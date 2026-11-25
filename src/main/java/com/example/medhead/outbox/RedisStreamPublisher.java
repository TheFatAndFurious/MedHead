package com.example.medhead.outbox;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStreamCommands.StreamDeletionPolicy;
import org.springframework.data.redis.connection.RedisStreamCommands.TrimOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

	/**
	 * Appends the events to the stream, in order, with one pipeline: one network round trip and, with
	 * {@code appendfsync always}, normally one AOF fsync for the whole batch instead of one per event.
	 * Returns only once Redis has confirmed every entry; on failure, part of the batch may already be in
	 * the stream and will be sent again.
	 */
	public void publish(List<OutboxEvent> events) {
		if (events.isEmpty()) {
			return;
		}
		if (!groupsReady) {
			ensureConsumerGroups();
		}
		List<Map<String, String>> refused = add(events.stream().map(RedisStreamPublisher::fields).toList());
		if (refused.isEmpty()) {
			return;
		}
		// The stream was deleted since the groups were created: recreate both, then retry the refused entries once.
		groupsReady = false;
		ensureConsumerGroups();
		if (!add(refused).isEmpty()) {
			throw new IllegalStateException("Redis stream " + properties.key() + " disappeared during publication");
		}
	}

	private static Map<String, String> fields(OutboxEvent event) {
		return Map.of(
				"eventId", event.getId().toString(),
				"type", event.getEventType(),
				"aggregateId", event.getAggregateId().toString(),
				"occurredAt", event.getOccurredAt().toString(),
				"payload", event.getPayload()
		);
	}

	/** Sends one pipeline and returns the entries Redis refused because the stream did not exist. */
	private List<Map<String, String>> add(List<Map<String, String>> entries) {
		long startedAt = metrics.start();
		String outcome = "error";
		try {
			List<Object> confirmations = redis.executePipelined(new SessionCallback<>() {
				@Override
				@SuppressWarnings({"unchecked", "rawtypes"})
				public <K, V> Object execute(RedisOperations<K, V> operations) {
					StreamOperations<String, String, String> streams = (StreamOperations) operations.opsForStream();
					for (Map<String, String> entry : entries) {
						streams.add(properties.key(), entry, options);
					}
					return null;
				}
			});
			if (confirmations.size() != entries.size()) {
				throw new IllegalStateException("Redis returned " + confirmations.size() + " results for " + entries.size() + " entries");
			}
			List<Map<String, String>> refused = new ArrayList<>();
			for (int i = 0; i < entries.size(); i++) {
				if (!(confirmations.get(i) instanceof RecordId)) {
					refused.add(entries.get(i));
				}
			}
			outcome = refused.isEmpty() ? "success" : "missing";
			return refused;
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
