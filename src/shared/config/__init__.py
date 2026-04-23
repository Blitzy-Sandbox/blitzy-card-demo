# ============================================================================
# Source: Mainframe configuration patterns (JCL, CICS) -> Python/AWS configuration
#         app/cpy/COCOM01Y.cpy (CICS COMMAREA carrying CDEMO-USER-ID,
#         CDEMO-USER-TYPE, CDEMO-FROM-TRANID, etc. across CICS program
#         transfers) -> environment-based Settings + JWT tokens + AWS
#         service client factories
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
"""Configuration module for CardDemo cloud-native application.

Converted from mainframe configuration patterns (JCL PARM values, CICS file
identifiers, VSAM dataset names) to Python environment-based settings and
AWS service client factories.

Package Role
------------
This package is the centralized configuration hub shared by BOTH workload
layers of the modernized CardDemo application:

* **API layer** (``src/api/``) -- FastAPI services deployed on AWS ECS
  Fargate. Imports :class:`Settings` and the AWS client factories to
  configure its database engine, JWT authentication, SQS report-
  submission producer, S3 statement reader, and Secrets Manager
  credential retrieval.
* **Batch layer** (``src/batch/``) -- PySpark jobs deployed on AWS Glue
  5.1. Imports :class:`Settings` and the AWS client factories to build
  JDBC connection strings (via :func:`get_database_credentials`), write
  statement / report / reject artifacts to S3 (via
  :func:`get_s3_client`), and orchestrate pipeline transitions with Step
  Functions (via :func:`get_glue_client`).

Mainframe -> Cloud Mapping
--------------------------
* **CICS COMMAREA** (``app/cpy/COCOM01Y.cpy``) -- the 96-byte
  ``CARDDEMO-COMMAREA`` structure carrying ``CDEMO-USER-ID``,
  ``CDEMO-USER-TYPE`` (``A``=Admin, ``U``=User), ``CDEMO-FROM-TRANID``,
  and ``CDEMO-TO-PROGRAM`` across CICS program transfers is replaced by
  stateless JWT tokens. The JWT signing parameters
  (``Settings.JWT_SECRET_KEY``, ``Settings.JWT_ALGORITHM``,
  ``Settings.JWT_ACCESS_TOKEN_EXPIRE_MINUTES``) are declared on the
  :class:`Settings` class re-exported from this package.
* **JCL DD statements** (e.g., ``//ACCTFILE DD
  DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS`` from ``app/jcl/ACCTFILE.jcl``)
  -- replaced by ``Settings.DATABASE_URL`` / ``Settings.DATABASE_URL_SYNC``
  Aurora PostgreSQL connection strings.
* **JCL JOB card parameters** (``CLASS=A``, ``MSGCLASS=0``, etc.) --
  replaced by ``Settings.AWS_REGION``, ``Settings.S3_BUCKET_NAME``,
  ``Settings.SQS_QUEUE_URL``, ``Settings.GLUE_JOB_ROLE_ARN``.
* **CICS TDQ WRITEQ JOBS** (``app/cbl/CORPT00C.cbl``) -- replaced by
  :func:`get_sqs_client` publishing to an SQS FIFO queue.
* **GDG output** (``app/jcl/DEFGDGB.jcl``, ``REPTFILE.jcl``,
  ``DALYREJS.jcl``) -- replaced by :func:`get_s3_client` writing
  versioned S3 objects.
* **z/OS RACF credential storage** -- replaced by
  :func:`get_secrets_manager_client` and
  :func:`get_database_credentials` retrieving Aurora PostgreSQL
  credentials from AWS Secrets Manager.
* **JES2 batch submission** (``app/jcl/POSTTRAN.jcl``, ``INTCALC.jcl``,
  ``COMBTRAN.jcl``, ``CREASTMT.JCL``, ``TRANREPT.jcl``) -- replaced by
  :func:`get_glue_client` starting AWS Glue jobs via Step Functions.

Public API
----------
Import convenience pattern::

    from src.shared.config import (
        Settings,
        get_settings,
        get_s3_client,
        get_sqs_client,
        get_secrets_manager_client,
        get_glue_client,
        get_database_credentials,
    )

Alternatively, submodule-direct imports remain fully supported for
callers that prefer explicit paths (and are used throughout
``src/api/middleware/auth.py``, ``src/api/services/auth_service.py``, and
``src/batch/common/glue_context.py``)::

    from src.shared.config.settings import Settings
    from src.shared.config.aws_config import get_s3_client

Usage -- Cached Settings Singleton
----------------------------------
The :func:`get_settings` helper returns a module-level cached
:class:`Settings` instance so environment variables are parsed exactly
once per process. This is the preferred accessor in FastAPI
``Depends(get_settings)`` injections and in PySpark driver-side
initialization::

    from src.shared.config import get_settings

    settings = get_settings()             # parses env vars on first call
    engine = create_async_engine(
        settings.DATABASE_URL,
        pool_size=settings.DB_POOL_SIZE,
    )

Tests that need to override configuration should construct a fresh
``Settings(...)`` instance with explicit keyword arguments rather than
calling :func:`get_settings`, which is cached for the lifetime of the
test process.

Import Safety
-------------
Importing :mod:`src.shared.config` does NOT instantiate :class:`Settings`
and therefore does NOT read or validate any environment variable.
Validation only occurs when :func:`get_settings` (or :class:`Settings`
itself) is called. This keeps package import cheap and allows tooling
(``mypy``, ``ruff``, ``pytest`` collection) to import the package even
when required env vars (``DATABASE_URL``, ``DATABASE_URL_SYNC``,
``JWT_SECRET_KEY``) are not yet set.

See Also
--------
* AAP Section 0.4.1 -- Refactored Structure Planning
  (``src/shared/config/`` layout).
* AAP Section 0.5.1 -- File-by-File Transformation Plan.
* AAP Section 0.6.1 -- Core / Shared Dependencies (``boto3``,
  ``pydantic-settings``, ``functools``).
* AAP Section 0.7.2 -- Security Requirements (IAM roles, Secrets
  Manager, no hardcoded credentials).
"""

# Source: Mainframe configuration patterns (JCL, CICS) -> Python/AWS configuration

from __future__ import annotations

# ----------------------------------------------------------------------------
# External standard-library imports.
#
# ``functools.lru_cache`` is used to memoize the result of
# :func:`get_settings` so the ``Settings()`` constructor (and its
# environment-variable parsing / validation) executes exactly once per
# process regardless of how many callers invoke :func:`get_settings`.
# ----------------------------------------------------------------------------
from functools import lru_cache

# ----------------------------------------------------------------------------
# Internal re-exports from the sibling submodules.
#
# These imports intentionally occur at module top-level (NOT lazily
# inside functions) so that the symbols ``Settings``, ``get_s3_client``,
# etc. are attributes of the :mod:`src.shared.config` package as soon as
# the package is imported. This enables the convenience import pattern
# documented in the module docstring::
#
#     from src.shared.config import Settings, get_s3_client
#
# Safety note: these top-level imports do NOT trigger ``Settings()``
# instantiation. They merely bind the class object and the factory
# function objects into this package's namespace. Actual environment-
# variable parsing / validation happens only when a caller invokes
# ``Settings()`` (directly or via :func:`get_settings`) or one of the
# AWS client factories (which lazily import Settings inside their own
# bodies -- see ``aws_config._get_default_region``).
# ----------------------------------------------------------------------------
from src.shared.config.aws_config import (
    get_database_credentials,
    get_glue_client,
    get_s3_client,
    get_secrets_manager_client,
    get_sqs_client,
)
from src.shared.config.settings import Settings


# ----------------------------------------------------------------------------
# Cached Settings accessor.
#
# The ``@lru_cache`` decorator creates a process-level singleton: the
# first call to ``get_settings()`` instantiates ``Settings()`` (which
# reads environment variables and raises ``ValidationError`` on missing
# required values), and every subsequent call returns the exact same
# instance without re-reading the environment.
#
# Using a cached accessor (rather than a module-level ``settings =
# Settings()`` line) has two important benefits:
#
# 1. **Deferred validation.** Importing ``src.shared.config`` does NOT
#    trigger environment-variable validation. This keeps tooling
#    (``mypy``, ``pytest`` collection, ``ruff``) working in environments
#    that have not yet configured runtime secrets.
#
# 2. **Test-friendly.** Tests that need non-default settings can either
#    clear the cache (``get_settings.cache_clear()``) and reset env
#    vars, or construct ``Settings(...)`` directly with explicit keyword
#    arguments -- bypassing the cache entirely.
#
# The bare form ``@lru_cache`` (without parentheses) is used rather than
# the parenthesized form ``@lru_cache()``. Both forms are valid in Python
# 3.11 and behave identically for parameter-less functions, but the bare
# form is the modern / ruff-preferred style (pyupgrade rule UP011) and
# matches the ``functools.lru_cache`` documentation recommendation since
# Python 3.8.
# ----------------------------------------------------------------------------
@lru_cache
def get_settings() -> Settings:
    """Return cached application settings instance.

    Returns a process-level singleton :class:`Settings` instance so that
    environment variables (``DATABASE_URL``, ``JWT_SECRET_KEY``,
    ``AWS_REGION``, etc.) are parsed exactly once per process. The
    singleton is backed by :func:`functools.lru_cache` with the default
    unbounded cache.

    On the first call, the underlying :class:`Settings` constructor reads
    environment variables (and the ``.env`` file in local development),
    validates required fields, and raises
    :class:`pydantic.ValidationError` if any required field
    (``DATABASE_URL``, ``DATABASE_URL_SYNC``, ``JWT_SECRET_KEY``) is
    missing -- failing fast rather than at first use.

    On subsequent calls, the cached instance is returned without
    re-reading environment variables.

    Returns
    -------
    Settings
        The process-level cached :class:`Settings` instance.

    Raises
    ------
    pydantic.ValidationError
        If any required field has no default and is not supplied via
        environment variables or the ``.env`` file. This only occurs on
        the first call within a process.
    """
    return Settings()


# ----------------------------------------------------------------------------
# Public API surface of the package.
#
# Listing names in ``__all__`` controls the behavior of
# ``from src.shared.config import *`` and documents the intended public
# API for consumers and tooling (IDE auto-import, static analyzers).
#
# The listed names are the same ones imported and defined above:
#   * ``Settings``                   -- re-exported from ``settings.py``
#   * ``get_settings``               -- defined here (cached factory)
#   * ``get_s3_client``              -- re-exported from ``aws_config.py``
#   * ``get_sqs_client``             -- re-exported from ``aws_config.py``
#   * ``get_secrets_manager_client`` -- re-exported from ``aws_config.py``
#   * ``get_glue_client``            -- re-exported from ``aws_config.py``
#   * ``get_database_credentials``   -- re-exported from ``aws_config.py``
# ----------------------------------------------------------------------------
__all__ = [
    "Settings",
    "get_settings",
    "get_s3_client",
    "get_sqs_client",
    "get_secrets_manager_client",
    "get_glue_client",
    "get_database_credentials",
]
