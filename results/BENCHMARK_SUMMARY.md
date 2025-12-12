# WF Benchmark Results

**Last Updated:** 2025-12-11

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Test Environment](#test-environment)
3. [Performance Results](#performance-results)
   - [UC 1-7 Multi-Collection Queries](#uc-1-7-multi-collection-queries)
   - [Hybrid Search (Fuzzy/Phonetic)](#hybrid-search-fuzzyphonetic)
   - [Single-Collection Queries](#single-collection-queries)
   - [Aggregation Queries](#aggregation-queries)
4. [Index Configuration](#index-configuration)
5. [Implementation Details](#implementation-details)
6. [CLI Commands](#cli-commands)

---

## Executive Summary

| Metric | Value |
|--------|-------|
| Total Queries Tested | 35+ |
| UC Multi-Collection Joins | 7 (UC-1 through UC-7) |
| Hybrid Search Types | 4 (fuzzy, phonetic, hybrid, business) |
| MongoDB B-Tree Indexes | 21 |
| Oracle Text Search Indexes | 4 |
| Unit Tests | 214 (all passing) |

### Performance Highlights

| Query Type | Avg Latency | Throughput |
|------------|-------------|------------|
| UC SQL JOINs (DBMS_SEARCH) | 5-7ms | 150-200/s |
| Fuzzy Text Search | 4.5ms | 224/s |
| Phonetic (SOUNDEX) | 7.4ms | 134/s |
| Single-Field Lookups | 2-3ms | 400-450/s |

---

## Test Environment

| Component | Value |
|-----------|-------|
| Database | Oracle Autonomous JSON Database |
| Region | US-Ashburn-1 |
| Java | OpenJDK 23.0.1+11 (preview features) |
| MongoDB Driver | 5.2.1 |
| Connection | MongoDB API for Oracle (ORDS) + JDBC |

### Data Scales

| Scale | Identity | Address | Phone | Account | Total |
|-------|----------|---------|-------|---------|-------|
| SMALL | 10,000 | 10,000 | 25,000 | 15,000 | 60,000 |
| LARGE | 1,000,000 | 1,000,000 | 2,500,000 | 1,000,000 | 5,500,000 |

---

## Performance Results

### UC 1-7 Multi-Collection Queries

These use cases implement the Wells Fargo RFP requirements for cross-collection customer search.

#### UC Query Definitions

| UC | Description | Collections | Join Type |
|----|-------------|-------------|-----------|
| UC-1 | Phone + SSN Last 4 | phone → identity | 2-way |
| UC-2 | Phone + SSN Last 4 + Account Last 4 | phone → identity → account | 3-way |
| UC-3 | Phone + Account Last 4 | phone → identity → account | 3-way |
| UC-4 | Account Number + SSN Last 4 | account → identity | 2-way |
| UC-5 | City/State/ZIP + SSN Last 4 + Account Last 4 | address → identity → account | 3-way |
| UC-6 | Email + Account Last 4 | identity → account | 2-way |
| UC-7 | Email + Phone + Account Number | identity → phone → account | 3-way |

#### SQL JOIN with DBMS_SEARCH (Recommended)

Uses Oracle Text full JSON search indexes with SCORE() relevance ranking.

**Data Scale:** SMALL (60K docs) | **Config:** 5 iterations, 1 warmup

| Query | Avg (ms) | P50 (ms) | P95 (ms) | Throughput | Docs |
|-------|----------|----------|----------|------------|------|
| UC-1 | 5.30 | 5.14 | 6.24 | 188.5/s | 1.0 |
| UC-2 | 6.76 | 6.68 | 7.18 | 147.9/s | 1.0 |
| UC-4 | 4.96 | 4.78 | 5.70 | 201.6/s | 1.0 |
| UC-6 | 5.20 | 5.09 | 5.59 | 192.1/s | 1.0 |

**Indexes Required:**
```sql
CREATE SEARCH INDEX idx_identity_search ON identity(DATA) FOR JSON;
CREATE SEARCH INDEX idx_phone_search ON phone(DATA) FOR JSON;
CREATE SEARCH INDEX idx_account_search ON account(DATA) FOR JSON;
CREATE SEARCH INDEX idx_address_search ON address(DATA) FOR JSON;
```

#### MongoDB API Sequential Joins

Uses multiple `db.collection.find()` calls per query (no `$lookup` support).

**Data Scale:** LARGE (5.5M docs) | **Config:** 100 iterations, 10 warmup

| Query | Avg (ms) | P95 (ms) | Throughput | Docs |
|-------|----------|----------|------------|------|
| UC-1 | 7.87 | 8.85 | 127.0/s | 1 |
| UC-2 | 6.91 | 7.73 | 144.6/s | 1 |
| UC-3 | 453.66 | 507.90 | 2.2/s | 1 |
| UC-4 | 2.33 | 2.27 | 428.5/s | 1 |
| UC-5 | 246.80 | 612.35 | 4.1/s | 3.8 |
| UC-6 | 12.21 | 27.65 | 81.9/s | 1.1 |
| UC-7 | 5.75 | 9.37 | 174.0/s | 1.1 |

---

### Hybrid Search (Fuzzy/Phonetic)

Uses Oracle SQL/JDBC for features not available in MongoDB API.

**Data Scale:** LARGE (1M identity) | **Config:** 10 iterations, 3 warmup

| Query | Description | Avg (ms) | P50 (ms) | P95 (ms) | Throughput | Docs |
|-------|-------------|----------|----------|----------|------------|------|
| fuzzy_name_search | Typo-tolerant name matching | 4.47 | 3.91 | 9.11 | 223.9/s | 1.2 |
| fuzzy_business_search | Business name search | 6.04 | 5.64 | 9.28 | 165.5/s | 0.4 |
| phonetic_name_search | SOUNDEX sound-alike matching | 7.44 | 7.34 | 7.71 | 134.4/s | 1.2 |
| hybrid_name_search | Combined phonetic + fuzzy | 12.28 | 12.16 | 13.45 | 81.5/s | 1.4 |

#### SQL Examples

**Fuzzy Search:**
```sql
SELECT json_value(DATA, '$._id.customerNumber'), json_value(DATA, '$.common.fullName'), SCORE(1)
FROM identity
WHERE CONTAINS(DATA, 'fuzzy(JOHN) AND fuzzy(SMITH)', 1) > 0
ORDER BY SCORE(1) DESC
FETCH FIRST 10 ROWS ONLY
```

**Phonetic Search:**
```sql
SELECT json_value(DATA, '$._id.customerNumber'), json_value(DATA, '$.common.fullName')
FROM identity
WHERE SOUNDEX(json_value(DATA, '$.individual.firstName')) = SOUNDEX('Jon')
  AND SOUNDEX(json_value(DATA, '$.individual.lastName')) = SOUNDEX('Smyth')
FETCH FIRST 10 ROWS ONLY
```

---

### Single-Collection Queries

**Data Scale:** LARGE (5.5M docs) | **Config:** 100 iterations, 10 warmup

#### High Performance (< 5ms)

| Query | Description | Avg (ms) | Throughput | Docs |
|-------|-------------|----------|------------|------|
| os2_account_full_search | Full account number | 2.25 | 445.0/s | 1 |
| os3_account_tokenized_search | Tokenized account | 2.26 | 443.4/s | 1 |
| account_by_customer | Customer's accounts | 2.39 | 417.9/s | 2.7 |
| os4_phone_full_search | Full phone number | 2.52 | 396.2/s | 1 |
| wr_s_id_document_search | DL/Passport lookup | 2.67 | 375.2/s | 1 |
| wr_e_email_search | Email address | 2.71 | 368.5/s | 1.2 |
| os1_tin_full_search | Full 9-digit SSN/TIN | 2.77 | 361.3/s | 1 |
| wr_f_dob_with_name | DOB + name (correlated) | 3.35 | 298.4/s | 3.8 |
| wr_c_zip_only | ZIP code only | 4.79 | 208.6/s | 23.8 |

#### Medium Performance (5-15ms)

| Query | Description | Avg (ms) | Throughput | Docs |
|-------|-------------|----------|------------|------|
| wr_q_tin_last4_with_name | TIN last 4 + name | 5.17 | 193.3/s | 1 |
| wr_h_full_name_search | First + Last name | 6.18 | 161.9/s | 4.8 |
| account_last4_search | Account last 4 digits | 6.96 | 143.6/s | 100 |
| wr_g_entity_type_filter | Entity type filter | 9.12 | 109.6/s | 100 |

#### Address Queries (High Variance)

| Query | Description | Avg (ms) | P95 (ms) | Throughput |
|-------|-------------|----------|----------|------------|
| wr_b_address_with_name | State + ZIP | 222.88 | 594.43 | 4.5/s |

*Note: High variance due to multikey array index behavior on `addresses[]` field.*

---

### Aggregation Queries

Full collection scans - significantly slower by design.

| Query | Description | Avg (ms) | Throughput |
|-------|-------------|----------|------------|
| baseline_count_all | Count all docs | ~500 | ~2.0/s |
| agg_count_by_entity_type | Group by entity type | ~1,800 | ~0.6/s |
| agg_phone_type_distribution | Phone type distribution | ~4,600 | ~0.2/s |
| agg_account_holder_distribution | Account holder counts | ~9,800 | ~0.1/s |
| agg_email_count_distribution | Email count distribution | ~9,900 | ~0.1/s |
| agg_count_by_state | Count by state | ~16,100 | ~0.1/s |

---

## Index Configuration

### MongoDB B-Tree Indexes (21 total)

#### Identity Collection (9)

| Index | Keys | Use Cases |
|-------|------|-----------|
| idx_identity_tin_full | `common.taxIdentificationNumber` | OS-1: Full SSN |
| idx_identity_tin_last4 | `common.taxIdentificationNumberLast4` | UC-1,2,4,5, WR-Q |
| idx_identity_fullname | `common.fullName` | WR-H: Name search |
| idx_identity_name_parts | `individual.lastName, individual.firstName` | WR-H: Structured name |
| idx_identity_entity_type | `common.entityTypeIndicator` | WR-G: Entity filter |
| idx_identity_dob | `individual.birthDate` | WR-F: DOB search |
| idx_identity_id_docs | `common.identifications.identificationNumber` | WR-S: DL/Passport |
| idx_identity_ecn | `common.ecn` (sparse) | WR-K: ECN lookup |
| idx_identity_email | `emails.emailAddress` (multikey) | UC-6,7, WR-E |

#### Phone Collection (3)

| Index | Keys | Use Cases |
|-------|------|-----------|
| idx_phone_number | `phoneKey.phoneNumber` | OS-4, UC-1,2,3,7 |
| idx_phone_customer | `phoneKey.customerNumber, customerCompanyNumber` | Join lookups |
| idx_phone_type | `phoneKey.phoneNumberTypeCode` | Type filter |

#### Account Collection (5)

| Index | Keys | Use Cases |
|-------|------|-----------|
| idx_account_full | `accountKey.accountNumber` | OS-2: Full account |
| idx_account_last4 | `accountKey.accountNumberLast4` | UC-2,3,4,5,6,7 |
| idx_account_tokenized | `accountKey.accountNumberTokenized` (sparse) | OS-3 |
| idx_account_holders | `accountHolders.customerNumber` (multikey) | Join lookups |
| idx_account_product_coid | `productTypeCode, companyOfInterestId` | OS-2 |

#### Address Collection (4)

| Index | Keys | Use Cases |
|-------|------|-----------|
| idx_address_city_state_zip | `stateCode, cityName, postalCode` | UC-5 |
| idx_address_state_zip | `stateCode, postalCode` | WR-B |
| idx_address_zip | `postalCode` | WR-C |
| idx_address_customer | `_id.customerNumber, customerCompanyNumber` | Lookups |

### Oracle Text Search Indexes (4)

```sql
CREATE SEARCH INDEX idx_identity_search ON identity(DATA) FOR JSON;
CREATE SEARCH INDEX idx_phone_search ON phone(DATA) FOR JSON;
CREATE SEARCH INDEX idx_account_search ON account(DATA) FOR JSON;
CREATE SEARCH INDEX idx_address_search ON address(DATA) FOR JSON;
```

---

## Implementation Details

### MongoDB API Limitations

| Feature | MongoDB API | Workaround |
|---------|-------------|------------|
| `$lookup` aggregation | Not supported | Sequential find() calls |
| `$text` search | Not supported | Oracle Text CONTAINS() |
| `$vectorSearch` | Not supported | Oracle AI Vector Search |
| SOUNDEX/phonetic | Not supported | Oracle SOUNDEX function |

### Correlated Parameters

The benchmark tool supports extracting multiple parameters from the same random document:

```yaml
parameters:
  dob:
    type: "random_from_loaded"
    field: "individual.birthDate"
    correlationGroup: "identity_dob_name"  # Same group
  fullName:
    type: "random_from_loaded"
    field: "common.fullName"
    correlationGroup: "identity_dob_name"  # Same group
```

### Multi-Collection Join YAML Syntax

```yaml
- name: "uc2_phone_ssn_account"
  collection: "phone"
  filter:
    phoneKey.phoneNumber: "${param:phoneNumber}"
  join:
    collection: "identity"
    localField: "phoneKey.customerNumber"
    foreignField: "_id.customerNumber"
    filter:
      common.taxIdentificationNumberLast4: "${param:ssnLast4}"
    join:  # Chained join
      collection: "account"
      localField: "_id.customerNumber"
      foreignField: "accountHolders.customerNumber"
      filter:
        accountKey.accountNumberLast4: "${param:accountLast4}"
```

---

## CLI Commands

### MongoDB API Benchmarks

```bash
java --enable-preview -jar wf-bench-1.0.0-SNAPSHOT.jar query \
  --connection-string "$CONN" \
  --config-file config/sample-query-config.yaml \
  --create-indexes \
  --threads 12 \
  --iterations 100 \
  --warmup 10
```

### Hybrid Search Benchmarks

```bash
java --enable-preview -jar wf-bench-1.0.0-SNAPSHOT.jar hybrid-search \
  --jdbc-url "$JDBC_URL" \
  -u ADMIN -p "$PASSWORD" \
  --benchmark \
  --iterations 100 \
  --warmup 10 \
  --disable-vector
```

### UC SQL JOIN Benchmarks

```bash
java --enable-preview -jar wf-bench-1.0.0-SNAPSHOT.jar hybrid-search \
  --jdbc-url "$JDBC_URL" \
  -u ADMIN -p "$PASSWORD" \
  --create-uc-search-indexes \
  --uc-benchmark \
  --iterations 10 \
  --warmup 3
```

### Data Loading

```bash
java --enable-preview -jar wf-bench-1.0.0-SNAPSHOT.jar load \
  --connection-string "$CONN" \
  --scale LARGE \
  --threads 12 \
  --batch-size 1000 \
  --drop-existing
```
