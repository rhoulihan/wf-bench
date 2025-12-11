package com.wf.benchmark.search;

/**
 * Result from a fuzzy search operation.
 * Contains the matched customer identifier, the matched value, and a relevance score.
 */
public class FuzzySearchResult {

    private final String customerNumber;
    private final String matchedValue;
    private final int score;

    public FuzzySearchResult(String customerNumber, String matchedValue, int score) {
        this.customerNumber = customerNumber;
        this.matchedValue = matchedValue;
        this.score = score;
    }

    public String getCustomerNumber() {
        return customerNumber;
    }

    public String getMatchedValue() {
        return matchedValue;
    }

    public int getScore() {
        return score;
    }

    @Override
    public String toString() {
        return "FuzzySearchResult{" +
                "customerNumber='" + customerNumber + '\'' +
                ", matchedValue='" + matchedValue + '\'' +
                ", score=" + score +
                '}';
    }
}
