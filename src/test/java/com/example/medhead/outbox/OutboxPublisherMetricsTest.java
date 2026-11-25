package com.example.medhead.outbox;

import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPublisherMetricsTest {

	private final MockClock clock = new MockClock();
	private final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
	private final OutboxMetrics metrics = new OutboxMetrics(registry);
	private final List<Integer> limits = new ArrayList<>();
	private boolean failCommit;

	@Test
	void measuresTransactionBeginAndCommitAroundTheBatch() {
		new OutboxPublisher(service(2), 2, metrics).publishPending();

		assertThat(registry.get("outbox.batch.duration").tag("outcome", "full")
				.timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(6);
		assertThat(registry.get("outbox.batch.size").tag("outcome", "full")
				.summary().totalAmount()).isEqualTo(2);
	}

	@Test
	void countsACommitFailureAsAnErrorWithoutPublishedEvents() {
		failCommit = true;

		new OutboxPublisher(service(2), 2, metrics).publishPending();

		assertThat(registry.get("outbox.batch.duration").tag("outcome", "full").timer().count()).isZero();
		assertThat(registry.get("outbox.batch.duration").tag("outcome", "error")
				.timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(6);
		assertThat(registry.get("outbox.batch.size").tag("outcome", "error").summary().count()).isEqualTo(1);
		assertThat(registry.get("outbox.batch.size").tag("outcome", "error").summary().totalAmount()).isZero();
	}

	@Test
	void reportsAPartialBatchAsEmptyAndRecordsTheGapAfterThePreviousBatch() {
		OutboxPublisher publisher = new OutboxPublisher(service(1, 0), 2, metrics);
		assertThat(publisher.publishPending()).isEqualTo(OutboxPublisher.Result.EMPTY);
		clock.add(Duration.ofSeconds(1));
		assertThat(publisher.publishPending()).isEqualTo(OutboxPublisher.Result.EMPTY);

		assertThat(registry.get("outbox.batch.size").tag("outcome", "empty").summary().count()).isEqualTo(2);
		assertThat(registry.get("outbox.batch.size").tag("outcome", "empty").summary().totalAmount()).isEqualTo(1);
		assertThat(registry.get("outbox.batch.gap").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1000);
	}

	@Test
	void asksTheServiceForOneBatchOfTheConfiguredSize() {
		OutboxPublicationService service = service(3);

		assertThat(new OutboxPublisher(service, 3, metrics).publishPending()).isEqualTo(OutboxPublisher.Result.FULL);

		assertThat(limits).containsExactly(3);
	}

	private OutboxPublicationService service(Integer... results) {
		StubService target = new StubService(metrics, new ArrayDeque<>(List.of(results)), clock, limits);
		AbstractPlatformTransactionManager manager = new AbstractPlatformTransactionManager() {
			@Override
			protected Object doGetTransaction() { return new Object(); }
			@Override
			protected void doBegin(Object transaction, TransactionDefinition definition) {
				clock.add(Duration.ofMillis(2));
			}
			@Override
			protected void doCommit(DefaultTransactionStatus status) {
				clock.add(Duration.ofMillis(3));
				if (failCommit) throw new TransactionSystemException("Simulated commit failure");
			}
			@Override
			protected void doRollback(DefaultTransactionStatus status) { }
		};
		ProxyFactory proxy = new ProxyFactory(target);
		TransactionInterceptor interceptor = new TransactionInterceptor();
		interceptor.setTransactionManager(manager);
		interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
		proxy.addAdvice(interceptor);
		return (OutboxPublicationService) proxy.getProxy();
	}

	static class StubService extends OutboxPublicationService {

		private final ArrayDeque<Integer> results;
		private final MockClock clock;
		private final List<Integer> limits;

		StubService(OutboxMetrics metrics, ArrayDeque<Integer> results, MockClock clock, List<Integer> limits) {
			super(null, null, metrics);
			this.results = results;
			this.clock = clock;
			this.limits = limits;
		}

		@Override
		@Transactional(propagation = Propagation.REQUIRES_NEW)
		public int publishBatch(int limit) {
			limits.add(limit);
			clock.add(Duration.ofMillis(1));
			Integer published = results.poll();
			return published == null ? 0 : published;
		}
	}
}
