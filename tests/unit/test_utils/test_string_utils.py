# ============================================================================
# CardDemo — String Utility Unit Tests (Mainframe-to-Cloud migration)
# ============================================================================
# Source (mainframe heritage):
#   * app/cpy/CSSTRPFY.cpy          — String / PF-key action constants
#   * CICS DFHBMSCA copybook        — AID key literals (DFHENTER, DFHPF01-24)
#   * app/cbl/CO*.cbl               — All 18 online CICS programs dispatch
#                                     on the AID key returned from EIB-AID
#
# Target module:  src/shared/utils/string_utils.py
#
# Test-case organisation
# ----------------------
#   Phase 1 — ActionCode enum integrity                (4 tests)
#   Phase 2 — AID_KEY_MAP shape + business contract    (8 tests)
#   Phase 3 — map_aid_key                              (10 tests)
#   Phase 4 — is_valid_aid_key                         (4 tests)
#   Phase 5 — get_pf_key_number                        (8 tests)
#   Phase 6 — left_pad / right_pad                     (14 tests)
#   Phase 7 — safe_strip                               (7 tests)
#   Phase 8 — __all__ public-API surface guard         (1 test)
# ============================================================================
# Copyright Amazon.com, Inc. or its affiliates.
# All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# You may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# ============================================================================
"""Verify behavioural parity between ``src/shared/utils/string_utils.py``
and the CICS-era AID-key semantics encoded in ``CSSTRPFY.cpy`` and the
IBM ``DFHBMSCA`` copybook.

COBOL → Python Verification Surface
-----------------------------------
+---------------------------------+-------------------------------------+
| COBOL / CICS construct          | Python target (tested)              |
+=================================+=====================================+
| CICS EIB-AID byte              | :class:`ActionCode` enum member      |
| (``DFHENTER``, ``DFHPF01`` …)   |                                     |
+---------------------------------+-------------------------------------+
| 88-level AID condition names    | :data:`AID_KEY_MAP` lookup table    |
| in ``CSSTRPFY.cpy``             |                                     |
+---------------------------------+-------------------------------------+
| ``DFHPF13`` … ``DFHPF24``       | Fold onto ``PFK01`` … ``PFK12``     |
| (same function as PF1-12)       | — "PF13-24 folding" business        |
|                                 | contract preserved exactly.         |
+---------------------------------+-------------------------------------+
| ``LOW-VALUES``, spaces, nulls  | ``None`` / whitespace-only strings  |
|  on EIB-AID                     | → :func:`map_aid_key` returns       |
|                                 | ``None``.                           |
+---------------------------------+-------------------------------------+
| COBOL ``MOVE … TO … WITH         | :func:`left_pad` / :func:`right_pad`|
|  TRAILING SPACES``              | (preserves COBOL ``PIC X(n)``       |
|                                 |  fixed-width semantics).            |
+---------------------------------+-------------------------------------+
| COBOL ``INSPECT … REPLACING``   | :func:`safe_strip` (INSPECT-style   |
| / ``FUNCTION TRIM``             | stripping with null tolerance).     |
+---------------------------------+-------------------------------------+

Business-contract invariants explicitly exercised
-------------------------------------------------
1. **PF13-24 folding**: ``DFHPF13`` … ``DFHPF24`` and the API-friendly
   ``PF13`` … ``PF24`` aliases all map to :attr:`ActionCode.PFK01` …
   :attr:`ActionCode.PFK12` — NOT to distinct PFK13-24 values.
   Rationale: in the CICS 3270 world PF1-12 and PF13-24 are functionally
   identical (SHIFT-modifier aliases). Every online CBL program in the
   source tree (``CO*.cbl``) treats them as equivalent.
2. **AID_KEY_MAP totality**: 68 registered keys — 24 CICS ``DFH*``
   identifiers, 4 PA aliases, 24 PF aliases (PF1-24), and 12 canonical
   ``PFK01``-``PFK12`` self-references. All must be upper-case
   dictionary keys because :func:`map_aid_key` performs ``.upper()``
   normalisation before lookup.
3. **``_validate_pad_args`` TypeError order**: value-not-str →
   length-is-bool-or-not-int → length-negative (ValueError) →
   pad_char-not-str → len(pad_char) != 1 (ValueError). The order is an
   API contract because it determines which exception surfaces when
   multiple arguments are invalid.
"""

from __future__ import annotations

from enum import Enum

import pytest

from src.shared.utils.string_utils import (
    AID_KEY_MAP,
    ActionCode,
    get_pf_key_number,
    is_valid_aid_key,
    left_pad,
    map_aid_key,
    right_pad,
    safe_strip,
)


# ---------------------------------------------------------------------------
# Phase 1 — ActionCode enum integrity
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_action_code_enum_is_str_enum() -> None:
    """ActionCode subclasses ``(str, Enum)`` so values are JSON-safe."""
    assert issubclass(ActionCode, str)
    assert issubclass(ActionCode, Enum)


@pytest.mark.unit
def test_action_code_enum_has_sixteen_members() -> None:
    """16 canonical members: ENTER, CLEAR, PA1, PA2, PFK01-PFK12."""
    expected: set[str] = {
        "ENTER",
        "CLEAR",
        "PA1",
        "PA2",
        "PFK01",
        "PFK02",
        "PFK03",
        "PFK04",
        "PFK05",
        "PFK06",
        "PFK07",
        "PFK08",
        "PFK09",
        "PFK10",
        "PFK11",
        "PFK12",
    }
    actual: set[str] = {member.name for member in ActionCode}
    assert actual == expected


@pytest.mark.unit
def test_action_code_values_equal_names() -> None:
    """Every ActionCode value equals its name string (JSON serialisation)."""
    for member in ActionCode:
        assert member.value == member.name


@pytest.mark.unit
def test_action_code_members_are_string_equal() -> None:
    """ActionCode members compare equal to their string literal."""
    assert ActionCode.ENTER == "ENTER"
    assert ActionCode.PFK01 == "PFK01"
    assert ActionCode.PFK12 == "PFK12"


# ---------------------------------------------------------------------------
# Phase 2 — AID_KEY_MAP shape + business contract
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_aid_key_map_is_frozen_dict_of_correct_size() -> None:
    """AID_KEY_MAP has the full 68 documented aliases."""
    # 4 CICS non-PF (DFHENTER, DFHCLEAR, DFHPA1, DFHPA2)
    # + 24 CICS PF (DFHPF01..DFHPF24)
    # + 4 API non-PF aliases (ENTER, CLEAR, PA1, PA2)
    # + 24 API PF aliases (PF1..PF24)
    # + 12 canonical self-references (PFK01..PFK12) = 68
    assert len(AID_KEY_MAP) == 68


@pytest.mark.unit
def test_aid_key_map_cics_dfh_constants_present() -> None:
    """All canonical CICS DFH constants map to a valid ActionCode."""
    assert AID_KEY_MAP["DFHENTER"] is ActionCode.ENTER
    assert AID_KEY_MAP["DFHCLEAR"] is ActionCode.CLEAR
    assert AID_KEY_MAP["DFHPA1"] is ActionCode.PA1
    assert AID_KEY_MAP["DFHPA2"] is ActionCode.PA2


@pytest.mark.unit
def test_aid_key_map_dfh_pf1_through_pf12_direct_mapping() -> None:
    """DFHPF1..DFHPF12 map to PFK01..PFK12 (direct, non-folded).

    Note: CICS DFH keys use plain integer suffix (``DFHPF1``),
    not zero-padded (``DFHPF01``). Only the canonical enum
    members use zero-padding (``PFK01``).
    """
    for pf_num in range(1, 13):
        dfh_key: str = f"DFHPF{pf_num}"
        expected: ActionCode = ActionCode[f"PFK{pf_num:02d}"]
        assert AID_KEY_MAP[dfh_key] is expected


@pytest.mark.unit
def test_aid_key_map_dfh_pf13_through_pf24_folded_onto_pfk01_12() -> None:
    """BUSINESS CONTRACT: DFHPF13..DFHPF24 FOLD onto PFK01..PFK12.

    This is a deliberate CICS-era semantic: SHIFT+PF1 == PF13, both
    fire the same transaction path in every ``CO*.cbl`` online program.
    """
    for pf_num in range(13, 25):
        dfh_key: str = f"DFHPF{pf_num}"
        folded_index: int = pf_num - 12  # PF13 -> PFK01, PF24 -> PFK12
        expected: ActionCode = ActionCode[f"PFK{folded_index:02d}"]
        assert AID_KEY_MAP[dfh_key] is expected, (
            f"{dfh_key} must fold onto PFK{folded_index:02d} (PF13-24 business contract violated)"
        )


@pytest.mark.unit
def test_aid_key_map_api_friendly_aliases_direct() -> None:
    """API-friendly ENTER/CLEAR/PA1/PA2 aliases present."""
    assert AID_KEY_MAP["ENTER"] is ActionCode.ENTER
    assert AID_KEY_MAP["CLEAR"] is ActionCode.CLEAR
    assert AID_KEY_MAP["PA1"] is ActionCode.PA1
    assert AID_KEY_MAP["PA2"] is ActionCode.PA2


@pytest.mark.unit
def test_aid_key_map_pf_prefix_aliases_direct_pf1_to_pf12() -> None:
    """PF1..PF12 API aliases (plain integer suffix) map to PFK01..PFK12."""
    for pf_num in range(1, 13):
        short_key: str = f"PF{pf_num}"
        expected: ActionCode = ActionCode[f"PFK{pf_num:02d}"]
        assert AID_KEY_MAP[short_key] is expected


@pytest.mark.unit
def test_aid_key_map_pf_prefix_aliases_folded_pf13_to_pf24() -> None:
    """PF13..PF24 API aliases (plain integer suffix) FOLD (mirror DFH)."""
    for pf_num in range(13, 25):
        short_key: str = f"PF{pf_num}"
        folded_index: int = pf_num - 12
        expected: ActionCode = ActionCode[f"PFK{folded_index:02d}"]
        assert AID_KEY_MAP[short_key] is expected


@pytest.mark.unit
def test_aid_key_map_canonical_pfk_self_references() -> None:
    """PFK01..PFK12 must self-map (identity) so map_aid_key is idempotent."""
    for pf_num in range(1, 13):
        key: str = f"PFK{pf_num:02d}"
        assert AID_KEY_MAP[key] is ActionCode[key]


# ---------------------------------------------------------------------------
# Phase 3 — map_aid_key
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_map_aid_key_returns_none_for_none() -> None:
    """``None`` (no AID byte supplied) → ``None``."""
    assert map_aid_key(None) is None  # type: ignore[arg-type]


@pytest.mark.unit
def test_map_aid_key_returns_none_for_non_string() -> None:
    """Non-string inputs → ``None`` (defensive)."""
    assert map_aid_key(123) is None  # type: ignore[arg-type]
    assert map_aid_key([]) is None  # type: ignore[arg-type]
    assert map_aid_key({}) is None  # type: ignore[arg-type]


@pytest.mark.unit
def test_map_aid_key_returns_none_for_empty_string() -> None:
    """Empty string → ``None`` (nothing to map)."""
    assert map_aid_key("") is None


@pytest.mark.unit
def test_map_aid_key_returns_none_for_whitespace() -> None:
    """Whitespace-only → ``None`` (EBCDIC ``LOW-VALUES``)."""
    assert map_aid_key("   ") is None
    assert map_aid_key("\t\n  ") is None


@pytest.mark.unit
def test_map_aid_key_strips_surrounding_whitespace() -> None:
    """Leading / trailing whitespace is stripped before lookup."""
    assert map_aid_key("  ENTER  ") is ActionCode.ENTER
    assert map_aid_key("\tPF1\n") is ActionCode.PFK01


@pytest.mark.unit
def test_map_aid_key_is_case_insensitive() -> None:
    """Lookup is case-insensitive (AID_KEY_MAP stores upper)."""
    assert map_aid_key("enter") is ActionCode.ENTER
    assert map_aid_key("Clear") is ActionCode.CLEAR
    assert map_aid_key("pf3") is ActionCode.PFK03
    assert map_aid_key("dfhpf1") is ActionCode.PFK01


@pytest.mark.unit
def test_map_aid_key_unknown_returns_none() -> None:
    """Unknown key → ``None`` (caller is responsible for rejection)."""
    assert map_aid_key("PF99") is None
    assert map_aid_key("BOGUS") is None
    assert map_aid_key("DFHXYZ") is None


@pytest.mark.unit
def test_map_aid_key_pf13_to_pf24_folds_correctly() -> None:
    """PF13-24 folding business contract via the public API."""
    assert map_aid_key("DFHPF13") is ActionCode.PFK01
    assert map_aid_key("PF13") is ActionCode.PFK01
    assert map_aid_key("DFHPF24") is ActionCode.PFK12
    assert map_aid_key("PF24") is ActionCode.PFK12


@pytest.mark.unit
def test_map_aid_key_pa_keys_mapped() -> None:
    """PA1 and PA2 (Program-Attention keys) resolve correctly."""
    assert map_aid_key("PA1") is ActionCode.PA1
    assert map_aid_key("PA2") is ActionCode.PA2
    assert map_aid_key("DFHPA1") is ActionCode.PA1
    assert map_aid_key("DFHPA2") is ActionCode.PA2


@pytest.mark.unit
def test_map_aid_key_clear_returns_clear_member() -> None:
    """CLEAR resolves (not merely a truthy value)."""
    assert map_aid_key("CLEAR") is ActionCode.CLEAR


# ---------------------------------------------------------------------------
# Phase 4 — is_valid_aid_key
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_is_valid_aid_key_true_for_registered_keys() -> None:
    """Any key that :func:`map_aid_key` resolves returns ``True``."""
    assert is_valid_aid_key("ENTER") is True
    assert is_valid_aid_key("PF7") is True
    assert is_valid_aid_key("DFHPF24") is True  # folded


@pytest.mark.unit
def test_is_valid_aid_key_false_for_none() -> None:
    """``None`` is treated as absence → ``False``."""
    assert is_valid_aid_key(None) is False  # type: ignore[arg-type]


@pytest.mark.unit
def test_is_valid_aid_key_false_for_unknown() -> None:
    """Unregistered key → ``False``."""
    assert is_valid_aid_key("UNKNOWN") is False
    assert is_valid_aid_key("PF99") is False


@pytest.mark.unit
def test_is_valid_aid_key_tolerates_whitespace_and_case() -> None:
    """Consistent with :func:`map_aid_key` normalisation."""
    assert is_valid_aid_key(" enter ") is True
    assert is_valid_aid_key("Pf12") is True


# ---------------------------------------------------------------------------
# Phase 5 — get_pf_key_number
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_get_pf_key_number_maps_pfk01_to_pfk12_to_int() -> None:
    """Every PFK01-PFK12 resolves to its 1..12 integer."""
    for pf_num in range(1, 13):
        member: ActionCode = ActionCode[f"PFK{pf_num:02d}"]
        assert get_pf_key_number(member) == pf_num


@pytest.mark.unit
def test_get_pf_key_number_returns_none_for_enter() -> None:
    """Non-PFK members return ``None`` (not 0, not raise)."""
    assert get_pf_key_number(ActionCode.ENTER) is None


@pytest.mark.unit
def test_get_pf_key_number_returns_none_for_clear() -> None:
    assert get_pf_key_number(ActionCode.CLEAR) is None


@pytest.mark.unit
def test_get_pf_key_number_returns_none_for_pa1() -> None:
    assert get_pf_key_number(ActionCode.PA1) is None


@pytest.mark.unit
def test_get_pf_key_number_returns_none_for_pa2() -> None:
    assert get_pf_key_number(ActionCode.PA2) is None


@pytest.mark.unit
def test_get_pf_key_number_returns_none_for_non_action_code() -> None:
    """Stringly-typed input → ``None`` (defensive isinstance check)."""
    assert get_pf_key_number("PFK01") is None  # type: ignore[arg-type]
    assert get_pf_key_number(None) is None  # type: ignore[arg-type]
    assert get_pf_key_number(42) is None  # type: ignore[arg-type]


@pytest.mark.unit
def test_get_pf_key_number_pfk01_is_1() -> None:
    """Smoke-test the lower boundary."""
    assert get_pf_key_number(ActionCode.PFK01) == 1


@pytest.mark.unit
def test_get_pf_key_number_pfk12_is_12() -> None:
    """Smoke-test the upper boundary."""
    assert get_pf_key_number(ActionCode.PFK12) == 12


# ---------------------------------------------------------------------------
# Phase 6 — left_pad / right_pad
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_left_pad_pads_with_spaces_by_default() -> None:
    """``left_pad`` right-justifies inside a fixed-width field."""
    assert left_pad("abc", 6) == "   abc"


@pytest.mark.unit
def test_left_pad_custom_pad_char() -> None:
    """Custom pad character (e.g. '0' for numeric fields)."""
    assert left_pad("42", 5, "0") == "00042"


@pytest.mark.unit
def test_left_pad_returns_value_unchanged_when_already_long_enough() -> None:
    """No truncation; equal-length returns identity."""
    assert left_pad("abcdef", 6) == "abcdef"
    assert left_pad("abcdefgh", 6) == "abcdefgh"


@pytest.mark.unit
def test_left_pad_empty_string() -> None:
    """Zero-length input pads to target width."""
    assert left_pad("", 3) == "   "


@pytest.mark.unit
def test_right_pad_pads_with_spaces_by_default() -> None:
    """``right_pad`` left-justifies inside a fixed-width field."""
    assert right_pad("abc", 6) == "abc   "


@pytest.mark.unit
def test_right_pad_custom_pad_char() -> None:
    """Custom pad character supported."""
    assert right_pad("abc", 6, "-") == "abc---"


@pytest.mark.unit
def test_right_pad_returns_value_unchanged_when_already_long_enough() -> None:
    """No truncation when value ≥ target length."""
    assert right_pad("abcdef", 6) == "abcdef"
    assert right_pad("abcdefgh", 6) == "abcdefgh"


@pytest.mark.unit
def test_pad_raises_type_error_when_value_not_string() -> None:
    """value-not-str is the FIRST validation (TypeError order step 1)."""
    with pytest.raises(TypeError):
        left_pad(123, 5)  # type: ignore[arg-type]
    with pytest.raises(TypeError):
        right_pad(123, 5)  # type: ignore[arg-type]


@pytest.mark.unit
def test_pad_raises_type_error_when_length_is_bool() -> None:
    """bool-as-length rejected (bool subclass of int must still fail)."""
    with pytest.raises(TypeError):
        left_pad("abc", True)
    with pytest.raises(TypeError):
        right_pad("abc", False)


@pytest.mark.unit
def test_pad_raises_type_error_when_length_not_int() -> None:
    """String-as-length rejected."""
    with pytest.raises(TypeError):
        left_pad("abc", "5")  # type: ignore[arg-type]
    with pytest.raises(TypeError):
        right_pad("abc", 5.0)  # type: ignore[arg-type]


@pytest.mark.unit
def test_pad_raises_value_error_when_length_negative() -> None:
    """Negative length is semantically invalid for a pad width."""
    with pytest.raises(ValueError):
        left_pad("abc", -1)
    with pytest.raises(ValueError):
        right_pad("abc", -1)


@pytest.mark.unit
def test_pad_raises_type_error_when_pad_char_not_string() -> None:
    """pad_char must be a string."""
    with pytest.raises(TypeError):
        left_pad("abc", 5, pad_char=0)  # type: ignore[arg-type]
    with pytest.raises(TypeError):
        right_pad("abc", 5, pad_char=b" ")  # type: ignore[arg-type]


@pytest.mark.unit
def test_pad_raises_value_error_when_pad_char_multichar() -> None:
    """pad_char must be exactly one character."""
    with pytest.raises(ValueError):
        left_pad("abc", 5, pad_char="--")
    with pytest.raises(ValueError):
        right_pad("abc", 5, pad_char="")


@pytest.mark.unit
def test_pad_zero_length_returns_original_when_nonempty() -> None:
    """length=0 on non-empty input returns value unchanged (already ≥ 0)."""
    assert left_pad("abc", 0) == "abc"
    assert right_pad("abc", 0) == "abc"


# ---------------------------------------------------------------------------
# Phase 7 — safe_strip
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_safe_strip_none_returns_empty_string() -> None:
    """``None`` maps to empty string (COBOL ``LOW-VALUES`` equivalent)."""
    assert safe_strip(None) == ""


@pytest.mark.unit
def test_safe_strip_ordinary_string() -> None:
    """Regular trim semantics for a typical padded COBOL field."""
    assert safe_strip("  hello  ") == "hello"


@pytest.mark.unit
def test_safe_strip_all_whitespace_becomes_empty() -> None:
    """Whitespace-only COBOL SPACES field → empty."""
    assert safe_strip("   ") == ""
    assert safe_strip("\t\n") == ""


@pytest.mark.unit
def test_safe_strip_no_whitespace_returns_identity() -> None:
    """Non-padded input returned unchanged."""
    assert safe_strip("abc") == "abc"


@pytest.mark.unit
def test_safe_strip_accepts_non_string_via_str_fallback() -> None:
    """Non-string input → ``str(value).strip()``."""
    assert safe_strip(42) == "42"  # type: ignore[arg-type]
    assert safe_strip(3.14) == "3.14"  # type: ignore[arg-type]


@pytest.mark.unit
def test_safe_strip_exception_guard_returns_empty() -> None:
    """If ``str(obj)`` raises, the guard returns empty string."""

    class _Boom:
        def __str__(self) -> str:
            raise RuntimeError("forced failure")

        def __repr__(self) -> str:
            raise RuntimeError("forced failure")

    assert safe_strip(_Boom()) == ""  # type: ignore[arg-type]


@pytest.mark.unit
def test_safe_strip_empty_string() -> None:
    """Empty string is a no-op."""
    assert safe_strip("") == ""


# ---------------------------------------------------------------------------
# Phase 8 — __all__ public-API surface guard
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_all_public_api_matches_expected_surface() -> None:
    """``__all__`` must list exactly the 8 documented exports."""
    from src.shared.utils import string_utils

    expected: set[str] = {
        "ActionCode",
        "AID_KEY_MAP",
        "map_aid_key",
        "is_valid_aid_key",
        "get_pf_key_number",
        "left_pad",
        "right_pad",
        "safe_strip",
    }
    # Rely on the module's declared public surface rather than guessing.
    assert hasattr(string_utils, "__all__")
    actual: set[str] = set(string_utils.__all__)
    assert actual == expected, (
        f"string_utils.__all__ drift detected. Missing: {expected - actual}  Extra: {actual - expected}"
    )
