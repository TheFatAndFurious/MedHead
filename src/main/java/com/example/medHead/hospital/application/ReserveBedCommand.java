package com.example.medhead.hospital.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Request to reserve the nearest available bed, independent of the HTTP contract.
 * The API checks the input format; this record only refuses missing values.
 *
 * @param requestedBy    authenticated subject; scopes the idempotency key
 * @param idempotencyKey identifies this request when the client repeats it
 */
public record ReserveBedCommand(
		String requestedBy,
		UUID idempotencyKey,
		double latitude,
		double longitude,
		String specialtyCode
) {

	public ReserveBedCommand {
		requestedBy = requireText(requestedBy, "Requester is required");
		Objects.requireNonNull(idempotencyKey, "Idempotency key is required");
		specialtyCode = requireText(specialtyCode, "Specialty code is required");
	}

	private static String requireText(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(message);
		}
		return value.trim();
	}
}
