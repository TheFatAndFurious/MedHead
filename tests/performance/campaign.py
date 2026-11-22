"""Load profile and the two separate verdicts of a campaign (pure functions, unit tested).

The phase arithmetic mirrors reservations.js: both read PEAK_RATE and PEAK_DURATION.
"""
import math

THRESHOLD_MS = 200
NOMINAL_RATE = 80
AVAILABLE_HOSPITALS = 1600
BED_MARGIN = 1.2


def load_profile(smoke, peak_rate=800, peak_duration=300):
    """Phases in seconds from the start of k6; `ramp` phases are excluded from the verdicts."""
    if smoke:
        return [{'name': 'smoke', 'start': 0, 'duration': 10, 'rate': 10, 'ramp': False}]
    phases = [('warmup', 120, NOMINAL_RATE, True), ('nominal', 300, NOMINAL_RATE, False),
              ('peak', peak_duration, peak_rate, False), ('recovery', 300, NOMINAL_RATE, False)]
    profile, start = [], 0
    for name, duration, rate, ramp in phases:
        profile.append({'name': name, 'start': start, 'duration': duration, 'rate': rate, 'ramp': ramp})
        start += duration
    return profile


def planned_requests(phase):
    total = phase['rate'] * phase['duration']
    return total // 2 if phase['ramp'] else total


def beds_per_hospital(profile, mode):
    """Enough beds for every planned reservation, with a 20 % margin, spread over 1 600 hospitals."""
    share = 1 if mode == 'success' else 0.5
    reservations = sum(planned_requests(phase) for phase in profile) * share
    return max(100, math.ceil(reservations * BED_MARGIN / AVAILABLE_HOSPITALS))


def _trend(metric):
    return {key: metric.get(source) for key, source in
            (('p50', 'med'), ('p95', 'p(95)'), ('p99', 'p(99)'), ('max', 'max'), ('avg', 'avg'))}


def api_latency_verdict(summary, profile, mode, threshold_ms=THRESHOLD_MS):
    """Verdict (a): p95 and p99 below the threshold in every measured phase, no technical error,
    every planned request sent and no dropped iteration."""
    metrics = summary['metrics']
    flows = ['available', 'unavailable'] if mode == 'mixed' else ['available']
    phases, scenarios, failures = [], [], []
    for phase in profile:
        if phase['ramp']:
            continue
        name = phase['name']
        duration = metrics.get(f'http_req_duration{{phase:{name}}}')
        if duration is None:
            failures.append(f'{name}: no latency measured')
            continue
        requests = int(metrics.get(f'http_reqs{{phase:{name}}}', {}).get('count', 0))
        errors = metrics.get(f'http_req_failed{{phase:{name}}}', {}).get('value')
        row = {'phase': name, 'target_rate': phase['rate'], 'duration_s': phase['duration'],
               'planned_requests': planned_requests(phase), 'requests': requests,
               'throughput': round(requests / phase['duration'], 1), 'error_rate': errors,
               **_trend(duration)}
        phases.append(row)
        if row['p95'] is None or row['p95'] >= threshold_ms:
            failures.append(f'{name}: p95 {row["p95"]} ms')
        if row['p99'] is None or row['p99'] >= threshold_ms:
            failures.append(f'{name}: p99 {row["p99"]} ms')
        if errors != 0:
            failures.append(f'{name}: HTTP error rate {errors}')
        if requests < row['planned_requests']:
            failures.append(f'{name}: {requests} requests sent, {row["planned_requests"]} planned')
        for flow in flows:
            scenario = metrics.get(f'http_req_duration{{scenario:{name}_{flow}}}')
            if scenario is not None:
                scenarios.append({'phase': name, 'flow': flow, **_trend(scenario)})
    dropped = int(metrics.get('dropped_iterations', {}).get('count', 0))
    if dropped:
        failures.append(f'{dropped} dropped iterations')
    checks = metrics.get('checks', {}).get('value')
    if checks != 1:
        failures.append(f'business checks rate {checks}')
    return {'passed': not failures, 'threshold_ms': threshold_ms, 'dropped_iterations': dropped,
            'checks_rate': checks, 'phases': phases, 'scenarios': scenarios, 'failures': failures}


def phase_at(profile, elapsed):
    """Name of the phase running `elapsed` seconds after the start of k6 (last phase for the tail)."""
    for phase in profile:
        if elapsed < phase['start'] + phase['duration']:
            return phase['name']
    return profile[-1]['name']


def outbox_verdict(samples, lag_rows, profile, start_epoch, created, published, pending_at_end):
    """Verdict (b): every BedReserved event published once the campaign is over.

    `samples` are outbox.csv rows (epoch, pending, oldest_pending_seconds) and `lag_rows`
    the publication delays per phase computed in PostgreSQL. No numeric publication
    delay is specified, so delays are reported, not judged."""
    per_phase = {phase['name']: {'phase': phase['name'], 'max_pending': 0, 'max_oldest_pending_s': 0.0}
                 for phase in profile}
    peak = next((phase for phase in profile if phase['name'] == 'peak'), None)
    peak_end = start_epoch + peak['start'] + peak['duration'] if peak else None
    drained_after_peak = None
    for sample in samples:
        elapsed = sample['epoch'] - start_epoch
        if elapsed < 0:
            continue
        row = per_phase[phase_at(profile, elapsed)]
        row['max_pending'] = max(row['max_pending'], sample['pending'])
        row['max_oldest_pending_s'] = max(row['max_oldest_pending_s'], round(sample['oldest'], 2))
        # First sample after the peak where no event waits more than a second.
        if peak_end and drained_after_peak is None and sample['epoch'] >= peak_end and sample['oldest'] < 1:
            drained_after_peak = round(sample['epoch'] - peak_end, 1)
    for lag in lag_rows:
        if lag['phase'] in per_phase:
            per_phase[lag['phase']].update({key: lag[key] for key in lag if key != 'phase'})
    failures = []
    if pending_at_end:
        failures.append(f'{pending_at_end} events still pending at the end')
    if published != created:
        failures.append(f'{published} events published for {created} reservations')
    return {'passed': not failures, 'created': created, 'published': published,
            'pending_at_end': pending_at_end, 'drained_after_peak_s': drained_after_peak,
            'phases': list(per_phase.values()), 'failures': failures}
