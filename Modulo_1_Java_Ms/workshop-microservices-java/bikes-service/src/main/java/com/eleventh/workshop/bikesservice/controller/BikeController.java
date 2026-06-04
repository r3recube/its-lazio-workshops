package com.eleventh.workshop.bikesservice.controller;

import com.eleventh.workshop.bikesservice.model.Bike;
import com.eleventh.workshop.bikesservice.service.BikeService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bikes")
@CrossOrigin(origins = "*")
public class BikeController {

    private final BikeService service;

    public BikeController(BikeService service) {
        this.service = service;
    }

    @GetMapping
    public List<Bike> getAll() { return service.findAll(); }

    @GetMapping("/{id}")
    public ResponseEntity<Bike> getById(@PathVariable Long id) {
        return service.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Bike> create(@Valid @RequestBody Bike bike) {
        return ResponseEntity.status(201).body(service.save(bike));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Bike> update(@PathVariable Long id, @Valid @RequestBody Bike bike) {
        return ResponseEntity.ok(service.update(id, bike));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
