package com.example.repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.duckdb.DuckDBDriver;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

final class RepositoryTestContext {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableJdbcRepositories(basePackageClasses = DestinationRepository.class)
    @EnableTransactionManagement
    @Import(DuckDbDataJdbcConfiguration.class)
    static class Repositories {}

    static AnnotationConfigApplicationContext start(String url) {
        var context = new AnnotationConfigApplicationContext();
        HikariConfig config = new HikariConfig();
        String pinnedUrl = url.contains("jdbc_pin_db=true") ? url : url + ";jdbc_pin_db=true";
        config.setJdbcUrl(pinnedUrl); config.setUsername(""); config.setPassword("");
        config.setMaximumPoolSize(5); config.setMinimumIdle(1);
        config.setConnectionInitSql("INSTALL spatial; LOAD spatial; SET geometry_always_xy=true");
        context.registerBean("dataSource", HikariDataSource.class, () -> new HikariDataSource(config) {
            @Override public void close() {
                super.close();
                try { DuckDBDriver.releaseDB(pinnedUrl); }
                catch (SQLException error) { throw new IllegalStateException(error); }
            }
        });
        context.registerBean("namedParameterJdbcTemplate", NamedParameterJdbcTemplate.class,
            () -> new NamedParameterJdbcTemplate(context.getBean(DataSource.class)));
        context.registerBean("transactionManager", JdbcTransactionManager.class,
            () -> new JdbcTransactionManager(context.getBean(DataSource.class)));
        context.register(Repositories.class);
        context.refresh();
        return context;
    }
}
