package com.example.medhead.hospital.persistence;

import com.example.medhead.hospital.domain.Specialty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpecialtyRepository extends JpaRepository<Specialty, Long> {

	Optional<Specialty> findByCode(String code);

	boolean existsByCode(String code);
}
