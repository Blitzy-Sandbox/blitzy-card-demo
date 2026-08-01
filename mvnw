#!/bin/sh
# ******************************************************************
# Component   : mvnw
# Application : CardDemo
# Type        : Maven Wrapper launcher (Unix, POSIX shell)
# Function    : Resolves, downloads, SHA-256 verifies, caches and then
#               executes the Apache Maven distribution pinned by this
#               repository, so that building CardDemo needs no Maven
#               installed on the host.
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
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# either express or implied. See the License for the specific
# language governing permissions and limitations under the License
# ******************************************************************
#
# PROVENANCE. Derived from the Apache Maven Wrapper 3.3.4 only-script
# launcher (Apache License 2.0, The Apache Software Foundation). The
# distribution cache layout and the distribution-URL hash are
# reproduced exactly, so an existing ~/.m2/wrapper cache and the
# sibling mvnw.cmd remain interoperable with this file. Configuration
# is read only from .mvn/wrapper/maven-wrapper.properties; no Maven
# version appears anywhere in this script.
#
# It supersedes the frozen z/OS build tooling, none of which is
# ported: the compile procedures samples/proc/BUILDBAT.prc,
# BUILDBMS.prc and BUILDONL.prc and the templates samples/jcl/
# BATCMP.jcl, BMSCMP.jcl and CICCMP.jcl. Authored per the Agent Action
# Plan in docs/technical-specifications.md sections 0.3.1.4, 0.3.1.8,
# 0.4.1.1 and 0.5.1.1, at traceability anchor commit 7756d89.
#
# WHAT IT DOES, in order.
#   1. Finds .mvn/wrapper/maven-wrapper.properties, following symlinks
#      and tolerating directory names that contain spaces.
#   2. Reads distributionUrl and distributionSha256Sum from it.
#   3. Rejects anything that is not an HTTPS URL for a released
#      Apache Maven binary zip.
#   4. Derives the cache directory from the URL and runs Maven straight
#      away when that directory already holds a distribution.
#   5. Otherwise downloads over HTTPS into a private staging directory,
#      verifies the SHA-256, expands the archive, and publishes it with
#      a single same-filesystem rename.
#   6. Replaces itself with Maven through exec, so Maven's exit status,
#      signal disposition, stdin, stdout and stderr are unchanged and
#      every argument is forwarded verbatim.
#
# BUILD AND TEST. No version is repeated here on purpose: the pinned
# Maven is whatever .mvn/wrapper/maven-wrapper.properties declares, and
# duplicating it in prose would let the two drift apart.
#   ./mvnw --version               report the pinned Maven and the JDK
#   ./mvnw -B clean compile        compile the Java sources
#   ./mvnw -B test                 run the unit test tier
#   ./mvnw -B clean verify         full build with coverage and scans
#   MVNW_VERBOSE=true ./mvnw ...   log wrapper decisions to stderr
#   MVNW_VERBOSE=debug ./mvnw ...  shell trace, credentials suppressed
#   sh -n mvnw && shellcheck -s sh mvnw    static checks on this file
#
# KEYS AND DEFAULTS. Read by this script:
#   JAVA_HOME            JDK used to locate java, and jar when unzip is
#                        absent. Default: java from PATH.
#   MAVEN_USER_HOME      Root of the wrapper cache. Default: $HOME/.m2.
#                        The cache path is
#                        <MAVEN_USER_HOME>/wrapper/dists/<dist>/<hash>,
#                        so it is deterministic for a given URL.
#   MAVEN_PROJECTBASEDIR Directory that holds .mvn/wrapper. Default:
#                        discovered by walking up from this script and
#                        then from the current directory.
#   MVNW_REPOURL         HTTPS base of a private Maven mirror. The
#                        cache key follows the mirror URL, so mirrored
#                        and upstream downloads never share a slot.
#   MVNW_USERNAME        Mirror credentials. Both must be set, they are
#   MVNW_PASSWORD        never placed on a command line, and they are
#                        removed from the environment before Maven
#                        starts. No default and no committed value.
#   MVNW_VERBOSE         true for wrapper logging, debug for a shell
#                        trace. Default: quiet.
# Passed through untouched, because Apache Maven's own bin/mvn already
# implements them and duplicating that here would let the two drift:
#   MAVEN_OPTS, MAVEN_ARGS, MAVEN_BASEDIR.
#
# EXIT STATUS. On success this process is replaced by Maven, so the
# caller observes Maven's own status and signal death. A wrapper
# failure exits 1 after writing a message to stderr prefixed
# "mvnw: ERROR:"; that prefix, not a special code, identifies a
# wrapper failure. Nothing is ever written to stdout by this script.
#
# SECURITY MODEL. HTTPS only, with certificate verification left at the
# downloader's secure default and never disabled. Protocol downgrades,
# including on redirect, are refused. The SHA-256 is mandatory and is
# checked before the archive is expanded, so a mismatch cannot reach
# execution. There is no eval, no piping of downloaded bytes into a
# shell, and no generated code. Credentials travel through a mode-600
# file, never through argv, and the shell trace is suspended around
# every statement that expands them, so MVNW_VERBOSE=debug cannot print
# a password. This file contains no credential, token or key.
#
# DELIBERATE DIVERGENCES from the upstream launcher, each recorded so a
# side-by-side reader can see they are intentional:
#   * The checksum is mandatory. Upstream skips verification silently
#     when distributionSha256Sum is absent or blank.
#   * No .tar.gz retarget when unzip is missing. Upstream rewrites the
#     URL, which then fails the zip checksum and reports a possibly
#     compromised distribution when the real fault is a missing tool.
#     Instead the JDK jar tool is used, then a precise error.
#   * No generated-and-compiled Java downloader. curl, then wget, then
#     an explicit error.
#   * No maven-mvnd and no mvnwDebug name variant: this repository pins
#     apache-maven, so those branches would be unreachable code.
#   * Staging happens inside the cache directory, making the install a
#     rename rather than a cross-filesystem copy.
#   * MAVEN_HOME is not assigned, so an exported value is not mutated.
#
# TROUBLESHOOTING.
#   checksum mismatch   Maven is not executed. Expected and actual
#                       digests are both printed. When the pinned
#                       version changes, update distributionUrl and
#                       distributionSha256Sum together.
#   no downloader       install curl or wget.
#   no digest tool      install coreutils, perl-shasum or openssl.
#   cannot expand       install unzip, or make $JAVA_HOME/bin/jar
#                       reachable.
#   wrong JDK           a warning names the JDK found; the enforcer
#                       rule requireJavaVersion [25,) in pom.xml fails
#                       the build. Point JAVA_HOME at a JDK 25.
#   permission denied   run chmod +x mvnw.
#   slow first build    a cold cache fetches about 9 MB once.
# ******************************************************************

# Fail on error, on an unset variable, and with pathname expansion off
# so no value can be turned into a file list. CDPATH is cleared so cd
# cannot be redirected by the environment.
set -euf
CDPATH=''

# MVNW_VERBOSE=debug traces this script. mvnwTrace records that so the
# trace can be suspended wherever a credential is expanded.
mvnwTrace=false
if [ "${MVNW_VERBOSE-}" = debug ]; then
  mvnwTrace=true
  set -x
fi

# ------------------------------------------------------------------
# Diagnostics. Everything goes to stderr so stdout belongs to Maven
# alone, and every line is prefixed so a wrapper failure is never
# mistaken for build output.
# POSIX sh has no local variables, so helper scratch variables carry a
# function-specific underscore prefix to keep them from colliding.
# ------------------------------------------------------------------

# Join the arguments into one line with a single separating space, so a
# long diagnostic can be wrapped in the source without ever being
# wrapped in the output: a one-line message survives log aggregation.
# The join is written out rather than delegated to "$*" so an inherited
# hostile IFS cannot change the separator, and it assigns instead of
# echoing so no message costs a subshell.
_msg_text=''
join_message() {
  _msg_text="$1"
  shift
  while [ "$#" -gt 0 ]; do
    _msg_text="$_msg_text $1"
    shift
  done
}

die() {
  join_message "$@"
  printf 'mvnw: ERROR: %s\n' "$_msg_text" >&2
  exit 1
}

warn() {
  join_message "$@"
  printf 'mvnw: WARNING: %s\n' "$_msg_text" >&2
}

verbose() {
  if [ "${MVNW_VERBOSE-}" = true ]; then
    printf 'mvnw: %s\n' "$1" >&2
  fi
}

# Suspend and resume the shell trace around any statement that expands a
# credential. Without this, MVNW_VERBOSE=debug would print the password
# to stderr, because "set -x" echoes assignments and test arguments
# after expansion. POSIX sh does not scope shell options per function,
# so a call to trace_off changes the option for the whole script until
# trace_on restores it. Both are no-ops when the trace is not enabled.
trace_off() {
  if [ "$mvnwTrace" = true ]; then
    set +x
  fi
  return 0
}

trace_on() {
  if [ "$mvnwTrace" = true ]; then
    set -x
  fi
  return 0
}

# MWRAPPER-139: strip spaces, tabs, carriage returns and linefeeds that
# some environments leave inside properties values.
trim() {
  printf '%s' "$1" | tr -d '[:space:]'
}

# Reproduce java.lang.String::hashCode as lowercase hex. This value is
# the cache directory name, so the algorithm is kept byte-compatible
# with the upstream launcher: changing it would orphan every
# distribution already downloaded by mvnw or by another tool. The
# per-character loop costs about 24 ms, which is negligible beside JVM
# start-up, and it keeps the hot path free of external dependencies.
hash_string() {
  _hs_str="$1"
  _hs_hash=0
  while [ -n "$_hs_str" ]; do
    _hs_char="${_hs_str%"${_hs_str#?}"}"
    _hs_hash=$(((_hs_hash * 31 + $(LC_CTYPE=C printf '%d' "'$_hs_char")) % 4294967296))
    _hs_str="${_hs_str#?}"
  done
  printf '%x\n' "$_hs_hash"
}

# Transport policy. http://, file:// and ftp:// are refused outright
# rather than silently accepted: the build toolchain is executable
# content and must arrive over an authenticated channel.
require_https() {
  case "$1" in
  https://?*) return 0 ;;
  esac
  die "$2 must be an https:// URL but is '$1';" \
    "fetching the build toolchain over an unauthenticated transport is not permitted"
}

# Follow a symlink chain without readlink -f, which is not portable.
# The chain is bounded so a cycle fails fast instead of hanging.
resolve_symlinks() {
  _rs_path="$1"
  if [ -h "$_rs_path" ] && ! command -v readlink >/dev/null 2>&1; then
    die "'$_rs_path' is a symlink but readlink is unavailable; invoke the real script or set MAVEN_PROJECTBASEDIR"
  fi
  _rs_depth=0
  while [ -h "$_rs_path" ]; do
    _rs_depth=$((_rs_depth + 1))
    if [ "$_rs_depth" -gt 40 ]; then
      die "symlink chain from '$1' exceeds 40 links or forms a cycle;" \
        "invoke the real script or set MAVEN_PROJECTBASEDIR"
    fi
    _rs_target="$(readlink "$_rs_path")" || die "cannot read symlink '$_rs_path'"
    case "$_rs_target" in
    /*) _rs_path="$_rs_target" ;;
    *) _rs_path="$(dirname "$_rs_path")/$_rs_target" ;;
    esac
  done
  printf '%s\n' "$_rs_path"
}

# Walk from $1 towards / and print the first directory that holds the
# wrapper configuration. Returns 1 when the root is reached without a
# match, which the caller turns into an actionable error.
find_wrapper_basedir() {
  _fw_dir="$(cd -- "$1" 2>/dev/null && pwd)" || return 1
  while :; do
    if [ -f "$_fw_dir/$wrapperPropertiesRelPath" ]; then
      printf '%s\n' "$_fw_dir"
      return 0
    fi
    if [ "$_fw_dir" = / ]; then
      return 1
    fi
    _fw_dir="$(dirname "$_fw_dir")"
  done
}

# Locate the java launcher. Maven's own bin/mvn would also do this, but
# failing here turns a late and noisier error into an immediate one.
resolve_java() {
  if [ -n "${JAVA_HOME-}" ]; then
    if [ -x "$JAVA_HOME/jre/sh/java" ]; then
      # IBM Java on AIX keeps its launchers in an unusual location.
      javaCommand="$JAVA_HOME/jre/sh/java"
    elif [ -x "$JAVA_HOME/bin/java" ]; then
      javaCommand="$JAVA_HOME/bin/java"
    else
      die "JAVA_HOME is '$JAVA_HOME' but neither \$JAVA_HOME/bin/java nor" \
        "\$JAVA_HOME/jre/sh/java is executable;" \
        "point JAVA_HOME at a JDK 25 installation"
    fi
    return 0
  fi
  # set +e keeps the subshell from aborting, and unsetting plus quoting
  # command defeats an inherited function or alias of the same name.
  javaCommand="$(
    set +e
    'unset' -f command 2>/dev/null
    'command' -v java 2>/dev/null
  )" || javaCommand=''
  if [ -z "$javaCommand" ] || [ ! -x "$javaCommand" ]; then
    die "no Java found: JAVA_HOME is unset and java is not on PATH; install JDK 25 and export JAVA_HOME"
  fi
  return 0
}

# Early JDK level diagnostic. The build's real gate is the enforcer
# rule requireJavaVersion [25,) in pom.xml, whose message reads "Build
# with JDK 25 or newer: a lower JDK cannot compile release 25 sources."
# Reporting it here turns that late failure into an immediate hint. The
# feature version is read from the JDK's own release descriptor, so the
# check costs a file read instead of a JVM start-up. No lower language
# level is ever selected as a fallback.
report_java_level() {
  _rj_image="$(dirname "$(dirname "$(resolve_symlinks "$javaCommand")")")"
  _rj_release="$_rj_image/release"
  if [ ! -f "$_rj_release" ]; then
    verbose "no release descriptor under '$_rj_image'; leaving the JDK level to the enforcer rule"
    return 0
  fi
  _rj_value="$(grep '^JAVA_VERSION=' "$_rj_release" | head -n 1)"
  _rj_value="${_rj_value#JAVA_VERSION=}"
  _rj_value="${_rj_value#\"}"
  _rj_value="${_rj_value%\"}"
  _rj_major="${_rj_value%%.*}"
  if [ "$_rj_major" = 1 ]; then
    # Legacy scheme: 1.8.0_402 is Java 8.
    _rj_rest="${_rj_value#1.}"
    _rj_major="${_rj_rest%%.*}"
  fi
  case "$_rj_major" in
  '' | *[!0-9]*)
    verbose "cannot read a feature version from '$_rj_release'; leaving the JDK level to the enforcer rule"
    return 0
    ;;
  esac
  verbose "Java $_rj_major at $javaCommand"
  if [ "$_rj_major" -lt 25 ]; then
    warn "Java $_rj_major found at '$javaCommand', but this build compiles with release 25."
    warn "The enforcer rule requireJavaVersion [25,) in pom.xml will fail the build;" \
      "point JAVA_HOME at a JDK 25 or newer installation."
  fi
  return 0
}

# Locate the JDK jar tool, the fallback archive expander. Preference
# order mirrors resolve_java: JAVA_HOME, then the sibling of the java
# launcher, then PATH. Returns 1 when none is usable.
find_jar_command() {
  if [ -n "${JAVA_HOME-}" ] && [ -x "$JAVA_HOME/bin/jar" ]; then
    printf '%s\n' "$JAVA_HOME/bin/jar"
    return 0
  fi
  _fj_sibling="${javaCommand%/*}/jar"
  if [ -x "$_fj_sibling" ]; then
    printf '%s\n' "$_fj_sibling"
    return 0
  fi
  _fj_path="$(
    set +e
    'unset' -f command 2>/dev/null
    'command' -v jar 2>/dev/null
  )" || _fj_path=''
  if [ -n "$_fj_path" ] && [ -x "$_fj_path" ]; then
    printf '%s\n' "$_fj_path"
    return 0
  fi
  return 1
}

# Expand $1 into the directory $2. The pinned URL is always a zip and
# is never retargeted at a .tar.gz variant, because that would make the
# pinned SHA-256 fail and blame a compromised distribution for what is
# only a missing tool.
extract_zip() {
  _ez_archive="$1"
  _ez_into="$2"
  if command -v unzip >/dev/null 2>&1; then
    verbose "expanding with unzip"
    unzip ${unzipQuiet:+"$unzipQuiet"} "$_ez_archive" -d "$_ez_into" ||
      die "unzip could not expand '$_ez_archive'"
    return 0
  fi
  _ez_jar="$(find_jar_command)" || _ez_jar=''
  if [ -n "$_ez_jar" ]; then
    verbose "unzip is unavailable; expanding with the JDK jar tool at $_ez_jar"
    # jar extracts relative to the working directory only, hence the
    # subshell. It also does not restore POSIX permission bits, which
    # is why the launcher modes are set explicitly after extraction.
    (cd -- "$_ez_into" && "$_ez_jar" xf "$_ez_archive") ||
      die "the JDK jar tool could not expand '$_ez_archive'"
    return 0
  fi
  die "cannot expand '$_ez_archive': neither unzip nor the JDK jar tool is available;" \
    "install unzip or make \$JAVA_HOME/bin/jar reachable"
}

# Verify that file $2 hashes to the expected lowercase hex digest $1.
# The digest is computed and compared here, rather than delegated to
# "sha256sum -c", so that both values can be reported on failure and so
# that all three backends behave identically.
verify_sha256() {
  _vs_expected="$1"
  _vs_file="$2"
  if command -v sha256sum >/dev/null 2>&1; then
    _vs_actual="$(sha256sum "$_vs_file")" || die "sha256sum failed on '$_vs_file'"
  elif command -v shasum >/dev/null 2>&1; then
    _vs_actual="$(shasum -a 256 "$_vs_file")" || die "shasum failed on '$_vs_file'"
  elif command -v openssl >/dev/null 2>&1; then
    _vs_actual="$(openssl dgst -sha256 -r "$_vs_file")" || die "openssl failed on '$_vs_file'"
  else
    die "cannot verify the distribution:" \
      "none of sha256sum, shasum or openssl is available; install one," \
      "because verification is mandatory and is never skipped"
  fi
  # All three backends print "<digest><separator><path>"; keep the
  # digest and fold it to lowercase, since some openssl builds emit
  # uppercase while the pinned value is lowercase.
  _vs_actual="${_vs_actual%% *}"
  _vs_actual="$(printf '%s' "$_vs_actual" | tr 'ABCDEF' 'abcdef')"
  if [ "$_vs_actual" = "$_vs_expected" ]; then
    verbose "SHA-256 verified: $_vs_actual"
    return 0
  fi
  printf 'mvnw: ERROR: SHA-256 mismatch for the Maven distribution. Maven was NOT executed.\n' >&2
  printf 'mvnw: ERROR:   url      %s\n' "$distributionUrl" >&2
  printf 'mvnw: ERROR:   expected %s\n' "$_vs_expected" >&2
  printf 'mvnw: ERROR:   actual   %s\n' "$_vs_actual" >&2
  # die appends the same trailing newline and exits 1, so the closing
  # line of the report and the failure status stay together.
  die "The download is corrupt, was tampered with, or the pinned version moved." \
    "If distributionUrl was changed on purpose," \
    "update distributionSha256Sum in $wrapperPropertiesFile to match."
}

# Write mirror credentials to a private file. printf is a shell builtin
# and the trace is suspended, so the password reaches neither the
# process list nor the shell trace.
write_netrc() {
  trace_off
  netrcFile="$stagingDir/netrc"
  (umask 077 && : >"$netrcFile") || die "cannot create the credential file '$netrcFile'"
  _wn_host="${distributionUrl#https://}"
  _wn_host="${_wn_host%%/*}"
  _wn_host="${_wn_host%%:*}"
  printf 'machine %s login %s password %s\n' \
    "$_wn_host" "$mvnwUsername" "$mvnwPassword" >"$netrcFile" ||
    die "cannot write the credential file '$netrcFile'"
  trace_on
  verbose "credentials staged for host $_wn_host"
  return 0
}

# --fail turns an HTTP error status into a non-zero exit instead of a
# saved error page, --location follows redirects, and the two --proto
# options refuse any URL or redirect that is not HTTPS. Certificate
# verification stays at curl's secure default and is never disabled.
download_with_curl() {
  curl ${curlQuiet:+"$curlQuiet"} --fail --show-error --location \
    --proto '=https' --proto-redir '=https' \
    "$@" --output "$downloadTarget" "$distributionUrl"
}

# --https-only refuses a redirect that leaves TLS. Certificate checking
# stays at wget's secure default.
download_with_wget() {
  wget ${wgetQuiet:+"$wgetQuiet"} --https-only \
    --output-document "$downloadTarget" "$distributionUrl"
}

# Remove the staging directory. Safe to call more than once, which the
# signal handlers rely on. A failure to clean up is reported but does
# not fail a build that has otherwise succeeded.
cleanup_staging() {
  if [ -n "${stagingDir-}" ] && [ -d "$stagingDir" ]; then
    rm -rf -- "$stagingDir" ||
      warn "could not remove the staging directory '$stagingDir'; delete it manually"
  fi
  return 0
}

# Hand over to Maven. exec replaces this shell, so Maven owns the exit
# status and the signal disposition, and "$@" is forwarded unaltered
# including empty arguments and arguments containing whitespace.
exec_maven() {
  # exec on a missing or non-executable file terminates the shell
  # without a usable diagnostic, so this cannot be deferred to a
  # trailing "|| die".
  if [ ! -x "$mavenLauncher" ]; then
    die "'$mavenLauncher' is missing or not executable;" \
      "remove '$mavenDistHome' and re-run to reinstall the distribution"
  fi
  # Logged before the unset below, because that unset clears the very
  # variable verbose consults and would otherwise silence this line.
  verbose "executing $mavenLauncher"
  # Wrapper-only settings, including any credential, must not leak into
  # the build or into anything the build spawns.
  unset MVNW_VERBOSE MVNW_USERNAME MVNW_PASSWORD MVNW_REPOURL
  exec "$mavenLauncher" "$@"
}

# ------------------------------------------------------------------
# Locate the wrapper configuration. Explicit configuration wins over
# inference; every path stays quoted so names containing spaces work.
# ------------------------------------------------------------------

wrapperPropertiesRelPath='.mvn/wrapper/maven-wrapper.properties'
wrapperBaseDir=''

if [ -n "${MAVEN_PROJECTBASEDIR-}" ]; then
  if [ ! -f "$MAVEN_PROJECTBASEDIR/$wrapperPropertiesRelPath" ]; then
    die "MAVEN_PROJECTBASEDIR is '$MAVEN_PROJECTBASEDIR' but it has no $wrapperPropertiesRelPath;" \
      "unset MAVEN_PROJECTBASEDIR or point it at the repository root"
  fi
  wrapperBaseDir="$(cd -- "$MAVEN_PROJECTBASEDIR" && pwd)" ||
    die "MAVEN_PROJECTBASEDIR '$MAVEN_PROJECTBASEDIR' is not an enterable directory"
else
  scriptDir="$(dirname "$(resolve_symlinks "$0")")"
  wrapperBaseDir="$(find_wrapper_basedir "$scriptDir")" || wrapperBaseDir=''
  if [ -z "$wrapperBaseDir" ]; then
    # Covers invocation through PATH, where $0 carries no usable
    # directory but the caller stands inside the repository.
    wrapperBaseDir="$(find_wrapper_basedir .)" || wrapperBaseDir=''
  fi
  if [ -z "$wrapperBaseDir" ]; then
    die "cannot find $wrapperPropertiesRelPath by searching upwards from '$scriptDir'" \
      "or from '$PWD'; run ./mvnw from the repository or set MAVEN_PROJECTBASEDIR"
  fi
fi

wrapperPropertiesFile="$wrapperBaseDir/$wrapperPropertiesRelPath"
if [ ! -r "$wrapperPropertiesFile" ]; then
  die "'$wrapperPropertiesFile' is not readable; check its permissions"
fi
verbose "wrapper configuration: $wrapperPropertiesFile"

# ------------------------------------------------------------------
# Read the pinned distribution. This file is the only source of the
# Maven version; nothing below substitutes a default for it.
# ------------------------------------------------------------------

distributionUrl=''
distributionSha256Sum=''
while IFS='=' read -r propertyKey propertyValue; do
  case "$propertyKey" in
  distributionUrl) distributionUrl="$(trim "${propertyValue-}")" ;;
  distributionSha256Sum) distributionSha256Sum="$(trim "${propertyValue-}")" ;;
  esac
done <"$wrapperPropertiesFile"

if [ -z "$distributionUrl" ]; then
  die "no distributionUrl property in '$wrapperPropertiesFile'; the pinned Maven version is declared only there"
fi

# An optional private mirror. The rewrite matches the upstream launcher
# so that a cache filled through a mirror keys on the mirror URL.
mvnwRepoPattern='/org/apache/maven/'
if [ -n "${MVNW_REPOURL-}" ]; then
  require_https "$MVNW_REPOURL" 'MVNW_REPOURL'
  case "$distributionUrl" in
  *"$mvnwRepoPattern"*) ;;
  *)
    die "MVNW_REPOURL is set but distributionUrl does not contain '$mvnwRepoPattern'," \
      "so it cannot be remapped: '$distributionUrl'"
    ;;
  esac
  distributionUrl="${MVNW_REPOURL}${mvnwRepoPattern}${distributionUrl#*"$mvnwRepoPattern"}"
  verbose "mirror applied: $distributionUrl"
fi

# Validate after the rewrite, so a mirror cannot downgrade the
# transport or point at a different kind of artifact.
require_https "$distributionUrl" 'distributionUrl'
case "${distributionUrl##*/}" in
apache-maven-?*-bin.zip) ;;
*)
  die "distributionUrl must name a released Apache Maven binary zip," \
    "apache-maven-<version>-bin.zip, but names '${distributionUrl##*/}';" \
    ".tar.gz and maven-mvnd distributions are not supported" \
    "because the pinned checksum covers the zip"
  ;;
esac

# The checksum is mandatory here, unlike upstream, where an absent or
# blank value disables verification without saying so.
if [ ${#distributionSha256Sum} -ne 64 ]; then
  die "distributionSha256Sum in '$wrapperPropertiesFile' must be exactly 64 hexadecimal digits" \
    "but is ${#distributionSha256Sum} characters long;" \
    "verification is mandatory and is never skipped"
fi
case "$distributionSha256Sum" in
*[!0-9a-f]*)
  die "distributionSha256Sum in '$wrapperPropertiesFile' must be lowercase hexadecimal;" \
    "uppercase digits pass on Unix but fail the Windows launcher," \
    "which folds its computed digest to lowercase"
  ;;
esac

# ------------------------------------------------------------------
# Deterministic cache location:
#   <mavenUserHome>/wrapper/dists/<distribution>/<url hash>
# The layout and the hash match the upstream launcher exactly, so a
# cache populated by any Maven Wrapper is reused as-is.
# ------------------------------------------------------------------

distributionArchiveName="${distributionUrl##*/}"
distributionName="${distributionArchiveName%-bin.zip}"

if [ -n "${MAVEN_USER_HOME-}" ]; then
  mavenUserHome="$MAVEN_USER_HOME"
else
  if [ -z "${HOME-}" ]; then
    die "neither MAVEN_USER_HOME nor HOME is set," \
      "so the wrapper cache location cannot be determined; export one of them"
  fi
  mavenUserHome="$HOME/.m2"
fi
case "$mavenUserHome" in
/*) ;;
*)
  die "the wrapper cache root must be an absolute path but is '$mavenUserHome';" \
    "set MAVEN_USER_HOME to an absolute path"
  ;;
esac

mavenDistHomeParent="$mavenUserHome/wrapper/dists/$distributionName"
mavenDistHome="$mavenDistHomeParent/$(hash_string "$distributionUrl")"
mavenLauncher="$mavenDistHome/bin/mvn"

# Java is resolved before any fast path so that a missing or unsuitable
# JDK is reported immediately rather than deep inside the build.
javaCommand=''
resolve_java
report_java_level

if [ -d "$mavenDistHome" ]; then
  verbose "using the cached distribution at $mavenDistHome"
  exec_maven "$@"
fi

# ------------------------------------------------------------------
# Install. Staging lives inside the cache directory so publishing is a
# same-filesystem rename: no other process can observe a half-written
# distribution, and the unique suffix keeps concurrent invocations
# from colliding.
# ------------------------------------------------------------------

mkdir -p -- "$mavenDistHomeParent" ||
  die "cannot create the wrapper cache directory '$mavenDistHomeParent'"

stagingDir="$(mktemp -d "$mavenDistHomeParent/.mvnw-install.XXXXXX")" ||
  die "cannot create a staging directory under '$mavenDistHomeParent'"
if [ ! -d "$stagingDir" ]; then
  die "mktemp reported '$stagingDir' but it is not a directory"
fi

# On a signal, clean up, restore the default disposition and re-raise,
# so the caller observes the real signal death rather than a synthesised
# exit code. cleanup_staging is idempotent, so the EXIT handler that
# follows a re-raise is harmless.
trap 'cleanup_staging' EXIT
trap 'cleanup_staging; trap - HUP; kill -HUP $$' HUP
trap 'cleanup_staging; trap - INT; kill -INT $$' INT
trap 'cleanup_staging; trap - TERM; kill -TERM $$' TERM

if [ "${MVNW_VERBOSE-}" = true ]; then
  curlQuiet=''
  wgetQuiet=''
  unzipQuiet=''
else
  curlQuiet='--silent'
  wgetQuiet='--quiet'
  unzipQuiet='-q'
fi

# Credentials are honoured only as a complete pair; half a pair is
# treated as absent instead of sending an empty user or password.
# The trace is suspended for the whole block, because "set -x" would
# otherwise echo both the assignment and the test arguments after
# expansion. mvnwCredentials is the only flag consulted afterwards, so
# no later statement has to expand the password again.
trace_off
mvnwUsername="${MVNW_USERNAME-}"
mvnwPassword="${MVNW_PASSWORD-}"
mvnwCredentials=false
if [ -n "$mvnwUsername" ] && [ -n "$mvnwPassword" ]; then
  mvnwCredentials=true
elif [ -n "$mvnwUsername" ] || [ -n "$mvnwPassword" ]; then
  mvnwUsername=''
  mvnwPassword=''
  trace_on
  warn "MVNW_USERNAME and MVNW_PASSWORD must both be set to authenticate; ignoring the incomplete pair"
fi
trace_on

downloadTarget="$stagingDir/$distributionArchiveName"
verbose "downloading $distributionUrl"
verbose "        to $downloadTarget"

if command -v curl >/dev/null 2>&1; then
  verbose "downloading with curl"
  if [ "$mvnwCredentials" = true ]; then
    write_netrc
    download_with_curl --netrc-file "$netrcFile" ||
      die "curl could not fetch '$distributionUrl'"
  else
    download_with_curl ||
      die "curl could not fetch '$distributionUrl'"
  fi
elif command -v wget >/dev/null 2>&1; then
  if [ "$mvnwCredentials" = true ]; then
    die "MVNW_USERNAME and MVNW_PASSWORD need curl," \
      "which reads credentials from a private file;" \
      "wget would expose the password in the process list." \
      "Install curl, or clear both variables."
  fi
  verbose "downloading with wget"
  download_with_wget ||
    die "wget could not fetch '$distributionUrl'"
else
  die "no downloader available: install curl or wget so the pinned Maven distribution can be fetched over HTTPS"
fi

# A truncated or empty body is a download failure and must not be
# reported later as a checksum mismatch.
if [ ! -f "$downloadTarget" ]; then
  die "the download produced no file at '$downloadTarget'"
fi
if [ ! -s "$downloadTarget" ]; then
  die "the download produced an empty file at '$downloadTarget'; '$distributionUrl' returned no content"
fi

# Verify before anything is expanded, so unverified bytes are never
# written into the cache and never executed.
verify_sha256 "$distributionSha256Sum" "$downloadTarget"

extract_zip "$downloadTarget" "$stagingDir"

extractedDir="$stagingDir/$distributionName"
if [ ! -d "$extractedDir" ]; then
  warn "'$distributionArchiveName' does not contain the expected directory" \
    "'$distributionName'; the staging directory holds:"
  ls -A -- "$stagingDir" >&2 || warn "(the staging directory could not be listed)"
  die "unexpected archive layout; distributionUrl and distributionSha256Sum in" \
    "'$wrapperPropertiesFile' may describe different artifacts"
fi

# The JDK jar tool does not restore POSIX permission bits, and some
# unzip builds drop them too, so the launcher modes are set explicitly
# rather than assumed to have survived extraction.
for launcherName in mvn mvnDebug mvnyjp; do
  launcherPath="$extractedDir/bin/$launcherName"
  if [ -f "$launcherPath" ] && [ ! -x "$launcherPath" ]; then
    chmod 0755 "$launcherPath" ||
      die "cannot make '$launcherPath' executable"
  fi
done
if [ ! -x "$extractedDir/bin/mvn" ]; then
  die "the expanded distribution at '$extractedDir' has no executable bin/mvn"
fi

# Same provenance marker the upstream launcher writes, so a cache
# directory can always be traced back to the URL it came from.
printf '%s\n' "$distributionUrl" >"$extractedDir/mvnw.url" ||
  die "cannot write the provenance marker '$extractedDir/mvnw.url'"

if [ -d "$mavenDistHome" ]; then
  verbose "another invocation installed '$mavenDistHome' first; discarding this copy"
elif mv -- "$extractedDir" "$mavenDistHome"; then
  verbose "installed $mavenDistHome"
elif [ -d "$mavenDistHome" ]; then
  verbose "lost the install race; using the distribution the other invocation published"
else
  die "cannot install: moving '$extractedDir' to '$mavenDistHome' failed"
fi

cleanup_staging
trap - HUP INT TERM EXIT
exec_maven "$@"

