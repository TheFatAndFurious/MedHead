package com.example.medhead.outbox;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public class OutboxPublicationService {

	private final OutboxEventRepository repository;
	private final RedisStreamPublisher publisher;

	public OutboxPublicationService(OutboxEventRepository repository, RedisStreamPublisher publisher) {
		this.repository = repository;
		this.publisher = publisher;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean publishNext() {
		OutboxEvent event = repository.findNextPending().orElse(null);
		if (event == null) {
			return false;
		}

		publisher.publish(event);
		event.markPublished(Instant.now());
		return true;
	}
}
