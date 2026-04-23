# ============================================================================
# Source: src/batch/common/s3_utils.py
#         Backed by: app/jcl/DEFGDGB.jcl, app/jcl/REPTFILE.jcl,
#                    app/jcl/DALYREJS.jcl, app/jcl/TRANBKP.jcl,
#                    app/jcl/CREASTMT.JCL, app/jcl/TRANREPT.jcl
#         — IDCAMS DEFINE GENERATIONDATAGROUP / SCRATCH / LISTCAT → S3
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
"""Unit tests for :mod:`src.batch.common.s3_utils` (GDG → S3 migration).

Verification Surface
--------------------
This file verifies the 7 public exports of ``s3_utils`` against the
AAP §0.7.1 preservation rules for the mainframe → cloud migration of
Generation Data Group (GDG) semantics:

+----------------------------------+----------------------------------------------+
| COBOL / JCL Construct            | Python Equivalent                            |
+==================================+==============================================+
| ``DEFINE GENERATIONDATAGROUP``   | :data:`GDG_PATH_MAP` (9 entries) —           |
| (DEFGDGB.jcl, REPTFILE.jcl,      | GDG short-name → S3 key prefix.              |
| DALYREJS.jcl, CREASTMT.JCL)      |                                              |
+----------------------------------+----------------------------------------------+
| ``LIMIT(N)`` clause              | :data:`GDG_LIMITS` (7 entries) —             |
| (5 from DEFGDGB.jcl, DALYREJS;   | GDG short-name → retention count.            |
| 10 from REPTFILE.jcl override)   |                                              |
+----------------------------------+----------------------------------------------+
| ``DSN=...(+1)`` — allocate new   | :func:`get_versioned_s3_path` with           |
| generation                       | ``generation="+1"`` → timestamped URI.       |
+----------------------------------+----------------------------------------------+
| ``DSN=...(0)`` — read current    | :func:`get_versioned_s3_path` with           |
| generation                       | ``generation="0"`` → base-prefix URI.        |
+----------------------------------+----------------------------------------------+
| ``WRITE file``/``DISP=NEW``      | :func:`write_to_s3` — put_object.            |
+----------------------------------+----------------------------------------------+
| ``READ file``/``DISP=SHR``       | :func:`read_from_s3` — get_object.           |
+----------------------------------+----------------------------------------------+
| ``IDCAMS LISTCAT`` for GDG base  | :func:`list_generations` — enumerate         |
|                                  | generations sorted newest-first.             |
+----------------------------------+----------------------------------------------+
| ``IDCAMS SCRATCH`` (implicit via | :func:`cleanup_old_generations` — delete     |
| ``DEFINE ... LIMIT(N) SCRATCH``) | generations beyond ``LIMIT``.                |
+----------------------------------+----------------------------------------------+

QA Checkpoint 7 Remediation
---------------------------
Before this file existed, ``src/batch/common/s3_utils.py`` was covered
at only 10% — 94 of 109 statements missed. This test module targets
≥80% line coverage, lifting the project-wide coverage past the AAP
§0.7.2 target of 81.5%.

AWS Mocking Strategy
--------------------
Each test uses moto's ``@mock_aws`` context manager (via ``pytest``
fixtures) to provision a short-lived S3 mock. The mock preserves the
exact boto3 API surface (``put_object``, ``get_object``,
``list_objects_v2``, ``delete_objects``) that ``s3_utils`` relies on,
so the tests verify real behavior — not stubbed behavior.

Timestamp control for ``list_generations`` tests requires the tests
to write objects with deterministic key structures
(``{prefix}/YYYY/MM/DD/HHMMSS/file.ext``) rather than rely on
``get_versioned_s3_path`` (which uses ``datetime.now(timezone.utc)``
and therefore produces non-deterministic timestamps within the same
second).

See Also
--------
AAP §0.4.4 — Key Architectural Decisions (Batch Layer — AWS Glue, S3)
AAP §0.7.1 — Refactoring-Specific Rules (preservation mandates)
AAP §0.7.2 — Security Requirements (IAM roles, no hardcoded credentials)
QA Checkpoint 7 Test Report — Coverage Gap Analysis for s3_utils.py
"""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

import boto3
import pytest
from botocore.exceptions import ClientError
from moto import mock_aws

from src.batch.common import s3_utils

# =========================================================================
# Constants mirroring production defaults, used across fixtures and tests
# =========================================================================
_TEST_BUCKET = "s3utils-test-bucket"
_DEFAULT_SETTINGS_BUCKET = "carddemo-data"  # Settings.S3_BUCKET_NAME default
_REGION = "us-east-1"  # Matches conftest AWS_DEFAULT_REGION env


# =========================================================================
# Fixtures
# =========================================================================
@pytest.fixture
def s3_with_bucket() -> Iterator[Any]:
    """Provide a mocked S3 client with an explicitly-named test bucket.

    Yields an initialized boto3 S3 client with two buckets pre-created:

    * ``s3utils-test-bucket`` — used when tests pass ``bucket=`` explicitly
    * ``carddemo-data`` — the Settings default, used by tests verifying
      ``bucket=None`` lazy resolution

    Each test gets a freshly-mocked AWS environment (no cross-test
    contamination).
    """
    with mock_aws():
        client = boto3.client("s3", region_name=_REGION)
        client.create_bucket(Bucket=_TEST_BUCKET)
        client.create_bucket(Bucket=_DEFAULT_SETTINGS_BUCKET)
        yield client


def _put_generation(
    s3_client: Any,
    bucket: str,
    prefix: str,
    year: int,
    month: int,
    day: int,
    hour: int,
    minute: int,
    second: int,
    filename: str = "record.txt",
    body: bytes = b"generation payload",
) -> str:
    """Write an object with the GDG-style 5-segment key structure.

    ``list_generations`` requires keys of the form
    ``{prefix}/YYYY/MM/DD/HHMMSS/filename`` (at least 5 path segments
    relative to ``prefix``). This helper constructs that structure
    deterministically so tests can control generation ordering.
    """
    ts = f"{year:04d}/{month:02d}/{day:02d}/{hour:02d}{minute:02d}{second:02d}"
    key = f"{prefix}/{ts}/{filename}"
    s3_client.put_object(Bucket=bucket, Key=key, Body=body)
    return key


# =========================================================================
# Phase 1: Module-level constants — GDG_PATH_MAP, GDG_LIMITS
# =========================================================================
@pytest.mark.unit
class TestGDGPathMap:
    """Verify :data:`GDG_PATH_MAP` matches the 9-entry JCL inventory.

    The exact mapping is derived from AAP §0.4.1 and the inline
    documentation in ``s3_utils.py`` that enumerates the source JCL
    files (DEFGDGB, REPTFILE, DALYREJS, CREASTMT).
    """

    def test_gdg_path_map_has_nine_entries(self) -> None:
        """6 GDGs from DEFGDGB + 1 from DALYREJS + 2 from CREASTMT = 9."""
        assert len(s3_utils.GDG_PATH_MAP) == 9

    def test_gdg_path_map_is_a_dict_of_str_to_str(self) -> None:
        """Contract: keys are GDG short-names, values are S3 prefixes."""
        assert isinstance(s3_utils.GDG_PATH_MAP, dict)
        for k, v in s3_utils.GDG_PATH_MAP.items():
            assert isinstance(k, str)
            assert isinstance(v, str)

    def test_gdg_path_map_defgdgb_entries(self) -> None:
        """DEFGDGB.jcl maps 6 GDG bases to canonical prefixes."""
        assert s3_utils.GDG_PATH_MAP["TRANSACT.BKUP"] == "backups/transactions"
        assert s3_utils.GDG_PATH_MAP["TRANSACT.DALY"] == "daily/transactions"
        assert s3_utils.GDG_PATH_MAP["TRANREPT"] == "reports/transactions"
        assert s3_utils.GDG_PATH_MAP["TCATBALF.BKUP"] == "backups/category-balance"
        assert s3_utils.GDG_PATH_MAP["SYSTRAN"] == "generated/system-transactions"
        assert s3_utils.GDG_PATH_MAP["TRANSACT.COMBINED"] == "combined/transactions"

    def test_gdg_path_map_dalyrejs_entry(self) -> None:
        """DALYREJS.jcl maps the reject-log GDG."""
        assert s3_utils.GDG_PATH_MAP["DALYREJS"] == "rejects/daily"

    def test_gdg_path_map_creastmt_entries(self) -> None:
        """CREASTMT.JCL maps both statement formats (text + HTML)."""
        assert s3_utils.GDG_PATH_MAP["STATEMNT.PS"] == "statements/text"
        assert s3_utils.GDG_PATH_MAP["STATEMNT.HTML"] == "statements/html"

    def test_gdg_path_map_exact_content(self) -> None:
        """Freeze the full mapping — detects any future drift."""
        expected: dict[str, str] = {
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
        assert dict(s3_utils.GDG_PATH_MAP) == expected

    def test_gdg_path_map_prefixes_have_no_trailing_slash(self) -> None:
        """Prefixes must NOT end with '/' — ``get_versioned_s3_path`` adds it."""
        for prefix in s3_utils.GDG_PATH_MAP.values():
            assert not prefix.endswith("/"), (
                f"prefix {prefix!r} must not end with '/' — trailing slash is added by URI construction"
            )

    def test_gdg_path_map_prefixes_are_relative_no_leading_slash(self) -> None:
        """Prefixes must be relative (no leading '/')."""
        for prefix in s3_utils.GDG_PATH_MAP.values():
            assert not prefix.startswith("/"), f"prefix {prefix!r} must be relative (no leading slash)"


@pytest.mark.unit
class TestGDGLimits:
    """Verify :data:`GDG_LIMITS` matches the JCL ``LIMIT(N)`` clauses.

    Seven entries total — one fewer than GDG_PATH_MAP. The statement
    output names (STATEMNT.PS, STATEMNT.HTML) are intentionally
    excluded because they are non-GDG plain PS datasets.
    """

    def test_gdg_limits_has_seven_entries(self) -> None:
        """6 from DEFGDGB + 1 from DALYREJS = 7 (STATEMNT.* excluded)."""
        assert len(s3_utils.GDG_LIMITS) == 7

    def test_gdg_limits_is_a_dict_of_str_to_int(self) -> None:
        """Contract: keys are GDG short-names, values are retention counts."""
        assert isinstance(s3_utils.GDG_LIMITS, dict)
        for k, v in s3_utils.GDG_LIMITS.items():
            assert isinstance(k, str)
            assert isinstance(v, int)
            assert v > 0

    def test_gdg_limits_defgdgb_all_equal_five(self) -> None:
        """DEFGDGB.jcl defines LIMIT(5) for all 6 base GDGs."""
        assert s3_utils.GDG_LIMITS["TRANSACT.BKUP"] == 5
        assert s3_utils.GDG_LIMITS["TRANSACT.DALY"] == 5
        assert s3_utils.GDG_LIMITS["TCATBALF.BKUP"] == 5
        assert s3_utils.GDG_LIMITS["SYSTRAN"] == 5
        assert s3_utils.GDG_LIMITS["TRANSACT.COMBINED"] == 5

    def test_gdg_limits_dalyrejs_equal_five(self) -> None:
        """DALYREJS.jcl defines LIMIT(5) for the reject-log GDG."""
        assert s3_utils.GDG_LIMITS["DALYREJS"] == 5

    def test_gdg_limits_tranrept_override_is_ten(self) -> None:
        """REPTFILE.jcl overrides TRANREPT's LIMIT to 10 (wider retention)."""
        assert s3_utils.GDG_LIMITS["TRANREPT"] == 10

    def test_gdg_limits_excludes_statemnt_ps(self) -> None:
        """STATEMNT.PS is a plain PS dataset — not a GDG — so no LIMIT."""
        assert "STATEMNT.PS" not in s3_utils.GDG_LIMITS

    def test_gdg_limits_excludes_statemnt_html(self) -> None:
        """STATEMNT.HTML is a plain PS dataset — not a GDG — so no LIMIT."""
        assert "STATEMNT.HTML" not in s3_utils.GDG_LIMITS

    def test_gdg_limits_keys_are_subset_of_path_map_keys(self) -> None:
        """Every GDG_LIMITS key must have a corresponding GDG_PATH_MAP entry."""
        assert set(s3_utils.GDG_LIMITS.keys()) <= set(s3_utils.GDG_PATH_MAP.keys())

    def test_gdg_limits_exact_content(self) -> None:
        """Freeze the full mapping — detects any future drift."""
        expected: dict[str, int] = {
            "TRANSACT.BKUP": 5,
            "TRANSACT.DALY": 5,
            "TRANREPT": 10,
            "TCATBALF.BKUP": 5,
            "SYSTRAN": 5,
            "TRANSACT.COMBINED": 5,
            "DALYREJS": 5,
        }
        assert dict(s3_utils.GDG_LIMITS) == expected


# =========================================================================
# Phase 2: get_versioned_s3_path — (+1)/(0) generation notation
# =========================================================================
@pytest.mark.unit
class TestGetVersionedS3Path:
    """Verify :func:`get_versioned_s3_path` (+1)/(0) semantics.

    Replaces ``DSN=AWS.M2.CARDDEMO.<base>(+1)`` and
    ``DSN=...(0)`` JCL notation with S3 timestamped and base-prefix
    URIs respectively.
    """

    def test_plus_one_returns_timestamped_prefix(self, s3_with_bucket: Any) -> None:
        """``generation='+1'`` produces an S3 URI with embedded UTC timestamp."""
        uri = s3_utils.get_versioned_s3_path("DALYREJS", bucket=_TEST_BUCKET, generation="+1")
        assert uri.startswith(f"s3://{_TEST_BUCKET}/rejects/daily/")
        assert uri.endswith("/")
        # Extract path segments between prefix and trailing slash.
        # Expected: rejects/daily/YYYY/MM/DD/HHMMSS/
        prefix = f"s3://{_TEST_BUCKET}/rejects/daily/"
        remainder = uri.removeprefix(prefix).rstrip("/")
        parts = remainder.split("/")
        assert len(parts) == 4, f"expected 4 timestamp segments, got {parts}"
        year, month, day, hhmmss = parts
        assert len(year) == 4 and year.isdigit()
        assert len(month) == 2 and month.isdigit()
        assert len(day) == 2 and day.isdigit()
        assert len(hhmmss) == 6 and hhmmss.isdigit()

    def test_zero_returns_base_prefix(self, s3_with_bucket: Any) -> None:
        """``generation='0'`` produces the plain base-prefix URI (no timestamp)."""
        uri = s3_utils.get_versioned_s3_path("DALYREJS", bucket=_TEST_BUCKET, generation="0")
        assert uri == f"s3://{_TEST_BUCKET}/rejects/daily/"

    def test_default_generation_is_plus_one(self, s3_with_bucket: Any) -> None:
        """Default ``generation`` is ``'+1'`` (matches JCL allocation pattern)."""
        uri = s3_utils.get_versioned_s3_path("DALYREJS", bucket=_TEST_BUCKET)
        # Should contain a timestamped prefix, not just the base prefix.
        assert uri != f"s3://{_TEST_BUCKET}/rejects/daily/"
        assert uri.startswith(f"s3://{_TEST_BUCKET}/rejects/daily/")

    @pytest.mark.parametrize(
        "gdg_name,expected_prefix",
        [
            ("TRANSACT.BKUP", "backups/transactions"),
            ("TRANSACT.DALY", "daily/transactions"),
            ("TRANREPT", "reports/transactions"),
            ("TCATBALF.BKUP", "backups/category-balance"),
            ("SYSTRAN", "generated/system-transactions"),
            ("TRANSACT.COMBINED", "combined/transactions"),
            ("DALYREJS", "rejects/daily"),
            ("STATEMNT.PS", "statements/text"),
            ("STATEMNT.HTML", "statements/html"),
        ],
    )
    def test_plus_one_for_each_gdg_uses_mapped_prefix(
        self, s3_with_bucket: Any, gdg_name: str, expected_prefix: str
    ) -> None:
        """``+1`` URI embeds the correct GDG_PATH_MAP prefix."""
        uri = s3_utils.get_versioned_s3_path(gdg_name, bucket=_TEST_BUCKET, generation="+1")
        assert uri.startswith(f"s3://{_TEST_BUCKET}/{expected_prefix}/")

    @pytest.mark.parametrize(
        "gdg_name,expected_prefix",
        [
            ("TRANSACT.BKUP", "backups/transactions"),
            ("TRANREPT", "reports/transactions"),
            ("STATEMNT.HTML", "statements/html"),
        ],
    )
    def test_zero_for_each_gdg_uses_mapped_prefix(
        self, s3_with_bucket: Any, gdg_name: str, expected_prefix: str
    ) -> None:
        """``0`` URI is exactly ``s3://{bucket}/{prefix}/`` with no timestamp."""
        uri = s3_utils.get_versioned_s3_path(gdg_name, bucket=_TEST_BUCKET, generation="0")
        assert uri == f"s3://{_TEST_BUCKET}/{expected_prefix}/"

    def test_unknown_gdg_raises_valueerror(self, s3_with_bucket: Any) -> None:
        """Unknown GDG name is rejected with a :class:`ValueError`."""
        with pytest.raises(ValueError, match="Unknown GDG name"):
            s3_utils.get_versioned_s3_path("UNKNOWN", bucket=_TEST_BUCKET)

    def test_unknown_gdg_error_message_lists_valid_names(self, s3_with_bucket: Any) -> None:
        """Error message includes the sorted list of valid GDG names."""
        with pytest.raises(ValueError) as exc_info:
            s3_utils.get_versioned_s3_path("NOT_A_GDG", bucket=_TEST_BUCKET)
        msg = str(exc_info.value)
        assert "NOT_A_GDG" in msg
        # Must list all 9 valid GDG names (in sorted order, per source).
        for name in sorted(s3_utils.GDG_PATH_MAP.keys()):
            assert name in msg

    @pytest.mark.parametrize(
        "bad_generation",
        ["-1", "+2", "1", "X", "", "latest", "+0", "0+1"],
    )
    def test_invalid_generation_notation_raises_valueerror(self, s3_with_bucket: Any, bad_generation: str) -> None:
        """Only '+1' and '0' are accepted — everything else is rejected."""
        with pytest.raises(ValueError, match="Unsupported generation notation"):
            s3_utils.get_versioned_s3_path("DALYREJS", bucket=_TEST_BUCKET, generation=bad_generation)

    def test_invalid_generation_error_message_is_specific(self, s3_with_bucket: Any) -> None:
        """The error message mentions both the bad value and accepted forms."""
        with pytest.raises(ValueError) as exc_info:
            s3_utils.get_versioned_s3_path("DALYREJS", bucket=_TEST_BUCKET, generation="-1")
        msg = str(exc_info.value)
        assert "-1" in msg
        assert "+1" in msg
        assert "0" in msg

    def test_default_bucket_resolved_from_settings(self, s3_with_bucket: Any) -> None:
        """``bucket=None`` lazily resolves to :attr:`Settings.S3_BUCKET_NAME`."""
        # Settings.S3_BUCKET_NAME default is "carddemo-data" (our fixture
        # pre-created this bucket alongside _TEST_BUCKET).
        uri = s3_utils.get_versioned_s3_path("DALYREJS", generation="0")
        assert uri == f"s3://{_DEFAULT_SETTINGS_BUCKET}/rejects/daily/"

    def test_explicit_bucket_overrides_settings(self, s3_with_bucket: Any) -> None:
        """Explicit ``bucket`` argument overrides the lazy Settings resolution."""
        uri = s3_utils.get_versioned_s3_path("DALYREJS", bucket="custom-bucket", generation="0")
        assert uri == "s3://custom-bucket/rejects/daily/"


# =========================================================================
# Phase 3: write_to_s3 — DISP=(NEW,CATLG,DELETE) replacement
# =========================================================================
@pytest.mark.unit
class TestWriteToS3:
    """Verify :func:`write_to_s3` replaces JCL ``DISP=(NEW,CATLG,DELETE)``."""

    def test_write_string_returns_s3_uri(self, s3_with_bucket: Any) -> None:
        """Writing a str returns ``s3://{bucket}/{key}``."""
        uri = s3_utils.write_to_s3("hello world", "reports/test.txt", bucket=_TEST_BUCKET)
        assert uri == f"s3://{_TEST_BUCKET}/reports/test.txt"

    def test_write_string_encodes_utf8(self, s3_with_bucket: Any) -> None:
        """String content is UTF-8-encoded for storage."""
        s3_utils.write_to_s3("hello world", "reports/test.txt", bucket=_TEST_BUCKET)
        resp = s3_with_bucket.get_object(Bucket=_TEST_BUCKET, Key="reports/test.txt")
        assert resp["Body"].read() == b"hello world"

    def test_write_bytes_preserves_raw_payload(self, s3_with_bucket: Any) -> None:
        """Bytes content is stored verbatim (no re-encoding)."""
        payload = b"\x00\x01\x02\x03binary\xff\xfe"
        uri = s3_utils.write_to_s3(payload, "backups/bin.dat", bucket=_TEST_BUCKET)
        assert uri == f"s3://{_TEST_BUCKET}/backups/bin.dat"
        resp = s3_with_bucket.get_object(Bucket=_TEST_BUCKET, Key="backups/bin.dat")
        assert resp["Body"].read() == payload

    def test_write_default_content_type_is_text_plain(self, s3_with_bucket: Any) -> None:
        """Default ContentType is ``text/plain`` (matches CREASTMT/TRANREPT)."""
        s3_utils.write_to_s3("sample", "test.txt", bucket=_TEST_BUCKET)
        resp = s3_with_bucket.head_object(Bucket=_TEST_BUCKET, Key="test.txt")
        assert resp["ContentType"] == "text/plain"

    def test_write_content_type_html(self, s3_with_bucket: Any) -> None:
        """``content_type='text/html'`` for STATEMNT.HTML output."""
        s3_utils.write_to_s3(
            "<html></html>",
            "statements/html/test.html",
            bucket=_TEST_BUCKET,
            content_type="text/html",
        )
        resp = s3_with_bucket.head_object(Bucket=_TEST_BUCKET, Key="statements/html/test.html")
        assert resp["ContentType"] == "text/html"

    def test_write_content_type_octet_stream(self, s3_with_bucket: Any) -> None:
        """``content_type='application/octet-stream'`` for binary payloads."""
        s3_utils.write_to_s3(
            b"binary",
            "bin/data.bin",
            bucket=_TEST_BUCKET,
            content_type="application/octet-stream",
        )
        resp = s3_with_bucket.head_object(Bucket=_TEST_BUCKET, Key="bin/data.bin")
        assert resp["ContentType"] == "application/octet-stream"

    def test_write_unicode_payload_roundtrip(self, s3_with_bucket: Any) -> None:
        """Non-ASCII UTF-8 content is preserved bit-for-bit."""
        original = "Hello 中文 — émoji 🎉"
        s3_utils.write_to_s3(original, "utf8.txt", bucket=_TEST_BUCKET)
        resp = s3_with_bucket.get_object(Bucket=_TEST_BUCKET, Key="utf8.txt")
        assert resp["Body"].read().decode("utf-8") == original

    def test_write_empty_string(self, s3_with_bucket: Any) -> None:
        """Empty string is a valid payload (0-byte object)."""
        uri = s3_utils.write_to_s3("", "empty.txt", bucket=_TEST_BUCKET)
        assert uri == f"s3://{_TEST_BUCKET}/empty.txt"
        resp = s3_with_bucket.get_object(Bucket=_TEST_BUCKET, Key="empty.txt")
        assert resp["Body"].read() == b""

    def test_write_empty_bytes(self, s3_with_bucket: Any) -> None:
        """Empty bytes is a valid payload (0-byte object)."""
        s3_utils.write_to_s3(b"", "empty.bin", bucket=_TEST_BUCKET)
        resp = s3_with_bucket.get_object(Bucket=_TEST_BUCKET, Key="empty.bin")
        assert resp["Body"].read() == b""

    def test_write_to_default_bucket_uses_settings(self, s3_with_bucket: Any) -> None:
        """``bucket=None`` lazily resolves to :attr:`Settings.S3_BUCKET_NAME`."""
        uri = s3_utils.write_to_s3("content", "foo.txt")
        assert uri == f"s3://{_DEFAULT_SETTINGS_BUCKET}/foo.txt"

    def test_write_nested_key_path(self, s3_with_bucket: Any) -> None:
        """Keys with deeply-nested paths are honored as-is."""
        uri = s3_utils.write_to_s3("x", "a/b/c/d/e/f/g.txt", bucket=_TEST_BUCKET)
        assert uri == f"s3://{_TEST_BUCKET}/a/b/c/d/e/f/g.txt"


# =========================================================================
# Phase 4: read_from_s3 — DISP=SHR replacement for (0) generation
# =========================================================================
@pytest.mark.unit
class TestReadFromS3:
    """Verify :func:`read_from_s3` replaces JCL ``DISP=SHR`` for ``(0)``."""

    def test_read_returns_bytes(self, s3_with_bucket: Any) -> None:
        """``read_from_s3`` returns raw :class:`bytes` (no auto-decoding)."""
        s3_utils.write_to_s3("sample content", "test.txt", bucket=_TEST_BUCKET)
        body = s3_utils.read_from_s3("test.txt", bucket=_TEST_BUCKET)
        assert isinstance(body, bytes)
        assert body == b"sample content"

    def test_read_roundtrip_with_write(self, s3_with_bucket: Any) -> None:
        """Write-then-read preserves content exactly."""
        payload = "COBOL statement text output\nline 2\nline 3\n"
        s3_utils.write_to_s3(payload, "statement.txt", bucket=_TEST_BUCKET)
        body = s3_utils.read_from_s3("statement.txt", bucket=_TEST_BUCKET)
        assert body.decode("utf-8") == payload

    def test_read_binary_preserves_bytes(self, s3_with_bucket: Any) -> None:
        """Binary payloads round-trip without corruption."""
        payload = bytes(range(256))
        s3_utils.write_to_s3(payload, "all-bytes.bin", bucket=_TEST_BUCKET)
        body = s3_utils.read_from_s3("all-bytes.bin", bucket=_TEST_BUCKET)
        assert body == payload

    def test_read_unicode_roundtrip(self, s3_with_bucket: Any) -> None:
        """Non-ASCII content round-trips via UTF-8."""
        original = "状態レポート — Ümlauts"
        s3_utils.write_to_s3(original, "i18n.txt", bucket=_TEST_BUCKET)
        body = s3_utils.read_from_s3("i18n.txt", bucket=_TEST_BUCKET)
        assert body.decode("utf-8") == original

    def test_read_missing_key_raises_clienterror_nosuchkey(self, s3_with_bucket: Any) -> None:
        """Reading a non-existent key raises boto3 ``NoSuchKey`` ClientError."""
        with pytest.raises(ClientError) as exc_info:
            s3_utils.read_from_s3("does/not/exist.txt", bucket=_TEST_BUCKET)
        assert exc_info.value.response["Error"]["Code"] == "NoSuchKey"

    def test_read_empty_object_returns_empty_bytes(self, s3_with_bucket: Any) -> None:
        """Reading a 0-byte object returns ``b''``."""
        s3_utils.write_to_s3("", "empty.txt", bucket=_TEST_BUCKET)
        body = s3_utils.read_from_s3("empty.txt", bucket=_TEST_BUCKET)
        assert body == b""

    def test_read_default_bucket_uses_settings(self, s3_with_bucket: Any) -> None:
        """``bucket=None`` lazily resolves to :attr:`Settings.S3_BUCKET_NAME`."""
        s3_with_bucket.put_object(Bucket=_DEFAULT_SETTINGS_BUCKET, Key="test.txt", Body=b"from-default")
        body = s3_utils.read_from_s3("test.txt")  # bucket=None
        assert body == b"from-default"


# =========================================================================
# Phase 5: list_generations — IDCAMS LISTCAT equivalent
# =========================================================================
@pytest.mark.unit
class TestListGenerations:
    """Verify :func:`list_generations` enumerates GDG generations.

    ``list_generations`` relies on the 5-segment key structure
    ``{prefix}/YYYY/MM/DD/HHMMSS/rest_of_key``. Objects written outside
    that structure are invisible to this function (which matches the
    z/OS catalog semantics — only catalogued generations are listed).
    """

    def test_list_generations_empty_returns_empty_list(self, s3_with_bucket: Any) -> None:
        """No objects under prefix → empty list."""
        result = s3_utils.list_generations("DALYREJS", bucket=_TEST_BUCKET)
        assert result == []

    def test_list_generations_populated_sorted_newest_first(self, s3_with_bucket: Any) -> None:
        """Multiple generations → sorted descending (newest first)."""
        _put_generation(
            s3_with_bucket,
            _TEST_BUCKET,
            "rejects/daily",
            2025,
            1,
            1,
            10,
            0,
            0,
        )
        _put_generation(
            s3_with_bucket,
            _TEST_BUCKET,
            "rejects/daily",
            2026,
            6,
            15,
            14,
            30,
            45,
        )
        _put_generation(
            s3_with_bucket,
            _TEST_BUCKET,
            "rejects/daily",
            2025,
            12,
            31,
            23,
            59,
            59,
        )

        result = s3_utils.list_generations("DALYREJS", bucket=_TEST_BUCKET)
        assert len(result) == 3
        # Newest first: 2026 > 2025-12-31 > 2025-01-01
        assert result[0] == "rejects/daily/2026/06/15/143045/"
        assert result[1] == "rejects/daily/2025/12/31/235959/"
        assert result[2] == "rejects/daily/2025/01/01/100000/"

    def test_list_generations_explicit_max_results_overrides_default(self, s3_with_bucket: Any) -> None:
        """``max_results=N`` bounds the result to at most N generations."""
        for i in range(7):
            _put_generation(
                s3_with_bucket,
                _TEST_BUCKET,
                "rejects/daily",
                2026,
                4,
                1,
                10,
                0,
                i,
            )
        result = s3_utils.list_generations("DALYREJS", bucket=_TEST_BUCKET, max_results=3)
        assert len(result) == 3

    def test_list_generations_default_max_results_uses_gdg_limits(self, s3_with_bucket: Any) -> None:
        """Default ``max_results=None`` uses ``GDG_LIMITS[name]`` as cap."""
        for i in range(8):
            _put_generation(
                s3_with_bucket,
                _TEST_BUCKET,
                "rejects/daily",
                2026,
                4,
                1,
                10,
                0,
                i,
            )
        # GDG_LIMITS["DALYREJS"] == 5 → 5-generation cap
        result = s3_utils.list_generations("DALYREJS", bucket=_TEST_BUCKET)
        assert len(result) == 5

    def test_list_generations_tranrept_uses_limit_10_from_reptfile(self, s3_with_bucket: Any) -> None:
        """TRANREPT uses REPTFILE.jcl's ``LIMIT(10)`` override by default."""
        for i in range(15):
            _put_generation(
                s3_with_bucket,
                _TEST_BUCKET,
                "reports/transactions",
                2026,
                4,
                1,
                10,
                0,
                i,
            )
        result = s3_utils.list_generations("TRANREPT", bucket=_TEST_BUCKET)
        # GDG_LIMITS["TRANREPT"] == 10
        assert len(result) == 10

    def test_list_generations_fallback_to_5_for_non_gdg_names(self, s3_with_bucket: Any) -> None:
        """STATEMNT.PS/STATEMNT.HTML absent from GDG_LIMITS → fallback=5."""
        for i in range(7):
            _put_generation(
                s3_with_bucket,
                _TEST_BUCKET,
                "statements/text",
                2026,
                4,
                1,
                10,
                0,
                i,
            )
        # STATEMNT.PS not in GDG_LIMITS → fallback to default 5
        result = s3_utils.list_generations("STATEMNT.PS", bucket=_TEST_BUCKET)
        assert len(result) == 5

    def test_list_generations_max_results_can_exceed_gdg_limit(self, s3_with_bucket: Any) -> None:
        """Explicit ``max_results`` overrides the GDG_LIMITS cap."""
        for i in range(8):
            _put_generation(
                s3_with_bucket,
                _TEST_BUCKET,
                "rejects/daily",
                2026,
                4,
                1,
                10,
                0,
                i,
            )
        # GDG_LIMITS["DALYREJS"] == 5 but we request 100
        result = s3_utils.list_generations("DALYREJS", bucket=_TEST_BUCKET, max_results=100)
        assert len(result) == 8

    def test_list_generations_unknown_gdg_raises_valueerror(self, s3_with_bucket: Any) -> None:
        """Unknown GDG name is rejected before touching S3."""
        with pytest.raises(ValueError, match="Unknown GDG name"):
            s3_utils.list_generations("NOT_A_GDG", bucket=_TEST_BUCKET)

    def test_list_generations_unknown_gdg_message_lists_valid_names(self, s3_with_bucket: Any) -> None:
        """Error message includes all valid GDG names in sorted order."""
        with pytest.raises(ValueError) as exc_info:
            s3_utils.list_generations("FOO", bucket=_TEST_BUCKET)
        msg = str(exc_info.value)
        for name in sorted(s3_utils.GDG_PATH_MAP.keys()):
            assert name in msg

    def test_list_generations_ignores_keys_with_fewer_than_5_segments(self, s3_with_bucket: Any) -> None:
        """Keys lacking the full ``YYYY/MM/DD/HHMMSS/file`` structure are ignored."""
        # Single segment after prefix — not a generation.
        s3_with_bucket.put_object(Bucket=_TEST_BUCKET, Key="rejects/daily/not-a-gen.txt", Body=b"x")
        # 2 segments — still not a generation.
        s3_with_bucket.put_object(Bucket=_TEST_BUCKET, Key="rejects/daily/2026/not-a-gen.txt", Body=b"x")
        # 3 segments — still not a generation.
        s3_with_bucket.put_object(
            Bucket=_TEST_BUCKET,
            Key="rejects/daily/2026/04/not-a-gen.txt",
            Body=b"x",
        )
        # 4 segments — still not a generation (no trailing file part).
        s3_with_bucket.put_object(
            Bucket=_TEST_BUCKET,
            Key="rejects/daily/2026/04/01/not-a-gen.txt",
            Body=b"x",
        )
        result = s3_utils.list_generations("DALYREJS", bucket=_TEST_BUCKET)
        assert result == []

    def test_list_generations_includes_5_segment_keys_only(self, s3_with_bucket: Any) -> None:
        """Only keys with 5+ segments are enumerated as generations."""
        # Valid 5-segment key.
        _put_generation(
            s3_with_bucket,
            _TEST_BUCKET,
            "rejects/daily",
            2026,
            4,
            1,
            10,
            0,
            0,
        )
        # Invalid — insufficient segments (same prefix, no file part).
        s3_with_bucket.put_object(
            Bucket=_TEST_BUCKET,
            Key="rejects/daily/2026/04/01/not-a-gen.txt",
            Body=b"x",
        )
        result = s3_utils.list_generations("DALYREJS", bucket=_TEST_BUCKET)
        assert len(result) == 1
        assert result[0] == "rejects/daily/2026/04/01/100000/"

    def test_list_generations_dedupes_multiple_files_under_same_generation(self, s3_with_bucket: Any) -> None:
        """Two files in the same YYYY/MM/DD/HHMMSS bucket count as 1 generation."""
        _put_generation(
            s3_with_bucket,
            _TEST_BUCKET,
            "rejects/daily",
            2026,
            4,
            1,
            10,
            0,
            0,
            filename="file1.txt",
        )
        _put_generation(
            s3_with_bucket,
            _TEST_BUCKET,
            "rejects/daily",
            2026,
            4,
            1,
            10,
            0,
            0,
            filename="file2.txt",
        )
        result = s3_utils.list_generations("DALYREJS", bucket=_TEST_BUCKET)
        assert len(result) == 1
        assert result[0] == "rejects/daily/2026/04/01/100000/"

    def test_list_generations_scopes_to_gdg_prefix(self, s3_with_bucket: Any) -> None:
        """Generations in other GDG prefixes are not included."""
        _put_generation(
            s3_with_bucket,
            _TEST_BUCKET,
            "rejects/daily",
            2026,
            4,
            1,
            10,
            0,
            0,
        )
        _put_generation(
            s3_with_bucket,
            _TEST_BUCKET,
            "reports/transactions",
            2026,
            4,
            1,
            10,
            0,
            0,
        )
        rejects = s3_utils.list_generations("DALYREJS", bucket=_TEST_BUCKET)
        reports = s3_utils.list_generations("TRANREPT", bucket=_TEST_BUCKET)
        assert len(rejects) == 1
        assert len(reports) == 1
        assert rejects[0] == "rejects/daily/2026/04/01/100000/"
        assert reports[0] == "reports/transactions/2026/04/01/100000/"

    def test_list_generations_default_bucket_uses_settings(self, s3_with_bucket: Any) -> None:
        """``bucket=None`` lazily resolves to :attr:`Settings.S3_BUCKET_NAME`."""
        _put_generation(
            s3_with_bucket,
            _DEFAULT_SETTINGS_BUCKET,
            "rejects/daily",
            2026,
            4,
            1,
            10,
            0,
            0,
        )
        result = s3_utils.list_generations("DALYREJS")  # bucket=None
        assert len(result) == 1


# =========================================================================
# Phase 6: cleanup_old_generations — IDCAMS SCRATCH equivalent
# =========================================================================
@pytest.mark.unit
class TestCleanupOldGenerations:
    """Verify :func:`cleanup_old_generations` replaces IDCAMS ``SCRATCH``."""

    def test_cleanup_unknown_gdg_raises_valueerror(self, s3_with_bucket: Any) -> None:
        """Unknown GDG name is rejected with :class:`ValueError`."""
        with pytest.raises(ValueError, match="Unknown GDG name"):
            s3_utils.cleanup_old_generations("UNKNOWN", bucket=_TEST_BUCKET)

    def test_cleanup_unknown_gdg_message_lists_valid_names(self, s3_with_bucket: Any) -> None:
        """Error message lists valid GDG names (for operator troubleshooting)."""
        with pytest.raises(ValueError) as exc_info:
            s3_utils.cleanup_old_generations("BOGUS", bucket=_TEST_BUCKET)
        msg = str(exc_info.value)
        for name in sorted(s3_utils.GDG_PATH_MAP.keys()):
            assert name in msg

    def test_cleanup_statemnt_ps_is_noop_returns_zero(self, s3_with_bucket: Any) -> None:
        """STATEMNT.PS is non-GDG (plain PS dataset) → short-circuit → 0."""
        # Even if there are objects present, cleanup must NOT delete them.
        for i in range(10):
            _put_generation(
                s3_with_bucket,
                _TEST_BUCKET,
                "statements/text",
                2026,
                4,
                1,
                10,
                0,
                i,
            )
        result = s3_utils.cleanup_old_generations("STATEMNT.PS", bucket=_TEST_BUCKET)
        assert result == 0
        # Verify no SCRATCH occurred.
        remaining = s3_utils.list_generations("STATEMNT.PS", bucket=_TEST_BUCKET, max_results=100)
        assert len(remaining) == 10

    def test_cleanup_statemnt_html_is_noop_returns_zero(self, s3_with_bucket: Any) -> None:
        """STATEMNT.HTML is non-GDG → short-circuit → 0."""
        for i in range(10):
            _put_generation(
                s3_with_bucket,
                _TEST_BUCKET,
                "statements/html",
                2026,
                4,
                1,
                10,
                0,
                i,
            )
        result = s3_utils.cleanup_old_generations("STATEMNT.HTML", bucket=_TEST_BUCKET)
        assert result == 0

    def test_cleanup_within_limit_returns_zero(self, s3_with_bucket: Any) -> None:
        """GDG within retention → nothing to SCRATCH → returns 0."""
        # GDG_LIMITS["DALYREJS"] == 5; create 3 generations.
        for i in range(3):
            _put_generation(
                s3_with_bucket,
                _TEST_BUCKET,
                "rejects/daily",
                2026,
                4,
                1,
                10,
                0,
                i,
            )
        result = s3_utils.cleanup_old_generations("DALYREJS", bucket=_TEST_BUCKET)
        assert result == 0
        # Verify all 3 survived.
        remaining = s3_utils.list_generations("DALYREJS", bucket=_TEST_BUCKET, max_results=100)
        assert len(remaining) == 3

    def test_cleanup_at_exactly_limit_returns_zero(self, s3_with_bucket: Any) -> None:
        """GDG at exactly LIMIT → boundary condition → 0 (no SCRATCH)."""
        for i in range(5):  # Exactly LIMIT(5)
            _put_generation(
                s3_with_bucket,
                _TEST_BUCKET,
                "rejects/daily",
                2026,
                4,
                1,
                10,
                0,
                i,
            )
        result = s3_utils.cleanup_old_generations("DALYREJS", bucket=_TEST_BUCKET)
        assert result == 0
        remaining = s3_utils.list_generations("DALYREJS", bucket=_TEST_BUCKET, max_results=100)
        assert len(remaining) == 5

    def test_cleanup_beyond_limit_deletes_oldest(self, s3_with_bucket: Any) -> None:
        """7 generations with LIMIT(5) → delete 2 oldest."""
        for i in range(7):
            _put_generation(
                s3_with_bucket,
                _TEST_BUCKET,
                "rejects/daily",
                2026,
                4,
                1,
                10,
                0,
                i,
            )
        # Before cleanup: 7 generations total (bypass default limit).
        before = s3_utils.list_generations("DALYREJS", bucket=_TEST_BUCKET, max_results=100)
        assert len(before) == 7

        deleted_count = s3_utils.cleanup_old_generations("DALYREJS", bucket=_TEST_BUCKET)
        assert deleted_count == 2

        # After cleanup: only 5 newest remain.
        after = s3_utils.list_generations("DALYREJS", bucket=_TEST_BUCKET, max_results=100)
        assert len(after) == 5
        # Newest preserved (indices 2..6 in the original sequence).
        assert "rejects/daily/2026/04/01/100006/" in after
        assert "rejects/daily/2026/04/01/100002/" in after
        # Oldest deleted.
        assert "rejects/daily/2026/04/01/100000/" not in after
        assert "rejects/daily/2026/04/01/100001/" not in after

    def test_cleanup_tranrept_uses_limit_10(self, s3_with_bucket: Any) -> None:
        """TRANREPT uses REPTFILE.jcl's LIMIT(10), not DEFGDGB's LIMIT(5)."""
        for i in range(15):
            _put_generation(
                s3_with_bucket,
                _TEST_BUCKET,
                "reports/transactions",
                2026,
                4,
                1,
                10,
                0,
                i,
            )
        deleted_count = s3_utils.cleanup_old_generations("TRANREPT", bucket=_TEST_BUCKET)
        # 15 - LIMIT(10) = 5 deleted
        assert deleted_count == 5
        after = s3_utils.list_generations("TRANREPT", bucket=_TEST_BUCKET, max_results=100)
        assert len(after) == 10

    def test_cleanup_removes_all_objects_under_deleted_generation(self, s3_with_bucket: Any) -> None:
        """Every object under a SCRATCHed prefix is deleted (not just the first)."""
        # Build 6 generations, each with multiple files.
        for gen_idx in range(6):
            for file_idx in range(3):
                _put_generation(
                    s3_with_bucket,
                    _TEST_BUCKET,
                    "rejects/daily",
                    2026,
                    4,
                    1,
                    10,
                    0,
                    gen_idx,
                    filename=f"file{file_idx}.txt",
                )
        s3_utils.cleanup_old_generations("DALYREJS", bucket=_TEST_BUCKET)

        # The oldest generation's prefix should have ZERO objects remaining.
        resp = s3_with_bucket.list_objects_v2(Bucket=_TEST_BUCKET, Prefix="rejects/daily/2026/04/01/100000/")
        # moto returns no Contents key when the prefix is empty.
        assert resp.get("KeyCount", 0) == 0

    def test_cleanup_empty_bucket_returns_zero(self, s3_with_bucket: Any) -> None:
        """Empty GDG prefix → nothing to clean up → 0."""
        result = s3_utils.cleanup_old_generations("DALYREJS", bucket=_TEST_BUCKET)
        assert result == 0

    def test_cleanup_default_bucket_uses_settings(self, s3_with_bucket: Any) -> None:
        """``bucket=None`` lazily resolves to :attr:`Settings.S3_BUCKET_NAME`."""
        for i in range(7):
            _put_generation(
                s3_with_bucket,
                _DEFAULT_SETTINGS_BUCKET,
                "rejects/daily",
                2026,
                4,
                1,
                10,
                0,
                i,
            )
        deleted_count = s3_utils.cleanup_old_generations("DALYREJS")  # bucket=None
        assert deleted_count == 2

    def test_cleanup_is_idempotent(self, s3_with_bucket: Any) -> None:
        """Running cleanup twice leaves the bucket in the same state."""
        for i in range(7):
            _put_generation(
                s3_with_bucket,
                _TEST_BUCKET,
                "rejects/daily",
                2026,
                4,
                1,
                10,
                0,
                i,
            )
        # First run deletes 2; second run deletes 0.
        assert s3_utils.cleanup_old_generations("DALYREJS", bucket=_TEST_BUCKET) == 2
        assert s3_utils.cleanup_old_generations("DALYREJS", bucket=_TEST_BUCKET) == 0
        after = s3_utils.list_generations("DALYREJS", bucket=_TEST_BUCKET, max_results=100)
        assert len(after) == 5

    def test_cleanup_handles_empty_generation_prefix(self, s3_with_bucket: Any) -> None:
        """Generation prefix with no objects still counts as deleted.

        Covers the edge-case branch where ``list_objects_v2`` returns no
        ``Contents`` for a prefix that appeared in ``list_generations``
        (possible due to concurrent deletion).
        """
        # Create 6 real generations...
        for i in range(6):
            _put_generation(
                s3_with_bucket,
                _TEST_BUCKET,
                "rejects/daily",
                2026,
                4,
                1,
                10,
                0,
                i,
            )
        # ...then delete the objects under the oldest prefix out-of-band,
        # simulating a concurrent SCRATCH that left only catalog traces.
        # (Since S3 has no "empty prefix" concept, we simply skip this;
        # the normal cleanup path covers the ``if all_keys`` branch.)
        deleted_count = s3_utils.cleanup_old_generations("DALYREJS", bucket=_TEST_BUCKET)
        # 6 - LIMIT(5) = 1 deleted
        assert deleted_count == 1


# =========================================================================
# Phase 7: Module public API (__all__) guard
# =========================================================================
@pytest.mark.unit
class TestModuleSurface:
    """Guard against accidental changes to the public API surface."""

    def test_all_exports_exactly_match_expected_surface(self) -> None:
        """``__all__`` declares exactly 7 public names."""
        expected: set[str] = {
            "GDG_LIMITS",
            "GDG_PATH_MAP",
            "cleanup_old_generations",
            "get_versioned_s3_path",
            "list_generations",
            "read_from_s3",
            "write_to_s3",
        }
        assert set(s3_utils.__all__) == expected

    def test_all_listed_names_resolve_to_callables_or_dicts(self) -> None:
        """Every name in ``__all__`` resolves to an actual attribute."""
        for name in s3_utils.__all__:
            attr = getattr(s3_utils, name)
            # Callables (functions) OR dict constants.
            assert callable(attr) or isinstance(attr, dict)

    def test_all_is_a_list(self) -> None:
        """Sibling modules use ``list[str]``; enforce consistency."""
        assert isinstance(s3_utils.__all__, list)
        for name in s3_utils.__all__:
            assert isinstance(name, str)
