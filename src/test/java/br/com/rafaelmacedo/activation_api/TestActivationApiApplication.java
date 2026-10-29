package br.com.rafaelmacedo.activation_api;

import org.springframework.boot.SpringApplication;

public class TestActivationApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(ActivationApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
