package com.wf.benchmark.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TDD tests for VectorSearchService.
 * Uses Oracle AI Vector Search (23ai) for semantic similarity searches.
 *
 * Vector search enables:
 * - Natural language queries ("find customers in banking sector")
 * - Semantic similarity ("similar to John Smith who works at Acme Corp")
 * - Concept matching (similar meaning, different words)
 *
 * Requires embeddings to be pre-computed and stored in the database.
 */
class VectorSearchServiceTest {

    private FuzzySearchServiceTest.StubDataSource stubDataSource;
    private VectorSearchService vectorSearchService;

    @BeforeEach
    void setUp() {
        stubDataSource = new FuzzySearchServiceTest.StubDataSource();
        vectorSearchService = new VectorSearchService(stubDataSource);
    }

    @Nested
    class SemanticSearchTests {

        @Test
        void shouldFindSemanticallySimilarResults() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "John Smith - Banking", "0.95"},
                {"1000000002", "Jane Doe - Finance", "0.82"}
            });

            // When - Search by natural language description
            List<VectorSearchResult> results = vectorSearchService.searchByDescription(
                "customers in banking and finance sector", "identity", 10);

            // Then
            assertThat(results).hasSize(2);
            assertThat(results.get(0).getCustomerNumber()).isEqualTo("1000000001");
            assertThat(results.get(0).getSimilarityScore()).isGreaterThan(0.9);
        }

        @Test
        void shouldRankResultsBySimilarity() throws SQLException {
            // Given - Results should be ordered by similarity
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "Most similar", "0.95"},
                {"1000000002", "Second most similar", "0.85"},
                {"1000000003", "Third most similar", "0.75"}
            });

            // When
            List<VectorSearchResult> results = vectorSearchService.searchByDescription(
                "find relevant customers", "identity", 10);

            // Then - Should be ranked by similarity score
            assertThat(results).hasSize(3);
            assertThat(results.get(0).getSimilarityScore()).isGreaterThan(results.get(1).getSimilarityScore());
            assertThat(results.get(1).getSimilarityScore()).isGreaterThan(results.get(2).getSimilarityScore());
        }

        @Test
        void shouldReturnEmptyListForNoMatches() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {}); // No results

            // When
            List<VectorSearchResult> results = vectorSearchService.searchByDescription(
                "completely unrelated query", "identity", 10);

            // Then
            assertThat(results).isEmpty();
        }
    }

    @Nested
    class SimilarToCustomerTests {

        @Test
        void shouldFindSimilarCustomers() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {
                {"1000000002", "Similar Customer A", "0.88"},
                {"1000000003", "Similar Customer B", "0.75"}
            });

            // When - Find customers similar to a reference customer
            List<VectorSearchResult> results = vectorSearchService.findSimilarToCustomer(
                "1000000001", "identity", 10);

            // Then
            assertThat(results).hasSize(2);
            assertThat(results).noneMatch(r -> r.getCustomerNumber().equals("1000000001")); // Should not include self
        }

        @Test
        void shouldExcludeSelfFromSimilarResults() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {
                {"1000000002", "Similar Customer", "0.88"}
            });

            // When
            List<VectorSearchResult> results = vectorSearchService.findSimilarToCustomer(
                "1000000001", "identity", 10);

            // Then - Should not include the reference customer
            assertThat(results).noneMatch(r -> r.getCustomerNumber().equals("1000000001"));
        }
    }

    @Nested
    class ConfigurationTests {

        @Test
        void shouldSetMinimumSimilarityThreshold() {
            // When
            vectorSearchService.setMinSimilarity(0.8);

            // Then
            assertThat(vectorSearchService.getMinSimilarity()).isEqualTo(0.8);
        }

        @Test
        void shouldSetDefaultMinimumSimilarity() {
            // When
            VectorSearchService service = new VectorSearchService(stubDataSource);

            // Then - Default should be 0.7
            assertThat(service.getMinSimilarity()).isEqualTo(0.7);
        }

        @Test
        void shouldRejectInvalidSimilarityThreshold() {
            // When/Then
            assertThatThrownBy(() -> vectorSearchService.setMinSimilarity(-0.1))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> vectorSearchService.setMinSimilarity(1.1))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldSetEmbeddingModel() {
            // When
            vectorSearchService.setEmbeddingModel("all-MiniLM-L6-v2");

            // Then
            assertThat(vectorSearchService.getEmbeddingModel()).isEqualTo("all-MiniLM-L6-v2");
        }
    }

    @Nested
    class ValidationTests {

        @Test
        void shouldHandleNullQuery() {
            // When/Then
            assertThatThrownBy(() -> vectorSearchService.searchByDescription(null, "identity", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Query cannot be null");
        }

        @Test
        void shouldHandleEmptyQuery() {
            // When/Then
            assertThatThrownBy(() -> vectorSearchService.searchByDescription("", "identity", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Query cannot be empty");
        }

        @Test
        void shouldHandleNullCollection() {
            // When/Then
            assertThatThrownBy(() -> vectorSearchService.searchByDescription("test query", null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Collection cannot be null");
        }

        @Test
        void shouldHandleNullCustomerNumber() {
            // When/Then
            assertThatThrownBy(() -> vectorSearchService.findSimilarToCustomer(null, "identity", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Customer number cannot be null");
        }
    }

    @Nested
    class SQLGenerationTests {

        @Test
        void shouldGenerateVectorDistanceQuery() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {}); // No results

            // When
            vectorSearchService.searchByDescription("test query", "identity", 10);

            // Then - verify the SQL contains vector search syntax
            assertThat(stubDataSource.getLastPreparedSql()).contains("VECTOR_DISTANCE");
        }
    }

    @Nested
    class ErrorHandlingTests {

        @Test
        void shouldWrapSQLExceptionInSearchException() {
            // Given
            stubDataSource.setThrowExceptionOnGetConnection(true);

            // When/Then
            assertThatThrownBy(() -> vectorSearchService.searchByDescription("test", "identity", 10))
                .isInstanceOf(SearchException.class)
                .hasCauseInstanceOf(SQLException.class);
        }
    }
}
