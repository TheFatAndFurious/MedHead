package com.example.medhead.hospital.application;

import java.util.UUID;

public class IdempotencyKeyReusedException extends RuntimeException {

	private final UUID idempotencyKey;

	public IdempotencyKeyReusedException(UUID idempotencyKey) {
		super("Idempotency key " + idempotencyKey + " was already used for a different reservation request");
		this.idempotencyKey = idempotencyKey;
	}

	public UUID getIdempotencyKey() {
		return idempotencyKey;
	}
}
