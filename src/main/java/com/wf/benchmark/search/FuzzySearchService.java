package com.wf.benchmark.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for fuzzy text search using Oracle Text CONTAINS with FUZZY operator.
 * Uses JSON Search Index for typo-tolerant searches on JSON data.
 *
 * For Oracle Autonomous JSON Database (AJD) with native JSON columns,
 * we use CONTAINS() with FUZZY operator which works with CREATE SEARCH INDEX ... FOR JSON.
 *
 * CONTAINS with FUZZY supports:
 * - Fuzzy matching with configurable edit distance
 * - Typo tolerance (misspellings return correct matches)
 * - Word-based and phrase searches
 *
 * Syntax: CONTAINS(DATA, 'fuzzy((term), score, numresults, weight)', label) > 0
 * - score: minimum similarity (1-100, higher = more similar)
 * - numresults: max expansions per term
 * - weight: boost factor for scoring
 *
 * This service queries JSON data stored in Oracle Autonomous JSON Database
 * using SQL with json_value() to extract fields from JSON documents.
 */
public class FuzzySearchService {

    private static final Logger log = LoggerFactory.getLogger(FuzzySearchService.class);
    private static final int DEFAULT_MIN_SCORE = 60;

    private final DataSource dataSource;
    private int minScore = DEFAULT_MIN_SCORE;

    public FuzzySearchService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Search for names using Oracle Text CONTAINS with FUZZY operator.
     * Performs typo-tolerant fuzzy matching on the fullName field in JSON documents.
     *
     * @param searchTerm The name to search for (fuzzy matching with typo tolerance)
     * @param collection The collection/table name (e.g., "identity")
     * @param limit Maximum number of results to return
     * @return List of matching results with scores
     */
    public List<FuzzySearchResult> searchByName(String searchTerm, String collection, int limit) {
        validateSearchParams(searchTerm, collection);

        String sql = buildNameSearchQuery(collection, limit);
        String fuzzyTerm = buildFuzzySearchTerm(searchTerm);

        log.debug("Executing fuzzy search for name: term='{}', fuzzyTerm='{}', collection='{}', limit={}",
                  searchTerm, fuzzyTerm, collection, limit);

        return executeSearch(sql, fuzzyTerm, "full_name");
    }

    /**
     * Search for business names using Oracle Text CONTAINS with FUZZY operator.
     * Performs typo-tolerant fuzzy matching on the businessName field in JSON documents.
     *
     * @param searchTerm The business name to search for
     * @param collection The collection/table name
     * @param limit Maximum number of results to return
     * @return List of matching results with scores
     */
    public List<FuzzySearchResult> searchByBusinessName(String searchTerm, String collection, int limit) {
        validateSearchParams(searchTerm, collection);

        String sql = buildBusinessNameSearchQuery(collection, limit);
        String fuzzyTerm = buildFuzzySearchTerm(searchTerm);

        log.debug("Executing fuzzy search for business name: term='{}', fuzzyTerm='{}', collection='{}', limit={}",
                  searchTerm, fuzzyTerm, collection, limit);

        return executeSearch(sql, fuzzyTerm, "business_name");
    }

    /**
     * Build the SQL query for name search using Oracle Text CONTAINS with FUZZY.
     * Uses JSON Search Index for fuzzy text search within JSON documents.
     * Note: MongoDB API for Oracle stores JSON in a column named DATA.
     *
     * CONTAINS syntax: CONTAINS(column, 'fuzzy((term), minScore, maxExpansions, weight)', label) > 0
     * - The fuzzy operator provides typo-tolerance via edit distance matching
     * - SCORE(label) returns the relevance score (0-100)
     */
    private String buildNameSearchQuery(String collection, int limit) {
        return """
            SELECT
                json_value(DATA, '$._id.customerNumber') as customer_number,
                json_value(DATA, '$.common.fullName') as full_name,
                SCORE(1) as score
            FROM %s
            WHERE CONTAINS(DATA, ?, 1) > 0
            ORDER BY SCORE(1) DESC
            FETCH FIRST ? ROWS ONLY
            """.formatted(collection);
    }

    /**
     * Build the SQL query for business name search using Oracle Text CONTAINS with FUZZY.
     * Uses JSON Search Index for fuzzy text search within JSON documents.
     * Note: MongoDB API for Oracle stores JSON in a column named DATA.
     */
    private String buildBusinessNameSearchQuery(String collection, int limit) {
        return """
            SELECT
                json_value(DATA, '$._id.customerNumber') as customer_number,
                json_value(DATA, '$.common.fullName') as business_name,
                SCORE(1) as score
            FROM %s
            WHERE CONTAINS(DATA, ?, 1) > 0
            AND json_value(DATA, '$.common.entityTypeIndicator') = 'NON_INDIVIDUAL'
            ORDER BY SCORE(1) DESC
            FETCH FIRST ? ROWS ONLY
            """.formatted(collection);
    }

    /**
     * Build a fuzzy search term for Oracle Text CONTAINS operator.
     * Uses the FUZZY operator for typo-tolerant matching.
     *
     * Syntax for JSON Search Index: fuzzy(term)
     * - With JSON Search Index, FUZZY only takes the term
     * - minScore is applied via global fuzzy settings or SCORE threshold
     *
     * For multi-word searches, each word gets the FUZZY operator and they're combined with AND.
     * Example: "JOHN SMITH" -> "fuzzy(JOHN) AND fuzzy(SMITH)"
     */
    private String buildFuzzySearchTerm(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }

        // Sanitize: remove special characters, normalize whitespace
        String sanitized = term
            .replaceAll("[&,.]", " ")
            .replaceAll("[^a-zA-Z0-9\\s]", "")
            .replaceAll("\\s+", " ")
            .trim()
            .toUpperCase();

        if (sanitized.isEmpty()) {
            return null;
        }

        // Split into words and create fuzzy expression for each
        String[] words = sanitized.split("\\s+");
        StringBuilder fuzzyExpr = new StringBuilder();

        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                fuzzyExpr.append(" AND ");
            }
            // Simple fuzzy syntax for JSON Search Index
            fuzzyExpr.append("fuzzy(").append(words[i]).append(")");
        }

        return fuzzyExpr.toString();
    }

    /**
     * Sanitize search term for JSON_TEXTCONTAINS (backward compatibility).
     * Removes special characters that might cause issues with text search.
     */
    private String sanitizeSearchTerm(String term) {
        if (term == null) {
            return null;
        }
        // Remove special characters and normalize whitespace
        return term
            .replaceAll("[&,.]", " ")
            .replaceAll("[^a-zA-Z0-9\\s]", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    /**
     * Execute the search query and map results.
     * Query has 2 bind parameters: search term and limit.
     */
    private List<FuzzySearchResult> executeSearch(String sql, String searchTerm, String valueColumn) {
        List<FuzzySearchResult> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, searchTerm);
            stmt.setInt(2, 100); // limit parameter

            log.debug("Executing SQL: {} with searchTerm='{}', limit={}", sql.trim().replaceAll("\\s+", " "), searchTerm, 100);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String customerNumber = rs.getString("customer_number");
                    String matchedValue = rs.getString(valueColumn);
                    int score = rs.getInt("score");

                    results.add(new FuzzySearchResult(customerNumber, matchedValue, score));
                }
            }

        } catch (SQLException e) {
            log.error("JSON text search failed: {}", e.getMessage(), e);
            throw new SearchException("JSON text search failed", e);
        }

        log.debug("JSON text search returned {} results", results.size());
        return results;
    }

    /**
     * Validate search parameters.
     */
    private void validateSearchParams(String searchTerm, String collection) {
        if (searchTerm == null) {
            throw new IllegalArgumentException("Search term cannot be null");
        }
        if (searchTerm.isEmpty()) {
            throw new IllegalArgumentException("Search term cannot be empty");
        }
        if (collection == null) {
            throw new IllegalArgumentException("Collection cannot be null");
        }
    }

    /**
     * Get the minimum score threshold for fuzzy matches.
     */
    public int getMinScore() {
        return minScore;
    }

    /**
     * Set the minimum score threshold for fuzzy matches.
     * Valid range is 0-100.
     *
     * @param minScore Minimum score (0-100)
     */
    public void setMinScore(int minScore) {
        if (minScore < 0 || minScore > 100) {
            throw new IllegalArgumentException("Min score must be between 0 and 100");
        }
        this.minScore = minScore;
    }
}
