package com.example.medhead.outbox;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStreamCommands.StreamDeletionPolicy;
import org.springframework.data.redis.connection.RedisStreamCommands.TrimOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class RedisStreamPublisher {

	private final StringRedisTemplate redis;
	private final OutboxPublisherConfiguration.StreamProperties properties;
	private final OutboxMetrics metrics;
	private final XAddOptions options;
	// Groups live with the stream: they are checked again only once the stream has disappeared.
	private volatile boolean groupsReady;

	public RedisStreamPublisher(StringRedisTemplate redis, OutboxPublisherConfiguration.StreamProperties properties, OutboxMetrics metrics) {
		this.redis = redis;
		this.properties = properties;
		this.metrics = metrics;
		// NOMKSTREAM: never let XADD recreate a deleted stream without its consumer groups,
		// otherwise ACKED retention would protect nothing.
		this.options = XAddOptions.trim(TrimOptions.maxLen(properties.maxLength())
				.approximate().deletionPolicy(StreamDeletionPolicy.ACKNOWLEDGED)).nomkstream(true);
	}

	public void publish(OutboxEvent event) {
		if (!groupsReady) {
			ensureConsumerGroups();
		}
		Map<String, String> fields = Map.of(
				"eventId", event.getId().toString(),
				"type", event.getEventType(),
				"aggregateId", event.getAggregateId().toString(),
				"occurredAt", event.getOccurredAt().toString(),
				"payload", event.getPayload()
		);
		if (add(fields)) {
			return;
		}
		// The stream was deleted since the groups were created: recreate both, then retry once.
		groupsReady = false;
		ensureConsumerGroups();
		if (!add(fields)) {
			throw new IllegalStateException("Redis stream " + properties.key() + " disappeared during publication");
		}
	}

	private boolean add(Map<String, String> fields) {
		long startedAt = metrics.start();
		String outcome = "error";
		try {
			RecordId confirmation = redis.opsForStream().add(properties.key(), fields, options);
			outcome = confirmation == null ? "missing" : "success";
			return confirmation != null;
		} finally {
			metrics.record("redis.add", outcome, startedAt);
		}
	}

	private void ensureConsumerGroups() {
		for (String group : properties.consumerGroups()) {
			ensureConsumerGroup(group);
		}
		groupsReady = true;
	}

	private void ensureConsumerGroup(String group) {
		long startedAt = metrics.start();
		String outcome = "error";
		try {
			// MKSTREAM creates the stream with the group, so a group always exists before the first XADD.
			redis.execute((RedisCallback<String>) connection -> connection.streamCommands().xGroupCreate(
					properties.key().getBytes(StandardCharsets.UTF_8), group, ReadOffset.from("0-0"), true
			));
			outcome = "created";
		} catch (DataAccessException exception) {
			String message = exception.getMostSpecificCause().getMessage();
			if (message == null || !message.startsWith("BUSYGROUP")) {
				throw exception;
			}
			outcome = "existing";
		} finally {
			metrics.record("redis.group", outcome, startedAt);
		}
	}
}
