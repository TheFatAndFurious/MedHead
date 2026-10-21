package com.example.medhead.hospital.application;

import com.example.medhead.hospital.api.HospitalSearchRequest;
import com.example.medhead.hospital.api.HospitalSearchResponse;
import com.example.medhead.hospital.persistence.HospitalRepository;
import com.example.medhead.hospital.persistence.NearestHospitalProjection;
import com.example.medhead.hospital.persistence.SpecialtyRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class FindNearestAvailableHospitalServiceTest {

	@Test
	void returnsNearestHospitalWithoutAnAdditionalSpecialtyQuery() {
		AtomicInteger specialtyQueries = new AtomicInteger();
		HospitalRepository hospitalRepository = hospitalRepository(Optional.of(projection()));
		SpecialtyRepository specialtyRepository = specialtyRepository(true, specialtyQueries);
		FindNearestAvailableHospitalService service = new FindNearestAvailableHospitalService(
				hospitalRepository,
				specialtyRepository
		);

		HospitalSearchResponse response = service.findNearest(
				new HospitalSearchRequest(51.5074, -0.1278, "CARDIOLOGY")
		);

		assertThat(response.hospitalCode()).isEqualTo("DEMO-LON-001");
		assertThat(response.distanceMeters()).isEqualTo(42.5);
		assertThat(response.specialtyCode()).isEqualTo("CARDIOLOGY");
		assertThat(specialtyQueries).hasValue(0);
	}

	@Test
	void reportsAnUnknownSpecialtyOnlyAfterTheSearchHasNoResult() {
		AtomicInteger specialtyQueries = new AtomicInteger();
		FindNearestAvailableHospitalService service = new FindNearestAvailableHospitalService(
				hospitalRepository(Optional.empty()),
				specialtyRepository(false, specialtyQueries)
		);

		assertThatExceptionOfType(UnknownSpecialtyException.class)
				.isThrownBy(() -> service.findNearest(
						new HospitalSearchRequest(51.5074, -0.1278, "UNKNOWN")
				));
		assertThat(specialtyQueries).hasValue(1);
	}

	@Test
	void reportsNoAvailabilityWhenTheSpecialtyExists() {
		AtomicInteger specialtyQueries = new AtomicInteger();
		FindNearestAvailableHospitalService service = new FindNearestAvailableHospitalService(
				hospitalRepository(Optional.empty()),
				specialtyRepository(true, specialtyQueries)
		);

		assertThatExceptionOfType(NoAvailableHospitalException.class)
				.isThrownBy(() -> service.findNearest(
						new HospitalSearchRequest(51.5074, -0.1278, "CARDIOLOGY")
				));
		assertThat(specialtyQueries).hasValue(1);
	}

	private static HospitalRepository hospitalRepository(
			Optional<NearestHospitalProjection> result
	) {
		return proxy(HospitalRepository.class, (proxy, method, arguments) -> {
			if (method.getName().equals("findNearestAvailable")) {
				return result;
			}
			throw new UnsupportedOperationException("Unexpected repository call: " + method.getName());
		});
	}

	private static SpecialtyRepository specialtyRepository(
			boolean specialtyExists,
			AtomicInteger queryCount
	) {
		return proxy(SpecialtyRepository.class, (proxy, method, arguments) -> {
			if (method.getName().equals("existsByCode")) {
				queryCount.incrementAndGet();
				return specialtyExists;
			}
			throw new UnsupportedOperationException("Unexpected repository call: " + method.getName());
		});
	}

	private static NearestHospitalProjection projection() {
		return new NearestHospitalProjection() {
			@Override
			public Long getHospitalId() {
				return 1L;
			}

			@Override
			public String getHospitalCode() {
				return "DEMO-LON-001";
			}

			@Override
			public String getHospitalName() {
				return "MedHead Westminster";
			}

			@Override
			public double getLatitude() {
				return 51.5072;
			}

			@Override
			public double getLongitude() {
				return -0.1276;
			}

			@Override
			public int getTotalBeds() {
				return 350;
			}

			@Override
			public int getAvailableBeds() {
				return 17;
			}

			@Override
			public double getDistanceMeters() {
				return 42.5;
			}
		};
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> repositoryType, java.lang.reflect.InvocationHandler handler) {
		return (T) Proxy.newProxyInstance(
				repositoryType.getClassLoader(),
				new Class<?>[]{repositoryType},
				handler
		);
	}
}
