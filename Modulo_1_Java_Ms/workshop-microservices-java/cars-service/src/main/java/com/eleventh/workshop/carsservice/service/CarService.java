package com.eleventh.workshop.carsservice.service;

import com.eleventh.workshop.carsservice.model.Car;
import com.eleventh.workshop.carsservice.repository.CarRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class CarService {

    private final CarRepository repository;

    public CarService(CarRepository repository) {
        this.repository = repository;
    }

    public List<Car> findAll() { return repository.findAll(); }

    public Optional<Car> findById(Long id) { return repository.findById(id); }

    public Car save(Car car) { return repository.save(car); }

    public Car update(Long id, Car updated) {
        return repository.findById(id)
            .map(existing -> {
                existing.setBrand(updated.getBrand());
                existing.setModel(updated.getModel());
                existing.setFuelType(updated.getFuelType());
                existing.setDoors(updated.getDoors());
                existing.setSeats(updated.getSeats());
                existing.setYear(updated.getYear());
                existing.setPrice(updated.getPrice());
                return repository.save(existing);
            })
            .orElseThrow(() -> new RuntimeException("Car not found: " + id));
    }

    public void delete(Long id) { repository.deleteById(id); }
}
