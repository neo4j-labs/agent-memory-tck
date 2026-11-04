package com.example.repository;

import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;

import javax.sql.DataSource;

public final class JdbcDataSources {
    private JdbcDataSources() {
    }

    public static DataSource raw(DataSource dataSource) {
        // Micronaut Data wraps DataSource.getConnection() with a contextual connection.
        // The existing JDBC repositories manage their own connection lifetimes.
        return DelegatingDataSource.unwrapDataSource(dataSource);
    }
}
