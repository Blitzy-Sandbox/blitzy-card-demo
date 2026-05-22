# Migration Notes — CardDemo COBOL → Java 25 LTS

This document is the log mandated by AAP §0.7.1 for:

- Translations of dead code (preserved faithfully, not "fixed")
- Suspected COBOL or JCL bugs translated faithfully (flagged, not "fixed")
- Deviations from idiom-for-idiom translation, with rationale
- Behavior-change items deferred to follow-up efforts
- TODO markers from the user prompt that require resolution

It is the **only** documentation file under `java/` that grows over time;
do not introduce other progress / status documents.

## Initial Setup (baseline established by the setup phase)

| Item | Value |
|------|-------|
| JDK | Eclipse Temurin 25.0.3+9 LTS |
| Apache Maven | 3.9.9 |
| OS | Ubuntu 25.10 |
| Baseline branch | `blitzy-f3bf2d6d-69c5-40a0-b93c-516c33020956` |
| `app/` tree | **unmodified** (preserved as reference implementation per AAP §0.2.2) |

The baseline build produces empty modules with only `package-info.java` files —
this is intentional: the COBOL → Java translation itself is the responsibility
of subsequent code-generation agents per AAP §0.4.1.

## Open TODO Markers (carried forward from the AAP §0.7.5)

The following TODO markers from the AAP must be resolved by downstream agents
in the course of translation:

1. **Persistence target** (AAP §0.7.5) — VSAM KSDS with fixed-width records;
   default path is `java.nio.file` with fixed-width readers. JDBC adapter
   remains conditional/empty unless source-side embedded SQL is discovered.

2. **External integrations** (AAP §0.7.5) — file-based batch only; CICS TDQ
   (used by `CORPT00C`) is translated to direct method invocation since
   CICS TDQ replacement orchestration is out of scope.

3. **Throughput target** (AAP §0.7.5) — no specific TPS target provided; JFR
   baseline enforces no regression beyond a 10 % band. Document the actual
   measured baseline here once captured.

4. **Maven dependencies** (AAP §0.5.1) — every coordinate is enumerated in
   `pom.xml` parent. All dependencies come from Maven Central; no private
   registries.

5. **Runtime configuration** (AAP §0.7.5) — every supported environment
   variable / properties key is documented in
   [`application.properties.example`](application.properties.example).

6. **Regenerating golden-record fixtures** (AAP §0.7.5) — documented in this
   file under "Golden-Record Fixture Capture Procedure" below.

## Behavior-Change Items Explicitly OUT OF SCOPE

Per AAP §0.1.3 and §0.7.2 these items would be behavior changes beyond
migration scope and must be flagged here, **not** silently fixed:

- **Plaintext password storage** in `SEC-USER-DATA` (`SEC-USR-PWD PIC X(08)`,
  `app/cpy/CSUSR01Y.cpy`) — preserved as plaintext. Any move to BCrypt /
  Argon2 is a separate effort.
- **Suspected JCL bug** in `app/jcl/DEFCUST.jcl` (delete/define dataset-name
  mismatch noted in AAP §0.4.1) — translate faithfully; do not "fix".
- **Legacy z/OS introspection** in `app/cbl/CBSTM03A.CBL` (TIOT/TCB/PSA
  inspection, ALTER/GO TO flow) — translate faithfully and flag as DEVIATION
  here once the translated code is in place.
- **SYNCPOINT ROLLBACK** in `app/cbl/COACTUPC.cbl` — the sole rollback in the
  COBOL source. Translation uses try/finally with compensating writes;
  document the exact rollback semantics on translation.

## Golden-Record Fixture Capture Procedure

The 9 ASCII fixtures under `app/data/ASCII/` are read directly by the
golden-record harness (AAP §0.6.11). They are **not** copied into `java/`.

To regenerate the expected outputs from the COBOL baseline (e.g., when
adding a new program-under-test):

1. Run the COBOL program on z/OS with the corresponding fixture from
   `app/data/ASCII/`. The fixture must be in the same fixed-width format
   the COBOL program expects (EBCDIC IBM-1047 by default; ASCII as provided).
2. Capture the program's output to a flat file.
3. Commit the captured output under
   `java/carddemo-tests/src/test/resources/golden/<program>/expected/`.
4. Re-run `mvn -B verify` from the `java/` directory.

The harness skeleton and per-program test classes will be created by the
code-generation agents during translation; expected-output files captured
later are dropped in alongside without code changes.

## Deviation Log (append-only)

> Each entry: program/file path, COBOL behavior summary, Java translation
> chosen, rationale for any deviation from strict idiom-for-idiom, and a
> link to the test that proves byte-for-byte parity.

_(empty at setup time — first entries are added by translation agents)_
