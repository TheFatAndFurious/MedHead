package com.example.medhead.hospital.application;

import com.example.medhead.hospital.domain.BedReservation;
import com.example.medhead.hospital.domain.Hospital;
import com.example.medhead.hospital.persistence.BedReservationRepository;
import com.example.medhead.hospital.persistence.HospitalRepository;
import com.example.medhead.hospital.persistence.NearestHospitalProjection;
import com.example.medhead.hospital.persistence.ReservationRequestRepository;
import com.example.medhead.hospital.persistence.SpecialtyRepository;
import com.example.medhead.outbox.OutboxEvent;
import com.example.medhead.outbox.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ReserveNearestAvailableBedService {

	private final HospitalRepository hospitalRepository;
	private final SpecialtyRepository specialtyRepository;
	private final BedReservationRepository reservationRepository;
	private final OutboxEventRepository outboxRepository;
	private final ReservationRequestRepository requestRepository;
	private final JsonMapper jsonMapper;

	public ReserveNearestAvailableBedService(
			HospitalRepository hospitalRepository,
			SpecialtyRepository specialtyRepository,
			BedReservationRepository reservationRepository,
			OutboxEventRepository outboxRepository,
			ReservationRequestRepository requestRepository,
			JsonMapper jsonMapper
	) {
		this.hospitalRepository = hospitalRepository;
		this.specialtyRepository = specialtyRepository;
		this.reservationRepository = reservationRepository;
		this.outboxRepository = outboxRepository;
		this.requestRepository = requestRepository;
		this.jsonMapper = jsonMapper;
	}

	/**
	 * Reserves a bed once per {@code (requestedBy, idempotencyKey)}. Repeating an accepted
	 * request returns its original result without reserving another bed. A refused request
	 * (unknown specialty, no bed) stores nothing and can be retried with the same key.
	 */
	@Transactional
	public ReservationOutcome reserveNearest(ReserveBedCommand command) {
		String requestedBy = command.requestedBy();
		UUID idempotencyKey = command.idempotencyKey();
		String specialtyCode = command.specialtyCode();
		String fingerprint = fingerprint(command.latitude(), command.longitude(), specialtyCode);

		// A concurrent request with the same key waits here until this transaction ends.
		requestRepository.lock(requestedBy, idempotencyKey);
		var previous = requestRepository.find(requestedBy, idempotencyKey);
		if (previous.isPresent()) {
			if (!previous.get().requestFingerprint().equals(fingerprint)) {
				throw new IdempotencyKeyReusedException(idempotencyKey);
			}
			return ReservationOutcome.replayed(
					jsonMapper.readValue(previous.get().response(), ReservedBed.class)
			);
		}

		NearestHospitalProjection nearest = reserveAvailableHospital(command);
		// The successful UPDATE holds the row lock until commit. Reload the actual
		// availability because another reservation may have committed since the search.
		Hospital hospital = hospitalRepository.findById(nearest.getHospitalId())
				.orElseThrow(() -> new IllegalStateException("Reserved hospital no longer exists"));

		Instant reservedAt = Instant.now();
		// Flushed now: the request row inserted below references this reservation.
		BedReservation reservation = reservationRepository.saveAndFlush(
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

		ReservedBed reservedBed = new ReservedBed(
				reservation.getId(), reservedAt, hospital.getId(),
				hospital.getCode(), hospital.getName(),
				hospital.getLocation().getY(), hospital.getLocation().getX(), hospital.getTotalBeds(),
				hospital.getAvailableBeds(), nearest.getDistanceMeters(), specialtyCode
		);
		// Stored as JSON so a repeated request returns exactly the same result.
		requestRepository.insert(
				requestedBy, idempotencyKey, fingerprint, reservation.getId(),
				jsonMapper.writeValueAsString(reservedBed), reservedAt
		);
		return ReservationOutcome.created(reservedBed);
	}

	private NearestHospitalProjection reserveAvailableHospital(ReserveBedCommand command) {
		String specialtyCode = command.specialtyCode();
		while (true) {
			NearestHospitalProjection nearest = hospitalRepository.findNearestAvailable(
					command.latitude(), command.longitude(), specialtyCode
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

	/** Hash of the request, so the stored row does not keep the emergency coordinates in clear. */
	private static String fingerprint(double latitude, double longitude, String specialtyCode) {
		String canonical = latitude + "|" + longitude + "|" + specialtyCode;
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}
}
