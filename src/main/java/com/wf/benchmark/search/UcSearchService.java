package com.wf.benchmark.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

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
