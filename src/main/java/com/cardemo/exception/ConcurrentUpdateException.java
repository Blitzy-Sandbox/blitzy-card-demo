/*
 * ******************************************************************
 * Program     : ConcurrentUpdateException.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 exception
 * Function    : Optimistic-concurrency and record-lock outcome of COACTUPC 9700-CHECK-CHANGE-IN-REC.
 * Source      : app/cbl/COACTUPC.cbl:L517-L524 @ 7756d89 - the four outcome
 *               flags and the verbatim screen message literal each carries.
 * Source      : app/cbl/COACTUPC.cbl:L654-L668 @ 7756d89 - the fifth marker
 *               ACUP-CHANGE-ACTION and its ten condition names.
 * Source      : app/cbl/COACTUPC.cbl:L2606-L2615 @ 7756d89 - the EVALUATE TRUE
 *               dispatch that never tests the customer lock flag, so a customer
 *               lock failure falls through WHEN OTHER and is reported to the
 *               user as success. Classified Blocker; preserved, not repaired.
 * Source      : app/cbl/COACTUPC.cbl:L3907-L3915,L3934-L3942 @ 7756d89 - the two
 *               read for update guards, each setting its lock flag only while no
 *               message has already been staged.
 * Source      : app/cbl/COACTUPC.cbl:L4076-L4081,L4095-L4102 @ 7756d89 - the
 *               asymmetric rollback, where both rewrite failures set the same
 *               flag and only the second issues SYNCPOINT ROLLBACK.
 * Source      : app/cbl/COCRDUPC.cbl @ 7756d89 - the mirrored card update change
 *               detection pattern, whose single lock flag is tested at L993.
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
 * Signals that an account update was abandoned without being applied, because the record could not be
 * locked, had changed since it was shown to the user, or had not yet been confirmed.
 *
 * <p><strong>What it does.</strong> It is the typed outcome of
 * {@code 9600-WRITE-PROCESSING} and the change detection paragraph it performs,
 * {@code 9700-CHECK-CHANGE-IN-REC}, in the largest program of the frozen corpus. That program signals
 * five distinct abandonment reasons through four condition names on a screen message field plus one
 * value of a change action marker. This class turns those five reasons into five individually
 * representable outcomes so that a caller can tell them apart, which no single generic conflict type
 * could do.
 *
 * <p><strong>What it deliberately is not.</strong> It carries no comparison logic, no retry, no merge
 * and no resolution strategy. It is <em>vocabulary</em>. The field by field comparison, the staged
 * message precedence and the reproduction of the fall through described below all belong to
 * {@code com.cardemo.service.account.AccountUpdateService}; the choice of HTTP status belongs to the
 * controller. Three concerns, deliberately split.
 *
 * <p><strong>Citation convention.</strong> Every legacy claim below cites a path and line in the frozen
 * corpus and all of them are keyed to the traceability anchor commit
 * {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}, short {@code 7756d89}, exactly as the header
 * records. Filename case is reproduced as it is on disk: in {@code app/cbl} only two members carry an
 * uppercase extension and neither is cited here, so every path below is lowercase. Each line number and
 * count quoted was measured against the anchor rather than inherited from prose, and the two places
 * where the measurement contradicts the received description are called out as such.
 *
 * <h2>The four outcome flags</h2>
 *
 * <p>Declared as condition names on {@code WS-RETURN-MSG PIC X(75)}
 * ({@code app/cbl/COACTUPC.cbl:L479}), so each flag <em>is</em> the text the screen displays. Rule 1
 * Clause A requires explicit behaviour and the parity contract requires byte identical text, so these
 * literals are reproduced exactly - including {@code some one} as two words and the full stop before
 * {@code ' Please review'}. They are not paraphrased, respaced or corrected.
 *
 * <table>
 * <caption>The four outcome flags at {@code app/cbl/COACTUPC.cbl:L517-L524}</caption>
 * <thead>
 * <tr><th scope="col">Lines</th><th scope="col">Condition name</th><th scope="col">Message literal</th></tr>
 * </thead>
 * <tbody>
 * <tr><td>L517-L518</td><td>{@code COULD-NOT-LOCK-ACCT-FOR-UPDATE}</td>
 *     <td>{@code 'Could not lock account record for update'}</td></tr>
 * <tr><td>L519-L520</td><td>{@code COULD-NOT-LOCK-CUST-FOR-UPDATE}</td>
 *     <td>{@code 'Could not lock customer record for update'}</td></tr>
 * <tr><td>L521-L522</td><td>{@code DATA-WAS-CHANGED-BEFORE-UPDATE}</td>
 *     <td>{@code 'Record changed by some one else. Please review'}</td></tr>
 * <tr><td>L523-L524</td><td>{@code LOCKED-BUT-UPDATE-FAILED}</td>
 *     <td>{@code 'Update of record failed'}</td></tr>
 * </tbody>
 * </table>
 *
 * <p>The same block declares six neighbouring condition names that are <em>not</em> concurrency
 * outcomes and are deliberately absent from this class: the two card expiry validations at L509-L512,
 * the two cross reference lookup failures at L513-L516, the read error at L525-L526 and the placeholder
 * at L527-L528. Those belong to validation and lookup types, not here.
 *
 * <h2>The fifth marker</h2>
 *
 * <p>{@code ACUP-CHANGE-ACTION PIC X(1)} at {@code app/cbl/COACTUPC.cbl:L654}, initialised to
 * {@code LOW-VALUES} at L655, is the program's screen state machine. The dispatch described further
 * below writes one of its values after every write attempt, which is why each outcome of this class
 * records the value it produces.
 *
 * <table>
 * <caption>{@code ACUP-CHANGE-ACTION} condition names at {@code app/cbl/COACTUPC.cbl:L655-L668}</caption>
 * <thead>
 * <tr><th scope="col">Lines</th><th scope="col">Condition name</th><th scope="col">Value</th></tr>
 * </thead>
 * <tbody>
 * <tr><td>L656-L658</td><td>{@code ACUP-DETAILS-NOT-FETCHED}</td><td>{@code LOW-VALUES}, {@code SPACES}</td></tr>
 * <tr><td>L659</td><td>{@code ACUP-SHOW-DETAILS}</td><td>{@code 'S'}</td></tr>
 * <tr><td>L660-L662</td><td>{@code ACUP-CHANGES-MADE}</td>
 *     <td>{@code 'E'}, {@code 'N'}, {@code 'C'}, {@code 'L'}, {@code 'F'}</td></tr>
 * <tr><td>L663</td><td>{@code ACUP-CHANGES-NOT-OK}</td><td>{@code 'E'}</td></tr>
 * <tr><td>L664</td><td>{@code ACUP-CHANGES-OK-NOT-CONFIRMED}</td><td>{@code 'N'}</td></tr>
 * <tr><td>L665</td><td>{@code ACUP-CHANGES-OKAYED-AND-DONE}</td><td>{@code 'C'}</td></tr>
 * <tr><td>L666</td><td>{@code ACUP-CHANGES-FAILED}</td><td>{@code 'L'}, {@code 'F'}</td></tr>
 * <tr><td>L667</td><td>{@code ACUP-CHANGES-OKAYED-LOCK-ERROR}</td><td>{@code 'L'}</td></tr>
 * <tr><td>L668</td><td>{@code ACUP-CHANGES-OKAYED-BUT-FAILED}</td><td>{@code 'F'}</td></tr>
 * </tbody>
 * </table>
 *
 * <h2>Why five outcomes and not six - the flag collision. Severity Low</h2>
 *
 * <p>The write sequence rewrites two datasets, and each rewrite has its own failure path. It is
 * tempting to model them as two outcomes, because the two paths are not identical: only the second
 * issues an explicit backout.
 *
 * <ul>
 *   <li>The account rewrite is issued at {@code app/cbl/COACTUPC.cbl:L4065-L4071}. Its failure path,
 *       L4076-L4081, sets {@code LOCKED-BUT-UPDATE-FAILED} at L4079 and branches to the shared exit at
 *       L4080. <strong>No rollback.</strong></li>
 *   <li>The customer rewrite is issued at L4085-L4091. Its failure path, L4095-L4103, sets
 *       <em>the same</em> {@code LOCKED-BUT-UPDATE-FAILED} at L4098, issues
 *       {@code EXEC CICS SYNCPOINT ROLLBACK} at L4099-L4101, and branches to the same exit at L4102.
 *       <strong>With rollback.</strong></li>
 *   <li>Both land on {@code 9600-WRITE-PROCESSING-EXIT} at L4105.</li>
 * </ul>
 *
 * <p>At the flag level the two failures are therefore <strong>indistinguishable</strong>: one condition
 * name, two sites. Adding a sixth outcome to separate them would invent a distinction the source does
 * not make and would make the mapping unprovable against the traceability matrix. So the vocabulary is
 * five. Where the distinction is genuinely useful for diagnosis it is recovered without touching the
 * outcome set, by naming the dataset in {@link #getAffectedRecord()} - metadata, not a sixth outcome.
 *
 * <p><strong>The rollback asymmetry is not a defect and is not corrected.</strong> At the account
 * rewrite failure nothing has yet been written inside the unit of work, so there is nothing to back
 * out; at the customer rewrite failure the account rewrite has already happened inside the same unit of
 * work, so an explicit backout is the only way to avoid a half applied update. A single service method
 * annotated with {@code @Transactional(rollbackFor = Exception.class)} reproduces <em>both</em> branches
 * automatically, because each failure path throws before the commit point. This is a mechanism
 * substitution recorded in {@code DECISION_LOG.md}, not a lost statement: a reviewer comparing the two
 * sources side by side will find no Java counterpart to {@code SYNCPOINT ROLLBACK} and should conclude
 * that the transaction boundary subsumed it, because it did.
 *
 * <h2>The two read for update guards and the staged message precedence. Severity Medium</h2>
 *
 * <p>Both locks are taken with {@code EXEC CICS READ ... UPDATE} and both are guarded identically - the
 * account at {@code app/cbl/COACTUPC.cbl:L3907-L3915} and the customer at L3934-L3942:
 *
 * <pre>
 * IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)
 *    CONTINUE
 * ELSE
 *    SET INPUT-ERROR                    TO TRUE
 *    IF  WS-RETURN-MSG-OFF
 *        SET COULD-NOT-LOCK-ACCT-FOR-UPDATE  TO TRUE
 *    END-IF
 *    GO TO 9600-WRITE-PROCESSING-EXIT
 * END-IF
 * </pre>
 *
 * <p>Two details of that guard are easy to miss and both change behaviour.
 *
 * <ul>
 *   <li>{@code INPUT-ERROR} ({@code 88 ... VALUE '1'} at L173) is set <strong>unconditionally</strong>,
 *       at L3910 and L3937. The write is abandoned whether or not a message is recorded.</li>
 *   <li>The lock flag is set <strong>only</strong> under {@code IF WS-RETURN-MSG-OFF} - L3911 guarding
 *       L3912 for the account, L3938 guarding L3939 for the customer.
 *       {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} at L480 means "no message has been staged yet", so
 *       the guard is a precedence rule: an earlier message wins and is never overwritten.</li>
 * </ul>
 *
 * <p>The consequence is that the outcome is only <em>conditionally</em> recorded in the source. A
 * caller must reproduce the precedence - if a message is already staged, do not replace it - and that
 * logic lives in {@code com.cardemo.service.account.AccountUpdateService}. This class only makes the
 * outcome representable; it holds no precedence rule and cannot enforce one.
 *
 * <h2>Two layer optimistic concurrency - why a version column alone is insufficient</h2>
 *
 * <p>{@code 9700-CHECK-CHANGE-IN-REC} occupies {@code app/cbl/COACTUPC.cbl:L4109-L4195} and compares
 * the freshly locked records against a snapshot captured when the screen was first populated. The
 * snapshot groups are declared separately: {@code ACUP-OLD-DETAILS} at L669-L756 and
 * {@code ACUP-NEW-DETAILS} from L757.
 *
 * <p><strong>Measured locator correction.</strong> The received description cites the comparison itself
 * as L669-L756. Measured against the anchor, that range is the snapshot <em>declaration</em>; the
 * comparison paragraph is at L4109-L4195. Both are cited above so the mapping stays provable. The same
 * description reports "twelve account predicates"; measured, the account condition at L4115-L4145 joins
 * <strong>16</strong> comparisons over 10 distinct fields and the customer condition at L4152-L4191
 * joins <strong>19</strong> over 17. Severity Low - an evidence correction, not a behaviour change.
 *
 * <p><strong>A version counter and this comparison do not answer the same question.</strong> A version
 * column detects <em>that</em> a row changed. The source detects <em>which fields</em> changed and in
 * what representation. The two disagree in both directions: a concurrent write that set a field back to
 * its original value passes the legacy comparison but fails a version check, while a concurrent write
 * that changed only a field outside the compared set fails a version check but passes the legacy
 * comparison. Reproducing the legacy behaviour therefore requires <strong>both</strong> layers, and
 * because the target is stateless the snapshot must travel in the request body - which is why
 * {@code com.cardemo.model.dto.AccountUpdateRequest} carries both an old and a new detail group.
 *
 * <p>Three characteristics of the comparison are routinely got wrong. They are implemented in the
 * service; they are recorded here because they explain why this class exists at all.
 *
 * <ul>
 *   <li><strong>Dates are compared component by component, never as whole strings.</strong> The open,
 *       expiry and reissue dates each contribute three comparisons, at L4127-L4129, L4131-L4133 and
 *       L4135-L4137. The expiry field name is misspelled in the copybook as
 *       {@code ACCT-EXPIRAION-DATE}; the misspelling is part of the field contract and is not
 *       corrected.</li>
 *   <li><strong>Case handling is deliberately asymmetric.</strong> {@code ACCT-GROUP-ID} is compared
 *       through {@code FUNCTION LOWER-CASE} on both sides at L4139-L4140. The customer names, the three
 *       address lines, the state code, the country code and {@code CUST-GOVT-ISSUED-ID} are compared
 *       through {@code FUNCTION UPPER-CASE} on both sides at L4152-L4167 and L4172-L4173.
 *       {@code CUST-ADDR-ZIP} (L4168), {@code CUST-PHONE-NUM-1} (L4169), {@code CUST-PHONE-NUM-2}
 *       (L4170), {@code CUST-SSN} (L4171), {@code CUST-EFT-ACCOUNT-ID} (L4181),
 *       {@code CUST-PRI-CARD-HOLDER-IND} (L4183) and {@code CUST-FICO-CREDIT-SCORE} (L4186) are
 *       compared with <strong>no case function at all</strong>. Normalising this in either direction
 *       changes which updates are accepted. Any Java case operation reproducing it must pass
 *       {@code Locale.ROOT}, because a default locale can map the dotless i differently and would make
 *       the outcome depend on the host.</li>
 *   <li><strong>The date of birth is compared at different offsets on each side.</strong> This is the
 *       trap. The live field is {@code CUST-DOB-YYYY-MM-DD PIC X(10)}
 *       ({@code app/cpy/CVCUS01Y.cpy:L19}), dash separated, so its components sit at 1, 6 and 9. The
 *       snapshot field is {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD PIC X(08)}
 *       ({@code app/cbl/COACTUPC.cbl:L746}), redefined at L747-L751 into a four byte year and two byte
 *       month and day with <strong>no separators</strong>, so its components sit at 1, 5 and 7. The
 *       source compares 1 against 1 (L4174-L4175), 6 against 5 (L4176-L4177) and 9 against 7
 *       (L4178-L4179). <strong>A whole string comparison of those two fields would report a change on
 *       every single request and make the endpoint permanently unusable.</strong> The source proves the
 *       point itself: L3856 is a commented out whole field move of the ten byte field into the eight
 *       byte one, replaced by the component moves at L3857-L3859. The Java implementation must compare
 *       components and must store the snapshot date in its compact eight character form.</li>
 * </ul>
 *
 * <p>A framework optimistic lock failure - the store level layer - maps to this same class, normally
 * with {@link Outcome#DATA_CHANGED_BEFORE_UPDATE}. The two layers must stay
 * <strong>distinguishable</strong>, because collapsing them discards the diagnostic: the store level
 * failure always arrives with a cause, so pass the framework exception to a cause carrying constructor
 * and never drop it. No persistence or framework type is imported here, by design.
 *
 * <h2>BLOCKER 5.2 - the customer lock flag that is set but never tested</h2>
 *
 * <p><strong>Severity Blocker. Preserved, not repaired.</strong>
 *
 * <p>Measured across all 4,236 lines of the program, {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} occurs at
 * exactly <strong>two</strong> lines: L519, the condition name declaration, and L3939, the single
 * {@code SET}. <strong>It is never read</strong> - not in a {@code WHEN}, not in an {@code IF}, not in
 * any condition anywhere in the program.
 *
 * <p>The dispatch that runs immediately after {@code PERFORM 9600-WRITE-PROCESSING} (L2604-L2605) is an
 * {@code EVALUATE TRUE} at L2606-L2615, reached only when changes were edited, found acceptable and
 * confirmed with {@code CCARD-AID-PFK05} (L2602-L2603). It tests three flags:
 *
 * <table>
 * <caption>The dispatch at {@code app/cbl/COACTUPC.cbl:L2606-L2615}</caption>
 * <thead>
 * <tr><th scope="col">Lines</th><th scope="col">Branch</th><th scope="col">Resulting marker</th></tr>
 * </thead>
 * <tbody>
 * <tr><td>L2607-L2608</td><td>{@code WHEN COULD-NOT-LOCK-ACCT-FOR-UPDATE}</td>
 *     <td>{@code ACUP-CHANGES-OKAYED-LOCK-ERROR} {@code 'L'}</td></tr>
 * <tr><td>L2609-L2610</td><td>{@code WHEN LOCKED-BUT-UPDATE-FAILED}</td>
 *     <td>{@code ACUP-CHANGES-OKAYED-BUT-FAILED} {@code 'F'}</td></tr>
 * <tr><td>L2611-L2612</td><td>{@code WHEN DATA-WAS-CHANGED-BEFORE-UPDATE}</td>
 *     <td>{@code ACUP-SHOW-DETAILS} {@code 'S'}</td></tr>
 * <tr><td>L2613-L2614</td><td>{@code WHEN OTHER}</td>
 *     <td>{@code ACUP-CHANGES-OKAYED-AND-DONE} {@code 'C'}</td></tr>
 * </tbody>
 * </table>
 *
 * <p><strong>The consequence.</strong> A customer read for update failure is recorded at L3939 with
 * nothing yet written. None of the three tested flags is set on that path, so control reaches
 * {@code WHEN OTHER} at L2613 and L2614 sets {@code ACUP-CHANGES-OKAYED-AND-DONE}. The user is told the
 * changes were okayed and done, <strong>although no update occurred at all</strong>. The staged message
 * guard of L3938 does not change the destination: whether or not the flag is set, the dispatch still
 * falls to {@code WHEN OTHER}.
 *
 * <p><strong>Corroboration that this is a slip rather than a design.</strong> The mirrored card update
 * program {@code app/cbl/COCRDUPC.cbl} rewrites a single dataset and so declares a single lock flag,
 * {@code COULD-NOT-LOCK-FOR-UPDATE} at L205 with the literal
 * {@code 'Could not lock record for update'}. That one <em>is</em> tested, at L993, and set at L1446.
 * The pattern is complete wherever there is one dataset; the gap appears only in the second lock flag,
 * added when this program was extended to rewrite two. The author added the condition name and the
 * {@code SET} and never extended the {@code EVALUATE}.
 *
 * <p><strong>Why it is not fixed here, or anywhere.</strong> Behavioural parity is the contract, and
 * rewriting legacy business rules to be more correct is out of scope. The service layer reproduces the
 * fall through so the user visible result matches the legacy screen, while the internal outcome stays
 * distinguishable and can be logged and alerted on - and that is possible only because
 * {@link Outcome#COULD_NOT_LOCK_CUSTOMER} exists as a first class value. Repairing the fall through
 * would change observable behaviour and break the parity comparison; deleting the outcome would make
 * the defect invisible. Representing it and documenting it is the only option that does neither. The
 * behaviour is cited in {@code TRACEABILITY_MATRIX.md} and justified in {@code DECISION_LOG.md}.
 *
 * <p><strong>Remediation, for the record and deliberately not applied:</strong> inserting
 * {@code WHEN COULD-NOT-LOCK-CUST-FOR-UPDATE} before {@code WHEN OTHER}, mapping it to
 * {@code ACUP-CHANGES-OKAYED-LOCK-ERROR} alongside the account flag, would close it in the legacy
 * program. It is recorded as residual risk rather than performed.
 *
 * <h2>Do not collapse the five outcomes. Severity High</h2>
 *
 * <p>Every one of the five must map to a distinguishable response. Collapsing them into a single
 * conflict status discards information the legacy screen displayed: a lock contention, a stale
 * snapshot, a failed rewrite and an unconfirmed submission are four different things for a client to
 * do something about, and the fifth is the silent one above. This is also what Rule 1 Clause A means by
 * meaningful errors. No status is bound here - selection is contextual and belongs to the controller -
 * which is why this class carries no annotation and no status field.
 *
 * <h2>Security contract for callers - outcome and field names only. Severity Blocker</h2>
 *
 * <p>Exception messages are logged, so anything placed in one is disclosed. The natural but wrong way
 * to explain a detected change is to report the old and new <em>values</em>, and the compared field set
 * is precisely the personally identifiable inventory of the customer record:
 * {@code CUST-PHONE-NUM-1 PIC X(15)} and {@code CUST-PHONE-NUM-2 PIC X(15)}
 * ({@code app/cpy/CVCUS01Y.cpy:L15-L16}), {@code CUST-SSN PIC 9(09)} (L17),
 * {@code CUST-GOVT-ISSUED-ID PIC X(20)} (L18), {@code CUST-DOB-YYYY-MM-DD PIC X(10)} (L19) and
 * {@code CUST-EFT-ACCOUNT-ID PIC X(10)} (L20), alongside sixteen character card numbers and
 * {@code SEC-USR-PWD PIC X(08)} ({@code app/cpy/CSUSR01Y.cpy:L21}).
 *
 * <p><strong>Binding contract: carry the outcome, and at most the <em>names</em> of the fields that
 * differed. Never their values, old or new.</strong> No password, hash, token, signing key, social
 * security number, card number, telephone number, government identifier, date of birth or electronic
 * funds account identifier may appear in the message, in {@link #getAffectedRecord()} or in any value
 * handed to a constructor. Identify a record by its key, never by its content. This class holds no
 * record payload and no snapshot, so it cannot leak one on its own; the obligation is on the caller
 * composing the message.
 *
 * <h2>What deliberately does not live here</h2>
 *
 * <ul>
 *   <li><strong>No retry, no re-read and merge, no last writer wins, no automatic resolution.</strong>
 *       The source abandons the write and redisplays the current record - L2612 sets
 *       {@code ACUP-SHOW-DETAILS} rather than retrying. Any auto resolution would change
 *       behaviour.</li>
 *   <li><strong>No sixth outcome</strong> for the rollback distinction, for the reason measured
 *       above.</li>
 *   <li><strong>No annotation of any kind and no logging.</strong> An exception is thrown; the caller
 *       logs it and chooses the status. A logger here would double log every failure and put a side
 *       effect in a constructor.</li>
 *   <li><strong>No framework or persistence import.</strong> The store level failure arrives as a
 *       {@link Throwable} cause and is treated as one, which keeps this package dependent on
 *       {@code java.lang} alone.</li>
 *   <li><strong>No batch reject code.</strong> Those are business outcomes that drive a batch exit
 *       status and are never thrown; none is referenced, stored or named here.</li>
 *   <li><strong>No status rendering.</strong> The four character file status diagnostic belongs to the
 *       file status enum and its mapper, and duplicating it would create a second source of truth for a
 *       value that must be byte identical to the legacy output.</li>
 *   <li><strong>No custom serialization hook.</strong> Rule 1 Clause D names insecure deserialization
 *       as a pattern to flag, so there is no object stream callback, no serialization proxy, no
 *       instance replacement and no alternative externalization contract - only the pinned
 *       {@code serialVersionUID} the compiler lint requires. No instance is ever rebuilt from
 *       untrusted input.</li>
 * </ul>
 *
 * <h2>Configuration and defaults</h2>
 *
 * <p>This type has no configuration. It reads no property, no profile, no environment variable and no
 * system property, and it depends on no clock, locale or charset, so construction is deterministic on
 * every host. Only two defaults exist, and both are deliberate rather than incidental:
 *
 * <ul>
 *   <li>The outcome defaults to {@code null} when a message only constructor is used, which records
 *       that no outcome was identified rather than inventing one.</li>
 *   <li>The affected record name defaults to {@code null} when the argument is absent or blank, and is
 *       otherwise stored with surrounding whitespace removed and letter case left exactly as supplied,
 *       because no case operation is performed anywhere in this class.</li>
 * </ul>
 *
 * <h2>Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>{@link #getOutcome()} returns null.</em> The instance was built through a message only
 *       constructor, which records no outcome. That is permitted and documented, but at a real throw
 *       site it means the outcome was not identified - use an outcome carrying constructor. Callers
 *       branching on the outcome must handle null explicitly.</li>
 *   <li><em>Every update request is rejected with {@link Outcome#DATA_CHANGED_BEFORE_UPDATE}.</em>
 *       Almost certainly the date of birth offset asymmetry above: the snapshot is being compared whole
 *       or stored in the ten character dash separated form instead of the eight character compact one.
 *       Check the component offsets before suspecting contention.</li>
 *   <li><em>An update is accepted that the legacy screen would have rejected, or the reverse.</em>
 *       Suspect the case asymmetry - a lower case comparison applied where the source upper cases, or a
 *       case function applied to one of the seven fields the source compares raw.</li>
 *   <li><em>{@link Throwable#getCause()} is null after wrapping a store level conflict.</em> The throw
 *       site used a constructor without a cause. Switch to a cause carrying form; this class never
 *       drops a cause it was handed.</li>
 *   <li><em>A caller cannot tell which rewrite failed.</em> Expected:
 *       {@link Outcome#LOCKED_BUT_UPDATE_FAILED} is one condition name used at two sites. Read
 *       {@link #getAffectedRecord()}, which is what that field is for.</li>
 *   <li><em>A customer lock failure is reported as success.</em> That is BLOCKER 5.2 faithfully
 *       reproduced, not a Java defect. The internal outcome is still
 *       {@link Outcome#COULD_NOT_LOCK_CUSTOMER} and should be logged and alerted on even though the
 *       response mirrors the legacy screen.</li>
 *   <li><em>The build fails with a {@code serial} warning.</em> A serializable type was added without
 *       pinning {@code serialVersionUID}. The build compiles with {@code -Xlint:all -Werror} and
 *       {@code failOnWarning}, so the declaration is mandatory rather than advisory.</li>
 * </ul>
 *
 * <h2>Testing</h2>
 *
 * <p>Instances are immutable, hold no static state and depend on no clock, locale or charset, so
 * behaviour is deterministic. Tests live under {@code src/test/java/com/cardemo/unit} and not in this
 * package; they assert that all five outcomes are distinct, that each carries its verbatim literal and
 * marker, that a cause survives construction and that
 * {@link Outcome#COULD_NOT_LOCK_CUSTOMER} is constructible.
 *
 * @see CardDemoException
 */
public class ConcurrentUpdateException extends CardDemoException {

    /**
     * Fixed serialization identity. {@link Throwable} already implements
     * {@link java.io.Serializable}, so every type in this hierarchy is unavoidably serializable and
     * must pin this value explicitly: the build runs {@code -Xlint:all} with {@code -Werror} and
     * {@code failOnWarning}, which turns the {@code serial} lint into a compilation failure. A fixed
     * literal is used rather than a computed default so that the identity does not shift when the class
     * is edited. Both instance fields below are themselves serializable - {@link Outcome} by
     * construction, as every enum is, and {@link String} inherently - so the default mechanism
     * inherited from {@link Throwable} reconstructs this type without any custom hook.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The five reasons an account update is abandoned, each mapped to the legacy construct it
     * represents.
     *
     * <p>This enum is <strong>nested deliberately</strong>. It is the vocabulary of exactly one
     * exception, so keeping it here holds the outcome and its meaning in one place rather than
     * scattering a general purpose enum across the tree, and being an enum it is serializable by
     * construction, which makes it safe as a field of a {@link Throwable}.
     *
     * <p>There are <strong>five constants and there is no sixth</strong>. The two rewrite failure paths
     * of {@code app/cbl/COACTUPC.cbl} set the same condition name, so the source draws no distinction
     * between them and neither does this enum; see the class documentation for the measurement. Use
     * {@link ConcurrentUpdateException#getAffectedRecord()} when the failing dataset matters.
     *
     * <p>Each constant records two things measured from the source: the verbatim screen message literal
     * the legacy condition name carries, and the {@code ACUP-CHANGE-ACTION} value that the dispatch at
     * {@code app/cbl/COACTUPC.cbl:L2606-L2615} writes for it. The second is what makes the never tested
     * outcome visible in code rather than only in prose.
     */
    public enum Outcome {

        /**
         * The account record could not be locked for update, so nothing was attempted.
         *
         * <p>Legacy origin: {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE}, declared at
         * {@code app/cbl/COACTUPC.cbl:L517-L518} and set at L3912 inside the guard at L3907-L3915 when
         * {@code EXEC CICS READ ... UPDATE} against {@code ACCTDAT} returns other than
         * {@code DFHRESP(NORMAL)}. The flag is set only while no message has already been staged
         * (L3911); {@code INPUT-ERROR} is set unconditionally at L3910 either way.
         *
         * <p>Dispatch: tested at L2607, producing {@code ACUP-CHANGES-OKAYED-LOCK-ERROR} {@code 'L'} at
         * L2608. This outcome is reported to the user, unlike {@link #COULD_NOT_LOCK_CUSTOMER}.
         */
        COULD_NOT_LOCK_ACCOUNT("Could not lock account record for update", 'L'),

        /**
         * The customer record could not be locked for update, so nothing was attempted.
         *
         * <p><strong>Severity Blocker. This is the outcome the source records but never acts on, and it
         * is preserved rather than repaired.</strong>
         *
         * <p>Legacy origin: {@code COULD-NOT-LOCK-CUST-FOR-UPDATE}, declared at
         * {@code app/cbl/COACTUPC.cbl:L519-L520} and set at L3939 inside the guard at L3934-L3942 when
         * {@code EXEC CICS READ ... UPDATE} against {@code CUSTDAT} returns other than
         * {@code DFHRESP(NORMAL)}. Measured across the whole program, those are the only two lines on
         * which the condition name appears: it is declared, it is set, and it is never read.
         *
         * <p>Dispatch: <strong>none.</strong> No {@code WHEN} at L2606-L2615 tests it, so control
         * reaches {@code WHEN OTHER} at L2613 and L2614 sets {@code ACUP-CHANGES-OKAYED-AND-DONE}
         * {@code 'C'} - the success marker. The user is told the changes were okayed and done although
         * the account was never rewritten. That is why {@link #getChangeActionCode()} returns
         * {@code 'C'} here: the value is reported truthfully as the source produces it, not as it ought
         * to be.
         *
         * <p>The message literal below is therefore never displayed by the legacy program. It is
         * retained because it is the text the source assigns, and callers should log this outcome and
         * alert on it even while the response they return mirrors the legacy screen.
         */
        COULD_NOT_LOCK_CUSTOMER("Could not lock customer record for update", 'C'),

        /**
         * The record changed between the read that populated the screen and the write that would have
         * committed it, so the update was abandoned and the current values are redisplayed.
         *
         * <p>Legacy origin: {@code DATA-WAS-CHANGED-BEFORE-UPDATE}, declared at
         * {@code app/cbl/COACTUPC.cbl:L521-L522}, set at L4143 when the account comparison fails and at
         * L4189 when the customer comparison fails, and tested at L3950 to skip the write.
         *
         * <p>Dispatch: tested at L2611, producing {@code ACUP-SHOW-DETAILS} {@code 'S'} at L2612 - the
         * source redisplays the record rather than retrying, which is why no retry exists here.
         *
         * <p>This is also the outcome a store level optimistic lock failure maps to. Keep the two
         * layers distinguishable by passing the framework exception as the cause: the business level
         * comparison arrives without one, the store level failure with one.
         */
        DATA_CHANGED_BEFORE_UPDATE("Record changed by some one else. Please review", 'S'),

        /**
         * Both records were locked and the snapshot matched, but a rewrite failed.
         *
         * <p>Legacy origin: {@code LOCKED-BUT-UPDATE-FAILED}, declared at
         * {@code app/cbl/COACTUPC.cbl:L523-L524} and set at <strong>two</strong> sites - L4079 after the
         * account rewrite of L4065-L4071 fails, with no backout, and L4098 after the customer rewrite of
         * L4085-L4091 fails, followed by {@code EXEC CICS SYNCPOINT ROLLBACK} at L4099-L4101. The source
         * uses one condition name for both, so this one constant covers both and
         * {@link ConcurrentUpdateException#getAffectedRecord()} carries the distinction the flag loses.
         *
         * <p>Dispatch: tested at L2609, producing {@code ACUP-CHANGES-OKAYED-BUT-FAILED} {@code 'F'} at
         * L2610.
         */
        LOCKED_BUT_UPDATE_FAILED("Update of record failed", 'F'),

        /**
         * The edited values were acceptable but the change was not confirmed, so no write was
         * attempted.
         *
         * <p>Legacy origin: {@code ACUP-CHANGES-OK-NOT-CONFIRMED}, the {@code 'N'} value of
         * {@code ACUP-CHANGE-ACTION} declared at {@code app/cbl/COACTUPC.cbl:L664}. The write sequence
         * runs only when that state coincides with the confirmation key, {@code CCARD-AID-PFK05}
         * (L2602-L2603); without it the branch at L2620-L2621 is taken and does nothing but
         * {@code CONTINUE}, leaving the marker at {@code 'N'} and the confirmation prompt on screen.
         *
         * <p>This is the one outcome with <strong>no literal</strong> in the L517-L524 block, because
         * the source communicates it through the confirmation prompt rather than an error message;
         * {@link #getLegacyMessage()} returns an empty string here and callers must supply their own
         * text.
         *
         * <p>It is a precondition that was not met rather than a conflict: nothing was read for update,
         * nothing changed underneath the caller and a resubmission carrying the confirmation is expected
         * to succeed. Callers should not map it to the same response as the four conflict outcomes.
         */
        CHANGES_NOT_CONFIRMED("", 'N');

        /**
         * The verbatim legacy screen message literal, reproduced byte for byte from the source.
         */
        private final String legacyMessage;

        /**
         * The {@code ACUP-CHANGE-ACTION} value the legacy dispatch writes for this outcome.
         */
        private final char changeActionCode;

        /**
         * Binds an outcome to the legacy text and marker measured for it.
         *
         * <p>Side effects: none. Enum construction only; nothing is logged, read or written.
         *
         * @param legacyMessage    the verbatim message literal from
         *                         {@code app/cbl/COACTUPC.cbl:L517-L524}, or an empty string for the one
         *                         outcome the source expresses without a literal. Never null, because
         *                         every constant supplies it
         * @param changeActionCode the {@code ACUP-CHANGE-ACTION} value from the dispatch at
         *                         {@code app/cbl/COACTUPC.cbl:L2606-L2615}
         */
        Outcome(String legacyMessage, char changeActionCode) {
            this.legacyMessage = legacyMessage;
            this.changeActionCode = changeActionCode;
        }

        /**
         * Returns the verbatim legacy screen message literal for this outcome.
         *
         * <p>The text is reproduced exactly as the source declares it, spelling and spacing included -
         * {@code some one} really is two words in
         * {@link Outcome#DATA_CHANGED_BEFORE_UPDATE}. Callers rendering it must not paraphrase or
         * correct it, because the parity comparison is byte for byte.
         *
         * <p>Side effects: none. Pure accessor over an immutable field.
         *
         * @return the literal, never null; <strong>empty</strong> for
         *         {@link Outcome#CHANGES_NOT_CONFIRMED}, which the source expresses without a message
         *         literal. Callers that display it must handle the empty case explicitly
         */
        public String getLegacyMessage() {
            return legacyMessage;
        }

        /**
         * Returns the {@code ACUP-CHANGE-ACTION} value the legacy dispatch writes for this outcome.
         *
         * <p>The values are {@code 'L'} for an account lock failure, {@code 'S'} for a detected change,
         * {@code 'F'} for a failed rewrite, {@code 'N'} for an unconfirmed change and - for
         * {@link Outcome#COULD_NOT_LOCK_CUSTOMER} - {@code 'C'}, the success marker reached through
         * {@code WHEN OTHER} because no branch tests that flag. The {@code 'C'} is reported as the
         * source produces it and must not be silently corrected; see the class documentation.
         *
         * <p>Side effects: none. Pure accessor over an immutable field.
         *
         * @return the single character marker, always one of {@code 'L'}, {@code 'C'}, {@code 'S'},
         *         {@code 'F'} or {@code 'N'}
         */
        public char getChangeActionCode() {
            return changeActionCode;
        }
    }

    /**
     * Which of the five reasons applies, or null when the throw site did not identify one.
     */
    private final Outcome outcome;

    /**
     * The name of the dataset involved, or null when none was stated. A name only - never content.
     */
    private final String affectedRecord;

    /**
     * Creates an exception that reports an abandoned update without naming which of the five outcomes
     * applies.
     *
     * <p>This form exists to mirror the base type's shape and to serve call sites that genuinely have
     * no outcome to state. Prefer {@link #ConcurrentUpdateException(Outcome, String)} at any real throw
     * site: an unidentified outcome cannot be mapped to a response, and distinguishing the five is the
     * entire purpose of this type. No underlying throwable is recorded by this form.
     *
     * <p>Side effects: none beyond throwable construction. Nothing is logged, no metric is recorded and
     * no state outside this instance is read or written.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}. Permitted
     *                to be null or blank and passed through unchanged, matching the base type. It must
     *                not contain a secret or a personally identifiable value, and in particular must
     *                not contain the old or new value of any compared field, because exception messages
     *                are logged
     */
    public ConcurrentUpdateException(String message) {
        super(message);
        this.outcome = null;
        this.affectedRecord = null;
    }

    /**
     * Creates an exception that reports an abandoned update, preserving the throwable that caused it but
     * without naming which of the five outcomes applies.
     *
     * <p>Use {@link #ConcurrentUpdateException(Outcome, String, Throwable)} instead wherever the
     * outcome is known, which at a wrapping site it normally is: a store level optimistic lock failure
     * is {@link Outcome#DATA_CHANGED_BEFORE_UPDATE}. The cause is delegated to the base type and is
     * never dropped, which is what keeps the store level and business level concurrency layers
     * separately diagnosable.
     *
     * <p>Side effects: none beyond throwable construction. The cause is neither inspected, unwrapped,
     * re-thrown nor logged.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}. Permitted
     *                to be null or blank and passed through unchanged. It must carry no secret and no
     *                compared field value
     * @param cause   the underlying throwable, retrievable through {@link Throwable#getCause()}.
     *                Permitted to be null, which records that there is no underlying failure to
     *                attribute. When non null it is always retained
     */
    public ConcurrentUpdateException(String message, Throwable cause) {
        super(message, cause);
        this.outcome = null;
        this.affectedRecord = null;
    }

    /**
     * Creates an exception for a named outcome, for the business level detections that have no
     * underlying throwable.
     *
     * <p>This is the form for the outcomes the source produces on its own: a lock that could not be
     * taken, a snapshot that no longer matches, a rewrite that failed, a change that was not confirmed.
     * None of those arrives as a caught exception, so there is genuinely no cause to preserve and none
     * is recorded.
     *
     * <p>Side effects: none beyond throwable construction.
     *
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}. Permitted
     *                to be null or blank and passed through unchanged; when null, callers can still
     *                render {@link Outcome#getLegacyMessage()} for the legacy text. It must carry no
     *                secret and no compared field value - at most the <em>names</em> of the fields that
     *                differed
     * @param outcome which of the five reasons applies. Permitted to be null, in which case
     *                {@link #getOutcome()} returns null exactly as it does for the message only forms;
     *                it is deliberately not rejected, because throwing from the constructor of an
     *                exception would mask the failure being reported and discard its cause. Supplying a
     *                non null value is expected at every real throw site
     */
    public ConcurrentUpdateException(Outcome outcome, String message) {
        super(message);
        this.outcome = outcome;
        this.affectedRecord = null;
    }

    /**
     * Creates an exception for a named outcome, preserving the throwable that caused it.
     *
     * <p>This is the form required when wrapping a store level conflict: the message supplies the
     * CardDemo level context, the outcome is normally
     * {@link Outcome#DATA_CHANGED_BEFORE_UPDATE}, and the framework exception is retained as the cause
     * so that the two concurrency layers remain distinguishable. Preserving it is Rule 1 Clause B's
     * requirement, not an option.
     *
     * <p>Side effects: none beyond throwable construction.
     *
     * @param outcome which of the five reasons applies. Permitted to be null, with the same meaning and
     *                for the same reason as in {@link #ConcurrentUpdateException(Outcome, String)}
     * @param message the detail message, retrievable through {@link Throwable#getMessage()}. Permitted
     *                to be null or blank. It must carry no secret and no compared field value
     * @param cause   the underlying throwable, retrievable through {@link Throwable#getCause()}.
     *                Permitted to be null, which records that there is no underlying failure to
     *                attribute. When non null it is always retained
     */
    public ConcurrentUpdateException(Outcome outcome, String message, Throwable cause) {
        this(outcome, message, null, cause);
    }

    /**
     * Creates an exception for a named outcome, naming the dataset involved and preserving the
     * throwable that caused it.
     *
     * <p>This is the fullest form and the one to use for
     * {@link Outcome#LOCKED_BUT_UPDATE_FAILED}, where naming the dataset recovers the only distinction
     * the legacy condition name loses: whether the account rewrite failed with nothing yet written, or
     * the customer rewrite failed after the account rewrite had already been applied inside the same
     * unit of work.
     *
     * <p>Side effects: none beyond throwable construction.
     *
     * @param outcome        which of the five reasons applies. Permitted to be null, with the same
     *                       meaning as in {@link #ConcurrentUpdateException(Outcome, String)}
     * @param message        the detail message, retrievable through {@link Throwable#getMessage()}.
     *                       Permitted to be null or blank. It must carry no secret and no compared field
     *                       value
     * @param affectedRecord the name of the dataset involved - the legacy file names are
     *                       {@code ACCTDAT} and {@code CUSTDAT}, declared at
     *                       {@code app/cbl/COACTUPC.cbl:L573-L576}. Permitted to be null or blank, both
     *                       of which are normalised to null so that {@link #getAffectedRecord()} never
     *                       returns a blank string; surrounding whitespace is stripped and the case is
     *                       left exactly as supplied, so no locale can influence the value.
     *                       <strong>This is a name, never content.</strong> It must never carry a key
     *                       value, a field value, a password, a card number, a social security number
     *                       or any other personally identifiable value
     * @param cause          the underlying throwable, retrievable through
     *                       {@link Throwable#getCause()}. Permitted to be null, which records that
     *                       there is no underlying failure to attribute. When non null it is always
     *                       retained
     */
    public ConcurrentUpdateException(Outcome outcome, String message, String affectedRecord,
            Throwable cause) {
        super(message, cause);
        this.outcome = outcome;
        this.affectedRecord = affectedRecord == null || affectedRecord.isBlank()
                ? null
                : affectedRecord.strip();
    }

    /**
     * Returns which of the five reasons the update was abandoned for.
     *
     * <p>Callers switch on this to choose a response, and must keep the five separately mapped:
     * collapsing them into one status discards information the legacy screen displayed.
     *
     * <p>Side effects: none. Pure accessor over an immutable field.
     *
     * @return the outcome, or <strong>null</strong> when the instance was created through one of the
     *         two message only constructors, which record none. Callers branching on the value must
     *         handle null explicitly - treat it as an unclassified abandonment rather than assuming any
     *         particular reason
     */
    public Outcome getOutcome() {
        return outcome;
    }

    /**
     * Returns the name of the dataset involved in the failure.
     *
     * <p>Its purpose is to recover the distinction that
     * {@link Outcome#LOCKED_BUT_UPDATE_FAILED} loses, because the source sets one condition name from
     * two different rewrite failures. It is diagnostic metadata and carries no outcome of its own,
     * which is precisely why adding it does not create a sixth outcome.
     *
     * <p>Side effects: none. Pure accessor over an immutable field.
     *
     * @return the dataset name as supplied with surrounding whitespace stripped, typically
     *         {@code ACCTDAT} or {@code CUSTDAT}, or <strong>null</strong> when none was stated or when
     *         a null or blank value was supplied. Never a blank string. It is a name only and never
     *         record content, so it is safe to log
     */
    public String getAffectedRecord() {
        return affectedRecord;
    }
}
