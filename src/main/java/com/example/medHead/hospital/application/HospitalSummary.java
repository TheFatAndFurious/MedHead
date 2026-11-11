package com.example.medhead.hospital.application;

/** Hospital shown in the catalogue, with WGS84 coordinates in degrees. */
public record HospitalSummary(long id, String code, String name, double latitude, double longitude) {
}
