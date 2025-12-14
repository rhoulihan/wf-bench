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

## Benchmark Results (December 13, 2025)

### Test Configuration
- **Implementation:** MongoDB $sql operator with json_textcontains()
- **Fuzzy Matching:** ALL search conditions use fuzzy matching
- **Score Calculation:** Combined average of all fuzzy scores
- **Address Requirement:** INNER JOIN (customers must have addresses)

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
| UC-1 | Phone + SSN | **6.70 ms** | 9.27 ms | 149.2/s | 20/20 |
| UC-2 | Phone + SSN + Account | **6.05 ms** | 6.72 ms | 165.4/s | 20/20 |
| UC-3 | Phone + Account Last 4 | **6.13 ms** | 7.14 ms | 163.2/s | 20/20 |
| UC-4 | Account + SSN | **5.84 ms** | 7.78 ms | 171.1/s | 20/20 |
| UC-5 | City/State/ZIP + SSN + Account | **114.96 ms** | 191.10 ms | 8.7/s | 20/20 |
| UC-6 | Email + Account Last 4 | **1118.11 ms** | 1132.54 ms | 0.9/s | 20/20 |
| UC-7 | Email + Phone + Account | **5.95 ms** | 6.34 ms | 168.1/s | 20/20 |

**All 7 UC queries pass 20/20 with fuzzy matching on ALL conditions!**

### Example Search Terms

| UC | Search Parameters | Example Values |
|----|-------------------|----------------|
| UC-1 | Phone, SSN Last 4 | `4151234567`, `%6789` |
| UC-2 | Phone, SSN Last 4, Account Last 4 | `4159876543`, `%1234`, `%5678` |
| UC-3 | Phone, Account Last 4 | `4155551234`, `%9012` |
| UC-4 | Account Number, SSN Last 4 | `1234567890`, `%4567` |
| UC-5 | City, State, ZIP, SSN Last 4, Account Last 4 | `San Francisco`, `CA`, `94102`, `%7890`, `%3456` |
| UC-6 | Email, Account Last 4 | `user123@gmail.com`, `%2345` |
| UC-7 | Email, Phone, Account Number | `john.smith@example.com`, `4155559999`, `9876543210` |

**Notes:**
- Phone numbers: 10-digit fuzzy match
- SSN Last 4: `%term` pattern anchors to end of full taxIdentificationNumber field
- Account Last 4: `%term` pattern anchors to end of accountNumberLast4 field
- Email: Fuzzy match on local part only (before @) for better performance
- Account Number: Full 10-digit fuzzy match

### Performance Notes
- **UC-6 (Email search)** shows higher latency (~1.1s) due to email text search complexity. The query is optimized to start with account last4 (more selective) before joining to identities.
- **UC-5 (Geo search)** involves three fuzzy conditions plus exact match on state/ZIP, resulting in moderate latency.
- **UC-1 through UC-4, UC-7** achieve sub-10ms latency with 150+ queries/second throughput.

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
  WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', '4155551234', 1)
  ORDER BY score(1) DESC
),
identities AS (
  SELECT "DATA", score(2) iscore
  FROM "identity"
  WHERE json_textcontains("DATA", '$."common"."taxIdentificationNumber"', '%6789', 2)
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
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '%1234', 3)
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
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '%1234', 2)
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

---

### UC-4: Account Number + SSN (ends-with)

```javascript
db.aggregate([{"$sql": `
WITH
accounts AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) ascore
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumber"', '1234567890', 1)
  ORDER BY score(1) DESC
),
identities AS (
  SELECT "DATA", score(2) iscore
  FROM "identity"
  WHERE json_textcontains("DATA", '$."common"."taxIdentificationNumber"', '%6789', 2)
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
  JOIN identities i ON JSON_VALUE(ac."DATA", '$.accountHolders[0].customerNumber') = JSON_VALUE(i."DATA", '$._id.customerNumber')
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
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '%1234', 3)
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

---

### UC-6: Email + Account Last 4

```javascript
db.aggregate([{"$sql": `
WITH
identities AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) iscore
  FROM "identity"
  WHERE json_textcontains("DATA", '$."primaryEmail"', 'john.smith@example.com', 1)
  ORDER BY score(1) DESC
),
accounts AS (
  SELECT "DATA", score(2) ascore
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '%1234', 2)
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
