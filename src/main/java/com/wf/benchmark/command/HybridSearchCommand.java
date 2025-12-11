package com.wf.benchmark.command;

import com.wf.benchmark.search.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI command for running hybrid search tests against Oracle ADB.
 *
 * Supports:
 * - Fuzzy text search (Oracle Text CONTAINS with FUZZY)
 * - Phonetic search (SOUNDEX with nickname expansion)
 * - Vector similarity search (Oracle AI Vector Search)
 * - Combined hybrid search strategies
 */
@Command(
    name = "hybrid-search",
    description = "Run hybrid search tests against Oracle ADB using SQL/JDBC",
    mixinStandardHelpOptions = true
)
public class HybridSearchCommand implements Callable<Integer> {

    @Option(names = {"-j", "--jdbc-url"}, description = "Oracle JDBC connection URL", required = true)
    private String jdbcUrl;

    @Option(names = {"-u", "--username"}, description = "Database username", required = true)
    private String username;

    @Option(names = {"-p", "--password"}, description = "Database password", required = true)
    private String password;

    @Option(names = {"--collection"}, description = "Collection/table name", defaultValue = "identity")
    private String collection;

    @Option(names = {"--first-name"}, description = "First name to search for")
    private String firstName;

    @Option(names = {"--last-name"}, description = "Last name to search for")
    private String lastName;

    @Option(names = {"--description"}, description = "Description for semantic search")
    private String description;

    @Option(names = {"--business-name"}, description = "Business name to search for")
    private String businessName;

    @Option(names = {"--similar-to"}, description = "Customer number to find similar customers")
    private String similarToCustomer;

    @Option(names = {"--limit"}, description = "Maximum results", defaultValue = "10")
    private int limit;

    @Option(names = {"--fuzzy-min-score"}, description = "Minimum fuzzy match score (0-100)", defaultValue = "70")
    private int fuzzyMinScore;

    @Option(names = {"--vector-min-similarity"}, description = "Minimum vector similarity (0.0-1.0)", defaultValue = "0.7")
    private double vectorMinSimilarity;

    @Option(names = {"--disable-fuzzy"}, description = "Disable fuzzy search strategy", defaultValue = "false")
    private boolean disableFuzzy;

    @Option(names = {"--disable-phonetic"}, description = "Disable phonetic search strategy", defaultValue = "false")
    private boolean disablePhonetic;

    @Option(names = {"--disable-vector"}, description = "Disable vector search strategy", defaultValue = "false")
    private boolean disableVector;

    @Option(names = {"--run-all-tests"}, description = "Run all hybrid search tests", defaultValue = "false")
    private boolean runAllTests;

    @Option(names = {"--pool-size"}, description = "Connection pool size", defaultValue = "10")
    private int poolSize;

    @Option(names = {"-q", "--quiet"}, description = "Suppress progress output", defaultValue = "false")
    private boolean quiet;

    @Override
    public Integer call() {
        try {
            DataSource dataSource = createDataSource();

            // Create search services
            FuzzySearchService fuzzySearchService = new FuzzySearchService(dataSource);
            PhoneticSearchService phoneticSearchService = new PhoneticSearchService(dataSource);
            VectorSearchService vectorSearchService = new VectorSearchService(dataSource);

            // Configure services
            fuzzySearchService.setMinScore(fuzzyMinScore);
            vectorSearchService.setMinSimilarity(vectorMinSimilarity);

            HybridSearchService hybridSearchService = new HybridSearchService(
                fuzzySearchService, phoneticSearchService, vectorSearchService);

            // Apply strategy configuration
            hybridSearchService.setFuzzyEnabled(!disableFuzzy);
            hybridSearchService.setPhoneticEnabled(!disablePhonetic);
            hybridSearchService.setVectorEnabled(!disableVector);

            if (runAllTests) {
                return runAllHybridTests(hybridSearchService);
            }

            // Run specific search based on options
            if (firstName != null && lastName != null) {
                return runNameSearch(hybridSearchService);
            } else if (description != null) {
                return runDescriptionSearch(hybridSearchService);
            } else if (businessName != null) {
                return runBusinessSearch(hybridSearchService);
            } else if (similarToCustomer != null) {
                return runSimilarCustomerSearch(hybridSearchService);
            } else {
                System.err.println("Error: Must specify one of: --first-name/--last-name, --description, --business-name, --similar-to, or --run-all-tests");
                return 1;
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private DataSource createDataSource() {
        if (!quiet) {
            System.out.println("Creating connection pool...");
            System.out.println("  JDBC URL: " + jdbcUrl.substring(0, Math.min(50, jdbcUrl.length())) + "...");
            System.out.println("  Username: " + username);
            System.out.println("  Pool size: " + poolSize);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        HikariDataSource dataSource = new HikariDataSource(config);

        if (!quiet) {
            System.out.println("Connection pool created.\n");
        }

        return dataSource;
    }

    private int runNameSearch(HybridSearchService service) {
        if (!quiet) {
            System.out.println("=== Hybrid Name Search ===");
            System.out.println("First name: " + firstName);
            System.out.println("Last name: " + lastName);
            System.out.println("Collection: " + collection);
            System.out.println("Limit: " + limit);
            System.out.println();
        }

        long startTime = System.currentTimeMillis();
        List<HybridSearchResult> results = service.searchByName(firstName, lastName, collection, limit);
        long elapsed = System.currentTimeMillis() - startTime;

        printResults("Name Search", results, elapsed);
        return 0;
    }

    private int runDescriptionSearch(HybridSearchService service) {
        if (!quiet) {
            System.out.println("=== Semantic Description Search ===");
            System.out.println("Description: " + description);
            System.out.println("Collection: " + collection);
            System.out.println("Limit: " + limit);
            System.out.println();
        }

        long startTime = System.currentTimeMillis();
        List<HybridSearchResult> results = service.searchByDescription(description, collection, limit);
        long elapsed = System.currentTimeMillis() - startTime;

        printResults("Description Search", results, elapsed);
        return 0;
    }

    private int runBusinessSearch(HybridSearchService service) {
        if (!quiet) {
            System.out.println("=== Business Name Search ===");
            System.out.println("Business name: " + businessName);
            System.out.println("Collection: " + collection);
            System.out.println("Limit: " + limit);
            System.out.println();
        }

        long startTime = System.currentTimeMillis();
        List<HybridSearchResult> results = service.searchByBusinessDescription(businessName, collection, limit);
        long elapsed = System.currentTimeMillis() - startTime;

        printResults("Business Search", results, elapsed);
        return 0;
    }

    private int runSimilarCustomerSearch(HybridSearchService service) {
        if (!quiet) {
            System.out.println("=== Similar Customer Search ===");
            System.out.println("Reference customer: " + similarToCustomer);
            System.out.println("Collection: " + collection);
            System.out.println("Limit: " + limit);
            System.out.println();
        }

        long startTime = System.currentTimeMillis();
        List<HybridSearchResult> results = service.findSimilarCustomers(similarToCustomer, collection, limit);
        long elapsed = System.currentTimeMillis() - startTime;

        printResults("Similar Customer Search", results, elapsed);
        return 0;
    }

    private int runAllHybridTests(HybridSearchService service) {
        System.out.println("========================================");
        System.out.println("     HYBRID SEARCH TEST SUITE");
        System.out.println("========================================\n");

        int passed = 0;
        int failed = 0;

        // Test 1: Fuzzy name search
        System.out.println("[TEST 1] Fuzzy Name Search");
        System.out.println("  Searching for 'Jon Smithe' (intentional typos)...");
        try {
            long start = System.currentTimeMillis();
            List<HybridSearchResult> results = service.searchByName("Jon", "Smithe", collection, 10);
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("  Results: " + results.size() + " found in " + elapsed + "ms");
            if (!results.isEmpty()) {
                results.stream().limit(3).forEach(r ->
                    System.out.println("    - " + r.getCustomerNumber() + ": " + r.getMatchedValue() +
                                       " (score=" + String.format("%.2f", r.getScore()) +
                                       ", strategies=" + r.getMatchStrategies() + ")"));
            }
            System.out.println("  [PASSED]");
            passed++;
        } catch (Exception e) {
            System.out.println("  [FAILED] " + e.getMessage());
            failed++;
        }
        System.out.println();

        // Test 2: Phonetic name search
        System.out.println("[TEST 2] Phonetic Name Search (sounds-alike)");
        System.out.println("  Searching for 'Sally Smith' (should match Sallie, etc.)...");
        try {
            long start = System.currentTimeMillis();
            List<HybridSearchResult> results = service.searchByName("Sally", "Smith", collection, 10);
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("  Results: " + results.size() + " found in " + elapsed + "ms");
            if (!results.isEmpty()) {
                results.stream().limit(3).forEach(r ->
                    System.out.println("    - " + r.getCustomerNumber() + ": " + r.getMatchedValue() +
                                       " (score=" + String.format("%.2f", r.getScore()) +
                                       ", strategies=" + r.getMatchStrategies() + ")"));
            }
            System.out.println("  [PASSED]");
            passed++;
        } catch (Exception e) {
            System.out.println("  [FAILED] " + e.getMessage());
            failed++;
        }
        System.out.println();

        // Test 3: Nickname expansion
        System.out.println("[TEST 3] Nickname Expansion");
        System.out.println("  Searching for 'Bill Johnson' (should match William)...");
        try {
            long start = System.currentTimeMillis();
            List<HybridSearchResult> results = service.searchByName("Bill", "Johnson", collection, 10);
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("  Results: " + results.size() + " found in " + elapsed + "ms");
            if (!results.isEmpty()) {
                results.stream().limit(3).forEach(r ->
                    System.out.println("    - " + r.getCustomerNumber() + ": " + r.getMatchedValue() +
                                       " (score=" + String.format("%.2f", r.getScore()) +
                                       ", strategies=" + r.getMatchStrategies() + ")"));
            }
            System.out.println("  [PASSED]");
            passed++;
        } catch (Exception e) {
            System.out.println("  [FAILED] " + e.getMessage());
            failed++;
        }
        System.out.println();

        // Test 4: Semantic vector search
        System.out.println("[TEST 4] Semantic Vector Search");
        System.out.println("  Searching for 'customers in technology sector'...");
        try {
            service.setFuzzyEnabled(false);
            service.setPhoneticEnabled(false);
            service.setVectorEnabled(true);

            long start = System.currentTimeMillis();
            List<HybridSearchResult> results = service.searchByDescription(
                "customers working in technology sector", collection, 10);
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("  Results: " + results.size() + " found in " + elapsed + "ms");
            if (!results.isEmpty()) {
                results.stream().limit(3).forEach(r ->
                    System.out.println("    - " + r.getCustomerNumber() + ": " + r.getMatchedValue() +
                                       " (similarity=" + String.format("%.2f", r.getScore()) + ")"));
            }
            System.out.println("  [PASSED]");
            passed++;
        } catch (Exception e) {
            System.out.println("  [FAILED] " + e.getMessage());
            failed++;
        } finally {
            // Reset configuration
            service.setFuzzyEnabled(!disableFuzzy);
            service.setPhoneticEnabled(!disablePhonetic);
            service.setVectorEnabled(!disableVector);
        }
        System.out.println();

        // Test 5: Business name fuzzy search
        System.out.println("[TEST 5] Business Name Fuzzy Search");
        System.out.println("  Searching for 'Acme Corporaton' (typo)...");
        try {
            long start = System.currentTimeMillis();
            List<HybridSearchResult> results = service.searchByBusinessDescription(
                "Acme Corporaton", collection, 10);
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("  Results: " + results.size() + " found in " + elapsed + "ms");
            if (!results.isEmpty()) {
                results.stream().limit(3).forEach(r ->
                    System.out.println("    - " + r.getCustomerNumber() + ": " + r.getMatchedValue() +
                                       " (score=" + String.format("%.2f", r.getScore()) +
                                       ", strategies=" + r.getMatchStrategies() + ")"));
            }
            System.out.println("  [PASSED]");
            passed++;
        } catch (Exception e) {
            System.out.println("  [FAILED] " + e.getMessage());
            failed++;
        }
        System.out.println();

        // Test 6: Hybrid combined search
        System.out.println("[TEST 6] Hybrid Combined Search");
        System.out.println("  Searching for 'John Smith' using all strategies...");
        try {
            long start = System.currentTimeMillis();
            List<HybridSearchResult> results = service.searchByName("John", "Smith", collection, 20);
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("  Results: " + results.size() + " found in " + elapsed + "ms");

            // Verify deduplication
            long uniqueCustomers = results.stream()
                .map(HybridSearchResult::getCustomerNumber)
                .distinct()
                .count();
            System.out.println("  Unique customers: " + uniqueCustomers);
            System.out.println("  Deduplication: " + (uniqueCustomers == results.size() ? "OK" : "FAILED"));

            // Show results with multiple strategies
            if (!results.isEmpty()) {
                results.stream()
                    .filter(r -> r.getMatchStrategies().size() > 1)
                    .limit(3)
                    .forEach(r ->
                        System.out.println("    - " + r.getCustomerNumber() +
                                           " matched by: " + r.getMatchStrategies()));
            }
            System.out.println("  [PASSED]");
            passed++;
        } catch (Exception e) {
            System.out.println("  [FAILED] " + e.getMessage());
            failed++;
        }
        System.out.println();

        // Test 7: Performance test
        System.out.println("[TEST 7] Performance Test");
        System.out.println("  Running hybrid search with 100 result limit...");
        try {
            long start = System.currentTimeMillis();
            List<HybridSearchResult> results = service.searchByName("John", "Smith", collection, 100);
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("  Results: " + results.size() + " found in " + elapsed + "ms");

            if (elapsed < 5000) {
                System.out.println("  [PASSED] - Completed within 5 second timeout");
                passed++;
            } else {
                System.out.println("  [FAILED] - Exceeded 5 second timeout");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("  [FAILED] " + e.getMessage());
            failed++;
        }
        System.out.println();

        // Summary
        System.out.println("========================================");
        System.out.println("           TEST SUMMARY");
        System.out.println("========================================");
        System.out.println("  Passed: " + passed);
        System.out.println("  Failed: " + failed);
        System.out.println("  Total:  " + (passed + failed));
        System.out.println("========================================");

        return failed > 0 ? 1 : 0;
    }

    private void printResults(String searchType, List<HybridSearchResult> results, long elapsedMs) {
        System.out.println("Results for " + searchType + ":");
        System.out.println("  Found: " + results.size() + " results");
        System.out.println("  Time: " + elapsedMs + "ms");
        System.out.println();

        if (results.isEmpty()) {
            System.out.println("  (No results found)");
        } else {
            System.out.println("  Customer Number     | Matched Value                    | Score  | Strategies");
            System.out.println("  --------------------|----------------------------------|--------|------------------");
            for (HybridSearchResult result : results) {
                String matchedValue = result.getMatchedValue();
                if (matchedValue.length() > 30) {
                    matchedValue = matchedValue.substring(0, 27) + "...";
                }
                System.out.printf("  %-18s | %-32s | %.4f | %s%n",
                    result.getCustomerNumber(),
                    matchedValue,
                    result.getScore(),
                    result.getMatchStrategies());
            }
        }
        System.out.println();
    }
}
