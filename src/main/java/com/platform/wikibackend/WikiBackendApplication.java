package com.platform.wikibackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WikiBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(WikiBackendApplication.class, args);
    }
}
