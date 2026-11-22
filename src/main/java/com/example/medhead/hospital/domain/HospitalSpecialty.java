package com.example.medhead.hospital.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

@Entity
@Table(name = "hospital_specialty")
@IdClass(HospitalSpecialtyId.class)
public class HospitalSpecialty {

	@Id
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "hospital_id", nullable = false)
	private Hospital hospital;

	@Id
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "specialty_id", nullable = false)
	private Specialty specialty;

	protected HospitalSpecialty() {
		// Required by JPA.
	}

	public HospitalSpecialty(Hospital hospital, Specialty specialty) {
		this.hospital = Objects.requireNonNull(hospital, "Hospital is required");
		this.specialty = Objects.requireNonNull(specialty, "Specialty is required");
	}

	public Hospital getHospital() {
		return hospital;
	}

	public Specialty getSpecialty() {
		return specialty;
	}

	boolean refersTo(Specialty candidate) {
		if (specialty == candidate) {
			return true;
		}
		return specialty.getId() != null
				&& candidate.getId() != null
				&& specialty.getId().equals(candidate.getId());
	}
}
