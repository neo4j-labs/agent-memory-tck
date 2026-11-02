package com.example.memory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamsConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class,
                    ImperativeHttpClientAutoConfiguration.class, RestClientAutoConfiguration.class))
            .withUserConfiguration(NamsConfiguration.class);

    @Test void usesBootConfiguredRestClientForMemoryRequests() {
        var intercepted = new AtomicBoolean();
        try (var stub = new MemoryServiceStub()) {
            runner.withPropertyValues("app.memory.endpoint=" + stub.endpoint(), "app.memory.api-key=test-key", "app.memory.timeout=10s")
                    .withBean(RestClientCustomizer.class, () -> builder -> builder.requestInterceptor((request, body, execution) -> {
                        assertEquals("Bearer test-key", request.getHeaders().getFirst("Authorization"));
                        intercepted.set(true);
                        return execution.execute(request, body);
                    }))
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        var conversation = context.getBean(NamsConversationHistory.class).create("Swiss trip");
                        assertEquals("Swiss trip", conversation.title().orElseThrow());
                        assertTrue(intercepted.get());
                        assertEquals(1, stub.requests().size());
                    });
        }
    }
    @Test void bindsMemoryConfigurationWithoutEagerRemoteCalls() {
        try (var stub = new MemoryServiceStub()) {
            runner.withPropertyValues("app.memory.endpoint=" + stub.endpoint(), "app.memory.api-key=test-key", "app.memory.timeout=10s")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        var properties = context.getBean(NamsProperties.class);
                        assertEquals(Duration.ofSeconds(10), properties.timeout());
                        assertEquals(stub.endpoint(), properties.endpoint());
                        assertNotNull(context.getBean(NamsConversationHistory.class));
                        assertNotNull(context.getBean(NamsChatMemory.class));
                        assertNotNull(context.getBean(NamsToolRecorder.class));
                        assertTrue(stub.requests().isEmpty());
                    });
            runner.withPropertyValues("app.memory.endpoint=" + stub.endpoint() + "/", "app.memory.api-key=test-key", "app.memory.timeout=10s")
                    .run(context -> assertEquals(stub.endpoint(), context.getBean(NamsProperties.class).endpoint()));
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"app.memory.api-key= ", "app.memory.timeout=0s", "app.memory.timeout=-1s", "app.memory.timeout=9223372036854775807s", "app.memory.endpoint=/v1", "app.memory.endpoint=ftp://localhost/v1", "app.memory.endpoint=http://localhost", "app.memory.endpoint=http://localhost/v1?key=secret", "app.memory.endpoint=http://localhost/v1#fragment", "app.memory.endpoint=http://user:secret@localhost/v1"})
    void rejectsInvalidConfiguration(String invalid) {
        runner.withPropertyValues("app.memory.endpoint=http://localhost/v1", "app.memory.api-key=test-key", "app.memory.timeout=10s")
                .withPropertyValues(invalid).run(context -> assertNotNull(context.getStartupFailure()));
    }
}
