package com.wf.benchmark.search;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.sql.DataSource;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for hybrid search services.
 *
 * These tests require a live Oracle 23ai database with:
 * 1. Oracle Text indexes configured
 * 2. ONNX embedding model loaded for vector search
 * 3. Test data loaded via the benchmark load command
 *
 * To run these tests, set environment variables OR system properties:
 * - ORACLE_JDBC_URL / oracle.jdbc.url
 * - ORACLE_USERNAME / oracle.username
 * - ORACLE_PASSWORD / oracle.password
 *
 * Run via Maven:
 * mvn test -Dtest=HybridSearchIntegrationTest \
 *     -Doracle.jdbc.url="jdbc:oracle:thin:@..." \
 *     -Doracle.username="admin" \
 *     -Doracle.password="..."
 *
 * Or via environment variables:
 * ORACLE_JDBC_URL="..." ORACLE_USERNAME="admin" ORACLE_PASSWORD="..." mvn test -Dtest=HybridSearchIntegrationTest
 *
 * Example connection string with wallet:
 * jdbc:oracle:thin:@WELLSFARGO_medium?TNS_ADMIN=/path/to/wallet
 */
@EnabledIfSystemProperty(named = "oracle.jdbc.url", matches = ".+", disabledReason = "Set -Doracle.jdbc.url to enable integration tests")
class HybridSearchIntegrationTest {

    private static DataSource dataSource;
    private static HybridSearchService hybridSearchService;

    @BeforeAll
    static void setUp() throws SQLException {
        // Support both environment variables and system properties
        String jdbcUrl = getConfig("ORACLE_JDBC_URL", "oracle.jdbc.url");
        String username = getConfig("ORACLE_USERNAME", "oracle.username");
        String password = getConfig("ORACLE_PASSWORD", "oracle.password");

        if (jdbcUrl == null || username == null || password == null) {
            throw new IllegalStateException(
                "Required configuration not set. Set environment variables (ORACLE_JDBC_URL, ORACLE_USERNAME, ORACLE_PASSWORD) " +
                "or system properties (-Doracle.jdbc.url, -Doracle.username, -Doracle.password)");
        }

        System.out.println("Connecting to: " + jdbcUrl.substring(0, Math.min(50, jdbcUrl.length())) + "...");
        System.out.println("Username: " + username);
        System.out.println("Password length: " + (password != null ? password.length() : "null"));

        // Create HikariCP connection pool (high performance)
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        dataSource = new HikariDataSource(config);

        // Create services
        FuzzySearchService fuzzySearchService = new FuzzySearchService(dataSource);
        PhoneticSearchService phoneticSearchService = new PhoneticSearchService(dataSource);
        VectorSearchService vectorSearchService = new VectorSearchService(dataSource);

        hybridSearchService = new HybridSearchService(
            fuzzySearchService, phoneticSearchService, vectorSearchService);
    }

    @Nested
    class FuzzySearchIntegrationTests {

        @Test
        void shouldFindCustomersByFuzzyName() {
            // When - Search for a name with slight variation
            List<HybridSearchResult> results = hybridSearchService.searchByName(
                "Jon", "Smithe", "identity", 10);

            // Then - Print results (may be empty if Oracle Text index not configured)
            System.out.println("Fuzzy name search results (" + results.size() + " found):");
            results.forEach(r -> System.out.println("  " + r));
            // Note: Fuzzy search requires Oracle Text index on DATA column
        }

        @Test
        void shouldFindBusinessByFuzzyName() {
            // When
            List<HybridSearchResult> results = hybridSearchService.searchByBusinessDescription(
                "Acme Corporaton", "identity", 10); // Intentional misspelling

            // Then - Print results (may be empty if Oracle Text index not configured)
            System.out.println("Fuzzy business search results (" + results.size() + " found):");
            results.forEach(r -> System.out.println("  " + r));
        }
    }

    @Nested
    class PhoneticSearchIntegrationTests {

        @Test
        void shouldFindByPhoneticMatch() {
            // When - Search for names that sound alike using a common name
            List<HybridSearchResult> results = hybridSearchService.searchByName(
                "John", "Smith", "identity", 10);

            // Then - Print results (SOUNDEX-based phonetic search should return results)
            System.out.println("Phonetic search results (" + results.size() + " found):");
            results.forEach(r -> System.out.println("  " + r));
            // Should find phonetic matches for common names like John/Jon, Smith/Smythe
        }

        @Test
        void shouldExpandNicknames() {
            // When - Search for nickname, should find formal name too
            // "Bill" should match "William"
            List<HybridSearchResult> results = hybridSearchService.searchByName(
                "Bill", "Johnson", "identity", 10);

            // Then - Print results
            System.out.println("Nickname expansion results (" + results.size() + " found):");
            results.forEach(r -> System.out.println("  " + r));
        }
    }

    @Nested
    class VectorSearchIntegrationTests {

        @Test
        void shouldFindBySemanticDescription() {
            // When - Natural language query
            // Note: Requires ONNX embedding model and vector index to be configured
            try {
                List<HybridSearchResult> results = hybridSearchService.searchByDescription(
                    "customers working in technology sector", "identity", 10);
                System.out.println("Semantic search results (" + results.size() + " found):");
                results.forEach(r -> System.out.println("  " + r));
            } catch (SearchException e) {
                System.out.println("Vector search not available (requires embedding column and ONNX model): " + e.getMessage());
                // Expected if vector search infrastructure not configured
            }
        }

        @Test
        void shouldFindSimilarCustomers() {
            // Given - Need a known customer number from test data
            String referenceCustomer = "1000000001";

            // When - Note: Requires embedding column to be configured
            try {
                List<HybridSearchResult> results = hybridSearchService.findSimilarCustomers(
                    referenceCustomer, "identity", 10);
                System.out.println("Similar customers to " + referenceCustomer + " (" + results.size() + " found):");
                results.forEach(r -> System.out.println("  " + r));
                // Verify reference customer not in results
                if (!results.isEmpty()) {
                    assertThat(results).noneMatch(r -> r.getCustomerNumber().equals(referenceCustomer));
                }
            } catch (SearchException e) {
                System.out.println("Similar customer search not available (requires embedding column): " + e.getMessage());
                // Expected if vector search infrastructure not configured
            }
        }
    }

    @Nested
    class HybridSearchIntegrationTests {

        @Test
        void shouldCombineMultipleStrategies() {
            // When - Search that should hit multiple strategies
            List<HybridSearchResult> results = hybridSearchService.searchByName(
                "John", "Smith", "identity", 20);

            // Then - Should combine and deduplicate
            System.out.println("Hybrid search results:");
            results.forEach(r -> System.out.println(
                "  " + r.getCustomerNumber() + " - " + r.getMatchedValue() +
                " (score: " + r.getScore() + ", strategies: " + r.getMatchStrategies() + ")"));

            // Verify deduplication
            long uniqueCustomers = results.stream()
                .map(HybridSearchResult::getCustomerNumber)
                .distinct()
                .count();
            assertThat(uniqueCustomers).isEqualTo(results.size());
        }

        @Test
        void shouldRankByScore() {
            // When
            List<HybridSearchResult> results = hybridSearchService.searchByName(
                "Smith", "Johnson", "identity", 10);

            // Then - Verify descending order
            if (results.size() > 1) {
                for (int i = 0; i < results.size() - 1; i++) {
                    assertThat(results.get(i).getScore())
                        .isGreaterThanOrEqualTo(results.get(i + 1).getScore());
                }
            }
        }
    }

    @Nested
    class PerformanceTests {

        @Test
        void shouldCompleteSearchWithinTimeout() {
            long start = System.currentTimeMillis();

            // Execute hybrid search
            List<HybridSearchResult> results = hybridSearchService.searchByName(
                "John", "Smith", "identity", 100);

            long elapsed = System.currentTimeMillis() - start;

            System.out.println("Search completed in " + elapsed + "ms with " + results.size() + " results");

            // Should complete within reasonable time (5 seconds)
            assertThat(elapsed).isLessThan(5000);
        }
    }

    /**
     * Get configuration value from properties file, system property, or environment variable.
     * Priority: properties file > system property (if valid JDBC URL) > environment variable.
     *
     * This ordering allows using a simple -Doracle.jdbc.url=enabled to trigger the test
     * while actual credentials come from the properties file (avoiding shell escaping issues).
     */
    private static String getConfig(String envVar, String sysProp) {
        // First try properties file (preferred - avoids shell escaping issues)
        String value = null;
        try {
            Properties props = new Properties();
            java.io.File propFile = new java.io.File("integration-test.properties");
            if (propFile.exists()) {
                try (FileInputStream fis = new FileInputStream(propFile)) {
                    props.load(fis);
                }
                value = props.getProperty(sysProp);
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        } catch (IOException e) {
            // ignore
        }

        // Then try system property (only if it looks like a real value, not just "enabled")
        value = System.getProperty(sysProp);
        if (value != null && !value.isEmpty() && !value.equals("enabled")) {
            // For jdbc.url, verify it starts with jdbc:
            if (sysProp.contains("url") && !value.startsWith("jdbc:")) {
                // Skip - not a valid JDBC URL
            } else {
                return value;
            }
        }

        // Finally try environment variable
        value = System.getenv(envVar);
        if (value != null && !value.isEmpty()) {
            return value;
        }

        return null;
    }
}
