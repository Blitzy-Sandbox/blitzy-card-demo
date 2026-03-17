# CardDemo Migration — Validation Gate Evidence

## Purpose

This document provides comprehensive evidence and verification procedures for all **8 validation gates** defined for the CardDemo mainframe-to-cloud migration. Each gate includes its objective, verification methodology, acceptance criteria, and an evidence template for recording results during execution.

**Migration Context:**
- **Source:** AWS CardDemo COBOL application (commit `27d6c6f`) — z/OS CICS/VSAM/JCL
- **Target:** Java 25 LTS + Spring Boot 3.x + PostgreSQL 16+ + AWS (S3/SQS/SNS via LocalStack)
- **Scope:** 28 COBOL programs, 28 copybooks, 17 BMS mapsets, 29 JCL jobs, 9 ASCII fixture files

---

## Table of Contents

1. [Gate 1 — End-to-End Boundary Verification](#gate-1--end-to-end-boundary-verification)
2. [Gate 2 — Zero-Warning Build](#gate-2--zero-warning-build)
3. [Gate 3 — Performance Baseline](#gate-3--performance-baseline)
4. [Gate 4 — Named Real-World Validation Artifacts](#gate-4--named-real-world-validation-artifacts)
5. [Gate 5 — API/Interface Contract Verification](#gate-5--apiinterface-contract-verification)
6. [Gate 6 — Unsafe/Low-Level Code Audit](#gate-6--unsafelow-level-code-audit)
7. [Gate 7 — Scope Matching (Extended)](#gate-7--scope-matching-extended)
8. [Gate 8 — Integration Sign-Off Checklist](#gate-8--integration-sign-off-checklist)

---

## Gate 1 — End-to-End Boundary Verification

### Objective

Verify that the Java batch pipeline (`DailyTransactionPostingJob`) produces identical behavioral output to the COBOL batch program (`CBTRN02C`) when processing the same production-representative daily transaction file. This gate confirms 100% behavioral parity for the most critical batch processing path.

### Input Artifact

| Property | Value |
|---|---|
| **Source File** | `app/data/ASCII/dailytran.txt` |
| **Record Count** | 300 daily transaction records |
| **Record Format** | Fixed-width, 350 bytes per record |
| **Key Fields** | Transaction ID (16 bytes), Card Number (16 bytes), Transaction Amount, Transaction Type/Category Codes, Merchant Info, Timestamp |
| **COBOL Source** | `CBTRN02C.cbl` (731 lines — Daily Transaction Posting engine) |
| **JCL Job** | `POSTTRAN.jcl` (STEP15 EXEC PGM=CBTRN02C) |

### Processing Path

The end-to-end processing path traces the complete data flow through the batch posting pipeline:

```
Input: dailytran.txt (S3 / sequential file)
  │
  ▼
DailyTransactionPostingJob (Spring Batch)
  │
  ├─► Step 1: Read daily transaction records
  │     └─ DailyTransactionReader (← CBTRN02C 1000-DALYTRAN-GET-NEXT)
  │
  ├─► Step 2: Validate each transaction (4-stage cascade)
  │     └─ TransactionPostingProcessor (← CBTRN02C 1500-VALIDATE-TRAN)
  │         ├─ Stage 1: Cross-reference lookup (1500-A-LOOKUP-XREF)
  │         ├─ Stage 2: Account lookup (1500-B-LOOKUP-ACCT)
  │         ├─ Stage 3: Credit limit check (within 1500-B)
  │         └─ Stage 4: Account expiration check (within 1500-B)
  │
  ├─► Step 3a: Post valid transactions
  │     ├─ Update TCATBAL record (← 2700-UPDATE-TCATBAL)
  │     ├─ Update Account balance (← 2800-UPDATE-ACCOUNT-REC)
  │     └─ Write to Transaction file (← 2900-WRITE-TRANSACTION-FILE)
  │
  └─► Step 3b: Write rejected transactions
        └─ RejectWriter → S3 rejection file (← 2500-WRITE-REJECT-REC)

Output:
  ├─ PostgreSQL: transactions table (posted records)
  ├─ PostgreSQL: transaction_category_balance table (updated/created balances)
  ├─ PostgreSQL: accounts table (updated balances)
  └─ S3: rejection file with validation trailers (rejected records)
```

### 4-Stage Validation Cascade

The validation cascade from `CBTRN02C.cbl` paragraphs `1500-VALIDATE-TRAN`, `1500-A-LOOKUP-XREF`, and `1500-B-LOOKUP-ACCT` implements a sequential fail-fast validation pipeline. Each stage runs only if all prior stages passed (fail-reason remains 0).

| Stage | COBOL Paragraph | Validation Rule | Reject Code | Reject Description | Java Equivalent |
|---|---|---|---|---|---|
| 1 | `1500-A-LOOKUP-XREF` | Card number exists in cross-reference (CARDXREF) dataset | **100** | `INVALID CARD NUMBER FOUND` | `CardCrossReferenceRepository.findById(cardNum)` — throws `RecordNotFoundException` if absent |
| 2 | `1500-B-LOOKUP-ACCT` | Account ID (from XREF) exists in account master (ACCTDAT) dataset | **101** | `ACCOUNT RECORD NOT FOUND` | `AccountRepository.findById(acctId)` — throws `RecordNotFoundException` if absent |
| 3 | `1500-B-LOOKUP-ACCT` | Credit limit not exceeded: `ACCT-CREDIT-LIMIT >= (ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + TRAN-AMT)` | **102** | `OVERLIMIT TRANSACTION` | `CreditLimitExceededException` when computed balance exceeds limit using `BigDecimal` arithmetic |
| 4 | `1500-B-LOOKUP-ACCT` | Account not expired: `ACCT-EXPIRAION-DATE >= TRAN-ORIG-TS(1:10)` (date portion) | **103** | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | `ExpiredCardException` when `account.expirationDate.isBefore(transaction.originTimestamp.toLocalDate())` |

**Additional Reject Codes (Post-Validation, during Posting):**

| Code | COBOL Paragraph | Condition | Description |
|---|---|---|---|
| **109** | `2800-UPDATE-ACCOUNT-REC` | REWRITE INVALID KEY on account master | `ACCOUNT RECORD NOT FOUND` during account balance update |

**Condition Code Logic:**
- If `WS-REJECT-COUNT > 0` at end of processing, `RETURN-CODE` is set to **4** (partial success with rejections)
- If no rejections, `RETURN-CODE` remains **0** (full success)
- Java equivalent: `ExitStatus("COMPLETED_WITH_REJECTIONS")` vs `ExitStatus.COMPLETED`

### Verification Commands

```bash
# Step 1: Ensure the application and PostgreSQL are running
docker-compose up -d

# Step 2: Seed the database with fixture data (Flyway runs automatically on startup)
mvn spring-boot:run -Dspring.profiles.active=local &
sleep 15

# Step 3: Upload the daily transaction file to S3 (LocalStack)
aws --endpoint-url=http://localhost.localstack.cloud:4566 s3 cp \
    src/test/resources/data/dailytran.txt \
    s3://carddemo-batch-input/daily/dailytran.txt

# Step 4: Trigger the DailyTransactionPostingJob
curl -X POST http://localhost:8080/api/batch/jobs/daily-posting/run

# Step 5: Verify results
# Check transaction count in PostgreSQL
psql -h localhost -U carddemo -d carddemo \
    -c "SELECT COUNT(*) AS posted FROM transactions WHERE proc_ts IS NOT NULL;"

# Check rejection file in S3
aws --endpoint-url=http://localhost.localstack.cloud:4566 s3 ls \
    s3://carddemo-batch-output/rejections/

# Step 6: Download and inspect rejection file
aws --endpoint-url=http://localhost.localstack.cloud:4566 s3 cp \
    s3://carddemo-batch-output/rejections/dailyrejs-latest.txt \
    /tmp/rejections.txt
cat /tmp/rejections.txt
```

### Evidence Template — Boundary Comparison Report

The following table is populated during actual execution by comparing COBOL-expected output against Java-actual output for each record in `dailytran.txt`. A representative sample of 20 records is shown; the complete report covers all 300 records.

| Record # | Transaction ID | Card Number | Tran Amount | Validation Result | Reject Code | Reject Reason | Expected Output | Java Output | Match |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 0000000000683580 | 4859452612877065 | $50.47 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |
| 2 | 0000000001774260 | 0927987108636232 | -$91.90 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |
| 3 | 0000000006292564 | 6009619150674526 | $6.78 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |
| 4 | 0000000009101861 | 8040580410348680 | $28.17 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |
| 5 | 0000000010142252 | 5656830544981216 | $45.46 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |
| 6 | 0000000010229018 | 7379335634661142 | $84.99 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |
| 7 | 0000000016259484 | 4011500891777367 | -$5.67 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |
| 8 | 0000000017874199 | 8040580410348680 | $37.36 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |
| 9 | 0000000019065428 | 6503535181795992 | -$53.58 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |
| 10 | 0000000021711604 | 9501733721429893 | $41.61 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |
| 11 | 0000000025430891 | 3260763612337560 | $9.43 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |
| 12 | 0000000028097268 | 7094142751055551 | $25.02 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |
| 13 | 0000000030755266 | 3766281984155154 | $82.95 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |
| 14 | 0000000032979555 | 6509230362553816 | $2.94 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |
| 15 | 0000000033688127 | 3766281984155154 | $95.89 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |
| 16 | 0000000040455859 | 1142167692878931 | $71.54 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |
| 17 | 0000000043636099 | 2940139362300449 | -$94.56 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |
| 18 | 0000000051205286 | 7094142751055551 | $64.93 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |
| 19 | 0000000054288996 | 4534784102713951 | $50.26 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |
| 20 | 0000000054727064 | 1014086565224350 | $30.31 | _PENDING_ | — | — | _PENDING_ | _PENDING_ | _PENDING_ |

> **Note:** Negative amounts (indicated by COBOL signed overpunch characters `}`, `J`–`R` in the source data) represent return/credit transactions. The `{` overpunch represents positive zero or positive amounts. Java `BigDecimal` parsing must handle these EBCDIC signed numeric conventions.

### Summary Metrics

| Metric | Expected | Actual | Status |
|---|---|---|---|
| Total records read | 300 | _PENDING_ | _PENDING_ |
| Records posted successfully | _PENDING_ | _PENDING_ | _PENDING_ |
| Records rejected | _PENDING_ | _PENDING_ | _PENDING_ |
| Rejection code 100 (card not found) | _PENDING_ | _PENDING_ | _PENDING_ |
| Rejection code 101 (account not found) | _PENDING_ | _PENDING_ | _PENDING_ |
| Rejection code 102 (overlimit) | _PENDING_ | _PENDING_ | _PENDING_ |
| Rejection code 103 (expired account) | _PENDING_ | _PENDING_ | _PENDING_ |
| Rejection code 109 (account update error) | _PENDING_ | _PENDING_ | _PENDING_ |
| Exit status | COMPLETED or COMPLETED_WITH_REJECTIONS | _PENDING_ | _PENDING_ |

---

## Gate 2 — Zero-Warning Build

### Objective

Verify that the entire Java project compiles and passes all tests with **zero warnings** and **zero errors**. This gate ensures production-grade code quality with no suppressed diagnostics except for framework-generated code.

### Build Command

```bash
# Full build with warnings treated as errors
source "/root/.sdkman/bin/sdkman-init.sh"
mvn clean verify -B \
    -Dmaven.compiler.failOnWarning=true
```

The Maven compiler plugin is configured with strict warning settings:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <source>25</source>
        <target>25</target>
        <compilerArgs>
            <arg>-Xlint:all</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

### Suppression Policy

| Suppression Category | Allowed | Justification |
|---|---|---|
| JPA metamodel (`@SuppressWarnings("unused")`) | Yes (≤3 instances) | JPA static metamodel classes are generated by the persistence provider and may trigger unused warnings in IDE/compiler static analysis |
| MapStruct mapper warnings | Yes (≤2 instances) | Code-generated mapper implementations may have unchecked assignments due to generic type erasure |
| Spring Batch `ItemProcessor` generics | Yes (≤2 instances) | `ItemProcessor<I, O>` interface requires unchecked casts when working with composite processors |
| All other categories | **No** | All warnings must be resolved at the source |

### Verification Steps

```bash
# Step 1: Run the full build with strict warnings
mvn clean verify -B 2>&1 | tee build-output.log

# Step 2: Grep for any warnings in the build output
grep -c "\[WARNING\]" build-output.log

# Step 3: Grep for compiler warnings specifically
grep -c "warning:" build-output.log

# Step 4: Verify zero test failures
grep -c "Tests run:.*Failures: [^0]" build-output.log

# Step 5: Check for any @SuppressWarnings usage
find src/main/java -name "*.java" -exec grep -l "@SuppressWarnings" {} \;
```

### Evidence Template — Build Log Excerpt

```
[INFO] --- maven-compiler-plugin:3.x:compile (default-compile) ---
[INFO] Compiling XXX source files with javac [release 25]
[INFO] --- maven-surefire-plugin:3.5.2:test (default-test) ---
[INFO] Tests run: XXX, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- maven-failsafe-plugin:3.5.2:integration-test (default-it) ---
[INFO] Tests run: XXX, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time: XX:XX min
```

| Check | Criterion | Result | Status |
|---|---|---|---|
| Compilation warnings | 0 | _PENDING_ | _PENDING_ |
| Compilation errors | 0 | _PENDING_ | _PENDING_ |
| Unit test failures | 0 | _PENDING_ | _PENDING_ |
| Integration test failures | 0 | _PENDING_ | _PENDING_ |
| `@SuppressWarnings` count | ≤5 total | _PENDING_ | _PENDING_ |
| Suppression justifications | All documented | _PENDING_ | _PENDING_ |

### Allowed Suppressions Log

| File | Annotation | Reason | Justified |
|---|---|---|---|
| _PENDING_ | _PENDING_ | _PENDING_ | _PENDING_ |

---

## Gate 3 — Performance Baseline

### Objective

Establish a performance baseline for the Java batch pipeline by measuring throughput, latency, and memory consumption during execution of the `DailyTransactionPostingJob` against the full fixture dataset. This baseline serves as the reference for future performance regression detection.

> **Note:** COBOL baseline metrics are not available — the source repository contains no SLA documentation, benchmark results, or performance specifications. The Java implementation establishes the initial baseline.

### Benchmark Command

```bash
# Step 1: Ensure clean state
docker-compose down -v && docker-compose up -d
sleep 10

# Step 2: Start the application with JMX monitoring enabled
mvn spring-boot:run -Dspring.profiles.active=local \
    -Dcom.sun.management.jmxremote \
    -Dcom.sun.management.jmxremote.port=9010 \
    -Dcom.sun.management.jmxremote.authenticate=false \
    -Dcom.sun.management.jmxremote.ssl=false &
APP_PID=$!
sleep 15

# Step 3: Upload fixture data to S3
aws --endpoint-url=http://localhost.localstack.cloud:4566 s3 cp \
    src/test/resources/data/dailytran.txt \
    s3://carddemo-batch-input/daily/dailytran.txt

# Step 4: Record start time and trigger batch job
START_TIME=$(date +%s%N)
curl -X POST http://localhost:8080/api/batch/jobs/daily-posting/run
END_TIME=$(date +%s%N)

# Step 5: Calculate elapsed time
ELAPSED_MS=$(( (END_TIME - START_TIME) / 1000000 ))
echo "Elapsed time: ${ELAPSED_MS}ms"

# Step 6: Capture JVM memory metrics via Actuator
curl -s http://localhost:8080/actuator/metrics/jvm.memory.used | python3 -m json.tool
curl -s http://localhost:8080/actuator/metrics/jvm.memory.max | python3 -m json.tool

# Step 7: Query batch job execution metrics
curl -s http://localhost:8080/actuator/metrics/spring.batch.job.active.count | python3 -m json.tool
```

### Metrics to Capture

| Metric Category | Specific Metric | Measurement Method |
|---|---|---|
| **Throughput** | Records processed per second | `total_records / elapsed_seconds` |
| **Throughput** | Records posted per second | `posted_records / elapsed_seconds` |
| **Latency** | Total job elapsed time (ms) | Wall-clock time from job start to completion |
| **Latency** | Average per-record processing time (µs) | `elapsed_ms * 1000 / total_records` |
| **Memory** | Peak JVM heap usage (MB) | JMX `java.lang:type=Memory` HeapMemoryUsage.used |
| **Memory** | Max JVM heap allocated (MB) | JMX `java.lang:type=Memory` HeapMemoryUsage.max |
| **Database** | Total SQL statements executed | Hibernate statistics or p6spy log count |
| **I/O** | S3 read operations | LocalStack request log count |
| **I/O** | S3 write operations | LocalStack request log count |

### Evidence Template — Performance Baseline Report

| Metric | Value | Unit | Notes |
|---|---|---|---|
| Total records read | _PENDING_ | records | From `dailytran.txt` (300 expected) |
| Records posted | _PENDING_ | records | Successfully validated and written |
| Records rejected | _PENDING_ | records | Failed validation cascade |
| Total elapsed time | _PENDING_ | ms | Wall-clock job duration |
| Throughput (total) | _PENDING_ | records/sec | All records including rejects |
| Throughput (posted) | _PENDING_ | records/sec | Successfully posted only |
| Avg per-record time | _PENDING_ | µs | Mean processing time per record |
| Peak JVM heap used | _PENDING_ | MB | Maximum heap during job execution |
| Max JVM heap allocated | _PENDING_ | MB | Configured heap ceiling |
| SQL statements | _PENDING_ | count | Total database operations |
| S3 read operations | _PENDING_ | count | File reads from S3 |
| S3 write operations | _PENDING_ | count | Output writes to S3 |

### Acceptance Criteria

Since no COBOL baseline exists, the following thresholds establish the minimum acceptable performance:

| Metric | Minimum Threshold | Rationale |
|---|---|---|
| Throughput | ≥100 records/sec | 300-record dataset should complete in under 3 seconds |
| Peak heap | ≤512 MB | Reasonable for batch processing 300 records |
| Job completion | SUCCESS or COMPLETED_WITH_REJECTIONS | Job must not fail or abort |

---

## Gate 4 — Named Real-World Validation Artifacts

### Objective

Verify that all 9 ASCII fixture files from the original COBOL `app/data/ASCII/` directory are successfully loaded into PostgreSQL via the Flyway `V3__seed_data.sql` migration and are processable through the complete batch pipeline. Each file represents a real-world data entity from the CardDemo application.

### Named Fixture Files

| # | File Name | Entity | COBOL Record Layout | VSAM Dataset | Description |
|---|---|---|---|---|---|
| 1 | `acctdata.txt` | Account | `CVACT01Y.cpy` (300 bytes) | `ACCTDATA.VSAM.KSDS` | Account master records with balances, credit limits, and dates |
| 2 | `carddata.txt` | Card | `CVACT02Y.cpy` (150 bytes) | `CARDDATA.VSAM.KSDS` | Credit card records linked to accounts |
| 3 | `custdata.txt` | Customer | `CVCUS01Y.cpy` (500 bytes) | `CUSTDATA.VSAM.KSDS` | Customer demographic records |
| 4 | `cardxref.txt` | CardCrossReference | `CVACT03Y.cpy` (50 bytes) | `CARDXREF.VSAM.KSDS` | Card-to-account cross-reference lookup |
| 5 | `dailytran.txt` | DailyTransaction | `CVTRA06Y.cpy` (350 bytes) | `DALYTRAN.PS` | Daily transaction staging file for batch posting |
| 6 | `discgrp.txt` | DisclosureGroup | `CVTRA02Y.cpy` (50 bytes) | `DISCGRP.VSAM.KSDS` | Interest rate disclosure groups (DEFAULT, ZEROAPR, account-specific) |
| 7 | `tcatbal.txt` | TransactionCategoryBalance | `CVTRA01Y.cpy` (50 bytes) | `TCATBALF.VSAM.KSDS` | Transaction category balance accumulators |
| 8 | `trancatg.txt` | TransactionCategory | `CVTRA04Y.cpy` (60 bytes) | `TRANCATG.VSAM.KSDS` | Transaction category definitions (18 categories across 7 types) |
| 9 | `trantype.txt` | TransactionType | `CVTRA03Y.cpy` (60 bytes) | `TRANTYPE.VSAM.KSDS` | Transaction type reference data (7 types: Purchase, Payment, Credit, Authorization, Refund, Reversal, Adjustment) |

### Data Loading Process

All 9 fixture files are loaded via the Flyway migration `V3__seed_data.sql`:

```
Application Startup
  │
  ▼
Flyway Auto-Migration
  │
  ├─ V1__create_schema.sql  → Creates all 11 tables
  ├─ V2__create_indexes.sql → Creates primary + alternate indexes
  └─ V3__seed_data.sql      → INSERT statements derived from ASCII fixture data
```

### Verification Commands

```bash
# Verify Flyway migration success
curl -s http://localhost:8080/actuator/flyway | python3 -m json.tool

# Verify record counts per table
psql -h localhost -U carddemo -d carddemo <<EOF
SELECT 'accounts' AS entity, COUNT(*) AS count FROM accounts
UNION ALL SELECT 'cards', COUNT(*) FROM cards
UNION ALL SELECT 'customers', COUNT(*) FROM customers
UNION ALL SELECT 'card_cross_references', COUNT(*) FROM card_cross_references
UNION ALL SELECT 'daily_transactions', COUNT(*) FROM daily_transactions
UNION ALL SELECT 'disclosure_groups', COUNT(*) FROM disclosure_groups
UNION ALL SELECT 'transaction_category_balances', COUNT(*) FROM transaction_category_balances
UNION ALL SELECT 'transaction_categories', COUNT(*) FROM transaction_categories
UNION ALL SELECT 'transaction_types', COUNT(*) FROM transaction_types
ORDER BY entity;
EOF
```

### Evidence Template — Per-File Processing Report

| # | File | Target Table | Record Count | Load Status | Flyway Migration | Processing Status | Notes |
|---|---|---|---|---|---|---|---|
| 1 | `acctdata.txt` | `accounts` | _PENDING_ | _PENDING_ | V3__seed_data.sql | _PENDING_ | Account master with BigDecimal balances |
| 2 | `carddata.txt` | `cards` | _PENDING_ | _PENDING_ | V3__seed_data.sql | _PENDING_ | FK to accounts table |
| 3 | `custdata.txt` | `customers` | _PENDING_ | _PENDING_ | V3__seed_data.sql | _PENDING_ | 500-byte demographic records |
| 4 | `cardxref.txt` | `card_cross_references` | _PENDING_ | _PENDING_ | V3__seed_data.sql | _PENDING_ | Card-to-account lookup index |
| 5 | `dailytran.txt` | `daily_transactions` | _PENDING_ | _PENDING_ | V3__seed_data.sql | _PENDING_ | Batch staging — 300 records |
| 6 | `discgrp.txt` | `disclosure_groups` | _PENDING_ | _PENDING_ | V3__seed_data.sql | _PENDING_ | Interest rates: DEFAULT, ZEROAPR, account-specific |
| 7 | `tcatbal.txt` | `transaction_category_balances` | _PENDING_ | _PENDING_ | V3__seed_data.sql | _PENDING_ | Composite key: acctId+typeCode+catCode |
| 8 | `trancatg.txt` | `transaction_categories` | _PENDING_ | _PENDING_ | V3__seed_data.sql | _PENDING_ | 18 categories across 7 types |
| 9 | `trantype.txt` | `transaction_types` | _PENDING_ | _PENDING_ | V3__seed_data.sql | _PENDING_ | 7 transaction types |

### Data Integrity Checks

After loading, the following integrity checks confirm correct data migration:

| Check | SQL Query | Expected Result | Actual Result | Status |
|---|---|---|---|---|
| All accounts have positive credit limits | `SELECT COUNT(*) FROM accounts WHERE credit_limit <= 0` | 0 | _PENDING_ | _PENDING_ |
| All cards reference valid accounts | `SELECT COUNT(*) FROM cards c LEFT JOIN accounts a ON c.acct_id = a.acct_id WHERE a.acct_id IS NULL` | 0 | _PENDING_ | _PENDING_ |
| All XREF entries reference valid cards | `SELECT COUNT(*) FROM card_cross_references x LEFT JOIN cards c ON x.card_num = c.card_num WHERE c.card_num IS NULL` | 0 | _PENDING_ | _PENDING_ |
| Disclosure groups include DEFAULT | `SELECT COUNT(*) FROM disclosure_groups WHERE dis_group_id = 'DEFAULT'` | ≥1 | _PENDING_ | _PENDING_ |
| Transaction types match 7 expected | `SELECT COUNT(DISTINCT type_code) FROM transaction_types` | 7 | _PENDING_ | _PENDING_ |
| Transaction categories total 18 | `SELECT COUNT(*) FROM transaction_categories` | 18 | _PENDING_ | _PENDING_ |

---

## Gate 5 — API/Interface Contract Verification

### Objective

Verify that all external interface contracts — file formats, message schemas, S3 object layouts, and REST API endpoints — are preserved exactly from the COBOL source system. This gate ensures that any upstream or downstream system integration remains functionally identical after migration.

### Contract Categories

#### 5.1 File Format Contracts

The `DailyTransactionReader` must parse fixed-width records matching the COBOL `FD DALYTRAN-FILE` file descriptor (350 bytes per record).

| Field | COBOL PIC | Offset | Length | Java Type | Parser |
|---|---|---|---|---|---|
| Transaction ID | `X(16)` | 0 | 16 | `String` | `substring(0, 16)` |
| Transaction Type Code | `X(02)` | 16 | 2 | `String` | `substring(16, 18)` |
| Transaction Category Code | `9(04)` | 18 | 4 | `int` | `Integer.parseInt(substring(18, 22))` |
| Transaction Source | `X(10)` | 22 | 10 | `String` | `substring(22, 32).trim()` |
| Transaction Description | `X(100)` | 32 | 100 | `String` | `substring(32, 132).trim()` |
| Transaction Amount | `S9(07)V99` | 132 | 10 | `BigDecimal` | Signed overpunch decode → `BigDecimal` with scale 2 |
| Merchant ID | `9(09)` | 142 | 9 | `String` | `substring(142, 151)` |
| Merchant Name | `X(50)` | 151 | 50 | `String` | `substring(151, 201).trim()` |
| Merchant City | `X(50)` | 201 | 50 | `String` | `substring(201, 251).trim()` |
| Merchant ZIP | `X(10)` | 251 | 10 | `String` | `substring(251, 261).trim()` |
| Card Number | `X(16)` | 261 | 16 | `String` | `substring(261, 277)` |
| Origination Timestamp | `X(26)` | 277 | 26 | `LocalDateTime` | `DateTimeFormatter` parse |
| (Filler) | `X(47)` | 303 | 47 | — | Ignored |

**Verification:** Integration test `DailyTransactionReaderIT` parses all 300 records from `dailytran.txt` and asserts that each field is correctly extracted.

#### 5.2 SQS Message Schema

The `ReportSubmissionService` publishes report requests to the SQS FIFO queue `carddemo-report-jobs.fifo`, replacing the COBOL `EXEC CICS WRITEQ TD QUEUE('JOBS')` in `CORPT00C.cbl`.

**Message Schema:**

```json
{
  "messageGroupId": "report-submissions",
  "messageDeduplicationId": "<UUID>",
  "body": {
    "reportType": "MONTHLY|YEARLY|CUSTOM",
    "startDate": "YYYY-MM-DD",
    "endDate": "YYYY-MM-DD",
    "accountId": "NNNNNNNNNNN",
    "requestedBy": "<userId>",
    "requestTimestamp": "YYYY-MM-DDTHH:MM:SS.SSSSSSZ",
    "correlationId": "<UUID>"
  }
}
```

**Verification:** Integration test `ReportSubmissionServiceIT` publishes a message via the service and reads it from the SQS queue using the AWS SDK, asserting schema compliance.

#### 5.3 S3 Object Formats

| Object Type | S3 Bucket | Key Pattern | Format | Source Equivalent |
|---|---|---|---|---|
| Batch input (daily transactions) | `carddemo-batch-input` | `daily/dailytran.txt` | Fixed-width 350 bytes/record | `DALYTRAN.PS` sequential file |
| Rejection file | `carddemo-batch-output` | `rejections/dailyrejs-YYYYMMDD-HHMMSS.txt` | 350-byte record + 80-byte validation trailer | `DALYREJS` GDG generation (+1) |
| Statement file (text) | `carddemo-statements` | `statements/ACCT-NNNNNNNNNNN-YYYYMM.txt` | Plain text formatted statement | Statement output file |
| Statement file (HTML) | `carddemo-statements` | `statements/ACCT-NNNNNNNNNNN-YYYYMM.html` | HTML formatted statement | N/A (enhancement) |
| Transaction report | `carddemo-batch-output` | `reports/tranrept-YYYYMMDD-HHMMSS.txt` | Formatted report with totals | Transaction report output |

**Verification:** Integration tests verify object existence, content format, and key patterns after batch job execution against LocalStack S3.

#### 5.4 REST API Contracts

All REST endpoints are documented in [`docs/api-contracts.md`](api-contracts.md). The following summary shows endpoint-to-COBOL mapping:

| Endpoint | Method | COBOL Source | BMS Map | Test Class |
|---|---|---|---|---|
| `/api/auth/signin` | POST | `COSGN00C.cbl` | `COSGN00.bms` | `AuthControllerIT` |
| `/api/accounts/{id}` | GET | `COACTVWC.cbl` | `COACTVW.bms` | `AccountControllerIT` |
| `/api/accounts/{id}` | PUT | `COACTUPC.cbl` | `COACTUP.bms` | `AccountControllerIT` |
| `/api/cards` | GET | `COCRDLIC.cbl` | `COCRDLI.bms` | `CardControllerIT` |
| `/api/cards/{cardNum}` | GET | `COCRDSLC.cbl` | `COCRDSL.bms` | `CardControllerIT` |
| `/api/cards/{cardNum}` | PUT | `COCRDUPC.cbl` | `COCRDUP.bms` | `CardControllerIT` |
| `/api/transactions` | GET | `COTRN00C.cbl` | `COTRN00.bms` | `TransactionControllerIT` |
| `/api/transactions/{id}` | GET | `COTRN01C.cbl` | `COTRN01.bms` | `TransactionControllerIT` |
| `/api/transactions` | POST | `COTRN02C.cbl` | `COTRN02.bms` | `TransactionControllerIT` |
| `/api/billing/pay` | POST | `COBIL00C.cbl` | `COBIL00.bms` | `BillingControllerIT` |
| `/api/reports/submit` | POST | `CORPT00C.cbl` | `CORPT00.bms` | `ReportControllerIT` |
| `/api/admin/users` | GET | `COUSR00C.cbl` | `COUSR00.bms` | `UserAdminControllerIT` |
| `/api/admin/users` | POST | `COUSR01C.cbl` | `COUSR01.bms` | `UserAdminControllerIT` |
| `/api/admin/users/{id}` | PUT | `COUSR02C.cbl` | `COUSR02.bms` | `UserAdminControllerIT` |
| `/api/admin/users/{id}` | DELETE | `COUSR03C.cbl` | `COUSR03.bms` | `UserAdminControllerIT` |
| `/api/menu/main` | GET | `COMEN01C.cbl` | `COMEN01.bms` | `MenuControllerIT` |
| `/api/menu/admin` | GET | `COADM01C.cbl` | `COADM01.bms` | `MenuControllerIT` |

### Evidence Template — Per-Interface Contract Verification

| Interface Type | Contract | Test Class | Test Count | Passed | Failed | Status |
|---|---|---|---|---|---|---|
| File format (dailytran.txt) | 350-byte fixed-width record parsing | `DailyTransactionReaderIT` | _PENDING_ | _PENDING_ | _PENDING_ | _PENDING_ |
| SQS message (report jobs) | JSON schema with FIFO ordering | `ReportSubmissionServiceIT` | _PENDING_ | _PENDING_ | _PENDING_ | _PENDING_ |
| S3 object (rejection file) | 430-byte records (350 + 80 trailer) | `RejectWriterIT` | _PENDING_ | _PENDING_ | _PENDING_ | _PENDING_ |
| S3 object (statement) | Text + HTML formatted output | `StatementWriterIT` | _PENDING_ | _PENDING_ | _PENDING_ | _PENDING_ |
| S3 object (report) | Formatted report with totals | `TransactionReportJobIT` | _PENDING_ | _PENDING_ | _PENDING_ | _PENDING_ |
| REST API (auth) | POST /api/auth/signin | `AuthControllerIT` | _PENDING_ | _PENDING_ | _PENDING_ | _PENDING_ |
| REST API (accounts) | GET/PUT /api/accounts/{id} | `AccountControllerIT` | _PENDING_ | _PENDING_ | _PENDING_ | _PENDING_ |
| REST API (cards) | GET/PUT /api/cards/* | `CardControllerIT` | _PENDING_ | _PENDING_ | _PENDING_ | _PENDING_ |
| REST API (transactions) | GET/POST /api/transactions/* | `TransactionControllerIT` | _PENDING_ | _PENDING_ | _PENDING_ | _PENDING_ |
| REST API (billing) | POST /api/billing/pay | `BillingControllerIT` | _PENDING_ | _PENDING_ | _PENDING_ | _PENDING_ |
| REST API (reports) | POST /api/reports/submit | `ReportControllerIT` | _PENDING_ | _PENDING_ | _PENDING_ | _PENDING_ |
| REST API (admin/users) | CRUD /api/admin/users/* | `UserAdminControllerIT` | _PENDING_ | _PENDING_ | _PENDING_ | _PENDING_ |
| REST API (menu) | GET /api/menu/{type} | `MenuControllerIT` | _PENDING_ | _PENDING_ | _PENDING_ | _PENDING_ |

---

## Gate 6 — Unsafe/Low-Level Code Audit

### Objective

Audit the entire Java codebase for unsafe or low-level code patterns that could introduce security vulnerabilities, maintainability risks, or runtime failures. Each category has an expected count threshold; any count exceeding the threshold requires per-site justification.

### Audit Categories

| Category | Search Pattern | Threshold | Risk Level | Rationale for Threshold |
|---|---|---|---|---|
| Raw SQL string concatenation | `" + "` within SQL strings, `String.format` in `@Query` | **0** | Critical | All database access via Spring Data JPA `@Query` with parameterized bindings or derived query methods. No raw JDBC `Statement` usage. |
| `Runtime.exec()` calls | `Runtime.getRuntime().exec`, `ProcessBuilder` | **0** | Critical | No subprocess execution required. All external integrations via Spring-managed clients (S3, SQS). |
| Reflection usage | `Class.forName`, `Method.invoke`, `Field.setAccessible` | **0** | High | Spring Dependency Injection handles all instantiation. No custom reflection needed. |
| Unchecked casts | `@SuppressWarnings("unchecked")` | **≤5** | Medium | Spring Batch `ItemProcessor<I,O>` generics require unchecked casts due to type erasure in composite processor chains. |
| Suppressed warnings | `@SuppressWarnings` (any variant) | **≤3** | Medium | JPA metamodel-related suppressions for generated code only. |
| Direct `System.out/err` usage | `System.out.print`, `System.err.print` | **0** | Low | All output via SLF4J structured logging. No console output. |
| Thread creation | `new Thread(`, `Executors.newFixedThreadPool` | **0** | Medium | Spring-managed thread pools only. Virtual threads via Spring configuration. |
| `sun.*` / internal API usage | `import sun.`, `import com.sun.` (non-JMX) | **0** | High | Only standard Java SE and Jakarta EE APIs used. |

### Verification Commands

```bash
# Search for raw SQL concatenation
grep -rn '".*SELECT.*" +\|".*INSERT.*" +\|".*UPDATE.*" +\|".*DELETE.*" +' \
    src/main/java/ --include="*.java" | grep -v "@Query"

# Search for Runtime.exec
grep -rn 'Runtime.getRuntime().exec\|ProcessBuilder' \
    src/main/java/ --include="*.java"

# Search for reflection
grep -rn 'Class.forName\|\.invoke(\|setAccessible' \
    src/main/java/ --include="*.java"

# Count @SuppressWarnings
grep -rn '@SuppressWarnings' src/main/java/ --include="*.java"

# Search for System.out/err
grep -rn 'System\.out\.\|System\.err\.' src/main/java/ --include="*.java"

# Search for direct thread creation
grep -rn 'new Thread(\|Executors\.' src/main/java/ --include="*.java"

# Search for internal API usage
grep -rn 'import sun\.\|import com\.sun\.' src/main/java/ --include="*.java" \
    | grep -v 'jmxremote\|management'
```

### Evidence Template — Audit Report

| Category | Threshold | Actual Count | Sites | Status |
|---|---|---|---|---|
| Raw SQL string concatenation | 0 | _PENDING_ | _PENDING_ | _PENDING_ |
| `Runtime.exec()` calls | 0 | _PENDING_ | _PENDING_ | _PENDING_ |
| Reflection usage | 0 | _PENDING_ | _PENDING_ | _PENDING_ |
| Unchecked casts | ≤5 | _PENDING_ | _PENDING_ | _PENDING_ |
| Suppressed warnings | ≤3 | _PENDING_ | _PENDING_ | _PENDING_ |
| Direct `System.out/err` | 0 | _PENDING_ | _PENDING_ | _PENDING_ |
| Thread creation | 0 | _PENDING_ | _PENDING_ | _PENDING_ |
| `sun.*` / internal API | 0 | _PENDING_ | _PENDING_ | _PENDING_ |

### Per-Site Justification (for any count > 0)

| File | Line | Category | Code Snippet | Justification | Remediation Plan |
|---|---|---|---|---|---|
| _PENDING_ | _PENDING_ | _PENDING_ | _PENDING_ | _PENDING_ | _PENDING_ |

---

## Gate 7 — Scope Matching (Extended)

### Objective

Justify and verify that the migration scope covers every subsystem, data flow, and integration point present in the source COBOL application. This gate confirms that no COBOL functionality was omitted, misinterpreted, or silently dropped during the Java migration.

### Justification for Extended Scope

The CardDemo application qualifies for extended scope matching due to the following complexity factors:

| Complexity Factor | Description | Quantified Scope |
|---|---|---|
| **Multi-subsystem batch processing** | 5-stage sequential pipeline (POSTTRAN → INTCALC → COMBTRAN → CREASTMT/TRANREPT) with inter-stage dependencies and condition code propagation | 5 batch jobs, 5 processors, 5 readers, 3 writers |
| **File I/O diversity** | 9 ASCII fixture files × 3 format types (fixed-width VSAM records, sequential PS, cross-reference indices) | 9 entity types, 11 tables, 3 file parsers |
| **Inter-program calls** | COBOL `CALL` (CSUTLDTC, CBSTM03B), `XCTL` (COMEN01C, COADM01C), and `RETURN TRANSID COMMAREA` | 18 online services, 10 batch components |
| **JCL orchestration** | 29 JCL jobs with DD statement routing, COND code logic, and PARM parameter passing | 29 → Spring Batch + Flyway migrations |
| **AWS service integration** | S3 (file staging), SQS FIFO (message queue), SNS (notifications) replacing VSAM PS, CICS TDQ, and GDG | 3 AWS services via LocalStack |
| **Data precision** | COMP-3 packed decimal fields requiring `BigDecimal` — zero floating-point | 15+ financial fields across 11 entities |

### Scope Evidence Matrix

| Subsystem | Source Construct | Count (Source) | Target Construct | Count (Target) | Coverage |
|---|---|---|---|---|---|
| **Online Programs** | COBOL CICS programs (`CO*.cbl`) | 18 | Spring service classes | 18 | 100% |
| **Batch Programs** | COBOL batch programs (`CB*.cbl`) | 10 | Spring Batch jobs + processors | 10 | 100% |
| **Shared Copybooks** | COBOL record layouts (`CV*.cpy`, `CS*.cpy`) | 28 | Java entities, DTOs, utilities | 28 | 100% |
| **BMS Mapsets** | BMS screen definitions (`.bms`) | 17 | REST API endpoints | 17 | 100% |
| **Symbolic Maps** | BMS copybooks (`cpy-bms/*.CPY`) | 17 | Request/Response DTOs | 17 | 100% |
| **JCL Provisioning** | VSAM DEFINE CLUSTER jobs | 16 | Flyway migration DDL | 11 tables | 100% |
| **JCL Business Batch** | Batch pipeline jobs | 9 | Spring Batch job definitions | 6 (merged) | 100% |
| **JCL Utility** | READ* diagnostic jobs | 4 | Health/diagnostic endpoints | 4 | 100% |
| **VSAM KSDS Datasets** | Keyed-sequential clusters | 10 | PostgreSQL tables with PKs | 10 | 100% |
| **VSAM AIX/PATH** | Alternate indexes | 2 | JPA secondary queries | 2 | 100% |
| **VSAM PS (Sequential)** | Sequential staging file | 1 | S3 object + staging table | 1 | 100% |
| **GDG Generations** | GDG bases (DEFGDGB) | 1 | S3 versioned objects | 1 | 100% |
| **CICS TDQ** | Transient data queue (JOBS) | 1 | SQS FIFO queue | 1 | 100% |
| **ASCII Fixture Files** | Test/seed data files | 9 | SQL seed scripts (V3) | 9 | 100% |
| **EBCDIC Data Files** | Binary data (reference) | 13 | N/A (reference only) | — | N/A |
| **Features (F-001–F-022)** | Documented feature catalog | 22 | REST endpoints + batch | 22 | 100% |

### COBOL Program-to-Java Mapping Coverage

| COBOL Program | Lines | Java Target Class(es) | Mapped | Notes |
|---|---|---|---|---|
| `COSGN00C.cbl` | 260 | `AuthenticationService`, `AuthController` | ✓ | BCrypt replaces plaintext compare |
| `COMEN01C.cbl` | 282 | `MainMenuService`, `MenuController` | ✓ | 10-option routing metadata |
| `COADM01C.cbl` | 268 | `AdminMenuService`, `MenuController` | ✓ | 4-option routing metadata |
| `COACTVWC.cbl` | 941 | `AccountViewService`, `AccountController` | ✓ | Multi-dataset read |
| `COACTUPC.cbl` | 4,236 | `AccountUpdateService`, `AccountController` | ✓ | @Transactional + @Version |
| `COCRDLIC.cbl` | 1,459 | `CardListService`, `CardController` | ✓ | Paginated browse |
| `COCRDSLC.cbl` | 887 | `CardDetailService`, `CardController` | ✓ | Single keyed read |
| `COCRDUPC.cbl` | 1,560 | `CardUpdateService`, `CardController` | ✓ | Optimistic concurrency |
| `COTRN00C.cbl` | 699 | `TransactionListService`, `TransactionController` | ✓ | Paginated browse |
| `COTRN01C.cbl` | 330 | `TransactionDetailService`, `TransactionController` | ✓ | Single keyed read |
| `COTRN02C.cbl` | 783 | `TransactionAddService`, `TransactionController` | ✓ | Auto-ID + confirmation |
| `COBIL00C.cbl` | 572 | `BillPaymentService`, `BillingController` | ✓ | Balance update + txn create |
| `CORPT00C.cbl` | 649 | `ReportSubmissionService`, `ReportController` | ✓ | SQS replaces TDQ |
| `COUSR00C.cbl` | 695 | `UserListService`, `UserAdminController` | ✓ | Paginated browse |
| `COUSR01C.cbl` | 299 | `UserAddService`, `UserAdminController` | ✓ | BCrypt password hash |
| `COUSR02C.cbl` | 414 | `UserUpdateService`, `UserAdminController` | ✓ | Record modification |
| `COUSR03C.cbl` | 359 | `UserDeleteService`, `UserAdminController` | ✓ | Confirmation delete |
| `CSUTLDTC.cbl` | 157 | `DateValidationService` | ✓ | java.time replaces CEEDAYS |
| `CBACT01C.cbl` | 193 | `AccountFileReader` | ✓ | Diagnostic reader |
| `CBACT02C.cbl` | 178 | `CardFileReader` | ✓ | Diagnostic reader |
| `CBACT03C.cbl` | 178 | `CrossReferenceFileReader` | ✓ | Diagnostic reader |
| `CBACT04C.cbl` | 652 | `InterestCalculationProcessor` | ✓ | Rate × Balance / 1200 |
| `CBCUS01C.cbl` | 178 | `CustomerFileReader` | ✓ | Diagnostic reader |
| `CBSTM03A.CBL` | 924 | `StatementProcessor`, `StatementWriter` | ✓ | Text + HTML generation |
| `CBSTM03B.CBL` | 230 | `StatementProcessor` (integrated) | ✓ | File-service subroutine merged |
| `CBTRN01C.cbl` | 491 | `DailyTransactionReader` | ✓ | Sequential S3 reader |
| `CBTRN02C.cbl` | 731 | `TransactionPostingProcessor`, `TransactionWriter`, `RejectWriter` | ✓ | 4-stage validation cascade |
| `CBTRN03C.cbl` | 649 | `TransactionReportProcessor` | ✓ | Date filtering + enrichment |

**Total: 28/28 COBOL programs mapped (100%)**

> **Full paragraph-level traceability** is maintained in [`TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md) with bidirectional mapping for every COBOL paragraph.

---

## Gate 8 — Integration Sign-Off Checklist

### Objective

Provide a consolidated sign-off checklist that references evidence from all other gates and additional quality metrics. This gate serves as the final approval checkpoint before the migration is considered complete.

### Prerequisites

All gates 1–7 must be completed before Gate 8 sign-off.

### Consolidated Sign-Off Table

| # | Gate / Check | Description | Criterion | Status | Evidence Location | Sign-Off Date |
|---|---|---|---|---|---|---|
| 1 | **Gate 1** | End-to-end boundary verification | All 300 records processed; output matches expected | _PENDING_ | [Gate 1 Evidence](#gate-1--end-to-end-boundary-verification) | _PENDING_ |
| 2 | **Gate 2** | Zero-warning build | `mvn clean verify` with 0 warnings, 0 errors | _PENDING_ | [Gate 2 Evidence](#gate-2--zero-warning-build) | _PENDING_ |
| 3 | **Gate 3** | Performance baseline | Throughput ≥100 records/sec; job completes successfully | _PENDING_ | [Gate 3 Evidence](#gate-3--performance-baseline) | _PENDING_ |
| 4 | **Gate 4** | Named real-world validation | All 9 ASCII files loaded and processed | _PENDING_ | [Gate 4 Evidence](#gate-4--named-real-world-validation-artifacts) | _PENDING_ |
| 5 | **Gate 5** | API/Interface contracts | All file, SQS, S3, and REST contracts verified | _PENDING_ | [Gate 5 Evidence](#gate-5--apiinterface-contract-verification) | _PENDING_ |
| 6 | **Gate 6** | Unsafe code audit | All categories within thresholds | _PENDING_ | [Gate 6 Evidence](#gate-6--unsafelow-level-code-audit) | _PENDING_ |
| 7 | **Gate 7** | Scope matching | 28/28 programs, 22/22 features at 100% | _PENDING_ | [Gate 7 Evidence](#gate-7--scope-matching-extended) | _PENDING_ |
| 8 | **Line Coverage** | JaCoCo code coverage | ≥80% line coverage (unit + integration) | _PENDING_ | JaCoCo HTML report: `target/site/jacoco/index.html` | _PENDING_ |
| 9 | **OWASP Scan** | Dependency vulnerability check | 0 critical/high CVEs | _PENDING_ | OWASP report: `target/dependency-check-report.html` | _PENDING_ |
| 10 | **Traceability** | Bidirectional mapping matrix | 100% COBOL paragraph coverage | _PENDING_ | [`TRACEABILITY_MATRIX.md`](../TRACEABILITY_MATRIX.md) | _PENDING_ |

### Line Coverage Verification

```bash
# Run full test suite with JaCoCo coverage
mvn clean verify -B

# Check JaCoCo report
cat target/site/jacoco/index.html | grep -o 'Total[^<]*'

# Or use the Maven enforcer rule configured in pom.xml:
# <rule>
#   <element>BUNDLE</element>
#   <limits>
#     <limit>
#       <counter>LINE</counter>
#       <value>COVEREDRATIO</value>
#       <minimum>0.80</minimum>
#     </limit>
#   </limits>
# </rule>
```

| Package | Line Coverage | Branch Coverage | Status |
|---|---|---|---|
| `com.cardemo.service` | _PENDING_ | _PENDING_ | _PENDING_ |
| `com.cardemo.batch` | _PENDING_ | _PENDING_ | _PENDING_ |
| `com.cardemo.controller` | _PENDING_ | _PENDING_ | _PENDING_ |
| `com.cardemo.model` | _PENDING_ | _PENDING_ | _PENDING_ |
| `com.cardemo.repository` | _PENDING_ | _PENDING_ | _PENDING_ |
| `com.cardemo.config` | _PENDING_ | _PENDING_ | _PENDING_ |
| `com.cardemo.exception` | _PENDING_ | _PENDING_ | _PENDING_ |
| **TOTAL** | **_PENDING_** | **_PENDING_** | **_PENDING_** |

### OWASP Dependency Check

```bash
# Run OWASP dependency-check
mvn org.owasp:dependency-check-maven:check -B

# Review results
cat target/dependency-check-report.html | grep -o 'vulnerabilities found[^<]*'
```

| Severity | Count | Threshold | Status |
|---|---|---|---|
| Critical | _PENDING_ | 0 | _PENDING_ |
| High | _PENDING_ | 0 | _PENDING_ |
| Medium | _PENDING_ | Informational | _PENDING_ |
| Low | _PENDING_ | Informational | _PENDING_ |

### Traceability Matrix Verification

```bash
# Count mapped COBOL paragraphs in the traceability matrix
grep -c "| \`" TRACEABILITY_MATRIX.md

# Verify all 28 COBOL programs are represented
for prog in COSGN00C COMEN01C COADM01C COACTVWC COACTUPC COCRDLIC COCRDSLC \
    COCRDUPC COTRN00C COTRN01C COTRN02C COBIL00C CORPT00C COUSR00C COUSR01C \
    COUSR02C COUSR03C CSUTLDTC CBACT01C CBACT02C CBACT03C CBACT04C CBCUS01C \
    CBSTM03A CBSTM03B CBTRN01C CBTRN02C CBTRN03C; do
    count=$(grep -c "$prog" TRACEABILITY_MATRIX.md)
    echo "$prog: $count entries"
done
```

### Final Sign-Off

| Role | Name | Date | Signature |
|---|---|---|---|
| Migration Architect | _PENDING_ | _PENDING_ | _PENDING_ |
| Technical Lead | _PENDING_ | _PENDING_ | _PENDING_ |
| QA Lead | _PENDING_ | _PENDING_ | _PENDING_ |

---

## Appendix A — Reject Code Reference

Complete reference of all validation reject codes from `CBTRN02C.cbl`:

| Code | COBOL Paragraph | Description | Trigger Condition | Java Exception |
|---|---|---|---|---|
| 100 | `1500-A-LOOKUP-XREF` | Invalid card number found | Card number not in CARDXREF | `RecordNotFoundException` |
| 101 | `1500-B-LOOKUP-ACCT` | Account record not found | Account ID from XREF not in ACCTDAT | `RecordNotFoundException` |
| 102 | `1500-B-LOOKUP-ACCT` | Overlimit transaction | `cycleCredit - cycleDebit + tranAmt > creditLimit` | `CreditLimitExceededException` |
| 103 | `1500-B-LOOKUP-ACCT` | Transaction after account expiration | `acctExpirationDate < tranOriginDate` | `ExpiredCardException` |
| 104–108 | Reserved | Future validation stages | Comment in source: `ADD MORE VALIDATIONS HERE` | `ValidationException` |
| 109 | `2800-UPDATE-ACCOUNT-REC` | Account update failed | REWRITE INVALID KEY on account file | `RecordNotFoundException` |

## Appendix B — Data File Format Quick Reference

| File | Record Length | Key Field(s) | Key Position | Separator |
|---|---|---|---|---|
| `acctdata.txt` | 300 bytes | Account ID (11 digits) | Pos 1–11 | Fixed-width, no delimiter |
| `carddata.txt` | 150 bytes | Card Number (16 digits) | Pos 1–16 | Fixed-width, no delimiter |
| `custdata.txt` | 500 bytes | Customer ID (9 digits) | Pos 1–9 | Fixed-width, no delimiter |
| `cardxref.txt` | 50 bytes | Card Number (16 digits) | Pos 1–16 | Fixed-width, no delimiter |
| `dailytran.txt` | 350 bytes | Transaction ID (16 digits) | Pos 1–16 | Fixed-width, no delimiter |
| `discgrp.txt` | 50 bytes | Group ID (10) + Type (2) + Cat (4) | Pos 1–16 | Fixed-width, no delimiter |
| `tcatbal.txt` | 50 bytes | Acct ID (11) + Type (2) + Cat (4) | Pos 1–17 | Fixed-width, no delimiter |
| `trancatg.txt` | 60 bytes | Type Code (2) + Cat Code (4) | Pos 1–6 | Fixed-width, no delimiter |
| `trantype.txt` | 60 bytes | Type Code (2) | Pos 1–2 | Fixed-width, no delimiter |

## Appendix C — Condition Code Mapping

| COBOL Return Code | Meaning | Spring Batch ExitStatus | Java Equivalent |
|---|---|---|---|
| 0 | Success — all records processed | `ExitStatus.COMPLETED` | Job completes normally |
| 4 | Partial success — some rejections | `ExitStatus("COMPLETED_WITH_REJECTIONS")` | Custom exit status, downstream stages may proceed |
| 8 | Warning — non-fatal errors detected | `ExitStatus("COMPLETED_WITH_WARNINGS")` | Logged but not fatal |
| 12 | Error — file open/read/write failure | `ExitStatus.FAILED` | Exception thrown, step fails |
| 16 | EOF — expected end of file | N/A (normal termination) | Reader returns `null` |
| 999 | Abend — fatal program abort | `ExitStatus.FAILED` + exception | `CEE3ABD` equivalent: uncaught exception |
