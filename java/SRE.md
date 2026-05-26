# CardDemo (Java 25 LTS) — SRE / SLO Skeleton

> Refine-PR deliverable Item 5 — site reliability engineering
> skeleton documenting the proposed SLI/SLO catalog, error-budget
> templates, and JFR-baseline integration. This file is the
> **scaffolding** that the SRE team uses to negotiate concrete
> targets with the business. Values marked `TBD pending baseline`
> require real production telemetry before they can be committed
> as binding targets.

**Authority**

* AAP §0.6.11 — JFR performance baseline; 10% regression band.
* AAP §0.7.2 — non-negotiable quality gates (test pass rate,
  application runtime validated, zero unresolved errors).
* AAP §0.7.5 — `[TODO]` markers explicitly allowed where data is
  not yet available; these are tracked as `TBD pending baseline`
  here.
* Refine-PR Item 5 — SRE/SLO skeleton requirements.
* `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/perf/JfrBaselineTest.java`
  — the executable contract for the Decimals JFR regression band.
* `java/carddemo-tests/src/test/resources/perf/baseline.properties`
  — the captured baseline values (currently `PLACEHOLDER`).

---

## Table of Contents

1. [SLI Catalog (Service Level Indicators)](#1-sli-catalog)
2. [SLO Targets](#2-slo-targets)
3. [JFR Performance Baseline Integration](#3-jfr-performance-baseline)
4. [Error Budget Template](#4-error-budget-template)
5. [Alerting Integration](#5-alerting-integration)
6. [Capacity Planning Notes](#6-capacity-planning)
7. [Periodic Review Cadence](#7-periodic-review-cadence)

---

## 1. SLI Catalog

A Service Level Indicator (SLI) is a precise numerical
measurement of one observable aspect of system behavior. The
SLI catalog below is normative; every operations dashboard MUST
report exactly these SLIs with exactly these definitions.

### 1.1 Batch success rate (SLI-1)

**Definition**: the fraction of nightly full-batch chain
executions that complete with all 17 steps returning exit
code 0 (`RC_OK`).

**Measurement window**: 30-day rolling window.

**Numerator**: count of chain runs where ALL 17 steps emitted
exit code 0.

**Denominator**: count of chain runs ATTEMPTED in the window
(i.e., the orchestration tier kicked the chain off at all).

**Excluded conditions**: chain runs that were cancelled
intentionally by an operator (e.g., maintenance window) are
excluded from BOTH numerator and denominator.

**Data source**: orchestration tier audit log (per-step exit
codes); supplemented by systemd journal events.

### 1.2 Batch step duration p50 / p95 / p99 (SLI-2)

**Definition**: the wall-clock duration of each `carddemo-app`
shaded jar from `main()` entry to JVM exit, measured at the
50th, 95th, and 99th percentiles within a 30-day rolling window.

**Measurement window**: 30-day rolling window.

**Per-step decomposition**: SLI-2 is computed independently for
EACH of the 28 shaded jars (see `java/RUNBOOK.md` §1). The
critical chain (POSTTRAN, INTCALC, COMBTRAN, CREASTMT) gets
dedicated dashboards; the supporting steps (Phase 1 loads,
Phase 4 reports) are aggregated.

**Data source**: SLF4J structured log line emitted at
shutdown — `app.run.completed total_ms=NNN exit_code=N`. The
log line is parsed by the SRE pipeline (Loki / Splunk / etc.).

### 1.3 File-write throughput (SLI-3)

**Definition**: bytes written per second to
`carddemo.output.root` during a step, measured at the 50th and
95th percentiles within a 30-day rolling window.

**Per-step decomposition**: this SLI is most meaningful for
COMBTRAN (sort + merge of a large input set), CREASTMT (statement
HTML/text generation), and TRANBKP (full TRANSACT backup).

**Data source**: SLF4J structured log line — `file.write.summary
path=<p> bytes=NNN duration_ms=NNN bytes_per_sec=NNN`. Emitted by
`carddemo-adapter-file` writers at file close.

### 1.4 Parity-test pass rate (SLI-4)

**Definition**: the fraction of golden-record parity tests
(`*GoldenTest.java` in `carddemo-tests`) that pass per nightly
CI run.

**Measurement window**: most-recent nightly CI run.

**Numerator**: count of `@Test`-annotated methods in the
`golden/` package that exit `PASSED`.

**Denominator**: count of `@Test`-annotated methods in the
`golden/` package that are NOT `@Disabled`.

**Note on `@Disabled` exclusions**: 29 golden tests are currently
`@Disabled` per the Refine-PR scope (they require real z/OS COBOL
captures that the user explicitly placed out of scope). These
tests are EXCLUDED from the denominator. When z/OS captures are
committed in a future PR, the `@Disabled` annotation is removed
and the tests automatically count against SLI-4.

**Data source**: JUnit Surefire / Failsafe XML reports parsed by
the CI workflow (`.github/workflows/build.yml` step
"Forbidden-artifact lint" includes a parity-test summary).

### 1.5 Decimals coverage gate (SLI-5)

**Definition**: line coverage of `carddemo-domain/.../Decimals.java`
as reported by JaCoCo, measured on every PR merge to main.

**Target**: 100% — this is a binding gate, not an SLO. A
single line uncovered breaks the build. See `java/README.md`
§8 quality gates.

**Data source**: `target/site/jacoco/jacoco.csv` produced by the
`jacoco:report` goal in `carddemo-domain`.

### 1.6 JFR Decimals regression band (SLI-6)

**Definition**: ratio of measured Decimals canonical-workload
duration to the committed baseline, expressed as a decimal
fraction (1.00 == on baseline, 1.10 == 10% slower).

**Target**: ≤ 1.10 (i.e., never more than 10% slower than the
captured baseline). The 10% band is non-negotiable per AAP §0.6.11.

**Data source**: `JfrBaselineTest#decimalsWorkloadWithinTolerance`
asserts this directly. When the baseline file still contains
`PLACEHOLDER`, the assertion is skipped (COLLECTION mode); see §3.

---

## 2. SLO Targets

A Service Level Objective (SLO) is the binding target for an SLI.
The values below are PROPOSED. The SRE team MUST confirm them
against business expectations before they become binding.

### 2.1 SLO catalog (proposed)

| SLO ID  | Reference SLI                                    | Proposed Target                 | Justification                                                                                                                  |
|---------|--------------------------------------------------|---------------------------------|--------------------------------------------------------------------------------------------------------------------------------|
| SLO-1   | SLI-1 batch success rate                         | ≥ 99.0% over 30-day window      | Industry-standard for nightly batch. Two failed runs per quarter is the upper bound the BI team can absorb via re-processing. |
| SLO-2.a | SLI-2 POSTTRAN duration p95                      | `TBD pending baseline` minutes  | POSTTRAN p95 must be captured from 14 days of PROD telemetry before this can be set.                                          |
| SLO-2.b | SLI-2 POSTTRAN duration p99                      | `TBD pending baseline` minutes  | p99 generally 2x p95 for I/O-bound workloads; refine after capture.                                                            |
| SLO-2.c | SLI-2 INTCALC duration p95                       | `TBD pending baseline` minutes  | INTCALC's duration scales with active-account count; capture is environment-dependent.                                         |
| SLO-2.d | SLI-2 COMBTRAN duration p95                      | `TBD pending baseline` minutes  | COMBTRAN's duration scales with daily-tran count; capture is environment-dependent.                                            |
| SLO-2.e | SLI-2 CREASTMT duration p95                      | `TBD pending baseline` minutes  | CREASTMT's duration scales with customer count; capture is environment-dependent.                                              |
| SLO-3.a | SLI-3 COMBTRAN file-write throughput p50         | `TBD pending baseline` MB/sec   | Depends on storage backend (NVMe vs networked); capture per PROD host class.                                                   |
| SLO-3.b | SLI-3 CREASTMT file-write throughput p50         | `TBD pending baseline` MB/sec   | Statement HTML output is small per record but high in record count.                                                            |
| SLO-4   | SLI-4 parity-test pass rate                      | 100% (excluding 29 `@Disabled`) | Parity is non-negotiable per AAP §0.6.11 ("non-negotiable PR gate").                                                            |
| SLO-5   | SLI-5 Decimals coverage gate                     | 100%                            | Binding hard gate, not aspirational. See `java/README.md` §8.                                                                  |
| SLO-6   | SLI-6 JFR Decimals regression band               | ≤ 1.10                          | AAP §0.6.11 mandate. See §3.                                                                                                   |

### 2.2 How to lift `TBD pending baseline` markers

For every SLO marked `TBD pending baseline`:

1. Operate the system in PROD (or a PROD-equivalent staging
   environment) for at least 14 consecutive successful nightly
   chains.
2. Compute the actual p50 / p95 / p99 from the SLI-2 / SLI-3
   data sources documented in §1.
3. Propose a target value equal to the observed p99 plus a 20%
   margin (this is the conventional starting point; the business
   may require tighter targets for revenue-critical steps).
4. Raise a PR updating this file, set the value, REMOVE the
   `TBD pending baseline` marker, and require sign-off from the
   business owner of the affected step.
5. Update the alerting integration (§5) to fire on threshold
   breaches.

### 2.3 SLO review cadence

Each SLO is reviewed quarterly (see §7). Any SLO that has been
breached more than once in a quarter triggers a root-cause
analysis and either a target widening (with business approval)
or an engineering investment to restore the SLO.

---

## 3. JFR Performance Baseline Integration

The JFR (Java Flight Recorder) integration is the single
mechanism by which SLO-6 is enforced AT TEST TIME (CI), not just
in production.

### 3.1 The executable contract

`JfrBaselineTest` (in
`java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/perf/JfrBaselineTest.java`)
defines the canonical Decimals workload (a deterministic sequence
of `BigDecimal` arithmetic operations using `MathContext.DECIMAL128`
and the COBOL-faithful `RoundingMode` selections). The test:

1. Captures a JFR `Recording` of the workload using the JDK
   `default` configuration.
2. Computes the median wall-clock duration across a configurable
   number of iterations.
3. Compares the measured median to the committed baseline value
   in `carddemo-tests/src/test/resources/perf/baseline.properties`.
4. Asserts `measured <= baseline * (1 + tolerance)` where
   `tolerance == 0.10` per AAP §0.6.11.

### 3.2 COLLECTION mode (current default)

The baseline file currently contains:

```
decimals.workload.median.nanos=PLACEHOLDER
decimals.workload.tolerance=0.10
```

When the median-nanos value equals the literal string
`PLACEHOLDER`, `JfrBaselineTest` runs in **COLLECTION mode**:

* The workload is executed.
* The measured median is logged at INFO level:
  `JFR baseline running in COLLECTION mode; measured Decimals workload median = N ns`
* The regression assertion is SKIPPED.

COLLECTION mode is the correct default state for the Refine-PR
scope because the CI runner is NOT a stable reference environment
(GitHub-hosted runners have variable CPU and noisy-neighbor
behavior). The Refine-PR explicitly allows leaving the
`PLACEHOLDER` intact rather than committing a non-authoritative
value.

### 3.3 ENFORCEMENT mode (target state)

ENFORCEMENT mode is enabled by replacing the `PLACEHOLDER` with an
integer measured on a stable reference environment:

```
decimals.workload.median.nanos=12345678
decimals.workload.tolerance=0.10
```

In ENFORCEMENT mode the test ASSERTS that the measured median is
≤ baseline * 1.10. A breach fails the build.

### 3.4 Recommended capture procedure (for SRE/perf team)

1. Provision a stable reference environment:
   * Dedicated bare-metal or pinned-VM host (no noisy
     neighbours).
   * Same CPU class as the PROD batch host (within the same
     processor family and generation).
   * Same JDK 25 build; same `-XX:+UseCompactObjectHeaders
     -XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational`
     JVM flags as PROD.
2. Run the JFR baseline test 100 times consecutively:
   ```bash
   cd java
   for i in $(seq 1 100); do
       mvn -B -ntp -pl carddemo-tests -Dtest=JfrBaselineTest test 2>&1 \
         | grep 'measured Decimals workload median'
   done | tee /tmp/baseline-capture.log
   ```
3. Compute the median across all 100 captures (use `awk` /
   `python` / `R` as preferred).
4. Update `carddemo-tests/src/test/resources/perf/baseline.properties`:
   * Replace `PLACEHOLDER` with the integer median.
   * Add a comment line above documenting the capture host,
     date, and JDK build.
5. Remove the `@Disabled` annotation on
   `JfrBaselineTest#decimalsWorkloadWithinTolerance` (if present)
   and commit both changes in the SAME PR.
6. Watch the next 7 CI runs to confirm the assertion holds
   stably; widen the workload's iteration count if jitter is too
   high.

### 3.5 What does NOT count as a stable baseline

* **CI runners (GitHub-hosted, Linux Foundation pool, etc.)** —
  too much noise. If the Refine-PR-suggested "directional CI
  capture" is committed, it MUST be annotated as
  `# NON-AUTHORITATIVE — replace with stable-host capture` and
  the assertion MUST remain `@Disabled`.
* **Developer laptops** — too much thermal throttling and
  per-machine variance.
* **Cloud VMs without CPU pinning** — hypervisor steal and
  hyperthread contention will produce a noisy baseline.

### 3.6 What to do if the assertion fails

A JFR regression failure indicates a real performance regression
in `Decimals` (the central hot path for all monetary arithmetic).
The triage flow:

1. Confirm the failure reproduces on the SAME stable host.
2. `git bisect` the regression to a single commit.
3. Inspect the bisected commit for changes to:
   * `Decimals.java` (Decimals utility)
   * `BigDecimal` arithmetic sites in `carddemo-application`
     (especially POSTTRAN's per-record loop)
   * JVM flag changes
4. Either revert the offending change OR re-baseline (only if the
   regression is deemed acceptable by the SRE/perf team).
5. If re-baselining, capture a new median on the stable host and
   commit a NEW baseline.properties; do NOT silently widen the
   tolerance above 0.10 (AAP §0.6.11 forbids this).

---

## 4. Error Budget Template

An error budget is the **allowed unreliability** for a given SLO
over a rolling window. Spending the budget on incidents is
acceptable; running out triggers a feature freeze on the relevant
component until the SLO is restored.

### 4.1 SLO-1 batch success rate error budget

* **SLO**: 99.0% chain success over 30-day rolling window.
* **Budget**: 1.0% of attempted runs per window.
* **Concrete**: with ~30 attempted runs per month, the budget is
  ~0.30 failed runs per month. In practice, ANY failed run
  consumes the entire month's budget.
* **Burn-rate alarms**: alert (NOT page) on a single failed run;
  page when 2 failed runs occur within 14 days.

### 4.2 SLO-2 duration error budget (per step)

* **SLO**: per-step p95 ≤ `TBD pending baseline`.
* **Budget**: ≤ 5% of runs may exceed the p95 target without
  triggering a budget-spend event. (This is by construction —
  p95 = 5% over.) A run exceeding p99 IS a budget event.
* **Burn-rate alarms**: page when 3 consecutive runs exceed p99.

### 4.3 SLO-3 throughput error budget (per step)

* **SLO**: per-step p50 throughput ≥ `TBD pending baseline`.
* **Budget**: ≤ 5% of runs may fall below the p50 target.
* **Burn-rate alarms**: alert when 2 consecutive runs are below
  50% of the p50 target.

### 4.4 SLO-4 parity-test pass rate error budget

* **SLO**: 100% (excluding `@Disabled`).
* **Budget**: ZERO. Any parity-test failure halts the PR.
* **Burn-rate alarms**: build fails the PR immediately.

### 4.5 SLO-5 Decimals coverage gate error budget

* **SLO**: 100%.
* **Budget**: ZERO. Coverage gate is binding.
* **Burn-rate alarms**: build fails the PR immediately.

### 4.6 SLO-6 JFR regression band error budget

* **SLO**: measured median ≤ baseline * 1.10.
* **Budget**: ZERO in ENFORCEMENT mode. (Until ENFORCEMENT mode
  is enabled per §3.3, this SLO is informational only.)
* **Burn-rate alarms**: build fails the PR in ENFORCEMENT mode.

### 4.7 Error-budget reporting template

```
SLO-N error budget status (window: YYYY-MM-DD to YYYY-MM-DD)
- Target:        99.0%
- Achieved:      99.7%
- Budget total:  X.Xh / Yh
- Budget spent:  Z incidents (top causes: ...)
- Budget left:   (target - achieved) * window_minutes
- Burn rate:     1.4x (slow) / 14x (fast)
- Action:        none / monitor / freeze
```

Reports are produced weekly by the SRE pipeline and reviewed in
the weekly operational review.

---

## 5. Alerting Integration

The alerts below are PROPOSED; the SRE team integrates them with
PagerDuty / Opsgenie / etc. per the organisation's alerting
playbook.

### 5.1 P1 (page on-call immediately)

* `AbendException` in any step (see `java/RUNBOOK.md` §4).
* `Sort order violation` log signature (AAP §0.6.6 invariant
  breach).
* `Decimals: scale mismatch` log signature.
* Exit code 16 from any Phase 2 step (the critical chain).
* Decimals coverage gate failure in CI.

### 5.2 P2 (alert on-call within business hours)

* `FileStatusException` in any non-Phase-2 step.
* Exit code 12 from any Phase 1 (load) step.
* Exit code 8 from any reporting step.
* SLO-1 budget burn rate ≥ 5x.

### 5.3 P3 (ticket; no page)

* JFR `WARN: JFR regression DETECTED` log signature in CI.
* Any new `@Disabled` test added to `carddemo-tests` (track for
  follow-on PR to enable).
* GDG retention pruning failures (cron job emitting non-zero
  exit).

---

## 6. Capacity Planning

The Java port is single-jar-per-step with the JVM tuning baseline
documented in `java/README.md` §5. Capacity assumptions:

* **CPU**: each batch step is single-process; one shaded jar per
  step. POSTTRAN and CREASTMT benefit from virtual-thread
  fan-out (AAP §0.6.6) and can scale to ~100 concurrent virtual
  threads per process without platform-thread thrashing.
* **Memory**: per-process default `-Xmx` SHOULD be set to 4 GB
  for the critical chain steps (POSTTRAN, INTCALC, CREASTMT) and
  2 GB for the others. Use `-XX:+UseCompactObjectHeaders` to
  reduce heap by ~20–30% per AAP §0.6.4.
* **Storage**: `carddemo.work.path` SHOULD have ≥ 10 GB free
  for CREASTMT's intermediate artifacts (statement HTML can be
  bursty). `carddemo.output.root` SHOULD have ≥ 50 GB free to
  accommodate a 30-day GDG retention window for TRANBKP +
  COMBTRAN outputs.
* **Network**: file-based default has zero network dependency
  (per AAP §0.7.5). If JDBC adapter is later enabled, capacity
  must include the JDBC driver latency budget.

---

## 7. Periodic Review Cadence

| Cadence       | Activity                                                                                              |
|---------------|-------------------------------------------------------------------------------------------------------|
| Daily         | Operator reviews the nightly batch exit codes and any P1/P2 alerts (§5).                              |
| Weekly        | SRE reviews SLI-1 success rate and SLI-2 duration percentiles; produces the error-budget report (§4.7). |
| Monthly       | SRE + Engineering review SLO-2 baselines; re-baseline if a step's duration distribution shifts >20%.   |
| Quarterly     | SRE + Business owner review the FULL SLO catalog; widen/tighten targets per business needs.            |
| Annually      | Re-capture the JFR Decimals baseline on the current PROD host class (§3.4); update SLO-6 baseline.    |

---

_End of SRE.md. Append additional SLIs, SLOs, or error-budget
templates as the operational footprint grows; treat every new
addition as a tracked PR with explicit business sign-off._
