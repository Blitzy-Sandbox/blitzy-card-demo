# ============================================================================
# Source: Batch COBOL programs (app/cbl/CB*.cbl) + JCL jobs (app/jcl/*.jcl)
#         — Mainframe-to-Cloud migration
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
"""CardDemo — Batch Processing Module.

PySpark / AWS Glue batch-processing layer for the CardDemo cloud-native
application. Converted from batch COBOL programs
(``app/cbl/CB*.cbl``, ``app/cbl/CB*.CBL``) and JCL jobs
(``app/jcl/*.jcl``) as part of the mainframe-to-cloud modernization
effort (Mainframe-to-Cloud migration).

Runtime target: AWS Glue 5.1 (Apache Spark 3.5.6, Python 3.11,
Scala 2.12.18). Jobs read from and write to AWS Aurora PostgreSQL via
JDBC (``pg8000``/``psycopg2`` fallback) and exchange GDG-equivalent
artifacts through versioned objects in AWS S3. Pipeline sequencing is
orchestrated by AWS Step Functions — replacing the JES2 / JCL ``COND``
parameter chaining of the source z/OS environment.

Subpackages
-----------
common
    Shared infrastructure for all Glue jobs — GlueContext and
    SparkSession factory (``glue_context.py``), Aurora PostgreSQL JDBC
    connector with AWS Secrets Manager credential retrieval
    (``db_connector.py``), and S3 read/write helpers for GDG-equivalent
    output (``s3_utils.py``).

jobs
    Individual PySpark job scripts — 11 scripts mapping to the 10 batch
    COBOL programs plus the DFSORT+REPRO merge stage (Stage 3) that has
    no original COBOL source:

    * ``posttran_job.py``          — Stage 1: CBTRN02C (Transaction posting,
                                     4-stage validation cascade, reject
                                     codes 100–109).
    * ``intcalc_job.py``           — Stage 2: CBACT04C (Interest calculation,
                                     formula ``(TRAN-CAT-BAL × DIS-INT-RATE)
                                     / 1200``, DEFAULT/ZEROAPR fallback).
    * ``combtran_job.py``          — Stage 3: DFSORT + REPRO equivalent
                                     (pure PySpark merge/sort; no COBOL
                                     source).
    * ``creastmt_job.py``          — Stage 4a: CBSTM03A + CBSTM03B
                                     (Statement generation — text and
                                     HTML formats, 4-entity join).
    * ``tranrept_job.py``          — Stage 4b: CBTRN03C (Transaction
                                     reporting, 3-level totals, date
                                     filtering).
    * ``prtcatbl_job.py``          — Category-balance print utility
                                     (from PRTCATBL.jcl).
    * ``daily_tran_driver_job.py`` — CBTRN01C (daily transaction driver).
    * ``read_account_job.py``      — CBACT01C (account diagnostic reader).
    * ``read_card_job.py``         — CBACT02C (card diagnostic reader).
    * ``read_customer_job.py``     — CBCUS01C (customer diagnostic reader).
    * ``read_xref_job.py``         — CBACT03C (cross-reference
                                     diagnostic reader).

pipeline
    AWS Step Functions state-machine definition
    (``step_functions_definition.json``) orchestrating the sequential
    and parallel stages of the batch pipeline.

Batch Pipeline Stages
---------------------
The 5-stage batch pipeline preserves the original JCL execution order
exactly — inter-stage data dependencies flow through Aurora PostgreSQL
tables (replacing shared VSAM datasets) and S3 objects (replacing GDG
generations). A failure in any upstream stage halts downstream stages,
mirroring the JCL ``COND`` parameter semantics::

    Stage 1 (POSTTRAN)  → Stage 2 (INTCALC)  → Stage 3 (COMBTRAN)
                                                        │
                                    ┌───────────────────┴───────────────────┐
                                    ▼                                       ▼
                          Stage 4a (CREASTMT)                     Stage 4b (TRANREPT)

Design Notes
------------
* **No eager imports**: This package module performs NO imports of
  subpackages. Each PySpark Glue job is a self-contained script that
  imports only what it needs from ``src.batch.common`` and
  ``src.shared`` at execution time. This lazy-loading pattern minimizes
  cold-start overhead for AWS Glue workers, avoids pulling
  API-layer-only dependencies (FastAPI, SQLAlchemy async) into the
  Spark driver process, and prevents circular-import risk between the
  API and batch layers.
* **Package marker only**: This ``__init__.py`` contains no executable
  logic — it exists solely to establish ``src.batch`` as an explicit
  package (PEP 328) so that ``from src.batch.jobs.posttran_job import
  main`` resolves unambiguously under every execution context: local
  ``pytest`` runs, AWS Glue ``--extra-py-files`` ``.zip`` shipping, and
  Step Functions job invocation.
* **Financial precision**: All monetary arithmetic in the batch jobs
  uses ``decimal.Decimal`` with ``ROUND_HALF_EVEN`` (banker's rounding)
  to preserve the COBOL ``PIC S9(n)V99`` ``ROUNDED`` semantics of the
  original programs. No floating-point arithmetic is permitted for any
  financial calculation — this discipline is enforced throughout
  ``src.shared.utils.decimal_utils`` and the individual job modules.
* **Python 3.11+**: The batch layer targets Python 3.11 exactly — the
  version provided by the managed AWS Glue 5.1 runtime. ``aws-glue-libs``
  is intentionally NOT pip-installed (it ships with the Glue managed
  runtime); for local unit testing, PySpark 3.5.6 is used directly.
* **Apache License 2.0**: Inherited from the original AWS CardDemo
  mainframe reference application.

See Also
--------
AAP §0.4.1 — Refactored Structure Planning
AAP §0.5.1 — File-by-File Transformation Plan
AAP §0.7.1 — Refactoring-Specific Rules (preserve business logic exactly)
AAP §0.7.2 — Financial Precision (Decimal + ROUND_HALF_EVEN)
"""

# ----------------------------------------------------------------------------
# Package version.
#
# Distinct from the distribution version declared in ``pyproject.toml``
# (``carddemo``). This ``__version__`` tracks the contract compatibility
# of the batch-layer job scripts and their shared infrastructure
# (``src.batch.common``) so that Step Functions state-machine
# definitions, Glue job JSON configurations (``infra/glue-job-configs/``),
# and downstream consumers can detect breaking changes.
#
# Semantic versioning: MAJOR.MINOR.PATCH
#   MAJOR — Breaking changes to job entrypoint signatures,
#           Step Functions state-machine contract, or
#           inter-stage table/S3-object schemas.
#   MINOR — Backward-compatible additions (new jobs, new optional
#           parameters, new CloudWatch metrics).
#   PATCH — Bug fixes with no external contract changes.
# ----------------------------------------------------------------------------
__version__: str = "1.0.0"

# Explicit re-export list — only ``__version__`` is considered part of
# the public API of this package module. All other symbols must be
# imported from their specific submodules (e.g.,
# ``from src.batch.common.glue_context import init_glue``,
# ``from src.batch.jobs.posttran_job import main``).
#
# ``from src.batch import *`` imports only ``__version__``.
__all__ = ["__version__"]
