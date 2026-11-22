package com.example.medhead.outbox;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class OutboxMetrics {

	private final MeterRegistry registry;

	public OutboxMetrics(MeterRegistry registry) {
		this.registry = registry;
		// Register even unused outcomes so collectors can distinguish zero from unavailable.
		Map.of(
				"batch", List.of("full", "empty", "error"),
				"event", List.of("published", "empty", "error"),
				"select", List.of("found", "empty", "error"),
				"redis.group", List.of("created", "existing", "error"),
				"redis.add", List.of("success", "error")
		).forEach((operation, outcomes) -> outcomes.forEach(outcome ->
				registry.timer("outbox." + operation + ".duration", "outcome", outcome)));
		registry.timer("outbox.batch.gap");
		for (String outcome : List.of("full", "empty", "error")) {
			registry.summary("outbox.batch.size", "outcome", outcome);
		}
	}

	public long start() {
		return registry.config().clock().monotonicTime();
	}

	public void record(String operation, String outcome, long startedAt) {
		registry.timer("outbox." + operation + ".duration", "outcome", outcome)
				.record(start() - startedAt, TimeUnit.NANOSECONDS);
	}

	public void recordGap(long previousEnd) {
		registry.timer("outbox.batch.gap").record(start() - previousEnd, TimeUnit.NANOSECONDS);
	}

	public void recordBatchSize(int published, String outcome) {
		registry.summary("outbox.batch.size", "outcome", outcome).record(published);
	}
}
