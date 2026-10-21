package com.example.medhead.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.Assert;

public class OutboxPublisher {

	private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
	private final OutboxPublicationService service;
	private final int batchSize;

	public OutboxPublisher(OutboxPublicationService service, int batchSize) {
		Assert.isTrue(batchSize >= 1 && batchSize <= 1000, "Outbox batch size must be between 1 and 1000");
		this.service = service;
		this.batchSize = batchSize;
	}

	@Scheduled(fixedDelayString = "${medhead.outbox.poll-delay:1000}", initialDelayString = "${medhead.outbox.poll-delay:1000}")
	public void publishPending() {
		try {
			for (int i = 0; i < batchSize; i++) {
				if (!service.publishNext()) {
					return;
				}
			}
		} catch (RuntimeException exception) {
			log.warn("Outbox publication failed; pending events will be retried: {}", exception.getMessage());
		}
	}
}
