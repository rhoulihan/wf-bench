# UC Unified Search Summary

## Overview

This document provides a comprehensive summary of the UC 1-7 unified search implementation using MongoDB `$sql` operator with `json_textcontains()` for fuzzy matching on Oracle Autonomous Database 23ai.

**Test Date:** December 13, 2025
**Database:** Oracle Autonomous Database 23ai (wellsfargo_low service)
**Region:** US-Ashburn-1

---

## Configuration

### Database Connection
- **MongoDB Connection:** `mongodb://admin:***@host:27017/[user]?authMechanism=PLAIN&authSource=$external&ssl=true&retryWrites=false&loadBalanced=true`
- **Service Level:** LOW (shared resources)

### Benchmark Parameters
- **Iterations:** 20
- **Warmup Iterations:** 5
- **Result Limit:** 10 documents per query

### Collections
| Collection | Description | Key Fields |
|------------|-------------|------------|
| `identity` | Customer identity records | customerNumber, fullName, taxIdentificationNumber, primaryEmail |
| `phone` | Phone number records | phoneKey.customerNumber, phoneKey.phoneNumber |
| `account` | Account records | accountHolders[0].customerNumber, accountKey.accountNumber, accountKey.accountNumberLast4 |
| `address` | Address records (array per customer) | _id.customerNumber, addresses[].cityName, addresses[].stateCode, addresses[].postalCode |

### Indexes
Full JSON search indexes created for Oracle Text operations:
```sql
CREATE SEARCH INDEX idx_identity_search ON identity(DATA) FOR JSON;
CREATE SEARCH INDEX idx_phone_search ON phone(DATA) FOR JSON;
CREATE SEARCH INDEX idx_account_search ON account(DATA) FOR JSON;
CREATE SEARCH INDEX idx_address_search ON address(DATA) FOR JSON;
```

Functional indexes on customerNumber fields for JOIN optimization:
```sql
CREATE INDEX idx_phone_cust_num ON phone(json_value(DATA, '$.phoneKey.customerNumber'));
CREATE INDEX idx_account_cust_num ON account(json_value(DATA, '$.accountHolders[0].customerNumber'));
CREATE INDEX idx_address_cust_num ON address(json_value(DATA, '$._id.customerNumber'));
CREATE INDEX idx_identity_cust_num ON identity(json_value(DATA, '$._id.customerNumber'));
```

---

## Implementation Approach

### Key Design Decisions

1. **Fuzzy Matching on ALL Conditions**: Every search condition uses `json_textcontains()` for fuzzy matching, not just the primary search field.

2. **Combined Relevance Scores**: Multiple fuzzy matches are averaged into a single `ranking_score`:
   ```sql
   (p.pscore + i.iscore + ac.ascore) / 3 ranking_score
   ```

3. **INNER JOIN for Addresses**: All queries require customers to have at least one address (no LEFT JOIN).

4. **Ends-With Pattern for Partial Matches**: SSN and account "last 4" searches use `%term` pattern to match the end of the full field value.

5. **Full TIN Field**: Queries search on `taxIdentificationNumber` (full SSN/EIN), not a separate last4 field.

---

## Use Cases (UC 1-7)

### UC-1: Phone + SSN (ends-with)
**Description:** Search for customers by phone number and SSN (ends-with matching)
**Collections:** phone, identity, address
**Fuzzy Conditions:** Phone number, SSN (ends-with)
**Score:** Average of phone score + SSN score

**Query Pattern (MongoDB $sql):**
```sql
WITH
phones AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) pscore
  FROM "phone"
  WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', 'term', 1)
  ORDER BY score(1) DESC
),
identities AS (
  SELECT "DATA", score(2) iscore
  FROM "identity"
  WHERE json_textcontains("DATA", '$."common"."taxIdentificationNumber"', '%term', 2)
),
addresses AS (
  SELECT "DATA"
  FROM "address"
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
SELECT json {...}
FROM joined j
ORDER BY j.ranking_score DESC
FETCH FIRST ? ROWS ONLY
```

---

### UC-2: Phone + SSN + Account Last 4
**Description:** Search for customers by phone, SSN (ends-with), and account last 4 (ends-with)
**Collections:** phone, identity, account, address
**Fuzzy Conditions:** Phone number, SSN (ends-with), Account last 4 (ends-with)
**Score:** Average of phone + SSN + account scores

---

### UC-3: Phone + Account Last 4
**Description:** Search for customers by phone number and account last 4 (ends-with)
**Collections:** phone, identity, account, address
**Fuzzy Conditions:** Phone number, Account last 4 (ends-with)
**Score:** Average of phone + account scores

---

### UC-4: Account Number + SSN
**Description:** Search for customers by full account number and SSN (ends-with)
**Collections:** account, identity, address
**Fuzzy Conditions:** Account number, SSN (ends-with)
**Score:** Average of account + SSN scores

---

### UC-5: City/State/ZIP + SSN + Account Last 4
**Description:** Search for customers by geographic location, SSN (ends-with), and account last 4 (ends-with)
**Collections:** address, identity, account
**Fuzzy Conditions:** City name, SSN (ends-with), Account last 4 (ends-with)
**Score:** Average of city + SSN + account scores

**Note:** Use `$.addresses.cityName` (without array index) - matches any element in the addresses array. Array index syntax like `$.addresses[0].cityName` fails with ORA-40469.

---

### UC-6: Email + Account Last 4
**Description:** Search for customers by email address and account last 4 (ends-with)
**Collections:** identity, account, address
**Fuzzy Conditions:** Email address, Account last 4 (ends-with)
**Score:** Average of email + account scores

---

### UC-7: Email + Phone + Account Number
**Description:** Search for customers by email, phone, and full account number
**Collections:** identity, phone, account, address
**Fuzzy Conditions:** Email address, Phone number, Account number
**Score:** Average of email + phone + account scores

---

## Response Format

Each UC query returns results in the following format:

| Field | Type | Description |
|-------|------|-------------|
| `rankingScore` | int | Combined relevance score (average of all fuzzy matches) |
| `ecn` | String | Enterprise Customer Number (customerNumber) |
| `companyId` | int | Customer Company Number |
| `entityType` | String | INDIVIDUAL or NON_INDIVIDUAL |
| `name` | String | Full name of customer |
| `alternateName` | String | First name (individual) or business description (non-individual) |
| `taxIdNumber` | String | Full SSN/EIN (masked in display) |
| `taxIdType` | String | SSN, EIN, or ITIN |
| `birthDate` | String | Date of birth (individuals only) |
| `addressLine` | String | Street address |
| `cityName` | String | City |
| `state` | String | State code |
| `postalCode` | String | ZIP code |
| `countryCode` | String | Country code (defaults to US) |
| `customerType` | String | Customer, Prospect, or Youth Banking |

---

## Benchmark Results (December 13, 2025)

### Test Configuration
- **Implementation:** MongoDB $sql operator with json_textcontains()
- **Fuzzy Matching:** ALL search conditions use fuzzy matching
- **Score Calculation:** Combined average of all fuzzy scores
- **Address Requirement:** INNER JOIN (customers must have addresses)

### Performance Summary

| UC | Description | Avg Latency | P95 | Throughput | Status |
|----|-------------|-------------|-----|------------|--------|
| UC-1 | Phone + SSN | **34.14 ms** | 39.87 ms | 29.3/s | 20/20 |
| UC-2 | Phone + SSN + Account | **46.98 ms** | 62.11 ms | 21.3/s | 20/20 |
| UC-3 | Phone + Account Last 4 | **18.47 ms** | 18.88 ms | 54.1/s | 20/20 |
| UC-4 | Account + SSN | **32.42 ms** | 37.95 ms | 30.8/s | 20/20 |
| UC-5 | City/State/ZIP + SSN + Account | **29.35 ms** | 35.33 ms | 34.1/s | 20/20 |
| UC-6 | Email + Account Last 4 | **6.23 ms** | 6.58 ms | 160.5/s | 20/20 |
| UC-7 | Email + Phone + Account | **6.42 ms** | 6.80 ms | 155.7/s | 20/20 |

**All 7 UC queries pass 20/20 with fuzzy matching on ALL conditions!**

---

## Key JSON Paths

### Identity Collection
| Path | Description |
|------|-------------|
| `$._id.customerNumber` | Customer number (primary key) |
| `$._id.customerCompanyNumber` | Company number |
| `$.common.entityTypeIndicator` | INDIVIDUAL or NON_INDIVIDUAL |
| `$.common.fullName` | Full name |
| `$.common.taxIdentificationNumber` | Full SSN/EIN (fuzzy searchable) |
| `$.common.taxIdentificationTypeCode` | SSN, EIN, or ITIN |
| `$.common.customerType` | Customer, Prospect, Youth Banking |
| `$.individual.firstName` | First name (individuals) |
| `$.individual.dateOfBirth` | Date of birth (individuals) |
| `$.nonIndividual.businessDescriptionText` | Business description (non-individuals) |
| `$.primaryEmail` | Primary email address (scalar field for text search) |

### Phone Collection
| Path | Description |
|------|-------------|
| `$.phoneKey.customerNumber` | Customer number (foreign key) |
| `$.phoneKey.phoneNumber` | Phone number |

### Account Collection
| Path | Description |
|------|-------------|
| `$.accountHolders[0].customerNumber` | Customer number (foreign key) |
| `$.accountKey.accountNumber` | Full account number |
| `$.accountKey.accountNumberLast4` | Last 4 digits of account |

### Address Collection (Array Structure)
| Path | Description |
|------|-------------|
| `$._id.customerNumber` | Customer number (foreign key) |
| `$._id.customerCompanyNumber` | Company number |
| `$.addresses` | Array of address objects |
| `$.addresses.addressLine1` | Street address (use without index for text search) |
| `$.addresses.cityName` | City (use without index for text search) |
| `$.addresses.stateCode` | State code |
| `$.addresses.postalCode` | ZIP code |
| `$.addresses.countryCode` | Country code |

**Note:** For `json_textcontains()`, use paths without array index (e.g., `$.addresses.cityName`) to match any element in the array.

---

## CLI Usage

### MongoDB $sql UC Benchmark
```bash
java --enable-preview -jar target/wf-bench-1.0.0-SNAPSHOT.jar mongo-sql \
  --connection-string "mongodb://admin:PASSWORD@host:27017/[user]?..." \
  --database admin \
  --uc-benchmark \
  --iterations 20 \
  --warmup 5
```

### Run Individual UC Query
```bash
java --enable-preview -jar target/wf-bench-1.0.0-SNAPSHOT.jar mongo-sql \
  --connection-string "$CONN" \
  --database admin \
  --uc1-phone "4155551234" \
  --uc1-ssn-last4 "6789"
```

---

## Implementation Files

| File | Description |
|------|-------------|
| `MongoSqlSearchService.java` | UC 1-7 using MongoDB $sql operator with json_textcontains() |
| `MongoSqlSearchCommand.java` | CLI command for MongoDB $sql UC benchmarks |
| `UcSearchResult.java` | Result model matching PDF response format |
| `IdentityGenerator.java` | Data generator for identity collection |

---

## Known Issues and Solutions

1. **Array Index Paths:** `json_textcontains()` does not accept array index syntax in JSON paths. Paths like `$."addresses"[0]."cityName"` return `ORA-40469`. **Solution:** Use `$.addresses.cityName` (without array index) which matches any element in the array.

2. **Negative Numbers in Text Search:** Oracle Text interprets `-` as a NOT operator. Account numbers starting with `-` cause `DRG-50901` parser errors. **Solution:** Ensure account numbers are always positive (use `Math.abs()`).

3. **Unique Score Labels:** Each `json_textcontains()` call in a CTE must use a unique score label (1, 2, 3...) to avoid `ORA-30605`. **Solution:** Assign sequential labels to each fuzzy condition.

---

## Notes

1. **Fuzzy Matching:** All search conditions use `json_textcontains()` for typo-tolerant matching
2. **Combined Scores:** Multiple fuzzy matches are averaged into a single relevance score
3. **Ends-With Pattern:** Use `%term` pattern for partial matches (SSN last 4, account last 4)
4. **Address Requirement:** INNER JOIN ensures all results have at least one address
5. **CTE Pattern:** WITH clause pattern enables efficient multi-collection joins
6. **DOMAIN_INDEX_SORT:** Hint pushes sort into domain index for better performance
