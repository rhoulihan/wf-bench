package com.wf.benchmark.query;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.wf.benchmark.config.QueryConfig.ParameterDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates parameter values for query execution based on configuration.
 * Supports correlated parameters that extract multiple fields from the same document.
 */
public class ParameterGenerator {

    private static final Logger log = LoggerFactory.getLogger(ParameterGenerator.class);
    private static final Pattern PARAM_PATTERN = Pattern.compile("\\$\\{param:([^}]+)\\}");
    private static final int DEFAULT_SAMPLE_SIZE = 1000;

    private final Map<String, ParameterDefinition> parameterDefs;
    private final Map<String, AtomicLong> sequentialCounters = new java.util.HashMap<>();
    private final MongoDatabase database;
    private final Map<String, List<Object>> loadedValueCache = new ConcurrentHashMap<>();

    // Cache for full documents by correlation group (collection -> list of documents)
    private final Map<String, List<Document>> correlatedDocumentCache = new ConcurrentHashMap<>();

    // Thread-local storage for current correlation group selections
    // Maps correlation group name -> currently selected document
    private final ThreadLocal<Map<String, Document>> currentCorrelatedDocuments =
        ThreadLocal.withInitial(HashMap::new);

    public ParameterGenerator(Map<String, ParameterDefinition> parameterDefs) {
        this(parameterDefs, null);
    }

    public ParameterGenerator(Map<String, ParameterDefinition> parameterDefs, MongoDatabase database) {
        this.parameterDefs = parameterDefs;
        this.database = database;
    }

    /**
     * Substitute parameters in a filter document with generated values.
     * First selects random documents for any correlation groups, then extracts values.
     */
    public Document substituteParameters(Document filter) {
        if (filter == null || parameterDefs == null || parameterDefs.isEmpty()) {
            return filter;
        }

        // Clear previous correlation group selections for this substitution
        currentCorrelatedDocuments.get().clear();

        // Pre-select documents for all correlation groups
        selectCorrelatedDocuments();

        Document result = new Document();
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            result.put(entry.getKey(), substituteValue(entry.getValue()));
        }
        return result;
    }

    /**
     * Pre-select random documents for each correlation group.
     * All parameters in the same group will extract values from the same document.
     */
    private void selectCorrelatedDocuments() {
        Map<String, Document> selections = currentCorrelatedDocuments.get();

        for (Map.Entry<String, ParameterDefinition> entry : parameterDefs.entrySet()) {
            ParameterDefinition paramDef = entry.getValue();
            String group = paramDef.getCorrelationGroup();

            if (group != null && !selections.containsKey(group)) {
                // Need to select a document for this group
                String collection = paramDef.getCollection();
                if (collection == null) {
                    throw new IllegalArgumentException(
                        "Correlated parameter '" + entry.getKey() +
                        "' requires 'collection' to be specified");
                }

                Document selectedDoc = selectRandomDocument(collection);
                if (selectedDoc != null) {
                    selections.put(group, selectedDoc);
                    log.debug("Selected document for correlation group '{}': {}",
                        group, selectedDoc.get("_id"));
                } else {
                    log.warn("No documents found for correlation group '{}' in collection '{}'",
                        group, collection);
                }
            }
        }
    }

    /**
     * Select a random document from the document cache for a collection.
     */
    private Document selectRandomDocument(String collection) {
        List<Document> documents = correlatedDocumentCache.get(collection);
        if (documents == null) {
            documents = loadDocumentsForCorrelation(collection);
            correlatedDocumentCache.put(collection, documents);
        }

        if (documents.isEmpty()) {
            return null;
        }

        Random random = ThreadLocalRandom.current();
        return documents.get(random.nextInt(documents.size()));
    }

    /**
     * Load full documents from a collection for correlated parameter extraction.
     */
    private List<Document> loadDocumentsForCorrelation(String collectionName) {
        if (database == null) {
            throw new IllegalStateException(
                "Database not configured for correlated parameters. " +
                "Use constructor with MongoDatabase parameter.");
        }

        log.info("Loading documents from {} for correlated parameter extraction", collectionName);

        MongoCollection<Document> collection = database.getCollection(collectionName);
        List<Document> documents = new ArrayList<>();

        try (var cursor = collection.find()
                .limit(DEFAULT_SAMPLE_SIZE)
                .iterator()) {
            while (cursor.hasNext()) {
                documents.add(cursor.next());
            }
        }

        log.info("Loaded {} documents from {} for correlation", documents.size(), collectionName);
        return documents;
    }

    private Object substituteValue(Object value) {
        if (value instanceof String strValue) {
            Matcher matcher = PARAM_PATTERN.matcher(strValue);
            if (matcher.matches()) {
                String paramName = matcher.group(1);
                return generateValue(paramName);
            }
            return strValue;
        } else if (value instanceof Document doc) {
            return substituteParameters(doc);
        } else if (value instanceof List<?> list) {
            return list.stream()
                .map(this::substituteValue)
                .toList();
        }
        return value;
    }

    private Object generateValue(String paramName) {
        ParameterDefinition paramDef = parameterDefs.get(paramName);
        if (paramDef == null) {
            throw new IllegalArgumentException("Unknown parameter: " + paramName);
        }

        // Check if this is a correlated parameter
        String correlationGroup = paramDef.getCorrelationGroup();
        if (correlationGroup != null) {
            return generateFromCorrelatedDocument(paramName, paramDef, correlationGroup);
        }

        return switch (paramDef.getType()) {
            case "random_range" -> generateRandomRange(paramDef);
            case "random_choice" -> generateRandomChoice(paramDef);
            case "sequential" -> generateSequential(paramName, paramDef);
            case "fixed" -> paramDef.getFixedValue();
            case "random_pattern" -> generateRandomPattern(paramDef);
            case "random_from_loaded" -> generateRandomFromLoaded(paramDef);
            default -> throw new IllegalArgumentException("Unknown parameter type: " + paramDef.getType());
        };
    }

    /**
     * Generate a value by extracting from a pre-selected correlated document.
     */
    private Object generateFromCorrelatedDocument(String paramName, ParameterDefinition paramDef, String group) {
        Document doc = currentCorrelatedDocuments.get().get(group);
        if (doc == null) {
            throw new IllegalStateException(
                "No document selected for correlation group '" + group +
                "'. Parameter: " + paramName);
        }

        String field = paramDef.getField();
        if (field == null) {
            throw new IllegalArgumentException(
                "Correlated parameter '" + paramName + "' requires 'field' to be specified");
        }

        // Extract value from the correlated document
        List<Object> values = extractAllNestedValues(doc, field);
        if (values.isEmpty()) {
            log.warn("No value found for field '{}' in correlated document for parameter '{}'",
                field, paramName);
            return null;
        }

        // If multiple values (e.g., from array), pick a random one
        if (values.size() == 1) {
            return values.get(0);
        }

        Random random = ThreadLocalRandom.current();
        return values.get(random.nextInt(values.size()));
    }

    private Object generateRandomRange(ParameterDefinition paramDef) {
        Random random = ThreadLocalRandom.current();
        long min = paramDef.getMin();
        long max = paramDef.getMax();
        return min + random.nextLong(max - min + 1);
    }

    private Object generateRandomChoice(ParameterDefinition paramDef) {
        Random random = ThreadLocalRandom.current();
        List<Object> values = paramDef.getValues();
        return values.get(random.nextInt(values.size()));
    }

    private Object generateSequential(String paramName, ParameterDefinition paramDef) {
        AtomicLong counter = sequentialCounters.computeIfAbsent(paramName,
            k -> new AtomicLong(paramDef.getMin()));

        long value = counter.getAndIncrement();
        if (value > paramDef.getMax()) {
            counter.set(paramDef.getMin());
            value = paramDef.getMin();
        }
        return value;
    }

    /**
     * Generate a random string matching a simple regex-like pattern.
     * Supports:
     * - \d{n} - n digits
     * - [A-Z]{n} - n uppercase letters
     * - [a-z]{n} - n lowercase letters
     * - [A-Za-z]{n} - n letters (mixed case)
     * - Literal characters (including -)
     *
     * Examples:
     * - \d{4} -> "1234"
     * - \d{3}-\d{2}-\d{4} -> "123-45-6789" (SSN format)
     * - [A-Z]{2}\d{6} -> "AB123456"
     */
    private Object generateRandomPattern(ParameterDefinition paramDef) {
        String pattern = paramDef.getPattern();
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("Pattern is required for random_pattern type");
        }

        Random random = ThreadLocalRandom.current();
        StringBuilder result = new StringBuilder();
        int i = 0;

        while (i < pattern.length()) {
            if (pattern.startsWith("\\d", i)) {
                // Handle \d or \d{n}
                i += 2;
                int count = parseRepetition(pattern, i);
                i = skipRepetition(pattern, i);
                for (int j = 0; j < count; j++) {
                    result.append((char) ('0' + random.nextInt(10)));
                }
            } else if (pattern.startsWith("[A-Z]", i)) {
                // Handle [A-Z] or [A-Z]{n}
                i += 5;
                int count = parseRepetition(pattern, i);
                i = skipRepetition(pattern, i);
                for (int j = 0; j < count; j++) {
                    result.append((char) ('A' + random.nextInt(26)));
                }
            } else if (pattern.startsWith("[a-z]", i)) {
                // Handle [a-z] or [a-z]{n}
                i += 5;
                int count = parseRepetition(pattern, i);
                i = skipRepetition(pattern, i);
                for (int j = 0; j < count; j++) {
                    result.append((char) ('a' + random.nextInt(26)));
                }
            } else if (pattern.startsWith("[A-Za-z]", i)) {
                // Handle [A-Za-z] or [A-Za-z]{n}
                i += 8;
                int count = parseRepetition(pattern, i);
                i = skipRepetition(pattern, i);
                for (int j = 0; j < count; j++) {
                    if (random.nextBoolean()) {
                        result.append((char) ('A' + random.nextInt(26)));
                    } else {
                        result.append((char) ('a' + random.nextInt(26)));
                    }
                }
            } else if (pattern.startsWith("[0-9]", i)) {
                // Handle [0-9] or [0-9]{n} (same as \d)
                i += 5;
                int count = parseRepetition(pattern, i);
                i = skipRepetition(pattern, i);
                for (int j = 0; j < count; j++) {
                    result.append((char) ('0' + random.nextInt(10)));
                }
            } else {
                // Literal character
                result.append(pattern.charAt(i));
                i++;
            }
        }

        return result.toString();
    }

    /**
     * Parse the repetition count from {n} syntax.
     * Returns 1 if no repetition is specified.
     */
    private int parseRepetition(String pattern, int start) {
        if (start < pattern.length() && pattern.charAt(start) == '{') {
            int end = pattern.indexOf('}', start);
            if (end > start) {
                return Integer.parseInt(pattern.substring(start + 1, end));
            }
        }
        return 1;
    }

    /**
     * Skip past the {n} syntax if present.
     */
    private int skipRepetition(String pattern, int start) {
        if (start < pattern.length() && pattern.charAt(start) == '{') {
            int end = pattern.indexOf('}', start);
            if (end > start) {
                return end + 1;
            }
        }
        return start;
    }

    /**
     * Generate a random value by sampling from loaded data in the database.
     * Supports nested field paths like "phoneKey.phoneNumber" or "common.taxIdentificationNumber".
     * Caches loaded values for performance.
     */
    private Object generateRandomFromLoaded(ParameterDefinition paramDef) {
        if (database == null) {
            throw new IllegalStateException("Database not configured for random_from_loaded parameter type. " +
                "Use constructor with MongoDatabase parameter.");
        }

        String collection = paramDef.getCollection();
        String field = paramDef.getField();
        String cacheKey = collection + ":" + field;

        // Check cache first
        List<Object> cachedValues = loadedValueCache.get(cacheKey);
        if (cachedValues == null) {
            cachedValues = loadValuesFromDatabase(collection, field);
            loadedValueCache.put(cacheKey, cachedValues);
        }

        if (cachedValues.isEmpty()) {
            throw new IllegalStateException("No values found for " + field + " in collection " + collection);
        }

        // Return random value from cache
        Random random = ThreadLocalRandom.current();
        return cachedValues.get(random.nextInt(cachedValues.size()));
    }

    /**
     * Load sample values from the database.
     * Uses a projection to only fetch the required field.
     */
    private List<Object> loadValuesFromDatabase(String collectionName, String fieldPath) {
        log.info("Loading sample values from {}.{}", collectionName, fieldPath);

        MongoCollection<Document> collection = database.getCollection(collectionName);

        // Build projection for the field path
        Document projection = new Document();
        String[] parts = fieldPath.split("\\.");
        String rootField = parts[0];
        projection.append(rootField, 1);  // Project the root field

        // Only exclude _id if we're not querying an _id subfield
        if (!rootField.equals("_id")) {
            projection.append("_id", 0);
        }

        List<Object> values = new ArrayList<>();
        try (var cursor = collection.find()
                .projection(projection)
                .limit(DEFAULT_SAMPLE_SIZE)
                .skip(0)
                .iterator()) {

            while (cursor.hasNext()) {
                Document doc = cursor.next();
                // Use extractAllNestedValues to handle array fields
                List<Object> extractedValues = extractAllNestedValues(doc, fieldPath);
                values.addAll(extractedValues);
            }
        }

        log.info("Loaded {} sample values for {}.{}", values.size(), collectionName, fieldPath);
        return values;
    }

    /**
     * Extract a value from a nested field path like "phoneKey.phoneNumber".
     * Does not handle arrays - use extractAllNestedValues for array support.
     */
    private Object extractNestedValue(Document doc, String fieldPath) {
        String[] parts = fieldPath.split("\\.");
        Object current = doc;

        for (String part : parts) {
            if (current instanceof Document d) {
                current = d.get(part);
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }

        return current;
    }

    /**
     * Extract all values from a nested field path, supporting arrays.
     * For paths like "addresses.postalCode" where "addresses" is an array,
     * this will extract postalCode from each element in the array.
     *
     * @param doc The document to extract from
     * @param fieldPath The dot-separated field path (e.g., "addresses.postalCode")
     * @return List of all values found at the path (may be empty)
     */
    public List<Object> extractAllNestedValues(Document doc, String fieldPath) {
        List<Object> results = new ArrayList<>();
        String[] parts = fieldPath.split("\\.");
        extractValuesRecursive(doc, parts, 0, results);
        return results;
    }

    /**
     * Recursively extract values from nested structure, handling arrays.
     */
    private void extractValuesRecursive(Object current, String[] pathParts, int index, List<Object> results) {
        if (current == null) {
            return;
        }

        // If we've processed all path parts, add the current value to results
        if (index >= pathParts.length) {
            results.add(current);
            return;
        }

        String part = pathParts[index];

        if (current instanceof Document doc) {
            Object child = doc.get(part);
            if (child instanceof List<?> list) {
                // If the child is a list, recurse into each element
                for (Object item : list) {
                    extractValuesRecursive(item, pathParts, index + 1, results);
                }
            } else {
                // Not a list, continue normally
                extractValuesRecursive(child, pathParts, index + 1, results);
            }
        } else if (current instanceof List<?> list) {
            // If current is a list, recurse into each element with the same path part
            for (Object item : list) {
                extractValuesRecursive(item, pathParts, index, results);
            }
        }
        // For other types (primitives, etc.), we can't traverse further
    }

    /**
     * Reset sequential counters and cached values for a new benchmark run.
     */
    public void reset() {
        sequentialCounters.clear();
        loadedValueCache.clear();
        correlatedDocumentCache.clear();
        currentCorrelatedDocuments.get().clear();
    }
}
