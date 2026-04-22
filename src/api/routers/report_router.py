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
"""Report submission router — HTTP transport for Feature F-022.

Endpoint summary
----------------
``POST /reports/submit`` — Submit a report-generation request to the
                          SQS FIFO queue (F-022, CORPT00C.cbl).

The router is a thin transport-layer shim that delegates to
:class:`src.api.services.report_service.ReportService`. The service
replaces the CICS ``WRITE Q TD ('JOBS')`` mechanism from the
original ``CORPT00C`` COBOL program:

* Historically, the COBOL program wrote up to 1000 80-character JCL
  records onto a CICS Transient Data Queue named ``JOBS`` which was
  monitored by an external job scheduler that then submitted the
  batch job.
* In the cloud-native target the equivalent is a **JSON message
  published to an AWS SQS FIFO queue**. A downstream consumer
  (AWS Step Functions + Glue jobs) picks up the message and starts
  the batch pipeline. See AAP §0.4.1 for the architectural decision.

**Service construction note**: unlike the other routers, the
``ReportService`` does NOT accept a database session — it
communicates exclusively with SQS through boto3. The router
therefore instantiates ``ReportService()`` with no arguments and
omits the ``db`` dependency entirely.

COBOL → HTTP mapping
--------------------
=======================================================  =======================
COBOL construct                                          HTTP equivalent
=======================================================  =======================
``RECEIVE MAP('CORPT00')``                               ``POST /reports/submit`` body
``WRITEQ TD QUEUE('JOBS') FROM(JCL-LINE)``               SQS FIFO ``SendMessage``
Job-scheduler pickup + JCL COND-based orchestration      AWS Step Functions + Glue
WS-CONFIRM / WS-ERRMSG                                   response ``confirm`` / ``message``
=======================================================  =======================

Error surfacing
---------------
Matching the ``bill_router`` convention, the service uses a
"confirm Y/N + message" response pattern:

* **Success** — ``confirm='Y'``, ``report_id`` carries the
  server-generated UUIDv4 used as the SQS message deduplication ID.
  Router returns the response as-is with HTTP 200.
* **Business / infrastructure failure** — ``confirm='N'``, ``message``
  populated with the user-facing reason (e.g. SQS throttling, invalid
  payload). Router translates to :class:`HTTPException` (400) so the
  global ABEND-DATA handler emits a consistent envelope.

See Also
--------
* AAP §0.5.1 — File-by-File Transformation Plan
* AAP §0.4.1 — TDQ -> SQS FIFO architectural decision
* :mod:`src.api.services.report_service` — business logic
* :mod:`src.shared.schemas.report_schema` — request/response contracts
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, status

from src.api.dependencies import CurrentUser, get_current_user
from src.api.services.report_service import ReportService
from src.shared.schemas.report_schema import (
    ReportSubmissionRequest,
    ReportSubmissionResponse,
)

logger = logging.getLogger(__name__)

router: APIRouter = APIRouter()


@router.post(
    "/submit",
    response_model=ReportSubmissionResponse,
    status_code=status.HTTP_200_OK,
    summary="Report submission — SQS FIFO publish (F-022 CORPT00C.cbl)",
    response_description=(
        "confirm='Y' with server-generated report_id (UUIDv4) on "
        "successful enqueue; confirm='N' with message on failure."
    ),
)
async def submit_report(
    request: ReportSubmissionRequest,
    current_user: CurrentUser = Depends(get_current_user),
) -> ReportSubmissionResponse:
    """Publish a report-generation request to the SQS FIFO queue.

    Replaces the CICS ``CORPT00C`` program's SUBMIT-JOB-TO-INTRDR /
    WRITE-JOBSUB-TDQ paragraph pair. Per the architectural decision
    in AAP §0.4.1 the CICS TDQ "JOBS" mechanism is replaced by an
    AWS SQS FIFO queue, and the downstream job scheduler by AWS
    Step Functions.

    Note
    ----
    ``ReportService`` has no database dependency — it communicates
    purely with SQS via boto3. Consequently this endpoint does NOT
    take a ``db: AsyncSession`` parameter, unlike every other POST/PUT
    endpoint in this API.
    """
    logger.info(
        "POST /reports/submit initiated",
        extra={
            "user_id": current_user.user_id,
            "report_type": request.report_type.value if hasattr(request.report_type, "value") else request.report_type,
            "start_date": request.start_date,
            "end_date": request.end_date,
            "endpoint": "report_submit",
        },
    )
    service = ReportService()
    response = await service.submit_report(request)
    if response.confirm != "Y":
        # SQS publish failure / validation failure. Translate to
        # HTTPException(400) so the ABEND-DATA envelope wraps the
        # message consistently with the rest of the API.
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=response.message or "Report submission failed",
        )
    return response


__all__ = ["router"]
