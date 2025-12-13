package com.wf.benchmark.command;

import com.wf.benchmark.search.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.HdrHistogram.Histogram;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI command for running hybrid search tests against Oracle ADB using JDBC.
 *
 * <p>Supports:
 * <ul>
 *   <li>Fuzzy text search (Oracle Text CONTAINS with FUZZY)</li>
 *   <li>Phonetic search (SOUNDEX with nickname expansion)</li>
 *   <li>Vector similarity search (Oracle AI Vector Search)</li>
 *   <li>Combined hybrid search strategies</li>
 * </ul>
 *
 * <p>Note: For UC 1-7 queries, use the new `mongo-sql` command which uses
 * MongoDB $sql operator with json_textcontains().
 */
@Command(
    name = "hybrid-search",
    description = "Run hybrid search tests (fuzzy, phonetic, vector) against Oracle ADB using SQL/JDBC",
    mixinStandardHelpOptions = true
)
public class HybridSearchCommand implements Callable<Integer> {

    @Option(names = {"-j", "--jdbc-url"}, description = "Oracle JDBC connection URL", required = true)
    private String jdbcUrl;

    @Option(names = {"-u", "--username"}, description = "Database username (optional if embedded in JDBC URL)")
    private String username;

    @Option(names = {"-p", "--password"}, description = "Database password (optional if embedded in JDBC URL)")
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

    @Option(names = {"--create-text-index"}, description = "Create Oracle Text index for fuzzy search", defaultValue = "false")
    private boolean createTextIndex;

    @Option(names = {"--create-vector-index"}, description = "Create vector embedding column and index for semantic search", defaultValue = "false")
    private boolean createVectorIndex;

    @Option(names = {"--setup-all-indexes"}, description = "Create all hybrid search indexes (text + vector)", defaultValue = "false")
    private boolean setupAllIndexes;

    @Option(names = {"--drop-text-index"}, description = "Drop existing Oracle Text index before creating", defaultValue = "false")
    private boolean dropTextIndex;

    @Option(names = {"--drop-vector-index"}, description = "Drop existing vector index before creating", defaultValue = "false")
    private boolean dropVectorIndex;

    @Option(names = {"--embedding-model"}, description = "ONNX embedding model name (must be loaded in DB)", defaultValue = "all_minilm_l6_v2")
    private String embeddingModel;

    @Option(names = {"--embedding-field"}, description = "JSON field to embed for vector search", defaultValue = "$.common.fullName")
    private String embeddingField;

    @Option(names = {"--pool-size"}, description = "Connection pool size", defaultValue = "10")
    private int poolSize;

    @Option(names = {"-q", "--quiet"}, description = "Suppress progress output", defaultValue = "false")
    private boolean quiet;

    @Option(names = {"--benchmark"}, description = "Run benchmark mode with detailed metrics", defaultValue = "false")
    private boolean benchmarkMode;

    @Option(names = {"-i", "--iterations"}, description = "Number of benchmark iterations", defaultValue = "10")
    private int iterations;

    @Option(names = {"-w", "--warmup"}, description = "Number of warmup iterations", defaultValue = "3")
    private int warmupIterations;

    @Override
    public Integer call() {
        try {
            DataSource dataSource = createDataSource();

            // Handle index creation if requested
            if (setupAllIndexes) {
                return setupAllHybridIndexes(dataSource);
            }
            if (createTextIndex) {
                return createOracleTextIndex(dataSource);
            }
            if (createVectorIndex) {
                return createOracleVectorIndex(dataSource);
            }

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

            if (benchmarkMode) {
                return runBenchmark(hybridSearchService);
            }

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
                System.err.println("\nNote: For UC 1-7 queries, use the 'mongo-sql' command instead.");
                return 1;
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private DataSource createDataSource() {
        String effectiveUrl = jdbcUrl;
        String effectiveUsername = username;
        String effectivePassword = password;

        // Priority: explicit -u/-p options > embedded credentials in URL
        if (username == null && password == null) {
            // Try to parse embedded credentials from Oracle thin URL
            if (jdbcUrl.startsWith("jdbc:oracle:thin:") && jdbcUrl.contains("@")) {
                String afterThin = jdbcUrl.substring("jdbc:oracle:thin:".length());
                int atIndex = afterThin.indexOf("@");
                if (atIndex > 0) {
                    String credsPart = afterThin.substring(0, atIndex);
                    int slashIndex = credsPart.indexOf("/");
                    if (slashIndex > 0) {
                        effectiveUsername = credsPart.substring(0, slashIndex);
                        effectivePassword = credsPart.substring(slashIndex + 1);
                        effectiveUrl = "jdbc:oracle:thin:@" + afterThin.substring(atIndex + 1);
                        if (!quiet) {
                            System.out.println("Detected embedded credentials in JDBC URL");
                        }
                    }
                }
            }
        } else {
            if (jdbcUrl.startsWith("jdbc:oracle:thin:") && !jdbcUrl.startsWith("jdbc:oracle:thin:@")) {
                int atIndex = jdbcUrl.indexOf("@");
                if (atIndex > 0) {
                    effectiveUrl = "jdbc:oracle:thin:@" + jdbcUrl.substring(atIndex + 1);
                }
            }
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(effectiveUrl);
        if (effectiveUsername != null) {
            config.setUsername(effectiveUsername);
        }
        if (effectivePassword != null) {
            config.setPassword(effectivePassword);
        }
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(2);
        config.setIdleTimeout(60000);
        config.setMaxLifetime(300000);
        config.setConnectionTimeout(30000);

        if (!quiet) {
            System.out.println("Creating connection pool to: " + effectiveUrl);
        }
        return new HikariDataSource(config);
    }

    // ==================== Index Creation ====================

    private int setupAllHybridIndexes(DataSource dataSource) {
        System.out.println("Setting up all hybrid search indexes...\n");
        int result = createOracleTextIndex(dataSource);
        if (result != 0) return result;
        return createOracleVectorIndex(dataSource);
    }

    private int createOracleTextIndex(DataSource dataSource) {
        System.out.println("Creating Oracle Text index for fuzzy search...");
        String indexName = "idx_" + collection + "_text";

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            if (dropTextIndex) {
                System.out.println("Dropping existing index if present...");
                try {
                    stmt.execute("DROP INDEX " + indexName);
                    System.out.println("Dropped existing index: " + indexName);
                } catch (SQLException e) {
                    // Index doesn't exist, continue
                }
            }

            String sql = String.format(
                "CREATE SEARCH INDEX %s ON %s(DATA) FOR JSON",
                indexName, collection);
            System.out.println("Executing: " + sql);
            stmt.execute(sql);
            System.out.println("Successfully created Oracle Text index: " + indexName);
            return 0;

        } catch (SQLException e) {
            System.err.println("Failed to create Oracle Text index: " + e.getMessage());
            return 1;
        }
    }

    private int createOracleVectorIndex(DataSource dataSource) {
        System.out.println("Creating Oracle Vector index for semantic search...");

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Add embedding column if not exists
            String addColumnSql = String.format(
                "ALTER TABLE %s ADD (name_embedding VECTOR)", collection);
            try {
                stmt.execute(addColumnSql);
                System.out.println("Added embedding column to " + collection);
            } catch (SQLException e) {
                if (e.getMessage().contains("already exists") || e.getMessage().contains("ORA-01430")) {
                    System.out.println("Embedding column already exists");
                } else {
                    throw e;
                }
            }

            // Populate embeddings
            System.out.println("Populating embeddings using model: " + embeddingModel);
            String updateSql = String.format(
                "UPDATE %s SET name_embedding = VECTOR_EMBEDDING(%s USING json_value(DATA, '%s') as data)",
                collection, embeddingModel, embeddingField);
            int updated = stmt.executeUpdate(updateSql);
            System.out.println("Updated " + updated + " rows with embeddings");

            // Create vector index
            if (dropVectorIndex) {
                try {
                    stmt.execute("DROP INDEX idx_" + collection + "_vector");
                } catch (SQLException e) {
                    // Index doesn't exist
                }
            }

            String createIndexSql = String.format(
                "CREATE VECTOR INDEX idx_%s_vector ON %s(name_embedding) " +
                "ORGANIZATION NEIGHBOR PARTITIONS WITH DISTANCE COSINE",
                collection, collection);
            stmt.execute(createIndexSql);
            System.out.println("Created vector index");
            return 0;

        } catch (SQLException e) {
            System.err.println("Failed to create vector index: " + e.getMessage());
            return 1;
        }
    }

    // ==================== Search Methods ====================

    private int runNameSearch(HybridSearchService hybridSearchService) {
        System.out.println("=== Hybrid Name Search ===");
        System.out.printf("Searching for: %s %s%n%n", firstName, lastName);

        try {
            List<HybridSearchResult> results = hybridSearchService.searchByName(
                firstName, lastName, collection, limit);
            printHybridResults(results);
            return 0;
        } catch (Exception e) {
            System.err.println("Search failed: " + e.getMessage());
            return 1;
        }
    }

    private int runDescriptionSearch(HybridSearchService hybridSearchService) {
        System.out.println("=== Semantic Description Search ===");
        System.out.printf("Searching for: %s%n%n", description);

        try {
            List<HybridSearchResult> results = hybridSearchService.searchByDescription(
                description, collection, limit);
            printHybridResults(results);
            return 0;
        } catch (Exception e) {
            System.err.println("Search failed: " + e.getMessage());
            return 1;
        }
    }

    private int runBusinessSearch(HybridSearchService hybridSearchService) {
        System.out.println("=== Business Name Search ===");
        System.out.printf("Searching for: %s%n%n", businessName);

        try {
            List<HybridSearchResult> results = hybridSearchService.searchByBusinessDescription(
                businessName, collection, limit);
            printHybridResults(results);
            return 0;
        } catch (Exception e) {
            System.err.println("Search failed: " + e.getMessage());
            return 1;
        }
    }

    private int runSimilarCustomerSearch(HybridSearchService hybridSearchService) {
        System.out.println("=== Similar Customer Search ===");
        System.out.printf("Finding customers similar to: %s%n%n", similarToCustomer);

        try {
            List<HybridSearchResult> results = hybridSearchService.findSimilarCustomers(
                similarToCustomer, collection, limit);
            printHybridResults(results);
            return 0;
        } catch (Exception e) {
            System.err.println("Search failed: " + e.getMessage());
            return 1;
        }
    }

    private int runAllHybridTests(HybridSearchService hybridSearchService) {
        System.out.println("=== Running All Hybrid Search Tests ===\n");

        List<TestResult> results = new ArrayList<>();

        // Test fuzzy name search
        results.add(runTest("Fuzzy Name Search (exact)", () ->
            hybridSearchService.searchByName("John", "Smith", collection, limit)));

        results.add(runTest("Fuzzy Name Search (typo)", () ->
            hybridSearchService.searchByName("Jon", "Smyth", collection, limit)));

        // Test phonetic search
        results.add(runTest("Phonetic Name Search", () ->
            hybridSearchService.searchByName("Jon", "Smythe", collection, limit)));

        // Test business search
        results.add(runTest("Business Name Search", () ->
            hybridSearchService.searchByBusinessDescription("Acme Corp", collection, limit)));

        // Print summary
        printTestSummary(results);
        return 0;
    }

    private int runBenchmark(HybridSearchService hybridSearchService) {
        System.out.println("=== Hybrid Search Benchmark ===\n");

        String[][] testCases = {
            {"John", "Smith"},
            {"Jane", "Doe"},
            {"Bob", "Johnson"},
            {"Mary", "Williams"},
            {"Mike", "Brown"}
        };

        Histogram latencyHistogram = new Histogram(3600000000L, 3);
        int successCount = 0;

        // Warmup
        System.out.printf("Warming up (%d iterations)...%n", warmupIterations);
        for (int i = 0; i < warmupIterations; i++) {
            try {
                String[] tc = testCases[i % testCases.length];
                hybridSearchService.searchByName(tc[0], tc[1], collection, limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Benchmark
        System.out.printf("Running benchmark (%d iterations)...%n%n", iterations);
        for (int i = 0; i < iterations; i++) {
            String[] tc = testCases[i % testCases.length];
            long startNanos = System.nanoTime();
            try {
                hybridSearchService.searchByName(tc[0], tc[1], collection, limit);
                long durationNanos = System.nanoTime() - startNanos;
                latencyHistogram.recordValue(durationNanos / 1000); // microseconds
                successCount++;
            } catch (Exception e) {
                System.err.printf("Iteration %d failed: %s%n", i, e.getMessage());
            }
        }

        // Results
        double avgMs = latencyHistogram.getMean() / 1000.0;
        double p50Ms = latencyHistogram.getValueAtPercentile(50.0) / 1000.0;
        double p95Ms = latencyHistogram.getValueAtPercentile(95.0) / 1000.0;
        double p99Ms = latencyHistogram.getValueAtPercentile(99.0) / 1000.0;

        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║       BENCHMARK RESULTS              ║");
        System.out.println("╠══════════════════════════════════════╣");
        System.out.printf("║ Iterations: %d/%d successful%n", successCount, iterations);
        System.out.printf("║ Average:    %.2f ms%n", avgMs);
        System.out.printf("║ P50:        %.2f ms%n", p50Ms);
        System.out.printf("║ P95:        %.2f ms%n", p95Ms);
        System.out.printf("║ P99:        %.2f ms%n", p99Ms);
        System.out.printf("║ Throughput: %.1f ops/sec%n", 1000.0 / avgMs);
        System.out.println("╚══════════════════════════════════════╝");

        return 0;
    }

    // ==================== Output Helpers ====================

    private void printHybridResults(List<HybridSearchResult> results) {
        if (results.isEmpty()) {
            System.out.println("No results found.");
            return;
        }

        System.out.printf("Found %d result(s):%n%n", results.size());

        for (int i = 0; i < results.size(); i++) {
            HybridSearchResult r = results.get(i);
            System.out.printf("Result %d%n", i + 1);
            System.out.printf("  Customer: %s%n", r.getCustomerNumber());
            System.out.printf("  Matched Value: %s%n", r.getMatchedValue());
            System.out.printf("  Score: %.2f%n", r.getScore());
            System.out.printf("  Strategies: %s%n", r.getMatchStrategies());
            System.out.println();
        }
    }

    private TestResult runTest(String name, TestSupplier test) {
        System.out.printf("Running: %s... ", name);
        try {
            long start = System.nanoTime();
            List<HybridSearchResult> results = test.get();
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("OK (%d results, %d ms)%n", results.size(), durationMs);
            return new TestResult(name, true, results.size(), durationMs, null);
        } catch (Exception e) {
            System.out.printf("FAILED: %s%n", e.getMessage());
            return new TestResult(name, false, 0, 0, e.getMessage());
        }
    }

    private void printTestSummary(List<TestResult> results) {
        System.out.println("\n=== Test Summary ===");
        int passed = 0;
        for (TestResult r : results) {
            if (r.success) passed++;
        }
        System.out.printf("Passed: %d/%d%n", passed, results.size());
    }

    @FunctionalInterface
    private interface TestSupplier {
        List<HybridSearchResult> get() throws Exception;
    }

    private record TestResult(String name, boolean success, int resultCount, long durationMs, String error) {}
}
