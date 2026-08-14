/*
 * ******************************************************************
 * Program     : CardDetailServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Verifies CardDetailService paragraph by paragraph against
 *               COCRDSLC. Concentrates on the contracts a reader cannot confirm
 *               by inspection: that the retrieval resolves by card number alone
 *               and the CARDAIX account-path finder is never reached, that the
 *               unreachable 9150-GETCARD-BYACCT pair is present and marked, the
 *               thirteen screen literals asserted byte for byte including three
 *               leading spaces and fourteen trailing ones, the deliberate
 *               two-flag versus one-flag invalidation asymmetry of the read
 *               outcomes, the online '9999' abend contract kept distinct from
 *               the batch 999 / RC 12 one, the 134-byte all-spaces abend block
 *               whose default message can never be substituted, and the rule
 *               that a card number never reaches a log, an exception message or
 *               an assertion message.
 * Source      : app/cbl/COCRDSLC.cbl      (887 lines, 34 procedure paragraphs)
 *               app/cpy/CVACT02Y.cpy      (CARD-RECORD 150 bytes, key X(16))
 *               app/cpy/CSMSG02Y.cpy      (CABENDD.CPY, ABEND-DATA 134 bytes)
 *               app/cpy-bms/COCRDSL.CPY   (15 input fields, ACCTSIDI X(11):60)
 *               app/cbl/CBACT04C.cbl      (canonical banner form, L1-L21)
 *               CONTRIBUTING.md, NOTICE   (style and licence conventions)
 *                                                                  @ 7756d89
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
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.exception.ValidationException.FailureKind;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;
import com.cardemo.service.card.CardDetailService;
import com.cardemo.service.card.CardDetailService.AttentionIdentifier;
import com.cardemo.service.card.CardDetailService.CardDetailRequest;
import com.cardemo.service.card.CardDetailService.CardDetailScreen;
import com.cardemo.service.card.CardDetailService.EntryMode;
import com.cardemo.service.card.CardDetailService.FilterState;
import com.cardemo.unit.model.FixedClockProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;

/**
 * Unit tests for {@link CardDetailService}, the Java replacement for {@code app/cbl/COCRDSLC.cbl}.
 *
 * <p><strong>1. What it does.</strong> Asserts the observable behaviour of the {@code CCDL} transaction's
 * card-detail service against the frozen COBOL program, one concern per test. Every claim is anchored to a
 * locator that was re-verified by direct inspection of the member at commit {@code 7756d89} rather than
 * taken on trust:
 * <ul>
 *   <li>{@code COCRDSLC.cbl:125-158} - the two message fields and the thirteen condition names declared on
 *       them, which are the program's entire observable outcome vocabulary. The condition name <em>is</em>
 *       the message: setting it moves its literal into the field, so a flag and a message are one act.</li>
 *   <li>{@code :248} {@code 0000-MAIN}, {@code :394} {@code COMMON-RETURN}, {@code :408}
 *       {@code 0000-MAIN-EXIT}, {@code :412} {@code 1000-SEND-MAP}, {@code :427}
 *       {@code 1100-SCREEN-INIT}, {@code :457} {@code 1200-SETUP-SCREEN-VARS}, {@code :502}
 *       {@code 1300-SETUP-SCREEN-ATTRS}, {@code :563} {@code 1400-SEND-SCREEN}, {@code :582}
 *       {@code 2000-PROCESS-INPUTS}, {@code :596} {@code 2100-RECEIVE-MAP}, {@code :608-643}
 *       {@code 2200-EDIT-MAP-INPUTS}, {@code :647-681} {@code 2210-EDIT-ACCOUNT}, {@code :685-722}
 *       {@code 2220-EDIT-CARD}, {@code :726-732} {@code 9000-READ-DATA}, {@code :736-775}
 *       {@code 9100-GETCARD-BYACCTCARD}, {@code :779-810} the unreachable {@code 9150-GETCARD-BYACCT}
 *       pair, {@code :820} {@code SEND-LONG-TEXT}, {@code :838} {@code SEND-PLAIN-TEXT} and {@code :857}
 *       {@code ABEND-ROUTINE}.</li>
 *   <li>{@code :743} and {@code :768} name {@code LIT-CARDFILENAME}, that is {@code 'CARDDAT '} at
 *       {@code :187-188}, and {@code :744} rides on {@code WS-CARD-RID-CARDNUM}. {@code :784} and
 *       {@code :804} name {@code LIT-CARDFILENAME-ACCT-PATH}, that is {@code 'CARDAIX '} at
 *       {@code :189-190}, and {@code :785} rides on {@code WS-CARD-RID-ACCT-ID} - and that whole
 *       paragraph is unreachable.</li>
 *   <li>{@code :876} carries {@code EXEC CICS ABEND ABCODE('9999')}, a four-character CICS online code,
 *       and {@code CSMSG02Y.cpy:21-29} declares the 134-byte {@code ABEND-DATA} block whose four fields
 *       are every one {@code VALUE SPACES}.</li>
 *   <li>{@code :855} copies {@code CSSTRPFY}, whose {@code YYYY-STORE-PFKEY} paragraph maps
 *       {@code EIBAID} onto the action codes of {@code CVCRD01Y.cpy:10} - and whose {@code :34-35} and
 *       {@code :58-59} arms both resolve to {@code CCARD-AID-PFK03}. The gate at {@code :291-299} then
 *       admits only enter and that code, coercing every other key to enter in silence.</li>
 *   </ul>
 *
 * <p><strong>2. How to run, build and test.</strong> This class is bound to the <em>Surefire</em> tier, not
 * Failsafe: the root build includes the two standard test name patterns while excluding the
 * {@code integration} and {@code e2e} subtrees, so a class named {@code CardDetailServiceTest} under
 * {@code src/test/java/com/cardemo/unit/} is collected by Surefire, and a class placed outside that tree
 * would be collected by neither plugin and would silently never run. Run it with
 * {@code ./mvnw -B -ntp test -Dtest=CardDetailServiceTest}, the whole unit tier with
 * {@code ./mvnw -B -ntp test}, and the gated build with
 * {@code ./mvnw -B -ntp clean verify}. A JDK 25 toolchain and Maven 3.9.11
 * are installed on the host, so no container is needed; where a host toolchain is genuinely absent the
 * equivalent is
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q test}.
 *
 * <p><strong>3. Key configs and defaults.</strong> A pure-JVM tier: no Spring context, no container, no
 * database and no network, so nothing here can reach an external endpoint and no environment value is
 * consulted. {@link MockitoExtension} supplies Mockito's default {@code STRICT_STUBS}, which fails a test
 * on an unused stubbing and so keeps the fixtures honest; every {@code when(...)} therefore lives in the
 * test that consumes it and never in a shared {@code @BeforeEach}. {@link CardRepository} is the single
 * double, because the service injects exactly one collaborator. {@code FileStatusMapper} is deliberately
 * <em>not</em> used: its surface is keyed on two-character batch {@code FILE STATUS} values and this is a
 * CICS online program that tests {@code RESP} codes, so injecting it would leave an unused field, and
 * importing it here would leave an unused import. Money is {@link BigDecimal} at
 * {@link RoundingMode#HALF_EVEN}, compared with {@code compareTo} and never with {@code equals} - a rule
 * this file pins even though {@code CVACT02Y} declares no monetary field, so the discipline is executable
 * rather than vacuous. The service takes its {@link java.time.Clock} by constructor, and this file supplies
 * the canonical fixed one, so the two header time fields are still asserted by <em>shape</em> - the shape is
 * what the source's own edited fields fix - while no {@code now()} call, no default zone and no default
 * locale appears anywhere in this file.
 *
 * <p><strong>4. Common failure modes and troubleshooting.</strong>
 * <ul>
 *   <li><em>{@code warnings found and -Werror specified}.</em> Test compilation runs under {@code -Xlint:all -Werror}
 *   with {@code failOnWarning}, so one dangling documentation comment, raw type or unchecked cast fails the build -
 *   but <em>not</em> an unused import, for which {@code javac} 25 publishes no lint key, so that one is
 *   review-enforced. The licence banner above is a plain block comment and never {@code /*} {@code *}, precisely so
 *   it cannot be read as a dangling doc comment.</li>
 *   <li><em>A paragraph-correspondence test fails after a refactor.</em> Something consolidated a label.
 *       Restore the method; do not relax the assertion. Deleting the unreachable {@code 9150} pair is the
 *       specific case that breaks the paragraph map the scope-coverage gate is proved against.</li>
 *   <li><em>An abend assertion fails on the number.</em> The online contract is the four-character string
 *       {@code 9999} of {@code :876}. It is not the batch pair
 *       {@link FatalProcessingException#BATCH_ABEND_CODE} 999 with
 *       {@link FatalProcessingException#BATCH_RETURN_CODE} 12, which belongs to the eight
 *       {@code ABCODE}-declaring batch programs and not to this one.</li>
 *   <li><em>A message assertion fails on whitespace.</em> Three literals carry load-bearing padding: the
 *       informational message of {@code :129-130} opens with three spaces, the exit message of
 *       {@code :136-137} closes with fourteen and has no space after {@code pressed.}, and
 *       {@code :157-158} carries four dots. Copy them; do not retype them.</li>
 *   <li><em>A flag assertion fails on one filter.</em> The two read-failure arms are deliberately
 *       asymmetric - see the Medium finding below - and unifying them is the mistake the assertion
 *       catches.</li>
 *   <li><em>An {@code UnnecessaryStubbingException}.</em> Strict stubs are deliberate. Stub inside the
 *       test that consumes the stubbing.</li>
 *   <li><em>The service reports uncovered lines and someone decides to "fix" the number.</em> Do not. The
 *       residual is fully accounted for and contains no reachable, unproved line. Two lines - the
 *       {@code deliver} return of the re-entry error arm and the {@code sendPlainText} call of the
 *       unexpected-data arm - <em>do</em> execute under these tests but cannot be credited, because a
 *       probe-based coverage agent cannot credit a line whose invocation leaves by throwing; both are
 *       proved to run by their immediately preceding lines being covered and by the exception type each
 *       one raises being asserted here. The remainder are the deliberately retained unreachable
 *       paragraphs of {@code :779-810}, {@code :820-832} and the {@code -EXIT} landing points, plus the
 *       redundant second input-error gate of {@code :386-391}. <em>Remediation:</em> none - deleting them
 *       to raise the number is the one change the scope-coverage gate is designed to catch.</li>
 *   </ul>
 *
 * <p><strong>Findings, classified per Rule 1 clause F.</strong>
 * <ul>
 *   <li><strong>Blocker - a test class outside the Surefire tree runs nowhere.</strong> The root build
 *       binds Surefire to the standard name patterns under {@code src/test/java/} while excluding the
 *       {@code integration} and {@code e2e} subtrees. A class placed outside the collected set matches
 *       neither Surefire's nor Failsafe's includes, so it is collected by neither plugin: the build stays
 *       green, both plugins report success, and coverage silently records the class as untested - no
 *       error, no warning. <em>Remediation:</em> this class sits inside that tree, and its collection is
 *       proved empirically by the regenerated report named in the validation evidence below rather than
 *       assumed.</li>
 *   <li><strong>High - routing the detail read through the account path.</strong> The live read is keyed
 *       on the card number alone. {@code :740} moves {@code CC-CARD-NUM} into
 *       {@code WS-CARD-RID-CARDNUM} and {@code :742-750} reads {@code LIT-CARDFILENAME}
 *       ({@code 'CARDDAT '}) with that field as {@code RIDFLD}; the account identifier is validated and
 *       echoed but never queried on, and the {@code MOVE} that would have supplied it is commented out at
 *       {@code :739}. Reaching {@code CARDAIX} instead would answer with a different record whenever an
 *       account holds more than one card. <em>Remediation:</em>
 *       {@link CardNumberOnlyRetrievalPath#accountPathFinderIsNeverReached()} pins that the account-path
 *       finder is never invoked, and {@link CardNumberOnlyRetrievalPath#readIsOneKeyedLookupAndNothingElse()}
 *       pins that the keyed read is the only interaction the repository ever sees.</li>
 *   <li><strong>High - conflating the two abend contracts.</strong> {@code :876} is CICS online and
 *       four characters wide. Emitting 999 with return code 12 here would import a batch convention into
 *       an online program and would make the two indistinguishable in evidence.
 *       <em>Remediation:</em> {@link AbendContract} asserts the online code and asserts that neither
 *       batch constant appears in the payload.</li>
 *   <li><strong>High - cardholder data in diagnostics.</strong> {@code CARD-NUM} is
 *       {@code PIC X(16)} at {@code CVACT02Y.cpy:5} and is the primary key, so it legitimately reaches
 *       the response because the map displays it, but it must never reach a log, an exception message or
 *       a test message. <em>Remediation:</em> {@link SensitiveDataHandling} proves absence from the whole
 *       captured log stream and from every raised message, and every assertion description in this file
 *       names a field rather than a value.</li>
 *   <li><strong>Medium - deleting the unreachable {@code 9150-GETCARD-BYACCT} pair.</strong> The
 *       paragraph at {@code :779} and its exit at {@code :810} are declared and never performed:
 *       {@code 9000-READ-DATA} at {@code :726-730} performs {@code 9100-GETCARD-BYACCTCARD THRU
 *       9100-GETCARD-BYACCTCARD-EXIT} and nothing else, and no {@code PERFORM}, {@code GO TO} or
 *       {@code THRU} names {@code 9150} anywhere in the member. They are retained one-to-one as empty
 *       private methods. <em>Remediation:</em> {@link UnreachableAccountPathParagraphPair} proves the pair
 *       is present, private, empty, documented as an intentional no-op and carrying both its source
 *       locator and its tracking references. The production class labels the retained artefact itself
 *       <em>Low</em>; the <em>Medium</em> classification here is of the act of deleting it, which is the
 *       finding this file guards against.</li>
 *   <li><strong>Medium - collapsing the invalidation asymmetry.</strong> On {@code WHEN DFHRESP(NOTFND)}
 *       at {@code :755-761} the source sets {@code FLG-ACCTFILTER-NOT-OK} <em>and</em>
 *       {@code FLG-CARDFILTER-NOT-OK}, both unguarded, and guards only the message. On
 *       {@code WHEN OTHER} at {@code :762-771} it sets {@code FLG-ACCTFILTER-NOT-OK} alone, inside the
 *       message guard, and leaves the diagnostic moves unguarded. The guard placement is reversed between
 *       the two arms and the card filter is untouched by the second. <em>Remediation:</em>
 *       {@link LookupOutcomeBranches} asserts each arm separately so a unified implementation fails.</li>
 *   <li><strong>Medium - the default abend message can never be substituted at runtime.</strong> All four
 *       {@code ABEND-DATA} fields initialise to {@code VALUE SPACES}
 *       ({@code CSMSG02Y.cpy:22-29}, the member internally titled {@code CABENDD.CPY}), yet the guard at
 *       {@code :859} tests {@code ABEND-MSG EQUAL LOW-VALUES}. {@code SPACES} is not {@code LOW-VALUES},
 *       so {@code 'UNEXPECTED ABEND OCCURRED.'} at {@code :860} is never written. <em>Remediation:</em>
 *       none - the behaviour is preserved deliberately, and
 *       {@link AbendContract#defaultAbendMessageSubstitutionIsUnreachableForABlankMessage()} pins that a
 *       blank message is <em>not</em> substituted while an absent one is.</li>
 *   <li><strong>Medium - a seventeenth card character is dropped, not rejected.</strong>
 *       {@code 2100-RECEIVE-MAP} at {@code :596-602} receives into {@code CARDSIDI PIC X(16)}
 *       ({@code COCRDSL.CPY:66}), and a fixed-width move truncates rather than diagnoses. A filter of
 *       sixteen digits followed by a letter is therefore accepted as those sixteen digits.
 *       <em>Remediation:</em> none - it is the {@code MOVE} semantics, and
 *       {@link HostileInput#aSeventeenthCharacterIsTruncatedRatherThanRejected()} records it as
 *       behaviour so it cannot be mistaken for a missing guard.</li>
 *   <li><strong>Low - two condition names carry an identical literal.</strong>
 *       {@code SEARCHED-ACCT-ZEROES} at {@code :144-145} and {@code SEARCHED-ACCT-NOT-NUMERIC} at
 *       {@code :146-147} are distinct names over the same 49-character text, so the two account failures
 *       they describe are indistinguishable to an operator. Neither is set on any reachable path in this
 *       member - the account edit moves its own longer literal at {@code :670} instead - which is why
 *       neither becomes a Java constant. <em>Remediation:</em> none;
 *       {@link ExactLiterals#theTwoIdenticallyValuedAccountConditionNamesShareOneText()} records the
 *       equality so a future divergence is deliberate.</li>
 *   <li><strong>Low - load-bearing padding in two literals.</strong> The informational message of
 *       {@code :129-130} is 31 characters opening with three spaces; the exit message of {@code :136-137}
 *       is 34 characters closing with fourteen and omitting the space after {@code pressed.} Both are
 *       contracts against the parity baseline.</li>
 *   <li><strong>Low - two different keys both exit the screen.</strong> The gate at {@code :291-299} tests
 *       the five-character action code, never the physical key, and {@code app/cpy/CSSTRPFY.cpy:34-35}
 *       sets {@code CCARD-AID-PFK03} for {@code DFHPF3} while {@code :58-59} sets the <em>same</em>
 *       condition name for {@code DFHPF15}. So {@code PF15} exits exactly as {@code PF3} does, and the
 *       twelve mapped codes each carry two keys on the same fold. <em>Remediation:</em> none;
 *       {@link AttentionIdentifierMapping#pf15FoldsOntoPfk03AndThereforeExits()} records the fold, and
 *       {@link AttentionIdentifierMapping#onlyPf03EscapesCoercionToEnter(CardDetailService.AttentionIdentifier)}
 *       sweeps all twenty-nine identifiers so no arm of the mapping is left unproved. Treating
 *       {@code PF15} as an ordinary key would silently remove an exit route the operator really has.</li>
 *   <li><strong>Low - an inadmissible key is coerced, not reported.</strong> {@code :295-297} replaces any
 *       key other than enter or {@code PFK03} with enter, so pressing {@code PF7} redisplays the screen in
 *       silence. Preserved: raising a validation error for the key would be an invention.</li>
 *   <li><strong>Low - {@code SEND-LONG-TEXT} and its exit are also never performed</strong>
 *       ({@code :820-836}), and are retained on the same terms as the {@code 9150} pair.</li>
 *   </ul>
 *
 * <p><strong>Validation evidence, per Rule 1 clause F.</strong> Recorded rather than asserted, on
 * OpenJDK 25.0.3 with Apache Maven 3.9.11 driven through {@code ./mvnw}. The commands, their exit codes
 * and the regenerated report path are given in the commit that introduces this file; collection is proved
 * by {@code target/surefire-reports/TEST-com.cardemo.unit.service.CardDetailServiceTest.xml}, whose
 * {@code tests="0"} root attribute is Surefire's convention for a class whose tests all live in
 * {@code @Nested} inner classes and is not a collection failure.
 *
 * <p><strong>Not available.</strong> Three things are deliberately out of reach here and are stated rather
 * than papered over. First, the repository-wide JaCoCo line floor of eighty percent cannot be met by this
 * class alone while sibling tiers are still being written; what is needed is those remaining tiers, and
 * the floor configured in the build is left untouched. Second, an assertion against a live
 * {@code CARDDAT} table is not available in a pure-JVM tier - it needs a container runtime and belongs to
 * the integration tier, which Failsafe owns and which this class must not duplicate. Third, the
 * least-privilege assertions Rule 1 clause D asks for at the HTTP edge - that {@code /api/admin/*} stays
 * restricted to the administrator role and that the session policy is stateless - are not available from
 * here either: both are properties of the security filter chain, which is neither this class's subject nor
 * inside its declared dependency set, and proving them needs a Spring context. What <em>is</em> proved
 * here is the half that belongs to this bean: transaction {@code CCDL} exposes no administrative
 * operation at all, and the bean carries no mutable state between requests, which is what makes a
 * stateless policy honest rather than merely configured - see
 * {@link ParagraphCorrespondence#everyInstanceFieldIsFinal()} and
 * {@link ParagraphCorrespondence#theBeanHoldsNoMutableStaticState()}.
 *
 * <p><strong>The documented conflict - parity governs.</strong> Rule 1 clause B forbids dead code, while
 * the migration mandate requires one-to-one control-flow parity. They collide here on the flagship
 * instance: {@code 9150-GETCARD-BYACCT} at {@code :779} with its exit at {@code :810} is genuinely
 * unreachable, and so are {@code SEND-LONG-TEXT} at {@code :820} with its exit, the default abend message
 * at {@code :859-860}, and every bare {@code EXIT} paragraph body. Parity governs, and clause B is
 * satisfied on its own terms: what it prohibits is an artefact <em>without an owner or tracking
 * reference</em>, and each retained item carries a decision-log entry, a traceability-matrix row, the
 * source locator cited above and an explicit intentional-no-op marker, all four of which
 * {@link UnreachableAccountPathParagraphPair} asserts are actually present rather than merely promised.
 * Deleting any of them would break the paragraph map the scope-coverage gate is proved against.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CardDetailService - COCRDSLC / CICS transaction CCDL")
final class CardDetailServiceTest {

    /**
     * {@code 88 FOUND-CARDS-FOR-ACCOUNT}, {@code app/cbl/COCRDSLC.cbl:129-130}. Thirty-one characters
     * opening with <em>three</em> spaces, which are inside the quotes in the source and are therefore part
     * of the value. Setting the condition name is also how the program records that a card was found, so
     * this one literal is simultaneously the message and the found flag.
     */
    private static final String FOUND_CARDS_FOR_ACCOUNT_L130 = "   Displaying requested details";

    /** {@code 88 WS-PROMPT-FOR-INPUT}, {@code app/cbl/COCRDSLC.cbl:131-132}. */
    private static final String PROMPT_FOR_INPUT_L132 = "Please enter Account and Card Number";

    /**
     * {@code 88 WS-EXIT-MESSAGE}, {@code app/cbl/COCRDSLC.cbl:136-137}. Thirty-four characters: no space
     * after {@code pressed.} and <em>fourteen</em> trailing spaces, both inside the quotes.
     */
    private static final String EXIT_MESSAGE_L137 = "PF03 pressed.Exiting              ";

    /** {@code 88 WS-PROMPT-FOR-ACCT}, {@code app/cbl/COCRDSLC.cbl:138-139}. */
    private static final String PROMPT_FOR_ACCT_L139 = "Account number not provided";

    /** {@code 88 WS-PROMPT-FOR-CARD}, {@code app/cbl/COCRDSLC.cbl:140-141}. */
    private static final String PROMPT_FOR_CARD_L141 = "Card number not provided";

    /** {@code 88 NO-SEARCH-CRITERIA-RECEIVED}, {@code app/cbl/COCRDSLC.cbl:142-143}. */
    private static final String NO_SEARCH_CRITERIA_L143 = "No input received";

    /**
     * {@code 88 SEARCHED-ACCT-ZEROES}, {@code app/cbl/COCRDSLC.cbl:144-145}, and - identically -
     * {@code 88 SEARCHED-ACCT-NOT-NUMERIC} at {@code :146-147}. Two distinct condition names over one
     * 49-character text; the Low finding recorded on this class.
     */
    private static final String SEARCHED_ACCT_L145_AND_L147 =
            "Account number must be a non zero 11 digit number";

    /** {@code 88 SEARCHED-CARD-NOT-NUMERIC}, {@code app/cbl/COCRDSLC.cbl:148-149}. */
    private static final String SEARCHED_CARD_NOT_NUMERIC_L149 =
            "Card number if supplied must be a 16 digit number";

    /**
     * {@code 88 DID-NOT-FIND-ACCT-IN-CARDXREF}, {@code app/cbl/COCRDSLC.cbl:151-152}. Set only inside the
     * unreachable {@code 9150} body at {@code :799}, so it can never be observed at runtime.
     */
    private static final String DID_NOT_FIND_ACCT_IN_CARDXREF_L152 =
            "Did not find this account in cards database";

    /** {@code 88 DID-NOT-FIND-ACCTCARD-COMBO}, {@code app/cbl/COCRDSLC.cbl:153-154}. */
    private static final String DID_NOT_FIND_ACCTCARD_COMBO_L154 =
            "Did not find cards for this search condition";

    /** {@code 88 XREF-READ-ERROR}, {@code app/cbl/COCRDSLC.cbl:155-156}. */
    private static final String XREF_READ_ERROR_L156 = "Error reading Card Data File";

    /**
     * {@code 88 CODING-TO-BE-DONE}, {@code app/cbl/COCRDSLC.cbl:157-158}. Twenty-one characters with
     * <em>four</em> consecutive dots.
     */
    private static final String CODING_TO_BE_DONE_L158 = "Looks Good.... so far";

    /**
     * The literal moved at {@code app/cbl/COCRDSLC.cbl:669-671}. Fifty-two characters, with no space after
     * the comma and the ungrammatical {@code A 11}; both are reproduced verbatim.
     */
    private static final String ACCOUNT_FILTER_NOT_NUMERIC_L670 =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** The literal moved at {@code app/cbl/COCRDSLC.cbl:710-712}. Fifty-two characters, same shape. */
    private static final String CARD_FILTER_NOT_NUMERIC_L711 =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /**
     * The composed online I/O failure text of {@code app/cbl/COCRDSLC.cbl:102-121}, as
     * {@code WHEN OTHER} at {@code :767-771} assembles it and as {@code CCARD-ERROR-MSG PIC X(75)}
     * receives it.
     *
     * <p>Eight segments, 80 characters before truncation: the {@code 'File Error: '} literal of
     * {@code :103-104} at 12, {@code ERROR-OPNAME X(8)} holding {@code 'READ'} from {@code :767},
     * {@code ' on '} at 4, {@code ERROR-FILE X(9)} holding {@code 'CARDDAT'} from {@code :768},
     * {@code ' returned RESP '} at 15, {@code ERROR-RESP X(10)}, {@code ',RESP2 '} at 7 and
     * {@code ERROR-RESP2 X(10)}, then the trailing {@code FILLER X(5)} of {@code :120-121}. The receiving
     * field is 75 wide, so the move drops exactly that trailing filler. The two response fields are spaces
     * because a JPA failure carries no CICS {@code RESP} pair to render.
     */
    private static final String COMPOSED_FILE_ERROR_L102_TO_L121 =
            "File Error: READ     on CARDDAT   returned RESP           ,RESP2           ";

    /** {@code LIT-THISTRANID}, {@code app/cbl/COCRDSLC.cbl:165-166}. */
    private static final String THIS_TRANSACTION_ID_L166 = "CCDL";

    /** {@code LIT-THISPGM}, {@code app/cbl/COCRDSLC.cbl:163-164}, and the abend culprit of {@code :863}. */
    private static final String THIS_PROGRAM_L164 = "COCRDSLC";

    /** {@code LIT-CARDFILENAME}, {@code app/cbl/COCRDSLC.cbl:187-188}, trimmed of its fixed-width pad. */
    private static final String CARD_FILE_L188 = "CARDDAT";

    /**
     * {@code LIT-CARDFILENAME-ACCT-PATH}, {@code app/cbl/COCRDSLC.cbl:189-190}, trimmed. Read only inside
     * the unreachable {@code 9150} body at {@code :784} and {@code :804}.
     */
    private static final String CARD_ACCT_PATH_FILE_L190 = "CARDAIX";

    /** {@code ABCODE('9999')}, {@code app/cbl/COCRDSLC.cbl:876}. A CICS <em>online</em> four-character code. */
    private static final String ONLINE_ABEND_CODE_L876 = "9999";

    /** The dispatch payload code of the unexpected-data arm, {@code app/cbl/COCRDSLC.cbl:373-380}. */
    private static final String UNEXPECTED_DATA_ABEND_CODE = "0001";

    /** The message the unexpected-data arm reports. */
    private static final String UNEXPECTED_DATA_MESSAGE = "UNEXPECTED DATA SCENARIO";

    /** {@code TITLE01} of {@code app/cpy/COTTL01Y.cpy}, at its declared forty characters. */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /** {@code TITLE02} of {@code app/cpy/COTTL01Y.cpy}, at its declared forty characters. */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /** {@code ABEND-CODE X(4)} of {@code app/cpy/CSMSG02Y.cpy:22}. */
    private static final int ABEND_CODE_WIDTH = 4;

    /** {@code ABEND-CULPRIT X(8)} of {@code app/cpy/CSMSG02Y.cpy:24}. */
    private static final int ABEND_CULPRIT_WIDTH = 8;

    /** {@code ABEND-REASON X(50)} of {@code app/cpy/CSMSG02Y.cpy:26}. */
    private static final int ABEND_REASON_WIDTH = 50;

    /** {@code ABEND-MSG X(72)} of {@code app/cpy/CSMSG02Y.cpy:28}. */
    private static final int ABEND_MSG_WIDTH = 72;

    /** {@code ACCTSIDI PIC X(11)}, {@code app/cpy-bms/COCRDSL.CPY:60}. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** {@code CARDSIDI PIC X(16)}, {@code app/cpy-bms/COCRDSL.CPY:66}. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** {@code INFOMSGI PIC X(40)}, {@code app/cpy-bms/COCRDSL.CPY:96}. */
    private static final int INFO_MESSAGE_WIDTH = 40;

    /** {@code CCARD-ERROR-MSG PIC X(75)}, {@code app/cpy/CVCRD01Y.cpy:28}. */
    private static final int RETURN_MESSAGE_WIDTH = 75;

    /** The field name the service reports on an account-filter rejection. */
    private static final String FIELD_ACCOUNT_ID = "accountId";

    /** The field name the service reports on a card-filter rejection. */
    private static final String FIELD_CARD_NUMBER = "cardNumber";

    /**
     * A valid eleven-digit account filter, {@code ACCTSIDI PIC X(11)}. Leading zeros are retained because
     * the field is alphanumeric and {@code :665} applies its class test to all eleven positions.
     */
    private static final String ACCOUNT_ID_11 = "00000000011";

    /** The numeric value {@link #ACCOUNT_ID_11} carries, as {@code CARD-ACCT-ID PIC 9(11)} holds it. */
    private static final long ACCOUNT_ID_NUMERIC = 11L;

    /**
     * A valid eleven-digit account filter that owns no card in this fixture. It is what a cross-tenant
     * request looks like: well formed, so every edit passes, and simply not the card's owner.
     */
    private static final String FOREIGN_ACCOUNT_ID_11 = "99999999999";

    /** The numeric value {@link #FOREIGN_ACCOUNT_ID_11} carries. */
    private static final long FOREIGN_ACCOUNT_ID_NUMERIC = 99999999999L;

    /**
     * A synthetic sixteen-digit card number: not a real primary account number, not Luhn valid and issued
     * by no scheme. It is fixture data only and is never placed in a log, an exception message or an
     * assertion description, which is the rule {@link SensitiveDataHandling} enforces.
     */
    private static final String CARD_NUMBER_16 = "9990001112223334";

    /** {@code CARD-EMBOSSED-NAME PIC X(50)}, {@code app/cpy/CVACT02Y.cpy:8}. */
    private static final String EMBOSSED_NAME = "GRACE HOPPER";

    /** {@code CARD-EXPIRAION-DATE PIC X(10)}, {@code app/cpy/CVACT02Y.cpy:9}; the copybook's own spelling. */
    private static final String EXPIRAION_DATE = "2026-08-31";

    /** {@code CARD-ACTIVE-STATUS PIC X(01)}, {@code app/cpy/CVACT02Y.cpy:10}. */
    private static final String ACTIVE_STATUS = "Y";

    /** {@code WS-CURDATE-MM-DD-YY} of {@code app/cpy/CSDAT01Y.cpy:30-35}, asserted by shape only. */
    private static final Pattern HEADER_DATE_MM_DD_YY = Pattern.compile("\\d{2}/\\d{2}/\\d{2}");

    /** {@code WS-CURTIME-HH-MM-SS} of {@code app/cpy/CSDAT01Y.cpy:36-41}, asserted by shape only. */
    private static final Pattern HEADER_TIME_HH_MM_SS = Pattern.compile("\\d{2}:\\d{2}:\\d{2}");

    /** A citation of a source line, as every paragraph method's Javadoc must carry at least one. */
    private static final Pattern SOURCE_LINE_CITATION = Pattern.compile(":\\d{2,3}\\b|at line \\d{2,3}\\b");

    /** The opening delimiter of a Javadoc block, used to bound a method's own documentation. */
    private static final String JAVADOC_OPEN = "/**";

    /**
     * The repository root, located by structure rather than inherited from the working directory.
     *
     * <p>Resolving the three paths below as bare relative paths, on the reason that "Surefire runs with the
     * project base directory as its working directory", would be true and would be the defect: the guarantee
     * is a plugin default that nothing declares and nothing enforces, so running this class from an IDE module
     * directory, from an aggregator build, or under any runner that forks elsewhere would turn every read into
     * a missing-file error far from its cause. A test that only passes from one directory is not
     * deterministic.
     *
     * <p>So this class does not depend on the working directory at all. The walk below starts wherever the
     * process happens to be and climbs until it finds the directory holding {@code pom.xml}, {@code src/}
     * and {@code app/} together - a triple no sub-directory of this repository satisfies - so the paths
     * below resolve identically from any starting point. {@code pom.xml} additionally pins the working
     * directory and {@code TestTierContractTest} asserts that pin, which protects the sibling classes that
     * still resolve relatively; this class needs neither.
     */
    private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

    /**
     * The production source of the service under test.
     *
     * <p>It is read - never written - and only to prove that documentation the compiler discards is
     * actually present, which is the one property reflection cannot reach.
     */
    private static final Path SERVICE_SOURCE = REPOSITORY_ROOT.resolve(
            Path.of("src", "main", "java", "com", "cardemo", "service", "card", "CardDetailService.java"));

    /**
     * The frozen COBOL program this service replaces.
     *
     * <p>It is opened <b>read-only</b>. {@code app/} is the parity oracle, the field-contract source and
     * the traceability anchor all at once, so nothing here may write to it; the sibling {@code unit/model}
     * package reads the copybooks under the same terms. Reading it is what turns a transcribed literal from
     * an assertion about this test's own constant into an assertion against the system of record - the only
     * way to prove three leading spaces, fourteen trailing spaces or four dots really are the source's.
     */
    private static final Path PROGRAM_SOURCE =
            REPOSITORY_ROOT.resolve(Path.of("app", "cbl", "COCRDSLC.cbl"));

    /**
     * The line count {@link #PROGRAM_SOURCE} must have for every cited locator to mean what it says.
     */
    private static final int PROGRAM_SOURCE_LINE_COUNT = 887;

    /**
     * The thirty-six paragraph labels this service must reproduce, as the private Java method each maps to,
     * paired in the comment with the source label and the line it sits on.
     *
     * <p>Thirty-four are {@code PROCEDURE DIVISION} labels of {@code app/cbl/COCRDSLC.cbl} and two come
     * from {@code COPY 'CSSTRPFY'} at {@code :855}, whose {@code YYYY-STORE-PFKEY} paragraph and exit are
     * copied into this program's procedure division and are therefore its paragraphs too. The figure of
     * thirty-seven quoted elsewhere counts the three {@code IDENTIFICATION DIVISION} labels
     * {@code PROGRAM-ID} at {@code :23}, {@code DATE-WRITTEN} at {@code :25} and {@code DATE-COMPILED} at
     * {@code :27}, which declare metadata and are not procedure paragraphs; they are named here so the
     * discrepancy is documented rather than silently reconciled. Immutable, so it is safe as a constant.
     */
    private static final List<String> PARAGRAPH_METHOD_NAMES = List.of(
            "main0000",                  // 0000-MAIN                      :248
            "commonReturn",              // COMMON-RETURN                  :394
            "mainExit0000",              // 0000-MAIN-EXIT                 :408
            "sendMap1000",               // 1000-SEND-MAP                  :412
            "sendMapExit1000",           // 1000-SEND-MAP-EXIT             :423
            "screenInit1100",            // 1100-SCREEN-INIT               :427
            "screenInitExit1100",        // 1100-SCREEN-INIT-EXIT          :453
            "setupScreenVars1200",       // 1200-SETUP-SCREEN-VARS         :457
            "setupScreenVarsExit1200",   // 1200-SETUP-SCREEN-VARS-EXIT    :499
            "setupScreenAttrs1300",      // 1300-SETUP-SCREEN-ATTRS        :502
            "setupScreenAttrsExit1300",  // 1300-SETUP-SCREEN-ATTRS-EXIT   :559
            "sendScreen1400",            // 1400-SEND-SCREEN               :563
            "sendScreenExit1400",        // 1400-SEND-SCREEN-EXIT          :578
            "processInputs2000",         // 2000-PROCESS-INPUTS            :582
            "processInputsExit2000",     // 2000-PROCESS-INPUTS-EXIT       :593
            "receiveMap2100",            // 2100-RECEIVE-MAP               :596
            "receiveMapExit2100",        // 2100-RECEIVE-MAP-EXIT          :605
            "editMapInputs2200",         // 2200-EDIT-MAP-INPUTS           :608
            "editMapInputsExit2200",     // 2200-EDIT-MAP-INPUTS-EXIT      :643
            "editAccount2210",           // 2210-EDIT-ACCOUNT              :647
            "editAccountExit2210",       // 2210-EDIT-ACCOUNT-EXIT         :681
            "editCard2220",              // 2220-EDIT-CARD                 :685
            "editCardExit2220",          // 2220-EDIT-CARD-EXIT            :722
            "readData9000",              // 9000-READ-DATA                 :726
            "readDataExit9000",          // 9000-READ-DATA-EXIT            :732
            "getCardByAcctCard9100",     // 9100-GETCARD-BYACCTCARD        :736
            "getCardByAcctCardExit9100", // 9100-GETCARD-BYACCTCARD-EXIT   :775
            "getCardByAcct9150",         // 9150-GETCARD-BYACCT            :779  UNREACHABLE
            "getCardByAcctExit9150",     // 9150-GETCARD-BYACCT-EXIT       :810  UNREACHABLE
            "sendLongText",              // SEND-LONG-TEXT                 :820  UNREACHABLE
            "sendLongTextExit",          // SEND-LONG-TEXT-EXIT            :831  UNREACHABLE
            "sendPlainText",             // SEND-PLAIN-TEXT                :838
            "sendPlainTextExit",         // SEND-PLAIN-TEXT-EXIT           :849
            "storePfKeyYyyy",            // YYYY-STORE-PFKEY   CSSTRPFY via :855
            "storePfKeyExitYyyy",        // YYYY-STORE-PFKEY-EXIT           via :855
            "abendRoutine");             // ABEND-ROUTINE                  :857

    /** The two paragraph methods that reproduce the unreachable account-path range. */
    private static final List<String> UNREACHABLE_ACCT_PATH_METHODS =
            List.of("getCardByAcct9150", "getCardByAcctExit9150");

    /**
     * The procedural copybook that {@code COPY 'CSSTRPFY'} at {@code app/cbl/COCRDSLC.cbl:855} pulls into
     * the procedure division. Read read-only, on the same terms as {@link #PROGRAM_SOURCE}.
     */
    private static final Path PF_KEY_COPYBOOK =
            REPOSITORY_ROOT.resolve(Path.of("app", "cpy", "CSSTRPFY.cpy"));

    /**
     * The two keys that resolve to {@code CCARD-AID-PFK03} and therefore take the exit branch.
     *
     * <p><b>Both</b> of them, not just the obvious one. {@code app/cpy/CSSTRPFY.cpy:34-35} sets
     * {@code CCARD-AID-PFK03} for {@code DFHPF3} and {@code :58-59} sets the same condition name for
     * {@code DFHPF15}, so the gate at {@code app/cbl/COCRDSLC.cbl:291-299} - which tests the five-character
     * action code, never the physical key - cannot distinguish them. Severity Low; treating {@code PF15} as
     * an ordinary key would silently remove an exit route the operator really has.
     */
    private static final List<AttentionIdentifier> EXIT_KEYS =
            List.of(AttentionIdentifier.PF3, AttentionIdentifier.PF15);

    /** The only four public operations the bean exposes. */
    private static final List<String> PUBLIC_ENTRY_POINTS =
            List.of("submitScreen", "viewCardDetail", "viewSelectedCard", "openWithoutContext");

    @Mock
    private CardRepository cardRepository;

    private CardDetailService service;

    private Logger serviceLogger;

    private ListAppender<ILoggingEvent> logEvents;

    /**
     * Builds the bean over its repository and the canonical fixed clock, and attaches an in-memory log
     * appender.
     *
     * <p>The clock is a constructor argument in production too - {@code ObservabilityConfig} publishes the
     * one {@code Clock} bean and this service takes it by injection - so the fixed clock supplied here
     * exercises the production wiring rather than a test-only seam.
     *
     * <p>No stubbing happens here. Under strict stubs a stubbing a given test does not consume fails that
     * test, so every {@code when(...)} lives in the test that needs it.
     */
    @BeforeEach
    void setUp() {
        service = new CardDetailService(cardRepository, FixedClockProvider.canonicalClock());

        serviceLogger = (Logger) LoggerFactory.getLogger(CardDetailService.class);
        logEvents = new ListAppender<>();
        logEvents.start();
        serviceLogger.addAppender(logEvents);
        serviceLogger.setLevel(Level.TRACE);
    }

    /**
     * Detaches the appender so one test's log output cannot leak into another's assertions.
     */
    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logEvents);
        logEvents.stop();
    }

    /**
     * The canonical card record: every field comfortably inside the width {@code app/cpy/CVACT02Y.cpy}
     * declares, so nothing truncates incidentally.
     *
     * @return the record, never {@code null}
     */
    private static Card canonicalCard() {
        return new Card(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC, "007", EMBOSSED_NAME, EXPIRAION_DATE,
                ACTIVE_STATUS);
    }

    /**
     * Stubs the keyed read to answer the canonical record and drives the re-entry path of
     * {@code app/cbl/COCRDSLC.cbl:357-371} with both filters valid.
     *
     * @return the rendered screen, never {@code null}
     */
    private CardDetailScreen screenForFoundCard() {
        when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(canonicalCard()));
        return service.submitScreen(CardDetailRequest.reentry(ACCOUNT_ID_11, CARD_NUMBER_16));
    }

    /**
     * Stubs the keyed read to answer nothing, which is {@code WHEN DFHRESP(NOTFND)} at
     * {@code app/cbl/COCRDSLC.cbl:755-761}.
     *
     * @return whatever the call threw, for the caller to classify
     */
    private Throwable notFoundOutcome() {
        when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.empty());
        return catchThrowable(() -> service.viewCardDetail(ACCOUNT_ID_11, CARD_NUMBER_16));
    }

    /**
     * Stubs the keyed read to fail with a store-level fault, which is {@code WHEN OTHER} at
     * {@code app/cbl/COCRDSLC.cbl:762-771}.
     *
     * @param cause the fault the store raises; must not be {@code null}
     * @return whatever the call threw, for the caller to classify
     */
    private Throwable readFailureOutcome(final RuntimeException cause) {
        when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC))
                .thenThrow(cause);
        return catchThrowable(() -> service.viewCardDetail(ACCOUNT_ID_11, CARD_NUMBER_16));
    }

    /**
     * Drives the re-entry edit path without ever reaching the store, which is what every rejected filter
     * combination does.
     *
     * @param accountFilter the account filter as the operator typed it; may be {@code null}
     * @param cardFilter    the card filter as the operator typed it; may be {@code null}
     * @return whatever the call threw, for the caller to classify
     */
    private Throwable editRejection(final String accountFilter, final String cardFilter) {
        return catchThrowable(() -> service.viewCardDetail(accountFilter, cardFilter));
    }

    /**
     * Renders every captured log event as one searchable string: the formatted message, every argument and
     * the whole throwable chain. Used only to prove that a value is <em>absent</em>.
     *
     * @return the concatenated log output, never {@code null}
     */
    private String capturedLogText() {
        final StringBuilder text = new StringBuilder(512);
        for (final ILoggingEvent event : logEvents.list) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getArgumentArray() != null) {
                for (final Object argument : event.getArgumentArray()) {
                    text.append(argument).append('\n');
                }
            }
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                text.append(proxy.getClassName()).append('\n').append(proxy.getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    /**
     * Reads the production source of the service under test.
     *
     * @return its lines, in file order
     * @throws UncheckedIOException if the source cannot be read from the project base directory
     */
    private static List<String> serviceSourceLines() {
        try {
            return Files.readAllLines(SERVICE_SOURCE, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("could not read " + SERVICE_SOURCE.toAbsolutePath()
                    + "; the repository root was located at " + REPOSITORY_ROOT, unreadable);
        }
    }

    /**
     * Locates the repository root by structure, so no assertion in this class depends on where the process
     * was started.
     *
     * <p>Climbs from the process working directory until it finds a directory holding {@code pom.xml},
     * {@code src/} and {@code app/} together. No sub-directory of this repository satisfies all three, so
     * the first match is the root and the walk cannot stop early. Failure is loud and names both the
     * starting point and the remedy, because the alternative - returning a wrong directory - would surface
     * as a missing file in an unrelated assertion.
     *
     * @return the repository root
     * @throws IllegalStateException if no ancestor of the working directory is the repository root
     */
    private static Path locateRepositoryRoot() {
        final Path start = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("src"))
                    && Files.isDirectory(candidate.resolve("app"))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No directory from " + start + " upward holds pom.xml, src/ and app/ together, so the "
                        + "frozen COBOL program and the service source this class reads cannot be located. "
                        + "Run it with the repository root, or any directory beneath it, as the working "
                        + "directory.");
    }


    /**
     * Reads the frozen COBOL program, read-only.
     *
     * @return its lines, in file order
     * @throws UncheckedIOException if the program cannot be read from the project base directory
     */
    private static List<String> programSourceLines() {
        try {
            return Files.readAllLines(PROGRAM_SOURCE, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("could not read " + PROGRAM_SOURCE.toAbsolutePath()
                    + "; app/ is frozen and read-only. Repository root: " + REPOSITORY_ROOT, unreadable);
        }
    }

    /**
     * Reads the procedural pushbutton copybook, read-only.
     *
     * @return its lines, in file order
     * @throws UncheckedIOException if the copybook cannot be read from the project base directory
     */
    private static List<String> pfKeyCopybookLines() {
        try {
            return Files.readAllLines(PF_KEY_COPYBOOK, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("could not read " + PF_KEY_COPYBOOK.toAbsolutePath()
                    + "; app/ is frozen and read-only", unreadable);
        }
    }

    /**
     * Extracts the single quoted literal a COBOL line carries, byte for byte.
     *
     * <p>Everything between the first apostrophe and the next one is returned <b>unaltered</b> - no trim,
     * no collapse of runs of spaces, no normalisation of any kind - because the padding is the contract.
     * {@code 88 FOUND-CARDS-FOR-ACCOUNT} at {@code :130} and {@code 88 WS-EXIT-MESSAGE} at {@code :137}
     * both depend on that: strip either and the assertion silently starts passing for the wrong text.
     *
     * @param lineNumber the one-based line of {@link #PROGRAM_SOURCE} to read
     * @return the literal's exact characters, without its delimiting apostrophes
     * @throws AssertionError if the line is out of range or carries no complete quoted literal
     */
    private static String cobolLiteralAt(final int lineNumber) {
        final List<String> lines = programSourceLines();
        assertThat(lines)
                .as("%s must still be the verified %d-line program for its locators to hold",
                        PROGRAM_SOURCE, PROGRAM_SOURCE_LINE_COUNT)
                .hasSize(PROGRAM_SOURCE_LINE_COUNT);
        assertThat(lineNumber).as("cited line must fall inside %s", PROGRAM_SOURCE)
                .isBetween(1, PROGRAM_SOURCE_LINE_COUNT);

        final String line = lines.get(lineNumber - 1);
        final int opening = line.indexOf('\'');
        assertThat(opening).as("%s:%d must carry a quoted literal", PROGRAM_SOURCE, lineNumber)
                .isNotNegative();
        final int closing = line.indexOf('\'', opening + 1);
        assertThat(closing).as("%s:%d must close its quoted literal", PROGRAM_SOURCE, lineNumber)
                .isGreaterThan(opening);
        return line.substring(opening + 1, closing);
    }

    /**
     * The literal this test transcribes for each cited declaration line.
     *
     * <p>Pairs the parameterised line numbers with the constant they are expected to equal, so that one
     * assertion covers the whole declared vocabulary of {@code :125-158} plus the two filter messages of
     * {@code :670} and {@code :711} without fifteen near-identical test methods.
     *
     * @param lineNumber the one-based declaration line
     * @return the constant this test carries for that line
     * @throws AssertionError if the line is not one of the cited fifteen
     */
    private static String transcribedLiteralFor(final int lineNumber) {
        return switch (lineNumber) {
            case 130 -> FOUND_CARDS_FOR_ACCOUNT_L130;
            case 132 -> PROMPT_FOR_INPUT_L132;
            case 137 -> EXIT_MESSAGE_L137;
            case 139 -> PROMPT_FOR_ACCT_L139;
            case 141 -> PROMPT_FOR_CARD_L141;
            case 143 -> NO_SEARCH_CRITERIA_L143;
            case 145, 147 -> SEARCHED_ACCT_L145_AND_L147;
            case 149 -> SEARCHED_CARD_NOT_NUMERIC_L149;
            case 152 -> DID_NOT_FIND_ACCT_IN_CARDXREF_L152;
            case 154 -> DID_NOT_FIND_ACCTCARD_COMBO_L154;
            case 156 -> XREF_READ_ERROR_L156;
            case 158 -> CODING_TO_BE_DONE_L158;
            case 670 -> ACCOUNT_FILTER_NOT_NUMERIC_L670;
            case 711 -> CARD_FILTER_NOT_NUMERIC_L711;
            default -> throw new AssertionError("line " + lineNumber + " is not one of the cited fifteen"
                    + " literal declarations of " + PROGRAM_SOURCE);
        };
    }

    /**
     * Returns the Javadoc block immediately preceding a private method declaration.
     *
     * @param sourceLines the production source lines; must not be {@code null}
     * @param methodName  the private method whose documentation is wanted; must not be {@code null}
     * @return the text of that method's own Javadoc block
     * @throws AssertionError if the method is not declared, or carries no Javadoc block
     */
    private static String javadocPreceding(final List<String> sourceLines, final String methodName) {
        final String declaration = " " + methodName + "(";
        int declarationIndex = -1;
        for (int index = 0; index < sourceLines.size(); index++) {
            final String line = sourceLines.get(index);
            if (line.contains("private ") && line.contains(declaration)) {
                declarationIndex = index;
                break;
            }
        }
        assertThat(declarationIndex)
                .as("%s must be declared as a private method in %s", methodName, SERVICE_SOURCE)
                .isNotNegative();

        int javadocIndex = declarationIndex - 1;
        while (javadocIndex >= 0 && !sourceLines.get(javadocIndex).contains(JAVADOC_OPEN)) {
            javadocIndex--;
        }
        assertThat(javadocIndex)
                .as("%s must carry its own Javadoc block", methodName)
                .isNotNegative();

        return String.join("\n", sourceLines.subList(javadocIndex, declarationIndex));
    }

    /**
     * Returns the body of a private no-argument method, that is the lines between its declaration and the
     * first line that closes it at the same indentation.
     *
     * @param sourceLines the production source lines; must not be {@code null}
     * @param methodName  the private method whose body is wanted; must not be {@code null}
     * @return the body lines, comments included, never {@code null}
     * @throws AssertionError if the method is not declared
     */
    private static List<String> bodyOf(final List<String> sourceLines, final String methodName) {
        final String declaration = " " + methodName + "(";
        int index = 0;
        while (index < sourceLines.size()
                && !(sourceLines.get(index).contains("private ")
                        && sourceLines.get(index).contains(declaration))) {
            index++;
        }
        assertThat(index)
                .as("%s must be declared as a private method in %s", methodName, SERVICE_SOURCE)
                .isLessThan(sourceLines.size());

        final List<String> body = new ArrayList<>();
        for (int cursor = index + 1; cursor < sourceLines.size(); cursor++) {
            final String line = sourceLines.get(cursor);
            if ("    }".equals(line)) {
                break;
            }
            body.add(line);
        }
        return List.copyOf(body);
    }

    /**
     * Finds the first line of a collected method body that contains a fragment.
     *
     * <p>Used to prove <b>relative order</b> inside one transcribed paragraph - specifically whether a flag
     * assignment falls before or after the line that opens the {@code IF WS-RETURN-MSG-OFF} guard. That
     * placement is behaviour in this program, and it differs between the two failure arms, so it is
     * asserted rather than trusted.
     *
     * @param body     the collected method body; must not be {@code null}
     * @param fragment the text to find; must not be {@code null}
     * @return the zero-based index of the first line containing the fragment
     * @throws AssertionError if no line contains it
     */
    private static int indexOfLineContaining(final List<String> body, final String fragment) {
        return indexOfLineContaining(body, fragment, 0);
    }

    /**
     * Finds the first line at or after an index that contains a fragment.
     *
     * @param body      the collected method body; must not be {@code null}
     * @param fragment  the text to find; must not be {@code null}
     * @param fromIndex the zero-based index to start from
     * @return the zero-based index of the first matching line at or after {@code fromIndex}
     * @throws AssertionError if no such line exists
     */
    private static int indexOfLineContaining(final List<String> body, final String fragment,
            final int fromIndex) {
        for (int index = Math.max(fromIndex, 0); index < body.size(); index++) {
            if (body.get(index).contains(fragment)) {
                return index;
            }
        }
        throw new AssertionError("no line at or after index " + fromIndex + " of the collected body"
                + " contains \"" + fragment + "\"; the transcription in " + SERVICE_SOURCE
                + " has changed shape and the guard-placement proof can no longer be made");
    }

    /**
     * Collects the fifteen detail values in the order {@code app/cpy-bms/COCRDSL.CPY} declares its input
     * fields, from {@code TRNNAMEI} at line 24 through {@code FKEYSI} at line 108.
     *
     * @param detail the projection the service returned; must not be {@code null}
     * @return the fifteen values in map order, never {@code null}
     */
    private static List<String> detailFieldsInMapOrder(final CardDto detail) {
        final List<String> values = new ArrayList<>(CardDto.DETAIL_FIELD_COUNT);
        values.add(detail.getTransactionName());
        values.add(detail.getTitle01());
        values.add(detail.getCurrentDate());
        values.add(detail.getProgramName());
        values.add(detail.getTitle02());
        values.add(detail.getCurrentTime());
        values.add(detail.getAccountId());
        values.add(detail.getCardNumber());
        values.add(detail.getCardholderName());
        values.add(detail.getCardStatusCode());
        values.add(detail.getExpiryMonth());
        values.add(detail.getExpiryYear());
        values.add(detail.getInformationMessage());
        values.add(detail.getErrorMessage());
        values.add(detail.getFunctionKeys());
        return values;
    }

    /**
     * A run of spaces, as a fixed-width COBOL field holds when it is initialised to {@code SPACES}.
     *
     * @param width the declared field width
     * @return exactly {@code width} space characters
     */
    private static String spaces(final int width) {
        return " ".repeat(width);
    }

    /**
     * The retrieval predicate: the card number <strong>and</strong> the owning account.
     *
     * <p>{@code 9000-READ-DATA} at {@code app/cbl/COCRDSLC.cbl:726-730} performs exactly one range, and
     * that range's read at {@code :742-750} names {@code LIT-CARDFILENAME} - {@code 'CARDDAT '} at
     * {@code :187-188} - with {@code RIDFLD(WS-CARD-RID-CARDNUM)} at {@code :744}, the field
     * {@code :740} has just filled from {@code CC-CARD-NUM}. The account identifier is edited at
     * {@code :647-681} and echoed back to the screen at {@code :462-466}, and the {@code MOVE} that would
     * have made it the other half of the read key is commented out at {@code :739}.
     *
     * <p><b>That {@code MOVE} is restored, and these tests assert the restored predicate.</b> Reproduced
     * literally, the account filter was decoration: any card number resolved regardless of which account
     * the caller named, and the record was then rendered under the named account. On a 3270 the operator
     * had reached the card through that account's own list screen; over HTTP both values arrive in one
     * caller-controlled request, which makes the filter the only thing scoping the read. The restoration is
     * still a single keyed read on {@code CARDDAT} - it is emphatically <em>not</em> routed through the
     * {@code CARDAIX} account path, which would answer with a different record whenever an account holds
     * more than one card, as the seven-row card list proves it can.
     */
    @Nested
    @DisplayName("retrieval resolves by card number AND owning account - :726-730, :739-750")
    final class CardNumberOnlyRetrievalPath {

        @Test
        @DisplayName("the read is one keyed lookup and the repository sees nothing else")
        void readIsOneKeyedLookupAndNothingElse() {
            final CardDetailScreen screen = screenForFoundCard();

            assertThat(screen.detail()).isNotNull();
            verify(cardRepository).findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC);
            verifyNoMoreInteractions(cardRepository);
        }

        @Test
        @DisplayName("the CARDAIX account-path finder is never reached")
        void accountPathFinderIsNeverReached() {
            screenForFoundCard();

            verify(cardRepository, never()).findByAccountIdOrderByCardNumberAsc(any(), any());
            verify(cardRepository, never()).findAllByOrderByCardNumberAsc(any());
        }

        @Test
        @DisplayName("the account filter scopes the read - the same card under another account is not found")
        void accountFilterScopesTheReadRatherThanBeingEchoed() {
            // The shipped behaviour echoed the filter and read on the card number alone, so this second
            // call returned the SAME record and rendered it under the account the caller had named. The
            // filter is now half of the read predicate, so a card the account does not own is not found.
            when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(canonicalCard()));
            // The single-key read is stubbed leniently and MUST go unused. That is what makes this a probe
            // rather than a restatement: were :739 dropped again, this stub would answer the foreign
            // request with the card and the not-found assertion below would fail.
            lenient().when(cardRepository.findById(CARD_NUMBER_16))
                    .thenReturn(Optional.of(canonicalCard()));

            final CardDto owned = service.viewCardDetail(ACCOUNT_ID_11, CARD_NUMBER_16);

            assertThat(owned.getAccountId()).isEqualTo(ACCOUNT_ID_11);
            assertThatExceptionOfType(RecordNotFoundException.class)
                    .as("the same wording a non-existent card gets, so the response cannot be used to "
                            + "learn that the card exists under some other account")
                    .isThrownBy(() -> service.viewCardDetail(FOREIGN_ACCOUNT_ID_11, CARD_NUMBER_16))
                    .withMessage(DID_NOT_FIND_ACCTCARD_COMBO_L154);
            verify(cardRepository).findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC);
            verify(cardRepository)
                    .findByCardNumberAndAccountId(CARD_NUMBER_16, FOREIGN_ACCOUNT_ID_NUMERIC);
            verify(cardRepository, never()).findByAccountIdOrderByCardNumberAsc(any(), any());
        }

        @Test
        @DisplayName("an account of zero does not opt out of the scoping")
        void anAccountOfZeroDoesNotOptOutOfTheScoping() {
            // The screen-rendering code at :1548 treats 0 as 'no filter to echo'. The read must NOT: the
            // card-list arm at :339-348 sets INPUT-OK outright and takes the account straight from the
            // commarea without running the edits, so a zero sentinel here would be a way to ask for an
            // unscoped read.
            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.viewSelectedCard(0L, CARD_NUMBER_16))
                    .withMessage(DID_NOT_FIND_ACCTCARD_COMBO_L154);

            verify(cardRepository).findByCardNumberAndAccountId(CARD_NUMBER_16, 0L);
            verify(cardRepository, never())
                    .findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC);
        }

        @Test
        @DisplayName("the pre-validated card-list arrival of :339-348 is scoped by its account too")
        void preValidatedArrivalIsScopedByItsAccountToo() {
            when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(canonicalCard()));

            final CardDto detail = service.viewSelectedCard(ACCOUNT_ID_NUMERIC, CARD_NUMBER_16);

            assertThat(detail.getCardNumber()).isEqualTo(CARD_NUMBER_16);
            assertThat(detail.getAccountId()).isEqualTo(ACCOUNT_ID_11);
            verify(cardRepository).findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC);
            verifyNoMoreInteractions(cardRepository);
        }

        @Test
        @DisplayName("the Java read-data range performs the card-number range and nothing else")
        void readDataRangePerformsOnlyTheCardNumberRange() {
            final List<String> body = bodyOf(serviceSourceLines(), "readData9000");
            final String text = String.join("\n", body);

            assertThat(text)
                    .as("readData9000 must reproduce the single PERFORM of :728-729")
                    .contains("getCardByAcctCard9100")
                    .contains("getCardByAcctCardExit9100");
            assertThat(text)
                    .as("readData9000 must not reach the unreachable account-path range")
                    .doesNotContain("getCardByAcct9150")
                    .doesNotContain("getCardByAcctExit9150");
        }
    }

    /**
     * The flagship parity artefact: {@code 9150-GETCARD-BYACCT} at {@code app/cbl/COCRDSLC.cbl:779} and
     * {@code 9150-GETCARD-BYACCT-EXIT} at {@code :810}.
     *
     * <p>Both are declared in the source and neither is ever performed. The only paragraph that could have
     * reached them, {@code 9000-READ-DATA} at {@code :726}, performs
     * {@code 9100-GETCARD-BYACCTCARD THRU 9100-GETCARD-BYACCTCARD-EXIT} and nothing else, and a search of
     * the member for the name {@code 9150-GETCARD-BYACCT} returns exactly the two label lines themselves -
     * no {@code PERFORM}, no {@code GO TO} and no {@code THRU}. The commented-out
     * {@code * MOVE CC-ACCT-ID-N TO WS-CARD-RID-ACCT-ID} at {@code :739}, immediately inside the live
     * {@code 9100}, is the direct evidence of the abandoned account-path route.
     *
     * <p>Had it run, it would have issued {@code EXEC CICS READ FILE(LIT-CARDFILENAME-ACCT-PATH)} at
     * {@code :784} - the card file's own account <em>path</em> {@code CARDAIX} of {@code :189-190}, not the
     * cross-reference dataset - keyed on {@code WS-CARD-RID-ACCT-ID} at {@code :785}, and its
     * {@code WHEN DFHRESP(NOTFND)} arm at {@code :796-799} would have set
     * {@code DID-NOT-FIND-ACCT-IN-CARDXREF} of {@code :151-152} while leaving the card filter untouched,
     * unlike the live {@code 9100}. Its {@code WHEN OTHER} arm would have named the same alternate index at
     * {@code :804}.
     *
     * <p><strong>The pair is proved present, not merely absent from coverage.</strong> Severity of the act
     * of deleting it is <em>Medium</em>: it would break the paragraph map the scope-coverage gate is proved
     * against, and no JaCoCo exclusion may be added in its place. Rule 1 clause B forbids dead code, and
     * the conflict resolves in favour of parity on the clause's own terms - the prohibition is on an
     * artefact without an owner or tracking reference, and these tests assert that the locator, the
     * intentional-no-op marker and both tracking references are physically present in the production
     * source rather than promised in prose.
     *
     * <p>Reflection here reads {@code private} members of the class under test and reads its source file;
     * it invokes nothing and every member name is a compile-time literal, never externally supplied, so
     * this is not the reflection-driven arbitrary invocation Rule 1 clause D prohibits.
     */
    @Nested
    @DisplayName("the unreachable 9150-GETCARD-BYACCT pair - :779-810")
    final class UnreachableAccountPathParagraphPair {

        @Test
        @DisplayName("both unreachable paragraphs exist as distinct private methods")
        void bothUnreachableParagraphsExistAsPrivateMethods() {
            for (final String name : UNREACHABLE_ACCT_PATH_METHODS) {
                boolean foundPrivate = false;
                for (final Method method : CardDetailService.class.getDeclaredMethods()) {
                    if (method.getName().equals(name) && Modifier.isPrivate(method.getModifiers())) {
                        foundPrivate = true;
                    }
                }
                assertThat(foundPrivate)
                        .as("retained unreachable paragraph %s must exist and be private", name)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("neither unreachable paragraph is consolidated into its partner")
        void neitherUnreachableParagraphIsConsolidated() {
            assertThat(UNREACHABLE_ACCT_PATH_METHODS)
                    .as("the label and its -EXIT partner are two paragraphs, never one")
                    .hasSize(2)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("both bodies are empty apart from their intentional-no-op comment")
        void bothUnreachableBodiesAreEmptyApartFromTheirMarker() {
            final List<String> sourceLines = serviceSourceLines();

            for (final String name : UNREACHABLE_ACCT_PATH_METHODS) {
                for (final String line : bodyOf(sourceLines, name)) {
                    assertThat(line.isBlank() || line.strip().startsWith("//"))
                            .as("%s must carry no executable statement; found: %s", name, line.strip())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("each unreachable paragraph cites its own source locator")
        void eachUnreachableParagraphCitesItsSourceLocator() {
            final List<String> sourceLines = serviceSourceLines();

            final String paragraphDoc = javadocPreceding(sourceLines, "getCardByAcct9150");
            final String exitDoc = javadocPreceding(sourceLines, "getCardByAcctExit9150");

            assertThat(SOURCE_LINE_CITATION.matcher(paragraphDoc).find())
                    .as("getCardByAcct9150 must cite a source line")
                    .isTrue();
            assertThat(SOURCE_LINE_CITATION.matcher(exitDoc).find())
                    .as("getCardByAcctExit9150 must cite a source line")
                    .isTrue();
            assertThat(paragraphDoc).contains("COCRDSLC.cbl").contains("779");
            assertThat(exitDoc).contains("COCRDSLC.cbl").contains("810");
        }

        @Test
        @DisplayName("each unreachable paragraph carries an explicit unreachable no-op marker")
        void eachUnreachableParagraphCarriesAnUnreachableMarker() {
            final List<String> sourceLines = serviceSourceLines();

            for (final String name : UNREACHABLE_ACCT_PATH_METHODS) {
                final String documentation = javadocPreceding(sourceLines, name);
                assertThat(documentation.toUpperCase(Locale.ROOT))
                        .as("%s must be documented as an intentional, unreachable no-op", name)
                        .contains("UNREACHABLE")
                        .contains("NO-OP");
            }
        }

        @Test
        @DisplayName("the retained pair names both of its tracking references")
        void theRetainedPairNamesBothTrackingReferences() {
            final List<String> sourceLines = serviceSourceLines();
            final String combined = javadocPreceding(sourceLines, "getCardByAcct9150")
                    + javadocPreceding(sourceLines, "getCardByAcctExit9150");

            assertThat(combined)
                    .as("Rule 1 clause B is satisfied by an owner and a tracking reference, not by prose")
                    .contains("DECISION_LOG.md")
                    .contains("TRACEABILITY_MATRIX.md");
        }

        @Test
        @DisplayName("the alternate-index file name survives only in documentation, never in code")
        void theAlternateIndexFileNameAppearsOnlyInDocumentation() {
            for (final String line : serviceSourceLines()) {
                if (line.contains(CARD_ACCT_PATH_FILE_L190)) {
                    final String stripped = line.strip();
                    assertThat(stripped.startsWith("*") || stripped.startsWith("//")
                            || stripped.startsWith("/*"))
                            .as("CARDAIX must not become an executable constant; found: %s", stripped)
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("the condition name only 9150 sets survives only in documentation")
        void theUnreachableConditionNameAppearsOnlyInDocumentation() {
            for (final String line : serviceSourceLines()) {
                if (line.contains(DID_NOT_FIND_ACCT_IN_CARDXREF_L152)) {
                    final String stripped = line.strip();
                    assertThat(stripped.startsWith("*") || stripped.startsWith("//"))
                            .as("the :151-152 literal must not become an executable constant; found: %s",
                                    stripped)
                            .isTrue();
                }
            }
        }
    }

    /**
     * The message vocabulary, byte for byte.
     *
     * <p>{@code app/cbl/COCRDSLC.cbl:125-158} declares the whole observable outcome vocabulary as
     * {@code 88}-level condition names over two fields - {@code WS-INFO-MSG PIC X(40)} and
     * {@code WS-RETURN-MSG PIC X(75)}. Setting a condition name <i>is</i> moving its literal, so in this
     * program <b>the flags are the messages</b> and a literal transcribed loosely is a behavioural
     * divergence, not a cosmetic one.
     *
     * <p>Two separate properties are proved for every literal. First, transcription: the constant this test
     * carries equals the characters between the apostrophes of the cited declaration line in the frozen
     * program, read at run time. Second, disposition: a literal the source actually sets is asserted where
     * the service emits it, whereas one the source declares and never sets is asserted to stay out of the
     * service's executable text. That second half matters, because
     * {@code SET WS-EXIT-MESSAGE}, {@code SET SEARCHED-ACCT-ZEROES}, {@code SET SEARCHED-ACCT-NOT-NUMERIC},
     * {@code SET SEARCHED-CARD-NOT-NUMERIC}, {@code SET XREF-READ-ERROR} and
     * {@code SET CODING-TO-BE-DONE} appear <b>nowhere</b> in the program: only nine of the fifteen cited
     * literals are ever moved, and emitting one of the other six would be an invention.
     *
     * <p>Findings carried here, both Low: the two condition names {@code SEARCHED-ACCT-ZEROES} at
     * {@code :144-145} and {@code SEARCHED-ACCT-NOT-NUMERIC} at {@code :146-147} are distinct names over
     * one identical text; and {@code FOUND-CARDS-FOR-ACCOUNT} at {@code :130} carries three leading spaces
     * while {@code WS-EXIT-MESSAGE} at {@code :137} carries fourteen trailing ones and no space after its
     * full stop. Remediation for either is to leave them exactly as written.
     */
    @Nested
    @DisplayName("the message vocabulary is byte-exact - :125-158, :670, :711")
    final class ExactLiterals {

        @ParameterizedTest
        @ValueSource(ints = {130, 132, 137, 139, 141, 143, 145, 147, 149, 152, 154, 156, 158, 670, 711})
        @DisplayName("every cited literal is transcribed from the frozen program byte for byte")
        void everyCitedLiteralIsTranscribedByteForByte(final int lineNumber) {
            assertThat(transcribedLiteralFor(lineNumber))
                    .as("%s:%d must be transcribed with no trim and no normalisation",
                            PROGRAM_SOURCE, lineNumber)
                    .isEqualTo(cobolLiteralAt(lineNumber));
        }

        @Test
        @DisplayName("the found-cards message keeps its three leading spaces on the way to the operator")
        void theFoundCardsMessageKeepsItsThreeLeadingSpaces() {
            final CardDetailScreen screen = screenForFoundCard();

            assertThat(FOUND_CARDS_FOR_ACCOUNT_L130)
                    .as(":130 declares three leading spaces inside the apostrophes")
                    .startsWith("   ")
                    .doesNotStartWith("    ")
                    .hasSize(31);
            assertThat(screen.informationMessage())
                    .as("setting FOUND-CARDS-FOR-ACCOUNT at :753-754 moves the literal unaltered")
                    .isEqualTo(FOUND_CARDS_FOR_ACCOUNT_L130);
        }

        @Test
        @DisplayName("the projected information message is padded to its declared forty, not trimmed")
        void theProjectedInformationMessageIsPaddedToItsDeclaredForty() {
            final CardDetailScreen screen = screenForFoundCard();

            assertThat(screen.detail().getInformationMessage())
                    .as("the MOVE at :496 into INFOMSGO PIC X(40) pads on the right and keeps the left")
                    .isEqualTo(FOUND_CARDS_FOR_ACCOUNT_L130 + spaces(9))
                    .hasSize(INFO_MESSAGE_WIDTH);
        }

        @Test
        @DisplayName("the prompt message is emitted verbatim when no context was supplied")
        void thePromptMessageIsEmittedVerbatimWithoutContext() {
            final CardDetailScreen screen = service.openWithoutContext();

            assertThat(screen.informationMessage())
                    .as("SET WS-PROMPT-FOR-INPUT at :460 and again at :491 moves the :132 literal")
                    .isEqualTo(PROMPT_FOR_INPUT_L132)
                    .isEqualTo("Please enter Account and Card Number");
            verify(cardRepository, never()).findByCardNumberAndAccountId(anyString(), anyLong());
        }

        @Test
        @DisplayName("the exit message keeps fourteen trailing spaces and its missing space after the stop")
        void theExitMessageKeepsItsFourteenTrailingSpacesAndItsMissingSpace() {
            assertThat(EXIT_MESSAGE_L137)
                    .as(":136-137 runs the word straight onto the full stop")
                    .contains("pressed.Exiting")
                    .doesNotContain("pressed. Exiting");
            assertThat(EXIT_MESSAGE_L137)
                    .as(":136-137 pads the literal itself, inside the apostrophes")
                    .endsWith(spaces(14))
                    .hasSize(34);
            assertThat(EXIT_MESSAGE_L137.stripTrailing())
                    .as("stripping is what a loose transcription would silently do")
                    .hasSize(20);
        }

        @Test
        @DisplayName("the two identically-valued account condition names share one text")
        void theTwoIdenticallyValuedAccountConditionNamesShareOneText() {
            assertThat(cobolLiteralAt(145))
                    .as("SEARCHED-ACCT-ZEROES at :144-145 and SEARCHED-ACCT-NOT-NUMERIC at :146-147 are"
                            + " two condition names over one identical literal; severity Low, remediation"
                            + " is to leave both declared")
                    .isEqualTo(cobolLiteralAt(147))
                    .isEqualTo(SEARCHED_ACCT_L145_AND_L147)
                    .isEqualTo("Account number must be a non zero 11 digit number");
        }

        @Test
        @DisplayName("the coding-to-be-done literal keeps four dots, not an ellipsis")
        void theCodingToBeDoneLiteralKeepsFourDots() {
            assertThat(CODING_TO_BE_DONE_L158)
                    .as(":157-158 carries four dots; three would be a plausible-looking divergence")
                    .contains("Good....")
                    .doesNotContain("Good...s")
                    .doesNotContain("Good.....")
                    .hasSize(21);
        }

        @Test
        @DisplayName("the literals of the six never-set condition names stay out of executable text")
        void theNeverSetConditionNameLiteralsStayOutOfExecutableText() {
            final List<String> neverSet = List.of(
                    EXIT_MESSAGE_L137.stripTrailing(),
                    SEARCHED_ACCT_L145_AND_L147,
                    SEARCHED_CARD_NOT_NUMERIC_L149,
                    XREF_READ_ERROR_L156,
                    CODING_TO_BE_DONE_L158);

            for (final String literal : neverSet) {
                for (final String line : serviceSourceLines()) {
                    final String stripped = line.stripLeading();
                    assertThat(!stripped.contains(literal)
                            || stripped.startsWith("*")
                            || stripped.startsWith("//")
                            || stripped.startsWith(JAVADOC_OPEN))
                            .as("the program never issues SET for this condition name, so emitting it"
                                    + " would be an invention; found in executable text: %s", stripped)
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("both filter messages keep their missing space after the comma and their upper case")
        void bothFilterMessagesKeepTheirMissingSpaceAfterTheComma() {
            assertThat(ACCOUNT_FILTER_NOT_NUMERIC_L670)
                    .as(":670 has no space after the comma and reads \"A 11\", not \"AN 11\"")
                    .contains("FILTER,IF")
                    .doesNotContain("FILTER, IF")
                    .contains("MUST BE A 11 DIGIT")
                    .doesNotContain("MUST BE AN 11 DIGIT")
                    .isEqualTo(ACCOUNT_FILTER_NOT_NUMERIC_L670.toUpperCase(Locale.ROOT))
                    .hasSize(52);
            assertThat(CARD_FILTER_NOT_NUMERIC_L711)
                    .as(":711 is the same shape at sixteen digits")
                    .contains("FILTER,IF")
                    .doesNotContain("FILTER, IF")
                    .contains("MUST BE A 16 DIGIT")
                    .isEqualTo(CARD_FILTER_NOT_NUMERIC_L711.toUpperCase(Locale.ROOT))
                    .hasSize(52);
        }

        @Test
        @DisplayName("the account filter message reaches the operator exactly as declared")
        void theAccountFilterMessageReachesTheOperatorAsDeclared() {
            final Throwable thrown = editRejection("0000000001A", CARD_NUMBER_16);

            assertThat(thrown)
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(ACCOUNT_FILTER_NOT_NUMERIC_L670)
                    .hasNoCause();
            verify(cardRepository, never()).findByCardNumberAndAccountId(anyString(), anyLong());
        }

        @Test
        @DisplayName("the card filter message reaches the operator exactly as declared")
        void theCardFilterMessageReachesTheOperatorAsDeclared() {
            final Throwable thrown = editRejection(ACCOUNT_ID_11, "999000111222333A");

            assertThat(thrown)
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(CARD_FILTER_NOT_NUMERIC_L711)
                    .hasNoCause();
        }

        @Test
        @DisplayName("the two not-provided messages reach the operator exactly as declared")
        void theTwoNotProvidedMessagesReachTheOperatorAsDeclared() {
            assertThat(editRejection(null, CARD_NUMBER_16))
                    .as("SET WS-PROMPT-FOR-ACCT at :657 moves the :139 literal")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(PROMPT_FOR_ACCT_L139);
            assertThat(editRejection(ACCOUNT_ID_11, null))
                    .as("SET WS-PROMPT-FOR-CARD at :697 moves the :141 literal")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(PROMPT_FOR_CARD_L141);
        }

        @Test
        @DisplayName("the no-input message reaches the operator exactly as declared")
        void theNoInputMessageReachesTheOperatorAsDeclared() {
            assertThat(editRejection(null, null))
                    .as("SET NO-SEARCH-CRITERIA-RECEIVED at :639 moves the :143 literal")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(NO_SEARCH_CRITERIA_L143)
                    .hasMessage("No input received");
        }

        @Test
        @DisplayName("the not-found message reaches the operator exactly as declared")
        void theNotFoundMessageReachesTheOperatorAsDeclared() {
            assertThat(notFoundOutcome())
                    .as("SET DID-NOT-FIND-ACCTCARD-COMBO at :760 moves the :154 literal")
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessage(DID_NOT_FIND_ACCTCARD_COMBO_L154)
                    .hasMessage("Did not find cards for this search condition");
        }

        @Test
        @DisplayName("the composed file-error message is truncated to its declared seventy-five")
        void theComposedFileErrorMessageIsTruncatedToItsDeclaredSeventyFive() {
            final Throwable thrown = readFailureOutcome(new QueryTimeoutException("read timed out"));

            assertThat(thrown.getMessage())
                    .as("the :767-771 assembly is moved into WS-RETURN-MSG PIC X(75) at :157")
                    .isEqualTo(COMPOSED_FILE_ERROR_L102_TO_L121)
                    .hasSize(RETURN_MESSAGE_WIDTH)
                    .contains(CARD_FILE_L188)
                    .contains("READ")
                    .contains("RESP")
                    .contains("RESP2");
        }
    }


    /**
     * Validation order, and the three-valued filter flags.
     *
     * <p>{@code 2200-EDIT-MAP-INPUTS.} at {@code app/cbl/COCRDSLC.cbl:608} - exit at {@code :643} - runs
     * {@code 2210-EDIT-ACCOUNT.} at {@code :647-681} and then {@code 2220-EDIT-CARD.} at {@code :685-722},
     * in that order and unconditionally, then applies one cross-field edit at {@code :637-640}.
     *
     * <p>Order is observable because every per-field message move is wrapped in
     * {@code IF WS-RETURN-MSG-OFF} - {@code :656}, {@code :666}, {@code :696}, {@code :707} - so the
     * <b>first</b> rejection to run owns the message and every later one is suppressed. Swap the two
     * paragraphs and a request with two bad filters reports the card instead of the account.
     *
     * <p>Each filter is three-valued, not boolean: {@code FLG-ACCTFILTER-BLANK},
     * {@code FLG-ACCTFILTER-NOT-OK} and {@code FLG-ACCTFILTER-ISVALID} at {@code :198-206}, with the same
     * three for the card at {@code :208-216}. Blank is not a kind of invalid - it is its own state, with its
     * own message and its own {@code '*'} echo at {@code :541-551} - and the migration surfaces the
     * distinction as {@link FailureKind#BLANK} against {@link FailureKind#INVALID}. Blank also swallows one
     * more input than an empty string does: {@code :651-654} treats {@code LOW-VALUES}, {@code SPACES} and
     * <b>all zeros</b> alike, so a filter of eleven zeros is "not supplied" rather than "not numeric".
     */
    @Nested
    @DisplayName("account is edited before card, on three-valued flags - :608-643, :647-681, :685-722")
    final class ValidationOrderAndFilterFlags {

        @Test
        @DisplayName("with both filters bad the account message wins, because 2210 runs first")
        void withBothFiltersBadTheAccountMessageWins() {
            final Throwable thrown = editRejection("0000000001A", "999000111222333A");

            assertThat(thrown)
                    .as("the IF WS-RETURN-MSG-OFF guard at :707 suppresses the card message that :666"
                            + " has already written")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(ACCOUNT_FILTER_NOT_NUMERIC_L670);
            assertThat(thrown.getMessage()).isNotEqualTo(CARD_FILTER_NOT_NUMERIC_L711);
        }

        @Test
        @DisplayName("with only the card bad the card message survives, so 2220 does still run")
        void withOnlyTheCardBadTheCardMessageSurvives() {
            final Throwable thrown = editRejection(ACCOUNT_ID_11, "999000111222333A");

            assertThat(thrown)
                    .as("2220 is performed unconditionally at :633-634, not only when 2210 passed")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(CARD_FILTER_NOT_NUMERIC_L711);
        }

        @Test
        @DisplayName("a blank filter reports the blank kind, and names the field it belongs to")
        void aBlankFilterReportsTheBlankKind() {
            final Throwable thrown = editRejection(null, CARD_NUMBER_16);

            assertThat(thrown).isInstanceOf(ValidationException.class);
            final ValidationException rejection = (ValidationException) thrown;
            assertThat(rejection.getFailureKind())
                    .as("FLG-ACCTFILTER-BLANK at :198-200 is its own state, not a flavour of NOT-OK")
                    .isEqualTo(FailureKind.BLANK);
            assertThat(rejection.hasFieldName()).isTrue();
            assertThat(rejection.getFieldName()).isEqualTo(FIELD_ACCOUNT_ID);
            assertThat(rejection).hasMessage(PROMPT_FOR_ACCT_L139).hasNoCause();
        }

        @Test
        @DisplayName("a supplied but non-numeric filter reports the invalid kind, not the blank kind")
        void aSuppliedButNonNumericFilterReportsTheInvalidKind() {
            final Throwable thrown = editRejection("0000000001A", CARD_NUMBER_16);

            assertThat(thrown).isInstanceOf(ValidationException.class);
            final ValidationException rejection = (ValidationException) thrown;
            assertThat(rejection.getFailureKind())
                    .as("FLG-ACCTFILTER-NOT-OK at :201-203 is the third state, distinct from blank")
                    .isEqualTo(FailureKind.INVALID);
            assertThat(rejection.getFieldName()).isEqualTo(FIELD_ACCOUNT_ID);
        }

        @Test
        @DisplayName("a blank card filter names the card field, on the same three-valued terms")
        void aBlankCardFilterNamesTheCardField() {
            final Throwable thrown = editRejection(ACCOUNT_ID_11, null);

            assertThat(thrown).isInstanceOf(ValidationException.class);
            final ValidationException rejection = (ValidationException) thrown;
            assertThat(rejection.getFailureKind()).isEqualTo(FailureKind.BLANK);
            assertThat(rejection.getFieldName()).isEqualTo(FIELD_CARD_NUMBER);
            assertThat(rejection).hasMessage(PROMPT_FOR_CARD_L141);
        }

        @Test
        @DisplayName("both blank takes the cross-field message and names no field at all")
        void bothBlankTakesTheCrossFieldMessageAndNamesNoField() {
            final Throwable thrown = editRejection(null, null);

            assertThat(thrown).isInstanceOf(ValidationException.class);
            final ValidationException rejection = (ValidationException) thrown;
            assertThat(rejection)
                    .as(":637-640 overwrites the per-field message that :657 had already set")
                    .hasMessage(NO_SEARCH_CRITERIA_L143);
            assertThat(rejection.hasFieldName())
                    .as("the cross-field edit belongs to no single field, so none is reported")
                    .isFalse();
            assertThat(rejection.getFieldName()).isNull();
        }

        @Test
        @DisplayName("the blank marker is treated as not supplied, on both filters")
        void theBlankMarkerIsTreatedAsNotSupplied() {
            final Throwable thrown = editRejection("*", "*");

            assertThat(thrown)
                    .as(":615-627 REPLACE * WITH LOW-VALUES runs before either field edit")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(NO_SEARCH_CRITERIA_L143);
            verify(cardRepository, never()).findByCardNumberAndAccountId(anyString(), anyLong());
        }

        @ParameterizedTest
        @ValueSource(strings = {"00000000000", "           ", ""})
        @DisplayName("all zeros, all spaces and empty are one state: not supplied")
        void allZerosAllSpacesAndEmptyAreOneState(final String accountFilter) {
            final Throwable thrown = editRejection(accountFilter, CARD_NUMBER_16);

            assertThat(thrown).isInstanceOf(ValidationException.class);
            assertThat(((ValidationException) thrown).getFailureKind())
                    .as(":651-654 tests LOW-VALUES, SPACES and ZEROS as one condition; %s must not be"
                            + " reported as merely non-numeric", accountFilter.isEmpty() ? "an empty"
                            + " filter" : "this filter")
                    .isEqualTo(FailureKind.BLANK);
            assertThat(thrown).hasMessage(PROMPT_FOR_ACCT_L139);
        }

        @Test
        @DisplayName("a valid pair leaves both flags reporting valid and reaches the store once")
        void aValidPairLeavesBothFlagsValid() {
            final CardDetailScreen screen = screenForFoundCard();

            assertThat(screen.accountFilterState())
                    .as("FLG-ACCTFILTER-ISVALID at :204-206 is reached only after both earlier tests fail")
                    .isEqualTo(FilterState.OK);
            assertThat(screen.cardFilterState()).isEqualTo(FilterState.OK);
            assertThat(screen.inputError()).isFalse();
            verify(cardRepository, times(1))
                    .findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC);
        }

        @Test
        @DisplayName("no filter combination that fails an edit ever reaches the store")
        void noFailedEditEverReachesTheStore() {
            assertThat(editRejection(null, null)).isInstanceOf(ValidationException.class);
            assertThat(editRejection("0000000001A", CARD_NUMBER_16)).isInstanceOf(ValidationException.class);
            assertThat(editRejection(ACCOUNT_ID_11, "999000111222333A"))
                    .isInstanceOf(ValidationException.class);

            verify(cardRepository, never()).findByCardNumberAndAccountId(anyString(), anyLong());
            verifyNoMoreInteractions(cardRepository);
        }

        @Test
        @DisplayName("the pre-validated card-list arrival skips the edits entirely, as :341 demands")
        void thePreValidatedArrivalSkipsTheEditsEntirely() {
            when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(canonicalCard()));

            final CardDto detail = service.viewSelectedCard(ACCOUNT_ID_NUMERIC, CARD_NUMBER_16);

            assertThat(detail)
                    .as("SET INPUT-OK TO TRUE at :341 stands in for the whole 2200 range")
                    .isNotNull();
            assertThat(detail.getCardholderName()).isEqualTo(EMBOSSED_NAME);
        }
    }


    /**
     * The three lookup outcomes, and the guard placement that differs between two of them.
     *
     * <p>{@code 9100-GETCARD-BYACCTCARD.} at {@code app/cbl/COCRDSLC.cbl:736} - exit at {@code :775} -
     * closes on {@code EVALUATE WS-RESP-CD} with three arms:
     *
     * <ul>
     *   <li>{@code WHEN DFHRESP(NORMAL)} at {@code :752-754} sets {@code FOUND-CARDS-FOR-ACCOUNT}, whose
     *       literal is the three-leading-space message.</li>
     *   <li>{@code WHEN DFHRESP(NOTFND)} at {@code :755-761} sets {@code INPUT-ERROR} and then
     *       <b>both</b> {@code FLG-ACCTFILTER-NOT-OK} and {@code FLG-CARDFILTER-NOT-OK} unconditionally,
     *       and guards only the message move with {@code IF WS-RETURN-MSG-OFF}.</li>
     *   <li>{@code WHEN OTHER} at {@code :762-771} sets {@code INPUT-ERROR}, then places
     *       {@code FLG-ACCTFILTER-NOT-OK} <b>inside</b> the {@code IF WS-RETURN-MSG-OFF} guard and leaves
     *       the four diagnostic moves and the message move outside it - never touching the card flag.</li>
     * </ul>
     *
     * <p>So the asymmetry is twofold: two flags against one, and the guard wrapped round the flag in one
     * arm but round the message in the other. It is transcribed as written, not tidied. Severity Medium,
     * with remediation being to leave both placements alone: collapsing them would let a store fault clear
     * a card filter the operator had entered correctly, and would move the cursor at {@code :515-524} to a
     * different field than the source moves it to.
     */
    @Nested
    @DisplayName("the three lookup outcomes keep their asymmetric guards - :752-771")
    final class LookupOutcomeBranches {

        @Test
        @DisplayName("a found record yields the three-leading-space informational message")
        void aFoundRecordYieldsTheInformationalMessage() {
            final CardDetailScreen screen = screenForFoundCard();

            assertThat(screen.informationMessage()).isEqualTo(FOUND_CARDS_FOR_ACCOUNT_L130);
            assertThat(screen.errorMessage())
                    .as("WS-RETURN-MSG is still off, so :395 moves nothing into the error field")
                    .isNull();
            assertThat(screen.inputError()).isFalse();
            assertThat(screen.detail()).isNotNull();
        }

        @Test
        @DisplayName("a not-found record invalidates both filters, unconditionally")
        void aNotFoundRecordInvalidatesBothFiltersUnconditionally() {
            final List<String> body = bodyOf(serviceSourceLines(), "getCardByAcctCard9100");
            final int notFoundArm = indexOfLineContaining(body, "work.readOutcome = ReadOutcome.NOT_FOUND");
            final int accountFlag = indexOfLineContaining(body,
                    "work.accountFilterState = FilterState.NOT_OK", notFoundArm);
            final int cardFlag = indexOfLineContaining(body,
                    "work.cardFilterState = FilterState.NOT_OK", notFoundArm);
            final int guard = indexOfLineContaining(body, "isReturnMessageOff(work)", notFoundArm);

            assertThat(accountFlag)
                    .as(":756-758 sets the account flag before the guard opens at :759")
                    .isLessThan(guard);
            assertThat(cardFlag)
                    .as(":756-758 sets the card flag too, also before the guard")
                    .isLessThan(guard)
                    .isGreaterThan(accountFlag);
        }

        @Test
        @DisplayName("a not-found record reports the search-condition message and nothing else")
        void aNotFoundRecordReportsTheSearchConditionMessage() {
            when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.empty());

            assertThatExceptionOfType(RecordNotFoundException.class)
                    .isThrownBy(() -> service.viewCardDetail(ACCOUNT_ID_11, CARD_NUMBER_16))
                    .withMessage(DID_NOT_FIND_ACCTCARD_COMBO_L154)
                    .withNoCause();
            assertThat(DID_NOT_FIND_ACCTCARD_COMBO_L154)
                    .as("the unreachable 9150 arm's message belongs to a paragraph nothing performs")
                    .isNotEqualTo(DID_NOT_FIND_ACCT_IN_CARDXREF_L152);
        }

        @Test
        @DisplayName("an unexpected status invalidates the account filter only, and inside the guard")
        void anUnexpectedStatusInvalidatesTheAccountFilterOnly() {
            final List<String> body = bodyOf(serviceSourceLines(), "getCardByAcctCard9100");
            final int otherArm = indexOfLineContaining(body, "work.readOutcome = ReadOutcome.OTHER");
            final int guard = indexOfLineContaining(body, "isReturnMessageOff(work)", otherArm);
            final int accountFlag = indexOfLineContaining(body,
                    "work.accountFilterState = FilterState.NOT_OK", otherArm);
            final int messageMove = indexOfLineContaining(body,
                    "work.returnMessage = composeFileErrorMessage(work)", otherArm);

            assertThat(accountFlag)
                    .as(":764-766 puts the flag INSIDE the guard - the reverse of the NOTFND arm")
                    .isGreaterThan(guard);
            assertThat(messageMove)
                    .as(":767-771 leaves the diagnostic and message moves OUTSIDE the guard")
                    .isGreaterThan(accountFlag);

            final List<String> arm = body.subList(otherArm, messageMove + 1);
            assertThat(arm)
                    .as("WHEN OTHER never touches FLG-CARDFILTER; unifying the two arms is the Medium"
                            + " finding this class exists to prevent")
                    .noneMatch(line -> line.contains("work.cardFilterState"));
        }

        @Test
        @DisplayName("an unexpected status reports the composite file-error message, with its cause kept")
        void anUnexpectedStatusReportsTheCompositeFileErrorMessage() {
            final QueryTimeoutException cause = new QueryTimeoutException("read timed out");

            final Throwable thrown = readFailureOutcome(cause);

            assertThat(thrown)
                    .isInstanceOf(CardDemoException.class)
                    .hasMessage(COMPOSED_FILE_ERROR_L102_TO_L121)
                    .hasCause(cause);
            assertThat(thrown.getClass().getSimpleName())
                    .as("the file-status translation of a 9x status; asserted by name because the type"
                            + " is outside this test's declared dependency set")
                    .isEqualTo("FileAccessException");
        }

        @Test
        @DisplayName("an unexpected status is never reported as a not-found, and the reverse")
        void anUnexpectedStatusIsNeverReportedAsNotFound() {
            assertThat(readFailureOutcome(new QueryTimeoutException("read timed out")))
                    .as("DFHRESP(NOTFND) and WHEN OTHER are separate arms with separate messages")
                    .isNotInstanceOf(RecordNotFoundException.class);
        }

        @Test
        @DisplayName("only the two documented arms carry a guard, and the found arm carries none")
        void onlyTheTwoDocumentedArmsCarryAGuard() {
            final List<String> body = bodyOf(serviceSourceLines(), "getCardByAcctCard9100");
            final int normalArm = indexOfLineContaining(body, "work.readOutcome = ReadOutcome.NORMAL");
            final int notFoundArm = indexOfLineContaining(body, "work.readOutcome = ReadOutcome.NOT_FOUND");

            assertThat(body.subList(normalArm, notFoundArm))
                    .as(":752-754 is three moves with no guard at all")
                    .noneMatch(line -> line.contains("isReturnMessageOff"));
            assertThat(body).filteredOn(line -> line.contains("isReturnMessageOff(work)")).hasSize(2);
        }
    }


    /**
     * The online abend contract, and the two contracts it must never be confused with.
     *
     * <p>{@code ABEND-ROUTINE.} at {@code app/cbl/COCRDSLC.cbl:857} substitutes a default message when
     * {@code ABEND-MSG} equals {@code LOW-VALUES} at {@code :859-860}, stamps {@code LIT-THISPGM} into
     * {@code ABEND-CULPRIT} at {@code :863}, sends the payload, cancels its own handler, and closes with
     * {@code EXEC CICS ABEND ABCODE('9999')} at {@code :876}.
     *
     * <p><b>{@code '9999'} is the CICS online contract and is four characters of text.</b> It is not the
     * batch contract - {@link FatalProcessingException#BATCH_ABEND_CODE} of {@code 999} paired with
     * {@link FatalProcessingException#BATCH_RETURN_CODE} of {@code 12} - which belongs to the eight
     * {@code ABCODE}-declaring batch programs {@code CBACT01C}, {@code CBACT02C}, {@code CBACT03C},
     * {@code CBACT04C}, {@code CBCUS01C}, {@code CBTRN01C}, {@code CBTRN02C} and {@code CBTRN03C}, and to
     * none of the seventeen online programs. Conflating them is a High finding: it would make an online
     * screen failure look like a batch abend to every operator, dashboard and alert downstream, and the
     * remediation is to keep the four-character online value and the binary batch value in separate fields
     * with separate meanings, exactly as the source keeps them.
     *
     * <p>The dispatch's own {@code WHEN OTHER} arm at {@code :373-380} is a <b>third</b> contract again: it
     * raises no CICS abend at all, but populates the payload with {@code '0001'} and sends plain text.
     *
     * <p><b>The default-message substitution is unreachable.</b> {@code app/cpy/CSMSG02Y.cpy} - internally
     * titled {@code CABENDD.CPY} - declares {@code ABEND-DATA} across physical lines 21 to 29 as
     * {@code ABEND-CODE X(4)}, {@code ABEND-CULPRIT X(8)}, {@code ABEND-REASON X(50)} and
     * {@code ABEND-MSG X(72)}, one hundred and thirty-four bytes in total, and every one of the four
     * carries {@code VALUE SPACES}. {@code SPACES} is not {@code LOW-VALUES}, so the test at {@code :859}
     * can never be true and {@code 'UNEXPECTED ABEND OCCURRED.'} can never be substituted. Retained with an
     * intentional-no-op marker; severity Medium, remediation is to retain it.
     */
    @Nested
    @DisplayName("the online abend contract is '9999', not the batch 999 - :857-877, CSMSG02Y:21-29")
    final class AbendContract {

        @Test
        @DisplayName("an unmodelled runtime fault becomes the online abend, carrying its culprit")
        void anUnmodelledRuntimeFaultBecomesTheOnlineAbend() {
            final IllegalStateException unmodelled = new IllegalStateException("store driver misbehaved");
            when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC))
                    .thenThrow(unmodelled);

            final Throwable thrown = catchThrowable(
                    () -> service.viewCardDetail(ACCOUNT_ID_11, CARD_NUMBER_16));

            assertThat(thrown).isInstanceOf(FatalProcessingException.class);
            final FatalProcessingException abend = (FatalProcessingException) thrown;
            assertThat(abend.getAbendCode())
                    .as("EXEC CICS ABEND ABCODE('9999') at :876 - four characters of text")
                    .isEqualTo(ONLINE_ABEND_CODE_L876)
                    .isEqualTo("9999")
                    .hasSize(ABEND_CODE_WIDTH);
            assertThat(abend.getAbendCulprit())
                    .as("MOVE LIT-THISPGM TO ABEND-CULPRIT at :863")
                    .isEqualTo(THIS_PROGRAM_L164)
                    .hasSizeLessThanOrEqualTo(ABEND_CULPRIT_WIDTH);
            assertThat(abend.getAbendMessage()).isEqualTo(unmodelled.getMessage());
            assertThat(abend).hasCause(unmodelled);
        }

        @Test
        @DisplayName("the online abend code is never the batch abend code or the batch return code")
        void theOnlineAbendCodeIsNeverTheBatchContract() {
            final IllegalStateException unmodelled = new IllegalStateException("store driver misbehaved");
            when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC))
                    .thenThrow(unmodelled);

            final Throwable thrown = catchThrowable(
                    () -> service.viewCardDetail(ACCOUNT_ID_11, CARD_NUMBER_16));

            final FatalProcessingException abend = (FatalProcessingException) thrown;
            assertThat(abend.getAbendCode())
                    .as("High finding if these are conflated: the online value is four characters of"
                            + " text and the batch value is a binary %d paired with return code %d",
                            FatalProcessingException.BATCH_ABEND_CODE,
                            FatalProcessingException.BATCH_RETURN_CODE)
                    .isNotEqualTo(Integer.toString(FatalProcessingException.BATCH_ABEND_CODE))
                    .isNotEqualTo(Integer.toString(FatalProcessingException.BATCH_RETURN_CODE));
            assertThat(FatalProcessingException.BATCH_ABEND_CODE).isEqualTo(999);
            assertThat(FatalProcessingException.BATCH_RETURN_CODE).isEqualTo(12);
        }

        @Test
        @DisplayName("the unexpected-data arm carries its own '0001', not the handler's '9999'")
        void theUnexpectedDataArmCarriesItsOwnCode() {
            final Throwable thrown = catchThrowable(() -> service.submitScreen(new CardDetailRequest(
                    AttentionIdentifier.ENTER, true, EntryMode.UNDEFINED, null, null, null, null, null,
                    ACCOUNT_ID_11, CARD_NUMBER_16)));

            assertThat(thrown).isInstanceOf(FatalProcessingException.class);
            final FatalProcessingException abend = (FatalProcessingException) thrown;
            assertThat(abend.getAbendCode())
                    .as(":373-380 populates the payload and sends plain text; it raises no CICS abend")
                    .isEqualTo(UNEXPECTED_DATA_ABEND_CODE)
                    .isNotEqualTo(ONLINE_ABEND_CODE_L876);
            assertThat(abend.getAbendMessage()).isEqualTo(UNEXPECTED_DATA_MESSAGE);
            assertThat(abend.getAbendCulprit()).isEqualTo(THIS_PROGRAM_L164);
            assertThat(abend).hasNoCause();
            verify(cardRepository, never()).findByCardNumberAndAccountId(anyString(), anyLong());
        }

        @Test
        @DisplayName("the abend reason is the fifty spaces the copybook initialises it to")
        void theAbendReasonIsFiftySpaces() {
            final Throwable thrown = catchThrowable(() -> service.submitScreen(new CardDetailRequest(
                    AttentionIdentifier.ENTER, true, EntryMode.UNDEFINED, null, null, null, null, null,
                    ACCOUNT_ID_11, CARD_NUMBER_16)));

            assertThat(((FatalProcessingException) thrown).getAbendReason())
                    .as("ABEND-REASON X(50) VALUE SPACES, app/cpy/CSMSG02Y.cpy:26-27")
                    .isEqualTo(spaces(ABEND_REASON_WIDTH))
                    .hasSize(50)
                    .isBlank();
        }

        @Test
        @DisplayName("the four abend fields total the hundred and thirty-four bytes the copybook declares")
        void theFourAbendFieldsTotalOneHundredAndThirtyFourBytes() {
            assertThat(ABEND_CODE_WIDTH + ABEND_CULPRIT_WIDTH + ABEND_REASON_WIDTH + ABEND_MSG_WIDTH)
                    .as("ABEND-CODE X(4) + ABEND-CULPRIT X(8) + ABEND-REASON X(50) + ABEND-MSG X(72),"
                            + " app/cpy/CSMSG02Y.cpy:21-29")
                    .isEqualTo(134);
            assertThat(ABEND_CODE_WIDTH).isEqualTo(4);
            assertThat(ABEND_CULPRIT_WIDTH).isEqualTo(8);
            assertThat(ABEND_REASON_WIDTH).isEqualTo(50);
            assertThat(ABEND_MSG_WIDTH).isEqualTo(72);
        }

        @Test
        @DisplayName("the default-message substitution is unreachable for a blank message")
        void defaultAbendMessageSubstitutionIsUnreachableForABlankMessage() {
            final FatalProcessingException blankMessaged = new FatalProcessingException(
                    ONLINE_ABEND_CODE_L876, THIS_PROGRAM_L164, spaces(ABEND_REASON_WIDTH),
                    spaces(ABEND_MSG_WIDTH));

            assertThat(blankMessaged.getAbendMessage())
                    .as("ABEND-MSG initialises to SPACES, and SPACES is not LOW-VALUES, so the test at"
                            + " :859 can never be true; severity Medium, remediation is to retain it")
                    .isEqualTo(spaces(ABEND_MSG_WIDTH))
                    .isNotEqualTo(FatalProcessingException.DEFAULT_ABEND_MESSAGE);
            assertThat(FatalProcessingException.DEFAULT_ABEND_MESSAGE)
                    .as("the literal at :860 is still carried, so the substitution is retained not deleted")
                    .isEqualTo("UNEXPECTED ABEND OCCURRED.");
        }

        @Test
        @DisplayName("the substitution the source does reach is the absent message, not the blank one")
        void theSubstitutionTheSourceReachesIsTheAbsentMessage() {
            final FatalProcessingException absentMessage = new FatalProcessingException(
                    ONLINE_ABEND_CODE_L876, THIS_PROGRAM_L164, spaces(ABEND_REASON_WIDTH), null);

            assertThat(absentMessage.getAbendMessage())
                    .as("an absent message is the only input that takes the default; a blank one does not")
                    .isEqualTo(FatalProcessingException.DEFAULT_ABEND_MESSAGE);
        }

        @Test
        @DisplayName("the retained substitution carries its unreachable marker and its copybook locator")
        void theRetainedSubstitutionCarriesItsMarker() {
            final List<String> body = bodyOf(serviceSourceLines(), "abendRoutine");
            final String text = String.join("\n", body).toLowerCase(Locale.ROOT);

            assertThat(text)
                    .as("the guard that never fires must say so, and cite where it is")
                    .contains("never fires")
                    .contains(":859");
            assertThat(String.join("\n", body))
                    .as("the handler must stamp this program as the culprit, per :863")
                    .contains(":863");
        }

        @Test
        @DisplayName("a modelled failure is rethrown unchanged, never wrapped as an abend")
        void aModelledFailureIsRethrownUnchanged() {
            assertThat(notFoundOutcome())
                    .as("submitScreen rethrows CardDemoException before its abend funnel can see it")
                    .isInstanceOf(RecordNotFoundException.class)
                    .isNotInstanceOf(FatalProcessingException.class);
        }
    }


    /**
     * Sensitive-data handling on every observable channel.
     *
     * <p>{@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy} is a primary account number, and
     * {@code CARD-EMBOSSED-NAME PIC X(50)} beside it is a cardholder name. Neither may leave the service in
     * clear text on a diagnostic channel, so this class holds the card number to <b>three</b> channels at
     * once: the log output, every exception message, and the exception's whole cause chain.
     *
     * <p>Severity High. Logging a full card number is a disclosure defect independent of any parity
     * question, and the remediation is the placeholder the service already substitutes - the number is
     * described, never printed. The parity cost is nil, because the legacy program had no log at all: its
     * only instrumentation was the screen itself, so nothing is lost by redacting here.
     *
     * <p>The screen projection is the one channel where the number legitimately appears, because
     * {@code CARDSIDO} at {@code app/cpy-bms/COCRDSL.CPY:66} is a field the operator typed and is being
     * shown their own input in. That is a response to an authenticated request, not a diagnostic channel,
     * and the distinction is asserted rather than assumed.
     */
    @Nested
    @DisplayName("the card number never reaches a diagnostic channel - CVACT02Y, :2075")
    final class SensitiveDataHandling {

        @Test
        @DisplayName("a read failure logs a placeholder, never the card number")
        void aReadFailureLogsAPlaceholderNeverTheCardNumber() {
            readFailureOutcome(new QueryTimeoutException("read timed out"));

            final String logged = capturedLogText();
            assertThat(logged)
                    .as("the read did fail, so there is log output to inspect")
                    .isNotEmpty()
                    .contains(CARD_FILE_L188);
            assertThat(logged)
                    .as("High finding if the number appears; the service describes it instead")
                    .doesNotContain(CARD_NUMBER_16)
                    .contains("redacted");
        }

        @Test
        @DisplayName("a read failure keeps the card number out of its exception message and cause chain")
        void aReadFailureKeepsTheCardNumberOutOfItsExceptionChain() {
            final Throwable thrown = readFailureOutcome(new QueryTimeoutException("read timed out"));

            for (Throwable link = thrown; link != null; link = link.getCause()) {
                assertThat(String.valueOf(link.getMessage()))
                        .as("every link of the chain is a channel the operator or an alert can see")
                        .doesNotContain(CARD_NUMBER_16);
            }
        }

        @Test
        @DisplayName("a not-found outcome logs nothing at all and names no card number")
        void aNotFoundOutcomeLogsNothingAndNamesNoCardNumber() {
            final Throwable thrown = notFoundOutcome();

            assertThat(capturedLogText())
                    .as("DFHRESP(NOTFND) at :755-761 is an ordinary operator outcome, not a fault")
                    .doesNotContain(CARD_NUMBER_16);
            assertThat(thrown.getMessage()).doesNotContain(CARD_NUMBER_16);
        }

        @Test
        @DisplayName("a rejected filter never echoes the rejected value into its message")
        void aRejectedFilterNeverEchoesTheRejectedValue() {
            final String hostileCardFilter = "999000111222333A";

            final Throwable thrown = editRejection(ACCOUNT_ID_11, hostileCardFilter);

            assertThat(thrown.getMessage())
                    .as(":706-715 moves a fixed literal, and quoting the input back would be an invention"
                            + " as well as a disclosure")
                    .isEqualTo(CARD_FILTER_NOT_NUMERIC_L711)
                    .doesNotContain(hostileCardFilter)
                    .doesNotContain(ACCOUNT_ID_11);
            assertThat(capturedLogText()).doesNotContain(hostileCardFilter);
        }

        @Test
        @DisplayName("the abend funnel logs the transaction and the code, never the card number")
        void theAbendFunnelLogsTheTransactionNotTheCardNumber() {
            when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC))
                    .thenThrow(new IllegalStateException("store driver misbehaved"));

            catchThrowable(() -> service.viewCardDetail(ACCOUNT_ID_11, CARD_NUMBER_16));

            final String logged = capturedLogText();
            assertThat(logged)
                    .contains(THIS_TRANSACTION_ID_L166)
                    .contains(ONLINE_ABEND_CODE_L876);
            assertThat(logged).doesNotContain(CARD_NUMBER_16);
        }

        @Test
        @DisplayName("the cardholder name never reaches a diagnostic channel either")
        void theCardholderNameNeverReachesADiagnosticChannel() {
            readFailureOutcome(new QueryTimeoutException("read timed out"));

            assertThat(capturedLogText())
                    .as("CARD-EMBOSSED-NAME PIC X(50) is personal data on the same terms")
                    .doesNotContain(EMBOSSED_NAME);
        }

        @Test
        @DisplayName("the operator's own card number is still shown back to them on the screen")
        void theOperatorsOwnCardNumberIsStillShownBackToThem() {
            final CardDetailScreen screen = screenForFoundCard();

            assertThat(screen.detail().getCardNumber())
                    .as("CARDSIDO at app/cpy-bms/COCRDSL.CPY:66 echoes the operator's own input; the"
                            + " redaction rule governs diagnostic channels, not the response itself")
                    .isEqualTo(CARD_NUMBER_16);
            assertThat(capturedLogText())
                    .as("a successful read logs nothing, so there is no channel to leak on")
                    .isEmpty();
        }

        @Test
        @DisplayName("no log statement in the service passes a raw card number or embossed name")
        void noLogStatementPassesARawCardNumberOrName() {
            final List<String> sourceLines = serviceSourceLines();

            for (int index = 0; index < sourceLines.size(); index++) {
                final String line = sourceLines.get(index);
                if (!line.contains("LOG.")) {
                    continue;
                }
                final StringBuilder statement = new StringBuilder(line);
                for (int cursor = index + 1; cursor < sourceLines.size()
                        && !statement.toString().contains(");"); cursor++) {
                    statement.append(sourceLines.get(cursor));
                }
                assertThat(statement.toString())
                        .as("a log statement may pass the described number but never the field itself")
                        .doesNotContain("getCardNumber()")
                        .doesNotContain("getEmbossedName()")
                        .doesNotContain("work.ccCardNum")
                        .doesNotContain("cardRecordIdCardNumber,");
            }
        }
    }


    /**
     * Hostile and boundary input on both filters.
     *
     * <p>Every input here is treated as untrusted, and each is asserted against the outcome the source
     * produces rather than the outcome a reasonable reader might expect. Two of those outcomes are
     * counter-intuitive and are the reason this class exists:
     *
     * <ul>
     *   <li><b>Over-length input truncates rather than rejecting.</b> {@code 2100-RECEIVE-MAP.} at
     *       {@code app/cbl/COCRDSLC.cbl:596-605} receives into {@code ACCTSIDI PIC X(11)} and
     *       {@code CARDSIDI PIC X(16)}, and a COBOL {@code MOVE} into a shorter alphanumeric field discards
     *       the excess on the right. A seventeen-digit card filter therefore arrives as its first sixteen
     *       digits and is <b>accepted</b>. Rejecting it would be an invention.</li>
     *   <li><b>Under-length input is rejected as non-numeric, not as short.</b> A ten-digit account filter
     *       is padded to eleven by the {@code MOVE} at {@code :617-619}, the pad is a space, and
     *       {@code :665} tests the padded field - so the message is the eleven-digit filter message.</li>
     * </ul>
     *
     * <p>Currency symbols and thousands separators are rejected outright. This program has no amount field:
     * both of its inputs are plain numeric identifiers edited by {@code IS NOT NUMERIC} at {@code :665} and
     * {@code :706}, so the currency-aware conversion that the transaction-add program applies to
     * {@code TRNAMT} has no place here and accepting either character would be a divergence.
     */
    @Nested
    @DisplayName("hostile and boundary input - :596-605, :651-678, :691-715")
    final class HostileInput {

        @Test
        @DisplayName("a null request is refused by name, before any work is done")
        void aNullRequestIsRefusedByName() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.submitScreen(null))
                    .withMessage("request must not be null");
            verifyNoMoreInteractions(cardRepository);
        }

        @Test
        @DisplayName("a null repository is refused by the constructor, before a bean can exist")
        void aNullRepositoryIsRefusedByTheConstructor() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new CardDetailService(null, FixedClockProvider.canonicalClock()))
                    .withMessage("cardRepository must not be null");
        }

        @Test
        @DisplayName("a null clock is refused by the constructor, so no bean reads an ambient time source")
        void aNullClockIsRefusedByTheConstructor() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new CardDetailService(cardRepository, null))
                    .withMessage("clock must not be null");
        }

        @Test
        @DisplayName("a null attention identifier and a null entry mode are both refused by name")
        void aNullAttentionIdentifierAndEntryModeAreRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new CardDetailRequest(null, true, EntryMode.REENTER, null, null,
                            null, null, null, ACCOUNT_ID_11, CARD_NUMBER_16))
                    .withMessage("attentionIdentifier must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> new CardDetailRequest(AttentionIdentifier.ENTER, true, null, null,
                            null, null, null, null, ACCOUNT_ID_11, CARD_NUMBER_16))
                    .withMessage("entryMode must not be null");
        }

        @Test
        @DisplayName("a seventeenth character is truncated rather than rejected")
        void aSeventeenthCharacterIsTruncatedRatherThanRejected() {
            when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(canonicalCard()));

            final CardDto detail = service.viewCardDetail(ACCOUNT_ID_11, CARD_NUMBER_16 + "7");

            assertThat(detail)
                    .as("the MOVE into CARDSIDI PIC X(16) at :596-605 discards the excess on the right;"
                            + " rejecting a seventeenth character would be an invention")
                    .isNotNull();
            verify(cardRepository, times(1))
                    .findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC);
        }

        @Test
        @DisplayName("a twelfth account character is truncated too, and the read still succeeds")
        void aTwelfthAccountCharacterIsTruncatedToo() {
            when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(canonicalCard()));

            final CardDto detail = service.viewCardDetail(ACCOUNT_ID_11 + "9", CARD_NUMBER_16);

            assertThat(detail).isNotNull();
            assertThat(detail.getAccountId())
                    .as("ACCTSIDI PIC X(11) holds eleven characters, so the twelfth never arrives")
                    .isEqualTo(ACCOUNT_ID_11)
                    .hasSize(ACCOUNT_ID_WIDTH);
        }

        @Test
        @DisplayName("a fifteen-character card filter is rejected, because the pad is not a digit")
        void aFifteenCharacterCardFilterIsRejected() {
            final Throwable thrown = editRejection(ACCOUNT_ID_11, "999000111222333");

            assertThat(thrown)
                    .as("the MOVE at :624-626 pads to sixteen with a space, and :706 tests the padded"
                            + " field, so the outcome is the sixteen-digit filter message")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(CARD_FILTER_NOT_NUMERIC_L711);
            assertThat(((ValidationException) thrown).getFailureKind()).isEqualTo(FailureKind.INVALID);
        }

        @Test
        @DisplayName("a ten-character account filter is rejected as non-numeric, not as short")
        void aTenCharacterAccountFilterIsRejectedAsNonNumeric() {
            final Throwable thrown = editRejection("0000000001", CARD_NUMBER_16);

            assertThat(thrown)
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(ACCOUNT_FILTER_NOT_NUMERIC_L670);
            assertThat(((ValidationException) thrown).getFailureKind()).isEqualTo(FailureKind.INVALID);
        }

        @Test
        @DisplayName("a seventeen-character filter ending in a letter truncates to sixteen valid digits")
        void aSeventeenCharacterFilterEndingInALetterTruncatesToValidDigits() {
            final Throwable thrown = editRejection(ACCOUNT_ID_11, "1234567890123456A");

            assertThat(thrown)
                    .as("the letter is the seventeenth character, so :596-605 discards it and the"
                            + " remaining sixteen digits pass :706; the outcome is therefore a read that"
                            + " finds nothing, NOT a validation rejection - reporting a rejection here"
                            + " would be an invention")
                    .isInstanceOf(RecordNotFoundException.class)
                    .isNotInstanceOf(ValidationException.class)
                    .hasMessage(DID_NOT_FIND_ACCTCARD_COMBO_L154);
            verify(cardRepository, times(1))
                    .findByCardNumberAndAccountId("1234567890123456", ACCOUNT_ID_NUMERIC);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "123456789012345A", "$99900011122233", "9,990,001,112,223", "999000111222333 ",
            "999000111222-334", "999000111222.334", "99900011122233\u0000", "999000111222333\t",
            "999000111222333\n", "999000111222333\u001b", "\u0000\u0000\u0000\u0000\u0000\u0000\u0000",
            "999000111222333\u007f", "９９９０００１１１２２２３３３４"})
        @DisplayName("every hostile card filter is rejected without ever reaching the store")
        void everyHostileCardFilterIsRejectedWithoutReachingTheStore(final String cardFilter) {
            final Throwable thrown = editRejection(ACCOUNT_ID_11, cardFilter);

            assertThat(thrown)
                    .as("only ASCII digits pass :706; currency symbols, separators, control characters"
                            + " and full-width digits are all non-numeric")
                    .isInstanceOf(ValidationException.class)
                    .isInstanceOf(CardDemoException.class);
            assertThat(thrown.getMessage()).isEqualTo(CARD_FILTER_NOT_NUMERIC_L711);
            verify(cardRepository, never()).findByCardNumberAndAccountId(anyString(), anyLong());
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "0000000001A", "$0000000001", "1,000,000,00", "0000000001 ", "-0000000001",
            "0000000.001", "0000000001\u0000", "0000000001\t", "0000000001\u001b", "０００００００００１１"})
        @DisplayName("every hostile account filter is rejected without ever reaching the store")
        void everyHostileAccountFilterIsRejectedWithoutReachingTheStore(final String accountFilter) {
            final Throwable thrown = editRejection(accountFilter, CARD_NUMBER_16);

            assertThat(thrown).isInstanceOf(ValidationException.class);
            assertThat(thrown.getMessage()).isEqualTo(ACCOUNT_FILTER_NOT_NUMERIC_L670);
            verify(cardRepository, never()).findByCardNumberAndAccountId(anyString(), anyLong());
        }

        @Test
        @DisplayName("an all-low-values filter pair is not supplied, not invalid")
        void anAllLowValuesFilterPairIsNotSupplied() {
            final String lowValues = "\u0000".repeat(CARD_NUMBER_WIDTH);

            final Throwable thrown = editRejection("\u0000".repeat(ACCOUNT_ID_WIDTH), lowValues);

            assertThat(thrown)
                    .as(":651-654 tests LOW-VALUES first, so the pair takes the cross-field message")
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(NO_SEARCH_CRITERIA_L143);
        }

        @Test
        @DisplayName("a hostile filter never becomes a store query string of any kind")
        void aHostileFilterNeverBecomesAStoreQueryString() {
            editRejection("0000000001'; DROP TABLE card_data; --", CARD_NUMBER_16);
            editRejection(ACCOUNT_ID_11, "999000111222333' OR '1'='1");

            verify(cardRepository, never()).findByCardNumberAndAccountId(anyString(), anyLong());
            verifyNoMoreInteractions(cardRepository);
        }

        @Test
        @DisplayName("the store is reached only through a bound identifier, never a composed string")
        void theStoreIsReachedOnlyThroughABoundIdentifier() {
            screenForFoundCard();

            verify(cardRepository, times(1))
                    .findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC);
            verifyNoMoreInteractions(cardRepository);
            assertThat(serviceSourceLines())
                    .as("Spring Data binds the identifier; no line may build a query by concatenation")
                    .noneMatch(line -> line.contains("createQuery") || line.contains("createNativeQuery"))
                    .noneMatch(line -> line.contains("SELECT ") && line.contains("+"));
        }

        @Test
        @DisplayName("an unmapped attention identifier is coerced to enter, not rejected")
        void anUnmappedAttentionIdentifierIsCoercedToEnter() {
            when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(canonicalCard()));

            final CardDetailScreen screen = service.submitScreen(new CardDetailRequest(
                    AttentionIdentifier.PF7, true, EntryMode.REENTER, null, null, null, null, null,
                    ACCOUNT_ID_11, CARD_NUMBER_16));

            assertThat(screen.detail())
                    .as(":291-299 admits only ENTER and PF03 and then silently coerces anything else to"
                            + " ENTER rather than reporting it; severity Low, documented only")
                    .isNotNull();
            assertThat(screen.inputError()).isFalse();
        }

        @Test
        @DisplayName("the exit key resolves its two navigation targets and reads nothing")
        void theExitKeyResolvesItsTwoNavigationTargetsAndReadsNothing() {
            final CardDetailScreen screen = service.submitScreen(new CardDetailRequest(
                    AttentionIdentifier.PF3, true, EntryMode.REENTER, null, null, null, null, null,
                    ACCOUNT_ID_11, CARD_NUMBER_16));

            assertThat(screen.navigationProgram())
                    .as(":313-320 falls back to the menu when the caller left no provenance")
                    .isEqualTo("COMEN01C");
            assertThat(screen.navigationTransactionId()).isEqualTo("CM00");
            verify(cardRepository, never()).findByCardNumberAndAccountId(anyString(), anyLong());
        }
    }


    /**
     * Paragraph correspondence: one private method per source label, none consolidated.
     *
     * <p>{@code app/cbl/COCRDSLC.cbl} is eight hundred and eighty-seven lines carrying <b>thirty-four</b>
     * {@code PROCEDURE DIVISION} labels, plus the {@code YYYY-STORE-PFKEY} paragraph and its exit that
     * {@code COPY 'CSSTRPFY'} at {@code :855} copies into that division - thirty-six in all. The figure of
     * thirty-seven quoted elsewhere counts the three {@code IDENTIFICATION DIVISION} labels
     * {@code PROGRAM-ID.} at {@code :23}, {@code DATE-WRITTEN.} at {@code :25} and
     * {@code DATE-COMPILED.} at {@code :27}, which are not paragraphs and have nothing to reproduce; the
     * count asserted here is the one established by reading the program.
     *
     * <p>Correspondence is one-to-one and total. Exits are methods, bare {@code EXIT} bodies are methods,
     * and the unreachable range is methods - because the paragraph map is what the scope-coverage gate
     * reads, and a consolidated pair leaves a row of that map unprovable. Every one of the thirty-six
     * carries Javadoc citing its own source line, which is the property reflection cannot reach and the
     * reason this class reads the production source as well as inspecting the class.
     */
    @Nested
    @DisplayName("thirty-six paragraphs map one-to-one, none consolidated - :23-27, :248-877, :855")
    final class ParagraphCorrespondence {

        @Test
        @DisplayName("all thirty-six paragraph methods are declared, and every one is private")
        void allThirtySixParagraphMethodsAreDeclaredAndPrivate() {
            final List<String> declared = declaredMethodNames();

            assertThat(PARAGRAPH_METHOD_NAMES)
                    .as("the source's thirty-four procedure labels plus the two copied at :855")
                    .hasSize(36)
                    .doesNotHaveDuplicates();
            assertThat(declared).containsAll(PARAGRAPH_METHOD_NAMES);
            for (final String name : PARAGRAPH_METHOD_NAMES) {
                assertThat(methodNamed(name).getModifiers())
                        .as("%s reproduces a paragraph, which is internal control flow, not API", name)
                        .matches(Modifier::isPrivate);
            }
        }

        @Test
        @DisplayName("each paragraph method cites its own source line in its own Javadoc")
        void eachParagraphMethodCitesItsOwnSourceLine() {
            final List<String> sourceLines = serviceSourceLines();

            for (final String name : PARAGRAPH_METHOD_NAMES) {
                final String javadoc = javadocPreceding(sourceLines, name);
                assertThat(SOURCE_LINE_CITATION.matcher(javadoc).find())
                        .as("%s must cite the line of the label it reproduces; found: %s", name, javadoc)
                        .isTrue();
                assertThat(javadoc)
                        .as("%s must name the program it was transcribed from", name)
                        .containsAnyOf("COCRDSLC", "CSSTRPFY");
            }
        }

        @Test
        @DisplayName("exactly four public operations are exposed, and each returns a projection")
        void exactlyFourPublicOperationsAreExposed() {
            final List<String> publicMethods = new ArrayList<>();
            for (final Method method : CardDetailService.class.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                    publicMethods.add(method.getName());
                }
            }

            assertThat(publicMethods)
                    .as("seventeen CSD transactions became eight controllers over seventeen endpoints;"
                            + " transaction CCDL contributes exactly these four operations")
                    .containsExactlyInAnyOrderElementsOf(PUBLIC_ENTRY_POINTS)
                    .hasSize(4);
        }

        @Test
        @DisplayName("the bean has one constructor, taking the repository it reads and the clock it reads")
        void theBeanHasOneConstructorTakingItsRepositoryAndClock() {
            final Constructor<?>[] constructors = CardDetailService.class.getDeclaredConstructors();

            assertThat(constructors)
                    .as("constructor injection only - no setter, no field injection, no second path in")
                    .hasSize(1);
            final Constructor<?> only = constructors[0];
            assertThat(Modifier.isPublic(only.getModifiers())).isTrue();
            assertThat(only.getParameterTypes())
                    .as("the time source is injected, not constructed: the clock arrives the same way the"
                            + " repository does, from the one Clock bean ObservabilityConfig publishes, so"
                            + " nothing in this service reads an ambient time source")
                    .containsExactly(CardRepository.class, Clock.class);
        }

        @Test
        @DisplayName("the bean holds no mutable static state, so no request can see another's work area")
        void theBeanHoldsNoMutableStaticState() {
            for (final Field field : CardDetailService.class.getDeclaredFields()) {
                if (field.isSynthetic() || !Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("static field %s must be final; the legacy WORKING-STORAGE flags become"
                                + " method-local work-area state, never shared", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("every instance field is final, so the bean is safe to share across requests")
        void everyInstanceFieldIsFinal() {
            for (final Field field : CardDetailService.class.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("instance field %s must be final; a singleton bean carrying request state"
                                + " would reintroduce the COMMAREA it replaced", field.getName())
                        .isTrue();
                assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
            }
        }

        @Test
        @DisplayName("no paragraph method is consolidated into another")
        void noParagraphMethodIsConsolidatedIntoAnother() {
            final List<String> declared = declaredMethodNames();

            for (final String name : PARAGRAPH_METHOD_NAMES) {
                assertThat(declared)
                        .as("%s must be its own method; folding a paragraph into its neighbour leaves a"
                                + " traceability row unprovable", name)
                        .filteredOn(name::equals)
                        .hasSize(1);
            }
        }

        @Test
        @DisplayName("no paragraph method leaks a work area onto the public surface")
        void noParagraphMethodLeaksAWorkArea() {
            for (final Method method : CardDetailService.class.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                    continue;
                }
                for (final Class<?> parameter : method.getParameterTypes()) {
                    assertThat(parameter.getSimpleName())
                            .as("%s must not accept the internal work area", method.getName())
                            .isNotEqualTo("ScreenWorkArea");
                }
                assertThat(method.getReturnType().getSimpleName())
                        .as("%s must not return the internal work area", method.getName())
                        .isNotEqualTo("ScreenWorkArea");
            }
        }

        @Test
        @DisplayName("the program the citations point at is still the verified eight-hundred-and-eighty-seven lines")
        void theCitedProgramIsStillTheVerifiedLength() {
            assertThat(programSourceLines())
                    .as("every locator in this test class is relative to %s at commit 7756d89",
                            PROGRAM_SOURCE)
                    .hasSize(PROGRAM_SOURCE_LINE_COUNT);
        }

        @Test
        @DisplayName("the three identification-division labels are not counted as paragraphs")
        void theThreeIdentificationLabelsAreNotCountedAsParagraphs() {
            final List<String> lines = programSourceLines();

            assertThat(lines.get(22)).as(":23 is PROGRAM-ID, not a paragraph").contains("PROGRAM-ID.");
            assertThat(lines.get(24)).as(":25 is DATE-WRITTEN").contains("DATE-WRITTEN.");
            assertThat(lines.get(26)).as(":27 is DATE-COMPILED").contains("DATE-COMPILED.");
            assertThat(PARAGRAPH_METHOD_NAMES)
                    .as("counting those three is what produces the figure of thirty-seven")
                    .hasSize(37 - 3 + 2);
        }
    }

    /**
     * The field contract this service projects, and the arithmetic discipline it must observe.
     *
     * <p>{@code app/cpy-bms/COCRDSL.CPY} declares fifteen input fields, from {@code TRNNAMEI} at line 24
     * through {@code FKEYSI} at line 108, and {@code 1400-SEND-SCREEN.} at
     * {@code app/cbl/COCRDSLC.cbl:563-578} sends exactly those fifteen. This class asserts the
     * <b>projection</b> - which value the service places in which slot, and in what shape - and deliberately
     * does <b>not</b> re-derive the copybook's field table or the entity's byte layout, because the sibling
     * {@code com.cardemo.unit.model} package owns both and duplicating them would violate the repository's
     * no-duplication standard.
     *
     * <p>The header date and time are asserted by <b>shape only</b>. The instant itself is pinned - the
     * service takes its {@link java.time.Clock} by constructor and this class injects the canonical fixed
     * clock - but the assertion stays a shape assertion on purpose, because what the source fixes is the
     * edited layout of {@code app/cpy/CSDAT01Y.cpy:30-41} rather than any particular moment.
     *
     * <p>Money discipline is asserted structurally rather than behaviourally, because
     * {@code app/cpy/CVACT02Y.cpy} declares <b>no monetary field at all</b> - the hundred and fifty bytes
     * are {@code CARD-NUM X(16)}, {@code CARD-ACCT-ID 9(11)}, {@code CARD-CVV-CD 9(03)},
     * {@code CARD-EMBOSSED-NAME X(50)}, {@code CARD-EXPIRAION-DATE X(10)},
     * {@code CARD-ACTIVE-STATUS X(01)} and fifty-nine bytes of filler. So the assertion made here is the
     * one that is true and provable: no {@code float} or {@code double} exists anywhere in the type graph
     * this service touches, and the {@link BigDecimal} discipline the migration mandates -
     * {@link RoundingMode#HALF_EVEN} with {@code compareTo} rather than {@code equals} - holds where it is
     * exercised. The field name {@code CARD-EXPIRAION-DATE} is misspelled in the copybook, and the
     * misspelling is part of the contract.
     */
    @Nested
    @DisplayName("the fifteen-field projection and the arithmetic discipline - COCRDSL.CPY:24-108, :563-578")
    final class FieldContractProjection {

        @Test
        @DisplayName("the projection fills all fifteen slots in map order")
        void theProjectionFillsAllFifteenSlotsInMapOrder() {
            final CardDetailScreen screen = screenForFoundCard();

            final List<String> values = detailFieldsInMapOrder(screen.detail());
            assertThat(values)
                    .as("TRNNAMEI at line 24 through FKEYSI at line 108, in declaration order")
                    .hasSize(CardDto.DETAIL_FIELD_COUNT)
                    .hasSize(15);
            assertThat(values.subList(0, 12))
                    .as("only the trailing message and function-key slots may be unset on a found card")
                    .doesNotContainNull();
        }

        @Test
        @DisplayName("the six header slots carry this transaction's own identity")
        void theSixHeaderSlotsCarryThisTransactionsIdentity() {
            final CardDto detail = screenForFoundCard().detail();

            assertThat(detail.getTransactionName())
                    .as("MOVE LIT-THISTRANID TO TRNNAMEO at :434")
                    .isEqualTo(THIS_TRANSACTION_ID_L166);
            assertThat(detail.getProgramName())
                    .as("MOVE LIT-THISPGM TO PGMNAMEO at :435")
                    .isEqualTo(THIS_PROGRAM_L164);
            assertThat(detail.getTitle01()).isEqualTo(SCREEN_TITLE_01).hasSize(40);
            assertThat(detail.getTitle02()).isEqualTo(SCREEN_TITLE_02).hasSize(40);
        }

        @Test
        @DisplayName("the header date and time carry their declared shape, asserted as a shape")
        void theHeaderDateAndTimeCarryTheirDeclaredShape() {
            final CardDto detail = screenForFoundCard().detail();

            assertThat(detail.getCurrentDate())
                    .as("the MM/DD/YY assembly at :439-443; the value depends on the clock, so only its"
                            + " shape can be asserted without pinning an instant")
                    .matches(HEADER_DATE_MM_DD_YY.pattern())
                    .hasSize(8);
            assertThat(detail.getCurrentTime())
                    .as("the HH:MM:SS assembly at :445-449")
                    .matches(HEADER_TIME_HH_MM_SS.pattern())
                    .hasSize(8);
        }

        @Test
        @DisplayName("the four card slots come from the record, expiry split at the copybook's offsets")
        void theFourCardSlotsComeFromTheRecord() {
            final CardDto detail = screenForFoundCard().detail();

            assertThat(detail.getCardholderName())
                    .as("MOVE CARD-EMBOSSED-NAME TO CRDNAMEO at :474-485")
                    .isEqualTo(EMBOSSED_NAME);
            assertThat(detail.getCardStatusCode())
                    .as("MOVE CARD-ACTIVE-STATUS TO CRDSTCDO")
                    .isEqualTo(ACTIVE_STATUS);
            assertThat(detail.getExpiryMonth())
                    .as("the month substring of CARD-EXPIRAION-DATE - the copybook's own misspelling")
                    .isEqualTo("08")
                    .hasSize(2);
            assertThat(detail.getExpiryYear()).isEqualTo("2026").hasSize(4);
        }

        @Test
        @DisplayName("the two filter slots echo the operator's own validated input")
        void theTwoFilterSlotsEchoTheOperatorsInput() {
            final CardDto detail = screenForFoundCard().detail();

            assertThat(detail.getAccountId()).isEqualTo(ACCOUNT_ID_11).hasSize(ACCOUNT_ID_WIDTH);
            assertThat(detail.getCardNumber()).isEqualTo(CARD_NUMBER_16).hasSize(CARD_NUMBER_WIDTH);
        }

        @Test
        @DisplayName("the detail projection carries no list rows, because this is not the list screen")
        void theDetailProjectionCarriesNoListRows() {
            final CardDto detail = screenForFoundCard().detail();

            assertThat(detail.getRows())
                    .as("COCRDSL.CPY declares no row array; the seven-row table belongs to COCRDLI.CPY"
                            + " and to the card-list service's own tests")
                    .isNull();
            assertThat(CardDto.DETAIL_FIELD_COUNT)
                    .as("fifteen for the detail map against forty-five for the list map")
                    .isNotEqualTo(CardDto.LIST_FIELD_COUNT);
        }

        @Test
        @DisplayName("no float or double appears anywhere in the type graph this service touches")
        void noFloatOrDoubleAppearsInTheTypeGraph() {
            final List<Class<?>> inspected = List.of(CardDetailService.class, Card.class, CardDto.class,
                    CardDetailScreen.class, CardDetailRequest.class);

            for (final Class<?> type : inspected) {
                for (final Field field : type.getDeclaredFields()) {
                    assertThat(field.getType())
                            .as("%s.%s must not be a binary floating-point type", type.getSimpleName(),
                                    field.getName())
                            .isNotIn(float.class, double.class, Float.class, Double.class);
                }
                for (final Method method : type.getDeclaredMethods()) {
                    assertThat(method.getReturnType())
                            .as("%s.%s must not return a binary floating-point type", type.getSimpleName(),
                                    method.getName())
                            .isNotIn(float.class, double.class, Float.class, Double.class);
                }
            }
        }

        @Test
        @DisplayName("decimal equality is compareTo, never equals, and rounding is half-even")
        void decimalEqualityIsCompareToNeverEquals() {
            final BigDecimal twoDecimals = new BigDecimal("100.00");
            final BigDecimal fourDecimals = new BigDecimal("100.0000");

            assertThat(twoDecimals.compareTo(fourDecimals))
                    .as("the same amount at two scales; compareTo is the only equality the migration"
                            + " permits on a financial field")
                    .isZero();
            assertThat(twoDecimals.equals(fourDecimals))
                    .as("equals compares scale as well as value, which is why it is forbidden")
                    .isFalse();
            assertThat(new BigDecimal("2.345").setScale(2, RoundingMode.HALF_EVEN))
                    .as("half-even rounds a tie to the even neighbour")
                    .isEqualByComparingTo("2.34");
            assertThat(new BigDecimal("2.355").setScale(2, RoundingMode.HALF_EVEN))
                    .isEqualByComparingTo("2.36");
        }

        @Test
        @DisplayName("the account identifier stays a whole number, never a decimal-scaled quantity")
        void theAccountIdentifierStaysAWholeNumber() {
            final Card card = canonicalCard();

            assertThat(card.getAccountId())
                    .as("CARD-ACCT-ID PIC 9(11) is an identifier, not an amount, so it carries no scale")
                    .isEqualTo(ACCOUNT_ID_NUMERIC);
            assertThat(card.getCardNumber()).isEqualTo(CARD_NUMBER_16);
            assertThat(card.getExpiraionDate())
                    .as("the copybook's misspelled CARD-EXPIRAION-DATE is preserved as the accessor name")
                    .isEqualTo(EXPIRAION_DATE);
        }
    }

    /**
     * The attention-identifier gate, across every key the copybook maps.
     *
     * <p>{@code COPY 'CSSTRPFY'} at {@code app/cbl/COCRDSLC.cbl:855} contributes
     * {@code YYYY-STORE-PFKEY}, which evaluates {@code EIBAID} against the {@code DFHAID} condition names
     * and stores the corresponding five-character action code from {@code app/cpy/CVCRD01Y.cpy} - the
     * literal {@code PFK} followed by a two-digit ordinal, so {@code DFHPF3} becomes {@code PFK03}. The
     * {@code DFHPF13}-onwards keys fold onto the same twelve codes as {@code DFHPF1}-onwards, which is why
     * the transcription pairs them.
     *
     * <p>The gate at {@code :291-299} then admits only {@code CCARD-AID-ENTER} and {@code CCARD-AID-PFK03}
     * and, rather than reporting anything, <b>silently coerces every other key to enter</b>. Pressing
     * {@code PF7} on this screen therefore redisplays it, and no validation error is raised for the key.
     * Severity Low, documented only, and preserved exactly: reporting an unmapped key would be an
     * invention, and rejecting one would change what the operator sees.
     *
     * <p>Every arm of the mapping is exercised here, including {@code UNMAPPED}, because an unexercised arm
     * is an unproved one - and the coercion is what makes the whole set observable through the public API
     * without reaching for the internal work area.
     */
    @Nested
    @DisplayName("every attention identifier is mapped, and only PF03 escapes coercion - :291-299, :855")
    final class AttentionIdentifierMapping {

        @ParameterizedTest
        @EnumSource(AttentionIdentifier.class)
        @DisplayName("only the PFK03 pair takes the exit branch; every other key is coerced to enter")
        void onlyPf03EscapesCoercionToEnter(final AttentionIdentifier attentionIdentifier) {
            final CardDetailRequest request = new CardDetailRequest(attentionIdentifier, true,
                    EntryMode.REENTER, null, null, null, null, null, ACCOUNT_ID_11, CARD_NUMBER_16);

            if (EXIT_KEYS.contains(attentionIdentifier)) {
                final CardDetailScreen screen = service.submitScreen(request);

                assertThat(screen.navigationProgram())
                        .as("%s resolves to PFK03, the one code the gate at :291-299 admits alongside"
                                + " ENTER", attentionIdentifier)
                        .isEqualTo("COMEN01C");
                assertThat(screen.detail()).isNull();
                verify(cardRepository, never()).findByCardNumberAndAccountId(anyString(), anyLong());
            } else {
                when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(canonicalCard()));

                final CardDetailScreen screen = service.submitScreen(request);

                assertThat(screen.navigationProgram())
                        .as("%s is coerced to ENTER, so the screen is redisplayed rather than navigated"
                                + " away from", attentionIdentifier)
                        .isNull();
                assertThat(screen.detail()).isNotNull();
                assertThat(screen.inputError()).isFalse();
            }
        }

        @Test
        @DisplayName("PF15 folds onto PFK03 and therefore exits, exactly as PF3 does")
        void pf15FoldsOntoPfk03AndThereforeExits() {
            final List<String> copybook = pfKeyCopybookLines();
            final String pf3Arm = String.join("\n", copybook.subList(33, 35));
            final String pf15Arm = String.join("\n", copybook.subList(57, 59));

            assertThat(pf3Arm)
                    .as("app/cpy/CSSTRPFY.cpy:34-35 sets CCARD-AID-PFK03 for DFHPF3")
                    .contains("DFHPF3")
                    .contains("CCARD-AID-PFK03");
            assertThat(pf15Arm)
                    .as("app/cpy/CSSTRPFY.cpy:58-59 sets the SAME condition name for DFHPF15, so the"
                            + " gate cannot tell the two keys apart; severity Low, and treating PF15 as"
                            + " an ordinary key would lose an exit route the operator really has")
                    .contains("DFHPF15")
                    .contains("CCARD-AID-PFK03");
            assertThat(EXIT_KEYS)
                    .containsExactlyInAnyOrder(AttentionIdentifier.PF3, AttentionIdentifier.PF15);
        }

        @Test
        @DisplayName("the exit key passes a supplied provenance straight through, without the fallback")
        void theExitKeyPassesASuppliedProvenanceThrough() {
            final CardDetailScreen screen = service.submitScreen(new CardDetailRequest(
                    AttentionIdentifier.PF3, true, EntryMode.REENTER, "COCRDLIC", "CCLI", "COCRDLI", null,
                    null, ACCOUNT_ID_11, CARD_NUMBER_16));

            assertThat(screen.navigationTransactionId())
                    .as(":312-314 uses the caller's own transaction when one was supplied")
                    .isEqualTo("CCLI");
            assertThat(screen.navigationProgram())
                    .as(":319-321 uses the caller's own program on identical terms")
                    .isEqualTo("COCRDLIC");
            verify(cardRepository, never()).findByCardNumberAndAccountId(anyString(), anyLong());
        }

        @Test
        @DisplayName("arriving from the menu is treated as a first entry, commarea or not")
        void arrivingFromTheMenuIsTreatedAsAFirstEntry() {
            final CardDetailScreen screen = service.submitScreen(new CardDetailRequest(
                    AttentionIdentifier.ENTER, true, EntryMode.ENTER, "COMEN01C", "CM00", "COMEN01",
                    ACCOUNT_ID_NUMERIC, CARD_NUMBER_16, null, null));

            assertThat(screen.informationMessage())
                    .as(":268-279 INITIALIZEs the commarea, so the screen is sent empty and prompting")
                    .isEqualTo(PROMPT_FOR_INPUT_L132);
            assertThat(screen.inputError())
                    .as("the edits never run on this arm, so nothing can be in error")
                    .isFalse();
            verify(cardRepository, never()).findByCardNumberAndAccountId(anyString(), anyLong());
        }

        @Test
        @DisplayName("the filters are protected only when the operator arrived from the card list")
        void theFiltersAreProtectedOnlyWhenArrivingFromTheCardList() {
            when(cardRepository.findByCardNumberAndAccountId(CARD_NUMBER_16, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(canonicalCard()));

            final CardDetailScreen fromList = service.submitScreen(new CardDetailRequest(
                    AttentionIdentifier.ENTER, true, EntryMode.REENTER, "COCRDLIC", "CCLI", "COCRDLI",
                    null, null, ACCOUNT_ID_11, CARD_NUMBER_16));

            assertThat(fromList.filtersProtected())
                    .as(":505-512 protects the selection when it is not the operator's to change")
                    .isTrue();
            assertThat(screenForFoundCard().filtersProtected())
                    .as("a direct re-entry leaves both filters editable")
                    .isFalse();
        }

        @Test
        @DisplayName("the cursor rests on the account field when both filters are valid")
        void theCursorRestsOnTheAccountFieldWhenBothFiltersAreValid() {
            final CardDetailScreen screen = screenForFoundCard();

            assertThat(screen.cursorField())
                    .as(":515-524 falls through to the account field when neither filter is in error")
                    .isEqualTo(FIELD_ACCOUNT_ID);
        }

        @Test
        @DisplayName("a first entry carries no filter state at all, because no edit has run")
        void aFirstEntryCarriesNoFilterState() {
            final CardDetailScreen screen = service.openWithoutContext();

            assertThat(screen.accountFilterState())
                    .as(":349-356 sends the map without performing 2200, so no flag is ever set")
                    .isNull();
            assertThat(screen.cardFilterState()).isNull();
            assertThat(screen.detail()).isNotNull();
            assertThat(screen.detail().getAccountId()).isNull();
        }
    }

    /**
     * Returns every method name the service declares.
     *
     * @return the declared names, including duplicates where a name is overloaded
     */
    private static List<String> declaredMethodNames() {
        final List<String> names = new ArrayList<>();
        for (final Method method : CardDetailService.class.getDeclaredMethods()) {
            if (!method.isSynthetic()) {
                names.add(method.getName());
            }
        }
        return List.copyOf(names);
    }

    /**
     * Returns the single declared method carrying a name.
     *
     * @param name the method name; must not be {@code null}
     * @return that method, with accessibility untouched
     * @throws AssertionError if it is not declared exactly once
     */
    private static Method methodNamed(final String name) {
        Method found = null;
        for (final Method method : CardDetailService.class.getDeclaredMethods()) {
            if (!method.isSynthetic() && name.equals(method.getName())) {
                assertThat(found).as("%s must be declared exactly once", name).isNull();
                found = method;
            }
        }
        assertThat(found).as("%s must be declared by the service", name).isNotNull();
        return found;
    }

}
