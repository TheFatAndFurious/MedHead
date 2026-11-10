package com.example.medhead.hospital.application;

import com.example.medhead.hospital.api.HospitalResponse;
import com.example.medhead.hospital.api.SpecialtyResponse;
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
	public List<HospitalResponse> listHospitals() {
		return hospitalRepository.findAll(Sort.by("name", "code")).stream()
				.map(hospital -> new HospitalResponse(
						hospital.getId(), hospital.getCode(), hospital.getName(),
						hospital.getLocation().getY(), hospital.getLocation().getX()
				))
				.toList();
	}

	@Transactional(readOnly = true)
	public List<SpecialtyResponse> listSpecialties() {
		return specialtyRepository.findAll(Sort.by("name", "code")).stream()
				.map(specialty -> new SpecialtyResponse(specialty.getCode(), specialty.getName()))
				.toList();
	}
}
