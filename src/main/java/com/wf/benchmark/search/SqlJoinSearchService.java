package com.wf.benchmark.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for multi-collection SQL JOIN queries.
 * Uses Oracle SQL JOINs with json_value() for efficient multi-collection queries.
 *
 * UC-1: Phone + SSN Last 4 (2-way join: phone -> identity)
 * UC-2: Phone + SSN + Account (3-way join: phone -> identity -> account)
 * UC-4: Account + SSN (2-way join: account -> identity)
 * UC-6: Email + Account Last 4 (2-way join: identity -> account)
 */
public class SqlJoinSearchService {

    private static final Logger log = LoggerFactory.getLogger(SqlJoinSearchService.class);

    private final DataSource dataSource;
    private String collectionPrefix = "";

    public SqlJoinSearchService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * UC-1: Search by Phone + SSN Last 4 (2-way join: phone -> identity)
     */
    public List<SqlJoinSearchResult> searchUC1(String phoneNumber, String ssnLast4, int limit) {
        validateUC1Params(phoneNumber, ssnLast4);

        String sql = buildUC1Query();
        log.debug("Executing UC-1 query: phoneNumber='{}', ssnLast4='{}', limit={}", phoneNumber, ssnLast4, limit);

        return executeUC1Query(sql, phoneNumber, ssnLast4, limit);
    }

    /**
     * UC-2: Search by Phone + SSN Last 4 + Account Last 4 (3-way join: phone -> identity -> account)
     */
    public List<SqlJoinSearchResult> searchUC2(String phoneNumber, String ssnLast4, String accountLast4, int limit) {
        validateUC2Params(phoneNumber, ssnLast4, accountLast4);

        String sql = buildUC2Query();
        log.debug("Executing UC-2 query: phoneNumber='{}', ssnLast4='{}', accountLast4='{}', limit={}",
                  phoneNumber, ssnLast4, accountLast4, limit);

        return executeUC2Query(sql, phoneNumber, ssnLast4, accountLast4, limit);
    }

    /**
     * UC-4: Search by Account + SSN Last 4 (2-way join: account -> identity)
     */
    public List<SqlJoinSearchResult> searchUC4(String accountNumber, String ssnLast4, int limit) {
        validateUC4Params(accountNumber, ssnLast4);

        String sql = buildUC4Query();
        log.debug("Executing UC-4 query: accountNumber='{}', ssnLast4='{}', limit={}", accountNumber, ssnLast4, limit);

        return executeUC4Query(sql, accountNumber, ssnLast4, limit);
    }

    /**
     * UC-6: Search by Email + Account Last 4 (2-way join: identity -> account)
     */
    public List<SqlJoinSearchResult> searchUC6(String email, String accountLast4, int limit) {
        validateUC6Params(email, accountLast4);

        String sql = buildUC6Query();
        log.debug("Executing UC-6 query: email='{}', accountLast4='{}', limit={}", email, accountLast4, limit);

        return executeUC6Query(sql, email, accountLast4, limit);
    }

    // ---- SQL Query Builders ----

    private String buildUC1Query() {
        String phoneTable = collectionPrefix + "phone";
        String identityTable = collectionPrefix + "identity";

        return """
            SELECT
                json_value(i.DATA, '$._id.customerNumber') as customer_number,
                json_value(p.DATA, '$.phoneKey.phoneNumber') as phone_number,
                json_value(i.DATA, '$.common.fullName') as full_name,
                json_value(i.DATA, '$.common.taxIdentificationNumberLast4') as ssn_last4
            FROM %s p
            JOIN %s i ON json_value(p.DATA, '$.phoneKey.customerNumber') =
                         json_value(i.DATA, '$._id.customerNumber')
            WHERE json_value(p.DATA, '$.phoneKey.phoneNumber') = ?
              AND json_value(i.DATA, '$.common.taxIdentificationNumberLast4') = ?
            FETCH FIRST ? ROWS ONLY
            """.formatted(phoneTable, identityTable);
    }

    private String buildUC2Query() {
        String phoneTable = collectionPrefix + "phone";
        String identityTable = collectionPrefix + "identity";
        String accountTable = collectionPrefix + "account";

        return """
            SELECT
                json_value(i.DATA, '$._id.customerNumber') as customer_number,
                json_value(p.DATA, '$.phoneKey.phoneNumber') as phone_number,
                json_value(i.DATA, '$.common.fullName') as full_name,
                json_value(i.DATA, '$.common.taxIdentificationNumberLast4') as ssn_last4,
                json_value(a.DATA, '$.accountKey.accountNumber') as account_number,
                json_value(a.DATA, '$.accountKey.accountNumberLast4') as account_last4
            FROM %s p
            JOIN %s i ON json_value(p.DATA, '$.phoneKey.customerNumber') =
                         json_value(i.DATA, '$._id.customerNumber')
            JOIN %s a ON json_value(i.DATA, '$._id.customerNumber') =
                         json_value(a.DATA, '$.accountHolders[0].customerNumber')
            WHERE json_value(p.DATA, '$.phoneKey.phoneNumber') = ?
              AND json_value(i.DATA, '$.common.taxIdentificationNumberLast4') = ?
              AND json_value(a.DATA, '$.accountKey.accountNumberLast4') = ?
            FETCH FIRST ? ROWS ONLY
            """.formatted(phoneTable, identityTable, accountTable);
    }

    private String buildUC4Query() {
        String accountTable = collectionPrefix + "account";
        String identityTable = collectionPrefix + "identity";

        return """
            SELECT
                json_value(i.DATA, '$._id.customerNumber') as customer_number,
                json_value(a.DATA, '$.accountKey.accountNumber') as account_number,
                json_value(i.DATA, '$.common.fullName') as full_name,
                json_value(i.DATA, '$.common.taxIdentificationNumberLast4') as ssn_last4
            FROM %s a
            JOIN %s i ON json_value(a.DATA, '$.accountHolders[0].customerNumber') =
                         json_value(i.DATA, '$._id.customerNumber')
            WHERE json_value(a.DATA, '$.accountKey.accountNumber') = ?
              AND json_value(i.DATA, '$.common.taxIdentificationNumberLast4') = ?
            FETCH FIRST ? ROWS ONLY
            """.formatted(accountTable, identityTable);
    }

    private String buildUC6Query() {
        String identityTable = collectionPrefix + "identity";
        String accountTable = collectionPrefix + "account";

        return """
            SELECT
                json_value(i.DATA, '$._id.customerNumber') as customer_number,
                json_value(i.DATA, '$.emails[0].emailAddress') as email,
                json_value(i.DATA, '$.common.fullName') as full_name,
                json_value(a.DATA, '$.accountKey.accountNumberLast4') as account_last4
            FROM %s i
            JOIN %s a ON json_value(i.DATA, '$._id.customerNumber') =
                         json_value(a.DATA, '$.accountHolders[0].customerNumber')
            WHERE json_value(i.DATA, '$.emails[0].emailAddress') = ?
              AND json_value(a.DATA, '$.accountKey.accountNumberLast4') = ?
            FETCH FIRST ? ROWS ONLY
            """.formatted(identityTable, accountTable);
    }

    // ---- Query Executors ----

    private List<SqlJoinSearchResult> executeUC1Query(String sql, String phoneNumber, String ssnLast4, int limit) {
        List<SqlJoinSearchResult> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, phoneNumber);
            stmt.setString(2, ssnLast4);
            stmt.setInt(3, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(SqlJoinSearchResult.builder()
                        .customerNumber(rs.getString("customer_number"))
                        .phoneNumber(rs.getString("phone_number"))
                        .fullName(rs.getString("full_name"))
                        .ssnLast4(rs.getString("ssn_last4"))
                        .build());
                }
            }

        } catch (SQLException e) {
            log.error("UC-1 query failed: {}", e.getMessage(), e);
            throw new SearchException("UC-1 query failed", e);
        }

        log.debug("UC-1 query returned {} results", results.size());
        return results;
    }

    private List<SqlJoinSearchResult> executeUC2Query(String sql, String phoneNumber, String ssnLast4, String accountLast4, int limit) {
        List<SqlJoinSearchResult> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, phoneNumber);
            stmt.setString(2, ssnLast4);
            stmt.setString(3, accountLast4);
            stmt.setInt(4, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(SqlJoinSearchResult.builder()
                        .customerNumber(rs.getString("customer_number"))
                        .phoneNumber(rs.getString("phone_number"))
                        .fullName(rs.getString("full_name"))
                        .ssnLast4(rs.getString("ssn_last4"))
                        .accountNumber(rs.getString("account_number"))
                        .accountNumberLast4(rs.getString("account_last4"))
                        .build());
                }
            }

        } catch (SQLException e) {
            log.error("UC-2 query failed: {}", e.getMessage(), e);
            throw new SearchException("UC-2 query failed", e);
        }

        log.debug("UC-2 query returned {} results", results.size());
        return results;
    }

    private List<SqlJoinSearchResult> executeUC4Query(String sql, String accountNumber, String ssnLast4, int limit) {
        List<SqlJoinSearchResult> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, accountNumber);
            stmt.setString(2, ssnLast4);
            stmt.setInt(3, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(SqlJoinSearchResult.builder()
                        .customerNumber(rs.getString("customer_number"))
                        .accountNumber(rs.getString("account_number"))
                        .fullName(rs.getString("full_name"))
                        .ssnLast4(rs.getString("ssn_last4"))
                        .build());
                }
            }

        } catch (SQLException e) {
            log.error("UC-4 query failed: {}", e.getMessage(), e);
            throw new SearchException("UC-4 query failed", e);
        }

        log.debug("UC-4 query returned {} results", results.size());
        return results;
    }

    private List<SqlJoinSearchResult> executeUC6Query(String sql, String email, String accountLast4, int limit) {
        List<SqlJoinSearchResult> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, email);
            stmt.setString(2, accountLast4);
            stmt.setInt(3, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(SqlJoinSearchResult.builder()
                        .customerNumber(rs.getString("customer_number"))
                        .email(rs.getString("email"))
                        .fullName(rs.getString("full_name"))
                        .accountNumberLast4(rs.getString("account_last4"))
                        .build());
                }
            }

        } catch (SQLException e) {
            log.error("UC-6 query failed: {}", e.getMessage(), e);
            throw new SearchException("UC-6 query failed", e);
        }

        log.debug("UC-6 query returned {} results", results.size());
        return results;
    }

    // ---- Validation ----

    private void validateUC1Params(String phoneNumber, String ssnLast4) {
        if (phoneNumber == null) {
            throw new IllegalArgumentException("Phone number cannot be null");
        }
        if (phoneNumber.isEmpty()) {
            throw new IllegalArgumentException("Phone number cannot be empty");
        }
        if (ssnLast4 == null) {
            throw new IllegalArgumentException("SSN last 4 cannot be null");
        }
        if (ssnLast4.isEmpty()) {
            throw new IllegalArgumentException("SSN last 4 cannot be empty");
        }
    }

    private void validateUC2Params(String phoneNumber, String ssnLast4, String accountLast4) {
        validateUC1Params(phoneNumber, ssnLast4);
        if (accountLast4 == null) {
            throw new IllegalArgumentException("Account last 4 cannot be null");
        }
        if (accountLast4.isEmpty()) {
            throw new IllegalArgumentException("Account last 4 cannot be empty");
        }
    }

    private void validateUC4Params(String accountNumber, String ssnLast4) {
        if (accountNumber == null) {
            throw new IllegalArgumentException("Account number cannot be null");
        }
        if (accountNumber.isEmpty()) {
            throw new IllegalArgumentException("Account number cannot be empty");
        }
        if (ssnLast4 == null) {
            throw new IllegalArgumentException("SSN last 4 cannot be null");
        }
        if (ssnLast4.isEmpty()) {
            throw new IllegalArgumentException("SSN last 4 cannot be empty");
        }
    }

    private void validateUC6Params(String email, String accountLast4) {
        if (email == null) {
            throw new IllegalArgumentException("Email cannot be null");
        }
        if (email.isEmpty()) {
            throw new IllegalArgumentException("Email cannot be empty");
        }
        if (accountLast4 == null) {
            throw new IllegalArgumentException("Account last 4 cannot be null");
        }
        if (accountLast4.isEmpty()) {
            throw new IllegalArgumentException("Account last 4 cannot be empty");
        }
    }

    // ---- Configuration ----

    public String getCollectionPrefix() {
        return collectionPrefix;
    }

    public void setCollectionPrefix(String collectionPrefix) {
        this.collectionPrefix = collectionPrefix != null ? collectionPrefix : "";
    }
}
