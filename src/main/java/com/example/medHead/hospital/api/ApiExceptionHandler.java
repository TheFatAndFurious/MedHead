package com.example.medhead.hospital.api;

import com.example.medhead.hospital.application.NoAvailableHospitalException;
import com.example.medhead.hospital.application.UnknownSpecialtyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

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
