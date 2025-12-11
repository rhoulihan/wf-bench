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
 * Service for semantic similarity search using Oracle AI Vector Search (23ai).
 * Uses vector embeddings to find semantically similar content.
 *
 * Vector search enables:
 * - Natural language queries ("find customers in banking sector")
 * - Semantic similarity ("similar to John Smith who works at Acme Corp")
 * - Concept matching (similar meaning, different words)
 *
 * This service requires:
 * 1. A vector column with pre-computed embeddings
 * 2. An ONNX model loaded in the database for embedding generation
 * 3. A vector index for efficient similarity search
 *
 * SQL example:
 * SELECT * FROM identity
 * WHERE VECTOR_DISTANCE(embedding_col, TO_VECTOR(:query_embedding), COSINE) < :threshold
 * ORDER BY VECTOR_DISTANCE(embedding_col, TO_VECTOR(:query_embedding), COSINE)
 */
public class VectorSearchService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);
    private static final double DEFAULT_MIN_SIMILARITY = 0.7;
    private static final String DEFAULT_EMBEDDING_MODEL = "all-MiniLM-L6-v2";

    private final DataSource dataSource;
    private double minSimilarity = DEFAULT_MIN_SIMILARITY;
    private String embeddingModel = DEFAULT_EMBEDDING_MODEL;

    public VectorSearchService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Search for content semantically similar to a natural language query.
     * Uses embedding generation and vector distance calculation.
     *
     * @param query Natural language query describing what to find
     * @param collection The collection/table name
     * @param limit Maximum number of results to return
     * @return List of matching results ordered by similarity
     */
    public List<VectorSearchResult> searchByDescription(String query, String collection, int limit) {
        validateSearchParams(query, collection);

        String sql = buildVectorSearchQuery(collection, limit);

        log.debug("Executing vector search: query='{}', collection='{}', limit={}, minSimilarity={}",
                  query, collection, limit, minSimilarity);

        return executeSearch(sql, query);
    }

    /**
     * Find customers similar to a reference customer.
     * Uses the reference customer's embedding to find similar records.
     *
     * @param customerNumber The reference customer number
     * @param collection The collection/table name
     * @param limit Maximum number of results to return
     * @return List of similar customers (excluding the reference)
     */
    public List<VectorSearchResult> findSimilarToCustomer(String customerNumber, String collection, int limit) {
        if (customerNumber == null) {
            throw new IllegalArgumentException("Customer number cannot be null");
        }
        if (collection == null) {
            throw new IllegalArgumentException("Collection cannot be null");
        }

        String sql = buildSimilarCustomerQuery(collection, limit);

        log.debug("Executing similar customer search: customerNumber='{}', collection='{}', limit={}",
                  customerNumber, collection, limit);

        return executeSearchByCustomer(sql, customerNumber);
    }

    /**
     * Build the SQL query for vector similarity search.
     * Uses Oracle AI Vector Search syntax with VECTOR_DISTANCE function.
     * Note: MongoDB API for Oracle stores JSON in a column named DATA.
     */
    private String buildVectorSearchQuery(String collection, int limit) {
        // Oracle AI Vector Search syntax
        // The query text needs to be converted to an embedding first
        return """
            SELECT
                json_value(DATA, '$._id.customerNumber') as customer_number,
                json_value(DATA, '$.common.fullName') as description,
                (1 - VECTOR_DISTANCE(
                    embedding,
                    VECTOR_EMBEDDING(%s USING ? as data),
                    COSINE
                )) as similarity_score
            FROM %s
            WHERE (1 - VECTOR_DISTANCE(
                embedding,
                VECTOR_EMBEDDING(%s USING ? as data),
                COSINE
            )) >= ?
            ORDER BY similarity_score DESC
            FETCH FIRST ? ROWS ONLY
            """.formatted(embeddingModel, collection, embeddingModel);
    }

    /**
     * Build the SQL query to find customers similar to a reference customer.
     * Note: MongoDB API for Oracle stores JSON in a column named DATA.
     */
    private String buildSimilarCustomerQuery(String collection, int limit) {
        return """
            SELECT
                t2.customer_number,
                t2.description,
                (1 - VECTOR_DISTANCE(t1.embedding, t2.embedding, COSINE)) as similarity_score
            FROM (
                SELECT embedding
                FROM %s
                WHERE json_value(DATA, '$._id.customerNumber') = ?
            ) t1
            CROSS JOIN (
                SELECT
                    json_value(DATA, '$._id.customerNumber') as customer_number,
                    json_value(DATA, '$.common.fullName') as description,
                    embedding
                FROM %s
                WHERE json_value(DATA, '$._id.customerNumber') != ?
            ) t2
            WHERE (1 - VECTOR_DISTANCE(t1.embedding, t2.embedding, COSINE)) >= ?
            ORDER BY similarity_score DESC
            FETCH FIRST ? ROWS ONLY
            """.formatted(collection, collection);
    }

    /**
     * Execute the vector search query with a natural language query.
     */
    private List<VectorSearchResult> executeSearch(String sql, String query) {
        List<VectorSearchResult> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, query);
            stmt.setString(2, query);
            stmt.setDouble(3, minSimilarity);
            stmt.setInt(4, 100); // limit

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String customerNumber = rs.getString("customer_number");
                    String description = rs.getString("description");
                    double similarityScore = rs.getDouble("similarity_score");

                    results.add(new VectorSearchResult(customerNumber, description, similarityScore));
                }
            }

        } catch (SQLException e) {
            log.error("Vector search failed: {}", e.getMessage(), e);
            throw new SearchException("Vector search failed", e);
        }

        log.debug("Vector search returned {} results", results.size());
        return results;
    }

    /**
     * Execute search to find similar customers.
     */
    private List<VectorSearchResult> executeSearchByCustomer(String sql, String customerNumber) {
        List<VectorSearchResult> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, customerNumber);
            stmt.setString(2, customerNumber);
            stmt.setDouble(3, minSimilarity);
            stmt.setInt(4, 100); // limit

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String custNum = rs.getString("customer_number");
                    String description = rs.getString("description");
                    double similarityScore = rs.getDouble("similarity_score");

                    results.add(new VectorSearchResult(custNum, description, similarityScore));
                }
            }

        } catch (SQLException e) {
            log.error("Similar customer search failed: {}", e.getMessage(), e);
            throw new SearchException("Similar customer search failed", e);
        }

        log.debug("Similar customer search returned {} results", results.size());
        return results;
    }

    /**
     * Validate search parameters.
     */
    private void validateSearchParams(String query, String collection) {
        if (query == null) {
            throw new IllegalArgumentException("Query cannot be null");
        }
        if (query.isEmpty()) {
            throw new IllegalArgumentException("Query cannot be empty");
        }
        if (collection == null) {
            throw new IllegalArgumentException("Collection cannot be null");
        }
    }

    /**
     * Get the minimum similarity threshold.
     */
    public double getMinSimilarity() {
        return minSimilarity;
    }

    /**
     * Set the minimum similarity threshold for results.
     * Valid range is 0.0 to 1.0.
     *
     * @param minSimilarity Minimum similarity score (0.0-1.0)
     */
    public void setMinSimilarity(double minSimilarity) {
        if (minSimilarity < 0.0 || minSimilarity > 1.0) {
            throw new IllegalArgumentException("Min similarity must be between 0.0 and 1.0");
        }
        this.minSimilarity = minSimilarity;
    }

    /**
     * Get the embedding model name.
     */
    public String getEmbeddingModel() {
        return embeddingModel;
    }

    /**
     * Set the embedding model to use for generating query embeddings.
     *
     * @param embeddingModel Name of the ONNX model loaded in Oracle
     */
    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }
}
