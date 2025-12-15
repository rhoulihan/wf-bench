# UC Unified Search - Benchmark Results

## Table of Contents

- [Executive Summary](#executive-summary)
- [Test Environment](#test-environment)
- [Use Cases (UC 1-7)](#use-cases-uc-1-7)
- [Performance Results](#performance-results)
- [Response Format](#response-format)
- [Data Model](#data-model)
- [Query Implementation](#query-implementation)
- [Appendix: Complete Query Syntax](#appendix-complete-query-syntax)

---

## Executive Summary

This document presents benchmark results for the UC 1-7 unified customer search implementation using Oracle Autonomous Database 23ai with MongoDB API compatibility. The solution leverages the `$sql` aggregation operator with `json_textcontains()` for fuzzy text matching across multiple JSON document collections.

**Key Results:**
- All 7 use cases execute in under 50ms average latency
- Queries perform cross-collection JOINs on 6 million documents
- Fuzzy matching with relevance scoring on all search conditions
- Results ranked by combined relevance score

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

## Use Cases (UC 1-7)

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

---

## Performance Results

### Summary (6 Million Documents)

| UC | Description | Avg Latency | P95 Latency | Throughput | Status |
|----|-------------|-------------|-------------|------------|--------|
| UC-1 | Phone + SSN | **37.62 ms** | 38.50 ms | 26.6 qps | OK |
| UC-2 | Phone + SSN + Account | **40.77 ms** | 42.10 ms | 24.5 qps | OK |
| UC-3 | Phone + Account Last 4 | **8.12 ms** | 8.70 ms | 123.1 qps | OK |
| UC-4 | Account + SSN | **36.19 ms** | 37.50 ms | 27.6 qps | OK |
| UC-5 | City/State/ZIP + SSN + Account | **41.45 ms** | 43.20 ms | 24.1 qps | OK |
| UC-6 | Email + Account Last 4 | **7.93 ms** | 8.30 ms | 126.1 qps | OK |
| UC-7 | Email + Phone + Account | **9.46 ms** | 9.80 ms | 105.7 qps | OK |

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
