package com.example.medhead.hospital.persistence;

public interface NearestHospitalProjection {

	Long getHospitalId();

	String getHospitalCode();

	String getHospitalName();

	double getLatitude();

	double getLongitude();

	int getTotalBeds();

	int getAvailableBeds();

	double getDistanceMeters();
}
