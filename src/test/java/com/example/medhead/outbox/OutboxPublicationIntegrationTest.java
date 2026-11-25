package com.example.medhead.outbox;

import com.example.medhead.hospital.application.ReserveBedCommand;
import com.example.medhead.hospital.application.ReservedBed;
import com.example.medhead.hospital.application.ReserveNearestAvailableBedService;
import io.lettuce.core.SslVerifyMode;
import io.micrometer.core.instrument.MeterRegistry;
import org.postgresql.PGConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "medhead.redis-integration-tests", matches = "true")
@ActiveProfiles("redis")
@SpringBootTest(properties = {
		"spring.datasource.url=${medhead.test.jdbc-url:jdbc:postgresql://localhost:55432/hospital_test?sslmode=verify-full&sslrootcert=.local/postgres-tests/ca.crt}",
		"spring.datasource.username=hospital_test",
		"spring.datasource.password=hospital_test",
		"spring.data.redis.host=localhost",
		"spring.data.redis.port=${medhead.test.redis-port:56379}",
		"spring.data.redis.password=medhead_redis_test",
		"spring.ssl.bundle.pem.redis.truststore.certificate=${medhead.test.redis-ca:file:./.local/redis-tests/ca.crt}",
		"medhead.events.stream.key=medhead:test:outbox",
		"medhead.events.stream.max-length=2",
		"medhead.events.stream.consumer-groups=outbox-consumers,outbox-audit",
		"medhead.outbox.dispatcher-enabled=false"
})
@AutoConfigureMockMvc
class OutboxPublicationIntegrationTest {

	private static final String STREAM = "medhead:test:outbox";
	private static final String SPECIALTY = "OUTBOX_TEST";
	@Autowired
	private OutboxPublicationService publicationService;
	@Autowired
	private RedisStreamPublisher publisher;
	@Autowired
	private StringRedisTemplate redis;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private ReserveNearestAvailableBedService reservationService;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private OutboxMetrics metrics;
	@Autowired
	private MeterRegistry registry;
	@Autowired
	private DataSourceProperties database;

	@BeforeEach
	void setUp() {
		cleanUp();
	}

	@AfterEach
	void cleanUp() {
		jdbc.execute("alter table event_outbox drop constraint if exists test_reject_publication");
		jdbc.update("delete from event_outbox where event_type = 'OutboxTest' or payload ->> 'specialtyCode' = ?", SPECIALTY);
		jdbc.update("delete from hospital where code = 'TEST-OUTBOX-HOSPITAL'");
		jdbc.update("delete from specialty where code = ?", SPECIALTY);
		redis.delete(STREAM);
	}

	@Test
	void publishesACommittedReservationOverVerifiedTlsAndMarksItPublished() {
		LettuceConnectionFactory factory = (LettuceConnectionFactory) redis.getConnectionFactory();
		assertThat(factory.getClientConfiguration().isUseSsl()).isTrue();
		assertThat(factory.getClientConfiguration().getVerifyMode()).isEqualTo(SslVerifyMode.FULL);
		ReservedBed reservation = reserveBed();
		UUID eventId = jdbc.queryForObject(
				"select id from event_outbox where aggregate_id = ?", UUID.class, reservation.reservationId()
		);

		assertThat(publicationService.publishBatch(10)).isEqualTo(1);

		assertThat(pendingCount()).isZero();
		assertThat(records()).singleElement().satisfies(record -> {
			assertThat(record.getValue()).containsEntry("eventId", eventId.toString());
			assertThat(record.getValue()).containsEntry("type", "BedReserved");
			assertThat(record.getValue()).containsEntry("aggregateId", reservation.reservationId().toString());
			assertThat(record.getValue().get("payload")).isEqualTo(jdbc.queryForObject(
					"select payload::text from event_outbox where id = ?", String.class, eventId
			));
		});
		assertThat(redis.opsForStream().groups(STREAM)).extracting(group -> group.groupName())
				.containsExactlyInAnyOrder("outbox-consumers", "outbox-audit");
		assertThat(publicationService.publishBatch(10)).isZero();
		assertThat(records()).hasSize(1);
	}

	@Test
	void recordsOneSelectPipelineAndUpdatePerBatch() {
		double createdGroups = count("outbox.redis.group.duration", "created");
		double existingGroups = count("outbox.redis.group.duration", "existing");
		double sent = count("outbox.redis.add.duration", "success");
		double found = count("outbox.select.duration", "found");
		double emptySelects = count("outbox.select.duration", "empty");
		double updated = count("outbox.update.duration", "success");
		double partial = registry.get("outbox.batch.size").tag("outcome", "empty").summary().totalAmount();
		insertEvent();
		insertEvent();

		assertThat(new OutboxPublisher(publicationService, 3, metrics).publishPending()).isEqualTo(OutboxPublisher.Result.EMPTY);

		// The stream was deleted before the test: both groups are recreated once, never checked per event.
		assertThat(count("outbox.redis.group.duration", "created") - createdGroups).isEqualTo(2);
		assertThat(count("outbox.redis.group.duration", "existing") - existingGroups).isZero();
		assertThat(count("outbox.redis.add.duration", "success") - sent).isEqualTo(1);
		assertThat(count("outbox.select.duration", "found") - found).isEqualTo(1);
		assertThat(count("outbox.select.duration", "empty") - emptySelects).isZero();
		assertThat(count("outbox.update.duration", "success") - updated).isEqualTo(1);
		assertThat(registry.get("outbox.batch.size").tag("outcome", "empty").summary().totalAmount() - partial).isEqualTo(2);
		assertThat(pendingCount()).isZero();
		assertThat(records()).hasSize(2);
	}

	@Test
	void keepsTheOutboxOrderWithinABatch() {
		Instant first = Instant.parse("2026-01-01T00:00:00Z");
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			expected.add(insertEvent(first.plusSeconds(i)).getId().toString());
		}

		assertThat(publicationService.publishBatch(10)).isEqualTo(5);

		assertThat(records()).extracting(record -> record.getValue().get("eventId")).containsExactlyElementsOf(expected);
	}

	@Test
	void createsConsumerGroupsOnceAndRecreatesThemWhenTheStreamDisappears() {
		publisher.publish(List.of(testEvent()));
		double groupCalls = groupCalls();
		double missing = count("outbox.redis.add.duration", "missing");

		for (int i = 0; i < 5; i++) {
			publisher.publish(List.of(testEvent(), testEvent()));
		}
		assertThat(groupCalls() - groupCalls).isZero();

		redis.delete(STREAM);
		List<OutboxEvent> batch = List.of(testEvent(), testEvent(), testEvent());
		publisher.publish(batch);

		// The whole pipeline is refused, then sent again once the stream and its groups exist.
		assertThat(count("outbox.redis.add.duration", "missing") - missing).isEqualTo(1);
		assertThat(count("outbox.redis.group.duration", "created")).isPositive();
		assertThat(redis.opsForStream().groups(STREAM)).extracting(group -> group.groupName())
				.containsExactlyInAnyOrder("outbox-consumers", "outbox-audit");
		assertThat(records()).extracting(record -> record.getValue().get("eventId"))
				.containsExactlyElementsOf(batch.stream().map(event -> event.getId().toString()).toList());
		publisher.publish(List.of(testEvent()));
		assertThat(groupCalls() - groupCalls).isEqualTo(2);
	}

	private double count(String timer, String outcome) {
		return registry.get(timer).tag("outcome", outcome).timer().count();
	}

	private double groupCalls() {
		return registry.get("outbox.redis.group.duration").timers().stream().mapToLong(timer -> timer.count()).sum();
	}

	private static OutboxEvent testEvent() {
		return new OutboxEvent(UUID.randomUUID(), "OutboxTest", UUID.randomUUID(), Instant.now(), "{}");
	}

	@Test
	void notifiesOnlyAfterCommitAndNeverAfterRollback() throws Exception {
		try (Connection connection = notificationsDataSource().getConnection()) {
			connection.createStatement().execute("LISTEN medhead_outbox");
			PGConnection postgres = connection.unwrap(PGConnection.class);
			TransactionTemplate transaction = new TransactionTemplate(transactionManager);
			transaction.executeWithoutResult(status -> {
				insertEvent();
				try {
					assertThat(postgres.getNotifications(100)).isEmpty();
				} catch (java.sql.SQLException exception) {
					throw new IllegalStateException(exception);
				}
			});
			assertThat(postgres.getNotifications(2000)).hasSize(1);
			transaction.executeWithoutResult(status -> {
				insertEvent();
				status.setRollbackOnly();
			});
			assertThat(postgres.getNotifications(100)).isEmpty();
		}
	}

	@Test
	void sleepsWithoutRepeatedOutboxReadsAndWakesOnInsertion() {
		long empty = registry.get("outbox.select.duration").tag("outcome", "empty").timer().count();
		try (OutboxNotificationListener listener = listener()) {
			listener.start();
			await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
					assertThat(registry.get("outbox.select.duration").tag("outcome", "empty").timer().count()).isEqualTo(empty + 1));
			await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
					assertThat(registry.get("outbox.select.duration").tag("outcome", "empty").timer().count()).isEqualTo(empty + 1));
			insertEvent();
			await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(pendingCount()).isZero());
			assertThat(records()).hasSize(1);
		}
		await().atMost(Duration.ofSeconds(2)).until(() -> listenerPid() == null);
	}

	@Test
	void drainsMultipleBatchesOnStartupWithoutNeedingANotification() {
		for (int i = 0; i < 5; i++) insertEvent();
		try (OutboxNotificationListener listener = listener()) {
			listener.start();
			await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(pendingCount()).isZero());
			assertThat(records()).hasSize(5);
		}
	}

	@Test
	void reconnectsAndCatchesUpOnEventsInsertedDuringTheDisconnection() {
		try (OutboxNotificationListener listener = listener()) {
			listener.start();
			await().atMost(Duration.ofSeconds(3)).until(() -> listenerPid() != null);
			int oldPid = listenerPid();
			assertThat(jdbc.queryForObject("select pg_terminate_backend(?)", Boolean.class, oldPid)).isTrue();
			insertEvent();
			await().atMost(Duration.ofSeconds(3)).until(() -> listenerPid() != null && listenerPid() != oldPid);
			await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(pendingCount()).isZero());
			assertThat(records()).hasSize(1);
		}
	}

	@Test
	void retriesACommitFailureWithoutWaitingForAnotherInsertion() {
		OutboxEvent event = insertEvent();
		jdbc.execute("""
				alter table event_outbox add constraint test_reject_publication
				check (event_type <> 'OutboxTest' or published_at is null)
				""");
		double errors = count("outbox.batch.duration", "error");
		try (OutboxNotificationListener listener = listener()) {
			listener.start();
			await().atMost(Duration.ofSeconds(3)).until(() ->
					count("outbox.batch.duration", "error") > errors);
			jdbc.execute("alter table event_outbox drop constraint test_reject_publication");
			await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(pendingCount()).isZero());
			assertThat(records()).allSatisfy(record ->
					assertThat(record.getValue()).containsEntry("eventId", event.getId().toString()));
		}
	}

	@Test
	void retriesRowsLockedByAnotherPublisherWithoutWaitingForAnotherInsertion() throws Exception {
		OutboxEvent event = insertEvent();
		try (Connection locking = notificationsDataSource().getConnection();
				OutboxNotificationListener listener = listener()) {
			locking.setAutoCommit(false);
			try (var statement = locking.prepareStatement("select id from event_outbox where id = ? for update")) {
				statement.setObject(1, event.getId());
				statement.executeQuery().close();
			}
			double empty = count("outbox.select.duration", "empty");
			listener.start();
			await().atMost(Duration.ofSeconds(3)).until(() ->
					count("outbox.select.duration", "empty") > empty);
			assertThat(pendingCount()).isEqualTo(1);
			locking.rollback();
			await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(pendingCount()).isZero());
		}
	}

	@Test
	void doesNotPublishAnUncommittedOrRolledBackReservation() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.executeWithoutResult(status -> {
			reserveBed();
			assertThat(publicationService.publishBatch(10)).isZero();
			status.setRollbackOnly();
		});
		assertThat(pendingCount()).isZero();
		assertThat(redis.hasKey(STREAM)).isFalse();
	}

	@Test
	void retriesAfterAnAuthenticationFailureAndKeepsTheReservationAvailable() throws Exception {
		LettuceConnectionFactory original = (LettuceConnectionFactory) redis.getConnectionFactory();
		RedisStandaloneConfiguration badCredentials = connectionSettings(original);
		badCredentials.setPassword("incorrect-test-password");
		LettuceConnectionFactory unavailable = new LettuceConnectionFactory(badCredentials, original.getClientConfiguration());
		unavailable.afterPropertiesSet();
		try {
			redis.setConnectionFactory(unavailable);
			ReservedBed reservation = reserveBed();
			assertThat(reservation.availableBeds()).isZero();
			new OutboxPublisher(publicationService, 10, metrics).publishPending();
			assertThat(pendingCount()).isEqualTo(1);
			mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
		} finally {
			redis.setConnectionFactory(original);
			unavailable.destroy();
		}
		assertThat(publicationService.publishBatch(10)).isEqualTo(1);
		assertThat(pendingCount()).isZero();
		assertThat(records()).hasSize(1);
	}

	@Test
	void rejectsAnUntrustedServerCertificateAndLeavesTheWholeBatchPending() {
		OutboxEvent event = insertEvent();
		insertEvent();
		LettuceConnectionFactory original = (LettuceConnectionFactory) redis.getConnectionFactory();
		LettuceClientConfiguration noLocalCa = LettuceClientConfiguration.builder()
				.commandTimeout(Duration.ofSeconds(2)).useSsl().and().build();
		LettuceConnectionFactory untrusted = new LettuceConnectionFactory(connectionSettings(original), noLocalCa);
		untrusted.afterPropertiesSet();
		try {
			redis.setConnectionFactory(untrusted);
			assertThatThrownBy(() -> publicationService.publishBatch(10)).isInstanceOf(DataAccessException.class);
			assertThat(pendingCount()).isEqualTo(2);
			assertThat(jdbc.queryForObject("select published_at from event_outbox where id = ?", Instant.class, event.getId())).isNull();
		} finally {
			redis.setConnectionFactory(original);
			untrusted.destroy();
		}
		assertThat(publicationService.publishBatch(10)).isEqualTo(2);
	}

	@Test
	void sendsTheWholeBatchAgainWithTheSameEventIdsWhenTheDatabaseCommitFails() {
		OutboxEvent first = insertEvent();
		OutboxEvent second = insertEvent();
		jdbc.execute("""
				alter table event_outbox add constraint test_reject_publication
				check (event_type <> 'OutboxTest' or published_at is null)
				""");

		assertThatThrownBy(() -> publicationService.publishBatch(10)).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(pendingCount()).isEqualTo(2);
		assertThat(records()).hasSize(2);
		jdbc.execute("alter table event_outbox drop constraint test_reject_publication");

		assertThat(publicationService.publishBatch(10)).isEqualTo(2);
		assertThat(pendingCount()).isZero();
		assertThat(records()).extracting(record -> record.getValue().get("eventId")).containsExactly(
				first.getId().toString(), second.getId().toString(), first.getId().toString(), second.getId().toString());
	}

	@Test
	void publishesAtMostOneBatchPerPass() {
		insertEvent();
		insertEvent();
		insertEvent();

		new OutboxPublisher(publicationService, 2, metrics).publishPending();

		assertThat(pendingCount()).isEqualTo(1);
		assertThat(records()).hasSize(2);
		new OutboxPublisher(publicationService, 2, metrics).publishPending();
		assertThat(pendingCount()).isZero();
		assertThat(records()).hasSize(3);
	}

	@Test
	void concurrentPublishersDoNotSendTheSameOutboxRowTwice() throws Exception {
		for (int i = 0; i < 12; i++) {
			insertEvent();
		}
		var executor = Executors.newFixedThreadPool(4);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				futures.add(executor.submit(() -> {
					assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
					while (publicationService.publishBatch(2) > 0) {
						// Drain unlocked rows in small batches, one transaction per batch.
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> future : futures) {
				future.get(20, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
		assertThat(pendingCount()).isZero();
		assertThat(records()).hasSize(12).extracting(record -> record.getValue().get("eventId")).doesNotHaveDuplicates();
	}

	@Test
	void retentionKeepsUnacknowledgedEventsUntilEveryGroupHasAcknowledgedThem() {
		for (int i = 0; i < 220; i++) {
			publisher.publish(List.of(testEvent()));
		}
		assertThat(records()).hasSize(220);
		acknowledgeAll("outbox-consumers");
		publisher.publish(List.of(testEvent()));
		assertThat(records()).hasSize(221);
		acknowledgeAll("outbox-consumers");
		acknowledgeAll("outbox-audit");
		publisher.publish(List.of(testEvent()));
		assertThat(records().size()).isBetween(1, 100);
	}

	private void acknowledgeAll(String group) {
		var messages = redis.opsForStream().read(Consumer.from(group, "test-consumer"),
				StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
		assertThat(messages).isNotEmpty();
		for (var message : messages) {
			redis.opsForStream().acknowledge(STREAM, group, message.getId());
		}
	}

	private OutboxEvent insertEvent() {
		return insertEvent(Instant.now());
	}

	private OutboxEvent insertEvent(Instant occurredAt) {
		OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "OutboxTest", UUID.randomUUID(), occurredAt, "{}");
		jdbc.update("insert into event_outbox (id, event_type, aggregate_id, occurred_at, payload) values (?, ?, ?, ?, ?::jsonb)",
				event.getId(), event.getEventType(), event.getAggregateId(), java.sql.Timestamp.from(event.getOccurredAt()), event.getPayload());
		return event;
	}

	private OutboxNotificationListener listener() {
		return new OutboxNotificationListener(notificationsDataSource(),
				new OutboxPublisher(publicationService, 2, metrics), publicationService, 50);
	}

	private DriverManagerDataSource notificationsDataSource() {
		DriverManagerDataSource source = new DriverManagerDataSource(
				database.determineUrl(), database.determineUsername(), database.determinePassword());
		Properties properties = new Properties();
		properties.setProperty("ApplicationName", "medhead-outbox-listener-test");
		source.setConnectionProperties(properties);
		return source;
	}

	private Integer listenerPid() {
		return jdbc.queryForObject("select max(pid) from pg_stat_activity where application_name = 'medhead-outbox-listener-test'",
				Integer.class);
	}

	private ReservedBed reserveBed() {
		jdbc.update("insert into specialty (code, name) values (?, ?)", SPECIALTY, "Outbox test");
		long hospitalId = jdbc.queryForObject("""
				insert into hospital (code, name, location, total_beds, available_beds)
				values ('TEST-OUTBOX-HOSPITAL', 'Outbox test', ST_SetSRID(ST_MakePoint(-0.1278, 51.5074), 4326)::geography, 1, 1)
				returning id
				""", Long.class);
		jdbc.update("insert into hospital_specialty (hospital_id, specialty_id) select ?, id from specialty where code = ?", hospitalId, SPECIALTY);
		return reservationService.reserveNearest(
				new ReserveBedCommand("outbox-test", UUID.randomUUID(), 51.5074, -0.1278, SPECIALTY)
		).reservation();
	}

	private int pendingCount() {
		return jdbc.queryForObject("select count(*) from event_outbox where published_at is null and (event_type = 'OutboxTest' or payload ->> 'specialtyCode' = ?)", Integer.class, SPECIALTY);
	}

	private List<MapRecord<String, Object, Object>> records() {
		return redis.opsForStream().range(STREAM, Range.unbounded());
	}

	private RedisStandaloneConfiguration connectionSettings(LettuceConnectionFactory factory) {
		RedisStandaloneConfiguration settings = new RedisStandaloneConfiguration(factory.getHostName(), factory.getPort());
		settings.setPassword(factory.getStandaloneConfiguration().getPassword());
		return settings;
	}
}
