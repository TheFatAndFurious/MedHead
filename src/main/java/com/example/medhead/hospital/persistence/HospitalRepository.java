package com.example.medhead.hospital.persistence;

import com.example.medhead.hospital.domain.Hospital;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface HospitalRepository extends JpaRepository<Hospital, Long> {

	@Query(value = """
			select hospital.id as "hospitalId",
			       ST_Distance(
			           hospital.location,
			           ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography
			       ) as "distanceMeters"
			from hospital
			join hospital_specialty
			  on hospital_specialty.hospital_id = hospital.id
			join specialty
			  on specialty.id = hospital_specialty.specialty_id
			where specialty.code = :specialtyCode
			  and hospital.available_beds > 0
			order by hospital.location <->
			         ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
			         hospital.id
			limit 1
			""", nativeQuery = true)
	Optional<NearestHospitalProjection> findNearestAvailable(
			@Param("latitude") double latitude,
			@Param("longitude") double longitude,
			@Param("specialtyCode") String specialtyCode
	);

	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update Hospital hospital
			set hospital.availableBeds = hospital.availableBeds - 1
			where hospital.id = :hospitalId
			  and hospital.availableBeds > 0
			""")
	int reserveAvailableBed(@Param("hospitalId") long hospitalId);
}
