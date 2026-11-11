package com.example.medhead.hospital.application;

/**
 * Result of a reservation request. {@code replayed} is true when the same request had
 * already been accepted: the original reservation is returned and no bed is consumed.
 */
public record ReservationOutcome(ReservedBed reservation, boolean replayed) {

	public static ReservationOutcome created(ReservedBed reservation) {
		return new ReservationOutcome(reservation, false);
	}

	public static ReservationOutcome replayed(ReservedBed reservation) {
		return new ReservationOutcome(reservation, true);
	}
}
