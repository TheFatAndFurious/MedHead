#!/usr/bin/env python3
"""Run a destructive reset and load test only in the isolated performance stack."""
import argparse
import csv
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import platform
import subprocess
import threading
import time
from urllib.error import URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from campaign import (api_latency_verdict, beds_per_hospital, load_profile, outbox_verdict,
                      phase_at)
from outbox_metrics import FIELDS, collect
import resource_usage

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
COMPOSE = ['docker', 'compose', '-f', str(ROOT / 'docker-compose.performance.yaml')]
API_URL = 'http://127.0.0.1:58080'
TOKEN_LIFESPAN = 1800  # accessTokenLifespan of tests/performance/keycloak/medhead-realm.json
SAMPLE_PERIOD = 5


def command(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, text=True, check=True, **kwargs)


def sql(source, *variables):
    return command(COMPOSE + ['exec', '-T', 'database', 'psql', '-X', '-U', 'hospital_perf',
                              '-d', 'hospital_performance', '-v', 'ON_ERROR_STOP=1',
                              *variables], input=source, capture_output=True).stdout


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def host_description():
    if platform.system() == 'Darwin':
        brand, cpus, memory = command(['sysctl', '-n', 'machdep.cpu.brand_string', 'hw.ncpu',
                                       'hw.memsize'], capture_output=True).stdout.splitlines()
        return {'cpu': brand, 'cpus': int(cpus), 'memory_gib': round(int(memory) / 1024 ** 3, 1),
                'os': f'macOS {platform.mac_ver()[0]}'}
    return {'cpu': platform.processor(), 'cpus': os.cpu_count(), 'os': platform.platform()}


def container_limits(container_ids):
    limits = {}
    for item in json.loads(command(['docker', 'inspect', *container_ids], capture_output=True).stdout):
        service = item['Config']['Labels'].get('com.docker.compose.service', item['Name'])
        host = item['HostConfig']
        limits[service] = {'cpus': host['NanoCpus'] / 1e9 or None,
                           'memory_mib': host['Memory'] // 1024 ** 2 or None,
                           'image': item['Config']['Image']}
    return limits


def outbox_lag(profile, start_epoch):
    """Publication delay (published_at - occurred_at) per phase of occurrence, in milliseconds."""
    last = profile[-1]
    phases = ', '.join(
        f"('{phase['name']}', {start_epoch + phase['start']:.3f}, "
        f"{start_epoch + phase['start'] + phase['duration'] + (3600 if phase is last else 0):.3f})"
        for phase in profile)
    percentile = 'round(percentile_cont({}) WITHIN GROUP (ORDER BY ms)::numeric, 1)'
    output = sql(f"""COPY (
        WITH phase(name, starts, ends) AS (VALUES {phases}),
        event AS (
            SELECT p.name, e.published_at, extract(epoch FROM e.published_at - e.occurred_at) * 1000 AS ms
            FROM event_outbox e JOIN phase p
              ON extract(epoch FROM e.occurred_at) >= p.starts AND extract(epoch FROM e.occurred_at) < p.ends)
        SELECT name AS phase, count(*) AS events, count(published_at) AS published,
               {percentile.format(0.5)} AS lag_p50_ms, {percentile.format(0.95)} AS lag_p95_ms,
               {percentile.format(0.99)} AS lag_p99_ms, round(max(ms)::numeric, 1) AS lag_max_ms
        FROM event GROUP BY name) TO STDOUT WITH CSV HEADER;""")
    rows = list(csv.DictReader(output.splitlines()))
    for row in rows:
        for key in row:
            if key != 'phase':
                row[key] = float(row[key]) if row[key] else None
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['success', 'mixed'])
    parser.add_argument('--smoke', action='store_true', help='10 requests/s for 10 seconds')
    parser.add_argument('--peak-rate', type=int, default=int(os.environ.get('PEAK_RATE', 800)),
                        help='requests/s during the peak (default: $PEAK_RATE or 800)')
    parser.add_argument('--peak-duration', type=int,
                        default=int(os.environ.get('PEAK_DURATION', 300)),
                        help='peak duration in seconds (default: $PEAK_DURATION or 300)')
    args = parser.parse_args()
    profile = load_profile(args.smoke, args.peak_rate, args.peak_duration)
    total_duration = profile[-1]['start'] + profile[-1]['duration']
    if total_duration + 300 > TOKEN_LIFESPAN:
        parser.error(f'a {total_duration} s campaign would outlive the {TOKEN_LIFESPAN} s access token')
    beds = beds_per_hospital(profile, args.mode)
    results = ROOT / '.local' / 'performance'
    results.mkdir(parents=True, exist_ok=True)
    # Prevent two campaigns from resetting each other's database.
    with (results / 'campaign.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        output = results / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
                            + '-' + args.mode + ('-smoke' if args.smoke else ''))
        output.mkdir()
        command(['k6', 'version'], stdout=(output / 'k6-version.txt').open('w'))
        # Wait for the API only: one-shot services (TLS generators) make `up --wait` fail.
        command(COMPOSE + ['up', '--build', '-d', '--wait', '--wait-timeout', '300', 'api'],
                stdout=(output / 'startup.txt').open('w'))
        command(COMPOSE + ['config'], stdout=(output / 'compose.yaml').open('w'))
        commit = command(['git', 'rev-parse', 'HEAD'], capture_output=True).stdout.strip()
        working_tree = command(['git', 'status', '--porcelain'], capture_output=True).stdout
        (output / 'working-tree.txt').write_text(working_tree)
        docker_info = json.loads(command(['docker', 'info', '--format', '{{json .}}'],
                                         capture_output=True).stdout)
        containers = [json.loads(line) for line in command(
            COMPOSE + ['ps', '--format', 'json'], capture_output=True).stdout.splitlines() if line]
        container_ids = [container['ID'] for container in containers]
        services = {container['Name']: container['Service'] for container in containers}
        with urlopen(f'{API_URL}/actuator/info', timeout=5) as response:
            api_info = json.load(response)
        environment = {
            'commit': commit, 'working_tree_clean': not working_tree.strip(),
            'host': host_description(),
            'docker': {'server_version': docker_info['ServerVersion'],
                       'operating_system': docker_info['OperatingSystem'],
                       'cpus': docker_info['NCPU'],
                       'memory_gib': round(docker_info['MemTotal'] / 1024 ** 3, 1),
                       'compose_version': command(['docker', 'compose', 'version', '--short'],
                                                  capture_output=True).stdout.strip(),
                       'project': containers[0]['Project'] if containers else None},
            'k6_version': (output / 'k6-version.txt').read_text().strip(),
            'containers': container_limits(container_ids), 'api_info': api_info}
        print(sql((HERE / 'prepare.sql').read_text(), '-v', f'beds={beds}'), flush=True)
        command(COMPOSE + ['exec', '-T', 'redis', 'sh', '-c',
                'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --tls --cacert /tls/ca.crt '
                '--sni redis -h redis DEL medhead:reservations'])
        stop = threading.Event()
        monitor_errors = []
        # An overloaded API also delays Actuator: count these timeouts, keep sampling the outbox.
        actuator_errors = []
        previous_metrics = {}

        # A dedicated service account exists only in the isolated performance realm.
        credentials = urlencode({'grant_type': 'client_credentials', 'client_id': 'medhead-load',
                                 'client_secret': 'medhead_perf_only'}).encode()
        with urlopen(Request('http://localhost:58180/realms/medhead/protocol/openid-connect/token',
                             data=credentials), timeout=10) as response:
            access_token = json.load(response)['access_token']

        # Start from a baseline: a reused API may already have cumulative samples.
        with (output / 'outbox-metrics.csv').open('w') as metrics_log:
            writer = csv.DictWriter(metrics_log, fieldnames=FIELDS)
            writer.writeheader()
            writer.writerows(collect(API_URL, previous_metrics, access_token))

        def monitor():
            with (output / 'outbox.csv').open('w') as log, \
                    (output / 'outbox-metrics.csv').open('a') as metrics_log:
                writer = csv.DictWriter(metrics_log, fieldnames=FIELDS)
                log.write('timestamp,epoch,pending,oldest_pending_seconds\n')
                while True:
                    try:
                        row = sql("COPY (SELECT now(), extract(epoch FROM now()), count(*), "
                                  "coalesce(extract(epoch FROM now() - min(occurred_at)), 0) "
                                  "FROM event_outbox WHERE published_at IS NULL) TO STDOUT WITH CSV;")
                        log.write(row)
                        log.flush()
                    except subprocess.CalledProcessError as error:
                        monitor_errors.append(error.stderr)
                        return
                    try:
                        writer.writerows(collect(API_URL, previous_metrics, access_token))
                        metrics_log.flush()
                    except (URLError, OSError) as error:
                        actuator_errors.append(f'{utc_now()} {error}')
                    except (ValueError, KeyError) as error:
                        # A counter reset means the API restarted: the campaign is not valid.
                        monitor_errors.append(str(error))
                        return
                    if stop.wait(SAMPLE_PERIOD):
                        return

        k6_process = {}

        def sample_resources():
            # docker stats --no-stream takes about two seconds: keep a fixed period.
            with (output / 'resources.csv').open('w') as log:
                writer = csv.DictWriter(log, fieldnames=resource_usage.FIELDS)
                writer.writeheader()
                while True:
                    began = time.time()
                    try:
                        rows = resource_usage.sample_containers(container_ids, services, utc_now(), began)
                        if 'pid' in k6_process:
                            rows += resource_usage.sample_process(k6_process['pid'], 'k6 (host)',
                                                             utc_now(), time.time())
                    except (subprocess.CalledProcessError, ValueError, KeyError) as error:
                        monitor_errors.append(f'resources: {error}')
                        return
                    writer.writerows(rows)
                    log.flush()
                    if stop.wait(max(0.0, SAMPLE_PERIOD - (time.time() - began))):
                        return

        workers = [threading.Thread(target=target, daemon=True)
                   for target in (monitor, sample_resources)]
        for worker in workers:
            worker.start()
        try:
            with (output / 'k6.txt').open('w') as log:
                start_epoch = time.time()
                k6 = subprocess.Popen(['k6', 'run', '--no-color', '--quiet',
                    '--summary-export', str(output / 'summary.json'),
                    '-e', f'MODE={args.mode}', '-e', f'SMOKE={int(args.smoke)}',
                    '-e', f'PEAK_RATE={args.peak_rate}', '-e', f'PEAK_DURATION={args.peak_duration}',
                    str(HERE / 'reservations.js')], cwd=ROOT, text=True,
                    stdout=log, stderr=subprocess.STDOUT,
                    env={**os.environ, 'MEDHEAD_ACCESS_TOKEN': access_token})
                k6_process['pid'] = k6.pid
                k6_exit_code = k6.wait()
        finally:
            stop.set()
            for worker in workers:
                worker.join()
        # Capture the tail of the last interval and recovery before slow SQL checks.
        try:
            with (output / 'outbox-metrics.csv').open('a') as metrics_log:
                csv.DictWriter(metrics_log, fieldnames=FIELDS).writerows(collect(API_URL, previous_metrics, access_token))
        except (URLError, OSError) as error:
            actuator_errors.append(f'{utc_now()} {error}')
        except (ValueError, KeyError) as error:
            monitor_errors.append(str(error))
        pending = int(sql('SELECT count(*) FROM event_outbox WHERE published_at IS NULL;',
                          '-t', '-A').strip())
        # Failed requests each log a warning: print the summary, not hundreds of thousands of lines.
        k6_lines = (output / 'k6.txt').read_text().splitlines()
        warnings = [line for line in k6_lines if 'level=warning' in line]
        print('\n'.join(line for line in k6_lines if 'level=warning' not in line))
        print(f'{len(warnings)} k6 warnings (failed requests) in k6.txt')
        summary = json.loads((output / 'summary.json').read_text())
        created = int(summary['metrics'].get('reservations_created', {}).get('count', 0))
        try:
            consistency = sql((HERE / 'verify.sql').read_text(), '-v', f'created={created}')
            consistent = True
        except subprocess.CalledProcessError as error:
            consistency = error.stdout + error.stderr
            consistent = False
        (output / 'consistency.txt').write_text(consistency)
        print(consistency)
        # A request that timed out on the client may still have been committed by the API.
        matches_http = 'PASS: database reservations match' in consistency

        reservations, events, published = (int(value) for value in sql(
            'SELECT (SELECT count(*) FROM bed_reservation), (SELECT count(*) FROM event_outbox), '
            '(SELECT count(*) FROM event_outbox WHERE published_at IS NOT NULL);',
            '-t', '-A', '-F', ' ').split())
        with (output / 'outbox.csv').open() as log:
            samples = [{'epoch': float(row['epoch']), 'pending': int(row['pending']),
                        'oldest': float(row['oldest_pending_seconds'])} for row in csv.DictReader(log)]
        with (output / 'resources.csv').open() as log:
            resource_rows = list(csv.DictReader(log))
        api = api_latency_verdict(summary, profile, args.mode)
        outbox = outbox_verdict(samples, outbox_lag(profile, start_epoch), profile, start_epoch,
                                events, published, pending)
        verdict = {
            'mode': args.mode, 'smoke': args.smoke, 'started_at':
                datetime.fromtimestamp(start_epoch, timezone.utc).isoformat(),
            'profile': profile, 'beds_per_available_hospital': beds, 'environment': environment,
            'k6_exit_code': k6_exit_code, 'k6_warnings': len(warnings),
            # The smoke run validates the chain only, not the requirement.
            'api_latency': api, 'outbox': outbox,
            'consistency': {'passed': consistent, 'matches_http': matches_http,
                            'created_http': created, 'reservations_in_database': reservations},
            'resources': resource_usage.summarize(resource_rows, profile, start_epoch, phase_at),
            'monitor_errors': monitor_errors, 'actuator_errors': len(actuator_errors),
            'actuator_error_samples': actuator_errors[:5]}
        (output / 'verdict.json').write_text(json.dumps(verdict, indent=2, ensure_ascii=False) + '\n')
        note = ' (smoke: informative only, cold JVM)' if args.smoke else ''
        print(f"(a) API latency{note}: {'PASS' if api['passed'] else 'FAIL'} {api['failures']}")
        print(f"(b) Outbox{note}: {'PASS' if outbox['passed'] else 'FAIL'} {outbox['failures']}")
        print(f'Data consistency: {"PASS" if consistent else "FAIL"}; '
              f'{reservations} reservations in database, {created} 201 responses received')
        if actuator_errors:
            print(f'{len(actuator_errors)} Actuator samples failed (overloaded API)')
        print(f'Results: {output}')
        failed = not consistent or bool(monitor_errors) or k6_exit_code != 0
        if not args.smoke:
            failed = failed or not api['passed'] or not outbox['passed']
        if failed:
            raise SystemExit(1)


if __name__ == '__main__':
    main()
