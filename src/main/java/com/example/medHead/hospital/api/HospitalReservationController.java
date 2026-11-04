package com.example.medhead.hospital.api;

import com.example.medhead.hospital.application.ReserveNearestAvailableBedService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hospitals")
public class HospitalReservationController {

	private final ReserveNearestAvailableBedService reservationService;

	public HospitalReservationController(ReserveNearestAvailableBedService reservationService) {
		this.reservationService = reservationService;
	}

	@PostMapping(
			path = "/reservations",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE
	)
	@ResponseStatus(HttpStatus.CREATED)
	public HospitalReservationResponse reserve(@Valid @RequestBody HospitalReservationRequest request) {
		return reservationService.reserveNearest(request);
	}
}
