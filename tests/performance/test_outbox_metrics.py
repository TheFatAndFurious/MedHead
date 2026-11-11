import unittest
from unittest.mock import MagicMock, patch

from outbox_metrics import collect, parse_measurements


def payload(count, total, statistic='TOTAL_TIME'):
    return {'baseUnit': 'seconds', 'measurements': [
        {'statistic': 'COUNT', 'value': count},
        {'statistic': statistic, 'value': total},
        {'statistic': 'MAX', 'value': 0.2},
    ]}


class MetricsTest(unittest.TestCase):
    @patch('outbox_metrics.METRICS', {'outbox.event.duration': ['published']})
    @patch('outbox_metrics.urlopen')
    def test_metrics_requests_carry_the_access_token(self, urlopen):
        response = MagicMock()
        response.__enter__.return_value = response
        urlopen.return_value = response
        with patch('outbox_metrics.json.load', return_value=payload(1, 0.01)):
            collect('http://localhost', {}, 'test-only-token')
        self.assertEqual(urlopen.call_args.args[0].get_header('Authorization'), 'Bearer test-only-token')

    def test_timer_and_batch_size_use_the_correct_total(self):
        self.assertEqual(parse_measurements(payload(10, 0.5)), (10, 0.5, 0.2))
        self.assertEqual(parse_measurements(payload(2, 100, 'TOTAL')), (2, 100, 0.2))

    @patch('outbox_metrics.METRICS', {'outbox.event.duration': ['published']})
    @patch('outbox_metrics.time.monotonic', side_effect=[100, 105])
    @patch('outbox_metrics.urlopen')
    def test_intervals_exclude_preexisting_samples(self, urlopen, monotonic):
        response = MagicMock()
        response.__enter__.return_value = response
        urlopen.return_value = response
        previous = {}
        with patch('outbox_metrics.json.load', side_effect=[payload(100, 1), payload(120, 1.1)]):
            baseline = collect('http://localhost', previous)[0]
            measured = collect('http://localhost', previous)[0]
        self.assertNotIn('interval_count', baseline)
        self.assertEqual(measured['interval_count'], 20)
        self.assertAlmostEqual(measured['interval_mean'], 0.005)
        self.assertEqual(measured['interval_rate'], 4)
        self.assertIn('tag=outcome%3Apublished', urlopen.call_args.args[0])

    @patch('outbox_metrics.METRICS', {'outbox.event.duration': ['published']})
    @patch('outbox_metrics.urlopen')
    def test_an_api_restart_cannot_silently_reset_the_measurements(self, urlopen):
        response = MagicMock()
        response.__enter__.return_value = response
        urlopen.return_value = response
        previous = {('outbox.event.duration', 'published'): (0, 100, 1)}
        with patch('outbox_metrics.json.load', return_value=payload(1, 0.01)):
            with self.assertRaisesRegex(ValueError, 'Metrics reset'):
                collect('http://localhost', previous)

    def test_missing_measurements_are_not_reported_as_zero(self):
        with self.assertRaises(KeyError):
            parse_measurements({'measurements': []})


if __name__ == '__main__':
    unittest.main()
