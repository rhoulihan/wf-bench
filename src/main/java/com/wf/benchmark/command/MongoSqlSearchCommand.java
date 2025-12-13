package com.wf.benchmark.command;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.wf.benchmark.search.MongoSqlSearchService;
import com.wf.benchmark.search.UcSearchResult;
import org.HdrHistogram.Histogram;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;

/**
 * CLI command for running UC 1-7 searches using MongoDB $sql operator.
 *
 * <p>This command uses the MongoDB API's $sql aggregation operator to execute
 * Oracle SQL queries through the MongoDB wire protocol. Based on Oracle team guidance:
 * <ul>
 *   <li>Use json_textcontains() for text search</li>
 *   <li>Use CTE pattern for multi-collection joins</li>
 *   <li>Use DOMAIN_INDEX_SORT hint for optimized score sorting</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * # Run UC benchmark with sample data
 * ./wf-bench.sh mongo-sql -c "mongodb://..." -d admin --uc-benchmark -i 20 -w 5
 *
 * # Run individual UC-1 query
 * ./wf-bench.sh mongo-sql -c "mongodb://..." -d admin --uc1-phone 4155551234 --uc1-ssn-last4 6789
 * </pre>
 */
@Command(
    name = "mongo-sql",
    description = "Run UC 1-7 searches using MongoDB $sql operator with json_textcontains()",
    mixinStandardHelpOptions = true
)
public class MongoSqlSearchCommand implements Callable<Integer> {

    @Option(names = {"-c", "--connection-string"}, description = "MongoDB connection string", required = true)
    private String connectionString;

    @Option(names = {"-d", "--database"}, description = "Database name", defaultValue = "admin")
    private String database;

    @Option(names = {"--collection-prefix"}, description = "Collection name prefix", defaultValue = "")
    private String collectionPrefix;

    @Option(names = {"--limit"}, description = "Maximum results per query", defaultValue = "10")
    private int limit;

    @Option(names = {"-q", "--quiet"}, description = "Suppress progress output", defaultValue = "false")
    private boolean quiet;

    // UC 1-7 Benchmark options
    @Option(names = {"--uc-benchmark"}, description = "Run UC 1-7 benchmark with sample data", defaultValue = "false")
    private boolean ucBenchmark;

    @Option(names = {"-i", "--iterations"}, description = "Number of benchmark iterations", defaultValue = "20")
    private int iterations;

    @Option(names = {"-w", "--warmup"}, description = "Number of warmup iterations", defaultValue = "5")
    private int warmupIterations;

    // UC-1: Phone + SSN Last 4
    @Option(names = {"--uc1-phone"}, description = "Phone number for UC-1 query")
    private String uc1Phone;

    @Option(names = {"--uc1-ssn-last4"}, description = "SSN last 4 digits for UC-1 query")
    private String uc1SsnLast4;

    // UC-2: Phone + SSN Last 4 + Account Last 4
    @Option(names = {"--uc2-phone"}, description = "Phone number for UC-2 query")
    private String uc2Phone;

    @Option(names = {"--uc2-ssn-last4"}, description = "SSN last 4 digits for UC-2 query")
    private String uc2SsnLast4;

    @Option(names = {"--uc2-account-last4"}, description = "Account last 4 digits for UC-2 query")
    private String uc2AccountLast4;

    // UC-3: Phone + Account Last 4
    @Option(names = {"--uc3-phone"}, description = "Phone number for UC-3 query")
    private String uc3Phone;

    @Option(names = {"--uc3-account-last4"}, description = "Account last 4 digits for UC-3 query")
    private String uc3AccountLast4;

    // UC-4: Account Number + SSN Last 4
    @Option(names = {"--uc4-account"}, description = "Account number for UC-4 query")
    private String uc4Account;

    @Option(names = {"--uc4-ssn-last4"}, description = "SSN last 4 digits for UC-4 query")
    private String uc4SsnLast4;

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

    // UC-6: Email + Account Last 4
    @Option(names = {"--uc6-email"}, description = "Email for UC-6 query")
    private String uc6Email;

    @Option(names = {"--uc6-account-last4"}, description = "Account last 4 digits for UC-6 query")
    private String uc6AccountLast4;

    // UC-7: Email + Phone + Account Number
    @Option(names = {"--uc7-email"}, description = "Email for UC-7 query")
    private String uc7Email;

    @Option(names = {"--uc7-phone"}, description = "Phone number for UC-7 query")
    private String uc7Phone;

    @Option(names = {"--uc7-account"}, description = "Account number for UC-7 query")
    private String uc7Account;

    // Sample data file for benchmark
    @Option(names = {"--sample-data-file"}, description = "Path to sample data JSON file for benchmark")
    private String sampleDataFile;

    @Override
    public Integer call() {
        try (MongoClient mongoClient = MongoClients.create(connectionString)) {
            MongoSqlSearchService searchService = new MongoSqlSearchService(
                mongoClient, database, collectionPrefix);

            // Test connection
            if (!quiet) {
                System.out.println("Connecting to MongoDB...");
                mongoClient.getDatabase(database).runCommand(new org.bson.Document("ping", 1));
                System.out.println("Connection successful.\n");
            }

            // Run UC benchmark
            if (ucBenchmark) {
                return runUcBenchmark(searchService);
            }

            // Individual UC queries
            if (uc1Phone != null && uc1SsnLast4 != null) {
                return runUc1Query(searchService);
            }
            if (uc2Phone != null && uc2SsnLast4 != null && uc2AccountLast4 != null) {
                return runUc2Query(searchService);
            }
            if (uc3Phone != null && uc3AccountLast4 != null) {
                return runUc3Query(searchService);
            }
            if (uc4Account != null && uc4SsnLast4 != null) {
                return runUc4Query(searchService);
            }
            if (uc5City != null && uc5State != null && uc5Zip != null && uc5SsnLast4 != null && uc5AccountLast4 != null) {
                return runUc5Query(searchService);
            }
            if (uc6Email != null && uc6AccountLast4 != null) {
                return runUc6Query(searchService);
            }
            if (uc7Email != null && uc7Phone != null && uc7Account != null) {
                return runUc7Query(searchService);
            }

            System.err.println("Error: Must specify --uc-benchmark or provide parameters for a specific UC query");
            System.err.println("Examples:");
            System.err.println("  --uc-benchmark                           Run full UC 1-7 benchmark");
            System.err.println("  --uc1-phone 4155551234 --uc1-ssn-last4 6789   Run UC-1 query");
            return 1;

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    // ==================== Individual UC Query Methods ====================

    private int runUc1Query(MongoSqlSearchService service) {
        System.out.println("=== UC-1: Phone + SSN Last 4 ===");
        System.out.printf("Phone: %s, SSN Last 4: %s%n%n", uc1Phone, uc1SsnLast4);

        try {
            List<UcSearchResult> results = service.searchUC1(uc1Phone, uc1SsnLast4, limit);
            printResults(results);
            return 0;
        } catch (Exception e) {
            System.err.println("UC-1 query failed: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private int runUc2Query(MongoSqlSearchService service) {
        System.out.println("=== UC-2: Phone + SSN Last 4 + Account Last 4 ===");
        System.out.printf("Phone: %s, SSN Last 4: %s, Account Last 4: %s%n%n",
            uc2Phone, uc2SsnLast4, uc2AccountLast4);

        try {
            List<UcSearchResult> results = service.searchUC2(uc2Phone, uc2SsnLast4, uc2AccountLast4, limit);
            printResults(results);
            return 0;
        } catch (Exception e) {
            System.err.println("UC-2 query failed: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private int runUc3Query(MongoSqlSearchService service) {
        System.out.println("=== UC-3: Phone + Account Last 4 ===");
        System.out.printf("Phone: %s, Account Last 4: %s%n%n", uc3Phone, uc3AccountLast4);

        try {
            List<UcSearchResult> results = service.searchUC3(uc3Phone, uc3AccountLast4, limit);
            printResults(results);
            return 0;
        } catch (Exception e) {
            System.err.println("UC-3 query failed: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private int runUc4Query(MongoSqlSearchService service) {
        System.out.println("=== UC-4: Account Number + SSN Last 4 ===");
        System.out.printf("Account: %s, SSN Last 4: %s%n%n", uc4Account, uc4SsnLast4);

        try {
            List<UcSearchResult> results = service.searchUC4(uc4Account, uc4SsnLast4, limit);
            printResults(results);
            return 0;
        } catch (Exception e) {
            System.err.println("UC-4 query failed: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private int runUc5Query(MongoSqlSearchService service) {
        System.out.println("=== UC-5: City/State/ZIP + SSN Last 4 + Account Last 4 ===");
        System.out.printf("City: %s, State: %s, ZIP: %s, SSN Last 4: %s, Account Last 4: %s%n%n",
            uc5City, uc5State, uc5Zip, uc5SsnLast4, uc5AccountLast4);

        try {
            List<UcSearchResult> results = service.searchUC5(
                uc5City, uc5State, uc5Zip, uc5SsnLast4, uc5AccountLast4, limit);
            printResults(results);
            return 0;
        } catch (Exception e) {
            System.err.println("UC-5 query failed: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private int runUc6Query(MongoSqlSearchService service) {
        System.out.println("=== UC-6: Email + Account Last 4 ===");
        System.out.printf("Email: %s, Account Last 4: %s%n%n", uc6Email, uc6AccountLast4);

        try {
            List<UcSearchResult> results = service.searchUC6(uc6Email, uc6AccountLast4, limit);
            printResults(results);
            return 0;
        } catch (Exception e) {
            System.err.println("UC-6 query failed: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private int runUc7Query(MongoSqlSearchService service) {
        System.out.println("=== UC-7: Email + Phone + Account Number ===");
        System.out.printf("Email: %s, Phone: %s, Account: %s%n%n", uc7Email, uc7Phone, uc7Account);

        try {
            List<UcSearchResult> results = service.searchUC7(uc7Email, uc7Phone, uc7Account, limit);
            printResults(results);
            return 0;
        } catch (Exception e) {
            System.err.println("UC-7 query failed: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    // ==================== UC Benchmark ====================

    private int runUcBenchmark(MongoSqlSearchService service) {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║         UC 1-7 Benchmark (MongoDB $sql with json_textcontains)   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Generate sample test data (or load from file if provided)
        List<String[]> uc1Params = generateUC1Params();
        List<String[]> uc2Params = generateUC2Params();
        List<String[]> uc3Params = generateUC3Params();
        List<String[]> uc4Params = generateUC4Params();
        List<String[]> uc5Params = generateUC5Params();
        List<String[]> uc6Params = generateUC6Params();
        List<String[]> uc7Params = generateUC7Params();

        System.out.printf("Benchmark configuration:%n");
        System.out.printf("  Iterations: %d%n", iterations);
        System.out.printf("  Warmup: %d%n", warmupIterations);
        System.out.printf("  Result limit: %d%n", limit);
        System.out.printf("  Collection prefix: %s%n", collectionPrefix.isEmpty() ? "(none)" : collectionPrefix);
        System.out.println();

        // Run benchmarks for each UC
        List<BenchmarkResult> allResults = new ArrayList<>();

        allResults.add(runUcQueryBenchmark("UC-1", "Phone + SSN Last 4", uc1Params,
            p -> service.searchUC1(p[0], p[1], limit)));

        allResults.add(runUcQueryBenchmark("UC-2", "Phone + SSN + Account Last 4", uc2Params,
            p -> service.searchUC2(p[0], p[1], p[2], limit)));

        allResults.add(runUcQueryBenchmark("UC-3", "Phone + Account Last 4", uc3Params,
            p -> service.searchUC3(p[0], p[1], limit)));

        allResults.add(runUcQueryBenchmark("UC-4", "Account + SSN Last 4", uc4Params,
            p -> service.searchUC4(p[0], p[1], limit)));

        allResults.add(runUcQueryBenchmark("UC-5", "City/State/ZIP + SSN + Account", uc5Params,
            p -> service.searchUC5(p[0], p[1], p[2], p[3], p[4], limit)));

        allResults.add(runUcQueryBenchmark("UC-6", "Email + Account Last 4", uc6Params,
            p -> service.searchUC6(p[0], p[1], limit)));

        allResults.add(runUcQueryBenchmark("UC-7", "Email + Phone + Account", uc7Params,
            p -> service.searchUC7(p[0], p[1], p[2], limit)));

        // Print summary
        printBenchmarkSummary(allResults);

        return 0;
    }

    private BenchmarkResult runUcQueryBenchmark(String ucName, String description,
            List<String[]> params, UcQueryExecutor executor) {
        System.out.printf("Running %s: %s%n", ucName, description);

        Histogram latencyHistogram = new Histogram(3600000000L, 3);
        int successCount = 0;
        int totalResults = 0;
        Random random = new Random(42); // Deterministic for reproducibility

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            try {
                String[] p = params.get(random.nextInt(params.size()));
                executor.execute(p);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Benchmark iterations
        for (int i = 0; i < iterations; i++) {
            String[] p = params.get(random.nextInt(params.size()));
            long startNanos = System.nanoTime();
            try {
                List<UcSearchResult> results = executor.execute(p);
                long durationNanos = System.nanoTime() - startNanos;
                latencyHistogram.recordValue(durationNanos / 1000); // Record in microseconds
                successCount++;
                totalResults += results.size();
            } catch (Exception e) {
                if (!quiet) {
                    System.err.printf("  [%s] Query failed: %s%n", ucName, e.getMessage());
                }
            }
        }

        BenchmarkResult result = new BenchmarkResult(ucName, description, latencyHistogram,
            successCount, iterations, totalResults);

        System.out.printf("  Completed: %d/%d successful, avg %.2f ms, p95 %.2f ms%n",
            successCount, iterations, result.avgLatencyMs, result.p95LatencyMs);

        return result;
    }

    @FunctionalInterface
    private interface UcQueryExecutor {
        List<UcSearchResult> execute(String[] params);
    }

    // ==================== Sample Data Generation ====================

    private List<String[]> generateUC1Params() {
        // Phone + SSN Last 4
        List<String[]> params = new ArrayList<>();
        Random r = new Random(1);
        for (int i = 0; i < 100; i++) {
            String phone = String.format("%d%07d", 415 + r.nextInt(10), r.nextInt(10000000));
            String ssn4 = String.format("%04d", r.nextInt(10000));
            params.add(new String[]{phone, ssn4});
        }
        return params;
    }

    private List<String[]> generateUC2Params() {
        // Phone + SSN Last 4 + Account Last 4
        List<String[]> params = new ArrayList<>();
        Random r = new Random(2);
        for (int i = 0; i < 100; i++) {
            String phone = String.format("%d%07d", 415 + r.nextInt(10), r.nextInt(10000000));
            String ssn4 = String.format("%04d", r.nextInt(10000));
            String acct4 = String.format("%04d", r.nextInt(10000));
            params.add(new String[]{phone, ssn4, acct4});
        }
        return params;
    }

    private List<String[]> generateUC3Params() {
        // Phone + Account Last 4
        List<String[]> params = new ArrayList<>();
        Random r = new Random(3);
        for (int i = 0; i < 100; i++) {
            String phone = String.format("%d%07d", 415 + r.nextInt(10), r.nextInt(10000000));
            String acct4 = String.format("%04d", r.nextInt(10000));
            params.add(new String[]{phone, acct4});
        }
        return params;
    }

    private List<String[]> generateUC4Params() {
        // Account Number + SSN Last 4
        // Note: Must use Math.abs() to avoid negative numbers which cause Oracle Text
        // parser errors (DRG-50901) because '-' is interpreted as NOT operator
        List<String[]> params = new ArrayList<>();
        Random r = new Random(4);
        for (int i = 0; i < 100; i++) {
            String acct = String.format("%010d", Math.abs(r.nextLong() % 10000000000L));
            String ssn4 = String.format("%04d", r.nextInt(10000));
            params.add(new String[]{acct, ssn4});
        }
        return params;
    }

    private List<String[]> generateUC5Params() {
        // City, State, ZIP, SSN Last 4, Account Last 4
        String[] cities = {"New York", "Los Angeles", "Chicago", "Houston", "Phoenix",
            "Philadelphia", "San Antonio", "San Diego", "Dallas", "San Jose"};
        String[] states = {"NY", "CA", "IL", "TX", "AZ", "PA", "TX", "CA", "TX", "CA"};
        String[] zips = {"10001", "90001", "60601", "77001", "85001", "19101", "78201", "92101", "75201", "95101"};

        List<String[]> params = new ArrayList<>();
        Random r = new Random(5);
        for (int i = 0; i < 100; i++) {
            int idx = r.nextInt(cities.length);
            String ssn4 = String.format("%04d", r.nextInt(10000));
            String acct4 = String.format("%04d", r.nextInt(10000));
            params.add(new String[]{cities[idx], states[idx], zips[idx], ssn4, acct4});
        }
        return params;
    }

    private List<String[]> generateUC6Params() {
        // Email + Account Last 4
        String[] domains = {"gmail.com", "yahoo.com", "outlook.com", "hotmail.com", "example.com"};
        List<String[]> params = new ArrayList<>();
        Random r = new Random(6);
        for (int i = 0; i < 100; i++) {
            String email = String.format("user%d@%s", r.nextInt(10000), domains[r.nextInt(domains.length)]);
            String acct4 = String.format("%04d", r.nextInt(10000));
            params.add(new String[]{email, acct4});
        }
        return params;
    }

    private List<String[]> generateUC7Params() {
        // Email + Phone + Account Number
        // Note: Must use Math.abs() to avoid negative numbers which cause Oracle Text
        // parser errors (DRG-50901) because '-' is interpreted as NOT operator
        String[] domains = {"gmail.com", "yahoo.com", "outlook.com", "hotmail.com", "example.com"};
        List<String[]> params = new ArrayList<>();
        Random r = new Random(7);
        for (int i = 0; i < 100; i++) {
            String email = String.format("user%d@%s", r.nextInt(10000), domains[r.nextInt(domains.length)]);
            String phone = String.format("%d%07d", 415 + r.nextInt(10), r.nextInt(10000000));
            String acct = String.format("%010d", Math.abs(r.nextLong() % 10000000000L));
            params.add(new String[]{email, phone, acct});
        }
        return params;
    }

    // ==================== Output Formatting ====================

    private void printResults(List<UcSearchResult> results) {
        if (results.isEmpty()) {
            System.out.println("No results found.");
            return;
        }

        System.out.printf("Found %d result(s):%n%n", results.size());

        for (int i = 0; i < results.size(); i++) {
            UcSearchResult r = results.get(i);
            System.out.printf("Result %d (Score: %d)%n", i + 1, r.getRankingScore());
            System.out.printf("  ECN: %s%n", r.getEcn());
            System.out.printf("  Company ID: %d%n", r.getCompanyId());
            System.out.printf("  Entity Type: %s%n", r.getEntityType());
            System.out.printf("  Name: %s%n", r.getName());
            if (r.getAlternateName() != null) {
                System.out.printf("  Alternate Name: %s%n", r.getAlternateName());
            }
            System.out.printf("  Tax ID: %s (%s)%n", maskTaxId(r.getTaxIdNumber()), r.getTaxIdType());
            if (r.getBirthDate() != null) {
                System.out.printf("  Birth Date: %s%n", r.getBirthDate());
            }
            if (r.getAddressLine() != null) {
                System.out.printf("  Address: %s, %s, %s %s%n",
                    r.getAddressLine(), r.getCityName(), r.getState(), r.getPostalCode());
            }
            System.out.printf("  Customer Type: %s%n", r.getCustomerType());
            System.out.println();
        }
    }

    private String maskTaxId(String taxId) {
        if (taxId == null || taxId.length() < 4) return taxId;
        return "***-**-" + taxId.substring(taxId.length() - 4);
    }

    private void printBenchmarkSummary(List<BenchmarkResult> results) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                              BENCHMARK SUMMARY                                        ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════════════╣");
        System.out.printf("║ %-6s │ %-35s │ %8s │ %8s │ %10s │ %7s ║%n",
            "UC", "Description", "Avg (ms)", "P95 (ms)", "Throughput", "Results");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════════════╣");

        for (BenchmarkResult r : results) {
            System.out.printf("║ %-6s │ %-35s │ %8.2f │ %8.2f │ %7.1f/s │ %7.1f ║%n",
                r.ucName,
                truncate(r.description, 35),
                r.avgLatencyMs,
                r.p95LatencyMs,
                r.throughputPerSecond,
                r.avgResultCount);
        }

        System.out.println("╚══════════════════════════════════════════════════════════════════════════════════════╝");
    }

    private String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }

    private static class BenchmarkResult {
        final String ucName;
        final String description;
        final double avgLatencyMs;
        final double p95LatencyMs;
        final double p99LatencyMs;
        final double throughputPerSecond;
        final double avgResultCount;
        final int successCount;
        final int totalIterations;

        BenchmarkResult(String ucName, String description, Histogram histogram,
                int successCount, int totalIterations, int totalResults) {
            this.ucName = ucName;
            this.description = description;
            this.successCount = successCount;
            this.totalIterations = totalIterations;

            if (successCount > 0) {
                this.avgLatencyMs = histogram.getMean() / 1000.0; // Convert from micros to ms
                this.p95LatencyMs = histogram.getValueAtPercentile(95.0) / 1000.0;
                this.p99LatencyMs = histogram.getValueAtPercentile(99.0) / 1000.0;
                this.throughputPerSecond = 1000.0 / avgLatencyMs;
                this.avgResultCount = (double) totalResults / successCount;
            } else {
                this.avgLatencyMs = 0;
                this.p95LatencyMs = 0;
                this.p99LatencyMs = 0;
                this.throughputPerSecond = 0;
                this.avgResultCount = 0;
            }
        }
    }
}
