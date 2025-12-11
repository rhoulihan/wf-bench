# WF Benchmark Results

**Environment:** Oracle Autonomous JSON Database (MongoDB API)
**Data Scale:** LARGE (1M identity, 1M address, 2.5M phone, 1M account documents)
**Test Configuration:** 12 threads, 10 iterations + 3 warmup per query

---

## Executive Summary

Successfully executed 26 query benchmarks with correlated parameter support. The correlated parameters feature enables extracting multiple related parameter values from the same randomly-selected document, ensuring realistic query patterns where filters on DOB+name or firstName+lastName actually match existing data.

### Key Results

| Metric | Value |
|--------|-------|
| Total Queries | 26 |
| Queries Passed | 25 (96%) |
| Queries Failed | 1 (baseline_pk_lookup - compound _id extraction issue) |
| Indexes Created | 20 |
| Concurrent Threads | 12 |
| Iterations per Query | 10 (+ 3 warmup) |

---

## Query Performance Results

### High Performance Queries (< 10ms avg)

| Query | Description | Avg (ms) | P95 (ms) | Throughput | Docs |
|-------|-------------|----------|----------|------------|------|
| os2_account_full_search | Full account number | 4.58 | 23.89 | 218.5/s | 1 |
| os3_account_tokenized_search | Tokenized account | 5.29 | 22.88 | 188.9/s | 1 |
| os4_phone_full_search | Full phone number | 5.68 | 34.59 | 176.2/s | 1 |
| uc7_email_phone_account | Email search (correlated) | 5.69 | 34.69 | 175.6/s | 1.6 |
| uc6_email_account_last4 | Email + account last 4 | 6.02 | 36.96 | 166.1/s | 1 |
| account_last4_search | Account last 4 digits | 6.45 | 6.96 | 155.0/s | 100 |
| wr_e_email_search | Email address (embedded) | 6.58 | 35.74 | 152.0/s | 2.2 |
| wr_s_id_document_search | Driver's License/Passport | 6.69 | 44.70 | 149.6/s | 1 |
| uc1_phone_ssn_last4 | Phone + SSN last 4 | 7.58 | 44.93 | 131.9/s | 1 |
| wr_g_entity_type_filter | Entity type filter | 9.55 | 10.32 | 104.7/s | 100 |
| os1_tin_full_search | Full 9-digit TIN/SSN | 9.77 | 73.98 | 102.4/s | 1 |

### Medium Performance Queries (10-50ms avg)

| Query | Description | Avg (ms) | P95 (ms) | Throughput | Docs |
|-------|-------------|----------|----------|------------|------|
| wr_h_full_name_search | First/Last name (correlated) | 10.12 | 73.86 | 98.8/s | 1.5 |
| wr_f_dob_with_name | DOB + name (correlated) | 11.57 | 91.46 | 86.4/s | 1.9 |
| wr_c_zip_only | ZIP code only | 12.33 | 86.85 | 81.1/s | 20.3 |
| wr_q_tin_last4_with_name | TIN last 4 + name (correlated) | 13.62 | 80.51 | 73.4/s | 1 |
| uc4_ssn_last4_search | SSN last 4 digits | 18.27 | 21.62 | 54.7/s | 98.7 |
| account_by_customer | Accounts for customer | 25.30 | 205.57 | 39.5/s | 2.8 |

### Address Searches (Higher Latency)

| Query | Description | Avg (ms) | P95 (ms) | Throughput | Docs |
|-------|-------------|----------|----------|------------|------|
| uc5_address_search | City/State/ZIP (correlated) | 239.01 | 473.34 | 4.2/s | 5.6 |
| wr_b_address_with_name | State/ZIP (correlated) | 343.53 | 700.42 | 2.9/s | 4.2 |
| baseline_count_all | Count all identity docs | 498.92 | 513.54 | 2.0/s | 1M |

### Aggregation Queries (Full Collection Scans)

| Query | Description | Avg (ms) | P95 (ms) | Throughput | Docs |
|-------|-------------|----------|----------|------------|------|
| agg_count_by_entity_type | Count by entity type | 1814.32 | 1954.82 | 0.6/s | 2 |
| agg_phone_type_distribution | Phone type distribution | 4687.46 | 5627.90 | 0.2/s | 4 |
| agg_account_holder_distribution | Account holder counts | 9854.98 | 10002.43 | 0.1/s | 10 |
| agg_email_count_distribution | Email count distribution | 10084.35 | 11264.00 | 0.1/s | 4 |
| agg_count_by_state | Count by state | 15919.51 | 16465.92 | 0.1/s | 10 |

### Failed Queries

| Query | Description | Issue |
|-------|-------------|-------|
| baseline_pk_lookup | PK lookup by customer number | Cannot extract `_id.customerNumber` from compound _id field |

---

## Correlated Parameters Feature

### Overview

The `correlationGroup` feature allows parameters to be extracted from the **same randomly-selected document**, ensuring filter combinations actually match existing data.

### Configuration Example

```yaml
# WR-F: DOB with name (correlated - same document)
- name: "wr_f_dob_with_name"
  description: "WR-F: Search by DOB with name identifier"
  collection: "identity"
  filter:
    individual.birthDate: "${param:dob}"
    common.fullName: "${param:fullName}"
  parameters:
    dob:
      type: "random_from_loaded"
      collection: "identity"
      field: "individual.birthDate"
      correlationGroup: "identity_dob_name"  # Same group!
    fullName:
      type: "random_from_loaded"
      collection: "identity"
      field: "common.fullName"
      correlationGroup: "identity_dob_name"  # Same group!
```

### Queries Using Correlated Parameters

| Query | Correlation Group | Parameters |
|-------|-------------------|------------|
| wr_f_dob_with_name | identity_dob_name | dob + fullName |
| wr_h_full_name_search | identity_name | firstName + lastName |
| wr_q_tin_last4_with_name | identity_tin | tinLast4 + fullName |
| uc5_address_search | address_location | city + state |
| wr_b_address_with_name | address_state_zip | state + zip |

### Known Limitation

The identity collection contains both individuals (with birthDate, firstName, lastName) and business entities (without these fields). When a business entity document is randomly selected, these individual-specific fields are null, resulting in warning messages:

```
WARN - No value found for field 'individual.birthDate' in correlated document for parameter 'dob'
```

This is expected behavior - the query still executes but returns fewer results.

---

## Index Summary

All 20 indexes were created successfully. The indexes support various query patterns including exact match, range queries, compound filters, and multikey arrays.

### Identity Collection (9 indexes)

| Index Name | Type | Keys | Options | Use Cases |
|------------|------|------|---------|-----------|
| `idx_identity_tin_full` | Single | `common.taxIdentificationNumber: 1` | background | OS-1: Full TIN/SSN search |
| `idx_identity_tin_last4` | Single | `common.taxIdentificationNumberLast4: 1` | background | UC-1, UC-2, UC-4, UC-5, WR-Q: Partial TIN |
| `idx_identity_fullname` | Single | `common.fullName: 1` | background | WR-H: Full name search |
| `idx_identity_name_parts` | Compound | `individual.lastName: 1, individual.firstName: 1` | background | WR-H: Structured name search |
| `idx_identity_entity_type` | Single | `common.entityTypeIndicator: 1` | background | WR-G: Entity type filter |
| `idx_identity_dob` | Single | `individual.birthDate: 1` | background | WR-F: DOB search |
| `idx_identity_id_docs` | Multikey | `common.identifications.identificationNumber: 1` | background | WR-S: DL/Passport lookup |
| `idx_identity_ecn` | Single | `common.ecn: 1` | background, sparse | WR-K: ECN lookup |
| `idx_identity_email` | Multikey | `emails.emailAddress: 1` | background | UC-6, UC-7, WR-E: Email search |

### Address Collection (3 indexes)

| Index Name | Type | Keys | Options | Use Cases |
|------------|------|------|---------|-----------|
| `idx_address_city_state_zip` | Compound | `addresses.stateCode: 1, addresses.cityName: 1, addresses.postalCode: 1` | background | UC-5, WR-B: Location search |
| `idx_address_zip` | Single | `addresses.postalCode: 1` | background | WR-C: ZIP-only search |
| `idx_address_customer` | Compound | `_id.customerNumber: 1, _id.customerCompanyNumber: 1` | - | Customer lookup |

### Phone Collection (3 indexes)

| Index Name | Type | Keys | Options | Use Cases |
|------------|------|------|---------|-----------|
| `idx_phone_number` | Single | `phoneKey.phoneNumber: 1` | background | OS-4, UC-1, UC-2, UC-3, UC-7: Phone search |
| `idx_phone_customer` | Compound | `phoneKey.customerNumber: 1, phoneKey.customerCompanyNumber: 1` | background | Customer lookup/join |
| `idx_phone_type` | Single | `phoneKey.phoneNumberTypeCode: 1` | background | Phone type filter |

### Account Collection (5 indexes)

| Index Name | Type | Keys | Options | Use Cases |
|------------|------|------|---------|-----------|
| `idx_account_last4` | Single | `accountKey.accountNumberLast4: 1` | background | UC-2, UC-3, UC-4, UC-5, UC-6, UC-7: Partial account |
| `idx_account_full` | Single | `accountKey.accountNumber: 1` | background | OS-2: Full account lookup |
| `idx_account_tokenized` | Single | `accountKey.accountNumberTokenized: 1` | background, sparse | OS-3: Tokenized account |
| `idx_account_holders` | Multikey | `accountHolders.customerNumber: 1, accountHolders.customerCompanyNumber: 1` | background | Customer-to-account join |
| `idx_account_product_coid` | Compound | `productTypeCode: 1, companyOfInterestId: 1` | background | OS-2: Product/COID filter |

### Index Type Legend

| Type | Description |
|------|-------------|
| **Single** | Index on a single field for equality and range queries |
| **Compound** | Index on multiple fields in order; supports queries using leading subset of keys |
| **Multikey** | Index on array field; creates entry for each array element |

### Index Options

| Option | Description |
|--------|-------------|
| **background** | Build index without blocking other operations |
| **sparse** | Only index documents that contain the indexed field |

---

## Performance Insights

1. **Indexed Lookups (2-10ms)**: Queries using indexed fields show excellent performance
2. **Correlated Queries (10-16ms)**: Multi-field queries with correlated parameters perform well
3. **Range Queries (15-25ms)**: Queries returning multiple documents (SSN last 4) take longer
4. **Address Search (239-344ms)**: High variance due to data distribution
5. **Full Collection Scans (1-16s)**: Aggregations requiring full scans are significantly slower

---

## Recommendations

1. **For correlated queries**: Filter identity collection to only individuals when querying individual-specific fields
2. **For address searches**: Consider additional indexes or query restructuring
3. **For aggregations**: Implement caching or pre-computed summaries
4. **For baseline_pk_lookup**: Use a different approach to sample _id values that handles compound keys

---

## Hybrid Search Capabilities

The benchmark tool now includes a **Hybrid Search** implementation that combines multiple search strategies to address MongoDB API limitations:

### Search Strategies

| Strategy | Implementation | Status | Description |
|----------|----------------|--------|-------------|
| **Phonetic** | Oracle SOUNDEX | Working | Matches names that sound alike (Smith/Smyth, John/Jon) |
| **Fuzzy** | Oracle Text CONTAINS | Requires Index | Typo-tolerant text search with configurable similarity |
| **Vector** | Oracle AI Vector Search | Requires Setup | Semantic similarity search using embeddings |

### Why Hybrid Search?

The MongoDB API for Oracle supports only B-tree indexes. Advanced search features require SQL/JDBC:

| Feature | MongoDB API | Hybrid (SQL/JDBC) |
|---------|-------------|-------------------|
| Text indexes ($text) | Not supported | Oracle Text CONTAINS with FUZZY |
| Vector indexes ($vectorSearch) | Not supported | Oracle AI Vector Search |
| SOUNDEX phonetic matching | Not supported | Oracle SOUNDEX function |
| Fuzzy/typo-tolerant search | Not supported | Oracle Text fuzzy operators |

### Hybrid Search Performance Results

Integration tests against live Oracle ADB (1M identity documents):

| Search Type | Status | Latency | Throughput | Notes |
|-------------|--------|---------|------------|-------|
| **Phonetic (SOUNDEX)** | Working | 280ms | ~3.6/sec | Full table scan, no index required |
| **Fuzzy (Oracle Text)** | Pending Index | - | - | Requires `CTXSYS.CONTEXT` index |
| **Vector (AI Search)** | Pending Setup | - | - | Requires ONNX model + embedding column |

#### Phonetic Search Example

```
Search completed in 280ms with 1 results
Phonetic search results (1 found):
  HybridSearchResult{customerNumber='1000008534', matchedValue='NORRIS EDMOND ALTENWERTH',
                     score=0.8, matchStrategies=[PHONETIC]}
```

#### Hybrid Search Graceful Degradation

When fuzzy search is unavailable (no Oracle Text index), the hybrid search service:
1. Logs a warning: `Fuzzy search failed, continuing with other strategies`
2. Falls back to phonetic (SOUNDEX) search
3. Returns results from available strategies with combined scoring

### Enabling Fuzzy Text Search

Create an Oracle Text index on the JSON DATA column:

```sql
CREATE INDEX idx_identity_data_text
ON identity(DATA)
INDEXTYPE IS CTXSYS.CONTEXT
PARAMETERS ('SYNC (ON COMMIT)');
```

### Enabling Vector Search

1. Add embedding column:
```sql
ALTER TABLE identity ADD (embedding VECTOR(384, FLOAT32));
```

2. Load ONNX embedding model (e.g., all-MiniLM-L6-v2)

3. Populate embeddings:
```sql
UPDATE identity SET embedding = VECTOR_EMBEDDING(
  all_minilm_l6_v2 USING json_value(DATA, '$.common.fullName') as data
);
```

4. Create vector index:
```sql
CREATE VECTOR INDEX idx_identity_embedding
ON identity(embedding)
ORGANIZATION NEIGHBOR PARTITIONS
WITH DISTANCE COSINE;
```

See `config/hybrid-search-config.yaml` for full configuration and query definitions.

---

## Environment Details

- **Database:** Oracle Autonomous JSON Database
- **Java:** OpenJDK 23.0.1+11 with preview features
- **Driver:** MongoDB Java Driver 5.2.1
- **Connection:** MongoDB API for Oracle (ORDS)
- **Region:** US-Ashburn-1
- **Test Suite:** 130 tests (121 unit + 9 integration)

---

## Test Execution

```bash
java --enable-preview -jar target/wf-bench-1.0.0-SNAPSHOT.jar query \
  --connection-string "$CONN" \
  --config-file config/sample-query-config.yaml \
  --threads 12 \
  --iterations 10 \
  --warmup 3
```
