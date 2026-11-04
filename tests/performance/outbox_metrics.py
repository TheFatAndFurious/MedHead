"""Collect cumulative Micrometer measurements and interval statistics via Actuator."""
from datetime import datetime, timezone
import json
import time
from urllib.parse import urlencode
from urllib.request import urlopen

METRICS = {
    'outbox.batch.duration': ['full', 'empty', 'error'],
    'outbox.batch.gap': [None],
    'outbox.batch.size': ['full', 'empty', 'error'],
    'outbox.event.duration': ['published', 'empty', 'error'],
    'outbox.select.duration': ['found', 'empty', 'error'],
    'outbox.redis.group.duration': ['created', 'existing', 'error'],
    'outbox.redis.add.duration': ['success', 'error'],
}
FIELDS = ['timestamp', 'metric', 'outcome', 'base_unit', 'count', 'total', 'max',
          'interval_seconds', 'interval_count', 'interval_mean', 'interval_rate']


def parse_measurements(payload):
    values = {item['statistic']: item['value'] for item in payload['measurements']}
    total = values['TOTAL_TIME'] if 'TOTAL_TIME' in values else values['TOTAL']
    return values['COUNT'], total, values['MAX']


def collect(base_url, previous):
    rows = []
    for metric, outcomes in METRICS.items():
        for outcome in outcomes:
            query = '?' + urlencode({'tag': f'outcome:{outcome}'}) if outcome else ''
            url = f'{base_url}/actuator/metrics/{metric}{query}'
            with urlopen(url, timeout=2) as response:
                payload = json.load(response)
            count, total, maximum = parse_measurements(payload)
            now = time.monotonic()
            key = (metric, outcome)
            old = previous.get(key)
            row = dict(zip(FIELDS[:7], [datetime.now(timezone.utc).isoformat(), metric,
                                       outcome or '', payload.get('baseUnit') or 'events',
                                       count, total, maximum]))
            if old:
                elapsed = now - old[0]
                delta_count = count - old[1]
                delta_total = total - old[2]
                if delta_count < 0 or delta_total < 0:
                    raise ValueError(f'Metrics reset during campaign: {key}')
                row.update(interval_seconds=elapsed, interval_count=delta_count,
                           interval_mean=delta_total / delta_count if delta_count else '',
                           interval_rate=delta_count / elapsed)
            previous[key] = (now, count, total)
            rows.append(row)
    return rows
