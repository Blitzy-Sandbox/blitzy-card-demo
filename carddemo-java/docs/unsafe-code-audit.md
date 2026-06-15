# Unsafe-Code Audit

**Deliverable for AAP §0.7.8:** *"raw SQL concatenation, `Runtime.exec`, reflection,
unchecked casts, and suppressed warnings are counted and justified per site where
present."*

This audit is the authoritative, per-site accounting of the five unsafe-code
categories across the CardDemo Java codebase. It is referenced by
`GateVerificationTest.gate6_unsafeCodeAudit()` (which documents the counts at
runtime but cannot scan source) and by `docs/validation-gates.md` (Gate 6).

## Methodology

- **Scope split:** production code (`src/main/java`) is audited separately from test
  code (`src/test/java`). The production audit is the security-meaningful gate; the
  test-tree counts are documented and justified but are not a security concern.
- **How counted (reproducible):**
  ```bash
  # @SuppressWarnings annotations (exclude Javadoc {@code ...} references)
  grep -rnE "^[[:space:]]*@SuppressWarnings" src/main/java src/test/java
  # java.lang.reflect usage
  grep -rnE "java\.lang\.reflect|getDeclaredMethod|getDeclaredField|getMethods\(|setAccessible" src/main/java src/test/java
  # Runtime.exec / ProcessBuilder
  grep -rnE "Runtime\.getRuntime|ProcessBuilder|\.exec\(" src/main/java src/test/java
  # raw SQL/JPQL string concatenation
  grep -rnE "create(Native)?Query\(.*\+|Statement.*\+.*\"" src/main/java src/test/java
  ```
- **Date of scan:** 2026-06-14 (FINAL checkpoint).

## Summary

| Category | Production (`src/main/java`) | Test (`src/test/java`) |
|---|---|---|
| Raw SQL/JPQL string concatenation | **0** | **0** |
| `Runtime.exec` / `ProcessBuilder` | **0** | **0** |
| Reflection (`java.lang.reflect`) | **0** | **2** sites (both warning-free) |
| Unchecked / raw-generic casts | **0** | **1** (`@SuppressWarnings("unchecked")`) |
| `@SuppressWarnings` annotations | **0** | **14** (13× `"resource"` + 1× `"unchecked"`) |

**Production code contains ZERO unsafe-code sites in every category.** All
suppressions and all reflection live exclusively in the test tree and are justified
below.

## Production (`src/main/java`) — all zero

- **Raw SQL = 0.** All persistence is Spring Data JPA derived queries and `@Query`
  JPQL with bound parameters; there is no string-built SQL/JPQL and no
  `JdbcTemplate` string concatenation.
- **`Runtime.exec` = 0.** No process execution anywhere in production code.
- **Reflection = 0.** There is no `import java.lang.reflect.*`. The only token that
  superficially resembles reflection is `WebConfig.java:609`
  `ex.getMethod()` — this is
  `org.springframework.web.HttpRequestMethodNotSupportedException.getMethod()`, which
  returns the offending **HTTP verb as a `String`** (e.g. `"DELETE"`); it is not
  `java.lang.reflect.Method`.
- **Unchecked casts = 0.** No raw-generic or unchecked casts.
- **`@SuppressWarnings` = 0.** Production code compiles warning-free with no
  suppressions, satisfying the zero-warning build (§0.7.8).

## Test (`src/test/java`) — documented & justified

### Reflection — 2 sites (read-only metadata; **no `setAccessible`**)

| # | Site | API | Justification |
|---|------|-----|---------------|
| 1 | `unit/service/account/AccountUpdateServiceTest.java:1044` | `getDeclaredMethod("updateAccount", …).getAnnotation(Transactional.class)` | Asserts `updateAccount(...)` carries `@Transactional(rollbackFor = Exception.class)` — the COACTUPC `SYNCPOINT ROLLBACK` parity contract (§0.7.5). Read-only annotation inspection; no `setAccessible`, no instantiation. |
| 2 | `unit/service/admin/UserListServiceTest.java:325` | `UserListItem.class.getMethods()` loop | Security structural guarantee: iterates **public** methods to prove the browse-row type exposes no password-bearing accessor. Public-only enumeration (the code comments note it deliberately avoids deep/`setAccessible` reflection); warning-free. |

Both are test-only assertion tools that verify a compile-time contract (an annotation
and an accessor surface). Neither runs in production, neither uses `setAccessible`,
and neither relaxes access control.

### `@SuppressWarnings` — 14 annotations

**13× `@SuppressWarnings("resource")`** — Testcontainers singleton containers and
shared static AWS SDK clients that are intentionally **never closed**: a single
container/client is shared across the whole test class (and cached Spring context)
for speed, and is reaped by Testcontainers' Ryuk sidecar at JVM exit. Closing them
per-test would defeat container reuse. Removing these suppressions would reintroduce
13 real `try-with-resources`/`AutoCloseable` compiler warnings and break the
zero-warning build, so they are retained.

| Site | Resource |
|------|----------|
| `e2e/BatchPipelineE2ETest.java:237` | singleton container |
| `e2e/BatchPipelineE2ETest.java:245` | singleton container |
| `e2e/BatchPipelineE2ETest.java:251` | shared static client |
| `e2e/BatchPipelineE2ETest.java:255` | shared static client |
| `e2e/BatchPipelineE2ETest.java:259` | shared static client |
| `integration/aws/AbstractLocalStackIntegrationTest.java:172` | singleton container |
| `integration/aws/AbstractLocalStackIntegrationTest.java:193` | singleton container |
| `integration/repository/AbstractRepositoryIT.java:128` | singleton container |
| `integration/batch/AbstractBatchJobIT.java:193` | singleton container |
| `integration/batch/AbstractBatchJobIT.java:206` | singleton container |
| `integration/batch/AbstractBatchJobIT.java:219` | shared static client |
| `integration/batch/AbstractBatchJobIT.java:223` | shared static client |
| `integration/batch/AbstractBatchJobIT.java:227` | shared static client |

**1× `@SuppressWarnings("unchecked")`**

| Site | Justification |
|------|---------------|
| `unit/service/report/ReportSubmissionServiceTest.java:310` | `mock(SqsSendOptions.class, …)` returns a raw `SqsSendOptions`; Mockito's `mock(Class)` erases generics, so casting it to the parameterized `SqsSendOptions<ReportJobMessage>` is unavoidably unchecked. Confined to one private test helper (`mockSendOptions()`). |

### `Runtime.exec` / raw SQL — 0 in tests

No test executes external processes or concatenates SQL/JPQL strings.

## Conclusion

The production codebase is free of all five unsafe-code categories. The only
suppressed warnings and reflection are in the test tree, are individually justified,
use no `setAccessible`, and (for the 13 `"resource"` sites) are required to keep the
build warning-free. The recorded counts in `GateVerificationTest.gate6` match this
audit exactly.
