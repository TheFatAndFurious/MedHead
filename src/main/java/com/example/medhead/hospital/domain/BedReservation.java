package com.example.medhead.hospital.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bed_reservation")
public class BedReservation {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "hospital_id", nullable = false)
	private long hospitalId;

	@Column(name = "specialty_code", nullable = false, length = 100)
	private String specialtyCode;

	@Column(name = "reserved_at", nullable = false)
	private Instant reservedAt;

	protected BedReservation() {
		// Required by JPA.
	}

	public BedReservation(long hospitalId, String specialtyCode, Instant reservedAt) {
		this.hospitalId = hospitalId;
		this.specialtyCode = specialtyCode;
		this.reservedAt = reservedAt;
	}

	public UUID getId() {
		return id;
	}

	public long getHospitalId() {
		return hospitalId;
	}

	public String getSpecialtyCode() {
		return specialtyCode;
	}

	public Instant getReservedAt() {
		return reservedAt;
	}
}
