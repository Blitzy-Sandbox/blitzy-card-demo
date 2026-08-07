#!/bin/sh
# ******************************************************************
# Program     : derive-gate1-oracle.sh
# Application : CardDemo
# Type        : Gate 1 parity-oracle derivation harness (POSIX shell)
# Function    : Re-derives every ".expected" file in the parent directory by
#               COMPILING AND EXECUTING the frozen app/cbl/CBTRN02C.cbl with
#               GnuCOBOL against the frozen app/data/ASCII fixtures. It reads
#               the corpus and writes only inside its own output directory, so
#               it can never modify app/. Run it from this directory:
#                   sh derive-gate1-oracle.sh <repository-root> <output-dir>
#               Requires GnuCOBOL 3.2 or later with an indexed-file handler
#               (Debian/Ubuntu: apt-get install -y gnucobol3).
# Source      : app/cbl/CBTRN02C.cbl is compiled UNMODIFIED. The load and dump
#               utilities beside this script stand in for the IDCAMS steps of
#               app/jcl/ACCTFILE.jcl, app/jcl/XREFFILE.jcl and
#               app/jcl/TCATBALF.jcl, which is the only scaffolding the run
#               needs. @ 7756d89
# ******************************************************************
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
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.
# ******************************************************************
set -e

R="$1"
OUT="$2"
if [ -z "$R" ] || [ -z "$OUT" ]; then
    echo "usage: sh derive-gate1-oracle.sh <repository-root> <output-dir>" >&2
    exit 2
fi
HERE="$(cd "$(dirname "$0")" && pwd)"
BIN="$OUT/bin"
DATA="$OUT/data"
rm -rf "$OUT"
mkdir -p "$BIN" "$DATA"

# -fsign=EBCDIC selects the trailing-sign overpunch representation the fixtures
# carry ('{' is +0, 'A'-'I' are +1..+9, '}' is -0, 'J'-'R' are -1..-9), which is
# the IBM zoned-decimal convention. Without it GnuCOBOL would read those bytes
# under its own ASCII sign convention and every signed amount would be wrong.
# -std=ibm selects the source dialect; -I selects the frozen copybook library.
COBFLAGS="-x -fsign=EBCDIC -std=ibm"

echo "compiling the frozen program and the load/dump utilities"
cobc $COBFLAGS -I "$R/app/cpy" -o "$BIN/CBTRN02C" "$R/app/cbl/CBTRN02C.cbl"
for p in LOADACCT LOADXREF LOADTCAT DUMPACCT DUMPTCAT DUMPTRAN; do
    cobc $COBFLAGS -o "$BIN/$p" "$HERE/$p.cbl"
done
export COB_LIBRARY_PATH="$BIN"

# DALYTRAN is ORGANIZATION SEQUENTIAL at app/cbl/CBTRN02C.cbl:L29-L32, so its
# records are 350 fixed bytes with NO separator. The ASCII fixture is the same
# 350-byte records with one LF appended to each; stripping the LFs is what turns
# a line-oriented fixture into the fixed-block dataset the program declares.
echo "staging DALYTRAN as fixed 350-byte blocks"
tr -d '\n' < "$R/app/data/ASCII/dailytran.txt" > "$DATA/DALYTRAN"

echo "loading the three keyed input datasets"
DD_FLATIN="$R/app/data/ASCII/acctdata.txt" DD_ACCTFILE="$DATA/ACCTFILE" "$BIN/LOADACCT"
DD_FLATIN="$R/app/data/ASCII/cardxref.txt" DD_XREFFILE="$DATA/XREFFILE" "$BIN/LOADXREF"
DD_FLATIN="$R/app/data/ASCII/tcatbal.txt"  DD_TCATBALF="$DATA/TCATBALF" "$BIN/LOADTCAT"

echo "executing app/cbl/CBTRN02C.cbl"
set +e
DD_DALYTRAN="$DATA/DALYTRAN" DD_XREFFILE="$DATA/XREFFILE" DD_ACCTFILE="$DATA/ACCTFILE" \
DD_TCATBALF="$DATA/TCATBALF" DD_TRANFILE="$DATA/TRANFILE" DD_DALYREJS="$DATA/DALYREJS" \
    "$BIN/CBTRN02C" > "$DATA/CBTRN02C.sysout" 2>&1
RC=$?
set -e
echo "RETURN-CODE=$RC" >> "$DATA/CBTRN02C.sysout"

echo "unloading the mutated datasets in key order"
DD_ACCTFILE="$DATA/ACCTFILE" DD_FLATOUT="$DATA/ACCTDATA.after" "$BIN/DUMPACCT"
DD_TCATBALF="$DATA/TCATBALF" DD_FLATOUT="$DATA/TCATBALF.after" "$BIN/DUMPTCAT"
DD_TRANFILE="$DATA/TRANFILE" DD_FLATOUT="$DATA/TRANSACT.after" "$BIN/DUMPTRAN"

echo "done. raw datasets are in $DATA"
echo
echo "THIS SCRIPT DOES NOT WRITE THE COMMITTED ORACLE. It produces the raw"
echo "datasets above; rendering them into the .expected files and re-taking the"
echo "digests in PROVENANCE.properties is a deliberate manual step, so that a"
echo "regeneration can be diffed against the committed oracle before it replaces"
echo "it. A comparison failure is a parity defect until proven otherwise."
echo
echo "the committed .expected files are these datasets rendered one record per"
echo "LF-delimited line, at these exact widths:"
echo "  DALYREJS.expected  430 chars x  38 records  (350 image + 80 trailer)"
echo "  TRANSACT.expected  304 chars x 262 records  (the REPRODUCIBLE prefix of"
echo "                     the 350-byte record: 305-330 is TRAN-PROC-TS, which is"
echo "                     generated per run, and 331-350 is the never-assigned"
echo "                     FILLER. Both spans are excluded by name in"
echo "                     PROVENANCE.properties, so 304 is not a truncation but"
echo "                     the boundary of what can be compared at all.)"
echo "  ACCTDATA.expected  300 chars x  50 records"
echo "  TCATBALF.expected   50 chars x 100 records"
