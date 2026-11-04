package com.example.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class DuckDbVectorJdbc {
    private DuckDbVectorJdbc() {
    }

    public static void bind(PreparedStatement statement, int index, float[] embedding) throws SQLException {
        if (embedding == null || embedding.length != 1536) {
            throw new IllegalArgumentException("embedding must contain 1536 floats; received "
                    + (embedding == null ? "null" : embedding.length));
        }
        Float[] boxed = new Float[embedding.length];
        for (int i = 0; i < embedding.length; i++) {
            boxed[i] = embedding[i];
        }
        statement.setArray(index, statement.getConnection().createArrayOf("FLOAT", boxed));
    }
}
