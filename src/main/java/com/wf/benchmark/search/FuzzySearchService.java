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
 * Service for fuzzy text search using Oracle JSON_TEXTCONTAINS.
 * Uses JSON Search Index for approximate string searches on JSON data.
 *
 * For Oracle Autonomous JSON Database (AJD) with native JSON columns,
 * we use JSON_TEXTCONTAINS() which works with CREATE SEARCH INDEX ... FOR JSON.
 *
 * JSON_TEXTCONTAINS supports:
 * - Full-text search within JSON documents
 * - Searching specific JSON paths
 * - Word-based matching
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
     * Search for names using JSON_TEXTCONTAINS with JSON Search Index.
     * Performs word-based matching on the fullName field in JSON documents.
     *
     * @param searchTerm The name to search for (word-based matching)
     * @param collection The collection/table name (e.g., "identity")
     * @param limit Maximum number of results to return
     * @return List of matching results with scores
     */
    public List<FuzzySearchResult> searchByName(String searchTerm, String collection, int limit) {
        validateSearchParams(searchTerm, collection);

        String sql = buildNameSearchQuery(collection, limit);
        String sanitizedTerm = sanitizeSearchTerm(searchTerm);

        log.debug("Executing JSON text search for name: term='{}', collection='{}', limit={}",
                  searchTerm, collection, limit);

        return executeSearch(sql, sanitizedTerm, "full_name");
    }

    /**
     * Search for business names using JSON_TEXTCONTAINS.
     * Performs word-based matching on the businessName field in JSON documents.
     *
     * @param searchTerm The business name to search for
     * @param collection The collection/table name
     * @param limit Maximum number of results to return
     * @return List of matching results with scores
     */
    public List<FuzzySearchResult> searchByBusinessName(String searchTerm, String collection, int limit) {
        validateSearchParams(searchTerm, collection);

        String sql = buildBusinessNameSearchQuery(collection, limit);
        String sanitizedTerm = sanitizeSearchTerm(searchTerm);

        log.debug("Executing JSON text search for business name: term='{}', sanitized='{}', collection='{}', limit={}",
                  searchTerm, sanitizedTerm, collection, limit);

        return executeSearch(sql, sanitizedTerm, "business_name");
    }

    /**
     * Build the SQL query for name search using JSON_TEXTCONTAINS.
     * Uses JSON Search Index for full-text search within JSON documents.
     * Note: MongoDB API for Oracle stores JSON in a column named DATA.
     *
     * JSON_TEXTCONTAINS syntax: JSON_TEXTCONTAINS(column, json_path, search_string)
     */
    private String buildNameSearchQuery(String collection, int limit) {
        return """
            SELECT
                json_value(DATA, '$._id.customerNumber') as customer_number,
                json_value(DATA, '$.common.fullName') as full_name,
                100 as score
            FROM %s
            WHERE JSON_TEXTCONTAINS(DATA, '$.common.fullName', ?)
            FETCH FIRST ? ROWS ONLY
            """.formatted(collection);
    }

    /**
     * Build the SQL query for business name search using JSON_TEXTCONTAINS.
     * Uses JSON Search Index for full-text search within JSON documents.
     * Note: MongoDB API for Oracle stores JSON in a column named DATA.
     */
    private String buildBusinessNameSearchQuery(String collection, int limit) {
        return """
            SELECT
                json_value(DATA, '$._id.customerNumber') as customer_number,
                json_value(DATA, '$.business.businessName') as business_name,
                100 as score
            FROM %s
            WHERE JSON_TEXTCONTAINS(DATA, '$.business.businessName', ?)
            AND json_value(DATA, '$.common.entityTypeIndicator') = 'BUSINESS'
            FETCH FIRST ? ROWS ONLY
            """.formatted(collection);
    }

    /**
     * Sanitize search term for JSON_TEXTCONTAINS.
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
