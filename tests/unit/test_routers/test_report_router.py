# ============================================================================
# CardDemo — Unit tests for report_router (Mainframe-to-Cloud migration)
# ============================================================================
# Source:
#   * app/cbl/CORPT00C.cbl     — CICS report-submission program, transaction
#                                ``CR00`` (~649 lines). Reads three mutually
#                                exclusive BMS flag fields
#                                (``MONTHLYI``/``YEARLYI``/``CUSTOMI``) plus
#                                two segmented date ranges
#                                (``SDT**I`` and ``EDT**I``), constructs a
#                                1000-line JCL deck in
#                                ``WS-JOBSUB-JCL-REC`` (the ``//TRNRPT00 JOB``
#                                card, ``//STEP10 EXEC PROC=TRANREPT`` line,
#                                ``DD *`` statements, PARM lines, ``/*`` and
#                                ``/*EOF`` sentinels), and finally issues
#                                ``EXEC CICS WRITEQ TD QUEUE('JOBS')`` to
#                                enqueue the deck for an external job
#                                scheduler that drops it onto the JES2
#                                internal reader (INTRDR).
#   * app/cpy-bms/CORPT00.CPY  — Report-submission BMS symbolic map
#                                (``CORPT0AI`` / ``CORPT0AO``). Defines
#                                the three 1-char report-type flags
#                                ``MONTHLYI``/``YEARLYI``/``CUSTOMI``, the
#                                segmented date fields
#                                ``SDTMMI``/``SDTDDI``/``SDTYYYYI`` (start)
#                                and ``EDTMMI``/``EDTDDI``/``EDTYYYYI`` (end),
#                                the confirmation echo ``CONFIRMI`` PIC X(1),
#                                and the 78-character error message slot
#                                ``ERRMSGI`` PIC X(78).
# ----------------------------------------------------------------------------
# Feature F-022: Report Submission. Target implementation under test:
# ``src/api/routers/report_router.py`` — FastAPI router providing a single
# ``POST /reports/submit`` endpoint that validates the request via Pydantic,
# delegates to :class:`src.api.services.report_service.ReportService`
# (which publishes a JSON message to the AWS SQS FIFO queue replacing the
# CICS ``'JOBS'`` TDQ), and translates the ``confirm='N'`` SQS-failure
# outcome into an ``HTTPException(500)`` per AAP §0.5.1.
# ----------------------------------------------------------------------------
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
"""Unit tests for :mod:`src.api.routers.report_router`.

Validates the ``POST /reports/submit`` endpoint that replaces the CICS
``CORPT00C`` COBOL program (transaction ``CR00``, Feature F-022) per
AAP §0.5.1 (File-by-File Transformation Plan). The tests isolate the
router from the underlying AWS SQS FIFO queue by patching
:class:`src.api.services.report_service.ReportService` at the router
import site so that no real ``boto3.client('sqs').send_message`` call is
attempted during unit testing.

COBOL → Python Verification Surface
-----------------------------------
=====================================================  ===============================================
COBOL paragraph / statement                            Python test (this module)
=====================================================  ===============================================
``EVALUATE TRUE`` MONTHLYI = 'Y' branch                ``test_submit_monthly_report_success``
``EVALUATE TRUE`` YEARLYI = 'Y' branch                 ``test_submit_yearly_report_success``
``EVALUATE TRUE`` CUSTOMI = 'Y' branch (happy path)    ``test_submit_custom_report_success``
``IF WS-START-DATE = SPACES`` (L320-323)               ``test_submit_custom_report_missing_start_date``
``IF WS-END-DATE   = SPACES`` (L325-328)               ``test_submit_custom_report_missing_end_date``
``IF WS-START-DATE = SPACES`` AND                      ``test_submit_custom_report_missing_both_dates``
  ``IF WS-END-DATE = SPACES`` (combined)
``CALL 'CSUTLDTC'`` date-validation routine returning  ``test_submit_custom_report_invalid_date_format``
  non-zero WS-SEVERITY-CD (L331-347, L349-365)
``IF WS-END-DATE < WS-START-DATE`` ordering check      ``test_submit_custom_report_end_before_start``
``ELSE`` branch under the mutex-check EVALUATE (no     ``test_submit_invalid_report_type``
  flag set to 'Y') - COBOL issues WS-MESSAGE error
``EXEC CICS WRITEQ TD QUEUE('JOBS')`` returning a      ``test_submit_report_sqs_failure``
  non-NORMAL DFHRESP (L517-535) → SQS send_message
  exception in the Python port
``EXEC CICS RECEIVE MAP('CORPT0A')`` without a prior   ``test_submit_report_requires_auth``
  sign-on (EIBCALEN = 0) - implicit JWT 401 today
``MOVE 'Y' TO CONFIRMI`` + ``SEND MAP('CORPT0A')``     ``test_submit_report_confirmation_flow``
  on successful TDQ enqueue
=====================================================  ===============================================

Mocking Strategy
----------------
The :class:`ReportService` is patched at the router import site —
``"src.api.routers.report_router.ReportService"`` — following the
pattern used in :mod:`tests.unit.test_routers.test_user_router`. This
replaces the service instance that the router constructs inside
``submit_report()`` (line 259 of the target module) with a
:class:`unittest.mock.MagicMock` whose ``submit_report`` attribute is
configured as an :class:`unittest.mock.AsyncMock`.

The service's return value is shaped as a
:class:`src.shared.schemas.report_schema.ReportSubmissionResponse`
instance carrying the ``report_id`` / ``report_type`` / ``confirm`` /
``message`` fields that the router echoes back to the caller. Tests
that exercise the success path use ``confirm='Y'``; the SQS-failure
test uses ``confirm='N'`` to trigger the router's
``HTTPException(500)`` translation (line 292-306 of the target).

HTTP Status-Code Expectations
-----------------------------
================================================  ====================================
Scenario                                          Expected HTTP status
================================================  ====================================
Valid request, SQS enqueue OK                     ``200 OK``
Request body rejected by Pydantic validator       ``422 Unprocessable Entity``
No ``Authorization`` header present               ``401 Unauthorized``
SQS ``send_message`` fails (confirm='N')          ``500 Internal Server Error``
================================================  ====================================

Fixtures Used
-------------
From :mod:`tests.conftest`:
    * ``client``           — AsyncClient with a regular-user JWT and
                             ``get_current_user`` dependency override
                             (sufficient for all happy-path and
                             validation tests since ``/reports`` is
                             not in :data:`ADMIN_ONLY_PREFIXES`).
    * ``test_app``         — FastAPI app used to build a fresh
                             AsyncClient (without an ``Authorization``
                             header) for the HTTP 401 test.

See Also
--------
* AAP §0.5.1  — File-by-File Transformation Plan (``report_router``
  row) and ``tests/unit/test_routers/test_report_router.py`` row.
* AAP §0.4.1  — TDQ → SQS FIFO architectural decision.
* AAP §0.7.1  — "Preserve all existing functionality exactly as-is".
* :mod:`src.api.routers.report_router` — the module under test.
* :mod:`src.api.services.report_service` — the mocked collaborator.
* :mod:`src.shared.schemas.report_schema` — request/response contracts.
"""

from __future__ import annotations

from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest  # noqa: F401  # retained for future @pytest.mark.parametrize use
from fastapi import FastAPI, status
from httpx import ASGITransport, AsyncClient

from src.shared.schemas.report_schema import (
    ReportSubmissionResponse,
    ReportType,
)

# ============================================================================
# Test constants — tightly coupled to conftest.py fixture values
# ============================================================================
# The ``client`` fixture in conftest.py overrides ``get_current_user`` to
# return ``CurrentUser(user_id="TESTUSER", user_type="U", is_admin=False)``.
# We mirror that identity here so response-body / log-field assertions
# remain self-documenting (without importing conftest's private module
# constants which are intentionally underscore-prefixed).
#
# CORPT00C.cbl itself does not gate on user_type — any signed-in user
# (``CDEMO-USRTYP-USER`` or ``CDEMO-USRTYP-ADMIN``) could reach the
# report-submission screen via the main menu (COMEN01C option 10),
# and ``/reports`` is intentionally absent from
# :data:`src.api.middleware.auth.ADMIN_ONLY_PREFIXES`.
# ============================================================================
_EXPECTED_USER_ID: str = "TESTUSER"

# ============================================================================
# Mock-target path — MUST patch the ReportService reference bound on the
# router module, NOT the service's definition site.
# ----------------------------------------------------------------------------
# The router does ``from src.api.services.report_service import
# ReportService`` at import time (line 110 of report_router.py), creating
# a binding on ``src.api.routers.report_router.ReportService``. When
# ``submit_report()`` later calls ``ReportService()`` it resolves via
# that binding — so patching the original module would leave the router's
# binding pointing at the real class. This mirror of the technique used
# throughout ``tests/unit/test_routers/test_user_router.py``.
# ============================================================================
_REPORT_SERVICE_PATCH_TARGET: str = "src.api.routers.report_router.ReportService"

# ============================================================================
# Success-message literals — drawn directly from the service layer so a
# future refactor that changes the wording but not the shape doesn't
# silently break the test-suite. We deliberately keep these as plain
# string literals (rather than importing the private ``_SUCCESS_MSG_FMT``
# constant from the service) because the test's contract is: whatever
# ``ReportSubmissionResponse.message`` the service returns MUST survive
# the round-trip through the router untouched.
# ============================================================================
_SUCCESS_MSG_MONTHLY: str = "Monthly report submitted for printing ..."
_SUCCESS_MSG_YEARLY: str = "Yearly report submitted for printing ..."
_SUCCESS_MSG_CUSTOM: str = "Custom report submitted for printing ..."

# ``WRITE-JOBSUB-TDQ`` failure literal from CORPT00C.cbl line 531 — the
# exact string the COBOL program placed into ``WS-MESSAGE`` when
# ``EXEC CICS WRITEQ TD`` failed. Replicating the wording here is a
# deliberate AAP §0.7.1 compliance check ("preserve all existing
# functionality exactly as-is" applies to user-facing text too).
_SQS_FAILURE_MSG: str = "Unable to Write TDQ (JOBS)..."

# Server-generated identifier used across all success-path tests. Any
# non-empty UUID-shaped string satisfies the ``report_id`` field
# contract on :class:`ReportSubmissionResponse`; the particular literal
# here is arbitrary and chosen only for readability of failure output.
_TEST_REPORT_ID: str = "00000000-0000-4000-8000-000000000001"


def _make_success_response(
    report_type: ReportType,
    message: str,
    report_id: str = _TEST_REPORT_ID,
) -> ReportSubmissionResponse:
    """Construct a fully-populated success response for mocking.

    Mirrors the service layer's success-path assembly at line 557 of
    :mod:`src.api.services.report_service`
    (``ReportSubmissionResponse(report_id=..., report_type=...,
    confirm=_CONFIRM_YES, message=...)``). The returned object is
    already a valid Pydantic model instance — there is no need for the
    mock to additionally validate it.
    """
    return ReportSubmissionResponse(
        report_id=report_id,
        report_type=report_type,
        confirm="Y",
        message=message,
    )


def _make_failure_response(
    report_type: ReportType,
    message: str = _SQS_FAILURE_MSG,
    report_id: str = _TEST_REPORT_ID,
) -> ReportSubmissionResponse:
    """Construct a SQS-publish-failure response for mocking.

    Mirrors the service layer's exception-path assembly at line 507 of
    :mod:`src.api.services.report_service`
    (``ReportSubmissionResponse(report_id=..., report_type=...,
    confirm=_CONFIRM_NO, message=_truncate_message(_ERROR_MSG))``).
    Returning this from the mock causes the router to raise
    :class:`HTTPException(status_code=500, detail=response.message)`
    per lines 292-306 of the target module.
    """
    return ReportSubmissionResponse(
        report_id=report_id,
        report_type=report_type,
        confirm="N",
        message=message,
    )


# ============================================================================
# SECTION 1 — TestReportSubmission — all 12 required test methods
# ----------------------------------------------------------------------------
# Covers every COBOL path in CORPT00C.cbl:
#   1-3.  Monthly / Yearly / Custom happy paths (``EVALUATE TRUE`` on
#         MONTHLYI / YEARLYI / CUSTOMI).
#   4-6.  Custom-report missing-date validation (``IF WS-START-DATE =
#         SPACES``, ``IF WS-END-DATE = SPACES``, both missing).
#   7-8.  Custom-report date-content validation — invalid format
#         (``CSUTLDTC`` returns non-zero ``WS-SEVERITY-CD``) and ordering
#         (``IF WS-END-DATE < WS-START-DATE``).
#   9.    Invalid ``report_type`` value — the ``ELSE`` branch under the
#         mutex EVALUATE (none of the three flags are 'Y').
#   10.   SQS publish failure — ``EXEC CICS WRITEQ TD QUEUE('JOBS')``
#         returning non-NORMAL DFHRESP (line 531, "Unable to Write TDQ
#         (JOBS)...").
#   11.   Unauthenticated request — the JWT middleware rejects with HTTP
#         401 before the router dependency stack runs.
#   12.   Confirmation-flow structural validation of the success
#         response body (``CONFIRMI`` / ``ERRMSGI`` fields).
# ============================================================================
class TestReportSubmission:
    """Tests for the ``POST /reports/submit`` endpoint (Feature F-022)."""

    # ------------------------------------------------------------------
    # 1. Monthly report — happy path
    # ------------------------------------------------------------------
    async def test_submit_monthly_report_success(self, client: AsyncClient) -> None:
        """Monthly report submission returns HTTP 200 with confirm='Y'.

        Mirrors ``CORPT00C.cbl`` ``EVALUATE TRUE`` where
        ``MONTHLYI = 'Y'``: the program constructs the JCL deck with a
        ``DATE1`` PARM of the current-month start and current-day end,
        then ``WRITEQ TD QUEUE('JOBS')`` enqueues the deck. In the
        cloud-native port, no date fields are required on the request —
        the service layer resolves the month range internally when it
        builds the SQS message body.

        Assertions:
            * HTTP 200 OK.
            * Response body contains ``report_id`` (non-empty string).
            * Response body carries ``report_type='monthly'``.
            * Response body carries ``confirm='Y'`` (CORPT00 ``CONFIRMI``
              PIC X(1) success sentinel).
            * Response body carries a non-empty ``message`` (CORPT00
              ``ERRMSGI`` PIC X(78) success text).
            * :meth:`ReportService.submit_report` was called exactly
              once and received a ``ReportSubmissionRequest`` whose
              ``report_type`` matched ``monthly``.
        """
        request_body: dict[str, Any] = {"report_type": "monthly"}

        with patch(_REPORT_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.submit_report = AsyncMock(
                return_value=_make_success_response(ReportType.monthly, _SUCCESS_MSG_MONTHLY),
            )

            response = await client.post("/reports/submit", json=request_body)

        # HTTP 200 — router forwarded the service's confirm='Y'
        # response unchanged (lines 308-328 of report_router.py).
        assert response.status_code == status.HTTP_200_OK, (
            f"Monthly report submission MUST return HTTP 200 OK; got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()

        # ``report_id`` is the UUIDv4 the service assigns for SQS
        # deduplication (line 377 of report_service.py). The router
        # echoes it verbatim so clients can correlate with CloudWatch
        # Logs / Step Functions executions.
        assert "report_id" in body, f"Response missing ``report_id``: {body}"
        assert isinstance(body["report_id"], str) and body["report_id"], (
            f"``report_id`` must be a non-empty string; got {body.get('report_id')!r}"
        )
        # ``report_type`` must echo the request exactly — CORPT00 does
        # not distinguish 'monthly' from 'MONTHLY'; lowercase is the
        # canonical on-the-wire form.
        assert body.get("report_type") == "monthly", (
            f"``report_type`` MUST echo request; got {body.get('report_type')!r}"
        )
        # CORPT00 CONFIRMI PIC X(1) = 'Y' on success.
        assert body.get("confirm") == "Y", (
            f"``confirm`` MUST be 'Y' on success (CORPT00 CONFIRMI); got {body.get('confirm')!r}"
        )
        # CORPT00 ERRMSGI PIC X(78) carries the success confirmation
        # text. Non-empty check only — the exact wording is a service-
        # level concern.
        assert body.get("message"), (
            f"Success response MUST carry a non-empty ``message`` (CORPT00 ERRMSGI); got {body.get('message')!r}"
        )

        # Verify the service was invoked with the correct request.
        mock_service_class.assert_called_once_with()  # no-arg constructor
        mock_instance.submit_report.assert_awaited_once()
        call_request = mock_instance.submit_report.call_args.args[0]
        assert call_request.report_type == ReportType.monthly, (
            f"Service received wrong report_type; got {call_request.report_type!r}"
        )

    # ------------------------------------------------------------------
    # 2. Yearly report — happy path
    # ------------------------------------------------------------------
    async def test_submit_yearly_report_success(self, client: AsyncClient) -> None:
        """Yearly report submission returns HTTP 200 with confirm='Y'.

        Mirrors ``CORPT00C.cbl`` ``EVALUATE TRUE`` where
        ``YEARLYI = 'Y'``: the program constructs the JCL deck with a
        ``DATE1`` PARM of the current-year start and current-day end,
        then ``WRITEQ TD QUEUE('JOBS')`` enqueues the deck. Like the
        monthly case, no date fields are required on the request.

        Assertions:
            * HTTP 200 OK.
            * Response body carries ``report_type='yearly'``.
            * Response body carries ``confirm='Y'``.
            * Service received a request with ``report_type == yearly``.
        """
        request_body: dict[str, Any] = {"report_type": "yearly"}

        with patch(_REPORT_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.submit_report = AsyncMock(
                return_value=_make_success_response(ReportType.yearly, _SUCCESS_MSG_YEARLY),
            )

            response = await client.post("/reports/submit", json=request_body)

        assert response.status_code == status.HTTP_200_OK, (
            f"Yearly report submission MUST return HTTP 200 OK; got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()
        assert body.get("report_type") == "yearly", (
            f"``report_type`` MUST echo request; got {body.get('report_type')!r}"
        )
        assert body.get("confirm") == "Y", f"``confirm`` MUST be 'Y' on success; got {body.get('confirm')!r}"

        mock_instance.submit_report.assert_awaited_once()
        call_request = mock_instance.submit_report.call_args.args[0]
        assert call_request.report_type == ReportType.yearly, (
            f"Service received wrong report_type; got {call_request.report_type!r}"
        )

    # ------------------------------------------------------------------
    # 3. Custom report with valid date range — happy path
    # ------------------------------------------------------------------
    async def test_submit_custom_report_success(self, client: AsyncClient) -> None:
        """Custom report with valid date range returns HTTP 200.

        Mirrors ``CORPT00C.cbl`` ``EVALUATE TRUE`` where
        ``CUSTOMI = 'Y'``: the program assembles
        ``WS-START-DATE = SDTYYYYI + '-' + SDTMMI + '-' + SDTDDI`` and
        ``WS-END-DATE = EDTYYYYI + '-' + EDTMMI + '-' + EDTDDI``,
        validates each with ``CALL 'CSUTLDTC'`` and checks ordering,
        then constructs the JCL deck with both dates as ``DATE1``/
        ``DATE2`` PARMs.

        In the Python port those segmented fields are presented to the
        API as pre-assembled ``start_date`` / ``end_date`` strings in
        ISO-8601 ``YYYY-MM-DD`` format. Full-year 2024 (``2024-01-01``
        through ``2024-12-31``) is the canonical happy-path sample.

        Assertions:
            * HTTP 200 OK.
            * Response carries ``report_type='custom'`` and ``confirm='Y'``.
            * Service received the request with the original
              ``start_date`` and ``end_date`` values preserved.
        """
        request_body: dict[str, Any] = {
            "report_type": "custom",
            "start_date": "2024-01-01",
            "end_date": "2024-12-31",
        }

        with patch(_REPORT_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.submit_report = AsyncMock(
                return_value=_make_success_response(ReportType.custom, _SUCCESS_MSG_CUSTOM),
            )

            response = await client.post("/reports/submit", json=request_body)

        assert response.status_code == status.HTTP_200_OK, (
            f"Custom report with valid range MUST return HTTP 200 OK; got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()
        assert body.get("report_type") == "custom", (
            f"``report_type`` MUST echo request; got {body.get('report_type')!r}"
        )
        assert body.get("confirm") == "Y", f"``confirm`` MUST be 'Y' on success; got {body.get('confirm')!r}"
        # The report_id is the server-assigned correlation handle that
        # downstream consumers use to match the HTTP response with the
        # SQS FIFO message.
        assert body.get("report_id"), f"``report_id`` MUST be non-empty; got {body.get('report_id')!r}"

        # Verify the service got the dates exactly as supplied — no
        # transformation happens in the router (line 271 passes the
        # validated Pydantic model directly).
        mock_instance.submit_report.assert_awaited_once()
        call_request = mock_instance.submit_report.call_args.args[0]
        assert call_request.report_type == ReportType.custom, (
            f"Service received wrong report_type; got {call_request.report_type!r}"
        )
        assert call_request.start_date == "2024-01-01", (
            f"Service received wrong start_date; got {call_request.start_date!r}"
        )
        assert call_request.end_date == "2024-12-31", f"Service received wrong end_date; got {call_request.end_date!r}"

    # ------------------------------------------------------------------
    # 4. Custom report missing start_date — Pydantic rejection
    # ------------------------------------------------------------------
    async def test_submit_custom_report_missing_start_date(self, client: AsyncClient) -> None:
        """Custom report without ``start_date`` returns HTTP 422.

        Mirrors the ``CORPT00C.cbl`` validation at line 320-323 where
        ``IF WS-START-DATE = SPACES`` flags the submission and
        ``MOVE 'Start Date cannot be blank...' TO WS-MESSAGE``. In the
        Python port this rule is enforced by
        :func:`ReportSubmissionRequest._validate_custom_requires_dates`
        (a Pydantic model-level validator) — the endpoint function is
        never reached.

        Assertions:
            * HTTP 422 Unprocessable Entity.
            * Service layer was NOT invoked (validation short-circuited
              before the router body ran).
            * Error detail mentions ``start_date`` so the UI layer can
              highlight the offending field.
        """
        request_body: dict[str, Any] = {
            "report_type": "custom",
            "start_date": None,
            "end_date": "2024-12-31",
        }

        with patch(_REPORT_SERVICE_PATCH_TARGET) as mock_service_class:
            response = await client.post("/reports/submit", json=request_body)

        # HTTP 422 — FastAPI's default error code for Pydantic
        # ValidationError when the request body fails schema validation.
        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"Missing start_date on custom report MUST return HTTP 422; got {response.status_code}: {response.text}"
        )

        # Service MUST NOT be constructed/invoked — validation short-
        # circuits the entire endpoint coroutine.
        mock_service_class.assert_not_called()

        # The error detail is a list of Pydantic error dicts. At least
        # one of them must mention ``start_date`` so that client UIs
        # can surface the specific missing field. We search the
        # serialized body because the exact structure (``detail`` vs
        # ``error``) depends on whether the global exception handler
        # intercepted the ValidationError.
        assert "start_date" in response.text, f"Validation error body MUST mention ``start_date``; got {response.text}"

    # ------------------------------------------------------------------
    # 5. Custom report missing end_date — Pydantic rejection
    # ------------------------------------------------------------------
    async def test_submit_custom_report_missing_end_date(self, client: AsyncClient) -> None:
        """Custom report without ``end_date`` returns HTTP 422.

        Mirrors the ``CORPT00C.cbl`` validation at line 325-328 where
        ``IF WS-END-DATE = SPACES`` flags the submission and
        ``MOVE 'End Date cannot be blank...' TO WS-MESSAGE``. Same
        validator pathway as the missing-start case.

        Assertions:
            * HTTP 422 Unprocessable Entity.
            * Service layer was NOT invoked.
            * Error detail mentions ``end_date``.
        """
        request_body: dict[str, Any] = {
            "report_type": "custom",
            "start_date": "2024-01-01",
            "end_date": None,
        }

        with patch(_REPORT_SERVICE_PATCH_TARGET) as mock_service_class:
            response = await client.post("/reports/submit", json=request_body)

        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"Missing end_date on custom report MUST return HTTP 422; got {response.status_code}: {response.text}"
        )
        mock_service_class.assert_not_called()

        assert "end_date" in response.text, f"Validation error body MUST mention ``end_date``; got {response.text}"

    # ------------------------------------------------------------------
    # 6. Custom report missing BOTH dates — Pydantic rejection
    # ------------------------------------------------------------------
    async def test_submit_custom_report_missing_both_dates(self, client: AsyncClient) -> None:
        """Custom report with neither date returns HTTP 422.

        Mirrors the combined ``IF WS-START-DATE = SPACES`` + ``IF
        WS-END-DATE = SPACES`` branches of ``CORPT00C.cbl``. The
        :func:`_validate_custom_requires_dates` validator explicitly
        handles the both-missing case with an error message of the
        form ``"report_type=custom requires start_date and end_date
        to be provided"``.

        Assertions:
            * HTTP 422 Unprocessable Entity.
            * Service layer was NOT invoked.
            * Error detail mentions BOTH ``start_date`` AND ``end_date``.
        """
        request_body: dict[str, Any] = {
            "report_type": "custom",
            # start_date and end_date deliberately omitted — the
            # schema ``Optional[str]`` default is ``None``.
        }

        with patch(_REPORT_SERVICE_PATCH_TARGET) as mock_service_class:
            response = await client.post("/reports/submit", json=request_body)

        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"Missing both dates on custom report MUST return HTTP 422; got {response.status_code}: {response.text}"
        )
        mock_service_class.assert_not_called()

        # Both field names must appear in the error detail so the
        # client UI can surface both offending fields to the user.
        response_text: str = response.text
        assert "start_date" in response_text, f"Error body MUST mention ``start_date``; got {response_text}"
        assert "end_date" in response_text, f"Error body MUST mention ``end_date``; got {response_text}"

    # ------------------------------------------------------------------
    # 7. Custom report with malformed date strings — Pydantic rejection
    # ------------------------------------------------------------------
    async def test_submit_custom_report_invalid_date_format(self, client: AsyncClient) -> None:
        """Custom report with a non-ISO-8601 date returns HTTP 422.

        Mirrors ``CORPT00C.cbl`` lines 331-347 / 349-365 where the
        program invokes the shared ``CSUTLDTC`` date-validation
        subroutine with ``WS-DATE-FORMAT = 'YYYYMMDD'`` and checks
        ``WS-SEVERITY-CD`` for non-zero (``MOVE 'Invalid Start Date'``
        / ``'Invalid End Date'``). In the Python port the pair of
        :func:`ReportSubmissionRequest._validate_date_*` field
        validators enforces the ``YYYY-MM-DD`` regex BEFORE the model
        validator runs.

        The ``01-01-2024`` format used here (MM-DD-YYYY) is a common
        US-style typo — it passes the 10-character length check but
        fails the regex anchor ``^\\d{4}-\\d{2}-\\d{2}$``.

        Assertions:
            * HTTP 422 Unprocessable Entity.
            * Service layer was NOT invoked.
        """
        request_body: dict[str, Any] = {
            "report_type": "custom",
            "start_date": "01-01-2024",  # wrong: MM-DD-YYYY, not YYYY-MM-DD
            "end_date": "2024-12-31",
        }

        with patch(_REPORT_SERVICE_PATCH_TARGET) as mock_service_class:
            response = await client.post("/reports/submit", json=request_body)

        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"Invalid date format MUST return HTTP 422; got {response.status_code}: {response.text}"
        )
        mock_service_class.assert_not_called()

    # ------------------------------------------------------------------
    # 8. Custom report with end_date < start_date — validator rejection
    # ------------------------------------------------------------------
    async def test_submit_custom_report_end_before_start(self, client: AsyncClient) -> None:
        """Custom report with ``end_date < start_date`` returns HTTP 422.

        Mirrors the ordering check in ``CORPT00C.cbl`` where
        ``IF WS-END-DATE < WS-START-DATE`` moves the
        ``'End Date cannot be before Start Date...'`` literal into
        ``WS-MESSAGE``. In the Python port the
        :func:`_validate_custom_requires_dates` model validator
        performs the same check AFTER both field-level validators have
        confirmed that the strings are well-formed dates.

        The reversed range (``2024-12-31`` … ``2024-01-01``) is the
        most obvious failure case.

        Assertions:
            * HTTP 422 Unprocessable Entity.
            * Service layer was NOT invoked.
            * Error detail mentions the ordering rule so UI layers can
              present a user-friendly message.
        """
        request_body: dict[str, Any] = {
            "report_type": "custom",
            "start_date": "2024-12-31",
            "end_date": "2024-01-01",
        }

        with patch(_REPORT_SERVICE_PATCH_TARGET) as mock_service_class:
            response = await client.post("/reports/submit", json=request_body)

        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"end_date < start_date MUST return HTTP 422; got {response.status_code}: {response.text}"
        )
        mock_service_class.assert_not_called()

        # The validator uses the phrase ``"end_date must be greater
        # than or equal to start_date"``. We search for the key
        # substring ``end_date`` AND ``start_date`` rather than the
        # full sentence to stay resilient to minor copy changes.
        response_text: str = response.text
        assert "end_date" in response_text and "start_date" in response_text, (
            f"Ordering-violation error MUST mention BOTH date fields; got {response_text}"
        )

    # ------------------------------------------------------------------
    # 9. Invalid report_type value — enum rejection
    # ------------------------------------------------------------------
    async def test_submit_invalid_report_type(self, client: AsyncClient) -> None:
        """Unknown ``report_type`` value returns HTTP 422.

        Mirrors the ``ELSE`` branch of ``CORPT00C.cbl``'s outer
        ``EVALUATE TRUE`` statement where none of the three BMS flags
        (``MONTHLYI``/``YEARLYI``/``CUSTOMI``) is set to ``'Y'`` — the
        program issues ``'Please select a report type'`` and re-sends
        the screen. In the Python port the :class:`ReportType` enum
        restricts the acceptable values to ``{"monthly", "yearly",
        "custom"}``; any other string triggers a Pydantic
        ``ValidationError`` before the route handler runs.

        Assertions:
            * HTTP 422 Unprocessable Entity.
            * Service layer was NOT invoked.
        """
        request_body: dict[str, Any] = {"report_type": "invalid"}

        with patch(_REPORT_SERVICE_PATCH_TARGET) as mock_service_class:
            response = await client.post("/reports/submit", json=request_body)

        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"Unknown report_type MUST return HTTP 422; got {response.status_code}: {response.text}"
        )
        mock_service_class.assert_not_called()

        # The enum validator surfaces the offending FIELD NAME in the
        # wrapped error-handler envelope so UI layers can highlight
        # the correct input control. The exact Pydantic-internal
        # message format (e.g. ``"Input should be 'monthly', 'yearly'
        # or 'custom'"``) is an implementation detail of the global
        # error_handler middleware — it lists the valid enum values
        # from the schema but does not echo the user-supplied bad
        # value verbatim in the public error envelope.
        #
        # Asserting on the field name is the most stable contract:
        # it verifies the client receives enough information to
        # highlight the specific offending input without coupling
        # the test to any Pydantic / middleware internals.
        assert "report_type" in response.text, (
            f"Validation error MUST identify the offending field ``report_type``; got {response.text}"
        )

    # ------------------------------------------------------------------
    # 10. SQS publish failure — service returns confirm='N' → HTTP 500
    # ------------------------------------------------------------------
    async def test_submit_report_sqs_failure(self, client: AsyncClient) -> None:
        """SQS publish failure surfaces as HTTP 500 Internal Server Error.

        Mirrors ``CORPT00C.cbl`` lines 517-535 where
        ``EXEC CICS WRITEQ TD QUEUE('JOBS')`` returns a non-NORMAL
        ``DFHRESP`` value (e.g., QZERO, NOSPACE, QBUSY, any
        infrastructure failure). The program moved the literal
        ``'Unable to Write TDQ (JOBS)...'`` into ``WS-MESSAGE`` and
        re-sent the screen with ``CONFIRMI = 'N'``.

        In the Python port the service layer catches the boto3
        exception internally and returns a
        :class:`ReportSubmissionResponse` with ``confirm='N'`` plus
        the original COBOL-exact message. The router then translates
        that into :class:`HTTPException` (500) per lines 292-306 of
        the target module — so the downstream UI / SDK sees a proper
        HTTP 5xx and retries (or escalates) appropriately.

        Assertions:
            * HTTP 500 Internal Server Error.
            * Response body contains the COBOL-exact failure message
              (exactly preserved per AAP §0.7.1).
            * Service was invoked exactly once (no client-side retry
              in the router — that is the caller's responsibility).
        """
        request_body: dict[str, Any] = {"report_type": "monthly"}

        with patch(_REPORT_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.submit_report = AsyncMock(
                return_value=_make_failure_response(ReportType.monthly),
            )

            response = await client.post("/reports/submit", json=request_body)

        assert response.status_code == status.HTTP_500_INTERNAL_SERVER_ERROR, (
            f"SQS publish failure MUST surface as HTTP 500; got {response.status_code}: {response.text}"
        )

        # The error response is wrapped by the global ABEND-DATA
        # handler in ``src/api/middleware/error_handler.py`` — the
        # COBOL-exact message appears in the ``reason`` field of the
        # envelope (rather than ``detail``). We search the full
        # response text so the assertion is resilient to future
        # envelope-shape tweaks.
        assert _SQS_FAILURE_MSG in response.text, (
            f"500 response MUST carry the CORPT00C.cbl line 531 "
            f"literal ``{_SQS_FAILURE_MSG!r}`` (AAP §0.7.1 — preserve "
            f"existing behavior exactly); got {response.text}"
        )

        # Service must have been invoked exactly once — the router
        # does NOT retry on its own.
        mock_instance.submit_report.assert_awaited_once()

    # ------------------------------------------------------------------
    # 11. Unauthenticated request — HTTP 401
    # ------------------------------------------------------------------
    async def test_submit_report_requires_auth(self, test_app: FastAPI) -> None:
        """Request without ``Authorization`` header returns HTTP 401.

        Mirrors the CICS access-control model where an unsigned-in
        user could not reach ``CORPT00C`` — the mainframe routed them
        to ``COSGN00C`` first. In the cloud-native port the
        :class:`src.api.middleware.auth.JWTAuthMiddleware` performs
        the equivalent check by looking for a bearer token in the
        ``Authorization`` header; if absent, it short-circuits the
        request with HTTP 401 BEFORE any router dependency resolves
        (crucially, before ``get_current_user`` is invoked).

        This test deliberately bypasses the conftest ``client``
        fixture — which pre-sets ``Authorization: Bearer <JWT>`` —
        and builds a fresh :class:`AsyncClient` against the same
        ``test_app`` so the middleware observes a genuinely missing
        header (the real-world attack pattern of an anonymous caller
        probing the report-submission endpoint).

        Assertions:
            * HTTP 401 Unauthorized.
            * Response includes the ``WWW-Authenticate: Bearer``
              challenge per RFC 7235 §4.1 (emitted by the JWT
              middleware — see ``src/api/middleware/auth.py``
              lines 646-658).
        """
        request_body: dict[str, Any] = {"report_type": "monthly"}

        # Build a fresh AsyncClient against the same test_app (which
        # has its dependency overrides in place) but WITHOUT an
        # Authorization header. The middleware should reject the
        # request before any dependency runs.
        transport = ASGITransport(app=test_app)
        async with AsyncClient(
            transport=transport,
            base_url="http://testserver",
        ) as unauth_client:
            response = await unauth_client.post("/reports/submit", json=request_body)

        assert response.status_code == status.HTTP_401_UNAUTHORIZED, (
            f"Unauthenticated /reports/submit MUST return HTTP 401; got {response.status_code}: {response.text}"
        )
        # RFC 7235 §4.1 — challenge MUST include a WWW-Authenticate
        # header identifying the scheme. The JWT middleware emits
        # ``Bearer``.
        assert "www-authenticate" in {key.lower() for key in response.headers}, (
            f"401 response MUST include WWW-Authenticate header per RFC 7235 §4.1; headers={dict(response.headers)}"
        )

    # ------------------------------------------------------------------
    # 12. Confirmation flow — structural validation of success body
    # ------------------------------------------------------------------
    async def test_submit_report_confirmation_flow(self, client: AsyncClient) -> None:
        """Successful submission response carries the full CONFIRMI flow.

        Mirrors the ``SEND MAP('CORPT0A') FROM(CORPT0AO)`` sequence at
        the end of ``CORPT00C.cbl`` when the TDQ enqueue succeeds: the
        program moves ``'Y'`` into ``CONFIRMI`` and a success message
        into ``ERRMSGI`` before re-painting the screen. The cloud
        response envelopes these as :class:`ReportSubmissionResponse`
        fields — this test validates the full fidelity of the
        echo-back, which is the client's signal that the submission
        was accepted by SQS.

        The happy-path structural contract validated here is:

        * ``report_id``   — server-generated UUIDv4 (non-empty
                            string).
        * ``report_type`` — one of the three :class:`ReportType`
                            values, echoed from the request.
        * ``confirm``     — ``'Y'`` (CORPT00 ``CONFIRMI`` PIC X(1)
                            success sentinel).
        * ``message``     — non-empty, at most 78 characters
                            (CORPT00 ``ERRMSGI`` PIC X(78) width
                            preservation — AAP §0.7.2).

        Assertions:
            * HTTP 200 OK.
            * Response body contains all four required fields.
            * ``confirm == 'Y'``.
            * ``message`` ≤ 78 characters.
            * ``report_id`` is the exact server-returned value.
        """
        request_body: dict[str, Any] = {"report_type": "monthly"}

        with patch(_REPORT_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.submit_report = AsyncMock(
                return_value=_make_success_response(ReportType.monthly, _SUCCESS_MSG_MONTHLY),
            )

            response = await client.post("/reports/submit", json=request_body)

        assert response.status_code == status.HTTP_200_OK, (
            f"Confirmation-flow test expects HTTP 200 OK; got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()

        # Full structural check — every field from the
        # ReportSubmissionResponse schema MUST appear in the JSON.
        for required_field in ("report_id", "report_type", "confirm", "message"):
            assert required_field in body, f"Confirmation response MUST include ``{required_field}``; got {body}"

        # CORPT00 CONFIRMI PIC X(1) = 'Y' on success — this is the
        # client's signal that the submission was enqueued.
        assert body["confirm"] == "Y", (
            f"Confirmation MUST carry ``CONFIRMI='Y'`` per CORPT00C.cbl; got {body.get('confirm')!r}"
        )

        # CORPT00 ERRMSGI PIC X(78) width — the ORM-level constraint
        # is already enforced by the Pydantic model (max_length=78),
        # but the test double-checks the runtime width so a future
        # regression (e.g., an over-long success message sneaking
        # through) fails loudly.
        message_value: str = body["message"]
        assert isinstance(message_value, str) and message_value, (
            f"``message`` MUST be a non-empty string on success; got {message_value!r}"
        )
        assert len(message_value) <= 78, (
            f"``message`` MUST be at most 78 characters (CORPT00 ERRMSGI PIC X(78)); got {len(message_value)} chars"
        )

        # The server-assigned ``report_id`` must be echoed verbatim so
        # clients can correlate with CloudWatch Logs / Step Functions
        # executions downstream.
        assert body["report_id"] == _TEST_REPORT_ID, (
            f"``report_id`` MUST echo the service-assigned UUID; got {body.get('report_id')!r}"
        )
        # ``report_type`` echoes the request — a core part of the
        # CORPT00 confirm-flow contract.
        assert body["report_type"] == "monthly", (
            f"``report_type`` MUST echo the request; got {body.get('report_type')!r}"
        )

        # Verify the service was called exactly once on the happy path.
        mock_instance.submit_report.assert_awaited_once()
