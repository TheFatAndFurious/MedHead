import unittest

import io_usage
from campaign import phase_at

DISKSTATS = """\
 254       0 vda 34421 13944 2103290 9233 2226690 1009728 30734848 851128 0 407838 1026661 98002 0 74246048 13623 1170270 152676
 254       1 vda1 100 0 800 10 200 0 1600 20 0 30 30 0 0 0 0 0 0
   7       0 loop0 1 0 2 0 0 0 0 0 0 0 0 0 0 0 0 0 0
"""
PRESSURE = """\
some avg10=0.00 avg60=0.00 avg300=0.07 total=76008794
full avg10=0.00 avg60=0.00 avg300=0.07 total=70286720
"""
PROFILE = [{'name': 'nominal', 'start': 0, 'duration': 10},
           {'name': 'peak', 'start': 10, 'duration': 10}]


def counter(epoch, source, metric, value):
    return {'epoch': epoch, 'source': source, 'metric': metric, 'value': value}


class ParsingTest(unittest.TestCase):
    def test_pressure_totals_are_cumulative_microseconds(self):
        self.assertEqual(io_usage.parse_pressure(PRESSURE, 'io_'),
                         {'io_some_us': 76008794, 'io_full_us': 70286720})

    def test_io_stat_sums_every_device(self):
        text = ('254:0 rbytes=10 wbytes=100 rios=1 wios=4 dbytes=0 dios=0\n'
                '254:16 rbytes=5 wbytes=50 rios=1 wios=2 dbytes=0 dios=0\n')
        self.assertEqual(io_usage.parse_io_stat(text),
                         {'rbytes': 15, 'wbytes': 150, 'rios': 2, 'wios': 6})

    def test_diskstats_keep_whole_disks_and_flush_counters(self):
        self.assertEqual(io_usage.parse_diskstats(DISKSTATS), {'vda': {
            'writes': 2226690, 'sectors_written': 30734848, 'write_ms': 851128,
            'io_ticks_ms': 407838, 'flushes': 13623, 'flush_ms': 1170270}})

    def test_latency_history_pairs_seconds_and_milliseconds(self):
        self.assertEqual(io_usage.parse_latency_history('1795600000\n3\n1795600001\n12\n'),
                         [(1795600000, 3), (1795600001, 12)])

    def test_postgres_output_sums_waits_over_snapshots(self):
        wal, waits = io_usage.parse_postgres(
            'wal|10|5|4|2.5|8.0\nnotify|0.25\nwait|IO|WALSync|2\nsleep\nwait|IO|WALSync|1\nwait|CPU|CPU|3\nsleep\n')
        self.assertEqual(wal['wal_sync'], 4)
        self.assertEqual(wal['notify_queue_usage'], 0.25)
        self.assertEqual(wal['wal_sync_time_ms'], 8.0)
        self.assertEqual(waits, {('IO', 'WALSync'): 3, ('CPU', 'CPU'): 3})

    def test_sections_split_on_markers(self):
        self.assertEqual(io_usage.split_sections('a\n@vm\nb\nc\n@disks\n'),
                         {'head': 'a', 'vm': 'b\nc', 'disks': ''})


class SummaryTest(unittest.TestCase):
    def test_phase_deltas_start_from_the_last_sample_of_the_previous_phase(self):
        rows = [counter(101, 'postgres', 'wal_sync', 50), counter(101, 'postgres', 'wal_sync_time_ms', 25),
                counter(106, 'postgres', 'wal_sync', 100), counter(106, 'postgres', 'wal_sync_time_ms', 50),
                counter(111, 'postgres', 'wal_sync', 200), counter(111, 'postgres', 'wal_sync_time_ms', 250),
                counter(116, 'postgres', 'wal_sync', 300), counter(116, 'postgres', 'wal_sync_time_ms', 450),
                counter(106, 'database', 'io_some_us', 0), counter(116, 'database', 'io_some_us', 2_500_000)]
        summary = io_usage.summarize(rows, [], [], PROFILE, 100, phase_at)
        self.assertEqual(summary['nominal']['postgres_wal']['syncs_per_s'], 10)
        self.assertEqual(summary['nominal']['postgres_wal']['sync_mean_ms'], 0.5)
        self.assertEqual(summary['peak']['postgres_wal']['syncs_per_s'], 20)
        self.assertEqual(summary['peak']['postgres_wal']['sync_mean_ms'], 2)
        # 2.5 s of stall in 10 s.
        self.assertEqual(summary['peak']['database']['io_pressure_some_percent'], 25)

    def test_notify_queue_usage_is_the_highest_sample_of_the_phase(self):
        rows = [counter(101, 'postgres', 'notify_queue_usage', 0.1),
                counter(106, 'postgres', 'notify_queue_usage', 0.4),
                counter(111, 'postgres', 'notify_queue_usage', 0.2)]
        summary = io_usage.summarize(rows, [], [], PROFILE, 100, phase_at)
        self.assertEqual(summary['nominal']['postgres_notify_queue_usage_max'], 0.4)
        self.assertEqual(summary['peak']['postgres_notify_queue_usage_max'], 0.2)

    def test_busiest_disk_is_reported(self):
        rows = [counter(101, 'disk:vda', 'writes', 0), counter(106, 'disk:vda', 'writes', 100),
                counter(101, 'disk:vda', 'write_ms', 0), counter(106, 'disk:vda', 'write_ms', 300),
                counter(101, 'disk:vdb', 'writes', 0), counter(106, 'disk:vdb', 'writes', 1)]
        disk = io_usage.summarize(rows, [], [], PROFILE, 100, phase_at)['nominal']['disk']
        self.assertEqual((disk['device'], disk['writes_per_s'], disk['write_mean_ms']), ('vda', 20, 3))

    def test_waits_average_sessions_over_every_snapshot(self):
        waits = [{'epoch': 101, 'snapshots': 10, 'wait_event_type': 'IO', 'wait_event': 'WALSync', 'backends': 6},
                 {'epoch': 101, 'snapshots': 10, 'wait_event_type': 'CPU', 'wait_event': 'CPU', 'backends': 2},
                 {'epoch': 106, 'snapshots': 10, 'wait_event_type': '', 'wait_event': '', 'backends': 0}]
        shares = io_usage.summarize([], waits, [], PROFILE, 100, phase_at)['nominal']['postgres_waits']
        self.assertEqual(shares, {'snapshots': 20, 'active_sessions_mean': 0.4,
                                  'by_event': {'IO:WALSync': 0.3, 'CPU:CPU': 0.1}})

    def test_fsync_latency_counts_only_the_fsync_event_of_the_phase(self):
        latencies = [{'event': 'aof-fsync-always', 'epoch': 101, 'latency_ms': 4},
                     {'event': 'aof-fsync-always', 'epoch': 102, 'latency_ms': 20},
                     {'event': 'aof-write', 'epoch': 103, 'latency_ms': 50},
                     {'event': 'aof-fsync-always', 'epoch': 115, 'latency_ms': 9}]
        fsync = io_usage.summarize([], [], latencies, PROFILE, 100, phase_at)['nominal']['redis_fsync']
        self.assertEqual(fsync, {'seconds_over_1ms': 2, 'max_ms': 20, 'p50_ms': 20, 'p95_ms': 20})


if __name__ == '__main__':
    unittest.main()
