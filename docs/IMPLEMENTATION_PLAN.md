# MongoDB API for Oracle Benchmark Tool - Implementation Plan

## Executive Summary

This document outlines the implementation plan for a Java CLI tool designed to benchmark the MongoDB API for Oracle Database. The tool will support data loading with configurable parallelism and comprehensive query benchmarking with index configuration testing.

---

## 1. Sample Data Analysis

### 1.1 Collections Discovered

The `admin` database contains three collections representing a customer information system:

| Collection | Document Count | Primary Key Structure | Avg Document Size (est.) |
|------------|----------------|----------------------|-------------------------|
| `identity` | 3 | Composite: `{customerNumber, customerCompanyNumber}` | ~2-3 KB |
| `address`  | 3 | Composite: `{customerNumber, customerCompanyNumber}` | ~1.5-2 KB |
| `phone`    | 3 | ObjectId (with embedded `phoneKey`) | ~1-1.5 KB |

### 1.2 Schema Analysis

#### Identity Collection
- **Purpose**: Core customer identity information
- **Key Fields**:
  - `_id.customerNumber` (Long) - Primary customer identifier
  - `_id.customerCompanyNumber` (Integer) - Company partition
  - `common.fullName` - Customer/Business name
  - `common.entityTypeIndicator` - "INDIVIDUAL" or "NON_INDIVIDUAL"
  - `common.taxIdentificationNumber` - SSN/EIN/ITIN
  - `individual` - Sub-document for individual customers (birthDate, name parts)
  - `nonIndividual` - Sub-document for business customers (corporation details, NAICS codes)
  - `common.identifications[]` - Array of ID documents (licenses, passports)
  - `metaData` - Audit trail with CDC information

#### Address Collection
- **Purpose**: Customer address information (one-to-many relationship with identity)
- **Key Fields**:
  - `_id.customerNumber` (Long) - Links to identity
  - `_id.customerCompanyNumber` (Integer)
  - `addresses[]` - Array containing multiple addresses per customer
    - `addressUseCode` - CUSTOMER_RESIDENCE, STATEMENT_ADDRESS, BILLING_ADDRESS
    - `addressLines[]` - Street address components
    - `cityName`, `stateCode`, `postalCode`, `countryCode`
    - `systemOfRecord` - Legacy system mapping
    - `metaData` - Per-address audit trail

#### Phone Collection
- **Purpose**: Customer phone contact information
- **Key Fields**:
  - `_id` - ObjectId
  - `phoneKey.customerNumber` (Long) - Links to identity
  - `phoneKey.customerCompanyNumber` (Integer)
  - `phoneKey.phoneNumber` - Actual phone number
  - `phoneKey.phoneNumberTypeCode` - MOBILE, BUSINESS, etc.
  - `lineTypeCode` - WIRELESS, LANDLINE
  - `providerName`, `countryCode`
  - Verification status fields
  - `metaData` - Audit trail

### 1.3 Recommended Data Generation Ratios

Based on real-world customer data patterns:

| Collection | Ratio (per Identity) | Rationale |
|------------|---------------------|-----------|
| Identity   | 1                   | Base entity |
| Address    | 1                   | One document per customer (contains array of addresses) |
| Phone      | 2-3                 | Customers typically have multiple phone numbers |

**Recommended Load Volumes for Benchmarking:**

| Scale | Identity | Address | Phone | Total Documents |
|-------|----------|---------|-------|-----------------|
| Small | 10,000 | 10,000 | 25,000 | 45,000 |
| Medium | 100,000 | 100,000 | 250,000 | 450,000 |
| Large | 1,000,000 | 1,000,000 | 2,500,000 | 4,500,000 |
| XLarge | 10,000,000 | 10,000,000 | 25,000,000 | 45,000,000 |

---

## 2. Tool Architecture

### 2.1 Project Structure

```
wf_bench/
├── pom.xml                           # Maven build configuration
├── docs/
│   └── IMPLEMENTATION_PLAN.md        # This document
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/wf/benchmark/
│   │   │       ├── WfBenchmarkCli.java           # Main entry point (Picocli)
│   │   │       ├── command/
│   │   │       │   ├── LoadCommand.java          # Data loading command
│   │   │       │   ├── QueryCommand.java         # Query benchmarking command
│   │   │       │   └── AnalyzeCommand.java       # Results analysis command
│   │   │       ├── config/
│   │   │       │   ├── ConnectionConfig.java     # MongoDB connection settings
│   │   │       │   ├── LoadConfig.java           # Data loading configuration
│   │   │       │   └── QueryConfig.java          # Query benchmark configuration
│   │   │       ├── generator/
│   │   │       │   ├── DataGenerator.java        # Interface for data generation
│   │   │       │   ├── IdentityGenerator.java    # Identity document generator
│   │   │       │   ├── AddressGenerator.java     # Address document generator
│   │   │       │   ├── PhoneGenerator.java       # Phone document generator
│   │   │       │   └── RandomDataProvider.java   # Faker-based random data
│   │   │       ├── loader/
│   │   │       │   ├── DataLoader.java           # Orchestrates parallel loading
│   │   │       │   ├── BatchWriter.java          # Batch insert logic
│   │   │       │   └── LoadMetrics.java          # Timing and throughput metrics
│   │   │       ├── query/
│   │   │       │   ├── QueryRunner.java          # Executes benchmark queries
│   │   │       │   ├── QueryDefinition.java      # Query configuration model
│   │   │       │   ├── IndexManager.java         # Index creation/management
│   │   │       │   └── QueryMetrics.java         # Query timing metrics
│   │   │       ├── report/
│   │   │       │   ├── ReportGenerator.java      # Generates benchmark reports
│   │   │       │   ├── ConsoleReporter.java      # Console output
│   │   │       │   ├── CsvReporter.java          # CSV export
│   │   │       │   └── JsonReporter.java         # JSON export
│   │   │       └── util/
│   │   │           ├── MongoClientFactory.java   # Connection management
│   │   │           └── ProgressBar.java          # Console progress display
│   │   └── resources/
│   │       ├── logback.xml                       # Logging configuration
│   │       ├── sample-load-config.yaml           # Example load configuration
│   │       └── sample-query-config.yaml          # Example query configuration
│   └── test/
│       └── java/
│           └── com/wf/benchmark/
│               ├── generator/
│               │   ├── IdentityGeneratorTest.java
│               │   ├── AddressGeneratorTest.java
│               │   └── PhoneGeneratorTest.java
│               ├── loader/
│               │   └── DataLoaderTest.java
│               └── query/
│                   └── QueryRunnerTest.java
└── config/
    ├── load-config.yaml                # Production load configuration
    └── query-config.yaml               # Production query configuration
```

### 2.2 Technology Stack

| Component | Technology | Version | Justification |
|-----------|------------|---------|---------------|
| Java | OpenJDK | 21 | Already installed, modern LTS with virtual threads |
| Build Tool | Maven | 3.9+ | Industry standard, dependency management |
| CLI Framework | Picocli | 4.7+ | Powerful CLI parsing with annotations |
| MongoDB Driver | mongodb-driver-sync | 4.11+ | Official driver, sync API for benchmarking |
| Data Generation | Java Faker | 2.0+ | Realistic test data |
| Configuration | SnakeYAML | 2.2+ | Human-readable config files |
| Logging | SLF4J + Logback | 1.4+ | Flexible logging |
| Testing | JUnit 5 + Mockito | 5.10+ | Modern testing framework |
| Metrics | Micrometer (optional) | 1.12+ | Standardized metrics collection |

---

## 3. CLI Interface Design

### 3.1 Main Commands

```bash
# Main entry point
wf-bench [global-options] <command> [command-options]

# Commands
wf-bench load     # Load test data
wf-bench query    # Run query benchmarks
wf-bench analyze  # Analyze previous results
wf-bench clean    # Clean test data from database
```

### 3.2 Global Options

| Option | Short | Type | Default | Description |
|--------|-------|------|---------|-------------|
| `--connection-string` | `-c` | String | (required) | MongoDB connection string |
| `--database` | `-d` | String | `benchmark` | Target database name |
| `--verbose` | `-v` | Boolean | false | Enable verbose logging |
| `--output-dir` | `-o` | String | `./results` | Directory for output files |

### 3.3 Load Command Options

```bash
wf-bench load [options]

# Data Volume Options
--scale              -s    String   medium    Predefined scale (small/medium/large/xlarge)
--identity-count     -i    Long     (scale)   Override identity document count
--address-ratio      -a    Double   1.0       Address documents per identity
--phone-ratio        -p    Double   2.5       Phone documents per identity

# Performance Tuning Options
--threads            -t    Integer  4         Number of writer threads
--batch-size         -b    Integer  1000      Documents per batch insert
--connection-pool    -cp   Integer  10        MongoDB connection pool size

# Behavior Options
--drop-existing      -D    Boolean  false     Drop collections before loading
--collection-prefix  -P    String   ""        Prefix for collection names
--config-file        -f    String   null      YAML configuration file (overrides CLI)
--dry-run                  Boolean  false     Show what would be loaded without loading

# Progress Options
--progress-interval        Integer  5000      Progress update interval (docs)
--quiet              -q    Boolean  false     Suppress progress output
```

### 3.4 Query Command Options

```bash
wf-bench query [options]

# Query Configuration
--config-file        -f    String   (required)  Query configuration YAML file
--query-name         -n    String   (all)       Run specific query by name
--iterations         -i    Integer  10          Iterations per query

# Performance Options
--warmup             -w    Integer  3           Warmup iterations (not measured)
--threads            -t    Integer  1           Concurrent query threads
--connection-pool    -cp   Integer  10          MongoDB connection pool size

# Index Management
--create-indexes           Boolean  true        Create indexes from config
--drop-indexes             Boolean  false       Drop indexes before run

# Output Options
--output-format            String   console     Output format (console/csv/json)
--include-explain          Boolean  false       Include explain plan in output
```

### 3.5 Configuration File Formats

#### Load Configuration (load-config.yaml)

```yaml
connection:
  connectionString: "mongodb://admin:password@host:27017/admin?..."
  database: "benchmark"
  connectionPoolSize: 20

load:
  scale: "medium"              # Overridden by specific counts if provided
  identityCount: 100000
  addressRatio: 1.0
  phoneRatio: 2.5

  threads: 8
  batchSize: 1000
  dropExisting: true
  collectionPrefix: "test_"

  # Advanced tuning
  writeConcern: "majority"     # majority, acknowledged, unacknowledged
  ordered: false               # Ordered batch inserts (slower but safer)

  # Data generation options
  dataGeneration:
    individualRatio: 0.7       # 70% individuals, 30% businesses
    countryDistribution:
      US: 0.85
      CA: 0.10
      MX: 0.05
    addressesPerCustomer:
      min: 1
      max: 4
    phonesPerCustomer:
      min: 1
      max: 5
```

#### Query Configuration (query-config.yaml)

```yaml
connection:
  connectionString: "mongodb://admin:password@host:27017/admin?..."
  database: "benchmark"
  connectionPoolSize: 10

queryExecution:
  iterations: 10
  warmupIterations: 3
  threads: 1
  includeExplainPlan: true

indexes:
  - collection: "identity"
    name: "idx_identity_taxid"
    keys:
      common.taxIdentificationNumber: 1
    options:
      background: true

  - collection: "identity"
    name: "idx_identity_fullname"
    keys:
      common.fullName: 1
    options:
      background: true

  - collection: "address"
    name: "idx_address_state_city"
    keys:
      addresses.stateCode: 1
      addresses.cityName: 1

  - collection: "phone"
    name: "idx_phone_customer"
    keys:
      phoneKey.customerNumber: 1
      phoneKey.customerCompanyNumber: 1

queries:
  - name: "find_by_customer_number"
    description: "Find identity by primary key"
    collection: "identity"
    type: "find"
    filter:
      _id.customerNumber: "${param:customerNumber}"
      _id.customerCompanyNumber: 1
    parameters:
      customerNumber:
        type: "random_range"
        min: 1000000001
        max: 1001000000
    expectedResults: 1

  - name: "find_by_taxid"
    description: "Find customer by tax ID"
    collection: "identity"
    type: "find"
    filter:
      common.taxIdentificationNumber: "${param:taxId}"
    parameters:
      taxId:
        type: "random_from_loaded"  # Uses actual values from loaded data
    expectedResults: 1

  - name: "find_addresses_by_state"
    description: "Find all addresses in a state"
    collection: "address"
    type: "find"
    filter:
      addresses.stateCode: "${param:state}"
    projection:
      _id: 1
      addresses.$: 1
    parameters:
      state:
        type: "random_choice"
        values: ["CA", "TX", "NY", "FL", "IL"]

  - name: "find_phones_for_customer"
    description: "Find all phone numbers for a customer"
    collection: "phone"
    type: "find"
    filter:
      phoneKey.customerNumber: "${param:customerNumber}"
    parameters:
      customerNumber:
        type: "random_range"
        min: 1000000001
        max: 1001000000

  - name: "aggregate_customers_by_state"
    description: "Count customers by state"
    collection: "address"
    type: "aggregate"
    pipeline:
      - $unwind: "$addresses"
      - $group:
          _id: "$addresses.stateCode"
          count: { $sum: 1 }
      - $sort: { count: -1 }
      - $limit: 10

  - name: "find_business_customers"
    description: "Find non-individual customers"
    collection: "identity"
    type: "find"
    filter:
      common.entityTypeIndicator: "NON_INDIVIDUAL"
    limit: 100

  - name: "text_search_name"
    description: "Search customer names (requires text index)"
    collection: "identity"
    type: "find"
    filter:
      $text:
        $search: "${param:searchTerm}"
    parameters:
      searchTerm:
        type: "random_choice"
        values: ["SMITH", "CORPORATION", "JOHNSON", "LLC"]
    requiresIndex: "idx_identity_fullname_text"
```

---

## 4. Implementation Phases

### Phase 1: Project Setup and Core Infrastructure

**Deliverables:**
1. Maven project with all dependencies
2. Picocli CLI structure with main commands
3. MongoDB connection factory with connection pooling
4. Configuration file parsing (YAML)
5. Basic logging infrastructure

**Key Classes:**
- `WfBenchmarkCli.java`
- `MongoClientFactory.java`
- `ConnectionConfig.java`

### Phase 2: Data Generators

**Deliverables:**
1. `DataGenerator` interface
2. `IdentityGenerator` - realistic customer data
3. `AddressGenerator` - US/CA/MX addresses
4. `PhoneGenerator` - phone numbers with metadata
5. `RandomDataProvider` - Faker integration

**Key Implementation Details:**
- Thread-safe generators with sequential ID generation
- Realistic data distributions (names, addresses, phone types)
- Configurable ratios for individuals vs. businesses
- CDC metadata generation matching sample format

### Phase 3: Data Loader

**Deliverables:**
1. `DataLoader` orchestrator
2. `BatchWriter` with configurable batch size
3. Multi-threaded loading with thread pool
4. Progress reporting
5. `LoadMetrics` collection and reporting

**Metrics Captured:**
- Total documents loaded per collection
- Documents per second (throughput)
- Batch insert latencies (min/max/avg/p50/p95/p99)
- Total elapsed time
- Errors encountered

### Phase 4: Query Benchmarking

**Deliverables:**
1. Query configuration parser
2. `IndexManager` for index creation/verification
3. `QueryRunner` with warmup and iteration support
4. Parameter substitution engine
5. `QueryMetrics` collection

**Metrics Captured:**
- Query execution time (min/max/avg/p50/p95/p99)
- Documents examined vs. returned
- Index usage (from explain)
- Throughput (queries per second)

### Phase 5: Reporting and Analysis

**Deliverables:**
1. Console reporter with formatted tables
2. CSV export for spreadsheet analysis
3. JSON export for programmatic processing
4. Comparison between runs
5. Historical result storage

### Phase 6: Testing and Documentation

**Deliverables:**
1. Unit tests for generators
2. Integration tests with embedded MongoDB (or test containers)
3. End-to-end test scenarios
4. User documentation
5. Sample configuration files

---

## 5. Key Design Decisions

### 5.1 Synchronous vs. Asynchronous Driver

**Decision:** Use synchronous MongoDB driver

**Rationale:**
- Benchmarking requires precise timing measurements
- Virtual threads (Java 21) provide good concurrency without async complexity
- Easier to reason about and debug
- More representative of typical application usage patterns

### 5.2 Thread Pool Strategy for Loading

**Decision:** Fixed thread pool with configurable size

**Approach:**
```java
ExecutorService executor = Executors.newFixedThreadPool(threadCount);
// Each thread gets a dedicated MongoClient from pool
// Work is partitioned by document ID ranges
```

**Rationale:**
- Predictable resource usage
- Easy to tune based on server capacity
- Avoids connection pool exhaustion

### 5.3 Batch Insert Strategy

**Decision:** Unordered bulk writes with configurable batch size

**Rationale:**
- Unordered allows parallel server-side processing
- Batch size 1000 is a good starting point (configurable)
- Better throughput than individual inserts

### 5.4 Query Parameter Generation

**Decision:** Support multiple parameter generation strategies

**Strategies:**
1. `random_range` - Random value within min/max
2. `random_choice` - Random selection from list
3. `random_from_loaded` - Sample from actual loaded data
4. `sequential` - Iterate through range
5. `fixed` - Constant value

### 5.5 Metrics Collection

**Decision:** Custom metrics collection with percentile support

**Approach:**
- Use `LongAdder` for thread-safe counters
- HdrHistogram for latency percentiles
- Avoid external metrics systems for portability

---

## 6. Sample Output Formats

### 6.1 Load Command Output

```
================================================================================
                    MongoDB Benchmark Tool - Data Load Results
================================================================================

Connection: MQSSYOWMQVGAC1Y-WELLSFARGO.adb.us-ashburn-1.oraclecloudapps.com:27017
Database:   benchmark
Started:    2024-12-10T15:30:00Z
Completed:  2024-12-10T15:45:32Z

Configuration:
  Threads:    8
  Batch Size: 1000
  Write Concern: majority

--------------------------------------------------------------------------------
Collection      Documents    Throughput     Avg Latency    P95 Latency    Errors
--------------------------------------------------------------------------------
identity          100,000      6,451/sec         12.3 ms       28.5 ms         0
address           100,000      7,234/sec         11.0 ms       24.2 ms         0
phone             250,000      8,102/sec          9.8 ms       21.8 ms         0
--------------------------------------------------------------------------------
TOTAL             450,000      7,262/sec         11.0 ms       24.8 ms         0

Total Time: 15 minutes, 32 seconds
```

### 6.2 Query Command Output

```
================================================================================
                    MongoDB Benchmark Tool - Query Results
================================================================================

Configuration:
  Iterations: 10 (+ 3 warmup)
  Threads:    1
  Database:   benchmark

--------------------------------------------------------------------------------
Query: find_by_customer_number
Description: Find identity by primary key
Collection: identity
Index Used: _id_

  Iterations:     10
  Avg Latency:    1.23 ms
  Min Latency:    0.89 ms
  Max Latency:    2.45 ms
  P50 Latency:    1.15 ms
  P95 Latency:    2.12 ms
  P99 Latency:    2.45 ms
  Throughput:     813 ops/sec
  Docs Examined:  1
  Docs Returned:  1

--------------------------------------------------------------------------------
Query: find_addresses_by_state
Description: Find all addresses in a state
Collection: address
Index Used: idx_address_state_city

  Iterations:     10
  Avg Latency:    45.67 ms
  Min Latency:    32.12 ms
  Max Latency:    78.90 ms
  P50 Latency:    43.21 ms
  P95 Latency:    72.34 ms
  P99 Latency:    78.90 ms
  Throughput:     22 ops/sec
  Docs Examined:  15,234
  Docs Returned:  15,234

================================================================================
                              Summary
================================================================================
Total Queries:     6
Total Iterations:  60
Total Time:        12.34 seconds
Avg Throughput:    4.86 queries/sec
```

---

## 7. Test Strategy

### 7.1 Unit Tests

| Component | Test Focus |
|-----------|------------|
| IdentityGenerator | Document structure, field validity, ID uniqueness |
| AddressGenerator | Address array structure, valid state/country codes |
| PhoneGenerator | Phone key structure, valid phone formats |
| LoadConfig | YAML parsing, default values, validation |
| QueryConfig | Query parsing, parameter substitution |

### 7.2 Integration Tests

| Test Scenario | Description |
|---------------|-------------|
| Connection Test | Verify connection to Oracle MongoDB API |
| Single Insert | Insert and retrieve single document |
| Batch Insert | Verify batch insert correctness |
| Index Creation | Create and verify index existence |
| Query Execution | Run queries and verify results |

### 7.3 Performance Tests

| Test Scenario | Description |
|---------------|-------------|
| Throughput Test | Maximum documents/second at various thread counts |
| Latency Test | Query latency under various loads |
| Scaling Test | Performance at different data volumes |

---

## 8. Risk Assessment and Mitigations

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| Oracle MongoDB API compatibility | High | Medium | Test all operations against Oracle early |
| Connection pool exhaustion | Medium | Low | Proper pool sizing, connection validation |
| Data generation bottleneck | Medium | Low | Pre-generate data batches, efficient random |
| Memory pressure with large loads | High | Medium | Stream-based generation, limit in-flight batches |
| Network latency variability | Medium | High | Multiple iterations, percentile reporting |

---

## 9. Dependencies (pom.xml)

```xml
<dependencies>
    <!-- MongoDB Driver -->
    <dependency>
        <groupId>org.mongodb</groupId>
        <artifactId>mongodb-driver-sync</artifactId>
        <version>4.11.1</version>
    </dependency>

    <!-- CLI Framework -->
    <dependency>
        <groupId>info.picocli</groupId>
        <artifactId>picocli</artifactId>
        <version>4.7.5</version>
    </dependency>

    <!-- Configuration -->
    <dependency>
        <groupId>org.yaml</groupId>
        <artifactId>snakeyaml</artifactId>
        <version>2.2</version>
    </dependency>

    <!-- Data Generation -->
    <dependency>
        <groupId>net.datafaker</groupId>
        <artifactId>datafaker</artifactId>
        <version>2.0.2</version>
    </dependency>

    <!-- Logging -->
    <dependency>
        <groupId>ch.qos.logback</groupId>
        <artifactId>logback-classic</artifactId>
        <version>1.4.14</version>
    </dependency>

    <!-- Metrics -->
    <dependency>
        <groupId>org.hdrhistogram</groupId>
        <artifactId>HdrHistogram</artifactId>
        <version>2.1.12</version>
    </dependency>

    <!-- Testing -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.1</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.mockito</groupId>
        <artifactId>mockito-core</artifactId>
        <version>5.8.0</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <version>3.24.2</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## 10. Success Criteria

1. **Data Loading:**
   - Successfully load 1M+ documents without errors
   - Achieve >5,000 documents/second throughput
   - Accurate timing measurements with <5% variance

2. **Query Benchmarking:**
   - Execute all configured queries successfully
   - Report accurate latency percentiles
   - Capture explain plan data for analysis

3. **Usability:**
   - Clear CLI help and error messages
   - Sensible defaults requiring minimal configuration
   - Comprehensive progress reporting

4. **Quality:**
   - >80% unit test coverage on core components
   - All integration tests pass against Oracle MongoDB API
   - No memory leaks during extended runs

---

## 11. Next Steps

Upon approval of this plan:

1. Initialize Maven project structure
2. Implement Phase 1 (Core Infrastructure)
3. Validate Oracle MongoDB API connectivity
4. Proceed with subsequent phases

---

## Appendix A: Sample Data Documents (Analyzed)

### Identity Document Structure
```json
{
  "_id": {
    "customerNumber": 1000000001,
    "customerCompanyNumber": 1
  },
  "common": {
    "fullName": "JOHN MICHAEL SMITH",
    "entityTypeIndicator": "INDIVIDUAL",
    "taxIdentificationTypeCode": "SSN",
    "taxIdentificationNumber": "123456789",
    "identifications": [...],
    "systemOfRecord": {...}
  },
  "individual": {
    "birthDate": "19850315",
    "firstName": "JOHN",
    "lastName": "SMITH",
    "nationalityCountryCode": "US",
    ...
  },
  "metaData": {
    "createdByProcessName": "24201 - IL",
    "createdTimestamp": "2023-01-01T10:00:00Z",
    ...
  }
}
```

### Address Document Structure
```json
{
  "_id": {
    "customerNumber": 1000000001,
    "customerCompanyNumber": 1
  },
  "addresses": [
    {
      "addressUseCode": "CUSTOMER_RESIDENCE",
      "addressLines": ["456 Elm Street", "Apt 12B"],
      "cityName": "San Francisco",
      "stateCode": "CA",
      "postalCode": "94102",
      "countryCode": "US",
      "systemOfRecord": {...},
      "metaData": {...}
    }
  ]
}
```

### Phone Document Structure
```json
{
  "_id": ObjectId,
  "phoneKey": {
    "customerNumber": 1000000001,
    "customerCompanyNumber": 1,
    "phoneNumber": "4155551234",
    "phoneNumberTypeCode": "MOBILE",
    "systemOfRecord": {...}
  },
  "countryCode": "US",
  "lineTypeCode": "WIRELESS",
  "providerName": "Verizon Wireless",
  "customerVerificationStatusCode": "TWO_FACTOR_AUTH",
  "vendorVerificationStatusCode": "VERIFIED",
  "metaData": {...}
}
```

---

*Document Version: 1.0*
*Created: 2024-12-10*
*Author: Claude AI Assistant*
