# ============================================================================
# Source: N/A — Python project root package marker (Mainframe-to-Cloud migration)
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
"""CardDemo — Python source tree root package.

This is the top-level package of the CardDemo Python application source
tree. It intentionally contains **no executable code** — its sole purpose
is to establish an explicit package boundary (PEP 328 / PEP 366) so that
absolute imports of the form ``from src.shared.models.account import
Account`` resolve unambiguously across every execution context:

* **Local development** — ``pytest``, ``uvicorn``, ``python -m`` invocations
  from the repository root.
* **FastAPI / ECS Fargate container** — the ``Dockerfile`` ``COPY src/ /app/src/``
  step preserves the package structure inside ``/app``.
* **AWS Glue 5.1 PySpark workers** — Glue ``--extra-py-files`` ships the
  ``src/`` tree as a ``.zip`` archive and relies on explicit package markers
  for module resolution by the managed Python 3.11 runtime.
* **Alembic migrations** — ``env.py`` uses ``from src.shared.models import
  Base`` to register metadata.

Subpackages
-----------
shared
    Foundational shared library used by BOTH workload layers. Contains
    SQLAlchemy ORM models, Pydantic v2 schemas, COBOL-derived constants,
    utility functions, and environment configuration. Translated from the
    28 COBOL copybooks (``app/cpy/``) and 17 BMS symbolic maps
    (``app/cpy-bms/``).

api
    FastAPI REST and GraphQL (Strawberry) endpoint modules deployed as a
    Docker container on AWS ECS Fargate. Translated from the 18 CICS
    online COBOL programs (``app/cbl/CO*.cbl``).
    *Not yet populated at this checkpoint.*

batch
    PySpark ETL job scripts deployed on AWS Glue 5.1 (Spark 3.5.6,
    Python 3.11) and orchestrated by AWS Step Functions. Translated from
    the 10 batch COBOL programs (``app/cbl/CB*.cbl``) and the 29 JCL job
    members (``app/jcl/``). *Not yet populated at this checkpoint.*

Design Notes
------------
* **Explicit package marker**: Although Python 3.3+ supports PEP 420
  implicit namespace packages (directories without ``__init__.py``), this
  project explicitly declares ``__init__.py`` at every package level to
  preserve compatibility with tooling that predates or does not fully
  support namespace packages — including older editable-install
  workflows, ``pytest --rootdir`` auto-discovery edge cases, and certain
  static analyzers. See QA Checkpoint 1 Finding 3 (``__init__.py``
  packaging).

* **No eager imports**: This module performs NO imports of subpackages.
  Consumers must import what they need explicitly (e.g.,
  ``from src.shared.config.settings import Settings``). This mirrors the
  lazy-loading pattern used throughout the codebase and avoids
  circular-import risk between the API and batch layers.

* **Apache License 2.0**: This file, like every other source file in the
  CardDemo repository, is governed by the Apache License 2.0 — inherited
  from the original AWS CardDemo mainframe reference application.

See Also
--------
AAP §0.4.1 — Refactored Structure Planning
AAP §0.5.1 — File-by-File Transformation Plan
"""

# ----------------------------------------------------------------------------
# Explicit re-export list.
#
# Intentionally empty — ``src`` is a pure namespace package marker and
# exposes no symbols of its own. All consumers must import from specific
# subpackages, e.g.::
#
#     from src.shared.config.settings import Settings
#     from src.shared.models.account import Account
#
# ``from src import *`` would correctly import nothing.
# ----------------------------------------------------------------------------
__all__: list[str] = []
