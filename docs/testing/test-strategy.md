# Test Strategy

## 1. Overview

The CardDemo migration test suite has one mission: deliver a complete, greenfield JUnit 5 + Mockito test suite for the Java 17 LTS / Spring Boot 3.x codebase migrated from the AWS CardDemo COBOL/JCL mainframe application. Two non-negotiable acceptance contracts govern every test artefact produced under this strategy: (1) **byte-identical financial-calculation parity** against the COBOL baseline, and (2) **≥80% line coverage on the service layer**, enforced declaratively by the Maven build. Together these contracts guarantee that the migrated Java implementation behaves identically to the original COBOL programs along every observable boundary.

The runtime envelope is fixed: **Java 17 LTS**, **Spring Boot 3.x**, **Maven 3.8+**, **JUnit Jupiter 5.10.x** (BOM-managed via `spring-boot-starter-test`), **Mockito 5.x** (BOM-managed), **JaCoCo 0.8.11+** for coverage instrumentation, and **Testcontainers** for ephemeral **PostgreSQL 16** and **LocalStack** containers. All testing dependencies are declared with `<scope>test</scope>` and resolved by the Spring Boot BOM so transitive versions remain coherent.

This document is the authoritative reference for the test strategy. It complements the parity workflow in [`baseline-parity.md`](baseline-parity.md), which documents how COBOL reference outputs are captured, refreshed, and consumed by the baseline parity layer of the test pyramid.

## 2. The Four-Layer Test Pyramid

The CardDemo migration test suite follows a four-layer strategy. Each layer answers a distinct correctness question; together they enforce the ≥80% line-coverage floor and the byte-identical baseline-parity requirement (AAP §0.4.1).

| Layer | Purpose | Spring Test Slice | Mock Boundary | Approx. Test Count |
| ----- | ------- | ----------------- | ------------- | ------------------ |
| Unit tests | Verify isolated business logic per service / processor / validator / mapper | None — `@ExtendWith(MockitoExtension.class)` only | All collaborators mocked; system under test is the real production class | ~729 |
| Integration tests | Verify cross-component wiring, JPA queries, Spring Batch step semantics, controller HTTP semantics | `@SpringBootTest`, `@DataJpaTest`, `@WebMvcTest`, `@SpringBatchTest` | Real Spring context + Testcontainers PostgreSQL/LocalStack; only third-party HTTP boundaries mocked | ~131 |
| Baseline parity ITs | Verify byte-identical output vs COBOL baseline for every batch job | `@SpringBatchTest` + custom `BaselineDiffUtil` | Real Spring Batch context | 5 (one per batch job) |
| End-to-end ITs | Verify multi-step user/operator journeys | `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` + Testcontainers | Real Spring context end-to-end | ~28 |

The pyramid shape is intentional. Unit tests sit at the wide base because they iterate in sub-second time and pinpoint failures to a single production class. End-to-end ITs sit at the narrow top because they are the slowest layer and are reserved for behaviours that genuinely require multiple components in coordination. The baseline parity layer is the third tier and carries the byte-identical-output contract for every Spring Batch job.

### 2.1 Unit Tests

Each migrated service method is exercised with curated inputs that trigger each `EVALUATE/WHEN` branch, each `PERFORM` paragraph, each `IF/ELSE`, and each `COMPUTE` step inherited from the COBOL source. Mockito boundary mocks replace JPA repositories, file readers/writers, AWS SDK clients, and the injected `Clock`. The system-under-test is always the real production class — never a partial mock — so production behaviour is what the test asserts on. `@ParameterizedTest` annotated methods, driven by `@CsvFileSource` files under `src/test/resources/fixtures/edge/`, cover variant inputs (reject codes, overflow boundaries, HALF_EVEN/HALF_UP discrimination, leap-year edge cases) without duplicating the test body for each row. (Cross-reference AAP §0.4.1, §0.10.1.)

### 2.2 Integration Tests

Repository ITs run real JPA queries against Testcontainers PostgreSQL 16 with Flyway-applied schema (`V1__schema.sql`, `V2__indexes.sql`, `V3__seed.sql`). Batch ITs run a complete Spring Batch `Job` end-to-end via `JobLauncherTestUtils`. Controller ITs use `@WebMvcTest` slice with `MockMvc` to assert HTTP status, headers, and body. AWS SDK integrations use LocalStack containers. Repository ITs extend `AbstractRepositoryIT`; batch ITs extend `AbstractBatchIT` — both shared test-support base classes wire the Testcontainers boilerplate so individual ITs stay focused on the behaviour under test. (Cross-reference AAP §0.4.1, §0.4.4.)

### 2.3 Baseline Parity Integration Tests

Each of the 5 Spring Batch jobs has a dedicated `*BaselineParityIT` class that produces an output file and calls `BaselineDiffUtil.assertByteEqual(actualPath, expectedPath)` against the captured COBOL reference output under `src/test/resources/baseline/expected/`. Zero delta is required — a single byte difference (a trailing whitespace character, a sign-overpunch mismatch, or a one-digit drift caused by `RoundingMode.HALF_UP` instead of `RoundingMode.HALF_EVEN`) causes the IT to fail. Until COBOL capture occurs, expected-output files carry a `BASELINE_CAPTURE_PENDING_<NAME>` marker on their first line and `BaselineDiffUtil` recognises that marker, failing loudly with a "baseline capture pending" diagnostic so green builds against unpopulated baselines are impossible. See [`baseline-parity.md`](baseline-parity.md) for the full capture and refresh procedure.

### 2.4 End-to-End Tests

End-to-end ITs orchestrate multiple components in sequence to verify user journeys and batch pipelines that span more than one controller, service, or job. The two canonical journeys are: online sign-on → main menu → operation → logout; and the batch pipeline `POSTTRAN → INTCALC → COMBTRAN → CREASTMT ∥ TRANREPT`. Tests use `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` and share a Testcontainers PostgreSQL instance with seeded fixtures so multi-step workflows can assert on accumulated state. The suite holds ~28 such tests; they are the slowest layer and are kept few by design. (Cross-reference AAP §0.4.1.)

## 3. Coverage Targets

Coverage is measured and enforced by the JaCoCo Maven plugin. The build fails when any package's coverage drops below its declared floor (AAP §0.7.1).

| Layer / Package Pattern | Line Coverage Target | Branch Coverage Target | Rationale |
| ----------------------- | -------------------- | ---------------------- | --------- |
| `com.aws.carddemo.service.**` | **≥80%** (user mandate) | ≥70% | Explicit user requirement; service layer is the primary correctness surface |
| `com.aws.carddemo.batch.**` | ≥85% | ≥75% | Financial-calculation processors carry the highest correctness burden |
| `com.aws.carddemo.validation.**` | ≥90% | ≥85% | Small surface, high criticality (date, lookup, format validators) |
| `com.aws.carddemo.io.**` | ≥90% | ≥85% | `FileStatusMapper` and adapter utilities are small and easy to fully cover |
| `com.aws.carddemo.repository.**` | ≥75% | n/a | Mostly Spring-generated; ITs target custom queries |
| `com.aws.carddemo.controller.**` | ≥80% | ≥70% | HTTP routing, validation, response shape |
| `com.aws.carddemo.dto.**` | not enforced | n/a | DTOs are data carriers (records or POJOs); excluded from coverage rules |
| `com.aws.carddemo.config.**` | not enforced | n/a | Spring configuration classes; behaviour exercised indirectly by ITs |

The JaCoCo `<rule>` block in `pom.xml` enforces the `LINE ≥ 0.80` and `BRANCH ≥ 0.70` floors on `com.aws.carddemo.service.*` directly. Coverage reports are produced under `target/site/jacoco/`:

- HTML: `target/site/jacoco/index.html`
- XML: `target/site/jacoco/jacoco.xml`

Specific coverage gaps that MUST be exercised (per AAP §0.7.1):

- Reject codes 100–103 (and any 104–109 surfaced during migration) for `CBTRN02C` → `TransactionPostingProcessor`.
- `ZEROAPR` skip and `DEFAULT` group fallback for `CBACT04C` → `InterestCalculationProcessor`.
- HALF_EVEN-vs-HALF_UP boundary at the `100.005` case.
- Both `@Version` success path and `OptimisticLockingFailureException` path for `COACTUPC`, `COCRDUPC`, `COUSR02C`.
- Both commit and rollback branches of `@Transactional` blocks in `COACTUPC` migrations.
- All documented VSAM status codes (`00`, `02`, `10`, `22`, `23`, `35`, `92`, `97`) in `FileStatusMapper`.

## 4. The Require Test Coverage Rule

The Require Test Coverage rule is the project's most consequential testing discipline. It is non-negotiable. The four user directives below are restated verbatim from AAP §0.10.1:

> - Tests MUST call production service and repository classes directly
> - Tests MUST NOT reimplement any business or calculation logic inside test bodies
> - Mocks limited to external boundaries: file I/O, downstream service calls, database
> - Each test method asserts a specific behavior of the production class under test

### 4.1 Enforcement

- Every unit test instantiates the real production class (via `new ServiceImpl(...)` or `@InjectMocks`) and invokes its public methods.
- `@Mock` instances are reserved for: `JpaRepository` subclasses, `FlatFileItemReader`/`FlatFileItemWriter`, AWS SDK clients (`S3Client`, `SqsClient`, `SesClient`), `Clock`, sequence generators, and external HTTP clients.
- Tests do NOT duplicate `COMPUTE` arithmetic in assertions. Example — correct: `assertThat(result.getInterest()).isEqualByComparingTo("15.00")`. Forbidden: `assertThat(result.getInterest()).isEqualByComparingTo(balance.multiply(rate).divide(BigDecimal.valueOf(1200), 2, HALF_EVEN))`.
- Tests do NOT duplicate `EVALUATE/WHEN` branch logic. Example — correct: `assertThat(reject.getCode()).isEqualTo(102)` for a specific input row. Forbidden: a conditional expression that re-derives the expected code from the input.
- Mockito strictness is `STRICT_STUBS` (the JUnit Jupiter default activated by `@ExtendWith(MockitoExtension.class)`). Unused stubs raise `UnnecessaryStubbingException` and fail the build — this catches the most common form of rule violation, where a collaborator is stubbed but the code path that uses the stub is never exercised.

### 4.2 Why

If tests reimplement the formula they are verifying, the test passes regardless of whether the production code is correct or buggy. The rule's goal is to ensure tests verify **production behaviour**, not the test author's understanding of the COBOL formula. A literal expected value (e.g., `"15.00"`) anchored to a documented input fixture is reviewable, debuggable, and falsifiable; a re-derived expected value computed inside the test body is none of those things.

## 5. Financial Precision in Tests

Every monetary value in the CardDemo migration is a `BigDecimal`. No exceptions. The five user directives below are restated verbatim from AAP §0.10.3:

> - No `float` or `double` used for any monetary value — `BigDecimal` exclusively
> - `BigDecimal` rounding mode set to `HALF_EVEN` (banker's rounding) matching COBOL `PICTURE` clause precision
> - All monetary fields: `BigDecimal` with scale derived from COBOL `PICTURE` clause (e.g., `PIC 9(7)V99` → scale 2)
> - Rounding: `RoundingMode.HALF_EVEN` throughout
> - No implicit type promotion to `float`/`double` at any calculation boundary

### 5.1 Test Enforcement Steps

- Every assertion on a monetary value uses AssertJ's `isEqualByComparingTo(BigDecimal)` and includes a paired `.satisfies(b -> assertThat(b.scale()).isEqualTo(2))` check where scale parity matters.
- The `@ParameterizedTest` for `InterestCalculationProcessor` includes the `100.005` HALF_EVEN-vs-HALF_UP boundary case to prove the production code uses HALF_EVEN, not HALF_UP. For example, `(100.005, 0.10, ...)` should round to `0.10` under HALF_EVEN, not `0.11` under HALF_UP.
- No test fixture file (CSV, properties, JSON) carries a monetary value as a floating-point literal; values are quoted strings (e.g., `"100.50"`) that map cleanly to `new BigDecimal(...)`.

### 5.2 Example

```java
@ParameterizedTest
@CsvFileSource(resources = "/fixtures/edge/interest_halfeven_boundary.csv", numLinesToSkip = 1)
void process_halfEvenBoundary_appliesBankersRounding(String balance, String rate, String expectedInterest) {
    var result = processor.process(new TransactionCategoryBalance(new BigDecimal(balance), /*...*/ ));
    assertThat(result.getInterest())
        .isEqualByComparingTo(new BigDecimal(expectedInterest))
        .satisfies(b -> assertThat(b.scale()).isEqualTo(2));
}
```

## 6. Test Naming and Location Conventions

The two user directives below are restated verbatim from AAP §0.10.6:

> - Test file location: `src/test/java` mirroring production package structure
> - Naming: `[ClassName]Test.java` for unit tests, `[ClassName]IT.java` for integration tests

### 6.1 Maven Enforcement

- The Surefire plugin `<includes>` lists `**/*Test.java` only; the Failsafe plugin `<includes>` lists `**/*IT.java` only. The split is enforced by Maven, not by convention — mis-naming a test class causes Maven to silently skip it.
- Every test class lives in the same package as its production class. A test for `com.aws.carddemo.service.AuthenticationService` lives at `src/test/java/com/aws/carddemo/service/AuthenticationServiceTest.java`. This grants package-private access where helpful and keeps IDE navigation tight.
- Test method names follow `methodUnderTest_inputCondition_expectedOutcome`. Examples:
  - `authenticate_validUserValidPassword_returnsUserSession`
  - `process_zeroBalance_returnsZeroInterest`
  - `process_invalidCardNumber_yieldsRejectCode100`

### 6.2 Package Layout Reference

| Source Package | Test Package | Test Suffix |
| -------------- | ------------ | ----------- |
| `com.aws.carddemo.service` | `src/test/java/com/aws/carddemo/service` | `*Test.java` |
| `com.aws.carddemo.batch` (processors) | `src/test/java/com/aws/carddemo/batch` | `*Test.java` |
| `com.aws.carddemo.batch` (jobs) | `src/test/java/com/aws/carddemo/batch` | `*IT.java` |
| `com.aws.carddemo.repository` | `src/test/java/com/aws/carddemo/repository` | `*IT.java` |
| `com.aws.carddemo.controller` | `src/test/java/com/aws/carddemo/controller` | `*Test.java` (slice) |
| `com.aws.carddemo.validation` | `src/test/java/com/aws/carddemo/validation` | `*Test.java` |
| `com.aws.carddemo.io` | `src/test/java/com/aws/carddemo/io` | `*Test.java` |
| (cross-cutting) | `src/test/java/com/aws/carddemo/e2e` | `*Test.java` |

## 7. Frameworks and Mockito Strictness

The framework choice is restated verbatim from AAP §0.10.7:

> Framework: JUnit 5 (`@Test`, `@ParameterizedTest` for calculation variants), Mockito for mocks

### 7.1 Framework Rules

- **No JUnit 4** (`org.junit.Test`) in any test file; no `junit-vintage-engine` dependency in `pom.xml`.
- **Mockito 5.x via BOM**; no PowerMock. Static-method mocking (rare — used only at the `Files`/`Paths` boundary) uses `Mockito.mockStatic`.
- **Mockito strictness**: `STRICT_STUBS` (the default with `MockitoExtension`). Unused stubs fail the build via `UnnecessaryStubbingException`.
- **`@ExtendWith(MockitoExtension.class)`** is the canonical extension for unit tests, replacing the legacy `@RunWith(MockitoJUnitRunner.class)`.
- **`@ParameterizedTest`** is the standard mechanism for "calculation variants": every monetary calculation has at least one parameterized test driven by a CSV under `src/test/resources/fixtures/edge/`.
- **AssertJ** for assertions everywhere — `assertThat(...)`. `org.junit.jupiter.api.Assertions` and Hamcrest matchers are not used.

### 7.2 Example Skeleton

```java
@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    @Mock private UserSecurityRepository userSecurityRepository;
    @Mock private Clock clock;
    @InjectMocks private AuthenticationService authenticationService;

    @Test
    void authenticate_validUserValidPassword_returnsUserSession() {
        // arrange — stub the boundary
        when(userSecurityRepository.findById("USRTST01")).thenReturn(Optional.of(fixtureUser));
        // act — invoke the real production class
        var session = authenticationService.authenticate("USRTST01", "TESTPASS");
        // assert — observable outcome
        assertThat(session.userId()).isEqualTo("USRTST01");
    }
}
```

## 8. Test Quality Criteria

Coverage percentage is necessary but not sufficient. The following qualitative criteria, drawn from AAP §0.7.2, are enforced by code review and by Mockito's strict-stubbing mode.

### 8.1 Assertion Density

- Every test method ends with at least one assertion that exercises observable behaviour of the production class under test.
- Tests that only verify a mock interaction without asserting on production state are flagged in code review — they typically indicate over-mocking and a Require-Test-Coverage-rule violation.
- Where a method returns a complex object, the test asserts on **all** semantically meaningful fields (use AssertJ's `usingRecursiveComparison()` where appropriate).

### 8.2 Test Isolation

- Each test creates its own arrange / act / assert flow; tests must not rely on ordering or shared mutable state.
- `@BeforeEach` resets shared collaborators; `@AfterEach` is generally unnecessary because Mockito instances are recreated per test.
- Repository ITs use Spring's `@Transactional` rollback or `@DirtiesContext` only when absolutely required.
- Batch ITs call `JobRepositoryTestUtils.removeJobExecutions()` between executions.

### 8.3 Performance Constraints

| Layer | Wall-Clock Target |
| ----- | ----------------- |
| Single unit-test method | < 5 ms (most run in < 1 ms) |
| Single repository IT method | < 250 ms (container warm-up amortised) |
| Single batch IT method | < 5 s |
| Total `mvn test` (unit only) | < 60 s |
| Total `mvn verify` (unit + IT + E2E) | < 5 min |

### 8.4 Maintainability Standards

- Test class names mirror production class names: `Foo` → `FooTest` / `FooIT`.
- Magic numbers are extracted to `TestFixtures` constants; magic strings to `@CsvFileSource` files.
- AssertJ chains are preferred over multiple separate `assertThat` calls when asserting on a single object.
- BCrypt is used at real strength (`BCryptPasswordEncoder.DEFAULT_STRENGTH = 10`) in service unit tests; tests remain sub-second because each test sees only a handful of hash operations.
- All time-dependent code is driven by an injected `Clock`; tests inject `Clock.fixed(Instant.parse("2024-01-15T00:00:00Z"), ZoneOffset.UTC)`.

## 9. Execution Commands

The canonical Maven invocations below are restated from AAP §0.9.1. Surefire 3.x runs `**/*Test.java` in the `test` phase; Failsafe 3.x runs `**/*IT.java` in the `integration-test` / `verify` phases; the `jacoco` profile gates the coverage rule.

| Action | Command |
| ------ | ------- |
| Build + unit tests | `mvn clean test` |
| Build + all tests (unit + integration) | `mvn clean verify` |
| Build + all tests + coverage | `mvn clean verify -Pjacoco` |
| Single unit test class | `mvn -Dtest=AuthenticationServiceTest test` |
| Single unit test method | `mvn -Dtest=AuthenticationServiceTest#authenticate_validUserValidPassword_returnsUserSession test` |
| Single integration test class | `mvn -Dit.test=TransactionPostingBaselineParityIT verify` |
| Skip integration tests | `mvn test -DskipITs` |
| Generate coverage report only | `mvn jacoco:report` |
| Run tests by tag | `mvn test -Dgroups="batch"` |
| Debug a single test | `mvn -Dmaven.surefire.debug -Dtest=ClassName test` (listens on port 5005) |

### 9.1 Environment Prerequisites

- Java 17 LTS on `PATH` (verify with `java -version`).
- Maven 3.8+ on `PATH` (verify with `mvn -version`).
- Docker running and accessible to Testcontainers (verify with `docker info`).
- `TESTCONTAINERS_RYUK_DISABLED=true` may be set in constrained environments where the Ryuk reaper container cannot run.

## 10. Fixtures and Test Data

Test data is organised under three roots inside `src/test/resources/`, all of which trace back to AAP §0.4.4.

### 10.1 Baseline Inputs

`src/test/resources/baseline/input/` holds canonical golden inputs (one-for-one copies, or documented PAN-converted variants, of `app/data/ASCII/*.txt`):

- `acctdata.txt` (50 records, ~15 KB) — drives `AccountRepository` / `AccountFileProcessor` tests.
- `carddata.txt` (50 records, ~7.5 KB) — drives `CardRepository` / `CardFileProcessor` tests.
- `cardxref.txt` (50 records, ~1.8 KB) — drives `CardXrefRepository` tests.
- `custdata.txt` (50 records, ~25 KB) — drives `CustomerRepository` tests.
- `dailytran.txt` (~105 KB) — drives posting / validation / report processors.
- `discgrp.txt` (51 records including `DEFAULT` and `ZEROAPR`) — drives interest-calculator branches.
- `tcatbal.txt` (50 records) — drives `InterestCalculationProcessor` and `CombineTransactionsProcessor`.
- `trancatg.txt` (18 categories) — reference data for category-key validation.
- `trantype.txt` (7 types) — reference data for type-key validation.

### 10.2 Baseline Expected Outputs

`src/test/resources/baseline/expected/` holds golden outputs captured from the COBOL baseline before the migration begins. Each `*BaselineParityIT` consumes one expected file as the right-hand operand of `BaselineDiffUtil.assertByteEqual(actualPath, expectedPath)`. See [`baseline-parity.md`](baseline-parity.md) for the capture procedure and for how `BaselineDiffUtil` handles unpopulated baselines.

### 10.3 Edge-Case CSV Fixtures

`src/test/resources/fixtures/edge/` holds curated CSV files for `@ParameterizedTest` data-driven edge cases (one CSV per edge-case category):

- `interest_zero_balance.csv`
- `interest_zeroapr_skip.csv`
- `interest_default_fallback.csv`
- `interest_halfeven_boundary.csv`
- `posting_reject_codes.csv`
- `date_validation_variants.csv`
- `status_code_mappings.csv`
- `eof_boundary.csv`
- `overflow_boundary.csv`
- `lookup_invalid_keys.csv`

### 10.4 Shared Test Utilities

`src/test/java/com/aws/carddemo/testsupport/` holds shared test utilities used across the suite:

- `FixtureLoader` — loads `baseline/input/*.txt` and `fixtures/edge/*.csv` as typed objects.
- `BaselineDiffUtil` — `assertByteEqual(Path actual, Path expected)` with line-level diff reporting on failure.
- `TestFixtures` — constants holding sample account IDs, card numbers, customer IDs, and transaction IDs used across tests.
- `AbstractBatchIT` — base class with `@SpringBatchTest` + Testcontainers PostgreSQL boilerplate.
- `AbstractRepositoryIT` — base class with `@DataJpaTest` + Testcontainers PostgreSQL boilerplate.

### 10.5 Test Database State Management

- **Unit tests** use no database — Mockito-stubbed repositories.
- **Repository ITs** use Testcontainers PostgreSQL 16 with `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)`. Flyway-applied schema and seed data; transactional rollback after each `@Test`.
- **Batch ITs and end-to-end ITs** use Testcontainers PostgreSQL 16 shared per test class via Spring's context cache. `JobRepositoryTestUtils.removeJobExecutions()` runs between tests to reset Spring Batch metadata.

## 11. Out of Scope

The following are explicitly excluded from the test work, drawn from AAP §0.8.2:

- **Database schema modifications.** Tests use the existing migration footprint (`V1__schema.sql`, `V2__indexes.sql`, `V3__seed.sql`); no additional production Flyway scripts are introduced by the test work.
- **Source-code modifications.** No reshaping of production classes for test convenience beyond the `Clock` injection seam, which is part of the migration's idiomatic Java design and not a testability-only addition.
- **Refactoring beyond what's needed for testing.** The Minimal Change Clause governs.
- **Feature additions while adding tests.** No new business logic, endpoints, or batch jobs are introduced by the test work.
- **Mutation testing** — no PIT plugin.
- **Property-based testing** — no jqwik.
- **Contract testing** — no Spring Cloud Contract.
- **Performance / load testing** — no JMH.
- **UI / browser end-to-end testing** — the migration intentionally drops BMS and produces REST controllers; no Selenium / Playwright / Cypress.
- **EBCDIC fixture conversion tooling.** The 12 `app/data/EBCDIC/*` files are reference-only; the test suite uses the ASCII fixtures.
- **Infrastructure / CI/CD configuration.** Jenkins/GitHub Actions/Cloud Build pipelines, Helm charts, Terraform, Dockerfiles — all out of scope. The local Maven build (`mvn verify`) is the single source of truth for test execution.
- **Java 25 or any non-Java-17 runtime.** The user mandate is Java 17 LTS; prior tech spec references to Java 25 are **superseded** for this work.

## 12. References

Authoritative companion documents and library references:

- **Companion document:** [`baseline-parity.md`](baseline-parity.md) — operating procedure for capturing new COBOL reference outputs when fixtures change; the byte-identical parity contract; the per-batch-job golden output mapping; `BaselineDiffUtil` usage.
- **Anchor blueprint:** [`../technical-specifications.md`](../technical-specifications.md) — the broader migration blueprint that this testing reference complements.
- **Top-level project README:** [`../../README.md`](../../README.md) — repository overview and getting-started commands.

**Spring Boot test umbrella** (transitively included via `spring-boot-starter-test`):

- JUnit Jupiter 5.10.x (BOM-managed)
- Mockito 5.x (BOM-managed)
- AssertJ 3.24+ (BOM-managed)
- Hamcrest 2.2 (BOM-managed)
- Spring Test 6.x (BOM-managed)

**Additional BOM-managed test libraries:**

- **Spring Batch Test** (BOM-managed): `@SpringBatchTest`, `JobLauncherTestUtils`, `JobRepositoryTestUtils`.
- **Spring Security Test** (BOM-managed): `@WithMockUser`, `SecurityMockMvcConfigurers`.
- **Testcontainers** (BOM 1.20.3): `@Testcontainers`, `@Container`, `PostgreSQLContainer<?>`, `LocalStackContainer`.
- **JaCoCo Maven plugin 0.8.11+**: line / branch coverage instrumentation and rule enforcement.
