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
