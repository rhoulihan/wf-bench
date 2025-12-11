package com.wf.benchmark.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TDD tests for PhoneticSearchService.
 * Uses Oracle SOUNDEX function for phonetic matching of names.
 * SOUNDEX encodes names by their sound, matching names that sound alike.
 *
 * Examples of SOUNDEX matching:
 * - "Sally" and "Sallie" both encode to S400
 * - "Smith" and "Smyth" both encode to S530
 * - "Catherine" and "Katherine" both encode to C365
 */
class PhoneticSearchServiceTest {

    private FuzzySearchServiceTest.StubDataSource stubDataSource;
    private PhoneticSearchService phoneticSearchService;

    @BeforeEach
    void setUp() {
        stubDataSource = new FuzzySearchServiceTest.StubDataSource();
        phoneticSearchService = new PhoneticSearchService(stubDataSource);
    }

    @Nested
    class PhoneticNameSearchTests {

        @Test
        void shouldFindExactNameMatch() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "Sally Brown", "100"}
            });

            // When
            List<PhoneticSearchResult> results = phoneticSearchService.searchByName(
                "Sally", "Brown", "identity", 10);

            // Then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getCustomerNumber()).isEqualTo("1000000001");
            assertThat(results.get(0).getMatchedValue()).isEqualTo("Sally Brown");
        }

        @Test
        void shouldFindPhoneticFirstNameMatch() throws SQLException {
            // Given - "Sallie" should match "Sally" via SOUNDEX (both S400)
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "Sally Brown", "S400"}
            });

            // When
            List<PhoneticSearchResult> results = phoneticSearchService.searchByName(
                "Sallie", "Brown", "identity", 10);

            // Then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getMatchedValue()).isEqualTo("Sally Brown");
        }

        @Test
        void shouldFindPhoneticLastNameMatch() throws SQLException {
            // Given - "Smyth" should match "Smith" via SOUNDEX (both S530)
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "John Smith", "S530"}
            });

            // When
            List<PhoneticSearchResult> results = phoneticSearchService.searchByName(
                "John", "Smyth", "identity", 10);

            // Then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getMatchedValue()).isEqualTo("John Smith");
        }

        @Test
        void shouldFindBothFirstAndLastNamePhoneticMatch() throws SQLException {
            // Given - Both names match phonetically
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "Katherine Smith", "K365-S530"}
            });

            // When - "Catherine Smyth" should match "Katherine Smith"
            List<PhoneticSearchResult> results = phoneticSearchService.searchByName(
                "Catherine", "Smyth", "identity", 10);

            // Then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getMatchedValue()).isEqualTo("Katherine Smith");
        }

        @Test
        void shouldReturnMultiplePhoneticMatches() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "Sally Brown", "S400"},
                {"1000000002", "Sallie Brown", "S400"},
                {"1000000003", "Salli Brown", "S400"}
            });

            // When
            List<PhoneticSearchResult> results = phoneticSearchService.searchByName(
                "Saly", "Brown", "identity", 10);

            // Then
            assertThat(results).hasSize(3);
        }

        @Test
        void shouldReturnEmptyListWhenNoPhoneticMatches() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {}); // No results

            // When
            List<PhoneticSearchResult> results = phoneticSearchService.searchByName(
                "XYZNONEXISTENT", "ABCNONEXISTENT", "identity", 10);

            // Then
            assertThat(results).isEmpty();
        }
    }

    @Nested
    class NicknameMatchingTests {

        @Test
        void shouldFindNicknameMatch() throws SQLException {
            // Given - "Peggy" is a nickname for "Margaret"
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "Margaret Smith", "100"}
            });

            // When - Search for Peggy, find Margaret
            List<PhoneticSearchResult> results = phoneticSearchService.searchByNameWithNicknames(
                "Peggy", "Smith", "identity", 10);

            // Then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getMatchedValue()).isEqualTo("Margaret Smith");
        }

        @Test
        void shouldFindFormalNameFromNickname() throws SQLException {
            // Given - "Bill" is a nickname for "William"
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "William Jones", "100"}
            });

            // When
            List<PhoneticSearchResult> results = phoneticSearchService.searchByNameWithNicknames(
                "Bill", "Jones", "identity", 10);

            // Then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getMatchedValue()).isEqualTo("William Jones");
        }

        @Test
        void shouldFindNicknameFromFormalName() throws SQLException {
            // Given - Can find "Bob" when searching for "Robert"
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "Bob Miller", "100"}
            });

            // When
            List<PhoneticSearchResult> results = phoneticSearchService.searchByNameWithNicknames(
                "Robert", "Miller", "identity", 10);

            // Then
            assertThat(results).hasSize(1);
        }
    }

    @Nested
    class ValidationTests {

        @Test
        void shouldHandleNullFirstName() {
            // When/Then
            assertThatThrownBy(() -> phoneticSearchService.searchByName(null, "Smith", "identity", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("First name cannot be null");
        }

        @Test
        void shouldHandleNullLastName() {
            // When/Then
            assertThatThrownBy(() -> phoneticSearchService.searchByName("John", null, "identity", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Last name cannot be null");
        }

        @Test
        void shouldHandleEmptyFirstName() {
            // When/Then
            assertThatThrownBy(() -> phoneticSearchService.searchByName("", "Smith", "identity", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("First name cannot be empty");
        }

        @Test
        void shouldHandleEmptyLastName() {
            // When/Then
            assertThatThrownBy(() -> phoneticSearchService.searchByName("John", "", "identity", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Last name cannot be empty");
        }

        @Test
        void shouldHandleNullCollection() {
            // When/Then
            assertThatThrownBy(() -> phoneticSearchService.searchByName("John", "Smith", null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Collection cannot be null");
        }
    }

    @Nested
    class SQLGenerationTests {

        @Test
        void shouldGenerateSoundexQuery() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {}); // No results

            // When
            phoneticSearchService.searchByName("John", "Smith", "identity", 10);

            // Then - verify the SQL contains SOUNDEX
            assertThat(stubDataSource.getLastPreparedSql()).contains("SOUNDEX");
        }
    }

    @Nested
    class ErrorHandlingTests {

        @Test
        void shouldWrapSQLExceptionInSearchException() {
            // Given
            stubDataSource.setThrowExceptionOnGetConnection(true);

            // When/Then
            assertThatThrownBy(() -> phoneticSearchService.searchByName("John", "Smith", "identity", 10))
                .isInstanceOf(SearchException.class)
                .hasCauseInstanceOf(SQLException.class);
        }
    }
}
