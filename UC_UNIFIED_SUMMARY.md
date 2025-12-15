# UC Unified Search Summary

## Table of Contents

- [Overview](#overview)
- [Configuration](#configuration)
- [Query Workflow Diagram](#query-workflow-diagram)
- [Implementation Approach](#implementation-approach)
- [Use Cases (UC 1-7)](#use-cases-uc-1-7)
  - [UC-1: Phone + SSN](#uc-1-phone--ssn-ends-with)
  - [UC-2: Phone + SSN + Account Last 4](#uc-2-phone--ssn--account-last-4)
  - [UC-3: Phone + Account Last 4](#uc-3-phone--account-last-4)
  - [UC-4: Account Number + SSN](#uc-4-account-number--ssn)
  - [UC-5: City/State/ZIP + SSN + Account Last 4](#uc-5-citystatezip--ssn--account-last-4)
  - [UC-6: Email + Account Last 4](#uc-6-email--account-last-4)
  - [UC-7: Email + Phone + Account Number](#uc-7-email--phone--account-number)
- [Response Format](#response-format)
- [Benchmark Results](#benchmark-results-december-13-2025)
- [Key JSON Paths](#key-json-paths)
- [Complete MongoDB $sql Command Syntax](#complete-mongodb-sql-command-syntax)
  - [UC-1 Query](#uc-1-phone--ssn-ends-with-1)
  - [UC-2 Query](#uc-2-phone--ssn--account-last-4-1)
  - [UC-3 Query](#uc-3-phone--account-last-4-1)
  - [UC-4 Query](#uc-4-account-number--ssn-ends-with)
  - [UC-5 Query](#uc-5-citystatezip--ssn--account-last-4-1)
  - [UC-6 Query](#uc-6-email--account-last-4-1)
  - [UC-7 Query](#uc-7-email--phone--account-number-1)
  - [Key Syntax Notes](#key-syntax-notes)
- [CLI Usage](#cli-usage)
- [Implementation Files](#implementation-files)
- [Known Issues and Solutions](#known-issues-and-solutions)
- [Notes](#notes)

---

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

### Dataset Sizes
| Scale | Identity | Phone | Account | Address | Total Documents |
|-------|----------|-------|---------|---------|-----------------|
| SMALL | 10,000 | 25,000 | 15,000 | 10,000 | 60,000 |
| MEDIUM | 100,000 | 250,000 | 150,000 | 100,000 | 600,000 |
| LARGE | 1,000,000 | 2,500,000 | 1,500,000 | 1,000,000 | 6,000,000 |
| XLARGE | 10,000,000 | 25,000,000 | 15,000,000 | 10,000,000 | 60,000,000 |

**Ratios:** Each customer has ~2.5 phone numbers, ~1.5 accounts, and 1 address record on average.

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

## Query Workflow Diagram

![UC Query Workflow](docs/uc-query-workflow.svg)

The diagram shows the CTE-based query pattern:
1. **Input Parameters** - Search terms (phone, SSN, account, city, etc.)
2. **CTEs with Fuzzy Matching** - Each collection gets its own CTE with `json_textcontains()` and unique score label
3. **Joined CTE** - INNER JOINs on customerNumber with combined score calculation
4. **JSON Output** - SELECT json{} constructs the response document
5. **Final Ordering** - Results sorted by combined ranking_score

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

### Sample Result Document

All UC queries return results in the same format. Here is a sample result:

```json
{
  "rankingScore": 85,
  "ecn": "1000045678",
  "companyId": 1,
  "entityType": "INDIVIDUAL",
  "name": "John Michael Smith",
  "alternateName": "John",
  "taxIdNumber": "123-45-6789",
  "taxIdType": "SSN",
  "birthDate": "1985-03-15",
  "addressLine": "123 Main Street",
  "cityName": "San Francisco",
  "state": "CA",
  "postalCode": "94102",
  "countryCode": "US",
  "customerType": "Customer"
}
```

**Field Notes:**
- `rankingScore`: Combined average of all fuzzy match scores (0-100 scale)
- `ecn`: Enterprise Customer Number used as the join key across all collections
- `taxIdNumber`: Full SSN/EIN (masked as `***-**-6789` in CLI output)
- `alternateName`: First name for individuals, business description for non-individuals
- `customerType`: One of "Customer", "Prospect", or "Youth Banking"

---

## Benchmark Results (December 14, 2025)

### Test Configuration
- **Implementation:** MongoDB $sql operator with json_textcontains()
- **Fuzzy Matching:** ALL search conditions use fuzzy matching
- **Score Calculation:** Combined average of all fuzzy scores
- **Address Requirement:** INNER JOIN (customers must have addresses)
- **Email Format:** `firstinitiallastname@domain` (e.g., `jsmith@gmail.com`) - optimized for text search
- **Statistics:** Freshly gathered on all tables
- **Optimization:** Direct match on accountNumberLast4 (no % prefix) per Oracle team guidance

### Dataset Size (LARGE)
| Collection | Document Count | Description |
|------------|----------------|-------------|
| identity | 1,000,000 | Customer identity records |
| phone | 2,500,000 | Phone number records (avg 2.5 per customer) |
| account | 1,500,000 | Account records (avg 1.5 per customer) |
| address | 1,000,000 | Address records (1 per customer) |
| **Total** | **6,000,000** | |

### Performance Summary (LARGE Dataset - 6M Documents)

| UC | Description | Avg Latency | P95 | Throughput | Status |
|----|-------------|-------------|-----|------------|--------|
| UC-1 | Phone + SSN | **6.85 ms** | 7.34 ms | 146.0/s | 10/10 |
| UC-2 | Phone + SSN + Account | **6.73 ms** | 7.62 ms | 148.6/s | 10/10 |
| UC-3 | Phone + Account Last 4 | **6.49 ms** | 6.84 ms | 154.0/s | 10/10 |
| UC-4 | Account + SSN | **5.96 ms** | 6.31 ms | 167.7/s | 10/10 |
| UC-5 | City/State/ZIP + SSN + Account | **114.61 ms** | 195.33 ms | 8.7/s | 10/10 |
| UC-6 | Email + Account Last 4 | **244.05 ms** | 485.38 ms | 4.1/s | 10/10 |
| UC-7 | Email + Phone + Account | **6.34 ms** | 7.02 ms | 157.8/s | 10/10 |

**All 7 UC queries pass 10/10 with fuzzy matching on ALL conditions!**

### Oracle Team Optimization (December 14, 2025)

Per Oracle team guidance (Rodrigo Fuentes), removed `%` prefix from `accountNumberLast4` searches:

> "Since accountNumberLast4 already stores just 4 digits, use direct match `'5005'` instead of wildcard `'%5005'`. The `%5005` pattern causes 1034 token expansion (~1.3s), while direct match is ~0.1s."

**Impact:**
- UC-6 improved from **1365 ms** to **244 ms** (5.6x faster)
- All queries using accountNumberLast4 now use direct match pattern
- Most UC queries now complete in under 10ms

**Note:** Test data uses actual matching customers from the database. Statistics freshly gathered on all tables.

### Example Search Terms

| UC | Search Parameters | Example Values |
|----|-------------------|----------------|
| UC-1 | Phone, SSN Last 4 | `5865531910`, `%0268` |
| UC-2 | Phone, SSN Last 4, Account Last 4 | `5865531910`, `%0268`, `5005` |
| UC-3 | Phone, Account Last 4 | `5865531910`, `5005` |
| UC-4 | Account Number, SSN Last 4 | `100000000000`, `%0268` |
| UC-5 | City, State, ZIP, SSN Last 4, Account Last 4 | `North Cristobalhaven`, `IL`, `75416`, `%0268`, `5005` |
| UC-6 | Email (local part), Account Last 4 | `ashields`, `5005` |
| UC-7 | Email, Phone, Account Number | `ashields`, `5549414620`, `100000375005` |

**Notes:**
- Phone numbers: 10-digit fuzzy match
- SSN Last 4: `%term` pattern anchors to end of full taxIdentificationNumber field
- Account Last 4: Direct match on accountNumberLast4 field (no `%` prefix - see Oracle team optimization above)
- Email: Fuzzy match on `emails.emailAddress` array field, **searches local part only** (e.g., `mkshlerin` not `mkshlerin@icloud.com`)
- Account Number: Full account number fuzzy match
- Email format: `firstinitiallastname@domain` (e.g., `mkshlerin@icloud.com`, `avandervort@gmail.com`)

### Performance Notes
- **UC-6 (Email search)** now achieves ~244ms after Oracle team optimization (removing % prefix from accountNumberLast4). Uses domain index on both account and identity collections.
- **UC-7 (Email + Phone + Account)** achieves excellent performance (~6ms) as multiple selective conditions quickly narrow results.
- **UC-5 (Geo search)** involves three fuzzy conditions plus exact match on state/ZIP, resulting in moderate latency (~115ms).
- **UC-4 (Account + SSN)** performs well (~6ms) with domain index access on both account and identity collections.
- **UC-1, UC-2, UC-3** achieve sub-10ms latency with phone-based queries providing excellent selectivity.
- **All UCs** use DOMAIN INDEX access via `json_textcontains()` for fuzzy text search, with HASH JOIN for efficient collection joins.

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

## Complete MongoDB $sql Command Syntax

Each UC query is executed via the MongoDB API's `$sql` aggregation operator. Below is the complete syntax for each use case.

### UC-1: Phone + SSN (ends-with)

```javascript
db.aggregate([{"$sql": `
WITH
phones AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) pscore
  FROM "phone"
  WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', '5549414620', 1)
  ORDER BY score(1) DESC
),
identities AS (
  SELECT "DATA", score(2) iscore
  FROM "identity"
  WHERE json_textcontains("DATA", '$."common"."taxIdentificationNumber"', '%1007', 2)
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
SELECT json {
  'rankingScore' : j.ranking_score,
  'ecn' : JSON_VALUE(j.identity_data, '$._id.customerNumber'),
  'companyId' : NVL(JSON_VALUE(j.identity_data, '$._id.customerCompanyNumber'), 1),
  'entityType' : JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator'),
  'name' : JSON_VALUE(j.identity_data, '$.common.fullName'),
  'alternateName' : CASE
    WHEN JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
    THEN JSON_VALUE(j.identity_data, '$.individual.firstName')
    ELSE JSON_VALUE(j.identity_data, '$.nonIndividual.businessDescriptionText')
  END,
  'taxIdNumber' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationNumber'),
  'taxIdType' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationType'),
  'birthDate' : JSON_VALUE(j.identity_data, '$.individual.dateOfBirth'),
  'addressLine' : JSON_VALUE(j.address_data, '$.addresses.addressLine1'),
  'cityName' : JSON_VALUE(j.address_data, '$.addresses.cityName'),
  'state' : JSON_VALUE(j.address_data, '$.addresses.stateCode'),
  'postalCode' : JSON_VALUE(j.address_data, '$.addresses.postalCode'),
  'countryCode' : NVL(JSON_VALUE(j.address_data, '$.addresses.countryCode'), 'US'),
  'customerType' : JSON_VALUE(j.identity_data, '$.common.customerType')
}
FROM joined j
ORDER BY j.ranking_score DESC
FETCH FIRST 10 ROWS ONLY
`}])
```

**Query Plan (December 14, 2025):**
```
-----------------------------------------------------------------------------------------------------------------
| Id  | Operation                         | Name                | Rows  | Bytes |TempSpc| Cost (%CPU)| Time     |
-----------------------------------------------------------------------------------------------------------------
|   0 | SELECT STATEMENT                  |                     |    10 |   130 |       |    65M  (1)| 00:42:53 |
|*  1 |  COUNT STOPKEY                    |                     |       |       |       |            |          |
|   2 |   VIEW                            |                     |    62M|   774M|       |    65M  (1)| 00:42:53 |
|*  3 |    SORT ORDER BY STOPKEY          |                     |    62M|   315G|   476G|    65M  (1)| 00:42:53 |
|*  4 |     HASH JOIN                     |                     |    62M|   315G|       | 73346   (1)| 00:00:03 |
|   5 |      TABLE ACCESS BY INDEX ROWID  | phone               |  1250 |  2238K|       |   834   (0)| 00:00:01 |
|*  6 |       DOMAIN INDEX                | IDX_PHONE_SEARCH    |       |       |       |     4   (0)| 00:00:01 |
|*  7 |      HASH JOIN                    |                     |  5000K|    16G|       | 72325   (1)| 00:00:03 |
|   8 |       JOIN FILTER CREATE          | :BF0000             |   500 |   996K|       |   342   (0)| 00:00:01 |
|   9 |        TABLE ACCESS BY INDEX ROWID| identity            |   500 |   996K|       |   342   (0)| 00:00:01 |
|* 10 |         DOMAIN INDEX              | IDX_IDENTITY_SEARCH |       |       |       |     4   (0)| 00:00:01 |
|  11 |       JOIN FILTER USE             | :BF0000             |  1000K|  1478M|       | 71968   (1)| 00:00:03 |
|* 12 |        TABLE ACCESS STORAGE FULL  | address             |  1000K|  1478M|       | 71968   (1)| 00:00:03 |
-----------------------------------------------------------------------------------------------------------------

Predicate Information:
   6 - access("CTXSYS"."CONTAINS"("phone"."DATA",'(5549414620) INPATH (/phoneKey/phoneNumber)',1)>0)
  10 - access("CTXSYS"."CONTAINS"("identity"."DATA",'(%1007) INPATH (/common/taxIdentificationNumber)',2)>0)
  12 - filter(SYS_OP_BLOOM_FILTER(:BF0000,JSON_VALUE("DATA",'$._id.customerNumber')))
```

---

### UC-2: Phone + SSN + Account Last 4

```javascript
db.aggregate([{"$sql": `
WITH
phones AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) pscore
  FROM "phone"
  WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', '4155551234', 1)
  ORDER BY score(1) DESC
),
identities AS (
  SELECT "DATA", score(2) iscore
  FROM "identity"
  WHERE json_textcontains("DATA", '$."common"."taxIdentificationNumber"', '%6789', 2)
),
accounts AS (
  SELECT "DATA", score(3) ascore
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '1234', 3)
),
addresses AS (
  SELECT "DATA"
  FROM "address"
),
joined AS (
  SELECT
    p."DATA" phone_data,
    i."DATA" identity_data,
    ac."DATA" account_data,
    a."DATA" address_data,
    (p.pscore + i.iscore + ac.ascore) / 3 ranking_score
  FROM phones p
  JOIN identities i ON JSON_VALUE(i."DATA", '$._id.customerNumber') = JSON_VALUE(p."DATA", '$.phoneKey.customerNumber')
  JOIN accounts ac ON JSON_VALUE(ac."DATA", '$.accountHolders[0].customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
  JOIN addresses a ON JSON_VALUE(a."DATA", '$._id.customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
)
SELECT json {
  'rankingScore' : j.ranking_score,
  'ecn' : JSON_VALUE(j.identity_data, '$._id.customerNumber'),
  'companyId' : NVL(JSON_VALUE(j.identity_data, '$._id.customerCompanyNumber'), 1),
  'entityType' : JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator'),
  'name' : JSON_VALUE(j.identity_data, '$.common.fullName'),
  'alternateName' : CASE
    WHEN JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
    THEN JSON_VALUE(j.identity_data, '$.individual.firstName')
    ELSE JSON_VALUE(j.identity_data, '$.nonIndividual.businessDescriptionText')
  END,
  'taxIdNumber' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationNumber'),
  'taxIdType' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationType'),
  'birthDate' : JSON_VALUE(j.identity_data, '$.individual.dateOfBirth'),
  'addressLine' : JSON_VALUE(j.address_data, '$.addresses.addressLine1'),
  'cityName' : JSON_VALUE(j.address_data, '$.addresses.cityName'),
  'state' : JSON_VALUE(j.address_data, '$.addresses.stateCode'),
  'postalCode' : JSON_VALUE(j.address_data, '$.addresses.postalCode'),
  'countryCode' : NVL(JSON_VALUE(j.address_data, '$.addresses.countryCode'), 'US'),
  'customerType' : JSON_VALUE(j.identity_data, '$.common.customerType')
}
FROM joined j
ORDER BY j.ranking_score DESC
FETCH FIRST 10 ROWS ONLY
`}])
```

**Query Plan (December 14, 2025):**
```
------------------------------------------------------------------------------------------------------------------
| Id  | Operation                          | Name                | Rows  | Bytes |TempSpc| Cost (%CPU)| Time     |
------------------------------------------------------------------------------------------------------------------
|   0 | SELECT STATEMENT                   |                     |    10 |   130 |       |   767M  (1)| 08:19:26 |
|*  1 |  COUNT STOPKEY                     |                     |       |       |       |            |          |
|   2 |   VIEW                             |                     |   468M|  5811M|       |   767M  (1)| 08:19:26 |
|*  3 |    SORT ORDER BY STOPKEY           |                     |   468M|  2823G|  3576G|   767M  (1)| 08:19:26 |
|*  4 |     HASH JOIN                      |                     |   468M|  2823G|       | 74995   (3)| 00:00:03 |
|   5 |      TABLE ACCESS BY INDEX ROWID   | phone               |  1250 |  2238K|       |   834   (0)| 00:00:01 |
|*  6 |       DOMAIN INDEX                 | IDX_PHONE_SEARCH    |       |       |       |     4   (0)| 00:00:01 |
|*  7 |      HASH JOIN                     |                     |    37M|   161G|       | 72765   (1)| 00:00:03 |
|   8 |       TABLE ACCESS BY INDEX ROWID  | account             |   750 |   763K|       |   328   (0)| 00:00:01 |
|*  9 |        DOMAIN INDEX                | IDX_ACCOUNT_SEARCH  |       |       |       |     4   (0)| 00:00:01 |
|* 10 |       HASH JOIN                    |                     |  5000K|    16G|       | 72325   (1)| 00:00:03 |
|  11 |        JOIN FILTER CREATE          | :BF0000             |   500 |   996K|       |   342   (0)| 00:00:01 |
|  12 |         TABLE ACCESS BY INDEX ROWID| identity            |   500 |   996K|       |   342   (0)| 00:00:01 |
|* 13 |          DOMAIN INDEX              | IDX_IDENTITY_SEARCH |       |       |       |     4   (0)| 00:00:01 |
|  14 |        JOIN FILTER USE             | :BF0000             |  1000K|  1478M|       | 71968   (1)| 00:00:03 |
|* 15 |         TABLE ACCESS STORAGE FULL  | address             |  1000K|  1478M|       | 71968   (1)| 00:00:03 |
------------------------------------------------------------------------------------------------------------------

Predicate Information:
   6 - access("CTXSYS"."CONTAINS"("phone"."DATA",'(5549414620) INPATH (/phoneKey/phoneNumber)',1)>0)
   9 - access("CTXSYS"."CONTAINS"("account"."DATA",'(5005) INPATH (/accountKey/accountNumberLast4)',3)>0)
  13 - access("CTXSYS"."CONTAINS"("identity"."DATA",'(%1007) INPATH (/common/taxIdentificationNumber)',2)>0)
```

---

### UC-3: Phone + Account Last 4

```javascript
db.aggregate([{"$sql": `
WITH
phones AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) pscore
  FROM "phone"
  WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', '4155551234', 1)
  ORDER BY score(1) DESC
),
identities AS (
  SELECT "DATA"
  FROM "identity"
),
accounts AS (
  SELECT "DATA", score(2) ascore
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '1234', 2)
),
addresses AS (
  SELECT "DATA"
  FROM "address"
),
joined AS (
  SELECT
    p."DATA" phone_data,
    i."DATA" identity_data,
    ac."DATA" account_data,
    a."DATA" address_data,
    (p.pscore + ac.ascore) / 2 ranking_score
  FROM phones p
  JOIN identities i ON JSON_VALUE(i."DATA", '$._id.customerNumber') = JSON_VALUE(p."DATA", '$.phoneKey.customerNumber')
  JOIN accounts ac ON JSON_VALUE(ac."DATA", '$.accountHolders[0].customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
  JOIN addresses a ON JSON_VALUE(a."DATA", '$._id.customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
)
SELECT json {
  'rankingScore' : j.ranking_score,
  'ecn' : JSON_VALUE(j.identity_data, '$._id.customerNumber'),
  'companyId' : NVL(JSON_VALUE(j.identity_data, '$._id.customerCompanyNumber'), 1),
  'entityType' : JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator'),
  'name' : JSON_VALUE(j.identity_data, '$.common.fullName'),
  'alternateName' : CASE
    WHEN JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
    THEN JSON_VALUE(j.identity_data, '$.individual.firstName')
    ELSE JSON_VALUE(j.identity_data, '$.nonIndividual.businessDescriptionText')
  END,
  'taxIdNumber' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationNumber'),
  'taxIdType' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationType'),
  'birthDate' : JSON_VALUE(j.identity_data, '$.individual.dateOfBirth'),
  'addressLine' : JSON_VALUE(j.address_data, '$.addresses.addressLine1'),
  'cityName' : JSON_VALUE(j.address_data, '$.addresses.cityName'),
  'state' : JSON_VALUE(j.address_data, '$.addresses.stateCode'),
  'postalCode' : JSON_VALUE(j.address_data, '$.addresses.postalCode'),
  'countryCode' : NVL(JSON_VALUE(j.address_data, '$.addresses.countryCode'), 'US'),
  'customerType' : JSON_VALUE(j.identity_data, '$.common.customerType')
}
FROM joined j
ORDER BY j.ranking_score DESC
FETCH FIRST 10 ROWS ONLY
`}])
```

**Query Plan (December 14, 2025):**
```
-----------------------------------------------------------------------------------------------------------------
| Id  | Operation                          | Name               | Rows  | Bytes |TempSpc| Cost (%CPU)| Time     |
-----------------------------------------------------------------------------------------------------------------
|   0 | SELECT STATEMENT                   |                    |    10 |   130 |       |   309M (18)| 03:21:45 |
|*  1 |  COUNT STOPKEY                     |                    |       |       |       |            |          |
|   2 |   VIEW                             |                    |   937G|    11T|       |   309M (18)| 03:21:45 |
|*  3 |    SORT ORDER BY STOPKEY           |                    |   937G|  5503T|  6984T|   309M (18)| 03:21:45 |
|*  4 |     HASH JOIN                      |                    |   937G|  5503T|       |  3361K (91)| 00:02:12 |
|   5 |      JOIN FILTER CREATE            | :BF0000            |  1250 |  2238K|       |   834   (0)| 00:00:01 |
|   6 |       TABLE ACCESS BY INDEX ROWID  | phone              |  1250 |  2238K|       |   834   (0)| 00:00:01 |
|*  7 |        DOMAIN INDEX                | IDX_PHONE_SEARCH   |       |       |       |     4   (0)| 00:00:01 |
|*  8 |      HASH JOIN                     |                    |    75G|   315T|       |   570K (45)| 00:00:23 |
|   9 |       JOIN FILTER CREATE           | :BF0001            |   750 |   763K|       |   328   (0)| 00:00:01 |
|  10 |        TABLE ACCESS BY INDEX ROWID | account            |   750 |   763K|       |   328   (0)| 00:00:01 |
|* 11 |         DOMAIN INDEX               | IDX_ACCOUNT_SEARCH |       |       |       |     4   (0)| 00:00:01 |
|* 12 |       HASH JOIN                    |                    |    10G|    32T|  1489M|   347K  (9)| 00:00:14 |
|  13 |        JOIN FILTER CREATE          | :BF0002            |  1000K|  1478M|       | 71968   (1)| 00:00:03 |
|  14 |         TABLE ACCESS STORAGE FULL  | address            |  1000K|  1478M|       | 71968   (1)| 00:00:03 |
|  15 |        JOIN FILTER USE             | :BF0000            |  1000K|  1935M|       | 93215   (1)| 00:00:04 |
|  16 |         JOIN FILTER USE            | :BF0001            |  1000K|  1935M|       | 93215   (1)| 00:00:04 |
|  17 |          JOIN FILTER USE           | :BF0002            |  1000K|  1935M|       | 93215   (1)| 00:00:04 |
|* 18 |           TABLE ACCESS STORAGE FULL| identity           |  1000K|  1935M|       | 93215   (1)| 00:00:04 |
-----------------------------------------------------------------------------------------------------------------

Predicate Information:
   7 - access("CTXSYS"."CONTAINS"("phone"."DATA",'(5549414620) INPATH (/phoneKey/phoneNumber)',1)>0)
  11 - access("CTXSYS"."CONTAINS"("account"."DATA",'(5005) INPATH (/accountKey/accountNumberLast4)',2)>0)
```

---

### UC-4: Account Number + SSN (ends-with)

```javascript
db.aggregate([{"$sql": `
WITH
accounts AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) ascore
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumber"', '100000375005', 1)
  ORDER BY score(1) DESC
),
identities AS (
  SELECT "DATA", score(2) iscore
  FROM "identity"
  WHERE json_textcontains("DATA", '$."common"."taxIdentificationNumber"', '%1007', 2)
),
addresses AS (
  SELECT "DATA"
  FROM "address"
),
joined AS (
  SELECT
    ac."DATA" account_data,
    i."DATA" identity_data,
    a."DATA" address_data,
    (ac.ascore + i.iscore) / 2 ranking_score
  FROM accounts ac
  JOIN identities i ON JSON_VALUE(ac."DATA", '$.accountKey.customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
  JOIN addresses a ON JSON_VALUE(a."DATA", '$._id.customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
)
SELECT json {
  'rankingScore' : j.ranking_score,
  'ecn' : JSON_VALUE(j.identity_data, '$._id.customerNumber'),
  'companyId' : NVL(JSON_VALUE(j.identity_data, '$._id.customerCompanyNumber'), 1),
  'entityType' : JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator'),
  'name' : JSON_VALUE(j.identity_data, '$.common.fullName'),
  'alternateName' : CASE
    WHEN JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
    THEN JSON_VALUE(j.identity_data, '$.individual.firstName')
    ELSE JSON_VALUE(j.identity_data, '$.nonIndividual.businessDescriptionText')
  END,
  'taxIdNumber' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationNumber'),
  'taxIdType' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationType'),
  'birthDate' : JSON_VALUE(j.identity_data, '$.individual.dateOfBirth'),
  'addressLine' : JSON_VALUE(j.address_data, '$.addresses.addressLine1'),
  'cityName' : JSON_VALUE(j.address_data, '$.addresses.cityName'),
  'state' : JSON_VALUE(j.address_data, '$.addresses.stateCode'),
  'postalCode' : JSON_VALUE(j.address_data, '$.addresses.postalCode'),
  'countryCode' : NVL(JSON_VALUE(j.address_data, '$.addresses.countryCode'), 'US'),
  'customerType' : JSON_VALUE(j.identity_data, '$.common.customerType')
}
FROM joined j
ORDER BY j.ranking_score DESC
FETCH FIRST 10 ROWS ONLY
`}])
```

**Query Plan (December 14, 2025):**
```
-----------------------------------------------------------------------------------------------------------------
| Id  | Operation                         | Name                | Rows  | Bytes |TempSpc| Cost (%CPU)| Time     |
-----------------------------------------------------------------------------------------------------------------
|   0 | SELECT STATEMENT                  |                     |    10 |   130 |       |    33M  (1)| 00:22:01 |
|*  1 |  COUNT STOPKEY                    |                     |       |       |       |            |          |
|   2 |   VIEW                            |                     |    37M|   464M|       |    33M  (1)| 00:22:01 |
|*  3 |    SORT ORDER BY STOPKEY          |                     |    37M|   161G|   286G|    33M  (1)| 00:22:01 |
|*  4 |     HASH JOIN                     |                     |    37M|   161G|       | 72765   (1)| 00:00:03 |
|   5 |      TABLE ACCESS BY INDEX ROWID  | account             |   750 |   763K|       |   328   (0)| 00:00:01 |
|*  6 |       DOMAIN INDEX                | IDX_ACCOUNT_SEARCH  |       |       |       |     4   (0)| 00:00:01 |
|*  7 |      HASH JOIN                    |                     |  5000K|    16G|       | 72325   (1)| 00:00:03 |
|   8 |       JOIN FILTER CREATE          | :BF0000             |   500 |   996K|       |   342   (0)| 00:00:01 |
|   9 |        TABLE ACCESS BY INDEX ROWID| identity            |   500 |   996K|       |   342   (0)| 00:00:01 |
|* 10 |         DOMAIN INDEX              | IDX_IDENTITY_SEARCH |       |       |       |     4   (0)| 00:00:01 |
|  11 |       JOIN FILTER USE             | :BF0000             |  1000K|  1478M|       | 71968   (1)| 00:00:03 |
|* 12 |        TABLE ACCESS STORAGE FULL  | address             |  1000K|  1478M|       | 71968   (1)| 00:00:03 |
-----------------------------------------------------------------------------------------------------------------

Predicate Information:
   6 - access("CTXSYS"."CONTAINS"("account"."DATA",'(100000375005) INPATH (/accountKey/accountNumber)',1)>0)
  10 - access("CTXSYS"."CONTAINS"("identity"."DATA",'(%1007) INPATH (/common/taxIdentificationNumber)',2)>0)
  12 - filter(SYS_OP_BLOOM_FILTER(:BF0000,JSON_VALUE("DATA",'$._id.customerNumber')))
```

---

### UC-5: City/State/ZIP + SSN + Account Last 4

```javascript
db.aggregate([{"$sql": `
WITH
addresses AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) addr_score
  FROM "address"
  WHERE json_textcontains("DATA", '$."addresses"."cityName"', 'San Francisco', 1)
    AND JSON_VALUE("DATA", '$.addresses.stateCode') = 'CA'
    AND JSON_VALUE("DATA", '$.addresses.postalCode') = '94102'
  ORDER BY score(1) DESC
),
identities AS (
  SELECT "DATA", score(2) iscore
  FROM "identity"
  WHERE json_textcontains("DATA", '$."common"."taxIdentificationNumber"', '%6789', 2)
),
accounts AS (
  SELECT "DATA", score(3) ascore
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '1234', 3)
),
joined AS (
  SELECT
    a."DATA" address_data,
    i."DATA" identity_data,
    ac."DATA" account_data,
    (a.addr_score + i.iscore + ac.ascore) / 3 ranking_score
  FROM addresses a
  JOIN identities i ON JSON_VALUE(a."DATA", '$._id.customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
  JOIN accounts ac ON JSON_VALUE(ac."DATA", '$.accountHolders[0].customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
)
SELECT json {
  'rankingScore' : j.ranking_score,
  'ecn' : JSON_VALUE(j.identity_data, '$._id.customerNumber'),
  'companyId' : NVL(JSON_VALUE(j.identity_data, '$._id.customerCompanyNumber'), 1),
  'entityType' : JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator'),
  'name' : JSON_VALUE(j.identity_data, '$.common.fullName'),
  'alternateName' : CASE
    WHEN JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
    THEN JSON_VALUE(j.identity_data, '$.individual.firstName')
    ELSE JSON_VALUE(j.identity_data, '$.nonIndividual.businessDescriptionText')
  END,
  'taxIdNumber' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationNumber'),
  'taxIdType' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationType'),
  'birthDate' : JSON_VALUE(j.identity_data, '$.individual.dateOfBirth'),
  'addressLine' : JSON_VALUE(j.address_data, '$.addresses.addressLine1'),
  'cityName' : JSON_VALUE(j.address_data, '$.addresses.cityName'),
  'state' : JSON_VALUE(j.address_data, '$.addresses.stateCode'),
  'postalCode' : JSON_VALUE(j.address_data, '$.addresses.postalCode'),
  'countryCode' : NVL(JSON_VALUE(j.address_data, '$.addresses.countryCode'), 'US'),
  'customerType' : JSON_VALUE(j.identity_data, '$.common.customerType')
}
FROM joined j
ORDER BY j.ranking_score DESC
FETCH FIRST 10 ROWS ONLY
`}])
```

**Note:** City search uses `$.addresses.cityName` (without array index) to match any element in the addresses array.

**Query Plan (December 14, 2025):**
```
----------------------------------------------------------------------------------------------------------------
| Id  | Operation                                | Name                | Rows  | Bytes | Cost (%CPU)| Time     |
----------------------------------------------------------------------------------------------------------------
|   0 | SELECT STATEMENT                         |                     |     1 |    13 |   682   (1)| 00:00:01 |
|*  1 |  COUNT STOPKEY                           |                     |       |       |            |          |
|   2 |   VIEW                                   |                     |     1 |    13 |   682   (1)| 00:00:01 |
|*  3 |    SORT ORDER BY STOPKEY                 |                     |     1 |  4645 |   682   (1)| 00:00:01 |
|*  4 |     HASH JOIN                            |                     |     1 |  4645 |   681   (1)| 00:00:01 |
|*  5 |      HASH JOIN                           |                     |     1 |  3603 |   353   (1)| 00:00:01 |
|*  6 |       TABLE ACCESS BY INDEX ROWID BATCHED| address             |     1 |  1562 |    10  (20)| 00:00:01 |
|   7 |        BITMAP CONVERSION TO ROWIDS       |                     |       |       |            |          |
|   8 |         BITMAP AND                       |                     |       |       |            |          |
|   9 |          BITMAP CONVERSION FROM ROWIDS   |                     |       |       |            |          |
|  10 |           SORT ORDER BY                  |                     |       |       |            |          |
|* 11 |            DOMAIN INDEX                  | IDX_ADDRESS_SEARCH  |       |       |     4   (0)| 00:00:01 |
|  12 |          BITMAP CONVERSION FROM ROWIDS   |                     |       |       |            |          |
|  13 |           SORT ORDER BY                  |                     |       |       |            |          |
|* 14 |            DOMAIN INDEX                  | IDX_ADDRESS_SEARCH  |       |       |     4   (0)| 00:00:01 |
|  15 |       TABLE ACCESS BY INDEX ROWID        | identity            |   500 |   996K|   342   (0)| 00:00:01 |
|* 16 |        DOMAIN INDEX                      | IDX_IDENTITY_SEARCH |       |       |     4   (0)| 00:00:01 |
|  17 |      TABLE ACCESS BY INDEX ROWID         | account             |   750 |   763K|   328   (0)| 00:00:01 |
|* 18 |       DOMAIN INDEX                       | IDX_ACCOUNT_SEARCH  |       |       |     4   (0)| 00:00:01 |
----------------------------------------------------------------------------------------------------------------

Predicate Information:
   6 - filter(JSON_VALUE("DATA",'$.addresses.stateCode')='CA' AND JSON_VALUE("DATA",'$.addresses.postalCode')='54717')
  11 - access("CTXSYS"."CONTAINS"("address"."DATA",'{54717} INPATH (/addresses/postalCode)')>0)
  14 - access("CTXSYS"."CONTAINS"("address"."DATA",'{CA} INPATH (/addresses/stateCode)')>0)
  16 - access("CTXSYS"."CONTAINS"("identity"."DATA",'(%1007) INPATH (/common/taxIdentificationNumber)',2)>0)
  18 - access("CTXSYS"."CONTAINS"("account"."DATA",'(5005) INPATH (/accountKey/accountNumberLast4)',3)>0)
```

---

### UC-6: Email + Account Last 4

**Note:** Email search uses the local part only (e.g., `ashields` not `ashields@gmail.com`) via `extractEmailLocalPart()` in the implementation.

```javascript
db.aggregate([{"$sql": `
WITH
identities AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) iscore
  FROM "identity"
  WHERE json_textcontains("DATA", '$."emails"."emailAddress"', 'ashields', 1)
  ORDER BY score(1) DESC
),
accounts AS (
  SELECT "DATA", score(2) ascore
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '5005', 2)
),
addresses AS (
  SELECT "DATA"
  FROM "address"
),
joined AS (
  SELECT
    i."DATA" identity_data,
    ac."DATA" account_data,
    a."DATA" address_data,
    (i.iscore + ac.ascore) / 2 ranking_score
  FROM identities i
  JOIN accounts ac ON JSON_VALUE(ac."DATA", '$.accountKey.customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
  JOIN addresses a ON JSON_VALUE(a."DATA", '$._id.customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
)
SELECT json {
  'rankingScore' : j.ranking_score,
  'ecn' : JSON_VALUE(j.identity_data, '$._id.customerNumber'),
  'companyId' : NVL(JSON_VALUE(j.identity_data, '$._id.customerCompanyNumber'), 1),
  'entityType' : JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator'),
  'name' : JSON_VALUE(j.identity_data, '$.common.fullName'),
  'alternateName' : CASE
    WHEN JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
    THEN JSON_VALUE(j.identity_data, '$.individual.firstName')
    ELSE JSON_VALUE(j.identity_data, '$.nonIndividual.businessDescriptionText')
  END,
  'taxIdNumber' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationNumber'),
  'taxIdType' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationType'),
  'birthDate' : JSON_VALUE(j.identity_data, '$.individual.dateOfBirth'),
  'addressLine' : JSON_VALUE(j.address_data, '$.addresses.addressLine1'),
  'cityName' : JSON_VALUE(j.address_data, '$.addresses.cityName'),
  'state' : JSON_VALUE(j.address_data, '$.addresses.stateCode'),
  'postalCode' : JSON_VALUE(j.address_data, '$.addresses.postalCode'),
  'countryCode' : NVL(JSON_VALUE(j.address_data, '$.addresses.countryCode'), 'US'),
  'customerType' : JSON_VALUE(j.identity_data, '$.common.customerType')
}
FROM joined j
ORDER BY j.ranking_score DESC
FETCH FIRST 10 ROWS ONLY
`}])
```

**Query Plan (December 14, 2025):**
```
-----------------------------------------------------------------------------------------------------------------
| Id  | Operation                         | Name                | Rows  | Bytes |TempSpc| Cost (%CPU)| Time     |
-----------------------------------------------------------------------------------------------------------------
|   0 | SELECT STATEMENT                  |                     |    10 |   130 |       |    33M  (1)| 00:22:01 |
|*  1 |  COUNT STOPKEY                    |                     |       |       |       |            |          |
|   2 |   VIEW                            |                     |    37M|   464M|       |    33M  (1)| 00:22:01 |
|*  3 |    SORT ORDER BY STOPKEY          |                     |    37M|   161G|   286G|    33M  (1)| 00:22:01 |
|*  4 |     HASH JOIN                     |                     |    37M|   161G|       | 72765   (1)| 00:00:03 |
|   5 |      TABLE ACCESS BY INDEX ROWID  | account             |   750 |   763K|       |   328   (0)| 00:00:01 |
|*  6 |       DOMAIN INDEX                | IDX_ACCOUNT_SEARCH  |       |       |       |     4   (0)| 00:00:01 |
|*  7 |      HASH JOIN                    |                     |  5000K|    16G|       | 72325   (1)| 00:00:03 |
|   8 |       JOIN FILTER CREATE          | :BF0000             |   500 |   996K|       |   342   (0)| 00:00:01 |
|   9 |        TABLE ACCESS BY INDEX ROWID| identity            |   500 |   996K|       |   342   (0)| 00:00:01 |
|* 10 |         DOMAIN INDEX              | IDX_IDENTITY_SEARCH |       |       |       |     4   (0)| 00:00:01 |
|  11 |       JOIN FILTER USE             | :BF0000             |  1000K|  1478M|       | 71968   (1)| 00:00:03 |
|* 12 |        TABLE ACCESS STORAGE FULL  | address             |  1000K|  1478M|       | 71968   (1)| 00:00:03 |
-----------------------------------------------------------------------------------------------------------------

Predicate Information:
   6 - access("CTXSYS"."CONTAINS"("account"."DATA",'(5005) INPATH (/accountKey/accountNumberLast4)',1)>0)
  10 - access("CTXSYS"."CONTAINS"("identity"."DATA",'(ashields) INPATH (/emails/emailAddress)',2)>0)
  12 - filter(SYS_OP_BLOOM_FILTER(:BF0000,JSON_VALUE("DATA",'$._id.customerNumber')))
```

**Note:** Uses direct match `5005` instead of wildcard `%5005` for accountNumberLast4 per Oracle team optimization.

---

### UC-7: Email + Phone + Account Number

```javascript
db.aggregate([{"$sql": `
WITH
identities AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) iscore
  FROM "identity"
  WHERE json_textcontains("DATA", '$."primaryEmail"', 'john.smith@example.com', 1)
  ORDER BY score(1) DESC
),
phones AS (
  SELECT "DATA", score(2) pscore
  FROM "phone"
  WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', '4155551234', 2)
),
accounts AS (
  SELECT "DATA", score(3) ascore
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumber"', '1234567890', 3)
),
addresses AS (
  SELECT "DATA"
  FROM "address"
),
joined AS (
  SELECT
    i."DATA" identity_data,
    p."DATA" phone_data,
    ac."DATA" account_data,
    a."DATA" address_data,
    (i.iscore + p.pscore + ac.ascore) / 3 combined_score
  FROM identities i
  JOIN phones p ON JSON_VALUE(p."DATA", '$.phoneKey.customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
  JOIN accounts ac ON JSON_VALUE(ac."DATA", '$.accountHolders[0].customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
  JOIN addresses a ON JSON_VALUE(a."DATA", '$._id.customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
)
SELECT json {
  'rankingScore' : j.combined_score,
  'ecn' : JSON_VALUE(j.identity_data, '$._id.customerNumber'),
  'companyId' : NVL(JSON_VALUE(j.identity_data, '$._id.customerCompanyNumber'), 1),
  'entityType' : JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator'),
  'name' : JSON_VALUE(j.identity_data, '$.common.fullName'),
  'alternateName' : CASE
    WHEN JSON_VALUE(j.identity_data, '$.common.entityTypeIndicator') = 'INDIVIDUAL'
    THEN JSON_VALUE(j.identity_data, '$.individual.firstName')
    ELSE JSON_VALUE(j.identity_data, '$.nonIndividual.businessDescriptionText')
  END,
  'taxIdNumber' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationNumber'),
  'taxIdType' : JSON_VALUE(j.identity_data, '$.common.taxIdentificationType'),
  'birthDate' : JSON_VALUE(j.identity_data, '$.individual.dateOfBirth'),
  'addressLine' : JSON_VALUE(j.address_data, '$.addresses.addressLine1'),
  'cityName' : JSON_VALUE(j.address_data, '$.addresses.cityName'),
  'state' : JSON_VALUE(j.address_data, '$.addresses.stateCode'),
  'postalCode' : JSON_VALUE(j.address_data, '$.addresses.postalCode'),
  'countryCode' : NVL(JSON_VALUE(j.address_data, '$.addresses.countryCode'), 'US'),
  'customerType' : JSON_VALUE(j.identity_data, '$.common.customerType')
}
FROM joined j
ORDER BY j.combined_score DESC
FETCH FIRST 10 ROWS ONLY
`}])
```

**Query Plan (December 14, 2025):**
```
------------------------------------------------------------------------------------------------------------------
| Id  | Operation                          | Name                | Rows  | Bytes |TempSpc| Cost (%CPU)| Time     |
------------------------------------------------------------------------------------------------------------------
|   0 | SELECT STATEMENT                   |                     |    10 |   130 |       |   767M  (1)| 08:19:26 |
|*  1 |  COUNT STOPKEY                     |                     |       |       |       |            |          |
|   2 |   VIEW                             |                     |   468M|  5811M|       |   767M  (1)| 08:19:26 |
|*  3 |    SORT ORDER BY STOPKEY           |                     |   468M|  2823G|  3576G|   767M  (1)| 08:19:26 |
|*  4 |     HASH JOIN                      |                     |   468M|  2823G|       | 74995   (3)| 00:00:03 |
|   5 |      TABLE ACCESS BY INDEX ROWID   | phone               |  1250 |  2238K|       |   834   (0)| 00:00:01 |
|*  6 |       DOMAIN INDEX                 | IDX_PHONE_SEARCH    |       |       |       |     4   (0)| 00:00:01 |
|*  7 |      HASH JOIN                     |                     |    37M|   161G|       | 72765   (1)| 00:00:03 |
|   8 |       TABLE ACCESS BY INDEX ROWID  | account             |   750 |   763K|       |   328   (0)| 00:00:01 |
|*  9 |        DOMAIN INDEX                | IDX_ACCOUNT_SEARCH  |       |       |       |     4   (0)| 00:00:01 |
|* 10 |       HASH JOIN                    |                     |  5000K|    16G|       | 72325   (1)| 00:00:03 |
|  11 |        JOIN FILTER CREATE          | :BF0000             |   500 |   996K|       |   342   (0)| 00:00:01 |
|  12 |         TABLE ACCESS BY INDEX ROWID| identity            |   500 |   996K|       |   342   (0)| 00:00:01 |
|* 13 |          DOMAIN INDEX              | IDX_IDENTITY_SEARCH |       |       |       |     4   (0)| 00:00:01 |
|  14 |        JOIN FILTER USE             | :BF0000             |  1000K|  1478M|       | 71968   (1)| 00:00:03 |
|* 15 |         TABLE ACCESS STORAGE FULL  | address             |  1000K|  1478M|       | 71968   (1)| 00:00:03 |
------------------------------------------------------------------------------------------------------------------

Predicate Information:
   6 - access("CTXSYS"."CONTAINS"("phone"."DATA",'(5549414620) INPATH (/phoneKey/phoneNumber)',2)>0)
   9 - access("CTXSYS"."CONTAINS"("account"."DATA",'(100000375005) INPATH (/accountKey/accountNumber)',3)>0)
  13 - access("CTXSYS"."CONTAINS"("identity"."DATA",'(ashields) INPATH (/emails/emailAddress)',1)>0)
  15 - filter(SYS_OP_BLOOM_FILTER(:BF0000,JSON_VALUE("DATA",'$._id.customerNumber')))
```

---

### Key Syntax Notes

| Element | Syntax | Description |
|---------|--------|-------------|
| $sql operator | `db.aggregate([{"$sql": \`...\`}])` | Wraps SQL in MongoDB aggregation |
| Fuzzy search | `json_textcontains("DATA", '$.path', 'term', label)` | Text search with score |
| Ends-with pattern | `'%6789'` | Matches values ending with 6789 |
| Score extraction | `score(n)` | Gets relevance score for label n |
| Combined score | `(score1 + score2) / 2` | Average of multiple fuzzy scores |
| JSON output | `SELECT json { 'key': value, ... }` | Constructs result document |
| Optimization hint | `/*+ DOMAIN_INDEX_SORT */` | Pushes sort into index |
| Collection reference | `FROM "collection_name"` | Double-quoted collection name |
| JSON path (fuzzy) | `'$."key"."subkey"'` | Quoted path for json_textcontains |
| JSON path (value) | `'$.key.subkey'` | Unquoted path for JSON_VALUE |

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
