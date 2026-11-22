package com.example.medhead;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.cert.CertPathBuilderException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL only accepts TLS connections and the application verifies the server
 * certificate against the local CA (sslmode=verify-full).
 */
@EnabledIfSystemProperty(named = "medhead.integration-tests", matches = "true")
@SpringBootTest(properties = {
		"spring.datasource.url=${medhead.test.jdbc-url:jdbc:postgresql://localhost:55432/hospital_test?sslmode=verify-full&sslrootcert=.local/postgres-tests/ca.crt}",
		"spring.datasource.username=hospital_test",
		"spring.datasource.password=hospital_test"
})
class DatabaseTlsIntegrationTest {

	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private DataSourceProperties database;

	@Test
	void theApplicationConnectionPoolUsesTls() {
		Map<String, Object> session = jdbc.queryForMap(
				"select ssl, version from pg_stat_ssl where pid = pg_backend_pid()");

		assertThat(session.get("ssl")).isEqualTo(true);
		assertThat(session.get("version")).isIn("TLSv1.2", "TLSv1.3");
	}

	@Test
	void theServerRejectsConnectionsWithoutTls() {
		assertThatThrownBy(() -> connectWith("sslmode=disable"))
				.isInstanceOfSatisfying(PSQLException.class,
						error -> assertThat(error.getSQLState()).isEqualTo("28000"))
				.hasMessageContaining("no encryption");
	}

	@Test
	void verifyFullRejectsAServerCertificateThatTheTrustedCaDidNotSign() {
		// The JVM trust store does not contain the local CA that signed the server certificate.
		assertThatThrownBy(() -> connectWith("sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory"))
				.isInstanceOf(PSQLException.class)
				.hasRootCauseInstanceOf(CertPathBuilderException.class);
	}

	private void connectWith(String sslParameters) throws SQLException {
		String url = database.determineUrl();
		int query = url.indexOf('?');
		String server = query < 0 ? url : url.substring(0, query);
		try (Connection ignored = DriverManager.getConnection(server + "?" + sslParameters,
				database.determineUsername(), database.determinePassword())) {
			// Opening the connection is the behaviour under test.
		}
	}
}
