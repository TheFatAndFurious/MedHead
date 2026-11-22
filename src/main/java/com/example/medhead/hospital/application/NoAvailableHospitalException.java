package com.example.medhead.hospital.application;

public class NoAvailableHospitalException extends RuntimeException {

	private final String specialtyCode;

	public NoAvailableHospitalException(String specialtyCode) {
		super("No hospital with an available bed supports specialty: " + specialtyCode);
		this.specialtyCode = specialtyCode;
	}

	public String getSpecialtyCode() {
		return specialtyCode;
	}
}
