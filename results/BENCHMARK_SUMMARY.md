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

All 20 indexes were created successfully:

### Identity Collection (9 indexes)
- `idx_identity_tin_full` - Full TIN search
- `idx_identity_tin_last4` - Partial TIN search
- `idx_identity_fullname` - Full name search
- `idx_identity_name_parts` - First/last name compound
- `idx_identity_entity_type` - Entity type filter
- `idx_identity_dob` - Date of birth search
- `idx_identity_id_docs` - ID document number (multikey)
- `idx_identity_ecn` - Enterprise customer number
- `idx_identity_email` - Email address (multikey)

### Address Collection (3 indexes)
- `idx_address_city_state_zip` - Location compound
- `idx_address_zip` - ZIP code only
- `idx_address_customer` - Customer lookup

### Phone Collection (3 indexes)
- `idx_phone_number` - Full phone number
- `idx_phone_customer` - Customer lookup
- `idx_phone_type` - Phone type filter

### Account Collection (5 indexes)
- `idx_account_last4` - Account number last 4
- `idx_account_full` - Full account number
- `idx_account_tokenized` - Tokenized account number
- `idx_account_holders` - Customer lookup (multikey)
- `idx_account_product_coid` - Product type + COID compound

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

## Environment Details

- **Database:** Oracle Autonomous JSON Database
- **Java:** OpenJDK 23.0.1+11 with preview features
- **Driver:** MongoDB Java Driver 5.2.1
- **Connection:** MongoDB API for Oracle (ORDS)
- **Region:** US-Ashburn-1

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
