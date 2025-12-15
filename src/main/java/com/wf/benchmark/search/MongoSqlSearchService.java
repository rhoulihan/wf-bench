package com.wf.benchmark.search;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoCursor;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Service for UC 1-7 search queries using MongoDB $sql operator with json_textcontains().
 *
 * <p>This implementation uses the MongoDB API's $sql aggregation operator to execute
 * Oracle SQL queries through the MongoDB wire protocol. Based on Oracle team guidance:
 * <ul>
 *   <li>Use json_textcontains() instead of CONTAINS() for text search</li>
 *   <li>Use CTE (WITH clause) pattern for multi-collection joins</li>
 *   <li>Use DOMAIN_INDEX_SORT hint for optimized score sorting</li>
 *   <li>Order by highest score DESC</li>
 *   <li>Use json {...} for structured output</li>
 * </ul>
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
public class MongoSqlSearchService {

    private static final Logger log = LoggerFactory.getLogger(MongoSqlSearchService.class);

    private final MongoClient mongoClient;
    private final String databaseName;
    private final String collectionPrefix;

    /**
     * Creates a new MongoSqlSearchService with default (empty) collection prefix.
     */
    public MongoSqlSearchService(MongoClient mongoClient, String databaseName) {
        this(mongoClient, databaseName, "");
    }

    /**
     * Creates a new MongoSqlSearchService with the specified collection prefix.
     *
     * @param mongoClient      the MongoDB client
     * @param databaseName     the database name
     * @param collectionPrefix the prefix for collection names (e.g., "bench_")
     */
    public MongoSqlSearchService(MongoClient mongoClient, String databaseName, String collectionPrefix) {
        this.mongoClient = mongoClient;
        this.databaseName = databaseName;
        this.collectionPrefix = collectionPrefix != null ? collectionPrefix : "";
    }

    // ==================== UC Search Methods ====================

    /**
     * UC-1: Search by Phone Number + SSN Last 4.
     * Joins phone and identity collections using text search on phone number.
     */
    public List<UcSearchResult> searchUC1(String phoneNumber, String ssnLast4, int limit) {
        validatePhoneNumber(phoneNumber);
        validateSsnLast4(ssnLast4);
        validateLimit(limit);

        String sql = buildUC1Query(phoneNumber, ssnLast4, limit);
        return executeQuery(sql);
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

        String sql = buildUC2Query(phoneNumber, ssnLast4, accountLast4, limit);
        return executeQuery(sql);
    }

    /**
     * UC-3: Search by Phone Number + Account Last 4.
     * Joins phone, identity, and account collections.
     */
    public List<UcSearchResult> searchUC3(String phoneNumber, String accountLast4, int limit) {
        validatePhoneNumber(phoneNumber);
        validateAccountLast4(accountLast4);
        validateLimit(limit);

        String sql = buildUC3Query(phoneNumber, accountLast4, limit);
        return executeQuery(sql);
    }

    /**
     * UC-4: Search by Account Number + SSN Last 4.
     * Joins account and identity collections using text search on account number.
     */
    public List<UcSearchResult> searchUC4(String accountNumber, String ssnLast4, int limit) {
        validateAccountNumber(accountNumber);
        validateSsnLast4(ssnLast4);
        validateLimit(limit);

        String sql = buildUC4Query(accountNumber, ssnLast4, limit);
        return executeQuery(sql);
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

        String sql = buildUC5Query(city, state, zip, ssnLast4, accountLast4, limit);
        return executeQuery(sql);
    }

    /**
     * UC-6: Search by Email + Account Last 4.
     * Joins identity and account collections using text search on email.
     */
    public List<UcSearchResult> searchUC6(String email, String accountLast4, int limit) {
        validateNotEmpty(email, "Email");
        validateAccountLast4(accountLast4);
        validateLimit(limit);

        String sql = buildUC6Query(email, accountLast4, limit);
        return executeQuery(sql);
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

        String sql = buildUC7Query(email, phoneNumber, accountNumber, limit);
        return executeQuery(sql);
    }

    // ==================== UC 8-11 Search Methods (Other Searches) ====================

    /**
     * UC-8: Search by TIN (full 9-digit).
     * Exact match on full Tax Identification Number (SSN/TIN).
     */
    public List<UcSearchResult> searchUC8(String tin, int limit) {
        validateTin(tin);
        validateLimit(limit);

        String sql = buildUC8Query(tin, limit);
        return executeQuery(sql);
    }

    /**
     * UC-9: Search by Account Number with optional filters.
     * Exact match on full account number with optional product type and COID filters.
     */
    public List<UcSearchResult> searchUC9(String accountNumber, String productType, String coid, int limit) {
        validateAccountNumber(accountNumber);
        validateLimit(limit);

        String sql = buildUC9Query(accountNumber, productType, coid, limit);
        return executeQuery(sql);
    }

    /**
     * UC-10: Search by Tokenized Account Number (hyphenated format).
     * Searches on the hyphenated format (XXXX-XXXX-XXXX) of account numbers.
     */
    public List<UcSearchResult> searchUC10(String tokenizedAccount, int limit) {
        validateNotEmpty(tokenizedAccount, "Account number");
        validateLimit(limit);

        String sql = buildUC10Query(tokenizedAccount, limit);
        return executeQuery(sql);
    }

    /**
     * UC-11: Search by Phone Number (full 10-digit).
     * Exact match on full 10-digit phone number.
     */
    public List<UcSearchResult> searchUC11(String phoneNumber, int limit) {
        validatePhoneNumber10Digits(phoneNumber);
        validateLimit(limit);

        String sql = buildUC11Query(phoneNumber, limit);
        return executeQuery(sql);
    }

    // ==================== Query Builders ====================

    /**
     * Builds the SQL query for UC-1: Phone + SSN Last 4.
     * Fuzzy matching on both phone and SSN last 4, combined score.
     * SSN last 4 uses ends-with pattern (%term) to anchor at end of string.
     * Uses dot notation for JOINs and SELECT per Oracle team guidance.
     */
    public String buildUC1Query(String phoneNumber, String ssnLast4, int limit) {
        String phone = collectionPrefix + "phone";
        String identity = collectionPrefix + "identity";
        String address = collectionPrefix + "address";

        return """
            WITH
            phones AS (
              SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) pscore
              FROM "%s"
              WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', '%s', 1)
              ORDER BY score(1) DESC
            ),
            identities AS (
              SELECT "DATA", score(2) iscore
              FROM "%s"
              WHERE json_textcontains("DATA", '$."common"."taxIdentificationNumber"', '%%%s', 2)
            ),
            addresses AS (
              SELECT "DATA"
              FROM "%s"
            ),
            joined AS (
              SELECT
                p."DATA" phone_data,
                i."DATA" identity_data,
                a."DATA" address_data,
                (p.pscore + i.iscore) / 2 ranking_score
              FROM phones p
              JOIN identities i ON i."DATA"."_id"."customerNumber".string() = p."DATA"."phoneKey"."customerNumber".string()
              JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
            )
            SELECT /*+ MONITOR */ json {
              'rankingScore' : j.ranking_score,
              'ecn' : j.identity_data."_id"."customerNumber".string(),
              'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
              'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
              'name' : j.identity_data."common"."fullName".string(),
              'alternateName' : CASE
                WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
                THEN j.identity_data."individual"."firstName".string()
                ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
              END,
              'taxIdNumber' : j.identity_data."common"."taxIdentificationNumber".string(),
              'taxIdType' : j.identity_data."common"."taxIdentificationType".string(),
              'birthDate' : j.identity_data."individual"."dateOfBirth".string(),
              'addressLine' : j.address_data."addresses"."addressLine1".string(),
              'cityName' : j.address_data."addresses"."cityName".string(),
              'state' : j.address_data."addresses"."stateCode".string(),
              'postalCode' : j.address_data."addresses"."postalCode".string(),
              'countryCode' : NVL(j.address_data."addresses"."countryCode".string(), 'US'),
              'customerType' : j.identity_data."common"."customerType".string()
            }
            FROM joined j
            ORDER BY j.ranking_score DESC
            FETCH FIRST %d ROWS ONLY
            """.formatted(phone, escapeSql(phoneNumber), identity, escapeSql(ssnLast4), address, limit);
    }

    /**
     * Builds the SQL query for UC-2: Phone + SSN Last 4 + Account Last 4.
     * Fuzzy matching on all three conditions, combined score.
     * SSN last 4 and account last 4 use ends-with pattern (%term) to anchor at end of string.
     * Uses dot notation for JOINs and SELECT per Oracle team guidance.
     */
    public String buildUC2Query(String phoneNumber, String ssnLast4, String accountLast4, int limit) {
        String phone = collectionPrefix + "phone";
        String identity = collectionPrefix + "identity";
        String account = collectionPrefix + "account";
        String address = collectionPrefix + "address";

        return """
            WITH
            phones AS (
              SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) pscore
              FROM "%s"
              WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', '%s', 1)
              ORDER BY score(1) DESC
            ),
            identities AS (
              SELECT "DATA", score(2) iscore
              FROM "%s"
              WHERE json_textcontains("DATA", '$."common"."taxIdentificationNumber"', '%%%s', 2)
            ),
            accounts AS (
              SELECT "DATA", score(3) ascore
              FROM "%s"
              WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '%s', 3)
            ),
            addresses AS (
              SELECT "DATA"
              FROM "%s"
            ),
            joined AS (
              SELECT
                p."DATA" phone_data,
                i."DATA" identity_data,
                ac."DATA" account_data,
                a."DATA" address_data,
                (p.pscore + i.iscore + ac.ascore) / 3 ranking_score
              FROM phones p
              JOIN identities i ON i."DATA"."_id"."customerNumber".string() = p."DATA"."phoneKey"."customerNumber".string()
              JOIN accounts ac ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
              JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
            )
            SELECT /*+ MONITOR */ json {
              'rankingScore' : j.ranking_score,
              'ecn' : j.identity_data."_id"."customerNumber".string(),
              'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
              'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
              'name' : j.identity_data."common"."fullName".string(),
              'alternateName' : CASE
                WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
                THEN j.identity_data."individual"."firstName".string()
                ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
              END,
              'taxIdNumber' : j.identity_data."common"."taxIdentificationNumber".string(),
              'taxIdType' : j.identity_data."common"."taxIdentificationType".string(),
              'birthDate' : j.identity_data."individual"."dateOfBirth".string(),
              'addressLine' : j.address_data."addresses"."addressLine1".string(),
              'cityName' : j.address_data."addresses"."cityName".string(),
              'state' : j.address_data."addresses"."stateCode".string(),
              'postalCode' : j.address_data."addresses"."postalCode".string(),
              'countryCode' : NVL(j.address_data."addresses"."countryCode".string(), 'US'),
              'customerType' : j.identity_data."common"."customerType".string()
            }
            FROM joined j
            ORDER BY j.ranking_score DESC
            FETCH FIRST %d ROWS ONLY
            """.formatted(phone, escapeSql(phoneNumber), identity, escapeSql(ssnLast4),
                account, escapeSql(accountLast4), address, limit);
    }

    /**
     * Builds the SQL query for UC-3: Phone + Account Last 4.
     * Fuzzy matching on both phone and account last 4, combined score.
     * Account last 4 uses ends-with pattern (%term) to anchor at end of string.
     * Uses dot notation for JOINs and SELECT per Oracle team guidance.
     */
    public String buildUC3Query(String phoneNumber, String accountLast4, int limit) {
        String phone = collectionPrefix + "phone";
        String identity = collectionPrefix + "identity";
        String account = collectionPrefix + "account";
        String address = collectionPrefix + "address";

        return """
            WITH
            phones AS (
              SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) pscore
              FROM "%s"
              WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', '%s', 1)
              ORDER BY score(1) DESC
            ),
            identities AS (
              SELECT "DATA"
              FROM "%s"
            ),
            accounts AS (
              SELECT "DATA", score(2) ascore
              FROM "%s"
              WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '%s', 2)
            ),
            addresses AS (
              SELECT "DATA"
              FROM "%s"
            ),
            joined AS (
              SELECT
                p."DATA" phone_data,
                i."DATA" identity_data,
                ac."DATA" account_data,
                a."DATA" address_data,
                (p.pscore + ac.ascore) / 2 ranking_score
              FROM phones p
              JOIN identities i ON i."DATA"."_id"."customerNumber".string() = p."DATA"."phoneKey"."customerNumber".string()
              JOIN accounts ac ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
              JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
            )
            SELECT /*+ MONITOR */ json {
              'rankingScore' : j.ranking_score,
              'ecn' : j.identity_data."_id"."customerNumber".string(),
              'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
              'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
              'name' : j.identity_data."common"."fullName".string(),
              'alternateName' : CASE
                WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
                THEN j.identity_data."individual"."firstName".string()
                ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
              END,
              'taxIdNumber' : j.identity_data."common"."taxIdentificationNumber".string(),
              'taxIdType' : j.identity_data."common"."taxIdentificationType".string(),
              'birthDate' : j.identity_data."individual"."dateOfBirth".string(),
              'addressLine' : j.address_data."addresses"."addressLine1".string(),
              'cityName' : j.address_data."addresses"."cityName".string(),
              'state' : j.address_data."addresses"."stateCode".string(),
              'postalCode' : j.address_data."addresses"."postalCode".string(),
              'countryCode' : NVL(j.address_data."addresses"."countryCode".string(), 'US'),
              'customerType' : j.identity_data."common"."customerType".string()
            }
            FROM joined j
            ORDER BY j.ranking_score DESC
            FETCH FIRST %d ROWS ONLY
            """.formatted(phone, escapeSql(phoneNumber), identity, account, escapeSql(accountLast4), address, limit);
    }

    /**
     * Builds the SQL query for UC-4: Account Number + SSN Last 4.
     * Fuzzy matching on both account number and SSN last 4, combined score.
     * SSN last 4 uses ends-with pattern (%term) to anchor at end of string.
     * Uses dot notation for JOINs and SELECT per Oracle team guidance.
     */
    public String buildUC4Query(String accountNumber, String ssnLast4, int limit) {
        String account = collectionPrefix + "account";
        String identity = collectionPrefix + "identity";
        String address = collectionPrefix + "address";

        return """
            WITH
            accounts AS (
              SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) ascore
              FROM "%s"
              WHERE json_textcontains("DATA", '$."accountKey"."accountNumber"', '%s', 1)
              ORDER BY score(1) DESC
            ),
            identities AS (
              SELECT "DATA", score(2) iscore
              FROM "%s"
              WHERE json_textcontains("DATA", '$."common"."taxIdentificationNumber"', '%%%s', 2)
            ),
            addresses AS (
              SELECT "DATA"
              FROM "%s"
            ),
            joined AS (
              SELECT
                ac."DATA" account_data,
                i."DATA" identity_data,
                a."DATA" address_data,
                (ac.ascore + i.iscore) / 2 ranking_score
              FROM accounts ac
              JOIN identities i ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
              JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
            )
            SELECT /*+ MONITOR */ json {
              'rankingScore' : j.ranking_score,
              'ecn' : j.identity_data."_id"."customerNumber".string(),
              'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
              'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
              'name' : j.identity_data."common"."fullName".string(),
              'alternateName' : CASE
                WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
                THEN j.identity_data."individual"."firstName".string()
                ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
              END,
              'taxIdNumber' : j.identity_data."common"."taxIdentificationNumber".string(),
              'taxIdType' : j.identity_data."common"."taxIdentificationType".string(),
              'birthDate' : j.identity_data."individual"."dateOfBirth".string(),
              'addressLine' : j.address_data."addresses"."addressLine1".string(),
              'cityName' : j.address_data."addresses"."cityName".string(),
              'state' : j.address_data."addresses"."stateCode".string(),
              'postalCode' : j.address_data."addresses"."postalCode".string(),
              'countryCode' : NVL(j.address_data."addresses"."countryCode".string(), 'US'),
              'customerType' : j.identity_data."common"."customerType".string()
            }
            FROM joined j
            ORDER BY j.ranking_score DESC
            FETCH FIRST %d ROWS ONLY
            """.formatted(account, escapeSql(accountNumber), identity, escapeSql(ssnLast4), address, limit);
    }

    /**
     * Builds the SQL query for UC-5: City/State/ZIP + SSN Last 4 + Account Last 4.
     * Fuzzy matching on city, SSN last 4, and account last 4, combined score.
     * Uses $.addresses.cityName (without array index) for json_textcontains - matches any element.
     * Uses json_exists with filter expression for state/zip array access.
     * SSN last 4 and account last 4 use ends-with pattern (%term) to anchor at end of string.
     * Uses dot notation for JOINs and SELECT per Oracle team guidance.
     *
     * <p>Per Josh Spiegel (Oracle): Use json_exists with filter expression for array access:
     * {@code json_exists(a.data, '$.path[0]?(@.field == $b1)' passing 'value' as "b1")}
     */
    public String buildUC5Query(String city, String state, String zip, String ssnLast4, String accountLast4, int limit) {
        String addressColl = collectionPrefix + "address";
        String identity = collectionPrefix + "identity";
        String account = collectionPrefix + "account";

        return """
            WITH
            addresses AS (
              SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) addr_score
              FROM "%s" a
              WHERE json_textcontains("DATA", '$."addresses"."cityName"', '%s', 1)
                AND json_exists(a.data, '$.addresses[0]?(@.stateCode == $b1 && @.postalCode == $b2)' passing '%s' as "b1", '%s' as "b2" error on error)
              ORDER BY score(1) DESC
            ),
            identities AS (
              SELECT "DATA", score(2) iscore
              FROM "%s"
              WHERE json_textcontains("DATA", '$."common"."taxIdentificationNumber"', '%%%s', 2)
            ),
            accounts AS (
              SELECT "DATA", score(3) ascore
              FROM "%s"
              WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '%s', 3)
            ),
            joined AS (
              SELECT
                a."DATA" address_data,
                i."DATA" identity_data,
                ac."DATA" account_data,
                (a.addr_score + i.iscore + ac.ascore) / 3 ranking_score
              FROM addresses a
              JOIN identities i ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
              JOIN accounts ac ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
            )
            SELECT /*+ MONITOR */ json {
              'rankingScore' : j.ranking_score,
              'ecn' : j.identity_data."_id"."customerNumber".string(),
              'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
              'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
              'name' : j.identity_data."common"."fullName".string(),
              'alternateName' : CASE
                WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
                THEN j.identity_data."individual"."firstName".string()
                ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
              END,
              'taxIdNumber' : j.identity_data."common"."taxIdentificationNumber".string(),
              'taxIdType' : j.identity_data."common"."taxIdentificationType".string(),
              'birthDate' : j.identity_data."individual"."dateOfBirth".string(),
              'addressLine' : j.address_data."addresses"."addressLine1".string(),
              'cityName' : j.address_data."addresses"."cityName".string(),
              'state' : j.address_data."addresses"."stateCode".string(),
              'postalCode' : j.address_data."addresses"."postalCode".string(),
              'countryCode' : NVL(j.address_data."addresses"."countryCode".string(), 'US'),
              'customerType' : j.identity_data."common"."customerType".string()
            }
            FROM joined j
            ORDER BY j.ranking_score DESC
            FETCH FIRST %d ROWS ONLY
            """.formatted(addressColl, escapeSql(city), escapeSql(state), escapeSql(zip),
                identity, escapeSql(ssnLast4), account, escapeSql(accountLast4), limit);
    }

    /**
     * Builds the SQL query for UC-6: Email + Account Last 4.
     * Fuzzy matching on email local part (before @) and account last 4, combined score.
     * Account last 4 uses ends-with pattern (%term) to anchor at end of string.
     * Uses dot notation for JOINs and SELECT per Oracle team guidance.
     *
     * <p>Optimization: Start with identities (email search), then join to accounts.
     * Per Oracle team optimized query pattern.
     */
    public String buildUC6Query(String email, String accountLast4, int limit) {
        String identity = collectionPrefix + "identity";
        String account = collectionPrefix + "account";
        String address = collectionPrefix + "address";

        // Extract local part of email (before @) for more focused fuzzy matching
        String emailLocalPart = extractEmailLocalPart(email);

        return """
            WITH
            identities AS (
              SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) iscore
              FROM "%s"
              WHERE json_textcontains("DATA", '$."emails"."emailAddress"', '%s', 1)
              ORDER BY score(1) DESC
            ),
            accounts AS (
              SELECT "DATA", score(2) ascore
              FROM "%s"
              WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '%s', 2)
            ),
            addresses AS (
              SELECT "DATA"
              FROM "%s"
            ),
            joined AS (
              SELECT
                i."DATA" identity_data,
                ac."DATA" account_data,
                a."DATA" address_data,
                (i.iscore + ac.ascore) / 2 ranking_score
              FROM identities i
              JOIN accounts ac ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
              JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
            )
            SELECT /*+ MONITOR */ json {
              'rankingScore' : j.ranking_score,
              'ecn' : j.identity_data."_id"."customerNumber".string(),
              'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
              'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
              'name' : j.identity_data."common"."fullName".string(),
              'alternateName' : CASE
                WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
                THEN j.identity_data."individual"."firstName".string()
                ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
              END,
              'taxIdNumber' : j.identity_data."common"."taxIdentificationNumber".string(),
              'taxIdType' : j.identity_data."common"."taxIdentificationType".string(),
              'birthDate' : j.identity_data."individual"."dateOfBirth".string(),
              'addressLine' : j.address_data."addresses"."addressLine1".string(),
              'cityName' : j.address_data."addresses"."cityName".string(),
              'state' : j.address_data."addresses"."stateCode".string(),
              'postalCode' : j.address_data."addresses"."postalCode".string(),
              'countryCode' : NVL(j.address_data."addresses"."countryCode".string(), 'US'),
              'customerType' : j.identity_data."common"."customerType".string()
            }
            FROM joined j
            ORDER BY j.ranking_score DESC
            FETCH FIRST %d ROWS ONLY
            """.formatted(identity, escapeSql(emailLocalPart), account, escapeSql(accountLast4), address, limit);
    }

    /**
     * Builds the SQL query for UC-7: Email + Phone + Account Number.
     * Fuzzy matching on email local part (before @), phone, and account number.
     * Uses dot notation for JOINs and SELECT per Oracle team guidance.
     *
     * <p>Note: Each CTE with json_textcontains must use unique score labels (1, 2, 3)
     * to avoid view-merge conflicts per Oracle team guidance.
     */
    public String buildUC7Query(String email, String phoneNumber, String accountNumber, int limit) {
        String identity = collectionPrefix + "identity";
        String phone = collectionPrefix + "phone";
        String account = collectionPrefix + "account";
        String address = collectionPrefix + "address";

        // Extract local part of email (before @) for more focused fuzzy matching
        String emailLocalPart = extractEmailLocalPart(email);

        return """
            WITH
            identities AS (
              SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) iscore
              FROM "%s"
              WHERE json_textcontains("DATA", '$."emails"."emailAddress"', '%s', 1)
              ORDER BY score(1) DESC
            ),
            phones AS (
              SELECT "DATA", score(2) pscore
              FROM "%s"
              WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', '%s', 2)
            ),
            accounts AS (
              SELECT "DATA", score(3) ascore
              FROM "%s"
              WHERE json_textcontains("DATA", '$."accountKey"."accountNumber"', '%s', 3)
            ),
            addresses AS (
              SELECT "DATA"
              FROM "%s"
            ),
            joined AS (
              SELECT
                i."DATA" identity_data,
                p."DATA" phone_data,
                ac."DATA" account_data,
                a."DATA" address_data,
                (i.iscore + p.pscore + ac.ascore) / 3 combined_score
              FROM identities i
              JOIN phones p ON p."DATA"."phoneKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
              JOIN accounts ac ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
              JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
            )
            SELECT /*+ MONITOR */ json {
              'rankingScore' : j.combined_score,
              'ecn' : j.identity_data."_id"."customerNumber".string(),
              'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
              'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
              'name' : j.identity_data."common"."fullName".string(),
              'alternateName' : CASE
                WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
                THEN j.identity_data."individual"."firstName".string()
                ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
              END,
              'taxIdNumber' : j.identity_data."common"."taxIdentificationNumber".string(),
              'taxIdType' : j.identity_data."common"."taxIdentificationType".string(),
              'birthDate' : j.identity_data."individual"."dateOfBirth".string(),
              'addressLine' : j.address_data."addresses"."addressLine1".string(),
              'cityName' : j.address_data."addresses"."cityName".string(),
              'state' : j.address_data."addresses"."stateCode".string(),
              'postalCode' : j.address_data."addresses"."postalCode".string(),
              'countryCode' : NVL(j.address_data."addresses"."countryCode".string(), 'US'),
              'customerType' : j.identity_data."common"."customerType".string()
            }
            FROM joined j
            ORDER BY j.combined_score DESC
            FETCH FIRST %d ROWS ONLY
            """.formatted(identity, escapeSql(emailLocalPart), phone, escapeSql(phoneNumber),
                account, escapeSql(accountNumber), address, limit);
    }

    // ==================== UC 8-11 Query Builders ====================

    /**
     * Builds the SQL query for UC-8: Search by TIN (full 9-digit).
     * Exact match on full Tax Identification Number.
     */
    public String buildUC8Query(String tin, int limit) {
        String identity = collectionPrefix + "identity";
        String address = collectionPrefix + "address";

        return """
            WITH
            identities AS (
              SELECT "DATA"
              FROM "%s"
              WHERE json_textcontains("DATA", '$."common"."taxIdentificationNumber"', '%s', 1)
            ),
            addresses AS (
              SELECT "DATA"
              FROM "%s"
            ),
            joined AS (
              SELECT
                i."DATA" identity_data,
                a."DATA" address_data
              FROM identities i
              JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
            )
            SELECT /*+ MONITOR */ json {
              'ecn' : j.identity_data."_id"."customerNumber".string(),
              'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
              'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
              'name' : j.identity_data."common"."fullName".string(),
              'alternateName' : CASE
                WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
                THEN j.identity_data."individual"."firstName".string()
                ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
              END,
              'taxIdNumber' : j.identity_data."common"."taxIdentificationNumber".string(),
              'taxIdType' : j.identity_data."common"."taxIdentificationType".string(),
              'birthDate' : j.identity_data."individual"."dateOfBirth".string(),
              'addressLine' : j.address_data."addresses"."addressLine1".string(),
              'cityName' : j.address_data."addresses"."cityName".string(),
              'state' : j.address_data."addresses"."stateCode".string(),
              'postalCode' : j.address_data."addresses"."postalCode".string(),
              'countryCode' : NVL(j.address_data."addresses"."countryCode".string(), 'US'),
              'customerType' : j.identity_data."common"."customerType".string()
            }
            FROM joined j
            FETCH FIRST %d ROWS ONLY
            """.formatted(identity, escapeSql(tin), address, limit);
    }

    /**
     * Builds the SQL query for UC-9: Search by Account Number with optional filters.
     * Exact match on full account number with optional productType and COID filters.
     */
    public String buildUC9Query(String accountNumber, String productType, String coid, int limit) {
        String account = collectionPrefix + "account";
        String identity = collectionPrefix + "identity";
        String address = collectionPrefix + "address";

        // Build optional filter conditions
        StringBuilder accountFilters = new StringBuilder();
        if (productType != null && !productType.isBlank()) {
            accountFilters.append(String.format(
                "\n    AND ac.\"DATA\".\"productTypeCode\".string() = '%s'", escapeSql(productType)));
        }
        if (coid != null && !coid.isBlank()) {
            accountFilters.append(String.format(
                "\n    AND ac.\"DATA\".\"companyOfInterestId\".string() = '%s'", escapeSql(coid)));
        }

        // Direct JOIN approach - avoid full table CTEs
        return """
            SELECT /*+ MONITOR */ json {
              'ecn' : i."DATA"."_id"."customerNumber".string(),
              'companyId' : NVL(i."DATA"."_id"."customerCompanyNumber".string(), 1),
              'accountNumber' : ac."DATA"."accountKey"."accountNumber".string(),
              'productType' : ac."DATA"."productTypeCode".string(),
              'companyOfInterestId' : ac."DATA"."companyOfInterestId".string(),
              'entityType' : i."DATA"."common"."entityTypeIndicator".string(),
              'name' : i."DATA"."common"."fullName".string(),
              'alternateName' : CASE
                WHEN i."DATA"."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
                THEN i."DATA"."individual"."firstName".string()
                ELSE i."DATA"."nonIndividual"."businessDescriptionText".string()
              END,
              'taxIdNumber' : i."DATA"."common"."taxIdentificationNumber".string(),
              'taxIdType' : i."DATA"."common"."taxIdentificationType".string(),
              'birthDate' : i."DATA"."individual"."dateOfBirth".string(),
              'addressLine' : a."DATA"."addresses"."addressLine1".string(),
              'cityName' : a."DATA"."addresses"."cityName".string(),
              'state' : a."DATA"."addresses"."stateCode".string(),
              'postalCode' : a."DATA"."addresses"."postalCode".string(),
              'countryCode' : NVL(a."DATA"."addresses"."countryCode".string(), 'US'),
              'customerType' : i."DATA"."common"."customerType".string()
            }
            FROM "%s" ac
            JOIN "%s" i ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
            JOIN "%s" a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
            WHERE json_textcontains(ac."DATA", '$."accountKey"."accountNumber"', '%s')%s
            FETCH FIRST %d ROWS ONLY
            """.formatted(account, identity, address, escapeSql(accountNumber), accountFilters.toString(), limit);
    }

    /**
     * Builds the SQL query for UC-10: Search by Tokenized Account Number (hyphenated).
     * Uses exact match on accountNumberHyphenated field in format XXXX-XXXX-XXXX.
     * If input is unhyphenated 12-digit, converts to hyphenated format.
     */
    public String buildUC10Query(String tokenizedAccount, int limit) {
        String account = collectionPrefix + "account";
        String identity = collectionPrefix + "identity";
        String address = collectionPrefix + "address";

        // Normalize input to hyphenated format if not already
        String hyphenatedAccount = normalizeToHyphenated(tokenizedAccount);

        // Use text search on tokenized/hyphenated account number
        return """
            WITH
            accounts AS (
              SELECT /*+ DOMAIN_INDEX_SORT */ "DATA"
              FROM "%s" ac
              WHERE json_textcontains(ac."DATA", '$."accountKey"."accountNumberHyphenated"', '%s')
            ),
            identities AS (
              SELECT "DATA"
              FROM "%s"
            ),
            addresses AS (
              SELECT "DATA"
              FROM "%s"
            ),
            joined AS (
              SELECT
                ac."DATA" account_data,
                i."DATA" identity_data,
                a."DATA" address_data
              FROM accounts ac
              JOIN identities i ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
              JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
            )
            SELECT /*+ MONITOR */ json {
              'ecn' : j.identity_data."_id"."customerNumber".string(),
              'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
              'accountNumber' : j.account_data."accountKey"."accountNumber".string(),
              'accountNumberHyphenated' : j.account_data."accountKey"."accountNumberHyphenated".string(),
              'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
              'name' : j.identity_data."common"."fullName".string(),
              'alternateName' : CASE
                WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
                THEN j.identity_data."individual"."firstName".string()
                ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
              END,
              'taxIdNumber' : j.identity_data."common"."taxIdentificationNumber".string(),
              'taxIdType' : j.identity_data."common"."taxIdentificationType".string(),
              'birthDate' : j.identity_data."individual"."dateOfBirth".string(),
              'addressLine' : j.address_data."addresses"."addressLine1".string(),
              'cityName' : j.address_data."addresses"."cityName".string(),
              'state' : j.address_data."addresses"."stateCode".string(),
              'postalCode' : j.address_data."addresses"."postalCode".string(),
              'countryCode' : NVL(j.address_data."addresses"."countryCode".string(), 'US'),
              'customerType' : j.identity_data."common"."customerType".string()
            }
            FROM joined j
            FETCH FIRST %d ROWS ONLY
            """.formatted(account, escapeSql(hyphenatedAccount), identity, address, limit);
    }

    /**
     * Builds the SQL query for UC-11: Search by Phone Number (full 10-digit).
     * Exact match on full phone number.
     */
    public String buildUC11Query(String phoneNumber, int limit) {
        String phone = collectionPrefix + "phone";
        String identity = collectionPrefix + "identity";
        String address = collectionPrefix + "address";

        return """
            WITH
            phones AS (
              SELECT "DATA"
              FROM "%s"
              WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', '%s', 1)
            ),
            identities AS (
              SELECT "DATA"
              FROM "%s"
            ),
            addresses AS (
              SELECT "DATA"
              FROM "%s"
            ),
            joined AS (
              SELECT
                p."DATA" phone_data,
                i."DATA" identity_data,
                a."DATA" address_data
              FROM phones p
              JOIN identities i ON i."DATA"."_id"."customerNumber".string() = p."DATA"."phoneKey"."customerNumber".string()
              JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
            )
            SELECT /*+ MONITOR */ json {
              'ecn' : j.identity_data."_id"."customerNumber".string(),
              'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
              'phoneNumber' : j.phone_data."phoneKey"."phoneNumber".string(),
              'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
              'name' : j.identity_data."common"."fullName".string(),
              'alternateName' : CASE
                WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
                THEN j.identity_data."individual"."firstName".string()
                ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
              END,
              'taxIdNumber' : j.identity_data."common"."taxIdentificationNumber".string(),
              'taxIdType' : j.identity_data."common"."taxIdentificationType".string(),
              'birthDate' : j.identity_data."individual"."dateOfBirth".string(),
              'addressLine' : j.address_data."addresses"."addressLine1".string(),
              'cityName' : j.address_data."addresses"."cityName".string(),
              'state' : j.address_data."addresses"."stateCode".string(),
              'postalCode' : j.address_data."addresses"."postalCode".string(),
              'countryCode' : NVL(j.address_data."addresses"."countryCode".string(), 'US'),
              'customerType' : j.identity_data."common"."customerType".string()
            }
            FROM joined j
            FETCH FIRST %d ROWS ONLY
            """.formatted(phone, escapeSql(phoneNumber), identity, address, limit);
    }

    /**
     * Normalizes an account number to hyphenated format (XXXX-XXXX-XXXX).
     * If already hyphenated, returns as-is. If 12-digit unhyphenated, converts.
     */
    private String normalizeToHyphenated(String accountNumber) {
        if (accountNumber == null) return null;

        // Remove any existing hyphens/spaces to normalize
        String digits = accountNumber.replaceAll("[\\-\\s]", "");

        // If it's 12 digits, convert to XXXX-XXXX-XXXX format
        if (digits.length() == 12 && digits.matches("\\d+")) {
            return digits.substring(0, 4) + "-" + digits.substring(4, 8) + "-" + digits.substring(8, 12);
        }

        // Otherwise return as-is (may already be hyphenated or different format)
        return accountNumber;
    }

    // ==================== Wildcard Pattern Builders ====================

    /**
     * Builds a "ends with" wildcard pattern.
     * Per Oracle team guidance: wildcard goes BEFORE the term.
     */
    public String buildEndsWithPattern(String term) {
        return "%" + term;
    }

    /**
     * Builds a "starts with" wildcard pattern.
     */
    public String buildStartsWithPattern(String term) {
        return term + "%";
    }

    /**
     * Builds a "contains" wildcard pattern.
     */
    public String buildContainsPattern(String term) {
        return "%" + term + "%";
    }

    // ==================== Query Execution ====================

    /**
     * Executes a SQL query using the MongoDB $sql aggregation operator.
     */
    private List<UcSearchResult> executeQuery(String sql) {
        log.debug("Executing $sql query: {}", sql);

        MongoDatabase database = mongoClient.getDatabase(databaseName);

        // Build the $sql aggregation pipeline
        Document sqlStage = new Document("$sql", sql);
        List<Document> pipeline = List.of(sqlStage);

        // Execute using database-level aggregation
        AggregateIterable<Document> results = database.aggregate(pipeline);

        // Parse results
        List<UcSearchResult> searchResults = new ArrayList<>();
        try (MongoCursor<Document> cursor = results.iterator()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                UcSearchResult result = parseResult(doc);
                if (result != null) {
                    searchResults.add(result);
                }
            }
        }

        log.debug("Query returned {} results", searchResults.size());
        return searchResults;
    }

    /**
     * Parses a MongoDB Document into a UcSearchResult.
     */
    private UcSearchResult parseResult(Document doc) {
        try {
            return UcSearchResult.builder()
                .rankingScore(getInt(doc, "rankingScore", 0))
                .ecn(getString(doc, "ecn"))
                .companyId(getInt(doc, "companyId", 1))
                .entityType(getString(doc, "entityType"))
                .name(getString(doc, "name"))
                .alternateName(getString(doc, "alternateName"))
                .taxIdNumber(getString(doc, "taxIdNumber"))
                .taxIdType(getString(doc, "taxIdType"))
                .birthDate(getString(doc, "birthDate"))
                .addressLine(getString(doc, "addressLine"))
                .cityName(getString(doc, "cityName"))
                .state(getString(doc, "state"))
                .postalCode(getString(doc, "postalCode"))
                .countryCode(getString(doc, "countryCode", "US"))
                .customerType(getString(doc, "customerType"))
                .build();
        } catch (Exception e) {
            log.warn("Failed to parse result document: {}", e.getMessage());
            return null;
        }
    }

    private String getString(Document doc, String key) {
        return doc.getString(key);
    }

    private String getString(Document doc, String key, String defaultValue) {
        String value = doc.getString(key);
        return value != null ? value : defaultValue;
    }

    private int getInt(Document doc, String key, int defaultValue) {
        Object value = doc.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    // ==================== SQL Escaping ====================

    /**
     * Escapes special characters in SQL strings to prevent injection.
     */
    private String escapeSql(String value) {
        if (value == null) return "";
        return value
            .replace("'", "''")     // Escape single quotes
            .replace("\\", "\\\\"); // Escape backslashes
    }

    /**
     * Extracts the local part of an email address (before the @ symbol).
     * This provides more focused fuzzy matching by ignoring the domain.
     *
     * @param email the full email address
     * @return the local part (before @), or the full string if no @ found
     */
    private String extractEmailLocalPart(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        int atIndex = email.indexOf('@');
        if (atIndex > 0) {
            return email.substring(0, atIndex);
        }
        return email;
    }

    // ==================== Validation Methods ====================

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

    private void validateTin(String tin) {
        if (tin == null) {
            throw new IllegalArgumentException("TIN cannot be null");
        }
        if (tin.isBlank()) {
            throw new IllegalArgumentException("TIN cannot be empty");
        }
        // Remove any dashes for length validation
        String digitsOnly = tin.replaceAll("-", "");
        if (digitsOnly.length() != 9) {
            throw new IllegalArgumentException("TIN must be exactly 9 digits");
        }
    }

    private void validatePhoneNumber10Digits(String phoneNumber) {
        if (phoneNumber == null) {
            throw new IllegalArgumentException("Phone number cannot be null");
        }
        if (phoneNumber.isBlank()) {
            throw new IllegalArgumentException("Phone number cannot be empty");
        }
        // Remove any formatting characters for length validation
        String digitsOnly = phoneNumber.replaceAll("[\\-\\s\\(\\)]", "");
        if (digitsOnly.length() != 10) {
            throw new IllegalArgumentException("Phone number must be exactly 10 digits");
        }
    }
}
