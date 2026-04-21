# ============================================================================
# Source: COBOL online CICS programs (app/cbl/CO*.cbl)
#         — Mainframe-to-Cloud migration
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
"""Service layer package for the CardDemo FastAPI application.

This package provides the business-logic service classes that sit
between the API routers (``src/api/routers/``) and the shared ORM
models (``src/shared/models/``). Each service module corresponds to
one or more online CICS COBOL programs from ``app/cbl/CO*.cbl``:

* :mod:`src.api.services.auth_service` — converted from
  ``app/cbl/COSGN00C.cbl`` (sign-on / authentication, Feature F-001).
  Replaces the CICS ``READ FILE('USRSEC')`` + cleartext password
  comparison + COMMAREA population with an async SQLAlchemy query,
  BCrypt verification, and JWT token issuance.

Design Notes
------------
* **Service Layer pattern** — each service class encapsulates the
  business rules of its corresponding COBOL PROCEDURE DIVISION
  paragraph(s). Routers are thin adapters that translate HTTP to
  service calls; services own the transaction boundaries and
  side-effect ordering (replacing CICS ``SYNCPOINT`` semantics).
* **Dependency Injection** — every service takes an
  :class:`sqlalchemy.ext.asyncio.AsyncSession` in its constructor,
  replacing CICS file handles to VSAM datasets.
* **No direct router imports** — services must not import from
  ``src.api.routers``; the dependency direction is router → service
  → model (one-way).

See Also
--------
AAP §0.4.1 "Refactored Structure Planning" — service layer specification
AAP §0.5.1 "File-by-File Transformation Plan" — online CICS → service mapping
"""

__all__: list[str] = []
