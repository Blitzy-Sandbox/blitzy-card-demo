# Test Strategy

This document is the authoritative reference for the CardDemo COBOL-to-Java migration
test suite. It defines the four-layer test pyramid, naming conventions, coverage
targets, framework constraints, the Require Test Coverage rule, financial-precision
rules, fixture policy, and the canonical Maven invocations.

It is the companion to [`baseline-parity.md`](baseline-parity.md), which documents the
operating procedure for capturing and refreshing COBOL reference outputs used by the
baseline parity layer.

> **Source of truth.** Every rule below traces back to the Agent Action Plan (AAP)
> sections §0.4, §0.5, §0.6, §0.7, §0.8, §0.9, and §0.10. Where this document and the
> AAP disagree, the AAP wins — but the intent is for them to remain identical.

## 1. Mission Statement

The CardDemo migration test suite has one mission: prove that the migrated
Java 17 LTS / Spring Boot 3.x implementation behaves identically to the original
AWS CardDemo COBOL/JCL mainframe application along every observable boundary.
"Identical" is enforced byte-for-byte for batch-job outputs and bit-exactly for
monetary calculations. Coverage is the floor; baseline parity is the ceiling.

The mission decomposes into six concrete requirements drawn verbatim from the user
prompt and the AAP:

1. **Functional parity** — every migrated Java class must produce the same
   observable output the COBOL program produced given the same input fixture.
2. **Financial precision** — every monetary calculation uses `BigDecimal` at the
   scale derived from the COBOL `PICTURE` clause with `RoundingMode.HALF_EVEN`
   (banker's rounding). No `float` or `double` is permitted on any monetary path.
3. **Service-layer coverage floor** — JaCoCo enforces ≥80% line coverage on every
   class under `com.aws.carddemo.service.**`, gated by the Maven build under the
   `jacoco` profile.
4. **Require Test Coverage** — tests must call production service and repository
   classes directly; business and calculation logic must never be reimplemented
   inside test bodies; mocks are limited to external boundaries.
5. **Spring Batch job parity** — every JCL job mapped to a Spring Batch `Job` has
   an integration test that runs the job against fixture inputs and asserts output
   parity with the original JCL execution.
6. **Edge case coverage** — every COBOL edge case (reject codes 100–109,
   `ZEROAPR` skip, `DEFAULT` group fallback, arithmetic overflow, zero-value
   records, EOF, VSAM file-status code translation) has at least one
   `@ParameterizedTest` covering it.

## 2. The Four-Layer Test Pyramid

The test suite is organised as a four-layer pyramid. Each layer answers a distinct
correctness question, mocks a different set of boundaries, runs in a different
Spring test slice, and consumes a different volume of execution time. Together
they enforce the user-mandated coverage floor and the byte-identical baseline
parity contract.

| Layer | Purpose | Spring Test Slice | Mock Boundary | Approximate Test Count |
| --- | --- | --- | --- | --- |
| 1 — Unit tests | Verify isolated business logic in a single service, processor, validator, or mapper | None — `@ExtendWith(MockitoExtension.class)` only | All collaborators mocked; system under test is the real production class | ~729 |
| 2 — Integration tests | Verify cross-component wiring, JPA queries, Spring Batch step semantics, controller HTTP semantics | `@SpringBootTest`, `@DataJpaTest`, `@WebMvcTest`, `@SpringBatchTest` | Real Spring context + Testcontainers PostgreSQL/LocalStack; only third-party HTTP boundaries mocked | ~131 |
| 3 — Baseline parity ITs | Verify byte-identical output vs the captured COBOL baseline for every batch job | `@SpringBatchTest` + the project's `BaselineDiffUtil` | Real Spring Batch context | 5 (one per batch job) |
| 4 — End-to-end ITs | Verify multi-step user/operator journeys (online sign-on → menu → operation → logout; batch pipeline POSTTRAN → INTCALC → COMBTRAN → CREASTMT ∥ TRANREPT) | `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` + Testcontainers | Real Spring context end-to-end | ~28 |

Layer 1 (unit tests) sits at the wide base of the pyramid. Layer 4 (end-to-end ITs)
sits at the narrow top. The pyramid shape is intentional: every failure mode that
unit tests can catch should be caught at the unit layer, where iteration is
sub-second; only behaviours that genuinely require multiple components in
coordination escalate to the upper layers.

### 2.1 Layer 1 — Unit Tests

Unit tests focus on **one production class at a time**. The class under test is
instantiated directly (`new ServiceImpl(...)` or `@InjectMocks`); all collaborators
(repositories, file readers/writers, AWS SDK clients, `Clock`, external HTTP
clients) are replaced with Mockito `@Mock` instances. Mockito strictness is
`STRICT_STUBS` (the JUnit Jupiter default with `MockitoExtension`) so unused stubs
raise `UnnecessaryStubbingException` and fail the build.

`@ParameterizedTest` annotated methods, driven by `@CsvFileSource` files under
`src/test/resources/fixtures/edge/`, cover variant inputs (reject codes, overflow
boundaries, HALF_EVEN/HALF_UP discrimination, leap-year edge cases, etc.) without
duplicating the test body for each row.

### 2.2 Layer 2 — Integration Tests

Integration tests verify cross-component behaviour that cannot be exercised in
isolation: JPA query correctness against PostgreSQL, Spring Batch step semantics,
HTTP routing through `MockMvc` slice tests, and `@Transactional` rollback paths
that require a real `EntityManager` and a real transaction manager.

Repository ITs extend `AbstractRepositoryIT`, which wires `@DataJpaTest` against
Testcontainers PostgreSQL 16 (one container per IT class). Batch ITs extend
`AbstractBatchIT`, which adds `@SpringBatchTest` boilerplate plus
`JobLauncherTestUtils` and `JobRepositoryTestUtils` injection. Controller ITs use
`@WebMvcTest(controllers = X.class)` with `@MockBean`-replaced services to keep
the slice tight.

### 2.3 Layer 3 — Baseline Parity ITs

The middle-narrow layer of the pyramid. Each of the 5 Spring Batch jobs has
exactly one paired `[JobName]BaselineParityIT.java` class that:

1. Stages canonical input fixtures from `src/test/resources/baseline/input/`.
2. Launches the Spring Batch job via `jobLauncherTestUtils.launchJob(...)`.
3. Asserts `BatchStatus.COMPLETED`.
4. Calls `BaselineDiffUtil.assertByteEqual(actualPath, expectedPath)` where
   `expectedPath` points at the captured COBOL reference output under
   `src/test/resources/baseline/expected/`.

Until COBOL capture occurs, expected-output files carry the literal
`# BASELINE_CAPTURE_PENDING_<NAME>` marker on the first line.
`BaselineDiffUtil` detects this marker and fails loudly with a clear "baseline
capture pending" diagnostic, preventing accidental green builds against
unpopulated baselines. The capture-and-refresh procedure is documented in
[`baseline-parity.md`](baseline-parity.md).

### 2.4 Layer 4 — End-to-End ITs

The narrow tip of the pyramid. End-to-end ITs orchestrate multiple components in
sequence to verify user journeys and batch pipelines that span more than one
controller, service, or job. Examples: sign-on → main menu → transaction
list/view/add round-trip (driven by `TestRestTemplate`), or the full batch
pipeline POSTTRAN → INTCALC → COMBTRAN → CREASTMT ∥ TRANREPT against a single
Testcontainers PostgreSQL state. The suite includes ~28 such tests; they are the
slowest layer and are intentionally kept few.

## 3. Naming and Location Conventions

Test naming and location are enforced by Maven, not by convention. The Surefire
plugin's `<includes>` block lists `**/*Test.java` exclusively; the Failsafe
plugin's `<includes>` block lists `**/*IT.java` exclusively. Mis-naming a test
class causes Maven to silently skip it — the convention is therefore part of the
build contract.

| Naming Element | Convention | Example |
| --- | --- | --- |
| Unit test class name | `[ProductionClassName]Test` | `AuthenticationServiceTest` |
| Integration test class name | `[ProductionClassName]IT` | `AccountRepositoryIT` |
| Baseline parity IT name | `[JobName]BaselineParityIT` | `TransactionPostingBaselineParityIT` |
| Test method name | `methodUnderTest_inputCondition_expectedOutcome` | `authenticate_validUserValidPassword_returnsUserSession` |
| Test class location | Mirrors production package | `src/test/java/com/aws/carddemo/service/AuthenticationServiceTest.java` for `src/main/java/com/aws/carddemo/service/AuthenticationService.java` |
| `@DisplayName` use | Only when the method name alone would obscure the scenario (rare) | `@DisplayName("merge_keyCollision_preservesPostedBeforeSystran")` |
| Fixture CSV name | `<feature>_<scenario>.csv` | `interest_zero_balance.csv`, `posting_reject_codes.csv` |
| Baseline input fixture | One-for-one (or PAN-converted) copy of `app/data/ASCII/*.txt` | `src/test/resources/baseline/input/dailytran.txt` |
| Baseline expected fixture | Captured COBOL output (or `BASELINE_CAPTURE_PENDING_<NAME>` placeholder) | `src/test/resources/baseline/expected/posted.txt` |

## 4. Coverage Targets

The user-mandated floor is **≥80% line coverage on every class in the service
layer**. This document extends that floor with per-layer targets enforced
declaratively by the JaCoCo Maven plugin's `<rule>` block. All targets are gated
under the `jacoco` profile and enforced by `mvn verify -Pjacoco`.

| Layer / Package Pattern | Line Coverage Target | Branch Coverage Target | Rationale |
| --- | --- | --- | --- |
| `com.aws.carddemo.service.**` | **≥80%** (user mandate) | ≥70% | Primary correctness surface; explicit user requirement |
| `com.aws.carddemo.batch.**` | ≥85% | ≥75% | Financial-calculation processors; highest correctness burden |
| `com.aws.carddemo.validation.**` | ≥90% | ≥85% | Small surface, high criticality (date, lookup, format validators) |
| `com.aws.carddemo.io.**` | ≥90% | ≥85% | `FileStatusMapper` and adapter utilities — small surface, easy to fully cover |
| `com.aws.carddemo.repository.**` | ≥75% | n/a | Mostly Spring-generated; ITs target custom queries and `@Modifying` updates |
| `com.aws.carddemo.controller.**` | ≥80% | ≥70% | HTTP routing, validation, response shape |
| `com.aws.carddemo.dto.**` | not enforced | n/a | Data carriers (records or POJOs); excluded from coverage rules |
| `com.aws.carddemo.config.**` | not enforced | n/a | Spring configuration classes exercised indirectly by ITs |

**Coverage-gap categories** that the test suite must address as production code lands:

- Every reject-code branch in `TransactionPostingProcessor` (CBTRN02C → reject
  codes 100–103, plus any 104–109 introduced during migration). One
  `@ParameterizedTest` row per code, driven by `posting_reject_codes.csv`.
- Every interest-calculation edge case in `InterestCalculationProcessor`
  (CBACT04C → zero balance, `ZEROAPR` skip, `DEFAULT` group fallback,
  HALF_EVEN-vs-HALF_UP `.5` boundary). Driven by the four
  `interest_*` CSV fixtures.
- Both optimistic-locking paths (`@Version` increment vs
  `OptimisticLockingFailureException`) in `COACTUPC`, `COCRDUPC`, `COUSR02C`
  migrations.
- Both commit and rollback branches in every `@Transactional` method that
  replaces a COBOL `SYNCPOINT ROLLBACK` block.
- Every documented VSAM file-status code (`00`, `02`, `10`, `22`, `23`, `35`,
  `92`, `97`) in the `FileStatusMapper`, driven by `status_code_mappings.csv`.

## 5. The Require Test Coverage Rule

The Require Test Coverage rule is the project's most consequential testing
discipline. It is non-negotiable.

### 5.1 What the Rule Says

Verbatim from the user prompt and the AAP §0.10.1:

- "Tests MUST call production service and repository classes directly."
- "Tests MUST NOT reimplement any business or calculation logic inside test bodies."
- "Mocks limited to external boundaries: file I/O, downstream service calls, database."
- "Each test method asserts a specific behavior of the production class under test."

### 5.2 What the Rule Forbids

- Re-deriving an expected value in the test body using the same arithmetic the
  production code uses. For example, in `InterestCalculationProcessorTest` the
  expected interest must be a literal `"15.00"`, never
  `balance.multiply(rate).divide(BigDecimal.valueOf(1200), 2, HALF_EVEN)`.
- Re-implementing branch logic in the test. For example, reject-code assertions
  read `assertThat(reject.getCode()).isEqualTo(102)` for a specific input row —
  never a conditional expression that re-derives the expected code from the
  input.
- Mocking internal collaborators (service helpers, private utility methods, or
  package-private inner classes). Mocks are reserved for true external
  boundaries.
- Calling private methods via reflection. The test exercises the public entry
  point of the containing class; if a paragraph maps to a private method, the
  public method must drive the same path under input that triggers it.

### 5.3 What the Rule Permits as External Boundaries

- JPA repositories (`JpaRepository<T, ID>` subclasses).
- Spring Batch `FlatFileItemReader` / `FlatFileItemWriter` and other readers /
  writers.
- AWS SDK clients (`S3Client`, `SqsClient`, `SesClient`, `LambdaClient`).
- `Clock` and `LocalDate.now()` — injected as a `Clock` bean and mocked with
  `Clock.fixed(...)` for deterministic timestamps.
- Random / sequence generators — injected behind an interface and mocked
  deterministically.
- External HTTP clients (none currently — the migration adds no outbound HTTP
  integrations beyond AWS SDK).

### 5.4 Mockito Strictness

Mockito strictness is `STRICT_STUBS` (the JUnit Jupiter default activated by
`@ExtendWith(MockitoExtension.class)`). Unused stubs raise
`UnnecessaryStubbingException` and fail the test. This catches the most common
form of Require-Test-Coverage rule violation: stubbing a collaborator and never
exercising the code path that uses the stub.

## 6. Financial Precision Rules

Every monetary value in the CardDemo migration is a `BigDecimal`. No exceptions.

| Rule | Mechanism |
| --- | --- |
| No `float` or `double` for any monetary value | Code review + grep-based CI check rejects any `@Column` of type `Double`/`Float` in `com.aws.carddemo.entity.**` |
| `RoundingMode.HALF_EVEN` (banker's rounding) on every monetary operation | Production code uses `BigDecimal.divide(..., HALF_EVEN)` and equivalent; tests assert HALF_EVEN behaviour at the `.5` boundary (e.g., `100.005` rounds to `100.00`, not `100.01`) |
| Scale matches COBOL `PICTURE` clause | `PIC 9(7)V99` → scale 2; `PIC 9(11)` → scale 0; tests assert `.scale()` alongside `.isEqualByComparingTo(...)` |
| No floating-point literals in fixture files | Monetary CSV values are quoted strings (e.g., `"100.50"`); the production code parses them via `new BigDecimal(String)` |
| AssertJ idiom | `assertThat(actual).isEqualByComparingTo(new BigDecimal("15.00"))` for value parity; pair with `.satisfies(b -> assertThat(b.scale()).isEqualTo(2))` when scale parity matters |

The `@ParameterizedTest` for `InterestCalculationProcessor` always includes a
`100.005`-with-HALF_EVEN row to prove the production code uses HALF_EVEN, not
HALF_UP. This is the canonical discrimination test.

## 7. Immutable Boundaries

Three boundaries are immutable across the migration. Tests must enforce them
positively (by asserting that they do not change), not just negatively (by not
breaking them).

| Boundary | Test Discipline |
| --- | --- |
| Input and output file formats | Each batch job's input fixtures are byte-identical copies (or documented PAN-converted variants) of `app/data/ASCII/*.txt`. Each output is compared byte-for-byte against the captured COBOL reference via `BaselineDiffUtil.assertByteEqual(...)`. |
| Record layouts | Record-width constants in `TestFixtures.RecordWidths` reflect the copybook `RECLN` values. Fixed-width parsers in tests assert `record.substring(0, 11)` is the account ID, `record.substring(11, 18)` is the customer ID, etc. |
| External interfaces consumed by downstream systems | Controller IT suite asserts every JSON response field name and shape via JsonPath expressions; any rename is caught at the slice-test boundary. |

## 8. Security Constraints

The following constraints are non-negotiable and apply to test code as well as
production code.

| Constraint | Enforcement |
| --- | --- |
| No financial data written to logs at any level | `logback-test.xml` registers a `TurboFilter` that masks card / PAN, 11-digit account IDs, CVV / CVC patterns, labelled monetary values, and BCrypt hashes *before* the log line is emitted. The console pattern's `%replace` chain provides defence-in-depth. A future `LoggingPiiRedactionTest` asserts the policy positively via `ListAppender<ILoggingEvent>`. |
| No plaintext credentials in any configuration file | `application-test.properties` carries no username/password literals. Spring Security's autoconfigured default user is suppressed by setting `spring.security.user.name=` and `spring.security.user.password=` to empty values; tests that need auth use `@WithMockUser` from `spring-security-test`. Testcontainers JDBC credentials are randomly generated by the container library (not hardcoded in the IT base classes — see the documented scope exception in `AbstractRepositoryIT` and `AbstractBatchIT`). |
| Visa test PAN range for card fixtures | `carddata.txt` and `cardxref.txt` carry card numbers in the publicly-documented Visa test PAN range `4111111111111101`–`4111111111111150`. This is a documented conversion from the original `app/data/ASCII/{carddata,cardxref}.txt` randomised PANs — see § 11 below. |
| Plaintext fixture passwords narrowly scoped | `TestFixtures.Users.TEST_PASSWORD_PLAINTEXT` is the only place a plaintext fixture password appears; stored fixture values (`TEST_PASSWORD_BCRYPT_HASH`) are BCrypt-hashed. The plaintext is required for the authenticate-and-verify code path but carries zero security value because it cannot authenticate against any production system. |
| Vulnerable dependency policy | The Spring Boot parent must remain on a patched version (no known unpatched CVEs in the chosen line). The build is upgraded promptly when a CVE is disclosed against the in-use Spring Boot or transitive library. |

## 9. Framework Constraints

The framework choices are fixed by the user prompt and the AAP. There is no
permission to introduce alternative testing frameworks.

| Constraint | Implementation |
| --- | --- |
| JUnit 5 (`@Test`, `@ParameterizedTest`) | All test classes import `org.junit.jupiter.api.Test` and `org.junit.jupiter.params.ParameterizedTest`. No `org.junit.Test` (JUnit 4) imports are permitted; `junit-vintage-engine` is not on the test classpath. |
| Mockito for mocks | `org.mockito.Mockito` and the `MockitoExtension` JUnit 5 integration. No PowerMock — Mockito 5.x supports static-method mocking natively via `Mockito.mockStatic(...)`. |
| Spring Boot 3.x BOM-managed versions | Test dependencies are declared without explicit version pins so the BOM resolves them. The only direct version pins are for build plugins (`maven-surefire-plugin`, `maven-failsafe-plugin`, `jacoco-maven-plugin`) and the Testcontainers BOM. |
| AssertJ for assertions | `assertThat(...)` everywhere. `org.junit.jupiter.api.Assertions` and Hamcrest matchers are not used. |
| Testcontainers for ITs | `@Testcontainers` + `@Container` for the JUnit Jupiter integration. `PostgreSQLContainer` is the repository / batch IT database; `LocalStackContainer` is the AWS-SDK IT boundary. |

**Excluded testing categories** (intentionally not in scope):

- No mutation testing (PIT).
- No property-based testing (jqwik).
- No contract testing (Spring Cloud Contract).
- No performance / load testing (JMH).
- No UI / browser end-to-end testing (Selenium / Playwright / Cypress) — the
  migration intentionally drops BMS and produces REST controllers.

## 10. Test Quality Criteria

Coverage percentage is necessary but not sufficient. The following qualitative
criteria are enforced by code review and by Mockito's strict-stubbing mode.

| Criterion | Standard |
| --- | --- |
| Assertion density | Every test method ends with at least one assertion that exercises observable behaviour of the production class under test. |
| Test isolation | Each test creates its own arrange / act / assert flow; tests must not rely on ordering or shared mutable state. |
| Performance — unit | `< 5 ms` per method (most run in `< 1 ms`). |
| Performance — repository IT | `< 250 ms` per method (Testcontainers warm-up amortised across the class). |
| Performance — batch IT | `< 5 s` per method. |
| Aggregate wall-clock — `mvn test` | `< 60 s` (unit only). |
| Aggregate wall-clock — `mvn verify` | `< 5 min` (unit + integration + E2E). |
| Magic numbers | Extracted to `TestFixtures.*` constants. |
| Magic strings | Extracted to `@CsvFileSource`-driven CSV files under `src/test/resources/fixtures/edge/`. |
| Style consistency | Arrange-Act-Assert (AAA) with blank-line separators between blocks; AssertJ-only assertions; `when(...).thenReturn(...)` Mockito style; static imports for assertion / Mockito DSLs. |

## 11. Fixture Policy

Baseline input fixtures are committed under `src/test/resources/baseline/input/`
and are the canonical "golden inputs" for unit and IT layers.

### 11.1 Byte-Identical Copies (Default)

The default policy is **one-for-one byte-identical copies** of
`app/data/ASCII/*.txt`. The following 7 fixtures follow this default:

- `acctdata.txt` (50 records × 300 bytes)
- `custdata.txt` (50 records × 500 bytes)
- `dailytran.txt` (300 records × 350 bytes)
- `discgrp.txt` (51 records × 50 bytes, includes `DEFAULT` and `ZEROAPR`)
- `tcatbal.txt` (50 records × 50 bytes)
- `trancatg.txt` (18 records × 60 bytes including newline)
- `trantype.txt` (7 records × 60 bytes including newline)

### 11.2 Documented Conversions (Scope Exceptions)

Two fixtures deviate from the byte-identical default because the source carries
randomised 16-digit card numbers that are not in any reserved test-PAN range and
because the source `cardxref.txt` omits the trailing 14-byte filler defined in
the copybook. The conversions are documented exceptions:

| Fixture | Source | Conversion | Reason |
| --- | --- | --- | --- |
| `carddata.txt` | 50 records × 150 bytes | Card-number field (positions 1–16) overwritten with Visa test PAN sequence `4111111111111101` (record 1) through `4111111111111150` (record 50) | The original ASCII file carries randomised 16-digit PANs (e.g., `0500024453765740`). These are not in a reserved test range. The Visa test PAN range `4111 1111 1111 11xx` is publicly documented and cannot be issued by any payment network, so the converted fixture remains safe for committed-source distribution and aligns with `TestFixtures.Cards.SAMPLE_CARD_NUMBER_*` constants. |
| `cardxref.txt` | 50 records × 36 bytes (source) → 50 records × 50 bytes (test fixture) | Card-number field converted to the same Visa test PAN sequence as `carddata.txt` (preserving referential integrity); the 14-byte trailing filler is added as ASCII spaces to bring each record up to the copybook `RECLN 50` from `app/cpy/CVACT03Y.cpy` | The ASCII source is sorted ascending by the original card-number field. After PAN conversion the records remain sorted (Visa test PAN range is monotonically ascending). The padding restores parity with `CVACT03Y.cpy` (`16 + 9 + 11 + 14 = 50`) and with the `TestFixtures.RecordWidths.CARD_XREF_RECLN = 50` constant. |

The referential integrity between `carddata.txt` and `cardxref.txt` is preserved
record-by-record: row N of `carddata.txt` and row N of `cardxref.txt` share the
same converted card number `4111111111111100 + N`, the same customer ID, and the
same account ID.

Both conversions are deterministic and reversible — the original ASCII source
remains under `app/data/ASCII/` untouched, so a future agent can regenerate the
converted fixtures from source if the conversion policy ever needs to change.

### 11.3 Baseline Expected Outputs (Capture-Pending)

All 6 expected-output files under `src/test/resources/baseline/expected/` are
currently committed as capture-pending placeholders whose first line is
`# BASELINE_CAPTURE_PENDING_<NAME>`. `BaselineDiffUtil.assertByteEqual(...)`
detects this marker and fails fast with a clear diagnostic, preventing
accidental green builds against unpopulated baselines. The capture procedure is
documented in [`baseline-parity.md`](baseline-parity.md).

### 11.4 Edge-Case CSV Fixtures

Edge-case CSV fixtures under `src/test/resources/fixtures/edge/` drive
`@ParameterizedTest` rows for specific scenarios. Each CSV has a header row
followed by data rows; monetary values are written as quoted decimal strings
(e.g., `"100.50"`) — never as `float`/`double` literals — and parsed via
`new BigDecimal(String)`.

| Fixture | Driven Test | Scenarios Covered |
| --- | --- | --- |
| `interest_zero_balance.csv` | `InterestCalculationProcessorTest` | Zero-balance rows produce zero interest at scale 2 |
| `interest_zeroapr_skip.csv` | `InterestCalculationProcessorTest` | `ZEROAPR` group rows are skipped (no interest written) |
| `interest_default_fallback.csv` | `InterestCalculationProcessorTest` | Missing-group rows fall back to `DEFAULT` group |
| `interest_halfeven_boundary.csv` | `InterestCalculationProcessorTest` | HALF_EVEN distinguishes from HALF_UP at the `.5` boundary |
| `posting_reject_codes.csv` | `TransactionPostingProcessorTest` | Each reject code 100–103 (and any 104–109 introduced during migration) |
| `overflow_boundary.csv` | Multiple processor tests | `PIC 9(7)V99` boundary arithmetic |
| `eof_boundary.csv` | Multiple processor tests | End-of-file conditions for `FlatFileItemReader` |
| `date_validation_variants.csv` | `DateValidationServiceTest` | Leap years, century rule, format variants |
| `status_code_mappings.csv` | `FileStatusMapperTest` | VSAM status codes `00`, `02`, `10`, `22`, `23`, `35`, `92`, `97` |
| `lookup_invalid_keys.csv` | `ValidationLookupServiceTest` | NANPA, US state, ZIP prefix invalid keys |

## 12. Canonical Maven Invocations

The Surefire / Failsafe split is enforced by Maven: Surefire 3.x picks up
`**/*Test.java`, Failsafe 3.x picks up `**/*IT.java`. The `jacoco` profile gates
the coverage rule.

| Action | Command |
| --- | --- |
| Build + unit tests only | `mvn clean test` |
| Build + unit + integration tests | `mvn clean verify` |
| Build + all tests + JaCoCo coverage gate | `mvn clean verify -Pjacoco` |
| Run a single unit test class | `mvn -Dtest=AuthenticationServiceTest test` |
| Run a single unit test method | `mvn -Dtest=AuthenticationServiceTest#authenticate_validUserValidPassword_returnsUserSession test` |
| Run a single integration test class | `mvn -Dit.test=TransactionPostingBaselineParityIT verify` |
| Skip integration tests | `mvn test -DskipITs` |
| Regenerate coverage report only | `mvn jacoco:report` |
| Validate `pom.xml` syntax / lifecycle | `mvn -B validate -DskipTests` |
| Resolve dependency tree | `mvn -B dependency:tree -DskipTests` |
| Compile test sources only | `mvn -B test-compile -DskipTests` |

**Environment requirements:**

- Java 17 LTS on `PATH` (verify with `java -version`).
- Maven 3.8+ on `PATH` (verify with `mvn -version`).
- Docker running and accessible to Testcontainers (verify with `docker info`).
- No external network calls required by the test suite; all integrations are
  sandboxed via Testcontainers.
- In constrained CI environments the `TESTCONTAINERS_RYUK_DISABLED=true`
  environment variable may be set when the Ryuk reaper container cannot run.

## 13. Test Execution Independence and Parallelism

Tests must be independent and runnable in any order.

| Property | Setting |
| --- | --- |
| `junit.jupiter.execution.parallel.enabled` | `true` |
| `junit.jupiter.execution.parallel.mode.classes.default` | `concurrent` (classes execute in parallel) |
| `junit.jupiter.execution.parallel.mode.default` | `same_thread` (methods within a class stay on one thread to preserve `@BeforeEach`/`@AfterEach` semantics) |
| Testcontainers container lifecycle | One container per IT class via `@Container static` field; each IT class owns its container lifecycle. |
| `@DirtiesContext` | Avoided unless absolutely required (keeps Spring's context cache warm). |
| Spring Batch state reset | `JobRepositoryTestUtils.removeJobExecutions()` between executions in batch ITs. |

## 14. Scope Discipline

### 14.1 In Scope

Every test artefact required by the AAP §0.5 file-by-file plan: the ~888 test
classes, the 9 baseline input fixtures, the 6 baseline expected fixtures, the
10 edge-case CSV fixtures, the 5 shared testsupport utilities, and the four
test configuration files (`pom.xml`, `junit-platform.properties`,
`application-test.properties`, `logback-test.xml`).

### 14.2 Out of Scope

- Source-code modifications in `src/main/java/**` made purely for testability.
  The only seam the migration accepts is `Clock` injection; everything else
  must be testable as written.
- Production database schema modifications. Tests reuse the production Flyway
  migrations under `src/main/resources/db/migration/`.
- Performance benchmarks, mutation tests, property-based tests, contract
  tests, and UI tests.
- Modifications to the `app/` reference directories. The COBOL/JCL/copybook/
  BMS/data/proc/csd directories are the source-of-truth baseline for the
  migration and remain unchanged.

### 14.3 Documented Scope Exceptions

Three files exist on disk at this milestone that the strict reading of AAP §0.8
would mark as "out of scope for the testing flavor" but are present for
legitimate technical reasons. Each is documented here as a deliberate
exception:

| File | Why It Exists | Why It Is Retained |
| --- | --- | --- |
| `.gitignore` | Repository hygiene — keeps `target/`, IDE metadata, log output, JaCoCo `.exec` files, and local credential files out of version control. | Removing it would cause every clean Maven build to surface dozens of untracked artefacts in `git status`. The hygiene benefit outweighs the strict scope-rule violation. Owned by the next checkpoint's scope when it next changes. |
| `src/main/java/com/aws/carddemo/batch/CombineTransactionsProcessor.java` | Required for `CombineTransactionsProcessorTest` (an in-scope test) to compile. Added by a prior agent in response to a critical "test must compile and be runnable" review finding. | Removing it would break the in-scope test's compilation and re-introduce the prior critical finding. Owned by the migration's REFACTOR-flavor workstream when the broader batch-processor migration occurs; this file is the minimal stand-in until then. |
| `src/main/resources/application.properties` | Was previously committed as a stub Spring Boot application configuration. **Removed** at this checkpoint — no `CardDemoApplication` main class exists yet, and the test profile uses `application-test.properties` exclusively. | N/A — see § 14.4. |

### 14.4 Removed at This Checkpoint

`src/main/resources/application.properties` was deleted. The setup status log
expected no production-source files to exist at this milestone, and no test
infrastructure references the production `application.properties` (Spring Boot
slice tests resolve `application-test.properties` and the
`@DynamicPropertySource` overrides applied by `AbstractRepositoryIT` /
`AbstractBatchIT`). Re-introducing this file is the responsibility of the
agent that lands the `CardDemoApplication` main class.

## 15. References

- [`baseline-parity.md`](baseline-parity.md) — operating procedure for capturing
  and refreshing COBOL reference outputs used by Layer 3 of the pyramid.
- AAP §0.4 — Test implementation design (strategy, blueprint, fixtures).
- AAP §0.5 — Test file transformation mapping (the 888-file plan).
- AAP §0.6 — Dependency inventory and version compatibility.
- AAP §0.7 — Coverage and quality targets.
- AAP §0.9 — Execution parameters and canonical commands.
- AAP §0.10 — Special instructions for testing (the source of every rule above).
