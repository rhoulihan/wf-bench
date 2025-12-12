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
            FROM %s
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
            FROM %s
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
     * Load sample UC query parameters from the database by joining identity, phone, account, and address collections.
     * Results are cached for subsequent calls.
     *
     * @param count Maximum number of parameter sets to load
     * @return List of [phone, ssnLast4, accountLast4, email, accountNumber, city, state, zip] arrays
     */
    public List<String[]> loadUcQueryParameters(int count) {
        if (cachedUcParams != null) {
            return cachedUcParams;
        }

        cachedUcParams = new ArrayList<>();

        // Query to join identity, phone, account, and address collections to get correlated UC parameters
        // Uses the collection prefix pattern: if collection is "bench_identity", derive others
        String identityTable = collection;
        String phoneTable = collection.endsWith("identity") ?
            collection.substring(0, collection.length() - 8) + "phone" : "phone";
        String accountTable = collection.endsWith("identity") ?
            collection.substring(0, collection.length() - 8) + "account" : "account";
        String addressTable = collection.endsWith("identity") ?
            collection.substring(0, collection.length() - 8) + "address" : "address";

        String sql = """
            SELECT
                json_value(p.DATA, '$.phoneKey.phoneNumber') as phone,
                json_value(i.DATA, '$.common.taxIdentificationNumberLast4') as ssn_last4,
                json_value(a.DATA, '$.accountKey.accountNumberLast4') as account_last4,
                json_value(i.DATA, '$.emails[0].emailAddress') as email,
                json_value(a.DATA, '$.accountKey.accountNumber') as account_number,
                json_value(addr.DATA, '$.cityName') as city,
                json_value(addr.DATA, '$.stateCode') as state,
                json_value(addr.DATA, '$.postalCode') as zip
            FROM %s i
            JOIN %s p ON json_value(i.DATA, '$._id.customerNumber') =
                         json_value(p.DATA, '$.phoneKey.customerNumber')
            JOIN %s a ON json_value(i.DATA, '$._id.customerNumber') =
                         json_value(a.DATA, '$.accountHolders[0].customerNumber')
            JOIN %s addr ON json_value(i.DATA, '$._id.customerNumber') =
                            json_value(addr.DATA, '$._id.customerNumber')
            WHERE json_value(p.DATA, '$.phoneKey.phoneNumber') IS NOT NULL
              AND json_value(i.DATA, '$.common.taxIdentificationNumberLast4') IS NOT NULL
              AND json_value(a.DATA, '$.accountKey.accountNumberLast4') IS NOT NULL
              AND json_value(i.DATA, '$.emails[0].emailAddress') IS NOT NULL
              AND json_value(a.DATA, '$.accountKey.accountNumber') IS NOT NULL
              AND json_value(addr.DATA, '$.cityName') IS NOT NULL
              AND json_value(addr.DATA, '$.stateCode') IS NOT NULL
              AND json_value(addr.DATA, '$.postalCode') IS NOT NULL
            FETCH FIRST ? ROWS ONLY
            """.formatted(identityTable, phoneTable, accountTable, addressTable);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
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
                    String zip = rs.getString("zip");

                    if (phone != null && ssnLast4 != null && accountLast4 != null &&
                        email != null && accountNumber != null &&
                        city != null && state != null && zip != null) {
                        cachedUcParams.add(new String[]{phone, ssnLast4, accountLast4, email, accountNumber, city, state, zip});
                    }
                }
            }
            log.debug("Loaded {} sample UC parameter sets from database", cachedUcParams.size());
        } catch (SQLException e) {
            log.warn("Failed to load sample UC parameters from database: {}. Using fallback.", e.getMessage());
            cachedUcParams = new ArrayList<>(FALLBACK_UC_PARAMS);
        }

        return cachedUcParams;
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
