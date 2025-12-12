package com.wf.benchmark.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TDD tests for the Unified DBMS_SEARCH algorithm.
 *
 * The algorithm:
 * 1. Query unified index with fuzzy OR search for all terms
 * 2. Group hits by customerNumber
 * 3. Filter: only keep customers with matches in ALL required categories
 * 4. Calculate average score per customer
 * 5. Fetch identity/address details for qualifying customers
 * 6. Return UcSearchResult with computed score
 */
class UnifiedSearchAlgorithmTest {

    // ==================== SearchHit Tests ====================

    @Nested
    class SearchHitTests {

        @Test
        void shouldCreateSearchHitWithAllFields() {
            // Given/When
            SearchHit hit = new SearchHit("phone", "1000000001", "phoneNumber", "5551234567", 95.5);

            // Then
            assertThat(hit.sourceTable()).isEqualTo("phone");
            assertThat(hit.customerNumber()).isEqualTo("1000000001");
            assertThat(hit.matchedField()).isEqualTo("phoneNumber");
            assertThat(hit.matchedValue()).isEqualTo("5551234567");
            assertThat(hit.score()).isEqualTo(95.5);
        }

        @Test
        void shouldSupportIdentityHits() {
            SearchHit hit = new SearchHit("identity", "1000000001", "ssnLast4", "6789", 88.0);
            assertThat(hit.sourceTable()).isEqualTo("identity");
            assertThat(hit.matchedField()).isEqualTo("ssnLast4");
        }

        @Test
        void shouldSupportAccountHits() {
            SearchHit hit = new SearchHit("account", "1000000001", "accountNumber", "1234567890", 100.0);
            assertThat(hit.sourceTable()).isEqualTo("account");
        }

        @Test
        void shouldSupportAddressHits() {
            SearchHit hit = new SearchHit("address", "1000000001", "cityName", "New York", 75.0);
            assertThat(hit.sourceTable()).isEqualTo("address");
        }
    }

    // ==================== SearchCategory Tests ====================

    @Nested
    class SearchCategoryTests {

        @Test
        void shouldDefineAllCategories() {
            assertThat(SearchCategory.values()).hasSize(8);
            assertThat(SearchCategory.values()).contains(
                SearchCategory.PHONE, SearchCategory.SSN_LAST4,
                SearchCategory.ACCOUNT_NUMBER, SearchCategory.ACCOUNT_LAST4,
                SearchCategory.EMAIL, SearchCategory.CITY,
                SearchCategory.STATE, SearchCategory.ZIP
            );
        }

        @Test
        void shouldProvideUc1Categories() {
            assertThat(SearchCategory.UC1_CATEGORIES).containsExactlyInAnyOrder(
                SearchCategory.PHONE, SearchCategory.SSN_LAST4
            );
        }

        @Test
        void shouldProvideUc2Categories() {
            assertThat(SearchCategory.UC2_CATEGORIES).containsExactlyInAnyOrder(
                SearchCategory.PHONE, SearchCategory.SSN_LAST4, SearchCategory.ACCOUNT_LAST4
            );
        }

        @Test
        void shouldProvideUc5Categories() {
            assertThat(SearchCategory.UC5_CATEGORIES).containsExactlyInAnyOrder(
                SearchCategory.CITY, SearchCategory.STATE, SearchCategory.ZIP,
                SearchCategory.SSN_LAST4, SearchCategory.ACCOUNT_LAST4
            );
        }

        @Test
        void shouldProvideUc7Categories() {
            assertThat(SearchCategory.UC7_CATEGORIES).containsExactlyInAnyOrder(
                SearchCategory.EMAIL, SearchCategory.PHONE, SearchCategory.ACCOUNT_NUMBER
            );
        }

        @Test
        void shouldReturnCategoriesForUcCase() {
            assertThat(SearchCategory.forUcCase(1)).isEqualTo(SearchCategory.UC1_CATEGORIES);
            assertThat(SearchCategory.forUcCase(2)).isEqualTo(SearchCategory.UC2_CATEGORIES);
            assertThat(SearchCategory.forUcCase(3)).isEqualTo(SearchCategory.UC3_CATEGORIES);
            assertThat(SearchCategory.forUcCase(4)).isEqualTo(SearchCategory.UC4_CATEGORIES);
            assertThat(SearchCategory.forUcCase(5)).isEqualTo(SearchCategory.UC5_CATEGORIES);
            assertThat(SearchCategory.forUcCase(6)).isEqualTo(SearchCategory.UC6_CATEGORIES);
            assertThat(SearchCategory.forUcCase(7)).isEqualTo(SearchCategory.UC7_CATEGORIES);
        }

        @Test
        void shouldThrowForInvalidUcCase() {
            assertThatThrownBy(() -> SearchCategory.forUcCase(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid UC case number: 0");

            assertThatThrownBy(() -> SearchCategory.forUcCase(8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid UC case number: 8");
        }
    }

    // ==================== CustomerHitGroup Tests ====================

    @Nested
    class CustomerHitGroupTests {

        private CustomerHitGroup group;

        @BeforeEach
        void setUp() {
            group = new CustomerHitGroup("1000000001");
        }

        @Test
        void shouldTrackCustomerNumber() {
            assertThat(group.getCustomerNumber()).isEqualTo("1000000001");
        }

        @Test
        void shouldAddHitsWithCategories() {
            // Given
            SearchHit phoneHit = new SearchHit("phone", "1000000001", "phoneNumber", "5551234567", 90.0);
            SearchHit ssnHit = new SearchHit("identity", "1000000001", "ssnLast4", "6789", 85.0);

            // When
            group.addHit(phoneHit, SearchCategory.PHONE);
            group.addHit(ssnHit, SearchCategory.SSN_LAST4);

            // Then
            assertThat(group.getHits()).hasSize(2);
            assertThat(group.getMatchedCategories()).containsExactlyInAnyOrder(
                SearchCategory.PHONE, SearchCategory.SSN_LAST4
            );
        }

        @Test
        void shouldCalculateAverageScore() {
            // Given
            group.addHit(new SearchHit("phone", "1000000001", "phoneNumber", "5551234567", 100.0), SearchCategory.PHONE);
            group.addHit(new SearchHit("identity", "1000000001", "ssnLast4", "6789", 80.0), SearchCategory.SSN_LAST4);

            // When
            double avgScore = group.getAverageScore();

            // Then
            assertThat(avgScore).isEqualTo(90.0);
        }

        @Test
        void shouldReturnZeroForEmptyGroup() {
            assertThat(group.getAverageScore()).isEqualTo(0.0);
        }

        @Test
        void shouldCalculateTotalScore() {
            group.addHit(new SearchHit("phone", "1000000001", "phoneNumber", "5551234567", 100.0), SearchCategory.PHONE);
            group.addHit(new SearchHit("identity", "1000000001", "ssnLast4", "6789", 80.0), SearchCategory.SSN_LAST4);
            assertThat(group.getTotalScore()).isEqualTo(180.0);
        }

        @Test
        void shouldCalculateMaxScore() {
            group.addHit(new SearchHit("phone", "1000000001", "phoneNumber", "5551234567", 100.0), SearchCategory.PHONE);
            group.addHit(new SearchHit("identity", "1000000001", "ssnLast4", "6789", 80.0), SearchCategory.SSN_LAST4);
            assertThat(group.getMaxScore()).isEqualTo(100.0);
        }

        @Test
        void shouldCalculateMinScore() {
            group.addHit(new SearchHit("phone", "1000000001", "phoneNumber", "5551234567", 100.0), SearchCategory.PHONE);
            group.addHit(new SearchHit("identity", "1000000001", "ssnLast4", "6789", 80.0), SearchCategory.SSN_LAST4);
            assertThat(group.getMinScore()).isEqualTo(80.0);
        }

        @Test
        void shouldCheckAllRequiredCategoriesPresent() {
            // Given - UC-1 requires PHONE and SSN_LAST4
            group.addHit(new SearchHit("phone", "1000000001", "phoneNumber", "5551234567", 90.0), SearchCategory.PHONE);
            group.addHit(new SearchHit("identity", "1000000001", "ssnLast4", "6789", 85.0), SearchCategory.SSN_LAST4);

            // When/Then
            assertThat(group.hasAllCategories(SearchCategory.UC1_CATEGORIES)).isTrue();
        }

        @Test
        void shouldReturnFalseWhenMissingCategory() {
            // Given - UC-1 requires PHONE and SSN_LAST4, but only PHONE is present
            group.addHit(new SearchHit("phone", "1000000001", "phoneNumber", "5551234567", 90.0), SearchCategory.PHONE);

            // When/Then
            assertThat(group.hasAllCategories(SearchCategory.UC1_CATEGORIES)).isFalse();
        }

        @Test
        void shouldAllowMultipleHitsInSameCategory() {
            // A customer might have multiple phone numbers
            group.addHit(new SearchHit("phone", "1000000001", "phoneNumber", "5551234567", 90.0), SearchCategory.PHONE);
            group.addHit(new SearchHit("phone", "1000000001", "phoneNumber", "5559876543", 85.0), SearchCategory.PHONE);

            assertThat(group.getHits()).hasSize(2);
            assertThat(group.getMatchedCategories()).containsExactly(SearchCategory.PHONE);
            assertThat(group.getHitCount()).isEqualTo(2);
            assertThat(group.getCategoryCount()).isEqualTo(1);
        }
    }

    // ==================== Grouping Logic Tests ====================

    @Nested
    class GroupingLogicTests {

        private Map<String, SearchCategory> fieldMap;

        @BeforeEach
        void setUp() {
            fieldMap = Map.of(
                "phoneNumber", SearchCategory.PHONE,
                "ssnLast4", SearchCategory.SSN_LAST4,
                "accountNumber", SearchCategory.ACCOUNT_NUMBER,
                "accountLast4", SearchCategory.ACCOUNT_LAST4,
                "email", SearchCategory.EMAIL
            );
        }

        @Test
        void shouldGroupHitsByCustomerNumber() {
            // Given
            List<SearchHit> hits = List.of(
                new SearchHit("phone", "CUST001", "phoneNumber", "5551234567", 90.0),
                new SearchHit("identity", "CUST001", "ssnLast4", "6789", 85.0),
                new SearchHit("phone", "CUST002", "phoneNumber", "5559876543", 80.0),
                new SearchHit("identity", "CUST002", "ssnLast4", "6789", 75.0)
            );

            // When
            Map<String, CustomerHitGroup> groups = CustomerHitGroup.groupByCustomer(hits, fieldMap);

            // Then
            assertThat(groups).hasSize(2);
            assertThat(groups.get("CUST001").getHits()).hasSize(2);
            assertThat(groups.get("CUST002").getHits()).hasSize(2);
        }

        @Test
        void shouldFilterByRequiredCategories() {
            // Given - simulating UC-1: Phone + SSN Last 4
            List<SearchHit> hits = List.of(
                // CUST001 has both PHONE and SSN_LAST4 - should be included
                new SearchHit("phone", "CUST001", "phoneNumber", "5551234567", 90.0),
                new SearchHit("identity", "CUST001", "ssnLast4", "6789", 85.0),
                // CUST002 only has PHONE - should be excluded
                new SearchHit("phone", "CUST002", "phoneNumber", "5559876543", 80.0),
                // CUST003 only has SSN_LAST4 - should be excluded
                new SearchHit("identity", "CUST003", "ssnLast4", "6789", 75.0)
            );

            // When
            Map<String, CustomerHitGroup> groups = CustomerHitGroup.groupByCustomer(hits, fieldMap);
            List<CustomerHitGroup> filtered = CustomerHitGroup.filterAndSort(groups, SearchCategory.UC1_CATEGORIES, 100);

            // Then - only CUST001 should pass
            assertThat(filtered).hasSize(1);
            assertThat(filtered.get(0).getCustomerNumber()).isEqualTo("CUST001");
        }

        @Test
        void shouldSortByAverageScoreDescending() {
            // Given
            List<SearchHit> hits = List.of(
                new SearchHit("phone", "CUST001", "phoneNumber", "5551234567", 70.0),
                new SearchHit("identity", "CUST001", "ssnLast4", "6789", 70.0),  // avg = 70
                new SearchHit("phone", "CUST002", "phoneNumber", "5559876543", 90.0),
                new SearchHit("identity", "CUST002", "ssnLast4", "6789", 90.0),  // avg = 90
                new SearchHit("phone", "CUST003", "phoneNumber", "5555551234", 80.0),
                new SearchHit("identity", "CUST003", "ssnLast4", "1234", 80.0)   // avg = 80
            );

            // When
            Map<String, CustomerHitGroup> groups = CustomerHitGroup.groupByCustomer(hits, fieldMap);
            List<CustomerHitGroup> sorted = CustomerHitGroup.filterAndSort(groups, SearchCategory.UC1_CATEGORIES, 100);

            // Then - should be ordered: CUST002 (90), CUST003 (80), CUST001 (70)
            assertThat(sorted).hasSize(3);
            assertThat(sorted.get(0).getCustomerNumber()).isEqualTo("CUST002");
            assertThat(sorted.get(1).getCustomerNumber()).isEqualTo("CUST003");
            assertThat(sorted.get(2).getCustomerNumber()).isEqualTo("CUST001");
        }

        @Test
        void shouldRespectLimit() {
            // Given - 5 customers with matching categories
            List<SearchHit> hits = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                String custNum = "CUST00" + i;
                hits.add(new SearchHit("phone", custNum, "phoneNumber", "555000000" + i, 100.0 - i));
                hits.add(new SearchHit("identity", custNum, "ssnLast4", "000" + i, 100.0 - i));
            }

            // When
            Map<String, CustomerHitGroup> groups = CustomerHitGroup.groupByCustomer(hits, fieldMap);
            List<CustomerHitGroup> limited = CustomerHitGroup.filterAndSort(groups, SearchCategory.UC1_CATEGORIES, 3);

            // Then
            assertThat(limited).hasSize(3);
        }

        @Test
        void shouldHandleUc2With3Categories() {
            // Given - UC-2: Phone + SSN Last 4 + Account Last 4
            List<SearchHit> hits = List.of(
                // CUST001 has all 3 categories
                new SearchHit("phone", "CUST001", "phoneNumber", "5551234567", 90.0),
                new SearchHit("identity", "CUST001", "ssnLast4", "6789", 85.0),
                new SearchHit("account", "CUST001", "accountLast4", "1234", 80.0),
                // CUST002 missing account - should be excluded
                new SearchHit("phone", "CUST002", "phoneNumber", "5559876543", 90.0),
                new SearchHit("identity", "CUST002", "ssnLast4", "5678", 85.0)
            );

            // When
            Map<String, CustomerHitGroup> groups = CustomerHitGroup.groupByCustomer(hits, fieldMap);
            List<CustomerHitGroup> filtered = CustomerHitGroup.filterAndSort(groups, SearchCategory.UC2_CATEGORIES, 100);

            // Then
            assertThat(filtered).hasSize(1);
            assertThat(filtered.get(0).getCustomerNumber()).isEqualTo("CUST001");
        }
    }

    // ==================== UC-Specific Category Tests ====================

    @Nested
    class UcCategoryRequirementTests {

        @Test
        void uc1RequiresPhoneAndSsnLast4() {
            assertThat(SearchCategory.UC1_CATEGORIES).hasSize(2);
            assertThat(SearchCategory.UC1_CATEGORIES).contains(SearchCategory.PHONE, SearchCategory.SSN_LAST4);
        }

        @Test
        void uc2RequiresPhoneSsnLast4AndAccountLast4() {
            assertThat(SearchCategory.UC2_CATEGORIES).hasSize(3);
            assertThat(SearchCategory.UC2_CATEGORIES).contains(
                SearchCategory.PHONE, SearchCategory.SSN_LAST4, SearchCategory.ACCOUNT_LAST4
            );
        }

        @Test
        void uc3RequiresPhoneAndAccountLast4() {
            assertThat(SearchCategory.UC3_CATEGORIES).hasSize(2);
            assertThat(SearchCategory.UC3_CATEGORIES).contains(SearchCategory.PHONE, SearchCategory.ACCOUNT_LAST4);
        }

        @Test
        void uc4RequiresAccountNumberAndSsnLast4() {
            assertThat(SearchCategory.UC4_CATEGORIES).hasSize(2);
            assertThat(SearchCategory.UC4_CATEGORIES).contains(SearchCategory.ACCOUNT_NUMBER, SearchCategory.SSN_LAST4);
        }

        @Test
        void uc5RequiresCityStateZipSsnLast4AndAccountLast4() {
            assertThat(SearchCategory.UC5_CATEGORIES).hasSize(5);
            assertThat(SearchCategory.UC5_CATEGORIES).contains(
                SearchCategory.CITY, SearchCategory.STATE, SearchCategory.ZIP,
                SearchCategory.SSN_LAST4, SearchCategory.ACCOUNT_LAST4
            );
        }

        @Test
        void uc6RequiresEmailAndAccountLast4() {
            assertThat(SearchCategory.UC6_CATEGORIES).hasSize(2);
            assertThat(SearchCategory.UC6_CATEGORIES).contains(SearchCategory.EMAIL, SearchCategory.ACCOUNT_LAST4);
        }

        @Test
        void uc7RequiresEmailPhoneAndAccountNumber() {
            assertThat(SearchCategory.UC7_CATEGORIES).hasSize(3);
            assertThat(SearchCategory.UC7_CATEGORIES).contains(
                SearchCategory.EMAIL, SearchCategory.PHONE, SearchCategory.ACCOUNT_NUMBER
            );
        }
    }

    // ==================== DBMS_SEARCH Query Building Tests ====================

    @Nested
    class DbmsSearchQueryTests {

        /**
         * Builds a DBMS_SEARCH.FIND query with fuzzy OR search.
         * The query searches across all collections in the unified index.
         */
        private String buildFuzzyOrQuery(String indexName, List<String> searchTerms, int limit) {
            // Build fuzzy search with OR between terms
            String fuzzyTerms = searchTerms.stream()
                .map(t -> "fuzzy(" + escapeForOracle(t) + ")")
                .reduce((a, b) -> a + " OR " + b)
                .orElse("");

            return String.format(
                "SELECT DBMS_SEARCH.FIND('%s', JSON('{\"$query\":\"%s\",\"$search\":{\"limit\":%d}}')) AS RESULT FROM DUAL",
                indexName, fuzzyTerms, limit
            );
        }

        private String escapeForOracle(String term) {
            return term.replace("'", "''").replace("\"", "\\\"");
        }

        @Test
        void shouldBuildFuzzyOrQueryForUC1() {
            // UC-1: Phone + SSN Last 4
            String query = buildFuzzyOrQuery("idx_uc_unified", List.of("5551234567", "6789"), 100);

            assertThat(query).contains("DBMS_SEARCH.FIND");
            assertThat(query).contains("idx_uc_unified");
            assertThat(query).contains("fuzzy(5551234567)");
            assertThat(query).contains("fuzzy(6789)");
            assertThat(query).contains("OR");
        }

        @Test
        void shouldBuildFuzzyOrQueryForUC5() {
            // UC-5: City/State/ZIP + SSN Last 4 + Account Last 4
            String query = buildFuzzyOrQuery("idx_uc_unified",
                List.of("New York", "NY", "10001", "6789", "1234"), 100);

            assertThat(query).contains("fuzzy(New York)");
            assertThat(query).contains("fuzzy(NY)");
            assertThat(query).contains("fuzzy(10001)");
            assertThat(query).contains("fuzzy(6789)");
            assertThat(query).contains("fuzzy(1234)");
        }

        @Test
        void shouldEscapeSpecialCharactersInQuery() {
            String query = buildFuzzyOrQuery("idx_test", List.of("O'Brien", "test@email.com"), 10);

            assertThat(query).contains("O''Brien"); // Single quote escaped
        }
    }

    // ==================== Score Calculation Tests ====================

    @Nested
    class ScoreCalculationTests {

        @Test
        void shouldConvertAverageScoreToRankingScore() {
            // Given - Oracle Text scores typically 0-100
            double avgScore = 87.5;

            // When - convert to integer ranking score
            int rankingScore = (int) Math.round(avgScore);

            // Then
            assertThat(rankingScore).isEqualTo(88);
        }

        @Test
        void shouldWeightCategoriesEqually() {
            // By default, all matching categories contribute equally to score
            CustomerHitGroup group = new CustomerHitGroup("CUST001");
            group.addHit(new SearchHit("phone", "CUST001", "phoneNumber", "5551234567", 100.0), SearchCategory.PHONE);
            group.addHit(new SearchHit("identity", "CUST001", "ssnLast4", "6789", 50.0), SearchCategory.SSN_LAST4);

            // Average of 100 and 50 = 75
            assertThat(group.getAverageScore()).isEqualTo(75.0);
        }
    }

    // ==================== Integration Simulation Tests ====================

    @Nested
    class IntegrationSimulationTests {

        /**
         * Simulates the full algorithm flow for UC-1.
         */
        @Test
        void shouldSimulateUc1FullFlow() {
            // Given - simulate DBMS_SEARCH.FIND results
            List<SearchHit> simulatedHits = List.of(
                // Customer 1 has matching phone and SSN
                new SearchHit("phone", "1000000001", "phoneNumber", "5551234567", 95.0),
                new SearchHit("identity", "1000000001", "ssnLast4", "6789", 92.0),
                // Customer 2 has matching phone only
                new SearchHit("phone", "1000000002", "phoneNumber", "5551234567", 90.0),
                // Customer 3 has matching SSN only
                new SearchHit("identity", "1000000003", "ssnLast4", "6789", 88.0)
            );

            Map<String, SearchCategory> fieldMap = Map.of(
                "phoneNumber", SearchCategory.PHONE,
                "ssnLast4", SearchCategory.SSN_LAST4
            );

            // When - group by customer
            Map<String, CustomerHitGroup> groups = CustomerHitGroup.groupByCustomer(simulatedHits, fieldMap);

            // Filter by UC-1 requirements (PHONE + SSN_LAST4)
            List<CustomerHitGroup> results = CustomerHitGroup.filterAndSort(
                groups, SearchCategory.UC1_CATEGORIES, 10
            );

            // Then - only Customer 1 should be returned
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getCustomerNumber()).isEqualTo("1000000001");
            assertThat(results.get(0).getAverageScore()).isEqualTo(93.5); // (95 + 92) / 2
        }

        /**
         * Simulates the full algorithm flow for UC-7 (3 categories).
         */
        @Test
        void shouldSimulateUc7FullFlow() {
            // Given - UC-7: Email + Phone + Account Number
            List<SearchHit> simulatedHits = List.of(
                // Customer 1 has all 3 categories
                new SearchHit("identity", "1000000001", "email", "john@test.com", 100.0),
                new SearchHit("phone", "1000000001", "phoneNumber", "5551234567", 95.0),
                new SearchHit("account", "1000000001", "accountNumber", "1234567890", 90.0),
                // Customer 2 missing accountNumber
                new SearchHit("identity", "1000000002", "email", "jane@test.com", 98.0),
                new SearchHit("phone", "1000000002", "phoneNumber", "5559876543", 92.0)
            );

            Map<String, SearchCategory> fieldMap = Map.of(
                "email", SearchCategory.EMAIL,
                "phoneNumber", SearchCategory.PHONE,
                "accountNumber", SearchCategory.ACCOUNT_NUMBER
            );

            // When
            Map<String, CustomerHitGroup> groups = CustomerHitGroup.groupByCustomer(simulatedHits, fieldMap);
            List<CustomerHitGroup> results = CustomerHitGroup.filterAndSort(
                groups, SearchCategory.UC7_CATEGORIES, 10
            );

            // Then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getCustomerNumber()).isEqualTo("1000000001");
            assertThat(results.get(0).getAverageScore()).isCloseTo(95.0, org.assertj.core.data.Offset.offset(0.1));
        }
    }
}
