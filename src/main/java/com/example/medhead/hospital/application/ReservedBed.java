package com.example.medhead.hospital.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Bed reserved for a request: the chosen hospital, its distance from the emergency
 * and the number of beds still available after this reservation.
 */
public record ReservedBed(
		UUID reservationId,
		Instant reservedAt,
		long hospitalId,
		String hospitalCode,
		String hospitalName,
		double latitude,
		double longitude,
		int totalBeds,
		int availableBeds,
		double distanceMeters,
		String specialtyCode
) {
}
