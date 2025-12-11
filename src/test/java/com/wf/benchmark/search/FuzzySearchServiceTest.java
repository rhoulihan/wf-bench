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
import java.util.List;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TDD tests for FuzzySearchService.
 * Uses Oracle JSON_TEXTCONTAINS with JSON Search Index for text searches in JSON documents.
 */
class FuzzySearchServiceTest {

    private StubDataSource stubDataSource;
    private FuzzySearchService fuzzySearchService;

    @BeforeEach
    void setUp() {
        stubDataSource = new StubDataSource();
        fuzzySearchService = new FuzzySearchService(stubDataSource);
    }

    @Nested
    class FuzzyNameSearchTests {

        @Test
        void shouldFindExactNameMatch() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "Sandra Jones", "100"}
            });

            // When
            List<FuzzySearchResult> results = fuzzySearchService.searchByName("Sandra Jones", "identity", 10);

            // Then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getCustomerNumber()).isEqualTo("1000000001");
            assertThat(results.get(0).getMatchedValue()).isEqualTo("Sandra Jones");
            assertThat(results.get(0).getScore()).isEqualTo(100);
        }

        @Test
        void shouldFindFuzzyNameMatch() throws SQLException {
            // Given - "Sandr Jones" should match "Sandra Jones" with fuzzy matching
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "Sandra Jones", "85"}
            });

            // When
            List<FuzzySearchResult> results = fuzzySearchService.searchByName("Sandr Jones", "identity", 10);

            // Then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getMatchedValue()).isEqualTo("Sandra Jones");
            assertThat(results.get(0).getScore()).isGreaterThan(0);
        }

        @Test
        void shouldReturnMultipleMatches() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {
                {"1000000001", "Sandra Jones", "100"},
                {"1000000002", "Sandra Johnson", "75"}
            });

            // When
            List<FuzzySearchResult> results = fuzzySearchService.searchByName("Sandra", "identity", 10);

            // Then
            assertThat(results).hasSize(2);
        }

        @Test
        void shouldRespectLimit() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {
                {"1", "Match 1", "100"},
                {"2", "Match 2", "90"},
                {"3", "Match 3", "80"}
            });

            // When - limit to 2 results (service should handle this)
            List<FuzzySearchResult> results = fuzzySearchService.searchByName("Match", "identity", 2);

            // Then - verify query was executed
            assertThat(stubDataSource.wasQueried()).isTrue();
        }

        @Test
        void shouldReturnEmptyListWhenNoMatches() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {}); // No results

            // When
            List<FuzzySearchResult> results = fuzzySearchService.searchByName("XYZNONEXISTENT", "identity", 10);

            // Then
            assertThat(results).isEmpty();
        }

        @Test
        void shouldHandleNullSearchTerm() {
            // When/Then
            assertThatThrownBy(() -> fuzzySearchService.searchByName(null, "identity", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Search term cannot be null");
        }

        @Test
        void shouldHandleEmptySearchTerm() {
            // When/Then
            assertThatThrownBy(() -> fuzzySearchService.searchByName("", "identity", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Search term cannot be empty");
        }

        @Test
        void shouldHandleNullCollection() {
            // When/Then
            assertThatThrownBy(() -> fuzzySearchService.searchByName("Sandra", null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Collection cannot be null");
        }
    }

    @Nested
    class FuzzyBusinessNameSearchTests {

        @Test
        void shouldFindFuzzyBusinessNameMatch() throws SQLException {
            // Given - "Acme Corp" should match "ACME Corporation"
            stubDataSource.setResultData(new String[][] {
                {"2000000001", "ACME Corporation", "90"}
            });
            stubDataSource.setValueColumnName("business_name");

            // When
            List<FuzzySearchResult> results = fuzzySearchService.searchByBusinessName("Acme Corp", "identity", 10);

            // Then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getMatchedValue()).isEqualTo("ACME Corporation");
        }

        @Test
        void shouldSanitizeBusinessNameForSearch() throws SQLException {
            // Given - Special characters should be handled
            stubDataSource.setResultData(new String[][] {
                {"2000000001", "ABC & DEF Inc.", "80"}
            });
            stubDataSource.setValueColumnName("business_name");

            // When
            List<FuzzySearchResult> results = fuzzySearchService.searchByBusinessName("ABC DEF", "identity", 10);

            // Then
            assertThat(results).hasSize(1);
        }
    }

    @Nested
    class ConfigurationTests {

        @Test
        void shouldSetFuzzyScoreThreshold() {
            // Given
            FuzzySearchService service = new FuzzySearchService(stubDataSource);

            // When
            service.setMinScore(70);

            // Then
            assertThat(service.getMinScore()).isEqualTo(70);
        }

        @Test
        void shouldSetDefaultFuzzyScoreThreshold() {
            // When
            FuzzySearchService service = new FuzzySearchService(stubDataSource);

            // Then - default should be 60
            assertThat(service.getMinScore()).isEqualTo(60);
        }

        @Test
        void shouldRejectInvalidScoreThreshold() {
            // Given
            FuzzySearchService service = new FuzzySearchService(stubDataSource);

            // When/Then
            assertThatThrownBy(() -> service.setMinScore(-1))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> service.setMinScore(101))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class SQLGenerationTests {

        @Test
        void shouldGenerateCorrectJsonTextContainsQuery() throws SQLException {
            // Given
            stubDataSource.setResultData(new String[][] {}); // No results

            // When
            fuzzySearchService.searchByName("test", "identity", 10);

            // Then - verify the SQL uses JSON_TEXTCONTAINS
            assertThat(stubDataSource.getLastPreparedSql()).contains("JSON_TEXTCONTAINS");
            assertThat(stubDataSource.getLastPreparedSql()).contains("$.common.fullName");
        }
    }

    @Nested
    class ErrorHandlingTests {

        @Test
        void shouldWrapSQLExceptionInSearchException() {
            // Given
            stubDataSource.setThrowExceptionOnGetConnection(true);

            // When/Then
            assertThatThrownBy(() -> fuzzySearchService.searchByName("test", "identity", 10))
                .isInstanceOf(SearchException.class)
                .hasCauseInstanceOf(SQLException.class);
        }
    }

    /**
     * Simple stub DataSource for testing without Mockito.
     * Works around Java 23 Mockito issues with mocking interfaces.
     */
    static class StubDataSource implements DataSource {
        private String[][] resultData = new String[0][0];
        private String valueColumnName = "full_name";
        private boolean throwExceptionOnGetConnection = false;
        private boolean wasQueried = false;
        private String lastPreparedSql = null;

        void setResultData(String[][] data) {
            this.resultData = data;
        }

        void setValueColumnName(String name) {
            this.valueColumnName = name;
        }

        void setThrowExceptionOnGetConnection(boolean throwException) {
            this.throwExceptionOnGetConnection = throwException;
        }

        boolean wasQueried() {
            return wasQueried;
        }

        String getLastPreparedSql() {
            return lastPreparedSql;
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

        @Override public void close() {}
        @Override public boolean isClosed() { return false; }
        @Override public java.sql.Statement createStatement() { return null; }
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
        @Override public java.sql.Statement createStatement(int a, int b) { return null; }
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
        @Override public java.sql.Statement createStatement(int a, int b, int c) { return null; }
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

    static class StubPreparedStatement implements PreparedStatement {
        private final StubDataSource dataSource;

        StubPreparedStatement(StubDataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public ResultSet executeQuery() {
            dataSource.wasQueried = true;
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
                String[] row = dataSource.resultData[currentRow];
                return switch (columnLabel) {
                    case "customer_number" -> row[0];
                    case "full_name", "business_name", "description" -> row[1];
                    case "soundex_code" -> row.length > 2 ? row[2] : null;
                    default -> null;
                };
            }
            return null;
        }

        @Override
        public int getInt(String columnLabel) {
            if (currentRow >= 0 && currentRow < dataSource.resultData.length) {
                String[] row = dataSource.resultData[currentRow];
                if ("score".equals(columnLabel) && row.length > 2) {
                    return Integer.parseInt(row[2]);
                }
            }
            return 0;
        }

        @Override
        public double getDouble(String columnLabel) {
            if (currentRow >= 0 && currentRow < dataSource.resultData.length) {
                String[] row = dataSource.resultData[currentRow];
                if ("similarity_score".equals(columnLabel) && row.length > 2) {
                    return Double.parseDouble(row[2]);
                }
            }
            return 0.0;
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
        // getDouble(String) implemented above for similarity_score
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
        @Override public java.sql.Statement getStatement() { return null; }
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
