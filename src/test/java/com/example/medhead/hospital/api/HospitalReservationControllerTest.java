package com.example.medhead.hospital.api;

import com.example.medhead.hospital.application.NoAvailableHospitalException;
import com.example.medhead.hospital.application.ReserveNearestAvailableBedService;
import com.example.medhead.hospital.application.UnknownSpecialtyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class HospitalReservationControllerTest {

	private static final String REQUEST = """
			{"latitude":51.5074,"longitude":-0.1278,"specialtyCode":"CARDIOLOGY"}
			""";
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
		reservationService.behaviour = request -> new HospitalReservationResponse(
				reservationId, Instant.parse("2026-10-14T08:00:00Z"),
				1L, "DEMO-LON-001", "MedHead Westminster", 51.5072, -0.1276,
				350, 16, 42.5, request.specialtyCode()
		);

		mockMvc.perform(post("/api/v1/hospitals/reservations")
					.contentType(MediaType.APPLICATION_JSON).content(REQUEST))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.reservationId").value(reservationId.toString()))
				.andExpect(jsonPath("$.reservedAt").value("2026-10-14T08:00:00Z"))
				.andExpect(jsonPath("$.hospitalCode").value("DEMO-LON-001"))
				.andExpect(jsonPath("$.availableBeds").value(16))
				.andExpect(jsonPath("$.specialtyCode").value("CARDIOLOGY"));
		assertThat(reservationService.callCount).isEqualTo(1);
	}

	@Test
	void rejectsAnInvalidRequestBeforeReservingAnything() throws Exception {
		mockMvc.perform(post("/api/v1/hospitals/reservations")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
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
		mockMvc.perform(post("/api/v1/hospitals/reservations")
					.contentType(MediaType.APPLICATION_JSON).content(REQUEST))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("urn:problem:unknown-specialty"));
	}

	@Test
	void returnsNotFoundWhenNoBedIsAvailable() throws Exception {
		reservationService.behaviour = request -> {
			throw new NoAvailableHospitalException(request.specialtyCode());
		};
		mockMvc.perform(post("/api/v1/hospitals/reservations")
					.contentType(MediaType.APPLICATION_JSON).content(REQUEST))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("urn:problem:no-available-hospital"));
	}

	private static final class StubReservationService extends ReserveNearestAvailableBedService {

		private Function<HospitalReservationRequest, HospitalReservationResponse> behaviour = request -> {
			throw new AssertionError("Reservation service should not have been called");
		};
		private int callCount;

		private StubReservationService() {
			super(null, null, null, null, null);
		}

		@Override
		public HospitalReservationResponse reserveNearest(HospitalReservationRequest request) {
			callCount++;
			return behaviour.apply(request);
		}
	}
}
