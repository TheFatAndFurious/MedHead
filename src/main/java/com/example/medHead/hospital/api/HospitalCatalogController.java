package com.example.medhead.hospital.api;

import com.example.medhead.hospital.application.HospitalCatalogService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class HospitalCatalogController {

	private final HospitalCatalogService catalogService;

	public HospitalCatalogController(HospitalCatalogService catalogService) {
		this.catalogService = catalogService;
	}

	@GetMapping("/hospitals")
	public List<HospitalResponse> listHospitals() {
		return catalogService.listHospitals().stream().map(HospitalResponse::from).toList();
	}

	@GetMapping("/specialties")
	public List<SpecialtyResponse> listSpecialties() {
		return catalogService.listSpecialties().stream().map(SpecialtyResponse::from).toList();
	}
}
