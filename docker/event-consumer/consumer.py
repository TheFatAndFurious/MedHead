"""Minimal consumer of the MedHead event stream: logs each event, then acknowledges it.

It stands in for a future business consumer (receiving hospital, dashboard), so that
the ACKED retention of the stream can trim acknowledged entries.
"""
import json
import logging
import os
import signal
import socket
import time

import redis

STREAM = os.environ.get('STREAM_KEY', 'medhead:reservations')
GROUP = os.environ.get('CONSUMER_GROUP', 'reservation-consumers')
CONSUMER = os.environ.get('CONSUMER_NAME') or socket.gethostname()
BATCH = int(os.environ.get('CONSUMER_BATCH_SIZE', '100'))
BLOCK_MS = int(os.environ.get('CONSUMER_BLOCK_MS', '5000'))
# false: log only a summary every SUMMARY_SECONDS (for load tests).
LOG_EVENTS = os.environ.get('CONSUMER_LOG_EVENTS', 'true').lower() == 'true'
SUMMARY_SECONDS = float(os.environ.get('CONSUMER_SUMMARY_SECONDS', '10'))

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
log = logging.getLogger('event-consumer')
running = True


def stop(*_):
    global running
    running = False


def connect():
    return redis.Redis(
        host=os.environ.get('REDIS_HOST', 'redis'),
        port=int(os.environ.get('REDIS_PORT', '6379')),
        password=os.environ['REDIS_PASSWORD'],
        ssl=True,
        ssl_ca_certs=os.environ.get('REDIS_CA_CERT', '/etc/redis-ca/ca.crt'),
        ssl_cert_reqs='required',
        ssl_check_hostname=True,
        decode_responses=True,
        socket_timeout=(BLOCK_MS / 1000) + 5,
        health_check_interval=30,
    )


def ensure_group(client):
    # Same settings as the publisher: start at 0-0 so no entry is skipped.
    try:
        client.xgroup_create(STREAM, GROUP, id='0-0', mkstream=True)
        log.info('created group %s on %s', GROUP, STREAM)
    except redis.ResponseError as error:
        if not str(error).startswith('BUSYGROUP'):
            raise


def handle(client, entries):
    """Logs then acknowledges a batch; returns the number of entries handled."""
    if not entries:
        return 0
    ids = []
    for entry_id, fields in entries:
        ids.append(entry_id)
        if LOG_EVENTS:
            log.info('event %s', json.dumps({
                'streamId': entry_id,
                'eventId': fields.get('eventId'),
                'type': fields.get('type'),
                'aggregateId': fields.get('aggregateId'),
                'occurredAt': fields.get('occurredAt'),
            }))
    # Acknowledge only after handling: a crash in between redelivers the entries (at least once).
    client.xack(STREAM, GROUP, *ids)
    return len(ids)


def read(client, offset, block):
    response = client.xreadgroup(GROUP, CONSUMER, {STREAM: offset}, count=BATCH, block=block)
    return response[0][1] if response else []


def main():
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    log.info('consumer %s reading %s in group %s', CONSUMER, STREAM, GROUP)
    client = None
    handled = 0
    last_summary = time.monotonic()
    while running:
        try:
            if client is None:
                client = connect()
                ensure_group(client)
                # Entries delivered to this consumer but not acknowledged before a restart.
                while running and (pending := read(client, '0', None)):
                    handled += handle(client, pending)
            handled += handle(client, read(client, '>', BLOCK_MS))
        except redis.ResponseError as error:
            if not str(error).startswith('NOGROUP'):
                raise
            # The stream was deleted (reset between load tests): recreate the group.
            log.warning('stream or group missing, recreating it')
            ensure_group(client)
        except (redis.ConnectionError, redis.TimeoutError) as error:
            log.warning('Redis unavailable (%s), retrying in 1 s', error)
            client = None
            time.sleep(1)
        now = time.monotonic()
        if not LOG_EVENTS and now - last_summary >= SUMMARY_SECONDS:
            log.info('%d events acknowledged in the last %.0f s', handled, now - last_summary)
            handled, last_summary = 0, now
    log.info('consumer stopped')


if __name__ == '__main__':
    main()
