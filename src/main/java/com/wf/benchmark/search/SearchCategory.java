package com.wf.benchmark.search;

import java.util.Set;

/**
 * Categories of search terms for UC queries.
 * A customer must have matches in ALL required categories to be included in results.
 *
 * <p>Each UC case requires specific categories:
 * <ul>
 *   <li>UC-1: PHONE + SSN_LAST4</li>
 *   <li>UC-2: PHONE + SSN_LAST4 + ACCOUNT_LAST4</li>
 *   <li>UC-3: PHONE + ACCOUNT_LAST4</li>
 *   <li>UC-4: ACCOUNT_NUMBER + SSN_LAST4</li>
 *   <li>UC-5: CITY + STATE + ZIP + SSN_LAST4 + ACCOUNT_LAST4</li>
 *   <li>UC-6: EMAIL + ACCOUNT_LAST4</li>
 *   <li>UC-7: EMAIL + PHONE + ACCOUNT_NUMBER</li>
 * </ul>
 */
public enum SearchCategory {
    PHONE,           // Phone number match (from phone collection)
    SSN_LAST4,       // SSN last 4 match (from identity collection)
    ACCOUNT_NUMBER,  // Full account number (from account collection)
    ACCOUNT_LAST4,   // Account last 4 digits (from account collection)
    EMAIL,           // Email address (from identity collection)
    CITY,            // City name (from address collection)
    STATE,           // State code (from address collection)
    ZIP;             // ZIP code (from address collection)

    /** UC-1 required categories: Phone + SSN Last 4 */
    public static final Set<SearchCategory> UC1_CATEGORIES = Set.of(PHONE, SSN_LAST4);

    /** UC-2 required categories: Phone + SSN Last 4 + Account Last 4 */
    public static final Set<SearchCategory> UC2_CATEGORIES = Set.of(PHONE, SSN_LAST4, ACCOUNT_LAST4);

    /** UC-3 required categories: Phone + Account Last 4 */
    public static final Set<SearchCategory> UC3_CATEGORIES = Set.of(PHONE, ACCOUNT_LAST4);

    /** UC-4 required categories: Account Number + SSN Last 4 */
    public static final Set<SearchCategory> UC4_CATEGORIES = Set.of(ACCOUNT_NUMBER, SSN_LAST4);

    /** UC-5 required categories: City/State/ZIP + SSN Last 4 + Account Last 4 */
    public static final Set<SearchCategory> UC5_CATEGORIES = Set.of(CITY, STATE, ZIP, SSN_LAST4, ACCOUNT_LAST4);

    /** UC-6 required categories: Email + Account Last 4 */
    public static final Set<SearchCategory> UC6_CATEGORIES = Set.of(EMAIL, ACCOUNT_LAST4);

    /** UC-7 required categories: Email + Phone + Account Number */
    public static final Set<SearchCategory> UC7_CATEGORIES = Set.of(EMAIL, PHONE, ACCOUNT_NUMBER);

    /**
     * Returns the required categories for a UC case number.
     *
     * @param ucNumber the UC case number (1-7)
     * @return the set of required categories
     * @throws IllegalArgumentException if ucNumber is not 1-7
     */
    public static Set<SearchCategory> forUcCase(int ucNumber) {
        return switch (ucNumber) {
            case 1 -> UC1_CATEGORIES;
            case 2 -> UC2_CATEGORIES;
            case 3 -> UC3_CATEGORIES;
            case 4 -> UC4_CATEGORIES;
            case 5 -> UC5_CATEGORIES;
            case 6 -> UC6_CATEGORIES;
            case 7 -> UC7_CATEGORIES;
            default -> throw new IllegalArgumentException("Invalid UC case number: " + ucNumber + ". Must be 1-7.");
        };
    }
}
