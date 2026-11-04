package com.example.repository;

public final class DuckDbVectors {
    private DuckDbVectors() {
    }

    public static String literal(float[] embedding) {
        if (embedding == null || embedding.length != 1536) {
            throw new IllegalArgumentException("embedding must contain 1536 floats; received "
                + (embedding == null ? "null" : embedding.length));
        }
        StringBuilder result = new StringBuilder(embedding.length * 8).append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i != 0) result.append(',');
            result.append(embedding[i]);
        }
        return result.append(']').toString();
    }
}
