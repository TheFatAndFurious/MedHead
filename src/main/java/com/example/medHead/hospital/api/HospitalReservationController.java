package com.example.medhead.hospital.api;

import com.example.medhead.hospital.application.ReservationOutcome;
import com.example.medhead.hospital.application.ReserveNearestAvailableBedService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hospitals")
public class HospitalReservationController {

	static final String IDEMPOTENCY_KEY = "Idempotency-Key";
	static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

	private final ReserveNearestAvailableBedService reservationService;

	public HospitalReservationController(ReserveNearestAvailableBedService reservationService) {
		this.reservationService = reservationService;
	}

	/**
	 * The client generates one Idempotency-Key per reservation request and resends the same
	 * key when it retries. A repeated request returns the original reservation, with the
	 * Idempotent-Replayed header, instead of reserving another bed.
	 */
	@PostMapping(
			path = "/reservations",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE
	)
	public ResponseEntity<HospitalReservationResponse> reserve(
			@RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
			@Valid @RequestBody HospitalReservationRequest request,
			Principal principal
	) {
		// With a JWT, the principal name is the token subject (sub claim).
		ReservationOutcome outcome = reservationService.reserveNearest(
				request.toCommand(principal.getName(), idempotencyKey)
		);
		ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
		if (outcome.replayed()) {
			response.header(IDEMPOTENT_REPLAYED, "true");
		}
		return response.body(HospitalReservationResponse.from(outcome.reservation()));
	}
}
