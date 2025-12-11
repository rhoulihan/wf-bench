package com.wf.benchmark.search;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Result from a hybrid search operation.
 * Contains the matched customer identifier, matched value, score,
 * and which search strategies produced the match.
 */
public class HybridSearchResult {

    /**
     * Enumeration of search strategies that can produce matches.
     */
    public enum MatchStrategy {
        FUZZY,      // Oracle Text CONTAINS with FUZZY
        PHONETIC,   // SOUNDEX phonetic matching
        VECTOR      // Oracle AI Vector Search
    }

    private final String customerNumber;
    private final String matchedValue;
    private final double score;
    private final Set<MatchStrategy> matchStrategies;

    public HybridSearchResult(String customerNumber, String matchedValue, double score,
                              Set<MatchStrategy> matchStrategies) {
        this.customerNumber = customerNumber;
        this.matchedValue = matchedValue;
        this.score = score;
        this.matchStrategies = matchStrategies != null
            ? EnumSet.copyOf(matchStrategies)
            : EnumSet.noneOf(MatchStrategy.class);
    }

    /**
     * Create a result from a single strategy.
     */
    public static HybridSearchResult fromFuzzy(String customerNumber, String matchedValue, double score) {
        return new HybridSearchResult(customerNumber, matchedValue, score,
            EnumSet.of(MatchStrategy.FUZZY));
    }

    public static HybridSearchResult fromPhonetic(String customerNumber, String matchedValue, double score) {
        return new HybridSearchResult(customerNumber, matchedValue, score,
            EnumSet.of(MatchStrategy.PHONETIC));
    }

    public static HybridSearchResult fromVector(String customerNumber, String matchedValue, double score) {
        return new HybridSearchResult(customerNumber, matchedValue, score,
            EnumSet.of(MatchStrategy.VECTOR));
    }

    public String getCustomerNumber() {
        return customerNumber;
    }

    public String getMatchedValue() {
        return matchedValue;
    }

    public double getScore() {
        return score;
    }

    public Set<MatchStrategy> getMatchStrategies() {
        return Collections.unmodifiableSet(matchStrategies);
    }

    /**
     * Create a new result that merges this result with another.
     * Uses the higher score and combines strategies.
     */
    public HybridSearchResult mergeWith(HybridSearchResult other) {
        if (!this.customerNumber.equals(other.customerNumber)) {
            throw new IllegalArgumentException("Cannot merge results for different customers");
        }

        double maxScore = Math.max(this.score, other.score);
        String value = this.score >= other.score ? this.matchedValue : other.matchedValue;

        EnumSet<MatchStrategy> combined = EnumSet.copyOf(this.matchStrategies);
        combined.addAll(other.matchStrategies);

        return new HybridSearchResult(customerNumber, value, maxScore, combined);
    }

    @Override
    public String toString() {
        return "HybridSearchResult{" +
                "customerNumber='" + customerNumber + '\'' +
                ", matchedValue='" + matchedValue + '\'' +
                ", score=" + score +
                ", matchStrategies=" + matchStrategies +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HybridSearchResult that = (HybridSearchResult) o;
        return customerNumber.equals(that.customerNumber);
    }

    @Override
    public int hashCode() {
        return customerNumber.hashCode();
    }
}
