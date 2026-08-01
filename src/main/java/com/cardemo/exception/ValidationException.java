/*
 * ******************************************************************
 * Program     : ValidationException.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 exception
 * Function    : Field-level validation failure, replacing the CSSETATY per-field error-marker template.
 * Source      : app/cpy/CSSETATY.cpy @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L510-L528 @ 7756d89
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
package com.cardemo.exception;

/**
 * A validation failure that identifies the input that was rejected and how it was rejected.
 *
 * <p><strong>What it does.</strong> It is the typed replacement for the per-field error markers that
 * the frozen legacy corpus produced from one parameterised copybook, {@code app/cpy/CSSETATY.cpy}. That
 * copybook is the single member of this exception package's source set whose content is
 * <em>procedural</em> rather than a record layout: it is a {@code PROCEDURE DIVISION} fragment resolved
 * by {@code COPY ... REPLACING} at each use site, so it has no entity and no data transfer object
 * counterpart and contributes no import anywhere in the Java tree. What it contributes instead is a
 * <em>contract</em>: after a screen's edits have run, every field that failed is marked, and a field
 * that failed because it was left empty is marked differently from a field that failed because the
 * value supplied was wrong. This class carries exactly that pair of facts - which field, and which of
 * the two failure kinds - and nothing else.
 *
 * <p><strong>Citation convention.</strong> Every legacy claim below cites a path and a line or line
 * range in the frozen corpus, and all of them are keyed to the traceability anchor commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, short {@code 7756d89}, exactly as the two
 * {@code Source} lines in the file header record. The anchor is stated once here rather than repeated
 * on each citation. Filename case is reproduced as it appears on disk, which is load bearing because
 * the corpus is not uniform: in {@code app/cpy} only {@code COSTM01.CPY} uses an uppercase extension,
 * so {@code app/cpy/CSSETATY.cpy} is cited in lowercase throughout. Every count quoted below was
 * measured on the anchor commit rather than inherited from prose.
 *
 * <h2>The source template, verbatim</h2>
 *
 * <p>{@code app/cpy/CSSETATY.cpy} is 30 lines: a partial Apache banner at L1-L16 - partial because it
 * carries no program, application, type or function block - then the procedural fragment at L17-L27,
 * one comment line and ten lines of code, reproduced here exactly as it appears on disk with the fixed
 * column COBOL indentation preserved:
 *
 * <pre>
 *       *    Set (TESTVAR1) to red if in error and * if blankACSHLIM
 *            IF (FLG-(TESTVAR1)-NOT-OK
 *            OR  FLG-(TESTVAR1)-BLANK)
 *            AND CDEMO-PGM-REENTER
 *                MOVE DFHRED             TO
 *                     (SCRNVAR2)C OF (MAPNAME3)O
 *                IF  FLG-(TESTVAR1)-BLANK
 *                    MOVE '*'            TO
 *                     (SCRNVAR2)O OF (MAPNAME3)O
 *                END-IF
 *            END-IF
 * </pre>
 *
 * <p>Three placeholder tokens are substituted at each use site. Measured with
 * {@code grep -c "COPY CSSETATY" app/cbl/COACTUPC.cbl}, the template is expanded at
 * <strong>39 sites</strong>, spanning {@code app/cbl/COACTUPC.cbl:L3208-L3432}, and
 * {@code grep -rl CSSETATY app/} shows that account update is the <em>only</em> program in the corpus
 * that uses it. A representative expansion, {@code app/cbl/COACTUPC.cbl:L3208-L3211}:
 *
 * <pre>
 *            COPY CSSETATY REPLACING
 *              ==(TESTVAR1)== BY ==ACCT-STATUS==
 *              ==(SCRNVAR2)== BY ==ACSTTUS==
 *              ==(MAPNAME3)== BY ==CACTUPA== .
 * </pre>
 *
 * <p>So {@code (TESTVAR1)} names the validation flag, {@code (SCRNVAR2)} names the screen field and
 * {@code (MAPNAME3)} names the map that owns it. In the Java target the map is gone and the field
 * becomes a data transfer object property, so the two identity tokens collapse into the single
 * {@link #getFieldName() field name} this class carries.
 *
 * <h2>Why there are exactly two failure kinds, and why collapsing them is wrong</h2>
 *
 * <p><strong>Finding, severity Medium.</strong> The obvious translation of the template is a single
 * "this field is invalid" state, and that translation is lossy. The flag the template tests is a one
 * byte field carrying <em>three</em> condition names. The first such flag of the account update
 * program's non-key group, verbatim from {@code app/cbl/COACTUPC.cbl:L191-L195} - the same triple
 * repeats for the credit limit at {@code :L196-L199} and for every other edited field:
 *
 * <pre>
 *          05 WS-NON-KEY-FLAGS.
 *            10  WS-EDIT-ACCT-STATUS                 PIC  X(1).
 *                88  FLG-ACCT-STATUS-ISVALID         VALUES 'Y', 'N'.
 *                88  FLG-ACCT-STATUS-NOT-OK          VALUE '0'.
 *                88  FLG-ACCT-STATUS-BLANK           VALUE 'B'.
 * </pre>
 *
 * <p>One of the three is success and raises nothing. The other two are both failures, both are tested
 * by the template's outer condition at {@code app/cpy/CSSETATY.cpy:L18-L19}, and both paint the field.
 * But the template's <em>inner</em> condition at L23 fires for the blank case alone, writing an
 * additional {@code '*'} into the field. The source therefore distinguishes them deliberately, and it
 * distinguishes them again in its cursor placement at {@code app/cbl/COACTUPC.cbl:L3013-L3019}, where
 * {@code -NOT-OK} and {@code -BLANK} appear as two separate {@code WHEN} branches of one
 * {@code EVALUATE TRUE}.
 *
 * <p>The clinching evidence is the message catalogue, which carries two different texts for one field:
 * {@code app/cbl/COACTUPC.cbl:L505-L506} declares {@code CRED-LIMIT-IS-BLANK} with the literal
 * {@code 'Credit Limit must be supplied'}, while {@code :L507-L508} declares
 * {@code CRED-LIMIT-IS-NOT-VALID} with {@code 'Credit Limit is not valid'}. A single collapsed state
 * could not choose between them. Remediation, applied here: model the distinction explicitly as
 * {@link FailureKind}, so a caller can always tell a rejected value from an absent one.
 *
 * <h2>Three mechanism substitutions, documented so nothing looks lost</h2>
 *
 * <p>A reviewer comparing this class against the template will find three source behaviours with no
 * Java counterpart. None was dropped by accident; each is a deliberate substitution, and each is
 * recorded in {@code DECISION_LOG.md}:
 *
 * <ul>
 *   <li><strong>The re-entry guard is not reproduced.</strong> The template only marks a field when
 *       {@code AND CDEMO-PGM-REENTER} holds, at {@code app/cpy/CSSETATY.cpy:L20} - that is, on a
 *       re-display of a pseudo-conversational screen. The enter versus re-enter flag lives in the
 *       COMMAREA and has no equivalent in a stateless target; it collapses into ordinary stateless
 *       request handling. Every request is evaluated on its own and every failure it produces is
 *       reported, so there is no state in which a detected failure goes unreported. This is strictly
 *       more informative than the source, never less.</li>
 *   <li><strong>The highlight attribute is not reproduced.</strong> {@code MOVE DFHRED} at
 *       {@code app/cpy/CSSETATY.cpy:L21-L22} writes a 3270 attribute byte supplied by the transaction
 *       monitor into the screen field's colour position. There is no green screen rendering in scope -
 *       the interface is JSON over HTTP - so there is no colour to set. What {@code DFHRED} expressed,
 *       namely "this field is in error and must be corrected", is carried by the existence of this
 *       exception and by its {@link FailureKind}. No colour, attribute byte or presentation field is
 *       invented here.</li>
 *   <li><strong>The asterisk marker is not reproduced.</strong> {@code MOVE '*'} at
 *       {@code app/cpy/CSSETATY.cpy:L24-L25} writes a literal into the screen field's output position
 *       to flag an empty entry. That is presentation, and it is the presentation of a screen that no
 *       longer exists. Its <em>meaning</em> - required but absent - is preserved as
 *       {@link FailureKind#BLANK}, which is the whole reason that constant exists separately.</li>
 * </ul>
 *
 * <p><strong>Finding, severity Low.</strong> The one byte flag's literal values are tempting to carry
 * on {@link FailureKind} as evidence, and doing so would be wrong, because they are not uniform across
 * the program. {@code app/cbl/COACTUPC.cbl:L186} declares {@code FLG-ACCTFILTER-BLANK VALUE ' '} - a
 * space - whereas {@code :L195} declares {@code FLG-ACCT-STATUS-BLANK VALUE 'B'}; the success
 * condition is variously {@code VALUE '1'} at {@code :L184}, {@code VALUES 'Y', 'N'} at {@code :L193}
 * and {@code VALUE LOW-VALUES} at {@code :L197}. Remediation, applied here: carry the failure
 * <em>kind</em> and not the byte, so no uniformity is fabricated. A second Low finding is recorded for
 * completeness and deliberately not corrected: the comment at {@code app/cpy/CSSETATY.cpy:L17} ends
 * with the field name {@code ACSHLIM} run directly onto the end of the word "blank", a legacy editing
 * artefact that is quoted above exactly as it appears because the corpus is frozen.
 *
 * <h2>Security contract for callers: carry the name, never the value</h2>
 *
 * <p><strong>Finding, severity Blocker if violated.</strong> This is the highest risk class in its
 * package for Rule 1 Clause D, "No secrets in code, logs, tests, or config", because the natural
 * implementation of a validation failure echoes the rejected input back so the user can see what was
 * wrong - and the rejected input is very often exactly the value that must never be logged. Exception
 * messages are logged.
 *
 * <p>The design forecloses the mistake structurally: <strong>this class has no field for a rejected
 * value, no constructor parameter that accepts one, and no accessor that could return one.</strong> It
 * carries a field <em>name</em>, which is a trusted internal identifier chosen by the developer, and
 * never a field <em>value</em>, which is untrusted request data. Callers must honour the same rule in
 * the message they supply. The values at risk, with their declared widths, are
 * {@code CUST-PHONE-NUM-1 PIC X(15)} and {@code CUST-PHONE-NUM-2 PIC X(15)} at
 * {@code app/cpy/CVCUS01Y.cpy:L15-L16}, {@code CUST-SSN PIC 9(09)} at {@code :L17},
 * {@code CUST-GOVT-ISSUED-ID PIC X(20)} at {@code :L18}, {@code CUST-DOB-YYYY-MM-DD PIC X(10)} at
 * {@code :L19} and {@code CUST-EFT-ACCOUNT-ID PIC X(10)} at {@code :L20}, together with
 * {@code SEC-USR-PWD PIC X(08)} from {@code app/cpy/CSUSR01Y.cpy} and the sixteen character card
 * number. Remediation for a violation: name the field, state the rule that was broken, and stop. Write
 * {@code missingField("cardExpiryMonth", "Card expiry month must be supplied")}, never a message that
 * interpolates what the caller actually typed.
 *
 * <h2>Where the message text comes from</h2>
 *
 * <p>The message is supplied by the caller and is stored and returned unchanged. It is <em>not</em>
 * catalogued here, and that is deliberate: the legacy texts are per program screen literals which the
 * parity gates compare byte for byte, so each service must own and reproduce its own exact strings.
 * Duplicating them in this class would create a second source of truth for a value that has to match
 * the legacy baseline. For orientation only, the account update program declares its texts as
 * condition names on a message field - {@code app/cbl/COACTUPC.cbl:L509-L510}
 * {@code THIS-MONTH-NOT-VALID} is {@code 'Card expiry month must be between 1 and 12'},
 * {@code :L511-L512} {@code THIS-YEAR-NOT-VALID} is {@code 'Invalid card expiry year'} and
 * {@code :L515-L516} {@code DID-NOT-FIND-ACCTCARD-COMBO} is
 * {@code 'Did not find cards for this search condition'}.
 *
 * <p>For the same reason {@link Throwable#getMessage()} is <strong>not</strong> overridden and the
 * field name is <strong>not</strong> appended to the message. A caller that needs both reads them from
 * the two accessors and composes its own response; a caller that byte compares the message against a
 * legacy baseline gets exactly the string the service supplied.
 *
 * <h2>What this class deliberately does not carry</h2>
 *
 * <ul>
 *   <li><strong>No reject code.</strong> The batch reject codes are business outcomes that drive a
 *       batch exit status and are written to a reject record; they are never thrown, and this class
 *       neither imports, accepts, stores nor exposes them. Two of them look exactly like validation
 *       failures - an over limit transaction and a transaction received after account expiry - and
 *       that resemblance is the trap. The source tests them in two sequential unguarded blocks with no
 *       early exit, so when both conditions fail the later code overwrites the earlier one and a single
 *       reject record is written. An exception based design cannot reproduce that overwrite, because
 *       the first throw would abandon the second test.</li>
 *   <li><strong>No ordering.</strong> The legacy program evaluates its fields in a fixed order and
 *       marks each independently, and that order is observable. It is preserved by the services that
 *       run the edits, which is where the order actually lives; this class answers only which field
 *       failed and how. It carries no index, sequence number or position, so no call site can come to
 *       depend on this class for an ordering guarantee it cannot make.</li>
 *   <li><strong>No HTTP status and no numeric code.</strong> Status selection is contextual and belongs
 *       to the controllers. Binding a status here, whether as a field or as an annotation, would fix
 *       one mapping for every call site.</li>
 *   <li><strong>No annotation of any kind, and no logging.</strong> An exception is thrown; the caller
 *       logs it. A logger here would double log every failure and would put a side effect in a
 *       constructor.</li>
 *   <li><strong>No coupling to the bean validation framework.</strong> No type from that framework is
 *       imported, accepted or exposed. This is a plain typed throwable, so a service that validates by
 *       hand - which is what the paragraph by paragraph translation of the legacy edits produces - can
 *       raise it without dragging in a constraint model it does not use. A controller adapting a
 *       framework level failure passes the original throwable as the {@code cause} of
 *       {@link #ValidationException(String, String, FailureKind, Throwable)}.</li>
 *   <li><strong>No custom serialization hook.</strong> Both fields are serializable - a
 *       {@link String} and an enum constant - and the default mechanism inherited from
 *       {@link Throwable} reconstructs them. Rule 1 Clause D names insecure deserialization as a
 *       pattern to flag, so no object stream callback, serialization proxy, instance replacement method
 *       or alternative externalization contract is defined, and no instance of this class is ever
 *       rebuilt from untrusted input. The pinned {@code serialVersionUID} below exists to satisfy the
 *       compiler lint, not to enable a wire format.</li>
 * </ul>
 *
 * <h2>Null and blank handling</h2>
 *
 * <p>Rule 1 Clause B requires null and empty cases to be handled explicitly, so the policy is stated
 * rather than left implicit, and it matches {@link CardDemoException} so that the whole package behaves
 * uniformly:
 *
 * <ul>
 *   <li><strong>A null or blank message is permitted and passed through unchanged.</strong></li>
 *   <li><strong>A null cause is permitted</strong> and records that there is no underlying throwable to
 *       attribute the failure to.</li>
 *   <li><strong>A null or blank field name is permitted and passed through unchanged.</strong> It means
 *       the failure is a request level rejection that no single field owns - a cross field rule, or a
 *       body that could not be interpreted at all. {@link #hasFieldName()} is the explicit test for
 *       that case and treats null and blank alike, so no caller has to guess.</li>
 *   <li><strong>A null failure kind is the one substituted argument</strong>, and it becomes
 *       {@link FailureKind#INVALID}. The reasoning is given on {@link #getFailureKind()}.</li>
 * </ul>
 *
 * <p>No argument is rejected and no constructor throws. Validating the reporter is not worth losing the
 * report: an {@link IllegalArgumentException} raised while a real failure is being constructed would
 * replace that failure with a spurious one and destroy the root cause the package exists to preserve.
 *
 * <h2>Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>A caller cannot tell an empty input from a wrong one.</em> The throwing site used a message
 *       only constructor, so the kind defaulted to {@link FailureKind#INVALID}. Switch it to
 *       {@link #missingField(String, String)} when the input was absent.</li>
 *   <li><em>{@link #getFieldName()} returns null where a field was expected.</em> The throwing site
 *       used a request level constructor. Use a field carrying factory or constructor; the name is
 *       never derived, defaulted or guessed.</li>
 *   <li><em>{@link Throwable#getCause()} returns null after adapting another failure.</em> The throwing
 *       site used a constructor without a cause parameter inside a {@code catch}. Every cause carrying
 *       form delegates the cause to {@code super}, and this class never drops one it was handed.</li>
 *   <li><em>A response echoes a password, a social security number or a card number.</em> The message
 *       supplied by the throwing site interpolated the rejected value. This class cannot cause that and
 *       cannot prevent it; fix the message at the throwing site, and treat the leak as a Blocker.</li>
 *   <li><em>The build fails with a {@code serial} warning after an edit.</em> The
 *       {@code serialVersionUID} declaration below was removed or renamed. This type is a
 *       {@link java.io.Serializable} descendant through {@link Throwable}, and the build compiles with
 *       {@code -Xlint:all}, {@code -Werror} and {@code failOnWarning}, so the declaration is mandatory
 *       rather than advisory.</li>
 * </ul>
 *
 * <h2>Determinism, dependencies and testing</h2>
 *
 * <p>Instances are immutable: both fields are {@code final}, both field types are themselves immutable,
 * no setter exists and no accessor returns anything a caller could mutate. There is no static mutable
 * state. The class performs no case conversion, no formatting, no parsing and no time or charset
 * dependent work of any kind, so no locale, clock, time zone or default charset can influence its
 * behaviour and no {@link java.util.Locale} argument is needed anywhere - which is why this file has no
 * imports at all. Two instances built from the same arguments are indistinguishable through their
 * accessors, which is what makes a byte comparing parity gate reproducible.
 *
 * <p>This class depends only on {@link CardDemoException} and {@code java.lang}: no framework type, no
 * third party library and no other CardDemo package, so it cannot participate in a cycle. Nothing here
 * is configurable - there is no property, no environment variable and no profile that changes its
 * behaviour - and the only default it applies is the null discriminator becoming
 * {@link FailureKind#INVALID}, described on {@link #getFailureKind()}.
 *
 * <p>Build and test it with the repository's pinned wrapper: {@code ./mvnw -B clean compile} to compile
 * and {@code ./mvnw -B clean test} to run the unit tier. Compilation is deliberately unforgiving -
 * {@code release} 25 with {@code -Xlint:all}, {@code -Werror} and {@code failOnWarning} - so any warning
 * introduced here fails the build rather than scrolling past. Tests for this class live under
 * {@code src/test/java/com/cardemo/unit} and never in this package.
 *
 * @see CardDemoException
 */
public class ValidationException extends CardDemoException {

    /**
     * Fixed serialization identity. {@link Throwable} already implements
     * {@link java.io.Serializable}, so every type in this hierarchy is unavoidably serializable and
     * must pin this value explicitly: the build runs {@code -Xlint:all} with {@code -Werror} and
     * {@code failOnWarning}, which turns the {@code serial} lint into a compilation failure. A fixed
     * literal is used rather than a computed default so that the identity does not shift when the class
     * is edited.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The two ways a single input can fail, transcribed from the outer and inner conditions of
     * {@code app/cpy/CSSETATY.cpy:L18-L26}.
     *
     * <p>The template tests a one byte flag that carries three condition names - success, wrong, and
     * empty - declared at {@code app/cbl/COACTUPC.cbl:L191-L199}. Success raises no exception at all, so
     * only the other two are represented here. Both are failures and both were painted
     * {@code DFHRED} by the source; the difference is the inner {@code IF} at
     * {@code app/cpy/CSSETATY.cpy:L23}, which writes an additional {@code '*'} for the empty case alone.
     *
     * <p>The constants carry no underlying byte value on purpose. The literals the source assigns are
     * not uniform - {@code VALUE ' '} at {@code app/cbl/COACTUPC.cbl:L186} against {@code VALUE 'B'} at
     * {@code :L195} for the same condition name suffix - so exposing a byte would fabricate a uniformity
     * the corpus does not have. The distinction that <em>is</em> stable, and the only one callers need,
     * is which of these two constants applies.
     *
     * <p>This enum is nested rather than promoted to its own file because it has no meaning away from
     * this exception: it is not a domain concept, it is this class's discriminator.
     */
    public enum FailureKind {

        /**
         * A value was supplied and it is wrong: the transcription of {@code FLG-(TESTVAR1)-NOT-OK} at
         * {@code app/cpy/CSSETATY.cpy:L18}.
         *
         * <p>Use this for a value that failed a format, range, membership or cross field rule - a
         * non-numeric account number, a month outside one to twelve, a status that is neither of the
         * two accepted codes. The corresponding legacy text pattern is
         * {@code app/cbl/COACTUPC.cbl:L507-L508}, {@code CRED-LIMIT-IS-NOT-VALID}, whose literal is
         * {@code 'Credit Limit is not valid'}.
         *
         * <p>This is also the kind recorded for a request level failure that no single field owns, and
         * the kind substituted when a caller passes a null discriminator, because it is the weaker of
         * the two claims: it asserts only that the input is unacceptable, never that it was absent.
         */
        INVALID,

        /**
         * A required value was not supplied at all: the transcription of
         * {@code FLG-(TESTVAR1)-BLANK} at {@code app/cpy/CSSETATY.cpy:L19}, the condition that the
         * template's inner {@code IF} at L23 singles out in order to write {@code '*'} into the field.
         *
         * <p>Use this only when the input is genuinely absent - null, empty, or whitespace where the
         * legacy field would have held spaces or low values. The corresponding legacy text pattern is
         * {@code app/cbl/COACTUPC.cbl:L505-L506}, {@code CRED-LIMIT-IS-BLANK}, whose literal is
         * {@code 'Credit Limit must be supplied'}. That the same field has a second, different literal
         * for the wrong-value case is the evidence that these two constants must stay separate.
         */
        BLANK
    }

    /**
     * The name of the input this failure attaches to, or null when no single input owns it.
     *
     * <p>It stands in for the pair {@code (SCRNVAR2) OF (MAPNAME3)} that the template substituted at
     * {@code app/cpy/CSSETATY.cpy:L22}, collapsed to one token because the target has data transfer
     * objects and no maps. It is always a developer chosen identifier and never request data; see the
     * security contract in the class documentation.
     */
    private final String fieldName;

    /**
     * Which of the two failure conditions of {@code app/cpy/CSSETATY.cpy:L18-L19} applies. Never null:
     * every constructor either receives a non-null value or substitutes {@link FailureKind#INVALID}.
     */
    private final FailureKind failureKind;

    /**
     * Creates a request level validation failure with no field identity and no underlying throwable.
     *
     * <p>Use this form when the rejection genuinely belongs to the request as a whole rather than to one
     * input: a cross field rule that no single field owns, or a body that could not be interpreted at
     * all. {@link #getFieldName()} then returns null and {@link #hasFieldName()} returns false, which is
     * the documented request level shape rather than a missing value. The kind is recorded as
     * {@link FailureKind#INVALID}, which is exact for this case - something was supplied and it is
     * unacceptable.
     *
     * <p>Prefer {@link #invalidField(String, String)} or {@link #missingField(String, String)} whenever
     * a specific input is at fault; a named field is what makes a caller's structured log queryable.
     * Inside a {@code catch} block use a form that takes a cause instead, because discarding the caught
     * throwable is the swallowing that Rule 1 Clause B forbids.
     *
     * <p>Side effects: none beyond throwable construction. Nothing is logged, no metric is recorded and
     * no state outside this instance is read or written.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}. Permitted
     *                to be null or blank and passed through unchanged. It must not contain a secret, a
     *                credential or a personally identifiable value, and in particular must not echo the
     *                rejected input, because exception messages are logged
     */
    public ValidationException(String message) {
        super(message);
        this.fieldName = null;
        this.failureKind = FailureKind.INVALID;
    }

    /**
     * Creates a request level validation failure that preserves the throwable which caused it.
     *
     * <p>This is the form to use when a request as a whole could not be validated because something
     * else failed first - a body that would not deserialize, a lookup that a cross field rule depended
     * on. The message supplies the CardDemo level context and the cause retains the original failure,
     * unmodified and fully navigable through {@link Throwable#getCause()}, which is the direct
     * implementation of Rule 1 Clause B's requirement to wrap with context and preserve the root cause.
     *
     * <p>Side effects: none beyond throwable construction. The cause is neither inspected, unwrapped,
     * re-thrown nor logged; it is delegated to {@link CardDemoException} exactly as supplied.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}. Permitted
     *                to be null or blank and passed through unchanged. It must not echo the rejected
     *                input or carry any secret or personally identifiable value
     * @param cause   the underlying throwable, retrievable through {@link Throwable#getCause()}.
     *                Permitted to be null, which records that there is no underlying failure to
     *                attribute. When non-null it is always retained; this class never drops a cause it
     *                was handed
     */
    public ValidationException(String message, Throwable cause) {
        super(message, cause);
        this.fieldName = null;
        this.failureKind = FailureKind.INVALID;
    }

    /**
     * Creates a validation failure for a named input, with no underlying throwable.
     *
     * <p>This is the general field level form: it reproduces one expansion of the template, where
     * {@code (SCRNVAR2)} named the field and the flag tested by {@code (TESTVAR1)} said which of the two
     * failure conditions had been detected. The two factory methods
     * {@link #invalidField(String, String)} and {@link #missingField(String, String)} are the preferred
     * entry points because they cannot be called with the wrong discriminator; use this constructor when
     * the kind is itself computed.
     *
     * <p>Side effects: none beyond throwable construction. Nothing is logged and no state outside this
     * instance is read or written.
     *
     * @param message     the detail message, retrievable through {@link Throwable#getMessage()}.
     *                    Permitted to be null or blank and passed through unchanged. It must name the
     *                    rule that was broken and must never echo the rejected value
     * @param fieldName   the name of the offending input, retrievable through {@link #getFieldName()}.
     *                    Permitted to be null or blank and passed through unchanged, in which case the
     *                    failure is treated as request level and {@link #hasFieldName()} returns false.
     *                    It must be a developer chosen identifier, never request data
     * @param failureKind which of the two failure conditions applies. Permitted to be null, in which
     *                    case {@link FailureKind#INVALID} is substituted for the reasons given on
     *                    {@link #getFailureKind()}
     */
    public ValidationException(String message, String fieldName, FailureKind failureKind) {
        super(message);
        this.fieldName = fieldName;
        this.failureKind = failureKind == null ? FailureKind.INVALID : failureKind;
    }

    /**
     * Creates a validation failure for a named input and preserves the throwable which caused it.
     *
     * <p>This is the form a controller uses when it adapts a failure raised elsewhere - most obviously a
     * bean validation constraint violation, which this package deliberately does not import - into the
     * typed vocabulary of this hierarchy. The adapted throwable is passed here as {@code cause} so that
     * the original is preserved in full, and the field name and kind are extracted by the adapting code
     * rather than by this class.
     *
     * <p>Side effects: none beyond throwable construction. The cause is neither inspected, unwrapped,
     * re-thrown nor logged; it is delegated to {@link CardDemoException} exactly as supplied.
     *
     * @param message     the detail message, retrievable through {@link Throwable#getMessage()}.
     *                    Permitted to be null or blank and passed through unchanged. It must never echo
     *                    the rejected value
     * @param fieldName   the name of the offending input, retrievable through {@link #getFieldName()}.
     *                    Permitted to be null or blank and passed through unchanged, in which case the
     *                    failure is treated as request level. It must be a developer chosen identifier,
     *                    never request data
     * @param failureKind which of the two failure conditions applies. Permitted to be null, in which
     *                    case {@link FailureKind#INVALID} is substituted
     * @param cause       the underlying throwable, retrievable through {@link Throwable#getCause()}.
     *                    Permitted to be null. When non-null it is always retained
     */
    public ValidationException(String message, String fieldName, FailureKind failureKind,
            Throwable cause) {
        super(message, cause);
        this.fieldName = fieldName;
        this.failureKind = failureKind == null ? FailureKind.INVALID : failureKind;
    }

    /**
     * Reports that a named input was supplied and is wrong.
     *
     * <p>This is the {@code FLG-(TESTVAR1)-NOT-OK} half of the template's outer condition at
     * {@code app/cpy/CSSETATY.cpy:L18}. It exists so that a validating service states its intent at the
     * call site and cannot pass the wrong discriminator: the resulting {@link #getFailureKind()} is
     * always {@link FailureKind#INVALID}.
     *
     * <p>The returned exception is <em>not</em> thrown. Returning it rather than throwing keeps this
     * method usable from a stream, an {@code orElseThrow} and a plain {@code throw} alike, and keeps the
     * throwing decision at the call site where the control flow is visible.
     *
     * <p>Side effects: none. The instance is constructed and returned; nothing is logged, no metric is
     * recorded and no state outside the new instance is read or written.
     *
     * @param fieldName the name of the offending input. Permitted to be null or blank and passed through
     *                  unchanged, in which case the failure is request level and {@link #hasFieldName()}
     *                  returns false. It must be a developer chosen identifier, never request data
     * @param message   the detail message. Permitted to be null or blank and passed through unchanged. It
     *                  must state the rule that was broken and must never echo the rejected value, which
     *                  may be a password, a social security number or a card number
     * @return a new exception carrying the supplied name with {@link FailureKind#INVALID}; never null
     */
    public static ValidationException invalidField(String fieldName, String message) {
        return new ValidationException(message, fieldName, FailureKind.INVALID);
    }

    /**
     * Reports that a required input was not supplied at all.
     *
     * <p>This is the {@code FLG-(TESTVAR1)-BLANK} half of the template's outer condition at
     * {@code app/cpy/CSSETATY.cpy:L19}, the condition that the inner {@code IF} at L23 singles out to
     * write {@code '*'} into the screen field. The marker itself has no counterpart here - there is no
     * screen - but the distinction it drew is preserved, because the resulting
     * {@link #getFailureKind()} is always {@link FailureKind#BLANK}.
     *
     * <p>Use this only for a genuinely absent input. A value that is present but unacceptable is
     * {@link #invalidField(String, String)}; the source keeps two separate messages for those two cases
     * on one field, at {@code app/cbl/COACTUPC.cbl:L505-L508}, so conflating them loses information the
     * legacy screen displayed.
     *
     * <p>The returned exception is <em>not</em> thrown, for the reasons given on
     * {@link #invalidField(String, String)}.
     *
     * <p>Side effects: none. The instance is constructed and returned.
     *
     * @param fieldName the name of the absent input. Permitted to be null or blank and passed through
     *                  unchanged. It must be a developer chosen identifier, never request data
     * @param message   the detail message. Permitted to be null or blank and passed through unchanged. A
     *                  required-value message has no value to echo, so it must name the field and stop
     * @return a new exception carrying the supplied name with {@link FailureKind#BLANK}; never null
     */
    public static ValidationException missingField(String fieldName, String message) {
        return new ValidationException(message, fieldName, FailureKind.BLANK);
    }

    /**
     * Returns the name of the input this failure attaches to.
     *
     * <p>It is the Java counterpart of the {@code (SCRNVAR2) OF (MAPNAME3)} pair that the template
     * substituted at {@code app/cpy/CSSETATY.cpy:L22}, collapsed to a single token because the target has
     * data transfer objects and no maps. The value is returned exactly as it was supplied: it is never
     * trimmed, re-cased, defaulted or derived, so no locale can influence it and a caller comparing it
     * against a known property name gets a deterministic answer.
     *
     * <p>Side effects: none. This is a pure read of an immutable field.
     *
     * @return the field name, or null when the failure is request level. It may also be blank if a caller
     *         supplied a blank name; use {@link #hasFieldName()} rather than a null check to cover both
     */
    public String getFieldName() {
        return fieldName;
    }

    /**
     * Returns which of the two failure conditions applies, distinguishing a wrong value from an absent
     * one.
     *
     * <p><strong>Never null</strong>, so a caller may switch on it without a guard. Where a null
     * discriminator was supplied, or where no field level kind was given at all, the value is
     * {@link FailureKind#INVALID}. That substitution is the one place this class does not simply pass an
     * argument through, and it is deliberate for three reasons: a constructor of a failure type must
     * never throw, or it replaces the real failure with a spurious argument failure at the exact moment
     * the real one is being reported; a null discriminator would force every call site into a null check
     * or risk a {@link NullPointerException} on a {@code switch}; and {@link FailureKind#INVALID} is the
     * weaker of the two claims, asserting only that the input is unacceptable and never that it was
     * absent, so defaulting to it can never over-claim.
     *
     * <p>Side effects: none. This is a pure read of an immutable field.
     *
     * @return the failure kind; never null
     */
    public FailureKind getFailureKind() {
        return failureKind;
    }

    /**
     * Reports whether this failure identifies a specific input.
     *
     * <p>This is the explicit test for the request level case that Rule 1 Clause B requires, and it is
     * the reason callers need not decide for themselves what a blank name means. A field name is treated
     * as present only when it is non-null <em>and</em> contains at least one non-whitespace character,
     * so null and blank are handled identically: both describe a failure that no single input owns.
     * Encoding that in one place keeps the two spellings of "absent" from being tested inconsistently
     * across call sites.
     *
     * <p>Blankness is decided by {@link String#isBlank()}, which classifies whitespace by Unicode
     * property and not by locale, so this method is locale, clock and charset independent like the rest
     * of the class.
     *
     * <p>Side effects: none. This is a pure function of an immutable field.
     *
     * @return true when a usable field name is present, false when the name is null or blank and the
     *         failure is therefore request level
     */
    public boolean hasFieldName() {
        return fieldName != null && !fieldName.isBlank();
    }
}
