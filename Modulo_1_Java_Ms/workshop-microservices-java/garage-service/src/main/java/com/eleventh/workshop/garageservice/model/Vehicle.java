package com.eleventh.workshop.garageservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "vehicles")
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    private Long externalId;

    @NotBlank
    private String vehicleType;

    @ManyToOne
    @JoinColumn(name = "garage_id")
    @JsonIgnore
    private Garage garage;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getExternalId() { return externalId; }
    public void setExternalId(Long externalId) { this.externalId = externalId; }
    public String getVehicleType() { return vehicleType; }
    public void setVehicleType(String vehicleType) { this.vehicleType = vehicleType; }
    public Garage getGarage() { return garage; }
    public void setGarage(Garage garage) { this.garage = garage; }
}
