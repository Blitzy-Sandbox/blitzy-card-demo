<!--
  ******************************************************************
  * Program     : DECISION_LOG.md
  * Application : CardDemo
  * Type        : Documentation - migration decision register
  * Function    : Records every non-trivial mechanism substitution, fidelity
  *               choice, deliberately preserved legacy defect, labelled
  *               deviation, resolved conflict and residual risk taken while
  *               migrating the AWS CardDemo credit-card application from IBM
  *               Enterprise COBOL under CICS, VSAM, JCL and BMS to Java 25
  *               with Spring Boot 3.5.11. Each entry carries its source
  *               locator, its target symbol, the alternatives that were
  *               rejected, the observable consequence, a severity and a
  *               verification status.
  * Source      : app/cbl/COACTUPC.cbl, app/cbl/CBTRN02C.cbl,
  *               app/cbl/CBACT04C.cbl, app/cbl/CBSTM03A.CBL,
  *               app/cbl/CBSTM03B.CBL, app/cbl/CBTRN03C.cbl,
  *               app/cbl/COTRN02C.cbl, app/cbl/COBIL00C.cbl,
  *               app/cbl/CORPT00C.cbl, app/cbl/COUSR03C.cbl,
  *               app/cbl/COSGN00C.cbl, app/jcl/CREASTMT.JCL,
  *               app/jcl/POSTTRAN.jcl, app/jcl/INTCALC.jcl,
  *               app/jcl/COMBTRAN.jcl, app/jcl/DEFGDGB.jcl,
  *               app/jcl/REPTFILE.jcl, app/jcl/DUSRSECJ.jcl,
  *               app/proc/TRANREPT.prc, app/csd/CARDDEMO.CSD,
  *               app/catlg/LISTCAT.txt, app/cpy/CVTRA07Y.cpy,
  *               app/data/ASCII/** @ 7756d89
  ******************************************************************
  * Copyright Amazon.com, Inc. or its affiliates.
  * All Rights Reserved.
  *
  * Licensed under the Apache License, Version 2.0 (the "License").
  * You may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *    http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing,
  * software distributed under the License is distributed on an
  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
  * either express or implied. See the License for the specific
  * language governing permissions and limitations under the License
  ******************************************************************
-->

# Decision Log

<a id="about"></a>

## 1. About this document

<a id="what-this-is"></a>

### 1.1 What this document is

This is the **decision register** for the CardDemo migration. Where
[`docs/validation-gates.md`](docs/validation-gates.md) records *what was proved*, this
document records *what was decided and why* — every point at which the obvious
mechanical translation of a COBOL construct was either rejected, adopted with a caveat,
or adopted while knowingly reproducing a defect.

It exists because behavioural parity is this project's acceptance contract, and parity
produces artefacts that look wrong to a reader who has not seen the source: an empty
method that is genuinely called, a reject code that can never be observed, a report
label that names the wrong entity, a two-byte truncation reproduced on purpose. Without
a written decision behind each of those, a later maintainer "fixes" it and silently
breaks parity. Every such artefact is therefore cited here, with the source locator that
justifies it.

The register is also the **tracking reference** the project's single user-specified rule
requires. *Rule 1: Build Verify* clause B forbids dead code and forbids deferred work
that carries no owner or tracking reference. Some artefacts in the Java tree are
deliberately retained no-ops or deliberately unreachable values, and **every one of them is
enumerated individually** — with its own stable identifier, its own `app/**` locator, its
reference status and its own reason — in [§15](#noop-registry). The conflict that admits them
is resolved at [DL-CR-01](#dl-cr-01). They are compliant precisely because they are tracked
there rather than left as bare markers in code.

**No global total for that set is asserted anywhere in this document.** The census is
derived mechanically — the harness writes `dispositions.registeredParityNoOps`,
`dispositions.calledParityNoOps` and `dispositions.unreferencedParityNoOps` into
`target/gate-verification/gate-verification-summary.properties`, and asserts the register and
the tree are **the same set in both directions**, so an unregistered member fails the build and
so does a row whose method no longer exists. Two earlier framings of this paragraph are
**withdrawn**: *"Three artefacts in the Java tree are deliberately retained no-ops"*, which
stated a figure as a fixed property of the project, and the wording that replaced it, which
pointed at [DL-CR-01](#dl-cr-01) as though its three named sites were the whole register. Read
the properties, not this prose, if the two ever disagree.

<a id="anchor-commit"></a>

### 1.2 Anchor commit and citation form

Every claim about the legacy system carries an inline citation of the form
`[app/<path>:L<line>]`, and every one was established by reading the file at

> **`7756d895ffeb65f7ea72aaa609e356d9899afcec`** (short **`7756d89`**)

which is the commit at which the COBOL corpus was frozen. The corpus under `app/` is
**read-only**: no program, copybook, mapset, job, procedure or data file was edited,
moved, renamed or reformatted. It remains simultaneously the parity oracle, the source of
every field contract, and the traceability anchor, and it loses all three roles the
moment it is touched.

Line numbers in citations are 1-based and were read with
`awk 'NR>=a && NR<=b'` against the frozen file, not inferred from prose. Where the
project's own technical specification disagreed with the file, **the file won** and the
disagreement is recorded in [§2.2](#corrections).

<a id="identifiers"></a>

### 1.3 Stable identifiers

Each entry carries a permanent identifier and an explicit HTML anchor, so an inbound
citation survives any later rewording of the heading:

| Prefix | Classification | Anchor form |
|---|---|---|
| `DL-AR-nn` | Architecture decision | `#dl-ar-01` |
| `DL-MS-nn` | `MECHANISM SUBSTITUTION` | `#dl-ms-01` |
| `DL-PP-nn` | `PARITY-PRESERVED` | `#dl-pp-01` |
| `DL-LD-nn` | `PRESERVED LEGACY DEFECT` | `#dl-ld-01` |
| `DL-DV-nn` | `DEVIATION` | `#dl-dv-01` |
| `DL-CR-nn` | `CONFLICT RESOLUTION` | `#dl-cr-01` |
| `DL-RR-nn` | `RESIDUAL RISK` | `#dl-rr-01` |
| `DL-RM-nn` | Remediation decision — [§14](#remediation) | `#dl-rm-01` |
| `NOOP-…` | A row of the parity no-op registry — [§15](#noop-registry) | none; cited by identifier |

**The no-op registry's identifiers are deliberately not ordinal.** A row is keyed
`NOOP-<simple class name>-<method name>` — or, for the artefacts that are not methods,
`NOOP-<program>-<tag>` — so the identifier *is* the identity. That is what lets the register
grow and shrink with the tree without renumbering, and it is why the register declares no
total: see [§15.1](#noop-membership).

**Identifiers are never reused and never renumbered.** A superseded decision is marked
`Superseded` and kept, with a pointer to the decision that replaced it; it is not
deleted, because the citations pointing at it live in Javadoc across the Java tree.

The `DL-` prefix is deliberate. `docs/validation-gates.md` already publishes register
identifiers in the bare forms `B-n`, `H-n`, `M-n`, `L-n`, `D-n`, `Q-n`, `V-n` and `R-n`;
an unprefixed scheme here would collide with them and make a cross-reference ambiguous.
Those identifiers are cited by name throughout this document.

<a id="classifications"></a>

### 1.4 Classifications, and why the distinction is load-bearing

| Classification | Means | The test that decides it |
|---|---|---|
| `MECHANISM SUBSTITUTION` | A different mechanism produces the **same observable behaviour** | Could a black-box observer tell the two apart? If no, it is a substitution |
| `PARITY-PRESERVED` | Source behaviour reproduced exactly, **including** behaviour that reads like a bug | Would a reviewer be tempted to "fix" this? If yes, it needs an entry |
| `PRESERVED LEGACY DEFECT` | A genuine defect **in the frozen corpus**, reported and not repaired | Is the defect in `app/`, and would repairing it change measured output or require editing `app/`? |
| `DEVIATION` | Observable behaviour **differs** from the source, knowingly | Is there an input for which source and target differ? If yes, it is a deviation and must be labelled |
| `CONFLICT RESOLUTION` | Two binding requirements collided and one was chosen | Did honouring one requirement force breaking another? |
| `RESIDUAL RISK` | Knowingly accepted exposure, not addressed by this work | Is there a real exposure with no fix in this change? |

The line between the first and the fourth carries the weight of this document. Calling a
deviation a substitution is the single most damaging thing this register could do,
because it converts a disclosed behaviour change into a hidden one. Where the target is
better than the source — and in two places it demonstrably is — the entry says
**DEVIATION** and says why the improvement is not equivalence:
[DL-DV-01](#dl-dv-01) and [DL-DV-03](#dl-dv-03).

<a id="statuses"></a>

### 1.5 Verification statuses

| Status | Means |
|---|---|
| `Source-verified` | The cited source locator was read at `7756d89` and says what the entry claims |
| `Target-verified` | The named Java symbol was confirmed to exist, at the stated path, with the stated shape |
| `Test-verified` | A named, existing test asserts the behaviour, and it was observed to pass locally |
| `Gate-pending` | The decision is implemented, but the **gate-level** evidence that would prove it end to end has not been published. See [§1.7](#no-invention) |
| `Superseded` | Replaced by a later decision, which is named |

A decision may hold several statuses; `Source-verified` plus `Target-verified` plus
`Gate-pending` is the normal state of an implemented decision in this branch.

<a id="severity"></a>

### 1.6 Severity scale

Severity classifies **the consequence of getting the decision wrong**, not the effort of
implementing it. The four levels are those *Rule 1: Build Verify* clause F requires.

| Severity | Means here |
|---|---|
| **Blocker** | The build does not work, or the system is unusable, if this is decided differently |
| **High** | A security exposure, or a parity break that a reviewer would not catch by reading |
| **Medium** | A parity break that a test or a careful reader would catch |
| **Low** | Cosmetic, documentary, or affecting only readability |

**These four bands govern the whole repository, not only this file.** A review found that the
Java tree, the provisioning script, the profiles and the workflow classified their own
findings on a different four-level scale inherited from the reviews that raised them —
`Critical`, `Major`, `Minor` and `Informational`. Those are **not** the bands clause F
requires, and two scales in one repository make a severity uncomparable across the artefacts
that carry it. Every such classification was remapped ordinally, in one pass:

| Superseded band | Mandated band |
|---|---|
| `Critical` | **Blocker** |
| `Major` | **High** |
| `Minor` | **Medium** |
| `Informational` | **Low** |

Two things are deliberately **not** changed by that remapping.

**External ratings keep their own vocabulary and are labelled as external.** CVSS labels a
score range `HIGH` and `CRITICAL`, and the image scanner labels its findings with the same
two words. Those are the vendors' ratings, not this project's severities, and rewriting them
would misquote the tool that produced them. Every site that states one now says whose scale
it is — `pom.xml`, `.github/workflows/build.yml`, `README.md`,
[`docs/validation-gates.md`](docs/validation-gates.md),
[`docs/onboarding-guide.md`](docs/onboarding-guide.md) and
[`docs/technical-specifications.md`](docs/technical-specifications.md).

**Finding identifiers are the raising review's, and are retained verbatim.** Identifiers of
the form `C-01`, `M-11`, `m-02`, `I-01` and `F-6` appear throughout the Java tree because the
reviews that raised those findings assigned them, and a citation that cannot be traced back
to the report it came from is worth less than an unfashionable letter. **The letter is part of
an opaque identifier and is never a severity**; the severity always appears as an explicit
band beside it. Renaming them would break every inbound citation for a cosmetic gain.

<a id="no-invention"></a>

### 1.7 No invention, and no gate is passed here

Two rules govern what may appear in an evidence field.

**Missing evidence is named, never filled.** Where an implementation or a test artefact
does not exist, the field reads exactly:

> `Not available — implementation/evidence not yet generated`

followed by the specific prerequisite that would close it. There are no `TBD`, `TODO` or
`FIXME` placeholders anywhere in this document; a placeholder in a register is worse than
an admission, because it reads as an oversight rather than as a disclosure.

**No gate is reported as passed by this document, and none may be.** Gate results live in
[`docs/validation-gates.md`](docs/validation-gates.md), and **that ledger is the only
place a result may be read**; this register cites the anchor and stops there, so a result
recorded, revised or withdrawn over there never has to be chased through here. A design
intention is not a pass; the Agent Action Plan describing a behaviour is not evidence that
the behaviour was measured.
Where this register cites a gate it cites the *obligation*, using the stable anchors that
document publishes — [Gate 1](docs/validation-gates.md#gate-1) through
[Gate 8](docs/validation-gates.md#gate-8).

Named unit and integration tests are a different matter: those exist in this branch and
are cited by class name. Every test class named in this document was confirmed present
on disk before being cited — see [§12](#verification).

<a id="template"></a>

### 1.8 The entry template

Every entry carries the same fields, in the same order:

| Field | Content |
|---|---|
| **Classification** | One of the six in [§1.4](#classifications) |
| **Severity** | One of the four in [§1.6](#severity) |
| **Source evidence** | Program, paragraph and line locators at `7756d89` |
| **Target** | The Java file and symbol that carries the decision |
| **Decision** | What was decided, in one sentence |
| **Alternatives considered** | What was rejected, and on what ground |
| **Rationale** | Why the decision follows from the evidence |
| **Observable consequence** | What a caller, operator or reviewer actually sees |
| **Test / gate evidence** | Named existing tests; gate obligation; or the "Not available" form |
| **Remediation / follow-up** | What would have to happen for this to change, and who owns it |
| **Verification** | Status from [§1.5](#statuses), with the date |

**Three fields are on every entry without exception** — *Classification*, *Severity* and
*Verification* — and a checked count of those appears in [§12](#verification).

**One field varies by kind, deliberately.** A mechanism substitution, a preserved defect
or a parity decision always opens with *Source evidence*, because a COBOL locator is what
justifies it. A conflict resolution in [§7](#conflicts) cannot: a collision between two
binding requirements has no single source line. Every one of them therefore opens with
*The two requirements*, followed by *Where they collide*. A residual risk in [§8](#risks)
usually cannot either, because an accepted future exposure often has no locator at all;
those open with *Decision*, *Statement* or *Status of the concern* instead, and the one risk
that does rest on a locator — [DL-RR-03](#dl-rr-03) — opens with *Source evidence* like any
other entry, because the substitution is licensed by the absence of evidence rather than by
the section an entry sits in. **No count of either kind is stated here**, deliberately: the
rule is what binds, and the counts belong to the parser that derives them, published at
[§12.1](#selfcheck-parser) and re-derived on every build by
`src/test/java/com/cardemo/unit/infrastructure/InventoryCountGateTest.java`. An earlier
revision of this paragraph carried two hand-maintained totals, "seven conflict resolutions"
and "those fifteen entries", and both had gone stale; they are withdrawn rather than
re-stated, because a number maintained in prose beside a rule adds nothing to the rule and
rots independently of it. The substitution itself is stated here rather than left for a
reader to notice, because a template claimed as uniform and then quietly varied is itself
the sort of inaccuracy this register exists to prevent.

Unless an entry says otherwise, its verification date is **Thursday, 30 July 2026**, the date
of the revision that first published it. An entry verified later carries its own later date in
its **Verification** field, and the latest such date in this document is **Friday, 7 August
2026**. A date is therefore read from the entry, never inferred from this sentence.

<a id="not-this"></a>

### 1.9 What this document is not

- **It is not the gate ledger.** Gate definitions, commands, assertions and results are
  in [`docs/validation-gates.md`](docs/validation-gates.md). This document links to gate
  anchors and never restates a gate's result.
- **It is not the traceability matrix.** Paragraph-to-method mapping across all 28
  programs belongs in [`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md), which **has
  since been authored** — see [DL-RR-07](#dl-rr-07). This document links to its row
  anchors and never restates a row: the mapping is cited, never copied.
- **It is not a risk register duplicate.** `docs/validation-gates.md` §12 carries the
  severity-classified findings, the legacy-defect table, the quirk table, the deviation
  table and the deferred-hardening list. This document carries the **reasoning and the
  rejected alternatives** for those same decisions, which that table format cannot hold,
  and cites its identifiers rather than copying its rows.
- **It is not a substitute for the Javadoc.** Each Java method that implements a decision
  carries its own source citation. This register is the index over those citations, not a
  transcript of them.

<a id="update-policy"></a>

### 1.10 Update policy

1. **Any change to observable behaviour needs an entry here before it merges.** If the
   change is a deviation, it is labelled `DEVIATION` — never softened into
   `MECHANISM SUBSTITUTION`.
2. **Entries are appended, never rewritten in place.** Correcting an entry means adding
   the correction and marking the old text as corrected, so the correction is visible to
   the next reader rather than invisible in history.
3. **Every claim carries a locator.** A statement about the source without an
   `app/<path>:L<n>` citation is not admissible, because the corpus is the only authority.
4. **Statuses are demoted freely and promoted only on evidence.** If a cited test is
   deleted, the entry drops to `Gate-pending` in the same change.
5. **Identifiers are permanent.** See [§1.3](#identifiers).
6. **`app/` is never edited to make an entry true.** If the corpus contradicts an entry,
   the entry is wrong.

<a id="verify-this"></a>

### 1.11 How to verify this document

This file has no build step. It is verified by re-reading the corpus and re-resolving the
symbols it names. The commands actually run against this revision, and their honest
results, are recorded in [§12](#verification). In outline:

```bash
# every cited app/... path resolves, with its exact case
grep -oE '\bapp/[A-Za-z0-9_./-]+' DECISION_LOG.md | sort -u | while read -r p; do
  [ -e "$p" ] || echo "MISSING: $p"
done

# every cited src/... path resolves
grep -oE '\bsrc/[A-Za-z0-9_./-]+\.(java|sql|xml|yml)' DECISION_LOG.md | sort -u | while read -r p; do
  [ -e "$p" ] || echo "MISSING: $p"
done

# no placeholder may survive in a register. Every hit must be a sentence naming the
# prohibition or this command itself - never a deferred item. See section 12 for the
# expected hit list at this revision.
grep -nE '\b(TBD|TODO|FIXME|XXX)\b' DECISION_LOG.md

# the guard that owns this file's existence premise
./mvnw -B -ntp -o test -Dtest=DocumentationConsistencyTest -Djacoco.skip=true
```

---

<a id="scope"></a>

## 2. The decided shape of the migration

<a id="shape"></a>

### 2.1 What was decided, and the figures it rests on

| Decision | Value | Established by |
|---|---|---|
| Repository | **The same repository, in place.** The Java tree is created at `src/`, in Maven standard layout, beside the frozen `app/` tree. No new repository and no wrapper sub-directory | Requirement; `app/` must remain the parity oracle |
| Package root | **`com.cardemo`** — one spelling, used in main sources, test sources, configuration and documentation | `pom.xml` `<groupId>com.cardemo</groupId>`, artefact `carddemo`, version `1.0.0` |
| Deployable shape | **One modular monolith**, one Spring Boot JAR — explicitly not microservices | [DL-AR-01](#dl-ar-01) |
| COBOL corpus | **28 programs, 19,254 lines.** Verified: `app/cbl/` holds 28 files and `cat app/cbl/* \| wc -l` reports `19254` | Direct measurement at `7756d89` |
| Online surface | **17 sourced screen programs → 17 endpoints across 8 controllers.** The CSD defines 18 transactions and 18 programs but only 17 mapsets; the eighteenth program has no source | [app/csd/CARDDEMO.CSD:L211], [:L388-L390] and [DL-RR-03](#dl-rr-03) |
| Data substrate | **10 base VSAM clusters + 3 alternate indexes + 3 paths → 11 JPA entities over PostgreSQL 16**, schema by three Flyway migrations | [app/catlg/LISTCAT.txt] — 10 `0CLUSTER` headers, 3 `0AIX`, 3 `0PATH` |
| Batch substrate | **29 JCL members**, including `CREASTMT.JCL` with an **uppercase** extension, → a 5-stage Spring Batch pipeline | `ls app/jcl/ \| wc -l` reports 29 |
| Seed and fixtures | **9 ASCII fixtures**, the posting fixture named **`dailytran.txt`** — the full spelling, not the mainframe DD name `DALYTRAN` | `ls app/data/ASCII/` |
| Generation groups | **7 GDG bases** → prefixed keys over a versioned object store | [app/catlg/LISTCAT.txt] — 7 `0GDG BASE` headers |

Two of those rows are traps rather than trivia, and both are recorded because a plausible
implementation gets them wrong. A `*.jcl` glob is case-sensitive on the platforms this
build runs on and therefore **silently drops `CREASTMT.JCL`**, which is the only source
for statement generation — an entire feature disappears from scope with no error. And the
posting fixture is spelled `dailytran.txt` while every DD name, dataset name and comment
in the corpus spells it `DALYTRAN`; a test resource path built from the corpus spelling
resolves to nothing.

<a id="corrections"></a>

### 2.2 Corrections this register carries against the technical specification

Every entry below was checked against the frozen file, and the file governs. These are
recorded rather than silently applied, because generated code or a later review that
trusted the superseded figure would diverge from the corpus.

| # | Superseded claim | What the source actually says | Why it matters | Severity |
|---|---|---|---|---|
| C-1 | The user-security cluster "is defined in JCL rather than catalogued" | It **is** catalogued. [app/catlg/LISTCAT.txt:L3846] carries `0CLUSTER ------- AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS` with `KEYLEN 8`, `AVGLRECL 80`, `MAXLRECL 80`, `RKP 0` — consistent with `KEYS(8,0) RECORDSIZE(80,80)` at [app/jcl/DUSRSECJ.jcl:L65-L66] | The catalogue is the authority for the schema. A reader told the cluster is absent from it would take the JCL as the only geometry source and lose the corroboration | Low |
| C-2 | The monthly report period is "month-to-date, not a full calendar month" | It is a **full calendar month**. [app/cbl/CORPT00C.cbl:L223-L234] sets the day to 1, advances the month (rolling the year at `> 12`), then computes `DATE-OF-INTEGER(INTEGER-OF-DATE(x) - 1)` — the first of next month minus one day, i.e. **the last day of the current month** | A month-to-date implementation returns fewer rows than the source for every run except one made on the last day of a month. This is a parity break invisible to code review | **Medium** |
| C-3 | The report-submission program embeds "eighteen" 80-byte job cards | It embeds **17**. Counting `05`-level entries in `JOB-DATA-1` at [app/cbl/CORPT00C.cbl:L82-L125] yields 17, three of which are composite groups whose sub-fields sum to 80 bytes each. The redefinition admits up to 1000 cards: [:L126-L127] | An off-by-one card census misstates the record that the queue message replaces | Low |
| C-4 | The change-detection comparison runs "twelve account predicates and a dozen customer predicates" | **16 account comparison terms over 10 account fields** at [app/cbl/COACTUPC.cbl:L4115-L4140], and **19 customer comparison terms over 17 customer fields** at [:L4152-L4186]. The inflation is the three dates, each compared as three separate substrings | The counts are what a paragraph-level coverage check asserts against. See [DL-PP-02](#dl-pp-02) | Low |
| C-5 | Posting "moves thirteen fields from the input record" | **12** fields are moved from the staging record at [app/cbl/CBTRN02C.cbl:L425-L436]; the thirteenth populated field is the **generated** processing timestamp at [:L437-L438]. Twelve moves, thirteen populated fields | A reader counting moves against the target finds twelve and concludes one is missing | Low |
| C-6 | The authoring host has "no container runtime — no client, no daemon and no socket" | Superseded by measurement. See [§2.4](#environment) | Four gates were described as unrunnable on an infrastructure claim that no longer holds | **Medium** |

One further correction is not a figure but a name. The statement programs are
`app/cbl/CBSTM03A.CBL` and `app/cbl/CBSTM03B.CBL` — **uppercase `.CBL`**, unlike the
other 26 programs, which use lowercase `.cbl`. Prose in the specification writes them
lowercase. Every citation in this document uses the on-disk case, and
[§12](#verification) verifies that every cited path resolves.

<a id="exclusions"></a>

### 2.3 Exclusions register — what was deliberately not built

Nothing in this list was skipped by omission. Each is excluded by decision, and the
decision is stated so that a reviewer does not read absence as oversight.

| Excluded | Reason | Severity of the exclusion |
|---|---|---|
| **Editing anything under `app/`** | The corpus is the parity oracle, the field-contract source and the traceability anchor. Migration is purely additive | **High** if violated |
| **Transaction `CDV1` and program `COCRDSEC`** | `COCRDSEC` **has no source anywhere in the repository**. Its name occurs exactly twice, both in the CSD: [app/csd/CARDDEMO.CSD:L211] defines the program and [:L390] names it as the target of `CDV1`, described at [:L389] as `DEVELOPER TRANSACTION - 1`. There is nothing to translate, so **no endpoint was invented**. This is why 18 CSD transactions yield 17 endpoints | Low — recorded in [DL-RR-03](#dl-rr-03) |
| **`app/cpy/UNUSED1Y.cpy`** | Dispositioned as dead. `grep -rl UNUSED1Y app/` matches **zero files** — the copybook is never `COPY`-ed, and its own text does not name it. No Java type corresponds to it | Low |
| **Parsing `app/data/EBCDIC/**`** | The twelve `.PS` files are retained as byte-level codepage reference only. The ASCII fixtures are the authoritative seed and test input, and no transcoding utility was built | Low |
| **`samples/**`** | Three z/OS compile templates, three build procedures and two binary emulator runtime bundles. `pom.xml` supersedes the set conceptually; nothing was ported | Low |
| **Any browser front end** | No 3270 emulation, no green-screen rendering, no single-page application, no component library and therefore no design system. The 17 symbolic maps are consumed as **DTO field contracts** and are not reimplemented as a user interface | Low |
| **Microservice decomposition, event sourcing, CQRS** | Refused on transactional grounds — see [DL-AR-01](#dl-ar-01) | **High** if violated |
| **Kubernetes, Helm, service mesh** | Container orchestration stops at Docker Compose | Low |
| **Live AWS accounts and real credentials** | All object-store, queue and topic interaction targets a local emulator. **Zero live credentials appear in any file**, and no code path may reach a real endpoint — see [DL-RR-05](#dl-rr-05) | **High** if violated |
| **Rewriting COBOL business rules to be "more correct"** | Parity is the contract. See [§6](#legacy-defects) and [§5](#named) | **High** if violated |
| **Deferred hardening (8 items)** | Partitioning, read replicas, pool tuning, transport security, rate limiting, URI versioning, generated API specification, encryption at rest. Each is accepted knowingly with a trigger, catalogued as `R-1`…`R-8` in `docs/validation-gates.md` [§12.6](docs/validation-gates.md#deferred) and summarised at [DL-RR-02](#dl-rr-02) | Medium to High per item |

<a id="environment"></a>

### 2.4 Environment evidence, dated

Environment claims go stale faster than anything else in a migration record, so this
section is dated and measured rather than asserted.

**Current reading — Friday, 7 August 2026, 20:08 UTC**, taken by invoking each tool. The full
ledger is [`docs/validation-gates.md` §2](docs/validation-gates.md#env-first):

| Tool | Result |
|---|---|
| `docker --version` | Docker version 29.7.0 |
| `docker info --format '{{.ServerVersion}}'` | 29.7.0 — **daemon reachable** |
| `java -version` / `javac -version` | openjdk 25.0.3 LTS, Temurin-25.0.3+9 / javac 25.0.3 |
| `mvn -v` | Apache Maven 3.9.11 |
| `localstack`, `aws`, `mkdocs` | LocalStack CLI 4.14.0, aws-cli 1.46.0, mkdocs 1.6.1 |
| `psql --version` | **NOT FOUND** — required by nothing; the database runs as a compose service |

**Historical — reading of Thursday, 30 July 2026, 06:56 UTC**, retained because the entries
below were first verified against it and because it is the reading correction **C-6** was
measured against. It recorded Docker 29.6.2 with a reachable daemon, and `java`, `javac`,
`mvn`, `localstack`, `aws` and `mkdocs` all **NOT FOUND** on the host.

Two consequences follow from the pair of readings:

- **A container runtime is available, and was available at both readings.** This supersedes
  correction **C-6**: the earlier claim that no client, daemon or socket existed is measurably
  wrong, so no gate is blocked by infrastructure.
- **The host JDK and build tool were absent at the historical reading and are present at the
  current one**, so a host-native build runs. The wrapper `./mvnw` pins Maven 3.9.11 and
  provisions it on first use, so that gap was a provisioning step and not a scope change.

This register publishes one current reading and defers the rest to
[`docs/validation-gates.md` §2](docs/validation-gates.md#env-first), because a second copy of a
dated measurement is the fastest way to create a contradiction.

**What this means for every gate-bearing entry below.** No entry may be read as claiming
that a container-dependent gate was executed. Availability of a runtime is a
precondition, not evidence.

---

<a id="architecture"></a>

## 3. Architecture decision

<a id="dl-ar-01"></a>

### DL-AR-01 — One modular monolith, not microservices

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | **Blocker** — decided the other way, parity is unreachable |
| **Source evidence** | Two multi-record units of work. **(1)** Account update writes the account record at [app/cbl/COACTUPC.cbl:L4065-L4071] and the customer record at [:L4085-L4091] inside one CICS unit of work, with `EXEC CICS SYNCPOINT ROLLBACK` at [:L4099-L4101] backing the pair out. **(2)** Daily posting performs the category-balance upsert, the account update and the transaction insert in immediate succession at [app/cbl/CBTRN02C.cbl:L440-L442], reaching three datasets from one record's processing |
| **Target** | `src/main/java/com/cardemo/CardDemoApplication.java` — a single `@SpringBootApplication`; 14 packages under one `com.cardemo` root; one deployable JAR (`carddemo-1.0.0.jar`) |
| **Decision** | Deliver a **single deployable modular monolith**. Modularity is expressed as package boundaries and constructor injection, not as network boundaries |
| **Alternatives considered** | **(a) Service-per-domain** — account, card, transaction, user, batch. Rejected: the two units of work above span domains, so distributing them requires compensating transactions. **(b) Monolith plus a separate batch service** — rejected: the posting job writes through the same repositories the online tier writes through, so the split duplicates the data layer or reintroduces a network hop inside a transaction. **(c) Event sourcing / CQRS** — rejected: it changes the persistence model, and the parity comparison is against fixed-width record images, not against a projection |
| **Rationale** | Atomicity is the invariant, and it is the one property a saga cannot reproduce. A compensating transaction is observable: it creates an interval in which a partially applied update is visible, and it converts a rollback into a second forward write. Both are behaviour changes, and behaviour change is forbidden. The source's rollback asymmetry ([DL-MS-12](#dl-ms-12), [DL-PP-01](#dl-pp-01)) makes the point sharper still: reproducing it depends on both writes sharing one boundary |
| **Observable consequence** | One process, one database connection pool, one transaction manager. Scaling is vertical or by replica of the whole unit. A caller sees an account-plus-customer update either wholly applied or wholly absent, exactly as the 3270 user did |
| **Test / gate evidence** | Package-boundary and layering assertions in `src/test/java/com/cardemo/unit/infrastructure/`; end-to-end shape exercised by `src/test/java/com/cardemo/e2e/OnlineTransactionE2ETest.java` and `src/test/java/com/cardemo/e2e/BatchPipelineE2ETest.java`. Gate-level: [Gate 5](docs/validation-gates.md#gate-5) and [Gate 8](docs/validation-gates.md#gate-8), whose results that ledger records |
| **Remediation / follow-up** | None outstanding. Any future decomposition proposal must first show how it reproduces the two units of work above without a compensating write; owner: whoever proposes it |
| **Verification** | `Source-verified`, `Target-verified`, `Gate-pending` — 30 July 2026 |

---

<a id="mechanisms"></a>

## 4. Mechanism substitutions — the sixteen binding transformation families

These sixteen are the invariants the whole migration is held to. Each replaces one legacy
construct with exactly one named Java or Spring mechanism. Entries here state the family
rule; where a family has a hard case, the hard case gets its own entry in
[§5](#named) and is cross-referenced.

<a id="dl-ms-01"></a>

### DL-MS-01 — Signed fixed-point COBOL numerics become `BigDecimal` and `NUMERIC(p,s)`

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | **High** — a floating-point substitution corrupts money silently |
| **Source evidence** | `PIC S9(10)V99` money fields on the account record, e.g. the snapshot redefinitions at [app/cbl/COACTUPC.cbl:L675-L680]; `PIC S9(09)V99` category balance; `PIC S9(04)V99` disclosure rate; `PIC S9(9)V99` transaction amount with the edited form `PIC +99999999.99` at [app/cbl/COTRN02C.cbl:L58-L59] |
| **Target** | `java.math.BigDecimal` on every monetary and rate field of the 11 entities under `src/main/java/com/cardemo/model/entity/`; `NUMERIC(p,2)` columns in `src/main/resources/db/migration/V1__create_schema.sql` |
| **Decision** | **Zero `float` and zero `double` in any financial field.** Scale comes from the PIC clause, rounding is `RoundingMode.HALF_EVEN`, and equality is `compareTo() == 0`, never `equals()` |
| **Alternatives considered** | **(a) `double`** — rejected outright: binary floating point cannot represent decimal cents exactly, and the parity comparison is against decimal text. **(b) `long` minor units** — rejected: it discards the declared scale, and three different scales are in play, so a single implicit scale would have to be chosen and remembered at every call site. **(c) One uniform `NUMERIC(12,2)`** — rejected: it widens two fields, see the next row |
| **Rationale** | `equals()` on `BigDecimal` compares scale as well as value, so `2.0` and `2.00` are unequal — a defect that appears only when one value came from the database and the other from a parsed fixed-width field, which is precisely the parity path. Two precisions genuinely differ from the common case and are honoured rather than rounded up to it: the category balance is `S9(09)V99` → `NUMERIC(11,2)`, and the disclosure interest rate is `S9(04)V99` → `NUMERIC(6,2)` |
| **Observable consequence** | Amounts round half-to-even at two decimals exactly as the source does; a value that overflows a narrower column is refused rather than silently truncated |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/model/SchemaStructureTest.java` for column precision; financial-field scanning in `src/test/java/com/cardemo/e2e/GateVerificationTest.java`. Gate-level: [Gate 6](docs/validation-gates.md#gate-6), whose result that ledger records |
| **Remediation / follow-up** | None outstanding. Any new monetary field must take its scale from a PIC clause and be added to the schema assertions in the same change |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-ms-02"></a>

### DL-MS-02 — Each `PARAGRAPH` / `SECTION` becomes one private method with a locator Javadoc

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | Medium — consolidation destroys the traceability the coverage gate reads |
| **Source evidence** | The corpus is organised entirely as numbered paragraphs, e.g. `9600-WRITE-PROCESSING` at [app/cbl/COACTUPC.cbl:L3888] with its paired `9600-WRITE-PROCESSING-EXIT` at [:L4105], and `1500-VALIDATE-TRAN` at [app/cbl/CBTRN02C.cbl:L370] |
| **Target** | The naming convention `<verb><Name><paragraphNumber>` plus a matching `…Exit()` where the source has an exit paragraph. Verified examples, all declared in `src/main/java/com/cardemo/service/account/AccountUpdateService.java`: `private void writeProcessing9600(`, `private void writeProcessing9600Exit(`, `private void checkChangeInRecord9700(`, plus `mainLine0000`, `receiveMap1100` and `compareOldNew1205` in the same class. **Each is cited by symbol rather than by line, per [DL-RM-10](#dl-rm-10)** |
| **Decision** | **One private method per source paragraph, no consolidation**, each carrying a Javadoc naming the paragraph and its line range |
| **Alternatives considered** | **(a) Idiomatic restructuring** into cohesive objects, which is the mainstream recommendation for this class of migration — rejected here, and the conflict is recorded as [DL-CR-05](#dl-cr-05). **(b) Merging trivial paragraphs** such as the `-EXIT` pairs — rejected: the exits are `GO TO` targets, and several are jumped to from more than one place, so merging changes control flow |
| **Rationale** | Paragraph correspondence is what makes coverage provable by inspection rather than by assertion. It is also what makes a parity defect diagnosable: a mismatch localises to a paragraph, and the Javadoc points straight at the lines to read |
| **Observable consequence** | No behavioural consequence. The cost is verbosity in the large services; the compensation is the citation on every method and the matrix published at [`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md) |
| **Test / gate evidence** | Citation resolution is enforced by `src/test/java/com/cardemo/unit/infrastructure/SourceCitationResolutionTest.java`, which checks that every `app/...` locator in the tree resolves against the frozen corpus. Gate-level: [Gate 7](docs/validation-gates.md#gate-7), whose result that ledger records; the matrix it reads is published at [`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md) — [DL-RR-07](#dl-rr-07) |
| **Remediation / follow-up** | **Done.** `TRACEABILITY_MATRIX.md` was authored by the traceability deliverable and publishes one row per paragraph, so the no-consolidation rule this entry states is now checkable rather than merely asserted — [DL-RR-07](#dl-rr-07) |
| **Verification** | `Source-verified`, `Target-verified`, `Gate-pending` — 30 July 2026 |

<a id="dl-ms-03"></a>

### DL-MS-03 — `COPY <member>` becomes a Java `import`, under three collapse rules

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | Medium |
| **Source evidence** | 28 copybooks in `app/cpy/`. Eleven are record layouts. Three are not data at all: `app/cpy/CSSTRPFY.cpy` is procedural and is `COPY`-ed into the procedure division — visible at [app/cbl/COACTUPC.cbl:L4199]; `app/cpy/CSSETATY.cpy` is a template resolved through `COPY … REPLACING`; `app/cpy/UNUSED1Y.cpy` is referenced nowhere |
| **Target** | Eleven entity types under `src/main/java/com/cardemo/model/entity/` — exactly one per record layout. The two customer layouts `app/cpy/CVCUS01Y.cpy` and `app/cpy/CUSTREC.cpy` differ only in the date-of-birth field name and resolve to the **single** `Customer` entity |
| **Decision** | One entity import per record-layout copybook, plus **three collapse rules**: **(i)** `CALL 'CSUTLDTC'` together with `app/cpy/CSUTLDPY.cpy` and `app/cpy/CSUTLDWY.cpy` collapse into one injected `service/shared/DateValidationService`; **(ii)** `CALL 'CBSTM03B'` collapses into one injected `service/shared/FileService`; **(iii)** the five 88-level lookup tables of `app/cpy/CSLKPCDY.cpy` collapse into one `service/shared/ValidationLookupService` reading three classpath JSON resources |
| **Alternatives considered** | **(a) Generating a constants class** from the lookup tables — rejected: over a thousand generated Java constants that add nothing to correctness and cannot be diffed against the source table by eye. **(b) One class per copybook, mechanically** — rejected: it would produce a second customer entity for a duplicate layout and Java types for a procedural copybook and a text template, neither of which is data. **(c) Importing the CICS-supplied copybooks** `DFHAID`, `DFHBMSCA`, `DFHATTR` — impossible: they are supplied by the transaction monitor and are not in the repository; they map onto framework mechanisms, not types |
| **Rationale** | A copybook is a textual include, not a type, so the mapping has to be decided per member rather than mechanically. The three collapses each replace many call sites with one injected dependency, which is what makes the call sites testable |
| **Observable consequence** | Lookup membership is editable as data rather than as code. `UNUSED1Y` has no Java counterpart, which is a documented absence rather than an omission — see [§2.3](#exclusions) |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/ValidationLookupServiceTest.java`, `src/test/java/com/cardemo/unit/service/DateValidationServiceTest.java`, `src/test/java/com/cardemo/unit/service/FileServiceTest.java`. Gate-level: [Gate 7](docs/validation-gates.md#gate-7); its result is recorded in that ledger, not here |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-ms-04"></a>

### DL-MS-04 — VSAM clusters, alternate indexes and paths become entities, finders and B-tree indexes

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | **High** — a missing index turns a keyed read into a table scan and a missing finder loses a query path entirely |
| **Source evidence** | [app/catlg/LISTCAT.txt] catalogues **10** base clusters, **3** alternate indexes and **3** paths. The alternate keys are placed physically: `CARDDATA.VSAM.AIX` has `KEYLEN 11` at alternate-key position 16 of the base record, and `TRANSACT.VSAM.AIX` has `KEYLEN 26` at position 304, which is the processing timestamp. The user-security cluster is catalogued at [:L3846] with `KEYLEN 8` and `MAXLRECL 80` — see correction **C-1** |
| **Target** | 11 `@Entity` types and 11 `JpaRepository` interfaces under `src/main/java/com/cardemo/model/entity/` and `src/main/java/com/cardemo/repository/`; three composite keys under `src/main/java/com/cardemo/model/key/`; three B-tree indexes in `src/main/resources/db/migration/V2__create_indexes.sql` |
| **Decision** | One entity plus one repository per base cluster; each alternate index becomes a **derived finder plus a non-unique B-tree index**; browse verbs (`STARTBR`, `READNEXT`, `READPREV`, `ENDBR`) become `Pageable` and ordered queries |
| **Alternatives considered** | **(a) Two indexes instead of three** — factually wrong; the catalogue carries three, each with a path, and the third is the processing-timestamp index. **(b) Unique indexes on the alternate keys** — rejected: the card-to-account and cross-reference-to-account relationships are many-to-one, so a unique index would refuse legitimate data. **(c) A single generic `find` with a specification API** — rejected: the derived finders correspond one-to-one to the paths the JCL and CICS definitions actually open, which is the evidence that the query exists |
| **Rationale** | The catalogue is the physical authority, so key lengths, record lengths and alternate-key offsets are taken from it rather than inferred from copybooks. Composite keys preserve **COBOL field order** exactly, because key order decides browse order and the reports depend on it |
| **Observable consequence** | Four datasets — `TCATBALF`, `DISCGRP`, `TRANCATG`, `TRANTYPE` — have **no** CICS file definition ([app/csd/CARDDEMO.CSD] defines 8 files), which is the evidence that they are batch-only. They are therefore reachable from batch and from repository tests, and are not exposed online |
| **Test / gate evidence** | `src/test/java/com/cardemo/integration/repository/RepositorySchemaAndFinderIntegrationTest.java` and the 11 per-repository tests in the same package; `src/test/java/com/cardemo/unit/model/SchemaStructureTest.java`. Gate-level: [Gate 8](docs/validation-gates.md#gate-8), whose result that ledger records |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Gate-pending` — 30 July 2026 |

<a id="dl-ms-05"></a>

### DL-MS-05 — `SEND MAP` / `RECEIVE MAP` become DTO-backed REST operations

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | Medium |
| **Source evidence** | 17 BMS mapsets in `app/bms/` with 17 generated symbolic maps in `app/cpy-bms/`, carrying **441 input fields** in total. An earlier revision of this row said **460**; that figure is **withdrawn** as unverified prose inherited from the specification. 441 is machine-derived, and the derivation is stated so it can be re-run: each screen field is generated as a level-**02** data item named `<FIELD>I` with a `PIC` clause, alongside a matching `<FIELD>L COMP PIC S9(4)` length item, so `for f in app/cpy-bms/*.CPY; do grep -cE '^ +02 +[A-Z0-9]+I +PIC ' "$f"; done` summed over the seventeen maps yields 441 — and the two independent counts, the `I` items and the `L` items, agree. Per map: `COACTUP` 54, `COACTVW` **37**, `COADM01` 20, `COBIL00` 10, `COCRDLI` 45, `COCRDSL` 15, `COCRDUP` 17, `COMEN01` 20, `CORPT00` 17, `COSGN00` 11, `COTRN00` 59, `COTRN01` 21, `COTRN02` 21, `COUSR00` 59, `COUSR01` 12, `COUSR02` 12, `COUSR03` 11. Note `COACTVW` is 37 and not the 36 the specification's own table gave. Six header fields recur on all seventeen maps: `TRNNAME X(4)`, `TITLE01 X(40)`, `CURDATE X(8)`, `PGMNAME X(8)`, `TITLE02 X(40)`, `CURTIME X(9)` |
| **Target** | 16 DTOs under `src/main/java/com/cardemo/model/dto/`; 17 operations across 8 `@RestController` classes in `src/main/java/com/cardemo/controller/` |
| **Decision** | Field names, types and **lengths** are taken from the symbolic maps exactly. Screen geometry — attribute bytes, length fields, cursor positions — is not modelled. **A declared length is a count of character positions, and in the target that count is a count of Unicode code points.** A `PIC X(n)` field is n single-byte EBCDIC positions, a substrate the target does not have: PostgreSQL counts and pads `character(n)` in characters, and a Java `String` measures itself in UTF-16 code units, which differ from characters for every supplementary-plane character. Every width guard on this contract therefore counts code points — the DTO projections, the entity and composite-key constructors, and the four admin services' screen-field checks — so that the inbound bound and the outbound bound are the same bound. Two boundaries are deliberately outside that rule and are measured in code units: the fixed-width **output record** writers, whose budget is bytes on a generated ASCII-only line rather than character positions of a screen field; and the three positional snapshot helpers of `AccountUpdateRequest`, whose declared component offsets are `substring` indices, so measuring in any other unit than the one they index in would make the check and the padding disagree. Bean-validation `@Size` and JPA `@Column(length = …)` also stay in code units, which is safe in one direction only and that is the direction they are in: a code-unit bound is never looser than the equivalent character-position bound, so a value they admit is always a value the guards admit |
| **Alternatives considered** | **(a) Reproducing the symbolic map quintuple** (length field, attribute byte, redefined alias, reserved bytes, data field) in each DTO — rejected: the attribute and length fields are 3270 presentation state with no meaning over HTTP. **(b) Free-form JSON with inferred lengths** — rejected: silent truncation or widening breaks parity in a way no test catches unless the contract is asserted |
| **Rationale** | The symbolic map is the only authority for a field's width, and the width is a contract: an eleven-digit account identifier that accepts twelve characters accepts input the source rejects. The three largest maps are large because of row arrays, not richer screens, which is why page sizes are fixed at 7, 10 and 10 rather than being made configurable |
| **Observable consequence** | Request validation refuses over-long values with a field-level error rather than truncating. Pagination sizes are 7 for the card list, 10 for the transaction list and 10 for the user list. A stored value always projects: a `sec_usr_fname` holding twenty character positions of which one is a supplementary-plane character reads back as twenty-one `char` values, and the user list renders it instead of failing. Under the withdrawn code-unit reading it did not: adding one such user turned every page of `GET /api/admin/users` into a 500, because a guard on the **response** projection refused a value the request path had accepted and the column had padded |
| **Test / gate evidence** | Per-DTO tests under `src/test/java/com/cardemo/unit/model/`; contract documentation in [`docs/api-contracts.md`](docs/api-contracts.md). The code-point unit is held by `src/test/java/com/cardemo/unit/model/FieldWidthUnitContractTest.java`, which asserts across every guard on this contract that a value of exactly n character positions containing a supplementary-plane character is accepted while n+1 is still refused, and that the user list projects such a row. Gate-level: [Gate 5](docs/validation-gates.md#gate-5), whose result that ledger records |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Gate-pending` — 30 July 2026. The character-position unit was added, and the code-unit reading withdrawn, when a supplementary-plane first name was found to fail the user-list projection — 8 August 2026 |

<a id="dl-ms-06"></a>

### DL-MS-06 — COMMAREA and `XCTL` become stateless JWT claims and URL routing

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | **High** — retaining server-side screen state would reintroduce affinity and break horizontal scaling |
| **Source evidence** | The COMMAREA is declared in the linkage section as a variable-length area sized by `EIBCALEN`; navigation is `EXEC CICS XCTL PROGRAM(...)`, and identity travels in `CDEMO-USER-ID` and `CDEMO-USER-TYPE` in `app/cpy/COCOM01Y.cpy`. Pseudo-conversational re-entry is flagged by `CDEMO-PGM-CONTEXT` |
| **Target** | `src/main/java/com/cardemo/security/JwtTokenProvider.java` (subject and role claims), `src/main/java/com/cardemo/security/JwtAuthenticationFilter.java`, `src/main/java/com/cardemo/config/SecurityConfig.java` (stateless session policy), `src/main/java/com/cardemo/model/dto/CommArea.java` (live fields only) |
| **Decision** | **No server-side session state.** `CDEMO-USER-ID` becomes the subject claim and `CDEMO-USER-TYPE` the role claim. Pagination state moves to request parameters and response metadata |
| **Alternatives considered** | **(a) An HTTP session holding the COMMAREA** — rejected: it recreates pseudo-conversational affinity, requires sticky routing, and makes the re-entry flag observable. **(b) A server-side continuation token for screen state** — rejected as unnecessary for every screen; adopted **only** where the source genuinely requires a snapshot across two requests, which is account update alone — see [DL-PP-02](#dl-pp-02) |
| **Rationale** | Four COMMAREA fields have no counterpart and are dropped deliberately: `CDEMO-FROM-TRANID`, `CDEMO-TO-TRANID`, `CDEMO-FROM-PROGRAM`, `CDEMO-TO-PROGRAM` are replaced by URL routing; `CDEMO-PGM-CONTEXT` collapses into stateless handling; `CDEMO-LAST-MAP` and `CDEMO-LAST-MAPSET` have nothing to retain because no screen state is kept |
| **Observable consequence** | Any instance can serve any request. A caller supplies the page number rather than pressing a function key against retained state |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/security/` for claim construction; statelessness asserted in `src/test/java/com/cardemo/e2e/OnlineTransactionE2ETest.java`. Gate-level: [Gate 5](docs/validation-gates.md#gate-5), whose result that ledger records |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Gate-pending` — 30 July 2026 |

<a id="dl-ms-07"></a>

### DL-MS-07 — JCL programs, DD statements and `COND` become Spring Batch jobs, steps, deciders and a split flow

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | **High** — mis-modelled step gating runs a step that the source suppresses |
| **Source evidence** | Return-code gating is expressed as `COND=(0,NE)` on successive steps of the statement job: [app/jcl/CREASTMT.JCL:L56] on `STEP020`, [:L66] on `STEP030`, [:L79] on `STEP040`. Program steps are `EXEC PGM=CBTRN02C` at [app/jcl/POSTTRAN.jcl:L23] and `EXEC PGM=CBACT04C,PARM='2022071800'` at [app/jcl/INTCALC.jcl:L22]. The posting exit code is set at [app/cbl/CBTRN02C.cbl:L229-L231] — `4` **if and only if** the reject count exceeds zero |
| **Target** | `src/main/java/com/cardemo/config/BatchConfig.java`; the six job definitions under `src/main/java/com/cardemo/batch/jobs/`; `src/main/java/com/cardemo/batch/jobs/BatchPipelineOrchestrator.java` for flow composition |
| **Decision** | One `Job` per JCL job, one `Step` per `EXEC PGM`, DD statements become configured resources, and `COND` gating becomes a `JobExecutionDecider` mapping return codes 0, 4, 8 and 12 onto completed, completed-with-rejects, failed and abend. Independent branches are composed with `FlowBuilder.split()` |
| **Alternatives considered** | **(a) A boolean `stepExecution` listener** instead of a decider — rejected: `COND` is a numeric comparison with four meaningful outcomes, and a boolean collapses "completed with rejects" into "completed". **(b) Throwing on a non-zero code** — rejected: return code 4 is a **successful** outcome in the source; the job completes and writes all its output. **(c) Sequential composition throughout** — rejected where the source job stream has no ordering dependency, because it would serialise branches that ran independently |
| **Rationale** | Return code 4 has exactly one determinant in the source and no other: the reject count. Any implementation that sets it for another reason — a warning, a skipped record, an empty input — diverges. This is also why reject codes are modelled as an enum returned from the processor rather than thrown; see [DL-PP-03](#dl-pp-03) |
| **Observable consequence** | An operator sees `COMPLETED` with a non-zero reject count where the source set RC=4, and `FAILED` where the source abended. A suppressed step is reported as skipped rather than silently absent |
| **Test / gate evidence** | `src/test/java/com/cardemo/integration/batch/BatchJobExecutionDeciderTest.java` covers the decider outcomes; `src/test/java/com/cardemo/integration/batch/BatchPipelineOrchestratorTest.java` and `src/test/java/com/cardemo/unit/batch/BatchPipelineOrchestratorTest.java` cover composition, including the halt that bypasses every downstream stage; and `src/test/java/com/cardemo/integration/batch/SuccessfulPipelineRunTest.java` covers the whole stream reaching a successful end with both branches of the split completed. Gate-level: [Gate 1](docs/validation-gates.md#gate-1), whose result that ledger records |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-ms-08"></a>

### DL-MS-08 — DFSORT and IDCAMS `REPRO` become in-process `Comparator` and batched JDBC

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | Medium |
| **Source evidence** | Three sort specifications. **(1)** Report sort: `SORT FIELDS=(TRAN-CARD-NUM,A)` at [app/proc/TRANREPT.prc:L44] over symbols `TRAN-CARD-NUM,263,16,ZD` at [:L39] and `TRAN-PROC-DT,305,10,CH` at [:L40], with an inclusive `INCLUDE COND` range at [:L45-L46]. **(2)** Combine sort: `SORT FIELDS=(TRAN-ID,A)` at [app/jcl/COMBTRAN.jcl:L30] over a **concatenated** input, [:L23-L26]. **(3)** Statement sort: `SORT FIELDS=(263,16,CH,A,1,16,CH,A)` at [app/jcl/CREASTMT.JCL:L53] with a record projection at [:L54]. Loads are IDCAMS: `REPRO INFILE(...) OUTFILE(...)` at [app/jcl/COMBTRAN.jcl:L48] and [app/jcl/CREASTMT.JCL:L61] |
| **Target** | `java.util.Comparator` instances and repository ordering in the readers and processors under `src/main/java/com/cardemo/batch/`; bulk loads through `JdbcTemplate.batchUpdate` |
| **Decision** | **No external sort process is spawned.** Ordering is either a `Comparator` or an `ORDER BY`, and `REPRO` becomes a batched insert |
| **Alternatives considered** | **(a) Shelling out to a sort utility** — rejected: it reintroduces an external process dependency and a temporary-file contract that the container image would have to carry. **(b) Row-by-row inserts** for the loads — rejected on efficiency grounds with no offsetting benefit; the load is a single-purpose bulk operation |
| **Rationale** | Two details in these specifications are load-bearing and neither is obvious. The report symbol declares a **character** field as zoned decimal, and the sort tolerates it; the resulting order is the source's order, so the comparator reproduces that order rather than the "correct" one — logged as `D-4` in `docs/validation-gates.md` [§12.3](docs/validation-gates.md#quirks). And the statement sort's projection truncates two bytes, which is [DL-PP-06](#dl-pp-06) |
| **Observable consequence** | Sorted output is byte-identical to the source's ordering, including the zoned-decimal-over-character case. One further consequence is catalogue-level rather than runtime: the combine sort and its load are the **whole** of feature **F-021 Combine Transactions**, the one catalogued feature with **no COBOL program** — [app/jcl/COMBTRAN.jcl]'s only two `EXEC` statements are `EXEC PGM=SORT` at [:L22] and `EXEC PGM=IDCAMS` at [:L41], so the JCL itself is its source of record and no program is invented to host it. `TRACEABILITY_MATRIX.md` [§8.3](TRACEABILITY_MATRIX.md#f-021) carries that registration, and the duplicate-key exposure the feature inherits from the interest job is at [DL-PP-07](#dl-pp-07) |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/TransactionCombineProcessorTest.java`; `src/test/java/com/cardemo/integration/batch/CombineTransactionsJobTest.java`; `src/test/java/com/cardemo/integration/batch/TransactionReportJobTest.java`. Gate-level: [Gate 1](docs/validation-gates.md#gate-1), whose result that ledger records |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-ms-09"></a>

### DL-MS-09 — The transient data queue `JOBS` becomes one typed FIFO queue message

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | Medium |
| **Source evidence** | The queue is defined as `DEFINE TDQUEUE(JOBS) ... TYPE(EXTRA) DDNAME(INREADER) TYPEFILE(OUTPUT) RECORDSIZE(80) RECORDFORMAT(FIXED) DISPOSITION(MOD)` in [app/csd/CARDDEMO.CSD]. The writer is `EXEC CICS WRITEQ TD QUEUE('JOBS')` at [app/cbl/CORPT00C.cbl:L517-L523], inside a paragraph whose name is misspelled `WIRTE-JOBSUB-TDQ` at [:L515] |
| **Target** | `SqsTemplate` publication from `src/main/java/com/cardemo/service/report/ReportSubmissionService.java`; queue and client configuration in `src/main/java/com/cardemo/config/AwsConfig.java`; an SQS listener replaces the JES2 internal reader |
| **Decision** | The 80-byte fixed card stream becomes **one typed JSON message** on a FIFO queue, carrying the report name and the two dates. Full reasoning for the collapse is [DL-MS-18](#dl-ms-18). **Extended, 8 August 2026 — the queue also carries a `RedrivePolicy` naming a derived dead-letter target, and the job's parameter validator reports a refusal as the type its interface declares.** Two findings made this necessary rather than optional, and both were reproduced at runtime. `TransactionReportParametersValidator` implemented `JobParametersValidator` but let a runtime `ValidationException` escape where the interface declares `JobParametersInvalidException`, so the consumer's existing terminal arm for refused parameters was unreachable and the message was neither acknowledged nor moved; and the queue had no dead-letter target at all, so nothing bounded how long an unclassifiable message could circulate. Because one FIFO message group carries every submission, an ordered group cannot deliver past the message at its head: a submission the producer legitimately accepts — [app/cbl/CORPT00C.cbl:L381-L410] validates the six custom-range components individually and never compares the two assembled dates, so an inverted range is accepted at submission by parity — starved every later submission for the queue's whole four-day retention while the submission endpoint kept answering `202`. **The producer was deliberately left unchanged**: adding a start-versus-end refusal there would be a behaviour change the source does not license. The containment is the consumer's, which is where a JCL step with a non-zero return code would have reported it |
| **Alternatives considered** | **(a) One message per card**, preserving the 80-byte record shape — rejected: the cards are a *job deck*, meaningful only as a whole, and 17 messages would have to be reassembled in order with no framing. **(b) A standard queue rather than FIFO** — rejected: `DISPOSITION(MOD)` appends to a stream read in order by the internal reader, so ordering is part of the contract |
| **Rationale** | The queue's declared record size of 80 and fixed format are what fix the *card* shape, not the *message* shape. What must survive is the meaning — which report, over which dates — plus the failure outcome, because the failure message is displayed to the user and is therefore an observable contract |
| **Observable consequence** | A submission failure reproduces the exact source message `Unable to Write TDQ (JOBS)...` from [app/cbl/CORPT00C.cbl:L531-L532], so an operator sees the same text. **Since 8 August 2026:** an unrunnable submission is acknowledged with its reason logged at `ERROR` on its **first** delivery, and every later submission in the group runs unaffected; `localstack-init/init-aws.sh` provisions a third queue, `carddemo-report-jobs-dlq.fifo`, whose name is derived from the report queue's so the two cannot be misconfigured against each other, and applies `maxReceiveCount` 4. The consumer's own discard and the redrive count are both wanted and act at different scopes — the consumer knows *why* a specific submission can never run and drops it immediately, while the redrive count bounds the cases it cannot classify, such as a deterministically failing job that is correctly returned to the queue because a failure might be transient. The published inventory moves from two queues to three in `README.md`, `docs/onboarding-guide.md`, `docs/validation-gates.md`, `docs/architecture-before-after.md` and the provisioned-inventory step of `.github/workflows/build.yml`. **This is an addition beyond the plan's enumeration of "the FIFO queue"**, recorded here rather than by amending the plan, which is frozen |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/ReportSubmissionServiceTest.java`; `src/test/java/com/cardemo/integration/aws/SqsReportQueueIntegrationTest.java`. For the extension: `src/test/java/com/cardemo/integration/batch/TransactionReportJobTest.java` asserts the validator raises `JobParametersInvalidException` and not a runtime type; `src/test/java/com/cardemo/unit/config/BatchConfigTest.java` asserts a validation refusal reaching the consumer is acknowledged rather than returned; `src/test/java/com/cardemo/unit/infrastructure/InitAwsScriptGuardTest.java` asserts the dead-letter queue is provisioned before the policy that names it and that the policy is read back and converged. Gate-level: [Gate 8](docs/validation-gates.md#gate-8), whose result that ledger records |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Gate-pending` — 30 July 2026; extension `Source-verified`, `Target-verified`, `Test-verified` — 8 August 2026 |

<a id="dl-ms-10"></a>

### DL-MS-10 — GDG generations become prefixed objects over a versioned bucket

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | Medium |
| **Source evidence** | Seven GDG bases are catalogued in [app/catlg/LISTCAT.txt]: `DALYREJS`, `SYSTRAN`, `TCATBALF.BKUP`, `TRANREPT`, `TRANSACT.BKUP`, `TRANSACT.COMBINED`, `TRANSACT.DALY`. Relative references appear as `(+1)` for a new generation — `DSN=AWS.M2.CARDDEMO.DALYREJS(+1)` at [app/jcl/POSTTRAN.jcl:L38], `SYSTRAN(+1)` at [app/jcl/INTCALC.jcl:L41] — and `(0)` for the current one, `TRANSACT.BKUP(0)` and `SYSTRAN(0)` at [app/jcl/COMBTRAN.jcl:L24] and [:L26] |
| **Target** | Object keys under three buckets provisioned idempotently by `localstack-init/init-aws.sh`; client configuration in `src/main/java/com/cardemo/config/AwsConfig.java`; writers under `src/main/java/com/cardemo/batch/writers/` |
| **Decision** | `(+1)` becomes a write under a **monotonically increasing prefix** (timestamp or job-instance); `(0)` becomes a read of the **lexicographically greatest** existing prefix; the output bucket has versioning enabled. **Record length is preserved byte-exactly at the object boundary** |
| **Alternatives considered** | **(a) Object versions alone**, with one fixed key per base — rejected: `(0)` would then mean "latest version", which is expressible, but a range of generations becomes unlistable and a reader cannot see the sequence. **(b) Emulating the catalogue** with a generation index — rejected: it recreates mainframe catalogue semantics with no consumer. **(c) Enforcing retention limits programmatically** — rejected; see the consequence row |
| **Rationale** | Record lengths carry the parity comparison: 430 for rejects, 133 for the report, 350 for transaction images, 80 and 100 for the two statement outputs. A boundary that re-wraps or trims lines makes every downstream diff fail for a reason unrelated to logic |
| **Observable consequence** | Retention limits are **documented, not enforced**; no lifecycle rule is applied by this work. The two conflicting declarations for the report group are resolved once, in [DL-CR-02](#dl-cr-02), because a single lifecycle value has to be chosen if one is ever applied. Tape and DASD emulation are out of scope |
| **Test / gate evidence** | `src/test/java/com/cardemo/integration/aws/S3GenerationKeyIntegrationTest.java`, `S3BucketProvisioningIntegrationTest.java`, `TransactionBackupGenerationIntegrationTest.java`. Gate-level: [Gate 8](docs/validation-gates.md#gate-8), whose result that ledger records |
| **Remediation / follow-up** | Apply an object-lifecycle rule if retention becomes a requirement; owner: deployment. Recorded at [DL-RR-06](#dl-rr-06) |
| **Verification** | `Source-verified`, `Target-verified`, `Gate-pending` — 30 July 2026 |

<a id="dl-ms-11"></a>

### DL-MS-11 — The `FILE STATUS` guard becomes typed contextual exceptions, with three accepted-control-path exceptions

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | **High** — a blanket status-to-exception rule abends three paths on which the source succeeds |
| **Source evidence** | The guard idiom is universal: a result field with condition names at [app/cbl/CBTRN02C.cbl:L142-L144], then `MOVE 8`, the verb, `MOVE 0` on `'00'` else `MOVE 12`, then either continue or display, render the status and abend. The renderer is `9910-DISPLAY-IO-STATUS` at [:L714-L727] and the abend is `9999-ABEND-PROGRAM` at [:L707-L711]. **The three exceptions where a non-`'00'` status is success:** the category-balance upsert accepts `'00' OR '23'` at [:L481]; the interest rate lookup accepts `'00' OR '23'` at [app/cbl/CBACT04C.cbl:L422]; and every file-service open and read in the statement program accepts `'00' OR '04'` — nine sites, at [app/cbl/CBSTM03A.CBL:L736], [:L748], [:L771], [:L789], [:L807], [:L862], [:L879], [:L895] and [:L911] |
| **Target** | `src/main/java/com/cardemo/service/shared/FileStatusMapper.java` and the nine exception types under `src/main/java/com/cardemo/exception/`: `CardDemoException`, `ValidationException`, `RecordNotFoundException`, `DuplicateRecordException`, `FileUnavailableException`, `ConcurrentUpdateException`, `DataIntegrityException`, `FileAccessException`, `FatalProcessingException` |
| **Decision** | Central translation: `'00'` continue; `'10'` end of file, not an error; `'23'` record-not-found; `'22'` duplicate; `'35'` unavailable; `'9x'` file-access carrying the expanded status; anything else fatal with abend code 999 and return code 12. **The mapper is aware of all three accepted-control-path exceptions**, which are honoured at their call sites rather than by weakening the general rule |
| **Alternatives considered** | **(a) One rule mapping `'23'` to an exception everywhere** — rejected: it abends the upsert and the interest default-rate fallback, both of which are normal, high-frequency paths. **(b) Catching and swallowing at the call site** — rejected: *Rule 1* clause B forbids swallowing, and it would hide a genuine not-found. **(c) Returning status codes rather than throwing** — rejected: it reproduces the COBOL idiom in Java and every caller must remember to check |
| **Rationale** | Recognising the guard as **one** idiom rather than hundreds of independent checks is what makes a single mapper correct. The status **rendering** is itself a contract: [:L714-L727] emits exactly four characters, copying the first byte through and expanding the second into three digits when the status is non-numeric or begins `'9'`, and otherwise emitting four zeros with the two status characters in positions three and four. Java emits the identical four-character form, because log output is compared |
| **Observable consequence** | Every I/O failure surfaces as a typed exception carrying its originating status; nothing is swallowed; the fatal type carries the abend code, culprit program, reason and message from `app/cpy/CSMSG02Y.cpy` — which, despite its name, is internally titled `CABENDD.CPY` and holds abend work areas rather than messages |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/FileStatusMapperTest.java` covers the map including the accepted paths. Gate-level: [Gate 1](docs/validation-gates.md#gate-1), whose result that ledger records |
| **Remediation / follow-up** | None outstanding. A new accepted-status site must be added to the mapper and to its test in the same change |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-ms-12"></a>

### DL-MS-12 — `EXEC CICS SYNCPOINT` becomes a declarative Spring transaction

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | **High** |
| **Source evidence** | `EXEC CICS SYNCPOINT ROLLBACK` at [app/cbl/COACTUPC.cbl:L4099-L4101], on the customer-rewrite failure path only |
| **Target** | The two write turns of `src/main/java/com/cardemo/service/account/AccountUpdateService.java`, each annotated `@Transactional(rollbackFor = Exception.class)`, and its two read paths, each annotated `@Transactional(readOnly = true)`. **Cited by symbol rather than by line, per [DL-RM-10](#dl-rm-10)**; `SourceCitationResolutionTest` resolves both annotation spellings in that file |
| **Decision** | Transaction boundaries are declarative and scoped so that the source's units of work are reproduced exactly, including the asymmetric appearance of the rollback verb |
| **Alternatives considered** | **(a) Programmatic transaction management** with an explicit rollback call on the second failure path, mirroring the source line for line — rejected: it is redundant, because the boundary already rolls back, and a redundant explicit rollback invites a later reader to "balance" it by adding one to the first path too, which would change nothing but would misrepresent the source. **(b) `rollbackFor` left at the default** (runtime exceptions only) — rejected: a checked exception escaping the boundary would commit a partial update |
| **Rationale** | The asymmetry is correct in the source and needs no conditional logic in Java. See [DL-PP-01](#dl-pp-01), which develops why |
| **Observable consequence** | A failure at either write leaves no partial update. The absence of an explicit rollback statement in Java is not a lost behaviour |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/AccountUpdateServiceTest.java`. Gate-level: [Gate 5](docs/validation-gates.md#gate-5), whose result that ledger records |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-ms-13"></a>

### DL-MS-13 — `CALL 'CSUTLDTC'` and `CEEDAYS` become `java.time` plus an outcome-compatible validation service

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | Medium |
| **Source evidence** | The utility is `app/cbl/CSUTLDTC.cbl` (157 lines), statically called and absent from the CSD. Its parameter block is declared by the caller: [app/cbl/CORPT00C.cbl:L129-L136] gives `CSUTLDTC-DATE X(10)`, `CSUTLDTC-DATE-FORMAT X(10)`, then a result group of `SEV-CD X(04)`, `FILLER X(11)`, `MSG-NUM X(04)` and `MSG X(61)`. The format literal is supplied by the caller, e.g. `WS-DATE-FORMAT ... VALUE 'YYYY-MM-DD'` at [app/cbl/COTRN02C.cbl:L60] |
| **Target** | `src/main/java/com/cardemo/service/shared/DateValidationService.java`, one injected bean subsuming the static call and both work-area copybooks (`app/cpy/CSUTLDPY.cpy`, `app/cpy/CSUTLDWY.cpy`) |
| **Decision** | Parse and validate with `java.time.LocalDate` behind a service that returns the **same accept-or-reject outcome** for the same input, and reproduces the severity-and-message-number result shape the callers read |
| **Alternatives considered** | **(a) Bare `LocalDate.parse`** at each call site — rejected: it throws where the source returns a severity code, and callers branch on the code rather than on an exception. **(b) Reimplementing the language-environment date algorithm** — rejected: unnecessary, since only the outcome is observable, and it would import a second date implementation to maintain |
| **Rationale** | **Validation outcomes, not merely parsing, must match.** A date the source accepts and Java rejects — or the reverse — changes which requests succeed. The result group's shape matters because callers read the severity code and the message number, not an exception type |
| **Observable consequence** | The custom report period validates its six discrete date components against an explicit format string and assembles the two dates with dash separators, exactly as [app/cbl/CORPT00C.cbl:L256] and [:L381-L410] do |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/DateValidationServiceTest.java`; `src/test/java/com/cardemo/unit/validation/LanguageEnvironmentDateContractTest.java`. Gate-level: [Gate 5](docs/validation-gates.md#gate-5), whose result that ledger records |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-ms-14"></a>

### DL-MS-14 — Plaintext user records become BCrypt hashes at strength 10

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | **High** — security |
| **Source evidence** | The ten user records exist **only** as inline `SYSUT1 DD *` data inside [app/jcl/DUSRSECJ.jcl:L35-L44] — there is **no `usrsec.txt` fixture**. The layout is `ID X(8) + FNAME X(20) + LNAME X(20) + PWD X(8) + TYPE X(1) + FILLER`, 80 bytes, matching `KEYS(8,0) RECORDSIZE(80,80)` at [:L65-L66]. Five administrators are type `A` and five standard users type `U`, and **every one carries the same fixed legacy demo password in plaintext** — the value occupies the eight-byte `PWD` field of each of those ten records and is read there, at [app/jcl/DUSRSECJ.jcl:L35-L44], rather than reproduced here. The comparison is plaintext: `IF SEC-USR-PWD = WS-USER-PWD` at [app/cbl/COSGN00C.cbl:L223] |
| **Target** | `src/main/resources/db/migration/V3__seed_data.sql` stores hashes only; verification in `src/main/java/com/cardemo/security/CardDemoUserDetailsService.java`; the entity column on `src/main/java/com/cardemo/model/entity/UserSecurity.java` widens from 8 characters to 60 to hold a BCrypt digest |
| **Decision** | BCrypt at **strength 10**. The eight-character plaintext field becomes a 60-character hash column, and the ten seeded users are stored **only** as hashes |
| **Alternatives considered** | **(a) Preserving plaintext** for exact parity — rejected: it is an unacceptable security posture and *Rule 1* clause D forbids it; the substitution is invisible to a caller, who still authenticates with the same credential. **(b) A stronger cost factor** — rejected as an unrequested change to a specified parameter; strength 10 is the specified value and is recorded here so a later change is a decision rather than a drift. **(c) Seeding the demo users unconditionally** — rejected; see the consequence row |
| **Rationale** | The credential a caller presents is unchanged, so this is a substitution and not a deviation: the same password authenticates the same user. Only the stored representation differs, and the stored representation was never observable |
| **Observable consequence** | The demo users exist **only under the local and test profiles**, gated by a migration placeholder, so a production deployment does not ship ten accounts with a known password. Sign-on upper-cases **both** identifier and password before comparison — [DL-PP-09](#dl-pp-09) — which means the credential space is unchanged by the hashing |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/model/DemoUserSeedGateContractTest.java`; `src/test/java/com/cardemo/unit/service/AuthenticationServiceTest.java`; `src/test/java/com/cardemo/integration/repository/RepositoryHarnessSeedStateTest.java`. Gate-level: [Gate 4](docs/validation-gates.md#gate-4) and [Gate 6](docs/validation-gates.md#gate-6), whose results that ledger records |
| **Remediation / follow-up** | Revisit the cost factor when hardware guidance changes; owner: security review |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-ms-15"></a>

### DL-MS-15 — `READ … UPDATE` plus snapshot becomes `@Version` **plus** explicit field comparison

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | **High** |
| **Source evidence** | `READ ... UPDATE` on the account at [app/cbl/COACTUPC.cbl:L3894-L3906] and on the customer at [:L3921-L3932], followed by the field-by-field comparison paragraph `9700-CHECK-CHANGE-IN-REC` at [:L4109-L4192] |
| **Target** | `@Version` columns on the entities that need them, plus `AccountUpdateService.checkChangeInRecord9700`, both layers reached from `AccountUpdateService.updateAccount`, which takes the comparison group from the value `SnapshotTokenService.open` recovers out of the request's sealed `snapshot` member and from nowhere else — never from a readable body group, and never from the live row. The read half is `AccountUpdateService.sealSnapshotForUpdate`, published as `AccountViewResponse.snapshot`; the card equivalents are `CardUpdateService.checkChangeInRec9300` and `CardUpdateService.sealSnapshotForUpdate` onto `CardResponse.snapshot` |
| **Decision** | **Both layers, always.** `@Version` is the store-level guard; the explicit comparison is the business-level guard. Neither substitutes for the other |
| **Alternatives considered** | **(a) `@Version` alone** — rejected, and this is the one place where the obvious mechanical translation is simply wrong; developed at [DL-PP-02](#dl-pp-02). **(b) Pessimistic locking** (`SELECT … FOR UPDATE`) held across two requests — rejected: it holds a database lock across user think-time, which the pseudo-conversational source never did |
| **Rationale** | A version column detects **that** a row changed. The source detects **which fields** changed, and in which representation. A concurrent write that set a field back to its original value passes the source's check and fails a version check — the two guarantees are genuinely different |
| **Observable consequence** | Because the target is stateless, the snapshot cannot live on the server between requests, so the request body carries it — as the opaque `snapshot` member the preceding read issued, echoed back byte for byte, alongside the readable `newDetails` group. This is why `AccountUpdateRequest` has that shape. An absent value is reported as `CHANGES_NOT_CONFIRMED` and one that fails to open as `DATA_CHANGED_BEFORE_UPDATE`, because the remedies differ — obtain a snapshot, versus read again |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/AccountUpdateServiceTest.java` (`aWriteWithoutTheSnapshotIsRejected`, `theSnapshotIsNeverDerivedFromTheLiveRow`, `theSnapshotCannotBeComposedByTheCaller`, `everyComponentSurvivesTheSealedRoundTrip`, `sealSnapshotForUpdateProjectsTheUnseparatedDateOfBirth`); `src/test/java/com/cardemo/unit/service/CardUpdateServiceTest.java` (`absentSnapshotIsAnUnmetPrecondition`, `theSnapshotIsNeverDerivedFromTheLiveRow`, `sealSnapshotForUpdateSealsTheFetchedValues`); `src/test/java/com/cardemo/unit/controller/AccountControllerTest.java` and `CardControllerTest.java` (`SnapshotContract`). Gate-level: [Gate 5](docs/validation-gates.md#gate-5), whose result that ledger records |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-ms-16"></a>

### DL-MS-16 — CICS identity, `DISPLAY` and the file-availability jobs become correlation IDs, metrics and health

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | Medium |
| **Source evidence** | The corpus has **no instrumentation** beyond `DISPLAY` to SYSOUT and the four-character status renderer at [app/cbl/CBTRN02C.cbl:L714-L727]. End-of-run reporting is two display statements: `DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT` and `DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT` at [:L227-L228]. **There is no per-request identity field in the corpus at all.** A repository-wide scan of `app/` returns **zero** occurrences of `EIBTRNID`, so the claim an earlier revision of this row made — *"Per-request identity is the CICS transaction identifier in `EIBTRNID`"* — is **withdrawn as an invention**. What the corpus actually reads from the EXEC interface block is two fields, neither of which is an identity: **`EIBCALEN`**, 49 occurrences across 17 files, which is the COMMAREA *length* used to distinguish a first entry from a re-entry; and **`EIBAID`**, 44 occurrences across 13 files, 28 of them inside the procedural copybook `app/cpy/CSSTRPFY.cpy` whose `YYYY-STORE-PFKEY` paragraph evaluates it against `DFHENTER`, `DFHCLEAR` and the `DFHPFnn` names — an *attention identifier*, that is, which key the operator pressed. The transaction code itself lives in the CSD as `DEFINE TRANSACTION(...)` and reaches a program through `EXEC CICS XCTL`, and it identifies the *screen*, never the request. Dataset availability for the online region is managed by two operator jobs, `app/jcl/OPENFIL.jcl` and `app/jcl/CLOSEFIL.jcl` |
| **Target** | `src/main/java/com/cardemo/observability/CorrelationIdFilter.java`, `MetricsConfig.java`, `HealthIndicators.java`; `src/main/java/com/cardemo/config/ObservabilityConfig.java`; `src/main/resources/logback-spring.xml`; the provisioning files under `observability/` |
| **Decision** | Structured JSON logging with trace, span and correlation identifiers carried in the logging context; four named counters replacing the end-of-run displays, with rejected records **tagged by reject code**; a composite health endpoint over database, object store and queue, with separate liveness and readiness groups. **One meter filter is declared, and only one**: `MetricsConfig.springBatchActiveJobMeterNameFilter`, which denies the *legacy* registration of `spring.batch.job.active` so that a single label set owns that Prometheus metric name |
| **Alternatives considered** | **(a) Keeping `DISPLAY`-equivalent `System.out` reporting** — rejected: it is not queryable and *Rule 1* clause A requires measurable behaviour. **(b) Deferring observability to a follow-up** — rejected: it is shipped with the initial implementation, because a compose file referencing a metrics scrape and a dashboard is inert without the provisioning files. **(c) One aggregate reject counter** — rejected: the reject-code tag is what turns the five constants of [DL-PP-03](#dl-pp-03) into an operable signal. **(d) For the meter filter, two property-only levers were rejected**, each for a stated reason. `management.observations.long-task-timer.enabled: false` suppresses the *other* variant and needs no code, but it is global — it would also delete `http_server_requests_active_seconds` and every other observation-derived active timer, which is telemetry loss outside this conflict. `management.metrics.enable.spring.batch.job.active: false` filters on the name alone, so it denies **both** variants and leaves no active-job timer at all. Only a filter discriminating on the *label set* can suppress one path and keep the other |
| **Rationale** | This family is the one place where the target is **new capability rather than a translation**, so it is designed explicitly rather than derived. The correlation identifier **has no legacy counterpart to replace** — it is net-new, because the corpus carries no per-request identity of any kind (see **Source evidence** above, and note that an earlier revision of this row named `EIBTRNID` as the thing being replaced, which is withdrawn: that field appears nowhere in `app/`). The nearest legacy analogue is a *job* identity rather than a request identity — the JES2 job name and number on a batch run — which is precisely why batch steps additionally propagate the Spring Batch job-instance identifier, and that is what makes per-run object prefixes and per-run logs correlatable |
| **Observable consequence** | Credentials, password hashes and government identifiers are **masked** in log output — a direct requirement of *Rule 1* clause D, and material because the customer layout carries a nine-digit government identifier and the user layout carries a password field. The two legacy file-availability jobs have **no Java analogue** and are documented as such; health checks replace their purpose. On the meter filter: Spring Batch 5.2.4 registers `spring.batch.job.active` **twice** with different tag keys — the legacy `BatchMetrics` binder in `AbstractJob.execute` with the single key `spring.batch.job.active.name`, and, immediately afterwards, the timer Micrometer's `DefaultMeterObservationHandler` derives from the `spring.batch.job` observation with the keys `spring.batch.job.name` and `spring.batch.job.status`. Prometheus admits one label set per metric name, so before the filter the second registration failed and its series was dropped, logging a `PrometheusMeterRegistry` warning at **every** job launch. The legacy variant is the one denied because job *duration* is reported by `spring_batch_job_seconds`, whose labels come from the same observation — so keeping the Observation-derived variant makes one label vocabulary serve both batch series, joinable on `spring_batch_job_name`, whereas `spring_batch_job_active_name` appears on no other meter and joins to nothing. No dashboard panel queries the active timer either way |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/FinancialLogRedactionTest.java`, `BatchLogHygieneTest.java`, `ReaderSensitiveDataTest.java`; `src/test/java/com/cardemo/integration/aws/ObservabilityHealthMetricsIntegrationTest.java`, `AwsCorrelationIdPropagationIntegrationTest.java`. The meter filter is pinned by `src/test/java/com/cardemo/unit/observability/MetricInstrumentOwnershipTest.java`, whose `BatchActiveJobMeterNameIsUnambiguous` group **reproduces the double registration against a real Prometheus registry** before asserting that the filter resolves it, and whose `theFactoryIsAStaticBeanMethod` pins the `static` modifier that stops the filter needing the registry it is filtering. Gate-level: [Gate 8](docs/validation-gates.md#gate-8), whose result that ledger records |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

---

<a id="named"></a>

## 5. Named decisions

The entries in this section are the hard cases: places where a competent, well-intentioned
implementation of the family rule would still produce the wrong behaviour. Each has its own
searchable title and permanent anchor. [§11](#coverage) indexes them against the
requirements that mandate them.

<a id="dl-pp-01"></a>

### DL-PP-01 — Account update: one transaction reproduces the asymmetric rollback

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | **High** |
| **Source evidence** | `9600-WRITE-PROCESSING`, [app/cbl/COACTUPC.cbl:L3888-L4105]. Seven steps in fixed order: read the account for update [:L3894-L3906], fail to the exit on a non-normal response [:L3910-L3916]; read the customer for update [:L3921-L3932], fail to the exit [:L3937-L3942]; run the change comparison [:L3947-L3952]; initialise the update images and move the new fields; **rewrite the account [:L4065-L4071] and on failure set `LOCKED-BUT-UPDATE-FAILED` and go to the exit with _no_ rollback [:L4076-L4081]**; **rewrite the customer [:L4085-L4091] and on failure set the same flag, issue `EXEC CICS SYNCPOINT ROLLBACK`, then go to the exit [:L4095-L4103]**; exit [:L4105-L4107] |
| **Target** | `private void writeProcessing9600(` in `src/main/java/com/cardemo/service/account/AccountUpdateService.java`, called inside that class's `@Transactional(rollbackFor = Exception.class)` boundary — the annotation appears twice, once on each write turn. **Cited by symbol rather than by line, per [DL-RM-10](#dl-rm-10)** |
| **Decision** | **One transactional method reproduces both failure branches automatically.** No conditional rollback logic is written |
| **Alternatives considered** | **(a) Mirror the source literally** — an explicit rollback on the customer path and none on the account path. Rejected: within a single boundary the explicit call is a no-op, and a redundant call invites a later reader to "balance" the two paths, which would misrepresent the source. **(b) Two separate transactions**, one per write — rejected: it makes the half-applied state reachable, which is exactly what the source's rollback exists to prevent. **(c) Read the two records without `UPDATE` intent** — rejected: it discards the lock-failure outcomes, which are distinguishable to the caller |
| **Rationale** | **The asymmetry is correct, not a defect.** At the account-rewrite failure point nothing has yet been written inside the unit of work, so the monitor releases the read-for-update locks at task end with no explicit action. At the customer-rewrite failure point the account rewrite has already happened inside the same unit of work, so an explicit backout is the only way to avoid a half-applied update. Both paths return before the commit point in Java, so both are covered by the boundary |
| **Observable consequence** | Four outcome flags declared at [:L517-L524] carry distinct literal texts — `Could not lock account record for update`, `Could not lock customer record for update`, `Record changed by some one else. Please review`, `Update of record failed` — and a fifth marker `ACUP-CHANGES-OKAYED-LOCK-ERROR` at [:L667] is set specifically on an account-lock failure. **Each maps to a distinguishable HTTP response.** Collapsing them into one conflict status would lose information the legacy screen displayed |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/AccountUpdateServiceTest.java`. Gate-level: [Gate 5](docs/validation-gates.md#gate-5), whose result that ledger records |
| **Remediation / follow-up** | None outstanding. A reviewer comparing the two sources will find a rollback statement with no Java counterpart; that is this entry's purpose |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-pp-02"></a>

### DL-PP-02 — Two-layer optimistic concurrency: `@Version` is not parity on its own

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | **High** — implemented with a version column alone, the endpoint is wrong in a way no test written from the version semantics would catch |
| **Source evidence** | `9700-CHECK-CHANGE-IN-REC`, [app/cbl/COACTUPC.cbl:L4109-L4192], comparing the live records against the snapshot groups `ACUP-OLD-DETAILS` [:L669-L756] and `ACUP-NEW-DETAILS` [:L757]. **Three characteristics that are easy to translate wrongly:** <br>**(i) Dates are compared as three separate substrings, never whole.** Open, expiry and reissue dates are each compared by `(1:4)`, `(6:2)`, `(9:2)` against discrete snapshot fields — [:L4127-L4137]. <br>**(ii) Case handling is deliberately asymmetric.** The account group identifier is compared through `FUNCTION LOWER-CASE` on both sides [:L4139-L4140]; nine customer fields — first, middle and last name, three address lines, state, country and government identifier — are compared through `FUNCTION UPPER-CASE` on both sides [:L4152-L4167], [:L4172-L4173]; and the postal code, both telephone numbers, the government identifier's numeric form, the funds-account identifier, the primary-holder indicator and the credit score are compared with **no case function at all** [:L4168-L4171], [:L4181-L4186]. <br>**(iii) The date-of-birth comparison uses different offsets on each side.** The live record holds a dash-separated date, so its components sit at `(1:4)`, `(6:2)`, `(9:2)`; the snapshot holds the same date **without separators**, so its components sit at `(1:4)`, `(5:2)`, `(7:2)`. The source compares 1↔1, 6↔5 and 9↔7 — [:L4174-L4179] |
| **Target** | `@Version` columns on the entities, plus `AccountUpdateService.checkChangeInRecord9700`; the snapshot travels in the request body as its opaque `snapshot` member, is opened and verified by `SnapshotTokenService.open`, and is then attached to the request through `AccountUpdateRequest.withOldDetails` and restored by `restoreSnapshotFromRequest`. The read half that seals the group is `AccountUpdateService.sealSnapshotForUpdate`, surfaced as the `snapshot` component of `AccountViewResponse` |
| **Decision** | Keep **both** layers. Compare the date of birth **by component**, and store the snapshot date in its **compact, separator-free form** |
| **Alternatives considered** | **(a) `@Version` alone** — rejected: it detects a different fact. **(b) A whole-string date-of-birth comparison** — rejected, and this is the sharpest trap in the corpus: because the two sides use different offsets, a whole-string compare **would report a change on every single request**, making the endpoint permanently unusable. **(c) Normalising case uniformly** in either direction — rejected: it changes *which updates are accepted*, so it is a behaviour change disguised as tidying. **(d) Holding the snapshot server-side** — rejected: it reintroduces session state, contradicting [DL-MS-06](#dl-ms-06). **(e) Publishing the group readably in the response and binding it readably on the request** — implemented at one point and **withdrawn**, in favour of the sealed member described under **Target**. It is recorded here rather than deleted, because it was the delivered shape for a time and a later reader comparing revisions will otherwise conclude something was lost. Two defects retired it, both of which a code review classified `Critical`. First, the read put nine protected customer values on the wire on every plain account view — the social security number, the date of birth, the three names, both telephone numbers, the government-issued identifier and the funds-account identifier — which is precisely the set `AccountViewResponse.WITHHELD_COMPONENTS` promises to withhold, so the response contradicted its own contract. Second, and more seriously, it handed the caller the second operand of the very comparison the guard exists to perform: a client could submit a group matching whatever the row currently held and the change detection would pass unconditionally, which is `CWE-345` and `CWE-602`. The counter-argument that retired the sealed shape the first time was that `app/cpy-bms/COACTUP.CPY` declares the government identifier and the date of birth as **input** fields, so the operator typed them anyway — that is true of the *edited* group and it is why `newDetails` remains readable, but it is not true of the *as-displayed* group, whose whole purpose is to be a value the server issued and the caller cannot choose. Two further objections were raised at the time and both are answered by the current shape rather than argued with: the group is still carried on the request body, as transformation Rule 7 requires, merely sealed rather than legible; and the caller never has to assemble it, because the read hands back exactly what the write must return — which also preserves this entry's sharpest trap, since the separator-free date of birth is sealed and echoed rather than reconstructed. **(f) An `If-Match` entity tag** carrying the same sealed value in a header rather than the body — rejected: it moves the value out of the body the frozen contract names, and `HTTP` entity-tag semantics promise a resource-version comparison the server does not perform |
| **Rationale** | The two guards answer different questions and both questions are asked. The comparison term counts are recorded in correction **C-4** because a coverage check asserts against them: 16 account terms over 10 fields, 19 customer terms over 17 fields |
| **Observable consequence** | A concurrent write that restores a field to its original value is **accepted**, as in the source, though the version column will have advanced — the business comparison governs the user-visible outcome. A request that omits the sealed snapshot is refused rather than silently treated as unchanged, and one that presents a snapshot which was tampered with, issued to another principal, issued for another account, or issued longer ago than the configured lifetime is refused as a rival write. One consequence is worth stating because it is a deliberate trade: a malformed account identifier presented together with a snapshot now yields `412` rather than `400`, because the identifier is bound as authenticated additional data and the binding is checked before any field edit runs |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/AccountUpdateServiceTest.java`, whose `sealSnapshotForUpdateProjectsTheUnseparatedDateOfBirth` and `theSnapshotIsNeverDerivedFromTheLiveRow` are the two assertions that pin this entry, with `aTamperedSnapshotIsRefused`, `aSnapshotSealedForAnotherPrincipalIsRefused`, `aSnapshotSealedForAnotherAccountIsRefused` and `anExpiredSnapshotIsRefused` pinning the four refusals; `src/test/java/com/cardemo/unit/controller/AccountControllerTest.java` (`theSerialisedReadResponseCarriesNoProtectedValue`, `theSubmittedSnapshotReachesTheServiceUnaltered`); `src/test/java/com/cardemo/e2e/OnlineTransactionE2ETest.java` (`@Order(5)`, `@Order(6)`). Gate-level: [Gate 5](docs/validation-gates.md#gate-5), whose result that ledger records. Cross-referenced as `Q-5` in `docs/validation-gates.md` [§12.4](docs/validation-gates.md#quirks) |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-pp-03"></a>

### DL-PP-03 — Exactly five reject codes, and code 109 is retained though it can never be observed

| Field | Content |
|---|---|
| **Classification** | `PARITY-PRESERVED` |
| **Severity** | Medium |
| **Source evidence** | Five and only five reject values are assigned in [app/cbl/CBTRN02C.cbl], each with an exact literal description: **100** `INVALID CARD NUMBER FOUND` [:L385-L387]; **101** `ACCOUNT RECORD NOT FOUND` [:L397-L399]; **102** `OVERLIMIT TRANSACTION` [:L410-L412]; **103** `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` [:L417-L419]; **109** [:L556-L558]. The reject record is 350 data bytes plus an 80-byte trailer of `PIC 9(04)` reason and `PIC X(76)` description [:L176-L182] — 430 bytes, corroborated by `DCB=(RECFM=F,LRECL=430,...)` at [app/jcl/POSTTRAN.jcl:L36] |
| **Target** | `src/main/java/com/cardemo/model/enums/RejectCode.java` — `INVALID_CARD_NUMBER(100, …)`, `ACCOUNT_RECORD_NOT_FOUND(101, …)`, `OVERLIMIT_TRANSACTION(102, …)`, `TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION(103, …)`, `ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE(109, …)`; returned as data through `TransactionPostingProcessor.PostingResult`, never thrown |
| **Decision** | Model **exactly five** constants, each carrying its exact literal text, as an enum; reject codes are **business outcomes, not exceptions**. Retain 109 even though no reject record can ever carry it |
| **Alternatives considered** | **(a) Four constants**, dropping 109 — rejected: the assignment at [:L556] is real code on a reachable path, so deleting the constant would misrepresent the program. **(b) Throwing an exception per reject** — rejected: a reject is a normal, counted outcome that produces output and sets return code 4; an exception would abort the chunk. **(c) Deriving the description text from the enum name** — rejected: the texts are compared byte-for-byte, and two of them do not follow from their names |
| **Rationale** | **Why 109 can never be observed.** It is assigned inside `2800-UPDATE-ACCOUNT-REC`, which runs only from `2000-POST-TRANSACTION` [:L441], which is entered only when the reason code was **already zero** [:L211-L212] — the validated path. No reject record is written, the reject count is not incremented, execution continues to the transaction write at [:L442], and the value is cleared on the next iteration by `MOVE 0 TO WS-VALIDATION-FAIL-REASON` at [:L208]. It is assigned and then discarded |
| **Observable consequence** | A finding this register adds, which the specification does not record: **code 109's description literal is `ACCOUNT RECORD NOT FOUND` — byte-identical to code 101's.** The enum reproduces the duplication rather than inventing a distinguishing text, which is why the two constants have different Java names but the same description string. Note also that the source performs no status guard at all after that rewrite [:L554-L559]: there is no `APPL-RESULT` check and no abend, which is the root of [DL-DV-01](#dl-dv-01) |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/model/RejectCodeTest.java` asserts the literal values; `src/test/java/com/cardemo/unit/batch/TransactionPostingProcessorTest.java` asserts the outcomes; the 430-byte decomposition is asserted in `src/test/java/com/cardemo/e2e/GateVerificationTest.java` via `RejectCode.REJECT_RECORD_LENGTH`. Gate-level: [Gate 1](docs/validation-gates.md#gate-1), whose result that ledger records |
| **Remediation / follow-up** | None outstanding. 109 is one of the three tracked retained-parity artefacts of [DL-CR-01](#dl-cr-01) |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-dv-01"></a>

### DL-DV-01 — DEVIATION: one transaction boundary closes the posting orphan-write hazard

| Field | Content |
|---|---|
| **Classification** | **`DEVIATION`** — an improvement, deliberately **not** described as parity |
| **Severity** | Medium |
| **Source evidence** | `2000-POST-TRANSACTION` performs three writes in immediate succession — `2700-UPDATE-TCATBAL` [:L440], `2800-UPDATE-ACCOUNT-REC` [:L441], `2900-WRITE-TRANSACTION-FILE` [:L442] — reaching three datasets. The category-balance upsert is [app/cbl/CBTRN02C.cbl:L467-L501], the account update [:L545-L560] and the transaction write [:L562]. **The account rewrite's `INVALID KEY` branch sets reject code 109 and nothing else** [:L554-L559]: no status guard, no abend, no early exit. Execution falls through to the transaction write |
| **Target** | `TransactionPostingProcessor.postTransaction`, `updateTcatbal`, `updateAccountRec` and the writers under `src/main/java/com/cardemo/batch/writers/`, all inside one Spring transaction |
| **Decision** | Scope the three writes into **one atomic unit**. This closes the hazard, and the closure is **labelled a deviation** |
| **Alternatives considered** | **(a) Reproduce three independent commits** to preserve the hazard exactly — rejected: it deliberately preserves a data-corruption path with no offsetting benefit, and no test could assert the corrupt state as desired behaviour. **(b) Close it and call it parity** — rejected as dishonest; that is the specific mislabelling this register exists to prevent |
| **Rationale** | In the source the three writes commit independently, so a failure on the account rewrite leaves **an orphaned category-balance row and an orphaned transaction row**, with the account balance unupdated. In Java the three succeed or fail together. That is a **genuine behavioural improvement**, and an improvement is still a difference |
| **Observable consequence** | On an account-rewrite failure the source produces two orphan rows and continues; Java produces none and fails the chunk. Any parity comparison constructed around that failure path will differ, by design. On every path where the rewrite succeeds — which is every path the fixtures exercise — output is identical |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/TransactionPostingProcessorTest.java`; `src/test/java/com/cardemo/integration/batch/DailyTransactionPostingJobTest.java`; `src/test/java/com/cardemo/unit/batch/BatchWriteSemanticsTest.java`. Gate-level: [Gate 1](docs/validation-gates.md#gate-1), whose result that ledger records. Recorded as `V-1` in `docs/validation-gates.md` [§12.5](docs/validation-gates.md#deviations) |
| **Remediation / follow-up** | None. Reverting would reintroduce the hazard; owner of any such proposal must state why corruption is preferable |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-pp-04"></a>

### DL-PP-04 — The identifier-generation race is retained; no database sequence

| Field | Content |
|---|---|
| **Classification** | `PARITY-PRESERVED` |
| **Severity** | Medium |
| **Source evidence** | Two programs generate identifiers identically: `MOVE HIGH-VALUES TO TRAN-ID`, `STARTBR`, `READPREV`, `ENDBR`, then `ADD 1` — [app/cbl/COTRN02C.cbl:L444-L449] and [app/cbl/COBIL00C.cbl:L212-L217]. The empty-file path is explicit: `WHEN DFHRESP(ENDFILE) MOVE ZEROS TO TRAN-ID` at [app/cbl/COBIL00C.cbl:L487-L488], so **the first generated identifier is 1**. The identifier field is `PIC 9(16)` — [app/cbl/COTRN02C.cbl:L57] |
| **Target** | A top-one descending-ordered query in `src/main/java/com/cardemo/repository/TransactionRepository.java`, consumed by `src/main/java/com/cardemo/service/transaction/TransactionAddService.java` and `src/main/java/com/cardemo/service/billing/BillPaymentService.java` |
| **Decision** | Keep the maximum-key-plus-one algorithm, defaulting to zero on an empty result and zero-padding to 16 characters. **No database sequence.** A collision surfaces as `DuplicateRecordException` from the primary-key constraint |
| **Alternatives considered** | **(a) A database sequence or identity column** — rejected: it changes the generated values, so output cannot be compared against a baseline, and it would also diverge whenever the table already holds higher identifiers. **(b) A pessimistic lock or advisory lock** around generation — rejected: it removes the race, which is a behaviour change, and serialises a high-frequency path the source never serialised. **(c) A retry loop inside the service** — rejected: the source does not retry; the caller does |
| **Rationale** | The browse-based algorithm is **inherently racy, exactly as the browse was**. Preserving it keeps generated values comparable; the primary-key constraint converts what was a silent overwrite risk into a clean refusal |
| **Observable consequence** | Concurrent adds can compute the same identifier; the loser receives a duplicate-record refusal and retries. Note the interaction with interest generation: because a generated interest identifier leads with a ten-character date ([DL-PP-07](#dl-pp-07)), those identifiers are numerically large and **dominate the descending browse** once an interest run has occurred |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/TransactionAddServiceTest.java`; `src/test/java/com/cardemo/unit/service/BillPaymentServiceTest.java`; `src/test/java/com/cardemo/integration/repository/TransactionRepositoryTest.java`. Gate-level: [Gate 5](docs/validation-gates.md#gate-5), whose result that ledger records. Recorded as `Q-4` in `docs/validation-gates.md` [§12.4](docs/validation-gates.md#quirks) |
| **Remediation / follow-up** | Callers retry on a duplicate refusal. Operational note at [DL-RR-04](#dl-rr-04) |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-pp-05"></a>

### DL-PP-05 — The empty `1400-COMPUTE-FEES` paragraph is retained as a reachable no-op

| Field | Content |
|---|---|
| **Classification** | `PARITY-PRESERVED` |
| **Severity** | Low behaviourally; **Medium** as a compliance question — see [DL-CR-01](#dl-cr-01) |
| **Source evidence** | [app/cbl/CBACT04C.cbl:L518-L520]. The paragraph's entire body is a comment reading `* To be implemented` and an `EXIT`. **It is genuinely reachable**: `PERFORM 1400-COMPUTE-FEES` at [:L216], inside the non-zero-rate branch guarded at [:L214] |
| **Target** | `private void computeFees(` in `src/main/java/com/cardemo/batch/processors/InterestCalculationProcessor.java` — an empty private method carrying a Javadoc that cites the source lines and states that the no-op is intentional |
| **Decision** | Retain the empty method, with a citation and an explicit statement that the emptiness is the behaviour. Retain the call site |
| **Alternatives considered** | **(a) Delete the method and the call** — satisfies the letter of the no-dead-code standard and breaks two things that matter more: the paragraph map the coverage gate reads, and the guarantee that the call at `:L216` is reproduced. **(b) Keep it with a bare marker comment** — rejected, and the reason is precise: a bare `TODO`-style marker would breach *Rule 1* clause B **for real**, because the clause forbids deferred work without an owner or tracking reference. A cited, tracked, deliberately empty method does not. **(c) Implement a fee calculation** — rejected outright: the source computes no fees, so any implementation invents behaviour |
| **Rationale** | The legacy authors, not this migration, left the paragraph unimplemented, and the source comment is reproduced as the evidence of that. The emptiness is a fact about the system of record |
| **Observable consequence** | None. It is a no-op in both systems. A reader encountering an empty method in a reviewed codebase will suspect an oversight, which is why it is cited here rather than only in the Javadoc. Note the zero-rate interaction: the gate at [:L214] suppresses this paragraph **together with** `1300-COMPUTE-INTEREST`, so a zero rate skips the fee call as well |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/InterestCalculationProcessorTest.java`. Gate-level: [Gate 7](docs/validation-gates.md#gate-7); its result is recorded in that ledger, not here |
| **Remediation / follow-up** | If fees are ever specified, implement them as new behaviour with their own decision entry; owner: product. Until then this entry is the tracking reference |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-dv-02"></a>

### DL-DV-02 — DEVIATION: self-modifying `ALTER` dispatch eliminated by static flow analysis

| Field | Content |
|---|---|
| **Classification** | **`DEVIATION`** — mechanism eliminated rather than reproduced; observable order preserved |
| **Severity** | Medium |
| **Source evidence** | [app/cbl/CBSTM03A.CBL]. A dispatch paragraph whose entire body is an unconditional branch — `8100-FILE-OPEN. GO TO 8100-TRNXFILE-OPEN` at [:L726-L728] — is rewritten at run time before being taken: four `ALTER … TO PROCEED TO` statements at [:L300], [:L303], [:L306] and [:L309], selected by `EVALUATE WS-FL-DD` at [:L298], whose field has the fixed initial value `'TRNXFILE'` at [:L67]. **But the state transitions are hard-coded in each handler's tail**, so the machine is deterministic: open and prime the transaction file, then `MOVE 'READTRNX'` and re-enter [:L760-L761]; build the in-memory table, flush the final counter and `MOVE 'XREFFILE'` [:L850-L852]; open the cross-reference file and `MOVE 'CUSTFILE'` [:L779-L780]; open the customer file and `MOVE 'ACCTFILE'` [:L797-L798]; open the account file and **`GO TO 1000-MAINLINE` [:L815], leaving the state machine permanently** |
| **Target** | `public void initialise(` in `src/main/java/com/cardemo/batch/processors/StatementProcessor.java` — an ordered sequence of exactly five calls: `initialiseTransactionStream()`, `openAndPrimeTransactionFile()`, `openCrossReferenceFile()`, `openCustomerFile()`, `openAccountFile()` |
| **Decision** | Reduce the self-modifying dispatch to **straight-line initialisation**, preserving the observable order exactly |
| **Alternatives considered** | **(a) A runtime dispatch table** keyed on a state field, modelling the `EVALUATE` — rejected, and this corrects an earlier design note: the chain is a **one-shot initialisation pipeline**, not a runtime dispatch table, so a strategy map here would introduce indirection modelling a variability that does not exist. The place where the variability is genuine is the file-access layer — [DL-DV-04](#dl-dv-04). **(b) Reproducing `ALTER` with a mutable function reference** — rejected: it reproduces the mechanism's hazard (self-modifying control flow) while adding nothing, since the transitions are static |
| **Rationale** | Static analysis of the transitions proves the machine visits five states in one fixed order and then exits. Anything that preserves that order is behaviourally identical; the state field itself was never observable |
| **Observable consequence** | The five datasets are opened and primed in the same order, so any ordering-dependent failure surfaces identically. The mechanism is gone: there is no state field to inspect and no way to re-enter the pipeline. Because the mechanism is gone rather than reproduced, this is labelled a deviation even though no output differs |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/StatementProcessorTest.java`; `src/test/java/com/cardemo/unit/batch/StatementProcessorStreamingTest.java`; `src/test/java/com/cardemo/integration/batch/StatementGenerationJobTest.java`. Gate-level: [Gate 1](docs/validation-gates.md#gate-1), whose result that ledger records. One of the ten deviations noted in `docs/validation-gates.md` [§12.5](docs/validation-gates.md#deviations) |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-dv-03"></a>

### DL-DV-03 — DEVIATION: the hard 510-transaction ceiling is removed

| Field | Content |
|---|---|
| **Classification** | **`DEVIATION`** — a silent-corruption hazard removed, deliberately **not** described as parity |
| **Severity** | Medium |
| **Source evidence** | [app/cbl/CBSTM03A.CBL:L225-L233]. `WS-CARD-TBL OCCURS 51 TIMES` [:L226], each holding `WS-TRAN-TBL OCCURS 10 TIMES` [:L228] with `WS-TRAN-REST PIC X(318)` [:L230], plus a parallel counter table `OCCURS 51 TIMES` [:L232]. **That is a maximum of 510 transactions per run, and the building loop increments both indices with no bounds check whatsoever**: `ADD 1 TO TR-CNT` [:L820], `ADD 1 TO CR-CNT` [:L823], then unconditional stores at [:L827-L829] and self-recursion at [:L840] |
| **Target** | Unbounded, streamed collections in `src/main/java/com/cardemo/batch/processors/StatementProcessor.java` — `readNextCardGroup()`, `advanceToCardGroup(String)`, `admitTransaction(int)` |
| **Decision** | Use unbounded collections and stream the transaction file. **510 is recorded as the historical capacity limit** |
| **Alternatives considered** | **(a) Reproduce the 510 ceiling** with the same absent bounds check — rejected: it deliberately reproduces a storage-overrun defect. **(b) Reproduce the ceiling but add a bounds check that fails cleanly** — rejected as the worst of both: it neither matches the source (which overruns) nor removes the limit, and it invents a failure mode that exists in neither system. **(c) Remove the ceiling and call it parity** — rejected as dishonest |
| **Rationale** | Beyond 510 transactions the source writes past the end of its tables. Java cannot overrun, so behaviour at scale genuinely differs. Pretending the ceiling was preserved would be false; pretending its removal is invisible would be worse |
| **Observable consequence** | For any run with 510 or fewer transactions, output is identical. Above that the source corrupts storage and Java does not |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/StatementProcessorStreamingTest.java`; `src/test/java/com/cardemo/unit/batch/StatementWorkObjectStreamingTest.java`; `src/test/java/com/cardemo/unit/batch/ReferenceTableCacheAndCountBoundTest.java`. Gate-level: [Gate 1](docs/validation-gates.md#gate-1), whose result that ledger records. Recorded as `V-2` in `docs/validation-gates.md` [§12.5](docs/validation-gates.md#deviations) |
| **Remediation / follow-up** | The historical limit belongs in the traceability matrix as the source's capacity; owner: the traceability deliverable — [DL-RR-07](#dl-rr-07) |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-pp-06"></a>

### DL-PP-06 — The statement projection's two-byte timestamp truncation is reproduced exactly

| Field | Content |
|---|---|
| **Classification** | `PARITY-PRESERVED` |
| **Severity** | Medium |
| **Source evidence** | [app/jcl/CREASTMT.JCL:L54] — `OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)`, applied after `SORT FIELDS=(263,16,CH,A,1,16,CH,A)` at [:L53]. Against the proven 350-byte transaction offset map — identifier 1–16, type 17–18, category 19–22, source 23–32, description 33–132, amount 133–143, merchant identifier 144–152, merchant name 153–202, merchant city 203–252, merchant postal code 253–262, card number 263–278, originating timestamp 279–304, processing timestamp 305–330, filler 331–350 — the projection emits the 16-byte card number at 1, the first 262 bytes of the record at 17, and **50 bytes taken from offset 279 at offset 279**. Input bytes 279–328 are the **full 26-byte originating timestamp plus only the first 24 of the 26 processing-timestamp bytes**. The 20-byte trailing filler is dropped entirely; the output is 328 bytes, padded to the `LRECL=350` declared at [:L50] |
| **Target** | The in-job projection consumed by `StatementProcessor.openAndPrimeTransactionFile()` in `src/main/java/com/cardemo/batch/processors/StatementProcessor.java`, and the work-cluster shape defined by `KEYS(32 0) RECORDSIZE(350 350)` at [app/jcl/CREASTMT.JCL:L29-L39] |
| **Decision** | Reproduce the projection exactly, **including the two-byte truncation and the dropped filler** |
| **Alternatives considered** | **(a) Copy the full 26-byte processing timestamp**, "fixing" what looks like an off-by-two in the control card — rejected: it changes the bytes the statement program reads, so every statement differs from the baseline. **(b) Preserve the filler** — rejected for the same reason |
| **Rationale** | The projected processing timestamp arrives as a 24-character value padded to 26. That is what the statement program consumed for the lifetime of the system, so it is the contract. A Java implementation that "corrects" it produces a diff that looks like a Java bug and is not |
| **Observable consequence** | Statement output matches byte for byte. The 32-byte composite key of the work cluster — a 16-character card number plus a 16-character identifier — is exactly the `KEYS(32 0)` declared for it, and the record remainder is 318 bytes for a 350-byte total, matching `WS-TRAN-REST PIC X(318)` at [app/cbl/CBSTM03A.CBL:L230] |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/StatementWorkObjectStreamingTest.java`; `src/test/java/com/cardemo/integration/batch/StatementGenerationJobTest.java`. Gate-level: [Gate 1](docs/validation-gates.md#gate-1), whose result that ledger records |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-pp-07"></a>

### DL-PP-07 — Interest output goes to a fresh sequential generation, not to the transaction table

| Field | Content |
|---|---|
| **Classification** | `PARITY-PRESERVED` |
| **Severity** | **High** — writing to the table instead would create duplicate keys in the wrong place and hide them |
| **Source evidence** | The interest program's transaction output is declared **sequential**, not keyed: `SELECT TRANSACT-FILE ASSIGN TO TRANSACT / ORGANIZATION IS SEQUENTIAL / ACCESS MODE IS SEQUENTIAL` at [app/cbl/CBACT04C.cbl:L53-L56]. Its job statement allocates a **brand-new generation of a sequential generation group** on every run: `DCB=(RECFM=F,LRECL=350,BLKSIZE=0)` writing to `DSN=AWS.M2.CARDDEMO.SYSTRAN(+1)` at [app/jcl/INTCALC.jcl:L39-L41]. Interest transactions reach the keyed cluster only later, through the combine job: its sort consumes the concatenated `TRANSACT.BKUP(0)` [app/jcl/COMBTRAN.jcl:L24] **and `SYSTRAN(0)`** [:L26], and its second step loads the result with `REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)` into `TRANSACT.VSAM.KSDS` [:L41-L48] |
| **Target** | Object-store output from `src/main/java/com/cardemo/batch/jobs/InterestCalculationJob.java` and `InterestCalculationProcessor.writeTransaction`; the load lives in `src/main/java/com/cardemo/batch/jobs/CombineTransactionsJob.java` |
| **Decision** | The interest job writes a **fresh sequential generation to the object store**, not to the transaction table. Duplicate-key exposure is surfaced **in the combine job's load step**, as a `DuplicateRecordException` and a failed exit status — **never as a silent upsert** |
| **Alternatives considered** | **(a) Write directly to the transaction table** — rejected: the source's output is a sequential file, so the program performs no duplicate detection at all; writing to the table would either fail where the source succeeds or, worse, be "fixed" with an upsert that silently overwrites a real transaction. **(b) Upsert on conflict during the combine load** — rejected explicitly: `REPRO` into a keyed cluster fails on a duplicate key, and silently absorbing it would hide a repeated date parameter, which is the actual operator error |
| **Rationale** | Four consequences follow from the sequential declaration, and all four are behaviour: no duplicate detection inside the interest program; duplicate exposure deferred to the combine load; **only the most recent generation is merged**, because the combine sort reads `SYSTRAN(0)`; and a repeated date parameter produces colliding identifiers, because the generated identifier concatenates the ten-character date parameter with a six-digit global suffix — `STRING PARM-DATE, WS-TRANID-SUFFIX ... INTO TRAN-ID` at [app/cbl/CBACT04C.cbl:L476-L480], where the suffix `PIC 9(06)` at [:L173] is **never reset per account** |
| **Observable consequence** | The date parameter is a linkage value, not a dataset: `PARM-LENGTH PIC S9(04) COMP` plus `PARM-DATE PIC X(10)` at [:L176-L178], received through `PROCEDURE DIVISION USING EXTERNAL-PARMS` at [:L180] and supplied as `PARM='2022071800'` at [app/jcl/INTCALC.jcl:L22] — **eight date digits followed by two zeros**, not an ISO date. Re-running with the same parameter fails the combine load rather than corrupting the cluster |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/InterestCalculationProcessorTest.java`; `src/test/java/com/cardemo/integration/batch/InterestCalculationJobTest.java` and `InterestCalculationJobIntegrationTest.java`; `src/test/java/com/cardemo/integration/batch/CombineTransactionsJobTest.java`. Gate-level: [Gate 1](docs/validation-gates.md#gate-1), whose result that ledger records |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-pp-08"></a>

### DL-PP-08 — Zoned-decimal overpunch is decoded position-aware, never by global substitution

| Field | Content |
|---|---|
| **Classification** | `PARITY-PRESERVED` |
| **Severity** | **High** — a global substitution silently corrupts text fields and inverts signs |
| **Source evidence** | Signed numerics in the ASCII fixtures carry a **trailing-sign overpunch**. The first account record reads `00000000001Y00000001940{00000020200{00000010200{2014-11-20…` — [app/data/ASCII/acctdata.txt:L1] — where each twelve-character `S9(10)V99` money field terminates in an overpunch character and `{` denotes `+0`, so those fields decode to +194.00, +2020.00 and +1020.00. The decode table is `{`→+0, `A`–`I`→+1…+9, `}`→−0, `J`–`R`→−1…−9. Corroborated by an eleven-character `S9(09)V99` ending `0000000000{` in [app/data/ASCII/tcatbal.txt:L1] and a six-character `S9(04)V99` reading `DEFAULT   01000100150{` → +15.00 in [app/data/ASCII/discgrp.txt:L18] |
| **Target** | `src/main/resources/db/migration/V3__seed_data.sql`, and the fixed-width readers under `src/main/java/com/cardemo/batch/readers/` |
| **Decision** | Decode **only at positions the PIC clauses declare signed**, driven by the field definitions. Never a global character substitution |
| **Alternatives considered** | **(a) Global search-and-replace** of the overpunch characters across the record — rejected, and the reason is concrete: the same letters occur legitimately inside text fields such as merchant names and customer names, so a global rule corrupts them. **(b) Normalising the fixtures to signed decimal text** before loading — rejected: it edits data derived from `app/`, and it would erase the negative amounts the posting logic needs |
| **Rationale** | `app/data/ASCII/dailytran.txt` contains **both** `{` and `}` — measured: 25 occurrences of `{` and 6 of `}` — so it carries genuinely negative amounts and therefore **exercises the cycle-debit branch** of the posting logic. Normalising it would silence the branch that [DL-PP-10](#dl-pp-10) depends on |
| **Observable consequence** | Seeded balances match the source exactly, including negative values. A text field containing a letter from the decode table is loaded unchanged |
| **Test / gate evidence** | Overpunch assertions in `src/test/java/com/cardemo/e2e/GateVerificationTest.java` and `src/test/java/com/cardemo/e2e/BatchPipelineE2ETest.java`; fixture-driven repository tests in `src/test/java/com/cardemo/integration/repository/`. Gate-level: [Gate 4](docs/validation-gates.md#gate-4), whose result that ledger records |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Gate-pending` — 30 July 2026 |

<a id="dl-pp-09"></a>

### DL-PP-09 — Sign-on upper-cases **both** the identifier and the password

| Field | Content |
|---|---|
| **Classification** | `PARITY-PRESERVED` |
| **Severity** | **High** — upper-casing only the identifier rejects every mixed-case password the source accepts |
| **Source evidence** | [app/cbl/COSGN00C.cbl] moves both inputs through `FUNCTION UPPER-CASE` before use: the identifier at [:L132-L133] into `WS-USER-ID PIC X(08)` [:L45], and **the password** at [:L135-L136] into `WS-USER-PWD PIC X(08)` [:L46]. The comparison is then plaintext against the stored value: `IF SEC-USR-PWD = WS-USER-PWD` at [:L223], with the record read by key at [:L215-L216] |
| **Target** | `src/main/java/com/cardemo/security/CardDemoUserDetailsService.java` and `src/main/java/com/cardemo/service/auth/AuthenticationService.java` |
| **Decision** | Upper-case **both** the identifier and the password before the BCrypt comparison, and hash the upper-cased form when seeding |
| **Alternatives considered** | **(a) Upper-case the identifier only**, treating the password as case-sensitive as modern practice would — rejected: it is a behaviour change that rejects credentials the source accepts, and it would break sign-on for any user whose password contains a lower-case letter. **(b) Case-fold at the database level** — rejected: a BCrypt digest cannot be case-folded after the fact, so the normalisation must happen before hashing, consistently on both the seed path and the verify path |
| **Rationale** | The source's credential space is upper-case only. Normalising identically on both paths keeps the space unchanged while allowing the stored representation to become a hash — see [DL-MS-14](#dl-ms-14). Getting this wrong on only one of the two paths produces a system where no one can log in, and the cause is not visible in either path alone |
| **Observable consequence** | The ten seeded users authenticate with **the fixed legacy demo password** — read from [app/jcl/DUSRSECJ.jcl:L35-L44], not reproduced here — in any case combination, exactly as they did against the plaintext comparison. The identifier remains eight characters and the key length remains 8 |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/AuthenticationServiceTest.java`; `src/test/java/com/cardemo/unit/model/DemoUserSeedGateContractTest.java`. Gate-level: [Gate 5](docs/validation-gates.md#gate-5) and [Gate 6](docs/validation-gates.md#gate-6), whose results that ledger records |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-ms-17"></a>

### DL-MS-17 — A sequential record's position becomes the ingestion ordinal `ingest_seq`

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | Medium |
| **Source evidence** | `DALYTRAN` is a **physical sequential** dataset, not a keyed cluster: [app/jcl/POSTTRAN.jcl] allocates it with `DCB=(RECFM=F,LRECL=350)` and it appears under no `DEFINE CLUSTER` and in no `0GDG BASE` entry of [app/catlg/LISTCAT.txt]. Its layout [app/cpy/CVTRA06Y.cpy] declares `RECLN 350` and **thirteen** fields, none of them a key: `DALYTRAN-ID` is `X(16)` and is the transaction identifier the record carries, not a dataset key, and the same identifier legitimately repeats across runs. The source reads the file with `READ DALYTRAN-FILE` in arrival order and counts it: `WS-TRANSACTION-COUNT PIC 9(09) VALUE 0` at [app/cbl/CBTRN02C.cbl:L185], incremented once per accepted read by `ADD 1 TO WS-TRANSACTION-COUNT` at [:L206], reported at [:L227]. **For a sequential dataset the record's position in the file IS its identity**, and the source already computes exactly that ordinal |
| **Target** | `src/main/java/com/cardemo/model/entity/DailyTransaction.java` (field `ingestSequence`, the only `@Id`), `src/main/resources/db/migration/V1__create_schema.sql` (column `ingest_seq NUMERIC(9) NOT NULL`, `CONSTRAINT pk_daily_transaction PRIMARY KEY (ingest_seq)`), `src/main/java/com/cardemo/batch/readers/DailyTransactionReader.java` (the keyset scan that orders by it) |
| **Decision** | The staging table's identity is a **one-based, dense ingestion ordinal assigned by the loader in read order** — the `WS-TRANSACTION-COUNT` of the source — carried as a fourteenth persisted column named `ingest_seq`. It is **not** derived from any copybook field, and its name says so: every column derived from `CVTRA06Y.cpy` carries the `dalytran_` prefix and this one deliberately does not |
| **Alternatives considered** | **(a) Key the table on `dalytran_id`** — rejected as incorrect, not merely inconvenient: the identifier is not unique in a sequential file, and [app/data/ASCII/dailytran.txt] is a 300-record fixture whose records the posting job must process in arrival order regardless of identifier. A unique constraint would reject legitimate input. **(b) A composite key over all thirteen fields** — rejected: two byte-identical records are legal in a sequential dataset and the source posts both, so a composite key would silently drop the second. **(c) A database `GENERATED`/`IDENTITY` column or a sequence** — rejected: the ordinal must be the **loader's** read-order counter, exactly as the source computes it, and a database-assigned value would not be reproducible across a reload. The count of `GENERATED` clauses, `IDENTITY` columns and sequences created by the migration is therefore **zero, without exception**. **(d) No primary key at all** — rejected: `ddl-auto: validate` requires a mapped identity, and an unordered staged read would make the posting order — and therefore the reject sequence and every generated timestamp — non-deterministic |
| **Rationale** | Two properties had to hold simultaneously: the staged rows must be **readable in the source's arrival order** so that posting, rejection and identifier generation reproduce, and they must be **individually addressable** so a chunked reader can resume. A record-derived key gives neither. Recording the ordinal the source already maintains gives both, and it introduces no information the source does not have — which is what makes this a substitution of mechanism rather than a change of contract |
| **Observable consequence** | **Nothing at the byte boundary changes, and that is the property that matters.** The ordinal occupies none of the 350 record bytes, appears in no byte-offset map, and is absent from every emitted artefact: two staged records differing only in their ordinal emit **byte-identical 430-byte reject images**. What does change is that the staging table carries fourteen columns where the copybook declares thirteen fields, and a reader of the schema must know why. Ordering a staged read by `ingest_seq` reproduces arrival order exactly; ordering it by any record field would not |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/model/StagingKeyParityTest.java` — proves the byte-identical reject images directly; `src/test/java/com/cardemo/unit/model/EntityIdentityContractTest.java`, `SchemaStructureTest.java`, `TransactionTwinLayoutTest.java`, `DailyTransactionTest.java`; `src/test/java/com/cardemo/unit/batch/SequentialReaderKeysetScanTest.java`; `src/test/java/com/cardemo/integration/repository/DailyTransactionRepositoryTest.java`. Traceability: section 12.1a of [`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md), "The one persisted column no fixture and no copybook supplies", carries the column-by-column derivation and attributes `ingest_seq` to `CBTRN02C.cbl` `WS-TRANSACTION-COUNT` at `:L185`, `:L206` and `:L227` rather than to any copybook line. Gate-level: [Gate 4](docs/validation-gates.md#gate-4) |
| **Remediation / follow-up** | None outstanding. If a future requirement needs the staging rows keyed by business content, it is a schema change with a new decision, not an adjustment to this one |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 6 August 2026 |

<a id="dl-ms-18"></a>

### DL-MS-18 — Report submission: an embedded job deck collapses to one typed queue message

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | Medium |
| **Source evidence** | [app/cbl/CORPT00C.cbl] carries the submission job as a run of 80-byte literal constants — `JOB-DATA` at [:L81], with **17** `05`-level card entries spanning [:L82-L125] (see correction **C-3**), redefined as an array of up to a thousand card images by `JOB-LINES OCCURS 1000 TIMES PIC X(80)` at [:L126-L127]. The deck holds the job card, a notify continuation, a procedure-library statement, `//STEP10 EXEC PROC=TRANREPT` [:L94], the sort symbol definitions [:L100], [:L102], the date-parameter card [:L116] and a terminating `/*EOF` [:L125]. The two date values are injected through named subfields inside filler groups whose lengths make each card total 80 bytes: `PARM-START-DATE-1` [:L106], `PARM-END-DATE-1` [:L111], `PARM-START-DATE-2` and `PARM-END-DATE-2` [:L118], [:L120]. The submission loop is [:L498-L508] |
| **Target** | `src/main/java/com/cardemo/service/report/ReportSubmissionService.java`, publishing through the mechanism of [DL-MS-09](#dl-ms-09); the receiving listener maps the message onto job parameters |
| **Decision** | The 17 cards collapse into **one typed message** carrying the report name and the two dates. The sort symbol offsets become a typed predicate; the date-parameter card becomes job parameters |
| **Alternatives considered** | **(a) Emit the 17 cards verbatim** onto the queue — rejected: it transmits mainframe job-control syntax to a consumer that has no interpreter for it. **(b) Emit a single card containing only the dates** — rejected: it loses the report name, which selects the period semantics |
| **Rationale** | What must survive the collapse is **meaning plus outcome**, not syntax. Three details are therefore preserved precisely: <br>**(i) The confirmation handshake** distinguishes blank, affirmative, negative and invalid input, each with its own message, and the invalid-input message quotes the offending value back to the user. <br>**(ii) The failure outcome** reproduces the exact literal `Unable to Write TDQ (JOBS)...` [:L531-L532]. <br>**(iii) The terminating card is written before the loop exits** — the loop sets its termination flag at [:L502-L505] and then still calls the write at [:L507], so the `/*EOF` card is enqueued. The single message reproduces that as a complete, self-delimiting payload rather than a stream that must be terminated |
| **Observable consequence** | Three report periods are preserved. **Monthly is a full calendar month** — first of the month to the last day of the current month, [:L217-L234] — which corrects the specification; see **C-2**. Yearly is 1 January to 31 December of the current year, [:L243-L253]. Custom validates six discrete date components through the date service, [:L256], [:L381-L410]. The paragraph that performs the write is misspelled `WIRTE-JOBSUB-TDQ` at [:L515]; the misspelling is a source fact and the Java method is named correctly, since a paragraph name is not observable |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/ReportSubmissionServiceTest.java`; `src/test/java/com/cardemo/integration/aws/SqsReportQueueIntegrationTest.java`; `src/test/java/com/cardemo/integration/batch/TransactionReportJobTest.java`. Gate-level: [Gate 5](docs/validation-gates.md#gate-5), whose result that ledger records |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-dv-04"></a>

### DL-DV-04 — DEVIATION: the file-and-operation strategy map belongs in `FileService`, not in statement dispatch

| Field | Content |
|---|---|
| **Classification** | **`DEVIATION`** — placement decision; no output differs |
| **Severity** | Medium |
| **Source evidence** | The called subprogram's contract is a shared area declared at [app/cbl/CBSTM03A.CBL:L71-L83]: a DD name `PIC X(08)`, a **single-character operation** with **six** condition names — `'O'` open, `'C'` close, `'R'` read, `'K'` read-by-key, `'W'` write, `'Z'` rewrite, [:L74-L79] — a two-character return code, a 25-character key, a key length and a 1000-byte payload. `app/cbl/CBSTM03B.CBL` selects among exactly **four** files, by `SELECT` at [:L31], [:L37], [:L43] and [:L49] and by `EVALUATE` at [:L119-L125]. The call idiom is `CALL 'CBSTM03B' USING WS-M03B-AREA`, e.g. [app/cbl/CBSTM03A.CBL:L351] |
| **Target** | `src/main/java/com/cardemo/service/shared/FileService.java`, exposing the four-file by six-operation matrix with a two-character status and a payload buffer; consumed through `StatementProcessor.openDataset(FileService.Dd)` |
| **Decision** | Put the keyed strategy map **at the file-access layer**, where the variability is genuine. The statement processor's initialisation is ordinary sequential code — [DL-DV-02](#dl-dv-02) |
| **Alternatives considered** | **(a) A dispatch map in the statement processor**, modelling the `ALTER` chain — rejected: the chain has no runtime variability to model, so the map would be indirection over a constant. **(b) Four separate repository-style services**, one per file — rejected: it discards the operation dimension and the shared status contract, and the callers select the file by DD name at run time. **(c) Inline the subprogram's logic into each caller** — rejected: the subprogram is called from nine sites, and duplicating the status handling nine times is exactly the swallowing risk *Rule 1* clause B forbids |
| **Rationale** | The matrix genuinely varies in two dimensions — four files by six operations — and that is what a strategy map is for. It is labelled a deviation because the *structure* differs from the source's static linkage even though every observable outcome is identical, including the accepted secondary status: **every open and read site accepts `'00'` or `'04'`**, nine of them, listed in [DL-MS-11](#dl-ms-11) |
| **Observable consequence** | End-of-file is `'10'` and anything outside `{'00','04','10'}` abends, exactly as the source does. The 32-byte composite key of the statement record matches the work cluster's declared key length — [DL-PP-06](#dl-pp-06) |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/FileServiceTest.java`; `src/test/java/com/cardemo/unit/batch/StatementProcessorTest.java`. Gate-level: [Gate 1](docs/validation-gates.md#gate-1), whose result that ledger records. One of the ten deviations noted in `docs/validation-gates.md` [§12.5](docs/validation-gates.md#deviations) |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-pp-10"></a>

### DL-PP-10 — The posting sign branch and the over-limit formula are transcribed, not simplified

| Field | Content |
|---|---|
| **Classification** | `PARITY-PRESERVED` |
| **Severity** | **High** — an algebraic "simplification" here changes which transactions are rejected |
| **Source evidence** | Two expressions in [app/cbl/CBTRN02C.cbl] that must be transcribed literally. **(1) The over-limit test**, [:L403-L412]: `COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT`, then `IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL CONTINUE ELSE` assign 102. **(2) The sign branch**, [:L547-L552]: add the amount to the current balance, then `IF DALYTRAN-AMT >= 0` add it to the current-cycle **credit**, `ELSE` add it to the current-cycle **debit** |
| **Target** | `TransactionPostingProcessor.lookupAcct` and `updateAccountRec` in `src/main/java/com/cardemo/batch/processors/TransactionPostingProcessor.java`, with `narrowToWsTempBalPicture` preserving the working field's declared picture |
| **Decision** | Transcribe both expressions exactly. **No absolute-value normalisation anywhere on this path**, and no algebraic rewriting of the temporary-balance expression |
| **Alternatives considered** | **(a) `ABS(amount)` when accumulating the debit**, on the reasonable-looking ground that a debit accumulator should hold positive magnitudes — rejected, and this is the trap: **a negative amount is added to the debit accumulator, so the accumulator holds negative values**, which is precisely why the over-limit formula *subtracts* it. Normalising the sign inverts the over-limit test. **(b) Rewriting the formula** into an apparently equivalent form — rejected: the working field has a declared picture, so intermediate truncation is part of the result, which is why the narrowing is explicit in the target |
| **Rationale** | The two expressions are coupled through the sign convention. Either one changed in isolation looks harmless and breaks the other |
| **Observable consequence** | Transactions are accepted and rejected on exactly the source's boundary. The interest job's cycle reset interacts with this: `1050-UPDATE-ACCOUNT` adds accumulated interest to the balance and then **zeroes both cycle counters** at [app/cbl/CBACT04C.cbl:L352-L354]. Omitting that reset would break the over-limit arithmetic on the *following* posting cycle — a divergence that surfaces only on a second batch run |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/TransactionPostingProcessorTest.java`; `src/test/java/com/cardemo/unit/batch/InterestCalculationProcessorTest.java`; `src/test/java/com/cardemo/e2e/BatchPipelineE2ETest.java`. Gate-level: [Gate 1](docs/validation-gates.md#gate-1), whose result that ledger records |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-pp-11"></a>

### DL-PP-11 — Two numeric parsers, because the source deliberately uses two intrinsics

| Field | Content |
|---|---|
| **Classification** | `PARITY-PRESERVED` |
| **Severity** | Medium |
| **Source evidence** | [app/cbl/COTRN02C.cbl] uses the **plain** conversion for identifiers — `FUNCTION NUMVAL` for the account identifier at [:L204] and the card number at [:L218] — and the **currency-aware** conversion for the amount — `FUNCTION NUMVAL-C` at [:L383] and [:L456]. The display round-trip is equally specific: the parsed amount moves through an edited field declared `PIC +99999999.99` at [:L59] — mandatory sign, exactly eight integer digits, two decimals — while the numeric field is `PIC S9(9)V99` at [:L58] |
| **Target** | Distinct parsing paths in `src/main/java/com/cardemo/service/transaction/TransactionAddService.java` and the DTO validation for `TransactionAddRequest`, with a formatter matching the edited mask for the response echo |
| **Decision** | **Two distinct parsers**: a strict digits-only parser for identifiers and card numbers, and a currency-tolerant parser for amounts. One formatter reproducing the edited mask |
| **Alternatives considered** | **(a) One parser for everything** — rejected: whichever is chosen is wrong in one direction. The tolerant form accepts currency symbols and thousands separators in an account identifier, which the source rejects; the strict form rejects a currency-formatted amount, which the source accepts. **(b) Accepting any numeric format and normalising** — rejected: it widens the accepted input set beyond the source's on both paths |
| **Rationale** | The asymmetry is deliberate in the source and is directly observable through validation outcomes |
| **Observable consequence** | An amount entered with a currency symbol or thousands separators is accepted; the same characters in an account identifier or card number are refused. The echoed amount carries a mandatory sign and exactly two decimals |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/model/TransactionAddRequestTest.java`; `src/test/java/com/cardemo/unit/service/TransactionAddServiceTest.java`. Gate-level: [Gate 5](docs/validation-gates.md#gate-5), whose result that ledger records |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-pp-12"></a>

### DL-PP-12 — The generated timestamp is `yyyy-MM-dd-HH.mm.ss.SS0000`: two fraction digits, then four literal zeros

| Field | Content |
|---|---|
| **Classification** | `PARITY-PRESERVED` |
| **Severity** | Medium |
| **Source evidence** | `Z-GET-DB2-FORMAT-TIMESTAMP` at [app/cbl/CBTRN02C.cbl:L692-L705] builds a 26-character value from `FUNCTION CURRENT-DATE` and sets its final four characters to literal zeros: `MOVE '0000' TO DB2-REST` at [:L701], with the separators placed at [:L702-L703]. **The fractional geometry is fixed by the redefinition, not inferred.** `DB2-FORMAT-TS` is `PIC X(26)` at [:L159], and its `FILLER REDEFINES` layout at [:L160-L174] ends with `DB2-MIL PIC 9(002)` at [:L173] followed by `DB2-REST PIC X(04)` at [:L174] — **two** fraction digits plus **four** zeros, six fractional characters in total. The two digits come from `MOVE COB-MIL TO DB2-MIL` at [:L700], and `COB-MIL` is itself `PIC X(02)` at [:L157], being the hundredths field `FUNCTION CURRENT-DATE` returns. The arithmetic is what closes it: 4+1+2+1+2+1+2+1+2+1+2+4 = 26 exactly, whereas three fraction digits plus four zeros would need 27 against a `PIC X(26)` field. The value is applied to the transaction's processing timestamp at [:L437-L438], and in the interest program to **both** timestamps at [app/cbl/CBACT04C.cbl:L496-L498]. The layout comment at [app/cbl/CBTRN02C.cbl:L149] spells the shape out as `EEEE-MM-DD-UU.MM.SS.HH0000`, the `HH` there being hundredths |
| **Target** | `TransactionPostingProcessor.getDb2FormatTimestamp` and `InterestCalculationProcessor.db2FormatTimestamp` |
| **Decision** | Format to **`yyyy-MM-dd-HH.mm.ss.SS`** — a two-digit `SS` fraction, which is **hundredths of a second** — and then append the four literal zeros, giving `yyyy-MM-dd-HH.mm.ss.SS0000`. Never nanosecond, never microsecond, and **never millisecond**: an earlier revision of this entry was titled *"at millisecond precision"* and decided *"Format to **millisecond** precision followed by four literal zeros"*, and **both are withdrawn as wrong**. Millisecond precision is three digits, which would emit 27 characters into a 26-character field and would differ from the baseline on every record — the exact failure the decision exists to prevent. The Java realisation is `DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SS", Locale.ROOT)` with `"0000"` concatenated, in `TransactionPostingProcessor` and `InterestCalculationProcessor` |
| **Alternatives considered** | **(a) `LocalDateTime.now()` at nanosecond precision** — rejected: every generated timestamp would then differ from the baseline in its last four characters, on every record. **(b) Microsecond precision** — rejected for the same reason: the source's final four digits are always zero, so only the first two of the six fractional digits ever vary |
| **Rationale** | The trailing zeros are not padding; they are a fixed part of the emitted value, and the value is compared |
| **Observable consequence** | Generated timestamps are 26 characters ending in `0000`. Note that the interest program sets the originating **and** processing timestamps to the same generated value, so a generated interest transaction carries identical timestamps |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/TransactionPostingProcessorTest.java`; `src/test/java/com/cardemo/unit/batch/InterestCalculationProcessorTest.java`. Gate-level: [Gate 1](docs/validation-gates.md#gate-1), whose result that ledger records |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-dv-05"></a>

### DL-DV-05 — DEVIATION: a user-type guard the source does not have

| Field | Content |
|---|---|
| **Classification** | **`DEVIATION`** — an added authorisation check |
| **Severity** | Medium |
| **Source evidence** | Authorisation in the source is a screen-level convention rather than an enforced check. The identity available to a program is `CDEMO-USER-TYPE` from `app/cpy/COCOM01Y.cpy`, whose 88-levels are `'A'` and `'U'`, and the CSD defines all 18 transactions with the same profile — [app/csd/CARDDEMO.CSD] — so nothing at the transaction level distinguishes an administrative function from a user one |
| **Target** | Role-based rules in `src/main/java/com/cardemo/config/SecurityConfig.java`; the administrative operations are grouped under `/api/admin/*` in `src/main/java/com/cardemo/controller/AdminController.java` |
| **Decision** | Enforce the user-type distinction as a role check on the administrative endpoints, and **label it a deviation** |
| **Alternatives considered** | **(a) No enforcement**, exactly matching the source — rejected: it exposes user administration to any authenticated caller over HTTP, which is materially worse than the 3270 equivalent because the transport is open. *Rule 1* clause D requires least privilege. **(b) Enforcement without disclosure** — rejected: an added check that refuses a request the source would have served is observable, so it must be labelled |
| **Rationale** | The source's protection was partly environmental: reaching a transaction required a terminal session inside the region. Over HTTP that environmental control is gone, so the equivalent protection has to be explicit. The addition is justified but it is still an addition |
| **Observable consequence** | A standard-type caller receives a refusal on administrative endpoints where the legacy system would have dispatched the transaction. The reasoning is published in [`docs/api-contracts.md`](docs/api-contracts.md) as well as in the enforcing services, so a caller meets the explanation and not just the refusal |
| **Test / gate evidence** | `src/test/java/com/cardemo/e2e/OnlineTransactionE2ETest.java`; role enforcement under `src/test/java/com/cardemo/unit/security/`. Gate-level: [Gate 5](docs/validation-gates.md#gate-5) and [Gate 6](docs/validation-gates.md#gate-6), whose results that ledger records. One of the ten deviations noted in `docs/validation-gates.md` [§12.5](docs/validation-gates.md#deviations) |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Gate-pending` — 30 July 2026 |

<a id="dl-dv-06"></a>

### DL-DV-06 — DEVIATION: the confirm turn refuses an omitted payload member the source would have written

| Field | Content |
|---|---|
| **Classification** | **`DEVIATION`** — a refusal the source does not make |
| **Severity** | Medium |
| **Source evidence** | `1100-RECEIVE-MAP` runs on **every** turn, the confirm turn included — performed unconditionally at [app/cbl/COACTUPC.cbl:L1026] — and it opens by rebuilding the whole update image from the screen, `INITIALIZE ACUP-NEW-DETAILS` at [:L1047], so nothing is retained across turns. Per field, an untransmitted value arrives as `'*'` or `SPACES` and becomes `LOW-VALUES`: the credit score at [:L1279-L1284], into `ACUP-NEW-CUST-FICO-SCORE-X PIC X(03)` declared at [:L845-L847]. `1200-EDIT-MAP-INPUTS` then abandons the cascade **before its first field edit** when the change action is `ACUP-CHANGES-OK-NOT-CONFIRMED` — [:L1463-L1468] — so `1245-EDIT-NUM-REQD` at [:L1545-L1552], the edit that produces `FICO Score must be supplied.`, is never performed on this turn, and [:L2602-L2605] goes straight to `9600-WRITE-PROCESSING`. The two images then move the values unexamined: the account image from [:L3956-L4002], the customer image from [:L4007-L4059], the credit score at [:L4058-L4059]. **Two different unrepresentable outcomes follow, and the difference matters.** For the fifteen character values the record receives `LOW-VALUES` — three `NUL` bytes for the credit score — and the write succeeds. For the five amount values the outcome is not a value at all: [:L1101-L1112] writes `LOW-VALUES` only into the working-storage edit field `ACUP-NEW-CURR-BAL-X PIC X(15)` at [:L414] and performs the `COMPUTE` into the image **only** when `TEST-NUMVAL-C` passes, so the image field `ACUP-NEW-CURR-BAL PIC X(12)` keeps the spaces `INITIALIZE` left it, and [:L3964] moves those twelve spaces read through the `S9(10)V99` redefinition at [:L763-L765] into a packed-decimal field — a move of invalid digits, whose result the language does not define |
| **Target** | `requireStorableUpdateImage` and `refuseUnstorableField` in `src/main/java/com/cardemo/service/account/AccountUpdateService.java`, reached from `writeProcessing9600` before either record is read for update |
| **Decision** | Screen the twenty values the two images move, and refuse the write using the program's **own** field-level vocabulary — `FailureKind.BLANK` and the literal `' must be supplied.'` that `1215-EDIT-MANDATORY` at [:L1824-L1852] and `1225-EDIT-ALPHA-REQD` at [:L1898-L1951] already use — then **label that refusal a deviation** rather than presenting it as equivalence |
| **Alternatives considered** | **(a) Let the write proceed**, which is what the code did before the screen existed: the entity width guards raise an `IllegalArgumentException`, which is not a `CardDemoException`, so the abend handler installed at [:L862-L864] answered abend `9999` to an otherwise well-formed request. Rejected: abending on an omitted payload member is a larger divergence than a named refusal, and it is not what the source does either. **(b) Substitute the closest storable analogue** — blanks for the character columns, zero for the amount columns — and let the write succeed. Rejected, and this is the alternative the method's own commentary did not enumerate before this entry was written: blanks are a **different byte value** from `NUL`, so that is not parity either, and for the five amount columns there is no analogue at all, because the source's outcome there is undefined rather than a value. Substituting zero would silently overwrite a balance, a credit limit or a cycle total with zero, which is the worst outcome on offer. **(c) Split by field class** — blanks for the character values, refusal for the amounts. Rejected: it answers one cause two ways and makes the boundary unpredictable to a caller. **(d) Add the edits to the confirm turn**, so that the refusal comes from the cascade instead. Rejected: it re-runs the edits [:L1463-L1468] deliberately skips, changing which values the confirm turn accepts — the parity half this same entry protects |
| **Rationale** | The legacy answer is unavailable in both of its forms. A PostgreSQL text column cannot hold a zero byte in any encoding, and `customer.cust_fico_credit_score` is declared `cust_fico_credit_score    CHAR(3)    NOT NULL` in `src/main/resources/db/migration/V1__create_schema.sql`; an undefined result cannot be reproduced at all. Of the answers that remain, a field-level refusal is the only one that neither invents stored data nor abends on a well-formed request. It is chosen on those grounds and disclosed as a divergence, because the alternative — asserting it as the expected behaviour — leaves a reader unable to tell an intended substitute from an accidental one |
| **Observable consequence** | A confirm-turn request omitting any of the twenty members receives a field-level refusal naming the **first** omitted field in image order, carrying `FailureKind.BLANK` and `<label> must be supplied.`, where the source would have written both records and reported success at [:L2613-L2614]. **Nothing else about the confirm turn changes.** A value that is *present* but invalid is still written unvalidated — digits in a name, an all-blank group identifier, a non-numeric credit score, a state code the lookup refuses — because [:L1463-L1468] skips every edit. That half is parity, and it is asserted as parity rather than left to inference |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/AccountUpdateServiceTest.java`. `theConfirmTurnAbandonsTheWholeEditCascade` pins the parity half: it presents five values spanning four edit families that the first turn demonstrably refuses, primes every lookup collaborator to answer *invalid*, and observes the write happen anyway. `anAbsentCreditScoreIsADeviationBecauseTheSourceStoresLowValues` pins this deviation together with its cause, reading the column definition out of the migration rather than restating it, and requiring this entry to exist with its classification, its target method, its paragraph and its remediation. `theUnstorableValueScreenCoversExactlyTheMovedFields` censuses the screen from the shipped source and bounds it to exactly these twenty fields, so a widened screen fails rather than passing unnoticed. Gate-level: [Gate 5](docs/validation-gates.md#gate-5), whose result that ledger records |
| **Remediation / follow-up** | **Two triggers, and they are not symmetric.** Widening the fifteen character columns to nullable would make the character half representable and retire it; that is a schema change with its own review, owner: schema maintenance. The amount half **cannot** be retired by any schema change, because the source's own outcome there is undefined rather than a value, so it can only ever be a chosen substitute. Tabulated as `V-3` in `docs/validation-gates.md` [§12.5](docs/validation-gates.md#deviations) |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` |

---

<a id="dl-dv-09"></a>

### DL-DV-09 — DEVIATION: an unimplemented file operation is refused instead of republishing a stale status

| Field | Content |
|---|---|
| **Classification** | **`DEVIATION`** — a latent source fail-open path is closed on the default entry point |
| **Severity** | High if reached, which in the source it cannot be — see *Rationale* |
| **Source evidence** | `CBSTM03B` declares **six** operation codes — `'O'`, `'C'`, `'R'`, `'K'`, `'W'`, `'Z'` at [app/cbl/CBSTM03B.CBL:L102-L108] — and dispatches **four** DD names at [app/cbl/CBSTM03B.CBL:L118-L128]. Four by six is twenty-four cells, but each handler implements only `OPEN`, `CLOSE` and exactly one of the two reads: the plain `READ` for the two sequential datasets and the keyed `READ … KEY` for the two random ones. **Twelve cells are implemented and twelve are not**, and `WRITE` and `REWRITE` are implemented by no dataset at all. An unimplemented operation matches no `IF`, so control falls straight to the dataset's status epilogue — [app/cbl/CBSTM03B.CBL:L199-L200] for the customer file, and its three counterparts — which executes `MOVE <file>-STATUS TO LK-M03B-RC` **with no input or output having been performed**. The register therefore republishes whatever the *previous* call against that dataset left in it, which after a successful open is `'00'`: a success report for an operation that did nothing |
| **Target** | `src/main/java/com/cardemo/service/shared/FileService.java` — `execute` refuses the cell through `requireDispatchableCell`; `executeInLegacyParityMode` performs it exactly as the source does |
| **Decision** | **Fail closed on the default entry point, and keep the byte-exact behaviour reachable only under its own explicit name.** `execute` abends with culprit `CBSTM03B` when a recognised DD is paired with an operation that dataset does not implement; `executeInLegacyParityMode` reproduces the stale-status republication, so the defect stays *provable* without being *reachable by accident* |
| **Alternatives considered** | **(a) Reproduce the fail-open on `execute`**, on the reasoning that the subprogram has no abend path of its own — rejected, and the reasoning is right about the subprogram and wrong about the adapter. In the source the path is **unreachable**: the only caller, `app/cbl/CBSTM03A.CBL`, pairs DD names from its own literals with operations those datasets implement, so no live execution selects an unimplemented cell. In Java the request object accepts any operation code, so the same path becomes reachable from new code — and would answer a caller `'00'` over a no-op. **(b) Delete the two unimplemented operation codes from the enum** — rejected: they are declared in the source's shared area, and dropping them would misreport the contract and break the twelve-of-twenty-four census. **(c) Return a distinguished sentinel status** — rejected: no such status exists in the source, so inventing one substitutes a fabricated behaviour for a documented one |
| **Rationale** | The deviation is *scoped to reachability, not to behaviour*: nothing the source can execute changes, because the source cannot execute this path. What changes is what happens when **new** code does something the legacy caller never did, and answering that with a silent false success is precisely the failure mode Rule 1 clause B forbids |
| **Observable consequence** | A caller that asks for a write, a rewrite, or a keyed read of a sequential dataset receives a `FatalProcessingException` naming the DD and the operation instead of a return code of `'00'`. The message points at the parity-mode method, so a caller that genuinely wants the legacy answer is told where to get it |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/FileServiceTest.java` — `theMatrixIsTwelveOfTwentyFour` publishes the census; `anUnimplementedCellRepublishesTheStaleStatus` proves the defect in parity mode; `unimplementedCellIsRefused` and `allTwelveUnimplementedCellsAreRefused` prove the refusal; `refusalNamesTheAlternative` proves the message names the parity mode. Traceability: `TM-CBSTM03B-R003` through `TM-CBSTM03B-R014` |
| **Remediation / follow-up** | None outstanding. The parity mode is deliberately retained rather than deleted, because deleting it would leave the source behaviour unprovable |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 6 August 2026 |

---

<a id="dl-dv-07"></a>

### DL-DV-07 — DEVIATION: an unknown DD name is refused instead of reporting the caller's pre-set success

| Field | Content |
|---|---|
| **Classification** | **`DEVIATION`** — the second latent fail-open path of the same subprogram, closed the same way |
| **Severity** | High if reached, which in the source it cannot be |
| **Source evidence** | The dispatcher's last arm is `WHEN OTHER GO TO 9999-GOBACK` at [app/cbl/CBSTM03B.CBL:L127-L128], and `9999-GOBACK` is a bare `GOBACK` at [app/cbl/CBSTM03B.CBL:L130-L131]. **No status epilogue runs and `LK-M03B-RC` is never assigned at all**, so the register still holds whatever the caller pre-set. The caller's idiom is `MOVE ZERO TO LK-M03B-RC` before every call, so an unrecognised DD name returns **`'00'` — success — together with the blank payload the caller also pre-set.** This is a distinct defect from [DL-DV-09](#dl-dv-09): there the stale value comes from the dataset's own last operation, here it comes from the caller |
| **Target** | `src/main/java/com/cardemo/service/shared/FileService.java` — the DD-name resolution step of `requireDispatchableCell`; parity behaviour under `executeInLegacyParityMode` |
| **Decision** | **Refuse the unknown name with an abend naming it, and control-encode the untrusted name before it enters the message.** The legacy answer remains available under the parity-mode method |
| **Alternatives considered** | **(a) Report success, as the source does** — rejected for the reachability reason of [DL-DV-09](#dl-dv-09): the DD name arrives as an arbitrary string in Java, so a typo in new code would be answered `'00'` and a caller would proceed on data that was never read. **(b) Return a not-found status** — rejected: no such status is defined for this boundary, and inventing one is invention. **(c) Interpolate the rejected name verbatim into the abend message** — rejected on clause D grounds: the name is untrusted input, so it is control-encoded first, which is what `refusalEncodesTheUntrustedName` asserts |
| **Rationale** | Same scoping as [DL-DV-09](#dl-dv-09). One further source detail makes the refusal safe rather than lossy: the shared area declares the DD name `PIC X(08)`, so a ninth character is truncated by the picture clause and the name still resolves — that truncation is reproduced, and only genuinely unrecognised names are refused |
| **Observable consequence** | A caller naming a DD outside the four resolves nothing and receives a `FatalProcessingException` rather than a blank payload with a success code |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/FileServiceTest.java` — `anUnknownDdNameReportsSuccess` and `thePresetReturnCodeIsWhatSurfaces` prove the defect in parity mode; `unknownDdIsRefused` and `refusalEncodesTheUntrustedName` prove the refusal; `aNinthCharacterIsTruncatedByThePictureClause` proves the preserved truncation; `anUnknownDdIsTracedWithItsFalseSuccess` proves the trace line. Traceability: `TM-CBSTM03B-R001` and `TM-CBSTM03B-R002` |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 6 August 2026 |

---

<a id="dl-dv-08"></a>

### DL-DV-08 — DEVIATION: a sealed token carries the browse position and record handle the COMMAREA used to hold

| Field | Content |
|---|---|
| **Classification** | **`DEVIATION`** — an added transport-level protection over state the plan moves out of the server and into the request |
| **Severity** | Medium |
| **Source evidence** | `01 WS-THIS-PROGCOMMAREA` at [app/cbl/COCRDLIC.cbl:L229-L242] holds the browse position of a seven-row page: `WS-CA-FIRST-CARDKEY` and `WS-CA-LAST-CARDKEY`, each a sixteen-character card number with its eleven-digit account identifier, plus `WS-CA-SCREEN-NUM`, `WS-CA-LAST-PAGE-DISPLAYED` and `WS-CA-NEXT-PAGE-IND`. The region carries it across the pseudo-conversation by appending it after `CARDDEMO-COMMAREA` and restoring it at [app/cbl/COCRDLIC.cbl:L327-L330]. The operator cannot see it, cannot edit it and cannot replay someone else's, because the carrier is unreachable from a terminal. AAP transformation rule 7 removes that carrier: pagination state becomes request parameters and response metadata, which hands the browse position to the client |
| **Target** | `src/main/java/com/cardemo/security/SnapshotTokenService.java`, consumed by `src/main/java/com/cardemo/controller/CardController.java` — `sealCursor`/`openCursor` for the page cursors a response publishes as `firstCursor` and `lastCursor`, and `seal`/`open` for the card handle a list row carries in place of a bare card number |
| **Decision** | **Publish the position sealed and authenticated, and require it back.** The seal is `HMAC-SHA-256` under a key derived from the configured signing key by `HMAC-SHA-256(configured-key, "carddemo-snapshot-token-v1")`, so the one existing secret indirection is *derived from* rather than shared with the JWT key. The seal kind is per page number, which makes the page authenticated additional data: a cursor issued for one page does not verify when presented with another. A cursor that does not verify is refused as an invalid field, naming the parameter and telling the caller to page again from a response |
| **Alternatives considered** | **(a) Publish the raw browse key**, which is what a literal reading of transformation rule 7 gives — rejected: the key is the *position* a browse resumes from, so a caller able to edit it can resume a browse anywhere, and a client that fabricates one gets a page the source could never have shown it. **(b) Hold the position server-side in a session or cache** — rejected: it reintroduces exactly the server-side conversational state rule 7 forbids, and it would make paging non-idempotent under a load balancer. **(c) A second dedicated secret for sealing** — rejected: it doubles the secret surface an operator manages for no gain, and recovering either key from the other requires inverting HMAC. **(d) Publish the position unsealed and validate its shape only** — rejected: a shape check cannot distinguish a key this server issued from one a caller composed, which is the whole distinction the COMMAREA used to make for free |
| **Rationale** | The protection the source relied on was *environmental*: the COMMAREA was unreachable because the client was a terminal. Over HTTP that unreachability is gone, so the equivalent protection has to be cryptographic. Nothing about paging behaviour changes — the page size stays seven, the keys stay the same keys, and only who is trusted to carry them changes |
| **Observable consequence** | A cursor that was edited, re-keyed, issued for a different page or issued by a different deployment is refused identically and deliberately, because distinguishing them would be an oracle. The server log names the parameter and the page; no message quotes the token, the key or the recovered position |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/security/SnapshotTokenServiceTest.java` for the seal, the open and every refusal arm; `src/test/java/com/cardemo/unit/controller/CardControllerTest.java` for the per-page kind and the refusal a controller turns it into; and the paging round trip over HTTP in `src/test/java/com/cardemo/e2e/OnlineTransactionE2ETest.java`. Gate-level: [Gate 5](docs/validation-gates.md#gate-5) and [Gate 6](docs/validation-gates.md#gate-6) |
| **Remediation / follow-up** | **One earlier reading of this entry is withdrawn.** It recorded this token as an `ETag` on the account read and an `If-Match` precondition on the account write, and rejected "trust the `oldDetails` group in the request body" as an alternative. That inverted the contract: AAP section 0.5.1.4 fixes `AccountUpdateRequest` as carrying **both** `oldDetails` and `newDetails`, transformation rule 16 and section 0.7.1.5 make the snapshot the request's own payload, and finding F-018 restored it. No endpoint in the tree emits an `ETag` or reads `If-Match`, which `src/main/java/com/cardemo/security/package-info.java` states at the point a reader would look for one. The lost-update guard is therefore the two-layer check of [DL-PP-02](#dl-pp-02) — the `@Version` column and the field-by-field comparison — and this token's role is the browse position and the record handle only |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 7 August 2026 |

<a id="dl-pp-13"></a>

### DL-PP-13 — Low-severity source behaviours are reproduced verbatim rather than normalised

| Field | Content |
|---|---|
| **Classification** | `PARITY-PRESERVED` |
| **Severity** | Low |
| **Source evidence** | The corpus contains many small behaviours that read as oversights and that a well-meaning implementer would tidy. Each is individually cited at the Java declaration that reproduces it; the register below names the ones a reviewer is most likely to meet, with their locators. **`COUSR00C`:** `SET NEXT-PAGE-NO TO TRUE` at [app/cbl/COUSR00C.cbl:102] is overwritten on every path that goes on to read the flag, because [:114] restores the whole communication area over it; the browse start key at [:263] is `HIGH-VALUES` rather than `LOW-VALUES`, so a malformed request positions past the last record; `MOVE SPACE TO USRIDINO` at [:328] makes the later clearing at [:231-233] a no-op, and the backward path omits that clear entirely, so a backward page echoes the identifier field while a forward page blanks it; `RESP` and `RESP2` are captured at [:555-556] and never examined, unlike every other I/O paragraph in the program; the page counter is `PIC 9(08)` with no `ON SIZE ERROR`, so eight nines wrap to zero; and `WS-REC-COUNT` at [:52] and `WS-USER-DATA` at [:56-64] are declared and referenced nowhere. **`COBIL00C`:** the absent `HANDLE CONDITION` means an input area that cannot be bound is silently accepted and left holding whatever it held; a failing `ENDBR` is silently ignored and the flow continues into identifier generation. **`COACTVWC`:** the cross-reference-to-account transition is guarded while the account-to-customer transition at [app/cbl/COACTVWC.cbl:708-711] is not. **`CBSTM03B`:** an operation that performs no input or output leaves the previous call's status in place, so the status registers are per-run state rather than per-call. **`CSLKPCDY`:** `02 LAST-3-OF-ZIP PIC X(3)` at [app/cpy/CSLKPCDY.cpy:L1314] is declared and never validated, because the condition name sits on the four-character state-and-first-two-digits item alone. **`CSUTLDTC`:** the `:L122` group move and the misnamed success token. **`CBTRN02C`:** reject codes `101` and `109` carry byte-identical description text |
| **Target** | The declaration that reproduces each behaviour, in `UserListService`, `BillPaymentService`, `AccountViewService`, `FileService`, `ValidationLookupService` and `RejectCode`. **The tracking reference is the declaration itself**, which carries the COBOL locator, the reason the behaviour looks wrong, and the statement that it is reproduced rather than repaired |
| **Decision** | **Reproduce each one exactly, and normalise none of them.** A redundant assignment stays redundant, an unexamined status capture stays unexamined, a silent truncation stays silent, an asymmetry between two paths stays asymmetric, a commented-out option stays commented out, an absent guard stays absent, and a declaration the source never reads is not carried into the target as a field nothing would touch. Where a behaviour has no Java field to occupy — a dead working-storage item — it is documented at the method that would have read it rather than modelled, because carrying it would create dead code in the target to mirror dead code in the source |
| **Alternatives considered** | **(a) Tidy each one** — rejected: every one of them is observable, several through the exact bytes a gate compares, and "obviously harmless" is not a property that can be established one site at a time against a 19,254-line corpus. **(b) Give each site its own entry here** — rejected: they share one decision and one severity, so twenty near-identical entries would obscure rather than expose the decision, and the per-site justification already exists where it belongs, at the declaration. **(c) Normalise only the ones with no test coverage** — rejected outright: that makes the tidy-up conditional on the tree's own blind spots |
| **Rationale** | Parity is the acceptance contract, and these behaviours are behaviour. The register is deliberately **keyed by locator and open**: it is a reading aid naming the sites most likely to be met, not a total, and it carries no count for the same reason [DL-CR-01](#dl-cr-01) carries none — a declared total is falsified silently by the next site and nothing notices |
| **Observable consequence** | A reviewer reading any of these declarations meets a comment saying the behaviour is the source's and is preserved on purpose. A maintainer who "fixes" one changes emitted bytes, a response payload or a validation outcome, and the covering test fails |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/UserListServiceTest.java`; `src/test/java/com/cardemo/unit/service/BillPaymentServiceTest.java`; `src/test/java/com/cardemo/unit/service/AccountViewServiceTest.java`; `src/test/java/com/cardemo/unit/service/FileServiceTest.java`; `src/test/java/com/cardemo/unit/service/ValidationLookupServiceTest.java`; `src/test/java/com/cardemo/unit/validation/LanguageEnvironmentDateContractTest.java`; `src/test/java/com/cardemo/unit/model/RejectCodeTest.java` |
| **Remediation / follow-up** | **The register is open by construction.** A further preserved behaviour of this kind needs no new entry here; it needs the same three things at its own declaration — the COBOL locator, the reason it looks wrong, and the statement that it is preserved — and it may be added to the register above as a reading aid. What is **not** permitted is a site that carries none of the three, because that is indistinguishable from an accident. **One item in the register above has been withdrawn, and the withdrawal is recorded here rather than performed silently.** It read: *“`GTEQ` is left commented out, so no equal-or-greater positioning semantic is enabled”*, and it was wrong on the COBOL. `GTEQ` is the **default** option of `EXEC CICS STARTBR` for a direct browse of a KSDS, and `USRSEC` is a KSDS — `app/jcl/DUSRSECJ.jcl` defines it with `KEYS(8,0)` and `INDEXED`. Commenting the option out leaves neither `GTEQ` nor `EQUAL` coded, so the effective option is the default and the effective semantic is equal-or-greater. This is the one error a register of preserved quirks is most exposed to: a behaviour was read out of the source, found surprising, and preserved — when the surprising reading was itself the mistake and there was no quirk there at all. Preserving it cost real behaviour, because `UserListService` validated a supplied browse key with a primary-key existence lookup, so an identifier naming no record answered not-found even when records followed it. The lookup is now a one-row equal-or-greater probe, and the two Javadoc blocks that cited this row have been corrected. Nothing else in the register above is affected. |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 7 August 2026. One register item withdrawn as a misreading of the source, with the reason recorded in the remediation cell — 8 August 2026 |

---

<a id="dl-dv-10"></a>

### DL-DV-10 — The boundaries where a Rule 1 standard, or the absence of a behaviour there is to reproduce, overrides parity

| Field | Content |
|---|---|
| **Classification** | `DEVIATION` |
| **Severity** | Medium |
| **Source evidence** | **(1) The interest job's date parameter.** `CBACT04C` receives a ten-character parameter through its procedure-division header at [app/cbl/CBACT04C.cbl:L175-L180] and validates nothing: a malformed value is concatenated straight into every generated identifier at [:L473-L515]. **(2) The sign-on message.** `COSGN00C` returns `'User not found. Try again ...'` at [app/cbl/COSGN00C.cbl:L247-L251] and `'Wrong Password. Try again ...'` at [:L241-L246], so the screen tells an unauthenticated caller which of the two failed. **(3) The menu bounds check.** `COMEN01C` indexes its option table before testing the supplied option against the populated count, so an out-of-range option reads adjacent storage. **(4) The abend message width.** `ABEND-MSG` is `PIC X(72)` at [app/cpy/CSMSG02Y.cpy:001200-002000], so a diagnostic longer than seventy-two characters is truncated. **(5) The report's closing totals on an empty stream.** `CBTRN03C` reaches its end-of-data arm at [app/cbl/CBTRN03C.cbl:L198-L201] and adds `TRAN-AMT` to the page and account totals even when no record was ever read; the record area is copied in at [:L93] with no `VALUE` clause, so the operand is whatever the region left there and the `ADD` has no defined result. **(6) The report's monetary and card-number diagnostics.** [app/cbl/CBTRN03C.cbl:L180] displays the whole record image and [:L487] displays the whole card number, both to a SYSOUT held under dataset-level protection, and the record image carries the card number and the transaction amount. **(7) The report's three unexamined lookup statuses.** The three lookup paragraphs at [app/cbl/CBTRN03C.cbl:L485-L491], [:L495-L501] and [:L505-L511] guard `INVALID KEY` only, so a `'9x'` physical failure or a `'35'` sets a status that nothing reads, the record area still holds the previous iteration's contents, and the report continues to be written from stale data with no diagnostic at all. **(8) The edited amount mask's zero branch.** The total line declares `REPT-ACCOUNT-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ` at [app/cpy/CVTRA07Y.cpy:L60] and the detail line `TRAN-REPORT-AMT PIC -ZZZ,ZZZ,ZZZ.ZZ` at [:L30], so both masks suppress every digit position and both carry a **fixed** insertion sign; the corpus does not determine whether the IBM suppression region includes that sign character — the two readings differ in exactly one emitted byte |
| **Target** | `InterestCalculationJob` for the parameter guard, `com.cardemo.service.auth.AuthenticationService` with `SecurityConfig` for the sign-on response, `MainMenuService` for the bounds short-circuit, `ValidationLookupService`'s abend construction for the message width, and `TransactionReportProcessor` for boundaries (5) to (8) — its `finishReport`, its reported-record diagnostic and card-number mask, its three lookup guards, and its edited-amount formatter |
| **Decision** | **Deviate at each of these boundaries, and label each rather than presenting it as parity.** (1) Validate the date parameter and refuse a malformed one, because *Rule 1* clause A requires untrusted input to be treated as untrusted and an unvalidated value would corrupt every identifier in the generation and surface only downstream in the combine job's load. The value the job control actually supplies passes unchanged. (2) Do not differentiate the two failures on the REST surface, because clause D's non-disclosure standard forbids telling an unauthenticated caller which half of a credential pair was wrong; the distinction survives internally as a typed exception and a structured log that carries no credential. (3) Refuse an option greater than the populated count **before** any table access, because a COBOL table overread has **no defined Java semantics** and therefore cannot be reproduced at all; the observable outcome is unchanged, since both source paths redisplay the menu carrying a message. (4) Allow the diagnostic to exceed seventy-two characters, because the resource path is required in the message and truncating to the legacy width discards exactly the part an operator needs. (5) **Define** the empty-stream contribution as zero and emit the closing totals reading zero, because an undefined COBOL result has **no defined Java semantics** and so cannot be reproduced at all; the header block still depends on the first record, so an empty stream still yields closing lines and no headers, which is the observable half that *is* parity. (6) Mask the card number to its last four digits, replace the amount with a redaction token rather than partially masking it, emit the fields individually rather than as an unlabelled 350-character run, and route the whole emission to a parity logger that is `OFF` in every shipped profile — because clause D forbids a card number or an amount on an aggregated, searchable log stream, and a DEBUG level on the class logger is a switch an operator can flip. The report **body** keeps whatever the layout requires, because the body is the deliverable and not a log. (7) Refuse a non-`INVALID KEY` lookup failure with a typed fatal exception, because clause B forbids swallowing and AAP transformation rule 12 requires a typed exception on **every** I/O path; a report silently built from a stale buffer is the class of corruption the parity gates exist to detect rather than to enshrine. (8) Treat the fixed insertion sign as **outside** the suppression region, so a zero total keeps its mandatory sign while a zero detail amount blanks entirely — and disclose the choice rather than assume it, with the one-line reversal named below |
| **Alternatives considered** | **(a) Reproduce each one literally** — impossible for (3) and unacceptable under a named clause for (1), (2) and (4). **(b) Reproduce (2) and rely on rate limiting to blunt the enumeration** — rejected: rate limiting is deferred hardening at [DL-RR-02](#dl-rr-02), so relying on it would make a security property depend on work that is explicitly not in this delivery. **(c) Truncate the abend message and log the full text separately** — rejected: it splits one diagnostic across two sinks, and the operator reading the abend is the one who needs the path. **(d) Throw on the empty stream at (5) rather than defining zero** — rejected: the source emits closing lines on an empty stream and that half is parity, so refusing the run would diverge on the observable output in order to avoid diverging on an operand that has no defined value. **(e) Partially mask the amount at (6)** — rejected outright: there is no partial form of an amount that neither discloses its magnitude nor reads as a *different* amount. **(f) Lower the report's diagnostics to DEBUG on the class logger instead of isolating them at (6)** — rejected: a level is operator-settable, so the protection would hold only until someone raised the verbosity to debug a report. **(g) Reproduce (7) faithfully and rely on the parity gates to notice** — rejected: the failure mode is a plausible-looking report built from a stale buffer, which is exactly the outcome a byte comparison cannot be trusted to flag, because the bytes are well-formed. **(h) Pick the stricter reading at (8) silently** — rejected: both readings are defensible from the corpus, so the choice belongs in writing beside the code that makes it |
| **Rationale** | Parity governs everywhere it can. These are the boundaries at which it cannot, and they fall into two kinds. At (1), (2), (4), (6) and (7) reproducing the source would require the target to breach a named clause of the one rule this project was given. At (3), (5) and (8) there is **no behaviour there to reproduce**: a COBOL table overread and an `ADD` over an unassigned operand have no defined result, and the corpus does not settle which reading of the editing rule its own output embodies. Each is therefore a labelled deviation carrying its own reason, not a silent improvement — and the count is left out of this entry's title on purpose, because a title that states a total is falsified by the next boundary and nothing notices |
| **Observable consequence** | (1) A malformed date parameter fails the job at launch instead of poisoning a generation. (2) Both sign-on failures return the same refusal. (3) An out-of-range menu option is refused before any lookup. (4) An abend message may exceed seventy-two characters, and the widening is contained at the one constructor that builds it. (5) An empty report run emits its three closing lines reading zero, and no headers, instead of failing on an undefined operand. (6) No card number and no transaction amount reaches the application log by any path in the report processor, while the 133-byte report body is unchanged. (7) A physical lookup failure in the report abends with the four-character rendered status instead of producing a well-formed report from stale data. (8) A zero total prints its mandatory sign followed by fourteen spaces, and a zero detail amount prints as spaces throughout |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/MainMenuServiceTest.java` makes (3) observable rather than asserted; `src/test/java/com/cardemo/unit/service/AuthenticationServiceTest.java` and `src/test/java/com/cardemo/unit/security/` cover (2); `src/test/java/com/cardemo/unit/batch/InterestCalculationJobTest.java` covers (1) — `rejectsImplausibleCalendarDates` and `jobValidatorRejectsBadParmDate`; `src/test/java/com/cardemo/unit/service/ValidationLookupServiceTest.java` covers (4). `src/test/java/com/cardemo/unit/batch/TransactionReportProcessorTest.java` covers (5) — `finishReport` on an empty run treats the stale buffer as zero rather than throwing — (7), in the group that asserts a lookup miss abends while rendering `FILE STATUS 0023`, and (8), in the group that asserts a zero detail amount blanks entirely while a zero total keeps its sign; `src/test/java/com/cardemo/unit/batch/ParityLoggerRoutingTest.java` and `src/test/java/com/cardemo/unit/batch/FinancialLogRedactionTest.java` cover (6), the first asserting that the shipped configuration pins the parity logger `OFF` and that the report body still carries the amount |
| **Remediation / follow-up** | None outstanding, and one reversal is named rather than merely permitted: should a legacy Gate 1 baseline ever show a blank where (8) prints a sign, return the full field width of spaces for both masks, which is a one-line change in the formatter's zero branch. A further boundary of this kind requires its own numbered item here and its own named clause; "it seemed safer" is not a ground, because that reasoning would readmit every quirk [DL-PP-13](#dl-pp-13) preserves |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 7 August 2026. Boundaries (5) to (8) added, each already implemented and covered, when the report processor's sites were converted from a forward reference to this identifier — 8 August 2026 |

---


---

<a id="legacy-defects"></a>

## 6. Preserved legacy defects

Every defect in this section exists in the frozen corpus. **None is repaired.** Two
reasons apply to all of them: repairing any would change behaviour the parity comparison
is measured against, and `app/` is read-only, so several could not be repaired at source
even if that were desired. They are recorded so that a reviewer meeting one recognises a
known legacy artefact rather than a migration error.

`docs/validation-gates.md` [§12.3](docs/validation-gates.md#legacy-defects) tabulates the same
defects as `D-1`…`D-11` and the behavioural quirks as `Q-1`…`Q-5` in
[§12.4](docs/validation-gates.md#quirks). The entries below carry the **decision and the
rejected alternative** for each, which the tables do not.

<a id="dl-ld-01"></a>

### DL-LD-01 — A corrupted data-definition line in the statement job

| Field | Content |
|---|---|
| **Classification** | `PRESERVED LEGACY DEFECT` |
| **Severity** | Low behaviourally; **Medium** as a reading hazard |
| **Source evidence** | [app/jcl/CREASTMT.JCL:L90] reads, verbatim: `//         SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS` — fragments of three different clauses left behind by an overtyped edit, inside the `STMTFILE` definition of `STEP040` that begins at [:L87] |
| **Target** | `src/main/java/com/cardemo/batch/jobs/StatementGenerationJob.java` and `src/main/java/com/cardemo/batch/writers/StatementWriter.java` reproduce the step's **effective** behaviour: a new text output at `LRECL=80`, per [:L89] |
| **Decision** | Reproduce the effective behaviour and **report** the corruption. Do not repair the line, and do not guess at what the truncated fragments were meant to say |
| **Alternatives considered** | **(a) Reconstruct the intended clauses** — rejected twice over: it requires editing the frozen corpus, and the reconstruction would be a guess presented as fact. **(b) Treat the step as undefined** and skip it — rejected: the step ran and produced statements for the lifetime of the system, so its effective behaviour is well determined by the surviving clauses |
| **Observable consequence** | None at run time. The hazard is to a reader, who may take the fragments for real parameters |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/StatementWriterOutputContractTest.java`; `src/test/java/com/cardemo/integration/batch/StatementGenerationJobTest.java`. Recorded as `D-1` in `docs/validation-gates.md` |
| **Remediation / follow-up** | None. Repair would require unfreezing `app/`; owner: not this migration |
| **Verification** | `Source-verified`, `Target-verified` — 30 July 2026 |

<a id="dl-ld-02"></a>

### DL-LD-02 — The markup output's record length disagrees between two steps: 80 versus 100

| Field | Content |
|---|---|
| **Classification** | `PRESERVED LEGACY DEFECT` |
| **Severity** | Medium |
| **Source evidence** | The pre-delete step declares `DCB=(LRECL=80,BLKSIZE=3200,RECFM=FB)` for `HTMLFILE` at [app/jcl/CREASTMT.JCL:L69]; the execution step declares `DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)` for the same dataset at [:L94]. Independent corroboration of 100 comes from the program: statement markup is emitted from a single `HTML-FIXED-LN PIC X(100)` field at [app/cbl/CBSTM03A.CBL:L149], with dozens of condition names each carrying one markup fragment as a literal |
| **Target** | `src/main/java/com/cardemo/batch/writers/StatementWriter.java` — the markup writer stays at **100** characters per line; the text writer stays at 80 |
| **Decision** | Follow the **execution** step's value. The mismatch is logged, not reconciled |
| **Alternatives considered** | **(a) Follow the pre-delete step's 80** — rejected: the pre-delete step only deletes; the emitting program writes a 100-character field, so 80 would truncate every markup line. **(b) Reconcile the two declarations** — rejected: it edits the corpus, and the disagreement is itself evidence a reader should see |
| **Observable consequence** | Markup lines are exactly 100 characters. This is also why the 80-versus-100 disagreement is not a signal to change the width: the program's field width settles it |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/StatementWriterOutputContractTest.java`; `src/test/java/com/cardemo/unit/batch/StatementHtmlEscapingTest.java`. Recorded as `D-2` in `docs/validation-gates.md` |
| **Remediation / follow-up** | None |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-ld-03"></a>

### DL-LD-03 — A procedure whose internal name differs from the member name

| Field | Content |
|---|---|
| **Classification** | `PRESERVED LEGACY DEFECT` |
| **Severity** | Low |
| **Source evidence** | [app/proc/TRANREPT.prc:L1] declares `//REPROC PROC`, while `//STEP10 EXEC PROC=TRANREPT` — as embedded at [app/cbl/CORPT00C.cbl:L94] — resolves the **member** `TRANREPT`. The sibling member `app/proc/REPROC.prc` also opens `//REPROC PROC`, so two members declare the same internal procedure name |
| **Target** | `src/main/java/com/cardemo/batch/jobs/TransactionReportJob.java`, named for the member that the calling statement resolves |
| **Decision** | Name the Java job after the **member**, and report the discrepancy |
| **Alternatives considered** | **(a) Name it `Reproc…`** after the internal declaration — rejected: it is the member name that the job stream resolves, so the internal name is the misleading half. **(b) Rename either member** — impossible without editing the corpus |
| **Observable consequence** | None at run time; procedure resolution is by member name. The hazard is to anyone reading the job stream, who may search for a procedure named `TRANREPT` inside the member and not find one |
| **Test / gate evidence** | `src/test/java/com/cardemo/integration/batch/TransactionReportJobTest.java`. Recorded as `D-3` in `docs/validation-gates.md` |
| **Remediation / follow-up** | None |
| **Verification** | `Source-verified`, `Target-verified` — 30 July 2026 |

<a id="dl-ld-04"></a>

### DL-LD-04 — Reject code 103 overwrites 102, because the two checks are sequential and unguarded

| Field | Content |
|---|---|
| **Classification** | `PRESERVED LEGACY DEFECT` — reproduced deliberately |
| **Severity** | Medium |
| **Source evidence** | [app/cbl/CBTRN02C.cbl:L393-L422]. The over-limit test closes its own `END-IF` at [:L413], and the expiry test opens **immediately** at [:L414] with no alternative branch and no early exit between them: `IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10) CONTINUE ELSE` assign 103 at [:L417-L419]. Both write to the same field, so when both conditions fail, **103 overwrites 102** and a single reject record bearing 103 is written. The surrounding cascade is two paragraphs only — `1500-VALIDATE-TRAN` at [:L370-L378] performs the cross-reference lookup, then the account lookup **only if** the reason code is still zero, and is followed by the literal comment `* ADD MORE VALIDATIONS HERE` at [:L377] |
| **Target** | `TransactionPostingProcessor.lookupAcct` in `src/main/java/com/cardemo/batch/processors/TransactionPostingProcessor.java` — the two checks are sequential and unguarded, in that order |
| **Decision** | Preserve the fall-through exactly. One reject record, carrying 103 |
| **Alternatives considered** | **(a) Guard the second check** so 102 survives — rejected: it changes which reject code a downstream reader sees. **(b) Emit two reject records** — rejected: the source writes one record per rejected transaction, and the count drives return code 4, so two records would also change the exit code arithmetic. **(c) Combine them into one composite code** — rejected: it invents a sixth value, and there are exactly five ([DL-PP-03](#dl-pp-03)) |
| **Observable consequence** | A transaction that is both over limit and past expiry is reported as `TRANSACTION RECEIVED AFTER ACCT EXPIRATION`, never as `OVERLIMIT TRANSACTION`. Two further details are preserved with it: the expiry comparison is a **string** comparison against the **originating** timestamp's first ten characters — not the processing timestamp — and the account expiry field name is **misspelled `ACCT-EXPIRAION-DATE`** in the copybook, a misspelling that is part of the field contract and appears identically at [app/cbl/COACTUPC.cbl:L4131-L4133] |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/TransactionPostingProcessorTest.java` asserts the 102-then-103 fall-through. Gate-level: [Gate 1](docs/validation-gates.md#gate-1), whose result that ledger records. Recorded as `Q-1` in `docs/validation-gates.md` |
| **Remediation / follow-up** | None |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-ld-05"></a>

### DL-LD-05 — The report breaks control on the card number while the label reads `Account Total`

| Field | Content |
|---|---|
| **Classification** | `PRESERVED LEGACY DEFECT` — reproduced deliberately |
| **Severity** | Medium |
| **Source evidence** | The break is on the **card number**: `IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM` at [app/cbl/CBTRN03C.cbl:L181], guarded against the first record at [:L182], calling `1120-WRITE-ACCOUNT-TOTALS` at [:L183]. That paragraph, at [:L306-L316], writes the `REPORT-ACCOUNT-TOTALS` group — **and the label lives in the copybook, not in the program**: [app/cpy/CVTRA07Y.cpy:L56-L58] declares the group with the literal `'Account Total'`. Page size is 20: `WS-PAGE-SIZE ... VALUE 20` at [app/cbl/CBTRN03C.cbl:L131-L132], enforced by a modulo test at [:L282] |
| **Target** | `src/main/java/com/cardemo/batch/processors/TransactionReportProcessor.java` — breaks on the card number, emits the `Account Total` label, 20 lines per page |
| **Decision** | Preserve **both halves**: the break key and the label text |
| **Alternatives considered** | **(a) Change the label to `Card Total`** — rejected: the emitted text is compared byte-for-byte. **(b) Change the break key to the account identifier** to match the label — rejected: it changes the grouping and therefore every subtotal on the report |
| **Observable consequence** | A report subtotal labelled `Account Total` is in fact a per-card total. Note precisely **where** the mismatch lives: the label is in the copybook, so "fixing" the program would not even address it — which is itself a reason the mismatch survived |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/TransactionReportProcessorTest.java`; `src/test/java/com/cardemo/integration/batch/TransactionReportJobTest.java`. Recorded as `Q-2` in `docs/validation-gates.md` |
| **Remediation / follow-up** | None |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-ld-06"></a>

### DL-LD-06 — User deletion has no self-delete guard, and none was invented

| Field | Content |
|---|---|
| **Classification** | `PRESERVED LEGACY DEFECT` — reproduced deliberately |
| **Severity** | Medium |
| **Source evidence** | `app/cbl/COUSR03C.cbl`, 359 lines. The proof is a census rather than an impression: **`CDEMO-USER-ID` — the signed-on identifier — appears zero times in the entire program.** The target identifier is taken from the screen field into `SEC-USR-ID` at [:L160] and [:L189], the record is read for update at [:L273-L275], and `EXEC CICS DELETE` is issued at [:L307-L311]. No comparison against the signed-on user exists anywhere, because the program never reads that value |
| **Target** | `src/main/java/com/cardemo/service/admin/UserDeleteService.java` — read, confirm, delete, with **no** comparison against the token subject |
| **Decision** | **Add nothing.** An administrator can delete their own account, exactly as in the source |
| **Alternatives considered** | **(a) Add a self-delete guard** — rejected: parity is the contract, and the guard would refuse an operation the source performs. It is also the kind of addition that looks unarguable and silently changes an administrative workflow. Contrast [DL-DV-05](#dl-dv-05), where an addition *was* made — the difference is that there the transport change removed an environmental control, whereas here nothing about HTTP makes self-deletion newly reachable |
| **Observable consequence** | The last remaining administrator can delete themselves and lock the system out of user administration |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/UserDeleteServiceTest.java`; the absence is asserted rather than assumed, and the wording is carried in `src/test/java/com/cardemo/unit/model/UserSecurityDtoTest.java`. Recorded as `Q-3` in `docs/validation-gates.md` |
| **Remediation / follow-up** | **Mitigate operationally**, not in code: keep more than one administrator account, and apply the guard client-side if a caller needs one. Owner: operations |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-ld-07"></a>

### DL-LD-07 — A redundant index assignment in the statement program is preserved

| Field | Content |
|---|---|
| **Classification** | `PRESERVED LEGACY DEFECT` — reproduced deliberately |
| **Severity** | Low |
| **Source evidence** | The mainline sets the outer table index immediately before the paragraph that re-initialises it: `MOVE 1 TO CR-JMP` at [app/cbl/CBSTM03A.CBL:L324], then `PERFORM 4000-TRNXFILE-GET` at [:L326] — and that paragraph opens with `PERFORM VARYING CR-JMP FROM 1 BY 1` at [:L417], which sets the same index to 1 again. The assignment at [:L324] is provably redundant. The field is declared at [:L62] |
| **Target** | `src/main/java/com/cardemo/batch/processors/StatementProcessor.java` retains the `crJmp` and `trJmp` fields and the assignment, under the paragraph-correspondence rule of [DL-MS-02](#dl-ms-02) |
| **Decision** | Preserve the assignment, cited, rather than optimising it away |
| **Alternatives considered** | **(a) Delete it** — the effect is provably nil, so deletion is behaviour-neutral. Rejected on traceability grounds only: the paragraph map is asserted mechanically, and a missing statement in a mapped paragraph is a coverage gap. This is the weakest case among the retained artefacts and is recorded as such |
| **Observable consequence** | None whatsoever |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/StatementProcessorTest.java`. Gate-level: [Gate 7](docs/validation-gates.md#gate-7); its result is recorded in that ledger, not here |
| **Remediation / follow-up** | If the coverage gate is ever satisfied by a mechanism that does not require statement-level correspondence, this is the first artefact that may be dropped; owner: the traceability deliverable |
| **Verification** | `Source-verified`, `Target-verified` — 30 July 2026 |

<a id="dl-ld-08"></a>

### DL-LD-08 — Source misspellings that are field or literal contracts are preserved as-is

| Field | Content |
|---|---|
| **Classification** | `PRESERVED LEGACY DEFECT` |
| **Severity** | Low, except where a field name is a contract, where it is Medium |
| **Source evidence** | Four distinct cases, each verified: <br>**(i) `ACCT-EXPIRAION-DATE`** — the account expiry field is misspelled in the copybook and used under that spelling in both programs that read it, [app/cbl/CBTRN02C.cbl:L414] and [app/cbl/COACTUPC.cbl:L4131-L4133]. <br>**(ii) `WIRTE-JOBSUB-TDQ`** — the queue-write paragraph name, [app/cbl/CORPT00C.cbl:L515]. <br>**(iii) `//OEPNFIL JOB`** — a batch job misspells its own job name, [app/jcl/OPENFIL.jcl:L1]. <br>**(iv) `ACCDATA.PS`** — a likely typo duplicate of `ACCTDATA.PS` in `app/data/EBCDIC/`, retained as reference only |
| **Target** | Field names that are part of a persisted or compared contract keep their meaning under a correctly spelled Java identifier; the misspelling is recorded in the Javadoc citation. Paragraph and job names are not observable and are not reproduced as misspellings |
| **Decision** | Distinguish the two kinds. A **misspelled field name** is a contract and its citation preserves the spelling exactly, so a reader searching the corpus finds it. A **misspelled paragraph or job name** is not observable, so the Java equivalent is spelled correctly and the source spelling is cited |
| **Alternatives considered** | **(a) Reproduce every misspelling in Java identifiers** — rejected: it propagates a defect into new code for no observable gain, and it fights the readability requirement of *Rule 1* clause A. **(b) Silently correct the citations** — rejected: a citation that does not match the corpus text cannot be found by searching it, which defeats the citation's purpose |
| **Observable consequence** | Every `app/...` citation in the tree matches the corpus byte-for-byte, which is what makes the citation-resolution test possible |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/infrastructure/SourceCitationResolutionTest.java` verifies that cited paths resolve. Recorded as `L-1` and `D-6` in `docs/validation-gates.md` for case (iii) |
| **Remediation / follow-up** | None |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 30 July 2026 |

---

<a id="dl-ld-09"></a>

### DL-LD-09 — The interest job's end-of-file account flush is unreachable, so the last account's interest is lost

| Field | Content |
|---|---|
| **Classification** | `PRESERVED LEGACY DEFECT` |
| **Severity** | **High** — it silently loses money for one account on every run |
| **Source evidence** | The control-break loop is `PERFORM UNTIL END-OF-FILE = 'Y'` at [app/cbl/CBACT04C.cbl:L188], and the flush is the `ELSE` arm of the loop's *own* body at [app/cbl/CBACT04C.cbl:L219-L220]: `ELSE PERFORM 1050-UPDATE-ACCOUNT`. `PERFORM UNTIL` evaluates its condition **before** each iteration, so the instant `1000-TCATBALF-GET-NEXT` sets `END-OF-FILE = 'Y'` the loop terminates and control passes to `9000-TCATBALF-CLOSE` at [app/cbl/CBACT04C.cbl:L224]. The `ELSE` arm requires `END-OF-FILE = 'N'` to be entered and `END-OF-FILE = 'Y'` to be selected, which are mutually exclusive: **it can never execute.** The accumulated interest of the final account in key order is therefore never added to its balance and its cycle counters are never reset |
| **Target** | `src/main/java/com/cardemo/batch/processors/InterestCalculationProcessor.java` — `updateAccountAtEndOfFile()`, which reproduces the arm's body faithfully by delegating to `updateAccount()` and is **called from nowhere**, exactly as the source's arm is reached from nowhere |
| **Decision** | **Reproduce the loss, and reproduce it structurally rather than by emptying the method.** The arm keeps its full body so that a reader can see what the source *would* have done, and it keeps its absent call site so that what the source *does* is what runs. The loss is therefore a property of the control flow, not of a hollowed-out method, which is what makes the reading auditable: emptying the body would have hidden the arm's real content behind a design choice of ours |
| **Alternatives considered** | **(a) Call the arm after the step's last item** — rejected: it makes the Java job produce a different account balance from the source for the last account of every run, which is a parity failure on the one measure this migration is contracted to. **(b) Delete the method** — rejected: it breaks the one-to-one paragraph map that [Gate 7](docs/validation-gates.md#gate-7) verifies, and it would erase the evidence that the arm exists in the source at all. **(c) Empty the method and mark it a no-op** — rejected: the arm is not empty in the source, so an empty Java counterpart would misreport it and would make the *unreachability* — the actual defect — invisible. **(d) Call it behind a configuration flag** — rejected: a flag that changes financial output is two behaviours, only one of which is ever tested, and the requirements admit no such switch |
| **Rationale** | This is the sharpest case in the register of *documenting rather than repairing*: the defect is unambiguous, its cost is money, and it would be one line to fix. It is not fixed because `app/` is the parity oracle and the contract is equivalence, not improvement. Recording it here is what turns a silent loss into a known, owned one |
| **Observable consequence** | For a run whose category-balance file ends on account *A*, account *A*'s interest for that cycle is not posted and its cycle counters are not reset — which, per [DL-PP-07](#dl-pp-07), also leaves the next cycle's over-limit arithmetic running against stale accumulators for that one account. The generated interest transaction for *A* **is** still written, because that happens per category inside the loop; only the account update is lost |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/InterestCalculationProcessorTest.java` invokes the arm reflectively — its only caller anywhere — and asserts that it still performs the save, the interest posting and both cycle resets, which is what distinguishes a faithful reproduction from abandoned residue; `src/test/java/com/cardemo/integration/batch/InterestCalculationJobTest.java` exercises the job end to end. Traceability: `TM-CBACT04C-R008` and its fidelity note |
| **Remediation / follow-up** | None while parity is the contract. Were parity ever relaxed, the change is one statement — move the flush after `END-PERFORM` — and it would have to be labelled a deviation here first; owner: whoever proposes it |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 6 August 2026 |

---

<a id="dl-ld-10"></a>

### DL-LD-10 — The report emits N−1 `Account Total` lines, and none at all for a single card

| Field | Content |
|---|---|
| **Classification** | `PRESERVED LEGACY DEFECT` |
| **Severity** | Medium |
| **Source evidence** | `1120-WRITE-ACCOUNT-TOTALS` is performed **only** when the card number changes and only when this is not the first record — [app/cbl/CBTRN03C.cbl:L182-L186] — and the end-of-data arm at [app/cbl/CBTRN03C.cbl:L197-L203] writes the page total and the grand total but **not** an account total. There is no flush after the loop either: [app/cbl/CBTRN03C.cbl:L208-L213] is six `CLOSE` performs. So for *N* distinct card numbers the report carries *N−1* `Account Total` lines, and for *N = 1* it carries none |
| **Target** | `src/main/java/com/cardemo/batch/processors/TransactionReportProcessor.java` — the control-break and end-of-data paths reproduce both counts exactly |
| **Decision** | **Reproduce the count.** The emitted report is compared line for line, so the missing final total is part of the expected output |
| **Alternatives considered** | **(a) Emit the final account total** — rejected: it adds a line the source does not emit, which fails the line-for-line comparison and changes a published report's shape. **(b) Emit it only when *N* > 1** — rejected: it invents a rule the source does not contain and would still add a line |
| **Rationale** | The omission follows from the same structure as [DL-LD-09](#dl-ld-09) — a control-break program with no post-loop flush — and it is preserved for the same reason. It is recorded separately from [DL-LD-05](#dl-ld-05) because that entry is about the *label* on the line and this one is about *how many* lines there are |
| **Observable consequence** | A reader totalling the `Account Total` lines finds one fewer than there are cards, and a single-card report has none. The grand total is unaffected by this defect, though it is affected by [DL-LD-11](#dl-ld-11) |
| **Test / gate evidence** | `src/test/java/com/cardemo/integration/batch/TransactionReportJobTest.java` — `threeDistinctCardsEmitOnlyTwoAccountTotalLines` and `aSingleCardEmitsNoAccountTotalLineAtAll`. Traceability: `TM-CBTRN03C-R006` |
| **Remediation / follow-up** | None while parity is the contract |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 6 August 2026 |

---

<a id="dl-ld-11"></a>

### DL-LD-11 — At end of data the last record's amount is added to the totals a second time

| Field | Content |
|---|---|
| **Classification** | `PRESERVED LEGACY DEFECT` |
| **Severity** | Medium |
| **Source evidence** | The end-of-data arm at [app/cbl/CBTRN03C.cbl:L197-L203] runs `ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL` before writing the page and grand totals. `TRAN-AMT` at that moment still holds the **last successfully read record's** amount, because a read that hits end of file leaves the record area untouched — and that amount was already added by `1100-WRITE-TRANSACTION-REPORT` on the iteration that read it. The final page total and the grand total therefore each carry the last record's amount **twice** |
| **Target** | `src/main/java/com/cardemo/batch/processors/TransactionReportProcessor.java` — the end-of-data path adds the retained amount a second time, deliberately |
| **Decision** | **Reproduce the double count.** The report's totals are compared byte-for-byte against the legacy layout, so the arithmetic the source publishes is the expected arithmetic |
| **Alternatives considered** | **(a) Add only once** — rejected: the totals would differ from the source's for every non-empty report, which is the most visible number on the page. **(b) Reproduce it but annotate the report** — rejected: the report is a fixed 133-byte layout with no room for an annotation, and adding one changes the record geometry |
| **Rationale** | The defect is a direct consequence of COBOL record-area semantics, which have no Java equivalent: in Java the reader simply returns no item, so the "retained last record" has to be modelled explicitly for the second addition to happen at all. Reproducing it is therefore *active* work, which is exactly why it needs an entry rather than a comment |
| **Observable consequence** | Every non-empty report's grand total and last page total are overstated by the final record's amount. An empty in-window set is unaffected, and that case is asserted separately |
| **Test / gate evidence** | `src/test/java/com/cardemo/integration/batch/TransactionReportJobTest.java` — `theLastAmountIsDoubleCountedIntoThePageAndGrandTotals`, with `anEmptyInWindowSetStillEmitsTheClosingBlock` covering the empty case. Traceability: `TM-CBTRN03C-R005` and `TM-CBTRN03C-R007` |
| **Remediation / follow-up** | None while parity is the contract |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 6 August 2026 |

---

<a id="dl-ld-12"></a>

### DL-LD-12 — `NEXT SENTENCE` ends the report's read loop at the first out-of-window record

| Field | Content |
|---|---|
| **Classification** | `PRESERVED LEGACY DEFECT` |
| **Severity** | **High** — it silently truncates the report |
| **Source evidence** | The in-window test at [app/cbl/CBTRN03C.cbl:L173-L178] reads `IF TRAN-PROC-TS (1:10) >= WS-START-DATE AND <= WS-END-DATE / CONTINUE / ELSE / NEXT SENTENCE / END-IF`. `NEXT SENTENCE` transfers control to the statement following the **next period**, and the next period in this paragraph terminates the whole `PERFORM UNTIL … END-PERFORM` at [app/cbl/CBTRN03C.cbl:L206]. An out-of-window record therefore does not merely skip its own detail line — **it abandons the entire read loop**, so every remaining record, in-window or not, is dropped and the closing block runs immediately. The pairing is deliberate-looking and misleading: `CONTINUE` is a true no-op that falls through, while `NEXT SENTENCE` is a jump |
| **Target** | `src/main/java/com/cardemo/batch/processors/TransactionReportProcessor.java` — the out-of-window branch terminates the step's read rather than filtering the record |
| **Decision** | **Reproduce the termination.** The sort step upstream ([app/proc/TRANREPT.prc] `STEP05R`) applies the same date filter, so in a normal run no out-of-window record ever reaches the program and the path is latent. It is reproduced because a record *can* reach it — the filter is applied at two layers and the second is this one |
| **Alternatives considered** | **(a) Treat it as a per-record skip**, which is what the code reads like at a glance — rejected: it is not what `NEXT SENTENCE` does, and a Java implementation that skipped would emit records the source drops. **(b) Rely on the sort filter alone and omit the second layer** — rejected: the source applies the test twice, and omitting it would remove a reachable branch from the paragraph map |
| **Rationale** | This is the one entry in the register whose defect is a *language* subtlety rather than a logic slip, and it is the reason the two filter layers are asserted to agree record by record: if they ever disagreed, this branch would truncate a report and nothing else would signal it |
| **Observable consequence** | A single out-of-window record downstream of the sort truncates the report at that point, with the closing block still emitted so the output looks complete. The two-layer agreement assertion is what makes the truncation detectable in test rather than in production |
| **Test / gate evidence** | `src/test/java/com/cardemo/integration/batch/TransactionReportJobTest.java` — `anOutOfWindowRecordDownstreamOfTheSortTerminatesTheReadLoop`, with `bothFilterLayersAgreeOnEveryRecord` covering the normal case. Traceability: `TM-CBTRN03C-R001` and `TM-CBTRN03C-R003` |
| **Remediation / follow-up** | None while parity is the contract |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 6 August 2026 |

---

<a id="dl-ld-13"></a>

### DL-LD-13 — A customer-lock failure is reported to the operator as success over an empty write

| Field | Content |
|---|---|
| **Classification** | `PRESERVED LEGACY DEFECT` |
| **Severity** | **High** — the operator is told an update succeeded when nothing was written |
| **Source evidence** | Two locations, and the defect is the gap between them. `9600-WRITE-PROCESSING` reads the customer record for update at [app/cbl/COACTUPC.cbl:L3920-L3929] and, on any non-`NORMAL` response, sets `INPUT-ERROR`, sets `COULD-NOT-LOCK-CUST-FOR-UPDATE` when the message latch is off, and leaves through `GO TO 9600-WRITE-PROCESSING-EXIT` at [app/cbl/COACTUPC.cbl:L3933-L3941] — **before either `REWRITE`**, so nothing has been written. The decider that classifies the outcome at [app/cbl/COACTUPC.cbl:L2606-L2615] then tests `COULD-NOT-LOCK-ACCT-FOR-UPDATE`, `LOCKED-BUT-UPDATE-FAILED` and `DATA-WAS-CHANGED-BEFORE-UPDATE` — and **has no arm for the customer flag at all**. It falls to `WHEN OTHER`, which sets `ACUP-CHANGES-OKAYED-AND-DONE`. The flag is set at exactly one place and tested at none |
| **Target** | `src/main/java/com/cardemo/service/account/AccountUpdateService.java` — the guard sets `customerLockFailed` and retains the typed failure; `classifyWriteOutcome2606` deliberately does **not** test it |
| **Decision** | **Preserve the screen outcome exactly, and make the typed failure accurate.** The legacy pseudo-conversational turn still reports `CHANGES_OKAYED_AND_DONE` with the success information message, because that is what a reachable input receives from the source. Separately, the retained typed failure carries `ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_CUSTOMER`, which the REST write entry point rethrows and the controller maps to `409 Conflict` with the source's own literal. **Nothing about the legacy turn changes; only the outcome that was already declared for the REST surface becomes reachable** |
| **Alternatives considered** | **(a) Add the missing arm** — rejected: it is a one-line repair that changes the response a real, reachable input receives, which is a parity failure. Were parity relaxed, the change is precisely one `WHEN COULD-NOT-LOCK-CUST-FOR-UPDATE / SET ACUP-CHANGES-OKAYED-LOCK-ERROR` inserted between [app/cbl/COACTUPC.cbl:L2607] and [:L2609]. **(b) Report the failure on both surfaces** — rejected for the legacy surface for the same reason; the REST surface is not the legacy surface and is where the accurate outcome belongs. **(c) Drop the unused flag** — rejected: it is declared and assigned in the source, and dropping it would hide the defect rather than record it |
| **Rationale** | The two surfaces answer different questions. The pseudo-conversational turn answers *what would the 3270 screen have shown*, and the honest answer is the false success. The REST write entry point answers *did the write happen*, and answering that with success would be a new defect rather than a preserved one. Splitting them is what lets both answers be true at once |
| **Observable consequence** | Through `processRequest` the caller sees the success marker, the success information message, **and** the customer-lock literal in the error field — the diagnostic reaches the message even though the outcome does not. Through `updateAccount` the caller receives a `409` naming the lock failure. No row is saved and no flush occurs on either path |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/AccountUpdateServiceTest.java` — `customerLockFailureSetsTheFlagAndIsStillReportedAsSuccess` asserts the false success and `customerLockFailurePersistsNeitherRow` asserts the empty write; the REST refusal is exercised in `src/test/java/com/cardemo/e2e/OnlineTransactionE2ETest.java`. Traceability: the defect spans two rows and both carry it &mdash; `TM-COACTUPC-R080` for the guard that sets the flag and `TM-COACTUPC-R044` for the decider that has no arm for it |
| **Remediation / follow-up** | None while parity is the contract. The REST mapping means an integrating caller is not misled, which is the mitigation available without changing the legacy turn |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 6 August 2026 |

---

<a id="conflicts"></a>

## 7. Conflict resolutions

Eleven places where two binding requirements collided. In each, both requirements are
stated, one is chosen, and the ground for choosing is given. None is left implicit.

<a id="dl-cr-01"></a>

### DL-CR-01 — No-dead-code versus one-to-one control-flow fidelity

| Field | Content |
|---|---|
| **Classification** | `CONFLICT RESOLUTION` |
| **Severity** | Medium |
| **The two requirements** | *Rule 1: Build Verify* clause B requires no dead code and no deferred work without an owner or tracking reference. The migration requirements mandate preserving control flow one-to-one so that paragraph-level traceability is mechanically provable |
| **Where they collide** | At specific, individually registered sites, **every one of which is enumerated in [§15](#noop-registry)** — the parity no-op registry, whose membership rule is mechanical, whose rows are keyed by identity rather than by ordinal, and which declares **no total** because the harness derives one. Two earlier closures of this cell are **withdrawn**: *"Three specific artefacts, and no others"*, and the three-identifier list that replaced it. Neither was wrong about the three sites named; both were wrong to read as the whole set, because the same collision recurs at every method the frozen source reaches and does nothing in.<br><br>Three sites are called out individually because they are the ones the clause and the mandate are *argued* over, and each has its own entry:<br>**`NOOP-CBACT04C-1400`** — the empty but reachable `1400-COMPUTE-FEES` at [app/cbl/CBACT04C.cbl:L518-L520], performed from [:L216] — [DL-PP-05](#dl-pp-05); its Java leg is the registry row `NOOP-InterestCalculationProcessor-computeFees`.<br>**`NOOP-CBTRN02C-109`** — reject code 109, assigned at [app/cbl/CBTRN02C.cbl:L556] inside the paragraph at [:L545-L560] and cleared at [:L208], reachable but never observable as a reject outcome — [DL-PP-03](#dl-pp-03).<br>**`NOOP-CBSTM03A-CRJMP`** — the redundant index assignment at [app/cbl/CBSTM03A.CBL:L324], where the mainline sets the outer index before a `VARYING` loop that re-initialises it — [DL-LD-07](#dl-ld-07).<br><br>The last two are not methods and are registered as such in [§15.5](#noop-nonmethod). `docs/validation-gates.md` [Gate 7](docs/validation-gates.md#gate-7) and `TRACEABILITY_MATRIX.md` §13.2 carry the same three identifiers and defer to [§15](#noop-registry) for the full set, so the registers are one set by construction rather than three lists kept in step by hand |
| **Decision** | **The parity mandate governs, and clause B is satisfied by a different mechanism.** Every registered artefact is retained, cited to its source lines and marked as intentional. **This document is the tracking reference that makes them compliant**, and the *membership criterion* rather than a count is what defines the set: the artefact must be a genuine no-op or unreachable value that the frozen source nevertheless reaches. That criterion is stated mechanically at [§15.1](#noop-membership) and enforced as a two-way equality between the register and the tree at [§15.2](#noop-bijection), so membership is decided by the compiler-visible shape of a method rather than by argument. A preserved asymmetry, a preserved absent guard and a preserved misspelling are documented source *behaviour* at their own locators and do **not** meet the criterion, so they are recorded in [§6](#legacy-defects) instead. A preserved `CONTINUE` **does** meet it — the verb does nothing, and the method that renders it is empty and called — and an earlier revision of this cell wrongly excluded it; that exclusion is withdrawn and the artefact is registered |
| **Alternatives considered** | **(a) Delete every registered artefact** — produces a system that is marginally cleaner and demonstrably less traceable, failing a stated acceptance criterion ([Gate 7](docs/validation-gates.md#gate-7)) to satisfy a stylistic one. **(b) Retain them with bare marker comments** — rejected, and this is the sharp point: a bare `TODO`-style marker would breach clause B **for real**, because the clause forbids deferred work *without an owner or tracking reference*. A cited, tracked, justified artefact does not breach it |
| **Rationale** | Clause B's actual target is **untracked** residue — code nobody owns and nobody can explain. Every registered artefact is owned, cited by identifier and locator, and explained. They are not abandoned residue; they are documented faithful reproductions of source behaviour that exists in the system of record |
| **Observable consequence** | Each registered artefact looks like a defect to a reader who has not read this entry. That is the cost, and it is paid deliberately. The register is the compensation. The harness publishes the derived census into `target/gate-verification/gate-verification-summary.properties` — the *Test / gate evidence* row names the exact properties — and those figures are a **measurement of today's corpus**, never a permanent property. **No figure is quoted here.** An earlier revision quoted `dispositions.justifiedNoOps=3` in this cell, which is how a spot-check total came to be read as the size of the register |
| **Test / gate evidence** | `src/test/java/com/cardemo/e2e/GateVerificationTest.java` asserts a **two-way equality** between the register of [§15.4](#noop-rows) and the census it derives from `src/main/java/com/cardemo/`: an unregistered member fails the build, and so does a row whose method no longer exists. It also re-derives each row's reference status and requires each row to cite an `app/**` locator. The derived figures are written to `target/gate-verification/gate-verification-summary.properties` as `dispositions.registeredParityNoOps`, `dispositions.calledParityNoOps`, `dispositions.unreferencedParityNoOps` and `dispositions.enumeratedNoOpSites`. Each individually argued artefact additionally carries its own test citation in its own entry, and its locator is checked against the frozen corpus line by line. `docs/validation-gates.md` [§14](docs/validation-gates.md#rule-1) records the same conflict from the gate side, and [Gate 7](docs/validation-gates.md#gate-7) carries the three argued identifiers and defers to [§15](#noop-registry) for the set |
| **Remediation / follow-up** | **The set is open by construction, not closed, and it is now complete rather than merely open.** Two earlier revisions of this cell are withdrawn: *"The set is **closed at three**"*, and the wording that replaced it, which withdrew the closure but left only three rows registered against a membership criterion that admits far more. Being open was necessary and was not sufficient — a review found that most admitted members carried a source citation and a rationale but **no individual register entry**, which is exactly the leg clause B requires. [§15](#noop-registry) supplies it for every member, and [§15.2](#noop-bijection) makes the completeness mechanical rather than curated: adding a qualifying method without registering it fails the build. The standing obligation is unchanged — any retained no-op requires its own row, its own identifier, its own locator and its own reason, and the owner is whoever proposes it — but it is now enforced rather than requested |
| **Verification** | `Source-verified`, `Target-verified` — 30 July 2026 |

<a id="dl-cr-02"></a>

### DL-CR-02 — The report generation group's conflicting retention limits, resolved to 10

| Field | Content |
|---|---|
| **Classification** | `CONFLICT RESOLUTION` |
| **Severity** | Medium |
| **The two requirements** | Two jobs in the corpus define the **same** generation-data-group base with **different** retention limits: [app/jcl/DEFGDGB.jcl:L37-L38] declares `NAME(AWS.M2.CARDDEMO.TRANREPT)` with `LIMIT(5)`, while [app/jcl/REPTFILE.jcl:L26-L27] declares the same name with `LIMIT(10)`. Both are in the frozen corpus and both are defensible |
| **Decision** | **Resolved to 10**, the larger value. **This is the only legacy inconsistency this migration actively resolves**; every other one is reported and preserved |
| **Alternatives considered** | **(a) Resolve to 5** — rejected: the smaller value discards report generations that one of the two source definitions intends to keep, and data loss is the worse failure direction. **(b) Preserve both** — impossible: an object-lifecycle rule takes one number. **(c) Apply no rule at all and record neither** — rejected: the conflict would then resurface the first time anyone configures retention, with no recorded decision to appeal to |
| **Rationale** | A single lifecycle value must be chosen if one is ever applied, so the conflict cannot be deferred indefinitely — but the *application* of the rule can be, and is. Object versioning supersedes generation counting for the purpose the GDG limit served |
| **Observable consequence** | **No lifecycle rule is applied by this work** — see [DL-MS-10](#dl-ms-10) and [DL-RR-06](#dl-rr-06). The resolution records which number to use when one is applied. The other six GDG bases each carry a single, unconflicting `LIMIT(5)`, including `DALYREJS` at [app/jcl/DALYREJS.jcl:L25-L26] |
| **Test / gate evidence** | `src/test/java/com/cardemo/integration/aws/S3GenerationKeyIntegrationTest.java`. Recorded as `M-1` and as deviation `V-5` in `docs/validation-gates.md` |
| **Remediation / follow-up** | Apply the rule at deployment time using 10; owner: deployment |
| **Verification** | `Source-verified`, `Target-verified` — 30 July 2026 |

<a id="dl-cr-03"></a>

### DL-CR-03 — Flyway migration filenames: the canonical set, and the alias

| Field | Content |
|---|---|
| **Classification** | `CONFLICT RESOLUTION` |
| **Severity** | Low |
| **The two requirements** | The requirements name three migrations — `V1__create_schema.sql`, `V2__create_indexes.sql`, `V3__seed_data.sql` — while other prose in the project refers to the same three by shorter descriptive names |
| **Target** | The authored files are exactly `src/main/resources/db/migration/V1__create_schema.sql`, `V2__create_indexes.sql` and `V3__seed_data.sql`. Confirmed: the migration directory contains those three files and no others |
| **Decision** | **The required names are canonical.** The short forms are recorded as **aliases** for the same three files. **No duplicate migration is added** under an alternative name |
| **Alternatives considered** | **(a) Add migrations under the short names as well** — rejected emphatically: Flyway would then apply six migrations, the second three failing on objects that already exist, and the schema history would record a set that does not correspond to the tree. This is the failure mode the entry exists to prevent. **(b) Rename the files to the short forms** — rejected: the required names govern. **(c) Leave the naming divergence unrecorded** — rejected: a reader looking for a short-form file finds nothing and may conclude a migration is missing |
| **Rationale** | **Ordering is unaffected by the descriptive tail.** Flyway keys on the version prefix `V1__` / `V2__` / `V3__`, so the tail is documentation. That is precisely why an alias is safe and a duplicate is not |
| **Observable consequence** | Three migrations apply, in order, once. A search for a short-form filename returns nothing, and this entry explains why |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/model/SchemaStructureTest.java`; `src/test/java/com/cardemo/integration/repository/RepositorySchemaAndFinderIntegrationTest.java`. Gate-level: [Gate 8](docs/validation-gates.md#gate-8), whose result that ledger records. Recorded as `M-2` in `docs/validation-gates.md` |
| **Remediation / follow-up** | None outstanding |
| **Verification** | `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-cr-04"></a>

### DL-CR-04 — The coverage plugin version: pin 0.8.12, with a documented Java 25 exception

| Field | Content |
|---|---|
| **Classification** | `CONFLICT RESOLUTION` |
| **Severity** | Medium |
| **The two requirements** | The requirements pin the coverage plugin to **0.8.12**. A prior implementation record cites **0.8.14**. Separately, the toolchain forces a technical constraint that neither number alone satisfies |
| **Target** | `pom.xml`, which carries **three** exact coordinates rather than one: `<jacoco-maven-plugin.version>0.8.12</jacoco-maven-plugin.version>`, `<jacoco.asm.version>9.9</jacoco.asm.version>` and `<jacoco.agent.runtime.version>0.8.14</jacoco.agent.runtime.version>`, each justified in a comment block immediately above them |
| **Decision** | **The pinned 0.8.12 governs the plugin**, and the divergence from the 0.8.14 record is documented rather than resolved by advancing the pin. A **documented Java 25 compatibility exception** supplies an ASM 9.9 bytecode reader and the matching 0.8.14 runtime agent on the plugin classpath |
| **Alternatives considered** | Both forms of alignment were rejected on stated grounds, not on preference. **(a) Raise the plugin pin to 0.8.14** — rejected: it resolves unilaterally the very divergence the requirement says to record. **(b) Lower the agent to 0.8.12** — rejected on measured grounds: that release ships ASM 9.7, whose class-file ceiling is version 67, and this toolchain emits 69, so coverage analysis fails outright. The split is therefore the only configuration that satisfies both the pin and the toolchain |
| **Rationale** | This entry deliberately states the situation as three coordinates rather than as "0.8.12 overrides 0.8.14", because the simpler framing would mislead a reader who then finds 0.8.14 in the build file and concludes the pin was ignored. The ASM ceiling of the pinned release is **validation evidence and a residual risk, not a changed requirement**. No range and no floating version is introduced |
| **Observable consequence** | Coverage runs on Java 25. The build file names two different JaCoCo versions on purpose, and `src/test/java/com/cardemo/unit/infrastructure/BuildProvenanceTest.java` asserts that the pin, the reader and the agent all stay on exactly these three values, so an unexplained drift fails the build |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/infrastructure/BuildProvenanceTest.java`. Gate-level: [Gate 2](docs/validation-gates.md#gate-2), whose result that ledger records. Recorded as `M-3` in `docs/validation-gates.md` |
| **Remediation / follow-up** | Collapse the split to a single coordinate when a JaCoCo release that both satisfies the pin and supports the emitted class-file version exists; owner: build maintenance |
| **Verification** | `Target-verified`, `Test-verified` — 30 July 2026 |

<a id="dl-cr-05"></a>

### DL-CR-05 — Idiomatic-restructuring guidance versus one-to-one paragraph fidelity

| Field | Content |
|---|---|
| **Classification** | `CONFLICT RESOLUTION` |
| **Severity** | Medium |
| **The two requirements** | Established industry guidance for this class of migration warns against literal transliteration that reproduces `GO TO` and `PERFORM` structure in Java, and recommends restructuring into idiomatic object-oriented code. The migration requirements mandate paragraph-level correspondence so that traceability is mechanically provable |
| **Decision** | **The fidelity mandate governs.** Paragraph correspondence is preserved, and the legitimate readability concern behind the guidance is answered by **two compensating mechanisms** rather than by restructuring |
| **Alternatives considered** | **(a) Restructure into cohesive domain objects** — rejected: behavioural parity is the contract, and a restructured control flow cannot be shown to reproduce a 4,236-line program's decision sequence by inspection. **(b) Restructure and rely on tests alone** for parity — rejected: the tests would then be the only evidence, and [Gate 7](docs/validation-gates.md#gate-7) requires a mechanically checkable paragraph map |
| **Rationale** | The two compensating mechanisms are: every private method carries a Javadoc citation naming its source paragraph and lines, and the traceability matrix makes the correspondence navigable. Where the guidance **can** be honoured without touching control flow, it **is**: naming is idiomatic, `BigDecimal` replaces packed decimal, framework mechanisms replace static linkage, and global mutable state is eliminated wholesale |
| **Observable consequence** | The large services are verbose, and a reader unfamiliar with the source sees more methods than a green-field design would have. The trade is deliberate: verbosity in exchange for provable correspondence |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/infrastructure/SourceCitationResolutionTest.java` enforces the citation half. Gate-level: [Gate 7](docs/validation-gates.md#gate-7), whose result that ledger records; the matrix it reads is published at [`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md) — [DL-RR-07](#dl-rr-07) |
| **Remediation / follow-up** | Any future restructuring proposal must first satisfy the coverage gate by another mechanism; owner: whoever proposes it |
| **Verification** | `Source-verified`, `Target-verified` — 30 July 2026 |

---

<a id="dl-cr-06"></a>

### DL-CR-06 — The plan's summary file counts versus the plan's own enumeration, Rule 1 clause E and the REST contract

| Field | Content |
|---|---|
| **Classification** | `CONFLICT RESOLUTION` |
| **Severity** | Medium |
| **The two requirements** | **(1)** The plan's `CREATE` summary table fixes a per-area count for the production tree, and it writes its own total out term by term: `1 + 6 + 3 + 11 + 3 + 4 + 17 + 11 + 21 + 8 + 6 + 5 + 7 + 3 + 9 + 3 + 14 = 132`, being 118 classes and 14 package documents. The tree must match that contract, and the surplus may not be blessed by reinterpreting the plan. **(2)** Two other binding requirements each mandate files that the same table does not count: *Rule 1: Build Verify* **clause E**, which requires every module to carry a docstring, against a table row that budgets 14 for a tree whose own layout section enumerates **24** class-bearing packages; and the plan's REST contract of 8 controllers over 17 JSON operations, which needs a response type per operation while the DTO row counts only the 17 request-and-view shapes derived from the BMS symbolic maps |
| **Where they collide** | In **exactly four of the seventeen areas**, totalling **27** files. The other thirteen areas match the table to the file. `model/dto` 29 against 17 (**+12**); `package-info.java` 26 against 14 (**+12**); `security` 4 against 3 (**+1**); `observability` 5 against 3 (**+2**). Counted per *directory* the picture looks broader than it is — `service` reads 31 against 22 and `batch` 26 against 23 — but **both hold exactly the number of classes the plan specifies**, 21 and 21, and their entire difference is package documentation: 9 further documents under the `service` leaves and 3 under `batch`, which is precisely the 12 of group (c) and not a separate surplus. That localisation is asserted, so the 12 cannot quietly spread to a region the register does not name. The DTO figure is 17 and not 16: the plan records 16 as a **withdrawn** figure, stating that "the previous figures — 16 DTOs and 131 files — survived alongside a by-name enumeration of 17", which is why the arithmetic here is against 17 and 132. That correction matters to this entry rather than merely tidying it, because 29 − 17 = 12 is exactly the number of additional DTO files named below, whereas the withdrawn 16 would leave one addition with no name attached to it |
| **Decision** | **The divergence is registered and asserted, never absorbed.** All 27 files are retained and sanctioned in four groups, each on its own ground: **(a) 11 controller response types** — `AccountViewResponse`, `AccountUpdateResponse`, `CardResponse`, `CardListResponse`, `TransactionResponse`, `TransactionListResponse`, `BillPaymentResponse`, `ReportSubmissionResponse`, `UserCreateResponse`, `UserUpdateResponse`, `UserListResponse` — every one the type argument of a `ResponseEntity<…>` returned by a controller method; **(b) `ApiMasking`**, the credential-masking helper 9 DTOs and one controller depend on, which discharges clause D's log-hygiene requirement in one place instead of nine; **(c) 12 further `package-info.java`**, giving all **24** class-bearing packages a docstring with **zero** undocumented, plus the two structural packages `service` and `batch`; **(d) 3 production types**, `SnapshotTokenService` (the sealing mechanism the stateless paging and record-handle contract of [DL-DV-08](#dl-dv-08) requires, referenced from 5 further main-tree files), `TemplatedUriObservationConvention` (referenced from `ObservabilityConfig`) and `BatchJobSpanNamingConvention` (added to close QA finding B-12: the framework hands the ALL-CAPS job name to Micrometer Tracing, whose `SpanNameUtil.toLowerHyphen` hyphenates every upper-case character, so `POSTTRAN` reached the trace store as `p-o-s-t-t-r-a-n` and `CARDDEMO-PIPELINE` as `c-a-r-d-d-e-m-o--p-i-p-e-l-i-n-e`, findable under no name an operator would search for. `DefaultBatchJobObservationConvention` does not override `getContextualName`, so the correction has nowhere else to live; it cannot fold into `ObservabilityConfig` for the same reason `TemplatedUriObservationConvention` cannot, and it cannot be a `@Component` because Spring Batch resolves no convention bean — each of the 7 job builders registers it explicitly, exactly as each registers its own `jobInstanceId` listener). No file is retained that is not load-bearing: the one genuine duplicate found while auditing — a second rendition of the correlation-filter suite — had its 4 unique assertions ported into the survivor and was then deleted |
| **Alternatives considered** | **(a) Delete the surplus to reach 132** — rejected, and this is the decisive one: 11 of the 26 are controller return types, so deleting them deletes the 17-operation REST surface the plan mandates, and 12 are the docstrings clause E requires. The deletion that satisfies the summary count breaks two requirements of higher standing. **(b) Fold the response types back into the 17 request DTOs** — rejected: a request shape and a response shape that share a class cannot be validated or masked independently, and the BMS field contracts the 17 carry are input-field contracts. **(c) Fold `TemplatedUriObservationConvention` into the plan-named `ObservabilityConfig`** — investigated and rejected on the tree's own evidence: that class documents *and* tests that exactly one `@Bean` method is declared, and states that the convention needs no `@Bean` method, so folding would fight a documented, tested design to satisfy a count. **(d) Re-state the plan's count as 158 and call the contract met** — rejected outright: that is exactly the reinterpretation the review forbids, and it would leave the next reader with a figure whose denominator is unstated |
| **Rationale** | The plan itself establishes the precedent for additive-with-written-justification twice, and both times the addition is the 21st or 4th of a set the summary sizes smaller: `FileService` is admitted as the **21st** service where the requirements mandate 20, because the `CALL 'CBSTM03B'` indirection has no home in the other twenty; `application-prod.yml` is admitted as the **4th** profile where the prompt names three, because clause D requires a least-privilege production configuration. Both are recorded rather than hidden. This entry follows that precedent for the remaining four groups. What makes it a resolution rather than an excuse is the direction of the fix: the count is not restated to match the tree — the tree is stated in full, each addition is named and grounded, and the gate is changed to assert **the schema minimum plus this register's additions** so the arithmetic is checked on every build |
| **Observable consequence** | A reviewer counting `src/main/java` finds 158 files, not 132, and finds this entry accounting for all 26. Nothing in the running system differs: no endpoint, payload, column or exit code changes. The plan's summary table is **not adjusted to match the tree** — the divergence this entry registers is left visible, so a reader comparing the table against `src/main/java` sees it and is pointed here rather than left to guess. **An earlier reading of this sentence went further and is withdrawn**: it said the table *"is not edited"*, which is not true of the table's history. Commit `76bd45ed` — an ancestor of this branch's review baseline — edited that very table, raising the `model/dto/**` row from 16 to **17** and the stated rollup from 131 to **132**, and added the term-by-term sum that makes the total checkable. **What that edit did and did not do is the whole distinction.** It corrected an internal contradiction inside the plan, where the summary table said 16 DTOs while the by-name enumeration of [§0.4.1.2](docs/technical-specifications.md) listed 17; it did **not** move the figure towards the tree, which holds 158 files against a rollup of 132, so the divergence registered here is unchanged in kind and one file smaller in size. Editing that document is sanctioned in any case — [§0.3.1.6](docs/technical-specifications.md) names it as one of exactly three files this migration updates — so the accurate claim is the narrow one: the summary figures are not rewritten to whatever the tree happens to contain |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/infrastructure/InventoryCountGateTest.java` asserts the sanctioned inventory area by area and re-derives the total from the parts. `src/test/java/com/cardemo/unit/model/PackageDocumentationInventoryTest.java` proves zero class-bearing packages lack a docstring, which is the ground for group (c) and is not restated in the inventory gate. `src/test/java/com/cardemo/unit/infrastructure/DocumentationConsistencyTest.java` — nested `JavaCensusIsMeasured` re-measures the published census on every build, and was written because one of those figures had already gone stale. Gate-level: [Gate 7](docs/validation-gates.md#gate-7), whose `gate7.inventoryDivergence` record publishes the same remediation this entry performs |
| **Remediation / follow-up** | Any future addition to the production tree must either fall inside one of the four sanctioned groups or be added to this entry in the same change; the inventory gate fails otherwise. Owner: whoever adds the file. If the plan is ever reopened, the one internal inconsistency it still carries — a package-document budget of 14 against its own enumeration of 24 class-bearing packages — should be corrected at the source, which would retire group (c) from this entry |
| **Verification** | `Target-verified` — every figure measured against the working tree on 7 August 2026. The plan's seventeen summary rows, its written-out sum and its withdrawal of the earlier 16-and-131 figures were read from `docs/technical-specifications.md` §0.3.1.1 as it now stands, **not** at `7756d89`, where no `src/` tree and no such rollup existed |

---

<a id="dl-cr-07"></a>

### DL-CR-07 — An enumerated test-path list versus the plan's pattern-based test contract and its coverage objective

| Field | Content |
|---|---|
| **Classification** | `CONFLICT RESOLUTION` |
| **Severity** | Medium |
| **The two requirements** | **(1)** The exact-inventory contract enumerates test paths, and a review of that contract reports a required test-file count of **96** against a delivered **264**, asking that unsanctioned paths be removed. **(2)** The plan's own test schema fixes no file count at all. It is **four trailing-wildcard patterns** — `src/test/java/com/cardemo/unit/**`, `…/integration/**`, `…/e2e/**` and `src/test/resources/**` — described by *content* rather than by number, and it names exactly **three** test classes anywhere: `BatchPipelineE2ETest`, `OnlineTransactionE2ETest` and `GateVerificationTest`. Alongside it sits the ≥80% line-coverage objective and *Rule 1: Build Verify* **clause B**, which requires tests for core logic |
| **Where they collide** | Only in the reading of the contract, not in the tree. The production schema publishes seventeen exact per-area counts **and writes its own sum out term by term**, so a file beyond it is a measurable divergence — which is why [DL-CR-06](#dl-cr-06) exists. The test schema publishes **no count to diverge from**: a wildcard is open by construction, and the plan's wildcard policy states that trailing wildcards "expand downward". The figure of 96 is therefore a count of paths that happened to be enumerated, not a ceiling the plan asserts |
| **Decision** | **The plan's pattern-based contract governs, and conformance to it is asserted mechanically instead of a file count being asserted.** Three things are checked on every build rather than assumed: every one of the **264** test files lies under one of the three sanctioned package roots, with **zero** outside them (measured: `unit` 219, `integration` 40, `e2e` 5); the `e2e` package holds **exactly** the three plan-named suite classes beside the two Gate 1 oracles, neither of which declares a test method, which is an exact match to the one place the plan does enumerate test files by name; and the coverage objective is met with margin. No test file is retained merely because it exists: a same-named pair whose assertions duplicate rather than complement each other is consolidated rather than kept |
| **Alternatives considered** | **(a) Delete about 168 test files to reach 96** — rejected: it would destroy assertions no other file makes, and it satisfies a figure the plan never published by breaking two requirements it did. Each same-named candidate pair was examined by its **assertions** rather than its name, and six of the seven proved complementary rather than duplicated: two independent test tiers over the same subject, a basic state machine beside a hardening suite, and a copybook data contract beside a bean-behaviour contract. Only one pair was a true duplicate. **(b) Keep the files and assert a total** — rejected for the same reason a bare production total was rejected: a figure that moves with every file added goes stale first, which is why the total above is published as a dated measurement with the command that reproduces it. **(c) Treat the 96 as binding and record the gap as a residual risk** — rejected: that would concede a contract breach where there is none, and a register that reports non-existent breaches is as misleading as one that hides real ones |
| **Rationale** | The two schemas are written differently because they constrain differently. A production tree with an exact count and a written-out sum is a closed enumeration; a test tree given as four wildcards with content descriptions is an open one, and the plan's own wildcard policy says so. What can be verified about an open set is **membership and sufficiency**, which is exactly what is asserted: every file is inside a sanctioned pattern, the named classes are present and are the only occupants of their package, and coverage clears its floor. Asserting a count instead would be asserting the one property the plan declined to fix |
| **Observable consequence** | A reviewer counting `src/test/java` finds 264 files against an enumerated 96 and finds this entry stating why the delivered tree is nonetheless conformant. Nothing in the running system differs. The distinction from [DL-CR-06](#dl-cr-06) is deliberate and load-bearing: there, a published count is diverged from and each addition is individually sanctioned; here, no count was published and conformance is to the pattern |
| **Test / gate evidence** | `src/test/java/com/cardemo/e2e/GateVerificationTest.java` — `theTestTierConformsToThePatternContractRatherThanToAFileCount` asserts pattern membership, the exact `e2e` occupancy and the absence of any file outside the three roots. `src/test/java/com/cardemo/unit/infrastructure/DocumentationConsistencyTest.java` — nested `JavaCensusIsMeasured` keeps the published test-file census measured rather than authored, which is what caught the stale 256. Coverage is enforced by the build's own JaCoCo `BUNDLE` rule at a 0.80 line floor. Gate-level: [Gate 7](docs/validation-gates.md#gate-7) |
| **Remediation / follow-up** | If a future revision of the plan publishes an exact test-file count with its arithmetic written out, as the production table does, this entry is void and the tree must be reconciled to that count file by file. Owner: whoever publishes the count. Until then, a new test file needs no entry here provided it lies under a sanctioned pattern; a new test **package** outside the three roots fails the gate above |
| **Verification** | `Target-verified` — measured on 7 August 2026: **264** files, **219 / 40 / 5** across the three roots, **0** outside them, and `e2e` holding exactly the three named suites beside the two Gate 1 oracles, neither of which declares a test method. The plan's test schema publishes four trailing wildcards and three named end-to-end classes, and no file count anywhere |

<a id="dl-cr-08"></a>

### DL-CR-08 — `SERIALIZABLE` job-execution creation versus the parallel split, resolved by a bounded launch retry

| Field | Content |
|---|---|
| **Classification** | `CONFLICT RESOLUTION` |
| **Severity** | High |
| **The two requirements** | **(a)** `spring.batch.jdbc.isolation-level-for-create` must stay `SERIALIZABLE`: it is the only mechanism that stops two launches of one job instance from both believing they created it, which is what makes the framework's refusal of a repeated run trustworthy. **(b)** The two branches of the stage 4 split must run **in parallel**: `app/jcl/CREASTMT.JCL` and `app/proc/TRANREPT.prc` have no ordering dependency, so `FlowBuilder.split()` models them faithfully and serialising them would stop the split being exercised at all |
| **The collision** | Under `SERIALIZABLE`, PostgreSQL is entitled to abort one of two **genuinely distinct** concurrent job-execution creations with SQLSTATE `40001`, and the split creates two at the same instant by design. The observable consequence was that one branch of stage 4 failed for a reason unrelated to the batch data, non-deterministically, and only ever in the parallel half of the stream |
| **Target** | `src/main/java/com/cardemo/batch/jobs/BatchPipelineOrchestrator.java` — `launchStage` is now a bounded retry loop over a new `launchStageOnce`, with `transientConflictState` and `backOffBeforeRelaunch` |
| **Decision** | **Retry the launch, bounded, and change neither requirement.** Five attempts counting the first, with a growing backoff of 50, 100, 150 and 200 milliseconds. The retry is confined to the **creation phase by construction** rather than by inspecting a message: `JobLauncher.run` creates the execution row and only then hands control to the job, and once the job is running a step failure is recorded *on the returned execution* rather than thrown — so a transient data-access failure escaping `run` cannot have come from a step, and retrying it repeats no business work and re-posts nothing |
| **Alternatives considered** | **(a) Lower `isolation-level-for-create`** — rejected: it is the guard against a double launch of one instance, so weakening it trades a visible flake for an invisible correctness hole. **(b) Serialise the two branches** — rejected: the parallelism is the behaviour under test, and removing it would make the split unexercised. **(c) Accept the flake and assert less** — rejected, and this is what the earlier revision did: `BatchPipelineOrchestratorTest` test 8 declined to assert branch completion on the recorded grounds that it "would fail roughly two runs in three". Declining an assertion because production is unreliable records the defect instead of fixing it. **(d) Match on the Spring exception class instead of the SQLSTATE** — rejected on precision: every non-deprecated candidate supertype, `PessimisticLockingFailureException` and `ConcurrencyFailureException`, also covers `OptimisticLockingFailureException`, which must never be retried here; and the two specific subtypes that would have been exact, `CannotSerializeTransactionException` and `DeadlockLoserDataAccessException`, are deprecated in this framework line and would fail the zero-warning build |
| **Rationale** | The decision rests on the phase distinction, not on a retry budget. Retrying an operation that may have partially applied would be unsafe at any bound; retrying one that provably did nothing is safe at any bound, and the bound exists only so that **persistent** contention is reported rather than spun on. Matching on SQLSTATE `40001` and `40P01` names exactly the two class-40 aborts a fresh snapshot can clear and nothing else, and the walk **fails closed**: a transient failure whose SQLSTATE cannot be found is treated as fatal rather than retried on suspicion |
| **Observable consequence** | The five-stage stream completes. A retried launch logs `PIPELINE STAGE <name> LAUNCH DID NOT SERIALIZE - SQLSTATE 40001, attempt n of 5` at `WARN` with the reason it is safe to retry, so the contention stays visible rather than being silently absorbed. Exhausting the bound raises a `FatalProcessingException` whose reason is `STAGE LAUNCH DID NOT SERIALIZE`, distinct from the generic `STAGE LAUNCH FAILED` |
| **Test / gate evidence** | `src/test/java/com/cardemo/integration/batch/SuccessfulPipelineRunTest.java` is the whole-stream proof and is the only place in the tree where all five stages reach a successful end. It is causally verified: on the recorded passing run the log carries `DID NOT SERIALIZE - SQLSTATE 40001, attempt 1 of 5`, so the retry fired and is load bearing; and with the bound reduced to a single attempt the same run ends `status FAILED, exit ABEND` and both of that suite's tests fail. `src/test/java/com/cardemo/integration/batch/BatchPipelineOrchestratorTest.java` test 8 now additionally refuses a `FAILED` branch, which the earlier revision could not. Gate-level: [Gate 1](docs/validation-gates.md#gate-1) |
| **Remediation / follow-up** | None outstanding. Should a future framework line expose a non-deprecated, precisely-scoped serialization-failure type, the SQLSTATE walk may be replaced by it; owner: build maintenance |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` |

<a id="dl-cr-09"></a>

### DL-CR-09 — The vulnerability scanner version: the pinned 12.1.0 governs, and the distance from upstream is disclosed rather than closed

| Field | Content |
|---|---|
| **Classification** | `CONFLICT RESOLUTION` |
| **Severity** | Medium |
| **The two requirements** | **(a)** The requirements pin the vulnerability scanner to an exact coordinate — `docs/technical-specifications.md` §0.6.1.1 lists `org.owasp:dependency-check-maven` at **12.1.0** — and §0.8.4 states that pinned versions are honoured as given, with any divergence *recorded rather than resolved unilaterally*. **(b)** Rule 1 clause D requires dependencies to be pinned **and risky patterns flagged**; running a security scanner well behind its upstream line is precisely such a pattern, and leaving it unflagged is the clause's own failure mode |
| **The collision** | The two requirements pull in opposite directions on the *same* coordinate. Clause D's instinct is to advance the scanner, because a scanner is the one dependency whose value decays with age. §0.8.4 forbids advancing it here, because this pin is an instruction, not a default. Neither requirement can be satisfied by editing the version: raising it breaches (a), and leaving it silent breaches (b) |
| **Target** | `pom.xml` — `<dependency-check-maven.version>12.1.0</dependency-check-maven.version>` at L477, unchanged. This entry, which supplies the flag clause D asks for. `src/test/java/com/cardemo/unit/infrastructure/BuildProvenanceTest.java` — nested `SuppressionPremises`, which now asserts the pin and the existence of this entry together, so the pin cannot move silently |
| **Decision** | **Keep 12.1.0 and flag the distance in writing; require owner approval before any move.** The pin is honoured exactly as instructed. Clause D is satisfied by disclosure and by a guard rather than by a version bump: the measured distance from upstream is published in this entry, the pin is now asserted by a test, and no move may be made without the owner named under *Remediation / follow-up*. Any approved move must be followed by a **full re-run of the scan with the skip flag absent**, re-measuring the figures [V-10c](#verification) publishes, because a different engine reports a different suppressed-match count and can surface findings the pinned engine does not |
| **Alternatives considered** | **(a) Advance the pin to the current release** — rejected: it resolves unilaterally the exact divergence §0.8.4 says to record, and it is a *major*-line move (13.0.0), so it carries a real prospect of changed report shape and changed suppression semantics that no gate here has measured. **(b) Advance to the tail of the pinned minor line, 12.1.9** — rejected on the same ground; it is smaller, but the instruction says 12.1.0, not "the 12.1 line". **(c) Introduce a version range or a floating version** — rejected outright: it breaks the reproducible-build requirement of clause C, which is why every coordinate in this build is exact. **(d) Record the distance as a residual risk in [§8](#risks) instead of as a conflict here** — rejected because there genuinely are two binding requirements in collision, not one accepted exposure; the residual-risk framing would drop requirement (b) from the record |
| **Rationale** | The reason a stale *scanner* is not the same risk as a stale *library* is worth stating, because the intuition that it is would push toward the rejected option (a). Advisory **data** is not carried in the plugin: the engine downloads the feed into `~/.m2/repository/org/owasp/dependency-check-data/` at scan time, so the pinned 12.1.0 scans against a current feed, which is why [V-10c](#verification) reports a finding published well after the engine's own release. What the engine version *does* govern is analyser coverage, report shape and the feed **protocol** it speaks — and that last one is the operative risk: this tool's history includes a feed-API migration that stranded older engines outright. That is a **future availability** risk, not a present false-negative claim, and this entry states it as the former. The honest position is therefore: the pin costs nothing measurable today, the exposure is that a feed-side change could make it stop working, and the response is a flagged pin with a named owner rather than a silent bump |
| **Observable consequence** | The scan runs on 12.1.0 and reports 168 dependencies, 166 suppressed matches and one active finding below the gate, exactly as [V-10c](#verification) records. A reviewer comparing the pin against the registry finds it thirteen releases behind and finds this entry stating why. Should someone edit the pin without amending this entry, `BuildProvenanceTest` fails and names both the pin and this identifier |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/infrastructure/BuildProvenanceTest.java` — nested `SuppressionPremises`, `theVulnerabilityScannerPinIsExactAndItsDivergenceIsDisclosed` asserts that `pom.xml` still carries exactly `12.1.0`, that no range or floating version stands in for it, and that this entry is present and names the pinned version, the current release and the owner gate. Gate-level: [Gate 2](docs/validation-gates.md#gate-2) and [Gate 6](docs/validation-gates.md#gate-6), whose scan results [V-10c](#verification) records. The precedent for the shape of this resolution is [DL-CR-04](#dl-cr-04), which keeps a pinned coverage plugin against a higher cited version on the same §0.8.4 ground |
| **Remediation / follow-up** | **Open, and deliberately not closed by this work.** A move off 12.1.0 requires the build maintainer's approval as owner, because the pin originates in the requirements rather than in this repository, and this work is not entitled to overrule it. On approval: change the single property, run `./mvnw -B -ntp clean verify` **without** `-Ddependency-check.skip=true`, re-measure the dependency count, the suppressed-match count and every active finding, reconcile `owasp-suppressions.xml` — a new engine may match suppressions differently and turn a suppressed entry live — and amend [V-10c](#verification) and this row with the new figures. Moving to the 13 line additionally requires reading its release notes for report-format and suppression-schema changes before the run, not after. The trigger that would make this urgent rather than routine is a feed-side protocol change the pinned engine cannot speak, which would show as the scan failing to update its data rather than as a wrong result |
| **Verification** | `Target-verified`, `Test-verified` — measured Friday, 7 August 2026. The pin was read from the `dependency-check-maven.version` property of `pom.xml`. The upstream position was measured, not recalled, with `curl -s https://repo1.maven.org/maven2/org/owasp/dependency-check-maven/maven-metadata.xml`, which reports `<latest>` and `<release>` both **13.0.0** and `<lastUpdated>20260803104202`, over **170** published versions; **13** releases stand between the pin and that release — `12.1.1`…`12.1.9`, `12.2.0`…`12.2.2`, then `13.0.0` — and **12.1.0 is still published and resolvable**, so the pin is honourable rather than merely stale. `Not available` on one point, stated rather than glossed: the review that raised this cited the current release as **12.2.2** and cited an upstream statement that outdated releases are unsupported. The first figure is superseded by the measurement above and is not repeated here. The second **could not be confirmed from the project's own published policy** in this environment; it is recorded as the raising review's assertion, and what would settle it is the upstream project's own support statement read directly. The decision does not rest on it: the pin is kept either way, and the flag clause D asks for is supplied either way |
| **Verification note** | The entry that follows was authored concurrently with this one and both were numbered `DL-CR-09`; the source-governs resolution is republished immediately below as [DL-CR-10](#dl-cr-10) so that neither identifier names two entries. |

---

<a id="dl-cr-10"></a>

### DL-CR-10 — Where the plan, a sibling prompt or the agent brief disagrees with the frozen source, the source governs

| Field | Content |
|---|---|
| **Classification** | `CONFLICT RESOLUTION` |
| **Severity** | Medium |
| **The two requirements** | **(1)** The Agent Action Plan, the folder requirements and the per-file agent briefs are the instructions this migration was given, and *Rule 1* clause F requires a finding to be evidence-based rather than asserted. **(2)** The frozen corpus in `app/` is the system of record and the parity oracle. Both are binding, and at nine identified points they say different things |
| **Where they collide** | Each row is an instruction that a reading of the corpus falsifies. **The register is keyed by locator and carries no total**, for the reason [DL-CR-01](#dl-cr-01) carries none.<br><br>**`CIT-CURTIME-WIDTH`** — the plan's note on the BMS layer records the screen clock field as `X(9)`; the symbolic map and [app/cpy/CSDAT01Y.cpy] both say `X(8)`. Owner: `UserListService`'s time formatter, which emits eight characters.<br>**`CIT-COACTUPC-LAST-AREA-A`** — the widely repeated claim that `COPY 'CSSTRPFY'` at [app/cbl/COACTUPC.cbl:4199] is the program's final Area A construct is wrong; `COPY CSUTLDPY` at [:4232] is, after which only the terminating period at [:4233] and a version comment remain. Owner: `AccountUpdateService`.<br>**`CIT-COTRN02C-NO-CLOCK`** — the technical specification describes a generated timestamp for the transaction-add program; the program reads the clock exactly once, at [app/cbl/COTRN02C.cbl:554], and only for the screen header. Owner: `TransactionAddService`, which holds no `Clock`.<br>**`CIT-XREFFIL1`** — the agent brief describes the interest job's second cross-reference DD as "a second logical view of the same dataset"; that is the job control's intent, but the program reaches the dataset through one file definition only. Owner: `InterestCalculationJob`, which retains the unreferenced allocation as a named constant reported at `DEBUG`.<br>**`CIT-FINDING-NUMBER`** — the folder requirements number one interest-job finding 3 while a sibling prompt numbers it 2. Owner: `InterestCalculationJob`, which asserts neither number.<br>**`CIT-BUCKET-DEFAULT`** — the brief instructs that every injected property carry a documented default; the output bucket deliberately carries none, because a default would convert a fail-fast misconfiguration into a silent write to the wrong location. Owner: `InterestCalculationJob`.<br>**`CIT-PROPERTY-NAMES`** — briefs cite the object-store properties in a shorter form than the profiles declare; the declared names are `carddemo.aws.s3.*` with the corresponding `CARDDEMO_S3_*` environment variables. Owners: `InterestCalculationJob` and `BatchPipelineOrchestrator`.<br>**`CIT-TERMINAL-TYPE`** — a sibling precedent selects a different terminal exception type at the interest job's guard than the source implies; the source's own choice governs, and the guard arithmetic and status rendering stay owned by `FileStatusMapper`.<br>**`CIT-PARAGRAPH-PLACEMENT`** — the brief implies each documented paragraph is re-declared where it is documented; a second never-invoked copy of a method the processor already owns is the dead code and duplication clauses B and C forbid, so the job documents the processor's flush paragraphs without re-declaring them |
| **Decision** | **The source governs, in every one of these rows, and the divergence is recorded rather than silently followed or silently ignored.** The correction is stated at the Java declaration that carries it, with the locator that proves it, and the instruction is named as corrected rather than deleted — so a reviewer holding the brief can see why the code does not match it. Where the disagreement is between two instructions rather than between an instruction and the source, as with the finding numbering, **neither number is asserted**: the substance is recorded once and the numbering is left alone |
| **Alternatives considered** | **(a) Follow the instruction and note the discrepancy** — rejected: it would emit nine characters where the map says eight, invent a clock the program does not read, and put a default on the one property whose absence is the safety property. The parity comparison is against the corpus, not against the brief. **(b) Follow the source silently** — rejected: it leaves a reviewer with an unexplained mismatch between the brief and the code, which is the failure clause F exists to prevent. **(c) Treat each as its own conflict entry** — rejected: they share one resolution rule, and nine entries restating the same rule would bury it |
| **Rationale** | An instruction about the corpus is a claim about the corpus, and a claim about the corpus is checkable. Every row above was checked by reading the cited lines. A migration that follows an instruction it can prove wrong is no longer evidence-based, whatever the instruction says |
| **Observable consequence** | Nine places where the delivered code deliberately does not match its brief, each naming the reading that overrides it. A reviewer who reconciles code against brief finds a stated reason at every one |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/service/UserListServiceTest.java` for the eight-character clock field; `src/test/java/com/cardemo/unit/service/AccountUpdateServiceTest.java` for the final Area A construct; `src/test/java/com/cardemo/unit/service/TransactionAddServiceTest.java` for the absence of a clock; `src/test/java/com/cardemo/unit/batch/InterestCalculationJobTest.java` for the parameter guard and the file-definition count; `src/test/java/com/cardemo/unit/infrastructure/SourceCitationResolutionTest.java` is what keeps every locator above resolvable |
| **Remediation / follow-up** | **The register is open.** A tenth divergence needs a row here and a correction at its own declaration; what it must not do is change the code to match an instruction the corpus contradicts. If a future revision of the plan adopts these readings, the rows stay as the record of why the code was already right |
| **Verification** | `Source-verified`, `Target-verified` — 7 August 2026 |

<a id="dl-cr-11"></a>

### DL-CR-11 — The five-substrate topology versus a populated dashboard, resolved by a seventh service

| Field | Content |
|---|---|
| **Classification** | `CONFLICT RESOLUTION` |
| **Severity** | Major |
| **The two requirements** | **(1)** `docs/technical-specifications.md` §0.3.1.4 and §0.4.1.1 enumerate the runtime topology exactly: PostgreSQL 16, LocalStack, Jaeger, Prometheus and Grafana, plus the application — six services and no more, a closed list that `GateVerificationTest` asserted as such. **(2)** §0.7.7(e) requires four named counters to replace the end-of-run `DISPLAY` totals of [app/cbl/CBTRN02C.cbl:L227-L228], and §0.7.7(g) requires the checked-in provisioning to produce a **populated** dashboard with **no manual configuration step**. Both are binding |
| **Where they collide** | The four counters are not written by one process. `carddemo.auth.attempts` is written by the long-lived web application, which Prometheus scrapes. `carddemo.batch.records.processed`, `carddemo.batch.records.rejected` and `carddemo.transaction.amount.total` are written by `POSTTRAN` and `COMBTRAN`, and those run in the operator submission process documented on `BatchPipelineOrchestrator` — a `java -jar` launch with `spring.main.web-application-type` set to `none`, which is what makes the process end when the job ends. **A scrape cannot reach a process that has already exited.** With a scrape as the only collection path the enumerated five substrate services can carry requirement (2) for one counter out of four, and the collision was measured rather than argued: a real `POSTTRAN` run printed `TRANSACTIONS PROCESSED :000000300` and `TRANSACTIONS REJECTED  :000000038` while `sum(carddemo_batch_records_processed_total)` answered `0`, eight of the ten series stood at `0.0`, and six of the nine data panels could never hold a value |
| **Decision** | **Requirement (2) governs, and a seventh service is added: a Prometheus Pushgateway.** The batch process publishes its final values as it exits (`shutdown-operation: push`) and the gateway retains them; `observability/prometheus.yml` reads them back under a second job, `carddemo-pushgateway`, with `honor_labels: true`. The push is **off by default in every profile** and enabled per launch, so the scraped web application cannot double-publish a series it is already scraped for. A `MeterFilter` in `ObservabilityConfig`, active only while the push is enabled, restricts a pushing process to the `carddemo.` namespace |
| **Alternatives considered** | **(a) Increment the batch counters from `TRANREPT`, which the queue listener can launch in the web process** — rejected outright: those increments were *removed* on purpose, because `carddemo.batch.records.processed` mirrors `ADD 1 TO WS-TRANSACTION-COUNT` at [app/cbl/CBTRN02C.cbl:L206], whose population is the daily-transaction records `POSTTRAN` reads. Re-adding them would make the counter count something the source never counted — a false figure is worse than an absent one. **(b) An `ApplicationRunner` or scheduled launch inside the web tier** — rejected: finding `CFG-001` removed exactly that mechanism, and it would run batch in the online address space, which the mainframe separated. **(c) A one-shot compose batch service, scraped directly** — rejected: `POSTTRAN` succeeds only once per seeded dataset, so a second `docker compose up` would exit non-zero and `--wait` would fail; and an instant-query panel returns *No data* once the samples pass the staleness window, which is a worse answer than the zero it replaced. **(d) OTLP metrics into Prometheus's receiver** — rejected: same staleness behaviour, plus name-translation risk on series the dashboard selects by exact name. **(e) Extend the report-queue listener to accept a `POSTTRAN` envelope** — rejected: the envelope is HMAC-signed ([DL-RM-08](#dl-rm-08)), so an operator cannot hand-craft one. **(f) Disclose the gap and leave it** — rejected: no plan exception licenses an unpopulated dashboard, and §0.8.4 requires a deviation to be *labelled*, not a requirement to be dropped |
| **Rationale** | The enumeration in (1) describes a topology sized for one collection path; the requirement in (2) is about an outcome. Of the two, the outcome is what a reader of the dashboard depends on, and the enumeration is what a reader of the plan can be shown a reason for. The push is also the **faithful** mechanism rather than merely an available one: the legacy job published its totals **once**, at end of run, to a destination that retained them for reading afterwards, which is precisely a Pushgateway's semantics. Retention is what makes it right for a counter and wrong for a gauge, which is why the filter exists |
| **Observable consequence** | `docker compose up` starts **seven** services rather than six, and one more published port — loopback-bound like every other. After a batch launch carrying `--management.prometheus.metrics.export.pushgateway.enabled=true`, the previously-zero panels render real values, and they **persist after the producing process has exited**, which is the point. A pushed series carries `job="carddemo-batch"` while a scraped one carries `job="carddemo-app"`; the dashboard's expressions select no `job` label, so `sum()` spans both and the reader sees one figure. Without the launch flag the batch series stay at the online tier's eager zeros, exactly as before |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/config/ObservabilityConfigTest.java` — the filter is opt-in and admits only `carddemo.`; `src/test/java/com/cardemo/e2e/GateVerificationTest.java` — `EXPECTED_COMPOSE_SERVICE_COUNT` is 7, the seven service names are asserted present, an eighth is refused, and both scrape jobs plus `honor_labels` are asserted in `observability/prometheus.yml`; `src/test/java/com/cardemo/unit/config/EnvironmentTemplateContractTest.java` — the eighth published port still goes through the one loopback bind expression. Gate-level: [Gate 8](docs/validation-gates.md#gate-8) |
| **Remediation / follow-up** | None outstanding. Should the batch tier ever acquire a long-lived process with a scrape endpoint, the push path becomes redundant and the service can be withdrawn — at which point this entry is the record of why it was there. Owner of any proposal to remove it must first show that the batch-fed counters reach Prometheus by some other route |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 8 August 2026. The runtime reading behind *Where they collide* and *Observable consequence* was taken in this working tree on the seven-service stack; `docs/validation-gates.md` [§Gate 8](docs/validation-gates.md#gate-8) is where a runtime reading is published, not here |



---

<a id="risks"></a>

## 8. Residual risks

Knowingly accepted exposures. None is dropped; each names what would close it and who owns
that. `docs/validation-gates.md` [§12.6](docs/validation-gates.md#deferred) carries the
deferred-hardening catalogue as `R-1`…`R-10`; the entries here carry the decisions.

<a id="dl-rr-01"></a>

### DL-RR-01 — The framework line's support horizon has been reached

| Field | Content |
|---|---|
| **Classification** | `RESIDUAL RISK` |
| **Severity** | **High** |
| **Status of the concern** | The migration requirements pinned Spring Boot **3.5.11** and recorded the end of open-source support for the 3.5 line as a mid-2026 concern to be tracked. **That concern had materialised by 30 July 2026 and is still materialised at 7 August 2026.** |
| **Evidence, and its provenance** | Consulted at the time of writing: the Spring project's published support policy, plus several independent release-tracking sources, agree that **open-source support for Spring Boot 3.5 ended on 30 June 2026**, that the **final open-source release of the line was 3.5.16 on 25 June 2026**, and that the supported lines are now 4.0 and 4.1 — with 4.1 supported into 2027. Spring's own policy adds a further multi-year **commercial** support period for the last minor of a major version, which 3.5 is; Spring designates no release as long-term support. **This is stated as verified against current sources at the stated date, not asserted from the migration plan** — the plan's wording was written in the future tense and would be stale if repeated |
| **Two distinct exposures follow** | **(1) The line receives no further open-source patches.** A future vulnerability disclosed against Spring Framework 6.2, Spring Security 6.x or the rest of the portfolio at that generation has no free fixed version on 3.5.x. **(2) The pin is also behind its own line.** 3.5.11 is **five patch releases behind the final open-source release, 3.5.16**, so patches that *were* published free of charge are not taken |
| **Decision** | **The pin is honoured exactly as instructed and is not advanced by this work.** Advancing a pinned version is a scope decision, not a maintenance chore. The risk is recorded with its verified dates so that the decision to move is taken deliberately, on evidence, by whoever owns it |
| **Alternatives considered** | **(a) Advance to 3.5.16** — a within-line patch move, no API change expected, and it recovers five releases of published fixes. Not taken here **only** because the pinned version is a stated requirement and changing it unilaterally is out of scope; it is the recommended first step and is the cheapest of the three. **(b) Advance to 4.1** — the durable answer, and notably the Java baseline does not move (4.x requires Java 17 and supports Java 25), so there is no forced toolchain jump; but it is a Jakarta EE 11 / Servlet 6.1 baseline change with real dependency-layer work, and it is unambiguously a scope decision. **(c) Commercial extended support** — a time-boxed bridge, appropriate only alongside a funded migration, not as a way to avoid one |
| **Observable consequence** | None at run time. The build still resolves: published artefacts are never withdrawn from the public registry, so nothing breaks today. That is exactly what makes this risk easy to miss — the failure mode is silence, not an error |
| **Test / gate evidence** | The pin itself is asserted by `src/test/java/com/cardemo/unit/infrastructure/BuildProvenanceTest.java`. Vulnerability-scan evidence, **measured 7 August 2026 and no longer outstanding**, and it substantiates exposure (2) with a named advisory &mdash; `CVE-2026-66299` at CVSS 7.5, whose full analysis and dated disposition are carried by [DL-RR-09](#dl-rr-09): the no-skip `./mvnw -B -ntp clean verify` recorded at `V-10c` in [§12](#verification) — report timestamp `2026-08-07T03:42:57Z`, **166 dependencies, zero findings at or above CVSS 7**, `BUILD SUCCESS`, published under [Gate 2](docs/validation-gates.md#gate-2). That reading closes the evidence gap this row previously declared, and it does **not** close the concern above: the single active finding it reports, `CVE-2026-40977` at CVSS 6.7, is fixed only by advancing the pinned parent, which is the exposure this entry exists to record |
| **Remediation / follow-up** | **Owner: build maintenance, with product sign-off for option (b).** Recommended sequence: (i) move to 3.5.16 as a patch-level change with its own decision entry; (ii) plan the 4.1 upgrade as scoped work; (iii) re-verify support status at the time of that decision rather than trusting this entry, which is dated. Supersedes the deliberately open position taken at `R-9` in `docs/validation-gates.md`, which recorded that no authoritative source had been consulted there |
| **Verification** | `Source-verified` (pin), `Target-verified` (pin), externally verified against current published support information — 30 July 2026 |

<a id="dl-rr-02"></a>

### DL-RR-02 — Eight deferred hardening items

| Field | Content |
|---|---|
| **Classification** | `RESIDUAL RISK` |
| **Severity** | Ranges **Low** to **High** per item; two are High |
| **Decision** | Eight items are deliberately out of scope and **none is dropped**: table partitioning, read replicas, connection-pool tuning, transport-security termination, request rate limiting, URI-based API versioning, generated API specification, and encryption at rest for personally identifiable data |
| **Rationale** | Six of the eight would have to be sized against a measured workload that does not exist — [Gate 3](docs/validation-gates.md#gate-3) records a **measured baseline**, not a target, because **the source publishes no service-level objective and none may be invented**. Tuning a pool or partitioning a table against an unmeasured access pattern optimises blind. The remaining two are deployment concerns that stop where container orchestration stops |
| **The two High items, stated plainly** | **Transport security** is not terminated by this work, so credentials and tokens traverse the network unprotected outside a trusted boundary; the trigger is any deployment beyond a trusted local network. **Encryption at rest** is not applied, and the customer layout carries a nine-digit government identifier and full addresses; the trigger is any deployment holding real customer data |
| **Alternatives considered** | **(a) Implement all eight** — rejected: six would be guesses, and a guessed limit either throttles legitimate use or fails to protect. **(b) Omit them silently** — rejected: *Rule 1* clause F requires disclosure |
| **Observable consequence** | The system is complete for its stated purpose and is **not** hardened for an untrusted network or for real personal data |
| **Test / gate evidence** | Per-item triggers and severities are catalogued as `R-1`…`R-8` in `docs/validation-gates.md` [§12.6](docs/validation-gates.md#deferred); this entry does not restate that table |
| **Remediation / follow-up** | **Owner: deployment**, per item, on the stated trigger |
| **Verification** | `Target-verified` — 30 July 2026 |

<a id="dl-rr-03"></a>

### DL-RR-03 — Transaction `CDV1` has no program to migrate

| Field | Content |
|---|---|
| **Classification** | `RESIDUAL RISK` |
| **Severity** | Low |
| **Source evidence** | The CSD defines 18 transactions and 18 programs but only 17 mapsets. Seventeen transaction-to-program pairs have both a source file and a mapset. The eighteenth is `CDV1 → COCRDSEC`, described at [app/csd/CARDDEMO.CSD:L389] as `DEVELOPER TRANSACTION - 1` with `PROGRAM(COCRDSEC)` at [:L390]. **`COCRDSEC` has no source file anywhere in the repository**; the only other occurrence of the name is its own `DEFINE PROGRAM` at [:L211]. It is a dangling definition with nothing behind it |
| **Decision** | **No endpoint is invented.** The absence is documented, and the arithmetic is stated: 17 sourced screen programs plus 1 orphan definition = 18 CSD entries |
| **Alternatives considered** | **(a) Infer the program's purpose from its name** and implement something plausible — rejected: it fabricates behaviour, and a fabricated endpoint would be indistinguishable from a real one to a later reader. **(b) Omit the finding** — rejected: a reader counting CSD transactions against endpoints finds 18 versus 17 and cannot tell whether one was missed |
| **Observable consequence** | The API exposes 17 operations, not 18. Anyone auditing the CSD against the API meets this entry rather than an unexplained gap |
| **Test / gate evidence** | Program-count reconciliation is asserted in `src/test/java/com/cardemo/e2e/GateVerificationTest.java`. Gate-level: [Gate 7](docs/validation-gates.md#gate-7); its result is recorded in that ledger, not here |
| **Remediation / follow-up** | If the source is ever recovered, it becomes new scope with its own decision entry; owner: not this migration. **The information needed is the program source itself, which does not exist at `7756d89`** |
| **Verification** | `Source-verified` — 30 July 2026 |

<a id="dl-rr-04"></a>

### DL-RR-04 — The retained identifier race is an operational exposure

| Field | Content |
|---|---|
| **Classification** | `RESIDUAL RISK` |
| **Severity** | Medium |
| **Decision** | The race is retained for parity — [DL-PP-04](#dl-pp-04) — so the exposure it creates is accepted rather than removed |
| **Rationale** | Under concurrent transaction-add or bill-payment requests, two callers can compute the same identifier. The primary-key constraint converts that into a clean refusal instead of an overwrite, so the failure is safe but visible |
| **Alternatives considered** | Covered at [DL-PP-04](#dl-pp-04); a sequence would remove the exposure and break baseline comparability |
| **Observable consequence** | A caller may receive a duplicate-record refusal under concurrency and must retry. Throughput on those two operations is bounded by the retry rate |
| **Test / gate evidence** | `src/test/java/com/cardemo/integration/repository/TransactionRepositoryTest.java`. Concurrency measurement: **Not available — implementation/evidence not yet generated.** Prerequisite: a captured [Gate 3](docs/validation-gates.md#gate-3) baseline under concurrent load, which does not exist |
| **Remediation / follow-up** | Callers retry. If a measured collision rate becomes unacceptable, a sequence becomes a scope decision with its own entry; **owner: product**, because it forfeits baseline comparability |
| **Verification** | `Source-verified`, `Target-verified` — 30 July 2026 |

<a id="dl-rr-05"></a>

### DL-RR-05 — All cloud interaction targets a local emulator; zero live credentials

| Field | Content |
|---|---|
| **Classification** | `RESIDUAL RISK` |
| **Severity** | Medium |
| **Decision** | Object-store, queue and topic interaction targets a **local emulator** through an endpoint override configured in `src/main/java/com/cardemo/config/AwsConfig.java` and the profile files. **No live credential appears in any file, and no code path may reach a live endpoint** |
| **Rationale** | The requirement is explicit that zero live credentials exist. The emulator is provisioned idempotently by `localstack-init/init-aws.sh`, so repeated stack cycles converge rather than failing on resources that already exist |
| **Alternatives considered** | **(a) A live account for integration testing** — rejected: it requires credentials in the repository or in continuous integration, which *Rule 1* clause D forbids. **(b) Mocking the clients entirely** — rejected: it would not exercise the client configuration, which is where endpoint-override defects live |
| **Observable consequence** | The risk is **unmeasured behaviour against a real provider**: emulator fidelity is good but not total, so a provider-specific behaviour — consistency timing, error shapes, versioning edge cases — could differ. That is the accepted exposure |
| **Test / gate evidence** | `src/test/java/com/cardemo/integration/aws/` — seven integration classes including `S3BucketProvisioningIntegrationTest`, `SqsReportQueueIntegrationTest` and `ObjectStoreAuthorizationBoundaryIntegrationTest`. Gate-level: [Gate 8](docs/validation-gates.md#gate-8), whose result that ledger records |
| **Remediation / follow-up** | A pre-production run against a real account, with credentials supplied by the environment and never committed; **owner: deployment** |
| **Verification** | `Target-verified` — 30 July 2026 |

<a id="dl-rr-06"></a>

### DL-RR-06 — Generation retention is documented, not enforced

| Field | Content |
|---|---|
| **Classification** | `RESIDUAL RISK` |
| **Severity** | Low |
| **Decision** | Relative generation references become prefixed object keys over a versioned bucket — [DL-MS-10](#dl-ms-10) — and the source's `LIMIT(n)` retention values are **documented rather than enforced**. No lifecycle rule is applied |
| **Rationale** | Retention is a storage-management policy, not application behaviour, and applying a rule that deletes data is not something to do implicitly as part of a migration. The one conflicting declaration is nonetheless resolved now, at [DL-CR-02](#dl-cr-02), so the number is decided before anyone needs it |
| **Alternatives considered** | **(a) Apply lifecycle rules matching each `LIMIT`** — rejected: it introduces automated deletion with no operator decision behind it. **(b) Emulate generation counting in application code** — rejected: it recreates catalogue semantics with no consumer |
| **Observable consequence** | Generations accumulate indefinitely; storage grows without bound until a rule is applied |
| **Test / gate evidence** | `src/test/java/com/cardemo/integration/aws/S3GenerationKeyIntegrationTest.java` covers key ordering, not retention |
| **Remediation / follow-up** | Apply lifecycle rules at deployment, using 10 for the report base and 5 for the other six; **owner: deployment** |
| **Verification** | `Source-verified`, `Target-verified` — 30 July 2026 |

<a id="dl-rr-07"></a>

### DL-RR-07 — `TRACEABILITY_MATRIX.md` — obligation discharged

| Field | Content |
|---|---|
| **Classification** | `RESIDUAL RISK` |
| **Severity** | Medium when raised; **closed** at this revision |
| **Statement** | The paragraph-level mapping from all 28 programs to their Java methods was owed at `TRACEABILITY_MATRIX.md` in the repository root. When this entry was raised that file was absent, and its absence was verified rather than assumed. **It has since been authored, so this entry records a closed obligation rather than an open gap** |
| **Consequence** | [Gate 7](docs/validation-gates.md#gate-7) reads that matrix. The prerequisite it was missing is now satisfied, so the gate is executable; the gate's own result is recorded in `docs/validation-gates.md` and in [§16 of the matrix](TRACEABILITY_MATRIX.md#verify), not here |
| **What was needed** | Four prerequisites were stated precisely and all four are met: a matrix keyed to commit `7756d89`; covering all 28 programs with the verified line counts; mapping every paragraph to its Java method; and recording the historical capacity limit of 510 from [DL-DV-03](#dl-dv-03). The delivered matrix publishes 537 rows — 528 procedure-division paragraphs plus 9 synthetic entry rows — against a derived census of 528, with 534 distinct Java targets and no unmapped paragraph |
| **Alternatives considered** | **(a) Inline the matrix here** — rejected: it is a different artefact with a different shape and a different consumer, and duplicating it would guarantee the two drift. That reasoning still holds, which is why this entry now **links** to the matrix rather than absorbing it. **(b) Link to it before it existed** — rejected at the time: a broken link in an evidence register undermines every other link in it |
| **Remediation / follow-up** | **Done.** Authored by the traceability deliverable. The three entries that depended on it — [DL-MS-02](#dl-ms-02), [DL-DV-03](#dl-dv-03) and [DL-LD-07](#dl-ld-07) — now resolve to published rows: the paragraph-to-method rule to the 537-row matrix, the 510-transaction ceiling to the `CBSTM03A` `8500-READTRNX-READ` row, and the redundant index assignment to `TM-CBSTM03A-R003` |
| **Verification** | Absence verified on disk — 30 July 2026. Presence and content verified on disk — 6 August 2026 |

<a id="dl-rr-08"></a>

### DL-RR-08 — Forward-reference wording in the Java tree — convention retired, obligation discharged

| Field | Content |
|---|---|
| **Classification** | `RESIDUAL RISK` |
| **Severity** | Low |
| **Statement** | Before this file existed, no source comment could honestly say a decision was "recorded in `DECISION_LOG.md`", so the tree adopted a forward-reference convention — *owed an entry in the planned `DECISION_LOG.md`* — and two guards enforced it: `src/test/java/com/cardemo/unit/model/EvidenceHonestyTest.java` and `src/test/java/com/cardemo/unit/infrastructure/DocumentationConsistencyTest.java`. The convention outlived its premise. **Re-measured 8 August 2026 with a case-insensitive grep, the guarded tree carries the phrasing at nine occurrences across two files, and none is an instance of it**: `src/test/java/com/cardemo/unit/model/EvidenceHonestyTest.java` quotes the superseded spelling eight times — inside the two patterns that forbid it, inside the matcher fixtures that prove they fire, and inside the prose that explains why — and `src/main/java/com/cardemo/package-info.java` quotes it once inside the `grep` a reader runs to check the sweep held. Reproduce with `grep -rni 'owed an entr' src/main src/test` — **the `-i` is load-bearing**, because a case-sensitive grep reports two of these four and silently misses every capitalised occurrence, which is exactly how fifteen live sites survived a sweep that believed itself complete. Occurrences also sit in this register itself, quoting the convention in order to define and forbid it, and none is a site of it — five under the loose stem and three under the shape rule, in this row and in **Remediation** below. No figure is published for them here, because a count of quotations inside the sentence that quotes them is falsified by the next sentence and nothing notices; the register is not in the guard's scanned roots in any case. **Two earlier readings of this entry are withdrawn:** one cited "roughly 198" and used it inconsistently as both a site count and a file count, and the next cited **75 occurrences across 31 files** as the standing state of a convention that was to be kept **Counted by Parser 2 of §12.1 rather than by the looser grep above, the convention occupies 5 occurrences across 1 files** — 0 occurrences in 0 files under `src/main`, and 5 in 1 files under `src/test`, that one file being the guard itself. The two counts differ because they ask different questions: the grep matches the stem `owed an entr` wherever it appears, while Parser 2 matches only the full forward reference naming a register, so the reproduce command in `src/main/java/com/cardemo/package-info.java` is a hit for the former and not for the latter. **A third reading is withdrawn with them:** **21 occurrences across 12 files**, which was the census before the Java tree's comment layer was reworked and every site given a resolvable identifier. **A fourth claim of this entry is withdrawn, and it is the one that mattered: the convention was not in fact gone.** Re-censused 8 August 2026 by the *shape* of the claim rather than by one of its spellings, the tree still carried it at **nineteen live sites in nine files** — fifteen spelled `owed to the` or `destined for the`, and four more that wrap across a Javadoc line break so no single-line pass can see them at all. Both the sweep and this entry's census had been keyed on the stem `owed an entr`, which is the one spelling the retirement happened to notice; ten of the nineteen sat in `TransactionReportProcessor` alone. All nineteen now name the identifier that holds them, and the rule that closes the class rather than the instance is `REGISTER_OBLIGATION`, described under **Remediation** below. |
| **Consequence** | With both registers authored, the forward-reference wording was **conservative rather than wrong** — an entry that was owed was in fact held — but it was still wrong *about the tree*: it sent a reader looking for an unwritten justification instead of at the entry that carries it. Replacing each phrase with the identifier of the entry that holds it makes the obligation resolvable rather than merely honest |
| **Decision** | **Retire the convention, and cite the entry that holds each site.** Every forward reference in the Java tree now names a stable identifier: [DL-CR-01](#dl-cr-01) where a construct is retained because deleting it would change observable behaviour or break the paragraph map, [DL-PP-13](#dl-pp-13) where a low-severity source behaviour is reproduced rather than normalised, [DL-CR-09](#dl-cr-09) where the plan or an agent brief disagrees with the frozen corpus and the corpus wins, [DL-DV-10](#dl-dv-10) at the boundaries where a *Rule 1* standard, or the absence of a behaviour to reproduce, overrides parity, and the specific `DL-MS-*`, `DL-PP-*`, `DL-LD-*` and `DL-DV-*` identifiers everywhere else. Two entries were authored for this purpose rather than an existing identifier being stretched to cover a site it did not fit — [DL-PP-13](#dl-pp-13) and [DL-CR-09](#dl-cr-09) — and a third, [DL-DV-10](#dl-dv-10), gathers four deviations that previously had no identifier at all. **An earlier revision of this row decided the opposite** — *"Leave the Java-tree sites unchanged, and keep the obligation form as a deliberate tree-wide convention"*, on the ground that rewording would be a large diff unrelated to the deliverable — and **that decision is withdrawn**: the wording is not unrelated to the deliverable, because the deliverable includes the evidence a reader uses, and *Rule 1* clause F requires that evidence to be resolvable. The `docs/` sites had already been reworded under the old decision as its stated exception; they are now simply consistent with the rest |
| **Alternatives considered** | **(a) Keep the obligation form** — this is the decision now withdrawn above; it was defensible only while "owed" was the most a comment could truthfully say. **(b) Weaken the guards so both forms are accepted** — rejected: the guards exist because four review findings shared the root cause of comments asserting things the tree did not support, and accepting both spellings would leave the tree permanently half in each. **(c) Reword the sites but cite the register generically, without an identifier** — rejected: "recorded in `DECISION_LOG.md`" is unresolvable in an eighty-four-entry document, so it moves the reader's problem rather than solving it. **(d) Give every site its own new entry** — rejected: about twenty sites share one decision and one severity, which is what [DL-PP-13](#dl-pp-13) records once |
| **Observable consequence** | A reader of a Javadoc comment is given the identifier of the entry that justifies what they are looking at, and can resolve it by anchor. **The claim this row used to make — *"nothing in the tree now describes a delivered register as owing anything"* — is withdrawn as an assertion and reinstated as an enforced invariant**, which is the only form of it worth publishing: it was true of the one spelling that had been grepped for and false of four others, and a sentence cannot tell a reader which. It now holds because `REGISTER_OBLIGATION` fails the build on any of them, not because a census once said so |
| **Test / gate evidence** | Both guard classes pass at this revision — see [§12](#verification). `EvidenceHonestyTest` now carries **two** register rules rather than one: `neitherRegisterIsDescribedAsAbsent` over `REGISTER_ABSENCE` and `neitherRegisterIsDescribedAsOwingAnEntry` over `REGISTER_OBLIGATION`, with both scanned over comment prose *and* over assertion-message literals. `InventoryCountGateTest` re-derives the two census figures this entry publishes from the tree, so neither can go stale unnoticed |
| **Remediation / follow-up** | **Done, and self-checking.** `DocumentationConsistencyTest` holds an **empty** absent-evidence set and an authored-evidence set naming both registers, asserts that emptiness explicitly, and asserts both registers present so the rule cannot decay into a silent no-op. `EvidenceHonestyTest`'s `REGISTER_CLAIM` and `REGISTER_POSSESSION` forbade a present-tense claim of record in either register, which was the right rule for exactly as long as neither file existed; both are authored, so those two patterns had stopped guarding a falsehood and would have started requiring one. They are **retired** and replaced by `REGISTER_ABSENCE`, which guards the reversed direction: no comment may call either register planned, absent, unavailable or not yet written. The retirement is self-checking — `FORMERLY_ABSENT_REGISTERS` is asserted present, so deleting either register forces the old rules to be reinstated deliberately rather than letting a claim about a missing document pass unnoticed. **A second rule now stands beside it, because the retirement was enforced by nothing.** `REGISTER_ABSENCE` catches an *absence* claim, and the obligation form is not one: "destined for the `DECISION_LOG.md`" says the register exists and is merely owed something, so it passed every guard the tree had while remaining exactly as misleading. `REGISTER_OBLIGATION` is a **sibling pattern, deliberately not a further alternative inside `REGISTER_ABSENCE`** — that pattern's own discrimination test asserts the spelling *"Owed an entry in `DECISION_LOG.md`, authored at the root"* is **not** one of its offences, because that was the honest rewording an absence claim was once corrected *to*, so folding the obligation phrasings in would have inverted a documented decision inside the pattern recording it. The sibling covers the obligation *shape* in five alternatives — the `owed` family, `destined for`, `pending`/`awaiting`, and the future tenses of `recorded` — each requiring a register name and each bounded at a sentence break, and two of its bounds are load-bearing: `\b` on the `owed` family, because the substring `owed to` sits inside *allowed to* and this tree uses that phrase in hundreds of assertion messages; and an immediately-adjacent register name after `destined for`, so a value *destined for a log* is outside the rule by construction rather than by exemption. It is proven non-vacuous by fault injection and by ten discrimination assertions, five positive and five negative. **What a new site must now do:** name the identifier of the entry that holds it. If no entry fits, author one — that is what [DL-PP-13](#dl-pp-13), [DL-CR-09](#dl-cr-09) and [DL-DV-10](#dl-dv-10) are, and [DL-DV-10](#dl-dv-10) gained four numbered boundaries in this reading for exactly that reason. Writing "owed" instead is the form this entry closes, and the form `REGISTER_OBLIGATION` now refuses |
| **Verification** | `Target-verified`, `Test-verified` — 30 July 2026. Guard narrowing verified by execution — 6 August 2026. Site census re-measured, the `docs/` exception applied, and the `EvidenceHonestyTest` premise corrected and re-proven non-vacuous by fault injection — 7 August 2026. Convention retired across the Java tree, every site given a resolvable identifier, and the residual census re-measured **case-insensitively**, none of the quoted occurrences being an instance — 7 August 2026. The fifteen sites a case-sensitive census had missed were closed in that reading, and the reproduce command in this entry and in `src/main/java/com/cardemo/package-info.java` now carries `-i` so the census cannot under-report again. **Re-censused by claim shape rather than by spelling, nineteen further live sites found and closed, `REGISTER_OBLIGATION` authored beside `REGISTER_ABSENCE`, and the rule proven non-vacuous by injecting a `destined for` probe into a source file and observing the named failure — 8 August 2026.** Both §12.1 census figures above were re-derived in the same reading rather than carried forward |

---

<a id="dl-rr-09"></a>

### DL-RR-09 — A High-severity CVE with no published fix, answered by a narrow dated suppression and still disclosed

| Field | Content |
|---|---|
| **Classification** | `RESIDUAL RISK` |
| **Severity** | **High** — the CVSS score, taken as published rather than re-scored downward on the exposure analysis below |
| **Statement** | **CVE-2026-66299**, CVSS **7.5**, matches `org.apache.tomcat.embed:tomcat-embed-core:10.1.57`, and **there is still no published version to move to**. It sits at or above the CVSS **7** gate, so on the record alone it would fail the build. It is answered by the single load-bearing **Tier 2** entry in `owasp-suppressions.xml` — narrow, scoped to that one coordinate and that one identifier, evidence-based, and **dated `until=2026-10-01Z` so the finding returns rather than disappearing**, which is the remediation order [Gate 2](docs/validation-gates.md#gate-2) itself fixes for a record at or above the threshold. **Measured on 7 August 2026 at the commit this change publishes** by the full no-skip `./mvnw -B -ntp clean verify`: **BUILD SUCCESS** in 08:56 min, engine dependency-check-maven 12.1.0, report dated `2026-08-07T16:00:45Z`, **166** dependencies scanned, **167** suppressed matches, and **one** active finding — CVE-2026-40977 at CVSS **6.7**, below the gate. **An earlier revision of this row stated that the scan *fails the build* on this CVE at exit code 1 and that *no suppression entry is added*; both statements are withdrawn.** They were true of `1363f491`, before the dated entry existed, and the tree they describe is not the tree this register documents |
| **Evidence, and its provenance** | Measured, not inferred, and in three independent parts. **(1) What the CVE is:** uncontrolled resource consumption in Apache Tomcat's WebSocket **chat example**, affecting 10.1.24 through 10.1.57, and the advisory itself states that users who removed the examples web application are unaffected. **(2) Whether this application ships it:** `tomcat-embed-core-10.1.57.jar` contains **zero** entries matching the examples or the chat sample, measured with `unzip -l`, because the embedded distribution ships no `webapps` directory at all. **(3) Whether a fix is obtainable:** the NVD names **10.1.58**, which **is not published** — Maven Central carries 10.1.49, .50, .52, .53, .54, .55, .56 and .57 and nothing higher, with 10.1.58, .59 and .60 all returning HTTP 404, and the 11.0.x line's fix 11.0.25 likewise unpublished against a latest of 11.0.24. Reports retained at `target/dependency-check/dependency-check-report.{html,json,sarif}` |
| **Decision** | **Keep the exposure disclosed, and answer the record with the narrowest instrument that keeps it visible.** The disposition changed once, deliberately, and both halves of that change are recorded rather than the later one overwriting the earlier: the finding was first left failing the build, and is now answered by a dated Tier 2 entry naming the one coordinate and the one identifier. What did **not** change is the disclosure — the advisory, its score, the exposure analysis and the absent fix are all still published here and under [Gate 2](docs/validation-gates.md#gate-2). The expiry is what makes the two positions equivalent in the one respect that matters: on `2026-10-01` the finding comes back by itself |
| **Alternatives considered** | **(a) Override `tomcat.version` upward** — the mechanism this project already uses, and the first thing tried: `pom.xml` overrides the parent BOM's 10.1.52 to 10.1.57 precisely to close CVE-2026-41293, CVE-2026-24880 and CVE-2026-41284. It is rejected here only because it has nowhere to go — there is no higher published 10.1.x. **(b) Move to the Tomcat 11 line** — rejected twice over: the fix release is also unpublished, and Tomcat 11 is a Servlet 6.1 baseline while Spring Boot 3.5 targets 6.0, so it would break the framework contract to chase a version that does not exist. **(c) Add an evidence-based suppression** — genuinely defensible on the exposure analysis, and rejected on disclosure grounds: it converts a visible High finding into an invisible one, and `pom.xml` records a deliberate move *away* from suppression, having deleted the entries that once masked 37 CVEs across 11 dependencies. A finding that cannot be fixed should be **loud**, not filed. **(d) Re-score it as Low because the component is absent** — rejected: re-scoring someone else's advisory to make a build green is precisely how a real exposure gets lost |
| **Observable consequence** | `./mvnw clean verify` **succeeds with the scan executed** — measured, not assumed. No runtime behaviour is affected and, on the exposure evidence above, no attack path exists in what this application deploys. Two consequences are accepted in exchange for the green build. **(1)** The scan reports `One or more dependencies were identified with known vulnerabilities`, one of the two `[WARNING]` lines the no-skip run emits; the other is the absent optional NVD API key. Neither is a compiler warning and the zero-warning compile gate is unaffected. **(2)** The suppression **expires on `2026-10-01`**, after which this record fails the build again unless a fixed release has been pinned forward by then. That is the intended cost of answering rather than accepting: the deadline is real and it is not renewable without new evidence |
| **Test / gate evidence** | [Gate 2](docs/validation-gates.md#gate-2) publishes the command, the UTC timestamp, the exit code, the CVEs with their trigger analysis and the report paths. `src/test/java/com/cardemo/unit/infrastructure/BuildProvenanceTest.java`, nested class `SuppressionPremises`, holds the register mechanically: the CVSS threshold, the absent skip and the scan's full scope are asserted, the file's own numerals are reconciled against the entries it declares, and every Tier 4 entry is required to carry an `until` date and a named fix version. The reports themselves are retained at `target/dependency-check/dependency-check-report.{html,json,sarif}`. The companion Medium finding against the pinned framework version is carried by [DL-RR-01](#dl-rr-01), and the scanner's own version pin by [DL-CR-09](#dl-cr-09), rather than duplicated here |
| **Remediation / follow-up** | Adopt `tomcat-embed-core` **10.1.58** as soon as Apache publishes it, by advancing the existing `<tomcat.version>` override — a one-line change that clears this entry outright. Owner: whoever maintains the dependency set. Until then, re-run the scan periodically rather than treating this entry as settled: the finding is dated, and both the advisory and the available version list can move |
| **Verification** | `Target-verified` and `Test-verified` — every figure measured on 7 August 2026, and the current ones re-measured at the commit this change publishes: the build result and elapsed time from the no-skip run, the dependency, suppression and active-finding counts read out of `dependency-check-report.json` rather than from console text, the CVSS scores as the report publishes them, the empty examples census inside the artefact, and the four HTTP 404 responses from Maven Central that establish the absent fix |

---
<a id="dl-rr-10"></a>

### DL-RR-10 — Authorization is role-based only; no request is scoped to the caller's own records

| Field | Content |
|---|---|
| **Classification** | `RESIDUAL RISK` |
| **Severity** | **High** — CWE-862 (Missing Authorization) / CWE-639 (Authorization Bypass Through User-Controlled Key) |
| **Statement** | **Ten of the seventeen operations authorise by ROLE and nothing else.** Any authenticated standard user may read or modify **any** account, **any** card and **any** transaction, and may submit a bill payment or a report against an account that has nothing to do with them, simply by naming its identifier. The affected operations are `GET /api/accounts/{accountId}`, `PUT /api/accounts`, `GET /api/cards`, `GET /api/cards/detail`, `PUT /api/cards`, `GET /api/transactions`, `GET /api/transactions/detail`, `POST /api/transactions`, `POST /api/billing/payments` and `POST /api/reports` |
| **Source evidence** | `src/main/java/com/cardemo/config/SecurityConfig.java` grants each of those ten matchers `hasAnyAuthority(ADMIN_AUTHORITY, USER_AUTHORITY)` with **no object-level predicate**, and the chain closes on `anyRequest().denyAll()`. **A reader counting that call in the file finds eleven, not ten, and the eleventh is deliberately outside this entry**: `GET` on the main-menu path carries no resource identifier at all, so there is nothing for a caller to over-reach with; the other six of the seventeen operations are sign-on, which is `permitAll()` because it is the operation that establishes identity, and the five administrator operations, which are `hasAuthority(ADMIN_AUTHORITY)` under two matchers — one wildcard over `/api/admin/**` covering the four user-administration transactions, and one exact rule for the administrative menu, declared first so the more specific path matches. This is faithful: the source has no such check either. [`app/cbl/COACTVWC.cbl`] never consults `CDEMO-USER-ID` — a signed-on 3270 operator viewed any account by typing its number — and the CSD gates transactions by nothing finer than the transaction identifier |
| **Why it cannot be implemented from the source** | **The system of record carries no relation to derive it from.** [`app/cpy/CSUSR01Y.cpy`] is an 80-byte record of exactly six fields — identifier, first name, last name, password, a one-character type and 23 bytes of filler — and **not one of them references an account, a card or a customer.** There is therefore no user-to-resource mapping anywhere in the frozen corpus, and none can be inferred from the migrated schema without inventing a business rule. Inventing one is forbidden twice over: [§0.8.3](docs/technical-specifications.md) requires absent guards be preserved rather than supplied, and [§0.3.2](docs/technical-specifications.md) makes parity the contract and rewriting rules "to be more correct" out of scope |
| **Decision** | **The code is left faithful and the gap is disclosed as a High residual risk.** No ownership predicate is added, no column is invented, and no endpoint is silently narrowed |
| **Alternatives considered** | **(a) Scope every read to the caller by joining through the customer record** — rejected: it presumes the signed-on user *is* a customer, and nothing in the corpus says so. The ten seeded users are operators of an internal back-office application, five of them administrators; the customer file is the data they operate **on**, not a directory of who they are. Building the join would encode a business rule nobody stated. **(b) Restrict the ten operations to the administrator role** — rejected: it contradicts the CSD, which grants the standard user type these transactions, and it would break parity for every non-administrator path. **(c) Add a configurable ownership strategy defaulting to permissive** — rejected: a security control that ships disabled reads as a control in an audit and behaves as its absence at run time, which is worse than an honest gap. **(d) Say nothing** — rejected: *Rule 1* clause F requires missing information to be stated plainly |
| **Observable consequence** | **The migrated system is a faithful back-office application, not a customer-facing one.** Treat every authenticated principal as trusted with the whole data set. It is safe only where the legacy system was safe: on a closed internal network, with operator accounts issued by an administrator. **It must not be exposed to end customers, or to any population where one authenticated user must not see another's data, without the control described below being built first** |
| **What a human must supply before that exposure** | Four decisions, none of which this work can take: **(1)** the authoritative relation between a sign-on identity and the accounts, cards and customers it may reach — a new relation, since the corpus has none; **(2)** whether an administrator retains unrestricted scope, and whether a standard user's scope is one customer, one account or a set; **(3)** the behaviour on an out-of-scope identifier — `403`, or `404` to avoid confirming the record exists, which is an information-disclosure decision in its own right; and **(4)** whether the report and bill-payment operations are scoped by the same relation or by a different one, since neither is a simple record read. Once those are answered the enforcement point is small — a predicate at the service boundary plus a `@PreAuthorize` or an equivalent check — but it cannot be written before they are |
| **Test / gate evidence** | The posture is asserted rather than assumed: the role matchers for all seventeen operations are verified by `src/test/java/com/cardemo/unit/config/` security tests and the route inventory by `src/test/java/com/cardemo/e2e/GateVerificationTest.java`. **No test asserts object-level scoping, because none exists to assert.** Recorded as `H-6` in `docs/validation-gates.md` [§12.2](docs/validation-gates.md#findings) |
| **Remediation / follow-up** | **Owner: product, then application security.** Product answers the four questions above; application security implements the predicate and adds the negative tests that prove a cross-tenant read is refused. **Trigger: any deployment reachable by more than one trust domain.** Until then this entry, not the code, is the statement of the posture |
| **Verification** | `Source-verified` — the absence of any user-to-resource field in `CSUSR01Y` and the absence of any `CDEMO-USER-ID` consultation in `COACTVWC` were both read directly from the frozen corpus. `Target-verified` — the ten role-only matchers were read directly from `SecurityConfig.java` — 7 August 2026 |

---

<a id="dl-rr-11"></a>

### DL-RR-11 — Advisories that only a pinned-version move would close, measured separately on the delivered image and on the plugin graph

| Field | Content |
|---|---|
| **Classification** | `RESIDUAL RISK` |
| **Severity** | **High** — two published High advisories on the delivered image, neither with a fix reachable without moving a pinned coordinate |
| **Statement** | Two scanners that the dependency scan cannot substitute for — a container scan of the **delivered image** and an advisory scan of the **build plugin graph** — report High findings that the Maven dependency scan cannot see. **On the delivered image there are exactly two**, and their only remedy is to advance a version the migration requirements pin; they are recorded here because they are real and not actioned here because advancing a pinned coordinate is a scope decision. **On the plugin graph there is one more, and it is a different kind of finding**: it is not on the delivered image at all, it already carries a written expiring disposition, and it is named below so that a reader who meets it in a scan report can tell which graph it belongs to. Which graph a finding sits on decides what can be done about it, so the two are never totalled together |
| **Evidence, and its provenance** | Measured 7 August 2026 with the digest-pinned scanner `aquasec/trivy:0.73.0` against a `docker save` archive of the image this repository builds, and with the OSV advisory service against the 293 coordinates that `dependency:resolve-plugins` reports. **(1)** `org.springframework.boot:spring-boot:3.5.11` — **CVE-2026-40973**, High, arbitrary code execution and session hijacking through a predictable value; fixed in **3.5.14** and 4.0.6. **(2)** `org.springframework.data:spring-data-commons:3.5.9` — **CVE-2026-41695**, High, denial of service through a crafted property path; fixed in **3.5.12** and 4.0.6. Both arrive inside `carddemo.jar` and both are reported by the image scan, which the Maven dependency scan cannot see. **(3)** `org.springframework:spring-expression:6.2.16` — **CVE-2026-41850**, High, algorithmic denial of service when evaluating user-supplied expressions; fixed in **6.2.19**. **It is on the plugin graph only**, arriving through `spring-boot-maven-plugin:3.5.11`, and it already carries a written Tier 3 disposition expiring `2027-02-01` at `.github/plugin-graph/dispositions.json` on the ground that a build evaluates only this repository's own expressions. **The application graph resolves 6.2.19 — the fixed release — so there is no application-side exposure to record**, because `pom.xml` sets `spring-framework.version` to `6.2.19` as a deliberate forward override of the `6.2.16` that [§0.6.1](docs/technical-specifications.md) records as the bill-of-materials value. Measured, not assumed: `./mvnw -B -ntp -o dependency:list -DincludeGroupIds=org.springframework` reports `spring-expression:jar:6.2.19:compile` alongside `spring-core:jar:6.2.19`. **An earlier revision of this row is withdrawn** — it read *"It is on both the application graph and the plugin graph … while the application-graph occurrence is the exposure recorded here"*, and it made this entry, its `H-7` counterpart and the disposition file itself each publish a third open advisory that the build does not carry. The same withdrawal applies to the **Severity** count above, which read *"three published High advisories"* |
| **Decision** | **Report the two image findings, name the plugin-graph one for what it is, and advance no pin.** [§0.6.1](docs/technical-specifications.md) pins Spring Boot 3.5.11, and [§0.8.4](docs/technical-specifications.md) requires pinned versions to be honoured as given with any divergence recorded rather than resolved unilaterally. The image scan that surfaces (1) and (2) is itself new work delivered here, so those findings are **newly visible rather than newly true**. (3) is reported at graph precision rather than folded in with them, because it is closed on the graph that ships and dispositioned on the graph that does not, and a reader given a total of three would look for a fix that has already been applied |
| **Alternatives considered** | **(a) Advance the parent to 3.5.14 or later** — closes (1) and, through its managed graph, (2); it is a within-line patch move with no expected API change and it is the recommended first step. Not taken here only because the pin is a stated requirement. This is the same move [DL-RR-01](#dl-rr-01) recommends for a different reason, so **one decision closes both entries**. **(b) Override the two coordinates individually** — mechanically possible, and rejected: overriding a Spring module out from under its own bill of materials risks a version skew across the portfolio that no test in this suite is designed to detect, which trades a disclosed risk for an undisclosed one. **(c) Suppress them** — rejected. The disclosure objection set out in [DL-RR-09](#dl-rr-09) applies to an *unbounded* suppression, and that entry's bounded, expiring form is available in principle; it is the wrong instrument **here** for a different reason. Findings (1) and (2) **do** have a published fix — advancing the pin, alternative (a) — so suppressing them would conceal a closable gap rather than time-box an unclosable one. **A reader who has met `CVE-2026-40973` in `owasp-suppressions.xml` will find it suppressed there, and the two dispositions are not in conflict once the gate each governs is named** — this rejection is about the **image scan**, which is defined to fail on High and Critical and offers no suppression path at all, so "suppress it" is not an option the gate has; the suppression is in the **dependency scan**, which does offer one, and it rests on a Tier 3 feature-absence argument — `server.servlet.session.persistent` is set nowhere in this repository and the requirements mandate stateless handling — rather than on a claim that the version is unaffected. One identifier, two scanners, two remedies: the suppression file now carries the pointer back to this entry so neither reads as the only disposition. **(d) Give (3) an application-side suppression entry too** — rejected as unnecessary and actively misleading: the application graph already carries the fixed 6.2.19, so an entry there would assert an exposure the build does not have, and `pom.xml`'s standing invariant is that a forward pin and a suppression entry are never both in place for one record |
| **Observable consequence** | **The image scan fails the build on findings (1) and (2)** — deliberately, since that gate is defined to fail on High and Critical and offers no suppression path. The failure names the findings rather than hiding them, and it clears the moment the parent pin moves. Because the `gate-evidence` job declares `image-scan` among its `needs`, that deliberate failure gates the evidence job as well; the pipeline is red by design until the pin decision is taken, and `docs/validation-gates.md` [§3](docs/validation-gates.md#summary) discloses that as a pipeline-status row rather than leaving a reader to infer it from a green-build assumption. **Finding (3) fails nothing**: the plugin-graph scan accepts a written expiring disposition, and the application graph carries the fixed release. No run-time behaviour is affected by any of the three: no code path in this application evaluates a caller-supplied expression, and the predictable-value defect is in a component this application does not configure |
| **Test / gate evidence** | The image scan and its threshold are asserted by `src/test/java/com/cardemo/unit/infrastructure/BuildProvenanceTest.java` and executed by the `image-scan` job in `.github/workflows/build.yml`; the plugin-graph scan is executed by the `plugin-graph-scan` job and fails on any High or Critical without a written, expiring disposition. Recorded as `H-7` in `docs/validation-gates.md` [§12.2](docs/validation-gates.md#findings) |
| **Remediation / follow-up** | **Owner: build maintenance, with the same sign-off [DL-RR-01](#dl-rr-01) needs.** Advance the parent to the latest 3.5.x, re-run both scans, and delete whichever of findings (1) and (2) the move clears — that also turns the pipeline green, because it is `image-scan` that is failing. Finding (3) is cleared instead by the plugin ecosystem shipping a `spring-boot-maven-plugin` whose own graph carries 6.2.19, which no change to this repository can bring forward; its disposition expires `2027-02-01` so the question returns on a date rather than being closed for ever. Re-measure rather than trusting this entry: it is dated, advisory data moves, and the graph a finding sits on is the first thing to re-establish |
| **Verification** | `Target-verified` — each advisory read from the scanner output and confirmed against OSV on 7 August 2026, with the fixed versions taken from the advisory records rather than assumed. **Graph attribution re-measured 8 August 2026 by resolving the application graph directly, which withdrew the third open advisory this row had published and corrected the same claim at its two other sites — `H-7` in `docs/validation-gates.md` and the `evidence` field of the plugin-graph disposition** |

---

<a id="dl-rr-12"></a>

### DL-RR-12 — The build stage's operating-system layer is one patch behind the delivered runtime's

| Field | Content |
|---|---|
| **Classification** | `RESIDUAL RISK` |
| **Severity** | Medium — three High advisories, on a stage that is **not delivered** |
| **Statement** | The image build stage and the delivered runtime stage now run **the identical JDK**, `25.0.3+9`, which is the alignment this work established. Their **operating-system layers still differ**: the runtime base is Ubuntu 24.04.4 and reports zero findings, while the build base is Ubuntu 24.04.3 and reports three High advisories — `CVE-2025-68973` in `gpgv`, and `CVE-2026-45447` in `openssl` and `libssl3t64`. All three are fixed upstream |
| **Evidence, and its provenance** | Measured 7 August 2026 with `aquasec/trivy:0.73.0` against `docker save` archives of both stages: the runtime's operating-system layer returns 0 findings; the build stage's returns 3, every one with an available fixed version. The JDK alignment was verified independently by interrogating each stage's own JVM — both report `java.runtime.version = 25.0.3+9-LTS` and `java.vm.vendor.version = Temurin-25.0.3+9` |
| **Decision** | **Scan the build stage and report it, rather than gate on it.** The delivered image remains gated on High and Critical with no suppression path; the build stage is scanned on every run and its findings are printed and uploaded as evidence |
| **Alternatives considered** | **(a) Upgrade the packages in the build stage** — requires `apt` with exact version pins against an archive that removes superseded versions, so the build would begin failing on a date nobody chose. That is non-determinism introduced to fix a determinism defect, which is the opposite of what [§0.8.1.3](docs/technical-specifications.md) asks of this build. **(b) Rebase the build stage onto the digest-pinned Temurin JDK image** — that image is Ubuntu 24.04.4 and would clear all three, and it is the recommended remedy; it is not taken here because that image ships no `curl`, `wget` or `unzip`, so the checksum-verifying Maven wrapper cannot fetch its pinned distribution there, and because changing a base image is a toolchain pin move requiring sign-off. **(c) Gate on the build stage anyway** — rejected: with (a) and (b) both unavailable to this work, the only remaining remedy would be suppression, and a gate whose sole available remedy is suppression is the failure mode `.github/workflows/build.yml` argues against in writing for the development-topology images |
| **Observable consequence** | A compiler host one Ubuntu patch behind the runtime it produces. Nothing ships from it except the application archive, which the delivered-image scan then examines in its own right |
| **Test / gate evidence** | The build stage is built with an explicit `--target`, saved and scanned by the `image-scan` job in `.github/workflows/build.yml`; the JDK equality is enforced twice — by a `RUN` assertion inside the build stage that fails the image build, and by the workflow assertion that holds continuous integration to the same build number. Recorded as `R-10` in `docs/validation-gates.md` [§12.6](docs/validation-gates.md#deferred) |
| **Remediation / follow-up** | **Owner: build maintenance.** Rebase the build stage onto the digest-pinned Temurin JDK image and supply Maven without relying on `curl` or `wget` in that image — for example by copying the distribution from the official Maven image rather than downloading it. Advancing every stage to a newer Temurin build, such as 25.0.4, is the same class of decision and is tracked with it |
| **Digest currency, and the standing obligation it creates** | A digest pin is immutable by design, so it goes stale silently: the tag it was taken from is re-pointed at each rebuild while the pin keeps naming the old image, and nothing in a build fails when that happens. Measured 9 August 2026, that had occurred on the **runtime** stage — the pinned `eclipse-temurin:25.0.3_9-jre-noble` digest was built 2 July 2026 while the same tag then resolved to a 4 August 2026 rebuild, a 33-day gap — and the `Dockerfile` pin and its `org.opencontainers.image.base.digest` label were both refreshed to the current digest. **What that refresh did and did not do is stated precisely, because the two are easy to conflate.** It re-aligned the pin with the tag; it closed no advisory. The two images were compared rather than assumed: both are Ubuntu 24.04.4, both report `Temurin-25.0.3+9`, and `dpkg-query` returns **108 packages with identical versions in both**, an empty diff — four of five layers differ, so the newer image is a rebuild rather than a package update. The **build**-stage pins were checked in the same pass and are current: the Temurin JDK digest this entry's alternative (b) names is the 4 August image already. **The obligation:** re-measure every digest in `Dockerfile` against its tag whenever this entry or `R-10` is revisited, and record the date, because the pin cannot report its own staleness and a refresh that closes nothing is still the difference between a stated provenance and a stale one |
| **Verification** | `Target-verified` — both scans and both JVM interrogations performed on 7 August 2026 against images built from the committed `Dockerfile` |

---

<a id="dl-rr-13"></a>

### DL-RR-13 — The CI runner image is pinned to a dated OS family, which bounds its drift without eliminating it

| Field | Content |
|---|---|
| **Classification** | `RESIDUAL RISK` |
| **Severity** | **Low** — the unpinned input has been removed; what remains is patch-stream drift within one OS release, in a component that supplies no part of the toolchain under test |
| **Statement** | Every job in `.github/workflows/build.yml` now names **`ubuntu-24.04`** rather than `ubuntu-latest`, at the six `runs-on` sites. That closes the silent-migration exposure: `ubuntu-latest` is an alias the platform re-points at a new OS release on general availability, so the operating system the harness runs on could change **with no diff in this repository** — the one unpinned input in a file that pins every action to a commit, every Maven coordinate to an exact version and every container image to a digest. **A dated label is not an immutable image**, and this entry exists so that distinction is disclosed rather than glossed: the platform rebuilds its dated images on a weekly cadence, so `ubuntu-24.04` on two different dates can ship different preinstalled tool versions |
| **Evidence, and its provenance** | Measured on 7 August 2026. **(1) The six sites:** `runs-on` occurs exactly six times in `.github/workflows/build.yml` — jobs `repository-integrity`, `verify`, `integration`, `image-scan`, `plugin-graph-scan` and `gate-evidence` — and a parse of the workflow reads `ubuntu-24.04` for all six, with **zero** occurrences of any `-latest` alias in the comment-stripped body. **(2) Why the drift is narrow:** nothing determining the build's output comes from the runner image — the JDK arrives from the digest-pinned setup action as an explicit distribution and feature version, Maven arrives through `./mvnw`, which verifies the wrapper's declared `distributionSha256Sum` before executing it, every dependency and plugin is an exact coordinate, and every service and scanner image is digest-pinned. The runner supplies the kernel, the shell and the container daemon. **(3) Why not `ubuntu-26.04`:** it is published as a **preview** image, and a preview base is the wrong foundation for the harness that decides whether a change is releasable |
| **Decision** | **Pin the dated family, disclose the remainder, and do not adopt either exact-reproducibility mechanism.** The two that would close it are a digest-pinned job container and an immutable self-hosted runner image |
| **Alternatives considered** | **(a) Run every step inside a digest-pinned job container** — rejected on a measured conflict rather than on effort: the `integration` job drives `docker compose` and Testcontainers against the **host** daemon, so a job container would need the daemon socket mounted into it and would relocate the very network topology those steps assert. That trades a real topology assertion for a narrower reproducibility claim, and the assertion is worth more. It is also the exact authority this repository refused elsewhere — `image-scan` was deliberately changed to hand Trivy a `docker save` archive instead of the socket, because socket access is equivalent to host root. **(b) An immutable self-hosted runner image** — rejected as out of scope and unavailable: no self-hosted runner exists for this project, and the workflow states that no self-hosted runner and no self-hosted secret is assumed anywhere. **(c) Resolve the label from a repository variable** so it is configured in one place — rejected as strictly worse: a repository variable lives **outside** the repository diff, which reintroduces the precise property being removed. **(d) Leave `ubuntu-latest` and document the risk** — rejected: the finding was raised against an input that could change without a diff, and a comment does not change that |
| **Observable consequence** | None on any build's result. The pinned family is what the alias resolves to at the time of writing, so the change is behaviour-neutral today. The accepted exposure is that a weekly image rebuild within the 24.04 family can alter a preinstalled tool version without a diff here — bounded, and unable to affect the JDK, Maven, any dependency or any container image |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/infrastructure/BuildProvenanceTest.java`, nested class `WorkflowSupplyChain`, holds both halves mechanically: `everyJobPinsADatedRunnerImage` **parses** the workflow and requires each job's label to match a dated family, then re-checks the comment-stripped body so a `-latest` alias reintroduced through a list or a matrix is caught too; `theRunnerPinDisclosesItsResidualLimit` requires the file to keep naming both mechanisms that would close the remainder, so the pin cannot come to read as exactness. Both were fault-injected on 7 August 2026 — reverting one job to `ubuntu-latest` failed the first with `repository-integrity -> ubuntu-latest`, and deleting one of the two mechanisms from the disclosure failed the second |
| **Remediation / follow-up** | Two independent follow-ups, neither urgent. **(1)** Move to `ubuntu-26.04` once it reaches general availability and the two-version support window begins to retire 24.04 — a label change at six sites plus a re-run, and the guard above accepts any dated family, so no test edit is needed. **(2)** Should the `integration` job ever stop needing the host daemon, revisit alternative (a), which would close this entry outright. Owner: whoever maintains the CI harness |
| **Verification** | `Target-verified` and `Test-verified` — 7 August 2026. The five labels were read back from a parse of the workflow rather than from a text search; the guard was fault-injected twice, each injection failing the intended assertion with the offending value named |
| **Verification note** | This entry and the authorization-scoping residual republished above as [DL-RR-10](#dl-rr-10) were authored concurrently and both were numbered `DL-RR-10`; the runner-image entry is renumbered here so that neither identifier names two entries, and so that the citations in `src/main/java/com/cardemo/config/SecurityConfig.java`, `README.md` and `H-6` of `docs/validation-gates.md` continue to resolve to the residual they name. |

---

<a id="dl-rr-14"></a>

### DL-RR-14 — An advisory on a pinned cloud coordinate that no gate in this build reports, whose only fix is a generation away

| Field | Content |
|---|---|
| **Classification** | `RESIDUAL RISK` |
| **Severity** | **Medium** — one Moderate advisory on a delivered coordinate, whose deciding precondition is absent from this application, and which **no scanner in this build reports**, so disclosure here is the only control it has |
| **Statement** | `io.awspring.cloud:spring-cloud-aws-sns:3.3.0` carries **CVE-2026-44308**: SNS notification messages delivered to an HTTP or HTTPS endpoint subscription are not signature-verified, so a party who can reach that endpoint can spoof a notification. **The entire 3.x line is affected and the only fixed release is 4.0.2**, which belongs to the next generation of the library rather than to a later patch of the pinned one. Two facts make this a disclosure rather than a fix: the advisory's precondition — an HTTP or HTTPS endpoint subscription whose notifications this application receives — **does not exist here**, and the coordinate is pinned by the migration requirements. It is recorded because **neither of this build's two scanners surfaces it**, which is precisely the case a register exists for: an advisory no gate will ever raise is one that only a written entry can carry |
| **Statement note** | The three sibling entries above concern advisories a scanner *does* report — [DL-RR-11](#dl-rr-11) the image scan, [DL-RR-09](#dl-rr-09) the dependency scan, [DL-RR-04](#dl-rr-04) a record with no published fix. This one is the opposite shape and is deliberately not folded into any of them: `spring-cloud-aws` appears nowhere in the image-scan findings, nowhere in the dependency-scan report, and nowhere in the plugin graph, so a reader looking for it in those places would conclude the build is unaware of it |
| **Evidence, and its provenance** | Measured 9 August 2026, in four independent parts. **(1) The advisory and its range:** OSV `GHSA-r4w4-wv68-qv85`, alias `CVE-2026-44308`, severity `MODERATE`, summary *"Spring Cloud AWS missing SNS message signature verification allows spoofing of HTTP/HTTPS endpoint notifications"*, with `fixed: 4.0.2` as its only fixed event. Queried per module, **only the `-sns` module matches**: `spring-cloud-aws-sns:3.3.0` returns the record, while `spring-cloud-aws-core:3.3.0` and `spring-cloud-aws-autoconfigure:3.3.0` each return zero. Queried per version, **no 3.x release clears it** — 3.3.1, 3.4.0 and 3.4.2 all still return it. **(2) Why 4.0.2 is not a patch move:** `spring-cloud-aws:4.0.2` builds on `spring-cloud-build:5.0.1`, whose `spring-boot.version` is **4.0.2**, whereas `spring-cloud-aws:3.3.0` builds on `spring-cloud-build:4.2.0`, whose `spring-boot.version` is 3.4.0. The fix therefore sits on the Spring Boot **4** generation while [§0.6.1](docs/technical-specifications.md) pins this application's parent at Spring Boot **3.5.11**. **(3) The precondition is absent, measured by search rather than asserted:** `NotificationMessage`, `SnsMessageManager`, `NotificationStatus`, `confirmSubscription` and `@NotificationSubscriptionMapping` return **zero** occurrences across `src/main`; SNS appears only in `AwsConfig`, `ObservabilityConfig`, `HealthIndicators` and `ReportSubmissionService`, all of which **publish** or health-check, and the topic's only subscriber in this topology is the SQS queue [DL-RM-12](#dl-rm-12) records `localstack-init/init-aws.sh` provisioning. There is no HTTP endpoint subscription, no notification-callback route and no signature-verification call site to be bypassed. **(4) Neither scanner reports it:** the dependency scan of `2026-08-09T15:30:31Z` lists **no** finding, active or suppressed, against any `spring-cloud-aws-*-3.3.0.jar` among its 168 dependencies, because its feed does not attribute the identifier to that coordinate; and the plugin-graph scan reads the **build plugin** graph, which this library is not on |
| **Decision** | **Disclose it here and in `H-10`, advance no pin, and add no suppression.** [§0.6.1.1](docs/technical-specifications.md) pins `io.awspring.cloud:spring-cloud-aws-dependencies` at 3.3.0 and [§0.8.4](docs/technical-specifications.md) requires pinned versions to be honoured as given with divergence recorded rather than resolved unilaterally. A suppression is not merely unnecessary but wrong here: there is no finding to suppress, and an entry matching nothing is the inert-disposition failure both `owasp-suppressions.xml` and `.github/plugin-graph/dispositions.json` are written to prevent |
| **Alternatives considered** | **(a) Advance the family to 4.0.2** — the only move that closes the advisory, and it is a **cross-generation** move by the measurement above, not a patch bump: it would carry this application onto a library line built against Spring Boot 4 while the pinned parent is 3.5.11. That is a scope decision with its own compatibility programme, and it is out of the reach of this work. **(b) Override `spring-cloud-aws-sns` alone to 4.0.2**, the narrow instrument this build uses elsewhere for parent-managed versions — rejected on the same ground [DL-RR-11](#dl-rr-11) rejects it for the Spring modules, and more strongly: the modules of this family share internal types through `spring-cloud-aws-core`, so a 4.0.2 `-sns` against a 3.3.0 core is a skew no test in this suite is designed to detect, and it trades a disclosed risk with an absent precondition for an undisclosed one. **(c) Drop the SNS dependency entirely** — it would remove the coordinate and the advisory with it, and it is rejected because the capability is delivered: `ReportSubmissionService` publishes operator notifications and `HealthIndicators` reports the topic's reachability, so removal is a functional change rather than hardening. **(d) Suppress it in `owasp-suppressions.xml`** — rejected as an inert entry, for the reason given under **Decision**. **(e) Verify signatures defensively in application code** — rejected as unreachable: there is no notification-receiving path to verify anything on, so the code would be a guard on a flow that does not exist, which is the dead-code clause B forbids |
| **Observable consequence** | **None in the delivered topology.** No endpoint subscription exists, so no unverified notification can be accepted, and nothing publishes across a trust boundary — the emulator holds the topic and the only subscriber is an in-network queue. The exposure becomes real the moment a future change subscribes an HTTP or HTTPS endpoint to the topic and acts on what arrives; at that point this entry's precondition is met and the disposition must be re-argued before that change ships |
| **Test / gate evidence** | The absence is held mechanically rather than only in prose. `src/test/java/com/cardemo/unit/infrastructure/InventoryCountGateTest.java` asserts the route census — **eight controllers publishing seventeen routes**, derived from the tree rather than remembered — so adding a notification-callback endpoint fails the build until the census and this entry are both revisited. `src/test/java/com/cardemo/unit/config/AwsConfigSecurityGuardTest.java` and `src/test/java/com/cardemo/unit/config/RuntimeWiringAndAwsIsolationTest.java` hold the emulator-only boundary the **Observable consequence** rests on. Recorded as `H-10` in `docs/validation-gates.md` [§12.2](docs/validation-gates.md#findings) |
| **Remediation / follow-up** | **Owner: build maintenance, alongside the generation decision [DL-RR-01](#dl-rr-01) already needs.** Two triggers, either of which reopens this: a 3.x release that carries the fix, which would make it a patch move and therefore actionable at once; or the decision to move generations, which closes it as a side effect. Re-measure rather than trusting this entry — it is dated, the affected range may widen, and part **(4)** in particular is a statement about a *feed*, so a feed update can turn this into a reported finding without anything in this repository changing |
| **Verification** | `Target-verified` — 9 August 2026. Every part measured rather than inferred: the advisory and its ranges read from OSV per module and per version; the generation gap read from the two published build parents; the precondition absence established by searching `src/main` for all five notification-receiving symbols; and the scanner silence read from this repository's own report rather than assumed |

---



<a id="security"></a>

## 9. Security decisions

Collected here because *Rule 1: Build Verify* clause D requires security decisions and
their risks to be stated explicitly rather than left implicit in configuration.

| # | Decision | Where | Why | Severity if reversed |
|---|---|---|---|---|
| S-1 | **The token signing key is resolved from the environment in every profile, with no committed default**, so an unset variable aborts startup rather than falling back | `src/main/resources/application.yml` and the three sibling profile files; `src/main/java/com/cardemo/security/JwtTokenProvider.java` | A hardcoded signing key lets anyone with repository access mint valid tokens. This closes a prior-implementation defect recorded as `H-1` in `docs/validation-gates.md` | **High** |
| S-2 | **Passwords are BCrypt hashes at strength 10**; the ten seeded users are stored only as hashes, and only under the local and test profiles | `src/main/resources/db/migration/V3__seed_data.sql`; `src/main/java/com/cardemo/security/CardDemoUserDetailsService.java` | The source compared plaintext — [DL-MS-14](#dl-ms-14). Profile gating means a production deployment does not ship ten accounts with a known password | **High** |
| S-3 | **A production profile exists**, with every secret externalised | `src/main/resources/application-prod.yml` | Its absence in a prior implementation is recorded as `H-2`. Without it there is no least-privilege deployment configuration and development conveniences reach production | **High** |
| S-4 | **Credentials, password hashes and government identifiers are masked in log output** | `src/main/resources/logback-spring.xml` | The customer layout carries a nine-digit government identifier and the user layout a password field, so both would otherwise reach logs — [DL-MS-16](#dl-ms-16) | **High** |
| S-5 | **Zero live cloud credentials; all interaction targets a local emulator** | `src/main/java/com/cardemo/config/AwsConfig.java`; `localstack-init/init-aws.sh` | There is no credential to over-privilege — [DL-RR-05](#dl-rr-05) | **High** |
| S-6 | **Every plugin and non-managed dependency is pinned to an exact coordinate**; no ranges, no floating versions | `pom.xml`, with the enforcer plugin asserting the toolchain floor | Pinning is what makes a vulnerability scan meaningful and a build reproducible. The one deliberate multi-coordinate case is [DL-CR-04](#dl-cr-04) | Medium |
| S-7 | **A template for local configuration is committed; the populated file is not** | `.env.example` is tracked; the populated `.env` is git-ignored and never committed | The template documents which variables exist without disclosing any value | **High** |
| S-8 | **No secret appears in this document, and that now holds without exception.** No key, token, password value or connection string is quoted anywhere in it | This file | A register that quotes a secret to explain a decision about secrets defeats itself. **An earlier revision of this row carved out one exception and quoted the fixed legacy demo password verbatim**, on the reasoning that it is public sample data in a frozen Apache-licensed corpus, is not a live credential for any system, and was needed to explain [DL-MS-14](#dl-ms-14). **That exception is withdrawn.** The reasoning was sound as far as it went but the carve-out was unnecessary: every decision that depends on the value is fully explained by *citing where it lives* — [app/jcl/DUSRSECJ.jcl:L35-L44] — and a document that says "no password value is quoted here" and then quotes one teaches the wrong habit whatever the value's provenance | Medium |

**One dependency finding was open here and is now closed on measured evidence, not
absorbed.** Between 4 and 7 August 2026 the vulnerability scan began reporting
`CVE-2026-66299` at **CVSS 7.5** against the embedded servlet container that the pinned
framework parent manages — `tomcat-embed-core` and `tomcat-embed-websocket` at
`10.1.57`, the version `pom.xml` already pins forward — which turned the CVSS ≥ 7 gate
**red** with no disposition present. **It is not a dependency-version policy question
after all, which is the correction this paragraph records:** the advisory scopes itself to
Tomcat's WebSocket **chat example**, ships only in the full distribution under
`webapps/examples`, and is absent from the embed jars — `tomcat-embed-core-10.1.57.jar`
holds 1,681 entries with **zero** matching `example`, `chat`, `webapps` or `.jsp`, and
`tomcat-embed-websocket-10.1.57.jar` holds 191 with the same zero. Pinning forward was
tried first and is unavailable: the advisory names the fix as 10.1.58 *"when released"*,
and the public registry answers **HTTP 404** for 10.1.58, 10.1.59 and 10.1.60 while its
metadata still lists 10.1.57 as the highest 10.1.x published. One Tier 2
*vulnerable-component-absent* suppression carries it, scoped to exactly the two
coordinates the scanner attributes the Tomcat identifier to, and **dated**
`until="2026-10-01Z"` so the record returns if a forward pin is still unavailable then;
its preferred exit condition is written into the entry &mdash; when 10.1.58 is published,
raise the pin and delete the entry.
The full analysis, including the measurement showing the vulnerable component is absent from
the artefact this application ships, is carried by [DL-RR-09](#dl-rr-09) and published under
[Gate 2](docs/validation-gates.md#gate-2).
Evidence: the no-skip `clean verify` recorded at `V-10c` in [§12](#verification), re-measured at
[the commit this change publishes](#evidence-commit) with report timestamp `2026-08-07T16:00:45Z`, **166 dependencies,
167 suppressed matches, one active finding at CVSS 6.7 and therefore zero at or above CVSS 7,
`BUILD SUCCESS`** with the threshold unchanged at 7 and no skip added. The earlier reading of the
same row, timestamped `2026-08-07T03:42:57Z`, is superseded.
A second finding, `CVE-2026-40977` against `spring-boot-3.5.11.jar` at CVSS **6.7**, is
reported without blocking and is named here so that clearing one pin is not read as clearing
the scan. The gate's own status is recorded in [Gate 2](docs/validation-gates.md#gate-2) and
not restated here. Owner: build maintenance, together with [DL-RR-01](#dl-rr-01), which
remains open on its own grounds — the support horizon — and is not closed by this.

---

<a id="rule-compliance"></a>

## 10. Rule 1: Build Verify — how this document complies

Exactly **one** user-specified rule governs this project: *Rule 1: Build Verify*, a global
coding and design standard in six lettered clauses. Its full text lives in the project's
rules document and is deliberately not transcribed here. Each clause is summarised in this
register's own words, with what this document does to satisfy it.

An earlier generation of the project's documentation described six separate
user-specified rules. **That is incorrect**: there is one rule with six clauses, and the
other items are prompt-level requirements. The distinction matters because it decides
which constraints originate from the project's own standards.

| Clause | What it requires | How this document satisfies it |
|---|---|---|
| **A — Engineering principles** | Correctness, determinism and explicit behaviour ahead of cleverness; security-conscious defaults; maintainability; observability; justified trade-offs | Every entry states an explicit decision, the alternatives rejected and the observable consequence, so behaviour is explicit rather than implied. Determinism is the organising principle: [§5](#named) exists precisely because those are the places where an implicit assumption produces silently different results. Observability decisions are recorded at [DL-MS-16](#dl-ms-16), and the one efficiency trade-off that changes behaviour at scale is justified in writing at [DL-DV-03](#dl-dv-03) rather than taken silently |
| **B — Code quality** | No dead code and no deferred work without an owner or tracking reference; explicit boundary handling; no swallowed errors; tests for core logic; documented interfaces | **This document is the tracking reference** that makes every intentionally retained artefact compliant: the conflict is resolved at [DL-CR-01](#dl-cr-01) and the artefacts themselves are enumerated individually in [§15](#noop-registry), whose membership rule is mechanical and whose completeness the gate harness asserts as a two-way equality with the tree. **Two earlier readings of this cell are withdrawn** — that three artefacts were the whole set, and that the set was closed at three. It contains **zero placeholder *uses*** — every occurrence of a `TODO`, `TBD` or `FIXME` token in this file is a sentence naming the prohibition or the command that checks it, never a deferred item, and [§12](#verification) publishes the exact count and location of each. Boundary conditions are recorded where the source handles them: the empty-file identifier path at [DL-PP-04](#dl-pp-04) and the three accepted not-found paths at [DL-MS-11](#dl-ms-11). **One boundary the source does NOT handle is recorded elsewhere on purpose, and an earlier revision of this row mis-attributed it here to [DL-PP-07](#dl-pp-07), which is about the interest job's output target and not about this at all.** The interest program's `ELSE PERFORM 1050-UPDATE-ACCOUNT` at [app/cbl/CBACT04C.cbl:L219-L220] is **unreachable** beneath the test-before `PERFORM UNTIL` at [:L188], so the last account of the browse never has its interest posted or its cycle counters reset. That is preserved rather than repaired, and it is analysed where the control flow is - `InterestCalculationProcessor`'s own PARITY TRAP 1 javadoc, `docs/technical-specifications.md` §0.7.3.3, and the `TM-CBACT04C-R008` fidelity note in `TRACEABILITY_MATRIX.md` - rather than in the retained-no-op register, whose membership rule at [§15.1](#noop-membership) it does not meet: it is an unreachable `PERFORM` of a paragraph that is **not** empty, so registering it would misreport the paragraph as a no-op and hide the actual defect. It now also has an entry of its own as a preserved legacy defect at [DL-LD-09](#dl-ld-09). Nothing-swallowed is the substance of [DL-MS-11](#dl-ms-11). Every entry names its tests or says they are unavailable |
| **C — Repository hygiene** | Follow existing conventions where present, never fight existing style; deterministic; consistent structure; avoid duplication | This file opens with the **universal Apache-2.0 banner** that every source file in four of the five legacy directories carries, extended per repository convention to name the artefacts it derives from — and it matches the banner form of its sibling `docs/validation-gates.md` exactly. **Duplication is actively avoided**: [§1.9](#not-this) states what belongs elsewhere, and gate results, risk-table rows and paragraph mappings are **cited by identifier rather than copied**. No formatter or linter configuration existed in the repository to inherit, so conventions were established rather than overridden — and [DL-RR-08](#dl-rr-08) declines a 31-file rewording specifically to keep this change focused |
| **D — Security standards** | No secrets in code, logs, tests or configuration; pin dependencies; least privilege | [§9](#security) collects eight security decisions with the exposure each closes. **No secret is quoted in this document** — `S-8` states that explicitly and justifies the single public sample credential that is named. Pinning is `S-6`; least privilege is `S-1` through `S-5` |
| **E — Documentation standards** | Every component documented: what it does, how to run, build and test it, key configuration and defaults, common failure modes and troubleshooting | [§1.1](#what-this-is) states what this document does; [§1.11](#verify-this) and [§12](#verification) give the literal commands that verify it and their honest results; [§1.3](#identifiers) through [§1.8](#template) are its configuration and defaults; [§1.10](#update-policy) is its operating procedure. **Failure modes** are the substance of the register itself — every entry's *Observable consequence* is a failure mode, and every *Remediation* is its troubleshooting step. Broader troubleshooting lives in `docs/validation-gates.md` §13 and is not duplicated |
| **F — Output requirements** | Evidence-based citation; severity classified as Blocker, High, Medium or Low; clear remediation; state "Not available" and list what is needed when information is missing | **Every** claim about the source carries an `app/<path>:L<n>` locator, and [§12](#verification) verifies mechanically that each resolves. **Every** entry carries a severity from the four required levels and a named remediation with an owner. Missing information is stated in the **exact** mandated form in **one** evidence field — the concurrency measurement at [DL-RR-04](#dl-rr-04) — followed by its specific prerequisite, plus the definition of the form itself at [§1.7](#no-invention). Three more stood and have since been **closed by producing the evidence**, which is the disclosure discipline working rather than an exception to it: [DL-RR-07](#dl-rr-07) when the matrix it named was authored, and both the vulnerability-scan field at [DL-RR-01](#dl-rr-01) and the open dependency finding at the end of [§9](#security) when the no-skip scan of `V-10c` in [§12](#verification) was run, captured and dated. Beyond those, unproven items are disclosed through the *Gate-pending* status and through gate references that cite the anchor and leave the result to the ledger that owns it, so **no gate is reported as passed** anywhere in this document |

**Two places where information is genuinely unavailable, stated plainly rather than
filled.** Clause F's final requirement is the one most easily glossed over, so both are
named together: the program behind transaction `CDV1` **does not exist** anywhere in the
repository ([DL-RR-03](#dl-rr-03)); and **no service-level objective exists** in the
source, which is why [Gate 3](docs/validation-gates.md#gate-3) is framed as a measurement
rather than a threshold ([DL-RR-02](#dl-rr-02)). Neither is filled with an invention.

A third stood here — the paragraph-level matrix owed at `TRACEABILITY_MATRIX.md` — and it
is named because it is the useful half of the record: the disclosure was made while the
artefact was absent, the absence was verified rather than assumed, and it was then closed
by authoring the artefact rather than by softening the sentence. That closure is
[DL-RR-07](#dl-rr-07).

---

<a id="coverage"></a>

## 11. Coverage index

Every requirement this register was obliged to carry, mapped to the entry that carries it.
The table is the mechanical proof of coverage; it is not a summary.

### 11.1 The sixteen binding transformation families

| # | Family | Entry |
|---|---|---|
| 1 | Signed fixed-point numerics → `BigDecimal` / `NUMERIC`, `HALF_EVEN`, `compareTo` | [DL-MS-01](#dl-ms-01) |
| 2 | Paragraph / section → one private method with a locator Javadoc | [DL-MS-02](#dl-ms-02) |
| 3 | Copybook → import, with the three collapse rules | [DL-MS-03](#dl-ms-03) |
| 4 | VSAM clusters and alternate indexes → entities, finders, B-tree indexes | [DL-MS-04](#dl-ms-04) |
| 5 | `SEND MAP` / `RECEIVE MAP` → DTO-backed REST operations | [DL-MS-05](#dl-ms-05) |
| 6 | COMMAREA and `XCTL` → stateless JWT claims and URL routing | [DL-MS-06](#dl-ms-06) |
| 7 | JCL programs, DD, `COND` → jobs, steps, deciders, split flow | [DL-MS-07](#dl-ms-07) |
| 8 | DFSORT and `REPRO` → `Comparator` and batched JDBC, no external sort | [DL-MS-08](#dl-ms-08) |
| 9 | `TDQ(JOBS)` 80-byte cards → one typed FIFO message | [DL-MS-09](#dl-ms-09), [DL-MS-18](#dl-ms-18) |
| 10 | GDG generations → versioned, prefixed objects | [DL-MS-10](#dl-ms-10) |
| 11 | `FILE STATUS` guard → typed exceptions, all three accepted control paths | [DL-MS-11](#dl-ms-11) |
| 12 | `SYNCPOINT` → Spring transactions | [DL-MS-12](#dl-ms-12) |
| 13 | `CSUTLDTC` / `CEEDAYS` → `LocalDate` plus an outcome-compatible service | [DL-MS-13](#dl-ms-13) |
| 14 | Plaintext user records → BCrypt strength 10 | [DL-MS-14](#dl-ms-14) |
| 15 | `READ UPDATE` plus snapshot → `@Version` **plus** explicit comparison | [DL-MS-15](#dl-ms-15), [DL-PP-02](#dl-pp-02) |
| 16 | CICS identity, `DISPLAY`, file-availability jobs → correlation, metrics, health | [DL-MS-16](#dl-ms-16) |
| — | One modular monolith, not microservices | [DL-AR-01](#dl-ar-01) |

### 11.2 The twenty-five named decisions

| # | Named decision | Entry |
|---|---|---|
| 1 | Account-update rollback asymmetry in one transactional method | [DL-PP-01](#dl-pp-01) |
| 2 | Two-layer concurrency: `@Version` plus snapshot comparison | [DL-PP-02](#dl-pp-02) |
| 3 | Exactly five reject codes; 109 reachable but never consumed | [DL-PP-03](#dl-pp-03) |
| 4 | Posting atomicity closes the orphan-write hazard — labelled DEVIATION | [DL-DV-01](#dl-dv-01) |
| 5 | Identifier race retained; no database sequence | [DL-PP-04](#dl-pp-04) |
| 6 | Empty `1400-COMPUTE-FEES` retained as a reachable no-op | [DL-PP-05](#dl-pp-05) |
| 7 | `ALTER` dispatch eliminated by static flow analysis | [DL-DV-02](#dl-dv-02) |
| 8 | 510-transaction ceiling removed — labelled DEVIATION | [DL-DV-03](#dl-dv-03) |
| 9 | Report retention conflict resolved to 10 | [DL-CR-02](#dl-cr-02) |
| 10 | Flyway migration filenames: canonical set plus alias, no duplicates | [DL-CR-03](#dl-cr-03) |
| 11 | Coverage plugin pinned at 0.8.12, with a documented Java 25 exception | [DL-CR-04](#dl-cr-04) |
| 12 | Framework support horizon — date-aware, externally verified | [DL-RR-01](#dl-rr-01) |
| 13 | Statement projection truncates two timestamp bytes, reproduced | [DL-PP-06](#dl-pp-06) |
| 14 | Interest output is a fresh sequential generation, not the table | [DL-PP-07](#dl-pp-07) |
| 15 | Position-aware overpunch decoding, never global substitution | [DL-PP-08](#dl-pp-08) |
| 16 | Sign-on upper-cases both identifier and password | [DL-PP-09](#dl-pp-09) |
| 17 | Report submission collapses 17 cards to one typed message | [DL-MS-18](#dl-ms-18) |
| 18 | The file-and-operation strategy map belongs in `FileService` | [DL-DV-04](#dl-dv-04) |
| 19 | An unimplemented file operation is refused, not answered with a stale status | [DL-DV-09](#dl-dv-09) |
| 20 | An unknown DD name is refused, not answered with the caller's pre-set success | [DL-DV-07](#dl-dv-07) |
| 21 | A sealed snapshot token carries the update guard the COMMAREA held | [DL-DV-08](#dl-dv-08) |
| 22 | The confirm turn refuses an omitted payload member the source would have written | [DL-DV-06](#dl-dv-06) |
| 23 | `SERIALIZABLE` execution creation versus the parallel split, resolved by a bounded launch retry | [DL-CR-08](#dl-cr-08) |
| 24 | Low-severity source behaviours reproduced verbatim rather than normalised | [DL-PP-13](#dl-pp-13) |
| 25 | The boundaries where a Rule 1 standard, or the absence of a behaviour to reproduce, overrides parity | [DL-DV-10](#dl-dv-10) |

Five further decisions from the same section are recorded here because the same reasoning
applies and omitting them would leave a gap a reviewer would have to rediscover: the
transcribed sign branch and over-limit formula at [DL-PP-10](#dl-pp-10), the two numeric
parsers at [DL-PP-11](#dl-pp-11), the four-trailing-zero timestamp at
[DL-PP-12](#dl-pp-12), the ingestion ordinal that replaces a sequential record's position
at [DL-MS-17](#dl-ms-17), and the added user-type guard at [DL-DV-05](#dl-dv-05). With the
twenty-five rows above, that accounts for every entry in [§5](#named).

### 11.3 Preserved legacy defects and quirks

| Defect or quirk | Entry | Cross-reference |
|---|---|---|
| Corrupted `STMTFILE` data-definition line | [DL-LD-01](#dl-ld-01) | `D-1` |
| Markup record length 80 versus 100; writer stays at 100 | [DL-LD-02](#dl-ld-02) | `D-2` |
| Internal procedure name differs from the member name | [DL-LD-03](#dl-ld-03) | `D-3` |
| Sequential unguarded checks, so 103 overwrites 102 | [DL-LD-04](#dl-ld-04) | `Q-1` |
| Control break on card number under an `Account Total` label | [DL-LD-05](#dl-ld-05) | `Q-2` |
| No self-delete guard, and none invented | [DL-LD-06](#dl-ld-06) | `Q-3` |
| Redundant index assignment preserved | [DL-LD-07](#dl-ld-07) | — |
| Source misspellings that are field or literal contracts | [DL-LD-08](#dl-ld-08) | `L-1`, `D-6` |
| Unreachable end-of-file account flush; the last account's interest is lost | [DL-LD-09](#dl-ld-09) | `D-7` |
| N&minus;1 `Account Total` lines, and none for a single card | [DL-LD-10](#dl-ld-10) | `D-8` |
| The last record's amount double-counted into the page and grand totals | [DL-LD-11](#dl-ld-11) | `D-9` |
| `NEXT SENTENCE` ends the report's read loop, truncating the report | [DL-LD-12](#dl-ld-12) | `D-10` |
| A customer-lock failure reported as success over an empty write | [DL-LD-13](#dl-ld-13) | `D-11` |

### 11.4 Scope statements and factual corrections

| Requirement | Where |
|---|---|
| In-place `src/` beside a frozen `app/`; package `com.cardemo`; 28 programs / 19,254 lines; 17 endpoints; 10 clusters + 3 alternate indexes and paths; 29 JCL members including the uppercase `CREASTMT.JCL`; 9 fixtures; `dailytran.txt` | [§2.1](#shape) |
| Six factual corrections against the technical specification, plus the `.CBL` case correction | [§2.2](#corrections) |
| Exclusions: `CDV1`/`COCRDSEC`, `UNUSED1Y`, EBCDIC parsing, `samples/`, any front end, microservices, Kubernetes, live cloud accounts, deferred hardening | [§2.3](#exclusions), [DL-RR-02](#dl-rr-02), [DL-RR-03](#dl-rr-03) |
| Dated environment evidence: a container runtime available, no host JDK or build tool at that reading | [§2.4](#environment) |
| The one conflict between the coding standard and the parity mandate | [DL-CR-01](#dl-cr-01) |
| The 27-file divergence between the plan's summary file counts and the delivered production tree, sanctioned in four named groups | [DL-CR-06](#dl-cr-06) |
| Why the delivered test tier conforms to a pattern-based schema that publishes no file count | [DL-CR-07](#dl-cr-07) |
| The one High-severity CVE with no published fix, answered by a dated suppression and still disclosed | [DL-RR-09](#dl-rr-09) |

---

<a id="verification"></a>

## 12. Verification of this document

This section records what was actually run against this revision and what it actually
returned. It is deliberately specific: a register that asserts its own correctness without
publishing the check is asking to be believed rather than verified.

<a id="selfcheck-parser"></a>

### 12.1 Checks run, and their results

**Every structural count below is the output of one of the two parsers published here, and
none of them is maintained by hand.** That distinction is the whole point of this
sub-section. An earlier revision published figures alongside commands that did not produce
them — a register asserting its own correctness with an unreproducible number is worse than
one that publishes no number at all, because the number invites belief. Both parsers are
runnable as written, from the repository root, and
`src/test/java/com/cardemo/unit/infrastructure/InventoryCountGateTest.java` re-derives their
results on every build and fails if a figure in this section stops matching the tree.

That guard reads the figures **out of this document** rather than holding its own copies, so
adding an entry or a citation does not require a test edit — it requires this section's
numbers to be brought up to date, and until they are the build fails and names the
discrepancy. It covers [V-1](#verification), [V-2](#verification), [V-3](#verification),
[V-5](#verification), [V-6](#verification), [V-10b](#verification) and
[V-11](#verification) — every row here whose result is a structural count of this file or of
the Java tree. The rows it does not cover are the ones whose result is the record of a
command that was run at a point in time — the build, scan and site rows — and those carry a
measurement date instead. Both parsers were proven non-vacuous by fault injection: altering
a published figure by one fails the guard and the failure message names the figure, the
parser's own result and, for the census, the exact file list behind it.

<a id="evidence-commit"></a>

**The commit every dated row below describes, published once.** Each row that records a
command rather than a structural count cites this marker instead of restating a commit, so
there is exactly one place to change when the evidence is refreshed and no possibility of
two rows describing different trees while appearing to describe one.

| Property | Value |
|---|---|
| Commit | `c752d4e52ad52268e6c9b2b17e840aa026eea38d`, short `c752d4e5` — **the parent of the revision that publishes this register**, superseding `faf8dc78be8309cb6a972772624934086d9cecaa` which held that position when the previous revision published it. The figures below were measured on this revision’s own tree; see *Reachability* |
| **Reachability** | **The name above resolves from any clone of this branch, and the tree it names is the parent of the one measured — both halves matter.** A document cannot cite the name of the commit that contains it: writing the stamp changes the tree, so a self-citation is unsatisfiable by any commit. Naming the parent keeps the citation resolvable, which is the property that failed before, when this cell quoted `4a4ad1c9` — an object no clone can resolve, so a citation that was not evidence. **No short internal object name is quoted for the measured tree itself**, because the revisions these figures were first measured at are not part of the published history and quoting one would repeat exactly that defect. The harness pins the measured tree independently and at run time — `target/gate-verification/gate-verification-evidence.properties` records `gate.harness.commit`, read from the working tree’s own git metadata as the file was written, and `.github/workflows/build.yml` reports a Blocker when it differs from the commit the job checked out, so an artefact from another commit is detectable rather than merely dated |
| Command | `./mvnw -B -ntp clean verify` — the full invocation with **no skip flag of any kind**, so the compile, all three test tiers, the coverage floor, the toolchain floors and the vulnerability scan all execute |
| Result | `BUILD SUCCESS`, exit 0, **11:13 min**, started `2026-08-09T16:31:37Z` and finished `2026-08-09T16:42:50Z`, **19 goals** with `dependency-check:12.1.0:check` among them. *Superseding the same-day run of 11:19 min finishing `2026-08-09T09:50:02Z`, which was exact for the tree before this revision's six added unit cases* |
| Tests | **15,098** unit across 212 suites, **804** integration across 35, and **115** end-to-end across 3 — of which the gate harness is **64** — for **919** in the Failsafe tier; **0 failures, 0 errors, 0 skipped** everywhere. Re-measured Sunday, 9 August 2026; the same-day reading of 15,092 unit cases and the 8 August reading of 14,917 are both superseded. The last six cases are this revision's boundary-refusal hardening — three added to `RequestBoundaryHardeningTest` and one `@ParameterizedTest` over three request targets added to `ProblemJsonErrorBoundaryTest` — and they landed in suites that already existed, which is why the suite counts are unchanged; the additions before them were this checkpoint's field-width unit contract, its two batch span-naming suites and assertions landed in existing suites. Counted from `testcase` elements, never from suite attributes, for the reason [V-11](#verification) gives. **Suite reconciliation: 250 concrete suites in `src/test/java`, 250 reports collected**, so no suite silently failed to run |
| Warnings | **Zero compiler warnings** — the property the gate actually asserts, and it is run-independent. The only `[WARNING]` lines come from the scan plugin and their *number* is not: this run emitted **one**, the banner announcing it has findings to report, while a run that also refreshes the advisory feed emits **a second** noting the deliberately unset optional NVD API key. Neither is a diagnostic, so no figure for the total is published as though it were stable |
| Coverage | LINE **0.914594** — `missed 2,112, covered 22,617, total 24,729` over a 350-class bundle — against the enforced 0.80 floor; BRANCH 0.809738; METHOD 0.978114. Superseding the 8 August reading of 0.915384 over 343 classes and the 7 August reading of 0.916413 over 340 |
| Scan | Engine 12.1.0, report `2026-08-09T16:42:42Z`: **168** dependencies, **one** active finding — `CVE-2026-40977` on `spring-boot-3.5.11.jar` at CVSS **6.7**, below the gate of 7. Unchanged in shape from the same-day `2026-08-09T09:49:54Z` report it supersedes, and re-measured here because the scanner's own HTTP transport moved in this revision — `dependency-check.httpcore5.version` 5.3.6 → 5.4.3 — so the reading has to be taken over the new transport rather than inherited. Read out of `dependency-check-report.json`, not from console text. The 7 August report at `2026-08-07T16:00:45Z` read the same one finding over the same 166 dependencies |
| Retained artefacts | `target/dependency-check/dependency-check-report.{html,json,sarif}`, `target/gate-verification/gate-verification-{evidence,summary}.properties`, `target/surefire-reports/`, `target/failsafe-reports/`, `target/site/jacoco/` |
| **What changed after the run** | **Documentation only — this sub-section and the other evidence owners that record the run.** A document that publishes its own verification cannot be inside the tree that was verified, so this is stated rather than left as a gap for a reader to notice. The figures were re-confirmed by re-running the same command on the tree carrying these edits, and they are identical, so they hold for both. No file under `src/`, no build descriptor and no infrastructure file changed after the run |

**The commit `4a4ad1c9fefc0858eba8bc4d1412eac3f672c0f1` is withdrawn wherever this register
or any other evidence owner cited it.** It was published as the exact-HEAD commit of a
verification run, and it does not exist: `git cat-file -t 4a4ad1c9` fails with *not a valid
object name*. Nothing about the run it described could be reproduced or even located, which
makes every figure attributed to it unverifiable rather than merely stale. It is replaced by
the reachable commit above together with results re-measured at that commit, never by a
re-attribution of the old figures to a new commit. Where an earlier reading is superseded it
is marked as superseded rather than deleted, so the direction of travel stays auditable.

**Parser 1 — the entry-scoped structural pass.** Every row that counts entries or their
fields is scoped from each `### DL-…` heading to the next heading, so a bolded label
anywhere else in the file cannot inflate a field count. That scoping is what an earlier
revision lacked, and it is why the earlier field counts disagreed with the entry count:

```bash
python3 - <<'EOF'
import re, collections
lines = open('DECISION_LOG.md', encoding='utf-8').read().splitlines()
hdr = re.compile(r'^###\s+(DL-[A-Z]+-\d+)\b')
lbl = re.compile(r'^\|\s*\*\*(.+?)\*\*\s*\|')
idx = [(i, hdr.match(l).group(1)) for i, l in enumerate(lines) if hdr.match(l)]
mandatory = ('Classification', 'Severity', 'Verification')
have = collections.Counter(); varying = collections.Counter(); gaps = []
for k, (i, name) in enumerate(idx):
    end = idx[k + 1][0] if k + 1 < len(idx) else len(lines)
    labels = [lbl.match(l).group(1) for l in lines[i:end] if lbl.match(l)]
    for field in mandatory:
        if field in labels:
            have[field] += 1
        else:
            gaps.append((name, field))
    rest = [l for l in labels if l not in ('Classification', 'Severity')]
    varying[rest[0] if rest else '<none>'] += 1
print('entries', len(idx))
for field in mandatory:
    print(' ', field, have[field])
print('missing', gaps or 'none')
for field, n in varying.most_common():
    print(' varying', n, field)
EOF
```

**Parser 2 — the forward-reference census.** The convention it counts is written in Javadoc,
which line-wraps, so the phrase is routinely split across two physical lines and **no
single-line `grep` can count it**. That is precisely why the figure an earlier revision
published was unreproducible: the command it gave matched only the occurrences that happen
to sit on one line. Joining comment continuations first is not a refinement, it is the
minimum correct reading:

```bash
perl -0777 -ne 's/\n\s*(?:\*|\/\/)\s?/ /g;
  $n++ while /owed\s+an\s+entry\s+in\s+(?:the\s+planned\s+)?(?:\{\@code\s+)?(?:DECISION_LOG|TRACEABILITY_MATRIX)\.md/gi;
  END { print "$n\n" }' $(find src/main src/test -name '*.java' | sort)
```

| # | Check | Command | Result |
|---|---|---|---|
| V-1 | Every cited `app/…` path resolves, with its exact case | `grep -oE '\bapp/[A-Za-z0-9_./-]+' DECISION_LOG.md \| sed 's/[.,:)]*$//' \| sort -u` then test each with `[ -e … ]` | **PASS** — **61** distinct paths, **0 missing**, re-measured Friday, 7 August 2026. This is the check that catches the `.CBL` case trap and the `dailytran.txt` spelling. The count rose from the 55 an earlier revision published because entries added since cite four further paths; the figure is the command's output, not a remembered one. **One qualification, and it applies to the resolution half of this row rather than to the count.** The count is parsed from this file and is therefore the same everywhere; resolution reads disk, and `.dockerignore` prunes the single out-of-scope subtree `app/data/EBCDIC/` from the Docker build context, where the `Dockerfile` runs the unit tier. Citations of that one subtree are consequently resolved only where the subtree itself exists — in a clone, in continuous integration, in any developer build, which is where the 0 above was measured — and are passed over only inside a context carrying none of it. The exemption is keyed on the directory, never on a file, so a mistyped dataset name still fails wherever the data is present; `InventoryCountGateTest` asserts that shape, that the prefix has not widened, and that citations of it still exist to protect |
| V-2 | Every **linked** repository path resolves; every bare mention resolves | the same construction over the `src/`, `docs/`, `observability/` and `localstack-init/` prefixes, plus a separate pass over markdown link targets only | **PASS, with no remaining absence.** Of **485** markdown link targets, **133** are repository-relative uses spanning **7** distinct paths, and **all of them resolve — 0 broken links**, which was **not** true before this reading: two pointed at a cross-document anchor the target does not define, and both were repointed. Of **145** distinct bare path mentions, **143** are concrete paths and **all 143 resolve**; the remaining two are wildcard patterns, which name a tree rather than a file and so cannot be tested with `[ -e … ]`. Re-measured Sunday, 9 August 2026, the total having risen by **fourteen** when [DL-RR-14](#dl-rr-14) was added: **ten** in the entry itself — six anchor links and four repository-relative uses, none of them a new distinct path, so the span stays at 7 — and **four** more when the rows of this sub-section were re-pointed at it. **A figure of 481 was carried here mid-edit and is withdrawn with the rest**, for a reason this row is the right place to record: a self-referential count has to be taken *after* the cross-references that cite the new entry, not before, or the act of publishing it moves it. The two bare mentions that rose are the guard classes that entry cites, `src/test/java/com/cardemo/unit/config/AwsConfigSecurityGuardTest.java` and `src/test/java/com/cardemo/unit/config/RuntimeWiringAndAwsIsolationTest.java`, both of which resolve. **The 471, 129, 143 and 141 this row published before that entry existed are withdrawn**, and they are withdrawn for the ordinary reason rather than a defect: an entry was added and the figures were re-derived rather than left to rot. Previously re-measured Saturday, 8 August 2026, the total having risen by seventeen when [DL-CR-11](#dl-cr-11), [DL-RM-14](#dl-rm-14) and [DL-RM-15](#dl-rm-15) were added and the rows here were re-pointed at them; the **467** this row published a moment earlier is withdrawn for the same reason the anchor count was — it was derived before the third entry and its cross-references existed; the three figures an earlier revision published — 282, 89 and 111 — are withdrawn, as is a fourth, **141**, which this row published for the bare mentions: the rule behind it was never stated, and the stated rule above yields 140 with two of them un-testable, which is the figure a reader can reproduce, and the uses are now reported separately from the distinct paths because most of the 116 are the same gate ledger reached through a different anchor. Two things changed since this row read "one declared absence": `docs/onboarding-guide.md` was authored, so it is now linked from [§13](#closing) rather than named as an obligation; and the re-measurement found a **genuine** unresolved mention this row had not caught — a guard class cited under `unit/infrastructure/` that actually lives under `unit/model/` — which has been corrected in [§12.2](#premise) rather than excused |
| V-3 | No internal link is broken | extract `<a id="…">` targets and `](#…)` uses, then `comm -23` | **PASS** — **134** anchors defined, **119** distinct anchors linked, **0 unresolved**, re-measured Sunday, 9 August 2026: [DL-RR-14](#dl-rr-14) added the one new anchor, and the **linked** total moved by one with it — not because that entry links anything new, since every anchor it points at was already linked from elsewhere, but because the four rows of this sub-section now cite the entry itself. **The 133 and 118 published before it existed are withdrawn**, for the ordinary reason: an entry was added and both figures were re-derived after the cross-references, which is the only order in which a self-referential count is stable. Previously re-measured Saturday, 8 August 2026. The 113 and 96 an earlier revision published, the 123 and 106 that superseded them, the 113 that superseded those, the 130 and 114 that superseded those, and the **132** this row published moments ago, are all withdrawn: the anchors added since are this sub-section's own and those of the entries added since — [DL-CR-11](#dl-cr-11), [DL-RM-14](#dl-rm-14) and then [DL-RM-15](#dl-rm-15), the accepted legend limitation, each carrying its own anchor. The 132 was wrong for a reason worth naming rather than quietly correcting: it was written before the third entry existed and was not re-derived afterwards, which is precisely the hand-maintained-number failure this whole sub-section exists to catch, and `InventoryCountGateTest` caught it |
| V-4 | No cross-document link into the gate ledger is broken | the same construction against `docs/validation-gates.md` | **PASS, re-measured Saturday, 8 August 2026** — **16** distinct anchors used (`#gate-1`…`#gate-8`, `#summary`, `#env-first`, `#findings`, `#legacy-defects`, `#quirks`, `#deviations`, `#deferred`, `#rule-1`), **all 16 defined in the target**. **The earlier reading is withdrawn**: it published 14 and listed `#env-second`, and it asserted all were defined when **two links pointed at `#register-findings`, an anchor the target does not define** — §12.2 is anchored `#findings`. Both were repointed in the same reading that found them. The lesson is the one [DL-RM-10](#dl-rm-10) records for line locators, in its cross-document form: this row's claim was true when written and had no guard to keep it true |
| V-5 | No identifier is defined twice | `grep -oE '<a id="[a-z0-9-]+"></a>' … \| sort \| uniq -d` | **PASS** — no duplicates |
| V-6 | No placeholder survives | `grep -nE '\b(TBD\|TODO\|FIXME\|XXX)\b' DECISION_LOG.md` | **PASS with a published hit list.** **Seven** hits, and every one is prose about the prohibition rather than an instance of it: two in [§1.7](#no-invention) (the sentence that forbids them), one in [§1.11](#verify-this) and one in [§12.1](#verification) (the checking command, quoted twice), one in [§10](#rule-compliance) (the clause-B row), and one each in [DL-PP-05](#dl-pp-05) and [DL-CR-01](#dl-cr-01), where the phrase *"a bare marker would breach the clause for real"* is the argument being made. **Zero deferred items** |
| V-7 | No secret appears in the file | pattern scan for provider key prefixes, private-key headers, bearer tokens, basic-auth URLs and any `key`/`secret`/`token`/`password` assignment; plus a self-derived pass for the fixed legacy demo value that never spells it out — `PW=$(awk 'NR==35 {print substr($0,49,8)}' app/jcl/DUSRSECJ.jcl); grep -c "$PW" DECISION_LOG.md`, which reads the eight-byte `PWD` field straight out of the frozen record at its cited locator and **must print 0** | **PASS — and now with no carve-out.** No secret material, and **no credential value is quoted at all**: the fixed legacy demo password is referred to by that description and cited to [app/jcl/DUSRSECJ.jcl:L35-L44]. An earlier revision of this row recorded a PASS that named the value explicitly as a justified exception; the exception is withdrawn — see `S-8` in [§9](#security). No value from the git-ignored local environment file is quoted anywhere |
| V-7a | Every Java symbol this document names exists, with the stated shape | targeted `grep` for each of 59 cited symbols in the file it is attributed to — the paragraph-numbered methods, the five reject constants and their four length constants, the two transactional boundaries, the three build coordinates and the three migration filenames | **PASS** — 59 of 59 resolve, **0 invented symbols**. Every test class named in this document was likewise confirmed present before being cited |
| V-7b | Every line-number citation says what this document claims | `sed -n '<n>p' <file>` against the frozen corpus for a 78-citation sample spanning all eleven cited programs, the six cited jobs, the procedure, the CSD, the catalogue, a copybook and a fixture | **PASS** — 78 of 78 match, including the corrupted line at [app/jcl/CREASTMT.JCL:L90], the two conflicting `LIMIT` declarations, the date-of-birth offset at [app/cbl/COACTUPC.cbl:L4177] and the `Account Total` literal in the copybook |
| V-8 | The guard that owns this file's existence premise passes | `./mvnw -B -ntp -o test -Dtest=DocumentationConsistencyTest -Djacoco.skip=true` | **PASS after a deliberate revision.** Creating this file falsified the guard's premise, exactly as the guard predicted it would. Recorded in full at [§12.2](#premise) |
| V-9 | The honesty guards still pass | `./mvnw -B -ntp -o test -Dtest='EvidenceHonestyTest,SourceCitationResolutionTest' -Djacoco.skip=true` | **PASS** — and one intermediate failure of my own making was fixed rather than worked around; see [§12.2](#premise) |
| V-10 | The whole unit tier still passes | `./mvnw -B -ntp -o test -Djacoco.skip=true -Ddependency-check.skip=true` | **PASS** — **15,098 tests, 0 failures, 0 errors, 0 skipped** across **212** suite reports, `BUILD SUCCESS`, re-measured Sunday, 9 August 2026 on [the tree this change publishes](#evidence-commit), superseding the same-day 15,092 and the 14,917 of 8 August. This is the check that proves a guard revision broke nothing else. The **14,461** an earlier revision published is withdrawn: it predates the guards added since, and the count is a property of the test tree rather than a contract — it is published so a reader can reproduce the same number, not so it can be held fixed |
| V-10a | The whole module builds and both test tiers pass, with the project's own gates intact | `./mvnw -B -ntp -Ddependency-check.skip=true clean verify` | **PASS** — `BUILD SUCCESS`, re-measured Friday, 7 August 2026 at [the commit this change publishes](#evidence-commit). This row records the invocation routine local builds use, and its **tier figures are those of [V-10c](#verification) rather than a second set**: the two commands differ only in whether the scan goal executes, so publishing independent counts for each would invite the two to drift while describing one tree. Both tiers pass with 0 failures and 0 errors; **zero compiler warnings**, so the zero-warning gate held; the coverage gate reported *all coverage checks have been met* under the pinned 0.8.12 plugin of [DL-CR-04](#dl-cr-04); the deployable JAR was produced. The integration tier ran against real containers, which the runtime availability recorded in [§2.4](#environment) is what made possible. **The vulnerability scan was skipped for this invocation, so this row is not evidence about it** — [V-10c](#verification) is, and it is the row that closes Gate 2. The earlier reading of 14,461 unit and 845 integration cases in about five and a half minutes is superseded |
| V-10b | Every entry carries the three mandatory fields, and the varying field varies only as declared | **Parser 1**, published above | **PASS** — **95 entries**, and every one of them carries all three mandatory fields: *Classification* **95 / 95**, *Severity* **95 / 95**, *Verification* **95 / 95**, with **`missing none`**. **71** open with *Source evidence*. The **24** that do not are the **eleven** conflict resolutions of [§7](#conflicts), each opening with *The two requirements*, and **thirteen of the fourteen** residual risks of [§8](#risks), opening with *Decision*, *Statement* or *Status of the concern* — the substitutes declared in [§1.8](#template). The fourteenth, [DL-RR-03](#dl-rr-03), opens with *Source evidence* because it has one. **An earlier revision of this row published "81 entries" against field counts of "56 / 56 / 56" and a "44 / 12" split; all four figures are withdrawn.** They disagreed with each other because the check behind them was not entry-scoped — it counted bolded labels across the whole file rather than within each entry — which is the exact defect Parser 1 exists to remove. The figures then stood at 82 with a 66 / 16 split until [DL-CR-09](#dl-cr-09) was added, at 83 with a 66 / 17 split until the authorization-scoping residual now at [DL-RR-10](#dl-rr-10) was added, and at 84 with a 66 / 18 split until the remaining concurrently-authored entries — [DL-CR-10](#dl-cr-10), [DL-RM-13](#dl-rm-13) and [DL-RR-11](#dl-rr-11) through [DL-RR-13](#dl-rr-13) — brought it to 91 with a 69 / 22 split, at 93 with a 70 / 23 split once the observability pair — [DL-CR-11](#dl-cr-11), which resolves the topology-versus-populated-dashboard collision, and [DL-RM-14](#dl-rm-14), which records two framework-seam corrections — was added, at 94 with a 71 / 23 split once [DL-RM-15](#dl-rm-15) recorded the legend label that cannot be made to fit, and at the **95** with the **71 / 24** split published here since [DL-RR-14](#dl-rr-14) recorded the cloud advisory no scanner in this build reports; which is why the count moves here and in [V-2](#verification), [V-3](#verification) and [V-11](#verification) too. **The 94 and the 71 / 23 split are withdrawn with the same movement.** The new entry lands in the substitute column rather than the *Source evidence* one because it opens with *Statement*, which is why the numerator holds at 71 while the split moves. The movement is the parser's, not an amendment by hand, and `InventoryCountGateTest` fails the build if this row and the parser ever disagree |
| V-10c | The same build with the vulnerability scan **executed** rather than skipped, which is the only invocation that reaches every clause of Gate 2 | `./mvnw -B -ntp clean verify` — note the absence of `-Ddependency-check.skip=true` | **PASS, measured Friday, 7 August 2026** at [the commit this change publishes](#evidence-commit), finishing `2026-08-07T16:00:59Z` after **08:56**: `BUILD SUCCESS`, exit 0, **18 goals** with `dependency-check:12.1.0:check` among them. **14,914 unit** across 209 suites and **907 integration and end-to-end** across 38 — 799 integration, 108 end-to-end of which the gate harness is 59 — cases — counted from `testcase` elements rather than from suite attributes, for the reason [V-11](#verification) gives about nested cases — **0 failures, 0 errors, 0 skipped** in both tiers. **Zero compiler warnings**, which is the property the gate asserts; the one or two `[WARNING]` lines a no-skip run emits are the scan plugin's own, as [the evidence-commit marker](#evidence-commit) records. `All coverage checks have been met` at **LINE missed 2,036, covered 22,100 of 24,136 — ratio 0.916413** against the 0.80 floor, with BRANCH at 0.810117 and METHOD at 0.977630. The scan itself, report timestamp `2026-08-07T16:00:45Z` from engine 12.1.0: **166 dependencies**, **167 suppressed matches**, **one active finding** — `CVE-2026-40977` on `spring-boot-3.5.11.jar` at **CVSS 6.7 MEDIUM** — and therefore **zero findings at or above the `failBuildOnCVSS` 7 gate**. The reports are retained at `target/dependency-check/dependency-check-report.{html,json,sarif}`. **Two earlier readings of this row are superseded rather than deleted, so the direction of travel stays auditable: 06:52 with 14,477 unit and 850 integration cases at ratio 0.9142 and report timestamp `2026-08-07T03:42:57Z`, and an intermediate reading of the same commit finishing `2026-08-07T15:41:24Z` after 09:21 whose every substantive figure equalled the one published here — the elapsed time and the warning count are the only things that moved, which is why neither is treated as a contract.** This is the row that closes what the paragraph at the end of [§9](#security) recorded as open, and the row [DL-RR-09](#dl-rr-09) draws its current figures from |
| V-11 | Markdown structure is sound | a structural pass over every table (consistent column counts, well-formed separator rows), heading-level continuity, code-fence balance, tab and trailing-whitespace scan. Pipes escaped as `\|` and pipes inside inline code spans are not column separators, which is what the earlier pass got wrong | **PASS** — **125 tables, all 125 well-formed** with **0** rows of inconsistent column count, no heading-level jump, fences balanced, no tabs, no trailing whitespace, LF-only line endings. Re-measured Sunday, 9 August 2026, the total having risen by the one table [DL-RR-14](#dl-rr-14) carries; **the 124 published before it existed is withdrawn**. Previously re-measured Saturday, 8 August 2026, the total having risen by the three tables [DL-CR-11](#dl-cr-11), [DL-RM-14](#dl-rm-14) and [DL-RM-15](#dl-rm-15) carry. **An earlier revision of this row said "all 99 tables" in its command and "74 tables well-formed" in its result — two hand-maintained numbers that disagreed inside one row, and both are withdrawn** |
| V-12 | The documentation site builds clean under strict validation | `mkdocs build --strict --site-dir <path outside the repository>` | **PASS — exit code 0, zero warnings, zero errors**, measured with MkDocs 1.6.1 on Friday, 7 August 2026. This row previously recorded a pre-existing failure of **20 warnings**, every one a link from three documents under `docs/` to `onboarding-guide.md` or `executive-presentation.html`, neither of which existed then; that reading is superseded, not deleted, so the direction of travel stays auditable. Both documents have since been authored, all five `docs/` pages were added to the navigation, and `validation.nav.omitted_files: warn` was set so that an omitted page now **fails** a strict build rather than passing at INFO level. **This document is still outside the site's `docs/` source directory**, so no warning could ever have named it, and its own links are checked by [V-2](#verification) instead. See [§12.3](#outofscope) |

Line-number citations were read with `awk 'NR>=a && NR<=b'` against the frozen files
rather than taken from prose, which is how the six corrections in
[§2.2](#corrections) were found.

<a id="premise"></a>

### 12.2 One in-scope test required a deliberate revision, and got one

Creating this file **falsified a premise that an existing test asserted**, and the honest
record of that is part of the deliverable rather than a footnote.

`src/test/java/com/cardemo/unit/infrastructure/DocumentationConsistencyTest.java` carried a
guard whose purpose is to stop any file claiming that a decision is "recorded in" a
document that does not exist. Its premise assertion listed **both** scheduled evidence
documents as absent and asserted that neither existed on disk — and its own failure
message stated the instruction to follow if that ever changed: *if it is ever authored,
this gate must be revised deliberately rather than passing by accident.*

**What was done.** The premise was revised deliberately, in the narrowest way that keeps
the guard's protective value:

- `DECISION_LOG.md` is now asserted to **exist**, so the guard cannot pass by accident if
  this file is later deleted.
- `TRACEABILITY_MATRIX.md` remained in the absent set at that revision, keeping its
  non-existence assertion and the present-tense-claim guard, because it was still absent.

**The same revision was then demanded a second time, and taken.** Authoring
[`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md) falsified the surviving half of the
premise, and the guard's instruction applies identically to the second name as to the
first. The absent-evidence set is therefore now **empty** — asserted empty explicitly,
rather than left implicit — the forbidden-phrase pattern is *derived* from that set so it
cannot outlive it, and **both** registers are asserted present. Deleting either one fails
the guard immediately, which is the protective value the narrowing had to keep. The
closure is recorded at [DL-RR-07](#dl-rr-07) and the standing decision at
[DL-RR-08](#dl-rr-08).

**What was then done, having first been deferred.** The forward-reference comments in the
Java tree were reworded after all, and [DL-RR-08](#dl-rr-08) carries both the deferral and
its withdrawal. Each site now names the identifier of the entry that holds it, so the
obligation is resolvable by anchor rather than merely honest, and the residual census is
**two quoted occurrences** — the guard pattern that forbids the spelling, and the `grep` a
reader runs to check the sweep held. *Two earlier readings of this paragraph are withdrawn:
one put the figure at "roughly 198" and described the diff as 198 files, the next recorded
**75 comments across 31 files** as a standing state.* The honesty guards were not weakened —
but the premise one of them **stated** was corrected, because `EvidenceHonestyTest` told
anyone who hit it that neither register existed, which had stopped being true.

**Baseline, before and after, measured.** Before this file existed, the three guard classes
ran green: **25 tests, 0 failures**. Creating it produced exactly **one** failure —
`thePremiseHolds` — and no others. After the revision the same three classes run **26
tests, 0 failures**: the count rose by one because the revision *adds* an assertion rather
than deleting one, namely that the newly authored register is present.

**Re-measured after the second revision.** Authoring the traceability matrix produced the
same single failure in the same method, and no others. After the second narrowing the three
guard classes —
`src/test/java/com/cardemo/unit/infrastructure/DocumentationConsistencyTest.java`,
`src/test/java/com/cardemo/unit/model/EvidenceHonestyTest.java` and
`src/test/java/com/cardemo/unit/model/PackageDocumentationInventoryTest.java` —
ran **31 tests, 0 failures, 0 errors, exit code 0**. The rise from 26 was not attributed to
that change: the second narrowing added assertions to two **existing** test methods and
declared no new one, so the intervening growth belonged to work between the two revisions.
What that change was accountable for is the failure it caused and the zero failures it left.

**Re-measured again, and this rise *is* attributable.** Giving every traceability row a
test-method identifier required something to check the identifiers, so
`DocumentationConsistencyTest` gained a further invariant — `TraceabilityTestCitations`, four new
test methods that resolve all 537 `file::method` pairs against the test sources and require each
named method to carry an executable test annotation.

**Re-measured a final time against this tree, and the figure is the tree's, not this page's.** The
same three guard classes now declare **55** test methods — `DocumentationConsistencyTest` 34,
`EvidenceHonestyTest` 11 and `PackageDocumentationInventoryTest` 10 — counted from the sources
rather than copied forward, which is why the earlier readings of 31 and 35 are left standing above
as the series rather than overwritten. Measured by
`./mvnw -B -ntp -Ddependency-check.skip=true -Dtest=DocumentationConsistencyTest,EvidenceHonestyTest,PackageDocumentationInventoryTest -DfailIfNoTests=false test`.
Four of the four are new here, which is exactly the difference between 31 and 35; nothing
existing was weakened or deleted to accommodate them.

**One intermediate failure was mine, and it was fixed rather than suppressed.** The first
attempt at the revision explained the narrowing by quoting the very phrase the sibling
guard in `EvidenceHonestyTest` forbids — that guard scans string literals too, so the
explanation tripped it. The remedy was to *describe* the forbidden form instead of quoting
it, leaving the sibling guard's logic untouched. It is recorded here because a register
that hides its own false starts is less trustworthy than one that shows them, and because
the next person to edit that comment needs to know why it is phrased so carefully.

<a id="outofscope"></a>

### 12.3 Out-of-scope issues found, documented and not modified

Two problems were found during validation that this change did **not** touch, because
neither was this deliverable and neither was caused by it. **Both have since been closed by
the deliverables that owned them**, and each row records that closure alongside the original
disposition — an out-of-scope issue that has been fixed elsewhere must say so, or the register
sends a reader to look for a defect that is gone.

| Issue | Evidence it was pre-existing | Why it was not fixed here, and how it was closed |
|---|---|---|
| `mkdocs build --strict` aborted on 20 warnings, from three documents under `docs/` linking to `onboarding-guide.md` and `executive-presentation.html` | `git diff --quiet HEAD -- docs/ mkdocs.yml` reported **no change**; no warning named this document, which sits outside the site source directory | Both targets were separate scheduled deliverables and authoring them was not this change's subject; removing the inbound links would have been wrong, because those documents correctly anticipated files that were owed. **CLOSED on Friday, 7 August 2026** by the documentation deliverables: both documents were authored and the strict build now exits **0 with zero warnings** |
| `mkdocs.yml` carried only its three original navigation entries, so the newer documents under `docs/` would not publish | Same command: `mkdocs.yml` was byte-identical to its committed state | The navigation update was a separate scheduled change, and it remains **not applicable to this file**: the site's source directory is `docs/`, and this register lives at the repository root, so it cannot be a navigation entry regardless. **CLOSED on Friday, 7 August 2026**: the navigation now carries all five `docs/` documents, and `validation.nav.omitted_files: warn` was added so a future omission fails a strict build instead of passing at INFO level |

Both rows are now closed, and the closure is recorded here rather than by deleting them,
because a register that silently drops what it retires cannot be audited. The two documents
were authored, all five navigation entries were added, and the omission-detection setting the
gate ledger's finding **H-5** prescribed was applied, so `mkdocs build --strict` now exits
**0 with zero warnings** — measured on Friday, 7 August 2026, down from the 20 recorded above.
The second row is also why [§13](#closing) once named `docs/onboarding-guide.md` without
linking it: while the target was absent, adding the link would have contributed a
twenty-first warning to an already-failing strict build for no gain. That target exists now,
so the roster links it.

### 12.4 What this section does not claim

- **It does not claim any validation gate passed.** Every gate result is read from
  [`docs/validation-gates.md`](docs/validation-gates.md), and nothing here changes or
  restates one. Rows V-1 to V-7 verify *this document*, not the system.
- **It no longer restates a gate status anywhere, and that is a correction rather than a
  convention this document always kept.** An earlier revision annotated 37 gate citations with
  the phrase *"currently **Not available**"*, which is precisely the restating that the bullet
  above forbids — and it went stale the moment the gate harness produced figures for Gates 4, 5,
  6, 7 and 8 and a red result for Gate 2's vulnerability half. **Every one of those annotations is withdrawn** and replaced with a pointer of the form
  *"whose result that ledger records"*, of which this document now carries 36. The rule this establishes is stronger than the fix: a gate status is written in
  exactly one place, and every other document links to it rather than copying it, because a
  copied status has no mechanism that keeps it true.
- **It does not claim the full test suite was run to completion here.** The suite is large
  and several tiers require a container runtime. What is claimed is exactly what was run:
  the guard classes named in rows V-8 and V-9.
- **It does not claim the environment readings are current.** They are dated. See
  [§2.4](#environment).

---

<a id="closing"></a>

## 13. Reading this register alongside the others

| Question | Document |
|---|---|
| *Why was it built this way, and what was rejected?* | **This file** |
| *What was proved, and what is still unproven?* | [`docs/validation-gates.md`](docs/validation-gates.md) |
| *Which Java method corresponds to which COBOL paragraph?* | [`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md) — 537 rows across all 28 programs; see [DL-RR-07](#dl-rr-07) |
| *What does each endpoint accept and return?* | [`docs/api-contracts.md`](docs/api-contracts.md) |
| *How do the two architectures compare?* | [`docs/architecture-before-after.md`](docs/architecture-before-after.md) |
| *How do I get set up and troubleshoot?* | [`docs/onboarding-guide.md`](docs/onboarding-guide.md), which is the clean-machine-to-verified-checkout walkthrough and carries its own symptom-keyed troubleshooting section; and [`docs/validation-gates.md`](docs/validation-gates.md) §13 for the gate-level failure modes. **The onboarding guide was owed and unlinked at an earlier revision; it has since been authored, so it is now linked** — the same closure [DL-RR-07](#dl-rr-07) records for the matrix |
| *What did the legacy system actually consist of?* | [`README.md`](README.md), whose legacy transaction, program and job inventory tables are preserved verbatim, and the frozen corpus under `app/` |

**The corpus is the authority.** Where this register and `app/` disagree, the register is
wrong and must be corrected — never the other way round.

<a id="remediation"></a>

## 14. Remediation decisions — the external-integration review

An external-integration code review of the migrated tree raised twenty-four findings across the
cloud, container, batch-boundary and configuration surfaces, and a project-wide comments review raised
thirteen more. Most were resolved by making the code
do what this register already said it did, and those needed no entry here. The twelve below are the
exceptions: each one either introduced a mechanism the earlier sections do not describe, or chose
deliberately against the obvious repair, or improved on the legacy behaviour rather than reproducing
it, or established a convention the tree had not stated. Every entry carries the same fields as the rest of this document, and the same rule applies —
where an entry and `app/` disagree, the entry is wrong.

**No total is declared for this section**, for the same reason none is declared for the retained
no-op registry: a count in prose is a second source of truth that drifts the moment an entry is
added. The entries are keyed by identifier — `DL-RM-01` upward, never reused and never renumbered
per [§1.3](#identifiers) — so the section is enumerated by reading it. An earlier revision of
this paragraph read *"The eleven below are the exceptions"*; that figure is **withdrawn**, having
been made stale by [`DL-RM-12`](#dl-rm-12) rather than by any change to the entries it described.

<a id="dl-rm-01"></a>

### DL-RM-01 — One generation per interest run: parts, then promotion

| Field | Content |
|---|---|
| **Classification** | `LABELLED DEVIATION` |
| **Severity** | **High** |
| **Source evidence** | `app/jcl/INTCALC.jcl:L37-L41` allocates `AWS.M2.CARDDEMO.SYSTRAN(+1)` with `DISP=(NEW,CATLG,DELETE)` and `LRECL=350`. One execute step produces exactly ONE catalogued generation, and `app/jcl/COMBTRAN.jcl:L23-L26` concatenates `SYSTRAN(0)` — the single most recent generation — as half of its sort input. |
| **Target** | `InterestCalculationJob` at `src/main/java/com/cardemo/batch/jobs/InterestCalculationJob.java`, which spools each chunk to a part object beneath the generation prefix and promotes exactly one generation object at step close. |
| **Decision** | **One object per RUN, assembled from durable per-chunk parts.** The chunk-oriented writer previously uploaded one object per chunk, so a run of four chunks catalogued four generations where the source catalogues one, and the consumer refuses more than one. Parts are written under the generation prefix, concatenated at close, and deleted only AFTER the generation object is accepted. |
| **Alternatives considered** | **(a) Buffer the whole run in memory and upload once** — rejected: it reintroduces the unbounded-heap defect that [DL-RM-05](#dl-rm-05) removes elsewhere, and a large interest run is exactly when it would fail. **(b) Publish an ordered manifest of the per-chunk objects** — rejected: the consumer's contract is a single sequential dataset, and a manifest would put a Java-only concept on a boundary the legacy job stream also reads. |
| **Rationale** | Restart safety decided the ordering. Parts are objects, so they survive a JVM exit; promotion is the last act, so a crash mid-run leaves parts and no generation, which is precisely what `DISP=(NEW,CATLG,DELETE)` means — a failed step catalogues nothing. Deleting parts before the generation was accepted would have opened a window in which neither existed. |
| **Observable consequence** | A four-chunk run now yields one `SYSTRAN` generation object and no residue. The combine stage reads it as `SYSTRAN(0)`. |
| **Test / gate evidence** | `src/test/java/com/cardemo/integration/batch/InterestCalculationJobTest.java` and the S3-generation integration suite. |
| **Remediation / follow-up** | None outstanding. |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` |

<a id="dl-rm-02"></a>

### DL-RM-02 — A failed run leaves no catalogued generation

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | **High** |
| **Source evidence** | `DISP=(NEW,CATLG,DELETE)` in `app/jcl/INTCALC.jcl:L37-L41`. The third positional subparameter is the abnormal disposition: on an abend the dataset is DELETED, so no generation is catalogued and the next run's `(+1)` is unaffected. |
| **Target** | `InterestCalculationJob`, which records every attempted object key and deletes each one from an unsuccessful `afterJob`. |
| **Decision** | **Track attempt keys and delete them when the job did not succeed**, rather than trusting that a failure happened before the first upload. |
| **Alternatives considered** | **(a) Rely on object lifecycle rules** — rejected: lifecycle is time-based and eventual, so a failed run would remain visible to the very next combine. **(b) Write to a staging prefix and copy on success** — rejected: it doubles the bytes moved for the same guarantee that promotion already provides. |
| **Rationale** | Without this the abnormal disposition was simply not implemented: a run that failed after its first upload left a generation the combine stage would then consume as authoritative. |
| **Observable consequence** | A deliberately failed run leaves the output prefix exactly as it was. |
| **Test / gate evidence** | `src/test/java/com/cardemo/integration/batch/InterestCalculationJobTest.java`. |
| **Remediation / follow-up** | None outstanding. |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` |

<a id="dl-rm-03"></a>

### DL-RM-03 — The step execution context as a transactional outbox

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | **High** |
| **Source evidence** | `app/cbl/CBTRN02C.cbl:L424-L444` writes the transaction record inside the same unit of work as the category-balance and account updates. The fixed-width image and the database row are one act, not two. |
| **Target** | `TransactionWriter` and `RejectWriter` at `src/main/java/com/cardemo/batch/writers/`, which write a pending-emission marker inside the chunk transaction and upload after commit, reconciling from the committed rows. |
| **Decision** | **Use Spring Batch's own step execution context as the outbox.** `TaskletStep$ChunkTransactionCallback.doInTransaction` calls `JobRepository.updateExecutionContext` and `JobRepository.update` INSIDE the chunk transaction — verified by decompiling the pinned framework jar, not assumed — so a marker written there commits atomically with the rows it describes. |
| **Alternatives considered** | **(a) Upload before commit** — rejected: a rollback then leaves an object describing rows that do not exist. **(b) Add an outbox table** — rejected: the schema is derived from eleven copybooks and a twelfth table would be a Java-only artefact on a boundary the parity gates compare. **(c) Leave the post-commit upload unguarded** — rejected: that was the finding. |
| **Rationale** | The asymmetry that makes this sound is that a marker naming an absent row is discarded rather than emitted, so reconciliation can only ever narrow the output to what the database actually holds. |
| **Observable consequence** | A crash between commit and upload is repaired on restart: the payload is re-derived from the committed rows, and the 430-byte and 350-byte geometries are unchanged. |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/` writer suites, reworked onto an in-memory object store so completion and deletion can be asserted rather than inferred. |
| **Remediation / follow-up** | None outstanding. |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` |

<a id="dl-rm-04"></a>

### DL-RM-04 — TRANBKP archive-and-reset, and one shared generation key convention

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | **High** |
| **Source evidence** | `app/jcl/TRANBKP.jcl` backs the transaction cluster up and `app/jcl/COMBTRAN.jcl:L33-L37` REPROs the combined result INTO that cluster. `REPRO` into a KSDS replaces its content; an INSERT into a populated table collides on the primary key, so the Java stream was not repeatable where the source stream is. |
| **Target** | `BatchPipelineOrchestrator` stage 3 and `CombineTransactionsJob`, gated by the non-identifying `archiveAndResetMaster` job parameter: archive, then sort, then load. |
| **Decision** | **Archive the master to its generation BEFORE emptying it**, key both `TRANSACT.BKUP` producers identically as `<prefix>/generation=<19-digit job instance id>/TRANSACT.BKUP`, and **empty the master inside the load step's own transaction rather than in the archive step**, immediately before the first insert (`CombineTransactionsJob.emptyMasterForReload`). |
| **Alternatives considered** | **(a) Upsert on load** — rejected outright: the requirements state that duplicate-key exposure must surface as a duplicate-record exception and a failed exit status, never a silent upsert. **(b) A bare numeric generation segment** — rejected after measuring: the consumer picks the lexicographically greatest first segment after the base, and `'0'` sorts below `'g'`, so a bare-numeric segment would never have out-sorted the pre-existing `generation=` producer. |
| **Rationale** | Uploading before emptying is what makes the stage crash-safe in the only order that matters: if the archive fails, nothing has been destroyed. Placing the emptying in the load's unit of work is what makes it crash-safe in the other direction. Spring Batch commits every step separately, so an emptying performed in the archive step was durable before the load ran: **one** repeated identifier then left the transaction master permanently empty while every account balance kept the effect of the rows that had vanished, and every online transaction list and detail read returned nothing until an operator restored the generation by hand. It cannot instead be sequenced *after* the load, because the sort's first `SORTIN` leg is the archive generation and the load re-inserts the rows it holds — between the two, inside the load's transaction, is the only placement that is both correctly ordered and atomic. This is the write atomicity the modular monolith is justified on. |
| **Observable consequence** | The whole stream is now repeatable — proved by running it twice end to end. A load refused by a duplicate identifier leaves the master exactly as the archive found it, whether or not the run was instructed to reset: measured as an unchanged row count *and* an unchanged identifier set across the failure, with the archive generation still written so the run stays diagnosable. |
| **Test / gate evidence** | `src/test/java/com/cardemo/integration/batch/` combine and orchestrator suites, including a two-run whole-stream repeatability case and `anInstructedResetIsRolledBackWhenTheLoadFails`, which drives a repeated identifier through an instructed reset and asserts the master intact by count and by membership. |
| **Remediation / follow-up** | None outstanding. |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` |

<a id="dl-rm-05"></a>

### DL-RM-05 — Bounded reads at the report boundary, and an asserted sort order

| Field | Content |
|---|---|
| **Classification** | `LABELLED DEVIATION` |
| **Severity** | Medium |
| **Source evidence** | `app/proc/TRANREPT.prc:L35-L46` sorts with DFSORT, which streams and spills to work datasets rather than holding the file in storage. `app/jcl/PRTCATBL.jcl:L52-L56` likewise sorts before the print step, so its input arrives ordered. |
| **Target** | `TransactionReportJob.streamSortedDailyGeneration` and `printCategoryBalances` at `src/main/java/com/cardemo/batch/jobs/TransactionReportJob.java`. |
| **Decision** | **Page through the repository finder and stream fixed-width records, rather than materialising a generation in the heap.** The category-balance print now ASSERTS ascending composite-key order record by record instead of sorting a resident list, and abends with `SORT ORDER VIOLATION` naming the offending ordinal when the assertion fails. |
| **Alternatives considered** | **(a) Keep sorting in memory with a larger heap** — rejected: it makes the failure a deployment tuning question rather than removing it. **(b) Sort the read-back records silently** — rejected: it would mask an unsorted upstream step, which is the defect worth reporting, and the source's print step does not sort either. |
| **Rationale** | Asserting the invariant the upstream sort establishes is stronger than re-establishing it: if the sort step is ever removed, the print step says so instead of quietly compensating. |
| **Observable consequence** | Peak heap no longer scales with generation size. The 133-byte line, the twenty-lines-per-page pagination and the card-number control break under an "Account Total" label are all unchanged. |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/TransactionReportBoundedReadTest.java`. |
| **Remediation / follow-up** | None outstanding. |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` |

<a id="dl-rm-06"></a>

### DL-RM-06 — One strict generation-prefix contract, in one place

| Field | Content |
|---|---|
| **Classification** | `LABELLED DEVIATION` |
| **Severity** | Medium |
| **Source evidence** | `app/jcl/DEFGDGB.jcl`, `app/jcl/DALYREJS.jcl` and `app/jcl/REPTFILE.jcl` define the generation-group bases. A base name is a catalogue-level identifier: two bases cannot silently share one, and no program validates the name because the catalogue does it. |
| **Target** | `GenerationPrefixContract` at `src/main/java/com/cardemo/batch/GenerationPrefixContract.java`, delegated to by eight classes across `batch/jobs`, `batch/readers` and `batch/writers`. |
| **Decision** | **Publish one strict relative-prefix validator and one startup pairwise-distinctness check over all nine configured roots**, replacing eight private normalisers whose rules disagreed. The collision guard refuses three relations: equality, path ancestry and bare string prefixing. |
| **Alternatives considered** | **(a) Align the eight copies** — rejected: eight copies of one rule is the defect, not its spelling. **(b) Put it in an existing utility** — rejected: `com.cardemo.batch` is the one package the class-census gate does not fix a count for, and every consumer is a batch component. |
| **Rationale** | This adds a type the requirements' per-package tables do not enumerate, which is a deliberate divergence recorded here: the production-class census moves from 132 to 133 and every per-package figure is unmoved. Two configured defaults were also corrected from absolute to relative spellings, which the strict validator would otherwise have refused at startup. |
| **Observable consequence** | Two generation bases can no longer be configured to share a prefix — the context fails to start instead. The eighth delegating site was found by measuring the tree rather than by reading the review, which named six. |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/GenerationPrefixContractTest.java`; the census figure is asserted in `GateVerificationTest` and restated in `docs/validation-gates.md` and `TRACEABILITY_MATRIX.md`. |
| **Remediation / follow-up** | None outstanding. |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` |

<a id="dl-rm-07"></a>

### DL-RM-07 — A statement key is redacted at both the source and the appender

| Field | Content |
|---|---|
| **Classification** | `SECURITY DECISION` |
| **Severity** | Medium |
| **Source evidence** | `app/cpy/CVACT01Y.cpy:L5` declares `ACCT-ID PIC 9(11)`, and `app/jcl/CREASTMT.JCL:L89` and `:L94` declare the two statement outputs at `LRECL=80` and `LRECL=100`. The statement object key embeds the account identifier as `statements/account=<11 digits>/…`. |
| **Target** | `StatementWriter.sanitizedCause` and `StatementGenerationJob.guardObjectStore`, plus rule R19 in `src/main/resources/logback-spring.xml`. |
| **Decision** | **Both halves, because they protect different sinks.** The appender rule cannot reach a throwable that Spring Batch renders itself or stores in `BATCH_STEP_EXECUTION.EXIT_MESSAGE`, so the cause is sanitised at the source as well. |
| **Alternatives considered** | **(a) The appender rule alone** — rejected: it does not cover the batch metadata table. **(b) Sanitising at the source alone** — rejected: it does not cover a key that reaches a log line by another route. **(c) Masking `accountId` generally** — rejected on parity grounds: the identifier is the control-break key of `app/cbl/CBACT04C.cbl:L194` and the reported key of the 133-byte report line, and Gate 1 compares those lines byte for byte. R19 is keyed on the literal `statements/account=` precisely so it cannot widen into that. |
| **Rationale** | The sanitiser returns the ORIGINAL exception instance unchanged when no key appears anywhere in the cause chain, so a caller's `instanceof` handling is preserved; the type is given up only where keeping it would keep the disclosure, and even then the original class name leads the surrogate's message and the original stack trace is attached. That is a substitution, not a swallow. |
| **Observable consequence** | An object-store failure during statement generation names the bucket and the dataset, never the account. The 80-byte and 100-byte geometries and the published key contract are unchanged. |
| **Test / gate evidence** | `StatementWriterTest` group 10 and `LogbackMaskingGuardTest`, one case of which reads the writer's own redaction constant so the two definitions cannot drift, and another of which checks the sanitiser against a key the writer really emitted. |
| **Remediation / follow-up** | None outstanding. |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` |

<a id="dl-rm-08"></a>

### DL-RM-08 — Two authenticity envelopes, deliberately not one

| Field | Content |
|---|---|
| **Classification** | `SECURITY DECISION` |
| **Severity** | **High** |
| **Source evidence** | The transient data queue is defined as `DEFINE TDQUEUE(JOBS) TYPE(EXTRA) DDNAME(INREADER) TYPEFILE(OUTPUT) RECORDSIZE(80)` in `app/csd/CARDDEMO.CSD`, and `app/jcl/POSTTRAN.jcl:L30` reads the `DALYTRAN` dataset. On z/OS both are reachable only through the region's own authorisation; over object storage and a queue neither is. |
| **Target** | `ReportSubmissionService.JobSubmissionEnvelope` for the queue and `DailyTransactionReader.InputObjectEnvelope` for the input object. |
| **Decision** | **Two separate envelope contracts with two distinct derivation labels**, not one shared implementation. Each derives a purpose key as `HMAC-SHA-256(signing key, label)` and then signs a canonical form; the object envelope's canonical form contains the bucket and key, so a signed object cannot be moved and still verify. **The queue envelope's canonical form additionally binds the submission identifier and a validity window** — `payload ‖ submissionId ‖ issuedAt ‖ expiresAt`, rendered as `v2=<submissionId>:<issued>:<expires>:<hex>` — and **neither the publisher nor the consumer retains the configured signing key**: each holds a `JobSubmissionEnvelope` that derived its key once at construction and zeroed the raw and derived byte arrays in a `finally` block. |
| **Alternatives considered** | **(a) Extract one shared MAC primitive** — rejected: the two live in different tiers, sign different subjects and must not share a label, and a shared helper would invite exactly the label reuse that makes a signature transplantable. Each Javadoc cites the other instead. **(b) A digest with no signature** — rejected: it accepts a body and manifest an attacker wrote together. **(c) A signature with no digest** — rejected: it accepts a valid manifest copied onto a different body of the same length. Both halves are required, and the verification is a bounded second pass BEFORE any record is parsed, because verifying while streaming authenticates the tail only after the head has been posted. **(d) A payload-only queue signature** — implemented first and **withdrawn**, because it authenticated the wrong thing. The deduplication identifier it left uncovered is an *identifying* Spring Batch job parameter, so a captured `(body, code)` pair re-published under a fresh identifier resolved to a new job instance and ran a real report — unbounded amplification from one observed message — and with no freshness in the code the original could be replayed for as long as the key lived. The retired form is refused rather than tolerated, which is what the `v1`→`v2` prefix change buys. **(e) A per-message key derivation** — the original arrangement, withdrawn: it re-derived on every sign and every verify, leaving an intermediate key array to the collector each time, and required both beans to hold the application signing key in an immutable `String` for the life of the process (`CWE-316`). **(f) A property-configurable lifetime** — rejected: the interval a legitimate submission needs follows from this topology (a bounded publish, a five-minute deduplication window, a continuously draining listener), not from a deployment, and a settable lifetime is a lifetime settable to something useless. Fifteen minutes is a compile-time constant, matching the sealed snapshot so there is one figure to remember. |
| **Rationale** | There is deliberately NO unverified mode: the object path requires the signing key at construction, which adds no new secret because that key is already mandatory in all four profiles with no committed default. A typed refusal is re-thrown unchanged from the open path, so an authenticity refusal is never re-reported as file status `9x` — an attack must not look like a hardware fault. |
| **Observable consequence** | On the object path, one refusal vocabulary — `DALYTRAN INPUT NOT AUTHENTIC` with abend 999, naming the dataset and bucket only; no rejection says which check failed. On the queue path, a delivery whose code is absent, of the retired version, misshapen, issued for another identifier, outside its validity window or simply wrong is discarded without launching anything, and the log names the *outcome* — this application's own classification, never any part of the message — so an operator can tell a stale submission from a forged one. An **exact** redelivery inside the window still verifies and reaches the launcher, which refuses it as the at-least-once delivery it is: treating an ordinary visibility-timeout redelivery as an attack would be the worse failure. Neither bean exposes a field holding the signing key, which two structural tests assert over the whole field set rather than over one call. The emulator's own authorisation is measured, not assumed — the compose file records that the community edition does not enforce it, and network containment is the operative control. |
| **Test / gate evidence** | The `M-11` unit group, one case of which recomputes the documented recipe independently so the test pins the recipe rather than agreeing with the implementation about itself, plus three live cases in `ObjectStoreAuthorizationBoundaryIntegrationTest`. Every refusal case also asserts the stream was never opened. For the queue path: `ReportSubmissionServiceTest` covers the identifier binding, both ends of the validity window, the retired version, every malformed rendering, the identifier shapes refused at signing, and `theServiceRetainsNoFieldHoldingTheSigningKey`; `BatchConfigTest` covers the consumer's refusals — the replay under a fresh identifier, the expired and premature codes, the retired version — the exact redelivery that must still verify, and `theListenerRetainsNoFieldHoldingTheSigningKey`; `ReportQueueListenerLiveTest` drives a genuinely published, genuinely signed submission to a real `TRANREPT` execution. |
| **Remediation / follow-up** | Nothing in this repository produces the input object; the producer is external, so the contract is documented in full and kept executable through a published signing entry point. |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` |

<a id="dl-rm-09"></a>

### DL-RM-09 — The runtime database role: silent degradation replaced by refusal

| Field | Content |
|---|---|
| **Classification** | `SECURITY DECISION` |
| **Severity** | **High** |
| **Source evidence** | No legacy analogue: CICS file control governed dataset access, and the online file control table names eight files in `app/csd/CARDDEMO.CSD`. The relational substitute is a database role, and the postgres image creates its bootstrap role as a cluster SUPERUSER. |
| **Target** | `docker-compose.yml` — the `app` service, the `postgres` service and the `postgres-least-privilege-roles` config — plus `.github/workflows/build.yml` and `.env.example`. |
| **Decision** | **One spelling of each role name in all three layers, and both role passwords promoted to required `:?` guards.** The two names default to `carddemo_app` and `carddemo_migrator` everywhere; neither falls back to the bootstrap role any more; the provisioning script refuses rather than skipping when a password is absent. |
| **Alternatives considered** | **(a) Keep the optional quartet and repair only the name defaults** — rejected: the password fallback was the other half of the split, and a name from one source with a password from another can disagree about which role is meant. **(b) Express "supplied together or not at all" in Compose** — rejected because it is not expressible: `:-` and `:+` have no else branch, so any conditional spelling leaves one member of the pair empty. |
| **Rationale** | This deliberately makes a previously silent degradation a loud refusal: a start that omitted the passwords used to create two least-privilege roles and then serve every request as the superuser, with the least-privilege block apparently in place. A start that cannot be least-privileged should not start. |
| **Observable consequence** | On a clean volume with neither role NAME exported, the application's only database connections are `carddemo_app`; all eighteen tables including the six Spring Batch metadata tables are owned by `carddemo_migrator`; `carddemo_app` may INSERT but is refused CREATE and TRUNCATE. The earlier documented ordering caveat — that the batch tables needed a superuser first — is obsolete, because `BatchConfig.batchMetadataInitializer` runs the framework schema as the migration role. |
| **Test / gate evidence** | `DataTierPrincipalContractTest` group "One spelling of each database role", proven able to fail by mutation; the workflow's topology step asserts the RENDERED contract, because a default is invisible until it is interpolated. |
| **Remediation / follow-up** | None outstanding. |
| **Verification** | `Target-verified`, `Test-verified`, `Runtime-verified` |

<a id="dl-rm-10"></a>

### DL-RM-10 — Cross-file citations name symbols, not line numbers

| Field | Content |
|---|---|
| **Classification** | `SCOPE STATEMENT` |
| **Severity** | Low |
| **Source evidence** | Not applicable to `app/`. An `app/...` line locator is safe permanently because the corpus is frozen; a locator into a file this project edits is correct only until the next edit to the file it points into. |
| **Target** | **Every file this project authors.** The first pass covered `application.yml`, `application-local.yml`, `application-prod.yml`, `application-test.yml`, `AwsConfig`, `RejectWriter`, `TransactionWriter`, `HealthIndicators`, `localstack-init/init-aws.sh` and two test sources. The second pass covered the remaining 110 locators: this file, `docs/technical-specifications.md`, `docs/onboarding-guide.md`, `docs/api-contracts.md`, `docs/validation-gates.md`, `docs/architecture-before-after.md`, `docs/executive-presentation.html`, `.github/workflows/build.yml`, `.mvn/wrapper/maven-wrapper.properties`, and 20 sources under `src/`. |
| **Decision** | **Every citation of a file this project authors now names a SYMBOL** — a method, field, column, constraint, Maven property, YAML key, shell function, Dockerfile instruction or quoted heading — and `SourceCitationResolutionTest` refuses the line-number form for **any** authored target, not for a named list of five. **The scope widened because the enumeration was the defect.** The rule was written for the five configuration files a first sweep had found rotted, and it held for exactly those five while the identical failure sat one directory over: a later review found twelve locators into `src/...` Java sources cited from this file, every one drifted onto the Javadoc prose above the construct it named, the worst by **108 lines**. A tree-wide census then found **110** locators into authored files, of which **45** were provably pointing at something other than what their sentence claimed. Same failure, same cause, invisible for the same reason. |
| **Alternatives considered** | **(a) Renumber the stale locators** — rejected **twice**, on the same ground each time: a corrected number is correct only until the next edit, and every rotted locator was accurate when it was written. Fifty-three were checked in the first sweep and essentially every one had rotted; 45 of 110 had rotted by the second. **(b) Add a guard that resolves each `src/` locator to the construct its sentence names** — this is the remedy the review that raised the finding suggested, and it was prototyped before being declined. It detects drift one edit after the fact rather than preventing it, and it can only be made sound by skipping any citation whose subject it cannot attribute with certainty: measured against this tree, a sound implementation reached **15 of 133** citations, leaving seven eighths of the population unguarded. The form rule reaches all of them and cannot rot. **(c) Widen the rule but keep a list of exempt files** — rejected: a list is what failed the first time. |
| **Rationale** | A resolution check cannot catch this, because any line number resolves to some line; only the citation FORM can be checked cheaply and kept true. |
| **Observable consequence** | Nothing at runtime. The guard reports the citing file, the offending text and the authored file the locator resolves to, and a companion assertion requires all 24 converted targets still to be cited by name, so the rule cannot be satisfied by deleting the evidence. |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/infrastructure/SourceCitationResolutionTest.java` group "No mutable file is cited by line number", now four assertions rather than three: the form rule over every authored target, the anti-silence roster, the symbol-resolution table — **grown from 12 rows to 59**, each pinning a cited symbol against its declaration in the target so a rename in either direction fails — and the byte-identity premise behind the one exemption. Proven non-vacuous by fault injection: a probe carrying one full-path and one bare-name locator was reported as two offenders and the assertion failed. |
| **Remediation / follow-up** | None outstanding. One exemption exists and is asserted rather than trusted: an ordinal into a fixture under `src/test/resources` names a **data record** of a byte-for-byte copy of a frozen `app/data/ASCII/` fixture, so it is as stable as a corpus locator. All nine copies were measured identical to their originals with `cmp`, and the guard re-measures them, so a fixture that ever diverges loses the exemption instead of quietly keeping it. |
| **Verification** | `Target-verified`, `Test-verified` — first pass 7 August 2026; scope widened, census re-measured tree-wide to **zero** remaining authored-target locators, and the whole class re-run green on 8 August 2026 |

<a id="dl-rm-11"></a>

### DL-RM-11 — Two batch-boundary corrections that go beyond parity

| Field | Content |
|---|---|
| **Classification** | `LABELLED DEVIATION` |
| **Severity** | Medium |
| **Source evidence** | `app/cbl/CBTRN02C.cbl:L545-L560` assigns reject code 109 on a rewrite failure inside the already-validated posting path, where no reject record is written and the value is cleared on the next iteration. `app/cbl/CBSTM03A.CBL:L225-L233` declares a fifty-one by ten transaction table and increments both indices with no bounds check. |
| **Target** | `TransactionPostingProcessor`, `TransactionWriter` and `StatementProcessor`. |
| **Decision** | **Both legacy hazards are closed rather than reproduced, and both are labelled as deviations rather than presented as parity.** The transactional boundary removes the orphaned category-balance and transaction rows that the source's three independent commits could leave behind, and unbounded collections remove the silent storage overrun at 510 transactions. |
| **Alternatives considered** | **Reproduce both faithfully** — rejected: reproducing a silent data-corruption hazard is not a contract worth honouring, and neither hazard is observable in any output the parity gates compare. |
| **Rationale** | Stating this plainly is the point. Pretending the ceiling was preserved would be false; pretending its removal is invisible would be worse. Reject code 109 remains as an enum constant because the assignment is real code on a reachable path. |
| **Observable consequence** | Reject-record output, exit codes and every fixed-width geometry are unchanged. Statement generation no longer has a capacity ceiling; the legacy figure is recorded as the historical limit. |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/batch/` posting and statement suites; the 102-over-103 fall-through and the "Account Total" label quirk are asserted unchanged. |
| **Remediation / follow-up** | None outstanding. |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` |

<a id="dl-rm-12"></a>

### DL-RM-12 — The notification topic gets one derived inbox queue, and zero subscriptions becomes fatal

| Field | Content |
|---|---|
| **Classification** | `MECHANISM SUBSTITUTION` |
| **Severity** | Blocker |
| **Source evidence** | `app/jcl/DEFGDGB.jcl:L1` carries the job card's `NOTIFY=&SYSUID` operand, and `app/cbl/CORPT00C.cbl:L86` carries the same operand inside the embedded submission deck. On z/OS `NOTIFY` delivers a message to the named user's *message queue*, where it is held until that user reads it. Delivery, not acceptance, is the operand's semantic. |
| **Target** | `localstack-init/init-aws.sh` — `NOTIFICATION_INBOX_QUEUE`, `ensure_notification_inbox`, `notification_inbox_arn`, `ensure_notification_subscription` and `verify_notification_subscription`; asserted from the CI side by the *Provisioned inventory* and *second run* steps of `.github/workflows/build.yml`, and published as the Gate 8 resource inventory in [`docs/validation-gates.md`](docs/validation-gates.md#gate-8). |
| **Decision** | **One STANDARD SQS queue is provisioned as the topic's single subscriber, its name derived by appending `-inbox` to the topic name, subscribed with `RawMessageDelivery=true`; and a topic reporting NO subscription is now a fatal error (exit 7) rather than a verified state.** The provisioned inventory is therefore three buckets, **two** queues, one topic and **one** subscription. |
| **Alternatives considered** | **(a) Keep the topic unsubscribed and keep asserting zero**, as the previous revision did — rejected: it is the one state in which SNS accepts every publish and discards it, so operator notification was structurally present and functionally inert, and no publisher could detect it. **(b) Subscribe an email or HTTP endpoint** — rejected: both introduce an external dependency the local-emulator-only constraint of AAP §0.3.2 forbids, and neither is durable in the way `NOTIFY` is. **(c) Subscribe the existing FIFO submission queue** — rejected: it would mix operator notices into the report-submission stream that `BatchConfig`'s listener consumes, and SNS cannot deliver to a FIFO queue without a message group the notice has no basis to supply. **(d) Give the inbox its own environment variable** — rejected: two independently settable names can drift, and a subscription pointing at a queue that no longer matches the topic is silent. Deriving it from the topic name makes the pair inseparable. |
| **Rationale** | Least privilege says the provisioned set must equal the **consumed** set. That rule is what removed the unconsumed `alerts` topic, and it is the same rule that adds this queue: the notification topic had no consumer, so the capability it advertised did not exist. One durable standard queue is the smallest surface that closes the gap. It is standard rather than FIFO because a notice is not ordered with respect to anything, and the submission queue's ordering guarantee exists for a different reason. The application deliberately never reads the inbox — reading it is the operator's act, exactly as reading a `NOTIFY` message was. |
| **Observable consequence** | `sqs list-queues` returns two queues on a clean volume rather than one, and `sns list-subscriptions` returns one rather than none. A repeated `docker compose up` must still converge: the LocalStack edge creates a **second** subscription for a repeated topic/protocol/endpoint triple instead of returning the existing one, so `ensure_notification_subscription` reads the existing set before subscribing, and the CI idempotence snapshot includes subscription ARNs so an accumulation is caught rather than inferred. |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/infrastructure/InitAwsScriptGuardTest.java` asserts that `verify_no_subscriptions` is gone, that both `ensure_notification_subscription` and `verify_notification_subscription` are present, that zero is the failing case, and that the existing set is read before the subscribe call. Gate-level: [Gate 8](docs/validation-gates.md#gate-8). |
| **Remediation / follow-up** | None outstanding. Six surfaces stated the superseded zero-subscription contract and are corrected together with this entry: the `WHAT IT DOES` header, the reproduce-command expectations and the drift/exit taxonomy of `localstack-init/init-aws.sh`; the *Provisioned inventory* step of `.github/workflows/build.yml`; the Gate 8 resource table in `docs/validation-gates.md`; the resource-contract block of `.env.example`; the live-resource evidence block of `src/main/resources/application-local.yml`; and the finding javadoc in `com.cardemo.service.report.ReportSubmissionService`. A resource inventory is a contract, and a contract asserted differently in six places is not one contract. |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 7 August 2026 |

---

<a id="dl-rm-13"></a>

### DL-RM-13 — Remediation narration is removed from comments; withdrawals live here

| Field | Content |
|---|---|
| **Classification** | `CONVENTION` |
| **Severity** | Low |
| **Source evidence** | This entry governs the Java tree and its documentation, not the corpus, so it carries no `app/` locator. Its premise is *Rule 1: Build Verify* clause A, which requires explicit behaviour and readable maintenance, and clause B, which forbids untracked residue. The corpus convention it respects is the source banner of [app/cbl/CBACT04C.cbl:L1-L21], which states a component's function and never its edit history. |
| **Target** | Every comment, docstring, banner and explanatory Markdown, YAML, SQL, XML, JSON, shell and HTML comment in the Java tree and in `docs/`. |
| **Decision** | **A comment states what the code does and why, never what the repository used to say.** Two forms are therefore prohibited outright. The first is *remediation narration* — "an earlier revision said X", "this paragraph previously read Y", "Finding N, severity H, RESOLVED" — which is a story about the repository's own edit history rather than about the code. The second is a *relative measurement*, "today" or "currently", which has no fixed referent and is stale the moment it is read. Both are rewritten in one of two ways: a claim worth keeping becomes a forward-looking **prohibition or invariant** ("reading `X` here would be wrong, because …"), and a claim that has to be retracted in writing is retracted **in this register**, with its own dated `Correction` row under [§1.10](#update-policy) policy 2, rather than in a comment. Current-state figures are published as **dated measurements** with the command that reproduces them. |
| **Alternatives considered** | **(a) Keep the withdrawal sentences in the comments** — rejected: a reader arriving at a method wants the contract, not a changelog, and a diary in a docstring goes stale exactly as fast as the claim it retracts. **(b) Delete the withdrawn claims outright with no replacement** — rejected: several of them carry the engineering reason a naive change would be wrong, and deleting the sentence would delete the reason. Reframing as a prohibition keeps the reason and drops the narration. **(c) Move every withdrawal into this register** — rejected as the general rule: a withdrawal only earns a register row when a *decision* changed, and most of these were wording, not decisions. |
| **Rationale** | The two prohibited forms fail in the same way and for the same reason: neither is a statement about the code. A reader cannot act on "this used to be different", and a build cannot check it, so it accumulates without limit and without anything noticing when it becomes false. A prohibition, by contrast, is checkable by inspection against the code beside it and stays true for as long as the code does. |
| **Observable consequence** | No runtime behaviour changes and no test expectation changes. Three narrow exceptions remain, and each is deliberate: `EvidenceHonestyTest` retains the forbidden spellings as **matcher fixtures**, without which its own guard could not be tested; `DocumentationConsistencyTest` requires the closed-total sentence to appear **quoted** in the root package documentation, so that quotation survives as a quotation; and a true past-tense statement about the **legacy system** — "the COMMAREA that once carried conversation state" — is not repository history and is retained. |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/infrastructure/DocumentationConsistencyTest.java` and `src/test/java/com/cardemo/unit/model/EvidenceHonestyTest.java` between them forbid a stale absence claim, forbid a closed total stated as a sentence, and require the register-site census to be derived rather than declared. Neither guard requires narration to survive in production documentation. |
| **Remediation / follow-up** | None outstanding. A future comment that needs to retract a published claim adds a dated `Correction` row to the entry that made it, rather than narrating the retraction where the code lives. |
| **Verification** | `Target-verified` — 7 August 2026 |

<a id="dl-rm-14"></a>

### DL-RM-14 — Two observability corrections at the framework seam: a readable batch span, and one instrument where the framework registers two

| Field | Content |
|---|---|
| **Classification** | `LABELLED DEVIATION` |
| **Severity** | Low |
| **Source evidence** | Both halves concern the batch job identity that comes straight from the corpus. The job names are the JCL member names — `app/jcl/POSTTRAN.jcl`, `app/jcl/INTCALC.jcl`, `app/jcl/COMBTRAN.jcl`, `app/jcl/CREASTMT.JCL` and `app/proc/TRANREPT.prc` — and they are all capitals because the members are. That is the property the two defects below turn on, and it is the property neither fix is permitted to change: the member name is what an operator submits, what `TRACEABILITY_MATRIX.md` cites, and what [DL-MS-07](#dl-ms-07) anchors the job mapping to |
| **Target** | `src/main/java/com/cardemo/config/BatchConfig.java` — `batchJobSpanNamePostProcessor()` and the nested `ReadableBatchJobObservationConvention`. `src/main/resources/application.yml` — `management.observations.long-task-timer.enabled` |
| **Decision** | **(1) Supply the job observation's *contextual* name, already lower-hyphenated, and leave the job name alone.** A batch span arrived in the trace store as `t-r-a-n-r-e-p-t`, one hyphen per letter, beside `p-o-s-t-t-r-a-n`. `AbstractJob.execute` sets the contextual name to the job name verbatim, and the tracing bridge then applies `SpanNameUtil.toLowerHyphen`, which inserts a hyphen before every upper-case character — correct for a `CamelCase` name, destructive for an all-capitals one. A convention that returns `batch-job-` plus the lower-cased job name passes through that transformation unchanged, because it has no upper-case character left to hyphenate. It is installed on every `AbstractJob` bean by a static `BeanPostProcessor`, which is the same mechanism Boot's own `BatchObservationAutoConfiguration` uses to attach the observation registry to those beans. **(2) Withdraw the observation-derived long-task timer.** Every batch launch logged a registration failure for `spring.batch.job.active`: Spring Batch's `AbstractJob` creates that instrument directly with tag key `spring.batch.job.active.name`, and Micrometer's meter observation handler independently derives a second instrument of the same name with tag keys `spring.batch.job.name` and `spring.batch.job.status`. The Prometheus registry admits one tag-key set per name, so it kept the first and refused the second, once per run, for ever |
| **Alternatives considered** | For (1): **(a) Rename the jobs to `CamelCase` or lower case** — rejected: the member name is the parity anchor and the submission key; a readable trace bought by breaking `--spring.batch.job.name=POSTTRAN` is not a trade. **(b) Set the convention on each of the six job builders via `JobBuilderHelper#observationConvention`** — rejected: six call sites is six chances for a seventh job to be added without one, and the omission would be invisible until someone read a trace. **(c) Accept the mangled name and document it** — rejected: it is unreadable, and nothing depends on it being what it is. For (2): **(d) A `MeterFilter` denying `spring.batch.job.active`** — rejected: it denies *both* instruments and removes the measurement to silence a message about it. **(e) Leave the warning** — rejected: a per-run error-level registration failure trains a reader to ignore registration failures |
| **Rationale** | Both are defects at the seam between two framework components rather than in this application's own logic, and in both cases the honest fix is the narrow one. (1) changes only the human-facing label, which is the field that exists to be human-facing, and leaves every tag and every metric name the framework's. (2) suppresses only the *derived* duplicate, so the instrument that already won the race keeps registering and the batch tier loses nothing it previously had |
| **Observable consequence** | (1) The trace store's operation list shows `batch-job-posttran` and `batch-job-tranrept` in place of `p-o-s-t-t-r-a-n` and `t-r-a-n-r-e-p-t`. Step spans are unchanged — they were always readable, being named from camel-case bean names — and `spring_batch_job_seconds` keeps every tag it had, `spring.batch.job.name` included. (2) The registration warning no longer occurs. **The cost is stated rather than glossed:** the property is global, so `http.server.requests.active` — the in-flight-request gauge — is also withdrawn. Nothing in this repository consumes it: no panel of `observability/grafana/dashboards/carddemo-dashboard.json`, no expression, no alert and no test refers to it. Request *duration* is unaffected, being a `Timer` rather than a long-task timer. Should an in-flight gauge ever be wanted, the correct move is to re-enable the property and rename one of the two colliding batch instruments, not to accept the warning again |
| **Test / gate evidence** | `src/test/java/com/cardemo/unit/config/BatchJobSpanNamingTest.java` — the convention's contextual name for each of the five member names, the identity of `toLowerHyphen` on the produced value, the post-processor's reach over every `AbstractJob` bean, and the delegation of every key value to the framework's own convention. Gate-level: [Gate 8](docs/validation-gates.md#gate-8) |
| **Remediation / follow-up** | None outstanding for (1). For (2), if Spring Batch or Spring Boot stops creating two instruments of one name, the property can be removed and the in-flight gauge returns; nothing else depends on it being `false` |
| **Verification** | `Source-verified`, `Target-verified`, `Test-verified` — 8 August 2026 |

---

<a id="dl-rm-15"></a>

### DL-RM-15 — A legend label that cannot be made to fit, because the label may not be shortened and the layer that would truncate it is not ours

| Field | Content |
|---|---|
| **Classification** | ACCEPTED LIMITATION — a partial resolution, published as partial rather than reported as closed |
| **Severity** | Low — cosmetic, one row of one panel, at one viewport width, with the same information available two other ways on the same screen |
| **Source evidence** | `app/cbl/CBTRN02C.cbl:L419` sets reject code 103's description to the forty-six characters `TRANSACTION RECEIVED AFTER ACCT EXPIRATION`. That string is reproduced byte-for-byte as a `legendFormat` in `observability/grafana/dashboards/carddemo-dashboard.json` and is asserted byte-for-byte by `BatchPipelineE2ETest`, `BatchJobExecutionDeciderTest` and `PostingParityOracle` |
| **Target** | `observability/grafana/dashboards/carddemo-dashboard.json`, panel 7 `Rejection rate by reject code` |
| **Decision** | Keep the label byte-exact and accept that at a 375 px viewport it is clipped without an ellipsis, and that its own per-second value is reachable only by scrolling the legend sideways. The panel's height was raised from 11 to 13 grid units, which removed the vertical overflow, and its legend was left in `list` mode rather than moved to `table` mode. The panel description now states the limitation and points at the two places the same information is fully legible |
| **Alternatives considered** | **(1) Shorten the label** — rejected: it is byte-identical to the COBOL description by design, three test classes assert that string, and the QA finding that raised this defect explicitly rejected shortening for the same reason. **(2) `displayMode: table`** — measured and rejected as a REGRESSION: the table's shared `Name` column sizes to the longest label, 366.9 px, which pins the value column 41.9 px outside the 325 px box and loses **all five** values plus the `Last *` header, where `list` mode loses only the 103 row's value. **(3) `placement: right`** — rejected: Grafana 12.4.6 responsively collapses right placement to a bottom-stacked legend below 768 px, measured as `flex-direction: column` with the legend at the full 325 px content width, so the setting has no effect at the width in question while costing plot area at 1920 px. **(4) `placement: right` plus `legend.width: 120`** — rejected: inert at 375 px and a severe regression at 1920 px, where it cut the label to twelve of forty-six characters and pushed all five values out of view. **(5) `calcs: []`** — rejected: it removes every value, which is the opposite of the requirement. **(6) Convert the panel to a bar gauge**, which does truncate these labels with a proper ellipsis — rejected: it would discard the time dimension that is the panel's entire purpose |
| **Rationale** | The root cause is not in this repository. Grafana's `VizLegend` sets the label element's `width` to its own natural content width — measured at 328.906 px, with `max-width` at 600 px or `none` — so `scrollWidth` equals `clientWidth` and the `text-overflow: ellipsis` the element already carries can never engage. The clipping happens one level out, at the legend's scroll container, whose own `text-overflow` computes to `clip`. Every one of the six `options.legend` permutations available to a provisioned dashboard was measured at 375 px and at 1920 px, and **all six clipped the identical five characters `ATION`**; not one produced an ellipsis. Fixing this properly means changing Grafana's own stylesheet, which ships inside a digest-pinned container image and is not a file this project owns. Between a label that is wrong and a label that is clipped, parity chooses clipped |
| **Observable consequence** | At 375 px the row renders `103 TRANSACTION RECEIVED AFTER ACCT EXPIR` with `ATION` clipped mid-word and no ellipsis, and its `0 c/s` value sits 41.9 px past the container edge. The legend **is** horizontally scrollable — `scrollLeft` reaches 118 and the content really moves — but no scrollbar occupies layout space, so nothing advertises it. The other four rows show their values in full. At 768 px and 1920 px the label and every value render completely, so this is a narrow-viewport-only effect |
| **Test / gate evidence** | Measured twice by an independent browser agent, once per configuration, using character-level `Range` geometry against the container's visible edge — the defect is invisible to text and accessibility assertions because the legend's `textContent` is byte-identical at every width. The six-variant comparison is the evidence that no dashboard-level fix exists. The sibling defect on panel 4 was fixed and verified in the same passes: its glyph shear went from about 7 px of an 18 px row to **zero glyph pixels clipped** |
| **Remediation / follow-up** | For whoever picks this up, in order of preference: constrain the legend label cell's width to the legend container so its existing `text-overflow: ellipsis` engages, which needs either a Grafana change or an operator-supplied stylesheet; or accept a `displayName` field override at narrow widths only, if Grafana ever gains viewport-conditional overrides; or raise it with Grafana as a `VizLegend` responsive-truncation defect. Do **not** resolve it by editing the label |
| **Verification** | Source-verified, Target-verified, Test-verified — 8 August 2026. The runtime geometry readings that establish both the residual and the panel 4 fix are published with their numbers in `docs/validation-gates.md`; this entry records the decision, not the measurement |

---

<a id="noop-registry"></a>

## 15. The parity no-op registry

[`DL-CR-01`](#dl-cr-01) resolves the one conflict between *Rule 1: Build Verify* clause B, which forbids
dead code, and the parity mandate, which requires one-to-one control-flow fidelity. The resolution is that
the parity mandate governs and clause B is satisfied **by tracking** rather than by deletion. This section
is that tracking, in full.

A review established that the tracking was incomplete: clause B's exception requires three legs — a source
citation, an intentional-no-op rationale and an **individual register entry** — and most admitted members
carried only the first two. The register below closes that gap. Every member is here, individually, with
its own identifier, its own `app/**` locator, its reference status and its own reason.

<a id="noop-membership"></a>

### 15.1 The membership rule, published once

Membership is **mechanical, not editorial.** A Java method belongs to this register when, and only when,
all of the following hold of the tree at `src/main/java/com/cardemo/`:

1. it is declared `private void <name>()` or `private static void <name>()` — no parameters, no return
   value, no wider visibility;
2. its body contains **no executable statement** once line and block comments are removed; and
3. it is a method rather than a constructor, a record header or an interface default.

Nothing else is in the register and nothing in the register escapes the rule. In particular a
`protected` no-argument entity constructor is excluded by (1) — it exists for the persistence provider,
not for a COBOL label — and an override with an empty body inherited from a framework contract is excluded
by the same clause.

**No total is declared here, in prose, anywhere.** A count in prose is a second source of truth that goes
stale the moment a member is added, which is exactly how the earlier *"closed at three"* wording came to
misdescribe a register it was meant to define. The count is derived by
`src/test/java/com/cardemo/e2e/GateVerificationTest.java` and written to
`target/gate-verification/gate-verification-summary.properties`; read that property, never this prose.

**The identifier is the identity.** A row's stable identifier is
`NOOP-<simple class name>-<method name>`, which is unique by construction and cannot be invalidated by
insertion, deletion or reordering — there is no ordinal to renumber. It is registered as a prefix in
[§1.3](#identifiers) alongside the `DL-` families.

<a id="noop-bijection"></a>

### 15.2 How this register is verified: bijection, not a floor

The harness derives the census from the tree by the rule in [§15.1](#noop-membership), parses the rows of
[§15.3](#noop-rows) out of this file, and asserts the two sets are **equal in both directions**:

* a census member absent from the register **fails the build** — that is untracked dead code, which is the
  thing clause B actually forbids; and
* a register row naming a method that no longer exists **also fails the build** — a register that outlives
  its subject is a different kind of dishonesty and is caught by the same assertion.

Each row's `app/**` locator and its reference status are re-derived from the tree in the same pass, so a
locator cannot rot and a method cannot silently acquire or lose its only caller without the register
saying so.

An earlier revision of that harness asserted `markerCount >= 3` — that the tree carried **at least** the
three sites the conflict resolution turns on. A floor cannot detect an unregistered member, which is the
whole of what needed detecting, so the floor is **withdrawn** and replaced by the equality above. The
three enumerated sites remain as a spot check of specific locators against the frozen corpus and are
asserted to be consistent with the register rather than to bound it.

<a id="noop-admission"></a>

### 15.3 Why every member is admitted rather than deleted

Four shapes account for the register, and each is a faithful reproduction rather than residue:

| Shape | Why the Java body is empty | Why it is not deleted |
|---|---|---|
| A bare `EXIT` paragraph that is performed | `EXIT` is COBOL's no-operation. It exists so a `PERFORM … THRU` range has a paragraph to stop at, and it transfers to the end of the paragraph — there is nothing for Java to do | Deleting it removes a label from the paragraph map [Gate 7](docs/validation-gates.md#gate-7) reads, and the mandate forbids consolidating a label with its `-EXIT` partner: a label and its `-EXIT` are two labels |
| A paragraph the source declares and never enters | Nothing references it — no `PERFORM`, `GO TO`, `THRU` or `THROUGH` — so it is unreachable in the source too | Its unreachability is itself source behaviour, and in two cases it is the evidence for a preserved legacy defect. Emitting nothing would erase the finding |
| The `CONTINUE` verb | `CONTINUE` does nothing and falls through | Calling it keeps every preserved `ELSE CONTINUE` arm visible at its source line rather than collapsing it into an empty block a later reader deletes as redundant |
| `GOBACK` | Returning to the caller is implicit in Java | It is a genuine label with two distinct entries, and its emptiness is the mechanism behind a recorded defect |

None of the four is deferred work. There is no future change that would fill any of these bodies, because
the corresponding source body is empty in the system of record. That is the distinction clause B draws:
it forbids **untracked** residue and deferred work with no owner, and this register is the owner.

<a id="noop-rows"></a>

### 15.4 The register

Rows are grouped by the class that carries them and ordered by declaration position within the class, which
is the order the harness walks. `Reference status` is `called` when at least one live call site names the
method in its own compilation unit and `unreferenced` when the declaration is the only occurrence — the
latter is the source's own unreachability carried across, never a Java-side omission.

#### `AccountUpdateService` &mdash; derived from `app/cbl/COACTUPC.cbl`

| Stable ID | Java method | Source label | Locator | Reference status | Why it is a no-op |
|---|---|---|---|---|---|
| `NOOP-AccountUpdateService-mainExit0000` | `AccountUpdateService#mainExit0000()` | `0000-MAIN-EXIT` | `app/cbl/COACTUPC.cbl:L1021-L1023` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-processInputs1000Exit` | `AccountUpdateService#processInputs1000Exit()` | `1000-PROCESS-INPUTS-EXIT` | `app/cbl/COACTUPC.cbl:L1036-L1038` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-storePfKeyExit` | `AccountUpdateService#storePfKeyExit()` | `YYYY-STORE-PFKEY-EXIT` | `app/cpy/CSSTRPFY.cpy:L80-L82` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-receiveMap1100Exit` | `AccountUpdateService#receiveMap1100Exit()` | `1100-RECEIVE-MAP-EXIT` | `app/cbl/COACTUPC.cbl:L1426-L1428` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-editMapInputs1200Exit` | `AccountUpdateService#editMapInputs1200Exit()` | `1200-EDIT-MAP-INPUTS-EXIT` | `app/cbl/COACTUPC.cbl:L1678-L1680` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-compareOldNew1205Exit` | `AccountUpdateService#compareOldNew1205Exit()` | `1205-COMPARE-OLD-NEW-EXIT` | `app/cbl/COACTUPC.cbl:L1777-L1779` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-editAccount1210Exit` | `AccountUpdateService#editAccount1210Exit()` | `1210-EDIT-ACCOUNT-EXIT` | `app/cbl/COACTUPC.cbl:L1820-L1822` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-editMandatory1215Exit` | `AccountUpdateService#editMandatory1215Exit()` | `1215-EDIT-MANDATORY-EXIT` | `app/cbl/COACTUPC.cbl:L1852-L1854` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-editYesNo1220Exit` | `AccountUpdateService#editYesNo1220Exit()` | `1220-EDIT-YESNO-EXIT` | `app/cbl/COACTUPC.cbl:L1894-L1896` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-editAlphaRequired1225Exit` | `AccountUpdateService#editAlphaRequired1225Exit()` | `1225-EDIT-ALPHA-REQD-EXIT` | `app/cbl/COACTUPC.cbl:L1951-L1953` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-editAlphanumericRequired1230Exit` | `AccountUpdateService#editAlphanumericRequired1230Exit()` | `1230-EDIT-ALPHANUM-REQD-EXIT` | `app/cbl/COACTUPC.cbl:L2009-L2011` | unreferenced | Bare `EXIT` paragraph **and unreachable**: no `PERFORM`, `GO TO`, `THRU` or `THROUGH` in the program names it, and its partner range is unreachable too. Empty on both sides |
| `NOOP-AccountUpdateService-editAlphaOptional1235Exit` | `AccountUpdateService#editAlphaOptional1235Exit()` | `1235-EDIT-ALPHA-OPT-EXIT` | `app/cbl/COACTUPC.cbl:L2057-L2059` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-editAlphanumericOptional1240Exit` | `AccountUpdateService#editAlphanumericOptional1240Exit()` | `1240-EDIT-ALPHANUM-OPT-EXIT` | `app/cbl/COACTUPC.cbl:L2105-L2107` | unreferenced | Bare `EXIT` paragraph **and unreachable**: no `PERFORM`, `GO TO`, `THRU` or `THROUGH` in the program names it, and its partner range is unreachable too. Empty on both sides |
| `NOOP-AccountUpdateService-editNumericRequired1245Exit` | `AccountUpdateService#editNumericRequired1245Exit()` | `1245-EDIT-NUM-REQD-EXIT` | `app/cbl/COACTUPC.cbl:L2176-L2178` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-editSignedAmount1250Exit` | `AccountUpdateService#editSignedAmount1250Exit()` | `1250-EDIT-SIGNED-9V2-EXIT` | `app/cbl/COACTUPC.cbl:L2221-L2223` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-editUsPhoneExit` | `AccountUpdateService#editUsPhoneExit()` | `EDIT-US-PHONE-EXIT` | `app/cbl/COACTUPC.cbl:L2424-L2426` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-editUsPhoneNumber1260Exit` | `AccountUpdateService#editUsPhoneNumber1260Exit()` | `1260-EDIT-US-PHONE-NUM-EXIT` | `app/cbl/COACTUPC.cbl:L2427-L2429` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-editUsSsn1265Exit` | `AccountUpdateService#editUsSsn1265Exit()` | `1265-EDIT-US-SSN-EXIT` | `app/cbl/COACTUPC.cbl:L2489-L2491` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-editUsStateCode1270Exit` | `AccountUpdateService#editUsStateCode1270Exit()` | `1270-EDIT-US-STATE-CD-EXIT` | `app/cbl/COACTUPC.cbl:L2511-L2513` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-editFicoScore1275Exit` | `AccountUpdateService#editFicoScore1275Exit()` | `1275-EDIT-FICO-SCORE-EXIT` | `app/cbl/COACTUPC.cbl:L2531-L2533` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-editUsStateZipCode1280Exit` | `AccountUpdateService#editUsStateZipCode1280Exit()` | `1280-EDIT-US-STATE-ZIP-CD-EXIT` | `app/cbl/COACTUPC.cbl:L2558-L2560` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-decideAction2000Exit` | `AccountUpdateService#decideAction2000Exit()` | `2000-DECIDE-ACTION-EXIT` | `app/cbl/COACTUPC.cbl:L2643-L2645` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-sendMap3000Exit` | `AccountUpdateService#sendMap3000Exit()` | `3000-SEND-MAP-EXIT` | `app/cbl/COACTUPC.cbl:L2664-L2666` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-screenInit3100Exit` | `AccountUpdateService#screenInit3100Exit()` | `3100-SCREEN-INIT-EXIT` | `app/cbl/COACTUPC.cbl:L2694-L2696` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-setupScreenVars3200Exit` | `AccountUpdateService#setupScreenVars3200Exit()` | `3200-SETUP-SCREEN-VARS-EXIT` | `app/cbl/COACTUPC.cbl:L2727-L2729` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-showInitialValues3201Exit` | `AccountUpdateService#showInitialValues3201Exit()` | `3201-SHOW-INITIAL-VALUES-EXIT` | `app/cbl/COACTUPC.cbl:L2783-L2785` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-showOriginalValues3202Exit` | `AccountUpdateService#showOriginalValues3202Exit()` | `3202-SHOW-ORIGINAL-VALUES-EXIT` | `app/cbl/COACTUPC.cbl:L2867-L2869` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-showUpdatedValues3203Exit` | `AccountUpdateService#showUpdatedValues3203Exit()` | `3203-SHOW-UPDATED-VALUES-EXIT` | `app/cbl/COACTUPC.cbl:L2951-L2953` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-setupInfoMessage3250Exit` | `AccountUpdateService#setupInfoMessage3250Exit()` | `3250-SETUP-INFOMSG-EXIT` | `app/cbl/COACTUPC.cbl:L2983-L2985` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-setupScreenAttributes3300Exit` | `AccountUpdateService#setupScreenAttributes3300Exit()` | `3300-SETUP-SCREEN-ATTRS-EXIT` | `app/cbl/COACTUPC.cbl:L3437-L3439` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-protectAllAttributes3310Exit` | `AccountUpdateService#protectAllAttributes3310Exit()` | `3310-PROTECT-ALL-ATTRS-EXIT` | `app/cbl/COACTUPC.cbl:L3496-L3498` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-unprotectFewAttributes3320Exit` | `AccountUpdateService#unprotectFewAttributes3320Exit()` | `3320-UNPROTECT-FEW-ATTRS-EXIT` | `app/cbl/COACTUPC.cbl:L3562-L3564` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-setupInfoMessageAttributes3390Exit` | `AccountUpdateService#setupInfoMessageAttributes3390Exit()` | `3390-SETUP-INFOMSG-ATTRS-EXIT` | `app/cbl/COACTUPC.cbl:L3584-L3586` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-sendScreen3400Exit` | `AccountUpdateService#sendScreen3400Exit()` | `3400-SEND-SCREEN-EXIT` | `app/cbl/COACTUPC.cbl:L3603-L3605` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-readAccount9000Exit` | `AccountUpdateService#readAccount9000Exit()` | `9000-READ-ACCT-EXIT` | `app/cbl/COACTUPC.cbl:L3647-L3649` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-getCardXrefByAccount9200Exit` | `AccountUpdateService#getCardXrefByAccount9200Exit()` | `9200-GETCARDXREF-BYACCT-EXIT` | `app/cbl/COACTUPC.cbl:L3698-L3700` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-getAccountDataByAccount9300Exit` | `AccountUpdateService#getAccountDataByAccount9300Exit()` | `9300-GETACCTDATA-BYACCT-EXIT` | `app/cbl/COACTUPC.cbl:L3748-L3750` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-getCustomerDataByCustomer9400Exit` | `AccountUpdateService#getCustomerDataByCustomer9400Exit()` | `9400-GETCUSTDATA-BYCUST-EXIT` | `app/cbl/COACTUPC.cbl:L3797-L3799` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-storeFetchedData9500Exit` | `AccountUpdateService#storeFetchedData9500Exit()` | `9500-STORE-FETCHED-DATA-EXIT` | `app/cbl/COACTUPC.cbl:L3885-L3887` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-writeProcessing9600Exit` | `AccountUpdateService#writeProcessing9600Exit()` | `9600-WRITE-PROCESSING-EXIT` | `app/cbl/COACTUPC.cbl:L4105-L4107` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-checkChangeInRecord9700Exit` | `AccountUpdateService#checkChangeInRecord9700Exit()` | `9700-CHECK-CHANGE-IN-REC-EXIT` | `app/cbl/COACTUPC.cbl:L4193-L4195` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountUpdateService-abendRoutineExit` | `AccountUpdateService#abendRoutineExit()` | `ABEND-ROUTINE-EXIT` | `app/cbl/COACTUPC.cbl:L4226-L4228` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |

#### `AccountViewService` &mdash; derived from `app/cbl/COACTVWC.cbl`

| Stable ID | Java method | Source label | Locator | Reference status | Why it is a no-op |
|---|---|---|---|---|---|
| `NOOP-AccountViewService-mainExit0000AtLine408` | `AccountViewService#mainExit0000AtLine408()` | `0000-MAIN-EXIT` | `app/cbl/COACTVWC.cbl:L409-L410` | unreferenced | Bare `EXIT` paragraph **and unreachable**: no `PERFORM`, `GO TO`, `THRU` or `THROUGH` in the program names it, and its partner range is unreachable too. Empty on both sides |
| `NOOP-AccountViewService-mainExit0000AtLine411` | `AccountViewService#mainExit0000AtLine411()` | `0000-MAIN-EXIT` | `app/cbl/COACTVWC.cbl:L412-L413` | unreferenced | Bare `EXIT` paragraph **and unreachable**: no `PERFORM`, `GO TO`, `THRU` or `THROUGH` in the program names it, and its partner range is unreachable too. Empty on both sides |
| `NOOP-AccountViewService-sendMap1000Exit` | `AccountViewService#sendMap1000Exit()` | `1000-SEND-MAP-EXIT` | `app/cbl/COACTVWC.cbl:L428-L429` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountViewService-screenInit1100Exit` | `AccountViewService#screenInit1100Exit()` | `1100-SCREEN-INIT-EXIT` | `app/cbl/COACTVWC.cbl:L458-L459` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountViewService-setupScreenVars1200Exit` | `AccountViewService#setupScreenVars1200Exit()` | `1200-SETUP-SCREEN-VARS-EXIT` | `app/cbl/COACTVWC.cbl:L538-L539` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountViewService-setupScreenAttrs1300Exit` | `AccountViewService#setupScreenAttrs1300Exit()` | `1300-SETUP-SCREEN-ATTRS-EXIT` | `app/cbl/COACTVWC.cbl:L575-L576` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountViewService-sendScreen1400Exit` | `AccountViewService#sendScreen1400Exit()` | `1400-SEND-SCREEN-EXIT` | `app/cbl/COACTVWC.cbl:L593-L594` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountViewService-processInputs2000Exit` | `AccountViewService#processInputs2000Exit()` | `2000-PROCESS-INPUTS-EXIT` | `app/cbl/COACTVWC.cbl:L608-L609` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountViewService-receiveMap2100Exit` | `AccountViewService#receiveMap2100Exit()` | `2100-RECEIVE-MAP-EXIT` | `app/cbl/COACTVWC.cbl:L620-L621` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountViewService-editMapInputs2200Exit` | `AccountViewService#editMapInputs2200Exit()` | `2200-EDIT-MAP-INPUTS-EXIT` | `app/cbl/COACTVWC.cbl:L646-L647` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountViewService-editAccount2210Exit` | `AccountViewService#editAccount2210Exit()` | `2210-EDIT-ACCOUNT-EXIT` | `app/cbl/COACTVWC.cbl:L684-L685` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountViewService-readAcct9000Exit` | `AccountViewService#readAcct9000Exit()` | `9000-READ-ACCT-EXIT` | `app/cbl/COACTVWC.cbl:L721-L722` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountViewService-getCardXrefByAcct9200Exit` | `AccountViewService#getCardXrefByAcct9200Exit()` | `9200-GETCARDXREF-BYACCT-EXIT` | `app/cbl/COACTVWC.cbl:L772-L773` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountViewService-getAcctDataByAcct9300Exit` | `AccountViewService#getAcctDataByAcct9300Exit()` | `9300-GETACCTDATA-BYACCT-EXIT` | `app/cbl/COACTVWC.cbl:L822-L823` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountViewService-getCustDataByCust9400Exit` | `AccountViewService#getCustDataByCust9400Exit()` | `9400-GETCUSTDATA-BYCUST-EXIT` | `app/cbl/COACTVWC.cbl:L871-L872` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountViewService-sendPlainTextExit` | `AccountViewService#sendPlainTextExit()` | `SEND-PLAIN-TEXT-EXIT` | `app/cbl/COACTVWC.cbl:L889-L890` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountViewService-sendLongTextExit` | `AccountViewService#sendLongTextExit()` | `SEND-LONG-TEXT-EXIT` | `app/cbl/COACTVWC.cbl:L908-L909` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-AccountViewService-storePfKeyExit` | `AccountViewService#storePfKeyExit()` | `YYYY-STORE-PFKEY-EXIT` | `app/cpy/CSSTRPFY.cpy:L81-L82` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |

#### `CardListService` &mdash; derived from `app/cbl/COCRDLIC.cbl`

| Stable ID | Java method | Source label | Locator | Reference status | Why it is a no-op |
|---|---|---|---|---|---|
| `NOOP-CardListService-main0000Exit` | `CardListService#main0000Exit()` | `0000-MAIN-EXIT` | `app/cbl/COCRDLIC.cbl:L621` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardListService-sendMap1000Exit` | `CardListService#sendMap1000Exit()` | `1000-SEND-MAP-EXIT` | `app/cbl/COCRDLIC.cbl:L639` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardListService-screenInit1100Exit` | `CardListService#screenInit1100Exit()` | `1100-SCREEN-INIT-EXIT` | `app/cbl/COCRDLIC.cbl:L674` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardListService-screenArrayInit1200Exit` | `CardListService#screenArrayInit1200Exit()` | `1200-SCREEN-ARRAY-INIT-EXIT` | `app/cbl/COCRDLIC.cbl:L746` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardListService-setupArrayAttribs1250Exit` | `CardListService#setupArrayAttribs1250Exit()` | `1250-SETUP-ARRAY-ATTRIBS-EXIT` | `app/cbl/COCRDLIC.cbl:L835` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardListService-setupScreenAttrs1300Exit` | `CardListService#setupScreenAttrs1300Exit()` | `1300-SETUP-SCREEN-ATTRS-EXIT` | `app/cbl/COCRDLIC.cbl:L891` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardListService-setupMessage1400Exit` | `CardListService#setupMessage1400Exit()` | `1400-SETUP-MESSAGE-EXIT` | `app/cbl/COCRDLIC.cbl:L933` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardListService-sendScreen1500Exit` | `CardListService#sendScreen1500Exit()` | `1500-SEND-SCREEN-EXIT` | `app/cbl/COCRDLIC.cbl:L948` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardListService-receiveMap2000Exit` | `CardListService#receiveMap2000Exit()` | `2000-RECEIVE-MAP-EXIT` | `app/cbl/COCRDLIC.cbl:L959` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardListService-receiveScreen2100Exit` | `CardListService#receiveScreen2100Exit()` | `2100-RECEIVE-SCREEN-EXIT` | `app/cbl/COCRDLIC.cbl:L982` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardListService-editInputs2200Exit` | `CardListService#editInputs2200Exit()` | `2200-EDIT-INPUTS-EXIT` | `app/cbl/COCRDLIC.cbl:L999` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardListService-editAccount2210Exit` | `CardListService#editAccount2210Exit()` | `2210-EDIT-ACCOUNT-EXIT` | `app/cbl/COCRDLIC.cbl:L1032` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardListService-editCard2220Exit` | `CardListService#editCard2220Exit()` | `2220-EDIT-CARD-EXIT` | `app/cbl/COCRDLIC.cbl:L1069` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardListService-editArray2250Exit` | `CardListService#editArray2250Exit()` | `2250-EDIT-ARRAY-EXIT` | `app/cbl/COCRDLIC.cbl:L1119` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardListService-filterRecords9500Exit` | `CardListService#filterRecords9500Exit()` | `9500-FILTER-RECORDS-EXIT` | `app/cbl/COCRDLIC.cbl:L1410` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardListService-yyyyStorePfkeyExit` | `CardListService#yyyyStorePfkeyExit()` | `YYYY-STORE-PFKEY-EXIT` | `app/cpy/CSSTRPFY.cpy:L80` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardListService-sendPlainText` | `CardListService#sendPlainText()` | `SEND-PLAIN-TEXT` | `app/cbl/COCRDLIC.cbl:L1422` | unreferenced | Declared and **never entered** in the source — no `PERFORM`, `GO TO`, `THRU` or `THROUGH` names the label — so there is nothing for the Java body to carry |
| `NOOP-CardListService-sendPlainTextExit` | `CardListService#sendPlainTextExit()` | `SEND-PLAIN-TEXT-EXIT` | `app/cbl/COCRDLIC.cbl:L1433` | unreferenced | Bare `EXIT` paragraph **and unreachable**: no `PERFORM`, `GO TO`, `THRU` or `THROUGH` in the program names it, and its partner range is unreachable too. Empty on both sides |
| `NOOP-CardListService-sendLongText` | `CardListService#sendLongText()` | `SEND-LONG-TEXT` | `app/cbl/COCRDLIC.cbl:L1441` | unreferenced | Declared and **never entered** in the source — no `PERFORM`, `GO TO`, `THRU` or `THROUGH` names the label — so there is nothing for the Java body to carry |
| `NOOP-CardListService-sendLongTextExit` | `CardListService#sendLongTextExit()` | `SEND-LONG-TEXT-EXIT` | `app/cbl/COCRDLIC.cbl:L1452` | unreferenced | Bare `EXIT` paragraph **and unreachable**: no `PERFORM`, `GO TO`, `THRU` or `THROUGH` in the program names it, and its partner range is unreachable too. Empty on both sides |
| `NOOP-CardListService-noOperation` | `CardListService#noOperation()` | `CONTINUE` | `app/cbl/COCRDLIC.cbl:L411` | called | Renders the COBOL `CONTINUE` verb, which does nothing and falls through; the locator is its FIRST occurrence in the program, of many. It is *called* rather than left implicit so that every preserved `ELSE CONTINUE` arm and every attribute move with no REST counterpart stays visible at its source line instead of collapsing into an empty block a later reader would delete |

#### `CardDetailService` &mdash; derived from `app/cbl/COCRDSLC.cbl`

| Stable ID | Java method | Source label | Locator | Reference status | Why it is a no-op |
|---|---|---|---|---|---|
| `NOOP-CardDetailService-mainExit0000` | `CardDetailService#mainExit0000()` | `0000-MAIN-EXIT` | `app/cbl/COCRDSLC.cbl:L408` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardDetailService-sendMapExit1000` | `CardDetailService#sendMapExit1000()` | `1000-SEND-MAP-EXIT` | `app/cbl/COCRDSLC.cbl:L423` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardDetailService-screenInitExit1100` | `CardDetailService#screenInitExit1100()` | `1100-SCREEN-INIT-EXIT` | `app/cbl/COCRDSLC.cbl:L453` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardDetailService-setupScreenVarsExit1200` | `CardDetailService#setupScreenVarsExit1200()` | `1200-SETUP-SCREEN-VARS-EXIT` | `app/cbl/COCRDSLC.cbl:L499` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardDetailService-setupScreenAttrsExit1300` | `CardDetailService#setupScreenAttrsExit1300()` | `1300-SETUP-SCREEN-ATTRS-EXIT` | `app/cbl/COCRDSLC.cbl:L559` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardDetailService-sendScreenExit1400` | `CardDetailService#sendScreenExit1400()` | `1400-SEND-SCREEN-EXIT` | `app/cbl/COCRDSLC.cbl:L578` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardDetailService-processInputsExit2000` | `CardDetailService#processInputsExit2000()` | `2000-PROCESS-INPUTS-EXIT` | `app/cbl/COCRDSLC.cbl:L593` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardDetailService-receiveMapExit2100` | `CardDetailService#receiveMapExit2100()` | `2100-RECEIVE-MAP-EXIT` | `app/cbl/COCRDSLC.cbl:L605` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardDetailService-editMapInputsExit2200` | `CardDetailService#editMapInputsExit2200()` | `2200-EDIT-MAP-INPUTS-EXIT` | `app/cbl/COCRDSLC.cbl:L643` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardDetailService-editAccountExit2210` | `CardDetailService#editAccountExit2210()` | `2210-EDIT-ACCOUNT-EXIT` | `app/cbl/COCRDSLC.cbl:L681` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardDetailService-editCardExit2220` | `CardDetailService#editCardExit2220()` | `2220-EDIT-CARD-EXIT` | `app/cbl/COCRDSLC.cbl:L722` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardDetailService-readDataExit9000` | `CardDetailService#readDataExit9000()` | `9000-READ-DATA-EXIT` | `app/cbl/COCRDSLC.cbl:L732` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardDetailService-getCardByAcctCardExit9100` | `CardDetailService#getCardByAcctCardExit9100()` | `9100-GETCARD-BYACCTCARD-EXIT` | `app/cbl/COCRDSLC.cbl:L775` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardDetailService-getCardByAcct9150` | `CardDetailService#getCardByAcct9150()` | `9150-GETCARD-BYACCT` | `app/cbl/COCRDSLC.cbl:L783-L807` | unreferenced | Declared and **never entered** in the source — no `PERFORM`, `GO TO`, `THRU` or `THROUGH` names the label — so there is nothing for the Java body to carry |
| `NOOP-CardDetailService-getCardByAcctExit9150` | `CardDetailService#getCardByAcctExit9150()` | `9150-GETCARD-BYACCT-EXIT` | `app/cbl/COCRDSLC.cbl:L810-L811` | unreferenced | Bare `EXIT` paragraph **and unreachable**: no `PERFORM`, `GO TO`, `THRU` or `THROUGH` in the program names it, and its partner range is unreachable too. Empty on both sides |
| `NOOP-CardDetailService-sendLongText` | `CardDetailService#sendLongText()` | `SEND-LONG-TEXT` | `app/cbl/COCRDSLC.cbl:L379-L380` | unreferenced | Declared and **never entered** in the source — no `PERFORM`, `GO TO`, `THRU` or `THROUGH` names the label — so there is nothing for the Java body to carry |
| `NOOP-CardDetailService-sendLongTextExit` | `CardDetailService#sendLongTextExit()` | `SEND-LONG-TEXT-EXIT` | `app/cbl/COCRDSLC.cbl:L831` | unreferenced | Bare `EXIT` paragraph **and unreachable**: no `PERFORM`, `GO TO`, `THRU` or `THROUGH` in the program names it, and its partner range is unreachable too. Empty on both sides |
| `NOOP-CardDetailService-sendPlainTextExit` | `CardDetailService#sendPlainTextExit()` | `SEND-PLAIN-TEXT-EXIT` | `app/cbl/COCRDSLC.cbl:L379-L380` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardDetailService-storePfKeyExitYyyy` | `CardDetailService#storePfKeyExitYyyy()` | `YYYY-STORE-PFKEY-EXIT` | `app/cpy/CSSTRPFY.cpy:L284-L285` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |

#### `CardUpdateService` &mdash; derived from `app/cbl/COCRDUPC.cbl`

| Stable ID | Java method | Source label | Locator | Reference status | Why it is a no-op |
|---|---|---|---|---|---|
| `NOOP-CardUpdateService-mainExit0000` | `CardUpdateService#mainExit0000()` | `0000-MAIN-EXIT` | `app/cbl/COCRDUPC.cbl:L560` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-processInputsExit1000` | `CardUpdateService#processInputsExit1000()` | `1000-PROCESS-INPUTS-EXIT` | `app/cbl/COCRDUPC.cbl:L575` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-receiveMapExit1100` | `CardUpdateService#receiveMapExit1100()` | `1100-RECEIVE-MAP-EXIT` | `app/cbl/COCRDUPC.cbl:L638` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-editMapInputsExit1200` | `CardUpdateService#editMapInputsExit1200()` | `1200-EDIT-MAP-INPUTS-EXIT` | `app/cbl/COCRDUPC.cbl:L717` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-editAccountExit1210` | `CardUpdateService#editAccountExit1210()` | `1210-EDIT-ACCOUNT-EXIT` | `app/cbl/COCRDUPC.cbl:L758` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-editCardExit1220` | `CardUpdateService#editCardExit1220()` | `1220-EDIT-CARD-EXIT` | `app/cbl/COCRDUPC.cbl:L802` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-editNameExit1230` | `CardUpdateService#editNameExit1230()` | `1230-EDIT-NAME-EXIT` | `app/cbl/COCRDUPC.cbl:L841` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-editCardStatusExit1240` | `CardUpdateService#editCardStatusExit1240()` | `1240-EDIT-CARDSTATUS-EXIT` | `app/cbl/COCRDUPC.cbl:L874` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-editExpiryMonthExit1250` | `CardUpdateService#editExpiryMonthExit1250()` | `1250-EDIT-EXPIRY-MON-EXIT` | `app/cbl/COCRDUPC.cbl:L910` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-editExpiryYearExit1260` | `CardUpdateService#editExpiryYearExit1260()` | `1260-EDIT-EXPIRY-YEAR-EXIT` | `app/cbl/COCRDUPC.cbl:L945` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-decideActionExit2000` | `CardUpdateService#decideActionExit2000()` | `2000-DECIDE-ACTION-EXIT` | `app/cbl/COCRDUPC.cbl:L1029` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-sendMapExit3000` | `CardUpdateService#sendMapExit3000()` | `3000-SEND-MAP-EXIT` | `app/cbl/COCRDUPC.cbl:L1048` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-screenInitExit3100` | `CardUpdateService#screenInitExit3100()` | `3100-SCREEN-INIT-EXIT` | `app/cbl/COCRDUPC.cbl:L1078` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-setupScreenVarsExit3200` | `CardUpdateService#setupScreenVarsExit3200()` | `3200-SETUP-SCREEN-VARS-EXIT` | `app/cbl/COCRDUPC.cbl:L1136` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-setupInfoMsgExit3250` | `CardUpdateService#setupInfoMsgExit3250()` | `3250-SETUP-INFOMSG-EXIT` | `app/cbl/COCRDUPC.cbl:L1165` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-setupScreenAttrsExit3300` | `CardUpdateService#setupScreenAttrsExit3300()` | `3300-SETUP-SCREEN-ATTRS-EXIT` | `app/cbl/COCRDUPC.cbl:L1320` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-sendScreenExit3400` | `CardUpdateService#sendScreenExit3400()` | `3400-SEND-SCREEN-EXIT` | `app/cbl/COCRDUPC.cbl:L1338` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-readDataExit9000` | `CardUpdateService#readDataExit9000()` | `9000-READ-DATA-EXIT` | `app/cbl/COCRDUPC.cbl:L1372` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-getCardByAcctCardExit9100` | `CardUpdateService#getCardByAcctCardExit9100()` | `9100-GETCARD-BYACCTCARD-EXIT` | `app/cbl/COCRDUPC.cbl:L1416` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-writeProcessingExit9200` | `CardUpdateService#writeProcessingExit9200()` | `9200-WRITE-PROCESSING-EXIT` | `app/cbl/COCRDUPC.cbl:L1495` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-checkChangeInRecExit9300` | `CardUpdateService#checkChangeInRecExit9300()` | `9300-CHECK-CHANGE-IN-REC-EXIT` | `app/cbl/COCRDUPC.cbl:L1522` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-storePfKeyExit` | `CardUpdateService#storePfKeyExit()` | `YYYY-STORE-PFKEY-EXIT` | `app/cpy/CSSTRPFY.cpy:L80` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-CardUpdateService-abendRoutineExit` | `CardUpdateService#abendRoutineExit()` | `ABEND-ROUTINE-EXIT` | `app/cbl/COCRDUPC.cbl:L1550-L1552` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |

#### `DateValidationService` &mdash; derived from `app/cbl/CSUTLDTC.cbl with app/cpy/CSUTLDPY.cpy and app/cpy/CSUTLDWY.cpy`

| Stable ID | Java method | Source label | Locator | Reference status | Why it is a no-op |
|---|---|---|---|---|---|
| `NOOP-DateValidationService-a000MainExit` | `DateValidationService#a000MainExit()` | `A000-MAIN-EXIT` | `app/cbl/CSUTLDTC.cbl:L152-L154` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-DateValidationService-editYearCcyyExit` | `DateValidationService#editYearCcyyExit()` | `EDIT-YEAR-CCYY-EXIT` | `app/cpy/CSUTLDPY.cpy:L88-L90` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-DateValidationService-editMonthExit` | `DateValidationService#editMonthExit()` | `EDIT-MONTH-EXIT` | `app/cpy/CSUTLDPY.cpy:L145-L147` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-DateValidationService-editDayExit` | `DateValidationService#editDayExit()` | `EDIT-DAY-EXIT` | `app/cpy/CSUTLDPY.cpy:L205-L207` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-DateValidationService-editDayMonthYearExit` | `DateValidationService#editDayMonthYearExit()` | `EDIT-DAY-MONTH-YEAR-EXIT` | `app/cpy/CSUTLDPY.cpy:L280-L282` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-DateValidationService-editDateCcyymmddExit` | `DateValidationService#editDateCcyymmddExit()` | `EDIT-DATE-CCYYMMDD-EXIT` | `app/cpy/CSUTLDPY.cpy:L329-L331` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-DateValidationService-editDateOfBirthExit` | `DateValidationService#editDateOfBirthExit()` | `EDIT-DATE-OF-BIRTH-EXIT` | `app/cpy/CSUTLDPY.cpy:L370-L372` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |

#### `FileService` &mdash; derived from `app/cbl/CBSTM03B.CBL`

| Stable ID | Java method | Source label | Locator | Reference status | Why it is a no-op |
|---|---|---|---|---|---|
| `NOOP-FileService-goback` | `FileService#goback()` | `9999-GOBACK` | `app/cbl/CBSTM03B.CBL:L130` | called | `GOBACK` returns control to the caller, which is implicit in Java, so the paragraph has no executable counterpart. Retained because it is a genuine label with two distinct entries — the `WHEN OTHER` branch and the fall-through — and its emptiness is itself the evidence for the unknown-DD-name defect recorded in [`DL-DV-07`](#dl-dv-07) |
| `NOOP-FileService-trnxfileTerminator` | `FileService#trnxfileTerminator()` | `1999-EXIT` | `app/cbl/CBSTM03B.CBL:L154` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-FileService-xreffileTerminator` | `FileService#xreffileTerminator()` | `2999-EXIT` | `app/cbl/CBSTM03B.CBL:L178` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-FileService-custfileTerminator` | `FileService#custfileTerminator()` | `3999-EXIT` | `app/cbl/CBSTM03B.CBL:L203` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |
| `NOOP-FileService-acctfileTerminator` | `FileService#acctfileTerminator()` | `4999-EXIT` | `app/cbl/CBSTM03B.CBL:L228` | called | Bare `EXIT` paragraph. `EXIT` is COBOL's no-operation, present so that a `PERFORM … THRU` range has a paragraph to stop at; there is no executable Java counterpart |

#### `InterestCalculationProcessor` &mdash; derived from `app/cbl/CBACT04C.cbl`

| Stable ID | Java method | Source label | Locator | Reference status | Why it is a no-op |
|---|---|---|---|---|---|
| `NOOP-InterestCalculationProcessor-computeFees` | `InterestCalculationProcessor#computeFees()` | `1400-COMPUTE-FEES` | `app/cbl/CBACT04C.cbl:L518` | called | The source paragraph is empty apart from a `* To be implemented` comment written by the legacy authors, yet it is genuinely `PERFORM`ed at `app/cbl/CBACT04C.cbl:L216`. The emptiness **is** the behaviour. This is the site the no-dead-code clause and the parity mandate collide over: [`DL-PP-05`](#dl-pp-05), resolved in [`DL-CR-01`](#dl-cr-01) |

<a id="noop-nonmethod"></a>

### 15.5 Retained artefacts that are not methods

Two of the three sites [`DL-CR-01`](#dl-cr-01) enumerates are not empty methods and therefore cannot appear
in [§15.4](#noop-rows); they are registered here instead, under the same obligation and with the same
fields as any other entry.

| Stable ID | Retained artefact | Locator | Why it stays | Reference |
|---|---|---|---|---|
| `NOOP-CBTRN02C-109` | Reject code 109 as an enum constant on `RejectCode` | `app/cbl/CBTRN02C.cbl:L556`, in the paragraph at `:L545`&ndash;`:L560`, cleared at `:L208` | Assigned on a reachable path but never observable as a reject outcome, because the paragraph runs only on the already-validated path. The constant must exist because the assignment is real code — which is why there are five reject constants and not four | [`DL-PP-03`](#dl-pp-03) |
| `NOOP-CBSTM03A-CRJMP` | The redundant outer-index assignment in the statement mainline | `app/cbl/CBSTM03A.CBL:L324` | The mainline sets the index before a `VARYING` loop that re-initialises it anyway. Preserved verbatim for control-flow fidelity | [`DL-LD-07`](#dl-ld-07) |

The third, `NOOP-CBACT04C-1400`, **is** a method: it is `NOOP-InterestCalculationProcessor-computeFees` in
[§15.4](#noop-rows). The two identifiers name one artefact from the two sides of the migration — the COBOL
paragraph and its Java counterpart — and are kept distinct because `DL-CR-01`, `TRACEABILITY_MATRIX.md`
and [Gate 7](docs/validation-gates.md#gate-7) cite the COBOL-side form.

**What is still not a member.** The criterion is unchanged by this section: the artefact must be a genuine
no-op or unreachable value that the frozen source nevertheless reaches. A preserved asymmetry, a preserved
absent guard and a preserved misspelling are documented source *behaviour* at their own locators, not
no-ops, so they are recorded in [§6](#legacy-defects) and not here.
