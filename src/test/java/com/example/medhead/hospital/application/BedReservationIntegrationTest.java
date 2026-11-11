package com.example.medhead.hospital.application;

import com.example.medhead.hospital.api.HospitalReservationResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "medhead.integration-tests", matches = "true")
@SpringBootTest(properties = {
		"spring.datasource.url=${medhead.test.jdbc-url:jdbc:postgresql://localhost:55432/hospital_test}",
		"spring.datasource.username=hospital_test",
		"spring.datasource.password=hospital_test"
})
@AutoConfigureMockMvc
@WithMockUser(username = BedReservationIntegrationTest.REGULATOR, roles = "REGULATEUR")
class BedReservationIntegrationTest {

	static final String REGULATOR = "regulator-test";
	private static final String SPECIALTY = "RESERVATION_TEST";
	private static final String NEAREST_CODE = "TEST-RESERVE-NEAREST";
	private static final String FARTHER_CODE = "TEST-RESERVE-FARTHER";

	@Autowired
	private ReserveNearestAvailableBedService reservationService;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JsonMapper jsonMapper;

	@BeforeEach
	void setUp() {
		cleanUp();
		jdbc.update("insert into specialty (code, name) values (?, ?)", SPECIALTY, "Test specialty");
		jdbc.update("insert into specialty (code, name) values (?, ?)", "RESERVATION_OTHER_TEST", "Other specialty");
		addHospital(NEAREST_CODE, -0.1276, 3, SPECIALTY);
		addHospital(FARTHER_CODE, -0.14, 3, SPECIALTY);
		addHospital("TEST-RESERVE-WRONG-SPECIALTY", -0.1278, 3, "RESERVATION_OTHER_TEST");
	}

	@AfterEach
	void cleanUp() {
		jdbc.execute("alter table event_outbox drop constraint if exists test_reject_outbox");
		jdbc.update("delete from event_outbox where payload ->> 'specialtyCode' = ?", SPECIALTY);
		// Reservations and their request rows are removed by cascade.
		jdbc.update("delete from hospital where code like 'TEST-RESERVE-%'");
		jdbc.update("delete from specialty where code in (?, ?)", SPECIALTY, "RESERVATION_OTHER_TEST");
	}

	@Test
	void reservesTheNearestEligibleHospitalAndPersistsItsOutboxEvent() throws Exception {
		UUID key = UUID.randomUUID();
		String body = mockMvc.perform(reservationPost(requestJson(SPECIALTY), key))
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotent-Replayed"))
				.andExpect(jsonPath("$.hospitalCode").value(NEAREST_CODE))
				.andExpect(jsonPath("$.totalBeds").value(3))
				.andExpect(jsonPath("$.availableBeds").value(2))
				.andReturn().getResponse().getContentAsString();
		HospitalReservationResponse response = jsonMapper.readValue(body, HospitalReservationResponse.class);

		assertThat(response.distanceMeters()).isPositive();
		assertThat(availableBeds(NEAREST_CODE)).isEqualTo(2);
		assertThat(availableBeds(FARTHER_CODE)).isEqualTo(3);
		assertThat(reservationCount()).isEqualTo(1);
		assertThat(outboxCount()).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"select hospital_id from bed_reservation where id = ?", Long.class, response.reservationId()
		)).isEqualTo(response.hospitalId());
		var request = jdbc.queryForMap(
				"select requested_by, reservation_id from reservation_request where idempotency_key = ?", key
		);
		assertThat(request.get("requested_by")).isEqualTo(REGULATOR);
		assertThat(request.get("reservation_id")).isEqualTo(response.reservationId());
		var outbox = jdbc.queryForMap(
				"select id, event_type, aggregate_id, payload::text, published_at from event_outbox where aggregate_id = ?",
				response.reservationId()
		);
		BedReservedEvent event = jsonMapper.readValue((String) outbox.get("payload"), BedReservedEvent.class);
		assertThat(outbox.get("event_type")).isEqualTo(BedReservedEvent.TYPE);
		assertThat(outbox.get("aggregate_id")).isEqualTo(response.reservationId());
		assertThat(outbox.get("published_at")).isNull();
		assertThat(event.eventId()).isEqualTo(outbox.get("id"));
		assertThat(event.reservationId()).isEqualTo(response.reservationId());
		assertThat(event.hospitalId()).isEqualTo(response.hospitalId());
		assertThat(event.hospitalCode()).isEqualTo(NEAREST_CODE);
		assertThat(event.specialtyCode()).isEqualTo(SPECIALTY);
		assertThat(event.occurredAt()).isEqualTo(response.reservedAt());
	}

	@Test
	void repeatsTheOriginalReservationForTheSameKeyWithoutConsumingAnotherBed() throws Exception {
		UUID key = UUID.randomUUID();
		String first = mockMvc.perform(reservationPost(requestJson(SPECIALTY), key))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		String repeated = mockMvc.perform(reservationPost(requestJson(SPECIALTY), key))
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andReturn().getResponse().getContentAsString();

		assertThat(jsonMapper.readTree(repeated)).isEqualTo(jsonMapper.readTree(first));
		assertThat(availableBeds(NEAREST_CODE)).isEqualTo(2);
		assertThat(reservationCount()).isEqualTo(1);
		assertThat(outboxCount()).isEqualTo(1);
		assertThat(requestCount()).isEqualTo(1);
	}

	@Test
	void refusesAKeyReusedForADifferentRequestWithoutWritingAnything() throws Exception {
		UUID key = UUID.randomUUID();
		mockMvc.perform(reservationPost(requestJson(SPECIALTY), key))
				.andExpect(status().isCreated());

		mockMvc.perform(reservationPost(requestJson(SPECIALTY).replace("51.5074", "51.5"), key))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.type").value("urn:problem:idempotency-key-reused"));

		assertThat(availableBeds(NEAREST_CODE)).isEqualTo(2);
		assertThat(availableBeds(FARTHER_CODE)).isEqualTo(3);
		assertThat(reservationCount()).isEqualTo(1);
		assertThat(outboxCount()).isEqualTo(1);
		assertThat(requestCount()).isEqualTo(1);
	}

	@Test
	void rejectsARequestWithoutIdempotencyKeyWithoutWritingAnything() throws Exception {
		mockMvc.perform(post("/api/v1/hospitals/reservations")
					.contentType(MediaType.APPLICATION_JSON).content(requestJson(SPECIALTY)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors['Idempotency-Key']").exists());
		assertThat(availableBeds(NEAREST_CODE)).isEqualTo(3);
		assertNoReservationOrEvent();
	}

	@Test
	void scopesKeysToTheAuthenticatedSubject() {
		UUID key = UUID.randomUUID();

		ReservationOutcome alice = reservationService.reserveNearest(command("alice", key));
		ReservationOutcome bob = reservationService.reserveNearest(command("bob", key));

		assertThat(alice.replayed()).isFalse();
		assertThat(bob.replayed()).isFalse();
		assertThat(bob.reservation().reservationId()).isNotEqualTo(alice.reservation().reservationId());
		assertThat(availableBeds(NEAREST_CODE)).isEqualTo(1);
		assertThat(reservationCount()).isEqualTo(2);
	}

	@Test
	void allowsARefusedRequestToBeRetriedWithTheSameKey() {
		UUID key = UUID.randomUUID();
		setAvailability(NEAREST_CODE, 0);
		setAvailability(FARTHER_CODE, 0);

		assertThatThrownBy(() -> reservationService.reserveNearest(command(REGULATOR, key)))
				.isInstanceOf(NoAvailableHospitalException.class);
		assertThat(requestCount()).isZero();

		setAvailability(NEAREST_CODE, 1);
		ReservationOutcome retry = reservationService.reserveNearest(command(REGULATOR, key));

		assertThat(retry.replayed()).isFalse();
		assertThat(retry.reservation().hospitalCode()).isEqualTo(NEAREST_CODE);
		assertThat(availableBeds(NEAREST_CODE)).isZero();
		assertThat(reservationCount()).isEqualTo(1);
	}

	@Test
	void rejectsInvalidCoordinatesWithoutWritingAnything() throws Exception {
		mockMvc.perform(reservationPost(requestJson(SPECIALTY).replace("51.5074", "95"), UUID.randomUUID()))
				.andExpect(status().isBadRequest());
		assertThat(availableBeds(NEAREST_CODE)).isEqualTo(3);
		assertNoReservationOrEvent();
	}

	@Test
	void rejectsAnUnknownSpecialtyWithoutWritingAnything() throws Exception {
		mockMvc.perform(reservationPost(requestJson("UNKNOWN_TEST"), UUID.randomUUID()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("urn:problem:unknown-specialty"));
		assertThat(availableBeds(NEAREST_CODE)).isEqualTo(3);
		assertNoReservationOrEvent();
	}

	@Test
	void returnsNotFoundWithoutCreatingAnEventWhenNoBedIsAvailable() throws Exception {
		setAvailability(NEAREST_CODE, 0);
		setAvailability(FARTHER_CODE, 0);
		mockMvc.perform(reservationPost(requestJson(SPECIALTY), UUID.randomUUID()))
				.andExpect(status().isNotFound());
		assertNoReservationOrEvent();
	}

	@Test
	void rollsBackTheBedAndReservationIfTheOutboxInsertFails() {
		jdbc.execute("""
				alter table event_outbox add constraint test_reject_outbox
				check (payload ->> 'hospitalCode' <> 'TEST-RESERVE-NEAREST')
				""");

		assertThatThrownBy(() -> reservationService.reserveNearest(command(REGULATOR, UUID.randomUUID())))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(availableBeds(NEAREST_CODE)).isEqualTo(3);
		assertNoReservationOrEvent();
	}

	@Test
	void reservesTheLastBedOnlyOnceUnderConcurrentRequests() throws Exception {
		setAvailability(NEAREST_CODE, 1);
		setAvailability(FARTHER_CODE, 0);

		List<ReservationOutcome> reservations = reserveConcurrently(8, request -> UUID.randomUUID());

		assertThat(reservations).hasSize(1);
		assertThat(reservations.getFirst().reservation().availableBeds()).isZero();
		assertThat(availableBeds(NEAREST_CODE)).isZero();
		assertThat(reservationCount()).isEqualTo(1);
		assertThat(outboxCount()).isEqualTo(1);
	}

	@Test
	void continuesWithTheNextHospitalWhenConcurrentRequestsExhaustTheNearestOne() throws Exception {
		setAvailability(NEAREST_CODE, 1);

		List<ReservedBed> reservations = reserveConcurrently(4, request -> UUID.randomUUID())
				.stream().map(ReservationOutcome::reservation).toList();

		assertThat(reservations).hasSize(4);
		assertThat(reservations.stream().filter(r -> r.hospitalCode().equals(NEAREST_CODE))).hasSize(1);
		assertThat(reservations.stream().filter(r -> r.hospitalCode().equals(FARTHER_CODE)))
				.extracting(ReservedBed::availableBeds).containsExactlyInAnyOrder(2, 1, 0);
		assertThat(reservations).extracting(ReservedBed::reservationId).doesNotHaveDuplicates();
		assertThat(availableBeds(NEAREST_CODE)).isZero();
		assertThat(availableBeds(FARTHER_CODE)).isZero();
		assertThat(reservationCount()).isEqualTo(4);
		assertThat(outboxCount()).isEqualTo(4);
	}

	@Test
	void concurrentRetriesWithTheSameKeyReserveASingleBed() throws Exception {
		UUID key = UUID.randomUUID();

		List<ReservationOutcome> outcomes = reserveConcurrently(8, request -> key);

		assertThat(outcomes).hasSize(8);
		assertThat(outcomes).filteredOn(outcome -> !outcome.replayed()).hasSize(1);
		assertThat(outcomes).extracting(outcome -> outcome.reservation().reservationId()).containsOnly(
				outcomes.getFirst().reservation().reservationId()
		);
		assertThat(availableBeds(NEAREST_CODE)).isEqualTo(2);
		assertThat(availableBeds(FARTHER_CODE)).isEqualTo(3);
		assertThat(reservationCount()).isEqualTo(1);
		assertThat(outboxCount()).isEqualTo(1);
		assertThat(requestCount()).isEqualTo(1);
	}

	private List<ReservationOutcome> reserveConcurrently(int requests, IntFunction<UUID> keys) throws Exception {
		CountDownLatch ready = new CountDownLatch(requests);
		CountDownLatch start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(requests);
		try {
			List<Future<ReservationOutcome>> futures = new ArrayList<>();
			for (int i = 0; i < requests; i++) {
				UUID key = keys.apply(i);
				futures.add(executor.submit(() -> {
					ready.countDown();
					if (!start.await(10, TimeUnit.SECONDS)) {
						throw new AssertionError("Concurrent requests did not start in time");
					}
					try {
						return reservationService.reserveNearest(command(REGULATOR, key));
					} catch (NoAvailableHospitalException expected) {
						return null;
					}
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			List<ReservationOutcome> outcomes = new ArrayList<>();
			for (Future<ReservationOutcome> future : futures) {
				ReservationOutcome outcome = future.get(20, TimeUnit.SECONDS);
				if (outcome != null) {
					outcomes.add(outcome);
				}
			}
			return outcomes;
		} finally {
			start.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	private void addHospital(String code, double longitude, int availableBeds, String specialty) {
		long id = jdbc.queryForObject("""
				insert into hospital (code, name, location, total_beds, available_beds)
				values (?, ?, ST_SetSRID(ST_MakePoint(?, 51.5074), 4326)::geography, 3, ?)
				returning id
				""", Long.class, code, code, longitude, availableBeds);
		jdbc.update("""
				insert into hospital_specialty (hospital_id, specialty_id)
				select ?, id from specialty where code = ?
				""", id, specialty);
	}

	private void setAvailability(String code, int availableBeds) {
		jdbc.update("update hospital set available_beds = ? where code = ?", availableBeds, code);
	}

	private int availableBeds(String code) {
		return jdbc.queryForObject("select available_beds from hospital where code = ?", Integer.class, code);
	}

	private int reservationCount() {
		return jdbc.queryForObject("select count(*) from bed_reservation where specialty_code = ?", Integer.class, SPECIALTY);
	}

	private int outboxCount() {
		return jdbc.queryForObject("select count(*) from event_outbox where payload ->> 'specialtyCode' = ?", Integer.class, SPECIALTY);
	}

	private int requestCount() {
		return jdbc.queryForObject("""
				select count(*) from reservation_request request
				join bed_reservation reservation on reservation.id = request.reservation_id
				where reservation.specialty_code = ?
				""", Integer.class, SPECIALTY);
	}

	private void assertNoReservationOrEvent() {
		assertThat(reservationCount()).isZero();
		assertThat(outboxCount()).isZero();
		assertThat(requestCount()).isZero();
	}

	private static ReserveBedCommand command(String requestedBy, UUID idempotencyKey) {
		return new ReserveBedCommand(requestedBy, idempotencyKey, 51.5074, -0.1278, SPECIALTY);
	}

	private static MockHttpServletRequestBuilder reservationPost(String json, UUID idempotencyKey) {
		return post("/api/v1/hospitals/reservations")
				.header("Idempotency-Key", idempotencyKey.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json);
	}

	private static String requestJson(String specialtyCode) {
		return """
				{"latitude":51.5074,"longitude":-0.1278,"specialtyCode":"%s"}
				""".formatted(specialtyCode);
	}
}
