package com.wf.benchmark.command;

import com.mongodb.client.MongoClient;
import com.wf.benchmark.config.ConnectionConfig;
import com.wf.benchmark.config.QueryConfig;
import com.wf.benchmark.query.IndexManager;
import com.wf.benchmark.query.QueryMetrics;
import com.wf.benchmark.query.QueryRunner;
import com.wf.benchmark.report.ConsoleReporter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name = "query",
    description = "Run query benchmarks against MongoDB collections",
    mixinStandardHelpOptions = true
)
public class QueryCommand implements Callable<Integer> {

    @Option(names = {"-c", "--connection-string"}, description = "MongoDB connection string")
    private String connectionString;

    @Option(names = {"-d", "--database"}, description = "Target database name", defaultValue = "benchmark")
    private String database;

    @Option(names = {"-f", "--config-file"}, description = "Query configuration YAML file", required = true)
    private String configFile;

    @Option(names = {"-n", "--query-name"}, description = "Run specific query by name")
    private String queryName;

    @Option(names = {"-i", "--iterations"}, description = "Iterations per query", defaultValue = "10")
    private int iterations;

    @Option(names = {"-w", "--warmup"}, description = "Warmup iterations", defaultValue = "3")
    private int warmupIterations;

    @Option(names = {"-t", "--threads"}, description = "Concurrent query threads", defaultValue = "1")
    private int threads;

    @Option(names = {"--connection-pool"}, description = "MongoDB connection pool size", defaultValue = "10")
    private int connectionPoolSize;

    @Option(names = {"--create-indexes"}, description = "Create indexes from config", defaultValue = "true")
    private boolean createIndexes;

    @Option(names = {"--drop-indexes"}, description = "Drop indexes before run", defaultValue = "false")
    private boolean dropIndexes;

    @Option(names = {"--include-explain"}, description = "Include explain plan in output", defaultValue = "false")
    private boolean includeExplain;

    @Option(names = {"-q", "--quiet"}, description = "Suppress progress output", defaultValue = "false")
    private boolean quiet;

    @Option(names = {"--output-format"}, description = "Output format: console, csv, json", defaultValue = "console")
    private String outputFormat;

    @Override
    public Integer call() {
        try {
            QueryConfig config = QueryConfig.fromYaml(configFile);

            // CLI options override config file
            if (connectionString != null) {
                config.getConnection().setConnectionString(connectionString);
            }
            config.getConnection().setDatabase(database);
            config.getConnection().setConnectionPoolSize(connectionPoolSize);

            config.getQueryExecution().setIterations(iterations);
            config.getQueryExecution().setWarmupIterations(warmupIterations);
            config.getQueryExecution().setThreads(threads);
            config.getQueryExecution().setIncludeExplainPlan(includeExplain);

            return executeQueries(config);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private int executeQueries(QueryConfig config) {
        ConsoleReporter reporter = new ConsoleReporter(quiet);

        reporter.printQueryHeader(config);

        ConnectionConfig connConfig = config.getConnection();
        try (MongoClient client = connConfig.createClient()) {

            // Test connection
            if (!quiet) {
                System.out.println("Testing connection...");
            }
            client.getDatabase(connConfig.getDatabase()).runCommand(new org.bson.Document("ping", 1));
            if (!quiet) {
                System.out.println("Connection successful.\n");
            }

            // Handle indexes
            IndexManager indexManager = new IndexManager(client, config);

            if (!quiet) {
                System.out.printf("Index config: dropIndexes=%b, createIndexes=%b, indexCount=%d%n",
                    dropIndexes, createIndexes, config.getIndexes().size());
            }

            if (dropIndexes) {
                if (!quiet) {
                    System.out.println("Dropping existing indexes...");
                }
                indexManager.dropIndexes();
            }

            if (createIndexes && !config.getIndexes().isEmpty()) {
                if (!quiet) {
                    System.out.println("Creating indexes...");
                }
                indexManager.createIndexes();
                if (!quiet) {
                    System.out.println("Indexes ready.\n");
                }
            } else if (!quiet && config.getIndexes().isEmpty()) {
                System.out.println("WARNING: No indexes defined in config file!\n");
            }

            // Filter queries if specific name provided
            List<QueryConfig.QueryDefinition> queriesToRun = config.getQueries();
            if (queryName != null) {
                queriesToRun = queriesToRun.stream()
                    .filter(q -> q.getName().equals(queryName))
                    .toList();

                if (queriesToRun.isEmpty()) {
                    System.err.println("No query found with name: " + queryName);
                    return 1;
                }
            }

            // Run queries
            QueryRunner runner = new QueryRunner(client, config);
            List<QueryMetrics> results = runner.runQueries(queriesToRun, !quiet);

            // Report results
            switch (outputFormat.toLowerCase()) {
                case "csv" -> reporter.printQueryResultsCsv(results);
                case "json" -> reporter.printQueryResultsJson(results);
                default -> reporter.printQueryResults(results);
            }

            return 0;
        } catch (Exception e) {
            System.err.println("\nError during query benchmark: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }
}
