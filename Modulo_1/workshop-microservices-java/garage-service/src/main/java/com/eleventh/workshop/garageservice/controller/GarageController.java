package com.eleventh.workshop.garageservice.controller;

import com.eleventh.workshop.garageservice.model.Garage;
import com.eleventh.workshop.garageservice.model.Vehicle;
import com.eleventh.workshop.garageservice.service.GarageService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/garages")
@CrossOrigin(origins = "*")
public class GarageController {

    private final GarageService service;

    public GarageController(GarageService service) {
        this.service = service;
    }

    @GetMapping
    public List<Garage> getAll() { return service.findAll(); }

    @GetMapping("/{id}")
    public ResponseEntity<Garage> getById(@PathVariable Long id) {
        return service.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Garage> create(@Valid @RequestBody Garage garage) {
        return ResponseEntity.status(201).body(service.save(garage));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Garage> update(@PathVariable Long id, @Valid @RequestBody Garage garage) {
        return ResponseEntity.ok(service.update(id, garage));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/vehicles")
    public ResponseEntity<Vehicle> addVehicle(@PathVariable Long id,
                                               @Valid @RequestBody Vehicle vehicle) {
        return ResponseEntity.status(201).body(service.addVehicle(id, vehicle));
    }

    @DeleteMapping("/{id}/vehicles/{vehicleId}")
    public ResponseEntity<Void> removeVehicle(@PathVariable Long id,
                                               @PathVariable Long vehicleId) {
        service.removeVehicle(id, vehicleId);
        return ResponseEntity.noContent().build();
    }
}
