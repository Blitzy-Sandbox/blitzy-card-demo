/*
 * ******************************************************************
 * Program     : package-info.java
 * Package     : com.cardemo.service.admin
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 package documentation
 *               (Service layer (user administration))
 * Function    : User administration over the USRSEC dataset: list,
 *               add and update. The delete boundary is absent and is
 *               declared so; note also that the source's delete
 *               program has NO self-delete guard, and none may be
 *               invented for it.
 * Source      : app/cbl/COUSR00C.cbl (695 lines, 16 paragraphs; user list, 10 rows per page at :L57) @ 7756d89
 * Source      : app/cbl/COUSR01C.cbl (299 lines, 9 paragraphs; user add) @ 7756d89
 * Source      : app/cbl/COUSR02C.cbl (414 lines, 11 paragraphs; user update) @ 7756d89
 * Source      : app/cbl/COUSR03C.cbl (359 lines; user delete - NOT translated in this package) @ 7756d89
 * Source      : app/cpy/CSUSR01Y.cpy:L17-L23 (80-byte SEC-USER-DATA layout, key 8) @ 7756d89
 * Source      : app/cpy-bms/COUSR00.CPY (59 input fields), COUSR01.CPY (12), COUSR02.CPY (12) @ 7756d89
 * Source      : app/jcl/DUSRSECJ.jcl:L64-L66 (KEYS(8,0) RECORDSIZE(80,80) REUSE INDEXED) @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD (DEFINE TRANSACTION(CU00) through (CU03)) @ 7756d89
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
 * User administration: the services behind the four {@code CU} transactions, of which three are present.
 *
 * <h2>What it does</h2>
 *
 * <ul>
 *   <li>{@link com.cardemo.service.admin.UserListService} - {@code app/cbl/COUSR00C.cbl}, 695 lines.
 *       <strong>Ten rows a page</strong> ({@code app/cbl/COUSR00C.cbl:L57}), with forward and backward paging.
 *       The 59 input fields of {@code app/cpy-bms/COUSR00.CPY} are mostly the ten-row table, which is why that
 *       map's field count is so much larger than the single-record maps'.</li>
 *   <li>{@link com.cardemo.service.admin.UserAddService} - {@code app/cbl/COUSR01C.cbl}, 299 lines. The
 *       field-by-field validation order is preserved as written, and duplicate-key handling is a typed outcome
 *       rather than a swallowed status.</li>
 *   <li>{@link com.cardemo.service.admin.UserUpdateService} - {@code app/cbl/COUSR02C.cbl}, 414 lines. A
 *       read-modify-write with change detection.</li>
 *   </ul>
 *
 * <p><strong>Not available: the delete service.</strong> Measured 3 August 2026 at commit {@code 2e087c4}, this
 * package contains the three services above and no others. {@code app/cbl/COUSR03C.cbl} - 359 lines, the
 * read-confirm-delete chain behind transaction {@code CU03} - is <strong>planned and not present</strong>.
 * Nothing here may be read as a claim that a delete path exists.
 *
 * <p>When it is authored, one property of the source must survive: <strong>{@code COUSR03C} has no self-delete
 * guard.</strong> It never compares the target user identifier against the signed-on identifier, so an
 * administrator can delete their own row. That is a preserved legacy behaviour, not an oversight to be closed:
 * adding the guard is a behaviour change, and behavioural parity is the acceptance contract. It is recorded here
 * so that whoever writes the service does not "improve" it in passing.
 *
 * <h3>The record contract</h3>
 *
 * <p>{@code app/cpy/CSUSR01Y.cpy} declares 80 bytes: an 8-character identifier, a 20-character first name, a
 * 20-character last name, an 8-character password, a 1-character type and filler. The cluster is
 * {@code KEYS(8,0) RECORDSIZE(80,80) REUSE INDEXED} ({@code app/jcl/DUSRSECJ.jcl:L64-L66}) - defined in JCL
 * rather than catalogued, which is why it does not appear in {@code app/catlg/LISTCAT.txt}. Two consequences
 * bind this package. The <strong>password column is 60 characters, not 8</strong>, because it holds a BCrypt
 * digest rather than the source's plaintext. And the type field carries only {@code 'A'} or {@code 'U'}
 * ({@code app/cpy/COCOM01Y.cpy:L26-L28}), which is the authority the whole authorisation model rests on.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>Page size 10 is a parity contract, not a preference</strong>, from
 *       {@code app/cbl/COUSR00C.cbl:L57}. It is not configurable, and it is unrelated to the batch reader fetch
 *       sizes under {@code carddemo.batch.*}. Because the exchange is stateless, the browse position that lived
 *       in the COMMAREA moves to a request parameter and the next-page flag to response metadata.</li>
 *   <li>BCrypt strength 10, matching what {@code src/main/resources/db/migration/V3__seed_data.sql} used for the
 *       ten seeded rows. The encoder itself is published by {@code com.cardemo.config.SecurityConfig} and
 *       injected; it is never constructed here.</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto: validate} in every profile, so a mapping that disagrees with the
 *       Flyway schema fails context startup rather than degrading into a wrong query.</li>
 *   <li>Every path in this package is restricted to the administrator authority, and that restriction is
 *       declared in {@code SecurityConfig} rather than checked here - a second authorisation model would be one
 *       too many.</li>
 *   <li>Masking of password hashes is configured in {@code src/main/resources/logback-spring.xml}, and
 *       {@code org.hibernate.orm.jdbc.bind} is deliberately held at WARN because at TRACE it prints every bound
 *       parameter - which for this table means the digest.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ol>
 *   <li><p><strong>Symptom: a 404 from the user delete path.</strong> Cause: the service does not exist - see the
 *       Not available note. <em>Remediation:</em> author it, preserving the absent self-delete guard.
 *       <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: a password digest is returned in a payload.</strong> Cause: the read model exposes
 *       the password field. <em>Remediation:</em> never return it, at any authority level. Rule 1 Clause D
 *       forbids it. <strong>Severity: Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: an added user cannot sign on.</strong> Cause: the password was stored unhashed, or
 *       hashed with a different encoder than the one authentication uses. <em>Remediation:</em> use the injected
 *       encoder for both. <strong>Severity: Blocker.</strong></p></li>
 *   <li><p><strong>Symptom: adding an existing identifier silently overwrites the row.</strong> Cause: a
 *       duplicate-key status was treated as success, or a save was used where an insert was meant.
 *       <em>Remediation:</em> surface it as a duplicate-record outcome; the source did not overwrite.
 *       <strong>Severity: High</strong> - it destroys a user's credentials.</p></li>
 *   <li><p><strong>Symptom: the user list skips or repeats rows across pages.</strong> Cause: the page size
 *       changed, or the browse position was derived from something other than the request parameter.
 *       <em>Remediation:</em> restore 10 and drive the position from the request.
 *       <strong>Severity: Medium.</strong></p></li>
 *   <li><p><strong>Symptom: an update silently clears a field the caller did not send.</strong> Cause: the
 *       read-modify-write was replaced by a whole-record replace. <em>Remediation:</em> read, apply the changed
 *       fields, then write, exactly as {@code app/cbl/COUSR02C.cbl} does.
 *       <strong>Severity: High.</strong></p></li>
 *   <li><p><strong>Symptom: a standard user reaches an administrative path.</strong> Cause: the authority
 *       mapping drifted from {@code CDEMO-USER-TYPE}. <em>Remediation:</em> fix it in {@code SecurityConfig}.
 *       <strong>Severity: Blocker.</strong></p></li>
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
 *       environment loaded with {@code set -a; . ./.env; set +a}, because {@code JWT_SIGNING_KEY} has no default and
 *       startup fails without it by design.</li>
 *   <li><strong>Test.</strong> Tests belong in {@code src/test/java/com/cardemo/unit/service}.
 *       {@code UserListServiceTest} and {@code UserAddServiceTest} exist. <strong>Not available, measured 3
 *       August 2026:</strong> {@code UserUpdateService} has no test class and is not referenced anywhere under
 *       {@code src/test/java}, so no coverage figure quoted anywhere is evidence about it. The required
 *       assertions across the package are: page size exactly 10 with forward and backward paging; the
 *       field-by-field validation order of the add path as written; a duplicate identifier surfacing as a
 *       duplicate-record outcome and performing no update; an update leaving unsent fields untouched; and no
 *       password or digest appearing in any payload, message or log event.</li>
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
package com.cardemo.service.admin;
