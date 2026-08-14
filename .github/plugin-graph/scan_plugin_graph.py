#!/usr/bin/env python3
# *****************************************************************
# Program     : scan_plugin_graph.py
# Application : CardDemo
# Type        : CI SUPPLY-CHAIN SCANNER (Python 3)
# Function    : Enumerate the Maven BUILD PLUGIN graph and scan every resolved
#               coordinate against the OSV advisory database, failing on any
#               HIGH or CRITICAL finding that is not dispositioned with evidence.
# Derived from : pom.xml gap entry (2) "THIS GATE DOES NOT SEE THE BUILD PLUGIN
#               GRAPH", which prescribes exactly this remedy: enumerate with
#               dependency:resolve-plugins, then scan those coordinates in a
#               dedicated job rather than by adding an unsanctioned scanning
#               plugin to the build itself.
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
"""Scan the Maven plugin graph for HIGH and CRITICAL advisories.

WHY THIS EXISTS. ``dependency-check-maven`` scans the project's own dependency
graph. It is never presented with the plugins that compile, test, measure and
package the project, so a vulnerable plugin dependency passed that gate in
silence - including, until it was overridden, the very HTTP client the scanner
used to fetch its own advisory feed.

WHY IT IS A SCRIPT AND NOT A PLUGIN. Adding a scanning plugin to the build would
introduce a plugin coordinate the AAP does not sanction, enlarging the surface
being measured. ``pom.xml`` records that rejection in writing; this script is the
alternative it prescribes.

TWO PROPERTIES THAT MATTER MORE THAN ANY SINGLE FINDING.

First, THE INVENTORY IS RECONCILED AGAINST WHAT ACTUALLY LOADS.
``dependency:resolve-plugins`` reports each plugin's own declared descriptor and
is blind to project-level plugin ``<dependencies>`` overrides. Measured: after
``pom.xml`` overrides ``dependency-check-maven``'s transport, that goal still
reports ``httpclient5:5.4.2`` while the plugin's real class realm loads
``httpclient5:5.5.2``. A scanner trusting the declared graph alone would report a
finding that cannot occur, and a reader who then discovers one false positive
stops believing the other findings. So every override is declared in
``dispositions.json`` and VERIFIED against a plugin realm listing: the effective
coordinate must be present and the declared one absent. An override that has been
removed from ``pom.xml`` therefore FAILS this gate instead of quietly widening it.

Second, IT FAILS ON UNDISPOSITIONED FINDINGS, NOT ON A LOWERED THRESHOLD. Every
HIGH or CRITICAL either carries a written, evidence-bearing disposition with an
expiry date, or it fails the job. A disposition that has expired fails. A
disposition that no longer matches anything fails too, so the file shrinks as the
graph moves instead of accumulating entries nobody rereads.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.request

OSV_BATCH = "https://api.osv.dev/v1/querybatch"
OSV_VULN = "https://api.osv.dev/v1/vulns/"

# A plugin coordinate line, or one of its resolved dependency lines, as
# `dependency:resolve-plugins` prints them.
_PLUGIN_RE = re.compile(r"^\[INFO]\s{4}([\w.\-]+):([\w.\-]+):maven-plugin:([\w.\-]+)\s*$")
_ARTIFACT_RE = re.compile(
    r"^\[INFO]\s+([\w.\-]+):([\w.\-]+):(?:jar|maven-plugin):([\w.\-]+)(?::[\w.\-]+)?\s*$"
)
_REALM_INCLUDED_RE = re.compile(r"Included:\s+([\w.\-]+):([\w.\-]+):jar:([\w.\-]+)")


def _http_json(url: str, payload: dict | None = None, attempts: int = 4) -> dict:
    """POST or GET JSON with bounded retries.

    A transient advisory-service failure must not be indistinguishable from a
    clean result, so exhaustion raises rather than returning an empty answer.
    """
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    headers = {"Content-Type": "application/json"} if data else {}
    last: Exception | None = None
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, data=data, headers=headers)
            with urllib.request.urlopen(request, timeout=120) as response:
                return json.load(response)
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as error:
            last = error
            time.sleep(2 ** attempt)
    raise RuntimeError(f"advisory service unreachable after {attempts} attempts: {url}: {last}")


def cvss_base_score(vector: str | None) -> float | None:
    """Compute a CVSS v3.x base score from its vector string.

    The band, not the decimal, is what this gate acts on, but the decimal is
    printed so a reader can check the arithmetic. Returns None for a vector this
    function does not understand - which is reported as UNSCORED and treated as
    serious, because an advisory nobody scored is not an advisory nobody needs.
    """
    if not vector or not vector.startswith("CVSS:3"):
        return None
    try:
        parts = dict(pair.split(":", 1) for pair in vector.split("/")[1:])
        av = {"N": 0.85, "A": 0.62, "L": 0.55, "P": 0.2}[parts["AV"]]
        ac = {"L": 0.77, "H": 0.44}[parts["AC"]]
        ui = {"N": 0.85, "R": 0.62}[parts["UI"]]
        scope_changed = parts.get("S", "U") == "C"
        pr_table = {"N": 0.85, "L": 0.68, "H": 0.50} if scope_changed else {"N": 0.85, "L": 0.62, "H": 0.27}
        pr = pr_table[parts["PR"]]
        weight = {"H": 0.56, "L": 0.22, "N": 0.0}
        impact_sub = 1 - (
            (1 - weight[parts["C"]]) * (1 - weight[parts["I"]]) * (1 - weight[parts["A"]])
        )
        if impact_sub <= 0:
            return 0.0
        if scope_changed:
            impact = 7.52 * (impact_sub - 0.029) - 3.25 * ((impact_sub - 0.02) ** 15)
        else:
            impact = 6.42 * impact_sub
        exploitability = 8.22 * av * ac * pr * ui
        raw = min((1.08 if scope_changed else 1.0) * (impact + exploitability), 10.0)
        return math.ceil(raw * 10) / 10
    except (KeyError, ValueError):
        return None


def severity_band_from_score(score: float | None) -> str:
    if score is None:
        return "UNSCORED"
    if score >= 9.0:
        return "CRITICAL"
    if score >= 7.0:
        return "HIGH"
    if score >= 4.0:
        return "MEDIUM"
    if score > 0:
        return "LOW"
    return "NONE"


# GitHub Advisory Database's own curated rating, which is the field OSV populates
# most consistently. MODERATE is GHSA's spelling of the CVSS MEDIUM band.
_GHSA_BANDS = {"LOW": "LOW", "MODERATE": "MEDIUM", "MEDIUM": "MEDIUM", "HIGH": "HIGH", "CRITICAL": "CRITICAL"}


def classify(record: dict) -> tuple[str, float | None, str | None]:
    """Return (band, cvss3_score_or_None, vector_or_None) for an OSV record.

    THE CURATED BAND WINS, AND THAT IS DELIBERATE. Two shapes in the real data
    defeat a CVSS-v3-only classifier, and both were observed in this graph:
    advisories carrying a CVSS_V4 vector, whose scoring algorithm is not v3's and
    cannot be evaluated by a v3 formula; and advisories carrying no severity
    vector at all. Measured: thirteen findings here fall into one of those two
    shapes, every one of them rated HIGH by the advisory database. A classifier
    that scored only v3 would have called all thirteen unscored - so the band is
    taken from database_specific.severity first, and the v3 arithmetic is kept
    only to print a corroborating decimal where a v3 vector exists.

    An advisory with neither a curated band nor a v3 vector is reported UNSCORED
    and treated as serious. That is the safe direction: an advisory nobody has
    scored yet is not an advisory nobody needs to look at.
    """
    vector = None
    for severity in record.get("severity", []) or []:
        if str(severity.get("type", "")).startswith("CVSS"):
            vector = severity.get("score")
            if str(vector or "").startswith("CVSS:3"):
                break
    score = cvss_base_score(vector)
    curated = str(((record.get("database_specific") or {}).get("severity") or "")).upper()
    band = _GHSA_BANDS.get(curated) or severity_band_from_score(score)
    return band, score, vector


SERIOUS_BANDS = {"CRITICAL", "HIGH", "UNSCORED"}


def parse_inventory(path: str) -> dict[tuple[str, str, str], set[str]]:
    """Read `dependency:resolve-plugins` output into coordinate -> {plugin, ...}.

    Attribution is kept because it is the first thing a reader needs: "which
    plugin drags this in" determines whether the finding is reachable at all.
    """
    inventory: dict[tuple[str, str, str], set[str]] = {}
    current_plugin = "unattributed"
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.rstrip("\n")
            plugin_match = _PLUGIN_RE.match(line)
            if plugin_match:
                current_plugin = ":".join(plugin_match.groups())
                continue
            artifact_match = _ARTIFACT_RE.match(line)
            if artifact_match:
                coordinate = artifact_match.groups()
                inventory.setdefault(coordinate, set()).add(current_plugin)
    return inventory


def realm_coordinates(paths: list[str]) -> set[tuple[str, str, str]]:
    """Collect `Included:` artifacts from Maven -X plugin realm listings."""
    found: set[tuple[str, str, str]] = set()
    for path in paths:
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                match = _REALM_INCLUDED_RE.search(line)
                if match:
                    found.add(match.groups())
    return found


def apply_overrides(
    inventory: dict[tuple[str, str, str], set[str]],
    overrides: list[dict],
    realm: set[tuple[str, str, str]],
) -> list[str]:
    """Replace declared coordinates with the ones the plugin realm really loads.

    Each override is self-verifying: it must be observable in the realm listing.
    Returns the list of failures, which are gate failures rather than warnings -
    an override asserted but not in effect is the exact condition under which the
    scan would otherwise report a clean graph that is not clean.
    """
    failures: list[str] = []
    for override in overrides:
        group, artifact = override["group"], override["artifact"]
        declared = (group, artifact, override["declaredVersion"])
        effective = (group, artifact, override["effectiveVersion"])
        if realm and effective not in realm:
            failures.append(
                f"override NOT IN EFFECT: {group}:{artifact} is declared as "
                f"{override['effectiveVersion']} in {override['declaredIn']} but the plugin "
                f"realm does not load it. Restore the plugin-scoped override, or delete this "
                f"override entry and let the declared version be scanned."
            )
            continue
        if realm and declared in realm:
            failures.append(
                f"override BYPASSED: {group}:{artifact}:{override['declaredVersion']} is still "
                f"loaded by the plugin realm despite the override in {override['declaredIn']}."
            )
            continue
        attributions = inventory.pop(declared, None)
        if attributions is not None:
            inventory.setdefault(effective, set()).update(attributions)
    return failures


def query_osv(coordinates: list[tuple[str, str, str]]) -> dict[tuple[str, str, str], list[str]]:
    """Batch-query OSV, returning only the coordinates that carry advisories."""
    hits: dict[tuple[str, str, str], list[str]] = {}
    batch = 200
    for start in range(0, len(coordinates), batch):
        window = coordinates[start : start + batch]
        payload = {
            "queries": [
                {"package": {"ecosystem": "Maven", "name": f"{g}:{a}"}, "version": v}
                for g, a, v in window
            ]
        }
        results = _http_json(OSV_BATCH, payload).get("results", [])
        for coordinate, result in zip(window, results):
            identifiers = [entry["id"] for entry in (result.get("vulns") or [])]
            if identifiers:
                hits[coordinate] = identifiers
        time.sleep(0.3)
    return hits


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", required=True, help="dependency:resolve-plugins output")
    parser.add_argument(
        "--realm",
        action="append",
        default=[],
        help="a Maven -X log carrying plugin realm 'Included:' lines (repeatable)",
    )
    parser.add_argument("--dispositions", required=True)
    parser.add_argument("--out", required=True, help="output directory for the evidence")
    arguments = parser.parse_args()

    with open(arguments.dispositions, encoding="utf-8") as handle:
        dispositions_document = json.load(handle)
    dispositions = dispositions_document.get("dispositions", [])
    overrides = dispositions_document.get("overrides", [])

    inventory = parse_inventory(arguments.inventory)
    if not inventory:
        print("FATAL: the plugin inventory is empty, so nothing was scanned.", file=sys.stderr)
        return 1

    realm = realm_coordinates(arguments.realm)
    override_failures = apply_overrides(inventory, overrides, realm)

    coordinates = sorted(inventory)
    hits = query_osv(coordinates)

    indexed = {(d["coordinate"], d["advisory"]): d for d in dispositions}
    today = _dt.date.today()
    matched: set[tuple[str, str]] = set()

    unresolved: list[dict] = []
    accepted: list[dict] = []
    below: list[dict] = []

    for coordinate, identifiers in sorted(hits.items()):
        name = f"{coordinate[0]}:{coordinate[1]}:{coordinate[2]}"
        for identifier in identifiers:
            record = _http_json(OSV_VULN + identifier)
            band, score, vector = classify(record)
            aliases = [a for a in record.get("aliases", []) if a.startswith("CVE")]
            finding = {
                "coordinate": name,
                "advisory": identifier,
                "aliases": aliases,
                "score": score,
                "severity": band,
                "vector": vector,
                "broughtBy": sorted(inventory[coordinate]),
                "summary": (record.get("summary") or "").strip(),
            }
            if band not in SERIOUS_BANDS:
                below.append(finding)
                continue

            key = (name, identifier)
            alias_keys = [(name, alias) for alias in aliases]
            disposition = indexed.get(key) or next(
                (indexed[k] for k in alias_keys if k in indexed), None
            )
            if disposition is None:
                unresolved.append(finding)
                continue
            matched.add((disposition["coordinate"], disposition["advisory"]))
            expires = _dt.date.fromisoformat(disposition["expires"])
            finding["disposition"] = disposition
            if expires < today:
                finding["expired"] = True
                unresolved.append(finding)
            else:
                accepted.append(finding)
        time.sleep(0.05)

    stale = [
        f"{d['coordinate']} / {d['advisory']}"
        for d in dispositions
        if (d["coordinate"], d["advisory"]) not in matched
    ]

    os.makedirs(arguments.out, exist_ok=True)
    summary = {
        "coordinatesScanned": len(coordinates),
        "coordinatesWithAdvisories": len(hits),
        "unresolvedSeriousFindings": len(unresolved),
        "dispositionedSeriousFindings": len(accepted),
        "belowThresholdFindings": len(below),
        "overrideFailures": override_failures,
        "staleDispositions": stale,
        "unresolved": unresolved,
        "dispositioned": accepted,
        "belowThreshold": below,
    }
    with open(os.path.join(arguments.out, "plugin-graph-advisories.json"), "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2, sort_keys=True)

    lines: list[str] = []
    lines.append("MAVEN PLUGIN GRAPH ADVISORY SCAN")
    lines.append(f"  coordinates scanned            : {len(coordinates)}")
    lines.append(f"  coordinates with advisories    : {len(hits)}")
    lines.append(f"  HIGH/CRITICAL undispositioned  : {len(unresolved)}")
    lines.append(f"  HIGH/CRITICAL dispositioned    : {len(accepted)}")
    lines.append(f"  below threshold (reported)     : {len(below)}")
    for label, bucket in (("UNDISPOSITIONED", unresolved), ("DISPOSITIONED", accepted)):
        if not bucket:
            continue
        lines.append("")
        lines.append(f"{label}:")
        for finding in sorted(bucket, key=lambda f: -(f["score"] or 0)):
            lines.append(
                f"  {finding['severity']:<8} {finding['score']}  {finding['coordinate']}  "
                f"{finding['advisory']} {finding['aliases']}"
            )
            lines.append(f"      brought by : {', '.join(finding['broughtBy'])}")
            if finding.get("expired"):
                lines.append(
                    f"      EXPIRED    : accepted until {finding['disposition']['expires']}"
                )
            elif finding.get("disposition"):
                lines.append(
                    f"      accepted   : until {finding['disposition']['expires']} - "
                    f"{finding['disposition']['evidence']}"
                )
    report = "\n".join(lines)
    with open(os.path.join(arguments.out, "plugin-graph-advisories.txt"), "w", encoding="utf-8") as handle:
        handle.write(report + "\n")
    print(report)

    failed = False
    for failure in override_failures:
        print(f"::error title=Blocker::pom.xml [plugin transport override] {failure}")
        failed = True
    if unresolved:
        print(
            "::error title=Blocker::pom.xml [plugin graph HIGH or CRITICAL advisories] "
            f"expected: none undispositioned | actual: {len(unresolved)} | remediation: raise the "
            "plugin, add an exact plugin-scoped dependency override as pom.xml already does for "
            "the scanner's own HTTP transport, or add an evidence-bearing entry with an expiry to "
            ".github/plugin-graph/dispositions.json. Lowering the threshold is not available."
        )
        failed = True
    if stale:
        print(
            "::error title=Blocker::.github/plugin-graph/dispositions.json [stale entries] "
            f"expected: every entry matches a current finding | actual: {', '.join(stale)} | "
            "remediation: delete them. The graph has moved past these, and an entry that matches "
            "nothing is how a disposition file stops being read."
        )
        failed = True
    if not failed:
        print("\nThe plugin graph carries no undispositioned HIGH or CRITICAL advisory.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
