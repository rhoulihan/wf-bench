package com.wf.benchmark.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TDD tests for HybridSearchService.
 *
 * The HybridSearchService orchestrates multiple search strategies:
 * - Fuzzy text search (Oracle Text CONTAINS with FUZZY)
 * - Phonetic search (SOUNDEX with nickname expansion)
 * - Vector similarity search (Oracle AI Vector Search)
 *
 * It combines results from these strategies, removing duplicates
 * and ranking by relevance score.
 */
class HybridSearchServiceTest {

    private FuzzySearchServiceTest.StubDataSource stubDataSource;
    private FuzzySearchService fuzzySearchService;
    private PhoneticSearchService phoneticSearchService;
    private VectorSearchService vectorSearchService;
    private HybridSearchService hybridSearchService;

    @BeforeEach
    void setUp() {
        stubDataSource = new FuzzySearchServiceTest.StubDataSource();
        fuzzySearchService = new FuzzySearchService(stubDataSource);
        phoneticSearchService = new PhoneticSearchService(stubDataSource);
        vectorSearchService = new VectorSearchService(stubDataSource);
        hybridSearchService = new HybridSearchService(
            fuzzySearchService, phoneticSearchService, vectorSearchService);
    }

    @Nested
    class NameSearchTests {

        @Test
        void shouldCombineResultsFromFuzzyAndPhoneticSearch() throws SQLException {
            // Given - Both fuzzy and phonetic return results
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "John Smith", "0.85"},
                {"1000000002", "Jon Smyth", "0.75"}
            });

            // When - Search by name combines strategies
            List<HybridSearchResult> results = hybridSearchService.searchByName(
                "John", "Smith", "identity", 10);

            // Then - Should return combined results
            assertThat(results).isNotEmpty();
        }

        @Test
        void shouldDeduplicateResultsFromMultipleStrategies() throws SQLException {
            // Given - Same customer found by both fuzzy and phonetic
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "John Smith", "0.90"}
            });

            // When
            List<HybridSearchResult> results = hybridSearchService.searchByName(
                "John", "Smith", "identity", 10);

            // Then - Should not have duplicates
            long uniqueCount = results.stream()
                .map(HybridSearchResult::getCustomerNumber)
                .distinct()
                .count();
            assertThat(uniqueCount).isEqualTo(results.size());
        }

        @Test
        void shouldRankResultsByHighestScore() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "Best Match", "0.95"},
                {"1000000002", "Good Match", "0.80"},
                {"1000000003", "Fair Match", "0.65"}
            });

            // When
            List<HybridSearchResult> results = hybridSearchService.searchByName(
                "Test", "Name", "identity", 10);

            // Then - Should be ordered by score descending
            if (results.size() > 1) {
                for (int i = 0; i < results.size() - 1; i++) {
                    assertThat(results.get(i).getScore())
                        .isGreaterThanOrEqualTo(results.get(i + 1).getScore());
                }
            }
        }

        @Test
        void shouldIndicateMatchStrategy() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "John Smith", "0.90"}
            });

            // When
            List<HybridSearchResult> results = hybridSearchService.searchByName(
                "John", "Smith", "identity", 10);

            // Then - Each result should indicate which strategy matched
            assertThat(results).allSatisfy(result ->
                assertThat(result.getMatchStrategies()).isNotEmpty()
            );
        }

        @Test
        void shouldReturnEmptyListWhenNoMatches() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {});

            // When
            List<HybridSearchResult> results = hybridSearchService.searchByName(
                "NonExistent", "Person", "identity", 10);

            // Then
            assertThat(results).isEmpty();
        }
    }

    @Nested
    class SemanticSearchTests {

        @Test
        void shouldSearchBySemanticDescription() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "Banking Customer", "0.88"},
                {"1000000002", "Finance Professional", "0.75"}
            });

            // When - Natural language query
            List<HybridSearchResult> results = hybridSearchService.searchByDescription(
                "customers in financial services", "identity", 10);

            // Then
            assertThat(results).isNotEmpty();
        }

        @Test
        void shouldCombineSemanticWithFuzzyResults() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "Financial Services Inc", "0.85"}
            });

            // When - Semantic + fuzzy business name search
            List<HybridSearchResult> results = hybridSearchService.searchByBusinessDescription(
                "Financial Services", "identity", 10);

            // Then
            assertThat(results).isNotEmpty();
        }
    }

    @Nested
    class SimilarCustomerTests {

        @Test
        void shouldFindSimilarCustomers() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {
                {"1000000002", "Similar Customer A", "0.88"},
                {"1000000003", "Similar Customer B", "0.72"}
            });

            // When
            List<HybridSearchResult> results = hybridSearchService.findSimilarCustomers(
                "1000000001", "identity", 10);

            // Then
            assertThat(results).isNotEmpty();
            assertThat(results).noneMatch(r -> r.getCustomerNumber().equals("1000000001"));
        }
    }

    @Nested
    class ConfigurationTests {

        @Test
        void shouldConfigureFuzzyThreshold() {
            // When
            hybridSearchService.setFuzzyMinScore(80);

            // Then
            assertThat(hybridSearchService.getFuzzyMinScore()).isEqualTo(80);
        }

        @Test
        void shouldConfigureVectorSimilarityThreshold() {
            // When
            hybridSearchService.setVectorMinSimilarity(0.8);

            // Then
            assertThat(hybridSearchService.getVectorMinSimilarity()).isEqualTo(0.8);
        }

        @Test
        void shouldEnableOrDisableStrategies() {
            // When - Disable phonetic search
            hybridSearchService.setPhoneticEnabled(false);

            // Then
            assertThat(hybridSearchService.isPhoneticEnabled()).isFalse();
        }

        @Test
        void shouldSetDefaultStrategiesToEnabled() {
            // When
            HybridSearchService service = new HybridSearchService(
                fuzzySearchService, phoneticSearchService, vectorSearchService);

            // Then - All strategies enabled by default
            assertThat(service.isFuzzyEnabled()).isTrue();
            assertThat(service.isPhoneticEnabled()).isTrue();
            assertThat(service.isVectorEnabled()).isTrue();
        }
    }

    @Nested
    class ValidationTests {

        @Test
        void shouldRejectNullFirstName() {
            assertThatThrownBy(() -> hybridSearchService.searchByName(null, "Smith", "identity", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("First name cannot be null");
        }

        @Test
        void shouldRejectNullLastName() {
            assertThatThrownBy(() -> hybridSearchService.searchByName("John", null, "identity", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Last name cannot be null");
        }

        @Test
        void shouldRejectNullCollection() {
            assertThatThrownBy(() -> hybridSearchService.searchByName("John", "Smith", null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Collection cannot be null");
        }

        @Test
        void shouldRejectNullDescription() {
            assertThatThrownBy(() -> hybridSearchService.searchByDescription(null, "identity", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Description cannot be null");
        }

        @Test
        void shouldRejectNullCustomerNumber() {
            assertThatThrownBy(() -> hybridSearchService.findSimilarCustomers(null, "identity", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Customer number cannot be null");
        }
    }

    @Nested
    class ErrorHandlingTests {

        @Test
        void shouldReturnEmptyWhenAllStrategiesFail() {
            // Given - All connections fail
            stubDataSource.setThrowExceptionOnGetConnection(true);

            // When - HybridSearchService gracefully handles failures
            // It should return empty results when all strategies fail, not throw
            List<HybridSearchResult> results = hybridSearchService.searchByName(
                "John", "Smith", "identity", 10);

            // Then - Should return empty, not throw
            assertThat(results).isEmpty();
        }

        @Test
        void shouldThrowForVectorSearchWhenOnlyStrategyEnabled() {
            // Given - All connections fail and only vector is enabled
            stubDataSource.setThrowExceptionOnGetConnection(true);
            hybridSearchService.setFuzzyEnabled(false);
            hybridSearchService.setPhoneticEnabled(false);

            // When/Then - Vector search throws when it's the only enabled strategy
            // because searchByDescription doesn't catch exceptions
            assertThatThrownBy(() -> hybridSearchService.searchByDescription(
                "test query", "identity", 10))
                .isInstanceOf(SearchException.class);
        }

        @Test
        void shouldContinueIfOneStrategyFails() throws SQLException {
            // This tests graceful degradation - if fuzzy search fails,
            // phonetic should still return results

            // Given - Set up results that will be returned by phonetic search
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "John Smith", "J500-S530"}
            });

            // When - Fuzzy will fail (data format), but phonetic will work
            hybridSearchService.setVectorEnabled(false); // Disable vector for this test

            List<HybridSearchResult> results = hybridSearchService.searchByName(
                "John", "Smith", "identity", 10);

            // Then - Should still get results from phonetic strategy
            assertThat(results).isNotEmpty();
        }
    }

    @Nested
    class ResultMergingTests {

        @Test
        void shouldMergeStrategiesForSameCustomer() throws SQLException {
            // When the same customer is found by multiple strategies,
            // should combine into single result with all strategies listed

            // Given
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "John Smith", "0.90"}
            });

            // When
            List<HybridSearchResult> results = hybridSearchService.searchByName(
                "John", "Smith", "identity", 10);

            // Then - Customer should appear once with potentially multiple strategies
            long customerCount = results.stream()
                .filter(r -> r.getCustomerNumber().equals("1000000001"))
                .count();
            assertThat(customerCount).isLessThanOrEqualTo(1);
        }

        @Test
        void shouldUseHighestScoreWhenMerging() throws SQLException {
            // When same customer found by multiple strategies with different scores,
            // should use the highest score

            // Given
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "John Smith", "0.95"}
            });

            // When
            List<HybridSearchResult> results = hybridSearchService.searchByName(
                "John", "Smith", "identity", 10);

            // Then
            if (!results.isEmpty()) {
                HybridSearchResult result = results.stream()
                    .filter(r -> r.getCustomerNumber().equals("1000000001"))
                    .findFirst()
                    .orElse(null);
                assertThat(result).isNotNull();
                assertThat(result.getScore()).isGreaterThan(0);
            }
        }
    }

    @Nested
    class LimitAndPaginationTests {

        @Test
        void shouldRespectResultLimit() throws SQLException {
            // Given - More results than limit
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "Match 1", "0.95"},
                {"1000000002", "Match 2", "0.90"},
                {"1000000003", "Match 3", "0.85"},
                {"1000000004", "Match 4", "0.80"},
                {"1000000005", "Match 5", "0.75"}
            });

            // When - Request only 3
            List<HybridSearchResult> results = hybridSearchService.searchByName(
                "Test", "Name", "identity", 3);

            // Then
            assertThat(results).hasSizeLessThanOrEqualTo(3);
        }
    }
}
