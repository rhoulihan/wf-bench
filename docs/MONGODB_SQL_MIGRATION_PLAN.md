# MongoDB $sql Operator Migration Plan

## Overview

This document outlines the plan to migrate the UC 1-7 search implementations from direct JDBC/SQL to using the MongoDB API's `$sql` aggregation operator. This approach allows running SQL queries through the MongoDB wire protocol while leveraging Oracle's full SQL capabilities.

## Background: Oracle Team Discussion Summary

From the Oracle team discussion, several key points were identified:

### 1. Query Pattern with $sql Operator
```javascript
db.aggregate([{"$sql": `
  WITH
  phones AS (
    SELECT "DATA", score(1) pscore FROM phone_sm
    WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', '4155551234', 1)
    ORDER BY score(1) DESC
  ),
  identities AS (
    SELECT "DATA", score(1) iscore FROM identity_sm
    WHERE json_textcontains("DATA", '$."common"."taxIdentificationNumber"', '*6789', 1)
    ORDER BY score(1) DESC
  ),
  joined AS (
    SELECT p.data phone, i.data identity, (pscore + iscore) / 2 score
    FROM phones p, identities i
    WHERE i.data."_id"."customerNumber" = p.data."phoneKey"."customerNumber"
  )
  SELECT json {
    'score' : score,
    'companyNumber' : j.identity."_id"."customerCompanyNumber",
    'entityTypeIndicator' : j.identity."common"."entityTypeIndicator"
  }
  FROM joined j
`}]);
```

### 2. Key Syntax Differences

| Feature | Current (JDBC) | New (MongoDB $sql) |
|---------|----------------|-------------------|
| Text Search | `CONTAINS(p.DATA, ?, 1) > 0` | `json_textcontains("DATA", '<path>', 'term', 1)` |
| Wildcard (ends with) | N/A | `json_textcontains(data, '<path>', '%abc')` |
| Wildcard (starts with) | N/A | `json_textcontains(data, '<path>', 'abc%')` |
| Score Access | `SCORE(1)` | `score(1)` |
| Table Names | `{prefix}phone` | `"phone_sm"` (quoted lowercase) |
| JSON Path | `json_value(p.DATA, '$.path')` | `p.data."path"."subpath"` |
| Output | ResultSet columns | `json {...}` object |

### 3. Optimization Hints

```sql
-- Push sort into domain index for better performance
SELECT /*+ DOMAIN_INDEX_SORT */ "DATA"
FROM phone
WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', 'term', 1)
ORDER BY score(1) DESC
```

### 4. K-grams for Wildcard Search (Optional Enhancement)

To enable efficient wildcard searches, the Oracle team suggests enabling k-grams on the search index:

```sql
-- Create preference for wildcard indexing
ctx_ddl.create_preference('idx_wl', 'BASIC_WORDLIST');
ctx_ddl.set_attribute('idx_wl', 'WILDCARD_INDEX');
ctx_ddl.set_attribute('idx_wl', 'WILDCARD_INDEX_K', '4');
```

---

## Implementation Plan

### Phase 1: Create New MongoDB $sql Search Service

**File:** `src/main/java/com/wf/benchmark/search/MongoSqlSearchService.java`

This new service will:
1. Use MongoDB Java driver for connection management
2. Execute queries using the `$sql` aggregation operator
3. Parse BSON document results

```java
public class MongoSqlSearchService {
    private final MongoClient mongoClient;
    private final String databaseName;
    private final String collectionPrefix;

    // UC 1-7 query methods using $sql operator
    public List<UcSearchResult> searchUC1(String phoneNumber, String ssnLast4, int limit);
    // ...
}
```

### Phase 2: UC Query Conversions

#### UC-1: Phone + SSN Last 4

**Current Pattern (JDBC):**
```sql
SELECT SCORE(1) as ranking_score, ...
FROM {prefix}phone p
JOIN {prefix}identity i ON json_value(p.DATA, '$.phoneKey.customerNumber') =
                           json_value(i.DATA, '$._id.customerNumber')
LEFT JOIN {prefix}address a ON ...
WHERE CONTAINS(p.DATA, ?, 1) > 0
  AND json_value(i.DATA, '$.common.taxIdentificationNumberLast4') = ?
ORDER BY SCORE(1) DESC
FETCH FIRST ? ROWS ONLY
```

**New Pattern (MongoDB $sql):**
```sql
WITH
phones AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) pscore, RESID
  FROM "phone"
  WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', ':phoneNumber', 1)
  ORDER BY score(1) DESC
),
identities AS (
  SELECT "DATA", RESID
  FROM "identity"
  WHERE "DATA"."common"."taxIdentificationNumberLast4" = ':ssnLast4'
),
addresses AS (
  SELECT "DATA", RESID
  FROM "address"
),
joined AS (
  SELECT
    p.data phone_data,
    i.data identity_data,
    a.data address_data,
    p.pscore ranking_score
  FROM phones p
  JOIN identities i ON i.data."_id"."customerNumber" = p.data."phoneKey"."customerNumber"
  LEFT JOIN addresses a ON a.data."_id"."customerNumber" = i.data."_id"."customerNumber"
)
SELECT json {
  'rankingScore' : j.ranking_score,
  'ecn' : j.identity_data."_id"."customerNumber",
  'companyId' : NVL(j.identity_data."_id"."customerCompanyNumber", 1),
  'entityType' : j.identity_data."common"."entityTypeIndicator",
  'name' : j.identity_data."common"."fullName",
  'taxIdNumber' : j.identity_data."common"."taxIdentificationNumber",
  'addressLine' : j.address_data."addresses"[0]."addressLine1",
  'cityName' : j.address_data."addresses"[0]."cityName",
  'state' : j.address_data."addresses"[0]."stateCode",
  'postalCode' : j.address_data."addresses"[0]."postalCode",
  'countryCode' : NVL(j.address_data."addresses"[0]."countryCode", 'US')
}
FROM joined j
FETCH FIRST :limit ROWS ONLY
```

#### UC-2: Phone + SSN Last 4 + Account Last 4

Similar CTE pattern with additional `accounts` CTE and join on `accountHolders[0].customerNumber`.

#### UC-3: Phone + Account Last 4

Similar to UC-2 but without SSN filter.

#### UC-4: Account Number + SSN Last 4

```sql
WITH
accounts AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) ascore, RESID
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumber"', ':accountNumber', 1)
  ORDER BY score(1) DESC
),
identities AS (
  SELECT "DATA", RESID
  FROM "identity"
  WHERE "DATA"."common"."taxIdentificationNumberLast4" = ':ssnLast4'
),
-- ... rest similar pattern
```

#### UC-5: City/State/ZIP + SSN Last 4 + Account Last 4

```sql
WITH
addresses AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) addr_score, RESID
  FROM "address"
  WHERE json_textcontains("DATA", '$."addresses"[0]."cityName"', ':city', 1)
    AND "DATA"."addresses"[0]."stateCode" = ':state'
    AND "DATA"."addresses"[0]."postalCode" = ':zip'
  ORDER BY score(1) DESC
),
-- ... rest of CTEs
```

#### UC-6: Email + Account Last 4

```sql
WITH
identities AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) iscore, RESID
  FROM "identity"
  WHERE json_textcontains("DATA", '$."emails"[0]."emailAddress"', ':email', 1)
  ORDER BY score(1) DESC
),
accounts AS (
  SELECT "DATA", RESID
  FROM "account"
  WHERE "DATA"."accountKey"."accountNumberLast4" = ':accountLast4'
),
-- ... rest similar pattern
```

#### UC-7: Email + Phone + Account Number

Joins identity, phone, and account with text search on email.

---

### Phase 3: New CLI Command Options

Add to `HybridSearchCommand.java`:

```java
@Option(names = {"--mongo-sql-benchmark"},
        description = "Run UC 1-7 benchmark using MongoDB $sql operator",
        defaultValue = "false")
private boolean mongoSqlBenchmark;

@Option(names = {"--create-mongo-sql-indexes"},
        description = "Create search indexes with k-gram wordlist for wildcard support",
        defaultValue = "false")
private boolean createMongoSqlIndexes;
```

### Phase 4: Index Configuration with K-grams

Create method to set up wordlist preferences for wildcard search:

```java
public void createWildcardSearchIndexes(Connection conn) throws SQLException {
    // Create k-gram wordlist preference
    String createPreference = """
        BEGIN
          ctx_ddl.create_preference('uc_wordlist', 'BASIC_WORDLIST');
          ctx_ddl.set_attribute('uc_wordlist', 'WILDCARD_INDEX', 'TRUE');
          ctx_ddl.set_attribute('uc_wordlist', 'WILDCARD_INDEX_K', '4');
        EXCEPTION
          WHEN OTHERS THEN
            IF SQLCODE != -20000 THEN RAISE; END IF;
        END;
        """;

    // Create search indexes with the wordlist
    String createIndex = """
        CREATE SEARCH INDEX idx_%s_search ON "%s"(DATA) FOR JSON
        PARAMETERS('WORDLIST uc_wordlist')
        """;
    // ...
}
```

---

## Implementation Files Summary

| File | Description | Action |
|------|-------------|--------|
| `MongoSqlSearchService.java` | New service using $sql operator | **Create** |
| `MongoSqlQueryBuilder.java` | Builds UC 1-7 SQL for $sql operator | **Create** |
| `HybridSearchCommand.java` | CLI command | **Modify** |
| `UcSearchService.java` | Existing JDBC service | **Keep** (for comparison) |

---

## Key Differences from Current Implementation

### 1. Connection Type
- **Current:** JDBC DataSource (HikariCP)
- **New:** MongoDB Java Driver (MongoClient)

### 2. Query Execution
- **Current:** PreparedStatement with parameter binding
- **New:** String interpolation in $sql (with proper escaping)

### 3. Result Parsing
- **Current:** ResultSet columns
- **New:** BSON Document with JSON structure

### 4. Score Handling
- **Current:** `SCORE(1)` returns integer 0-100
- **New:** `score(1)` returns same, but multiple scores can be combined in CTE

### 5. JSON Path Syntax
- **Current:** `json_value(i.DATA, '$.common.fullName')`
- **New:** `i.data."common"."fullName"` (dot notation with quoted keys)

---

## Testing Strategy

1. **Unit Tests:** Compare results from MongoSqlSearchService vs UcSearchService
2. **Performance Benchmark:** Compare latencies side-by-side
3. **Validation:** Ensure identical result sets for same inputs

---

## Rollback Plan

The existing `UcSearchService.java` (JDBC-based) will be retained. The new MongoDB $sql implementation is additive. Benchmarks can switch between approaches via CLI flags:

- `--uc-search-benchmark` → Existing JDBC approach
- `--mongo-sql-benchmark` → New MongoDB $sql approach

---

## Open Questions

1. **Parameter Binding:** Does `$sql` support parameterized queries, or must values be interpolated (with SQL injection prevention)?

2. **Index Compatibility:** Are existing `CREATE SEARCH INDEX ... FOR JSON` indexes compatible with `json_textcontains()`?

3. **K-gram Index Creation:** Can k-gram preferences be set via MongoDB API or only via SQL/JDBC?

4. **Score Combination:** The Oracle team example uses `(pscore + iscore) / 2` for combined scores. Should we:
   - Use average of all matching scores?
   - Weight scores by search field importance?
   - Use max score?

---

## Timeline Estimate

| Phase | Tasks |
|-------|-------|
| Phase 1 | Create MongoSqlSearchService skeleton, basic $sql execution |
| Phase 2 | Implement UC-1 through UC-7 query builders |
| Phase 3 | Add CLI options and benchmark infrastructure |
| Phase 4 | Testing and performance comparison |

---

## References

- Oracle MongoDB API $sql operator documentation
- Oracle Text json_textcontains() function
- K-gram indexing for wildcard search
