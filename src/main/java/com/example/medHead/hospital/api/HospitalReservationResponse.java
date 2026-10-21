package com.example.medhead.hospital.api;

import java.time.Instant;
import java.util.UUID;

public record HospitalReservationResponse(
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
