package com.example.medhead.acceptance;

import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/**
 * Spring context of the acceptance scenarios: the full application on the same PostGIS TLS
 * test database as {@code BedReservationIntegrationTest}, overridable with
 * {@code -Dmedhead.test.jdbc-url=...}.
 */
@CucumberContextConfiguration
@SpringBootTest(properties = {
		"spring.datasource.url=${medhead.test.jdbc-url:jdbc:postgresql://localhost:55432/hospital_test?sslmode=verify-full&sslrootcert=.local/postgres-tests/ca.crt}",
		"spring.datasource.username=hospital_test",
		"spring.datasource.password=hospital_test"
})
@AutoConfigureMockMvc
class CucumberSpringConfiguration {
}
