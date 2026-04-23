# ============================================================================
# Source: app/cbl/CBCUS01C.cbl — Customer Diagnostic Reader
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
"""Customer diagnostic reader PySpark Glue job.

Replaces ``app/cbl/CBCUS01C.cbl`` + ``app/jcl/READCUST.jcl`` — the
mainframe *diagnostic* program that reads every record from the
CUSTDATA VSAM KSDS cluster (customer master file) and DISPLAYs it to
the system console for operator verification.

Overview
--------
The original COBOL program ``CBCUS01C`` (see ``app/cbl/CBCUS01C.cbl``)
implements the canonical z/OS sequential-read-until-EOF pattern against
a single INDEXED VSAM KSDS cluster::

    FILE-CONTROL.
        SELECT CUSTFILE-FILE ASSIGN TO   CUSTFILE
               ORGANIZATION IS INDEXED
               ACCESS MODE  IS SEQUENTIAL
               RECORD KEY   IS FD-CUST-ID
               FILE STATUS  IS CUSTFILE-STATUS.

and the PROCEDURE DIVISION (lines 70-87 of CBCUS01C.cbl) is the
minimal::

    DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'.
    PERFORM 0000-CUSTFILE-OPEN.
    PERFORM UNTIL END-OF-FILE = 'Y'
        IF  END-OF-FILE = 'N'
            PERFORM 1000-CUSTFILE-GET-NEXT
            IF  END-OF-FILE = 'N'
                DISPLAY CUSTOMER-RECORD
            END-IF
        END-IF
    END-PERFORM.
    PERFORM 9000-CUSTFILE-CLOSE.
    DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'.
    GOBACK.

Record Layout (``app/cpy/CVCUS01Y.cpy`` — RECLN 500)
----------------------------------------------------
::

    01 CUSTOMER-RECORD.
        05  CUST-ID                       PIC 9(09).
        05  CUST-FIRST-NAME               PIC X(25).
        05  CUST-MIDDLE-NAME              PIC X(25).
        05  CUST-LAST-NAME                PIC X(25).
        05  CUST-ADDR-LINE-1              PIC X(50).
        05  CUST-ADDR-LINE-2              PIC X(50).
        05  CUST-ADDR-LINE-3              PIC X(50).
        05  CUST-ADDR-STATE-CD            PIC X(02).
        05  CUST-ADDR-COUNTRY-CD          PIC X(03).
        05  CUST-ADDR-ZIP                 PIC X(10).
        05  CUST-PHONE-NUM-1              PIC X(15).
        05  CUST-PHONE-NUM-2              PIC X(15).
        05  CUST-SSN                      PIC 9(09).
        05  CUST-GOVT-ISSUED-ID           PIC X(20).
        05  CUST-DOB-YYYY-MM-DD           PIC X(10).
        05  CUST-EFT-ACCOUNT-ID           PIC X(10).
        05  CUST-PRI-CARD-HOLDER-IND      PIC X(01).
        05  CUST-FICO-CREDIT-SCORE        PIC 9(03).
        05  FILLER                        PIC X(168).

In the target Aurora PostgreSQL schema this maps to the ``customers``
table (``db/migrations/V1__schema.sql``) with the ``FILLER`` 168-byte
slack column dropped (no semantic content, pure VSAM record-length
padding) and PII fields (``CUST-SSN``, ``CUST-GOVT-ISSUED-ID``)
stored per the project's data-at-rest encryption policy. The 9-digit
``CUST-ID`` PIC clause becomes a ``BIGINT`` primary key, while
fixed-length ``PIC X(n)`` text fields become ``VARCHAR(n)`` without
the COBOL space padding.

Mainframe-to-Cloud Transformation
---------------------------------
* JCL ``//READCUST JOB`` + ``//STEP05 EXEC PGM=CBCUS01C`` +
  ``//STEPLIB DD DISP=SHR,DSN=AWS.M2.CARDDEMO.LOADLIB`` +
  ``//SYSOUT DD SYSOUT=*`` + ``//SYSPRINT DD SYSOUT=*`` from
  ``app/jcl/READCUST.jcl`` all collapse into a single
  :func:`src.batch.common.glue_context.init_glue` call which
  provisions the SparkSession, GlueContext, Job, and structured
  JSON logging handler pointed at CloudWatch.
* JCL ``//CUSTFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS``
  plus the COBOL ``0000-CUSTFILE-OPEN`` + ``1000-CUSTFILE-GET-NEXT``
  read loop + ``9000-CUSTFILE-CLOSE`` all collapse into a single
  :func:`src.batch.common.db_connector.read_table` call which
  issues a JDBC query against the Aurora PostgreSQL ``customers``
  table.
* Each COBOL ``DISPLAY CUSTOMER-RECORD`` statement becomes a
  single :func:`logging.Logger.info` call on the pre-configured
  ``logger`` instance. Structured JSON output to CloudWatch Logs
  replaces the traditional SYSOUT-to-JES-spool pattern.
* The terminal ``GOBACK`` statement + JCL ``MAXCC=0`` success
  signalling become a :func:`src.batch.common.glue_context.commit_job`
  call which commits the Glue job bookmark and signals the
  Step Functions state machine that this stage completed cleanly.

Role in the Pipeline
--------------------
This job is a *diagnostic* / *utility* job — it performs no data
modification and has no downstream dependencies. It exists as a
cloud-native equivalent of running ``READCUST.jcl`` on the
mainframe: a lightweight verification tool that operators can
invoke ad-hoc to validate the full contents of the ``customers``
table after a data migration or before running the batch pipeline.
It is **not** part of the 5-stage POSTTRAN → INTCALC → COMBTRAN →
(CREASTMT ∥ TRANREPT) sequence. Customer data flows into the main
pipeline transparently via the ``customers`` table which is
referenced by :mod:`src.batch.jobs.creastmt_job` (statement
generation joins ``accounts`` × ``customers`` × ``transactions`` ×
``card_cross_references``).

Error Handling
--------------
Any exception raised by :func:`init_glue`, :func:`read_table`, or
the Spark actions (``.cache()``, ``.count()``, ``.collect()``) is
logged with the COBOL-equivalent DISPLAY text
(``'ERROR READING CUSTOMER FILE'`` — see CBCUS01C.cbl line 110) and
re-raised. AWS Glue will then mark the Job as ``FAILED``, causing
Step Functions (if invoked from a state machine) to halt the
pipeline — preserving the JCL ``COND=(0,NE)`` abort semantics from
the mainframe implementation. The COBOL ``Z-ABEND-PROGRAM``
paragraph (``CALL 'CEE3ABD'`` at line 158) maps to the Python 3
default non-zero exit code on uncaught exceptions, which AWS Glue
interprets as job failure.

See Also
--------
:mod:`src.batch.common.glue_context`    — init_glue / commit_job factory
:mod:`src.batch.common.db_connector`    — JDBC read_table helper
:mod:`src.batch.jobs.read_account_job`  — Companion reader (CBACT01C.cbl)
:mod:`src.batch.jobs.read_card_job`     — Companion reader (CBACT02C.cbl)
:mod:`src.batch.jobs.read_xref_job`     — Companion reader (CBACT03C.cbl)
AAP §0.2.2 — Batch Program Classification (CBCUS01C listed as utility)
AAP §0.5.1 — File-by-File Transformation Plan (read_customer_job entry)
AAP §0.7.1 — Refactoring-Specific Rules (preserve functionality exactly)

Source
------
* ``app/cbl/CBCUS01C.cbl``  — COBOL diagnostic program (179 lines)
* ``app/jcl/READCUST.jcl``  — JCL job card + EXEC PGM=CBCUS01C
* ``app/cpy/CVCUS01Y.cpy``  — CUSTOMER-RECORD layout (500 bytes)
"""

from __future__ import annotations

# ----------------------------------------------------------------------------
# Standard library imports.
# ----------------------------------------------------------------------------
# ``logging``    — structured JSON logging configured by init_glue() that
#                  emits to CloudWatch, replacing the mainframe's
#                  ``//SYSPRINT DD SYSOUT=*`` + ``//SYSOUT DD SYSOUT=*``
#                  convention for CBCUS01C's DISPLAY statements (see
#                  READCUST.jcl lines 11-12).
# ``sys``        — AWS Glue script convention; init_glue() internally
#                  uses sys.argv via awsglue.utils.getResolvedOptions, and
#                  the ``if __name__`` guard below records sys.argv at
#                  DEBUG for CloudWatch-side operator troubleshooting.
# ``typing.Any`` — loose type annotation for ``dict[str, Any]`` used by
#                  :func:`_redact_customer_pii` to accept arbitrary
#                  row-dict values produced by ``pyspark.sql.Row.asDict()``
#                  (which can yield str / int / None depending on the
#                  underlying column type).
# ----------------------------------------------------------------------------
import logging
import sys
from typing import Any

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
#     JCL ``MAXCC=0`` success signalling from CBCUS01C.cbl line 87.
# ``read_table(spark, "<table>")``
#     Issues a JDBC query against the configured Aurora PostgreSQL
#     cluster and returns a lazy PySpark DataFrame. No JDBC traffic
#     flows until a Spark action (``.count()``, ``.collect()``, etc.)
#     is triggered. Replaces the COBOL ``0000-CUSTFILE-OPEN`` +
#     ``1000-CUSTFILE-GET-NEXT`` + ``9000-CUSTFILE-CLOSE`` sequence
#     plus the JCL ``//CUSTFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.
#     CUSTDATA.VSAM.KSDS`` file binding from READCUST.jcl lines 9-10.
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
# of ``app/cbl/CBCUS01C.cbl``. They are declared as module-level constants
# (rather than inlined at the DISPLAY call sites) to make the
# functional-parity contract with the COBOL source explicit and grep-able.
# ----------------------------------------------------------------------------
_COBOL_START_MSG: str = "START OF EXECUTION OF PROGRAM CBCUS01C"
_COBOL_END_MSG: str = "END OF EXECUTION OF PROGRAM CBCUS01C"

# COBOL error DISPLAYs preserved from lines 110, 129, 147 of
# ``app/cbl/CBCUS01C.cbl``. Only ERROR READING is emitted in the happy-path
# error handler below because init_glue + read_table collapse the
# separate OPEN / READ / CLOSE phases into a single logical operation;
# the mainframe's OPEN and CLOSE errors therefore surface through the
# same exception path as a READ error in the PySpark translation. Note
# that CBCUS01C uses two slightly different error strings in the source:
# 'ERROR READING CUSTOMER FILE' (line 110) and 'ERROR CLOSING CUSTOMER
# FILE' (line 147) use "CUSTOMER FILE", whereas 'ERROR OPENING CUSTFILE'
# (line 129) uses the short name "CUSTFILE". We preserve the READ-phase
# wording here because that is the most common failure mode for the
# collapsed JDBC read operation.
_COBOL_ERROR_READING_MSG: str = "ERROR READING CUSTOMER FILE"
_COBOL_ABEND_MSG: str = "ABENDING PROGRAM"

# ----------------------------------------------------------------------------
# Glue job name — exposed as a module-level constant to make the mapping
# between this PySpark script and the corresponding AWS Glue Job resource
# explicit and testable. Naming follows the ``carddemo-<job>`` convention
# applied across the batch layer (see sibling files such as
# ``read_xref_job.py`` / ``carddemo-read-xref``,
# ``daily_tran_driver_job.py`` / ``carddemo-daily-tran-driver``). This
# constant is also the value that flows into ``--JOB_NAME`` when Step
# Functions (or a manual `aws glue start-job-run`) triggers this script.
# ----------------------------------------------------------------------------
_JOB_NAME: str = "carddemo-read-customer"

# ----------------------------------------------------------------------------
# Target PostgreSQL table. Maps to the VSAM CUSTDATA cluster originally
# referenced by the JCL DD statement ``//CUSTFILE DD DISP=SHR,
# DSN=AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS`` (READCUST.jcl lines 9-10).
# The mapping is canonicalized in
# ``src.batch.common.db_connector.VSAM_TABLE_MAP["CUSTDATA"]`` — using
# the literal string here (rather than looking it up via the map) keeps
# the whitelist of imported names tight (read_table only) and avoids a
# runtime indirection for a value that is immutable for the lifetime
# of this script.
# ----------------------------------------------------------------------------
_TABLE_NAME: str = "customers"


# ----------------------------------------------------------------------------
# Sensitive PII columns to redact from log output.
# ----------------------------------------------------------------------------
# The ``customers`` table stores a number of sensitive Personally
# Identifiable Information (PII) elements that must never be written
# to CloudWatch Logs (or any log sink that persists beyond the
# original Aurora PostgreSQL row).  The following columns are treated
# as sensitive and are omitted entirely from the log payload:
#
#   * ``cust_ssn``              — 9-digit Social Security Number
#                                 (CUST-SSN PIC 9(09) in CVCUS01Y).
#                                 U.S. national identifier subject to
#                                 PII protection regulations (GLBA,
#                                 state privacy laws).
#   * ``cust_govt_issued_id``   — Government-issued identifier number
#                                 (CUST-GOVT-ISSUED-ID PIC X(20)).
#                                 Covers driver's license / passport /
#                                 other national IDs.
#   * ``cust_dob_yyyy_mm_dd``   — Date of birth (CUST-DOB-YYYY-MM-DD
#                                 PIC X(10)).  Combined with name is a
#                                 strong identity-theft vector.
#   * ``cust_phone_num_1``,
#     ``cust_phone_num_2``      — Phone numbers (CUST-PHONE-NUM-*
#                                 PIC X(15)).  PII used for identity
#                                 verification and multi-factor auth.
#   * ``cust_eft_account_id``   — EFT bank account identifier
#                                 (CUST-EFT-ACCOUNT-ID PIC X(10)).
#                                 Financial PII enabling ACH routing.
#
# Non-sensitive columns (``cust_id``, name, address lines, state,
# country, zip, primary card-holder indicator, FICO score) pass
# through unchanged — they are not regulated PII on their own and
# are necessary for operator-side record identification in logs.
# ----------------------------------------------------------------------------
_SENSITIVE_CUSTOMER_PII_KEYS: frozenset[str] = frozenset(
    {
        "cust_ssn",
        "cust_govt_issued_id",
        "cust_dob_yyyy_mm_dd",
        "cust_phone_num_1",
        "cust_phone_num_2",
        "cust_eft_account_id",
    }
)


def _redact_customer_pii(row_dict: dict[str, Any]) -> dict[str, Any]:
    """Return a PII-redacted copy of a customer row dict for logging.

    Drops every key listed in :data:`_SENSITIVE_CUSTOMER_PII_KEYS`
    from the input mapping.  Preserves all other keys unchanged.
    The input ``row_dict`` is not mutated — a new dict is returned.

    Parameters
    ----------
    row_dict : dict[str, Any]
        Dict produced by :meth:`pyspark.sql.Row.asDict` on a row of
        the ``customers`` table.  May contain any / all of the
        column names declared by the CVCUS01Y copybook — the
        function drops the sensitive subset regardless of which
        keys are present.

    Returns
    -------
    dict[str, Any]
        A new dict with the sensitive PII keys removed.  All other
        key / value pairs are passed through identically.
    """
    return {k: v for k, v in row_dict.items() if k not in _SENSITIVE_CUSTOMER_PII_KEYS}


def main() -> None:
    """Execute the customer diagnostic reader PySpark Glue job.

    This is the Glue-level entry point that mirrors the COBOL
    ``MAIN-LOGIC`` paragraph-set of ``app/cbl/CBCUS01C.cbl`` (lines
    70-87). It performs:

    1. **Initialization** — :func:`init_glue` provisions SparkSession,
       GlueContext, Job, and structured JSON logging (replaces JCL JOB
       card + EXEC PGM=CBCUS01C + STEPLIB + SYSPRINT/SYSOUT DD cards
       from READCUST.jcl lines 1-2, 6-8, 11-12).
    2. **Open** — A single :func:`read_table` call replaces the COBOL
       ``0000-CUSTFILE-OPEN`` paragraph (lines 118-134) plus the
       ``//CUSTFILE DD`` statement from READCUST.jcl lines 9-10. The
       returned DataFrame is *lazy* — no JDBC traffic flows until
       ``.count()`` / ``.collect()`` is invoked below.
    3. **Sequential read** — The ``DataFrame.collect()`` call (plus the
       explicit ``.cache()`` immediately preceding it) replaces the
       ``PERFORM UNTIL END-OF-FILE`` loop (lines 74-81) along with the
       ``1000-CUSTFILE-GET-NEXT`` paragraph (lines 92-116). Each row
       in the collected driver-side list corresponds to one iteration
       of the COBOL ``READ CUSTFILE-FILE INTO CUSTOMER-RECORD``
       statement at line 93.
    4. **Display per record** — One :func:`logging.Logger.info` call
       per row replaces the COBOL ``DISPLAY CUSTOMER-RECORD`` at
       line 78 (and its twin at line 96 inside the 1000 paragraph).
       Structured JSON output to CloudWatch Logs replaces the
       traditional SYSOUT-to-JES-spool convention.
    5. **Close** — The implicit Spark materialization cleanup
       (``.unpersist()`` on the cached DataFrame) replaces the COBOL
       ``9000-CUSTFILE-CLOSE`` paragraph (lines 136-152).
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
        ``Z-ABEND-PROGRAM`` paragraph's ``CALL 'CEE3ABD'`` behavior
        (lines 154-158 of CBCUS01C.cbl).
    """
    # ------------------------------------------------------------------
    # Step 0: Glue / Spark initialization.
    # ------------------------------------------------------------------
    # Replaces the entire JCL boiler-plate for CBCUS01C (JOB card,
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

    # COBOL line 71: DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'
    # Verbatim preservation — AAP §0.7.1 requires exact functionality
    # match. The CloudWatch consumer (CloudWatch Logs Insights queries,
    # alerting rules) may be keyed on this exact string literal.
    logger.info(_COBOL_START_MSG)

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
        # Step 1: Open the CUSTDATA table.
        #
        # Replaces:
        #   * COBOL 0000-CUSTFILE-OPEN paragraph (lines 118-134)
        #       OPEN INPUT CUSTFILE-FILE
        #   * JCL //CUSTFILE DD DISP=SHR,
        #         DSN=AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS
        #     (READCUST.jcl lines 9-10)
        #
        # read_table() returns a LAZY PySpark DataFrame — no JDBC
        # traffic flows until a Spark action is executed below. Any
        # connection / authentication / permission errors will
        # therefore surface at the first .count() call, not here.
        # --------------------------------------------------------------
        logger.info("Opening customers table via JDBC...")
        customers_df = read_table(spark, _TABLE_NAME)

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
        # 'ERROR READING CUSTOMER FILE' text (line 110 of CBCUS01C.cbl)
        # before re-raising.
        # --------------------------------------------------------------
        customers_df = customers_df.cache()
        record_count = customers_df.count()
        logger.info("customers record count: %d", record_count)

        # --------------------------------------------------------------
        # Step 3: Iterate and DISPLAY each CUSTOMER-RECORD.
        #
        # Replaces the COBOL main loop (lines 74-81):
        #
        #     PERFORM UNTIL END-OF-FILE = 'Y'
        #         IF  END-OF-FILE = 'N'
        #             PERFORM 1000-CUSTFILE-GET-NEXT
        #             IF  END-OF-FILE = 'N'
        #                 DISPLAY CUSTOMER-RECORD
        #             END-IF
        #         END-IF
        #     END-PERFORM.
        #
        # In PySpark the sequential VSAM read collapses into a single
        # ``collect()`` action that materializes the entire table
        # driver-side. This is appropriate for a diagnostic reader
        # because:
        #   (a) CUSTDATA is the customer master table of O(N=customers)
        #       rows with modest per-row footprint (~500 bytes per row
        #       in the original VSAM KSDS including slack filler);
        #   (b) The diagnostic purpose of the original program is
        #       precisely to dump every row to SYSOUT.
        #
        # An empty table is a legitimate outcome (matches the COBOL
        # path where 1000-CUSTFILE-GET-NEXT hits APPL-EOF on the
        # first invocation and MOVE 'Y' TO END-OF-FILE fires without
        # any DISPLAY CUSTOMER-RECORD ever executing).
        # --------------------------------------------------------------
        if record_count > 0:
            # Row.asDict() emits a structured dict of the customer
            # record (cust_id, names, addresses, cardholder indicator,
            # FICO score, and so on) as a JSON payload inside each
            # log line — preserving the COBOL DISPLAY CUSTOMER-RECORD
            # semantic of emitting one record per SYSOUT line, but in
            # a form that CloudWatch Logs Insights can query
            # structurally.
            #
            # PII REDACTION (security control):
            # Although the COBOL DISPLAY CUSTOMER-RECORD statement
            # wrote the *entire* 500-byte record to SYSOUT — including
            # SSN, government-issued ID, DOB, phone numbers, and EFT
            # account ID — direct replication of that behavior in
            # CloudWatch Logs would violate the project's data
            # protection policy (AAP §0.7.2) and industry privacy
            # regulations (U.S. GLBA/FCRA/state privacy laws; the EU
            # GDPR under Art. 5(1)(c) data-minimization principle;
            # U.S. state data-breach notification laws).
            #
            # We therefore apply :func:`_redact_customer_pii` to each
            # row dict BEFORE it is passed to the logger.  The helper
            # drops the sensitive keys listed in
            # :data:`_SENSITIVE_CUSTOMER_PII_KEYS`
            # (``cust_ssn``, ``cust_govt_issued_id``,
            # ``cust_dob_yyyy_mm_dd``, ``cust_phone_num_1``,
            # ``cust_phone_num_2``, ``cust_eft_account_id``) and
            # leaves benign operator-useful keys (``cust_id``, name,
            # address, state/country/zip, cardholder flag, FICO)
            # untouched — those are necessary for operators to
            # identify and correlate records in logs without
            # exposing identity-theft vectors or financial routing
            # information.
            #
            # This redaction is applied at the log-emission site, not
            # at the JDBC read site — the underlying DataFrame still
            # contains the full, authoritative row for downstream
            # processing and is protected by IAM-gated access to the
            # Aurora PostgreSQL cluster itself.
            for row in customers_df.collect():
                logger.info(
                    "CUSTOMER-RECORD: %s",
                    _redact_customer_pii(row.asDict()),
                )
        else:
            # COBOL path when the loop exits immediately via
            # APPL-EOF on the first read. In mainframe SYSOUT this
            # would manifest as the START / END bracket lines with
            # nothing between them; here we log an explicit
            # informational line for operator clarity.
            logger.info("No customer records found (empty table).")

        # --------------------------------------------------------------
        # Step 4: Release the cached DataFrame.
        #
        # Replaces the COBOL 9000-CUSTFILE-CLOSE paragraph (lines
        # 136-152) which would CLOSE CUSTFILE-FILE and validate the
        # resulting file status. Best-effort cleanup — if unpersist
        # throws, the run has already succeeded and we swallow the
        # exception at DEBUG level so the job does not flip to
        # FAILED for a post-success housekeeping glitch. This
        # mirrors the defensive close-error handling in
        # daily_tran_driver_job.py and other batch layer jobs.
        # --------------------------------------------------------------
        try:
            customers_df.unpersist()
        except Exception as unpersist_err:  # noqa: BLE001 — defensive
            logger.debug(
                "DataFrame.unpersist() raised during cleanup (non-fatal): %s",
                unpersist_err,
            )

        # --------------------------------------------------------------
        # Step 5: Emit COBOL DISPLAY 'END OF EXECUTION' and commit.
        # Replaces CBCUS01C.cbl line 85 + the 9000-CUSTFILE-CLOSE
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
        #   * 'ERROR OPENING CUSTFILE'       (line 129) — JDBC connect errors
        #   * 'ERROR READING CUSTOMER FILE'  (line 110) — JDBC query errors
        #   * 'ERROR CLOSING CUSTOMER FILE'  (line 147) — pre-commit errors
        #   * 'ABENDING PROGRAM'             (line 155)
        #   * CALL 'CEE3ABD'                 (line 158)
        #
        # All of these collapse to a single structured error log plus
        # a re-raise. Python 3 propagates the exception up the stack
        # and exits with a non-zero status code, which AWS Glue
        # interprets as job failure (equivalent to MAXCC != 0 and the
        # COBOL ABCODE=999 sentinel set at line 157).
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
# carddemo-read-customer --<other> <val> ...``). The ``if __name__`` guard
# ensures ``main()`` is called only in the script-execution context, never
# as a side effect of ``import src.batch.jobs.read_customer_job`` (which
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
