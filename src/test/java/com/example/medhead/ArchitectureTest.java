package com.example.medhead;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Dependencies of the hospital module go one way only: HTTP contract, then use cases,
 * then persistence, then domain. The application layer never sees the API DTOs.
 */
class ArchitectureTest {

	private static JavaClasses productionClasses;

	@BeforeAll
	static void importProductionClasses() {
		productionClasses = new ClassFileImporter()
				.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages("com.example.medhead");
	}

	@Test
	void hospitalLayersOnlyDependOnTheLayersBelowThem() {
		layeredArchitecture()
				.consideringOnlyDependenciesInLayers()
				.layer("API").definedBy("..hospital.api..")
				.layer("Application").definedBy("..hospital.application..")
				.layer("Persistence").definedBy("..hospital.persistence..")
				.layer("Domain").definedBy("..hospital.domain..")
				.whereLayer("API").mayNotBeAccessedByAnyLayer()
				.whereLayer("Application").mayOnlyBeAccessedByLayers("API")
				.whereLayer("Persistence").mayOnlyBeAccessedByLayers("Application")
				.whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Persistence")
				.check(productionClasses);
	}
}
