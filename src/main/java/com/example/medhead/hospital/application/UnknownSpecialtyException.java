package com.example.medhead.hospital.application;

public class UnknownSpecialtyException extends RuntimeException {

	private final String specialtyCode;

	public UnknownSpecialtyException(String specialtyCode) {
		super("Unknown specialty code: " + specialtyCode);
		this.specialtyCode = specialtyCode;
	}

	public String getSpecialtyCode() {
		return specialtyCode;
	}
}
