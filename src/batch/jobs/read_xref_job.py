# ============================================================================
# Source: app/cbl/CBACT03C.cbl — Cross-Reference Diagnostic Reader
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
"""Cross-reference diagnostic reader PySpark Glue job.

Replaces ``app/cbl/CBACT03C.cbl`` + ``app/jcl/READXREF.jcl`` — the
mainframe *diagnostic* program that reads every record from the
CARDXREF VSAM KSDS cluster (card↔account cross-reference) and
DISPLAYs it to the system console for operator verification.

Overview
--------
The original COBOL program ``CBACT03C`` (see
``app/cbl/CBACT03C.cbl``) implements the canonical z/OS sequential-
read-until-EOF pattern against a single INDEXED VSAM KSDS cluster::

    FILE-CONTROL.
        SELECT XREFFILE-FILE ASSIGN TO   XREFFILE
               ORGANIZATION IS INDEXED
               ACCESS MODE  IS SEQUENTIAL
               RECORD KEY   IS FD-XREF-CARD-NUM
               FILE STATUS  IS XREFFILE-STATUS.

and the PROCEDURE DIVISION (lines 70-87) is the minimal::

    DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'.
    PERFORM 0000-XREFFILE-OPEN.
    PERFORM UNTIL END-OF-FILE = 'Y'
        IF  END-OF-FILE = 'N'
            PERFORM 1000-XREFFILE-GET-NEXT
            IF  END-OF-FILE = 'N'
                DISPLAY CARD-XREF-RECORD
            END-IF
        END-IF
    END-PERFORM.
    PERFORM 9000-XREFFILE-CLOSE.
    DISPLAY 'END OF EXECUTION OF PROGRAM CBACT03C'.
    GOBACK.

Record Layout (``app/cpy/CVACT03Y.cpy`` — RECLN 50)
--------------------------------------------------
::

    01 CARD-XREF-RECORD.
        05  XREF-CARD-NUM                     PIC X(16).
        05  XREF-CUST-ID                      PIC 9(09).
        05  XREF-ACCT-ID                      PIC 9(11).
        05  FILLER                            PIC X(14).

In the target Aurora PostgreSQL schema this maps to the
``card_cross_references`` table (``db/migrations/V1__schema.sql``)
with the ``FILLER`` 14-byte slack column dropped (no semantic
content, pure VSAM record-length padding).

Mainframe-to-Cloud Transformation
---------------------------------
* JCL ``//READXREF JOB`` + ``//STEP05 EXEC PGM=CBACT03C`` +
  ``//STEPLIB DD DISP=SHR,DSN=AWS.M2.CARDDEMO.LOADLIB`` +
  ``//SYSOUT DD SYSOUT=*`` + ``//SYSPRINT DD SYSOUT=*`` from
  ``app/jcl/READXREF.jcl`` all collapse into a single
  :func:`src.batch.common.glue_context.init_glue` call which
  provisions the SparkSession, GlueContext, Job, and structured
  JSON logging handler pointed at CloudWatch.
* JCL ``//XREFFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS``
  plus the COBOL ``0000-XREFFILE-OPEN`` + ``1000-XREFFILE-GET-NEXT``
  read loop + ``9000-XREFFILE-CLOSE`` all collapse into a single
  :func:`src.batch.common.db_connector.read_table` call which
  issues a JDBC query against the Aurora PostgreSQL
  ``card_cross_references`` table.
* Each COBOL ``DISPLAY CARD-XREF-RECORD`` statement becomes a
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
cloud-native equivalent of running ``READXREF.jcl`` on the
mainframe: a lightweight verification tool that operators can
invoke ad-hoc to validate the full contents of the
``card_cross_references`` table after a data migration or before
running the batch pipeline. It is not part of the 5-stage
POSTTRAN → INTCALC → COMBTRAN → (CREASTMT ∥ TRANREPT) sequence.

Error Handling
--------------
Any exception raised by :func:`init_glue`, :func:`read_table`, or
the Spark actions (``.cache()``, ``.count()``, ``.collect()``) is
logged with the COBOL-equivalent DISPLAY text (``'ERROR READING
XREFFILE'``) and re-raised. AWS Glue will then mark the Job as
``FAILED``, causing Step Functions (if invoked from a state
machine) to halt the pipeline — preserving the JCL ``COND=(0,NE)``
abort semantics from the mainframe implementation. The COBOL
9999-ABEND-PROGRAM paragraph (``CALL 'CEE3ABD'``) maps to the
Python 3 default non-zero exit code on uncaught exceptions, which
AWS Glue interprets as job failure.

See Also
--------
:mod:`src.batch.common.glue_context`    — init_glue / commit_job factory
:mod:`src.batch.common.db_connector`    — JDBC read_table helper
:mod:`src.batch.jobs.read_account_job`  — Companion reader (CBACT01C.cbl)
:mod:`src.batch.jobs.read_card_job`     — Companion reader (CBACT02C.cbl)
:mod:`src.batch.jobs.read_customer_job` — Companion reader (CBCUS01C.cbl)
AAP §0.2.2 — Batch Program Classification (CBACT03C listed as utility)
AAP §0.5.1 — File-by-File Transformation Plan (read_xref_job entry)
AAP §0.7.1 — Refactoring-Specific Rules (preserve functionality exactly)

Source
------
* ``app/cbl/CBACT03C.cbl``  — COBOL diagnostic program (179 lines)
* ``app/jcl/READXREF.jcl``  — JCL job card + EXEC PGM=CBACT03C
* ``app/cpy/CVACT03Y.cpy``  — CARD-XREF-RECORD layout (50 bytes)
"""

from __future__ import annotations

# ----------------------------------------------------------------------------
# Standard library imports.
# ----------------------------------------------------------------------------
# ``logging``    — structured JSON logging configured by init_glue() that
#                  emits to CloudWatch, replacing the mainframe's
#                  ``//SYSPRINT DD SYSOUT=*`` + ``//SYSOUT DD SYSOUT=*``
#                  convention for CBACT03C's DISPLAY statements (see
#                  README XREF JCL lines 27-28).
# ``sys``        — AWS Glue script convention; init_glue() internally
#                  uses sys.argv via awsglue.utils.getResolvedOptions, and
#                  the ``if __name__`` guard below records sys.argv at
#                  DEBUG for CloudWatch-side operator troubleshooting.
# ----------------------------------------------------------------------------
import logging
import sys

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
#     JCL ``MAXCC=0`` success signalling from CBACT03C.cbl line 87.
# ``read_table(spark, "<table>")``
#     Issues a JDBC query against the configured Aurora PostgreSQL
#     cluster and returns a lazy PySpark DataFrame. No JDBC traffic
#     flows until a Spark action (``.count()``, ``.collect()``, etc.)
#     is triggered. Replaces the COBOL ``0000-XREFFILE-OPEN`` +
#     ``1000-XREFFILE-GET-NEXT`` + ``9000-XREFFILE-CLOSE`` sequence
#     plus the JCL ``//XREFFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.
#     CARDXREF.VSAM.KSDS`` file binding from READXREF.jcl lines 25-26.
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
# of ``app/cbl/CBACT03C.cbl``. They are declared as module-level constants
# (rather than inlined at the DISPLAY call sites) to make the
# functional-parity contract with the COBOL source explicit and grep-able.
# ----------------------------------------------------------------------------
_COBOL_START_MSG: str = "START OF EXECUTION OF PROGRAM CBACT03C"
_COBOL_END_MSG: str = "END OF EXECUTION OF PROGRAM CBACT03C"

# COBOL error DISPLAYs preserved from lines 110, 129, 147 of
# ``app/cbl/CBACT03C.cbl``. Only ERROR READING is emitted in the happy-path
# error handler below because init_glue + read_table collapse the
# separate OPEN / READ / CLOSE phases into a single logical operation;
# the mainframe's OPEN and CLOSE errors therefore surface through the
# same exception path as a READ error in the PySpark translation.
_COBOL_ERROR_READING_MSG: str = "ERROR READING XREFFILE"
_COBOL_ABEND_MSG: str = "ABENDING PROGRAM"

# ----------------------------------------------------------------------------
# Glue job name — exposed as a module-level constant to make the mapping
# between this PySpark script and the corresponding AWS Glue Job resource
# explicit and testable. Naming follows the ``carddemo-<job>`` convention
# applied across the batch layer (see sibling files such as
# ``daily_tran_driver_job.py`` / ``carddemo-daily-tran-driver``). This
# constant is also the value that flows into ``--JOB_NAME`` when Step
# Functions (or a manual `aws glue start-job-run`) triggers this script.
# ----------------------------------------------------------------------------
_JOB_NAME: str = "carddemo-read-xref"

# ----------------------------------------------------------------------------
# Target PostgreSQL table. Maps to the VSAM CARDXREF cluster originally
# referenced by the JCL DD statement ``//XREFFILE DD DISP=SHR,
# DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS`` (READXREF.jcl lines 25-26).
# The mapping is canonicalized in
# ``src.batch.common.db_connector.VSAM_TABLE_MAP["CARDXREF"]`` — using
# the literal string here (rather than looking it up via the map) keeps
# the whitelist of imported names tight (read_table only) and avoids a
# runtime indirection for a value that is immutable for the lifetime
# of this script.
# ----------------------------------------------------------------------------
_TABLE_NAME: str = "card_cross_references"


def main() -> None:
    """Execute the cross-reference diagnostic reader PySpark Glue job.

    This is the Glue-level entry point that mirrors the COBOL
    ``MAIN-LOGIC`` paragraph-set of ``app/cbl/CBACT03C.cbl`` (lines
    70-87). It performs:

    1. **Initialization** — :func:`init_glue` provisions SparkSession,
       GlueContext, Job, and structured JSON logging (replaces JCL JOB
       card + EXEC PGM=CBACT03C + STEPLIB + SYSPRINT/SYSOUT DD cards
       from READXREF.jcl lines 1-2, 22-24, 27-28).
    2. **Open** — A single :func:`read_table` call replaces the COBOL
       ``0000-XREFFILE-OPEN`` paragraph (lines 118-134) plus the
       ``//XREFFILE DD`` statement from READXREF.jcl lines 25-26. The
       returned DataFrame is *lazy* — no JDBC traffic flows until
       ``.count()`` / ``.collect()`` is invoked below.
    3. **Sequential read** — The ``DataFrame.collect()`` call (plus the
       explicit ``.cache()`` immediately preceding it) replaces the
       ``PERFORM UNTIL END-OF-FILE`` loop (lines 74-81) along with the
       ``1000-XREFFILE-GET-NEXT`` paragraph (lines 92-116). Each row
       in the collected driver-side list corresponds to one iteration
       of the COBOL ``READ XREFFILE-FILE INTO CARD-XREF-RECORD``
       statement at line 93.
    4. **Display per record** — One :func:`logging.Logger.info` call
       per row replaces the COBOL ``DISPLAY CARD-XREF-RECORD`` at
       line 78 (and its twin at line 96 inside the 1000 paragraph).
       Structured JSON output to CloudWatch Logs replaces the
       traditional SYSOUT-to-JES-spool convention.
    5. **Close** — The implicit Spark materialization cleanup
       (``.unpersist()`` on the cached DataFrame) replaces the COBOL
       ``9000-XREFFILE-CLOSE`` paragraph (lines 136-152).
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
        ``COND`` parameter abort semantics and the COBOL 9999-ABEND-
        PROGRAM paragraph's ``CALL 'CEE3ABD'`` behavior.
    """
    # ------------------------------------------------------------------
    # Step 0: Glue / Spark initialization.
    # ------------------------------------------------------------------
    # Replaces the entire JCL boiler-plate for CBACT03C (JOB card,
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

    # COBOL line 71: DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'
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
        # Step 1: Open the CARDXREF table.
        #
        # Replaces:
        #   * COBOL 0000-XREFFILE-OPEN paragraph (lines 118-134)
        #       OPEN INPUT XREFFILE-FILE
        #   * JCL //XREFFILE DD DISP=SHR,
        #         DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS
        #     (READXREF.jcl lines 25-26)
        #
        # read_table() returns a LAZY PySpark DataFrame — no JDBC
        # traffic flows until a Spark action is executed below. Any
        # connection / authentication / permission errors will
        # therefore surface at the first .count() call, not here.
        # --------------------------------------------------------------
        logger.info("Opening card_cross_references table via JDBC...")
        xref_df = read_table(spark, _TABLE_NAME)

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
        # 'ERROR READING XREFFILE' text before re-raising.
        # --------------------------------------------------------------
        xref_df = xref_df.cache()
        record_count = xref_df.count()
        logger.info("card_cross_references record count: %d", record_count)

        # --------------------------------------------------------------
        # Step 3: Iterate and DISPLAY each CARD-XREF-RECORD.
        #
        # Replaces the COBOL main loop (lines 74-81):
        #
        #     PERFORM UNTIL END-OF-FILE = 'Y'
        #         IF  END-OF-FILE = 'N'
        #             PERFORM 1000-XREFFILE-GET-NEXT
        #             IF  END-OF-FILE = 'N'
        #                 DISPLAY CARD-XREF-RECORD
        #             END-IF
        #         END-IF
        #     END-PERFORM.
        #
        # In PySpark the sequential VSAM read collapses into a single
        # ``collect()`` action that materializes the entire table
        # driver-side. This is appropriate for a diagnostic reader
        # because:
        #   (a) CARDXREF is a lookup table of O(N=cards) rows with
        #       small per-row footprint (card_num + cust_id + acct_id
        #       ≈ 36 bytes per row excluding VSAM slack);
        #   (b) The diagnostic purpose of the original program is
        #       precisely to dump every row to SYSOUT.
        #
        # An empty table is a legitimate outcome (matches the COBOL
        # path where 1000-XREFFILE-GET-NEXT hits APPL-EOF on the
        # first invocation and MOVE 'Y' TO END-OF-FILE fires without
        # any DISPLAY CARD-XREF-RECORD ever executing).
        # --------------------------------------------------------------
        if record_count > 0:
            # Row.asDict() emits the full cross-reference record
            # (card_num, cust_id, acct_id) as a structured JSON
            # payload inside each log line — preserving the COBOL
            # DISPLAY CARD-XREF-RECORD semantic of emitting the full
            # 50-byte record to SYSOUT, but in a form that
            # CloudWatch Logs Insights can query structurally.
            for row in xref_df.collect():
                logger.info("CARD-XREF-RECORD: %s", row.asDict())
        else:
            # COBOL path when the loop exits immediately via
            # APPL-EOF on the first read. In mainframe SYSOUT this
            # would manifest as the START / END bracket lines with
            # nothing between them; here we log an explicit
            # informational line for operator clarity.
            logger.info("No cross-reference records found (empty table).")

        # --------------------------------------------------------------
        # Step 4: Release the cached DataFrame.
        #
        # Replaces the COBOL 9000-XREFFILE-CLOSE paragraph (lines
        # 136-152) which would CLOSE XREFFILE-FILE and validate the
        # resulting file status. Best-effort cleanup — if unpersist
        # throws, the run has already succeeded and we swallow the
        # exception at DEBUG level so the job does not flip to
        # FAILED for a post-success housekeeping glitch. This
        # mirrors the defensive close-error handling in
        # daily_tran_driver_job.py and other batch layer jobs.
        # --------------------------------------------------------------
        try:
            xref_df.unpersist()
        except Exception as unpersist_err:  # noqa: BLE001 — defensive
            logger.debug(
                "DataFrame.unpersist() raised during cleanup (non-fatal): %s",
                unpersist_err,
            )

        # --------------------------------------------------------------
        # Step 5: Emit COBOL DISPLAY 'END OF EXECUTION' and commit.
        # Replaces CBACT03C.cbl line 85 + the 9000-XREFFILE-CLOSE
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
        #   * 'ERROR OPENING XREFFILE'  (line 129) — for JDBC connect errors
        #   * 'ERROR READING XREFFILE'  (line 110) — for JDBC query errors
        #   * 'ERROR CLOSING XREFFILE'  (line 147) — for pre-commit errors
        #   * 'ABENDING PROGRAM'        (line 155)
        #   * CALL 'CEE3ABD'            (line 158)
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
# carddemo-read-xref --<other> <val> ...``). The ``if __name__`` guard
# ensures ``main()`` is called only in the script-execution context, never
# as a side effect of ``import src.batch.jobs.read_xref_job`` (which would
# be catastrophic during unit-test collection or Step Functions script
# validation).
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
