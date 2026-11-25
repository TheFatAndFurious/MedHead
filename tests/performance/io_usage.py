"""Sample disk activity during a campaign, to tell disk waits from CPU or lock waits.

Every period, three `docker compose exec` calls read cumulative counters:
- database container: its cgroup I/O pressure and volume, plus the Docker VM disk
  (/proc/diskstats and /proc/pressure/io are not namespaced);
- redis container: its cgroup I/O pressure and volume, the per-second maxima of the AOF
  fsync latency (LATENCY HISTORY) and the delayed fsync counter;
- PostgreSQL: pg_stat_wal (with track_wal_io_timing) and a few snapshots of what the
  active sessions wait for.
"""
import subprocess

IO_FIELDS = ['timestamp', 'epoch', 'source', 'metric', 'value']
WAIT_FIELDS = ['timestamp', 'epoch', 'snapshots', 'wait_event_type', 'wait_event', 'backends']
LATENCY_FIELDS = ['event', 'epoch', 'latency_ms']
LATENCY_EVENTS = ['aof-fsync-always', 'aof-write']
WAIT_SNAPSHOTS = 10
WAIT_INTERVAL = 0.2
# Field positions after the device name in /proc/diskstats (kernel 5.5+).
DISK_FIELDS = {'writes': 4, 'sectors_written': 6, 'write_ms': 7, 'io_ticks_ms': 9,
               'flushes': 14, 'flush_ms': 15}
CGROUP_FILES = 'cat /sys/fs/cgroup/io.pressure; echo @io.stat; cat /sys/fs/cgroup/io.stat'
REDIS_CLI = ('REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --tls --cacert /tls/ca.crt '
             '--sni redis -h redis')


def parse_pressure(text, prefix=''):
    """`some ... total=N` lines (microseconds of stall) to {prefix + 'some_us': N, ...}."""
    values = {}
    for line in text.splitlines():
        parts = line.split()
        if parts and parts[0] in ('some', 'full'):
            total = next(part for part in parts if part.startswith('total='))
            values[f'{prefix}{parts[0]}_us'] = int(total.split('=')[1])
    return values


def parse_io_stat(text):
    """Sums rbytes, wbytes, rios and wios over the devices of a cgroup io.stat file."""
    totals = {'rbytes': 0, 'wbytes': 0, 'rios': 0, 'wios': 0}
    for line in text.splitlines():
        for item in line.split()[1:]:
            key, _, value = item.partition('=')
            if key in totals:
                totals[key] += int(value)
    return totals


def parse_diskstats(text):
    """Whole virtual or physical disks only (no partitions, loop or ram devices)."""
    disks = {}
    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 3 + max(DISK_FIELDS.values()) + 1:
            continue
        name = parts[2]
        if not (name.startswith(('vd', 'sd', 'xvd')) and name[-1].isalpha()
                or name.startswith('nvme') and 'p' not in name.removeprefix('nvme')):
            continue
        values = [int(value) for value in parts[3:]]
        disks[name] = {metric: values[index] for metric, index in DISK_FIELDS.items()}
    return disks


def split_sections(text):
    """Splits `@name` separated output; the text before the first marker is `head`."""
    sections, name = {'head': []}, 'head'
    for line in text.splitlines():
        if line.startswith('@'):
            name = line[1:].strip()
            sections[name] = []
        else:
            sections[name].append(line)
    return {key: '\n'.join(lines) for key, lines in sections.items()}


def parse_latency_history(text):
    """redis-cli prints LATENCY HISTORY as alternating timestamp and latency lines."""
    values = [int(line) for line in text.split()]
    return list(zip(values[0::2], values[1::2]))


def wait_query():
    statements = ['SELECT \'wal\', wal_records, wal_write, wal_sync, wal_write_time, '
                  'wal_sync_time FROM pg_stat_wal;']
    # Separate statements: each one is its own transaction and reads a fresh snapshot.
    for _ in range(WAIT_SNAPSHOTS):
        statements.append(
            "SELECT 'wait', coalesce(wait_event_type, 'CPU'), coalesce(wait_event, 'CPU'), count(*) "
            "FROM pg_stat_activity WHERE backend_type = 'client backend' AND state = 'active' "
            "AND pid <> pg_backend_pid() GROUP BY 2, 3;")
        statements.append(f"SELECT 'sleep' FROM pg_sleep({WAIT_INTERVAL});")
    return '\n'.join(statements) + '\n'


def parse_postgres(text):
    wal, waits = {}, {}
    for line in text.splitlines():
        parts = line.split('|')
        if parts[0] == 'wal':
            wal = {'wal_records': int(parts[1]), 'wal_write': int(parts[2]),
                   'wal_sync': int(parts[3]), 'wal_write_time_ms': float(parts[4]),
                   'wal_sync_time_ms': float(parts[5])}
        elif parts[0] == 'wait':
            key = (parts[1], parts[2])
            waits[key] = waits.get(key, 0) + int(parts[3])
    return wal, waits


def counter_rows(source, values, timestamp, epoch):
    return [{'timestamp': timestamp, 'epoch': round(epoch, 3), 'source': source,
             'metric': metric, 'value': value} for metric, value in values.items()]


def sample(compose_exec, timestamp, epoch):
    """Returns (io rows, wait rows, {(event, second): latency_ms})."""
    rows = []
    database = split_sections(compose_exec('database', ['sh', '-c',
        CGROUP_FILES + '; echo @vm; cat /proc/pressure/io; echo @disks; cat /proc/diskstats']))
    rows += counter_rows('database', {**parse_pressure(database['head'], 'io_'),
                                      **parse_io_stat(database['io.stat'])}, timestamp, epoch)
    rows += counter_rows('vm', parse_pressure(database['vm'], 'io_'), timestamp, epoch)
    for disk, values in parse_diskstats(database['disks']).items():
        rows += counter_rows(f'disk:{disk}', values, timestamp, epoch)

    script = CGROUP_FILES + ''.join(
        f'; echo @{event}; {REDIS_CLI} LATENCY HISTORY {event}' for event in LATENCY_EVENTS)
    script += f'; echo @persistence; {REDIS_CLI} INFO persistence'
    redis = split_sections(compose_exec('redis', ['sh', '-c', script]))
    delayed = [line.split(':')[1] for line in redis['persistence'].splitlines()
               if line.startswith('aof_delayed_fsync:')]
    rows += counter_rows('redis', {**parse_pressure(redis['head'], 'io_'),
                                   **parse_io_stat(redis['io.stat']),
                                   **({'aof_delayed_fsync': int(delayed[0])} if delayed else {})},
                         timestamp, epoch)
    latencies = {(event, second): latency for event in LATENCY_EVENTS
                 for second, latency in parse_latency_history(redis[event])}

    wal, waits = parse_postgres(compose_exec('database', [
        'psql', '-X', '-q', '-At', '-U', 'hospital_perf', '-d', 'hospital_performance'],
        wait_query()))
    rows += counter_rows('postgres', wal, timestamp, epoch)
    wait_rows = [{'timestamp': timestamp, 'epoch': round(epoch, 3), 'snapshots': WAIT_SNAPSHOTS,
                  'wait_event_type': kind, 'wait_event': event, 'backends': count}
                 for (kind, event), count in sorted(waits.items())]
    if not wait_rows:
        wait_rows = [{'timestamp': timestamp, 'epoch': round(epoch, 3), 'snapshots': WAIT_SNAPSHOTS,
                      'wait_event_type': '', 'wait_event': '', 'backends': 0}]
    return rows, wait_rows, latencies


def reset_redis_latency(compose_exec):
    """Records every Redis latency event of 1 ms or more, from a clean history."""
    compose_exec('redis', ['sh', '-c', f'{REDIS_CLI} CONFIG SET latency-monitor-threshold 1 '
                                       f'&& {REDIS_CLI} LATENCY RESET'])


def summarize(io_rows, wait_rows, latency_rows, profile, start_epoch, phase_at):
    """Per phase: rates and mean durations from counter deltas, wait shares, fsync latencies."""
    def phase(epoch):
        elapsed = float(epoch) - start_epoch
        return phase_at(profile, elapsed) if elapsed >= 0 else None

    series = {}
    for row in io_rows:
        series.setdefault((row['source'], row['metric']), []).append(
            (float(row['epoch']), float(row['value'])))
    summary = {}
    for item in profile:
        name = item['name']
        deltas = {}
        for key, points in series.items():
            points.sort()
            inside = [point for point in points if phase(point[0]) == name]
            before = [point for point in points if point[0] < inside[0][0]] if inside else []
            if not inside or (not before and len(inside) < 2):
                continue
            first = before[-1] if before else inside[0]
            seconds = inside[-1][0] - first[0]
            if seconds > 0:
                deltas[key] = (inside[-1][1] - first[1], seconds)
        summary[name] = derived(deltas)
        summary[name]['postgres_waits'] = wait_shares(
            [row for row in wait_rows if phase(row['epoch']) == name])
        summary[name]['redis_fsync'] = latency_summary(
            [row for row in latency_rows if row['event'] == 'aof-fsync-always'
             and phase(row['epoch']) == name])
    return summary


def derived(deltas):
    def rate(source, metric, scale=1.0):
        delta, seconds = deltas.get((source, metric), (None, None))
        return None if delta is None else round(delta / seconds * scale, 2)

    def mean(source, total, count):
        spent, _ = deltas.get((source, total), (None, None))
        number, _ = deltas.get((source, count), (None, None))
        return round(spent / number, 3) if spent is not None and number else None

    result = {}
    for source in ('database', 'redis', 'vm'):
        result[source] = {
            # Share of wall time during which at least one task waited for the disk.
            'io_pressure_some_percent': rate(source, 'io_some_us', 1e-4),
            'io_pressure_full_percent': rate(source, 'io_full_us', 1e-4)}
        if source != 'vm':
            result[source].update({'write_mib_s': rate(source, 'wbytes', 1 / 1024 ** 2),
                                   'write_iops': rate(source, 'wios')})
    disks = [source for source, metric in deltas if source.startswith('disk:') and metric == 'writes']
    if disks:
        disk = max(disks, key=lambda source: deltas[(source, 'writes')][0])
        result['disk'] = {'device': disk.split(':')[1],
                          'writes_per_s': rate(disk, 'writes'),
                          'write_mean_ms': mean(disk, 'write_ms', 'writes'),
                          'flushes_per_s': rate(disk, 'flushes'),
                          'flush_mean_ms': mean(disk, 'flush_ms', 'flushes'),
                          'utilisation_percent': rate(disk, 'io_ticks_ms', 0.1)}
    result['postgres_wal'] = {'syncs_per_s': rate('postgres', 'wal_sync'),
                              'sync_mean_ms': mean('postgres', 'wal_sync_time_ms', 'wal_sync'),
                              'writes_per_s': rate('postgres', 'wal_write'),
                              'write_mean_ms': mean('postgres', 'wal_write_time_ms', 'wal_write')}
    result['redis']['aof_delayed_fsync'] = rate('redis', 'aof_delayed_fsync')
    return result


def wait_shares(rows):
    """Average number of active sessions per wait event, over all snapshots of the phase."""
    snapshots = sum(int(row['snapshots']) for row in {row['epoch']: row for row in rows}.values())
    if not snapshots:
        return {}
    totals = {}
    for row in rows:
        if row['wait_event']:
            key = f"{row['wait_event_type']}:{row['wait_event']}"
            totals[key] = totals.get(key, 0) + int(row['backends'])
    ranked = sorted(totals.items(), key=lambda item: -item[1])
    return {'snapshots': snapshots,
            'active_sessions_mean': round(sum(totals.values()) / snapshots, 2),
            'by_event': {key: round(count / snapshots, 2) for key, count in ranked[:8]}}


def latency_summary(rows):
    """Seconds in which the slowest AOF fsync reached 1 ms or more."""
    values = sorted(int(row['latency_ms']) for row in rows)
    if not values:
        return {'seconds_over_1ms': 0}
    return {'seconds_over_1ms': len(values), 'max_ms': values[-1],
            'p50_ms': values[len(values) // 2], 'p95_ms': values[int(len(values) * 0.95)]}


def compose_exec_with(compose):
    def compose_exec(service, args, input=None):
        return subprocess.run(compose + ['exec', '-T', service, *args], input=input, text=True,
                              capture_output=True, check=True).stdout
    return compose_exec
