/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.service.auth
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 *               (Service layer (sign-on))
 * Function    : Sign-on. Credential normalisation, BCrypt
 *               verification and token issue, replacing the plaintext
 *               password compare of COSGN00C and the COMMAREA identity
 *               it populated.
 * Source      : app/cbl/COSGN00C.cbl (260 lines, 6 own paragraph labels) @ 7756d89
 * Source      : app/cbl/COSGN00C.cbl:L132-L136 (UPPER-CASE BOTH identifier and password) @ 7756d89
 * Source      : app/cbl/COSGN00C.cbl:L209-L257 (READ-USER-SEC-FILE), :L223 (SEC-USR-PWD compare) @ 7756d89
 * Source      : app/cpy/CSUSR01Y.cpy:L17-L23 (80-byte SEC-USER-DATA layout, key 8) @ 7756d89
 * Source      : app/cpy/COCOM01Y.cpy:L25-L28 (CDEMO-USER-ID and CDEMO-USER-TYPE 'A'/'U') @ 7756d89
 * Source      : app/jcl/DUSRSECJ.jcl:L35-L44 (the ten inline seed users, every one carrying the literal plaintext
 *               password) @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD (DEFINE TRANSACTION(CC00) and DEFINE FILE(USRSEC)) @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */

/**
 * Sign-on: the one service that decides whether a caller is who they claim to be.
 *
 * <h2>What it does</h2>
 *
 * <p>{@link com.cardemo.service.auth.AuthenticationService} reproduces
 * {@code app/cbl/COSGN00C.cbl} - 260 lines across 6 paragraphs - and replaces two of its mechanisms rather than
 * one. The plaintext password comparison at {@code app/cbl/COSGN00C.cbl:L223} becomes a BCrypt verification, and
 * the COMMAREA identity the source populated becomes claims in a signed token: {@code CDEMO-USER-ID} the
 * subject, {@code CDEMO-USER-TYPE} the authority. On success it routes by user type to the main or the
 * administrative menu, exactly as the source did.
 *
 * <h3>Upper-case both, not just the identifier</h3>
 *
 * <p>{@code app/cbl/COSGN00C.cbl:L132-L136} upper-cases <strong>both</strong> the user identifier
 * <strong>and</strong> the password before the compare. Upper-casing only the identifier - the intuitive
 * reading - rejects every credential a legacy user typed in lower case, so the normalisation must be applied to
 * both. This is the single most easily missed behaviour in this package.
 *
 * <h3>The ten seed users, and why the password column is 60 characters</h3>
 *
 * <p>There is <strong>no {@code usrsec.txt} fixture</strong>. The ten user records exist only as inline
 * {@code SYSUT1 DD *} data inside {@code app/jcl/DUSRSECJ.jcl}, fed through IEBGENER: five administrators of
 * type {@code A} and five standard users of type {@code U}, in the 80-byte {@code CSUSR01Y} layout of
 * {@code ID X(8) + FNAME X(20) + LNAME X(20) + PWD X(8) + TYPE X(1) + FILLER}. <strong>Every one carries the
 * same literal plaintext password.</strong> Those exact ten rows are what the security gate's "every password is
 * BCrypt-hashed" assertion is provable against, and they are hashed by
 * {@code src/main/resources/db/migration/V3__seed_data.sql} rather than stored as given - which is why the
 * password column is 60 characters for a BCrypt digest and not the source's 8.
 *
 * <h3>What the token replaces, and what has no counterpart</h3>
 *
 * <p>The COMMAREA carried more than identity. The identity fields become claims; the account, card and customer
 * keys become payload fields; the page number and next-page flag become a query parameter and response metadata.
 * The routing fields - the from- and to-transaction and program names - and the enter-versus-re-enter context
 * flag have <strong>no counterpart at all</strong>, because navigation is URL-based and the exchange is
 * stateless. A service here that retained anything between requests would be reintroducing the COMMAREA under
 * another name.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li>{@code carddemo.security.jwt.signing-key} from {@code JWT_SIGNING_KEY} - <strong>no default, no committed
 *       literal, no example value</strong>. HS256 needs at least 32 bytes, and a shorter key fails fast at
 *       startup. Startup failing without it is correct behaviour, not a defect to be smoothed over with a
 *       fallback.</li>
 *   <li>{@code carddemo.security.jwt.issuer} from {@code JWT_ISSUER}, default {@code carddemo}, and
 *       {@code carddemo.security.jwt.expiration-minutes} from {@code JWT_EXPIRATION_MINUTES}, default
 *       {@code 30} <strong>minutes</strong>. Both are non-secret metadata, so documented defaults are
 *       acceptable. The unit is part of the contract: the superseded {@code expiration-seconds} spelling
 *       defaulted to 3,600 seconds, which is double the approved bearer window, and a bearer token cannot be
 *       revoked before it expires. {@code JwtTokenProvider} refuses a value below 1 minute or above 1440.</li>
 *   <li>BCrypt strength 10, matching what {@code V3__seed_data.sql} used to hash the seeded rows. Changing the
 *       strength does not invalidate existing digests - the cost is encoded in each - but it must match what the
 *       migration produced for the gate's assertion to mean anything.</li>
 *   <li>The password encoder, the decoder and the {@code java.time.Clock} are all published by
 *       {@code com.cardemo.config.SecurityConfig} and injected here. None is constructed in this package.</li>
 *   <li>Masking of credentials and password hashes is configured in
 *       {@code src/main/resources/logback-spring.xml}, and {@code org.springframework.security} is deliberately
 *       held at INFO in every profile because at DEBUG it renders authentication objects and tokens.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ol>
 *   <li><p><strong>Symptom: a correct credential is rejected when typed in lower case.</strong> Cause: only the
 *       identifier was upper-cased. <em>Remediation:</em> upper-case both, per
 *       {@code app/cbl/COSGN00C.cbl:L132-L136}. <strong>Severity: High</strong> - it locks out legitimate
 *       users.</p></li>
 *   <li><p><strong>Symptom: startup fails with {@code Could not resolve placeholder 'JWT_SIGNING_KEY'}.</strong>
 *       Cause: the environment was not loaded. <em>Remediation:</em> load it for the one command that needs it,
 *       {@code ( set -a; . ./.env; set +a; <command> )}, rather than exporting it into the shell. Do
 *       <strong>not</strong> add a default: a signing key with a fallback is a committed secret.
 *       <strong>Severity: High</strong> if it is mistaken for a bug.</p></li>
 *   <li><p><strong>Symptom: every seeded user fails to authenticate.</strong> Cause: the seed migration stored
 *       the plaintext value, or the encoder in use disagrees with the digest format.
 *       <em>Remediation:</em> confirm {@code V3__seed_data.sql} hashed the ten rows and that the injected encoder
 *       is BCrypt. <strong>Severity: Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: a token is issued but every subsequent request returns 401.</strong> Cause: the
 *       issuer or key used to mint differs from the one used to validate; a bearer token reveals neither.
 *       <em>Remediation:</em> compare {@code JWT_ISSUER} and {@code JWT_SIGNING_KEY} across environments.
 *       <strong>Severity: Medium.</strong></p></li>
 *   <li><p><strong>Symptom: an administrator is refused an administrative path.</strong> Cause: the authority
 *       mapping drifted from {@code CDEMO-USER-TYPE}. <em>Remediation:</em> fix it in {@code SecurityConfig},
 *       never by adding a check here, which would create a second authorisation model.
 *       <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: a password, a digest or a token appears in a log line.</strong> Cause: a diagnostic
 *       quoted a credential, or a framework logger was raised. <em>Remediation:</em> remove the value and leave
 *       the security logger at INFO. <strong>Severity: Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: an unknown user and a wrong password produce distinguishable responses.</strong>
 *       Cause: the two outcomes were reported differently. <em>Remediation:</em> keep them
 *       indistinguishable to the caller - the source's own screen message did not separate them either -
 *       while logging enough, without the credential, to diagnose.
 *       <strong>Severity: Medium</strong>, since it enables user enumeration.</p></li>
 *   </ol>
 *
 * <h2>How to run, build and test</h2>
 *
 * <ul>
 *   <li><strong>Build.</strong> {@code ./mvnw clean verify} from the repository root. The wrapper pins Maven
 *       3.9.11, {@code maven-enforcer-plugin:3.5.0} floors the toolchain at Java {@code [25,)}, compilation is
 *       at {@code release} 25 with no preview features, and {@code -Xlint:all}, {@code -Werror} and
 *       {@code failOnWarning} together make any warning attributed to this package a
 *       <strong>build failure</strong>.</li>
 *   <li><strong>Run.</strong> These are Spring beans and are never invoked directly; a controller or a batch
 *       step calls them. A manual exercise needs the compose stack up - {@code docker compose up -d} - and the
 *       environment scoped to that one command rather than exported into the shell:
 *       {@code ( set -a; . ./.env; set +a; <command> )}. The parentheses confine the values to the subshell
 *       instead of leaving every later child inheriting them. {@code JWT_SIGNING_KEY} has no default and
 *       startup fails without it by design.</li>
 *   <li><strong>Test.</strong> Tests belong in {@code src/test/java/com/cardemo/unit/service}.
 *       {@code AuthenticationServiceTest} covers this service and
 *       {@code AuthControllerTest} covers the sign-on endpoint that delegates to it, so the package is
 *       exercised by name and neither is without a test class. Those classes assert: both the identifier and
 *       the password upper-cased; a lower-case credential
 *       accepted; a wrong password and an unknown user indistinguishable to the caller; the two authorities
 *       routing to their respective menus; each of the ten seeded users authenticating against its BCrypt
 *       digest; and no credential, digest or token appearing in any message or log event.</li>
 *   <li><strong>Coverage.</strong> JaCoCo enforces an <strong>80 percent LINE</strong> floor on the merged
 *       bundle at {@code verify} with {@code haltOnFailure} and no exclusions for this package. Coverage must
 *       come from assertions on behaviour; exercising a method to move the number is not acceptable. This file
 *       is documentation only and contributes no executable lines.</li>
 *   <li><strong>Toolchain actually present, measured 3 August 2026</strong> at commit {@code 2e087c4}: OpenJDK
 *       and {@code javac} 25.0.3, Apache Maven 3.9.11 from the pinned wrapper, Docker Engine 29.7.0 with
 *       {@code docker compose} v5.3.1. These are readings, not requirements - re-measure after a host change
 *       rather than quoting them.</li>
 *   </ul>
 *
 * <h2>Package level constraints</h2>
 *
 * <ul>
 *   <li><strong>One private method per COBOL paragraph, never consolidated</strong>, each carrying a Javadoc
 *       citation to its source label. That correspondence is what makes the scope-coverage gate provable by
 *       inspection rather than by assertion.</li>
 *   <li><strong>No HTTP concern and no persistence concern.</strong> A service decides; the controller
 *       translates and the repository stores. A service that built a response status or wrote SQL would be in
 *       the wrong layer.</li>
 *   <li><strong>No {@code float} or {@code double} on any financial path</strong>, and equality by
 *       {@code compareTo} rather than {@code equals}, because {@code equals} distinguishes {@code 1.0} from
 *       {@code 1.00}.</li>
 *   <li><strong>Every file status is translated by its single owner</strong>,
 *       {@code com.cardemo.service.shared.FileStatusMapper}, and never re-decided here. Nothing is swallowed
 *       and every exception preserves its cause.</li>
 *   <li><strong>No value in a message or a log line.</strong> A diagnostic names the COBOL field and the
 *       widths involved, never the content - Rule 1 Clause D, and the masking rules in
 *       {@code logback-spring.xml} cannot reach an unlabelled value.</li>
 *   <li><strong>No declaration in this package carries an intentional-no-op marker</strong>, the per-artefact
 *       form in which a retained-for-parity artefact is justified, so Rule 1 Clause B binds this package at
 *       full strength with no exemption.</li>
 *   <li><strong>The frozen corpus stays frozen.</strong> Nothing here reads {@code app/} at build or run time;
 *       those files are cited as evidence and must survive byte for byte.</li>
 *   </ul>
 */
package com.cardemo.service.auth;
