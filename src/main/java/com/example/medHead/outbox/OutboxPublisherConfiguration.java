package com.example.medhead.outbox;

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
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "medhead.outbox.enabled", havingValue = "true")
@EnableScheduling
@EnableConfigurationProperties(OutboxPublisherConfiguration.StreamProperties.class)
public class OutboxPublisherConfiguration {

	@Bean
	RedisStreamPublisher redisStreamPublisher(StringRedisTemplate redis, StreamProperties properties) {
		return new RedisStreamPublisher(redis, properties);
	}

	@Bean
	OutboxPublicationService outboxPublicationService(OutboxEventRepository repository, RedisStreamPublisher publisher) {
		return new OutboxPublicationService(repository, publisher);
	}

	@Bean
	@ConditionalOnProperty(name = "medhead.outbox.scheduling-enabled", havingValue = "true", matchIfMissing = true)
	OutboxPublisher outboxPublisher(
			OutboxPublicationService service,
			@Value("${medhead.outbox.batch-size:100}") int batchSize
	) {
		return new OutboxPublisher(service, batchSize);
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
