package com.example.service;

import com.example.memory.NamsChatMemory;
import com.example.memory.NamsChatMemoryAdvisor;
import com.example.memory.NamsToolCallingManager;
import com.example.tools.TravelTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SpringAiConfiguration {
    @Bean ObjectMapper legacyObjectMapper() { return new ObjectMapper(); }
    @Bean ChatClient chatClient(ChatModel chatModel, TravelTools travelTools,
            ObservationRegistry observationRegistry, ToolCallingManager toolCallingManager,
            NamsChatMemory chatMemory, ObjectMapper mapper) {
        return ChatClient.builder(chatModel, observationRegistry, null, null)
                .defaultAdvisors(new NamsChatMemoryAdvisor(chatMemory), ToolCallingAdvisor.builder()
                        .toolCallingManager(new NamsToolCallingManager(toolCallingManager, chatMemory, mapper)).build())
                .defaultTools((Object[]) ToolCallbacks.from(travelTools)).build();
    }
}
