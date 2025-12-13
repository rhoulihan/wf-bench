# UC Unified Search Summary

## Overview

This document provides a comprehensive summary of the UC 1-7 unified search implementation using Oracle Text SCORE() with full JSON search indexes on Oracle Autonomous Database 23ai.

**Test Date:** December 13, 2025
**Database:** Oracle Autonomous Database 23ai (wellsfargo_low service)
**Region:** US-Ashburn-1

---

## Configuration

### Database Connection
- **JDBC URL:** `jdbc:oracle:thin:@wellsfargo_low?TNS_ADMIN=/home/opc/rick/wallet_wellsfargo`
- **Service Level:** LOW (shared resources)
- **Connection Pool Size:** 10

### Benchmark Parameters
- **Iterations:** 20
- **Warmup Iterations:** 5
- **Result Limit:** 10 documents per query

### Collections
| Collection | Description | Key Fields |
|------------|-------------|------------|
| `identity` | Customer identity records | customerNumber, fullName, taxIdentificationNumberLast4, emails |
| `phone` | Phone number records | phoneKey.customerNumber, phoneKey.phoneNumber |
| `account` | Account records | accountHolders[0].customerNumber, accountKey.accountNumber, accountKey.accountNumberLast4 |
| `address` | Address records | _id.customerNumber, addresses[0].cityName, addresses[0].stateCode, addresses[0].postalCode |

### Indexes
Full JSON search indexes created for Oracle Text CONTAINS() operations:
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

## Use Cases (UC 1-7)

### UC-1: Phone + SSN Last 4
**Description:** Search for customers by phone number and last 4 digits of SSN
**Collections:** phone, identity, address (LEFT JOIN)
**Input Parameters:**
- Phone Number (full-text search via CONTAINS)
- SSN Last 4 (exact match)

**Query Pattern:**
```sql
SELECT SCORE(1) as ranking_score, ...
FROM phone p
JOIN identity i ON json_value(p.DATA, '$.phoneKey.customerNumber') =
                   json_value(i.DATA, '$._id.customerNumber')
LEFT JOIN address a ON json_value(a.DATA, '$._id.customerNumber') =
                       json_value(i.DATA, '$._id.customerNumber')
WHERE CONTAINS(p.DATA, ?, 1) > 0
  AND json_value(i.DATA, '$.common.taxIdentificationNumberLast4') = ?
ORDER BY SCORE(1) DESC
FETCH FIRST ? ROWS ONLY
```

---

### UC-2: Phone + SSN Last 4 + Account Last 4
**Description:** Search for customers by phone, SSN last 4, and account last 4
**Collections:** phone, identity, account, address (LEFT JOIN)
**Input Parameters:**
- Phone Number (full-text search)
- SSN Last 4 (exact match)
- Account Last 4 (exact match)

**Query Pattern:**
```sql
SELECT SCORE(1) as ranking_score, ...
FROM phone p
JOIN identity i ON json_value(p.DATA, '$.phoneKey.customerNumber') =
                   json_value(i.DATA, '$._id.customerNumber')
JOIN account a ON json_value(i.DATA, '$._id.customerNumber') =
                  json_value(a.DATA, '$.accountHolders[0].customerNumber')
LEFT JOIN address addr ON json_value(addr.DATA, '$._id.customerNumber') =
                          json_value(i.DATA, '$._id.customerNumber')
WHERE CONTAINS(p.DATA, ?, 1) > 0
  AND json_value(i.DATA, '$.common.taxIdentificationNumberLast4') = ?
  AND json_value(a.DATA, '$.accountKey.accountNumberLast4') = ?
ORDER BY SCORE(1) DESC
FETCH FIRST ? ROWS ONLY
```

---

### UC-3: Phone + Account Last 4
**Description:** Search for customers by phone number and account last 4
**Collections:** phone, identity, account, address (LEFT JOIN)
**Input Parameters:**
- Phone Number (full-text search)
- Account Last 4 (exact match)

**Query Pattern:**
```sql
SELECT SCORE(1) as ranking_score, ...
FROM phone p
JOIN identity i ON json_value(p.DATA, '$.phoneKey.customerNumber') =
                   json_value(i.DATA, '$._id.customerNumber')
JOIN account a ON json_value(i.DATA, '$._id.customerNumber') =
                  json_value(a.DATA, '$.accountHolders[0].customerNumber')
LEFT JOIN address addr ON json_value(addr.DATA, '$._id.customerNumber') =
                          json_value(i.DATA, '$._id.customerNumber')
WHERE CONTAINS(p.DATA, ?, 1) > 0
  AND json_value(a.DATA, '$.accountKey.accountNumberLast4') = ?
ORDER BY SCORE(1) DESC
FETCH FIRST ? ROWS ONLY
```

---

### UC-4: Account Number + SSN Last 4
**Description:** Search for customers by full account number and SSN last 4
**Collections:** account, identity, address (LEFT JOIN)
**Input Parameters:**
- Account Number (full-text search)
- SSN Last 4 (exact match)

**Query Pattern:**
```sql
SELECT SCORE(1) as ranking_score, ...
FROM account a
JOIN identity i ON json_value(a.DATA, '$.accountHolders[0].customerNumber') =
                   json_value(i.DATA, '$._id.customerNumber')
LEFT JOIN address addr ON json_value(addr.DATA, '$._id.customerNumber') =
                          json_value(i.DATA, '$._id.customerNumber')
WHERE CONTAINS(a.DATA, ?, 1) > 0
  AND json_value(i.DATA, '$.common.taxIdentificationNumberLast4') = ?
ORDER BY SCORE(1) DESC
FETCH FIRST ? ROWS ONLY
```

---

### UC-5: City/State/ZIP + SSN Last 4 + Account Last 4
**Description:** Search for customers by geographic location, SSN last 4, and account last 4
**Collections:** address, identity, account
**Input Parameters:**
- City (full-text search)
- State Code (exact match)
- ZIP/Postal Code (exact match)
- SSN Last 4 (exact match)
- Account Last 4 (exact match)

**Query Pattern:**
```sql
SELECT SCORE(1) as ranking_score, ...
FROM address addr
JOIN identity i ON json_value(addr.DATA, '$._id.customerNumber') =
                   json_value(i.DATA, '$._id.customerNumber')
JOIN account a ON json_value(i.DATA, '$._id.customerNumber') =
                  json_value(a.DATA, '$.accountHolders[0].customerNumber')
WHERE CONTAINS(addr.DATA, ?, 1) > 0
  AND json_value(addr.DATA, '$.addresses[0].stateCode') = ?
  AND json_value(addr.DATA, '$.addresses[0].postalCode') = ?
  AND json_value(i.DATA, '$.common.taxIdentificationNumberLast4') = ?
  AND json_value(a.DATA, '$.accountKey.accountNumberLast4') = ?
ORDER BY SCORE(1) DESC
FETCH FIRST ? ROWS ONLY
```

---

### UC-6: Email + Account Last 4
**Description:** Search for customers by email address and account last 4
**Collections:** identity, account, address (LEFT JOIN)
**Input Parameters:**
- Email Address (full-text search)
- Account Last 4 (exact match)

**Query Pattern:**
```sql
SELECT SCORE(1) as ranking_score, ...
FROM identity i
JOIN account a ON json_value(i.DATA, '$._id.customerNumber') =
                  json_value(a.DATA, '$.accountHolders[0].customerNumber')
LEFT JOIN address addr ON json_value(addr.DATA, '$._id.customerNumber') =
                          json_value(i.DATA, '$._id.customerNumber')
WHERE CONTAINS(i.DATA, ?, 1) > 0
  AND json_value(a.DATA, '$.accountKey.accountNumberLast4') = ?
ORDER BY SCORE(1) DESC
FETCH FIRST ? ROWS ONLY
```

---

### UC-7: Email + Phone + Account Number
**Description:** Search for customers by email, phone, and full account number
**Collections:** identity, phone, account, address (LEFT JOIN)
**Input Parameters:**
- Email Address (full-text search)
- Phone Number (exact match)
- Account Number (exact match)

**Query Pattern:**
```sql
SELECT SCORE(1) as ranking_score, ...
FROM identity i
JOIN phone p ON json_value(i.DATA, '$._id.customerNumber') =
                json_value(p.DATA, '$.phoneKey.customerNumber')
JOIN account a ON json_value(i.DATA, '$._id.customerNumber') =
                  json_value(a.DATA, '$.accountHolders[0].customerNumber')
LEFT JOIN address addr ON json_value(addr.DATA, '$._id.customerNumber') =
                          json_value(i.DATA, '$._id.customerNumber')
WHERE CONTAINS(i.DATA, ?, 1) > 0
  AND json_value(p.DATA, '$.phoneKey.phoneNumber') = ?
  AND json_value(a.DATA, '$.accountKey.accountNumber') = ?
ORDER BY SCORE(1) DESC
FETCH FIRST ? ROWS ONLY
```

---

## Response Format

Each UC query returns results in the following format:

| Field | Type | Description |
|-------|------|-------------|
| `rankingScore` | int | Oracle Text SCORE() relevance ranking (0-100) |
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

## Benchmark Results

### Test Configuration
- **Sample Data:** 20 correlated parameter sets loaded via 4-way JOIN
- **Data Loading:** SampleDataLoader performs 4-way JOIN across phone, identity, account, and address to ensure correlated test data

### Performance Summary

| UC | Description | Avg Latency | P50 | P95 | P99 | Throughput | Avg Results |
|----|-------------|-------------|-----|-----|-----|------------|-------------|
| UC-1 | Phone + SSN Last 4 | **6.86 ms** | 6.31 ms | 8.39 ms | 8.79 ms | 145.7/s | 1.0 docs |
| UC-2 | Phone + SSN + Account | **7.87 ms** | 7.43 ms | 9.83 ms | 11.00 ms | 127.0/s | 1.0 docs |
| UC-3 | Phone + Account Last 4 | **5.51 ms** | 5.48 ms | 5.77 ms | 6.58 ms | 181.6/s | 1.0 docs |
| UC-4 | Account + SSN | **5.43 ms** | 5.23 ms | 6.68 ms | 7.51 ms | 184.0/s | 1.0 docs |
| UC-5 | City/State/ZIP + SSN + Account | **9.88 ms** | 9.38 ms | 11.68 ms | 12.28 ms | 101.2/s | 1.0 docs |
| UC-6 | Email + Account Last 4 | **5.21 ms** | 5.18 ms | 5.52 ms | 5.86 ms | 191.8/s | 1.0 docs |
| UC-7 | Email + Phone + Account | **6.58 ms** | 6.52 ms | 7.40 ms | 7.58 ms | 152.1/s | 1.0 docs |

### Detailed Results

#### UC-1: Phone + SSN Last 4
```
Iterations:      20 (+ 5 warmup)
Avg Latency:     6.86 ms
Min Latency:     5.81 ms
Max Latency:     8.79 ms
P50 Latency:     6.31 ms
P95 Latency:     8.39 ms
P99 Latency:     8.79 ms
Throughput:      145.7 ops/sec
Avg Results:     1.0 docs
```

#### UC-2: Phone + SSN + Account
```
Iterations:      20 (+ 5 warmup)
Avg Latency:     7.87 ms
Min Latency:     6.94 ms
Max Latency:     11.00 ms
P50 Latency:     7.43 ms
P95 Latency:     9.83 ms
P99 Latency:     11.00 ms
Throughput:      127.0 ops/sec
Avg Results:     1.0 docs
```

#### UC-3: Phone + Account Last 4
```
Iterations:      20 (+ 5 warmup)
Avg Latency:     5.51 ms
Min Latency:     5.16 ms
Max Latency:     6.58 ms
P50 Latency:     5.48 ms
P95 Latency:     5.77 ms
P99 Latency:     6.58 ms
Throughput:      181.6 ops/sec
Avg Results:     1.0 docs
```

#### UC-4: Account + SSN
```
Iterations:      20 (+ 5 warmup)
Avg Latency:     5.43 ms
Min Latency:     4.91 ms
Max Latency:     7.51 ms
P50 Latency:     5.23 ms
P95 Latency:     6.68 ms
P99 Latency:     7.51 ms
Throughput:      184.0 ops/sec
Avg Results:     1.0 docs
```

#### UC-5: City/State/ZIP + SSN + Account
```
Iterations:      20 (+ 5 warmup)
Avg Latency:     9.88 ms
Min Latency:     8.81 ms
Max Latency:     12.28 ms
P50 Latency:     9.38 ms
P95 Latency:     11.68 ms
P99 Latency:     12.28 ms
Throughput:      101.2 ops/sec
Avg Results:     1.0 docs
```

#### UC-6: Email + Account Last 4
```
Iterations:      20 (+ 5 warmup)
Avg Latency:     5.21 ms
Min Latency:     4.84 ms
Max Latency:     5.86 ms
P50 Latency:     5.18 ms
P95 Latency:     5.52 ms
P99 Latency:     5.86 ms
Throughput:      191.8 ops/sec
Avg Results:     1.0 docs
```

#### UC-7: Email + Phone + Account
```
Iterations:      20 (+ 5 warmup)
Avg Latency:     6.58 ms
Min Latency:     5.04 ms
Max Latency:     7.58 ms
P50 Latency:     6.52 ms
P95 Latency:     7.40 ms
P99 Latency:     7.58 ms
Throughput:      152.1 ops/sec
Avg Results:     1.0 docs
```

---

## Key JSON Paths

### Identity Collection
| Path | Description |
|------|-------------|
| `$._id.customerNumber` | Customer number (primary key) |
| `$._id.customerCompanyNumber` | Company number |
| `$.common.entityTypeIndicator` | INDIVIDUAL or NON_INDIVIDUAL |
| `$.common.fullName` | Full name |
| `$.common.taxIdentificationNumberLast4` | Last 4 digits of SSN/EIN |
| `$.common.taxIdentificationNumber` | Full SSN/EIN |
| `$.common.taxIdentificationType` | SSN, EIN, or ITIN |
| `$.common.customerType` | Customer, Prospect, Youth Banking |
| `$.individual.firstName` | First name (individuals) |
| `$.individual.dateOfBirth` | Date of birth (individuals) |
| `$.nonIndividual.businessDescriptionText` | Business description (non-individuals) |
| `$.emails[0].emailAddress` | Primary email address |

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

### Address Collection
| Path | Description |
|------|-------------|
| `$._id.customerNumber` | Customer number (foreign key) |
| `$.addresses[0].addressLine1` | Street address |
| `$.addresses[0].cityName` | City |
| `$.addresses[0].stateCode` | State code |
| `$.addresses[0].postalCode` | ZIP code |
| `$.addresses[0].countryCode` | Country code |

---

## CLI Usage

### Run UC Search Benchmark
```bash
java --enable-preview -jar target/wf-bench-1.0.0-SNAPSHOT.jar hybrid-search \
  --jdbc-url 'jdbc:oracle:thin:@wellsfargo_low?TNS_ADMIN=/path/to/wallet' \
  -u ADMIN \
  -p <password> \
  --uc-search-benchmark \
  -i 10 \
  -w 3
```

### Create Search Indexes
```bash
java --enable-preview -jar target/wf-bench-1.0.0-SNAPSHOT.jar hybrid-search \
  --jdbc-url 'jdbc:oracle:thin:@wellsfargo_low?TNS_ADMIN=/path/to/wallet' \
  -u ADMIN \
  -p <password> \
  --create-uc-search-indexes
```

---

## MongoDB $sql Operator Benchmark (December 13, 2025)

### Overview
In addition to JDBC-based queries, UC 1-7 searches can be executed using the MongoDB API's `$sql` aggregation operator with `json_textcontains()` function. This approach allows SQL queries to be executed through the MongoDB wire protocol.

### Implementation Details
- Uses `db.aggregate([{"$sql": "SELECT ..."}])` pattern
- Text search via `json_textcontains("DATA", '$.path', 'term', label)`
- Scalar value extraction via `JSON_VALUE("DATA", '$.path')`
- CTE (WITH clause) pattern for multi-collection joins
- `/*+ DOMAIN_INDEX_SORT */` hint for optimized score sorting

### MongoDB $sql Benchmark Results

| UC | Description | Avg Latency | P95 | Throughput | Status |
|----|-------------|-------------|-----|------------|--------|
| UC-1 | Phone + SSN Last 4 | **21.91 ms** | 28.26 ms | 45.6/s | 20/20 |
| UC-2 | Phone + SSN + Account | **35.05 ms** | 43.33 ms | 28.5/s | 20/20 |
| UC-3 | Phone + Account Last 4 | **39.38 ms** | 47.49 ms | 25.4/s | 20/20 |
| UC-4 | Account + SSN | **21.54 ms** | 39.10 ms | 46.4/s | 12/20* |
| UC-5 | City/State/ZIP + SSN + Account | - | - | - | 0/20** |
| UC-6 | Email + Account Last 4 | - | - | - | 0/20** |
| UC-7 | Email + Phone + Account | - | - | - | 0/20** |

**Notes:**
- *UC-4: 8 failures due to Oracle Text parser errors on certain account number patterns
- **UC-5, UC-6, UC-7: `json_textcontains()` does not support array index paths (e.g., `$."emails"[0]."emailAddress"`)

### Known Issues
1. **Array Index Paths:** `json_textcontains()` does not accept array index syntax in JSON paths. Paths like `$."addresses"[0]."cityName"` return `ORA-40469: JSON path expression in JSON_TEXTCONTAINS() is invalid`
2. **Account Number Parser:** Some account number patterns cause Oracle Text parser errors (`DRG-50901`)

### Comparison: JDBC vs MongoDB $sql

| Metric | JDBC/SQL | MongoDB $sql |
|--------|----------|--------------|
| UC-1 Latency | 6.86 ms | 21.91 ms |
| UC-2 Latency | 7.87 ms | 35.05 ms |
| UC-3 Latency | 5.51 ms | 39.38 ms |
| Protocol | Direct JDBC | MongoDB Wire Protocol |
| Text Search | CONTAINS() | json_textcontains() |

The JDBC approach is approximately 3-4x faster due to direct database connectivity, but MongoDB $sql provides an alternative for applications using the MongoDB driver.

---

## Implementation Files

| File | Description |
|------|-------------|
| `MongoSqlSearchService.java` | UC 1-7 using MongoDB $sql operator with json_textcontains() |
| `MongoSqlSearchCommand.java` | CLI command for MongoDB $sql UC benchmarks |
| `UcSearchResult.java` | Result model matching PDF response format |
| `SampleDataLoader.java` | Loads correlated test data via 4-way JOIN |
| `HybridSearchCommand.java` | CLI command for fuzzy, phonetic, vector search |

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

### JDBC UC Search Benchmark (Legacy)
```bash
java --enable-preview -jar target/wf-bench-1.0.0-SNAPSHOT.jar hybrid-search \
  --jdbc-url 'jdbc:oracle:thin:@wellsfargo_low?TNS_ADMIN=/path/to/wallet' \
  -u ADMIN \
  -p <password> \
  --uc-search-benchmark \
  -i 10 \
  -w 3
```

### Create Search Indexes
```bash
java --enable-preview -jar target/wf-bench-1.0.0-SNAPSHOT.jar hybrid-search \
  --jdbc-url 'jdbc:oracle:thin:@wellsfargo_low?TNS_ADMIN=/path/to/wallet' \
  -u ADMIN \
  -p <password> \
  --create-uc-search-indexes
```

---

## Notes

1. **Relevance Scoring:** Oracle Text SCORE() function provides relevance ranking (0-100) for full-text search matches
2. **JOIN Strategy:** All queries use customerNumber as the join key across collections
3. **Address LEFT JOIN:** Address is always LEFT JOINed to handle customers without address records
4. **JDBC Performance:** Average latency ranges from 5.21ms (UC-6) to 9.88ms (UC-5), well within acceptable limits
5. **MongoDB $sql Performance:** UC-1 through UC-3 work reliably with 20-40ms latency
6. **Data Correlation:** SampleDataLoader ensures test parameters are correlated across all 4 collections for realistic testing
