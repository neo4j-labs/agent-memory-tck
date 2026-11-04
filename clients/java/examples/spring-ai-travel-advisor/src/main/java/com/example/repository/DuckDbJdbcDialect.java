package com.example.repository;
import org.springframework.data.relational.core.dialect.AnsiDialect;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.jdbc.core.dialect.JdbcArrayColumns;
public final class DuckDbJdbcDialect extends AnsiDialect implements JdbcDialect {
    public static final DuckDbJdbcDialect INSTANCE = new DuckDbJdbcDialect();
    private DuckDbJdbcDialect() {}
    @Override public JdbcArrayColumns getArraySupport() { return JdbcArrayColumns.unsupported(); }
}
