package com.example.medhead.hospital.api;

public record HospitalSearchResponse(
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
