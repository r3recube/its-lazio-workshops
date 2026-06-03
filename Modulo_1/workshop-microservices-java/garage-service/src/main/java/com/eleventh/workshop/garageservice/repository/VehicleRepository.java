package com.eleventh.workshop.garageservice.repository;

import com.eleventh.workshop.garageservice.model.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {
}
