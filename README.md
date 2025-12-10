# WF Benchmark Tool

A Java CLI tool for benchmarking the MongoDB API for Oracle Database. Load test data and run query benchmarks with configurable parallelism, batch sizes, and comprehensive metrics collection.

## Features

- **Data Loading** - Generate and load realistic test data with configurable volume and parallelism
- **Query Benchmarking** - Run queries with warmup iterations and detailed latency metrics (p50/p95/p99)
- **Index Management** - Create and manage indexes via YAML configuration
- **Multiple Output Formats** - Console, CSV, and JSON output for integration with analysis tools
- **Tunable Parameters** - Thread count, batch size, connection pool size, and more

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
```

## Data Model

The tool generates three collections matching a customer information system:

### Identity Collection
- Composite key: `{customerNumber, customerCompanyNumber}`
- Individual customers (70%): name, SSN, birth date, identifications
- Business customers (30%): company name, EIN, NAICS codes, registration info

### Address Collection
- One document per customer with array of addresses
- Address types: CUSTOMER_RESIDENCE, STATEMENT_ADDRESS, BILLING_ADDRESS, MAILING_ADDRESS
- US (85%), Canada (10%), Mexico (5%) distribution

### Phone Collection
- Multiple documents per customer (ratio configurable)
- Phone types: MOBILE, HOME, BUSINESS, FAX
- Includes verification status, provider info, line type

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
│   └── IMPLEMENTATION_PLAN.md
└── src/
    ├── main/java/com/wf/benchmark/
    │   ├── WfBenchmarkCli.java    # Main entry point
    │   ├── command/               # CLI commands
    │   ├── config/                # Configuration classes
    │   ├── generator/             # Data generators
    │   ├── loader/                # Data loading
    │   ├── query/                 # Query execution
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
