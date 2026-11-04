package com.example.medhead.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Properties;

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
	OutboxNotificationListener outboxNotificationListener(DataSourceProperties database,
			OutboxPublisher publisher, OutboxPublicationService service,
			@Value("${medhead.outbox.retry-delay:1000}") long retryDelay) {
		// A separate physical connection keeps blocking LISTEN out of the application pool.
		DriverManagerDataSource notifications = new DriverManagerDataSource(
				database.determineUrl(), database.determineUsername(), database.determinePassword());
		Properties properties = new Properties();
		properties.setProperty("ApplicationName", "medhead-outbox-listener");
		properties.setProperty("tcpKeepAlive", "true");
		properties.setProperty("connectTimeout", "2");
		notifications.setConnectionProperties(properties);
		return new OutboxNotificationListener(notifications, publisher, service, retryDelay);
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
