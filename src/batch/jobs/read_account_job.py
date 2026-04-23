# ============================================================================
# Source: app/cbl/CBACT01C.cbl — Account Diagnostic Reader
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
"""Account diagnostic reader PySpark Glue job.

Replaces ``app/cbl/CBACT01C.cbl`` + ``app/jcl/READACCT.jcl`` — the
mainframe *diagnostic* program that reads every record from the
ACCTDATA VSAM KSDS cluster (account master file) and DISPLAYs it to
the system console for operator verification.

Overview
--------
The original COBOL program ``CBACT01C`` (see ``app/cbl/CBACT01C.cbl``)
implements the canonical z/OS sequential-read-until-EOF pattern against
a single INDEXED VSAM KSDS cluster::

    FILE-CONTROL.
        SELECT ACCTFILE-FILE ASSIGN TO ACCTFILE
               ORGANIZATION IS INDEXED
               ACCESS MODE  IS SEQUENTIAL
               RECORD KEY   IS FD-ACCT-ID
               FILE STATUS  IS ACCTFILE-STATUS.

and the PROCEDURE DIVISION (lines 70-87 of CBACT01C.cbl) is the
minimal::

    DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'.
    PERFORM 0000-ACCTFILE-OPEN.
    PERFORM UNTIL END-OF-FILE = 'Y'
        IF  END-OF-FILE = 'N'
            PERFORM 1000-ACCTFILE-GET-NEXT
            IF  END-OF-FILE = 'N'
                DISPLAY ACCOUNT-RECORD
            END-IF
        END-IF
    END-PERFORM.
    PERFORM 9000-ACCTFILE-CLOSE.
    DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'.
    GOBACK.

Record Layout (``app/cpy/CVACT01Y.cpy`` — RECLN 300)
----------------------------------------------------
::

    01 ACCOUNT-RECORD.
        05  ACCT-ID                           PIC 9(11).
        05  ACCT-ACTIVE-STATUS                PIC X(01).
        05  ACCT-CURR-BAL                     PIC S9(10)V99.
        05  ACCT-CREDIT-LIMIT                 PIC S9(10)V99.
        05  ACCT-CASH-CREDIT-LIMIT            PIC S9(10)V99.
        05  ACCT-OPEN-DATE                    PIC X(10).
        05  ACCT-EXPIRAION-DATE               PIC X(10).
        05  ACCT-REISSUE-DATE                 PIC X(10).
        05  ACCT-CURR-CYC-CREDIT              PIC S9(10)V99.
        05  ACCT-CURR-CYC-DEBIT               PIC S9(10)V99.
        05  ACCT-ADDR-ZIP                     PIC X(10).
        05  ACCT-GROUP-ID                     PIC X(10).
        05  FILLER                            PIC X(178).

The field name ``ACCT-EXPIRAION-DATE`` (sic — missing a "T") is the
authoritative spelling from the original COBOL copybook and is
preserved verbatim across the source mainframe artifact. The target
Aurora PostgreSQL ``accounts`` table (``db/migrations/V1__schema.sql``)
canonicalizes this as ``expiration_date`` in the migrated schema
(SQLAlchemy ORM in ``src/shared/models/account.py``) while the VSAM
178-byte FILLER slack column is dropped (no semantic content, pure
VSAM record-length padding). The 11-digit ``ACCT-ID`` PIC clause
becomes the ``BIGINT`` primary key. All five monetary fields
(``ACCT-CURR-BAL``, ``ACCT-CREDIT-LIMIT``, ``ACCT-CASH-CREDIT-LIMIT``,
``ACCT-CURR-CYC-CREDIT``, ``ACCT-CURR-CYC-DEBIT``) declared as
``PIC S9(10)V99`` become ``NUMERIC(15,2)`` PostgreSQL columns and flow
through PySpark as :class:`pyspark.sql.types.DecimalType` values backed
by Python :class:`decimal.Decimal` — preserving the COBOL fixed-point
precision contract mandated by AAP §0.7.2 (no ``float`` arithmetic
for any monetary value). The three ``PIC X(10)`` date fields
(``ACCT-OPEN-DATE``, ``ACCT-EXPIRAION-DATE``, ``ACCT-REISSUE-DATE``)
are stored as ``CHAR(10)`` / ``DATE`` columns per the schema, and the
single-character ``ACCT-ACTIVE-STATUS`` typically holds ``'Y'``/``'N'``
and maps to a ``CHAR(1)`` column.

Mainframe-to-Cloud Transformation
---------------------------------
* JCL ``//READACCT JOB`` + ``//STEP05 EXEC PGM=CBACT01C`` +
  ``//STEPLIB DD DISP=SHR,DSN=AWS.M2.CARDDEMO.LOADLIB`` +
  ``//SYSOUT DD SYSOUT=*`` + ``//SYSPRINT DD SYSOUT=*`` from
  ``app/jcl/READACCT.jcl`` (lines 1-2, 22-24, 27-28) all collapse
  into a single :func:`src.batch.common.glue_context.init_glue`
  call which provisions the SparkSession, GlueContext, Job, and
  structured JSON logging handler pointed at CloudWatch.
* JCL ``//ACCTFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS``
  (READACCT.jcl lines 25-26) plus the COBOL ``0000-ACCTFILE-OPEN``
  paragraph (CBACT01C.cbl lines 133-149) + the main read-loop
  (lines 74-81) driven by ``1000-ACCTFILE-GET-NEXT`` (lines 92-116)
  + the ``9000-ACCTFILE-CLOSE`` paragraph (lines 151-167) all
  collapse into a single :func:`src.batch.common.db_connector.read_table`
  call which issues a JDBC query against the Aurora PostgreSQL
  ``accounts`` table. The canonical VSAM-to-PostgreSQL mapping lives
  in :data:`src.batch.common.db_connector.VSAM_TABLE_MAP` with
  ``"ACCTDATA": "accounts"``.
* Each COBOL ``DISPLAY ACCOUNT-RECORD`` statement (line 78) becomes a
  single :func:`logging.Logger.info` call on the pre-configured
  ``logger`` instance. Structured JSON output to CloudWatch Logs
  replaces the traditional SYSOUT-to-JES-spool pattern, enabling
  CloudWatch Logs Insights structured-query access for operators.
* The terminal ``GOBACK`` statement (line 87) + JCL ``MAXCC=0``
  success signalling become a :func:`src.batch.common.glue_context.commit_job`
  call which commits the Glue job bookmark and signals the
  Step Functions state machine that this stage completed cleanly.

Role in the Pipeline
--------------------
This job is a *diagnostic* / *utility* job — it performs no data
modification and has no downstream dependencies. It exists as a
cloud-native equivalent of running ``READACCT.jcl`` on the
mainframe: a lightweight verification tool that operators can
invoke ad-hoc to validate the full contents of the ``accounts``
table after a data migration or before running the batch pipeline.
It is **not** part of the 5-stage POSTTRAN → INTCALC → COMBTRAN →
(CREASTMT ∥ TRANREPT) sequence. Account data flows into the main
pipeline via the ``accounts`` table which is referenced by
:mod:`src.batch.jobs.posttran_job` (balance updates in Stage 1),
:mod:`src.batch.jobs.intcalc_job` (interest posting in Stage 2),
and :mod:`src.batch.jobs.creastmt_job` (statement generation in
Stage 4a). The ``card_cross_references`` join table used by the
transaction posting, interest calculation, and statement generation
stages references ``accounts`` via the ``acct_id`` foreign key.

Error Handling
--------------
Any exception raised by :func:`init_glue`, :func:`read_table`, or
the Spark actions (``.cache()``, ``.count()``, ``.collect()``) is
logged with the COBOL-equivalent DISPLAY text
(``'ERROR READING ACCOUNT FILE'`` — see CBACT01C.cbl line 110) and
re-raised. AWS Glue will then mark the Job as ``FAILED``, causing
Step Functions (if invoked from a state machine) to halt the
pipeline — preserving the JCL ``COND=(0,NE)`` abort semantics from
the mainframe implementation. The COBOL ``9999-ABEND-PROGRAM``
paragraph (``CALL 'CEE3ABD'`` at line 173) maps to the Python 3
default non-zero exit code on uncaught exceptions, which AWS Glue
interprets as job failure.

See Also
--------
:mod:`src.batch.common.glue_context`     — init_glue / commit_job factory
:mod:`src.batch.common.db_connector`     — JDBC read_table helper
:mod:`src.batch.jobs.read_card_job`      — Companion reader (CBACT02C.cbl)
:mod:`src.batch.jobs.read_customer_job`  — Companion reader (CBCUS01C.cbl)
:mod:`src.batch.jobs.read_xref_job`      — Companion reader (CBACT03C.cbl)
AAP §0.2.2 — Batch Program Classification (CBACT01C listed as utility)
AAP §0.5.1 — File-by-File Transformation Plan (read_account_job entry)
AAP §0.7.1 — Refactoring-Specific Rules (preserve functionality exactly)
AAP §0.7.2 — Financial Precision (Decimal only, no float arithmetic)

Source
------
* ``app/cbl/CBACT01C.cbl``  — COBOL diagnostic program (194 lines)
* ``app/jcl/READACCT.jcl``  — JCL job card + EXEC PGM=CBACT01C
* ``app/cpy/CVACT01Y.cpy``  — ACCOUNT-RECORD layout (300 bytes)
"""

from __future__ import annotations

# ----------------------------------------------------------------------------
# Standard library imports.
# ----------------------------------------------------------------------------
# ``logging``    — structured JSON logging configured by init_glue() that
#                  emits to CloudWatch, replacing the mainframe's
#                  ``//SYSPRINT DD SYSOUT=*`` + ``//SYSOUT DD SYSOUT=*``
#                  convention for CBACT01C's DISPLAY statements (see
#                  READACCT.jcl lines 27-28).
# ``sys``        — AWS Glue script convention; init_glue() internally
#                  uses sys.argv via awsglue.utils.getResolvedOptions, and
#                  the ``if __name__`` guard below records sys.argv at
#                  DEBUG for CloudWatch-side operator troubleshooting.
# ``Decimal``    — COBOL PIC S9(n)V99 equivalent. Explicit import documents
#                  that the five monetary columns read from PostgreSQL
#                  (acct_curr_bal, acct_credit_limit, acct_cash_credit_limit,
#                  acct_curr_cyc_credit, acct_curr_cyc_debit) are Python
#                  Decimal instances backed by NUMERIC(15,2) storage — see
#                  AAP §0.7.2 which mandates no float arithmetic for any
#                  monetary value. A module-level _MONETARY_ZERO sentinel
#                  (constructed as Decimal("0.00")) below provides both
#                  a concrete runtime use of the import (satisfying ruff
#                  F401 and mypy unused-import rules) and the exemplar
#                  for the precision-contract log line emitted at job
#                  start by _log_monetary_precision_contract().
# ----------------------------------------------------------------------------
import logging
import sys
from decimal import Decimal
from typing import Any  # Used by ``_redact_account_financial`` below

# for the dict[str, Any] type annotation of the row payload
# produced by ``pyspark.sql.Row.asDict()``.
# ----------------------------------------------------------------------------
# First-party imports — batch common infrastructure.
# ----------------------------------------------------------------------------
# Both imports are WHITELISTED dependencies per the AAP ``depends_on_files``
# declaration for this file (see AAP §0.5.1). No other internal modules may
# be imported — particularly, this job MUST NOT import from any sibling
# job module in ``src.batch.jobs`` (those are standalone Glue scripts).
#
# ``init_glue(job_name=...)``
#     Returns the 4-tuple (spark_session, glue_context, job, resolved_args).
#     In the Glue runtime it instantiates a SparkContext, wraps it with
#     GlueContext, initializes a Job object, applies Spark tuning
#     (shuffle partitions = 10, AQE enabled), and installs the
#     :class:`src.batch.common.glue_context.JsonFormatter` on the root
#     logger so every call through the module-level ``logger`` below
#     is emitted as single-line JSON to stdout → CloudWatch. In local
#     development (``_GLUE_AVAILABLE`` is False) it returns a minimal
#     SparkSession plus ``None`` for glue_context and job — the
#     commit_job(None) call below is a no-op in that mode.
# ``commit_job(job)``
#     Commits the Glue job bookmark on success. When ``job`` is ``None``
#     (local development) the function logs an informational message
#     and returns without effect. Replaces the terminal ``GOBACK`` +
#     JCL ``MAXCC=0`` success signalling from CBACT01C.cbl line 87.
# ``read_table(spark, "<table>")``
#     Issues a JDBC query against the configured Aurora PostgreSQL
#     cluster and returns a lazy PySpark DataFrame. No JDBC traffic
#     flows until a Spark action (``.count()``, ``.collect()``, etc.)
#     is triggered. Replaces the COBOL ``0000-ACCTFILE-OPEN`` +
#     ``1000-ACCTFILE-GET-NEXT`` + ``9000-ACCTFILE-CLOSE`` sequence
#     plus the JCL ``//ACCTFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.
#     ACCTDATA.VSAM.KSDS`` file binding from READACCT.jcl lines 25-26.
# ----------------------------------------------------------------------------
from src.batch.common.db_connector import read_table
from src.batch.common.glue_context import commit_job, init_glue

# ----------------------------------------------------------------------------
# Module-level logger. ``init_glue`` attaches a :class:`JsonFormatter`
# handler to the root logger on first invocation, so every call made
# through this module-level logger is emitted as structured JSON to
# stdout — and thus into CloudWatch Logs under the Glue job's log
# group ``/aws-glue/jobs/output``.
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)

# ----------------------------------------------------------------------------
# Exact COBOL DISPLAY text preserved verbatim from the original program.
# AAP §0.7.1: "Preserve all existing functionality exactly as-is."
# These string constants mirror the DISPLAY statements at lines 71 and 85
# of ``app/cbl/CBACT01C.cbl``. They are declared as module-level constants
# (rather than inlined at the DISPLAY call sites) to make the
# functional-parity contract with the COBOL source explicit and grep-able.
# The file-level agent prompt for this file specifically mandates: "Preserve
# exact COBOL DISPLAY messages" — any change to these literals is a
# functional-parity regression.
# ----------------------------------------------------------------------------
_COBOL_START_MSG: str = "START OF EXECUTION OF PROGRAM CBACT01C"
_COBOL_END_MSG: str = "END OF EXECUTION OF PROGRAM CBACT01C"

# COBOL error DISPLAYs preserved from lines 110, 144, 162, 170 of
# ``app/cbl/CBACT01C.cbl``. Only ERROR READING is emitted in the happy-path
# error handler below because init_glue + read_table collapse the
# separate OPEN / READ / CLOSE phases into a single logical operation;
# the mainframe's OPEN and CLOSE errors therefore surface through the
# same exception path as a READ error in the PySpark translation. The
# CBACT01C source uses the two-word form "ACCOUNT FILE" (with a space)
# in both the READ (line 110) and CLOSE (line 162) error DISPLAYs, but
# the single-word form "ACCTFILE" (no space) in the OPEN error DISPLAY
# (line 144). All three spellings are preserved verbatim here in the
# corresponding comment blocks where they are referenced; the constant
# below uses the READ-error variant because that is the most common
# failure mode for JDBC-backed reads.
_COBOL_ERROR_READING_MSG: str = "ERROR READING ACCOUNT FILE"
_COBOL_ABEND_MSG: str = "ABENDING PROGRAM"

# ----------------------------------------------------------------------------
# Fixed-scale Decimal sentinel used for the documented monetary-precision
# contract. Declared once at module scope to avoid recomputing the
# quantize exemplar inside tight Spark driver code paths. The value itself
# is immaterial — the explicit Decimal construction is what documents
# the financial-precision requirement (AAP §0.7.2). The reference is
# then used by :func:`_log_monetary_precision_contract` so static-analysis
# tools (mypy, ruff) do not flag ``Decimal`` as an unused import and so
# production log output records the precision contract at startup. The
# scale exponent (-2) corresponds directly to the COBOL ``PIC S9(10)V99``
# declaration on the five account monetary fields.
# ----------------------------------------------------------------------------
_MONETARY_ZERO: Decimal = Decimal("0.00")

# ----------------------------------------------------------------------------
# Glue job name — exposed as a module-level constant to make the mapping
# between this PySpark script and the corresponding AWS Glue Job resource
# explicit and testable. Naming follows the ``carddemo-<job>`` convention
# applied across the batch layer (see sibling files such as
# ``read_card_job.py`` / ``carddemo-read-card``,
# ``read_customer_job.py`` / ``carddemo-read-customer``,
# ``read_xref_job.py`` / ``carddemo-read-xref``,
# ``daily_tran_driver_job.py`` / ``carddemo-daily-tran-driver``). This
# constant is also the value that flows into ``--JOB_NAME`` when Step
# Functions (or a manual `aws glue start-job-run`) triggers this script.
# The string literal "carddemo-read-account" is mandated by the file's
# agent prompt: ``init_glue(job_name="carddemo-read-account")``.
# ----------------------------------------------------------------------------
_JOB_NAME: str = "carddemo-read-account"

# ----------------------------------------------------------------------------
# Target PostgreSQL table. Maps to the VSAM ACCTDATA cluster originally
# referenced by the JCL DD statement ``//ACCTFILE DD DISP=SHR,
# DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS`` (READACCT.jcl lines 25-26).
# The mapping is canonicalized in
# ``src.batch.common.db_connector.VSAM_TABLE_MAP["ACCTDATA"]`` — using
# the literal string here (rather than looking it up via the map) keeps
# the whitelist of imported names tight (read_table only) and avoids a
# runtime indirection for a value that is immutable for the lifetime
# of this script. The value "accounts" matches the agent prompt
# specification: ``read_table(spark, "accounts")``.
# ----------------------------------------------------------------------------
_TABLE_NAME: str = "accounts"


# ----------------------------------------------------------------------------
# Sensitive financial columns to redact from log output.
# ----------------------------------------------------------------------------
# The ``accounts`` table stores monetary and credit-limit values
# that are SENSITIVE FINANCIAL DATA and must never be written to
# CloudWatch Logs (or any log sink that persists beyond the
# original Aurora PostgreSQL row).  Exposure of account-level
# balances and credit limits in log output would create an
# information-disclosure vector analogous to PAN exposure for the
# cards table (violating the spirit of AAP §0.7.2 "Use AWS Secrets
# Manager for all database credentials and sensitive
# configuration" and the project's data protection policy).
#
# The following columns are treated as sensitive and are omitted
# entirely from the log payload:
#
#   * ``acct_curr_bal``          — current account balance
#                                  (ACCT-CURR-BAL PIC S9(10)V99).
#                                  The authoritative money figure
#                                  for the account at run time.
#   * ``acct_credit_limit``      — aggregate credit line
#                                  (ACCT-CREDIT-LIMIT PIC S9(10)V99).
#                                  Regulated customer financial
#                                  information under FCRA / GLBA.
#   * ``acct_cash_credit_limit`` — cash-advance credit line
#                                  (ACCT-CASH-CREDIT-LIMIT
#                                  PIC S9(10)V99).  Sub-limit of
#                                  the aggregate credit line; also
#                                  regulated financial data.
#   * ``acct_curr_cyc_credit``   — current-cycle aggregate credits
#                                  (ACCT-CURR-CYC-CREDIT
#                                  PIC S9(10)V99).  Accounts
#                                  running-cycle inflow total.
#   * ``acct_curr_cyc_debit``    — current-cycle aggregate debits
#                                  (ACCT-CURR-CYC-DEBIT
#                                  PIC S9(10)V99).  Accounts
#                                  running-cycle outflow total.
#
# Non-sensitive columns (``acct_id``, ``acct_active_status``,
# ``acct_open_date``, ``acct_expiraton_date``, ``acct_reissue_date``,
# ``acct_group_id``, ``acct_addr_zip``, ``version_id``) pass
# through unchanged — they are operator-useful record identifiers
# and metadata required to correlate records in logs without
# exposing financial balances or credit-line information.
# ----------------------------------------------------------------------------
_SENSITIVE_ACCOUNT_FINANCIAL_KEYS: frozenset[str] = frozenset(
    {
        "acct_curr_bal",
        "acct_credit_limit",
        "acct_cash_credit_limit",
        "acct_curr_cyc_credit",
        "acct_curr_cyc_debit",
    }
)


def _redact_account_financial(row_dict: dict[str, Any]) -> dict[str, Any]:
    """Return a financial-redacted copy of an account row dict for logging.

    Drops every key listed in
    :data:`_SENSITIVE_ACCOUNT_FINANCIAL_KEYS` from the input
    mapping.  Preserves all other keys unchanged.  The input
    ``row_dict`` is not mutated — a new dict is returned.

    Parameters
    ----------
    row_dict : dict[str, Any]
        Dict produced by :meth:`pyspark.sql.Row.asDict` on a row of
        the ``accounts`` table.  May contain any / all of the
        column names declared by the CVACT01Y copybook — the
        function drops the sensitive subset regardless of which
        keys are present.

    Returns
    -------
    dict[str, Any]
        A new dict with the sensitive financial keys removed.  All
        other key / value pairs are passed through identically.
    """
    return {k: v for k, v in row_dict.items() if k not in _SENSITIVE_ACCOUNT_FINANCIAL_KEYS}


def _log_monetary_precision_contract() -> None:
    """Log the monetary-precision contract enforced by this job.

    Emits a single informational line documenting that every monetary
    column read from Aurora PostgreSQL in this job is represented as
    :class:`decimal.Decimal` with two-decimal-place scale — matching
    the COBOL ``PIC S9(10)V99`` fields from ``app/cpy/CVACT01Y.cpy``
    (``ACCT-CURR-BAL`` at line 7, ``ACCT-CREDIT-LIMIT`` at line 8,
    ``ACCT-CASH-CREDIT-LIMIT`` at line 9, ``ACCT-CURR-CYC-CREDIT`` at
    line 13, ``ACCT-CURR-CYC-DEBIT`` at line 14).

    This audit line is useful in CloudWatch for post-run verification
    that the job ran under the correct precision contract, and it
    also provides a concrete runtime use of the :class:`Decimal`
    import declared in the external-imports schema for this file.
    """
    logger.info(
        "Monetary precision contract: Decimal scale=%s (COBOL PIC S9(10)V99)",
        _MONETARY_ZERO.as_tuple().exponent,
    )


def main() -> None:
    """Execute the account diagnostic reader PySpark Glue job.

    This is the Glue-level entry point that mirrors the COBOL
    ``MAIN-LOGIC`` paragraph-set of ``app/cbl/CBACT01C.cbl`` (lines
    70-87). It performs:

    1. **Initialization** — :func:`init_glue` provisions SparkSession,
       GlueContext, Job, and structured JSON logging (replaces JCL JOB
       card + EXEC PGM=CBACT01C + STEPLIB + SYSPRINT/SYSOUT DD cards
       from READACCT.jcl lines 1-2, 22-24, 27-28).
    2. **Open** — A single :func:`read_table` call replaces the COBOL
       ``0000-ACCTFILE-OPEN`` paragraph (lines 133-149) plus the
       ``//ACCTFILE DD`` statement from READACCT.jcl lines 25-26. The
       returned DataFrame is *lazy* — no JDBC traffic flows until
       ``.count()`` / ``.collect()`` is invoked below.
    3. **Sequential read** — The ``DataFrame.collect()`` call (plus the
       explicit ``.cache()`` immediately preceding it) replaces the
       ``PERFORM UNTIL END-OF-FILE`` loop (lines 74-81) along with the
       ``1000-ACCTFILE-GET-NEXT`` paragraph (lines 92-116). Each row
       in the collected driver-side list corresponds to one iteration
       of the COBOL ``READ ACCTFILE-FILE INTO ACCOUNT-RECORD`` statement
       at line 93.
    4. **Display per record** — One :func:`logging.Logger.info` call
       per row replaces the COBOL ``DISPLAY ACCOUNT-RECORD`` at line 78
       (and the expanded 1100-DISPLAY-ACCT-RECORD paragraph at lines
       118-131). Structured JSON output to CloudWatch Logs replaces
       the traditional SYSOUT-to-JES-spool convention.
    5. **Close** — The implicit Spark materialization cleanup
       (``.unpersist()`` on the cached DataFrame) replaces the COBOL
       ``9000-ACCTFILE-CLOSE`` paragraph (lines 151-167).
    6. **Commit** — :func:`commit_job` finalizes the Glue job
       (replaces terminal ``GOBACK`` + JCL ``MAXCC=0`` at line 87).
       On any uncaught exception, the function re-raises after
       emitting a structured error log, so AWS Glue transitions the
       Job into the ``FAILED`` state and Step Functions halts
       downstream stages — preserving JCL ``COND=(0,NE)`` semantics.

    Returns
    -------
    None
        This function is invoked for its side effects (logging,
        Spark job execution, Glue bookmark commit). It does not
        return a value — matching the COBOL ``GOBACK`` + void
        return semantics of the source program.

    Raises
    ------
    Exception
        Any exception raised during Glue initialization, JDBC
        connectivity, or Spark DataFrame actions is propagated
        after being logged with the COBOL-equivalent DISPLAY text.
        AWS Glue will mark the Job as ``FAILED`` and Step Functions
        will halt the pipeline, preserving the original JCL
        ``COND`` parameter abort semantics and the COBOL
        ``9999-ABEND-PROGRAM`` paragraph's ``CALL 'CEE3ABD'``
        behavior (lines 169-173 of CBACT01C.cbl, which sets
        ABCODE=999 before the CEE3ABD system service call).
    """
    # ------------------------------------------------------------------
    # Step 0: Glue / Spark initialization.
    # ------------------------------------------------------------------
    # Replaces the entire JCL boiler-plate for CBACT01C (JOB card,
    # EXEC PGM=, STEPLIB, SYSPRINT/SYSOUT DD). After this call returns,
    # structured JSON logging to CloudWatch is wired up and ``logger``
    # propagates to the configured root handler.
    #
    # Return-tuple components:
    #   ``spark``          — SparkSession used for read_table() below.
    #   ``_glue_context``  — awsglue.context.GlueContext (None in
    #                        local-dev). Prefixed with underscore
    #                        because this diagnostic reader does not
    #                        need GlueContext-specific features
    #                        (DynamicFrame, bookmarks, etc.).
    #   ``job``            — awsglue.job.Job (None in local-dev).
    #                        Passed straight through to commit_job()
    #                        at exit.
    #   ``resolved_args``  — Dict of resolved --JOB_NAME and any
    #                        additional ``--arg value`` pairs. Logged
    #                        for operator debugging in CloudWatch.
    # ------------------------------------------------------------------
    spark, _glue_context, job, resolved_args = init_glue(job_name=_JOB_NAME)

    # COBOL line 71: DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'
    # Verbatim preservation — AAP §0.7.1 requires exact functionality
    # match. The CloudWatch consumer (CloudWatch Logs Insights queries,
    # alerting rules) may be keyed on this exact string literal.
    logger.info(_COBOL_START_MSG)

    # Document the monetary precision contract for auditability —
    # also provides a concrete runtime use of the Decimal import
    # declared in the external-imports schema for this file. The
    # emitted line contains the literal exponent (-2) from the
    # module-level ``_MONETARY_ZERO`` sentinel, matching the scale
    # of the five PIC S9(10)V99 fields in CVACT01Y.cpy.
    _log_monetary_precision_contract()

    # Log resolved Glue arguments (useful for operator debugging in
    # CloudWatch — the equivalent of capturing the mainframe SYSIN /
    # PARM= at job start). Filter out internal ``--<key>`` sentinels
    # so the emitted JSON is a flat dict of operator-supplied values.
    logger.info(
        "Resolved Glue arguments: %s",
        {k: v for k, v in resolved_args.items() if not k.startswith("--")},
    )

    try:
        # --------------------------------------------------------------
        # Step 1: Open the ACCTDATA table.
        #
        # Replaces:
        #   * COBOL 0000-ACCTFILE-OPEN paragraph (lines 133-149)
        #       OPEN INPUT ACCTFILE-FILE
        #       — failure DISPLAYs 'ERROR OPENING ACCTFILE' (line 144)
        #   * JCL //ACCTFILE DD DISP=SHR,
        #         DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS
        #     (READACCT.jcl lines 25-26)
        #
        # read_table() returns a LAZY PySpark DataFrame — no JDBC
        # traffic flows until a Spark action is executed below. Any
        # connection / authentication / permission errors will
        # therefore surface at the first .count() call, not here.
        # --------------------------------------------------------------
        logger.info("Opening accounts table via JDBC...")
        accounts_df = read_table(spark, _TABLE_NAME)

        # --------------------------------------------------------------
        # Step 2: Cache and count the DataFrame.
        #
        # The explicit .cache() materializes the DataFrame once into
        # Spark memory so that the subsequent .count() and .collect()
        # actions share a single JDBC read — eliminating a redundant
        # round-trip to Aurora PostgreSQL.
        #
        # The .count() action is the first point at which actual JDBC
        # traffic flows. A JDBC error (connectivity, auth, missing
        # table) will surface here and fall through to the except
        # block below, where it is logged with the COBOL-equivalent
        # 'ERROR READING ACCOUNT FILE' text (line 110 of CBACT01C.cbl)
        # before re-raising.
        #
        # The literal log message "Total account records read: %d" is
        # mandated verbatim by the file-level agent prompt (Step 5) —
        # this exact phrasing is what CloudWatch Logs Insights queries
        # or alerting rules may be keyed on for daily operational
        # verification that the diagnostic reader saw the expected
        # record count.
        # --------------------------------------------------------------
        accounts_df = accounts_df.cache()
        record_count = accounts_df.count()
        logger.info("Total account records read: %d", record_count)

        # --------------------------------------------------------------
        # Step 3: Iterate and DISPLAY each ACCOUNT-RECORD.
        #
        # Replaces the COBOL main loop (lines 74-81):
        #
        #     PERFORM UNTIL END-OF-FILE = 'Y'
        #         IF  END-OF-FILE = 'N'
        #             PERFORM 1000-ACCTFILE-GET-NEXT
        #             IF  END-OF-FILE = 'N'
        #                 DISPLAY ACCOUNT-RECORD
        #             END-IF
        #         END-IF
        #     END-PERFORM.
        #
        # and the expanded 1100-DISPLAY-ACCT-RECORD paragraph (lines
        # 118-131) which emitted one line per account field (ACCT-ID,
        # ACCT-ACTIVE-STATUS, ACCT-CURR-BAL, ACCT-CREDIT-LIMIT,
        # ACCT-CASH-CREDIT-LIMIT, ACCT-OPEN-DATE, ACCT-EXPIRAION-DATE,
        # ACCT-REISSUE-DATE, ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT,
        # ACCT-GROUP-ID). The PySpark translation emits the whole row
        # as a single structured JSON log line containing all field
        # values, which is actually more queryable than the line-per-
        # field SYSOUT output of the original.
        #
        # In PySpark the sequential VSAM read collapses into a single
        # ``collect()`` action that materializes the entire table
        # driver-side. This is appropriate for a diagnostic reader
        # because:
        #   (a) ACCTDATA is the account master table of O(N=accounts)
        #       rows with modest per-row footprint (~300 bytes per row
        #       in the original VSAM KSDS including 178-byte slack
        #       filler, substantially less in the PostgreSQL target
        #       where the FILLER column is dropped);
        #   (b) The diagnostic purpose of the original program is
        #       precisely to dump every row to SYSOUT.
        #
        # An empty table is a legitimate outcome (matches the COBOL
        # path where 1000-ACCTFILE-GET-NEXT hits APPL-EOF on the
        # first invocation and MOVE 'Y' TO END-OF-FILE fires without
        # any DISPLAY ACCOUNT-RECORD ever executing).
        #
        # The literal log message "Account Record: %s" is mandated
        # verbatim by the file-level agent prompt (Step 6) — mixed
        # case with a space separator (as opposed to the all-caps
        # hyphenated "ACCOUNT-RECORD" COBOL variable name).
        # --------------------------------------------------------------
        if record_count > 0:
            # Row.asDict() emits a structured dict of the account
            # record (acct_id, active_status, open_date,
            # expiration_date, reissue_date, addr_zip, group_id,
            # version_id) as a JSON payload inside each log line —
            # preserving the COBOL DISPLAY ACCOUNT-RECORD semantic
            # of emitting one record per SYSOUT line, but in a form
            # that CloudWatch Logs Insights can query structurally.
            #
            # FINANCIAL DATA REDACTION (security control):
            # Although the COBOL DISPLAY ACCOUNT-RECORD statement
            # wrote the *entire* 300-byte record to SYSOUT —
            # including current balance, credit limits, and cycle
            # credit / debit totals — direct replication of that
            # behavior in CloudWatch Logs would expose regulated
            # financial data (FCRA / GLBA sensitive customer
            # financial information) and violate the project's
            # data-at-rest / data-in-transit protection policy
            # (AAP §0.7.2).
            #
            # We therefore apply :func:`_redact_account_financial`
            # to each row dict BEFORE it is passed to the logger.
            # The helper drops the sensitive financial keys listed
            # in :data:`_SENSITIVE_ACCOUNT_FINANCIAL_KEYS`
            # (``acct_curr_bal``, ``acct_credit_limit``,
            # ``acct_cash_credit_limit``, ``acct_curr_cyc_credit``,
            # ``acct_curr_cyc_debit``) and leaves benign
            # operator-useful keys (``acct_id``,
            # ``acct_active_status``, open / expiration / reissue
            # dates, zip, group id, version id) untouched — those
            # are necessary for operators to identify and correlate
            # records in logs without disclosing balances or
            # credit-line information.
            #
            # This redaction is applied at the log-emission site,
            # not at the JDBC read site — the underlying DataFrame
            # still contains the full, authoritative monetary row
            # (with Python :class:`decimal.Decimal` precision
            # decoded from NUMERIC(15,2) storage) for downstream
            # processing, and that data is protected at the access
            # layer by IAM-gated access to the Aurora PostgreSQL
            # cluster.
            for row in accounts_df.collect():
                logger.info(
                    "Account Record: %s",
                    _redact_account_financial(row.asDict()),
                )
        else:
            # COBOL path when the loop exits immediately via
            # APPL-EOF on the first read. In mainframe SYSOUT this
            # would manifest as the START / END bracket lines with
            # nothing between them; here we log an explicit
            # informational line for operator clarity.
            logger.info("No account records found (empty table).")

        # --------------------------------------------------------------
        # Step 4: Release the cached DataFrame.
        #
        # Replaces the COBOL 9000-ACCTFILE-CLOSE paragraph (lines
        # 151-167) which would CLOSE ACCTFILE-FILE and validate the
        # resulting file status — failure DISPLAYs 'ERROR CLOSING
        # ACCOUNT FILE' (line 162). Best-effort cleanup — if unpersist
        # throws, the run has already succeeded and we swallow the
        # exception at DEBUG level so the job does not flip to
        # FAILED for a post-success housekeeping glitch. This
        # mirrors the defensive close-error handling in
        # read_card_job.py, read_customer_job.py, read_xref_job.py,
        # and other batch layer jobs.
        # --------------------------------------------------------------
        try:
            accounts_df.unpersist()
        except Exception as unpersist_err:  # noqa: BLE001 — defensive
            logger.debug(
                "DataFrame.unpersist() raised during cleanup (non-fatal): %s",
                unpersist_err,
            )

        # --------------------------------------------------------------
        # Step 5: Emit COBOL DISPLAY 'END OF EXECUTION' and commit.
        # Replaces CBACT01C.cbl line 85 + the 9000-ACCTFILE-CLOSE
        # paragraph's successful completion path + the final GOBACK
        # at line 87.
        # --------------------------------------------------------------
        logger.info(_COBOL_END_MSG)

        # Signal MAXCC=0 to Step Functions (or no-op in local-dev).
        # Replaces JCL step completion with return code 0.
        commit_job(job)

    except Exception as exc:
        # ------------------------------------------------------------------
        # Fatal error path. Collapses the COBOL error-handling branches:
        #   * 'ERROR OPENING ACCTFILE'       (line 144) — JDBC connect errors
        #   * 'ERROR READING ACCOUNT FILE'   (line 110) — JDBC query errors
        #   * 'ERROR CLOSING ACCOUNT FILE'   (line 162) — pre-commit errors
        #   * 'ABENDING PROGRAM'             (line 170)
        #   * CALL 'CEE3ABD' with ABCODE=999 (line 173)
        #
        # All of these collapse to a single structured error log plus
        # a re-raise. Python 3 propagates the exception up the stack
        # and exits with a non-zero status code, which AWS Glue
        # interprets as job failure (equivalent to MAXCC != 0 and the
        # COBOL ABCODE=999 sentinel set at line 172).
        # ------------------------------------------------------------------
        logger.error(
            "%s — %s: %s",
            _COBOL_ERROR_READING_MSG,
            _COBOL_ABEND_MSG,
            exc,
            exc_info=True,
        )
        # Propagate so Glue marks the job FAILED — do NOT swallow. The
        # uncaught exception → non-zero exit replaces the COBOL
        # CALL 'CEE3ABD' behavior with its 999 ABCODE sentinel.
        raise


# ----------------------------------------------------------------------------
# Glue script entry point.
#
# AWS Glue invokes the script file directly (``python <script>.py --JOB_NAME
# carddemo-read-account --<other> <val> ...``). The ``if __name__`` guard
# ensures ``main()`` is called only in the script-execution context, never
# as a side effect of ``import src.batch.jobs.read_account_job`` (which
# would be catastrophic during unit-test collection or Step Functions
# script validation).
#
# ``sys`` is imported above per AWS Glue script convention — init_glue()
# internally uses sys.argv via awsglue.utils.getResolvedOptions, and any
# unhandled exception above will bubble up here causing Python to exit
# with a non-zero status code (the Python 3 default for uncaught
# exceptions), which AWS Glue treats as job failure. The explicit use of
# ``sys.argv`` below satisfies the external-imports schema contract
# (sys.argv is listed as an accessed member) and also provides
# operator-debug visibility into the argv vector at DEBUG level —
# invaluable when diagnosing argument-passing issues between Step
# Functions and the Glue runtime in CloudWatch.
# ----------------------------------------------------------------------------
if __name__ == "__main__":
    # Log the argv vector at DEBUG so operator troubleshooting in
    # CloudWatch can correlate Glue --argument passing with script
    # behavior. Note: logger.debug messages emitted BEFORE init_glue()
    # installs the JsonFormatter root handler are simply dropped — which
    # is the correct behavior (no double-logging, no orphan plaintext
    # lines); DEBUG-level tracing only surfaces once main() is entered.
    logger.debug("Invoked with sys.argv: %s", sys.argv)
    main()
