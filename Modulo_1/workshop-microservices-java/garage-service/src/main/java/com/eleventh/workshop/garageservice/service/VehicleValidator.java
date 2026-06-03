package com.eleventh.workshop.garageservice.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class VehicleValidator {

    private final RestClient bikesClient;
    private final RestClient carsClient;

    public VehicleValidator(@Qualifier("bikesClient") RestClient bikesClient,
                            @Qualifier("carsClient") RestClient carsClient) {
        this.bikesClient = bikesClient;
        this.carsClient = carsClient;
    }

    public boolean exists(String type, Long externalId) {
        RestClient client = switch (type.toUpperCase()) {
            case "BIKE" -> bikesClient;
            case "CAR"  -> carsClient;
            default -> throw new IllegalArgumentException("Unknown type: " + type);
        };
        String path = type.equalsIgnoreCase("BIKE") ? "/api/bikes/" : "/api/cars/";
        try {
            var response = client.get().uri(path + externalId).retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {})
                .toBodilessEntity();
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }
}
