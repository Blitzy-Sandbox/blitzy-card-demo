#!/usr/bin/env python3
# *****************************************************************
# Program     : redact_auth_body.py
# Application : CardDemo
# Type        : CI LOG-HYGIENE FILTER (Python 3)
# Function    : Mask any bearer token in a captured sign-on response, and render
#               that response for a job log WITHOUT its credential values.
# Derived from : the packaged-image API smoke step of
#               .github/workflows/build.yml, whose failure branches previously
#               printed sign-on response bodies verbatim.
# *****************************************************************
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
# *****************************************************************
"""Keep credentials out of the CI job log.

WHY THIS EXISTS. The packaged-image smoke step drives four sign-on requests and
writes each response to a file so a failing probe can explain itself. Those
explanations were ``cat`` of the response body. Three of the four bodies can
legitimately carry a JWT and only the bootstrap token was masked, so the single
regression the wildcard-media-type probe exists to catch - a negotiation failure
answered with ``200`` and a normal token instead of ``415`` - would have printed a
live credential into a job log that persists for the artefact retention period.

WHY A SCRIPT RATHER THAN SHELL IN THE WORKFLOW. Two reasons, and the second is the
important one. A multi-line Python heredoc cannot be embedded in a YAML block
scalar without its body sitting at column zero, which terminates the scalar. But
more than that: logic that protects a credential should be executable OUTSIDE the
workflow, so it can be tested by planting a sentinel token and proving the
sentinel cannot appear in the output. A shell function inside a job step cannot be
tested that way; this can, and is.

TWO MODES, AND THE ORDER MATTERS.

``--mask`` emits ``::add-mask::`` directives for every token found. It runs
IMMEDIATELY after each request, before any branch can print anything, because
masking after a print does not unprint it. It is deliberately indifferent to the
HTTP status: a token is masked because it is PRESENT, never because the response
was one that was expected to carry one - the whole point is the response nobody
expected to carry one.

``--describe`` is what replaces ``cat``. It prints the body's length and its KEY
SET, which is the structural information a diagnostic actually needs, plus the
values of an explicit allowlist of non-sensitive fields so the authored problem
envelope stays legible. A value is printed ONLY if its key is on that allowlist,
so redaction is allowlist-driven rather than denylist-driven: an unrecognised
token-bearing key is reported as present with its value withheld, instead of being
echoed because nobody thought to add it to a list of things to hide. Masking is
therefore belt and braces rather than the only control - even with the runner's
scrubber bypassed entirely, this never emits the value.
"""

from __future__ import annotations

import argparse
import json
import sys

# Values that may be printed. Everything else is reported as present, with its
# value withheld. This list is deliberately short and deliberately an ALLOWLIST:
# a denylist would print any credential field whose name was not anticipated.
PRINTABLE_KEYS = frozenset(
    {
        "code",
        "title",
        "status",
        "type",
        "instance",
        "timestamp",
        "correlationId",
        "error",
        "message",
        "path",
        "detail",
        "userId",
        "userType",
    }
)

# A key whose name suggests it carries a credential. Used only by --mask, to
# decide what to hand the scrubber; --describe does not consult it, because
# --describe withholds everything that is not explicitly printable.
_SECRET_HINTS = ("token", "secret", "password", "credential", "authorization", "jwt")


def load(path: str) -> object | None:
    """Parse the captured body, returning None when it is absent or not JSON."""
    try:
        with open(path, "rb") as handle:
            raw = handle.read()
    except OSError:
        return None
    if not raw.strip():
        return None
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None


def secret_values(body: object) -> list[str]:
    """Collect every credential-looking string, at any depth."""
    found: list[str] = []

    def walk(node: object, key_hint: str = "") -> None:
        if isinstance(node, dict):
            for key, value in node.items():
                walk(value, str(key))
        elif isinstance(node, list):
            for item in node:
                walk(item, key_hint)
        elif isinstance(node, str) and node:
            lowered = key_hint.lower()
            if any(hint in lowered for hint in _SECRET_HINTS):
                found.append(node)

    walk(body)
    return found


def emit_masks(body: object) -> int:
    """Print ::add-mask:: for every credential found. Returns how many."""
    count = 0
    for value in secret_values(body):
        print(f"::add-mask::{value}")
        count += 1
        # Each dot-delimited segment as well as the whole value. The runner
        # scrubs exact matches, and a wrapped or re-encoded log line can be split
        # at a dot, which would leave an unscrubbed segment behind.
        for segment in value.split("."):
            if len(segment) >= 8:
                print(f"::add-mask::{segment}")
                count += 1
    return count


def describe(path: str, body: object) -> None:
    """Render the body for a log without printing any withheld value."""
    try:
        with open(path, "rb") as handle:
            size = len(handle.read())
    except OSError:
        size = 0

    if size == 0:
        print("    body: empty")
        return
    print(f"    body: {size} bytes")

    if body is None:
        print("    body is not JSON; contents withheld rather than echoed")
        return
    if not isinstance(body, dict):
        print(f"    body is a JSON {type(body).__name__}; contents withheld")
        return

    print(f"    keys: {sorted(body)}")
    for key in sorted(body):
        value = body[key]
        if key in PRINTABLE_KEYS and isinstance(value, (str, int, float, bool)):
            print(f"    {key} = {value}")
        else:
            print(f"    {key} = <withheld, {len(str(value))} characters>")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", help="the captured response body")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--mask", action="store_true", help="emit ::add-mask:: directives")
    mode.add_argument("--describe", action="store_true", help="render without credentials")
    arguments = parser.parse_args()

    body = load(arguments.path)
    if arguments.mask:
        emit_masks(body)
    else:
        describe(arguments.path, body)
    # Always successful: this is a log filter, and a body it cannot parse must
    # not fail the probe whose result the caller is about to report.
    return 0


if __name__ == "__main__":
    sys.exit(main())
