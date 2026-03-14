package com.ccommit.monolith_to_msa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MonolithToMsaApplication {

	public static void main(String[] args) {
		SpringApplication.run(MonolithToMsaApplication.class, args);
	}

}
