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
- **Iterations:** 10
- **Warmup Iterations:** 3
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
| UC-1 | Phone + SSN Last 4 | **7.27 ms** | 7.12 ms | 9.14 ms | 9.14 ms | 137.5/s | 1.0 docs |
| UC-2 | Phone + SSN + Account | **8.20 ms** | 7.98 ms | 9.30 ms | 9.30 ms | 122.0/s | 1.0 docs |
| UC-3 | Phone + Account Last 4 | **6.01 ms** | 6.00 ms | 6.16 ms | 6.16 ms | 166.4/s | 1.0 docs |
| UC-4 | Account + SSN | **5.93 ms** | 5.81 ms | 6.47 ms | 6.47 ms | 168.6/s | 1.0 docs |
| UC-5 | City/State/ZIP + SSN + Account | **11.74 ms** | 10.70 ms | 13.95 ms | 13.95 ms | 85.2/s | 1.0 docs |
| UC-6 | Email + Account Last 4 | **8.78 ms** | 6.62 ms | 26.18 ms | 26.18 ms | 113.9/s | 1.0 docs |
| UC-7 | Email + Phone + Account | **11.18 ms** | 7.68 ms | 39.97 ms | 39.97 ms | 89.4/s | 1.0 docs |

### Detailed Results

#### UC-1: Phone + SSN Last 4
```
Iterations:      10 (+ 3 warmup)
Avg Latency:     7.27 ms
Min Latency:     6.32 ms
Max Latency:     9.14 ms
P50 Latency:     7.12 ms
P95 Latency:     9.14 ms
P99 Latency:     9.14 ms
Throughput:      137.5 ops/sec
Avg Results:     1.0 docs
```

#### UC-2: Phone + SSN + Account
```
Iterations:      10 (+ 3 warmup)
Avg Latency:     8.20 ms
Min Latency:     7.73 ms
Max Latency:     9.30 ms
P50 Latency:     7.98 ms
P95 Latency:     9.30 ms
P99 Latency:     9.30 ms
Throughput:      122.0 ops/sec
Avg Results:     1.0 docs
```

#### UC-3: Phone + Account Last 4
```
Iterations:      10 (+ 3 warmup)
Avg Latency:     6.01 ms
Min Latency:     5.76 ms
Max Latency:     6.16 ms
P50 Latency:     6.00 ms
P95 Latency:     6.16 ms
P99 Latency:     6.16 ms
Throughput:      166.4 ops/sec
Avg Results:     1.0 docs
```

#### UC-4: Account + SSN
```
Iterations:      10 (+ 3 warmup)
Avg Latency:     5.93 ms
Min Latency:     5.63 ms
Max Latency:     6.47 ms
P50 Latency:     5.81 ms
P95 Latency:     6.47 ms
P99 Latency:     6.47 ms
Throughput:      168.6 ops/sec
Avg Results:     1.0 docs
```

#### UC-5: City/State/ZIP + SSN + Account
```
Iterations:      10 (+ 3 warmup)
Avg Latency:     11.74 ms
Min Latency:     10.29 ms
Max Latency:     13.95 ms
P50 Latency:     10.70 ms
P95 Latency:     13.95 ms
P99 Latency:     13.95 ms
Throughput:      85.2 ops/sec
Avg Results:     1.0 docs
```

#### UC-6: Email + Account Last 4
```
Iterations:      10 (+ 3 warmup)
Avg Latency:     8.78 ms
Min Latency:     5.75 ms
Max Latency:     26.18 ms
P50 Latency:     6.62 ms
P95 Latency:     26.18 ms
P99 Latency:     26.18 ms
Throughput:      113.9 ops/sec
Avg Results:     1.0 docs
```

#### UC-7: Email + Phone + Account
```
Iterations:      10 (+ 3 warmup)
Avg Latency:     11.18 ms
Min Latency:     7.16 ms
Max Latency:     39.97 ms
P50 Latency:     7.68 ms
P95 Latency:     39.97 ms
P99 Latency:     39.97 ms
Throughput:      89.4 ops/sec
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

## Implementation Files

| File | Description |
|------|-------------|
| `UcSearchService.java` | Main service class implementing UC 1-7 queries |
| `UcSearchResult.java` | Result model matching PDF response format |
| `SampleDataLoader.java` | Loads correlated test data via 4-way JOIN |
| `HybridSearchCommand.java` | CLI command with `--uc-search-benchmark` option |

---

## Notes

1. **Relevance Scoring:** Oracle Text SCORE() function provides relevance ranking (0-100) for full-text search matches
2. **JOIN Strategy:** All queries use customerNumber as the join key across collections
3. **Address LEFT JOIN:** Address is always LEFT JOINed to handle customers without address records
4. **Performance:** Average latency ranges from 5.93ms (UC-4) to 11.74ms (UC-5), well within acceptable limits
5. **Data Correlation:** SampleDataLoader ensures test parameters are correlated across all 4 collections for realistic testing
