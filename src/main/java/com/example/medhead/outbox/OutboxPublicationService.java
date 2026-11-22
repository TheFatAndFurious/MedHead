package com.example.medhead.outbox;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public class OutboxPublicationService {

	private final OutboxEventRepository repository;
	private final RedisStreamPublisher publisher;
	private final OutboxMetrics metrics;

	public OutboxPublicationService(OutboxEventRepository repository, RedisStreamPublisher publisher, OutboxMetrics metrics) {
		this.repository = repository;
		this.publisher = publisher;
		this.metrics = metrics;
	}

	public boolean hasPending() {
		return repository.hasPending();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean publishNext() {
		OutboxEvent event;
		long startedAt = metrics.start();
		String outcome = "error";
		try {
			event = repository.findNextPending().orElse(null);
			outcome = event == null ? "empty" : "found";
		} finally {
			metrics.record("select", outcome, startedAt);
		}
		if (event == null) {
			return false;
		}

		publisher.publish(event);
		event.markPublished(Instant.now());
		return true;
	}
}
