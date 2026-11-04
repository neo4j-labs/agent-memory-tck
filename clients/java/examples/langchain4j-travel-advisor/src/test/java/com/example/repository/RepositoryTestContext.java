package com.example.repository;

import com.example.service.DataInitializer;
import io.micronaut.context.ApplicationContext;

import java.util.Map;

import static org.mockito.Mockito.mock;

final class RepositoryTestContext {
    private RepositoryTestContext() {
    }

    static ApplicationContext start(String url) {
        return ApplicationContext.builder()
            .properties(Map.of("datasources.default.url", url,
                "flyway.datasources.default.enabled", false,
                "langchain4j.open-ai.api-key", "repository-test"))
            .singletons(mock(DataInitializer.class), mock(DuckDbExtensionManager.class))
            .start();
    }
}
