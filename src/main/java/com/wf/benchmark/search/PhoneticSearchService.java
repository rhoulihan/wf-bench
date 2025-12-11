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
import java.util.Map;
import java.util.Set;

/**
 * Service for phonetic name search using Oracle SOUNDEX function.
 * SOUNDEX encodes names based on pronunciation, enabling matching of
 * names that sound similar but are spelled differently.
 *
 * Examples of SOUNDEX matching:
 * - "Sally" and "Sallie" both encode to S400
 * - "Smith" and "Smyth" both encode to S530
 * - "Catherine" and "Katherine" both encode to C365
 *
 * This service queries JSON data stored in Oracle Autonomous JSON Database
 * using SQL with json_value() to extract fields from JSON documents.
 */
public class PhoneticSearchService {

    private static final Logger log = LoggerFactory.getLogger(PhoneticSearchService.class);

    private final DataSource dataSource;

    /**
     * Common nickname mappings for name expansion.
     * Maps nicknames to their formal names and vice versa.
     */
    private static final Map<String, Set<String>> NICKNAME_MAP = Map.ofEntries(
        Map.entry("PEGGY", Set.of("MARGARET", "MARGE", "MARGIE")),
        Map.entry("MARGARET", Set.of("PEGGY", "MARGE", "MARGIE", "MEG", "MAGGIE")),
        Map.entry("BILL", Set.of("WILLIAM", "WILL", "BILLY")),
        Map.entry("WILLIAM", Set.of("BILL", "WILL", "BILLY", "LIAM")),
        Map.entry("BOB", Set.of("ROBERT", "ROB", "BOBBY")),
        Map.entry("ROBERT", Set.of("BOB", "ROB", "BOBBY", "BERT")),
        Map.entry("DICK", Set.of("RICHARD", "RICK", "RICH")),
        Map.entry("RICHARD", Set.of("DICK", "RICK", "RICH", "RICHIE")),
        Map.entry("JACK", Set.of("JOHN", "JOHNNY")),
        Map.entry("JOHN", Set.of("JACK", "JOHNNY", "JON")),
        Map.entry("JIM", Set.of("JAMES", "JIMMY", "JAMIE")),
        Map.entry("JAMES", Set.of("JIM", "JIMMY", "JAMIE")),
        Map.entry("KATE", Set.of("KATHERINE", "CATHERINE", "KATHY", "CATHY")),
        Map.entry("KATHERINE", Set.of("KATE", "KATHY", "KAT", "KATIE")),
        Map.entry("CATHERINE", Set.of("KATE", "CATHY", "CAT", "KATIE")),
        Map.entry("MIKE", Set.of("MICHAEL", "MICK", "MICKEY")),
        Map.entry("MICHAEL", Set.of("MIKE", "MICK", "MICKEY")),
        Map.entry("TOM", Set.of("THOMAS", "TOMMY")),
        Map.entry("THOMAS", Set.of("TOM", "TOMMY")),
        Map.entry("BETH", Set.of("ELIZABETH", "BETTY", "LIZ", "LIZZY")),
        Map.entry("ELIZABETH", Set.of("BETH", "BETTY", "LIZ", "LIZZY", "ELIZA")),
        Map.entry("SALLY", Set.of("SARAH", "SARA")),
        Map.entry("SARAH", Set.of("SALLY", "SARA")),
        Map.entry("TONY", Set.of("ANTHONY", "ANT")),
        Map.entry("ANTHONY", Set.of("TONY", "ANT")),
        Map.entry("STEVE", Set.of("STEVEN", "STEPHEN")),
        Map.entry("STEVEN", Set.of("STEVE", "STEPHEN")),
        Map.entry("STEPHEN", Set.of("STEVE", "STEVEN"))
    );

    public PhoneticSearchService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Search for names using SOUNDEX phonetic matching.
     * Matches names that sound similar even if spelled differently.
     *
     * @param firstName First name to search for
     * @param lastName Last name to search for
     * @param collection The collection/table name
     * @param limit Maximum number of results to return
     * @return List of matching results with SOUNDEX codes
     */
    public List<PhoneticSearchResult> searchByName(String firstName, String lastName, String collection, int limit) {
        validateSearchParams(firstName, lastName, collection);

        String sql = buildPhoneticSearchQuery(collection, limit);

        log.debug("Executing phonetic search: firstName='{}', lastName='{}', collection='{}', limit={}",
                  firstName, lastName, collection, limit);

        return executeSearch(sql, firstName, lastName);
    }

    /**
     * Search for names using SOUNDEX with nickname expansion.
     * Expands the search to include common nicknames and formal names.
     *
     * @param firstName First name to search for (may be nickname or formal name)
     * @param lastName Last name to search for
     * @param collection The collection/table name
     * @param limit Maximum number of results to return
     * @return List of matching results
     */
    public List<PhoneticSearchResult> searchByNameWithNicknames(
            String firstName, String lastName, String collection, int limit) {
        validateSearchParams(firstName, lastName, collection);

        // Get all name variants (including original)
        Set<String> firstNameVariants = getNicknameVariants(firstName);

        String sql = buildPhoneticSearchQueryWithNicknames(collection, firstNameVariants.size(), limit);

        log.debug("Executing phonetic search with nicknames: firstName='{}', variants={}, lastName='{}', collection='{}', limit={}",
                  firstName, firstNameVariants, lastName, collection, limit);

        return executeSearchWithNicknames(sql, firstNameVariants, lastName);
    }

    /**
     * Build the SQL query for phonetic search using SOUNDEX.
     * Note: MongoDB API for Oracle stores JSON in a column named DATA.
     */
    private String buildPhoneticSearchQuery(String collection, int limit) {
        return """
            SELECT
                json_value(DATA, '$._id.customerNumber') as customer_number,
                json_value(DATA, '$.common.fullName') as full_name,
                SOUNDEX(json_value(DATA, '$.individual.firstName')) || '-' ||
                SOUNDEX(json_value(DATA, '$.individual.lastName')) as soundex_code
            FROM %s
            WHERE SOUNDEX(json_value(DATA, '$.individual.firstName')) = SOUNDEX(?)
              AND SOUNDEX(json_value(DATA, '$.individual.lastName')) = SOUNDEX(?)
            ORDER BY json_value(DATA, '$.common.fullName')
            FETCH FIRST ? ROWS ONLY
            """.formatted(collection);
    }

    /**
     * Build the SQL query for phonetic search with multiple nickname variants.
     * Note: MongoDB API for Oracle stores JSON in a column named DATA.
     */
    private String buildPhoneticSearchQueryWithNicknames(String collection, int variantCount, int limit) {
        // Build IN clause with placeholders for all variants
        StringBuilder soundexIn = new StringBuilder();
        for (int i = 0; i < variantCount; i++) {
            if (i > 0) soundexIn.append(", ");
            soundexIn.append("SOUNDEX(?)");
        }

        return """
            SELECT
                json_value(DATA, '$._id.customerNumber') as customer_number,
                json_value(DATA, '$.common.fullName') as full_name,
                SOUNDEX(json_value(DATA, '$.individual.firstName')) || '-' ||
                SOUNDEX(json_value(DATA, '$.individual.lastName')) as soundex_code
            FROM %s
            WHERE SOUNDEX(json_value(DATA, '$.individual.firstName')) IN (%s)
              AND SOUNDEX(json_value(DATA, '$.individual.lastName')) = SOUNDEX(?)
            ORDER BY json_value(DATA, '$.common.fullName')
            FETCH FIRST ? ROWS ONLY
            """.formatted(collection, soundexIn.toString());
    }

    /**
     * Get nickname variants for a given first name.
     * Returns a set including the original name plus any known variants.
     */
    private Set<String> getNicknameVariants(String firstName) {
        String upperName = firstName.toUpperCase();
        Set<String> variants = new java.util.HashSet<>();
        variants.add(firstName); // Always include original

        if (NICKNAME_MAP.containsKey(upperName)) {
            for (String variant : NICKNAME_MAP.get(upperName)) {
                // Add with original case style
                if (Character.isUpperCase(firstName.charAt(0))) {
                    variants.add(variant.charAt(0) + variant.substring(1).toLowerCase());
                } else {
                    variants.add(variant.toLowerCase());
                }
            }
        }

        return variants;
    }

    /**
     * Execute the basic phonetic search query.
     */
    private List<PhoneticSearchResult> executeSearch(String sql, String firstName, String lastName) {
        List<PhoneticSearchResult> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, firstName);
            stmt.setString(2, lastName);
            stmt.setInt(3, 100); // limit

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String customerNumber = rs.getString("customer_number");
                    String fullName = rs.getString("full_name");
                    String soundexCode = rs.getString("soundex_code");

                    results.add(new PhoneticSearchResult(customerNumber, fullName, soundexCode));
                }
            }

        } catch (SQLException e) {
            log.error("Phonetic search failed: {}", e.getMessage(), e);
            throw new SearchException("Phonetic search failed", e);
        }

        log.debug("Phonetic search returned {} results", results.size());
        return results;
    }

    /**
     * Execute phonetic search with nickname variants.
     */
    private List<PhoneticSearchResult> executeSearchWithNicknames(
            String sql, Set<String> firstNameVariants, String lastName) {
        List<PhoneticSearchResult> results = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            int paramIndex = 1;
            for (String variant : firstNameVariants) {
                stmt.setString(paramIndex++, variant);
            }
            stmt.setString(paramIndex++, lastName);
            stmt.setInt(paramIndex, 100); // limit

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String customerNumber = rs.getString("customer_number");
                    String fullName = rs.getString("full_name");
                    String soundexCode = rs.getString("soundex_code");

                    results.add(new PhoneticSearchResult(customerNumber, fullName, soundexCode));
                }
            }

        } catch (SQLException e) {
            log.error("Phonetic search with nicknames failed: {}", e.getMessage(), e);
            throw new SearchException("Phonetic search with nicknames failed", e);
        }

        log.debug("Phonetic search with nicknames returned {} results", results.size());
        return results;
    }

    /**
     * Validate search parameters.
     */
    private void validateSearchParams(String firstName, String lastName, String collection) {
        if (firstName == null) {
            throw new IllegalArgumentException("First name cannot be null");
        }
        if (firstName.isEmpty()) {
            throw new IllegalArgumentException("First name cannot be empty");
        }
        if (lastName == null) {
            throw new IllegalArgumentException("Last name cannot be null");
        }
        if (lastName.isEmpty()) {
            throw new IllegalArgumentException("Last name cannot be empty");
        }
        if (collection == null) {
            throw new IllegalArgumentException("Collection cannot be null");
        }
    }
}
