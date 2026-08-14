/*
 ******************************************************************
 * Program     : UserUpdateRequest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : User-update request; identifier first; the password is write-only and never echoed back.
 * Source      : app/cpy-bms/COUSR02.CPY (12 fields) @ 7756d89
 ******************************************************************
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
 ******************************************************************
 */
package com.cardemo.model.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

/**
 * Inbound request payload for the user-update transaction, translated field-for-field from the BMS symbolic map
 * {@code app/cpy-bms/COUSR02.CPY}, which declares <strong>exactly 12 input fields</strong>. The count is
 * verified rather than assumed: the input group {@code 01 COUSR2AI} begins at line 17 and its last data field
 * {@code ERRMSGI} sits at line 90, after which the output group {@code 01 COUSR2AO REDEFINES COUSR2AI} begins
 * at line 91. The behavioural counterpart is {@code app/cbl/COUSR02C.cbl}, the CICS program whose declared
 * function is to update a user in the {@code USRSEC} file, and whose persisted record layout is
 * {@code app/cpy/CSUSR01Y.cpy}.
 *
 * <p>This type is a pure data holder. It performs no hashing, no change detection, no normalisation, no mapping
 * and no arithmetic, because every one of those is a service concern. In particular the change detection that
 * {@code app/cbl/COUSR02C.cbl} performs at lines 219 to 234 - comparing the submitted first name, last name,
 * password and user type against the record just read and setting {@code USR-MODIFIED-YES} when any of them
 * differs - belongs to the update service and is deliberately absent here.
 *
 * <p>The user identifier is declared under a different name and at a different position on each user map:
 * {@code USRIDINI} at {@code app/cpy-bms/COUSR02.CPY:60}, ahead of the two name fields, but {@code USERIDI} at
 * {@code app/cpy-bms/COUSR01.CPY:72}, behind them. <strong>No shared base type, interface or mixin therefore
 * exists across the user data transfer objects</strong>; such an abstraction would be factually wrong rather
 * than merely redundant. The same reasoning applies to the six recurring terminal header fields, which are
 * declared
 * inline on every map rather than extracted: {@code CURTIMEI} is {@code PIC X(8)} here at line 54, but
 * {@code app/cpy-bms/COSGN00.CPY:54} alone declares it {@code PIC X(9)}, so a shared header helper could not
 * carry a single correct width.
 *
 * <p>Because the order and the name genuinely differ, <strong>no shared base type, interface or mixin
 * exists across the user data transfer objects</strong>; such an abstraction would be factually wrong
 * rather than merely redundant. The same reasoning applies to the six recurring terminal header fields,
 * which are declared inline on every map rather than extracted: {@code CURTIMEI} is {@code PIC X(8)} here
 * at line 54, but {@code app/cpy-bms/COSGN00.CPY:54} alone declares it {@code PIC X(9)}, so a shared header
 * helper could not carry a single correct width.</p>
 *
 * <p><strong>SECURITY - the password is write-only.</strong> {@code PASSWDI PIC X(8)} at
 * {@code app/cpy-bms/COUSR02.CPY:78} is bound inbound and is <strong>never</strong> serialised outbound:
 * the component carries Jackson's write-only property access, so a value in a request body populates it
 * while no value ever appears in a response body. The persisted layout {@code app/cpy/CSUSR01Y.cpy} is an
 * 80 byte record - identifier {@code X(08)}, first name {@code X(20)}, last name {@code X(20)}, password
 * {@code X(08)}, type {@code X(01)}, filler {@code X(23)} - and the target column holds a 60 character
 * BCrypt digest, produced with the target's standard cost factor by the seed migration and by the update
 * service. This type declares no digest field at all: it carries the presented plaintext inbound and
 * nothing outbound, and the digest prefixes that identify a BCrypt value must never appear in its output.</p>
 *
 * <p>Two further hardening decisions follow from holding a credential in memory. This type deliberately does
 * <strong>not</strong> implement {@code java.io.Serializable}, because Java serialisation is a recognised
 * risky pattern and there is no reason to make an object that carries a credential reconstructible from a
 * byte stream; JSON binding is the only ingress it needs. And that single ingress is closed to anything
 * unexpected: {@link #rejectUnrecognisedProperty(String, Object)} refuses a payload that carries a property
 * this type does not declare, rather than binding the twelve it recognises and discarding the rest in
 * silence.</p>
 *
 * <p><strong>The read-modify-write flow must never echo the stored password back.</strong> This warning is
 * not theoretical. The legacy program pre-populates the screen from the record it has just read, and at
 * {@code app/cbl/COUSR02C.cbl:169} it moves {@code SEC-USR-PWD} straight into {@code PASSWDI}, so the
 * stored plaintext credential was rendered on the 3270 display. That behaviour is deliberately not
 * reproduced. It is the one place where this type departs from strict parity with its source, the departure
 * is a deliberate security correction rather than an oversight, and it is why the response side of the
 * update flow carries no password property of any kind. The consequence for callers is set out next.</p>
 *
 * <p><strong>The password is required on every update, and a blank one is refused.</strong> A blank password
 * does <em>not</em> mean "leave the stored credential unchanged", whatever a reader might expect.
 * {@code com.cardemo.service.admin.UserUpdateService} tests the field for emptiness at
 * {@code app/cbl/COUSR02C.cbl:198} and answers {@code :200}'s literal, {@code "Password can NOT be
 * empty..."}, as a {@code 400} carrying {@code errorCode} {@code CARDDEMO-VALIDATION-REJECTED}. Absent and
 * blank therefore reach the same refusal; a caller cannot omit the field to mean "no change".</p>
 *
 * <p><strong>The no-change outcome is reached by resubmitting the same password, not by omitting it.</strong>
 * The service's predicate is {@code !passwordEncoder.matches(presented, storedDigest)}, so a value that
 * matches the stored digest leaves the digest untouched - which is the ordinary no-change result. That is why
 * the reasoning behind the withdrawn claim, though sound as design, does not describe this implementation: the
 * source governs, and the source rejects an empty field. Because the stored credential is no longer echoed on
 * any response, a caller has to hold or re-collect the password rather than read it back and resubmit it.</p>
 *
 * <p>Three input states are nonetheless still carried through distinctly, because the <em>refusal</em> has to
 * be reported accurately even though absent and blank share an outcome: absent (the JSON property is missing,
 * so the component is {@code null}), blank (the property is present and empty, so the component is the empty
 * string) and populated (a value the service will hash and store). This type never coerces {@code null} to
 * the empty string, never coerces the empty string to {@code null}, and never substitutes a sentinel.</p>
 *
 * <p>For the same reason nothing is normalised on the way in: no trimming, no case folding, no padding to
 * the picture width. Trimming would turn a blank field into an empty one and erase the distinction just
 * described. Case folding is a service concern rather than a carrier concern, and where the services do
 * apply it - the sign-on path upper-cases both the identifier and the presented password before comparing
 * them - it must pass {@code Locale.ROOT}, because a Turkish-locale default maps a dotless letter i to a
 * dotted capital and would silently change an authentication outcome from one host to another. Keeping this
 * type free of any locale-sensitive operation is what makes its behaviour identical on every machine.</p>
 *
 * <p><strong>The three-state model comes from the source, not from convenience.</strong>
 * {@code app/cpy/CSSETATY.cpy} is a parameterised {@code COPY ... REPLACING} template copied into the
 * procedure division, with parameters {@code (TESTVAR1)}, {@code (SCRNVAR2)} and {@code (MAPNAME3)}. Its
 * verified body is:</p>
 * <pre>{@code
 * IF (FLG-(TESTVAR1)-NOT-OK
 * OR  FLG-(TESTVAR1)-BLANK)
 * AND CDEMO-PGM-REENTER
 *     MOVE DFHRED  TO (SCRNVAR2)C OF (MAPNAME3)O
 *     IF  FLG-(TESTVAR1)-BLANK
 *         MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O
 *     END-IF
 * END-IF
 * }</pre>
 * <p>The states are OK, NOT-OK and BLANK. BLANK is a state distinct from NOT-OK and additionally stamps an
 * asterisk into the screen field, and the markers fire only on re-entry, never on first display. The
 * template is procedural, so it gets no class of its own; it maps onto bean validation plus per-field error
 * markers. A sibling program corroborates the distinction at message level:
 * {@code app/cbl/COACTUPC.cbl:505-508} declares two different literals for one field, "Credit Limit must be
 * supplied" for the blank case and "Credit Limit is not valid" for the invalid case.</p>
 *
 * <p><strong>The user type is a raw one-character code, not an enum.</strong> {@code USRTYPEI} is
 * {@code PIC X(1)} at {@code app/cpy-bms/COUSR02.CPY:84} and is modelled as a one-character
 * {@code String}. The {@code UserType} enum in the sibling enums package has exactly two constants, for
 * the administrator and standard-user codes derived from the 88-levels at {@code app/cpy/COCOM01Y.cpy:27-28},
 * and it is deliberately not used here for two independent reasons. First, binding an inbound
 * one-character field to an enum turns an out-of-domain character into a deserialization failure, which
 * replaces the source's own validation message with a framework error; that is a behaviour change, and
 * parity is the contract. Second, this is the update path, so the flow re-presents a stored value: if a
 * legacy record holds a type character outside the two-constant domain, an enum binding would make that
 * record unloadable and therefore uneditable, whereas a raw {@code String} carries it and lets the record be
 * corrected. Within this package only {@code CommArea}, {@code SignOnResponse} and {@code MenuResponse}
 * reference the enum; this type, {@code UserCreateRequest} and {@code UserSecurityDto} all carry the raw
 * one-character code.</p>
 *
 * <p><strong>No snapshot detail groups.</strong> Unlike {@code AccountUpdateRequest}, this type carries no
 * {@code oldDetails} and no {@code newDetails} group, because {@code app/cpy-bms/COUSR02.CPY} declares none.
 * The only program in the corpus that declares such groups is {@code app/cbl/COACTUPC.cbl}, at line 669
 * ({@code ACUP-OLD-DETAILS}) and line 757 ({@code ACUP-NEW-DETAILS}), where a stateless request cannot
 * otherwise reproduce a field-by-field comparison against what the screen displayed. The user-update flow
 * needs no such carrier: it re-reads the record and compares against what it read.</p>
 *
 * <p><strong>Validation matches the source exactly - no stricter, no looser.</strong> Each component
 * carries a maximum-size constraint equal to its {@code PIC} width, and nothing else. The password
 * constraint is a maximum of 8 characters because the source field is {@code X(8)}; it is deliberately not
 * widened to accommodate a digest, since no digest is ever carried here. No password-complexity rule, no
 * minimum length, no character-class requirement and no regular expression is imposed, because
 * {@code app/cbl/COUSR02C.cbl} imposes none and adding one would reject input the legacy system accepts.
 * No component is marked as required, because the source tolerates a blank field and reports it with its own
 * message rather than rejecting the payload outright. The declaration order below reproduces the source's
 * field-by-field validation order, which for this map begins with the identifier and then proceeds through
 * the names: identifier at {@code app/cbl/COUSR02C.cbl:180}, first name at 186, last name at 192, password
 * at 198 and user type at 204.</p>
 *
 * <p><strong>Seed data.</strong> The ten users the system starts with exist only as inline
 * {@code SYSUT1 DD *} card images inside {@code app/jcl/DUSRSECJ.jcl}, which the same job loads through
 * {@code IEBGENER} into a cluster it defines with {@code KEYS(8,0) RECORDSIZE(80,80) REUSE INDEXED} at
 * lines 65 to 68 - the origin of the 8 character identifier width used below. Five are administrators and
 * five are standard users, and every one of them carries the same literal plaintext password in that job
 * stream. That literal is not reproduced here, in any comment, or in any test fixture for this type; the
 * seed migration hashes it on load, and it is cited only by file.</p>
 *
 * <p><strong>Error modes.</strong> This type has no behaviour of its own that can fail beyond the guard it
 * declares and the size constraints it carries, so its error modes are those of binding and validation:</p>
 * <ul>
 *   <li>A JSON property that is not one of the twelve documented fields is rejected by
 *       {@link #rejectUnrecognisedProperty(String, Object)}, which throws {@link IllegalArgumentException}
 *       and surfaces as a bad-request response. The rejected property name is not reproduced in the
 *       message, because it is untrusted input.</li>
 *   <li>A value longer than its {@code PIC} width fails its size constraint. The interpolated message
 *       states the permitted range and does not reproduce the submitted value, which is what keeps a
 *       rejected password out of logs and out of responses.</li>
 *   <li>A JSON value of the wrong shape, for example an object where a string is declared, fails during
 *       binding before this type is constructed.</li>
 *   <li>Emptiness, blankness and absence are never errors here. They are carried through faithfully so
 *       that the update service can raise the source's own messages, which are, in the source's own
 *       validation order, "User ID can NOT be empty..." at {@code app/cbl/COUSR02C.cbl:182}, "First Name
 *       can NOT be empty..." at 188, "Last Name can NOT be empty..." at 194, "Password can NOT be
 *       empty..." at 200 and "User Type can NOT be empty..." at 206, followed by "Please modify to update
 *       ..." at 239 when nothing changed, and the lookup and rewrite outcomes "User ID NOT found...",
 *       "Unable to lookup User..." and "Unable to Update User..." from the file-access paragraphs.</li>
 *   <li>Not available: the source declares no password policy, no rate limit and no service-level
 *       objective for this transaction, so none is invented. Nothing in the corpus supplies one.</li>
 *   </ul>
 *
 * <p><strong>Build, test and configuration.</strong> This type is compiled by the single root
 * {@code pom.xml} against Java 25 with {@code -Xlint:all -Werror}, so any warning it produced would fail
 * the build; it is exercised by the unit tests under {@code src/test/java/com/cardemo/unit/model}, which own
 * the regression proving that a populated instance serialises without any password key while a payload
 * containing a password still binds. It adds no dependency: the only libraries it touches are the bean
 * validation API and the Jackson annotations, both already managed by the Spring Boot parent, and Lombok is
 * not used anywhere in this project. It reads no configuration property and has no default to override.
 * Structured logging masks credentials and digests profile-invariantly through
 * {@code src/main/resources/logback-spring.xml}, but that is the second line of defence; the first is that
 * this type never emits them.</p>
 *
 * <p><strong>Findings recorded against this translation</strong>, classified by severity:</p>
 * <ul>
 *   <li><em>High</em> - {@code app/cbl/COUSR02C.cbl:169} echoes the stored plaintext password onto the
 *       screen. Remediation applied: the password is write-only here and no response type carries it.</li>
 *   <li><em>Medium, closed</em> - the input-field census. Prior-generation plan prose totalled 460 input
 *       fields across the 17 symbolic maps, while its own per-map table summed to 440 and a direct recount of
 *       {@code app/cpy-bms} yields 441. The single discrepancy is {@code app/cpy-bms/COACTVW.CPY:60}, which
 *       declares {@code ACCTSIDI PIC 99999999999} longhand, so any count driven by a parenthesised picture
 *       clause misses it. This map contributes 12 fields under every reading, so nothing here changes;
 *       remediation is documentation only.</li>
 *   <li><em>Medium</em> - the identifier field name diverges across the four user maps, as set out above.
 *       Remediation applied: each map is cited by its own field name, and no shared type is introduced.</li>
 *   <li><em>Low</em> - the {@code CURTIMEI} width outlier at {@code app/cpy-bms/COSGN00.CPY:54}.
 *       Remediation applied: the six header fields are declared inline on every map.</li>
 *   <li><em>Low</em> - Jackson's type-level unknown-property annotation is ineffective when the object
 *       mapper disables failure on unknown properties, which is the framework default. Remediation
 *       applied: the guard below rejects unconditionally instead, so the behaviour does not depend on
 *       mapper configuration.</li>
 *   </ul>
 *
 * @param transactionName {@code TRNNAMEI PIC X(4)} at {@code app/cpy-bms/COUSR02.CPY:24}. Terminal header
 *                        field carrying the four-character CICS transaction identifier that the legacy
 *                        screen displayed. Declared inline rather than shared, and never trusted as input.
 * @param title01         {@code TITLE01I PIC X(40)} at {@code app/cpy-bms/COUSR02.CPY:30}. First screen
 *                        title line, populated on the legacy screen from the application title constants.
 * @param currentDate     {@code CURDATEI PIC X(8)} at {@code app/cpy-bms/COUSR02.CPY:36}. Header date the
 *                        legacy screen rendered; carried as text because the map declares text, and never
 *                        parsed or reformatted here.
 * @param programName     {@code PGMNAMEI PIC X(8)} at {@code app/cpy-bms/COUSR02.CPY:42}. Header field
 *                        carrying the eight-character originating program name.
 * @param title02         {@code TITLE02I PIC X(40)} at {@code app/cpy-bms/COUSR02.CPY:48}. Second screen
 *                        title line.
 * @param currentTime     {@code CURTIMEI PIC X(8)} at {@code app/cpy-bms/COUSR02.CPY:54}. Header time the
 *                        legacy screen rendered. Eight characters here; {@code app/cpy-bms/COSGN00.CPY:54}
 *                        alone declares nine, which is why the header fields are not shared.
 * @param userId          {@code USRIDINI PIC X(8)} at {@code app/cpy-bms/COUSR02.CPY:60}. The user
 *                        identifier, and the first field the source validates. Eight characters, matching
 *                        the {@code USRSEC} cluster key length declared in {@code app/jcl/DUSRSECJ.jcl}.
 *                        Note the field name: this map, the list map and the delete map all declare
 *                        {@code USRIDINI}, while the add map alone declares {@code USERIDI}.
 * @param firstName       {@code FNAMEI PIC X(20)} at {@code app/cpy-bms/COUSR02.CPY:66}. Given name,
 *                        twenty characters, matching {@code SEC-USR-FNAME} in the persisted layout.
 * @param lastName        {@code LNAMEI PIC X(20)} at {@code app/cpy-bms/COUSR02.CPY:72}. Family name,
 *                        twenty characters, matching {@code SEC-USR-LNAME} in the persisted layout.
 * @param password        {@code PASSWDI PIC X(8)} at {@code app/cpy-bms/COUSR02.CPY:78}. The presented
 *                        plaintext credential. <strong>Inbound only</strong>: it binds from a request body
 *                        and is never serialised into a response, never rendered by
 *                        {@link #toString()}, and never stored as given, since the service hashes it.
 *                        <strong>Required in practice</strong>: an absent or blank value is refused with
 *                        {@code 400} and {@code app/cbl/COUSR02C.cbl:200}'s literal, {@code "Password can NOT
 *                        be empty..."}. The no-change outcome is reached by resubmitting a value that matches
 *                        the stored digest, not by omitting the field. All three input states are still
 *                        carried through untouched so the refusal can be reported accurately.
 * @param userType        {@code USRTYPEI PIC X(1)} at {@code app/cpy-bms/COUSR02.CPY:84}. The raw
 *                        one-character user-type code, carried as declared and deliberately not bound to
 *                        the {@code UserType} enum, so that an out-of-domain character reaches the
 *                        service's own validation instead of failing during binding.
 * @param errorMessage    {@code ERRMSGI PIC X(78)} at {@code app/cpy-bms/COUSR02.CPY:90}. The message line
 *                        the legacy screen displayed. The symbolic map declares it as an input field, so it
 *                        is mirrored here for field-contract completeness; the server produces its own
 *                        messages and never trusts a submitted one.
 */
public record UserUpdateRequest(

        // TRNNAMEI PIC X(4)  - app/cpy-bms/COUSR02.CPY:24
        @Size(max = 4) String transactionName,

        // TITLE01I PIC X(40) - app/cpy-bms/COUSR02.CPY:30
        @Size(max = 40) String title01,

        // CURDATEI PIC X(8)  - app/cpy-bms/COUSR02.CPY:36
        @Size(max = 8) String currentDate,

        // PGMNAMEI PIC X(8)  - app/cpy-bms/COUSR02.CPY:42
        @Size(max = 8) String programName,

        // TITLE02I PIC X(40) - app/cpy-bms/COUSR02.CPY:48
        @Size(max = 40) String title02,

        // CURTIMEI PIC X(8)  - app/cpy-bms/COUSR02.CPY:54 (X(9) only at app/cpy-bms/COSGN00.CPY:54)
        @Size(max = 8) String currentTime,

        // USRIDINI PIC X(8)  - app/cpy-bms/COUSR02.CPY:60 (identifier first on this map)
        @Size(max = 8) String userId,

        // FNAMEI   PIC X(20) - app/cpy-bms/COUSR02.CPY:66
        @Size(max = 20) String firstName,

        // LNAMEI   PIC X(20) - app/cpy-bms/COUSR02.CPY:72
        @Size(max = 20) String lastName,

        // PASSWDI  PIC X(8)  - app/cpy-bms/COUSR02.CPY:78
        //
        // WRITE-ONLY AND REQUIRED IN PRACTICE. "Blank means unchanged" is not the behaviour a caller gets.
        // app/cbl/COUSR02C.cbl:198 tests this field for emptiness and :200 answers "Password can NOT be
        // empty...", so UserUpdateService rejects a blank or absent password with a 400 carrying that exact
        // literal and errorCode CARDDEMO-VALIDATION-REJECTED. A blank value is refused, never interpreted as
        // "leave the stored credential alone".
        //
        // The no-change outcome does exist, but it is reached differently: supply the password and let it
        // MATCH the stored digest. The service's predicate is !passwordEncoder.matches(presented, stored), so
        // a matching resubmission simply leaves the digest untouched. Every rewrite of this record must
        // therefore carry the password, and because the current value is never readable - the stored digest is
        // deliberately not echoed on any response - a caller cannot pre-fill it from a prior read.
        //
        // WRITE_ONLY is what keeps it out of every response body: the field is bound on the way in and
        // omitted on the way out, so no digest and no plaintext can leave through this type.
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Size(max = 8) String password,

        // USRTYPEI PIC X(1)  - app/cpy-bms/COUSR02.CPY:84 (raw code, never the UserType enum)
        @Size(max = 1) String userType,

        // ERRMSGI  PIC X(78) - app/cpy-bms/COUSR02.CPY:90
        @Size(max = 78) String errorMessage) {

    /**
     * Rejects any JSON property that is not one of the twelve fields declared by
     * {@code app/cpy-bms/COUSR02.CPY}, so that a request carrying an unexpected property fails loudly instead
     * of being bound with that property silently discarded.
     *
     * @param name the unrecognised property name supplied by the caller, deliberately neither stored nor
     * reproduced in the thrown message
     * @param value the unrecognised property value supplied by the caller, deliberately neither stored nor
     * reproduced in the thrown message
     * @throws IllegalArgumentException always, because an unrecognised property is never acceptable on this
     * request
     */
    @JsonAnySetter
    void rejectUnrecognisedProperty(String name, Object value) {
        throw new IllegalArgumentException(
                "UserUpdateRequest accepts only the twelve fields declared by app/cpy-bms/COUSR02.CPY, "
                        + "and the request contained a property that is not one of them. The offending "
                        + "name and value are withheld because they are untrusted input.");
    }

    /**
     * Returns a rendering that is safe to place in a log record or an exception message.
     *
     * <p>That claim was previously false and is now true. Both fields are declared {@code String} and both
     * arrive from a JSON request body, so a caller controlled their bytes and a CR or LF in either forged log
     * records - while this very sentence asserted the rendering was safe. Both are passed through
     * {@link ApiMasking#forDiagnostics(String)}, which escapes every control character to its own code point.
     *
     * @return a rendering containing only the user identifier and the originating program name, with every
     *     control character escaped
     */
    @Override
    public String toString() {
        return "UserUpdateRequest[userId=" + ApiMasking.forDiagnostics(userId)
                + ", programName=" + ApiMasking.forDiagnostics(programName) + "]";
    }
}
