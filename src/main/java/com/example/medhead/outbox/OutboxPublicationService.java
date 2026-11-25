package com.example.medhead.outbox;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public class OutboxPublicationService {

	private final OutboxEventRepository repository;
	private final RedisStreamPublisher publisher;
	private final OutboxMetrics metrics;

	public OutboxPublicationService(OutboxEventRepository repository, RedisStreamPublisher publisher, OutboxMetrics metrics) {
		this.repository = repository;
		this.publisher = publisher;
		this.metrics = metrics;
	}

	/**
	 * Publishes up to {@code limit} pending events in one transaction: one locking SELECT, one Redis
	 * pipeline, one UPDATE and one commit. If anything fails, no event of the batch is marked published
	 * and the whole batch is sent again later with the same event ids.
	 *
	 * @return the number of events published and committed
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int publishBatch(int limit) {
		List<OutboxEvent> events;
		long startedAt = metrics.start();
		String outcome = "error";
		try {
			events = repository.lockNextPending(limit);
			outcome = events.isEmpty() ? "empty" : "found";
		} finally {
			metrics.record("select", outcome, startedAt);
		}
		if (events.isEmpty()) {
			return 0;
		}

		publisher.publish(events);

		startedAt = metrics.start();
		outcome = "error";
		try {
			int updated = repository.markPublished(events.stream().map(OutboxEvent::getId).toList(), Instant.now());
			if (updated != events.size()) {
				throw new IllegalStateException("Marked " + updated + " of " + events.size() + " outbox events as published");
			}
			outcome = "success";
		} finally {
			metrics.record("update", outcome, startedAt);
		}
		return events.size();
	}
}
