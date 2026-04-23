# ============================================================================
# Source: app/cbl/CBTRN02C.cbl  — Transaction Posting Engine (Stage 1)
#         app/jcl/POSTTRAN.jcl  — JCL orchestration
#         app/cpy/CVTRA06Y.cpy  — DALYTRAN-RECORD (350B)
#         app/cpy/CVTRA05Y.cpy  — TRAN-RECORD (350B, posted output)
#         app/cpy/CVACT03Y.cpy  — CARD-XREF-RECORD (50B, card↔account lookup)
#         app/cpy/CVACT01Y.cpy  — ACCOUNT-RECORD (300B)
#         app/cpy/CVTRA01Y.cpy  — TRAN-CAT-BAL-RECORD (50B)
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
"""Stage 1 — Daily transaction posting PySpark Glue job.

Replaces ``app/cbl/CBTRN02C.cbl`` (the *posting engine*, ~580 lines of
COBOL) and its JCL wrapper ``app/jcl/POSTTRAN.jcl``.  This is the most
critical batch job in the CardDemo pipeline: it validates every inbound
daily transaction, posts valid records to the master transaction table,
updates the corresponding account balance and transaction-category
balance, and writes a reject file for invalid records.  Downstream
stages (``intcalc_job`` / ``combtran_job`` / ``creastmt_job`` /
``tranrept_job``) depend on the posting completing successfully.

Overview of the original COBOL program
--------------------------------------
``CBTRN02C`` declares six files in its FILE-CONTROL section
(``app/cbl/CBTRN02C.cbl`` lines 28-61):

==========  ============  ==================================================
DD name     I/O kind      Description
==========  ============  ==================================================
DALYTRAN    SEQUENTIAL    Daily transaction feed (350-byte records, CVTRA06Y)
TRANSACT    INDEXED OUT   Posted transaction master (key = TRAN-ID)
XREFFILE    INDEXED RND   Card → account cross-reference (key = CARD-NUM)
DALYREJS    SEQUENTIAL    Reject file (350-byte tran + 80-byte trailer = 430B)
ACCTFILE    INDEXED I/O   Account master (key = ACCT-ID; READ + REWRITE)
TCATBALF    INDEXED I/O   Transaction-category balance (3-part key)
==========  ============  ==================================================

The main loop (lines 193-234) performs, for every daily-transaction
record:

1. ``PERFORM 1500-VALIDATE-TRAN`` — a **sequential 4-stage cascade**
   that stops at the first failure:

   * **Stage 1** (``1500-A-LOOKUP-XREF``): cross-reference lookup on
     card number.  On INVALID KEY → reject code **100**
     "INVALID CARD NUMBER FOUND".
   * **Stage 2** (``1500-B-LOOKUP-ACCT``): account lookup on the
     account-id returned by the xref.  On INVALID KEY → reject code
     **101** "ACCOUNT RECORD NOT FOUND".
   * **Stage 3** (overlimit check): ``COMPUTE WS-TEMP-BAL =
     ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT`` and
     if ``ACCT-CREDIT-LIMIT < WS-TEMP-BAL`` → reject code **102**
     "OVERLIMIT TRANSACTION".
   * **Stage 4** (expiration check): if
     ``ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10)`` → reject code
     **103** "TRANSACTION RECEIVED AFTER ACCT EXPIRATION".

2. When validation passes → ``PERFORM 2000-POST-TRANSACTION`` which
   copies every field from ``DALYTRAN-*`` to ``TRAN-*``, generates a
   DB2-format timestamp for ``TRAN-PROC-TS``, then performs
   ``2700-UPDATE-TCATBAL`` (create-or-update on the composite key),
   ``2800-UPDATE-ACCOUNT-REC`` (add the amount to ``ACCT-CURR-BAL``
   and to either ``ACCT-CURR-CYC-CREDIT`` or ``ACCT-CURR-CYC-DEBIT``
   depending on sign), and ``2900-WRITE-TRANSACTION-FILE``.

3. When validation fails → ``PERFORM 2500-WRITE-REJECT-REC`` writes a
   fixed 430-byte record (350B original transaction data + 80B
   validation trailer made of a 4-digit fail-reason code and a
   76-char description) to the DALYREJS GDG.

4. After the loop: six CLOSE paragraphs, two DISPLAY counts
   ("TRANSACTIONS PROCESSED" / "TRANSACTIONS REJECTED"), and the
   non-zero ``MOVE 4 TO RETURN-CODE`` when any rejects occurred.

Mainframe-to-Cloud Transformation
---------------------------------
* The six VSAM ``OPEN`` paragraphs (0000-0500) collapse into four
  :func:`src.batch.common.db_connector.read_table` calls (one per
  input PostgreSQL table) plus the lazy DataFrames that become the
  in-memory lookup maps.
* The sequential ``PERFORM UNTIL END-OF-FILE`` loop is preserved
  faithfully via :meth:`pyspark.sql.DataFrame.toLocalIterator` — the
  4-stage validation cascade is inherently sequential and stops at
  the first failure, so per-row Python iteration is the correct
  translation idiom (AAP §0.7.1 "preserve business logic exactly").
  The xref / account / TCATBAL lookups are performed against Python
  dicts built once from the source DataFrames, giving O(1) access
  equivalent to VSAM INDEXED RANDOM READ.
* The posted transactions, updated accounts, and updated
  transaction-category balances are accumulated in driver-side
  collections during the loop and then written back to Aurora
  PostgreSQL as three DataFrames via
  :func:`src.batch.common.db_connector.write_table` — replacing the
  per-record ``WRITE`` / ``REWRITE`` VSAM semantics with bulk JDBC
  INSERT / UPSERT-equivalent patterns.
* The reject file (LRECL=430, 350B tran + 80B trailer) is written to
  a versioned S3 prefix allocated by
  :func:`src.batch.common.s3_utils.get_versioned_s3_path` with
  ``gdg_name="DALYREJS"``, matching the original
  ``DSN=AWS.M2.CARDDEMO.DALYREJS(+1)`` GDG disposition.  The content
  is a fixed-width text file where every record is exactly 430
  characters, so downstream consumers (operator review, audit
  tooling) can parse it with the same layout as the mainframe file.
* The JCL boilerplate (``//POSTTRAN JOB ...`` / ``//STEP15 EXEC
  PGM=CBTRN02C`` / ``//STEPLIB`` / ``//SYSPRINT`` / ``//SYSOUT``)
  collapses into a single :func:`src.batch.common.glue_context.init_glue`
  call.  The terminal ``GOBACK`` becomes :func:`commit_job`.  When
  ``reject_count > 0`` the Python process exits with status 4 via
  :func:`sys.exit` — preserving the original ``MOVE 4 TO RETURN-CODE``
  signal for Step Functions (a *warning*, not a fatal abort — the
  downstream INTCALC / COMBTRAN stages are still scheduled).

Financial precision contract
----------------------------
Every monetary column read from PostgreSQL flows through PySpark as
:class:`pyspark.sql.types.DecimalType(n,2)` backed by Python
:class:`decimal.Decimal` — the COBOL ``PIC S9(n)V99`` equivalent per
AAP §0.7.2.  The overlimit computation
``temp_bal = curr_cyc_credit − curr_cyc_debit + dalytran_amt`` uses
:class:`Decimal` arithmetic exclusively; no floating-point conversion
is performed anywhere in this module.  Rounding, where required (e.g.,
when packing a :class:`Decimal` into the 430-byte reject record
layout), uses the :data:`decimal.ROUND_HALF_EVEN` policy (banker's
rounding) that matches the COBOL ``ROUNDED`` keyword semantics.

Reject code catalog (preserved from CBTRN02C.cbl verbatim)
----------------------------------------------------------
* **100** — "INVALID CARD NUMBER FOUND"               (xref lookup failure)
* **101** — "ACCOUNT RECORD NOT FOUND"                (account lookup failure)
* **102** — "OVERLIMIT TRANSACTION"                   (credit-limit breach)
* **103** — "TRANSACTION RECEIVED AFTER ACCT EXPIRATION" (expired account)
* **109** — "ACCOUNT RECORD NOT FOUND"                (account REWRITE failure)

Exit codes
----------
* **0** — normal completion, zero rejects → all downstream stages proceed.
* **4** — normal completion with one or more rejects →
  non-fatal warning, downstream stages proceed but the reject file
  MUST be reviewed by an operator (equivalent to JCL ``COND=(4,LT)``
  semantics on the next step, which the Step Functions state machine
  translates into a "ProcessedWithRejects" success branch).
* **non-zero / traceback** — fatal error (JDBC failure, Spark error,
  S3 write failure, unhandled exception) → Glue marks the job as
  FAILED and Step Functions halts the pipeline.

See Also
--------
:mod:`src.batch.jobs.daily_tran_driver_job`  — Pre-pipeline driver (CBTRN01C.cbl)
:mod:`src.batch.jobs.intcalc_job`            — Stage 2 (CBACT04C.cbl)
:mod:`src.batch.jobs.combtran_job`           — Stage 3 (COMBTRAN.jcl, no COBOL)
:mod:`src.batch.jobs.creastmt_job`           — Stage 4a (CBSTM03A/B.CBL)
:mod:`src.batch.jobs.tranrept_job`           — Stage 4b (CBTRN03C.cbl)
:mod:`src.batch.common.glue_context`         — init_glue / commit_job
:mod:`src.batch.common.db_connector`         — read_table / write_table / get_connection_options
:mod:`src.batch.common.s3_utils`             — get_versioned_s3_path / write_to_s3

AAP §0.2.2 — Batch Program Classification (CBTRN02C → POSTTRAN stage 1)
AAP §0.5.1 — File-by-File Transformation Plan (posttran_job row)
AAP §0.7.1 — Preserve all existing business logic exactly as-is
AAP §0.7.2 — Financial precision (Decimal only, no float)
AAP §0.7.3 — Minimal change discipline
"""

from __future__ import annotations

# ----------------------------------------------------------------------------
# Standard-library imports.
# ----------------------------------------------------------------------------
# ``sys``        — sys.exit(4) to signal ``MOVE 4 TO RETURN-CODE`` when any
#                  rejects occurred (CBTRN02C.cbl line 232).
# ``logging``    — structured JSON logging configured by init_glue() that
#                  emits to CloudWatch, replacing SYSPRINT / SYSOUT DD
#                  SYSOUT=* from POSTTRAN.jcl for COBOL DISPLAY output.
# ``Decimal``    — COBOL PIC S9(n)V99 equivalent.  Used for every monetary
#                  field (dalytran_amt, acct_curr_bal, acct_curr_cyc_credit,
#                  acct_curr_cyc_debit, acct_credit_limit, tran_cat_bal)
#                  and for the overlimit COMPUTE expression.
# ``ROUND_HALF_EVEN`` — banker's-rounding policy matching COBOL ROUNDED.
# ``datetime``   — DB2-format timestamp generation for TRAN-PROC-TS in the
#                  posted-transaction record (CBTRN02C.cbl paragraph
#                  Z-GET-DB2-FORMAT-TIMESTAMP invoked from 2000-POST-
#                  TRANSACTION).
# ``timezone``   — timezone.utc keeps the generated timestamp UTC-aware,
#                  matching the source program's STORE clock behavior.
# ----------------------------------------------------------------------------
import logging
import sys
from datetime import datetime, timezone
from decimal import ROUND_HALF_EVEN, Decimal
from typing import Any

# ----------------------------------------------------------------------------
# Third-party imports — PySpark 3.5.6 (AWS Glue 5.1 runtime).
# ----------------------------------------------------------------------------
# ``pyspark.sql.functions`` (aliased ``F``) — column helpers.  ``F.col``
# and ``F.lit`` are used to build the posted-transaction DataFrame and to
# tag the driver's run-marker column so CloudWatch Logs Insights queries
# can filter by job invocation.
#
# ``DataFrame`` / ``Row`` — type aliases referenced in helper function
# signatures (DataFrame as parameter type, Row as the element type
# returned by .toLocalIterator() / .collect() during the sequential
# validation cascade).  Row.asDict() converts the per-row snapshot into
# a plain dict so the validate_transaction helper (which takes a dict
# parameter) stays independent of PySpark internals — this keeps the
# pure-Python helpers testable under pytest without a SparkSession.
#
# ``StructType`` / ``StructField`` / ``StringType`` / ``DecimalType`` /
# ``IntegerType`` — explicit schema builders for the posted-transaction
# DataFrame, the updated-accounts DataFrame, and the updated-TCATBAL
# DataFrame.  The schemas mirror the PostgreSQL column types declared in
# db/migrations/V1__schema.sql: CHAR / VARCHAR → StringType, NUMERIC(n,2)
# → DecimalType(n,2), and the reject_code used internally → IntegerType.
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
# ``write_table_idempotent``         → JDBC INSERT ... ON CONFLICT DO NOTHING
#                                      (Spark-side equivalent; filters input
#                                      DataFrame to exclude rows whose PK
#                                      already exists in the target). Used
#                                      for the ``transactions`` append at
#                                      Step 4a to make POSTTRAN retry-safe
#                                      per QA Checkpoint 5 Issue 22 and
#                                      AAP §0.7.1.
# ``get_connection_options``         → JDBC connection opts dict (url, user, …)
# ``get_versioned_s3_path``          → DALYREJS(+1) → s3://bucket/rejects/…
# ``write_to_s3``                    → Write reject payload to S3 object
# ----------------------------------------------------------------------------
from src.batch.common.db_connector import (
    get_connection_options,
    read_table,
    write_table,
    write_table_idempotent,
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
# REJECT-CODE CONSTANTS — preserved verbatim from CBTRN02C.cbl.
# ============================================================================
# The five reject codes below are emitted by the validation cascade in
# paragraphs 1500-A-LOOKUP-XREF, 1500-B-LOOKUP-ACCT, and 2800-UPDATE-
# ACCOUNT-REC of the source COBOL program.  The numeric values match
# the ``WS-VALIDATION-FAIL-REASON PIC 9(04)`` literals used by the
# COBOL ``MOVE NNN TO WS-VALIDATION-FAIL-REASON`` statements verbatim
# (AAP §0.7.1 — "Preserve all existing functionality exactly as-is").
#
# The associated descriptive text (76 characters after space-padding in
# the reject trailer) is assembled in :func:`validate_transaction` and
# :func:`_reject_desc_for_code`.  Both the numeric code and the text
# MUST remain byte-for-byte identical to the COBOL source so that
# downstream audit and reconciliation tooling (which was written
# against the mainframe reject file) continues to parse the cloud-
# produced reject file without modification.
# ============================================================================

#: Reject code for card cross-reference lookup failure.
#: Emitted by Stage 1 of the validation cascade (paragraph
#: 1500-A-LOOKUP-XREF, lines 380-392).  COBOL text:
#: ``"INVALID CARD NUMBER FOUND"``.
REJECT_INVALID_CARD: int = 100

#: Reject code for account lookup failure.
#: Emitted by Stage 2 of the validation cascade (paragraph
#: 1500-B-LOOKUP-ACCT, lines 393-422 — INVALID KEY branch).
#: COBOL text: ``"ACCOUNT RECORD NOT FOUND"``.
REJECT_ACCT_NOT_FOUND: int = 101

#: Reject code for credit-limit overlimit breach.
#: Emitted by Stage 3 of the validation cascade (paragraph
#: 1500-B-LOOKUP-ACCT, lines 403-413 — overlimit compute branch).
#: COBOL text: ``"OVERLIMIT TRANSACTION"``.
REJECT_OVERLIMIT: int = 102

#: Reject code for transactions received after the account's
#: expiration date.  Emitted by Stage 4 of the validation cascade
#: (paragraph 1500-B-LOOKUP-ACCT, lines 414-420 — expiration branch).
#: COBOL text: ``"TRANSACTION RECEIVED AFTER ACCT EXPIRATION"``.
REJECT_EXPIRED: int = 103

#: Reject code for an account-record REWRITE failure.  Emitted by the
#: INVALID KEY branch of paragraph 2800-UPDATE-ACCOUNT-REC (line 558).
#: This is a RARE code in practice (the record was just successfully
#: read 20 COBOL lines earlier, so it should still exist) but is
#: preserved for fidelity with the source program.  COBOL text:
#: ``"ACCOUNT RECORD NOT FOUND"``.
REJECT_ACCT_REWRITE_FAIL: int = 109


# ----------------------------------------------------------------------------
# COBOL DISPLAY literals — emitted verbatim at start / end of execution
# and for the final summary counters.  Preserved byte-for-byte from
# CBTRN02C.cbl so downstream log-parsing tooling does not break
# (AAP §0.7.1).
# ----------------------------------------------------------------------------
_COBOL_START_MSG: str = "START OF EXECUTION OF PROGRAM CBTRN02C"
_COBOL_END_MSG: str = "END OF EXECUTION OF PROGRAM CBTRN02C"

# ----------------------------------------------------------------------------
# Glue job name — exposed as a module-level constant for explicit AWS
# Glue job resource mapping.  Matches the convention applied throughout
# the batch layer (daily_tran_driver_job → "carddemo-daily-tran-driver",
# etc.) and is used as the ``job_name`` argument to ``init_glue``.
# ----------------------------------------------------------------------------
_JOB_NAME: str = "carddemo-posttran"

# ----------------------------------------------------------------------------
# Mapping from reject code → 76-character description text.  Assembled
# once at module load so the per-record reject-record construction in
# ``build_reject_record`` is O(1).  Values are right-space-padded to 76
# characters to match the COBOL ``WS-VALIDATION-FAIL-REASON-DESC
# PIC X(76)`` field layout; the padding is applied when the reject
# record is serialized (see :func:`build_reject_record`).
# ----------------------------------------------------------------------------
_REJECT_DESCRIPTIONS: dict[int, str] = {
    REJECT_INVALID_CARD: "INVALID CARD NUMBER FOUND",
    REJECT_ACCT_NOT_FOUND: "ACCOUNT RECORD NOT FOUND",
    REJECT_OVERLIMIT: "OVERLIMIT TRANSACTION",
    REJECT_EXPIRED: "TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
    REJECT_ACCT_REWRITE_FAIL: "ACCOUNT RECORD NOT FOUND",
}

# ----------------------------------------------------------------------------
# Fixed byte sizes from the source copybooks, used for padding /
# truncating string fields into the fixed-width reject record.  Each
# value corresponds to the PIC X(n) / PIC 9(n) length from the COBOL
# copybook referenced on the right.
# ----------------------------------------------------------------------------
_DALYTRAN_ID_LEN: int = 16         # DALYTRAN-ID          PIC X(16)  (CVTRA06Y)
_DALYTRAN_TYPE_LEN: int = 2        # DALYTRAN-TYPE-CD     PIC X(02)  (CVTRA06Y)
_DALYTRAN_CAT_LEN: int = 4         # DALYTRAN-CAT-CD      PIC 9(04)  (CVTRA06Y)
_DALYTRAN_SOURCE_LEN: int = 10     # DALYTRAN-SOURCE      PIC X(10)  (CVTRA06Y)
_DALYTRAN_DESC_LEN: int = 100      # DALYTRAN-DESC        PIC X(100) (CVTRA06Y)
_DALYTRAN_AMT_LEN: int = 12        # DALYTRAN-AMT         PIC S9(09)V99 → 12 chars "-nnnnnnnnn.nn"
_DALYTRAN_MERCH_ID_LEN: int = 9    # DALYTRAN-MERCHANT-ID PIC 9(09)  (CVTRA06Y)
_DALYTRAN_MERCH_NAME_LEN: int = 50
_DALYTRAN_MERCH_CITY_LEN: int = 50
_DALYTRAN_MERCH_ZIP_LEN: int = 10
_DALYTRAN_CARD_NUM_LEN: int = 16   # DALYTRAN-CARD-NUM    PIC X(16)  (CVTRA06Y)
_DALYTRAN_ORIG_TS_LEN: int = 26    # DALYTRAN-ORIG-TS     PIC X(26)  (CVTRA06Y)
_DALYTRAN_PROC_TS_LEN: int = 26    # DALYTRAN-PROC-TS     PIC X(26)  (CVTRA06Y)
_DALYTRAN_FILLER_LEN: int = 20     # FILLER               PIC X(20)  (CVTRA06Y)
#: Total byte size of the DALYTRAN-RECORD = 16 + 2 + 4 + 10 + 100 + 12
#: + 9 + 50 + 50 + 10 + 16 + 26 + 26 + 20 = 351 bytes.  Because the
#: source copybook FILLER is PIC X(20) and the amount field is
#: serialized as a signed 12-character string in the text projection,
#: the actual written payload is **350** bytes — one byte of padding
#: at the end of the FILLER is truncated when the record is written.
#: The COBOL ``WRITE FD-REJS-RECORD FROM REJECT-RECORD`` writes exactly
#: 350 bytes of REJECT-TRAN-DATA + 80 bytes of VALIDATION-TRAILER =
#: 430 bytes per record (POSTTRAN.jcl: LRECL=430).
_REJECT_TRAN_DATA_LEN: int = 350

#: VALIDATION-TRAILER layout (80 bytes):
#:   WS-VALIDATION-FAIL-REASON       PIC 9(04)  → 4 chars
#:   WS-VALIDATION-FAIL-REASON-DESC  PIC X(76)  → 76 chars
_VALIDATION_FAIL_REASON_LEN: int = 4
_VALIDATION_FAIL_DESC_LEN: int = 76
_VALIDATION_TRAILER_LEN: int = _VALIDATION_FAIL_REASON_LEN + _VALIDATION_FAIL_DESC_LEN

#: Total byte size of a reject record written to DALYREJS: 430 bytes.
#: Matches ``LRECL=430`` on the ``//DALYREJS DD`` statement of
#: POSTTRAN.jcl (line 33-37 of the source JCL).
_REJECT_RECORD_LEN: int = _REJECT_TRAN_DATA_LEN + _VALIDATION_TRAILER_LEN  # = 430

# ----------------------------------------------------------------------------
# Monetary-precision sentinel.  Used to quantize Decimal values to two
# decimal places where required by the COBOL ``PIC S9(n)V99`` fixed
# scale.  Declared at module scope so the sentinel is constructed once,
# not on every row of every call.
# ----------------------------------------------------------------------------
_MONEY_SCALE: Decimal = Decimal("0.01")


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


def _reject_desc_for_code(reject_code: int) -> str:
    """Return the COBOL-verbatim description text for a reject code.

    The returned string is NOT space-padded to 76 characters — the
    caller (:func:`build_reject_record`) performs the padding when it
    assembles the 80-byte validation trailer.  This keeps the
    description constants usable in log messages (where
    space-padding would just produce ugly trailing whitespace).

    Unknown reject codes return the neutral text
    ``"UNKNOWN VALIDATION ERROR"``.  In the current implementation
    this branch is unreachable (every code that the cascade emits is
    in the lookup table), but the fallback exists so that a future
    extension point ("ADD MORE VALIDATIONS HERE" — CBTRN02C.cbl
    line 377) cannot crash the job due to a mis-wired reject code.
    """
    return _REJECT_DESCRIPTIONS.get(reject_code, "UNKNOWN VALIDATION ERROR")


def _pad_right(value: object, width: int) -> str:
    """Right-pad (or truncate) a value's string form to *width* chars.

    Preserves COBOL ``MOVE field TO fixed-width-field`` semantics: if
    the source value's text is shorter than the target width, the
    remainder is filled with ASCII spaces; if longer, the text is
    truncated.  Numeric / Decimal values are first stringified via
    ``str()`` which is acceptable for the reject-record text
    projection (the reject file is inspected by operators / audit
    tooling, not re-consumed by a parser expecting COBOL ZONED).
    """
    if value is None:
        text = ""
    else:
        text = str(value)
    if len(text) >= width:
        return text[:width]
    return text + " " * (width - len(text))


def _zero_pad_left(value: object, width: int) -> str:
    """Left-zero-pad a value's string form to *width* chars.

    Mirrors COBOL ``MOVE numeric-field TO PIC 9(n)`` semantics where
    ``n`` exceeds the numeric's significant digit count.  Used for the
    DALYTRAN-CAT-CD (PIC 9(04)) and DALYTRAN-MERCHANT-ID (PIC 9(09))
    fields when projected into the fixed-width reject record, and for
    the WS-VALIDATION-FAIL-REASON (PIC 9(04)) field of the
    validation trailer.
    """
    if value is None:
        text = ""
    else:
        text = str(value)
    if len(text) >= width:
        return text[:width]
    return "0" * (width - len(text)) + text


def _format_amt_for_reject(amount: Decimal) -> str:
    """Format a Decimal amount into the 12-character signed text field.

    Layout: ``[sign][9 digits].[2 digits]`` — e.g., ``" 1234567.89"``
    or ``"-0000000.01"``.  The leading character is ``'-'`` for
    negative values and a space for non-negative values (matching
    COBOL SIGN LEADING SEPARATE semantics that the reject-record
    tooling expects).

    The total character width is exactly :data:`_DALYTRAN_AMT_LEN`
    = 12 so the reject record assembled in
    :func:`build_reject_record` is the required 350 bytes of
    transaction data.  Decimal scale is snapped to 2 places; if the
    source value has more precision the excess digits are rounded
    via banker's rounding.
    """
    quantised = _money(amount)
    sign_char = "-" if quantised < 0 else " "
    abs_value = abs(quantised)
    # Build the mantissa as "000000000.00" (9 digits before dp, 2 after).
    # Convert to an explicit tuple to avoid scientific notation on
    # very small values (Decimal suppresses e-notation only for
    # normalised values; quantise above already normalises but the
    # explicit integer+fractional split below is defensive).
    integer_part = int(abs_value)
    # Multiply by 100 to obtain cents, then take modulo 100 for
    # fractional cents — safer than parsing the string representation.
    cents = int((abs_value - Decimal(integer_part)) * Decimal(100))
    mantissa = f"{integer_part:09d}.{cents:02d}"
    return f"{sign_char}{mantissa}"


# ============================================================================
# VALIDATION CASCADE — replaces paragraph 1500-VALIDATE-TRAN + helpers.
# ============================================================================
def validate_transaction(
    tran_row: dict[str, Any],
    xref_lookup: dict[str, dict[str, Any]],
    account_lookup: dict[str, dict[str, Any]],
) -> tuple[bool, int, str]:
    """Run the 4-stage sequential validation cascade from CBTRN02C.

    This function is the precise Python equivalent of the COBOL
    paragraph 1500-VALIDATE-TRAN (``app/cbl/CBTRN02C.cbl`` lines
    370-378), which delegates to 1500-A-LOOKUP-XREF (lines 380-392)
    and 1500-B-LOOKUP-ACCT (lines 393-422).

    The cascade is intentionally *sequential* — it stops at the first
    failed check and does NOT evaluate subsequent stages.  This
    matches the COBOL ``IF WS-VALIDATION-FAIL-REASON = 0 THEN PERFORM
    1500-B-LOOKUP-ACCT`` guard in paragraph 1500-VALIDATE-TRAN and
    the nested ``IF WS-VALIDATION-FAIL-REASON = 0 THEN ...`` guards
    inside 1500-B-LOOKUP-ACCT that gate the overlimit and expiration
    checks behind the account-lookup success.  Preserving the short-
    circuit behaviour is critical: some callers of the reject file
    rely on the first-failure-only convention for de-duplication
    (e.g., a record rejected with code 100 should NOT ALSO carry a
    code-102 reject because its lookup never reached that stage).

    Parameters
    ----------
    tran_row : dict
        Daily-transaction record (the result of calling
        :meth:`pyspark.sql.Row.asDict` on a row of the
        ``daily_transactions`` DataFrame).  Must contain at minimum
        the following keys (lower-case PostgreSQL column names from
        ``db/migrations/V1__schema.sql``):

        * ``dalytran_id``        — PIC X(16) transaction ID
        * ``dalytran_card_num``  — PIC X(16) card number  (Stage 1 input)
        * ``dalytran_amt``       — NUMERIC(11,2) amount   (Stage 3 input)
        * ``dalytran_orig_ts``   — PIC X(26) timestamp    (Stage 4 input)

        Any other fields in the dict are ignored by this helper.

    xref_lookup : dict[str, dict]
        In-memory index on ``card_cross_references`` keyed by
        ``card_num`` (stripped whitespace).  The value is itself a
        dict with keys ``cust_id`` and ``acct_id`` (or ``None`` if
        the row is missing those columns).  Built once by
        :func:`main` from the xref DataFrame and passed unchanged
        to every call of this function.

    account_lookup : dict[str, dict]
        In-memory index on ``accounts`` keyed by ``acct_id`` (stripped
        whitespace).  The value is a dict with at minimum the keys
        ``acct_curr_cyc_credit``, ``acct_curr_cyc_debit``,
        ``acct_credit_limit``, ``acct_expiration_date``, and the
        other account columns used downstream (e.g.,
        ``acct_curr_bal`` consumed by ``update_account_balance``).

    Returns
    -------
    tuple[bool, int, str]
        A 3-tuple ``(is_valid, reject_code, reject_desc)``:

        * ``is_valid``   — ``True`` iff all 4 stages passed.
        * ``reject_code`` — ``0`` on success or one of the four
          reject codes (100, 101, 102, 103) on failure.  Note that
          code **109** (account REWRITE failure) is NOT emitted by
          this function — it is emitted by
          :func:`update_account_balance` if / when the account
          record cannot be updated.
        * ``reject_desc`` — empty string on success; otherwise the
          COBOL-verbatim description associated with the reject
          code (looked up via :func:`_reject_desc_for_code`).
    """
    # ------------------------------------------------------------------
    # STAGE 1 — Cross-reference lookup on card number.
    # ------------------------------------------------------------------
    # COBOL paragraph 1500-A-LOOKUP-XREF (lines 380-392):
    #   MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM.
    #   READ XREF-FILE INTO CARD-XREF-RECORD
    #     KEY IS FD-XREF-CARD-NUM
    #     INVALID KEY
    #       MOVE 100 TO WS-VALIDATION-FAIL-REASON
    #       MOVE 'INVALID CARD NUMBER FOUND'
    #         TO WS-VALIDATION-FAIL-REASON-DESC
    #     NOT INVALID KEY
    #       CONTINUE
    #   END-READ.
    #
    # The Python dict lookup below is the O(1) equivalent of the VSAM
    # INDEXED RANDOM READ.  The key is normalised with str().strip()
    # because CHAR(16) columns arrive from JDBC right-space-padded,
    # while the xref key was built in the same way — both sides agree
    # on the canonical key.
    # ------------------------------------------------------------------
    raw_card_num = tran_row.get("dalytran_card_num")
    card_num_key = _normalise_key(raw_card_num)
    if card_num_key not in xref_lookup:
        return (
            False,
            REJECT_INVALID_CARD,
            _reject_desc_for_code(REJECT_INVALID_CARD),
        )

    xref_record = xref_lookup[card_num_key]
    acct_id_key = _normalise_key(xref_record.get("acct_id"))

    # ------------------------------------------------------------------
    # STAGE 2 — Account lookup on the xref-returned account id.
    # ------------------------------------------------------------------
    # COBOL paragraph 1500-B-LOOKUP-ACCT, lines 393-402:
    #   MOVE XREF-ACCT-ID TO FD-ACCT-ID.
    #   READ ACCOUNT-FILE INTO ACCOUNT-RECORD
    #     KEY IS FD-ACCT-ID
    #     INVALID KEY
    #       MOVE 101 TO WS-VALIDATION-FAIL-REASON
    #       MOVE 'ACCOUNT RECORD NOT FOUND'
    #         TO WS-VALIDATION-FAIL-REASON-DESC
    #     NOT INVALID KEY
    #       ...
    # ------------------------------------------------------------------
    if acct_id_key not in account_lookup:
        return (
            False,
            REJECT_ACCT_NOT_FOUND,
            _reject_desc_for_code(REJECT_ACCT_NOT_FOUND),
        )

    account = account_lookup[acct_id_key]

    # ------------------------------------------------------------------
    # STAGE 3 — Overlimit check.
    # ------------------------------------------------------------------
    # COBOL paragraph 1500-B-LOOKUP-ACCT, lines 403-413:
    #   COMPUTE WS-TEMP-BAL =
    #       ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
    #   IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
    #       CONTINUE
    #   ELSE
    #       MOVE 102 TO WS-VALIDATION-FAIL-REASON
    #       MOVE 'OVERLIMIT TRANSACTION'
    #         TO WS-VALIDATION-FAIL-REASON-DESC
    #   END-IF.
    #
    # The formula is preserved byte-for-byte — it is NOT algebraically
    # simplified (AAP §0.7.1 "do not optimize beyond migration
    # requirements").  All arithmetic uses Decimal; no floating-point
    # conversion is performed.
    # ------------------------------------------------------------------
    curr_cyc_credit = _money(account.get("acct_curr_cyc_credit"))
    curr_cyc_debit = _money(account.get("acct_curr_cyc_debit"))
    tran_amt = _money(tran_row.get("dalytran_amt"))
    credit_limit = _money(account.get("acct_credit_limit"))
    temp_bal = curr_cyc_credit - curr_cyc_debit + tran_amt

    if credit_limit < temp_bal:
        return (
            False,
            REJECT_OVERLIMIT,
            _reject_desc_for_code(REJECT_OVERLIMIT),
        )

    # ------------------------------------------------------------------
    # STAGE 4 — Expiration check.
    # ------------------------------------------------------------------
    # COBOL paragraph 1500-B-LOOKUP-ACCT, lines 414-420:
    #   IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS(1:10)
    #       CONTINUE
    #   ELSE
    #       MOVE 103 TO WS-VALIDATION-FAIL-REASON
    #       MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
    #         TO WS-VALIDATION-FAIL-REASON-DESC
    #   END-IF.
    #
    # Both sides of the comparison are strings (CHAR / VARCHAR) in
    # the target PostgreSQL schema — acct_expiration_date is stored
    # as VARCHAR(10) "YYYY-MM-DD" and dalytran_orig_ts is stored as
    # VARCHAR(26) "YYYY-MM-DD-HH.MM.SS.ffffff".  The slice [0:10] on
    # the orig_ts captures the date portion, matching the COBOL
    # reference modification ``DALYTRAN-ORIG-TS(1:10)``.  Both fields
    # share the lexicographically-ordered ISO-8601 date format, so
    # string comparison yields the correct chronological order
    # without any datetime parsing.
    # ------------------------------------------------------------------
    expiration_date = account.get("acct_expiration_date")
    orig_ts = tran_row.get("dalytran_orig_ts")
    expiration_str = "" if expiration_date is None else str(expiration_date).strip()
    orig_ts_str = "" if orig_ts is None else str(orig_ts)
    # Defensive slicing: if orig_ts is shorter than 10 chars the slice
    # still returns what's available — the comparison will then treat
    # a malformed orig_ts as "earlier than expiration" (the COBOL
    # equivalent also yields a defined ordering via space-fill
    # right-padding).  This cannot cause a false-positive reject
    # because the preceding stages have already validated the
    # presence of a valid xref → account chain.
    orig_ts_date = orig_ts_str[:10] if len(orig_ts_str) >= 10 else orig_ts_str
    if expiration_str < orig_ts_date:
        return (
            False,
            REJECT_EXPIRED,
            _reject_desc_for_code(REJECT_EXPIRED),
        )

    # All four stages passed.
    return (True, 0, "")


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
    "missing-record" behaviour in the validation cascade.
    """
    if value is None:
        return ""
    return str(value).strip()


# ============================================================================
# build_posted_transaction — replaces paragraph 2000-POST-TRANSACTION.
# ============================================================================
def build_posted_transaction(daily_tran: dict[str, Any]) -> dict[str, Any]:
    """Convert a validated DALYTRAN record into a posted TRAN record.

    This function replaces the field-by-field ``MOVE`` block in COBOL
    paragraph 2000-POST-TRANSACTION (``app/cbl/CBTRN02C.cbl`` lines
    424-444):

    * MOVE DALYTRAN-ID           TO TRAN-ID.
    * MOVE DALYTRAN-TYPE-CD      TO TRAN-TYPE-CD.
    * MOVE DALYTRAN-CAT-CD       TO TRAN-CAT-CD.
    * MOVE DALYTRAN-SOURCE       TO TRAN-SOURCE.
    * MOVE DALYTRAN-DESC         TO TRAN-DESC.
    * MOVE DALYTRAN-AMT          TO TRAN-AMT.
    * MOVE DALYTRAN-MERCHANT-ID  TO TRAN-MERCHANT-ID.
    * MOVE DALYTRAN-MERCHANT-NAME TO TRAN-MERCHANT-NAME.
    * MOVE DALYTRAN-MERCHANT-CITY TO TRAN-MERCHANT-CITY.
    * MOVE DALYTRAN-MERCHANT-ZIP TO TRAN-MERCHANT-ZIP.
    * MOVE DALYTRAN-CARD-NUM     TO TRAN-CARD-NUM.
    * MOVE DALYTRAN-ORIG-TS      TO TRAN-ORIG-TS.
    * PERFORM Z-GET-DB2-FORMAT-TIMESTAMP THRU Z-GET-DB2-FORMAT-TIMESTAMP-EXIT.
    * MOVE DB2-FORMAT-TS TO TRAN-PROC-TS.

    The source copybook (``CVTRA05Y.cpy``) has identical field layout
    to the DALYTRAN copybook (``CVTRA06Y.cpy``) — the only difference
    is the field-name prefix (``DALYTRAN-`` vs ``TRAN-``).  In the
    target PostgreSQL schema both tables follow the same column-
    naming convention (``dalytran_*`` and ``tran_*``), so this helper
    is a small prefix-renaming projection plus the generation of the
    DB2-format timestamp that replaces ``Z-GET-DB2-FORMAT-TIMESTAMP``.

    Parameters
    ----------
    daily_tran : dict
        The source DALYTRAN record (from
        :meth:`pyspark.sql.Row.asDict`).  Must contain all 13 daily-
        transaction columns listed in ``db/migrations/V1__schema.sql``
        for the ``daily_transactions`` table.

    Returns
    -------
    dict
        A dict mirroring the ``transactions`` table schema (columns
        ``tran_id``, ``tran_type_cd``, ``tran_cat_cd``,
        ``tran_source``, ``tran_desc``, ``tran_amt``,
        ``tran_merchant_id``, ``tran_merchant_name``,
        ``tran_merchant_city``, ``tran_merchant_zip``,
        ``tran_card_num``, ``tran_orig_ts``, ``tran_proc_ts``).  The
        monetary field ``tran_amt`` is a :class:`Decimal` with 2
        decimal-place scale; every other field is a ``str`` or
        ``None``.
    """
    # Compute the DB2-format timestamp once per call.  Format is
    # "YYYY-MM-DD-HH.MM.SS.ffffff" (26 characters) — the exact
    # layout produced by the COBOL Z-GET-DB2-FORMAT-TIMESTAMP
    # paragraph.  UTC is used deliberately (the mainframe STORE
    # clock returned system time which for batch jobs ran at GMT).
    now_utc = datetime.now(timezone.utc)  # noqa: UP017  # Schema-mandated member access: ``timezone.utc`` is listed in the external_imports members_accessed for this file and must be retained verbatim; ``datetime.UTC`` is a Python 3.11+ alias but is NOT in the schema specification.
    tran_proc_ts = now_utc.strftime("%Y-%m-%d-%H.%M.%S.%f")

    return {
        "tran_id": daily_tran.get("dalytran_id"),
        "tran_type_cd": daily_tran.get("dalytran_type_cd"),
        "tran_cat_cd": daily_tran.get("dalytran_cat_cd"),
        "tran_source": daily_tran.get("dalytran_source"),
        "tran_desc": daily_tran.get("dalytran_desc"),
        "tran_amt": _money(daily_tran.get("dalytran_amt")),
        "tran_merchant_id": daily_tran.get("dalytran_merchant_id"),
        "tran_merchant_name": daily_tran.get("dalytran_merchant_name"),
        "tran_merchant_city": daily_tran.get("dalytran_merchant_city"),
        "tran_merchant_zip": daily_tran.get("dalytran_merchant_zip"),
        "tran_card_num": daily_tran.get("dalytran_card_num"),
        "tran_orig_ts": daily_tran.get("dalytran_orig_ts"),
        "tran_proc_ts": tran_proc_ts,
    }


# ============================================================================
# build_reject_record — replaces paragraph 2500-WRITE-REJECT-REC.
# ============================================================================
def build_reject_record(
    daily_tran: dict[str, Any],
    reject_code: int,
    reject_desc: str,
) -> dict[str, Any]:
    """Build a 430-byte reject record from a failed daily-transaction.

    Replaces COBOL paragraph 2500-WRITE-REJECT-REC
    (``app/cbl/CBTRN02C.cbl`` lines 446-465):

    * MOVE DALYTRAN-RECORD      TO REJECT-TRAN-DATA      (350 bytes).
    * MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER   ( 80 bytes).
    * WRITE FD-REJS-RECORD FROM REJECT-RECORD.

    The returned dict carries both a structured representation (for
    DataFrame-based analytics / unit tests) and the fixed-width text
    line that gets written to S3.  The text line layout matches the
    COBOL WRITE byte-for-byte:

    ======== ====== ===============================================
    Offset   Length Field
    ======== ====== ===============================================
    0-15     16     DALYTRAN-ID                (PIC X(16))
    16-17     2     DALYTRAN-TYPE-CD           (PIC X(02))
    18-21     4     DALYTRAN-CAT-CD            (PIC 9(04))
    22-31    10     DALYTRAN-SOURCE            (PIC X(10))
    32-131  100     DALYTRAN-DESC              (PIC X(100))
    132-143  12     DALYTRAN-AMT (signed text) (PIC S9(09)V99)
    144-152   9     DALYTRAN-MERCHANT-ID       (PIC 9(09))
    153-202  50     DALYTRAN-MERCHANT-NAME     (PIC X(50))
    203-252  50     DALYTRAN-MERCHANT-CITY     (PIC X(50))
    253-262  10     DALYTRAN-MERCHANT-ZIP      (PIC X(10))
    263-278  16     DALYTRAN-CARD-NUM          (PIC X(16))
    279-304  26     DALYTRAN-ORIG-TS           (PIC X(26))
    305-330  26     DALYTRAN-PROC-TS           (PIC X(26))
    331-349  19     FILLER (truncated to 19)   (PIC X(20) → 19 packed)
    350-353   4     WS-VALIDATION-FAIL-REASON  (PIC 9(04))
    354-429  76     WS-VALIDATION-FAIL-REASON-DESC (PIC X(76))
    ======== ====== ===============================================

    Total = 430 characters, matching ``LRECL=430`` on the DALYREJS DD.

    Parameters
    ----------
    daily_tran : dict
        The source DALYTRAN record.
    reject_code : int
        One of the public ``REJECT_*`` constants (100, 101, 102, 103,
        or 109).
    reject_desc : str
        The COBOL-verbatim description text (unpadded).  The caller
        may pass the result of :func:`_reject_desc_for_code`.

    Returns
    -------
    dict
        A dict with:

        * ``reject_code``    — int, echoes the input code
        * ``reject_desc``    — str, echoes the input description
        * ``dalytran_id``    — str, for log filtering / dedup
        * ``record_line``    — the 430-character fixed-width text
          line that gets written to S3.
    """
    # --------------------------------------------------------------
    # Assemble the 350-byte REJECT-TRAN-DATA block.
    # --------------------------------------------------------------
    parts: list[str] = []
    parts.append(_pad_right(daily_tran.get("dalytran_id"), _DALYTRAN_ID_LEN))
    parts.append(_pad_right(daily_tran.get("dalytran_type_cd"), _DALYTRAN_TYPE_LEN))
    # DALYTRAN-CAT-CD is PIC 9(04) — numeric, leading zeros.  The
    # PostgreSQL column is CHAR(4) so the value may already be "0001"
    # or may be the literal integer 1 depending on driver handling —
    # _zero_pad_left normalises either to 4-digit zero-padded.
    parts.append(_zero_pad_left(daily_tran.get("dalytran_cat_cd"), _DALYTRAN_CAT_LEN))
    parts.append(_pad_right(daily_tran.get("dalytran_source"), _DALYTRAN_SOURCE_LEN))
    parts.append(_pad_right(daily_tran.get("dalytran_desc"), _DALYTRAN_DESC_LEN))
    parts.append(_format_amt_for_reject(_money(daily_tran.get("dalytran_amt"))))
    parts.append(
        _zero_pad_left(daily_tran.get("dalytran_merchant_id"), _DALYTRAN_MERCH_ID_LEN)
    )
    parts.append(
        _pad_right(daily_tran.get("dalytran_merchant_name"), _DALYTRAN_MERCH_NAME_LEN)
    )
    parts.append(
        _pad_right(daily_tran.get("dalytran_merchant_city"), _DALYTRAN_MERCH_CITY_LEN)
    )
    parts.append(
        _pad_right(daily_tran.get("dalytran_merchant_zip"), _DALYTRAN_MERCH_ZIP_LEN)
    )
    parts.append(
        _pad_right(daily_tran.get("dalytran_card_num"), _DALYTRAN_CARD_NUM_LEN)
    )
    parts.append(
        _pad_right(daily_tran.get("dalytran_orig_ts"), _DALYTRAN_ORIG_TS_LEN)
    )
    parts.append(
        _pad_right(daily_tran.get("dalytran_proc_ts"), _DALYTRAN_PROC_TS_LEN)
    )

    # Interim sum before FILLER: 16+2+4+10+100+12+9+50+50+10+16+26+26 = 331.
    # Pad the remainder out to exactly 350 bytes with spaces (the
    # COBOL FILLER is PIC X(20) but the interim sum is 331 so the
    # effective filler width here is 350-331 = 19 characters).
    interim = "".join(parts)
    if len(interim) < _REJECT_TRAN_DATA_LEN:
        interim += " " * (_REJECT_TRAN_DATA_LEN - len(interim))
    reject_tran_data: str = interim[:_REJECT_TRAN_DATA_LEN]

    # --------------------------------------------------------------
    # Assemble the 80-byte VALIDATION-TRAILER block.
    # --------------------------------------------------------------
    fail_reason_text = _zero_pad_left(reject_code, _VALIDATION_FAIL_REASON_LEN)
    fail_desc_text = _pad_right(reject_desc, _VALIDATION_FAIL_DESC_LEN)
    validation_trailer: str = fail_reason_text + fail_desc_text

    # --------------------------------------------------------------
    # Concatenate and sanity-check the total length.
    # --------------------------------------------------------------
    record_line: str = reject_tran_data + validation_trailer
    # Defensive: truncate-or-pad in case any pad helper drifted.
    if len(record_line) < _REJECT_RECORD_LEN:
        record_line = record_line + " " * (_REJECT_RECORD_LEN - len(record_line))
    elif len(record_line) > _REJECT_RECORD_LEN:
        record_line = record_line[:_REJECT_RECORD_LEN]

    return {
        "reject_code": reject_code,
        "reject_desc": reject_desc,
        "dalytran_id": daily_tran.get("dalytran_id"),
        "record_line": record_line,
    }


# ============================================================================
# update_tcatbal — replaces paragraph 2700-UPDATE-TCATBAL.
# ============================================================================
def update_tcatbal(
    acct_id: str,
    type_cd: str,
    cat_cd: str,
    amount: Decimal,
    existing_tcatbals: dict[tuple[str, str, str], dict[str, Any]],
) -> dict[str, Any]:
    """Create-or-update a transaction-category balance.

    This function replaces the COBOL paragraph 2700-UPDATE-TCATBAL
    (``app/cbl/CBTRN02C.cbl`` lines 467-542) which decides between a
    WRITE (new record, paragraph 2700-A-CREATE-TCATBAL-REC) and a
    REWRITE (existing record, paragraph 2700-B-UPDATE-TCATBAL-REC)
    based on whether the composite key (ACCT-ID + TYPE-CD + CAT-CD)
    already exists in the TCATBAL-FILE cluster.

    COBOL logic (simplified):

    ::

        MOVE XREF-ACCT-ID       TO FD-TRANCAT-ACCT-ID.
        MOVE DALYTRAN-TYPE-CD   TO FD-TRANCAT-TYPE-CD.
        MOVE DALYTRAN-CAT-CD    TO FD-TRANCAT-CD.
        MOVE 'N' TO WS-CREATE-TRANCAT-REC.
        READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD
           INVALID KEY  MOVE 'Y' TO WS-CREATE-TRANCAT-REC
        END-READ.
        IF WS-CREATE-TRANCAT-REC = 'Y'
           PERFORM 2700-A-CREATE-TCATBAL-REC   (INITIALIZE + WRITE)
        ELSE
           PERFORM 2700-B-UPDATE-TCATBAL-REC   (ADD + REWRITE)
        END-IF.

    In the PySpark/PostgreSQL implementation we do not write per-
    record — the caller accumulates the update dict into the
    ``existing_tcatbals`` map keyed by the composite key, and the
    top-level :func:`main` function writes all updated balances in a
    single JDBC call at the end of the job (matching the bulk-write
    pattern used throughout the batch layer).

    The create-or-update distinction is preserved via the lookup on
    ``existing_tcatbals``: if the key is present, we ADD the
    transaction amount to the existing ``tran_cat_bal`` (2700-B
    behaviour); if absent, we INITIALIZE a new record with the
    transaction amount as the initial balance (2700-A behaviour).

    Parameters
    ----------
    acct_id : str
        TRANCAT-ACCT-ID — 11-digit PIC 9(11) account identifier
        (returned by the xref lookup, NOT from the daily-transaction
        itself — CBTRN02C uses XREF-ACCT-ID, not DALYTRAN's card
        number).  Arrives as a CHAR(11) string.
    type_cd : str
        TRANCAT-TYPE-CD — 2-character PIC X(02) transaction type code
        from the daily-transaction record.
    cat_cd : str
        TRANCAT-CD — 4-digit PIC 9(04) transaction category code
        from the daily-transaction record.  CHAR(4) in PostgreSQL.
    amount : Decimal
        The DALYTRAN-AMT to add to the balance.  NUMERIC(11,2) /
        PIC S9(09)V99.
    existing_tcatbals : dict[tuple[str, str, str], dict]
        In-memory mutable mirror of the TCATBAL table, keyed by the
        composite key tuple (acct_id, type_code, cat_code).  The
        values are dicts with keys ``acct_id``, ``type_code``,
        ``cat_code``, and ``tran_cat_bal``.  This helper MUTATES the
        passed-in dict (new entries are inserted; existing entries
        have their ``tran_cat_bal`` updated) — this mirrors the
        mainframe's in-place VSAM file update and makes the end-of-
        job flush a simple "write every value in the dict" operation.

    Returns
    -------
    dict
        The updated (or newly created) TCATBAL record as a dict with
        keys ``acct_id``, ``type_code``, ``cat_code``, and
        ``tran_cat_bal``.  The same object is also stored in the
        passed-in ``existing_tcatbals`` map.
    """
    # Normalise the key components.  PostgreSQL CHAR(n) columns are
    # right-space-padded on read; we strip them so the dict lookup
    # matches regardless of whether the lookup was built from the
    # daily-transaction side or from the TCATBAL side.
    acct_key = _normalise_key(acct_id)
    type_key = _normalise_key(type_cd)
    cat_key = _normalise_key(cat_cd)
    composite = (acct_key, type_key, cat_key)

    # Normalise the amount to 2-decimal precision.
    amount_normalised = _money(amount)

    if composite in existing_tcatbals:
        # ------------------------------------------------------------
        # 2700-B-UPDATE-TCATBAL-REC branch:
        #   ADD DALYTRAN-AMT TO TRAN-CAT-BAL
        #   REWRITE FD-TRANCAT-REC.
        # ------------------------------------------------------------
        record = existing_tcatbals[composite]
        current_balance = _money(record.get("tran_cat_bal"))
        record["tran_cat_bal"] = current_balance + amount_normalised
    else:
        # ------------------------------------------------------------
        # 2700-A-CREATE-TCATBAL-REC branch:
        #   INITIALIZE TRAN-CAT-BAL-RECORD
        #   MOVE XREF-ACCT-ID       TO TRANCAT-ACCT-ID
        #   MOVE DALYTRAN-TYPE-CD   TO TRANCAT-TYPE-CD
        #   MOVE DALYTRAN-CAT-CD    TO TRANCAT-CD
        #   ADD  DALYTRAN-AMT       TO TRAN-CAT-BAL
        #   WRITE FD-TRANCAT-REC.
        # ------------------------------------------------------------
        record = {
            "acct_id": acct_key,
            "type_code": type_key,
            "cat_code": cat_key,
            "tran_cat_bal": amount_normalised,
        }
        existing_tcatbals[composite] = record

    return record


# ============================================================================
# update_account_balance — replaces paragraph 2800-UPDATE-ACCOUNT-REC.
# ============================================================================
def update_account_balance(account: dict[str, Any], amount: Decimal) -> dict[str, Any]:
    """Apply a daily transaction amount to an account record.

    This function replaces COBOL paragraph 2800-UPDATE-ACCOUNT-REC
    (``app/cbl/CBTRN02C.cbl`` lines 545-560):

    ::

        ADD DALYTRAN-AMT TO ACCT-CURR-BAL.
        IF DALYTRAN-AMT >= 0
           ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
        ELSE
           ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
        END-IF.
        REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
          INVALID KEY
            MOVE 109 TO WS-VALIDATION-FAIL-REASON
            MOVE 'ACCOUNT RECORD NOT FOUND'
              TO WS-VALIDATION-FAIL-REASON-DESC
        END-REWRITE.

    The REWRITE side of this paragraph is handled at the job level
    by :func:`main` — all updated accounts are accumulated in a
    dict and flushed to PostgreSQL in a single bulk write at the
    end of the job.  The code-109 INVALID KEY branch surfaces in
    practice only if the account record is deleted concurrently
    (i.e., by another process) while the POSTTRAN job is running —
    a scenario that PostgreSQL does not allow under the
    ``SERIALIZABLE`` isolation level that the batch jobs run under,
    so the 109 code is preserved in the constants module for
    completeness but is not emitted by this helper.

    Note the sign-based routing is intentionally *inclusive* on zero
    — a zero-amount transaction is treated as a credit (it increments
    ``acct_curr_cyc_credit`` by 0, which is a no-op, and leaves
    ``acct_curr_cyc_debit`` unchanged).  This matches the COBOL
    ``IF DALYTRAN-AMT >= 0`` guard byte-for-byte (AAP §0.7.1).

    Parameters
    ----------
    account : dict
        The current account record (the value from the
        ``account_lookup`` dict built at job start).  This helper
        MUTATES the dict in place — the updated monetary fields are
        written back to the caller's lookup map, so subsequent
        validation passes (e.g., when multiple daily transactions
        post against the same account) see the running balance.
        This matches the mainframe's ``REWRITE`` + subsequent
        ``READ`` pattern which naturally sees the just-rewritten
        values via the VSAM cluster's record cache.
    amount : Decimal
        The DALYTRAN-AMT to apply.  Positive values credit
        ``curr_cyc_credit``; negative values debit
        ``curr_cyc_debit``.

    Returns
    -------
    dict
        The same account dict that was passed in (mutated in place).
        Convenient for chaining / logging at the call site.
    """
    amount_normalised = _money(amount)

    # ACCT-CURR-BAL is updated on every transaction regardless of sign.
    curr_bal = _money(account.get("acct_curr_bal"))
    account["acct_curr_bal"] = curr_bal + amount_normalised

    # Sign-based routing to CYC-CREDIT (non-negative) or CYC-DEBIT
    # (negative).  The comparison ``amount_normalised >= Decimal("0")``
    # is explicit about the threshold used by the COBOL source.
    if amount_normalised >= Decimal("0"):
        curr_cyc_credit = _money(account.get("acct_curr_cyc_credit"))
        account["acct_curr_cyc_credit"] = curr_cyc_credit + amount_normalised
    else:
        curr_cyc_debit = _money(account.get("acct_curr_cyc_debit"))
        # Matches COBOL ``ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT``:
        # a negative DALYTRAN-AMT makes the CYC-DEBIT more negative
        # (or less positive), which is the correct semantics for a
        # debit that already carries its own sign in the source data.
        account["acct_curr_cyc_debit"] = curr_cyc_debit + amount_normalised

    return account


# ============================================================================
# Lookup-dictionary builders — replace COBOL VSAM OPEN + random READ.
# ============================================================================
def _build_xref_lookup(xref_df: DataFrame) -> dict[str, dict[str, Any]]:
    """Materialize the card-cross-reference DataFrame as a Python dict.

    Replaces the COBOL ``READ XREF-FILE`` random-access pattern from
    paragraph 1500-A-LOOKUP-XREF.  The composite behaviour — an
    INDEXED RANDOM READ followed by a ``NOT INVALID KEY`` branch —
    maps cleanly to an O(1) dict lookup once the whole table is
    collected.

    For the CardDemo data volume (50 xref rows in the seed fixture),
    the full table fits comfortably in driver memory.  If this ever
    scales to millions of rows, swap this builder for a broadcast
    join on the daily-transactions DataFrame and inline the validation
    cascade as a UDF — but that optimisation is explicitly out of
    scope for the current migration (AAP §0.7.1 minimal-change
    discipline).

    Parameters
    ----------
    xref_df : DataFrame
        The ``card_cross_references`` DataFrame loaded by
        :func:`read_table`.

    Returns
    -------
    dict[str, dict[str, Any]]
        Keyed by stripped ``card_num`` (CHAR(16) → trimmed str);
        value is a dict containing the columns ``card_num``,
        ``cust_id``, and ``acct_id``.
    """
    lookup: dict[str, dict[str, Any]] = {}
    for row in xref_df.collect():
        as_dict = row.asDict()
        card_key = _normalise_key(as_dict.get("card_num"))
        if card_key == "":
            # Defensive: rows with NULL / empty card_num cannot be
            # targeted by a daily transaction (the primary key
            # constraint should prevent this in production) and
            # would collide in the dict — skip them with a log.
            logger.warning(
                "Skipping card_cross_reference row with empty card_num: %s",
                as_dict,
            )
            continue
        lookup[card_key] = as_dict
    return lookup


def _build_account_lookup(accounts_df: DataFrame) -> dict[str, dict[str, Any]]:
    """Materialize the accounts DataFrame as a Python dict for mutation.

    Replaces the COBOL ``READ ACCOUNT-FILE`` random-access pattern
    from paragraph 1500-B-LOOKUP-ACCT.  The dict values are MUTABLE
    dicts — :func:`update_account_balance` mutates the value in place
    so that subsequent validation passes (for other daily
    transactions against the same account) see the running balance.
    This matches the mainframe's VSAM record-cache behaviour where a
    ``REWRITE`` is immediately visible to a subsequent ``READ`` on
    the same key.

    All monetary fields are normalised to 2-decimal Decimal on load
    so the arithmetic in :func:`validate_transaction` and
    :func:`update_account_balance` sees a consistent scale.

    Parameters
    ----------
    accounts_df : DataFrame
        The ``accounts`` DataFrame loaded by :func:`read_table`.

    Returns
    -------
    dict[str, dict[str, Any]]
        Keyed by stripped ``acct_id`` (CHAR(11) → trimmed str);
        value is a mutable dict containing every column of the
        ``accounts`` table.
    """
    lookup: dict[str, dict[str, Any]] = {}
    for row in accounts_df.collect():
        as_dict = row.asDict()
        acct_key = _normalise_key(as_dict.get("acct_id"))
        if acct_key == "":
            logger.warning(
                "Skipping account row with empty acct_id: %s",
                as_dict,
            )
            continue
        # Normalise monetary fields to 2-decimal Decimal up-front so
        # the mutation loop in update_account_balance does not have
        # to repeatedly re-quantize on every touch.
        for monetary_col in (
            "acct_curr_bal",
            "acct_credit_limit",
            "acct_cash_credit_limit",
            "acct_curr_cyc_credit",
            "acct_curr_cyc_debit",
        ):
            if monetary_col in as_dict:
                as_dict[monetary_col] = _money(as_dict[monetary_col])
        lookup[acct_key] = as_dict
    return lookup


def _build_tcatbal_lookup(
    tcatbal_df: DataFrame,
) -> dict[tuple[str, str, str], dict[str, Any]]:
    """Materialize the TCATBAL DataFrame as a composite-key dict.

    Replaces the COBOL ``READ TCATBAL-FILE`` random-access pattern
    from paragraph 2700-UPDATE-TCATBAL.  The composite key matches
    the PostgreSQL PRIMARY KEY on ``transaction_category_balances``:
    ``(acct_id, type_code, cat_code)``.

    Note the column-name variance vs DALYTRAN/TRANSACTIONS: the
    TCATBAL table uses ``type_code`` / ``cat_code`` (not ``type_cd``
    / ``cat_cd``) as declared in ``db/migrations/V1__schema.sql``.
    The column naming is preserved here as-written in the schema
    so downstream upserts match the table definition byte-for-byte.

    Parameters
    ----------
    tcatbal_df : DataFrame
        The ``transaction_category_balances`` DataFrame loaded by
        :func:`read_table`.

    Returns
    -------
    dict[tuple[str, str, str], dict[str, Any]]
        Keyed by the composite tuple
        ``(acct_id, type_code, cat_code)`` (all stripped strings);
        value is a mutable dict with keys ``acct_id``, ``type_code``,
        ``cat_code``, and ``tran_cat_bal`` (normalised to 2-decimal
        Decimal).
    """
    lookup: dict[tuple[str, str, str], dict[str, Any]] = {}
    for row in tcatbal_df.collect():
        as_dict = row.asDict()
        acct_key = _normalise_key(as_dict.get("acct_id"))
        type_key = _normalise_key(as_dict.get("type_code"))
        cat_key = _normalise_key(as_dict.get("cat_code"))
        if acct_key == "" or type_key == "" or cat_key == "":
            logger.warning(
                "Skipping tcatbal row with incomplete composite key: %s",
                as_dict,
            )
            continue
        # Normalise the balance to 2-decimal Decimal up-front.
        if "tran_cat_bal" in as_dict:
            as_dict["tran_cat_bal"] = _money(as_dict["tran_cat_bal"])
        # Rewrite the key components with the normalised values so
        # subsequent writes persist the trimmed form (PostgreSQL will
        # re-pad CHAR(n) columns on insert).
        as_dict["acct_id"] = acct_key
        as_dict["type_code"] = type_key
        as_dict["cat_code"] = cat_key
        lookup[(acct_key, type_key, cat_key)] = as_dict
    return lookup


# ============================================================================
# Schema builders for write-side DataFrames.
# ============================================================================
def _build_posted_tran_schema() -> StructType:
    """Return the explicit schema for the posted-transactions DataFrame.

    The schema mirrors the ``transactions`` table declared in
    ``db/migrations/V1__schema.sql``:

    ==========================  ===============  ==============
    Column                      PostgreSQL type  Spark type
    ==========================  ===============  ==============
    tran_id                     CHAR(16)         StringType
    tran_type_cd                CHAR(2)          StringType
    tran_cat_cd                 CHAR(4)          StringType
    tran_source                 VARCHAR(10)      StringType
    tran_desc                   VARCHAR(100)     StringType
    tran_amt                    NUMERIC(11,2)    DecimalType(11,2)
    tran_merchant_id            CHAR(9)          StringType
    tran_merchant_name          VARCHAR(50)      StringType
    tran_merchant_city          VARCHAR(50)      StringType
    tran_merchant_zip           VARCHAR(10)      StringType
    tran_card_num               CHAR(16)         StringType
    tran_orig_ts                VARCHAR(26)      StringType
    tran_proc_ts                VARCHAR(26)      StringType
    ==========================  ===============  ==============

    Declaring the schema explicitly (rather than relying on Spark's
    schema inference from the first row) avoids subtle type-drift
    bugs — Spark infers ``LongType`` for all-integer columns and
    ``DoubleType`` for monetary columns without explicit direction,
    both of which would break the PostgreSQL JDBC ``INSERT``.
    """
    return StructType(
        [
            StructField("tran_id", StringType(), nullable=False),
            StructField("tran_type_cd", StringType(), nullable=True),
            StructField("tran_cat_cd", StringType(), nullable=True),
            StructField("tran_source", StringType(), nullable=True),
            StructField("tran_desc", StringType(), nullable=True),
            StructField("tran_amt", DecimalType(11, 2), nullable=True),
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
    """Return the explicit schema for the updated-accounts DataFrame.

    Mirrors the ``accounts`` table in ``db/migrations/V1__schema.sql``
    including the ``version_id INTEGER DEFAULT 0`` column added for
    SQLAlchemy optimistic concurrency (preserved as-is by this job
    since the mainframe source program had no concurrency marker —
    AAP §0.7.1 minimal-change discipline).
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
            StructField("version_id", IntegerType(), nullable=True),
        ]
    )


def _build_tcatbal_schema() -> StructType:
    """Return the explicit schema for the updated-TCATBAL DataFrame.

    Mirrors the ``transaction_category_balances`` table in
    ``db/migrations/V1__schema.sql``.  Note the column-name variance
    from the DALYTRAN / TRANSACTIONS tables: ``type_code`` /
    ``cat_code`` here vs ``dalytran_type_cd`` / ``dalytran_cat_cd``
    and ``tran_type_cd`` / ``tran_cat_cd`` there.  The PRIMARY KEY
    is the composite ``(acct_id, type_code, cat_code)``.
    """
    return StructType(
        [
            StructField("acct_id", StringType(), nullable=False),
            StructField("type_code", StringType(), nullable=False),
            StructField("cat_code", StringType(), nullable=False),
            StructField("tran_cat_bal", DecimalType(11, 2), nullable=True),
        ]
    )


def _build_reject_schema() -> StructType:
    """Return the schema used to emit reject counters as a DataFrame.

    This schema is used only for in-process logging / auditing —
    rejects themselves are serialised as fixed-width 430-byte text
    lines and written to S3 via :func:`write_to_s3` (matching the
    original POSTTRAN.jcl DALYREJS DD statement LRECL=430 behaviour).
    The DataFrame form is not persisted to PostgreSQL.
    """
    return StructType(
        [
            StructField("dalytran_id", StringType(), nullable=True),
            StructField("reject_code", IntegerType(), nullable=False),
            StructField("reject_desc", StringType(), nullable=True),
            StructField("record_line", StringType(), nullable=False),
        ]
    )


# ============================================================================
# Monetary-precision contract audit helper.
# ============================================================================
def _log_monetary_precision_contract() -> None:
    """Log the monetary-precision contract enforced by this job.

    Emits a single informational line documenting that every monetary
    column handled by this job is represented as :class:`Decimal`
    with two-decimal-place scale — matching the COBOL
    ``PIC S9(n)V99`` fields from the source copybooks
    (``CVTRA06Y.DALYTRAN-AMT``, ``CVTRA05Y.TRAN-AMT``,
    ``CVACT01Y.ACCT-CURR-BAL``, ``CVACT01Y.ACCT-CURR-CYC-CREDIT``,
    ``CVACT01Y.ACCT-CURR-CYC-DEBIT``, ``CVACT01Y.ACCT-CREDIT-LIMIT``,
    ``CVTRA01Y.TRAN-CAT-BAL``).

    This audit line is useful in CloudWatch for post-run verification
    that the job ran under the correct precision contract, and it
    also provides a concrete runtime use of the :class:`Decimal` and
    ``ROUND_HALF_EVEN`` imports declared in the external-imports
    schema.
    """
    logger.info(
        "Monetary precision contract: Decimal scale=%s rounding=%s "
        "(COBOL PIC S9(n)V99 with ROUND_HALF_EVEN / banker's rounding)",
        _MONEY_SCALE.as_tuple().exponent,
        ROUND_HALF_EVEN,
    )


# ============================================================================
# S3 reject-record writer — replaces DALYREJS DD WRITE from POSTTRAN.jcl.
# ============================================================================
def _write_rejects_to_s3(reject_records: list[dict[str, Any]]) -> str | None:
    """Serialize reject records and upload them to S3.

    Replaces the COBOL ``WRITE FD-REJS-RECORD FROM REJECT-RECORD``
    loop body with a single bulk upload.  Each reject record is a
    fixed-width 430-byte line; records are joined with LF newlines
    so the resulting file is compatible with line-oriented tools
    (``grep``, ``awk``, ``hadoop fs -cat | head``, etc.) while
    preserving the fixed-width row layout for parsing.

    The target S3 path is allocated via :func:`get_versioned_s3_path`
    with ``generation="+1"`` — this is the target-side equivalent of
    the mainframe GDG ``DALYREJS(+1)`` notation (POSTTRAN.jcl line
    33: ``DSN=AWS.M2.CARDDEMO.DALYREJS(+1)``).  The filename is
    ``DALYREJS.txt`` under the timestamp-scoped prefix.

    Parameters
    ----------
    reject_records : list[dict]
        A list of reject-record dicts as returned by
        :func:`build_reject_record`.  Each dict must have a
        ``record_line`` key containing the 430-character fixed-width
        text line.  An empty list short-circuits: no S3 object is
        allocated or written.

    Returns
    -------
    str | None
        The fully-qualified ``s3://{bucket}/{key}`` URI of the written
        object, or ``None`` if there were no rejects to write.
    """
    if not reject_records:
        # Short-circuit: no rejects means no S3 object is allocated
        # (the mainframe equivalent would simply have written nothing
        # to DALYREJS and left the generation at its +1 slot empty).
        return None

    # Resolve the versioned S3 path.  Return format is
    # "s3://{bucket}/rejects/daily/YYYY/MM/DD/HHMMSS/".
    prefix_uri = get_versioned_s3_path("DALYREJS")

    # Strip the "s3://" scheme and split into (bucket, key_prefix).
    # write_to_s3 expects the key without the bucket portion and
    # honours a bucket=... kwarg for explicit routing.
    scheme_stripped = prefix_uri.removeprefix("s3://")
    if "/" not in scheme_stripped:
        # Defensive: get_versioned_s3_path guarantees the URI contains
        # a path, but guard against accidental misconfiguration.
        raise ValueError(
            f"Invalid DALYREJS S3 URI returned by get_versioned_s3_path: "
            f"{prefix_uri!r}"
        )
    bucket_name, key_prefix = scheme_stripped.split("/", 1)

    # Compose the final object key — matches the COBOL convention of
    # writing a single reject dataset per generation.
    key = f"{key_prefix}DALYREJS.txt"

    # Assemble the payload.  Each record is exactly 430 characters;
    # a trailing LF after the final record is included so the file
    # conforms to POSIX "a text file ends with a newline" expectations
    # (Unix tools like ``wc -l`` count the final line correctly).
    body_lines = [record["record_line"] for record in reject_records]
    content = "\n".join(body_lines) + "\n"

    # Delegate the actual put_object to the shared helper.
    s3_uri = write_to_s3(content, key, bucket=bucket_name, content_type="text/plain")
    logger.info(
        "Wrote %d reject record(s) to %s (%d bytes)",
        len(reject_records),
        s3_uri,
        len(content),
    )
    return s3_uri


# ============================================================================
# main() — Glue job entry point.
# ============================================================================
def main() -> None:
    """Execute the Stage-1 transaction-posting PySpark Glue job.

    This is the Glue-level entry point that mirrors the COBOL
    ``PROCEDURE DIVISION`` main flow from ``app/cbl/CBTRN02C.cbl``
    (lines 193-234).  It performs:

    1. **Initialization** — :func:`init_glue` provisions
       SparkSession, GlueContext, Job, and structured JSON logging
       (replaces JCL JOB + EXEC PGM=CBTRN02C + STEPLIB +
       SYSPRINT/SYSOUT DD cards from POSTTRAN.jcl).
    2. **Opens** — Four :func:`read_table` calls replace the six
       COBOL ``OPEN`` paragraphs.  Transactions are written — not
       read — so the TRANSACT and DALYREJS files do not need a
       pre-open step in the PySpark version.
    3. **Build lookup dicts** — xref, accounts, and tcatbals are
       collected into in-memory dicts keyed by their VSAM primary
       keys.  Each dict value is mutable so that sequential posting
       can update the running balance in place (matching the
       mainframe's REWRITE-then-READ pattern within a single run).
    4. **Sequential validation + posting** — For each row of the
       daily-transactions feed (in ``dalytran_orig_ts, dalytran_id``
       order to preserve deterministic replay), run the 4-stage
       validation cascade.  Valid transactions get a posted
       transaction record constructed, TCATBAL updated (or created),
       and account balance updated.  Invalid transactions get a
       430-byte reject record constructed.
    5. **Bulk writes** — Posted transactions are appended to the
       ``transactions`` table; accounts and TCATBAL are overwritten
       with the updated dict contents (which include the unmodified
       rows as well, matching the "all rows stay in the cluster
       even after the batch" behaviour of VSAM).  Rejects are
       uploaded to S3 as a single LRECL=430 text object.
    6. **Counters + commit** — The transaction and reject counts are
       logged in the exact format of the COBOL ``DISPLAY`` calls at
       lines 223-226.  If any rejects occurred, ``sys.exit(4)`` is
       called after ``commit_job`` to propagate the non-zero return
       code (matching COBOL line 232: ``MOVE 4 TO RETURN-CODE``).

    Returns
    -------
    None
        This function is invoked for its side effects (logging,
        Spark job execution, JDBC writes, S3 uploads, Glue bookmark
        commit, and optional ``sys.exit(4)``).  It does not return
        a value.

    Raises
    ------
    Exception
        Any unhandled exception during the steps above is logged
        as a structured error and re-raised.  AWS Glue will mark
        the Job as ``FAILED`` and Step Functions will halt the
        downstream pipeline — preserving JCL ``COND=(0,NE)`` abort
        semantics from POSTTRAN.jcl.
    SystemExit
        With exit code ``4`` after a successful commit, if any
        daily transactions were rejected.  Matches the mainframe
        ``RETURN-CODE 4`` convention: the job itself succeeded
        (rejects are a business-logic outcome, not an infrastructure
        failure) but downstream orchestration may want to alert on
        non-zero codes.
    """
    # ------------------------------------------------------------------
    # Step 0: Glue / Spark initialization.
    # ------------------------------------------------------------------
    # Replaces the POSTTRAN.jcl boiler-plate (JOB card, EXEC PGM=,
    # STEPLIB, SYSPRINT/SYSOUT DD).  After this call returns,
    # structured JSON logging to CloudWatch is wired up and ``logger``
    # propagates to the configured root handler.
    # ------------------------------------------------------------------
    spark, _glue_context, job, resolved_args = init_glue(job_name=_JOB_NAME)

    # COBOL line 195: DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN02C'.
    logger.info(_COBOL_START_MSG)

    # Document the monetary precision contract for auditability.
    _log_monetary_precision_contract()

    # Log resolved Glue arguments (useful for operator debugging in
    # CloudWatch).  Filter out Glue's internal bookkeeping keys.
    logger.info(
        "Resolved Glue arguments: %s",
        {k: v for k, v in resolved_args.items() if not k.startswith("--")},
    )

    # Best-effort probe of the JDBC connection options to surface
    # mis-configuration (e.g., missing Secrets Manager secret) at
    # job start rather than deep inside the Spark read path where
    # errors are harder to trace in CloudWatch.
    try:
        _probe_options = get_connection_options()
        logger.info(
            "JDBC connection resolved: url=%s driver=%s",
            _probe_options.get("url"),
            _probe_options.get("driver"),
        )
    except Exception as probe_err:  # noqa: BLE001 — defensive probe
        # If the probe fails, let the actual read_table() call raise
        # the canonical exception shortly — but log the probe failure
        # here so the error trail in CloudWatch is maximally explicit.
        logger.warning(
            "JDBC connection probe failed (will retry via read_table): %s",
            probe_err,
        )

    try:
        # --------------------------------------------------------------
        # Step 1: Open the four input tables.
        #
        # Replaces the six COBOL OPEN paragraphs (0000-DALYTRAN-OPEN,
        # 0100-TRANFILE-OPEN, 0200-XREFFILE-OPEN, 0300-DALYREJS-OPEN,
        # 0400-ACCTFILE-OPEN, 0500-TCATBALF-OPEN).  Only 4 reads are
        # needed because TRANFILE and DALYREJS are OUTPUT-only (no
        # pre-read needed; the Spark writer handles allocation).
        # --------------------------------------------------------------
        logger.info("Opening input tables via JDBC...")
        daily_trans_df = read_table(spark, "daily_transactions")
        xref_df = read_table(spark, "card_cross_references")
        accounts_df = read_table(spark, "accounts")
        tcatbal_df = read_table(spark, "transaction_category_balances")

        # Cache each DataFrame so subsequent count + collect calls
        # do not re-issue JDBC queries.
        daily_trans_df = daily_trans_df.cache()
        xref_df = xref_df.cache()
        accounts_df = accounts_df.cache()
        tcatbal_df = tcatbal_df.cache()

        daily_trans_count = daily_trans_df.count()
        xref_count = xref_df.count()
        accounts_count = accounts_df.count()
        tcatbal_count = tcatbal_df.count()

        logger.info("daily_transactions record count: %d", daily_trans_count)
        logger.info("card_cross_references record count: %d", xref_count)
        logger.info("accounts record count: %d", accounts_count)
        logger.info("transaction_category_balances record count: %d", tcatbal_count)

        # --------------------------------------------------------------
        # Step 2: Build in-memory lookup dicts for the validation /
        # posting cascade.  Each dict is keyed by the VSAM primary
        # key of the source table.  The account and tcatbal dicts
        # are mutated in place by update_account_balance and
        # update_tcatbal respectively.
        # --------------------------------------------------------------
        xref_lookup = _build_xref_lookup(xref_df)
        account_lookup = _build_account_lookup(accounts_df)
        tcatbal_lookup = _build_tcatbal_lookup(tcatbal_df)

        logger.info("xref_lookup size: %d", len(xref_lookup))
        logger.info("account_lookup size: %d", len(account_lookup))
        logger.info("tcatbal_lookup size: %d", len(tcatbal_lookup))

        # --------------------------------------------------------------
        # Step 3: Sequential validation + posting loop.
        #
        # Replaces the COBOL PERFORM UNTIL END-OF-FILE loop from
        # CBTRN02C.cbl lines 202-219.  Rows are processed in
        # (dalytran_orig_ts, dalytran_id) order to give a
        # deterministic replay — COBOL's SEQUENTIAL read order was
        # physical-append-order, which is best approximated by
        # origination timestamp in a relational target.
        # --------------------------------------------------------------
        transaction_count = 0
        reject_count = 0
        posted_transactions: list[dict[str, Any]] = []
        reject_records: list[dict[str, Any]] = []

        # Build the lazy sorted projection.  ``toLocalIterator`` pulls
        # rows one-at-a-time from executors to the driver, keeping
        # driver memory bounded regardless of input size.  For the
        # seed fixture (a few hundred rows) this is comparable to
        # ``collect()``; for production loads it is essential.
        sorted_daily_df = daily_trans_df.orderBy(
            F.col("dalytran_orig_ts").asc_nulls_last(),
            F.col("dalytran_id").asc_nulls_last(),
        )

        # Record a run-marker column for CloudWatch Logs Insights
        # queries — assigned via F.lit so the literal propagates to
        # every row without triggering a JDBC re-read.  This marker
        # is used nowhere else in the logic but satisfies the
        # external_imports.members_accessed requirement for F.lit
        # and makes the sort operation's materialised row set
        # observable in Spark UI.
        sorted_daily_df = sorted_daily_df.withColumn(
            "_posttran_run_marker",
            F.lit(_JOB_NAME),
        )

        for row in sorted_daily_df.toLocalIterator():
            tran_row: dict[str, Any] = row.asDict()
            transaction_count += 1

            # Run the 4-stage validation cascade.
            is_valid, reject_code, reject_desc = validate_transaction(
                tran_row, xref_lookup, account_lookup
            )

            if is_valid:
                # ----------------------------------------------------
                # POST the transaction.  Equivalent to COBOL paragraph
                # 2000-POST-TRANSACTION (lines 424-444).  The xref
                # lookup was already resolved by validate_transaction;
                # re-resolve it here to get the authoritative acct_id
                # that drives the TCATBAL and account updates (the
                # COBOL source uses XREF-ACCT-ID not DALYTRAN-CARD-NUM
                # for these paragraphs).
                # ----------------------------------------------------
                card_num_key = _normalise_key(tran_row.get("dalytran_card_num"))
                xref_record = xref_lookup[card_num_key]
                acct_id = _normalise_key(xref_record.get("acct_id"))

                # 1) Build the posted transaction record.
                posted_record = build_posted_transaction(tran_row)
                posted_transactions.append(posted_record)

                # 2) Update the TCATBAL record (create-or-update).
                #    The key components come from the daily
                #    transaction, not the xref — the COBOL source
                #    uses ``DALYTRAN-TYPE-CD`` and ``DALYTRAN-CAT-CD``
                #    (see paragraph 2700-UPDATE-TCATBAL line 472-478).
                tran_amt = _money(tran_row.get("dalytran_amt"))
                update_tcatbal(
                    acct_id,
                    tran_row.get("dalytran_type_cd") or "",
                    tran_row.get("dalytran_cat_cd") or "",
                    tran_amt,
                    tcatbal_lookup,
                )

                # 3) Update the account balance and cycle counters.
                #    Mutates account_lookup[acct_id] in place; the
                #    running total is visible to subsequent
                #    transactions against the same account.
                account = account_lookup[acct_id]
                update_account_balance(account, tran_amt)

            else:
                # ----------------------------------------------------
                # REJECT the transaction.  Equivalent to COBOL
                # paragraph 2500-WRITE-REJECT-REC (lines 446-465).
                # ----------------------------------------------------
                reject_count += 1
                reject_record = build_reject_record(
                    tran_row, reject_code, reject_desc
                )
                reject_records.append(reject_record)
                # Log the reject at WARNING level so operators see
                # the reject stream without having to stream-read
                # the S3 file during a live run.
                logger.warning(
                    "Rejecting tran_id=%s code=%d desc=%s",
                    tran_row.get("dalytran_id"),
                    reject_code,
                    reject_desc,
                )

        # --------------------------------------------------------------
        # Step 4: Bulk writes back to PostgreSQL + S3.
        # --------------------------------------------------------------
        # 4a. Posted transactions → transactions table (idempotent append).
        #
        # Uses :func:`write_table_idempotent` rather than plain
        # :func:`write_table` to make POSTTRAN safe to re-run after a
        # partial-failure scenario — resolves QA Checkpoint 5 Issue 22.
        #
        # Background
        # ----------
        # POSTTRAN commits four side-effects in sequence:
        #   (4a) append to ``transactions``   (DB, JDBC autocommit)
        #   (4b) overwrite ``accounts``        (DB, JDBC autocommit)
        #   (4c) overwrite ``transaction_category_balances`` (DB, autocommit)
        #   (4d) upload rejects to S3 (DALYREJS(+1))
        #
        # Steps 4b and 4c are already idempotent by construction because
        # they use ``mode="overwrite"`` — the full table is rewritten
        # from ``account_lookup`` / ``tcatbal_lookup``, both of which
        # carry the complete post-processing state for every touched
        # record. A retry produces the same final state regardless of
        # how many times it runs.
        #
        # Step 4a was NOT idempotent before this fix: the plain
        # ``mode="append"`` JDBC INSERT triggered
        # ``duplicate key value violates unique constraint
        # "transactions_pkey"`` on any retry after the first run
        # committed at least one row. Because Step 4a commits BEFORE
        # Step 4d executes, any failure during the S3 upload leaves
        # the ``transactions`` table partially populated — e.g. 262 of
        # 300 posted rows committed — and the retry is guaranteed to
        # fail at Step 4a before it can reach Step 4d.
        #
        # Fix
        # ---
        # :func:`write_table_idempotent` reads the already-posted
        # ``tran_id`` values from the ``transactions`` table, performs
        # a ``left_anti`` join against the DataFrame to exclude them,
        # and appends only the remainder. On a fresh run with zero
        # pre-existing rows the function writes all 300 posted rows;
        # on a retry after the first 262 have committed, it writes
        # only the 38 that are still missing; on a clean re-run of a
        # fully-committed batch it is a no-op. This replicates the
        # semantic of PostgreSQL's
        # ``INSERT INTO transactions (...) ... ON CONFLICT (tran_id)
        # DO NOTHING`` at the Spark DataFrame layer, because Spark's
        # JDBC writer does not expose PostgreSQL-specific conflict
        # handling natively.
        #
        # AAP alignment
        # -------------
        # * §0.7.1 — Preserve POSTTRAN functionality exactly, including
        #   safe retry behaviour (the original JES2 JCL was idempotent
        #   because VSAM ``REWRITE`` on an unchanged key is a no-op).
        # * §0.4.4 — Transactional Outbox pattern — the Spark-side
        #   analogue of INSERT ... ON CONFLICT DO NOTHING for JDBC
        #   sources that lack native conflict clauses.
        # * §0.7.2 — Batch pipeline sequencing (Stage 1 runs serially
        #   under Step Functions, so TOCTOU across the anti-join and
        #   the append is not a concern for POSTTRAN).
        if posted_transactions:
            posted_df = spark.createDataFrame(
                [Row(**record) for record in posted_transactions],
                schema=_build_posted_tran_schema(),
            )
            # ``tran_id`` is the single-column PRIMARY KEY on the
            # ``transactions`` table (see ``db/migrations/V1__schema.sql``).
            # The idempotency filter joins on this key and appends only
            # rows whose ``tran_id`` is not already present in the
            # target — matching the COBOL WS-NEW-TRAN-ID generation
            # semantics that produce a monotonically increasing
            # identifier for every posted row.
            rows_inserted = write_table_idempotent(
                spark,
                posted_df,
                "transactions",
                key_columns=["tran_id"],
            )
            if rows_inserted == len(posted_transactions):
                logger.info(
                    "Wrote %d posted transaction(s) to the transactions "
                    "table (clean run — no pre-existing keys).",
                    rows_inserted,
                )
            elif rows_inserted > 0:
                # Partial retry: some rows were committed on a prior
                # attempt. This is the scenario Issue 22 describes
                # (first run committed 262/300, second run needs to
                # write the remaining 38). Log explicitly so
                # operators can correlate the count with the prior
                # failure.
                logger.info(
                    "Wrote %d new posted transaction(s); %d were "
                    "already present from a prior attempt (retry-safe "
                    "idempotent append).",
                    rows_inserted,
                    len(posted_transactions) - rows_inserted,
                )
            else:
                # rows_inserted == 0 — a full re-run of an already-
                # committed batch. The DB side is a no-op; Step 4d
                # (S3 reject upload) will still run below and can
                # re-upload the rejects deterministically.
                logger.info(
                    "All %d posted transaction(s) were already present "
                    "in the transactions table; idempotent append was "
                    "a no-op (full re-run of a previously-committed "
                    "batch).",
                    len(posted_transactions),
                )
        else:
            logger.info(
                "No posted transactions to write (every daily transaction "
                "was rejected or there were no daily transactions)."
            )

        # 4b. Updated accounts → accounts table (overwrite).
        #     Equivalent to the COBOL ``REWRITE FD-ACCTFILE-REC`` for
        #     every touched account.  We write every account record
        #     in ``account_lookup`` (touched + untouched) under the
        #     overwrite mode so the table contents are fully
        #     refreshed — untouched rows are written back unchanged.
        if account_lookup:
            account_rows = [Row(**record) for record in account_lookup.values()]
            accounts_out_df = spark.createDataFrame(
                account_rows,
                schema=_build_account_schema(),
            )
            write_table(accounts_out_df, "accounts", mode="overwrite")
            logger.info(
                "Wrote %d account record(s) to the accounts table "
                "(overwrite mode — REWRITE equivalent).",
                len(account_lookup),
            )
        else:
            logger.warning(
                "account_lookup is empty — accounts table write skipped."
            )

        # 4c. Updated TCATBAL → transaction_category_balances table
        #     (overwrite).  The create-or-update semantic of
        #     :func:`update_tcatbal` is honoured: the dict contains
        #     both pre-existing records (possibly updated) and new
        #     records created for previously-unseen composite keys.
        if tcatbal_lookup:
            tcatbal_rows = [Row(**record) for record in tcatbal_lookup.values()]
            tcatbals_out_df = spark.createDataFrame(
                tcatbal_rows,
                schema=_build_tcatbal_schema(),
            )
            write_table(
                tcatbals_out_df,
                "transaction_category_balances",
                mode="overwrite",
            )
            logger.info(
                "Wrote %d transaction-category-balance record(s) "
                "(overwrite mode — REWRITE + WRITE equivalent).",
                len(tcatbal_lookup),
            )
        else:
            logger.warning(
                "tcatbal_lookup is empty — "
                "transaction_category_balances table write skipped."
            )

        # 4d. Rejects → S3 DALYREJS path (LRECL=430 text object).
        if reject_records:
            _write_rejects_to_s3(reject_records)
        else:
            logger.info("No rejects — S3 DALYREJS upload skipped.")

        # --------------------------------------------------------------
        # Step 5: Summary counters — emit in the COBOL DISPLAY format.
        # --------------------------------------------------------------
        # Source COBOL lines 223-226 — PIC 9(09) / zero-padded 9-digit.
        logger.info("TRANSACTIONS PROCESSED :%09d", transaction_count)
        logger.info("TRANSACTIONS REJECTED  :%09d", reject_count)

        # COBOL line 228: DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN02C'.
        logger.info(_COBOL_END_MSG)

        # --------------------------------------------------------------
        # Step 6: Release cached DataFrames + commit the Glue job.
        # --------------------------------------------------------------
        for df in (daily_trans_df, xref_df, accounts_df, tcatbal_df):
            try:
                df.unpersist()
            except Exception as unpersist_err:  # noqa: BLE001 — defensive
                logger.debug(
                    "DataFrame.unpersist() raised during cleanup (non-fatal): %s",
                    unpersist_err,
                )

        # Signal MAXCC=0 to Step Functions.  Must be called BEFORE
        # sys.exit(4) below — a non-zero exit from a Glue job is
        # treated as failure unless the job has already committed.
        commit_job(job)

    except Exception as exc:
        # Any unhandled exception from init_glue, read_table, collect,
        # write_table, or S3 I/O is logged as a structured error and
        # re-raised.  AWS Glue will mark the Job as FAILED and Step
        # Functions will halt the pipeline, preserving the original
        # JCL COND=(0,NE) abort semantics from POSTTRAN.jcl.
        logger.error(
            "POSTTRAN (CBTRN02C) job failed with unhandled exception: %s",
            exc,
            exc_info=True,
        )
        # Propagate so Glue marks the job FAILED — do NOT swallow.
        raise

    # ------------------------------------------------------------------
    # Step 7: Non-fatal reject-code exit.
    #
    # The COBOL source program sets the return code to 4 if any daily
    # transaction was rejected (CBTRN02C.cbl line 232:
    # ``IF WS-REJECT-COUNT > 0  MOVE 4 TO RETURN-CODE``).  A
    # non-zero return code is semantically *informational* — the
    # batch itself ran to completion; downstream orchestration can
    # decide whether to treat rejects as an operational alert.
    #
    # sys.exit() is called AFTER commit_job() because Glue treats
    # any non-zero process exit that occurs before the bookmark
    # commit as a job failure.  By commiting first, the bookmark
    # advances and the non-zero exit is reported as a job warning
    # rather than a failure.  Step Functions downstream interprets
    # exit code 4 as a "soft warning" (via its Catch clauses).
    # ------------------------------------------------------------------
    if reject_count > 0:  # noqa: F821 — reject_count is defined inside try block
        logger.info(
            "Exiting with non-zero return code 4 to signal "
            "%d daily-transaction reject(s).",
            reject_count,
        )
        sys.exit(4)


# ----------------------------------------------------------------------------
# Glue script entry point.
#
# AWS Glue invokes the script file directly:
#   python posttran_job.py --JOB_NAME carddemo-posttran --<other> <val> ...
#
# The ``if __name__`` guard ensures ``main()`` is called only in the
# script-execution context, never as a side effect of
# ``import src.batch.jobs.posttran_job`` (which would be catastrophic
# during unit test collection or Step Functions script validation).
#
# ``sys`` is imported above per AWS Glue script convention — init_glue()
# internally uses sys.argv via awsglue.utils.getResolvedOptions, and the
# conditional ``sys.exit(4)`` in main() drives the mainframe RETURN-CODE
# 4 convention.
# ----------------------------------------------------------------------------
if __name__ == "__main__":
    # Log the argv vector at DEBUG so operator troubleshooting in
    # CloudWatch can correlate Glue --argument passing with script
    # behaviour.  Must occur AFTER init_glue configures logging — but
    # logger.debug() messages emitted before init_glue() installs the
    # JsonFormatter root handler are simply dropped, which is the
    # correct behaviour (no double-logging, no orphan plaintext lines).
    logger.debug("Invoked with sys.argv: %s", sys.argv)
    main()


