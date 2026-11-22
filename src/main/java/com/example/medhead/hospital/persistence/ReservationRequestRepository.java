package com.example.medhead.hospital.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Accepted reservation requests, keyed by subject and Idempotency-Key. Plain JDBC keeps
 * the write an INSERT: an existing key makes it fail instead of being overwritten.
 */
@Repository
public class ReservationRequestRepository {

	private final JdbcClient jdbc;

	public ReservationRequestRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Makes requests sharing a key wait for each other until the current transaction ends,
	 * so a retry sent while the first attempt is still running sees its committed result.
	 */
	public void lock(String requestedBy, UUID idempotencyKey) {
		jdbc.sql("select 1 from pg_advisory_xact_lock(hashtextextended(:scope, 0))")
				.param("scope", requestedBy + "\n" + idempotencyKey)
				.query(Integer.class)
				.single();
	}

	public Optional<StoredReservationRequest> find(String requestedBy, UUID idempotencyKey) {
		return jdbc.sql("""
				select request_fingerprint, response::text as response
				from reservation_request
				where requested_by = :requestedBy and idempotency_key = :idempotencyKey
				""")
				.param("requestedBy", requestedBy)
				.param("idempotencyKey", idempotencyKey)
				.query((row, rowNumber) -> new StoredReservationRequest(
						row.getString("request_fingerprint"), row.getString("response")
				))
				.optional();
	}

	public void insert(
			String requestedBy,
			UUID idempotencyKey,
			String requestFingerprint,
			UUID reservationId,
			String response,
			Instant createdAt
	) {
		jdbc.sql("""
				insert into reservation_request (
				    requested_by, idempotency_key, request_fingerprint, reservation_id, response, created_at
				)
				values (:requestedBy, :idempotencyKey, :requestFingerprint, :reservationId, cast(:response as jsonb), :createdAt)
				""")
				.param("requestedBy", requestedBy)
				.param("idempotencyKey", idempotencyKey)
				.param("requestFingerprint", requestFingerprint)
				.param("reservationId", reservationId)
				.param("response", response)
				.param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
				.update();
	}

	public record StoredReservationRequest(String requestFingerprint, String response) {
	}
}
