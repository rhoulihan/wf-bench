package com.wf.benchmark.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service for UC 1-7 search queries using Oracle Text SCORE() with full JSON search indexes.
 * Implements the use cases from the Wells Fargo RFP (PDF pages 12-18).
 *
 * <p>Each use case performs multi-collection searches with relevance scoring:
 * <ul>
 *   <li>UC-1: Phone + SSN Last 4</li>
 *   <li>UC-2: Phone + SSN Last 4 + Account Last 4</li>
 *   <li>UC-3: Phone + Account Last 4</li>
 *   <li>UC-4: Account Number + SSN Last 4</li>
 *   <li>UC-5: City/State/ZIP + SSN Last 4 + Account Last 4</li>
 *   <li>UC-6: Email + Account Last 4</li>
 *   <li>UC-7: Email + Phone + Account Number</li>
 * </ul>
 */
public class UcSearchService {

    private static final Logger log = LoggerFactory.getLogger(UcSearchService.class);

    private final DataSource dataSource;
    private final String collectionPrefix;

    /**
     * Creates a new UcSearchService with default (empty) collection prefix.
     */
    public UcSearchService(DataSource dataSource) {
        this(dataSource, "");
    }

    /**
     * Creates a new UcSearchService with the specified collection prefix.
     *
     * @param dataSource       the data source for database connections
     * @param collectionPrefix the prefix for collection names (e.g., "bench_")
     */
    public UcSearchService(DataSource dataSource, String collectionPrefix) {
        this.dataSource = dataSource;
        this.collectionPrefix = collectionPrefix != null ? collectionPrefix : "";
    }

    /**
     * UC-1: Search by Phone Number + SSN Last 4.
     * Joins phone and identity collections.
     */
    public List<UcSearchResult> searchUC1(String phoneNumber, String ssnLast4, int limit) {
        validatePhoneNumber(phoneNumber);
        validateSsnLast4(ssnLast4);
        validateLimit(limit);

        String sql = buildUC1Query();
        return executeQuery(sql, phoneNumber, ssnLast4, limit);
    }

    /**
     * UC-2: Search by Phone Number + SSN Last 4 + Account Last 4.
     * Joins phone, identity, and account collections.
     */
    public List<UcSearchResult> searchUC2(String phoneNumber, String ssnLast4, String accountLast4, int limit) {
        validatePhoneNumber(phoneNumber);
        validateSsnLast4(ssnLast4);
        validateAccountLast4(accountLast4);
        validateLimit(limit);

        String sql = buildUC2Query();
        return executeQueryWithAccountLast4(sql, phoneNumber, ssnLast4, accountLast4, limit);
    }

    /**
     * UC-3: Search by Phone Number + Account Last 4.
     * Joins phone, identity, and account collections.
     */
    public List<UcSearchResult> searchUC3(String phoneNumber, String accountLast4, int limit) {
        validatePhoneNumber(phoneNumber);
        validateAccountLast4(accountLast4);
        validateLimit(limit);

        String sql = buildUC3Query();
        return executeQueryPhoneAccountLast4(sql, phoneNumber, accountLast4, limit);
    }

    /**
     * UC-4: Search by Account Number + SSN Last 4.
     * Joins account and identity collections.
     */
    public List<UcSearchResult> searchUC4(String accountNumber, String ssnLast4, int limit) {
        validateAccountNumber(accountNumber);
        validateSsnLast4(ssnLast4);
        validateLimit(limit);

        String sql = buildUC4Query();
        return executeQuery(sql, accountNumber, ssnLast4, limit);
    }

    /**
     * UC-5: Search by City/State/ZIP + SSN Last 4 + Account Last 4.
     * Joins address, identity, and account collections.
     */
    public List<UcSearchResult> searchUC5(String city, String state, String zip, String ssnLast4, String accountLast4, int limit) {
        validateNotEmpty(city, "City");
        validateNotEmpty(state, "State");
        validateNotEmpty(zip, "ZIP");
        validateSsnLast4(ssnLast4);
        validateAccountLast4(accountLast4);
        validateLimit(limit);

        String sql = buildUC5Query();
        return executeQueryUC5(sql, city, state, zip, ssnLast4, accountLast4, limit);
    }

    /**
     * UC-6: Search by Email + Account Last 4.
     * Joins identity and account collections.
     */
    public List<UcSearchResult> searchUC6(String email, String accountLast4, int limit) {
        validateNotEmpty(email, "Email");
        validateAccountLast4(accountLast4);
        validateLimit(limit);

        String sql = buildUC6Query();
        return executeQueryEmailAccountLast4(sql, email, accountLast4, limit);
    }

    /**
     * UC-7: Search by Email + Phone + Account Number.
     * Joins identity, phone, and account collections.
     */
    public List<UcSearchResult> searchUC7(String email, String phoneNumber, String accountNumber, int limit) {
        validateNotEmpty(email, "Email");
        validatePhoneNumber(phoneNumber);
        validateAccountNumber(accountNumber);
        validateLimit(limit);

        String sql = buildUC7Query();
        return executeQueryUC7(sql, email, phoneNumber, accountNumber, limit);
    }

    /**
     * Creates full JSON search indexes for all collections.
     */
    public void createSearchIndexes(Connection conn) throws SQLException {
        List<String> statements = getCreateSearchIndexStatements();
        try (Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                try {
                    log.info("Creating search index: {}", sql);
                    stmt.execute(sql);
                } catch (SQLException e) {
                    if (e.getMessage().contains("already exists")) {
                        log.debug("Index already exists, skipping: {}", sql);
                    } else {
                        throw e;
                    }
                }
            }
        }
    }

    /**
     * Drops all search indexes for collections.
     */
    public void dropSearchIndexes(Connection conn) throws SQLException {
        List<String> statements = getDropSearchIndexStatements();
        try (Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                try {
                    log.info("Dropping search index: {}", sql);
                    stmt.execute(sql);
                } catch (SQLException e) {
                    if (e.getMessage().contains("does not exist")) {
                        log.debug("Index does not exist, skipping: {}", sql);
                    } else {
                        throw e;
                    }
                }
            }
        }
    }

    /**
     * Returns the SQL statements to create search indexes.
     */
    public List<String> getCreateSearchIndexStatements() {
        return List.of(
            "CREATE SEARCH INDEX idx_" + collectionPrefix + "identity_search ON " + collectionPrefix + "identity(DATA) FOR JSON",
            "CREATE SEARCH INDEX idx_" + collectionPrefix + "phone_search ON " + collectionPrefix + "phone(DATA) FOR JSON",
            "CREATE SEARCH INDEX idx_" + collectionPrefix + "account_search ON " + collectionPrefix + "account(DATA) FOR JSON",
            "CREATE SEARCH INDEX idx_" + collectionPrefix + "address_search ON " + collectionPrefix + "address(DATA) FOR JSON"
        );
    }

    /**
     * Returns the SQL statements to drop search indexes.
     */
    public List<String> getDropSearchIndexStatements() {
        return List.of(
            "DROP INDEX idx_" + collectionPrefix + "identity_search",
            "DROP INDEX idx_" + collectionPrefix + "phone_search",
            "DROP INDEX idx_" + collectionPrefix + "account_search",
            "DROP INDEX idx_" + collectionPrefix + "address_search"
        );
    }

    // ==================== DBMS_SEARCH Unified Index Methods ====================

    /**
     * Returns the name for the unified DBMS_SEARCH index.
     * The index spans all collections (identity, phone, account, address).
     */
    public String getUnifiedIndexName() {
        return "idx_" + collectionPrefix + "uc_unified";
    }

    /**
     * Returns the PL/SQL statement to create the unified DBMS_SEARCH index.
     * Uses DBMS_SEARCH.CREATE_INDEX to create a ubiquitous search index.
     */
    public String getCreateUnifiedIndexStatement() {
        return "BEGIN DBMS_SEARCH.CREATE_INDEX('" + getUnifiedIndexName() + "'); END;";
    }

    /**
     * Returns the PL/SQL statements to add sources (tables) to the unified index.
     * Uses DBMS_SEARCH.ADD_SOURCE to add each collection to the index.
     */
    public List<String> getAddSourceStatements() {
        return List.of(
            "BEGIN DBMS_SEARCH.ADD_SOURCE('" + getUnifiedIndexName() + "', '" + collectionPrefix + "identity'); END;",
            "BEGIN DBMS_SEARCH.ADD_SOURCE('" + getUnifiedIndexName() + "', '" + collectionPrefix + "phone'); END;",
            "BEGIN DBMS_SEARCH.ADD_SOURCE('" + getUnifiedIndexName() + "', '" + collectionPrefix + "account'); END;",
            "BEGIN DBMS_SEARCH.ADD_SOURCE('" + getUnifiedIndexName() + "', '" + collectionPrefix + "address'); END;"
        );
    }

    // ==================== View-Based Unified Index Methods ====================
    // These views contain only the fields needed for UC 1-7 queries, reducing index size
    // and improving search accuracy by limiting indexed content.

    /**
     * Returns the view name prefix for UC unified index views.
     */
    public String getUcViewPrefix() {
        return "v_" + collectionPrefix + "uc_";
    }

    /**
     * Returns SQL to create the identity UC view with only required fields.
     * Fields: customerNumber (PK), taxIdentificationNumberLast4, fullName, entityTypeIndicator, email
     */
    public String getCreateIdentityUcViewSql() {
        return """
            CREATE OR REPLACE VIEW %sidentity AS
            SELECT
                json_value(DATA, '$._id.customerNumber') as customer_number,
                json_value(DATA, '$.common.taxIdentificationNumberLast4') as ssn_last4,
                json_value(DATA, '$.common.fullName') as full_name,
                json_value(DATA, '$.common.entityTypeIndicator') as entity_type,
                json_value(DATA, '$.emails[0].emailAddress') as email
            FROM %sidentity
            """.formatted(getUcViewPrefix(), collectionPrefix);
    }

    /**
     * Returns SQL to create the phone UC view with only required fields.
     * Fields: customerNumber, phoneNumber
     */
    public String getCreatePhoneUcViewSql() {
        return """
            CREATE OR REPLACE VIEW %sphone AS
            SELECT
                json_value(DATA, '$.phoneKey.customerNumber') as customer_number,
                json_value(DATA, '$.phoneKey.phoneNumber') as phone_number
            FROM %sphone
            """.formatted(getUcViewPrefix(), collectionPrefix);
    }

    /**
     * Returns SQL to create the account UC view with only required fields.
     * Fields: customerNumber, accountNumber, accountNumberLast4
     */
    public String getCreateAccountUcViewSql() {
        return """
            CREATE OR REPLACE VIEW %saccount AS
            SELECT
                json_value(DATA, '$.accountHolders[0].customerNumber') as customer_number,
                json_value(DATA, '$.accountKey.accountNumber') as account_number,
                json_value(DATA, '$.accountKey.accountNumberLast4') as account_last4
            FROM %saccount
            """.formatted(getUcViewPrefix(), collectionPrefix);
    }

    /**
     * Returns SQL to create the address UC view with only required fields.
     * Fields: customerNumber, cityName, stateCode, postalCode
     */
    public String getCreateAddressUcViewSql() {
        return """
            CREATE OR REPLACE VIEW %saddress AS
            SELECT
                json_value(DATA, '$._id.customerNumber') as customer_number,
                json_value(DATA, '$.cityName') as city,
                json_value(DATA, '$.stateCode') as state,
                json_value(DATA, '$.postalCode') as zip
            FROM %saddress
            """.formatted(getUcViewPrefix(), collectionPrefix);
    }

    /**
     * Returns all view creation SQL statements.
     */
    public List<String> getCreateUcViewStatements() {
        return List.of(
            getCreateIdentityUcViewSql(),
            getCreatePhoneUcViewSql(),
            getCreateAccountUcViewSql(),
            getCreateAddressUcViewSql()
        );
    }

    /**
     * Returns SQL statements to drop the UC views.
     */
    public List<String> getDropUcViewStatements() {
        return List.of(
            "DROP VIEW " + getUcViewPrefix() + "identity",
            "DROP VIEW " + getUcViewPrefix() + "phone",
            "DROP VIEW " + getUcViewPrefix() + "account",
            "DROP VIEW " + getUcViewPrefix() + "address"
        );
    }

    /**
     * Returns the PL/SQL statements to add UC views to the unified index.
     * These views contain only the fields needed for UC 1-7 queries.
     */
    public List<String> getAddUcViewSourceStatements() {
        return List.of(
            "BEGIN DBMS_SEARCH.ADD_SOURCE('" + getUnifiedIndexName() + "', '" + getUcViewPrefix() + "identity'); END;",
            "BEGIN DBMS_SEARCH.ADD_SOURCE('" + getUnifiedIndexName() + "', '" + getUcViewPrefix() + "phone'); END;",
            "BEGIN DBMS_SEARCH.ADD_SOURCE('" + getUnifiedIndexName() + "', '" + getUcViewPrefix() + "account'); END;",
            "BEGIN DBMS_SEARCH.ADD_SOURCE('" + getUnifiedIndexName() + "', '" + getUcViewPrefix() + "address'); END;"
        );
    }

    /**
     * Creates the UC views containing only the fields needed for UC 1-7 queries.
     *
     * @param conn the database connection
     * @throws SQLException if view creation fails
     */
    public void createUcViews(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            for (String sql : getCreateUcViewStatements()) {
                log.info("Creating UC view: {}", sql.substring(0, Math.min(sql.length(), 80)) + "...");
                stmt.execute(sql);
            }
            log.info("Successfully created UC views with prefix: {}", getUcViewPrefix());
        }
    }

    /**
     * Drops the UC views.
     *
     * @param conn the database connection
     * @throws SQLException if view drop fails
     */
    public void dropUcViews(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            for (String sql : getDropUcViewStatements()) {
                try {
                    log.info("Dropping UC view: {}", sql);
                    stmt.execute(sql);
                } catch (SQLException e) {
                    if (e.getMessage().contains("does not exist") || e.getErrorCode() == 942) {
                        log.debug("View does not exist, skipping: {}", sql);
                    } else {
                        throw e;
                    }
                }
            }
        }
    }

    /**
     * Creates the unified DBMS_SEARCH index using UC views (specific fields only).
     * This creates a smaller, more focused index for UC 1-7 queries.
     *
     * @param conn the database connection
     * @throws SQLException if index creation fails
     */
    public void createUnifiedIndexWithViews(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // First create the views
            log.info("Creating UC views for unified index...");
            createUcViews(conn);

            // Then create the index
            log.info("Creating unified DBMS_SEARCH index: {}", getUnifiedIndexName());
            stmt.execute(getCreateUnifiedIndexStatement());

            // Add the UC views as sources (not the full tables)
            for (String addSourceSql : getAddUcViewSourceStatements()) {
                log.info("Adding UC view source to unified index: {}", addSourceSql);
                stmt.execute(addSourceSql);
            }

            log.info("Successfully created unified DBMS_SEARCH index with UC views: {}", getUnifiedIndexName());
        } catch (SQLException e) {
            if (e.getMessage().contains("already exists") || e.getMessage().contains("ORA-20000")) {
                log.debug("Unified index already exists: {}", getUnifiedIndexName());
            } else {
                throw e;
            }
        }
    }

    /**
     * Drops the unified DBMS_SEARCH index and its UC views.
     *
     * @param conn the database connection
     * @throws SQLException if drop fails
     */
    public void dropUnifiedIndexWithViews(Connection conn) throws SQLException {
        // First drop the index (releases the views)
        dropUnifiedIndex(conn);
        // Then drop the views
        dropUcViews(conn);
    }

    /**
     * Returns the PL/SQL statement to drop the unified DBMS_SEARCH index.
     */
    public String getDropUnifiedIndexStatement() {
        return "BEGIN DBMS_SEARCH.DROP_INDEX('" + getUnifiedIndexName() + "'); END;";
    }

    /**
     * Builds a query using DBMS_SEARCH.FIND to search across all collections.
     *
     * @param searchTerm the search term to find
     * @param limit maximum number of results
     * @return the SQL query string
     */
    public String buildUnifiedFindQuery(String searchTerm, int limit) {
        return """
            SELECT * FROM DBMS_SEARCH.FIND('%s', '%s')
            FETCH FIRST %d ROWS ONLY
            """.formatted(getUnifiedIndexName(), searchTerm, limit);
    }

    /**
     * Creates the unified DBMS_SEARCH index across all collections.
     * This creates a single index that can search identity, phone, account, and address.
     *
     * @param conn the database connection
     * @throws SQLException if index creation fails
     */
    public void createUnifiedIndex(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // First create the index
            log.info("Creating unified DBMS_SEARCH index: {}", getUnifiedIndexName());
            stmt.execute(getCreateUnifiedIndexStatement());

            // Then add all source tables
            for (String addSourceSql : getAddSourceStatements()) {
                log.info("Adding source to unified index: {}", addSourceSql);
                stmt.execute(addSourceSql);
            }

            log.info("Successfully created unified DBMS_SEARCH index: {}", getUnifiedIndexName());
        } catch (SQLException e) {
            if (e.getMessage().contains("already exists") || e.getMessage().contains("ORA-20000")) {
                log.debug("Unified index already exists: {}", getUnifiedIndexName());
            } else {
                throw e;
            }
        }
    }

    /**
     * Drops the unified DBMS_SEARCH index.
     *
     * @param conn the database connection
     * @throws SQLException if index drop fails
     */
    public void dropUnifiedIndex(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            log.info("Dropping unified DBMS_SEARCH index: {}", getUnifiedIndexName());
            stmt.execute(getDropUnifiedIndexStatement());
        } catch (SQLException e) {
            if (e.getMessage().contains("does not exist") || e.getMessage().contains("ORA-20000")) {
                log.debug("Unified index does not exist: {}", getUnifiedIndexName());
            } else {
                throw e;
            }
        }
    }

    // ==================== New Unified DBMS_SEARCH Algorithm ====================
    //
    // Algorithm:
    // 1. Query unified index with fuzzy OR search for all terms
    // 2. Group hits by customerNumber
    // 3. Filter: only keep customers with matches in ALL required categories
    // 4. Calculate average score per customer
    // 5. Fetch identity/address details for qualifying customers
    // 6. Return UcSearchResult with computed score
    //

    /** Field to category mapping for parsing search hits */
    private static final Map<String, SearchCategory> FIELD_TO_CATEGORY = Map.of(
        "phone_number", SearchCategory.PHONE,
        "ssn_last4", SearchCategory.SSN_LAST4,
        "account_number", SearchCategory.ACCOUNT_NUMBER,
        "account_last4", SearchCategory.ACCOUNT_LAST4,
        "email", SearchCategory.EMAIL,
        "city", SearchCategory.CITY,
        "state", SearchCategory.STATE,
        "zip", SearchCategory.ZIP
    );

    /**
     * Escapes special characters in search terms for Oracle Text.
     */
    private String escapeForOracle(String term) {
        if (term == null) return "";
        return term.replace("'", "''").replace("\"", "\\\"");
    }

    /**
     * Builds a fuzzy OR query for DBMS_SEARCH.FIND.
     * Uses JSON QBE syntax with fuzzy matching on all terms.
     */
    public String buildFuzzyOrQuery(List<String> searchTerms, int limit) {
        // Build fuzzy search with OR between terms
        String fuzzyTerms = searchTerms.stream()
            .filter(t -> t != null && !t.isBlank())
            .map(t -> "fuzzy(" + escapeForOracle(t) + ")")
            .reduce((a, b) -> a + " OR " + b)
            .orElse("");

        return String.format(
            "SELECT DBMS_SEARCH.FIND('%s', JSON('{\"$query\":\"%s\",\"$search\":{\"limit\":%d}}')) AS RESULT FROM DUAL",
            getUnifiedIndexName(), fuzzyTerms, limit * 10 // Get more hits to ensure we find matching sets
        );
    }

    /**
     * UC-1 Unified Search: Phone + SSN Last 4 using new algorithm.
     */
    public List<UcSearchResult> searchUnifiedUC1(String phoneNumber, String ssnLast4, int limit) {
        validatePhoneNumber(phoneNumber);
        validateSsnLast4(ssnLast4);
        validateLimit(limit);

        List<String> searchTerms = List.of(phoneNumber, ssnLast4);
        return executeUnifiedSearch(searchTerms, SearchCategory.UC1_CATEGORIES, limit);
    }

    /**
     * UC-2 Unified Search: Phone + SSN Last 4 + Account Last 4.
     */
    public List<UcSearchResult> searchUnifiedUC2(String phoneNumber, String ssnLast4, String accountLast4, int limit) {
        validatePhoneNumber(phoneNumber);
        validateSsnLast4(ssnLast4);
        validateAccountLast4(accountLast4);
        validateLimit(limit);

        List<String> searchTerms = List.of(phoneNumber, ssnLast4, accountLast4);
        return executeUnifiedSearch(searchTerms, SearchCategory.UC2_CATEGORIES, limit);
    }

    /**
     * UC-3 Unified Search: Phone + Account Last 4.
     */
    public List<UcSearchResult> searchUnifiedUC3(String phoneNumber, String accountLast4, int limit) {
        validatePhoneNumber(phoneNumber);
        validateAccountLast4(accountLast4);
        validateLimit(limit);

        List<String> searchTerms = List.of(phoneNumber, accountLast4);
        return executeUnifiedSearch(searchTerms, SearchCategory.UC3_CATEGORIES, limit);
    }

    /**
     * UC-4 Unified Search: Account Number + SSN Last 4.
     */
    public List<UcSearchResult> searchUnifiedUC4(String accountNumber, String ssnLast4, int limit) {
        validateAccountNumber(accountNumber);
        validateSsnLast4(ssnLast4);
        validateLimit(limit);

        List<String> searchTerms = List.of(accountNumber, ssnLast4);
        return executeUnifiedSearch(searchTerms, SearchCategory.UC4_CATEGORIES, limit);
    }

    /**
     * UC-5 Unified Search: City/State/ZIP + SSN Last 4 + Account Last 4.
     */
    public List<UcSearchResult> searchUnifiedUC5(String city, String state, String zip, String ssnLast4, String accountLast4, int limit) {
        validateNotEmpty(city, "City");
        validateNotEmpty(state, "State");
        validateNotEmpty(zip, "ZIP");
        validateSsnLast4(ssnLast4);
        validateAccountLast4(accountLast4);
        validateLimit(limit);

        List<String> searchTerms = List.of(city, state, zip, ssnLast4, accountLast4);
        return executeUnifiedSearch(searchTerms, SearchCategory.UC5_CATEGORIES, limit);
    }

    /**
     * UC-6 Unified Search: Email + Account Last 4.
     */
    public List<UcSearchResult> searchUnifiedUC6(String email, String accountLast4, int limit) {
        validateNotEmpty(email, "Email");
        validateAccountLast4(accountLast4);
        validateLimit(limit);

        List<String> searchTerms = List.of(email, accountLast4);
        return executeUnifiedSearch(searchTerms, SearchCategory.UC6_CATEGORIES, limit);
    }

    /**
     * UC-7 Unified Search: Email + Phone + Account Number.
     */
    public List<UcSearchResult> searchUnifiedUC7(String email, String phoneNumber, String accountNumber, int limit) {
        validateNotEmpty(email, "Email");
        validatePhoneNumber(phoneNumber);
        validateAccountNumber(accountNumber);
        validateLimit(limit);

        List<String> searchTerms = List.of(email, phoneNumber, accountNumber);
        return executeUnifiedSearch(searchTerms, SearchCategory.UC7_CATEGORIES, limit);
    }

    /**
     * Executes the unified search algorithm.
     *
     * @param searchTerms        the search terms for fuzzy OR query
     * @param requiredCategories the categories that must all match
     * @param limit              maximum results to return
     * @return list of UcSearchResult for customers matching all categories
     */
    private List<UcSearchResult> executeUnifiedSearch(List<String> searchTerms,
                                                       Set<SearchCategory> requiredCategories,
                                                       int limit) {
        String sql = buildFuzzyOrQuery(searchTerms, limit);
        log.debug("Executing unified search: {}", sql);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            // Step 1: Get raw hits from DBMS_SEARCH.FIND
            List<SearchHit> hits = parseDbmsSearchHits(rs);
            log.debug("DBMS_SEARCH.FIND returned {} raw hits", hits.size());

            if (hits.isEmpty()) {
                return List.of();
            }

            // Step 2: Group by customer number
            Map<String, CustomerHitGroup> groups = CustomerHitGroup.groupByCustomer(hits, FIELD_TO_CATEGORY);
            log.debug("Grouped into {} customer groups", groups.size());

            // Step 3: Sort by average score (with 0 for missing categories)
            // This includes ALL customers with at least one hit, ranked by score
            // Full matches rank higher than partial matches
            List<CustomerHitGroup> qualifyingGroups = CustomerHitGroup.sortByScore(
                groups, requiredCategories, limit
            );
            log.debug("{} customers returned (ranked by score with 0 for missing categories)", qualifyingGroups.size());

            if (qualifyingGroups.isEmpty()) {
                return List.of();
            }

            // Step 4: Fetch identity/address details for qualifying customers
            return fetchCustomerDetails(conn, qualifyingGroups);

        } catch (SQLException e) {
            log.error("Failed to execute unified search. SQL: {}", sql);
            log.error("SQL Error: {}", e.getMessage());
            throw new SearchException("Failed to execute unified search: " + e.getMessage(), e);
        }
    }

    /**
     * Parses DBMS_SEARCH.FIND JSON result into SearchHit records.
     * DBMS_SEARCH.FIND returns JSON with structure: {"$count": n, "$hit": [...]}
     * Each hit contains source table name and row data with customer_number.
     */
    private List<SearchHit> parseDbmsSearchHits(ResultSet rs) throws SQLException {
        List<SearchHit> hits = new ArrayList<>();

        if (!rs.next()) {
            return hits;
        }

        String jsonResult = rs.getString(1);
        if (jsonResult == null || jsonResult.isEmpty()) {
            return hits;
        }

        log.debug("Parsing DBMS_SEARCH.FIND JSON result (length={})", jsonResult.length());

        // Parse the JSON using simple string parsing
        // Expected format: {"$count":N,"$hit":[{"$source":"table","$data":{...},"$score":N},...]}
        try {
            int hitArrayIdx = jsonResult.indexOf("\"$hit\"");
            if (hitArrayIdx == -1) {
                log.debug("No $hit array found in result");
                return hits;
            }

            int bracketIdx = jsonResult.indexOf("[", hitArrayIdx);
            if (bracketIdx == -1) {
                return hits;
            }

            // Find matching closing bracket
            int depth = 0;
            int hitStart = -1;
            for (int i = bracketIdx; i < jsonResult.length(); i++) {
                char c = jsonResult.charAt(i);
                if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) break;
                }
                else if (c == '{' && depth == 1) {
                    hitStart = i;
                }
                else if (c == '}' && depth == 1 && hitStart != -1) {
                    // Parse this hit object
                    String hitJson = jsonResult.substring(hitStart, i + 1);
                    SearchHit hit = parseHitObject(hitJson);
                    if (hit != null) {
                        hits.add(hit);
                    }
                    hitStart = -1;
                }
            }
        } catch (Exception e) {
            log.warn("Error parsing DBMS_SEARCH.FIND JSON: {}", e.getMessage());
        }

        return hits;
    }

    /**
     * Parses a single hit object from the $hit array.
     * Format: {"$source":"table","$data":{...},"$score":N}
     */
    private SearchHit parseHitObject(String hitJson) {
        try {
            // Extract $source (table name)
            String source = extractJsonString(hitJson, "$source");
            if (source == null) return null;

            // Extract $score
            double score = extractJsonNumber(hitJson, "$score");

            // Extract customer_number from $data
            String customerNumber = extractNestedJsonString(hitJson, "$data", "CUSTOMER_NUMBER");
            if (customerNumber == null) {
                customerNumber = extractNestedJsonString(hitJson, "$data", "customer_number");
            }
            if (customerNumber == null) return null;

            // Determine matched field based on source table
            String matchedField = determineMatchedField(source, hitJson);
            String matchedValue = extractMatchedValue(hitJson, matchedField);

            return new SearchHit(source, customerNumber, matchedField, matchedValue, score);
        } catch (Exception e) {
            log.trace("Failed to parse hit object: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts a string value from JSON.
     */
    private String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;

        int colonIdx = json.indexOf(":", idx);
        if (colonIdx == -1) return null;

        int quoteStart = json.indexOf("\"", colonIdx);
        if (quoteStart == -1) return null;

        int quoteEnd = json.indexOf("\"", quoteStart + 1);
        if (quoteEnd == -1) return null;

        return json.substring(quoteStart + 1, quoteEnd);
    }

    /**
     * Extracts a number value from JSON.
     */
    private double extractJsonNumber(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return 0.0;

        int colonIdx = json.indexOf(":", idx);
        if (colonIdx == -1) return 0.0;

        int numStart = colonIdx + 1;
        while (numStart < json.length() && Character.isWhitespace(json.charAt(numStart))) {
            numStart++;
        }

        int numEnd = numStart;
        while (numEnd < json.length() && (Character.isDigit(json.charAt(numEnd)) || json.charAt(numEnd) == '.')) {
            numEnd++;
        }

        if (numEnd > numStart) {
            try {
                return Double.parseDouble(json.substring(numStart, numEnd));
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    /**
     * Extracts a nested string value from JSON.
     */
    private String extractNestedJsonString(String json, String parentKey, String childKey) {
        String pattern = "\"" + parentKey + "\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;

        int braceIdx = json.indexOf("{", idx);
        if (braceIdx == -1) return null;

        // Find matching closing brace
        int depth = 0;
        int dataEnd = -1;
        for (int i = braceIdx; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    dataEnd = i + 1;
                    break;
                }
            }
        }

        if (dataEnd == -1) return null;
        String dataJson = json.substring(braceIdx, dataEnd);
        return extractJsonString(dataJson, childKey);
    }

    /**
     * Determines the matched field based on source table.
     */
    private String determineMatchedField(String source, String hitJson) {
        return switch (source.toLowerCase().replace(collectionPrefix.toLowerCase(), "")
                            .replace(getUcViewPrefix().toLowerCase(), "")) {
            case "phone" -> "phone_number";
            case "identity" -> {
                // Check if email or ssn_last4 is present
                if (extractNestedJsonString(hitJson, "$data", "EMAIL") != null ||
                    extractNestedJsonString(hitJson, "$data", "email") != null) {
                    yield "email";
                }
                yield "ssn_last4";
            }
            case "account" -> {
                // Check if full account number or last 4
                if (extractNestedJsonString(hitJson, "$data", "ACCOUNT_NUMBER") != null ||
                    extractNestedJsonString(hitJson, "$data", "account_number") != null) {
                    yield "account_number";
                }
                yield "account_last4";
            }
            case "address" -> "city"; // Default to city, could be state or zip
            default -> "unknown";
        };
    }

    /**
     * Extracts the matched value for the given field.
     */
    private String extractMatchedValue(String hitJson, String matchedField) {
        String upperField = matchedField.toUpperCase();
        String value = extractNestedJsonString(hitJson, "$data", upperField);
        if (value == null) {
            value = extractNestedJsonString(hitJson, "$data", matchedField);
        }
        return value != null ? value : "";
    }

    /**
     * Fetches identity and address details for qualifying customers.
     */
    private List<UcSearchResult> fetchCustomerDetails(Connection conn, List<CustomerHitGroup> groups)
            throws SQLException {
        List<UcSearchResult> results = new ArrayList<>();

        for (CustomerHitGroup group : groups) {
            UcSearchResult result = fetchCustomerResult(conn, group);
            if (result != null) {
                results.add(result);
            }
        }

        return results;
    }

    /**
     * Fetches identity and address details for a single customer.
     */
    private UcSearchResult fetchCustomerResult(Connection conn, CustomerHitGroup group) throws SQLException {
        String sql = """
            SELECT
                json_value(i.DATA, '$._id.customerNumber') as ecn,
                NVL(json_value(i.DATA, '$._id.customerCompanyNumber' RETURNING NUMBER), 1) as company_id,
                json_value(i.DATA, '$.common.entityTypeIndicator') as entity_type,
                json_value(i.DATA, '$.common.fullName') as name,
                CASE
                    WHEN json_value(i.DATA, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
                    THEN json_value(i.DATA, '$.individual.firstName')
                    ELSE json_value(i.DATA, '$.nonIndividual.businessDescriptionText')
                END as alternate_name,
                json_value(i.DATA, '$.common.taxIdentificationNumber') as tax_id_number,
                json_value(i.DATA, '$.common.taxIdentificationType') as tax_id_type,
                json_value(i.DATA, '$.individual.dateOfBirth') as birth_date,
                json_value(a.DATA, '$.addressLine1') as address_line,
                json_value(a.DATA, '$.city') as city_name,
                json_value(a.DATA, '$.state') as state,
                json_value(a.DATA, '$.postalCode') as postal_code,
                NVL(json_value(a.DATA, '$.countryCode'), 'US') as country_code,
                json_value(i.DATA, '$.common.customerType') as customer_type
            FROM %sidentity i
            LEFT JOIN %saddress a ON json_value(a.DATA, '$._id.customerNumber') =
                                     json_value(i.DATA, '$._id.customerNumber')
            WHERE json_value(i.DATA, '$._id.customerNumber') = ?
            FETCH FIRST 1 ROW ONLY
            """.formatted(collectionPrefix, collectionPrefix);

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, group.getCustomerNumber());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return UcSearchResult.builder()
                        .rankingScore((int) Math.round(group.getAverageScore()))
                        .ecn(rs.getString("ecn"))
                        .companyId(rs.getInt("company_id"))
                        .entityType(rs.getString("entity_type"))
                        .name(rs.getString("name"))
                        .alternateName(rs.getString("alternate_name"))
                        .taxIdNumber(rs.getString("tax_id_number"))
                        .taxIdType(rs.getString("tax_id_type"))
                        .birthDate(rs.getString("birth_date"))
                        .addressLine(rs.getString("address_line"))
                        .cityName(rs.getString("city_name"))
                        .state(rs.getString("state"))
                        .postalCode(rs.getString("postal_code"))
                        .countryCode(rs.getString("country_code"))
                        .customerType(rs.getString("customer_type"))
                        .build();
                }
            }
        }

        // If we couldn't fetch details, create a basic result from the group
        return UcSearchResult.builder()
            .rankingScore((int) Math.round(group.getAverageScore()))
            .ecn(group.getCustomerNumber())
            .companyId(1)
            .entityType("UNKNOWN")
            .name("Customer " + group.getCustomerNumber())
            .countryCode("US")
            .build();
    }

    // SQL Query Builders

    private String buildUC1Query() {
        return """
            SELECT
                SCORE(1) as ranking_score,
                json_value(i.DATA, '$._id.customerNumber') as ecn,
                NVL(json_value(i.DATA, '$._id.customerCompanyNumber' RETURNING NUMBER), 1) as company_id,
                json_value(i.DATA, '$.common.entityTypeIndicator') as entity_type,
                json_value(i.DATA, '$.common.fullName') as name,
                CASE
                    WHEN json_value(i.DATA, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
                    THEN json_value(i.DATA, '$.individual.firstName')
                    ELSE json_value(i.DATA, '$.nonIndividual.businessDescriptionText')
                END as alternate_name,
                json_value(i.DATA, '$.common.taxIdentificationNumber') as tax_id_number,
                json_value(i.DATA, '$.common.taxIdentificationType') as tax_id_type,
                json_value(i.DATA, '$.individual.dateOfBirth') as birth_date,
                json_value(a.DATA, '$.addressLine1') as address_line,
                json_value(a.DATA, '$.city') as city_name,
                json_value(a.DATA, '$.state') as state,
                json_value(a.DATA, '$.postalCode') as postal_code,
                NVL(json_value(a.DATA, '$.countryCode'), 'US') as country_code,
                json_value(i.DATA, '$.common.customerType') as customer_type
            FROM %sphone p
            JOIN %sidentity i ON json_value(p.DATA, '$.phoneKey.customerNumber') =
                                 json_value(i.DATA, '$._id.customerNumber')
            LEFT JOIN %saddress a ON json_value(a.DATA, '$._id.customerNumber') =
                                     json_value(i.DATA, '$._id.customerNumber')
            WHERE CONTAINS(p.DATA, ?, 1) > 0
              AND json_value(i.DATA, '$.common.taxIdentificationNumberLast4') = ?
            ORDER BY SCORE(1) DESC
            FETCH FIRST ? ROWS ONLY
            """.formatted(collectionPrefix, collectionPrefix, collectionPrefix);
    }

    private String buildUC2Query() {
        return """
            SELECT
                SCORE(1) as ranking_score,
                json_value(i.DATA, '$._id.customerNumber') as ecn,
                NVL(json_value(i.DATA, '$._id.customerCompanyNumber' RETURNING NUMBER), 1) as company_id,
                json_value(i.DATA, '$.common.entityTypeIndicator') as entity_type,
                json_value(i.DATA, '$.common.fullName') as name,
                CASE
                    WHEN json_value(i.DATA, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
                    THEN json_value(i.DATA, '$.individual.firstName')
                    ELSE json_value(i.DATA, '$.nonIndividual.businessDescriptionText')
                END as alternate_name,
                json_value(i.DATA, '$.common.taxIdentificationNumber') as tax_id_number,
                json_value(i.DATA, '$.common.taxIdentificationType') as tax_id_type,
                json_value(i.DATA, '$.individual.dateOfBirth') as birth_date,
                json_value(addr.DATA, '$.addressLine1') as address_line,
                json_value(addr.DATA, '$.city') as city_name,
                json_value(addr.DATA, '$.state') as state,
                json_value(addr.DATA, '$.postalCode') as postal_code,
                NVL(json_value(addr.DATA, '$.countryCode'), 'US') as country_code,
                json_value(i.DATA, '$.common.customerType') as customer_type
            FROM %sphone p
            JOIN %sidentity i ON json_value(p.DATA, '$.phoneKey.customerNumber') =
                                 json_value(i.DATA, '$._id.customerNumber')
            JOIN %saccount a ON json_value(i.DATA, '$._id.customerNumber') =
                                json_value(a.DATA, '$.accountHolders[0].customerNumber')
            LEFT JOIN %saddress addr ON json_value(addr.DATA, '$._id.customerNumber') =
                                        json_value(i.DATA, '$._id.customerNumber')
            WHERE CONTAINS(p.DATA, ?, 1) > 0
              AND json_value(i.DATA, '$.common.taxIdentificationNumberLast4') = ?
              AND json_value(a.DATA, '$.accountKey.accountNumberLast4') = ?
            ORDER BY SCORE(1) DESC
            FETCH FIRST ? ROWS ONLY
            """.formatted(collectionPrefix, collectionPrefix, collectionPrefix, collectionPrefix);
    }

    private String buildUC3Query() {
        return """
            SELECT
                SCORE(1) as ranking_score,
                json_value(i.DATA, '$._id.customerNumber') as ecn,
                NVL(json_value(i.DATA, '$._id.customerCompanyNumber' RETURNING NUMBER), 1) as company_id,
                json_value(i.DATA, '$.common.entityTypeIndicator') as entity_type,
                json_value(i.DATA, '$.common.fullName') as name,
                CASE
                    WHEN json_value(i.DATA, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
                    THEN json_value(i.DATA, '$.individual.firstName')
                    ELSE json_value(i.DATA, '$.nonIndividual.businessDescriptionText')
                END as alternate_name,
                json_value(i.DATA, '$.common.taxIdentificationNumber') as tax_id_number,
                json_value(i.DATA, '$.common.taxIdentificationType') as tax_id_type,
                json_value(i.DATA, '$.individual.dateOfBirth') as birth_date,
                json_value(addr.DATA, '$.addressLine1') as address_line,
                json_value(addr.DATA, '$.city') as city_name,
                json_value(addr.DATA, '$.state') as state,
                json_value(addr.DATA, '$.postalCode') as postal_code,
                NVL(json_value(addr.DATA, '$.countryCode'), 'US') as country_code,
                json_value(i.DATA, '$.common.customerType') as customer_type
            FROM %sphone p
            JOIN %sidentity i ON json_value(p.DATA, '$.phoneKey.customerNumber') =
                                 json_value(i.DATA, '$._id.customerNumber')
            JOIN %saccount a ON json_value(i.DATA, '$._id.customerNumber') =
                                json_value(a.DATA, '$.accountHolders[0].customerNumber')
            LEFT JOIN %saddress addr ON json_value(addr.DATA, '$._id.customerNumber') =
                                        json_value(i.DATA, '$._id.customerNumber')
            WHERE CONTAINS(p.DATA, ?, 1) > 0
              AND json_value(a.DATA, '$.accountKey.accountNumberLast4') = ?
            ORDER BY SCORE(1) DESC
            FETCH FIRST ? ROWS ONLY
            """.formatted(collectionPrefix, collectionPrefix, collectionPrefix, collectionPrefix);
    }

    private String buildUC4Query() {
        return """
            SELECT
                SCORE(1) as ranking_score,
                json_value(i.DATA, '$._id.customerNumber') as ecn,
                NVL(json_value(i.DATA, '$._id.customerCompanyNumber' RETURNING NUMBER), 1) as company_id,
                json_value(i.DATA, '$.common.entityTypeIndicator') as entity_type,
                json_value(i.DATA, '$.common.fullName') as name,
                CASE
                    WHEN json_value(i.DATA, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
                    THEN json_value(i.DATA, '$.individual.firstName')
                    ELSE json_value(i.DATA, '$.nonIndividual.businessDescriptionText')
                END as alternate_name,
                json_value(i.DATA, '$.common.taxIdentificationNumber') as tax_id_number,
                json_value(i.DATA, '$.common.taxIdentificationType') as tax_id_type,
                json_value(i.DATA, '$.individual.dateOfBirth') as birth_date,
                json_value(addr.DATA, '$.addressLine1') as address_line,
                json_value(addr.DATA, '$.city') as city_name,
                json_value(addr.DATA, '$.state') as state,
                json_value(addr.DATA, '$.postalCode') as postal_code,
                NVL(json_value(addr.DATA, '$.countryCode'), 'US') as country_code,
                json_value(i.DATA, '$.common.customerType') as customer_type
            FROM %saccount a
            JOIN %sidentity i ON json_value(a.DATA, '$.accountHolders[0].customerNumber') =
                                 json_value(i.DATA, '$._id.customerNumber')
            LEFT JOIN %saddress addr ON json_value(addr.DATA, '$._id.customerNumber') =
                                        json_value(i.DATA, '$._id.customerNumber')
            WHERE CONTAINS(a.DATA, ?, 1) > 0
              AND json_value(i.DATA, '$.common.taxIdentificationNumberLast4') = ?
            ORDER BY SCORE(1) DESC
            FETCH FIRST ? ROWS ONLY
            """.formatted(collectionPrefix, collectionPrefix, collectionPrefix);
    }

    private String buildUC5Query() {
        return """
            SELECT
                SCORE(1) as ranking_score,
                json_value(i.DATA, '$._id.customerNumber') as ecn,
                NVL(json_value(i.DATA, '$._id.customerCompanyNumber' RETURNING NUMBER), 1) as company_id,
                json_value(i.DATA, '$.common.entityTypeIndicator') as entity_type,
                json_value(i.DATA, '$.common.fullName') as name,
                CASE
                    WHEN json_value(i.DATA, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
                    THEN json_value(i.DATA, '$.individual.firstName')
                    ELSE json_value(i.DATA, '$.nonIndividual.businessDescriptionText')
                END as alternate_name,
                json_value(i.DATA, '$.common.taxIdentificationNumber') as tax_id_number,
                json_value(i.DATA, '$.common.taxIdentificationType') as tax_id_type,
                json_value(i.DATA, '$.individual.dateOfBirth') as birth_date,
                json_value(addr.DATA, '$.addressLine1') as address_line,
                json_value(addr.DATA, '$.city') as city_name,
                json_value(addr.DATA, '$.state') as state,
                json_value(addr.DATA, '$.postalCode') as postal_code,
                NVL(json_value(addr.DATA, '$.countryCode'), 'US') as country_code,
                json_value(i.DATA, '$.common.customerType') as customer_type
            FROM %saddress addr
            JOIN %sidentity i ON json_value(addr.DATA, '$._id.customerNumber') =
                                 json_value(i.DATA, '$._id.customerNumber')
            JOIN %saccount a ON json_value(i.DATA, '$._id.customerNumber') =
                                json_value(a.DATA, '$.accountHolders[0].customerNumber')
            WHERE CONTAINS(addr.DATA, ?, 1) > 0
              AND json_value(addr.DATA, '$.state') = ?
              AND json_value(addr.DATA, '$.postalCode') = ?
              AND json_value(i.DATA, '$.common.taxIdentificationNumberLast4') = ?
              AND json_value(a.DATA, '$.accountKey.accountNumberLast4') = ?
            ORDER BY SCORE(1) DESC
            FETCH FIRST ? ROWS ONLY
            """.formatted(collectionPrefix, collectionPrefix, collectionPrefix);
    }

    private String buildUC6Query() {
        return """
            SELECT
                SCORE(1) as ranking_score,
                json_value(i.DATA, '$._id.customerNumber') as ecn,
                NVL(json_value(i.DATA, '$._id.customerCompanyNumber' RETURNING NUMBER), 1) as company_id,
                json_value(i.DATA, '$.common.entityTypeIndicator') as entity_type,
                json_value(i.DATA, '$.common.fullName') as name,
                CASE
                    WHEN json_value(i.DATA, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
                    THEN json_value(i.DATA, '$.individual.firstName')
                    ELSE json_value(i.DATA, '$.nonIndividual.businessDescriptionText')
                END as alternate_name,
                json_value(i.DATA, '$.common.taxIdentificationNumber') as tax_id_number,
                json_value(i.DATA, '$.common.taxIdentificationType') as tax_id_type,
                json_value(i.DATA, '$.individual.dateOfBirth') as birth_date,
                json_value(addr.DATA, '$.addressLine1') as address_line,
                json_value(addr.DATA, '$.city') as city_name,
                json_value(addr.DATA, '$.state') as state,
                json_value(addr.DATA, '$.postalCode') as postal_code,
                NVL(json_value(addr.DATA, '$.countryCode'), 'US') as country_code,
                json_value(i.DATA, '$.common.customerType') as customer_type
            FROM %sidentity i
            JOIN %saccount a ON json_value(i.DATA, '$._id.customerNumber') =
                                json_value(a.DATA, '$.accountHolders[0].customerNumber')
            LEFT JOIN %saddress addr ON json_value(addr.DATA, '$._id.customerNumber') =
                                        json_value(i.DATA, '$._id.customerNumber')
            WHERE CONTAINS(i.DATA, ?, 1) > 0
              AND json_value(a.DATA, '$.accountKey.accountNumberLast4') = ?
            ORDER BY SCORE(1) DESC
            FETCH FIRST ? ROWS ONLY
            """.formatted(collectionPrefix, collectionPrefix, collectionPrefix);
    }

    private String buildUC7Query() {
        return """
            SELECT
                SCORE(1) as ranking_score,
                json_value(i.DATA, '$._id.customerNumber') as ecn,
                NVL(json_value(i.DATA, '$._id.customerCompanyNumber' RETURNING NUMBER), 1) as company_id,
                json_value(i.DATA, '$.common.entityTypeIndicator') as entity_type,
                json_value(i.DATA, '$.common.fullName') as name,
                CASE
                    WHEN json_value(i.DATA, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
                    THEN json_value(i.DATA, '$.individual.firstName')
                    ELSE json_value(i.DATA, '$.nonIndividual.businessDescriptionText')
                END as alternate_name,
                json_value(i.DATA, '$.common.taxIdentificationNumber') as tax_id_number,
                json_value(i.DATA, '$.common.taxIdentificationType') as tax_id_type,
                json_value(i.DATA, '$.individual.dateOfBirth') as birth_date,
                json_value(addr.DATA, '$.addressLine1') as address_line,
                json_value(addr.DATA, '$.city') as city_name,
                json_value(addr.DATA, '$.state') as state,
                json_value(addr.DATA, '$.postalCode') as postal_code,
                NVL(json_value(addr.DATA, '$.countryCode'), 'US') as country_code,
                json_value(i.DATA, '$.common.customerType') as customer_type
            FROM %sidentity i
            JOIN %sphone p ON json_value(i.DATA, '$._id.customerNumber') =
                              json_value(p.DATA, '$.phoneKey.customerNumber')
            JOIN %saccount a ON json_value(i.DATA, '$._id.customerNumber') =
                                json_value(a.DATA, '$.accountHolders[0].customerNumber')
            LEFT JOIN %saddress addr ON json_value(addr.DATA, '$._id.customerNumber') =
                                        json_value(i.DATA, '$._id.customerNumber')
            WHERE CONTAINS(i.DATA, ?, 1) > 0
              AND json_value(p.DATA, '$.phoneKey.phoneNumber') = ?
              AND json_value(a.DATA, '$.accountKey.accountNumber') = ?
            ORDER BY SCORE(1) DESC
            FETCH FIRST ? ROWS ONLY
            """.formatted(collectionPrefix, collectionPrefix, collectionPrefix, collectionPrefix);
    }

    // Query Execution Methods

    private List<UcSearchResult> executeQuery(String sql, String searchTerm, String ssnLast4, int limit) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, searchTerm);
            stmt.setString(2, ssnLast4);
            stmt.setInt(3, limit);
            return executeAndMapResults(stmt);
        } catch (SQLException e) {
            throw new SearchException("Failed to execute UC search query", e);
        }
    }

    private List<UcSearchResult> executeQueryWithAccountLast4(String sql, String searchTerm, String ssnLast4, String accountLast4, int limit) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, searchTerm);
            stmt.setString(2, ssnLast4);
            stmt.setString(3, accountLast4);
            stmt.setInt(4, limit);
            return executeAndMapResults(stmt);
        } catch (SQLException e) {
            throw new SearchException("Failed to execute UC search query", e);
        }
    }

    private List<UcSearchResult> executeQueryPhoneAccountLast4(String sql, String phoneNumber, String accountLast4, int limit) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, phoneNumber);
            stmt.setString(2, accountLast4);
            stmt.setInt(3, limit);
            return executeAndMapResults(stmt);
        } catch (SQLException e) {
            throw new SearchException("Failed to execute UC search query", e);
        }
    }

    private List<UcSearchResult> executeQueryUC5(String sql, String city, String state, String zip,
                                                   String ssnLast4, String accountLast4, int limit) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, city);
            stmt.setString(2, state);
            stmt.setString(3, zip);
            stmt.setString(4, ssnLast4);
            stmt.setString(5, accountLast4);
            stmt.setInt(6, limit);
            return executeAndMapResults(stmt);
        } catch (SQLException e) {
            throw new SearchException("Failed to execute UC search query", e);
        }
    }

    private List<UcSearchResult> executeQueryEmailAccountLast4(String sql, String email, String accountLast4, int limit) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            stmt.setString(2, accountLast4);
            stmt.setInt(3, limit);
            return executeAndMapResults(stmt);
        } catch (SQLException e) {
            throw new SearchException("Failed to execute UC search query", e);
        }
    }

    private List<UcSearchResult> executeQueryUC7(String sql, String email, String phoneNumber, String accountNumber, int limit) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, email);
            stmt.setString(2, phoneNumber);
            stmt.setString(3, accountNumber);
            stmt.setInt(4, limit);
            return executeAndMapResults(stmt);
        } catch (SQLException e) {
            throw new SearchException("Failed to execute UC search query", e);
        }
    }

    private List<UcSearchResult> executeAndMapResults(PreparedStatement stmt) throws SQLException {
        List<UcSearchResult> results = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                results.add(mapResultRow(rs));
            }
        }
        return results;
    }

    private UcSearchResult mapResultRow(ResultSet rs) throws SQLException {
        return UcSearchResult.builder()
            .rankingScore(rs.getInt("ranking_score"))
            .ecn(rs.getString("ecn"))
            .companyId(rs.getInt("company_id"))
            .entityType(rs.getString("entity_type"))
            .name(rs.getString("name"))
            .alternateName(rs.getString("alternate_name"))
            .taxIdNumber(rs.getString("tax_id_number"))
            .taxIdType(rs.getString("tax_id_type"))
            .birthDate(rs.getString("birth_date"))
            .addressLine(rs.getString("address_line"))
            .cityName(rs.getString("city_name"))
            .state(rs.getString("state"))
            .postalCode(rs.getString("postal_code"))
            .countryCode(rs.getString("country_code"))
            .customerType(rs.getString("customer_type"))
            .build();
    }

    // Validation Methods

    private void validatePhoneNumber(String phoneNumber) {
        if (phoneNumber == null) {
            throw new IllegalArgumentException("Phone number cannot be null");
        }
        if (phoneNumber.isBlank()) {
            throw new IllegalArgumentException("Phone number cannot be empty");
        }
    }

    private void validateSsnLast4(String ssnLast4) {
        if (ssnLast4 == null) {
            throw new IllegalArgumentException("SSN last 4 cannot be null");
        }
        if (ssnLast4.length() != 4) {
            throw new IllegalArgumentException("SSN last 4 must be exactly 4 digits");
        }
    }

    private void validateAccountLast4(String accountLast4) {
        if (accountLast4 == null) {
            throw new IllegalArgumentException("Account last 4 cannot be null");
        }
        if (accountLast4.length() != 4) {
            throw new IllegalArgumentException("Account last 4 must be exactly 4 digits");
        }
    }

    private void validateAccountNumber(String accountNumber) {
        if (accountNumber == null) {
            throw new IllegalArgumentException("Account number cannot be null");
        }
        if (accountNumber.isBlank()) {
            throw new IllegalArgumentException("Account number cannot be empty");
        }
    }

    private void validateNotEmpty(String value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be empty");
        }
    }

    private void validateLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
    }
}
