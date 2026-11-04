package com.example.medhead.hospital.application;

import com.example.medhead.hospital.api.HospitalReservationResponse;
import com.example.medhead.hospital.api.HospitalReservationRequest;
import com.example.medhead.hospital.domain.BedReservation;
import com.example.medhead.hospital.domain.Hospital;
import com.example.medhead.hospital.persistence.BedReservationRepository;
import com.example.medhead.hospital.persistence.HospitalRepository;
import com.example.medhead.hospital.persistence.NearestHospitalProjection;
import com.example.medhead.hospital.persistence.SpecialtyRepository;
import com.example.medhead.outbox.OutboxEvent;
import com.example.medhead.outbox.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

@Service
public class ReserveNearestAvailableBedService {

	private final HospitalRepository hospitalRepository;
	private final SpecialtyRepository specialtyRepository;
	private final BedReservationRepository reservationRepository;
	private final OutboxEventRepository outboxRepository;
	private final JsonMapper jsonMapper;

	public ReserveNearestAvailableBedService(
			HospitalRepository hospitalRepository,
			SpecialtyRepository specialtyRepository,
			BedReservationRepository reservationRepository,
			OutboxEventRepository outboxRepository,
			JsonMapper jsonMapper
	) {
		this.hospitalRepository = hospitalRepository;
		this.specialtyRepository = specialtyRepository;
		this.reservationRepository = reservationRepository;
		this.outboxRepository = outboxRepository;
		this.jsonMapper = jsonMapper;
	}

	@Transactional
	public HospitalReservationResponse reserveNearest(HospitalReservationRequest request) {
		String specialtyCode = request.specialtyCode().trim();
		NearestHospitalProjection nearest = reserveAvailableHospital(request, specialtyCode);
		// The successful UPDATE holds the row lock until commit. Reload the actual
		// availability because another reservation may have committed since the search.
		Hospital hospital = hospitalRepository.findById(nearest.getHospitalId())
				.orElseThrow(() -> new IllegalStateException("Reserved hospital no longer exists"));

		Instant reservedAt = Instant.now();
		BedReservation reservation = reservationRepository.save(
				new BedReservation(hospital.getId(), specialtyCode, reservedAt)
		);
		BedReservedEvent event = new BedReservedEvent(
				UUID.randomUUID(), reservation.getId(), hospital.getId(),
				hospital.getCode(), specialtyCode, reservedAt
		);
		outboxRepository.save(new OutboxEvent(
				event.eventId(), BedReservedEvent.TYPE, reservation.getId(),
				reservedAt, jsonMapper.writeValueAsString(event)
		));

		return new HospitalReservationResponse(
				reservation.getId(), reservedAt, hospital.getId(),
				hospital.getCode(), hospital.getName(),
				hospital.getLocation().getY(), hospital.getLocation().getX(), hospital.getTotalBeds(),
				hospital.getAvailableBeds(), nearest.getDistanceMeters(), specialtyCode
		);
	}

	private NearestHospitalProjection reserveAvailableHospital(HospitalReservationRequest request, String specialtyCode) {
		while (true) {
			NearestHospitalProjection nearest = hospitalRepository.findNearestAvailable(
					request.latitude(), request.longitude(), specialtyCode
			).orElseThrow(() -> exceptionForMissingResult(specialtyCode));
			if (hospitalRepository.reserveAvailableBed(nearest.getHospitalId()) == 1) {
				return nearest;
			}
			// A concurrent request took the last bed; search again in this transaction.
		}
	}

	private RuntimeException exceptionForMissingResult(String specialtyCode) {
		if (!specialtyRepository.existsByCode(specialtyCode)) {
			return new UnknownSpecialtyException(specialtyCode);
		}
		return new NoAvailableHospitalException(specialtyCode);
	}
}
