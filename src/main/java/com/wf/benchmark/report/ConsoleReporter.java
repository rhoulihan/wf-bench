package com.wf.benchmark.report;

import com.wf.benchmark.config.LoadConfig;
import com.wf.benchmark.config.QueryConfig;
import com.wf.benchmark.loader.LoadMetrics;
import com.wf.benchmark.query.QueryMetrics;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Console reporter for benchmark results.
 */
public class ConsoleReporter {

    private static final String SEPARATOR = "=".repeat(80);
    private static final String THIN_SEPARATOR = "-".repeat(80);
    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final boolean quiet;

    public ConsoleReporter(boolean quiet) {
        this.quiet = quiet;
    }

    public void printLoadHeader(LoadConfig config) {
        if (quiet) return;

        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("              MongoDB Benchmark Tool - Data Load");
        System.out.println(SEPARATOR);
        System.out.println();
        System.out.printf("Database:        %s%n", config.getConnection().getDatabase());
        System.out.printf("Started:         %s%n", LocalDateTime.now().format(DT_FORMAT));
        System.out.println();
        System.out.println("Configuration:");
        System.out.printf("  Scale:           %s%n", config.getScale());
        System.out.printf("  Threads:         %d%n", config.getThreads());
        System.out.printf("  Batch Size:      %d%n", config.getBatchSize());
        System.out.printf("  Connection Pool: %d%n", config.getConnection().getConnectionPoolSize());
        System.out.println();
        System.out.println("Target Documents:");
        System.out.printf("  Identity:        %,d%n", config.getEffectiveIdentityCount());
        System.out.printf("  Address:         %,d%n", config.getAddressCount());
        System.out.printf("  Phone:           %,d%n", config.getPhoneCount());
        System.out.printf("  Total:           %,d%n",
            config.getEffectiveIdentityCount() + config.getAddressCount() + config.getPhoneCount());
        System.out.println();
        System.out.println("Progress:");
    }

    public void printLoadResults(List<LoadMetrics> metrics) {
        if (quiet) {
            printLoadResultsCompact(metrics);
            return;
        }

        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("                           Results Summary");
        System.out.println(SEPARATOR);
        System.out.println();
        System.out.printf("%-14s %12s %12s %12s %12s %8s%n",
            "Collection", "Documents", "Throughput", "Avg Latency", "P95 Latency", "Errors");
        System.out.println(THIN_SEPARATOR);

        long totalDocs = 0;
        long totalErrors = 0;
        long totalTimeMs = 0;

        for (LoadMetrics m : metrics) {
            System.out.printf("%-14s %,12d %,10.0f/s %10.2f ms %10.2f ms %8d%n",
                m.getCollectionName(),
                m.getDocumentsLoaded(),
                m.getThroughput(),
                m.getAvgLatencyMs(),
                m.getP95LatencyMs(),
                m.getErrors());

            totalDocs += m.getDocumentsLoaded();
            totalErrors += m.getErrors();
            totalTimeMs = Math.max(totalTimeMs, m.getElapsedTimeMs());
        }

        System.out.println(THIN_SEPARATOR);

        double overallThroughput = totalTimeMs > 0 ? (totalDocs * 1000.0) / totalTimeMs : 0;
        System.out.printf("%-14s %,12d %,10.0f/s %22s %8d%n",
            "TOTAL", totalDocs, overallThroughput, "", totalErrors);

        System.out.println();
        System.out.printf("Total Time: %s%n", formatDuration(totalTimeMs));
        System.out.printf("Completed:  %s%n", LocalDateTime.now().format(DT_FORMAT));
        System.out.println(SEPARATOR);
    }

    private void printLoadResultsCompact(List<LoadMetrics> metrics) {
        long totalDocs = metrics.stream().mapToLong(LoadMetrics::getDocumentsLoaded).sum();
        long totalErrors = metrics.stream().mapToLong(LoadMetrics::getErrors).sum();
        long totalTimeMs = metrics.stream().mapToLong(LoadMetrics::getElapsedTimeMs).max().orElse(0);
        double throughput = totalTimeMs > 0 ? (totalDocs * 1000.0) / totalTimeMs : 0;

        System.out.printf("Loaded %,d documents in %s (%.0f/sec), %d errors%n",
            totalDocs, formatDuration(totalTimeMs), throughput, totalErrors);
    }

    public void printQueryHeader(QueryConfig config) {
        if (quiet) return;

        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("              MongoDB Benchmark Tool - Query Benchmark");
        System.out.println(SEPARATOR);
        System.out.println();
        System.out.printf("Database:        %s%n", config.getConnection().getDatabase());
        System.out.printf("Started:         %s%n", LocalDateTime.now().format(DT_FORMAT));
        System.out.println();
        System.out.println("Configuration:");
        System.out.printf("  Iterations:      %d (+ %d warmup)%n",
            config.getQueryExecution().getIterations(),
            config.getQueryExecution().getWarmupIterations());
        System.out.printf("  Threads:         %d%n", config.getQueryExecution().getThreads());
        System.out.printf("  Queries:         %d%n", config.getQueries().size());
        System.out.println();
    }

    public void printQueryResults(List<QueryMetrics> results) {
        if (quiet) {
            printQueryResultsCompact(results);
            return;
        }

        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("                        Query Results");
        System.out.println(SEPARATOR);

        for (QueryMetrics m : results) {
            System.out.println();
            System.out.println(THIN_SEPARATOR);
            System.out.printf("Query: %s%n", m.getQueryName());
            System.out.printf("Description: %s%n", m.getDescription());
            System.out.printf("Collection: %s%n", m.getCollection());
            if (m.getIndexUsed() != null) {
                System.out.printf("Index Used: %s%n", m.getIndexUsed());
            }
            System.out.println();

            System.out.printf("  Iterations:      %d%n", m.getIterations());
            System.out.printf("  Avg Latency:     %.2f ms%n", m.getAvgLatencyMs());
            System.out.printf("  Min Latency:     %.2f ms%n", m.getMinLatencyMs());
            System.out.printf("  Max Latency:     %.2f ms%n", m.getMaxLatencyMs());
            System.out.printf("  P50 Latency:     %.2f ms%n", m.getP50LatencyMs());
            System.out.printf("  P95 Latency:     %.2f ms%n", m.getP95LatencyMs());
            System.out.printf("  P99 Latency:     %.2f ms%n", m.getP99LatencyMs());
            System.out.printf("  Throughput:      %.1f ops/sec%n", m.getThroughput());
            System.out.printf("  Avg Docs Returned: %.1f%n", m.getAvgDocumentsReturned());
            if (m.getErrors() > 0) {
                System.out.printf("  Errors:          %d%n", m.getErrors());
            }
        }

        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("                           Summary");
        System.out.println(SEPARATOR);
        System.out.printf("Total Queries:     %d%n", results.size());
        System.out.printf("Total Iterations:  %d%n",
            results.stream().mapToInt(QueryMetrics::getIterations).sum());
        System.out.printf("Completed:         %s%n", LocalDateTime.now().format(DT_FORMAT));
        System.out.println(SEPARATOR);
    }

    private void printQueryResultsCompact(List<QueryMetrics> results) {
        for (QueryMetrics m : results) {
            System.out.printf("%s: avg=%.2fms p95=%.2fms throughput=%.1f/sec%n",
                m.getQueryName(), m.getAvgLatencyMs(), m.getP95LatencyMs(), m.getThroughput());
        }
    }

    public void printQueryResultsCsv(List<QueryMetrics> results) {
        System.out.println("query_name,collection,iterations,avg_latency_ms,min_latency_ms,max_latency_ms," +
            "p50_latency_ms,p95_latency_ms,p99_latency_ms,throughput_ops_sec,avg_docs_returned,errors");

        for (QueryMetrics m : results) {
            System.out.printf("%s,%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.1f,%.1f,%d%n",
                m.getQueryName(),
                m.getCollection(),
                m.getIterations(),
                m.getAvgLatencyMs(),
                m.getMinLatencyMs(),
                m.getMaxLatencyMs(),
                m.getP50LatencyMs(),
                m.getP95LatencyMs(),
                m.getP99LatencyMs(),
                m.getThroughput(),
                m.getAvgDocumentsReturned(),
                m.getErrors());
        }
    }

    public void printQueryResultsJson(List<QueryMetrics> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"results\": [\n");

        for (int i = 0; i < results.size(); i++) {
            QueryMetrics m = results.get(i);
            sb.append("    {\n");
            sb.append(String.format("      \"queryName\": \"%s\",\n", m.getQueryName()));
            sb.append(String.format("      \"collection\": \"%s\",\n", m.getCollection()));
            sb.append(String.format("      \"description\": \"%s\",\n", escape(m.getDescription())));
            sb.append(String.format("      \"iterations\": %d,\n", m.getIterations()));
            sb.append(String.format("      \"avgLatencyMs\": %.3f,\n", m.getAvgLatencyMs()));
            sb.append(String.format("      \"minLatencyMs\": %.3f,\n", m.getMinLatencyMs()));
            sb.append(String.format("      \"maxLatencyMs\": %.3f,\n", m.getMaxLatencyMs()));
            sb.append(String.format("      \"p50LatencyMs\": %.3f,\n", m.getP50LatencyMs()));
            sb.append(String.format("      \"p95LatencyMs\": %.3f,\n", m.getP95LatencyMs()));
            sb.append(String.format("      \"p99LatencyMs\": %.3f,\n", m.getP99LatencyMs()));
            sb.append(String.format("      \"throughputOpsPerSec\": %.1f,\n", m.getThroughput()));
            sb.append(String.format("      \"avgDocsReturned\": %.1f,\n", m.getAvgDocumentsReturned()));
            sb.append(String.format("      \"errors\": %d\n", m.getErrors()));
            sb.append("    }");
            if (i < results.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append("  ],\n");
        sb.append(String.format("  \"timestamp\": \"%s\"\n", LocalDateTime.now().format(DT_FORMAT)));
        sb.append("}\n");

        System.out.println(sb);
    }

    private String escape(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private String formatDuration(long millis) {
        if (millis < 1000) {
            return millis + "ms";
        } else if (millis < 60_000) {
            return String.format("%.1f seconds", millis / 1000.0);
        } else if (millis < 3600_000) {
            long minutes = millis / 60_000;
            long seconds = (millis % 60_000) / 1000;
            return String.format("%d min %d sec", minutes, seconds);
        } else {
            long hours = millis / 3600_000;
            long minutes = (millis % 3600_000) / 60_000;
            return String.format("%d hr %d min", hours, minutes);
        }
    }
}
