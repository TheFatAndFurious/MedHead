package com.example.medhead.hospital.application;

import java.time.Instant;
import java.util.UUID;

public record BedReservedEvent(
		UUID eventId,
		UUID reservationId,
		long hospitalId,
		String hospitalCode,
		String specialtyCode,
		Instant occurredAt
) {
	public static final String TYPE = "BedReserved";
}
