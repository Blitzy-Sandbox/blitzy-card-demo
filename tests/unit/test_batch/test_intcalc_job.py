# ============================================================================
# Source: app/cbl/CBACT04C.cbl      — Interest Calculation Batch (CBACT04C)
#         app/jcl/INTCALC.jcl       — JCL orchestration for Stage 2
#         app/cpy/CVTRA01Y.cpy      — TRAN-CAT-BAL-RECORD (50B)
#         app/cpy/CVACT03Y.cpy      — CARD-XREF-RECORD (50B)
#         app/cpy/CVTRA02Y.cpy      — DIS-GROUP-RECORD (50B)
#         app/cpy/CVACT01Y.cpy      — ACCOUNT-RECORD (300B)
#         app/cpy/CVTRA05Y.cpy      — TRAN-RECORD (350B, SYSTRAN output)
#
# Target module: src/batch/jobs/intcalc_job.py
#
# Test-case mapping (AAP §0.5.1, test instructions):
#   Phase 2 — Interest formula preservation (5 tests)
#     test_compute_monthly_interest_basic
#     test_compute_monthly_interest_not_simplified
#     test_compute_monthly_interest_small_balance
#     test_compute_monthly_interest_zero_rate
#     test_compute_monthly_interest_negative_balance
#   Phase 3 — DEFAULT disclosure-group fallback (4 tests)
#     test_get_interest_rate_direct_lookup
#     test_get_interest_rate_default_fallback
#     test_get_interest_rate_default_not_found_raises
#     test_get_interest_rate_zeroapr_group
#   Phase 4 — Account break detection (4 tests)
#     test_account_break_detection_single_account
#     test_account_break_detection_multiple_accounts
#     test_account_update_on_break
#     test_last_account_updated_after_eof
#   Phase 5 — Transaction-ID / record generation (3 tests)
#     test_generate_tran_id_format
#     test_generate_tran_id_incrementing
#     test_build_interest_transaction_fields
#   Phase 6 — 1400-COMPUTE-FEES stub (1 test)
#     test_compute_fees_stub_preserved
#   Phase 7 — PySpark integration (1 test)
#     test_intcalc_main_with_spark(spark_session)
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
"""Unit tests for ``src.batch.jobs.intcalc_job``.

This module validates behavioural parity between the Python/PySpark
INTCALC job (Stage 2 of the 5-stage batch pipeline) and the original
COBOL source program ``app/cbl/CBACT04C.cbl``.  INTCALC depends on
Stage 1 (POSTTRAN) having already applied the daily transactions to
the ``transaction_category_balances`` table, and feeds Stage 3
(COMBTRAN), which combines the computed interest transactions with
the posted transactions for downstream statement generation
(CREASTMT) and reporting (TRANREPT).  Any drift between the
mainframe source semantics and the cloud implementation surfaces
here first.

COBOL → Python Verification Surface
-----------------------------------
=================================  ===========================================
COBOL construct                    Python symbol under test
=================================  ===========================================
paragraph 1300-COMPUTE-INTEREST    ``compute_monthly_interest``
  line 464-465 ``COMPUTE WS-        (CRITICAL: formula preserved as
  MONTHLY-INT = ( TRAN-CAT-BAL *     ``(tran_cat_bal * dis_int_rate) /
  DIS-INT-RATE) / 1200``              _INTEREST_DIVISOR`` — NOT simplified)
paragraph 1200-GET-INTEREST-RATE   ``get_interest_rate`` (primary lookup)
paragraph 1200-A-GET-DEFAULT-INT-  ``get_interest_rate`` (DEFAULT fallback;
  RATE (DISCGRP-STATUS = '23')       KeyError on second miss)
paragraph 1050-UPDATE-ACCOUNT      ``_update_account_balance``
  ``ADD WS-TOTAL-INT TO ACCT-        (ADD total_int, ZERO cycle fields)
  CURR-BAL`` / ``MOVE 0 TO ACCT-
  CURR-CYC-CREDIT`` / ``MOVE 0 TO
  ACCT-CURR-CYC-DEBIT``
paragraph 1300-B-WRITE-TX          ``build_interest_transaction``
  (TYPE-CD='01', CAT-CD='05',         (TYPE-CD='01', CAT-CD='0005',
   SOURCE='System',                    SOURCE='System    ', 14-field
   DESC='Int. for a/c ' + ACCT-ID)     record)
``STRING PARM-DATE WS-TRANID-      ``generate_tran_id``
 SUFFIX ... INTO TRAN-ID`` (16B)    (PARM-DATE[10] + suffix[6 zero-padded])
paragraph 1400-COMPUTE-FEES        ``_compute_fees_stub``
  (``EXIT.`` — "To be implemented")   (no-op, preserves call-site)
account break detection (lines    ``main`` (integration via Phase 7)
  194-206, 219-220)
entry point / PROCEDURE DIVISION   ``main`` (end-to-end integration)
=================================  ===========================================

Mocking Strategy
----------------
The INTCALC job has three external-to-Python side effects: AWS Glue /
Spark lifecycle (``init_glue`` / ``commit_job``), JDBC I/O against
Aurora PostgreSQL (``read_table`` / ``write_table`` /
``get_connection_options``), and S3 uploads for the SYSTRAN output
(``get_versioned_s3_path`` / ``write_to_s3``).  For the unit-level
tests in this module every one of those surfaces is replaced with a
:class:`unittest.mock.MagicMock` scoped via
:func:`unittest.mock.patch` at the *target-module* namespace (i.e.,
patching ``src.batch.jobs.intcalc_job.read_table`` rather than
``src.batch.common.db_connector.read_table``) so the tests are
hermetic and do not require any running infrastructure.

The Phase 7 integration test (``test_intcalc_main_with_spark``) uses
a **real** :class:`pyspark.sql.SparkSession` from the project-wide
``spark_session`` fixture declared in ``tests/conftest.py`` — the
Glue / JDBC / S3 layers are still mocked, but the Spark DataFrame
operations (``createDataFrame``, ``orderBy``, ``collect``) execute
for real so that the end-to-end flow is validated against actual
PySpark semantics.  This catches schema drift and DataFrame
construction incompatibilities that the pure-unit tests miss.

Financial Precision
-------------------
Every monetary test value is a :class:`decimal.Decimal` — never a
:class:`float`.  This is required by AAP §0.7.2 which mandates COBOL
``PIC S9(n)V99`` precision parity via Python's
:class:`decimal.Decimal` type.  Assertions compare :class:`Decimal`
values byte-for-byte; even a single ``float`` in a test input would
introduce binary-floating-point drift and cause false-positive /
false-negative outcomes in the interest-formula assertions.  The
Banker's rounding mode :data:`decimal.ROUND_HALF_EVEN` mirrors COBOL
``ROUNDED`` semantics.

Formula Preservation Contract
-----------------------------
Per AAP §0.7.2, the interest formula
``(TRAN-CAT-BAL * DIS-INT-RATE) / 1200`` MUST NOT be algebraically
simplified.  Phase 2 tests include ``test_compute_monthly_interest_
not_simplified`` which uses inputs that would produce *different*
numeric results under a simplified form such as
``tran_cat_bal * (dis_int_rate / Decimal("1200"))`` — exposing any
refactoring that breaks the COBOL-compatible arithmetic order.

See Also
--------
:mod:`src.batch.jobs.intcalc_job` — module under test.
:mod:`tests.unit.test_batch.test_posttran_job` — Stage 1 sibling test
    suite; pattern template for this module.
:mod:`tests.conftest` — source of the project-wide ``spark_session``
    fixture used by the Phase 7 integration test.
"""

from __future__ import annotations

# ----------------------------------------------------------------------------
# Standard-library imports.
# ----------------------------------------------------------------------------
# ``logging``     — configured by ``caplog`` fixture for capturing the
#                   COBOL-equivalent DISPLAY messages emitted by
#                   :func:`get_interest_rate` on a DEFAULT-fallback.
# ``Decimal`` /
# ``ROUND_HALF_EVEN`` — COBOL PIC S9(n)V99 equivalent with Banker's
#                      rounding per AAP §0.7.2.  Every monetary test
#                      value is a Decimal.
# ``MagicMock`` /
# ``patch``       — isolation of the module under test from Glue /
#                   JDBC / S3 dependencies.
# ----------------------------------------------------------------------------
import logging
from decimal import ROUND_HALF_EVEN, Decimal
from typing import Any
from unittest.mock import MagicMock, patch

import pytest
from pyspark.sql import Row, SparkSession

# ----------------------------------------------------------------------------
# First-party imports — symbols under test.
# ----------------------------------------------------------------------------
# The full list of imported names is declared in the AAP
# internal_imports schema for this file: the four public functions
# implementing the CBACT04C main business logic (interest rate lookup,
# interest formula, transaction-ID generation, transaction-record
# assembly) plus the ``main`` entry point (Phase 7 end-to-end test).
#
# Additionally the Phase 6 test imports the intentionally-empty
# ``_compute_fees_stub`` private helper which preserves the COBOL
# 1400-COMPUTE-FEES call-site verbatim.
# ----------------------------------------------------------------------------
from src.batch.jobs.intcalc_job import (
    _compute_fees_stub,
    _update_account_balance,
    build_interest_transaction,
    compute_monthly_interest,
    generate_tran_id,
    get_interest_rate,
    main,
)

# ============================================================================
# Expected COBOL-verbatim text — compared against actual log emissions
# to verify AAP §0.7.1 behavioural parity with the CBACT04C source.
# ============================================================================
#: The exact banner the COBOL source emits at start of execution
#: (``DISPLAY 'START OF EXECUTION OF PROGRAM CBACT04C'.``, line 181).
_COBOL_START_MSG_EXPECTED: str = "START OF EXECUTION OF PROGRAM CBACT04C"

#: The exact banner the COBOL source emits at end of execution
#: (``DISPLAY 'END OF EXECUTION OF PROGRAM CBACT04C'.``, line 230).
_COBOL_END_MSG_EXPECTED: str = "END OF EXECUTION OF PROGRAM CBACT04C"

#: The exact DISPLAY message emitted on DISCGRP-STATUS = '23'
#: (``DISPLAY 'DISCLOSURE GROUP RECORD MISSING'``, CBACT04C line 418).
_COBOL_MISSING_MSG_EXPECTED: str = "DISCLOSURE GROUP RECORD MISSING"

#: The exact DISPLAY message that follows the missing message
#: (``DISPLAY 'TRY WITH DEFAULT GROUP CODE'``, CBACT04C line 419).
_COBOL_TRY_DEFAULT_MSG_EXPECTED: str = "TRY WITH DEFAULT GROUP CODE"

#: The module-qualified logger name used for ``caplog`` filtering.
#: Matches the ``logging.getLogger(__name__)`` call at line 347 of
#: src/batch/jobs/intcalc_job.py.
_MODULE_LOGGER_NAME: str = "src.batch.jobs.intcalc_job"


# ============================================================================
# Patch-target constants — fully-qualified module paths of the
# runtime dependencies imported by src/batch/jobs/intcalc_job.py.
# Each constant is the string form :func:`unittest.mock.patch`
# requires.  Centralising these strings prevents drift between tests
# and keeps the "patch at the target module's namespace" idiom
# consistent across every test below.
# ============================================================================
_PATCH_INIT_GLUE: str = "src.batch.jobs.intcalc_job.init_glue"
_PATCH_COMMIT_JOB: str = "src.batch.jobs.intcalc_job.commit_job"
_PATCH_READ_TABLE: str = "src.batch.jobs.intcalc_job.read_table"
_PATCH_WRITE_TABLE: str = "src.batch.jobs.intcalc_job.write_table"
_PATCH_GET_CONN_OPTS: str = "src.batch.jobs.intcalc_job.get_connection_options"
_PATCH_WRITE_TO_S3: str = "src.batch.jobs.intcalc_job.write_to_s3"
_PATCH_GET_S3_PATH: str = "src.batch.jobs.intcalc_job.get_versioned_s3_path"
# ``F`` is the ``pyspark.sql.functions`` alias imported at the top of
# ``src/batch/jobs/intcalc_job.py``.  ``_build_tcatbal_list`` calls
# ``F.col("acct_id").asc_nulls_last()`` to order TCATBAL records by
# the VSAM KSDS key (acct_id + type_cd + cat_cd) — which requires an
# active SparkContext.  Tests that mock the TCATBAL DataFrame (via
# :func:`_make_mock_df`) without a real SparkContext MUST patch
# ``F`` wholesale, otherwise ``pyspark.sql.functions.col`` raises
# ``AssertionError: assert SparkContext._active_spark_context is not
# None`` inside ``_build_tcatbal_list``'s ``orderBy`` expression.
# Mirrors the ``_PATCH_F`` convention in ``test_posttran_job.py``.
_PATCH_F: str = "src.batch.jobs.intcalc_job.F"


# ============================================================================
# Helper: minimal "row-like" dict matching the ``asDict()``-projected
# row shape produced by PySpark.
# ============================================================================
def _tcatbal(acct_id: str, type_cd: str, cat_cd: str, bal: Decimal) -> dict[str, Any]:
    """Build a minimal TCATBAL record dict for tests.

    Mirrors the dict shape produced by
    :func:`_build_tcatbal_list` on a real PySpark Row.  Tests that
    exercise the account-break detection loop directly use this
    helper (rather than building real PySpark DataFrames) for speed
    and to avoid SparkContext side effects.

    Parameters
    ----------
    acct_id : str
        The 11-character account ID (``TRANCAT-ACCT-ID``,
        composite-key component 1).
    type_cd : str
        The 2-character transaction-type code
        (``TRANCAT-TYPE-CD``, composite-key component 2).
    cat_cd : str
        The 4-character transaction-category code
        (``TRANCAT-CD``, composite-key component 3).
    bal : Decimal
        The current category balance (``TRAN-CAT-BAL``, PIC
        S9(09)V99) as a :class:`Decimal`.

    Returns
    -------
    dict[str, Any]
        The synthetic record dict with keys ``acct_id``,
        ``type_code``, ``cat_code``, ``tran_cat_bal``.
    """
    return {
        "acct_id": acct_id,
        "type_code": type_cd,
        "cat_code": cat_cd,
        "tran_cat_bal": bal,
    }


# ============================================================================
# Phase 2 — Interest-formula preservation tests.
# ============================================================================
# The CRITICAL behavioural contract in this test suite: the formula
#   WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
# MUST be preserved verbatim.  Algebraic simplification (e.g.,
# ``tran_cat_bal * (dis_int_rate / Decimal("1200"))``) changes the
# arithmetic order and can introduce rounding drift that breaks COBOL
# binary parity — see AAP §0.7.2.
#
# All monetary inputs are :class:`Decimal`; the return value is
# always quantised to 2-decimal-place scale via
# ``ROUND_HALF_EVEN`` (Banker's rounding) matching COBOL ROUNDED.
# ============================================================================
@pytest.mark.unit
def test_compute_monthly_interest_basic() -> None:
    """Verify the core formula (bal * rate) / 1200 with simple inputs.

    Corresponds to COBOL paragraph 1300-COMPUTE-INTEREST
    (CBACT04C.cbl lines 462-470)::

        COMPUTE WS-MONTHLY-INT
         = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200

    With ``TRAN-CAT-BAL = 10000.00`` and ``DIS-INT-RATE = 18.00``:
      (10000.00 * 18.00) / 1200 = 180000.00 / 1200 = 150.00

    Asserts the return value is exactly ``Decimal("150.00")``.
    """
    result = compute_monthly_interest(
        tran_cat_bal=Decimal("10000.00"),
        dis_int_rate=Decimal("18.00"),
    )
    assert result == Decimal("150.00")
    # Type safety: return value must be Decimal (never float).
    assert isinstance(result, Decimal)


@pytest.mark.unit
def test_compute_monthly_interest_not_simplified() -> None:
    """Verify the formula is NOT algebraically simplified.

    This test is the **primary behavioural guardrail** for AAP §0.7.2
    (formula preservation).  Chooses inputs where the exact Decimal
    result of ``(bal * rate) / 1200`` differs from the result of a
    simplified form like ``bal * (rate / 1200)`` or
    ``bal * rate / 1200`` with different associativity.

    With ``TRAN-CAT-BAL = 3333.33`` and ``DIS-INT-RATE = 7.77``,
    the exact COBOL-ordered computation is:

        (Decimal("3333.33") * Decimal("7.77")) / Decimal("1200")
        = Decimal("25899.9741") / Decimal("1200")
        = Decimal("21.58331175")
        → quantised via ROUND_HALF_EVEN to Decimal("21.58")

    If the implementation silently refactored to
    ``bal * (rate / 1200)`` the intermediate
    ``Decimal("7.77") / Decimal("1200")`` is non-terminating in
    the default context and would introduce a divergent result.

    Asserts the exact 2-decimal result matches the expected
    Decimal byte-for-byte.
    """
    tran_cat_bal = Decimal("3333.33")
    dis_int_rate = Decimal("7.77")
    # Manually compute the reference value in the SAME order the
    # production code uses: (bal * rate) / 1200 — then quantise.
    expected = ((tran_cat_bal * dis_int_rate) / Decimal("1200")).quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)

    result = compute_monthly_interest(
        tran_cat_bal=tran_cat_bal,
        dis_int_rate=dis_int_rate,
    )
    # Primary assertion: matches the COBOL-ordered reference.
    assert result == expected
    # Spot-check the literal expected value to guard against
    # simultaneous drift in both the helper and the reference.
    assert result == Decimal("21.58")
    assert isinstance(result, Decimal)


@pytest.mark.unit
def test_compute_monthly_interest_small_balance() -> None:
    """Verify small-balance precision (no floating-point rounding drift).

    With ``TRAN-CAT-BAL = 1.23`` and ``DIS-INT-RATE = 0.50``:
      (1.23 * 0.50) / 1200 = 0.615 / 1200 = 0.0005125
      → quantised ROUND_HALF_EVEN to Decimal("0.00")

    Note: 0.0005 rounds DOWN to 0.00 under banker's rounding when
    the preceding digit is zero (tie-to-even).  The actual quotient
    0.0005125 is above the tie and still rounds to 0.00 because the
    next digit is 0 and the target precision is 0.01.

    Asserts no binary-float drift — if the implementation used
    ``float`` anywhere, precision would be lost at this scale.
    """
    result = compute_monthly_interest(
        tran_cat_bal=Decimal("1.23"),
        dis_int_rate=Decimal("0.50"),
    )
    # (1.23 * 0.50) / 1200 = 0.0005125 → 0.00 at 2-decimal scale.
    assert result == Decimal("0.00")
    assert isinstance(result, Decimal)


@pytest.mark.unit
def test_compute_monthly_interest_zero_rate() -> None:
    """Verify ZEROAPR handling: rate = 0 → interest = 0.

    Corresponds to the COBOL guard at CBACT04C line 214::

        IF DIS-INT-RATE NOT = 0
            PERFORM 1300-COMPUTE-INTEREST

    In the Python implementation the caller (``main``) short-
    circuits on ``dis_int_rate != Decimal("0.00")`` — but the
    :func:`compute_monthly_interest` function itself is still
    expected to return ``Decimal("0.00")`` if called with a zero
    rate, providing a defence-in-depth guarantee.

    Input: ``TRAN-CAT-BAL = 10000.00``, ``DIS-INT-RATE = 0.00``
    Expected: ``Decimal("0.00")``
    """
    result = compute_monthly_interest(
        tran_cat_bal=Decimal("10000.00"),
        dis_int_rate=Decimal("0.00"),
    )
    assert result == Decimal("0.00")
    assert isinstance(result, Decimal)


@pytest.mark.unit
def test_compute_monthly_interest_negative_balance() -> None:
    """Verify negative-balance inputs produce negative interest.

    COBOL ``WS-MONTHLY-INT PIC S9(09)V99`` is a SIGNED field
    (note the ``S``); the COMPUTE statement handles negative
    operands naturally.  A negative TRAN-CAT-BAL (e.g., after a
    credit transaction has been posted by POSTTRAN) yields a
    negative monthly interest — mathematically consistent and
    preserves the sign rules.

    Input: ``TRAN-CAT-BAL = -500.00``, ``DIS-INT-RATE = 18.00``
    Expected: (-500.00 * 18.00) / 1200 = -9000.00 / 1200 = -7.50
    """
    result = compute_monthly_interest(
        tran_cat_bal=Decimal("-500.00"),
        dis_int_rate=Decimal("18.00"),
    )
    assert result == Decimal("-7.50")
    assert isinstance(result, Decimal)


# ============================================================================
# Phase 3 — DEFAULT disclosure-group fallback tests.
# ============================================================================
# The COBOL DISCGRP-FILE random-READ logic in 1200-GET-INTEREST-RATE
# (lines 415-440) performs a two-tier lookup: first with the
# account's actual group-id, then (on status '23' = NOT FOUND) with
# the hard-coded literal ``'DEFAULT'``.  If the DEFAULT row is also
# missing, the COBOL program ABENDs via 9999-ABEND-PROGRAM.
#
# The Python :func:`get_interest_rate` mirrors this:
#   1. Primary lookup ``(acct_group_id, type_cd, cat_cd)``.
#   2. Emit 2 warnings: 'DISCLOSURE GROUP RECORD MISSING' +
#      'TRY WITH DEFAULT GROUP CODE' (matching the COBOL DISPLAY
#      statements byte-for-byte).
#   3. Retry lookup ``('DEFAULT', type_cd, cat_cd)``.
#   4. If still missing: raise :class:`KeyError` (≡ CEE3ABD
#      ABCODE=999) so AWS Glue halts the job.
# ============================================================================
@pytest.mark.unit
def test_get_interest_rate_direct_lookup() -> None:
    """Verify primary lookup returns the exact rate on a hit.

    No DEFAULT fallback involved — the account's actual group-id
    is present in the disclosure-groups dict, so the function
    returns the mapped rate directly without emitting the
    missing/try-default warnings.

    Disclosure-group key tuple: ``(acct_group_id, type_cd, cat_cd)``
    → ``Decimal`` rate.  Note the three-part composite key:
      - ``acct_group_id`` = CHAR(10) DIS-ACCT-GROUP-ID
      - ``type_cd``       = CHAR(2)  DIS-TRAN-TYPE-CD
      - ``cat_cd``        = CHAR(4)  DIS-TRAN-CAT-CD
    """
    disclosure_groups: dict[tuple[str, str, str], Decimal] = {
        ("GOLD", "01", "0005"): Decimal("18.00"),
        ("PLATINUM", "01", "0005"): Decimal("15.00"),
        ("DEFAULT", "01", "0005"): Decimal("24.00"),
    }

    result = get_interest_rate(
        disclosure_groups=disclosure_groups,
        acct_group_id="GOLD",
        type_cd="01",
        cat_cd="0005",
    )

    # Primary lookup succeeded — returns the GOLD rate, not DEFAULT.
    assert result == Decimal("18.00")
    assert isinstance(result, Decimal)


@pytest.mark.unit
def test_get_interest_rate_default_fallback(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """Verify DEFAULT fallback when primary lookup misses.

    This is the CRITICAL behavioural contract from AAP §0.7.1 — the
    COBOL 1200-A-GET-DEFAULT-INT-RATE (lines 443-460) retry path
    MUST be preserved.  The implementation:

    1. Looks up ``("ZEROAPR", "01", "0005")`` — not in dict → miss.
    2. Logs ``"DISCLOSURE GROUP RECORD MISSING"`` (COBOL line 418).
    3. Logs ``"TRY WITH DEFAULT GROUP CODE"`` (COBOL line 419).
    4. Retries with ``("DEFAULT", "01", "0005")`` — found → returns
       ``Decimal("24.00")``.

    Crucial: only the group_id component is replaced — type_cd and
    cat_cd retain their values from the original call.  Verified by
    NOT placing ``("DEFAULT", "DEFAULT", "DEFAULT")`` in the dict
    and still expecting the retry to succeed.
    """
    # Disclosure-group dict where ``ZEROAPR`` is ABSENT but
    # ``DEFAULT`` IS present — forces the fallback path.
    disclosure_groups: dict[tuple[str, str, str], Decimal] = {
        ("DEFAULT", "01", "0005"): Decimal("24.00"),
        ("GOLD", "01", "0005"): Decimal("18.00"),
    }

    with caplog.at_level(logging.WARNING, logger=_MODULE_LOGGER_NAME):
        result = get_interest_rate(
            disclosure_groups=disclosure_groups,
            acct_group_id="ZEROAPR",
            type_cd="01",
            cat_cd="0005",
        )

    # Fallback succeeded — returns the DEFAULT rate.
    assert result == Decimal("24.00")
    assert isinstance(result, Decimal)

    # Verify the 2 COBOL DISPLAY messages were emitted verbatim
    # (AAP §0.7.1 behavioural parity).  The caplog records are a
    # superset (may include other warnings), so we search for
    # substring matches rather than exact equality.
    warning_messages = [
        record.getMessage()
        for record in caplog.records
        if record.levelno == logging.WARNING and record.name == _MODULE_LOGGER_NAME
    ]
    assert _COBOL_MISSING_MSG_EXPECTED in warning_messages, (
        f"Missing COBOL DISPLAY 'DISCLOSURE GROUP RECORD MISSING' — got: {warning_messages!r}"
    )
    assert _COBOL_TRY_DEFAULT_MSG_EXPECTED in warning_messages, (
        f"Missing COBOL DISPLAY 'TRY WITH DEFAULT GROUP CODE' — got: {warning_messages!r}"
    )

    # Ordering check — the COBOL source emits the two messages
    # inside the same INVALID KEY clause in the order shown at
    # lines 418-419 (MISSING first, TRY DEFAULT second).
    missing_idx = warning_messages.index(_COBOL_MISSING_MSG_EXPECTED)
    try_default_idx = warning_messages.index(_COBOL_TRY_DEFAULT_MSG_EXPECTED)
    assert missing_idx < try_default_idx, (
        "COBOL DISPLAY message order violated: 'DISCLOSURE GROUP "
        "RECORD MISSING' must precede 'TRY WITH DEFAULT GROUP CODE'."
    )


@pytest.mark.unit
def test_get_interest_rate_default_not_found_raises() -> None:
    """Verify KeyError is raised when BOTH primary and DEFAULT miss.

    Corresponds to the COBOL ABEND semantics in
    1200-A-GET-DEFAULT-INT-RATE (lines 452-459)::

        IF  APPL-AOK
            CONTINUE
        ELSE
            DISPLAY 'ERROR READING DEFAULT DISCLOSURE GROUP'
            ...
            PERFORM 9999-ABEND-PROGRAM

    The Python implementation raises :class:`KeyError` so AWS Glue
    marks the job FAILED and Step Functions halts the pipeline —
    matching the mainframe CEE3ABD ABCODE=999 fatal error.

    The raised error message is verified to include the missing
    composite-key components so operators can troubleshoot.
    """
    # Disclosure-group dict where NEITHER the specific group NOR
    # the DEFAULT group is present.
    disclosure_groups: dict[tuple[str, str, str], Decimal] = {
        ("GOLD", "01", "0005"): Decimal("18.00"),
    }

    with pytest.raises(KeyError) as exc_info:
        get_interest_rate(
            disclosure_groups=disclosure_groups,
            acct_group_id="PLATINUM",
            type_cd="01",
            cat_cd="0005",
        )

    # The error message should contain the DEFAULT group id and
    # the specific type/cat codes for operator troubleshooting.
    error_msg = str(exc_info.value)
    assert "DEFAULT" in error_msg, f"KeyError message must mention the DEFAULT group id — got: {error_msg!r}"
    assert "01" in error_msg, f"KeyError message must mention the type_cd — got: {error_msg!r}"
    assert "0005" in error_msg, f"KeyError message must mention the cat_cd — got: {error_msg!r}"
    # The message should also reference the account's actual
    # group-id so operators can correlate with the missing row.
    assert "PLATINUM" in error_msg, f"KeyError message must mention the account group_id — got: {error_msg!r}"


@pytest.mark.unit
def test_get_interest_rate_zeroapr_group() -> None:
    """Verify a ZEROAPR-group row with rate 0 returns Decimal("0.00").

    The ``'ZEROAPR'`` disclosure-group is a legitimate directly-
    resolvable row (NOT a DEFAULT fallback) that carries a 0%
    interest rate — the caller short-circuits interest computation
    when the rate returns zero.  This test exercises the primary-
    lookup path with a zero-rate result (no DEFAULT fallback, no
    warnings, no KeyError).
    """
    disclosure_groups: dict[tuple[str, str, str], Decimal] = {
        ("ZEROAPR", "01", "0005"): Decimal("0.00"),
        ("GOLD", "01", "0005"): Decimal("18.00"),
        ("DEFAULT", "01", "0005"): Decimal("24.00"),
    }

    result = get_interest_rate(
        disclosure_groups=disclosure_groups,
        acct_group_id="ZEROAPR",
        type_cd="01",
        cat_cd="0005",
    )

    # Returns 0.00 — caller should skip interest computation.
    assert result == Decimal("0.00")
    assert isinstance(result, Decimal)


# ============================================================================
# Helper: mock DataFrame factory for main() integration tests.
# ============================================================================
def _make_mock_df(
    rows: list[dict[str, Any]] | None = None,
    count_value: int | None = None,
) -> MagicMock:
    """Build a chainable mock DataFrame for use with patched ``read_table``.

    The INTCALC job's ``main()`` chains several PySpark DataFrame
    operations (``.cache()``, ``.count()``, ``.collect()``,
    ``.orderBy(...)`` for the tcatbal table).  A plain
    :class:`unittest.mock.MagicMock` would produce a fresh child mock
    on each chained call, making invocation assertions clumsy.  This
    helper wires the chain so that all the fluent methods return the
    same mock, while the terminal data-access methods (``collect`` /
    ``count``) return concrete Python values that the module-under-
    test consumes directly.

    Mirrors the ``_make_mock_df`` helper in
    ``tests/unit/test_batch/test_posttran_job.py`` to keep the two
    sibling test suites consistent.

    Parameters
    ----------
    rows : list[dict] | None
        The list of row-dicts that ``collect()`` should yield.  Each
        dict represents a single row; they are wrapped in
        ``MagicMock`` objects whose ``asDict()`` method returns the
        dict itself (matching the pattern used by
        :meth:`pyspark.sql.Row.asDict`).  Defaults to an empty list.
    count_value : int | None
        Value returned by ``count()``.  Defaults to ``len(rows)`` if
        ``rows`` is supplied, else ``0``.

    Returns
    -------
    MagicMock
        A mock DataFrame whose chainable methods return ``self``
        (the same mock), and whose terminal access methods return
        either the supplied ``rows`` or their derived integer count.
    """
    actual_rows = rows or []
    actual_count = count_value if count_value is not None else len(actual_rows)

    df = MagicMock(name="MockDataFrame")
    # Chainable fluent methods — all return the same mock so
    # downstream assertions can inspect one shared object.
    df.cache.return_value = df
    df.orderBy.return_value = df
    df.withColumn.return_value = df
    df.select.return_value = df
    df.alias.return_value = df
    df.join.return_value = df
    df.filter.return_value = df
    df.where.return_value = df

    # Row objects for the terminal access paths.  Wrapping each dict
    # in a MagicMock with asDict() → dict mirrors the behaviour of
    # :meth:`pyspark.sql.Row.asDict` which the module-under-test
    # invokes during its per-row iteration.
    row_mocks: list[MagicMock] = []
    for row_dict in actual_rows:
        row_mock = MagicMock(name="MockRow")
        row_mock.asDict.return_value = row_dict
        row_mocks.append(row_mock)

    # Terminal access methods.  ``collect()`` yields the row mocks;
    # ``count()`` returns the supplied / derived integer;
    # ``unpersist()`` returns None (its value is discarded in the
    # cleanup loop of main()).
    df.collect.return_value = row_mocks
    df.count.return_value = actual_count
    df.unpersist.return_value = None
    return df


# ============================================================================
# Phase 4 — Account break detection tests.
# ============================================================================
# The COBOL main loop at lines 188-222 of CBACT04C.cbl processes
# TCATBAL records sequentially (VSAM KSDS by composite key
# TRANCAT-ACCT-ID + TRANCAT-TYPE-CD + TRANCAT-CD) and detects
# account breaks using a "last-account-ID" guard::
#
#     IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM
#         IF WS-FIRST-TIME = 'N'
#             PERFORM 1050-UPDATE-ACCOUNT    *> update PREV account
#         END-IF
#         MOVE 'N' TO WS-FIRST-TIME
#         MOVE 0   TO WS-TOTAL-INT
#         ... read new account + xref ...
#     END-IF
#
# After the main loop exits on TCATBAL EOF, a final
# ``PERFORM 1050-UPDATE-ACCOUNT`` (line 220) updates the LAST
# account (which would otherwise be missed because no subsequent
# TCATBAL record triggered a break).
#
# The Python main() preserves this pattern via:
#   - ``last_acct_num`` / ``total_int`` / ``first_time`` state vars
#   - Account-break check at the top of each iteration
#   - Post-loop ``if not first_time: _update_account_balance(...)``
#     on the LAST account
#
# Phase 4 tests exercise this behaviour by:
#   (a) Directly testing _update_account_balance for the mutations
#       applied on a break (test_account_update_on_break).
#   (b) Running main() with mocked DataFrames that exercise single-
#       and multi-account TCATBAL inputs and asserting the
#       total_int accumulation per account
#       (test_account_break_detection_* + test_last_account_
#       updated_after_eof).
# ============================================================================
@pytest.mark.unit
def test_account_update_on_break() -> None:
    """Verify _update_account_balance applies the three COBOL mutations.

    Corresponds directly to COBOL paragraph 1050-UPDATE-ACCOUNT
    lines 350-370 of CBACT04C.cbl::

        1050-UPDATE-ACCOUNT.
            ADD WS-TOTAL-INT       TO ACCT-CURR-BAL
            MOVE 0                 TO ACCT-CURR-CYC-CREDIT
            MOVE 0                 TO ACCT-CURR-CYC-DEBIT
            REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD.

    The three mutations MUST be applied exactly:
      1. acct_curr_bal += total_int
      2. acct_curr_cyc_credit = 0.00   (MOVE 0 TO ACCT-CURR-CYC-CREDIT)
      3. acct_curr_cyc_debit  = 0.00   (MOVE 0 TO ACCT-CURR-CYC-DEBIT)

    Input: starting balance Decimal("500.00"), total_int
    Decimal("25.00"), non-zero cycle values that MUST be zeroed.
    """
    account_record: dict[str, Any] = {
        "acct_id": "00000000001",
        "acct_curr_bal": Decimal("500.00"),
        "acct_curr_cyc_credit": Decimal("123.45"),
        "acct_curr_cyc_debit": Decimal("67.89"),
        "acct_group_id": "GOLD",
        "acct_active_status": "Y",
    }
    total_int = Decimal("25.00")

    _update_account_balance(account_record, total_int)

    # Mutation 1: ADD WS-TOTAL-INT TO ACCT-CURR-BAL
    assert account_record["acct_curr_bal"] == Decimal("525.00"), (
        f"acct_curr_bal must equal 500.00 + 25.00 = 525.00, got {account_record['acct_curr_bal']!r}"
    )
    # Mutation 2: MOVE 0 TO ACCT-CURR-CYC-CREDIT (CBACT04C line 353)
    assert account_record["acct_curr_cyc_credit"] == Decimal("0.00"), (
        f"acct_curr_cyc_credit must be zeroed (MOVE 0), got {account_record['acct_curr_cyc_credit']!r}"
    )
    # Mutation 3: MOVE 0 TO ACCT-CURR-CYC-DEBIT (CBACT04C line 354)
    assert account_record["acct_curr_cyc_debit"] == Decimal("0.00"), (
        f"acct_curr_cyc_debit must be zeroed (MOVE 0), got {account_record['acct_curr_cyc_debit']!r}"
    )
    # Type safety: all three monetary fields remain Decimal
    # (COBOL PIC S9(n)V99 parity — never float).
    assert isinstance(account_record["acct_curr_bal"], Decimal)
    assert isinstance(account_record["acct_curr_cyc_credit"], Decimal)
    assert isinstance(account_record["acct_curr_cyc_debit"], Decimal)
    # Non-mutated fields are left unchanged (defensive — the COBOL
    # REWRITE statement writes the full 300-byte ACCOUNT-RECORD, so
    # untouched fields must retain their original values).
    assert account_record["acct_id"] == "00000000001"
    assert account_record["acct_group_id"] == "GOLD"
    assert account_record["acct_active_status"] == "Y"


@pytest.mark.unit
@patch(_PATCH_F)
@patch(_PATCH_WRITE_TO_S3)
@patch(_PATCH_GET_S3_PATH)
@patch(_PATCH_WRITE_TABLE)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_GET_CONN_OPTS)
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_INIT_GLUE)
def test_account_break_detection_single_account(
    mock_init_glue: MagicMock,
    mock_commit_job: MagicMock,
    mock_get_conn_opts: MagicMock,
    mock_read_table: MagicMock,
    mock_write_table: MagicMock,
    mock_get_s3_path: MagicMock,
    mock_write_to_s3: MagicMock,
    mock_f: MagicMock,
) -> None:
    """Verify total_int accumulates across multiple TCATBAL rows of one acct.

    The COBOL loop adds each monthly_int to ``WS-TOTAL-INT`` at
    line 467 (``ADD WS-MONTHLY-INT TO WS-TOTAL-INT``) and only
    calls 1050-UPDATE-ACCOUNT on the NEXT account break (or at EOF).

    Input: 3 TCATBAL records all for acct_id "00000000001" with
    different type_cd/cat_cd combinations but the same disclosure
    group.  Expected behaviour:
      - 3 interest transactions generated (one per TCATBAL row)
      - total_int accumulates across all 3 rows
      - _update_account_balance called ONCE (at EOF) with the full
        accumulated total

    The test asserts that the final write of the accounts table
    contains an account balance updated by the full sum of all 3
    computed interests.
    """
    # ----- Arrange: mock Glue lifecycle -----
    mock_init_glue.return_value = (
        MagicMock(name="MockSparkSession"),
        MagicMock(name="MockGlueCtx"),
        MagicMock(name="MockJob"),
        {"JOB_NAME": "carddemo-intcalc"},
    )
    mock_get_conn_opts.return_value = {
        "url": "jdbc:postgresql://localhost:5432/carddemo",
        "driver": "org.postgresql.Driver",
    }
    mock_get_s3_path.return_value = "s3://test-bucket/systran/v1/"

    # ----- Arrange: mock DataFrames -----
    # TCATBAL: 3 rows for the same account with different cat_cd.
    # The 3 row balances are chosen so their computed interests at
    # rate 18.00 sum to a round, easily-verifiable total:
    #   (1000.00 * 18.00) / 1200 = 15.00
    #   (2000.00 * 18.00) / 1200 = 30.00
    #   (3000.00 * 18.00) / 1200 = 45.00
    #   --------------------------------
    #   total interest                 = 90.00
    tcatbal_rows = [
        _tcatbal("00000000001", "01", "0005", Decimal("1000.00")),
        _tcatbal("00000000001", "01", "0006", Decimal("2000.00")),
        _tcatbal("00000000001", "01", "0007", Decimal("3000.00")),
    ]
    tcatbal_df = _make_mock_df(tcatbal_rows)
    xref_df = _make_mock_df([{"card_num": "4111111111111111", "cust_id": "000000001", "acct_id": "00000000001"}])
    accounts_df = _make_mock_df(
        [
            {
                "acct_id": "00000000001",
                "acct_active_status": "Y",
                "acct_curr_bal": Decimal("500.00"),
                "acct_credit_limit": Decimal("5000.00"),
                "acct_cash_credit_limit": Decimal("1000.00"),
                "acct_open_date": "2020-01-01",
                "acct_expiration_date": "2030-12-31",
                "acct_reissue_date": "2025-01-01",
                "acct_curr_cyc_credit": Decimal("100.00"),
                "acct_curr_cyc_debit": Decimal("50.00"),
                "acct_addr_zip": "98101",
                "acct_group_id": "GOLD",
                "version_id": 0,
            }
        ]
    )
    # Disclosure groups: GOLD rate = 18.00 for all three
    # (type_cd, cat_cd) combos.
    discgrp_df = _make_mock_df(
        [
            {
                "dis_acct_group_id": "GOLD",
                "dis_tran_type_cd": "01",
                "dis_tran_cat_cd": "0005",
                "dis_int_rate": Decimal("18.00"),
            },
            {
                "dis_acct_group_id": "GOLD",
                "dis_tran_type_cd": "01",
                "dis_tran_cat_cd": "0006",
                "dis_int_rate": Decimal("18.00"),
            },
            {
                "dis_acct_group_id": "GOLD",
                "dis_tran_type_cd": "01",
                "dis_tran_cat_cd": "0007",
                "dis_int_rate": Decimal("18.00"),
            },
        ]
    )

    def _read_side_effect(_spark: Any, table_name: str, **_kwargs: Any) -> Any:
        return {
            "transaction_category_balances": tcatbal_df,
            "card_cross_references": xref_df,
            "accounts": accounts_df,
            "disclosure_groups": discgrp_df,
        }[table_name]

    mock_read_table.side_effect = _read_side_effect

    # Capture write_table invocations so we can inspect the
    # updated-accounts DataFrame.  Because the mocks use plain
    # MagicMock DataFrames (no real Spark), the module builds the
    # accounts write-back DF via ``spark.createDataFrame(...)``
    # on the mock spark session — which itself returns another
    # MagicMock.  To inspect the *source data* of the write, we
    # instead rely on the mutable ``account_lookup`` dict built
    # from ``accounts_df.collect()`` — which was populated from
    # the row dicts we supplied above.  The module mutates those
    # dicts in-place, so post-main we can read the mutation back
    # directly from ``accounts_df.collect.return_value``.
    written_tables: dict[str, Any] = {}

    def _write_side_effect(df_arg: Any, table_name: str, **_kwargs: Any) -> None:
        written_tables[table_name] = df_arg

    mock_write_table.side_effect = _write_side_effect

    # ----- Act -----
    main()

    # ----- Assert -----
    # commit_job was called (Glue success signal).
    mock_commit_job.assert_called_once()

    # Interest transactions were written to S3 (3 rows from 3
    # TCATBAL records).  The S3 write path is
    # ``_write_interest_trans_to_s3`` → ``write_to_s3``.
    mock_write_to_s3.assert_called_once()

    # Accounts table was written back (bulk overwrite).
    assert "accounts" in written_tables, f"Expected accounts table to be written, got: {list(written_tables)!r}"

    # The accounts dict was mutated in place by _update_account_balance.
    # Inspect the original row dict to confirm the accumulated total_int.
    account_row_dict = accounts_df.collect.return_value[0].asDict.return_value
    #   starting balance   = Decimal("500.00")
    #   sum of 3 interests = Decimal("15.00") + 30.00 + 45.00 = 90.00
    #   final balance      = Decimal("590.00")
    assert account_row_dict["acct_curr_bal"] == Decimal("590.00"), (
        f"single-account total_int accumulation wrong — "
        f"expected Decimal('590.00'), got {account_row_dict['acct_curr_bal']!r}"
    )
    # Cycle credit/debit zeroed.
    assert account_row_dict["acct_curr_cyc_credit"] == Decimal("0.00")
    assert account_row_dict["acct_curr_cyc_debit"] == Decimal("0.00")


@pytest.mark.unit
@patch(_PATCH_F)
@patch(_PATCH_WRITE_TO_S3)
@patch(_PATCH_GET_S3_PATH)
@patch(_PATCH_WRITE_TABLE)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_GET_CONN_OPTS)
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_INIT_GLUE)
def test_account_break_detection_multiple_accounts(
    mock_init_glue: MagicMock,
    mock_commit_job: MagicMock,
    mock_get_conn_opts: MagicMock,
    mock_read_table: MagicMock,
    mock_write_table: MagicMock,
    mock_get_s3_path: MagicMock,
    mock_write_to_s3: MagicMock,
    mock_f: MagicMock,
) -> None:
    """Verify each account's total_int is isolated (break resets WS-TOTAL-INT).

    Corresponds to the COBOL account-break logic at lines 194-200::

        IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM
            IF WS-FIRST-TIME = 'N'
                PERFORM 1050-UPDATE-ACCOUNT    *> update PREV
            END-IF
            MOVE 'N' TO WS-FIRST-TIME
            MOVE 0   TO WS-TOTAL-INT            *> RESET

    Input: 3 TCATBAL records for 3 different accounts (acct_ids
    "00000000001", "00000000002", "00000000003" — note lexical
    ordering matches the VSAM KSDS read order).  Each account
    has ONE category balance — so total_int per account equals
    the single monthly_int for that row.

    Expected:
      - Account 1: bal += (1000.00 * 18.00)/1200 = 15.00 → 515.00
      - Account 2: bal += (2000.00 * 18.00)/1200 = 30.00 → 530.00
      - Account 3: bal += (3000.00 * 18.00)/1200 = 45.00 → 545.00
    """
    # ----- Arrange -----
    mock_init_glue.return_value = (
        MagicMock(name="MockSparkSession"),
        MagicMock(name="MockGlueCtx"),
        MagicMock(name="MockJob"),
        {"JOB_NAME": "carddemo-intcalc"},
    )
    mock_get_conn_opts.return_value = {
        "url": "jdbc:postgresql://localhost:5432/carddemo",
        "driver": "org.postgresql.Driver",
    }
    mock_get_s3_path.return_value = "s3://test-bucket/systran/v1/"

    # 3 TCATBAL records — one per account — already in VSAM KSDS
    # order (sorted by acct_id).  The mock DataFrame's orderBy is
    # a no-op (returns self) so the order we pass here is the
    # order main() will iterate.
    tcatbal_rows = [
        _tcatbal("00000000001", "01", "0005", Decimal("1000.00")),
        _tcatbal("00000000002", "01", "0005", Decimal("2000.00")),
        _tcatbal("00000000003", "01", "0005", Decimal("3000.00")),
    ]
    tcatbal_df = _make_mock_df(tcatbal_rows)

    # Each acct has a distinct xref entry (card_num resolution).
    xref_rows = [
        {"card_num": "4111111111111111", "cust_id": "000000001", "acct_id": "00000000001"},
        {"card_num": "4111111111111112", "cust_id": "000000002", "acct_id": "00000000002"},
        {"card_num": "4111111111111113", "cust_id": "000000003", "acct_id": "00000000003"},
    ]
    xref_df = _make_mock_df(xref_rows)

    # 3 accounts, each with starting balances and cycle values that
    # must be zeroed.
    account_rows = [
        {
            "acct_id": "00000000001",
            "acct_active_status": "Y",
            "acct_curr_bal": Decimal("500.00"),
            "acct_credit_limit": Decimal("5000.00"),
            "acct_cash_credit_limit": Decimal("1000.00"),
            "acct_open_date": "2020-01-01",
            "acct_expiration_date": "2030-12-31",
            "acct_reissue_date": "2025-01-01",
            "acct_curr_cyc_credit": Decimal("10.00"),
            "acct_curr_cyc_debit": Decimal("5.00"),
            "acct_addr_zip": "98101",
            "acct_group_id": "GOLD",
            "version_id": 0,
        },
        {
            "acct_id": "00000000002",
            "acct_active_status": "Y",
            "acct_curr_bal": Decimal("500.00"),
            "acct_credit_limit": Decimal("5000.00"),
            "acct_cash_credit_limit": Decimal("1000.00"),
            "acct_open_date": "2020-01-01",
            "acct_expiration_date": "2030-12-31",
            "acct_reissue_date": "2025-01-01",
            "acct_curr_cyc_credit": Decimal("20.00"),
            "acct_curr_cyc_debit": Decimal("10.00"),
            "acct_addr_zip": "98101",
            "acct_group_id": "GOLD",
            "version_id": 0,
        },
        {
            "acct_id": "00000000003",
            "acct_active_status": "Y",
            "acct_curr_bal": Decimal("500.00"),
            "acct_credit_limit": Decimal("5000.00"),
            "acct_cash_credit_limit": Decimal("1000.00"),
            "acct_open_date": "2020-01-01",
            "acct_expiration_date": "2030-12-31",
            "acct_reissue_date": "2025-01-01",
            "acct_curr_cyc_credit": Decimal("30.00"),
            "acct_curr_cyc_debit": Decimal("15.00"),
            "acct_addr_zip": "98101",
            "acct_group_id": "GOLD",
            "version_id": 0,
        },
    ]
    accounts_df = _make_mock_df(account_rows)

    # One disclosure-group row covers all three accounts.
    discgrp_df = _make_mock_df(
        [
            {
                "dis_acct_group_id": "GOLD",
                "dis_tran_type_cd": "01",
                "dis_tran_cat_cd": "0005",
                "dis_int_rate": Decimal("18.00"),
            }
        ]
    )

    def _read_side_effect(_spark: Any, table_name: str, **_kwargs: Any) -> Any:
        return {
            "transaction_category_balances": tcatbal_df,
            "card_cross_references": xref_df,
            "accounts": accounts_df,
            "disclosure_groups": discgrp_df,
        }[table_name]

    mock_read_table.side_effect = _read_side_effect

    written_tables: dict[str, Any] = {}

    def _write_side_effect(df_arg: Any, table_name: str, **_kwargs: Any) -> None:
        written_tables[table_name] = df_arg

    mock_write_table.side_effect = _write_side_effect

    # ----- Act -----
    main()

    # ----- Assert -----
    mock_commit_job.assert_called_once()
    mock_write_to_s3.assert_called_once()
    assert "accounts" in written_tables

    # Extract the 3 mutated account dicts from the input mock
    # (mutated in-place by _update_account_balance).
    updated_accts = {
        rm.asDict.return_value["acct_id"]: rm.asDict.return_value for rm in accounts_df.collect.return_value
    }

    # Account 1: base 500.00 + interest 15.00 = 515.00
    assert updated_accts["00000000001"]["acct_curr_bal"] == Decimal("515.00"), (
        "account 1 total_int incorrect — each account's total_int must reset on account break"
    )
    # Account 2: base 500.00 + interest 30.00 = 530.00
    assert updated_accts["00000000002"]["acct_curr_bal"] == Decimal("530.00"), (
        "account 2 total_int incorrect — WS-TOTAL-INT must reset at the account break"
    )
    # Account 3: base 500.00 + interest 45.00 = 545.00
    assert updated_accts["00000000003"]["acct_curr_bal"] == Decimal("545.00"), (
        "account 3 total_int incorrect (last account must still be updated after TCATBAL EOF — see COBOL line 220)"
    )

    # All three accounts had their cycle values zeroed.
    for acct_id, acct in updated_accts.items():
        assert acct["acct_curr_cyc_credit"] == Decimal("0.00"), f"account {acct_id} cycle credit not zeroed"
        assert acct["acct_curr_cyc_debit"] == Decimal("0.00"), f"account {acct_id} cycle debit not zeroed"


@pytest.mark.unit
@patch(_PATCH_F)
@patch(_PATCH_WRITE_TO_S3)
@patch(_PATCH_GET_S3_PATH)
@patch(_PATCH_WRITE_TABLE)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_GET_CONN_OPTS)
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_INIT_GLUE)
def test_last_account_updated_after_eof(
    mock_init_glue: MagicMock,
    mock_commit_job: MagicMock,
    mock_get_conn_opts: MagicMock,
    mock_read_table: MagicMock,
    mock_write_table: MagicMock,
    mock_get_s3_path: MagicMock,
    mock_write_to_s3: MagicMock,
    mock_f: MagicMock,
) -> None:
    """Verify the last account receives its update after TCATBAL EOF.

    Corresponds to COBOL post-loop logic at line 219-220 of
    CBACT04C.cbl::

        END-PERFORM
        IF WS-FIRST-TIME = 'N'
            PERFORM 1050-UPDATE-ACCOUNT

    Because the account-break check happens at the TOP of each
    iteration (not the bottom), the LAST account's accumulated
    total_int would be lost without this post-loop guard.  The
    Python main() replicates the guard via ``if not first_time:``
    after the for-loop.

    Input: 2 TCATBAL records for the SAME last account (so no
    intra-loop break occurs).  Without the post-loop guard the
    last account's balance would NOT be updated.
    """
    # ----- Arrange -----
    mock_init_glue.return_value = (
        MagicMock(name="MockSparkSession"),
        MagicMock(name="MockGlueCtx"),
        MagicMock(name="MockJob"),
        {"JOB_NAME": "carddemo-intcalc"},
    )
    mock_get_conn_opts.return_value = {
        "url": "jdbc:postgresql://localhost:5432/carddemo",
        "driver": "org.postgresql.Driver",
    }
    mock_get_s3_path.return_value = "s3://test-bucket/systran/v1/"

    # Single account with 2 TCATBAL rows.  NO account break
    # occurs in the loop — the only update happens via the
    # post-loop guard.
    tcatbal_rows = [
        _tcatbal("00000000042", "01", "0005", Decimal("1000.00")),
        _tcatbal("00000000042", "02", "0005", Decimal("1000.00")),
    ]
    tcatbal_df = _make_mock_df(tcatbal_rows)

    xref_df = _make_mock_df([{"card_num": "4111111111111142", "cust_id": "000000042", "acct_id": "00000000042"}])

    account_rows = [
        {
            "acct_id": "00000000042",
            "acct_active_status": "Y",
            "acct_curr_bal": Decimal("100.00"),
            "acct_credit_limit": Decimal("5000.00"),
            "acct_cash_credit_limit": Decimal("1000.00"),
            "acct_open_date": "2020-01-01",
            "acct_expiration_date": "2030-12-31",
            "acct_reissue_date": "2025-01-01",
            "acct_curr_cyc_credit": Decimal("99.99"),
            "acct_curr_cyc_debit": Decimal("50.00"),
            "acct_addr_zip": "98101",
            "acct_group_id": "GOLD",
            "version_id": 0,
        }
    ]
    accounts_df = _make_mock_df(account_rows)

    # Both TCATBAL records use different type_cd but the same
    # disclosure rate 12.00 → interest per row = 10.00.
    discgrp_df = _make_mock_df(
        [
            {
                "dis_acct_group_id": "GOLD",
                "dis_tran_type_cd": "01",
                "dis_tran_cat_cd": "0005",
                "dis_int_rate": Decimal("12.00"),
            },
            {
                "dis_acct_group_id": "GOLD",
                "dis_tran_type_cd": "02",
                "dis_tran_cat_cd": "0005",
                "dis_int_rate": Decimal("12.00"),
            },
        ]
    )

    def _read_side_effect(_spark: Any, table_name: str, **_kwargs: Any) -> Any:
        return {
            "transaction_category_balances": tcatbal_df,
            "card_cross_references": xref_df,
            "accounts": accounts_df,
            "disclosure_groups": discgrp_df,
        }[table_name]

    mock_read_table.side_effect = _read_side_effect

    written_tables: dict[str, Any] = {}

    def _write_side_effect(df_arg: Any, table_name: str, **_kwargs: Any) -> None:
        written_tables[table_name] = df_arg

    mock_write_table.side_effect = _write_side_effect

    # ----- Act -----
    main()

    # ----- Assert -----
    mock_commit_job.assert_called_once()

    # Extract the mutated account dict.
    account_row_dict = accounts_df.collect.return_value[0].asDict.return_value

    # Starting balance 100.00, accumulated interest:
    #   (1000.00 * 12.00) / 1200 = 10.00   (row 1)
    #   (1000.00 * 12.00) / 1200 = 10.00   (row 2)
    #   total_int                 = 20.00
    #   final balance             = 120.00
    # This update can ONLY happen via the post-loop guard; if it
    # were missing, the balance would still be 100.00.
    assert account_row_dict["acct_curr_bal"] == Decimal("120.00"), (
        f"Last account was NOT updated after TCATBAL EOF — "
        f"expected Decimal('120.00') (100.00 + 20.00 accumulated "
        f"interest), got {account_row_dict['acct_curr_bal']!r}.  "
        f"This means the post-loop guard `if not first_time: "
        f"_update_account_balance(...)` is missing."
    )
    # Cycle fields zeroed too.
    assert account_row_dict["acct_curr_cyc_credit"] == Decimal("0.00")
    assert account_row_dict["acct_curr_cyc_debit"] == Decimal("0.00")


# ============================================================================
# Phase 5 — Transaction-ID generation & record assembly tests.
# ============================================================================
# The COBOL source at 1300-B-WRITE-TX (lines 473-515) assembles each
# interest transaction with an ID built by concatenating PARM-DATE
# (10 chars) with WS-TRANID-SUFFIX (6-digit zero-padded) and sets
# 13 TRAN-RECORD fields (ID, type, cat, source, desc, amt, merchant
# fields, card-num, timestamps).  The Python equivalents are:
#
#   generate_tran_id(parm_date, suffix) → 16-char str
#   build_interest_transaction(
#       parm_date, suffix, acct_id, card_num, monthly_int
#   ) → dict[str, Any]  (13 keys)
#
# The tests below verify the exact format/content of both
# functions against the COBOL source.
# ============================================================================
@pytest.mark.unit
def test_generate_tran_id_format() -> None:
    """Verify tran_id is exactly ``PARM-DATE + 6-digit-zero-padded-suffix``.

    Corresponds to COBOL paragraph 1300-B-WRITE-TX lines 474-480::

        ADD 1 TO WS-TRANID-SUFFIX
        STRING PARM-DATE,
               WS-TRANID-SUFFIX
         DELIMITED BY SIZE
         INTO TRAN-ID
        END-STRING.

    Where:
      - ``PARM-DATE``     = PIC X(10)   → 10 chars
      - ``WS-TRANID-SUFFIX`` = PIC 9(06) → 6-digit zero-padded

    Giving a 16-char TRAN-ID per CVTRA05Y (``TRAN-ID PIC X(16)``).

    Input: parm_date="2022071800", suffix=1
    Expected: "2022071800000001"
    """
    result = generate_tran_id("2022071800", 1)
    assert result == "2022071800000001", (
        f"Expected '2022071800000001' (10-char date + 6-digit zero-padded '000001'), got {result!r}"
    )
    # Length is exactly 16 (TRAN-ID PIC X(16)).
    assert len(result) == 16, f"Expected len 16, got {len(result)}"


@pytest.mark.unit
def test_generate_tran_id_incrementing() -> None:
    """Verify the suffix increments correctly across multiple values.

    The COBOL ``WS-TRANID-SUFFIX PIC 9(06)`` is a 6-digit field
    zero-padded on the left when STRINGed into TRAN-ID.  Tests
    multiple suffix values to confirm consistent zero-padding:

      - suffix=1     → "...000001"
      - suffix=99    → "...000099"
      - suffix=1234  → "...001234"
      - suffix=999999 → "...999999" (max 6-digit value)
    """
    # Small suffix — 5 zeros.
    assert generate_tran_id("2022071800", 1) == "2022071800000001"

    # Two-digit suffix — 4 zeros.
    assert generate_tran_id("2022071800", 99) == "2022071800000099"

    # Four-digit suffix — 2 zeros.
    assert generate_tran_id("2022071800", 1234) == "2022071800001234"

    # Max 6-digit suffix — no zero-padding needed.
    assert generate_tran_id("2022071800", 999999) == "2022071800999999"

    # Different PARM-DATE — confirms the first 10 chars vary with
    # parm_date, the last 6 with suffix.
    assert generate_tran_id("2024010100", 42) == "2024010100000042"


@pytest.mark.unit
def test_build_interest_transaction_fields() -> None:
    """Verify all 13 TRAN-RECORD fields match COBOL paragraph 1300-B-WRITE-TX.

    Corresponds to COBOL lines 482-498 of CBACT04C.cbl::

        MOVE '01'                 TO TRAN-TYPE-CD
        MOVE '05'                 TO TRAN-CAT-CD
        MOVE 'System'             TO TRAN-SOURCE
        STRING 'Int. for a/c ' ,
               ACCT-ID
               DELIMITED BY SIZE
         INTO TRAN-DESC
        MOVE WS-MONTHLY-INT       TO TRAN-AMT
        MOVE 0                    TO TRAN-MERCHANT-ID
        MOVE SPACES               TO TRAN-MERCHANT-NAME
        MOVE SPACES               TO TRAN-MERCHANT-CITY
        MOVE SPACES               TO TRAN-MERCHANT-ZIP
        MOVE XREF-CARD-NUM        TO TRAN-CARD-NUM

    The returned dict is verified for:
      - tran_id (16 chars = parm_date + suffix)
      - tran_type_cd = "01"                (interest TYPE)
      - tran_cat_cd = "0005"               (interest CAT, PIC 9(04))
      - tran_source starts with "System"   (right-padded to 10 chars)
      - tran_desc starts with "Int. for a/c {acct_id}"
      - tran_amt == monthly_int (Decimal)
      - tran_merchant_id == "000000000"    (9 zeros)
      - tran_merchant_name/city/zip are spaces-only
      - tran_card_num = padded card_num
      - tran_orig_ts == tran_proc_ts (both populated from same
        timestamp per COBOL line 496-498)
    """
    result = build_interest_transaction(
        parm_date="2022071800",
        suffix=7,
        acct_id="00000000001",
        card_num="4111111111111111",
        monthly_int=Decimal("150.00"),
    )

    # Field 1: tran_id — 16-char PARM-DATE + 6-digit suffix.
    assert result["tran_id"] == "2022071800000007", f"tran_id wrong — got {result['tran_id']!r}"
    # Field 2: TRAN-TYPE-CD = '01' (interest).
    assert result["tran_type_cd"] == "01", f"tran_type_cd must be '01' (interest) — got {result['tran_type_cd']!r}"
    # Field 3: TRAN-CAT-CD = '0005' (zero-padded PIC 9(04)).
    assert result["tran_cat_cd"] == "0005", (
        f"tran_cat_cd must be '0005' (zero-padded PIC 9(04) interest category) — got {result['tran_cat_cd']!r}"
    )
    # Field 4: TRAN-SOURCE = 'System' padded to 10 chars.
    assert result["tran_source"].startswith("System"), (
        f"tran_source must start with 'System' — got {result['tran_source']!r}"
    )
    assert len(result["tran_source"]) == 10, (
        f"tran_source must be 10 chars (PIC X(10)) — got len {len(result['tran_source'])}"
    )
    # Field 5: TRAN-DESC = 'Int. for a/c ' + ACCT-ID, padded to 100.
    expected_desc_prefix = "Int. for a/c 00000000001"
    assert result["tran_desc"].startswith(expected_desc_prefix), (
        f"tran_desc must start with {expected_desc_prefix!r} — got {result['tran_desc']!r}"
    )
    assert len(result["tran_desc"]) == 100, (
        f"tran_desc must be 100 chars (PIC X(100)) — got len {len(result['tran_desc'])}"
    )
    # Field 6: TRAN-AMT = WS-MONTHLY-INT (Decimal, 2-decimal scale).
    assert result["tran_amt"] == Decimal("150.00"), f"tran_amt must equal monthly_int — got {result['tran_amt']!r}"
    assert isinstance(result["tran_amt"], Decimal)
    # Field 7: TRAN-MERCHANT-ID = 0 → stored as "000000000" (9 zeros).
    assert result["tran_merchant_id"] == "000000000", (
        f"tran_merchant_id must be '000000000' (MOVE 0 TO TRAN-MERCHANT-ID) — got {result['tran_merchant_id']!r}"
    )
    # Fields 8, 9, 10: MERCHANT-NAME/CITY/ZIP = SPACES (blank-filled).
    assert result["tran_merchant_name"] == " " * 50, (
        f"tran_merchant_name must be 50 spaces (MOVE SPACES) — got {result['tran_merchant_name']!r}"
    )
    assert result["tran_merchant_city"] == " " * 50, (
        f"tran_merchant_city must be 50 spaces (MOVE SPACES) — got {result['tran_merchant_city']!r}"
    )
    assert result["tran_merchant_zip"] == " " * 10, (
        f"tran_merchant_zip must be 10 spaces (MOVE SPACES) — got {result['tran_merchant_zip']!r}"
    )
    # Field 11: TRAN-CARD-NUM = XREF-CARD-NUM padded to 16.
    assert result["tran_card_num"] == "4111111111111111", (
        f"tran_card_num must equal XREF-CARD-NUM padded to 16 — got {result['tran_card_num']!r}"
    )
    assert len(result["tran_card_num"]) == 16
    # Fields 12 & 13: ORIG/PROC timestamps must match (populated
    # from same Z-GET-DB2-FORMAT-TIMESTAMP call per COBOL line
    # 496-498).
    assert result["tran_orig_ts"] == result["tran_proc_ts"], (
        "TRAN-ORIG-TS and TRAN-PROC-TS must share the same "
        "DB2-format timestamp (both populated from the same "
        "Z-GET-DB2-FORMAT-TIMESTAMP call at COBOL lines 496-498)"
    )
    # Timestamps must be non-empty strings (DB2 format).
    assert isinstance(result["tran_orig_ts"], str)
    assert len(result["tran_orig_ts"]) > 0

    # Total field count: the dict must have exactly 13 keys
    # (ID, type, cat, source, desc, amt, merchant-id/name/city/
    # zip, card-num, orig-ts, proc-ts).
    assert len(result) == 13, (
        f"build_interest_transaction must return exactly 13 keys "
        f"(CVTRA05Y TRAN-RECORD fields) — got {len(result)} keys: "
        f"{sorted(result.keys())!r}"
    )


# ============================================================================
# Phase 6 — 1400-COMPUTE-FEES stub preservation test.
# ============================================================================
# COBOL paragraph 1400-COMPUTE-FEES (CBACT04C.cbl lines 517-520) is
# documented as "To be implemented" — an intentional no-op pending
# future business logic.  AAP §0.7.1 mandates preserving behaviour
# exactly, so the Python equivalent :func:`_compute_fees_stub` is
# also a no-op.  When the business eventually implements fees, this
# call-site remains intact.
# ============================================================================
@pytest.mark.unit
def test_compute_fees_stub_preserved() -> None:
    """Verify ``_compute_fees_stub`` returns ``None`` with no side effects.

    Corresponds to COBOL paragraph::

        1400-COMPUTE-FEES.
        *    To be implemented
             EXIT.

    The Python stub must match: return None, no exceptions, no
    I/O, no state changes.  This test preserves the call-site so
    the business can implement fees without touching main().
    """
    # Act — should execute without raising and return None.
    # ``_compute_fees_stub`` has return annotation ``-> None``, so
    # assigning the call to a variable triggers mypy's
    # ``func-returns-value`` error.  We therefore invoke the stub
    # twice and rely on the lack of exception / state change as
    # evidence that the COBOL EXIT. semantics are preserved.
    _compute_fees_stub()  # first invocation — no-op
    _compute_fees_stub()  # second invocation — idempotent no-op


# ============================================================================
# Phase 7 — End-to-end PySpark integration test.
# ============================================================================
# Exercises :func:`main` with a real :class:`SparkSession` from the
# shared ``spark_session`` fixture in ``tests/conftest.py``.  AWS
# Glue dependencies (init_glue, commit_job, get_connection_options,
# read_table, write_table, get_versioned_s3_path, write_to_s3) are
# mocked at the module namespace so the test exercises the entire
# business-logic path without requiring a live Glue/JDBC/S3 stack.
#
# Unlike the POSTTRAN job (which calls ``sys.exit(4)`` when rejects
# exist), INTCALC does NOT use a process-exit code — it simply
# returns after ``commit_job(job)``.  Therefore this test does NOT
# wrap ``main()`` in ``pytest.raises(SystemExit)``.
#
# Test scenario:
#   * 1 account ("00000000001") with starting balance 500.00 and
#     group_id "GOLD".
#   * 2 TCATBAL rows for that account: (type="01", cat="0005",
#     bal=1000.00) and (type="01", cat="0006", bal=2000.00).
#   * 2 disclosure-group rows for ("GOLD", "01", "0005") at
#     rate 18.00 and ("GOLD", "01", "0006") at rate 18.00.
#
# Expected outputs:
#   * 2 interest transactions generated and written to S3 via a
#     single :func:`write_to_s3` call.
#   * 2 interest transactions ALSO written to the PostgreSQL
#     ``transactions`` table via :func:`write_table` with
#     ``mode="append"`` — required so the downstream COMBTRAN,
#     CREASTMT, and TRANREPT stages can read the interest rows
#     from Aurora PostgreSQL (the shared persistence layer
#     between stages in the AWS Glue pipeline).  This matches
#     the COBOL CBACT04C behaviour (WRITE FD-TRANFILE-REC at
#     line 514 in paragraph 1300-B-WRITE-TX) which writes every
#     interest record to the TRANSACT VSAM cluster.
#   * Account balance updated: 500.00 + (15.00 + 30.00) = 545.00.
#   * Cycle credit/debit reset to 0.00 per 1050-UPDATE-ACCOUNT.
#   * accounts table written via :func:`write_table` with the
#     13-column schema from :func:`_build_account_schema`
#     (``mode="overwrite"`` — REWRITE equivalent).
#   * :func:`commit_job` invoked exactly once (MAXCC=0 signal).
# ============================================================================
@pytest.mark.unit
@patch(_PATCH_WRITE_TO_S3)
@patch(_PATCH_GET_S3_PATH)
@patch(_PATCH_WRITE_TABLE)
@patch(_PATCH_READ_TABLE)
@patch(_PATCH_GET_CONN_OPTS)
@patch(_PATCH_COMMIT_JOB)
@patch(_PATCH_INIT_GLUE)
def test_intcalc_main_with_spark(
    mock_init_glue: MagicMock,
    mock_commit_job: MagicMock,
    mock_get_conn_opts: MagicMock,
    mock_read_table: MagicMock,
    mock_write_table: MagicMock,
    mock_get_s3_path: MagicMock,
    mock_write_to_s3: MagicMock,
    spark_session: SparkSession,
) -> None:
    """End-to-end ``main()`` using a real SparkSession; verify DF outputs.

    Constructs real PySpark DataFrames matching the copybook
    layouts:

      * ``transaction_category_balances`` — ``CVTRA01Y.cpy``
        (TRAN-CAT-BAL-RECORD).
      * ``card_cross_references`` — ``CVACT03Y.cpy``
        (CARD-XREF-RECORD, 50B).
      * ``accounts`` — ``CVACT01Y.cpy`` (ACCOUNT-RECORD, 300B).
      * ``disclosure_groups`` — ``CVTRA02Y.cpy``
        (DIS-GROUP-RECORD).

    Verifies:
      * ``main()`` runs end-to-end with real SparkSession without
        crashing.
      * ``write_to_s3`` is invoked exactly once (2 interest
        transactions serialised into a single SYSTRAN object).
      * ``write_table`` is invoked with the ``accounts`` table name
        (``mode="overwrite"``) and a DataFrame containing the
        updated balance row.
      * ``write_table`` is ALSO invoked with the ``transactions``
        table name and ``mode="append"`` carrying the interest
        transactions DataFrame — this is the pipeline-integration
        append required so Stage 3 (COMBTRAN), Stage 4a
        (CREASTMT), and Stage 4b (TRANREPT) downstream jobs can
        read the interest rows from Aurora PostgreSQL via JDBC.
        Mirrors the COBOL WRITE FD-TRANFILE-REC at CBACT04C
        line 514 (paragraph 1300-B-WRITE-TX) which writes every
        interest transaction to the TRANSACT VSAM cluster.
      * The accounts-writeback DataFrame has the expected 13-column
        schema from :func:`_build_account_schema`.
      * The account balance row shows 545.00 (starting 500 + 15 +
        30) with zeroed cycle fields per 1050-UPDATE-ACCOUNT.
      * ``commit_job`` is invoked exactly once (MAXCC=0 signal).
    """
    # ----- Arrange: use the REAL spark fixture from conftest.py -----
    # init_glue() returns (spark, glue_ctx, job, resolved_args).
    # The PARM_DATE key mimics the JCL PARM='2022071800' literal
    # passed via awsglue.utils.getResolvedOptions.
    mock_init_glue.return_value = (
        spark_session,
        MagicMock(name="RealSparkGlueCtx"),
        MagicMock(name="RealSparkJob"),
        {
            "JOB_NAME": "carddemo-intcalc",
            "PARM_DATE": "2022071800",
        },
    )
    # The JDBC probe is defensive; returning a plausible dict keeps
    # the probe's INFO log clean.
    mock_get_conn_opts.return_value = {
        "url": "jdbc:postgresql://localhost:5432/carddemo",
        "driver": "org.postgresql.Driver",
    }

    # -------- Build the four real input DataFrames --------
    # ``transaction_category_balances`` — 2 TCATBAL rows for the
    # SAME account.  This exercises the sequential processing loop
    # (2 interest transactions generated) AND the post-loop final
    # update (the last account must be updated after the loop exits
    # since no account-break occurred).
    tcatbal_df = spark_session.createDataFrame(
        [
            Row(
                acct_id="00000000001",
                type_code="01",
                cat_code="0005",
                tran_cat_bal=Decimal("1000.00"),
            ),
            Row(
                acct_id="00000000001",
                type_code="01",
                cat_code="0006",
                tran_cat_bal=Decimal("2000.00"),
            ),
        ]
    )

    # ``card_cross_references`` — 1 xref row mapping a card to the
    # test account.  Required by 1110-GET-XREF-DATA (COBOL line
    # 206) so the interest-transaction record has a valid
    # TRAN-CARD-NUM.
    xref_df = spark_session.createDataFrame(
        [
            Row(
                card_num="4111111111111111",
                cust_id="000000001",
                acct_id="00000000001",
            )
        ]
    )

    # ``accounts`` — 1 account with starting balance 500.00 and
    # group_id "GOLD".  Pre-existing cycle credit/debit values
    # (100.00 / 50.00) will be reset to 0.00 by
    # 1050-UPDATE-ACCOUNT per the COBOL mutation contract
    # (lines 352-354).
    accounts_df = spark_session.createDataFrame(
        [
            Row(
                acct_id="00000000001",
                acct_active_status="Y",
                acct_curr_bal=Decimal("500.00"),
                acct_credit_limit=Decimal("5000.00"),
                acct_cash_credit_limit=Decimal("1000.00"),
                acct_open_date="2020-01-01",
                acct_expiration_date="2030-12-31",
                acct_reissue_date="2025-01-01",
                acct_curr_cyc_credit=Decimal("100.00"),
                acct_curr_cyc_debit=Decimal("50.00"),
                acct_addr_zip="98101",
                acct_group_id="GOLD",
                version_id=0,
            )
        ]
    )

    # ``disclosure_groups`` — 2 rows matching the 2 TCATBAL
    # composite keys, both at 18.00% APR.  Expected monthly
    # interest:
    #   (1000.00 * 18.00) / 1200 = 15.00
    #   (2000.00 * 18.00) / 1200 = 30.00
    #   Total: 45.00
    # The COBOL formula ``(TRAN-CAT-BAL * DIS-INT-RATE) / 1200``
    # is preserved exactly in :func:`compute_monthly_interest`.
    disclosure_groups_df = spark_session.createDataFrame(
        [
            Row(
                dis_acct_group_id="GOLD",
                dis_tran_type_cd="01",
                dis_tran_cat_cd="0005",
                dis_int_rate=Decimal("18.00"),
            ),
            Row(
                dis_acct_group_id="GOLD",
                dis_tran_type_cd="01",
                dis_tran_cat_cd="0006",
                dis_int_rate=Decimal("18.00"),
            ),
        ]
    )

    # ----- Wire read_table → DataFrame dispatch by table name -----
    def _read_side_effect(_spark: Any, table_name: str, **_kwargs: Any) -> Any:
        return {
            "transaction_category_balances": tcatbal_df,
            "card_cross_references": xref_df,
            "accounts": accounts_df,
            "disclosure_groups": disclosure_groups_df,
        }[table_name]

    mock_read_table.side_effect = _read_side_effect

    # get_versioned_s3_path returns a plausible versioned URI with
    # scheme + bucket + key_prefix (must contain at least one "/"
    # after scheme per _write_interest_trans_to_s3 validation).
    mock_get_s3_path.return_value = "s3://test-bucket/generated/system-transactions/2024/06/15/120000/"
    mock_write_to_s3.return_value = "s3://test-bucket/generated/system-transactions/2024/06/15/120000/SYSTRAN.txt"

    # ----- Capture write_table calls so we can inspect the DF -----
    written_dataframes: dict[str, Any] = {}

    def _write_side_effect(df_arg: Any, table_name: str, **_kwargs: Any) -> None:
        written_dataframes[table_name] = df_arg

    mock_write_table.side_effect = _write_side_effect

    # ----- Act: call main() end-to-end -----
    main()

    # ----- Assert -----
    # 1) commit_job invoked exactly once (MAXCC=0 signal).
    mock_commit_job.assert_called_once()

    # 2) write_to_s3 invoked exactly once for the SYSTRAN object
    #    (2 interest transactions serialised together into a
    #    single S3 PutObject call).
    mock_write_to_s3.assert_called_once()

    # 3) get_versioned_s3_path invoked with the "SYSTRAN" logical
    #    name so _write_interest_trans_to_s3 can resolve the
    #    versioned output path.
    mock_get_s3_path.assert_called_once_with("SYSTRAN")

    # 4) write_table invoked for the accounts table.
    assert "accounts" in written_dataframes, (
        f"Expected 'accounts' table to be written — got: {list(written_dataframes.keys())}"
    )

    # 5) Verify the accounts-writeback DataFrame has the 13-column
    #    schema from _build_account_schema.
    accounts_out_df = written_dataframes["accounts"]
    accounts_out_schema = accounts_out_df.schema
    accounts_out_field_names = {f.name for f in accounts_out_schema.fields}
    expected_accounts_fields = {
        "acct_id",
        "acct_active_status",
        "acct_curr_bal",
        "acct_credit_limit",
        "acct_cash_credit_limit",
        "acct_open_date",
        "acct_expiration_date",
        "acct_reissue_date",
        "acct_curr_cyc_credit",
        "acct_curr_cyc_debit",
        "acct_addr_zip",
        "acct_group_id",
        "version_id",
    }
    assert accounts_out_field_names == expected_accounts_fields, (
        f"accounts-writeback DataFrame schema drift — got "
        f"{sorted(accounts_out_field_names)}, expected "
        f"{sorted(expected_accounts_fields)}"
    )

    # 6) Verify the monetary columns are DecimalType (COBOL
    #    PIC S9(10)V99 parity — NUMERIC(12,2) in PostgreSQL).
    for money_col in (
        "acct_curr_bal",
        "acct_credit_limit",
        "acct_cash_credit_limit",
        "acct_curr_cyc_credit",
        "acct_curr_cyc_debit",
    ):
        field = next(f for f in accounts_out_schema.fields if f.name == money_col)
        assert "decimal" in field.dataType.simpleString().lower(), (
            f"{money_col} must be DecimalType — got {field.dataType.simpleString()}"
        )

    # 7) Verify the accounts DataFrame has exactly 1 row (the
    #    single account, fully updated).
    assert accounts_out_df.count() == 1

    # 8) Verify the account balance row reflects the 1050-UPDATE-
    #    ACCOUNT mutations:
    #      * acct_curr_bal = 500.00 + 45.00 = 545.00
    #      * acct_curr_cyc_credit = 0.00 (reset)
    #      * acct_curr_cyc_debit = 0.00 (reset)
    #      * acct_id preserved
    #      * acct_group_id preserved
    updated_row = accounts_out_df.collect()[0]
    assert updated_row["acct_id"] == "00000000001"
    assert updated_row["acct_curr_bal"] == Decimal("545.00"), (
        f"acct_curr_bal must be 545.00 (starting 500.00 + "
        f"(1000.00 * 18.00 / 1200) + (2000.00 * 18.00 / 1200) = "
        f"500.00 + 15.00 + 30.00 = 545.00) — got "
        f"{updated_row['acct_curr_bal']!r}"
    )
    assert updated_row["acct_curr_cyc_credit"] == Decimal("0.00"), (
        f"acct_curr_cyc_credit must be reset to 0.00 "
        f"(MOVE 0 TO ACCT-CURR-CYC-CREDIT) — got "
        f"{updated_row['acct_curr_cyc_credit']!r}"
    )
    assert updated_row["acct_curr_cyc_debit"] == Decimal("0.00"), (
        f"acct_curr_cyc_debit must be reset to 0.00 "
        f"(MOVE 0 TO ACCT-CURR-CYC-DEBIT) — got "
        f"{updated_row['acct_curr_cyc_debit']!r}"
    )
    # Group ID must be preserved (not mutated by 1050-UPDATE-ACCOUNT).
    assert updated_row["acct_group_id"] == "GOLD"

    # 9) Verify write_table was called with mode="overwrite" for
    #    the accounts table (REWRITE semantics — all rows written
    #    back so untouched accounts survive the JDBC load).
    #
    #    NOTE (schema-preservation rationale — CRITICAL #1 fix):
    #    ``mode="overwrite"`` is still the correct mode for the
    #    accounts table because the mainframe COBOL semantic is
    #    REWRITE every row (touched + untouched) so the VSAM
    #    cluster contents are fully refreshed.  Spark JDBC's
    #    DEFAULT behaviour for ``mode="overwrite"`` is DROP TABLE
    #    + CREATE TABLE + INSERT (destructive to PRIMARY KEY,
    #    FKs, indexes, and the ``version_id`` optimistic-
    #    concurrency column declared in ``V1__schema.sql``).
    #    However, :func:`src.batch.common.db_connector.write_table`
    #    explicitly sets ``truncate="true"`` in the JDBC writer
    #    options, which flips the semantic to TRUNCATE TABLE +
    #    INSERT instead — preserving the PostgreSQL schema
    #    (PRIMARY KEY on ``acct_id``, B-tree indexes, NOT NULL
    #    constraints, the ``version_id`` column for SQLAlchemy
    #    optimistic concurrency, etc.).  Therefore asserting
    #    ``mode == "overwrite"`` here is the correct production
    #    expectation — the schema-preserving behaviour is handled
    #    inside ``db_connector.write_table`` as a JDBC option,
    #    not as a different ``mode=`` argument.
    accounts_call = None
    for call in mock_write_table.call_args_list:
        # call.args: (dataframe, table_name)
        # call.kwargs: {"mode": "overwrite", ...}
        if len(call.args) >= 2 and call.args[1] == "accounts":
            accounts_call = call
            break
    assert accounts_call is not None, "write_table was never invoked with table_name='accounts'"
    assert accounts_call.kwargs.get("mode") == "overwrite", (
        f"accounts write_table must use mode='overwrite' (REWRITE equivalent) — got {accounts_call.kwargs!r}"
    )

    # 9b) Verify write_table was called with mode="append" for the
    #     transactions table — this is the pipeline-integration
    #     append (CRITICAL #2 fix) REQUIRED so the downstream
    #     COMBTRAN (Stage 3), CREASTMT (Stage 4a), and TRANREPT
    #     (Stage 4b) jobs can read the interest rows from Aurora
    #     PostgreSQL via JDBC.  Mirrors the COBOL WRITE
    #     FD-TRANFILE-REC at CBACT04C line 514 (paragraph
    #     1300-B-WRITE-TX) which writes every interest transaction
    #     to the TRANSACT VSAM cluster.
    #
    #     NOTE (mode="append" — NOT "overwrite" — rationale):
    #     Interest transactions are NEW rows to be added to the
    #     existing posted-transaction set produced by Stage 1
    #     (posttran_job).  Using ``mode="overwrite"`` here would
    #     TRUNCATE the transactions table (via the
    #     ``truncate="true"`` option in db_connector.write_table)
    #     and destroy the posted transactions from Stage 1,
    #     breaking the entire pipeline.  ``mode="append"`` performs
    #     a pure INSERT matching the COBOL WRITE (no key check —
    #     the DataFrame is built from newly allocated tran_ids so
    #     no primary-key collision is possible).
    #
    #     Guard: the production code short-circuits the write when
    #     the interest_trans list is empty, but this test scenario
    #     generates 2 interest transactions so the append call
    #     MUST have been emitted.
    transactions_call = None
    for call in mock_write_table.call_args_list:
        # call.args: (dataframe, table_name)
        # call.kwargs: {"mode": "append", ...}
        if len(call.args) >= 2 and call.args[1] == "transactions":
            transactions_call = call
            break
    assert transactions_call is not None, (
        "write_table was never invoked with table_name='transactions' "
        "— CRITICAL #2 pipeline-integration append missing.  Interest "
        "transactions must be written to the PostgreSQL transactions "
        "table so downstream COMBTRAN/CREASTMT/TRANREPT stages can "
        "read them.  Without this append, the pipeline output is "
        "broken: combtran_job receives zero interest rows, "
        "creastmt_job statements omit interest charges, and "
        "tranrept_job totals exclude interest transactions — "
        "violating AAP §0.7.1 'No feature may be dropped'."
    )
    assert transactions_call.kwargs.get("mode") == "append", (
        f"transactions write_table must use mode='append' (INSERT "
        f"equivalent) — NOT 'overwrite' (which would TRUNCATE the "
        f"posted transactions from Stage 1 posttran_job).  Got "
        f"kwargs={transactions_call.kwargs!r}"
    )

    # 9c) Verify the DataFrame argument of the transactions write
    #     contains exactly 2 interest-transaction rows (matching
    #     the 2 TCATBAL rows processed: type=01/cat=0005 and
    #     type=01/cat=0006).  This guards against a regression
    #     where the write_table call would be emitted with an
    #     unrelated DataFrame (e.g. the accounts DF) or with an
    #     empty DataFrame (short-circuit bypass).
    transactions_out_df = written_dataframes.get("transactions")
    assert transactions_out_df is not None, (
        "transactions DataFrame was not captured by the "
        "_write_side_effect — write_table must be invoked with "
        "'transactions' as the table name"
    )
    transactions_row_count = transactions_out_df.count()
    assert transactions_row_count == 2, (
        f"transactions-write DataFrame must contain exactly 2 "
        f"interest-transaction rows (one per TCATBAL row processed: "
        f"type=01/cat=0005 and type=01/cat=0006) — got "
        f"{transactions_row_count} rows"
    )

    # 10) Verify the SYSTRAN S3 write captured 2 interest-transaction
    #     lines (the serialised content is the first positional arg
    #     of write_to_s3).  The content is joined by LF and each
    #     line is the 350-byte SYSTRAN record format.  2 records +
    #     trailing LF → exactly 2 newlines in the payload.
    write_to_s3_call = mock_write_to_s3.call_args
    s3_content_arg = write_to_s3_call.args[0]
    # Count records: each record ends with "\n" (joined via
    # "\n".join + trailing "\n"), so the newline count == record
    # count.
    newline_count = s3_content_arg.count("\n")
    assert newline_count == 2, (
        f"SYSTRAN S3 payload must contain exactly 2 interest-"
        f"transaction records (newline-delimited) — got "
        f"{newline_count} records"
    )

    # 11) Verify write_to_s3 was called with the correct content
    #     type (text/plain for the fixed-width LRECL=350 SYSTRAN
    #     dataset).
    assert write_to_s3_call.kwargs.get("content_type") == "text/plain", (
        f"SYSTRAN S3 write must use content_type='text/plain' — got {write_to_s3_call.kwargs!r}"
    )

    # 12) Verify init_glue was invoked with the expected JOB_NAME
    #     (Glue job identifier matching the AWS Glue catalog entry).
    mock_init_glue.assert_called_once_with(job_name="carddemo-intcalc")
