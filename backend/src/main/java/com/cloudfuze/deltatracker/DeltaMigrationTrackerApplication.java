package com.cloudfuze.deltatracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DeltaMigrationTrackerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeltaMigrationTrackerApplication.class, args);
    }

}
