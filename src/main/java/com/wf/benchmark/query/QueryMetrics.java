package com.wf.benchmark.query;

import org.HdrHistogram.Histogram;

/**
 * Metrics collected during query benchmark execution.
 */
public class QueryMetrics {

    private final String queryName;
    private final String collection;
    private final String description;

    private int iterations;
    private int warmupIterations;
    private final Histogram latencyHistogram;

    private long totalDocumentsExamined;
    private long totalDocumentsReturned;
    private String indexUsed;
    private String explainPlan;
    private int errors;

    public QueryMetrics(String queryName, String collection, String description) {
        this.queryName = queryName;
        this.collection = collection;
        this.description = description;
        // Histogram for latencies from 1 microsecond to 60 seconds with 3 significant digits
        this.latencyHistogram = new Histogram(1, 60_000_000, 3);
    }

    public void recordLatency(long latencyMicros) {
        latencyHistogram.recordValue(latencyMicros);
    }

    public void addDocumentsExamined(long count) {
        totalDocumentsExamined += count;
    }

    public void addDocumentsReturned(long count) {
        totalDocumentsReturned += count;
    }

    public void recordError() {
        errors++;
    }

    // Getters
    public String getQueryName() {
        return queryName;
    }

    public String getCollection() {
        return collection;
    }

    public String getDescription() {
        return description;
    }

    public int getIterations() {
        return iterations;
    }

    public void setIterations(int iterations) {
        this.iterations = iterations;
    }

    public int getWarmupIterations() {
        return warmupIterations;
    }

    public void setWarmupIterations(int warmupIterations) {
        this.warmupIterations = warmupIterations;
    }

    public int getErrors() {
        return errors;
    }

    public String getIndexUsed() {
        return indexUsed;
    }

    public void setIndexUsed(String indexUsed) {
        this.indexUsed = indexUsed;
    }

    public String getExplainPlan() {
        return explainPlan;
    }

    public void setExplainPlan(String explainPlan) {
        this.explainPlan = explainPlan;
    }

    public long getTotalDocumentsExamined() {
        return totalDocumentsExamined;
    }

    public long getTotalDocumentsReturned() {
        return totalDocumentsReturned;
    }

    public double getAvgDocumentsExamined() {
        return iterations > 0 ? (double) totalDocumentsExamined / iterations : 0;
    }

    public double getAvgDocumentsReturned() {
        return iterations > 0 ? (double) totalDocumentsReturned / iterations : 0;
    }

    // Latency metrics in milliseconds
    public double getAvgLatencyMs() {
        return latencyHistogram.getMean() / 1000.0;
    }

    public double getMinLatencyMs() {
        return latencyHistogram.getMinValue() / 1000.0;
    }

    public double getMaxLatencyMs() {
        return latencyHistogram.getMaxValue() / 1000.0;
    }

    public double getP50LatencyMs() {
        return latencyHistogram.getValueAtPercentile(50.0) / 1000.0;
    }

    public double getP95LatencyMs() {
        return latencyHistogram.getValueAtPercentile(95.0) / 1000.0;
    }

    public double getP99LatencyMs() {
        return latencyHistogram.getValueAtPercentile(99.0) / 1000.0;
    }

    public double getThroughput() {
        double totalTimeMs = latencyHistogram.getTotalCount() > 0 ?
            (latencyHistogram.getMean() * latencyHistogram.getTotalCount()) / 1000.0 : 0;
        return totalTimeMs > 0 ? (iterations * 1000.0) / totalTimeMs : 0;
    }

    @Override
    public String toString() {
        return String.format(
            "QueryMetrics{name=%s, iterations=%d, avgLatency=%.2fms, p95=%.2fms, throughput=%.1f/sec}",
            queryName, iterations, getAvgLatencyMs(), getP95LatencyMs(), getThroughput());
    }
}
