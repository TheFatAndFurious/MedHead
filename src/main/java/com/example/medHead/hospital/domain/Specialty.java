package com.example.medhead.hospital.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Entity
@Table(
		name = "specialty",
		uniqueConstraints = @UniqueConstraint(name = "uk_specialty_code", columnNames = "code")
)
public class Specialty {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@NotBlank
	@Size(max = 100)
	@Column(nullable = false, length = 100)
	private String code;

	@NotBlank
	@Size(max = 255)
	@Column(nullable = false, length = 255)
	private String name;

	@Size(max = 255)
	@Column(name = "specialty_group", length = 255)
	private String specialtyGroup;

	protected Specialty() {
		// Required by JPA.
	}

	public Specialty(String code, String name, String specialtyGroup) {
		this.code = requireText(code, "Specialty code is required");
		this.name = requireText(name, "Specialty name is required");
		this.specialtyGroup = normalizeOptionalText(specialtyGroup);
	}

	public Long getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public String getSpecialtyGroup() {
		return specialtyGroup;
	}

	private static String requireText(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(message);
		}
		return value.trim();
	}

	private static String normalizeOptionalText(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
