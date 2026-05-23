# Technical Specification

# 0. Agent Action Plan

## 0.1 Intent Clarification

### 0.1.1 Core Testing Objective

Based on the provided requirements, the Blitzy platform understands that the testing objective is to **deliver a complete, greenfield JUnit 5 + Mockito test suite for the Java 17 / Spring Boot 3.x codebase that is being migrated from the AWS CardDemo COBOL/JCL mainframe application, while enforcing byte-identical financial-calculation parity against the COBOL baseline and an `≥80%` line-coverage floor on the service layer.**

The request category is unambiguously **Add new tests** (greenfield). The repository today contains only COBOL source, copybooks, JCL job control, BMS mapsets, and ASCII/EBCDIC data fixtures; there is no existing `pom.xml`, no `src/main/java`, and no `src/test/java`. Every test artefact identified in this Action Plan must therefore be **CREATEd**.

Each testing requirement extracted from the user prompt is restated below with technical precision:

- **R1 — Functional parity verification.** Every migrated Java class (one Java class per COBOL `PROGRAM-ID`) must have JUnit 5 test methods that drive its public API with the same input fixtures the COBOL program receives and assert that observable outputs (returned objects, written records, computed monetary values) are identical to the COBOL reference output.
- **R2 — Financial precision verification.** Every monetary calculation must be exercised with `BigDecimal` inputs/outputs at the scale derived from the originating COBOL `PICTURE` clause (e.g., `PIC 9(7)V99` → scale 2), and rounding behaviour must be asserted to follow `RoundingMode.HALF_EVEN`. Tests must fail if any code path uses `float` or `double` for monetary values.
- **R3 — `≥80%` service-layer line coverage.** JaCoCo must measure ≥80% line coverage on every class in the service layer, enforced via the Maven build (`<rule>` element on the JaCoCo `check` goal).
- **R4 — Require Test Coverage rule compliance.** Every test method must invoke production service/repository code directly. No business or calculation logic may be reimplemented inside test bodies; mocks are limited to external boundaries (file I/O streams, downstream service stubs, database).
- **R5 — Spring Batch step/job parity.** Every JCL job mapped to a Spring Batch `Job` must have an integration test that runs the job against fixture input and asserts output parity with the original JCL execution.
- **R6 — Edge case coverage.** All edge cases inherited from the COBOL implementation (arithmetic overflow, zero-value records, end-of-file conditions, reject codes 100–109 for `CBTRN02C`, `ZEROAPR` skip and `DEFAULT` group fallback for `CBACT04C`, VSAM file-status code translation) must have corresponding `@ParameterizedTest` cases.

Implicit testing needs that the user's prompt assumes but does not name explicitly:

- Coverage of the date-validation utility that replaces the LE `CEEDAYS` intrinsic (originally `CSUTLDTC`, 703 lines of COBOL).
- Coverage of the lookup-data validator that replaces the `CSLKPCDY` copybook (NANPA area-code, US-state, ZIP-code prefix validation).
- Coverage of the optimistic-locking semantics (`@Version`) that replace the COBOL before/after-image record comparison in `COACTUPC` and `COCRDUPC`.
- Coverage of the `@Transactional(rollbackFor = Exception.class)` boundary that replaces the COBOL `SYNCPOINT ROLLBACK` block in `COACTUPC`.
- Coverage of the BCrypt-hashed authentication path that replaces the plaintext password comparison in `COSGN00C`.
- Coverage of the `FileStatusMapper` that translates VSAM file-status codes to Java exceptions.

### 0.1.2 Special Instructions and Constraints

The user's prompt embeds several non-negotiable directives that must be preserved verbatim in the test design:

- **User Directive:** "Tests MUST call production service and repository classes directly"
- **User Directive:** "Tests MUST NOT reimplement any business or calculation logic inside test bodies"
- **User Directive:** "Mocks limited to external boundaries: file I/O, downstream service calls, database"
- **User Directive:** "Each test method asserts a specific behavior of the production class under test"
- **User Directive:** "Framework: JUnit 5 (`@Test`, `@ParameterizedTest` for calculation variants), Mockito for mocks"
- **User Directive:** "Coverage target: ≥80% line coverage on all classes in the service layer"
- **User Directive:** "Test file location: `src/test/java` mirroring production package structure"
- **User Directive:** "Naming: `[ClassName]Test.java` for unit tests, `[ClassName]IT.java` for integration tests"
- **User Directive:** "Baseline comparison test: feed identical input files to COBOL baseline and Java implementation; diff output files — zero delta required"
- **User Directive:** "All edge cases from COBOL (overflow handling, zero-value records, end-of-file conditions) must have corresponding JUnit test cases"
- **User Directive:** "Input and output file formats and record layouts MUST remain identical"
- **User Directive:** "All financial calculation results MUST match COBOL baseline output exactly"
- **User Directive:** "External interfaces consumed by downstream systems MUST NOT change"
- **User Directive:** "No financial data written to logs at any level"
- **User Directive:** "No plaintext credentials in any configuration file"
- **User Directive:** "No float or double used for any monetary value — BigDecimal exclusively"
- **User Directive:** "BigDecimal rounding mode set to HALF_EVEN (banker's rounding) matching COBOL PICTURE clause precision"
- **User Directive:** "Migrate only what is necessary to achieve functional parity. Do not introduce patterns, abstractions, or optimizations beyond what the migration requires."

The Minimal Change Clause applies to test code as well: tests should remain idiomatic JUnit 5 + Mockito with no exotic frameworks, no custom DSLs, no code-generation, and no abstractions that exist solely to flatter the test code.

**Web search requirements** (research conducted during planning):

- Confirmed that Spring Boot 3.x's `spring-boot-starter-test` BOM transitively manages JUnit Jupiter 5.x, Mockito 5.x, AssertJ, Hamcrest, JSONassert, JsonPath, and Spring Test at versions tested together by the Spring Boot release engineering team.
- Confirmed JUnit Jupiter 5.x supports Java 17 at runtime (JUnit 6.x raises the runtime floor to Java 17 but is not required because Spring Boot 3.x BOM still pins Jupiter 5.x).
- Confirmed Mockito 5.x supports Java 11+ and is fully compatible with Java 17 bytecode and Java records.
- Confirmed JaCoCo 0.8.11+ supports Java 17 bytecode for line and branch instrumentation.

### 0.1.3 Technical Interpretation

These testing requirements translate to the following technical test implementation strategy:

| Requirement | Test Action |
| --- | --- |
| Functional parity per COBOL program | Create `[ServiceName]Test.java` under `src/test/java/com/aws/carddemo/service/` for each of the 28 migrated services; drive each public method with ASCII fixture data and AssertJ-compare the result to a captured COBOL reference output |
| Financial precision (`BigDecimal` HALF_EVEN) | Add `@ParameterizedTest` cases in service tests with `BigDecimal` inputs at the COBOL-derived scale; assert both the numeric value and the scale of the result |
| Service-layer `≥80%` line coverage | Configure JaCoCo plugin with a `<rule>` block that fails the build when any class under `com.aws.carddemo.service.**` drops below `LINE 0.80` coverage |
| Require Test Coverage rule | Every test injects the real `@Service` bean (unit tests use `new ServiceImpl(mockA, mockB)`; integration tests use `@Autowired`) and asserts on its real output; mocks restricted to JPA repositories, `FlatFileItemReader/Writer`, AWS SDK clients, and external HTTP clients |
| Batch step/job parity | Create `[JobName]IT.java` under `src/test/java/com/aws/carddemo/batch/` using `@SpringBatchTest` + `JobLauncherTestUtils` against Testcontainers PostgreSQL and golden fixtures |
| Edge case coverage | One `@ParameterizedTest` per edge case category with `@CsvFileSource` pointing at curated edge-case CSV under `src/test/resources/fixtures/edge/` |
| Baseline parity | Create `[JobName]BaselineParityIT.java` test classes that run the Spring Batch job, then `BaselineDiffUtil.assertByteEqual(actualFile, expectedFile)` against a captured COBOL reference output stored under `src/test/resources/baseline/expected/` |

The mapping pattern is: **to verify [requirement], we will create/extend [test file] that drives [production class] with [fixture data] and asserts [observable behaviour].**

### 0.1.4 Coverage Requirements Interpretation

The user prompt states an explicit target: **`≥80%` line coverage on all classes in the service layer**. To achieve comprehensive testing, coverage should include the following dimensions, all enforced via the JaCoCo `check` goal in `pom.xml`:

| Coverage Dimension | Target | Rationale |
| --- | --- | --- |
| Service-layer line coverage | ≥80% | Explicit user mandate |
| Service-layer branch coverage | ≥70% | Industry standard for financial-system services with multi-branch validation cascades (F-013 has 4 stages with reject codes 100–103) |
| Repository-layer line coverage | ≥75% | Repository methods are mostly Spring-generated; explicit IT coverage focuses on custom queries and `@Modifying` updates |
| REST controller line coverage | ≥80% | Each of the 8 controllers replaces a BMS screen; `@WebMvcTest` slice tests assert HTTP semantics |
| Batch processor line coverage | ≥85% | The 5 batch processors carry the financial-calculation correctness burden (interest, posting, statement, report) |
| Validation/mapper utility line coverage | ≥90% | Small surface area, high criticality — easy to fully cover |
| Per-COBOL edge case | 100% of documented edges | Reject codes 100–109, ZEROAPR, DEFAULT group fallback, overflow, zero-value, EOF, null/empty input |
| Baseline parity | 100% of batch jobs | Every Spring Batch `Job` has one `[JobName]BaselineParityIT` with zero-delta assertion |

Implicit coverage expectations derived from the repository context:

- Coverage must include every reject path enumerated in the existing tech spec § 2.1 Feature Catalog (e.g., F-013 four-stage validation cascade).
- Coverage must include both the `HAPPY-PATH` and the `SAD-PATH` for every COBOL `EVALUATE`/`WHEN` group migrated to a Java `switch` expression or `if/else` chain.
- Coverage must include every VSAM file-status code that the source COBOL inspects (`STATUS '00'`, `'10'`, `'23'`, `'35'`, etc.) by exercising the `FileStatusMapper`.
- Coverage must include both the `@Version` increment path and the `OptimisticLockingFailureException` path in `COACTUPC`/`COCRDUPC` migrations.
- Coverage must include both commit and rollback paths in `@Transactional` methods that replace `SYNCPOINT ROLLBACK` blocks.

## 0.2 Test Discovery and Analysis

### 0.2.1 Existing Test Infrastructure Assessment

Repository analysis reveals a **pure COBOL/JCL mainframe codebase with zero existing Java test infrastructure**. An exhaustive scan of the repository root for the standard set of test-tooling artefacts produced no matches:

- No `pom.xml`, `build.gradle`, `build.gradle.kts`, or `settings.gradle` (no Maven/Gradle project descriptor)
- No `*.java` files anywhere under the repository root (no production or test Java source)
- No `*Test.java`, `*IT.java`, `*Spec.*`, `*_test.*`, `*test_*` files (no test classes in any language)
- No `jest.config.*`, `pytest.ini`, `.mocharc.*`, `karma.conf.*`, `junit-platform.properties` (no test-runner configuration)
- No `tests/` or `test/` or `spec/` directories anywhere outside `.git`

The repository instead contains the COBOL/JCL artefacts that will become the migration source-of-truth:

| Artefact Type | Location | Count | Test Relevance |
| --- | --- | --- | --- |
| COBOL programs (`.cbl`/`.cob`) | `app/cbl/` | 28 | Each becomes one Java service class requiring one `[ServiceName]Test.java` |
| Copybooks (`.cpy`) | `app/cpy/` | 28 | Each becomes a Java model class (POJO or `record`) — implicitly covered by service tests |
| JCL job definitions | `app/jcl/` | 29 | Each becomes a Spring Batch `Job` requiring one `[JobName]IT.java` + one `[JobName]BaselineParityIT.java` |
| BMS mapsets | `app/bms/` | 17 | Each becomes a REST controller endpoint group requiring `@WebMvcTest`-driven controller tests |
| ASCII data fixtures | `app/data/ASCII/` | 9 | Canonical "golden input" datasets — copied into `src/test/resources/baseline/input/` |
| EBCDIC data fixtures | `app/data/EBCDIC/` | 12 | Mainframe-format reference for binary-equivalence checks |
| Procs | `app/proc/` | 2 | Reference-only for batch job composition |
| CSD definitions | `app/csd/` | 1 | Reference-only for online-program registry semantics |

The existing tech spec § 6.6 Testing Strategy already documents an aspirational test pyramid (729 unit + 131 integration + 28 E2E = 888 tests at 81.5% line coverage) intended for the migrated codebase. The current Action Plan must materialise that strategy under the user-mandated **Java 17 LTS + Spring Boot 3.x** runtime envelope. Because no Java source or test files exist on disk at this point, all 888 test files referenced in § 6.6 must be **created** as part of execution.

Because no test infrastructure exists today, the following baseline values apply:

- **Current testing framework:** *None* — to be introduced as **JUnit Jupiter 5.10.x** (BOM-managed by `spring-boot-starter-test`)
- **Current test runner configuration location:** *None* — to be created at `pom.xml` (Surefire 3.x + Failsafe 3.x plugin blocks) and `src/test/resources/junit-platform.properties`
- **Coverage tools in use:** *None* — to be introduced as **JaCoCo 0.8.11+** Maven plugin
- **Mock/stub libraries detected:** *None* — to be introduced as **Mockito 5.x** (BOM-managed)
- **Test data fixtures or factories present:** **9 ASCII + 12 EBCDIC fixtures** in `app/data/` — to be reused as canonical golden datasets

Inferred test infrastructure (from existing tech spec § 6.6, to be created on disk):

- **JUnit Platform launcher**: BOM-managed transitively
- **AssertJ 3.24+**: BOM-managed; fluent assertions adopted as project convention
- **Spring Batch Test** (`spring-batch-test`): adopted for `@SpringBatchTest`-driven batch ITs
- **Testcontainers BOM 2.0.3**: PostgreSQL 16 + LocalStack Pro modules adopted for repository and AWS ITs
- **Flyway Test extension** (or Spring Boot Flyway autoconfiguration): adopted for test-data hydration

### 0.2.2 Web Search Research Conducted

The following research questions were investigated to validate technology choices and to align the test stack with current best practices:

- **Spring Boot 3.x BOM-managed JUnit and Mockito versions.** Search results confirm that the Spring Boot 3.x `spring-boot-starter-test` umbrella dependency transitively manages compatible versions of <cite index="8-1,8-2">JUnit 5, Mockito, AssertJ, Hamcrest, Spring Test, and several other testing libraries, with the parent POM ensuring these libraries are compatible versions that have been tested together by the Spring Boot team</cite>. The implication for this Action Plan is that test dependencies should be declared **without explicit version pins**, allowing the BOM to resolve them — eliminating an entire class of version-skew defects.
- **JUnit 5 + Java 17 runtime compatibility.** Search results confirm that <cite index="7-1,7-2">JUnit requires Java 17 (or higher) at runtime, but tests can still cover code compiled with previous JDKs</cite>. Because the migration target is Java 17 LTS, this constraint is satisfied trivially.
- **Mockito 5.x + Java 17 compatibility.** Search results confirm Mockito 5.x is the current major line, and the Mockito project's recommended dependency declaration is `mockito-core:5.+` for projects that do not manage versions through a BOM.
- **Spring Boot 3.x + Java 17 minimum.** Search results confirm that <cite index="4-1">Spring Boot 3.3+ requires at least Java 17</cite>, satisfying the user-mandated Java 17 LTS runtime.
- **`@RunWith` → `@ExtendWith` migration.** Search results confirm that the JUnit 5 mockito integration pattern uses `@ExtendWith(MockitoExtension.class)` rather than the legacy `@RunWith(MockitoJUnitRunner.class)`. All generated test classes will follow this idiom.
- **Static method mocking strategy.** Search results indicate that <cite index="6-17,6-18">PowerMock is not ported to JUnit 5, but Mockito has supported static-method mocking since 3.4.x</cite>. The CardDemo migration plan therefore avoids PowerMock entirely; static mocks (rare — only at the `Files`/`Paths` boundary) use `Mockito.mockStatic`.
- **Industry best practice for `spring-boot-starter-test` scope.** Search results confirm that <cite index="8-14,8-15">test dependencies must be in `test` scope to avoid leaking byte-code manipulation libraries into the production JAR</cite>. Every test dependency in this Action Plan is declared with `<scope>test</scope>`.
- **Spring Batch 5.x test slice.** `spring-batch-test` provides `JobLauncherTestUtils` and `JobRepositoryTestUtils` to drive `Job` and `Step` beans in isolation; this is the canonical pattern for the 5 batch-job ITs.
- **Testcontainers PostgreSQL pattern.** The `@Testcontainers` + `@Container` annotations combined with `@DynamicPropertySource` is the canonical pattern for hydrating Spring's `DataSource` against an ephemeral PostgreSQL instance for repository and end-to-end ITs.
- **JaCoCo Maven plugin coverage rule.** The `<rule>` element under `<configuration>` of the `jacoco-maven-plugin`'s `check` goal allows declarative enforcement of the `≥80%` line-coverage floor scoped to packages matching `com.aws.carddemo.service.**`.

## 0.3 Testing Scope Analysis

### 0.3.1 Test Target Identification

#### Primary Code to be Tested

The 28 COBOL programs in `app/cbl/` translate one-for-one to Java classes under `com.aws.carddemo.*`. Each Java class is a test target. The table below maps every COBOL `PROGRAM-ID` to its target Java class, the test layer that owns its primary coverage, and the categories of tests required.

| COBOL Program | Function | Target Java Class | Layer | Required Test Categories |
| --- | --- | --- | --- | --- |
| CBACT01C | Read & print account data | `com.aws.carddemo.batch.AccountFileProcessor` | Batch processor | Unit (happy, EOF, malformed record), Integration |
| CBACT02C | Read & print card data | `com.aws.carddemo.batch.CardFileProcessor` | Batch processor | Unit (happy, EOF, malformed record), Integration |
| CBACT03C | Read & print account cross-reference | `com.aws.carddemo.batch.CardXrefFileProcessor` | Batch processor | Unit (happy, EOF), Integration |
| CBACT04C | Interest calculator | `com.aws.carddemo.batch.InterestCalculationProcessor` | Batch processor | Unit (happy, ZEROAPR skip, DEFAULT group fallback, zero balance, scale), `@ParameterizedTest`, Baseline parity IT |
| CBCUS01C | Read & print customer data | `com.aws.carddemo.batch.CustomerFileProcessor` | Batch processor | Unit (happy, EOF), Integration |
| CBTRN01C | Validate daily transactions | `com.aws.carddemo.batch.TransactionValidationProcessor` | Batch processor | Unit (per-reject-code), `@ParameterizedTest` |
| CBTRN02C | Post records from daily transaction | `com.aws.carddemo.batch.TransactionPostingProcessor` | Batch processor | Unit (per-reject-code 100–103, dual-write commit, dual-write rollback), `@ParameterizedTest`, Baseline parity IT |
| CBTRN03C | Print transaction-detail report | `com.aws.carddemo.batch.TransactionReportProcessor` | Batch processor | Unit (formatting, page break, EOF), Baseline parity IT |
| CBSTM03A | Print account statements | `com.aws.carddemo.batch.StatementProcessor` | Batch processor | Unit (per-customer aggregation, dual-output text + HTML), Baseline parity IT |
| CBSTM03B | Statement file processing | `com.aws.carddemo.batch.StatementFileProcessor` | Batch processor | Unit (file orchestration), Integration |
| COACTUPC | Account update | `com.aws.carddemo.service.AccountUpdateService` | Service | Unit (happy, optimistic-lock conflict, validation reject, SYNCPOINT-ROLLBACK parity), Controller IT |
| COACTVWC | Account view | `com.aws.carddemo.service.AccountViewService` | Service | Unit (happy, account not found), Controller IT |
| COADM01C | Admin menu | `com.aws.carddemo.service.AdminMenuService` | Service | Unit (option dispatch), Controller IT |
| COBIL00C | Bill payment | `com.aws.carddemo.service.BillPaymentService` | Service | Unit (happy, zero balance reject, account-not-found), Controller IT |
| COCRDLIC | List credit cards | `com.aws.carddemo.service.CardListService` | Service | Unit (paging, empty-result, filter), Controller IT |
| COCRDSLC | Card detail view | `com.aws.carddemo.service.CardDetailService` | Service | Unit (happy, card-not-found), Controller IT |
| COCRDUPC | Card update | `com.aws.carddemo.service.CardUpdateService` | Service | Unit (happy, optimistic-lock conflict, validation reject), Controller IT |
| COMEN01C | Main menu (regular users) | `com.aws.carddemo.service.MainMenuService` | Service | Unit (option dispatch by role), Controller IT |
| CORPT00C | Report submission | `com.aws.carddemo.service.ReportSubmissionService` | Service | Unit (job-launch happy, parameter validation), Controller IT |
| COSGN00C | Sign-on | `com.aws.carddemo.service.AuthenticationService` | Service | Unit (happy, BCrypt mismatch, locked user, unknown user), Controller IT |
| COTRN00C | Transaction list | `com.aws.carddemo.service.TransactionListService` | Service | Unit (paging, empty-result, filter), Controller IT |
| COTRN01C | Transaction view | `com.aws.carddemo.service.TransactionDetailService` | Service | Unit (happy, txn-not-found), Controller IT |
| COTRN02C | Transaction add | `com.aws.carddemo.service.TransactionAddService` | Service | Unit (happy, validation reject, sequence collision), Controller IT |
| COUSR00C | User list | `com.aws.carddemo.service.UserListService` | Service | Unit (paging, role filter), Controller IT |
| COUSR01C | User add | `com.aws.carddemo.service.UserAddService` | Service | Unit (happy, duplicate ID reject, password-policy reject), Controller IT |
| COUSR02C | User update | `com.aws.carddemo.service.UserUpdateService` | Service | Unit (happy, optimistic-lock conflict), Controller IT |
| COUSR03C | User delete | `com.aws.carddemo.service.UserDeleteService` | Service | Unit (happy, user-not-found), Controller IT |
| CSUTLDTC | CEEDAYS date validation | `com.aws.carddemo.validation.DateValidationService` | Validation utility | Unit (`@ParameterizedTest` across leap, century, format variants) |

Supporting classes not derived from a single `PROGRAM-ID` but required by the migration architecture:

| Target Java Class | Source | Required Test Categories |
| --- | --- | --- |
| `com.aws.carddemo.validation.ValidationLookupService` | Replaces `CSLKPCDY.cpy` lookup table (NANPA, state, ZIP) | Unit (`@ParameterizedTest` across area-code, state, ZIP variants) |
| `com.aws.carddemo.io.FileStatusMapper` | Replaces VSAM `STATUS` code interpretation | Unit (`@ParameterizedTest` across status codes `00`, `10`, `23`, `35`, `92`, `97`) |
| `com.aws.carddemo.batch.CombineTransactionsProcessor` | Replaces COMBTRAN.jcl DFSORT step (no COBOL program) | Unit (merge ordering, dedup, key collision) |

#### Existing Test File Mapping

| Source File | Existing Test File | Test Categories Present |
| --- | --- | --- |
| (any production source file) | *None — repository contains no existing Java tests* | *None* |

Because no Java source or test files exist on disk today, every entry in the file-by-file plan in § 0.5 is **CREATE**.

#### Dependencies Requiring Mocking

External boundaries that must be mocked under the Require Test Coverage rule (mocks limited to these — never internal business logic):

- **JPA repositories** (`AccountRepository`, `CardRepository`, `CustomerRepository`, `TransactionRepository`, `CardXrefRepository`, `TransactionCategoryBalanceRepository`, `DiscountGroupRepository`, `TransactionCategoryRepository`, `TransactionTypeRepository`, `UserSecurityRepository`): mocked in service-layer unit tests with `@Mock`; real in repository ITs against Testcontainers PostgreSQL.
- **Spring Batch `FlatFileItemReader` / `FlatFileItemWriter`**: mocked in processor unit tests; real in batch job ITs.
- **AWS SDK clients** (`S3Client`, `LambdaClient`, `SesClient`, `SqsClient` if applicable): mocked at unit level; real against LocalStack Pro in integration tests.
- **Spring Security `AuthenticationManager`** and `PasswordEncoder`: real in service tests (BCrypt is fast); mocked only when verifying the auth-failure path requires deterministic exception throw.
- **Clock / `LocalDate.now()`** for date-sensitive logic (e.g., interest-calculation date stamping): injected as a `Clock` bean and mocked with `Clock.fixed(...)`.
- **Random / sequence generators** for transaction-ID generation: injected behind an interface and mocked deterministically.
- **External HTTP clients** (none currently — the migration adds no outbound HTTP integrations beyond AWS SDK).

### 0.3.2 Version Compatibility Research

Based on the current Java version (Java 17 LTS) and the user-mandated Spring Boot 3.x line, the recommended testing stack is:

| Component | Recommended Version | BOM Source | Rationale |
| --- | --- | --- | --- |
| JUnit Jupiter (`junit-jupiter`) | 5.10.x | `spring-boot-dependencies` BOM | <cite index="8-3,8-4">spring-boot-starter-test brings JUnit 5 in versions tested together by the Spring Boot team</cite>; Java 17 satisfies the runtime floor |
| Mockito Core (`mockito-core`) | 5.7+ | `spring-boot-dependencies` BOM | Mockito 5.x is the recommended major line; the Mockito project's installation guidance is <cite index="9-14,9-15">declaring `mockito-core:5.+` via the build system</cite> |
| Mockito JUnit Jupiter (`mockito-junit-jupiter`) | 5.7+ (matched to `mockito-core`) | `spring-boot-dependencies` BOM | Provides `MockitoExtension` for `@ExtendWith(MockitoExtension.class)` |
| AssertJ (`assertj-core`) | 3.24+ | `spring-boot-dependencies` BOM | Fluent assertions, idiomatic with Spring Boot Test |
| Hamcrest (`hamcrest`) | 2.2 | `spring-boot-dependencies` BOM | Transitively required by JUnit |
| Spring Boot Test (`spring-boot-starter-test`) | 3.x line latest | direct dependency | Umbrella starter that <cite index="8-1">brings in JUnit 5, Mockito, AssertJ, Hamcrest, Spring Test, and several other testing libraries</cite> |
| Spring Batch Test (`spring-batch-test`) | 5.x (Spring Boot-managed) | `spring-boot-dependencies` BOM | Required for `@SpringBatchTest`, `JobLauncherTestUtils` |
| Spring Security Test (`spring-security-test`) | 6.x (Spring Boot-managed) | `spring-boot-dependencies` BOM | Required for `@WithMockUser` and SecurityMockMvcConfigurers |
| Testcontainers (`testcontainers`, `postgresql`, `localstack`) | 1.19+ (Testcontainers BOM 2.0.3 if used) | `testcontainers-bom` | Ephemeral PostgreSQL + LocalStack Pro for ITs |
| Awaitility (`awaitility`) | 4.2+ | `spring-boot-dependencies` BOM | Asynchronous assertions for batch-completion polling |
| JaCoCo (`jacoco-maven-plugin`) | 0.8.11+ | direct plugin | Java 17 bytecode instrumentation; supports `<rule>` enforcement of coverage thresholds |
| Maven Surefire (`maven-surefire-plugin`) | 3.2+ | direct plugin | Surefire 3.x ships the JUnit Platform provider by default |
| Maven Failsafe (`maven-failsafe-plugin`) | 3.2+ | direct plugin | `*IT.java` suffix convention; runs in the `verify` phase |

**No version conflicts identified.** The Spring Boot 3.x BOM resolves all transitive testing dependencies coherently. Explicit version pins are added **only** for plugins (`jacoco-maven-plugin`, `maven-surefire-plugin`, `maven-failsafe-plugin`) and the Testcontainers BOM, because those are not transitively pulled by `spring-boot-starter-test`.

**Mockito Java 17 + interface-mock note.** Search results surface a known Mockito issue where <cite index="1-3,1-4">tests that mock interfaces (e.g., `@Mock private UserRepository userRepository;`) on Java 17 with older Mockito versions can throw "Mockito cannot mock this class: interface" exceptions</cite>. The mitigation is to ensure `mockito-core` is the BOM-managed 5.x line and that no transitive 4.x pin exists in the dependency tree. The CardDemo `pom.xml` therefore declares `spring-boot-starter-test` only and lets the BOM resolve Mockito.

## 0.4 Test Implementation Design

### 0.4.1 Test Strategy Selection

The CardDemo migration test suite follows a four-layer strategy. Each layer answers a distinct correctness question; together they enforce the user's `≥80%` line-coverage floor and the byte-identical baseline-parity requirement.

| Layer | Purpose | Spring Test Slice | Mock Boundary | Approximate Test Count |
| --- | --- | --- | --- | --- |
| Unit tests | Verify isolated business logic per service / processor / validator / mapper | None — `@ExtendWith(MockitoExtension.class)` only | All collaborators mocked; system under test is the real production class | ~729 |
| Integration tests | Verify cross-component wiring, JPA queries, Spring Batch step semantics, controller HTTP semantics | `@SpringBootTest`, `@DataJpaTest`, `@WebMvcTest`, `@SpringBatchTest` | Real Spring context + Testcontainers PostgreSQL/LocalStack; only third-party HTTP boundaries mocked | ~131 |
| Baseline parity ITs | Verify byte-identical output vs COBOL baseline for every batch job | `@SpringBatchTest` + custom `BaselineDiffUtil` | Real Spring Batch context | 5 (one per batch job) |
| End-to-end ITs | Verify multi-step user/operator journeys (online sign-on → menu → operation → logout; batch pipeline POSTTRAN → INTCALC → COMBTRAN → CREASTMT ∥ TRANREPT) | `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` + Testcontainers | Real Spring context end-to-end | ~28 |

#### Test Types to Implement

- **Unit tests:** focus on isolated components. Each migrated service method is exercised with a curated set of inputs that triggers each `EVALUATE/WHEN` branch, each `PERFORM` paragraph, each `IF/ELSE`, and each `COMPUTE` step inherited from the COBOL source. Mockito boundary mocks replace JPA repositories, file readers/writers, and AWS SDK clients.
- **Integration tests:** focus on component interactions. Repository ITs run real JPA queries against Testcontainers PostgreSQL 16 with Flyway-applied schema. Batch ITs run a complete Spring Batch `Job` end-to-end. Controller ITs use `@WebMvcTest` slice with `MockMvc` to assert HTTP status, headers, and body.
- **Edge case tests:** address boundary conditions. Each `@ParameterizedTest` is driven by `@CsvFileSource` files under `src/test/resources/fixtures/edge/` capturing reject codes, zero-value records, overflow boundaries, EOF, null/empty input, and date-format variants.
- **Error handling tests:** verify failure scenarios. Each service test includes negative-path assertions: validation rejects raise specific `RejectException` subclasses with the COBOL-equivalent reject code; optimistic-lock collisions raise `OptimisticLockingFailureException`; transactional dual-writes roll back atomically.

### 0.4.2 Test Case Blueprint

For each migrated component, the test class follows the blueprint below. The template is illustrated with two representative components (the rest follow the same pattern):

#### Blueprint A — Service-Layer Class (Example: `AuthenticationService`, replaces `COSGN00C`)

```
Component: AuthenticationService
Production class: com.aws.carddemo.service.AuthenticationService
Test class:       com.aws.carddemo.service.AuthenticationServiceTest

Test Categories:
- Happy path:
    * authenticate_validUserValidPassword_returnsUserSession
    * authenticate_adminUserValidPassword_returnsAdminSession
- Edge cases:
    * authenticate_emptyUserId_throwsValidationException
    * authenticate_emptyPassword_throwsValidationException
    * authenticate_userIdMaxLength_acceptsBoundary
- Error cases:
    * authenticate_unknownUser_throwsAuthenticationException
    * authenticate_passwordMismatch_throwsAuthenticationException (BCrypt verify path)
    * authenticate_lockedUser_throwsAccountLockedException
    * authenticate_dbUnavailable_propagatesDataAccessException
- Performance boundaries: not applicable (sub-second per call)

Mocks:
- UserSecurityRepository (boundary — database)
- PasswordEncoder (real BCrypt instance; deterministic salt provided by test fixture)
- Clock (fixed at 2024-01-15T00:00:00Z for deterministic session timestamps)
```

#### Blueprint B — Batch Processor (Example: `InterestCalculationProcessor`, replaces `CBACT04C`)

```
Component: InterestCalculationProcessor
Production class: com.aws.carddemo.batch.InterestCalculationProcessor
Test class:       com.aws.carddemo.batch.InterestCalculationProcessorTest

Test Categories:
- Happy path:
    * process_standardBalanceStandardRate_computesInterestHalfEven
        (balance=1000.00, rate=18.00 -> interest=15.00 at scale 2)
    * process_largeBalanceFullPrecision_preservesScale
        (PIC 9(7)V99 boundary: balance=9999999.99, rate=24.00)
- Edge cases:
    * process_zeroBalance_returnsZeroInterest
    * process_zeroRate_returnsZeroInterest (covers ZEROAPR group)
    * process_missingDiscountGroup_fallsBackToDefaultGroup
    * process_rateRequiresBankerRounding_appliesHalfEven
        (balance=100.005, rate=10.00 -> verify HALF_EVEN behaviour vs HALF_UP)
- Error cases:
    * process_nullBalance_throwsValidationException
    * process_negativeBalance_recordsRejectAndContinues
    * process_overflow_handlesViaBigDecimalMathContext
- Baseline parity:
    * (covered separately in InterestCalculationBaselineParityIT)

Mocks:
- TransactionCategoryBalanceRepository (boundary — database)
- DiscountGroupRepository (boundary — database)
- ItemWriter<TransactionCategoryBalance> (boundary — file/database)
```

The same pattern is applied to each of the 28 migrated programs plus supporting components.

### 0.4.3 Existing Test Extension Strategy

Because no Java tests exist on disk today, there is no extension work in the traditional sense. All test files are CREATE.

- **Tests to extend:** *None* — no pre-existing Java tests to enhance.
- **Tests to refactor:** *None* — no legacy test patterns to modernise.
- **Tests to fix:** *None* — no broken test scenarios from prior runs.

However, the AAP recognises that an **aspirational** test suite is documented in the existing tech spec § 6.6 (888 tests at 81.5% coverage). The CREATE plan in § 0.5 explicitly aligns with that aspirational structure: the test class names, package layout (`src/test/java/com/aws/carddemo/{service,batch,repository,controller,validation,io,e2e}`), and test categories (unit / integration / E2E / baseline parity) all match § 6.6's documented breakdown so the implementation can fulfil that strategy under the user-mandated Java 17 / Spring Boot 3.x runtime envelope.

### 0.4.4 Test Data and Fixtures Design

#### Required Test Data Structures

| Fixture Category | Source | Destination | Format | Purpose |
| --- | --- | --- | --- | --- |
| Account master records | `app/data/ASCII/acctdata.txt` (50 records, ~15 KB) | `src/test/resources/baseline/input/acctdata.txt` | Flat fixed-width ASCII | Drive `AccountRepository` / `AccountFileProcessor` tests; seed `accounts` table in repository ITs |
| Card master records | `app/data/ASCII/carddata.txt` (50 records, ~7.5 KB) | `src/test/resources/baseline/input/carddata.txt` | Flat fixed-width ASCII | Drive `CardRepository` / `CardFileProcessor` tests |
| Card cross-reference | `app/data/ASCII/cardxref.txt` (50 records, ~1.8 KB) | `src/test/resources/baseline/input/cardxref.txt` | Flat fixed-width ASCII | Drive `CardXrefRepository` / `CardXrefFileProcessor` tests |
| Customer master records | `app/data/ASCII/custdata.txt` (50 records, ~25 KB) | `src/test/resources/baseline/input/custdata.txt` | Flat fixed-width ASCII | Drive `CustomerRepository` / `CustomerFileProcessor` tests |
| Daily transactions | `app/data/ASCII/dailytran.txt` (~105 KB) | `src/test/resources/baseline/input/dailytran.txt` | Flat fixed-width ASCII | Drive `TransactionPostingProcessor`, `TransactionValidationProcessor`, `TransactionReportProcessor` tests |
| Disclosure groups | `app/data/ASCII/discgrp.txt` (51 records incl. DEFAULT and ZEROAPR, ~2.6 KB) | `src/test/resources/baseline/input/discgrp.txt` | Flat fixed-width ASCII | Drive `InterestCalculationProcessor` happy path + DEFAULT fallback + ZEROAPR skip |
| Transaction category balances | `app/data/ASCII/tcatbal.txt` (50 records, ~2.5 KB) | `src/test/resources/baseline/input/tcatbal.txt` | Flat fixed-width ASCII | Drive `InterestCalculationProcessor` and `CombineTransactionsProcessor` tests |
| Transaction categories | `app/data/ASCII/trancatg.txt` (18 categories) | `src/test/resources/baseline/input/trancatg.txt` | Flat fixed-width ASCII | Reference data for category-key validation |
| Transaction types | `app/data/ASCII/trantype.txt` (7 types) | `src/test/resources/baseline/input/trantype.txt` | Flat fixed-width ASCII | Reference data for type-key validation |
| Edge-case CSVs | *Authored fresh* | `src/test/resources/fixtures/edge/*.csv` | CSV with header | Drive `@ParameterizedTest` cases for reject codes 100–109, ZEROAPR, DEFAULT, overflow, EOF, null/empty |
| Expected outputs (goldens) | *Captured by running COBOL baseline against ASCII input* | `src/test/resources/baseline/expected/*` | Flat fixed-width ASCII, identical layout to inputs | Right-hand operand for `BaselineDiffUtil.assertByteEqual` |

#### Fixture Organization Strategy

- **`src/test/resources/baseline/input/`** holds canonical golden inputs (one-for-one copies of `app/data/ASCII/*.txt`).
- **`src/test/resources/baseline/expected/`** holds golden outputs captured from the COBOL baseline before the migration begins.
- **`src/test/resources/fixtures/edge/`** holds curated CSV files for `@ParameterizedTest` data-driven edge cases (one CSV per edge-case category, e.g., `interest_zero_balance.csv`, `posting_reject_codes.csv`).
- **`src/test/resources/db/migration/test/`** holds optional test-specific Flyway migrations (kept minimal; the production Flyway scripts under `src/main/resources/db/migration/V1__schema.sql`, `V2__indexes.sql`, `V3__seed.sql` are reused for ITs).
- **`src/test/java/com/aws/carddemo/testsupport/`** holds shared test utilities: `FixtureLoader`, `BaselineDiffUtil`, `TestFixtures` (constants), `AbstractBatchIT` base class.

#### Mock Object Specifications

- **`@Mock` repositories** use Mockito's default deep-stub for chained Spring Data calls only when strictly necessary; otherwise return explicit `Optional`/`List` values.
- **`@MockBean` JPA repositories in controller ITs** stand in for the persistence layer so the slice test does not load `EntityManager`.
- **Mockito strictness** is set to `STRICT_STUBS` (the JUnit Jupiter default) so any unused stub fails the test — surfaces Require-Test-Coverage-rule violations early.
- **Argument captors** (`ArgumentCaptor<>`) are used to assert on entity values passed to repository `save()` calls, ensuring the production class — not the test — computes the value being persisted.

#### Test Database / State Management Approach

- **Unit tests:** no database — Mockito-stubbed repositories.
- **Repository ITs:** Testcontainers PostgreSQL 16, fresh container per test class (`@Testcontainers` + `@Container static PostgreSQLContainer<?> POSTGRES`), Flyway-applied schema and seed data, transactional rollback after each `@Test`.
- **Batch ITs and end-to-end ITs:** Testcontainers PostgreSQL 16, shared container per test class via Spring's context cache, `JobRepositoryTestUtils.removeJobExecutions()` between tests.
- **Baseline parity ITs:** Testcontainers PostgreSQL 16 hydrated with the canonical ASCII fixtures via Flyway `V3__seed.sql`, then run the Spring Batch `Job` to produce an actual output file, then diff against the captured golden output.

## 0.5 Test File Transformation Mapping

### 0.5.1 File-by-File Test Plan

The following table enumerates every test artefact in scope for this migration. The **Target Test File** is listed first per AAP testing-flavor convention. The **Source File / Test** column identifies the COBOL artefact (for CREATE entries derived from migration) or the Java production class (for unit-test CREATE entries). Every entry is **CREATE**; there are no UPDATE/DELETE/REFERENCE entries because no Java tests exist on disk today (the COBOL programs themselves are listed under § 0.5.5 as cross-file dependencies / truth sources).

#### Service-Layer Unit Tests (one per migrated COBOL program)

| Target Test File | Transformation | Source File / Test | Purpose / Changes |
| --- | --- | --- | --- |
| `src/test/java/com/aws/carddemo/service/AuthenticationServiceTest.java` | CREATE | `app/cbl/COSGN00C.cbl` | Cover BCrypt verify happy path, password mismatch, unknown user, locked user, validation rejects, deterministic clock for session timestamps |
| `src/test/java/com/aws/carddemo/service/MainMenuServiceTest.java` | CREATE | `app/cbl/COMEN01C.cbl` | Cover option-dispatch by role, invalid-option reject, max-option boundary |
| `src/test/java/com/aws/carddemo/service/AdminMenuServiceTest.java` | CREATE | `app/cbl/COADM01C.cbl` | Cover admin-option dispatch, non-admin-rejected paths |
| `src/test/java/com/aws/carddemo/service/AccountViewServiceTest.java` | CREATE | `app/cbl/COACTVWC.cbl` | Cover happy lookup, account-not-found, cross-reference hydration |
| `src/test/java/com/aws/carddemo/service/AccountUpdateServiceTest.java` | CREATE | `app/cbl/COACTUPC.cbl` (4,236 lines) | Cover happy update, optimistic-lock conflict (`@Version`), `@Transactional(rollbackFor=Exception.class)` parity with SYNCPOINT ROLLBACK, validation rejects, dual-write Account+Customer |
| `src/test/java/com/aws/carddemo/service/CardListServiceTest.java` | CREATE | `app/cbl/COCRDLIC.cbl` (1,459 lines) | Cover paging (page size, last page), empty result, account/customer filter |
| `src/test/java/com/aws/carddemo/service/CardDetailServiceTest.java` | CREATE | `app/cbl/COCRDSLC.cbl` (887 lines) | Cover happy detail lookup, card-not-found, expired-card display flag |
| `src/test/java/com/aws/carddemo/service/CardUpdateServiceTest.java` | CREATE | `app/cbl/COCRDUPC.cbl` (1,560 lines) | Cover happy update, optimistic-lock conflict, validation rejects (card number format, expiration date) |
| `src/test/java/com/aws/carddemo/service/TransactionListServiceTest.java` | CREATE | `app/cbl/COTRN00C.cbl` (699 lines) | Cover paging, empty result, account-key filter, card-key filter |
| `src/test/java/com/aws/carddemo/service/TransactionDetailServiceTest.java` | CREATE | `app/cbl/COTRN01C.cbl` (330 lines) | Cover happy lookup, txn-not-found |
| `src/test/java/com/aws/carddemo/service/TransactionAddServiceTest.java` | CREATE | `app/cbl/COTRN02C.cbl` (783 lines) | Cover happy add, sequence collision, validation rejects (amount overflow, category lookup), audit timestamp injection |
| `src/test/java/com/aws/carddemo/service/BillPaymentServiceTest.java` | CREATE | `app/cbl/COBIL00C.cbl` (572 lines) | Cover full-balance pay-off, zero-balance reject, account-not-found, transaction record creation |
| `src/test/java/com/aws/carddemo/service/ReportSubmissionServiceTest.java` | CREATE | `app/cbl/CORPT00C.cbl` (649 lines) | Cover happy job launch, parameter validation (start/end date), TDQ-replacement async dispatch |
| `src/test/java/com/aws/carddemo/service/UserListServiceTest.java` | CREATE | `app/cbl/COUSR00C.cbl` (695 lines) | Cover paging, role filter, admin-only authorization |
| `src/test/java/com/aws/carddemo/service/UserAddServiceTest.java` | CREATE | `app/cbl/COUSR01C.cbl` (299 lines) | Cover happy add, duplicate user-ID reject, password policy reject, BCrypt-on-insert |
| `src/test/java/com/aws/carddemo/service/UserUpdateServiceTest.java` | CREATE | `app/cbl/COUSR02C.cbl` (414 lines) | Cover happy update, optimistic-lock conflict, role change, password rehash |
| `src/test/java/com/aws/carddemo/service/UserDeleteServiceTest.java` | CREATE | `app/cbl/COUSR03C.cbl` (359 lines) | Cover happy delete, user-not-found, self-delete reject |

#### Batch Processor Unit Tests

| Target Test File | Transformation | Source File / Test | Purpose / Changes |
| --- | --- | --- | --- |
| `src/test/java/com/aws/carddemo/batch/AccountFileProcessorTest.java` | CREATE | `app/cbl/CBACT01C.cbl` | Cover happy read, malformed record reject, EOF, count-vs-input parity |
| `src/test/java/com/aws/carddemo/batch/CardFileProcessorTest.java` | CREATE | `app/cbl/CBACT02C.cbl` | Cover happy read, malformed record reject, EOF |
| `src/test/java/com/aws/carddemo/batch/CardXrefFileProcessorTest.java` | CREATE | `app/cbl/CBACT03C.cbl` | Cover happy read, EOF, count parity |
| `src/test/java/com/aws/carddemo/batch/InterestCalculationProcessorTest.java` | CREATE | `app/cbl/CBACT04C.cbl` (652 lines) | Cover happy `(TRAN-CAT-BAL × DIS-INT-RATE) / 1200` HALF_EVEN, zero balance, ZEROAPR skip, DEFAULT group fallback, scale=2 preservation, HALF_EVEN vs HALF_UP discrimination |
| `src/test/java/com/aws/carddemo/batch/CustomerFileProcessorTest.java` | CREATE | `app/cbl/CBCUS01C.cbl` | Cover happy read, EOF |
| `src/test/java/com/aws/carddemo/batch/TransactionValidationProcessorTest.java` | CREATE | `app/cbl/CBTRN01C.cbl` | Cover each reject-code path (`100`, `101`, `102`, `103`), happy path |
| `src/test/java/com/aws/carddemo/batch/TransactionPostingProcessorTest.java` | CREATE | `app/cbl/CBTRN02C.cbl` (731 lines) | Cover 4-stage validation cascade (reject codes 100–103), dual-write Account+Card balance update, `@Transactional` rollback parity, fail-fast Strategy |
| `src/test/java/com/aws/carddemo/batch/TransactionReportProcessorTest.java` | CREATE | `app/cbl/CBTRN03C.cbl` (649 lines) | Cover record formatting, page break (66 lines/page), header/footer, totals, EOF |
| `src/test/java/com/aws/carddemo/batch/StatementProcessorTest.java` | CREATE | `app/cbl/CBSTM03A.cbl` (924 lines) | Cover per-customer aggregation, dual-output text+HTML, page break, customer-not-found |
| `src/test/java/com/aws/carddemo/batch/StatementFileProcessorTest.java` | CREATE | `app/cbl/CBSTM03B.cbl` (230 lines) | Cover file orchestration, dual-output writer |
| `src/test/java/com/aws/carddemo/batch/CombineTransactionsProcessorTest.java` | CREATE | `app/jcl/COMBTRAN.jcl` (DFSORT) | Cover merge ordering by composite key, dedup, key-collision handling (no COBOL program — pure DFSORT replacement) |

#### Repository Integration Tests

| Target Test File | Transformation | Source File / Test | Purpose / Changes |
| --- | --- | --- | --- |
| `src/test/java/com/aws/carddemo/repository/AccountRepositoryIT.java` | CREATE | `app/cpy/CVACT01Y.cpy` (account record layout) | Cover `findById`, `save`, optimistic locking via `@Version`, custom queries (by customer ID, by status) |
| `src/test/java/com/aws/carddemo/repository/CardRepositoryIT.java` | CREATE | `app/cpy/CVACT02Y.cpy` (card record layout) | Cover `findById`, `save`, optimistic locking, find-by-account |
| `src/test/java/com/aws/carddemo/repository/CardXrefRepositoryIT.java` | CREATE | `app/cpy/CVACT03Y.cpy` (card cross-reference) | Cover bi-directional lookup card→account and account→card |
| `src/test/java/com/aws/carddemo/repository/CustomerRepositoryIT.java` | CREATE | `app/cpy/CUSTREC.cpy` and `app/cpy/CVCUS01Y.cpy` | Cover `findById`, `save`, NANPA phone validation persistence |
| `src/test/java/com/aws/carddemo/repository/TransactionRepositoryIT.java` | CREATE | `app/cpy/CVTRA05Y.cpy` (transaction record) | Cover insert, paging query, date-range query, category-aggregation query |
| `src/test/java/com/aws/carddemo/repository/TransactionCategoryBalanceRepositoryIT.java` | CREATE | `app/cpy/CVTRA01Y.cpy` (TCATBAL record) | Cover composite-key access, interest-calculation update path |
| `src/test/java/com/aws/carddemo/repository/DiscountGroupRepositoryIT.java` | CREATE | `app/data/ASCII/discgrp.txt` | Cover lookup including DEFAULT and ZEROAPR groups |
| `src/test/java/com/aws/carddemo/repository/TransactionCategoryRepositoryIT.java` | CREATE | `app/data/ASCII/trancatg.txt` | Cover lookup of all 18 categories |
| `src/test/java/com/aws/carddemo/repository/TransactionTypeRepositoryIT.java` | CREATE | `app/data/ASCII/trantype.txt` | Cover lookup of all 7 transaction types |
| `src/test/java/com/aws/carddemo/repository/UserSecurityRepositoryIT.java` | CREATE | `app/cpy/CSUSR01Y.cpy` (USRSEC record) | Cover `findByUserId`, BCrypt-hash persistence, role retrieval |

#### Controller Integration Tests

| Target Test File | Transformation | Source File / Test | Purpose / Changes |
| --- | --- | --- | --- |
| `src/test/java/com/aws/carddemo/controller/AuthControllerTest.java` | CREATE | `app/bms/COSGN00.bms` + `app/cbl/COSGN00C.cbl` | Cover POST /api/auth/sign-on happy/fail, session cookie issuance, `@WithMockUser` for protected endpoints |
| `src/test/java/com/aws/carddemo/controller/MenuControllerTest.java` | CREATE | `app/bms/COMEN01.bms` + `app/cbl/COMEN01C.cbl` + `app/bms/COADM01.bms` + `app/cbl/COADM01C.cbl` | Cover GET menu, role-based option visibility |
| `src/test/java/com/aws/carddemo/controller/AccountControllerTest.java` | CREATE | `app/bms/COACTVW.bms` + `app/bms/COACTUP.bms` + `app/cbl/COACTVWC.cbl` + `app/cbl/COACTUPC.cbl` | Cover GET /api/accounts/{id}, PUT /api/accounts/{id}, validation 400, optimistic-lock 409 |
| `src/test/java/com/aws/carddemo/controller/CardControllerTest.java` | CREATE | `app/bms/COCRDLI.bms` + `app/bms/COCRDSL.bms` + `app/bms/COCRDUP.bms` + corresponding COBOL programs | Cover GET /api/cards, GET /api/cards/{id}, PUT /api/cards/{id} |
| `src/test/java/com/aws/carddemo/controller/TransactionControllerTest.java` | CREATE | `app/bms/COTRN00.bms` + `app/bms/COTRN01.bms` + `app/bms/COTRN02.bms` + corresponding COBOL programs | Cover GET /api/transactions, GET /api/transactions/{id}, POST /api/transactions |
| `src/test/java/com/aws/carddemo/controller/BillPaymentControllerTest.java` | CREATE | `app/bms/COBIL00.bms` + `app/cbl/COBIL00C.cbl` | Cover POST /api/bill-payment happy + zero-balance reject |
| `src/test/java/com/aws/carddemo/controller/ReportControllerTest.java` | CREATE | `app/bms/CORPT00.bms` + `app/cbl/CORPT00C.cbl` | Cover POST /api/reports/submit with date params, async dispatch verification |
| `src/test/java/com/aws/carddemo/controller/UserAdminControllerTest.java` | CREATE | `app/bms/COUSR00.bms` to `COUSR03.bms` + corresponding COBOL programs | Cover GET /api/users, POST /api/users, PUT /api/users/{id}, DELETE /api/users/{id}, admin-only authorization |

#### Batch Job Integration Tests and Baseline Parity ITs

| Target Test File | Transformation | Source File / Test | Purpose / Changes |
| --- | --- | --- | --- |
| `src/test/java/com/aws/carddemo/batch/TransactionPostingJobIT.java` | CREATE | `app/jcl/POSTTRAN.jcl` | End-to-end Spring Batch job exec via `JobLauncherTestUtils`, asserts `BatchStatus.COMPLETED`, output-file row count parity |
| `src/test/java/com/aws/carddemo/batch/TransactionPostingBaselineParityIT.java` | CREATE | `app/jcl/POSTTRAN.jcl` + golden output | Byte-identical diff: produced posting file vs captured COBOL reference under `baseline/expected/` |
| `src/test/java/com/aws/carddemo/batch/InterestCalculationJobIT.java` | CREATE | `app/jcl/INTCALC.jcl` | End-to-end interest job; asserts updated TCATBAL rows |
| `src/test/java/com/aws/carddemo/batch/InterestCalculationBaselineParityIT.java` | CREATE | `app/jcl/INTCALC.jcl` + golden output | Byte-identical diff for interest-calculation output |
| `src/test/java/com/aws/carddemo/batch/CombineTransactionsJobIT.java` | CREATE | `app/jcl/COMBTRAN.jcl` | End-to-end DFSORT-equivalent merge; asserts ordering and dedup |
| `src/test/java/com/aws/carddemo/batch/CombineTransactionsBaselineParityIT.java` | CREATE | `app/jcl/COMBTRAN.jcl` + golden output | Byte-identical diff for combined transaction file |
| `src/test/java/com/aws/carddemo/batch/StatementGenerationJobIT.java` | CREATE | `app/jcl/CREASTMT.jcl` | End-to-end statement job; asserts text and HTML output files |
| `src/test/java/com/aws/carddemo/batch/StatementGenerationBaselineParityIT.java` | CREATE | `app/jcl/CREASTMT.jcl` + golden output | Byte-identical diff for statement files |
| `src/test/java/com/aws/carddemo/batch/TransactionReportJobIT.java` | CREATE | `app/jcl/TRANREPT.jcl` | End-to-end report job; asserts report-file structure and totals |
| `src/test/java/com/aws/carddemo/batch/TransactionReportBaselineParityIT.java` | CREATE | `app/jcl/TRANREPT.jcl` + golden output | Byte-identical diff for transaction report |

#### Validation / Mapper Unit Tests

| Target Test File | Transformation | Source File / Test | Purpose / Changes |
| --- | --- | --- | --- |
| `src/test/java/com/aws/carddemo/validation/DateValidationServiceTest.java` | CREATE | `app/cbl/CSUTLDTC.cbl` (703 lines, CEEDAYS wrapper) | `@ParameterizedTest` across leap years, century rule, format variants (`YYYY-MM-DD`, `MM/DD/YYYY`, `DD-MON-YYYY`), invalid-date reject parity |
| `src/test/java/com/aws/carddemo/validation/ValidationLookupServiceTest.java` | CREATE | `app/cpy/CSLKPCDY.cpy` | `@ParameterizedTest` across NANPA area codes, US state codes, ZIP prefixes; invalid-key reject |
| `src/test/java/com/aws/carddemo/io/FileStatusMapperTest.java` | CREATE | VSAM STATUS codes referenced in COBOL programs | `@ParameterizedTest` across status codes `00`, `02`, `10`, `22`, `23`, `35`, `92`, `97`; assert mapping to specific Java exception subclasses |

#### End-to-End User Journey Tests

| Target Test File | Transformation | Source File / Test | Purpose / Changes |
| --- | --- | --- | --- |
| `src/test/java/com/aws/carddemo/e2e/OnlineTransactionE2ETest.java` | CREATE | F-001 → F-002 → F-009/F-010/F-011 journey | Sign-on → main menu → transaction list/view/add round-trip via `TestRestTemplate` |
| `src/test/java/com/aws/carddemo/e2e/BatchPipelineE2ETest.java` | CREATE | POSTTRAN → INTCALC → COMBTRAN → CREASTMT ∥ TRANREPT | Sequential exec of all batch jobs with shared Testcontainers PostgreSQL state |
| `src/test/java/com/aws/carddemo/e2e/AdminUserManagementE2ETest.java` | CREATE | F-003 → F-018 → F-019/F-020/F-021 | Admin sign-on → user list → add/update/delete |
| `src/test/java/com/aws/carddemo/e2e/GateVerificationE2ETest.java` | CREATE | Cross-cutting acceptance suite | Smoke test that every controller responds 401 without auth, 200/403 with auth |

#### Test Support Utilities

| Target Test File | Transformation | Source File / Test | Purpose / Changes |
| --- | --- | --- | --- |
| `src/test/java/com/aws/carddemo/testsupport/FixtureLoader.java` | CREATE | (new) | Utility to load `src/test/resources/baseline/input/*.txt` and `fixtures/edge/*.csv` as typed objects |
| `src/test/java/com/aws/carddemo/testsupport/BaselineDiffUtil.java` | CREATE | (new) | `assertByteEqual(Path actual, Path expected)` with line-level diff reporting on failure |
| `src/test/java/com/aws/carddemo/testsupport/TestFixtures.java` | CREATE | (new) | Constants for sample account IDs, card IDs, customer IDs, transaction IDs used across tests |
| `src/test/java/com/aws/carddemo/testsupport/AbstractBatchIT.java` | CREATE | (new) | Base class with `@SpringBatchTest` + Testcontainers PostgreSQL `@Container` boilerplate |
| `src/test/java/com/aws/carddemo/testsupport/AbstractRepositoryIT.java` | CREATE | (new) | Base class with `@DataJpaTest` + Testcontainers PostgreSQL boilerplate |

### 0.5.2 New Test Files Detail

The expanded categorisation below complements the file-by-file table above with the categories, mocks, and assertion focus required in each net-new test file.

- `src/test/java/com/aws/carddemo/service/AuthenticationServiceTest.java` — unit coverage of `COSGN00C` migration
    - **Test categories:** happy sign-on (regular user), happy sign-on (admin), unknown user, wrong password, locked user, empty/whitespace user-id, empty/whitespace password, BCrypt salt boundary
    - **Mock dependencies:** `UserSecurityRepository`, `Clock` (fixed)
    - **Assertions focus:** returned `UserSession` carries correct role and timestamp; BCrypt verify is invoked via real `PasswordEncoder`; deterministic salt produces deterministic hash for fixture user

- `src/test/java/com/aws/carddemo/batch/InterestCalculationProcessorTest.java` — unit coverage of `CBACT04C` migration
    - **Test categories:** happy interest calc (`(balance × rate) / 1200`), zero balance, ZEROAPR group skip, DEFAULT group fallback, HALF_EVEN rounding boundary (`100.005` case), scale preservation at `PIC 9(7)V99` boundary, negative balance reject
    - **Mock dependencies:** `TransactionCategoryBalanceRepository`, `DiscountGroupRepository`, `ItemWriter<TransactionCategoryBalance>`
    - **Assertions focus:** numeric value equals expected `BigDecimal`; `scale()` returns 2; `RoundingMode.HALF_EVEN` distinguishes from HALF_UP at `.5` boundary

- `src/test/java/com/aws/carddemo/batch/TransactionPostingProcessorTest.java` — unit coverage of `CBTRN02C` migration
    - **Test categories:** happy posting (account+card dual-write), each reject code (100=account-not-found, 101=card-not-found, 102=card-status-mismatch, 103=insufficient-credit-limit), validation cascade fail-fast ordering, `@Transactional` rollback on RuntimeException
    - **Mock dependencies:** `AccountRepository`, `CardRepository`, `TransactionRepository`, `RejectRepository`
    - **Assertions focus:** `ArgumentCaptor<Account>` shows post-balance equals pre-balance ± transaction amount; reject records are persisted with COBOL-equivalent reject codes; rollback path leaves all repositories untouched

- `src/test/java/com/aws/carddemo/batch/TransactionPostingBaselineParityIT.java` — baseline parity IT for POSTTRAN job
    - **Integration points:** Spring Batch `Job`, Testcontainers PostgreSQL, Flyway-seeded test schema
    - **Test data requirements:** `src/test/resources/baseline/input/dailytran.txt` driven through job; expected output at `src/test/resources/baseline/expected/posted.txt`
    - **Assertions focus:** `BaselineDiffUtil.assertByteEqual(actual, expected)` — zero delta required

- `src/test/java/com/aws/carddemo/batch/InterestCalculationBaselineParityIT.java` — baseline parity IT for INTCALC job
    - **Integration points:** Spring Batch `Job`, Testcontainers PostgreSQL, Flyway seed
    - **Test data requirements:** `tcatbal.txt`, `discgrp.txt` as input; expected output at `baseline/expected/tcatbal_after_interest.txt`
    - **Assertions focus:** zero-byte delta against captured COBOL reference

- `src/test/java/com/aws/carddemo/repository/AccountRepositoryIT.java` — JPA integration test
    - **Integration points:** Testcontainers PostgreSQL, Flyway V1+V2+V3 applied, real `EntityManager`
    - **Test data requirements:** Flyway `V3__seed.sql` (827 INSERT lines) hydrates `accounts` table from `acctdata.txt`
    - **Assertions focus:** custom queries return correct rows; `@Version` increments on update; concurrent update raises `OptimisticLockingFailureException`

- `src/test/java/com/aws/carddemo/testsupport/FixtureLoader.java` — test utility
    - **Categories:** static helper methods `loadAccountFixtures()`, `loadCardFixtures()`, `loadDailyTransactions()`, `loadEdgeCases(String csvName)`
    - **Assertions focus:** N/A (no test methods — utility class)

- `src/test/resources/fixtures/edge/interest_zero_balance.csv` — edge-case fixture
    - **Content:** CSV header `balance,rate,expectedInterest`, rows including `(0.00, 18.00, 0.00)`, `(0.00, 0.00, 0.00)`, etc.
    - **Used by:** `InterestCalculationProcessorTest#process_zeroBalance_returnsZeroInterest`

- `src/test/resources/fixtures/edge/posting_reject_codes.csv` — edge-case fixture
    - **Content:** CSV header `accountId,cardNumber,amount,expectedRejectCode`, one row per reject scenario
    - **Used by:** `TransactionPostingProcessorTest#process_invalidInput_yieldsExpectedRejectCode`

- `src/test/resources/baseline/input/*.txt` — golden inputs
    - **Categories:** one-for-one copies of `app/data/ASCII/*.txt`
    - **Used by:** every baseline parity IT and selected repository ITs

- `src/test/resources/baseline/expected/*.txt` — golden outputs
    - **Categories:** captured COBOL reference outputs (one per batch job)
    - **Used by:** every baseline parity IT (right-hand operand of `BaselineDiffUtil.assertByteEqual`)

### 0.5.3 Test Files to Modify Detail

There are no test files to modify. The entire test suite is being created fresh.

### 0.5.4 Test Configuration Updates

| Configuration File | Transformation | Purpose / Changes |
| --- | --- | --- |
| `pom.xml` | CREATE | Define Maven coordinates, parent POM `spring-boot-starter-parent:3.x`, Java 17 toolchain (`<java.version>17</java.version>`), Surefire 3.x + Failsafe 3.x plugin blocks for unit (`*Test.java`) vs integration (`*IT.java`) split, JaCoCo plugin block with `<rule>` enforcing `LINE 0.80` on `com.aws.carddemo.service.**`, Testcontainers BOM 2.0.3 import, AssertJ + Awaitility BOM-managed |
| `src/test/resources/junit-platform.properties` | CREATE | Configure JUnit 5 platform: `junit.jupiter.execution.parallel.enabled = true`, `junit.jupiter.execution.parallel.mode.default = same_thread`, `junit.jupiter.execution.parallel.mode.classes.default = concurrent`, `junit.jupiter.testinstance.lifecycle.default = per_class` (when appropriate) |
| `src/test/resources/application-test.properties` | CREATE | Spring `test` profile config: `spring.datasource.url=` driven by Testcontainers `@DynamicPropertySource`; `spring.batch.job.enabled=false` to prevent auto-launch in non-batch tests; `logging.level.com.aws.carddemo=DEBUG` |
| `src/test/resources/logback-test.xml` | CREATE | Test-time logging config with PCI/PII filter ensuring no account numbers, card numbers, or balances appear in logs |
| `pom.xml` JaCoCo plugin block | CREATE (within pom.xml) | `prepare-agent` + `report` + `check` goals; `<rule>` block on `com.aws.carddemo.service.**` with `LINE 0.80` and `BRANCH 0.70`; excludes generated sources (`**/dto/**`, `**/config/**`) |
| `pom.xml` Surefire plugin block | CREATE (within pom.xml) | Includes `**/*Test.java`; excludes `**/*IT.java`; argLine wired to JaCoCo agent |
| `pom.xml` Failsafe plugin block | CREATE (within pom.xml) | Includes `**/*IT.java`; bound to `integration-test` + `verify` phases; argLine wired to JaCoCo agent |

### 0.5.5 Cross-File Test Dependencies

#### Shared Fixtures

- **`src/test/resources/baseline/input/*.txt`** — consumed by every baseline parity IT and selected unit tests via `FixtureLoader`. One-for-one copies of `app/data/ASCII/*.txt`.
- **`src/test/resources/baseline/expected/*.txt`** — consumed by every baseline parity IT as the right-hand operand of `BaselineDiffUtil.assertByteEqual`.
- **`src/test/resources/fixtures/edge/*.csv`** — consumed by `@ParameterizedTest @CsvFileSource` annotated test methods across processor and validation unit tests.

#### Mock Object Reuse

- Mock object configuration is intentionally **not** shared across test classes (each test owns its own `@Mock` instances) to preserve test isolation per the Require Test Coverage rule. The only shared mocking convenience is `MockitoExtension`'s `@Mock` field injection.

#### Test Utilities (Helper Functions)

- **`FixtureLoader`** — loads ASCII fixtures and edge-case CSVs; consumed by every batch processor unit test, every batch IT, and every baseline parity IT
- **`BaselineDiffUtil`** — byte-equality assertion utility; consumed by all 5 baseline parity ITs
- **`TestFixtures`** — constants holding sample IDs (account `00000000010` through `00000000060`, card `4111111111111111` through `4111111111111150`, etc.); consumed by service unit tests and controller ITs
- **`AbstractBatchIT`** — base class with shared `@Testcontainers` PostgreSQL container and `@SpringBatchTest` annotations; extended by all 5 batch ITs and 5 baseline parity ITs
- **`AbstractRepositoryIT`** — base class with `@DataJpaTest` + Testcontainers PostgreSQL; extended by all 10 repository ITs

#### Import Updates Required Across Test Files

- Every test file imports from `org.junit.jupiter.api.*` (`@Test`, `@DisplayName`, `@BeforeEach`, `@Nested`), `org.mockito.*` (`@Mock`, `@InjectMocks`, `ArgumentCaptor`), `org.mockito.junit.jupiter.MockitoExtension`, `org.assertj.core.api.Assertions.assertThat`
- Spring slice tests additionally import `org.springframework.boot.test.context.SpringBootTest`, `org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE`, `org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest`, `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest`, `org.springframework.test.context.DynamicPropertySource`
- Batch ITs additionally import `org.springframework.batch.test.JobLauncherTestUtils`, `org.springframework.batch.test.context.SpringBatchTest`, `org.springframework.batch.test.JobRepositoryTestUtils`
- Testcontainers ITs additionally import `org.testcontainers.junit.jupiter.Testcontainers`, `org.testcontainers.junit.jupiter.Container`, `org.testcontainers.containers.PostgreSQLContainer`

## 0.6 Dependency Inventory

### 0.6.1 Testing Dependencies

The dependencies below are scoped to **`test`** in `pom.xml` and are introduced by this Action Plan. Versions are BOM-managed by `spring-boot-starter-parent:3.x` except for the entries explicitly tagged "(plugin)" or "(BOM import)". This avoids placeholder versions like "latest" and ensures every transitive dependency arrives at a version the Spring Boot release engineering team has tested together.

| Registry | Package Name | Version | Purpose |
| --- | --- | --- | --- |
| Maven Central | `org.springframework.boot:spring-boot-starter-test` | 3.x BOM-managed | Umbrella starter that <cite index="8-1">brings in JUnit 5, Mockito, AssertJ, Hamcrest, Spring Test, and several other testing libraries</cite>; the parent POM ensures these libraries are at versions tested together |
| Maven Central | `org.junit.jupiter:junit-jupiter` (transitive via starter-test) | 5.10.x BOM-managed | JUnit 5 aggregate (api + engine + params); JUnit 5 requires <cite index="7-1">Java 17 (or higher) at runtime</cite>, satisfied by the Java 17 LTS target |
| Maven Central | `org.junit.jupiter:junit-jupiter-params` (transitive) | 5.10.x BOM-managed | `@ParameterizedTest`, `@CsvFileSource`, `@MethodSource` for fixture-driven edge cases |
| Maven Central | `org.mockito:mockito-core` (transitive via starter-test) | 5.7+ BOM-managed | Boundary mocking framework; recommended pattern is <cite index="9-14,9-15">declaring `mockito-core:5.+` via the build system</cite> |
| Maven Central | `org.mockito:mockito-junit-jupiter` (transitive) | 5.7+ BOM-managed | `MockitoExtension` for `@ExtendWith(MockitoExtension.class)`; replaces legacy `@RunWith(MockitoJUnitRunner.class)` |
| Maven Central | `org.assertj:assertj-core` (transitive) | 3.24+ BOM-managed | Fluent assertions; `assertThat(...).isEqualTo(...)` idiom throughout |
| Maven Central | `org.hamcrest:hamcrest` (transitive) | 2.2 BOM-managed | Required by JUnit Platform and JsonPath assertions |
| Maven Central | `org.springframework.boot:spring-boot-test` (transitive) | 3.x BOM-managed | Spring `TestRestTemplate`, `SpringBootContextLoader` |
| Maven Central | `org.springframework:spring-test` (transitive) | 6.x BOM-managed | `MockMvc`, `WebTestClient`, `@DynamicPropertySource` |
| Maven Central | `org.springframework.batch:spring-batch-test` | 5.x BOM-managed | `@SpringBatchTest`, `JobLauncherTestUtils`, `JobRepositoryTestUtils` |
| Maven Central | `org.springframework.security:spring-security-test` | 6.x BOM-managed | `@WithMockUser`, `SecurityMockMvcConfigurers` |
| Maven Central | `org.testcontainers:testcontainers-bom` (BOM import) | 2.0.3 | Aligns Testcontainers module versions |
| Maven Central | `org.testcontainers:junit-jupiter` | 1.19+ BOM-managed | `@Testcontainers` + `@Container` JUnit 5 integration |
| Maven Central | `org.testcontainers:postgresql` | 1.19+ BOM-managed | Ephemeral PostgreSQL 16 container for repository and batch ITs |
| Maven Central | `org.testcontainers:localstack` | 1.19+ BOM-managed | LocalStack Pro container for AWS SDK integration tests |
| Maven Central | `org.awaitility:awaitility` | 4.2+ BOM-managed | Asynchronous wait-for-condition utility for batch-completion polling |
| Maven Central | `com.h2database:h2` (optional fallback) | BOM-managed | Reserved for the rare repository unit test that does not require PostgreSQL-specific behaviour |
| Maven Central | `org.jacoco:jacoco-maven-plugin` (plugin) | 0.8.11+ | Coverage instrumentation and `<rule>` enforcement of `LINE 0.80` on the service layer |
| Maven Central | `org.apache.maven.plugins:maven-surefire-plugin` (plugin) | 3.2+ | Unit test execution (`*Test.java`); ships JUnit Platform provider by default |
| Maven Central | `org.apache.maven.plugins:maven-failsafe-plugin` (plugin) | 3.2+ | Integration test execution (`*IT.java`) bound to `integration-test` / `verify` phases |

**Note on version strategy.** No explicit version pins are placed on transitive testing libraries because <cite index="10-1,10-2,10-3,10-4">specifying a version for JUnit can conflict with the versions Spring Boot brings in; the recommended approach is to remove the specific version number so the build system matches the correct versions automatically</cite>. The only direct version pins in `pom.xml` are for plugins (`jacoco-maven-plugin`, `maven-surefire-plugin`, `maven-failsafe-plugin`) and the Testcontainers BOM, all of which sit outside the Spring Boot starter dependency tree.

**Note on scope.** All testing dependencies are declared with `<scope>test</scope>`. Search results confirm that <cite index="8-14,8-15,8-18,8-19">test dependencies must not leak into the production JAR — Mockito in particular includes byte-code manipulation libraries that have no place in production; the correct approach always includes the test scope</cite>.

### 0.6.2 Import Updates

Because this is a greenfield test suite (no existing Java tests on disk), there are no existing imports to update. Every test class created under this Action Plan ships with a canonical, idiomatic import set:

- Test classes requiring base JUnit 5 + Mockito support — produced fresh, importing `org.junit.jupiter.api.{Test, DisplayName, BeforeEach, Nested}`, `org.mockito.{Mock, InjectMocks, ArgumentCaptor}`, `org.mockito.junit.jupiter.MockitoExtension`, `org.assertj.core.api.Assertions.assertThat` (static import)
- Repository ITs additionally import `org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest`, `org.springframework.test.context.DynamicPropertySource`, `org.testcontainers.junit.jupiter.{Testcontainers, Container}`, `org.testcontainers.containers.PostgreSQLContainer`
- Batch ITs additionally import `org.springframework.batch.test.{JobLauncherTestUtils, JobRepositoryTestUtils}`, `org.springframework.batch.test.context.SpringBatchTest`
- Controller ITs additionally import `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest`, `org.springframework.test.web.servlet.MockMvc`, `org.springframework.test.web.servlet.request.MockMvcRequestBuilders`, `org.springframework.test.web.servlet.result.MockMvcResultMatchers`
- End-to-end tests additionally import `org.springframework.boot.test.context.SpringBootTest`, `org.springframework.boot.test.web.client.TestRestTemplate`

**Import transformation rules** (apply to every produced test file):

- Use static imports for assertion helpers: `import static org.assertj.core.api.Assertions.assertThat;`
- Use static imports for Mockito DSL: `import static org.mockito.Mockito.{when, verify, never, times, any, eq};`
- Use static imports for MockMvc DSL: `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;` and `import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;`
- Prefer `org.junit.jupiter.api.Test` over the legacy `org.junit.Test` — JUnit 4 is **not** used; no `junit-vintage-engine` dependency is added

## 0.7 Coverage and Quality Targets

### 0.7.1 Coverage Metrics

**Current coverage:** *Not measurable.* The repository contains no Java source or test files; JaCoCo cannot run.

**Target coverage:** the user's explicit floor is **`≥80%` line coverage on all classes in the service layer**. The Action Plan extends this to per-layer targets enforced declaratively by the JaCoCo Maven plugin's `<rule>` block. The aggregate project-level target is `≥80%` line coverage / `≥70%` branch coverage.

| Layer / Package Pattern | Line Coverage Target | Branch Coverage Target | Rationale |
| --- | --- | --- | --- |
| `com.aws.carddemo.service.**` | **≥80%** (user mandate) | ≥70% | Explicit user requirement; service layer is the primary correctness surface |
| `com.aws.carddemo.batch.**` | ≥85% | ≥75% | Financial-calculation processors carry the highest correctness burden |
| `com.aws.carddemo.validation.**` | ≥90% | ≥85% | Small surface, high criticality (date, lookup, format validators) |
| `com.aws.carddemo.io.**` | ≥90% | ≥85% | `FileStatusMapper` and adapter utilities are small and easy to fully cover |
| `com.aws.carddemo.repository.**` | ≥75% | n/a | Mostly Spring-generated; ITs target custom queries |
| `com.aws.carddemo.controller.**` | ≥80% | ≥70% | HTTP routing, validation, response shape |
| `com.aws.carddemo.dto.**` | not enforced | n/a | DTOs are data carriers (records or POJOs); excluded from coverage rules |
| `com.aws.carddemo.config.**` | not enforced | n/a | Spring configuration classes; behaviour exercised indirectly by ITs |

**Coverage gaps to address as the test suite is materialised:**

- **Reject-code branches (CBTRN02C → `TransactionPostingProcessor`):** every reject code 100–103 (and any additional rejects discovered during migration up to 109) must be exercised by at least one `@ParameterizedTest` row. Target: 100% of reject-code branches.
- **Interest-calculation edge cases (CBACT04C → `InterestCalculationProcessor`):** zero balance, ZEROAPR skip, DEFAULT group fallback, HALF_EVEN-vs-HALF_UP boundary (`100.005` case). Target: 100% of these specific lines.
- **Optimistic-locking paths (`COACTUPC`, `COCRDUPC`, `COUSR02C` migrations):** both the success path (`@Version` increments) and the `OptimisticLockingFailureException` path. Target: 100% of these specific branches.
- **`@Transactional` rollback paths (`COACTUPC` migration):** both commit and rollback branches. Target: 100% of these branches via deliberate exception injection in unit tests.
- **VSAM status-code branches (`FileStatusMapper`):** every documented status code (`00`, `02`, `10`, `22`, `23`, `35`, `92`, `97`). Target: 100%.

**Per-file coverage targets where specified.** The JaCoCo `<rule>` block uses an `<element>BUNDLE</element>` aggregate with a per-package filter; no per-file overrides are required. Files that are intentionally excluded from coverage enforcement (DTO records, Spring configuration classes, generated MapStruct mappers if any) are listed via `<excludes>` patterns in the JaCoCo plugin configuration.

**Coverage report location.** `target/site/jacoco/index.html` after `mvn verify -Pjacoco`. The HTML report is the authoritative artefact for stakeholder review; the XML report at `target/site/jacoco/jacoco.xml` is the machine-readable feed for CI tooling.

### 0.7.2 Test Quality Criteria

Coverage percentage is necessary but not sufficient. The following qualitative criteria, enforced by code review and by Mockito's strict-stubbing mode, define a "good" test in this project:

**Assertion density expectations**

- Every test method ends with at least one assertion that exercises observable behaviour of the production class under test.
- Tests that only verify a mock interaction without asserting on production state are flagged in code review; they typically indicate over-mocking and a Require-Test-Coverage-rule violation.
- Where a method returns a complex object, the test asserts on **all** semantically meaningful fields (use AssertJ's `usingRecursiveComparison()` where appropriate).

**Test isolation requirements**

- Each test creates its own arrange / act / assert flow; tests must not rely on ordering or shared mutable state.
- `@BeforeEach` resets shared collaborators; `@AfterEach` is generally unnecessary because Mockito instances are recreated per test.
- Repository ITs use Spring's `@Transactional` rollback or `@DirtiesContext` only when absolutely required.
- Batch ITs call `JobRepositoryTestUtils.removeJobExecutions()` between executions.

**Performance constraints for test execution**

- Unit-test wall-clock target: `< 5 ms` per method (most run in `< 1 ms`).
- Repository IT wall-clock target: `< 250 ms` per method (Testcontainers PostgreSQL warm-up is amortised over the test class).
- Batch IT wall-clock target: `< 5 s` per method (driven by job step throughput; tests use the smallest fixture that still exercises the path).
- Total `mvn test` wall-clock target: `< 60 s` (unit only).
- Total `mvn verify` wall-clock target: `< 5 min` (unit + integration + E2E).

**Maintainability standards**

- Test class names mirror production class names: `Foo` → `FooTest` / `FooIT`.
- Test method names follow `methodUnderTest_inputCondition_expectedOutcome` ("given/when/then" implicit in the name).
- `@DisplayName` is used on test classes and on test methods only when the method name alone would obscure the scenario (rare).
- Magic numbers are extracted to `TestFixtures` constants; magic strings to `@CsvFileSource` files.
- AssertJ chains are preferred over multiple separate `assertThat` calls when asserting on a single object.

**Following repository test patterns and conventions**

- Mockito strictness is `STRICT_STUBS` (JUnit Jupiter default with `MockitoExtension`) — unused stubs fail the build.
- Static imports for assertion DSLs are mandatory.
- BCrypt is used at real strength (`BCryptPasswordEncoder.DEFAULT_STRENGTH = 10`) in service unit tests; tests are still sub-second because each test sees only a handful of hash operations.
- All time-dependent code is driven by an injected `Clock`; tests inject `Clock.fixed(Instant.parse("2024-01-15T00:00:00Z"), ZoneOffset.UTC)`.

## 0.8 Scope Boundaries

### 0.8.1 Exhaustively In Scope

**New test source files (every entry CREATE):**

- `src/test/java/com/aws/carddemo/service/**/*Test.java` — one unit test per migrated service (17 service tests covering COSGN00C, COMEN01C, COADM01C, COACTVWC, COACTUPC, COCRDLIC, COCRDSLC, COCRDUPC, COTRN00C, COTRN01C, COTRN02C, COBIL00C, CORPT00C, COUSR00C–COUSR03C)
- `src/test/java/com/aws/carddemo/batch/**/*Test.java` — one unit test per batch processor (11 processor tests covering CBACT01C–CBACT04C, CBCUS01C, CBTRN01C–CBTRN03C, CBSTM03A, CBSTM03B, plus CombineTransactionsProcessor for COMBTRAN.jcl)
- `src/test/java/com/aws/carddemo/validation/**/*Test.java` — DateValidationServiceTest, ValidationLookupServiceTest
- `src/test/java/com/aws/carddemo/io/**/*Test.java` — FileStatusMapperTest
- `src/test/java/com/aws/carddemo/repository/**/*IT.java` — 9 repository ITs (AccountRepositoryIT, CardRepositoryIT, CardXrefRepositoryIT, CustomerRepositoryIT, TransactionRepositoryIT, TransactionCategoryBalanceRepositoryIT, DiscountGroupRepositoryIT, TransactionCategoryRepositoryIT, TransactionTypeRepositoryIT, UserSecurityRepositoryIT)
- `src/test/java/com/aws/carddemo/controller/**/*Test.java` — 8 controller tests (AuthControllerTest, MenuControllerTest, AccountControllerTest, CardControllerTest, TransactionControllerTest, BillPaymentControllerTest, ReportControllerTest, UserAdminControllerTest)
- `src/test/java/com/aws/carddemo/batch/**/*IT.java` — 5 batch job ITs and 5 baseline parity ITs
- `src/test/java/com/aws/carddemo/e2e/**/*Test.java` — 4 end-to-end journey tests
- `src/test/java/com/aws/carddemo/testsupport/**/*.java` — FixtureLoader, BaselineDiffUtil, TestFixtures, AbstractBatchIT, AbstractRepositoryIT

**Test configuration files (every entry CREATE):**

- `pom.xml` (in repository root) — Maven project descriptor with Spring Boot 3.x parent, Java 17 toolchain, all testing dependencies in `test` scope, Surefire 3.x + Failsafe 3.x plugin blocks, JaCoCo 0.8.11+ plugin block with `<rule>` enforcing `LINE 0.80` on `com.aws.carddemo.service.**`
- `src/test/resources/junit-platform.properties` — JUnit Platform configuration
- `src/test/resources/application-test.properties` — Spring `test` profile (Testcontainers JDBC URL via `@DynamicPropertySource`, `spring.batch.job.enabled=false`)
- `src/test/resources/logback-test.xml` — test-time logging with PCI/PII redaction filter

**Test utilities and helpers (every entry CREATE):**

- `src/test/java/com/aws/carddemo/testsupport/FixtureLoader.java`
- `src/test/java/com/aws/carddemo/testsupport/BaselineDiffUtil.java`
- `src/test/java/com/aws/carddemo/testsupport/TestFixtures.java`
- `src/test/java/com/aws/carddemo/testsupport/AbstractBatchIT.java`
- `src/test/java/com/aws/carddemo/testsupport/AbstractRepositoryIT.java`

**Test resource files (every entry CREATE):**

- `src/test/resources/baseline/input/*.txt` — golden inputs (one-for-one copies of `app/data/ASCII/*.txt`: acctdata.txt, carddata.txt, cardxref.txt, custdata.txt, dailytran.txt, discgrp.txt, tcatbal.txt, trancatg.txt, trantype.txt)
- `src/test/resources/baseline/expected/*.txt` — golden outputs captured from the COBOL baseline (posted.txt, tcatbal_after_interest.txt, combined.txt, statements_text.txt, statements_html.txt, transaction_report.txt)
- `src/test/resources/fixtures/edge/*.csv` — edge-case fixtures (interest_zero_balance.csv, interest_zeroapr_skip.csv, interest_default_fallback.csv, interest_halfeven_boundary.csv, posting_reject_codes.csv, date_validation_variants.csv, status_code_mappings.csv, eof_boundary.csv, overflow_boundary.csv, lookup_invalid_keys.csv)
- `src/test/resources/db/migration/test/V99__test_only.sql` (optional, created only if a test-specific Flyway script becomes necessary)

**Documentation updates:**

- `README.md` — append a "Testing" section documenting `mvn test` / `mvn verify` invocations and coverage report path
- `docs/testing/test-strategy.md` — concise summary of the test pyramid, naming, and coverage targets
- `docs/testing/baseline-parity.md` — operating procedure for capturing new COBOL reference outputs when input fixtures change

**Cross-cutting files that the migration creates and that this Action Plan exercises (but does not own):**

- `src/main/java/com/aws/carddemo/**/*.java` — produced by the migration phases (REFACTOR flavor); the testing flavor of the AAP CREATEs tests against these classes but **does not modify** them outside what the migration itself dictates

### 0.8.2 Explicitly Out of Scope

- **Database schema modifications.** Tests use the existing migration footprint (`V1__schema.sql`, `V2__indexes.sql`, `V3__seed.sql`). No additional production Flyway scripts are introduced by the test work.
- **Source-code modifications.** The Require Test Coverage rule explicitly forbids reimplementing business logic in tests; conversely, this Action Plan does not introduce production-source changes purely for testability. The only exception is dependency injection of `Clock` and similar seams, which are already part of the migration's idiomatic Java design and not testability-only additions.
- **Refactoring beyond what's needed for testing.** No reshaping of production classes to suit test convenience. The Minimal Change Clause governs.
- **Feature additions while adding tests.** No new business logic, no new endpoints, no new batch jobs are introduced by the test work.
- **Unrelated test files not specified by user.** Performance benchmarks, mutation tests (e.g., PIT), property-based tests (e.g., jqwik), and contract tests (e.g., Spring Cloud Contract) are out of scope. They are mentioned for completeness but are not part of this Action Plan.
- **Performance optimisations not related to test coverage.** Java tuning, JVM flags, query optimisation, indexing changes — all out of scope.
- **Downstream consumer interface changes.** External interfaces consumed by other systems must remain identical; tests assert this immutability but do not change those interfaces.
- **Infrastructure, deployment, or CI/CD configuration.** Jenkins/GitHub Actions/Cloud Build pipelines, Helm charts, Terraform, Dockerfiles are out of scope. The local Maven build (`mvn verify`) is the single source of truth for test execution under this Action Plan.
- **Any COBOL source not part of the financial processing codebase.** Programs outside `app/cbl/` are not in scope (none exist in this repository).
- **EBCDIC fixture conversion tooling.** The 12 `app/data/EBCDIC/*` files are reference-only; the test suite uses the ASCII fixtures. EBCDIC-binary equivalence is verified as part of the migration phase, not the testing phase.
- **Java 25 or any non-Java-17 runtime.** The user mandate is Java 17 LTS. Prior tech spec references to Java 25 are superseded by this Action Plan's runtime envelope; no Java 25-specific features (e.g., switch with patterns at higher language levels) are used in test code.

## 0.9 Execution Parameters

### 0.9.1 Testing-Specific Instructions

The commands below are the canonical invocations for the produced test suite. They assume a working installation of **Java 17 LTS** and **Maven 3.8+** as mandated by the user's prompt (`<java.version>17</java.version>` in `pom.xml`, `mvn -version` reports `Maven 3.8+`).

| Action | Command | Notes |
| --- | --- | --- |
| Build + unit tests | `mvn clean test` | Surefire 3.x picks up `**/*Test.java`; excludes `**/*IT.java` |
| Build + all tests (unit + integration) | `mvn clean verify` | Failsafe 3.x runs `**/*IT.java` in the `integration-test` phase, asserts results in `verify` |
| Build + all tests + coverage | `mvn clean verify -Pjacoco` | JaCoCo agent attaches via Surefire/Failsafe `argLine`; `jacoco:check` enforces `LINE 0.80` on `com.aws.carddemo.service.**`; HTML report at `target/site/jacoco/index.html` |
| Single unit test class | `mvn -Dtest=AuthenticationServiceTest test` | Surefire test selector by simple class name |
| Single unit test method | `mvn -Dtest=AuthenticationServiceTest#authenticate_validUserValidPassword_returnsUserSession test` | Surefire selector including method |
| Single integration test class | `mvn -Dit.test=TransactionPostingBaselineParityIT verify` | Failsafe selector |
| Skip integration tests | `mvn test -DskipITs` | Runs only Surefire-managed unit tests |
| Generate coverage report only | `mvn jacoco:report` | Run after a prior `mvn test` or `mvn verify` |
| Generate XML coverage report for CI | (default) `target/site/jacoco/jacoco.xml` is produced by `mvn verify -Pjacoco` | Consumed by CI dashboards |
| Run tests by tag | `mvn test -Dgroups="batch"` | JUnit 5 `@Tag("batch")` selector if test tagging is adopted |
| Debug a single test (JVM remote debug) | `mvn -Dmaven.surefire.debug -Dtest=ClassName test` | Listens on port 5005 |
| Re-run only failing tests | `mvn -Dsurefire.rerunFailingTestsCount=2 test` | Surefire's flaky-test mitigation; intended for ITs only, set to `0` for unit tests |
| Watch mode | *not applicable* | Maven has no first-class watch mode; CI-style invocations are the project standard |

**Specific test patterns followed in the repository.** Every test class adopts:

- `@ExtendWith(MockitoExtension.class)` for unit tests
- `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + `@Testcontainers` for repository ITs
- `@SpringBatchTest` + `@SpringBootTest(classes = BatchTestConfig.class)` for batch ITs
- `@WebMvcTest(controllers = X.class)` + `@MockBean` for controller slice tests
- `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` for end-to-end ITs
- Static imports of AssertJ `assertThat`, Mockito `when`/`verify`, MockMvc DSLs
- Mockito strictness left at `STRICT_STUBS` (default with `MockitoExtension`) so unused stubs fail the build

**Test categories explicitly excluded per user instruction.**

- No mutation testing (no PIT plugin)
- No property-based testing (no jqwik)
- No contract testing (no Spring Cloud Contract)
- No performance / load testing (no JMH; out of scope per Minimal Change Clause)
- No UI/end-to-end browser testing (the migration intentionally drops BMS and produces REST controllers, so there is no UI layer to drive with Selenium/Playwright/Cypress)

**Environment setup requirements for tests.**

- **Java 17 LTS** installed and on `PATH`; verified with `java -version`
- **Maven 3.8+** installed and on `PATH`; verified with `mvn -version`
- **Docker** running and accessible to Testcontainers (verified with `docker info`); ITs that use `PostgreSQLContainer` or `LocalStackContainer` will skip with a clear error message if Docker is unavailable
- No external network calls required by the test suite; all integrations are sandboxed via Testcontainers
- `TESTCONTAINERS_RYUK_DISABLED=true` may be set in constrained environments where the Ryuk reaper container cannot run
- No `application.properties` secrets required; the test profile derives all configuration via `@DynamicPropertySource`

## 0.10 Special Instructions for Testing

The following directives — extracted verbatim from the user's prompt or directly implied by it — govern all test work in this Action Plan. They take precedence over generic best-practice patterns where a conflict arises.

### 0.10.1 Require Test Coverage Rule (ENFORCED)

- **User Directive:** "Tests MUST call production service and repository classes directly"
- **User Directive:** "Tests MUST NOT reimplement any business or calculation logic inside test bodies"
- **User Directive:** "Mocks limited to external boundaries: file I/O, downstream service calls, database"
- **User Directive:** "Each test method asserts a specific behavior of the production class under test"

**Concrete enforcement steps**:

- Every unit test instantiates the real production class (via `new ServiceImpl(...)` or `@InjectMocks`) and invokes its public methods.
- Mockito `@Mock` instances are reserved for `JpaRepository` subclasses, `FlatFileItemReader/Writer`, AWS SDK clients, `Clock`, and external HTTP clients.
- Tests must not duplicate `COMPUTE` arithmetic in their assertions. For example, the assertion in `InterestCalculationProcessorTest` reads `assertThat(result.getInterest()).isEqualByComparingTo("15.00")` — not `assertThat(result.getInterest()).isEqualByComparingTo(balance.multiply(rate).divide(BigDecimal.valueOf(1200), 2, HALF_EVEN))`.
- Tests must not duplicate `EVALUATE/WHEN` branch logic. For example, reject-code assertions read `assertThat(reject.getCode()).isEqualTo(102)` for a specific input row — not a conditional expression that re-derives the expected code from the input.
- Mockito strictness is `STRICT_STUBS`; unused stubs raise `UnnecessaryStubbingException`. Search results confirm that this strictness <cite index="5-21,5-22,5-23">detects places where stubs are mocked unnecessarily and fails the tests; the UnnecessaryStubbingException error shows where stubs were unnecessarily mocked and the tests won't fail even if those stubs are deleted</cite>. This catches premature mocking that would otherwise mask the rule violation.

### 0.10.2 Minimal Change Clause and Refactor Discipline

- **User Directive:** "IMPORTANT: Migrate only what is necessary to achieve functional parity. Do not introduce patterns, abstractions, or optimizations beyond what the migration requires."
- **User Directive:** "Preserve COBOL business logic structure — do not redesign algorithms"
- **User Directive:** "Do not add features, UI layers, or capabilities not present in the original COBOL"
- **User Directive:** "Do not update dependencies beyond those required for Java 17 and Spring Boot 3.x compatibility"
- **User Directive:** "If a COBOL construct has no clean Java equivalent, document the mapping decision in a code comment — do not silently alter behavior"
- **User Directive:** "All deviations from literal COBOL logic must be documented with the original COBOL paragraph name and reason for divergence"

**Concrete enforcement steps in test work**:

- Test code uses only Spring Boot 3.x BOM-managed test dependencies plus the Testcontainers BOM and the standard Maven test plugins. No exotic libraries, no test-specific code generators, no homegrown DSLs.
- Test code does not introduce abstractions (custom base classes beyond the two `AbstractBatchIT` / `AbstractRepositoryIT` helpers already documented) that exist only to flatter the test code.
- When a COBOL paragraph maps to a Java private method, the test exercises the public entry point of the containing class — not the private method via reflection.
- Test names embed the COBOL paragraph or program identifier when the test covers a non-obvious mapping (e.g., `process_paragraph1100ReadCustomer_handlesEofRecord`).

### 0.10.3 Financial Precision

- **User Directive:** "No float or double used for any monetary value — BigDecimal exclusively"
- **User Directive:** "BigDecimal rounding mode set to HALF_EVEN (banker's rounding) matching COBOL PICTURE clause precision"
- **User Directive:** "All monetary fields: BigDecimal with scale derived from COBOL PICTURE clause (e.g., PIC 9(7)V99 → scale 2)"
- **User Directive:** "Rounding: RoundingMode.HALF_EVEN throughout"
- **User Directive:** "No implicit type promotion to float/double at any calculation boundary"

**Concrete enforcement steps in test work**:

- Every assertion on a monetary value uses AssertJ's `isEqualByComparingTo(BigDecimal)` (which compares value ignoring scale only when explicitly intended) and includes a paired `.satisfies(b -> assertThat(b.scale()).isEqualTo(2))` check where scale parity matters.
- `@ParameterizedTest` for `InterestCalculationProcessor` includes the `100.005` HALF_EVEN-vs-HALF_UP boundary case to prove the production code uses HALF_EVEN, not HALF_UP.
- No test fixture file (CSV, properties, JSON) carries a monetary value as a floating-point literal; values are quoted strings (e.g., `"100.50"`) that map cleanly to `new BigDecimal(...)`.
- A repository-wide build-time check (an architectural rule, e.g., ArchUnit if introduced, or a simple `grep` in CI) asserts that no field annotated `@Column` of type `double`/`float`/`Double`/`Float` exists in any `com.aws.carddemo.entity.**` class.

### 0.10.4 Immutable Boundaries

- **User Directive:** "Input and output file formats and record layouts MUST remain identical"
- **User Directive:** "All financial calculation results MUST match COBOL baseline output exactly"
- **User Directive:** "External interfaces consumed by downstream systems MUST NOT change"
- **User Directive:** "Baseline comparison test: feed identical input files to COBOL baseline and Java implementation; diff output files — zero delta required"

**Concrete enforcement steps in test work**:

- Each of the 5 batch jobs has a dedicated `[JobName]BaselineParityIT` that produces an output file and calls `BaselineDiffUtil.assertByteEqual(actual, expected)`; the assertion fails with a line-level diff when any byte differs.
- Record-layout parity is asserted at the unit level via fixed-width parsers: tests assert `record.substring(0, 11)` is the account ID, `record.substring(11, 18)` is the customer ID, etc.
- Downstream-consumer contract is asserted at the controller IT level: every JSON response field name and shape is asserted with JsonPath expressions; any rename is caught at the slice-test boundary.

### 0.10.5 Security Constraints

- **User Directive:** "No financial data written to logs at any level"
- **User Directive:** "No plaintext credentials in any configuration file"

**Concrete enforcement steps in test work**:

- A dedicated test class `LoggingPiiRedactionTest.java` exercises selected service paths with known PII (account numbers, card numbers, balances) and asserts via a `ListAppender<ILoggingEvent>` that captured log lines do not contain any of those values.
- `application-test.properties` and `application.properties` are inspected via a CI `grep` for password patterns; any plaintext password fails the build.
- `logback-test.xml` registers a turbofilter that masks account numbers and card numbers in any log line (defence in depth).

### 0.10.6 Test Naming and Location Conventions

- **User Directive:** "Test file location: `src/test/java` mirroring production package structure"
- **User Directive:** "Naming: `[ClassName]Test.java` for unit tests, `[ClassName]IT.java` for integration tests"

**Concrete enforcement steps in test work**:

- The Surefire plugin `<includes>` block lists `**/*Test.java` only; the Failsafe plugin `<includes>` block lists `**/*IT.java` only. This split is enforced by Maven, not by convention.
- Every test class lives in the same package as its production class. A test for `com.aws.carddemo.service.AuthenticationService` lives at `src/test/java/com/aws/carddemo/service/AuthenticationServiceTest.java`. This positioning grants package-private access where helpful and keeps the IDE navigation tight.

### 0.10.7 Framework Constraint

- **User Directive:** "Framework: JUnit 5 (`@Test`, `@ParameterizedTest` for calculation variants), Mockito for mocks"

**Concrete enforcement steps in test work**:

- No JUnit 4 (`org.junit.Test`) imports in any test file; no `junit-vintage-engine` dependency in `pom.xml`.
- Mockito 5.x via BOM; no PowerMock. Search results confirm that <cite index="6-3,6-4,6-5">PowerMock is not ported to JUnit 5, but Mockito has supported static-method mocking since version 3.4</cite> — the migration uses `Mockito.mockStatic` for the rare static-method case (e.g., `Files`, `Paths`).
- `@ParameterizedTest` is the standard mechanism for "calculation variants": every monetary calculation has at least one parameterized test driven by a CSV under `src/test/resources/fixtures/edge/`.

### 0.10.8 Coverage Constraint

- **User Directive:** "Coverage target: ≥80% line coverage on all classes in the service layer"

**Concrete enforcement steps in test work**:

- The `jacoco-maven-plugin` `check` goal carries a `<rule>` block scoped to `BUNDLE` with an `<element>BUNDLE</element>` filter (or `<element>PACKAGE</element>` filter on `com.aws.carddemo.service`) requiring `LINE 0.80`.
- `mvn verify -Pjacoco` fails when the rule is violated.
- The CI pipeline (out of scope for this AAP but documented for completeness) publishes the JaCoCo HTML report as an artifact.

### 0.10.9 Test Execution Independence and Parallelism

- **Implied directive (industry standard for the Require Test Coverage rule):** tests must be independent and runnable in any order.

**Concrete enforcement steps in test work**:

- Unit tests declare no shared mutable static state. Each test class instantiates fresh mocks and fresh production objects.
- JUnit 5 platform property `junit.jupiter.execution.parallel.enabled=true` is enabled with `mode.classes.default=concurrent` so test classes execute in parallel where possible. Per-class methods stay `same_thread` to preserve `@BeforeEach`/`@AfterEach` semantics.
- Repository ITs use Testcontainers with per-class containers (`@Container static`) — each class owns its container lifecycle. Spring's `@DirtiesContext` is avoided unless absolutely required to keep the context cache warm.
- Batch ITs reset `JobRepository` state via `JobRepositoryTestUtils.removeJobExecutions()` between tests.

### 0.10.10 Style Consistency

- **User Directive (Minimal Change Clause):** "Do not introduce patterns, abstractions, or optimizations beyond what the migration requires"

**Concrete enforcement steps in test work**:

- All test classes follow Arrange-Act-Assert (AAA) with blank-line separators between the three blocks.
- All assertions use AssertJ (fluent) rather than mixing AssertJ + Hamcrest + `Assertions.assertEquals`.
- All Mockito stubs use the `when(...).thenReturn(...)` style; `doReturn(...).when(...)` is reserved for the rare cases where the former does not compile (e.g., void methods, spy interactions).
- Code style follows the Spring Java Format conventions or the project's existing Checkstyle/SpotBugs ruleset (both inherited from the migration's production code; tests respect the same rules).

