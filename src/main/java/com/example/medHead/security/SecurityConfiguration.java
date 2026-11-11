package com.example.medhead.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

@Configuration
public class SecurityConfiguration {

	@Bean
	JwtDecoder jwtDecoder(
			@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
			@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwksUri
	) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
		decoder.setJwtValidator(tokenValidator(issuer));
		return decoder;
	}

	static OAuth2TokenValidator<Jwt> tokenValidator(String issuer) {
		return new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer(issuer),
				new JwtClaimValidator<List<String>>("aud", audience -> audience != null && audience.contains("medhead-api"))
		);
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
		roles.setAuthoritiesClaimName("roles");
		roles.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter authentication = new JwtAuthenticationConverter();
		authentication.setJwtGrantedAuthoritiesConverter(roles);

		return http
				// This API accepts Bearer tokens only. Browser cookies terminate at SvelteKit,
				// whose POST routes retain origin/CSRF protection.
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.requestCache(cache -> cache.disable())
				.authorizeHttpRequests(authorize -> authorize
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness",
								"/actuator/health/readiness").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/hospitals", "/api/v1/specialties")
						.hasAnyRole("LECTEUR", "REGULATEUR")
						.requestMatchers(HttpMethod.POST, "/api/v1/hospitals/reservations").hasRole("REGULATEUR")
						.requestMatchers(HttpMethod.GET, "/actuator/metrics", "/actuator/metrics/**")
						.hasRole("REGULATEUR")
						.anyRequest().denyAll())
				.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(authentication)))
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.logout(logout -> logout.disable())
				.build();
	}
}
