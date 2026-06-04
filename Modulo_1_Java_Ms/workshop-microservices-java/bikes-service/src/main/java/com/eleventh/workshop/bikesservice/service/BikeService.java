package com.eleventh.workshop.bikesservice.service;

import com.eleventh.workshop.bikesservice.model.Bike;
import com.eleventh.workshop.bikesservice.repository.BikeRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class BikeService {

    private final BikeRepository repository;

    public BikeService(BikeRepository repository) {
        this.repository = repository;
    }

    public List<Bike> findAll() { return repository.findAll(); }

    public Optional<Bike> findById(Long id) { return repository.findById(id); }

    public Bike save(Bike bike) { return repository.save(bike); }

    public Bike update(Long id, Bike updated) {
        return repository.findById(id)
            .map(existing -> {
                existing.setBrand(updated.getBrand());
                existing.setModel(updated.getModel());
                existing.setEngineCc(updated.getEngineCc());
                existing.setType(updated.getType());
                existing.setYear(updated.getYear());
                existing.setPrice(updated.getPrice());
                return repository.save(existing);
            })
            .orElseThrow(() -> new RuntimeException("Bike not found: " + id));
    }

    public void delete(Long id) { repository.deleteById(id); }
}
