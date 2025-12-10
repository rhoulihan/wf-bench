package com.wf.benchmark.command;

import com.mongodb.client.MongoClient;
import com.wf.benchmark.config.ConnectionConfig;
import com.wf.benchmark.config.LoadConfig;
import com.wf.benchmark.loader.DataLoader;
import com.wf.benchmark.loader.LoadMetrics;
import com.wf.benchmark.report.ConsoleReporter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name = "load",
    description = "Load test data into MongoDB collections",
    mixinStandardHelpOptions = true
)
public class LoadCommand implements Callable<Integer> {

    @Option(names = {"-c", "--connection-string"}, description = "MongoDB connection string", required = true)
    private String connectionString;

    @Option(names = {"-d", "--database"}, description = "Target database name", defaultValue = "benchmark")
    private String database;

    @Option(names = {"-s", "--scale"}, description = "Data scale: SMALL, MEDIUM, LARGE, XLARGE", defaultValue = "MEDIUM")
    private LoadConfig.Scale scale;

    @Option(names = {"-i", "--identity-count"}, description = "Override identity document count")
    private Long identityCount;

    @Option(names = {"-a", "--address-ratio"}, description = "Address documents per identity", defaultValue = "1.0")
    private double addressRatio;

    @Option(names = {"-p", "--phone-ratio"}, description = "Phone documents per identity", defaultValue = "2.5")
    private double phoneRatio;

    @Option(names = {"-t", "--threads"}, description = "Number of writer threads", defaultValue = "4")
    private int threads;

    @Option(names = {"-b", "--batch-size"}, description = "Documents per batch insert", defaultValue = "1000")
    private int batchSize;

    @Option(names = {"--connection-pool"}, description = "MongoDB connection pool size", defaultValue = "10")
    private int connectionPoolSize;

    @Option(names = {"-D", "--drop-existing"}, description = "Drop collections before loading", defaultValue = "false")
    private boolean dropExisting;

    @Option(names = {"-P", "--collection-prefix"}, description = "Prefix for collection names", defaultValue = "")
    private String collectionPrefix;

    @Option(names = {"-f", "--config-file"}, description = "YAML configuration file")
    private String configFile;

    @Option(names = {"--dry-run"}, description = "Show what would be loaded without loading", defaultValue = "false")
    private boolean dryRun;

    @Option(names = {"-q", "--quiet"}, description = "Suppress progress output", defaultValue = "false")
    private boolean quiet;

    @Option(names = {"--progress-interval"}, description = "Progress update interval (docs)", defaultValue = "5000")
    private int progressInterval;

    @Override
    public Integer call() {
        try {
            LoadConfig config = buildConfig();

            if (dryRun) {
                printDryRun(config);
                return 0;
            }

            return executeLoad(config);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private LoadConfig buildConfig() throws IOException {
        LoadConfig config;

        if (configFile != null) {
            config = LoadConfig.fromYaml(configFile);
            // CLI options override config file
            if (connectionString != null) {
                config.getConnection().setConnectionString(connectionString);
            }
        } else {
            config = new LoadConfig();
            config.getConnection().setConnectionString(connectionString);
        }

        config.getConnection().setDatabase(database);
        config.getConnection().setConnectionPoolSize(connectionPoolSize);
        config.setScale(scale);

        if (identityCount != null) {
            config.setIdentityCount(identityCount);
        }

        config.setAddressRatio(addressRatio);
        config.setPhoneRatio(phoneRatio);
        config.setThreads(threads);
        config.setBatchSize(batchSize);
        config.setDropExisting(dropExisting);
        config.setCollectionPrefix(collectionPrefix);
        config.setQuiet(quiet);
        config.setProgressInterval(progressInterval);

        return config;
    }

    private void printDryRun(LoadConfig config) {
        System.out.println("\n=== DRY RUN - No data will be loaded ===\n");
        System.out.println("Configuration:");
        System.out.printf("  Database:          %s%n", config.getConnection().getDatabase());
        System.out.printf("  Scale:             %s%n", config.getScale());
        System.out.printf("  Threads:           %d%n", config.getThreads());
        System.out.printf("  Batch Size:        %d%n", config.getBatchSize());
        System.out.printf("  Connection Pool:   %d%n", config.getConnection().getConnectionPoolSize());
        System.out.printf("  Drop Existing:     %s%n", config.isDropExisting());
        System.out.printf("  Collection Prefix: %s%n",
            config.getCollectionPrefix().isEmpty() ? "(none)" : config.getCollectionPrefix());

        System.out.println("\nDocuments to load:");
        System.out.printf("  Identity:  %,d%n", config.getEffectiveIdentityCount());
        System.out.printf("  Address:   %,d (ratio: %.1f)%n", config.getAddressCount(), config.getAddressRatio());
        System.out.printf("  Phone:     %,d (ratio: %.1f)%n", config.getPhoneCount(), config.getPhoneRatio());
        System.out.printf("  ─────────────────────%n");
        System.out.printf("  TOTAL:     %,d%n",
            config.getEffectiveIdentityCount() + config.getAddressCount() + config.getPhoneCount());
    }

    private int executeLoad(LoadConfig config) {
        ConsoleReporter reporter = new ConsoleReporter(quiet);

        reporter.printLoadHeader(config);

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

            DataLoader loader = new DataLoader(client, config, new DataLoader.ProgressReporter() {
                @Override
                public void reportProgress(String collection, long written, long total, double throughput) {
                    if (!quiet) {
                        double pct = (written * 100.0) / total;
                        System.out.printf("\r  %-12s %,12d / %,12d (%5.1f%%) - %,.0f docs/sec",
                            collection, written, total, pct, throughput);
                    }
                }

                @Override
                public void reportComplete(LoadMetrics metrics) {
                    if (!quiet) {
                        System.out.printf("\r  %-12s %,12d / %,12d (100.0%%) - %,.0f docs/sec - COMPLETE%n",
                            metrics.getCollectionName(), metrics.getDocumentsLoaded(),
                            metrics.getDocumentsLoaded(), metrics.getThroughput());
                    }
                }
            });

            List<LoadMetrics> metrics = loader.load();

            reporter.printLoadResults(metrics);

            return 0;
        } catch (Exception e) {
            System.err.println("\nError during load: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }
}
