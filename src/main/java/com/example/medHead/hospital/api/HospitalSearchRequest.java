package com.example.medhead.hospital.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record HospitalSearchRequest(
		@NotNull(message = "Latitude is required")
		@DecimalMin(value = "-90.0", message = "Latitude must be greater than or equal to -90")
		@DecimalMax(value = "90.0", message = "Latitude must be less than or equal to 90")
		Double latitude,

		@NotNull(message = "Longitude is required")
		@DecimalMin(value = "-180.0", message = "Longitude must be greater than or equal to -180")
		@DecimalMax(value = "180.0", message = "Longitude must be less than or equal to 180")
		Double longitude,

		@NotBlank(message = "Specialty code is required")
		@Size(max = 100, message = "Specialty code must not exceed 100 characters")
		@Pattern(
				regexp = "^[A-Z0-9_]+$",
				message = "Specialty code must contain only uppercase letters, digits and underscores"
		)
		String specialtyCode
) {
}
