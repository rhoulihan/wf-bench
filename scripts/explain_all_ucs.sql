-- UC 1-7 Execution Plans with DOT NOTATION
-- Uses sample data for customer 1000250004
-- Updated December 15, 2025 per Oracle team guidance (Rodrigo Fuentes)

SET PAGESIZE 200
SET LINESIZE 300
SET LONG 50000

PROMPT ================================================================================
PROMPT UC-1: Phone + SSN Last 4 (DOT NOTATION)
PROMPT phone=5549414620, ssnLast4=1007
PROMPT ================================================================================

EXPLAIN PLAN FOR
WITH
phones AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) pscore
  FROM "phone"
  WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', '5549414620', 1)
  ORDER BY score(1) DESC
),
identities AS (
  SELECT "DATA", score(2) iscore
  FROM "identity"
  WHERE json_textcontains("DATA", '$."common"."taxIdentificationNumber"', '%1007', 2)
),
addresses AS (
  SELECT "DATA"
  FROM "address"
),
joined AS (
  SELECT
    p."DATA" phone_data,
    i."DATA" identity_data,
    a."DATA" address_data,
    (p.pscore + i.iscore) / 2 ranking_score
  FROM phones p
  JOIN identities i ON i."DATA"."_id"."customerNumber".string() = p."DATA"."phoneKey"."customerNumber".string()
  JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
)
SELECT /*+ MONITOR */ j.ranking_score, j.identity_data
FROM joined j
ORDER BY j.ranking_score DESC
FETCH FIRST 10 ROWS ONLY;

SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY);

PROMPT ================================================================================
PROMPT UC-2: Phone + SSN Last 4 + Account Last 4 (DOT NOTATION)
PROMPT phone=5549414620, ssnLast4=1007, accountLast4=5005
PROMPT ================================================================================

EXPLAIN PLAN FOR
WITH
phones AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) pscore
  FROM "phone"
  WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', '5549414620', 1)
  ORDER BY score(1) DESC
),
identities AS (
  SELECT "DATA", score(2) iscore
  FROM "identity"
  WHERE json_textcontains("DATA", '$."common"."taxIdentificationNumber"', '%1007', 2)
),
accounts AS (
  SELECT "DATA", score(3) ascore
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '5005', 3)
),
addresses AS (
  SELECT "DATA"
  FROM "address"
),
joined AS (
  SELECT
    p."DATA" phone_data,
    i."DATA" identity_data,
    ac."DATA" account_data,
    a."DATA" address_data,
    (p.pscore + i.iscore + ac.ascore) / 3 ranking_score
  FROM phones p
  JOIN identities i ON i."DATA"."_id"."customerNumber".string() = p."DATA"."phoneKey"."customerNumber".string()
  JOIN accounts ac ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
  JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
)
SELECT /*+ MONITOR */ j.ranking_score, j.identity_data
FROM joined j
ORDER BY j.ranking_score DESC
FETCH FIRST 10 ROWS ONLY;

SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY);

PROMPT ================================================================================
PROMPT UC-3: Phone + Account Last 4 (DOT NOTATION)
PROMPT phone=5549414620, accountLast4=5005
PROMPT ================================================================================

EXPLAIN PLAN FOR
WITH
phones AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) pscore
  FROM "phone"
  WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', '5549414620', 1)
  ORDER BY score(1) DESC
),
identities AS (
  SELECT "DATA"
  FROM "identity"
),
accounts AS (
  SELECT "DATA", score(2) ascore
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '5005', 2)
),
addresses AS (
  SELECT "DATA"
  FROM "address"
),
joined AS (
  SELECT
    p."DATA" phone_data,
    i."DATA" identity_data,
    ac."DATA" account_data,
    a."DATA" address_data,
    (p.pscore + ac.ascore) / 2 ranking_score
  FROM phones p
  JOIN identities i ON i."DATA"."_id"."customerNumber".string() = p."DATA"."phoneKey"."customerNumber".string()
  JOIN accounts ac ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
  JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
)
SELECT /*+ MONITOR */ j.ranking_score, j.identity_data
FROM joined j
ORDER BY j.ranking_score DESC
FETCH FIRST 10 ROWS ONLY;

SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY);

PROMPT ================================================================================
PROMPT UC-4: Account Number + SSN Last 4 (DOT NOTATION)
PROMPT accountNumber=100000375005, ssnLast4=1007
PROMPT ================================================================================

EXPLAIN PLAN FOR
WITH
accounts AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) ascore
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumber"', '100000375005', 1)
  ORDER BY score(1) DESC
),
identities AS (
  SELECT "DATA", score(2) iscore
  FROM "identity"
  WHERE json_textcontains("DATA", '$."common"."taxIdentificationNumber"', '%1007', 2)
),
addresses AS (
  SELECT "DATA"
  FROM "address"
),
joined AS (
  SELECT
    ac."DATA" account_data,
    i."DATA" identity_data,
    a."DATA" address_data,
    (ac.ascore + i.iscore) / 2 ranking_score
  FROM accounts ac
  JOIN identities i ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
  JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
)
SELECT /*+ MONITOR */ j.ranking_score, j.identity_data
FROM joined j
ORDER BY j.ranking_score DESC
FETCH FIRST 10 ROWS ONLY;

SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY);

PROMPT ================================================================================
PROMPT UC-5: City/State/ZIP + SSN Last 4 + Account Last 4 (DOT NOTATION)
PROMPT city=South Wilbertfurt, state=CA, zip=54717, ssnLast4=1007, accountLast4=5005
PROMPT Note: WHERE clause uses JSON_VALUE for array access (dot notation doesn't work in WHERE)
PROMPT ================================================================================

EXPLAIN PLAN FOR
WITH
addresses AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) addr_score
  FROM "address"
  WHERE json_textcontains("DATA", '$."addresses"."cityName"', 'South Wilbertfurt', 1)
    AND JSON_VALUE("DATA", '$.addresses[0].stateCode') = 'CA'
    AND JSON_VALUE("DATA", '$.addresses[0].postalCode') = '54717'
  ORDER BY score(1) DESC
),
identities AS (
  SELECT "DATA", score(2) iscore
  FROM "identity"
  WHERE json_textcontains("DATA", '$."common"."taxIdentificationNumber"', '%1007', 2)
),
accounts AS (
  SELECT "DATA", score(3) ascore
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '5005', 3)
),
joined AS (
  SELECT
    a."DATA" address_data,
    i."DATA" identity_data,
    ac."DATA" account_data,
    (a.addr_score + i.iscore + ac.ascore) / 3 ranking_score
  FROM addresses a
  JOIN identities i ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
  JOIN accounts ac ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
)
SELECT /*+ MONITOR */ j.ranking_score, j.identity_data
FROM joined j
ORDER BY j.ranking_score DESC
FETCH FIRST 10 ROWS ONLY;

SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY);

PROMPT ================================================================================
PROMPT UC-6: Email + Account Last 4 (DOT NOTATION)
PROMPT email=ashields, accountLast4=5005
PROMPT ================================================================================

EXPLAIN PLAN FOR
WITH
identities AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) iscore
  FROM "identity"
  WHERE json_textcontains("DATA", '$."emails"."emailAddress"', 'ashields', 1)
  ORDER BY score(1) DESC
),
accounts AS (
  SELECT "DATA", score(2) ascore
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumberLast4"', '5005', 2)
),
addresses AS (
  SELECT "DATA"
  FROM "address"
),
joined AS (
  SELECT
    i."DATA" identity_data,
    ac."DATA" account_data,
    a."DATA" address_data,
    (i.iscore + ac.ascore) / 2 ranking_score
  FROM identities i
  JOIN accounts ac ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
  JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
)
SELECT /*+ MONITOR */ j.ranking_score, j.identity_data
FROM joined j
ORDER BY j.ranking_score DESC
FETCH FIRST 10 ROWS ONLY;

SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY);

PROMPT ================================================================================
PROMPT UC-7: Email + Phone + Account Number (DOT NOTATION)
PROMPT email=ashields, phone=5549414620, accountNumber=100000375005
PROMPT ================================================================================

EXPLAIN PLAN FOR
WITH
identities AS (
  SELECT /*+ DOMAIN_INDEX_SORT */ "DATA", score(1) iscore
  FROM "identity"
  WHERE json_textcontains("DATA", '$."emails"."emailAddress"', 'ashields', 1)
  ORDER BY score(1) DESC
),
phones AS (
  SELECT "DATA", score(2) pscore
  FROM "phone"
  WHERE json_textcontains("DATA", '$."phoneKey"."phoneNumber"', '5549414620', 2)
),
accounts AS (
  SELECT "DATA", score(3) ascore
  FROM "account"
  WHERE json_textcontains("DATA", '$."accountKey"."accountNumber"', '100000375005', 3)
),
addresses AS (
  SELECT "DATA"
  FROM "address"
),
joined AS (
  SELECT
    i."DATA" identity_data,
    p."DATA" phone_data,
    ac."DATA" account_data,
    a."DATA" address_data,
    (i.iscore + p.pscore + ac.ascore) / 3 combined_score
  FROM identities i
  JOIN phones p ON p."DATA"."phoneKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
  JOIN accounts ac ON ac."DATA"."accountKey"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
  JOIN addresses a ON a."DATA"."_id"."customerNumber".string() = i."DATA"."_id"."customerNumber".string()
)
SELECT /*+ MONITOR */ j.combined_score, j.identity_data
FROM joined j
ORDER BY j.combined_score DESC
FETCH FIRST 10 ROWS ONLY;

SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY);

EXIT;
