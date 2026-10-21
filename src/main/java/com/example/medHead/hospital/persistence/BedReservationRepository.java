package com.example.medhead.hospital.persistence;

import com.example.medhead.hospital.domain.BedReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BedReservationRepository extends JpaRepository<BedReservation, UUID> {
}
