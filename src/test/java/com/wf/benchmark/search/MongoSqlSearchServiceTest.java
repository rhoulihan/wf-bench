package com.wf.benchmark.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TDD tests for MongoSqlSearchService.
 * Tests UC 1-7 search queries using MongoDB $sql operator with json_textcontains().
 *
 * Based on Oracle team recommendations:
 * - Use json_textcontains() instead of CONTAINS()
 * - Use CTE pattern for multi-collection joins
 * - Use DOMAIN_INDEX_SORT hint for optimized score sorting
 * - Order by highest score DESC
 * - Use json {...} for output formatting
 */
class MongoSqlSearchServiceTest {

    private MongoSqlSearchService service;

    @BeforeEach
    void setUp() {
        // Create service with null client - we'll test query building only
        // Execution tests require integration testing with real MongoDB
        service = new MongoSqlSearchService(null, "admin", "");
    }

    @Nested
    class UC1QueryBuilderTests {

        @Test
        void shouldBuildUC1QueryWithJsonTextContains() {
            // When
            String sql = service.buildUC1Query("4155551234", "6789", 10);

            // Then - should use json_textcontains instead of CONTAINS
            assertThat(sql).contains("json_textcontains");
            assertThat(sql).doesNotContain("CONTAINS(");
        }

        @Test
        void shouldBuildUC1QueryWithPhoneNumberPath() {
            // When
            String sql = service.buildUC1Query("4155551234", "6789", 10);

            // Then - should search phone number at correct JSON path
            assertThat(sql).contains("phoneKey")
                .contains("phoneNumber");
        }

        @Test
        void shouldBuildUC1QueryWithCTEPattern() {
            // When
            String sql = service.buildUC1Query("4155551234", "6789", 10);

            // Then - should use WITH clause (CTE pattern)
            assertThat(sql).containsIgnoringCase("WITH");
            assertThat(sql).containsIgnoringCase("phones AS");
            assertThat(sql).containsIgnoringCase("identities AS");
        }

        @Test
        void shouldBuildUC1QueryWithDomainIndexSortHint() {
            // When
            String sql = service.buildUC1Query("4155551234", "6789", 10);

            // Then - should include optimizer hint for score sorting
            assertThat(sql).contains("DOMAIN_INDEX_SORT");
        }

        @Test
        void shouldBuildUC1QueryWithScoreOrdering() {
            // When
            String sql = service.buildUC1Query("4155551234", "6789", 10);

            // Then - should order by score DESC
            assertThat(sql).containsIgnoringCase("ORDER BY");
            assertThat(sql).containsIgnoringCase("score");
            assertThat(sql).containsIgnoringCase("DESC");
        }

        @Test
        void shouldBuildUC1QueryWithJsonOutput() {
            // When
            String sql = service.buildUC1Query("4155551234", "6789", 10);

            // Then - should use json {...} output format
            assertThat(sql).containsIgnoringCase("SELECT json");
        }

        @Test
        void shouldBuildUC1QueryWithRowLimit() {
            // When
            String sql = service.buildUC1Query("4155551234", "6789", 25);

            // Then - should include FETCH FIRST with limit
            assertThat(sql).containsIgnoringCase("FETCH FIRST 25 ROWS ONLY");
        }

        @Test
        void shouldBuildUC1QueryWithSsnLast4ExactMatch() {
            // When
            String sql = service.buildUC1Query("4155551234", "6789", 10);

            // Then - SSN last 4 should be exact match (not text search)
            assertThat(sql).contains("taxIdentificationNumberLast4");
            assertThat(sql).contains("= '6789'");
        }

        @Test
        void shouldBuildUC1QueryWithJoinOnCustomerNumber() {
            // When
            String sql = service.buildUC1Query("4155551234", "6789", 10);

            // Then - should join on customerNumber
            assertThat(sql).contains("customerNumber");
        }

        @Test
        void shouldIncludeAddressLeftJoin() {
            // When
            String sql = service.buildUC1Query("4155551234", "6789", 10);

            // Then - address should be LEFT JOIN (customers may not have addresses)
            assertThat(sql).containsIgnoringCase("LEFT JOIN addresses");
        }
    }

    @Nested
    class UC2QueryTests {

        @Test
        void shouldBuildUC2QueryWithThreeWayJoin() {
            // When
            String sql = service.buildUC2Query("4155551234", "6789", "1234", 10);

            // Then - should have phone, identity, and account CTEs
            assertThat(sql).containsIgnoringCase("phones AS");
            assertThat(sql).containsIgnoringCase("identities AS");
            assertThat(sql).containsIgnoringCase("accounts AS");
        }

        @Test
        void shouldBuildUC2QueryWithAccountLast4ExactMatch() {
            // When
            String sql = service.buildUC2Query("4155551234", "6789", "1234", 10);

            // Then - account last 4 should be exact match
            assertThat(sql).contains("accountNumberLast4");
            assertThat(sql).contains("= '1234'");
        }

        @Test
        void shouldBuildUC2QueryWithJsonTextContains() {
            // When
            String sql = service.buildUC2Query("4155551234", "6789", "1234", 10);

            // Then
            assertThat(sql).contains("json_textcontains");
        }
    }

    @Nested
    class UC3QueryTests {

        @Test
        void shouldBuildUC3QueryWithPhoneAndAccountLast4() {
            // When
            String sql = service.buildUC3Query("4155551234", "5678", 10);

            // Then
            assertThat(sql).contains("json_textcontains");
            assertThat(sql).contains("phoneNumber");
            assertThat(sql).contains("accountNumberLast4");
            assertThat(sql).contains("= '5678'");
        }

        @Test
        void shouldBuildUC3QueryWithoutSsnFilter() {
            // When
            String sql = service.buildUC3Query("4155551234", "5678", 10);

            // Then - UC3 doesn't filter by SSN (unlike UC2)
            // The identities CTE should select all identities, not filter by SSN
            assertThat(sql).containsIgnoringCase("identities AS");
            // Should NOT have SSN exact match in identities CTE
            String identitiesCte = extractCte(sql, "identities");
            assertThat(identitiesCte).doesNotContain("taxIdentificationNumberLast4");
        }
    }

    @Nested
    class UC4QueryTests {

        @Test
        void shouldBuildUC4QueryWithAccountNumberTextSearch() {
            // When
            String sql = service.buildUC4Query("9876543210", "6789", 10);

            // Then - account number should be text search
            assertThat(sql).contains("json_textcontains");
            assertThat(sql).contains("accountNumber");
        }

        @Test
        void shouldBuildUC4QueryStartingFromAccount() {
            // When
            String sql = service.buildUC4Query("9876543210", "6789", 10);

            // Then - should have accounts CTE with DOMAIN_INDEX_SORT (primary search)
            assertThat(sql).containsIgnoringCase("accounts AS");
            String accountsCte = extractCte(sql, "accounts");
            assertThat(accountsCte).contains("DOMAIN_INDEX_SORT");
        }

        @Test
        void shouldBuildUC4QueryWithSsnExactMatch() {
            // When
            String sql = service.buildUC4Query("9876543210", "6789", 10);

            // Then
            assertThat(sql).contains("taxIdentificationNumberLast4");
            assertThat(sql).contains("= '6789'");
        }
    }

    @Nested
    class UC5QueryTests {

        @Test
        void shouldBuildUC5QueryWithCityTextSearch() {
            // When
            String sql = service.buildUC5Query("San Francisco", "CA", "94102", "6789", "1234", 10);

            // Then - city should be text search
            assertThat(sql).contains("json_textcontains");
            assertThat(sql).contains("cityName");
        }

        @Test
        void shouldBuildUC5QueryWithStateAndZipExactMatch() {
            // When
            String sql = service.buildUC5Query("San Francisco", "CA", "94102", "6789", "1234", 10);

            // Then - state and zip should be exact match
            assertThat(sql).contains("stateCode");
            assertThat(sql).contains("= 'CA'");
            assertThat(sql).contains("postalCode");
            assertThat(sql).contains("= '94102'");
        }

        @Test
        void shouldBuildUC5QueryWithAllFiveParameters() {
            // When
            String sql = service.buildUC5Query("San Francisco", "CA", "94102", "6789", "1234", 10);

            // Then - should include all five search criteria
            assertThat(sql).contains("San Francisco"); // city
            assertThat(sql).contains("CA");            // state
            assertThat(sql).contains("94102");         // zip
            assertThat(sql).contains("6789");          // ssn last 4
            assertThat(sql).contains("1234");          // account last 4
        }
    }

    @Nested
    class UC6QueryTests {

        @Test
        void shouldBuildUC6QueryWithEmailTextSearch() {
            // When
            String sql = service.buildUC6Query("john@example.com", "1234", 10);

            // Then - email should be text search on identity
            assertThat(sql).contains("json_textcontains");
            assertThat(sql).contains("primaryEmail");
        }

        @Test
        void shouldBuildUC6QueryStartingFromIdentity() {
            // When
            String sql = service.buildUC6Query("john@example.com", "1234", 10);

            // Then - should have identities CTE with DOMAIN_INDEX_SORT
            String identitiesCte = extractCte(sql, "identities");
            assertThat(identitiesCte).contains("DOMAIN_INDEX_SORT");
        }

        @Test
        void shouldBuildUC6QueryWithAccountLast4ExactMatch() {
            // When
            String sql = service.buildUC6Query("john@example.com", "1234", 10);

            // Then
            assertThat(sql).contains("accountNumberLast4");
            assertThat(sql).contains("= '1234'");
        }
    }

    @Nested
    class UC7QueryTests {

        @Test
        void shouldBuildUC7QueryWithEmailPhoneAndAccount() {
            // When
            String sql = service.buildUC7Query("john@example.com", "4155551234", "9876543210", 10);

            // Then - should have all three search criteria
            assertThat(sql).contains("json_textcontains");
            assertThat(sql).contains("primaryEmail");
            assertThat(sql).contains("phoneNumber");
            assertThat(sql).contains("accountNumber");
        }

        @Test
        void shouldBuildUC7QueryWithFourWayJoin() {
            // When
            String sql = service.buildUC7Query("john@example.com", "4155551234", "9876543210", 10);

            // Then - should join identity, phone, account, address
            assertThat(sql).containsIgnoringCase("identities AS");
            assertThat(sql).containsIgnoringCase("phones AS");
            assertThat(sql).containsIgnoringCase("accounts AS");
            assertThat(sql).containsIgnoringCase("addresses AS");
        }

        @Test
        void shouldBuildUC7QueryWithEmailAsMainSearch() {
            // When
            String sql = service.buildUC7Query("john@example.com", "4155551234", "9876543210", 10);

            // Then - identities CTE should have DOMAIN_INDEX_SORT (email is main search)
            String identitiesCte = extractCte(sql, "identities");
            assertThat(identitiesCte).contains("DOMAIN_INDEX_SORT");
        }
    }

    @Nested
    class WildcardSearchTests {

        @Test
        void shouldBuildEndsWithWildcardCorrectly() {
            // When - SSN ending in 6789
            String wildcard = service.buildEndsWithPattern("6789");

            // Then - wildcard before term (per Oracle team guidance)
            assertThat(wildcard).isEqualTo("%6789");
        }

        @Test
        void shouldBuildStartsWithWildcardCorrectly() {
            // When
            String wildcard = service.buildStartsWithPattern("415");

            // Then
            assertThat(wildcard).isEqualTo("415%");
        }

        @Test
        void shouldBuildContainsWildcardCorrectly() {
            // When
            String wildcard = service.buildContainsPattern("main");

            // Then
            assertThat(wildcard).isEqualTo("%main%");
        }
    }

    @Nested
    class SqlEscapingTests {

        @Test
        void shouldEscapeSingleQuotesInSearchTerms() {
            // When
            String sql = service.buildUC1Query("415'555'1234", "6789", 10);

            // Then - single quotes should be escaped
            assertThat(sql).contains("415''555''1234");
        }

        @Test
        void shouldNotContainUnescapedSingleQuotes() {
            // When - search term with single quote
            String sql = service.buildUC6Query("john.o'brien@example.com", "1234", 10);

            // Then - should have escaped quote
            assertThat(sql).contains("o''brien");
        }
    }

    @Nested
    class CollectionPrefixTests {

        @Test
        void shouldUseCollectionPrefix() {
            // Given
            MongoSqlSearchService prefixedService = new MongoSqlSearchService(null, "admin", "bench_");

            // When
            String sql = prefixedService.buildUC1Query("4155551234", "6789", 10);

            // Then
            assertThat(sql).contains("\"bench_phone\"");
            assertThat(sql).contains("\"bench_identity\"");
            assertThat(sql).contains("\"bench_address\"");
        }

        @Test
        void shouldWorkWithoutPrefix() {
            // When
            String sql = service.buildUC1Query("4155551234", "6789", 10);

            // Then
            assertThat(sql).contains("\"phone\"");
            assertThat(sql).contains("\"identity\"");
            assertThat(sql).contains("\"address\"");
        }

        @Test
        void shouldUseCorrectPrefixForAllCollections() {
            // Given
            MongoSqlSearchService prefixedService = new MongoSqlSearchService(null, "admin", "test_");

            // When
            String sql = prefixedService.buildUC7Query("john@example.com", "4155551234", "9876543210", 10);

            // Then - all four collections should have prefix
            assertThat(sql).contains("\"test_identity\"");
            assertThat(sql).contains("\"test_phone\"");
            assertThat(sql).contains("\"test_account\"");
            assertThat(sql).contains("\"test_address\"");
        }
    }

    @Nested
    class ValidationTests {

        @Test
        void shouldRejectNullPhoneNumber() {
            assertThatThrownBy(() -> service.searchUC1(null, "1234", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Phone number");
        }

        @Test
        void shouldRejectEmptyPhoneNumber() {
            assertThatThrownBy(() -> service.searchUC1("", "1234", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Phone number");
        }

        @Test
        void shouldRejectBlankPhoneNumber() {
            assertThatThrownBy(() -> service.searchUC1("   ", "1234", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Phone number");
        }

        @Test
        void shouldRejectNullSsnLast4() {
            assertThatThrownBy(() -> service.searchUC1("4155551234", null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSN last 4");
        }

        @Test
        void shouldRejectInvalidSsnLast4Length() {
            assertThatThrownBy(() -> service.searchUC1("4155551234", "123", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4 digits");
        }

        @Test
        void shouldRejectTooLongSsnLast4() {
            assertThatThrownBy(() -> service.searchUC1("4155551234", "12345", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4 digits");
        }

        @Test
        void shouldRejectZeroLimit() {
            assertThatThrownBy(() -> service.searchUC1("4155551234", "1234", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        }

        @Test
        void shouldRejectNegativeLimit() {
            assertThatThrownBy(() -> service.searchUC1("4155551234", "1234", -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        }

        @Test
        void shouldRejectNullAccountLast4ForUC2() {
            assertThatThrownBy(() -> service.searchUC2("4155551234", "1234", null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account last 4");
        }

        @Test
        void shouldRejectInvalidAccountLast4Length() {
            assertThatThrownBy(() -> service.searchUC2("4155551234", "1234", "12", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4 digits");
        }

        @Test
        void shouldRejectNullCityForUC5() {
            assertThatThrownBy(() -> service.searchUC5(null, "CA", "94102", "1234", "5678", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("City");
        }

        @Test
        void shouldRejectNullEmailForUC6() {
            assertThatThrownBy(() -> service.searchUC6(null, "1234", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Email");
        }

        @Test
        void shouldRejectNullAccountNumberForUC4() {
            assertThatThrownBy(() -> service.searchUC4(null, "1234", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account number");
        }
    }

    @Nested
    class OutputFieldTests {

        @Test
        void shouldIncludeRankingScoreInOutput() {
            String sql = service.buildUC1Query("4155551234", "6789", 10);
            assertThat(sql).contains("'rankingScore'");
        }

        @Test
        void shouldIncludeEcnInOutput() {
            String sql = service.buildUC1Query("4155551234", "6789", 10);
            assertThat(sql).contains("'ecn'");
        }

        @Test
        void shouldIncludeEntityTypeInOutput() {
            String sql = service.buildUC1Query("4155551234", "6789", 10);
            assertThat(sql).contains("'entityType'");
        }

        @Test
        void shouldIncludeNameInOutput() {
            String sql = service.buildUC1Query("4155551234", "6789", 10);
            assertThat(sql).contains("'name'");
        }

        @Test
        void shouldIncludeAddressFieldsInOutput() {
            String sql = service.buildUC1Query("4155551234", "6789", 10);
            assertThat(sql).contains("'addressLine'");
            assertThat(sql).contains("'cityName'");
            assertThat(sql).contains("'state'");
            assertThat(sql).contains("'postalCode'");
            assertThat(sql).contains("'countryCode'");
        }

        @Test
        void shouldIncludeCustomerTypeInOutput() {
            String sql = service.buildUC1Query("4155551234", "6789", 10);
            assertThat(sql).contains("'customerType'");
        }

        @Test
        void shouldHandleAlternateNameForIndividualAndBusiness() {
            String sql = service.buildUC1Query("4155551234", "6789", 10);
            // Should have CASE statement for alternateName
            assertThat(sql).contains("'alternateName'");
            assertThat(sql).contains("INDIVIDUAL");
            assertThat(sql).contains("firstName");
            assertThat(sql).contains("businessDescriptionText");
        }
    }

    @Nested
    class JsonPathSyntaxTests {

        @Test
        void shouldUseQuotedJsonPaths() {
            String sql = service.buildUC1Query("4155551234", "6789", 10);
            // Oracle MongoDB API uses quoted JSON paths in $sql
            assertThat(sql).contains("\"DATA\"");
        }

        @Test
        void shouldUseJsonValueForNestedPaths() {
            String sql = service.buildUC1Query("4155551234", "6789", 10);
            // Should access nested fields via JSON_VALUE function
            assertThat(sql).contains("JSON_VALUE(");
            assertThat(sql).contains("$._id");
            assertThat(sql).contains("$.common");
        }

        @Test
        void shouldUseFlatAddressPathsNotArrayIndex() {
            String sql = service.buildUC1Query("4155551234", "6789", 10);
            // Should access address fields at top level (not in array)
            assertThat(sql).contains("$.addressLine1");
            assertThat(sql).contains("$.cityName");
            assertThat(sql).contains("$.stateCode");
            assertThat(sql).contains("$.postalCode");
            // Should NOT use array index (addresses is now flat structure)
            assertThat(sql).doesNotContain("addresses[0]");
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Extracts a CTE (Common Table Expression) block from the SQL.
     */
    private String extractCte(String sql, String cteName) {
        String pattern = cteName + " AS";
        int start = sql.toLowerCase().indexOf(cteName.toLowerCase() + " as");
        if (start == -1) return "";

        // Find the matching parentheses
        int parenStart = sql.indexOf("(", start);
        if (parenStart == -1) return "";

        int depth = 0;
        int parenEnd = -1;
        for (int i = parenStart; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) {
                    parenEnd = i;
                    break;
                }
            }
        }

        if (parenEnd == -1) return "";
        return sql.substring(parenStart, parenEnd + 1);
    }
}
