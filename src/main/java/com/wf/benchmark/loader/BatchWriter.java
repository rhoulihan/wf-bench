package com.wf.benchmark.loader;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.InsertManyOptions;
import com.wf.benchmark.generator.DataGenerator;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Handles batch writing of documents to MongoDB.
 */
public class BatchWriter implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(BatchWriter.class);

    private final MongoCollection<Document> collection;
    private final DataGenerator generator;
    private final long startSequence;
    private final long endSequence;
    private final int batchSize;
    private final boolean ordered;
    private final LoadMetrics metrics;
    private final ProgressCallback progressCallback;
    private final Consumer<Document> sampleCollector;

    public interface ProgressCallback {
        void onProgress(String collection, long documentsWritten);
    }

    public BatchWriter(MongoCollection<Document> collection,
                       DataGenerator generator,
                       long startSequence,
                       long endSequence,
                       int batchSize,
                       boolean ordered,
                       LoadMetrics metrics,
                       ProgressCallback progressCallback) {
        this(collection, generator, startSequence, endSequence, batchSize, ordered, metrics, progressCallback, null);
    }

    public BatchWriter(MongoCollection<Document> collection,
                       DataGenerator generator,
                       long startSequence,
                       long endSequence,
                       int batchSize,
                       boolean ordered,
                       LoadMetrics metrics,
                       ProgressCallback progressCallback,
                       Consumer<Document> sampleCollector) {
        this.collection = collection;
        this.generator = generator;
        this.startSequence = startSequence;
        this.endSequence = endSequence;
        this.batchSize = batchSize;
        this.ordered = ordered;
        this.metrics = metrics;
        this.progressCallback = progressCallback;
        this.sampleCollector = sampleCollector;
    }

    @Override
    public void run() {
        List<Document> batch = new ArrayList<>(batchSize);
        InsertManyOptions options = new InsertManyOptions().ordered(ordered);

        for (long seq = startSequence; seq < endSequence; seq++) {
            Document doc = generator.generate(seq);
            batch.add(doc);

            // Collect sample data if collector is configured
            if (sampleCollector != null) {
                sampleCollector.accept(doc);
            }

            if (batch.size() >= batchSize) {
                writeBatch(batch, options);
                batch = new ArrayList<>(batchSize);
            }
        }

        // Write any remaining documents
        if (!batch.isEmpty()) {
            writeBatch(batch, options);
        }
    }

    private void writeBatch(List<Document> batch, InsertManyOptions options) {
        int size = batch.size();
        long startNanos = System.nanoTime();

        try {
            collection.insertMany(batch, options);
            long latencyMicros = (System.nanoTime() - startNanos) / 1000;
            metrics.recordBatch(size, latencyMicros);

            if (progressCallback != null) {
                progressCallback.onProgress(generator.getCollectionName(), size);
            }
        } catch (MongoBulkWriteException e) {
            // Some documents may have been inserted
            int inserted = e.getWriteResult().getInsertedCount();
            long latencyMicros = (System.nanoTime() - startNanos) / 1000;

            if (inserted > 0) {
                metrics.recordBatch(inserted, latencyMicros);
                if (progressCallback != null) {
                    progressCallback.onProgress(generator.getCollectionName(), inserted);
                }
            }

            int failed = size - inserted;
            for (int i = 0; i < failed; i++) {
                metrics.recordError();
            }

            log.warn("Bulk write partial failure: {} inserted, {} failed - {}",
                inserted, failed, e.getMessage());
        } catch (Exception e) {
            for (int i = 0; i < size; i++) {
                metrics.recordError();
            }
            log.error("Batch write failed: {}", e.getMessage(), e);
        }
    }
}
