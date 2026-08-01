@REM ******************************************************************
@REM * File        : mvnw.cmd
@REM * Application : CardDemo
@REM * Type        : Apache Maven Wrapper launcher for Windows cmd.exe
@REM * Function    : Bootstraps the exact Apache Maven distribution pinned in
@REM *               .mvn\wrapper\maven-wrapper.properties, verifies that
@REM *               archive against the SHA-256 recorded in the same file
@REM *               before a single byte of it is executed, caches it once
@REM *               per user, then hands the arguments given to this script
@REM *               to Maven and returns Maven's own exit code. No Apache
@REM *               Maven installation is required on the host and no
@REM *               maven-wrapper.jar is committed to this repository.
@REM * Source      : samples/proc/BUILDBAT.prc, samples/proc/BUILDBMS.prc and
@REM *               samples/proc/BUILDONL.prc together with the compile
@REM *               templates samples/jcl/BATCMP.jcl, samples/jcl/BMSCMP.jcl
@REM *               and samples/jcl/CICCMP.jcl - the frozen z/OS build
@REM *               tooling, none of which is ported. Authored per the Agent
@REM *               Action Plan in docs/technical-specifications.md sections
@REM *               0.3.1.4, 0.3.1.8, 0.4.1.1, 0.5.1.1 and 0.6.1.3, at
@REM *               traceability anchor commit 7756d89.
@REM * Replaces    : the three z/OS build procedures and three compile
@REM *               templates named above, and with them the requirement for
@REM *               a preinstalled build tool on the machine that builds.
@REM * Pairs with  : mvnw, the POSIX launcher, and
@REM *               .mvn\wrapper\maven-wrapper.properties, the single source
@REM *               of truth for the Maven version. Both launchers resolve
@REM *               the identical cache directory for the identical URL.
@REM * Origin      : derived from the Apache Maven Wrapper, script-only
@REM *               variant, version 3.3.4 - the value recorded as
@REM *               wrapperVersion in .mvn\wrapper\maven-wrapper.properties.
@REM *               Apache Maven and the Apache Maven Wrapper are products
@REM *               of the Apache Software Foundation and are licensed to
@REM *               you under the Apache License, Version 2.0. The upstream
@REM *               launcher was restructured here into a plain batch host
@REM *               that shells out to Windows PowerShell only for the
@REM *               download, checksum and archive-expansion steps, so that
@REM *               this file starts with valid batch comments and never
@REM *               evaluates its own text as a script. Every difference is
@REM *               listed under DEVIATIONS below.
@REM * Convention  : this banner reproduces the universal Apache-2.0 source
@REM *               header of the frozen legacy corpus, canonical form
@REM *               app/cbl/CBACT04C.cbl:L1-L21.
@REM ******************************************************************
@REM * Copyright Amazon.com, Inc. or its affiliates.
@REM * All Rights Reserved.
@REM *
@REM * Licensed under the Apache License, Version 2.0 (the "License").
@REM * You may not use this file except in compliance with the License.
@REM * You may obtain a copy of the License at
@REM *
@REM *    http://www.apache.org/licenses/LICENSE-2.0
@REM *
@REM * Unless required by applicable law or agreed to in writing,
@REM * software distributed under the License is distributed on an
@REM * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
@REM * either express or implied. See the License for the specific
@REM * language governing permissions and limitations under the License
@REM ******************************************************************
@REM
@REM ==================================================================
@REM WHAT IT DOES
@REM ==================================================================
@REM Six steps, in this order, and it stops at the first one that fails:
@REM
@REM   1. Locates .mvn\wrapper\maven-wrapper.properties beside this file.
@REM   2. Confirms a Java launcher is reachable, using exactly the order
@REM      that Apache Maven's own bin\mvn.cmd uses: JAVA_HOME\bin\java.exe
@REM      first, then java.exe on PATH.
@REM   3. Reads distributionUrl and distributionSha256Sum from that
@REM      properties file, then rejects the run unless the URL uses HTTPS,
@REM      names an Apache Maven -bin.zip archive, and is accompanied by a
@REM      checksum of exactly 64 lowercase hexadecimal digits.
@REM   4. Derives the per-user cache location
@REM      MAVEN_USER_HOME\wrapper\dists\apache-maven-VERSION\HASH where
@REM      HASH is the Java String hashCode of the distribution URL. The
@REM      POSIX launcher mvnw derives the same value from the same URL, so
@REM      one download serves both launchers.
@REM   5. On a cache miss, downloads the archive into a staging directory
@REM      inside that same cache, verifies its SHA-256, expands it, and
@REM      moves the result into place with a same-volume rename. Nothing
@REM      is executed and nothing is installed until the checksum matches.
@REM   6. Calls MAVEN_HOME\bin\mvn.cmd with the arguments given to this
@REM      script and exits with the code Maven returned.
@REM
@REM Windows PowerShell is used for step 5 and for the URL hash of step 4
@REM only, because cmd.exe alone cannot fetch over TLS, compute SHA-256 or
@REM expand a zip archive. The PowerShell helper receives every input
@REM through environment variables rather than through string
@REM interpolation, so no value read from disk or from the environment can
@REM alter the command that runs.
@REM
@REM ==================================================================
@REM PREREQUISITES
@REM ==================================================================
@REM   * Windows with cmd.exe command extensions enabled. They are on by
@REM     default, and Apache Maven's own bin\mvn.cmd requires them too.
@REM   * Windows PowerShell 5.0 or later, or PowerShell 7 as pwsh.exe.
@REM     Get-FileHash and Expand-Archive are the two cmdlets needed.
@REM   * A JDK reachable through JAVA_HOME or PATH. The required version
@REM     is asserted by the build itself, not here; see below.
@REM   * Outbound HTTPS to Maven Central, or to an HTTPS mirror named by
@REM     MVNW_REPOURL, for the first run only. Later runs are offline.
@REM
@REM ==================================================================
@REM HOW TO RUN, BUILD AND TEST
@REM ==================================================================
@REM From a checkout root, in cmd.exe or PowerShell:
@REM
@REM   mvnw.cmd --version              confirm Maven 3.9.11 on Java 25
@REM   mvnw.cmd -B clean compile       compile the Java sources
@REM   mvnw.cmd -B test                run the unit test tier
@REM   mvnw.cmd -B clean verify        full build with coverage and scans
@REM
@REM The POSIX equivalent is ./mvnw with the same arguments. Quoted
@REM arguments, arguments containing spaces and arguments containing an
@REM exclamation mark all reach Maven unchanged, for example
@REM   mvnw.cmd -B test -Dtest=AccountUpdateServiceTest
@REM   mvnw.cmd -B verify -Dmaven.repo.local=D:\my repo\m2
@REM
@REM There is no test suite for a launcher script. It is exercised by
@REM every build that runs through it, and the checks that protect it are
@REM the validations in step 3 and the checksum gate in step 5.
@REM
@REM ==================================================================
@REM KEY CONFIGURATION AND DEFAULTS
@REM ==================================================================
@REM Read from .mvn\wrapper\maven-wrapper.properties, never from here:
@REM   distributionUrl         which Apache Maven to run. Mandatory.
@REM   distributionSha256Sum   64 lowercase hex digits. Mandatory. An
@REM                           absent or empty value aborts the run rather
@REM                           than silently skipping verification.
@REM
@REM Environment variables this launcher reads:
@REM   JAVA_HOME         a JDK home. When unset, java.exe must be on PATH.
@REM   MAVEN_USER_HOME   cache root. Default USERPROFILE\.m2, then
@REM                     HOMEDRIVE plus HOMEPATH followed by \.m2.
@REM   MVNW_REPOURL      base URL of an HTTPS mirror of Maven Central.
@REM                     The path from /org/apache/maven/ onwards is
@REM                     preserved. A trailing slash is tolerated.
@REM   MVNW_USERNAME     basic-auth user for that mirror. Optional.
@REM   MVNW_PASSWORD     basic-auth password for that mirror. Optional.
@REM                     Never echoed, never logged, and cleared before
@REM                     Apache Maven is started.
@REM   MVNW_VERBOSE      true traces each step on stderr. debug does that
@REM                     and additionally echoes every command, which
@REM                     includes the Maven arguments you typed, so avoid
@REM                     debug in a shared log if those carry secrets.
@REM                     Any other value, including unset, stays quiet.
@REM
@REM Environment variables passed through untouched, because Apache
@REM Maven's own bin\mvn.cmd owns them: MAVEN_OPTS, MAVEN_ARGS,
@REM MAVEN_DEBUG_OPTS, MAVEN_PROJECTBASEDIR, MAVEN_SKIP_RC,
@REM MAVEN_BATCH_PAUSE and MAVEN_TERMINATE_CMD.
@REM
@REM This launcher deliberately does NOT check the Java or Maven version.
@REM The maven-enforcer-plugin rules in pom.xml, requireJavaVersion and
@REM requireMavenVersion, are the single authority for the Java 25 and
@REM Maven 3.9.11 floor. A second, weaker check here could only ever
@REM disagree with them or silently accept a lower toolchain.
@REM
@REM ==================================================================
@REM EXIT CODES
@REM ==================================================================
@REM   0            Apache Maven ran and reported success.
@REM   1            this launcher refused to start Apache Maven. The
@REM                reason is on stderr, prefixed with [mvnw] ERROR.
@REM   any other    the exit code Apache Maven itself returned, passed
@REM                through unchanged.
@REM
@REM ==================================================================
@REM FAILURE MODES AND TROUBLESHOOTING
@REM ==================================================================
@REM   cannot find the wrapper configuration
@REM       .mvn\wrapper\maven-wrapper.properties is missing. Run the
@REM       script from a complete checkout, not from a copied-out file.
@REM   no Java launcher found
@REM       Install a JDK 25 and set JAVA_HOME to its home directory, or
@REM       put its bin directory on PATH.
@REM   JAVA_HOME is set but bin\java.exe is missing under it
@REM       JAVA_HOME points at the bin directory, at a JRE-less layout or
@REM       at a stale path. Point it at the JDK home itself. Surrounding
@REM       the value with quotation marks in the environment also causes
@REM       this; store the bare path.
@REM   no Windows PowerShell found
@REM       powershell.exe is expected under SystemRoot\System32 or on
@REM       PATH, and pwsh.exe is accepted as a fallback. Without one of
@REM       them nothing can be downloaded, hashed or expanded.
@REM   the distribution must be fetched over HTTPS
@REM       distributionUrl, or MVNW_REPOURL, uses a plaintext scheme.
@REM       Only HTTPS is accepted, for both the pin and any mirror.
@REM   distributionSha256Sum is missing, empty or not 64 lowercase hex
@REM       Restore the checksum of the pinned archive. When the pinned
@REM       Maven version changes, the URL and the checksum change
@REM       together; changing one alone is the usual cause.
@REM   SHA-256 verification FAILED
@REM       The bytes received are not the bytes that were pinned. The
@REM       archive is discarded, nothing is installed and Apache Maven is
@REM       not started. Treat a repeatable failure as a compromised
@REM       mirror or proxy, not as a wrapper defect.
@REM   download failure or a timeout
@REM       Network, proxy or mirror problem. The cache is left untouched,
@REM       so a later run simply retries. A cold cache fetches about 9 MB
@REM       once; every later run is offline.
@REM   release version 25 not supported
@REM       Apache Maven started but JAVA_HOME is not a JDK 25. This comes
@REM       from the compiler, not from this launcher.
@REM   directories named .mvnw-staging- under the cache
@REM       Leftovers from an install that was interrupted, for instance
@REM       by Ctrl-C. They contain no installed Maven and can be deleted.
@REM
@REM ==================================================================
@REM SECURITY PROPERTIES
@REM ==================================================================
@REM   * HTTPS is mandatory. Certificate validation is never relaxed and
@REM     no callback is installed. On Windows PowerShell the transport
@REM     floor is raised to TLS 1.2 and, where available, TLS 1.3; it is
@REM     never lowered.
@REM   * The SHA-256 recorded in the properties file is mandatory and is
@REM     verified before the archive is expanded, before anything is
@REM     installed and before any Maven code runs.
@REM   * The archive is staged inside the caller's own cache tree, on the
@REM     same volume as its destination, so the install is a single
@REM     rename and never lands in a world-writable temporary directory.
@REM   * The helper is a fixed script. It contains no Invoke-Expression,
@REM     builds no script block from file or network content, and pipes
@REM     nothing downloaded into an interpreter. All of its inputs arrive
@REM     as environment variables, so none of them can be parsed as code.
@REM   * PSModulePath is cleared for the helper so that no module on a
@REM     user path can shadow Get-FileHash or Expand-Archive.
@REM   * No credential, key, token or absolute machine path is stored in
@REM     this file. MVNW_USERNAME and MVNW_PASSWORD are read from the
@REM     environment, are never printed even under MVNW_VERBOSE, and are
@REM     cleared, along with MVNW_REPOURL, before Apache Maven starts.
@REM   * No error is suppressed. Every failure prints its cause and exits
@REM     nonzero; the one non-fatal condition, a staging directory that
@REM     could not be removed, prints a warning that names the directory.
@REM
@REM ==================================================================
@REM DEVIATIONS FROM THE UPSTREAM LAUNCHER, AND WHY
@REM ==================================================================
@REM   1. Plain batch host instead of the polyglot script that reads its
@REM      own file and turns the text into a script block. That upstream
@REM      construct is equivalent to Invoke-Expression, and it forces the
@REM      first line of the file to be PowerShell rather than a comment.
@REM   2. HTTPS is enforced. Upstream never inspects the URL scheme.
@REM   3. The checksum is mandatory. Upstream verifies only when the
@REM      property happens to be present, so deleting it disables the
@REM      check silently.
@REM   4. Staging happens inside the destination cache, making the final
@REM      install an atomic same-volume rename. Upstream stages in the
@REM      shared temporary directory and can cross a volume boundary.
@REM   5. The cache directory is named with the same Java String hashCode
@REM      digest that mvnw uses, so the two launchers share one entry.
@REM      Upstream's Windows script uses a different digest from its own
@REM      POSIX script and the two never share a download.
@REM   6. The TLS floor is 1.2 plus 1.3 where available. Upstream pins
@REM      1.2 alone, which excludes 1.3.
@REM   7. mvnd distributions and the mvnwDebug name derivation are not
@REM      implemented. Neither is reachable from this repository's pinned
@REM      configuration, so carrying them would be untested dead code;
@REM      an mvnd URL is rejected with an explicit message instead.
@REM   8. Java is located and diagnosed but never version-checked, for
@REM      the reason given under KEY CONFIGURATION AND DEFAULTS.
@REM ==================================================================

@echo off
REM ---- Command extensions supply CALL, EXIT /B, IF DEFINED and the FOR /F
REM ---- token parsing used below. They are on by default; Apache Maven's own
REM ---- bin\mvn.cmd requires them just as unconditionally and, like this
REM ---- launcher, does not test for them. A test would be self-defeating,
REM ---- because reporting the absence would itself need EXIT /B.
REM ----
REM ---- Delayed expansion is switched OFF on purpose. With it on, cmd.exe
REM ---- would consume an exclamation mark inside a Maven argument, such as a
REM ---- -D property value ending in one, before Maven ever saw it. Requesting
REM ---- it here also neutralises an outer shell started with the /V:ON switch.
setlocal EnableExtensions DisableDelayedExpansion

REM ---- Verbosity. true traces each step on stderr; debug does that and also
REM ---- echoes every command, the batch counterpart of the POSIX set -x.
set "__MVNW_TRACE=0"
if /i "%MVNW_VERBOSE%"=="true"  set "__MVNW_TRACE=1"
if /i "%MVNW_VERBOSE%"=="debug" set "__MVNW_TRACE=1"
if /i "%MVNW_VERBOSE%"=="debug" echo on

REM ==================================================================
REM Step 1 - locate the wrapper configuration beside this script.
REM The script directory reference used here already ends in a backslash,
REM and every path below is quoted, so a checkout path containing spaces,
REM brackets or an ampersand is handled correctly.
REM ==================================================================
set "__MVNW_ROOT=%~dp0"
set "__MVNW_PROPS=%__MVNW_ROOT%.mvn\wrapper\maven-wrapper.properties"
if not exist "%__MVNW_PROPS%" (
    echo [mvnw] ERROR: cannot find the wrapper configuration file. 1>&2
    echo [mvnw]        expected: "%__MVNW_PROPS%" 1>&2
    echo [mvnw]        Run this launcher from a complete checkout. 1>&2
    exit /b 1
)
if "%__MVNW_TRACE%"=="1" echo [mvnw] configuration: "%__MVNW_PROPS%" 1>&2

REM ==================================================================
REM Step 2 - confirm a Java launcher is reachable, using the same order as
REM Apache Maven's own bin\mvn.cmd. The version is deliberately NOT checked:
REM maven-enforcer-plugin in pom.xml is the single authority for that floor.
REM ==================================================================
set "__MVNW_JAVA="
if not "%JAVA_HOME%"=="" goto :javaFromHome
for %%J in (java.exe) do set "__MVNW_JAVA=%%~$PATH:J"
if not "%__MVNW_JAVA%"=="" goto :javaFound
echo [mvnw] ERROR: no Java launcher found, so Apache Maven cannot start. 1>&2
echo [mvnw]        JAVA_HOME is not set and java.exe is not on PATH. 1>&2
echo [mvnw]        Install a JDK 25 and set JAVA_HOME to its home directory. 1>&2
exit /b 1

:javaFromHome
set "__MVNW_JAVA=%JAVA_HOME%\bin\java.exe"
if exist "%__MVNW_JAVA%" goto :javaFound
echo [mvnw] ERROR: JAVA_HOME does not contain bin\java.exe. 1>&2
echo [mvnw]        JAVA_HOME: "%JAVA_HOME%" 1>&2
echo [mvnw]        expected:  "%__MVNW_JAVA%" 1>&2
echo [mvnw]        Point JAVA_HOME at the JDK home itself, without quotation marks. 1>&2
exit /b 1

:javaFound
if "%__MVNW_TRACE%"=="1" echo [mvnw] java: "%__MVNW_JAVA%" 1>&2

REM ==================================================================
REM Step 3 - resolve the per-user cache root exactly as mvnw does, with
REM MAVEN_USER_HOME winning and the user profile as the documented default.
REM ==================================================================
set "__MVNW_M2=%MAVEN_USER_HOME%"
if not "%__MVNW_M2%"=="" goto :cacheRootFound
if not "%USERPROFILE%"=="" set "__MVNW_M2=%USERPROFILE%\.m2"
if not "%__MVNW_M2%"=="" goto :cacheRootFound
if not "%HOMEDRIVE%%HOMEPATH%"=="" set "__MVNW_M2=%HOMEDRIVE%%HOMEPATH%\.m2"
if not "%__MVNW_M2%"=="" goto :cacheRootFound
echo [mvnw] ERROR: cannot determine your home directory. 1>&2
echo [mvnw]        MAVEN_USER_HOME, USERPROFILE, HOMEDRIVE and HOMEPATH are all empty. 1>&2
echo [mvnw]        Set MAVEN_USER_HOME to a writable directory and re-run. 1>&2
exit /b 1

:cacheRootFound
set "__MVNW_DISTS=%__MVNW_M2%\wrapper\dists"
if "%__MVNW_TRACE%"=="1" echo [mvnw] cache: "%__MVNW_DISTS%" 1>&2

REM ==================================================================
REM Step 4 - resolve a PowerShell host. cmd.exe cannot fetch over TLS,
REM compute SHA-256 or expand a zip archive, so exactly those three
REM operations are delegated. The search order is fixed for determinism.
REM ==================================================================
set "__MVNW_PS_SYS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
set "__MVNW_PS_EXE="
if exist "%__MVNW_PS_SYS%" set "__MVNW_PS_EXE=%__MVNW_PS_SYS%"
if not "%__MVNW_PS_EXE%"=="" goto :powerShellFound
for %%P in (powershell.exe) do set "__MVNW_PS_EXE=%%~$PATH:P"
if not "%__MVNW_PS_EXE%"=="" goto :powerShellFound
for %%P in (pwsh.exe) do set "__MVNW_PS_EXE=%%~$PATH:P"
if not "%__MVNW_PS_EXE%"=="" goto :powerShellFound
echo [mvnw] ERROR: no PowerShell host found, so the pinned Apache Maven 1>&2
echo [mvnw]        cannot be downloaded, verified or expanded. 1>&2
echo [mvnw]        Looked under SystemRoot\System32 for powershell.exe, then on 1>&2
echo [mvnw]        PATH for powershell.exe, then on PATH for pwsh.exe. 1>&2
exit /b 1

:powerShellFound
if "%__MVNW_TRACE%"=="1" echo [mvnw] powershell: "%__MVNW_PS_EXE%" 1>&2

REM ==================================================================
REM Step 5 - resolve, and if necessary install, the pinned distribution.
REM
REM The helper receives every input through the environment rather than
REM through interpolated text, so no value read from the properties file or
REM from the environment can become part of the command that runs.
REM ==================================================================
set "MVNW_PROPERTIES_FILE=%__MVNW_PROPS%"
set "MVNW_DISTS_DIR=%__MVNW_DISTS%"

REM ---- PSModulePath is emptied so that no module on a user or machine path
REM ---- can shadow Get-FileHash or Expand-Archive. PowerShell restores its
REM ---- own defaults from an empty value, and SETLOCAL keeps this local.
set "PSModulePath="

REM ---- The helper is assembled one statement per line so that it stays
REM ---- readable and reviewable. Two characters are banned from every
REM ---- fragment. A double quotation mark would close the SET quoting and
REM ---- let the remainder run as cmd.exe commands. A percent sign would be
REM ---- expanded by cmd.exe before PowerShell ever saw it. That is why the
REM ---- PowerShell strings below are single quoted, messages are built with
REM ---- the plus operator, and the 32-bit wrap uses -band, not a modulo.
REM ----
REM ---- Diagnostics are written to standard error directly, never through
REM ---- the verbose stream. When standard output is redirected, as it is
REM ---- here, PowerShell folds the verbose stream into standard output, so
REM ---- a Write-Verbose call would land in the channel this launcher parses
REM ---- for its one contract line. Raising VerbosePreference would do the
REM ---- same for any cmdlet's own verbose output. Writing to the error
REM ---- stream explicitly keeps standard output single-purpose on every
REM ---- PowerShell edition and host, whether redirected or not.
set "__MVNW_PS=$ErrorActionPreference = 'Stop';"
set "__MVNW_PS=%__MVNW_PS% $ProgressPreference = 'SilentlyContinue';"
set "__MVNW_PS=%__MVNW_PS% function Fail([string]$m)"
set "__MVNW_PS=%__MVNW_PS%   { [Console]::Error.WriteLine('[mvnw] ERROR: ' + $m); exit 1 };"
set "__MVNW_PS=%__MVNW_PS% function Note([string]$m) {"
set "__MVNW_PS=%__MVNW_PS%   if ($env:MVNW_VERBOSE -in @('true', 'debug'))"
set "__MVNW_PS=%__MVNW_PS%     { [Console]::Error.WriteLine('[mvnw] ' + $m) } };"

REM ---- 5a. Read and validate the inputs handed over by the batch host.
set "__MVNW_PS=%__MVNW_PS% $propsFile = $env:MVNW_PROPERTIES_FILE;"
set "__MVNW_PS=%__MVNW_PS% $distsDir = $env:MVNW_DISTS_DIR;"
set "__MVNW_PS=%__MVNW_PS% if (-not $propsFile)"
set "__MVNW_PS=%__MVNW_PS%   { Fail('the launcher did not pass MVNW_PROPERTIES_FILE') };"
set "__MVNW_PS=%__MVNW_PS% if (-not $distsDir)"
set "__MVNW_PS=%__MVNW_PS%   { Fail('the launcher did not pass MVNW_DISTS_DIR') };"
set "__MVNW_PS=%__MVNW_PS% if (-not (Test-Path -LiteralPath $propsFile -PathType Leaf))"
set "__MVNW_PS=%__MVNW_PS%   { Fail('wrapper configuration not found: ' + $propsFile) };"

REM ---- 5b. Parse the properties file. Every key and value is trimmed, which
REM ---- also removes a stray carriage return when the file has been checked
REM ---- out with Windows line endings. This is upstream issue MWRAPPER-139.
set "__MVNW_PS=%__MVNW_PS% $props = @{};"
set "__MVNW_PS=%__MVNW_PS% foreach ($line in (Get-Content -LiteralPath $propsFile)) {"
set "__MVNW_PS=%__MVNW_PS%   $entry = $line.Trim();"
set "__MVNW_PS=%__MVNW_PS%   if ($entry.Length -eq 0) { continue };"
set "__MVNW_PS=%__MVNW_PS%   if ($entry.StartsWith('#') -or $entry.StartsWith('!')) { continue };"
set "__MVNW_PS=%__MVNW_PS%   $split = $entry.IndexOf('=');"
set "__MVNW_PS=%__MVNW_PS%   if ($split -lt 1) { continue };"
set "__MVNW_PS=%__MVNW_PS%   $props[$entry.Substring(0, $split).Trim()] ="
set "__MVNW_PS=%__MVNW_PS%     $entry.Substring($split + 1).Trim()"
set "__MVNW_PS=%__MVNW_PS% };"

REM ---- 5c. The distribution URL is mandatory. So is its checksum: an absent
REM ---- or empty value must abort rather than silently skip verification.
set "__MVNW_PS=%__MVNW_PS% $url = $props['distributionUrl'];"
set "__MVNW_PS=%__MVNW_PS% if (-not $url)"
set "__MVNW_PS=%__MVNW_PS%   { Fail('cannot read the distributionUrl property in ' + $propsFile) };"
set "__MVNW_PS=%__MVNW_PS% $sum = $props['distributionSha256Sum'];"
set "__MVNW_PS=%__MVNW_PS% if (-not $sum) { Fail('distributionSha256Sum is missing or empty in '"
set "__MVNW_PS=%__MVNW_PS%   + $propsFile + '. This launcher never installs an unverified'"
set "__MVNW_PS=%__MVNW_PS%   + ' Apache Maven distribution.') };"
set "__MVNW_PS=%__MVNW_PS% if ($sum -cnotmatch '^[0-9a-f]{64}$') { Fail('distributionSha256Sum in '"
set "__MVNW_PS=%__MVNW_PS%   + $propsFile + ' must be exactly 64 lowercase hexadecimal digits,'"
set "__MVNW_PS=%__MVNW_PS%   + ' but it is: ' + $sum) };"

REM ---- 5d. Apply an optional mirror, then require HTTPS for whatever URL
REM ---- results. A trailing slash on the mirror base is tolerated.
set "__MVNW_PS=%__MVNW_PS% if ($env:MVNW_REPOURL) {"
set "__MVNW_PS=%__MVNW_PS%   $anchor = '/org/apache/maven/';"
set "__MVNW_PS=%__MVNW_PS%   $at = $url.IndexOf($anchor);"
set "__MVNW_PS=%__MVNW_PS%   if ($at -lt 0) { Fail('MVNW_REPOURL is set but distributionUrl does'"
set "__MVNW_PS=%__MVNW_PS%     + ' not contain ' + $anchor + ', so no mirror can be applied to: '"
set "__MVNW_PS=%__MVNW_PS%     + $url) };"
set "__MVNW_PS=%__MVNW_PS%   $url = $env:MVNW_REPOURL.TrimEnd('/') + $url.Substring($at)"
set "__MVNW_PS=%__MVNW_PS% };"
set "__MVNW_PS=%__MVNW_PS% if ($url -notmatch '^https://') { Fail('the Apache Maven distribution'"
set "__MVNW_PS=%__MVNW_PS%   + ' must be fetched over HTTPS, but the URL is: ' + $url) };"

REM ---- 5e. Derive the archive name and the version-bearing directory name.
REM ---- Only an Apache Maven -bin.zip archive is supported; an mvnd URL is
REM ---- rejected outright rather than handled by untested code.
set "__MVNW_PS=%__MVNW_PS% $name = $url.Substring($url.LastIndexOf('/') + 1);"
set "__MVNW_PS=%__MVNW_PS% if ($name -like 'maven-mvnd-*') { Fail('this launcher installs the'"
set "__MVNW_PS=%__MVNW_PS%   + ' Apache Maven distribution only, but distributionUrl names an'"
set "__MVNW_PS=%__MVNW_PS%   + ' mvnd archive: ' + $name) };"
set "__MVNW_PS=%__MVNW_PS% if ($name -notlike '?*-bin.zip') { Fail('distributionUrl must name a'"
set "__MVNW_PS=%__MVNW_PS%   + ' -bin.zip archive, but it ends with: ' + $name) };"
set "__MVNW_PS=%__MVNW_PS% $main = $name.Substring(0, $name.Length - 8);"

REM ---- 5f. Cache location. The digest is the Java String hashCode of the
REM ---- URL, which is exactly what mvnw computes, so both launchers resolve
REM ---- the same directory. The URL is ASCII by construction, so a char code
REM ---- and the byte the POSIX script reads are the same number.
REM ----
REM ---- The mask MUST stay the decimal literal 4294967295, i.e. 2 to the
REM ---- 32nd minus 1. It is the direct counterpart of the modulo that the
REM ---- POSIX twin applies, which cannot be written here because a percent
REM ---- sign would be eaten by cmd.exe before PowerShell ever saw it.
REM ---- Do NOT rewrite it as the hex literal: PowerShell types an 8-digit
REM ---- hex literal as a signed 32-bit value, so that spelling is minus
REM ---- one, the mask silently becomes a no-op, the accumulator overflows
REM ---- into a double and the resolved cache directory is wrong. A decimal
REM ---- literal above the signed 32-bit range is widened to 64 bits by
REM ---- every PowerShell version, which is why this form is used.
set "__MVNW_PS=%__MVNW_PS% $digest = 0;"
set "__MVNW_PS=%__MVNW_PS% foreach ($ch in $url.ToCharArray())"
set "__MVNW_PS=%__MVNW_PS%   { $digest = (($digest * 31) + [int]$ch) -band 4294967295 };"
set "__MVNW_PS=%__MVNW_PS% $parent = Join-Path $distsDir $main;"
set "__MVNW_PS=%__MVNW_PS% $mvnHome = Join-Path $parent ('{0:x}' -f [uint32]$digest);"
set "__MVNW_PS=%__MVNW_PS% $launcher = Join-Path (Join-Path $mvnHome 'bin') 'mvn.cmd';"
set "__MVNW_PS=%__MVNW_PS% if (Test-Path -LiteralPath $launcher -PathType Leaf) {"
set "__MVNW_PS=%__MVNW_PS%   Note('using the cached Apache Maven at ' + $mvnHome);"
set "__MVNW_PS=%__MVNW_PS%   Write-Output ('MVNW_MAVEN_HOME=' + $mvnHome);"
set "__MVNW_PS=%__MVNW_PS%   exit 0"
set "__MVNW_PS=%__MVNW_PS% };"

REM ---- 5g. Cache miss. Stage inside the destination's own parent so the
REM ---- final move is a same-volume rename, and so the archive never rests
REM ---- in a world-writable temporary directory. The name is unguessable.
set "__MVNW_PS=%__MVNW_PS% Note('no cached distribution at ' + $mvnHome + ', installing it now');"
set "__MVNW_PS=%__MVNW_PS% Note('downloading ' + $url);"
set "__MVNW_PS=%__MVNW_PS% New-Item -ItemType Directory -Path $parent -Force | Out-Null;"
set "__MVNW_PS=%__MVNW_PS% $stage = Join-Path $parent"
set "__MVNW_PS=%__MVNW_PS%   ('.mvnw-staging-' + [Guid]::NewGuid().ToString('N'));"
set "__MVNW_PS=%__MVNW_PS% New-Item -ItemType Directory -Path $stage | Out-Null;"
set "__MVNW_PS=%__MVNW_PS% try {"
set "__MVNW_PS=%__MVNW_PS%   $archive = Join-Path $stage $name;"

REM ---- 5h. Raise the transport floor on Windows PowerShell, whose default
REM ---- still admits SSL 3.0 and TLS 1.0. Certificate validation is never
REM ---- touched. PowerShell 7 already negotiates the system best.
set "__MVNW_PS=%__MVNW_PS%   if ($PSVersionTable.PSEdition -eq 'Desktop') {"
set "__MVNW_PS=%__MVNW_PS%     $tls = [Net.SecurityProtocolType]::Tls12;"
set "__MVNW_PS=%__MVNW_PS%     if ([Enum]::GetNames([Net.SecurityProtocolType]) -contains 'Tls13')"
set "__MVNW_PS=%__MVNW_PS%       { $tls = $tls -bor [Net.SecurityProtocolType]::Tls13 };"
set "__MVNW_PS=%__MVNW_PS%     [Net.ServicePointManager]::SecurityProtocol = $tls"
set "__MVNW_PS=%__MVNW_PS%   };"

REM ---- 5i. Download. The optional credentials are read straight from the
REM ---- environment into the request and are never rendered anywhere. A
REM ---- transport failure is reported as a diagnostic that names the URL
REM ---- and unwraps the underlying cause, rather than surfacing a raw .NET
REM ---- reflection exception, and it names MVNW_REPOURL when a mirror is
REM ---- what actually failed. The archive never arrives, so nothing is
REM ---- verified, expanded, installed or executed on this path.
set "__MVNW_PS=%__MVNW_PS%   $client = New-Object System.Net.WebClient;"
set "__MVNW_PS=%__MVNW_PS%   try {"
set "__MVNW_PS=%__MVNW_PS%     if ($env:MVNW_USERNAME -and $env:MVNW_PASSWORD)"
set "__MVNW_PS=%__MVNW_PS%       { $client.Credentials = New-Object System.Net.NetworkCredential("
set "__MVNW_PS=%__MVNW_PS%         $env:MVNW_USERNAME, $env:MVNW_PASSWORD) };"
set "__MVNW_PS=%__MVNW_PS%     $client.DownloadFile($url, $archive)"
set "__MVNW_PS=%__MVNW_PS%   } catch {"
set "__MVNW_PS=%__MVNW_PS%     $cause = $_.Exception;"
set "__MVNW_PS=%__MVNW_PS%     while ($cause.InnerException) { $cause = $cause.InnerException };"
set "__MVNW_PS=%__MVNW_PS%     $hint = '';"
set "__MVNW_PS=%__MVNW_PS%     if ($env:MVNW_REPOURL)"
set "__MVNW_PS=%__MVNW_PS%       { $hint = ' MVNW_REPOURL is set, so this request went to that mirror'"
set "__MVNW_PS=%__MVNW_PS%         + ' rather than to the pinned host; clear it to bypass the'"
set "__MVNW_PS=%__MVNW_PS%         + ' mirror.' };"
set "__MVNW_PS=%__MVNW_PS%     Fail('could not download the Apache Maven distribution from ' + $url"
set "__MVNW_PS=%__MVNW_PS%       + ' - ' + $cause.Message + '.' + $hint"
set "__MVNW_PS=%__MVNW_PS%       + ' Nothing was installed and Apache Maven was NOT started.')"
set "__MVNW_PS=%__MVNW_PS%   } finally { $client.Dispose() };"

REM ---- 5j. Verify before anything is expanded, installed or executed.
set "__MVNW_PS=%__MVNW_PS%   $actual ="
set "__MVNW_PS=%__MVNW_PS%     (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant();"
set "__MVNW_PS=%__MVNW_PS%   if ($actual -cne $sum) { Fail('SHA-256 verification FAILED for ' + $url"
set "__MVNW_PS=%__MVNW_PS%     + '. expected ' + $sum + ' but computed ' + $actual"
set "__MVNW_PS=%__MVNW_PS%     + '. Nothing was installed and Apache Maven was NOT started.') };"
set "__MVNW_PS=%__MVNW_PS%   Note('SHA-256 verified, expanding the archive');"

REM ---- 5k. Expand, then find the one expanded directory that really is a
REM ---- Maven distribution. Sorting keeps the choice deterministic.
set "__MVNW_PS=%__MVNW_PS%   Expand-Archive -LiteralPath $archive -DestinationPath $stage -Force;"
set "__MVNW_PS=%__MVNW_PS%   Remove-Item -LiteralPath $archive -Force;"
set "__MVNW_PS=%__MVNW_PS%   $found = $null;"
set "__MVNW_PS=%__MVNW_PS%   foreach ($dir in (Get-ChildItem -LiteralPath $stage -Directory |"
set "__MVNW_PS=%__MVNW_PS%     Sort-Object -Property Name)) {"
set "__MVNW_PS=%__MVNW_PS%     $probe = Join-Path (Join-Path $dir.FullName 'bin') 'mvn.cmd';"
set "__MVNW_PS=%__MVNW_PS%     if (Test-Path -LiteralPath $probe -PathType Leaf)"
set "__MVNW_PS=%__MVNW_PS%       { $found = $dir.FullName; break }"
set "__MVNW_PS=%__MVNW_PS%   };"
set "__MVNW_PS=%__MVNW_PS%   if (-not $found) { Fail('the verified archive ' + $name + ' contains no'"
set "__MVNW_PS=%__MVNW_PS%     + ' directory holding bin\mvn.cmd, so nothing was installed') };"

REM ---- 5l. Record the origin, then install with one rename. A concurrent
REM ---- run that got there first is accepted; any other failure is re-thrown
REM ---- with its original cause intact.
set "__MVNW_PS=%__MVNW_PS%   Set-Content -LiteralPath (Join-Path $found 'mvnw.url') -Value $url;"
set "__MVNW_PS=%__MVNW_PS%   try { Move-Item -LiteralPath $found -Destination $mvnHome }"
set "__MVNW_PS=%__MVNW_PS%   catch {"
set "__MVNW_PS=%__MVNW_PS%     if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) { throw };"
set "__MVNW_PS=%__MVNW_PS%     Note('a concurrent run installed the same distribution first')"
set "__MVNW_PS=%__MVNW_PS%   }"

REM ---- 5m. Always clear the staging directory. A failure to remove it is
REM ---- reported rather than swallowed, and is not fatal: no Maven code was
REM ---- ever run from it and it holds no installed distribution.
set "__MVNW_PS=%__MVNW_PS% } finally {"
set "__MVNW_PS=%__MVNW_PS%   if (Test-Path -LiteralPath $stage) {"
set "__MVNW_PS=%__MVNW_PS%     try { Remove-Item -LiteralPath $stage -Recurse -Force }"
set "__MVNW_PS=%__MVNW_PS%     catch { [Console]::Error.WriteLine('[mvnw] WARNING: could not remove'"
set "__MVNW_PS=%__MVNW_PS%       + ' the staging directory ' + $stage + ' - ' + $_.Exception.Message) }"
set "__MVNW_PS=%__MVNW_PS%   }"
set "__MVNW_PS=%__MVNW_PS% };"
set "__MVNW_PS=%__MVNW_PS% if (-not (Test-Path -LiteralPath $launcher -PathType Leaf))"
set "__MVNW_PS=%__MVNW_PS%   { Fail('the install completed but ' + $launcher + ' is missing') };"
set "__MVNW_PS=%__MVNW_PS% Note('installed Apache Maven at ' + $mvnHome);"
set "__MVNW_PS=%__MVNW_PS% Write-Output ('MVNW_MAVEN_HOME=' + $mvnHome)"

REM ---- Run the helper. Its only standard-output line is the contract line
REM ---- consumed below; every message it emits goes to standard error, so
REM ---- diagnostics reach the console instead of being captured here. Only
REM ---- the one expected key is accepted, so even if something unforeseen
REM ---- did reach standard output it could not define a variable here.
REM ----
REM ---- -NoProfile keeps a user profile script out of the run, for
REM ---- determinism and because a profile is itself a script file.
REM ---- -NonInteractive turns any prompt into an error instead of a hang, so
REM ---- an unattended build can never stall here.
REM ---- The execution-policy switch is deliberately NOT passed: it governs
REM ---- script files, not -Command, so relaxing it would buy nothing.
set "__MVNW_PS_OPTS=-NoProfile -NonInteractive -Command"
set "__MVNW_MAVEN_HOME="
for /f "usebackq tokens=1,* delims==" %%A in (`"%__MVNW_PS_EXE%" %__MVNW_PS_OPTS% "%__MVNW_PS%"`) do (
    if /i "%%A"=="MVNW_MAVEN_HOME" set "__MVNW_MAVEN_HOME=%%B"
)

if not defined __MVNW_MAVEN_HOME (
    echo [mvnw] ERROR: the wrapper helper did not report an Apache Maven home, 1>&2
    echo [mvnw]        so Apache Maven was NOT started. 1>&2
    echo [mvnw]        Any message above explains why. For a step by step trace, 1>&2
    echo [mvnw]        set MVNW_VERBOSE to true and run the same command again. 1>&2
    exit /b 1
)
if not exist "%__MVNW_MAVEN_HOME%\bin\mvn.cmd" (
    echo [mvnw] ERROR: the resolved Apache Maven home has no bin\mvn.cmd, 1>&2
    echo [mvnw]        so Apache Maven was NOT started. 1>&2
    echo [mvnw]        resolved: "%__MVNW_MAVEN_HOME%" 1>&2
    echo [mvnw]        Delete that directory and run again to download and 1>&2
    echo [mvnw]        verify the pinned distribution from scratch. 1>&2
    exit /b 1
)
if "%__MVNW_TRACE%"=="1" echo [mvnw] maven home: "%__MVNW_MAVEN_HOME%" 1>&2

REM ==================================================================
REM Step 6 - run Apache Maven. The wrapper's own variables are cleared first
REM so that the download credentials and the mirror override are not
REM inherited by the build, then the caller's arguments are forwarded
REM verbatim and Maven's own exit code is returned unchanged.
REM ==================================================================
set "MVNW_USERNAME="
set "MVNW_PASSWORD="
set "MVNW_REPOURL="
set "MVNW_VERBOSE="
set "MVNW_PROPERTIES_FILE="
set "MVNW_DISTS_DIR="
set "__MVNW_PS="
set "__MVNW_PS_OPTS="

call "%__MVNW_MAVEN_HOME%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
