package com.example.medhead.outbox;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStreamCommands.StreamDeletionPolicy;
import org.springframework.data.redis.connection.RedisStreamCommands.TrimOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class RedisStreamPublisher {

	private final StringRedisTemplate redis;
	private final OutboxPublisherConfiguration.StreamProperties properties;

	public RedisStreamPublisher(StringRedisTemplate redis, OutboxPublisherConfiguration.StreamProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	public void publish(OutboxEvent event) {
		for (String group : properties.consumerGroups()) {
			ensureConsumerGroup(group);
		}

		Map<String, String> fields = Map.of(
				"eventId", event.getId().toString(),
				"type", event.getEventType(),
				"aggregateId", event.getAggregateId().toString(),
				"occurredAt", event.getOccurredAt().toString(),
				"payload", event.getPayload()
		);
		XAddOptions options = XAddOptions.trim(TrimOptions.maxLen(properties.maxLength())
				.approximate().deletionPolicy(StreamDeletionPolicy.ACKNOWLEDGED));
		var confirmation = redis.opsForStream().add(properties.key(), fields, options);
		if (confirmation == null) {
			throw new IllegalStateException("Redis did not confirm the event publication");
		}
	}

	private void ensureConsumerGroup(String group) {
		try {
			redis.execute((RedisCallback<String>) connection -> connection.streamCommands().xGroupCreate(
					properties.key().getBytes(StandardCharsets.UTF_8), group, ReadOffset.from("0-0"), true
			));
		} catch (DataAccessException exception) {
			String message = exception.getMostSpecificCause().getMessage();
			if (message == null || !message.startsWith("BUSYGROUP")) {
				throw exception;
			}
		}
	}
}
