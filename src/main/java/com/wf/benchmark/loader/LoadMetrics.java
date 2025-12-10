package com.wf.benchmark.loader;

import org.HdrHistogram.Histogram;

import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe metrics collection for data loading operations.
 */
public class LoadMetrics {

    private final String collectionName;
    private final LongAdder documentsLoaded = new LongAdder();
    private final LongAdder batchesCompleted = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final Histogram batchLatencyHistogram;

    private volatile long startTimeNanos;
    private volatile long endTimeNanos;

    public LoadMetrics(String collectionName) {
        this.collectionName = collectionName;
        // Histogram for latencies from 1ms to 60 seconds with 3 significant digits
        this.batchLatencyHistogram = new Histogram(1, 60_000_000, 3);
    }

    public void start() {
        this.startTimeNanos = System.nanoTime();
    }

    public void complete() {
        this.endTimeNanos = System.nanoTime();
    }

    public void recordBatch(int documentCount, long latencyMicros) {
        documentsLoaded.add(documentCount);
        batchesCompleted.increment();
        synchronized (batchLatencyHistogram) {
            batchLatencyHistogram.recordValue(latencyMicros);
        }
    }

    public void recordError() {
        errors.increment();
    }

    public String getCollectionName() {
        return collectionName;
    }

    public long getDocumentsLoaded() {
        return documentsLoaded.sum();
    }

    public long getBatchesCompleted() {
        return batchesCompleted.sum();
    }

    public long getErrors() {
        return errors.sum();
    }

    public long getElapsedTimeMs() {
        long end = endTimeNanos > 0 ? endTimeNanos : System.nanoTime();
        return (end - startTimeNanos) / 1_000_000;
    }

    public double getThroughput() {
        long elapsedMs = getElapsedTimeMs();
        if (elapsedMs == 0) return 0;
        return (documentsLoaded.sum() * 1000.0) / elapsedMs;
    }

    public double getAvgLatencyMs() {
        synchronized (batchLatencyHistogram) {
            return batchLatencyHistogram.getMean() / 1000.0;
        }
    }

    public double getMinLatencyMs() {
        synchronized (batchLatencyHistogram) {
            return batchLatencyHistogram.getMinValue() / 1000.0;
        }
    }

    public double getMaxLatencyMs() {
        synchronized (batchLatencyHistogram) {
            return batchLatencyHistogram.getMaxValue() / 1000.0;
        }
    }

    public double getPercentileLatencyMs(double percentile) {
        synchronized (batchLatencyHistogram) {
            return batchLatencyHistogram.getValueAtPercentile(percentile) / 1000.0;
        }
    }

    public double getP50LatencyMs() {
        return getPercentileLatencyMs(50.0);
    }

    public double getP95LatencyMs() {
        return getPercentileLatencyMs(95.0);
    }

    public double getP99LatencyMs() {
        return getPercentileLatencyMs(99.0);
    }

    @Override
    public String toString() {
        return String.format(
            "LoadMetrics{collection=%s, docs=%d, batches=%d, errors=%d, " +
                "elapsed=%dms, throughput=%.1f/sec, avgLatency=%.2fms, p95=%.2fms}",
            collectionName, getDocumentsLoaded(), getBatchesCompleted(), getErrors(),
            getElapsedTimeMs(), getThroughput(), getAvgLatencyMs(), getP95LatencyMs());
    }
}
