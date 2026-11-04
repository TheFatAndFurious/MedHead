#!/usr/bin/env python3
"""Run a destructive reset and load test only in the isolated performance stack."""
import argparse
import csv
from datetime import datetime, timezone
import fcntl
import json
from pathlib import Path
import subprocess
import threading
from urllib.error import URLError

from outbox_metrics import FIELDS, collect

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
COMPOSE = ['docker', 'compose', '-f', str(ROOT / 'docker-compose.performance.yaml')]
API_URL = 'http://127.0.0.1:58080'


def command(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, text=True, check=True, **kwargs)


def sql(source, *variables):
    return command(COMPOSE + ['exec', '-T', 'database', 'psql', '-X', '-U', 'hospital_perf',
                              '-d', 'hospital_performance', '-v', 'ON_ERROR_STOP=1',
                              *variables], input=source, capture_output=True).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['success', 'mixed'])
    parser.add_argument('--smoke', action='store_true', help='10 requests/s for 10 seconds')
    args = parser.parse_args()
    results = ROOT / '.local' / 'performance'
    results.mkdir(parents=True, exist_ok=True)
    # Prevent two campaigns from resetting each other's database.
    with (results / 'campaign.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        output = results / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
                            + '-' + args.mode)
        output.mkdir()
        command(['k6', 'version'], stdout=(output / 'k6-version.txt').open('w'))
        command(COMPOSE + ['up', '--build', '-d', '--wait'],
                stdout=(output / 'startup.txt').open('w'))
        command(COMPOSE + ['config'], stdout=(output / 'compose.yaml').open('w'))
        command(['git', 'rev-parse', 'HEAD'], stdout=(output / 'commit.txt').open('w'))
        command(['git', 'status', '--short'], stdout=(output / 'working-tree.txt').open('w'))
        command(['docker', 'info', '--format', '{{json .}}'],
                stdout=(output / 'docker-info.json').open('w'))
        print(sql((HERE / 'prepare.sql').read_text()), flush=True)
        command(COMPOSE + ['exec', '-T', 'redis', 'sh', '-c',
                'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --tls --cacert /tls/ca.crt '
                '--sni redis -h redis DEL medhead:reservations'])
        stop = threading.Event()
        monitor_errors = []
        previous_metrics = {}

        # Start from a baseline: a reused API may already have cumulative samples.
        with (output / 'outbox-metrics.csv').open('w') as metrics_log:
            writer = csv.DictWriter(metrics_log, fieldnames=FIELDS)
            writer.writeheader()
            writer.writerows(collect(API_URL, previous_metrics))

        def monitor():
            with (output / 'outbox.csv').open('w') as log, \
                    (output / 'outbox-metrics.csv').open('a') as metrics_log:
                writer = csv.DictWriter(metrics_log, fieldnames=FIELDS)
                log.write('timestamp,pending,oldest_pending_seconds\n')
                while True:
                    try:
                        row = sql("COPY (SELECT now(), count(*), coalesce(extract(epoch FROM "
                                  "now() - min(occurred_at)), 0) FROM event_outbox "
                                  "WHERE published_at IS NULL) TO STDOUT WITH CSV;")
                        log.write(row)
                        log.flush()
                        writer.writerows(collect(API_URL, previous_metrics))
                        metrics_log.flush()
                    except subprocess.CalledProcessError as error:
                        monitor_errors.append(error.stderr)
                        return
                    except (URLError, OSError, ValueError, KeyError) as error:
                        monitor_errors.append(str(error))
                        return
                    if stop.wait(5):
                        return

        worker = threading.Thread(target=monitor, daemon=True)
        worker.start()
        try:
            with (output / 'k6.txt').open('w') as log:
                run = subprocess.run(['k6', 'run', '--no-color', '--quiet',
                    '--summary-export', str(output / 'summary.json'),
                    '-e', f'MODE={args.mode}', '-e', f'SMOKE={int(args.smoke)}',
                    str(HERE / 'reservations.js')], cwd=ROOT, text=True,
                    stdout=log, stderr=subprocess.STDOUT)
        finally:
            stop.set()
            worker.join()
        # Capture the tail of the last interval and recovery before slow SQL checks.
        try:
            with (output / 'outbox-metrics.csv').open('a') as metrics_log:
                csv.DictWriter(metrics_log, fieldnames=FIELDS).writerows(collect(API_URL, previous_metrics))
        except (URLError, OSError, ValueError, KeyError) as error:
            monitor_errors.append(str(error))
        pending = int(sql('SELECT count(*) FROM event_outbox WHERE published_at IS NULL;',
                          '-t', '-A').strip())
        print((output / 'k6.txt').read_text())
        summary = json.loads((output / 'summary.json').read_text())
        created = int(summary['metrics'].get('reservations_created', {}).get('count', 0))
        consistency = sql((HERE / 'verify.sql').read_text(), '-v', f'created={created}')
        (output / 'consistency.txt').write_text(consistency)
        print(consistency)
        # Smoke validates wiring, not the five-minute recovery requirement.
        recovered = args.smoke or pending == 0
        verdict = {'mode': args.mode, 'smoke': args.smoke, 'k6_exit_code': run.returncode,
                   'created': created, 'pending_at_end': pending,
                   'recovery_passed': None if args.smoke else recovered,
                   'monitor_errors': monitor_errors}
        (output / 'verdict.json').write_text(json.dumps(verdict, indent=2) + '\n')
        print(f'Results: {output}')
        if run.returncode or not recovered or monitor_errors:
            raise SystemExit(1)


if __name__ == '__main__':
    main()
