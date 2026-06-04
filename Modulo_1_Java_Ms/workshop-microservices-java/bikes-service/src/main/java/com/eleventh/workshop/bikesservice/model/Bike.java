package com.eleventh.workshop.bikesservice.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;

@Entity
@Table(name = "bikes")
public class Bike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    private String brand;

    @NotBlank
    private String model;

    @Min(50)
    private Integer engineCc;

    @NotBlank
    private String type;

    @Min(1900)
    private Integer year;

    @Min(0)
    private Double price;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getEngineCc() { return engineCc; }
    public void setEngineCc(Integer engineCc) { this.engineCc = engineCc; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }
    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }
}
