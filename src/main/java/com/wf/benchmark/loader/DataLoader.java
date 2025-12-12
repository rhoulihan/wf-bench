package com.wf.benchmark.loader;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.wf.benchmark.config.LoadConfig;
import com.wf.benchmark.generator.*;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Orchestrates parallel data loading across multiple collections.
 */
public class DataLoader {

    private static final Logger log = LoggerFactory.getLogger(DataLoader.class);

    // Sample rate: 0.1% of documents will be sampled
    private static final double SAMPLE_RATE = 0.001;

    private final MongoClient client;
    private final LoadConfig config;
    private final ProgressReporter progressReporter;
    private final SampleDataCollector sampleCollector;

    private final LongAdder totalDocumentsWritten = new LongAdder();
    private final List<LoadMetrics> allMetrics = new ArrayList<>();

    public interface ProgressReporter {
        void reportProgress(String collection, long written, long total, double throughput);
        void reportComplete(LoadMetrics metrics);
    }

    public DataLoader(MongoClient client, LoadConfig config, ProgressReporter progressReporter) {
        this.client = client;
        this.config = config;
        this.progressReporter = progressReporter;

        // Create sample collector - will write to sample-data.json in current directory
        Path sampleDataPath = Path.of("sample-data.json");
        this.sampleCollector = new SampleDataCollector(SAMPLE_RATE, sampleDataPath);
    }

    public List<LoadMetrics> load() throws InterruptedException {
        MongoDatabase database = client.getDatabase(config.getConnection().getDatabase());

        // Drop existing collections if configured
        if (config.isDropExisting()) {
            dropCollections(database);
        }

        // Create generators
        RandomDataProvider randomProvider = new RandomDataProvider();

        long identityCount = config.getEffectiveIdentityCount();

        IdentityGenerator identityGen = new IdentityGenerator(
            randomProvider, config.getIndividualRatio(), config.getCollectionPrefix());

        AddressGenerator addressGen = new AddressGenerator(
            randomProvider,
            config.getMinAddressesPerCustomer(),
            config.getMaxAddressesPerCustomer(),
            config.getCollectionPrefix());

        PhoneGenerator phoneGen = new PhoneGenerator(
            randomProvider, config.getPhoneRatio(), config.getCollectionPrefix());

        AccountGenerator accountGen = new AccountGenerator(
            randomProvider, config.getAccountRatio(),
            config.getCollectionPrefix(), identityCount);

        // Load each collection
        long addressCount = config.getAddressCount();
        long phoneCount = config.getPhoneCount();
        long accountCount = config.getAccountCount();

        log.info("Starting data load - Identity: {}, Address: {}, Phone: {}, Account: {}",
            identityCount, addressCount, phoneCount, accountCount);

        // Load each collection with appropriate sample collectors
        LoadMetrics identityMetrics = loadCollection(database, identityGen, identityCount,
            sampleCollector::collectIdentitySample);
        allMetrics.add(identityMetrics);

        LoadMetrics addressMetrics = loadCollection(database, addressGen, addressCount,
            sampleCollector::collectAddressSample);
        allMetrics.add(addressMetrics);

        LoadMetrics phoneMetrics = loadCollection(database, phoneGen, phoneCount,
            sampleCollector::collectPhoneSample);
        allMetrics.add(phoneMetrics);

        LoadMetrics accountMetrics = loadCollection(database, accountGen, accountCount,
            sampleCollector::collectAccountSample);
        allMetrics.add(accountMetrics);

        // Write sample data to file
        try {
            sampleCollector.writeToFile();
            log.info("Sample data collection complete: {}", sampleCollector.getStats());
        } catch (IOException e) {
            log.warn("Failed to write sample data file: {}", e.getMessage());
        }

        return allMetrics;
    }

    private void dropCollections(MongoDatabase database) {
        String prefix = config.getCollectionPrefix();
        List<String> collections = List.of(
            prefix + "identity",
            prefix + "address",
            prefix + "phone",
            prefix + "account"
        );

        for (String collName : collections) {
            log.info("Dropping collection: {}", collName);
            database.getCollection(collName).drop();
        }
    }

    private LoadMetrics loadCollection(MongoDatabase database, DataGenerator generator, long documentCount,
                                        Consumer<Document> sampleCollectorFn)
            throws InterruptedException {

        String collectionName = generator.getCollectionName();
        MongoCollection<Document> collection = database.getCollection(collectionName);
        LoadMetrics metrics = new LoadMetrics(collectionName);

        log.info("Loading {} documents into {}", documentCount, collectionName);

        int threads = config.getThreads();
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        // Track progress for this collection
        LongAdder collectionProgress = new LongAdder();
        long progressInterval = config.getProgressInterval();

        BatchWriter.ProgressCallback progressCallback = (coll, written) -> {
            collectionProgress.add(written);
            totalDocumentsWritten.add(written);

            long current = collectionProgress.sum();
            if (!config.isQuiet() && progressReporter != null && current % progressInterval < written) {
                progressReporter.reportProgress(coll, current, documentCount, metrics.getThroughput());
            }
        };

        metrics.start();

        // Partition work across threads
        long docsPerThread = documentCount / threads;
        long remainder = documentCount % threads;

        List<BatchWriter> writers = new ArrayList<>();
        long startSeq = 0;

        for (int i = 0; i < threads; i++) {
            long count = docsPerThread + (i < remainder ? 1 : 0);
            long endSeq = startSeq + count;

            BatchWriter writer = new BatchWriter(
                collection, generator, startSeq, endSeq,
                config.getBatchSize(), config.isOrdered(),
                metrics, progressCallback, sampleCollectorFn);

            writers.add(writer);
            startSeq = endSeq;
        }

        // Submit all writers
        for (BatchWriter writer : writers) {
            executor.submit(writer);
        }

        // Wait for completion
        executor.shutdown();
        boolean completed = executor.awaitTermination(24, TimeUnit.HOURS);

        metrics.complete();

        if (!completed) {
            log.error("Data loading timed out for collection {}", collectionName);
            executor.shutdownNow();
        }

        if (progressReporter != null) {
            progressReporter.reportComplete(metrics);
        }

        log.info("Completed loading {}: {} documents in {}ms ({:.1f}/sec)",
            collectionName, metrics.getDocumentsLoaded(),
            metrics.getElapsedTimeMs(), metrics.getThroughput());

        return metrics;
    }

    public long getTotalDocumentsWritten() {
        return totalDocumentsWritten.sum();
    }

    public List<LoadMetrics> getAllMetrics() {
        return allMetrics;
    }
}
