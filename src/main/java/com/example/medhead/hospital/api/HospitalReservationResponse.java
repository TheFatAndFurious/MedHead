package com.example.medhead.hospital.api;

import com.example.medhead.hospital.application.ReservedBed;

import com.fasterxml.jackson.annotation.JsonInclude;

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
		String specialtyCode,
		@JsonInclude(JsonInclude.Include.NON_NULL) Double travelDurationSeconds
) {

	static HospitalReservationResponse from(ReservedBed bed) {
		return new HospitalReservationResponse(
				bed.reservationId(), bed.reservedAt(), bed.hospitalId(), bed.hospitalCode(),
				bed.hospitalName(), bed.latitude(), bed.longitude(), bed.totalBeds(),
				bed.availableBeds(), bed.distanceMeters(), bed.specialtyCode(), bed.travelDurationSeconds()
		);
	}
}
