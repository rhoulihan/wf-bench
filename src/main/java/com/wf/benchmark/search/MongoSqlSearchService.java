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

    // ==================== Query Builders ====================

    /**
     * Builds the SQL query for UC-1: Phone + SSN Last 4.
     * Fuzzy matching on both phone and SSN last 4, combined score.
     * SSN last 4 uses ends-with pattern (%term) to anchor at end of string.
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
              JOIN identities i ON JSON_VALUE(i."DATA", '$._id.customerNumber') = JSON_VALUE(p."DATA", '$.phoneKey.customerNumber')
              JOIN addresses a ON JSON_VALUE(a."DATA", '$._id.customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
            )
            SELECT json {
              'rankingScore' : j.ranking_score,
              'ecn' : JSON_VALUE(j.identity_data, '$._id.customerNumber'),
              'companyId' : NVL(JSON_VALUE(j.identity_data, '$._id.customerCompanyNumber'), 1),
              'entityType' : JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator'),
              'name' : JSON_VALUE(j.identity_data, '$.common.fullName'),
              'alternateName' : CASE
                WHEN JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
                THEN JSON_VALUE(j.identity_data, '$.individual.firstName')
                ELSE JSON_VALUE(j.identity_data, '$.nonIndividual.businessDescriptionText')
              END,
              'taxIdNumber' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationNumber'),
              'taxIdType' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationType'),
              'birthDate' : JSON_VALUE(j.identity_data, '$.individual.dateOfBirth'),
              'addressLine' : JSON_VALUE(j.address_data, '$.addresses.addressLine1'),
              'cityName' : JSON_VALUE(j.address_data, '$.addresses.cityName'),
              'state' : JSON_VALUE(j.address_data, '$.addresses.stateCode'),
              'postalCode' : JSON_VALUE(j.address_data, '$.addresses.postalCode'),
              'countryCode' : NVL(JSON_VALUE(j.address_data, '$.addresses.countryCode'), 'US'),
              'customerType' : JSON_VALUE(j.identity_data, '$.common.customerType')
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
              WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '%%%s', 3)
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
              JOIN identities i ON JSON_VALUE(i."DATA", '$._id.customerNumber') = JSON_VALUE(p."DATA", '$.phoneKey.customerNumber')
              JOIN accounts ac ON JSON_VALUE(ac."DATA", '$.accountHolders[0].customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
              JOIN addresses a ON JSON_VALUE(a."DATA", '$._id.customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
            )
            SELECT json {
              'rankingScore' : j.ranking_score,
              'ecn' : JSON_VALUE(j.identity_data, '$._id.customerNumber'),
              'companyId' : NVL(JSON_VALUE(j.identity_data, '$._id.customerCompanyNumber'), 1),
              'entityType' : JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator'),
              'name' : JSON_VALUE(j.identity_data, '$.common.fullName'),
              'alternateName' : CASE
                WHEN JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
                THEN JSON_VALUE(j.identity_data, '$.individual.firstName')
                ELSE JSON_VALUE(j.identity_data, '$.nonIndividual.businessDescriptionText')
              END,
              'taxIdNumber' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationNumber'),
              'taxIdType' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationType'),
              'birthDate' : JSON_VALUE(j.identity_data, '$.individual.dateOfBirth'),
              'addressLine' : JSON_VALUE(j.address_data, '$.addresses.addressLine1'),
              'cityName' : JSON_VALUE(j.address_data, '$.addresses.cityName'),
              'state' : JSON_VALUE(j.address_data, '$.addresses.stateCode'),
              'postalCode' : JSON_VALUE(j.address_data, '$.addresses.postalCode'),
              'countryCode' : NVL(JSON_VALUE(j.address_data, '$.addresses.countryCode'), 'US'),
              'customerType' : JSON_VALUE(j.identity_data, '$.common.customerType')
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
              WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '%%%s', 2)
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
              JOIN identities i ON JSON_VALUE(i."DATA", '$._id.customerNumber') = JSON_VALUE(p."DATA", '$.phoneKey.customerNumber')
              JOIN accounts ac ON JSON_VALUE(ac."DATA", '$.accountHolders[0].customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
              JOIN addresses a ON JSON_VALUE(a."DATA", '$._id.customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
            )
            SELECT json {
              'rankingScore' : j.ranking_score,
              'ecn' : JSON_VALUE(j.identity_data, '$._id.customerNumber'),
              'companyId' : NVL(JSON_VALUE(j.identity_data, '$._id.customerCompanyNumber'), 1),
              'entityType' : JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator'),
              'name' : JSON_VALUE(j.identity_data, '$.common.fullName'),
              'alternateName' : CASE
                WHEN JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
                THEN JSON_VALUE(j.identity_data, '$.individual.firstName')
                ELSE JSON_VALUE(j.identity_data, '$.nonIndividual.businessDescriptionText')
              END,
              'taxIdNumber' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationNumber'),
              'taxIdType' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationType'),
              'birthDate' : JSON_VALUE(j.identity_data, '$.individual.dateOfBirth'),
              'addressLine' : JSON_VALUE(j.address_data, '$.addresses.addressLine1'),
              'cityName' : JSON_VALUE(j.address_data, '$.addresses.cityName'),
              'state' : JSON_VALUE(j.address_data, '$.addresses.stateCode'),
              'postalCode' : JSON_VALUE(j.address_data, '$.addresses.postalCode'),
              'countryCode' : NVL(JSON_VALUE(j.address_data, '$.addresses.countryCode'), 'US'),
              'customerType' : JSON_VALUE(j.identity_data, '$.common.customerType')
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
              JOIN identities i ON JSON_VALUE(ac."DATA", '$.accountHolders[0].customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
              JOIN addresses a ON JSON_VALUE(a."DATA", '$._id.customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
            )
            SELECT json {
              'rankingScore' : j.ranking_score,
              'ecn' : JSON_VALUE(j.identity_data, '$._id.customerNumber'),
              'companyId' : NVL(JSON_VALUE(j.identity_data, '$._id.customerCompanyNumber'), 1),
              'entityType' : JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator'),
              'name' : JSON_VALUE(j.identity_data, '$.common.fullName'),
              'alternateName' : CASE
                WHEN JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
                THEN JSON_VALUE(j.identity_data, '$.individual.firstName')
                ELSE JSON_VALUE(j.identity_data, '$.nonIndividual.businessDescriptionText')
              END,
              'taxIdNumber' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationNumber'),
              'taxIdType' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationType'),
              'birthDate' : JSON_VALUE(j.identity_data, '$.individual.dateOfBirth'),
              'addressLine' : JSON_VALUE(j.address_data, '$.addresses.addressLine1'),
              'cityName' : JSON_VALUE(j.address_data, '$.addresses.cityName'),
              'state' : JSON_VALUE(j.address_data, '$.addresses.stateCode'),
              'postalCode' : JSON_VALUE(j.address_data, '$.addresses.postalCode'),
              'countryCode' : NVL(JSON_VALUE(j.address_data, '$.addresses.countryCode'), 'US'),
              'customerType' : JSON_VALUE(j.identity_data, '$.common.customerType')
            }
            FROM joined j
            ORDER BY j.ranking_score DESC
            FETCH FIRST %d ROWS ONLY
            """.formatted(account, escapeSql(accountNumber), identity, escapeSql(ssnLast4), address, limit);
    }

    /**
     * Builds the SQL query for UC-5: City/State/ZIP + SSN Last 4 + Account Last 4.
     * Fuzzy matching on city, SSN last 4, and account last 4, combined score.
     * Uses $.addresses.cityName (without array index) - matches any element in the array.
     * SSN last 4 and account last 4 use ends-with pattern (%term) to anchor at end of string.
     * Note: Array index syntax like $.addresses[0].cityName fails with ORA-40469.
     */
    public String buildUC5Query(String city, String state, String zip, String ssnLast4, String accountLast4, int limit) {
        String addressColl = collectionPrefix + "address";
        String identity = collectionPrefix + "identity";
        String account = collectionPrefix + "account";

        return """
            WITH
            addresses AS (
              SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) addr_score
              FROM "%s"
              WHERE json_textcontains("DATA", '$."addresses"."cityName"', '%s', 1)
                AND JSON_VALUE("DATA", '$.addresses.stateCode') = '%s'
                AND JSON_VALUE("DATA", '$.addresses.postalCode') = '%s'
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
              WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '%%%s', 3)
            ),
            joined AS (
              SELECT
                a."DATA" address_data,
                i."DATA" identity_data,
                ac."DATA" account_data,
                (a.addr_score + i.iscore + ac.ascore) / 3 ranking_score
              FROM addresses a
              JOIN identities i ON JSON_VALUE(a."DATA", '$._id.customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
              JOIN accounts ac ON JSON_VALUE(ac."DATA", '$.accountHolders[0].customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
            )
            SELECT json {
              'rankingScore' : j.ranking_score,
              'ecn' : JSON_VALUE(j.identity_data, '$._id.customerNumber'),
              'companyId' : NVL(JSON_VALUE(j.identity_data, '$._id.customerCompanyNumber'), 1),
              'entityType' : JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator'),
              'name' : JSON_VALUE(j.identity_data, '$.common.fullName'),
              'alternateName' : CASE
                WHEN JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
                THEN JSON_VALUE(j.identity_data, '$.individual.firstName')
                ELSE JSON_VALUE(j.identity_data, '$.nonIndividual.businessDescriptionText')
              END,
              'taxIdNumber' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationNumber'),
              'taxIdType' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationType'),
              'birthDate' : JSON_VALUE(j.identity_data, '$.individual.dateOfBirth'),
              'addressLine' : JSON_VALUE(j.address_data, '$.addresses.addressLine1'),
              'cityName' : JSON_VALUE(j.address_data, '$.addresses.cityName'),
              'state' : JSON_VALUE(j.address_data, '$.addresses.stateCode'),
              'postalCode' : JSON_VALUE(j.address_data, '$.addresses.postalCode'),
              'countryCode' : NVL(JSON_VALUE(j.address_data, '$.addresses.countryCode'), 'US'),
              'customerType' : JSON_VALUE(j.identity_data, '$.common.customerType')
            }
            FROM joined j
            ORDER BY j.ranking_score DESC
            FETCH FIRST %d ROWS ONLY
            """.formatted(addressColl, escapeSql(city), escapeSql(state), escapeSql(zip),
                identity, escapeSql(ssnLast4), account, escapeSql(accountLast4), limit);
    }

    /**
     * Builds the SQL query for UC-6: Email + Account Last 4.
     * Fuzzy matching on both email and account last 4, combined score.
     * Account last 4 uses ends-with pattern (%term) to anchor at end of string.
     */
    public String buildUC6Query(String email, String accountLast4, int limit) {
        String identity = collectionPrefix + "identity";
        String account = collectionPrefix + "account";
        String address = collectionPrefix + "address";

        return """
            WITH
            identities AS (
              SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) iscore
              FROM "%s"
              WHERE json_textcontains("DATA", '$."primaryEmail"', '%s', 1)
              ORDER BY score(1) DESC
            ),
            accounts AS (
              SELECT "DATA", score(2) ascore
              FROM "%s"
              WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '%%%s', 2)
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
              JOIN accounts ac ON JSON_VALUE(ac."DATA", '$.accountHolders[0].customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
              JOIN addresses a ON JSON_VALUE(a."DATA", '$._id.customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
            )
            SELECT json {
              'rankingScore' : j.ranking_score,
              'ecn' : JSON_VALUE(j.identity_data, '$._id.customerNumber'),
              'companyId' : NVL(JSON_VALUE(j.identity_data, '$._id.customerCompanyNumber'), 1),
              'entityType' : JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator'),
              'name' : JSON_VALUE(j.identity_data, '$.common.fullName'),
              'alternateName' : CASE
                WHEN JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
                THEN JSON_VALUE(j.identity_data, '$.individual.firstName')
                ELSE JSON_VALUE(j.identity_data, '$.nonIndividual.businessDescriptionText')
              END,
              'taxIdNumber' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationNumber'),
              'taxIdType' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationType'),
              'birthDate' : JSON_VALUE(j.identity_data, '$.individual.dateOfBirth'),
              'addressLine' : JSON_VALUE(j.address_data, '$.addresses.addressLine1'),
              'cityName' : JSON_VALUE(j.address_data, '$.addresses.cityName'),
              'state' : JSON_VALUE(j.address_data, '$.addresses.stateCode'),
              'postalCode' : JSON_VALUE(j.address_data, '$.addresses.postalCode'),
              'countryCode' : NVL(JSON_VALUE(j.address_data, '$.addresses.countryCode'), 'US'),
              'customerType' : JSON_VALUE(j.identity_data, '$.common.customerType')
            }
            FROM joined j
            ORDER BY j.ranking_score DESC
            FETCH FIRST %d ROWS ONLY
            """.formatted(identity, escapeSql(email), account, escapeSql(accountLast4), address, limit);
    }

    /**
     * Builds the SQL query for UC-7: Email + Phone + Account Number.
     *
     * <p>Note: Each CTE with json_textcontains must use unique score labels (1, 2, 3)
     * to avoid view-merge conflicts per Oracle team guidance.
     */
    public String buildUC7Query(String email, String phoneNumber, String accountNumber, int limit) {
        String identity = collectionPrefix + "identity";
        String phone = collectionPrefix + "phone";
        String account = collectionPrefix + "account";
        String address = collectionPrefix + "address";

        return """
            WITH
            identities AS (
              SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) iscore
              FROM "%s"
              WHERE json_textcontains("DATA", '$."primaryEmail"', '%s', 1)
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
              JOIN phones p ON JSON_VALUE(p."DATA", '$.phoneKey.customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
              JOIN accounts ac ON JSON_VALUE(ac."DATA", '$.accountHolders[0].customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
              JOIN addresses a ON JSON_VALUE(a."DATA", '$._id.customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
            )
            SELECT json {
              'rankingScore' : j.combined_score,
              'ecn' : JSON_VALUE(j.identity_data, '$._id.customerNumber'),
              'companyId' : NVL(JSON_VALUE(j.identity_data, '$._id.customerCompanyNumber'), 1),
              'entityType' : JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator'),
              'name' : JSON_VALUE(j.identity_data, '$.common.fullName'),
              'alternateName' : CASE
                WHEN JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
                THEN JSON_VALUE(j.identity_data, '$.individual.firstName')
                ELSE JSON_VALUE(j.identity_data, '$.nonIndividual.businessDescriptionText')
              END,
              'taxIdNumber' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationNumber'),
              'taxIdType' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationType'),
              'birthDate' : JSON_VALUE(j.identity_data, '$.individual.dateOfBirth'),
              'addressLine' : JSON_VALUE(j.address_data, '$.addresses.addressLine1'),
              'cityName' : JSON_VALUE(j.address_data, '$.addresses.cityName'),
              'state' : JSON_VALUE(j.address_data, '$.addresses.stateCode'),
              'postalCode' : JSON_VALUE(j.address_data, '$.addresses.postalCode'),
              'countryCode' : NVL(JSON_VALUE(j.address_data, '$.addresses.countryCode'), 'US'),
              'customerType' : JSON_VALUE(j.identity_data, '$.common.customerType')
            }
            FROM joined j
            ORDER BY j.combined_score DESC
            FETCH FIRST %d ROWS ONLY
            """.formatted(identity, escapeSql(email), phone, escapeSql(phoneNumber),
                account, escapeSql(accountNumber), address, limit);
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
}
