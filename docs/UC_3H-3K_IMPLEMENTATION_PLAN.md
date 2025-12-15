# Implementation Plan: UC 3.H - 3.K (Other Searches)

## Overview

This document outlines the implementation plan for use cases 3.H through 3.K from the Wells Fargo Vector Search demonstration requirements. These are simpler "Other Searches" that return customer information from single-field or minimal-field searches.

---

## Requirements Summary

| UC | Description | Input | Output |
|----|-------------|-------|--------|
| **3.H** | Search by TIN (full 9-digit) | Full SSN/TIN (e.g., "855611007") | Customer details |
| **3.I** | Search by Account Number (with optional product type and COID) | Full account number + optional filters | Customer details |
| **3.J** | Search by Account Number (tokenized) | Tokenized account number (hyphenated format) | Customer details |
| **3.K** | Search by Phone Number (full 10-digit) | Full phone number | Customer details |

---

## Current Data Schema Analysis

### Identity Collection
```json
{
  "_id": { "customerNumber": 1000250004, "customerCompanyNumber": 1 },
  "common": {
    "taxIdentificationNumber": "855611007",  // Full 9-digit TIN
    "taxIdentificationTypeCode": "SSN",
    ...
  }
}
```

### Account Collection
```json
{
  "accountKey": {
    "customerNumber": 1000750001,
    "accountNumber": "100001125000",           // Full account number (12 digits)
    "accountNumberLast4": "5000",
    "accountNumberTokenized": "TOK-2812267249023"  // Current tokenization (needs change)
  },
  "productTypeCode": "CHECKING",               // For optional filter
  "companyOfInterestId": "WF_MORTGAGE",        // COID for optional filter
  ...
}
```

### Phone Collection
```json
{
  "phoneKey": {
    "customerNumber": 1000250004,
    "phoneNumber": "5549414620"                // 10-digit phone number
  }
}
```

---

## UC 3.H: Search by TIN (Full 9-digit)

### Description
Exact match search on full Tax Identification Number (SSN/TIN).

### Implementation Approach

**Option A: Exact Match with json_textcontains** (Recommended)
- Use `json_textcontains()` for Oracle Text index-based search
- Fast and consistent with existing UC patterns

**Query Pattern:**
```sql
WITH
identities AS (
  SELECT "DATA"
  FROM "identity"
  WHERE json_textcontains("DATA", '$."common"."taxIdentificationNumber"', '855611007', 1)
),
addresses AS (
  SELECT "DATA"
  FROM "address"
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
FETCH FIRST 10 ROWS ONLY
```

### Implementation Tasks
1. Add `buildUC3HQuery(String tin, int limit)` method to `MongoSqlSearchService.java`
2. Add CLI option `--uc3h-tin` to `MongoSqlSearchCommand.java`
3. Add test case to sample-data.json

---

## UC 3.I: Search by Account Number (with optional filters)

### Description
Search by full account number with optional product type and Company of Interest ID (COID) filters.

### Implementation Approach
- Exact match on full account number using `json_textcontains()`
- Add optional WHERE clause filters for productTypeCode and companyOfInterestId
- JOIN to identity and address for customer details

**Query Pattern:**
```sql
WITH
accounts AS (
  SELECT "DATA"
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumber"', '100001125000', 1)
    -- Optional filters (added dynamically):
    -- AND "DATA"."productTypeCode".string() = 'CHECKING'
    -- AND "DATA"."companyOfInterestId".string() = 'WF_MORTGAGE'
),
identities AS (
  SELECT "DATA"
  FROM "identity"
),
addresses AS (
  SELECT "DATA"
  FROM "address"
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
  'accountNumber' : j.account_data."accountKey"."accountNumber".string(),
  'productType' : j.account_data."productTypeCode".string(),
  'companyOfInterestId' : j.account_data."companyOfInterestId".string(),
  'name' : j.identity_data."common"."fullName".string(),
  ...
}
FROM joined j
FETCH FIRST 10 ROWS ONLY
```

### Implementation Tasks
1. Add `buildUC3IQuery(String accountNumber, String productType, String coid, int limit)` method
2. Add CLI options `--uc3i-account`, `--uc3i-product-type`, `--uc3i-coid`
3. Add test case

---

## UC 3.J: Search by Tokenized Account Number

### Description
Search by tokenized (hyphenated) account number format. This requires transforming the account number into a searchable tokenized format.

### Tokenization Strategy

**Current State:**
- `accountNumber`: "100001125000" (12 digits)
- `accountNumberTokenized`: "TOK-2812267249023" (opaque token - not useful for search)

**Required Tokenization:**
Transform account number into hyphenated segments for fuzzy/partial matching.

**Proposed Format Options:**

| Option | Format | Example | Search Capability |
|--------|--------|---------|-------------------|
| A | `XXXX-XXXX-XXXX` | `1000-0112-5000` | Segment search |
| B | `XXX-XXX-XXX-XXX` | `100-001-125-000` | Segment search |
| C | Space-separated | `1000 0112 5000` | Word search |

**Recommendation: Option A (4-4-4 format)**
- Matches credit card-style formatting
- Easy to parse and search
- Compatible with Oracle Text tokenization

### Implementation Approach

**Phase 1: Data Migration**
Create a new field `accountNumberHyphenated` in each account document:

```javascript
// Example transformation
accountNumber: "100001125000"
accountNumberHyphenated: "1000-0112-5000"
```

**Migration Script (SQL):**
```sql
-- Add hyphenated field to all account documents
UPDATE account
SET DATA = JSON_TRANSFORM(DATA,
  SET '$.accountKey.accountNumberHyphenated' =
    SUBSTR(JSON_VALUE(DATA, '$.accountKey.accountNumber'), 1, 4) || '-' ||
    SUBSTR(JSON_VALUE(DATA, '$.accountKey.accountNumber'), 5, 4) || '-' ||
    SUBSTR(JSON_VALUE(DATA, '$.accountKey.accountNumber'), 9, 4)
);
```

**Phase 2: Index Creation**
The existing JSON Search Index should automatically index the new field.

**Phase 3: Query Implementation**
```sql
WITH
accounts AS (
  SELECT "DATA"
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumberHyphenated"', '1000-0112-5000', 1)
),
...
```

### Alternative: Query-Time Transformation
If data migration is not desired, transform the search input at query time:

```sql
-- User inputs: "100001125000" or "1000-0112-5000"
-- Normalize and search both formats
WHERE json_textcontains("DATA", '$."accountKey"."accountNumber"', :normalizedInput, 1)
   OR json_textcontains("DATA", '$."accountKey"."accountNumberHyphenated"', :hyphenatedInput, 1)
```

### Implementation Tasks
1. **Data Migration:**
   - Add `accountNumberHyphenated` field computation to data generator
   - Create SQL migration script for existing data
   - Verify index includes new field

2. **Query Implementation:**
   - Add `buildUC3JQuery(String tokenizedAccountNumber, int limit)` method
   - Add input normalization (handle both formats)
   - Add CLI option `--uc3j-tokenized-account`

3. **Testing:**
   - Update sample-data.json with hyphenated format
   - Verify search works with partial segments

---

## UC 3.K: Search by Phone Number (Full 10-digit)

### Description
Exact match search on full 10-digit phone number.

### Implementation Approach
Similar to existing phone searches but optimized for exact match rather than fuzzy.

**Query Pattern:**
```sql
WITH
phones AS (
  SELECT "DATA"
  FROM "phone"
  WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', '5549414620', 1)
),
identities AS (
  SELECT "DATA"
  FROM "identity"
),
addresses AS (
  SELECT "DATA"
  FROM "address"
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
  'phoneNumber' : j.phone_data."phoneKey"."phoneNumber".string(),
  'name' : j.identity_data."common"."fullName".string(),
  ...
}
FROM joined j
FETCH FIRST 10 ROWS ONLY
```

### Implementation Tasks
1. Add `buildUC3KQuery(String phoneNumber, int limit)` method
2. Add CLI option `--uc3k-phone`
3. Add test case

---

## Implementation Summary

### Files to Modify

| File | Changes |
|------|---------|
| `MongoSqlSearchService.java` | Add 4 new query builder methods (buildUC3H, buildUC3I, buildUC3J, buildUC3K) |
| `MongoSqlSearchCommand.java` | Add CLI options and test execution |
| `sample-data.json` | Add test cases for UC 3.H-3.K |
| `AccountGenerator.java` | Add hyphenated account number field generation |
| `UC_UNIFIED_SUMMARY.md` | Document new use cases |

### Migration Script for UC 3.J
```sql
-- Run this once to add accountNumberHyphenated to existing accounts
UPDATE account
SET DATA = JSON_TRANSFORM(DATA,
  SET '$.accountKey.accountNumberHyphenated' =
    SUBSTR(JSON_VALUE(DATA, '$.accountKey.accountNumber'), 1, 4) || '-' ||
    SUBSTR(JSON_VALUE(DATA, '$.accountKey.accountNumber'), 5, 4) || '-' ||
    SUBSTR(JSON_VALUE(DATA, '$.accountKey.accountNumber'), 9, 4)
)
WHERE JSON_VALUE(DATA, '$.accountKey.accountNumberHyphenated') IS NULL;

COMMIT;

-- Verify
SELECT JSON_VALUE(DATA, '$.accountKey.accountNumber') as acct_num,
       JSON_VALUE(DATA, '$.accountKey.accountNumberHyphenated') as hyphenated
FROM account
WHERE ROWNUM <= 5;
```

### Test Data (sample-data.json additions)
```json
{
  "uc3h": {
    "description": "Search by TIN (full 9-digit)",
    "testCases": [
      {"tin": "855611007"}
    ]
  },
  "uc3i": {
    "description": "Search by Account Number (with optional product type and COID)",
    "testCases": [
      {"accountNumber": "100000375005"},
      {"accountNumber": "100000375005", "productType": "CHECKING"},
      {"accountNumber": "100000375005", "coid": "WF_MORTGAGE"}
    ]
  },
  "uc3j": {
    "description": "Search by Account Number (tokenized/hyphenated)",
    "testCases": [
      {"tokenizedAccount": "1000-0037-5005"}
    ]
  },
  "uc3k": {
    "description": "Search by Phone Number (full 10-digit)",
    "testCases": [
      {"phone": "5549414620"}
    ]
  }
}
```

---

## Questions for Review

1. **UC 3.J Tokenization Format:** Is `XXXX-XXXX-XXXX` (4-4-4) the preferred hyphenation format, or should we use a different pattern?

2. **UC 3.I Optional Filters:** Should productType and COID be AND conditions (both must match) or OR conditions (either matches)?

3. **Response Format:** Should these "Other Searches" return the same full customer response as UC 1-7, or a simplified subset?

4. **Scoring:** Since these are exact-match searches, should we still return a ranking score (always 100 for exact match) or omit scoring?

---

## Estimated Effort

| Task | Complexity |
|------|------------|
| UC 3.H (TIN search) | Low - Simple exact match |
| UC 3.I (Account + filters) | Low-Medium - Conditional filter logic |
| UC 3.J (Tokenized account) | Medium - Data migration + query |
| UC 3.K (Phone search) | Low - Simple exact match |
| Testing & Documentation | Low |

---

## Next Steps

1. Review and approve this implementation plan
2. Decide on tokenization format for UC 3.J
3. Run data migration for hyphenated account numbers
4. Implement query methods
5. Add CLI options and testing
6. Update documentation
