package com.wf.benchmark.search;

import java.util.*;

/**
 * Groups search hits by customer and tracks which search categories matched.
 *
 * <p>This class is used to aggregate search results from DBMS_SEARCH.FIND
 * and determine which customers have matches in all required categories
 * for a given UC case.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Parse DBMS_SEARCH.FIND results into SearchHit records</li>
 *   <li>Group hits by customerNumber using this class</li>
 *   <li>Filter groups that have all required categories for the UC case</li>
 *   <li>Calculate average score for ranking</li>
 *   <li>Fetch identity/address details for qualifying customers</li>
 * </ol>
 */
public class CustomerHitGroup {

    private final String customerNumber;
    private final List<SearchHit> hits = new ArrayList<>();
    private final Set<SearchCategory> matchedCategories = new HashSet<>();

    public CustomerHitGroup(String customerNumber) {
        this.customerNumber = customerNumber;
    }

    /**
     * Adds a search hit to this group and records its category.
     *
     * @param hit      the search hit
     * @param category the category this hit represents
     */
    public void addHit(SearchHit hit, SearchCategory category) {
        hits.add(hit);
        matchedCategories.add(category);
    }

    /**
     * Returns the customer number for this group.
     */
    public String getCustomerNumber() {
        return customerNumber;
    }

    /**
     * Returns an unmodifiable list of all hits in this group.
     */
    public List<SearchHit> getHits() {
        return Collections.unmodifiableList(hits);
    }

    /**
     * Returns an unmodifiable set of matched categories.
     */
    public Set<SearchCategory> getMatchedCategories() {
        return Collections.unmodifiableSet(matchedCategories);
    }

    /**
     * Checks if this group has matches in all required categories.
     *
     * @param required the set of required categories
     * @return true if all required categories are matched
     */
    public boolean hasAllCategories(Set<SearchCategory> required) {
        return matchedCategories.containsAll(required);
    }

    /**
     * Calculates the average score across all hits in this group.
     *
     * @return the average score, or 0.0 if no hits
     */
    public double getAverageScore() {
        if (hits.isEmpty()) return 0.0;
        return hits.stream().mapToDouble(SearchHit::score).average().orElse(0.0);
    }

    /**
     * Returns the total score (sum of all hit scores).
     *
     * @return the total score
     */
    public double getTotalScore() {
        return hits.stream().mapToDouble(SearchHit::score).sum();
    }

    /**
     * Returns the maximum score among all hits.
     *
     * @return the max score, or 0.0 if no hits
     */
    public double getMaxScore() {
        return hits.stream().mapToDouble(SearchHit::score).max().orElse(0.0);
    }

    /**
     * Returns the minimum score among all hits.
     *
     * @return the min score, or 0.0 if no hits
     */
    public double getMinScore() {
        return hits.stream().mapToDouble(SearchHit::score).min().orElse(0.0);
    }

    /**
     * Returns the number of hits in this group.
     */
    public int getHitCount() {
        return hits.size();
    }

    /**
     * Returns the number of unique categories matched.
     */
    public int getCategoryCount() {
        return matchedCategories.size();
    }

    @Override
    public String toString() {
        return "CustomerHitGroup{" +
                "customerNumber='" + customerNumber + '\'' +
                ", hitCount=" + hits.size() +
                ", categories=" + matchedCategories +
                ", avgScore=" + String.format("%.2f", getAverageScore()) +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CustomerHitGroup that = (CustomerHitGroup) o;
        return Objects.equals(customerNumber, that.customerNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(customerNumber);
    }

    /**
     * Groups a list of search hits by customer number.
     *
     * @param hits           the list of search hits to group
     * @param fieldToCategory mapping from matched field names to search categories
     * @return a map of customer number to CustomerHitGroup
     */
    public static Map<String, CustomerHitGroup> groupByCustomer(
            List<SearchHit> hits,
            Map<String, SearchCategory> fieldToCategory) {

        Map<String, CustomerHitGroup> groups = new HashMap<>();

        for (SearchHit hit : hits) {
            String custNum = hit.customerNumber();
            CustomerHitGroup group = groups.computeIfAbsent(custNum, CustomerHitGroup::new);

            SearchCategory category = fieldToCategory.get(hit.matchedField());
            if (category != null) {
                group.addHit(hit, category);
            }
        }

        return groups;
    }

    /**
     * Filters and sorts customer groups by required categories and score.
     *
     * @param groups           the groups to filter
     * @param requiredCategories the categories required for inclusion
     * @param limit            maximum number of results
     * @return sorted list of groups that have all required categories
     */
    public static List<CustomerHitGroup> filterAndSort(
            Map<String, CustomerHitGroup> groups,
            Set<SearchCategory> requiredCategories,
            int limit) {

        return groups.values().stream()
                .filter(g -> g.hasAllCategories(requiredCategories))
                .sorted(Comparator.comparingDouble(CustomerHitGroup::getAverageScore).reversed())
                .limit(limit)
                .toList();
    }
}
