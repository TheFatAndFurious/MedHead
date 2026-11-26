package com.example.medhead.hospital.application;

import com.example.medhead.hospital.domain.Hospital;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

@Component
public class OsrmClient {

	// The default OSRM table limit is 100 coordinates, including the origin.
	private static final int MAX_DESTINATIONS = 99;
	private static final int MAX_SNAP_DISTANCE_METERS = 1_000;

	private final RestClient client;
	private final String tableUrl;

	public OsrmClient(@Value("${medhead.osrm.url}") String baseUrl) {
		var requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofMillis(250));
		requestFactory.setReadTimeout(Duration.ofSeconds(1));
		client = RestClient.builder().requestFactory(requestFactory).build();
		tableUrl = baseUrl.replaceFirst("/+$", "") + "/table/v1/driving/";
	}

	List<Route> routes(double latitude, double longitude, List<Hospital> hospitals) {
		List<Route> routes = new ArrayList<>();
		for (int offset = 0; offset < hospitals.size(); offset += MAX_DESTINATIONS) {
			var batch = hospitals.subList(offset, Math.min(offset + MAX_DESTINATIONS, hospitals.size()));
			routes.addAll(requestRoutes(latitude, longitude, batch));
		}
		return routes;
	}

	private List<Route> requestRoutes(double latitude, double longitude, List<Hospital> hospitals) {
		var coordinates = new StringJoiner(";").add(longitude + "," + latitude);
		var destinations = new StringJoiner(";");
		int index = 1;
		for (Hospital hospital : hospitals) {
			var point = hospital.getLocation();
			coordinates.add(point.getX() + "," + point.getY());
			destinations.add(Integer.toString(index++));
		}

		// Bound snapping for the incident so a point outside the map cannot snap to its edge.
		var uri = URI.create(tableUrl + coordinates + "?sources=0&destinations=" + destinations
				+ "&annotations=duration,distance&generate_hints=false&radiuses="
				+ MAX_SNAP_DISTANCE_METERS + ";".repeat(hospitals.size()));
		try {
			return client.get().uri(uri).exchange((request, response) -> {
				var status = response.getStatusCode();
				if (!status.is2xxSuccessful() && status.value() != 400) {
					throw new RoadRoutingUnavailableException();
				}
				Table table = response.bodyTo(Table.class);
				if (table != null && ("NoSegment".equals(table.code()) || "NoTable".equals(table.code()))) {
					return List.of();
				}
				if (!status.is2xxSuccessful() || table == null || !"Ok".equals(table.code())
						|| !isRow(table.distances(), hospitals.size()) || !isRow(table.durations(), hospitals.size())
						|| table.destinations() == null || table.destinations().length != hospitals.size()) {
					throw new RoadRoutingUnavailableException();
				}

				List<Route> routes = new ArrayList<>();
				for (int column = 0; column < hospitals.size(); column++) {
					Double distance = table.distances()[0][column];
					Double duration = table.durations()[0][column];
					Waypoint destination = table.destinations()[column];
					if (destination == null || destination.distance() == null) {
						throw new RoadRoutingUnavailableException();
					}
					if (distance != null && duration != null && destination.distance() <= MAX_SNAP_DISTANCE_METERS) {
						routes.add(new Route(hospitals.get(column).getId(), distance, duration));
					}
				}
				return routes;
			});
		} catch (RestClientException exception) {
			throw new RoadRoutingUnavailableException();
		}
	}

	private static boolean isRow(Double[][] matrix, int columns) {
		return matrix != null && matrix.length == 1 && matrix[0] != null && matrix[0].length == columns;
	}

	record Route(long hospitalId, double distanceMeters, double durationSeconds) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Table(String code, Double[][] distances, Double[][] durations, Waypoint[] destinations) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Waypoint(Double distance) {
	}
}
