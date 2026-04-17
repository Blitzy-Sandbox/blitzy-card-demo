# ============================================================================
# Source: JCL batch pipeline orchestration (app/jcl/POSTTRAN.jcl,
#         app/jcl/INTCALC.jcl, app/jcl/COMBTRAN.jcl, app/jcl/CREASTMT.JCL,
#         app/jcl/TRANREPT.jcl) — Mainframe-to-Cloud migration
# ============================================================================
# Copyright Amazon.com, Inc. or its affiliates.
# All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# You may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# ============================================================================
"""AWS Step Functions batch pipeline orchestration for the CardDemo application.

This module contains the AWS Step Functions state-machine definition that
orchestrates the 5-stage CardDemo batch pipeline, replacing the JES2 / JCL
``COND`` parameter chaining of the source z/OS mainframe environment.
The state-machine definition itself lives beside this module as the
``step_functions_definition.json`` Amazon States Language (ASL) document —
this Python file is a pure package marker so that
``src.batch.pipeline`` resolves unambiguously as an explicit PEP 328
package under every execution context (local ``pytest`` runs, AWS Glue
``--extra-py-files`` ``.zip`` shipping, Step Functions job invocation,
static-analysis tooling such as ``mypy`` and ``ruff``).

Pipeline Execution Order
------------------------
The 5-stage batch pipeline preserves the original JCL execution order
exactly. Stage 3 output feeds both Stage 4a and Stage 4b, which run
concurrently as a Step Functions ``Parallel`` branch — mirroring the
original JCL architecture where ``CREASTMT`` and ``TRANREPT`` are
independently dispatchable after ``COMBTRAN`` completes successfully.
Inter-stage data dependencies flow through Aurora PostgreSQL tables
(replacing shared VSAM datasets) and S3 objects (replacing GDG
generations). A failure in any upstream stage halts downstream stages,
matching the JCL ``COND=(0,NE)`` semantics found in ``CREASTMT.JCL``::

    Stage 1 (POSTTRAN)  →  Stage 2 (INTCALC)  →  Stage 3 (COMBTRAN)
                                                          │
                                      ┌───────────────────┴───────────────────┐
                                      ▼                                       ▼
                            Stage 4a (CREASTMT)                     Stage 4b (TRANREPT)

Source JCL Jobs
---------------
POSTTRAN.jcl
    Stage 1 — Transaction posting. Executes ``PGM=CBTRN02C`` with DD
    bindings for ``TRANFILE`` (master transaction KSDS), ``DALYTRAN``
    (inbound daily transaction PS), ``XREFFILE`` (card cross-reference
    KSDS), ``ACCTFILE`` (account KSDS), ``TCATBALF`` (transaction
    category balance KSDS), and ``DALYREJS`` (daily rejects GDG
    generation). Target: ``src.batch.jobs.posttran_job`` on AWS Glue 5.1.

INTCALC.jcl
    Stage 2 — Interest calculation. Executes ``PGM=CBACT04C`` with a
    date ``PARM`` and DD bindings for ``TCATBALF``, ``XREFFILE`` (+
    ``XREFFIL1`` AIX path), ``ACCTFILE``, ``DISCGRP`` (disclosure group
    KSDS), and ``TRANSACT`` (system-generated transaction GDG
    generation). Preserves the per-(account, type, category) interest
    formula ``(TRAN-CAT-BAL × DIS-INT-RATE) / 1200`` with the
    DEFAULT / ZEROAPR disclosure-group fallback. Target:
    ``src.batch.jobs.intcalc_job``.

COMBTRAN.jcl
    Stage 3 — Merge / sort. Executes ``PGM=SORT`` (``STEP05R``) followed
    by ``PGM=IDCAMS`` (``STEP10`` with ``REPRO``) — no COBOL source.
    Unions ``TRANSACT.BKUP(0)`` with ``SYSTRAN(0)`` ordered by
    ``TRAN-ID`` and loads the combined file into ``TRANSACT.VSAM.KSDS``.
    Target: ``src.batch.jobs.combtran_job`` (pure PySpark merge/sort).

CREASTMT.JCL
    Stage 4a — Statement generation. Executes ``PGM=IDCAMS``
    (``DELDEF01`` — reset output KSDS), ``PGM=SORT`` (``STEP010`` — sort
    by card number + tran ID), ``PGM=IDCAMS`` (``STEP020`` — REPRO into
    TRXFL KSDS, guarded by ``COND=(0,NE)``), ``PGM=IEFBR14``
    (``STEP030`` — delete prior-run report files), and ``PGM=CBSTM03A``
    (``STEP040`` — statement generation driver that dynamically calls
    the ``CBSTM03B`` file-service subroutine). Produces both plain-text
    (``STMTFILE``) and HTML (``HTMLFILE``) outputs via a 4-entity join
    (Account ⋈ Customer ⋈ CardCrossReference ⋈ Transaction). Target:
    ``src.batch.jobs.creastmt_job`` writing to S3 objects that replace
    the original GDG generations.

TRANREPT.jcl
    Stage 4b — Transaction reporting. Executes the ``REPROC`` procedure
    (unload ``TRANSACT.VSAM.KSDS`` to ``TRANSACT.BKUP(+1)``), followed
    by ``PGM=SORT`` (filter on ``TRAN-PROC-DT`` between
    ``PARM-START-DATE`` and ``PARM-END-DATE`` and sort by
    ``TRAN-CARD-NUM``), and ``PGM=CBTRN03C`` (formatted report
    generation with the preserved 3-level totals — per-account,
    per-page, report-grand-total). Target: ``src.batch.jobs.tranrept_job``.

Contents
--------
``step_functions_definition.json``
    AWS Step Functions Amazon States Language (ASL) state-machine
    definition that wires the five Glue jobs into the sequential and
    parallel execution graph shown above. The JSON document is the
    single source of truth for pipeline topology — this Python module
    deliberately contains no orchestration logic and no imports of the
    JSON file. Deployment tooling (``infra/``, GitHub Actions
    ``deploy-glue.yml``) reads the JSON directly and publishes it to
    AWS Step Functions.

Design Notes
------------
* **No imports — pure package marker**: This ``__init__.py`` performs
  NO imports of submodules, the sibling JSON file, or any third-party
  packages. It exists solely to establish ``src.batch.pipeline`` as an
  explicit PEP 328 package so that references from test fixtures,
  deployment scripts, and static-analysis tooling resolve
  unambiguously.

* **No ``__version__``**: The batch-layer version is declared once at
  the parent package level (:mod:`src.batch`). This sub-package
  deliberately does not duplicate it — consumers that need the version
  should import it from :mod:`src.batch`.

* **No ``ROUND_HALF_EVEN`` / Decimal concerns here**: This package
  contains no financial arithmetic. All monetary calculations live in
  the individual Glue job modules under :mod:`src.batch.jobs` and use
  ``decimal.Decimal`` with banker's rounding to preserve the COBOL
  ``PIC S9(n)V99`` ``ROUNDED`` semantics (see AAP §0.7.2).

* **Python 3.11+**: Aligned with the managed AWS Glue 5.1 runtime
  (Apache Spark 3.5.6, Python 3.11, Scala 2.12.18).

* **Apache License 2.0**: Inherited from the original AWS CardDemo
  mainframe reference application.

See Also
--------
:mod:`src.batch`                   — parent package (holds ``__version__``)
:mod:`src.batch.jobs`              — individual PySpark Glue job scripts
:mod:`src.batch.jobs.posttran_job` — Stage 1 implementation
:mod:`src.batch.jobs.intcalc_job`  — Stage 2 implementation
:mod:`src.batch.jobs.combtran_job` — Stage 3 implementation
:mod:`src.batch.jobs.creastmt_job` — Stage 4a implementation
:mod:`src.batch.jobs.tranrept_job` — Stage 4b implementation
AAP §0.4.1 — Refactored Structure Planning
AAP §0.4.4 — Key Architectural Decisions (batch layer)
AAP §0.5.1 — File-by-File Transformation Plan (pipeline mapping)
AAP §0.7.1 — Refactoring-Specific Rules (preserve pipeline order exactly)
"""

# ----------------------------------------------------------------------------
# Explicit re-export list.
#
# Intentionally EMPTY. The Step Functions state-machine definition lives
# in the sibling ``step_functions_definition.json`` file — there are no
# Python symbols that this package marker should re-export. Consumers
# that need the pipeline topology should read the JSON document directly
# (e.g., deployment workflows in ``.github/workflows/deploy-glue.yml``
# and Terraform / CloudFormation templates under ``infra/``).
#
# ``from src.batch.pipeline import *`` correctly imports nothing — this
# reinforces the marker-only contract of this module and mirrors the
# pattern established by :mod:`src`, :mod:`src.batch`, and
# :mod:`src.batch.jobs`.
# ----------------------------------------------------------------------------
__all__: list[str] = []
