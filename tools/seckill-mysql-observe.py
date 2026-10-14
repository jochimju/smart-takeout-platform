"""Read-only commit and file-wait samples. File counters cover the whole MySQL instance."""
import argparse
import importlib.util
import json
import time
from pathlib import Path

spec = importlib.util.spec_from_file_location('stages', Path(__file__).with_name('seckill-stage-observe.py'))
stages = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stages)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--db', required=True)
    parser.add_argument('--seconds', type=int, default=90)
    parser.add_argument('--interval', type=float, default=2)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not stages.re.fullmatch(r'sky_perf_[A-Za-z0-9_]+', args.db) or args.interval < 1:
        parser.error('Only isolated schemas and intervals >= 1 second are allowed')
    db = stages.yaml.safe_load((stages.ROOT/'trade-service/src/main/resources/application-dev.yml').read_text(encoding='utf8'))['sky']['datasource']
    connection = stages.pymysql.connect(host=stages.resolve(db['host']), port=int(stages.resolve(db['port'])),
        user=stages.resolve(db['username']), password=stages.resolve(db['password']),
        database=args.db, autocommit=True, read_timeout=3)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    start = time.monotonic()
    try:
        with args.output.open('w', encoding='utf8') as output:
            while time.monotonic()-start < args.seconds:
                tick = time.monotonic()
                row = {'wallTime': time.time(), 'elapsed': tick-start}
                try:
                    with connection.cursor() as query:
                        query.execute("SELECT COALESCE(SUM(COUNT_STAR),0),COALESCE(SUM(SUM_TIMER_WAIT),0),COALESCE(SUM(SUM_LOCK_TIME),0) "
                                      "FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=%s AND DIGEST_TEXT='COMMIT'", (args.db,))
                        row['schemaCommit'] = dict(zip(('count', 'waitPs', 'lockPs'), map(int, query.fetchone())))
                        query.execute("SELECT EVENT_NAME,SUM(COUNT_READ),SUM(SUM_TIMER_READ),SUM(COUNT_WRITE),SUM(SUM_TIMER_WRITE),SUM(COUNT_MISC),SUM(SUM_TIMER_MISC) "
                                      "FROM performance_schema.file_summary_by_instance WHERE EVENT_NAME IN "
                                      "('wait/io/file/sql/binlog','wait/io/file/innodb/innodb_log_file','wait/io/file/innodb/innodb_data_file','wait/io/file/innodb/innodb_dblwr_file') GROUP BY EVENT_NAME")
                        row['globalFileWaits'] = {name: dict(zip(('reads','readPs','writes','writePs','misc','miscPs'),map(int, values)))
                                                  for name, *values in query.fetchall()}
                        query.execute("SELECT l.OBJECT_NAME,COUNT(*) FROM performance_schema.data_lock_waits w "
                                      "JOIN performance_schema.data_locks l ON w.REQUESTING_ENGINE_LOCK_ID=l.ENGINE_LOCK_ID AND w.ENGINE=l.ENGINE "
                                      "WHERE l.OBJECT_SCHEMA=%s GROUP BY l.OBJECT_NAME", (args.db,))
                        row['schemaLockWaiters'] = dict(query.fetchall())
                except Exception as failure:
                    row['errorType'] = type(failure).__name__
                row['sampleCostMs'] = (time.monotonic()-tick)*1000
                output.write(json.dumps(row)+'\n')
                output.flush()
                time.sleep(max(0,args.interval-(time.monotonic()-tick)))
    finally:
        connection.close()


if __name__ == '__main__':
    main()
