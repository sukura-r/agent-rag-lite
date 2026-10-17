package com.example.agentrag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AgentRagLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentRagLiteApplication.class, args);
    }
}
