package com.eleventh.workshop.bikesservice.repository;

import com.eleventh.workshop.bikesservice.model.Bike;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BikeRepository extends JpaRepository<Bike, Long> {
}
