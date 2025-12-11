package com.wf.benchmark.search;

/**
 * Result from a vector similarity search operation.
 * Contains the matched customer identifier, description, and similarity score.
 */
public class VectorSearchResult {

    private final String customerNumber;
    private final String matchedValue;
    private final double similarityScore;

    public VectorSearchResult(String customerNumber, String matchedValue, double similarityScore) {
        this.customerNumber = customerNumber;
        this.matchedValue = matchedValue;
        this.similarityScore = similarityScore;
    }

    public String getCustomerNumber() {
        return customerNumber;
    }

    public String getMatchedValue() {
        return matchedValue;
    }

    public double getSimilarityScore() {
        return similarityScore;
    }

    @Override
    public String toString() {
        return "VectorSearchResult{" +
                "customerNumber='" + customerNumber + '\'' +
                ", matchedValue='" + matchedValue + '\'' +
                ", similarityScore=" + similarityScore +
                '}';
    }
}
