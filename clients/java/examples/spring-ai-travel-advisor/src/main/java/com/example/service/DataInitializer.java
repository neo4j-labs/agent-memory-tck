package com.example.service;

import com.example.repository.EmbeddingBackfillRepository;
import com.example.repository.EmbeddingBackfillRepository.ActivityEmbeddingSeed;
import com.example.repository.EmbeddingBackfillRepository.DestinationEmbeddingSeed;
import com.example.repository.EmbeddingBackfillRepository.HotelEmbeddingSeed;
import com.example.repository.DuckDbExtensionManager;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@DependsOnDatabaseInitialization
public class DataInitializer implements SmartInitializingSingleton {
    private static final Logger LOG = LoggerFactory.getLogger(DataInitializer.class);

    private final EmbeddingService embeddingService;
    private final EmbeddingBackfillRepository embeddingBackfillRepository;
    private final DuckDbExtensionManager extensionManager;

    public DataInitializer(
            EmbeddingService embeddingService,
            EmbeddingBackfillRepository embeddingBackfillRepository,
            DuckDbExtensionManager extensionManager) {
        this.embeddingService = embeddingService;
        this.embeddingBackfillRepository = embeddingBackfillRepository;
        this.extensionManager = extensionManager;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // Finish synchronously before the server binds its HTTP port.
        initialize();
    }

    public void initialize() {
        LOG.info("Checking for missing embeddings...");
        stage("DuckDB extension setup", extensionManager::prepare);
        int[] counts = new int[3];
        stage("destination embedding backfill", () -> {
            for (DestinationEmbeddingSeed destination : embeddingBackfillRepository.findDestinationsWithoutEmbedding()) {
                String text = destination.name() + " " + destination.region() + ". " + destination.description();
                float[] embedding = embeddingService.generateEmbedding(text);
                embeddingBackfillRepository.updateDestinationEmbedding(destination.id(), embedding);
                counts[0]++;
            }
        });
        stage("hotel embedding backfill", () -> {
            for (HotelEmbeddingSeed hotel : embeddingBackfillRepository.findHotelsWithoutEmbedding()) {
                String text = hotel.name() + " in " + hotel.destinationName() + ". " + hotel.description();
                float[] embedding = embeddingService.generateEmbedding(text);
                embeddingBackfillRepository.updateHotelEmbedding(hotel.id(), embedding);
                counts[1]++;
            }
        });
        stage("activity embedding backfill", () -> {
            for (ActivityEmbeddingSeed activity : embeddingBackfillRepository.findActivitiesWithoutEmbedding()) {
                String text = activity.name() + " in " + activity.destinationName() + " (" + activity.season() + "). " + activity.description();
                float[] embedding = embeddingService.generateEmbedding(text);
                embeddingBackfillRepository.updateActivityEmbedding(activity.id(), embedding);
                counts[2]++;
            }
        });
        stage("embedding validation", extensionManager::validateEmbeddings);
        stage("HNSW index creation", extensionManager::createSearchIndexes);
        if (counts[0] + counts[1] + counts[2] > 0) {
            LOG.info("Generated embeddings: {} destinations, {} hotels, {} activities",
                    counts[0], counts[1], counts[2]);
        } else {
            LOG.info("All embeddings up to date");
        }
    }

    private static void stage(String name, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            throw new IllegalStateException("Data initialization failed during " + name, e);
        }
    }
}
