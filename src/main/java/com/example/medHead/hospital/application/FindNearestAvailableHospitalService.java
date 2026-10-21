package com.example.medhead.hospital.application;

import com.example.medhead.hospital.api.HospitalSearchRequest;
import com.example.medhead.hospital.api.HospitalSearchResponse;
import com.example.medhead.hospital.persistence.HospitalRepository;
import com.example.medhead.hospital.persistence.NearestHospitalProjection;
import com.example.medhead.hospital.persistence.SpecialtyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FindNearestAvailableHospitalService {

	private final HospitalRepository hospitalRepository;
	private final SpecialtyRepository specialtyRepository;

	public FindNearestAvailableHospitalService(
			HospitalRepository hospitalRepository,
			SpecialtyRepository specialtyRepository
	) {
		this.hospitalRepository = hospitalRepository;
		this.specialtyRepository = specialtyRepository;
	}

	@Transactional(readOnly = true)
	public HospitalSearchResponse findNearest(HospitalSearchRequest request) {
		String specialtyCode = request.specialtyCode().trim();

		return hospitalRepository.findNearestAvailable(
				request.latitude(),
				request.longitude(),
				specialtyCode
		).map(result -> toResponse(result, specialtyCode))
				.orElseThrow(() -> exceptionForMissingResult(specialtyCode));
	}

	private RuntimeException exceptionForMissingResult(String specialtyCode) {
		if (!specialtyRepository.existsByCode(specialtyCode)) {
			return new UnknownSpecialtyException(specialtyCode);
		}
		return new NoAvailableHospitalException(specialtyCode);
	}

	private static HospitalSearchResponse toResponse(
			NearestHospitalProjection result,
			String specialtyCode
	) {
		return new HospitalSearchResponse(
				result.getHospitalId(),
				result.getHospitalCode(),
				result.getHospitalName(),
				result.getLatitude(),
				result.getLongitude(),
				result.getTotalBeds(),
				result.getAvailableBeds(),
				result.getDistanceMeters(),
				specialtyCode
		);
	}
}
