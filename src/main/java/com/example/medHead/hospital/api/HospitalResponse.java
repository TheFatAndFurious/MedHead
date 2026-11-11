package com.example.medhead.hospital.api;

import com.example.medhead.hospital.application.HospitalSummary;

public record HospitalResponse(
		long id,
		String code,
		String name,
		double latitude,
		double longitude
) {

	static HospitalResponse from(HospitalSummary hospital) {
		return new HospitalResponse(
				hospital.id(), hospital.code(), hospital.name(), hospital.latitude(), hospital.longitude()
		);
	}
}
