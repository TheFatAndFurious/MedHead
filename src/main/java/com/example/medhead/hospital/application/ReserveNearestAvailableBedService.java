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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class ReserveNearestAvailableBedService {

	private final HospitalRepository hospitalRepository;
	private final SpecialtyRepository specialtyRepository;
	private final BedReservationRepository reservationRepository;
	private final OutboxEventRepository outboxRepository;
	private final ReservationRequestRepository requestRepository;
	private final JsonMapper jsonMapper;
	private final OsrmClient osrm;
	private final TransactionTemplate transaction;

	public ReserveNearestAvailableBedService(
			HospitalRepository hospitalRepository,
			SpecialtyRepository specialtyRepository,
			BedReservationRepository reservationRepository,
			OutboxEventRepository outboxRepository,
			ReservationRequestRepository requestRepository,
			JsonMapper jsonMapper,
			OsrmClient osrm,
			TransactionTemplate transaction
	) {
		this.hospitalRepository = hospitalRepository;
		this.specialtyRepository = specialtyRepository;
		this.reservationRepository = reservationRepository;
		this.outboxRepository = outboxRepository;
		this.requestRepository = requestRepository;
		this.jsonMapper = jsonMapper;
		this.osrm = osrm;
		this.transaction = transaction;
	}

	/**
	 * Reserves a bed once per {@code (requestedBy, idempotencyKey)}. Repeating an accepted
	 * request returns its original result without reserving another bed. A refused request
	 * (unknown specialty, no bed) stores nothing and can be retried with the same key.
	 */
	@Transactional
	public ReservationOutcome reserveNearest(ReserveBedCommand command) {
		return reserveOnce(command, fingerprint(command, false), () -> reserveClosestHospital(command));
	}

	public ReservationOutcome reserveByRoad(ReserveBedCommand command) {
		String fingerprint = fingerprint(command, true);
		var replay = findReplay(command, fingerprint);
		if (replay.isPresent()) {
			return replay.get();
		}

		var hospitals = hospitalRepository.findAvailableBySpecialty(command.specialtyCode());
		var routes = osrm.routes(command.latitude(), command.longitude(), hospitals).stream()
				.sorted(Comparator.comparingDouble(OsrmClient.Route::durationSeconds)
						.thenComparingLong(OsrmClient.Route::hospitalId))
				.toList();

		// Do not hold a transaction or its locks while waiting for OSRM.
		return transaction.execute(status -> reserveOnce(command, fingerprint, () -> {
			if (hospitals.isEmpty()) {
				throw exceptionForMissingResult(command.specialtyCode());
			}
			return reserveRoadHospital(command, routes);
		}));
	}

	private ReservationOutcome reserveOnce(
			ReserveBedCommand command, String fingerprint, Supplier<HospitalChoice> reserveHospital
	) {
		String requestedBy = command.requestedBy();
		UUID idempotencyKey = command.idempotencyKey();
		String specialtyCode = command.specialtyCode();

		// A concurrent request with the same key waits here until this transaction ends.
		requestRepository.lock(requestedBy, idempotencyKey);
		var replay = findReplay(command, fingerprint);
		if (replay.isPresent()) {
			return replay.get();
		}

		HospitalChoice nearest = reserveHospital.get();
		// The successful UPDATE holds the row lock until commit. Reload the actual
		// availability because another reservation may have committed since the search.
		Hospital hospital = hospitalRepository.findById(nearest.hospitalId())
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
				hospital.getAvailableBeds(), nearest.distanceMeters(), specialtyCode, nearest.durationSeconds()
		);
		// Stored as JSON so a repeated request returns exactly the same result.
		requestRepository.insert(
				requestedBy, idempotencyKey, fingerprint, reservation.getId(),
				jsonMapper.writeValueAsString(reservedBed), reservedAt
		);
		return ReservationOutcome.created(reservedBed);
	}

	private Optional<ReservationOutcome> findReplay(ReserveBedCommand command, String fingerprint) {
		return requestRepository.find(command.requestedBy(), command.idempotencyKey()).map(previous -> {
			if (!previous.requestFingerprint().equals(fingerprint)) {
				throw new IdempotencyKeyReusedException(command.idempotencyKey());
			}
			return ReservationOutcome.replayed(jsonMapper.readValue(previous.response(), ReservedBed.class));
		});
	}

	private HospitalChoice reserveClosestHospital(ReserveBedCommand command) {
		String specialtyCode = command.specialtyCode();
		while (true) {
			NearestHospitalProjection nearest = hospitalRepository.findNearestAvailable(
					command.latitude(), command.longitude(), specialtyCode
			).orElseThrow(() -> exceptionForMissingResult(specialtyCode));
			if (hospitalRepository.reserveAvailableBed(nearest.getHospitalId()) == 1) {
				return new HospitalChoice(nearest.getHospitalId(), nearest.getDistanceMeters(), null);
			}
			// A concurrent request took the last bed; search again in this transaction.
		}
	}

	private HospitalChoice reserveRoadHospital(ReserveBedCommand command, List<OsrmClient.Route> routes) {
		if (routes.isEmpty()) {
			throw new NoRoadRouteException();
		}
		for (var route : routes) {
			if (hospitalRepository.reserveAvailableBed(route.hospitalId()) == 1) {
				return new HospitalChoice(route.hospitalId(), route.distanceMeters(), route.durationSeconds());
			}
		}
		throw new NoAvailableHospitalException(command.specialtyCode());
	}

	private RuntimeException exceptionForMissingResult(String specialtyCode) {
		if (!specialtyRepository.existsByCode(specialtyCode)) {
			return new UnknownSpecialtyException(specialtyCode);
		}
		return new NoAvailableHospitalException(specialtyCode);
	}

	/** Hash of the request, so the stored row does not keep the emergency coordinates in clear. */
	private static String fingerprint(ReserveBedCommand command, boolean byRoad) {
		String canonical = command.latitude() + "|" + command.longitude() + "|" + command.specialtyCode();
		if (byRoad) {
			canonical += "|road";
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	private record HospitalChoice(long hospitalId, double distanceMeters, Double durationSeconds) {
	}
}
