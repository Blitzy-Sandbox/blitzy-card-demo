# ============================================================================
# Source: app/cbl/COACTVWC.cbl  (Account View   — CICS transaction CAVW,   941 lines)
#       + app/cbl/COACTUPC.cbl  (Account Update — CICS transaction CAUP, 4,236 lines)
#       + app/cpy/CVACT01Y.cpy  (ACCOUNT-RECORD  300-byte VSAM record layout — PK acct_id)
#       + app/cpy/CVACT03Y.cpy  (CARD-XREF-RECORD 50-byte VSAM record layout — PK card_num)
#       + app/cpy/CVCUS01Y.cpy  (CUSTOMER-RECORD 500-byte VSAM record layout — PK cust_id)
#       + app/cpy-bms/COACTVW.CPY (Account View   BMS symbolic map — output contract)
#       + app/cpy-bms/COACTUP.CPY (Account Update BMS symbolic map — input/output contract)
# ============================================================================
# Mainframe-to-Cloud migration:
#
#   CICS 3-entity keyed read chain for the Account View (F-004):
#     ``EXEC CICS READ DATASET('CXACAIX') RIDFLD(acct_id) INTO(CARD-XREF-RECORD)``
#     ``EXEC CICS READ DATASET('ACCTDAT') RIDFLD(acct_id) INTO(ACCOUNT-RECORD)``
#     ``EXEC CICS READ DATASET('CUSTDAT') RIDFLD(cust_id) INTO(CUSTOMER-RECORD)``
#
#   CICS locked READ + dual REWRITE with SYNCPOINT ROLLBACK for the Account
#   Update (F-005):
#     ``EXEC CICS READ    DATASET('ACCTDAT') UPDATE INTO(ACCT-UPDATE-RECORD)``
#     ``EXEC CICS READ    DATASET('CUSTDAT') UPDATE INTO(CUST-UPDATE-RECORD)``
#     ``EXEC CICS REWRITE DATASET('ACCTDAT') FROM(ACCT-UPDATE-RECORD)``
#     ``EXEC CICS REWRITE DATASET('CUSTDAT') FROM(CUST-UPDATE-RECORD)``
#     ``EXEC CICS SYNCPOINT ROLLBACK``  (when the second REWRITE fails — the
#        CRITICAL recovery path on COACTUPC.cbl line ~4100 that makes the
#        two-table mutation atomic across mainframe VSAM datasets)
#
# becomes
#
#   SQLAlchemy 2.x async keyed lookups against the Aurora PostgreSQL
#   ``card_cross_references`` / ``accounts`` / ``customers`` tables for
#   the View, followed by attribute-level mutation of both the
#   :class:`~src.shared.models.account.Account` and
#   :class:`~src.shared.models.customer.Customer` instances managed by
#   the SAME AsyncSession, then a single ``session.flush()`` +
#   ``session.commit()`` for the Update. Because both UPDATEs occur
#   within a single PostgreSQL transaction, a failure of either one
#   triggers an automatic rollback of BOTH — directly replicating the
#   COBOL SYNCPOINT ROLLBACK semantics on Account ↔ Customer dual-write.
#
#   Optimistic concurrency is enforced by the Account model's
#   ``version_id`` column (wired via ``__mapper_args__ =
#   {"version_id_col": version_id}``). On flush, SQLAlchemy appends
#   ``AND version_id = :old_version`` to the UPDATE's WHERE clause and
#   raises :class:`sqlalchemy.orm.exc.StaleDataError` when the row has
#   been modified by a concurrent client — replacing the CICS READ
#   UPDATE enqueue-based locking.
#
# The target deployment is AWS ECS Fargate behind an Application Load
# Balancer, connecting to Aurora PostgreSQL via asyncpg; the database
# credentials come from AWS Secrets Manager in staging/production
# (injected via ECS task-definition secrets) and from the ``.env`` file
# in local development (docker-compose).
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
"""Account view and update service.

Converted from ``app/cbl/COACTVWC.cbl`` (941 lines — 3-entity keyed
read chain for the Account View screen) and ``app/cbl/COACTUPC.cbl``
(4,236 lines — dual-write Account + Customer update with SYNCPOINT
ROLLBACK on the secondary REWRITE failure). This service implements
features F-004 (Account View) and F-005 (Account Update) from the
AAP §0.2.3 Online CICS Program Classification catalog.

COBOL -> Python flow
--------------------
**F-004 Account View (:meth:`AccountService.get_account_view`):**

======================================================  ==================================
COBOL paragraph (COACTVWC.cbl)                          Python equivalent
======================================================  ==================================
``1000-MAIN-PARA``   (driver)                           :meth:`get_account_view`
``9200-GETCARDXREF-BYACCT``                             1st SELECT on CardCrossReference
``9300-GETACCTDATA-BYACCT``                             2nd SELECT on Account
``9400-GETCUSTDATA-BYCUST``                             3rd SELECT on Customer
``1100-PROCESS-INPUTS``  (assemble display)             Assemble :class:`AccountViewResponse`
``1200-SEND-MAP``        (emit BMS screen)              ``return`` the response
======================================================  ==================================

**F-005 Account Update (:meth:`AccountService.update_account`):**

======================================================  ======================================
COBOL paragraph (COACTUPC.cbl)                          Python equivalent
======================================================  ======================================
``1000-MAIN-PARA``   (driver)                           :meth:`update_account`
``1200-EDIT-MAP-INPUTS``  (field validation)            Per-field validation helpers
``1205-COMPARE-OLD-NEW``  (change detection)            :func:`_detect_changes`
``1210-EDIT-ACCOUNT``  (11-digit numeric acct_id)       :func:`_validate_account_id`
``1215-EDIT-MANDATORY``  (required-field check)         Pydantic + explicit guards
``1220-EDIT-YESNO``  (Y/N active_status)                :func:`_validate_yes_no`
``1250-EDIT-SIGNED-9V2``  (signed-numeric monetary)     :func:`safe_decimal` (decimal_utils)
``1260-EDIT-US-PHONE-NUM``  (phone validation)          :func:`_validate_us_phone`
``1265-EDIT-US-SSN``  (SSN 3-2-4 split)                 :func:`_validate_us_ssn`
``1270-EDIT-US-STATE-CD``  (state code)                 :func:`_validate_state_code`
``1275-EDIT-FICO-SCORE``  (FICO 300-850)                :func:`_validate_fico_score`
``1280-EDIT-DATE-OF-BIRTH``  (DOB + no-future)          :func:`validate_date_of_birth`
``1285-EDIT-DATE-CCYYMMDD``  (generic date)             :func:`validate_date_ccyymmdd`
``9200-WRITE-PROCESSING``  (REWRITE ACCTDAT)            attribute mutation on Account
``9300-WRITE-PROCESSING``  (REWRITE CUSTDAT)            attribute mutation on Customer
``SYNCPOINT ROLLBACK``  (L~4100, customer write fail)   ``session.rollback()`` on exception
``CONFIRM-UPDATE-SUCCESS``  (L~491 success message)     Assemble :class:`AccountUpdateResponse`
======================================================  ======================================

Transaction boundaries
----------------------
The ``get_account_view`` method is read-only and does NOT commit or
roll back; the caller (typically the FastAPI dependency-injected
session from ``src/api/database.py``) owns transaction management for
reads. The ``update_account`` method, by contrast, owns its
transaction from end-to-end: it reads the current Account and
Customer rows, applies attribute-level mutations to both ORM
instances, and issues a single ``flush() + commit()`` covering the
dual-table UPDATE. On any exception during flush/commit the service
issues ``session.rollback()`` — this is the precise Python-layer
equivalent of the COBOL ``EXEC CICS SYNCPOINT ROLLBACK`` that
COACTUPC.cbl invokes on line ~4100 when the CUSTDAT REWRITE fails
after the ACCTDAT REWRITE has succeeded. The single-transaction
scope makes the Account + Customer dual-write atomic: either both
rows land in the new state or neither does, with no partially-updated
half-write visible to subsequent readers.

Optimistic concurrency
----------------------
The :class:`~src.shared.models.account.Account` model declares
``version_id`` as its ``__mapper_args__["version_id_col"]`` (see
``src/shared/models/account.py`` lines 458-482). On every UPDATE,
SQLAlchemy appends ``AND version_id = :old_version`` to the WHERE
clause and increments the column. When another client has modified
the row between our read and our flush, the UPDATE affects zero rows
and SQLAlchemy raises :class:`sqlalchemy.orm.exc.StaleDataError` —
we catch this, roll back, and return a response with the COBOL
``DATA-WAS-CHANGED-BEFORE-UPDATE`` error text ("Record changed by
some one else. Please review").

Note that the :class:`~src.shared.models.customer.Customer` model
does NOT carry its own ``version_id`` column. This is deliberate:
per the CardDemo domain model, a Customer record is modified only
via the Account Update flow (F-005) — never directly — so the
Account's version stamp is sufficient to guard against concurrent
modification of the customer fields touched by the same Account
Update transaction.

Error message fidelity
----------------------
Per AAP §0.7.1 ("Preserve all existing functionality exactly as-is"),
every user-facing error and info message in this service is preserved
byte-for-byte from the source COBOL programs. The messages are
defined as module-private constants (``_MSG_*``) with an inline
docstring citing the COBOL source location. Key messages include:

* ``'Changes committed to database'``
  (COACTUPC.cbl ``CONFIRM-UPDATE-SUCCESS``; update success)
* ``'Changes unsuccessful. Please try again'``
  (COACTUPC.cbl ``INFORM-FAILURE``; generic update failure)
* ``'No change detected with respect to values fetched.'``
  (COACTUPC.cbl ``NO-CHANGES-DETECTED``; dual-write no-op)
* ``'Did not find this account in account card xref file'``
  (COACTVWC.cbl ``DID-NOT-FIND-ACCT-IN-CARDXREF``)
* ``'Did not find this account in account master file'``
  (COACTVWC.cbl ``DID-NOT-FIND-ACCT-IN-ACCTDAT``)
* ``'Did not find associated customer in master file'``
  (COACTVWC.cbl ``DID-NOT-FIND-CUST-IN-CUSTDAT``)
* ``'Account Active Status must be Y or N'``
  (COACTUPC.cbl ``ACCT-STATUS-MUST-BE-YES-NO``; 1220-EDIT-YESNO)
* ``'Account number must be a non zero 11 digit number'``
  (COACTUPC.cbl ``SEARCHED-ACCT-NOT-NUMERIC``)

Observability
-------------
All operations emit structured log records via the module logger.
Log records include the ``acct_id`` and ``cust_id`` fields for
CloudWatch Logs Insights correlation. Sensitive fields (SSN, govt
ID, phone numbers) are NEVER logged — only a fixed "redacted"
indicator is written. Log levels:

* ``INFO``    — successful view retrieval, successful update (with
  old and new version_id for audit trails).
* ``WARNING`` — business-rule failures: entity not found, validation
  failure, no-change-detected, stale-data on update.
* ``ERROR``   — unexpected SQLAlchemy / driver exceptions (emitted
  via ``logger.exception`` / ``logger.error(exc_info=True)`` to
  preserve the full traceback alongside structured context).

See Also
--------
* AAP §0.2.3 — Online CICS Program Classification (F-004, F-005)
* AAP §0.5.1 — File-by-File Transformation Plan (account_service.py)
* AAP §0.7.1 — Refactoring-Specific Rules (preserve exact COBOL
  messages; dual-write pattern must remain atomic; optimistic
  concurrency must be maintained)
* ``src/shared/models/account.py`` — ORM model (300-byte VSAM
  ACCOUNT-RECORD) with ``version_id_col`` optimistic-concurrency
* ``src/shared/models/customer.py`` — ORM model (500-byte VSAM
  CUSTOMER-RECORD); no version column (updated in Account's tx)
* ``src/shared/models/card_cross_reference.py`` — xref ORM model
  used for account-based customer linkage (50-byte VSAM
  CARD-XREF-RECORD; maps to the CXACAIX alternate index)
* ``src/shared/schemas/account_schema.py`` — Pydantic request /
  response schemas (3 classes) and COBOL-sourced width constants
* ``src/shared/utils/date_utils.py`` — CCYYMMDD / DOB date
  validators ported from CSUTLDTC.cbl
* ``src/shared/utils/decimal_utils.py`` — COBOL-compatible Decimal
  helpers (``safe_decimal``, ``round_financial``) for monetary fields
* ``src/api/services/card_service.py`` — sibling service; shares
  the same structured-logging, ``_safe_rollback``, and
  optimistic-concurrency idioms applied here
"""

from __future__ import annotations

import logging
import re
from decimal import Decimal

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm.exc import StaleDataError

from src.shared.constants.lookup_codes import VALID_GENERAL_PURPOSE_CODES
from src.shared.models.account import Account
from src.shared.models.card_cross_reference import CardCrossReference
from src.shared.models.customer import Customer
from src.shared.schemas.account_schema import (
    AccountUpdateRequest,
    AccountUpdateResponse,
    AccountViewResponse,
)
from src.shared.utils.date_utils import (
    validate_date_ccyymmdd,
    validate_date_of_birth,
)
from src.shared.utils.decimal_utils import round_financial, safe_decimal

logger: logging.Logger = logging.getLogger(__name__)
"""Module-scoped logger.

Emits structured events for account view and account update
operations. Uses the fully-qualified module path so log routing
(e.g. ``src.api.services`` level thresholds) can isolate
account-service chatter from other services. Correlation IDs added
by the FastAPI request-scope middleware propagate automatically
through :func:`logging.getLogger` context filters.
"""


# ----------------------------------------------------------------------------
# Private field-width constants
# ----------------------------------------------------------------------------
# The widths below mirror the PIC clauses on the source COBOL copybooks
# (CVACT01Y.cpy, CVCUS01Y.cpy, CVACT03Y.cpy) and the field attributes
# declared on the BMS symbolic maps (COACTVW.CPY, COACTUP.CPY). The
# constants are private (``_`` prefix) and used only within this module;
# callers that need the same widths rely on the Pydantic ``max_length``
# constraints declared on :class:`AccountUpdateRequest` /
# :class:`AccountViewResponse` instead of importing these values.
# ----------------------------------------------------------------------------

_ACCT_ID_LEN: int = 11
"""Account ID width — COBOL ``PIC 9(11)`` (CVACT01Y.cpy ACCT-ID)."""

_CUST_ID_LEN: int = 9
"""Customer ID width — COBOL ``PIC 9(09)`` (CVCUS01Y.cpy CUST-ID)."""

_DATE_LEN: int = 10
"""Date display width — ``CCYY-MM-DD`` (10 chars incl. hyphens)."""

_DATE_YEAR_LEN: int = 4
"""Year segment width — ``CCYY``."""

_DATE_MM_DD_LEN: int = 2
"""Month / day segment width — ``MM`` and ``DD``."""

_FLAG_LEN: int = 1
"""Single-char flag width — active_status, pri_card_holder_ind: ``PIC X(01)``."""

_SSN_PART1_LEN: int = 3
"""SSN area segment width — first 3 digits (COACTUPC.cbl CUST-SSN-1)."""

_SSN_PART2_LEN: int = 2
"""SSN group segment width — middle 2 digits (COACTUPC.cbl CUST-SSN-2)."""

_SSN_PART3_LEN: int = 4
"""SSN serial segment width — last 4 digits (COACTUPC.cbl CUST-SSN-3)."""

_SSN_STORED_LEN: int = 9
"""SSN VSAM stored width — 9 raw digits in CVCUS01Y.cpy CUST-SSN."""

_SSN_DISPLAY_LEN: int = 11
"""SSN display width — ``NNN-NN-NNNN`` (11 chars incl. hyphens)."""

_PHONE_AREA_LEN: int = 3
"""Phone area code segment width — COACTUPC.cbl CUST-PHONE-NUM-1-AREA."""

_PHONE_PREFIX_LEN: int = 3
"""Phone prefix segment width — COACTUPC.cbl CUST-PHONE-NUM-1-PREFIX."""

_PHONE_LINE_LEN: int = 4
"""Phone line segment width — COACTUPC.cbl CUST-PHONE-NUM-1-LINE."""

_PHONE_DISPLAY_LEN: int = 13
"""Phone display width — ``(AAA)BBB-CCCC`` (13 chars incl. punctuation)."""

_PHONE_STORED_LEN: int = 15
"""Phone VSAM stored width — 15 chars in CVCUS01Y.cpy CUST-PHONE-NUM-X."""

_FICO_LEN: int = 3
"""FICO score width — COBOL ``PIC 9(03)`` (CVCUS01Y.cpy CUST-FICO-CREDIT-SCORE)."""

_FICO_MIN: int = 300
"""Minimum valid FICO score — industry floor (below 300 = "no score")."""

_FICO_MAX: int = 850
"""Maximum valid FICO score — industry ceiling."""

_NAME_LEN: int = 25
"""First / middle / last name field width — COBOL ``PIC X(25)``."""

_ADDR_LINE_LEN: int = 50
"""Address-line field width — COBOL ``PIC X(50)``.

CVCUS01Y.cpy declares ``CUST-ADDR-LINE-1`` / ``-2`` / ``-3`` each as
``PIC X(50)``; ``-3`` is repurposed by the BMS map (COACTVW.CPY /
COACTUP.CPY) to carry the city name, since the mainframe layout
predates the standard street / city separation.
"""

_STATE_CD_LEN: int = 2
"""US state code width — 2-letter USPS abbreviation (e.g. 'CA')."""

_COUNTRY_CD_LEN: int = 3
"""ISO country code width — 3-letter (e.g. 'USA')."""

_ZIP_LEN: int = 5
"""ZIP code width — 5-digit US ZIP (the 10-char VSAM field holds ZIP+4
  elsewhere, but COACTVW.CPY only emits the leading 5)."""

_GOVT_ID_LEN: int = 20
"""Government-issued-ID field width — driver's license, passport, etc."""

_EFT_ACCT_LEN: int = 10
"""EFT account identifier width — COBOL ``PIC X(10)`` (CVCUS01Y.cpy CUST-EFT-ACCOUNT-ID)."""


# ----------------------------------------------------------------------------
# Private message constants — byte-for-byte from COBOL source
# ----------------------------------------------------------------------------
# Per AAP §0.7.1 rule 1 ("Preserve all existing functionality exactly
# as-is"), every user-facing message is reproduced verbatim from the
# COBOL source. The messages below are never f-string interpolated —
# they are stored as fixed strings so the CloudWatch log-metric filters
# inherited from the mainframe era continue to match. Any new message
# added here must also be added to the module docstring "Error message
# fidelity" table above.
# ----------------------------------------------------------------------------

_MSG_UPDATE_SUCCESS: str = "Changes committed to database"
"""Successful dual-write confirmation (COACTUPC.cbl CONFIRM-UPDATE-SUCCESS)."""

_MSG_UPDATE_FAILED: str = "Changes unsuccessful. Please try again"
"""Generic update-failure message (COACTUPC.cbl INFORM-FAILURE)."""

# Dedicated internal-integrity-violation message used when ``_parse_request``
# raises :class:`ValueError` despite ``_validate_request`` having returned
# success.  In COBOL this condition would never arise because the validator
# and the parser share identical working-storage; in Python the two functions
# operate on the request string separately and a validator/parser contract
# violation (e.g., a validator gap) would surface here.  Per CP3 review
# finding MINOR #11 ("Parse errors indistinguishable from validation errors
# — loss of COBOL error-code fidelity") we use a DISTINCT literal so the
# user-facing message, the structured log record, and any downstream
# monitoring can tell apart:
#
#   * Field-level validation failure (specific COBOL message via
#     ``_validate_request`` -> ``error_message``)
#   * Internal parse-phase inconsistency (this message)
#   * Database-layer update failure (``_MSG_UPDATE_FAILED``)
#
# The message is phrased to signal "this request cannot be processed" rather
# than inviting a retry — a retry on the same input would reproduce the
# parser/validator disagreement.  Not a COBOL-sourced literal (the condition
# is Python-specific); documented explicitly as AAP-consistent reporting.
_MSG_PARSE_FAILED: str = "Unable to process update request due to internal validation mismatch"
"""Defensive-in-depth internal-integrity message (CP3 MINOR #11)."""

_MSG_UPDATE_STALE: str = "Record changed by some one else. Please review"
"""Optimistic-concurrency mismatch (COACTUPC.cbl DATA-WAS-CHANGED-BEFORE-UPDATE)."""

# COBOL source (COACTUPC.cbl line 492) carries the verbatim literal
# "No change detected with respect to values fetched." (50 characters
# including the trailing period). The BMS ``INFOMSGO`` output field
# is declared as ``PIC X(45)`` — on the mainframe this 50-char
# literal would silently truncate for display to 45 characters. Per
# AAP §0.7.1 ("Preserve existing functionality exactly as-is") we
# restore the full authored 50-character literal here; the modernized
# API has no BMS screen painter and therefore no natural truncation
# step, so the full string is surfaced to the client. The Pydantic
# schema ``AccountUpdateResponse.info_message`` has been widened in
# tandem from ``max_length=45`` to ``max_length=50`` to admit this
# COBOL-verbatim value (see ``src/shared/schemas/account_schema.py``
# ``_INFO_MSG_LEN``). (Code Review Finding MAJOR #5.)
_MSG_NO_CHANGES: str = "No change detected with respect to values fetched."
"""No-op update — request equals stored values (COACTUPC.cbl line 492, NO-CHANGES-DETECTED)."""

_MSG_VIEW_XREF_NOT_FOUND: str = "Did not find this account in account card xref file"
"""Xref miss on the acct_id alternate index (COACTVWC.cbl DID-NOT-FIND-ACCT-IN-CARDXREF)."""

_MSG_VIEW_ACCT_NOT_FOUND: str = "Did not find this account in account master file"
"""Account miss on ACCTDAT primary key (COACTVWC.cbl DID-NOT-FIND-ACCT-IN-ACCTDAT)."""

_MSG_VIEW_CUST_NOT_FOUND: str = "Did not find associated customer in master file"
"""Customer miss on CUSTDAT primary key (COACTVWC.cbl DID-NOT-FIND-CUST-IN-CUSTDAT)."""

# ----------------------------------------------------------------------------
# Standalone literals — STANDALONE COBOL error strings (no WS-EDIT-VARIABLE-NAME
# prefix, emitted verbatim by the source program).
# ----------------------------------------------------------------------------
# The following literals are emitted by COACTUPC.cbl / COACTVWC.cbl **without**
# the ``FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) + suffix`` template pattern that
# dominates the rest of the validation cascade. They appear as standalone
# ``STRING`` / ``MOVE`` statements and therefore carry no field-name prefix.
# ----------------------------------------------------------------------------

_MSG_ACCT_MISSING: str = "Account number not provided"
"""Blank account_id on the request (COACTVWC.cbl ACCT-NUMBER-NOT-SUPPLIED)."""

# COACTUPC.cbl ``1210-EDIT-ACCOUNT`` paragraph (lines 1787-1817) emits this
# STANDALONE literal as a STRING of two fragments: ``'Account Number if
# supplied must be a 11 digit'`` + ``' Non-Zero Number'``. There is NO TRIM
# prefix and NO trailing period.
_MSG_ACCT_INVALID: str = "Account Number if supplied must be a 11 digit Non-Zero Number"
"""Bad account_id format (COACTUPC.cbl 1210-EDIT-ACCOUNT lines 1787-1817)."""

# Dedicated message for the REST-specific path/body account_id mismatch
# condition.  COACTUPC.cbl has only ONE account-number input field on its BMS
# screen (ACCTSIDI), so the legacy program never encountered this mismatch
# scenario — it is purely a by-product of the modern REST routing where the
# account identifier appears in BOTH the URL path (``/accounts/{acct_id}``)
# AND the request body (``AccountUpdateRequest.account_id``).  The schema's
# contract (``account_schema.py`` line 959) states "the service layer MUST
# verify that the body's account_id matches the URL path".  Using
# ``_MSG_ACCT_INVALID`` here was misleading because that message describes a
# format error; at Guard 3 both IDs are already known to be valid 11-digit
# non-zero numbers — they simply disagree with each other.  This dedicated
# literal accurately reports the actual condition, addressing CP3 review
# finding MINOR #10 ("path/body msg mismatch").  NOT a COBOL-sourced message.
_MSG_ACCT_PATH_BODY_MISMATCH: str = "Account number in URL path does not match request body"
"""Path/body account_id disagreement (REST-specific; no COBOL equivalent)."""

# COACTUPC.cbl ``1280-EDIT-US-STATE-ZIP-CD`` paragraph emits this STANDALONE
# literal on a bad state/zip combo check (line ~2550). No TRIM prefix and no
# trailing period.
_MSG_ZIP_STATE_INVALID: str = "Invalid zip code for state"
"""Mismatched zip/state combination (COACTUPC.cbl 1280-EDIT-US-STATE-ZIP-CD)."""


# ----------------------------------------------------------------------------
# WS-EDIT-VARIABLE-NAME values — COBOL field labels per COACTUPC.cbl 1472-1657
# ----------------------------------------------------------------------------
# Before each field-level ``PERFORM 1215-EDIT-MANDATORY`` / ``1220-EDIT-YESNO`` /
# ``1245-EDIT-NUM-REQD`` / etc., COACTUPC.cbl sets ``WS-EDIT-VARIABLE-NAME`` to
# a field label (e.g. ``'Account Status'``, ``'FICO Score'``). The edit
# paragraph then builds the error via
# ``STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) DELIMITED BY SIZE, <suffix>``
# so the label becomes a prefix on every error message. We reproduce the same
# labels verbatim here and feed them through :func:`_field_msg` below.
# ----------------------------------------------------------------------------

_FLD_ACCT_STATUS: str = "Account Status"
"""COACTUPC.cbl L1472 WS-EDIT-VARIABLE-NAME for active_status."""

_FLD_OPEN_DATE: str = "Open Date"
"""COACTUPC.cbl L1478 WS-EDIT-VARIABLE-NAME for open_date."""

_FLD_CREDIT_LIMIT: str = "Credit Limit"
"""COACTUPC.cbl L1484 WS-EDIT-VARIABLE-NAME for credit_limit."""

_FLD_EXPIRY_DATE: str = "Expiry Date"
"""COACTUPC.cbl L1490 WS-EDIT-VARIABLE-NAME for expiration_date."""

_FLD_CASH_CREDIT_LIMIT: str = "Cash Credit Limit"
"""COACTUPC.cbl L1496 WS-EDIT-VARIABLE-NAME for cash_credit_limit."""

_FLD_REISSUE_DATE: str = "Reissue Date"
"""COACTUPC.cbl L1503 WS-EDIT-VARIABLE-NAME for reissue_date."""

_FLD_CURRENT_BALANCE: str = "Current Balance"
"""COACTUPC.cbl L1509 WS-EDIT-VARIABLE-NAME for current_balance."""

_FLD_CURR_CYC_CREDIT: str = "Current Cycle Credit Limit"
"""COACTUPC.cbl L1515 WS-EDIT-VARIABLE-NAME for curr_cyc_credit."""

_FLD_CURR_CYC_DEBIT: str = "Current Cycle Debit Limit"
"""COACTUPC.cbl L1522 WS-EDIT-VARIABLE-NAME for curr_cyc_debit."""

_FLD_DOB: str = "Date of Birth"
"""COACTUPC.cbl L1533 WS-EDIT-VARIABLE-NAME for customer_dob."""

_FLD_FICO: str = "FICO Score"
"""COACTUPC.cbl L1545 WS-EDIT-VARIABLE-NAME for customer_fico_score."""

_FLD_FIRST_NAME: str = "First Name"
"""COACTUPC.cbl L1560 WS-EDIT-VARIABLE-NAME for customer_first_name."""

_FLD_MIDDLE_NAME: str = "Middle Name"
"""COACTUPC.cbl L1568 WS-EDIT-VARIABLE-NAME for customer_middle_name."""

_FLD_LAST_NAME: str = "Last Name"
"""COACTUPC.cbl L1576 WS-EDIT-VARIABLE-NAME for customer_last_name."""

_FLD_ADDR_LINE_1: str = "Address Line 1"
"""COACTUPC.cbl L1584 WS-EDIT-VARIABLE-NAME for customer_addr_line_1."""

_FLD_STATE: str = "State"
"""COACTUPC.cbl L1592 WS-EDIT-VARIABLE-NAME for customer_state_cd."""

_FLD_ZIP: str = "Zip"
"""COACTUPC.cbl L1605 WS-EDIT-VARIABLE-NAME for customer_zip."""

_FLD_CITY: str = "City"
"""COACTUPC.cbl L1615 WS-EDIT-VARIABLE-NAME for customer_city."""

_FLD_COUNTRY: str = "Country"
"""COACTUPC.cbl L1623 WS-EDIT-VARIABLE-NAME for customer_country_cd."""

_FLD_PHONE_1: str = "Phone Number 1"
"""COACTUPC.cbl L1632 WS-EDIT-VARIABLE-NAME for phone_number_1."""

_FLD_PHONE_2: str = "Phone Number 2"
"""COACTUPC.cbl L1640 WS-EDIT-VARIABLE-NAME for phone_number_2."""

_FLD_EFT_ACCOUNT_ID: str = "EFT Account Id"
"""COACTUPC.cbl L1648 WS-EDIT-VARIABLE-NAME for eft_account_id."""

_FLD_PRIMARY_CARD_HOLDER: str = "Primary Card Holder"
"""COACTUPC.cbl L1657 WS-EDIT-VARIABLE-NAME for pri_card_holder_ind."""

# SSN WS-EDIT-VARIABLE-NAME overrides — COACTUPC.cbl ``1265-EDIT-US-SSN``
# paragraph MOVEs a different label for each of the three SSN segments before
# calling ``1245-EDIT-NUM-REQD``, producing three distinct prefixes on the
# downstream "must be supplied / all numeric / not zero" errors.
_FLD_SSN_PART1: str = "SSN: First 3 chars"
"""COACTUPC.cbl L2439 override for 1245-EDIT-NUM-REQD on SSN part 1."""

_FLD_SSN_PART2: str = "SSN 4th & 5th chars"
"""COACTUPC.cbl L2469 override for 1245-EDIT-NUM-REQD on SSN part 2."""

_FLD_SSN_PART3: str = "SSN Last 4 chars"
"""COACTUPC.cbl L2481 override for 1245-EDIT-NUM-REQD on SSN part 3."""


# ----------------------------------------------------------------------------
# Edit-paragraph suffix constants — verbatim COBOL STRING fragments
# ----------------------------------------------------------------------------
# The COBOL edit paragraphs emit the error message as:
#
#     STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) DELIMITED BY SIZE
#            <SUFFIX-LITERAL>                     DELIMITED BY SIZE
#         INTO WS-ERROR-MESSAGE
#
# For paragraphs 1215-1250 (required/optional alpha/alnum/numeric/monetary),
# the suffix starts with a SPACE (e.g. ``' must be supplied.'``) so the
# concatenated output is ``"<field_name> must be supplied."``.
#
# For paragraph 1260 (phone) and 1265 (SSN Part1 invalid-area) / 1270 (state)
# / 1275 (FICO), the suffix starts with ``': '`` (colon-space) so the
# concatenated output is ``"<field_name>: <suffix>"``.
#
# A handful of suffixes omit the trailing period — preserved exactly here.
# ----------------------------------------------------------------------------

# 1215-EDIT-MANDATORY / 1220-EDIT-YESNO blank / 1225-EDIT-ALPHA-REQD blank /
# 1230-EDIT-ALPHANUM-REQD blank / 1245-EDIT-NUM-REQD blank /
# 1250-EDIT-SIGNED-9V2 blank.
_SFX_MUST_BE_SUPPLIED: str = " must be supplied."
"""COACTUPC.cbl edit-paragraph suffix for mandatory-blank errors."""

# 1220-EDIT-YESNO invalid Y/N.
_SFX_MUST_BE_Y_OR_N: str = " must be Y or N."
"""COACTUPC.cbl 1220-EDIT-YESNO invalid-value suffix (line 1890)."""

# 1225-EDIT-ALPHA-REQD / 1235-EDIT-ALPHA-OPT non-alphabetic.
_SFX_CAN_HAVE_ALPHABETS_ONLY: str = " can have alphabets only."
"""COACTUPC.cbl alpha-edit paragraphs non-alphabetic suffix."""

# 1230-EDIT-ALPHANUM-REQD / 1240-EDIT-ALPHANUM-OPT non-alphanumeric.
_SFX_CAN_HAVE_ALNUM_ONLY: str = " can have numbers or alphabets only."
"""COACTUPC.cbl alnum-edit paragraphs non-alphanumeric suffix."""

# 1245-EDIT-NUM-REQD not-numeric.
_SFX_MUST_BE_ALL_NUMERIC: str = " must be all numeric."
"""COACTUPC.cbl 1245-EDIT-NUM-REQD non-numeric suffix."""

# 1245-EDIT-NUM-REQD zero.
_SFX_MUST_NOT_BE_ZERO: str = " must not be zero."
"""COACTUPC.cbl 1245-EDIT-NUM-REQD zero-value suffix."""

# 1250-EDIT-SIGNED-9V2 NUMVAL-C failure. NOTE: no trailing period — preserved
# verbatim from COACTUPC.cbl line 2209.
_SFX_IS_NOT_VALID: str = " is not valid"
"""COACTUPC.cbl 1250-EDIT-SIGNED-9V2 invalid-number suffix (NO PERIOD)."""

# 1260-EDIT-US-PHONE-NUM area-code sub-paragraph.
_SFX_AREA_MUST_BE_SUPPLIED: str = ": Area code must be supplied."
"""COACTUPC.cbl EDIT-AREA-CODE blank suffix (line 2254)."""

# NOTE: "A 3 digit number" uses a CAPITAL A in the COBOL source — preserved
# verbatim even though it is grammatically unusual.
_SFX_AREA_MUST_BE_3_DIGIT: str = ": Area code must be A 3 digit number."
"""COACTUPC.cbl EDIT-AREA-CODE non-numeric suffix (line 2272, CAPITAL A)."""

# NOTE: NO trailing period — preserved verbatim from COACTUPC.cbl line 2286.
_SFX_AREA_CANNOT_BE_ZERO: str = ": Area code cannot be zero"
"""COACTUPC.cbl EDIT-AREA-CODE zero suffix (NO PERIOD)."""

# NOTE: NO trailing period — preserved verbatim from COACTUPC.cbl line 2306.
_SFX_AREA_NOT_VALID_NANPA: str = ": Not valid North America general purpose area code"
"""COACTUPC.cbl EDIT-AREA-CODE NANPA-lookup-fail suffix (NO PERIOD)."""

# 1260 phone prefix sub-paragraph.
_SFX_PREFIX_MUST_BE_SUPPLIED: str = ": Prefix code must be supplied."
"""COACTUPC.cbl EDIT-US-PHONE-PREFIX blank suffix (line 2325)."""

_SFX_PREFIX_MUST_BE_3_DIGIT: str = ": Prefix code must be A 3 digit number."
"""COACTUPC.cbl EDIT-US-PHONE-PREFIX non-numeric suffix (CAPITAL A)."""

# NOTE: NO trailing period.
_SFX_PREFIX_CANNOT_BE_ZERO: str = ": Prefix code cannot be zero"
"""COACTUPC.cbl EDIT-US-PHONE-PREFIX zero suffix (NO PERIOD)."""

# 1260 phone line sub-paragraph.
_SFX_LINE_MUST_BE_SUPPLIED: str = ": Line number code must be supplied."
"""COACTUPC.cbl EDIT-US-PHONE-LINENUM blank suffix (line 2378)."""

_SFX_LINE_MUST_BE_4_DIGIT: str = ": Line number code must be A 4 digit number."
"""COACTUPC.cbl EDIT-US-PHONE-LINENUM non-numeric suffix (CAPITAL A, 4 digits)."""

# NOTE: NO trailing period.
_SFX_LINE_CANNOT_BE_ZERO: str = ": Line number code cannot be zero"
"""COACTUPC.cbl EDIT-US-PHONE-LINENUM zero suffix (NO PERIOD)."""

# 1265-EDIT-US-SSN INVALID-SSN-PART1 check (invalid SSA area). NOTE: NO
# trailing period — preserved verbatim from COACTUPC.cbl line ~2464.
_SFX_SSN_PART1_INVALID: str = ": should not be 000, 666, or between 900 and 999"
"""COACTUPC.cbl 1265-EDIT-US-SSN INVALID-SSN-PART1 suffix (NO PERIOD)."""

# 1270-EDIT-US-STATE-CD invalid state. NOTE: NO trailing period — preserved
# verbatim from COACTUPC.cbl line 2503.
_SFX_IS_NOT_A_VALID_STATE: str = ": is not a valid state code"
"""COACTUPC.cbl 1270-EDIT-US-STATE-CD suffix (NO PERIOD)."""

# 1275-EDIT-FICO-SCORE out-of-range. NOTE: NO trailing period — preserved
# verbatim from COACTUPC.cbl line 2523.
_SFX_FICO_OUT_OF_RANGE: str = ": should be between 300 and 850"
"""COACTUPC.cbl 1275-EDIT-FICO-SCORE out-of-range suffix (NO PERIOD)."""


# ----------------------------------------------------------------------------
# Message-builder helper
# ----------------------------------------------------------------------------


def _field_msg(field_name: str, suffix: str) -> str:
    """Concatenate a WS-EDIT-VARIABLE-NAME label with a COBOL suffix literal.

    Replicates the COBOL ``STRING`` statement used by every edit
    paragraph (1215-EDIT-MANDATORY, 1220-EDIT-YESNO, 1225-EDIT-ALPHA-REQD,
    1230-EDIT-ALPHANUM-REQD, 1245-EDIT-NUM-REQD, 1250-EDIT-SIGNED-9V2,
    1260-EDIT-US-PHONE-NUM, 1265-EDIT-US-SSN, 1270-EDIT-US-STATE-CD,
    1275-EDIT-FICO-SCORE) of COACTUPC.cbl:

    .. code-block:: cobol

        STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) DELIMITED BY SIZE,
               ' must be supplied.'                 DELIMITED BY SIZE
            INTO WS-ERROR-MESSAGE

    ``FUNCTION TRIM`` in COBOL removes both leading and trailing spaces
    from the field label, which we replicate with :meth:`str.strip`.

    Parameters
    ----------
    field_name : str
        The WS-EDIT-VARIABLE-NAME label (see the ``_FLD_*`` constants
        defined above). Typically 2-30 characters.
    suffix : str
        The COBOL suffix literal — one of the ``_SFX_*`` constants
        defined above. Preserved byte-for-byte including any leading
        space or leading ``": "`` prefix, and INCLUDING or EXCLUDING
        the trailing period exactly as the COBOL source does.

    Returns
    -------
    str
        The concatenated error message string. Per AAP §0.7.1
        ("Preserve existing functionality exactly as-is") the output
        is byte-for-byte identical to what the COBOL STRING statement
        produces.

    Examples
    --------
    >>> _field_msg(_FLD_FICO, _SFX_FICO_OUT_OF_RANGE)
    'FICO Score: should be between 300 and 850'
    >>> _field_msg(_FLD_ACCT_STATUS, _SFX_MUST_BE_Y_OR_N)
    'Account Status must be Y or N.'
    """
    return f"{field_name.strip()}{suffix}"


# ----------------------------------------------------------------------------
# Private regex constants
# ----------------------------------------------------------------------------
_RE_DIGITS_ONLY: re.Pattern[str] = re.compile(r"^\d+$")
"""All-ASCII-digits pattern. Used for numeric field validation where
COBOL relies on TEST-NUMVAL-C; in Python we use a regex for clarity."""

_RE_LETTERS_ONLY: re.Pattern[str] = re.compile(r"^[A-Za-z]+$")
"""All-ASCII-letters pattern. Used for state and country code
validation (2 or 3 alphabetic chars, no digits)."""

_RE_SSN_9: re.Pattern[str] = re.compile(r"^\d{9}$")
"""Exact 9-digit SSN pattern (3+2+4 without hyphens)."""

# Per SSA policy: SSN area numbers of 000, 666, and 900-999 are never
# issued. COACTUPC.cbl encodes this at lines 118-135 as the
# ``88 INVALID-SSN-PART1 VALUES 0, 666, 900 THRU 999`` level-88.
_INVALID_SSN_AREAS: frozenset[str] = frozenset({"000", "666"} | {str(n).zfill(3) for n in range(900, 1000)})
"""Invalid SSN area codes per SSA (COACTUPC.cbl INVALID-SSN-PART1)."""


# ============================================================================
# AccountService — public API
# ============================================================================


class AccountService:
    """Service implementing F-004 (Account View) and F-005 (Account Update).

    This class encapsulates the business logic extracted from
    ``app/cbl/COACTVWC.cbl`` (941 lines) and ``app/cbl/COACTUPC.cbl``
    (4,236 lines). Each public method corresponds to a CICS transaction:

    * :meth:`get_account_view` — replaces CICS transaction CAVW (Account
      View). Performs the 3-entity keyed read chain CXACAIX ->
      ACCTDAT -> CUSTDAT and returns the assembled display record.
    * :meth:`update_account` — replaces CICS transaction CAUP (Account
      Update). Performs field validation, change detection, and a
      dual-write update on Account + Customer with single-transaction
      atomicity and optimistic-concurrency guarding via the Account
      ``version_id`` column.

    Instances are lightweight (they hold only an AsyncSession reference)
    and are constructed per-request by the FastAPI dependency-injection
    layer. Do NOT share an instance across requests — the bound
    AsyncSession is scoped to the FastAPI request lifecycle.

    Parameters
    ----------
    db : AsyncSession
        An async SQLAlchemy session bound to Aurora PostgreSQL. Must
        be active (not closed); the caller is responsible for session
        lifecycle (open, commit/rollback for non-mutation flows, close).

    Attributes
    ----------
    db : AsyncSession
        The bound async session used for all database operations.

    Thread Safety
    -------------
    Not thread-safe — the bound AsyncSession itself is not thread-safe
    per SQLAlchemy's documentation. Since FastAPI invokes async
    handlers in a single event loop per worker, this is not a concern
    in the standard deployment topology.

    Examples
    --------
    Typical usage via FastAPI dependency injection::

        from fastapi import Depends
        from src.api.services.account_service import AccountService
        from src.api.dependencies import get_db

        def get_account_service(
            db: AsyncSession = Depends(get_db),
        ) -> AccountService:
            return AccountService(db)

        @router.get("/accounts/{account_id}")
        async def get_account(
            account_id: str,
            service: AccountService = Depends(get_account_service),
        ) -> AccountViewResponse:
            return await service.get_account_view(account_id)
    """

    def __init__(self, db: AsyncSession) -> None:
        """Bind the service to an async SQLAlchemy session.

        Parameters
        ----------
        db : AsyncSession
            An active async SQLAlchemy session. The service does NOT
            own the session's lifecycle — the caller (typically a
            FastAPI dependency) is responsible for opening and
            closing it.
        """
        self.db: AsyncSession = db

    # ------------------------------------------------------------------
    # F-004 — Account View
    # ------------------------------------------------------------------

    async def get_account_view(self, acct_id: str) -> AccountViewResponse:
        """Retrieve the full Account + Customer display record.

        Implements the 3-entity keyed read chain from
        ``app/cbl/COACTVWC.cbl`` paragraphs 9200-GETCARDXREF-BYACCT,
        9300-GETACCTDATA-BYACCT, and 9400-GETCUSTDATA-BYCUST.

        The read sequence is:

        1. ``SELECT * FROM card_cross_references WHERE acct_id = ?``
           (first row; maps to ``READ DATASET('CXACAIX') RIDFLD(ACCT-ID)``
           on the VSAM alternate index)
        2. ``SELECT * FROM accounts WHERE acct_id = ?``
           (maps to ``READ DATASET('ACCTDAT') RIDFLD(ACCT-ID)``)
        3. ``SELECT * FROM customers WHERE cust_id = ?``
           (where ``cust_id`` comes from step 1; maps to
           ``READ DATASET('CUSTDAT') RIDFLD(CUST-ID)``)

        This is a read-only operation: the service does NOT commit or
        roll back. The caller's session context manager owns
        transaction boundaries for read operations.

        Parameters
        ----------
        acct_id : str
            The 11-digit account identifier. Leading / trailing
            whitespace is stripped; anything other than exactly 11
            digits yields a view response carrying the
            ``_MSG_ACCT_INVALID`` error message. Parameter name
            ``acct_id`` aligns with the ``Account.acct_id`` ORM
            attribute and the AAP's exported signature.

        Returns
        -------
        AccountViewResponse
            The populated view record on success. On validation or
            lookup failure, returns a response with blanked data
            fields and an ``error_message`` string that matches the
            COBOL source byte-for-byte.

        Notes
        -----
        Does not raise on not-found — per COACTVWC.cbl behaviour,
        missing xref / account / customer rows are surfaced via the
        ``error_message`` field of the response so the BMS map (now
        the JSON response) can display them on the same screen. Only
        unexpected infrastructure exceptions (connection loss, DDL
        mismatch, etc.) are allowed to propagate to the FastAPI
        exception handler.
        """
        normalized_id: str = (acct_id or "").strip()

        log_context: dict[str, object] = {
            "operation": "get_account_view",
            "acct_id": normalized_id,
        }

        # --- Guard 1: account_id must be supplied ------------------------
        # Maps to COACTVWC.cbl ACCT-NUMBER-NOT-SUPPLIED branch of
        # 1100-PROCESS-INPUTS.
        if not normalized_id:
            logger.warning(
                "Account view rejected: account_id missing",
                extra=log_context,
            )
            return _build_view_error_response(normalized_id, _MSG_ACCT_MISSING)

        # --- Guard 2: account_id must be 11 numeric digits --------------
        # Maps to COACTVWC.cbl SEARCHED-ACCT-NOT-NUMERIC check and the
        # COACTUPC.cbl 1210-EDIT-ACCOUNT paragraph. Also rejects
        # all-zero account_id per the "non zero" clause of the
        # canonical error message.
        if not _validate_account_id(normalized_id):
            logger.warning(
                "Account view rejected: account_id invalid format",
                extra=log_context,
            )
            return _build_view_error_response(normalized_id, _MSG_ACCT_INVALID)

        # --- Step 1: CardCrossReference lookup by acct_id ---------------
        # COBOL: EXEC CICS READ FILE('CXACAIX') INTO(CARD-XREF-RECORD)
        #                 RIDFLD(XREF-ACCT-ID)
        # SQL  : SELECT * FROM card_cross_references WHERE acct_id = ?
        # Notes: The xref table is built on top of the VSAM alternate
        # index (CXACAIX). Multiple card numbers may map to the same
        # account, but the original COBOL program uses only the first
        # match to resolve the customer — we replicate that with
        # ``.first()`` rather than ``.one()`` to remain tolerant of
        # the 1:many shape.
        try:
            xref_stmt = select(CardCrossReference).where(CardCrossReference.acct_id == normalized_id)
            xref_result = await self.db.execute(xref_stmt)
            xref: CardCrossReference | None = xref_result.scalars().first()
        except Exception as exc:  # noqa: BLE001 — blanket catch per COBOL WHEN OTHER
            logger.error(
                "Account view xref query failed",
                extra={
                    **log_context,
                    "error_type": type(exc).__name__,
                },
                exc_info=True,
            )
            return _build_view_error_response(normalized_id, _MSG_VIEW_XREF_NOT_FOUND)

        if xref is None:
            # COBOL: WHEN DFHRESP(NOTFND) ->
            #   MOVE 'Y' TO WS-DID-NOT-FIND-ACCT-IN-CARDXREF
            logger.warning(
                "Account view: xref not found",
                extra=log_context,
            )
            return _build_view_error_response(normalized_id, _MSG_VIEW_XREF_NOT_FOUND)

        log_context["cust_id"] = xref.cust_id

        # --- Step 2: Account lookup by acct_id --------------------------
        # COBOL: EXEC CICS READ FILE('ACCTDAT') INTO(ACCOUNT-RECORD)
        #                 RIDFLD(ACCT-ID)
        # SQL  : SELECT * FROM accounts WHERE acct_id = ?
        try:
            account: Account | None = await self.db.get(Account, normalized_id)
        except Exception as exc:  # noqa: BLE001 — blanket catch per COBOL WHEN OTHER
            logger.error(
                "Account view account query failed",
                extra={
                    **log_context,
                    "error_type": type(exc).__name__,
                },
                exc_info=True,
            )
            return _build_view_error_response(normalized_id, _MSG_VIEW_ACCT_NOT_FOUND)

        if account is None:
            logger.warning(
                "Account view: account not found",
                extra=log_context,
            )
            return _build_view_error_response(normalized_id, _MSG_VIEW_ACCT_NOT_FOUND)

        # --- Step 3: Customer lookup by cust_id -------------------------
        # COBOL: EXEC CICS READ FILE('CUSTDAT') INTO(CUSTOMER-RECORD)
        #                 RIDFLD(CUST-ID)
        # SQL  : SELECT * FROM customers WHERE cust_id = ?
        try:
            customer: Customer | None = await self.db.get(Customer, xref.cust_id)
        except Exception as exc:  # noqa: BLE001 — blanket catch per COBOL WHEN OTHER
            logger.error(
                "Account view customer query failed",
                extra={
                    **log_context,
                    "error_type": type(exc).__name__,
                },
                exc_info=True,
            )
            return _build_view_error_response(normalized_id, _MSG_VIEW_CUST_NOT_FOUND)

        if customer is None:
            logger.warning(
                "Account view: customer not found",
                extra=log_context,
            )
            return _build_view_error_response(normalized_id, _MSG_VIEW_CUST_NOT_FOUND)

        # --- Step 4: Assemble the view response -------------------------
        # COBOL: 1100-PROCESS-INPUTS paragraph — MOVE each record field
        # into the corresponding BMS map field, formatting SSN / phones
        # as display strings.
        response: AccountViewResponse = _assemble_view_response(
            account, customer, info_message=None, error_message=None
        )

        logger.info(
            "Account view retrieved",
            extra={**log_context, "card_num": xref.card_num},
        )
        return response

    # ------------------------------------------------------------------
    # F-005 — Account Update
    # ------------------------------------------------------------------

    async def update_account(self, acct_id: str, request: AccountUpdateRequest) -> AccountUpdateResponse:
        """Dual-write update of an Account plus its associated Customer.

        Implements the full COACTUPC.cbl (4,236 lines) transaction
        with:

        * path / body consistency check (acct_id in URL vs.
          request.account_id)
        * field-level validation matching the COBOL 1200-EDIT-MAP-INPUTS
          paragraph cascade
        * change detection equivalent to COACTUPC.cbl
          1205-COMPARE-OLD-NEW (no-change = no-op, with info message)
        * optimistic-concurrency enforcement via
          :attr:`Account.version_id`
        * single-transaction dual-write: Account is mutated, Customer
          is mutated, one ``flush()``/``commit()`` covers both, one
          ``rollback()`` on any exception covers both (the EXACT
          Python analogue of the CICS SYNCPOINT ROLLBACK on line
          ~4100 of COACTUPC.cbl)

        Parameters
        ----------
        acct_id : str
            The 11-digit account identifier from the URL path. Must
            match ``request.account_id``; a mismatch yields an error
            response without touching the database. Parameter name
            ``acct_id`` aligns with the ``Account.acct_id`` ORM
            attribute and the AAP's exported signature.
        request : AccountUpdateRequest
            The fully-validated update payload. Field-width limits
            are enforced by Pydantic; the richer
            content validation (numeric, date, SSN, state, phone,
            FICO) is enforced in this method to preserve the exact
            COBOL error messages.

        Returns
        -------
        AccountUpdateResponse
            On success, the newly-persisted account + customer state
            with ``info_message = 'Changes committed to database'``.
            On failure (validation, not-found, stale-data, rollback),
            an error response carrying the original request's field
            values and a byte-for-byte COBOL error message via
            ``error_message``.
        """
        normalized_id: str = (acct_id or "").strip()

        log_context: dict[str, object] = {
            "operation": "update_account",
            "acct_id": normalized_id,
        }

        # --- Guard 1: acct_id must be supplied ---------------------------
        if not normalized_id:
            logger.warning(
                "Account update rejected: acct_id missing",
                extra=log_context,
            )
            return _build_update_error_response(normalized_id, request, _MSG_ACCT_MISSING)

        # --- Guard 2: acct_id must be 11 numeric non-zero digits -------
        if not _validate_account_id(normalized_id):
            logger.warning(
                "Account update rejected: acct_id invalid format",
                extra=log_context,
            )
            return _build_update_error_response(normalized_id, request, _MSG_ACCT_INVALID)

        # --- Guard 3: URL-path acct_id must match request body ----------
        # When the body account_id is supplied AND differs from the
        # URL-path value, both IDs have already been shown to be
        # well-formed 11-digit non-zero numbers (otherwise Guard 2 or
        # Pydantic validation would have intercepted earlier).  The
        # condition is therefore NOT a format error — it is a
        # disagreement between two valid identifiers.  Using the
        # dedicated ``_MSG_ACCT_PATH_BODY_MISMATCH`` literal addresses
        # CP3 review finding MINOR #10 by making the error message
        # describe the actual condition rather than misreporting it as
        # a format error.  This is a REST-specific concern; COACTUPC.cbl
        # has no parallel because BMS screens carry a single
        # ``ACCTSIDI`` input field.
        if request.account_id and request.account_id.strip() != normalized_id:
            logger.warning(
                "Account update rejected: path/body acct_id mismatch",
                extra={
                    **log_context,
                    "body_acct_id": request.account_id.strip(),
                },
            )
            return _build_update_error_response(normalized_id, request, _MSG_ACCT_PATH_BODY_MISMATCH)

        # --- Step 1: Read existing state (xref + account + customer) ---
        # Same 3-entity chain as :meth:`get_account_view`, but we
        # need the entities (not just the view projection) to apply
        # attribute-level mutations and to compare old vs. new for
        # change detection.
        try:
            xref_stmt = select(CardCrossReference).where(CardCrossReference.acct_id == normalized_id)
            xref_result = await self.db.execute(xref_stmt)
            xref: CardCrossReference | None = xref_result.scalars().first()
        except Exception as exc:  # noqa: BLE001 — blanket catch per COBOL WHEN OTHER
            logger.error(
                "Account update xref query failed",
                extra={
                    **log_context,
                    "error_type": type(exc).__name__,
                },
                exc_info=True,
            )
            await _safe_rollback(self.db, log_context)
            return _build_update_error_response(normalized_id, request, _MSG_VIEW_XREF_NOT_FOUND)

        if xref is None:
            logger.warning(
                "Account update: xref not found",
                extra=log_context,
            )
            return _build_update_error_response(normalized_id, request, _MSG_VIEW_XREF_NOT_FOUND)

        log_context["cust_id"] = xref.cust_id

        try:
            account: Account | None = await self.db.get(Account, normalized_id)
        except Exception as exc:  # noqa: BLE001 — blanket catch per COBOL WHEN OTHER
            logger.error(
                "Account update account read failed",
                extra={
                    **log_context,
                    "error_type": type(exc).__name__,
                },
                exc_info=True,
            )
            await _safe_rollback(self.db, log_context)
            return _build_update_error_response(normalized_id, request, _MSG_VIEW_ACCT_NOT_FOUND)

        if account is None:
            logger.warning(
                "Account update: account not found",
                extra=log_context,
            )
            return _build_update_error_response(normalized_id, request, _MSG_VIEW_ACCT_NOT_FOUND)

        try:
            customer: Customer | None = await self.db.get(Customer, xref.cust_id)
        except Exception as exc:  # noqa: BLE001 — blanket catch per COBOL WHEN OTHER
            logger.error(
                "Account update customer read failed",
                extra={
                    **log_context,
                    "error_type": type(exc).__name__,
                },
                exc_info=True,
            )
            await _safe_rollback(self.db, log_context)
            return _build_update_error_response(normalized_id, request, _MSG_VIEW_CUST_NOT_FOUND)

        if customer is None:
            logger.warning(
                "Account update: customer not found",
                extra=log_context,
            )
            return _build_update_error_response(normalized_id, request, _MSG_VIEW_CUST_NOT_FOUND)

        # --- Step 1b: Fill missing fields from existing records --------
        # QA Checkpoint 5, Finding #5 — support partial updates by
        # merging caller-supplied overrides with the stored values
        # before validation. For fields the caller did not supply
        # (``None`` on the request), the stored Account/Customer
        # values are decomposed back into BMS-style segments and
        # substituted into the merged request. After this step
        # every field is guaranteed to be populated with a non-
        # ``None`` value, so the downstream COBOL-verbatim
        # validator and parser contracts are unchanged. Callers
        # who submit a full 39-field payload are unaffected
        # (every ``None`` check short-circuits to the caller value),
        # so this is a backward-compatible enhancement.
        request = _fill_missing_from_existing(request, account, customer)

        # --- Step 2: Validate all request fields -----------------------
        # COBOL: 1200-EDIT-MAP-INPUTS paragraph cascade. Each helper
        # returns either None (valid) or a COBOL error-message string.
        # We short-circuit on the first failure and return an error
        # response — matching the COBOL "EDIT-FAILED -> SEND-MAP with
        # red message" behaviour.
        validation_error: str | None = _validate_request(request)
        if validation_error is not None:
            logger.warning(
                "Account update rejected: validation failed",
                extra={
                    **log_context,
                    "error_message": validation_error,
                },
            )
            return _build_update_error_response(normalized_id, request, validation_error)

        # --- Step 3: Parse request -> canonical storage representation ---
        # COBOL: various field-assembly paragraphs. The request carries
        # segmented date / SSN / phone fields (to mirror the BMS map);
        # we assemble them into the canonical stored form before
        # comparing with the current DB state.
        try:
            parsed: _ParsedRequest = _parse_request(request)
        except ValueError as exc:
            # Defensive in depth: this branch is reachable only if the
            # validator/parser contract is violated — i.e.,
            # ``_validate_request`` returned ``None`` (success) yet
            # ``_parse_request`` could not complete.  In COBOL the two
            # phases share identical working storage so the condition
            # cannot arise; in Python they operate on the request string
            # independently.  Per CP3 review finding MINOR #11 we use
            # (a) a dedicated ``_MSG_PARSE_FAILED`` message so the
            # response is distinguishable from both validation errors
            # (specific COBOL messages) and ``_MSG_UPDATE_FAILED``
            # (DB-level failure expected to be retryable), and (b)
            # ``logger.error`` (not ``warning``) with
            # ``exc_info=True`` because this represents an internal
            # contract violation worthy of operator investigation.
            logger.error(
                "Account update rejected: parse failure",
                extra={
                    **log_context,
                    "error_type": type(exc).__name__,
                    "error_message": str(exc),
                },
                exc_info=True,
            )
            return _build_update_error_response(normalized_id, request, _MSG_PARSE_FAILED)

        # --- Step 4: Change detection (1205-COMPARE-OLD-NEW) ----------
        # COBOL: MOVE old field to WS-OLD; MOVE new field to WS-NEW;
        # IF WS-OLD NOT EQUAL WS-NEW -> SET CHANGE-HAS-OCCURRED.
        if not _detect_changes(account, customer, parsed):
            logger.info(
                "Account update: no changes detected",
                extra=log_context,
            )
            # Return current state with the informational message;
            # error_message is cleared because this is a benign no-op.
            return _assemble_update_response(account, customer, info_message=_MSG_NO_CHANGES)

        # --- Step 5: Apply mutations to the ORM instances --------------
        # COBOL: MOVE new values into ACCT-UPDATE-RECORD /
        # CUST-UPDATE-RECORD working storage, then REWRITE. We mutate
        # the ORM entities' attributes; SQLAlchemy tracks the dirty
        # state and emits the UPDATE statements on flush.
        _apply_account_mutations(account, parsed)
        _apply_customer_mutations(customer, parsed)

        old_version_id: int = account.version_id
        log_context["old_version_id"] = old_version_id

        # --- Step 6: Dual-write flush + commit -------------------------
        # COBOL: EXEC CICS REWRITE FILE('ACCTDAT') FROM(ACCT-UPDATE-RECORD)
        #        EXEC CICS REWRITE FILE('CUSTDAT') FROM(CUST-UPDATE-RECORD)
        #        EXEC CICS SYNCPOINT        (on success)
        #        EXEC CICS SYNCPOINT ROLLBACK (on failure of second REWRITE)
        #
        # In SQLAlchemy, both dirty entities are included in the same
        # flush — this produces two UPDATE statements in a single
        # PostgreSQL transaction. If either UPDATE fails (constraint
        # violation, stale version_id, connectivity loss), the
        # exception propagates and we roll back; PostgreSQL atomicity
        # ensures NEITHER row is persisted in that case. This
        # preserves the CICS SYNCPOINT ROLLBACK semantics on line
        # ~4100 of COACTUPC.cbl exactly.
        try:
            await self.db.flush()
            await self.db.commit()
        except StaleDataError as exc:
            # Optimistic-concurrency mismatch on Account.version_id.
            # COBOL equivalent: READ UPDATE attempt fails with
            # DFHRESP(NOTOPEN) / DFHRESP(INVREQ) when another task
            # already holds the lock — COACTUPC surfaces this via
            # the DATA-WAS-CHANGED-BEFORE-UPDATE message.
            logger.warning(
                "Account update: stale-data (optimistic-concurrency conflict)",
                extra={
                    **log_context,
                    "error_type": type(exc).__name__,
                },
            )
            await _safe_rollback(self.db, log_context)
            return _build_update_error_response(normalized_id, request, _MSG_UPDATE_STALE)
        except IntegrityError as exc:
            # Constraint violation on either table — e.g. NOT NULL
            # violation, check constraint failure. Treated as a
            # generic update-failure per COBOL.
            logger.warning(
                "Account update: integrity error",
                extra={
                    **log_context,
                    "error_type": type(exc).__name__,
                    "orig": str(exc.orig) if exc.orig else "",
                },
            )
            await _safe_rollback(self.db, log_context)
            return _build_update_error_response(normalized_id, request, _MSG_UPDATE_FAILED)
        except Exception as exc:  # noqa: BLE001 — blanket catch per COBOL WHEN OTHER
            # Catch-all for unexpected failures (connectivity loss,
            # driver errors, etc.). The SYNCPOINT ROLLBACK on
            # COACTUPC.cbl line ~4100 corresponds EXACTLY to the
            # await _safe_rollback(...) call below — both discard
            # both mutations atomically.
            logger.error(
                "Account update: unexpected failure during commit",
                extra={
                    **log_context,
                    "error_type": type(exc).__name__,
                },
                exc_info=True,
            )
            await _safe_rollback(self.db, log_context)
            return _build_update_error_response(normalized_id, request, _MSG_UPDATE_FAILED)

        # --- Step 7: Happy path — refresh and return ------------------
        # SQLAlchemy has incremented account.version_id in-place
        # during flush. Capture the new value for the audit log.
        new_version_id: int = account.version_id
        log_context["new_version_id"] = new_version_id

        logger.info(
            "Account update: changes committed to database",
            extra=log_context,
        )

        # Return the post-update projection of Account + Customer so
        # the caller (and the BMS-equivalent JSON response) sees the
        # newly-persisted state, matching COACTUPC.cbl's 2000-SEND-MAP
        # behaviour after CONFIRM-UPDATE-SUCCESS.
        return _assemble_update_response(account, customer, info_message=_MSG_UPDATE_SUCCESS)


# ============================================================================
# Module-private helper functions
# ============================================================================
# These helpers encapsulate the small, self-contained data-massaging
# steps that keep the :class:`AccountService` public methods readable.
# All helpers are pure (no side effects on database state) except
# :func:`_safe_rollback`, which deliberately mutates session state.
# ============================================================================


class _ParsedRequest:
    """Canonical in-memory representation of an AccountUpdateRequest.

    Maps the segmented BMS-shaped request fields (year/month/day
    triples, SSN part1/part2/part3, phone area/prefix/line) into
    the canonical stored forms used by the ORM (ISO ``YYYY-MM-DD``
    dates, raw 9-digit SSN, ``(NNN)NNN-NNNN`` phone). Monetary
    values are scaled to 2 decimal places using
    :func:`round_financial` and FICO score is converted to the
    integer type used by the Customer.fico_credit_score column.

    This intermediate representation serves two purposes:

    1. It lets :func:`_detect_changes` compare old vs. new values
       in the same canonical shape the DB uses, avoiding false
       positives from format drift (e.g. ``"100.5"`` vs.
       ``Decimal("100.50")``).
    2. It decouples :func:`_apply_account_mutations` and
       :func:`_apply_customer_mutations` from the request-schema
       shape, letting them work on a stable domain-oriented
       object.

    Attributes are populated only by :func:`_parse_request`; this
    class is not intended to be constructed directly by callers.
    """

    __slots__ = (
        # Account attributes (12)
        "active_status",
        "open_date",
        "credit_limit",
        "expiration_date",
        "cash_credit_limit",
        "reissue_date",
        "group_id",
        # Customer attributes (17)
        "ssn",
        "dob",
        "fico_credit_score",
        "first_name",
        "middle_name",
        "last_name",
        "addr_line_1",
        "addr_line_2",
        "addr_line_3",
        "state_cd",
        "country_cd",
        "addr_zip",
        "phone_num_1",
        "phone_num_2",
        "govt_issued_id",
        "eft_account_id",
        "pri_card_holder_ind",
    )

    # Account attributes
    active_status: str
    open_date: str
    credit_limit: Decimal
    expiration_date: str
    cash_credit_limit: Decimal
    reissue_date: str
    group_id: str
    # Customer attributes
    ssn: str
    dob: str
    fico_credit_score: int
    first_name: str
    middle_name: str
    last_name: str
    addr_line_1: str
    addr_line_2: str
    addr_line_3: str
    state_cd: str
    country_cd: str
    addr_zip: str
    phone_num_1: str
    phone_num_2: str
    govt_issued_id: str
    eft_account_id: str
    pri_card_holder_ind: str


# ---------------------------------------------------------------------------
# Individual field validators
# ---------------------------------------------------------------------------


def _validate_account_id(value: str) -> bool:
    """Validate the 11-digit non-zero account_id format.

    Maps to the COBOL ``1210-EDIT-ACCOUNT`` paragraph which enforces
    that the account identifier is exactly 11 numeric characters
    and is not all-zero (the "non zero" clause of the error message
    "Account number must be a non zero 11 digit number").

    Parameters
    ----------
    value : str
        The (already trimmed) account identifier to validate.

    Returns
    -------
    bool
        ``True`` if ``value`` is an 11-digit string whose numeric
        interpretation is nonzero; ``False`` otherwise.
    """
    if len(value) != _ACCT_ID_LEN:
        return False
    if not _RE_DIGITS_ONLY.match(value):
        return False
    # "Non zero" — "00000000000" must be rejected.
    if value == "0" * _ACCT_ID_LEN:
        return False
    return True


def _validate_yes_no(value: str) -> bool:
    """Validate a single-character Y/N flag.

    Maps to the COBOL ``1220-EDIT-YESNO`` paragraph which enforces
    that active_status and pri_card_holder_ind are exactly ``"Y"``
    or ``"N"`` (uppercase; the COBOL level-88 is case-sensitive).

    Parameters
    ----------
    value : str
        The flag value.

    Returns
    -------
    bool
        ``True`` if ``value`` is exactly ``"Y"`` or ``"N"``.
    """
    return value in ("Y", "N")


def _validate_fico_score(value: str) -> int | None:
    """Validate the 3-digit FICO score string.

    Maps to the COBOL ``1275-EDIT-FICO-SCORE`` paragraph which
    enforces that the FICO score is a 3-digit numeric value within
    the industry-standard 300-850 range.

    Parameters
    ----------
    value : str
        The FICO score string as supplied on the request.

    Returns
    -------
    int | None
        The integer FICO score if valid (in range 300-850);
        ``None`` otherwise.
    """
    if len(value) != _FICO_LEN or not _RE_DIGITS_ONLY.match(value):
        return None
    score: int = int(value)
    if score < _FICO_MIN or score > _FICO_MAX:
        return None
    return score


def _validate_us_ssn(part1: str, part2: str, part3: str) -> str | None:
    """Validate the 3-2-4 SSN segmentation per COACTUPC.cbl 1265-EDIT-US-SSN.

    Maps to the COBOL ``1265-EDIT-US-SSN`` paragraph (lines 2431-2491)
    plus the SSA area-number blacklist defined at COACTUPC.cbl lines
    118-135 (``88 INVALID-SSN-PART1 VALUES 0, 666, 900 THRU 999``).

    The COBOL paragraph calls ``1245-EDIT-NUM-REQD`` for each of the
    three SSN segments in turn, substituting a segment-specific label
    into ``WS-EDIT-VARIABLE-NAME`` before each call:

    - Part 1 (3 chars): label ``'SSN: First 3 chars'``  (line 2439)
    - Part 2 (2 chars): label ``'SSN 4th & 5th chars'`` (line 2469)
    - Part 3 (4 chars): label ``'SSN Last 4 chars'``    (line 2481)

    If the three segments are structurally valid (all numeric, none
    zero), a final ``INVALID-SSN-PART1`` check rejects the SSA-reserved
    area numbers (000, 666, 900-999), emitting the COBOL
    ``': should not be 000, 666, or between 900 and 999'`` suffix with
    the ``'SSN: First 3 chars'`` label as prefix.

    Parameters
    ----------
    part1, part2, part3 : str
        The three SSN segments, each expected to be all-digits of
        the specified length (3, 2, 4 respectively).

    Returns
    -------
    str | None
        A COBOL-byte-exact error-message string describing the
        violation if the SSN is invalid, or ``None`` if valid.

    Notes
    -----
    The structural check ordering matches the COBOL cascade: blank
    first, non-numeric second, zero third — then SSA area blacklist
    as a post-validation check. Each segment is validated with its
    own label, so the error message pinpoints WHICH segment failed.
    """
    segments: tuple[tuple[str, str, int], ...] = (
        (_FLD_SSN_PART1, part1, _SSN_PART1_LEN),
        (_FLD_SSN_PART2, part2, _SSN_PART2_LEN),
        (_FLD_SSN_PART3, part3, _SSN_PART3_LEN),
    )
    # 1245-EDIT-NUM-REQD cascade for each segment: blank → not-numeric
    # → zero. The COBOL paragraph short-circuits on the first failure
    # within each segment.
    for label, value, expected_len in segments:
        if value == "" or value.strip() == "":
            return _field_msg(label, _SFX_MUST_BE_SUPPLIED)
        if len(value) != expected_len or not _RE_DIGITS_ONLY.match(value):
            return _field_msg(label, _SFX_MUST_BE_ALL_NUMERIC)
        if int(value) == 0:
            return _field_msg(label, _SFX_MUST_NOT_BE_ZERO)
    # SSA rules: 000, 666, 900-999 are never issued as area numbers.
    # COACTUPC.cbl 1265-EDIT-US-SSN INVALID-SSN-PART1 check. (The
    # 000 sub-case is already handled by the zero check above but
    # the area-range check also rejects the other reserved blocks.)
    if part1 in _INVALID_SSN_AREAS:
        return _field_msg(_FLD_SSN_PART1, _SFX_SSN_PART1_INVALID)
    return None


def _validate_us_phone(phone_label: str, area: str, prefix: str, line: str) -> str | None:
    """Validate the 3-3-4 phone number segmentation per COACTUPC.cbl 1260.

    Maps to the COBOL ``1260-EDIT-US-PHONE-NUM`` paragraph and its
    three sub-paragraphs:

    - ``EDIT-AREA-CODE``         (lines 2246-2314)
    - ``EDIT-US-PHONE-PREFIX``   (lines 2316-2368)
    - ``EDIT-US-PHONE-LINENUM``  (lines 2370-2425)

    Each sub-paragraph runs its own cascade: blank → not-numeric →
    zero → (area only) NANPA-lookup. Per COACTUPC.cbl lines 2234-2244
    an all-blank phone is accepted as a valid OPTIONAL submission.

    The error message built by the COBOL source is the concatenation
    of the parent ``WS-EDIT-VARIABLE-NAME`` (e.g. ``'Phone Number 1'``)
    and the sub-paragraph suffix (which itself starts with ``': '``),
    e.g. ``"Phone Number 1: Area code must be supplied."``.

    Parameters
    ----------
    phone_label : str
        The COBOL WS-EDIT-VARIABLE-NAME label for the parent phone
        field — either ``_FLD_PHONE_1`` or ``_FLD_PHONE_2``.
    area, prefix, line : str
        The three phone segments: area code (3 digits), prefix
        (3 digits), line number (4 digits).

    Returns
    -------
    str | None
        A COBOL-byte-exact error-message string describing the
        violation, or ``None`` if valid (including the all-blank
        case).

    Notes
    -----
    The COBOL source additionally cross-references the area code
    against the ``VALID-GENERAL-PURP-CODE`` 88-level lookup at
    ``app/cpy/CSLKPCDY.cpy`` line 521 — approximately 410 real NANPA
    geographic codes that explicitly EXCLUDE the 80 "easily
    recognizable codes" (ERC) such as ``211`` / ``800`` / ``911``.
    We import the Python translation of that lookup from
    :data:`src.shared.constants.lookup_codes.VALID_GENERAL_PURPOSE_CODES`
    (produced from the same COBOL source at import-time) and apply
    the exact same membership test the COBOL ``IF
    VALID-GENERAL-PURP-CODE`` performs at COACTUPC.cbl line 2298.
    A stricter N11-only check (rejecting codes starting with 0 or 1
    but accepting ERC codes) would both false-positive and
    false-negative relative to the COBOL source and is therefore
    inappropriate.
    """
    # All-blank phone is a valid OPTIONAL submission (COACTUPC.cbl
    # lines 2234-2244): ``IF WS-EDIT-US-PHONE-NUMA = SPACES AND
    # WS-EDIT-US-PHONE-NUMP = SPACES AND WS-EDIT-US-PHONE-NUML = SPACES
    # CONTINUE``.  Any fixed-width PIC X field is padded with spaces
    # so the COBOL ``= SPACES`` test is equivalent to ``.strip() == ""``
    # in Python — this covers both the empty-string case (caller
    # supplied ``""``) and the all-spaces case (caller supplied the
    # BMS-padded form ``"   "``).
    if area.strip() == "" and prefix.strip() == "" and line.strip() == "":
        return None

    # --- Area code sub-paragraph (EDIT-AREA-CODE, COACTUPC.cbl 2246-2314) ---
    if area == "" or area.strip() == "":
        return _field_msg(phone_label, _SFX_AREA_MUST_BE_SUPPLIED)
    if len(area) != _PHONE_AREA_LEN or not _RE_DIGITS_ONLY.match(area):
        return _field_msg(phone_label, _SFX_AREA_MUST_BE_3_DIGIT)
    if int(area) == 0:
        return _field_msg(phone_label, _SFX_AREA_CANNOT_BE_ZERO)
    # NANPA general-purpose area-code membership check.  Directly
    # mirrors COACTUPC.cbl line 2298 ``IF VALID-GENERAL-PURP-CODE``
    # (the 88-level condition whose value list is enumerated at
    # CSLKPCDY.cpy line 521 and mechanically translated into the
    # ``VALID_GENERAL_PURPOSE_CODES`` frozenset).  This replaces the
    # earlier N11 heuristic (``area[0] in {'0','1'}``) which was
    # simultaneously (a) too loose — it accepted ERC codes like
    # ``211``/``800``/``911`` that COBOL rejects — and (b) too strict
    # in edge cases; per MINOR #13 alignment with COACTUPC.cbl L2298
    # the full lookup is required.
    if area not in VALID_GENERAL_PURPOSE_CODES:
        return _field_msg(phone_label, _SFX_AREA_NOT_VALID_NANPA)

    # --- Prefix sub-paragraph (EDIT-US-PHONE-PREFIX, COACTUPC.cbl 2316-2368) ---
    if prefix == "" or prefix.strip() == "":
        return _field_msg(phone_label, _SFX_PREFIX_MUST_BE_SUPPLIED)
    if len(prefix) != _PHONE_PREFIX_LEN or not _RE_DIGITS_ONLY.match(prefix):
        return _field_msg(phone_label, _SFX_PREFIX_MUST_BE_3_DIGIT)
    if int(prefix) == 0:
        return _field_msg(phone_label, _SFX_PREFIX_CANNOT_BE_ZERO)

    # --- Line-number sub-paragraph (EDIT-US-PHONE-LINENUM, COACTUPC.cbl 2370-2425) ---
    if line == "" or line.strip() == "":
        return _field_msg(phone_label, _SFX_LINE_MUST_BE_SUPPLIED)
    if len(line) != _PHONE_LINE_LEN or not _RE_DIGITS_ONLY.match(line):
        return _field_msg(phone_label, _SFX_LINE_MUST_BE_4_DIGIT)
    if int(line) == 0:
        return _field_msg(phone_label, _SFX_LINE_CANNOT_BE_ZERO)
    return None


def _validate_state_code(value: str) -> bool:
    """Validate a 2-character US state code.

    Maps to the COBOL ``1270-EDIT-US-STATE-CD`` paragraph.
    Minimum structural validation: exactly 2 ASCII alphabetic
    characters. Full lookup against the USPS abbreviation list
    (``CSLKPCDY.cpy`` ``VALID-US-STATE-CODE``) is not available
    because ``src/shared/constants/lookup_codes.py`` is outside
    the dependency whitelist for this service.

    Parameters
    ----------
    value : str
        The state code.

    Returns
    -------
    bool
        ``True`` if ``value`` is exactly 2 alphabetic characters.
    """
    if len(value) != _STATE_CD_LEN:
        return False
    if not _RE_LETTERS_ONLY.match(value):
        return False
    return True


def _validate_country_code(value: str) -> bool:
    """Validate a 3-character ISO country code.

    Minimum structural validation: exactly 3 alphabetic
    characters. The COBOL source accepts any 3-char alpha
    country code without a lookup list.

    Parameters
    ----------
    value : str
        The country code.

    Returns
    -------
    bool
        ``True`` if ``value`` is exactly 3 alphabetic characters.
    """
    if len(value) != _COUNTRY_CD_LEN:
        return False
    if not _RE_LETTERS_ONLY.match(value):
        return False
    return True


def _validate_zip_code(value: str) -> bool:
    """Validate a 5-digit US ZIP code.

    Minimum structural validation: exactly 5 digits. This is less
    permissive than the Customer.addr_zip column (which is
    String(10) to allow ZIP+4), because the COACTUP.CPY BMS map
    only accepts 5 digits.

    Parameters
    ----------
    value : str
        The ZIP code.

    Returns
    -------
    bool
        ``True`` if ``value`` is exactly 5 digits.
    """
    if len(value) != _ZIP_LEN:
        return False
    if not _RE_DIGITS_ONLY.match(value):
        return False
    return True


def _validate_request(request: AccountUpdateRequest) -> str | None:
    """Execute the full COACTUPC.cbl 1200-EDIT-MAP-INPUTS cascade.

    The COBOL paragraph runs through every field in the update
    screen, stopping at the first error — the error is displayed
    on the BMS map and the user is prompted to correct it. We
    replicate this short-circuit behavior by returning the COBOL
    error-message string of the first failure and letting the
    caller return an error response to the client.

    Error messages are built through :func:`_field_msg` which
    concatenates a WS-EDIT-VARIABLE-NAME label (the ``_FLD_*``
    constants) with a COBOL suffix literal (the ``_SFX_*`` constants)
    so every output is byte-for-byte identical to what the COBOL
    source emits. For date fields the message is passed through from
    :func:`validate_date_ccyymmdd` / :func:`validate_date_of_birth`
    in ``src.shared.utils.date_utils``, both of which already emit
    COBOL-verbatim error messages with the correct field-name prefix.

    Parameters
    ----------
    request : AccountUpdateRequest
        The already-structurally-validated request (Pydantic has
        verified length and type; this method verifies content).

    Returns
    -------
    str | None
        The COBOL-byte-exact error-message string of the first
        failure, or ``None`` if every field passes validation.
    """
    # --- Non-None invariant (QA Checkpoint 5 Finding #5 fix) -------
    # ``AccountUpdateRequest`` carries Optional fields so that the
    # router accepts partial updates (PATCH-style semantics). By
    # contract, however, this validator runs ONLY after
    # :func:`_fill_missing_from_existing` has been invoked at
    # :meth:`AccountService.update_account` (line 1251) — that helper
    # guarantees every field on the returned request is populated
    # with a non-``None`` value drawn either from the caller's
    # payload or from the stored Account/Customer record. The
    # assertions below reaffirm this invariant: they both (a) guard
    # against a programmer error if a future caller forgets to merge
    # the stored values in, and (b) narrow the Optional types down
    # to their non-``None`` counterparts so mypy can statically
    # verify the downstream ``.strip()`` / ``round_financial(...)`` /
    # ``_join_date(...)`` / ``_validate_*(...)`` calls without a
    # union-attr or arg-type error. Each assertion emits a
    # deterministic message that maps back to the originating
    # invariant source.
    assert request.active_status is not None, (
        "invariant violation: _fill_missing_from_existing must populate active_status"
    )
    assert request.credit_limit is not None, (
        "invariant violation: _fill_missing_from_existing must populate credit_limit"
    )
    assert request.cash_credit_limit is not None, (
        "invariant violation: _fill_missing_from_existing must populate cash_credit_limit"
    )
    assert request.open_date_year is not None, (
        "invariant violation: _fill_missing_from_existing must populate open_date_year"
    )
    assert request.open_date_month is not None, (
        "invariant violation: _fill_missing_from_existing must populate open_date_month"
    )
    assert request.open_date_day is not None, (
        "invariant violation: _fill_missing_from_existing must populate open_date_day"
    )
    assert request.expiration_date_year is not None, (
        "invariant violation: _fill_missing_from_existing must populate expiration_date_year"
    )
    assert request.expiration_date_month is not None, (
        "invariant violation: _fill_missing_from_existing must populate expiration_date_month"
    )
    assert request.expiration_date_day is not None, (
        "invariant violation: _fill_missing_from_existing must populate expiration_date_day"
    )
    assert request.reissue_date_year is not None, (
        "invariant violation: _fill_missing_from_existing must populate reissue_date_year"
    )
    assert request.reissue_date_month is not None, (
        "invariant violation: _fill_missing_from_existing must populate reissue_date_month"
    )
    assert request.reissue_date_day is not None, (
        "invariant violation: _fill_missing_from_existing must populate reissue_date_day"
    )
    assert request.customer_dob_year is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_dob_year"
    )
    assert request.customer_dob_month is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_dob_month"
    )
    assert request.customer_dob_day is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_dob_day"
    )
    assert request.customer_first_name is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_first_name"
    )
    assert request.customer_last_name is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_last_name"
    )
    assert request.customer_fico_score is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_fico_score"
    )
    assert request.customer_ssn_part1 is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_ssn_part1"
    )
    assert request.customer_ssn_part2 is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_ssn_part2"
    )
    assert request.customer_ssn_part3 is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_ssn_part3"
    )
    assert request.customer_state_cd is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_state_cd"
    )
    assert request.customer_country_cd is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_country_cd"
    )
    assert request.customer_zip is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_zip"
    )
    assert request.customer_phone_1_area is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_phone_1_area"
    )
    assert request.customer_phone_1_prefix is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_phone_1_prefix"
    )
    assert request.customer_phone_1_line is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_phone_1_line"
    )
    assert request.customer_phone_2_area is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_phone_2_area"
    )
    assert request.customer_phone_2_prefix is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_phone_2_prefix"
    )
    assert request.customer_phone_2_line is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_phone_2_line"
    )
    assert request.customer_pri_cardholder is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_pri_cardholder"
    )

    # --- Account flags (1220-EDIT-YESNO) ---------------------------
    # COACTUPC.cbl L1472 sets WS-EDIT-VARIABLE-NAME = 'Account Status'
    # then PERFORMs 1220-EDIT-YESNO, which builds a byte-for-byte
    # concatenation of the label and ' must be Y or N.' (with trailing
    # period) on an invalid Y/N.
    if request.active_status.strip() == "":
        return _field_msg(_FLD_ACCT_STATUS, _SFX_MUST_BE_SUPPLIED)
    if not _validate_yes_no(request.active_status):
        return _field_msg(_FLD_ACCT_STATUS, _SFX_MUST_BE_Y_OR_N)

    # --- Monetary fields (1250-EDIT-SIGNED-9V2) --------------------
    # COACTUPC.cbl L1484, L1496, L1509, L1515, L1522 each set
    # WS-EDIT-VARIABLE-NAME = '<Credit Limit|Cash Credit Limit|...>'
    # then PERFORM 1250-EDIT-SIGNED-9V2. The ``' is not valid'``
    # suffix explicitly has NO trailing period (COACTUPC.cbl L2209).
    # Pydantic has already enforced the Decimal type via the schema
    # validator ``_validate_monetary_non_negative``; the remaining
    # check is that the value is in a reasonable scale. We attempt
    # round_financial which will raise on NaN/infinity.
    try:
        round_financial(request.credit_limit)
    except (ValueError, ArithmeticError):
        return _field_msg(_FLD_CREDIT_LIMIT, _SFX_IS_NOT_VALID)
    try:
        round_financial(request.cash_credit_limit)
    except (ValueError, ArithmeticError):
        return _field_msg(_FLD_CASH_CREDIT_LIMIT, _SFX_IS_NOT_VALID)

    # --- Date validation (1285-EDIT-DATE-CCYYMMDD) -----------------
    # COACTUPC.cbl L1478, L1490, L1503 set WS-EDIT-VARIABLE-NAME =
    # 'Open Date' / 'Expiry Date' / 'Reissue Date' then PERFORM
    # 1285-EDIT-DATE-CCYYMMDD, which delegates to CSUTLDPY.cpy
    # paragraphs that already emit byte-exact error messages
    # prefixed with the field name (see validate_date_ccyymmdd in
    # src/shared/utils/date_utils.py). We pass the WS-EDIT-VARIABLE-
    # NAME through and return the DateValidationResult.error_message
    # verbatim per AAP §0.7.1.
    open_date_ccyymmdd: str = _join_date(
        request.open_date_year,
        request.open_date_month,
        request.open_date_day,
    )
    open_date_result = validate_date_ccyymmdd(open_date_ccyymmdd, field_name=_FLD_OPEN_DATE)
    if not open_date_result.is_valid:
        return open_date_result.error_message or _field_msg(_FLD_OPEN_DATE, _SFX_IS_NOT_VALID)

    expiry_date_ccyymmdd: str = _join_date(
        request.expiration_date_year,
        request.expiration_date_month,
        request.expiration_date_day,
    )
    expiry_date_result = validate_date_ccyymmdd(expiry_date_ccyymmdd, field_name=_FLD_EXPIRY_DATE)
    if not expiry_date_result.is_valid:
        return expiry_date_result.error_message or _field_msg(_FLD_EXPIRY_DATE, _SFX_IS_NOT_VALID)

    reissue_date_ccyymmdd: str = _join_date(
        request.reissue_date_year,
        request.reissue_date_month,
        request.reissue_date_day,
    )
    reissue_date_result = validate_date_ccyymmdd(reissue_date_ccyymmdd, field_name=_FLD_REISSUE_DATE)
    if not reissue_date_result.is_valid:
        return reissue_date_result.error_message or _field_msg(_FLD_REISSUE_DATE, _SFX_IS_NOT_VALID)

    # --- Date of birth (EDIT-DATE-OF-BIRTH) ------------------------
    # COACTUPC.cbl L1533 sets WS-EDIT-VARIABLE-NAME = 'Date of Birth'
    # then PERFORM EDIT-DATE-OF-BIRTH (CSUTLDPY.cpy lines 341-372),
    # which first delegates to EDIT-DATE-CCYYMMDD and, on structural
    # success, additionally rejects future dates with the literal
    # ':cannot be in the future ' (trailing space, no period).
    # validate_date_of_birth in date_utils.py already emits both
    # branches byte-exact; we pass the field name through and return
    # its error message verbatim.
    dob_ccyymmdd: str = _join_date(
        request.customer_dob_year,
        request.customer_dob_month,
        request.customer_dob_day,
    )
    dob_result = validate_date_of_birth(dob_ccyymmdd, field_name=_FLD_DOB)
    if not dob_result.is_valid:
        return dob_result.error_message or _field_msg(_FLD_DOB, _SFX_IS_NOT_VALID)

    # --- Customer name (1215-EDIT-MANDATORY / 1225-EDIT-ALPHA-REQD) ---
    # COACTUPC.cbl L1560, L1576 set WS-EDIT-VARIABLE-NAME =
    # 'First Name' / 'Last Name' then PERFORM 1225-EDIT-ALPHA-REQD,
    # which emits ' must be supplied.' on blank and
    # ' can have alphabets only.' on a non-alpha character. The
    # COBOL cascade is blank → non-alpha; we check First Name first,
    # then Last Name, matching the COACTUPC source order. (The
    # middle-name check uses 1235-EDIT-ALPHA-OPT — it's optional so
    # a blank is accepted and only a non-alpha triggers an error.)
    if request.customer_first_name.strip() == "":
        return _field_msg(_FLD_FIRST_NAME, _SFX_MUST_BE_SUPPLIED)
    if request.customer_last_name.strip() == "":
        return _field_msg(_FLD_LAST_NAME, _SFX_MUST_BE_SUPPLIED)

    # --- FICO score (1275-EDIT-FICO-SCORE) -------------------------
    # COACTUPC.cbl L1545 sets WS-EDIT-VARIABLE-NAME = 'FICO Score'
    # then PERFORM 1275-EDIT-FICO-SCORE. The COBOL suffix
    # ': should be between 300 and 850' explicitly has NO trailing
    # period (COACTUPC.cbl L2523). Output:
    #   "FICO Score: should be between 300 and 850"
    if _validate_fico_score(request.customer_fico_score) is None:
        return _field_msg(_FLD_FICO, _SFX_FICO_OUT_OF_RANGE)

    # --- SSN (1265-EDIT-US-SSN) ------------------------------------
    # COACTUPC.cbl L1529 sets WS-EDIT-VARIABLE-NAME = 'SSN' (the
    # parent label) but the sub-paragraph overrides this with its
    # own segment-specific labels before each 1245-EDIT-NUM-REQD
    # call. _validate_us_ssn() returns a fully-templated message
    # using those segment labels.
    ssn_err: str | None = _validate_us_ssn(
        request.customer_ssn_part1,
        request.customer_ssn_part2,
        request.customer_ssn_part3,
    )
    if ssn_err is not None:
        return ssn_err

    # --- State (1270-EDIT-US-STATE-CD) -----------------------------
    # COACTUPC.cbl L1592 sets WS-EDIT-VARIABLE-NAME = 'State' then
    # PERFORM 1270-EDIT-US-STATE-CD. The COBOL suffix
    # ': is not a valid state code' explicitly has NO trailing
    # period (COACTUPC.cbl L2503). Output:
    #   "State: is not a valid state code"
    if not _validate_state_code(request.customer_state_cd):
        return _field_msg(_FLD_STATE, _SFX_IS_NOT_A_VALID_STATE)

    # --- Country (1225-EDIT-ALPHA-REQD) ----------------------------
    # COACTUPC.cbl L1623 sets WS-EDIT-VARIABLE-NAME = 'Country' then
    # PERFORM 1225-EDIT-ALPHA-REQD. The COBOL suffix
    # ' can have alphabets only.' includes a trailing period.
    if request.customer_country_cd.strip() == "":
        return _field_msg(_FLD_COUNTRY, _SFX_MUST_BE_SUPPLIED)
    if not _validate_country_code(request.customer_country_cd):
        return _field_msg(_FLD_COUNTRY, _SFX_CAN_HAVE_ALPHABETS_ONLY)

    # --- ZIP (1280-EDIT-US-STATE-ZIP-CD) ---------------------------
    # COACTUPC.cbl L1605 sets WS-EDIT-VARIABLE-NAME = 'Zip' then
    # PERFORM 1280-EDIT-US-STATE-ZIP-CD. The structural validation
    # (5 digits) is enforced here — the COBOL paragraph additionally
    # cross-references the zip+state against a lookup table; on
    # mismatch it emits the STANDALONE literal
    # 'Invalid zip code for state' (no field-name prefix, no trailing
    # period — COACTUPC.cbl L2550). We preserve the structural check
    # only: a 5-digit non-numeric ZIP falls through to the standalone
    # literal rather than the 1245-style '<Zip> must be all numeric.'
    # to match the COBOL STANDALONE emission pattern.
    if request.customer_zip.strip() == "":
        return _field_msg(_FLD_ZIP, _SFX_MUST_BE_SUPPLIED)
    if not _validate_zip_code(request.customer_zip):
        return _MSG_ZIP_STATE_INVALID

    # --- Phones (1260-EDIT-US-PHONE-NUM, optional) -----------------
    # COACTUPC.cbl L1632 / L1640 set WS-EDIT-VARIABLE-NAME =
    # 'Phone Number 1' / 'Phone Number 2'. The sub-paragraph
    # suffixes start with ': ' (colon-space) so the concatenated
    # output is e.g. "Phone Number 1: Area code must be supplied."
    phone_1_err: str | None = _validate_us_phone(
        _FLD_PHONE_1,
        request.customer_phone_1_area,
        request.customer_phone_1_prefix,
        request.customer_phone_1_line,
    )
    if phone_1_err is not None:
        return phone_1_err
    phone_2_err: str | None = _validate_us_phone(
        _FLD_PHONE_2,
        request.customer_phone_2_area,
        request.customer_phone_2_prefix,
        request.customer_phone_2_line,
    )
    if phone_2_err is not None:
        return phone_2_err

    # --- Primary Card Holder (1220-EDIT-YESNO) ---------------------
    # COACTUPC.cbl L1657-1661 sets WS-EDIT-VARIABLE-NAME =
    # 'Primary Card Holder' then PERFORMs 1220-EDIT-YESNO. The
    # cascade (1220-EDIT-YESNO, L1856-1893) is identical to the
    # active_status cascade at the top of this function:
    #   1. blank/spaces/zeros -> ' must be supplied.'  (L1869)
    #   2. not in {Y, N}      -> ' must be Y or N.'   (L1886)
    # Both suffixes carry a trailing period. The Python cascade
    # places this check AFTER the phone validations to match COBOL
    # source ordering (which runs EFT Account Id -> Primary Card
    # Holder at L1648-1661, AFTER the phone edits at L1632-1646).
    # AAP §0.7.1 requires this validation to execute with the same
    # error-message template as the COBOL source.
    if request.customer_pri_cardholder.strip() == "":
        return _field_msg(_FLD_PRIMARY_CARD_HOLDER, _SFX_MUST_BE_SUPPLIED)
    if not _validate_yes_no(request.customer_pri_cardholder):
        return _field_msg(_FLD_PRIMARY_CARD_HOLDER, _SFX_MUST_BE_Y_OR_N)

    return None


# ---------------------------------------------------------------------------
# Parse helpers: request segments -> canonical storage values
# ---------------------------------------------------------------------------


def _join_date(year: str, month: str, day: str) -> str:
    """Concatenate 3 date segments into 8-char CCYYMMDD (no hyphens).

    Used to build the input for :func:`validate_date_ccyymmdd`, which
    expects 8 contiguous digits.

    Parameters
    ----------
    year, month, day : str
        Zero-padded segments: 4 digits, 2 digits, 2 digits.

    Returns
    -------
    str
        The 8-digit concatenation. No zero-padding is applied here —
        the caller is expected to have submitted already-zero-padded
        values as the BMS map guarantees.
    """
    return f"{year}{month}{day}"


def _format_date(year: str, month: str, day: str) -> str:
    """Format 3 date segments as the canonical storage string CCYY-MM-DD.

    Used to assemble the stored value for open_date, expiration_date,
    reissue_date, dob columns (all ``String(10)`` in the ORM schema
    and ``YYYY-MM-DD`` ISO format in the Aurora PostgreSQL seed data).

    Parameters
    ----------
    year, month, day : str
        Zero-padded segments: 4 digits, 2 digits, 2 digits.

    Returns
    -------
    str
        The 10-character ISO date string.
    """
    return f"{year}-{month}-{day}"


def _parse_date(stored: str) -> tuple[str, str, str]:
    """Parse a stored ``YYYY-MM-DD`` date into 3 segments.

    The inverse of :func:`_format_date`. Used by
    :func:`_build_update_error_response` to echo the stored date
    back to the client in segmented form after an error.

    Parameters
    ----------
    stored : str
        The stored date string; typically 10 characters in
        ``YYYY-MM-DD`` format. Non-conforming inputs return empty
        strings for each segment.

    Returns
    -------
    tuple[str, str, str]
        ``(year, month, day)`` segments. Empty strings if the
        input is malformed.
    """
    parts: list[str] = stored.split("-") if stored else []
    if len(parts) != 3:
        return ("", "", "")
    return (parts[0], parts[1], parts[2])


def _format_ssn_display(raw_ssn: str) -> str:
    """Format a 9-digit raw SSN as the display ``NNN-NN-NNNN`` string.

    Used when assembling the AccountViewResponse customer_ssn field
    from the stored :attr:`Customer.ssn` column (which holds raw
    9-digit digits, no hyphens).

    Parameters
    ----------
    raw_ssn : str
        The raw 9-digit SSN from the database. Non-conforming
        inputs are returned unchanged (defensive — the database
        schema enforces ``nullable=False`` on this column but
        does not enforce digit-only content).

    Returns
    -------
    str
        The 11-character ``NNN-NN-NNNN`` display form, or the
        input unchanged if malformed.
    """
    if len(raw_ssn) != _SSN_STORED_LEN or not _RE_DIGITS_ONLY.match(raw_ssn):
        return raw_ssn
    return f"{raw_ssn[0:3]}-{raw_ssn[3:5]}-{raw_ssn[5:9]}"


def _join_ssn(part1: str, part2: str, part3: str) -> str:
    """Concatenate 3 SSN segments into 9 raw digits (no hyphens).

    Maps to the COBOL REDEFINES clause in CVCUS01Y.cpy where
    ``CUST-SSN`` is declared as ``PIC 9(09)`` with a redefinition
    as ``PIC X(3) + PIC X(2) + PIC X(4)``.

    Parameters
    ----------
    part1, part2, part3 : str
        The 3-2-4 SSN segments.

    Returns
    -------
    str
        The 9-digit concatenation, suitable for writing to the
        :attr:`Customer.ssn` column.
    """
    return f"{part1}{part2}{part3}"


def _format_phone_display(stored: str) -> str:
    """Return the display representation of a stored phone number.

    The :attr:`Customer.phone_num_1` / :attr:`Customer.phone_num_2`
    columns are declared as ``String(15)``; in the seed data they
    contain 13-character ``(NNN)NNN-NNNN`` strings (sometimes with
    trailing padding). This helper normalizes by right-stripping
    trailing whitespace; the display width of 13 is preserved when
    the canonical form is present.

    Parameters
    ----------
    stored : str
        The raw column value from the database.

    Returns
    -------
    str
        The right-trimmed phone string, ready for the response
        ``customer_phone_1`` / ``customer_phone_2`` fields.
    """
    return stored.rstrip() if stored else ""


def _format_phone_stored(area: str, prefix: str, line: str) -> str:
    """Compose the canonical ``(AAA)BBB-CCCC`` stored phone string.

    All-blank input yields an empty string — matching the
    COACTUPC.cbl behavior in which an optional phone field is
    cleared to blanks if none of the three segments is supplied.

    Parameters
    ----------
    area, prefix, line : str
        The three phone segments.

    Returns
    -------
    str
        The 13-character stored form, or the empty string if all
        three segments are blank.
    """
    if area == "" and prefix == "" and line == "":
        return ""
    return f"({area}){prefix}-{line}"


def _parse_phone_display(stored: str) -> tuple[str, str, str]:
    """Parse a stored ``(AAA)BBB-CCCC`` phone string into 3 segments.

    Inverse of :func:`_format_phone_stored`. Used by
    :func:`_build_update_error_response` to echo the current
    phone back to the client in segmented form. Returns empty
    strings on malformed input (defensive).

    Parameters
    ----------
    stored : str
        The stored phone string.

    Returns
    -------
    tuple[str, str, str]
        ``(area, prefix, line)`` segments, or three empty strings
        if the input is malformed.
    """
    trimmed: str = stored.rstrip() if stored else ""
    # Expected format ``(AAA)BBB-CCCC`` — 13 chars with specific punctuation.
    if len(trimmed) != _PHONE_DISPLAY_LEN:
        return ("", "", "")
    if trimmed[0] != "(" or trimmed[4] != ")" or trimmed[8] != "-":
        return ("", "", "")
    area: str = trimmed[1:4]
    prefix: str = trimmed[5:8]
    line: str = trimmed[9:13]
    return (area, prefix, line)


def _format_fico(score: int) -> str:
    """Format an integer FICO score as a 3-char zero-padded string.

    Used when assembling the :attr:`AccountViewResponse.customer_fico_score`
    field from the integer :attr:`Customer.fico_credit_score` column.
    Values below 0 are clamped to ``"000"``; values above 999 are
    clamped to ``"999"``. In normal operation the score is 0-850,
    but the clamping is defensive against corrupted stored values.

    Parameters
    ----------
    score : int
        The FICO score from the database.

    Returns
    -------
    str
        A 3-character zero-padded representation.
    """
    clamped: int = max(0, min(999, score))
    return str(clamped).zfill(_FICO_LEN)


# ---------------------------------------------------------------------------
# Partial-update fill helper (QA Checkpoint 5, Finding #5)
# ---------------------------------------------------------------------------


def _fill_missing_from_existing(
    request: AccountUpdateRequest,
    account: Account,
    customer: Customer,
) -> AccountUpdateRequest:
    """Populate any ``None`` fields on the request from stored records.

    The original COBOL contract (COACTUPC.cbl) required every BMS
    field to be populated on the update screen — the map was
    initially primed with the current DB values, the user edited
    fields in place, and the submitted map therefore always carried
    a full set of values. The REST modernisation preserves that
    semantic for callers who send a full payload, but also supports
    modern REST partial-update conventions: QA Checkpoint 5,
    Finding #5, reported that forcing clients to submit all 38
    non-id fields is impractical. To support partial updates without
    weakening the downstream validator or parser contracts, this
    helper decomposes the currently-stored :class:`Account` and
    :class:`Customer` records back into the BMS-style segmented
    request form and substitutes any ``None`` value on the incoming
    request with the stored equivalent.

    After this helper runs, the returned :class:`AccountUpdateRequest`
    is guaranteed to have every field populated with a non-``None``
    value — either the caller-supplied override or the stored
    current value. Downstream code
    (:func:`_validate_request`, :func:`_parse_request`,
    :func:`_detect_changes`, :func:`_apply_account_mutations`,
    :func:`_apply_customer_mutations`) is therefore unchanged and
    sees the same shape it always did.

    Decomposition matrix (stored form → BMS segments):

    * ``account.open_date`` (``YYYY-MM-DD``) → 3 segments via
      :func:`_parse_date`.
    * ``account.expiration_date`` (``YYYY-MM-DD``) → 3 segments via
      :func:`_parse_date`.
    * ``account.reissue_date`` (``YYYY-MM-DD``) → 3 segments via
      :func:`_parse_date`.
    * ``customer.dob`` (``YYYY-MM-DD``) → 3 segments via
      :func:`_parse_date`.
    * ``customer.ssn`` (9 raw digits) → 3 segments via slicing
      (``[0:3]``, ``[3:5]``, ``[5:9]``) — the inverse of
      :func:`_join_ssn`.
    * ``customer.phone_num_1`` / ``phone_num_2`` (``(AAA)BBB-CCCC``)
      → 3 segments via :func:`_parse_phone_display`.
    * ``customer.fico_credit_score`` (int) → 3-char zero-padded
      string via :func:`_format_fico`.
    * Every other scalar stored field is copied directly
      (account.active_status, account.credit_limit,
      account.cash_credit_limit, account.group_id,
      customer.first_name, customer.middle_name, customer.last_name,
      customer.addr_line_1, customer.addr_line_2, customer.addr_line_3
      (city), customer.state_cd, customer.country_cd,
      customer.addr_zip (truncated to 5 chars), customer.govt_issued_id,
      customer.eft_account_id, customer.pri_card_holder_ind).

    Parameters
    ----------
    request : AccountUpdateRequest
        The original caller-supplied request, potentially with
        any of the 38 non-id fields set to ``None``.
    account : Account
        The currently-stored Account ORM entity, already read by
        the ``update_account`` flow before this helper is invoked.
    customer : Customer
        The currently-stored Customer ORM entity, already read by
        the ``update_account`` flow before this helper is invoked.

    Returns
    -------
    AccountUpdateRequest
        A new request instance with every ``None`` field replaced
        by the corresponding stored value. Caller-supplied non-
        ``None`` fields are preserved unchanged. The returned
        instance is constructed via Pydantic ``model_copy(update=...)``
        which re-runs field validation but NOT the ``@field_validator``
        methods — those run on the original construction. Downstream
        :func:`_validate_request` still executes the full content
        validation cascade on the merged request.

    Notes
    -----
    This helper is called exactly once in :meth:`update_account`
    between Step 1 (read existing state) and Step 2
    (``_validate_request``). It is a no-op for callers who submit
    a full payload: every ``None`` check short-circuits to the
    caller-supplied value. For callers submitting a partial update
    (e.g. ``{"account_id": "...", "credit_limit": "7500.00"}``) the
    37 missing fields are filled from the stored records before
    validation, so the COBOL field-by-field validation cascade
    continues to run on a fully-populated view of the "new" state.
    Unchanged fields will therefore pass validation (they were
    valid when stored) and also fail change detection
    (:func:`_detect_changes`), so a partial update with only
    ``credit_limit`` supplied will correctly result in a single
    field mutation while all other fields remain untouched.
    """
    # ------------------------------------------------------------------
    # Account fields — direct scalars and date decompositions
    # ------------------------------------------------------------------
    open_year, open_month, open_day = _parse_date(account.open_date)
    expiry_year, expiry_month, expiry_day = _parse_date(account.expiration_date)
    reissue_year, reissue_month, reissue_day = _parse_date(account.reissue_date)

    # ------------------------------------------------------------------
    # Customer fields — SSN decomposition, date decomposition,
    # phone decomposition, FICO formatting, and string trims
    # ------------------------------------------------------------------
    dob_year, dob_month, dob_day = _parse_date(customer.dob)

    # SSN is stored as 9 raw digits (no hyphens); decompose into
    # the 3-2-4 BMS segments.
    stored_ssn: str = customer.ssn or ""
    if len(stored_ssn) >= 9:
        stored_ssn_part1 = stored_ssn[0:3]
        stored_ssn_part2 = stored_ssn[3:5]
        stored_ssn_part3 = stored_ssn[5:9]
    else:
        # Defensive: should never occur (the ORM column is
        # nullable=False), but if the stored SSN is short we
        # emit empty segments so validation picks it up as a
        # specific error rather than crashing in slicing.
        stored_ssn_part1 = ""
        stored_ssn_part2 = ""
        stored_ssn_part3 = ""

    phone1_area, phone1_prefix, phone1_line = _parse_phone_display(customer.phone_num_1 or "")
    phone2_area, phone2_prefix, phone2_line = _parse_phone_display(customer.phone_num_2 or "")

    stored_fico: str = _format_fico(customer.fico_credit_score)

    # ``customer.addr_zip`` is declared as VARCHAR(10) (preserves
    # possible 5+4 encoding) but the BMS / response field is
    # always the 5-char base ZIP, so truncate defensively.
    stored_zip: str = customer.addr_zip[:_ZIP_LEN] if customer.addr_zip else ""

    # ``customer.addr_line_3`` carries the city per CVCUS01Y.cpy
    # REDEFINES (see _parse_request and _assemble_view_response).
    stored_city: str = customer.addr_line_3 or ""

    # Customer state/country are stored uppercase; the BMS layer
    # also uppercases caller input, so no case transformation
    # is needed here.
    stored_state_cd: str = customer.state_cd or ""
    stored_country_cd: str = customer.country_cd or ""

    # Name, address, govt_id, eft_account_id fields on the
    # Customer entity are declared as fixed-width CHAR columns
    # (PostgreSQL CHAR preserves trailing-space padding). The
    # downstream validator trims via ``.rstrip()`` / ``.strip()``,
    # so we pass the stored values through unchanged and let
    # the existing cascade handle trimming.
    stored_first_name: str = customer.first_name or ""
    stored_middle_name: str = customer.middle_name or ""
    stored_last_name: str = customer.last_name or ""
    stored_addr_line_1: str = customer.addr_line_1 or ""
    stored_addr_line_2: str = customer.addr_line_2 or ""
    stored_govt_id: str = customer.govt_issued_id or ""
    stored_eft_acct: str = customer.eft_account_id or ""
    stored_pri_cardholder: str = customer.pri_card_holder_ind or ""

    # ------------------------------------------------------------------
    # Build the merged request: caller value if supplied, else
    # stored equivalent. Monetary fields are passed through
    # ``safe_decimal`` to guarantee proper scale — this mirrors
    # the handling in ``_assemble_view_response`` where stored
    # values are re-scaled before emission.
    # ------------------------------------------------------------------
    updates: dict[str, object] = {
        # Account scalars
        "active_status": (request.active_status if request.active_status is not None else account.active_status),
        "open_date_year": (request.open_date_year if request.open_date_year is not None else open_year),
        "open_date_month": (request.open_date_month if request.open_date_month is not None else open_month),
        "open_date_day": (request.open_date_day if request.open_date_day is not None else open_day),
        "credit_limit": (
            request.credit_limit if request.credit_limit is not None else safe_decimal(account.credit_limit)
        ),
        "expiration_date_year": (
            request.expiration_date_year if request.expiration_date_year is not None else expiry_year
        ),
        "expiration_date_month": (
            request.expiration_date_month if request.expiration_date_month is not None else expiry_month
        ),
        "expiration_date_day": (request.expiration_date_day if request.expiration_date_day is not None else expiry_day),
        "cash_credit_limit": (
            request.cash_credit_limit
            if request.cash_credit_limit is not None
            else safe_decimal(account.cash_credit_limit)
        ),
        "reissue_date_year": (request.reissue_date_year if request.reissue_date_year is not None else reissue_year),
        "reissue_date_month": (request.reissue_date_month if request.reissue_date_month is not None else reissue_month),
        "reissue_date_day": (request.reissue_date_day if request.reissue_date_day is not None else reissue_day),
        "group_id": (request.group_id if request.group_id is not None else account.group_id),
        # Customer SSN segments
        "customer_ssn_part1": (
            request.customer_ssn_part1 if request.customer_ssn_part1 is not None else stored_ssn_part1
        ),
        "customer_ssn_part2": (
            request.customer_ssn_part2 if request.customer_ssn_part2 is not None else stored_ssn_part2
        ),
        "customer_ssn_part3": (
            request.customer_ssn_part3 if request.customer_ssn_part3 is not None else stored_ssn_part3
        ),
        # Customer DOB segments
        "customer_dob_year": (request.customer_dob_year if request.customer_dob_year is not None else dob_year),
        "customer_dob_month": (request.customer_dob_month if request.customer_dob_month is not None else dob_month),
        "customer_dob_day": (request.customer_dob_day if request.customer_dob_day is not None else dob_day),
        # Customer scalars
        "customer_fico_score": (
            request.customer_fico_score if request.customer_fico_score is not None else stored_fico
        ),
        "customer_first_name": (
            request.customer_first_name if request.customer_first_name is not None else stored_first_name
        ),
        "customer_middle_name": (
            request.customer_middle_name if request.customer_middle_name is not None else stored_middle_name
        ),
        "customer_last_name": (
            request.customer_last_name if request.customer_last_name is not None else stored_last_name
        ),
        "customer_addr_line_1": (
            request.customer_addr_line_1 if request.customer_addr_line_1 is not None else stored_addr_line_1
        ),
        "customer_state_cd": (request.customer_state_cd if request.customer_state_cd is not None else stored_state_cd),
        "customer_addr_line_2": (
            request.customer_addr_line_2 if request.customer_addr_line_2 is not None else stored_addr_line_2
        ),
        "customer_zip": (request.customer_zip if request.customer_zip is not None else stored_zip),
        "customer_city": (request.customer_city if request.customer_city is not None else stored_city),
        "customer_country_cd": (
            request.customer_country_cd if request.customer_country_cd is not None else stored_country_cd
        ),
        # Primary phone segments
        "customer_phone_1_area": (
            request.customer_phone_1_area if request.customer_phone_1_area is not None else phone1_area
        ),
        "customer_phone_1_prefix": (
            request.customer_phone_1_prefix if request.customer_phone_1_prefix is not None else phone1_prefix
        ),
        "customer_phone_1_line": (
            request.customer_phone_1_line if request.customer_phone_1_line is not None else phone1_line
        ),
        # Government ID
        "customer_govt_id": (request.customer_govt_id if request.customer_govt_id is not None else stored_govt_id),
        # Secondary phone segments
        "customer_phone_2_area": (
            request.customer_phone_2_area if request.customer_phone_2_area is not None else phone2_area
        ),
        "customer_phone_2_prefix": (
            request.customer_phone_2_prefix if request.customer_phone_2_prefix is not None else phone2_prefix
        ),
        "customer_phone_2_line": (
            request.customer_phone_2_line if request.customer_phone_2_line is not None else phone2_line
        ),
        # EFT account + primary cardholder flag
        "customer_eft_account_id": (
            request.customer_eft_account_id if request.customer_eft_account_id is not None else stored_eft_acct
        ),
        "customer_pri_cardholder": (
            request.customer_pri_cardholder if request.customer_pri_cardholder is not None else stored_pri_cardholder
        ),
    }

    # model_copy(update=...) returns a new instance with the
    # specified fields replaced. We pass ``deep=False`` (the
    # default) because we only replace top-level scalar fields;
    # no nested structures require deep-copy semantics.
    return request.model_copy(update=updates)


# ---------------------------------------------------------------------------
# Request parsing
# ---------------------------------------------------------------------------


def _parse_request(request: AccountUpdateRequest) -> _ParsedRequest:
    """Convert an :class:`AccountUpdateRequest` into canonical storage form.

    Precondition: :func:`_validate_request` has already returned
    ``None`` for this request, i.e. all fields have passed content
    validation. If this function is invoked on an invalid request
    it may raise :class:`ValueError` from the FICO parse.

    Parameters
    ----------
    request : AccountUpdateRequest
        The validated request payload.

    Returns
    -------
    _ParsedRequest
        A populated :class:`_ParsedRequest` instance.

    Raises
    ------
    ValueError
        If the FICO score is not a valid integer after validation
        (defensive — should not occur after :func:`_validate_request`).
    """
    # --- Non-None invariant (QA Checkpoint 5 Finding #5 fix) -------
    # Same invariant as :func:`_validate_request`:
    # :func:`_fill_missing_from_existing` has already run at
    # :meth:`AccountService.update_account` (line 1251) and guarantees
    # every Optional field is populated with a non-``None`` value. The
    # assertions below serve as both runtime safety nets and mypy
    # type-narrowing markers so the downstream ``.rstrip()`` /
    # ``.strip()`` / ``.upper()`` / ``_format_date(...)`` /
    # ``round_financial(...)`` / ``_join_ssn(...)`` /
    # ``_format_phone_stored(...)`` calls do not trigger a union-attr
    # or arg-type error.
    assert request.active_status is not None, (
        "invariant violation: _fill_missing_from_existing must populate active_status"
    )
    assert request.open_date_year is not None, (
        "invariant violation: _fill_missing_from_existing must populate open_date_year"
    )
    assert request.open_date_month is not None, (
        "invariant violation: _fill_missing_from_existing must populate open_date_month"
    )
    assert request.open_date_day is not None, (
        "invariant violation: _fill_missing_from_existing must populate open_date_day"
    )
    assert request.credit_limit is not None, (
        "invariant violation: _fill_missing_from_existing must populate credit_limit"
    )
    assert request.expiration_date_year is not None, (
        "invariant violation: _fill_missing_from_existing must populate expiration_date_year"
    )
    assert request.expiration_date_month is not None, (
        "invariant violation: _fill_missing_from_existing must populate expiration_date_month"
    )
    assert request.expiration_date_day is not None, (
        "invariant violation: _fill_missing_from_existing must populate expiration_date_day"
    )
    assert request.cash_credit_limit is not None, (
        "invariant violation: _fill_missing_from_existing must populate cash_credit_limit"
    )
    assert request.reissue_date_year is not None, (
        "invariant violation: _fill_missing_from_existing must populate reissue_date_year"
    )
    assert request.reissue_date_month is not None, (
        "invariant violation: _fill_missing_from_existing must populate reissue_date_month"
    )
    assert request.reissue_date_day is not None, (
        "invariant violation: _fill_missing_from_existing must populate reissue_date_day"
    )
    assert request.group_id is not None, "invariant violation: _fill_missing_from_existing must populate group_id"
    assert request.customer_ssn_part1 is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_ssn_part1"
    )
    assert request.customer_ssn_part2 is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_ssn_part2"
    )
    assert request.customer_ssn_part3 is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_ssn_part3"
    )
    assert request.customer_dob_year is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_dob_year"
    )
    assert request.customer_dob_month is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_dob_month"
    )
    assert request.customer_dob_day is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_dob_day"
    )
    assert request.customer_fico_score is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_fico_score"
    )
    assert request.customer_first_name is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_first_name"
    )
    assert request.customer_middle_name is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_middle_name"
    )
    assert request.customer_last_name is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_last_name"
    )
    assert request.customer_addr_line_1 is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_addr_line_1"
    )
    assert request.customer_addr_line_2 is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_addr_line_2"
    )
    assert request.customer_city is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_city"
    )
    assert request.customer_state_cd is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_state_cd"
    )
    assert request.customer_country_cd is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_country_cd"
    )
    assert request.customer_zip is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_zip"
    )
    assert request.customer_phone_1_area is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_phone_1_area"
    )
    assert request.customer_phone_1_prefix is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_phone_1_prefix"
    )
    assert request.customer_phone_1_line is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_phone_1_line"
    )
    assert request.customer_phone_2_area is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_phone_2_area"
    )
    assert request.customer_phone_2_prefix is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_phone_2_prefix"
    )
    assert request.customer_phone_2_line is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_phone_2_line"
    )
    assert request.customer_govt_id is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_govt_id"
    )
    assert request.customer_eft_account_id is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_eft_account_id"
    )
    assert request.customer_pri_cardholder is not None, (
        "invariant violation: _fill_missing_from_existing must populate customer_pri_cardholder"
    )

    parsed: _ParsedRequest = _ParsedRequest()

    # Account fields
    parsed.active_status = request.active_status
    parsed.open_date = _format_date(
        request.open_date_year,
        request.open_date_month,
        request.open_date_day,
    )
    parsed.credit_limit = round_financial(request.credit_limit)
    parsed.expiration_date = _format_date(
        request.expiration_date_year,
        request.expiration_date_month,
        request.expiration_date_day,
    )
    parsed.cash_credit_limit = round_financial(request.cash_credit_limit)
    parsed.reissue_date = _format_date(
        request.reissue_date_year,
        request.reissue_date_month,
        request.reissue_date_day,
    )
    parsed.group_id = request.group_id.strip()

    # Customer fields
    parsed.ssn = _join_ssn(
        request.customer_ssn_part1,
        request.customer_ssn_part2,
        request.customer_ssn_part3,
    )
    parsed.dob = _format_date(
        request.customer_dob_year,
        request.customer_dob_month,
        request.customer_dob_day,
    )
    fico_parsed: int | None = _validate_fico_score(request.customer_fico_score)
    if fico_parsed is None:
        raise ValueError("FICO score failed re-validation during parse")
    parsed.fico_credit_score = fico_parsed

    parsed.first_name = request.customer_first_name.rstrip()
    parsed.middle_name = request.customer_middle_name.rstrip()
    parsed.last_name = request.customer_last_name.rstrip()
    parsed.addr_line_1 = request.customer_addr_line_1.rstrip()
    parsed.addr_line_2 = request.customer_addr_line_2.rstrip()
    # CVCUS01Y.cpy reuses ``CUST-ADDR-LINE-3`` for the city name,
    # matching the BMS layout: the third address line is the city.
    parsed.addr_line_3 = request.customer_city.rstrip()
    parsed.state_cd = request.customer_state_cd.upper()
    parsed.country_cd = request.customer_country_cd.upper()
    parsed.addr_zip = request.customer_zip
    parsed.phone_num_1 = _format_phone_stored(
        request.customer_phone_1_area,
        request.customer_phone_1_prefix,
        request.customer_phone_1_line,
    )
    parsed.phone_num_2 = _format_phone_stored(
        request.customer_phone_2_area,
        request.customer_phone_2_prefix,
        request.customer_phone_2_line,
    )
    parsed.govt_issued_id = request.customer_govt_id.rstrip()
    parsed.eft_account_id = request.customer_eft_account_id.rstrip()
    parsed.pri_card_holder_ind = request.customer_pri_cardholder
    return parsed


# ---------------------------------------------------------------------------
# Change detection (COACTUPC.cbl 1205-COMPARE-OLD-NEW)
# ---------------------------------------------------------------------------


def _detect_changes(account: Account, customer: Customer, parsed: _ParsedRequest) -> bool:
    """Return ``True`` if any Account or Customer field differs from the request.

    Maps to the COBOL ``1205-COMPARE-OLD-NEW`` paragraph at
    COACTUPC.cbl line 1681. That paragraph compares each of the
    ~30 field pairs and sets the ``CHANGE-HAS-OCCURRED`` flag if
    any pair mismatches. We replicate this by comparing the parsed
    (canonical) request values against the current entity
    attribute values — a mismatch on any single field triggers
    ``True`` (= must write).

    The comparison is done in canonical form (already stripped,
    normalized casing for state/country, Decimal-scaled for money)
    so there are no false positives from display-layer format drift.

    Parameters
    ----------
    account : Account
        The current Account row from the database.
    customer : Customer
        The current Customer row from the database.
    parsed : _ParsedRequest
        The canonical request values.

    Returns
    -------
    bool
        ``True`` if any field has changed, ``False`` if the
        request is a no-op (matches the current state exactly).
    """
    # Account comparisons. All fields that the update request can
    # modify must be compared. The view-only fields (curr_bal,
    # curr_cyc_credit, curr_cyc_debit) are excluded because the
    # update request has no corresponding inputs for them.
    if account.active_status != parsed.active_status:
        return True
    if account.open_date != parsed.open_date:
        return True
    if round_financial(account.credit_limit) != parsed.credit_limit:
        return True
    if account.expiration_date != parsed.expiration_date:
        return True
    if round_financial(account.cash_credit_limit) != parsed.cash_credit_limit:
        return True
    if account.reissue_date != parsed.reissue_date:
        return True
    if account.group_id != parsed.group_id:
        return True

    # Customer comparisons.
    if customer.ssn != parsed.ssn:
        return True
    if customer.dob != parsed.dob:
        return True
    if customer.fico_credit_score != parsed.fico_credit_score:
        return True
    if customer.first_name.rstrip() != parsed.first_name:
        return True
    if customer.middle_name.rstrip() != parsed.middle_name:
        return True
    if customer.last_name.rstrip() != parsed.last_name:
        return True
    if customer.addr_line_1.rstrip() != parsed.addr_line_1:
        return True
    if customer.addr_line_2.rstrip() != parsed.addr_line_2:
        return True
    if customer.addr_line_3.rstrip() != parsed.addr_line_3:
        return True
    if customer.state_cd != parsed.state_cd:
        return True
    if customer.country_cd != parsed.country_cd:
        return True
    if customer.addr_zip != parsed.addr_zip:
        return True
    if _format_phone_display(customer.phone_num_1) != parsed.phone_num_1:
        return True
    if _format_phone_display(customer.phone_num_2) != parsed.phone_num_2:
        return True
    if customer.govt_issued_id.rstrip() != parsed.govt_issued_id:
        return True
    if customer.eft_account_id.rstrip() != parsed.eft_account_id:
        return True
    if customer.pri_card_holder_ind != parsed.pri_card_holder_ind:
        return True

    return False


# ---------------------------------------------------------------------------
# Attribute mutation helpers (map parsed request onto the ORM entities)
# ---------------------------------------------------------------------------


def _apply_account_mutations(account: Account, parsed: _ParsedRequest) -> None:
    """Apply the updatable request fields to the Account ORM instance.

    Maps to the COBOL ``9200-WRITE-PROCESSING`` paragraph which
    assembles ACCT-UPDATE-RECORD from the WS-EDITED-* working-storage
    fields prior to ``EXEC CICS REWRITE FILE('ACCTDAT')``. In
    SQLAlchemy we simply assign attributes; the session tracks the
    dirty state and emits UPDATE at flush time.

    Note the absence of ``version_id`` — SQLAlchemy increments it
    automatically under the ``__mapper_args__["version_id_col"]``
    configuration.

    Parameters
    ----------
    account : Account
        The Account instance loaded from the session (state:
        persistent).
    parsed : _ParsedRequest
        The canonical request values.
    """
    account.active_status = parsed.active_status
    account.open_date = parsed.open_date
    account.credit_limit = parsed.credit_limit
    account.expiration_date = parsed.expiration_date
    account.cash_credit_limit = parsed.cash_credit_limit
    account.reissue_date = parsed.reissue_date
    account.group_id = parsed.group_id
    # Propagate the customer's mailing ZIP onto the Account record so
    # ``ACCT-ADDR-ZIP`` stays aligned with ``CUST-ADDR-ZIP``. In the
    # legacy VSAM layout these were independent physical fields but
    # the COBOL online flow only ever writes a single ZIP value from
    # the BMS map into both datasets.
    account.addr_zip = parsed.addr_zip


def _apply_customer_mutations(customer: Customer, parsed: _ParsedRequest) -> None:
    """Apply the updatable request fields to the Customer ORM instance.

    Maps to the COBOL ``9300-WRITE-PROCESSING`` paragraph which
    assembles CUST-UPDATE-RECORD prior to ``EXEC CICS REWRITE
    FILE('CUSTDAT')``. See :func:`_apply_account_mutations` for
    the session-tracking semantics.

    Unlike the Account entity, the Customer entity does NOT carry
    its own ``version_id`` column. This is intentional: Customer
    records are only updated via the Account Update flow (F-005),
    and the Account's version_id provides sufficient
    optimistic-concurrency coverage for the dual-write pattern.

    Parameters
    ----------
    customer : Customer
        The Customer instance loaded from the session (state:
        persistent).
    parsed : _ParsedRequest
        The canonical request values.
    """
    customer.first_name = parsed.first_name
    customer.middle_name = parsed.middle_name
    customer.last_name = parsed.last_name
    customer.addr_line_1 = parsed.addr_line_1
    customer.addr_line_2 = parsed.addr_line_2
    # ``addr_line_3`` is the city per BMS map semantics.
    customer.addr_line_3 = parsed.addr_line_3
    customer.state_cd = parsed.state_cd
    customer.country_cd = parsed.country_cd
    customer.addr_zip = parsed.addr_zip
    customer.phone_num_1 = parsed.phone_num_1
    customer.phone_num_2 = parsed.phone_num_2
    customer.ssn = parsed.ssn
    customer.govt_issued_id = parsed.govt_issued_id
    customer.dob = parsed.dob
    customer.eft_account_id = parsed.eft_account_id
    customer.pri_card_holder_ind = parsed.pri_card_holder_ind
    customer.fico_credit_score = parsed.fico_credit_score


# ---------------------------------------------------------------------------
# Response assembly (Account + Customer -> View / Update response)
# ---------------------------------------------------------------------------


def _assemble_view_response(
    account: Account,
    customer: Customer,
    *,
    info_message: str | None,
    error_message: str | None,
) -> AccountViewResponse:
    """Build a fully-populated :class:`AccountViewResponse` from entities.

    Maps to the COBOL ``1100-PROCESS-INPUTS`` paragraph which
    moves each record field into the BMS map output area. The
    SSN is reformatted from raw digits to ``NNN-NN-NNNN``; the
    phones are right-trimmed; FICO is zero-padded to 3 chars.

    All monetary values are explicitly re-scaled via
    :func:`safe_decimal` before being passed to the Pydantic
    validator to guarantee 2-decimal-place precision.

    Parameters
    ----------
    account : Account
        The loaded Account instance.
    customer : Customer
        The loaded Customer instance.
    info_message : str | None
        Advisory message to surface on the response (45 chars
        max per ``_INFO_MSG_LEN``).
    error_message : str | None
        Error message to surface on the response (78 chars max
        per ``_ERR_MSG_LEN``).

    Returns
    -------
    AccountViewResponse
        A fully-populated response object.
    """
    return AccountViewResponse(
        account_id=account.acct_id,
        active_status=account.active_status,
        open_date=account.open_date,
        credit_limit=safe_decimal(account.credit_limit),
        expiration_date=account.expiration_date,
        cash_credit_limit=safe_decimal(account.cash_credit_limit),
        reissue_date=account.reissue_date,
        current_balance=safe_decimal(account.curr_bal),
        current_cycle_credit=safe_decimal(account.curr_cyc_credit),
        group_id=account.group_id,
        current_cycle_debit=safe_decimal(account.curr_cyc_debit),
        customer_id=customer.cust_id,
        customer_ssn=_format_ssn_display(customer.ssn),
        customer_dob=customer.dob,
        customer_fico_score=_format_fico(customer.fico_credit_score),
        customer_first_name=customer.first_name,
        customer_middle_name=customer.middle_name,
        customer_last_name=customer.last_name,
        customer_addr_line_1=customer.addr_line_1,
        customer_state_cd=customer.state_cd,
        customer_addr_line_2=customer.addr_line_2,
        customer_zip=(customer.addr_zip[:_ZIP_LEN] if customer.addr_zip else ""),
        # ``addr_line_3`` carries the city per the BMS map layout.
        customer_city=customer.addr_line_3,
        customer_country_cd=customer.country_cd,
        customer_phone_1=_format_phone_display(customer.phone_num_1),
        customer_govt_id=customer.govt_issued_id,
        customer_phone_2=_format_phone_display(customer.phone_num_2),
        customer_eft_account_id=customer.eft_account_id,
        customer_pri_cardholder=customer.pri_card_holder_ind,
        info_message=info_message,
        error_message=error_message,
    )


def _assemble_update_response(
    account: Account,
    customer: Customer,
    *,
    info_message: str | None,
    error_message: str | None = None,
) -> AccountUpdateResponse:
    """Build a fully-populated :class:`AccountUpdateResponse` from entities.

    :class:`AccountUpdateResponse` extends :class:`AccountViewResponse`
    with no additional fields — it is a marker subclass so the
    OpenAPI schema has a distinct name for PUT /accounts/{id}
    responses. We assemble it using the same field-mapping logic
    as the view response.

    Parameters
    ----------
    account : Account
    customer : Customer
    info_message : str | None
    error_message : str | None

    Returns
    -------
    AccountUpdateResponse
        A fully-populated update response object.
    """
    # Re-use the view-response assembly and cast (via
    # ``model_validate``) into the Update response type. The
    # AccountUpdateResponse class body is empty — it inherits
    # every field from AccountViewResponse — so the .model_dump()
    # payload is directly usable as kwargs.
    return AccountUpdateResponse(
        account_id=account.acct_id,
        active_status=account.active_status,
        open_date=account.open_date,
        credit_limit=safe_decimal(account.credit_limit),
        expiration_date=account.expiration_date,
        cash_credit_limit=safe_decimal(account.cash_credit_limit),
        reissue_date=account.reissue_date,
        current_balance=safe_decimal(account.curr_bal),
        current_cycle_credit=safe_decimal(account.curr_cyc_credit),
        group_id=account.group_id,
        current_cycle_debit=safe_decimal(account.curr_cyc_debit),
        customer_id=customer.cust_id,
        customer_ssn=_format_ssn_display(customer.ssn),
        customer_dob=customer.dob,
        customer_fico_score=_format_fico(customer.fico_credit_score),
        customer_first_name=customer.first_name,
        customer_middle_name=customer.middle_name,
        customer_last_name=customer.last_name,
        customer_addr_line_1=customer.addr_line_1,
        customer_state_cd=customer.state_cd,
        customer_addr_line_2=customer.addr_line_2,
        customer_zip=(customer.addr_zip[:_ZIP_LEN] if customer.addr_zip else ""),
        customer_city=customer.addr_line_3,
        customer_country_cd=customer.country_cd,
        customer_phone_1=_format_phone_display(customer.phone_num_1),
        customer_govt_id=customer.govt_issued_id,
        customer_phone_2=_format_phone_display(customer.phone_num_2),
        customer_eft_account_id=customer.eft_account_id,
        customer_pri_cardholder=customer.pri_card_holder_ind,
        info_message=info_message,
        error_message=error_message,
    )


# ---------------------------------------------------------------------------
# Error-response builders (no ORM entity available)
# ---------------------------------------------------------------------------


def _build_view_error_response(account_id: str, error_message: str) -> AccountViewResponse:
    """Build an AccountViewResponse carrying an error message but no data.

    Used when the Account View flow cannot retrieve a full entity
    chain (missing xref, missing account, missing customer,
    validation failure). Every data field is set to a neutral
    default so the Pydantic validators pass; the caller can rely
    on the ``error_message`` field to surface the COBOL error to
    the user.

    Parameters
    ----------
    account_id : str
        The (possibly invalid) account identifier from the
        request — echoed back to the client so the UI can display
        it in the "account number" field.
    error_message : str
        The COBOL error-message string to surface.

    Returns
    -------
    AccountViewResponse
        A skeleton response with blanked data fields.
    """
    # Use non-empty but obviously-blank values that pass the
    # Pydantic validators. account_id has a length validator that
    # requires 11 digits, so we pad with zeros if the supplied
    # value doesn't fit. The monetary fields are all Decimal("0.00").
    safe_acct_id: str = account_id if _validate_account_id(account_id) else "0" * _ACCT_ID_LEN
    zero: Decimal = safe_decimal(Decimal("0"))
    return AccountViewResponse(
        account_id=safe_acct_id,
        active_status="",
        open_date="",
        credit_limit=zero,
        expiration_date="",
        cash_credit_limit=zero,
        reissue_date="",
        current_balance=zero,
        current_cycle_credit=zero,
        group_id="",
        current_cycle_debit=zero,
        customer_id="",
        customer_ssn="",
        customer_dob="",
        customer_fico_score="",
        customer_first_name="",
        customer_middle_name="",
        customer_last_name="",
        customer_addr_line_1="",
        customer_state_cd="",
        customer_addr_line_2="",
        customer_zip="",
        customer_city="",
        customer_country_cd="",
        customer_phone_1="",
        customer_govt_id="",
        customer_phone_2="",
        customer_eft_account_id="",
        customer_pri_cardholder="",
        info_message=None,
        error_message=error_message,
    )


def _build_update_error_response(
    account_id: str,
    request: AccountUpdateRequest,
    error_message: str,
) -> AccountUpdateResponse:
    """Build an AccountUpdateResponse echoing the request fields with an error.

    Used when the Account Update flow cannot commit a change
    (validation failure, not-found, stale-data, rollback). We
    echo the user's submitted values back rather than blanking
    them so the client can re-display the form pre-filled with
    the values the user typed — matching the COBOL behaviour of
    re-displaying the BMS map with the last-submitted input and
    the error message in red.

    All monetary values are re-scaled via :func:`safe_decimal` to
    guarantee 2-decimal-place precision on the response.

    Parameters
    ----------
    account_id : str
        The (possibly invalid) account identifier from the URL path.
    request : AccountUpdateRequest
        The original request payload to echo back.
    error_message : str
        The COBOL error-message string to surface.

    Returns
    -------
    AccountUpdateResponse
        A response carrying the echoed request fields plus the
        error message.
    """
    # Account ID: prefer the URL-path value when valid; fall back
    # to the body's value when the URL-path value is not valid;
    # finally zero-pad to 11 digits as a last resort so Pydantic's
    # validator does not reject the response payload.
    safe_acct_id: str
    if _validate_account_id(account_id):
        safe_acct_id = account_id
    elif request.account_id and _validate_account_id(request.account_id.strip()):
        safe_acct_id = request.account_id.strip()
    else:
        safe_acct_id = "0" * _ACCT_ID_LEN

    # --- Optional field defensive defaults (QA Checkpoint 5 #5) ----
    # This error-response builder is invoked from TWO distinct call
    # contexts in :meth:`AccountService.update_account`:
    #
    #   (a) Pre-fill paths (lines ~1106/1116/1144/1171/1180/1198/
    #       1207/1225/1234) — invoked BEFORE
    #       :func:`_fill_missing_from_existing`. Here the request
    #       may still carry ``None`` on any Optional field because
    #       the caller submitted a partial update and the stored
    #       record was never read (e.g. acct_id missing/invalid, or
    #       the 3-entity xref/Account/Customer read failed). In this
    #       mode we must provide a safe, well-formed default for the
    #       response so Pydantic does not reject the payload.
    #
    #   (b) Post-fill paths (lines ~1268/1303/1363/1379/1397) —
    #       invoked AFTER :func:`_fill_missing_from_existing`. Here
    #       every field is guaranteed non-``None`` by construction.
    #
    # Because (a) must not crash on ``None`` and (b) must not lose
    # the caller-filled values, each Optional field is extracted into
    # a local with a safe fallback that preserves the echo semantics:
    #
    #   • str fields  -> empty string ("") which matches how a
    #     blank BMS input screen renders an un-submitted field.
    #   • Decimal     -> ``Decimal("0")`` re-scaled via
    #     :func:`safe_decimal` to preserve the 2-decimal-place
    #     contract of the response schema.
    #   • Date segments (year/month/day) -> ``""`` so
    #     :func:`_format_date` returns an empty string rather than
    #     raising ``ValueError``. Callers that refetch via GET will
    #     replace the empty display with the stored value.
    echo_active_status: str = request.active_status or ""
    echo_credit_limit: Decimal = request.credit_limit if request.credit_limit is not None else Decimal("0")
    echo_cash_credit_limit: Decimal = (
        request.cash_credit_limit if request.cash_credit_limit is not None else Decimal("0")
    )
    echo_group_id: str = request.group_id or ""
    echo_open_year: str = request.open_date_year or ""
    echo_open_month: str = request.open_date_month or ""
    echo_open_day: str = request.open_date_day or ""
    echo_expiry_year: str = request.expiration_date_year or ""
    echo_expiry_month: str = request.expiration_date_month or ""
    echo_expiry_day: str = request.expiration_date_day or ""
    echo_reissue_year: str = request.reissue_date_year or ""
    echo_reissue_month: str = request.reissue_date_month or ""
    echo_reissue_day: str = request.reissue_date_day or ""
    echo_dob_year: str = request.customer_dob_year or ""
    echo_dob_month: str = request.customer_dob_month or ""
    echo_dob_day: str = request.customer_dob_day or ""
    echo_ssn_part1: str = request.customer_ssn_part1 or ""
    echo_ssn_part2: str = request.customer_ssn_part2 or ""
    echo_ssn_part3: str = request.customer_ssn_part3 or ""
    echo_fico: str = request.customer_fico_score or ""
    echo_first_name: str = request.customer_first_name or ""
    echo_middle_name: str = request.customer_middle_name or ""
    echo_last_name: str = request.customer_last_name or ""
    echo_addr_line_1: str = request.customer_addr_line_1 or ""
    echo_addr_line_2: str = request.customer_addr_line_2 or ""
    echo_city: str = request.customer_city or ""
    echo_state_cd: str = request.customer_state_cd or ""
    echo_country_cd: str = request.customer_country_cd or ""
    echo_zip: str = request.customer_zip or ""
    echo_phone_1_area: str = request.customer_phone_1_area or ""
    echo_phone_1_prefix: str = request.customer_phone_1_prefix or ""
    echo_phone_1_line: str = request.customer_phone_1_line or ""
    echo_phone_2_area: str = request.customer_phone_2_area or ""
    echo_phone_2_prefix: str = request.customer_phone_2_prefix or ""
    echo_phone_2_line: str = request.customer_phone_2_line or ""
    echo_govt_id: str = request.customer_govt_id or ""
    echo_eft_account_id: str = request.customer_eft_account_id or ""
    echo_pri_cardholder: str = request.customer_pri_cardholder or ""

    return AccountUpdateResponse(
        account_id=safe_acct_id,
        active_status=echo_active_status,
        open_date=_format_date(
            echo_open_year,
            echo_open_month,
            echo_open_day,
        ),
        credit_limit=safe_decimal(echo_credit_limit),
        expiration_date=_format_date(
            echo_expiry_year,
            echo_expiry_month,
            echo_expiry_day,
        ),
        cash_credit_limit=safe_decimal(echo_cash_credit_limit),
        reissue_date=_format_date(
            echo_reissue_year,
            echo_reissue_month,
            echo_reissue_day,
        ),
        # View-only monetary fields — not present on the update request.
        # We echo zero rather than a meaningless default so the response
        # remains well-formed; the UI should refetch via GET after a
        # failed update to re-sync to the current persisted values.
        current_balance=safe_decimal(Decimal("0")),
        current_cycle_credit=safe_decimal(Decimal("0")),
        group_id=echo_group_id,
        current_cycle_debit=safe_decimal(Decimal("0")),
        # Customer: echo segmented values back into the display
        # forms so the JSON response is consistent with the view
        # contract.
        customer_id="",
        customer_ssn=_format_ssn_display(
            _join_ssn(
                echo_ssn_part1,
                echo_ssn_part2,
                echo_ssn_part3,
            )
        ),
        customer_dob=_format_date(
            echo_dob_year,
            echo_dob_month,
            echo_dob_day,
        ),
        customer_fico_score=echo_fico,
        customer_first_name=echo_first_name,
        customer_middle_name=echo_middle_name,
        customer_last_name=echo_last_name,
        customer_addr_line_1=echo_addr_line_1,
        customer_state_cd=echo_state_cd,
        customer_addr_line_2=echo_addr_line_2,
        customer_zip=echo_zip,
        customer_city=echo_city,
        customer_country_cd=echo_country_cd,
        customer_phone_1=_format_phone_stored(
            echo_phone_1_area,
            echo_phone_1_prefix,
            echo_phone_1_line,
        ),
        customer_govt_id=echo_govt_id,
        customer_phone_2=_format_phone_stored(
            echo_phone_2_area,
            echo_phone_2_prefix,
            echo_phone_2_line,
        ),
        customer_eft_account_id=echo_eft_account_id,
        customer_pri_cardholder=echo_pri_cardholder,
        info_message=None,
        error_message=error_message,
    )


# ---------------------------------------------------------------------------
# Transaction-rollback helper
# ---------------------------------------------------------------------------


async def _safe_rollback(db: AsyncSession, log_context: dict[str, object]) -> None:
    """Roll back the active transaction, swallowing any secondary exception.

    Used after an error path to discard pending mutations before
    returning an error response. The COBOL analogue is ``EXEC CICS
    SYNCPOINT ROLLBACK`` (COACTUPC.cbl line ~4100) — a mainframe
    CICS primitive that is similarly best-effort: if the rollback
    itself fails (e.g. database is unreachable), the task
    terminates anyway and the mainframe task manager reclaims
    the task resources. We mimic that behavior by catching any
    secondary exception during rollback and logging it without
    re-raising — because the caller has already decided to
    return an error response to the client, re-raising here
    would only confuse the error handling chain.

    Parameters
    ----------
    db : AsyncSession
        The session on which to issue the rollback.
    log_context : dict[str, object]
        Structured logging context (operation, acct_id, etc.)
        to include in any secondary-failure log record.
    """
    try:
        await db.rollback()
    except Exception:  # noqa: BLE001 — deliberate blanket catch; see docstring
        logger.exception(
            "Session rollback failed during account-update error recovery",
            extra=log_context,
        )


# ============================================================================
# Module exports
# ============================================================================

__all__: list[str] = ["AccountService"]
"""Public API of this module.

Only :class:`AccountService` is part of the public contract. All
``_``-prefixed names are implementation details subject to change
without notice.
"""
