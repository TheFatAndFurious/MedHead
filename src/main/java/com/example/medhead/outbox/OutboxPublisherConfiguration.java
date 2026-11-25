package com.example.medhead.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "medhead.outbox.enabled", havingValue = "true")
@EnableConfigurationProperties(OutboxPublisherConfiguration.StreamProperties.class)
public class OutboxPublisherConfiguration {

	@Bean
	OutboxMetrics outboxMetrics(MeterRegistry registry) {
		return new OutboxMetrics(registry);
	}

	@Bean
	RedisStreamPublisher redisStreamPublisher(StringRedisTemplate redis, StreamProperties properties, OutboxMetrics metrics) {
		return new RedisStreamPublisher(redis, properties, metrics);
	}

	@Bean
	OutboxPublicationService outboxPublicationService(OutboxEventRepository repository, RedisStreamPublisher publisher, OutboxMetrics metrics) {
		return new OutboxPublicationService(repository, publisher, metrics);
	}

	@Bean
	OutboxPublisher outboxPublisher(
			OutboxPublicationService service,
			@Value("${medhead.outbox.batch-size:100}") int batchSize,
			OutboxMetrics metrics
	) {
		return new OutboxPublisher(service, batchSize, metrics);
	}

	@Bean
	@ConditionalOnProperty(name = "medhead.outbox.dispatcher-enabled", havingValue = "true", matchIfMissing = true)
	OutboxDispatcher outboxDispatcher(OutboxPublisher publisher, OutboxWakeUp wakeUp,
			@Value("${medhead.outbox.poll-interval:1000}") long pollInterval,
			@Value("${medhead.outbox.retry-delay:1000}") long retryDelay) {
		return new OutboxDispatcher(publisher, wakeUp, pollInterval, retryDelay);
	}

	@Validated
	@ConfigurationProperties("medhead.events.stream")
	public record StreamProperties(
			@NotBlank String key,
			@Min(1) long maxLength,
			@NotEmpty List<@NotBlank String> consumerGroups
	) {
	}
}
