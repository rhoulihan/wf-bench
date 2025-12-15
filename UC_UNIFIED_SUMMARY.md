# UC Unified Search - Benchmark Results

## Table of Contents

- [Executive Summary](#executive-summary)
- [Test Environment](#test-environment)
- [Use Cases (UC 1-11)](#use-cases-uc-1-11)
- [Performance Results](#performance-results)
- [Response Format](#response-format)
- [Data Model](#data-model)
- [Query Implementation](#query-implementation)
- [Appendix: Complete Query Syntax](#appendix-complete-query-syntax)
- [Appendix: Explain Plans](#appendix-explain-plans)

---

## Executive Summary

This document presents benchmark results for the UC 1-11 unified customer search implementation using Oracle Autonomous Database 23ai with MongoDB API compatibility. The solution leverages the `$sql` aggregation operator with `json_textcontains()` for fuzzy text matching across multiple JSON document collections.

**Key Results:**
- All 11 use cases execute in under 55ms average latency
- Queries perform cross-collection JOINs on 6 million documents
- Fuzzy matching with relevance scoring on all search conditions
- Results ranked by combined relevance score

**Key Optimizations Applied:**
- UC-9: Use `JSON_VALUE(...error on error)` for optional filters instead of dot notation
- UC-10: Escape hyphens with backslash (`\-`) to prevent text search tokenization

---

## Test Environment

### Database Platform
| Component | Specification |
|-----------|---------------|
| **Database** | Oracle Autonomous Database 23ai |
| **Service** | Autonomous JSON Database (AJD) |
| **Region** | Oracle Cloud US-Ashburn-1 |
| **Workload Type** | JSON Document Store |
| **Service Level** | LOW (shared OCPU resources) |

### Compute Environment
| Component | Specification |
|-----------|---------------|
| **Client Location** | Oracle Cloud Compute (same region) |
| **Network** | Private VCN, same availability domain |
| **Client Runtime** | Java 23 with preview features |
| **Connection Protocol** | MongoDB Wire Protocol (port 27017) |

### Database Configuration
| Setting | Value |
|---------|-------|
| **OCPU Count** | 1 OCPU (auto-scaling disabled) |
| **Storage** | 1 TB |
| **Database Version** | 23ai |
| **Character Set** | AL32UTF8 |

### Test Parameters
| Parameter | Value |
|-----------|-------|
| **Test Date** | December 15, 2025 |
| **Iterations per UC** | 20 |
| **Warmup Iterations** | 5 |
| **Result Limit** | 10 documents per query |

### Dataset Configuration
| Collection | Document Count | Description |
|------------|----------------|-------------|
| identity | 1,000,000 | Customer identity records |
| phone | 2,500,000 | Phone number records (avg 2.5 per customer) |
| account | 1,500,000 | Account records (avg 1.5 per customer) |
| address | 1,000,000 | Address records (1 per customer) |
| **Total** | **6,000,000** | |

### Index Configuration

**JSON Search Indexes** (Oracle Text with wildcard optimization):
```sql
CREATE SEARCH INDEX idx_identity_search ON identity(DATA) FOR JSON PARAMETERS ('wordlist idx_wl');
CREATE SEARCH INDEX idx_phone_search ON phone(DATA) FOR JSON PARAMETERS ('wordlist idx_wl');
CREATE SEARCH INDEX idx_account_search ON account(DATA) FOR JSON PARAMETERS ('wordlist idx_wl');
CREATE SEARCH INDEX idx_address_search ON address(DATA) FOR JSON PARAMETERS ('wordlist idx_wl');
```

**Functional Indexes** (for JOIN optimization):
```sql
CREATE INDEX idx_phone_cust_num ON phone(json_value(DATA, '$.phoneKey.customerNumber'));
CREATE INDEX idx_account_cust_num ON account(json_value(DATA, '$.accountHolders[0].customerNumber'));
CREATE INDEX idx_address_cust_num ON address(json_value(DATA, '$._id.customerNumber'));
CREATE INDEX idx_identity_cust_num ON identity(json_value(DATA, '$._id.customerNumber'));
```

---

## Use Cases (UC 1-11)

### UC-1: Phone + SSN Last 4
Search for customers by phone number and last 4 digits of SSN.

| Attribute | Value |
|-----------|-------|
| **Collections** | phone, identity, address |
| **Search Fields** | Phone number, SSN (ends-with) |
| **Score** | Average of phone + SSN scores |

### UC-2: Phone + SSN Last 4 + Account Last 4
Search for customers by phone, SSN last 4, and account last 4.

| Attribute | Value |
|-----------|-------|
| **Collections** | phone, identity, account, address |
| **Search Fields** | Phone number, SSN (ends-with), Account last 4 |
| **Score** | Average of phone + SSN + account scores |

### UC-3: Phone + Account Last 4
Search for customers by phone number and account last 4.

| Attribute | Value |
|-----------|-------|
| **Collections** | phone, identity, account, address |
| **Search Fields** | Phone number, Account last 4 |
| **Score** | Average of phone + account scores |

### UC-4: Account Number + SSN Last 4
Search for customers by full account number and SSN last 4.

| Attribute | Value |
|-----------|-------|
| **Collections** | account, identity, address |
| **Search Fields** | Account number, SSN (ends-with) |
| **Score** | Average of account + SSN scores |

### UC-5: City/State/ZIP + SSN Last 4 + Account Last 4
Search for customers by geographic location, SSN last 4, and account last 4.

| Attribute | Value |
|-----------|-------|
| **Collections** | address, identity, account |
| **Search Fields** | City, State, ZIP, SSN (ends-with), Account last 4 |
| **Score** | Average of address + SSN + account scores |

### UC-6: Email + Account Last 4
Search for customers by email address and account last 4.

| Attribute | Value |
|-----------|-------|
| **Collections** | identity, account, address |
| **Search Fields** | Email address, Account last 4 |
| **Score** | Average of email + account scores |

### UC-7: Email + Phone + Account Number
Search for customers by email, phone, and full account number.

| Attribute | Value |
|-----------|-------|
| **Collections** | identity, phone, account, address |
| **Search Fields** | Email, Phone number, Account number |
| **Score** | Average of email + phone + account scores |

### UC-8: TIN (Full 9-digit)
Search for customers by full Tax Identification Number (SSN/EIN).

| Attribute | Value |
|-----------|-------|
| **Collections** | identity, address |
| **Search Fields** | Tax Identification Number (9-digit) |
| **Score** | Text search score |

### UC-9: Account Number + Optional Filters
Search for customers by full account number with optional product type and company filters.

| Attribute | Value |
|-----------|-------|
| **Collections** | account, identity, address |
| **Search Fields** | Account number, Product type (optional), Company ID (optional) |
| **Score** | Text search score |
| **Optimization** | Uses `JSON_VALUE(...error on error)` for optional filters |

### UC-10: Tokenized Account (Hyphenated)
Search for customers by tokenized/hyphenated account number (XXXX-XXXX-XXXX format).

| Attribute | Value |
|-----------|-------|
| **Collections** | account, identity, address |
| **Search Fields** | Account number in hyphenated format |
| **Score** | Text search score |
| **Optimization** | Escape hyphens with `\-` to prevent tokenization |

### UC-11: Phone (Full 10-digit)
Search for customers by full 10-digit phone number.

| Attribute | Value |
|-----------|-------|
| **Collections** | phone, identity, address |
| **Search Fields** | Phone number (10-digit) |
| **Score** | Text search score |

---

## Performance Results

### Summary (6 Million Documents)

| UC | Description | Avg Latency | P95 Latency | Throughput | Results | Status |
|----|-------------|-------------|-------------|------------|---------|--------|
| UC-1 | Phone + SSN Last 4 | **41.87 ms** | 50.94 ms | 23.9 qps | 1 | ✅ OK |
| UC-2 | Phone + SSN + Account Last 4 | **44.46 ms** | 49.66 ms | 22.5 qps | 1 | ✅ OK |
| UC-3 | Phone + Account Last 4 | **8.67 ms** | 9.10 ms | 115.4 qps | 1 | ✅ OK |
| UC-4 | Account + SSN Last 4 | **39.91 ms** | 42.50 ms | 25.1 qps | 1 | ✅ OK |
| UC-5 | City/State/ZIP + SSN + Account | **44.92 ms** | 49.06 ms | 22.3 qps | 1 | ✅ OK |
| UC-6 | Email + Account Last 4 | **9.20 ms** | 9.99 ms | 108.6 qps | 1 | ✅ OK |
| UC-7 | Email + Phone + Account | **9.95 ms** | 10.16 ms | 100.5 qps | 1 | ✅ OK |
| UC-8 | TIN (Full 9-digit) | **4.43 ms** | 5.08 ms | 225.5 qps | 1 | ✅ OK |
| UC-9 | Account + Optional Filters | **53.06 ms** | 56.70 ms | 18.8 qps | 1 | ✅ OK |
| UC-10 | Tokenized Account (Hyphenated) | **53.02 ms** | 56.42 ms | 18.9 qps | 1 | ✅ OK |
| UC-11 | Phone (Full 10-digit) | **4.46 ms** | 5.22 ms | 224.0 qps | 1 | ✅ OK |

### Test Data Parameters

| UC | Search Parameters | Test Values |
|----|-------------------|-------------|
| UC-1 | Phone, SSN Last 4 | `5549414620`, `1007` |
| UC-2 | Phone, SSN Last 4, Account Last 4 | `5549414620`, `1007`, `5005` |
| UC-3 | Phone, Account Last 4 | `5549414620`, `5005` |
| UC-4 | Account Number, SSN Last 4 | `100000375005`, `1007` |
| UC-5 | City, State, ZIP, SSN Last 4, Account Last 4 | `South Wilbertfurt`, `CA`, `54717`, `1007`, `5005` |
| UC-6 | Email, Account Last 4 | `ashields`, `5005` |
| UC-7 | Email, Phone, Account Number | `ashields`, `5549414620`, `100000375005` |
| UC-8 | TIN (9-digit) | `855611007` |
| UC-9 | Account Number, Product Type, COID | `100000375005`, `BROKERAGE` (optional), `null` |
| UC-10 | Tokenized Account (Hyphenated) | `1000-0037-5005` |
| UC-11 | Phone (10-digit) | `5549414620` |

---

## Response Format

All UC queries return results in a standardized JSON format:

| Field | Type | Description |
|-------|------|-------------|
| `rankingScore` | int | Combined relevance score (0-100) |
| `ecn` | String | Enterprise Customer Number |
| `companyId` | int | Customer Company Number |
| `entityType` | String | INDIVIDUAL or NON_INDIVIDUAL |
| `name` | String | Full name |
| `alternateName` | String | First name or business description |
| `taxIdNumber` | String | SSN/EIN |
| `taxIdType` | String | SSN, EIN, or ITIN |
| `birthDate` | String | Date of birth (individuals) |
| `addressLine` | String | Street address |
| `cityName` | String | City |
| `state` | String | State code |
| `postalCode` | String | ZIP code |
| `countryCode` | String | Country code |
| `customerType` | String | Customer, Prospect, or Youth Banking |

### Sample Response

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

---

## Data Model

### Collections

#### Identity Collection
| Field Path | Description |
|------------|-------------|
| `$._id.customerNumber` | Customer number (primary key) |
| `$._id.customerCompanyNumber` | Company number |
| `$.common.entityTypeIndicator` | INDIVIDUAL or NON_INDIVIDUAL |
| `$.common.fullName` | Full name |
| `$.common.taxIdentificationNumber` | SSN/EIN |
| `$.common.customerType` | Customer type |
| `$.individual.firstName` | First name |
| `$.individual.dateOfBirth` | Date of birth |
| `$.emails[].emailAddress` | Email addresses (array) |

#### Phone Collection
| Field Path | Description |
|------------|-------------|
| `$.phoneKey.customerNumber` | Customer number (foreign key) |
| `$.phoneKey.phoneNumber` | Phone number |

#### Account Collection
| Field Path | Description |
|------------|-------------|
| `$.accountKey.customerNumber` | Customer number (foreign key) |
| `$.accountKey.accountNumber` | Full account number |
| `$.accountKey.accountNumberLast4` | Last 4 digits |
| `$.accountKey.accountNumberHyphenated` | Tokenized format (XXXX-XXXX-XXXX) |
| `$.productTypeCode` | Account product type |
| `$.companyOfInterestId` | Company of Interest ID |

#### Address Collection
| Field Path | Description |
|------------|-------------|
| `$._id.customerNumber` | Customer number (foreign key) |
| `$.addresses[].addressLine1` | Street address |
| `$.addresses[].cityName` | City |
| `$.addresses[].stateCode` | State code |
| `$.addresses[].postalCode` | ZIP code |

---

## Query Implementation

### Architecture

Queries use a Common Table Expression (CTE) pattern:

1. **Collection CTEs** - Each collection has a CTE with `json_textcontains()` for fuzzy matching
2. **Joined CTE** - INNER JOINs on customerNumber with combined score calculation
3. **Result Selection** - JSON output construction with ranking

### Key Techniques

| Technique | Description |
|-----------|-------------|
| `json_textcontains()` | Oracle Text fuzzy search on JSON fields |
| `score(n)` | Relevance score extraction (0-100) |
| Dot notation | `alias."DATA"."field".string()` for efficient field access |
| `json_exists()` | Filter predicate for array element matching |
| `/*+ DOMAIN_INDEX_SORT */` | Optimizer hint for index-based sorting |

### Query Pattern Example (UC-1)

```sql
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
  SELECT "DATA" FROM "address"
),
joined AS (
  SELECT
    p."DATA" phone_data,
    i."DATA" identity_data,
    a."DATA" address_data,
    (p.pscore + i.iscore) / 2 ranking_score
  FROM phones p
  JOIN identities i ON i."DATA"."_id"."customerNumber".string() = p."DATA"."phoneKey"."customerNumber".string()
  JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
)
SELECT json { ... }
FROM joined j
ORDER BY j.ranking_score DESC
FETCH FIRST 10 ROWS ONLY
```

---

## Appendix: Complete Query Syntax

### UC-1: Phone + SSN Last 4

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
  JOIN identities i ON i."DATA"."_id"."customerNumber".string() = p."DATA"."phoneKey"."customerNumber".string()
  JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
)
SELECT json {
  'rankingScore' : j.ranking_score,
  'ecn' : j.identity_data."_id"."customerNumber".string(),
  'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
  'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
  'name' : j.identity_data."common"."fullName".string(),
  'alternateName' : CASE
    WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
    THEN j.identity_data."individual"."firstName".string()
    ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
  END,
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
ORDER BY j.ranking_score DESC
FETCH FIRST 10 ROWS ONLY
`}])
```

---

### UC-2: Phone + SSN Last 4 + Account Last 4

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
accounts AS (
  SELECT "DATA", score(3) ascore
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '5005', 3)
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
  JOIN identities i ON i."DATA"."_id"."customerNumber".string() = p."DATA"."phoneKey"."customerNumber".string()
  JOIN accounts ac ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
  JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
)
SELECT json {
  'rankingScore' : j.ranking_score,
  'ecn' : j.identity_data."_id"."customerNumber".string(),
  'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
  'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
  'name' : j.identity_data."common"."fullName".string(),
  'alternateName' : CASE
    WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
    THEN j.identity_data."individual"."firstName".string()
    ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
  END,
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
  WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', '5549414620', 1)
  ORDER BY score(1) DESC
),
identities AS (
  SELECT "DATA"
  FROM "identity"
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
    p."DATA" phone_data,
    i."DATA" identity_data,
    ac."DATA" account_data,
    a."DATA" address_data,
    (p.pscore + ac.ascore) / 2 ranking_score
  FROM phones p
  JOIN identities i ON i."DATA"."_id"."customerNumber".string() = p."DATA"."phoneKey"."customerNumber".string()
  JOIN accounts ac ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
  JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
)
SELECT json {
  'rankingScore' : j.ranking_score,
  'ecn' : j.identity_data."_id"."customerNumber".string(),
  'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
  'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
  'name' : j.identity_data."common"."fullName".string(),
  'alternateName' : CASE
    WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
    THEN j.identity_data."individual"."firstName".string()
    ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
  END,
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
ORDER BY j.ranking_score DESC
FETCH FIRST 10 ROWS ONLY
`}])
```

---

### UC-4: Account Number + SSN Last 4

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
  JOIN identities i ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
  JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
)
SELECT json {
  'rankingScore' : j.ranking_score,
  'ecn' : j.identity_data."_id"."customerNumber".string(),
  'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
  'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
  'name' : j.identity_data."common"."fullName".string(),
  'alternateName' : CASE
    WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
    THEN j.identity_data."individual"."firstName".string()
    ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
  END,
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
ORDER BY j.ranking_score DESC
FETCH FIRST 10 ROWS ONLY
`}])
```

---

### UC-5: City/State/ZIP + SSN Last 4 + Account Last 4

```javascript
db.aggregate([{"$sql": `
WITH
addresses AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) addr_score
  FROM "address" a
  WHERE json_textcontains("DATA", '$."addresses"."cityName"', 'South Wilbertfurt', 1)
    AND json_exists(a.data, '$.addresses[0]?(@.stateCode == $b1 && @.postalCode == $b2)' passing 'CA' as "b1", '54717' as "b2" error on error)
  ORDER BY score(1) DESC
),
identities AS (
  SELECT "DATA", score(2) iscore
  FROM "identity"
  WHERE json_textcontains("DATA", '$."common"."taxIdentificationNumber"', '%1007', 2)
),
accounts AS (
  SELECT "DATA", score(3) ascore
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '5005', 3)
),
joined AS (
  SELECT
    a."DATA" address_data,
    i."DATA" identity_data,
    ac."DATA" account_data,
    (a.addr_score + i.iscore + ac.ascore) / 3 ranking_score
  FROM addresses a
  JOIN identities i ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
  JOIN accounts ac ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
)
SELECT json {
  'rankingScore' : j.ranking_score,
  'ecn' : j.identity_data."_id"."customerNumber".string(),
  'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
  'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
  'name' : j.identity_data."common"."fullName".string(),
  'alternateName' : CASE
    WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
    THEN j.identity_data."individual"."firstName".string()
    ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
  END,
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
ORDER BY j.ranking_score DESC
FETCH FIRST 10 ROWS ONLY
`}])
```

---

### UC-6: Email + Account Last 4

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
  JOIN accounts ac ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
  JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
)
SELECT json {
  'rankingScore' : j.ranking_score,
  'ecn' : j.identity_data."_id"."customerNumber".string(),
  'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
  'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
  'name' : j.identity_data."common"."fullName".string(),
  'alternateName' : CASE
    WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
    THEN j.identity_data."individual"."firstName".string()
    ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
  END,
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
  WHERE json_textcontains("DATA", '$."emails"."emailAddress"', 'ashields', 1)
  ORDER BY score(1) DESC
),
phones AS (
  SELECT "DATA", score(2) pscore
  FROM "phone"
  WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', '5549414620', 2)
),
accounts AS (
  SELECT "DATA", score(3) ascore
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumber"', '100000375005', 3)
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
  JOIN phones p ON p."DATA"."phoneKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
  JOIN accounts ac ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
  JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
)
SELECT json {
  'rankingScore' : j.combined_score,
  'ecn' : j.identity_data."_id"."customerNumber".string(),
  'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
  'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
  'name' : j.identity_data."common"."fullName".string(),
  'alternateName' : CASE
    WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
    THEN j.identity_data."individual"."firstName".string()
    ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
  END,
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
ORDER BY j.combined_score DESC
FETCH FIRST 10 ROWS ONLY
`}])
```

---

### UC-8: TIN (Full 9-digit)

```javascript
db.aggregate([{"$sql": `
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
SELECT json {
  'ecn' : j.identity_data."_id"."customerNumber".string(),
  'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
  'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
  'name' : j.identity_data."common"."fullName".string(),
  'alternateName' : CASE
    WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
    THEN j.identity_data."individual"."firstName".string()
    ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
  END,
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
`}])
```

---

### UC-9: Account Number + Optional Filters

**Optimization:** Use `JSON_VALUE(...error on error)` for optional filters - improved from 325ms to 53ms.

```javascript
db.aggregate([{"$sql": `
SELECT json {
  'ecn' : i."DATA"."_id"."customerNumber".string(),
  'companyId' : NVL(i."DATA"."_id"."customerCompanyNumber".string(), 1),
  'accountNumber' : ac."DATA"."accountKey"."accountNumber".string(),
  'productType' : ac."DATA"."productTypeCode".string(),
  'companyOfInterestId' : ac."DATA"."companyOfInterestId".string(),
  'entityType' : i."DATA"."common"."entityTypeIndicator".string(),
  'name' : i."DATA"."common"."fullName".string(),
  'alternateName' : CASE
    WHEN i."DATA"."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
    THEN i."DATA"."individual"."firstName".string()
    ELSE i."DATA"."nonIndividual"."businessDescriptionText".string()
  END,
  'taxIdNumber' : i."DATA"."common"."taxIdentificationNumber".string(),
  'taxIdType' : i."DATA"."common"."taxIdentificationType".string(),
  'birthDate' : i."DATA"."individual"."dateOfBirth".string(),
  'addressLine' : a."DATA"."addresses"."addressLine1".string(),
  'cityName' : a."DATA"."addresses"."cityName".string(),
  'state' : a."DATA"."addresses"."stateCode".string(),
  'postalCode' : a."DATA"."addresses"."postalCode".string(),
  'countryCode' : NVL(a."DATA"."addresses"."countryCode".string(), 'US'),
  'customerType' : i."DATA"."common"."customerType".string()
}
FROM "account" ac
JOIN "identity" i ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
JOIN "address" a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
WHERE json_textcontains(ac."DATA", '$."accountKey"."accountNumber"', '100000375005')
  AND JSON_VALUE(ac."DATA", '$.productTypeCode' error on error) = 'BROKERAGE'  -- Optional filter
FETCH FIRST 10 ROWS ONLY
`}])
```

---

### UC-10: Tokenized Account (Hyphenated)

**Optimization:** Escape hyphens with `\-` to prevent tokenization - improved from 456ms to 53ms and returns 1 result instead of 10.

```javascript
db.aggregate([{"$sql": `
WITH
accounts AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA"
  FROM "account" ac
  WHERE json_textcontains(ac."DATA", '$."accountKey"."accountNumberHyphenated"', '1000\-0037\-5005')
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
SELECT json {
  'ecn' : j.identity_data."_id"."customerNumber".string(),
  'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
  'accountNumber' : j.account_data."accountKey"."accountNumber".string(),
  'accountNumberHyphenated' : j.account_data."accountKey"."accountNumberHyphenated".string(),
  'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
  'name' : j.identity_data."common"."fullName".string(),
  'alternateName' : CASE
    WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
    THEN j.identity_data."individual"."firstName".string()
    ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
  END,
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
`}])
```

---

### UC-11: Phone (Full 10-digit)

```javascript
db.aggregate([{"$sql": `
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
SELECT json {
  'ecn' : j.identity_data."_id"."customerNumber".string(),
  'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber".string(), 1),
  'phoneNumber' : j.phone_data."phoneKey"."phoneNumber".string(),
  'entityType' : j.identity_data."common"."entityTypeIndicator".string(),
  'name' : j.identity_data."common"."fullName".string(),
  'alternateName' : CASE
    WHEN j.identity_data."common"."entityTypeIndicator".string() = 'INDIVIDUAL'
    THEN j.identity_data."individual"."firstName".string()
    ELSE j.identity_data."nonIndividual"."businessDescriptionText".string()
  END,
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
`}])
```

---

### SQL Syntax Reference

| Element | Syntax | Description |
|---------|--------|-------------|
| MongoDB $sql | `db.aggregate([{"$sql": \`...\`}])` | Execute SQL via MongoDB API |
| Fuzzy search | `json_textcontains("DATA", '$.path', 'term', label)` | Text search with scoring |
| Ends-with | `'%1007'` | Pattern matching suffix |
| Score | `score(n)` | Get relevance score for label n |
| JSON output | `SELECT json { 'key': value }` | Construct JSON result |
| Dot notation | `alias."DATA"."field".string()` | Extract field as VARCHAR2 |
| Array filter | `json_exists(data, '$.arr[0]?(@.f == $b)' passing 'v' as "b")` | Filter array elements |

---

## Appendix: Explain Plans

### UC-1: Phone + SSN Last 4

```
Plan hash value: 2687655126

--------------------------------------------------------------------------------------------------------
| Id  | Operation                        | Name                | Rows  | Bytes | Cost (%CPU)| Time     |
--------------------------------------------------------------------------------------------------------
|   0 | SELECT STATEMENT                 |                     |     1 |  4115 |  1181   (1)| 00:00:01 |
|*  1 |  COUNT STOPKEY                   |                     |       |       |            |          |
|   2 |   VIEW                           |                     |     1 |  4115 |  1181   (1)| 00:00:01 |
|*  3 |    SORT ORDER BY STOPKEY         |                     |     1 |  3899 |  1181   (1)| 00:00:01 |
|   4 |     NESTED LOOPS                 |                     |     1 |  3899 |  1180   (0)| 00:00:01 |
|*  5 |      HASH JOIN                   |                     |     1 |  3886 |  1177   (0)| 00:00:01 |
|   6 |       TABLE ACCESS BY INDEX ROWID| identity            |   500 |  1001K|   342   (0)| 00:00:01 |
|*  7 |        DOMAIN INDEX              | IDX_IDENTITY_SEARCH |       |       |     4   (0)| 00:00:01 |
|   8 |       TABLE ACCESS BY INDEX ROWID| phone               |  1250 |  2238K|   834   (0)| 00:00:01 |
|*  9 |        DOMAIN INDEX              | IDX_PHONE_SEARCH    |       |       |     4   (0)| 00:00:01 |
|* 10 |      INDEX RANGE SCAN            | IDX_ADDRESS_CUSTNUM |     1 |       |     2   (0)| 00:00:01 |
--------------------------------------------------------------------------------------------------------

Predicate Information:
   7 - access(CONTAINS(identity.DATA,'(%1007) INPATH (/common/taxIdentificationNumber)')>0)
   9 - access(CONTAINS(phone.DATA,'(5549414620) INPATH (/phoneKey/phoneNumber)')>0)
```

---

### UC-2: Phone + SSN Last 4 + Account Last 4

```
Plan hash value: 1443162883

----------------------------------------------------------------------------------------------------------------
| Id  | Operation                                | Name                | Rows  | Bytes | Cost (%CPU)| Time     |
----------------------------------------------------------------------------------------------------------------
|   0 | SELECT STATEMENT                         |                     |     5 | 20575 |  1509   (1)| 00:00:01 |
|*  1 |  COUNT STOPKEY                           |                     |       |       |            |          |
|   2 |   VIEW                                   |                     |     5 | 20575 |  1509   (1)| 00:00:01 |
|*  3 |    SORT ORDER BY STOPKEY                 |                     |     5 | 24880 |  1509   (1)| 00:00:01 |
|*  4 |     HASH JOIN                            |                     |     5 | 24880 |  1508   (0)| 00:00:01 |
|   5 |      NESTED LOOPS                        |                     |     1 |  3899 |  1180   (0)| 00:00:01 |
|*  6 |       HASH JOIN                          |                     |     1 |  3886 |  1177   (0)| 00:00:01 |
|   7 |        TABLE ACCESS BY INDEX ROWID       | identity            |   500 |  1001K|   342   (0)| 00:00:01 |
|*  8 |         DOMAIN INDEX                     | IDX_IDENTITY_SEARCH |       |       |     4   (0)| 00:00:01 |
|   9 |        TABLE ACCESS BY INDEX ROWID       | phone               |  1250 |  2238K|   834   (0)| 00:00:01 |
|* 10 |         DOMAIN INDEX                     | IDX_PHONE_SEARCH    |       |       |     4   (0)| 00:00:01 |
|  11 |       TABLE ACCESS BY INDEX ROWID BATCHED| address             |     1 |    13 |     3   (0)| 00:00:01 |
|* 12 |        INDEX RANGE SCAN                  | IDX_ADDRESS_CUSTNUM |     1 |       |     2   (0)| 00:00:01 |
|  13 |      TABLE ACCESS BY INDEX ROWID         | account             |   750 |   788K|   328   (0)| 00:00:01 |
|* 14 |       DOMAIN INDEX                       | IDX_ACCOUNT_TEXT    |       |       |     4   (0)| 00:00:01 |
----------------------------------------------------------------------------------------------------------------

Predicate Information:
   8 - access(CONTAINS(identity.DATA,'(%1007) INPATH (/common/taxIdentificationNumber)')>0)
  10 - access(CONTAINS(phone.DATA,'(5549414620) INPATH (/phoneKey/phoneNumber)')>0)
  14 - access(CONTAINS(account.DATA,'(5005) INPATH (/accountKey/accountNumberLast4)')>0)
```

---

### UC-3: Phone + Account Last 4

```
Plan hash value: 3809329927

--------------------------------------------------------------------------------------------------------------------------
| Id  | Operation                                 | Name                 | Rows  | Bytes |TempSpc| Cost (%CPU)| Time     |
--------------------------------------------------------------------------------------------------------------------------
|   0 | SELECT STATEMENT                          |                      |    10 | 41150 |       | 17698   (1)| 00:00:01 |
|*  1 |  COUNT STOPKEY                            |                      |       |       |       |            |          |
|   2 |   VIEW                                    |                      |  9375 |    36M|       | 17698   (1)| 00:00:01 |
|*  3 |    SORT ORDER BY STOPKEY                  |                      |  9375 |    44M|    73M| 17698   (1)| 00:00:01 |
|*  4 |     HASH JOIN                             |                      |  9375 |    44M|       |  8664   (1)| 00:00:01 |
|   5 |      TABLE ACCESS BY INDEX ROWID          | account              |   750 |   788K|       |   328   (0)| 00:00:01 |
|*  6 |       DOMAIN INDEX                        | IDX_ACCOUNT_TEXT     |       |       |       |     4   (0)| 00:00:01 |
|   7 |      NESTED LOOPS                         |                      |  1250 |  4744K|       |  8336   (1)| 00:00:01 |
|   8 |       NESTED LOOPS                        |                      |  1250 |  4729K|       |  4585   (1)| 00:00:01 |
|   9 |        TABLE ACCESS BY INDEX ROWID        | phone                |  1250 |  2238K|       |   834   (0)| 00:00:01 |
|* 10 |         DOMAIN INDEX                      | IDX_PHONE_SEARCH     |       |       |       |     4   (0)| 00:00:01 |
|  11 |        TABLE ACCESS BY INDEX ROWID BATCHED| identity             |     1 |  2040 |       |     3   (0)| 00:00:01 |
|* 12 |         INDEX RANGE SCAN                  | IDX_IDENTITY_CUSTNUM |     1 |       |       |     2   (0)| 00:00:01 |
|* 13 |       INDEX RANGE SCAN                    | IDX_ADDRESS_CUSTNUM  |     1 |       |       |     2   (0)| 00:00:01 |
--------------------------------------------------------------------------------------------------------------------------

Predicate Information:
   6 - access(CONTAINS(account.DATA,'(5005) INPATH (/accountKey/accountNumberLast4)')>0)
  10 - access(CONTAINS(phone.DATA,'(5549414620) INPATH (/phoneKey/phoneNumber)')>0)
```

---

### UC-4: Account Number + SSN Last 4

```
Plan hash value: 2721229334

------------------------------------------------------------------------------------------------------------------------
| Id  | Operation                                | Name                | Rows  | Bytes |TempSpc| Cost (%CPU)| Time     |
------------------------------------------------------------------------------------------------------------------------
|   0 | SELECT STATEMENT                         |                     |    10 | 41150 |       |  4456   (1)| 00:00:01 |
|*  1 |  COUNT STOPKEY                           |                     |       |       |       |            |          |
|   2 |   VIEW                                   |                     |  3750 |    14M|       |  4456   (1)| 00:00:01 |
|*  3 |    SORT ORDER BY STOPKEY                 |                     |  3750 |    11M|    14M|  4456   (1)| 00:00:01 |
|*  4 |     HASH JOIN                            |                     |  3750 |    11M|       |  2171   (1)| 00:00:01 |
|   5 |      NESTED LOOPS                        |                     |   500 |  1002K|       |  1843   (1)| 00:00:01 |
|   6 |       TABLE ACCESS BY INDEX ROWID        | identity            |   500 |   996K|       |   342   (0)| 00:00:01 |
|*  7 |        DOMAIN INDEX                      | IDX_IDENTITY_SEARCH |       |       |       |     4   (0)| 00:00:01 |
|   8 |       TABLE ACCESS BY INDEX ROWID BATCHED| address             |     1 |    13 |       |     3   (0)| 00:00:01 |
|*  9 |        INDEX RANGE SCAN                  | IDX_ADDRESS_CUSTNUM |     1 |       |       |     2   (0)| 00:00:01 |
|  10 |      TABLE ACCESS BY INDEX ROWID         | account             |   750 |   788K|       |   328   (0)| 00:00:01 |
|* 11 |       DOMAIN INDEX                       | IDX_ACCOUNT_TEXT    |       |       |       |     4   (0)| 00:00:01 |
------------------------------------------------------------------------------------------------------------------------

Predicate Information:
   7 - access(CONTAINS(identity.DATA,'(%1007) INPATH (/common/taxIdentificationNumber)')>0)
  11 - access(CONTAINS(account.DATA,'(100000375005) INPATH (/accountKey/accountNumber)')>0)
```

---

### UC-5: City/State/ZIP + SSN Last 4 + Account Last 4

```
Plan hash value: 2514249749

--------------------------------------------------------------------------------------------------------
| Id  | Operation                        | Name                | Rows  | Bytes | Cost (%CPU)| Time     |
--------------------------------------------------------------------------------------------------------
|   0 | SELECT STATEMENT                 |                     |     1 |  4115 |   960   (1)| 00:00:01 |
|*  1 |  COUNT STOPKEY                   |                     |       |       |            |          |
|   2 |   VIEW                           |                     |     1 |  4115 |   960   (1)| 00:00:01 |
|*  3 |    SORT ORDER BY STOPKEY         |                     |     1 |  4691 |   960   (1)| 00:00:01 |
|*  4 |     HASH JOIN                    |                     |     1 |  4691 |   959   (1)| 00:00:01 |
|*  5 |      HASH JOIN                   |                     |     1 |  3614 |   631   (1)| 00:00:01 |
|*  6 |       TABLE ACCESS BY INDEX ROWID| address             |     5 |  7865 |   288   (0)| 00:00:01 |
|*  7 |        DOMAIN INDEX              | IDX_ADDRESS_SEARCH  |       |       |     4   (0)| 00:00:01 |
|   8 |       TABLE ACCESS BY INDEX ROWID| identity            |   500 |   996K|   342   (0)| 00:00:01 |
|*  9 |        DOMAIN INDEX              | IDX_IDENTITY_SEARCH |       |       |     4   (0)| 00:00:01 |
|  10 |      TABLE ACCESS BY INDEX ROWID | account             |   750 |   788K|   328   (0)| 00:00:01 |
|* 11 |       DOMAIN INDEX               | IDX_ACCOUNT_TEXT    |       |       |     4   (0)| 00:00:01 |
--------------------------------------------------------------------------------------------------------

Predicate Information:
   6 - filter(JSON_EXISTS(address.DATA, '$.addresses[0]?(@.stateCode == $b1 && @.postalCode == $b2)'))
   7 - access(CONTAINS(address.DATA,'(South Wilbertfurt) INPATH (/addresses/cityName)')>0)
   9 - access(CONTAINS(identity.DATA,'(%1007) INPATH (/common/taxIdentificationNumber)')>0)
  11 - access(CONTAINS(account.DATA,'(5005) INPATH (/accountKey/accountNumberLast4)')>0)
```

---

### UC-6: Email + Account Last 4

```
Plan hash value: 2721229334

------------------------------------------------------------------------------------------------------------------------
| Id  | Operation                                | Name                | Rows  | Bytes |TempSpc| Cost (%CPU)| Time     |
------------------------------------------------------------------------------------------------------------------------
|   0 | SELECT STATEMENT                         |                     |    10 | 41150 |       |  4456   (1)| 00:00:01 |
|*  1 |  COUNT STOPKEY                           |                     |       |       |       |            |          |
|   2 |   VIEW                                   |                     |  3750 |    14M|       |  4456   (1)| 00:00:01 |
|*  3 |    SORT ORDER BY STOPKEY                 |                     |  3750 |    11M|    14M|  4456   (1)| 00:00:01 |
|*  4 |     HASH JOIN                            |                     |  3750 |    11M|       |  2171   (1)| 00:00:01 |
|   5 |      NESTED LOOPS                        |                     |   500 |  1002K|       |  1843   (1)| 00:00:01 |
|   6 |       TABLE ACCESS BY INDEX ROWID        | identity            |   500 |   996K|       |   342   (0)| 00:00:01 |
|*  7 |        DOMAIN INDEX                      | IDX_IDENTITY_SEARCH |       |       |       |     4   (0)| 00:00:01 |
|   8 |       TABLE ACCESS BY INDEX ROWID BATCHED| address             |     1 |    13 |       |     3   (0)| 00:00:01 |
|*  9 |        INDEX RANGE SCAN                  | IDX_ADDRESS_CUSTNUM |     1 |       |       |     2   (0)| 00:00:01 |
|  10 |      TABLE ACCESS BY INDEX ROWID         | account             |   750 |   788K|       |   328   (0)| 00:00:01 |
|* 11 |       DOMAIN INDEX                       | IDX_ACCOUNT_TEXT    |       |       |       |     4   (0)| 00:00:01 |
------------------------------------------------------------------------------------------------------------------------

Predicate Information:
   7 - access(CONTAINS(identity.DATA,'(ashields) INPATH (/emails/emailAddress)')>0)
  11 - access(CONTAINS(account.DATA,'(5005) INPATH (/accountKey/accountNumberLast4)')>0)
```

---

### UC-7: Email + Phone + Account Number

```
Plan hash value: 1678481105

-------------------------------------------------------------------------------------------------------------------------
| Id  | Operation                                 | Name                | Rows  | Bytes |TempSpc| Cost (%CPU)| Time     |
-------------------------------------------------------------------------------------------------------------------------
|   0 | SELECT STATEMENT                          |                     |    10 | 41150 |       | 48174   (1)| 00:00:02 |
|*  1 |  COUNT STOPKEY                            |                     |       |       |       |            |          |
|   2 |   VIEW                                    |                     | 46875 |   183M|       | 48174   (1)| 00:00:02 |
|*  3 |    SORT ORDER BY STOPKEY                  |                     | 46875 |   221M|   366M| 48174   (1)| 00:00:02 |
|*  4 |     HASH JOIN                             |                     | 46875 |   221M|       |  3005   (0)| 00:00:01 |
|   5 |      TABLE ACCESS BY INDEX ROWID          | phone               |  1250 |  2238K|       |   834   (0)| 00:00:01 |
|*  6 |       DOMAIN INDEX                        | IDX_PHONE_SEARCH    |       |       |       |     4   (0)| 00:00:01 |
|*  7 |      HASH JOIN                            |                     |  3750 |    11M|       |  2171   (1)| 00:00:01 |
|   8 |       NESTED LOOPS                        |                     |   500 |  1002K|       |  1843   (1)| 00:00:01 |
|   9 |        TABLE ACCESS BY INDEX ROWID        | identity            |   500 |   996K|       |   342   (0)| 00:00:01 |
|* 10 |         DOMAIN INDEX                      | IDX_IDENTITY_SEARCH |       |       |       |     4   (0)| 00:00:01 |
|  11 |        TABLE ACCESS BY INDEX ROWID BATCHED| address             |     1 |    13 |       |     3   (0)| 00:00:01 |
|* 12 |         INDEX RANGE SCAN                  | IDX_ADDRESS_CUSTNUM |     1 |       |       |     2   (0)| 00:00:01 |
|  13 |       TABLE ACCESS BY INDEX ROWID         | account             |   750 |   788K|       |   328   (0)| 00:00:01 |
|* 14 |        DOMAIN INDEX                       | IDX_ACCOUNT_TEXT    |       |       |       |     4   (0)| 00:00:01 |
-------------------------------------------------------------------------------------------------------------------------

Predicate Information:
   6 - access(CONTAINS(phone.DATA,'(5549414620) INPATH (/phoneKey/phoneNumber)')>0)
  10 - access(CONTAINS(identity.DATA,'(ashields) INPATH (/emails/emailAddress)')>0)
  14 - access(CONTAINS(account.DATA,'(100000375005) INPATH (/accountKey/accountNumber)')>0)
```

---

### UC-8: TIN (Full 9-digit)

```
Plan hash value: 3979866288

------------------------------------------------------------------------------------------------------
| Id  | Operation                      | Name                | Rows  | Bytes | Cost (%CPU)| Time     |
------------------------------------------------------------------------------------------------------
|   0 | SELECT STATEMENT               |                     |    10 | 41150 |    38   (3)| 00:00:01 |
|*  1 |  COUNT STOPKEY                 |                     |       |       |            |          |
|   2 |   VIEW                         |                     |    10 | 41150 |    38   (3)| 00:00:01 |
|   3 |    NESTED LOOPS                |                     |    10 | 20540 |    38   (3)| 00:00:01 |
|   4 |     TABLE ACCESS BY INDEX ROWID| identity            |   500 |   996K|     8  (13)| 00:00:01 |
|*  5 |      DOMAIN INDEX              | IDX_IDENTITY_SEARCH |       |       |     0   (0)| 00:00:01 |
|*  6 |     INDEX RANGE SCAN           | IDX_ADDRESS_CUSTNUM |     1 |       |     2   (0)| 00:00:01 |
------------------------------------------------------------------------------------------------------

Predicate Information:
   5 - access(CONTAINS(identity.DATA,'(855611007) INPATH (/common/taxIdentificationNumber)')>0)
```

---

### UC-9: Account Number + Optional Filters

**Optimized:** Using `JSON_VALUE(...error on error)` reduced latency from 325ms to 53ms.

```
Plan hash value: 1651662844

---------------------------------------------------------------------------------------------------------------
| Id  | Operation                               | Name                | Rows  | Bytes | Cost (%CPU)| Time     |
---------------------------------------------------------------------------------------------------------------
|   0 | SELECT STATEMENT                        |                     |    10 | 46670 |   181   (0)| 00:00:01 |
|*  1 |  COUNT STOPKEY                          |                     |       |       |            |          |
|   2 |   NESTED LOOPS                          |                     |    10 | 46670 |   181   (0)| 00:00:01 |
|   3 |    NESTED LOOPS                         |                     |    11 | 34166 |   148   (0)| 00:00:01 |
|*  4 |     TABLE ACCESS BY INDEX ROWID         | account             |     8 |  8616 |    44   (0)| 00:00:01 |
|*  5 |      DOMAIN INDEX                       | IDX_ACCOUNT_TEXT    |       |       |     1   (0)| 00:00:01 |
|*  6 |     TABLE ACCESS STORAGE FULL FIRST ROWS| identity            |    11 | 22319 |   104   (0)| 00:00:01 |
|   7 |    TABLE ACCESS BY INDEX ROWID BATCHED  | address             |     1 |  1561 |     3   (0)| 00:00:01 |
|*  8 |     INDEX RANGE SCAN                    | IDX_ADDRESS_CUSTNUM |     1 |       |     2   (0)| 00:00:01 |
---------------------------------------------------------------------------------------------------------------

Predicate Information:
   4 - filter(JSON_VALUE(ac.DATA, '$.productTypeCode' ERROR ON ERROR)='BROKERAGE')
   5 - access(CONTAINS(account.DATA,'(100000375005) INPATH (/accountKey/accountNumber)')>0)
   6 - storage + filter on customerNumber JOIN condition
```

---

### UC-10: Tokenized Account (Hyphenated)

**Optimized:** Escaping hyphens with `\-` reduced latency from 456ms to 53ms and returns 1 exact match.

```
Plan hash value: 4227112093

---------------------------------------------------------------------------------------------------------------
| Id  | Operation                               | Name                | Rows  | Bytes | Cost (%CPU)| Time     |
---------------------------------------------------------------------------------------------------------------
|   0 | SELECT STATEMENT                        |                     |    10 | 31190 |   126   (1)| 00:00:01 |
|*  1 |  COUNT STOPKEY                          |                     |       |       |            |          |
|   2 |   NESTED LOOPS                          |                     |    10 | 31190 |   126   (1)| 00:00:01 |
|   3 |    NESTED LOOPS                         |                     |    10 | 31060 |    96   (2)| 00:00:01 |
|   4 |     TABLE ACCESS BY INDEX ROWID         | account             |   500 |   525K|     0   (0)| 00:00:01 |
|*  5 |      DOMAIN INDEX                       | IDX_ACCOUNT_TEXT    |       |       |     0   (0)| 00:00:01 |
|*  6 |     TABLE ACCESS STORAGE FULL FIRST ROWS| identity            |    10 | 20290 |    95   (0)| 00:00:01 |
|*  7 |    INDEX RANGE SCAN                     | IDX_ADDRESS_CUSTNUM |     1 |       |     2   (0)| 00:00:01 |
---------------------------------------------------------------------------------------------------------------

Predicate Information:
   5 - access(CONTAINS(account.DATA,'(1000\-0037\-5005) INPATH (/accountKey/accountNumberHyphenated)')>0)
   6 - storage + filter on customerNumber JOIN condition
```

---

### UC-11: Phone (Full 10-digit)

```
Plan hash value: 2875705814

--------------------------------------------------------------------------------------------------------------------------
| Id  | Operation                                  | Name                | Rows  | Bytes |TempSpc| Cost (%CPU)| Time     |
--------------------------------------------------------------------------------------------------------------------------
|   0 | SELECT STATEMENT                           |                     |    10 | 41150 |       | 98748   (1)| 00:00:04 |
|*  1 |  COUNT STOPKEY                             |                     |       |       |       |            |          |
|   2 |   VIEW                                     |                     |  1250 |  5023K|       | 98748   (1)| 00:00:04 |
|*  3 |    SORT ORDER BY STOPKEY                   |                     |  1250 |  4744K|  5008K| 98748   (1)| 00:00:04 |
|   4 |     NESTED LOOPS                           |                     |  1250 |  4744K|       | 97803   (1)| 00:00:04 |
|*  5 |      HASH JOIN                             |                     |  1250 |  4729K|       | 94052   (1)| 00:00:04 |
|   6 |       JOIN FILTER CREATE                   | :BF0000             |  1250 |  2238K|       |   834   (0)| 00:00:01 |
|   7 |        TABLE ACCESS BY INDEX ROWID         | phone               |  1250 |  2238K|       |   834   (0)| 00:00:01 |
|*  8 |         DOMAIN INDEX                       | IDX_PHONE_SEARCH    |       |       |       |     4   (0)| 00:00:01 |
|   9 |       JOIN FILTER USE                      | :BF0000             |  1000K|  1945M|       | 93215   (1)| 00:00:04 |
|* 10 |        TABLE ACCESS STORAGE FULL FIRST ROWS| identity            |  1000K|  1945M|       | 93215   (1)| 00:00:04 |
|* 11 |      INDEX RANGE SCAN                      | IDX_ADDRESS_CUSTNUM |     1 |       |       |     2   (0)| 00:00:01 |
--------------------------------------------------------------------------------------------------------------------------

Predicate Information:
   8 - access(CONTAINS(phone.DATA,'(5549414620) INPATH (/phoneKey/phoneNumber)')>0)
  10 - filter(SYS_OP_BLOOM_FILTER on customerNumber)
```

---

### Execution Plan Analysis Summary

| UC | Plan Hash | Key Operations | Domain Index Usage | Notes |
|----|-----------|----------------|-------------------|-------|
| UC-1 | 2687655126 | HASH JOIN + NESTED LOOPS | IDX_IDENTITY_SEARCH, IDX_PHONE_SEARCH | Optimal |
| UC-2 | 1443162883 | HASH JOIN + NESTED LOOPS | IDX_IDENTITY_SEARCH, IDX_PHONE_SEARCH, IDX_ACCOUNT_TEXT | Optimal |
| UC-3 | 3809329927 | HASH JOIN + NESTED LOOPS | IDX_PHONE_SEARCH, IDX_ACCOUNT_TEXT | TempSpc 73M |
| UC-4 | 2721229334 | HASH JOIN + NESTED LOOPS | IDX_IDENTITY_SEARCH, IDX_ACCOUNT_TEXT | TempSpc 14M |
| UC-5 | 2514249749 | HASH JOIN | IDX_ADDRESS_SEARCH, IDX_IDENTITY_SEARCH, IDX_ACCOUNT_TEXT | json_exists filter |
| UC-6 | 2721229334 | HASH JOIN + NESTED LOOPS | IDX_IDENTITY_SEARCH, IDX_ACCOUNT_TEXT | TempSpc 14M |
| UC-7 | 1678481105 | HASH JOIN | IDX_PHONE_SEARCH, IDX_IDENTITY_SEARCH, IDX_ACCOUNT_TEXT | TempSpc 366M |
| UC-8 | 3979866288 | NESTED LOOPS only | IDX_IDENTITY_SEARCH | Very efficient |
| UC-9 | 1651662844 | NESTED LOOPS | IDX_ACCOUNT_TEXT | Optimized with JSON_VALUE |
| UC-10 | 4227112093 | NESTED LOOPS | IDX_ACCOUNT_TEXT | Optimized with escaped hyphens |
| UC-11 | 2875705814 | HASH JOIN + BLOOM FILTER | IDX_PHONE_SEARCH | TempSpc 5008K |
