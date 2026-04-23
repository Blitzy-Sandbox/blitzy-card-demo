# ============================================================================
# Source: COBOL copybook library (app/cpy/) — Mainframe-to-Cloud migration
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
"""CardDemo — Shared Module Package.

Foundational shared library used by BOTH workload layers of the CardDemo
cloud-native application:

* ``src.api``   — FastAPI REST/GraphQL endpoints deployed on AWS ECS Fargate
                  (replaces the 18 CICS online COBOL programs).
* ``src.batch`` — PySpark ETL jobs deployed on AWS Glue 5.1
                  (replaces the 10 batch COBOL programs).

Converted from COBOL copybook library (``app/cpy/``) and BMS symbolic maps
(``app/cpy-bms/``) as part of the mainframe-to-cloud modernization effort.

Subpackages
-----------
models
    SQLAlchemy ORM entities mapping the 11 Aurora PostgreSQL tables that
    replace the 10 VSAM KSDS datasets and 3 alternate indexes.
    Derived from ``app/cpy/CV*.cpy`` record layouts.

schemas
    Pydantic v2 request/response models defining the API contracts.
    Derived from ``app/cpy-bms/*.CPY`` symbolic map copybooks.

constants
    COBOL-derived message text, lookup codes, and menu/navigation
    configuration. Derived from ``app/cpy/CSMSG0*Y.cpy``,
    ``app/cpy/COTTL01Y.cpy``, ``app/cpy/CSLKPCDY.cpy``,
    ``app/cpy/COMEN02Y.cpy``, and ``app/cpy/COADM02Y.cpy``.

utils
    Shared utility functions for date validation, string processing,
    and ``decimal.Decimal`` arithmetic preserving COBOL
    ``PIC S9(n)V99`` semantics. Derived from ``app/cbl/CSUTLDTC.cbl``,
    ``app/cpy/CSDAT01Y.cpy``, ``app/cpy/CSUTLDWY.cpy``,
    ``app/cpy/CSUTLDPY.cpy``, and ``app/cpy/CSSTRPFY.cpy``.

config
    Environment-driven settings (``pydantic-settings.BaseSettings``) and
    AWS service client factories (Secrets Manager, SQS, S3).

Design Notes
------------
* This package module is intentionally **minimal**. It does NOT eagerly
  import submodules — consumers must import what they need explicitly
  (lazy loading pattern). This avoids circular-import issues between the
  API and batch layers, minimizes startup time for AWS Glue jobs, and
  prevents pulling heavy optional dependencies (e.g., SQLAlchemy async
  engines) into short-lived batch contexts.
* No floating-point arithmetic: all monetary fields use
  ``decimal.Decimal`` with explicit scale to preserve the COBOL
  ``PIC S9(n)V99`` contract (see AAP §0.7.2 Financial Precision).
* Python 3.11+ only (aligned with AWS Glue 5.1 runtime and FastAPI
  deployment baseline).

See Also
--------
AAP §0.4.1 — Refactored Structure Planning
AAP §0.5.1 — File-by-File Transformation Plan
AAP §0.7.1 — Refactoring-Specific Rules
"""

# ----------------------------------------------------------------------------
# Package version.
#
# Distinct from the distribution version declared in ``pyproject.toml``
# (``carddemo``). This ``__version__`` tracks the API contract / schema
# compatibility of the shared module itself so that consumers (e.g., the
# batch Glue jobs, which may be pinned to a specific Spark container
# image) can detect breaking changes in the shared copybook-derived
# models.
#
# Semantic versioning: MAJOR.MINOR.PATCH
#   MAJOR — Breaking changes to shared models/schemas/constants
#   MINOR — Backward-compatible additions
#   PATCH — Bug fixes with no contract changes
# ----------------------------------------------------------------------------
__version__: str = "1.0.0"

# Explicit re-export list — only ``__version__`` is considered part of the
# public API of this package module. All other symbols must be imported
# from their specific submodules (e.g., ``from src.shared.models.account
# import Account``).
__all__ = ["__version__"]
