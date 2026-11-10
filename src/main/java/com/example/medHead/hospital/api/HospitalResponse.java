package com.example.medhead.hospital.api;

public record HospitalResponse(
		long id,
		String code,
		String name,
		double latitude,
		double longitude
) {
}
