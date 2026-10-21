package com.example.medhead.hospital.api;

import com.example.medhead.hospital.application.FindNearestAvailableHospitalService;
import com.example.medhead.hospital.application.UnknownSpecialtyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class HospitalSearchControllerTest {

	private StubSearchService searchService;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		searchService = new StubSearchService();
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		mockMvc = standaloneSetup(new HospitalSearchController(searchService))
				.setControllerAdvice(new ApiExceptionHandler())
				.setValidator(validator)
				.build();
	}

	@Test
	void returnsTheNearestAvailableHospital() throws Exception {
		searchService.behaviour = request -> new HospitalSearchResponse(
				1L,
				"DEMO-LON-001",
				"MedHead Westminster",
				51.5072,
				-0.1276,
				350,
				17,
				42.5,
				"CARDIOLOGY"
		);

		mockMvc.perform(post("/api/v1/hospitals/search")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "latitude": 51.5074,
							  "longitude": -0.1278,
							  "specialtyCode": "CARDIOLOGY"
							}
							"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.hospitalCode").value("DEMO-LON-001"))
				.andExpect(jsonPath("$.distanceMeters").value(42.5))
				.andExpect(jsonPath("$.specialtyCode").value("CARDIOLOGY"));
	}

	@Test
	void rejectsAnInvalidSpecialtyCodeBeforeCallingTheService() throws Exception {
		mockMvc.perform(post("/api/v1/hospitals/search")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "latitude": 51.5074,
							  "longitude": -0.1278,
							  "specialtyCode": "CARDIOLOGY'; DROP TABLE hospital; --"
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"))
				.andExpect(jsonPath("$.errors.specialtyCode").exists());

		assertThat(searchService.callCount).isZero();
	}

	@Test
	void rejectsCoordinatesOutsideWgs84Bounds() throws Exception {
		mockMvc.perform(post("/api/v1/hospitals/search")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "latitude": 95,
							  "longitude": -0.1278,
							  "specialtyCode": "CARDIOLOGY"
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.latitude").exists());
	}

	@Test
	void returnsAProblemForAnUnknownSpecialty() throws Exception {
		searchService.behaviour = request -> {
			throw new UnknownSpecialtyException("UNKNOWN");
		};

		mockMvc.perform(post("/api/v1/hospitals/search")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "latitude": 51.5074,
							  "longitude": -0.1278,
							  "specialtyCode": "UNKNOWN"
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("urn:problem:unknown-specialty"))
				.andExpect(jsonPath("$.specialtyCode").value("UNKNOWN"));
	}

	private static final class StubSearchService extends FindNearestAvailableHospitalService {

		private Function<HospitalSearchRequest, HospitalSearchResponse> behaviour;
		private int callCount;

		private StubSearchService() {
			super(null, null);
			this.behaviour = request -> {
				throw new AssertionError("Search service should not have been called");
			};
		}

		@Override
		public HospitalSearchResponse findNearest(HospitalSearchRequest request) {
			callCount++;
			return behaviour.apply(request);
		}
	}
}
