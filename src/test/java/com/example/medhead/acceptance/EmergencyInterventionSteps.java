package com.example.medhead.acceptance;

import com.example.medhead.hospital.application.BedReservedEvent;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.fr.Alors;
import io.cucumber.java.fr.Et;
import io.cucumber.java.fr.Mais;
import io.cucumber.java.fr.Quand;
import io.cucumber.java.fr.Soit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Steps of {@code intervention-urgence.feature}. Each scenario creates its own hospitals,
 * coded {@code PDF-...}, and calls the REST API as a regulator.
 */
public class EmergencyInterventionSteps {

	private static final String CODE_PREFIX = "PDF-";
	private static final String HOSPITAL_PREFIX = "Hôpital ";
	private static final String REGULATOR = "regulateur-acceptance";
	/** The requirements document gives available beds only; every hospital has this capacity. */
	private static final int TOTAL_BEDS = 10;

	/**
	 * The requirements document does not locate the hospitals: they are placed in central
	 * London, three to five kilometres apart (latitude, longitude).
	 */
	private static final Map<String, double[]> LOCATIONS = Map.of(
			"Fred Brooks", new double[] {51.5246, -0.1340},
			"Julia Crusher", new double[] {51.5010, -0.1180},
			"Beverly Bashir", new double[] {51.5185, -0.0590}
	);
	/** North offsets of the emergency, in degrees of latitude: about 200 m and 55 m. */
	private static final double NEAR = 0.0018;
	private static final double VERY_NEAR = 0.0005;

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private JsonMapper jsonMapper;

	/** Hospitals of the scenario, by short name (without "Hôpital"). */
	private final Map<String, ScenarioHospital> hospitals = new LinkedHashMap<>();
	private String neededSpecialty;
	private String requestedSpecialty;
	private double[] emergency;
	private int reservationsBefore;
	private int eventsBefore;
	private MockHttpServletResponse response;
	private JsonNode body;

	@Before
	public void startFromACleanDatabase() {
		removeScenarioData();
	}

	@After
	public void removeScenarioData() {
		jdbc.update("delete from event_outbox where payload ->> 'hospitalCode' like ?", CODE_PREFIX + "%");
		// Reservations and their idempotency rows are removed by cascade.
		jdbc.update("delete from hospital where code like ?", CODE_PREFIX + "%");
	}

	@Soit("trois hôpitaux :")
	public void threeHospitals(DataTable table) {
		List<Map<String, String>> rows = table.asMaps();
		assertThat(rows).hasSize(3);
		for (Map<String, String> row : rows) {
			String name = row.get("Hôpital");
			int availableBeds = Integer.parseInt(row.get("Lits disponibles").trim());
			List<String> specialtyCodes = Arrays.stream(row.get("Spécialisations").split(","))
					.map(this::specialtyCode)
					.toList();
			addHospital(name, availableBeds, specialtyCodes);
		}
	}

	@Et("^un patient nécessitant des soins en (.+)$")
	public void aPatientNeedingCareIn(String specialtyName) {
		neededSpecialty = specialtyCode(specialtyName);
	}

	@Quand("^un patient demande des soins en (.+)$")
	public void aPatientRequestsCareIn(String specialtyName) {
		requestedSpecialty = specialtyCode(specialtyName);
		assertThat(requestedSpecialty).as("requested specialty").isEqualTo(neededSpecialty);
	}

	/** "Et que" is a French Gherkin keyword: the step text starts at "l'urgence". */
	@Et("^l'urgence est localisée (tout )?près de l'hôpital (.+)$")
	public void theEmergencyIsLocatedNear(String veryNear, String hospitalName) {
		double[] location = hospital(hospitalName).location();
		double offset = veryNear == null ? NEAR : VERY_NEAR;
		emergency = new double[] {location[0] + offset, location[1]};
	}

	@Alors("^l'hôpital (.+) devrait être proposé$")
	public void theHospitalShouldBeProposed(String hospitalName) throws Exception {
		ScenarioHospital expected = hospital(hospitalName);
		reserve();

		assertThat(response.getStatus()).as("HTTP status, body: %s", body).isEqualTo(201);
		assertThat(body.get("hospitalCode").asString()).isEqualTo(expected.code());
		assertThat(body.get("hospitalName").asString()).isEqualTo(expected.name());
		assertThat(body.get("specialtyCode").asString()).isEqualTo(requestedSpecialty);
		assertThat(body.get("availableBeds").asInt()).isEqualTo(expected.availableBeds() - 1);
		assertThat(availableBeds(expected)).isEqualTo(expected.availableBeds() - 1);
	}

	@Et("un événement devrait être publié pour réserver un lit")
	public void anEventShouldBePublishedToReserveABed() throws Exception {
		UUID reservationId = UUID.fromString(body.get("reservationId").asString());
		List<Map<String, Object>> events = jdbc.queryForList(
				"select id, event_type, payload::text as payload from event_outbox where aggregate_id = ?",
				reservationId
		);

		assertThat(events).hasSize(1);
		Map<String, Object> event = events.getFirst();
		assertThat(event.get("event_type")).isEqualTo(BedReservedEvent.TYPE);
		BedReservedEvent payload = jsonMapper.readValue((String) event.get("payload"), BedReservedEvent.class);
		assertThat(payload.eventId()).isEqualTo(event.get("id"));
		assertThat(payload.reservationId()).isEqualTo(reservationId);
		assertThat(payload.hospitalCode()).isEqualTo(body.get("hospitalCode").asString());
		assertThat(payload.specialtyCode()).isEqualTo(requestedSpecialty);
		assertThat(jdbc.queryForObject(
				"select h.code from bed_reservation r join hospital h on h.id = r.hospital_id where r.id = ?",
				String.class, reservationId
		)).isEqualTo(payload.hospitalCode());
	}

	@Mais("^l'hôpital (.+), pourtant plus proche, ne devrait pas être proposé$")
	public void theCloserHospitalShouldNotBeProposed(String hospitalName) {
		ScenarioHospital closer = hospital(hospitalName);

		assertThat(body.get("hospitalCode").asString()).isNotEqualTo(closer.code());
		assertThat(distanceToEmergency(closer)).isLessThan(body.get("distanceMeters").asDouble());
		assertThat(availableBeds(closer)).isEqualTo(closer.availableBeds());
	}

	@Alors("aucun hôpital ne devrait être proposé")
	public void noHospitalShouldBeProposed() throws Exception {
		reserve();

		assertThat(response.getStatus()).as("HTTP status, body: %s", body).isEqualTo(404);
		assertThat(body.get("type").asString()).isEqualTo("urn:problem:no-available-hospital");
		assertThat(body.get("specialtyCode").asString()).isEqualTo(requestedSpecialty);
	}

	@Et("aucun lit ne devrait être réservé")
	public void noBedShouldBeReserved() {
		for (ScenarioHospital hospital : hospitals.values()) {
			assertThat(availableBeds(hospital)).as(hospital.name()).isEqualTo(hospital.availableBeds());
		}
		assertThat(jdbc.queryForObject("select count(*) from bed_reservation", Integer.class))
				.isEqualTo(reservationsBefore);
	}

	@Et("aucun événement ne devrait être publié")
	public void noEventShouldBePublished() {
		assertThat(jdbc.queryForObject("select count(*) from event_outbox", Integer.class))
				.isEqualTo(eventsBefore);
	}

	/** Sends the reservation request once the specialty and the location are known. */
	private void reserve() throws Exception {
		if (response != null) {
			return;
		}
		assertThat(requestedSpecialty).as("requested specialty").isNotNull();
		assertThat(emergency).as("emergency location").isNotNull();
		reservationsBefore = jdbc.queryForObject("select count(*) from bed_reservation", Integer.class);
		eventsBefore = jdbc.queryForObject("select count(*) from event_outbox", Integer.class);

		response = mockMvc.perform(post("/api/v1/hospitals/reservations")
						.with(jwt()
								.jwt(token -> token.subject(REGULATOR))
								.authorities(new SimpleGrantedAuthority("ROLE_REGULATEUR")))
						.header("Idempotency-Key", UUID.randomUUID().toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content(jsonMapper.writeValueAsString(Map.of(
								"latitude", emergency[0],
								"longitude", emergency[1],
								"specialtyCode", requestedSpecialty
						))))
				.andReturn().getResponse();
		body = jsonMapper.readTree(response.getContentAsString());
	}

	private void addHospital(String name, int availableBeds, List<String> specialtyCodes) {
		String shortName = shortName(name);
		double[] location = LOCATIONS.get(shortName);
		assertThat(location).as("location of %s", name).isNotNull();
		String code = CODE_PREFIX + shortName.toUpperCase(Locale.ROOT).replace(' ', '-');
		long id = jdbc.queryForObject("""
				insert into hospital (code, name, location, total_beds, available_beds)
				values (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?)
				returning id
				""", Long.class, code, name, location[1], location[0], TOTAL_BEDS, availableBeds);
		for (String specialtyCode : specialtyCodes) {
			jdbc.update("""
					insert into hospital_specialty (hospital_id, specialty_id)
					select ?, id from specialty where code = ?
					""", id, specialtyCode);
		}
		hospitals.put(shortName, new ScenarioHospital(code, name, location, availableBeds));
	}

	/** Finds a specialty of the NHS reference data by its French label, ignoring case. */
	private String specialtyCode(String label) {
		List<String> codes = jdbc.queryForList(
				"select code from specialty where lower(name) = lower(?)", String.class, label.trim()
		);
		assertThat(codes).as("specialty labelled \"%s\"", label.trim()).hasSize(1);
		return codes.getFirst();
	}

	private ScenarioHospital hospital(String name) {
		ScenarioHospital hospital = hospitals.get(shortName(name));
		assertThat(hospital).as("hospital %s of the scenario", name).isNotNull();
		return hospital;
	}

	private int availableBeds(ScenarioHospital hospital) {
		return jdbc.queryForObject(
				"select available_beds from hospital where code = ?", Integer.class, hospital.code()
		);
	}

	private double distanceToEmergency(ScenarioHospital hospital) {
		return jdbc.queryForObject("""
				select ST_Distance(location, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
				from hospital where code = ?
				""", Double.class, emergency[1], emergency[0], hospital.code());
	}

	private static String shortName(String name) {
		String trimmed = name.trim();
		return trimmed.regionMatches(true, 0, HOSPITAL_PREFIX, 0, HOSPITAL_PREFIX.length())
				? trimmed.substring(HOSPITAL_PREFIX.length())
				: trimmed;
	}

	private record ScenarioHospital(String code, String name, double[] location, int availableBeds) {
	}
}
