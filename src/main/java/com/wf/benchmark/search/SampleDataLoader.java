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

    private final DataSource dataSource;
    private final String collection;

    // Cached sample data
    private List<String[]> cachedNames;
    private List<String> cachedBusinessNames;

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
     * Clear the cached data to force a reload on next request.
     */
    public void clearCache() {
        cachedNames = null;
        cachedBusinessNames = null;
    }
}
