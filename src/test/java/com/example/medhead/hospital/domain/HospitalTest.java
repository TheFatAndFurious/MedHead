package com.example.medhead.hospital.domain;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class HospitalTest {

	private static final GeometryFactory GEOMETRY_FACTORY =
			new GeometryFactory(new PrecisionModel(), 4326);

	@Test
	void createsHospitalWithGlobalBedAvailability() {
		Hospital hospital = new Hospital(
				"DEMO-LON-001",
				"St Thomas' Hospital",
				point(-0.1187, 51.4982),
				20,
				3
		);

		assertThat(hospital.getCode()).isEqualTo("DEMO-LON-001");
		assertThat(hospital.getTotalBeds()).isEqualTo(20);
		assertThat(hospital.getAvailableBeds()).isEqualTo(3);
		assertThat(hospital.getLocation().getSRID()).isEqualTo(4326);
	}

	@Test
	void rejectsAvailabilityGreaterThanTotalCapacity() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new Hospital("INVALID", "Invalid Hospital", point(0, 51), 2, 3));
	}

	@Test
	void rejectsCoordinatesOutsideWgs84Bounds() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new Hospital("INVALID", "Invalid Hospital", point(181, 51), 2, 1));
	}

	@Test
	void rejectsBlankHospitalCode() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new Hospital(" ", "Invalid Hospital", point(0, 51), 2, 1));
	}

	@Test
	void doesNotAddSameSpecialtyInstanceTwice() {
		Hospital hospital = new Hospital(
				"DEMO-LON-001",
				"St Thomas' Hospital",
				point(-0.1187, 51.4982),
				20,
				3
		);
		Specialty cardiology = new Specialty("CARDIOLOGY", "Cardiology", "General medicine group");

		hospital.addSpecialty(cardiology);
		hospital.addSpecialty(cardiology);

		assertThat(hospital.getSpecialtyLinks()).hasSize(1);
	}

	private static Point point(double longitude, double latitude) {
		return GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
	}
}
