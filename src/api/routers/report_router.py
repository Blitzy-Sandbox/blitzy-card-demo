# ============================================================================
# Source: app/cbl/CORPT00C.cbl  (Report submission, Feature F-022)
#         + app/cpy-bms/CORPT00.CPY  (BMS symbolic map) —
#         Mainframe-to-Cloud migration
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
"""Report submission router. Converted from CORPT00C.cbl (649 lines, Feature F-022). POST /reports/submit publishes to SQS FIFO queue (replacing CICS TDQ WRITEQ to 'JOBS' queue). Supports Monthly/Yearly/Custom report types.

Endpoint summary
----------------
``POST /reports/submit`` — Submit a report-generation request to the
                          SQS FIFO queue (Feature F-022, CORPT00C.cbl).

The router is a thin transport-layer shim that delegates to
:class:`src.api.services.report_service.ReportService`. The service
replaces the CICS ``WRITEQ TD QUEUE('JOBS')`` mechanism from the
original ``CORPT00C`` COBOL program:

* Historically, the COBOL program wrote up to 1000 80-character JCL
  records (the ``//TRNRPT00 JOB`` card, ``//STEP10 EXEC PROC=TRANREPT``
  line, ``DD *`` statements, PARM lines, ``/*`` and ``/*EOF`` sentinels)
  onto a CICS Transient Data Queue named ``JOBS`` which was monitored
  by an external job scheduler that submitted the batch job to JES2.
* In the cloud-native target the equivalent is a **JSON message
  published to an AWS SQS FIFO queue**. A downstream consumer
  (AWS Step Functions + AWS Glue) picks up the message and starts
  the ``tranrept_job`` pipeline. See AAP §0.4.1 for the architectural
  decision.

**Service construction note**: unlike every other router in this
package, the :class:`ReportService` does NOT accept a database
session — it communicates exclusively with SQS through boto3. The
router therefore instantiates ``ReportService()`` with no arguments
and omits the ``db`` dependency entirely (see AAP Phase 3, Step 1
note: "ReportService does NOT need a database session — it's a pure
messaging service").

COBOL → HTTP mapping
--------------------
==================================================  ==========================================
COBOL construct (CORPT00C.cbl)                      HTTP equivalent (this router)
==================================================  ==========================================
``CICS transaction CR00``                           ``POST /reports/submit``
``RECEIVE MAP('CORPT0A') INTO(CORPT0AI)``           ``request: ReportSubmissionRequest`` body
``EVALUATE TRUE`` (MONTHLYI/YEARLYI/CUSTOMI)        ``request.report_type`` (enum)
``MOVE DFHCOMMAREA TO CARDDEMO-COMMAREA``           ``Depends(get_current_user)`` (JWT)
``EXEC CICS WRITEQ TD QUEUE('JOBS')``               :meth:`ReportService.submit_report`
``WS-REPORT-NAME`` + success message STRING         ``response.message`` (success path)
``'Unable to Write TDQ (JOBS)...'`` (L531)          ``HTTPException(500)`` (SQS failure)
``SEND MAP('CORPT0A') FROM(CORPT0AO)``              ``return response`` (JSON)
==================================================  ==========================================

Error surfacing model
---------------------
The Pydantic :class:`ReportSubmissionRequest` validator enforces the
BMS-level business rules BEFORE the endpoint function executes:

* Invalid ``report_type`` (not ``monthly``/``yearly``/``custom``) →
  FastAPI emits HTTP 422 automatically (Pydantic ``ValidationError``).
* ``report_type=custom`` without both ``start_date`` and ``end_date`` →
  HTTP 422 (model-level ``_validate_custom_requires_dates`` validator).
* ``end_date < start_date`` → HTTP 422 (same validator).
* Malformed date strings (not ``YYYY-MM-DD``) → HTTP 422.

Only infrastructure-level failures reach the router's exception
handler:

* **Success** — ``confirm='Y'``. Router returns the
  :class:`ReportSubmissionResponse` unchanged with HTTP 200. The
  ``report_id`` field carries the server-generated UUIDv4 used as
  the SQS ``MessageDeduplicationId`` for exactly-once semantics.
* **SQS publish failure** — ``confirm='N'`` with the COBOL-exact
  message ``"Unable to Write TDQ (JOBS)..."``. Router translates to
  :class:`HTTPException` 500 per AAP §0.5.1 transformation rules
  ("SQS publish failure → HTTPException(500)"). The global ABEND-DATA
  handler in :mod:`src.api.middleware.error_handler` wraps it in the
  standard error envelope.

See Also
--------
* AAP §0.5.1 — File-by-File Transformation Plan (``report_router.py`` row)
* AAP §0.4.1 — TDQ → SQS FIFO architectural decision
* AAP §0.7.2 — Monitoring Requirements (CloudWatch-friendly JSON logging)
* :mod:`src.api.services.report_service` — business / messaging logic
* :mod:`src.shared.schemas.report_schema` — request/response contracts
* :mod:`src.api.dependencies` — :func:`get_current_user` JWT dependency
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, status

from src.api.dependencies import get_current_admin_user
from src.api.services.report_service import ReportService
from src.shared.schemas.report_schema import (
    ReportSubmissionRequest,
    ReportSubmissionResponse,
)

# ----------------------------------------------------------------------------
# Module logger
# ----------------------------------------------------------------------------
# Structured JSON records flow to CloudWatch Logs via the ECS awslogs
# driver on ECS Fargate. Filter by ``logger_name =
# "src.api.routers.report_router"`` in CloudWatch Logs Insights to
# isolate this endpoint's audit trail — useful for correlating API
# submissions with the downstream SQS FIFO message publish events
# emitted by :mod:`src.api.services.report_service`.
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)

# ----------------------------------------------------------------------------
# Router instance
#
# Replaces CICS transaction CR00 (CORPT00C.cbl report submission via
# TDQ → SQS FIFO). The ``/reports`` prefix is applied by
# ``src.api.main`` via ``app.include_router(report_router.router,
# prefix="/reports", tags=["Reports"])`` — so no prefix is specified
# here per AAP Phase 2 "Router Configuration" guidance.
# ----------------------------------------------------------------------------
router: APIRouter = APIRouter()


@router.post(
    "/submit",
    response_model=ReportSubmissionResponse,
    status_code=status.HTTP_200_OK,
    summary="Submit Report",
    response_description=(
        "``confirm='Y'`` with server-generated ``report_id`` (UUIDv4) "
        "on successful SQS FIFO enqueue. SQS publish failures surface "
        "as HTTP 500 via the global ABEND-DATA handler."
    ),
)
async def submit_report(
    request: ReportSubmissionRequest,
    current_user: object = Depends(get_current_admin_user),
) -> ReportSubmissionResponse:
    """Publish a report-generation request to the SQS FIFO queue.

    Replaces the CICS ``CORPT00C`` program's PROCESS-ENTER-KEY →
    SUBMIT-JOB-TO-INTRDR → WRITE-JOBSUB-TDQ paragraph chain. Per the
    architectural decision in AAP §0.4.1, the CICS TDQ ``'JOBS'``
    mechanism is replaced by an AWS SQS FIFO queue, and the downstream
    job-scheduler + JES2 internal reader by AWS Step Functions + Glue.

    Authorization (admin-only)
    --------------------------
    Report submission is restricted to administrators
    (``user_type == 'A'``) via :func:`get_current_admin_user`. This
    mirrors the CORPT00C.cbl original CICS behavior where report
    submission is an administrative function (it consumes JES2
    resources and can trigger expensive batch pipelines). Regular
    users (``user_type == 'U'``) who attempt the endpoint receive
    HTTP 403 Forbidden — matching the ``user_router.py`` /
    ``admin_router.py`` convention for admin-only endpoints.

    Validation handled upstream by Pydantic
    ---------------------------------------
    The :class:`ReportSubmissionRequest` body is validated by Pydantic
    before this function is invoked:

    * Invalid ``report_type`` values → HTTP 422.
    * ``report_type=custom`` missing ``start_date``/``end_date`` →
      HTTP 422.
    * Malformed dates or ``end_date < start_date`` → HTTP 422.

    Consequently the function body only needs to handle the SQS
    infrastructure-level outcome.

    Parameters
    ----------
    request : ReportSubmissionRequest
        Validated request payload carrying ``report_type`` (monthly /
        yearly / custom — replaces the BMS ``MONTHLYI``/``YEARLYI``/
        ``CUSTOMI`` radio-button fields) and optional ``start_date`` /
        ``end_date`` (YYYY-MM-DD, assembled upstream from the BMS
        ``SDTYYYYI+SDTMMI+SDTDDI`` and ``EDTYYYYI+EDTMMI+EDTDDI``
        segmented fields).
    current_user : object
        The JWT-authenticated ADMIN user, injected by
        :func:`get_current_admin_user`. Required — anonymous callers
        receive HTTP 401 from the JWT dependency and non-admin
        callers receive HTTP 403 from the admin-gate dependency
        before reaching this function. Replaces the CICS
        ``COMMAREA``/``CDEMO-USER-ID`` session propagation from
        ``COCOM01Y.cpy``. Typed as ``object`` to avoid importing
        :class:`CurrentUser` beyond the dependency whitelist — the
        router does not consume any fields on the user object, only
        the presence of a valid admin identity.

    Returns
    -------
    ReportSubmissionResponse
        Successful (``confirm='Y'``) response, echoed to the caller
        with HTTP 200. Carries the server-generated ``report_id``
        (UUIDv4) that callers can correlate with downstream
        CloudWatch / Step Functions execution artifacts.

    Raises
    ------
    HTTPException
        HTTP 500 (``"Report submission failed"``) when the SQS FIFO
        ``send_message`` call raises. Replaces the CICS ``DFHRESP !=
        NORMAL`` handling in ``CORPT00C`` WIRTE-JOBSUB-TDQ (L517-535)
        where ``'Unable to Write TDQ (JOBS)...'`` was moved to
        ``WS-MESSAGE`` and the screen re-sent. In the cloud-native
        target, infrastructure failures surface as HTTP 5xx so the
        calling UI / SDK retries (or escalates) appropriately — the
        underlying exception detail is preserved in CloudWatch Logs
        by :mod:`src.api.services.report_service` without leaking
        into the response body.

    See Also
    --------
    * :meth:`ReportService.submit_report` — the messaging adapter that
      actually performs the SQS publish, including the
      ``MessageGroupId='report-submissions'`` and
      ``MessageDeduplicationId=report_id`` FIFO parameters.
    """
    # -------------------------------------------------------------------
    # Audit log — request received.
    #
    # Logged at INFO level with structured ``extra`` fields so that
    # CloudWatch Logs Insights can slice by ``user_id`` (who
    # submitted), ``report_type`` (which report), and ``endpoint``
    # (cross-router correlation). The ``request.report_type`` is a
    # :class:`ReportType` ``(str, Enum)`` member — its ``.value``
    # attribute is the wire-level string (``"monthly"`` /
    # ``"yearly"`` / ``"custom"``) which is what we want in the log.
    # We use :func:`getattr` defensively so that a hypothetical
    # future Pydantic version returning a bare string doesn't raise.
    # -------------------------------------------------------------------
    report_type_value: str = getattr(
        request.report_type, "value", str(request.report_type)
    )
    logger.info(
        "POST /reports/submit initiated",
        extra={
            "user_id": getattr(current_user, "user_id", None),
            "report_type": report_type_value,
            "start_date": request.start_date,
            "end_date": request.end_date,
            "endpoint": "report_submit",
        },
    )

    # -------------------------------------------------------------------
    # Step 1 — Instantiate the messaging-only service.
    #
    # ReportService does NOT take a database session (it is a pure
    # SQS FIFO publisher — no Aurora PostgreSQL interactions). This is
    # intentionally different from every other CardDemo router; see
    # the module docstring for the rationale.
    # -------------------------------------------------------------------
    service: ReportService = ReportService()

    # -------------------------------------------------------------------
    # Step 2 — Delegate to the service.
    #
    # The service method is contractually guaranteed to NEVER raise:
    # SQS errors are caught inside ``submit_report`` and returned as a
    # ``confirm='N'`` response with the COBOL-exact error message
    # ``"Unable to Write TDQ (JOBS)..."``. The router therefore needs
    # no ``try``/``except`` block — it simply inspects the
    # ``confirm`` field of the response.
    # -------------------------------------------------------------------
    response: ReportSubmissionResponse = await service.submit_report(request)

    # -------------------------------------------------------------------
    # Step 3 — Translate ``confirm='N'`` into HTTPException(500).
    #
    # Per AAP §0.5.1 the SQS publish failure case maps to HTTP 500
    # ("SQS publish failure → HTTPException(500, detail='Report
    # submission failed')"). This is correct because ``confirm='N'``
    # from the service indicates an *infrastructure* failure (boto3
    # ``send_message`` raised — queue unreachable, throttled,
    # credentials expired, etc.) rather than a client error.
    # Validation errors (invalid report_type, missing dates, bad
    # ordering) are already handled by Pydantic BEFORE the endpoint
    # is invoked and surface as HTTP 422 automatically.
    #
    # The global ``HTTPException`` handler in
    # ``src/api/middleware/error_handler.py`` wraps the exception in
    # the CardDemo ABEND-DATA envelope (with the CICS-equivalent
    # ``error_code="IOER"`` for 500 responses — see L258 of that
    # module).
    # -------------------------------------------------------------------
    if response.confirm != "Y":
        logger.error(
            "Report submission failed at SQS publish",
            extra={
                "user_id": getattr(current_user, "user_id", None),
                "report_id": response.report_id,
                "report_type": report_type_value,
                "service_message": response.message,
                "endpoint": "report_submit",
            },
        )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=response.message or "Report submission failed",
        )

    # -------------------------------------------------------------------
    # Step 4 — Success audit log (CloudWatch observability).
    #
    # AAP Phase 3 Step 5 calls for "Log report submission event for
    # CloudWatch". The service logs the SQS publish at INFO with the
    # SQS-assigned ``MessageId``; here we additionally log the HTTP
    # endpoint's success so that operators can correlate the HTTP
    # request with the SQS message. Both logs share the same
    # ``report_id`` for correlation.
    # -------------------------------------------------------------------
    logger.info(
        "POST /reports/submit succeeded",
        extra={
            "user_id": getattr(current_user, "user_id", None),
            "report_id": response.report_id,
            "report_type": report_type_value,
            "confirm": response.confirm,
            "endpoint": "report_submit",
        },
    )
    return response


# ----------------------------------------------------------------------------
# Public API — explicit re-export list
#
# Only the ``router`` APIRouter instance is part of the public contract.
# The ``submit_report`` coroutine function is an implementation detail
# of the router and is NOT exported; callers interact with the endpoint
# via HTTP (or via :class:`fastapi.testclient.TestClient` in tests).
# ----------------------------------------------------------------------------
__all__ = ["router"]
