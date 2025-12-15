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

            // Then - should use json {...} output format (with MONITOR hint)
            assertThat(sql).containsIgnoringCase("SELECT");
            assertThat(sql).containsIgnoringCase("json {");
        }

        @Test
        void shouldBuildUC1QueryWithRowLimit() {
            // When
            String sql = service.buildUC1Query("4155551234", "6789", 25);

            // Then - should include FETCH FIRST with limit
            assertThat(sql).containsIgnoringCase("FETCH FIRST 25 ROWS ONLY");
        }

        @Test
        void shouldBuildUC1QueryWithSsnLast4EndsWithPattern() {
            // When
            String sql = service.buildUC1Query("4155551234", "6789", 10);

            // Then - SSN last 4 uses ends-with pattern (%term) for text search
            assertThat(sql).contains("taxIdentificationNumber");
            assertThat(sql).contains("%6789");  // ends-with pattern
        }

        @Test
        void shouldBuildUC1QueryWithJoinOnCustomerNumber() {
            // When
            String sql = service.buildUC1Query("4155551234", "6789", 10);

            // Then - should join on customerNumber
            assertThat(sql).contains("customerNumber");
        }

        @Test
        void shouldIncludeAddressJoin() {
            // When
            String sql = service.buildUC1Query("4155551234", "6789", 10);

            // Then - address should be joined for customer details
            assertThat(sql).containsIgnoringCase("addresses AS");
            assertThat(sql).containsIgnoringCase("JOIN addresses");
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
        void shouldBuildUC2QueryWithAccountLast4TextSearch() {
            // When
            String sql = service.buildUC2Query("4155551234", "6789", "1234", 10);

            // Then - account last 4 uses text search
            assertThat(sql).contains("accountNumberLast4");
            assertThat(sql).contains("json_textcontains");
            assertThat(sql).contains("1234");
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
            assertThat(sql).contains("5678");  // text search, not exact match
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
        void shouldBuildUC4QueryWithSsnEndsWithPattern() {
            // When
            String sql = service.buildUC4Query("9876543210", "6789", 10);

            // Then - SSN last 4 uses ends-with pattern for text search
            assertThat(sql).contains("taxIdentificationNumber");
            assertThat(sql).contains("%6789");  // ends-with pattern
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
        void shouldBuildUC5QueryWithStateAndZipJsonExists() {
            // When
            String sql = service.buildUC5Query("San Francisco", "CA", "94102", "6789", "1234", 10);

            // Then - state and zip use json_exists filter
            assertThat(sql).contains("json_exists");
            assertThat(sql).contains("stateCode");
            assertThat(sql).contains("CA");
            assertThat(sql).contains("postalCode");
            assertThat(sql).contains("94102");
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

            // Then - email should be text search on identity.emails array
            assertThat(sql).contains("json_textcontains");
            assertThat(sql).contains("emailAddress");
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
        void shouldBuildUC6QueryWithAccountLast4TextSearch() {
            // When
            String sql = service.buildUC6Query("john@example.com", "1234", 10);

            // Then - account last 4 uses text search
            assertThat(sql).contains("accountNumberLast4");
            assertThat(sql).contains("json_textcontains");
            assertThat(sql).contains("1234");
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
            assertThat(sql).contains("emailAddress");  // emails array field
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
        void shouldUseDotNotationForNestedPaths() {
            String sql = service.buildUC1Query("4155551234", "6789", 10);
            // Should access nested fields via dot notation
            assertThat(sql).contains("identity_data");
            assertThat(sql).contains("customerNumber");
        }

        @Test
        void shouldUseAddressFieldAccess() {
            String sql = service.buildUC1Query("4155551234", "6789", 10);
            // Should access address fields via dot notation
            assertThat(sql).contains("address_data");
            assertThat(sql).contains("addressLine1");
            assertThat(sql).contains("cityName");
            assertThat(sql).contains("stateCode");
            assertThat(sql).contains("postalCode");
        }
    }

    // ==================== UC 8-11 Tests (Other Searches) ====================

    @Nested
    class UC8QueryTests {
        // UC-8: Search by TIN (full 9-digit)

        @Test
        void shouldBuildUC8QueryWithTinTextSearch() {
            // When
            String sql = service.buildUC8Query("855611007", 10);

            // Then - TIN should be text search
            assertThat(sql).contains("json_textcontains");
            assertThat(sql).contains("taxIdentificationNumber");
            assertThat(sql).contains("855611007");
        }

        @Test
        void shouldBuildUC8QueryWithIdentityCTE() {
            // When
            String sql = service.buildUC8Query("855611007", 10);

            // Then - should have identities CTE
            assertThat(sql).containsIgnoringCase("identities AS");
        }

        @Test
        void shouldBuildUC8QueryWithAddressJoin() {
            // When
            String sql = service.buildUC8Query("855611007", 10);

            // Then - should join to addresses for customer details
            assertThat(sql).containsIgnoringCase("addresses AS");
            assertThat(sql).containsIgnoringCase("JOIN");
        }

        @Test
        void shouldBuildUC8QueryWithJsonOutput() {
            // When
            String sql = service.buildUC8Query("855611007", 10);

            // Then - should have JSON output format
            assertThat(sql).containsIgnoringCase("SELECT");
            assertThat(sql).containsIgnoringCase("json");
            assertThat(sql).contains("'ecn'");
            assertThat(sql).contains("'name'");
        }

        @Test
        void shouldBuildUC8QueryWithRowLimit() {
            // When
            String sql = service.buildUC8Query("855611007", 25);

            // Then
            assertThat(sql).containsIgnoringCase("FETCH FIRST 25 ROWS ONLY");
        }

        @Test
        void shouldValidateNullTin() {
            assertThatThrownBy(() -> service.searchUC8(null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TIN");
        }

        @Test
        void shouldValidateEmptyTin() {
            assertThatThrownBy(() -> service.searchUC8("", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TIN");
        }

        @Test
        void shouldValidateTinLength() {
            assertThatThrownBy(() -> service.searchUC8("1234", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("9 digits");
        }
    }

    @Nested
    class UC9QueryTests {
        // UC-9: Search by Account Number (with optional product type and COID)

        @Test
        void shouldBuildUC9QueryWithAccountNumberOnly() {
            // When
            String sql = service.buildUC9Query("100000375005", null, null, 10);

            // Then - account number should be text search
            assertThat(sql).contains("json_textcontains");
            assertThat(sql).contains("accountNumber");
            assertThat(sql).contains("100000375005");
        }

        @Test
        void shouldBuildUC9QueryWithProductTypeFilter() {
            // When
            String sql = service.buildUC9Query("100000375005", "CHECKING", null, 10);

            // Then - should filter by productTypeCode
            assertThat(sql).contains("productTypeCode");
            assertThat(sql).contains("CHECKING");
        }

        @Test
        void shouldBuildUC9QueryWithCoidFilter() {
            // When
            String sql = service.buildUC9Query("100000375005", null, "WF_MORTGAGE", 10);

            // Then - should filter by companyOfInterestId
            assertThat(sql).contains("companyOfInterestId");
            assertThat(sql).contains("WF_MORTGAGE");
        }

        @Test
        void shouldBuildUC9QueryWithBothFilters() {
            // When
            String sql = service.buildUC9Query("100000375005", "CHECKING", "WF_MORTGAGE", 10);

            // Then - should have both filters
            assertThat(sql).contains("productTypeCode");
            assertThat(sql).contains("CHECKING");
            assertThat(sql).contains("companyOfInterestId");
            assertThat(sql).contains("WF_MORTGAGE");
        }

        @Test
        void shouldBuildUC9QueryWithIdentityJoin() {
            // When
            String sql = service.buildUC9Query("100000375005", null, null, 10);

            // Then - should join to identity for customer details
            assertThat(sql).containsIgnoringCase("identities AS");
            assertThat(sql).containsIgnoringCase("JOIN");
        }

        @Test
        void shouldBuildUC9QueryWithAddressJoin() {
            // When
            String sql = service.buildUC9Query("100000375005", null, null, 10);

            // Then - should join to addresses
            assertThat(sql).containsIgnoringCase("addresses AS");
        }

        @Test
        void shouldBuildUC9QueryWithJsonOutputIncludingAccountDetails() {
            // When
            String sql = service.buildUC9Query("100000375005", null, null, 10);

            // Then - output should include account-specific fields
            assertThat(sql).contains("'ecn'");
            assertThat(sql).contains("'accountNumber'");
        }

        @Test
        void shouldValidateNullAccountNumber() {
            assertThatThrownBy(() -> service.searchUC9(null, null, null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account number");
        }

        @Test
        void shouldValidateEmptyAccountNumber() {
            assertThatThrownBy(() -> service.searchUC9("", null, null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account number");
        }
    }

    @Nested
    class UC10QueryTests {
        // UC-10: Search by Account Number (tokenized/hyphenated format)

        @Test
        void shouldBuildUC10QueryWithHyphenatedAccountNumber() {
            // When
            String sql = service.buildUC10Query("1000-0037-5005", 10);

            // Then - should search hyphenated field
            assertThat(sql).contains("json_textcontains");
            assertThat(sql).contains("accountNumberHyphenated");
            assertThat(sql).contains("1000-0037-5005");
        }

        @Test
        void shouldBuildUC10QueryWithAccountCTE() {
            // When
            String sql = service.buildUC10Query("1000-0037-5005", 10);

            // Then - should have accounts CTE
            assertThat(sql).containsIgnoringCase("accounts AS");
        }

        @Test
        void shouldBuildUC10QueryWithIdentityAndAddressJoins() {
            // When
            String sql = service.buildUC10Query("1000-0037-5005", 10);

            // Then - should join to identity and address
            assertThat(sql).containsIgnoringCase("identities AS");
            assertThat(sql).containsIgnoringCase("addresses AS");
        }

        @Test
        void shouldBuildUC10QueryWithJsonOutput() {
            // When
            String sql = service.buildUC10Query("1000-0037-5005", 10);

            // Then
            assertThat(sql).containsIgnoringCase("SELECT");
            assertThat(sql).containsIgnoringCase("json");
            assertThat(sql).contains("'ecn'");
        }

        @Test
        void shouldNormalizeUnhyphenatedInput() {
            // Given - user provides unhyphenated account number
            // When
            String sql = service.buildUC10Query("100000375005", 10);

            // Then - should convert to hyphenated format for search
            assertThat(sql).contains("1000-0037-5005");
        }

        @Test
        void shouldValidateNullTokenizedAccount() {
            assertThatThrownBy(() -> service.searchUC10(null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account");
        }

        @Test
        void shouldValidateEmptyTokenizedAccount() {
            assertThatThrownBy(() -> service.searchUC10("", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account");
        }
    }

    @Nested
    class UC11QueryTests {
        // UC-11: Search by Phone Number (full 10-digit)

        @Test
        void shouldBuildUC11QueryWithPhoneTextSearch() {
            // When
            String sql = service.buildUC11Query("5549414620", 10);

            // Then - phone should be text search
            assertThat(sql).contains("json_textcontains");
            assertThat(sql).contains("phoneNumber");
            assertThat(sql).contains("5549414620");
        }

        @Test
        void shouldBuildUC11QueryWithPhoneCTE() {
            // When
            String sql = service.buildUC11Query("5549414620", 10);

            // Then - should have phones CTE
            assertThat(sql).containsIgnoringCase("phones AS");
        }

        @Test
        void shouldBuildUC11QueryWithIdentityJoin() {
            // When
            String sql = service.buildUC11Query("5549414620", 10);

            // Then - should join to identity for customer details
            assertThat(sql).containsIgnoringCase("identities AS");
            assertThat(sql).containsIgnoringCase("JOIN");
        }

        @Test
        void shouldBuildUC11QueryWithAddressJoin() {
            // When
            String sql = service.buildUC11Query("5549414620", 10);

            // Then - should join to addresses
            assertThat(sql).containsIgnoringCase("addresses AS");
        }

        @Test
        void shouldBuildUC11QueryWithJsonOutput() {
            // When
            String sql = service.buildUC11Query("5549414620", 10);

            // Then
            assertThat(sql).containsIgnoringCase("SELECT");
            assertThat(sql).containsIgnoringCase("json");
            assertThat(sql).contains("'ecn'");
            assertThat(sql).contains("'phoneNumber'");
        }

        @Test
        void shouldBuildUC11QueryWithRowLimit() {
            // When
            String sql = service.buildUC11Query("5549414620", 25);

            // Then
            assertThat(sql).containsIgnoringCase("FETCH FIRST 25 ROWS ONLY");
        }

        @Test
        void shouldValidateNullPhoneNumber() {
            assertThatThrownBy(() -> service.searchUC11(null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Phone");
        }

        @Test
        void shouldValidateEmptyPhoneNumber() {
            assertThatThrownBy(() -> service.searchUC11("", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Phone");
        }

        @Test
        void shouldValidatePhoneNumberLength() {
            assertThatThrownBy(() -> service.searchUC11("12345", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10 digits");
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
