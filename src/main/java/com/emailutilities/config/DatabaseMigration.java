package com.emailutilities.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Add missing columns to sync_jobs table (idempotent)
        addColumnIfNotExists("sync_jobs", "current_page", "INTEGER DEFAULT 0");
        addColumnIfNotExists("sync_jobs", "emails_per_second", "BIGINT DEFAULT 0");
        addColumnIfNotExists("sync_jobs", "estimated_seconds_remaining", "INTEGER DEFAULT 0");
        addColumnIfNotExists("sync_jobs", "total_emails_processed", "INTEGER DEFAULT 0");
        addColumnIfNotExists("sync_jobs", "estimated_total_emails", "INTEGER DEFAULT 0");

        System.out.println("Database migration completed.");
    }

    private void addColumnIfNotExists(String table, String column, String definition) {
        try {
            String checkSql = "SELECT column_name FROM information_schema.columns " +
                             "WHERE table_name = ? AND column_name = ?";
            var result = jdbcTemplate.queryForList(checkSql, table, column);

            if (result.isEmpty()) {
                String alterSql = String.format("ALTER TABLE %s ADD COLUMN %s %s", table, column, definition);
                jdbcTemplate.execute(alterSql);
                System.out.println("Added column " + column + " to " + table);
            }
        } catch (Exception e) {
            System.err.println("Migration warning for " + table + "." + column + ": " + e.getMessage());
        }
    }
}
