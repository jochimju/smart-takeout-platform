"""Bounded, read-only diagnosis of occupied seckill slots in an isolated environment.

Redis is sampled atomically; subsequent MySQL/MQ/HTTP samples are not a distributed
snapshot. Transitions during sampling can move a slot into a later stage.
"""
import argparse
import importlib.util
import json
import time
from pathlib import Path

spec = importlib.util.spec_from_file_location('stages', Path(__file__).with_name('seckill-stage-observe.py'))
stages = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stages)

SNAPSHOT = """
if redis.call('hlen',KEYS[1])>500 then return redis.error_reply('slot observation bound exceeded') end
local slots=redis.call('hgetall',KEYS[1])
return {redis.call('hlen',KEYS[2]),redis.call('hlen',KEYS[3]),redis.call('time'),slots}
"""


def classify(slots, rows, now_ms):
    groups = {name: [] for name in ('not_bridged', 'awaiting_order', 'terminal_awaiting_release', 'unexpected_state')}
    for number, accepted_at in slots.items():
        state = rows.get(number)
        name = ('not_bridged' if state is None else 'awaiting_order' if state == 'ACCEPTED'
                else 'terminal_awaiting_release' if state in ('SUCCEEDED', 'FAILED', 'CANCELLED')
                else 'unexpected_state')
        groups[name].append(max(0, now_ms-int(accepted_at)))
    return {name: {'count': len(ages), 'oldestMs': max(ages, default=0),
                   'meanAgeMs': sum(ages)/len(ages) if ages else 0} for name, ages in groups.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--db', required=True)
    parser.add_argument('--activity', type=int, required=True)
    parser.add_argument('--vhost', required=True)
    parser.add_argument('--redis-port', type=int, default=16380)
    parser.add_argument('--seconds', type=int, default=90)
    parser.add_argument('--interval', type=float, default=2)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not stages.re.fullmatch(r'sky_perf_[A-Za-z0-9_]+', args.db) or not stages.re.fullmatch(r'sky_perf_[A-Za-z0-9_]+', args.vhost):
        parser.error('Only isolated sky_perf databases/vhosts are allowed')
    if args.interval < 1 or args.seconds < 1:
        parser.error('Sampling interval must be at least one second')
    cfg = stages.yaml.safe_load((stages.ROOT/'trade-service/src/main/resources/application-dev.yml').read_text(encoding='utf8'))['sky']
    db, broker = cfg['datasource'], cfg['rabbitmq']
    connection = stages.pymysql.connect(host=stages.resolve(db['host']), port=int(stages.resolve(db['port'])),
        user=stages.resolve(db['username']), password=stages.resolve(db['password']), database=args.db,
        autocommit=True, read_timeout=3)
    redis = stages.Redis(args.redis_port)
    session = stages.requests.Session()
    session.auth = (stages.resolve(broker['username']), stages.resolve(broker['password']))
    queues_url = 'http://'+stages.resolve(broker['host'])+':15672/api/queues/'+args.vhost
    routes = ['seckill.order']+['seckill.order.bucket.'+str(i) for i in range(8)]
    prefix = 'seckill:v3:{'+str(args.activity)+'}:'
    start = time.monotonic()
    previous = None
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        with args.output.open('w', encoding='utf8') as output:
            while time.monotonic()-start < args.seconds:
                tick = time.monotonic()
                row = {'elapsed': tick-start, 'wallTime': time.time()}
                try:
                    accepted, pending, server_time, raw = redis.command('EVAL', SNAPSHOT, 3,
                        prefix+'outstanding', prefix+'requests', prefix+'commands')
                    slots = dict(zip(raw[::2], raw[1::2]))
                    if len(slots) > 500:
                        raise RuntimeError('Observed more than the configured 500 slots; refuse unbounded probe')
                    now_ms = int(server_time[0])*1000+int(server_time[1])//1000
                    states = {}
                    with connection.cursor() as query:
                        if slots:
                            query.execute('SELECT order_number,state FROM seckill_submit_request WHERE order_number IN ('+
                                ','.join(['%s']*len(slots))+')', list(slots))
                            states = dict(query.fetchall())
                        query.execute('SELECT routing_key,status,COUNT(*) FROM mq_fail_message FORCE INDEX(idx_mq_route_claim) '
                            'WHERE routing_key IN ('+','.join(['%s']*len(routes))+') AND status IN (0,2,3) GROUP BY routing_key,status', routes)
                        row['outboxPendingByRoute'] = [{'route': route, 'status': status, 'count': count}
                                                      for route, status, count in query.fetchall()]
                    row.update(accepted=accepted, redisCommands=pending, outstanding=len(slots),
                               released=accepted-len(slots), slots=classify(slots, states, now_ms))
                    row['dbSampleOffsetMs'] = (time.monotonic()-tick)*1000
                    response = session.get(queues_url, timeout=3)
                    response.raise_for_status()
                    row['queues'] = {q['name']: {key: q.get(key) for key in
                        ('messages_ready', 'messages_unacknowledged', 'consumers')} for q in response.json()
                        if q['name'].startswith('sky.seckill.order.')}
                    metrics = stages.requests.get('http://127.0.0.1:18081/actuator/prometheus',
                        headers={'X-Internal-Token': 'perf-internal-isolated'}, timeout=3)
                    metrics.raise_for_status()
                    row['metrics'] = [line for line in metrics.text.splitlines() if line.startswith((
                        'seckill_profile_', 'seckill_admission_', 'sky_jdbc_pool_', 'feign_pool_',
                        'process_cpu_usage', 'system_cpu_usage', 'jvm_gc_pause_seconds_sum',
                        'jvm_gc_pause_seconds_count'))]
                    if previous:
                        delta = tick-previous['tick']
                        row['rates'] = {key: (row[key]-previous['row'][key])/delta for key in ('accepted', 'released')}
                    previous = {'tick': tick, 'row': row}
                except Exception as error:
                    row['errorType'] = type(error).__name__
                row['sampleCostMs'] = (time.monotonic()-tick)*1000
                output.write(json.dumps(row, ensure_ascii=False)+'\n')
                output.flush()
                time.sleep(max(0, args.interval-(time.monotonic()-tick)))
    finally:
        connection.close()
        session.close()
        redis.reader.close()
        redis.sock.close()


if __name__ == '__main__':
    main()
