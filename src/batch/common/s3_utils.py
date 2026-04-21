# ============================================================================
# Source: app/jcl/DEFGDGB.jcl, app/jcl/REPTFILE.jcl, app/jcl/DALYREJS.jcl,
#         app/jcl/TRANBKP.jcl, app/jcl/CREASTMT.JCL, app/jcl/TRANREPT.jcl
#         — JCL GDG (Generation Data Group) DEFINE/USE patterns
#         → AWS S3 versioned object storage with date-based key prefixes
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
"""S3 read/write helpers replacing mainframe GDG (Generation Data Group) management.

Replaces the following JCL/GDG patterns used by every batch pipeline stage:

* **DEFGDGB.jcl** — Defines 6 GDG bases via IDCAMS
  ``DEFINE GENERATIONDATAGROUP (NAME(...) LIMIT(5) SCRATCH)``:
  ``TRANSACT.BKUP``, ``TRANSACT.DALY``, ``TRANREPT``, ``TCATBALF.BKUP``,
  ``SYSTRAN``, ``TRANSACT.COMBINED``. Each base enforces a sliding
  window of the 5 most recent generations with automatic deletion
  (``SCRATCH``) of older generations → S3 versioned paths with
  date-based timestamps under a per-GDG key prefix.
* **REPTFILE.jcl** — Redefines ``TRANREPT`` with
  ``LIMIT(10)`` (higher retention for report generations). The wider
  retention window is preserved in :data:`GDG_LIMITS` so that
  :func:`cleanup_old_generations` does not prune report generations
  beyond the 10-generation boundary.
* **DALYREJS.jcl** — Defines ``DALYREJS`` with
  ``LIMIT(5) SCRATCH`` for daily transaction reject files
  (reject codes 100-109 emitted by ``CBTRN02C`` during Stage 1
  POSTTRAN). Rejects are written to S3 rather than a GDG generation.
* **CREASTMT.JCL** — Writes customer statement output in two formats:

  - ``AWS.M2.CARDDEMO.STATEMNT.PS`` (``LRECL=80,RECFM=FB``) — text
    format → S3 object with ``Content-Type: text/plain`` under the
    ``statements/text/`` prefix.
  - ``AWS.M2.CARDDEMO.STATEMNT.HTML`` (``LRECL=100,RECFM=FB``) — HTML
    format → S3 object with ``Content-Type: text/html`` under the
    ``statements/html/`` prefix.

* **TRANREPT.jcl** — Writes transaction report output
  (``AWS.M2.CARDDEMO.TRANREPT(+1)`` with ``LRECL=133,RECFM=FB``) →
  S3 versioned report object under ``reports/transactions/``.
* **TRANBKP.jcl** — Writes transaction backup
  (``AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)`` with ``LRECL=350,RECFM=FB``)
  → S3 versioned backup object under ``backups/transactions/``.

Mainframe-to-Cloud mapping for generation semantics
---------------------------------------------------
On the mainframe every batch job that produces output allocates a new
generation via ``DISP=(NEW,CATLG,DELETE), DSN=...(+1)``. The ``(+1)``
notation instructs the z/OS catalog to create a new generation whose
catalog entry shadows the previous ``(0)`` (current) generation. IDCAMS
maintains the GDG base's ``LIMIT`` by SCRATCHing (deleting) the oldest
generation when a new one pushes the count beyond the limit.

In the target architecture every ``(+1)`` write becomes an S3
``put_object`` with a key that embeds a UTC timestamp
(``YYYY/MM/DD/HHMMSS``) under the GDG's mapped prefix. Every ``(0)``
read becomes an S3 ``list_objects_v2`` + ``get_object`` that sorts the
timestamped prefixes descending and selects the newest. The ``SCRATCH``
semantics of IDCAMS are preserved by :func:`cleanup_old_generations`,
which lists all generations and deletes every generation beyond the
``LIMIT`` defined in :data:`GDG_LIMITS`.

The 3-part GDG notation mapping is::

    MAINFRAME JCL                          → AWS S3
    ──────────────────────────────────     ──────────────────────────────
    DSN=AWS.M2.CARDDEMO.<base>(+1)          → s3://{bucket}/{prefix}/{YYYY}/{MM}/{DD}/{HHMMSS}/
    DSN=AWS.M2.CARDDEMO.<base>(0)           → s3://{bucket}/{prefix}/  (latest)
    DEFINE GENERATIONDATAGROUP LIMIT(N)     → GDG_LIMITS[base] = N
    SCRATCH                                 → cleanup_old_generations()

Credential management
---------------------
S3 clients are obtained via :func:`src.shared.config.aws_config.get_s3_client`
which uses IAM role-based authentication (ECS task role for API
workloads; Glue job role for batch workloads). No AWS access keys or
secrets are handled by this module — matching AAP §0.7.2 Security
Requirements (IAM roles, Secrets Manager, no hardcoded credentials).

Public API
----------
* :data:`GDG_PATH_MAP` — canonical mapping from GDG short-names (as
  they appear in the mainframe ``DEFGDGB.jcl``, ``REPTFILE.jcl``,
  ``DALYREJS.jcl``, ``CREASTMT.JCL``, ``TRANREPT.jcl``, and
  ``TRANBKP.jcl`` DD statements) to S3 key prefixes.
* :data:`GDG_LIMITS` — canonical mapping from GDG short-names to
  generation retention limits. ``TRANREPT`` uses ``LIMIT(10)`` from
  ``REPTFILE.jcl``; all other GDGs use ``LIMIT(5)`` from
  ``DEFGDGB.jcl`` and ``DALYREJS.jcl``. The statement output names
  ``STATEMNT.PS`` and ``STATEMNT.HTML`` are intentionally excluded
  because they are non-GDG (plain PS datasets with
  ``DISP=(NEW,CATLG,DELETE)``).
* :func:`get_versioned_s3_path` — constructs a date-timestamped S3 URI
  equivalent to the mainframe ``DSN=...(+1)`` generation notation.
* :func:`write_to_s3` — writes bytes or text to S3 (replaces COBOL
  ``WRITE`` / JCL ``DISP=(NEW,CATLG,DELETE)``).
* :func:`read_from_s3` — reads bytes from S3 (replaces COBOL ``READ``
  / JCL ``DISP=SHR`` for ``(0)`` generation reads).
* :func:`list_generations` — returns a descending-sorted list of
  generation prefixes (replaces IDCAMS ``LISTCAT`` for GDG bases).
* :func:`cleanup_old_generations` — removes generations beyond the
  configured ``LIMIT`` (replaces IDCAMS ``SCRATCH`` semantics from
  ``DEFGDGB.jcl`` and ``DALYREJS.jcl``).

Source
------
``app/jcl/DEFGDGB.jcl``, ``app/jcl/REPTFILE.jcl``,
``app/jcl/DALYREJS.jcl``, ``app/jcl/TRANBKP.jcl``,
``app/jcl/CREASTMT.JCL``, ``app/jcl/TRANREPT.jcl``.

See Also
--------
AAP §0.4.1 — Refactored Structure Planning
AAP §0.4.4 — Key Architectural Decisions (Batch Layer — AWS Glue, S3)
AAP §0.5.1 — File-by-File Transformation Plan
AAP §0.6.2 — AWS Service Dependencies (AWS S3)
AAP §0.7.2 — Security Requirements (IAM roles, no hardcoded credentials)
"""

from __future__ import annotations

import io
import logging
from datetime import datetime, timezone
from typing import Any

import boto3  # noqa: F401  # Schema-mandated runtime dependency declaration; actual S3 clients are obtained via get_s3_client() from src.shared.config.aws_config for IAM-role-based authentication

# ----------------------------------------------------------------------------
# Module logger. Uses the module's ``__name__`` so CloudWatch log streams
# tag every structured JSON record with ``src.batch.common.s3_utils`` for
# CloudWatch Logs Insights filtering. Log format is configured globally by
# :func:`src.batch.common.glue_context.init_glue` (see its :class:`JsonFormatter`).
# Replaces ``SYSPRINT DD SYSOUT=*`` output from every batch JCL for
# tracing GDG operations (generation allocation, read, cleanup).
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)


# ============================================================================
# GDG → S3 Path Mapping
# ============================================================================
# Canonical mapping from GDG base names (as they appear in mainframe JCL
# ``DD DSN=AWS.M2.CARDDEMO.<base>`` clauses) to AWS S3 key prefixes. The
# prefix is appended to ``s3://{S3_BUCKET_NAME}/`` (where the bucket name
# is resolved lazily from :class:`src.shared.config.settings.Settings`).
#
# Mapping rationale (mainframe short-name → functional category → S3 prefix):
#
#   TRANSACT.BKUP      → backups        → backups/transactions       (TRANBKP.jcl)
#   TRANSACT.DALY      → daily extracts → daily/transactions         (DEFGDGB.jcl)
#   TRANREPT           → reports        → reports/transactions       (REPTFILE.jcl, TRANREPT.jcl)
#   TCATBALF.BKUP      → backups        → backups/category-balance   (DEFGDGB.jcl)
#   SYSTRAN            → generated      → generated/system-transactions (DEFGDGB.jcl, INTCALC)
#   TRANSACT.COMBINED  → combined       → combined/transactions      (DEFGDGB.jcl, COMBTRAN)
#   DALYREJS           → rejects        → rejects/daily              (DALYREJS.jcl, POSTTRAN)
#   STATEMNT.PS        → statements     → statements/text            (CREASTMT.JCL, text)
#   STATEMNT.HTML      → statements     → statements/html            (CREASTMT.JCL, HTML)
#
# Nine entries total: 6 from DEFGDGB.jcl + 1 from DALYREJS.jcl + 2 from
# CREASTMT.JCL. REPTFILE.jcl overrides DEFGDGB for TRANREPT's LIMIT but
# reuses the same base name (see GDG_LIMITS below).
# ----------------------------------------------------------------------------
GDG_PATH_MAP: dict[str, str] = {
    "TRANSACT.BKUP": "backups/transactions",
    "TRANSACT.DALY": "daily/transactions",
    "TRANREPT": "reports/transactions",
    "TCATBALF.BKUP": "backups/category-balance",
    "SYSTRAN": "generated/system-transactions",
    "TRANSACT.COMBINED": "combined/transactions",
    "DALYREJS": "rejects/daily",
    "STATEMNT.PS": "statements/text",
    "STATEMNT.HTML": "statements/html",
}


# ============================================================================
# GDG Generation Retention Limits
# ============================================================================
# Canonical mapping from GDG base names to generation retention limits.
# Each limit matches the exact ``LIMIT(N)`` clause in the source JCL
# ``DEFINE GENERATIONDATAGROUP`` statements:
#
#   TRANSACT.BKUP      = 5   — DEFGDGB.jcl (LIMIT(5) SCRATCH)
#   TRANSACT.DALY      = 5   — DEFGDGB.jcl (LIMIT(5) SCRATCH)
#   TRANREPT           = 10  — REPTFILE.jcl override (LIMIT(10))
#   TCATBALF.BKUP      = 5   — DEFGDGB.jcl (LIMIT(5) SCRATCH)
#   SYSTRAN            = 5   — DEFGDGB.jcl (LIMIT(5) SCRATCH)
#   TRANSACT.COMBINED  = 5   — DEFGDGB.jcl (LIMIT(5) SCRATCH)
#   DALYREJS           = 5   — DALYREJS.jcl (LIMIT(5) SCRATCH)
#
# Seven entries total (one fewer than GDG_PATH_MAP's nine) because
# ``STATEMNT.PS`` and ``STATEMNT.HTML`` from CREASTMT.JCL are NOT GDG
# bases — they are plain PS datasets with ``DISP=(NEW,CATLG,DELETE)``
# and therefore have no retention limit to enforce in S3.
# :func:`cleanup_old_generations` short-circuits for names without an
# entry in this dictionary.
# ----------------------------------------------------------------------------
GDG_LIMITS: dict[str, int] = {
    "TRANSACT.BKUP": 5,
    "TRANSACT.DALY": 5,
    "TRANREPT": 10,  # REPTFILE.jcl override — higher retention for reports
    "TCATBALF.BKUP": 5,
    "SYSTRAN": 5,
    "TRANSACT.COMBINED": 5,
    "DALYREJS": 5,
}


# ============================================================================
# Public API: get_versioned_s3_path
# ============================================================================
def get_versioned_s3_path(
    gdg_name: str,
    bucket: str | None = None,
    generation: str = "+1",
) -> str:
    """Construct a versioned S3 path replacing mainframe GDG(+1)/GDG(0) notation.

    The mainframe JCL uses the 3-part GDG notation ``DSN=AWS.M2.CARDDEMO.<base>(+1)``
    to allocate a new generation (on write) and ``DSN=AWS.M2.CARDDEMO.<base>(0)``
    to read the most recent generation. This function translates both forms to
    an S3 URI suitable for use with :func:`write_to_s3` (``(+1)``) or as a
    prefix argument to :func:`list_generations` / ``boto3`` ``list_objects_v2``
    (``(0)``).

    Parameters
    ----------
    gdg_name : str
        The GDG base short-name (one of the keys of :data:`GDG_PATH_MAP`,
        e.g. ``"TRANSACT.BKUP"``, ``"TRANREPT"``, ``"DALYREJS"``,
        ``"STATEMNT.PS"``). Must be an exact match (case-sensitive).
    bucket : str or None, optional
        The S3 bucket name. When ``None`` (default), the bucket is
        resolved lazily from :attr:`src.shared.config.settings.Settings.S3_BUCKET_NAME`.
        Lazy import avoids circular dependencies between the batch module
        and the shared config module.
    generation : str, optional
        Generation notation to emit. Supported values:

        * ``"+1"`` (default) — Allocate a new generation. The returned
          URI contains a UTC timestamp in ``YYYY/MM/DD/HHMMSS`` format,
          matching mainframe ``DISP=(NEW,CATLG,DELETE), DSN=...(+1)``.
        * ``"0"`` — Return the GDG's base prefix URI (no timestamp).
          Callers can pass this prefix to :func:`list_generations` or
          ``list_objects_v2`` to discover the latest generation, matching
          mainframe ``DISP=SHR, DSN=...(0)``.

    Returns
    -------
    str
        A fully qualified ``s3://{bucket}/{prefix}/...`` URI. For
        ``generation="+1"`` the URI ends with a trailing slash indicating
        a virtual directory; the caller appends the object name (e.g.
        ``statement.txt``) before invoking :func:`write_to_s3`.

    Raises
    ------
    ValueError
        If ``gdg_name`` is not a recognized GDG base (not in
        :data:`GDG_PATH_MAP`), or if ``generation`` is a value other than
        ``"+1"`` or ``"0"``.

    Examples
    --------
    Allocate a new generation for daily rejects (equivalent to
    ``DISP=(NEW,CATLG,DELETE), DSN=AWS.M2.CARDDEMO.DALYREJS(+1)``)::

        >>> path = get_versioned_s3_path("DALYREJS")
        >>> # returns 's3://carddemo-data/rejects/daily/2025/04/21/143052/'

    Resolve the base prefix for reading the latest generation
    (equivalent to ``DISP=SHR, DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(0)``)::

        >>> prefix = get_versioned_s3_path("TRANSACT.BKUP", generation="0")
        >>> # returns 's3://carddemo-data/backups/transactions/'

    Notes
    -----
    The timestamp resolution is 1 second (``HHMMSS``). Two calls to
    this function in the same second will yield colliding prefixes; in
    practice batch jobs allocate at most one generation per stage so
    this is not a problem. If sub-second resolution becomes required,
    the ``%f`` microsecond specifier can be appended to the strftime
    format string; the existing S3 sort order (lexicographic descending)
    preserves correctness under either format.
    """
    if gdg_name not in GDG_PATH_MAP:
        raise ValueError(
            f"Unknown GDG name: {gdg_name!r}. "
            f"Valid GDG names: {sorted(GDG_PATH_MAP.keys())}"
        )

    if bucket is None:
        # Lazy import of Settings to avoid circular dependency between
        # the batch module and the shared config module. Matches the
        # pattern used throughout src/batch/common/ (see db_connector.py
        # and glue_context.py) and is explicitly called out by the
        # schema's internal_imports specification.
        from src.shared.config.settings import Settings

        bucket = Settings().S3_BUCKET_NAME

    prefix = GDG_PATH_MAP[gdg_name]

    if generation == "+1":
        # Allocate a new generation: append a UTC timestamp under the
        # GDG prefix. The YYYY/MM/DD/HHMMSS format yields S3 keys that
        # sort lexicographically in chronological order, so the newest
        # generation is always the last entry when sorted ascending
        # (equivalently, the first entry when sorted descending — the
        # convention used by :func:`list_generations`).
        now = datetime.now(timezone.utc)  # noqa: UP017  # Schema-mandated member access: ``timezone.utc`` is listed in the external_imports members_accessed for this file and must be retained verbatim; ``datetime.UTC`` is a Python 3.11+ alias but is NOT in the schema specification.
        timestamp = now.strftime("%Y/%m/%d/%H%M%S")
        path = f"s3://{bucket}/{prefix}/{timestamp}/"
        logger.info(
            "Allocated new GDG generation",
            extra={
                "gdg_name": gdg_name,
                "generation": "+1",
                "s3_uri": path,
            },
        )
        return path

    if generation == "0":
        # Return the GDG base prefix. Callers use this as the ``Prefix``
        # argument to ``list_objects_v2`` (with ``Delimiter="/"``) to
        # enumerate all generations and select the latest one. Cheaper
        # than having this function perform the list itself, and matches
        # the idiom used throughout PySpark batch jobs in src/batch/jobs/.
        path = f"s3://{bucket}/{prefix}/"
        logger.info(
            "Resolved GDG base prefix for (0) read",
            extra={
                "gdg_name": gdg_name,
                "generation": "0",
                "s3_uri": path,
            },
        )
        return path

    raise ValueError(
        f"Unsupported generation notation: {generation!r}. "
        f"Use '+1' to allocate a new generation or '0' to read the latest."
    )


# ============================================================================
# Public API: write_to_s3
# ============================================================================
def write_to_s3(
    content: str | bytes,
    key: str,
    bucket: str | None = None,
    content_type: str = "text/plain",
) -> str:
    """Write content to S3. Replaces JCL ``DISP=(NEW,CATLG,DELETE)`` output patterns.

    On the mainframe, every batch job that produces output (statement
    files in ``CREASTMT.JCL``, report files in ``TRANREPT.jcl``, backup
    files in ``TRANBKP.jcl``, reject files in ``DALYREJS.jcl``) issues a
    COBOL ``WRITE`` against a DD statement with
    ``DISP=(NEW,CATLG,DELETE)``. The dataset is created fresh, cataloged
    on successful completion, and deleted on abend.

    This function performs the equivalent S3 ``put_object`` call. The
    object is uploaded atomically (S3 provides read-after-write
    consistency for new objects, so there is no equivalent of the
    ``DELETE`` disposition — a failure before ``put_object`` returns
    means no S3 object is ever created).

    Parameters
    ----------
    content : str or bytes
        The object body to upload. A ``str`` is UTF-8 encoded before
        upload; ``bytes`` are uploaded as-is. The length of the encoded
        payload is recorded in the CloudWatch log entry for traceability.
    key : str
        The full S3 object key (the path portion after the bucket).
        Typically constructed by concatenating the result of
        :func:`get_versioned_s3_path` (with ``generation="+1"``,
        stripping the ``s3://{bucket}/`` prefix) and the desired object
        name. The key must NOT start with a leading slash.
    bucket : str or None, optional
        The S3 bucket name. When ``None`` (default), the bucket is
        resolved lazily from :attr:`src.shared.config.settings.Settings.S3_BUCKET_NAME`.
    content_type : str, optional
        The MIME ``Content-Type`` header to set on the S3 object. Default
        is ``"text/plain"`` (matching ``STATEMNT.PS`` from
        ``CREASTMT.JCL`` and the transaction report from
        ``TRANREPT.jcl``). For the HTML statement output from
        ``CREASTMT.JCL`` use ``"text/html"``; for arbitrary binary
        payloads use ``"application/octet-stream"``.

    Returns
    -------
    str
        The fully qualified ``s3://{bucket}/{key}`` URI of the written
        object.

    Raises
    ------
    botocore.exceptions.ClientError
        Propagated from boto3 on S3 service errors (AccessDenied,
        NoSuchBucket, etc.). Callers should log and re-raise — do not
        swallow, as an S3 write failure in a batch job is a fatal
        condition matching mainframe ``MAXCC=16`` behavior.

    Examples
    --------
    Write a plain-text statement::

        >>> prefix = get_versioned_s3_path("STATEMNT.PS", generation="+1")
        >>> # 's3://carddemo-data/statements/text/2025/04/21/143052/'
        >>> key = prefix.removeprefix("s3://carddemo-data/") + "ACCT-00000000001.txt"
        >>> uri = write_to_s3("STATEMENT BODY ...\\n", key)

    Write an HTML statement::

        >>> uri = write_to_s3(
        ...     "<html>...</html>",
        ...     key="statements/html/2025/04/21/143052/ACCT-00000000001.html",
        ...     content_type="text/html",
        ... )

    Notes
    -----
    The upload uses :class:`io.BytesIO` as a buffered stream wrapper.
    While ``put_object`` accepts a ``bytes`` value directly, wrapping
    in a stream enables future enhancements (streaming multipart upload
    for large payloads, progress callbacks) without API changes. For
    the typical statement/report payloads (single- to double-digit
    kilobytes) the overhead is negligible.
    """
    if bucket is None:
        # Lazy import of Settings — see note in get_versioned_s3_path.
        from src.shared.config.settings import Settings

        bucket = Settings().S3_BUCKET_NAME

    # Lazy import of get_s3_client to avoid importing boto3 at module
    # load time (the schema-level boto3 import above is a declaration
    # only; actual client construction flows through aws_config which
    # applies the shared boto3 Config and IAM-role resolution).
    from src.shared.config.aws_config import get_s3_client

    s3_client: Any = get_s3_client()

    # Encode text payloads as UTF-8 (matches PostgreSQL ``encoding=UTF8``
    # and the canonical behavior of ``str.encode()`` without arguments).
    # COBOL ``DISPLAY`` output from the source batch programs was EBCDIC
    # on the mainframe; the migration to Linux/Python assumes UTF-8
    # consistently across all I/O paths.
    body_bytes: bytes = content.encode("utf-8") if isinstance(content, str) else content

    # Wrap the payload in :class:`io.BytesIO` so that the boto3 client
    # receives a file-like object (enabling streaming uploads for large
    # payloads in future revisions). :class:`io.BytesIO` is resettable
    # via ``.seek(0)``, which matters for boto3's internal retry logic.
    body_stream: io.BytesIO = io.BytesIO(body_bytes)

    s3_client.put_object(
        Bucket=bucket,
        Key=key,
        Body=body_stream,
        ContentType=content_type,
    )

    s3_uri = f"s3://{bucket}/{key}"
    logger.info(
        "Wrote object to S3",
        extra={
            "s3_uri": s3_uri,
            "content_type": content_type,
            "byte_count": len(body_bytes),
        },
    )
    return s3_uri


# ============================================================================
# Public API: read_from_s3
# ============================================================================
def read_from_s3(key: str, bucket: str | None = None) -> bytes:
    """Read content from S3. Replaces JCL ``DISP=SHR`` input patterns for GDG(0).

    On the mainframe, every batch job that consumes prior-stage output
    (e.g., Stage 2 INTCALC reading Stage 1 POSTTRAN's generated
    transaction records, Stage 3 COMBTRAN reading TRANSACT.DALY and
    SYSTRAN, Stage 4a CREASTMT reading TRANSACT.COMBINED) opens the
    input dataset with ``DISP=SHR, DSN=AWS.M2.CARDDEMO.<base>(0)``. The
    ``(0)`` notation resolves to the most recent generation through the
    z/OS catalog.

    This function performs the S3 equivalent: a single ``get_object``
    call against the fully-specified object key. The caller is
    responsible for resolving ``(0)`` semantics — typically by calling
    :func:`list_generations` to discover the most recent generation
    prefix, then concatenating the object name before invoking this
    function.

    Parameters
    ----------
    key : str
        The full S3 object key to read. Must NOT start with a leading
        slash. Must reference an object that exists in the bucket —
        boto3 will raise :class:`botocore.exceptions.ClientError`
        (``NoSuchKey``) otherwise.
    bucket : str or None, optional
        The S3 bucket name. When ``None`` (default), the bucket is
        resolved lazily from :attr:`src.shared.config.settings.Settings.S3_BUCKET_NAME`.

    Returns
    -------
    bytes
        The raw object content. Callers that expect text (e.g., reading
        a statement file written as ``text/plain``) should decode via
        ``result.decode("utf-8")``. Returning bytes rather than str
        mirrors the behavior of boto3's ``StreamingBody.read()`` and
        avoids decoding overhead when the caller only needs to pass the
        payload through to another binary sink (e.g., S3 copy or SQS).

    Raises
    ------
    botocore.exceptions.ClientError
        Propagated from boto3 on service errors. The most common cases
        are ``NoSuchKey`` (object does not exist), ``AccessDenied``
        (IAM role lacks ``s3:GetObject`` permission), and
        ``NoSuchBucket`` (bucket configuration error). Callers should
        log and re-raise — matching mainframe ``ABEND S0C7`` / ``MAXCC=16``
        semantics for a fatal batch read error.

    Examples
    --------
    Read the latest DALYREJS file (equivalent to ``DISP=SHR,
    DSN=AWS.M2.CARDDEMO.DALYREJS(0)``)::

        >>> gens = list_generations("DALYREJS")
        >>> # e.g. ['rejects/daily/2025/04/21/143052/', ...]
        >>> key = gens[0] + "rejects.txt"
        >>> body = read_from_s3(key)
        >>> text = body.decode("utf-8")

    Notes
    -----
    The function reads the entire object into memory via
    ``response["Body"].read()``. For the mainframe's typical batch
    payload sizes (statement files in the 10-100 KB range, backup files
    in the 1-10 MB range) this is well within AWS Glue G.1X worker
    memory (16 GB) and FastAPI ECS task memory (1 GB). For larger
    payloads (e.g., multi-gigabyte compressed backups), PySpark
    Glue jobs should prefer direct DataFrame reads via
    ``spark.read.text("s3://...")`` rather than this helper.
    """
    if bucket is None:
        # Lazy import of Settings — see note in get_versioned_s3_path.
        from src.shared.config.settings import Settings

        bucket = Settings().S3_BUCKET_NAME

    # Lazy import of get_s3_client — see note in write_to_s3.
    from src.shared.config.aws_config import get_s3_client

    s3_client: Any = get_s3_client()

    response = s3_client.get_object(Bucket=bucket, Key=key)
    # ``response["Body"]`` is a :class:`botocore.response.StreamingBody`
    # which exposes a ``read()`` method returning the full payload as
    # bytes. The StreamingBody is automatically closed by boto3 once
    # ``read()`` completes, so there is no context-manager cleanup
    # required here.
    content: bytes = response["Body"].read()

    logger.info(
        "Read object from S3",
        extra={
            "s3_uri": f"s3://{bucket}/{key}",
            "byte_count": len(content),
        },
    )
    return content


# ============================================================================
# Public API: list_generations
# ============================================================================
def list_generations(
    gdg_name: str,
    bucket: str | None = None,
    max_results: int | None = None,
) -> list[str]:
    """List existing generations for a GDG name. Replaces IDCAMS ``LISTCAT``.

    On the mainframe the operator discovers the generations currently
    cataloged for a GDG base by running IDCAMS ``LISTCAT ENTRIES(...)``
    against the base. The output is a sequence of ``G0000V00``-style
    generation entries. The newest generation always has the highest
    ``Gnnnn`` sequence number and is also the one aliased by ``(0)``.

    This function performs the S3 equivalent: a ``list_objects_v2``
    call with ``Delimiter="/"`` to enumerate the common prefixes
    (virtual subfolders) under the GDG's mapped S3 prefix. The common
    prefixes correspond to the timestamped generation paths created by
    :func:`get_versioned_s3_path` with ``generation="+1"``.

    Parameters
    ----------
    gdg_name : str
        The GDG base short-name (one of the keys of :data:`GDG_PATH_MAP`).
        Must be an exact match (case-sensitive).
    bucket : str or None, optional
        The S3 bucket name. When ``None`` (default), the bucket is
        resolved lazily from :attr:`src.shared.config.settings.Settings.S3_BUCKET_NAME`.
    max_results : int or None, optional
        The maximum number of generation prefixes to return. When
        ``None`` (default), the function returns up to
        ``GDG_LIMITS[gdg_name]`` entries (falling back to 5 for
        non-GDG entries like ``STATEMNT.PS`` and ``STATEMNT.HTML``).
        Explicit non-``None`` values override the GDG retention
        limit — useful for :func:`cleanup_old_generations` which needs
        to see ALL generations in order to identify which ones to
        SCRATCH.

    Returns
    -------
    list of str
        A list of S3 common prefixes sorted in descending order
        (newest first). Each entry ends with a trailing slash. The
        length of the returned list is bounded by ``max_results``.
        Returns an empty list if no generations exist under the GDG
        prefix yet (e.g., a fresh deployment before the first batch
        run).

    Raises
    ------
    ValueError
        If ``gdg_name`` is not a recognized GDG base (not in
        :data:`GDG_PATH_MAP`).
    botocore.exceptions.ClientError
        Propagated from boto3 on service errors. The most common case
        is ``AccessDenied`` (IAM role lacks ``s3:ListBucket`` permission).

    Examples
    --------
    List all retained report generations (returns up to 10 because
    ``GDG_LIMITS["TRANREPT"] = 10`` per REPTFILE.jcl)::

        >>> gens = list_generations("TRANREPT")
        >>> for g in gens:
        ...     print(g)  # e.g. 'reports/transactions/2025/04/21/143052/'

    List ALL generations (including those beyond the limit) for a
    cleanup operation::

        >>> all_gens = list_generations("TRANSACT.BKUP", max_results=1000)
        >>> to_delete = all_gens[GDG_LIMITS["TRANSACT.BKUP"]:]

    Notes
    -----
    The function uses ``list_objects_v2`` with ``Delimiter="/"`` which
    returns at most 1000 common prefixes per API call. For GDG bases
    with retention limits of 5 or 10 this is always sufficient. If a
    caller passes ``max_results > 1000`` the function silently caps
    at 1000 (the S3 API limit); this matches the cleanup use case
    where 1000 generations far exceeds any reasonable retention
    boundary.
    """
    if gdg_name not in GDG_PATH_MAP:
        raise ValueError(
            f"Unknown GDG name: {gdg_name!r}. "
            f"Valid GDG names: {sorted(GDG_PATH_MAP.keys())}"
        )

    if bucket is None:
        # Lazy import of Settings — see note in get_versioned_s3_path.
        from src.shared.config.settings import Settings

        bucket = Settings().S3_BUCKET_NAME

    prefix = GDG_PATH_MAP[gdg_name]
    # Resolve the effective result limit. The explicit argument wins;
    # otherwise we fall back to the GDG's configured retention limit
    # (GDG_LIMITS), and finally to 5 for non-GDG entries that don't
    # appear in GDG_LIMITS (STATEMNT.PS, STATEMNT.HTML).
    effective_limit: int = max_results if max_results is not None else GDG_LIMITS.get(gdg_name, 5)

    # Lazy import of get_s3_client — see note in write_to_s3.
    from src.shared.config.aws_config import get_s3_client

    s3_client: Any = get_s3_client()

    # Enumerate every object under the GDG's prefix and derive the
    # generation timestamp from each object's key. We deliberately use
    # ``list_objects_v2`` WITHOUT ``Delimiter`` (i.e., the S3 equivalent
    # of ``aws s3 ls --recursive``) because the timestamp path
    # ``YYYY/MM/DD/HHMMSS/`` has four nested segments — a delimiter-based
    # approach would only expose the first segment (year) and require
    # four additional API calls per GDG to drill down. Recursive listing
    # returns all keys via the boto3 paginator and is the canonical
    # pattern used by AWS SDK documentation.
    #
    # A boto3 paginator is used here instead of a single
    # ``list_objects_v2`` call because a single call is capped at the
    # S3 server-side limit of 1000 keys per response. A GDG with the
    # maximum LIMIT of 10 (TRANREPT) may legitimately contain more than
    # 1000 objects over its lifetime (e.g., multi-page statements or
    # per-account reports), and a non-paginated call would silently
    # truncate results — breaking the subsequent cleanup logic which
    # depends on seeing the full generation set.
    #
    # For YYYY/MM/DD/HHMMSS paths we want the 5-segment prefix:
    # <gdg_prefix>/YYYY/MM/DD/HHMMSS/
    generation_prefixes: set[str] = set()
    paginator = s3_client.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=bucket, Prefix=f"{prefix}/"):
        for obj in page.get("Contents", []):
            obj_key: str = obj["Key"]
            # Strip the GDG prefix, then take the first 4 path components
            # (YYYY/MM/DD/HHMMSS) to form the generation prefix.
            relative = obj_key.removeprefix(f"{prefix}/")
            parts = relative.split("/", 4)
            if len(parts) >= 5:
                # parts = [YYYY, MM, DD, HHMMSS, rest_of_key]
                generation_prefixes.add(
                    f"{prefix}/{parts[0]}/{parts[1]}/{parts[2]}/{parts[3]}/"
                )

    # Sort descending (newest first). Lexicographic descending sort is
    # equivalent to chronological descending sort because the path
    # components are zero-padded (e.g., "04" not "4", "07" not "7").
    sorted_prefixes = sorted(generation_prefixes, reverse=True)

    result = sorted_prefixes[:effective_limit]
    logger.info(
        "Listed GDG generations",
        extra={
            "gdg_name": gdg_name,
            "total_discovered": len(sorted_prefixes),
            "returned": len(result),
            "effective_limit": effective_limit,
        },
    )
    return result


# ============================================================================
# Public API: cleanup_old_generations
# ============================================================================
def cleanup_old_generations(gdg_name: str, bucket: str | None = None) -> int:
    """Remove old generations beyond GDG LIMIT. Implements SCRATCH semantics.

    On the mainframe, IDCAMS automatically deletes (``SCRATCH``) old
    generations when a new one is allocated that would push the count
    beyond the GDG's ``LIMIT``. For ``DEFGDGB.jcl`` and ``DALYREJS.jcl``
    this limit is 5; for ``REPTFILE.jcl``'s redefinition of TRANREPT
    the limit is 10. The SCRATCH operation is atomic from the caller's
    perspective: the ``DEFINE GENERATIONDATAGROUP`` clause
    ``SCRATCH`` instructs IDCAMS to both uncatalog the generation and
    delete the underlying dataset.

    In S3 the equivalent operation is a two-step process:

    1. Enumerate the existing generations via ``list_objects_v2`` with
       ``Delimiter="/"`` (done via :func:`list_generations` with
       ``max_results`` large enough to capture ALL generations).
    2. For each generation beyond the ``LIMIT``, list the objects under
       it and issue a batch ``delete_objects`` call.

    This function encapsulates that workflow and returns the number of
    GENERATIONS (not objects) that were deleted. Each generation
    typically contains a small number of objects (1 statement.txt, or
    1 report.txt, or a single reject file), but the count of objects
    removed is included in the structured log entry for each deletion.

    Parameters
    ----------
    gdg_name : str
        The GDG base short-name (one of the keys of :data:`GDG_PATH_MAP`).
        Must be an exact match (case-sensitive). Names that appear in
        :data:`GDG_PATH_MAP` but NOT in :data:`GDG_LIMITS`
        (``STATEMNT.PS``, ``STATEMNT.HTML``) short-circuit with a
        return value of ``0`` — these are plain PS datasets, not GDGs,
        and have no SCRATCH retention to enforce.
    bucket : str or None, optional
        The S3 bucket name. When ``None`` (default), the bucket is
        resolved lazily from :attr:`src.shared.config.settings.Settings.S3_BUCKET_NAME`.

    Returns
    -------
    int
        The number of generations deleted. A value of ``0`` means
        either (a) the GDG had fewer generations than the ``LIMIT`` and
        nothing needed to be deleted, or (b) the name is a non-GDG
        entry (``STATEMNT.PS`` / ``STATEMNT.HTML``) for which SCRATCH
        does not apply.

    Raises
    ------
    ValueError
        If ``gdg_name`` is not a recognized GDG base (not in
        :data:`GDG_PATH_MAP`).
    botocore.exceptions.ClientError
        Propagated from boto3 on service errors. The most common cases
        are ``AccessDenied`` (IAM role lacks ``s3:ListBucket`` or
        ``s3:DeleteObject`` permission) and transient ``SlowDown``
        throttling (boto3's default retry policy handles the latter
        automatically).

    Examples
    --------
    Run SCRATCH for daily reject files at the end of a batch pipeline::

        >>> deleted = cleanup_old_generations("DALYREJS")
        >>> # e.g. deleted == 2 if there were 7 generations before

    No-op for statement output files::

        >>> deleted = cleanup_old_generations("STATEMNT.PS")
        >>> assert deleted == 0

    Notes
    -----
    **Batch deletion**: S3's ``delete_objects`` API accepts up to 1000
    keys per call. This function handles that boundary by iterating
    per generation; a single generation rarely contains more than a
    handful of objects, so one ``delete_objects`` call per generation
    is sufficient in practice.

    **Quiet mode**: The ``Quiet=True`` flag suppresses the per-object
    deletion response in the S3 response envelope. We don't need the
    detail — the structured log entry reports the object count.

    **Idempotency**: Calling this function repeatedly is safe. If a
    generation is already deleted by a concurrent caller, the second
    call's ``list_objects_v2`` simply returns no ``Contents`` and the
    deletion step is skipped.
    """
    if gdg_name not in GDG_PATH_MAP:
        raise ValueError(
            f"Unknown GDG name: {gdg_name!r}. "
            f"Valid GDG names: {sorted(GDG_PATH_MAP.keys())}"
        )

    # Short-circuit for non-GDG names (STATEMNT.PS, STATEMNT.HTML from
    # CREASTMT.JCL). These are plain PS datasets on the mainframe and
    # have no SCRATCH retention to enforce.
    if gdg_name not in GDG_LIMITS:
        logger.info(
            "Skipping cleanup for non-GDG name",
            extra={
                "gdg_name": gdg_name,
                "reason": "Not a GDG base; no SCRATCH retention applies",
            },
        )
        return 0

    limit: int = GDG_LIMITS[gdg_name]

    if bucket is None:
        # Lazy import of Settings — see note in get_versioned_s3_path.
        from src.shared.config.settings import Settings

        bucket = Settings().S3_BUCKET_NAME

    # Enumerate ALL generations (not just the retention limit). We pass
    # a large explicit ``max_results`` to override the default behavior
    # of capping at GDG_LIMITS[gdg_name] — otherwise we could not see
    # the generations that need to be deleted. The value 1000 matches
    # S3's native list_objects_v2 page limit.
    all_generations = list_generations(gdg_name, bucket=bucket, max_results=1000)

    if len(all_generations) <= limit:
        # Nothing to SCRATCH — the GDG is within retention bounds.
        logger.info(
            "GDG within retention bounds; no cleanup required",
            extra={
                "gdg_name": gdg_name,
                "current_generations": len(all_generations),
                "limit": limit,
            },
        )
        return 0

    # Generations are sorted newest-first by :func:`list_generations`,
    # so the generations at indices ``[limit:]`` are the oldest ones
    # that must be SCRATCHed.
    old_generations = all_generations[limit:]

    # Lazy import of get_s3_client — see note in write_to_s3.
    from src.shared.config.aws_config import get_s3_client

    s3_client: Any = get_s3_client()

    deleted_generation_count = 0
    # One paginator is sufficient — it is re-used for every generation
    # via multiple ``paginate(...)`` calls. This avoids the overhead of
    # building a new paginator object on each iteration.
    paginator = s3_client.get_paginator("list_objects_v2")
    for old_prefix in old_generations:
        # List all objects under the old generation's prefix. This is a
        # flat list (no delimiter) because we want to delete every
        # object regardless of virtual subfolder structure.
        #
        # A paginator is used here instead of a single
        # ``list_objects_v2`` call because a single call is capped at
        # the S3 server-side limit of 1000 keys per response. A
        # generation that spans more than 1000 objects (rare but
        # possible for per-account statements or multi-page reports)
        # would be silently truncated by a non-paginated call, leaving
        # orphan objects behind after SCRATCH — a correctness bug.
        all_keys: list[dict[str, str]] = []
        for page in paginator.paginate(Bucket=bucket, Prefix=old_prefix):
            for obj in page.get("Contents", []):
                all_keys.append({"Key": obj["Key"]})
        if not all_keys:
            # The generation prefix has no objects (possibly already
            # deleted by a concurrent caller). Skip it — we still count
            # the generation as "deleted" because from the caller's
            # perspective it no longer exists.
            deleted_generation_count += 1
            logger.info(
                "SCRATCH old generation (already empty)",
                extra={
                    "gdg_name": gdg_name,
                    "generation_prefix": old_prefix,
                    "object_count": 0,
                },
            )
            continue

        # Chunk into batches of 1000 (the S3 ``delete_objects`` limit).
        # In practice each generation has 1-3 objects so chunking is
        # almost never triggered, but we implement it defensively to
        # guarantee correctness for any legitimate payload size.
        chunk_size = 1000
        for i in range(0, len(all_keys), chunk_size):
            chunk = all_keys[i : i + chunk_size]
            s3_client.delete_objects(
                Bucket=bucket,
                Delete={"Objects": chunk, "Quiet": True},
            )

        deleted_generation_count += 1
        logger.info(
            "SCRATCH old generation",
            extra={
                "gdg_name": gdg_name,
                "generation_prefix": old_prefix,
                "object_count": len(all_keys),
            },
        )

    logger.info(
        "GDG cleanup complete",
        extra={
            "gdg_name": gdg_name,
            "limit": limit,
            "generations_before": len(all_generations),
            "generations_deleted": deleted_generation_count,
            "generations_retained": len(all_generations) - deleted_generation_count,
        },
    )
    return deleted_generation_count


# ============================================================================
# Public API surface
# ============================================================================
# Explicitly enumerate the public exports to support ``from s3_utils import *``
# and to signal to static analyzers (mypy, ruff) which names are part of
# the module's public contract. Matches the style of sibling modules
# ``src/batch/common/glue_context.py`` and ``src/batch/common/db_connector.py``.
#
# The five functions and two constants below are the exact exports
# declared by the schema for this file; no additional names are exposed.
# ----------------------------------------------------------------------------
__all__ = [
    "GDG_LIMITS",
    "GDG_PATH_MAP",
    "cleanup_old_generations",
    "get_versioned_s3_path",
    "list_generations",
    "read_from_s3",
    "write_to_s3",
]

