package com.eleventh.workshop.carsservice.repository;

import com.eleventh.workshop.carsservice.model.Car;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarRepository extends JpaRepository<Car, Long> {
}
