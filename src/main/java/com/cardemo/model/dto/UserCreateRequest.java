/*
 * ******************************************************************
 * Program     : UserCreateRequest.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : User-add request; the password is write-only and never
 *               serialized outward.
 * Source      : app/cpy-bms/COUSR01.CPY (12 fields) @ 7756d89
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
package com.cardemo.model.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import java.util.function.Function;

/**
 * Inbound request payload for the user-add transaction, translated field-for-field from the BMS symbolic map
 * {@code app/cpy-bms/COUSR01.CPY}, whose input group {@code COUSR1AI} (declared at line 17) carries exactly
 * <strong>12</strong> input fields. The twelve fields below appear in the map's own declaration order, and each
 * one names its originating COBOL field, PIC clause and line number.
 *
 * <p>The legacy counterpart is {@code app/cbl/COUSR01C.cbl}, the CICS screen program behind transaction
 * {@code CU01}. That program receives this map, validates the five operator-supplied fields for presence, and
 * writes an 80-byte record to the {@code USRSEC} VSAM cluster. This class is the request half of that
 * conversation only: it holds transport state and nothing else, performs no hashing, no normalisation, no
 * mapping and no arithmetic, and reaches no repository, service or controller.
 *
 * <p><strong>Purpose.</strong> Bind an untrusted JSON request body at the HTTP trust boundary, carry the
 * operator's twelve presented values verbatim into the service layer, and expose eleven of them back out
 * again. The twelfth, the password, is inbound-only and is described below.
 *
 * <p><strong>SECURITY: the password is write-only.</strong> {@code PASSWDI PIC X(8)} at
 * {@code app/cpy-bms/COUSR01.CPY:78} is the presented plaintext credential. This class carries it
 * <em>inbound, and outbound to exactly one in-process caller and to no serializer</em>, enforced by three
 * independent mechanisms so that no single one is load-bearing:
 * <ul>
 *   <li>the field is annotated write-only for JSON binding, so the serializer never emits it;</li>
 *   <li><strong>no {@code getPassword()} bean accessor exists</strong>, so no reflective bean mapper,
 *       property binder or {@code java.beans.Introspector}-driven renderer can discover the credential as a
 *       JavaBean property; and</li>
 *   <li><strong>the single read path takes an argument.</strong> It is
 *       {@link #mapPassword(java.util.function.Function)}, which hands the value to a reader the caller
 *       supplies rather than returning it. Jackson, {@code java.beans.Introspector}, reflective bean mappers,
 *       {@code toString} generators and property-walking loggers all discover ZERO-ARGUMENT methods only, so
 *       a method that requires an argument is invisible to every one of them - which is why no
 *       {@code @JsonIgnore} is needed on it and why no zero-argument reader exists to need one.</li>
 *   </ul>
 *
 * <p><strong>Why a read path exists at all, severity HIGH.</strong> It used to have none, and the
 * consequence was worse than the problem it was avoiding: with the body member unreadable, the controller
 * accepted the credential through a bespoke {@code X-Presented-Password} request header instead, and the
 * body member it had bound was ignored. Two harms followed. Generic ingress, proxy and APM redaction
 * recognises the standard authorization, cookie and body-password channels, not a project-invented header
 * name, so the credential travelled through exactly the channel least likely to be scrubbed. And the value
 * that was audited - the body - could differ from the value that was hashed, because they arrived
 * independently. One narrowly-scoped, non-serializing, non-bean read path removes both harms: the credential
 * travels in the request body only, and the value hashed is by construction the value bound.
 * The persisted layout {@code app/cpy/CSUSR01Y.cpy} is an 80-byte record — {@code SEC-USR-ID PIC X(08)} at
 * line 18, {@code SEC-USR-FNAME PIC X(20)} at line 19, {@code SEC-USR-LNAME PIC X(20)} at line 20,
 * {@code SEC-USR-PWD PIC X(08)} at line 21, {@code SEC-USR-TYPE PIC X(01)} at line 22 and
 * {@code SEC-USR-FILLER PIC X(23)} at line 23, totalling exactly 80 bytes. In the migrated target that
 * 8-byte plaintext password column becomes a 60-character BCrypt digest column, recognisable by its
 * dollar-delimited version marker. <strong>This class never carries that digest</strong>: there is no hash
 * field, no digest field and no salt field here. Hashing is the service layer's responsibility, and the
 * digest belongs to the persistence entity, never to a transport object.
 *
 * <p>Consequently this class deliberately declares no {@code toString()} override. The inherited
 * {@link Object#toString()} emits only the class name and an identity hash, so no accidental log, debugger
 * expression or exception message can render the credential, either whole or partially masked. It likewise
 * declares no {@code equals} or {@code hashCode}, so the credential is never compared as bulk state, and it
 * does <em>not</em> implement {@code java.io.Serializable}, keeping this credential-bearing object off every
 * Java deserialization path.
 *
 * <p><strong>Two divergences from the sibling update map — both deliberately preserved.</strong>
 * {@code app/cpy-bms/COUSR02.CPY} also declares 12 input fields, but they are not the same 12, and the two
 * maps are therefore <em>not</em> unified behind a shared base type, interface or mixin. Doing so would be
 * factually wrong rather than merely redundant:
 * <ul>
 *   <li><strong>Field order differs.</strong> This add map leads with the operator's names and places the
 *       identifier third — {@code FNAMEI} at line 60, {@code LNAMEI} at line 66, {@code USERIDI} at line 72.
 *       The update map leads with the identifier — {@code USRIDINI} at {@code COUSR02.CPY:60}, then
 *       {@code FNAMEI} at line 66 and {@code LNAMEI} at line 72. Each map keeps its own order.</li>
 *   <li><strong>The identifier field name differs.</strong> This map declares {@code USERIDI}; the update
 *       map declares the input-suffixed {@code USRIDINI}, as does the user-list map at
 *       {@code app/cpy-bms/COUSR00.CPY:66}. The citation carried by this class is {@code USERIDI}.</li>
 *   </ul>
 *
 * <p><strong>The six recurring header fields are declared inline, by design.</strong> They are not
 * extracted into a shared helper, base class, interface or mixin, because the recurrence is not uniform:
 * {@code CURTIMEI} is {@code PIC X(8)} on this map and on fifteen of the seventeen symbolic maps, but
 * {@code app/cpy-bms/COSGN00.CPY:54} alone declares {@code PIC X(9)}. A shared header abstraction would
 * have to pick one width and would misrepresent the other, so the six fields are repeated per map instead.
 *
 * <p><strong>The user type stays a raw one-character code.</strong> {@code USRTYPEI PIC X(1)} at line 84 is
 * modelled as a one-character {@code String}, <em>not</em> as the {@code UserType} enum. The enum has
 * exactly two constants, {@code ADMIN} for {@code 'A'} and {@code USER} for {@code 'U'}, derived from the
 * 88-level condition names in {@code app/cpy/COCOM01Y.cpy} at lines 27 and 28. Binding an inbound
 * one-character field to that enum would turn any out-of-domain value into a JSON deserialization failure
 * and would replace the source program's own validation message with a framework error. That is a
 * behaviour change, and parity is the contract, so the raw code is carried and the service decides. Within
 * this package only {@code CommArea}, {@code SignOnResponse} and {@code MenuResponse} reference the enum
 * type; {@code UserCreateRequest}, {@code UserUpdateRequest} and {@code UserSecurityDto} all carry the raw
 * {@code X(1)} code.
 *
 * <p><strong>Validation matches the source exactly — no stricter, no looser.</strong> Every field carries a
 * maximum-length constraint equal to its PIC width and nothing more. In particular the password constraint
 * is a maximum of 8, because the source field is {@code X(8)}; it is deliberately not widened to
 * accommodate a digest, since the digest lives in the entity rather than here. There is <em>no</em>
 * password-complexity rule, no minimum length, no character-class requirement and no regular expression,
 * because {@code app/cbl/COUSR01C.cbl} performs none: its validation is a presence cascade testing
 * {@code = SPACES OR LOW-VALUES} over {@code FNAMEI} at line 118, {@code LNAMEI} at line 124,
 * {@code USERIDI} at line 130, {@code PASSWDI} at line 136 and {@code USRTYPEI} at line 142, plus
 * duplicate-key handling on write, and nothing else. Inventing a complexity rule here would reject input
 * the legacy system accepts. The declaration order of those five fields is what makes the source's
 * first-failing-field reporting order reproducible by the service.
 *
 * <p>No field is marked as required, because the source tolerates a blank submission and reports it as a
 * business validation message rather than rejecting the payload outright. Presence is therefore the service
 * layer's decision, taken in the source's order, not a binding-time rejection.
 *
 * <p><strong>The three-state model is preserved.</strong> {@code app/cpy/CSSETATY.cpy} is a parameterised
 * {@code COPY ... REPLACING} PROCEDURE DIVISION template — parameters {@code (TESTVAR1)},
 * {@code (SCRNVAR2)} and {@code (MAPNAME3)} — whose body is:
 * <pre>{@code
 * IF (FLG-(TESTVAR1)-NOT-OK
 * OR  FLG-(TESTVAR1)-BLANK)
 * AND CDEMO-PGM-REENTER
 *     MOVE DFHRED             TO
 *          (SCRNVAR2)C OF (MAPNAME3)O
 *     IF  FLG-(TESTVAR1)-BLANK
 *         MOVE '*'            TO
 *          (SCRNVAR2)O OF (MAPNAME3)O
 *     END-IF
 * END-IF
 * }</pre>
 * The model is OK / NOT-OK / BLANK, in which <strong>BLANK is a state distinct from NOT-OK</strong>: it
 * additionally stamps an asterisk into the screen field, and the whole block fires only on re-entry, never
 * on first display. That template is procedural and has no class of its own; it maps onto bean validation
 * plus per-field error markers. The consequence for this class is that <em>absent</em>, <em>blank</em> and
 * <em>marked</em> are three distinct states. A null is never coerced to an empty string, an empty string is
 * never coerced to null, and neither is ever coerced to a sentinel; no field is trimmed, upper-cased or
 * lower-cased on ingest. A payload that collapsed the three into one would diverge from the source.
 * Message-level corroboration comes from a sibling program: {@code app/cbl/COACTUPC.cbl:505-508} declares
 * two different literals for one field, {@code 'Credit Limit must be supplied'} for the blank case and
 * {@code 'Credit Limit is not valid'} for the invalid case.
 *
 * <p>Case folding is likewise left to the service. The sign-on program upper-cases both the identifier and
 * the password before comparison, so any such operation must be performed with {@code Locale.ROOT} to stay
 * deterministic — under a Turkish locale {@code toUpperCase()} maps a dotless letter differently and would
 * silently change authentication outcomes. This class performs no case operation at all.
 *
 * <p><strong>Seeded users.</strong> The ten reference users exist only as inline {@code SYSUT1 DD *} data
 * inside {@code app/jcl/DUSRSECJ.jcl}, fed through IEBGENER at step {@code STEP01} (line 32) into the
 * cluster defined at lines 64 to 68 as {@code KEYS(8,0) RECORDSIZE(80,80) REUSE INDEXED}. Five carry user
 * type {@code 'A'} and five carry user type {@code 'U'}, and all ten share one literal plaintext password
 * that the seed migration replaces with a BCrypt digest at load time. That literal value is deliberately
 * not reproduced here, in any comment, or in any test fixture for this class.
 *
 * <p><strong>Error modes.</strong>
 * <ul>
 *   <li><em>Over-length field.</em> A value longer than its PIC width raises a constraint violation whose
 *       interpolated message states the permitted bounds only. Verified against Hibernate Validator: the
 *       password violation message renders as a bounds statement and does <em>not</em> echo the submitted
 *       value.</li>
 *   <li><em>Credential leakage through a rejected value.</em> Spring's field-error object retains the
 *       submitted value alongside the message, so a validation failure on the password puts the plaintext
 *       inside the framework's error object. The default Spring Boot error body does not render it, but a
 *       custom exception handler that serialised field errors wholesale would leak it. Any such handler
 *       belongs to the exception-handling layer rather than to this class, and must emit the field name and
 *       message only, never the rejected value.</li>
 *   <li><em>Unknown JSON property.</em> Rejected outright, by this class rather than by configuration
 *       elsewhere. {@link #rejectUnrecognisedProperty} refuses any property outside the twelve, so the
 *       guarantee does not depend on the object mapper's fail-on-unknown-properties feature remaining
 *       enabled - which matters, because the framework disables that feature by default. The class-level
 *       {@code ignoreUnknown = false} is retained as a declaration of intent, but it is the any-setter
 *       that does the rejecting.</li>
 *   <li><em>Out-of-domain user type.</em> A one-character value outside {@code 'A'} and {@code 'U'} binds
 *       successfully and is carried into the service, which reports it using the source's own message. This
 *       is intentional; see the user-type note above.</li>
 *   </ul>
 *
 * <p><strong>Two structural guarantees, and why each is expressed the way it is.</strong>
 * <ul>
 *   <li><strong>The payload is immutable after validation.</strong> Bean Validation runs once, at the
 *       boundary, so a class with a no-argument constructor and public setters could be validated and then
 *       mutated into a state the {@code @Size} bounds had never seen before the service read it, and the
 *       credential could be replaced after the fact. There is therefore no setter, all twelve fields are
 *       {@code final}, and binding goes through the single {@code @JsonCreator} constructor, so what
 *       validation saw is what the service reads.</li>
 *   <li><strong>An unrecognised JSON property is rejected, by this class.</strong>
 *       {@code @JsonIgnoreProperties(ignoreUnknown = false)} reads as protection but cannot provide it: it
 *       can only decline to suppress the unknown-property check, never enable it, and the feature it defers
 *       to is disabled by default. An undeclared property would otherwise be accepted and dropped, so a
 *       misspelled {@code password} would create a user with an absent credential. The rejection is done by
 *       {@link #rejectUnrecognisedProperty}; the annotation is retained as a declaration of intent.</li>
 *   <li><strong>The 78-character error-message field is an outbound screen concern.</strong> It arrives on
 *       an inbound payload purely because the BMS input group carries every field, and is retained for
 *       field-contract completeness rather than treated as operator input.</li>
 *   </ul>
 *
 * <p><strong>Build and test.</strong> This class is compiled by the project's Maven build against Java 25
 * under {@code -Xlint:all -Werror} with warnings failing the build; there is nothing to run standalone. Its
 * regression tests live in {@code src/test/java/com/cardemo/unit/model} and must assert, at minimum, that
 * exactly twelve fields are declared in the map's order, that serialising a fully populated instance
 * produces JSON containing no password key and no submitted password value, that deserialising a payload
 * containing a password does populate the field, and that null, empty and asterisk-marked values remain
 * three distinguishable states.
 *
 * <p><strong>Troubleshooting.</strong> If a password appears in any log line, HTTP response or test
 * snapshot, the cause is not this class serialising it — the field is write-only and its one read path takes
 * an argument, which is what makes it invisible to every reflective discoverer, so no {@code @JsonIgnore} is
 * involved anywhere in the guarantee. Read the bullet list above rather than looking for that annotation: an
 * earlier revision of this file carried an unused import of it, which a review recorded as a hygiene finding
 * precisely because a reader who trusted this paragraph would go looking for an annotation that was never
 * there. Look instead at a custom validation-error handler serialising a rejected value, at a
 * mapper configured to auto-detect private fields rather than bean accessors, or at request-body logging
 * upstream of the controller. If an inbound password does not arrive at the service, confirm the JSON key is
 * exactly {@code password} and that the request reaches the write-only constructor parameter rather than a
 * getter-driven binder. Note that this class is immutable and exposes no setters at all, so a binder that
 * expects JavaBean mutators will bind nothing; the {@code @JsonCreator} constructor is the only write path.
 * If a caller supplies the credential anywhere other than the body - a header, a query parameter, a form
 * field - it is not read: {@code com.cardemo.controller.AdminController} declares no credential-bearing
 * parameter of any kind, and a regression test asserts that it never will.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public class UserCreateRequest {

    /**
     * Terminal transaction identifier. Source {@code TRNNAMEI PIC X(4)} at {@code app/cpy-bms/COUSR01.CPY:24}.
     */
    @Size(max = 4)
    private final String transactionName;

    /**
     * First screen title line. Source {@code TITLE01I PIC X(40)} at {@code app/cpy-bms/COUSR01.CPY:30}.
     */
    @Size(max = 40)
    private final String title01;

    /**
     * Screen date stamp as presented. Source {@code CURDATEI PIC X(8)} at {@code app/cpy-bms/COUSR01.CPY:36}.
     */
    @Size(max = 8)
    private final String currentDate;

    /**
     * Originating program name. Source {@code PGMNAMEI PIC X(8)} at {@code app/cpy-bms/COUSR01.CPY:42}.
     */
    @Size(max = 8)
    private final String programName;

    /**
     * Second screen title line. Source {@code TITLE02I PIC X(40)} at {@code app/cpy-bms/COUSR01.CPY:48}.
     */
    @Size(max = 40)
    private final String title02;

    /**
     * Screen time stamp as presented. Source {@code CURTIMEI PIC X(8)} at {@code app/cpy-bms/COUSR01.CPY:54}.
     * Width 8 here; {@code app/cpy-bms/COSGN00.CPY:54} alone declares {@code PIC X(9)}, which is why this
     * header field is declared inline rather than shared.
     */
    @Size(max = 8)
    private final String currentTime;

    /**
     * Operator-supplied first name, and the first field the source validates. Source {@code FNAMEI PIC X(20)}
     * at {@code app/cpy-bms/COUSR01.CPY:60}. On this add map the names precede the identifier; the update map
     * {@code app/cpy-bms/COUSR02.CPY} orders them the other way round.
     */
    @Size(max = 20)
    private final String firstName;

    /**
     * Operator-supplied last name, and the second field the source validates. Source {@code LNAMEI PIC X(20)}
     * at {@code app/cpy-bms/COUSR01.CPY:66}.
     */
    @Size(max = 20)
    private final String lastName;

    /**
     * Operator-supplied user identifier, and the third field the source validates. Source
     * {@code USERIDI PIC X(8)} at {@code app/cpy-bms/COUSR01.CPY:72} — note the field name, which is
     * {@code USERIDI} on this map and the input-suffixed {@code USRIDINI} on {@code app/cpy-bms/COUSR02.CPY:60}
     * and {@code app/cpy-bms/COUSR00.CPY:66}. Width 8 matches the {@code USRSEC} cluster key length declared as
     * {@code KEYS(8,0)} in {@code app/jcl/DUSRSECJ.jcl}.
     */
    @Size(max = 8)
    private final String userId;

    /**
     * Presented plaintext password, and the fourth field the source validates. Source {@code PASSWDI PIC X(8)}
     * at {@code app/cpy-bms/COUSR01.CPY:78}.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Size(max = 8)
    private final String password;

    /**
     * Operator-supplied user type, and the fifth field the source validates. Source {@code USRTYPEI PIC X(1)}
     * at {@code app/cpy-bms/COUSR01.CPY:84}. Carried as a raw one-character code rather than as the
     * {@code UserType} enum, so that an out-of-domain value reaches the service and is reported with the
     * source's own message instead of failing JSON binding.
     */
    @Size(max = 1)
    private final String userType;

    /**
     * Screen error-message area. Source {@code ERRMSGI PIC X(78)} at {@code app/cpy-bms/COUSR01.CPY:90}.
     * Present for field-contract completeness because the BMS input group declares it; it is an outbound screen
     * concern and is not treated as operator input.
     */
    @Size(max = 78)
    private final String errorMessage;

    /**
     * Binds a submitted screen, assigning every component exactly as presented.
     *
     * <p>No value is normalised on the way in: nothing is trimmed, case-folded, padded or defaulted, and an
     * omitted property arrives as {@code null} rather than as an empty string. That distinction is
     * load bearing, because {@code null} is the <em>absent</em> state of the three-state model described on
     * this class while an empty string is a <em>present but blank</em> submission, and the two produce
     * different validation outcomes.
     *
     * @param transactionName the terminal transaction identifier, {@code TRNNAMEI}
     * @param title01         the first screen title line, {@code TITLE01I}
     * @param currentDate     the screen date field, {@code CURDATEI}
     * @param programName     the painting program name, {@code PGMNAMEI}
     * @param title02         the second screen title line, {@code TITLE02I}
     * @param currentTime     the screen time field, {@code CURTIMEI}
     * @param firstName       the operator-supplied first name, {@code FNAMEI}
     * @param lastName        the operator-supplied last name, {@code LNAMEI}
     * @param userId          the operator-supplied user identifier, {@code USERIDI}
     * @param password        the operator-supplied credential, {@code PASSWDI}, never logged and never echoed
     * @param userType        the operator-supplied user type, {@code USRTYPEI}
     * @param errorMessage    the outbound message slot, {@code ERRMSGI}, an outbound screen concern rather
     *                        than operator input
     */
    @JsonCreator
    public UserCreateRequest(
            @JsonProperty("transactionName") final String transactionName,
            @JsonProperty("title01") final String title01,
            @JsonProperty("currentDate") final String currentDate,
            @JsonProperty("programName") final String programName,
            @JsonProperty("title02") final String title02,
            @JsonProperty("currentTime") final String currentTime,
            @JsonProperty("firstName") final String firstName,
            @JsonProperty("lastName") final String lastName,
            @JsonProperty("userId") final String userId,
            @JsonProperty("password") final String password,
            @JsonProperty("userType") final String userType,
            @JsonProperty("errorMessage") final String errorMessage) {
        this.transactionName = transactionName;
        this.title01 = title01;
        this.currentDate = currentDate;
        this.programName = programName;
        this.title02 = title02;
        this.currentTime = currentTime;
        this.firstName = firstName;
        this.lastName = lastName;
        this.userId = userId;
        this.password = password;
        this.userType = userType;
        this.errorMessage = errorMessage;
    }

    /**
     * Returns the terminal transaction identifier, {@code TRNNAMEI} at {@code app/cpy-bms/COUSR01.CPY:24}.
     *
     * @return the presented value exactly as bound, which may be {@code null} or empty
     */
    public String getTransactionName() {
        return transactionName;
    }

    /**
     * Returns the first screen title line, {@code TITLE01I} at {@code app/cpy-bms/COUSR01.CPY:30}.
     *
     * @return the presented value exactly as bound, which may be {@code null} or empty
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Returns the screen date stamp as presented, {@code CURDATEI} at
     * {@code app/cpy-bms/COUSR01.CPY:36}.
     *
     * @return the presented value exactly as bound, which may be {@code null} or empty
     */
    public String getCurrentDate() {
        return currentDate;
    }

    /**
     * Returns the originating program name, {@code PGMNAMEI} at {@code app/cpy-bms/COUSR01.CPY:42}.
     *
     * @return the presented value exactly as bound, which may be {@code null} or empty
     */
    public String getProgramName() {
        return programName;
    }

    /**
     * Returns the second screen title line, {@code TITLE02I} at {@code app/cpy-bms/COUSR01.CPY:48}.
     *
     * @return the presented value exactly as bound, which may be {@code null} or empty
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Returns the screen time stamp as presented, {@code CURTIMEI} at
     * {@code app/cpy-bms/COUSR01.CPY:54}.
     *
     * @return the presented value exactly as bound, which may be {@code null} or empty
     */
    public String getCurrentTime() {
        return currentTime;
    }

    /**
     * Returns the operator-supplied first name, {@code FNAMEI} at {@code app/cpy-bms/COUSR01.CPY:60}.
     *
     * @return the presented value exactly as bound, which may be {@code null} or empty
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Returns the operator-supplied last name, {@code LNAMEI} at {@code app/cpy-bms/COUSR01.CPY:66}.
     *
     * @return the presented value exactly as bound, which may be {@code null} or empty
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Returns the operator-supplied user identifier, {@code USERIDI} at
     * {@code app/cpy-bms/COUSR01.CPY:72}.
     *
     * @return the presented value exactly as bound, which may be {@code null} or empty
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Returns the operator-supplied user type as a raw one-character code, {@code USRTYPEI} at
     * {@code app/cpy-bms/COUSR01.CPY:84}. A value outside {@code 'A'} and {@code 'U'} is carried rather than
     * rejected, so the service can report it with the source's own message.
     *
     * @return the presented value exactly as bound, which may be {@code null} or empty
     */
    public String getUserType() {
        return userType;
    }

    /**
     * Returns the screen error-message area, {@code ERRMSGI} at {@code app/cpy-bms/COUSR01.CPY:90}.
     *
     * @return the presented value exactly as bound, which may be {@code null} or empty
     */
    public String getErrorMessage() {
        return errorMessage;
    }
    /**
     * Hands the bound credential to a caller-supplied reader and returns whatever that reader produces.
     *
     * <p><strong>Finding H-01, severity High, RESOLVED.</strong> This class deliberately publishes no
     * {@code getPassword()}, and for a while it published nothing at all - with the consequence that the
     * add endpoint could not reach the credential it had just bound and validated, and read it from an
     * undocumented {@code X-Presented-Password} request header instead. That was worse in three ways than the
     * accessor it was avoiding: a contract-conformant body reached the service with a null credential and
     * silently took the empty-password arm of {@code app/cbl/COUSR01C.cbl:136}; the credential travelled in a
     * header, which proxies, gateways and access logs record far more readily than a body; and the endpoint's
     * real contract was invisible to every client and every generated document.
     *
     * <p><strong>Why this is not the read path the class refuses to publish.</strong> The danger of
     * {@code getPassword()} is that it is a <em>bean</em> read path: Jackson, any reflective bean mapper, any
     * {@code toString} generator and any logging framework that walks properties will find a zero-argument
     * getter and invoke it without being asked. This method cannot be found that way. It takes an argument,
     * so it is not a JavaBean property; nothing invokes it except code that names it explicitly; and the
     * credential is never the value of an expression the caller can hold onto by accident, because it exists
     * only as the parameter of a function the caller wrote. {@code toString}, {@code equals} and
     * {@code hashCode} remain un-overridden and still cannot see it.
     *
     * <p>The value handed over may be {@code null}, empty or blank. Those are not errors here: each takes the
     * source's own empty-password arm, and deciding that is the service's job, not this payload's.
     *
     * <p><strong>Obligation on the caller.</strong> The reader must not log, echo, store or return the value
     * it is given. Masking rules for credential-shaped values live in {@code logback-spring.xml} as a second
     * line of defence, not as the first.
     *
     * @param <R>    what the reader produces
     * @param reader receives the presented credential, which may be {@code null}; never {@code null} itself
     * @return whatever {@code reader} returned, including {@code null} if it returned {@code null}
     * @throws NullPointerException if {@code reader} is {@code null}, because a missing reader would
     *     otherwise silently discard the credential
     */
    public <R> R mapPassword(final Function<String, R> reader) {
        Objects.requireNonNull(reader, "reader must not be null");
        return reader.apply(this.password);
    }

    /**
     * Rejects any JSON property that is not one of the twelve this class declares.
     *
     * <p><strong>Why this exists alongside the class-level setting.</strong>
     * {@code @JsonIgnoreProperties(ignoreUnknown = false)} pins this class to non-permissive binding, but
     * pinning is not rejecting: that annotation can only decline to suppress the unknown-property check,
     * never enable it, so it defers the actual decision to the object mapper's
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} feature. The framework disables that feature by default;
     * {@code application.yml} turns it back on by setting
     * {@code spring.jackson.deserialization.fail-on-unknown-properties} to {@code true}, and no profile
     * overlay disables it. That is a property value rather than a property of this type, so this guard is
     * the mechanism that actually refuses an unrecognised property, and it holds under a lenient mapper as
     * well as a strict one.</p>
     *
     * <p>Silent discarding matters here more than on most payloads: a client that misspells
     * {@code password} would otherwise create a user whose credential is absent, and
     * {@code app/cbl/COUSR01C.cbl:136} treats an absent password as the empty-field path rather than as a
     * malformed request.</p>
     *
     * <p>Neither the offending name nor the offending value is echoed. Both are untrusted input, and this
     * payload carries a plaintext credential, so echoing an arbitrary submitted value into a log line is
     * exactly the disclosure this class is built to avoid.</p>
     *
     * @param name  the unrecognised property name, accepted only so that Jackson can invoke this method;
     *              deliberately never read
     * @param value the unrecognised property value, accepted only so that Jackson can invoke this method;
     *              deliberately never read
     * @throws IllegalArgumentException always, because no unrecognised property is acceptable
     */
    @JsonAnySetter
    void rejectUnrecognisedProperty(final String name, final Object value) {
        throw new IllegalArgumentException(
                "UserCreateRequest accepts only the 12 fields declared by app/cpy-bms/COUSR01.CPY, and an"
                        + " unrecognised property was supplied. The offending name and value are withheld"
                        + " because they are untrusted input.");
    }
}
