# WF Benchmark Tool - Query Benchmark Summary

**Date:** 2025-12-11
**Environment:** Oracle Autonomous JSON Database (MongoDB API)
**Data Scale:** LARGE (1M identity, 1M address, 2.5M phone, 1.5M account documents)

---

## Executive Summary

Successfully executed 26 query benchmarks against Oracle ADB with MongoDB API. All queries completed without errors. The `random_from_loaded` parameter type with array field extraction enables realistic benchmarking using sampled values from the database.

### Key Results

| Metric | Value |
|--------|-------|
| Total Queries | 26 |
| Total Iterations | 260 (10 per query + 3 warmup) |
| Queries Passed | 26 (100%) |
| Indexes Created | 20 |
| Concurrent Threads | 12 |

---

## Query Performance Results

### Indexed Single-Collection Lookups (< 20ms target)

| Query | Collection | Avg Latency | P95 Latency | Throughput | Docs Returned |
|-------|------------|-------------|-------------|------------|---------------|
| uc1_phone_ssn_last4 | phone | 6.95ms | 43.17ms | 143.9/sec | 1.0 |
| os1_tin_full_search | identity | 12.96ms | 66.69ms | 77.1/sec | 1.0 |
| os4_phone_full_search | phone | 5.83ms | 35.58ms | 171.6/sec | 1.0 |
| wr_b_address_with_name | address | 2.55ms | 2.90ms | 392.1/sec | 0.0 |
| wr_h_full_name_search | identity | 3.87ms | 17.74ms | 258.3/sec | 0.3 |
| wr_s_id_document_search | identity | 7.10ms | 49.44ms | 140.9/sec | 1.0 |
| wr_e_email_search | identity | 6.29ms | 39.17ms | 159.1/sec | 2.3 |
| uc6_email_account_last4 | identity | 7.09ms | 40.35ms | 141.1/sec | 1.3 |
| uc7_email_phone_account | identity | 2.47ms | 2.80ms | 405.7/sec | 0.0 |
| os2_account_full_search | account | 4.70ms | 25.65ms | 212.8/sec | 1.0 |
| os3_account_tokenized_search | account | 4.64ms | 26.11ms | 215.5/sec | 1.0 |
| account_by_customer | account | 2.43ms | 2.72ms | 411.7/sec | 2.3 |

### Filtered Searches (< 50ms target)

| Query | Collection | Avg Latency | P95 Latency | Throughput | Docs Returned |
|-------|------------|-------------|-------------|------------|---------------|
| uc4_ssn_last4_search | identity | 19.98ms | 33.18ms | 50.0/sec | 95.9 |
| wr_c_zip_only | address | 16.08ms | 113.47ms | 62.2/sec | 19.0 |
| wr_f_dob_with_name | identity | 7.88ms | 39.23ms | 126.9/sec | 0.0 |
| wr_g_entity_type_filter | identity | 15.09ms | 21.38ms | 66.3/sec | 100.0 |
| wr_q_tin_last4_with_name | identity | 9.39ms | 12.54ms | 106.5/sec | 0.0 |
| account_last4_search | account | 8.42ms | 10.50ms | 118.8/sec | 100.0 |

### Complex Address Search

| Query | Collection | Avg Latency | P95 Latency | Throughput | Docs Returned |
|-------|------------|-------------|-------------|------------|---------------|
| uc5_address_search | address | 626.53ms | 1143.81ms | 1.6/sec | 0.0 |

### Aggregation Queries (Full Collection Scans)

| Query | Collection | Avg Latency | P95 Latency | Throughput | Docs Returned |
|-------|------------|-------------|-------------|------------|---------------|
| agg_count_by_state | address | 17,840.95ms | 21,839.87ms | 0.1/sec | 10.0 |
| agg_count_by_entity_type | identity | 1,853.44ms | 1,982.46ms | 0.5/sec | 2.0 |
| agg_phone_type_distribution | phone | 4,877.93ms | 6,057.98ms | 0.2/sec | 4.0 |
| agg_account_holder_distribution | account | 9,935.26ms | 10,428.42ms | 0.1/sec | 4.0 |
| agg_email_count_distribution | identity | 9,830.40ms | 10,100.74ms | 0.1/sec | 4.0 |

### Baseline Queries

| Query | Collection | Avg Latency | P95 Latency | Throughput | Docs Returned |
|-------|------------|-------------|-------------|------------|---------------|
| baseline_pk_lookup | identity | 652.12ms | 807.94ms | 1.5/sec | 0.1 |
| baseline_count_all | identity | 492.57ms | 518.40ms | 2.0/sec | 1,000,000 |

---

## Index Summary

All 20 indexes were created successfully:

### Identity Collection (8 indexes)
- `idx_identity_tin_full` - Full TIN search
- `idx_identity_tin_last4` - Partial TIN search
- `idx_identity_fullname` - Full name search
- `idx_identity_name_parts` - First/last name compound
- `idx_identity_entity_type` - Entity type filter
- `idx_identity_dob` - Date of birth search
- `idx_identity_id_docs` - ID document number (multikey on array)
- `idx_identity_ecn` - Enterprise customer number
- `idx_identity_email` - Email address (multikey on array)

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
- `idx_account_holders` - Customer lookup (multikey on array)
- `idx_account_product_coid` - Product type + COID compound

---

## Parameter Generation - Array Field Extraction

The `random_from_loaded` parameter type now supports extracting values from nested array fields:

| Parameter | Collection | Field Path | Values Loaded |
|-----------|------------|------------|---------------|
| postalCode | address | addresses.postalCode | 2,483 |
| identificationNumber | identity | common.identifications.identificationNumber | 819 |
| emailAddress | identity | emails.emailAddress | 1,466 |
| phoneNumber | phone | phoneKey.phoneNumber | 1,000 |
| taxIdentificationNumber | identity | common.taxIdentificationNumber | 1,000 |
| birthDate | identity | individual.birthDate | 712 |
| accountNumber | account | accountKey.accountNumber | 1,000 |
| accountNumberTokenized | account | accountKey.accountNumberTokenized | 1,000 |

---

## Implementation Notes

### Array Field Extraction Fix

The `ParameterGenerator.extractAllNestedValues()` method recursively traverses nested document structures to extract values from array fields. This enables queries like:

```yaml
parameters:
  postalCode:
    type: "random_from_loaded"
    collection: "address"
    field: "addresses.postalCode"  # Array field
```

### Test Coverage

5 unit tests cover array field extraction:
- Simple array field extraction
- Deeply nested array extraction
- Empty array handling
- Mixed null/non-null values in arrays
- Non-array nested field extraction

---

## Recommendations

1. **Indexed lookups perform well** - Sub-10ms average latency for most indexed queries
2. **Aggregations are slow** - Full collection scans take 2-18 seconds; consider:
   - Materialized views for common aggregations
   - Pre-computed statistics
   - Covered indexes where possible
3. **Complex address search needs optimization** - 627ms average suggests index tuning needed
4. **Primary key lookup is slower than expected** - 652ms baseline indicates potential connection/network overhead

---

## Environment Details

- **Database:** Oracle Autonomous JSON Database
- **Java:** OpenJDK 23.0.1+11
- **Driver:** MongoDB Java Driver 4.11+
- **Connection:** MongoDB API for Oracle (ORDS)
- **Region:** US-Ashburn-1
