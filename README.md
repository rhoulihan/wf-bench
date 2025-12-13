# WF Benchmark Tool

A Java CLI tool for benchmarking the MongoDB API for Oracle Database. Load test data and run query benchmarks with configurable parallelism, batch sizes, and comprehensive metrics collection.

## Features

- **Data Loading** - Generate and load realistic test data with configurable volume and parallelism
- **Query Benchmarking** - Run queries with warmup iterations and detailed latency metrics (p50/p95/p99)
- **Index Management** - Create and manage indexes via YAML configuration
- **Hybrid Search** - Advanced search capabilities via SQL/JDBC:
  - **Phonetic Search** - SOUNDEX matching for names that sound alike
  - **Fuzzy Search** - Oracle Text for typo-tolerant matching
  - **Vector Search** - Semantic similarity using Oracle AI Vector Search
  - **UC SQL JOIN Queries** - Multi-collection joins via native SQL with json_value()
  - **UC Unified Search** - DBMS_SEARCH.FIND with fuzzy OR queries across unified index
- **Multiple Output Formats** - Console, CSV, and JSON output for integration with analysis tools
- **Tunable Parameters** - Thread count, batch size, connection pool size, and more
- **Parameterized Queries** - Support for multiple parameter generation strategies:
  - `random_range` - Random value within min/max range
  - `random_choice` - Random selection from predefined list
  - `random_pattern` - Generate strings matching regex-like patterns (e.g., `\d{4}` for 4 digits)
  - `random_from_loaded` - Sample actual values from database collections (supports nested array fields)
  - `sequential` - Iterate through a range
  - `fixed` - Constant value
- **Correlated Parameters** - Extract multiple parameter values from the same document using `correlationGroup`, ensuring realistic query patterns (e.g., DOB + name from same person)
- **Multi-Collection Joins** - Declarative YAML syntax for queries spanning multiple collections with chained joins (e.g., phone → identity → account)
- **$lookup Aggregation** - Native MongoDB `$lookup` aggregation for multi-collection queries with key attribute matching and chained lookups

## Requirements

- Java 23+ (Oracle JDK recommended)
- Maven 3.9+
- Access to Oracle Database with MongoDB API enabled

## Quick Start

### Build

```bash
mvn clean package
```

### Run

```bash
# Show help
./wf-bench.sh --help

# Load test data (dry run)
./wf-bench.sh load \
  -c "mongodb://user:pass@host:27017/db?authMechanism=PLAIN&authSource=\$external&ssl=true&retryWrites=false&loadBalanced=true" \
  -d admin \
  -s SMALL \
  --dry-run

# Load test data
./wf-bench.sh load \
  -c "mongodb://user:pass@host:27017/db?..." \
  -d admin \
  -s SMALL \
  -t 4 \
  -b 500 \
  --drop-existing

# Run query benchmarks
./wf-bench.sh query \
  -c "mongodb://user:pass@host:27017/db?..." \
  -f config/sample-query-config.yaml \
  --create-indexes
```

## Commands

### `load` - Load Test Data

Generate and load customer identity, address, and phone data.

| Option | Description | Default |
|--------|-------------|---------|
| `-c, --connection-string` | MongoDB connection string | (required) |
| `-d, --database` | Target database | `benchmark` |
| `-s, --scale` | Data scale: SMALL, MEDIUM, LARGE, XLARGE | `MEDIUM` |
| `-t, --threads` | Writer threads | `4` |
| `-b, --batch-size` | Documents per batch | `1000` |
| `-D, --drop-existing` | Drop collections first | `false` |
| `-P, --collection-prefix` | Collection name prefix | `""` |
| `--dry-run` | Show plan without loading | `false` |

**Scale Presets:**

| Scale | Identity | Address | Phone | Total |
|-------|----------|---------|-------|-------|
| SMALL | 10,000 | 10,000 | 25,000 | 45,000 |
| MEDIUM | 100,000 | 100,000 | 250,000 | 450,000 |
| LARGE | 1,000,000 | 1,000,000 | 2,500,000 | 4,500,000 |
| XLARGE | 10,000,000 | 10,000,000 | 25,000,000 | 45,000,000 |

### `query` - Run Query Benchmarks

Execute queries defined in a YAML configuration file.

| Option | Description | Default |
|--------|-------------|---------|
| `-c, --connection-string` | MongoDB connection string | (from config) |
| `-d, --database` | Target database | `benchmark` |
| `-f, --config-file` | Query configuration YAML | (required) |
| `-i, --iterations` | Iterations per query | `10` |
| `-w, --warmup` | Warmup iterations | `3` |
| `--create-indexes` | Create indexes from config | `true` |
| `--output-format` | Output: console, csv, json | `console` |

### `clean` - Clean Test Data

Remove benchmark collections from the database.

```bash
./wf-bench.sh clean \
  -c "mongodb://..." \
  -d admin \
  -P bench_ \
  -y
```

## Configuration Files

### Load Configuration (`config/sample-load-config.yaml`)

```yaml
connection:
  connectionString: "mongodb://..."
  database: "admin"
  connectionPoolSize: 20

load:
  scale: "MEDIUM"
  threads: 8
  batchSize: 1000
  dropExisting: true
  collectionPrefix: "bench_"

  dataGeneration:
    individualRatio: 0.7  # 70% individuals, 30% businesses
    addressesPerCustomer:
      min: 1
      max: 4
    phonesPerCustomer:
      min: 1
      max: 5
```

### Query Configuration (`config/sample-query-config.yaml`)

```yaml
connection:
  connectionString: "mongodb://..."
  database: "admin"

queryExecution:
  iterations: 10
  warmupIterations: 3
  includeExplainPlan: true

indexes:
  - collection: "bench_identity"
    name: "idx_identity_taxid"
    keys:
      common.taxIdentificationNumber: 1

queries:
  - name: "find_by_customer_number"
    description: "Find identity by primary key"
    collection: "bench_identity"
    type: "find"
    filter:
      _id.customerNumber: "${param:customerNumber}"
    parameters:
      customerNumber:
        type: "random_range"
        min: 1000000001
        max: 1000100000

  # Example using random_pattern for SSN last 4
  - name: "find_by_ssn_last4"
    description: "Search by SSN last 4 digits"
    collection: "bench_identity"
    type: "find"
    filter:
      common.taxIdentificationNumberLast4: "${param:ssnLast4}"
    parameters:
      ssnLast4:
        type: "random_pattern"
        pattern: "\\d{4}"  # Generates 4 random digits

  # Example using random_from_loaded (samples from actual data)
  - name: "find_by_zip"
    description: "Search by ZIP code"
    collection: "bench_address"
    type: "find"
    filter:
      addresses.postalCode: "${param:zip}"
    parameters:
      zip:
        type: "random_from_loaded"
        collection: "address"
        field: "addresses.postalCode"  # Supports nested array fields

  # Example using correlated parameters (values from same document)
  - name: "find_by_dob_and_name"
    description: "Search by DOB with name (correlated)"
    collection: "bench_identity"
    type: "find"
    filter:
      individual.birthDate: "${param:dob}"
      common.fullName: "${param:fullName}"
    parameters:
      dob:
        type: "random_from_loaded"
        collection: "identity"
        field: "individual.birthDate"
        correlationGroup: "identity_dob_name"  # Same group
      fullName:
        type: "random_from_loaded"
        collection: "identity"
        field: "common.fullName"
        correlationGroup: "identity_dob_name"  # Same group = same document
```

## Data Model

The tool generates four collections matching a customer information system:

### Identity Collection
- Composite key: `{customerNumber, customerCompanyNumber}`
- Individual customers (70%): name, SSN, birth date, identifications
- Business customers (30%): company name, EIN, NAICS codes, registration info
- Embedded emails array (1-3 per customer)

### Address Collection
- One document per customer with array of addresses
- Address types: CUSTOMER_RESIDENCE, STATEMENT_ADDRESS, BILLING_ADDRESS, MAILING_ADDRESS
- US (85%), Canada (10%), Mexico (5%) distribution

### Phone Collection
- Multiple documents per customer (ratio configurable)
- Phone types: MOBILE, HOME, BUSINESS, FAX
- Includes verification status, provider info, line type

### Account Collection
- Links accounts to customer identities via `accountHolders` array
- Supports multiple holders per account (joint accounts, authorized users)
- Full and last-4 account number fields for different search patterns
- Tokenized account number for security scenarios

## $lookup Aggregation

The MongoDB API for Oracle Database supports `$lookup` aggregation with key attributes for multi-collection queries. This enables efficient server-side joins without requiring multiple round-trips.

### $lookup Configuration in YAML

```yaml
queries:
  # UC-1: Phone + SSN Last 4 via $lookup
  - name: "uc1_phone_ssn_lookup"
    description: "UC-1: Phone + SSN via $lookup aggregation"
    collection: "phone"
    type: "aggregate"
    filter:
      phoneKey.phoneNumber: "${param:phoneNumber}"
    lookup:
      from: "identity"
      localField: "phoneKey.customerNumber"
      foreignField: "_id.customerNumber"
      as: "identityDocs"
      matchFilter:
        identityDocs.common.taxIdentificationNumberLast4: "${param:ssnLast4}"
    parameters:
      phoneNumber:
        type: "random_from_loaded"
        collection: "phone"
        field: "phoneKey.phoneNumber"
      ssnLast4:
        type: "random_pattern"
        pattern: "\\d{4}"

  # UC-2: Phone + SSN + Account via chained $lookup
  - name: "uc2_phone_ssn_account_lookup"
    description: "UC-2: Phone + SSN + Account via chained $lookup"
    collection: "phone"
    type: "aggregate"
    filter:
      phoneKey.phoneNumber: "${param:phoneNumber}"
    lookup:
      from: "identity"
      localField: "phoneKey.customerNumber"
      foreignField: "_id.customerNumber"
      as: "identityDocs"
      matchFilter:
        identityDocs.common.taxIdentificationNumberLast4: "${param:ssnLast4}"
      lookup:  # Chained lookup
        from: "account"
        localField: "identityDocs._id.customerNumber"
        foreignField: "accountHolders.customerNumber"
        as: "accountDocs"
        matchFilter:
          accountDocs.accountKey.accountNumberLast4: "${param:accountLast4}"
```

### Lookup Configuration Options

| Option | Description | Required |
|--------|-------------|----------|
| `from` | Target collection to join | Yes |
| `localField` | Field in source documents | Yes |
| `foreignField` | Field in target collection | Yes |
| `as` | Output array field name | Yes |
| `matchFilter` | Post-lookup filter criteria | No |
| `lookup` | Nested lookup for chained joins | No |

### Generated Pipeline

The tool automatically generates the aggregation pipeline:

```javascript
[
  { $match: { "phoneKey.phoneNumber": "5551234567" } },
  { $lookup: {
      from: "identity",
      localField: "phoneKey.customerNumber",
      foreignField: "_id.customerNumber",
      as: "identityDocs"
  }},
  { $unwind: { path: "$identityDocs", preserveNullAndEmptyArrays: false } },
  { $match: { "identityDocs.common.taxIdentificationNumberLast4": "1234" } }
]
```

## Hybrid Search

The MongoDB API for Oracle supports only B-tree indexes. For advanced search features, the tool provides hybrid search services that use SQL/JDBC:

### Why Hybrid Search?

| Feature | MongoDB API | Hybrid (SQL/JDBC) |
|---------|-------------|-------------------|
| Text indexes ($text) | Not supported | Oracle Text CONTAINS with FUZZY |
| Vector indexes ($vectorSearch) | Not supported | Oracle AI Vector Search |
| SOUNDEX phonetic matching | Not supported | Oracle SOUNDEX function |

### Search Strategies

| Strategy | Description | Status |
|----------|-------------|--------|
| **Phonetic** | SOUNDEX matching for similar-sounding names (Smith/Smyth, John/Jon) | Works immediately |
| **Fuzzy** | Typo-tolerant matching using Oracle Text | Requires Oracle Text index |
| **Vector** | Semantic similarity search | Requires ONNX model + embedding column |
| **UC SQL JOIN** | Multi-collection JOIN queries via native SQL | Works immediately with JDBC |

### UC SQL JOIN Queries

The `hybrid-search` command includes SQL JOIN implementations for UC (Use Case) queries that span multiple collections. This approach uses Oracle's native SQL JOIN with `json_value()` functions instead of sequential MongoDB find() operations.

```bash
# Run UC SQL JOIN benchmark
java --enable-preview -jar wf-bench-1.0.0-SNAPSHOT.jar hybrid-search \
  -j "$JDBC_URL" -u ADMIN -p "$PASSWORD" \
  --collection-prefix "bench_" \
  --uc-benchmark -i 100 -w 10

# Run individual UC queries
java --enable-preview -jar wf-bench-1.0.0-SNAPSHOT.jar hybrid-search \
  -j "$JDBC_URL" -u ADMIN -p "$PASSWORD" \
  --uc1-phone "5551234567" --uc1-ssn-last4 "6789"
```

Supported UC queries: UC-1, UC-2, UC-4, UC-6 (see `results/BENCHMARK_SUMMARY.md` for SQL definitions).

### UC Unified Search (DBMS_SEARCH.FIND)

The `hybrid-search` command also supports UC 1-7 queries using Oracle DBMS_SEARCH (Ubiquitous Database Search) with a unified index across all collections. This approach uses:

1. **Fuzzy OR Query** - Search all terms with fuzzy matching: `fuzzy(term1) OR fuzzy(term2) OR ...`
2. **Group by Customer** - Aggregate search hits by customerNumber
3. **Category Filtering** - Only include customers matching ALL required categories for the UC case
4. **Score Ranking** - Sort by average relevance score

```bash
# Create unified DBMS_SEARCH index
java --enable-preview -jar wf-bench-1.0.0-SNAPSHOT.jar hybrid-search \
  -j "$JDBC_URL" -u ADMIN -p "$PASSWORD" \
  --collection-prefix "bench_" \
  --create-uc-search-indexes

# Run UC Unified Search benchmark
java --enable-preview -jar wf-bench-1.0.0-SNAPSHOT.jar hybrid-search \
  -j "$JDBC_URL" -u ADMIN -p "$PASSWORD" \
  --collection-prefix "bench_" \
  --uc-search-benchmark -i 10 -w 3
```

**Algorithm:**
```
1. Execute: DBMS_SEARCH.FIND('idx_uc_unified', 'fuzzy(phone) OR fuzzy(ssn_last4)')
2. Parse JSON response into SearchHit records (source_table, customerNumber, score)
3. Group hits by customerNumber → CustomerHitGroup
4. Map matched fields to SearchCategory (PHONE, SSN_LAST4, ACCOUNT_LAST4, etc.)
5. Filter groups having ALL required categories for UC case
6. Sort by average score, limit results
7. Fetch customer details from identity/address collections
```

### Running Integration Tests

```bash
# Run all tests including hybrid search integration tests
mvn test -Doracle.jdbc.url=enabled

# With explicit credentials
mvn test -Doracle.jdbc.url="jdbc:oracle:thin:@..." \
         -Doracle.username="admin" \
         -Doracle.password="..."
```

See `config/hybrid-search-config.yaml` for configuration and `results/BENCHMARK_SUMMARY.md` for setup instructions.

## Recent Benchmark Results

**Environment:** Oracle Autonomous JSON Database (MongoDB API), LARGE scale (1M identity, 1M address, 2.5M phone, 1M account)
**Test Configuration:** 12 threads, 100 iterations + 10 warmup per query

### High Performance Queries (< 10ms avg)

| Query | Description | Avg | P95 | Throughput |
|-------|-------------|-----|-----|------------|
| uc4_account_ssn | Account + SSN (2-way join) | 2.33ms | 2.27ms | 428.5/sec |
| os2_account_full_search | Full account number lookup | 2.25ms | 2.15ms | 445.0/sec |
| os3_account_tokenized_search | Tokenized account lookup | 2.26ms | 2.15ms | 443.4/sec |
| os4_phone_full_search | Full phone number lookup | 2.52ms | 2.62ms | 396.2/sec |
| wr_e_email_search | Email address (embedded) | 2.71ms | 3.01ms | 368.5/sec |
| os1_tin_full_search | Full 9-digit TIN/SSN | 2.77ms | 2.61ms | 361.3/sec |
| uc7_email_phone_account | Email + Phone + Account (3-way) | 5.75ms | 9.37ms | 174.0/sec |
| uc2_phone_ssn_account | Phone + SSN + Account (3-way) | 6.91ms | 7.73ms | 144.6/sec |
| uc1_phone_ssn_last4 | Phone + SSN last 4 (2-way) | 7.87ms | 8.85ms | 127.0/sec |

### Multi-Collection Join Queries (UC-1 through UC-7) - SCORE() Approach

Using Oracle Text SCORE() with full JSON search indexes:

| Query | Description | Join Chain | Avg | P50 | P95 | Throughput | Docs |
|-------|-------------|------------|-----|-----|-----|------------|------|
| UC-1 | Phone + SSN Last 4 | phone → identity | 6.86ms | 6.31ms | 8.39ms | 145.7/s | 1.0 |
| UC-2 | Phone + SSN + Account | phone → identity → account | 7.87ms | 7.43ms | 9.83ms | 127.0/s | 1.0 |
| UC-3 | Phone + Account Last 4 | phone → identity → account | 5.51ms | 5.48ms | 5.77ms | 181.6/s | 1.0 |
| UC-4 | Account + SSN | account → identity | 5.43ms | 5.23ms | 6.68ms | 184.0/s | 1.0 |
| UC-5 | City/State/ZIP + SSN + Account | address → identity → account | 9.88ms | 9.38ms | 11.68ms | 101.2/s | 1.0 |
| UC-6 | Email + Account Last 4 | identity → account | 5.21ms | 5.18ms | 5.52ms | 191.8/s | 1.0 |
| UC-7 | Email + Phone + Account | identity → phone → account | 6.58ms | 6.52ms | 7.40ms | 152.1/s | 1.0 |

**Note:** See [UC_UNIFIED_SUMMARY.md](UC_UNIFIED_SUMMARY.md) for detailed query patterns and SQL examples.

### Address Queries (Higher Latency)

| Query | Description | Avg | P95 | Throughput |
|-------|-------------|-----|-----|------------|
| wr_b_address_with_name | State/ZIP (correlated) | 222.88ms | 594.43ms | 4.5/sec |

*See [results/BENCHMARK_SUMMARY.md](results/BENCHMARK_SUMMARY.md) for detailed results including aggregation queries and hybrid search.*

## Sample Output

### Load Results

```
================================================================================
                           Results Summary
================================================================================

Collection        Documents   Throughput  Avg Latency  P95 Latency   Errors
--------------------------------------------------------------------------------
bench_identity       10,000      1,220/s    1478.52 ms    2428.93 ms        0
bench_address        10,000        618/s    2963.25 ms    4943.87 ms        0
bench_phone          25,000      1,296/s    1316.75 ms    3018.75 ms        0
--------------------------------------------------------------------------------
TOTAL                45,000      2,333/s                                    0

Total Time: 19.3 seconds
================================================================================
```

### Query Results

```
--------------------------------------------------------------------------------
Query: find_by_customer_number
Description: Find identity by primary key
Collection: bench_identity
Index Used: _id_

  Iterations:      10
  Avg Latency:     1.23 ms
  Min Latency:     0.89 ms
  Max Latency:     2.45 ms
  P50 Latency:     1.15 ms
  P95 Latency:     2.12 ms
  P99 Latency:     2.45 ms
  Throughput:      813 ops/sec
--------------------------------------------------------------------------------
```

## Project Structure

```
wf_bench/
├── pom.xml
├── wf-bench.sh                    # Launcher script
├── config/
│   ├── sample-load-config.yaml
│   └── sample-query-config.yaml
├── docs/
│   ├── IMPLEMENTATION_PLAN.md
│   └── QUERY_IMPLEMENTATION_PLAN.md
├── results/                       # Benchmark results
│   └── BENCHMARK_SUMMARY_*.md
└── src/
    ├── main/java/com/wf/benchmark/
    │   ├── WfBenchmarkCli.java    # Main entry point
    │   ├── command/               # CLI commands
    │   ├── config/                # Configuration classes
    │   ├── generator/             # Data generators
    │   ├── loader/                # Data loading
    │   ├── query/                 # Query execution
    │   │   └── ParameterGenerator.java  # Parameter value generation
    │   ├── search/                # Hybrid search services
    │   │   ├── FuzzySearchService.java    # Oracle Text fuzzy search
    │   │   ├── PhoneticSearchService.java # SOUNDEX phonetic matching
    │   │   ├── VectorSearchService.java   # Oracle AI Vector Search
    │   │   ├── HybridSearchService.java   # Combined search strategies
    │   │   ├── SqlJoinSearchService.java  # UC SQL JOIN queries
    │   │   ├── UcSearchService.java       # UC Unified Search (DBMS_SEARCH)
    │   │   ├── UcSearchResult.java        # UC search result model
    │   │   ├── SearchCategory.java        # UC category definitions
    │   │   ├── SearchHit.java             # DBMS_SEARCH hit record
    │   │   ├── CustomerHitGroup.java      # Customer hit aggregation
    │   │   └── SampleDataLoader.java      # UC parameter loading
    │   └── report/                # Output formatting
    └── test/java/                 # Unit tests (278 tests)
```

## Development

```bash
# Compile
mvn compile

# Run tests
mvn test

# Package
mvn package

# Run directly with Maven
mvn exec:java -Dexec.mainClass="com.wf.benchmark.WfBenchmarkCli" -Dexec.args="--help"
```

## License

MIT License

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run tests: `mvn test`
5. Submit a pull request
