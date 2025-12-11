package com.wf.benchmark.query;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.wf.benchmark.config.QueryConfig;
import com.wf.benchmark.config.QueryConfig.JoinDefinition;
import com.wf.benchmark.config.QueryConfig.QueryDefinition;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes query benchmarks and collects metrics.
 */
public class QueryRunner {

    private static final Logger log = LoggerFactory.getLogger(QueryRunner.class);

    private final MongoClient client;
    private final QueryConfig config;

    public QueryRunner(MongoClient client, QueryConfig config) {
        this.client = client;
        this.config = config;
    }

    public List<QueryMetrics> runQueries(List<QueryDefinition> queries, boolean showProgress) {
        List<QueryMetrics> results = new ArrayList<>();

        MongoDatabase database = client.getDatabase(config.getConnection().getDatabase());

        for (QueryDefinition queryDef : queries) {
            if (showProgress) {
                System.out.printf("Running query: %s%n", queryDef.getName());
            }

            QueryMetrics metrics = runSingleQuery(database, queryDef, showProgress);
            results.add(metrics);

            if (showProgress) {
                System.out.printf("  Completed: avg=%.2fms, p95=%.2fms, throughput=%.1f/sec%n%n",
                    metrics.getAvgLatencyMs(), metrics.getP95LatencyMs(), metrics.getThroughput());
            }
        }

        return results;
    }

    private QueryMetrics runSingleQuery(MongoDatabase database, QueryDefinition queryDef, boolean showProgress) {
        QueryMetrics metrics = new QueryMetrics(
            queryDef.getName(),
            queryDef.getCollection(),
            queryDef.getDescription());

        MongoCollection<Document> collection = database.getCollection(queryDef.getCollection());
        ParameterGenerator paramGen = new ParameterGenerator(queryDef.getParameters(), database);

        int warmup = config.getQueryExecution().getWarmupIterations();
        int iterations = config.getQueryExecution().getIterations();

        metrics.setWarmupIterations(warmup);
        metrics.setIterations(iterations);

        // Warmup iterations
        for (int i = 0; i < warmup; i++) {
            try {
                executeQuery(collection, queryDef, paramGen);
            } catch (Exception e) {
                log.warn("Warmup iteration {} failed: {}", i, e.getMessage());
            }
        }

        // Reset parameter generator for actual run
        paramGen.reset();

        // Capture explain plan on first iteration if requested
        if (config.getQueryExecution().isIncludeExplainPlan()) {
            try {
                String explainPlan = getExplainPlan(collection, queryDef, paramGen);
                metrics.setExplainPlan(explainPlan);
                metrics.setIndexUsed(extractIndexUsed(explainPlan));
            } catch (Exception e) {
                log.warn("Failed to get explain plan: {}", e.getMessage());
            }
        }

        // Measured iterations
        for (int i = 0; i < iterations; i++) {
            try {
                long startNanos = System.nanoTime();
                QueryResult result = executeQuery(collection, queryDef, paramGen);
                long latencyMicros = (System.nanoTime() - startNanos) / 1000;

                metrics.recordLatency(latencyMicros);
                metrics.addDocumentsReturned(result.documentsReturned());

                if (showProgress && (i + 1) % 10 == 0) {
                    System.out.printf("\r  Progress: %d/%d iterations", i + 1, iterations);
                }
            } catch (Exception e) {
                metrics.recordError();
                log.error("Query iteration {} failed: {}", i, e.getMessage());
            }
        }

        if (showProgress) {
            System.out.print("\r                                    \r");
        }

        return metrics;
    }

    private record QueryResult(long documentsReturned, long documentsExamined) {}

    private QueryResult executeQuery(MongoCollection<Document> collection,
                                     QueryDefinition queryDef,
                                     ParameterGenerator paramGen) {
        return switch (queryDef.getType()) {
            case "find" -> executeFindQuery(collection, queryDef, paramGen);
            case "aggregate" -> executeAggregateQuery(collection, queryDef);
            case "count" -> executeCountQuery(collection, queryDef, paramGen);
            default -> throw new IllegalArgumentException("Unknown query type: " + queryDef.getType());
        };
    }

    private QueryResult executeFindQuery(MongoCollection<Document> collection,
                                         QueryDefinition queryDef,
                                         ParameterGenerator paramGen) {
        Document filter = paramGen.substituteParameters(queryDef.getFilter());

        FindIterable<Document> cursor = collection.find(filter);

        if (queryDef.getProjection() != null) {
            cursor = cursor.projection(queryDef.getProjection());
        }
        if (queryDef.getSort() != null) {
            cursor = cursor.sort(queryDef.getSort());
        }
        if (queryDef.getLimit() != null) {
            cursor = cursor.limit(queryDef.getLimit());
        }

        // If no join, just count results
        if (!queryDef.hasJoin()) {
            long count = 0;
            for (Document doc : cursor) {
                count++;
            }
            return new QueryResult(count, count);
        }

        // Execute join for each primary document
        MongoDatabase database = client.getDatabase(config.getConnection().getDatabase());
        List<Document> primaryDocs = new ArrayList<>();
        for (Document doc : cursor) {
            primaryDocs.add(doc);
        }

        long matchingCount = 0;
        for (Document primaryDoc : primaryDocs) {
            boolean joinMatches = executeJoinChain(database, primaryDoc, queryDef.getJoin(), paramGen);
            if (joinMatches) {
                matchingCount++;
            }
        }

        return new QueryResult(matchingCount, primaryDocs.size());
    }

    /**
     * Execute a chain of joins starting from a source document.
     * Returns true if the entire join chain matches (all joins find at least one result).
     */
    private boolean executeJoinChain(MongoDatabase database, Document sourceDoc,
                                     JoinDefinition joinDef, ParameterGenerator paramGen) {
        if (joinDef == null) {
            return true; // No more joins to process
        }

        // Get the local field value from the source document
        Object localValue = getNestedValue(sourceDoc, joinDef.getLocalField());
        if (localValue == null) {
            log.debug("Local field {} not found in document", joinDef.getLocalField());
            return false;
        }

        // Build the join filter
        Document joinFilter = new Document(joinDef.getForeignField(), localValue);

        // Add any additional filter from the join definition
        if (joinDef.getFilter() != null) {
            Document additionalFilter = paramGen.substituteParameters(joinDef.getFilter());
            joinFilter.putAll(additionalFilter);
        }

        // Query the target collection
        MongoCollection<Document> targetCollection = database.getCollection(joinDef.getCollection());
        FindIterable<Document> joinResults = targetCollection.find(joinFilter);

        // Check if any documents match
        List<Document> matchedDocs = new ArrayList<>();
        for (Document doc : joinResults) {
            matchedDocs.add(doc);
        }

        if (matchedDocs.isEmpty()) {
            return false; // Join didn't match any documents
        }

        // If there's a next join in the chain, process it
        if (joinDef.getNextJoin() != null) {
            for (Document matchedDoc : matchedDocs) {
                if (executeJoinChain(database, matchedDoc, joinDef.getNextJoin(), paramGen)) {
                    return true; // At least one path through the chain matched
                }
            }
            return false; // No paths through the chain matched
        }

        return true; // This join matched and there are no more joins
    }

    /**
     * Get a nested value from a document using dot notation.
     * E.g., "phoneKey.customerNumber" would get doc.phoneKey.customerNumber
     */
    private Object getNestedValue(Document doc, String fieldPath) {
        String[] parts = fieldPath.split("\\.");
        Object current = doc;

        for (String part : parts) {
            if (current == null) {
                return null;
            }
            if (current instanceof Document) {
                current = ((Document) current).get(part);
            } else {
                return null;
            }
        }

        return current;
    }

    private QueryResult executeAggregateQuery(MongoCollection<Document> collection,
                                              QueryDefinition queryDef) {
        List<Document> pipeline = queryDef.getPipeline();

        AggregateIterable<Document> cursor = collection.aggregate(pipeline);

        long count = 0;
        for (Document doc : cursor) {
            count++;
        }

        return new QueryResult(count, count);
    }

    private QueryResult executeCountQuery(MongoCollection<Document> collection,
                                          QueryDefinition queryDef,
                                          ParameterGenerator paramGen) {
        Document filter = paramGen.substituteParameters(queryDef.getFilter());
        long count = collection.countDocuments(filter);
        return new QueryResult(count, count);
    }

    private String getExplainPlan(MongoCollection<Document> collection,
                                  QueryDefinition queryDef,
                                  ParameterGenerator paramGen) {
        if ("aggregate".equals(queryDef.getType())) {
            // For aggregate, use explain on the aggregate command
            Document explainCmd = new Document("aggregate", collection.getNamespace().getCollectionName())
                .append("pipeline", queryDef.getPipeline())
                .append("explain", true)
                .append("cursor", new Document());

            Document result = collection.getNamespace().getDatabaseName() != null ?
                client.getDatabase(config.getConnection().getDatabase()).runCommand(explainCmd) : null;

            return result != null ? result.toJson() : "N/A";
        } else {
            // For find queries, use find with explain
            Document filter = paramGen.substituteParameters(queryDef.getFilter());
            Document explainCmd = new Document("explain",
                new Document("find", collection.getNamespace().getCollectionName())
                    .append("filter", filter));

            if (queryDef.getLimit() != null) {
                ((Document) explainCmd.get("explain")).append("limit", queryDef.getLimit());
            }

            Document result = client.getDatabase(config.getConnection().getDatabase()).runCommand(explainCmd);
            return result.toJson();
        }
    }

    private String extractIndexUsed(String explainPlan) {
        // Simple extraction - look for index name in explain output
        if (explainPlan == null) return "N/A";

        if (explainPlan.contains("\"indexName\"")) {
            int start = explainPlan.indexOf("\"indexName\"");
            int colonPos = explainPlan.indexOf(":", start);
            int quoteStart = explainPlan.indexOf("\"", colonPos + 1);
            int quoteEnd = explainPlan.indexOf("\"", quoteStart + 1);
            if (quoteStart > 0 && quoteEnd > quoteStart) {
                return explainPlan.substring(quoteStart + 1, quoteEnd);
            }
        }

        if (explainPlan.contains("COLLSCAN")) {
            return "COLLSCAN (no index)";
        }

        if (explainPlan.contains("IXSCAN")) {
            return "Index Scan";
        }

        return "Unknown";
    }
}
