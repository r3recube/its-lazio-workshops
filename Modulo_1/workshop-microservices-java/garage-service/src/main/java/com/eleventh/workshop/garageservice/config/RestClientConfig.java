package com.eleventh.workshop.garageservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Value("${services.bikes.url}")
    private String bikesUrl;

    @Value("${services.cars.url}")
    private String carsUrl;

    @Bean(name = "bikesClient")
    public RestClient bikesClient() {
        return RestClient.builder().baseUrl(bikesUrl).build();
    }

    @Bean(name = "carsClient")
    public RestClient carsClient() {
        return RestClient.builder().baseUrl(carsUrl).build();
    }
}
