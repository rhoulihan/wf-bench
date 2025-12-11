package com.wf.benchmark.command;

import com.wf.benchmark.search.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
        String effectiveUrl = jdbcUrl;
        String effectiveUsername = username;
        String effectivePassword = password;

        // Priority: explicit -u/-p options > embedded credentials in URL
        // Only parse embedded credentials if -u/-p were NOT provided
        if (username == null && password == null) {
            // Try to parse embedded credentials from Oracle thin URL: jdbc:oracle:thin:user/pass@...
            if (jdbcUrl.startsWith("jdbc:oracle:thin:") && jdbcUrl.contains("@")) {
                String afterThin = jdbcUrl.substring("jdbc:oracle:thin:".length());
                int atIndex = afterThin.indexOf("@");
                if (atIndex > 0) {
                    String credsPart = afterThin.substring(0, atIndex);
                    int slashIndex = credsPart.indexOf("/");
                    if (slashIndex > 0) {
                        // Credentials are embedded - extract them
                        effectiveUsername = credsPart.substring(0, slashIndex);
                        effectivePassword = credsPart.substring(slashIndex + 1);
                        // Remove credentials from URL for HikariCP (it will add them separately)
                        effectiveUrl = "jdbc:oracle:thin:@" + afterThin.substring(atIndex + 1);
                        if (!quiet) {
                            System.out.println("Detected embedded credentials in JDBC URL");
                        }
                    }
                }
            }
        } else {
            // Explicit credentials provided - ensure URL doesn't have embedded creds
            if (jdbcUrl.startsWith("jdbc:oracle:thin:") && !jdbcUrl.startsWith("jdbc:oracle:thin:@")) {
                // URL might have embedded creds, strip them and use only -u/-p values
                int atIndex = jdbcUrl.indexOf("@");
                if (atIndex > 0) {
                    effectiveUrl = "jdbc:oracle:thin:@" + jdbcUrl.substring(atIndex + 1);
                    if (!quiet) {
                        System.out.println("Using explicit -u/-p credentials (ignoring embedded URL credentials)");
                    }
                }
            }
        }

        if (!quiet) {
            System.out.println("Creating connection pool...");
            System.out.println("  JDBC URL: " + effectiveUrl.substring(0, Math.min(50, effectiveUrl.length())) + "...");
            System.out.println("  Username: " + effectiveUsername);
            System.out.println("  Pool size: " + poolSize);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(effectiveUrl);
        config.setUsername(effectiveUsername);
        config.setPassword(effectivePassword);
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

    /**
     * Create Oracle Text index on the identity collection's DATA column for fuzzy search.
     * This enables CONTAINS queries with FUZZY operator for typo-tolerant text matching.
     */
    private int createOracleTextIndex(DataSource dataSource) {
        String indexName = "idx_" + collection + "_data_text";

        System.out.println("========================================");
        System.out.println("  CREATE JSON SEARCH INDEX");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Collection: " + collection);
        System.out.println("Index name: " + indexName);
        System.out.println();

        try (Connection conn = dataSource.getConnection()) {
            // Drop existing index if requested
            if (dropTextIndex) {
                System.out.println("[1/2] Dropping existing Oracle Text index...");
                try (Statement stmt = conn.createStatement()) {
                    String dropSql = "DROP INDEX " + indexName;
                    stmt.execute(dropSql);
                    System.out.println("  Dropped existing index: " + indexName);
                } catch (SQLException e) {
                    if (e.getErrorCode() == 1418) { // ORA-01418: index does not exist
                        System.out.println("  Index does not exist (OK)");
                    } else {
                        System.out.println("  Warning: " + e.getMessage());
                    }
                }
                System.out.println();
            }

            // Create the JSON Search Index
            // For Oracle 23ai/AJD with native JSON columns, use JSON Search Index
            // This enables full-text search with JSON_TEXTCONTAINS()
            System.out.println((dropTextIndex ? "[2/2]" : "[1/1]") + " Creating JSON Search Index...");
            System.out.println();

            // JSON Search Index for full-text search on JSON data
            // Supports JSON_TEXTCONTAINS() for fuzzy/text search operations
            String createSql = String.format(
                "CREATE SEARCH INDEX %s ON %s(DATA) FOR JSON",
                indexName, collection);

            System.out.println("  SQL: " + createSql);
            System.out.println();

            long startTime = System.currentTimeMillis();
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createSql);
            }
            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("  Index created successfully!");
            System.out.println("  Time: " + elapsed + "ms");
            System.out.println();
            System.out.println("========================================");
            System.out.println("  JSON Search Index is now available.");
            System.out.println("  Use JSON_TEXTCONTAINS() for text search.");
            System.out.println("========================================");

            return 0;

        } catch (SQLException e) {
            System.err.println();
            System.err.println("ERROR: Failed to create JSON Search Index");
            System.err.println("  SQL Error Code: " + e.getErrorCode());
            System.err.println("  Message: " + e.getMessage());
            System.err.println();

            if (e.getErrorCode() == 955) { // ORA-00955: name is already used
                System.err.println("  The index already exists. Use --drop-text-index to drop and recreate.");
            } else if (e.getErrorCode() == 29855) { // ORA-29855: error in executing ODCIIndexCreate
                System.err.println("  JSON Search Index creation failed.");
                System.err.println("  This feature requires Oracle 23ai or later.");
            }

            e.printStackTrace();
            return 1;
        }
    }

    /**
     * Create vector embedding column and index for semantic similarity search.
     * Requires ONNX embedding model to be loaded in the database.
     */
    private int createOracleVectorIndex(DataSource dataSource) {
        String embeddingColumn = "embedding";
        String vectorIndexName = "idx_" + collection + "_embedding";

        System.out.println("========================================");
        System.out.println("  CREATE ORACLE VECTOR INDEX");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Collection: " + collection);
        System.out.println("Embedding model: " + embeddingModel);
        System.out.println("Embedding field: " + embeddingField);
        System.out.println("Vector index: " + vectorIndexName);
        System.out.println();

        try (Connection conn = dataSource.getConnection()) {
            int step = 1;
            int totalSteps = dropVectorIndex ? 4 : 3;

            // Drop existing vector index if requested
            if (dropVectorIndex) {
                System.out.println("[" + step + "/" + totalSteps + "] Dropping existing vector index...");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DROP INDEX " + vectorIndexName);
                    System.out.println("  Dropped existing index: " + vectorIndexName);
                } catch (SQLException e) {
                    if (e.getErrorCode() == 1418) {
                        System.out.println("  Index does not exist (OK)");
                    } else {
                        System.out.println("  Warning: " + e.getMessage());
                    }
                }
                System.out.println();
                step++;
            }

            // Step 1: Add embedding column if not exists
            System.out.println("[" + step + "/" + totalSteps + "] Adding embedding column...");
            try (Statement stmt = conn.createStatement()) {
                String addColumnSql = String.format(
                    "ALTER TABLE %s ADD (%s VECTOR(384, FLOAT32))",
                    collection, embeddingColumn);
                System.out.println("  SQL: " + addColumnSql);
                stmt.execute(addColumnSql);
                System.out.println("  Column added successfully!");
            } catch (SQLException e) {
                if (e.getErrorCode() == 1430) { // ORA-01430: column already exists
                    System.out.println("  Column already exists (OK)");
                } else {
                    throw e;
                }
            }
            System.out.println();
            step++;

            // Step 2: Populate embeddings using ONNX model
            System.out.println("[" + step + "/" + totalSteps + "] Populating embeddings...");
            System.out.println("  This may take several minutes for large collections...");
            String updateSql = String.format(
                "UPDATE %s SET %s = VECTOR_EMBEDDING(%s USING json_value(DATA, '%s') as data) " +
                "WHERE %s IS NULL",
                collection, embeddingColumn, embeddingModel, embeddingField, embeddingColumn);
            System.out.println("  SQL: " + updateSql);

            long startTime = System.currentTimeMillis();
            int rowsUpdated;
            try (Statement stmt = conn.createStatement()) {
                rowsUpdated = stmt.executeUpdate(updateSql);
            }
            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("  Rows updated: " + rowsUpdated);
            System.out.println("  Time: " + elapsed + "ms");
            System.out.println();
            step++;

            // Step 3: Create vector index
            System.out.println("[" + step + "/" + totalSteps + "] Creating vector index...");
            String createIndexSql = String.format(
                "CREATE VECTOR INDEX %s ON %s(%s) " +
                "ORGANIZATION NEIGHBOR PARTITIONS " +
                "WITH DISTANCE COSINE",
                vectorIndexName, collection, embeddingColumn);
            System.out.println("  SQL: " + createIndexSql);

            startTime = System.currentTimeMillis();
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createIndexSql);
            }
            elapsed = System.currentTimeMillis() - startTime;
            System.out.println("  Vector index created successfully!");
            System.out.println("  Time: " + elapsed + "ms");
            System.out.println();

            System.out.println("========================================");
            System.out.println("  Oracle Vector index is now available.");
            System.out.println("  Semantic similarity search enabled.");
            System.out.println("========================================");

            return 0;

        } catch (SQLException e) {
            System.err.println();
            System.err.println("ERROR: Failed to create Oracle Vector index");
            System.err.println("  SQL Error Code: " + e.getErrorCode());
            System.err.println("  Message: " + e.getMessage());
            System.err.println();

            if (e.getMessage() != null && e.getMessage().contains("VECTOR_EMBEDDING")) {
                System.err.println("  ONNX embedding model may not be loaded.");
                System.err.println("  Load the model first with: ");
                System.err.println("    DBMS_VECTOR.LOAD_ONNX_MODEL(...)");
            }

            e.printStackTrace();
            return 1;
        }
    }

    /**
     * Setup all hybrid search indexes: Oracle Text + Vector.
     */
    private int setupAllHybridIndexes(DataSource dataSource) {
        System.out.println("========================================");
        System.out.println("  SETUP ALL HYBRID SEARCH INDEXES");
        System.out.println("========================================");
        System.out.println();
        System.out.println("This will create:");
        System.out.println("  1. Oracle Text index (for fuzzy search)");
        System.out.println("  2. Oracle Vector index (for semantic search)");
        System.out.println();

        // Create Oracle Text index
        System.out.println("--- PHASE 1: Oracle Text Index ---");
        int textResult = createOracleTextIndex(dataSource);
        if (textResult != 0) {
            System.err.println("Warning: Oracle Text index creation failed, continuing...");
        }

        System.out.println();

        // Create Vector index
        System.out.println("--- PHASE 2: Oracle Vector Index ---");
        int vectorResult = createOracleVectorIndex(dataSource);
        if (vectorResult != 0) {
            System.err.println("Warning: Oracle Vector index creation failed");
        }

        System.out.println();
        System.out.println("========================================");
        System.out.println("  HYBRID INDEX SETUP COMPLETE");
        System.out.println("========================================");
        System.out.println("  Oracle Text index: " + (textResult == 0 ? "OK" : "FAILED"));
        System.out.println("  Oracle Vector index: " + (vectorResult == 0 ? "OK" : "FAILED"));
        System.out.println("========================================");

        // Return success if at least one index was created
        return (textResult == 0 || vectorResult == 0) ? 0 : 1;
    }
}
