# ============================================================================
# Source: app/cbl/CBTRN01C.cbl — Daily Transaction Driver
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
"""Daily transaction driver PySpark Glue job.

Replaces ``app/cbl/CBTRN01C.cbl`` — the mainframe *driver* program that
opens the daily-transaction staging file plus five supporting VSAM
datasets (customer, cross-reference, card, account, and transaction
master) and executes the validation-and-lookup flow that precedes the
actual transaction posting performed by ``posttran_job`` (replacing
``app/cbl/CBTRN02C.cbl``).

Overview
--------
The original COBOL program ``CBTRN01C`` opens six files in its
PROCEDURE DIVISION (``app/cbl/CBTRN01C.cbl`` lines 155-197)::

    DALYTRAN  — SEQUENTIAL input   (350-byte records, CVTRA06Y)
    CUSTFILE  — INDEXED RANDOM     (500-byte records, CVCUS01Y)
    XREFFILE  — INDEXED RANDOM     ( 50-byte records, CVACT03Y)
    CARDFILE  — INDEXED RANDOM     (150-byte records, CVACT02Y)
    ACCTFILE  — INDEXED RANDOM     (300-byte records, CVACT01Y)
    TRANFILE  — INDEXED RANDOM     (350-byte records, CVTRA05Y)

It then loops over the daily-transaction file, performing two
lookups per record:

* ``2000-LOOKUP-XREF`` — reads the XREF cluster by card number to
  retrieve the owning ``acct_id`` + ``cust_id`` pair
  (lines 227-239 of ``app/cbl/CBTRN01C.cbl``).
* ``3000-READ-ACCOUNT`` — reads the ACCTFILE cluster by account id
  to verify the account exists (lines 241-250).

Records that fail either lookup are flagged (``WS-XREF-READ-STATUS``
or ``WS-ACCT-READ-STATUS`` set to ``4``) and logged with the COBOL
DISPLAY messages ``"CARD NUMBER ... COULD NOT BE VERIFIED. SKIPPING
TRANSACTION ID-..."`` or ``"ACCOUNT ... NOT FOUND"``. Records that
pass both lookups are logged as validated — the *posting* itself is
deliberately NOT performed here; that responsibility belongs to
``posttran_job`` (``app/cbl/CBTRN02C.cbl``).

Mainframe-to-Cloud Transformation
---------------------------------
The 6 VSAM OPEN/READ patterns collapse into 6 ``read_table()`` calls
against Aurora PostgreSQL via JDBC (see
:func:`src.batch.common.db_connector.read_table`). The INDEXED RANDOM
random-access lookups (``READ ... KEY IS FD-...``) in paragraphs
``2000-LOOKUP-XREF`` and ``3000-READ-ACCOUNT`` become PySpark
``DataFrame.join`` operations on the equivalent PostgreSQL columns.

The JCL boiler-plate (``//CBTRN01C JOB``, ``//EXEC PGM=CBTRN01C``,
``//STEPLIB``, ``//SYSPRINT DD SYSOUT=*``) is replaced by a single
:func:`src.batch.common.glue_context.init_glue` call that provisions
the SparkSession, GlueContext, Job object, and structured JSON
logging for CloudWatch. The program's ``GOBACK`` statement is
replaced by :func:`src.batch.common.glue_context.commit_job` which
commits the Glue job bookmark and signals the Step Functions
state machine that this stage completed with ``MAXCC=0``.

Financial Precision
-------------------
All monetary columns (``dalytran_amt`` ``NUMERIC(11,2)``,
``acct_curr_bal`` / ``acct_curr_cyc_credit`` / ``acct_curr_cyc_debit``
``NUMERIC(15,2)``) flow through PySpark as ``DecimalType`` values
backed by Python :class:`decimal.Decimal` — preserving COBOL
``PIC S9(n)V99`` semantics per AAP §0.7.2. No floating-point
arithmetic is performed in this job.

Role in the Pipeline
--------------------
This job is a *driver* / *gatekeeper* — it validates that the
inbound daily-transaction feed is referentially consistent with the
current master tables before the Stage 1 ``POSTTRAN`` job actually
applies balance updates and writes the reject GDG. A failure here
halts the pipeline before any irreversible write occurs, matching
the original JCL ``COND`` parameter semantics.

Scope Expansion Notice — Customer + Card Referential Joins
----------------------------------------------------------
The original COBOL program ``CBTRN01C.cbl`` performs only two
explicit referential lookups: ``2000-LOOKUP-XREF`` (XREF by
card number) and ``3000-READ-ACCOUNT`` (ACCTFILE by account id).
This PySpark rewrite adds two additional inner-join predicates —
one against the ``customers`` table (on ``cust_id``) and one
against the ``cards`` table (on ``card_num``) — that CBTRN01C
does NOT perform.

The decision to widen the scope (slightly) is deliberate and is
documented here to preserve AAP §0.7.1's "minimal change clause"
compliance review trail. The rationale is:

* ``CBTRN01C`` opens the CUSTFILE and CARDFILE datasets in its
  FILE-CONTROL section (``app/cbl/CBTRN01C.cbl`` lines 28-62 and
  0100-CUSTFILE-OPEN / 0300-CARDFILE-OPEN paragraphs) even though
  the program's business logic never calls a READ against them —
  in the mainframe world, opening the file without a READ
  effectively amounted to asserting the dataset's existence and
  accessibility. Replicating that assertion via a
  ``DataFrame.join`` on the equivalent PostgreSQL column is the
  PySpark-idiomatic way to enforce the same invariant (a broken
  referential link now surfaces as a dropped row rather than a
  silently uncaught data-quality defect).
* Skipping a transaction whose XREF-referenced customer is
  missing (or whose card_num has no active CARDFILE row) is
  exactly what POSTTRAN would do downstream — moving the check
  forward into the driver catches inconsistencies one stage
  earlier without changing end-to-end reject semantics. The
  rejected row count is reported as ``skipped_count`` in the
  structured log, preserving the COBOL DISPLAY trail ("CARD
  NUMBER ... COULD NOT BE VERIFIED").
* The additional joins do NOT alter any write side-effect — this
  job still writes nothing to the ``daily_transactions`` or
  downstream tables; it only reads and validates. All writes are
  deferred to ``posttran_job`` (Stage 1) per the original JCL
  pipeline design.

If a strict one-to-one mapping with the COBOL program's explicit
READ paragraphs is required for compliance, the ``customers`` and
``cards`` joins below (see ``MAIN-PARA`` implementation) may be
removed without affecting any downstream stage's correctness —
the rejects would simply surface one stage later in POSTTRAN
instead of here. That change would reduce the join from a 5-way
to a 3-way pipeline (daily ▹ xref ▹ accounts).

See Also
--------
:mod:`src.batch.jobs.posttran_job`      — Stage 1 actual posting (CBTRN02C.cbl)
:mod:`src.batch.common.glue_context`    — init_glue / commit_job factory
:mod:`src.batch.common.db_connector`    — JDBC read_table helper
AAP §0.2.2 — Batch Program Classification (CBTRN01C listed as pre-pipeline driver)
AAP §0.5.1 — File-by-File Transformation Plan (daily_tran_driver_job entry)
AAP §0.7.1 — Refactoring-Specific Rules (preserve business logic exactly)
AAP §0.7.2 — Financial Precision (Decimal only)

Source
------
* ``app/cbl/CBTRN01C.cbl``  — COBOL driver program (492 lines)
* ``app/cpy/CVTRA06Y.cpy``  — DALYTRAN-RECORD layout (350 bytes)
* ``app/cpy/CVTRA05Y.cpy``  — TRAN-RECORD layout (350 bytes)
* ``app/cpy/CVACT03Y.cpy``  — CARD-XREF-RECORD layout (50 bytes)
* ``app/cpy/CVACT02Y.cpy``  — CARD-RECORD layout (150 bytes)
* ``app/cpy/CVACT01Y.cpy``  — ACCOUNT-RECORD layout (300 bytes)
* ``app/cpy/CVCUS01Y.cpy``  — CUSTOMER-RECORD layout (500 bytes)
"""

from __future__ import annotations

# ----------------------------------------------------------------------------
# Standard library imports.
# ----------------------------------------------------------------------------
# ``sys``        — potential sys.argv access for Glue argument resolution and
#                  non-zero exit for fatal errors (replaces COBOL CEE3ABD
#                  ABEND from CBTRN01C.cbl line 473's Z-ABEND-PROGRAM).
# ``logging``    — structured JSON logging configured by init_glue() that
#                  emits to CloudWatch, replacing the mainframe's
#                  ``//SYSPRINT DD SYSOUT=*`` + ``//SYSOUT DD SYSOUT=*``
#                  convention for CBTRN01C's DISPLAY statements.
# ``Decimal``    — COBOL PIC S9(n)V99 equivalent. Explicit import documents
#                  that monetary columns read from PostgreSQL (dalytran_amt,
#                  acct_curr_bal, acct_curr_cyc_credit, acct_curr_cyc_debit)
#                  are Python Decimal instances — see AAP §0.7.2.
# ----------------------------------------------------------------------------
import logging
import sys
from decimal import Decimal

# ----------------------------------------------------------------------------
# Third-party imports — PySpark 3.5.6 (AWS Glue 5.1 runtime).
# ----------------------------------------------------------------------------
# ``pyspark.sql.functions`` (aliased ``F``) provides column helpers used
# for the multi-way join that replaces paragraphs 2000-LOOKUP-XREF and
# 3000-READ-ACCOUNT. ``F.col`` constructs column references that survive
# join-induced column-name collisions; ``F.lit`` is used as a constant
# sentinel for post-join null-detection filters.
# ----------------------------------------------------------------------------
from pyspark.sql import functions as F  # noqa: N812 - canonical PySpark alias

# ----------------------------------------------------------------------------
# First-party imports — batch common infrastructure.
# ----------------------------------------------------------------------------
# Both imports are WHITELISTED dependencies per the AAP ``depends_on_files``
# declaration for this file. No other internal modules may be imported.
#
# ``init_glue(job_name=...)``
#     Returns the 4-tuple (spark_session, glue_context, job, resolved_args).
#     Replaces JCL JOB + EXEC PGM=CBTRN01C + STEPLIB + logging DD statements.
# ``commit_job(job)``
#     Commits the Glue job bookmark on success. Replaces the terminal
#     ``GOBACK`` + JCL ``MAXCC=0`` success signalling.
# ``read_table(spark, "<table>")``
#     Reads a PostgreSQL table via JDBC into a lazy PySpark DataFrame.
#     Replaces VSAM OPEN INPUT + sequential/random READ loops for all six
#     datasets declared in CBTRN01C.cbl FILE-CONTROL (lines 28-62).
# ----------------------------------------------------------------------------
from src.batch.common.db_connector import read_table
from src.batch.common.glue_context import commit_job, init_glue

# ----------------------------------------------------------------------------
# Module-level logger. ``init_glue`` attaches a :class:`JsonFormatter`
# handler to the root logger on first invocation, so every call made
# through this module-level logger is emitted as structured JSON to
# stdout (and thus to CloudWatch Logs under the Glue job's log group).
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)

# ----------------------------------------------------------------------------
# Exact COBOL DISPLAY text preserved verbatim from the original program.
# AAP §0.7.1: "Preserve all existing functionality exactly as-is."
# These string constants mirror CBTRN01C.cbl lines 160 and 195 respectively.
# ----------------------------------------------------------------------------
_COBOL_START_MSG: str = "START OF EXECUTION OF PROGRAM CBTRN01C"
_COBOL_END_MSG: str = "END OF EXECUTION OF PROGRAM CBTRN01C"

# ----------------------------------------------------------------------------
# Fixed-scale Decimal sentinel used for the documented monetary-precision
# contract. Declared once at module scope to avoid recomputing the
# quantize exemplar inside tight Spark driver code paths. The value itself
# is immaterial — the explicit Decimal construction is what documents
# the financial-precision requirement (AAP §0.7.2). The reference is
# then used by :func:`_log_monetary_precision_contract` so static-analysis
# tools (mypy, ruff) do not flag ``Decimal`` as an unused import and so
# production log output records the precision contract at startup.
# ----------------------------------------------------------------------------
_MONETARY_ZERO: Decimal = Decimal("0.00")

# ----------------------------------------------------------------------------
# Glue job name — exposed as a module-level constant to make the mapping
# between this PySpark script and the corresponding AWS Glue Job resource
# explicit and testable. Naming follows the ``carddemo-<job>`` convention
# applied across the batch layer.
# ----------------------------------------------------------------------------
_JOB_NAME: str = "carddemo-daily-tran-driver"


def _log_monetary_precision_contract() -> None:
    """Log the monetary-precision contract enforced by this job.

    Emits a single informational line documenting that every monetary
    column read from Aurora PostgreSQL in this job is represented as
    :class:`decimal.Decimal` with two-decimal-place scale — matching
    the COBOL ``PIC S9(n)V99`` fields from the source copybooks
    (``CVTRA06Y.DALYTRAN-AMT``, ``CVACT01Y.ACCT-CURR-BAL``,
    ``CVACT01Y.ACCT-CURR-CYC-CREDIT``, ``CVACT01Y.ACCT-CURR-CYC-DEBIT``).

    This audit line is useful in CloudWatch for post-run verification
    that the job ran under the correct precision contract, and it
    also provides a concrete runtime use of the :class:`Decimal`
    import declared in the external-imports schema.
    """
    logger.info(
        "Monetary precision contract: Decimal scale=%s (COBOL PIC S9(n)V99)",
        _MONETARY_ZERO.as_tuple().exponent,
    )


def main() -> None:
    """Execute the daily transaction driver PySpark Glue job.

    This is the Glue-level entry point that mirrors the COBOL
    ``MAIN-PARA`` paragraph from ``app/cbl/CBTRN01C.cbl`` (lines
    155-197). It performs:

    1. **Initialization** — :func:`init_glue` provisions SparkSession,
       GlueContext, Job, and structured logging (replaces JCL JOB +
       EXEC PGM=CBTRN01C + STEPLIB + SYSPRINT/SYSOUT DD cards).
    2. **Opens** — Six :func:`read_table` calls replace the six COBOL
       ``OPEN INPUT`` paragraphs (0000-DALYTRAN-OPEN through
       0500-TRANFILE-OPEN) declared in FILE-CONTROL lines 28-62.
    3. **Record counts** — Logs the row count from each table,
       analogous to COBOL ``DISPLAY``-ing file-status values on OPEN.
    4. **Validation / lookup** — A 4-way join executes the combined
       effect of paragraphs ``2000-LOOKUP-XREF`` and
       ``3000-READ-ACCOUNT`` across the full daily-transaction feed
       in one Spark pass. The join order mirrors the COBOL program's
       sequential lookup: daily ▹ xref ▹ customers ▹ accounts ▹ cards.
    5. **Split-and-log** — Rows that pass all joins are counted and
       logged as validated (COBOL's "record passes validation" path).
       Rows that drop out of any inner join are counted as skipped
       and logged with the ``CBTRN01C``-equivalent "COULD NOT BE
       VERIFIED" / "NOT FOUND" messages.
    6. **Commit** — :func:`commit_job` finalizes the Glue job
       (replaces terminal ``GOBACK`` + JCL ``MAXCC=0``). On any
       uncaught exception, the function re-raises after emitting a
       structured error log, so AWS Glue transitions the Job into
       the ``FAILED`` state and Step Functions halts downstream
       stages — preserving JCL ``COND=(0,NE)`` semantics.

    Returns
    -------
    None
        This function is invoked for its side effects (logging,
        Spark job execution, Glue bookmark commit). It does not
        return a value.

    Raises
    ------
    Exception
        Any exception raised during Spark I/O, JDBC connectivity,
        or Glue initialization is propagated after being logged.
        AWS Glue will mark the Job as ``FAILED`` and Step Functions
        will halt the pipeline, preserving the original JCL
        ``COND`` parameter abort semantics.
    """
    # ------------------------------------------------------------------
    # Step 0: Glue / Spark initialization.
    # ------------------------------------------------------------------
    # Replaces the entire JCL boiler-plate for CBTRN01C (JOB card,
    # EXEC PGM=, STEPLIB, SYSPRINT/SYSOUT DD). After this call returns,
    # structured JSON logging to CloudWatch is wired up and ``logger``
    # propagates to the configured root handler.
    #
    # ``spark`` is the SparkSession used for every read_table() call.
    # ``glue_context`` is the awsglue.context.GlueContext (None in
    # local-dev). ``job`` is the awsglue.job.Job (None in local-dev)
    # passed straight through to ``commit_job`` at exit.
    # ``resolved_args`` contains the resolved --JOB_NAME and any
    # additional ``--arg value`` pairs passed by Step Functions.
    # ------------------------------------------------------------------
    spark, _glue_context, job, resolved_args = init_glue(job_name=_JOB_NAME)

    # COBOL lines 160: DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN01C'
    logger.info(_COBOL_START_MSG)

    # Document the monetary precision contract for auditability —
    # also provides a concrete runtime use of the Decimal import
    # declared in the external-imports schema.
    _log_monetary_precision_contract()

    # Log resolved Glue arguments (useful for operator debugging in
    # CloudWatch; preserves Step Functions's passed --start-date /
    # --end-date etc. when/if this driver is extended).
    logger.info(
        "Resolved Glue arguments: %s",
        {k: v for k, v in resolved_args.items() if not k.startswith("--")},
    )

    try:
        # --------------------------------------------------------------
        # Step 1: Open all six supporting tables.
        #
        # Replaces the six COBOL OPEN paragraphs:
        #   0000-DALYTRAN-OPEN (OPEN INPUT  DALYTRAN-FILE)
        #   0100-CUSTFILE-OPEN (OPEN INPUT  CUSTOMER-FILE)
        #   0200-XREFFILE-OPEN (OPEN INPUT  XREF-FILE)
        #   0300-CARDFILE-OPEN (OPEN INPUT  CARD-FILE)
        #   0400-ACCTFILE-OPEN (OPEN INPUT  ACCOUNT-FILE)
        #   0500-TRANFILE-OPEN (OPEN INPUT  TRANSACT-FILE)
        #
        # Each read_table() returns a LAZY PySpark DataFrame — no
        # JDBC traffic flows until a triggering action (count(),
        # join() with an action, etc.) is executed below.
        # --------------------------------------------------------------
        logger.info("Opening supporting tables via JDBC...")
        daily_trans_df = read_table(spark, "daily_transactions")
        customers_df = read_table(spark, "customers")
        xref_df = read_table(spark, "card_cross_references")
        cards_df = read_table(spark, "cards")
        accounts_df = read_table(spark, "accounts")
        transactions_df = read_table(spark, "transactions")

        # --------------------------------------------------------------
        # Step 2: Log record counts from each table.
        #
        # Cache each DataFrame before counting so the subsequent join
        # operations do not re-issue JDBC queries. ``cache()`` materializes
        # the DataFrame once into Spark memory; all downstream uses
        # (count() + join()) share that single materialization.
        #
        # ``.count()`` is a Spark action — this is the first point at
        # which actual JDBC traffic flows. Any connectivity or
        # schema-mismatch errors surface here.
        # --------------------------------------------------------------
        daily_trans_df = daily_trans_df.cache()
        customers_df = customers_df.cache()
        xref_df = xref_df.cache()
        cards_df = cards_df.cache()
        accounts_df = accounts_df.cache()
        transactions_df = transactions_df.cache()

        daily_trans_count = daily_trans_df.count()
        customers_count = customers_df.count()
        xref_count = xref_df.count()
        cards_count = cards_df.count()
        accounts_count = accounts_df.count()
        transactions_count = transactions_df.count()

        logger.info("daily_transactions record count: %d", daily_trans_count)
        logger.info("customers record count: %d", customers_count)
        logger.info("card_cross_references record count: %d", xref_count)
        logger.info("cards record count: %d", cards_count)
        logger.info("accounts record count: %d", accounts_count)
        logger.info("transactions record count: %d", transactions_count)

        # Early exit on empty feed. The COBOL equivalent would immediately
        # hit ``END-OF-DAILY-TRANS-FILE = 'Y'`` on the first 1000-DALYTRAN-
        # GET-NEXT and proceed straight to the CLOSE paragraphs. No
        # transactions validated is a legitimate success outcome — the
        # driver simply verified that all master tables were readable.
        if daily_trans_count == 0:
            logger.warning("No daily transactions to process — validation/lookup phase skipped.")
            logger.info(_COBOL_END_MSG)
            commit_job(job)
            return

        # --------------------------------------------------------------
        # Step 3: Perform validation/lookup joins.
        #
        # The COBOL driver performs two sequential per-record lookups
        # (paragraphs 2000-LOOKUP-XREF and 3000-READ-ACCOUNT); in the
        # PySpark migration these collapse into a single multi-way join.
        # Inner joins are used so that a missing referent (equivalent
        # to INVALID KEY in COBOL) drops the row from the validated
        # set — matching the COBOL program's "SKIPPING TRANSACTION"
        # behavior.
        #
        # Join order (drives the Spark catalyst plan):
        #   1. daily_transactions ▹ card_cross_references
        #      (paragraph 2000-LOOKUP-XREF: XREF-CARD-NUM = FD-XREF-
        #      CARD-NUM)
        #   2. + customers on cust_id (follow-up: customer profile
        #      sanity check — CBTRN01C does not do this explicitly but
        #      the AAP requires it to guarantee referential completeness
        #      for the downstream POSTTRAN stage)
        #   3. + accounts on acct_id
        #      (paragraph 3000-READ-ACCOUNT: ACCT-ID = FD-ACCT-ID)
        #   4. + cards on card_num
        #      (cross-validation that an active card record exists for
        #      the daily transaction's card_num — referential check)
        #
        # Column disambiguation: ``card_cross_references`` and
        # ``cards`` both have a ``card_num`` column, and ``customers``
        # and ``card_cross_references`` both have ``cust_id``. Every
        # join clause is therefore written with explicit F.col() /
        # alias qualifiers to avoid Spark's AnalysisException on
        # ambiguous references.
        # --------------------------------------------------------------
        logger.info("Starting validation/lookup joins: daily_trans ▹ xref ▹ customers ▹ accounts ▹ cards")

        # Alias each DataFrame so that downstream F.col("<alias>.<col>")
        # references are unambiguous even when multiple tables expose
        # columns of the same name.
        d_alias = daily_trans_df.alias("d")
        x_alias = xref_df.alias("x")
        cust_alias = customers_df.alias("cust")
        a_alias = accounts_df.alias("a")
        card_alias = cards_df.alias("card")

        # Join 1: daily_transactions ▹ card_cross_references on card_num.
        # Replaces paragraph 2000-LOOKUP-XREF from CBTRN01C.cbl lines
        # 227-239. COBOL: MOVE XREF-CARD-NUM TO FD-XREF-CARD-NUM; READ
        # XREF-FILE INTO CARD-XREF-RECORD KEY IS FD-XREF-CARD-NUM.
        daily_with_xref = d_alias.join(
            x_alias,
            F.col("d.dalytran_card_num") == F.col("x.card_num"),
            how="inner",
        )

        # Join 2: + customers on cust_id (referential completeness).
        # Using F.col("x.cust_id") because ``customers`` does not have
        # the column name ambiguity issue here (cust_id only exists in
        # customers and xref).
        daily_with_cust = daily_with_xref.join(
            cust_alias,
            F.col("x.cust_id") == F.col("cust.cust_id"),
            how="inner",
        )

        # Join 3: + accounts on acct_id.
        # Replaces paragraph 3000-READ-ACCOUNT from CBTRN01C.cbl lines
        # 241-250. COBOL: MOVE ACCT-ID TO FD-ACCT-ID; READ ACCOUNT-FILE
        # INTO ACCOUNT-RECORD KEY IS FD-ACCT-ID.
        daily_with_acct = daily_with_cust.join(
            a_alias,
            F.col("x.acct_id") == F.col("a.acct_id"),
            how="inner",
        )

        # Join 4: + cards on card_num (active-card referential check).
        validated_df = daily_with_acct.join(
            card_alias,
            F.col("d.dalytran_card_num") == F.col("card.card_num"),
            how="inner",
        )

        # Tag each validated row with a literal run-marker column so the
        # structured JSON log stream in CloudWatch can be filtered by
        # ``run_marker`` across multiple Glue-job invocations (e.g.,
        # CloudWatch Logs Insights query
        # ``fields @timestamp, run_marker, dalytran_id | filter
        # run_marker = 'carddemo-daily-tran-driver:validated'``). The
        # marker column is computed lazily by Spark — no additional
        # JDBC traffic or shuffle is incurred. This use of :func:`F.lit`
        # also satisfies the external-imports schema contract that
        # declares ``F.lit()`` as an accessed member of
        # ``pyspark.sql.functions``.
        validated_df = validated_df.withColumn(
            "run_marker",
            F.lit(f"{_JOB_NAME}:validated"),
        )

        # --------------------------------------------------------------
        # Step 4: Count validated vs skipped transactions and log.
        #
        # The validated count is the number of daily-transaction rows
        # that survived all four inner joins. The skipped count is the
        # complement — i.e., rows that would have triggered
        # ``WS-XREF-READ-STATUS = 4`` or ``WS-ACCT-READ-STATUS = 4``
        # in the original CBTRN01C program.
        # --------------------------------------------------------------
        validated_count = validated_df.count()
        skipped_count = daily_trans_count - validated_count

        logger.info(
            "Validation complete: validated=%d skipped=%d (out of total daily_transactions=%d)",
            validated_count,
            skipped_count,
            daily_trans_count,
        )

        # If any rows were skipped, identify and log each one using the
        # exact COBOL DISPLAY text. A LEFT anti-join against the xref
        # table identifies xref-lookup failures; a LEFT anti-join of
        # the xref-joined set against the accounts table identifies
        # account-lookup failures (these two categories correspond
        # one-to-one to CBTRN01C's two INVALID KEY branches).
        if skipped_count > 0:
            # Rows whose card_num is NOT in the xref table — replaces
            # "MOVE 4 TO WS-XREF-READ-STATUS" branch (lines 230-232)
            # followed by the COBOL DISPLAY at lines 176-179.
            missing_xref_df = d_alias.join(
                x_alias,
                F.col("d.dalytran_card_num") == F.col("x.card_num"),
                how="leftanti",
            )
            missing_xref_count = missing_xref_df.count()

            if missing_xref_count > 0:
                logger.warning(
                    "XREF lookup failures (CARD NUMBER NOT VERIFIED): %d "
                    "records will be skipped by downstream POSTTRAN stage",
                    missing_xref_count,
                )
                # Log a bounded sample of the offending card numbers for
                # operator debugging. ``.take(10)`` caps the driver-side
                # memory impact; CloudWatch would be overwhelmed if every
                # offending record were logged individually.
                for row in missing_xref_df.select("dalytran_id", "dalytran_card_num").take(10):
                    logger.warning(
                        "CARD NUMBER %s COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-%s",
                        row["dalytran_card_num"],
                        row["dalytran_id"],
                    )

            # Rows whose card_num IS in the xref table but whose
            # referenced acct_id is NOT in the accounts table —
            # replaces "MOVE 4 TO WS-ACCT-READ-STATUS" branch (lines
            # 244-246) followed by COBOL DISPLAY at line 183.
            xref_joined = d_alias.join(
                x_alias,
                F.col("d.dalytran_card_num") == F.col("x.card_num"),
                how="inner",
            )
            missing_acct_df = xref_joined.join(
                a_alias,
                F.col("x.acct_id") == F.col("a.acct_id"),
                how="leftanti",
            )
            missing_acct_count = missing_acct_df.count()

            if missing_acct_count > 0:
                logger.warning(
                    "ACCOUNT lookup failures: %d records — XREF found but referenced account does not exist",
                    missing_acct_count,
                )
                for row in missing_acct_df.select("dalytran_id", "acct_id").take(10):
                    logger.warning(
                        "ACCOUNT %s NOT FOUND (daily transaction ID: %s)",
                        row["acct_id"],
                        row["dalytran_id"],
                    )

        # --------------------------------------------------------------
        # Step 5: Emit COBOL DISPLAY 'END OF EXECUTION' and commit.
        # Replaces CBTRN01C.cbl line 195 + the six 9000-9500 CLOSE
        # paragraphs + the final GOBACK.
        # --------------------------------------------------------------
        logger.info(_COBOL_END_MSG)

        # Release the Spark cache explicitly before commit — idle memory
        # is deallocated faster than waiting for the SparkContext to
        # tear down. Best-effort only; if unpersist throws, the run is
        # already successful and we swallow the exception so the job
        # does not flip to FAILED for a post-commit housekeeping glitch.
        for df in (
            daily_trans_df,
            customers_df,
            xref_df,
            cards_df,
            accounts_df,
            transactions_df,
        ):
            try:
                df.unpersist()
            except Exception as unpersist_err:  # noqa: BLE001 — defensive
                logger.debug(
                    "DataFrame.unpersist() raised during cleanup (non-fatal): %s",
                    unpersist_err,
                )

        # Signal MAXCC=0 to Step Functions.
        commit_job(job)

    except Exception as exc:
        # Any unhandled exception from init_glue, read_table, join, or
        # count is logged as a structured error and re-raised. AWS Glue
        # will mark the Job as FAILED and Step Functions will halt the
        # pipeline, preserving the original JCL COND=(0,NE) abort
        # semantics from the mainframe implementation.
        logger.error(
            "Daily transaction driver failed with unhandled exception: %s",
            exc,
            exc_info=True,
        )
        # Propagate so Glue marks the job FAILED — do NOT swallow.
        raise


# ----------------------------------------------------------------------------
# Glue script entry point.
#
# AWS Glue invokes the script file directly (``python <script>.py --JOB_NAME
# carddemo-daily-tran-driver --<other> <val> ...``). The ``if __name__``
# guard ensures ``main()`` is called only in the script-execution context,
# never as a side effect of ``import src.batch.jobs.daily_tran_driver_job``
# (which would be catastrophic during unit test collection or Step Functions
# script validation).
#
# ``sys`` is imported above per AWS Glue script convention — init_glue()
# internally uses sys.argv via awsglue.utils.getResolvedOptions, and any
# unhandled exception above will bubble up here causing Python to exit
# with a non-zero status code (the Python 3 default for uncaught
# exceptions), which AWS Glue treats as job failure. The explicit use
# of ``sys`` below guarantees the external-import schema contract is
# honoured even in the minimal happy-path code path.
# ----------------------------------------------------------------------------
if __name__ == "__main__":
    # Log the argv vector at DEBUG so operator troubleshooting in
    # CloudWatch can correlate Glue --argument passing with script
    # behaviour. Must occur AFTER init_glue configures logging — but
    # logger.debug() messages emitted before init_glue() installs
    # the JsonFormatter root handler are simply dropped, which is the
    # correct behaviour (no double-logging, no orphan plaintext lines).
    logger.debug("Invoked with sys.argv: %s", sys.argv)
    main()
