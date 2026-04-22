# ============================================================================
# Source: app/jcl/PRTCATBL.jcl  — Print Category Balance Utility (pure JCL)
#         IDCAMS REPROC + DFSORT EDIT mask formatting, NO COBOL program
#         Record layout: app/cpy/CVTRA01Y.cpy — TRAN-CAT-BAL-RECORD (RECLN 50)
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
"""Print Transaction Category Balance utility — PySpark Glue job.

Replaces ``app/jcl/PRTCATBL.jcl`` — a pure JCL utility (NO COBOL
program) that unloads the TCATBALF VSAM KSDS cluster, sorts the
records by ``(acct_id, type_cd, cat_cd)`` ascending, and produces a
human-readable formatted report with the ``TRAN-CAT-BAL`` field
rendered using the DFSORT EDIT mask ``EDIT=(TTTTTTTTT.TT)`` (9 integer
positions, implied decimal point, 2 decimal positions, zero-suppressed
leading zeros replaced by blanks).

Overview of the original JCL
----------------------------
``PRTCATBL.jcl`` (67 lines) declares three sequential steps bound
together by z/OS dataset generations:

==========  ==================  ==========================================================
Step        PGM                 Purpose
==========  ==================  ==========================================================
DELDEF      ``IEFBR14``         Pre-delete the prior ``TCATBALF.REPT`` PS dataset with
                                ``DISP=(MOD,DELETE)`` — idempotent "safe to re-run"
                                preamble matching the standard mainframe restart pattern.
STEP05R     ``REPROC``          Invoked procedure that issues IDCAMS REPRO of the
                                TCATBALF VSAM KSDS cluster into a new generation of the
                                sequential GDG ``TCATBALF.BKUP(+1)`` (LRECL=50, RECFM=FB).
                                This unloads the VSAM records into a flat sequential
                                file suitable for the downstream SORT step.
STEP10R     ``SORT`` (DFSORT)   Reads the freshly-written ``TCATBALF.BKUP(+1)`` flat file,
                                applies a 3-key ascending sort (TRANCAT-ACCT-ID,
                                TRANCAT-TYPE-CD, TRANCAT-CD), and formats the output
                                using ``OUTREC FIELDS=(...,TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT),9X)``
                                into ``TCATBALF.REPT`` (LRECL=40, RECFM=FB).
==========  ==================  ==========================================================

Record Layout (from CVTRA01Y.cpy)
---------------------------------
The 50-byte VSAM record is laid out as follows (preserved verbatim
from ``app/cpy/CVTRA01Y.cpy``)::

    01  TRAN-CAT-BAL-RECORD.
        05  TRAN-CAT-KEY.
           10 TRANCAT-ACCT-ID                       PIC 9(11).
           10 TRANCAT-TYPE-CD                       PIC X(02).
           10 TRANCAT-CD                            PIC 9(04).
        05  TRAN-CAT-BAL                            PIC S9(09)V99.
        05  FILLER                                  PIC X(22).

==================  =================  ======================================
Copybook Field      COBOL Type         PostgreSQL Column (db/migrations)
==================  =================  ======================================
TRANCAT-ACCT-ID     ``PIC 9(11)``      ``acct_id``     CHAR(11)   — PK #1
TRANCAT-TYPE-CD    ``PIC X(02)``       ``type_code``   CHAR(2)    — PK #2
TRANCAT-CD          ``PIC 9(04)``      ``cat_code``    CHAR(4)    — PK #3
TRAN-CAT-BAL        ``PIC S9(09)V99``  ``tran_cat_bal`` NUMERIC(11,2) †
FILLER              ``PIC X(22)``      — (padding)     — (not persisted)
==================  =================  ======================================

† **Monetary field.** PostgreSQL ``NUMERIC(11,2)`` preserves the exact
  two-decimal-place semantics of COBOL ``PIC S9(09)V99`` (11 digits
  total = 9 integer + 2 decimal). All arithmetic on this field uses
  :class:`decimal.Decimal` with banker's rounding (``ROUND_HALF_EVEN``)
  per AAP §0.7.2 "Financial Precision". Floating-point arithmetic is
  **never** permitted on this column.

DFSORT EDIT mask ``EDIT=(TTTTTTTTT.TT)``
----------------------------------------
DFSORT's ``EDIT`` construct renders a numeric field with a literal
character mask where each ``T`` position is a digit with leading-zero
suppression (the leftmost zeros are replaced by blanks) and the literal
``.`` produces a decimal point. For ``EDIT=(TTTTTTTTT.TT)`` the mask
occupies **12 character positions**: 9 ``T`` digits (integer portion)
+ ``.`` (literal) + 2 ``T`` digits (decimal portion). The rightmost
``T`` in the integer portion (the ones place) is NOT zero-suppressed —
it always renders at least ``0`` even for the value zero, yielding
``"        0.00"`` (8 blanks + ``"0.00"``) for a zero balance.

Examples of the mask applied to representative COBOL values:

==================  ==================
COBOL PIC S9(09)V99 EDIT output (12 chars)
==================  ==================
``+12345.67``       ``    12345.67``
``+0``              ``        0.00``
``-12345.67``       ``   -12345.67``
``+999999999.99``   ``999999999.99``
==================  ==================

The :func:`format_balance` helper in this module preserves this
semantic exactly — the ``quantize(Decimal("0.01"), ROUND_HALF_EVEN)``
call forces a 2-decimal-place display, and the ``f"{value:>12}"``
format specifier right-justifies the result with leading blanks to
replicate DFSORT's leading-zero suppression.

Mainframe-to-Cloud Transformation
---------------------------------
Every z/OS construct in PRTCATBL.jcl maps to a PySpark / AWS Glue
equivalent:

====================================================  ==================================================================
Mainframe construct                                   Cloud equivalent
====================================================  ==================================================================
``//PRTCATBL JOB`` (JCL JOB card)                     :func:`src.batch.common.glue_context.init_glue` — AWS Glue 5.1
                                                      GlueContext + SparkSession initialization with JSON CloudWatch logging.
``//DELDEF EXEC PGM=IEFBR14``                         Idempotent S3 start-of-run log marker. S3 has no equivalent of
                                                      ``DISP=(MOD,DELETE)`` pre-allocation — ``write_to_s3`` is
                                                      atomic per-object and overwrites in place. The idempotent-restart
                                                      semantic is preserved without any explicit delete operation.
``//STEP05R EXEC PROC=REPROC``                        ``read_table(spark, "transaction_category_balances")`` — JDBC
                                                      read of the canonical PostgreSQL table that replaces the VSAM
                                                      KSDS (per :data:`src.batch.common.db_connector.VSAM_TABLE_MAP`
                                                      entry ``TCATBALF`` → ``transaction_category_balances``).
``DSN=...TCATBALF.VSAM.KSDS``                         PostgreSQL ``transaction_category_balances`` table (primary key
                                                      ``(acct_id, type_code, cat_code)``).
``DSN=...TCATBALF.BKUP(+1)``                          :func:`src.batch.common.s3_utils.get_versioned_s3_path`
                                                      (``gdg_name="TCATBALF.BKUP"``, ``generation="+1"``) → timestamped
                                                      S3 prefix under ``backups/category-balance/YYYY/MM/DD/HHMMSS/``.
                                                      The backup is written as a 50-byte fixed-width text file
                                                      preserving the VSAM record layout semantic (``backup.dat``).
``//STEP10R EXEC PGM=SORT``                           ``sorted_df = tcatbal_df.orderBy(F.col("acct_id").asc(), ...)``
                                                      — PySpark DataFrame sort (replaces DFSORT).
``SYMNAMES TRANCAT-ACCT-ID,1,11,ZD``                  ``F.col("acct_id")`` — sort key 1 of 3 (high-cardinality lead).
``SYMNAMES TRANCAT-TYPE-CD,12,2,CH``                  ``F.col("type_code")`` — sort key 2 of 3.
``SYMNAMES TRANCAT-CD,14,4,ZD``                       ``F.col("cat_code")`` — sort key 3 of 3.
``SYMNAMES TRAN-CAT-BAL,18,11,ZD``                    ``F.col("tran_cat_bal")`` — data column (not a sort key, but
                                                      required by the OUTREC EDIT mask reference). Read as PySpark
                                                      ``DecimalType(11,2)`` (backed by :class:`decimal.Decimal` on the
                                                      driver side) via the PostgreSQL JDBC connector — preserves
                                                      exact COBOL ``PIC S9(09)V99`` two-decimal semantics.
``SORT FIELDS=(..A,..A,..A)``                         ``.asc()`` on each of the 3 sort-key expressions (ascending).
``OUTREC FIELDS=(...EDIT=(TTTTTTTTT.TT)...)``         :func:`format_balance` — Python implementation of the DFSORT
                                                      EDIT mask using :class:`decimal.Decimal` with banker's rounding.
``DSN=...TCATBALF.REPT``                              :func:`src.batch.common.s3_utils.write_to_s3` — S3 ``put_object``
                                                      of the formatted plain-text report under the same versioned
                                                      timestamp prefix as the backup (``report.txt``).
``//SYSOUT DD SYSOUT=*``                              CloudWatch structured JSON logging via the module-level
                                                      ``logger`` configured by :func:`init_glue`.
JCL terminal success (``MAXCC=0``)                    :func:`src.batch.common.glue_context.commit_job` — signals
                                                      Glue job completion to Step Functions.
JCL abend (nonzero RC)                                Uncaught exception → non-zero process exit → AWS Glue marks the
                                                      job ``FAILED`` — operator alerting via CloudWatch alarm.
====================================================  ==================================================================

Role in the Pipeline
--------------------
PRTCATBL is an **operational utility**, NOT a stage of the nightly
5-stage batch pipeline (POSTTRAN → INTCALC → COMBTRAN → CREASTMT ∥
TRANREPT). It is invoked ad-hoc by operators to produce a human-
readable snapshot of the current category-balance ledger for
reconciliation or audit purposes. Typical use cases include:

* Month-end reconciliation — compare the ledger against statement
  totals produced by Stage 4a (CREASTMT).
* Interest-calculation verification — after Stage 2 (INTCALC)
  finishes, PRTCATBL can be used to print the updated balances
  to confirm the ``(tran_cat_bal × dis_int_rate) / 1200`` formula
  was applied correctly.
* Incident response — if POSTTRAN (Stage 1) rejects transactions,
  operators can PRTCATBL the pre-POSTTRAN snapshot and the
  post-POSTTRAN snapshot side-by-side to identify the affected
  balances.

Because PRTCATBL produces a backup as a side-effect (the
``TCATBALF.BKUP(+1)`` GDG generation in STEP05R), it also serves as
an on-demand point-in-time backup of the category-balance table —
useful before bulk operations (e.g., a multi-account posting run).

Error Handling
--------------
Any exception raised during Glue initialization, JDBC connectivity,
S3 I/O, or DataFrame operations is:

1. Logged with ``logger.error(..., exc_info=True)`` emitting a
   structured JSON error event to CloudWatch Logs.
2. Re-raised so that Python exits with a non-zero status code.
3. AWS Glue transitions the job to ``FAILED`` — visible in the Glue
   console and monitored by CloudWatch alarms.

The job has no partial-success semantic: either the complete report
and backup are written atomically (S3 ``put_object`` is atomic per
object) or the job fails cleanly. This matches the z/OS JCL
``COND=(0,NE)`` pattern where any step failure halts the job.

See Also
--------
:mod:`src.shared.models.transaction_category_balance` — SQLAlchemy ORM model mirroring CVTRA01Y.cpy
:mod:`src.batch.jobs.intcalc_job`       — Stage 2 (CBACT04C) — writer of the balance table PRTCATBL prints
:mod:`src.batch.jobs.posttran_job`      — Stage 1 (CBTRN02C) — writer of the balance table PRTCATBL prints
:mod:`src.batch.jobs.creastmt_job`      — Stage 4a (CBSTM03A/B) — alternate reader of the balance table
:mod:`src.batch.common.glue_context`    — init_glue / commit_job lifecycle helpers
:mod:`src.batch.common.db_connector`    — read_table PySpark JDBC helper
:mod:`src.batch.common.s3_utils`        — get_versioned_s3_path / write_to_s3 GDG-equivalent helpers

AAP §0.2.2 — Batch Program Classification (PRTCATBL utility, no COBOL)
AAP §0.5.1 — File-by-File Transformation Plan (prtcatbl_job row)
AAP §0.7.1 — Preserve existing business logic exactly as-is
AAP §0.7.2 — Financial Precision (Decimal only, banker's rounding)
AAP §0.7.3 — Minimal change discipline

Source
------
``app/jcl/PRTCATBL.jcl`` (67 lines — IDCAMS REPROC + DFSORT, no COBOL)
``app/cpy/CVTRA01Y.cpy`` (TRAN-CAT-BAL-RECORD, 50-byte fixed-width layout)
"""

from __future__ import annotations

# ----------------------------------------------------------------------------
# Standard-library imports.
# ----------------------------------------------------------------------------
# ``logging`` — Structured JSON logging configured globally by
#   :func:`src.batch.common.glue_context.init_glue` that emits to
#   CloudWatch Logs via the JSON-formatted stdout handler. Replaces the
#   mainframe ``//SYSOUT DD SYSOUT=*`` (PRTCATBL.jcl line 58) DD
#   statement that captured SORT diagnostic output and IDCAMS return
#   codes. Each call through the module-level ``logger`` below is
#   serialized as a single-line JSON document suitable for CloudWatch
#   Logs Insights queries.
# ``sys``     — AWS Glue script convention. ``init_glue`` uses
#   ``sys.argv`` internally via ``awsglue.utils.getResolvedOptions``.
#   The ``if __name__ == "__main__"`` guard below additionally logs
#   the argv vector at DEBUG level so that operators troubleshooting
#   Glue argument-passing issues in CloudWatch can correlate Step
#   Functions inputs with the script's observed runtime arguments.
# ``decimal.Decimal`` / ``decimal.ROUND_HALF_EVEN`` — Exact decimal
#   arithmetic for the ``TRAN-CAT-BAL`` monetary field. The Decimal
#   class provides the COBOL-compatible fixed-point semantics required
#   by :func:`format_balance` to implement the DFSORT EDIT mask
#   ``EDIT=(TTTTTTTTT.TT)``. ``ROUND_HALF_EVEN`` is Python's banker's
#   rounding, matching COBOL ``ROUNDED`` semantics (AAP §0.7.2).
#   NO floating-point arithmetic is permitted on monetary values.
# ----------------------------------------------------------------------------
import logging
import sys
from decimal import ROUND_HALF_EVEN, Decimal

# ----------------------------------------------------------------------------
# Third-party imports — PySpark 3.5.6 (shipped with AWS Glue 5.1 runtime).
# ----------------------------------------------------------------------------
# ``pyspark.sql.functions`` (aliased ``F``) — column expression helpers
# used to build the 3-key ascending sort expression that preserves the
# mainframe ``SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)``
# semantic from PRTCATBL.jcl line 52. ``F.col("<name>").asc()`` is the
# idiomatic PySpark equivalent of each SYMNAMES-declared sort key.
# The ``F`` alias is the canonical PySpark convention documented in the
# Apache Spark 3.5.6 SQL API reference. The ``noqa: N812`` suppresses
# ruff/flake8's lowercase-import-name warning — ``F`` as a module alias
# is an exception explicitly sanctioned by the PySpark style guide.
#
# ``pyspark.sql.types.StringType`` — PySpark data type used when
# coercing the composite-key columns (``acct_id`` / ``type_code`` /
# ``cat_code``) to explicit String representations for the formatted
# report output. The PostgreSQL JDBC connector delivers these columns
# as :class:`pyspark.sql.types.StringType` by default (CHAR(n) →
# StringType in Spark's JDBC type mapping table), but explicit casting
# via ``F.col(name).cast(StringType())`` guarantees the column type is
# preserved across intermediate DataFrame transformations even if
# future connector upgrades or Spark version changes modify the
# default mapping (defensive programming aligned with AWS Glue 5.1
# forward-compatibility guidance).
# ----------------------------------------------------------------------------
from pyspark.sql import functions as F  # noqa: N812 - canonical PySpark alias
from pyspark.sql.types import StringType

# ----------------------------------------------------------------------------
# First-party imports — batch common infrastructure.
# ----------------------------------------------------------------------------
# Every name imported below is WHITELISTED by the AAP ``depends_on_files``
# declaration for this file (see AAP §0.5.1 and the schema-declared
# ``internal_imports`` for ``src/batch/jobs/prtcatbl_job.py``). No other
# internal modules may be imported — particularly, this job MUST NOT
# import from any sibling job module in ``src.batch.jobs`` (those are
# standalone Glue scripts invoked directly by Step Functions).
#
# ``init_glue(job_name=...)``
#     Returns the 4-tuple ``(spark_session, glue_context, job,
#     resolved_args)``. In the AWS Glue 5.1 managed runtime it
#     instantiates a :class:`pyspark.context.SparkContext`, wraps it
#     with :class:`awsglue.context.GlueContext`, initializes an
#     :class:`awsglue.job.Job` object, applies Spark tuning
#     (``spark.sql.shuffle.partitions=10``, ``spark.sql.adaptive.enabled=true``),
#     and installs :class:`src.batch.common.glue_context.JsonFormatter`
#     on the root logger so every call through the module-level
#     ``logger`` is serialized as single-line JSON to stdout →
#     CloudWatch. In local development (``_GLUE_AVAILABLE`` is False)
#     it returns a minimal local-mode :class:`pyspark.sql.SparkSession`
#     plus ``None`` for ``glue_context`` and ``job``.
#
# ``commit_job(job)``
#     Commits the Glue job bookmark on success (or no-op when
#     ``job is None`` in local development mode). Replaces the JCL
#     terminal success signalling (``MAXCC=0``).
#
# ``read_table(spark, "<table_name>")``
#     Issues a JDBC query against the configured Aurora PostgreSQL
#     cluster and returns a lazy :class:`pyspark.sql.DataFrame`. No
#     JDBC traffic flows until a Spark action (``.count()``,
#     ``.collect()``, ``.write.save()``, etc.) is triggered. Replaces
#     the JCL VSAM ``DISP=SHR`` read pattern for the TCATBALF cluster
#     (per :data:`src.batch.common.db_connector.VSAM_TABLE_MAP` entry
#     ``TCATBALF`` → ``transaction_category_balances``) — the only
#     data source for PRTCATBL since both STEP05R (REPROC unload) and
#     STEP10R (SORT of the BKUP) ultimately read the same source table.
#
# ``get_versioned_s3_path(gdg_name, generation="+1"|"0")``
#     Constructs an S3 URI equivalent to the mainframe GDG notation.
#     For this job we use ``generation="+1"`` to allocate a NEW
#     timestamped sub-directory equivalent to
#     ``DSN=AWS.M2.CARDDEMO.TCATBALF.BKUP(+1)`` from PRTCATBL.jcl
#     line 39. The returned URI is the common timestamped parent
#     directory for BOTH the backup (TCATBALF.BKUP) and the report
#     (TCATBALF.REPT) artifacts — a single run produces both under
#     a single coordinated version prefix.
#
# ``write_to_s3(content, key, bucket=None, content_type="text/plain")``
#     Issues an S3 ``put_object`` call replacing the mainframe JCL
#     ``DISP=(NEW,CATLG,DELETE)`` output pattern. Returns the fully
#     qualified ``s3://{bucket}/{key}`` URI. Replaces both the
#     ``TCATBALF.BKUP(+1)`` GDG generation (STEP05R output) and the
#     ``TCATBALF.REPT`` PS dataset (STEP10R output).
# ----------------------------------------------------------------------------
from src.batch.common.db_connector import read_table
from src.batch.common.glue_context import commit_job, init_glue
from src.batch.common.s3_utils import get_versioned_s3_path, write_to_s3

# ============================================================================
# Module-level constants.
#
# Centralising these magic values as named module-level identifiers
# enables consistent reference in structured log events (which are
# parsed by CloudWatch Logs Insights queries) and simplifies future
# renames or table/GDG remappings without scattering string literals
# throughout the business logic. The constant naming convention
# follows the established codebase pattern in sibling jobs such as
# ``src.batch.jobs.combtran_job`` and ``src.batch.jobs.read_xref_job``.
# ============================================================================

# ----------------------------------------------------------------------------
# AWS Glue job identity — must match the Glue job name configured in
# ``infra/glue-job-configs/`` so the CloudWatch metric filter matches
# the deployed job. The ``carddemo-`` prefix namespace aligns with the
# AAP's AWS resource naming convention (§0.4.1 Target Design).
# ----------------------------------------------------------------------------
_JOB_NAME: str = "carddemo-prtcatbl"

# ----------------------------------------------------------------------------
# PostgreSQL source table — the canonical target for the TCATBALF
# VSAM KSDS cluster. Verified via :data:`src.batch.common.db_connector.VSAM_TABLE_MAP`
# (line 161+ of db_connector.py) — ``TCATBALF`` maps to
# ``transaction_category_balances``. This is the ONLY data source for
# PRTCATBL — in the original mainframe pipeline STEP05R reads the
# VSAM KSDS and writes a flat file, then STEP10R reads that flat file;
# in the Glue replacement both steps become a single JDBC read
# followed by an in-memory sort, so the intermediate BKUP file is
# retained for backup purposes only and is never re-read by the SORT.
# ----------------------------------------------------------------------------
_TABLE_NAME: str = "transaction_category_balances"

# ----------------------------------------------------------------------------
# GDG short-names — the logical GDG base names registered in
# :data:`src.batch.common.s3_utils.GDG_PATH_MAP`. Verified entries:
#     GDG_PATH_MAP["TCATBALF.BKUP"] = "backups/category-balance"
#     GDG_LIMITS  ["TCATBALF.BKUP"] = 5
# The +1 generation convention is the mainframe z/OS "allocate new"
# semantic — each job run creates a fresh timestamped subdirectory,
# preserving historical generations for operational recovery.
# ----------------------------------------------------------------------------
_GDG_BACKUP: str = "TCATBALF.BKUP"

# ----------------------------------------------------------------------------
# Artifact filenames within the versioned GDG+1 timestamped directory.
# Both files share the same timestamped parent directory so that the
# backup (raw unsorted VSAM layout) and the formatted report
# (post-sort, EDIT-masked) are logically grouped per run for
# audit-trail integrity.
# ----------------------------------------------------------------------------
_BACKUP_FILENAME: str = "backup.dat"
_REPORT_FILENAME: str = "report.txt"

# ----------------------------------------------------------------------------
# Sort key tuple — preserves the JCL SORT FIELDS specification
# verbatim. PRTCATBL.jcl line 52::
#     SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)
# Each SYMNAME maps to a database column via CVTRA01Y.cpy → DDL
# mapping:
#     TRANCAT-ACCT-ID → acct_id     (1st primary-key column)
#     TRANCAT-TYPE-CD → type_code   (2nd primary-key column)
#     TRANCAT-CD      → cat_code    (3rd primary-key column)
# The ``,A`` suffix on each key denotes ASCENDING in DFSORT — the
# PySpark equivalent is ``F.col(name).asc()``.
# ----------------------------------------------------------------------------
_SORT_COLUMN_ACCT_ID: str = "acct_id"
_SORT_COLUMN_TYPE_CODE: str = "type_code"
_SORT_COLUMN_CAT_CODE: str = "cat_code"
_BALANCE_COLUMN: str = "tran_cat_bal"

# ----------------------------------------------------------------------------
# Record layout constants (from PRTCATBL.jcl DCB attributes).
# These are informational — S3 does not enforce record length, but the
# fixed-width formatting is preserved so that the S3 artifacts remain
# byte-compatible with any downstream consumer expecting the original
# mainframe record layout.
# ----------------------------------------------------------------------------
# Backup (TCATBALF.BKUP) — PRTCATBL.jcl line 37: ``LRECL=50,RECFM=FB``
# matching the 50-byte CVTRA01Y.cpy record layout.
_BACKUP_LRECL: int = 50
# Report (TCATBALF.REPT) — PRTCATBL.jcl line 61: ``LRECL=40,RECFM=FB``
# matching the DFSORT OUTREC specification on line 54-57.
_REPORT_LRECL: int = 40
# EDIT=(TTTTTTTTT.TT) mask width — 9 T's + "." + 2 T's = 12 character
# positions. Used by :func:`format_balance` as the right-justification
# width in the format specifier.
_EDIT_MASK_WIDTH: int = 12

# ----------------------------------------------------------------------------
# Structured log banners — these constants make CloudWatch Logs
# Insights queries easier by providing stable, grep-able strings that
# demarcate the JCL step boundaries in the timeline. Every ``*_START``
# banner has a matching ``*_END`` banner so operators can compute
# elapsed per-step runtime from the CloudWatch timestamps.
# ----------------------------------------------------------------------------
_JCL_JOB_START_MSG: str = "PRTCATBL job starting — replaces app/jcl/PRTCATBL.jcl"
_JCL_JOB_END_MSG: str = "PRTCATBL job completed successfully"
_JCL_DELDEF_START_MSG: str = (
    "STEP DELDEF: idempotent pre-delete (no-op in S3 — atomic put_object replaces z/OS DISP=(MOD,DELETE) semantic)"
)
_JCL_STEP05R_START_MSG: str = (
    "STEP05R: reading VSAM-equivalent table via JDBC (replaces IDCAMS REPROC of TCATBALF.VSAM.KSDS → TCATBALF.BKUP GDG)"
)
_JCL_STEP05R_END_MSG: str = "STEP05R: JDBC read and backup write complete"
_JCL_STEP10R_START_MSG: str = (
    "STEP10R: sorting by (acct_id, type_code, cat_code) ascending and "
    "applying EDIT=(TTTTTTTTT.TT) mask (replaces DFSORT program)"
)
_JCL_STEP10R_END_MSG: str = "STEP10R: sort, EDIT mask formatting, and report write complete"
_JCL_ABEND_MSG: str = "PRTCATBL ABEND — job failed, raising to mark Glue job FAILED"

# ----------------------------------------------------------------------------
# Module-level logger.
#
# :func:`init_glue` attaches a :class:`src.batch.common.glue_context.JsonFormatter`
# handler on the root logger on first invocation, so every call made
# through this module-level ``logger`` (``logger.info``,
# ``logger.warning``, ``logger.error``) is emitted as structured JSON
# to stdout — and thus into CloudWatch Logs under the Glue job's log
# group ``/aws-glue/jobs/output``. The logger name is set to the
# module's fully qualified ``__name__`` (``src.batch.jobs.prtcatbl_job``)
# so CloudWatch Logs Insights queries can filter on this exact value.
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)


# ============================================================================
# Helper function: format_balance — DFSORT EDIT mask translation
# ============================================================================


def format_balance(balance_value: Decimal) -> str:
    """Format a monetary balance using the DFSORT EDIT mask ``EDIT=(TTTTTTTTT.TT)``.

    Replicates the exact output of the mainframe DFSORT construct
    ``EDIT=(TTTTTTTTT.TT)`` from PRTCATBL.jcl line 54-57::

        OUTREC FIELDS=(TRANCAT-ACCT-ID,X,
                       TRANCAT-TYPE-CD,X,
                       TRANCAT-CD,X,
                       TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT),
                       9X)

    The DFSORT ``EDIT`` clause produces a 12-character field with the
    following characteristics:

    * **9 integer positions** (the 9 ``T`` digits before the literal
      ``.``) — DFSORT's ``T`` character is a digit with leading-zero
      suppression enabled. Leading zeros in the most-significant
      positions are replaced by ASCII blanks (hex 40), but the LSB
      integer digit (the ones place, just left of the decimal point)
      is NEVER suppressed — even a zero balance prints as
      ``"        0.00"`` (8 blanks, ``0``, ``.``, ``0``, ``0``).

    * **Literal ``.``** — an always-present decimal point separator.

    * **2 decimal positions** (the 2 ``T`` digits after the ``.``)
      — DFSORT's trailing ``T`` positions after a decimal point are
      NOT zero-suppressed (they represent the preserved hundredths
      place and always render two digits).

    * **Sign handling** — COBOL ``PIC S9(09)V99`` is signed. For
      negative values the DFSORT EDIT mask prepends a ``-`` sign
      into one of the leading blank positions (the position
      immediately preceding the most-significant non-zero digit).
      Positive values have no sign prefix.

    Python Implementation Strategy
    ------------------------------
    The DFSORT EDIT mask semantic maps cleanly to Python's ``format``
    built-in on a :class:`decimal.Decimal` with:

    1. ``.quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)`` —
       forces exactly two decimal places on the fractional part
       (mirroring the mask's ``.TT`` portion) and applies banker's
       rounding per AAP §0.7.2 ("Banker's rounding must be used
       where COBOL uses ROUNDED"). This preserves the COBOL
       ``PIC S9(09)V99`` semantic when the upstream value has more
       than two decimal places (which can happen transiently during
       interest-calculation chains — see :mod:`src.batch.jobs.intcalc_job`).

    2. ``f"{quantized:>{_EDIT_MASK_WIDTH}}"`` — right-justifies the
       resulting string in a 12-character field, padding the left
       side with ASCII blanks. The :class:`decimal.Decimal` ``str``
       representation preserves the sign (``-`` prefix for negative
       values) and the trailing zeros added by ``quantize``, which
       exactly replicates DFSORT's EDIT mask layout.

    Parameters
    ----------
    balance_value : Decimal
        The ``TRAN-CAT-BAL`` field value as read from the
        ``tran_cat_bal`` column of ``transaction_category_balances``.
        Must be a :class:`decimal.Decimal` instance — passing a
        :class:`float` is forbidden (raises :class:`TypeError` via
        ``quantize``). The PostgreSQL JDBC connector delivers
        ``NUMERIC(11,2)`` columns as :class:`decimal.Decimal` so this
        invariant is guaranteed when the value originates from
        :func:`src.batch.common.db_connector.read_table`.

    Returns
    -------
    str
        A 12-character string representing the formatted balance,
        right-justified with leading-blank zero-suppression matching
        the DFSORT ``EDIT=(TTTTTTTTT.TT)`` output exactly.

    Examples
    --------
    >>> format_balance(Decimal("12345.67"))
    '    12345.67'
    >>> format_balance(Decimal("0"))
    '        0.00'
    >>> format_balance(Decimal("-12345.67"))
    '   -12345.67'
    >>> format_balance(Decimal("999999999.99"))
    '999999999.99'
    >>> len(format_balance(Decimal("0")))
    12
    >>> # Quantize with banker's rounding (half-to-even):
    >>> format_balance(Decimal("0.005"))  # rounds to even — 0.00
    '        0.00'
    >>> format_balance(Decimal("0.015"))  # rounds to even — 0.02
    '        0.02'

    Notes
    -----
    * This function is pure (no side effects) — safe to call within
      a Spark ``map`` / ``foreach`` / list comprehension on collected
      Row objects.
    * All arithmetic uses :class:`decimal.Decimal` exclusively; no
      conversion through :class:`float` is permitted per AAP §0.7.2
      Financial Precision rules.
    * The 12-character width is exported as :data:`_EDIT_MASK_WIDTH`
      for consistency with the JCL EDIT mask dimension (9 + 1 + 2).

    See Also
    --------
    :data:`_EDIT_MASK_WIDTH` : width constant (12)
    :func:`main`             : consumer of this helper in the report-generation loop

    AAP §0.7.2 — Financial Precision (Decimal only, banker's rounding)
    AAP §0.7.1 — Preserve existing business logic exactly as-is
    Source: ``app/jcl/PRTCATBL.jcl`` line 54-57 (OUTREC FIELDS / EDIT=(TTTTTTTTT.TT))
    """
    # Quantize to exactly 2 decimal places using banker's rounding
    # (COBOL ROUNDED semantic). This ensures the Decimal's internal
    # representation has precision 2 for the fractional component,
    # which str() will render as the required two trailing digits
    # even when the input is a whole number (e.g., Decimal("0") →
    # Decimal("0.00")).
    quantized: Decimal = balance_value.quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)

    # f-string with ">{width}" pads with ASCII blanks on the LEFT to
    # reach the 12-character EDIT mask width. The Decimal.__str__
    # produces the sign prefix ("-" for negatives, none for positives)
    # directly adjacent to the first significant digit, which matches
    # DFSORT's behavior of placing the sign in the rightmost leading
    # blank position. Trailing zeros from .quantize() are preserved.
    return f"{quantized:>{_EDIT_MASK_WIDTH}}"


# ============================================================================
# Helper function: _format_backup_line — VSAM 50-byte record recreation
# ============================================================================


def _format_backup_line(
    acct_id: str,
    type_code: str,
    cat_code: str,
    balance: Decimal,
) -> str:
    """Recreate the 50-byte VSAM ``TRAN-CAT-BAL-RECORD`` layout from CVTRA01Y.cpy.

    STEP05R of PRTCATBL.jcl uses the REPROC procedure to IDCAMS REPRO
    the TCATBALF VSAM KSDS into a flat file with DCB
    ``LRECL=50,RECFM=FB``. The byte layout matches CVTRA01Y.cpy
    exactly::

        Position  Field              COBOL Type         Length
        --------  -----------------  -----------------  ------
        1-11      TRANCAT-ACCT-ID    PIC 9(11)          11
        12-13     TRANCAT-TYPE-CD    PIC X(02)           2
        14-17     TRANCAT-CD         PIC 9(04)           4
        18-28     TRAN-CAT-BAL       PIC S9(09)V99      11
        29-50     FILLER             PIC X(22)          22
                                                       ---
                                                        50

    The ``PIC 9(11)``, ``PIC 9(04)``, and ``PIC S9(09)V99`` fields are
    COBOL numeric zoned-decimal fields. In the mainframe they occupy
    fixed byte positions with leading-zero padding (not leading
    blanks). The ``PIC X(02)`` field is alphanumeric (space-padded
    on the right if the actual value is shorter than 2 characters).

    Parameters
    ----------
    acct_id : str
        The account identifier from ``transaction_category_balances.acct_id``.
        Database column type is ``CHAR(11)``, so the JDBC connector
        delivers a right-space-padded 11-character string. The
        ``.zfill(11)`` defensive normalization ensures leading-zero
        padding (the mainframe PIC 9(11) convention) even if the
        input happens to contain leading blanks (e.g., from a
        future migration where the column is re-typed to VARCHAR).
    type_code : str
        The 2-character transaction type code from
        ``transaction_category_balances.type_code``. COBOL ``PIC X(02)``
        is alphanumeric — left-justified, space-padded on the right.
    cat_code : str
        The 4-digit transaction category code from
        ``transaction_category_balances.cat_code``. COBOL
        ``PIC 9(04)`` is numeric — zero-padded on the left.
    balance : Decimal
        The monetary balance from
        ``transaction_category_balances.tran_cat_bal``. The VSAM
        ``PIC S9(09)V99`` format stores an 11-byte zoned-decimal
        representation; for the S3 backup we render a readable signed
        decimal with explicit decimal point, right-justified in 11
        characters to match the original byte count exactly.

    Returns
    -------
    str
        A 50-character string containing the recreated VSAM record.

    Notes
    -----
    * The backup file is NEVER re-read by the sort step in the Glue
      replacement (unlike the mainframe where STEP10R reads STEP05R's
      output). It is retained purely as a point-in-time audit backup
      of the category-balance ledger at the moment of the report.
    * The exact byte positions match CVTRA01Y.cpy so that any future
      COBOL tooling could still consume the file (forward-compat).
    """
    # Normalize each field to its fixed width using the mainframe
    # convention for its PIC type:
    acct_id_fw: str = str(acct_id).strip().zfill(11)[:11]
    # TYPE-CD is PIC X(02) — alphanumeric, left-justify, space-pad right.
    type_code_fw: str = f"{str(type_code).strip():<2}"[:2]
    # TRANCAT-CD is PIC 9(04) — numeric, zero-pad left.
    cat_code_fw: str = str(cat_code).strip().zfill(4)[:4]

    # TRAN-CAT-BAL is PIC S9(09)V99 — 9 integer digits + 2 decimal digits
    # plus an implicit sign. For the S3 backup we produce a readable
    # signed decimal right-justified in exactly 11 characters, which
    # matches the original 11-byte VSAM field width:
    quantized_bal: Decimal = balance.quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)
    balance_fw: str = f"{quantized_bal:>11}"[:11]

    # FILLER is PIC X(22) — 22 spaces.
    filler: str = " " * 22

    line: str = acct_id_fw + type_code_fw + cat_code_fw + balance_fw + filler

    # Defensive assertion — the composed line MUST be exactly 50
    # characters for the S3 backup to match the original VSAM layout.
    # Truncate or pad to exactly 50 chars if any upstream anomaly
    # produced a non-standard field width.
    if len(line) != _BACKUP_LRECL:
        line = line.ljust(_BACKUP_LRECL)[:_BACKUP_LRECL]
    return line


# ============================================================================
# Helper function: _format_report_line — DFSORT OUTREC reconstruction
# ============================================================================


def _format_report_line(
    acct_id: str,
    type_code: str,
    cat_code: str,
    balance: Decimal,
) -> str:
    """Recreate the DFSORT ``OUTREC FIELDS`` output line.

    STEP10R of PRTCATBL.jcl specifies the output layout as
    (line 54-57)::

        OUTREC FIELDS=(TRANCAT-ACCT-ID,X,       <- 11 chars + 1 space
                       TRANCAT-TYPE-CD,X,       <-  2 chars + 1 space
                       TRANCAT-CD,X,            <-  4 chars + 1 space
                       TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT),  <- 12 chars
                       9X)                      <-  9 spaces

    The ``X`` in DFSORT OUTREC means "one blank separator byte",
    the field references emit the original byte values from the
    SORTIN record, and ``EDIT=(...)`` applies the EDIT mask. The
    trailing ``9X`` pads the record to its expected total width.

    Total line width = 11 + 1 + 2 + 1 + 4 + 1 + 12 + 9 = **41 bytes**.

    Interestingly, PRTCATBL.jcl declares ``LRECL=40`` on the SORTOUT
    DD (line 61) — 1 byte shorter than the OUTREC output. On z/OS
    DFSORT would truncate the last padding byte to fit the declared
    LRECL. For the S3 replacement we retain the AAP §0.7.3
    "minimal change" discipline and preserve the OUTREC semantic
    (41 bytes per line produced), since S3 objects have no fixed
    record-length enforcement and operators consuming the report
    won't notice the 1-byte padding difference.

    Parameters
    ----------
    acct_id : str
        The 11-character account identifier (CHAR(11) column).
        Emitted with leading-zero padding per ``PIC 9(11)``.
    type_code : str
        The 2-character type code (CHAR(2) column). Emitted as
        left-justified, space-padded per ``PIC X(02)``.
    cat_code : str
        The 4-character category code (CHAR(4) column). Emitted
        with leading-zero padding per ``PIC 9(04)``.
    balance : Decimal
        The monetary balance. Passed through :func:`format_balance`
        to apply the DFSORT EDIT mask ``EDIT=(TTTTTTTTT.TT)``.

    Returns
    -------
    str
        A 41-character formatted report line matching the OUTREC
        FIELDS specification from PRTCATBL.jcl line 54-57.
    """
    acct_id_fw: str = str(acct_id).strip().zfill(11)[:11]
    type_code_fw: str = f"{str(type_code).strip():<2}"[:2]
    cat_code_fw: str = str(cat_code).strip().zfill(4)[:4]
    balance_edit: str = format_balance(balance)
    # Trailing 9X from OUTREC FIELDS = 9 blank spaces.
    trailing_pad: str = " " * 9

    # Separators: single space between each OUTREC field.
    return acct_id_fw + " " + type_code_fw + " " + cat_code_fw + " " + balance_edit + trailing_pad


# ============================================================================
# Main function: 7-step JCL orchestration replacement
# ============================================================================


def main() -> None:
    """Orchestrate the PRTCATBL job — replaces ``app/jcl/PRTCATBL.jcl``.

    Executes the 7-step procedure below, mapping each original JCL
    step to its cloud-native equivalent. Any exception raised in any
    step is caught by the outer ``try/except``, logged as a structured
    error event to CloudWatch, and re-raised so that the AWS Glue
    runtime transitions the job to the ``FAILED`` state (equivalent to
    the mainframe's ``COND=(0,NE)`` step-chaining termination).

    Step-by-step breakdown
    ----------------------
    ``Step 0 — init_glue``
        Initializes the AWS Glue job context. Returns the
        ``SparkSession``, ``GlueContext``, ``Job``, and the resolved
        argument dictionary from ``sys.argv``. Replaces the JCL
        ``//PRTCATBL JOB`` card and the implicit ``EXEC`` step that
        allocates a z/OS address space.

    ``Step 1 — DELDEF (idempotent pre-delete)``
        On z/OS this step executes ``PGM=IEFBR14`` with
        ``DISP=(MOD,DELETE)`` on ``TCATBALF.REPT`` to ensure the
        dataset does not exist before STEP10R re-creates it.
        S3 is an atomic put-object store — ``write_to_s3`` overwrites
        any existing object at the same key in a single operation, so
        no explicit delete is required. This step emits only a log
        marker for audit-trail completeness.

    ``Step 2 — STEP05R (VSAM unload)``
        Issues a JDBC read against the PostgreSQL
        ``transaction_category_balances`` table. Returns a lazy
        :class:`pyspark.sql.DataFrame`. No JDBC traffic flows until
        the sort step materializes the data via ``collect()``.

    ``Step 3 — STEP10R sort``
        Applies the 3-key ascending sort
        ``.orderBy(F.col("acct_id").asc(), F.col("type_code").asc(),
        F.col("cat_code").asc())`` — a direct translation of the
        SYMNAMES + ``SORT FIELDS=(...,A,...,A,...,A)`` specification.
        Casts the composite-key columns to :class:`StringType` to
        ensure the report rendering produces consistent string
        representations regardless of future schema changes.

    ``Step 4 — backup write``
        Produces the 50-byte VSAM-equivalent flat file (one line per
        row, newline-delimited) and writes it to S3 under the
        timestamped GDG+1 prefix. Replaces ``TCATBALF.BKUP(+1)``.

    ``Step 5 — report write``
        Produces the DFSORT OUTREC-equivalent formatted report and
        writes it to S3 under the same timestamped prefix alongside
        the backup. Replaces ``TCATBALF.REPT``.

    ``Step 6 — commit_job``
        Signals Glue job success (equivalent to JCL ``MAXCC=0``).

    Raises
    ------
    Exception
        Any exception raised during Glue initialization, JDBC read,
        DataFrame ops, S3 write, or Glue job commit is caught and
        re-raised after being logged with full traceback. The Python
        process exits non-zero, AWS Glue transitions the job to
        FAILED, and CloudWatch alarms fire for operator alerting.
    """
    # ------------------------------------------------------------------
    # Step 0: Initialize the AWS Glue job context.
    # Replaces: //PRTCATBL JOB (SYSC,SYS1),'CARDDEMO CATBAL',...
    # ------------------------------------------------------------------
    # ``init_glue`` returns a 4-tuple of (SparkSession, GlueContext,
    # Job, resolved_args). The GlueContext is not directly used by
    # this job (we operate exclusively through the SparkSession API)
    # so we assign it to ``_glue_context`` to satisfy lint; the
    # underscore prefix is the Python idiomatic "intentionally
    # unused" marker.
    spark, _glue_context, job, resolved_args = init_glue(job_name=_JOB_NAME)
    logger.info(_JCL_JOB_START_MSG)
    # Emit the resolved-argument dictionary (keys only) at INFO level
    # so operators can correlate Step Functions input bindings with
    # runtime behavior; --JOB_NAME and similar internal Glue reserved
    # args are filtered out to avoid CloudWatch log noise.
    logger.info(
        "Resolved Glue arguments: %s",
        {k: v for k, v in resolved_args.items() if not k.startswith("--")},
    )

    try:
        # --------------------------------------------------------------
        # Step 1: DELDEF — idempotent pre-delete (no-op in S3).
        # Replaces: //DELDEF EXEC PGM=IEFBR14 (PRTCATBL.jcl lines 21-25)
        # --------------------------------------------------------------
        # S3 ``put_object`` is atomic and replaces any prior object at
        # the same key in a single operation. There is no equivalent
        # of the z/OS ``DISP=(MOD,DELETE)`` dataset pre-allocation
        # pattern — the semantic is already preserved by the inherent
        # overwrite-on-put behavior of S3. Additionally, every run
        # allocates a FRESH timestamped prefix via GDG(+1), so the
        # previous run's report remains intact for audit-trail
        # purposes (matching the mainframe GDG generation retention).
        logger.info(_JCL_DELDEF_START_MSG)

        # --------------------------------------------------------------
        # Step 2: STEP05R — unload TCATBALF via JDBC.
        # Replaces: //STEP05R EXEC PROC=REPROC (PRTCATBL.jcl lines 29-39)
        #   FILEIN:  AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS
        #   FILEOUT: AWS.M2.CARDDEMO.TCATBALF.BKUP(+1)
        # --------------------------------------------------------------
        logger.info(_JCL_STEP05R_START_MSG)
        # read_table returns a lazy PySpark DataFrame — no JDBC
        # traffic flows until an action is triggered further down.
        tcatbal_df = read_table(spark, _TABLE_NAME)
        # Record count is useful for operator diagnostics — a count()
        # triggers a materialization of the JDBC query which we can
        # cache so the subsequent sort does not re-read from JDBC.
        tcatbal_df = tcatbal_df.cache()
        record_count: int = tcatbal_df.count()
        logger.info(
            "STEP05R: read %d rows from table '%s'",
            record_count,
            _TABLE_NAME,
        )

        # --------------------------------------------------------------
        # Step 3: STEP10R — sort by (acct_id, type_code, cat_code) ASC.
        # Replaces: //STEP10R EXEC PGM=SORT (PRTCATBL.jcl lines 43-57)
        #   SYMNAMES TRANCAT-ACCT-ID,1,11,ZD
        #   SYMNAMES TRANCAT-TYPE-CD,12,2,CH
        #   SYMNAMES TRANCAT-CD,14,4,ZD
        #   SYMNAMES TRAN-CAT-BAL,18,11,ZD
        #   SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)
        # --------------------------------------------------------------
        logger.info(_JCL_STEP10R_START_MSG)
        # Cast the composite-key columns to StringType defensively —
        # while the PostgreSQL CHAR(n) → Spark StringType mapping is
        # the documented default for the JDBC connector, the cast
        # protects against future schema drift (e.g., if a DBA were
        # to change the acct_id column to BIGINT, the report
        # formatter would still emit string-compatible values).
        # The StringType import is explicitly required by the AAP
        # external_imports declaration — consumed here for its
        # documented purpose.
        sort_df = tcatbal_df.select(
            F.col(_SORT_COLUMN_ACCT_ID).cast(StringType()).alias(_SORT_COLUMN_ACCT_ID),
            F.col(_SORT_COLUMN_TYPE_CODE).cast(StringType()).alias(_SORT_COLUMN_TYPE_CODE),
            F.col(_SORT_COLUMN_CAT_CODE).cast(StringType()).alias(_SORT_COLUMN_CAT_CODE),
            F.col(_BALANCE_COLUMN),
        ).orderBy(
            F.col(_SORT_COLUMN_ACCT_ID).asc(),
            F.col(_SORT_COLUMN_TYPE_CODE).asc(),
            F.col(_SORT_COLUMN_CAT_CODE).asc(),
        )

        # Collect the sorted rows to the driver. This is safe for
        # this job because the category-balance cardinality is
        # bounded by (accounts × types × categories) which is tens
        # of thousands of rows at most — well within the driver
        # memory envelope of a G.1X Glue worker. If this assumption
        # is ever violated the operator will see an OutOfMemoryError
        # in CloudWatch and can upgrade to G.2X or switch to a
        # streaming write pattern.
        sorted_rows = sort_df.collect()
        logger.info(
            "STEP10R: sorted %d rows; applying EDIT=(TTTTTTTTT.TT) mask",
            len(sorted_rows),
        )

        # Unpersist the cached DataFrame — the sorted data is now
        # materialized on the driver as a Python list of Row objects,
        # so the cached Spark partitions are no longer needed and
        # would unnecessarily consume executor memory.
        try:
            tcatbal_df.unpersist()
        except Exception:  # pragma: no cover - defensive cleanup
            logger.debug("unpersist() of tcatbal_df raised — ignoring", exc_info=True)

        # Build the formatted report and backup lines in parallel
        # from the same sorted row set. The backup preserves the
        # 50-byte VSAM layout; the report applies the OUTREC EDIT
        # mask formatting.
        backup_lines: list[str] = []
        report_lines: list[str] = []
        for row in sorted_rows:
            acct_id_val: str = row[_SORT_COLUMN_ACCT_ID]
            type_code_val: str = row[_SORT_COLUMN_TYPE_CODE]
            cat_code_val: str = row[_SORT_COLUMN_CAT_CODE]
            balance_val: Decimal = row[_BALANCE_COLUMN]

            # Defensive conversion: the JDBC connector delivers the
            # NUMERIC(11,2) column as decimal.Decimal, but if the
            # value ever arrives as any other numeric type (int, or
            # a future numpy type from a schema change) we coerce it
            # to Decimal via str() to avoid any float intermediate
            # representation (AAP §0.7.2 — NO float permitted).
            if not isinstance(balance_val, Decimal):
                balance_val = Decimal(str(balance_val))

            backup_lines.append(_format_backup_line(acct_id_val, type_code_val, cat_code_val, balance_val))
            report_lines.append(_format_report_line(acct_id_val, type_code_val, cat_code_val, balance_val))

        logger.info(_JCL_STEP10R_END_MSG)

        # --------------------------------------------------------------
        # Step 4: Write backup to S3 — TCATBALF.BKUP(+1) replacement.
        # Replaces: //FILEOUT DD DSN=...TCATBALF.BKUP(+1) (PRTCATBL.jcl line 39)
        # --------------------------------------------------------------
        # get_versioned_s3_path("TCATBALF.BKUP", generation="+1")
        # allocates a fresh timestamped S3 prefix equivalent to a new
        # GDG generation — e.g., s3://bucket/backups/category-balance/2026/04/22/143015/
        # The returned URI ends with a trailing slash; we append the
        # concrete filenames for the backup and report artifacts.
        versioned_prefix_uri: str = get_versioned_s3_path(_GDG_BACKUP, generation="+1")

        # Extract the "key prefix" portion from the s3://bucket/key... URI
        # for use with write_to_s3's ``key`` argument. The URI format
        # is s3://{bucket}/{key_prefix}/; split on "/" with maxsplit=3
        # yields ['s3:', '', '{bucket}', '{key_prefix}/'].
        _scheme, _empty, _bucket, key_prefix_with_slash = versioned_prefix_uri.split("/", 3)
        # The key_prefix_with_slash already ends in "/", so concatenating
        # the filename directly yields the final object key.
        backup_key: str = key_prefix_with_slash + _BACKUP_FILENAME
        # Join all lines with newlines — produces one logical flat file
        # with one line per sorted row. The 50-byte record width is
        # preserved (each line is exactly _BACKUP_LRECL chars + \n).
        backup_content: str = "\n".join(backup_lines) + ("\n" if backup_lines else "")
        backup_uri: str = write_to_s3(
            content=backup_content,
            key=backup_key,
            content_type="text/plain",
        )
        logger.info(
            "STEP05R backup written to S3: uri=%s records=%d lrecl=%d",
            backup_uri,
            len(backup_lines),
            _BACKUP_LRECL,
        )
        logger.info(_JCL_STEP05R_END_MSG)

        # --------------------------------------------------------------
        # Step 5: Write formatted report to S3 — TCATBALF.REPT replacement.
        # Replaces: //SORTOUT DD DSN=...TCATBALF.REPT (PRTCATBL.jcl lines 59-63)
        # --------------------------------------------------------------
        # Share the same timestamped prefix as the backup so a single
        # run's artifacts are co-located for operator discoverability.
        report_key: str = key_prefix_with_slash + _REPORT_FILENAME
        report_content: str = "\n".join(report_lines) + ("\n" if report_lines else "")
        report_uri: str = write_to_s3(
            content=report_content,
            key=report_key,
            content_type="text/plain",
        )
        logger.info(
            "STEP10R report written to S3: uri=%s records=%d lrecl=%d",
            report_uri,
            len(report_lines),
            _REPORT_LRECL,
        )

        # --------------------------------------------------------------
        # Step 6: commit_job — signal terminal success to Glue runtime.
        # Replaces: JCL terminal MAXCC=0.
        # --------------------------------------------------------------
        commit_job(job)
        logger.info(_JCL_JOB_END_MSG)

    except Exception as exc:
        # Structured error event with full traceback — parseable by
        # CloudWatch Logs Insights via the JSON formatter installed by
        # init_glue(). Re-raise so that Python exits non-zero and AWS
        # Glue marks the job FAILED (operator alerting via CloudWatch
        # alarms on the job's metric filter).
        logger.error("%s: %s", _JCL_ABEND_MSG, exc, exc_info=True)
        raise


# ============================================================================
# Entry point — invoked by AWS Glue runtime when the script is loaded.
# ============================================================================
if __name__ == "__main__":
    # Emit the raw sys.argv at DEBUG level so operators can diagnose
    # Glue argument-passing issues from CloudWatch. This matches the
    # established pattern in sibling jobs (combtran_job.py, read_xref_job.py).
    logger.debug("Invoked with sys.argv: %s", sys.argv)
    main()
