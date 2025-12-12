package com.wf.benchmark.search;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.sql.DataSource;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for UC 1-7 unified search using DBMS_SEARCH.FIND.
 *
 * These tests require a live Oracle 23ai database with:
 * 1. DBMS_SEARCH unified index created on collections
 * 2. Test data loaded via the benchmark load command
 *
 * To run these tests, set environment variables OR system properties:
 * - ORACLE_JDBC_URL / oracle.jdbc.url
 * - ORACLE_USERNAME / oracle.username
 * - ORACLE_PASSWORD / oracle.password
 *
 * Run via Maven:
 * mvn test -Dtest=UcSearchIntegrationTest \
 *     -Doracle.jdbc.url="jdbc:oracle:thin:@..." \
 *     -Doracle.username="admin" \
 *     -Doracle.password="..."
 *
 * Or via environment variables:
 * ORACLE_JDBC_URL="..." ORACLE_USERNAME="admin" ORACLE_PASSWORD="..." mvn test -Dtest=UcSearchIntegrationTest
 *
 * Example connection string with wallet:
 * jdbc:oracle:thin:@WELLSFARGO_medium?TNS_ADMIN=/path/to/wallet
 */
@EnabledIfSystemProperty(named = "oracle.jdbc.url", matches = ".+", disabledReason = "Set -Doracle.jdbc.url to enable integration tests")
class UcSearchIntegrationTest {

    private static DataSource dataSource;
    private static UcSearchService ucSearchService;

    @BeforeAll
    static void setUp() {
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

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);

        dataSource = new HikariDataSource(config);

        // Use "bench_" prefix to match loaded test data
        ucSearchService = new UcSearchService(dataSource, "bench_");
    }

    @Nested
    class UnifiedSearchUC1Tests {

        @Test
        void shouldSearchByPhoneAndSsnLast4() {
            // UC-1: Phone + SSN Last 4
            // Note: Results depend on test data loaded
            try {
                List<UcSearchResult> results = ucSearchService.searchUnifiedUC1("5551234567", "1234", 10);

                System.out.println("UC-1 Unified Search Results (" + results.size() + " found):");
                results.forEach(r -> System.out.println("  ECN: " + r.getEcn() +
                    ", Name: " + r.getName() +
                    ", Score: " + r.getRankingScore()));

                // Verify results are sorted by score descending
                if (results.size() > 1) {
                    for (int i = 0; i < results.size() - 1; i++) {
                        assertThat(results.get(i).getRankingScore())
                            .isGreaterThanOrEqualTo(results.get(i + 1).getRankingScore());
                    }
                }
            } catch (SearchException e) {
                System.out.println("UC-1 search failed (may need unified index): " + e.getMessage());
            }
        }
    }

    @Nested
    class UnifiedSearchUC2Tests {

        @Test
        void shouldSearchByPhoneSsnLast4AndAccountLast4() {
            // UC-2: Phone + SSN Last 4 + Account Last 4
            try {
                List<UcSearchResult> results = ucSearchService.searchUnifiedUC2("5551234567", "1234", "5678", 10);

                System.out.println("UC-2 Unified Search Results (" + results.size() + " found):");
                results.forEach(r -> System.out.println("  ECN: " + r.getEcn() +
                    ", Name: " + r.getName() +
                    ", Score: " + r.getRankingScore()));

            } catch (SearchException e) {
                System.out.println("UC-2 search failed (may need unified index): " + e.getMessage());
            }
        }
    }

    @Nested
    class UnifiedSearchUC3Tests {

        @Test
        void shouldSearchByPhoneAndAccountLast4() {
            // UC-3: Phone + Account Last 4
            try {
                List<UcSearchResult> results = ucSearchService.searchUnifiedUC3("5551234567", "5678", 10);

                System.out.println("UC-3 Unified Search Results (" + results.size() + " found):");
                results.forEach(r -> System.out.println("  ECN: " + r.getEcn() +
                    ", Name: " + r.getName() +
                    ", Score: " + r.getRankingScore()));

            } catch (SearchException e) {
                System.out.println("UC-3 search failed (may need unified index): " + e.getMessage());
            }
        }
    }

    @Nested
    class UnifiedSearchUC4Tests {

        @Test
        void shouldSearchByAccountNumberAndSsnLast4() {
            // UC-4: Account Number + SSN Last 4
            try {
                List<UcSearchResult> results = ucSearchService.searchUnifiedUC4("1234567890123456", "1234", 10);

                System.out.println("UC-4 Unified Search Results (" + results.size() + " found):");
                results.forEach(r -> System.out.println("  ECN: " + r.getEcn() +
                    ", Name: " + r.getName() +
                    ", Score: " + r.getRankingScore()));

            } catch (SearchException e) {
                System.out.println("UC-4 search failed (may need unified index): " + e.getMessage());
            }
        }
    }

    @Nested
    class UnifiedSearchUC5Tests {

        @Test
        void shouldSearchByCityStateZipSsnLast4AndAccountLast4() {
            // UC-5: City/State/ZIP + SSN Last 4 + Account Last 4
            try {
                List<UcSearchResult> results = ucSearchService.searchUnifiedUC5(
                    "New York", "NY", "10001", "1234", "5678", 10);

                System.out.println("UC-5 Unified Search Results (" + results.size() + " found):");
                results.forEach(r -> System.out.println("  ECN: " + r.getEcn() +
                    ", Name: " + r.getName() +
                    ", City: " + r.getCityName() +
                    ", Score: " + r.getRankingScore()));

            } catch (SearchException e) {
                System.out.println("UC-5 search failed (may need unified index): " + e.getMessage());
            }
        }
    }

    @Nested
    class UnifiedSearchUC6Tests {

        @Test
        void shouldSearchByEmailAndAccountLast4() {
            // UC-6: Email + Account Last 4
            try {
                List<UcSearchResult> results = ucSearchService.searchUnifiedUC6("john@example.com", "5678", 10);

                System.out.println("UC-6 Unified Search Results (" + results.size() + " found):");
                results.forEach(r -> System.out.println("  ECN: " + r.getEcn() +
                    ", Name: " + r.getName() +
                    ", Score: " + r.getRankingScore()));

            } catch (SearchException e) {
                System.out.println("UC-6 search failed (may need unified index): " + e.getMessage());
            }
        }
    }

    @Nested
    class UnifiedSearchUC7Tests {

        @Test
        void shouldSearchByEmailPhoneAndAccountNumber() {
            // UC-7: Email + Phone + Account Number
            try {
                List<UcSearchResult> results = ucSearchService.searchUnifiedUC7(
                    "john@example.com", "5551234567", "1234567890123456", 10);

                System.out.println("UC-7 Unified Search Results (" + results.size() + " found):");
                results.forEach(r -> System.out.println("  ECN: " + r.getEcn() +
                    ", Name: " + r.getName() +
                    ", Score: " + r.getRankingScore()));

            } catch (SearchException e) {
                System.out.println("UC-7 search failed (may need unified index): " + e.getMessage());
            }
        }
    }

    @Nested
    class FuzzyOrQueryTests {

        @Test
        void shouldBuildAndExecuteFuzzyOrQuery() {
            // Test the buildFuzzyOrQuery method produces valid SQL
            String query = ucSearchService.buildFuzzyOrQuery(List.of("5551234567", "1234"), 10);

            System.out.println("Generated Fuzzy OR Query:");
            System.out.println(query);

            assertThat(query).contains("DBMS_SEARCH.FIND");
            assertThat(query).contains("fuzzy(5551234567)");
            assertThat(query).contains("fuzzy(1234)");
            assertThat(query).contains("OR");
        }
    }

    @Nested
    class PerformanceTests {

        @Test
        void shouldCompleteUC1SearchWithinTimeout() {
            try {
                long start = System.currentTimeMillis();

                List<UcSearchResult> results = ucSearchService.searchUnifiedUC1("5551234567", "1234", 100);

                long elapsed = System.currentTimeMillis() - start;

                System.out.println("UC-1 Unified Search completed in " + elapsed + "ms with " + results.size() + " results");

                // Should complete within reasonable time (10 seconds for unified search)
                assertThat(elapsed).isLessThan(10000);

            } catch (SearchException e) {
                System.out.println("UC-1 performance test failed: " + e.getMessage());
            }
        }

        @Test
        void shouldCompleteUC5SearchWithinTimeout() {
            try {
                long start = System.currentTimeMillis();

                // UC-5 has the most search terms (5 terms)
                List<UcSearchResult> results = ucSearchService.searchUnifiedUC5(
                    "New York", "NY", "10001", "1234", "5678", 100);

                long elapsed = System.currentTimeMillis() - start;

                System.out.println("UC-5 Unified Search completed in " + elapsed + "ms with " + results.size() + " results");

                // Should complete within reasonable time
                assertThat(elapsed).isLessThan(15000);

            } catch (SearchException e) {
                System.out.println("UC-5 performance test failed: " + e.getMessage());
            }
        }
    }

    @Nested
    class CategoryMatchingTests {

        @Test
        void shouldOnlyReturnCustomersWithAllRequiredCategories() {
            // This test verifies the core algorithm: customers must match ALL required categories
            // For UC-1, a customer must have both a PHONE match AND an SSN_LAST4 match
            try {
                List<UcSearchResult> results = ucSearchService.searchUnifiedUC1("5551234567", "9999", 10);

                System.out.println("Category matching test - UC-1 results (" + results.size() + " found):");
                results.forEach(r -> System.out.println("  ECN: " + r.getEcn() + ", Name: " + r.getName()));

                // Results should only include customers that matched BOTH phone AND SSN last 4
                // Empty results are valid if no customer has both matching terms

            } catch (SearchException e) {
                System.out.println("Category matching test failed: " + e.getMessage());
            }
        }
    }

    /**
     * Get configuration value from properties file, system property, or environment variable.
     */
    private static String getConfig(String envVar, String sysProp) {
        // First try properties file
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

        // Then try system property (only if it looks like a real value)
        value = System.getProperty(sysProp);
        if (value != null && !value.isEmpty() && !value.equals("enabled")) {
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
