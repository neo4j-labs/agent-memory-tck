package org.neo4j.agentmemory.http;

import org.neo4j.agentmemory.exception.MissingJsonCodecException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

final class JsonCodecs {
    record Candidate(String coordinate, String mapperClass, Supplier<JsonCodec> codec) {}

    private JsonCodecs() {}

    static JsonCodec select() {
        // Use lambdas: constructor references link adapters before the guarded classpath probe.
        return select(List.of(
                new Candidate("tools.jackson.core:jackson-databind 3.1.4+",
                        "tools.jackson.databind.json.JsonMapper", () -> new Jackson3JsonCodec()),
                new Candidate("com.fasterxml.jackson.core:jackson-databind 2.19.0+",
                        "com.fasterxml.jackson.databind.json.JsonMapper", () -> new Jackson2JsonCodec())));
    }

    static JsonCodec select(List<Candidate> candidates) {
        var failures = new ArrayList<Throwable>();
        for (var candidate : candidates) {
            try {
                Class.forName(candidate.mapperClass(), false, JsonCodecs.class.getClassLoader());
                return candidate.codec().get();
            } catch (ClassNotFoundException | LinkageError failure) {
                failures.add(failure);
            }
        }

        var coordinates = candidates.stream()
                .map(Candidate::coordinate)
                .collect(Collectors.joining(" or "));
        var missing = new MissingJsonCodecException(
                "No supported JSON codec is available; add " + coordinates, null);
        failures.forEach(missing::addSuppressed);
        throw missing;
    }
}
