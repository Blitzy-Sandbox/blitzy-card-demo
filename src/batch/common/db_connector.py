# ============================================================================
# Source: app/jcl/POSTTRAN.jcl, app/jcl/INTCALC.jcl, app/jcl/CREASTMT.JCL,
#         app/jcl/TRANREPT.jcl, app/jcl/ACCTFILE.jcl, app/jcl/TRANBKP.jcl
#         — JCL VSAM DD statements (DISP=SHR / DISP=(NEW,CATLG,DELETE))
#         → PySpark JDBC connection factory for Aurora PostgreSQL
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
"""JDBC connection factory for Aurora PostgreSQL, used by all PySpark Glue jobs.

Replaces VSAM file I/O patterns defined through JCL DD statements:

* ``TRANFILE DD DISP=SHR, DSN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS``
  → PostgreSQL ``transactions`` table
* ``ACCTFILE DD DISP=SHR, DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS``
  → PostgreSQL ``accounts`` table
* ``CARDFILE DD DISP=SHR, DSN=AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS``
  → PostgreSQL ``cards`` table
* ``CUSTFILE DD DISP=SHR, DSN=AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS``
  → PostgreSQL ``customers`` table
* ``XREFFILE DD DISP=SHR, DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS``
  → PostgreSQL ``card_cross_references`` table
* ``TCATBALF DD DISP=SHR, DSN=AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS``
  → PostgreSQL ``transaction_category_balances`` table
* ``DISCGRP  DD DISP=SHR, DSN=AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS``
  → PostgreSQL ``disclosure_groups`` table
* ``TRANTYPE DD DISP=SHR, DSN=AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS``
  → PostgreSQL ``transaction_types`` table
* ``TRANCATG DD DISP=SHR, DSN=AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS``
  → PostgreSQL ``transaction_categories`` table
* ``DALYTRAN DD DISP=SHR, DSN=AWS.M2.CARDDEMO.DALYTRAN.PS``
  → PostgreSQL ``daily_transactions`` table
* ``USRSEC`` (CICS sign-on USRSEC VSAM file)
  → PostgreSQL ``user_security`` table

Mainframe-to-Cloud mapping for data access
------------------------------------------
On the mainframe every batch program declares DD statements to bind the
COBOL ``SELECT ... ASSIGN TO DD-NAME`` file handles to physical VSAM
KSDS datasets. ``DISP=SHR`` opens a cluster for shared read/update;
``DISP=(NEW,CATLG,DELETE)`` allocates a new output dataset (sequential
for reject files, ``(+1)`` for GDG generations). COBOL then issues
``READ``/``WRITE``/``REWRITE``/``DELETE`` against those handles.

In the target architecture all ten VSAM KSDS clusters plus the USRSEC
VSAM file are consolidated into a single AWS Aurora PostgreSQL database.
Each COBOL DD statement becomes a PySpark JDBC read/write against the
corresponding PostgreSQL table. This module provides the connection
factory used by every PySpark Glue job in ``src/batch/jobs/`` to obtain
JDBC URLs, connection options, and convenience DataFrame read/write
helpers.

Credential management
---------------------
Credentials are retrieved from AWS Secrets Manager via
:func:`src.shared.config.aws_config.get_database_credentials` — replacing
the z/OS RACF-protected dataset profiles that controlled access to the
original VSAM clusters (AAP §0.7.2 Security Requirements). For local
development (``docker-compose`` with PostgreSQL 16) the factory falls
back gracefully to :attr:`Settings.DATABASE_URL_SYNC` when Secrets
Manager is unavailable. No AWS access keys, database passwords, or
other secrets are ever hardcoded in this module.

IAM authentication
------------------
The module architecture supports zero-password IAM-based authentication
to Aurora PostgreSQL. When enabled by the Glue job execution role,
boto3 produces a short-lived authentication token that is used as the
password in the JDBC connection options — Aurora validates the token
against the IAM service. No code changes are required in this module to
switch between password-based (local dev) and IAM-based (production)
authentication; the secret merely contains the appropriate credential
material.

Public API
----------
* :data:`VSAM_TABLE_MAP` — canonical mapping from mainframe VSAM dataset
  short-names (as they appear in JCL DD statements) to PostgreSQL table
  names (as they appear in the Aurora PostgreSQL schema).
* :func:`get_jdbc_url` — constructs a ``jdbc:postgresql://host:port/dbname``
  URL string.
* :func:`get_connection_options` — returns the full dict of JDBC
  connection options (url, driver, user, password, and optionally
  dbtable) ready to be passed to PySpark's ``DataFrameReader.options()``
  or ``DataFrameWriter.options()``.
* :func:`get_table_name` — translates a VSAM dataset short-name to its
  PostgreSQL table name via :data:`VSAM_TABLE_MAP`.
* :func:`read_table` — convenience function that reads a full PostgreSQL
  table into a PySpark DataFrame (replaces VSAM ``DISP=SHR`` reads).
* :func:`write_table` — convenience function that writes a PySpark
  DataFrame to a PostgreSQL table (replaces VSAM
  ``DISP=(NEW,CATLG,DELETE)`` writes and VSAM REWRITE patterns).

Source
------
``app/jcl/POSTTRAN.jcl``, ``app/jcl/INTCALC.jcl``,
``app/jcl/CREASTMT.JCL``, ``app/jcl/TRANREPT.jcl``,
``app/jcl/ACCTFILE.jcl``, ``app/jcl/TRANBKP.jcl``.

See Also
--------
AAP §0.4.1 — Refactored Structure Planning
AAP §0.4.4 — Key Architectural Decisions (Batch Layer — AWS Glue)
AAP §0.5.1 — File-by-File Transformation Plan
AAP §0.6.2 — AWS Service Dependencies (Aurora PostgreSQL, Secrets Manager)
AAP §0.7.2 — Security Requirements (IAM roles, Secrets Manager,
             no hardcoded credentials)
"""

from __future__ import annotations

import logging
from typing import Any

# ----------------------------------------------------------------------------
# Module logger. Uses the module's ``__name__`` so CloudWatch log streams
# clearly identify ``src.batch.common.db_connector`` as the source of
# JDBC connection diagnostics, Secrets Manager fallback warnings, and
# JDBC table read/write operations. Structured JSON formatting is
# provided by the ``JsonFormatter`` installed by
# :func:`src.batch.common.glue_context.init_glue` at job startup.
# Replaces JCL ``SYSPRINT DD SYSOUT=*`` / ``SYSOUT DD SYSOUT=*``.
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)


# ============================================================================
# VSAM dataset name → PostgreSQL table name mapping
# ============================================================================
#
# Derived from JCL DD statements across all five pipeline JCLs:
#   POSTTRAN.jcl — TRANFILE, DALYTRAN, XREFFILE, ACCTFILE, TCATBALF
#   INTCALC.jcl  — TCATBALF, XREFFILE, ACCTFILE, DISCGRP, TRANSACT
#   CREASTMT.JCL — TRNXFILE, XREFFILE, ACCTFILE, CUSTFILE, STMTFILE
#   TRANREPT.jcl — TRANFILE, CARDXREF, TRANTYPE, TRANCATG, TRANREPT
#   ACCTFILE.jcl — ACCTDATA (VSAM provisioning source)
#
# Canonical "short names" on the left are the 8-character mainframe
# dataset qualifiers that appear in DSN=AWS.M2.CARDDEMO.<SHORT>.VSAM.KSDS
# references. The right-hand column is the snake_case PostgreSQL table
# name defined in ``db/migrations/V1__schema.sql``.
#
# All 11 VSAM datasets that have a PostgreSQL counterpart in the target
# architecture are enumerated here. USRSEC (USER SECURITY) originates
# from the CICS sign-on flow (see CSUSR01Y.cpy / COSGN00C.cbl) and is
# included so batch user-administration scripts can resolve it.
# ----------------------------------------------------------------------------
VSAM_TABLE_MAP: dict[str, str] = {
    # JCL: //ACCTFILE DD DISP=SHR, DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS
    # Source copybook: app/cpy/CVACT01Y.cpy (Account 300-byte record)
    "ACCTDATA": "accounts",
    # JCL: //CARDFILE DD DISP=SHR, DSN=AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS
    # Source copybook: app/cpy/CVACT02Y.cpy (Card 150-byte record)
    "CARDDATA": "cards",
    # JCL: //CUSTFILE DD DISP=SHR, DSN=AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS
    # Source copybook: app/cpy/CVCUS01Y.cpy (Customer 500-byte record)
    "CUSTDATA": "customers",
    # JCL: //XREFFILE DD DISP=SHR, DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS
    # Source copybook: app/cpy/CVACT03Y.cpy (Cross-reference 50-byte)
    "CARDXREF": "card_cross_references",
    # JCL: //TRANFILE DD DISP=SHR, DSN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS
    # Source copybook: app/cpy/CVTRA05Y.cpy (Transaction 350-byte record)
    "TRANSACT": "transactions",
    # JCL: //TCATBALF DD DISP=SHR, DSN=AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS
    # Source copybook: app/cpy/CVTRA01Y.cpy (Category balance 50-byte)
    "TCATBALF": "transaction_category_balances",
    # JCL: //DISCGRP  DD DISP=SHR, DSN=AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS
    # Source copybook: app/cpy/CVTRA02Y.cpy (Disclosure group 50-byte)
    "DISCGRP": "disclosure_groups",
    # JCL: //TRANTYPE DD DISP=SHR, DSN=AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS
    # Source copybook: app/cpy/CVTRA03Y.cpy (Transaction type 60-byte)
    "TRANTYPE": "transaction_types",
    # JCL: //TRANCATG DD DISP=SHR, DSN=AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS
    # Source copybook: app/cpy/CVTRA04Y.cpy (Transaction category 60-byte)
    "TRANCATG": "transaction_categories",
    # JCL: //DALYTRAN DD DISP=SHR, DSN=AWS.M2.CARDDEMO.DALYTRAN.PS
    # Source copybook: app/cpy/CVTRA06Y.cpy (Daily staging 350-byte)
    "DALYTRAN": "daily_transactions",
    # USRSEC (CICS sign-on USRSEC VSAM file — no dedicated JCL, managed
    # via DUSRSECJ.jcl at provisioning time)
    # Source copybook: app/cpy/CSUSR01Y.cpy (User security 80-byte)
    "USRSEC": "user_security",
}


# ============================================================================
# Public re-export list.
#
# Only the six names below are part of the public API of this module.
# Helper functions — if any — are intentionally kept private (leading
# underscore). Consumers should ``from src.batch.common.db_connector
# import ...`` only the names listed here.
# ============================================================================
__all__ = [
    "VSAM_TABLE_MAP",
    "get_jdbc_url",
    "get_connection_options",
    "get_table_name",
    "read_table",
    "write_table",
]


# ============================================================================
# JDBC URL construction
# ============================================================================


def get_jdbc_url(
    host: str | None = None,
    port: str | None = None,
    dbname: str | None = None,
) -> str:
    """Construct a JDBC URL for Aurora PostgreSQL.

    Replaces JCL VSAM DSN references with JDBC connectivity.

    Resolution strategy (in precedence order):

    1. **Explicit arguments** — When all three of ``host``, ``port``,
       and ``dbname`` are provided, the URL is composed directly. This
       is the preferred pathway for Glue jobs that have already
       retrieved credentials through another mechanism (e.g., Glue
       connection properties).
    2. **AWS Secrets Manager** — When any argument is ``None``, the
       factory retrieves the full credential dict via
       :func:`src.shared.config.aws_config.get_database_credentials`.
       This is the production pathway: the ECS task role (for API
       consumers) or the Glue job execution role (for batch consumers)
       grants IAM access to the Secrets Manager secret named by
       :attr:`Settings.DB_SECRET_NAME` (default:
       ``"carddemo/aurora-credentials"``).
    3. **Local Settings fallback** — If Secrets Manager raises *any*
       exception (missing secret, IAM denial, botocore transport
       failure, LocalStack unavailable), the factory emits a WARNING
       log and falls back to parsing :attr:`Settings.DATABASE_URL_SYNC`
       (``postgresql+psycopg2://user:pass@host:port/dbname``) — the
       connection string used by ``docker-compose.yml`` for local
       PostgreSQL 16 development.

    Replaces
    --------
    The mainframe equivalent is the ``DSN=AWS.M2.CARDDEMO.<name>.VSAM.KSDS``
    clause on every DD statement in the source JCLs — e.g.,
    ``//ACCTFILE DD DISP=SHR, DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS``
    (``ACCTFILE.jcl`` line 59). The z/OS catalog resolves the DSN to a
    physical VSAM cluster on DASD; the Aurora PostgreSQL equivalent
    is reached via the returned JDBC URL.

    Parameters
    ----------
    host : str | None, optional
        Aurora PostgreSQL hostname (e.g.,
        ``"carddemo-aurora.cluster-xyz.us-east-1.rds.amazonaws.com"``).
        When ``None``, resolved from Secrets Manager / Settings.
    port : str | None, optional
        Aurora PostgreSQL port (e.g., ``"5432"``). When ``None``,
        resolved from Secrets Manager / Settings.
    dbname : str | None, optional
        Aurora PostgreSQL database name (e.g., ``"carddemo"``). When
        ``None``, resolved from Secrets Manager / Settings.

    Returns
    -------
    str
        A JDBC URL of the form ``jdbc:postgresql://{host}:{port}/{dbname}``.
        This is the exact URL format expected by the PostgreSQL JDBC
        driver (``org.postgresql.Driver``) bundled with AWS Glue 5.1 /
        Apache Spark 3.5.6.
    """
    # ------------------------------------------------------------------
    # Pathway 1 — explicit arguments
    # ------------------------------------------------------------------
    # When all three components are supplied the function is trivially
    # pure: no AWS or Settings lookup is performed. This pathway is
    # useful for ad-hoc scripts and unit tests that want to inject
    # their own connection target (e.g., a Testcontainers PostgreSQL
    # instance).
    if host and port and dbname:
        url = f"jdbc:postgresql://{host}:{port}/{dbname}"
        logger.info(
            "Constructed JDBC URL: jdbc:postgresql://%s:%s/%s",
            host,
            port,
            dbname,
        )
        return url

    # ------------------------------------------------------------------
    # Pathway 2 — AWS Secrets Manager
    # ------------------------------------------------------------------
    # Lazy import of aws_config to avoid circular dependency between
    # the batch module and shared config module. The import is placed
    # inside the function body (not at the module top) so that static
    # analysis tools and unit tests can import db_connector without
    # requiring boto3 to be importable in the current environment —
    # matches the pattern documented in AAP §0.7.3 (Minimal change
    # clause) and the lazy-import guidance in
    # src/shared/config/aws_config.py.
    try:
        from src.shared.config.aws_config import get_database_credentials

        creds = get_database_credentials()
        url = f"jdbc:postgresql://{creds['host']}:{creds['port']}/{creds['dbname']}"
        logger.info("Constructed JDBC URL from Secrets Manager")
        return url
    except Exception:
        # ------------------------------------------------------------------
        # Pathway 3 — local Settings fallback
        # ------------------------------------------------------------------
        # Any Secrets Manager failure (missing secret, IAM denial,
        # LocalStack down, JSON decode error) drops through to local
        # Settings. The WARNING is emitted — not ERROR — because this
        # pathway is fully intentional for local development; the
        # ``aws_config.get_database_credentials`` call already emits
        # ERROR logs for its own failure modes.
        logger.warning("Failed to retrieve credentials from Secrets Manager; falling back to local settings")
        # Lazy import of Settings for the same circular-dependency
        # reasons as above.
        from src.shared.config.settings import Settings

        settings = Settings()
        sync_url = settings.DATABASE_URL_SYNC
        # DATABASE_URL_SYNC format:
        #   postgresql+psycopg2://user:pass@host:port/dbname
        # We only need host:port/dbname for the JDBC URL — the user
        # and password are handled separately by
        # :func:`get_connection_options`. The parsing below is
        # deliberately minimal (no urllib parsing) because the format
        # is fully controlled by settings.py and docker-compose.yml
        # and must not contain query parameters.
        parts = sync_url.split("@")[-1]  # → "host:port/dbname"
        host_port, db = parts.split("/", 1)
        h, p = host_port.split(":", 1)
        url = f"jdbc:postgresql://{h}:{p}/{db}"
        logger.info(
            "Constructed JDBC URL from local settings: jdbc:postgresql://%s:%s/%s",
            h,
            p,
            db,
        )
        return url


# ============================================================================
# JDBC connection options (url + driver + user + password + dbtable)
# ============================================================================


def get_connection_options(table_name: str | None = None) -> dict[str, str]:
    """Get JDBC connection options for PySpark DataFrameReader/Writer.

    Replaces VSAM DD statement contracts.

    Returns the full dictionary of options that must be supplied to
    PySpark's JDBC reader/writer via ``.options(**opts)``. The returned
    dict follows the PostgreSQL JDBC driver's public contract:

    * ``url``      — the JDBC URL from :func:`get_jdbc_url`.
    * ``driver``   — ``"org.postgresql.Driver"`` (bundled with AWS
      Glue 5.1 / Apache Spark 3.5.6).
    * ``user``     — database username retrieved from Secrets Manager
      or parsed from :attr:`Settings.DATABASE_URL_SYNC`.
    * ``password`` — database password retrieved from Secrets Manager
      or parsed from :attr:`Settings.DATABASE_URL_SYNC`.
    * ``dbtable``  — (optional) PostgreSQL table name when the caller
      supplies ``table_name``. Required by PySpark's JDBC reader but
      not by its writer (the writer takes ``dbtable`` via a separate
      ``.option()`` call in :func:`write_table`).

    The IAM-based authentication pattern is fully compatible with this
    contract: the ``password`` field simply carries a short-lived IAM
    authentication token instead of a long-lived password. No code
    changes are required here — only the secret content differs.

    Replaces
    --------
    On the mainframe every VSAM DD statement is a complete *contract*
    describing both the dataset (via DSN) and the disposition (via
    DISP). ``DISP=SHR`` grants shared read/update access;
    ``DISP=(NEW,CATLG,DELETE)`` allocates a new dataset and catalogs
    it on success. In the target architecture *both* disposition
    aspects are carried by the ``mode=`` argument on
    :func:`write_table` (``"append"`` ≈ DISP=SHR write,
    ``"overwrite"`` ≈ DISP=NEW with DELETE-first semantics), while
    this function captures the connection-side contract.

    Parameters
    ----------
    table_name : str | None, optional
        PostgreSQL table name to include as the ``dbtable`` option.
        When ``None`` (the default), the ``dbtable`` key is omitted
        from the returned dict — useful when the caller will set it
        explicitly via ``.option("dbtable", ...)``.

    Returns
    -------
    dict[str, str]
        A dictionary with keys ``url``, ``driver``, ``user``,
        ``password``, and optionally ``dbtable``. All values are
        strings, satisfying PySpark's requirement that JDBC options
        be string-typed.
    """
    # ------------------------------------------------------------------
    # Primary pathway — AWS Secrets Manager.
    # ------------------------------------------------------------------
    # Lazy import pattern (see notes in get_jdbc_url above).
    try:
        from src.shared.config.aws_config import get_database_credentials

        creds = get_database_credentials()
        options: dict[str, str] = {
            "url": (f"jdbc:postgresql://{creds['host']}:{creds['port']}/{creds['dbname']}"),
            "driver": "org.postgresql.Driver",
            "user": creds["username"],
            "password": creds["password"],
        }
    except Exception:
        # ------------------------------------------------------------------
        # Fallback pathway — local Settings.
        # ------------------------------------------------------------------
        # Parse DATABASE_URL_SYNC to extract user, password, host,
        # port, dbname. The parsing is deliberately minimal and
        # intentionally NOT urllib-based: the settings.py module
        # guarantees the canonical form
        #   postgresql+psycopg2://user:pass@host:port/dbname
        # without query parameters. If a deployment ever needs query
        # parameters (e.g., ``?sslmode=require``), the Settings field
        # should be extended or a dedicated helper added — do NOT
        # silently drop parameters here.
        logger.warning("Failed to retrieve credentials from Secrets Manager; falling back to local settings")
        from src.shared.config.settings import Settings

        settings = Settings()
        sync_url = settings.DATABASE_URL_SYNC
        # Split off the scheme (``postgresql+psycopg2://`` or
        # ``postgresql://``).
        after_scheme = sync_url.split("://", 1)[1]  # → user:pass@host:port/dbname
        user_pass, host_port_db = after_scheme.split("@", 1)
        user, password = user_pass.split(":", 1)
        host_port, dbname = host_port_db.split("/", 1)
        options = {
            "url": f"jdbc:postgresql://{host_port}/{dbname}",
            "driver": "org.postgresql.Driver",
            "user": user,
            "password": password,
        }

    # ------------------------------------------------------------------
    # Conditionally add dbtable.
    # ------------------------------------------------------------------
    # PySpark's JDBC reader requires dbtable to be set on the reader
    # options; the writer sets it via a separate .option() call. We
    # include it here only when the caller requested it — keeping the
    # default return value minimal.
    if table_name:
        options["dbtable"] = table_name

    logger.info(
        "JDBC connection options prepared for %s",
        table_name or "unspecified table",
    )
    return options


# ============================================================================
# VSAM → PostgreSQL table-name translation
# ============================================================================


def get_table_name(vsam_name: str) -> str:
    """Translate a VSAM dataset short-name to its PostgreSQL table name.

    Looks up ``vsam_name`` (case-insensitive) in :data:`VSAM_TABLE_MAP`
    and returns the corresponding PostgreSQL table name. The VSAM short
    name is the 8-character qualifier that appears in the JCL DSN:
    ``DSN=AWS.M2.CARDDEMO.<SHORT>.VSAM.KSDS``.

    Example lookups (derived from the JCLs in this file's Source
    section)::

        get_table_name("ACCTDATA") → "accounts"          # ACCTFILE.jcl
        get_table_name("TRANSACT") → "transactions"      # POSTTRAN.jcl, TRANBKP.jcl
        get_table_name("CARDXREF") → "card_cross_references"  # POSTTRAN.jcl, INTCALC.jcl
        get_table_name("TCATBALF") → "transaction_category_balances"
        get_table_name("DISCGRP")  → "disclosure_groups"

    Unknown dataset names raise :exc:`ValueError` rather than silently
    returning a placeholder — catching configuration drift at the
    earliest possible call site.

    Parameters
    ----------
    vsam_name : str
        The 8-character VSAM short-name (case-insensitive). Leading/
        trailing whitespace is NOT stripped; callers are expected to
        supply a well-formed identifier.

    Returns
    -------
    str
        The PostgreSQL table name (snake_case) corresponding to the
        VSAM dataset.

    Raises
    ------
    ValueError
        If ``vsam_name`` (uppercased) is not a key in
        :data:`VSAM_TABLE_MAP`. The error message lists the valid
        keys for operational diagnostics.
    """
    upper_name = vsam_name.upper()
    if upper_name not in VSAM_TABLE_MAP:
        # Include the set of valid names in the error message so the
        # operator sees the entire menu of legal VSAM short-names in
        # one place — mirroring the JCL behaviour where an unknown
        # DD-NAME is flagged at job-submission time.
        raise ValueError(f"Unknown VSAM dataset: {vsam_name}. Valid names: {list(VSAM_TABLE_MAP.keys())}")
    return VSAM_TABLE_MAP[upper_name]


# ============================================================================
# PySpark DataFrame convenience helpers
# ============================================================================


def read_table(spark_session: Any, table_name: str) -> Any:
    """Read a PostgreSQL table into a PySpark DataFrame.

    Replaces VSAM READ (``DISP=SHR``) patterns.

    Constructs the full set of JDBC connection options via
    :func:`get_connection_options` and issues a ``spark.read.format("jdbc")``
    against the requested table. The returned DataFrame is lazy — no
    actual JDBC traffic flows until the first action (``.count()``,
    ``.collect()``, ``.write.save()``, etc.).

    Replaces
    --------
    On the mainframe every batch program opens a VSAM cluster with
    ``OPEN INPUT`` (for reads) or ``OPEN I-O`` (for read-then-update)
    against a DD-NAME bound to a ``DISP=SHR`` dataset. Example from
    ``POSTTRAN.jcl`` (lines 28-29)::

        //TRANFILE DD DISP=SHR,
        //         DSN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS

    The COBOL program then executes ``READ TRANFILE INTO WS-TRAN-REC``
    repeatedly until EOF. The PySpark equivalent is a single call::

        tran_df = read_table(spark, "transactions")

    which loads the entire table as a distributed DataFrame whose
    rows can be processed in parallel across Glue worker nodes.

    Financial precision
    -------------------
    All monetary columns in the PostgreSQL schema are declared
    ``NUMERIC(15, 2)``. PySpark's JDBC connector maps these to
    :class:`pyspark.sql.types.DecimalType` (which is backed by Python
    :class:`decimal.Decimal` on the driver side) — preserving the
    exact two-decimal-place semantics of COBOL ``PIC S9(n)V99`` fields
    per AAP §0.7.2 Financial Precision requirements. No floating-point
    conversion ever occurs in the connection layer.

    Parameters
    ----------
    spark_session : Any
        An active :class:`pyspark.sql.SparkSession`, typically obtained
        from :func:`src.batch.common.glue_context.init_glue`. Typed as
        ``Any`` because PySpark type stubs are not available in all
        deployment contexts (AWS Glue 5.1 runtime ships PySpark but
        local unit tests may stub SparkSession).
    table_name : str
        PostgreSQL table name to read (e.g., ``"accounts"``,
        ``"transactions"``). Must be one of the tables defined in
        ``db/migrations/V1__schema.sql``. To translate a VSAM
        short-name to a PostgreSQL table name, use
        :func:`get_table_name`.

    Returns
    -------
    Any
        A :class:`pyspark.sql.DataFrame` containing the full contents
        of the PostgreSQL table. Typed as ``Any`` for the same reason
        as ``spark_session`` above.
    """
    options = get_connection_options(table_name)
    logger.info("Reading table '%s' via JDBC", table_name)
    # The .options(**options) call unpacks the dict of JDBC connection
    # options (url, driver, user, password, dbtable) so each key
    # becomes an individual .option(k, v) call. PySpark's JDBC
    # connector uses these to establish a pool of connections and
    # partition the read across worker nodes when the DataFrame is
    # materialized.
    return spark_session.read.format("jdbc").options(**options).load()


def write_table(
    dataframe: Any,
    table_name: str,
    mode: str = "append",
) -> None:
    """Write a PySpark DataFrame to a PostgreSQL table.

    Replaces VSAM WRITE/REWRITE patterns.

    Uses :func:`get_connection_options` to retrieve JDBC credentials,
    then issues ``dataframe.write.format("jdbc").mode(mode).save()``
    against the requested table. The write is triggered eagerly — this
    function blocks until the operation completes.

    Replaces
    --------
    On the mainframe VSAM writes take several forms:

    * ``WRITE tran-rec`` against a ``DISP=SHR`` output file appends a
      new record to the cluster (the VSAM equivalent of an INSERT).
      PySpark equivalent: ``mode="append"`` (the default).
    * ``REWRITE tran-rec`` against a previously-read record updates
      the record in-place. PySpark equivalent: either
      ``mode="append"`` (for idempotent INSERTs) or ``mode="overwrite"``
      (for non-idempotent REWRITE-all-records patterns). Note that
      ``overwrite`` truncates the target table before writing — use it
      only when the DataFrame represents the complete new state of the
      table.
    * ``DISP=(NEW,CATLG,DELETE)`` on an output DD (e.g., ``DALYREJS``
      in ``POSTTRAN.jcl``) allocates a brand-new dataset and catalogs
      it on success; on failure the dataset is deleted. PySpark
      equivalent: ``mode="overwrite"`` plus the normal Glue job
      success/failure semantics — if the Glue job fails, Step
      Functions can roll back the write by re-invoking a compensating
      job (AAP §0.7.2 batch pipeline sequencing).

    Example usage from a PySpark Glue job::

        write_table(
            dataframe=posted_transactions_df,
            table_name=get_table_name("TRANSACT"),
            mode="append",
        )

    Financial precision
    -------------------
    As with :func:`read_table`, the JDBC connector correctly round-trips
    :class:`pyspark.sql.types.DecimalType` columns to PostgreSQL
    ``NUMERIC(15, 2)`` without any floating-point conversion — per AAP
    §0.7.2 Financial Precision requirements.

    Parameters
    ----------
    dataframe : Any
        A :class:`pyspark.sql.DataFrame` whose schema matches the
        target PostgreSQL table. Typed as ``Any`` for the same
        deployment-context reasons as :func:`read_table`.
    table_name : str
        PostgreSQL table name to write to (e.g., ``"transactions"``).
        Must be one of the tables defined in
        ``db/migrations/V1__schema.sql``.
    mode : str, optional
        Spark DataFrame write mode. Accepted values:

        * ``"append"`` (the default) — inserts all rows from the
          DataFrame; existing rows in the target table are preserved.
          Replaces COBOL ``WRITE`` patterns.
        * ``"overwrite"`` — truncates the target table before writing.
          Replaces ``DISP=(NEW,CATLG,DELETE)`` semantics or
          ``REWRITE``-all-records patterns.
        * ``"error"`` / ``"ignore"`` — less-common Spark modes for
          refusing writes when data already exists. Available but not
          used by any of the current batch jobs.

    Returns
    -------
    None
        The write is a side-effecting operation; no value is returned.
        Any error surfaces as an exception from the PySpark JDBC
        connector (e.g., :exc:`py4j.protocol.Py4JJavaError`), which
        the calling Glue job must catch or allow to propagate so Step
        Functions can transition to the failure state.
    """
    options = get_connection_options(table_name)
    logger.info(
        "Writing to table '%s' via JDBC (mode=%s)",
        table_name,
        mode,
    )
    # The .option("dbtable", table_name) call below is technically
    # redundant with .options(**options) when table_name is non-empty
    # (get_connection_options already sets dbtable). It is kept for
    # explicitness and to match the documented write pattern; PySpark
    # treats the later .option() call as an override of the earlier
    # one, so the value is identical either way.
    (dataframe.write.format("jdbc").options(**options).option("dbtable", table_name).mode(mode).save())
