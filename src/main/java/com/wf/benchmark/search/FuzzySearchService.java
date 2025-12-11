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
 * Service for fuzzy text search using Oracle Text CONTAINS operator.
 * Uses Oracle Text indexes with fuzzy matching for approximate string searches.
 *
 * Oracle Text fuzzy matching supports:
 * - Spelling variations
 * - Typos
 * - Character transpositions
 * - Missing/extra characters
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
     * Search for names using fuzzy matching.
     * Uses Oracle Text CONTAINS with FUZZY operator.
     *
     * @param searchTerm The name to search for (can be partial or misspelled)
     * @param collection The collection/table name (e.g., "identity")
     * @param limit Maximum number of results to return
     * @return List of matching results with scores
     */
    public List<FuzzySearchResult> searchByName(String searchTerm, String collection, int limit) {
        validateSearchParams(searchTerm, collection);

        String sql = buildNameSearchQuery(collection, limit);
        String fuzzyTerm = buildFuzzySearchTerm(searchTerm);

        log.debug("Executing fuzzy name search: term='{}', collection='{}', limit={}",
                  searchTerm, collection, limit);

        return executeSearch(sql, fuzzyTerm, "full_name");
    }

    /**
     * Search for business names using fuzzy matching.
     * Handles special characters and common business name variations.
     *
     * @param searchTerm The business name to search for
     * @param collection The collection/table name
     * @param limit Maximum number of results to return
     * @return List of matching results with scores
     */
    public List<FuzzySearchResult> searchByBusinessName(String searchTerm, String collection, int limit) {
        validateSearchParams(searchTerm, collection);

        String sql = buildBusinessNameSearchQuery(collection, limit);
        String sanitizedTerm = sanitizeBusinessName(searchTerm);
        String fuzzyTerm = buildFuzzySearchTerm(sanitizedTerm);

        log.debug("Executing fuzzy business name search: term='{}', sanitized='{}', collection='{}', limit={}",
                  searchTerm, sanitizedTerm, collection, limit);

        return executeSearch(sql, fuzzyTerm, "business_name");
    }

    /**
     * Build the SQL query for name search using Oracle Text.
     * Uses json_value() to extract fields from JSON documents.
     * Note: MongoDB API for Oracle stores JSON in a column named DATA.
     */
    private String buildNameSearchQuery(String collection, int limit) {
        return """
            SELECT
                json_value(DATA, '$._id.customerNumber') as customer_number,
                json_value(DATA, '$.common.fullName') as full_name,
                SCORE(1) as score
            FROM %s
            WHERE CONTAINS(DATA, ?, 1) > 0
            AND SCORE(1) >= ?
            ORDER BY SCORE(1) DESC
            FETCH FIRST ? ROWS ONLY
            """.formatted(collection);
    }

    /**
     * Build the SQL query for business name search using Oracle Text.
     * Note: MongoDB API for Oracle stores JSON in a column named DATA.
     */
    private String buildBusinessNameSearchQuery(String collection, int limit) {
        return """
            SELECT
                json_value(DATA, '$._id.customerNumber') as customer_number,
                json_value(DATA, '$.business.businessName') as business_name,
                SCORE(1) as score
            FROM %s
            WHERE CONTAINS(DATA, ?, 1) > 0
            AND json_value(DATA, '$.common.entityTypeIndicator') = 'BUSINESS'
            AND SCORE(1) >= ?
            ORDER BY SCORE(1) DESC
            FETCH FIRST ? ROWS ONLY
            """.formatted(collection);
    }

    /**
     * Build a fuzzy search term for Oracle Text CONTAINS.
     * Uses the FUZZY operator with configurable similarity threshold.
     */
    private String buildFuzzySearchTerm(String searchTerm) {
        // Oracle Text FUZZY syntax: FUZZY(term, score, numexpand)
        // score: minimum similarity (1-80), lower = more fuzzy
        // numexpand: max expansions to consider
        return "FUZZY(" + escapeOracleText(searchTerm) + ", 70, 100)";
    }

    /**
     * Sanitize business name by removing special characters that cause issues.
     */
    private String sanitizeBusinessName(String name) {
        if (name == null) {
            return null;
        }
        // Remove common business suffixes and special characters
        return name
            .replaceAll("[&]", " ")
            .replaceAll("[,.]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    /**
     * Escape special characters for Oracle Text query.
     */
    private String escapeOracleText(String term) {
        if (term == null) {
            return null;
        }
        // Escape Oracle Text special characters: { } ( ) [ ] - & | ! > < ~ *
        return term
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("-", "\\-")
            .replace("&", "\\&")
            .replace("|", "\\|")
            .replace("!", "\\!")
            .replace(">", "\\>")
            .replace("<", "\\<")
            .replace("~", "\\~")
            .replace("*", "\\*");
    }

    /**
     * Execute the search query and map results.
     */
    private List<FuzzySearchResult> executeSearch(String sql, String fuzzyTerm, String valueColumn) {
        List<FuzzySearchResult> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, fuzzyTerm);
            stmt.setInt(2, minScore);
            stmt.setInt(3, 100); // limit parameter position

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String customerNumber = rs.getString("customer_number");
                    String matchedValue = rs.getString(valueColumn);
                    int score = rs.getInt("score");

                    results.add(new FuzzySearchResult(customerNumber, matchedValue, score));
                }
            }

        } catch (SQLException e) {
            log.error("Fuzzy search failed: {}", e.getMessage(), e);
            throw new SearchException("Fuzzy search failed", e);
        }

        log.debug("Fuzzy search returned {} results", results.size());
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
