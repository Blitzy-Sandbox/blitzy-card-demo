# OWASP Dependency-Check — Final Evidence

This directory persists the **passing** OWASP dependency-check report for the
CardDemo Java migration, satisfying AAP §0.7.8 ("OWASP zero critical/high CVEs —
`dependency-check-maven` reports no critical or high-severity CVEs across direct
and transitive dependencies"). The `target/` output directory is git-ignored, so
the report is copied here as committed final evidence.

## Result

| Field | Value |
|---|---|
| Engine | OWASP dependency-check **12.1.0** |
| Build gate | `failBuildOnCVSS = 7` (fail on CVSS ≥ 7.0) |
| Build outcome | **BUILD SUCCESS** (exit code 0) |
| Dependencies scanned | 115 |
| Vulnerabilities at/above CVSS 7.0 (critical/high) | **0** |
| Vulnerabilities reported after suppressions | **0** |
| Report date | 2026-06-14 |

The gate passes with **zero critical/high CVEs**. Before suppressions, the scan
surfaced exactly 5 informational **MEDIUM** findings (max CVSS 5.9, all below the
CVSS 7.0 gate); every one is a documented CPE / version false positive and is
suppressed with a per-site justification in
[`../../owasp-suppressions.xml`](../../owasp-suppressions.xml):

| CVE | Flagged artifact | CVSS | Why it is a false positive |
|---|---|---|---|
| CVE-2025-68161 | log4j-api 2.24.3 | 4.8 | SocketAppender lives in Log4j **Core** (absent; only the log4j-api facade is present via log4j-to-slf4j → SLF4J/Logback). |
| CVE-2026-34477 | log4j-api 2.24.3 | 5.9 | Incomplete-fix of CVE-2025-68161 — same Core-only SocketAppender/SslConfiguration. |
| CVE-2025-15104 | hibernate-validator 8.0.3.Final | 5.3 | CPE `validator:validator` is the **Nu Html Checker (validator.nu)** web service, not the Jakarta Bean Validation engine. |
| CVE-2020-29582 | kotlin-stdlib(+jdk7) 1.9.25 | 5.3 | Fixed in Kotlin **1.4.21**; resolved artifacts are 1.9.25 (already patched); NVD range over-matches. |
| CVE-2026-39882 | opentelemetry-semconv 1.41.1 | 5.3 | OpenTelemetry-**Go** OTLP exporter (CPE platform `go`); this jar is Java generated-constants only. |

## Reproduce

From `carddemo-java/`:

```bash
export JAVA_HOME=/opt/java/current
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -B org.owasp:dependency-check-maven:12.1.0:check -DautoUpdate=false -Dformat=ALL
# BUILD SUCCESS; report written to target/dependency-check-report.{html,json,xml,csv,sarif}
```

`-DautoUpdate=false` uses the locally cached NVD database. The committed files in
this directory are the HTML (human-readable) and JSON (machine-readable) outputs
of the run above.
