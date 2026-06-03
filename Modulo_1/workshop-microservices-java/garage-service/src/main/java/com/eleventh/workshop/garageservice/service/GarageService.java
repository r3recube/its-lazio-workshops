package com.eleventh.workshop.garageservice.service;

import com.eleventh.workshop.garageservice.model.Garage;
import com.eleventh.workshop.garageservice.model.Vehicle;
import com.eleventh.workshop.garageservice.repository.GarageRepository;
import com.eleventh.workshop.garageservice.repository.VehicleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Service
public class GarageService {

    private final GarageRepository garageRepository;
    private final VehicleRepository vehicleRepository;
    private final VehicleValidator validator;

    public GarageService(GarageRepository garageRepository,
                         VehicleRepository vehicleRepository,
                         VehicleValidator validator) {
        this.garageRepository = garageRepository;
        this.vehicleRepository = vehicleRepository;
        this.validator = validator;
    }

    public List<Garage> findAll() { return garageRepository.findAll(); }

    public Optional<Garage> findById(Long id) { return garageRepository.findById(id); }

    public Garage save(Garage garage) { return garageRepository.save(garage); }

    public Garage update(Long id, Garage updated) {
        return garageRepository.findById(id)
            .map(existing -> {
                existing.setOwnerName(updated.getOwnerName());
                existing.setAddress(updated.getAddress());
                return garageRepository.save(existing);
            })
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public void delete(Long id) { garageRepository.deleteById(id); }

    public Vehicle addVehicle(Long garageId, Vehicle v) {
        if (!validator.exists(v.getVehicleType(), v.getExternalId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Vehicle " + v.getVehicleType() + ":" + v.getExternalId() + " does not exist");
        }
        Garage garage = garageRepository.findById(garageId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        v.setGarage(garage);
        return vehicleRepository.save(v);
    }

    public void removeVehicle(Long garageId, Long vehicleId) {
        Vehicle v = vehicleRepository.findById(vehicleId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!v.getGarage().getId().equals(garageId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        vehicleRepository.delete(v);
    }
}
