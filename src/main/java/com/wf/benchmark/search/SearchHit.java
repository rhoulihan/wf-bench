package com.wf.benchmark.search;

/**
 * Represents a single hit from DBMS_SEARCH.FIND unified index query.
 * Each hit contains source table info, customer reference, matched field, and relevance score.
 *
 * <p>This record is used to capture raw search results before grouping and filtering
 * by customer number and required categories.
 *
 * @param sourceTable    the collection name (identity, phone, account, address)
 * @param customerNumber the customer this hit relates to
 * @param matchedField   the field that matched (e.g., phoneNumber, ssnLast4, email)
 * @param matchedValue   the actual value that matched
 * @param score          relevance score from Oracle Text (0-100)
 */
public record SearchHit(
    String sourceTable,
    String customerNumber,
    String matchedField,
    String matchedValue,
    double score
) {}
