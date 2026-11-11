package com.example.medhead.hospital.application;

import com.example.medhead.hospital.persistence.HospitalRepository;
import com.example.medhead.hospital.persistence.SpecialtyRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class HospitalCatalogService {

	private final HospitalRepository hospitalRepository;
	private final SpecialtyRepository specialtyRepository;

	public HospitalCatalogService(HospitalRepository hospitalRepository, SpecialtyRepository specialtyRepository) {
		this.hospitalRepository = hospitalRepository;
		this.specialtyRepository = specialtyRepository;
	}

	@Transactional(readOnly = true)
	public List<HospitalSummary> listHospitals() {
		return hospitalRepository.findAll(Sort.by("name", "code")).stream()
				.map(hospital -> new HospitalSummary(
						hospital.getId(), hospital.getCode(), hospital.getName(),
						hospital.getLocation().getY(), hospital.getLocation().getX()
				))
				.toList();
	}

	@Transactional(readOnly = true)
	public List<SpecialtySummary> listSpecialties() {
		return specialtyRepository.findAll(Sort.by("name", "code")).stream()
				.map(specialty -> new SpecialtySummary(specialty.getCode(), specialty.getName()))
				.toList();
	}
}
