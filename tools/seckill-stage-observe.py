"""Read-only stage sampling for an isolated seckill activity; no application instrumentation."""
import argparse
import json
import os
from pathlib import Path
import re
import socket
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT.parent / '.runtime/perf-python'))
import pymysql
import requests
import yaml


def resolve(value):
    return re.sub(r'\$\{([^:}]+):([^}]+)\}',
                  lambda m: os.getenv(m[1], m[2]), str(value))


class Redis:
    def __init__(self, port):
        self.sock = socket.create_connection(('127.0.0.1', port), timeout=5)
        self.reader = self.sock.makefile('rb')

    def read(self):
        line = self.reader.readline().rstrip(b'\r\n')
        prefix, value = line[:1], line[1:]
        if prefix == b':': return int(value)
        if prefix == b'+': return value.decode()
        if prefix == b'-': raise RuntimeError('Redis command failed')
        if prefix == b'$':
            size = int(value)
            if size < 0: return None
            data = self.reader.read(size); self.reader.read(2)
            return data.decode()
        if prefix == b'*': return [self.read() for _ in range(int(value))]
        raise RuntimeError('Invalid Redis response')

    def command(self, *args):
        parts = [str(a).encode() for a in args]
        self.sock.sendall(b'*%d\r\n' % len(parts) + b''.join(
            b'$%d\r\n' % len(p) + p + b'\r\n' for p in parts))
        return self.read()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--db', required=True)
    parser.add_argument('--activity', type=int, required=True)
    parser.add_argument('--redis-port', type=int, default=16380)
    parser.add_argument('--vhost', required=True)
    parser.add_argument('--seconds', type=int, default=240)
    parser.add_argument('--interval', type=float, default=2)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--light', action='store_true', help='Index-only confirmed row counts for a dedicated vhost; no JSON/full-body scanning')
    args = parser.parse_args()
    if not re.fullmatch(r'sky_perf_[A-Za-z0-9_]+', args.db) or not args.vhost.startswith('sky_perf_'):
        parser.error('Only isolated sky_perf databases and vhosts are supported')
    config = yaml.safe_load((ROOT / 'trade-service/src/main/resources/application-dev.yml').read_text(encoding='utf8'))['sky']
    db = config['datasource']; broker = config['rabbitmq']
    connection = pymysql.connect(host=resolve(db['host']), port=int(resolve(db['port'])),
        user=resolve(db['username']), password=resolve(db['password']), database=args.db,
        autocommit=True, read_timeout=10)
    redis = Redis(args.redis_port)
    session = requests.Session()
    session.auth = (resolve(broker['username']), resolve(broker['password']))
    queue_url = 'http://' + resolve(broker['host']) + ':15672/api/queues/' + requests.utils.quote(args.vhost, safe='') + '/sky.seckill.order.v2.queue'
    prefix = 'seckill:v3:{' + str(args.activity) + '}:'
    args.output.parent.mkdir(parents=True, exist_ok=True)
    start = time.monotonic(); previous = None
    with args.output.open('w', encoding='utf8') as output:
        while time.monotonic() - start < args.seconds:
            tick = time.monotonic()
            row = {'wallTime': time.time(), 'elapsed': tick-start}
            row['accepted'] = redis.command('HLEN', prefix+'requests')
            row['redisPending'] = redis.command('HLEN', prefix+'commands')
            row['outstanding'] = redis.command('HLEN', prefix+'outstanding')
            with connection.cursor() as query:
                query.execute('SELECT state,COUNT(*) FROM seckill_submit_request WHERE activity_id=%s GROUP BY state', (args.activity,))
                states = dict(query.fetchall())
                row.update(bridged=sum(states.values()), succeeded=states.get('SUCCEEDED', 0),
                           failed=states.get('FAILED', 0), dbPending=states.get('ACCEPTED', 0))
                if args.light:
                    query.execute("SELECT status,COUNT(*) FROM mq_fail_message WHERE routing_key='seckill.order' GROUP BY status")
                    outbox = {str(status): {'rows': total} for status,total in query.fetchall()}
                    row['publishedRows'] = outbox.get('1', {}).get('rows', 0)
                    row['publicationCountKind'] = 'all_dedicated_schema_confirmed_rows_including_repeats'
                else:
                    query.execute("SELECT status,COUNT(*),COUNT(DISTINCT JSON_UNQUOTE(JSON_EXTRACT(message_body,'$.orderNumber'))) FROM mq_fail_message WHERE routing_key='seckill.order' AND JSON_UNQUOTE(JSON_EXTRACT(message_body,'$.activityId'))=%s GROUP BY status", (str(args.activity),))
                    outbox = {str(status): {'rows': total, 'uniqueOrders': unique} for status,total,unique in query.fetchall()}
                    row['publishedUnique'] = outbox.get('1', {}).get('uniqueOrders', 0)
                row['outbox'] = outbox
                query.execute('SELECT l.OBJECT_NAME,COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON w.REQUESTING_ENGINE_LOCK_ID=l.ENGINE_LOCK_ID AND w.ENGINE=l.ENGINE WHERE l.OBJECT_SCHEMA=%s GROUP BY l.OBJECT_NAME', (args.db,))
                row['lockWaiters'] = dict(query.fetchall())
            response = session.get(queue_url, timeout=5)
            if response.status_code == 200:
                queue = response.json(); stats = queue.get('message_stats', {})
                row['mq'] = {'ready': queue.get('messages_ready', 0), 'unacked': queue.get('messages_unacknowledged', 0),
                             'consumers': queue.get('consumers', 0), **{k: stats.get(k, 0) for k in ['publish','deliver_get','ack','redeliver']}}
            elif response.status_code != 404:
                raise RuntimeError('Broker observation failed: '+str(response.status_code))
            row['sampleCostMs'] = (time.monotonic()-tick)*1000
            if previous:
                delta = tick-previous['tick']
                row['rates'] = {name: (row[name]-previous['row'][name])/delta
                               for name in ['accepted','bridged', 'publishedRows' if args.light else 'publishedUnique','succeeded','failed']}
                if 'mq' in row and 'mq' in previous['row']:
                    row['mqRates'] = {name: (row['mq'][name]-previous['row']['mq'][name])/delta
                                     for name in ['publish','deliver_get','ack','redeliver']}
            output.write(json.dumps(row, ensure_ascii=False)+'\n'); output.flush()
            previous = {'tick': tick, 'row': row}
            if int(row['elapsed']) % 10 < args.interval:
                print(json.dumps({k: row[k] for k in ['elapsed','accepted','bridged','succeeded','failed','redisPending','dbPending']}), flush=True)
            time.sleep(max(0, args.interval-(time.monotonic()-tick)))
    connection.close(); session.close(); redis.sock.close()


if __name__ == '__main__':
    main()
