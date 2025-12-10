package com.wf.benchmark.query;

import com.wf.benchmark.config.QueryConfig.ParameterDefinition;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bson.Document;

/**
 * Generates parameter values for query execution based on configuration.
 */
public class ParameterGenerator {

    private static final Pattern PARAM_PATTERN = Pattern.compile("\\$\\{param:([^}]+)\\}");

    private final Map<String, ParameterDefinition> parameterDefs;
    private final Map<String, AtomicLong> sequentialCounters = new java.util.HashMap<>();

    public ParameterGenerator(Map<String, ParameterDefinition> parameterDefs) {
        this.parameterDefs = parameterDefs;
    }

    /**
     * Substitute parameters in a filter document with generated values.
     */
    public Document substituteParameters(Document filter) {
        if (filter == null || parameterDefs == null || parameterDefs.isEmpty()) {
            return filter;
        }

        Document result = new Document();
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            result.put(entry.getKey(), substituteValue(entry.getValue()));
        }
        return result;
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

        return switch (paramDef.getType()) {
            case "random_range" -> generateRandomRange(paramDef);
            case "random_choice" -> generateRandomChoice(paramDef);
            case "sequential" -> generateSequential(paramName, paramDef);
            case "fixed" -> paramDef.getFixedValue();
            default -> throw new IllegalArgumentException("Unknown parameter type: " + paramDef.getType());
        };
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
     * Reset sequential counters for a new benchmark run.
     */
    public void reset() {
        sequentialCounters.clear();
    }
}
