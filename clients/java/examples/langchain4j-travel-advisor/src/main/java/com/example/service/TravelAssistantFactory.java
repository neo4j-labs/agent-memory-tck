package com.example.service;

import com.example.tools.TravelTools;
import dev.langchain4j.service.AiServices;
import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.langchain4j.aiservices.AiServiceDef;
import jakarta.inject.Singleton;
import java.util.Set;

@Factory
public class TravelAssistantFactory {
    @Singleton
    public TravelAssistant swissTravelAssistant(BeanContext beanContext) {
        var definition = new AiServiceDef<>(
            beanContext.getBeanDefinition(TravelAssistant.class),
            TravelAssistant.class, null, Set.of(TravelTools.class), null);
        AiServices<?> builder = beanContext.createBean(AiServices.class, definition);
        return TravelAssistant.class.cast(builder.build());
    }
}
