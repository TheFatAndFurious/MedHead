package com.example.medhead.hospital.domain;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

public class HospitalSpecialtyId implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private Long hospital;
	private Long specialty;

	public HospitalSpecialtyId() {
		// Required by JPA.
	}

	public HospitalSpecialtyId(Long hospital, Long specialty) {
		this.hospital = hospital;
		this.specialty = specialty;
	}

	public Long getHospital() {
		return hospital;
	}

	public Long getSpecialty() {
		return specialty;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof HospitalSpecialtyId that)) {
			return false;
		}
		return Objects.equals(hospital, that.hospital)
				&& Objects.equals(specialty, that.specialty);
	}

	@Override
	public int hashCode() {
		return Objects.hash(hospital, specialty);
	}
}
