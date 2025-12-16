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

**Credentials:** See `db-secrets.yaml` (gitignored) for actual password.

**Important notes:**
- The database path MUST be `/[user]` (with literal brackets) - NOT `/admin` or other database names
- `authSource=$external` is required for Oracle ADB authentication
- `loadBalanced=true` is required for Oracle ADB

### Shell Escaping Issues

When passing the connection string via SSH, the `!` and `$` characters cause shell escaping issues. Use base64 encoding to transfer safely:

```bash
# Encode locally (get connection string from db-secrets.yaml)
printf '%s' 'mongodb://admin:PASSWORD@...' | base64

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
- **Password:** See `db-secrets.yaml` (contains `!` - see escaping notes below)
- **Service Names:** `wellsfargo_low`, `wellsfargo_medium`, `wellsfargo_high`

**JDBC URL Format (with wallet via TNS_ADMIN parameter):**
```
jdbc:oracle:thin:@wellsfargo_low?TNS_ADMIN=/home/opc/rick/wallet_wellsfargo
```

**HIGH Service (for DDL operations like index creation):**
Use the HIGH service for better performance during DDL operations:
```
jdbc:oracle:thin:@wellsfargo_high?TNS_ADMIN=/home/opc/rick/wallet_wellsfargo
```

Or direct connect string (without wallet):
```
(description= (retry_count=20)(retry_delay=3)(address=(protocol=tcps)(port=1521)(host=adb.us-ashburn-1.oraclecloud.com))(connect_data=(service_name=mqssyowmqvgac1y_wellsfargo_high.adb.oraclecloud.com))(security=(ssl_server_dn_match=yes)))
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
- `-p 'PASSWORD!'` - bash history expansion mangles the `!`
- `-p "PASSWORD!"` - still gets escaped in double quotes
- `-p PASSWORD\!` - backslash gets passed literally

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

### Oracle Text Search Index (Wildcard Optimized)

JSON Search Indexes are created with **wildcard optimization** (per Rodrigo Fuentes) for efficient `%term` pattern searches:

```sql
-- 1. Create wordlist preference with wildcard optimization (K=4 k-gram index)
BEGIN
  ctx_ddl.create_preference('idx_wl', 'BASIC_WORDLIST');
  ctx_ddl.set_attribute('idx_wl', 'WILDCARD_INDEX', 'TRUE');
  ctx_ddl.set_attribute('idx_wl', 'WILDCARD_INDEX_K', '4');
END;
/

-- 2. Create search indexes with wildcard wordlist
CREATE SEARCH INDEX idx_identity_search ON identity(DATA) FOR JSON PARAMETERS ('wordlist idx_wl');
CREATE SEARCH INDEX idx_phone_search ON phone(DATA) FOR JSON PARAMETERS ('wordlist idx_wl');
CREATE SEARCH INDEX idx_account_search ON account(DATA) FOR JSON PARAMETERS ('wordlist idx_wl');
CREATE SEARCH INDEX idx_address_search ON address(DATA) FOR JSON PARAMETERS ('wordlist idx_wl');
```

**Wildcard Index Benefits:**
- `WILDCARD_INDEX=TRUE` enables k-gram index for efficient wildcard searches
- `WILDCARD_INDEX_K=4` creates 4-character gram index
- Optimizes `%term` patterns (SSN last-4, partial matches)
- **20x performance improvement** on SSN last-4 searches

### Hybrid Search Features
- **Fuzzy Search:** Uses `json_textcontains(DATA, '$.path', 'term', 1)` with JSON Search Index
- **Phonetic Search:** Uses `SOUNDEX()` function for names that sound alike
- **Vector Search:** Requires embedding column and vector index (not yet configured)

## Benchmark Results

See [results/BENCHMARK_SUMMARY.md](results/BENCHMARK_SUMMARY.md) for detailed benchmark results including:
- Data load benchmarks
- Hybrid search benchmarks (fuzzy, phonetic, combined)
- UC 1-7 search benchmarks (SQL JOINs with Oracle Text)
