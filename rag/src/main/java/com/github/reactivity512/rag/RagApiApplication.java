package com.github.reactivity512.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Точка входа API-сервиса: REST, статика, постановка задач в очередь.
 *
 * <p>Запуск: {@code java -jar rag-hr.jar --spring.profiles.active=api}
 */
@SpringBootApplication
public class RagApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApiApplication.class, args);
    }
}
