# ============================================================================
# Source: Mainframe batch infrastructure (JCL DD statements, GDG definitions,
#         JES init) → AWS Glue/S3/Aurora
#         Derived from: app/cbl/CBTRN02C.cbl, app/cbl/CBACT04C.cbl,
#                       app/jcl/POSTTRAN.jcl, app/jcl/DEFGDGB.jcl
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
"""Shared infrastructure for AWS Glue PySpark batch jobs.

Replaces mainframe batch infrastructure patterns:

- JES batch initialization → GlueContext factory (``glue_context.py``)
- VSAM file OPEN/READ/WRITE/REWRITE/DELETE → JDBC connections
  (``db_connector.py``)
- GDG DEFINE/REPRO → S3 versioned paths (``s3_utils.py``)
- STEPLIB/SYSPRINT/SYSOUT → Glue job structured logging
  (``glue_context.py``)

This package is the shared infrastructure layer consumed by every PySpark
Glue job under :mod:`src.batch.jobs`. It consolidates three orthogonal
infrastructure concerns that were previously provided by the z/OS
operating environment (JES2, VSAM Access Method Services, IDCAMS GDG
management, RACF credential resolution) into three focused submodules:

Submodules
----------
glue_context
    :func:`init_glue` — GlueContext and SparkSession factory for every
    PySpark Glue job entry point. Handles both the managed AWS Glue 5.1
    runtime (Apache Spark 3.5.6, Python 3.11, Scala 2.12.18) and the
    graceful local-development fallback where the ``awsglue`` package is
    unavailable (pytest runs, CI, developer workstations). Replaces the
    JCL ``JOB`` card, ``EXEC PGM=`` directive, ``STEPLIB``, ``SYSPRINT``,
    and ``SYSOUT`` DD statements that every batch pipeline stage
    (POSTTRAN, INTCALC, COMBTRAN, CREASTMT, TRANREPT) required on the
    mainframe. Emits CloudWatch-compatible structured JSON logs in place
    of ``DISPLAY`` output on the ``/aws-glue/jobs/output`` log group.

db_connector
    :func:`get_jdbc_url` — constructs a
    ``jdbc:postgresql://host:port/dbname`` URL string for Aurora
    PostgreSQL connections. Replaces mainframe ``DISP=SHR, DSN=`` VSAM
    dataset references (e.g.,
    ``DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS``).
    :func:`get_connection_options` — returns the full dict of JDBC
    connection options (``url``, ``driver``, ``user``, ``password``, and
    optionally ``dbtable``) ready for PySpark ``DataFrameReader.options``
    or ``DataFrameWriter.options``. Replaces VSAM DD statement contracts
    with credential retrieval via AWS Secrets Manager (IAM-based,
    zero-password authentication for production Glue jobs).

s3_utils
    :func:`get_versioned_s3_path` — constructs a date-timestamped S3 URI
    replacing the mainframe ``(+1)``/``(0)`` Generation Data Group
    notation from ``app/jcl/DEFGDGB.jcl`` and the six GDG bases it
    defines (``TRANSACT.BKUP``, ``TRANSACT.DALY``, ``TRANREPT``,
    ``TCATBALF.BKUP``, ``SYSTRAN``, ``TRANSACT.COMBINED``).
    :func:`write_to_s3` — writes bytes or text to S3, replacing GDG
    output allocation patterns (``DISP=(NEW,CATLG,DELETE)``).
    :func:`read_from_s3` — reads bytes from S3, replacing GDG input read
    patterns (``DISP=SHR`` against ``...(0)``).

Design Notes
------------
* **Eager re-exports**: Unlike :mod:`src.batch.jobs`,
  :mod:`src.batch.pipeline`, and :mod:`src.shared.utils`, this package
  init DOES eagerly import its six primary factory functions and
  re-exports them at the package root. The motivation is ergonomic —
  every PySpark Glue job in :mod:`src.batch.jobs` imports all six
  functions, and consolidating them under
  ``from src.batch.common import init_glue, get_jdbc_url, ...`` avoids
  6-line import blocks at the top of every job module. The eager
  imports are lightweight (stdlib-only at module level for each
  submodule; any ``awsglue``/``boto3`` imports are lazy inside the
  factory functions) so the startup cost is negligible for AWS Glue
  workers and pytest collection.

* **No wildcard imports**: Every symbol re-exported here is listed
  explicitly in :data:`__all__` so that ``from src.batch.common import *``
  produces a well-defined, audited import surface. No ``*`` imports are
  used internally.

* **No ``__version__`` here**: The batch-layer version is declared once,
  at the parent package level (:mod:`src.batch`). This subpackage
  deliberately does not duplicate it — consumers that need the version
  should import it from :mod:`src.batch`.

* **No floating-point arithmetic**: This module and its submodules do
  not perform financial calculations. All monetary arithmetic in the
  individual PySpark job modules uses ``decimal.Decimal`` with
  ``ROUND_HALF_EVEN`` (banker's rounding) to preserve the COBOL
  ``PIC S9(n)V99`` ``ROUNDED`` semantics of the original programs
  (AAP §0.7.2).

* **Python 3.11+**: Aligned with the managed AWS Glue 5.1 runtime
  (Apache Spark 3.5.6, Python 3.11, Scala 2.12.18).

* **Apache License 2.0**: Inherited from the original AWS CardDemo
  mainframe reference application (see header above and the source
  files ``app/cbl/CBTRN02C.cbl``, ``app/cbl/CBACT04C.cbl``,
  ``app/jcl/POSTTRAN.jcl``, ``app/jcl/DEFGDGB.jcl``).

Usage Patterns
--------------
Every PySpark Glue job in :mod:`src.batch.jobs` bootstraps via this
package's re-exports::

    from src.batch.common import (
        init_glue,
        get_jdbc_url,
        get_connection_options,
        get_versioned_s3_path,
        write_to_s3,
        read_from_s3,
    )

    def main() -> None:
        spark, glue_ctx, job, args = init_glue()
        opts = get_connection_options(table_name="transactions")
        df = spark.read.format("jdbc").options(**opts).load()
        # ... transformation logic ...
        reject_path = get_versioned_s3_path("DALYREJS")
        write_to_s3(reject_path + "rejects.txt", df.toJSON().collect())

Source
------
Primary batch COBOL programs that define the infrastructure requirements:

* ``app/cbl/CBTRN02C.cbl`` — Stage 1 POSTTRAN transaction posting
  (VSAM + GDG output patterns).
* ``app/cbl/CBACT04C.cbl`` — Stage 2 INTCALC interest calculation (VSAM
  read/update patterns).

Primary JCL jobs that define the orchestration and storage requirements:

* ``app/jcl/POSTTRAN.jcl`` — Stage 1 orchestration (JES2 init + VSAM DD
  bindings + DALYREJS GDG output).
* ``app/jcl/DEFGDGB.jcl`` — IDCAMS ``DEFINE GENERATIONDATAGROUP``
  statements for all six GDG bases that S3 versioned paths replace.

See Also
--------
:mod:`src.batch`                  — parent package (holds ``__version__``)
:mod:`src.batch.common.glue_context`
    — GlueContext + SparkSession factory source module
:mod:`src.batch.common.db_connector`
    — Aurora PostgreSQL JDBC connector source module
:mod:`src.batch.common.s3_utils`  — S3 read/write helpers source module
:mod:`src.batch.jobs`             — PySpark Glue job scripts that consume
    the re-exports below
AAP §0.4.1 — Refactored Structure Planning
AAP §0.4.4 — Key Architectural Decisions (batch layer — AWS Glue)
AAP §0.5.1 — File-by-File Transformation Plan
AAP §0.7.1 — Refactoring-Specific Rules (preserve business logic exactly)
AAP §0.7.2 — Security Requirements (IAM, Secrets Manager, no hardcoded
             credentials) and Monitoring Requirements (CloudWatch)
"""

from __future__ import annotations

# ----------------------------------------------------------------------------
# Eager re-exports of the three submodules' primary factory functions.
#
# Each of the three infrastructure submodules (``glue_context``,
# ``db_connector``, ``s3_utils``) is imported once here at package-init
# time to establish the advertised public API of :mod:`src.batch.common`.
# All PySpark Glue jobs under :mod:`src.batch.jobs` import the six
# functions below from this package root rather than from the individual
# submodules — the one-line import pattern keeps job scripts concise and
# auditable:
#
#     from src.batch.common import init_glue, get_jdbc_url, ...
#
# The submodule-level ``awsglue`` / ``boto3`` / ``pg8000`` imports are
# deliberately deferred (wrapped in try/except or moved inside the
# factory functions) so that eagerly importing this package does NOT
# pull those heavy optional dependencies into module memory at
# collection time. This keeps ``pytest`` fast and avoids spurious
# ImportError in environments without the AWS Glue runtime.
# ----------------------------------------------------------------------------
from src.batch.common.db_connector import get_connection_options, get_jdbc_url
from src.batch.common.glue_context import init_glue
from src.batch.common.s3_utils import (
    get_versioned_s3_path,
    read_from_s3,
    write_to_s3,
)

# ----------------------------------------------------------------------------
# Public re-export list.
#
# Advertises exactly the six factory functions that this package
# consolidates from its three submodules. The order is grouped by
# concern (Glue runtime → JDBC → S3) to match the logical flow of a
# typical PySpark Glue job body: initialize the Glue context, connect
# to Aurora PostgreSQL, and read/write GDG-equivalent S3 objects.
#
# Consumers that need auxiliary helpers NOT in this list (e.g.,
# ``commit_job`` from glue_context, ``read_table``/``write_table`` from
# db_connector, ``list_generations``/``cleanup_old_generations`` from
# s3_utils) must import them directly from the specific submodule —
# this keeps the common package's surface area minimal and stable
# across versions.
#
# ``from src.batch.common import *`` pulls in exactly these six names.
# ----------------------------------------------------------------------------
__all__: list[str] = [
    "init_glue",
    "get_jdbc_url",
    "get_connection_options",
    "get_versioned_s3_path",
    "write_to_s3",
    "read_from_s3",
]
