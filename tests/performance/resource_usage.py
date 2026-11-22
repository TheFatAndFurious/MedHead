"""Sample container (docker stats) and load generator (ps) resources during a campaign."""
import json
import re
import subprocess

FIELDS = ['timestamp', 'epoch', 'name', 'cpu_percent', 'mem_used_mib', 'mem_limit_mib', 'pids']
UNITS = {'b': 1, 'kb': 1e3, 'kib': 1024, 'mb': 1e6, 'mib': 1024 ** 2,
         'gb': 1e9, 'gib': 1024 ** 3, 'tb': 1e12, 'tib': 1024 ** 4}


def to_mib(text):
    match = re.fullmatch(r'\s*([\d.]+)\s*([a-zA-Z]+)\s*', text)
    if not match:
        raise ValueError(f'Unexpected size: {text!r}')
    return round(float(match.group(1)) * UNITS[match.group(2).lower()] / 1024 ** 2, 1)


def parse_docker_stats(lines, services, timestamp, epoch):
    """One row per container; `services` maps container names to Compose service names."""
    rows = []
    for line in lines:
        if not line.strip():
            continue
        stats = json.loads(line)
        used, limit = stats['MemUsage'].split('/')
        rows.append({'timestamp': timestamp, 'epoch': round(epoch, 3),
                     'name': services.get(stats['Name'], stats['Name']),
                     'cpu_percent': float(stats['CPUPerc'].rstrip('%')),
                     'mem_used_mib': to_mib(used), 'mem_limit_mib': to_mib(limit),
                     'pids': int(stats['PIDs'])})
    return rows


def sample_process(pid, name, timestamp, epoch):
    """CPU (one core = 100 %) and resident memory of a host process, such as k6."""
    output = subprocess.run(['ps', '-o', '%cpu=,rss=', '-p', str(pid)],
                            capture_output=True, text=True).stdout.split()
    if len(output) != 2:
        return []
    return [{'timestamp': timestamp, 'epoch': round(epoch, 3), 'name': name,
             'cpu_percent': float(output[0].replace(',', '.')),
             'mem_used_mib': round(int(output[1]) / 1024, 1), 'mem_limit_mib': '', 'pids': ''}]


def sample_containers(container_ids, services, timestamp, epoch):
    output = subprocess.run(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *container_ids],
                            capture_output=True, text=True, check=True).stdout
    return parse_docker_stats(output.splitlines(), services, timestamp, epoch)


def summarize(rows, profile, start_epoch, phase_at):
    """Mean and maximum CPU, maximum memory, per resource and per phase."""
    groups = {}
    for row in rows:
        elapsed = float(row['epoch']) - start_epoch
        if elapsed < 0:
            continue
        key = (row['name'], phase_at(profile, elapsed))
        groups.setdefault(key, []).append(row)
    summary = []
    for (name, phase), samples in sorted(groups.items()):
        cpu = [float(sample['cpu_percent']) for sample in samples]
        memory = [float(sample['mem_used_mib']) for sample in samples]
        summary.append({'name': name, 'phase': phase, 'samples': len(samples),
                        'cpu_mean_percent': round(sum(cpu) / len(cpu), 1),
                        'cpu_max_percent': round(max(cpu), 1),
                        'mem_max_mib': round(max(memory), 1)})
    return summary

