# CLAUDE.md - Project Context for Claude Code

## Project Overview

WF Benchmark Tool - A CLI tool for benchmarking MongoDB API for Oracle Database (Autonomous JSON Database).

## Build Requirements

- **Java 23** with preview features enabled
- **Maven 3.9+** (older versions fail due to HTTP→HTTPS requirement for Maven Central)

## Build Commands

```bash
# Build the project (skip tests for faster builds)
mvn clean package -DskipTests

# Run the tool
java --enable-preview -jar target/wf-bench-1.0.0-SNAPSHOT.jar [command]

# Or use the wrapper script
./wf-bench.sh [command]
```

## Remote Test Server

### Connection Details
- **Host:** 129.213.29.234 (Oracle Cloud - Ashburn)
- **User:** opc
- **SSH Key:** ~/.ssh/phoenix
- **SSH Command:** `ssh -i ~/.ssh/phoenix opc@129.213.29.234`

### Remote Environment Setup
The remote server has been configured with:
- **JDK 23:** `/home/opc/jdk-23.0.1+11` (Temurin/Adoptium)
- **Maven 3.9.6:** `/home/opc/apache-maven-3.9.6`
- **Project Location:** `~/rick/wf-bench`

To run commands on the remote server with proper Java/Maven:
```bash
ssh -i ~/.ssh/phoenix opc@129.213.29.234 "export JAVA_HOME=/home/opc/jdk-23.0.1+11 && export PATH=\$JAVA_HOME/bin:/home/opc/apache-maven-3.9.6/bin:\$PATH && cd ~/rick/wf-bench && [command]"
```

## MongoDB Connection String

The Oracle MongoDB API connection string has a specific format:
```
mongodb://admin:PASSWORD@MQSSYOWMQVGAC1Y-WELLSFARGO.adb.us-ashburn-1.oraclecloudapps.com:27017/[user]?authMechanism=PLAIN&authSource=$external&ssl=true&retryWrites=false&loadBalanced=true
```

**Important notes:**
- The database path MUST be `/[user]` (with literal brackets) - NOT `/admin` or other database names
- `authSource=$external` is required for Oracle ADB authentication
- `loadBalanced=true` is required for Oracle ADB

### Shell Escaping Issues

When passing the connection string via SSH, the `!` and `$` characters cause shell escaping issues. Use base64 encoding to transfer safely:

```bash
# Encode locally
printf '%s' 'mongodb://admin:OracleJson1!@...' | base64

# Decode on remote and save to file
ssh -i ~/.ssh/phoenix opc@129.213.29.234 "echo 'BASE64_STRING' | base64 -d > ~/connstr.txt"

# Use in command
ssh -i ~/.ssh/phoenix opc@129.213.29.234 'CONN=$(cat ~/connstr.txt) && ./wf-bench.sh load -c "$CONN" ...'
```

## CLI Commands

### Load Command
```bash
./wf-bench.sh load -c "CONNECTION_STRING" -d DATABASE -s SCALE [-D]

Options:
  -c, --connection-string  MongoDB connection string (required)
  -d, --database          Target database name
  -s, --scale             SMALL (10K), MEDIUM (100K), LARGE (1M), XLARGE (10M)
  -D, --drop-existing     Drop collections before loading
  -t, --threads           Number of writer threads
  -b, --batch-size        Documents per batch insert
```

### Query Command
```bash
./wf-bench.sh query -c "CONNECTION_STRING" -d DATABASE [options]
```

### Clean Command
```bash
./wf-bench.sh clean -c "CONNECTION_STRING" -d DATABASE
```

## Benchmark Results (SMALL scale - 45,000 docs)

From remote server (129.213.29.234) to Oracle ADB:

| Collection | Documents | Throughput | Avg Latency | P95 Latency |
|------------|-----------|------------|-------------|-------------|
| identity   | 10,000    | 7,576/s    | 304.38 ms   | 588.80 ms   |
| address    | 10,000    | 11,274/s   | 213.87 ms   | 316.93 ms   |
| phone      | 25,000    | 20,358/s   | 139.85 ms   | 317.44 ms   |
| **TOTAL**  | **45,000**| **34,091/s**|            |             |

Total time: 1.3 seconds, Zero errors

## Security Notes

- Connection strings with passwords should NEVER be committed to git
- Use `*-secrets.yaml`, `*-private.yaml`, or `connstr.txt` naming - these are gitignored
- The sample config files contain placeholder credentials only

## Troubleshooting

### SSH Permission Denied
- Verify correct IP address (watch for typos like .23 vs .234)
- Check key file permissions: `chmod 600 ~/.ssh/phoenix`
- Verify key fingerprint matches: `ssh-keygen -lf ~/.ssh/phoenix`

### MongoDB Authentication Errors
- Ensure database path is `/[user]` with literal brackets
- Verify `authSource=$external` is in connection string
- Check `authMechanism=PLAIN` is specified

### Maven Build Failures (501 errors)
- Maven 3.1.x uses HTTP which Maven Central no longer supports
- Upgrade to Maven 3.9+ which uses HTTPS by default

## Deploying and Testing on Phoenix

### Quick Deploy (Build locally, deploy to Phoenix)
```bash
# 1. Build locally with Java 23
JAVA_HOME=/home/rickh/tools/jdk-23.0.1+11 /usr/bin/mvn package -DskipTests

# 2. Deploy JAR to Phoenix
scp -i ~/.ssh/phoenix target/wf-bench-1.0.0-SNAPSHOT.jar opc@129.213.29.234:~/rick/wf-bench/target/
```

### Run Query Benchmark (MongoDB API)
```bash
# Uses connection string stored in ~/connstr.txt on Phoenix
ssh -i ~/.ssh/phoenix opc@129.213.29.234 "export JAVA_HOME=/home/opc/jdk-23.0.1+11 && export PATH=\$JAVA_HOME/bin:\$PATH && cd ~/rick/wf-bench && CONN=\$(cat ~/connstr.txt) && java --enable-preview -jar target/wf-bench-1.0.0-SNAPSHOT.jar query --connection-string \"\$CONN\" --config-file config/sample-query-config.yaml --iterations 10 --warmup 3"
```

### Run Hybrid Search Benchmark (SQL/JDBC)
The hybrid search command requires a JDBC URL for Oracle Text and vector search features.

**JDBC Connection Details:**
- **Wallet Location:** `/home/opc/rick/wallet_wellsfargo/`
- **TNS Names File:** `/home/opc/rick/wallet_wellsfargo/tnsnames.ora`
- **Username:** `ADMIN` (uppercase required for JDBC)
- **Password:** `OracleJson1!` (contains `!` - see escaping notes below)
- **Service Names:** `wellsfargo_low`, `wellsfargo_medium`, `wellsfargo_high`

**JDBC URL Format (with wallet via TNS_ADMIN parameter):**
```
jdbc:oracle:thin:@wellsfargo_low?TNS_ADMIN=/home/opc/rick/wallet_wellsfargo
```

**CRITICAL: Password Escaping Issue with `!` Character**

The password contains `!` which causes shell escaping issues. The solution is to extract the password from the existing MongoDB connection string file:

```bash
# This is the WORKING command that properly handles the ! character:
ssh -i ~/.ssh/phoenix opc@129.213.29.234 "export JAVA_HOME=/home/opc/jdk-23.0.1+11 && export PATH=\$JAVA_HOME/bin:\$PATH && cd ~/rick/wf-bench && PASS=\$(cat ~/connstr.txt | sed 's/.*:\([^@]*\)@.*/\1/') && java --enable-preview -jar target/wf-bench-1.0.0-SNAPSHOT.jar hybrid-search --jdbc-url 'jdbc:oracle:thin:@wellsfargo_low?TNS_ADMIN=/home/opc/rick/wallet_wellsfargo' -u ADMIN -p \"\$PASS\" --run-all-tests"
```

**Why this works:**
1. The password is stored in `~/connstr.txt` (MongoDB connection string)
2. We extract it using `sed 's/.*:\([^@]*\)@.*/\1/'` which captures everything between `:` and `@`
3. The extracted password is stored in `$PASS` variable and used with proper quoting

**DO NOT use these approaches (they fail):**
- `-p 'OracleJson1!'` - bash history expansion mangles the `!`
- `-p "OracleJson1!"` - still gets escaped in double quotes
- `-p OracleJson1\!` - backslash gets passed literally

**JDBC URL Format (without wallet - NOT RECOMMENDED):**
```
jdbc:oracle:thin:@(description=(retry_count=20)(retry_delay=3)(address=(protocol=tcps)(port=1522)(host=adb.us-ashburn-1.oraclecloud.com))(connect_data=(service_name=mqssyowmqvgac1y_wellsfargo_low.adb.oraclecloud.com))(security=(ssl_server_dn_match=yes)))
```

**Note:** JDBC connections to Oracle ADB require either:
1. A wallet file (recommended) - use `TNS_ADMIN` query parameter in JDBC URL
2. OR disable SSL DN matching and certificate verification (not recommended for production)

### Config Files on Phoenix
- **Query Config:** `config/sample-query-config.yaml`
- **Hybrid Search Config:** `config/hybrid-search-config.yaml`
- **MongoDB Connection String:** `~/connstr.txt` (base64 decoded, contains password)

### Oracle Text Search Index
The JSON Search Index for fuzzy text search is already created on the `identity` collection:
```sql
-- Index name: idx_identity_data_text
-- SQL to recreate if needed:
CREATE SEARCH INDEX idx_identity_data_text ON identity(DATA) FOR JSON;
```

### Hybrid Search Features
- **Fuzzy Search:** Uses `CONTAINS(DATA, 'fuzzy(term)', 1) > 0` with JSON Search Index
- **Phonetic Search:** Uses `SOUNDEX()` function for names that sound alike
- **Vector Search:** Requires embedding column and vector index (not yet configured)

## Hybrid Search Benchmark Results

Date: 2025-12-11 | Server: Phoenix (129.213.29.234) | Database: Oracle ADB

| Search Type | Avg (ms) | P50 (ms) | P95 (ms) | P99 (ms) | Throughput | Avg Docs |
|-------------|----------|----------|----------|----------|------------|----------|
| phonetic_name_search | 10.95 | 10.44 | 14.61 | 14.61 | 91.3/s | 1.2 |
| fuzzy_name_search | 4.77 | 4.25 | 8.98 | 8.98 | 209.6/s | 1.2 |
| hybrid_name_search | 17.16 | 17.10 | 18.40 | 18.40 | 58.3/s | 1.4 |
| fuzzy_business_search | 5.81* | 5.77 | 6.20 | 6.20 | 172.0/s | 0.0* |

*\*Business name fuzzy search returns 0 docs due to DRG-50901 syntax errors from special characters in business names (asterisks, hyphens). Needs sanitization improvement.*

**Key Findings:**
- Fuzzy name search is fastest (4.77ms avg, ~210 ops/sec)
- Phonetic search is slower but finds phonetic matches (~11ms avg)
- Hybrid search combines both strategies (~17ms avg, returns 1.4 docs on average)
- Business name search fails on names with special characters (requires fix)
