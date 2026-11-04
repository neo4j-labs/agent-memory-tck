package com.example.memory;

import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NamsProperties.class)
public class NamsConfiguration {
    @Bean MemoryClient memoryClient(NamsProperties properties, RestClient.Builder restClientBuilder,
            AsyncTaskExecutor applicationTaskExecutor) {
        return MemoryClient.create(MemoryClientConfiguration.builder()
                .baseUrl(properties.endpoint().toString())
                .apiKey(properties.apiKey())
                .restClient(restClientBuilder.build(), applicationTaskExecutor)
                .build());
    }
    @Bean NamsConversationHistory namsConversationHistory(MemoryClient client, NamsProperties properties) {
        return new NamsConversationHistory(client, properties.timeout());
    }
    @Bean NamsToolRecorder namsToolRecorder(MemoryClient client, NamsProperties properties) {
        return new NamsToolRecorder(client, properties.timeout());
    }
    @Bean NamsChatMemory namsChatMemory(MemoryClient client, NamsProperties properties, NamsContextFormatter formatter, NamsToolRecorder recorder) {
        return new NamsChatMemory(client, properties.timeout(), formatter, recorder);
    }
    @Bean NamsContextFormatter namsContextFormatter() { return new NamsContextFormatter(); }
}
