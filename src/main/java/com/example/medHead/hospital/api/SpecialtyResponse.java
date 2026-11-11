package com.example.medhead.hospital.api;

import com.example.medhead.hospital.application.SpecialtySummary;

public record SpecialtyResponse(String code, String name) {

	static SpecialtyResponse from(SpecialtySummary specialty) {
		return new SpecialtyResponse(specialty.code(), specialty.name());
	}
}
