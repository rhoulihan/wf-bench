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
 * Service for loading sample data from the database for benchmark tests.
 * Provides cached access to sample individual names and business names.
 */
public class SampleDataLoader {

    private static final Logger log = LoggerFactory.getLogger(SampleDataLoader.class);

    private static final List<String[]> FALLBACK_NAMES = List.of(
        new String[]{"JOHN", "SMITH"},
        new String[]{"MARY", "JOHNSON"}
    );

    private static final List<String> FALLBACK_BUSINESS_NAMES = List.of(
        "*ACME CORPORATION"
    );

    // Fallback UC parameters: [phone, ssnLast4, accountLast4, email, accountNumber, city, state, zip]
    private static final String[][] FALLBACK_UC_PARAMS_ARRAY = {
        {"5551234567", "6789", "7890", "john@example.com", "1234567890", "New York", "NY", "10001"}
    };
    private static final List<String[]> FALLBACK_UC_PARAMS = java.util.Arrays.asList(FALLBACK_UC_PARAMS_ARRAY);

    private final DataSource dataSource;
    private final String collection;

    // Cached sample data
    private List<String[]> cachedNames;
    private List<String> cachedBusinessNames;
    private List<String[]> cachedUcParams;

    public SampleDataLoader(DataSource dataSource, String collection) {
        this.dataSource = dataSource;
        this.collection = collection;
    }

    /**
     * Load sample individual names (firstName, lastName) from the database.
     * Results are cached for subsequent calls.
     *
     * @param count Maximum number of names to load
     * @return List of [firstName, lastName] pairs
     */
    public List<String[]> loadSampleNames(int count) {
        if (cachedNames != null) {
            return cachedNames;
        }

        cachedNames = new ArrayList<>();

        String sql = """
            SELECT
                json_value(DATA, '$.individual.firstName') as first_name,
                json_value(DATA, '$.individual.lastName') as last_name
            FROM "%s"
            WHERE json_value(DATA, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
            AND json_value(DATA, '$.individual.firstName') IS NOT NULL
            AND json_value(DATA, '$.individual.lastName') IS NOT NULL
            FETCH FIRST ? ROWS ONLY
            """.formatted(collection);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, count);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String firstName = rs.getString("first_name");
                    String lastName = rs.getString("last_name");
                    if (firstName != null && lastName != null) {
                        cachedNames.add(new String[]{firstName, lastName});
                    }
                }
            }
            log.debug("Loaded {} sample names from database", cachedNames.size());
        } catch (SQLException e) {
            log.warn("Failed to load sample names from database: {}. Using fallback names.", e.getMessage());
            cachedNames = new ArrayList<>(FALLBACK_NAMES);
        }

        return cachedNames;
    }

    /**
     * Load sample business names from the database.
     * Results are cached for subsequent calls.
     *
     * @param count Maximum number of names to load
     * @return List of business names
     */
    public List<String> loadSampleBusinessNames(int count) {
        if (cachedBusinessNames != null) {
            return cachedBusinessNames;
        }

        cachedBusinessNames = new ArrayList<>();

        String sql = """
            SELECT
                json_value(DATA, '$.common.fullName') as business_name
            FROM "%s"
            WHERE json_value(DATA, '$.common.entityTypeIndicator') = 'NON_INDIVIDUAL'
            AND json_value(DATA, '$.common.fullName') IS NOT NULL
            FETCH FIRST ? ROWS ONLY
            """.formatted(collection);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, count);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String businessName = rs.getString("business_name");
                    if (businessName != null) {
                        cachedBusinessNames.add(businessName);
                    }
                }
            }
            log.debug("Loaded {} sample business names from database", cachedBusinessNames.size());
        } catch (SQLException e) {
            log.warn("Failed to load sample business names from database: {}. Using fallback names.", e.getMessage());
            cachedBusinessNames = new ArrayList<>(FALLBACK_BUSINESS_NAMES);
        }

        return cachedBusinessNames;
    }

    /**
     * Get sample names as a 2D array for benchmark tests.
     *
     * @param count Maximum number of names to load
     * @return 2D array of [firstName, lastName] pairs
     */
    public String[][] getSampleNamesArray(int count) {
        List<String[]> names = loadSampleNames(count);
        if (names.isEmpty()) {
            return FALLBACK_NAMES.toArray(new String[0][]);
        }
        return names.toArray(new String[0][]);
    }

    /**
     * Get sample business names as an array for benchmark tests.
     *
     * @param count Maximum number of names to load
     * @return Array of business names
     */
    public String[] getSampleBusinessNamesArray(int count) {
        List<String> names = loadSampleBusinessNames(count);
        if (names.isEmpty()) {
            return FALLBACK_BUSINESS_NAMES.toArray(new String[0]);
        }
        return names.toArray(new String[0]);
    }

    /**
     * Load sample UC query parameters from the database using a JOIN query that finds
     * customers with correlated data across phone, identity, and account collections.
     * This ensures the sample data can actually produce matches in UC search queries.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Join phone, identity, and account collections on customerNumber</li>
     *   <li>Extract correlated values (phone, SSN last 4, account number, etc.)</li>
     *   <li>Optionally join address for city/state/zip (with fallback)</li>
     *   <li>Return parameter sets where all values belong to the same customer</li>
     * </ol>
     *
     * @param count Maximum number of parameter sets to load
     * @return List of [phone, ssnLast4, accountLast4, email, accountNumber, city, state, zip] arrays
     */
    public List<String[]> loadUcQueryParameters(int count) {
        if (cachedUcParams != null) {
            return cachedUcParams;
        }

        cachedUcParams = new ArrayList<>();

        // Derive collection names from identity collection
        String identityTable = collection;
        String phoneTable = deriveTableName("phone");
        String accountTable = deriveTableName("account");
        String addressTable = deriveTableName("address");

        try (Connection conn = dataSource.getConnection()) {
            log.info("Loading correlated UC sample data from collections: identity={}, phone={}, account={}, address={}",
                    identityTable, phoneTable, accountTable, addressTable);

            // First try: 4-way join (phone, identity, account, address) - all UC cases need these
            cachedUcParams = loadCorrelatedUcParams(conn, phoneTable, identityTable, accountTable, addressTable, count);

            if (!cachedUcParams.isEmpty()) {
                log.info("Loaded {} correlated UC parameter sets from 4-way join", cachedUcParams.size());
            } else {
                // Fallback: Load from identity only and generate synthetic phone/account values
                log.warn("No correlated data found via 3-way join. Trying identity-only fallback...");
                cachedUcParams = loadIdentityOnlyParams(conn, identityTable, count);
                if (!cachedUcParams.isEmpty()) {
                    log.info("Loaded {} UC parameter sets from identity-only fallback", cachedUcParams.size());
                }
            }

        } catch (SQLException e) {
            log.warn("Failed to load correlated UC parameters from database: {}. Using fallback.", e.getMessage());
        }

        // Use fallback if no data loaded
        if (cachedUcParams.isEmpty()) {
            log.warn("No UC parameters loaded from database, using fallback data");
            cachedUcParams = new ArrayList<>(FALLBACK_UC_PARAMS);
        }

        return cachedUcParams;
    }

    /**
     * Load correlated UC parameters using a 4-way JOIN on customerNumber.
     * This finds customers that have records in phone, identity, account, AND address collections.
     *
     * <p>Requires functional indexes on customerNumber fields for optimal performance:
     * <ul>
     *   <li>idx_phone_cust_num ON phone(json_value(DATA, '$.phoneKey.customerNumber'))</li>
     *   <li>idx_account_cust_num ON account(json_value(DATA, '$.accountHolders[0].customerNumber'))</li>
     *   <li>idx_address_cust_num ON address(json_value(DATA, '$._id.customerNumber'))</li>
     *   <li>idx_identity_cust_num ON identity(json_value(DATA, '$._id.customerNumber'))</li>
     * </ul>
     */
    private List<String[]> loadCorrelatedUcParams(Connection conn, String phoneTable,
            String identityTable, String accountTable, String addressTable, int count) throws SQLException {
        List<String[]> params = new ArrayList<>();

        // 4-way JOIN to find customers with phone, identity, account, and address data
        // Uses functional indexes on customerNumber for fast lookups
        String sql = """
            SELECT DISTINCT
                p.phone,
                i.ssn_last4,
                a.account_last4,
                i.email,
                a.account_number,
                i.customer_number,
                addr.city,
                addr.state,
                addr.postal_code
            FROM (
                SELECT
                    json_value(DATA, '$.phoneKey.customerNumber') as customer_number,
                    json_value(DATA, '$.phoneKey.phoneNumber') as phone
                FROM "%s"
                WHERE json_value(DATA, '$.phoneKey.phoneNumber') IS NOT NULL
                  AND json_value(DATA, '$.phoneKey.customerNumber') IS NOT NULL
            ) p
            JOIN (
                SELECT
                    json_value(DATA, '$._id.customerNumber') as customer_number,
                    json_value(DATA, '$.common.taxIdentificationNumberLast4') as ssn_last4,
                    json_value(DATA, '$.emails[0].emailAddress') as email
                FROM "%s"
                WHERE json_value(DATA, '$.common.taxIdentificationNumberLast4') IS NOT NULL
                  AND json_value(DATA, '$._id.customerNumber') IS NOT NULL
            ) i ON p.customer_number = i.customer_number
            JOIN (
                SELECT
                    json_value(DATA, '$.accountHolders[0].customerNumber') as customer_number,
                    json_value(DATA, '$.accountKey.accountNumberLast4') as account_last4,
                    json_value(DATA, '$.accountKey.accountNumber') as account_number
                FROM "%s"
                WHERE json_value(DATA, '$.accountKey.accountNumberLast4') IS NOT NULL
                  AND json_value(DATA, '$.accountKey.accountNumber') IS NOT NULL
                  AND json_value(DATA, '$.accountHolders[0].customerNumber') IS NOT NULL
            ) a ON p.customer_number = a.customer_number
            JOIN (
                SELECT
                    json_value(DATA, '$._id.customerNumber') as customer_number,
                    json_value(DATA, '$.addresses[0].cityName') as city,
                    json_value(DATA, '$.addresses[0].stateCode') as state,
                    json_value(DATA, '$.addresses[0].postalCode') as postal_code
                FROM "%s"
                WHERE json_value(DATA, '$.addresses[0].cityName') IS NOT NULL
                  AND json_value(DATA, '$.addresses[0].stateCode') IS NOT NULL
                  AND json_value(DATA, '$.addresses[0].postalCode') IS NOT NULL
                  AND json_value(DATA, '$._id.customerNumber') IS NOT NULL
            ) addr ON p.customer_number = addr.customer_number
            FETCH FIRST ? ROWS ONLY
            """.formatted(phoneTable, identityTable, accountTable, addressTable);

        log.debug("Executing correlated UC params query (4-way join with address)");

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(120); // 2 minute timeout
            stmt.setInt(1, count);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String phone = rs.getString("phone");
                    String ssnLast4 = rs.getString("ssn_last4");
                    String accountLast4 = rs.getString("account_last4");
                    String email = rs.getString("email");
                    String accountNumber = rs.getString("account_number");
                    String city = rs.getString("city");
                    String state = rs.getString("state");
                    String zip = rs.getString("postal_code");

                    // Use fallback values for optional fields
                    if (email == null) email = "test@example.com";

                    // Create parameter array: [phone, ssnLast4, accountLast4, email, accountNumber, city, state, zip]
                    params.add(new String[]{
                            phone,
                            ssnLast4,
                            accountLast4,
                            email,
                            accountNumber,
                            city,
                            state,
                            zip
                    });

                    log.debug("Loaded correlated params for customer: phone={}, ssn4={}, acct4={}, city={}, state={}, zip={}",
                            phone, ssnLast4, accountLast4, city, state, zip);
                }
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("timeout")) {
                log.warn("Query timed out, will use fallback data");
            } else {
                throw e;
            }
        }

        return params;
    }

    /**
     * Fallback: Load UC parameters from identity collection only.
     * Uses identity data (SSN, email) and generates synthetic phone/account values.
     * This is used when the 3-way JOIN returns no results.
     */
    private List<String[]> loadIdentityOnlyParams(Connection conn, String identityTable, int count) throws SQLException {
        List<String[]> params = new ArrayList<>();

        String sql = """
            SELECT
                json_value(DATA, '$._id.customerNumber') as customer_number,
                json_value(DATA, '$.common.taxIdentificationNumberLast4') as ssn_last4,
                json_value(DATA, '$.emails[0].emailAddress') as email
            FROM "%s"
            WHERE json_value(DATA, '$.common.taxIdentificationNumberLast4') IS NOT NULL
              AND json_value(DATA, '$._id.customerNumber') IS NOT NULL
            FETCH FIRST ? ROWS ONLY
            """.formatted(identityTable);

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, count);
            try (ResultSet rs = stmt.executeQuery()) {
                int seq = 0;
                while (rs.next()) {
                    String customerNumber = rs.getString("customer_number");
                    String ssnLast4 = rs.getString("ssn_last4");
                    String email = rs.getString("email");

                    if (email == null) email = "test@example.com";

                    // Generate synthetic but consistent phone and account values based on customerNumber
                    String phone = "555" + String.format("%07d", (Long.parseLong(customerNumber) % 10000000));
                    String accountLast4 = String.format("%04d", seq % 10000);
                    String accountNumber = "1000" + String.format("%08d", seq);

                    params.add(new String[]{
                            phone,
                            ssnLast4,
                            accountLast4,
                            email,
                            accountNumber,
                            "New York",   // city fallback
                            "NY",         // state fallback
                            "10001"       // zip fallback
                    });
                    seq++;
                }
            }
        }

        return params;
    }

    /**
     * Derive related table name from identity collection name.
     * Handles two cases:
     * 1. Collection has prefix (e.g., "bench_identity") -> derive "bench_phone"
     * 2. Collection is just "identity" -> derive "phone"
     */
    private String deriveTableName(String suffix) {
        if (collection.endsWith("identity")) {
            // Replace "identity" suffix with the desired suffix
            return collection.substring(0, collection.length() - 8) + suffix;
        }
        // Collection doesn't end with "identity", just return the suffix
        return suffix;
    }

    /**
     * Get sample UC query parameters as a 2D array for benchmark tests.
     *
     * @param count Maximum number of parameter sets to load
     * @return 2D array of [phone, ssnLast4, accountLast4, email, accountNumber, city, state, zip] arrays
     */
    public String[][] getUcQueryParametersArray(int count) {
        List<String[]> params = loadUcQueryParameters(count);
        if (params.isEmpty()) {
            return FALLBACK_UC_PARAMS.toArray(new String[0][]);
        }
        return params.toArray(new String[0][]);
    }

    /**
     * Clear the cached data to force a reload on next request.
     */
    public void clearCache() {
        cachedNames = null;
        cachedBusinessNames = null;
        cachedUcParams = null;
    }
}
