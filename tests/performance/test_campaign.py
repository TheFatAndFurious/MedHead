import unittest

from campaign import (api_latency_verdict, beds_per_hospital, load_profile, outbox_verdict,
                      phase_at)


def trend(p95, p99=None):
    return {'avg': 10, 'med': 8, 'p(95)': p95, 'p(99)': p99 if p99 is not None else p95, 'max': 300}


def summary(profile, latency=None, errors=0.0, dropped=0, checks=1, missing=0):
    latency = latency or {}
    metrics = {'checks': {'value': checks}, 'dropped_iterations': {'count': dropped}}
    for phase in profile:
        if phase['ramp']:
            continue
        name = phase['name']
        metrics[f'http_req_duration{{phase:{name}}}'] = latency.get(name, trend(20, 40))
        metrics[f'http_reqs{{phase:{name}}}'] = {'count': phase['rate'] * phase['duration'] - missing}
        metrics[f'http_req_failed{{phase:{name}}}'] = {'value': errors}
        metrics[f'http_req_duration{{scenario:{name}_available}}'] = trend(20, 40)
    return {'metrics': metrics}


class ProfileTest(unittest.TestCase):
    def test_full_profile_holds_the_peak_for_five_minutes_by_default(self):
        profile = load_profile(False)
        self.assertEqual([(p['name'], p['start'], p['duration'], p['rate']) for p in profile], [
            ('warmup', 0, 120, 80), ('nominal', 120, 300, 80),
            ('peak', 420, 300, 800), ('recovery', 720, 300, 80)])

    def test_the_peak_duration_can_go_back_to_one_minute(self):
        recovery = load_profile(False, peak_duration=60)[-1]
        self.assertEqual((recovery['start'], recovery['duration']), (480, 300))

    def test_beds_cover_every_planned_reservation_with_a_margin(self):
        # 4,800 + 24,000 + 240,000 + 24,000 = 292,800 reservations, plus 20 %.
        self.assertEqual(beds_per_hospital(load_profile(False), 'success'), 220)
        self.assertEqual(beds_per_hospital(load_profile(False), 'mixed'), 110)
        self.assertEqual(beds_per_hospital(load_profile(True), 'success'), 100)

    def test_the_tail_after_the_last_phase_belongs_to_it(self):
        profile = load_profile(False)
        self.assertEqual(phase_at(profile, 419.9), 'nominal')
        self.assertEqual(phase_at(profile, 420), 'peak')
        self.assertEqual(phase_at(profile, 5000), 'recovery')


class ApiLatencyVerdictTest(unittest.TestCase):
    profile = load_profile(False)

    def test_passes_when_every_measured_phase_is_fast_and_complete(self):
        verdict = api_latency_verdict(summary(self.profile), self.profile, 'success')
        self.assertTrue(verdict['passed'], verdict['failures'])
        self.assertEqual([row['phase'] for row in verdict['phases']], ['nominal', 'peak', 'recovery'])
        self.assertEqual(verdict['phases'][1]['throughput'], 800)

    def test_a_slow_p99_fails_even_when_the_p95_is_met(self):
        verdict = api_latency_verdict(summary(self.profile, {'peak': trend(150, 250)}),
                                      self.profile, 'success')
        self.assertFalse(verdict['passed'])
        self.assertEqual(verdict['failures'], ['peak: p99 250 ms'])

    def test_errors_dropped_iterations_and_missing_requests_fail(self):
        verdict = api_latency_verdict(summary(self.profile, errors=0.001, dropped=3, missing=1),
                                      self.profile, 'success')
        self.assertFalse(verdict['passed'])
        self.assertIn('3 dropped iterations', verdict['failures'])
        self.assertIn('peak: HTTP error rate 0.001', verdict['failures'])
        self.assertIn('peak: 239999 requests sent, 240000 planned', verdict['failures'])


class OutboxVerdictTest(unittest.TestCase):
    profile = load_profile(False, peak_duration=60)

    def test_reports_the_backlog_per_phase_and_the_drain_after_the_peak(self):
        samples = [{'epoch': 1000 + 200, 'pending': 2, 'oldest': 0.1},
                   {'epoch': 1000 + 470, 'pending': 9000, 'oldest': 12.5},
                   {'epoch': 1000 + 485, 'pending': 4000, 'oldest': 8.0},
                   {'epoch': 1000 + 500, 'pending': 0, 'oldest': 0.0}]
        lag = [{'phase': 'peak', 'events': 48000, 'published': 48000, 'lag_p95_ms': 9000.0}]
        verdict = outbox_verdict(samples, lag, self.profile, 1000, 100, 100, 0)
        self.assertTrue(verdict['passed'])
        self.assertEqual(verdict['drained_after_peak_s'], 20)
        peak = next(row for row in verdict['phases'] if row['phase'] == 'peak')
        self.assertEqual((peak['max_pending'], peak['max_oldest_pending_s'], peak['lag_p95_ms']),
                         (9000, 12.5, 9000.0))

    def test_pending_or_unpublished_events_fail(self):
        verdict = outbox_verdict([], [], self.profile, 1000, 100, 90, 10)
        self.assertFalse(verdict['passed'])
        self.assertEqual(len(verdict['failures']), 2)


if __name__ == '__main__':
    unittest.main()
