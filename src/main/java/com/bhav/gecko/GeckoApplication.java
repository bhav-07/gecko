package com.bhav.gecko;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

@OpenAPIDefinition(info = @Info(title = "Gecko API", version = "1.0", description = "API documentation for GeckoDB"))
@SpringBootApplication
public class GeckoApplication {

	public static void main(String[] args) {
		SpringApplication.run(GeckoApplication.class, args);
	}

}
