package com.example.medhead.hospital.persistence;

import com.example.medhead.hospital.domain.HospitalSpecialty;
import com.example.medhead.hospital.domain.HospitalSpecialtyId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HospitalSpecialtyRepository
		extends JpaRepository<HospitalSpecialty, HospitalSpecialtyId> {
}
