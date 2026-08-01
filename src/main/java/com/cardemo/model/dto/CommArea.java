/*
 * ******************************************************************
 * Program     : CommArea.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 data transfer object
 * Function    : Live projection of the CICS COMMAREA; routing, re-entry and screen-state fields omitted.
 * Source      : app/cpy/COCOM01Y.cpy (9 live fields of 17 declared) @ 7756d89
 * Source      : app/cpy/CVACT02Y.cpy:L5 @ 7756d89 (card number type divergence)
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

import com.cardemo.model.enums.UserType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

/**
 * The live business payload of the CICS communication area, projected as an immutable value.
 *
 * <p>The source is {@code 01 CARDDEMO-COMMAREA.} at {@code app/cpy/COCOM01Y.cpy:L19}, whose own header
 * comment at {@code app/cpy/COCOM01Y.cpy:L2} describes it as the "Communication area for CardDemo
 * application programs". In the legacy system that structure was the sole carrier of state between
 * pseudo-conversational turns: it was declared in the LINKAGE SECTION, handed to every program reached by
 * {@code EXEC CICS XCTL}, and returned to the terminal on {@code RETURN TRANSID ... COMMAREA}.
 *
 * <p><strong>This type is deliberately not that carrier.</strong> The target holds no server-side session
 * state, so only the fields that carry <em>business</em> identity survive the translation. The fields whose
 * sole purpose was to carry <em>conversation</em> state have nothing left to carry and are omitted outright
 * rather than retained as unused members. Nine of the seventeen declarations are projected here; the eight
 * that are not are enumerated below with the reason for each, so that a reader comparing this file against
 * the copybook side by side can see that nothing was lost by accident.
 *
 * <h2>Verified source layout</h2>
 *
 * <p>Transcribed from {@code app/cpy/COCOM01Y.cpy}, with the disposition of every declaration marked:
 *
 * <pre>
 * 01 CARDDEMO-COMMAREA.                                    &lt;- L19
 *    05 CDEMO-GENERAL-INFO.                                &lt;- L20
 *       10 CDEMO-FROM-TRANID             PIC X(04).        &lt;- L21   OMIT
 *       10 CDEMO-FROM-PROGRAM            PIC X(08).        &lt;- L22   OMIT
 *       10 CDEMO-TO-TRANID               PIC X(04).        &lt;- L23   OMIT
 *       10 CDEMO-TO-PROGRAM              PIC X(08).        &lt;- L24   OMIT
 *       10 CDEMO-USER-ID                 PIC X(08).        &lt;- L25   INCLUDE
 *       10 CDEMO-USER-TYPE               PIC X(01).        &lt;- L26   INCLUDE
 *          88 CDEMO-USRTYP-ADMIN         VALUE 'A'.        &lt;- L27
 *          88 CDEMO-USRTYP-USER          VALUE 'U'.        &lt;- L28
 *       10 CDEMO-PGM-CONTEXT             PIC 9(01).        &lt;- L29   OMIT
 *          88 CDEMO-PGM-ENTER            VALUE 0.          &lt;- L30   OMIT
 *          88 CDEMO-PGM-REENTER          VALUE 1.          &lt;- L31   OMIT
 *    05 CDEMO-CUSTOMER-INFO.                               &lt;- L32
 *       10 CDEMO-CUST-ID                 PIC 9(09).        &lt;- L33   INCLUDE
 *       10 CDEMO-CUST-FNAME              PIC X(25).        &lt;- L34   INCLUDE
 *       10 CDEMO-CUST-MNAME              PIC X(25).        &lt;- L35   INCLUDE
 *       10 CDEMO-CUST-LNAME              PIC X(25).        &lt;- L36   INCLUDE
 *    05 CDEMO-ACCOUNT-INFO.                                &lt;- L37
 *       10 CDEMO-ACCT-ID                 PIC 9(11).        &lt;- L38   INCLUDE
 *       10 CDEMO-ACCT-STATUS             PIC X(01).        &lt;- L39   INCLUDE
 *    05 CDEMO-CARD-INFO.                                   &lt;- L40
 *       10 CDEMO-CARD-NUM                PIC 9(16).        &lt;- L41   INCLUDE
 *    05 CDEMO-MORE-INFO.                                   &lt;- L42
 *       10  CDEMO-LAST-MAP               PIC X(7).         &lt;- L43   OMIT
 *       10  CDEMO-LAST-MAPSET            PIC X(7).         &lt;- L44   OMIT
 * </pre>
 *
 * <p>The count of seventeen declarations, of which nine are live, is arrived at as follows: the copybook
 * declares sixteen elementary fields under the five {@code 05} group items, and the omitted
 * {@code CDEMO-PGM-ENTER} / {@code CDEMO-PGM-REENTER} condition-name pair at L30-L31 is the seventeenth
 * declaration. Nine elementary fields are projected and eight declarations are omitted. The five
 * {@code 05} group items are not counted: a COBOL group item declares no storage of its own, and the
 * grouping is reproduced here by field naming and ordering rather than by nested types, because a nested
 * type per group would add four indirections that carry no information.
 *
 * <h2>The nine live fields, in exact source order</h2>
 *
 * <p>Component order is the copybook's declaration order, not an alphabetical or convenience order. That
 * matters because a record's canonical constructor is positional: reordering the components would silently
 * change the meaning of every existing call site while still compiling.
 *
 * <ol>
 *   <li>{@code userId} - {@code CDEMO-USER-ID PIC X(08)} at L25</li>
 *   <li>{@code userType} - {@code CDEMO-USER-TYPE PIC X(01)} at L26</li>
 *   <li>{@code customerId} - {@code CDEMO-CUST-ID PIC 9(09)} at L33</li>
 *   <li>{@code customerFirstName} - {@code CDEMO-CUST-FNAME PIC X(25)} at L34</li>
 *   <li>{@code customerMiddleName} - {@code CDEMO-CUST-MNAME PIC X(25)} at L35</li>
 *   <li>{@code customerLastName} - {@code CDEMO-CUST-LNAME PIC X(25)} at L36</li>
 *   <li>{@code accountId} - {@code CDEMO-ACCT-ID PIC 9(11)} at L38</li>
 *   <li>{@code accountStatus} - {@code CDEMO-ACCT-STATUS PIC X(01)} at L39</li>
 *   <li>{@code cardNumber} - {@code CDEMO-CARD-NUM PIC 9(16)} at L41</li>
 * </ol>
 *
 * <h2>The eight omitted declarations, and why each has no counterpart</h2>
 *
 * <p>None of these appears as a component, a field, an accessor or a constant. They are listed because
 * their absence is a design decision that must be auditable, not an oversight.
 *
 * <ol>
 *   <li><strong>{@code CDEMO-FROM-TRANID PIC X(04)} at L21</strong> - no equivalent: routing is
 *       URL-based.</li>
 *   <li><strong>{@code CDEMO-FROM-PROGRAM PIC X(08)} at L22</strong> - no equivalent: routing is
 *       URL-based.</li>
 *   <li><strong>{@code CDEMO-TO-TRANID PIC X(04)} at L23</strong> - no equivalent: routing is
 *       URL-based.</li>
 *   <li><strong>{@code CDEMO-TO-PROGRAM PIC X(08)} at L24</strong> - no equivalent: routing is
 *       URL-based. These four fields together formed the {@code EXEC CICS XCTL} transfer chain, naming
 *       the transaction and program a screen was entered from and the pair it should hand control to.
 *       HTTP request routing supplies that information from the request line, so a payload field naming
 *       the next program would duplicate the URL and could contradict it.</li>
 *   <li><strong>{@code CDEMO-PGM-CONTEXT PIC 9(01)} at L29</strong> - no equivalent: the
 *       pseudo-conversational enter-versus-re-enter flag collapses into stateless request handling.</li>
 *   <li><strong>{@code CDEMO-PGM-ENTER VALUE 0} at L30 and {@code CDEMO-PGM-REENTER VALUE 1} at
 *       L31</strong> - no equivalent, for the same reason: these are the two condition names over the
 *       flag above. A COBOL program used them to distinguish its first display of a screen from a
 *       subsequent turn on the same screen. Every HTTP request is self-contained, so there is no
 *       "subsequent turn" for a flag to describe. Nothing is carried in their place, deliberately: an
 *       enter-versus-re-enter marker in a stateless payload would be client-controlled state that the
 *       server would have to trust.</li>
 *   <li><strong>{@code CDEMO-LAST-MAP PIC X(7)} at L43</strong> - no equivalent: no screen state is
 *       retained.</li>
 *   <li><strong>{@code CDEMO-LAST-MAPSET PIC X(7)} at L44</strong> - no equivalent: no screen state is
 *       retained. These named the BMS map and mapset last sent to the terminal so that a program could
 *       redisplay it. There is no BMS layer in the target and no terminal to redisplay to; the
 *       presentation layer is JSON, and the seventeen symbolic maps are consumed as field contracts
 *       rather than reimplemented.</li>
 * </ol>
 *
 * <h2>Documented type divergence: PIC 9(16) versus PIC X(16)</h2>
 *
 * <p>The same logical card number is typed differently by the two copybooks that declare it.
 * {@code CDEMO-CARD-NUM} at {@code app/cpy/COCOM01Y.cpy:L41} is numeric, {@code PIC 9(16)}, whereas
 * {@code CARD-NUM} at {@code app/cpy/CVACT02Y.cpy:L5} - the card record layout backing the persisted
 * card entity - is alphanumeric, {@code PIC X(16)}.
 *
 * <p><strong>Resolved in favour of the record layout: {@code cardNumber} is a {@code String}.</strong>
 * The value must round-trip across this boundary byte for byte, and a numeric type cannot do that. A
 * sixteen-digit card number beginning with a zero would lose that digit on the way in and could not be
 * reconstructed on the way out, so a numeric type would corrupt exactly the values it appeared to
 * simplify. Sixteen digits do fit a {@code long}, so this choice is about representation fidelity and not
 * about range.
 *
 * <p>Neither copybook is corrected and the two are not silently unified. The divergence is recorded here
 * and again on the component, because a reader who consults only {@code COCOM01Y} will otherwise read the
 * {@code String} as a mistake.
 *
 * <h2>Security posture</h2>
 *
 * <p>{@code cardNumber} is on the never-emit list, and the three customer name components are personally
 * identifying. {@link #toString()} is therefore overridden. That override is mandatory rather than
 * cosmetic: the {@code toString()} a record would otherwise inherit prints every component, so declining
 * to override it would publish a card number and a full customer name into any log line, exception
 * message or debugger view that rendered this object. The override emits {@code userId},
 * {@code userType} and {@code accountStatus} and nothing else - not the card number, not masked, and not
 * as a length, and no name field in any form. No alternative full rendering and no masking helper is
 * provided, so there is no second path to the same disclosure.
 *
 * <p>The logging configuration masks card numbers profile-invariantly, but that is a second line of
 * defence. The primary defence is never emitting them.
 *
 * <p>This type carries no credential material of any kind: the COMMAREA declares no password and no hash,
 * so there is none to omit. It exposes exactly the nine fields the copybook declares as live and not one
 * field more, which is the least-privilege position for a payload type.
 *
 * <h2>Validation stance</h2>
 *
 * <p>Each component carries at most one constraint, and that constraint states the width the copybook
 * declares: {@code @Size(max = ...)} for a {@code PIC X(n)} field and {@code @Digits(integer = n,
 * fraction = 0)} for a {@code PIC 9(n)} field. Nothing stricter is invented. There is no
 * {@code @NotNull} anywhere, because absence is a legitimate state for every one of these fields - a
 * COMMAREA reaching a program before an account had been selected simply had no account identifier in
 * it. There is no digits-only {@code @Pattern} on {@code cardNumber}, because the record layout it is
 * typed from is alphanumeric and the source performs no such check at this boundary.
 *
 * <p>The widths themselves are also published as named constants so that a consumer needing to render a
 * value back into a fixed-width record reads the figure from here rather than repeating a literal.
 *
 * <h2>Absent, blank and low values are three distinct states</h2>
 *
 * <p>They are kept distinct and are never conflated. A {@code null} component means the field was absent;
 * a component holding spaces means the fixed-width field was present and blank, which is what a COBOL
 * {@code MOVE SPACES} leaves behind; a component holding low values means the field was present and
 * initialised to binary zeros, which is what {@code INITIALIZE} or a {@code MOVE LOW-VALUES} leaves
 * behind. A caller can tell all three apart because this type performs no coercion: {@code null} never
 * becomes {@code ""} and {@code ""} never becomes {@code null}.
 *
 * <h2>What this type deliberately does not do</h2>
 *
 * <ul>
 *   <li>It performs no normalisation on the way in. Nothing is trimmed, upper-cased, lower-cased,
 *       zero-padded or reformatted, so a value read out is byte-identical to the value put in. Where the
 *       legacy programs fold case they do so in their own logic, upstream of this structure:
 *       {@code app/cbl/COSGN00C.cbl:L132} upper-cases the entered user identifier straight into
 *       {@code CDEMO-USER-ID}, and {@code app/cbl/COSGN00C.cbl:L135} upper-cases the entered password.
 *       By the time a value reaches this field it has therefore already been folded by the program that
 *       owns that decision, so folding again here would apply the same transformation twice and in the
 *       wrong layer.</li>
 *   <li>It contains no locale-sensitive, charset-sensitive or time-zone-sensitive operation whatsoever.
 *       Because no case folding, formatting or parsing is performed anywhere in this file, its behaviour
 *       cannot vary with the platform default locale, default charset or default time zone.</li>
 *   <li>It compares nothing and validates nothing across entities. The field-by-field snapshot comparison
 *       that the account update path requires lives with that path, not here.</li>
 *   <li>It holds no mutable static state, performs no I/O, reads no configuration and logs nothing.</li>
 *   <li>It does not implement {@code Serializable}. Nothing in the target transports this type by Java
 *       serialisation; it crosses boundaries as JSON.</li>
 *   <li>It does not map {@code userType} onto a granted authority. Role mapping is an authorisation
 *       concern owned by the security and configuration packages, which is also why this file imports no
 *       framework type beyond the two validation annotations.</li>
 * </ul>
 *
 * <p>Instances are immutable and inherently thread safe, subject only to the caller not mutating an
 * argument after construction - which cannot happen here, because every component is either a
 * {@code String} or a {@code Long} and both are themselves immutable.
 *
 * <h2>Error modes</h2>
 *
 * <p>There are none. The canonical constructor accepts every combination of values, including all nine
 * {@code null}, and neither it nor any accessor throws. An out-of-domain user type code is reported by
 * {@link #resolvedUserType()} as {@code null} rather than as a failure, so a value this application did
 * not write still round-trips instead of making the object unusable. The declared constraints are
 * evaluated by a validator when a caller chooses to apply one; they never fire during construction.
 *
 * @param userId             {@code CDEMO-USER-ID}, {@code PIC X(08)}, at {@code app/cpy/COCOM01Y.cpy:L25}.
 *                           The signed-on user's identifier, eight characters. In the target this same
 *                           value is the subject claim of the issued token, which is why it is the first
 *                           component here and the only identity field a diagnostic rendering emits.
 *                           {@code null} when no user context is present.
 * @param userType           {@code CDEMO-USER-TYPE}, {@code PIC X(01)}, at
 *                           {@code app/cpy/COCOM01Y.cpy:L26}, with the two condition names
 *                           {@code CDEMO-USRTYP-ADMIN VALUE 'A'} at L27 and
 *                           {@code CDEMO-USRTYP-USER VALUE 'U'} at L28. Carried as the <em>raw</em>
 *                           one-character value so that the {@code PIC X(01)} contract is preserved
 *                           exactly and an unrecognised code stored by some other writer survives the
 *                           round trip instead of failing to bind. Use {@link #resolvedUserType()} for
 *                           the typed view. Exactly one character when present; {@code null} when absent.
 * @param customerId         {@code CDEMO-CUST-ID}, {@code PIC 9(09)}, at
 *                           {@code app/cpy/COCOM01Y.cpy:L33}. Typed {@code Long} because the source PIC
 *                           is genuinely numeric and this value is used as a number: it is the key of the
 *                           customer record, and the persisted customer entity types it {@code Long} too,
 *                           so agreeing with it avoids a conversion at every hand-off. Nine digits fit a
 *                           {@code Long} with room to spare. {@code null} when no customer is in context.
 * @param customerFirstName  {@code CDEMO-CUST-FNAME}, {@code PIC X(25)}, at
 *                           {@code app/cpy/COCOM01Y.cpy:L34}. Personally identifying: never rendered by
 *                           {@link #toString()}.
 * @param customerMiddleName {@code CDEMO-CUST-MNAME}, {@code PIC X(25)}, at
 *                           {@code app/cpy/COCOM01Y.cpy:L35}. Personally identifying: never rendered by
 *                           {@link #toString()}. Legitimately blank for a customer with no middle name,
 *                           which is distinct from being absent.
 * @param customerLastName   {@code CDEMO-CUST-LNAME}, {@code PIC X(25)}, at
 *                           {@code app/cpy/COCOM01Y.cpy:L36}. Personally identifying: never rendered by
 *                           {@link #toString()}.
 * @param accountId          {@code CDEMO-ACCT-ID}, {@code PIC 9(11)}, at
 *                           {@code app/cpy/COCOM01Y.cpy:L38}. Typed {@code Long} for the same reasons as
 *                           {@code customerId}, and matching the persisted account entity. Eleven digits
 *                           overflow a 32-bit {@code int}, so {@code int} is not an option here.
 *                           {@code null} when no account has been selected.
 * @param accountStatus      {@code CDEMO-ACCT-STATUS}, {@code PIC X(01)}, at
 *                           {@code app/cpy/COCOM01Y.cpy:L39}. The one-character active status carried
 *                           alongside the account identifier. Not modelled as an enumeration: unlike the
 *                           user type at L26-L28, this field carries no condition names in the copybook,
 *                           so there is no source-defined value domain to enumerate and inventing one
 *                           would reject values the legacy system accepts.
 * @param cardNumber         {@code CDEMO-CARD-NUM}, {@code PIC 9(16)}, at
 *                           {@code app/cpy/COCOM01Y.cpy:L41}. Typed {@code String}, <em>not</em> a
 *                           numeric type, because {@code CARD-NUM} at {@code app/cpy/CVACT02Y.cpy:L5} -
 *                           the record layout this value is read from and written back to - declares the
 *                           same field {@code PIC X(16)}. A numeric type would strip a significant
 *                           leading zero and break the round trip. The two copybooks genuinely disagree
 *                           and neither is corrected. <strong>Sensitive: never rendered by
 *                           {@link #toString()}, not even masked or as a length.</strong>
 */
public record CommArea(

        @Size(max = USER_ID_MAX_LENGTH)
        String userId,

        @Size(max = USER_TYPE_LENGTH)
        String userType,

        @Digits(integer = CUSTOMER_ID_DIGITS, fraction = 0)
        Long customerId,

        @Size(max = CUSTOMER_NAME_MAX_LENGTH)
        String customerFirstName,

        @Size(max = CUSTOMER_NAME_MAX_LENGTH)
        String customerMiddleName,

        @Size(max = CUSTOMER_NAME_MAX_LENGTH)
        String customerLastName,

        @Digits(integer = ACCOUNT_ID_DIGITS, fraction = 0)
        Long accountId,

        @Size(max = ACCOUNT_STATUS_LENGTH)
        String accountStatus,

        @Size(max = CARD_NUMBER_MAX_LENGTH)
        String cardNumber) {

    /**
     * Declared width of {@code userId}: {@code CDEMO-USER-ID PIC X(08)} at
     * {@code app/cpy/COCOM01Y.cpy:L25}.
     */
    public static final int USER_ID_MAX_LENGTH = 8;

    /**
     * Declared width of {@code userType}: {@code CDEMO-USER-TYPE PIC X(01)} at
     * {@code app/cpy/COCOM01Y.cpy:L26}.
     *
     * <p>One byte, which is why the component is a one-character {@code String} rather than a longer
     * code. The constraint is expressed as a maximum rather than as an exact length so that an absent
     * value stays absent: a minimum would additionally reject the empty string, a rejection the source
     * never makes because a fixed-width field cannot be empty. The exactly-one-character part of the
     * contract is enforced where it matters, in {@link #resolvedUserType()}, which resolves nothing for a
     * value that is not exactly one character long.</p>
     */
    public static final int USER_TYPE_LENGTH = 1;

    /**
     * Declared digit count of {@code customerId}: {@code CDEMO-CUST-ID PIC 9(09)} at
     * {@code app/cpy/COCOM01Y.cpy:L33}. Nine digits, no decimal places.
     */
    public static final int CUSTOMER_ID_DIGITS = 9;

    /**
     * Declared width of each customer name component: {@code CDEMO-CUST-FNAME PIC X(25)} at
     * {@code app/cpy/COCOM01Y.cpy:L34}, {@code CDEMO-CUST-MNAME PIC X(25)} at L35 and
     * {@code CDEMO-CUST-LNAME PIC X(25)} at L36.
     *
     * <p>One constant serves all three because the copybook declares all three at the same width. They
     * are not given three separate constants of equal value, which would suggest the widths could drift
     * apart independently when the source says they cannot.</p>
     */
    public static final int CUSTOMER_NAME_MAX_LENGTH = 25;

    /**
     * Declared digit count of {@code accountId}: {@code CDEMO-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/COCOM01Y.cpy:L38}. Eleven digits, no decimal places.
     *
     * <p>Eleven digits exceed the range of a 32-bit signed integer, whose maximum is ten digits. This is
     * the reason the component is a {@code Long}, and the reason that choice is not negotiable.</p>
     */
    public static final int ACCOUNT_ID_DIGITS = 11;

    /**
     * Declared width of {@code accountStatus}: {@code CDEMO-ACCT-STATUS PIC X(01)} at
     * {@code app/cpy/COCOM01Y.cpy:L39}.
     */
    public static final int ACCOUNT_STATUS_LENGTH = 1;

    /**
     * Declared width of {@code cardNumber}: {@code CDEMO-CARD-NUM PIC 9(16)} at
     * {@code app/cpy/COCOM01Y.cpy:L41}, sixteen positions.
     *
     * <p>The same sixteen positions are declared alphanumerically as {@code CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVACT02Y.cpy:L5}. The two copybooks disagree about the type but agree exactly about
     * the width, so this single constant is correct for both readings.</p>
     */
    public static final int CARD_NUMBER_MAX_LENGTH = 16;

    /**
     * Returns the typed view of {@code userType}, or {@code null} when the raw value is not one of the
     * two codes the copybook defines.
     *
     * <p>This accessor exists because the source itself types this field: {@code CDEMO-USER-TYPE} carries
     * the two condition names {@code CDEMO-USRTYP-ADMIN VALUE 'A'} at
     * {@code app/cpy/COCOM01Y.cpy:L27} and {@code CDEMO-USRTYP-USER VALUE 'U'} at L28, and
     * {@code UserType} is the transcription of exactly those two. The typed view is therefore derived
     * from the source rather than invented on top of it. The raw one-character value remains available
     * from {@link #userType()} and is what serialises, so this accessor adds a reading of the field
     * without replacing it.
     *
     * <p><strong>It returns {@code null} rather than throwing for anything it cannot resolve.</strong>
     * That is the whole point of it: a stored value outside the domain must round-trip through this type
     * intact, and a throwing accessor would make an object holding such a value impossible to inspect,
     * log or serialise. {@code null} is returned, explicitly and by design, in every one of these cases:
     *
     * <ul>
     *   <li>{@link #userType()} is {@code null}, that is the field was absent;</li>
     *   <li>it is the empty string;</li>
     *   <li>it is longer than one character, including a one-character code carried with the blank
     *       padding of a fixed-width record;</li>
     *   <li>it is exactly one character but is neither {@code 'A'} nor {@code 'U'} - a blank, a low value
     *       byte, a digit, or a lower-case {@code 'a'} or {@code 'u'}.</li>
     * </ul>
     *
     * <p>The last case is deliberate and is parity behaviour, not strictness: the resolution is case
     * sensitive because {@code app/cbl/COSGN00C.cbl:L227} moves the stored type byte into
     * {@code CDEMO-USER-TYPE} unfolded - {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE}, with no
     * {@code FUNCTION UPPER-CASE} applied - and {@code app/cbl/COSGN00C.cbl:L230} then tests
     * {@code IF CDEMO-USRTYP-ADMIN}, which compares the byte against {@code 'A'} exactly. A lower-case
     * byte satisfies neither condition name in the legacy program and must satisfy neither here.
     *
     * <p>Nothing is trimmed or folded before resolution, consistent with this type performing no
     * normalisation anywhere. A caller that wants to accept padded input must decide that for itself,
     * because deciding it here would accept input the legacy program refuses.
     *
     * <p>This method is a pure function: it has no side effects, performs no I/O, allocates nothing
     * beyond what the delegate does, never throws, and is safe for concurrent use. It is not a component
     * accessor, so it takes no part in {@link #equals(Object)}, {@link #hashCode()} or
     * {@link #toString()}; two instances agreeing on the raw {@code userType} necessarily agree here too.
     *
     * @return {@code UserType.ADMIN} when the raw value is {@code "A"}, {@code UserType.USER} when it is
     *         {@code "U"}, and {@code null} in every other case, including when the raw value is
     *         {@code null}
     */
    public UserType resolvedUserType() {
        return UserType.fromCode(this.userType).orElse(null);
    }

    /**
     * Returns a diagnostic rendering that discloses neither the card number nor any customer name.
     *
     * <p>Only {@code userId}, {@code userType} and {@code accountStatus} are reported: an identity and a
     * pair of one-character control values, which is what a log line needs in order to be useful and no
     * more. Overriding is mandatory rather than cosmetic, because the {@code toString()} a record would
     * otherwise inherit prints every component and would therefore print {@code cardNumber} and all three
     * name fields.
     *
     * <p>Nothing is emitted in place of the omitted values - not a masked form of the card number, not a
     * digit count, not a first-and-last-four fragment, and no indication of whether a name is present.
     * The four sensitive components are simply absent from the output, so this rendering discloses nothing
     * whatsoever about them. {@code customerId} and {@code accountId} are omitted as well: they are not
     * sensitive in themselves, but they are not needed to identify a request either, and the narrower
     * output is the safer default.
     *
     * <p>No alternative full or verbose rendering is provided anywhere on this type, so there is no
     * second route to the disclosure this method avoids.
     *
     * <p>The output is assembled by plain concatenation and contains no formatted, parsed or case-folded
     * value, so it is byte-identical on every platform regardless of the default locale, charset or time
     * zone.
     *
     * @return a rendering of this projection containing no card number and no personally identifying name
     */
    @Override
    public String toString() {
        return "CommArea[userId=" + this.userId
                + ", userType=" + this.userType
                + ", accountStatus=" + this.accountStatus + "]";
    }
}
