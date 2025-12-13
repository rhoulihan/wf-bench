package com.wf.benchmark.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TDD tests for UcSearchService.
 * Tests UC 1-7 search queries using Oracle Text SCORE() with full JSON search indexes.
 *
 * UC-1: Phone + SSN Last 4
 * UC-2: Phone + SSN Last 4 + Account Last 4
 * UC-3: Phone + Account Last 4
 * UC-4: Account Number + SSN Last 4
 * UC-5: City/State/ZIP + SSN Last 4 + Account Last 4
 * UC-6: Email + Account Last 4
 * UC-7: Email + Phone + Account Number
 */
class UcSearchServiceTest {

    private StubDataSource stubDataSource;
    private UcSearchService ucSearchService;

    @BeforeEach
    void setUp() {
        stubDataSource = new StubDataSource();
        ucSearchService = new UcSearchService(stubDataSource, "bench_");
    }

    @Nested
    class UC1Tests {

        @Test
        void shouldSearchByPhoneAndSsnLast4() throws SQLException {
            // Given
            stubDataSource.setResultData(new Object[][] {
                {100, "1000000001", 1, "INDIVIDUAL", "JOHN SMITH", "John", "123456789", "SSN",
                 "1980-01-15", "123 Main St", "New York", "NY", "10001", "US", "Customer"}
            });

            // When
            List<UcSearchResult> results = ucSearchService.searchUC1("5551234567", "6789", 10);

            // Then
            assertThat(results).hasSize(1);
            UcSearchResult result = results.get(0);
            assertThat(result.getRankingScore()).isEqualTo(100);
            assertThat(result.getEcn()).isEqualTo("1000000001");
            assertThat(result.getCompanyId()).isEqualTo(1);
            assertThat(result.getEntityType()).isEqualTo("INDIVIDUAL");
            assertThat(result.getName()).isEqualTo("JOHN SMITH");
            assertThat(result.getAlternateName()).isEqualTo("John");
            assertThat(result.getTaxIdNumber()).isEqualTo("123456789");
            assertThat(result.getTaxIdType()).isEqualTo("SSN");
            assertThat(result.getBirthDate()).isEqualTo("1980-01-15");
            assertThat(result.getAddressLine()).isEqualTo("123 Main St");
            assertThat(result.getCityName()).isEqualTo("New York");
            assertThat(result.getState()).isEqualTo("NY");
            assertThat(result.getPostalCode()).isEqualTo("10001");
            assertThat(result.getCountryCode()).isEqualTo("US");
            assertThat(result.getCustomerType()).isEqualTo("Customer");
        }

        @Test
        void shouldReturnEmptyListWhenNoMatches() throws SQLException {
            // Given
            stubDataSource.setResultData(new Object[0][]);

            // When
            List<UcSearchResult> results = ucSearchService.searchUC1("5559999999", "0000", 10);

            // Then
            assertThat(results).isEmpty();
        }

        @Test
        void shouldUseContainsWithScoreInQuery() throws SQLException {
            // Given
            stubDataSource.setResultData(new Object[0][]);

            // When
            ucSearchService.searchUC1("5551234567", "6789", 10);

            // Then
            String sql = stubDataSource.getLastPreparedSql();
            assertThat(sql).contains("CONTAINS");
            assertThat(sql).contains("SCORE(1)");
        }
    }

    @Nested
    class UC2Tests {

        @Test
        void shouldSearchByPhoneSsnLast4AndAccountLast4() throws SQLException {
            // Given
            stubDataSource.setResultData(new Object[][] {
                {95, "1000000002", 1, "INDIVIDUAL", "JANE DOE", "Jane", "987654321", "SSN",
                 "1985-06-20", "456 Oak Ave", "Los Angeles", "CA", "90001", "US", "Prospect"}
            });

            // When
            List<UcSearchResult> results = ucSearchService.searchUC2("5551234567", "4321", "7890", 10);

            // Then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getRankingScore()).isEqualTo(95);
            assertThat(results.get(0).getCustomerType()).isEqualTo("Prospect");
        }

        @Test
        void shouldJoinPhoneIdentityAndAccount() throws SQLException {
            // Given
            stubDataSource.setResultData(new Object[0][]);

            // When
            ucSearchService.searchUC2("5551234567", "4321", "7890", 10);

            // Then
            String sql = stubDataSource.getLastPreparedSql();
            assertThat(sql).contains("bench_phone");
            assertThat(sql).contains("bench_identity");
            assertThat(sql).contains("bench_account");
        }
    }

    @Nested
    class UC3Tests {

        @Test
        void shouldSearchByPhoneAndAccountLast4() throws SQLException {
            // Given
            stubDataSource.setResultData(new Object[][] {
                {90, "1000000003", 2, "INDIVIDUAL", "BOB WILSON", "Bob", "111223333", "SSN",
                 "1990-03-10", "789 Pine Rd", "Chicago", "IL", "60601", "US", "Customer"}
            });

            // When
            List<UcSearchResult> results = ucSearchService.searchUC3("5551234567", "5678", 10);

            // Then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getName()).isEqualTo("BOB WILSON");
        }
    }

    @Nested
    class UC4Tests {

        @Test
        void shouldSearchByAccountNumberAndSsnLast4() throws SQLException {
            // Given
            stubDataSource.setResultData(new Object[][] {
                {85, "1000000004", 1, "NON_INDIVIDUAL", "ACME CORPORATION", "Manufacturing", "123456789", "EIN",
                 null, "100 Business Pkwy", "Seattle", "WA", "98101", "US", "Customer"}
            });

            // When
            List<UcSearchResult> results = ucSearchService.searchUC4("9876543210", "6789", 10);

            // Then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getEntityType()).isEqualTo("NON_INDIVIDUAL");
            assertThat(results.get(0).getBirthDate()).isNull();
            assertThat(results.get(0).getTaxIdType()).isEqualTo("EIN");
        }

        @Test
        void shouldStartFromAccountCollection() throws SQLException {
            // Given
            stubDataSource.setResultData(new Object[0][]);

            // When
            ucSearchService.searchUC4("9876543210", "6789", 10);

            // Then
            String sql = stubDataSource.getLastPreparedSql();
            assertThat(sql).contains("bench_account");
            assertThat(sql).contains("CONTAINS");
        }
    }

    @Nested
    class UC5Tests {

        @Test
        void shouldSearchByCityStateZipSsnLast4AndAccountLast4() throws SQLException {
            // Given
            stubDataSource.setResultData(new Object[][] {
                {80, "1000000005", 1, "INDIVIDUAL", "ALICE BROWN", "Alice", "444556666", "SSN",
                 "1975-12-01", "555 Elm St", "Miami", "FL", "33101", "US", "Youth Banking"}
            });

            // When
            List<UcSearchResult> results = ucSearchService.searchUC5("Miami", "FL", "33101", "6666", "1234", 10);

            // Then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getCityName()).isEqualTo("Miami");
            assertThat(results.get(0).getState()).isEqualTo("FL");
            assertThat(results.get(0).getPostalCode()).isEqualTo("33101");
            assertThat(results.get(0).getCustomerType()).isEqualTo("Youth Banking");
        }

        @Test
        void shouldJoinAddressIdentityAndAccount() throws SQLException {
            // Given
            stubDataSource.setResultData(new Object[0][]);

            // When
            ucSearchService.searchUC5("Miami", "FL", "33101", "6666", "1234", 10);

            // Then
            String sql = stubDataSource.getLastPreparedSql();
            assertThat(sql).contains("bench_address");
            assertThat(sql).contains("bench_identity");
            assertThat(sql).contains("bench_account");
        }
    }

    @Nested
    class UC6Tests {

        @Test
        void shouldSearchByEmailAndAccountLast4() throws SQLException {
            // Given
            stubDataSource.setResultData(new Object[][] {
                {75, "1000000006", 1, "INDIVIDUAL", "CHARLIE DAVIS", "Charlie", "777889999", "SSN",
                 "1988-07-22", "222 Maple Dr", "Boston", "MA", "02101", "US", "Customer"}
            });

            // When
            List<UcSearchResult> results = ucSearchService.searchUC6("charlie@example.com", "4567", 10);

            // Then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getName()).isEqualTo("CHARLIE DAVIS");
        }

        @Test
        void shouldStartFromIdentityCollection() throws SQLException {
            // Given
            stubDataSource.setResultData(new Object[0][]);

            // When
            ucSearchService.searchUC6("test@example.com", "1234", 10);

            // Then
            // UC-6 searches for email via CONTAINS on identity collection (full JSON search index)
            // The search index covers all fields including the embedded emails array
            String sql = stubDataSource.getLastPreparedSql();
            assertThat(sql).contains("bench_identity");
            assertThat(sql).contains("CONTAINS(i.DATA");
        }
    }

    @Nested
    class UC7Tests {

        @Test
        void shouldSearchByEmailPhoneAndAccountNumber() throws SQLException {
            // Given
            stubDataSource.setResultData(new Object[][] {
                {70, "1000000007", 2, "INDIVIDUAL", "DAVID MILLER", "David", "111222333", "SSN",
                 "1995-09-05", "333 Birch Ln", "Denver", "CO", "80201", "US", "Prospect"}
            });

            // When
            List<UcSearchResult> results = ucSearchService.searchUC7("david@example.com", "5551234567", "1234567890", 10);

            // Then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getName()).isEqualTo("DAVID MILLER");
            assertThat(results.get(0).getCustomerType()).isEqualTo("Prospect");
        }

        @Test
        void shouldJoinIdentityPhoneAndAccount() throws SQLException {
            // Given
            stubDataSource.setResultData(new Object[0][]);

            // When
            ucSearchService.searchUC7("test@example.com", "5551234567", "1234567890", 10);

            // Then
            String sql = stubDataSource.getLastPreparedSql();
            assertThat(sql).contains("bench_identity");
            assertThat(sql).contains("bench_phone");
            assertThat(sql).contains("bench_account");
        }
    }

    @Nested
    class SearchIndexTests {

        @Test
        void shouldGenerateCreateIndexStatements() {
            // When
            List<String> statements = ucSearchService.getCreateSearchIndexStatements();

            // Then
            assertThat(statements).hasSize(4);
            assertThat(statements).anyMatch(s -> s.contains("bench_identity") && s.contains("SEARCH INDEX"));
            assertThat(statements).anyMatch(s -> s.contains("bench_phone") && s.contains("SEARCH INDEX"));
            assertThat(statements).anyMatch(s -> s.contains("bench_account") && s.contains("SEARCH INDEX"));
            assertThat(statements).anyMatch(s -> s.contains("bench_address") && s.contains("SEARCH INDEX"));
        }

        @Test
        void shouldGenerateDropIndexStatements() {
            // When
            List<String> statements = ucSearchService.getDropSearchIndexStatements();

            // Then
            assertThat(statements).hasSize(4);
            assertThat(statements).allMatch(s -> s.contains("DROP INDEX"));
        }
    }

    @Nested
    class UnifiedDbmsSearchIndexTests {

        @Test
        void shouldGenerateUnifiedIndexName() {
            // When
            String indexName = ucSearchService.getUnifiedIndexName();

            // Then
            assertThat(indexName).isEqualTo("idx_bench_uc_unified");
        }

        @Test
        void shouldGenerateUnifiedIndexNameWithoutPrefix() {
            // Given
            UcSearchService service = new UcSearchService(stubDataSource);

            // When
            String indexName = service.getUnifiedIndexName();

            // Then
            assertThat(indexName).isEqualTo("idx_uc_unified");
        }

        @Test
        void shouldGenerateCreateUnifiedIndexStatement() {
            // When
            String statement = ucSearchService.getCreateUnifiedIndexStatement();

            // Then
            assertThat(statement).contains("DBMS_SEARCH.CREATE_INDEX");
            assertThat(statement).contains("idx_bench_uc_unified");
        }

        @Test
        void shouldGenerateAddSourceStatements() {
            // When
            List<String> statements = ucSearchService.getAddSourceStatements();

            // Then
            assertThat(statements).hasSize(4);
            assertThat(statements).anyMatch(s -> s.contains("DBMS_SEARCH.ADD_SOURCE") && s.contains("bench_identity"));
            assertThat(statements).anyMatch(s -> s.contains("DBMS_SEARCH.ADD_SOURCE") && s.contains("bench_phone"));
            assertThat(statements).anyMatch(s -> s.contains("DBMS_SEARCH.ADD_SOURCE") && s.contains("bench_account"));
            assertThat(statements).anyMatch(s -> s.contains("DBMS_SEARCH.ADD_SOURCE") && s.contains("bench_address"));
        }

        @Test
        void shouldGenerateDropUnifiedIndexStatement() {
            // When
            String statement = ucSearchService.getDropUnifiedIndexStatement();

            // Then
            assertThat(statement).contains("DBMS_SEARCH.DROP_INDEX");
            assertThat(statement).contains("idx_bench_uc_unified");
        }

        @Test
        void shouldGenerateFindQueryForUnifiedIndex() {
            // When
            String query = ucSearchService.buildUnifiedFindQuery("phone:5551234567", 10);

            // Then
            assertThat(query).contains("DBMS_SEARCH.FIND");
            assertThat(query).contains("idx_bench_uc_unified");
        }

        // ==================== Unified UC 1-7 Query Tests ====================

        @Test
        void shouldBuildFuzzyOrQueryWithTwoTerms() {
            // When - UC-1 style: Phone + SSN Last 4
            String query = ucSearchService.buildFuzzyOrQuery(List.of("5551234567", "6789"), 10);

            // Then - uses CONTAINS with fuzzy OR query on source tables via V_UC_ views
            assertThat(query).contains("CONTAINS");
            // Now queries source tables directly with V_UC_ views
            assertThat(query).contains("V_UC_PHONE");
            assertThat(query).contains("V_UC_IDENTITY");
            assertThat(query).contains("V_UC_ACCOUNT");
            assertThat(query).contains("V_UC_ADDRESS");
            assertThat(query).contains("fuzzy(5551234567)");
            assertThat(query).contains("fuzzy(6789)");
            assertThat(query).contains("OR");
        }

        @Test
        void shouldBuildFuzzyOrQueryWithThreeTerms() {
            // When - UC-2 style: Phone + SSN Last 4 + Account Last 4
            String query = ucSearchService.buildFuzzyOrQuery(List.of("5551234567", "6789", "1234"), 10);

            // Then - uses CONTAINS with fuzzy OR query on source tables via V_UC_ views
            assertThat(query).contains("CONTAINS");
            // Now queries source tables directly with V_UC_ views
            assertThat(query).contains("V_UC_PHONE");
            assertThat(query).contains("V_UC_IDENTITY");
            assertThat(query).contains("fuzzy(5551234567)");
            assertThat(query).contains("fuzzy(6789)");
            assertThat(query).contains("fuzzy(1234)");
        }

        @Test
        void shouldBuildFuzzyOrQueryWithPhoneAndAccountLast4() {
            // When - UC-3 style: Phone + Account Last 4
            String query = ucSearchService.buildFuzzyOrQuery(List.of("5551234567", "1234"), 10);

            // Then - uses CONTAINS with fuzzy OR query on index table
            assertThat(query).contains("CONTAINS");
            assertThat(query).contains("fuzzy(5551234567)");
            assertThat(query).contains("fuzzy(1234)");
        }

        @Test
        void shouldBuildFuzzyOrQueryWithAccountAndSsn() {
            // When - UC-4 style: Account Number + SSN Last 4
            String query = ucSearchService.buildFuzzyOrQuery(List.of("1234567890", "6789"), 10);

            // Then - uses CONTAINS with fuzzy OR query on index table
            assertThat(query).contains("CONTAINS");
            assertThat(query).contains("fuzzy(1234567890)");
            assertThat(query).contains("fuzzy(6789)");
        }

        @Test
        void shouldBuildFuzzyOrQueryWithAddressAndAccountTerms() {
            // When - UC-5 style: City/State/ZIP + SSN Last 4 + Account Last 4
            String query = ucSearchService.buildFuzzyOrQuery(List.of("New York", "NY", "10001", "6789", "1234"), 10);

            // Then - uses CONTAINS with fuzzy OR query on index table
            assertThat(query).contains("CONTAINS");
            assertThat(query).contains("fuzzy(New York)");
            assertThat(query).contains("fuzzy(NY)");
            assertThat(query).contains("fuzzy(10001)");
        }

        @Test
        void shouldBuildFuzzyOrQueryWithEmailAndAccountLast4() {
            // When - UC-6 style: Email + Account Last 4
            String query = ucSearchService.buildFuzzyOrQuery(List.of("john@example.com", "1234"), 10);

            // Then - uses CONTAINS with fuzzy OR query on index table
            assertThat(query).contains("CONTAINS");
            assertThat(query).contains("fuzzy(john@example.com)");
            assertThat(query).contains("fuzzy(1234)");
        }

        @Test
        void shouldBuildFuzzyOrQueryWithEmailPhoneAccount() {
            // When - UC-7 style: Email + Phone + Account Number
            String query = ucSearchService.buildFuzzyOrQuery(List.of("john@example.com", "5551234567", "1234567890"), 10);

            // Then - uses CONTAINS with fuzzy OR query on index table
            assertThat(query).contains("CONTAINS");
            assertThat(query).contains("fuzzy(john@example.com)");
            assertThat(query).contains("fuzzy(5551234567)");
            assertThat(query).contains("fuzzy(1234567890)");
        }

        @Test
        void shouldFilterNullAndBlankTermsFromFuzzyOrQuery() {
            // When - some terms are blank (using Arrays.asList to allow nulls)
            String query = ucSearchService.buildFuzzyOrQuery(java.util.Arrays.asList("5551234567", null, "", "  ", "6789"), 10);

            // Then - only valid terms are included
            assertThat(query).contains("fuzzy(5551234567)");
            assertThat(query).contains("fuzzy(6789)");
            assertThat(query).doesNotContain("fuzzy()");
            assertThat(query).doesNotContain("fuzzy(null)");
        }

        // NOTE: searchUnifiedUC1-7 execution tests require real Oracle DBMS_SEARCH.FIND
        // JSON responses which can't be easily mocked with a stub datasource.
        // The algorithm logic (grouping, filtering, scoring) is thoroughly tested in:
        // - UnifiedSearchAlgorithmTest.java (40+ tests covering SearchHit, SearchCategory, CustomerHitGroup)
        // Integration tests should be used to verify end-to-end DBMS_SEARCH functionality.
    }

    @Nested
    class ConfigurationTests {

        @Test
        void shouldUseConfiguredCollectionPrefix() {
            // Given
            UcSearchService service = new UcSearchService(stubDataSource, "test_");
            stubDataSource.setResultData(new Object[0][]);

            // When
            service.searchUC1("5551234567", "1234", 10);

            // Then
            String sql = stubDataSource.getLastPreparedSql();
            assertThat(sql).contains("test_phone");
            assertThat(sql).contains("test_identity");
        }

        @Test
        void shouldDefaultToEmptyPrefix() {
            // Given
            UcSearchService service = new UcSearchService(stubDataSource);
            stubDataSource.setResultData(new Object[0][]);

            // When
            service.searchUC1("5551234567", "1234", 10);

            // Then
            String sql = stubDataSource.getLastPreparedSql();
            assertThat(sql).doesNotContain("bench_");
        }
    }

    @Nested
    class ValidationTests {

        @Test
        void shouldRejectNullPhoneNumber() {
            assertThatThrownBy(() -> ucSearchService.searchUC1(null, "1234", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Phone number");
        }

        @Test
        void shouldRejectNullSsnLast4() {
            assertThatThrownBy(() -> ucSearchService.searchUC1("5551234567", null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSN last 4");
        }

        @Test
        void shouldRejectInvalidSsnLast4Length() {
            assertThatThrownBy(() -> ucSearchService.searchUC1("5551234567", "123", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4 digits");
        }

        @Test
        void shouldRejectNullAccountLast4ForUC2() {
            assertThatThrownBy(() -> ucSearchService.searchUC2("5551234567", "1234", null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account last 4");
        }

        @Test
        void shouldRejectInvalidLimit() {
            assertThatThrownBy(() -> ucSearchService.searchUC1("5551234567", "1234", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        }
    }

    @Nested
    class ErrorHandlingTests {

        @Test
        void shouldWrapSQLExceptionInSearchException() {
            // Given
            stubDataSource.setThrowExceptionOnGetConnection(true);

            // When/Then
            assertThatThrownBy(() -> ucSearchService.searchUC1("5551234567", "1234", 10))
                .isInstanceOf(SearchException.class)
                .hasCauseInstanceOf(SQLException.class);
        }
    }

    @Nested
    class ResultMappingTests {

        @Test
        void shouldMapAllFieldsCorrectly() throws SQLException {
            // Given - all fields populated
            stubDataSource.setResultData(new Object[][] {
                {100, "ECN123", 42, "INDIVIDUAL", "Full Name", "Alt Name", "123456789", "SSN",
                 "2000-01-01", "Address Line", "City", "ST", "12345", "US", "Customer"}
            });

            // When
            List<UcSearchResult> results = ucSearchService.searchUC1("5551234567", "6789", 10);

            // Then
            assertThat(results).hasSize(1);
            UcSearchResult r = results.get(0);
            assertThat(r.getRankingScore()).isEqualTo(100);
            assertThat(r.getEcn()).isEqualTo("ECN123");
            assertThat(r.getCompanyId()).isEqualTo(42);
            assertThat(r.getEntityType()).isEqualTo("INDIVIDUAL");
            assertThat(r.getName()).isEqualTo("Full Name");
            assertThat(r.getAlternateName()).isEqualTo("Alt Name");
            assertThat(r.getTaxIdNumber()).isEqualTo("123456789");
            assertThat(r.getTaxIdType()).isEqualTo("SSN");
            assertThat(r.getBirthDate()).isEqualTo("2000-01-01");
            assertThat(r.getAddressLine()).isEqualTo("Address Line");
            assertThat(r.getCityName()).isEqualTo("City");
            assertThat(r.getState()).isEqualTo("ST");
            assertThat(r.getPostalCode()).isEqualTo("12345");
            assertThat(r.getCountryCode()).isEqualTo("US");
            assertThat(r.getCustomerType()).isEqualTo("Customer");
        }

        @Test
        void shouldHandleNullableFields() throws SQLException {
            // Given - optional fields are null
            stubDataSource.setResultData(new Object[][] {
                {100, "ECN123", 1, "NON_INDIVIDUAL", "Business Name", null, "123456789", "EIN",
                 null, null, null, null, null, "US", "Customer"}
            });

            // When
            List<UcSearchResult> results = ucSearchService.searchUC1("5551234567", "6789", 10);

            // Then
            assertThat(results).hasSize(1);
            UcSearchResult r = results.get(0);
            assertThat(r.getAlternateName()).isNull();
            assertThat(r.getBirthDate()).isNull();
            assertThat(r.getAddressLine()).isNull();
            assertThat(r.getCityName()).isNull();
        }

        @Test
        void shouldReturnResultsOrderedByScore() throws SQLException {
            // Given - results in score order
            stubDataSource.setResultData(new Object[][] {
                {100, "ECN1", 1, "INDIVIDUAL", "Name1", "Alt1", "111", "SSN", null, null, null, null, null, "US", "Customer"},
                {90, "ECN2", 1, "INDIVIDUAL", "Name2", "Alt2", "222", "SSN", null, null, null, null, null, "US", "Customer"},
                {80, "ECN3", 1, "INDIVIDUAL", "Name3", "Alt3", "333", "SSN", null, null, null, null, null, "US", "Customer"}
            });

            // When
            List<UcSearchResult> results = ucSearchService.searchUC1("5551234567", "1234", 10);

            // Then
            assertThat(results).hasSize(3);
            assertThat(results.get(0).getRankingScore()).isEqualTo(100);
            assertThat(results.get(1).getRankingScore()).isEqualTo(90);
            assertThat(results.get(2).getRankingScore()).isEqualTo(80);
        }
    }

    /**
     * Stub DataSource for testing without Mockito (Java 23 compatibility).
     */
    static class StubDataSource implements DataSource {
        private Object[][] resultData = new Object[0][];
        private boolean throwExceptionOnGetConnection = false;
        private String lastPreparedSql = null;
        private boolean wasExecuted = false;

        void setResultData(Object[][] data) {
            this.resultData = data;
        }

        void setThrowExceptionOnGetConnection(boolean throwException) {
            this.throwExceptionOnGetConnection = throwException;
        }

        String getLastPreparedSql() {
            return lastPreparedSql;
        }

        boolean wasExecuted() {
            return wasExecuted;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (throwExceptionOnGetConnection) {
                throw new SQLException("Connection failed");
            }
            return new StubConnection(this);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    static class StubConnection implements Connection {
        private final StubDataSource dataSource;

        StubConnection(StubDataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public PreparedStatement prepareStatement(String sql) {
            dataSource.lastPreparedSql = sql;
            return new StubPreparedStatement(dataSource);
        }

        @Override
        public Statement createStatement() {
            return new StubStatement(dataSource);
        }

        @Override public void close() {}
        @Override public boolean isClosed() { return false; }
        @Override public void setAutoCommit(boolean autoCommit) {}
        @Override public boolean getAutoCommit() { return true; }
        @Override public void commit() {}
        @Override public void rollback() {}
        @Override public java.sql.DatabaseMetaData getMetaData() { return null; }
        @Override public void setReadOnly(boolean readOnly) {}
        @Override public boolean isReadOnly() { return false; }
        @Override public void setCatalog(String catalog) {}
        @Override public String getCatalog() { return null; }
        @Override public void setTransactionIsolation(int level) {}
        @Override public int getTransactionIsolation() { return 0; }
        @Override public java.sql.SQLWarning getWarnings() { return null; }
        @Override public void clearWarnings() {}
        @Override public Statement createStatement(int a, int b) { return null; }
        @Override public PreparedStatement prepareStatement(String sql, int a, int b) { return null; }
        @Override public java.sql.CallableStatement prepareCall(String sql) { return null; }
        @Override public java.sql.CallableStatement prepareCall(String sql, int a, int b) { return null; }
        @Override public String nativeSQL(String sql) { return null; }
        @Override public java.util.Map<String, Class<?>> getTypeMap() { return null; }
        @Override public void setTypeMap(java.util.Map<String, Class<?>> map) {}
        @Override public void setHoldability(int holdability) {}
        @Override public int getHoldability() { return 0; }
        @Override public java.sql.Savepoint setSavepoint() { return null; }
        @Override public java.sql.Savepoint setSavepoint(String name) { return null; }
        @Override public void rollback(java.sql.Savepoint savepoint) {}
        @Override public void releaseSavepoint(java.sql.Savepoint savepoint) {}
        @Override public Statement createStatement(int a, int b, int c) { return null; }
        @Override public PreparedStatement prepareStatement(String sql, int a, int b, int c) { return null; }
        @Override public java.sql.CallableStatement prepareCall(String sql, int a, int b, int c) { return null; }
        @Override public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) { return null; }
        @Override public PreparedStatement prepareStatement(String sql, int[] columnIndexes) { return null; }
        @Override public PreparedStatement prepareStatement(String sql, String[] columnNames) { return null; }
        @Override public java.sql.Clob createClob() { return null; }
        @Override public java.sql.Blob createBlob() { return null; }
        @Override public java.sql.NClob createNClob() { return null; }
        @Override public java.sql.SQLXML createSQLXML() { return null; }
        @Override public boolean isValid(int timeout) { return true; }
        @Override public void setClientInfo(String name, String value) {}
        @Override public void setClientInfo(java.util.Properties properties) {}
        @Override public String getClientInfo(String name) { return null; }
        @Override public java.util.Properties getClientInfo() { return null; }
        @Override public java.sql.Array createArrayOf(String typeName, Object[] elements) { return null; }
        @Override public java.sql.Struct createStruct(String typeName, Object[] attributes) { return null; }
        @Override public void setSchema(String schema) {}
        @Override public String getSchema() { return null; }
        @Override public void abort(java.util.concurrent.Executor executor) {}
        @Override public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) {}
        @Override public int getNetworkTimeout() { return 0; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    static class StubStatement implements Statement {
        private final StubDataSource dataSource;

        StubStatement(StubDataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public boolean execute(String sql) {
            dataSource.lastPreparedSql = sql;
            dataSource.wasExecuted = true;
            return false;
        }

        @Override public void close() {}
        @Override public int getMaxFieldSize() { return 0; }
        @Override public void setMaxFieldSize(int max) {}
        @Override public int getMaxRows() { return 0; }
        @Override public void setMaxRows(int max) {}
        @Override public void setEscapeProcessing(boolean enable) {}
        @Override public int getQueryTimeout() { return 0; }
        @Override public void setQueryTimeout(int seconds) {}
        @Override public void cancel() {}
        @Override public java.sql.SQLWarning getWarnings() { return null; }
        @Override public void clearWarnings() {}
        @Override public void setCursorName(String name) {}
        @Override
        public ResultSet executeQuery(String sql) {
            dataSource.lastPreparedSql = sql;
            dataSource.wasExecuted = true;
            return new StubResultSet(dataSource);
        }
        @Override public int executeUpdate(String sql) { return 0; }
        @Override public ResultSet getResultSet() { return null; }
        @Override public int getUpdateCount() { return 0; }
        @Override public boolean getMoreResults() { return false; }
        @Override public void setFetchDirection(int direction) {}
        @Override public int getFetchDirection() { return 0; }
        @Override public void setFetchSize(int rows) {}
        @Override public int getFetchSize() { return 0; }
        @Override public int getResultSetConcurrency() { return 0; }
        @Override public int getResultSetType() { return 0; }
        @Override public void addBatch(String sql) {}
        @Override public void clearBatch() {}
        @Override public int[] executeBatch() { return null; }
        @Override public Connection getConnection() { return null; }
        @Override public boolean getMoreResults(int current) { return false; }
        @Override public ResultSet getGeneratedKeys() { return null; }
        @Override public int executeUpdate(String sql, int autoGeneratedKeys) { return 0; }
        @Override public int executeUpdate(String sql, int[] columnIndexes) { return 0; }
        @Override public int executeUpdate(String sql, String[] columnNames) { return 0; }
        @Override public boolean execute(String sql, int autoGeneratedKeys) { return false; }
        @Override public boolean execute(String sql, int[] columnIndexes) { return false; }
        @Override public boolean execute(String sql, String[] columnNames) { return false; }
        @Override public int getResultSetHoldability() { return 0; }
        @Override public boolean isClosed() { return false; }
        @Override public void setPoolable(boolean poolable) {}
        @Override public boolean isPoolable() { return false; }
        @Override public void closeOnCompletion() {}
        @Override public boolean isCloseOnCompletion() { return false; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    static class StubPreparedStatement implements PreparedStatement {
        private final StubDataSource dataSource;

        StubPreparedStatement(StubDataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public ResultSet executeQuery() {
            dataSource.wasExecuted = true;
            return new StubResultSet(dataSource);
        }

        @Override public void setString(int parameterIndex, String x) {}
        @Override public void setInt(int parameterIndex, int x) {}
        @Override public void close() {}
        @Override public int executeUpdate() { return 0; }
        @Override public void setNull(int parameterIndex, int sqlType) {}
        @Override public void setBoolean(int parameterIndex, boolean x) {}
        @Override public void setByte(int parameterIndex, byte x) {}
        @Override public void setShort(int parameterIndex, short x) {}
        @Override public void setLong(int parameterIndex, long x) {}
        @Override public void setFloat(int parameterIndex, float x) {}
        @Override public void setDouble(int parameterIndex, double x) {}
        @Override public void setBigDecimal(int parameterIndex, java.math.BigDecimal x) {}
        @Override public void setBytes(int parameterIndex, byte[] x) {}
        @Override public void setDate(int parameterIndex, java.sql.Date x) {}
        @Override public void setTime(int parameterIndex, java.sql.Time x) {}
        @Override public void setTimestamp(int parameterIndex, java.sql.Timestamp x) {}
        @Override public void setAsciiStream(int parameterIndex, java.io.InputStream x, int length) {}
        @Override public void setUnicodeStream(int parameterIndex, java.io.InputStream x, int length) {}
        @Override public void setBinaryStream(int parameterIndex, java.io.InputStream x, int length) {}
        @Override public void clearParameters() {}
        @Override public void setObject(int parameterIndex, Object x, int targetSqlType) {}
        @Override public void setObject(int parameterIndex, Object x) {}
        @Override public boolean execute() { return false; }
        @Override public void addBatch() {}
        @Override public void setCharacterStream(int parameterIndex, java.io.Reader reader, int length) {}
        @Override public void setRef(int parameterIndex, java.sql.Ref x) {}
        @Override public void setBlob(int parameterIndex, java.sql.Blob x) {}
        @Override public void setClob(int parameterIndex, java.sql.Clob x) {}
        @Override public void setArray(int parameterIndex, java.sql.Array x) {}
        @Override public java.sql.ResultSetMetaData getMetaData() { return null; }
        @Override public void setDate(int parameterIndex, java.sql.Date x, java.util.Calendar cal) {}
        @Override public void setTime(int parameterIndex, java.sql.Time x, java.util.Calendar cal) {}
        @Override public void setTimestamp(int parameterIndex, java.sql.Timestamp x, java.util.Calendar cal) {}
        @Override public void setNull(int parameterIndex, int sqlType, String typeName) {}
        @Override public void setURL(int parameterIndex, java.net.URL x) {}
        @Override public java.sql.ParameterMetaData getParameterMetaData() { return null; }
        @Override public void setRowId(int parameterIndex, java.sql.RowId x) {}
        @Override public void setNString(int parameterIndex, String value) {}
        @Override public void setNCharacterStream(int parameterIndex, java.io.Reader value, long length) {}
        @Override public void setNClob(int parameterIndex, java.sql.NClob value) {}
        @Override public void setClob(int parameterIndex, java.io.Reader reader, long length) {}
        @Override public void setBlob(int parameterIndex, java.io.InputStream inputStream, long length) {}
        @Override public void setNClob(int parameterIndex, java.io.Reader reader, long length) {}
        @Override public void setSQLXML(int parameterIndex, java.sql.SQLXML xmlObject) {}
        @Override public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) {}
        @Override public void setAsciiStream(int parameterIndex, java.io.InputStream x, long length) {}
        @Override public void setBinaryStream(int parameterIndex, java.io.InputStream x, long length) {}
        @Override public void setCharacterStream(int parameterIndex, java.io.Reader reader, long length) {}
        @Override public void setAsciiStream(int parameterIndex, java.io.InputStream x) {}
        @Override public void setBinaryStream(int parameterIndex, java.io.InputStream x) {}
        @Override public void setCharacterStream(int parameterIndex, java.io.Reader reader) {}
        @Override public void setNCharacterStream(int parameterIndex, java.io.Reader value) {}
        @Override public void setClob(int parameterIndex, java.io.Reader reader) {}
        @Override public void setBlob(int parameterIndex, java.io.InputStream inputStream) {}
        @Override public void setNClob(int parameterIndex, java.io.Reader reader) {}
        @Override public ResultSet executeQuery(String sql) { return null; }
        @Override public int executeUpdate(String sql) { return 0; }
        @Override public int getMaxFieldSize() { return 0; }
        @Override public void setMaxFieldSize(int max) {}
        @Override public int getMaxRows() { return 0; }
        @Override public void setMaxRows(int max) {}
        @Override public void setEscapeProcessing(boolean enable) {}
        @Override public int getQueryTimeout() { return 0; }
        @Override public void setQueryTimeout(int seconds) {}
        @Override public void cancel() {}
        @Override public java.sql.SQLWarning getWarnings() { return null; }
        @Override public void clearWarnings() {}
        @Override public void setCursorName(String name) {}
        @Override public boolean execute(String sql) { return false; }
        @Override public ResultSet getResultSet() { return null; }
        @Override public int getUpdateCount() { return 0; }
        @Override public boolean getMoreResults() { return false; }
        @Override public void setFetchDirection(int direction) {}
        @Override public int getFetchDirection() { return 0; }
        @Override public void setFetchSize(int rows) {}
        @Override public int getFetchSize() { return 0; }
        @Override public int getResultSetConcurrency() { return 0; }
        @Override public int getResultSetType() { return 0; }
        @Override public void addBatch(String sql) {}
        @Override public void clearBatch() {}
        @Override public int[] executeBatch() { return null; }
        @Override public Connection getConnection() { return null; }
        @Override public boolean getMoreResults(int current) { return false; }
        @Override public ResultSet getGeneratedKeys() { return null; }
        @Override public int executeUpdate(String sql, int autoGeneratedKeys) { return 0; }
        @Override public int executeUpdate(String sql, int[] columnIndexes) { return 0; }
        @Override public int executeUpdate(String sql, String[] columnNames) { return 0; }
        @Override public boolean execute(String sql, int autoGeneratedKeys) { return false; }
        @Override public boolean execute(String sql, int[] columnIndexes) { return false; }
        @Override public boolean execute(String sql, String[] columnNames) { return false; }
        @Override public int getResultSetHoldability() { return 0; }
        @Override public boolean isClosed() { return false; }
        @Override public void setPoolable(boolean poolable) {}
        @Override public boolean isPoolable() { return false; }
        @Override public void closeOnCompletion() {}
        @Override public boolean isCloseOnCompletion() { return false; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    static class StubResultSet implements ResultSet {
        private final StubDataSource dataSource;
        private int currentRow = -1;

        // Column order: rankingScore, ecn, companyId, entityType, name, alternateName,
        //               taxIdNumber, taxIdType, birthDate, addressLine, cityName,
        //               state, postalCode, countryCode, customerType
        private static final int COL_RANKING_SCORE = 0;
        private static final int COL_ECN = 1;
        private static final int COL_COMPANY_ID = 2;
        private static final int COL_ENTITY_TYPE = 3;
        private static final int COL_NAME = 4;
        private static final int COL_ALTERNATE_NAME = 5;
        private static final int COL_TAX_ID_NUMBER = 6;
        private static final int COL_TAX_ID_TYPE = 7;
        private static final int COL_BIRTH_DATE = 8;
        private static final int COL_ADDRESS_LINE = 9;
        private static final int COL_CITY_NAME = 10;
        private static final int COL_STATE = 11;
        private static final int COL_POSTAL_CODE = 12;
        private static final int COL_COUNTRY_CODE = 13;
        private static final int COL_CUSTOMER_TYPE = 14;

        StubResultSet(StubDataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public boolean next() {
            currentRow++;
            return currentRow < dataSource.resultData.length;
        }

        @Override
        public String getString(String columnLabel) {
            if (currentRow >= 0 && currentRow < dataSource.resultData.length) {
                Object[] row = dataSource.resultData[currentRow];
                int col = switch (columnLabel) {
                    case "ecn" -> COL_ECN;
                    case "entity_type" -> COL_ENTITY_TYPE;
                    case "name" -> COL_NAME;
                    case "alternate_name" -> COL_ALTERNATE_NAME;
                    case "tax_id_number" -> COL_TAX_ID_NUMBER;
                    case "tax_id_type" -> COL_TAX_ID_TYPE;
                    case "birth_date" -> COL_BIRTH_DATE;
                    case "address_line" -> COL_ADDRESS_LINE;
                    case "city_name" -> COL_CITY_NAME;
                    case "state" -> COL_STATE;
                    case "postal_code" -> COL_POSTAL_CODE;
                    case "country_code" -> COL_COUNTRY_CODE;
                    case "customer_type" -> COL_CUSTOMER_TYPE;
                    default -> -1;
                };
                if (col >= 0 && col < row.length) {
                    Object value = row[col];
                    return value == null ? null : value.toString();
                }
            }
            return null;
        }

        @Override
        public int getInt(String columnLabel) {
            if (currentRow >= 0 && currentRow < dataSource.resultData.length) {
                Object[] row = dataSource.resultData[currentRow];
                int col = switch (columnLabel) {
                    case "ranking_score" -> COL_RANKING_SCORE;
                    case "company_id" -> COL_COMPANY_ID;
                    default -> -1;
                };
                if (col >= 0 && col < row.length && row[col] != null) {
                    return ((Number) row[col]).intValue();
                }
            }
            return 0;
        }

        @Override public void close() {}
        @Override public boolean wasNull() { return false; }
        @Override public String getString(int columnIndex) { return null; }
        @Override public boolean getBoolean(int columnIndex) { return false; }
        @Override public byte getByte(int columnIndex) { return 0; }
        @Override public short getShort(int columnIndex) { return 0; }
        @Override public int getInt(int columnIndex) { return 0; }
        @Override public long getLong(int columnIndex) { return 0; }
        @Override public float getFloat(int columnIndex) { return 0; }
        @Override public double getDouble(int columnIndex) { return 0; }
        @Override public java.math.BigDecimal getBigDecimal(int columnIndex, int scale) { return null; }
        @Override public byte[] getBytes(int columnIndex) { return null; }
        @Override public java.sql.Date getDate(int columnIndex) { return null; }
        @Override public java.sql.Time getTime(int columnIndex) { return null; }
        @Override public java.sql.Timestamp getTimestamp(int columnIndex) { return null; }
        @Override public java.io.InputStream getAsciiStream(int columnIndex) { return null; }
        @Override public java.io.InputStream getUnicodeStream(int columnIndex) { return null; }
        @Override public java.io.InputStream getBinaryStream(int columnIndex) { return null; }
        @Override public boolean getBoolean(String columnLabel) { return false; }
        @Override public byte getByte(String columnLabel) { return 0; }
        @Override public short getShort(String columnLabel) { return 0; }
        @Override public long getLong(String columnLabel) { return 0; }
        @Override public float getFloat(String columnLabel) { return 0; }
        @Override public double getDouble(String columnLabel) { return 0; }
        @Override public java.math.BigDecimal getBigDecimal(String columnLabel, int scale) { return null; }
        @Override public byte[] getBytes(String columnLabel) { return null; }
        @Override public java.sql.Date getDate(String columnLabel) { return null; }
        @Override public java.sql.Time getTime(String columnLabel) { return null; }
        @Override public java.sql.Timestamp getTimestamp(String columnLabel) { return null; }
        @Override public java.io.InputStream getAsciiStream(String columnLabel) { return null; }
        @Override public java.io.InputStream getUnicodeStream(String columnLabel) { return null; }
        @Override public java.io.InputStream getBinaryStream(String columnLabel) { return null; }
        @Override public java.sql.SQLWarning getWarnings() { return null; }
        @Override public void clearWarnings() {}
        @Override public String getCursorName() { return null; }
        @Override public java.sql.ResultSetMetaData getMetaData() { return null; }
        @Override public Object getObject(int columnIndex) { return null; }
        @Override public Object getObject(String columnLabel) { return null; }
        @Override public int findColumn(String columnLabel) { return 0; }
        @Override public java.io.Reader getCharacterStream(int columnIndex) { return null; }
        @Override public java.io.Reader getCharacterStream(String columnLabel) { return null; }
        @Override public java.math.BigDecimal getBigDecimal(int columnIndex) { return null; }
        @Override public java.math.BigDecimal getBigDecimal(String columnLabel) { return null; }
        @Override public boolean isBeforeFirst() { return false; }
        @Override public boolean isAfterLast() { return false; }
        @Override public boolean isFirst() { return false; }
        @Override public boolean isLast() { return false; }
        @Override public void beforeFirst() {}
        @Override public void afterLast() {}
        @Override public boolean first() { return false; }
        @Override public boolean last() { return false; }
        @Override public int getRow() { return 0; }
        @Override public boolean absolute(int row) { return false; }
        @Override public boolean relative(int rows) { return false; }
        @Override public boolean previous() { return false; }
        @Override public void setFetchDirection(int direction) {}
        @Override public int getFetchDirection() { return 0; }
        @Override public void setFetchSize(int rows) {}
        @Override public int getFetchSize() { return 0; }
        @Override public int getType() { return 0; }
        @Override public int getConcurrency() { return 0; }
        @Override public boolean rowUpdated() { return false; }
        @Override public boolean rowInserted() { return false; }
        @Override public boolean rowDeleted() { return false; }
        @Override public void updateNull(int columnIndex) {}
        @Override public void updateBoolean(int columnIndex, boolean x) {}
        @Override public void updateByte(int columnIndex, byte x) {}
        @Override public void updateShort(int columnIndex, short x) {}
        @Override public void updateInt(int columnIndex, int x) {}
        @Override public void updateLong(int columnIndex, long x) {}
        @Override public void updateFloat(int columnIndex, float x) {}
        @Override public void updateDouble(int columnIndex, double x) {}
        @Override public void updateBigDecimal(int columnIndex, java.math.BigDecimal x) {}
        @Override public void updateString(int columnIndex, String x) {}
        @Override public void updateBytes(int columnIndex, byte[] x) {}
        @Override public void updateDate(int columnIndex, java.sql.Date x) {}
        @Override public void updateTime(int columnIndex, java.sql.Time x) {}
        @Override public void updateTimestamp(int columnIndex, java.sql.Timestamp x) {}
        @Override public void updateAsciiStream(int columnIndex, java.io.InputStream x, int length) {}
        @Override public void updateBinaryStream(int columnIndex, java.io.InputStream x, int length) {}
        @Override public void updateCharacterStream(int columnIndex, java.io.Reader x, int length) {}
        @Override public void updateObject(int columnIndex, Object x, int scaleOrLength) {}
        @Override public void updateObject(int columnIndex, Object x) {}
        @Override public void updateNull(String columnLabel) {}
        @Override public void updateBoolean(String columnLabel, boolean x) {}
        @Override public void updateByte(String columnLabel, byte x) {}
        @Override public void updateShort(String columnLabel, short x) {}
        @Override public void updateInt(String columnLabel, int x) {}
        @Override public void updateLong(String columnLabel, long x) {}
        @Override public void updateFloat(String columnLabel, float x) {}
        @Override public void updateDouble(String columnLabel, double x) {}
        @Override public void updateBigDecimal(String columnLabel, java.math.BigDecimal x) {}
        @Override public void updateString(String columnLabel, String x) {}
        @Override public void updateBytes(String columnLabel, byte[] x) {}
        @Override public void updateDate(String columnLabel, java.sql.Date x) {}
        @Override public void updateTime(String columnLabel, java.sql.Time x) {}
        @Override public void updateTimestamp(String columnLabel, java.sql.Timestamp x) {}
        @Override public void updateAsciiStream(String columnLabel, java.io.InputStream x, int length) {}
        @Override public void updateBinaryStream(String columnLabel, java.io.InputStream x, int length) {}
        @Override public void updateCharacterStream(String columnLabel, java.io.Reader reader, int length) {}
        @Override public void updateObject(String columnLabel, Object x, int scaleOrLength) {}
        @Override public void updateObject(String columnLabel, Object x) {}
        @Override public void insertRow() {}
        @Override public void updateRow() {}
        @Override public void deleteRow() {}
        @Override public void refreshRow() {}
        @Override public void cancelRowUpdates() {}
        @Override public void moveToInsertRow() {}
        @Override public void moveToCurrentRow() {}
        @Override public Statement getStatement() { return null; }
        @Override public Object getObject(int columnIndex, java.util.Map<String, Class<?>> map) { return null; }
        @Override public java.sql.Ref getRef(int columnIndex) { return null; }
        @Override public java.sql.Blob getBlob(int columnIndex) { return null; }
        @Override public java.sql.Clob getClob(int columnIndex) { return null; }
        @Override public java.sql.Array getArray(int columnIndex) { return null; }
        @Override public Object getObject(String columnLabel, java.util.Map<String, Class<?>> map) { return null; }
        @Override public java.sql.Ref getRef(String columnLabel) { return null; }
        @Override public java.sql.Blob getBlob(String columnLabel) { return null; }
        @Override public java.sql.Clob getClob(String columnLabel) { return null; }
        @Override public java.sql.Array getArray(String columnLabel) { return null; }
        @Override public java.sql.Date getDate(int columnIndex, java.util.Calendar cal) { return null; }
        @Override public java.sql.Date getDate(String columnLabel, java.util.Calendar cal) { return null; }
        @Override public java.sql.Time getTime(int columnIndex, java.util.Calendar cal) { return null; }
        @Override public java.sql.Time getTime(String columnLabel, java.util.Calendar cal) { return null; }
        @Override public java.sql.Timestamp getTimestamp(int columnIndex, java.util.Calendar cal) { return null; }
        @Override public java.sql.Timestamp getTimestamp(String columnLabel, java.util.Calendar cal) { return null; }
        @Override public java.net.URL getURL(int columnIndex) { return null; }
        @Override public java.net.URL getURL(String columnLabel) { return null; }
        @Override public void updateRef(int columnIndex, java.sql.Ref x) {}
        @Override public void updateRef(String columnLabel, java.sql.Ref x) {}
        @Override public void updateBlob(int columnIndex, java.sql.Blob x) {}
        @Override public void updateBlob(String columnLabel, java.sql.Blob x) {}
        @Override public void updateClob(int columnIndex, java.sql.Clob x) {}
        @Override public void updateClob(String columnLabel, java.sql.Clob x) {}
        @Override public void updateArray(int columnIndex, java.sql.Array x) {}
        @Override public void updateArray(String columnLabel, java.sql.Array x) {}
        @Override public java.sql.RowId getRowId(int columnIndex) { return null; }
        @Override public java.sql.RowId getRowId(String columnLabel) { return null; }
        @Override public void updateRowId(int columnIndex, java.sql.RowId x) {}
        @Override public void updateRowId(String columnLabel, java.sql.RowId x) {}
        @Override public int getHoldability() { return 0; }
        @Override public boolean isClosed() { return false; }
        @Override public void updateNString(int columnIndex, String nString) {}
        @Override public void updateNString(String columnLabel, String nString) {}
        @Override public void updateNClob(int columnIndex, java.sql.NClob nClob) {}
        @Override public void updateNClob(String columnLabel, java.sql.NClob nClob) {}
        @Override public java.sql.NClob getNClob(int columnIndex) { return null; }
        @Override public java.sql.NClob getNClob(String columnLabel) { return null; }
        @Override public java.sql.SQLXML getSQLXML(int columnIndex) { return null; }
        @Override public java.sql.SQLXML getSQLXML(String columnLabel) { return null; }
        @Override public void updateSQLXML(int columnIndex, java.sql.SQLXML xmlObject) {}
        @Override public void updateSQLXML(String columnLabel, java.sql.SQLXML xmlObject) {}
        @Override public String getNString(int columnIndex) { return null; }
        @Override public String getNString(String columnLabel) { return null; }
        @Override public java.io.Reader getNCharacterStream(int columnIndex) { return null; }
        @Override public java.io.Reader getNCharacterStream(String columnLabel) { return null; }
        @Override public void updateNCharacterStream(int columnIndex, java.io.Reader x, long length) {}
        @Override public void updateNCharacterStream(String columnLabel, java.io.Reader reader, long length) {}
        @Override public void updateAsciiStream(int columnIndex, java.io.InputStream x, long length) {}
        @Override public void updateBinaryStream(int columnIndex, java.io.InputStream x, long length) {}
        @Override public void updateCharacterStream(int columnIndex, java.io.Reader x, long length) {}
        @Override public void updateAsciiStream(String columnLabel, java.io.InputStream x, long length) {}
        @Override public void updateBinaryStream(String columnLabel, java.io.InputStream x, long length) {}
        @Override public void updateCharacterStream(String columnLabel, java.io.Reader reader, long length) {}
        @Override public void updateBlob(int columnIndex, java.io.InputStream inputStream, long length) {}
        @Override public void updateBlob(String columnLabel, java.io.InputStream inputStream, long length) {}
        @Override public void updateClob(int columnIndex, java.io.Reader reader, long length) {}
        @Override public void updateClob(String columnLabel, java.io.Reader reader, long length) {}
        @Override public void updateNClob(int columnIndex, java.io.Reader reader, long length) {}
        @Override public void updateNClob(String columnLabel, java.io.Reader reader, long length) {}
        @Override public void updateNCharacterStream(int columnIndex, java.io.Reader x) {}
        @Override public void updateNCharacterStream(String columnLabel, java.io.Reader reader) {}
        @Override public void updateAsciiStream(int columnIndex, java.io.InputStream x) {}
        @Override public void updateBinaryStream(int columnIndex, java.io.InputStream x) {}
        @Override public void updateCharacterStream(int columnIndex, java.io.Reader x) {}
        @Override public void updateAsciiStream(String columnLabel, java.io.InputStream x) {}
        @Override public void updateBinaryStream(String columnLabel, java.io.InputStream x) {}
        @Override public void updateCharacterStream(String columnLabel, java.io.Reader reader) {}
        @Override public void updateBlob(int columnIndex, java.io.InputStream inputStream) {}
        @Override public void updateBlob(String columnLabel, java.io.InputStream inputStream) {}
        @Override public void updateClob(int columnIndex, java.io.Reader reader) {}
        @Override public void updateClob(String columnLabel, java.io.Reader reader) {}
        @Override public void updateNClob(int columnIndex, java.io.Reader reader) {}
        @Override public void updateNClob(String columnLabel, java.io.Reader reader) {}
        @Override public <T> T getObject(int columnIndex, Class<T> type) { return null; }
        @Override public <T> T getObject(String columnLabel, Class<T> type) { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
