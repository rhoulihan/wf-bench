package com.wf.benchmark.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TDD tests for SqlJoinSearchService.
 * Uses Oracle SQL JOINs with json_value() for multi-collection queries.
 *
 * UC-1: Phone + SSN Last 4 (2-way join: phone -> identity)
 * UC-2: Phone + SSN + Account (3-way join: phone -> identity -> account)
 * UC-4: Account + SSN (2-way join: account -> identity)
 * UC-6: Email + Account Last 4 (2-way join: identity -> account)
 */
class SqlJoinSearchServiceTest {

    private FuzzySearchServiceTest.StubDataSource stubDataSource;
    private SqlJoinSearchService sqlJoinSearchService;

    @BeforeEach
    void setUp() {
        stubDataSource = new FuzzySearchServiceTest.StubDataSource();
        sqlJoinSearchService = new SqlJoinSearchService(stubDataSource);
    }

    @Nested
    class UC1PhoneSsnLast4Tests {

        @Test
        void shouldFindMatchingPhoneAndSsnLast4() throws SQLException {
            // Given - phone and identity records that match
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "5551234567", "John Smith", "6789"}
            });

            // When
            List<SqlJoinSearchResult> results = sqlJoinSearchService.searchUC1(
                "5551234567", "6789", 10);

            // Then
            assertThat(results).hasSize(1);
            SqlJoinSearchResult result = results.get(0);
            assertThat(result.getCustomerNumber()).isEqualTo("1000000001");
            assertThat(result.getPhoneNumber()).isEqualTo("5551234567");
            assertThat(result.getFullName()).isEqualTo("John Smith");
            assertThat(result.getSsnLast4()).isEqualTo("6789");
        }

        @Test
        void shouldReturnMultipleMatchesWhenMultipleCustomersMatch() throws SQLException {
            // Given - multiple customers with same phone number and SSN last 4
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "5551234567", "John Smith", "6789"},
                {"1000000002", "5551234567", "Jane Smith", "6789"}
            });

            // When
            List<SqlJoinSearchResult> results = sqlJoinSearchService.searchUC1(
                "5551234567", "6789", 10);

            // Then
            assertThat(results).hasSize(2);
        }

        @Test
        void shouldReturnEmptyListWhenNoMatch() throws SQLException {
            // Given - no matching records
            stubDataSource.setResultData(new String[][] {});

            // When
            List<SqlJoinSearchResult> results = sqlJoinSearchService.searchUC1(
                "5559999999", "0000", 10);

            // Then
            assertThat(results).isEmpty();
        }

        @Test
        void shouldGenerateJoinQueryWithJsonValue() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {});

            // When
            sqlJoinSearchService.searchUC1("5551234567", "6789", 10);

            // Then - verify SQL contains JOIN and json_value
            String sql = stubDataSource.getLastPreparedSql();
            assertThat(sql).containsIgnoringCase("JOIN");
            assertThat(sql).containsIgnoringCase("json_value");
            assertThat(sql).containsIgnoringCase("phone");
            assertThat(sql).containsIgnoringCase("identity");
        }

        @Test
        void shouldHandleNullPhoneNumber() {
            // When/Then
            assertThatThrownBy(() -> sqlJoinSearchService.searchUC1(null, "6789", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Phone number cannot be null");
        }

        @Test
        void shouldHandleNullSsnLast4() {
            // When/Then
            assertThatThrownBy(() -> sqlJoinSearchService.searchUC1("5551234567", null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSN last 4 cannot be null");
        }

        @Test
        void shouldHandleEmptyPhoneNumber() {
            // When/Then
            assertThatThrownBy(() -> sqlJoinSearchService.searchUC1("", "6789", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Phone number cannot be empty");
        }

        @Test
        void shouldHandleEmptySsnLast4() {
            // When/Then
            assertThatThrownBy(() -> sqlJoinSearchService.searchUC1("5551234567", "", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSN last 4 cannot be empty");
        }
    }

    @Nested
    class UC2PhoneSsnAccountTests {

        @Test
        void shouldFindMatchingPhoneSsnAndAccount() throws SQLException {
            // Given - phone, identity, and account records that match
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "5551234567", "John Smith", "6789", "1234567890", "7890"}
            });

            // When
            List<SqlJoinSearchResult> results = sqlJoinSearchService.searchUC2(
                "5551234567", "6789", "7890", 10);

            // Then
            assertThat(results).hasSize(1);
            SqlJoinSearchResult result = results.get(0);
            assertThat(result.getCustomerNumber()).isEqualTo("1000000001");
            assertThat(result.getPhoneNumber()).isEqualTo("5551234567");
            assertThat(result.getSsnLast4()).isEqualTo("6789");
            assertThat(result.getAccountNumberLast4()).isEqualTo("7890");
        }

        @Test
        void shouldGenerateThreeWayJoinQuery() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {});

            // When
            sqlJoinSearchService.searchUC2("5551234567", "6789", "7890", 10);

            // Then - verify SQL contains two JOINs
            String sql = stubDataSource.getLastPreparedSql();
            assertThat(sql).containsIgnoringCase("phone");
            assertThat(sql).containsIgnoringCase("identity");
            assertThat(sql).containsIgnoringCase("account");
        }

        @Test
        void shouldHandleNullAccountLast4() {
            // When/Then
            assertThatThrownBy(() -> sqlJoinSearchService.searchUC2("5551234567", "6789", null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account last 4 cannot be null");
        }
    }

    @Nested
    class UC4AccountSsnTests {

        @Test
        void shouldFindMatchingAccountAndSsn() throws SQLException {
            // Given - account and identity records that match
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "1234567890", "John Smith", "6789"}
            });

            // When
            List<SqlJoinSearchResult> results = sqlJoinSearchService.searchUC4(
                "1234567890", "6789", 10);

            // Then
            assertThat(results).hasSize(1);
            SqlJoinSearchResult result = results.get(0);
            assertThat(result.getCustomerNumber()).isEqualTo("1000000001");
            assertThat(result.getAccountNumber()).isEqualTo("1234567890");
            assertThat(result.getSsnLast4()).isEqualTo("6789");
        }

        @Test
        void shouldGenerateAccountIdentityJoinQuery() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {});

            // When
            sqlJoinSearchService.searchUC4("1234567890", "6789", 10);

            // Then - verify SQL contains JOIN
            String sql = stubDataSource.getLastPreparedSql();
            assertThat(sql).containsIgnoringCase("JOIN");
            assertThat(sql).containsIgnoringCase("account");
            assertThat(sql).containsIgnoringCase("identity");
        }

        @Test
        void shouldHandleNullAccountNumber() {
            // When/Then
            assertThatThrownBy(() -> sqlJoinSearchService.searchUC4(null, "6789", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account number cannot be null");
        }
    }

    @Nested
    class UC6EmailAccountTests {

        @Test
        void shouldFindMatchingEmailAndAccountLast4() throws SQLException {
            // Given - identity and account records that match
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "john@example.com", "John Smith", "7890"}
            });

            // When
            List<SqlJoinSearchResult> results = sqlJoinSearchService.searchUC6(
                "john@example.com", "7890", 10);

            // Then
            assertThat(results).hasSize(1);
            SqlJoinSearchResult result = results.get(0);
            assertThat(result.getCustomerNumber()).isEqualTo("1000000001");
            assertThat(result.getEmail()).isEqualTo("john@example.com");
            assertThat(result.getAccountNumberLast4()).isEqualTo("7890");
        }

        @Test
        void shouldGenerateIdentityAccountJoinQuery() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {});

            // When
            sqlJoinSearchService.searchUC6("john@example.com", "7890", 10);

            // Then - verify SQL contains JOIN
            String sql = stubDataSource.getLastPreparedSql();
            assertThat(sql).containsIgnoringCase("JOIN");
            assertThat(sql).containsIgnoringCase("identity");
            assertThat(sql).containsIgnoringCase("account");
        }

        @Test
        void shouldHandleNullEmail() {
            // When/Then
            assertThatThrownBy(() -> sqlJoinSearchService.searchUC6(null, "7890", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Email cannot be null");
        }

        @Test
        void shouldHandleNullAccountLast4() {
            // When/Then
            assertThatThrownBy(() -> sqlJoinSearchService.searchUC6("john@example.com", null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account last 4 cannot be null");
        }
    }

    @Nested
    class ErrorHandlingTests {

        @Test
        void shouldWrapSQLExceptionInSearchException() {
            // Given
            stubDataSource.setThrowExceptionOnGetConnection(true);

            // When/Then
            assertThatThrownBy(() -> sqlJoinSearchService.searchUC1("5551234567", "6789", 10))
                .isInstanceOf(SearchException.class)
                .hasCauseInstanceOf(SQLException.class);
        }
    }

    @Nested
    class ConfigurationTests {

        @Test
        void shouldSetCollectionPrefix() {
            // Given
            SqlJoinSearchService service = new SqlJoinSearchService(stubDataSource);

            // When
            service.setCollectionPrefix("bench_");

            // Then
            assertThat(service.getCollectionPrefix()).isEqualTo("bench_");
        }

        @Test
        void shouldUseDefaultEmptyPrefix() {
            // When
            SqlJoinSearchService service = new SqlJoinSearchService(stubDataSource);

            // Then
            assertThat(service.getCollectionPrefix()).isEmpty();
        }
    }
}
