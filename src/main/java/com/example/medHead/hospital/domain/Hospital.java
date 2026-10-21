package com.example.medhead.hospital.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Entity
@Table(
		name = "hospital",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_hospital_code",
				columnNames = "code"
		)
)
public class Hospital {

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

	@NotNull
	@JdbcTypeCode(SqlTypes.GEOGRAPHY)
	@Column(nullable = false, columnDefinition = "geography(Point,4326)")
	private Point location;

	@PositiveOrZero
	@Column(name = "total_beds", nullable = false)
	private int totalBeds;

	@PositiveOrZero
	@Column(name = "available_beds", nullable = false)
	private int availableBeds;

	@OneToMany(mappedBy = "hospital", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<HospitalSpecialty> specialtyLinks = new ArrayList<>();

	protected Hospital() {
		// Required by JPA.
	}

	public Hospital(String code, String name, Point location, int totalBeds, int availableBeds) {
		this.code = requireText(code, "Hospital code is required");
		this.name = requireName(name);
		this.location = requireLocation(location);
		setBedCapacity(totalBeds, availableBeds);
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

	public Point getLocation() {
		return location;
	}

	public int getTotalBeds() {
		return totalBeds;
	}

	public int getAvailableBeds() {
		return availableBeds;
	}

	public List<HospitalSpecialty> getSpecialtyLinks() {
		return Collections.unmodifiableList(specialtyLinks);
	}

	public void rename(String name) {
		this.name = requireName(name);
	}

	public void relocate(Point location) {
		this.location = requireLocation(location);
	}

	public void setBedCapacity(int totalBeds, int availableBeds) {
		if (totalBeds < 0) {
			throw new IllegalArgumentException("Total bed count cannot be negative");
		}
		if (availableBeds < 0 || availableBeds > totalBeds) {
			throw new IllegalArgumentException(
					"Available bed count must be between zero and the total bed count"
			);
		}
		this.totalBeds = totalBeds;
		this.availableBeds = availableBeds;
	}

	public void addSpecialty(Specialty specialty) {
		Objects.requireNonNull(specialty, "Specialty is required");
		boolean alreadyPresent = specialtyLinks.stream()
				.anyMatch(link -> link.refersTo(specialty));
		if (!alreadyPresent) {
			specialtyLinks.add(new HospitalSpecialty(this, specialty));
		}
	}

	private static String requireName(String name) {
		return requireText(name, "Hospital name is required");
	}

	private static String requireText(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(message);
		}
		return value.trim();
	}

	private static Point requireLocation(Point location) {
		Objects.requireNonNull(location, "Hospital location is required");
		if (location.isEmpty()) {
			throw new IllegalArgumentException("Hospital location cannot be empty");
		}
		if (location.getSRID() != 4326) {
			throw new IllegalArgumentException("Hospital location must use SRID 4326");
		}
		double longitude = location.getX();
		double latitude = location.getY();
		if (longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
			throw new IllegalArgumentException("Hospital coordinates are outside WGS84 bounds");
		}
		return location;
	}
}
