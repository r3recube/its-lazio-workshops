package com.eleventh.workshop.garageservice.repository;

import com.eleventh.workshop.garageservice.model.Garage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GarageRepository extends JpaRepository<Garage, Long> {
}
