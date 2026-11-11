package com.example.medhead.hospital.api;

import com.example.medhead.hospital.application.IdempotencyKeyReusedException;
import com.example.medhead.hospital.application.NoAvailableHospitalException;
import com.example.medhead.hospital.application.ReservationOutcome;
import com.example.medhead.hospital.application.ReserveBedCommand;
import com.example.medhead.hospital.application.ReserveNearestAvailableBedService;
import com.example.medhead.hospital.application.ReservedBed;
import com.example.medhead.hospital.application.UnknownSpecialtyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class HospitalReservationControllerTest {

	private static final String REQUEST = """
			{"latitude":51.5074,"longitude":-0.1278,"specialtyCode":"CARDIOLOGY"}
			""";
	private static final UUID KEY = UUID.fromString("6f1c2b8e-0d3a-4c55-9e7a-1b2c3d4e5f60");
	private static final Principal ALICE = () -> "alice";
	private StubReservationService reservationService;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		reservationService = new StubReservationService();
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		mockMvc = standaloneSetup(new HospitalReservationController(reservationService))
				.setControllerAdvice(new ApiExceptionHandler())
				.setValidator(validator)
				.build();
	}

	@Test
	void returnsACreatedReservationWithTheRemainingAvailability() throws Exception {
		UUID reservationId = UUID.randomUUID();
		reservationService.behaviour = request -> ReservationOutcome.created(reservation(reservationId, request));

		mockMvc.perform(reservationRequest(REQUEST))
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotent-Replayed"))
				.andExpect(jsonPath("$.reservationId").value(reservationId.toString()))
				.andExpect(jsonPath("$.reservedAt").value("2026-10-14T08:00:00Z"))
				.andExpect(jsonPath("$.hospitalCode").value("DEMO-LON-001"))
				.andExpect(jsonPath("$.availableBeds").value(16))
				.andExpect(jsonPath("$.specialtyCode").value("CARDIOLOGY"));
		assertThat(reservationService.callCount).isEqualTo(1);
		assertThat(reservationService.command).isEqualTo(
				new ReserveBedCommand("alice", KEY, 51.5074, -0.1278, "CARDIOLOGY")
		);
	}

	@Test
	void marksARepeatedRequestThatReturnsTheOriginalReservation() throws Exception {
		UUID reservationId = UUID.randomUUID();
		reservationService.behaviour = request -> ReservationOutcome.replayed(reservation(reservationId, request));

		mockMvc.perform(reservationRequest(REQUEST))
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andExpect(jsonPath("$.reservationId").value(reservationId.toString()));
	}

	@Test
	void rejectsARequestWithoutIdempotencyKeyBeforeReservingAnything() throws Exception {
		mockMvc.perform(post("/api/v1/hospitals/reservations").principal(ALICE)
					.contentType(MediaType.APPLICATION_JSON).content(REQUEST))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("urn:problem:invalid-request"))
				.andExpect(jsonPath("$.errors['Idempotency-Key']").value("Idempotency-Key header is required"));
		assertThat(reservationService.callCount).isZero();
	}

	@Test
	void rejectsAnIdempotencyKeyThatIsNotAUuid() throws Exception {
		mockMvc.perform(post("/api/v1/hospitals/reservations").principal(ALICE)
					.header("Idempotency-Key", "retry-1")
					.contentType(MediaType.APPLICATION_JSON).content(REQUEST))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors['Idempotency-Key']").value("Idempotency-Key must be a UUID"));
		assertThat(reservationService.callCount).isZero();
	}

	@Test
	void rejectsAnInvalidRequestBeforeReservingAnything() throws Exception {
		mockMvc.perform(reservationRequest("""
							{"latitude":95,"longitude":-0.1278,"specialtyCode":"cardiology"}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.latitude").exists())
				.andExpect(jsonPath("$.errors.specialtyCode").exists());
		assertThat(reservationService.callCount).isZero();
	}

	@Test
	void returnsBadRequestForAnUnknownSpecialty() throws Exception {
		reservationService.behaviour = request -> {
			throw new UnknownSpecialtyException(request.specialtyCode());
		};
		mockMvc.perform(reservationRequest(REQUEST))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("urn:problem:unknown-specialty"));
	}

	@Test
	void returnsNotFoundWhenNoBedIsAvailable() throws Exception {
		reservationService.behaviour = request -> {
			throw new NoAvailableHospitalException(request.specialtyCode());
		};
		mockMvc.perform(reservationRequest(REQUEST))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("urn:problem:no-available-hospital"));
	}

	@Test
	void returnsUnprocessableContentWhenTheKeyWasUsedForAnotherRequest() throws Exception {
		reservationService.behaviour = request -> {
			throw new IdempotencyKeyReusedException(KEY);
		};
		mockMvc.perform(reservationRequest(REQUEST))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.type").value("urn:problem:idempotency-key-reused"))
				.andExpect(jsonPath("$.idempotencyKey").value(KEY.toString()));
	}

	private static MockHttpServletRequestBuilder reservationRequest(String body) {
		return post("/api/v1/hospitals/reservations")
				.principal(ALICE)
				.header("Idempotency-Key", KEY.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	private static ReservedBed reservation(UUID reservationId, ReserveBedCommand command) {
		return new ReservedBed(
				reservationId, Instant.parse("2026-10-14T08:00:00Z"),
				1L, "DEMO-LON-001", "MedHead Westminster", 51.5072, -0.1276,
				350, 16, 42.5, command.specialtyCode()
		);
	}

	private static final class StubReservationService extends ReserveNearestAvailableBedService {

		private Function<ReserveBedCommand, ReservationOutcome> behaviour = command -> {
			throw new AssertionError("Reservation service should not have been called");
		};
		private int callCount;
		private ReserveBedCommand command;

		private StubReservationService() {
			super(null, null, null, null, null, null);
		}

		@Override
		public ReservationOutcome reserveNearest(ReserveBedCommand command) {
			callCount++;
			this.command = command;
			return behaviour.apply(command);
		}
	}
}
