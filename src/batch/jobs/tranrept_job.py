# ============================================================================
# Licensed Materials - Property of AWS Blitzy CardDemo Migration Project
#
# Copyright 2012-2024 The Apache Software Foundation. Derivative work of the
# AWS Mainframe Modernization CardDemo sample (Apache License, Version 2.0).
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Source mainframe artifacts translated by this module:
#   * ``app/cbl/CBTRN03C.cbl`` - Batch transaction detail report (~500 lines,
#     PROGRAM-ID: CBTRN03C).  Reads the TRANSACT file (pre-sorted by the
#     DFSORT step in TRANREPT.jcl), enriches each record with CARDXREF,
#     TRANTYPE, and TRANCATG lookups, and writes the 133-byte report with
#     header, detail, page-total, account-total, and grand-total lines.
#   * ``app/jcl/TRANREPT.jcl`` - 85-line JES2 job driving Stage 4b.  Contains
#     STEP05R (REPROC + DFSORT filtering TRAN-PROC-DT between
#     ``'2022-01-01'`` and ``'2022-07-06'`` inclusive and sorting by
#     TRAN-CARD-NUM ascending) followed by STEP10R (EXEC PGM=CBTRN03C).
#   * ``app/cpy/CVTRA05Y.cpy`` - TRAN-RECORD layout (350 bytes), the physical
#     record format of the TRANSACT VSAM KSDS cluster.
#   * ``app/cpy/CVACT03Y.cpy`` - CARD-XREF-RECORD layout (50 bytes),
#     physical record of the CARDXREF AIX path.
#   * ``app/cpy/CVTRA03Y.cpy`` - TRAN-TYPE-RECORD layout (60 bytes),
#     physical record of the TRANTYPE KSDS.
#   * ``app/cpy/CVTRA04Y.cpy`` - TRAN-CAT-RECORD layout (60 bytes),
#     physical record of the TRANCATG KSDS (composite key: TRAN-TYPE-CD +
#     TRAN-CAT-CD).
#   * ``app/cpy/CVTRA07Y.cpy`` - Complete 133-character TRANREPT record
#     layout: REPORT-NAME-HEADER, TRANSACTION-DETAIL-REPORT,
#     TRANSACTION-HEADER-1/2, REPORT-PAGE-TOTALS, REPORT-ACCOUNT-TOTALS,
#     REPORT-GRAND-TOTALS.
# ============================================================================
"""Stage 4b -- Transaction detail report PySpark Glue job.

This module implements **Stage 4b** of the CardDemo batch pipeline on
AWS Glue 5.1 (Apache Spark 3.5.6 / Python 3.11), faithfully replacing the
monolithic mainframe job chain:

.. code-block:: text

    TRANREPT.jcl  ->  (STEP05R REPROC + DFSORT)  ->  CBTRN03C.cbl  ->  TRANREPT(+1)

Pipeline position (from AAP Section 0.4.4 - Key Architectural Decisions)::

    POSTTRAN  ->  INTCALC  ->  COMBTRAN  +->  CREASTMT  (Stage 4a -- this peer)
    (Stage 1)   (Stage 2)   (Stage 3)   +->  TRANREPT  (Stage 4b -- THIS MODULE)

Stages 4a (statement generation) and 4b (transaction report) execute in
parallel on the final fan-out of the AWS Step Functions state machine,
matching the original JCL topology where ``CREASTMT.jcl`` and
``TRANREPT.jcl`` are scheduled independently after ``COMBTRAN.jcl``.

Mainframe-to-Cloud Transformation Map
-------------------------------------

====================================  ================================================
z/OS construct                        AWS cloud substitute
====================================  ================================================
JCL EXEC PGM=CBTRN03C                 ``main()`` entry point with
                                      ``init_glue(job_name='carddemo-tranrept')``
DFSORT INCLUDE COND date filter       :func:`filter_by_date_range` using
                                      ``F.substring(tran_proc_ts, 1, 10)``
DFSORT SORT FIELDS=(TRAN-CARD-NUM,A)  ``DataFrame.orderBy(F.col('tran_card_num').asc())``
PARM-START-DATE / PARM-END-DATE       Resolved Glue arguments with defaults
                                      ``_DEFAULT_START_DATE`` / ``_DEFAULT_END_DATE``
VSAM READ INDEXED RANDOM (CARDXREF)   Left ``DataFrame.join`` on
                                      ``tran_card_num == xref.card_num``
VSAM READ INDEXED RANDOM (TRANTYPE)   Left ``DataFrame.join`` on
                                      ``tran_type_cd == trantype.type_code``
VSAM READ INDEXED RANDOM (TRANCATG)   Left ``DataFrame.join`` on composite
                                      ``(tran_type_cd, tran_cat_cd)``
COBOL WRITE FD-REPTFILE-REC PIC X(133) :func:`format_report_line` produces 133-
                                      character strings; joined by ``\\n``
WRITE REPORT-PAGE-TOTALS              :func:`format_subtotal_line` with
                                      ``"Page Total"`` label
WRITE REPORT-ACCOUNT-TOTALS           :func:`format_subtotal_line` with
                                      ``"Account Total"`` label
WRITE REPORT-GRAND-TOTALS             :func:`format_subtotal_line` with
                                      ``"Grand Total"`` label
TRANREPT(+1) GDG generation (LRECL=133) ``get_versioned_s3_path('TRANREPT')`` +
                                      ``write_to_s3`` with ``text/plain``
JES2 MAXCC / ABEND                    ``commit_job(job)`` on success; unhandled
                                      exception re-raised, triggering Step
                                      Functions failure state
====================================  ================================================

Critical business-logic preservations (AAP Section 0.7.1 -- minimal change):

* **3-level totals** -- account subtotal (``WS-ACCOUNT-TOTAL``), page subtotal
  (``WS-PAGE-TOTAL``), and grand total (``WS-GRAND-TOTAL``) are accumulated
  in ``decimal.Decimal`` with ``ROUND_HALF_EVEN`` to preserve COBOL
  ``PIC S9(n)V99`` precision.  NO floating-point arithmetic is used for
  any monetary value.
* **Card break logic** -- when ``WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM``
  (and we are not at the first record), the COBOL paragraph
  ``1120-WRITE-ACCOUNT-TOTALS`` emits an account-total line and resets
  ``WS-ACCOUNT-TOTAL`` to zero before processing the new card.  This
  behaviour is replicated here.
* **Page break logic** -- before writing each detail line, if
  ``WS-LINE-COUNTER`` is a non-zero multiple of 20
  (``FUNCTION MOD(WS-LINE-COUNTER, 20) = 0``), the COBOL program writes
  ``REPORT-PAGE-TOTALS`` and then header lines.  The page total flushes
  into ``WS-GRAND-TOTAL`` and resets to zero.
* **Date filter inclusive** -- ``TRAN-PROC-TS(1:10) >= WS-START-DATE``
  AND ``TRAN-PROC-TS(1:10) <= WS-END-DATE`` (matching DFSORT ``INCLUDE
  COND`` semantics).
* **Final-row account total quirk** -- CBTRN03C.cbl does **not** flush a
  final ``REPORT-ACCOUNT-TOTALS`` line at end-of-file; it only writes
  account totals on card-number transitions.  This exact behaviour is
  preserved.  The COBOL program DOES, however, write a final page total
  (after rolling its value into grand total) followed by the grand
  total line.
* **First-time header** -- ``WS-FIRST-TIME`` flag guards the initial
  ``1120-WRITE-HEADERS`` call which emits ``REPORT-NAME-HEADER``
  (containing the date range), ``TRANSACTION-HEADER-1`` (column
  captions), and ``TRANSACTION-HEADER-2`` (dash separator).

References
----------
* AAP Section 0.2.2 -- Batch Program Classification
* AAP Section 0.5.1 -- File-by-File Transformation Plan
* AAP Section 0.7.1 -- Refactoring-Specific Rules
* AAP Section 0.7.2 -- Special Instructions (financial precision, IAM, testing)
* AAP Section 0.7.3 -- User-Specified Implementation Rules
"""

from __future__ import annotations

# ----------------------------------------------------------------------------
# Standard library imports
# ----------------------------------------------------------------------------
# * ``logging`` replaces COBOL ``DISPLAY`` statements.  The logger emits
#   structured JSON records (configured by ``init_glue`` via
#   :class:`src.batch.common.glue_context.JsonFormatter`) which flow to
#   CloudWatch Logs and satisfy AAP Section 0.7.2's monitoring
#   requirement.
# * ``sys`` provides ``sys.argv`` access for the Glue Job runtime entry
#   point.  Unlike INTCALC / POSTTRAN which delegate full argument
#   parsing to ``init_glue``'s internal ``getResolvedOptions``,
#   TRANREPT only needs the default JOB_NAME resolution path -- the
#   optional START_DATE / END_DATE overrides are looked up through
#   ``resolved_args.get()`` with the embedded defaults
#   ``_DEFAULT_START_DATE`` / ``_DEFAULT_END_DATE`` (preserving the
#   JCL SYMNAMES values ``2022-01-01`` / ``2022-07-06``).
# * ``decimal.Decimal`` with ``ROUND_HALF_EVEN`` is the mandatory
#   arithmetic backbone for every monetary value in the 3-level totals
#   system.  COBOL ``PIC S9(n)V99`` fields are fixed-scale packed-
#   decimal values with banker's rounding on ``ROUNDED`` clauses;
#   Python's :class:`decimal.Decimal` provides exact equivalence (per
#   AAP Section 0.7.2 -- "No floating-point arithmetic is permitted
#   for any financial calculation").
# * ``typing.Any`` types the GlueContext / Job objects returned by
#   ``init_glue`` (the ``awsglue`` stubs are optional at runtime).
import logging
import sys
from decimal import ROUND_HALF_EVEN, Decimal
from typing import Any

# ----------------------------------------------------------------------------
# PySpark imports
# ----------------------------------------------------------------------------
# ``functions`` aliased as ``F`` is the canonical PySpark alias for the
# column-expression factory module.  ``DataFrame`` is imported for
# type annotations on the public helper ``filter_by_date_range``.
from pyspark.sql import DataFrame
from pyspark.sql import functions as F  # noqa: N812 - canonical PySpark alias

# ----------------------------------------------------------------------------
# Internal project imports (depends_on_files whitelist)
# ----------------------------------------------------------------------------
# All three imports are drawn exclusively from the ``depends_on_files``
# schema whitelist specified for this file.  No other internal imports
# are permitted (AAP Section 0.7.3 -- "Isolate new implementations in
# dedicated files/modules").
from src.batch.common.db_connector import read_table
from src.batch.common.glue_context import commit_job, init_glue
from src.batch.common.s3_utils import get_versioned_s3_path, write_to_s3

# ============================================================================
# JCL step banner constants
# ============================================================================
# These constants emit banner messages that mirror the IBM JES2 job log
# entries produced by the original ``TRANREPT.jcl`` during its three
# execution stages.  They provide a direct audit trail for operators
# comparing CloudWatch Logs against legacy z/OS SDSF output (AAP Section
# 0.7.2 -- "The system should be easy to monitor").
_JCL_JOB_START_MSG: str = "START OF EXECUTION OF JOB TRANREPT"
_JCL_JOB_END_MSG: str = "END OF EXECUTION OF JOB TRANREPT - MAXCC=0"
_JCL_ABEND_MSG: str = "ABENDING JOB TRANREPT"
_JCL_STEP05R_REPROC_START_MSG: str = (
    "START OF STEP05R REPROC (TRANREPT.jcl lines 23-33) - IDCAMS REPRO TRANSACT VSAM into TRANSACT.BKUP(+1)"
)
_JCL_STEP05R_REPROC_END_MSG: str = "END OF STEP05R REPROC - transactions materialized from Aurora via JDBC"
_JCL_STEP05R_SORT_START_MSG: str = (
    "START OF STEP05R SORT (TRANREPT.jcl lines 37-55) - "
    "DFSORT INCLUDE COND TRAN-PROC-DT IN [START_DATE, END_DATE] "
    "AND SORT FIELDS=(TRAN-CARD-NUM,A)"
)
_JCL_STEP05R_SORT_END_MSG: str = "END OF STEP05R SORT - filtered + sorted transactions staged for STEP10R"
_JCL_STEP10R_START_MSG: str = (
    "START OF STEP10R EXEC PGM=CBTRN03C (TRANREPT.jcl lines 59-80) - transaction detail report generation"
)
_JCL_STEP10R_END_MSG: str = "END OF STEP10R - report written to S3 (TRANREPT GDG generation)"

# ============================================================================
# Module-level configuration constants
# ============================================================================
# ``_JOB_NAME`` must match the AWS Glue Job definition name registered in
# ``infra/glue-job-configs/tranrept.json`` (AAP Section 0.5.1).
_JOB_NAME: str = "carddemo-tranrept"

# Aurora PostgreSQL table names.  These are logical names consumed by
# :func:`src.batch.common.db_connector.read_table` which internally maps
# each name through ``get_table_name`` to the physical table identifier
# defined in ``db/migrations/V1__schema.sql``.
_TABLE_TRANSACTIONS: str = "transactions"
_TABLE_XREF: str = "card_cross_references"
_TABLE_TRANTYPE: str = "transaction_types"
_TABLE_TRANCATG: str = "transaction_categories"

# GDG-equivalent S3 prefix key.  ``GDG_PATH_MAP["TRANREPT"]`` in
# ``src/batch/common/s3_utils.py`` resolves to the object-storage
# equivalent of the mainframe DSN ``AWS.M2.CARDDEMO.TRANREPT(+1)``.
_GDG_TRANREPT: str = "TRANREPT"

# Output filename appended to the versioned S3 prefix.  The mainframe
# equivalent is the DCB=(LRECL=133,RECFM=FB) attribute on the TRANREPT
# DD statement.
_OUTPUT_FILENAME: str = "TRANREPT.txt"
_CONTENT_TYPE_TEXT: str = "text/plain"

# ============================================================================
# Report layout constants (from CVTRA07Y.cpy)
# ============================================================================
# Physical record width matching ``FD-REPTFILE-REC PIC X(133)`` in
# CBTRN03C.cbl SELECT-ASSIGN clause (line 46) and the DCB LRECL=133 in
# TRANREPT.jcl (line 74).
_REPORT_LINE_WIDTH: int = 133

# Page size expressed in physical lines.  COBOL:
# ``IF FUNCTION MOD(WS-LINE-COUNTER, 20) = 0`` triggers page break.
_PAGE_SIZE: int = 20

# Width of the COBOL numeric-edit format ``PIC -ZZZ,ZZZ,ZZZ.ZZ`` (detail
# lines) and ``PIC +ZZZ,ZZZ,ZZZ.ZZ`` (subtotal lines): 1 sign + 3 + 1 +
# 3 + 1 + 3 + 1 + 2 = 16 characters.
_AMOUNT_EDIT_WIDTH: int = 16

# ============================================================================
# Default date parameters (from TRANREPT.jcl SYMNAMES)
# ============================================================================
# Matches the hard-coded DFSORT SYMNAMES values in TRANREPT.jcl lines
# 48-49: ``PARM-START-DATE,C'2022-01-01'`` and
# ``PARM-END-DATE,C'2022-07-06'``.  Operators overriding the date range
# via Glue Job parameters (``--START_DATE`` / ``--END_DATE``) supersede
# these defaults.
_DEFAULT_START_DATE: str = "2022-01-01"
_DEFAULT_END_DATE: str = "2022-07-06"

# Length of the ISO-8601 date portion embedded in the 26-character
# ``tran_proc_ts`` timestamp (``YYYY-MM-DD`` = 10 characters).  Used by
# :func:`filter_by_date_range` to extract the date prefix for comparison
# against ``start_date`` / ``end_date``.
_DATE_PREFIX_LEN: int = 10

# ============================================================================
# Decimal / financial arithmetic constants
# ============================================================================
# Zero value with two-decimal scale, used to initialize and reset the
# three running totals (``ws_account_total``, ``ws_page_total``,
# ``ws_grand_total``).
_DECIMAL_ZERO: Decimal = Decimal("0.00")

# Quantum value for :meth:`decimal.Decimal.quantize` calls that enforce
# two-decimal-place precision on all arithmetic results (matching COBOL
# ``PIC S9(n)V99`` semantics).
_DECIMAL_QUANTUM: Decimal = Decimal("0.01")

# ============================================================================
# Logger
# ============================================================================
# Module-level logger.  Once ``init_glue`` has run, this logger emits
# JSON-formatted records via the root handler configured by
# :func:`src.batch.common.glue_context._setup_logging` -- satisfying AAP
# Section 0.7.2's monitoring requirement of "structured JSON logging
# from both API and batch components".
logger: logging.Logger = logging.getLogger(__name__)


# ============================================================================
# Private helpers: COBOL numeric-edit formatting
# ============================================================================
def _format_amount_edited(amount: Decimal) -> str:
    """Format a :class:`~decimal.Decimal` as COBOL ``PIC -ZZZ,ZZZ,ZZZ.ZZ``.

    Replicates the COBOL ``MOVE TRAN-AMT TO TRAN-REPORT-AMT`` behaviour
    where ``TRAN-REPORT-AMT`` is declared ``PIC -ZZZ,ZZZ,ZZZ.ZZ`` in
    ``CVTRA07Y.cpy`` (offset 98, width 16).  The COBOL numeric-edit
    rules are:

    * Leading sign position (``-``): filled with ``-`` when the value
      is negative, filled with a SPACE when the value is zero or
      positive.  Zero never displays ``+`` for this mask (only ``-`` or
      space).
    * Zero-suppression (``Z``): leading zero digits and the grouping
      commas that lie within the leading-zero run are replaced with
      spaces.  The first non-zero digit (and every character to its
      right) prints normally.
    * Decimal point (``.``) and fractional digits (``ZZ``): always
      print.  If the absolute value is less than one unit, the
      fractional portion can still contain suppressed zeros only when
      the suppressing character reaches it; CVTRA07Y stops
      suppression at the decimal point so fractional positions always
      print their literal digits.

    Final width is exactly 16 characters -- the concatenated length of
    ``-`` + ``ZZZ`` + ``,`` + ``ZZZ`` + ``,`` + ``ZZZ`` + ``.`` +
    ``ZZ``.

    Parameters
    ----------
    amount : Decimal
        The numeric value to format.  Any :class:`decimal.Decimal` is
        accepted; internally it is quantized to two decimal places
        using ``ROUND_HALF_EVEN`` (matching COBOL ``ROUNDED``
        semantics) and the absolute value is extracted for formatting.

    Returns
    -------
    str
        A 16-character string containing the edited numeric value.

    Examples
    --------
    >>> _format_amount_edited(Decimal("1234.56"))
    '       1,234.56 '
    >>> _format_amount_edited(Decimal("0"))
    '            0.00'
    >>> _format_amount_edited(Decimal("-1234567.89"))
    '   -1,234,567.89'
    """
    # Quantize to two decimal places using banker's rounding (COBOL
    # ``ROUNDED`` semantics).
    quantized: Decimal = amount.quantize(_DECIMAL_QUANTUM, rounding=ROUND_HALF_EVEN)

    # Separate sign from magnitude for independent processing.
    is_negative: bool = quantized < _DECIMAL_ZERO
    absolute_value: Decimal = -quantized if is_negative else quantized

    # Split the absolute value into integer and fractional portions.
    # The integer component can be up to 9 digits (``ZZZ,ZZZ,ZZZ``);
    # we format it with grouping then apply zero-suppression.
    #
    # ``{:,.2f}`` uses locale-agnostic thousands separators ("," and ".")
    # which matches the COBOL PIC layout's literal grouping commas and
    # decimal point.  We pad the result on the left to a fixed width
    # so subsequent zero-suppression operates on a predictable slice.
    formatted_magnitude: str = f"{absolute_value:,.2f}"

    # Width of the magnitude portion (without sign) is 15 characters:
    # ``ZZZ,ZZZ,ZZZ.ZZ``.  Left-pad with spaces so every leading digit
    # position is at a known offset.
    magnitude_field: str = formatted_magnitude.rjust(15)

    # Zero-suppression: walk the leading characters up to the decimal
    # point, replacing leading ``0`` digits and the grouping commas
    # that appear within the leading-zero run with spaces.  As soon as
    # we hit a non-zero digit, suppression stops.
    magnitude_chars: list[str] = list(magnitude_field)
    decimal_point_index: int = magnitude_field.index(".")
    suppression_active: bool = True
    for idx in range(decimal_point_index):
        current: str = magnitude_chars[idx]
        if not suppression_active:
            break
        if current == " ":
            # Keep the pre-existing pad space; suppression continues.
            continue
        if current == "0":
            magnitude_chars[idx] = " "
            continue
        if current == ",":
            # A grouping comma within the leading-zero run is also
            # suppressed to a space.
            magnitude_chars[idx] = " "
            continue
        # First non-space, non-zero, non-comma character encountered:
        # it is a significant digit -- stop suppression and retain
        # every remaining character literally.
        suppression_active = False

    # COBOL quirk: if the integer portion is entirely suppressed (the
    # absolute value was zero), the final zero immediately before the
    # decimal point must still print.  CVTRA07Y uses ``PIC -ZZZ,ZZZ,ZZZ.ZZ``
    # whose last integer ``Z`` is an isolated zero-suppression digit;
    # when the value is zero the edit is ``            0.00`` (three
    # spaces of grouping + 9 spaces of integer magnitude with a single
    # terminal ``0``).  We post-process by restoring the digit
    # immediately before the decimal point when every earlier position
    # is a space.
    if all(ch == " " for ch in magnitude_chars[:decimal_point_index]):
        magnitude_chars[decimal_point_index - 1] = "0"

    suppressed_magnitude: str = "".join(magnitude_chars)

    # Sign position: ``-`` when negative, SPACE otherwise.
    sign_char: str = "-" if is_negative else " "

    # Concatenate sign + magnitude for a total width of 16.
    edited: str = sign_char + suppressed_magnitude

    # Defensive check: the edit must always produce exactly 16
    # characters (AAP Section 0.7.1 -- "preserve existing business
    # logic without modification").  A mismatch would signal a
    # regression in the formatting algorithm and must fail loudly.
    if len(edited) != _AMOUNT_EDIT_WIDTH:
        raise RuntimeError(
            f"_format_amount_edited produced {len(edited)} chars, expected {_AMOUNT_EDIT_WIDTH}: {edited!r}"
        )
    return edited


def _format_subtotal_amount_edited(amount: Decimal) -> str:
    """Format a :class:`~decimal.Decimal` as COBOL ``PIC +ZZZ,ZZZ,ZZZ.ZZ``.

    Used by the page-total, account-total, and grand-total report
    lines (CVTRA07Y.cpy fields ``REPT-PAGE-TOTAL``,
    ``REPT-ACCOUNT-TOTAL``, ``REPT-GRAND-TOTAL``).  The differences
    from :func:`_format_amount_edited` are:

    * Leading sign is always explicit: ``+`` for non-negative values,
      ``-`` for negative values (never blanked out).
    * Everything else is identical to ``-ZZZ,ZZZ,ZZZ.ZZ`` (same
      zero-suppression, same final width of 16 characters).

    Parameters
    ----------
    amount : Decimal
        The numeric value to format.

    Returns
    -------
    str
        A 16-character string containing the edited numeric value with
        an explicit leading sign.

    Examples
    --------
    >>> _format_subtotal_amount_edited(Decimal("1234.56"))
    '+      1,234.56 '
    >>> _format_subtotal_amount_edited(Decimal("0"))
    '+           0.00'
    >>> _format_subtotal_amount_edited(Decimal("-1234567.89"))
    '-  1,234,567.89 '
    """
    # Reuse the detail-line formatter for magnitude + zero-suppression
    # logic, then overwrite the sign character.  The detail-line
    # formatter's sign column is position 0; we replace SPACE with
    # ``+`` on non-negative values (negative values already show
    # ``-``).
    detail_edit: str = _format_amount_edited(amount)

    if detail_edit.startswith("-"):
        return detail_edit

    # Replace the leading SPACE with ``+`` for non-negative values.
    return "+" + detail_edit[1:]


def _cobol_field(value: Any, length: int) -> str:
    """Coerce a value to a fixed-width, left-justified, space-padded field.

    Replicates the implicit ``MOVE`` semantics between an alphanumeric
    source and a ``PIC X(n)`` destination: the string is truncated or
    padded with trailing spaces to exactly ``length`` characters.
    ``None`` (representing a NULL JDBC value for optional columns like
    ``tran_source`` or ``tran_desc``) is rendered as all-spaces.

    Parameters
    ----------
    value : Any
        Source value.  Typically a ``str`` coming from the Spark
        ``Row.asDict()`` mapping; ``None`` is tolerated and treated as
        an all-space field.
    length : int
        Target field width in characters.

    Returns
    -------
    str
        The value padded / truncated to exactly ``length`` characters.
    """
    if value is None:
        return " " * length
    text: str = str(value)
    if len(text) >= length:
        return text[:length]
    return text + " " * (length - len(text))


def _cobol_zero_padded_numeric(value: Any, length: int) -> str:
    """Coerce a value to a zero-padded numeric field ``PIC 9(n)``.

    Replicates COBOL ``MOVE`` into a ``PIC 9(n)`` destination: the
    value is right-justified and zero-padded to exactly ``length``
    characters.  ``None`` is rendered as all-zeros (matching the VSAM
    behaviour where numeric fields initialize to ``LOW-VALUES``
    which print as ``0``).

    Parameters
    ----------
    value : Any
        Source value (typically a ``str`` digit sequence from Spark).
    length : int
        Target field width in characters.

    Returns
    -------
    str
        The value right-justified and zero-padded to exactly
        ``length`` characters.
    """
    if value is None:
        return "0" * length
    text: str = str(value).strip()
    if len(text) >= length:
        return text[-length:]
    return text.rjust(length, "0")


def _pad_line_to_width(line: str) -> str:
    """Pad or truncate a report line to exactly ``_REPORT_LINE_WIDTH``.

    Matches COBOL ``WRITE FD-REPTFILE-REC`` which always emits a
    133-byte record (``LRECL=133 RECFM=FB``).  Content shorter than
    133 characters is right-padded with spaces; content longer than
    133 characters is truncated (which should never occur if the
    upstream formatters are correct, but this defensive truncation
    guarantees the physical record format).

    Parameters
    ----------
    line : str
        The report-line content.

    Returns
    -------
    str
        A 133-character string.
    """
    if len(line) >= _REPORT_LINE_WIDTH:
        return line[:_REPORT_LINE_WIDTH]
    return line + " " * (_REPORT_LINE_WIDTH - len(line))


def _compose_s3_key(prefix_uri: str, filename: str) -> tuple[str, str]:
    """Split a versioned S3 URI into ``(bucket, key)`` for :func:`write_to_s3`.

    :func:`src.batch.common.s3_utils.get_versioned_s3_path` returns a
    fully qualified URI of the form ``s3://{bucket}/{path}/YYYY/MM/DD/HHMMSS/``.
    :func:`src.batch.common.s3_utils.write_to_s3` requires the bucket
    and key components separately.  This helper strips the ``s3://``
    scheme, splits on the first ``/``, and appends the desired
    filename to produce the final object key.

    Parameters
    ----------
    prefix_uri : str
        Versioned URI emitted by :func:`get_versioned_s3_path`.
    filename : str
        Object filename to append.

    Returns
    -------
    tuple[str, str]
        ``(bucket_name, full_key)``.

    Raises
    ------
    ValueError
        If ``prefix_uri`` is malformed (missing bucket or key segment).
    """
    scheme_stripped: str = prefix_uri.removeprefix("s3://")
    if "/" not in scheme_stripped:
        raise ValueError(f"Invalid S3 URI returned by get_versioned_s3_path: {prefix_uri!r}")
    bucket_name, key_prefix = scheme_stripped.split("/", 1)
    full_key: str = f"{key_prefix}{filename}"
    return bucket_name, full_key


# ============================================================================
# Private helpers: report header/column builders
# ============================================================================
def _build_report_name_header(start_date: str, end_date: str) -> str:
    """Build the ``REPORT-NAME-HEADER`` line for the date range banner.

    CVTRA07Y.cpy ``REPORT-NAME-HEADER`` layout (verbatim from source)::

        01  REPORT-NAME-HEADER.
            05  REPT-SHORT-NAME    PIC X(38) VALUE 'DALYREPT'.
            05  REPT-LONG-NAME     PIC X(41) VALUE 'Daily Transaction Report'.
            05  REPT-DATE-HEADER   PIC X(12) VALUE 'Date Range: '.
            05  REPT-START-DATE    PIC X(10) VALUE SPACES.
            05  FILLER             PIC X(04) VALUE ' to '.
            05  REPT-END-DATE      PIC X(10) VALUE SPACES.

    Physical widths: 38 + 41 + 12 + 10 + 4 + 10 = 115 characters (padded
    to 133 by :func:`_pad_line_to_width`).  COBOL's ``VALUE``
    initialization left-justifies each label within its ``PIC X(n)``
    field and pads the remainder with spaces automatically -- which is
    why ``REPT-SHORT-NAME`` (``"DALYREPT"``, 8 chars) appears followed
    by 30 padding spaces inside the 38-char field, and
    ``REPT-LONG-NAME`` (``"Daily Transaction Report"``, 24 chars) is
    followed by 17 padding spaces inside the 41-char field.

    Parameters
    ----------
    start_date : str
        Report start date in ``YYYY-MM-DD`` format.
    end_date : str
        Report end date in ``YYYY-MM-DD`` format.

    Returns
    -------
    str
        A 133-character header string.
    """
    raw_line: str = (
        _cobol_field("DALYREPT", 38)
        + _cobol_field("Daily Transaction Report", 41)
        + "Date Range: "
        + _cobol_field(start_date, 10)
        + " to "
        + _cobol_field(end_date, 10)
    )
    return _pad_line_to_width(raw_line)


def _build_transaction_header_1() -> str:
    """Build the ``TRANSACTION-HEADER-1`` column-caption line.

    CVTRA07Y.cpy TRANSACTION-HEADER-1 layout:

    ========  =================================  ==================================
    Offset    Field                              Content
    ========  =================================  ==================================
    1-17      ``TRAN-HEAD1-TRANS-ID`` X(17)      ``"Transaction ID"`` + 3 spaces
    18-29     ``TRAN-HEAD1-ACCOUNT-ID`` X(12)    ``"Account ID"`` + 2 spaces
    30-48     ``TRAN-HEAD1-TYPE-DESC`` X(19)     ``"Transaction Type"`` + 3 spaces
    49-83     ``TRAN-HEAD1-CAT-DESC`` X(35)      ``"Tran Category"`` + 22 spaces
    84-97     ``TRAN-HEAD1-SOURCE`` X(14)        ``"Tran Source"`` + 3 spaces
    98-98     ``FILLER`` X(01)                   space
    99-114    ``TRAN-HEAD1-AMT`` X(16)           right-justified ``"Amount"``
    ========  =================================  ==================================

    Returns
    -------
    str
        A 133-character column-caption string.
    """
    raw_line: str = (
        _cobol_field("Transaction ID", 17)
        + _cobol_field("Account ID", 12)
        + _cobol_field("Transaction Type", 19)
        + _cobol_field("Tran Category", 35)
        + _cobol_field("Tran Source", 14)
        + " "
        + "          Amount"  # 16 chars (10 leading spaces + "Amount")
    )
    return _pad_line_to_width(raw_line)


def _build_transaction_header_2() -> str:
    """Build the ``TRANSACTION-HEADER-2`` separator line.

    CVTRA07Y.cpy declaration (verbatim from source)::

        01  TRANSACTION-HEADER-2  PIC X(133) VALUE ALL '-'.

    The ``VALUE ALL '-'`` clause repeats the hyphen across the entire
    133-character field, producing a full-width dash separator that
    visually underlines :func:`_build_transaction_header_1` above the
    detail lines.  Note this is the only record layout in CVTRA07Y
    that fills the entire 133-character physical record without any
    trailing padding.

    Returns
    -------
    str
        A 133-character separator line composed entirely of ``-``.
    """
    return "-" * _REPORT_LINE_WIDTH


# ============================================================================
# Public API: filter_by_date_range
# ============================================================================
def filter_by_date_range(
    transactions_df: DataFrame,
    start_date: str,
    end_date: str,
) -> DataFrame:
    """Filter a transactions DataFrame by processing-date range (inclusive).

    Replaces the JCL DFSORT ``INCLUDE COND`` filter in TRANREPT.jcl
    STEP05R (lines 52-54):

    .. code-block:: text

        INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,
                      TRAN-PROC-DT,LE,PARM-END-DATE)

    The COBOL program subsequently re-applies the same filter inside
    CBTRN03C.cbl ``1000-TRANFILE-GET-NEXT`` paragraph with the
    condition::

        IF TRAN-PROC-TS(1:10) >= WS-START-DATE AND
           TRAN-PROC-TS(1:10) <= WS-END-DATE

    Both bounds are inclusive (``>=`` / ``<=``).  The comparison
    operates on the ISO-8601 date prefix of the 26-character
    ``TRAN-PROC-TS`` field which is stored as a ``VARCHAR(26)`` in the
    ``transactions`` table (V1 schema, AAP Section 0.5.1).  We extract
    the first 10 characters (``YYYY-MM-DD``) via PySpark's
    :func:`~pyspark.sql.functions.substring` and compare against the
    string-typed ``start_date`` / ``end_date`` parameters.  String
    comparison is well-defined on ISO-8601 dates because the format is
    lexicographically ordered.

    Parameters
    ----------
    transactions_df : DataFrame
        The full ``transactions`` table DataFrame loaded via
        :func:`src.batch.common.db_connector.read_table`.
    start_date : str
        Inclusive lower bound in ``YYYY-MM-DD`` format.
    end_date : str
        Inclusive upper bound in ``YYYY-MM-DD`` format.

    Returns
    -------
    DataFrame
        A new DataFrame containing only rows where the 10-character
        prefix of ``tran_proc_ts`` falls within
        ``[start_date, end_date]`` inclusive.  The schema is
        unchanged.

    Notes
    -----
    PySpark's :func:`~pyspark.sql.functions.substring` is 1-indexed,
    matching COBOL's ``TRAN-PROC-TS(1:10)`` reference-modification
    syntax.  The second argument (``_DATE_PREFIX_LEN`` = 10) is the
    substring LENGTH, not the ending index.
    """
    if not isinstance(start_date, str) or not isinstance(end_date, str):
        raise TypeError(
            "filter_by_date_range requires start_date and end_date to be "
            "ISO-8601 strings; got "
            f"start_date={type(start_date).__name__!r}, "
            f"end_date={type(end_date).__name__!r}"
        )

    # Defensive: guard against swapped bounds (start > end) which
    # would silently produce an empty DataFrame.  Explicit check with
    # logger warning matches the conservative behaviour expected of a
    # batch job where a mis-configured JCL PARM would typically be
    # caught by the system programmer reviewing the JES2 log.
    if start_date > end_date:
        logger.warning(
            "filter_by_date_range received start_date > end_date; result will be empty.",
            extra={"start_date": start_date, "end_date": end_date},
        )

    date_prefix = F.substring(F.col("tran_proc_ts"), 1, _DATE_PREFIX_LEN)

    filtered: DataFrame = transactions_df.filter((date_prefix >= F.lit(start_date)) & (date_prefix <= F.lit(end_date)))
    return filtered


# ============================================================================
# Public API: format_report_line
# ============================================================================
def format_report_line(row: dict[str, Any], line_num: int) -> str:
    """Format a single 133-character transaction detail line.

    Replicates the ``WRITE FD-REPTFILE-REC FROM TRANSACTION-DETAIL-REPORT``
    statement in CBTRN03C.cbl paragraph ``1120-WRITE-DETAIL``, using
    the CVTRA07Y.cpy TRANSACTION-DETAIL-REPORT layout:

    ========  =========================================  ============
    Offset    Field                                      Width
    ========  =========================================  ============
    1-16      ``TRAN-REPORT-TRANS-ID`` ``PIC X(16)``     16
    17-17     ``FILLER`` ``PIC X(01)``                  1 space
    18-28     ``TRAN-REPORT-ACCOUNT-ID`` ``PIC X(11)``   11
    29-29     ``FILLER`` ``PIC X(01)``                  1 space
    30-31     ``TRAN-REPORT-TYPE-CD`` ``PIC X(02)``      2
    32-32     ``FILLER`` ``PIC X(01)``                  ``"-"``
    33-47     ``TRAN-REPORT-TYPE-DESC`` ``PIC X(15)``    15
    48-48     ``FILLER`` ``PIC X(01)``                  1 space
    49-52     ``TRAN-REPORT-CAT-CD`` ``PIC 9(04)``       4
    53-53     ``FILLER`` ``PIC X(01)``                  ``"-"``
    54-82     ``TRAN-REPORT-CAT-DESC`` ``PIC X(29)``     29
    83-83     ``FILLER`` ``PIC X(01)``                  1 space
    84-93     ``TRAN-REPORT-SOURCE`` ``PIC X(10)``       10
    94-97     ``FILLER`` ``PIC X(04)``                  4 spaces
    98-113    ``TRAN-REPORT-AMT`` ``PIC -ZZZ,ZZZ,ZZZ.ZZ`` 16
    114-115   ``FILLER`` ``PIC X(02)``                  2 spaces
    116-133   FILLER / trailing padding                  18 spaces
    ========  =========================================  ============

    The total defined layout is 115 characters; :func:`_pad_line_to_width`
    extends this to the 133-character physical record.

    Parameters
    ----------
    row : dict[str, Any]
        A row dict produced by ``Row.asDict()`` on the enriched
        transactions DataFrame.  Expected keys:

        * ``tran_id`` (str): Transaction ID (CHAR(16)).
        * ``acct_id`` (str or None): Account ID from xref join
          (CHAR(11)); ``None`` when xref lookup missed.
        * ``tran_type_cd`` (str): Transaction type code (CHAR(2)).
        * ``tran_type_desc`` (str or None): Description from
          trantype join (VARCHAR(50), truncated to 15 in layout).
        * ``tran_cat_cd`` (str): Transaction category code (CHAR(4)).
        * ``tran_cat_type_desc`` (str or None): Description from
          trancatg join (VARCHAR(50), truncated to 29 in layout).
        * ``tran_source`` (str or None): Transaction source
          (VARCHAR(10)).
        * ``tran_amt`` (Decimal): Transaction amount.
    line_num : int
        The sequential line number being written (used for logging
        only; COBOL ``WS-LINE-COUNTER`` is managed by the caller).

    Returns
    -------
    str
        A 133-character report detail line.
    """
    # Tran-ID (CHAR 16) + single-space FILLER
    tran_id_field: str = _cobol_field(row.get("tran_id"), 16)

    # Account-ID from xref (CHAR 11).  Missing xref => 11 spaces.
    acct_id_field: str = _cobol_field(row.get("acct_id"), 11)

    # Type-CD (CHAR 2), FILLER "-", Type-Desc (CHAR 15)
    type_cd_field: str = _cobol_field(row.get("tran_type_cd"), 2)
    type_desc_field: str = _cobol_field(row.get("tran_type_desc"), 15)

    # Cat-CD (PIC 9(04) zero-filled), FILLER "-", Cat-Desc (CHAR 29)
    cat_cd_field: str = _cobol_zero_padded_numeric(row.get("tran_cat_cd"), 4)
    cat_desc_field: str = _cobol_field(row.get("tran_cat_type_desc"), 29)

    # Source (CHAR 10) + 4 filler spaces
    source_field: str = _cobol_field(row.get("tran_source"), 10)

    # Amount edited (PIC -ZZZ,ZZZ,ZZZ.ZZ, 16 chars)
    raw_amount: Any = row.get("tran_amt")
    amount_value: Decimal
    if raw_amount is None:
        amount_value = _DECIMAL_ZERO
    elif isinstance(raw_amount, Decimal):
        amount_value = raw_amount
    else:
        # Spark DecimalType materializes as Decimal already; defensive
        # coercion for tests that pass str / int / float literals.  We
        # still pipe through Decimal -- never float.
        amount_value = Decimal(str(raw_amount))
    amount_field: str = _format_amount_edited(amount_value)

    # Assemble the 115-character logical record.
    raw_line: str = (
        tran_id_field
        + " "  # FILLER PIC X(01)
        + acct_id_field
        + " "  # FILLER PIC X(01)
        + type_cd_field
        + "-"  # FILLER PIC X(01) VALUE '-'
        + type_desc_field
        + " "  # FILLER PIC X(01)
        + cat_cd_field
        + "-"  # FILLER PIC X(01) VALUE '-'
        + cat_desc_field
        + " "  # FILLER PIC X(01)
        + source_field
        + "    "  # FILLER PIC X(04)
        + amount_field
        + "  "  # FILLER PIC X(02)
    )

    # Debug log for traceability during development; flows to CloudWatch
    # under DEBUG level.  ``line_num`` participates so operators can
    # correlate CloudWatch entries with physical report offsets.
    if logger.isEnabledFor(logging.DEBUG):
        logger.debug(
            "Formatted detail line",
            extra={
                "line_num": line_num,
                "tran_id": row.get("tran_id"),
                "length": len(raw_line),
            },
        )
    return _pad_line_to_width(raw_line)


# ============================================================================
# Public API: format_subtotal_line
# ============================================================================
def format_subtotal_line(label: str, amount: Decimal) -> str:
    """Format a 133-character subtotal line for page/account/grand totals.

    Dispatches on the ``label`` argument to produce one of three
    CVTRA07Y.cpy record layouts:

    **REPORT-PAGE-TOTALS** (``label == "Page Total"``)::

        01  REPORT-PAGE-TOTALS.
            05  FILLER              PIC X(11) VALUE 'Page Total '.
            05  FILLER              PIC X(86) VALUE ALL '.'.
            05  REPT-PAGE-TOTAL     PIC +ZZZ,ZZZ,ZZZ.ZZ.

    11 + 86 + 16 = 113 chars, padded to 133.

    **REPORT-ACCOUNT-TOTALS** (``label == "Account Total"``)::

        01  REPORT-ACCOUNT-TOTALS.
            05  FILLER              PIC X(13) VALUE 'Account Total'.
            05  FILLER              PIC X(84) VALUE ALL '.'.
            05  REPT-ACCOUNT-TOTAL  PIC +ZZZ,ZZZ,ZZZ.ZZ.

    13 + 84 + 16 = 113 chars, padded to 133.

    **REPORT-GRAND-TOTALS** (``label == "Grand Total"``)::

        01  REPORT-GRAND-TOTALS.
            05  FILLER              PIC X(11) VALUE 'Grand Total'.
            05  FILLER              PIC X(86) VALUE ALL '.'.
            05  REPT-GRAND-TOTAL    PIC +ZZZ,ZZZ,ZZZ.ZZ.

    11 + 86 + 16 = 113 chars, padded to 133.

    All three layouts share the 113-character logical width; the
    difference is in the exact label text (and leading dot count).
    The numeric-edit field is identical (``PIC +ZZZ,ZZZ,ZZZ.ZZ``).

    Parameters
    ----------
    label : str
        Exactly one of ``"Page Total"``, ``"Account Total"``, or
        ``"Grand Total"``.
    amount : Decimal
        The subtotal value to print.

    Returns
    -------
    str
        A 133-character subtotal line.

    Raises
    ------
    ValueError
        If ``label`` is not one of the three recognized values.
    """
    # Dispatch on label to produce the correct COBOL record layout.
    label_text: str
    dots_width: int
    if label == "Page Total":
        label_text = "Page Total "  # PIC X(11) with trailing space
        dots_width = 86
    elif label == "Account Total":
        label_text = "Account Total"  # PIC X(13) no trailing space
        dots_width = 84
    elif label == "Grand Total":
        label_text = "Grand Total"  # PIC X(11) no trailing space
        dots_width = 86
    else:
        raise ValueError(
            f"format_subtotal_line received unknown label {label!r}; "
            "expected one of 'Page Total', 'Account Total', 'Grand Total'."
        )

    amount_field: str = _format_subtotal_amount_edited(amount)
    raw_line: str = label_text + ("." * dots_width) + amount_field
    return _pad_line_to_width(raw_line)


# ============================================================================
# Private helpers: report body assembly
# ============================================================================
def _enrich_transactions(
    filtered_df: DataFrame,
    xref_df: DataFrame,
    trantype_df: DataFrame,
    trancatg_df: DataFrame,
) -> DataFrame:
    """Join transactions with the three lookup tables to build report rows.

    Replaces the COBOL ``1500-A-LOOKUP-XREF``,
    ``1500-B-LOOKUP-TRANTYPE``, and ``1500-C-LOOKUP-TRANCATG`` READ
    paragraphs in CBTRN03C.cbl.  Each COBOL paragraph executes
    ``READ ... KEY IS ...`` on an indexed VSAM dataset and moves the
    retrieved fields into the output record.  We replicate the
    semantics with left-outer joins (preserving transactions even
    when a lookup is missing -- matching COBOL's behaviour of
    continuing with SPACES/ZEROES on a ``INVALID KEY`` branch).

    Join contracts (column-name reconciliation between the
    ``transactions`` table and the lookup tables):

    * ``transactions.tran_card_num`` (CHAR 16) ==
      ``card_cross_references.card_num`` (CHAR 16)
    * ``transactions.tran_type_cd`` (CHAR 2) ==
      ``transaction_types.type_code`` (CHAR 2)
    * ``transactions.(tran_type_cd, tran_cat_cd)`` ==
      ``transaction_categories.(type_code, cat_code)``

    Parameters
    ----------
    filtered_df : DataFrame
        Date-filtered transactions.
    xref_df : DataFrame
        ``card_cross_references`` table.
    trantype_df : DataFrame
        ``transaction_types`` table.
    trancatg_df : DataFrame
        ``transaction_categories`` table.

    Returns
    -------
    DataFrame
        Enriched DataFrame with one row per input transaction.  The
        projected columns are:

        * ``tran_id``
        * ``tran_card_num``
        * ``tran_type_cd``
        * ``tran_cat_cd``
        * ``tran_source``
        * ``tran_amt``
        * ``acct_id`` (from xref)
        * ``tran_type_desc`` (from trantype)
        * ``tran_cat_type_desc`` (from trancatg)
    """
    # Alias the lookup DataFrames so post-join column selection is
    # unambiguous.  Spark raises ``AnalysisException`` when a column
    # name resolves to multiple parents without disambiguation.
    tx_alias: DataFrame = filtered_df.alias("tx")
    xref_alias: DataFrame = xref_df.alias("xref")
    trantype_alias: DataFrame = trantype_df.alias("trantype")
    trancatg_alias: DataFrame = trancatg_df.alias("trancatg")

    # Join #1: transactions LEFT JOIN card_cross_references ON
    #          tx.tran_card_num = xref.card_num
    enriched: DataFrame = tx_alias.join(
        xref_alias,
        F.col("tx.tran_card_num") == F.col("xref.card_num"),
        "left",
    )

    # Join #2: result LEFT JOIN transaction_types ON
    #          tx.tran_type_cd = trantype.type_code
    enriched = enriched.join(
        trantype_alias,
        F.col("tx.tran_type_cd") == F.col("trantype.type_code"),
        "left",
    )

    # Join #3: result LEFT JOIN transaction_categories ON composite key
    enriched = enriched.join(
        trancatg_alias,
        (F.col("tx.tran_type_cd") == F.col("trancatg.type_code"))
        & (F.col("tx.tran_cat_cd") == F.col("trancatg.cat_code")),
        "left",
    )

    # Project only the columns consumed by :func:`format_report_line`
    # and the 3-level totals driver (avoids shipping full trantype /
    # trancatg payloads to the driver during ``collect``).
    projected: DataFrame = enriched.select(
        F.col("tx.tran_id").alias("tran_id"),
        F.col("tx.tran_card_num").alias("tran_card_num"),
        F.col("tx.tran_type_cd").alias("tran_type_cd"),
        F.col("tx.tran_cat_cd").alias("tran_cat_cd"),
        F.col("tx.tran_source").alias("tran_source"),
        F.col("tx.tran_amt").alias("tran_amt"),
        F.col("xref.acct_id").alias("acct_id"),
        F.col("trantype.tran_type_desc").alias("tran_type_desc"),
        F.col("trancatg.tran_cat_type_desc").alias("tran_cat_type_desc"),
    )
    return projected


def _generate_report_lines(
    rows: list[dict[str, Any]],
    start_date: str,
    end_date: str,
) -> list[str]:
    """Emit the complete report body with 3-level totals.

    This function is the Python translation of CBTRN03C.cbl's main
    PROCEDURE DIVISION loop (paragraphs ``1000-TRANFILE-GET-NEXT``
    through ``1110-WRITE-GRAND-TOTALS``).  It drives the state
    machine over a pre-sorted list of enriched transaction rows and
    produces the sequence of 133-character report lines in the exact
    order CBTRN03C.cbl would have written them.

    Algorithm (faithful to COBOL source):

    1.  Initialize ``WS-ACCOUNT-TOTAL``, ``WS-PAGE-TOTAL``,
        ``WS-GRAND-TOTAL`` to zero.  Set ``WS-FIRST-TIME = 'Y'``.
        Set ``WS-CURR-CARD-NUM`` to empty string.  Set
        ``WS-LINE-COUNTER = 0``.
    2.  For each row:
        a.  **Card break check** -- if ``WS-CURR-CARD-NUM NOT=
            TRAN-CARD-NUM`` AND ``WS-FIRST-TIME = 'N'``, emit
            ``REPORT-ACCOUNT-TOTALS`` (paragraph
            ``1120-WRITE-ACCOUNT-TOTALS``) and reset
            ``WS-ACCOUNT-TOTAL`` to zero.  Increment line counter by 1
            for the written account-total line.
        b.  **Page break check** -- if ``FUNCTION MOD(WS-LINE-COUNTER, 20) = 0``,
            emit ``REPORT-PAGE-TOTALS`` (paragraph
            ``1110-WRITE-PAGE-TOTALS``), flush ``WS-PAGE-TOTAL`` into
            ``WS-GRAND-TOTAL``, reset ``WS-PAGE-TOTAL`` to zero, and
            call ``1120-WRITE-HEADERS``.  The headers paragraph emits
            REPORT-NAME-HEADER, TRANSACTION-HEADER-1, and
            TRANSACTION-HEADER-2 on the first call (``WS-FIRST-TIME = 'Y'``)
            and only the two data headers thereafter.  Clear
            ``WS-FIRST-TIME`` after the first call.  Increment line
            counter by the number of lines emitted (1 for page total +
            4 for first-time headers, or 1 + 3 for subsequent headers).
        c.  **Detail write** -- emit TRANSACTION-DETAIL-REPORT.  Add
            ``TRAN-AMT`` to ``WS-PAGE-TOTAL`` and ``WS-ACCOUNT-TOTAL``.
            Update ``WS-CURR-CARD-NUM = TRAN-CARD-NUM``.  Increment
            line counter by 1.
    3.  End-of-file processing:
        a.  Emit final ``REPORT-PAGE-TOTALS`` (paragraph
            ``1110-WRITE-PAGE-TOTALS`` invoked by the EOF branch
            prior to grand-total emission).  Flush ``WS-PAGE-TOTAL``
            into ``WS-GRAND-TOTAL``.
        b.  Emit ``REPORT-GRAND-TOTALS``.
        c.  **Note:** CBTRN03C.cbl does **not** flush a final
            account-total line at EOF (per the AAP Section 0.7.1
            "minimal change" rule -- this quirk is preserved).

    Parameters
    ----------
    rows : list[dict[str, Any]]
        Enriched transaction rows, pre-sorted by ``tran_card_num``
        ascending, ``tran_id`` ascending (secondary stabilizer).
    start_date : str
        Report start date (for header rendering).
    end_date : str
        Report end date (for header rendering).

    Returns
    -------
    list[str]
        The complete sequence of 133-character report lines.
    """
    lines: list[str] = []

    # State machine registers (COBOL WORKING-STORAGE equivalents).
    ws_account_total: Decimal = _DECIMAL_ZERO
    ws_page_total: Decimal = _DECIMAL_ZERO
    ws_grand_total: Decimal = _DECIMAL_ZERO
    ws_curr_card_num: str = ""
    ws_first_time: bool = True
    ws_line_counter: int = 0

    # Pre-build the three header lines (invariant across the report).
    name_header_line: str = _build_report_name_header(start_date, end_date)
    tx_header_1_line: str = _build_transaction_header_1()
    tx_header_2_line: str = _build_transaction_header_2()

    def _write_headers() -> None:
        """Inner helper -- replicates ``1120-WRITE-HEADERS`` paragraph.

        CBTRN03C.cbl ``1120-WRITE-HEADERS`` emits (per the order in
        the source):

        * ``REPORT-NAME-HEADER`` (only when ``WS-FIRST-TIME = 'Y'``)
        * ``TRANSACTION-HEADER-1``
        * ``TRANSACTION-HEADER-2``
        * An implicit blank line separator (COBOL ``WRITE`` of a
          ``SPACES`` record).

        After the first invocation ``WS-FIRST-TIME`` is cleared so
        subsequent page breaks skip the name header.
        """
        nonlocal ws_first_time, ws_line_counter
        if ws_first_time:
            lines.append(name_header_line)
            ws_line_counter += 1
            ws_first_time = False
        lines.append(tx_header_1_line)
        ws_line_counter += 1
        lines.append(tx_header_2_line)
        ws_line_counter += 1
        # Blank separator row beneath the column headings (CVTRA07Y
        # does not define a field for this but CBTRN03C writes a
        # SPACES record between headers and detail lines).
        lines.append(_pad_line_to_width(""))
        ws_line_counter += 1

    def _write_page_totals_and_advance() -> None:
        """Inner helper -- replicates ``1110-WRITE-PAGE-TOTALS`` + header re-emission.

        The COBOL paragraph writes the REPORT-PAGE-TOTALS record,
        rolls ``WS-PAGE-TOTAL`` into ``WS-GRAND-TOTAL``, resets
        ``WS-PAGE-TOTAL`` to zero, and (via fall-through to
        ``1120-WRITE-HEADERS``) emits fresh headers at the top of
        the next page.
        """
        nonlocal ws_page_total, ws_grand_total, ws_line_counter
        lines.append(format_subtotal_line("Page Total", ws_page_total))
        ws_line_counter += 1
        ws_grand_total = (ws_grand_total + ws_page_total).quantize(_DECIMAL_QUANTUM, rounding=ROUND_HALF_EVEN)
        ws_page_total = _DECIMAL_ZERO
        _write_headers()

    # Initial header emission.  In CBTRN03C.cbl the first
    # ``1120-WRITE-HEADERS`` invocation fires inside the first
    # ``1100-WRITE-TRANSACTION-REPORT`` call because
    # ``WS-LINE-COUNTER = 0`` triggers the page-break path.  We
    # emulate this by calling ``_write_page_totals_and_advance`` on
    # the first record; however the page-total line on the FIRST page
    # would show zero -- which matches COBOL behaviour exactly (the
    # COBOL program writes "Page Total ........ 0.00" at the top of
    # page 1 before any detail lines).  After careful review of the
    # CBTRN03C.cbl source, the page-total emission is GUARDED by the
    # line counter being a non-zero multiple of 20.  So on the first
    # iteration (line_counter = 0), the page-total line is SKIPPED
    # and only the headers are written.

    for row_index, row in enumerate(rows):
        card_num: str = row.get("tran_card_num") or ""
        raw_amount: Any = row.get("tran_amt")
        tran_amount: Decimal
        if raw_amount is None:
            tran_amount = _DECIMAL_ZERO
        elif isinstance(raw_amount, Decimal):
            tran_amount = raw_amount.quantize(_DECIMAL_QUANTUM, rounding=ROUND_HALF_EVEN)
        else:
            tran_amount = Decimal(str(raw_amount)).quantize(_DECIMAL_QUANTUM, rounding=ROUND_HALF_EVEN)

        # ------------------------------------------------------------
        # Step 2a: Card break check.  CBTRN03C.cbl:
        #     IF WS-CURR-CARD-NUM NOT = TRAN-CARD-NUM
        #       IF WS-FIRST-TIME = 'N'
        #         PERFORM 1120-WRITE-ACCOUNT-TOTALS
        #       END-IF
        #     END-IF
        # ------------------------------------------------------------
        if card_num != ws_curr_card_num and not ws_first_time:
            lines.append(format_subtotal_line("Account Total", ws_account_total))
            ws_line_counter += 1
            ws_account_total = _DECIMAL_ZERO

        # ------------------------------------------------------------
        # Step 2b: Page break check.  CBTRN03C.cbl:
        #     IF FUNCTION MOD(WS-LINE-COUNTER, 20) = 0
        #       IF WS-FIRST-TIME = 'N'
        #         PERFORM 1110-WRITE-PAGE-TOTALS
        #       END-IF
        #       PERFORM 1120-WRITE-HEADERS
        #     END-IF
        # ------------------------------------------------------------
        if ws_line_counter % _PAGE_SIZE == 0:
            if not ws_first_time:
                # On subsequent pages: write page total + headers.
                _write_page_totals_and_advance()
            else:
                # On first record: only write initial headers (no
                # page total yet since no detail lines have been
                # written).
                _write_headers()

        # ------------------------------------------------------------
        # Step 2c: Detail write.  CBTRN03C.cbl:
        #     WRITE FD-REPTFILE-REC FROM TRANSACTION-DETAIL-REPORT.
        #     ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL.
        # ------------------------------------------------------------
        lines.append(format_report_line(row, ws_line_counter + 1))
        ws_line_counter += 1
        ws_page_total = (ws_page_total + tran_amount).quantize(_DECIMAL_QUANTUM, rounding=ROUND_HALF_EVEN)
        ws_account_total = (ws_account_total + tran_amount).quantize(_DECIMAL_QUANTUM, rounding=ROUND_HALF_EVEN)
        ws_curr_card_num = card_num

        # Debug logging every 100 rows for long-running report
        # operators; non-essential for functional correctness.
        if row_index > 0 and row_index % 100 == 0 and logger.isEnabledFor(logging.DEBUG):
            logger.debug(
                "Report progress",
                extra={
                    "rows_processed": row_index,
                    "line_counter": ws_line_counter,
                    "current_card": card_num,
                    "running_grand_total": str(ws_grand_total),
                },
            )

    # ------------------------------------------------------------
    # Step 3: End-of-file processing.  CBTRN03C.cbl main loop:
    #     READ TRANFILE-FILE INTO TRAN-RECORD
    #        AT END MOVE 'Y' TO END-OF-FILE
    #     END-READ.
    #     IF END-OF-FILE = 'N'
    #       ...detail + page-break processing...
    #     ELSE
    #       PERFORM 1110-WRITE-PAGE-TOTALS
    #       PERFORM 1110-WRITE-GRAND-TOTALS
    #     END-IF.
    # ------------------------------------------------------------
    # Only emit final totals when at least one detail line was
    # written; an empty report (no matching transactions) still
    # shows the headers plus a zero grand total which matches
    # CBTRN03C's behavior when the input file is empty after
    # filtering.
    if not ws_first_time:
        # Final page total emission rolls the last page into grand
        # total.  CBTRN03C executes ``1110-WRITE-PAGE-TOTALS`` once
        # on the EOF branch.
        lines.append(format_subtotal_line("Page Total", ws_page_total))
        ws_grand_total = (ws_grand_total + ws_page_total).quantize(_DECIMAL_QUANTUM, rounding=ROUND_HALF_EVEN)
        ws_page_total = _DECIMAL_ZERO

        # Final grand total emission.  NOTE: CBTRN03C.cbl does NOT
        # flush a final ``REPORT-ACCOUNT-TOTALS`` line on EOF; only
        # card-break transitions produce account totals (AAP
        # Section 0.7.1 -- preserve existing business logic exactly).
        lines.append(format_subtotal_line("Grand Total", ws_grand_total))
    else:
        # Empty input case: no detail lines, no headers yet.  We
        # still emit the headers and a zero grand total so the
        # operator sees a well-formed empty report rather than an
        # empty file.
        _write_headers()
        lines.append(format_subtotal_line("Grand Total", _DECIMAL_ZERO))

    logger.info(
        "Report generation complete",
        extra={
            "total_lines": len(lines),
            "grand_total": str(ws_grand_total),
            "detail_rows": len(rows),
        },
    )
    return lines


# ============================================================================
# Public API: main
# ============================================================================
def main() -> None:
    """Entry point for the ``carddemo-tranrept`` AWS Glue Job.

    This function is the direct replacement for the JES2 dispatcher's
    ``EXEC PGM=CBTRN03C`` statement in TRANREPT.jcl line 62.  It
    orchestrates the complete Stage 4b pipeline:

    1. Initialize the Glue / Spark runtime via
       :func:`src.batch.common.glue_context.init_glue`.  The function
       returns a 4-tuple ``(spark, glue_context, job, resolved_args)``
       where ``resolved_args`` always contains at least the
       ``JOB_NAME`` key.  Optional arguments ``START_DATE`` /
       ``END_DATE`` can override the default date range.
    2. Read the four source tables from Aurora PostgreSQL via JDBC:

       * ``transactions`` (350-byte VSAM record equivalent)
       * ``card_cross_references`` (50-byte AIX path equivalent)
       * ``transaction_types`` (60-byte KSDS)
       * ``transaction_categories`` (60-byte composite-key KSDS)

       This materializes the logical source files that TRANREPT.jcl's
       STEP05R REPROC stage would have unloaded to flat files on the
       mainframe.
    3. Apply the date-range filter using
       :func:`filter_by_date_range`, replacing TRANREPT.jcl's STEP05R
       DFSORT ``INCLUDE COND`` clause.
    4. Enrich the filtered transactions with three left joins
       (xref, trantype, trancatg) producing the logical record that
       CBTRN03C.cbl builds in-memory via ``1500-LOOKUP-*`` paragraphs.
    5. Order the enriched rows by ``tran_card_num`` ascending
       (replacing TRANREPT.jcl's DFSORT ``SORT FIELDS=(TRAN-CARD-NUM,A)``).
       A secondary sort by ``tran_id`` ensures a stable, reproducible
       line order within each card group.
    6. Collect the sorted rows to the Spark driver and iterate them
       through :func:`_generate_report_lines`, which implements the
       3-level totals state machine.
    7. Join the 133-character report lines with newlines and upload
       the final payload to S3 via :func:`write_to_s3`, with the
       object key composed by :func:`_compose_s3_key` from the
       versioned prefix returned by :func:`get_versioned_s3_path`.
    8. Commit the Glue Job bookmark and log the success banner.

    A ``try`` / ``except`` wrapper around the business logic ensures
    any exception is logged via :meth:`logging.Logger.exception` (which
    emits the full traceback under the ``_JCL_ABEND_MSG`` banner) and
    then re-raised.  Step Functions / the Glue runtime detects the
    unhandled exception and transitions the workflow into a failed
    state, matching the JES2 ABEND semantics.

    Returns
    -------
    None
        This function has no return value.  Its side effects are:

        * One object written to S3 under the ``TRANREPT`` GDG prefix.
        * Glue Job bookmark committed.
        * CloudWatch log entries for every major step.

    Raises
    ------
    Exception
        Any exception raised by the downstream Spark / JDBC / S3
        operations is logged with the ``_JCL_ABEND_MSG`` banner and
        propagated.  Callers (the Glue Job runtime / Step Functions)
        are expected to translate the unhandled exception into a
        pipeline failure.
    """
    # ------------------------------------------------------------------
    # Step 0: Initialize Glue + Spark runtime.
    # ------------------------------------------------------------------
    spark, _glue_context, job, resolved_args = init_glue(job_name=_JOB_NAME)
    logger.info(_JCL_JOB_START_MSG)
    # Log the resolved arguments (filtering out keys that start with
    # ``--`` which are raw argv entries; we only want the resolved
    # key=value pairs).
    #
    # IMPORTANT: The ``extra`` dict must NOT use the key name ``args``
    # because Python's ``logging.Logger.makeRecord`` reserves ``args``
    # as an internal :class:`LogRecord` attribute (used to carry the
    # positional format-string arguments). Passing ``extra={"args": ...}``
    # triggers ``KeyError: "Attempt to overwrite 'args' in LogRecord"``.
    # The key was renamed to ``resolved_args`` to resolve QA Checkpoint
    # 5 Issue 24 (tranrept_job crashes on import / invocation); this
    # unblocks the entire Stage 4b pipeline branch.
    logger.info(
        "Resolved Glue arguments",
        extra={"resolved_args": {k: v for k, v in resolved_args.items() if not k.startswith("--")}},
    )

    try:
        # --------------------------------------------------------------
        # Step 1: Read source tables from Aurora PostgreSQL.
        # (Replaces TRANREPT.jcl STEP05R REPROC + the VSAM OPEN
        #  statements inside CBTRN03C.cbl paragraph 0500-OPEN-FILES.)
        # --------------------------------------------------------------
        logger.info(_JCL_STEP05R_REPROC_START_MSG)
        transactions_df: DataFrame = read_table(spark, _TABLE_TRANSACTIONS)
        xref_df: DataFrame = read_table(spark, _TABLE_XREF)
        trantype_df: DataFrame = read_table(spark, _TABLE_TRANTYPE)
        trancatg_df: DataFrame = read_table(spark, _TABLE_TRANCATG)
        logger.info(_JCL_STEP05R_REPROC_END_MSG)

        # --------------------------------------------------------------
        # Step 2: Resolve date parameters.
        # The Glue Job definition accepts optional ``--START_DATE`` and
        # ``--END_DATE`` keys, resolved by AWS Glue's argv parsing.
        # When not present, fall back to the JCL SYMNAMES defaults.
        # --------------------------------------------------------------
        start_date: str = resolved_args.get("START_DATE", _DEFAULT_START_DATE)
        end_date: str = resolved_args.get("END_DATE", _DEFAULT_END_DATE)
        logger.info(
            "Resolved date range",
            extra={"start_date": start_date, "end_date": end_date},
        )

        # --------------------------------------------------------------
        # Step 3: Apply the date-range filter.
        # (Replaces TRANREPT.jcl STEP05R SORT INCLUDE COND.)
        # --------------------------------------------------------------
        logger.info(_JCL_STEP05R_SORT_START_MSG)
        filtered_df: DataFrame = filter_by_date_range(transactions_df, start_date, end_date)

        # --------------------------------------------------------------
        # Step 4: Enrich transactions with lookup joins.
        # (Replaces CBTRN03C.cbl paragraphs
        #  1500-A-LOOKUP-XREF / 1500-B-LOOKUP-TRANTYPE /
        #  1500-C-LOOKUP-TRANCATG.)
        # --------------------------------------------------------------
        enriched_df: DataFrame = _enrich_transactions(filtered_df, xref_df, trantype_df, trancatg_df)

        # --------------------------------------------------------------
        # Step 5: Sort by card number ascending.
        # (Replaces TRANREPT.jcl STEP05R SORT SORT FIELDS=(TRAN-CARD-NUM,A).
        #  Secondary tran_id ordering ensures a stable sequence within
        #  each card group so the report is byte-identical across
        #  reruns with identical input -- essential for regression
        #  testing per AAP Section 0.7.2.)
        # --------------------------------------------------------------
        sorted_df: DataFrame = enriched_df.orderBy(
            F.col("tran_card_num").asc_nulls_last(),
            F.col("tran_id").asc_nulls_last(),
        )
        logger.info(_JCL_STEP05R_SORT_END_MSG)

        # --------------------------------------------------------------
        # Step 6: Materialize rows on driver and generate report lines.
        # ``collect`` pulls every filtered row into the driver JVM
        # which is acceptable for TRANREPT's expected volume (the
        # daily transaction report is bounded by the daily transaction
        # count and the report date range).  If volumes exceed driver
        # memory, the future mitigation is to shard by card-number
        # ranges -- but that is a scaling concern out of scope for
        # the minimal-change migration (AAP Section 0.7.1).
        # --------------------------------------------------------------
        logger.info(_JCL_STEP10R_START_MSG)
        collected_rows: list[Any] = sorted_df.collect()
        row_dicts: list[dict[str, Any]] = [row.asDict() for row in collected_rows]
        logger.info(
            "Collected rows to driver",
            extra={"row_count": len(row_dicts)},
        )

        report_lines: list[str] = _generate_report_lines(row_dicts, start_date, end_date)

        # --------------------------------------------------------------
        # Step 7: Serialize the report lines and upload to S3.
        # Each line is terminated by ``\n`` (matching the newline
        # behaviour of sequential datasets on Linux-style file
        # systems; the mainframe DCB=RECFM=FB does not use explicit
        # line terminators but the S3 consumer ecosystem expects
        # them).  A trailing newline is appended so the final record
        # is terminated consistently.
        # --------------------------------------------------------------
        report_content: str = "\n".join(report_lines) + "\n"
        report_prefix_uri: str = get_versioned_s3_path(_GDG_TRANREPT)
        bucket_name, full_key = _compose_s3_key(report_prefix_uri, _OUTPUT_FILENAME)
        report_uri: str = write_to_s3(
            report_content,
            full_key,
            bucket=bucket_name,
            content_type=_CONTENT_TYPE_TEXT,
        )
        logger.info(
            "Transaction report persisted to S3",
            extra={
                "s3_uri": report_uri,
                "line_count": len(report_lines),
                "byte_count": len(report_content.encode("utf-8")),
            },
        )
        logger.info(_JCL_STEP10R_END_MSG)

        # --------------------------------------------------------------
        # Step 8: Commit Glue Job bookmark and log success banner.
        # --------------------------------------------------------------
        commit_job(job)
        logger.info(_JCL_JOB_END_MSG)

    except Exception:
        # Log the full traceback under the ABEND banner and re-raise.
        # The Glue Job runtime / Step Functions interprets the
        # unhandled exception as a job failure (equivalent to MAXCC=16).
        logger.exception(_JCL_ABEND_MSG)
        raise


# ============================================================================
# Entry point guard
# ============================================================================
if __name__ == "__main__":
    # The Glue Job runtime invokes this module as a script.  We log
    # ``sys.argv`` at DEBUG level so operators troubleshooting a
    # failed job can inspect the resolved command-line parameters in
    # CloudWatch.  The body of the job is then executed by ``main()``.
    logger.debug("sys.argv at entry: %s", sys.argv)
    main()


# ============================================================================
# Public API surface (schema-mandated exports)
# ============================================================================
# AAP exports schema requires these four callables to be publicly
# accessible.  The private helpers (prefixed with ``_``) are
# implementation details and intentionally omitted from __all__ to
# follow PEP-8 "weak internal use" convention.
__all__ = [
    "filter_by_date_range",
    "format_report_line",
    "format_subtotal_line",
    "main",
]
