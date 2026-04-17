# ============================================================================
# Source: N/A — Cloud-native configuration package (Mainframe-to-Cloud migration)
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
"""Environment configuration and AWS service client factories.

This package provides the two foundational configuration components used
by both the FastAPI API layer and the PySpark batch layer:

* :mod:`src.shared.config.settings` — a
  :class:`pydantic_settings.BaseSettings` subclass (:class:`Settings`)
  that loads all environment-driven configuration (database URLs, JWT
  secrets, AWS region, S3 bucket, SQS queue, Glue role ARN, logging
  level, etc.) with fail-fast validation on missing required values.

* :mod:`src.shared.config.aws_config` — thin stateless boto3 ``client()``
  factory functions (:func:`get_s3_client`, :func:`get_sqs_client`,
  :func:`get_secrets_manager_client`) that respect the
  :attr:`Settings.AWS_ENDPOINT_URL` override for local development
  against LocalStack while routing to real AWS endpoints in production.

Mainframe → Cloud Mapping
-------------------------
The configuration layer replaces a patchwork of mainframe conventions
with a single, environment-variable-driven pattern:

===================================  =============================================
z/OS / CICS / JCL construct          Cloud-native replacement
===================================  =============================================
JCL ``PROC`` parameters              Environment variables loaded by ``Settings``
CICS ``SIT`` (System Init Table)     ``.env`` / ECS task-definition secrets
``RACF`` stored credentials          AWS Secrets Manager (credentials fetched at
                                     startup via :func:`get_secrets_manager_client`)
``JCL SYSIN DD *`` inline config     ``pydantic-settings`` typed ``Settings`` fields
GDG dataset names                    ``Settings.S3_BUCKET_NAME`` + S3 key prefix
CICS TDQ destination name            ``Settings.SQS_QUEUE_URL``
JES2 JOB class (e.g., ``CLASS=A``)   ``Settings.GLUE_JOB_ROLE_ARN``
===================================  =============================================

Design Notes
------------
* **No direct ``os.environ`` reads**: All environment variable access
  goes through :class:`Settings`. This gives us typed validation,
  centralized defaults, clear error messages on missing required
  variables, and a single seam for tests to inject overrides. See QA
  Checkpoint 1 Feature 6 which verified zero ``os.environ`` / ``os.getenv``
  calls inside ``src/shared/config/``.

* **No hardcoded secrets**: Zero credentials, API keys, or database
  passwords appear in source. Production secrets are retrieved at
  runtime from AWS Secrets Manager (using the Secrets Manager client
  factory exported by :mod:`aws_config`). Development secrets come from
  the local ``.env`` file (which is ``.gitignore``-d).

* **Lazy loading**: This package init performs NO imports of its
  submodules. Consumers must import what they need explicitly::

      from src.shared.config.settings import Settings
      from src.shared.config.aws_config import (
          get_s3_client,
          get_sqs_client,
          get_secrets_manager_client,
      )

  This mirrors the pattern used by :mod:`src.shared.utils` and
  :mod:`src.shared` and keeps import cost minimal for short-lived
  contexts such as AWS Glue worker startup.

* **Python 3.11+**: Aligned with the AWS Glue 5.1 runtime (Python 3.11)
  and the FastAPI container image (``python:3.11-slim``).

See Also
--------
AAP §0.4.1 — Refactored Structure Planning (``src/shared/config/`` layout)
AAP §0.5.1 — File-by-File Transformation Plan (``settings.py`` / ``aws_config.py``)
AAP §0.6.1 — Key Public Packages (``boto3``, ``pydantic-settings``)
AAP §0.6.2 — AWS Service Dependencies (Secrets Manager, S3, SQS)
AAP §0.7.2 — Security Requirements (Secrets Manager, no hardcoded credentials)
"""

# ----------------------------------------------------------------------------
# Public submodule re-export list.
#
# Only the two submodule names are advertised as the public API of this
# package. Individual symbols (the ``Settings`` class, the three
# ``get_*_client`` factory functions) must be imported from their
# specific submodule rather than from the package root. This keeps the
# package init side-effect-free.
#
# NOTE: ``__all__`` containing submodule names does NOT cause those
# submodules to be imported automatically. It only controls what
# ``from src.shared.config import *`` would pull in. The lazy-loading
# contract is preserved.
# ----------------------------------------------------------------------------
__all__: list[str] = [
    "settings",
    "aws_config",
]
