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

    // UC SQL JOIN query options
    @Option(names = {"--uc-benchmark"}, description = "Run UC SQL join query benchmark", defaultValue = "false")
    private boolean ucBenchmark;

    @Option(names = {"--uc1-phone"}, description = "Phone number for UC-1 query (Phone + SSN Last 4)")
    private String uc1Phone;

    @Option(names = {"--uc1-ssn-last4"}, description = "SSN last 4 digits for UC-1 query")
    private String uc1SsnLast4;

    @Option(names = {"--uc2-phone"}, description = "Phone number for UC-2 query (Phone + SSN + Account)")
    private String uc2Phone;

    @Option(names = {"--uc2-ssn-last4"}, description = "SSN last 4 digits for UC-2 query")
    private String uc2SsnLast4;

    @Option(names = {"--uc2-account-last4"}, description = "Account last 4 digits for UC-2 query")
    private String uc2AccountLast4;

    @Option(names = {"--uc4-account"}, description = "Account number for UC-4 query (Account + SSN)")
    private String uc4Account;

    @Option(names = {"--uc4-ssn-last4"}, description = "SSN last 4 digits for UC-4 query")
    private String uc4SsnLast4;

    @Option(names = {"--uc6-email"}, description = "Email for UC-6 query (Email + Account Last 4)")
    private String uc6Email;

    @Option(names = {"--uc6-account-last4"}, description = "Account last 4 digits for UC-6 query")
    private String uc6AccountLast4;

    // UC-3: Phone + Account Last 4
    @Option(names = {"--uc3-phone"}, description = "Phone number for UC-3 query (Phone + Account Last 4)")
    private String uc3Phone;

    @Option(names = {"--uc3-account-last4"}, description = "Account last 4 digits for UC-3 query")
    private String uc3AccountLast4;

    // UC-5: City/State/ZIP + SSN Last 4 + Account Last 4
    @Option(names = {"--uc5-city"}, description = "City for UC-5 query")
    private String uc5City;

    @Option(names = {"--uc5-state"}, description = "State for UC-5 query")
    private String uc5State;

    @Option(names = {"--uc5-zip"}, description = "ZIP code for UC-5 query")
    private String uc5Zip;

    @Option(names = {"--uc5-ssn-last4"}, description = "SSN last 4 digits for UC-5 query")
    private String uc5SsnLast4;

    @Option(names = {"--uc5-account-last4"}, description = "Account last 4 digits for UC-5 query")
    private String uc5AccountLast4;

    // UC-7: Email + Phone + Account Number
    @Option(names = {"--uc7-email"}, description = "Email for UC-7 query")
    private String uc7Email;

    @Option(names = {"--uc7-phone"}, description = "Phone number for UC-7 query")
    private String uc7Phone;

    @Option(names = {"--uc7-account"}, description = "Account number for UC-7 query")
    private String uc7Account;

    // UC Search Index options
    @Option(names = {"--create-uc-search-indexes"}, description = "Create full JSON search indexes for UC queries", defaultValue = "false")
    private boolean createUcSearchIndexes;

    @Option(names = {"--drop-uc-search-indexes"}, description = "Drop UC search indexes", defaultValue = "false")
    private boolean dropUcSearchIndexes;

    @Option(names = {"--uc-search-benchmark"}, description = "Run UC search benchmark with SCORE()", defaultValue = "false")
    private boolean ucSearchBenchmark;

    @Option(names = {"--collection-prefix"}, description = "Collection/table name prefix for UC queries", defaultValue = "")
    private String collectionPrefix;

    // DBMS_SEARCH Unified Index options
    @Option(names = {"--create-unified-index"}, description = "Create unified DBMS_SEARCH index across all collections", defaultValue = "false")
    private boolean createUnifiedIndex;

    @Option(names = {"--drop-unified-index"}, description = "Drop unified DBMS_SEARCH index", defaultValue = "false")
    private boolean dropUnifiedIndex;

    @Option(names = {"--unified-uc-benchmark"}, description = "Run UC 1-7 benchmark using unified DBMS_SEARCH index", defaultValue = "false")
    private boolean unifiedUcBenchmark;

    // DBMS_SEARCH Unified Index with Views options (UC-specific fields only)
    @Option(names = {"--create-unified-index-with-views"}, description = "Create unified DBMS_SEARCH index using views with UC-specific fields only", defaultValue = "false")
    private boolean createUnifiedIndexWithViews;

    @Option(names = {"--drop-unified-index-with-views"}, description = "Drop unified DBMS_SEARCH index and UC views", defaultValue = "false")
    private boolean dropUnifiedIndexWithViews;

    @Option(names = {"--create-uc-indexes"}, description = "Create functional indexes for UC SQL JOIN queries", defaultValue = "false")
    private boolean createUcIndexes;

    @Option(names = {"--benchmark"}, description = "Run benchmark mode with detailed metrics", defaultValue = "false")
    private boolean benchmarkMode;

    @Option(names = {"-i", "--iterations"}, description = "Number of benchmark iterations", defaultValue = "10")
    private int iterations;

    @Option(names = {"-w", "--warmup"}, description = "Number of warmup iterations", defaultValue = "3")
    private int warmupIterations;

    // Sample data loader for data-driven tests
    private SampleDataLoader sampleDataLoader;

    @Override
    public Integer call() {
        try {
            DataSource dataSource = createDataSource();
            this.sampleDataLoader = new SampleDataLoader(dataSource, collection);

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

            // UC SQL Join query modes
            if (ucBenchmark) {
                SqlJoinSearchService sqlJoinService = new SqlJoinSearchService(dataSource);
                sqlJoinService.setCollectionPrefix(collectionPrefix);
                return runUcBenchmark(sqlJoinService);
            }

            // UC Search Index management
            if (createUcSearchIndexes) {
                UcSearchService ucSearchService = new UcSearchService(dataSource, collectionPrefix);
                return createUcSearchIndexesHandler(ucSearchService, dataSource);
            }
            if (dropUcSearchIndexes) {
                UcSearchService ucSearchService = new UcSearchService(dataSource, collectionPrefix);
                return dropUcSearchIndexesHandler(ucSearchService, dataSource);
            }

            // DBMS_SEARCH Unified Index management
            if (createUnifiedIndex) {
                UcSearchService ucSearchService = new UcSearchService(dataSource, collectionPrefix);
                return createUnifiedIndexHandler(ucSearchService, dataSource);
            }
            if (dropUnifiedIndex) {
                UcSearchService ucSearchService = new UcSearchService(dataSource, collectionPrefix);
                return dropUnifiedIndexHandler(ucSearchService, dataSource);
            }

            // DBMS_SEARCH Unified Index with Views management (UC-specific fields only)
            if (createUnifiedIndexWithViews) {
                UcSearchService ucSearchService = new UcSearchService(dataSource, collectionPrefix);
                return createUnifiedIndexWithViewsHandler(ucSearchService, dataSource);
            }
            if (dropUnifiedIndexWithViews) {
                UcSearchService ucSearchService = new UcSearchService(dataSource, collectionPrefix);
                return dropUnifiedIndexWithViewsHandler(ucSearchService, dataSource);
            }

            // UC Search benchmark (using SCORE())
            if (ucSearchBenchmark) {
                UcSearchService ucSearchService = new UcSearchService(dataSource, collectionPrefix);
                return runUcSearchBenchmark(ucSearchService);
            }

            // Unified DBMS_SEARCH UC benchmark
            if (unifiedUcBenchmark) {
                UcSearchService ucSearchService = new UcSearchService(dataSource, collectionPrefix);
                return runUnifiedUcBenchmark(ucSearchService);
            }

            // Individual UC queries (SqlJoinSearchService)
            if (uc1Phone != null && uc1SsnLast4 != null) {
                SqlJoinSearchService sqlJoinService = new SqlJoinSearchService(dataSource);
                sqlJoinService.setCollectionPrefix(collectionPrefix);
                return runUc1Query(sqlJoinService);
            }
            if (uc2Phone != null && uc2SsnLast4 != null && uc2AccountLast4 != null) {
                SqlJoinSearchService sqlJoinService = new SqlJoinSearchService(dataSource);
                sqlJoinService.setCollectionPrefix(collectionPrefix);
                return runUc2Query(sqlJoinService);
            }
            if (uc3Phone != null && uc3AccountLast4 != null) {
                UcSearchService ucSearchService = new UcSearchService(dataSource, collectionPrefix);
                return runUc3Query(ucSearchService);
            }
            if (uc4Account != null && uc4SsnLast4 != null) {
                SqlJoinSearchService sqlJoinService = new SqlJoinSearchService(dataSource);
                sqlJoinService.setCollectionPrefix(collectionPrefix);
                return runUc4Query(sqlJoinService);
            }
            if (uc5City != null && uc5State != null && uc5Zip != null && uc5SsnLast4 != null && uc5AccountLast4 != null) {
                UcSearchService ucSearchService = new UcSearchService(dataSource, collectionPrefix);
                return runUc5Query(ucSearchService);
            }
            if (uc6Email != null && uc6AccountLast4 != null) {
                SqlJoinSearchService sqlJoinService = new SqlJoinSearchService(dataSource);
                sqlJoinService.setCollectionPrefix(collectionPrefix);
                return runUc6Query(sqlJoinService);
            }
            if (uc7Email != null && uc7Phone != null && uc7Account != null) {
                UcSearchService ucSearchService = new UcSearchService(dataSource, collectionPrefix);
                return runUc7Query(ucSearchService);
            }

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

        // Load sample data from database
        System.out.println("Loading sample data from database...");
        String[][] sampleNames = sampleDataLoader.getSampleNamesArray(10);
        String[] sampleBusinessNames = sampleDataLoader.getSampleBusinessNamesArray(5);
        System.out.println("  Loaded " + sampleNames.length + " sample names");
        System.out.println("  Loaded " + sampleBusinessNames.length + " sample business names");
        System.out.println();

        // Use first sample name for tests (introducing typo for fuzzy test)
        String testFirstName = sampleNames[0][0];
        String testLastName = sampleNames[0][1];
        // Create typo version by replacing last char
        String typoFirstName = testFirstName.length() > 1 ?
            testFirstName.substring(0, testFirstName.length() - 1) + "e" : testFirstName;
        String typoLastName = testLastName.length() > 1 ?
            testLastName.substring(0, testLastName.length() - 1) + "e" : testLastName;

        // Use first sample business name for tests
        String testBusinessName = sampleBusinessNames[0];
        // Create typo version
        String typoBusinessName = testBusinessName.length() > 1 ?
            testBusinessName.substring(0, testBusinessName.length() - 1) + "n" : testBusinessName;

        int passed = 0;
        int failed = 0;

        // Test 1: Fuzzy name search
        System.out.println("[TEST 1] Fuzzy Name Search");
        System.out.printf("  Searching for '%s %s' (with typos)...%n", typoFirstName, typoLastName);
        try {
            long start = System.currentTimeMillis();
            List<HybridSearchResult> results = service.searchByName(typoFirstName, typoLastName, collection, 10);
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

        // Test 2: Phonetic name search (use original name - phonetic should find it)
        System.out.println("[TEST 2] Phonetic Name Search (sounds-alike)");
        System.out.printf("  Searching for '%s %s' (phonetic match)...%n", testFirstName, testLastName);
        try {
            long start = System.currentTimeMillis();
            List<HybridSearchResult> results = service.searchByName(testFirstName, testLastName, collection, 10);
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

        // Test 3: Nickname expansion - use second sample name if available
        String test3FirstName = sampleNames.length > 1 ? sampleNames[1][0] : testFirstName;
        String test3LastName = sampleNames.length > 1 ? sampleNames[1][1] : testLastName;
        System.out.println("[TEST 3] Different Name Search");
        System.out.printf("  Searching for '%s %s'...%n", test3FirstName, test3LastName);
        try {
            long start = System.currentTimeMillis();
            List<HybridSearchResult> results = service.searchByName(test3FirstName, test3LastName, collection, 10);
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
        System.out.printf("  Searching for '%s' (with typo)...%n", typoBusinessName);
        try {
            long start = System.currentTimeMillis();
            List<HybridSearchResult> results = service.searchByBusinessDescription(
                typoBusinessName, collection, 10);
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
        System.out.printf("  Searching for '%s %s' using all strategies...%n", testFirstName, testLastName);
        try {
            long start = System.currentTimeMillis();
            List<HybridSearchResult> results = service.searchByName(testFirstName, testLastName, collection, 20);
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
        System.out.printf("  Running hybrid search for '%s %s' with 100 result limit...%n", testFirstName, testLastName);
        try {
            long start = System.currentTimeMillis();
            List<HybridSearchResult> results = service.searchByName(testFirstName, testLastName, collection, 100);
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

    /**
     * Run benchmark mode with detailed metrics collection.
     * Runs multiple iterations with warmup and collects p50/p95/p99 latencies.
     */
    private int runBenchmark(HybridSearchService service) {
        System.out.println("================================================================================");
        System.out.println("                    HYBRID SEARCH BENCHMARK");
        System.out.println("================================================================================");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  Collection:         " + collection);
        System.out.println("  Iterations:         " + iterations);
        System.out.println("  Warmup iterations:  " + warmupIterations);
        System.out.println("  Fuzzy enabled:      " + !disableFuzzy);
        System.out.println("  Phonetic enabled:   " + !disablePhonetic);
        System.out.println("  Vector enabled:     " + !disableVector);
        System.out.println();

        List<BenchmarkResult> results = new ArrayList<>();

        // Load sample data from database for data-driven tests
        System.out.println("Loading sample data from database...");
        String[][] nameSearches = sampleDataLoader.getSampleNamesArray(10);
        String[] businessSearches = sampleDataLoader.getSampleBusinessNamesArray(5);
        System.out.println("  Loaded " + nameSearches.length + " sample names");
        System.out.println("  Loaded " + businessSearches.length + " sample business names");
        System.out.println();

        // Benchmark 1: Phonetic-only name search
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: phonetic_name_search");
        System.out.println("Description: Phonetic (SOUNDEX) name search");
        System.out.println("--------------------------------------------------------------------------------");
        service.setFuzzyEnabled(false);
        service.setPhoneticEnabled(true);
        service.setVectorEnabled(false);
        BenchmarkResult phoneticResult = runSearchBenchmark(service, "phonetic_name_search",
            "Phonetic (SOUNDEX) name search", nameSearches, null);
        results.add(phoneticResult);
        printBenchmarkResult(phoneticResult);

        // Benchmark 2: Fuzzy-only name search
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: fuzzy_name_search");
        System.out.println("Description: Fuzzy (JSON_TEXTCONTAINS) name search");
        System.out.println("--------------------------------------------------------------------------------");
        service.setFuzzyEnabled(true);
        service.setPhoneticEnabled(false);
        service.setVectorEnabled(false);
        BenchmarkResult fuzzyResult = runSearchBenchmark(service, "fuzzy_name_search",
            "Fuzzy (JSON_TEXTCONTAINS) name search", nameSearches, null);
        results.add(fuzzyResult);
        printBenchmarkResult(fuzzyResult);

        // Benchmark 3: Hybrid name search (phonetic + fuzzy)
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: hybrid_name_search");
        System.out.println("Description: Combined phonetic + fuzzy name search");
        System.out.println("--------------------------------------------------------------------------------");
        service.setFuzzyEnabled(!disableFuzzy);
        service.setPhoneticEnabled(!disablePhonetic);
        service.setVectorEnabled(false);
        BenchmarkResult hybridResult = runSearchBenchmark(service, "hybrid_name_search",
            "Combined phonetic + fuzzy name search", nameSearches, null);
        results.add(hybridResult);
        printBenchmarkResult(hybridResult);

        // Benchmark 4: Fuzzy business name search
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: fuzzy_business_search");
        System.out.println("Description: Fuzzy business name search");
        System.out.println("--------------------------------------------------------------------------------");
        service.setFuzzyEnabled(true);
        service.setPhoneticEnabled(false);
        service.setVectorEnabled(false);
        BenchmarkResult businessResult = runBusinessBenchmark(service, "fuzzy_business_search",
            "Fuzzy business name search", businessSearches);
        results.add(businessResult);
        printBenchmarkResult(businessResult);

        // Benchmark 5: Vector search (if enabled and available)
        if (!disableVector) {
            System.out.println("--------------------------------------------------------------------------------");
            System.out.println("Benchmark: vector_semantic_search");
            System.out.println("Description: Vector semantic similarity search");
            System.out.println("--------------------------------------------------------------------------------");
            service.setFuzzyEnabled(false);
            service.setPhoneticEnabled(false);
            service.setVectorEnabled(true);
            BenchmarkResult vectorResult = runVectorBenchmark(service, "vector_semantic_search",
                "Vector semantic similarity search");
            results.add(vectorResult);
            printBenchmarkResult(vectorResult);
        }

        // Reset service configuration
        service.setFuzzyEnabled(!disableFuzzy);
        service.setPhoneticEnabled(!disablePhonetic);
        service.setVectorEnabled(!disableVector);

        // Print summary table
        printBenchmarkSummary(results);

        return 0;
    }

    private BenchmarkResult runSearchBenchmark(HybridSearchService service, String name, String description,
                                                String[][] nameSearches, String ignored) {
        // Histogram for latencies from 1 microsecond to 60 seconds with 3 significant digits
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;

        int searchIndex = 0;

        // Warmup iterations
        for (int i = 0; i < warmupIterations; i++) {
            String[] names = nameSearches[searchIndex % nameSearches.length];
            searchIndex++;
            try {
                service.searchByName(names[0], names[1], collection, limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String[] names = nameSearches[searchIndex % nameSearches.length];
            searchIndex++;
            try {
                long start = System.nanoTime();
                List<HybridSearchResult> results = service.searchByName(names[0], names[1], collection, limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000); // Convert to microseconds
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult(name, description, histogram, iterations, warmupIterations,
            totalResults, errors);
    }

    private BenchmarkResult runBusinessBenchmark(HybridSearchService service, String name, String description,
                                                  String[] businessNames) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;

        int searchIndex = 0;

        // Warmup iterations
        for (int i = 0; i < warmupIterations; i++) {
            String businessName = businessNames[searchIndex % businessNames.length];
            searchIndex++;
            try {
                service.searchByBusinessDescription(businessName, collection, limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String businessName = businessNames[searchIndex % businessNames.length];
            searchIndex++;
            try {
                long start = System.nanoTime();
                List<HybridSearchResult> results = service.searchByBusinessDescription(businessName, collection, limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult(name, description, histogram, iterations, warmupIterations,
            totalResults, errors);
    }

    private BenchmarkResult runVectorBenchmark(HybridSearchService service, String name, String description) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;

        String[] queries = {
            "customers in banking and finance",
            "technology sector professionals",
            "healthcare industry workers",
            "retail business owners"
        };

        int searchIndex = 0;

        // Warmup iterations
        for (int i = 0; i < warmupIterations; i++) {
            String query = queries[searchIndex % queries.length];
            searchIndex++;
            try {
                service.searchByDescription(query, collection, limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String query = queries[searchIndex % queries.length];
            searchIndex++;
            try {
                long start = System.nanoTime();
                List<HybridSearchResult> results = service.searchByDescription(query, collection, limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult(name, description, histogram, iterations, warmupIterations,
            totalResults, errors);
    }

    private void printBenchmarkResult(BenchmarkResult result) {
        System.out.println();
        System.out.printf("  Iterations:      %d (+ %d warmup)%n", result.iterations, result.warmupIterations);
        System.out.printf("  Avg Latency:     %.2f ms%n", result.getAvgLatencyMs());
        System.out.printf("  Min Latency:     %.2f ms%n", result.getMinLatencyMs());
        System.out.printf("  Max Latency:     %.2f ms%n", result.getMaxLatencyMs());
        System.out.printf("  P50 Latency:     %.2f ms%n", result.getP50LatencyMs());
        System.out.printf("  P95 Latency:     %.2f ms%n", result.getP95LatencyMs());
        System.out.printf("  P99 Latency:     %.2f ms%n", result.getP99LatencyMs());
        System.out.printf("  Throughput:      %.1f ops/sec%n", result.getThroughput());
        System.out.printf("  Avg Results:     %.1f docs%n", result.getAvgResults());
        if (result.errors > 0) {
            System.out.printf("  Errors:          %d%n", result.errors);
        }
        System.out.println();
    }

    private void printBenchmarkSummary(List<BenchmarkResult> results) {
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("                         BENCHMARK SUMMARY");
        System.out.println("================================================================================");
        System.out.println();
        System.out.println("| Query | Description | Avg (ms) | P50 (ms) | P95 (ms) | P99 (ms) | Throughput | Docs |");
        System.out.println("|-------|-------------|----------|----------|----------|----------|------------|------|");

        for (BenchmarkResult result : results) {
            if (result.histogram.getTotalCount() > 0) {
                String desc = result.description;
                if (desc.length() > 35) {
                    desc = desc.substring(0, 32) + "...";
                }
                System.out.printf("| %s | %s | %.2f | %.2f | %.2f | %.2f | %.1f/s | %.1f |%n",
                    result.name,
                    desc,
                    result.getAvgLatencyMs(),
                    result.getP50LatencyMs(),
                    result.getP95LatencyMs(),
                    result.getP99LatencyMs(),
                    result.getThroughput(),
                    result.getAvgResults());
            } else {
                System.out.printf("| %s | %s | FAILED | - | - | - | - | - |%n",
                    result.name, result.description);
            }
        }

        System.out.println();
        System.out.println("================================================================================");
    }

    // ---- UC SQL JOIN Query Methods ----

    private int runUc1Query(SqlJoinSearchService service) {
        if (!quiet) {
            System.out.println("=== UC-1: Phone + SSN Last 4 (SQL JOIN) ===");
            System.out.println("Phone: " + uc1Phone);
            System.out.println("SSN Last 4: " + uc1SsnLast4);
            System.out.println("Collection prefix: " + collectionPrefix);
            System.out.println("Limit: " + limit);
            System.out.println();
        }

        long startTime = System.currentTimeMillis();
        List<SqlJoinSearchResult> results = service.searchUC1(uc1Phone, uc1SsnLast4, limit);
        long elapsed = System.currentTimeMillis() - startTime;

        printUcResults("UC-1 (Phone + SSN Last 4)", results, elapsed);
        return 0;
    }

    private int runUc2Query(SqlJoinSearchService service) {
        if (!quiet) {
            System.out.println("=== UC-2: Phone + SSN + Account (SQL JOIN) ===");
            System.out.println("Phone: " + uc2Phone);
            System.out.println("SSN Last 4: " + uc2SsnLast4);
            System.out.println("Account Last 4: " + uc2AccountLast4);
            System.out.println("Collection prefix: " + collectionPrefix);
            System.out.println("Limit: " + limit);
            System.out.println();
        }

        long startTime = System.currentTimeMillis();
        List<SqlJoinSearchResult> results = service.searchUC2(uc2Phone, uc2SsnLast4, uc2AccountLast4, limit);
        long elapsed = System.currentTimeMillis() - startTime;

        printUcResults("UC-2 (Phone + SSN + Account)", results, elapsed);
        return 0;
    }

    private int runUc4Query(SqlJoinSearchService service) {
        if (!quiet) {
            System.out.println("=== UC-4: Account + SSN (SQL JOIN) ===");
            System.out.println("Account: " + uc4Account);
            System.out.println("SSN Last 4: " + uc4SsnLast4);
            System.out.println("Collection prefix: " + collectionPrefix);
            System.out.println("Limit: " + limit);
            System.out.println();
        }

        long startTime = System.currentTimeMillis();
        List<SqlJoinSearchResult> results = service.searchUC4(uc4Account, uc4SsnLast4, limit);
        long elapsed = System.currentTimeMillis() - startTime;

        printUcResults("UC-4 (Account + SSN)", results, elapsed);
        return 0;
    }

    private int runUc6Query(SqlJoinSearchService service) {
        if (!quiet) {
            System.out.println("=== UC-6: Email + Account Last 4 (SQL JOIN) ===");
            System.out.println("Email: " + uc6Email);
            System.out.println("Account Last 4: " + uc6AccountLast4);
            System.out.println("Collection prefix: " + collectionPrefix);
            System.out.println("Limit: " + limit);
            System.out.println();
        }

        long startTime = System.currentTimeMillis();
        List<SqlJoinSearchResult> results = service.searchUC6(uc6Email, uc6AccountLast4, limit);
        long elapsed = System.currentTimeMillis() - startTime;

        printUcResults("UC-6 (Email + Account Last 4)", results, elapsed);
        return 0;
    }

    private void printUcResults(String queryType, List<SqlJoinSearchResult> results, long elapsedMs) {
        System.out.println("Results for " + queryType + ":");
        System.out.println("  Found: " + results.size() + " results");
        System.out.println("  Time: " + elapsedMs + "ms");
        System.out.println();

        if (results.isEmpty()) {
            System.out.println("  (No results found)");
        } else {
            System.out.println("  Customer Number     | Full Name                        | Phone          | SSN Last4 | Account");
            System.out.println("  --------------------|----------------------------------|----------------|-----------|----------");
            for (SqlJoinSearchResult result : results) {
                String fullName = result.getFullName() != null ? result.getFullName() : "";
                if (fullName.length() > 30) {
                    fullName = fullName.substring(0, 27) + "...";
                }
                String phone = result.getPhoneNumber() != null ? result.getPhoneNumber() : "";
                String ssnLast4 = result.getSsnLast4() != null ? result.getSsnLast4() : "";
                String account = result.getAccountNumber() != null ? result.getAccountNumber() :
                    (result.getAccountNumberLast4() != null ? "****" + result.getAccountNumberLast4() : "");

                System.out.printf("  %-18s | %-32s | %-14s | %-9s | %s%n",
                    result.getCustomerNumber(),
                    fullName,
                    phone,
                    ssnLast4,
                    account);
            }
        }
        System.out.println();
    }

    /**
     * Run UC SQL JOIN query benchmark with detailed metrics.
     * Tests all UC scenarios (UC-1, UC-2, UC-4, UC-6) with data-driven parameter generation.
     */
    private int runUcBenchmark(SqlJoinSearchService service) {
        System.out.println("================================================================================");
        System.out.println("                    UC SQL JOIN QUERY BENCHMARK");
        System.out.println("================================================================================");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  Collection prefix:  " + collectionPrefix);
        System.out.println("  Iterations:         " + iterations);
        System.out.println("  Warmup iterations:  " + warmupIterations);
        System.out.println("  Result limit:       " + limit);
        System.out.println();

        List<BenchmarkResult> results = new ArrayList<>();

        // Load sample data for UC queries from database
        System.out.println("Loading sample data from database for UC queries...");
        String[][] ucParams = sampleDataLoader.getUcQueryParametersArray(20);
        System.out.println("  Loaded " + ucParams.length + " sample parameter sets");
        System.out.println();

        // UC-1: Phone + SSN Last 4
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: uc1_phone_ssn_last4_sql");
        System.out.println("Description: UC-1 SQL JOIN: Phone + SSN Last 4");
        System.out.println("--------------------------------------------------------------------------------");
        BenchmarkResult uc1Result = runUc1Benchmark(service, ucParams);
        results.add(uc1Result);
        printBenchmarkResult(uc1Result);

        // UC-2: Phone + SSN + Account
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: uc2_phone_ssn_account_sql");
        System.out.println("Description: UC-2 SQL JOIN: Phone + SSN + Account (3-way)");
        System.out.println("--------------------------------------------------------------------------------");
        BenchmarkResult uc2Result = runUc2Benchmark(service, ucParams);
        results.add(uc2Result);
        printBenchmarkResult(uc2Result);

        // UC-4: Account + SSN
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: uc4_account_ssn_sql");
        System.out.println("Description: UC-4 SQL JOIN: Account + SSN");
        System.out.println("--------------------------------------------------------------------------------");
        BenchmarkResult uc4Result = runUc4Benchmark(service, ucParams);
        results.add(uc4Result);
        printBenchmarkResult(uc4Result);

        // UC-6: Email + Account Last 4
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: uc6_email_account_sql");
        System.out.println("Description: UC-6 SQL JOIN: Email + Account Last 4");
        System.out.println("--------------------------------------------------------------------------------");
        BenchmarkResult uc6Result = runUc6Benchmark(service, ucParams);
        results.add(uc6Result);
        printBenchmarkResult(uc6Result);

        // Print summary
        printBenchmarkSummary(results);

        return 0;
    }

    private BenchmarkResult runUc1Benchmark(SqlJoinSearchService service, String[][] params) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;
        int paramIndex = 0;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                // params: [phone, ssnLast4, accountLast4, email, accountNumber]
                service.searchUC1(p[0], p[1], limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                long start = System.nanoTime();
                List<SqlJoinSearchResult> results = service.searchUC1(p[0], p[1], limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult("uc1_sql_join", "UC-1 SQL JOIN: Phone + SSN Last 4",
            histogram, iterations, warmupIterations, totalResults, errors);
    }

    private BenchmarkResult runUc2Benchmark(SqlJoinSearchService service, String[][] params) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;
        int paramIndex = 0;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                // params: [phone, ssnLast4, accountLast4, email, accountNumber]
                service.searchUC2(p[0], p[1], p[2], limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                long start = System.nanoTime();
                List<SqlJoinSearchResult> results = service.searchUC2(p[0], p[1], p[2], limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult("uc2_sql_join", "UC-2 SQL JOIN: Phone + SSN + Account",
            histogram, iterations, warmupIterations, totalResults, errors);
    }

    private BenchmarkResult runUc4Benchmark(SqlJoinSearchService service, String[][] params) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;
        int paramIndex = 0;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                // params: [phone, ssnLast4, accountLast4, email, accountNumber]
                service.searchUC4(p[4], p[1], limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                long start = System.nanoTime();
                List<SqlJoinSearchResult> results = service.searchUC4(p[4], p[1], limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult("uc4_sql_join", "UC-4 SQL JOIN: Account + SSN",
            histogram, iterations, warmupIterations, totalResults, errors);
    }

    private BenchmarkResult runUc6Benchmark(SqlJoinSearchService service, String[][] params) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;
        int paramIndex = 0;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                // params: [phone, ssnLast4, accountLast4, email, accountNumber]
                service.searchUC6(p[3], p[2], limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                long start = System.nanoTime();
                List<SqlJoinSearchResult> results = service.searchUC6(p[3], p[2], limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult("uc6_sql_join", "UC-6 SQL JOIN: Email + Account Last 4",
            histogram, iterations, warmupIterations, totalResults, errors);
    }

    // ---- UC Search (SCORE()) Query Methods ----

    private int runUc3Query(UcSearchService service) {
        if (!quiet) {
            System.out.println("=== UC-3: Phone + Account Last 4 (SCORE()) ===");
            System.out.println("Phone: " + uc3Phone);
            System.out.println("Account Last 4: " + uc3AccountLast4);
            System.out.println("Collection prefix: " + collectionPrefix);
            System.out.println("Limit: " + limit);
            System.out.println();
        }

        long startTime = System.currentTimeMillis();
        List<UcSearchResult> results = service.searchUC3(uc3Phone, uc3AccountLast4, limit);
        long elapsed = System.currentTimeMillis() - startTime;

        printUcSearchResults("UC-3 (Phone + Account Last 4)", results, elapsed);
        return 0;
    }

    private int runUc5Query(UcSearchService service) {
        if (!quiet) {
            System.out.println("=== UC-5: City/State/ZIP + SSN Last 4 + Account Last 4 (SCORE()) ===");
            System.out.println("City: " + uc5City);
            System.out.println("State: " + uc5State);
            System.out.println("ZIP: " + uc5Zip);
            System.out.println("SSN Last 4: " + uc5SsnLast4);
            System.out.println("Account Last 4: " + uc5AccountLast4);
            System.out.println("Collection prefix: " + collectionPrefix);
            System.out.println("Limit: " + limit);
            System.out.println();
        }

        long startTime = System.currentTimeMillis();
        List<UcSearchResult> results = service.searchUC5(uc5City, uc5State, uc5Zip, uc5SsnLast4, uc5AccountLast4, limit);
        long elapsed = System.currentTimeMillis() - startTime;

        printUcSearchResults("UC-5 (City/State/ZIP + SSN + Account)", results, elapsed);
        return 0;
    }

    private int runUc7Query(UcSearchService service) {
        if (!quiet) {
            System.out.println("=== UC-7: Email + Phone + Account Number (SCORE()) ===");
            System.out.println("Email: " + uc7Email);
            System.out.println("Phone: " + uc7Phone);
            System.out.println("Account: " + uc7Account);
            System.out.println("Collection prefix: " + collectionPrefix);
            System.out.println("Limit: " + limit);
            System.out.println();
        }

        long startTime = System.currentTimeMillis();
        List<UcSearchResult> results = service.searchUC7(uc7Email, uc7Phone, uc7Account, limit);
        long elapsed = System.currentTimeMillis() - startTime;

        printUcSearchResults("UC-7 (Email + Phone + Account)", results, elapsed);
        return 0;
    }

    private void printUcSearchResults(String queryType, List<UcSearchResult> results, long elapsedMs) {
        System.out.println("Results for " + queryType + ":");
        System.out.println("  Found: " + results.size() + " results");
        System.out.println("  Time: " + elapsedMs + "ms");
        System.out.println();

        if (results.isEmpty()) {
            System.out.println("  (No results found)");
        } else {
            System.out.println("  Score | ECN               | Name                             | Type          | City              | CustomerType");
            System.out.println("  ------|-------------------|----------------------------------|---------------|-------------------|---------------");
            for (UcSearchResult result : results) {
                String name = result.getName() != null ? result.getName() : "";
                if (name.length() > 30) {
                    name = name.substring(0, 27) + "...";
                }
                String city = result.getCityName() != null ? result.getCityName() : "";
                if (city.length() > 15) {
                    city = city.substring(0, 12) + "...";
                }
                String entityType = result.getEntityType() != null ? result.getEntityType() : "";
                if (entityType.length() > 11) {
                    entityType = entityType.substring(0, 8) + "...";
                }
                String customerType = result.getCustomerType() != null ? result.getCustomerType() : "";

                System.out.printf("  %5d | %-17s | %-32s | %-13s | %-17s | %s%n",
                    result.getRankingScore(),
                    result.getEcn(),
                    name,
                    entityType,
                    city,
                    customerType);
            }
        }
        System.out.println();
    }

    // ---- UC Search Index Management ----

    private int createUcSearchIndexesHandler(UcSearchService service, DataSource dataSource) {
        System.out.println("========================================");
        System.out.println("  CREATE UC SEARCH INDEXES");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Collection prefix: " + collectionPrefix);
        System.out.println();

        List<String> statements = service.getCreateSearchIndexStatements();
        System.out.println("Creating the following indexes:");
        for (String stmt : statements) {
            System.out.println("  - " + stmt);
        }
        System.out.println();

        try (Connection conn = dataSource.getConnection()) {
            long startTime = System.currentTimeMillis();
            service.createSearchIndexes(conn);
            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("========================================");
            System.out.println("  UC Search indexes created successfully!");
            System.out.println("  Time: " + elapsed + "ms");
            System.out.println("========================================");

            return 0;
        } catch (Exception e) {
            System.err.println("ERROR: Failed to create UC search indexes");
            System.err.println("  Message: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private int dropUcSearchIndexesHandler(UcSearchService service, DataSource dataSource) {
        System.out.println("========================================");
        System.out.println("  DROP UC SEARCH INDEXES");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Collection prefix: " + collectionPrefix);
        System.out.println();

        List<String> statements = service.getDropSearchIndexStatements();
        System.out.println("Dropping the following indexes:");
        for (String stmt : statements) {
            System.out.println("  - " + stmt);
        }
        System.out.println();

        try (Connection conn = dataSource.getConnection()) {
            long startTime = System.currentTimeMillis();
            service.dropSearchIndexes(conn);
            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("========================================");
            System.out.println("  UC Search indexes dropped successfully!");
            System.out.println("  Time: " + elapsed + "ms");
            System.out.println("========================================");

            return 0;
        } catch (Exception e) {
            System.err.println("ERROR: Failed to drop UC search indexes");
            System.err.println("  Message: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    // ---- DBMS_SEARCH Unified Index Management ----

    private int createUnifiedIndexHandler(UcSearchService service, DataSource dataSource) {
        System.out.println("========================================");
        System.out.println("  CREATE UNIFIED DBMS_SEARCH INDEX");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Collection prefix: " + collectionPrefix);
        System.out.println("Index name: " + service.getUnifiedIndexName());
        System.out.println();

        System.out.println("Creating unified search index:");
        System.out.println("  1. " + service.getCreateUnifiedIndexStatement());
        System.out.println();
        System.out.println("Adding sources:");
        for (String stmt : service.getAddSourceStatements()) {
            System.out.println("  - " + stmt);
        }
        System.out.println();

        try (Connection conn = dataSource.getConnection()) {
            long startTime = System.currentTimeMillis();
            service.createUnifiedIndex(conn);
            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("========================================");
            System.out.println("  Unified DBMS_SEARCH index created successfully!");
            System.out.println("  Time: " + elapsed + "ms");
            System.out.println("========================================");

            return 0;
        } catch (Exception e) {
            System.err.println("ERROR: Failed to create unified DBMS_SEARCH index");
            System.err.println("  Message: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private int dropUnifiedIndexHandler(UcSearchService service, DataSource dataSource) {
        System.out.println("========================================");
        System.out.println("  DROP UNIFIED DBMS_SEARCH INDEX");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Collection prefix: " + collectionPrefix);
        System.out.println("Index name: " + service.getUnifiedIndexName());
        System.out.println();

        System.out.println("Dropping: " + service.getDropUnifiedIndexStatement());
        System.out.println();

        try (Connection conn = dataSource.getConnection()) {
            long startTime = System.currentTimeMillis();
            service.dropUnifiedIndex(conn);
            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("========================================");
            System.out.println("  Unified DBMS_SEARCH index dropped successfully!");
            System.out.println("  Time: " + elapsed + "ms");
            System.out.println("========================================");

            return 0;
        } catch (Exception e) {
            System.err.println("ERROR: Failed to drop unified DBMS_SEARCH index");
            System.err.println("  Message: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    // ---- DBMS_SEARCH Unified Index with Views Management ----

    private int createUnifiedIndexWithViewsHandler(UcSearchService service, DataSource dataSource) {
        System.out.println("========================================");
        System.out.println("  CREATE UNIFIED DBMS_SEARCH INDEX WITH UC VIEWS");
        System.out.println("========================================");
        System.out.println();
        System.out.println("This creates an optimized unified index using views that");
        System.out.println("contain only the UC-required fields, reducing index size");
        System.out.println("and improving search accuracy.");
        System.out.println();
        System.out.println("Collection prefix: " + collectionPrefix);
        System.out.println("View prefix: " + service.getUcViewPrefix());
        System.out.println("Index name: " + service.getUnifiedIndexName());
        System.out.println();

        System.out.println("Creating UC views:");
        for (String sql : service.getCreateUcViewStatements()) {
            System.out.println("  - " + sql.substring(0, Math.min(sql.length(), 80)) + "...");
        }
        System.out.println();

        System.out.println("Creating unified search index:");
        System.out.println("  " + service.getCreateUnifiedIndexStatement());
        System.out.println();

        System.out.println("Adding UC view sources:");
        for (String stmt : service.getAddUcViewSourceStatements()) {
            System.out.println("  - " + stmt);
        }
        System.out.println();

        try (Connection conn = dataSource.getConnection()) {
            long startTime = System.currentTimeMillis();
            service.createUnifiedIndexWithViews(conn);
            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("========================================");
            System.out.println("  Unified DBMS_SEARCH index with UC views created successfully!");
            System.out.println("  Time: " + elapsed + "ms");
            System.out.println("========================================");

            return 0;
        } catch (Exception e) {
            System.err.println("ERROR: Failed to create unified DBMS_SEARCH index with views");
            System.err.println("  Message: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private int dropUnifiedIndexWithViewsHandler(UcSearchService service, DataSource dataSource) {
        System.out.println("========================================");
        System.out.println("  DROP UNIFIED DBMS_SEARCH INDEX AND UC VIEWS");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Collection prefix: " + collectionPrefix);
        System.out.println("View prefix: " + service.getUcViewPrefix());
        System.out.println("Index name: " + service.getUnifiedIndexName());
        System.out.println();

        System.out.println("Dropping index: " + service.getDropUnifiedIndexStatement());
        System.out.println();

        System.out.println("Dropping UC views:");
        for (String sql : service.getDropUcViewStatements()) {
            System.out.println("  - " + sql);
        }
        System.out.println();

        try (Connection conn = dataSource.getConnection()) {
            long startTime = System.currentTimeMillis();
            service.dropUnifiedIndexWithViews(conn);
            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("========================================");
            System.out.println("  Unified DBMS_SEARCH index and UC views dropped successfully!");
            System.out.println("  Time: " + elapsed + "ms");
            System.out.println("========================================");

            return 0;
        } catch (Exception e) {
            System.err.println("ERROR: Failed to drop unified DBMS_SEARCH index and views");
            System.err.println("  Message: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    // ---- UC Search Benchmark (SCORE()) ----

    private int runUcSearchBenchmark(UcSearchService service) {
        System.out.println("================================================================================");
        System.out.println("                UC SEARCH BENCHMARK (SCORE())");
        System.out.println("================================================================================");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  Collection prefix:  " + collectionPrefix);
        System.out.println("  Iterations:         " + iterations);
        System.out.println("  Warmup iterations:  " + warmupIterations);
        System.out.println("  Result limit:       " + limit);
        System.out.println();

        List<BenchmarkResult> results = new ArrayList<>();

        // Load sample data for UC queries from database
        System.out.println("Loading sample data from database for UC queries...");
        String[][] ucParams = sampleDataLoader.getUcQueryParametersArray(20);
        System.out.println("  Loaded " + ucParams.length + " sample parameter sets");
        System.out.println();

        // UC-1: Phone + SSN Last 4 (SCORE())
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: uc1_score_search");
        System.out.println("Description: UC-1 SCORE(): Phone + SSN Last 4");
        System.out.println("--------------------------------------------------------------------------------");
        BenchmarkResult uc1Result = runUc1ScoreBenchmark(service, ucParams);
        results.add(uc1Result);
        printBenchmarkResult(uc1Result);

        // UC-2: Phone + SSN + Account (SCORE())
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: uc2_score_search");
        System.out.println("Description: UC-2 SCORE(): Phone + SSN + Account");
        System.out.println("--------------------------------------------------------------------------------");
        BenchmarkResult uc2Result = runUc2ScoreBenchmark(service, ucParams);
        results.add(uc2Result);
        printBenchmarkResult(uc2Result);

        // UC-3: Phone + Account Last 4 (SCORE())
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: uc3_score_search");
        System.out.println("Description: UC-3 SCORE(): Phone + Account Last 4");
        System.out.println("--------------------------------------------------------------------------------");
        BenchmarkResult uc3Result = runUc3ScoreBenchmark(service, ucParams);
        results.add(uc3Result);
        printBenchmarkResult(uc3Result);

        // UC-4: Account + SSN (SCORE())
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: uc4_score_search");
        System.out.println("Description: UC-4 SCORE(): Account + SSN");
        System.out.println("--------------------------------------------------------------------------------");
        BenchmarkResult uc4Result = runUc4ScoreBenchmark(service, ucParams);
        results.add(uc4Result);
        printBenchmarkResult(uc4Result);

        // UC-5: City/State/ZIP + SSN + Account (SCORE())
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: uc5_score_search");
        System.out.println("Description: UC-5 SCORE(): City/State/ZIP + SSN + Account");
        System.out.println("--------------------------------------------------------------------------------");
        BenchmarkResult uc5Result = runUc5ScoreBenchmark(service, ucParams);
        results.add(uc5Result);
        printBenchmarkResult(uc5Result);

        // UC-6: Email + Account Last 4 (SCORE())
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: uc6_score_search");
        System.out.println("Description: UC-6 SCORE(): Email + Account Last 4");
        System.out.println("--------------------------------------------------------------------------------");
        BenchmarkResult uc6Result = runUc6ScoreBenchmark(service, ucParams);
        results.add(uc6Result);
        printBenchmarkResult(uc6Result);

        // UC-7: Email + Phone + Account (SCORE())
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: uc7_score_search");
        System.out.println("Description: UC-7 SCORE(): Email + Phone + Account");
        System.out.println("--------------------------------------------------------------------------------");
        BenchmarkResult uc7Result = runUc7ScoreBenchmark(service, ucParams);
        results.add(uc7Result);
        printBenchmarkResult(uc7Result);

        // Print summary
        printBenchmarkSummary(results);

        return 0;
    }

    private BenchmarkResult runUc1ScoreBenchmark(UcSearchService service, String[][] params) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;
        int paramIndex = 0;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                service.searchUC1(p[0], p[1], limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                long start = System.nanoTime();
                List<UcSearchResult> results = service.searchUC1(p[0], p[1], limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult("uc1_score", "UC-1 SCORE(): Phone + SSN Last 4",
            histogram, iterations, warmupIterations, totalResults, errors);
    }

    private BenchmarkResult runUc2ScoreBenchmark(UcSearchService service, String[][] params) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;
        int paramIndex = 0;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                service.searchUC2(p[0], p[1], p[2], limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                long start = System.nanoTime();
                List<UcSearchResult> results = service.searchUC2(p[0], p[1], p[2], limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult("uc2_score", "UC-2 SCORE(): Phone + SSN + Account",
            histogram, iterations, warmupIterations, totalResults, errors);
    }

    private BenchmarkResult runUc3ScoreBenchmark(UcSearchService service, String[][] params) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;
        int paramIndex = 0;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                service.searchUC3(p[0], p[2], limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                long start = System.nanoTime();
                List<UcSearchResult> results = service.searchUC3(p[0], p[2], limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult("uc3_score", "UC-3 SCORE(): Phone + Account Last 4",
            histogram, iterations, warmupIterations, totalResults, errors);
    }

    private BenchmarkResult runUc4ScoreBenchmark(UcSearchService service, String[][] params) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;
        int paramIndex = 0;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                service.searchUC4(p[4], p[1], limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                long start = System.nanoTime();
                List<UcSearchResult> results = service.searchUC4(p[4], p[1], limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult("uc4_score", "UC-4 SCORE(): Account + SSN",
            histogram, iterations, warmupIterations, totalResults, errors);
    }

    private BenchmarkResult runUc5ScoreBenchmark(UcSearchService service, String[][] params) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;
        int paramIndex = 0;

        // Address parameters are loaded from database: params[5]=city, params[6]=state, params[7]=zip

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                // p[5]=city, p[6]=state, p[7]=zip, p[1]=ssnLast4, p[2]=accountLast4
                service.searchUC5(p[5], p[6], p[7], p[1], p[2], limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                long start = System.nanoTime();
                // p[5]=city, p[6]=state, p[7]=zip, p[1]=ssnLast4, p[2]=accountLast4
                List<UcSearchResult> results = service.searchUC5(p[5], p[6], p[7], p[1], p[2], limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult("uc5_score", "UC-5 SCORE(): City/State/ZIP + SSN + Account",
            histogram, iterations, warmupIterations, totalResults, errors);
    }

    private BenchmarkResult runUc6ScoreBenchmark(UcSearchService service, String[][] params) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;
        int paramIndex = 0;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                service.searchUC6(p[3], p[2], limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                long start = System.nanoTime();
                List<UcSearchResult> results = service.searchUC6(p[3], p[2], limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult("uc6_score", "UC-6 SCORE(): Email + Account Last 4",
            histogram, iterations, warmupIterations, totalResults, errors);
    }

    private BenchmarkResult runUc7ScoreBenchmark(UcSearchService service, String[][] params) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;
        int paramIndex = 0;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                service.searchUC7(p[3], p[0], p[4], limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                long start = System.nanoTime();
                List<UcSearchResult> results = service.searchUC7(p[3], p[0], p[4], limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult("uc7_score", "UC-7 SCORE(): Email + Phone + Account",
            histogram, iterations, warmupIterations, totalResults, errors);
    }

    // ========================================================================
    // UNIFIED DBMS_SEARCH BENCHMARK METHODS
    // ========================================================================

    private int runUnifiedUcBenchmark(UcSearchService service) {
        System.out.println("================================================================================");
        System.out.println("          UNIFIED DBMS_SEARCH UC BENCHMARK (DBMS_SEARCH.FIND)");
        System.out.println("================================================================================");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  Collection prefix:  " + collectionPrefix);
        System.out.println("  Iterations:         " + iterations);
        System.out.println("  Warmup iterations:  " + warmupIterations);
        System.out.println("  Result limit:       " + limit);
        System.out.println();

        List<BenchmarkResult> results = new ArrayList<>();

        // Load sample data for UC queries from database
        System.out.println("Loading sample data from database for UC queries...");
        String[][] ucParams = sampleDataLoader.getUcQueryParametersArray(20);
        System.out.println("  Loaded " + ucParams.length + " sample parameter sets");
        System.out.println();

        // UC-1: Phone + SSN Last 4 (Unified DBMS_SEARCH)
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: uc1_unified_dbms_search");
        System.out.println("Description: UC-1 Unified DBMS_SEARCH: Phone + SSN Last 4");
        System.out.println("--------------------------------------------------------------------------------");
        BenchmarkResult uc1Result = runUnifiedUc1Benchmark(service, ucParams);
        results.add(uc1Result);
        printBenchmarkResult(uc1Result);

        // UC-2: Phone + SSN + Account (Unified DBMS_SEARCH)
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: uc2_unified_dbms_search");
        System.out.println("Description: UC-2 Unified DBMS_SEARCH: Phone + SSN + Account");
        System.out.println("--------------------------------------------------------------------------------");
        BenchmarkResult uc2Result = runUnifiedUc2Benchmark(service, ucParams);
        results.add(uc2Result);
        printBenchmarkResult(uc2Result);

        // UC-3: Phone + Account Last 4 (Unified DBMS_SEARCH)
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: uc3_unified_dbms_search");
        System.out.println("Description: UC-3 Unified DBMS_SEARCH: Phone + Account Last 4");
        System.out.println("--------------------------------------------------------------------------------");
        BenchmarkResult uc3Result = runUnifiedUc3Benchmark(service, ucParams);
        results.add(uc3Result);
        printBenchmarkResult(uc3Result);

        // UC-4: Account + SSN (Unified DBMS_SEARCH)
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: uc4_unified_dbms_search");
        System.out.println("Description: UC-4 Unified DBMS_SEARCH: Account + SSN");
        System.out.println("--------------------------------------------------------------------------------");
        BenchmarkResult uc4Result = runUnifiedUc4Benchmark(service, ucParams);
        results.add(uc4Result);
        printBenchmarkResult(uc4Result);

        // UC-5: City/State/ZIP + SSN + Account (Unified DBMS_SEARCH)
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: uc5_unified_dbms_search");
        System.out.println("Description: UC-5 Unified DBMS_SEARCH: City/State/ZIP + SSN + Account");
        System.out.println("--------------------------------------------------------------------------------");
        BenchmarkResult uc5Result = runUnifiedUc5Benchmark(service, ucParams);
        results.add(uc5Result);
        printBenchmarkResult(uc5Result);

        // UC-6: Email + Account Last 4 (Unified DBMS_SEARCH)
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: uc6_unified_dbms_search");
        System.out.println("Description: UC-6 Unified DBMS_SEARCH: Email + Account Last 4");
        System.out.println("--------------------------------------------------------------------------------");
        BenchmarkResult uc6Result = runUnifiedUc6Benchmark(service, ucParams);
        results.add(uc6Result);
        printBenchmarkResult(uc6Result);

        // UC-7: Email + Phone + Account (Unified DBMS_SEARCH)
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Benchmark: uc7_unified_dbms_search");
        System.out.println("Description: UC-7 Unified DBMS_SEARCH: Email + Phone + Account");
        System.out.println("--------------------------------------------------------------------------------");
        BenchmarkResult uc7Result = runUnifiedUc7Benchmark(service, ucParams);
        results.add(uc7Result);
        printBenchmarkResult(uc7Result);

        // Print summary
        printBenchmarkSummary(results);

        return 0;
    }

    private BenchmarkResult runUnifiedUc1Benchmark(UcSearchService service, String[][] params) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;
        int paramIndex = 0;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                service.searchUnifiedUC1(p[0], p[1], limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                long start = System.nanoTime();
                List<UcSearchResult> results = service.searchUnifiedUC1(p[0], p[1], limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
                if (errors == 1) {
                    System.out.println("First error in UC1 unified: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        return new BenchmarkResult("uc1_unified", "UC-1 Unified DBMS_SEARCH: Phone + SSN Last 4",
            histogram, iterations, warmupIterations, totalResults, errors);
    }

    private BenchmarkResult runUnifiedUc2Benchmark(UcSearchService service, String[][] params) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;
        int paramIndex = 0;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                service.searchUnifiedUC2(p[0], p[1], p[2], limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                long start = System.nanoTime();
                List<UcSearchResult> results = service.searchUnifiedUC2(p[0], p[1], p[2], limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult("uc2_unified", "UC-2 Unified DBMS_SEARCH: Phone + SSN + Account",
            histogram, iterations, warmupIterations, totalResults, errors);
    }

    private BenchmarkResult runUnifiedUc3Benchmark(UcSearchService service, String[][] params) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;
        int paramIndex = 0;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                service.searchUnifiedUC3(p[0], p[2], limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                long start = System.nanoTime();
                List<UcSearchResult> results = service.searchUnifiedUC3(p[0], p[2], limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult("uc3_unified", "UC-3 Unified DBMS_SEARCH: Phone + Account Last 4",
            histogram, iterations, warmupIterations, totalResults, errors);
    }

    private BenchmarkResult runUnifiedUc4Benchmark(UcSearchService service, String[][] params) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;
        int paramIndex = 0;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                service.searchUnifiedUC4(p[4], p[1], limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                long start = System.nanoTime();
                List<UcSearchResult> results = service.searchUnifiedUC4(p[4], p[1], limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult("uc4_unified", "UC-4 Unified DBMS_SEARCH: Account + SSN",
            histogram, iterations, warmupIterations, totalResults, errors);
    }

    private BenchmarkResult runUnifiedUc5Benchmark(UcSearchService service, String[][] params) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;
        int paramIndex = 0;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                service.searchUnifiedUC5(p[5], p[6], p[7], p[1], p[2], limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                long start = System.nanoTime();
                List<UcSearchResult> results = service.searchUnifiedUC5(p[5], p[6], p[7], p[1], p[2], limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult("uc5_unified", "UC-5 Unified DBMS_SEARCH: City/State/ZIP + SSN + Account",
            histogram, iterations, warmupIterations, totalResults, errors);
    }

    private BenchmarkResult runUnifiedUc6Benchmark(UcSearchService service, String[][] params) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;
        int paramIndex = 0;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                service.searchUnifiedUC6(p[3], p[2], limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                long start = System.nanoTime();
                List<UcSearchResult> results = service.searchUnifiedUC6(p[3], p[2], limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult("uc6_unified", "UC-6 Unified DBMS_SEARCH: Email + Account Last 4",
            histogram, iterations, warmupIterations, totalResults, errors);
    }

    private BenchmarkResult runUnifiedUc7Benchmark(UcSearchService service, String[][] params) {
        Histogram histogram = new Histogram(1, 60_000_000, 3);
        long totalResults = 0;
        int errors = 0;
        int paramIndex = 0;

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                service.searchUnifiedUC7(p[3], p[0], p[4], limit);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            String[] p = params[paramIndex % params.length];
            paramIndex++;
            try {
                long start = System.nanoTime();
                List<UcSearchResult> results = service.searchUnifiedUC7(p[3], p[0], p[4], limit);
                long elapsed = System.nanoTime() - start;
                histogram.recordValue(elapsed / 1000);
                totalResults += results.size();
            } catch (Exception e) {
                errors++;
            }
        }

        return new BenchmarkResult("uc7_unified", "UC-7 Unified DBMS_SEARCH: Email + Phone + Account",
            histogram, iterations, warmupIterations, totalResults, errors);
    }

    /**
     * Internal class to hold benchmark results with latency statistics.
     */
    private static class BenchmarkResult {
        final String name;
        final String description;
        final Histogram histogram;
        final int iterations;
        final int warmupIterations;
        final long totalResults;
        final int errors;

        BenchmarkResult(String name, String description, Histogram histogram, int iterations,
                       int warmupIterations, long totalResults, int errors) {
            this.name = name;
            this.description = description;
            this.histogram = histogram;
            this.iterations = iterations;
            this.warmupIterations = warmupIterations;
            this.totalResults = totalResults;
            this.errors = errors;
        }

        double getAvgLatencyMs() {
            return histogram.getMean() / 1000.0;
        }

        double getMinLatencyMs() {
            return histogram.getMinValue() / 1000.0;
        }

        double getMaxLatencyMs() {
            return histogram.getMaxValue() / 1000.0;
        }

        double getP50LatencyMs() {
            return histogram.getValueAtPercentile(50.0) / 1000.0;
        }

        double getP95LatencyMs() {
            return histogram.getValueAtPercentile(95.0) / 1000.0;
        }

        double getP99LatencyMs() {
            return histogram.getValueAtPercentile(99.0) / 1000.0;
        }

        double getThroughput() {
            double totalTimeMs = histogram.getTotalCount() > 0 ?
                (histogram.getMean() * histogram.getTotalCount()) / 1000.0 : 0;
            return totalTimeMs > 0 ? (iterations * 1000.0) / totalTimeMs : 0;
        }

        double getAvgResults() {
            return iterations > 0 ? (double) totalResults / iterations : 0;
        }
    }
}
