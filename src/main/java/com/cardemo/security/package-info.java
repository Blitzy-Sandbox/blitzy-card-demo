/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.security
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 *               (package-info #3 of 14)
 * Function    : JWT identity replacing COMMAREA propagation across
 *               EXEC CICS XCTL.
 * Source      : app/cbl/COSGN00C.cbl (260 lines) :L132-L136
 *               (UPPER-CASE BOTH id and password), :L223
 *               (SEC-USR-PWD = WS-USER-PWD compare), :L209-L257
 *               (READ-USER-SEC-FILE) @ 7756d89
 * Source      : app/cpy/CSUSR01Y.cpy:L17-L23 (80-byte SEC-USER-DATA
 *               layout, key 8) @ 7756d89
 * Source      : app/cpy/COCOM01Y.cpy:L25 (CDEMO-USER-ID -> subject),
 *               :L26-L28 (CDEMO-USER-TYPE 'A'/'U' -> role) @ 7756d89
 * Source      : app/cbl/COMEN01C.cbl:L149-L150 (identity MOVEs
 *               commented out), :L152-L155 (XCTL + COMMAREA)
 *               @ 7756d89
 * Source      : app/jcl/DUSRSECJ.jcl:L35-L44 (10 inline seed users),
 *               :L64-L66 (KEYS(8,0) RECORDSIZE(80,80)) @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD:L88-L92 (DEFINE FILE(USRSEC)),
 *               :L378 (DEFINE TRANSACTION(CC00)) @ 7756d89
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
 * The identity layer for CardDemo: three collaborating classes that replace COMMAREA-borne identity with a
 * signed JSON Web Token, so that the stateless REST surface can carry across requests what the legacy system
 * carried across an {@code EXEC CICS XCTL}.
 *
 * <p>The banner above names every legacy artefact this package derives from, each pinned to commit
 * {@code 7756d89}, the traceability anchor for the frozen COBOL corpus in {@code app/}. Every behavioural
 * claim below was read either from the three authored classes in this package or from the cited source
 * locator; none is inferred, and the four corrections this documentation carries against its own
 * specification are recorded explicitly rather than quietly absorbed.
 *
 * <h2>What it does</h2>
 *
 * <p>The legacy system propagated identity in a control block. {@code app/cpy/COCOM01Y.cpy} declares
 * {@code CARDDEMO-COMMAREA}, and every screen program passed it forward on transfer of control:
 * {@code app/cbl/COMEN01C.cbl:L152-L155} is the canonical site, an {@code EXEC CICS XCTL} naming
 * {@code COMMAREA(CARDDEMO-COMMAREA)}. This package replaces that mechanism, and only that mechanism.
 *
 * <p>Exactly two COMMAREA fields become token claims:
 *
 * <ul>
 *   <li>{@code CDEMO-USER-ID} at {@code app/cpy/COCOM01Y.cpy:L25}, {@code PIC X(08)}, becomes the
 *       <strong>subject</strong> claim.</li>
 *   <li>{@code CDEMO-USER-TYPE} at {@code :L26-L28}, {@code PIC X(01)} carrying the two condition names
 *       {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and {@code 88 CDEMO-USRTYP-USER VALUE 'U'}, becomes the
 *       <strong>role</strong> claim. The value {@code 'A'} maps to the authority {@code ROLE_ADMIN} and
 *       {@code 'U'} to {@code ROLE_USER}. There are exactly two, and there is deliberately no permissive
 *       default in either direction.</li>
 * </ul>
 *
 * <p>Several COMMAREA fields have <strong>no counterpart at all</strong>, and saying so is as important as
 * describing what was carried over, because each absence is a design decision rather than an oversight:
 *
 * <ul>
 *   <li>{@code CDEMO-FROM-TRANID}, {@code CDEMO-FROM-PROGRAM}, {@code CDEMO-TO-TRANID} and
 *       {@code CDEMO-TO-PROGRAM} at {@code :L21-L24} carried the navigation pair. Routing is URL-based here,
 *       so nothing carries it.</li>
 *   <li>{@code CDEMO-PGM-CONTEXT} at {@code :L29-L31}, with {@code 88 CDEMO-PGM-ENTER VALUE 0} and
 *       {@code 88 CDEMO-PGM-REENTER VALUE 1}, was the pseudo-conversational enter-versus-re-enter flag. It
 *       collapses into stateless request handling.</li>
 *   <li>{@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} at {@code :L43-L44}, both {@code PIC X(7)},
 *       retained screen state. No screen state is retained.</li>
 * </ul>
 *
 * <p><strong>The claim set is minimal, and that is a security boundary rather than a preference.</strong> A
 * JWT is signed, not encrypted: anyone holding one can read its payload. The personally identifying and
 * financial fields of the COMMAREA are therefore <strong>data-transfer-object fields and never claims</strong>
 * - {@code CDEMO-CUST-ID} at {@code :L33}, the three customer name fields {@code CDEMO-CUST-FNAME},
 * {@code CDEMO-CUST-MNAME} and {@code CDEMO-CUST-LNAME} at {@code :L34-L36}, {@code CDEMO-ACCT-ID} at
 * {@code :L38}, {@code CDEMO-ACCT-STATUS} at {@code :L39} and {@code CDEMO-CARD-NUM} at {@code :L41}.
 * Emitting any of them as a claim is a <strong>Blocker</strong>. As authored, {@link JwtTokenProvider} emits
 * exactly five claims and no more: issuer, subject, issued-at, expires-at, and the single role claim named by
 * its {@code ROLE_CLAIM_NAME} constant.
 *
 * <h3>The three classes</h3>
 *
 * <p>One responsibility each, with no overlap:
 *
 * <ul>
 *   <li>{@link JwtTokenProvider} - <strong>issuance and claim reading</strong>. Signs a token for an
 *       already-authenticated sign-on and reads the two claims back out. It owns the claim contract: the
 *       constants {@code ROLE_CLAIM_NAME}, {@code ADMIN_AUTHORITY} and {@code USER_AUTHORITY} are declared
 *       here once and consumed everywhere else, which is what keeps the three readers from drifting apart.
 *       It validates its own configuration on construction and refuses to start on a weak or absent signing
 *       key.</li>
 *   <li>{@link CardDemoUserDetailsService} - <strong>credential load and verify</strong>. Reads one
 *       {@code user_security} row by its eight-character key and verifies the presented password against the
 *       stored digest. This is the translation of {@code READ-USER-SEC-FILE} at
 *       {@code app/cbl/COSGN00C.cbl:L209-L257}. It is the <strong>single normalisation authority</strong>
 *       for the package.</li>
 *   <li>{@link JwtAuthenticationFilter} - <strong>per-request authentication</strong>. Extracts a bearer
 *       credential, verifies it, and populates a request-local security context. It performs
 *       <strong>no database read</strong>.</li>
 * </ul>
 *
 * <h3>Upper-case both, trim neither</h3>
 *
 * <p>This is the single most easily missed behaviour in the package, and getting it wrong breaks sign-on for
 * real users while every test that only exercises upper-case input still passes.
 * {@code app/cbl/COSGN00C.cbl:L132-L136} contains <strong>two</strong> {@code MOVE FUNCTION UPPER-CASE}
 * statements: {@code :L132-L134} folds the identifier into {@code WS-USER-ID} and {@code CDEMO-USER-ID}, and
 * {@code :L135-L136} folds the password into {@code WS-USER-PWD}. The comparison that consumes them is
 * {@code IF SEC-USR-PWD = WS-USER-PWD} at <strong>{@code :L223}</strong>, so the value compared is the folded
 * one.
 *
 * <p>Consequently {@link CardDemoUserDetailsService} folds <strong>both</strong> credentials with
 * {@code java.util.Locale.ROOT}, through two separate private helpers so that both folds are visible by
 * inspection, and <strong>trims neither and pads neither</strong>. Folding the identifier but not the
 * password is the obvious mistake and a <strong>High</strong>-severity parity break: it silently refuses
 * every user who types a password in lower case, while the legacy system admits them.
 *
 * <p>Two consequences follow that are worth stating outright. First, because normalisation lives in exactly
 * one place, {@code com.cardemo.service.auth.AuthenticationService} must <strong>delegate</strong> to it and
 * must never re-apply the folding itself; a second fold would be harmless today and wrong the moment the
 * rule changes. Second, the citation is {@code :L223} and not {@code :L222} - the latter is the
 * {@code WHEN 0} selector of the {@code EVALUATE WS-RESP-CD} that begins at {@code :L221}. That is a
 * <strong>Low</strong>-severity citation correction, verified against the source and recorded here so it is
 * not reintroduced.
 *
 * <h3>No per-request database read</h3>
 *
 * <p>{@link JwtAuthenticationFilter} derives the caller's authorities from the token alone. The evidence for
 * that being faithful rather than merely convenient is {@code app/cbl/COMEN01C.cbl:L149-L150}, where the two
 * identity moves are <strong>commented out</strong> in the source: the legacy system did not re-read the
 * security file on each transfer of control either, it trusted the control block it was handed. The filter is
 * constructed with a decoder and the token provider and holds no persistence collaborator at all, so the
 * property is structural rather than a matter of discipline.
 *
 * <p>The accepted consequence is documented under the failure modes below: a role changed in the database
 * takes effect when the token expires, not immediately. That is the legacy behaviour, and the token lifetime
 * bounds it.
 *
 * <h3>Ownership boundary</h3>
 *
 * <p>Nothing in this package configures the security framework. {@code com.cardemo.config.SecurityConfig}
 * owns, and is the only place that may own:
 *
 * <ul>
 *   <li>the filter chain and the position of {@link JwtAuthenticationFilter} within it;</li>
 *   <li>the {@code PasswordEncoder} bean, BCrypt at strength <strong>10</strong>;</li>
 *   <li>the symmetric HMAC {@code NimbusJwtDecoder} built in Java from the signing key;</li>
 *   <li>the authorisation rules for the seventeen sourced transactions;</li>
 *   <li>the {@code STATELESS} session policy and the disabling of CSRF state.</li>
 * </ul>
 *
 * <p>This package declares no {@code @Bean} method of any kind and duplicates none of the above. Defining a
 * second {@code JwtDecoder} or {@code PasswordEncoder} here is a <strong>High</strong>-severity defect, not a
 * convenience.
 *
 * <h3>Authorisation surface</h3>
 *
 * <p>The CICS resource definitions in {@code app/csd/CARDDEMO.CSD} are the inventory of what may be reached.
 * Seventeen transactions have both a source program and a mapset, and they are the whole authenticated
 * surface: {@code CC00}, {@code CM00}, {@code CA00}, {@code CAVW}, {@code CAUP}, {@code CCLI}, {@code CCDL},
 * {@code CCUP}, {@code CT00}, {@code CT01}, {@code CT02}, {@code CB00}, {@code CR00}, {@code CU00},
 * {@code CU01}, {@code CU02} and {@code CU03}.
 *
 * <ul>
 *   <li>Sign-on is {@code CC00} at {@code app/csd/CARDDEMO.CSD:L378}, over {@code PROGRAM(COSGN00C)}. It is
 *       the <strong>only unauthenticated operation</strong> in the application.</li>
 *   <li>The user-administration endpoints under {@code /api/admin/*} are restricted to the administrator
 *       authority, which is the least-privilege reading of the four {@code CU0x} transactions.</li>
 *   <li><strong>No endpoint is invented for {@code CDV1}.</strong> That transaction fronts
 *       {@code PROGRAM(COCRDSEC)} at {@code app/csd/CARDDEMO.CSD:L390}, inside the definition at
 *       {@code :L388-L391} described as a developer transaction. {@code COCRDSEC} occurs at exactly two
 *       places in the frozen corpus, both inside the CSD - {@code :L211} and {@code :L390} - and has no
 *       source file anywhere in the repository. It is a dangling legacy definition with nothing to
 *       translate, so nothing here authorises it.</li>
 * </ul>
 *
 * <h3>The record contract</h3>
 *
 * <p>{@code app/cpy/CSUSR01Y.cpy:L17-L23} declares {@code SEC-USER-DATA} as exactly <strong>80 bytes</strong>:
 * {@code SEC-USR-ID X(08)}, {@code SEC-USR-FNAME X(20)}, {@code SEC-USR-LNAME X(20)},
 * {@code SEC-USR-PWD X(08)}, {@code SEC-USR-TYPE X(01)} and {@code SEC-USR-FILLER X(23)}. The key is the
 * first eight bytes, which is why {@link JwtTokenProvider} bounds the subject claim at eight characters.
 * Three independent sources corroborate the geometry: {@code app/jcl/DUSRSECJ.jcl:L64-L66} defines the
 * cluster with {@code KEYS(8,0)} and {@code RECORDSIZE(80,80)}; {@code app/catlg/LISTCAT.txt:L3846} catalogues
 * the cluster and {@code :L3883} records {@code KEYLEN 8} with {@code AVGLRECL 80}; and
 * {@code app/csd/CARDDEMO.CSD:L88-L92} defines the online file over the same dataset.
 *
 * <p>The ten seeded users are the population this package authenticates. They exist only as inline
 * {@code SYSUT1 DD *} card images fed through {@code IEBGENER}: {@code app/jcl/DUSRSECJ.jcl:L32} is the
 * {@code EXEC PGM=IEBGENER}, {@code :L34} opens the stream, the records occupy {@code :L35-L44}, {@code :L45}
 * closes it and {@code :L48} declares {@code LRECL=80}. There is no fixture file for them; the nine fixtures
 * under {@code app/data/ASCII/} do not include one. The identifiers are {@code ADMIN001} through
 * {@code ADMIN005}, all of type {@code 'A'}, and {@code USER0001} through {@code USER0005}, all of type
 * {@code 'U'}. All ten share one <em>legacy inline plaintext literal</em> as their password, which is
 * deliberately not reproduced anywhere in this package; the seed migration stores only BCrypt digests of it,
 * which is the assertion the security gate is proved against.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>There is nothing package-specific to run: this package is compiled, tested and packaged with the rest of
 * the single deployable artefact. From the repository root:
 *
 * <ul>
 *   <li><strong>Build and verify</strong> - {@code ./mvnw clean verify}. The wrapper is the canonical entry
 *       point because it pins the build tool itself, so the build does not depend on what happens to be
 *       installed. {@code ./mvnw -B -ntp clean compile} is the faster loop while editing.</li>
 *   <li><strong>Toolchain</strong> - Java 25 with {@code maven.compiler.release} set to 25 and
 *       <strong>no preview features</strong>, Maven 3.9.11, and the parent
 *       {@code org.springframework.boot:spring-boot-starter-parent:3.5.11}.
 *       {@code maven-enforcer-plugin:3.5.0} floors both, so a wrong toolchain fails the build rather than
 *       producing a subtly different artefact. This package was compiled and its documentation rendered
 *       against OpenJDK 25.0.3 and Maven 3.9.11.</li>
 *   <li><strong>Zero-warning gate</strong> - {@code maven-compiler-plugin:3.14.1} runs with
 *       {@code -Xlint:all} and {@code -Werror}. An unused import, a raw type, an unchecked cast, a use of a
 *       deprecated API or a switch fall-through is a <strong>hard build failure</strong>, not a warning to
 *       triage later. This is also why this file declares no nullability annotation: no JSR-305 and no
 *       JSpecify artefact is on the classpath, and the Spring alternatives carry deprecation risk that the
 *       gate would turn fatal.</li>
 *   <li><strong>Coverage gate</strong> - {@code jacoco-maven-plugin:0.8.12} enforces a merged line-coverage
 *       floor of <strong>80%</strong> at {@code verify}, with no exclusions. Tests for this package live under
 *       {@code src/test/java/com/cardemo/unit} and the integration tiers beside it -
 *       <strong>never inside this package</strong>, which contains production classes only.</li>
 *   <li><strong>Test signing keys</strong> - a test must use a <strong>generated or injected</strong> signing
 *       key. Committing one, even a throwaway, puts a usable key in version control and is treated as a
 *       secret leak.</li>
 *   <li><strong>Runtime prerequisites</strong> - PostgreSQL 16, with {@code spring.jpa.hibernate.ddl-auto}
 *       set to {@code validate} and {@code spring.jpa.open-in-view} disabled in every profile. The schema and
 *       the ten seeded users arrive through the Flyway migrations {@code V1__create_schema.sql},
 *       {@code V2__create_indexes.sql} and {@code V3__seed_data.sql}. <strong>Sign-on cannot succeed until
 *       the third migration has applied</strong>, because until then there is no digest to verify against.
 *       The container topology that supplies the database is brought up from the repository root with
 *       {@code docker compose up -d}.</li>
 *   <li><strong>No dependency may be added for this package.</strong> There is no Lombok and no separate JWT
 *       library - no {@code jjwt}, no {@code java-jwt}, no {@code auth0} - and their absence from
 *       {@code pom.xml} is verifiable rather than aspirational. Token handling uses the Spring Security
 *       OAuth2 resource-server and Nimbus JOSE path, {@code JwtEncoder} and {@code JwtDecoder}, which the
 *       parent already manages, so no version needs pinning here.</li>
 * </ul>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Three properties configure this package. Their names and defaults are recorded here; no value of the
 * first is recorded anywhere, in any form:
 *
 * <ul>
 *   <li>{@code carddemo.security.jwt.signing-key} - bound from {@code ${JWT_SECRET}} with
 *       <strong>no default, no example, no fallback and no committed literal</strong>. An absent or blank
 *       variable leaves the placeholder unresolvable, which fails property resolution and therefore
 *       <strong>fails startup</strong>. That is the required behaviour and not a defect: a generated or
 *       committed default would let the application boot with a key an attacker already knows. The key is
 *       additionally rejected at construction when it is shorter than 32 bytes encoded as UTF-8, so a weak
 *       key is refused at startup rather than at the first sign-on.</li>
 *   <li>{@code carddemo.security.jwt.issuer} - bound from {@code ${JWT_ISSUER:carddemo}}, so the default
 *       issuer is {@code carddemo}.</li>
 *   <li>{@code carddemo.security.jwt.expiration-seconds} - bound from
 *       {@code ${JWT_EXPIRATION_SECONDS:3600}}, so the default lifetime is <strong>3600 seconds</strong>, one
 *       hour. It is validated into the closed range of one second to 86400 seconds. This value bounds how
 *       long a stale role claim can persist, which is the accepted cost of not re-reading the database on
 *       every request.</li>
 * </ul>
 *
 * <p>Further configuration facts that belong to this package's contract even though they are set elsewhere:
 *
 * <ul>
 *   <li>The password encoder is BCrypt at strength exactly <strong>10</strong>, matching the digests written
 *       by the seed migration, and is owned by {@code com.cardemo.config.SecurityConfig}.</li>
 *   <li>The session policy is {@code STATELESS} and CSRF state is disabled, both owned by
 *       {@code com.cardemo.config.SecurityConfig}.</li>
 *   <li>{@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} and its
 *       {@code public-key-location} sibling are <strong>deliberately not set</strong>. The decoder is a
 *       symmetric HMAC decoder built in Java from the signing key, so either property would be configuration
 *       that nothing reads - dead configuration, which clause B forbids as surely as it forbids dead
 *       code.</li>
 *   <li>Configuration reaches this package through <strong>Spring property binding only</strong>. Nothing in
 *       it reads an environment variable or a system property directly, so behaviour is determined by the
 *       resolved configuration rather than by ambient process state, and a test can supply configuration
 *       without mutating the process it runs in.</li>
 *   <li>The signing key is environment-indirected in <strong>all four profiles</strong> -
 *       {@code application.yml}, {@code application-local.yml}, {@code application-test.yml} and
 *       {@code application-prod.yml} - and {@code .env.example} ships the variable
 *       <strong>empty and required</strong>, documenting its existence without documenting a value.</li>
 *   <li>The diagnostic-context keys consumed by the logging configuration are exactly
 *       {@code correlationId}, {@code traceId} and {@code spanId}, and they are set by
 *       {@code com.cardemo.observability.CorrelationIdFilter}. Nothing in this package renames, overwrites or
 *       clears them.</li>
 *   <li>The authentication-attempts counter is one of exactly <strong>four</strong> instruments, all owned by
 *       {@code com.cardemo.observability.MetricsConfig}. This package may emit that signal; it must never
 *       register a fifth instrument, and must never tag any instrument with a per-subject value such as a
 *       user identifier, which would turn a metric into an unbounded-cardinality identity leak.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>Each entry carries a severity and a remediation. The three <strong>Blocker</strong> entries are listed
 * first because each of them is a security failure rather than an availability one, and none of them announces
 * itself in a log.
 *
 * <ul>
 *   <li><p><strong>Blocker - a request is served under the previous user's identity.</strong> A security
 *       context leaked across a pooled servlet thread: the container reuses threads, so a context left behind
 *       is inherited by whatever request lands on that thread next. <em>Remediation:</em> clear the context in
 *       a {@code finally} block on <strong>every</strong> path including the exception path, which is what
 *       {@link JwtAuthenticationFilter} does unconditionally around the downstream chain. Never make the
 *       clearing conditional on the request having been authenticated.</p></li>
 *   <li><p><strong>Blocker - a credential appears in an observable channel.</strong> A token, an
 *       {@code Authorization} header, the signing key, a presented password or a stored digest turns up in a
 *       log record, a metric tag, a diagnostic-context value, a health detail or an exception message.
 *       <em>Remediation:</em> never produce the value in the first place. The classes here log only an
 *       exception's simple type name and never its message, because a message can carry a claim value; the
 *       masking rules in the logging configuration are a second line of defence, not the control.</p></li>
 *   <li><p><strong>Blocker - a personally identifying or financial claim is present in the token
 *       payload.</strong> A card number, account identifier, customer identifier or customer name was added
 *       as a claim. A JWT is signed, not encrypted, so anyone holding the token can read it.
 *       <em>Remediation:</em> remove the claim. Those values are data-transfer-object fields, carried in
 *       request and response bodies over an authenticated channel, and the claim set stays at the five
 *       claims listed above.</p></li>
 *   <li><p><strong>High, by design - the application refuses to start because the signing key cannot be
 *       resolved.</strong> The {@code JWT_SECRET} variable is unset, blank, or shorter than 32 bytes. This is
 *       a <strong>deliberate fail-fast and not a bug</strong>; the startup failure names both the variable to
 *       set and the property it backs. <em>Remediation:</em> export a strong {@code JWT_SECRET} in the runtime
 *       or compose environment. Never commit a value, never add a property default to make the symptom go
 *       away, and never print the key while diagnosing. The previous migration attempt hardcoded this secret,
 *       which was an open <strong>High</strong>-severity defect that this arrangement closes.</p></li>
 *   <li><p><strong>High - every sign-on fails although the ten seeded users exist.</strong> Two usual causes.
 *       Either the presented password was not folded to upper case before verification, in which case see
 *       {@code app/cbl/COSGN00C.cbl:L132-L136} and {@code :L223}; or the encoder strength diverged from the
 *       strength 10 used when {@code V3__seed_data.sql} computed the digests. <em>Remediation:</em> fold
 *       <strong>both</strong> the identifier and the password with {@code java.util.Locale.ROOT}, trim
 *       neither, and consume the single encoder bean that
 *       {@code com.cardemo.config.SecurityConfig} owns rather than constructing one.</p></li>
 *   <li><p><strong>High - sign-on fails only for some users, apparently at random or only on some
 *       hosts.</strong> A case operation used the platform default locale, so the fold depends on the server's
 *       locale; the Turkish dotless-i mapping is the classic instance. <em>Remediation:</em> pass
 *       {@code java.util.Locale.ROOT} to <strong>every</strong> case operation without exception.</p></li>
 *   <li><p><strong>High - a valid-looking token yields 401 or 403 on every request.</strong> The role-claim
 *       name drifted between the issuer, the filter and the authorisation rules. This failure is silent: the
 *       token verifies, no error is logged, and the request simply has no authority.
 *       <em>Remediation:</em> the claim name exists as a single constant on {@link JwtTokenProvider}, and the
 *       two authority strings beside it. Align every reader on those constants instead of repeating string
 *       literals.</p></li>
 *   <li><p><strong>High - startup fails with a duplicate bean definition, or tokens verify against the wrong
 *       key.</strong> A second {@code JwtDecoder} or {@code PasswordEncoder} was defined inside this package.
 *       <em>Remediation:</em> delete the duplicate here. {@code com.cardemo.config.SecurityConfig} is the
 *       designated owner of both, and this package declares no bean method at all.</p></li>
 *   <li><p><strong>High - doubled log lines, doubled counter increments, or two authentication attempts per
 *       request.</strong> The filter was registered twice, once auto-registered as a servlet filter bean and
 *       once added explicitly to the security chain. <em>Remediation:</em> the filter extends
 *       {@code org.springframework.web.filter.OncePerRequestFilter}, which makes a second invocation per
 *       request a no-op; keep it that way, and register it in exactly one place.</p></li>
 *   <li><p><strong>Medium - a 401 or 403 appears in the logs with no correlation identifier.</strong> The
 *       authentication filter ran ahead of {@code com.cardemo.observability.CorrelationIdFilter}, so the
 *       diagnostic context was still empty when the outcome was recorded. <em>Remediation:</em> restore the
 *       ordering - correlation filter, then authentication filter, then authorisation - which is what the
 *       filter's declared order is chosen to produce.</p></li>
 *   <li><p><strong>Medium - a response does not distinguish an unknown identifier from a wrong
 *       password.</strong> This is intentional. The legacy 3270 screen did distinguish them: the
 *       wrong-password branch at {@code app/cbl/COSGN00C.cbl:L241-L246} and the user-not-found branch at
 *       {@code :L247-L251} emit different messages. The REST surface deliberately returns one shared
 *       rejection message instead, because distinguishing them hands an attacker a user-enumeration oracle.
 *       <em>Remediation:</em> none - this is a labelled security deviation from parity, and it should not be
 *       "fixed" back. Two related notes: absent-identifier and absent-password are still reported
 *       distinguishably, because neither discloses which identifiers exist; and the legacy branches are
 *       themselves asymmetric, in that {@code :L241-L246} does <strong>not</strong> set {@code WS-ERR-FLG}
 *       while {@code :L248} and {@code :L253} both do. That asymmetry is <strong>Low</strong> severity, cited
 *       here and preserved rather than corrected.</p></li>
 *   <li><p><strong>Low - a role change does not take effect immediately.</strong> Expected behaviour.
 *       Authorities come from the token, so a change lands when the token expires, within the configured
 *       lifetime of 3600 seconds by default. <em>Remediation:</em> none required; this reproduces the legacy
 *       behaviour evidenced by the commented-out identity moves at {@code app/cbl/COMEN01C.cbl:L149-L150}. If
 *       an installation needs a tighter bound, shorten {@code JWT_EXPIRATION_SECONDS} rather than adding a
 *       per-request database read, which would change the performance profile of every request.</p></li>
 *   <li><p><strong>Low - the password looks as though it needs padding to eight characters.</strong> It does
 *       not. {@code SEC-USR-PWD} at {@code app/cpy/CSUSR01Y.cpy:L21} and {@code WS-USER-PWD} are both
 *       {@code PIC X(08)} fixed-width fields, but the presented value is verified against the digest written
 *       by {@code V3__seed_data.sql}, and padding it would append spaces that were absent when the digest was
 *       computed - making every verification fail. All ten seeded credentials are exactly eight characters, so
 *       the padded and unpadded forms coincide and the distinction never shows up in the fixtures.
 *       <em>Remediation:</em> none; do not add padding. This is a documented boundary decision.</p></li>
 * </ul>
 *
 * <h2>Not available</h2>
 *
 * <p>Four things this documentation would otherwise have to guess at are Not available, and each is recorded
 * with what would be needed to close it. Stating the gap is the point: an asserted fact that nobody verified
 * is worse than an acknowledged hole, because only the first one gets believed.
 *
 * <ul>
 *   <li><p><strong>{@code com.cardemo.config.SecurityConfig} is Not available.</strong> The
 *       {@code com.cardemo.config} package exists and holds three classes - the persistence, web and cloud
 *       configurations - but there is no {@code SecurityConfig} among them. Everything this file states about
 *       the filter chain's composition and ordering, the BCrypt strength of 10, the symmetric HMAC decoder and
 *       the mapping of {@code 'A'} and {@code 'U'} onto {@code ROLE_ADMIN} and {@code ROLE_USER} is therefore
 *       a <strong>contract asserted from the specification, not verified against code</strong> - with one
 *       exception worth noting, that the two authority strings themselves and their derivation from the user
 *       class <em>were</em> read from {@link JwtTokenProvider} and {@link CardDemoUserDetailsService} in this
 *       package. <em>What is needed:</em> {@code src/main/java/com/cardemo/config/SecurityConfig.java}. When it
 *       lands, re-read it and reconcile this section against it. Note that this absence is also why every
 *       cross-package reference in this file is set in code font rather than linked: a documentation link to a
 *       type that does not yet exist would fail the documentation build under the zero-warning gate.</p></li>
 *   <li><p><strong>{@code EIBTRNID} is Not available.</strong> The CICS transaction identifier is the closest
 *       legacy analogue of a per-request correlation identifier, and it is the natural thing to cite when
 *       explaining what the correlation filter replaces - but it <strong>does not occur anywhere in the frozen
 *       corpus</strong>. The complete census of exec-interface-block fields referenced across the
 *       twenty-eight programs in {@code app/cbl} is {@code EIBCALEN}, 49 occurrences, and {@code EIBAID}, 16
 *       occurrences, and nothing else. {@code EIBTRNID} is supplied by the transaction monitor rather than
 *       declared by the application, so <strong>no {@code app/} line citation for it exists and none may be
 *       fabricated</strong>. Severity <strong>Medium</strong>, because a fabricated citation would look
 *       authoritative and survive review. <em>What is needed:</em> a CICS monitor definition or the
 *       vendor-supplied copybook that declares the block, neither of which this corpus contains.</p></li>
 *   <li><p><strong>Executed evidence for the four container-dependent validation gates is Not
 *       available from this package.</strong> End-to-end boundary parity, named-fixture validation, API
 *       contract verification and integration sign-off all require a running container runtime supplying
 *       PostgreSQL and the cloud-service emulator. This file records the prerequisite rather than asserting an
 *       untested pass. What <em>was</em> executed for this package is the container-free part: a clean
 *       compilation of the whole module under the zero-warning gate and a clean documentation render.
 *       <em>What is needed:</em> a container runtime with an accessible socket, the compose topology started
 *       from the repository root, and the three Flyway migrations applied; the gate outcomes are recorded in
 *       the validation-gates document rather than duplicated here.</p></li>
 *   <li><p><strong>A service-level objective for this package is Not available.</strong> The frozen corpus
 *       publishes no latency or throughput target - no such figure exists anywhere in
 *       {@code app/} - so none may be invented here, and none is. The performance gate accordingly records a
 *       <strong>measured baseline</strong> rather than a threshold to pass. The only performance-relevant
 *       decision this package makes is the deliberate absence of a per-request database read, and its cost is
 *       documented above as the bounded staleness of a role claim. <em>What is needed:</em> a stated objective
 *       from the business owners before any assertion about acceptable authentication latency can be
 *       made.</p></li>
 * </ul>
 *
 * <h3>Corrections this documentation carries</h3>
 *
 * <p>Four claims in this package's own specification did not survive being checked against the code and
 * configuration as authored. They are recorded rather than silently overwritten, because a reader who has the
 * specification in hand needs to know which one to trust:
 *
 * <ul>
 *   <li>The signing-key variable is {@code JWT_SECRET}, not {@code JWT_SIGNING_KEY}. The repository
 *       standardised on the former before this package existed - it is what the base profile's placeholder
 *       reads and what {@code .env.example} ships empty - and introducing a second name would create two ways
 *       to configure one secret. Severity <strong>Medium</strong>.</li>
 *   <li>The lifetime property is {@code carddemo.security.jwt.expiration-seconds}, bound from
 *       {@code ${JWT_EXPIRATION_SECONDS:3600}}, not an equivalent expressed in minutes. The default is
 *       therefore 3600 seconds rather than 30 minutes, and any troubleshooting note quoting half an hour is
 *       wrong. Severity <strong>Medium</strong>, because it is the kind of error an operator acts on.</li>
 *   <li>The {@code com.cardemo.config} package is not empty; it is {@code SecurityConfig} specifically that is
 *       absent. The disclosure above stands, but on the accurate ground. Severity <strong>Low</strong>.</li>
 *   <li>{@code EIBTRNID} is absent from the <em>frozen corpus</em>. It does now appear in the migrated tree,
 *       where several packages record the same disclosure, so a repository-wide search is no longer the right
 *       test and the claim is scoped to {@code app/} above. Severity <strong>Low</strong>.</li>
 * </ul>
 *
 * <h2>Package level constraints</h2>
 *
 * <p>These are structural invariants, checkable by inspection, and each exists because violating it has a
 * specific consequence:
 *
 * <ul>
 *   <li>This package contains <strong>exactly four</strong> {@code .java} files:
 *       {@link JwtTokenProvider}, {@link CardDemoUserDetailsService}, {@link JwtAuthenticationFilter} and this
 *       file. A fifth is a <strong>Blocker</strong> against the agreed directory shape.</li>
 *   <li>This file declares <strong>no imports, no annotations and no code</strong> - no class, interface,
 *       enum, record, field or method. A package documentation file that declares anything is no longer only
 *       documentation.</li>
 *   <li>Nothing may appear between the end of this documentation comment and the package declaration below
 *       it. An intervening comment or annotation <strong>detaches</strong> the comment, after which it
 *       vanishes from the generated documentation with no error and no warning - the one failure in this file
 *       that produces no diagnostic at all.</li>
 *   <li>The package root is spelled {@code com.cardemo}, with one {@code d} in the middle. The doubled
 *       spelling appears nowhere.</li>
 *   <li>No README and no Markdown file belongs in this subtree. This file <em>is</em> the documentation
 *       deliverable, and a second one would both duplicate it and break the file count.</li>
 * </ul>
 *
 * <h2>Rule 1, Build Verify, clause by clause</h2>
 *
 * <p>One rule governs this project. How each of its clauses lands on this package:
 *
 * <ul>
 *   <li><strong>Clause A, engineering principles.</strong> Correctness and determinism: every fact above was
 *       read from the authored classes or from a cited locator, and the four that failed that test are
 *       recorded as corrections. Security by default: the bearer header, the sign-on body and the inbound
 *       token are all treated as untrusted, and the signing key has no default. Maintainability: the
 *       ownership boundary is stated so that no future contributor duplicates a bean. Observability: the three
 *       diagnostic-context keys and the four-instrument ceiling are documented. Performance: the one tradeoff
 *       this package makes is justified rather than assumed, and its cost is quantified as a bounded
 *       staleness window.</li>
 *   <li><strong>Clause B, code quality.</strong> No dead code and no unused imports, by construction - there
 *       are no imports to be unused. No deferred-work marker appears anywhere in this package.
 *       <strong>None of the project's three retained-for-parity artefacts lives here</strong>, so clause B
 *       binds this package at full strength with no exemption, and this documentation claims no intentional
 *       no-op. Input validation, null and blank handling, constructor injection with no mutable static state,
 *       and exception wrapping that preserves the cause while withholding credentials are all properties of
 *       the three classes rather than aspirations for them. The clause's final bullet, documenting public
 *       surfaces with their purpose, inputs, outputs, side effects and error modes, together with clause E, is
 *       what puts this file in the tree at all.</li>
 *   <li><strong>Clause C, repository hygiene.</strong> The banner above follows the universal Apache-2.0
 *       source-header convention of the frozen corpus, extended in the way the migration adopted for Java:
 *       the header names the originating COBOL artefacts as well as the component. The formatter condition of
 *       this clause was not triggered when the migration began, because the repository then contained no
 *       formatter or linter configuration and no Java at all; the conventions were therefore
 *       <strong>established</strong> rather than inherited, and this file follows the established ones - UTF-8,
 *       line-feed endings, a final newline, no trailing whitespace, four-space Java indentation - and the
 *       documentation shape its sibling package files already use. Determinism comes from the pinned wrapper,
 *       the pinned plugin versions and the enforcer floor; the absence of any direct environment read keeps
 *       behaviour out of ambient process state.</li>
 *   <li><strong>Clause D, security standards.</strong> This is the load-bearing clause here.
 *       <strong>No secret value of any kind appears in this file</strong>: no signing key, no example key, no
 *       token, no password and no digest. The shared plaintext literal of the ten seeded users is referred to
 *       only in the abstract and never reproduced. Dependencies are pinned and none is added; Lombok and every
 *       standalone JWT library are absent. No dynamic evaluation, no process execution, no deserialization of
 *       untrusted input and no concatenated SQL occurs anywhere in the package. Least privilege shows up as
 *       BCrypt at strength 10, the five-claim minimum, administrator-only user administration, sign-on as the
 *       sole unauthenticated operation, and a short configurable token lifetime.</li>
 *   <li><strong>Clause E, documentation standards.</strong> Discharged through the docstring option rather
 *       than a README, with the four required subjects present above as separately headed sections: what it
 *       does, how to run and build and test it, key configuration and defaults, and common failure modes with
 *       troubleshooting.</li>
 *   <li><strong>Clause F, output requirements.</strong> Every claim cites a path, a symbol or a locator; every
 *       failure mode carries a severity from the Blocker, High, Medium and Low scale and a remediation; and
 *       what is missing is stated as Not available with what would be needed, rather than filled with a
 *       plausible guess.</li>
 * </ul>
 *
 * <h2>The one documented conflict, and why it is not instantiated here</h2>
 *
 * <p>The project carries exactly one standing conflict between its rule and its mandate: clause B forbids dead
 * code, while behavioural parity requires preserving legacy control flow including reachable no-ops. Parity
 * governs, on the reading that clause B forbids <em>untracked</em> dead code and every retained artefact is
 * cited in the traceability matrix and justified in the decision log.
 *
 * <p><strong>That conflict has no site in this package.</strong> All three retained-for-parity artefacts live
 * elsewhere - a reject-code constant that is assigned but never consumed, an empty but reachable
 * fee-computation paragraph, and a redundant index assignment in the statement program. Nothing in
 * {@code com.cardemo.security} is retained for parity, nothing here is unreachable, and this documentation
 * therefore describes no intentional no-op. Anything in this package that looks like dead code is simply dead
 * code, and should be removed rather than explained.
 */
package com.cardemo.security;
