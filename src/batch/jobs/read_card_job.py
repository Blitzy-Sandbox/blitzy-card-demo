# ============================================================================
# Source: app/cbl/CBACT02C.cbl — Card Diagnostic Reader
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
"""Card diagnostic reader PySpark Glue job.

Replaces ``app/cbl/CBACT02C.cbl`` + ``app/jcl/READCARD.jcl`` — the
mainframe *diagnostic* program that reads every record from the
CARDDATA VSAM KSDS cluster (card master file) and DISPLAYs it to
the system console for operator verification.

Overview
--------
The original COBOL program ``CBACT02C`` (see ``app/cbl/CBACT02C.cbl``)
implements the canonical z/OS sequential-read-until-EOF pattern against
a single INDEXED VSAM KSDS cluster::

    FILE-CONTROL.
        SELECT CARDFILE-FILE ASSIGN TO   CARDFILE
               ORGANIZATION IS INDEXED
               ACCESS MODE  IS SEQUENTIAL
               RECORD KEY   IS FD-CARD-NUM
               FILE STATUS  IS CARDFILE-STATUS.

and the PROCEDURE DIVISION (lines 70-87 of CBACT02C.cbl) is the
minimal::

    DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'.
    PERFORM 0000-CARDFILE-OPEN.
    PERFORM UNTIL END-OF-FILE = 'Y'
        IF  END-OF-FILE = 'N'
            PERFORM 1000-CARDFILE-GET-NEXT
            IF  END-OF-FILE = 'N'
                DISPLAY CARD-RECORD
            END-IF
        END-IF
    END-PERFORM.
    PERFORM 9000-CARDFILE-CLOSE.
    DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'.
    GOBACK.

Record Layout (``app/cpy/CVACT02Y.cpy`` — RECLN 150)
----------------------------------------------------
::

    01 CARD-RECORD.
        05  CARD-NUM                          PIC X(16).
        05  CARD-ACCT-ID                      PIC 9(11).
        05  CARD-CVV-CD                       PIC 9(03).
        05  CARD-EMBOSSED-NAME                PIC X(50).
        05  CARD-EXPIRAION-DATE               PIC X(10).
        05  CARD-ACTIVE-STATUS                PIC X(01).
        05  FILLER                            PIC X(59).

The field name ``CARD-EXPIRAION-DATE`` (sic — missing a "T") is the
authoritative spelling from the original COBOL copybook and is
preserved verbatim across the source mainframe artifact. The target
Aurora PostgreSQL ``cards`` table (``db/migrations/V1__schema.sql``)
canonicalizes this as ``expiration_date`` in the migrated schema
(SQLAlchemy ORM in ``src/shared/models/card.py``) while the VSAM
59-byte FILLER slack column is dropped (no semantic content, pure
VSAM record-length padding). The 16-character ``CARD-NUM`` PIC clause
becomes the ``VARCHAR(16)`` primary key, the 11-digit ``CARD-ACCT-ID``
becomes ``BIGINT`` for the foreign key to ``accounts``, and the
3-digit ``CARD-CVV-CD`` is stored with the project's data-at-rest
encryption policy applied (PII). The single-character
``CARD-ACTIVE-STATUS`` typically holds ``'Y'``/``'N'`` and maps to a
``CHAR(1)`` column.

Mainframe-to-Cloud Transformation
---------------------------------
* JCL ``//READCARD JOB`` + ``//STEP05 EXEC PGM=CBACT02C`` +
  ``//STEPLIB DD DISP=SHR,DSN=AWS.M2.CARDDEMO.LOADLIB`` +
  ``//SYSOUT DD SYSOUT=*`` + ``//SYSPRINT DD SYSOUT=*`` from
  ``app/jcl/READCARD.jcl`` (lines 1-2, 22-24, 27-28) all collapse
  into a single :func:`src.batch.common.glue_context.init_glue`
  call which provisions the SparkSession, GlueContext, Job, and
  structured JSON logging handler pointed at CloudWatch.
* JCL ``//CARDFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS``
  (READCARD.jcl lines 25-26) plus the COBOL ``0000-CARDFILE-OPEN``
  paragraph (CBACT02C.cbl lines 118-134) + the main read-loop
  (lines 74-81) driven by ``1000-CARDFILE-GET-NEXT`` (lines 92-116)
  + the ``9000-CARDFILE-CLOSE`` paragraph (lines 136-152) all
  collapse into a single :func:`src.batch.common.db_connector.read_table`
  call which issues a JDBC query against the Aurora PostgreSQL
  ``cards`` table. The canonical VSAM-to-PostgreSQL mapping lives
  in :data:`src.batch.common.db_connector.VSAM_TABLE_MAP` with
  ``"CARDDATA": "cards"``.
* Each COBOL ``DISPLAY CARD-RECORD`` statement (line 78) becomes a
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
cloud-native equivalent of running ``READCARD.jcl`` on the
mainframe: a lightweight verification tool that operators can
invoke ad-hoc to validate the full contents of the ``cards`` table
after a data migration or before running the batch pipeline. It is
**not** part of the 5-stage POSTTRAN → INTCALC → COMBTRAN →
(CREASTMT ∥ TRANREPT) sequence. Card data flows into the main
pipeline via the ``cards`` table which is referenced by
:mod:`src.batch.jobs.daily_tran_driver_job` (validation/lookup flow)
and indirectly via the ``card_cross_references`` join table used by
the transaction posting, interest calculation, and statement
generation stages.

Error Handling
--------------
Any exception raised by :func:`init_glue`, :func:`read_table`, or
the Spark actions (``.cache()``, ``.count()``, ``.collect()``) is
logged with the COBOL-equivalent DISPLAY text
(``'ERROR READING CARDFILE'`` — see CBACT02C.cbl line 110) and
re-raised. AWS Glue will then mark the Job as ``FAILED``, causing
Step Functions (if invoked from a state machine) to halt the
pipeline — preserving the JCL ``COND=(0,NE)`` abort semantics from
the mainframe implementation. The COBOL ``9999-ABEND-PROGRAM``
paragraph (``CALL 'CEE3ABD'`` at line 158) maps to the Python 3
default non-zero exit code on uncaught exceptions, which AWS Glue
interprets as job failure.

See Also
--------
:mod:`src.batch.common.glue_context`     — init_glue / commit_job factory
:mod:`src.batch.common.db_connector`     — JDBC read_table helper
:mod:`src.batch.jobs.read_account_job`   — Companion reader (CBACT01C.cbl)
:mod:`src.batch.jobs.read_customer_job`  — Companion reader (CBCUS01C.cbl)
:mod:`src.batch.jobs.read_xref_job`      — Companion reader (CBACT03C.cbl)
AAP §0.2.2 — Batch Program Classification (CBACT02C listed as utility)
AAP §0.5.1 — File-by-File Transformation Plan (read_card_job entry)
AAP §0.7.1 — Refactoring-Specific Rules (preserve functionality exactly)

Source
------
* ``app/cbl/CBACT02C.cbl``  — COBOL diagnostic program (179 lines)
* ``app/jcl/READCARD.jcl``  — JCL job card + EXEC PGM=CBACT02C
* ``app/cpy/CVACT02Y.cpy``  — CARD-RECORD layout (150 bytes)
"""

from __future__ import annotations

# ----------------------------------------------------------------------------
# Standard library imports.
# ----------------------------------------------------------------------------
# ``logging``    — structured JSON logging configured by init_glue() that
#                  emits to CloudWatch, replacing the mainframe's
#                  ``//SYSPRINT DD SYSOUT=*`` + ``//SYSOUT DD SYSOUT=*``
#                  convention for CBACT02C's DISPLAY statements (see
#                  READCARD.jcl lines 27-28).
# ``sys``        — AWS Glue script convention; init_glue() internally
#                  uses sys.argv via awsglue.utils.getResolvedOptions, and
#                  the ``if __name__`` guard below records sys.argv at
#                  DEBUG for CloudWatch-side operator troubleshooting.
# ``typing.Any`` — loose type annotation for ``dict[str, Any]`` used by
#                  :func:`_mask_card_record` to accept arbitrary row-dict
#                  values produced by ``pyspark.sql.Row.asDict()`` (which
#                  can yield str / int / None depending on the column).
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
#     JCL ``MAXCC=0`` success signalling from CBACT02C.cbl line 87.
# ``read_table(spark, "<table>")``
#     Issues a JDBC query against the configured Aurora PostgreSQL
#     cluster and returns a lazy PySpark DataFrame. No JDBC traffic
#     flows until a Spark action (``.count()``, ``.collect()``, etc.)
#     is triggered. Replaces the COBOL ``0000-CARDFILE-OPEN`` +
#     ``1000-CARDFILE-GET-NEXT`` + ``9000-CARDFILE-CLOSE`` sequence
#     plus the JCL ``//CARDFILE DD DISP=SHR,DSN=AWS.M2.CARDDEMO.
#     CARDDATA.VSAM.KSDS`` file binding from READCARD.jcl lines 25-26.
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
# of ``app/cbl/CBACT02C.cbl``. They are declared as module-level constants
# (rather than inlined at the DISPLAY call sites) to make the
# functional-parity contract with the COBOL source explicit and grep-able.
# The file-level agent prompt for this file specifically mandates: "Preserve
# exact COBOL DISPLAY messages" — any change to these literals is a
# functional-parity regression.
# ----------------------------------------------------------------------------
_COBOL_START_MSG: str = "START OF EXECUTION OF PROGRAM CBACT02C"
_COBOL_END_MSG: str = "END OF EXECUTION OF PROGRAM CBACT02C"

# COBOL error DISPLAYs preserved from lines 110, 129, 147, 155 of
# ``app/cbl/CBACT02C.cbl``. Only ERROR READING is emitted in the happy-path
# error handler below because init_glue + read_table collapse the
# separate OPEN / READ / CLOSE phases into a single logical operation;
# the mainframe's OPEN and CLOSE errors therefore surface through the
# same exception path as a READ error in the PySpark translation. The
# CBACT02C source uses the short form "CARDFILE" (not "CARD FILE") in
# all three error DISPLAYs — preserved verbatim here for functional
# parity with the mainframe operator experience.
_COBOL_ERROR_READING_MSG: str = "ERROR READING CARDFILE"
_COBOL_ABEND_MSG: str = "ABENDING PROGRAM"

# ----------------------------------------------------------------------------
# Glue job name — exposed as a module-level constant to make the mapping
# between this PySpark script and the corresponding AWS Glue Job resource
# explicit and testable. Naming follows the ``carddemo-<job>`` convention
# applied across the batch layer (see sibling files such as
# ``read_account_job.py`` / ``carddemo-read-account``,
# ``read_customer_job.py`` / ``carddemo-read-customer``,
# ``read_xref_job.py`` / ``carddemo-read-xref``,
# ``daily_tran_driver_job.py`` / ``carddemo-daily-tran-driver``). This
# constant is also the value that flows into ``--JOB_NAME`` when Step
# Functions (or a manual `aws glue start-job-run`) triggers this script.
# The string literal "carddemo-read-card" is mandated by the file's
# agent prompt: ``init_glue(job_name="carddemo-read-card")``.
# ----------------------------------------------------------------------------
_JOB_NAME: str = "carddemo-read-card"

# ----------------------------------------------------------------------------
# Target PostgreSQL table. Maps to the VSAM CARDDATA cluster originally
# referenced by the JCL DD statement ``//CARDFILE DD DISP=SHR,
# DSN=AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS`` (READCARD.jcl lines 25-26).
# The mapping is canonicalized in
# ``src.batch.common.db_connector.VSAM_TABLE_MAP["CARDDATA"]`` — using
# the literal string here (rather than looking it up via the map) keeps
# the whitelist of imported names tight (read_table only) and avoids a
# runtime indirection for a value that is immutable for the lifetime
# of this script. The value "cards" matches the agent prompt
# specification: ``read_table(spark, "cards")``.
# ----------------------------------------------------------------------------
_TABLE_NAME: str = "cards"


# ----------------------------------------------------------------------------
# _mask_card_record — PCI-DSS v4.0 Requirement 3.3 and Requirement 3.2
# compliance helper for logging card rows.
# ----------------------------------------------------------------------------
# PCI-DSS v4.0 Requirement 3.3.1 mandates that the PAN (Primary Account
# Number, i.e. ``card_num``) be masked when displayed — the maximum number
# of digits that may be rendered in clear text is the first six and the
# last four digits (BIN + last-4 pattern).  CloudWatch Logs persistence
# is a "display" under PCI-DSS (the log entries are human-readable by
# operators with the appropriate IAM policy), so the unmasked PAN must
# never be emitted to any log record.
#
# PCI-DSS v4.0 Requirement 3.2.1 (Sensitive Authentication Data — SAD)
# mandates that SAD elements, which include the full card verification
# value (CVV / CVV2 / CID / CAV2 — ``cvv_cd`` in our schema), MUST NOT
# be stored after the authorization flow completes.  CloudWatch log
# persistence meets the PCI-DSS definition of "stored", so CVV values
# must be excluded entirely from any log payload.
#
# This helper builds a PCI-safe copy of a row dict by:
#   * masking ``card_num`` as ``<first-6>******<last-4>`` — six asterisks
#     in the middle so the emitted token length is stable at 16 characters
#     matching VARCHAR(16) / CVACT02Y ``CARD-NUM PIC X(16)``;
#   * removing ``cvv_cd`` entirely from the output dict (the key does
#     not appear);
#   * passing all other keys through unchanged (``acct_id``,
#     ``embossed_name``, ``expiration_date``, ``active_status`` are not
#     PCI-DSS account data or SAD).
#
# The function does not mutate its input: it returns a new dict.  Null
# (``None``) card numbers are passed through unchanged since there is
# nothing to mask.  Short / non-standard card numbers (length ≤ 10)
# are masked in their entirety because the first-6 + last-4 pattern
# would overlap and leak the middle digits.
# ----------------------------------------------------------------------------
def _mask_card_record(row_dict: dict[str, Any]) -> dict[str, Any]:
    """Return a PCI-DSS-safe copy of a card row dict suitable for logging.

    Parameters
    ----------
    row_dict : dict[str, Any]
        Dict produced by :meth:`pyspark.sql.Row.asDict` on a row of
        the ``cards`` table.  Expected keys: ``card_num``,
        ``acct_id``, ``cvv_cd``, ``embossed_name``,
        ``expiration_date``, ``active_status``.  Missing keys are
        tolerated — the function operates on whatever keys are
        present.

    Returns
    -------
    dict[str, Any]
        A new dict whose ``card_num`` entry is masked per PCI-DSS
        Requirement 3.3.1 (first-6 + last-4 format) and whose
        ``cvv_cd`` entry is entirely omitted per PCI-DSS
        Requirement 3.2.1.  All other keys pass through unchanged.
        The input ``row_dict`` is not mutated.
    """
    masked: dict[str, Any] = {}
    for key, value in row_dict.items():
        if key == "cvv_cd":
            # PCI-DSS Req 3.2.1 — SAD (CVV/CAV2/CID) must never be
            # stored after authorization; CloudWatch log persistence
            # qualifies as storage.  Omit the key entirely.
            continue
        if key == "card_num":
            if value is None:
                masked[key] = None
                continue
            card_str = str(value)
            if len(card_str) > 10:
                # PCI-DSS Req 3.3.1 — keep first-6 BIN + last-4, mask
                # the middle with exactly six asterisks.  For a
                # 16-character PAN the resulting token length is
                # preserved (6 + 6 + 4 = 16 characters) so downstream
                # parsers relying on fixed field widths still work.
                masked[key] = f"{card_str[:6]}******{card_str[-4:]}"
            else:
                # Short / malformed PAN — cannot apply the
                # first-6+last-4 split without overlap.  Mask in full.
                masked[key] = "*" * len(card_str)
            continue
        masked[key] = value
    return masked


def main() -> None:
    """Execute the card diagnostic reader PySpark Glue job.

    This is the Glue-level entry point that mirrors the COBOL
    ``MAIN-LOGIC`` paragraph-set of ``app/cbl/CBACT02C.cbl`` (lines
    70-87). It performs:

    1. **Initialization** — :func:`init_glue` provisions SparkSession,
       GlueContext, Job, and structured JSON logging (replaces JCL JOB
       card + EXEC PGM=CBACT02C + STEPLIB + SYSPRINT/SYSOUT DD cards
       from READCARD.jcl lines 1-2, 22-24, 27-28).
    2. **Open** — A single :func:`read_table` call replaces the COBOL
       ``0000-CARDFILE-OPEN`` paragraph (lines 118-134) plus the
       ``//CARDFILE DD`` statement from READCARD.jcl lines 25-26. The
       returned DataFrame is *lazy* — no JDBC traffic flows until
       ``.count()`` / ``.collect()`` is invoked below.
    3. **Sequential read** — The ``DataFrame.collect()`` call (plus the
       explicit ``.cache()`` immediately preceding it) replaces the
       ``PERFORM UNTIL END-OF-FILE`` loop (lines 74-81) along with the
       ``1000-CARDFILE-GET-NEXT`` paragraph (lines 92-116). Each row
       in the collected driver-side list corresponds to one iteration
       of the COBOL ``READ CARDFILE-FILE INTO CARD-RECORD`` statement
       at line 93.
    4. **Display per record** — One :func:`logging.Logger.info` call
       per row replaces the COBOL ``DISPLAY CARD-RECORD`` at line 78
       (and its commented-out twin at line 96 inside the 1000
       paragraph). Structured JSON output to CloudWatch Logs replaces
       the traditional SYSOUT-to-JES-spool convention.
    5. **Close** — The implicit Spark materialization cleanup
       (``.unpersist()`` on the cached DataFrame) replaces the COBOL
       ``9000-CARDFILE-CLOSE`` paragraph (lines 136-152).
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
        behavior (lines 154-158 of CBACT02C.cbl).
    """
    # ------------------------------------------------------------------
    # Step 0: Glue / Spark initialization.
    # ------------------------------------------------------------------
    # Replaces the entire JCL boiler-plate for CBACT02C (JOB card,
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

    # COBOL line 71: DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'
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
        # Step 1: Open the CARDDATA table.
        #
        # Replaces:
        #   * COBOL 0000-CARDFILE-OPEN paragraph (lines 118-134)
        #       OPEN INPUT CARDFILE-FILE
        #   * JCL //CARDFILE DD DISP=SHR,
        #         DSN=AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS
        #     (READCARD.jcl lines 25-26)
        #
        # read_table() returns a LAZY PySpark DataFrame — no JDBC
        # traffic flows until a Spark action is executed below. Any
        # connection / authentication / permission errors will
        # therefore surface at the first .count() call, not here.
        # --------------------------------------------------------------
        logger.info("Opening cards table via JDBC...")
        cards_df = read_table(spark, _TABLE_NAME)

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
        # 'ERROR READING CARDFILE' text (line 110 of CBACT02C.cbl)
        # before re-raising.
        # --------------------------------------------------------------
        cards_df = cards_df.cache()
        record_count = cards_df.count()
        logger.info("cards record count: %d", record_count)

        # --------------------------------------------------------------
        # Step 3: Iterate and DISPLAY each CARD-RECORD.
        #
        # Replaces the COBOL main loop (lines 74-81):
        #
        #     PERFORM UNTIL END-OF-FILE = 'Y'
        #         IF  END-OF-FILE = 'N'
        #             PERFORM 1000-CARDFILE-GET-NEXT
        #             IF  END-OF-FILE = 'N'
        #                 DISPLAY CARD-RECORD
        #             END-IF
        #         END-IF
        #     END-PERFORM.
        #
        # In PySpark the sequential VSAM read collapses into a single
        # ``collect()`` action that materializes the entire table
        # driver-side. This is appropriate for a diagnostic reader
        # because:
        #   (a) CARDDATA is the card master table of O(N=cards) rows
        #       with modest per-row footprint (~150 bytes per row in
        #       the original VSAM KSDS including slack filler);
        #   (b) The diagnostic purpose of the original program is
        #       precisely to dump every row to SYSOUT.
        #
        # An empty table is a legitimate outcome (matches the COBOL
        # path where 1000-CARDFILE-GET-NEXT hits APPL-EOF on the
        # first invocation and MOVE 'Y' TO END-OF-FILE fires without
        # any DISPLAY CARD-RECORD ever executing).
        # --------------------------------------------------------------
        if record_count > 0:
            # Row.asDict() emits one dict per card row; each dict is
            # passed through :func:`_mask_card_record` which applies
            # PCI-DSS v4.0 Requirement 3.3.1 (first-6 + last-4 PAN
            # masking) and Requirement 3.2.1 (CVV/SAD removal) before
            # the record is handed to the logger.  This preserves the
            # COBOL DISPLAY CARD-RECORD semantic of emitting one line
            # per row to the diagnostic log, but ensures that neither
            # the full PAN nor any sensitive authentication data ever
            # lands in CloudWatch Logs.
            #
            # PCI-DSS compliance notes:
            #   * Req 3.3.1 — Only the first 6 (BIN) and last 4
            #     digits of ``card_num`` are emitted in clear text.
            #   * Req 3.2.1 — ``cvv_cd`` is never emitted to logs
            #     (key omitted from the masked dict entirely).
            #   * The remaining columns (``acct_id``,
            #     ``embossed_name``, ``expiration_date``,
            #     ``active_status``) are not PCI-DSS account data or
            #     SAD and are therefore safe to log.
            # The unmasked row data itself remains accessible only
            # through the Aurora PostgreSQL ``cards`` table under
            # the IAM policies that gate the JDBC read; it is never
            # written to CloudWatch.
            for row in cards_df.collect():
                logger.info("CARD-RECORD: %s", _mask_card_record(row.asDict()))
        else:
            # COBOL path when the loop exits immediately via
            # APPL-EOF on the first read. In mainframe SYSOUT this
            # would manifest as the START / END bracket lines with
            # nothing between them; here we log an explicit
            # informational line for operator clarity.
            logger.info("No card records found (empty table).")

        # --------------------------------------------------------------
        # Step 4: Release the cached DataFrame.
        #
        # Replaces the COBOL 9000-CARDFILE-CLOSE paragraph (lines
        # 136-152) which would CLOSE CARDFILE-FILE and validate the
        # resulting file status. Best-effort cleanup — if unpersist
        # throws, the run has already succeeded and we swallow the
        # exception at DEBUG level so the job does not flip to
        # FAILED for a post-success housekeeping glitch. This
        # mirrors the defensive close-error handling in
        # read_customer_job.py, read_xref_job.py, and other batch
        # layer jobs.
        # --------------------------------------------------------------
        try:
            cards_df.unpersist()
        except Exception as unpersist_err:  # noqa: BLE001 — defensive
            logger.debug(
                "DataFrame.unpersist() raised during cleanup (non-fatal): %s",
                unpersist_err,
            )

        # --------------------------------------------------------------
        # Step 5: Emit COBOL DISPLAY 'END OF EXECUTION' and commit.
        # Replaces CBACT02C.cbl line 85 + the 9000-CARDFILE-CLOSE
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
        #   * 'ERROR OPENING CARDFILE'       (line 129) — JDBC connect errors
        #   * 'ERROR READING CARDFILE'       (line 110) — JDBC query errors
        #   * 'ERROR CLOSING CARDFILE'       (line 147) — pre-commit errors
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
# carddemo-read-card --<other> <val> ...``). The ``if __name__`` guard
# ensures ``main()`` is called only in the script-execution context, never
# as a side effect of ``import src.batch.jobs.read_card_job`` (which would
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
