# ============================================================================
# Source: Batch COBOL programs (app/cbl/CB*.cbl) + JCL batch jobs
#         (app/jcl/*.jcl) — Mainframe-to-Cloud migration
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
"""Individual PySpark Glue job scripts for the CardDemo batch pipeline.

Each job script replaces exactly one batch COBOL program
(``app/cbl/CB*.cbl`` / ``app/cbl/CB*.CBL``) or JCL-only job
(``app/jcl/*.jcl``) from the mainframe CardDemo application. The scripts
are designed to run on the managed AWS Glue 5.1 runtime (Apache Spark
3.5.6, Python 3.11, Scala 2.12.18) and are orchestrated end-to-end by
AWS Step Functions — replacing the JES2 / JCL ``COND`` parameter
chaining of the source z/OS environment.

Pipeline Stage Jobs
-------------------
posttran_job
    Stage 1 — Transaction posting. Converts
    ``app/cbl/CBTRN02C.cbl`` + ``app/jcl/POSTTRAN.jcl``. Implements the
    4-stage validation cascade (card cross-reference → account lookup →
    credit-limit / cash-limit checks → dual write) with preserved
    reject codes 100–109.

intcalc_job
    Stage 2 — Interest calculation. Converts
    ``app/cbl/CBACT04C.cbl`` + ``app/jcl/INTCALC.jcl``. Implements the
    per-(account, type, category) interest formula
    ``(TRAN-CAT-BAL × DIS-INT-RATE) / 1200`` with the DEFAULT / ZEROAPR
    disclosure-group fallback — the formula is intentionally NOT
    algebraically simplified (AAP §0.7.1).

combtran_job
    Stage 3 — Merge / sort transactions. Converts
    ``app/jcl/COMBTRAN.jcl`` (DFSORT + IDCAMS REPRO, no COBOL source)
    into a pure PySpark merge-and-sort stage that unions the posted
    day's transactions with the master transaction history, ordered by
    ``TRAN-ID``, before downstream Stage 4 consumption.

creastmt_job
    Stage 4a — Statement generation. Converts
    ``app/cbl/CBSTM03A.CBL`` + ``app/cbl/CBSTM03B.CBL`` +
    ``app/jcl/CREASTMT.JCL``. Produces monthly account statements in
    both plain-text and HTML formats via a 4-entity join
    (Account ⋈ Customer ⋈ CardCrossReference ⋈ Transaction) with
    output written to S3 objects that replace the original GDG
    generations.

tranrept_job
    Stage 4b — Transaction reporting. Converts
    ``app/cbl/CBTRN03C.cbl`` + ``app/jcl/TRANREPT.jcl``. Produces the
    date-filtered transaction detail report with the 3-level totals
    (per-account, per-page, report-grand-total) preserved exactly from
    the COBOL control-break logic.

Utility Jobs
------------
prtcatbl_job
    Category-balance print utility. Converts ``app/jcl/PRTCATBL.jcl``
    (no COBOL source — pure IDCAMS print) into a PySpark scan of the
    ``transaction_category_balance`` table for operator visibility.

daily_tran_driver_job
    Daily transaction driver. Converts ``app/cbl/CBTRN01C.cbl`` — the
    entry-point driver that reads the inbound daily-transaction
    sequential file and enqueues it for the POSTTRAN stage.

Diagnostic Reader Jobs
----------------------
read_account_job
    Account diagnostic reader. Converts ``app/cbl/CBACT01C.cbl`` +
    ``app/jcl/READACCT.jcl`` — sequential dump of the ``account`` table
    for operational troubleshooting.

read_card_job
    Card diagnostic reader. Converts ``app/cbl/CBACT02C.cbl`` +
    ``app/jcl/READCARD.jcl`` — sequential dump of the ``card`` table.

read_customer_job
    Customer diagnostic reader. Converts ``app/cbl/CBCUS01C.cbl`` +
    ``app/jcl/READCUST.jcl`` — sequential dump of the ``customer``
    table.

read_xref_job
    Cross-reference diagnostic reader. Converts
    ``app/cbl/CBACT03C.cbl`` + ``app/jcl/READXREF.jcl`` — sequential
    dump of the ``card_cross_reference`` table.

Pipeline Execution Order
------------------------
The 5-stage batch pipeline preserves the original JCL execution order
exactly. Inter-stage data dependencies flow through Aurora PostgreSQL
tables (replacing shared VSAM datasets) and S3 objects (replacing GDG
generations). A failure in any upstream stage halts downstream stages,
mirroring the JCL ``COND`` parameter semantics::

    Stage 1 (POSTTRAN)  →  Stage 2 (INTCALC)  →  Stage 3 (COMBTRAN)
                                                          │
                                      ┌───────────────────┴───────────────────┐
                                      ▼                                       ▼
                            Stage 4a (CREASTMT)                     Stage 4b (TRANREPT)

Stages 4a (CREASTMT) and 4b (TRANREPT) run in parallel after Stage 3
completes successfully, matching the original JCL architecture.

Design Notes
------------
* **No eager imports**: This package ``__init__`` performs NO imports
  of its job submodules. Each PySpark Glue job is a fully self-contained
  standalone script with its own ``if __name__ == "__main__": main()``
  entry point. AWS Glue invokes each script directly via the
  ``--JOB_NAME`` and ``--scriptLocation`` parameters — there is no
  package-level bootstrap step. Lazy loading keeps cold-start overhead
  minimal for AWS Glue workers, avoids pulling API-layer-only
  dependencies (FastAPI, SQLAlchemy async) into the Spark driver
  process, and eliminates circular-import risk between the API and
  batch layers.

* **Package marker only**: This file exists solely to establish
  ``src.batch.jobs`` as an explicit package (PEP 328) so that
  ``from src.batch.jobs.posttran_job import main`` resolves
  unambiguously under every execution context: local ``pytest`` runs,
  AWS Glue ``--extra-py-files`` ``.zip`` shipping, Step Functions
  job invocation, and static-analysis tooling (``mypy``, ``ruff``).

* **No ``__version__``**: The batch-layer version is declared once, at
  the parent package level (:mod:`src.batch`). Individual job
  submodules and this jobs-package marker deliberately do not duplicate
  it — consumers that need the version should import it from
  :mod:`src.batch`.

* **Financial precision**: Every job that performs monetary arithmetic
  uses ``decimal.Decimal`` with ``ROUND_HALF_EVEN`` (banker's rounding)
  to preserve the COBOL ``PIC S9(n)V99`` ``ROUNDED`` semantics of the
  original programs. No floating-point arithmetic is permitted for any
  financial calculation — the discipline is enforced centrally in
  :mod:`src.shared.utils.decimal_utils`.

* **Python 3.11+**: The batch layer targets Python 3.11 exactly — the
  version provided by the managed AWS Glue 5.1 runtime. ``aws-glue-libs``
  is intentionally NOT pip-installed (it ships with the Glue managed
  runtime); for local unit testing, PySpark 3.5.6 is used directly.

* **Apache License 2.0**: Inherited from the original AWS CardDemo
  mainframe reference application.

See Also
--------
:mod:`src.batch`                   — parent package (holds ``__version__``)
:mod:`src.batch.common`            — shared Glue / DB / S3 infrastructure
:mod:`src.batch.pipeline`          — Step Functions state-machine definition
AAP §0.4.1 — Refactored Structure Planning
AAP §0.5.1 — File-by-File Transformation Plan (batch jobs mapping)
AAP §0.7.1 — Refactoring-Specific Rules (preserve business logic exactly)
AAP §0.7.2 — Financial Precision (``Decimal`` + ``ROUND_HALF_EVEN``)
"""

# ----------------------------------------------------------------------------
# Explicit re-export list.
#
# Intentionally EMPTY. Each PySpark Glue job submodule is a standalone
# entry point invoked directly by AWS Glue / Step Functions — there are
# no symbols that this package marker should re-export. Consumers must
# import from the specific job submodule they need, for example::
#
#     from src.batch.jobs.posttran_job import main
#     from src.batch.jobs.intcalc_job import main
#
# ``from src.batch.jobs import *`` correctly imports nothing. This
# reinforces the lazy-loading contract and mirrors the pattern used by
# :mod:`src` itself.
# ----------------------------------------------------------------------------
__all__: list[str] = []
