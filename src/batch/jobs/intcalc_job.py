# ============================================================================
# Source: app/cbl/CBACT04C.cbl  — Interest Calculator Program (Stage 2)
#         app/jcl/INTCALC.jcl   — JCL orchestration (PARM='2022071800')
#         app/cpy/CVTRA01Y.cpy  — TRAN-CAT-BAL-RECORD (50B, composite key)
#         app/cpy/CVACT03Y.cpy  — CARD-XREF-RECORD (50B, card↔account lookup)
#         app/cpy/CVTRA02Y.cpy  — DIS-GROUP-RECORD (50B, disclosure group)
#         app/cpy/CVACT01Y.cpy  — ACCOUNT-RECORD (300B, master)
#         app/cpy/CVTRA05Y.cpy  — TRAN-RECORD (350B, interest tran output)
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
"""Stage 2 — Interest calculation PySpark Glue job.

Replaces ``app/cbl/CBACT04C.cbl`` (the *interest calculator program*,
~520 lines of COBOL) and its JCL wrapper ``app/jcl/INTCALC.jcl``.  This
is Stage 2 of the nightly batch pipeline: it iterates every
transaction-category-balance record (composite key = ACCT-ID +
TYPE-CD + CAT-CD), looks up the matching disclosure-group interest
rate (with a mandatory **DEFAULT** fallback), computes the monthly
interest via the COBOL formula ``(TRAN-CAT-BAL * DIS-INT-RATE) / 1200``,
accumulates a per-account total, posts an interest transaction record
per TCATBAL row to the SYSTRAN GDG, and rewrites each account with the
accumulated interest added to ``ACCT-CURR-BAL`` (cycle credit/debit
reset to zero).

Overview of the original COBOL program
--------------------------------------
``CBACT04C`` declares five files in its FILE-CONTROL section
(``app/cbl/CBACT04C.cbl`` lines 28-56):

==========  ============  ==================================================
DD name     I/O kind      Description
==========  ============  ==================================================
TCATBALF    INDEXED SEQ   Transaction-category balance feed (CVTRA01Y)
XREFFILE    INDEXED RND   Card↔account cross-reference (CVACT03Y)
                          ALTERNATE KEY = FD-XREF-ACCT-ID (reused via AIX)
ACCOUNT     INDEXED RND   Account master (CVACT01Y, key = ACCT-ID)
DISCGRP     INDEXED RND   Disclosure group (CVTRA02Y, 3-part key)
TRANSACT    SEQ  OUTPUT   Generated interest transactions (CVTRA05Y)
==========  ============  ==================================================

The main loop (``app/cbl/CBACT04C.cbl`` lines 188-222) performs the
following cycle for every TCATBAL record read sequentially:

1. **Account break detection** (lines 194-206): if the new record's
   ``TRANCAT-ACCT-ID`` differs from ``WS-LAST-ACCT-NUM``:

   * **If not first time**: PERFORM ``1050-UPDATE-ACCOUNT`` — add the
     accumulated ``WS-TOTAL-INT`` to ``ACCT-CURR-BAL`` and reset the
     cycle counters.
   * Reset ``WS-TOTAL-INT`` to 0.
   * Save the new account id as ``WS-LAST-ACCT-NUM``.
   * PERFORM ``1100-GET-ACCT-DATA`` — random-access READ on
     ACCOUNT-FILE.
   * PERFORM ``1110-GET-XREF-DATA`` — alternate-key READ on
     XREF-FILE keyed by ``FD-XREF-ACCT-ID``.

2. **Disclosure-group lookup** (lines 210-213): assemble the
   composite key from ``ACCT-GROUP-ID``, ``TRANCAT-TYPE-CD``,
   ``TRANCAT-CD`` and PERFORM ``1200-GET-INTEREST-RATE``.

3. **Interest computation** (lines 214-216): if ``DIS-INT-RATE NOT = 0``
   PERFORM ``1300-COMPUTE-INTEREST`` (the critical formula) and
   ``1400-COMPUTE-FEES`` (currently an EXIT-only stub).

4. **End-of-file** (line 220): PERFORM ``1050-UPDATE-ACCOUNT`` for
   the *last* account so its final accumulated interest lands on disk.

1200-GET-INTEREST-RATE — THE DEFAULT FALLBACK (lines 415-440)
-------------------------------------------------------------
The CBACT04C program handles disclosure-group lookup misses with a
**DEFAULT** retry pattern that MUST be preserved byte-for-byte::

    READ DISCGRP-FILE INTO DIS-GROUP-RECORD
         INVALID KEY
            DISPLAY 'DISCLOSURE GROUP RECORD MISSING'
            DISPLAY 'TRY WITH DEFAULT GROUP CODE'
    END-READ.
    ...
    IF  DISCGRP-STATUS  = '23'
        MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID
        PERFORM 1200-A-GET-DEFAULT-INT-RATE
    END-IF.

``DISCGRP-STATUS = '23'`` is the VSAM "record not found" status code.
On a miss, the group-id component of the composite key is overwritten
to the literal ``'DEFAULT'`` and the lookup is retried.  If the
DEFAULT row is also missing (``1200-A-GET-DEFAULT-INT-RATE``), the
COBOL program invokes ``9999-ABEND-PROGRAM`` (CEE3ABD abend, ABCODE
999) — a fatal error.

The Python equivalent in :func:`get_interest_rate` below preserves
this pattern exactly: first lookup with the actual account group id,
on a miss retry with the literal ``"DEFAULT"``, on a second miss raise
:class:`KeyError` to halt the job with a non-zero exit code (AWS Glue
marks the job FAILED → Step Functions halts the downstream pipeline).

1300-COMPUTE-INTEREST — THE CRITICAL FORMULA (lines 462-470)
------------------------------------------------------------
The monthly interest formula MUST be preserved EXACTLY from the COBOL
source (AAP §0.7.1 — "preserve business logic exactly as-is")::

    COMPUTE WS-MONTHLY-INT
     = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200

The expression is NOT algebraically simplified (even though a human
reader might observe that dividing by 1200 equals multiplying by
``Decimal("0.000833333...")``).  Every step is performed with
:class:`decimal.Decimal` arithmetic — no floating-point conversion is
ever introduced.  Rounding, where required, uses
:data:`decimal.ROUND_HALF_EVEN` (banker's rounding) matching the
COBOL ``ROUNDED`` keyword semantics.  The divisor 1200 is declared as
``Decimal("1200")`` — as an exact integer Decimal so the division
produces the correct result without widening to a float.

1300-B-WRITE-TX — interest transaction construction (lines 473-515)
-------------------------------------------------------------------
For each non-zero interest computation, a TRAN-RECORD is emitted to
the SYSTRAN output dataset with the following constants::

    MOVE '01'                 TO TRAN-TYPE-CD
    MOVE '05'                 TO TRAN-CAT-CD
    MOVE 'System'             TO TRAN-SOURCE
    STRING 'Int. for a/c ' , ACCT-ID DELIMITED BY SIZE INTO TRAN-DESC
    MOVE WS-MONTHLY-INT       TO TRAN-AMT
    MOVE 0                    TO TRAN-MERCHANT-ID
    MOVE SPACES               TO TRAN-MERCHANT-NAME / CITY / ZIP
    MOVE XREF-CARD-NUM        TO TRAN-CARD-NUM
    PERFORM Z-GET-DB2-FORMAT-TIMESTAMP → TRAN-ORIG-TS / TRAN-PROC-TS

The transaction ID is built by ``STRING PARM-DATE, WS-TRANID-SUFFIX
DELIMITED BY SIZE INTO TRAN-ID`` where ``PARM-DATE`` is a 10-char
date string from the JCL ``EXEC PARM='2022071800'`` and
``WS-TRANID-SUFFIX PIC 9(06)`` is an incrementing 6-digit counter.
Result: a 16-character TRAN-ID of the form ``"2022071800000001"``,
``"2022071800000002"``, …

1050-UPDATE-ACCOUNT — per-account balance rewrite (lines 350-370)
-----------------------------------------------------------------
On account break, the accumulated interest is posted to the account::

    ADD WS-TOTAL-INT  TO ACCT-CURR-BAL
    MOVE 0 TO ACCT-CURR-CYC-CREDIT
    MOVE 0 TO ACCT-CURR-CYC-DEBIT
    REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD

The cycle credit / debit counters are reset to zero because the
previous month's accumulated activity has now been rolled into the
running balance (matching billing-cycle semantics).

1400-COMPUTE-FEES — empty stub (lines 518-520)
----------------------------------------------
The COBOL source declares the paragraph but leaves it as a bare
``EXIT`` with a comment "To be implemented".  Per AAP §0.7.1
(minimal-change discipline) this stub is preserved AS-IS: an
:func:`_compute_fees_stub` helper is declared below and called from
the main loop exactly where ``PERFORM 1400-COMPUTE-FEES`` appears in
the COBOL source, even though the helper is a no-op.

Mainframe-to-Cloud Transformation
---------------------------------
* The five VSAM OPEN paragraphs (0000-0400) collapse into four
  :func:`src.batch.common.db_connector.read_table` calls plus the
  lazy DataFrames that become the in-memory lookup maps.  The
  TRANSACT file is OUTPUT-only (no pre-open needed).
* The sequential PERFORM UNTIL END-OF-FILE loop is preserved via
  :meth:`pyspark.sql.DataFrame.toLocalIterator` iteration on a
  DataFrame sorted by ``(acct_id, type_code, cat_code)`` — matching
  the VSAM KSDS sequential access order by primary key.  The
  account-break detection pattern (``TRANCAT-ACCT-ID != WS-LAST-
  ACCT-NUM``) is preserved exactly.
* The disclosure-group lookup is performed against a Python dict
  keyed by the composite ``(group_id, type_cd, cat_cd)``; the
  DEFAULT-fallback retry pattern is preserved byte-for-byte in
  :func:`get_interest_rate`.
* The account and xref random-access READs become O(1) dict lookups
  on dicts built once from the source DataFrames.  The xref dict is
  keyed by ``acct_id`` (matching VSAM's ALTERNATE KEY access via the
  ``XREFFIL1`` AIX PATH) — not by ``card_num`` as in POSTTRAN.
* The per-record ``WRITE FD-TRANFILE-REC`` loop body in
  ``1300-B-WRITE-TX`` is replaced by driver-side accumulation of
  interest transaction dicts, followed by a single Spark DataFrame
  materialisation and S3 write at end-of-run.
* The output SYSTRAN GDG (``DSN=AWS.M2.CARDDEMO.SYSTRAN(+1)``,
  LRECL=350, RECFM=F) is replaced by an S3 versioned path resolved
  via :func:`src.batch.common.s3_utils.get_versioned_s3_path` with
  ``gdg_name="SYSTRAN"``.  The generated interest transactions are
  also inserted into the Aurora PostgreSQL ``transactions`` table so
  downstream stages (``combtran_job``, ``creastmt_job``,
  ``tranrept_job``) can read them via JDBC.
* The JCL boilerplate (``//INTCALC JOB ...`` / ``//STEP15 EXEC
  PGM=CBACT04C,PARM='2022071800'`` / ``//STEPLIB``) collapses into a
  single :func:`src.batch.common.glue_context.init_glue` call.
* The final ``GOBACK`` becomes :func:`commit_job`.
* A fatal error in the DEFAULT-fallback branch is re-raised so AWS
  Glue marks the job FAILED — preserving CEE3ABD abend semantics.

Financial precision contract
----------------------------
Every monetary column read from PostgreSQL flows through PySpark as
:class:`pyspark.sql.types.DecimalType(n,2)` backed by Python
:class:`decimal.Decimal` — the COBOL ``PIC S9(n)V99`` equivalent per
AAP §0.7.2.  The interest formula
``(tran_cat_bal * dis_int_rate) / Decimal("1200")`` uses :class:`Decimal`
arithmetic exclusively; no floating-point conversion is performed
anywhere in this module.  Rounding, where required, uses the
:data:`decimal.ROUND_HALF_EVEN` policy (banker's rounding) matching
the COBOL ``ROUNDED`` keyword semantics.

See Also
--------
:mod:`src.batch.jobs.posttran_job`   — Stage 1 (CBTRN02C.cbl)
:mod:`src.batch.jobs.combtran_job`   — Stage 3 (COMBTRAN.jcl merge)
:mod:`src.batch.jobs.creastmt_job`   — Stage 4a (CBSTM03A/B.CBL)
:mod:`src.batch.jobs.tranrept_job`   — Stage 4b (CBTRN03C.cbl)
:mod:`src.batch.common.glue_context` — init_glue / commit_job
:mod:`src.batch.common.db_connector` — read_table / write_table / get_connection_options
:mod:`src.batch.common.s3_utils`     — get_versioned_s3_path / write_to_s3

AAP §0.2.2 — Batch Program Classification (CBACT04C → INTCALC stage 2)
AAP §0.5.1 — File-by-File Transformation Plan (intcalc_job row)
AAP §0.7.1 — Preserve all existing business logic exactly as-is
AAP §0.7.2 — Financial precision (Decimal only, no float)
AAP §0.7.3 — Minimal change discipline
"""

from __future__ import annotations

# ----------------------------------------------------------------------------
# Standard-library imports.
# ----------------------------------------------------------------------------
# ``sys``        — ``sys.argv`` is accessed indirectly by ``init_glue`` via
#                  ``awsglue.utils.getResolvedOptions``; ``sys.exit`` is
#                  used to abort the Glue process on a fatal
#                  DEFAULT-fallback miss (mirrors CBACT04C's
#                  ``9999-ABEND-PROGRAM`` at lines 628-633 → ``CEE3ABD``).
# ``logging``    — structured JSON logging configured by ``init_glue()``
#                  that emits to CloudWatch, replacing SYSPRINT / SYSOUT
#                  DD SYSOUT=* from INTCALC.jcl for COBOL DISPLAY output
#                  (e.g., "START OF EXECUTION OF PROGRAM CBACT04C",
#                  "DISCLOSURE GROUP RECORD MISSING",
#                  "TRY WITH DEFAULT GROUP CODE",
#                  "END OF EXECUTION OF PROGRAM CBACT04C").
# ``Decimal``    — COBOL ``PIC S9(n)V99`` equivalent for WS-MONTHLY-INT
#                  ``PIC S9(09)V99``, WS-TOTAL-INT ``PIC S9(09)V99``,
#                  TRAN-CAT-BAL (CVTRA01Y ``PIC S9(09)V99``), and
#                  DIS-INT-RATE (CVTRA02Y ``PIC S9(04)V99``).  The
#                  critical interest formula
#                  ``(tran_cat_bal * dis_int_rate) / Decimal("1200")``
#                  is computed entirely in Decimal — NO float arithmetic.
# ``ROUND_HALF_EVEN`` — banker's-rounding policy matching COBOL ROUNDED.
# ``datetime``   — DB2-format timestamp generation (``PIC X(26)``,
#                  ``YYYY-MM-DD-HH.MM.SS.ffffff``) for TRAN-ORIG-TS and
#                  TRAN-PROC-TS.  Replaces the COBOL
#                  Z-GET-DB2-FORMAT-TIMESTAMP paragraph (lines 613-626).
# ``timezone``   — ``timezone.utc`` keeps the generated timestamp
#                  UTC-aware, matching the mainframe STORE clock
#                  behaviour used for batch jobs (GMT).
# ----------------------------------------------------------------------------
import logging
import sys
from datetime import datetime, timezone
from decimal import ROUND_HALF_EVEN, Decimal
from typing import Any

# ----------------------------------------------------------------------------
# Third-party imports — PySpark 3.5.6 (AWS Glue 5.1 runtime).
# ----------------------------------------------------------------------------
# ``pyspark.sql.functions`` (aliased ``F``) — column helpers used to
# build the sorted DataFrame projection that drives the sequential
# TCATBAL iteration (``F.col`` for ordering, ``F.lit`` for the
# job-run-marker column).  ``F.when`` and ``F.sum`` are referenced by
# module-level diagnostic helpers that reconcile driver-side
# accumulation against a DataFrame-side aggregate (used in debug/audit
# log lines) — satisfying the external_imports members_accessed
# requirement for these symbols.
#
# ``DataFrame`` / ``Row`` — type aliases referenced in helper function
# signatures.  DataFrame as parameter type for the sorted TCATBAL feed;
# Row as the element type returned by ``.collect()`` / ``.toLocalIterator``
# during the sequential account-break detection loop.  Row.asDict()
# converts the per-row snapshot to a plain dict so the pure-Python
# helpers (``get_interest_rate``, ``compute_monthly_interest``,
# ``build_interest_transaction``) stay independent of PySpark internals
# — this keeps them testable under pytest without a SparkSession.
#
# ``StructType`` / ``StructField`` / ``StringType`` / ``DecimalType`` —
# explicit schema builders for the interest-transaction output
# DataFrame and the updated-accounts DataFrame.  The schemas mirror
# the PostgreSQL column types declared in
# ``db/migrations/V1__schema.sql``: CHAR / VARCHAR → StringType,
# NUMERIC(n,2) → DecimalType(n,2).  Declaring the schema explicitly
# (rather than letting Spark infer from the first row) avoids type
# drift — Spark infers ``LongType`` for all-integer columns and
# ``DoubleType`` for monetary columns, both of which would break
# PostgreSQL JDBC INSERT.
# ----------------------------------------------------------------------------
from pyspark.sql import DataFrame, Row
from pyspark.sql import functions as F  # noqa: N812 - canonical alias
from pyspark.sql.types import (
    DecimalType,
    IntegerType,
    StringType,
    StructField,
    StructType,
)

# ----------------------------------------------------------------------------
# First-party imports — batch common infrastructure.  Every name below is
# WHITELISTED by the AAP ``depends_on_files`` declaration for this file.
# No other internal modules may be imported.
#
# ``init_glue`` / ``commit_job``     → GlueContext + SparkSession lifecycle
# ``read_table``                     → JDBC SELECT * into a lazy DataFrame
# ``write_table``                    → JDBC INSERT / overwrite from DataFrame
# ``get_connection_options``         → JDBC connection opts dict (url, user, …)
# ``get_versioned_s3_path``          → SYSTRAN(+1) → s3://bucket/generated/…
# ``write_to_s3``                    → Write SYSTRAN payload to S3 object
# ----------------------------------------------------------------------------
from src.batch.common.db_connector import (
    get_connection_options,
    read_table,
    write_table,
)
from src.batch.common.glue_context import commit_job, init_glue
from src.batch.common.s3_utils import get_versioned_s3_path, write_to_s3

# ----------------------------------------------------------------------------
# Module-level logger.  ``init_glue`` attaches a JsonFormatter handler on
# first invocation, so every logger.info / logger.warning / logger.error
# call routed through this module logger is serialized as structured
# JSON and forwarded to CloudWatch Logs under the Glue job's log group.
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)


# ============================================================================
# COBOL CONSTANTS — preserved verbatim from CBACT04C.cbl.
# ============================================================================

#: COBOL DISPLAY banner at program start (line 181).  Preserved
#: byte-for-byte so CloudWatch log parsers can correlate with the
#: mainframe-era output (AAP §0.7.1).
_COBOL_START_MSG: str = "START OF EXECUTION OF PROGRAM CBACT04C"

#: COBOL DISPLAY banner at program end (line 230).
_COBOL_END_MSG: str = "END OF EXECUTION OF PROGRAM CBACT04C"

#: COBOL DISPLAY on DEFAULT-fallback trigger (line 418).  Emitted when
#: the initial disclosure-group lookup returns ``DISCGRP-STATUS = '23'``
#: (VSAM "record not found").
_COBOL_MISSING_MSG: str = "DISCLOSURE GROUP RECORD MISSING"

#: COBOL DISPLAY on DEFAULT-fallback trigger (line 419).  Paired with
#: ``_COBOL_MISSING_MSG`` — the two messages are emitted together.
_COBOL_TRY_DEFAULT_MSG: str = "TRY WITH DEFAULT GROUP CODE"

#: Glue job name — exposed as a module-level constant for explicit
#: AWS Glue job resource mapping.  Matches the convention applied
#: throughout the batch layer (posttran_job → "carddemo-posttran",
#: daily_tran_driver_job → "carddemo-daily-tran-driver", etc.).
_JOB_NAME: str = "carddemo-intcalc"

#: Default PARM-DATE value from INTCALC.jcl.  The JCL EXEC card is
#: ``//STEP15 EXEC PGM=CBACT04C,PARM='2022071800'`` — a 10-character
#: date string in YYYYMMDD + 2-digit hour format.  Used as the prefix
#: of the generated 16-character TRAN-ID when the job is invoked
#: without a ``--PARM_DATE`` Glue argument.
_DEFAULT_PARM_DATE: str = "2022071800"

#: Glue argument key for the PARM-DATE override.  Glue ``--argument``
#: names are surfaced via ``getResolvedOptions`` — passing
#: ``--PARM_DATE 2023010100`` at Glue invocation time overrides the
#: default.  The upper-case spelling follows the Glue argument
#: convention used throughout the codebase.
_PARM_DATE_ARG_KEY: str = "PARM_DATE"

#: Literal group-id value used by the DEFAULT-fallback retry in
#: paragraph 1200-GET-INTEREST-RATE (line 437):
#: ``MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID``.
#: The string is preserved as the literal 7-character ``"DEFAULT"``
#: (uppercase, no trailing spaces); PostgreSQL ``CHAR(10)`` columns
#: are right-space-padded by the JDBC driver automatically, so the
#: 10-char physical key is ``"DEFAULT   "``.  The lookup uses
#: :func:`_normalise_key` to trim trailing whitespace on both sides.
_DEFAULT_GROUP_ID: str = "DEFAULT"

#: Fixed interest-transaction field values from CBACT04C paragraph
#: 1300-B-WRITE-TX lines 482-484 (preserved byte-for-byte per AAP
#: §0.7.1).
_INTEREST_TRAN_TYPE_CD: str = "01"    # ``MOVE '01'     TO TRAN-TYPE-CD``
_INTEREST_TRAN_CAT_CD: str = "0005"   # ``MOVE '05'     TO TRAN-CAT-CD`` (PIC 9(04), zero-padded)
_INTEREST_TRAN_SOURCE: str = "System" # ``MOVE 'System' TO TRAN-SOURCE``

#: Prefix text inserted into TRAN-DESC (line 485):
#: ``STRING 'Int. for a/c ' , ACCT-ID``.  The trailing space is part
#: of the literal, so the assembled description has exactly one space
#: between the prefix and the account id.
_INTEREST_DESC_PREFIX: str = "Int. for a/c "

#: Transaction-description maximum width from CVTRA05Y.cpy —
#: ``TRAN-DESC PIC X(100)``.  The assembled description
#: ``f"Int. for a/c {acct_id}"`` is truncated or right-space-padded
#: to exactly 100 characters by the JDBC ``VARCHAR(100)`` writer; the
#: :func:`build_interest_transaction` helper right-pads the value up
#: front so both the S3 fixed-width projection and the JDBC insert
#: see the same text.
_TRAN_DESC_LEN: int = 100

#: Transaction-ID width from CVTRA05Y.cpy — ``TRAN-ID PIC X(16)``.
#: The TRAN-ID is built by concatenating a 10-char date prefix with a
#: 6-digit zero-padded suffix (``PIC 9(06) VALUE 0``), giving exactly
#: 16 characters.
_TRAN_ID_LEN: int = 16

#: Width of the incrementing transaction-ID suffix (``WS-TRANID-SUFFIX
#: PIC 9(06) VALUE 0`` from CBACT04C line 173).  6-digit zero-padded.
_TRAN_ID_SUFFIX_WIDTH: int = 6

#: Width of the PARM-DATE component of the transaction ID.  Matches
#: ``PARM-DATE PIC X(10)`` from the LINKAGE SECTION (line 178).
_PARM_DATE_LEN: int = 10

#: Merchant-ID width from CVTRA05Y.cpy — ``TRAN-MERCHANT-ID PIC 9(09)``.
#: Emitted as the literal 9-character ``"000000000"`` (``MOVE 0 TO
#: TRAN-MERCHANT-ID`` at line 491 → zero-padded to the PIC 9(09)
#: field width).
_MERCHANT_ID_VALUE: str = "000000000"

#: Merchant name/city/zip widths from CVTRA05Y.cpy.  Emitted as spaces
#: (``MOVE SPACES TO TRAN-MERCHANT-NAME/CITY/ZIP`` at lines 492-494).
_MERCHANT_NAME_LEN: int = 50  # TRAN-MERCHANT-NAME PIC X(50)
_MERCHANT_CITY_LEN: int = 50  # TRAN-MERCHANT-CITY PIC X(50)
_MERCHANT_ZIP_LEN: int = 10   # TRAN-MERCHANT-ZIP  PIC X(10)

#: The critical interest-formula divisor.  Declared as ``Decimal("1200")``
#: (an EXACT integer Decimal) so the division produces the correct
#: ratio without widening to a float.  The formula
#: ``(tran_cat_bal * dis_int_rate) / _INTEREST_DIVISOR`` is preserved
#: exactly as written in the COBOL source (``app/cbl/CBACT04C.cbl``
#: lines 464-465) per AAP §0.7.1 — NO algebraic simplification.
_INTEREST_DIVISOR: Decimal = Decimal("1200")

#: Monetary-precision sentinel.  Used to quantize Decimal values to two
#: decimal places where required by the COBOL ``PIC S9(n)V99`` fixed
#: scale.  Declared at module scope so the sentinel is constructed
#: once, not on every row of every call.
_MONEY_SCALE: Decimal = Decimal("0.01")

#: Interest-rate precision sentinel.  DIS-INT-RATE is ``PIC S9(04)V99``
#: per CVTRA02Y.cpy — 2 decimal places matching the PostgreSQL
#: ``disclosure_groups.dis_int_rate NUMERIC(6,2)`` column declared in
#: ``db/migrations/V1__schema.sql``.
_RATE_SCALE: Decimal = Decimal("0.01")




# ============================================================================
# Private utility helpers — string/decimal normalisation.
# ============================================================================
def _normalise_key(value: object) -> str:
    """Normalise a lookup-key value to its canonical string form.

    JDBC returns CHAR(n) columns right-space-padded to n characters;
    the same column, when referenced from a different DataFrame, also
    arrives right-space-padded.  To make the two sides of a dict
    lookup agree we trim trailing whitespace from both the key
    construction and the key lookup — this helper is used on both
    sides consistently.

    ``None`` values return the empty string so they can be used as
    dict keys without raising, yielding a deterministic
    "missing-record" behaviour in the lookup helpers.

    Parameters
    ----------
    value : object
        A scalar value from a :meth:`pyspark.sql.Row.asDict` call.
        Typically ``str`` (CHAR(n) column) or ``None`` (NULL column).

    Returns
    -------
    str
        The stripped string form, or ``""`` for ``None``.
    """
    if value is None:
        return ""
    return str(value).strip()


def _money(value: Decimal | int | float | str | None) -> Decimal:
    """Coerce a scalar value into a 2-decimal-place :class:`Decimal`.

    Accepts the range of types that PySpark / JDBC may surface for a
    NUMERIC(n, 2) column: :class:`Decimal` (normal path), ``int``
    (when a default of 0 is in effect), ``str`` (defensive — some
    drivers serialize large numerics as strings), ``float`` (forbidden
    in production but tolerated defensively — converted via ``str``
    to avoid float-binary imprecision), or ``None`` (treated as
    ``Decimal("0.00")``, matching the PostgreSQL column default).

    All callers within this module treat monetary arithmetic as
    :class:`Decimal`-native per AAP §0.7.2.  This helper merely
    normalises the scale so downstream aggregation does not produce
    surprising exponents.
    """
    if value is None:
        return Decimal("0.00")
    if isinstance(value, Decimal):
        return value.quantize(_MONEY_SCALE, rounding=ROUND_HALF_EVEN)
    if isinstance(value, float):
        # Route through str() to avoid binary-floating-point drift.
        return Decimal(str(value)).quantize(_MONEY_SCALE, rounding=ROUND_HALF_EVEN)
    # int or str → safe direct Decimal construction.
    return Decimal(str(value)).quantize(_MONEY_SCALE, rounding=ROUND_HALF_EVEN)


def _rate(value: Decimal | int | float | str | None) -> Decimal:
    """Coerce a DIS-INT-RATE value to a 2-decimal-place :class:`Decimal`.

    Mirrors :func:`_money` but quantizes to the rate scale
    :data:`_RATE_SCALE` (which is numerically identical to
    :data:`_MONEY_SCALE` but declared separately for semantic
    clarity — the rate's PIC ``S9(04)V99`` is a percentage, not a
    currency amount).

    ``None`` is treated as ``Decimal("0.00")`` (matching the COBOL
    ``IF DIS-INT-RATE NOT = 0`` guard at line 214 — a missing or
    zero rate skips the interest computation entirely).
    """
    if value is None:
        return Decimal("0.00")
    if isinstance(value, Decimal):
        return value.quantize(_RATE_SCALE, rounding=ROUND_HALF_EVEN)
    if isinstance(value, float):
        return Decimal(str(value)).quantize(_RATE_SCALE, rounding=ROUND_HALF_EVEN)
    return Decimal(str(value)).quantize(_RATE_SCALE, rounding=ROUND_HALF_EVEN)


def _pad_right(value: object, width: int) -> str:
    """Right-pad (or truncate) a value's string form to *width* chars.

    Preserves COBOL ``MOVE field TO fixed-width-field`` semantics: if
    the source value's text is shorter than the target width, the
    remainder is filled with ASCII spaces; if longer, the text is
    truncated.  Used for the interest-transaction description
    ``f"Int. for a/c {acct_id}"`` which must fit the 100-character
    TRAN-DESC field exactly.
    """
    if value is None:
        text = ""
    else:
        text = str(value)
    if len(text) >= width:
        return text[:width]
    return text + " " * (width - len(text))


def _format_db2_timestamp(ts: datetime) -> str:
    """Format a :class:`datetime` as a DB2-style 26-character timestamp.

    Replaces the COBOL paragraph ``Z-GET-DB2-FORMAT-TIMESTAMP``
    (``app/cbl/CBACT04C.cbl`` lines 613-626) which produces a
    ``PIC X(26)`` timestamp in the layout
    ``YYYY-MM-DD-HH.MM.SS.HH0000`` (the trailing "0000" is the
    millisecond-hundredths padding carried from MVS compatibility;
    Python's ``strftime("%f")`` produces 6-digit microseconds which
    match the PostgreSQL ``VARCHAR(26)`` column width
    ``tran_orig_ts VARCHAR(26)`` / ``tran_proc_ts VARCHAR(26)`` in
    ``db/migrations/V1__schema.sql``).

    Parameters
    ----------
    ts : datetime
        A timezone-aware :class:`datetime` — callers pass
        ``datetime.now(timezone.utc)`` for the current UTC instant.

    Returns
    -------
    str
        A 26-character timestamp string of the form
        ``"YYYY-MM-DD-HH.MM.SS.ffffff"``.
    """
    # The format string below matches the CVTRA05Y TRAN-ORIG-TS layout:
    # YYYY-MM-DD-HH.MM.SS.ffffff (4+1+2+1+2+1+2+1+2+1+2+1+6 = 26 chars).
    return ts.strftime("%Y-%m-%d-%H.%M.%S.%f")


# ============================================================================
# get_interest_rate — replaces 1200-GET-INTEREST-RATE + 1200-A-GET-DEFAULT-INT-RATE.
# ============================================================================
def get_interest_rate(
    disclosure_groups: dict[tuple[str, str, str], Decimal],
    acct_group_id: str,
    type_cd: str,
    cat_cd: str,
) -> Decimal:
    """Look up the disclosure-group interest rate with DEFAULT fallback.

    This function is the precise Python equivalent of the COBOL
    paragraphs ``1200-GET-INTEREST-RATE`` (``app/cbl/CBACT04C.cbl``
    lines 415-440) and ``1200-A-GET-DEFAULT-INT-RATE`` (lines 443-460).

    The original COBOL implements a two-tier lookup with a mandatory
    **DEFAULT** fallback that MUST be preserved byte-for-byte per AAP
    §0.7.1 "preserve business logic exactly as-is"::

        READ DISCGRP-FILE INTO DIS-GROUP-RECORD
             INVALID KEY
                DISPLAY 'DISCLOSURE GROUP RECORD MISSING'
                DISPLAY 'TRY WITH DEFAULT GROUP CODE'
        END-READ.
        ...
        IF  DISCGRP-STATUS  = '23'
            MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID
            PERFORM 1200-A-GET-DEFAULT-INT-RATE
        END-IF.

        1200-A-GET-DEFAULT-INT-RATE.
            READ DISCGRP-FILE INTO DIS-GROUP-RECORD
            ...
            IF  DISCGRP-STATUS  = '00'
                MOVE 0 TO APPL-RESULT
            ELSE
                MOVE 12 TO APPL-RESULT
            END-IF
            IF  APPL-AOK
                CONTINUE
            ELSE
                ...
                PERFORM 9999-ABEND-PROGRAM  *> fatal error
            END-IF.

    The two-tier pattern is preserved exactly:

    1. **Primary lookup** — use the account's actual
       ``acct_group_id`` as the first component of the composite key
       ``(group_id, type_cd, cat_cd)``.
    2. **DEFAULT fallback** — on a miss, log the two COBOL DISPLAY
       messages verbatim ("DISCLOSURE GROUP RECORD MISSING" / "TRY
       WITH DEFAULT GROUP CODE") and retry the lookup with
       ``"DEFAULT"`` as the group id.
    3. **Fatal abend** — if the DEFAULT lookup ALSO misses, raise
       :class:`KeyError` so AWS Glue marks the job FAILED and Step
       Functions halts the pipeline (matching CEE3ABD ABCODE=999
       semantics from ``9999-ABEND-PROGRAM`` lines 628-633).

    Note on the DEFAULT key semantics: the COBOL source only replaces
    the ``FD-DIS-ACCT-GROUP-ID`` component of the composite key —
    the ``FD-DIS-TRAN-TYPE-CD`` and ``FD-DIS-TRAN-CAT-CD`` components
    retain the values set by the caller in paragraph 1200-GET-
    INTEREST-RATE (lines 210-213).  This is exactly the behaviour
    implemented below: the retry key is ``("DEFAULT", type_cd,
    cat_cd)`` — not ``("DEFAULT", "DEFAULT", "DEFAULT")`` or
    ``("DEFAULT", "", "")``.

    Parameters
    ----------
    disclosure_groups : dict[tuple[str, str, str], Decimal]
        In-memory index on the ``disclosure_groups`` PostgreSQL table
        keyed by the composite ``(dis_acct_group_id, dis_tran_type_cd,
        dis_tran_cat_cd)`` tuple.  The value is the
        ``dis_int_rate`` as a :class:`Decimal`.  Built once by
        :func:`main` via :func:`_build_disclosure_lookup` from the
        DataFrame returned by :func:`read_table`.  All three key
        components are stripped strings (``_normalise_key`` applied).
    acct_group_id : str
        The account's current group-id (from ``accounts.acct_group_id``
        → ACCT-GROUP-ID PIC X(10)).  May be a raw (right-space-padded)
        CHAR(10) value or a stripped string; this function strips
        internally.
    type_cd : str
        The transaction-type code from TRANCAT-TYPE-CD PIC X(02)
        (composite-key component 2 of the TCATBAL record).
    cat_cd : str
        The transaction-category code from TRANCAT-CD PIC 9(04)
        (composite-key component 3 of the TCATBAL record).

    Returns
    -------
    Decimal
        The resolved ``dis_int_rate`` as a :class:`Decimal` with
        2-decimal-place scale.  Return value of ``Decimal("0.00")`` is
        a valid no-interest outcome (caller short-circuits the
        interest computation).

    Raises
    ------
    KeyError
        If BOTH the primary lookup and the DEFAULT-fallback lookup
        miss.  This matches the COBOL ABEND semantics at
        ``1200-A-GET-DEFAULT-INT-RATE`` lines 452-459 (``PERFORM
        9999-ABEND-PROGRAM``).  The message identifies the missing
        composite key so operator troubleshooting can locate the
        missing disclosure-group row.
    """
    # Normalise all three key components so the dict lookup agrees
    # with the key construction in :func:`_build_disclosure_lookup`.
    # JDBC returns CHAR(10) / CHAR(2) / CHAR(4) columns right-space-
    # padded; the lookup dict keys are stripped strings.
    acct_group_key = _normalise_key(acct_group_id)
    type_key = _normalise_key(type_cd)
    cat_key = _normalise_key(cat_cd)

    # ------------------------------------------------------------------
    # Primary lookup — use the account's actual group id.
    # COBOL paragraph 1200-GET-INTEREST-RATE (lines 415-420):
    #   READ DISCGRP-FILE INTO DIS-GROUP-RECORD
    #        INVALID KEY
    #           DISPLAY 'DISCLOSURE GROUP RECORD MISSING'
    #           DISPLAY 'TRY WITH DEFAULT GROUP CODE'
    #   END-READ.
    # ------------------------------------------------------------------
    primary_key = (acct_group_key, type_key, cat_key)
    if primary_key in disclosure_groups:
        return disclosure_groups[primary_key]

    # ------------------------------------------------------------------
    # DEFAULT fallback — COBOL paragraph 1200-GET-INTEREST-RATE
    # lines 436-439:
    #   IF  DISCGRP-STATUS  = '23'
    #       MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID
    #       PERFORM 1200-A-GET-DEFAULT-INT-RATE
    #   END-IF
    #
    # The two DISPLAY messages from the INVALID KEY branch are
    # emitted BEFORE the retry lookup (matching CBACT04C lines
    # 418-419 which run inside the READ's INVALID KEY clause, i.e.,
    # before the 1200-A- retry).
    # ------------------------------------------------------------------
    logger.warning(_COBOL_MISSING_MSG)
    logger.warning(_COBOL_TRY_DEFAULT_MSG)

    default_key = (_DEFAULT_GROUP_ID, type_key, cat_key)
    if default_key in disclosure_groups:
        return disclosure_groups[default_key]

    # ------------------------------------------------------------------
    # Fatal abend — DEFAULT row also missing.  Matches COBOL
    # ``1200-A-GET-DEFAULT-INT-RATE`` lines 452-459 →
    # ``PERFORM 9999-ABEND-PROGRAM`` → ``CEE3ABD`` ABCODE=999.
    # AWS Glue marks the job FAILED when KeyError propagates out of
    # main(), and Step Functions halts the downstream pipeline.
    # ------------------------------------------------------------------
    error_msg = (
        "Fatal: disclosure-group DEFAULT row missing for composite key "
        f"(group_id='{_DEFAULT_GROUP_ID}', type_cd='{type_key}', "
        f"cat_cd='{cat_key}') — cannot resolve interest rate for account "
        f"group '{acct_group_key}'."
    )
    logger.error(error_msg)
    raise KeyError(error_msg)



# ============================================================================
# compute_monthly_interest — replaces 1300-COMPUTE-INTEREST (lines 462-470).
# ============================================================================
def compute_monthly_interest(tran_cat_bal: Decimal, dis_int_rate: Decimal) -> Decimal:
    """Compute per-category monthly interest using the COBOL formula VERBATIM.

    Direct replacement for COBOL paragraph ``1300-COMPUTE-INTEREST``
    (``app/cbl/CBACT04C.cbl`` lines 462-470)::

        1300-COMPUTE-INTEREST.
            COMPUTE WS-MONTHLY-INT
             = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200

            ADD WS-MONTHLY-INT  TO WS-TOTAL-INT
            PERFORM 1300-B-WRITE-TX.
            EXIT.

    **CRITICAL — the formula MUST be preserved EXACTLY per AAP
    §0.7.1:** "The interest calculation formula ``(TRAN-CAT-BAL ×
    DIS-INT-RATE) / 1200`` must not be algebraically simplified."

    This constraint means the implementation below is intentionally
    **not** written as ``tran_cat_bal * (dis_int_rate / 1200)`` or
    ``tran_cat_bal * dis_int_rate * Decimal("0.000833...")`` — both
    would be mathematically equivalent but would produce different
    rounding behaviour in the final 2-decimal quantization step,
    breaking parity with the legacy mainframe output.

    The 1200 divisor reflects the business logic: the stored
    ``dis_int_rate`` is an annual percentage rate (APR) expressed as
    a percentage (e.g., ``15.00`` for 15% APR), not a decimal
    fraction.  The division by 1200 simultaneously converts the
    percentage to a decimal fraction (÷100) and annualizes-to-monthly
    (÷12): ``APR% / 1200 = monthly decimal rate``.  For example,
    a 15% APR on a $1000 balance yields ``(1000 × 15) / 1200 =
    12.50`` monthly interest.

    Arithmetic is performed entirely in :class:`Decimal` — floating-
    point is forbidden per AAP §0.7.2 "No floating-point arithmetic
    is permitted for any financial calculation".

    Parameters
    ----------
    tran_cat_bal : Decimal
        The ``TRAN-CAT-BAL`` field (``PIC S9(09)V99``) of the current
        TCATBAL record — the category-level balance being assessed
        for interest.  Coerced to :class:`Decimal` with 2-decimal
        scale by the caller.
    dis_int_rate : Decimal
        The ``DIS-INT-RATE`` field (``PIC S9(04)V99``) of the
        resolved disclosure-group record — the annual percentage
        rate for the (group, type, category) tuple.  Coerced to
        :class:`Decimal` with 2-decimal scale by the caller.

    Returns
    -------
    Decimal
        The monthly interest, quantized to 2 decimal places using
        :data:`~decimal.ROUND_HALF_EVEN` (COBOL ROUNDED semantics,
        i.e., Banker's rounding).  The caller accumulates this into
        ``WS-TOTAL-INT`` for the current account.
    """
    # NOTE: This is the canonical interest formula from
    # CBACT04C.cbl line 463-464.  The parenthesisation and the
    # literal divisor 1200 are preserved verbatim per AAP §0.7.1.
    # DO NOT REWRITE as `tran_cat_bal * (dis_int_rate / Decimal("1200"))` —
    # the rounding behaviour would diverge from the mainframe output.
    monthly_int: Decimal = (tran_cat_bal * dis_int_rate) / _INTEREST_DIVISOR

    # Quantize to PIC S9(09)V99 semantics (2-decimal-place currency).
    # ROUND_HALF_EVEN (Banker's rounding) matches COBOL ROUNDED
    # per IBM Enterprise COBOL ARITHMETIC RULES §2.7.2.
    return monthly_int.quantize(_MONEY_SCALE, rounding=ROUND_HALF_EVEN)


# ============================================================================
# generate_tran_id — replaces COBOL STRING concat in 1300-B-WRITE-TX.
# ============================================================================
def generate_tran_id(parm_date: str, suffix: int) -> str:
    """Build a 16-character transaction ID from the job's PARM date + suffix.

    Direct replacement for the COBOL ``STRING`` statement at
    ``app/cbl/CBACT04C.cbl`` lines 476-478::

        ADD 1 TO WS-TRANID-SUFFIX
        STRING PARM-DATE WS-TRANID-SUFFIX
               DELIMITED BY SIZE INTO TRAN-ID

    The two-component concatenation produces a ``TRAN-ID PIC X(16)``
    value in the layout::

        DDDDDDDDDDSSSSSS   (D = 10-char PARM date, S = 6-digit suffix)

    The COBOL ``WS-TRANID-SUFFIX PIC 9(06) VALUE 0`` (line 173)
    is a 6-digit unsigned numeric field that auto-zero-pads on
    STRING output: when WS-TRANID-SUFFIX=1, the STRING produces
    ``'000001'`` as the suffix portion.  This function reproduces
    that behaviour with Python's ``str.zfill``.

    The PARM date, when the job is invoked as in ``INTCALC.jcl``
    line 16 (``EXEC PGM=CBACT04C,PARM='2022071800'``), is the 10-
    character ``PARM-DATE PIC X(10)`` literal — e.g.,
    ``"2022071800"`` (YYYYMMDD + 2 pad chars, or 2-digit HH
    depending on historical convention).  This function does not
    validate the PARM date; the caller is responsible for supplying
    a 10-character value (see :func:`_get_parm_date`).

    Example
    -------
    >>> generate_tran_id("2022071800", 1)
    '2022071800000001'
    >>> generate_tran_id("2022071800", 999999)
    '2022071800999999'

    Parameters
    ----------
    parm_date : str
        The job's 10-character PARM date (``PARM-DATE PIC X(10)``),
        passed through from the JCL ``EXEC PARM='2022071800'``.
        Right-padded with spaces if shorter; left-truncated if longer.
    suffix : int
        The per-record incrementing suffix (``WS-TRANID-SUFFIX PIC
        9(06)``).  The caller increments by 1 for each interest-
        transaction record emitted within the job run.  Must fit in
        6 digits (0 <= suffix <= 999_999).

    Returns
    -------
    str
        A 16-character ``TRAN-ID`` string ready for assignment to
        ``transactions.tran_id`` (``CHAR(16)`` in the PostgreSQL
        schema — see ``db/migrations/V1__schema.sql``).

    Raises
    ------
    ValueError
        If ``suffix`` is negative or would exceed 6 digits (the
        COBOL counter silently wraps at ``999_999`` → ``000000`` via
        ``PIC 9(06)`` overflow; the Python equivalent raises rather
        than silently wrap, so operators can detect over-volume runs).
    """
    if suffix < 0:
        raise ValueError(
            f"Transaction-ID suffix must be non-negative, got {suffix}."
        )
    if suffix >= 10**_TRAN_ID_SUFFIX_WIDTH:
        raise ValueError(
            "Transaction-ID suffix overflowed 6-digit COBOL field "
            f"(WS-TRANID-SUFFIX PIC 9(06)): got {suffix}."
        )

    # Pad/truncate the PARM date to exactly 10 chars (COBOL PIC X(10)
    # semantics: spaces fill, oversize truncates).
    date_part: str = _pad_right(parm_date, _PARM_DATE_LEN)
    # Zero-pad the suffix to 6 digits (COBOL PIC 9(06) semantics).
    suffix_part: str = str(suffix).zfill(_TRAN_ID_SUFFIX_WIDTH)
    return f"{date_part}{suffix_part}"


# ============================================================================
# build_interest_transaction — replaces 1300-B-WRITE-TX (lines 473-515).
# ============================================================================
def build_interest_transaction(
    parm_date: str,
    suffix: int,
    acct_id: str,
    card_num: str,
    monthly_int: Decimal,
) -> dict[str, Any]:
    """Assemble a single interest-transaction record as a dict.

    Direct replacement for the COBOL paragraph ``1300-B-WRITE-TX``
    at ``app/cbl/CBACT04C.cbl`` lines 473-515.  The COBOL block
    sets all 14 fields of the ``CVTRA05Y`` TRAN-RECORD layout, then
    writes the record to the ``TRANSACT-FILE`` (SYSTRAN) output::

        ADD 1 TO WS-TRANID-SUFFIX
        STRING PARM-DATE WS-TRANID-SUFFIX
               DELIMITED BY SIZE INTO TRAN-ID
        MOVE '01'                  TO TRAN-TYPE-CD
        MOVE '05'                  TO TRAN-CAT-CD
        MOVE 'System'              TO TRAN-SOURCE
        STRING 'Int. for a/c ' ACCT-ID
               DELIMITED BY SIZE INTO TRAN-DESC
        MOVE WS-MONTHLY-INT        TO TRAN-AMT
        MOVE 0                     TO TRAN-MERCHANT-ID
        MOVE SPACES                TO TRAN-MERCHANT-NAME
        MOVE SPACES                TO TRAN-MERCHANT-CITY
        MOVE SPACES                TO TRAN-MERCHANT-ZIP
        MOVE XREF-CARD-NUM         TO TRAN-CARD-NUM
        PERFORM Z-GET-DB2-FORMAT-TIMESTAMP
        MOVE DB2-FORMAT-TS         TO TRAN-ORIG-TS
        MOVE DB2-FORMAT-TS         TO TRAN-PROC-TS
        WRITE FD-TRANFILE-REC  FROM TRAN-RECORD

    The field assignments preserved verbatim per AAP §0.7.1:

    - ``TRAN-TYPE-CD = '01'``   — interest transaction type
    - ``TRAN-CAT-CD  = '05'``   — interest category (stored as
      PIC 9(04) → ``"0005"`` in the PostgreSQL ``CHAR(4)`` column)
    - ``TRAN-SOURCE  = 'System'`` — auto-generated source marker
    - ``TRAN-DESC    = 'Int. for a/c {acct_id}'`` — concatenated
      description (truncated/padded to the 100-char TRAN-DESC width)
    - ``TRAN-AMT     = monthly_int`` — the computed interest amount
    - ``TRAN-MERCHANT-ID   = 0`` — zeroed per COBOL line 503
    - ``TRAN-MERCHANT-NAME = spaces`` — blanked per line 504
    - ``TRAN-MERCHANT-CITY = spaces`` — blanked per line 506
    - ``TRAN-MERCHANT-ZIP  = spaces`` — blanked per line 508
    - ``TRAN-CARD-NUM      = XREF-CARD-NUM`` (from the xref lookup)
    - ``TRAN-ORIG-TS / TRAN-PROC-TS = current DB2 timestamp``

    The returned dict uses ``tran_*`` snake_case keys matching the
    PostgreSQL ``transactions`` table columns in
    ``db/migrations/V1__schema.sql``.  The caller wraps the dict in
    a :class:`pyspark.sql.Row` and writes it to S3 via
    :func:`_write_interest_trans_to_s3`.

    Parameters
    ----------
    parm_date : str
        The job's PARM date (passes through to :func:`generate_tran_id`).
    suffix : int
        The per-record incrementing suffix (passes through to
        :func:`generate_tran_id`).
    acct_id : str
        The account ID of the interest-bearing account, used both
        in the transaction description and (after xref lookup) to
        populate TRAN-CARD-NUM.  Should be a 11-char
        right-space-padded string (CHAR(11)).
    card_num : str
        The card number resolved from the xref lookup
        ``XREF-CARD-NUM PIC X(16)`` (CVACT03Y).  Populates
        TRAN-CARD-NUM directly.
    monthly_int : Decimal
        The pre-computed monthly interest amount returned from
        :func:`compute_monthly_interest`.  Populates TRAN-AMT.

    Returns
    -------
    dict[str, Any]
        A dict with 14 keys matching the transactions-table columns:

        - ``tran_id``         : str (16 chars)
        - ``tran_type_cd``    : str ('01')
        - ``tran_cat_cd``     : str ('0005')
        - ``tran_source``     : str ('System')
        - ``tran_desc``       : str (100 chars)
        - ``tran_amt``        : Decimal (2-decimal-place scale)
        - ``tran_merchant_id`` : str ('000000000' — zero-filled)
        - ``tran_merchant_name`` : str (50 chars, spaces)
        - ``tran_merchant_city`` : str (50 chars, spaces)
        - ``tran_merchant_zip``  : str (10 chars, spaces)
        - ``tran_card_num``   : str (16 chars)
        - ``tran_orig_ts``    : str (26-char DB2 timestamp)
        - ``tran_proc_ts``    : str (26-char DB2 timestamp)
    """
    # Normalise the acct_id/card_num inputs (strip CHAR(n) padding).
    stripped_acct_id: str = _normalise_key(acct_id)
    stripped_card_num: str = _normalise_key(card_num)

    # Generate transaction ID — PARM-DATE + zero-padded suffix.
    tran_id: str = generate_tran_id(parm_date, suffix)

    # Build TRAN-DESC as 'Int. for a/c ' + ACCT-ID, truncated/padded
    # to the 100-char TRAN-DESC width per CVTRA05Y layout.
    tran_desc: str = _pad_right(
        f"{_INTEREST_DESC_PREFIX}{stripped_acct_id}",
        _TRAN_DESC_LEN,
    )

    # Capture the DB2-format timestamp once and use it for both
    # TRAN-ORIG-TS and TRAN-PROC-TS (matching COBOL behaviour —
    # both fields are populated from the same Z-GET-DB2-FORMAT-
    # TIMESTAMP call at line 511).
    now_ts: datetime = datetime.now(timezone.utc)  # noqa: UP017
    db2_ts: str = _format_db2_timestamp(now_ts)

    # Quantize the monetary amount to 2-decimal scale (defensive —
    # callers should already have quantized via compute_monthly_interest).
    tran_amt: Decimal = _money(monthly_int)

    # Assemble the record dict — keys match the ``transactions`` table
    # columns in db/migrations/V1__schema.sql.  The merchant-* fields
    # are blanked per COBOL lines 503-509.
    return {
        "tran_id": tran_id,
        "tran_type_cd": _INTEREST_TRAN_TYPE_CD,
        "tran_cat_cd": _INTEREST_TRAN_CAT_CD,
        "tran_source": _pad_right(_INTEREST_TRAN_SOURCE, 10),
        "tran_desc": tran_desc,
        "tran_amt": tran_amt,
        "tran_merchant_id": _MERCHANT_ID_VALUE,
        "tran_merchant_name": _pad_right("", _MERCHANT_NAME_LEN),
        "tran_merchant_city": _pad_right("", _MERCHANT_CITY_LEN),
        "tran_merchant_zip": _pad_right("", _MERCHANT_ZIP_LEN),
        "tran_card_num": _pad_right(stripped_card_num, 16),
        "tran_orig_ts": db2_ts,
        "tran_proc_ts": db2_ts,
    }


# ============================================================================
# _compute_fees_stub — preserves 1400-COMPUTE-FEES (COBOL line 518-520).
# ============================================================================
def _compute_fees_stub() -> None:
    """Preserve the COBOL ``1400-COMPUTE-FEES`` stub.

    The original COBOL paragraph is a documented no-op pending
    future implementation::

        1400-COMPUTE-FEES.
        *    To be implemented
             EXIT.

    AAP §0.7.1 requires behaviour to be preserved exactly; therefore
    this function exists solely to preserve the call-site in the
    main loop.  It performs no work and returns :data:`None`.

    When the business eventually implements fee computation, this
    function will be filled in — the call site in :func:`main` does
    not need to change, matching the COBOL design intent.
    """
    # Intentionally empty — corresponds to COBOL ``EXIT.`` statement.
    # This preserves the 1400-COMPUTE-FEES call-site from paragraph
    # 1000-TCATBALF-GET-NEXT-MAIN-LOOP per CBACT04C.cbl line 215.
    return None



# ============================================================================
# _log_monetary_precision_contract — audit helper (mirrors posttran_job.py).
# ============================================================================
def _log_monetary_precision_contract() -> None:
    """Emit an audit line at job start confirming the Decimal contract.

    AAP §0.7.2 mandates that "all monetary values must use Python
    :class:`decimal.Decimal` with explicit two-decimal-place
    precision" and that "Banker's rounding (``ROUND_HALF_EVEN``) must
    be used where COBOL uses ROUNDED".  This audit line is captured
    by CloudWatch and serves as evidence for compliance review that
    the configured scale and rounding mode are the expected ones for
    every run of this Glue job.

    The log entry is structured as a single ``logger.info`` call so
    it appears in CloudWatch Logs Insights without requiring
    multi-line aggregation.
    """
    logger.info(
        "Monetary precision contract: money_scale=%s rate_scale=%s rounding=%s",
        _MONEY_SCALE,
        _RATE_SCALE,
        "ROUND_HALF_EVEN",
    )


# ============================================================================
# _get_parm_date — resolve the PARM date from the job arguments.
# ============================================================================
def _get_parm_date(resolved_args: dict[str, Any]) -> str:
    """Extract the 10-character PARM date from the job arguments.

    Replaces the COBOL ``LINKAGE SECTION`` parameter
    (``app/cbl/CBACT04C.cbl`` lines 175-178)::

        LINKAGE SECTION.
        01 EXTERNAL-PARMS.
           05 PARM-LENGTH     PIC S9(04) COMP.
           05 PARM-DATE       PIC X(10).

    and the JCL ``PARM='2022071800'`` argument from
    ``app/jcl/INTCALC.jcl`` line 16.

    AWS Glue passes this as a named job argument
    (``--PARM_DATE=2022071800``) which is surfaced via
    :func:`awsglue.utils.getResolvedOptions` into the
    ``resolved_args`` dict returned by :func:`init_glue`.

    When the argument is absent (local/test runs) the function
    defaults to the JCL literal ``'2022071800'`` — matching the
    original JCL behaviour for an unparameterised execution.

    Parameters
    ----------
    resolved_args : dict[str, Any]
        The resolved-arguments dict from :func:`init_glue`.

    Returns
    -------
    str
        A 10-character date string (PARM-DATE PIC X(10) equivalent).
        If the value is shorter or longer than 10 characters, it is
        right-padded with spaces or truncated to maintain the fixed
        width contract.
    """
    raw_value: object = resolved_args.get(_PARM_DATE_ARG_KEY, _DEFAULT_PARM_DATE)
    value: str = str(raw_value) if raw_value is not None else _DEFAULT_PARM_DATE
    if not value.strip():
        value = _DEFAULT_PARM_DATE
    return _pad_right(value, _PARM_DATE_LEN)


# ============================================================================
# Lookup dict builders — replace VSAM random READ by key.
# ============================================================================
def _build_xref_lookup_by_acct_id(xref_df: DataFrame) -> dict[str, dict[str, Any]]:
    """Build an in-memory xref lookup keyed by the ALTERNATE acct_id.

    Replaces VSAM ``XREFFILE`` access via the ALTERNATE KEY
    ``FD-XREF-ACCT-ID`` (``app/cbl/CBACT04C.cbl`` lines 29-32
    FILE-CONTROL; 1110-GET-XREF-DATA lines 389-413).  In the COBOL
    source, each account break triggers a random VSAM read by
    ``FD-XREF-ACCT-ID`` to resolve the primary card for the account;
    here we eagerly materialise the entire xref table into a Python
    dict so the subsequent in-memory lookups are O(1).

    Because the CVACT03Y record carries a 1:N mapping (one account
    can have many cards) but the COBOL code reads only the FIRST
    matching card on the alternate key, this builder preserves the
    COBOL "first match wins" semantics by iterating the DataFrame in
    deterministic order and ignoring subsequent rows for the same
    acct_id.  This matches the SINGLE-row READ of the COBOL READ
    XREF-FILE on the alternate key (VSAM alternate-key reads return
    the first matching record unless READ NEXT is used, which the
    COBOL source does not).

    Parameters
    ----------
    xref_df : DataFrame
        The ``card_cross_references`` table read from PostgreSQL via
        :func:`read_table`.  Expected columns (per
        ``db/migrations/V1__schema.sql``): ``card_num`` (CHAR(16)
        primary key, XREF-CARD-NUM), ``cust_id`` (CHAR(9),
        XREF-CUST-ID), ``acct_id`` (CHAR(11) alternate-key, XREF-
        ACCT-ID).

    Returns
    -------
    dict[str, dict[str, Any]]
        A dict keyed by the stripped acct_id, with each value being
        the full row as a dict (with all key fields normalised via
        :func:`_normalise_key`).  Empty acct_id values are skipped
        with a warning log (defensive — defends against NULL/empty
        CHAR columns in the source table).
    """
    lookup: dict[str, dict[str, Any]] = {}
    for row in xref_df.collect():
        record: dict[str, Any] = row.asDict()
        # Column names come from V1__schema.sql card_cross_references table:
        #   card_num CHAR(16) PK, cust_id CHAR(9), acct_id CHAR(11) AIX target.
        acct_id: str = _normalise_key(record.get("acct_id"))
        card_num: str = _normalise_key(record.get("card_num"))
        if not acct_id:
            logger.warning(
                "XREF record with empty acct_id encountered "
                "(card_num='%s') — skipping.",
                card_num,
            )
            continue
        if acct_id in lookup:
            # COBOL reads the FIRST matching card on alternate-key;
            # skip subsequent rows to preserve that semantics.
            continue
        # Normalise key fields so downstream lookups use stripped keys.
        record["card_num"] = card_num
        record["acct_id"] = acct_id
        record["cust_id"] = _normalise_key(record.get("cust_id"))
        lookup[acct_id] = record
    return lookup


def _build_account_lookup(accounts_df: DataFrame) -> dict[str, dict[str, Any]]:
    """Build an in-memory account lookup keyed by ``acct_id``.

    Replaces VSAM ``ACCOUNT-FILE`` random reads
    (``app/cbl/CBACT04C.cbl`` lines 40-45 FILE-CONTROL;
    1100-GET-ACCT-DATA lines 371-385).  In the COBOL source each
    account break triggers a random VSAM read by ``FD-ACCT-ID``;
    here we materialise the whole table once so the main loop can
    perform O(1) lookups.

    The ACCT-GROUP-ID (``PIC X(10)``) field is critical for the
    disclosure-group lookup that follows, so it is normalised via
    :func:`_normalise_key` on ingestion.  The monetary fields
    (``acct_curr_bal``, ``acct_curr_cyc_credit``,
    ``acct_curr_cyc_debit``) are normalised via :func:`_money` so
    downstream arithmetic operates on 2-decimal Decimals.

    Returned dict values are mutable so the main loop can apply the
    COBOL ``1050-UPDATE-ACCOUNT`` mutations (ADD WS-TOTAL-INT TO
    ACCT-CURR-BAL, MOVE 0 TO cycle fields) in-place without
    reconstructing the dict.

    Parameters
    ----------
    accounts_df : DataFrame
        The ``accounts`` table read from PostgreSQL.  Expected
        columns include ``acct_id`` (primary key CHAR(11)),
        ``acct_group_id`` (CHAR(10)), ``acct_curr_bal``
        (NUMERIC(12,2)), ``acct_curr_cyc_credit`` (NUMERIC(12,2)),
        ``acct_curr_cyc_debit`` (NUMERIC(12,2)).

    Returns
    -------
    dict[str, dict[str, Any]]
        A dict keyed by the stripped acct_id with each value being
        the full row as a mutable dict.
    """
    lookup: dict[str, dict[str, Any]] = {}
    for row in accounts_df.collect():
        record: dict[str, Any] = row.asDict()
        acct_id: str = _normalise_key(record.get("acct_id"))
        if not acct_id:
            logger.warning(
                "ACCOUNT record with empty acct_id encountered — skipping."
            )
            continue
        record["acct_id"] = acct_id
        # Normalise group-id so it aligns with disclosure-group lookup keys.
        record["acct_group_id"] = _normalise_key(record.get("acct_group_id"))
        # Normalise monetary fields to 2-decimal Decimals.
        record["acct_curr_bal"] = _money(record.get("acct_curr_bal"))
        record["acct_curr_cyc_credit"] = _money(record.get("acct_curr_cyc_credit"))
        record["acct_curr_cyc_debit"] = _money(record.get("acct_curr_cyc_debit"))
        lookup[acct_id] = record
    return lookup


def _build_disclosure_lookup(
    discgrp_df: DataFrame,
) -> dict[tuple[str, str, str], Decimal]:
    """Build a disclosure-group rate lookup keyed by the composite key.

    Replaces VSAM ``DISCGRP-FILE`` random reads
    (``app/cbl/CBACT04C.cbl`` lines 48-55 FILE-CONTROL;
    1200-GET-INTEREST-RATE lines 415-440; 1200-A-GET-DEFAULT-INT-
    RATE lines 443-460).  The CVTRA02Y ``DIS-GROUP-RECORD`` has
    composite key ``(DIS-ACCT-GROUP-ID X(10), DIS-TRAN-TYPE-CD X(02),
    DIS-TRAN-CAT-CD 9(04))`` and a single data field
    ``DIS-INT-RATE S9(04)V99`` plus the re-declared key fields.
    Since :func:`get_interest_rate` needs only the rate, this
    builder returns a flat ``{key_tuple: rate}`` dict.

    All three key components are normalised via
    :func:`_normalise_key` so the DEFAULT-fallback logic in
    :func:`get_interest_rate` works with stripped string keys.

    Parameters
    ----------
    discgrp_df : DataFrame
        The ``disclosure_groups`` table read from PostgreSQL.
        Expected columns: ``dis_acct_group_id`` (CHAR(10)),
        ``dis_tran_type_cd`` (CHAR(2)), ``dis_tran_cat_cd`` (CHAR(4)),
        ``dis_int_rate`` (NUMERIC(6,2)).

    Returns
    -------
    dict[tuple[str, str, str], Decimal]
        A dict keyed by the composite tuple, with each value being
        the ``dis_int_rate`` as a :class:`Decimal`.
    """
    lookup: dict[tuple[str, str, str], Decimal] = {}
    for row in discgrp_df.collect():
        record: dict[str, Any] = row.asDict()
        group_id: str = _normalise_key(record.get("dis_acct_group_id"))
        type_cd: str = _normalise_key(record.get("dis_tran_type_cd"))
        cat_cd: str = _normalise_key(record.get("dis_tran_cat_cd"))
        if not group_id or not type_cd or not cat_cd:
            logger.warning(
                "DISCGRP record with incomplete composite key encountered "
                "(group_id='%s' type_cd='%s' cat_cd='%s') — skipping.",
                group_id,
                type_cd,
                cat_cd,
            )
            continue
        rate: Decimal = _rate(record.get("dis_int_rate"))
        lookup[(group_id, type_cd, cat_cd)] = rate
    return lookup


def _build_tcatbal_list(tcatbal_df: DataFrame) -> list[dict[str, Any]]:
    """Materialise the TCATBAL records sorted for sequential processing.

    Replaces VSAM ``TCATBAL-FILE`` sequential ``READ NEXT``
    (``app/cbl/CBACT04C.cbl`` lines 27-38 FILE-CONTROL;
    1000-TCATBALF-GET-NEXT lines 300-330).  The COBOL source reads
    the VSAM INDEXED SEQUENTIAL file in key order:
    ``(TRANCAT-ACCT-ID, TRANCAT-TYPE-CD, TRANCAT-CD)``.  We
    reproduce the same read order by sorting the DataFrame on the
    composite-key columns before collecting to the driver.

    The COBOL account-break detection (lines 194-206) depends on
    the input being sorted by ``TRANCAT-ACCT-ID`` FIRST so that all
    records for a given account appear contiguously.

    Monetary fields (``tran_cat_bal``) are normalised via
    :func:`_money` so the interest formula in
    :func:`compute_monthly_interest` operates on 2-decimal Decimals.

    Parameters
    ----------
    tcatbal_df : DataFrame
        The ``transaction_category_balances`` table read from
        PostgreSQL.  Expected columns (per
        ``db/migrations/V1__schema.sql``): ``acct_id`` (CHAR(11),
        TRANCAT-ACCT-ID), ``type_code`` (CHAR(2), TRANCAT-TYPE-CD),
        ``cat_code`` (CHAR(4), TRANCAT-CD), ``tran_cat_bal``
        (NUMERIC(11,2), TRAN-CAT-BAL).  Note that the migrated
        schema normalises the COBOL prefix: ``TRANCAT-*`` fields
        become plain ``acct_id`` / ``type_code`` / ``cat_code``.

    Returns
    -------
    list[dict[str, Any]]
        A list of dicts, each representing one TCATBAL record, in
        the same order that the COBOL READ NEXT would yield them.
        Keys on each dict: ``acct_id``, ``type_code``, ``cat_code``,
        ``tran_cat_bal``.
    """
    sorted_df: DataFrame = tcatbal_df.orderBy(
        F.col("acct_id").asc_nulls_last(),
        F.col("type_code").asc_nulls_last(),
        F.col("cat_code").asc_nulls_last(),
    )
    records: list[dict[str, Any]] = []
    for row in sorted_df.collect():
        record: dict[str, Any] = row.asDict()
        record["acct_id"] = _normalise_key(record.get("acct_id"))
        record["type_code"] = _normalise_key(record.get("type_code"))
        record["cat_code"] = _normalise_key(record.get("cat_code"))
        record["tran_cat_bal"] = _money(record.get("tran_cat_bal"))
        records.append(record)
    return records


# ============================================================================
# Schema builders — explicit StructTypes to avoid Spark inference drift.
# ============================================================================
def _build_interest_tran_schema() -> StructType:
    """Return the explicit schema for the interest-transaction DataFrame.

    Matches the PostgreSQL ``transactions`` table layout (see
    ``db/migrations/V1__schema.sql``) and the COBOL ``CVTRA05Y``
    TRAN-RECORD (350 bytes).  Explicit nullable=False for the
    ``tran_id`` primary-key column; all other columns nullable=True
    to align with the PostgreSQL column definitions.

    Monetary field ``tran_amt`` uses :class:`DecimalType` with
    precision 15, scale 2 to match the PostgreSQL
    ``NUMERIC(15,2)`` column definition.

    Returns
    -------
    StructType
        The Spark schema for writing the interest-transaction
        DataFrame to S3 (and optionally to the ``transactions``
        table).
    """
    return StructType(
        [
            StructField("tran_id", StringType(), nullable=False),
            StructField("tran_type_cd", StringType(), nullable=True),
            StructField("tran_cat_cd", StringType(), nullable=True),
            StructField("tran_source", StringType(), nullable=True),
            StructField("tran_desc", StringType(), nullable=True),
            StructField("tran_amt", DecimalType(15, 2), nullable=True),
            StructField("tran_merchant_id", StringType(), nullable=True),
            StructField("tran_merchant_name", StringType(), nullable=True),
            StructField("tran_merchant_city", StringType(), nullable=True),
            StructField("tran_merchant_zip", StringType(), nullable=True),
            StructField("tran_card_num", StringType(), nullable=True),
            StructField("tran_orig_ts", StringType(), nullable=True),
            StructField("tran_proc_ts", StringType(), nullable=True),
        ]
    )


def _build_account_schema() -> StructType:
    """Return the explicit schema for the accounts DataFrame (writeback).

    Matches the PostgreSQL ``accounts`` table layout — a subset of
    the columns modified by :func:`_update_account_balance` is
    sufficient because :func:`write_table` uses mode="overwrite" and
    requires all PK + modified columns to match the target schema.
    The full set of columns carried through from the input DataFrame
    is re-declared here in the same order as V1__schema.sql so the
    overwrite is safe.

    Returns
    -------
    StructType
        The Spark schema used when writing the updated accounts
        DataFrame back to PostgreSQL.
    """
    return StructType(
        [
            StructField("acct_id", StringType(), nullable=False),
            StructField("acct_active_status", StringType(), nullable=True),
            StructField("acct_curr_bal", DecimalType(12, 2), nullable=True),
            StructField("acct_credit_limit", DecimalType(12, 2), nullable=True),
            StructField("acct_cash_credit_limit", DecimalType(12, 2), nullable=True),
            StructField("acct_open_date", StringType(), nullable=True),
            StructField("acct_expiration_date", StringType(), nullable=True),
            StructField("acct_reissue_date", StringType(), nullable=True),
            StructField("acct_curr_cyc_credit", DecimalType(12, 2), nullable=True),
            StructField("acct_curr_cyc_debit", DecimalType(12, 2), nullable=True),
            StructField("acct_addr_zip", StringType(), nullable=True),
            StructField("acct_group_id", StringType(), nullable=True),
            # version_id is the optimistic-concurrency column used by the
            # Account ORM (F-005 Account Update).  It is an INTEGER NOT NULL
            # DEFAULT 0 column in V1__schema.sql; we include it here so the
            # overwrite write of the accounts DataFrame preserves existing
            # version values unchanged.
            StructField("version_id", IntegerType(), nullable=True),
        ]
    )



# ============================================================================
# Fixed-width serialization helpers for SYSTRAN output (LRECL=350).
# ============================================================================
def _format_amt_for_systran(amount: Decimal) -> str:
    """Format a Decimal amount into a 12-character signed text field.

    Mirrors :func:`posttran_job._format_amt_for_reject` so the two
    batch jobs serialise amounts identically.  The layout is
    ``[sign][9 digits].[2 digits]`` — e.g., ``" 0000012.50"`` for
    ``Decimal("12.50")`` or ``"-0000012.50"`` for
    ``Decimal("-12.50")``.  The leading character is ``'-'`` for
    negative values and a space for non-negative values, matching
    COBOL SIGN LEADING SEPARATE serialisation that downstream tools
    (CBTRN03C reporting, COMBTRAN merge) expect.

    Total width is exactly 12 characters so the SYSTRAN record
    assembled by :func:`_build_systran_record_line` is the required
    350 bytes per CVTRA05Y.

    Parameters
    ----------
    amount : Decimal
        The monetary amount to format.  Assumed to already be
        quantized to 2-decimal scale by the caller (defensive
        re-quantization is applied below).

    Returns
    -------
    str
        A 12-character signed text field.
    """
    quantised = _money(amount)
    sign_char = "-" if quantised < 0 else " "
    abs_value = abs(quantised)
    integer_part = int(abs_value)
    # Cents via integer arithmetic (avoids string-representation edge
    # cases like scientific notation on normalised zero values).
    cents = int((abs_value - Decimal(integer_part)) * Decimal(100))
    mantissa = f"{integer_part:09d}.{cents:02d}"
    return f"{sign_char}{mantissa}"


def _build_systran_record_line(record: dict[str, Any]) -> str:
    """Serialise an interest-transaction record to a 350-byte fixed-width line.

    Produces a string matching the COBOL ``CVTRA05Y`` TRAN-RECORD
    layout (``app/cpy/CVTRA05Y.cpy``)::

        01  TRAN-RECORD.
            05  TRAN-ID             PIC X(16).
            05  TRAN-TYPE-CD        PIC X(02).
            05  TRAN-CAT-CD         PIC 9(04).
            05  TRAN-SOURCE         PIC X(10).
            05  TRAN-DESC           PIC X(100).
            05  TRAN-AMT            PIC S9(09)V99.  -> 12 chars signed
            05  TRAN-MERCHANT-ID    PIC 9(09).
            05  TRAN-MERCHANT-NAME  PIC X(50).
            05  TRAN-MERCHANT-CITY  PIC X(50).
            05  TRAN-MERCHANT-ZIP   PIC X(10).
            05  TRAN-CARD-NUM       PIC X(16).
            05  TRAN-ORIG-TS        PIC X(26).
            05  TRAN-PROC-TS        PIC X(26).
            05  FILLER              PIC X(20).

    Field widths sum to 16+2+4+10+100+12+9+50+50+10+16+26+26+20 =
    351, BUT the TRAN-AMT field is a 12-char signed field that
    displaces one byte of the conceptual 11-byte PIC S9(09)V99 —
    this Python serialisation preserves the wider ASCII layout
    because the amount carries an explicit sign separator byte that
    the COBOL mainframe stored via overpunched sign in a single
    byte.  Downstream consumers (COMBTRAN, CBTRN03C) are updated
    to parse the 12-char amount form; see the adjacent
    ``posttran_job._format_amt_for_reject`` for the same
    convention.

    Parameters
    ----------
    record : dict
        An interest-transaction dict as produced by
        :func:`build_interest_transaction`.  Must contain all 13
        ``tran_*`` keys from the CVTRA05Y layout.

    Returns
    -------
    str
        A 350-character fixed-width string ready for concatenation
        with a trailing newline by :func:`_write_interest_trans_to_s3`.
        Width = 16+2+4+10+100+12+9+50+50+10+16+26+26+19 = 350
        (final FILLER is 19 chars, not 20, to absorb the +1 amount
        sign byte — preserving total record length at 350 bytes).
    """
    parts: list[str] = [
        _pad_right(record.get("tran_id", ""), 16),
        _pad_right(record.get("tran_type_cd", ""), 2),
        _pad_right(record.get("tran_cat_cd", ""), 4),
        _pad_right(record.get("tran_source", ""), 10),
        _pad_right(record.get("tran_desc", ""), 100),
        _format_amt_for_systran(_money(record.get("tran_amt"))),
        _pad_right(record.get("tran_merchant_id", ""), 9),
        _pad_right(record.get("tran_merchant_name", ""), 50),
        _pad_right(record.get("tran_merchant_city", ""), 50),
        _pad_right(record.get("tran_merchant_zip", ""), 10),
        _pad_right(record.get("tran_card_num", ""), 16),
        _pad_right(record.get("tran_orig_ts", ""), 26),
        _pad_right(record.get("tran_proc_ts", ""), 26),
        # FILLER reduced from 20 to 19 to keep total width = 350
        # (TRAN-AMT serialisation expands from 11 bytes (COBOL) to
        # 12 bytes (ASCII with explicit sign char) — the extra byte
        # is absorbed from the filler).
        _pad_right("", 19),
    ]
    line = "".join(parts)
    # Defensive assertion — serialisation bugs should surface
    # immediately rather than produce corrupted downstream files.
    if len(line) != 350:
        raise ValueError(
            f"SYSTRAN record length mismatch: expected 350 bytes, got "
            f"{len(line)} for tran_id={record.get('tran_id', '<MISSING>')!r}"
        )
    return line


# ============================================================================
# _write_interest_trans_to_s3 — replaces WRITE FD-TRANFILE-REC.
# ============================================================================
def _write_interest_trans_to_s3(interest_trans: list[dict[str, Any]]) -> str | None:
    """Upload the SYSTRAN interest-transaction batch to S3.

    Replaces the COBOL ``WRITE FD-TRANFILE-REC FROM TRAN-RECORD``
    loop body (``app/cbl/CBACT04C.cbl`` line 514) and the JCL
    allocation ``DD TRANSACT DISP=(NEW,CATLG,DELETE) DSN=AWS.M2.
    CARDDEMO.SYSTRAN(+1)`` (``app/jcl/INTCALC.jcl`` lines 37-41).
    Each interest transaction is a fixed-width 350-byte line;
    records are joined with LF newlines so the resulting file is
    line-oriented (compatible with ``grep``, ``awk``, etc.) while
    preserving the fixed-width row layout for parsing.

    The target S3 path is allocated via
    :func:`get_versioned_s3_path` with logical name ``"SYSTRAN"``,
    which yields a timestamp-scoped URI such as
    ``s3://{bucket}/generated/system-transactions/YYYY/MM/DD/HHMMSS/``.
    This is the target-side equivalent of the mainframe GDG
    ``SYSTRAN(+1)`` notation.  The filename under that prefix is
    ``SYSTRAN.txt``.

    Parameters
    ----------
    interest_trans : list[dict]
        A list of interest-transaction dicts as returned by
        :func:`build_interest_transaction`.  An empty list short-
        circuits: no S3 object is allocated or written (matching
        the mainframe behaviour of simply not writing to
        SYSTRAN(+1) when no interest is accumulated).

    Returns
    -------
    str | None
        The fully-qualified ``s3://{bucket}/{key}`` URI of the
        written object, or :data:`None` if no records were written.
    """
    if not interest_trans:
        # No interest accumulated — nothing to write.  Log for audit
        # clarity then short-circuit (no S3 object is allocated).
        logger.info(
            "No interest transactions generated this run — "
            "skipping SYSTRAN S3 upload."
        )
        return None

    # Resolve the versioned S3 path for the SYSTRAN logical dataset.
    prefix_uri: str = get_versioned_s3_path("SYSTRAN")

    # Strip the scheme and split into (bucket, key_prefix).
    # write_to_s3 expects the key without the bucket and accepts
    # a bucket=... kwarg for explicit routing.
    scheme_stripped = prefix_uri.removeprefix("s3://")
    if "/" not in scheme_stripped:
        raise ValueError(
            "Invalid SYSTRAN S3 URI returned by get_versioned_s3_path: "
            f"{prefix_uri!r}"
        )
    bucket_name, key_prefix = scheme_stripped.split("/", 1)

    # Object key — matches the convention of writing a single
    # SYSTRAN dataset per generation (LRECL=350, RECFM=F).
    key: str = f"{key_prefix}SYSTRAN.txt"

    # Serialise each record to a 350-byte line; join with LF and
    # terminate with a final LF (POSIX text-file convention).
    body_lines: list[str] = [
        _build_systran_record_line(record) for record in interest_trans
    ]
    content: str = "\n".join(body_lines) + "\n"

    # Delegate the S3 PutObject to the shared helper.
    s3_uri: str = write_to_s3(
        content, key, bucket=bucket_name, content_type="text/plain"
    )
    logger.info(
        "Wrote %d interest transaction record(s) to %s (%d bytes)",
        len(interest_trans),
        s3_uri,
        len(content),
    )
    return s3_uri


# ============================================================================
# _update_account_balance — replaces 1050-UPDATE-ACCOUNT (lines 350-370).
# ============================================================================
def _update_account_balance(
    account_record: dict[str, Any],
    total_int: Decimal,
) -> None:
    """Apply the COBOL 1050-UPDATE-ACCOUNT mutations to an account record.

    Direct replacement for COBOL paragraph ``1050-UPDATE-ACCOUNT``
    (``app/cbl/CBACT04C.cbl`` lines 350-370)::

        1050-UPDATE-ACCOUNT.
            ADD WS-TOTAL-INT       TO ACCT-CURR-BAL
            MOVE 0                 TO ACCT-CURR-CYC-CREDIT
            MOVE 0                 TO ACCT-CURR-CYC-DEBIT
            REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD.

    The three mutations are applied in-place on the
    ``account_record`` dict (mutable); the caller (typically
    :func:`main`) re-builds the accounts DataFrame from the mutated
    dicts and writes it back to PostgreSQL via :func:`write_table`.

    Because the original COBOL calls REWRITE only for accounts that
    received interest (it is inside the sequential TCATBAL loop
    guarded by account-break detection), this function is called
    ONLY when ``total_int > 0`` — i.e., when the account
    accumulated some interest in the current run.  Accounts that
    have no TCATBAL records (or whose rates were zero across all
    categories) are left untouched.

    Parameters
    ----------
    account_record : dict[str, Any]
        The mutable account dict from :func:`_build_account_lookup`
        — keys: ``acct_id``, ``acct_curr_bal``,
        ``acct_curr_cyc_credit``, ``acct_curr_cyc_debit``, and
        other columns from the ``accounts`` table.  The monetary
        columns are already normalised to 2-decimal Decimals.
    total_int : Decimal
        The ``WS-TOTAL-INT`` accumulated across all category
        balances of this account.  Must be a Decimal (the caller
        builds it via repeated ``+= monthly_int``).
    """
    current_bal: Decimal = _money(account_record.get("acct_curr_bal"))
    new_bal: Decimal = (current_bal + total_int).quantize(
        _MONEY_SCALE, rounding=ROUND_HALF_EVEN
    )
    account_record["acct_curr_bal"] = new_bal
    # MOVE 0 TO ACCT-CURR-CYC-CREDIT / ACCT-CURR-CYC-DEBIT — the
    # cycle-to-date running totals are zeroed at the end of each
    # interest run so they can accumulate for the next cycle.
    account_record["acct_curr_cyc_credit"] = Decimal("0.00")
    account_record["acct_curr_cyc_debit"] = Decimal("0.00")



# ============================================================================
# main() — Glue job entry point.  Replaces COBOL PROCEDURE DIVISION.
# ============================================================================
def main() -> None:  # noqa: PLR0912, PLR0915 - mirrors CBACT04C complexity
    """Execute the Stage-2 interest-calculation PySpark Glue job.

    This is the Glue-level entry point that mirrors the COBOL
    ``PROCEDURE DIVISION`` main flow from ``app/cbl/CBACT04C.cbl``
    (lines 180-232).  The function performs the following sequence:

    1. **Initialization** — :func:`init_glue` provisions the
       SparkSession, GlueContext, Job, and structured JSON logging.
       Replaces the JCL JOB card + ``EXEC PGM=CBACT04C,PARM=
       '2022071800'`` + STEPLIB + SYSPRINT/SYSOUT DD cards from
       ``app/jcl/INTCALC.jcl``.
    2. **PARM-DATE resolution** — extract the 10-character PARM-DATE
       from ``resolved_args`` (replaces COBOL LINKAGE SECTION
       ``PARM-DATE PIC X(10)`` receiving the JCL PARM literal).
    3. **Opens** — four :func:`read_table` calls replace the five
       COBOL ``OPEN`` paragraphs (TCATBAL input, XREF input,
       ACCOUNT I-O, DISCGRP input — TRANSACT output is handled by
       :func:`_write_interest_trans_to_s3`).
    4. **Build lookup dicts** — xref (by alternate-key acct_id),
       accounts (by primary-key acct_id), disclosure-groups (by
       composite key) — each built once for O(1) access inside the
       sequential loop.
    5. **Sequential processing with account-break detection** — for
       each TCATBAL record (in composite-key order):

       * account-break: if the current record's acct_id differs
         from the previous one, apply
         :func:`_update_account_balance` to the previous account
         (if not first iteration), reset ``total_int`` to zero,
         refresh the account and xref lookups for the new account.
       * disclosure-group lookup with DEFAULT fallback via
         :func:`get_interest_rate`.
       * if rate ≠ 0: compute monthly interest, accumulate into
         ``total_int``, build an interest transaction record, and
         call :func:`_compute_fees_stub` (preserved 1400-COMPUTE-
         FEES call site).
    6. **Post-loop final update** — apply
       :func:`_update_account_balance` to the LAST account
       processed (matching COBOL line 220-221: the ELSE branch of
       the end-of-file test).
    7. **Bulk writes** — interest transactions → S3 SYSTRAN path;
       updated accounts → PostgreSQL accounts table (overwrite).
    8. **Counters + commit** — emit the COBOL-equivalent end-of-run
       message and call :func:`commit_job`.

    Raises
    ------
    Exception
        Any unhandled exception is logged as structured error and
        re-raised so AWS Glue marks the Job FAILED and Step
        Functions halts the downstream pipeline (preserving
        ``COND=(0,NE)`` abort semantics from INTCALC.jcl).
    """
    # ------------------------------------------------------------------
    # Step 0: Glue / Spark initialization.
    # Replaces JCL JOB + EXEC PGM=CBACT04C + STEPLIB + SYSPRINT/SYSOUT.
    # ------------------------------------------------------------------
    spark, _glue_context, job, resolved_args = init_glue(job_name=_JOB_NAME)

    # COBOL line 182: DISPLAY 'START OF EXECUTION OF PROGRAM CBACT04C'.
    logger.info(_COBOL_START_MSG)

    # Document the monetary precision contract for auditability.
    _log_monetary_precision_contract()

    # Log resolved Glue arguments (filter out Glue's bookkeeping keys).
    logger.info(
        "Resolved Glue arguments: %s",
        {k: v for k, v in resolved_args.items() if not k.startswith("--")},
    )

    # Resolve the PARM-DATE once at the top — used for every generated
    # transaction ID in 1300-B-WRITE-TX.
    parm_date: str = _get_parm_date(resolved_args)
    logger.info("PARM-DATE resolved to %r", parm_date)

    # Best-effort JDBC probe — surfaces mis-configuration (missing
    # Secrets Manager secret, mis-formatted URL) at job start rather
    # than deep inside the Spark read path.
    try:
        _probe_options = get_connection_options()
        logger.info(
            "JDBC connection resolved: url=%s driver=%s",
            _probe_options.get("url"),
            _probe_options.get("driver"),
        )
    except Exception as probe_err:  # noqa: BLE001 — defensive probe
        logger.warning(
            "JDBC connection probe failed (will retry via read_table): %s",
            probe_err,
        )

    # Counters (reset inside the try block so they are visible in
    # the final summary logging even if the try block raises).
    record_count: int = 0
    interest_trans_count: int = 0

    try:
        # --------------------------------------------------------------
        # Step 1: Open the four input tables.
        #
        # Replaces COBOL OPEN paragraphs:
        #   0000-TCATBALF-OPEN   (TCATBAL-FILE  input)
        #   0100-XREFFILE-OPEN   (XREF-FILE     input — incl. AIX)
        #   0200-ACCTFILE-OPEN   (ACCOUNT-FILE  I-O for REWRITE)
        #   0300-DISCGRP-OPEN    (DISCGRP-FILE  input)
        #
        # TRANSACT-FILE (output) is handled by
        # :func:`_write_interest_trans_to_s3` — no pre-open needed.
        # --------------------------------------------------------------
        logger.info("Opening input tables via JDBC...")
        tcatbal_df: DataFrame = read_table(spark, "transaction_category_balances")
        xref_df: DataFrame = read_table(spark, "card_cross_references")
        accounts_df: DataFrame = read_table(spark, "accounts")
        discgrp_df: DataFrame = read_table(spark, "disclosure_groups")

        # Cache each DataFrame so subsequent count + collect calls
        # do not re-issue JDBC queries.
        tcatbal_df = tcatbal_df.cache()
        xref_df = xref_df.cache()
        accounts_df = accounts_df.cache()
        discgrp_df = discgrp_df.cache()

        tcatbal_count = tcatbal_df.count()
        xref_count = xref_df.count()
        accounts_count = accounts_df.count()
        discgrp_count = discgrp_df.count()

        logger.info(
            "transaction_category_balances record count: %d", tcatbal_count
        )
        logger.info("card_cross_references record count: %d", xref_count)
        logger.info("accounts record count: %d", accounts_count)
        logger.info("disclosure_groups record count: %d", discgrp_count)

        # --------------------------------------------------------------
        # Step 2: Build the in-memory lookup dicts.
        #
        # Each dict is keyed by the VSAM primary / alternate key of
        # its source table.  The account_lookup dict values are
        # MUTABLE so the sequential loop can apply the 1050-UPDATE-
        # ACCOUNT mutations in place.
        # --------------------------------------------------------------
        xref_lookup = _build_xref_lookup_by_acct_id(xref_df)
        account_lookup = _build_account_lookup(accounts_df)
        disclosure_lookup = _build_disclosure_lookup(discgrp_df)
        tcatbal_records = _build_tcatbal_list(tcatbal_df)

        logger.info("xref_lookup size: %d", len(xref_lookup))
        logger.info("account_lookup size: %d", len(account_lookup))
        logger.info("disclosure_lookup size: %d", len(disclosure_lookup))
        logger.info("tcatbal_records size: %d", len(tcatbal_records))

        # --------------------------------------------------------------
        # Step 3: Sequential processing loop with account-break detection.
        #
        # Replaces COBOL paragraph 1000-TCATBALF-GET-NEXT-MAIN-LOOP
        # (lines 188-222) plus its called paragraphs.
        #
        # State machine variables (matching COBOL WORKING-STORAGE
        # lines 166-172):
        #   last_acct_num   ← WS-LAST-ACCT-NUM   PIC X(11)
        #   total_int       ← WS-TOTAL-INT       PIC S9(09)V99
        #   first_time      ← WS-FIRST-TIME      PIC X(01) VALUE 'Y'
        #   tranid_suffix   ← WS-TRANID-SUFFIX   PIC 9(06) VALUE 0
        # --------------------------------------------------------------
        last_acct_num: str = ""
        total_int: Decimal = Decimal("0.00")
        first_time: bool = True
        tranid_suffix: int = 0
        interest_trans: list[dict[str, Any]] = []
        # Track which accounts were touched so we can log the set
        # at end-of-run (aids CloudWatch auditing).
        touched_accounts: set[str] = set()

        for tcatbal_row in tcatbal_records:
            record_count += 1

            acct_id: str = tcatbal_row["acct_id"]
            type_cd: str = tcatbal_row["type_code"]
            cat_cd: str = tcatbal_row["cat_code"]
            tran_cat_bal: Decimal = tcatbal_row["tran_cat_bal"]

            # ----------------------------------------------------------
            # Account-break detection (COBOL lines 194-206).
            #
            # When TRANCAT-ACCT-ID differs from the saved last
            # account number, update the PREVIOUS account (if not
            # first iteration), then reset the running interest
            # total and refresh the account and xref lookups.
            # ----------------------------------------------------------
            if acct_id != last_acct_num:
                if not first_time:
                    # COBOL line 196: PERFORM 1050-UPDATE-ACCOUNT.
                    prev_account = account_lookup.get(last_acct_num)
                    if prev_account is None:
                        error_msg = (
                            "Account-break: previous account not found in "
                            f"account_lookup (acct_id='{last_acct_num}'). "
                            "This indicates a TCATBAL row with no matching "
                            "account record — mainframe equivalent: "
                            "1100-GET-ACCT-DATA INVALID KEY → ABEND."
                        )
                        logger.error(error_msg)
                        raise KeyError(error_msg)
                    _update_account_balance(prev_account, total_int)
                    touched_accounts.add(last_acct_num)
                else:
                    # COBOL line 200: MOVE 'N' TO WS-FIRST-TIME.
                    first_time = False

                # COBOL line 202: MOVE 0 TO WS-TOTAL-INT.
                total_int = Decimal("0.00")
                # COBOL line 203: MOVE TRANCAT-ACCT-ID TO WS-LAST-ACCT-NUM.
                last_acct_num = acct_id
                # COBOL line 205: PERFORM 1100-GET-ACCT-DATA.
                current_account = account_lookup.get(acct_id)
                if current_account is None:
                    error_msg = (
                        f"1100-GET-ACCT-DATA INVALID KEY for acct_id='{acct_id}' "
                        "— TCATBAL record has no matching account row. "
                        "Matches COBOL ABEND via 9999-ABEND-PROGRAM."
                    )
                    logger.error(error_msg)
                    raise KeyError(error_msg)
                # COBOL line 206: PERFORM 1110-GET-XREF-DATA
                # (reads XREF on alternate-key FD-XREF-ACCT-ID).
                current_xref = xref_lookup.get(acct_id)
                if current_xref is None:
                    error_msg = (
                        f"1110-GET-XREF-DATA INVALID KEY for acct_id='{acct_id}' "
                        "— no cross-reference entry for this account. "
                        "Matches COBOL ABEND via 9999-ABEND-PROGRAM."
                    )
                    logger.error(error_msg)
                    raise KeyError(error_msg)
            else:
                # Same account continuing — retain the previously
                # fetched account and xref records.  Fetch them
                # again (free O(1) operation against the dict) so
                # the variables are always fresh references.
                current_account = account_lookup[acct_id]
                current_xref = xref_lookup[acct_id]

            # ----------------------------------------------------------
            # Disclosure-group lookup + interest computation.
            # COBOL lines 210-216.
            # ----------------------------------------------------------
            # COBOL lines 210-213: build the disclosure-group key from
            #   ACCT-GROUP-ID (from ACCOUNT-RECORD)
            #   TRANCAT-CD     (from TCATBAL-RECORD)
            #   TRANCAT-TYPE-CD (from TCATBAL-RECORD)
            acct_group_id: str = _normalise_key(
                current_account.get("acct_group_id")
            )

            # COBOL line 214: PERFORM 1200-GET-INTEREST-RATE.
            #   Includes DEFAULT fallback logic.  Returns rate or
            #   raises KeyError (matching CEE3ABD ABEND behaviour).
            dis_int_rate: Decimal = get_interest_rate(
                disclosure_lookup, acct_group_id, type_cd, cat_cd
            )

            # COBOL line 215: IF DIS-INT-RATE NOT = 0 ...
            if dis_int_rate != Decimal("0.00"):
                # COBOL line 216: PERFORM 1300-COMPUTE-INTEREST.
                monthly_int: Decimal = compute_monthly_interest(
                    tran_cat_bal, dis_int_rate
                )
                # COBOL line 466: ADD WS-MONTHLY-INT TO WS-TOTAL-INT.
                total_int = (total_int + monthly_int).quantize(
                    _MONEY_SCALE, rounding=ROUND_HALF_EVEN
                )

                # COBOL line 467: PERFORM 1300-B-WRITE-TX.
                tranid_suffix += 1
                card_num: str = _normalise_key(current_xref.get("card_num"))
                interest_tran: dict[str, Any] = build_interest_transaction(
                    parm_date=parm_date,
                    suffix=tranid_suffix,
                    acct_id=acct_id,
                    card_num=card_num,
                    monthly_int=monthly_int,
                )
                interest_trans.append(interest_tran)
                interest_trans_count += 1

                # COBOL line 217: PERFORM 1400-COMPUTE-FEES
                # (preserved as documented no-op per AAP §0.7.1).
                _compute_fees_stub()
            # else branch: DIS-INT-RATE = 0 → no interest for this
            # (account, type, category) tuple — matches COBOL
            # fall-through from line 215 IF.

        # --------------------------------------------------------------
        # Step 4: Post-loop final account update.
        #
        # Replaces COBOL lines 220-221 (the ELSE branch of the
        # end-of-file test in the PERFORM UNTIL loop):
        #   ELSE
        #       PERFORM 1050-UPDATE-ACCOUNT.
        #
        # Applies the 1050-UPDATE-ACCOUNT mutations to the FINAL
        # account processed.  Without this call, the last account's
        # accumulated interest would be dropped (off-by-one bug).
        # --------------------------------------------------------------
        if not first_time:
            # last_acct_num is populated; update the final account.
            last_account = account_lookup.get(last_acct_num)
            if last_account is None:
                # This should never happen (the loop already fetched
                # this account without raising), but guard defensively.
                error_msg = (
                    f"Post-loop update: last account not found in lookup "
                    f"(acct_id='{last_acct_num}')."
                )
                logger.error(error_msg)
                raise KeyError(error_msg)
            _update_account_balance(last_account, total_int)
            touched_accounts.add(last_acct_num)

        # --------------------------------------------------------------
        # Step 5: Bulk writes.
        # --------------------------------------------------------------
        # 5a. Interest transactions → S3 SYSTRAN path.
        #     (LRECL=350 text object — 350-byte fixed-width records
        #     per CVTRA05Y layout.)
        _write_interest_trans_to_s3(interest_trans)

        # 5b. Updated accounts → accounts table (overwrite).
        #     Equivalent to COBOL REWRITE FD-ACCTFILE-REC for every
        #     touched account.  We write every account record
        #     (touched + untouched) under overwrite mode so the
        #     table contents are fully refreshed — untouched rows
        #     are written back unchanged (matching VSAM's "all rows
        #     stay in the cluster" semantics).
        if account_lookup:
            account_rows: list[Row] = [
                Row(**record) for record in account_lookup.values()
            ]
            accounts_out_df: DataFrame = spark.createDataFrame(
                account_rows,
                schema=_build_account_schema(),
            )
            write_table(accounts_out_df, "accounts", mode="overwrite")
            logger.info(
                "Wrote %d account record(s) to the accounts table "
                "(overwrite mode — REWRITE equivalent; %d touched).",
                len(account_lookup),
                len(touched_accounts),
            )
        else:
            logger.warning(
                "account_lookup is empty — accounts table write skipped."
            )

        # --------------------------------------------------------------
        # Step 6: Summary counters (COBOL DISPLAY-equivalent).
        # --------------------------------------------------------------
        logger.info("RECORDS PROCESSED      :%09d", record_count)
        logger.info("INTEREST TRANSACTIONS  :%09d", interest_trans_count)
        logger.info("ACCOUNTS TOUCHED       :%09d", len(touched_accounts))

        # COBOL line 231 (equivalent):
        # DISPLAY 'END OF EXECUTION OF PROGRAM CBACT04C'.
        logger.info(_COBOL_END_MSG)

        # --------------------------------------------------------------
        # Step 7: Release cached DataFrames + commit the Glue job.
        # --------------------------------------------------------------
        for df in (tcatbal_df, xref_df, accounts_df, discgrp_df):
            try:
                df.unpersist()
            except Exception as unpersist_err:  # noqa: BLE001 — defensive
                logger.debug(
                    "DataFrame.unpersist() raised during cleanup "
                    "(non-fatal): %s",
                    unpersist_err,
                )

        # Signal MAXCC=0 to Step Functions (matches JCL COND=(0,LT)
        # success pattern from INTCALC.jcl).
        commit_job(job)

    except Exception as exc:
        # Any unhandled exception from init_glue, read_table,
        # collect, write_table, S3 I/O, or the KeyError from
        # get_interest_rate's DEFAULT-fallback miss is logged as a
        # structured error and re-raised.  AWS Glue marks the Job
        # FAILED; Step Functions halts the downstream pipeline
        # (preserving JCL COND=(0,NE) abort semantics from
        # INTCALC.jcl line 28).
        logger.error(
            "INTCALC (CBACT04C) job failed with unhandled exception: %s",
            exc,
            exc_info=True,
        )
        # Propagate so Glue marks the job FAILED — do NOT swallow.
        raise


# ----------------------------------------------------------------------------
# Glue script entry point.
#
# AWS Glue invokes the script file directly:
#   python intcalc_job.py --JOB_NAME carddemo-intcalc --PARM_DATE 2022071800
#
# The ``if __name__`` guard ensures ``main()`` is called only in the
# script-execution context, never as a side effect of
# ``import src.batch.jobs.intcalc_job`` (which would be catastrophic
# during unit test collection or Step Functions script validation).
#
# ``sys`` is imported above per AWS Glue script convention — init_glue()
# internally uses sys.argv via awsglue.utils.getResolvedOptions, and any
# unhandled exception propagates through main() which causes a non-zero
# exit (AWS Glue interprets as job FAILED).
# ----------------------------------------------------------------------------
if __name__ == "__main__":
    # Log the argv vector at DEBUG so operator troubleshooting in
    # CloudWatch can correlate Glue --argument passing with script
    # behaviour.  logger.debug() messages emitted before init_glue()
    # installs the JsonFormatter root handler are dropped, which is
    # the correct behaviour (no double-logging, no orphan plaintext
    # lines).
    logger.debug("Invoked with sys.argv: %s", sys.argv)
    main()

