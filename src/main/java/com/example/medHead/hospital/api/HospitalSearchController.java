package com.example.medhead.hospital.api;

import com.example.medhead.hospital.application.FindNearestAvailableHospitalService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hospitals")
public class HospitalSearchController {

	private final FindNearestAvailableHospitalService searchService;

	public HospitalSearchController(FindNearestAvailableHospitalService searchService) {
		this.searchService = searchService;
	}

	@PostMapping(
			path = "/search",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE
	)
	public HospitalSearchResponse search(@Valid @RequestBody HospitalSearchRequest request) {
		return searchService.findNearest(request);
	}
}
