package com.wf.benchmark.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service that orchestrates multiple search strategies to provide hybrid search.
 *
 * Combines results from:
 * - FuzzySearchService (Oracle Text CONTAINS with FUZZY)
 * - PhoneticSearchService (SOUNDEX with nickname expansion)
 * - VectorSearchService (Oracle AI Vector Search)
 *
 * Results are deduplicated, merged when the same customer is found by multiple
 * strategies, and ranked by the highest score.
 */
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private final FuzzySearchService fuzzySearchService;
    private final PhoneticSearchService phoneticSearchService;
    private final VectorSearchService vectorSearchService;

    // Configuration flags
    private boolean fuzzyEnabled = true;
    private boolean phoneticEnabled = true;
    private boolean vectorEnabled = true;

    public HybridSearchService(FuzzySearchService fuzzySearchService,
                               PhoneticSearchService phoneticSearchService,
                               VectorSearchService vectorSearchService) {
        this.fuzzySearchService = fuzzySearchService;
        this.phoneticSearchService = phoneticSearchService;
        this.vectorSearchService = vectorSearchService;
    }

    /**
     * Search by name using fuzzy and phonetic strategies.
     * Combines results from both strategies, removes duplicates, and ranks by score.
     *
     * @param firstName First name to search for
     * @param lastName Last name to search for
     * @param collection The collection/table name
     * @param limit Maximum number of results
     * @return Combined and ranked results
     */
    public List<HybridSearchResult> searchByName(String firstName, String lastName,
                                                  String collection, int limit) {
        validateNameParams(firstName, lastName, collection);

        log.debug("Executing hybrid name search: firstName='{}', lastName='{}', collection='{}', limit={}",
                  firstName, lastName, collection, limit);

        Map<String, HybridSearchResult> resultMap = new HashMap<>();

        // Execute fuzzy search
        if (fuzzyEnabled) {
            try {
                String fullName = firstName + " " + lastName;
                List<FuzzySearchResult> fuzzyResults = fuzzySearchService.searchByName(
                    fullName, collection, limit * 2); // Get more to allow for merging

                for (FuzzySearchResult r : fuzzyResults) {
                    HybridSearchResult hybrid = HybridSearchResult.fromFuzzy(
                        r.getCustomerNumber(), r.getMatchedValue(), normalizeScore(r.getScore()));
                    mergeResult(resultMap, hybrid);
                }
            } catch (Exception e) {
                log.warn("Fuzzy search failed, continuing with other strategies: {}", e.getMessage());
            }
        }

        // Execute phonetic search with nickname expansion
        if (phoneticEnabled) {
            try {
                List<PhoneticSearchResult> phoneticResults = phoneticSearchService.searchByNameWithNicknames(
                    firstName, lastName, collection, limit * 2);

                for (PhoneticSearchResult r : phoneticResults) {
                    // Phonetic matches are binary (match or no match), use fixed score
                    HybridSearchResult hybrid = HybridSearchResult.fromPhonetic(
                        r.getCustomerNumber(), r.getMatchedValue(), 0.8);
                    mergeResult(resultMap, hybrid);
                }
            } catch (Exception e) {
                log.warn("Phonetic search failed, continuing with other strategies: {}", e.getMessage());
            }
        }

        return sortAndLimit(resultMap, limit);
    }

    /**
     * Search by natural language description using vector similarity.
     *
     * @param description Natural language description
     * @param collection The collection/table name
     * @param limit Maximum number of results
     * @return Vector search results
     */
    public List<HybridSearchResult> searchByDescription(String description, String collection, int limit) {
        if (description == null) {
            throw new IllegalArgumentException("Description cannot be null");
        }
        if (collection == null) {
            throw new IllegalArgumentException("Collection cannot be null");
        }

        log.debug("Executing semantic description search: description='{}', collection='{}', limit={}",
                  description, collection, limit);

        Map<String, HybridSearchResult> resultMap = new HashMap<>();

        if (vectorEnabled) {
            try {
                List<VectorSearchResult> vectorResults = vectorSearchService.searchByDescription(
                    description, collection, limit);

                for (VectorSearchResult r : vectorResults) {
                    HybridSearchResult hybrid = HybridSearchResult.fromVector(
                        r.getCustomerNumber(), r.getMatchedValue(), r.getSimilarityScore());
                    mergeResult(resultMap, hybrid);
                }
            } catch (Exception e) {
                log.warn("Vector search failed: {}", e.getMessage());
                throw new SearchException("Vector search failed", e);
            }
        }

        return sortAndLimit(resultMap, limit);
    }

    /**
     * Search by business description using both fuzzy text and vector similarity.
     *
     * @param businessName Business name or description
     * @param collection The collection/table name
     * @param limit Maximum number of results
     * @return Combined results
     */
    public List<HybridSearchResult> searchByBusinessDescription(String businessName,
                                                                 String collection, int limit) {
        if (businessName == null) {
            throw new IllegalArgumentException("Business name cannot be null");
        }
        if (collection == null) {
            throw new IllegalArgumentException("Collection cannot be null");
        }

        log.debug("Executing business description search: businessName='{}', collection='{}', limit={}",
                  businessName, collection, limit);

        Map<String, HybridSearchResult> resultMap = new HashMap<>();

        // Execute fuzzy business name search
        if (fuzzyEnabled) {
            try {
                List<FuzzySearchResult> fuzzyResults = fuzzySearchService.searchByBusinessName(
                    businessName, collection, limit * 2);

                for (FuzzySearchResult r : fuzzyResults) {
                    HybridSearchResult hybrid = HybridSearchResult.fromFuzzy(
                        r.getCustomerNumber(), r.getMatchedValue(), normalizeScore(r.getScore()));
                    mergeResult(resultMap, hybrid);
                }
            } catch (Exception e) {
                log.warn("Fuzzy business search failed, continuing with vector: {}", e.getMessage());
            }
        }

        // Execute vector similarity search
        if (vectorEnabled) {
            try {
                List<VectorSearchResult> vectorResults = vectorSearchService.searchByDescription(
                    businessName, collection, limit * 2);

                for (VectorSearchResult r : vectorResults) {
                    HybridSearchResult hybrid = HybridSearchResult.fromVector(
                        r.getCustomerNumber(), r.getMatchedValue(), r.getSimilarityScore());
                    mergeResult(resultMap, hybrid);
                }
            } catch (Exception e) {
                log.warn("Vector search failed, continuing with fuzzy results: {}", e.getMessage());
            }
        }

        return sortAndLimit(resultMap, limit);
    }

    /**
     * Find customers similar to a reference customer using vector similarity.
     *
     * @param customerNumber Reference customer number
     * @param collection The collection/table name
     * @param limit Maximum number of results
     * @return Similar customers
     */
    public List<HybridSearchResult> findSimilarCustomers(String customerNumber,
                                                          String collection, int limit) {
        if (customerNumber == null) {
            throw new IllegalArgumentException("Customer number cannot be null");
        }
        if (collection == null) {
            throw new IllegalArgumentException("Collection cannot be null");
        }

        log.debug("Finding similar customers: customerNumber='{}', collection='{}', limit={}",
                  customerNumber, collection, limit);

        Map<String, HybridSearchResult> resultMap = new HashMap<>();

        if (vectorEnabled) {
            try {
                List<VectorSearchResult> vectorResults = vectorSearchService.findSimilarToCustomer(
                    customerNumber, collection, limit);

                for (VectorSearchResult r : vectorResults) {
                    HybridSearchResult hybrid = HybridSearchResult.fromVector(
                        r.getCustomerNumber(), r.getMatchedValue(), r.getSimilarityScore());
                    mergeResult(resultMap, hybrid);
                }
            } catch (Exception e) {
                log.warn("Similar customer search failed: {}", e.getMessage());
                throw new SearchException("Similar customer search failed", e);
            }
        }

        return sortAndLimit(resultMap, limit);
    }

    /**
     * Merge a result into the result map, combining strategies if same customer.
     */
    private void mergeResult(Map<String, HybridSearchResult> resultMap, HybridSearchResult newResult) {
        String key = newResult.getCustomerNumber();
        if (resultMap.containsKey(key)) {
            HybridSearchResult existing = resultMap.get(key);
            resultMap.put(key, existing.mergeWith(newResult));
        } else {
            resultMap.put(key, newResult);
        }
    }

    /**
     * Sort results by score descending and limit to requested count.
     */
    private List<HybridSearchResult> sortAndLimit(Map<String, HybridSearchResult> resultMap, int limit) {
        List<HybridSearchResult> results = new ArrayList<>(resultMap.values());
        results.sort(Comparator.comparingDouble(HybridSearchResult::getScore).reversed());

        if (results.size() > limit) {
            return results.subList(0, limit);
        }
        return results;
    }

    /**
     * Normalize Oracle Text score (0-100) to 0-1 range for consistency with vector scores.
     */
    private double normalizeScore(double oracleTextScore) {
        return oracleTextScore / 100.0;
    }

    /**
     * Validate name search parameters.
     */
    private void validateNameParams(String firstName, String lastName, String collection) {
        if (firstName == null) {
            throw new IllegalArgumentException("First name cannot be null");
        }
        if (lastName == null) {
            throw new IllegalArgumentException("Last name cannot be null");
        }
        if (collection == null) {
            throw new IllegalArgumentException("Collection cannot be null");
        }
    }

    // Configuration getters and setters

    public boolean isFuzzyEnabled() {
        return fuzzyEnabled;
    }

    public void setFuzzyEnabled(boolean fuzzyEnabled) {
        this.fuzzyEnabled = fuzzyEnabled;
    }

    public boolean isPhoneticEnabled() {
        return phoneticEnabled;
    }

    public void setPhoneticEnabled(boolean phoneticEnabled) {
        this.phoneticEnabled = phoneticEnabled;
    }

    public boolean isVectorEnabled() {
        return vectorEnabled;
    }

    public void setVectorEnabled(boolean vectorEnabled) {
        this.vectorEnabled = vectorEnabled;
    }

    public int getFuzzyMinScore() {
        return fuzzySearchService.getMinScore();
    }

    public void setFuzzyMinScore(int minScore) {
        fuzzySearchService.setMinScore(minScore);
    }

    public double getVectorMinSimilarity() {
        return vectorSearchService.getMinSimilarity();
    }

    public void setVectorMinSimilarity(double minSimilarity) {
        vectorSearchService.setMinSimilarity(minSimilarity);
    }
}
