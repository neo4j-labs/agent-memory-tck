package com.example.repository;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
@Configuration(proxyBeanMethods = false)
public class DuckDbDataJdbcConfiguration extends AbstractJdbcConfiguration {
    @Override protected Collection<String> getMappingBasePackages() { return List.of("com.example.entity"); }
    @Bean @Override public JdbcDialect jdbcDialect(NamedParameterJdbcOperations operations) { return DuckDbJdbcDialect.INSTANCE; }
}
