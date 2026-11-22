package com.example.medhead.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

public class OutboxPublisher {

	public enum Result { FULL, EMPTY, ERROR }

	private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
	private final OutboxPublicationService service;
	private final int batchSize;
	private final OutboxMetrics metrics;
	private Long previousEnd;

	public OutboxPublisher(OutboxPublicationService service, int batchSize, OutboxMetrics metrics) {
		Assert.isTrue(batchSize >= 1 && batchSize <= 1000, "Outbox batch size must be between 1 and 1000");
		this.service = service;
		this.batchSize = batchSize;
		this.metrics = metrics;
	}

	public Result publishPending() {
		if (previousEnd != null) {
			metrics.recordGap(previousEnd);
		}
		long startedAt = metrics.start();
		int published = 0;
		String outcome = "error";
		try {
			for (int i = 0; i < batchSize; i++) {
				long eventStartedAt = metrics.start();
				String eventOutcome = "error";
				boolean found;
				try {
					// The transactional proxy returns only after flush and commit.
					found = service.publishNext();
					eventOutcome = found ? "published" : "empty";
				} finally {
					metrics.record("event", eventOutcome, eventStartedAt);
				}
				if (!found) {
					outcome = "empty";
					return Result.EMPTY;
				}
				published++;
			}
			outcome = "full";
			return Result.FULL;
		} catch (RuntimeException exception) {
			log.warn("Outbox publication failed; pending events will be retried: {}", exception.getMessage());
			return Result.ERROR;
		} finally {
			metrics.record("batch", outcome, startedAt);
			metrics.recordBatchSize(published, outcome);
			previousEnd = metrics.start();
		}
	}
}
