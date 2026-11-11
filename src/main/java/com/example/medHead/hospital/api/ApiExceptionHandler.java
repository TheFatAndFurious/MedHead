package com.example.medhead.hospital.api;

import com.example.medhead.hospital.application.IdempotencyKeyReusedException;
import com.example.medhead.hospital.application.NoAvailableHospitalException;
import com.example.medhead.hospital.application.UnknownSpecialtyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(MissingRequestHeaderException.class)
	public ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException exception) {
		return invalidRequest(exception.getHeaderName(), exception.getHeaderName() + " header is required");
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
		String name = exception.getName();
		if (HospitalReservationController.IDEMPOTENCY_KEY.equals(name)) {
			return invalidRequest(name, name + " must be a UUID");
		}
		return invalidRequest(name, name + " has an invalid format");
	}

	@ExceptionHandler(IdempotencyKeyReusedException.class)
	public ResponseEntity<ProblemDetail> handleIdempotencyKeyReused(IdempotencyKeyReusedException exception) {
		ProblemDetail problem = problem(
				HttpStatus.UNPROCESSABLE_CONTENT,
				"Idempotency key reused",
				"This Idempotency-Key was already used for a different reservation request",
				"urn:problem:idempotency-key-reused"
		);
		problem.setProperty("idempotencyKey", exception.getIdempotencyKey());
		return ResponseEntity.unprocessableContent().body(problem);
	}

	private static ResponseEntity<ProblemDetail> invalidRequest(String field, String message) {
		ProblemDetail problem = problem(
				HttpStatus.BAD_REQUEST,
				"Invalid request",
				"One or more request fields are invalid",
				"urn:problem:invalid-request"
		);
		problem.setProperty("errors", Map.of(field, message));
		return ResponseEntity.badRequest().body(problem);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception) {
		Map<String, String> errors = new LinkedHashMap<>();
		exception.getBindingResult().getFieldErrors().forEach(error ->
				errors.putIfAbsent(error.getField(), error.getDefaultMessage())
		);

		ProblemDetail problem = problem(
				HttpStatus.BAD_REQUEST,
				"Invalid request",
				"One or more request fields are invalid",
				"urn:problem:invalid-request"
		);
		problem.setProperty("errors", errors);
		return ResponseEntity.badRequest().body(problem);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ProblemDetail> handleUnreadableMessage() {
		ProblemDetail problem = problem(
				HttpStatus.BAD_REQUEST,
				"Invalid JSON",
				"The request body is missing or malformed",
				"urn:problem:invalid-json"
		);
		return ResponseEntity.badRequest().body(problem);
	}

	@ExceptionHandler(UnknownSpecialtyException.class)
	public ResponseEntity<ProblemDetail> handleUnknownSpecialty(UnknownSpecialtyException exception) {
		ProblemDetail problem = problem(
				HttpStatus.BAD_REQUEST,
				"Unknown specialty",
				exception.getMessage(),
				"urn:problem:unknown-specialty"
		);
		problem.setProperty("specialtyCode", exception.getSpecialtyCode());
		return ResponseEntity.badRequest().body(problem);
	}

	@ExceptionHandler(NoAvailableHospitalException.class)
	public ResponseEntity<ProblemDetail> handleNoAvailableHospital(
			NoAvailableHospitalException exception
	) {
		ProblemDetail problem = problem(
				HttpStatus.NOT_FOUND,
				"No available hospital",
				exception.getMessage(),
				"urn:problem:no-available-hospital"
		);
		problem.setProperty("specialtyCode", exception.getSpecialtyCode());
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
	}

	private static ProblemDetail problem(
			HttpStatus status,
			String title,
			String detail,
			String type
	) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		problem.setType(URI.create(type));
		return problem;
	}
}
