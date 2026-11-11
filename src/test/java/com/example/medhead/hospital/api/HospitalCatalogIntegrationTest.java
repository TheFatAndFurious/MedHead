package com.example.medhead.hospital.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.test.context.support.WithMockUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "medhead.integration-tests", matches = "true")
@SpringBootTest(properties = {
		"spring.datasource.url=${medhead.test.jdbc-url:jdbc:postgresql://localhost:55432/hospital_test}",
		"spring.datasource.username=hospital_test",
		"spring.datasource.password=hospital_test"
})
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = "LECTEUR")
class HospitalCatalogIntegrationTest {

	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from hospital");
		jdbc.update("delete from specialty");
	}

	@Test
	void listsHospitalsWithTheirMapCoordinatesIncludingHospitalsWithoutBeds() throws Exception {
		addHospital("TEST-CATALOG-Z", "Zulu Hospital", -0.14, 51.54, 3);
		long hospitalId = addHospital("TEST-CATALOG-A", "Alpha Hospital", -0.1276, 51.5072, 0);
		int reservationCount = jdbc.queryForObject("select count(*) from bed_reservation", Integer.class);
		int eventCount = jdbc.queryForObject("select count(*) from event_outbox", Integer.class);

		mockMvc.perform(get("/api/v1/hospitals"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].length()").value(5))
				.andExpect(jsonPath("$[0].id").value(hospitalId))
				.andExpect(jsonPath("$[0].code").value("TEST-CATALOG-A"))
				.andExpect(jsonPath("$[0].name").value("Alpha Hospital"))
				.andExpect(jsonPath("$[0].latitude").value(51.5072))
				.andExpect(jsonPath("$[0].longitude").value(-0.1276))
				.andExpect(jsonPath("$[1].code").value("TEST-CATALOG-Z"));

		assertThat(jdbc.queryForList(
				"select available_beds from hospital order by name", Integer.class
		)).containsExactly(0, 3);
		assertThat(jdbc.queryForObject("select count(*) from bed_reservation", Integer.class))
				.isEqualTo(reservationCount);
		assertThat(jdbc.queryForObject("select count(*) from event_outbox", Integer.class))
				.isEqualTo(eventCount);
	}

	@Test
	void listsAllSpecialtiesByNameIncludingUnassignedSpecialties() throws Exception {
		jdbc.update("insert into specialty (code, name) values (?, ?)", "CARDIOLOGY", "Cardiologie");
		jdbc.update("insert into specialty (code, name) values (?, ?)", "ANAESTHESIA", "Anesthésie");
		long hospitalId = addHospital("TEST-CATALOG-A", "Alpha Hospital", -0.1276, 51.5072, 3);
		jdbc.update("""
				insert into hospital_specialty (hospital_id, specialty_id)
				select ?, id from specialty where code = 'CARDIOLOGY'
				""", hospitalId);

		mockMvc.perform(get("/api/v1/specialties"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].length()").value(2))
				.andExpect(jsonPath("$[0].code").value("ANAESTHESIA"))
				.andExpect(jsonPath("$[0].name").value("Anesthésie"))
				.andExpect(jsonPath("$[1].code").value("CARDIOLOGY"))
				.andExpect(jsonPath("$[1].name").value("Cardiologie"));

		assertThat(jdbc.queryForObject("select available_beds from hospital where id = ?", Integer.class, hospitalId))
				.isEqualTo(3);
	}

	@Test
	void returnsAnEmptyArrayWhenThereAreNoHospitals() throws Exception {
		mockMvc.perform(get("/api/v1/hospitals"))
				.andExpect(status().isOk())
				.andExpect(content().json("[]"));
	}

	@Test
	void returnsAnEmptyArrayWhenThereAreNoSpecialties() throws Exception {
		mockMvc.perform(get("/api/v1/specialties"))
				.andExpect(status().isOk())
				.andExpect(content().json("[]"));
	}

	private long addHospital(String code, String name, double longitude, double latitude, int availableBeds) {
		return jdbc.queryForObject("""
				insert into hospital (code, name, location, total_beds, available_beds)
				values (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 3, ?)
				returning id
				""", Long.class, code, name, longitude, latitude, availableBeds);
	}
}
