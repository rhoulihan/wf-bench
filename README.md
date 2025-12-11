# WF Benchmark Tool

A Java CLI tool for benchmarking the MongoDB API for Oracle Database. Load test data and run query benchmarks with configurable parallelism, batch sizes, and comprehensive metrics collection.

## Features

- **Data Loading** - Generate and load realistic test data with configurable volume and parallelism
- **Query Benchmarking** - Run queries with warmup iterations and detailed latency metrics (p50/p95/p99)
- **Index Management** - Create and manage indexes via YAML configuration
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

## Recent Benchmark Results

**Environment:** Oracle Autonomous JSON Database (MongoDB API), LARGE scale (1M identity, 1M address, 2.5M phone, 1M account)

### High Performance Queries (< 10ms avg)

| Query | Description | Avg | P95 | Throughput |
|-------|-------------|-----|-----|------------|
| os2_account_full_search | Full account number lookup | 4.58ms | 23.89ms | 218.5/sec |
| os3_account_tokenized_search | Tokenized account lookup | 5.29ms | 22.88ms | 188.9/sec |
| os4_phone_full_search | Full phone number lookup | 5.68ms | 34.59ms | 176.2/sec |
| uc7_email_phone_account | Email search (correlated) | 5.69ms | 34.69ms | 175.6/sec |
| uc6_email_account_last4 | Email + account last 4 | 6.02ms | 36.96ms | 166.1/sec |
| account_last4_search | Account last 4 digits | 6.45ms | 6.96ms | 155.0/sec |
| wr_e_email_search | Email address (embedded) | 6.58ms | 35.74ms | 152.0/sec |
| wr_s_id_document_search | Driver's License/Passport | 6.69ms | 44.70ms | 149.6/sec |
| uc1_phone_ssn_last4 | Phone + SSN last 4 | 7.58ms | 44.93ms | 131.9/sec |
| os1_tin_full_search | Full 9-digit TIN/SSN | 9.77ms | 73.98ms | 102.4/sec |

### Medium Performance Queries (10-50ms avg)

| Query | Description | Avg | P95 | Throughput |
|-------|-------------|-----|-----|------------|
| wr_h_full_name_search | First/Last name (correlated) | 10.12ms | 73.86ms | 98.8/sec |
| wr_f_dob_with_name | DOB + name (correlated) | 11.57ms | 91.46ms | 86.4/sec |
| wr_c_zip_only | ZIP code only | 12.33ms | 86.85ms | 81.1/sec |
| wr_q_tin_last4_with_name | TIN last 4 + name | 13.62ms | 80.51ms | 73.4/sec |
| uc4_ssn_last4_search | SSN last 4 digits | 18.27ms | 21.62ms | 54.7/sec |
| account_by_customer | Accounts for customer | 25.30ms | 205.57ms | 39.5/sec |

### Address Searches (Higher Latency)

| Query | Description | Avg | P95 | Throughput |
|-------|-------------|-----|-----|------------|
| uc5_address_search | City/State/ZIP (correlated) | 239.01ms | 473.34ms | 4.2/sec |
| wr_b_address_with_name | State/ZIP (correlated) | 343.53ms | 700.42ms | 2.9/sec |

*See [results/BENCHMARK_SUMMARY.md](results/BENCHMARK_SUMMARY.md) for detailed results including aggregation queries.*

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
    │   └── report/                # Output formatting
    └── test/java/                 # Unit tests
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
