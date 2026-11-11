package com.example.medhead.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringJUnitConfig(SecurityConfigurationTest.TestConfiguration.class)
@WebAppConfiguration
class SecurityConfigurationTest {
	private static final String ISSUER = "https://identity.example.test/realms/medhead";
	private static final RSAKey KEY = signingKey();
	@Autowired WebApplicationContext context;
	@Autowired TestEndpoints endpoints;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = webAppContextSetup(context).apply(springSecurity()).build();
		endpoints.reservations = 0;
	}

	@Test
	void anonymousCallsCannotReadOrReserve() throws Exception {
		mvc.perform(get("/api/v1/hospitals")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/hospitals").cookie(new Cookie("medhead_session", "forged")))
				.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/hospitals/reservations")).andExpect(status().isUnauthorized());
		assertThat(endpoints.reservations).isZero();
	}

	@Test
	void readerCanReadBothCataloguesButCannotReserve() throws Exception {
		String token = token(ISSUER, "medhead-api", List.of("LECTEUR"), Instant.now().plusSeconds(300), KEY);
		mvc.perform(get("/api/v1/hospitals").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/specialties").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
		mvc.perform(post("/api/v1/hospitals/reservations").header("Authorization", "Bearer " + token))
				.andExpect(status().isForbidden());
		assertThat(endpoints.reservations).isZero();
	}

	@Test
	void regulatorCanReserveWithBearerAuthenticationWithoutASessionOrCsrfCookie() throws Exception {
		mvc.perform(post("/api/v1/hospitals/reservations").header("Authorization", "Bearer " +
				token(ISSUER, "medhead-api", List.of("REGULATEUR"), Instant.now().plusSeconds(300), KEY)))
				.andExpect(status().isCreated()).andExpect(header().doesNotExist("Set-Cookie"));
		assertThat(endpoints.reservations).isEqualTo(1);
	}

	@Test
	void rejectsExpiredTokens() throws Exception {
		assertRejected(token(ISSUER, "medhead-api", List.of("REGULATEUR"), Instant.now().minusSeconds(120), KEY));
	}

	@Test
	void rejectsTokensFromAnotherIssuer() throws Exception {
		assertRejected(token("https://other.example.test", "medhead-api", List.of("REGULATEUR"), Instant.now().plusSeconds(300), KEY));
	}

	@Test
	void rejectsTokensForAnotherAudienceIncludingWebIdentityTokens() throws Exception {
		assertRejected(token(ISSUER, "medhead-web", List.of("REGULATEUR"), Instant.now().plusSeconds(300), KEY));
	}

	@Test
	void rejectsForgedAndMalformedTokens() throws Exception {
		assertRejected(token(ISSUER, "medhead-api", List.of("REGULATEUR"), Instant.now().plusSeconds(300), signingKey()));
		assertRejected("not-a-jwt");
	}

	@Test
	void authenticatedAccountWithoutABusinessRoleCannotRead() throws Exception {
		mvc.perform(get("/api/v1/hospitals").header("Authorization", "Bearer " +
				token(ISSUER, "medhead-api", List.of("OTHER"), Instant.now().plusSeconds(300), KEY)))
				.andExpect(status().isForbidden());
	}

	@Test
	void healthProbesRemainPublicAndOtherEndpointsAreDenied() throws Exception {
		mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
		mvc.perform(get("/actuator/info").header("Authorization", "Bearer " +
				token(ISSUER, "medhead-api", List.of("REGULATEUR"), Instant.now().plusSeconds(300), KEY)))
				.andExpect(status().isForbidden());
		mvc.perform(post("/logout").header("Authorization", "Bearer " +
				token(ISSUER, "medhead-api", List.of("REGULATEUR"), Instant.now().plusSeconds(300), KEY)))
				.andExpect(status().isForbidden());
	}

	@Test
	void internalErrorDispatchKeepsItsStatusInsteadOfBecomingAnAuthenticationRefusal() throws Exception {
		mvc.perform(get("/error").with(request -> {
			request.setDispatcherType(DispatcherType.ERROR);
			return request;
		})).andExpect(status().isInternalServerError());
	}

	private void assertRejected(String token) throws Exception {
		mvc.perform(post("/api/v1/hospitals/reservations").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
		assertThat(endpoints.reservations).isZero();
	}

	private static RSAKey signingKey() {
		try { return new RSAKeyGenerator(2048).keyID("test").generate(); }
		catch (Exception exception) { throw new IllegalStateException(exception); }
	}

	private static String token(String issuer, String audience, List<String> roles, Instant expiry, RSAKey key) {
		NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject("demo-user")
				.audience(List.of(audience)).issuedAt(Instant.now().minusSeconds(600)).expiresAt(expiry)
				.claim("roles", roles).build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
	}

	@Configuration
	@EnableWebMvc
	@EnableWebSecurity
	@Import(TestSecurityConfiguration.class)
	static class TestConfiguration {
		@Bean TestEndpoints endpoints() { return new TestEndpoints(); }
	}

	@Configuration
	static class TestSecurityConfiguration extends SecurityConfiguration {
		@Override
		@Bean
		JwtDecoder jwtDecoder(@Value(ISSUER) String issuer, @Value("unused") String jwksUri) {
			try {
				NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
				decoder.setJwtValidator(SecurityConfiguration.tokenValidator(issuer));
				return decoder;
			} catch (Exception exception) { throw new IllegalStateException(exception); }
		}
	}

	@RestController
	static class TestEndpoints {
		int reservations;
		@GetMapping({"/api/v1/hospitals", "/api/v1/specialties", "/actuator/health/readiness", "/actuator/info"})
		String get() { return "ok"; }
		@GetMapping("/error")
		@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
		String failure() { return "error"; }
		@PostMapping("/api/v1/hospitals/reservations")
		@ResponseStatus(HttpStatus.CREATED)
		String reserve() { reservations++; return "created"; }
	}
}
