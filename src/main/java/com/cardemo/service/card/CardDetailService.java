/*
 ******************************************************************
 * Program     : CardDetailService.java
 * Application : CardDemo
 * Type        : Spring Boot Service Bean (Java 25)
 * Function    : Accept and process credit card detail request.
 * Source      : app/cbl/COCRDSLC.cbl (887 lines, 37 paragraphs) @ 7756d89
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
package com.cardemo.service.card;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import jakarta.persistence.PersistenceException;

/**
 * Credit card detail retrieval: the Java 25 / Spring Boot replacement for the CICS online program
 * {@code COCRDSLC}, reached in the legacy system through transaction {@code CCDL}
 * ({@code app/csd/CARDDEMO.CSD:347-348} declares {@code DEFINE TRANSACTION(CCDL)} with
 * {@code PROGRAM(COCRDSLC)}) and surfaced in the target through {@code CardController} under
 * {@code /api/cards/*}.
 *
 * <h2>What it does</h2>
 *
 * <p>It accepts an account filter and a card filter, edits both exactly as the source edits them, and
 * on success performs a single keyed read of the card master file. The source is 887 lines and 37
 * paragraphs; every applicable paragraph label is reproduced here as its own private method carrying a
 * {@code path:label:line} citation, because behavioural parity is the contract of this migration and
 * the traceability matrix has to stay mechanically provable against paragraph correspondence. The
 * order is fixed and is the order of {@code 2200-EDIT-MAP-INPUTS} at
 * {@code app/cbl/COCRDSLC.cbl:608}: normalise both filters, edit the account, edit the card, then
 * apply the cross-field edit, and only then read.
 *
 * <p>The read is a primary-key lookup and nothing more. {@code 9000-READ-DATA} at
 * {@code app/cbl/COCRDSLC.cbl:726-729} performs exactly one paragraph,
 * {@code 9100-GETCARD-BYACCTCARD THRU 9100-GETCARD-BYACCTCARD-EXIT}, and that paragraph issues
 * {@code EXEC CICS READ FILE(LIT-CARDFILENAME)} at {@code :742-750} where {@code LIT-CARDFILENAME} is
 * {@code 'CARDDAT '} at {@code :187-188} - the base cluster, keyed on the 16-character card number
 * moved at {@code :740}. <b>The account identifier is not part of the read predicate at all</b>; it is
 * validated and echoed, never queried on. That is why exactly one repository is injected, and why
 * neither the account, the cross-reference nor the customer repository appears in the constructor.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Build and test with {@code ./mvnw -B -ntp clean compile} and {@code ./mvnw -B -ntp test}, or the
 * whole gate with {@code ./mvnw -B -ntp -Ddependency-check.skip=true clean verify}. The build pins
 * {@code maven.compiler.release} to 25 with no preview features and runs {@code -Xlint:all -Werror}
 * with {@code failOnWarning}, so a single unused import in this file is a build failure rather than a
 * warning; {@code jacoco-maven-plugin} enforces an 80% LINE floor at {@code verify}, and no exclusion
 * for this class or this package may be added to satisfy it. A JDK 25 toolchain and Maven 3.9.11 are
 * installed on the host, so no container is required to compile; where a host toolchain is genuinely
 * absent the equivalent is
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 mvn -q -e verify}.
 * Environment values are supplied by the git-ignored {@code .env}, sourced with
 * {@code set -a; . ./.env; set +a} before invoking Maven.
 *
 * <p>Unit tests for this service live under {@code src/test/java/com/cardemo/unit/service/} and are
 * owned by a different agent; no test file is created alongside this one. What this class owes them is
 * testability, and it pays that in three ways: it performs no hidden I/O beyond the injected
 * repository, it declares no static initialiser and no static mutable state, and every edit, render
 * and compose step is a pure private method reachable without a Spring context. A test needs only a
 * stubbed {@code CardRepository}.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p><b>This service binds no configuration property of its own.</b> It reads no
 * {@code @Value}, no {@code @ConfigurationProperties}, no system property and no environment
 * variable, and it declares no {@code @Bean}. It consumes what the configuration classes own:
 * {@code JpaConfig} owns entity scanning and transaction management, {@code WebConfig} owns the
 * message converters that render the returned payload, {@code SecurityConfig} owns the filter chain
 * and the role mapping, and {@code MetricsConfig} owns the named counters - so no metric is registered
 * here. Two settings nevertheless govern its behaviour and are worth naming:
 * {@code spring.jpa.open-in-view} is {@code false}, which is safe here because the card entity exposes
 * {@code accountId} as a plain scalar rather than an association, so nothing can be lazily navigated
 * after the call returns; and {@code spring.jpa.show-sql} is {@code false}, which is what keeps a
 * 16-character card number out of the application log.
 *
 * <p>The one value that could have been made configurable deliberately is not. The screen furniture -
 * the transaction name {@code CCDL}, the program name {@code COCRDSLC} and the two screen titles - is
 * fixed by the source at {@code app/cbl/COCRDSLC.cbl:163-170} and {@code app/cpy/COTTL01Y.cpy:18-22}
 * and is declared as constants below, because a configurable value there would let a redeploy change
 * output that the parity comparison is measured against.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <p>Every outcome below is reached by exactly the source path cited, carries exactly the source's
 * literal text, and raises a typed {@code com.cardemo.exception} subtype. Nothing is swallowed and no
 * {@code catch} block logs and continues. Note the first column: the state is what determines the
 * {@code ValidationException} failure kind, and only the {@code BLANK} state stamps {@code '*'} back
 * into the echoed filter.
 *
 * <table border="1">
 *   <caption>Failure modes, their source sites, their exact legacy text and the Java outcome</caption>
 *   <tr><th>Condition</th><th>Source</th><th>Exact legacy message</th><th>Java outcome</th></tr>
 *   <tr>
 *     <td>Account blank, spaces, {@code "*"} or all zeros</td><td>{@code :651-661}</td>
 *     <td>{@code Account number not provided}</td>
 *     <td>{@code ValidationException}, field {@code accountId}, kind {@code BLANK}</td>
 *   </tr>
 *   <tr>
 *     <td>Account not 11 digits</td><td>{@code :665-678}</td>
 *     <td>{@code ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER}</td>
 *     <td>{@code ValidationException}, field {@code accountId}, kind {@code INVALID}</td>
 *   </tr>
 *   <tr>
 *     <td>Card blank, spaces, {@code "*"} or all zeros</td><td>{@code :691-702}</td>
 *     <td>{@code Card number not provided}</td>
 *     <td>{@code ValidationException}, field {@code cardNumber}, kind {@code BLANK}</td>
 *   </tr>
 *   <tr>
 *     <td>Card not 16 digits</td><td>{@code :706-719}</td>
 *     <td>{@code CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER}</td>
 *     <td>{@code ValidationException}, field {@code cardNumber}, kind {@code INVALID}</td>
 *   </tr>
 *   <tr>
 *     <td>Both filters blank</td><td>{@code :637-640}</td>
 *     <td>{@code No input received}</td>
 *     <td>{@code ValidationException}, request level, no field</td>
 *   </tr>
 *   <tr>
 *     <td>Card not found</td><td>{@code :755-761}</td>
 *     <td>{@code Did not find cards for this search condition}</td>
 *     <td>{@code RecordNotFoundException}</td>
 *   </tr>
 *   <tr>
 *     <td>Card file read failure</td><td>{@code :762-771}</td>
 *     <td>composed {@code File Error: } text, truncated to 75</td>
 *     <td>{@code FileAccessException}, cause preserved</td>
 *   </tr>
 *   <tr>
 *     <td>Unexpected dispatch state</td><td>{@code :373-380}</td>
 *     <td>{@code UNEXPECTED DATA SCENARIO}</td>
 *     <td>{@code FatalProcessingException}, payload code {@code 0001}, culprit {@code COCRDSLC}</td>
 *   </tr>
 *   <tr>
 *     <td>Abend handler</td><td>{@code :857-878}</td>
 *     <td>the {@code ABEND-DATA} block, CICS {@code ABCODE('9999')}</td>
 *     <td>{@code FatalProcessingException}, cause preserved</td>
 *   </tr>
 *   <tr>
 *     <td>Success</td><td>{@code :753-754}</td>
 *     <td>informational {@code &#32;&#32;&#32;Displaying requested details}</td>
 *     <td>a populated {@code CardDto}</td>
 *   </tr>
 *   <tr>
 *     <td>First entry, no input yet</td><td>{@code :349-356}</td>
 *     <td>informational {@code Please enter Account and Card Number}</td>
 *     <td>an empty prompt screen</td>
 *   </tr>
 * </table>
 *
 * <p><b>Troubleshooting the two outcomes that surprise people.</b> First, a caller that supplies
 * {@code "*"} in either filter gets {@code not provided}, not a wildcard search: {@code :615} and
 * {@code :622} move {@code LOW-VALUES} into the work field when the input is {@code '*'} <i>or</i>
 * {@code SPACES}, so the two are indistinguishable from that point on. Second, a caller that supplies
 * a short-but-numeric account such as three digits gets
 * {@code ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER} rather than a match, because
 * {@code :665} tests {@code CC-ACCT-ID IS NOT NUMERIC} against the whole {@code PIC X(11)} field
 * ({@code app/cpy/CVCRD01Y.cpy:34}) and the unused positions hold spaces, which are not digits. The
 * message means precisely what it says: exactly 11 digits, exactly 16 for the card.
 *
 * <h2>Preserved legacy characteristics, with severities</h2>
 *
 * <p>Classified per Rule 1 clause F. Each is reproduced rather than repaired, because parity is the
 * contract; none is a defect introduced here.
 *
 * <ul>
 *   <li><b>Blocker, discharged - the card verification value is never touched.</b>
 *       {@code app/cpy/CVACT02Y.cpy:7} declares {@code CARD-CVV-CD PIC 9(03)} and the entity exposes
 *       it, yet {@code 1200-SETUP-SCREEN-VARS} at {@code :474-485} moves only the embossed name, the
 *       expiry month, the expiry year and the active status to the screen. A token search of
 *       {@code app/cpy-bms/COCRDSL.CPY} for a verification-value field returns zero hits. This class
 *       therefore never calls the accessor, never projects it and never logs it, and no expiry-day
 *       member is populated either - the same search for an expiry-day token returns zero, even though
 *       {@code :90} extracts {@code CARD-EXPIRY-DAY} into working storage.</li>
 *   <li><b>Medium - the default abend message can never be substituted at runtime.</b> All four
 *       {@code ABEND-DATA} fields initialise to {@code VALUE SPACES}
 *       ({@code app/cpy/CSMSG02Y.cpy:22-29}, the member internally titled {@code CABENDD.CPY}), yet
 *       the guard at {@code :859} tests {@code ABEND-MSG EQUAL LOW-VALUES}, and the
 *       {@code INITIALIZE} at {@code :254-256} covers {@code CC-WORK-AREA},
 *       {@code WS-MISC-STORAGE} and {@code WS-COMMAREA} but not {@code ABEND-DATA}. The test is
 *       therefore always false and {@code 'UNEXPECTED ABEND OCCURRED.'} is never written. In Java the
 *       substitution happens only when the message is {@code null} and never when it is blank or
 *       empty, which {@code FatalProcessingException} already implements. Remediation: none - the
 *       behaviour is preserved deliberately and recorded for the decision log.</li>
 *   <li><b>Medium - two different file-error formats exist and must not be merged.</b> The composed
 *       online text assembled at {@code :102-121} is this program's own, and its leading literal is
 *       {@code 'File Error: '} <i>with</i> a trailing space while its final {@code FILLER} carries
 *       {@code VALUE SPACES}; the card-list program differs on both counts. It is also not the batch
 *       {@code FILE STATUS IS: NNNN} literal owned by {@code FileStatus} and
 *       {@code FileStatusMapper}. That literal is never reformatted, wrapped, prefixed or truncated
 *       by this class, because this class does not route through the mapper at all - see the
 *       constructor.</li>
 *   <li><b>Low - two paragraph pairs are declared and never performed.</b>
 *       {@code 9150-GETCARD-BYACCT} and its exit, and {@code SEND-LONG-TEXT} and its exit, are
 *       retained as four separate empty methods. The reasoning, the grep evidence and the Rule 1
 *       clause B conflict resolution are on each method.</li>
 *   <li><b>Low - an invalid function key is silently coerced to Enter.</b> {@code :291-299} sets the
 *       key invalid, validates only Enter and PF03, then reassigns Enter when the key was invalid. No
 *       error is raised for an unrecognised key and none is raised here.</li>
 *   <li><b>Low - {@code LIT-CCLISTMAP} holds the wrong map name.</b> {@code :177-178} declares the
 *       card <i>list</i> map literal as {@code 'CCRDSLA'}, which is the card <i>detail</i> map name;
 *       {@code 'CCRDLIA'} is meant. It has no functional effect in the target because navigation is
 *       URL-based, so it is documented and not fixed.</li>
 *   <li><b>Low - the customer copybook is included and never used.</b> {@code COPY CVCUS01Y} is
 *       present at {@code :240} but no {@code CUSTDAT} file is ever opened, so no customer repository
 *       is injected. Corroborated by the commented-out {@code COPY CVACT01Y} at {@code :231} and
 *       {@code COPY CVACT03Y} at {@code :237}, and by the commented-out vestige at {@code :739}.</li>
 * </ul>
 *
 * <p><b>Statelessness, thread safety and side effects.</b> Both fields are immutable and the class
 * declares no mutable state whatsoever, so the singleton bean is safe to share across request threads.
 * Every legacy {@code WORKING-STORAGE} flag - {@code WS-INPUT-FLAG} with its
 * {@code INPUT-OK}/{@code INPUT-ERROR}/{@code INPUT-PENDING} conditions at {@code :51-54},
 * {@code WS-EDIT-ACCT-FLAG} at {@code :55-58}, {@code WS-EDIT-CARD-FLAG} at {@code :59-62},
 * {@code WS-RETURN-FLAG} at {@code :63-65}, {@code WS-PFK-FLAG} at {@code :66-68} and the
 * {@code WS-INFO-MSG} markers at {@code :126-132} - lives on a per-request work area created inside
 * the entry method and discarded when it returns, which is the direct translation of
 * {@code INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA} at {@code :254-256}. The only side
 * effects are one repository read and, on the I/O failure path, one structured log event. No
 * server-side session state is retained, no {@code RETURN TRANSID} equivalent exists, and this service
 * is read-only, so it declares no transaction: a RESP census of the source finds
 * {@code DFHRESP(NORMAL)} twice and {@code DFHRESP(NOTFND)} twice and nothing else, with no
 * {@code WRITE}, {@code REWRITE} or {@code DELETE} anywhere.
 *
 * <p><b>Determinism.</b> Every case operation and every formatter passes {@code Locale.ROOT}, so
 * rendering cannot vary with the platform default locale, and no financial value is handled at all -
 * the card record declares none, so no decimal type is imported and no approximate binary type appears
 * anywhere in this file.
 */
@Service
public class CardDetailService {

    /**
     * The one logger for this service. The source's only instrumentation is screen text, so the single
     * log event emitted here stands in for the diagnosis {@code EXEC CICS SEND} would have shown an
     * operator, and it is written on the I/O failure path only.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CardDetailService.class);

    /**
     * {@code LIT-THISPGM} at {@code app/cbl/COCRDSLC.cbl:163-164}, value {@code 'COCRDSLC'}. Echoed
     * into the screen header and used as the abend culprit.
     */
    private static final String THIS_PROGRAM = "COCRDSLC";

    /**
     * {@code LIT-THISTRANID} at {@code app/cbl/COCRDSLC.cbl:165-166}, value {@code 'CCDL'}. Moved to
     * {@code WS-TRANID} at {@code :260} and echoed into the screen header.
     */
    private static final String THIS_TRANSACTION_ID = "CCDL";

    /**
     * {@code LIT-CARDFILENAME} at {@code app/cbl/COCRDSLC.cbl:187-188}, value {@code 'CARDDAT '} - the
     * base card cluster and the only file this program actually opens. The trailing space of the
     * {@code PIC X(8)} literal is dropped because the value is used as a diagnostic name rather than as
     * a fixed-width record field; the composed file-error text pads it back to its own width.
     *
     * <p>Its sibling {@code LIT-CARDFILENAME-ACCT-PATH} at {@code :189-190}, value {@code 'CARDAIX '},
     * is deliberately <b>not</b> declared: it is referenced only from the unreachable
     * {@code 9150-GETCARD-BYACCT} body, so a constant for it would be untracked dead code under Rule 1
     * clause B. Its name is recorded in that method's documentation instead.
     */
    private static final String CARD_FILE_NAME = "CARDDAT";

    /**
     * {@code MOVE 'READ' TO ERROR-OPNAME} at {@code app/cbl/COCRDSLC.cbl:767}, the operation name that
     * the composed file-error text reports.
     */
    private static final String READ_OPERATION = "READ";

    /**
     * {@code CCDA-TITLE01} at {@code app/cpy/COTTL01Y.cpy:18-19}. Exactly 40 characters: six leading
     * spaces, the 27-character title, seven trailing spaces. Reproduced verbatim because the parity
     * comparison is byte for byte.
     */
    private static final String SCREEN_TITLE_01 = "      AWS Mainframe Modernization       ";

    /**
     * {@code CCDA-TITLE02} at {@code app/cpy/COTTL01Y.cpy:20-22}. Exactly 40 characters: fourteen
     * leading spaces, {@code CardDemo}, eighteen trailing spaces. The commented-out alternative on
     * {@code :21} of that copybook is not used and is not reproduced.
     */
    private static final String SCREEN_TITLE_02 = "              CardDemo                  ";

    /**
     * {@code 88 WS-PROMPT-FOR-ACCT} at {@code app/cbl/COCRDSLC.cbl:138-139}. Set under the guard at
     * {@code :656-658}.
     */
    private static final String ACCOUNT_NOT_PROVIDED_MESSAGE = "Account number not provided";

    /**
     * The inline literal moved at {@code app/cbl/COCRDSLC.cbl:669-671}. Note the comma with <b>no</b>
     * following space, and the article {@code A} before {@code 11}; both are reproduced exactly.
     *
     * <p>This is the text the program actually emits. The two declared {@code 88} levels
     * {@code SEARCHED-ACCT-ZEROES} at {@code :144-145} and {@code SEARCHED-ACCT-NOT-NUMERIC} at
     * {@code :146-147} carry a different, mixed-case wording - and carry it twice, two names over one
     * text - but neither is ever {@code SET}, so neither becomes a Java constant.
     */
    private static final String ACCOUNT_FILTER_NOT_NUMERIC_MESSAGE =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /**
     * {@code 88 WS-PROMPT-FOR-CARD} at {@code app/cbl/COCRDSLC.cbl:140-141}. Set under the guard at
     * {@code :696-698}.
     */
    private static final String CARD_NOT_PROVIDED_MESSAGE = "Card number not provided";

    /**
     * The inline literal moved at {@code app/cbl/COCRDSLC.cbl:710-712}, with the same comma-without-space
     * shape as its account counterpart. The declared {@code 88 SEARCHED-CARD-NOT-NUMERIC} at
     * {@code :148-149} is never {@code SET} and is therefore not represented.
     */
    private static final String CARD_FILTER_NOT_NUMERIC_MESSAGE =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /**
     * {@code 88 NO-SEARCH-CRITERIA-RECEIVED} at {@code app/cbl/COCRDSLC.cbl:142-143}, set by the
     * cross-field edit at {@code :637-640}.
     */
    private static final String NO_INPUT_RECEIVED_MESSAGE = "No input received";

    /**
     * {@code 88 DID-NOT-FIND-ACCTCARD-COMBO} at {@code app/cbl/COCRDSLC.cbl:153-154}, set under the
     * guard at {@code :759-761} when the keyed read finds nothing.
     */
    private static final String CARD_NOT_FOUND_MESSAGE = "Did not find cards for this search condition";

    /**
     * The literal moved at {@code app/cbl/COCRDSLC.cbl:377-378} on the {@code WHEN OTHER} dispatch arm.
     */
    private static final String UNEXPECTED_DATA_SCENARIO_MESSAGE = "UNEXPECTED DATA SCENARIO";

    /**
     * {@code 88 FOUND-CARDS-FOR-ACCOUNT} at {@code app/cbl/COCRDSLC.cbl:129-130}. <b>Three leading
     * spaces</b>, reproduced exactly.
     *
     * <p>Worth reading carefully: this condition name is declared on {@code WS-INFO-MSG}, so
     * {@code SET FOUND-CARDS-FOR-ACCOUNT TO TRUE} at {@code :754} does not raise a separate boolean -
     * it <i>moves this literal into the information message</i>. The success marker and the success
     * text are one and the same field, which is why {@code 1200-SETUP-SCREEN-VARS} can test
     * {@code IF FOUND-CARDS-FOR-ACCOUNT} at {@code :474} to decide whether to project the card.
     */
    private static final String DISPLAYING_DETAILS_MESSAGE = "   Displaying requested details";

    /**
     * {@code 88 WS-PROMPT-FOR-INPUT} at {@code app/cbl/COCRDSLC.cbl:131-132}, also declared on
     * {@code WS-INFO-MSG}. Set at {@code :460} when no communication area was passed and again at
     * {@code :490-492} whenever the information message is still empty.
     */
    private static final String PROMPT_FOR_INPUT_MESSAGE = "Please enter Account and Card Number";

    /**
     * {@code MOVE '0001' TO ABEND-CODE} at {@code app/cbl/COCRDSLC.cbl:375} - the abend <i>payload</i>
     * code of the {@code WHEN OTHER} arm.
     *
     * <p>Three distinct abend values coexist in this corpus and confusing them is easy: the batch value
     * is {@code 999} with return code 12 ({@code app/cbl/CBTRN02C.cbl:707-711}), the online
     * <i>abcode</i> raised by this program's handler is {@code '9999'}
     * ({@code app/cbl/COCRDSLC.cbl:875-877}), and this one is the online <i>payload</i> field. Each site
     * cites its own.
     */
    private static final String UNEXPECTED_DATA_ABEND_CODE = "0001";

    /**
     * {@code EXEC CICS ABEND ABCODE('9999')} at {@code app/cbl/COCRDSLC.cbl:875-877} - the online abend
     * code this program's handler raises. Not {@code 999}, which is the batch value.
     */
    private static final String HANDLER_ABEND_CODE = "9999";

    /**
     * Width of {@code ABEND-REASON PIC X(50)} at {@code app/cpy/CSMSG02Y.cpy:26-27}, used to reproduce
     * {@code MOVE SPACES TO ABEND-REASON} at {@code app/cbl/COCRDSLC.cbl:376} at its declared width.
     */
    private static final int ABEND_REASON_WIDTH = 50;

    /**
     * {@code MOVE SPACES TO ABEND-REASON} at {@code app/cbl/COCRDSLC.cbl:376}, rendered at the field's
     * declared width so the payload is the same shape the source sends.
     */
    private static final String ABEND_REASON_SPACES = " ".repeat(ABEND_REASON_WIDTH);

    /**
     * Width of {@code CC-ACCT-ID PIC X(11)} at {@code app/cpy/CVCRD01Y.cpy:34}, matching
     * {@code ACCTSIDI PIC X(11)} at {@code app/cpy-bms/COCRDSL.CPY:60} and the 11-byte cluster key.
     */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /**
     * Width of {@code CC-CARD-NUM PIC X(16)} at {@code app/cpy/CVCRD01Y.cpy:37}, matching
     * {@code CARDSIDI PIC X(16)} at {@code app/cpy-bms/COCRDSL.CPY:66} and the 16-byte cluster key.
     */
    private static final int CARD_NUMBER_WIDTH = 16;

    /**
     * Width of {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDSLC.cbl:134}. This is the width
     * that truncates the 80-character composed file-error text, and the truncation is reproduced rather
     * than avoided.
     */
    private static final int RETURN_MESSAGE_WIDTH = 75;

    /**
     * The single character the source writes back into a filter that was left blank -
     * {@code MOVE '*' TO ACCTSIDO} at {@code app/cbl/COCRDSLC.cbl:543} and
     * {@code MOVE '*' TO CARDSIDO} at {@code :549}. It is also the character the source accepts on the
     * way in as meaning "not supplied", at {@code :615} and {@code :622}, which is what makes the
     * marker round-trip.
     */
    private static final String BLANK_FILTER_MARKER = "*";

    /**
     * The name carried on a {@code ValidationException} when the account filter is at fault, standing
     * in for {@code MOVE -1 TO ACCTSIDL} at {@code app/cbl/COCRDSLC.cbl:518} and {@code :523}. The
     * <b>name</b> travels, never the value: an account identifier is not echoed into diagnostics.
     */
    private static final String FIELD_ACCOUNT_ID = "accountId";

    /**
     * The name carried on a {@code ValidationException} when the card filter is at fault, standing in
     * for {@code MOVE -1 TO CARDSIDL} at {@code app/cbl/COCRDSLC.cbl:521}. As above, the name travels
     * and the value never does - this one is a card number.
     */
    private static final String FIELD_CARD_NUMBER = "cardNumber";

    /**
     * {@code WS-CURDATE-MM-DD-YY} at {@code app/cpy/CSDAT01Y.cpy:30-35}: two digits, {@code '/'}, two
     * digits, {@code '/'}, two digits - eight characters, matching {@code CURDATEI PIC X(8)} at
     * {@code app/cpy-bms/COCRDSL.CPY:36}. {@code Locale.ROOT} is passed so the rendering cannot vary
     * with the platform default locale.
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * {@code WS-CURTIME-HH-MM-SS} at {@code app/cpy/CSDAT01Y.cpy:36-41}: two digits, {@code ':'}, two
     * digits, {@code ':'}, two digits - eight characters, matching {@code CURTIMEI PIC X(8)} at
     * {@code app/cpy-bms/COCRDSL.CPY:54}. The copybook's {@code WS-CURTIME-MILSEC} is not part of this
     * rendering and is not emitted.
     */
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * The {@code LOW-VALUES} image of {@code CC-ACCT-ID PIC X(11)}. {@code LOW-VALUES} is the lowest
     * character in the collating sequence, that is a byte of binary zeros, so the faithful Java image is
     * a string of NUL characters at the field's declared width.
     *
     * <p>This is not cosmetic. {@code 2210-EDIT-ACCOUNT} tests {@code EQUAL LOW-VALUES} and
     * {@code EQUAL SPACES} as two <i>separate</i> conditions at {@code app/cbl/COCRDSLC.cbl:651-652},
     * and {@code 1200-SETUP-SCREEN-VARS} moves {@code LOW-VALUES} to an output field at {@code :463} to
     * mean "transmit nothing". Collapsing the two would erase a distinction the source relies on, so
     * they are kept apart and neither is ever coerced to the other.
     */
    private static final String ACCOUNT_ID_LOW_VALUES = "\u0000".repeat(ACCOUNT_ID_WIDTH);

    /**
     * The {@code LOW-VALUES} image of {@code CC-CARD-NUM PIC X(16)}, on the same terms as its account
     * counterpart. Tested at {@code app/cbl/COCRDSLC.cbl:691} and written at {@code :469}.
     */
    private static final String CARD_NUMBER_LOW_VALUES = "\u0000".repeat(CARD_NUMBER_WIDTH);

    /**
     * The {@code SPACES} image of {@code CC-ACCT-ID PIC X(11)}, for the {@code EQUAL SPACES} test at
     * {@code app/cbl/COCRDSLC.cbl:652}.
     */
    private static final String ACCOUNT_ID_SPACES = " ".repeat(ACCOUNT_ID_WIDTH);

    /**
     * The {@code SPACES} image of {@code CC-CARD-NUM PIC X(16)}, for the {@code EQUAL SPACES} test at
     * {@code app/cbl/COCRDSLC.cbl:692}.
     */
    private static final String CARD_NUMBER_SPACES = " ".repeat(CARD_NUMBER_WIDTH);

    /**
     * What a log event says in place of a card number that is present. The value itself is never
     * rendered - not masked to its last four digits, not hashed, not truncated - because Rule 1
     * clause D admits no partial disclosure and the entity's own {@code toString} likewise excludes it.
     */
    private static final String CARD_NUMBER_REDACTED = "<redacted-card-number>";

    /**
     * What a log event says when there is no card number to describe at all, keeping the absent case
     * distinguishable from the redacted case without disclosing either.
     */
    private static final String CARD_NUMBER_ABSENT = "<absent>";

    /**
     * {@code LIT-THISMAPSET PIC X(8) VALUE 'COCRDSL '} at {@code app/cbl/COCRDSLC.cbl:167-168},
     * reproduced with its declared trailing pad byte. It is moved to {@code CDEMO-LAST-MAPSET} at
     * {@code :328} and named on {@code EXEC CICS SEND MAP} at {@code :565} and {@code :589}. BMS is not
     * reimplemented, so the value is carried as provenance on the response and never used to route.
     */
    private static final String THIS_MAPSET = "COCRDSL ";

    /**
     * {@code LIT-THISMAP PIC X(7) VALUE 'CCRDSLA'} at {@code app/cbl/COCRDSLC.cbl:169-170}, moved to
     * {@code CDEMO-LAST-MAP} at {@code :329} and named on the two {@code SEND MAP} commands.
     */
    private static final String THIS_MAP = "CCRDSLA";

    /**
     * {@code LIT-CCLISTPGM PIC X(8) VALUE 'COCRDLIC'} at {@code app/cbl/COCRDSLC.cbl:171-172}. It is
     * the discriminant of the pre-validated arrival branch at {@code :339-340} and of the field
     * protection tests at {@code :506} and {@code :528}, which makes it live data rather than a name
     * carried for documentation.
     */
    private static final String CARD_LIST_PROGRAM = "COCRDLIC";

    /**
     * {@code LIT-CCLISTMAPSET PIC X(7) VALUE 'COCRDLI'} at {@code app/cbl/COCRDSLC.cbl:175-176},
     * compared against {@code CDEMO-LAST-MAPSET} at {@code :505} and {@code :527}.
     *
     * <p><b>Its sibling {@code LIT-CCLISTMAP} is deliberately absent from this class.</b> That item is
     * declared at {@code :177-178} as {@code PIC X(7) VALUE 'CCRDSLA'} - the card <i>detail</i> map name
     * where the card <i>list</i> map name {@code 'CCRDLIA'} is plainly intended - and a scan of the
     * procedure division from {@code :247} to end of member returns no reference to it at all. Severity
     * <b>Low</b>: it is inert in the source and inert in the target, because navigation here is
     * URL-based and no map name is resolved at runtime. Remediation would be a one-word correction to a
     * frozen file, so the finding is recorded and not fixed, and declaring a Java constant for an item
     * the source never reads would be exactly the untracked dead code Rule 1 clause B forbids.
     */
    private static final String CARD_LIST_MAPSET = "COCRDLI";

    /**
     * {@code LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'} at {@code app/cbl/COCRDSLC.cbl:179-180}, read at
     * {@code :269} by the commarea-reset test and at {@code :318} as the default transfer target.
     */
    private static final String MENU_PROGRAM = "COMEN01C";

    /**
     * {@code LIT-MENUTRANID PIC X(4) VALUE 'CM00'} at {@code app/cbl/COCRDSLC.cbl:181-182}, the default
     * transaction the exit branch falls back to at {@code :311}.
     */
    private static final String MENU_TRANSACTION_ID = "CM00";

    /**
     * {@code CCARD-AID-ENTER VALUE 'ENTER'} - {@code app/cpy/CVCRD01Y.cpy:4}, on the five-byte
     * {@code CCARD-AID} of {@code :3}. The mapped attention identifiers are held as their declared
     * five-character images rather than as a second enumeration, so that the width-dependent
     * {@code 'PA1  '} and {@code 'PA2  '} values keep the trailing pad the copybook gives them.
     */
    private static final String CCARD_AID_ENTER = "ENTER";

    /** {@code CCARD-AID-CLEAR VALUE 'CLEAR'} - {@code app/cpy/CVCRD01Y.cpy:5}. */
    private static final String CCARD_AID_CLEAR = "CLEAR";

    /** {@code CCARD-AID-PA1 VALUE 'PA1  '} - {@code app/cpy/CVCRD01Y.cpy:6}, padded to five bytes. */
    private static final String CCARD_AID_PA1 = "PA1  ";

    /** {@code CCARD-AID-PA2 VALUE 'PA2  '} - {@code app/cpy/CVCRD01Y.cpy:7}, padded to five bytes. */
    private static final String CCARD_AID_PA2 = "PA2  ";

    /**
     * {@code CCARD-AID-PFK03 VALUE 'PFK03'} - {@code app/cpy/CVCRD01Y.cpy:10}. Named individually
     * because {@code app/cbl/COCRDSLC.cbl:292-293} admits exactly two attention identifiers, this one
     * and {@code ENTER}, and {@code :305} dispatches the exit branch on it.
     */
    private static final String CCARD_AID_PFK03 = "PFK03";

    /**
     * The stem shared by {@code CCARD-AID-PFK01} through {@code CCARD-AID-PFK12} -
     * {@code app/cpy/CVCRD01Y.cpy:8-19}. The mapping method appends the two-digit ordinal, which is how
     * the copybook's own values are formed and what makes the {@code DFHPF13}-through-{@code DFHPF24}
     * fold onto {@code PFK01} through {@code PFK12} expressible without twelve further constants.
     */
    private static final String CCARD_AID_PFK_PREFIX = "PFK";

    /**
     * The number of distinct programmed function keys {@code CCARD-AID} can represent -
     * {@code app/cpy/CVCRD01Y.cpy:8-19} declares twelve, {@code PFK01} through {@code PFK12}, while
     * {@code app/cpy/CSSTRPFY.cpy} maps twenty-four physical keys onto them.
     */
    private static final int MAPPED_FUNCTION_KEY_COUNT = 12;

    /**
     * {@code FILLER PIC X(12) VALUE 'File Error: '} - the leading segment of
     * {@code WS-FILE-ERROR-MESSAGE} at {@code app/cbl/COCRDSLC.cbl:103-104}.
     *
     * <p><b>The trailing space is part of the literal and must not be trimmed.</b> The corresponding
     * segment in {@code app/cbl/COCRDLIC.cbl} reads {@code 'File Error:'} without it, which is one of
     * the two reasons a shared composer across the two programs would silently break parity in both.
     */
    private static final String FILE_ERROR_PREFIX = "File Error: ";

    /**
     * {@code FILLER PIC X(4) VALUE ' on '} - {@code app/cbl/COCRDSLC.cbl:107-108}. Both spaces are
     * inside the literal.
     */
    private static final String FILE_ERROR_ON_SEGMENT = " on ";

    /**
     * {@code FILLER PIC X(15) VALUE ' returned RESP '} - {@code app/cbl/COCRDSLC.cbl:111-113}, split
     * across three source lines by continuation and carrying a leading and a trailing space.
     */
    private static final String FILE_ERROR_RESP_SEGMENT = " returned RESP ";

    /**
     * {@code FILLER PIC X(7) VALUE ',RESP2 '} - {@code app/cbl/COCRDSLC.cbl:116-117}. There is no space
     * after the comma and there is one before the value, exactly as declared.
     */
    private static final String FILE_ERROR_RESP2_SEGMENT = ",RESP2 ";

    /**
     * {@code FILLER PIC X(5) VALUE SPACES} - the trailing segment at
     * {@code app/cbl/COCRDSLC.cbl:120-121}. It carries {@code VALUE SPACES} here, where
     * {@code app/cbl/COCRDLIC.cbl} leaves its counterpart without a {@code VALUE} clause; that is the
     * second reason the composition cannot be shared between the two programs.
     */
    private static final String FILE_ERROR_TRAILING_FILLER = "     ";

    /** {@code ERROR-OPNAME PIC X(8)} - {@code app/cbl/COCRDSLC.cbl:105-106}. */
    private static final int ERROR_OPNAME_WIDTH = 8;

    /**
     * {@code ERROR-FILE PIC X(9)} - {@code app/cbl/COCRDSLC.cbl:109-110}. The name moved into it,
     * {@code LIT-CARDFILENAME PIC X(8) VALUE 'CARDDAT '} at {@code :187-188}, is one byte narrower, so
     * the nine bytes that reach the message are {@code CARDDAT} followed by two spaces whether the pad
     * is taken from the literal or supplied by this field's width.
     */
    private static final int ERROR_FILE_WIDTH = 9;

    /**
     * {@code ERROR-RESP PIC X(10)} and {@code ERROR-RESP2 PIC X(10)} -
     * {@code app/cbl/COCRDSLC.cbl:114-115} and {@code :118-119}.
     */
    private static final int ERROR_RESP_WIDTH = 10;

    /**
     * What the two response-code segments of the message hold in the target: their declared
     * {@code VALUE SPACES} from {@code app/cbl/COCRDSLC.cbl:115} and {@code :119}.
     *
     * <p><b>Not available, deliberately.</b> {@code :769-770} move {@code WS-RESP-CD} and
     * {@code WS-REAS-CD} - both {@code PIC S9(09) COMP} at {@code :41} and {@code :43} - into these
     * ten-byte fields, which as numeric-to-alphanumeric moves would render nine unsigned display digits
     * left-justified with one trailing space. There is no counterpart to a CICS response code in the
     * target: a JPA or JDBC failure surfaces as an exception, not as a numbered condition, so no value
     * exists to render. Rendering zeros instead would be worse than rendering nothing, because
     * {@code 000000000} is the encoding of {@code DFHRESP(NORMAL)} and would assert that the read
     * succeeded. The fields therefore keep the spaces they are declared with, which preserves the
     * message's eighty-byte geometry exactly while asserting nothing false. The underlying cause travels
     * on the exception instead, where it is not width-constrained.
     */
    private static final String ERROR_RESP_UNAVAILABLE = " ".repeat(ERROR_RESP_WIDTH);

    /**
     * {@code CARD-EXPIRAION-DATE PIC X(10)} of {@code app/cpy/CVACT02Y.cpy}, redefined as
     * {@code CARD-EXPIRAION-DATE-X} at {@code app/cbl/COCRDSLC.cbl:84-92}. The misspelling is the
     * copybook's and is preserved by the entity property name.
     */
    private static final int EXPIRY_DATE_WIDTH = 10;

    /**
     * The zero-based start of {@code CARD-EXPIRY-YEAR PIC X(4)}, which the redefinition places at
     * {@code (1:4)} - {@code app/cbl/COCRDSLC.cbl:85}.
     */
    private static final int EXPIRY_YEAR_OFFSET = 0;

    /** The width of {@code CARD-EXPIRY-YEAR} - {@code app/cbl/COCRDSLC.cbl:85}. */
    private static final int EXPIRY_YEAR_WIDTH = 4;

    /**
     * The zero-based start of {@code CARD-EXPIRY-MONTH PIC X(2)}, which the redefinition places at
     * {@code (6:2)} - {@code app/cbl/COCRDSLC.cbl:88}. One separator byte sits before it at {@code (5:1)}
     * and another after it at {@code (8:1)}, which is what makes the field dash-separated text.
     */
    private static final int EXPIRY_MONTH_OFFSET = 5;

    /** The width of {@code CARD-EXPIRY-MONTH} - {@code app/cbl/COCRDSLC.cbl:88}. */
    private static final int EXPIRY_MONTH_WIDTH = 2;

    /**
     * The assembled width of {@code WS-FILE-ERROR-MESSAGE} - 12 + 8 + 4 + 9 + 15 + 10 + 7 + 10 + 5
     * across {@code app/cbl/COCRDSLC.cbl:102-121}.
     *
     * <p>It is eighty bytes wide and {@code :768} moves it into {@code WS-RETURN-MSG PIC X(75)} at
     * {@code :134}, so five bytes are discarded by the move. Composing to eighty and then truncating to
     * {@link #RETURN_MESSAGE_WIDTH} reproduces that; composing straight to seventy-five would not,
     * because the discarded bytes are not always the trailing filler.
     */
    private static final int FILE_ERROR_MESSAGE_WIDTH = 80;

    /**
     * {@code WS-INFO-MSG PIC X(40)} at {@code app/cbl/COCRDSLC.cbl:126}, the field whose condition
     * names at {@code :129-132} are the information messages this service reports.
     */
    private static final int INFO_MESSAGE_WIDTH = 40;


    /**
     * The card master repository - the relational replacement for CICS file {@code CARDDAT} over
     * {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS}, keyed on the 16-character card number.
     *
     * <p><b>This is the only collaborator, and the list is closed.</b> The program opens exactly one
     * file: {@code EXEC CICS READ FILE(LIT-CARDFILENAME)} at {@code app/cbl/COCRDSLC.cbl:742-750} is
     * the sole live read, and the only other {@code READ} in the member sits inside the unreachable
     * {@code 9150} body at {@code :783-791}. No {@code ACCTDAT}, {@code CCXREF}, {@code CUSTDAT} or
     * {@code CXACAIX} is opened anywhere, so an account, cross-reference or customer repository here
     * would be an unused field - dead code under Rule 1 clause B and, with its import, a hard failure
     * under {@code -Werror}.
     */
    private final CardRepository cardRepository;

    /**
     * The clock behind {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at
     * {@code app/cbl/COCRDSLC.cbl:430} and {@code :437}.
     *
     * <p><b>Why it is not constructor-injected.</b> No {@code Clock} bean exists in this application
     * context - there is no {@code config} package at this commit - so asking for one would prevent the
     * context from starting. A second constructor taking a {@code Clock} for tests is not an option
     * either: Spring's implicit constructor injection requires exactly one declared constructor, and
     * annotating one with {@code @Autowired} to disambiguate is prohibited for this file. The
     * resolution keeps both properties that matter. Determinism is preserved because
     * {@code Clock.systemDefaultZone()} is immutable and thread safe and the zone is the deployment's,
     * exactly as the CICS region's was. Testability is preserved because the clock is read in one place
     * only and every rendering step below it is a pure static function of the {@code LocalDateTime} it
     * produces, so the formatting is assertable without controlling time.
     */
    private final Clock clock;

    /**
     * Creates the service. This is the <b>only</b> declared constructor, so Spring performs implicit
     * constructor injection without an {@code @Autowired} annotation.
     *
     * <p><b>{@code FileStatusMapper} is deliberately absent.</b> Its entire surface is keyed on
     * two-character COBOL {@code FILE STATUS} values - {@code '00'}, {@code '10'}, {@code '23'},
     * {@code '35'} and the {@code '9x'} family - and the exception it builds carries the batch
     * {@code FILE STATUS IS: NNNN} wording. This program has no {@code FILE STATUS} anywhere: it is a
     * CICS online program that tests {@code RESP} values, and the text it must emit on an I/O failure is
     * the online composition of {@code app/cbl/COCRDSLC.cbl:102-121} truncated to 75 characters.
     * Routing through the mapper would mean inventing a status code the source never produces and
     * emitting a message the source never emits, and injecting it without routing through it would leave
     * an unused field and an unused import, which {@code -Werror} rejects outright. The exception is
     * therefore constructed directly, and the mapper's own literal is left untouched.
     *
     * @param cardRepository the card master repository; must not be {@code null}.
     * @throws NullPointerException if {@code cardRepository} is {@code null}.
     */
    public CardDetailService(final CardRepository cardRepository) {
        this.cardRepository = Objects.requireNonNull(cardRepository, "cardRepository must not be null");
        this.clock = Clock.systemDefaultZone();
    }


    /**
     * The attention identifier the terminal raised, standing in for the CICS-supplied {@code EIBAID}
     * byte that {@code app/cpy/CSSTRPFY.cpy} evaluates.
     *
     * <p>The copybook compares {@code EIBAID} against the {@code DFHAID} equates - {@code DFHENTER},
     * {@code DFHCLEAR}, {@code DFHPA1}, {@code DFHPA2} and {@code DFHPF1} through {@code DFHPF24}. That
     * copybook is supplied by the transaction monitor and is not present in this repository, so the
     * equates cannot be imported and are enumerated here instead. HTTP has no attention identifier at
     * all, so a caller that has no function key to report passes {@link #ENTER}, which is what the
     * source's own coercion at {@code app/cbl/COCRDSLC.cbl:297-299} would produce for it anyway.
     */
    public enum AttentionIdentifier {

        /** {@code DFHENTER} - {@code app/cpy/CSSTRPFY.cpy:19}. */
        ENTER,

        /** {@code DFHCLEAR} - {@code app/cpy/CSSTRPFY.cpy:21}. */
        CLEAR,

        /** {@code DFHPA1} - {@code app/cpy/CSSTRPFY.cpy:23}. */
        PA1,

        /** {@code DFHPA2} - {@code app/cpy/CSSTRPFY.cpy:25}. */
        PA2,

        /** {@code DFHPF1} - folds onto {@code CCARD-AID-PFK01} together with {@link #PF13}. */
        PF1,

        /** {@code DFHPF2} - folds onto {@code CCARD-AID-PFK02} together with {@link #PF14}. */
        PF2,

        /** {@code DFHPF3} - folds onto {@code CCARD-AID-PFK03} together with {@link #PF15}. */
        PF3,

        /** {@code DFHPF4} - folds onto {@code CCARD-AID-PFK04} together with {@link #PF16}. */
        PF4,

        /** {@code DFHPF5} - folds onto {@code CCARD-AID-PFK05} together with {@link #PF17}. */
        PF5,

        /** {@code DFHPF6} - folds onto {@code CCARD-AID-PFK06} together with {@link #PF18}. */
        PF6,

        /** {@code DFHPF7} - folds onto {@code CCARD-AID-PFK07} together with {@link #PF19}. */
        PF7,

        /** {@code DFHPF8} - folds onto {@code CCARD-AID-PFK08} together with {@link #PF20}. */
        PF8,

        /** {@code DFHPF9} - folds onto {@code CCARD-AID-PFK09} together with {@link #PF21}. */
        PF9,

        /** {@code DFHPF10} - folds onto {@code CCARD-AID-PFK10} together with {@link #PF22}. */
        PF10,

        /** {@code DFHPF11} - folds onto {@code CCARD-AID-PFK11} together with {@link #PF23}. */
        PF11,

        /** {@code DFHPF12} - folds onto {@code CCARD-AID-PFK12} together with {@link #PF24}. */
        PF12,

        /** {@code DFHPF13} - folds onto {@code CCARD-AID-PFK01}, sharing it with {@link #PF1}. */
        PF13,

        /** {@code DFHPF14} - folds onto {@code CCARD-AID-PFK02}. */
        PF14,

        /** {@code DFHPF15} - folds onto {@code CCARD-AID-PFK03}. */
        PF15,

        /** {@code DFHPF16} - folds onto {@code CCARD-AID-PFK04}. */
        PF16,

        /** {@code DFHPF17} - folds onto {@code CCARD-AID-PFK05}. */
        PF17,

        /** {@code DFHPF18} - folds onto {@code CCARD-AID-PFK06}. */
        PF18,

        /** {@code DFHPF19} - folds onto {@code CCARD-AID-PFK07}. */
        PF19,

        /** {@code DFHPF20} - folds onto {@code CCARD-AID-PFK08}. */
        PF20,

        /** {@code DFHPF21} - folds onto {@code CCARD-AID-PFK09}. */
        PF21,

        /** {@code DFHPF22} - folds onto {@code CCARD-AID-PFK10}. */
        PF22,

        /** {@code DFHPF23} - folds onto {@code CCARD-AID-PFK11}. */
        PF23,

        /** {@code DFHPF24} - folds onto {@code CCARD-AID-PFK12}. */
        PF24,

        /**
         * Any attention identifier the copybook does not enumerate - a light-pen detect or a
         * trigger-field attention, for instance.
         *
         * <p>It exists because {@code app/cpy/CSSTRPFY.cpy}'s {@code EVALUATE TRUE} has <b>no</b>
         * {@code WHEN OTHER} branch, so an unrecognised {@code EIBAID} leaves {@code CCARD-AID} at the
         * {@code LOW-VALUES} the {@code INITIALIZE} of {@code app/cbl/COCRDSLC.cbl:254-256} left in it.
         * That unset value then satisfies neither test of the gate at {@code :292-293}, so the gate
         * coerces it to {@code ENTER}. Omitting this constant would make that reachable path
         * unreachable and hide a genuine behaviour.
         */
        UNMAPPED
    }

    /**
     * The pseudo-conversational entry mode, standing in for {@code CDEMO-PGM-CONTEXT PIC 9(1)} of
     * {@code app/cpy/COCOM01Y.cpy} and the two condition names declared on it.
     */
    public enum EntryMode {

        /** {@code 88 CDEMO-PGM-ENTER VALUE 0} - first entry into the transaction. */
        ENTER,

        /** {@code 88 CDEMO-PGM-REENTER VALUE 1} - a screen has been displayed and returned. */
        REENTER,

        /**
         * Any other value of the one-digit field, which no condition name covers and which therefore
         * reaches {@code WHEN OTHER} at {@code app/cbl/COCRDSLC.cbl:373}. Retained so that branch is
         * reachable from the public API rather than only in principle.
         */
        UNDEFINED
    }

    /**
     * The state of one filter field after editing - the three condition names declared on
     * {@code WS-EDIT-ACCT-FLAG} at {@code app/cbl/COCRDSLC.cbl:55-58} and, identically, on
     * {@code WS-EDIT-CARD-FLAG} at {@code :59-62}.
     *
     * <p>The flag is one byte wide and starts at {@code LOW-VALUES}, which matches none of the three
     * condition names. A state of {@code null} on the work area and on the response therefore means
     * exactly that: the field has not been edited, so no condition name is true. It is not a synonym for
     * any of the three values below and must never be collapsed into one.
     */
    public enum FilterState {

        /** {@code 88 FLG-ACCTFILTER-ISVALID VALUE '1'} - supplied, numeric and accepted. */
        OK,

        /**
         * {@code 88 FLG-ACCTFILTER-NOT-OK VALUE '0'} - the pessimistic default that
         * {@code app/cbl/COCRDSLC.cbl:648} and {@code :686} set before either gate runs, and the state a
         * non-numeric value or a failed read leaves behind.
         */
        NOT_OK,

        /**
         * {@code 88 FLG-ACCTFILTER-BLANK VALUE ' '} - nothing was supplied. This is the <b>only</b>
         * state that stamps {@code '*'} back into the field, and then only on re-entry
         * ({@code app/cbl/COCRDSLC.cbl:541-551}).
         */
        BLANK
    }

    /**
     * Everything the transaction reads on entry: the attention identifier, the pseudo-conversational
     * context, the commarea fields it may have been handed and the two map input fields.
     *
     * <p>It is a faithful inventory of the program's inputs rather than a convenience. {@code 0000-MAIN}
     * at {@code app/cbl/COCRDSLC.cbl:248-392} branches on {@code EIBAID}, on {@code EIBCALEN}, on
     * {@code CDEMO-PGM-CONTEXT} and on {@code CDEMO-FROM-PROGRAM}; {@code 1300-SETUP-SCREEN-ATTRS} at
     * {@code :505-512} additionally reads {@code CDEMO-LAST-MAPSET}; and the pre-validated arrival
     * branch at {@code :341-342} reads {@code CDEMO-ACCT-ID} and {@code CDEMO-CARD-NUM}. Every
     * component below is one of those, and there are no others.
     *
     * <p>Three static factories cover the three shapes a caller realistically has, so that the ten-way
     * canonical constructor is needed only when a test wants an unusual combination.
     *
     * @param attentionIdentifier the terminal attention identifier; {@code EIBAID}. Must not be
     *     {@code null}; pass {@link AttentionIdentifier#ENTER} when the caller has none.
     * @param commAreaPresent whether a commarea was passed; {@code EIBCALEN IS NOT EQUAL TO 0} at
     *     {@code app/cbl/COCRDSLC.cbl:268}.
     * @param entryMode the pseudo-conversational context; {@code CDEMO-PGM-CONTEXT}. Must not be
     *     {@code null}.
     * @param fromProgram the program that transferred control; {@code CDEMO-FROM-PROGRAM}. May be
     *     {@code null}, which models {@code LOW-VALUES}.
     * @param fromTransactionId the transaction that transferred control; {@code CDEMO-FROM-TRANID}. May
     *     be {@code null}.
     * @param lastMapset the mapset last displayed; {@code CDEMO-LAST-MAPSET}. May be {@code null}.
     * @param commAreaAccountId the account identifier carried in the commarea; {@code CDEMO-ACCT-ID
     *     PIC 9(11)}. May be {@code null}.
     * @param commAreaCardNumber the card number carried in the commarea; {@code CDEMO-CARD-NUM
     *     PIC 9(16)}. May be {@code null}. Never logged and never echoed into a diagnostic.
     * @param accountFilter the account filter the operator typed; {@code ACCTSIDI PIC X(11)} of
     *     {@code app/cpy-bms/COCRDSL.CPY:60}. May be {@code null} or {@code "*"}.
     * @param cardFilter the card filter the operator typed; {@code CARDSIDI PIC X(16)} of
     *     {@code app/cpy-bms/COCRDSL.CPY:66}. May be {@code null} or {@code "*"}.
     */
    public record CardDetailRequest(
            AttentionIdentifier attentionIdentifier,
            boolean commAreaPresent,
            EntryMode entryMode,
            String fromProgram,
            String fromTransactionId,
            String lastMapset,
            Long commAreaAccountId,
            String commAreaCardNumber,
            String accountFilter,
            String cardFilter) {

        /**
         * Validates the two components the dispatch cannot proceed without.
         *
         * @throws NullPointerException if {@code attentionIdentifier} or {@code entryMode} is
         *     {@code null}. Neither has a meaningful absent form: {@code EIBAID} and
         *     {@code CDEMO-PGM-CONTEXT} are always readable in the source, the unset cases being
         *     modelled by {@link AttentionIdentifier#UNMAPPED} and {@link EntryMode#UNDEFINED}.
         */
        public CardDetailRequest {
            Objects.requireNonNull(attentionIdentifier, "attentionIdentifier must not be null");
            Objects.requireNonNull(entryMode, "entryMode must not be null");
        }

        /**
         * The request that reaches the transaction on a bare first entry - no commarea, no filters.
         *
         * <p>It drives {@code WHEN CDEMO-PGM-ENTER} at {@code app/cbl/COCRDSLC.cbl:349-356}, the branch
         * that sends the empty prompt screen and gathers nothing.
         *
         * @return a first-entry request.
         */
        public static CardDetailRequest firstEntry() {
            return new CardDetailRequest(AttentionIdentifier.ENTER, false, EntryMode.ENTER,
                    null, null, null, null, null, null, null);
        }

        /**
         * The request that reaches the transaction when the operator selected a row on the card list.
         *
         * <p>It drives {@code WHEN CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM EQUAL LIT-CCLISTPGM} at
         * {@code app/cbl/COCRDSLC.cbl:339-348} - the branch that sets {@code INPUT-OK} outright and
         * skips {@code 2200-EDIT-MAP-INPUTS} because the selection was already validated upstream.
         *
         * @param accountId the account identifier from {@code CDEMO-ACCT-ID}.
         * @param cardNumber the card number from {@code CDEMO-CARD-NUM}.
         * @return a pre-validated arrival request.
         */
        public static CardDetailRequest fromCardList(final long accountId, final String cardNumber) {
            return new CardDetailRequest(AttentionIdentifier.ENTER, true, EntryMode.ENTER,
                    CARD_LIST_PROGRAM, null, CARD_LIST_MAPSET, accountId, cardNumber, null, null);
        }

        /**
         * The request that reaches the transaction when the operator typed filters and pressed enter.
         *
         * <p>It drives {@code WHEN CDEMO-PGM-REENTER} at {@code app/cbl/COCRDSLC.cbl:357-371} - validate
         * the filters, and read only if they pass.
         *
         * @param accountFilter the account filter; {@code null}, blank or {@code "*"} all mean absent.
         * @param cardFilter the card filter; {@code null}, blank or {@code "*"} all mean absent.
         * @return a re-entry request.
         */
        public static CardDetailRequest reentry(final String accountFilter, final String cardFilter) {
            return new CardDetailRequest(AttentionIdentifier.ENTER, true, EntryMode.REENTER,
                    null, null, null, null, null, accountFilter, cardFilter);
        }
    }

    /**
     * The screen the transaction would have sent, in place of the {@code EXEC CICS SEND MAP} that
     * {@code 1400-SEND-SCREEN} issues at {@code app/cbl/COCRDSLC.cbl:570-577}.
     *
     * <p>Only outcomes the source displays <i>without</i> an input error return one of these. When the
     * source displays a screen that carries an error message, this service performs the same
     * {@code 1000-SEND-MAP} chain and then raises the typed exception the migration mandates, so the
     * error text and the offending field name travel on the exception rather than on a response body.
     * That is a labelled deviation, severity <b>Low</b>: the information content is identical - the
     * {@code '*'} stamp and the cursor position encoded exactly which field was at fault - and the
     * remediation, if a caller ever needs the populated error screen instead, is to return this record
     * from the error paths rather than to change any of the edit logic.
     *
     * @param detail the projected card, or {@code null} when nothing was read - the prompt screen and
     *     the exit screen both carry no card.
     * @param informationMessage {@code WS-INFO-MSG PIC X(40)} of {@code app/cbl/COCRDSLC.cbl:126},
     *     padded to its declared forty bytes, or {@code null} when no condition name is set.
     * @param errorMessage {@code CCARD-ERROR-MSG PIC X(75)} of {@code app/cpy/CVCRD01Y.cpy:28}, or
     *     {@code null} when {@code WS-RETURN-MSG-OFF} still holds.
     * @param inputError {@code 88 INPUT-ERROR VALUE '1'} on {@code WS-INPUT-FLAG} at
     *     {@code app/cbl/COCRDSLC.cbl:53}.
     * @param accountFilterState the edited state of the account filter, or {@code null} when the flag is
     *     still {@code LOW-VALUES} because no edit ran.
     * @param cardFilterState the edited state of the card filter, or {@code null} on the same terms.
     * @param cursorField the name of the field {@code 1300-SETUP-SCREEN-ATTRS} would have positioned the
     *     cursor on ({@code app/cbl/COCRDSLC.cbl:515-524}), as a field <i>name</i> - never a value.
     * @param filtersProtected whether the two filter fields would have been protected, which
     *     {@code app/cbl/COCRDSLC.cbl:505-512} keys on having arrived from the card list.
     * @param navigationProgram {@code CDEMO-TO-PROGRAM} for the exit branch, or {@code null}. The
     *     {@code EXEC CICS XCTL} it fed has no Java equivalent; the name is reported so the caller can
     *     redirect.
     * @param navigationTransactionId {@code CDEMO-TO-TRANID} for the exit branch, or {@code null}.
     */
    public record CardDetailScreen(
            CardDto detail,
            String informationMessage,
            String errorMessage,
            boolean inputError,
            FilterState accountFilterState,
            FilterState cardFilterState,
            String cursorField,
            boolean filtersProtected,
            String navigationProgram,
            String navigationTransactionId) {
    }


    /**
     * Runs the transaction end to end for one request - the public face of
     * {@code 0000-MAIN} at {@code app/cbl/COCRDSLC.cbl:248-392}.
     *
     * <p><b>What it does.</b> Stores the passed data, maps the attention identifier, applies the
     * attention-identifier gate, dispatches on the resulting combination of key and entry mode, and
     * returns the screen the source would have sent. Which of the five dispatch arms runs is decided
     * exactly as {@code EVALUATE TRUE} at {@code :338-381} decides it.
     *
     * <p><b>What the {@code HANDLE ABEND} becomes.</b> {@code EXEC CICS HANDLE ABEND
     * LABEL(ABEND-ROUTINE)} at {@code :250-252} arms a handler for the whole run, and this method's
     * {@code try} is that arm. An outcome the migration models - any {@code CardDemoException} - is
     * rethrown unchanged, because the source reaches those through its own flow and not through the
     * abend handler. Anything else is funnelled into {@link #abendRoutine}.
     *
     * <p>The catch is on {@code RuntimeException} rather than on {@code Throwable}. Catching
     * {@code Throwable} would also intercept {@code Error} - a heap exhaustion or a linkage failure -
     * which the JVM must be allowed to propagate and which has no counterpart in the CICS abend model.
     * Labelled deviation, severity <b>Low</b>; remediation, if a caller ever needs the two treated
     * alike, is to widen this one catch and nothing else.
     *
     * @param request the transaction's inputs; must not be {@code null}.
     * @return the screen to display. Never {@code null}.
     * @throws NullPointerException if {@code request} is {@code null}.
     * @throws ValidationException if an edit rejected a filter, or if neither filter was supplied.
     * @throws RecordNotFoundException if the card is absent from {@code CARDDAT}.
     * @throws FileAccessException if the read failed for any reason other than not-found.
     * @throws FatalProcessingException on the unexpected-data dispatch arm, or when an unmodelled
     *     runtime failure reaches the abend handler.
     */
    public CardDetailScreen submitScreen(final CardDetailRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            return main0000(request);
        } catch (final CardDemoException modelled) {
            throw modelled;
        } catch (final RuntimeException unexpected) {
            throw abendRoutine(unexpected);
        }
    }

    /**
     * Retrieves one card's details from a typed account filter and card filter - the re-entry path of
     * {@code WHEN CDEMO-PGM-REENTER} at {@code app/cbl/COCRDSLC.cbl:357-371}, which validates first and
     * reads only if the validation passed.
     *
     * <p>This is the primary retrieval entry point. It is a thin, documented convenience over
     * {@link #submitScreen(CardDetailRequest)} with {@link CardDetailRequest#reentry}: every edit, every
     * message and every exception is the dispatch's, not this method's.
     *
     * @param accountFilter the account filter as typed. {@code null}, blank and {@code "*"} are all
     *     "not supplied" and all reject with {@code Account number not provided}.
     * @param cardFilter the card filter as typed, on the same terms.
     * @return the populated projection. Never {@code null}, because every outcome other than a
     *     successful read raises an exception before returning.
     * @throws ValidationException if either filter is absent or not exactly the required number of
     *     digits, or if both are absent.
     * @throws RecordNotFoundException if no card carries that number.
     * @throws FileAccessException if the read failed.
     * @throws FatalProcessingException if an unmodelled runtime failure reached the abend handler.
     */
    public CardDto viewCardDetail(final String accountFilter, final String cardFilter) {
        return submitScreen(CardDetailRequest.reentry(accountFilter, cardFilter)).detail();
    }

    /**
     * Retrieves one card's details for a row the operator already selected on the card list - the
     * pre-validated arrival path of {@code app/cbl/COCRDSLC.cbl:339-348}.
     *
     * <p><b>The edits are skipped, deliberately.</b> That branch opens with {@code SET INPUT-OK TO TRUE}
     * at {@code :341} and moves the two identifiers straight out of the commarea into the work area
     * before performing {@code 9000-READ-DATA}; {@code 2200-EDIT-MAP-INPUTS} never runs, because the
     * card list validated the selection before transferring control. The behaviour is therefore
     * genuinely different from {@link #viewCardDetail} and is exposed as a separate operation rather
     * than as a flag, which is also why this class does not - and must not - call
     * {@code CardListService}: the legacy transfer is {@code EXEC CICS XCTL}, which terminates the
     * caller, and the coupling runs both ways ({@code app/cbl/COCRDLIC.cbl:538} dispatches into this
     * program), so an injection either way would be a circular dependency.
     *
     * @param accountId the account identifier the list carried in {@code CDEMO-ACCT-ID}.
     * @param cardNumber the card number the list carried in {@code CDEMO-CARD-NUM}; must not be
     *     {@code null}. Never logged and never echoed into any diagnostic.
     * @return the populated projection. Never {@code null}.
     * @throws NullPointerException if {@code cardNumber} is {@code null}.
     * @throws RecordNotFoundException if the selected card has since been removed.
     * @throws FileAccessException if the read failed.
     * @throws FatalProcessingException if an unmodelled runtime failure reached the abend handler.
     */
    public CardDto viewSelectedCard(final long accountId, final String cardNumber) {
        Objects.requireNonNull(cardNumber, "cardNumber must not be null");
        return submitScreen(CardDetailRequest.fromCardList(accountId, cardNumber)).detail();
    }

    /**
     * Produces the empty prompt screen the transaction shows on a bare first entry - the branch at
     * {@code app/cbl/COCRDSLC.cbl:349-356}, which gathers the selection criteria rather than acting on
     * them.
     *
     * <p>It sends the map and nothing else: no edit runs, no read is issued and no exception is raised.
     * The information message is {@code Please enter Account and Card Number}, the condition name on
     * {@code WS-INFO-MSG} at {@code :131-132}.
     *
     * @return the prompt screen, whose {@code detail} is {@code null} because no card was read.
     */
    public CardDetailScreen openWithoutContext() {
        return submitScreen(CardDetailRequest.firstEntry());
    }


    /**
     * The mainline dispatch.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 0000-MAIN.} at line 248.
     *
     * <p>Transcribed in source order. {@code EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)} at
     * {@code :250-252} is the {@code try} in {@link #submitScreen(CardDetailRequest)}, which is the only
     * part of this paragraph that cannot live inside the method itself. Everything from {@code :254} to
     * {@code :391} is below, including the two blocks that carry no label of their own - the
     * store-passed-data test at {@code :268-279} and the exit branch's body at {@code :306-336} - which
     * stay inline here because the one-to-one mandate maps <i>labels</i> to methods and those blocks
     * have none.
     *
     * <p><b>{@code EVALUATE TRUE} becomes an {@code if}/{@code else if} chain, not a {@code switch}.</b>
     * The five {@code WHEN} conditions are independent predicates over three different variables rather
     * than cases of one selector, which is exactly what {@code EVALUATE TRUE} means. A {@code switch}
     * cannot express that; the chain can, and it preserves the source's evaluation order, which matters
     * because the pre-validated arrival test at {@code :339-340} is a strictly narrower case of the
     * prompt test at {@code :349} and must therefore be tried first.
     *
     * @param request the transaction's inputs.
     * @return the screen to display.
     */
    private CardDetailScreen main0000(final CardDetailRequest request) {
        // :254-256  INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA.  Every flag, filter, message
        // and record image the paragraph touches is local to this call and dies with it.  Note what the
        // statement does not name: ABEND-DATA is absent from it, which is the root of the parity trap
        // documented on abendRoutine().
        final ScreenWorkArea work = new ScreenWorkArea();

        // The two values the operator typed stand in for the terminal input buffer that
        // EXEC CICS RECEIVE MAP reads at :597-602.  They are held unaltered here and are moved into the
        // symbolic input map - with the truncation a COBOL move performs - by receiveMap2100, so that the
        // one paragraph responsible for reading the screen remains the only place that reads it.
        work.rawAccountFilter = request.accountFilter();
        work.rawCardFilter = request.cardFilter();

        // :260  MOVE LIT-THISTRANID TO WS-TRANID.
        work.transactionId = THIS_TRANSACTION_ID;

        // :264  SET WS-RETURN-MSG-OFF TO TRUE.  The condition name carries VALUE SPACES, so this clears
        // the message.  It is what arms first-error-wins: every later write is guarded by a test of this
        // same condition, so the first message to land is the one the operator sees.
        work.returnMessage = null;

        // :268-279  Store passed data if any.  Either the commarea is adopted wholesale or it is
        // INITIALIZEd, and the reset is not merely a matter of clearing identifiers: CDEMO-PGM-CONTEXT is
        // PIC 9(1), so INITIALIZE drives it to zero and therefore to CDEMO-PGM-ENTER.  A caller that
        // passes REENTER with no commarea is silently treated as a first entry.
        work.commAreaPresent = request.commAreaPresent();
        if (!request.commAreaPresent()
                || (MENU_PROGRAM.equals(request.fromProgram()) && request.entryMode() != EntryMode.REENTER)) {
            work.entryMode = EntryMode.ENTER;
            work.fromProgram = null;
            work.fromTransactionId = null;
            work.lastMapset = null;
            work.commAreaAccountId = 0L;
            work.commAreaCardNumber = 0L;
        } else {
            work.entryMode = request.entryMode();
            work.fromProgram = request.fromProgram();
            work.fromTransactionId = request.fromTransactionId();
            work.lastMapset = request.lastMapset();
            work.commAreaAccountId = request.commAreaAccountId() == null ? 0L : request.commAreaAccountId();
            work.commAreaCardNumber = parseCommAreaCardNumber(request.commAreaCardNumber());
        }

        // :284-285  PERFORM YYYY-STORE-PFKEY THRU YYYY-STORE-PFKEY-EXIT.
        storePfKeyYyyy(work, request.attentionIdentifier());
        storePfKeyExitYyyy();

        // :291-299  Check the AID to see if it is valid at this point.  Only ENTER and PF03 are admitted,
        // and then - this is the quirk - an inadmissible key is not rejected but SILENTLY COERCED to
        // ENTER.  Pressing PF7 on this screen therefore redisplays it rather than reporting anything, and
        // no validation error is raised for the key.  Preserved exactly; severity Low, documented only.
        work.pfkInvalid = true;
        if (CCARD_AID_ENTER.equals(work.ccardAid) || CCARD_AID_PFK03.equals(work.ccardAid)) {
            work.pfkInvalid = false;
        }
        if (work.pfkInvalid) {
            work.ccardAid = CCARD_AID_ENTER;
        }

        // :338-381  EVALUATE TRUE - decide what to do based on inputs received.
        if (CCARD_AID_PFK03.equals(work.ccardAid)) {
            // :305-336  XCTL to the calling program or the main menu.  The transfer itself has no Java
            // counterpart, so the two resolved targets are reported on the response and the caller
            // redirects; the identity moves at :325-326 and the map provenance at :328-329 are commarea
            // bookkeeping for a mechanism that no longer exists and are documented rather than carried.
            if (isUnsetOrSpaces(work.fromTransactionId)) {
                work.toTransactionId = MENU_TRANSACTION_ID;
            } else {
                work.toTransactionId = work.fromTransactionId;
            }
            if (isUnsetOrSpaces(work.fromProgram)) {
                work.toProgram = MENU_PROGRAM;
            } else {
                work.toProgram = work.fromProgram;
            }
            work.entryMode = EntryMode.ENTER;
            return deliver(work, commonReturn(work));
        } else if (work.entryMode == EntryMode.ENTER && CARD_LIST_PROGRAM.equals(work.fromProgram)) {
            // :339-348  Coming from the credit card list screen; the selection criteria were already
            // validated there, so INPUT-OK is set outright and 2200-EDIT-MAP-INPUTS never runs.
            work.inputError = Boolean.FALSE;
            work.ccAcctId = renderZeroPadded(work.commAreaAccountId, ACCOUNT_ID_WIDTH);
            work.ccCardNum = renderZeroPadded(work.commAreaCardNumber, CARD_NUMBER_WIDTH);
            readData9000(work);
            readDataExit9000();
            sendMap1000(work);
            sendMapExit1000();
            return deliver(work, commonReturn(work));
        } else if (work.entryMode == EntryMode.ENTER) {
            // :349-356  Coming from some other context; the selection criteria are still to be gathered,
            // so the map is sent empty and nothing is read.
            sendMap1000(work);
            sendMapExit1000();
            return deliver(work, commonReturn(work));
        } else if (work.entryMode == EntryMode.REENTER) {
            // :357-371  The primary path: process the inputs, and read only if they survived the edits.
            processInputs2000(work);
            processInputsExit2000();
            if (Boolean.TRUE.equals(work.inputError)) {
                sendMap1000(work);
                sendMapExit1000();
                return deliver(work, commonReturn(work));
            } else {
                readData9000(work);
                readDataExit9000();
                sendMap1000(work);
                sendMapExit1000();
                return deliver(work, commonReturn(work));
            }
        } else {
            // :373-380  WHEN OTHER.  Note that this arm does not raise a CICS abend: it populates the
            // abend payload, sets the message and sends plain text, which issues EXEC CICS RETURN.
            work.abendCulprit = THIS_PROGRAM;
            work.abendCode = UNEXPECTED_DATA_ABEND_CODE;
            work.abendReason = ABEND_REASON_SPACES;
            work.returnMessage = UNEXPECTED_DATA_SCENARIO_MESSAGE;
            sendPlainText(work);
            sendPlainTextExit();
        }

        // :386-391  "If we had an error setup error message that slipped through - display and return."
        // A second, redundant INPUT-ERROR gate: every arm of the EVALUATE above either transfers to
        // COMMON-RETURN or issues EXEC CICS RETURN from SEND-PLAIN-TEXT, so nothing can reach here with
        // the flag set.  Retained as written rather than optimised away, per the parity mandate; severity
        // Low, tracked, and reachable in Java only because sendPlainText's throw is invisible to the
        // compiler's flow analysis.
        if (Boolean.TRUE.equals(work.inputError)) {
            work.errorMessage = work.returnMessage;
            sendMap1000(work);
            sendMapExit1000();
            return deliver(work, commonReturn(work));
        }
        return deliver(work, commonReturn(work));
    }

    /**
     * Packs the outcome and hands control back to CICS.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code COMMON-RETURN.} at line 394.
     *
     * <p>{@code :395} moves {@code WS-RETURN-MSG} into {@code CCARD-ERROR-MSG}, which is why an error
     * message reaches the screen even on the arms that never touched {@code CCARD-ERROR-MSG} themselves.
     * {@code :397-400} then concatenates the shared commarea and this program's own commarea into one
     * buffer and {@code :402-406} issues {@code EXEC CICS RETURN TRANSID('CCDL') COMMAREA(WS-COMMAREA)}.
     *
     * <p><b>The return itself has no Java equivalent.</b> {@code RETURN TRANSID} tells CICS which
     * transaction should receive the next terminal input and hands it a buffer to carry the conversation
     * forward; under transformation rule 7 the target is stateless and holds no server-side session, so
     * there is nothing to carry and nothing to schedule. The response object is returned instead.
     *
     * <p>{@code 0000-MAIN-EXIT} is invoked from here because the source reaches it by fall-through from
     * this paragraph. In the source that fall-through never happens - {@code EXEC CICS RETURN} ends the
     * task first - so calling it here is a labelled structural deviation, severity <b>Low</b>: with the
     * return gone there is nothing left to stop the flow, and the alternative would be a paragraph in the
     * map with no call site at all.
     *
     * @param work the request-scoped working storage.
     * @return the assembled response.
     */
    private CardDetailScreen commonReturn(final ScreenWorkArea work) {
        // :395  MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG.
        work.errorMessage = work.returnMessage;

        final CardDetailScreen screen = new CardDetailScreen(
                work.detail,
                work.infoMessage,
                work.errorMessage,
                Boolean.TRUE.equals(work.inputError),
                work.accountFilterState,
                work.cardFilterState,
                work.cursorField,
                work.filtersProtected,
                work.toProgram,
                work.toTransactionId);

        mainExit0000();
        return screen;
    }

    /**
     * The mainline's structural endpoint.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 0000-MAIN-EXIT.} at line 408, whose body
     * is the single statement {@code EXIT} at {@code :409}.
     *
     * <p>{@code EXIT} is a no-operation in COBOL - it exists to give a {@code PERFORM ... THRU} range a
     * landing point - so an empty body is the faithful translation and not an omission. It is called from
     * {@link #commonReturn} for the reason recorded there.
     */
    private void mainExit0000() {
        // EXIT.  COBOL's no-operation; there is nothing to translate.
    }

    /**
     * Builds and sends the screen.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 1000-SEND-MAP.} at line 412.
     *
     * <p>Four {@code PERFORM ... THRU} ranges in fixed order ({@code :413-420}): initialise the map,
     * populate its variables, set its attributes, send it. The order is load-bearing - the attribute pass
     * at {@code :502} reads the entry mode that the send at {@code :567} then changes, so swapping the
     * last two would lose the {@code '*'} stamp on every re-entry.
     *
     * @param work the request-scoped working storage.
     */
    private void sendMap1000(final ScreenWorkArea work) {
        screenInit1100(work);
        screenInitExit1100();
        setupScreenVars1200(work);
        setupScreenVarsExit1200();
        setupScreenAttrs1300(work);
        setupScreenAttrsExit1300();
        sendScreen1400(work);
        sendScreenExit1400();
    }

    /**
     * The send-map range's landing point.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 1000-SEND-MAP-EXIT.} at line 423, body
     * {@code EXIT} at {@code :424}.
     */
    private void sendMapExit1000() {
        // EXIT.
    }

    /**
     * Clears the output map and stamps the six header fields every CardDemo screen carries.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 1100-SCREEN-INIT.} at line 427.
     *
     * <p>{@code :428} moves {@code LOW-VALUES} across the whole symbolic output map, which is how BMS is
     * told to transmit nothing for a field; every output field is therefore reset to unset here rather
     * than to blank, and the two are not interchangeable.
     *
     * <p><b>A redundant duplicate is preserved.</b> {@code MOVE FUNCTION CURRENT-DATE TO
     * WS-CURDATE-DATA} appears twice, at {@code :430} and again at {@code :437}, with only literal moves
     * between them. The first read is therefore overwritten before anything consumes it. Both reads are
     * kept, which also keeps the source's own latent hazard: the two calls can straddle a second
     * boundary, and it is the second that reaches the screen. Severity <b>Low</b>, tracked; remediation
     * would be to delete the first statement, which parity forbids.
     *
     * @param work the request-scoped working storage.
     */
    private void screenInit1100(final ScreenWorkArea work) {
        // :428  MOVE LOW-VALUES TO CCRDSLAO.
        work.clearOutputMap();

        // :430  MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA - the first, redundant read.
        work.currentDateData = LocalDateTime.now(clock);

        // :432-435  Titles from app/cpy/COTTL01Y.cpy, then this transaction's own identity.
        work.title01Out = SCREEN_TITLE_01;
        work.title02Out = SCREEN_TITLE_02;
        work.transactionNameOut = THIS_TRANSACTION_ID;
        work.programNameOut = THIS_PROGRAM;

        // :437  MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA - the second read, the one that counts.
        work.currentDateData = LocalDateTime.now(clock);

        // :439-443  WS-CURDATE-MONTH, -DAY and -YEAR(3:2) assembled into WS-CURDATE-MM-DD-YY, the
        // MM/DD/YY layout of app/cpy/CSDAT01Y.cpy:30-35.  The year is the last two digits of four.
        work.currentDateOut = renderHeaderDate(work.currentDateData);

        // :445-449  WS-CURTIME-HOURS, -MINUTE and -SECOND assembled into WS-CURTIME-HH-MM-SS, the
        // HH:MM:SS layout of app/cpy/CSDAT01Y.cpy:36-41.
        work.currentTimeOut = renderHeaderTime(work.currentDateData);
    }

    /**
     * The screen-initialise range's landing point.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 1100-SCREEN-INIT-EXIT.} at line 453, body
     * {@code EXIT} at {@code :454}.
     */
    private void screenInitExit1100() {
        // EXIT.
    }


    /**
     * Populates the map's variable fields - the echoed filters, the card projection and the two messages.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 1200-SETUP-SCREEN-VARS.} at line 457.
     *
     * <p><b>The card projection is the whole of the entity-to-DTO mapping</b> and it is deliberately
     * narrow. {@code :474-485} moves exactly four things: the embossed name, the expiry month, the expiry
     * year and the active status.
     *
     * <p><b>Two fields are conspicuously absent and must stay absent.</b> The card verification value is
     * never moved to the screen anywhere in the member, and neither is the expiry <i>day</i>, even though
     * {@code :90-92} takes the trouble to extract it. {@code app/cpy-bms/COCRDSL.CPY} confirms the
     * omission from the other side: it contains no {@code EXPDAY} field and no verification-value field
     * at all. Severity <b>Blocker</b> for hygiene: the verification value is not read from the entity,
     * not projected, not logged and not named in any diagnostic, and no expiry-day property is invented -
     * not even a null one.
     *
     * <p>The expiry decomposition redefines the ten-byte date as year at offset 1 for four bytes, a
     * separator, month at offset 6 for two, a separator, and day at offset 9 for two
     * ({@code app/cbl/COCRDSLC.cbl:84-92}) - that is, dash-separated {@code yyyy-MM-dd} handled purely as
     * text. {@code app/cbl/COCRDUPC.cbl} decomposes the same field at the same offsets, so the layout is a
     * package-wide contract; it is nonetheless restated here rather than extracted, because a shared
     * helper across these three programs would sit alongside edits whose semantics genuinely differ.
     *
     * <p><b>The information message and the found-cards flag are one and the same field.</b>
     * {@code 88 FOUND-CARDS-FOR-ACCOUNT} at {@code :129-130} is a condition name declared on
     * {@code WS-INFO-MSG PIC X(40)}, so {@code SET FOUND-CARDS-FOR-ACCOUNT TO TRUE} moves the literal
     * {@code '   Displaying requested details'} - three leading spaces - into the message, and
     * {@code IF FOUND-CARDS-FOR-ACCOUNT} is a comparison against that literal. There is no separate
     * boolean to model, and inventing one would let the two drift apart. That is also why {@code :490-492}
     * behaves as it does: on any path that did not reach a successful read the message is still blank, so
     * the prompt text is substituted, and an error screen therefore carries the prompt as its information
     * message alongside the error text.
     *
     * @param work the request-scoped working storage.
     */
    private void setupScreenVars1200(final ScreenWorkArea work) {
        // :459-461  INITIALIZE SEARCH CRITERIA.  With no commarea there is nothing to echo at all.
        if (!work.commAreaPresent) {
            work.infoMessage = PROMPT_FOR_INPUT_MESSAGE;
        } else {
            // :462-466  The test is on the numeric commarea field but the value moved is the work area's
            // alphanumeric one.  Both edits drive CDEMO-ACCT-ID to zero when they reject, so a rejected
            // filter echoes as LOW-VALUES here and is then stamped with '*' by 1300 if it was blank.
            if (work.commAreaAccountId == 0L) {
                work.accountFilterOut = null;
            } else {
                work.accountFilterOut = work.ccAcctId;
            }

            // :468-472  The card filter, on identical terms.
            if (work.commAreaCardNumber == 0L) {
                work.cardFilterOut = null;
            } else {
                work.cardFilterOut = work.ccCardNum;
            }

            // :474-485  The card projection.  Four moves, no more.
            if (work.foundCardsForAccount()) {
                work.cardholderNameOut = work.cardRecord.getEmbossedName();
                work.cardExpirationDateX = padRight(work.cardRecord.getExpiraionDate(), EXPIRY_DATE_WIDTH);
                work.expiryMonthOut = extractExpiryMonth(work.cardExpirationDateX);
                work.expiryYearOut = extractExpiryYear(work.cardExpirationDateX);
                work.cardStatusOut = work.cardRecord.getActiveStatus();
            }
        }

        // :490-492  SETUP MESSAGE.
        if (work.noInfoMessage()) {
            work.infoMessage = PROMPT_FOR_INPUT_MESSAGE;
        }

        // :494  MOVE WS-RETURN-MSG TO ERRMSGO.
        work.errorMessageOut = work.returnMessage;

        // :496  MOVE WS-INFO-MSG TO INFOMSGO.  The receiving field is X(40), so the value is padded to
        // its declared width exactly as the move would pad it.
        work.infoMessageOut = padRight(work.infoMessage, INFO_MESSAGE_WIDTH);
    }

    /**
     * The screen-variables range's landing point.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 1200-SETUP-SCREEN-VARS-EXIT.} at line 499,
     * body {@code EXIT} at {@code :500}.
     */
    private void setupScreenVarsExit1200() {
        // EXIT.
    }

    /**
     * Sets the map's attributes - protection, cursor position, colour and the blank-filter marker.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 1300-SETUP-SCREEN-ATTRS.} at line 502.
     *
     * <p><b>The cursor becomes a field name.</b> {@code :515-524} moves {@code -1} into one field's
     * length attribute, which is how BMS is told where to leave the cursor. A stateless JSON response has
     * no cursor, so the <i>name</i> of the offending field is reported instead - and only the name. The
     * value is never carried, because these two fields hold an account identifier and a card number and
     * Rule 1 clause D admits no disclosure of either into a diagnostic.
     *
     * <p>Note that the first and third arms of the {@code EVALUATE} have the same body: an account-filter
     * problem and no problem at all both leave the cursor on the account field. They are kept as separate
     * branches because the source declares them separately and collapsing them would misrepresent a
     * three-way decision as a two-way one.
     *
     * <p><b>The three-state marker.</b> {@code :541-551} stamps {@code '*'} into a filter field when, and
     * only when, that filter is {@code BLANK} <i>and</i> this is a re-entry. The {@code NOT-OK} state
     * never stamps, and neither state stamps on first entry. That is the model
     * {@code app/cpy/CSSETATY.cpy} describes, and it is why {@link FilterState} has three values and why
     * {@code null} - no condition name true - is a fourth, distinct condition that must not be folded
     * into any of them.
     *
     * <p>The colour moves at {@code :527-557} - {@code DFHDFCOL}, {@code DFHRED}, {@code DFHBMDAR} and
     * {@code DFHNEUTR} - and the protection attributes {@code DFHBMPRF} and {@code DFHBMFSE} at
     * {@code :507-511} come from CICS-supplied copybooks that are not in this repository and describe
     * 3270 field attribute bytes. They have <b>no</b> Java counterpart and nothing is emitted for them;
     * the protection decision alone is reported, because whether the filters were protected is behaviour
     * rather than presentation.
     *
     * @param work the request-scoped working storage.
     */
    private void setupScreenAttrs1300(final ScreenWorkArea work) {
        // :505-512  PROTECT OR UNPROTECT BASED ON CONTEXT.  The filters are protected when the operator
        // arrived from the card list, because the selection is then not theirs to change.
        work.filtersProtected = CARD_LIST_MAPSET.equals(work.lastMapset)
                && CARD_LIST_PROGRAM.equals(work.fromProgram);

        // :515-524  POSITION CURSOR.
        if (work.accountFilterState == FilterState.NOT_OK || work.accountFilterState == FilterState.BLANK) {
            work.cursorField = FIELD_ACCOUNT_ID;
        } else if (work.cardFilterState == FilterState.NOT_OK || work.cardFilterState == FilterState.BLANK) {
            work.cursorField = FIELD_CARD_NUMBER;
        } else {
            work.cursorField = FIELD_ACCOUNT_ID;
        }

        // :541-545  The account filter's blank marker.  This is a data move, not an attribute, which is
        // why it survives into the target while the colour move beside it does not.
        if (work.accountFilterState == FilterState.BLANK && work.entryMode == EntryMode.REENTER) {
            work.accountFilterOut = BLANK_FILTER_MARKER;
        }

        // :547-551  The card filter's blank marker, on identical terms.
        if (work.cardFilterState == FilterState.BLANK && work.entryMode == EntryMode.REENTER) {
            work.cardFilterOut = BLANK_FILTER_MARKER;
        }
    }

    /**
     * The screen-attributes range's landing point.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 1300-SETUP-SCREEN-ATTRS-EXIT.} at line 559,
     * body {@code EXIT} at {@code :560}.
     */
    private void setupScreenAttrsExit1300() {
        // EXIT.
    }

    /**
     * Transmits the assembled map.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 1400-SEND-SCREEN.} at line 563.
     *
     * <p>{@code SET CDEMO-PGM-REENTER TO TRUE} at {@code :567} is the pseudo-conversational flag: it tells
     * the next invocation that a screen has already been displayed, which is the mechanism transformation
     * rule 7 replaces outright with stateless request handling. The assignment is still performed, because
     * it is a genuine mutation of state the paragraph before this one reads, and because dropping it would
     * make the ordering constraint recorded on {@link #sendMap1000} invisible.
     *
     * <p>{@code EXEC CICS SEND MAP ... CURSOR ERASE FREEKB} at {@code :569-576} has no Java counterpart -
     * there is no 3270 datastream to write and no keyboard to unlock. Assembling the projection is the
     * faithful analogue, and it happens here rather than earlier because {@code 1300} may still have
     * stamped a filter field after {@code 1200} populated it.
     *
     * @param work the request-scoped working storage.
     */
    private void sendScreen1400(final ScreenWorkArea work) {
        // :565-566  MOVE LIT-THISMAPSET / LIT-THISMAP TO CCARD-NEXT-MAPSET / CCARD-NEXT-MAP.
        work.nextMapset = THIS_MAPSET;
        work.nextMap = THIS_MAP;

        // :567  SET CDEMO-PGM-REENTER TO TRUE.
        work.entryMode = EntryMode.REENTER;

        // :569-576  EXEC CICS SEND MAP.
        work.detail = CardDto.detail(
                work.transactionNameOut,
                work.title01Out,
                work.currentDateOut,
                work.programNameOut,
                work.title02Out,
                work.currentTimeOut,
                work.accountFilterOut,
                work.cardFilterOut,
                work.cardholderNameOut,
                work.cardStatusOut,
                work.expiryMonthOut,
                work.expiryYearOut,
                work.infoMessageOut,
                work.errorMessageOut,
                work.functionKeysOut);
    }

    /**
     * The send-screen range's landing point.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 1400-SEND-SCREEN-EXIT.} at line 578, body
     * {@code EXIT} at {@code :579}.
     */
    private void sendScreenExit1400() {
        // EXIT.
    }

    /**
     * Receives and edits the operator's input.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 2000-PROCESS-INPUTS.} at line 582.
     *
     * <p>Two ranges then four moves ({@code :583-590}). The first move copies the accumulated message into
     * {@code CCARD-ERROR-MSG}. The remaining three - {@code CCARD-NEXT-PROG}, {@code CCARD-NEXT-MAPSET}
     * and {@code CCARD-NEXT-MAP} - are navigation state for the {@code XCTL}-and-{@code SEND MAP}
     * mechanism and have <b>no</b> Java counterpart, because routing is URL-based; the two map names are
     * recorded on the working storage for provenance and the program name is this program's own.
     *
     * @param work the request-scoped working storage.
     */
    private void processInputs2000(final ScreenWorkArea work) {
        receiveMap2100(work);
        receiveMapExit2100();
        editMapInputs2200(work);
        editMapInputsExit2200();

        // :587  MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG.
        work.errorMessage = work.returnMessage;

        // :588-590  MOVE LIT-THISPGM / LIT-THISMAPSET / LIT-THISMAP TO the CCARD-NEXT-* fields.
        work.nextProgram = THIS_PROGRAM;
        work.nextMapset = THIS_MAPSET;
        work.nextMap = THIS_MAP;
    }

    /**
     * The process-inputs range's landing point.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 2000-PROCESS-INPUTS-EXIT.} at line 593,
     * body {@code EXIT} at {@code :594}.
     */
    private void processInputsExit2000() {
        // EXIT.
    }

    /**
     * Reads the operator's input into the symbolic input map.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 2100-RECEIVE-MAP.} at line 596.
     *
     * <p>{@code EXEC CICS RECEIVE MAP ... INTO(CCRDSLAI)} at {@code :597-602} moves the inbound 3270
     * datastream into the seventeen fields of {@code app/cpy-bms/COCRDSL.CPY}. In the target the two input
     * fields arrive already deserialised on the request, so the faithful residue of this paragraph is the
     * <b>move semantics</b>: {@code ACCTSIDI} is {@code PIC X(11)} and {@code CARDSIDI} is
     * {@code PIC X(16)}, and a COBOL move into a fixed-width alphanumeric field truncates on the right.
     * Applying that here rather than letting an over-long value travel onward is what keeps the width
     * contract of {@code CardDto} satisfied and reproduces what the terminal could physically have sent.
     *
     * <p>The {@code RESP} and {@code RESP2} values the command returns are captured by the source at
     * {@code :600-601} and then never tested, so nothing is derived from them.
     *
     * @param work the request-scoped working storage.
     */
    private void receiveMap2100(final ScreenWorkArea work) {
        work.accountFilterIn = truncate(work.rawAccountFilter, ACCOUNT_ID_WIDTH);
        work.cardFilterIn = truncate(work.rawCardFilter, CARD_NUMBER_WIDTH);
    }

    /**
     * The receive-map range's landing point.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 2100-RECEIVE-MAP-EXIT.} at line 605, body
     * {@code EXIT} at {@code :606}.
     */
    private void receiveMapExit2100() {
        // EXIT.
    }

    /**
     * Edits the two filter fields, individually and then against each other.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 2200-EDIT-MAP-INPUTS.} at line 608.
     *
     * <p><b>An asterisk means "not supplied".</b> {@code :615-620} and {@code :622-627} test each inbound
     * field against {@code '*'} <i>or</i> {@code SPACES} and move {@code LOW-VALUES} into the work area for
     * either, so a lone asterisk is indistinguishable from a blank field from this point on. This is the
     * input half of the marker that {@code 1300} writes back on re-entry, and treating the asterisk as a
     * literal search value instead would diverge on the very first request that used the screen as
     * designed.
     *
     * <p><b>The cross-field edit is unguarded.</b> {@code :637-640} sets the no-criteria message whenever
     * both filters came back blank, with no test of whether a message is already present - unlike every
     * per-field message in this program. So when nothing at all was supplied, {@code No input received}
     * overwrites the {@code Account number not provided} that {@code 2210} had already set. The condition
     * that ultimately reported the failure is tracked alongside the message so the boundary can raise the
     * matching exception, but the message text is the source's.
     *
     * @param work the request-scoped working storage.
     */
    private void editMapInputs2200(final ScreenWorkArea work) {
        // :610-612  Optimistic defaults for the two filters, and no error yet.
        work.inputError = Boolean.FALSE;
        work.cardFilterState = FilterState.OK;
        work.accountFilterState = FilterState.OK;

        // :615-620  REPLACE * WITH LOW-VALUES - the account filter.
        if (BLANK_FILTER_MARKER.equals(work.accountFilterIn) || isUnsetOrSpaces(work.accountFilterIn)) {
            work.ccAcctId = null;
        } else {
            work.ccAcctId = padRight(work.accountFilterIn, ACCOUNT_ID_WIDTH);
        }

        // :622-627  REPLACE * WITH LOW-VALUES - the card filter.
        if (BLANK_FILTER_MARKER.equals(work.cardFilterIn) || isUnsetOrSpaces(work.cardFilterIn)) {
            work.ccCardNum = null;
        } else {
            work.ccCardNum = padRight(work.cardFilterIn, CARD_NUMBER_WIDTH);
        }

        // :630-634  INDIVIDUAL FIELD EDITS, in this order.
        editAccount2210(work);
        editAccountExit2210();
        editCard2220(work);
        editCardExit2220();

        // :637-640  CROSS FIELD EDITS.
        if (work.accountFilterState == FilterState.BLANK && work.cardFilterState == FilterState.BLANK) {
            work.returnMessage = NO_INPUT_RECEIVED_MESSAGE;
            work.failingField = null;
            work.failingKind = null;
        }
    }

    /**
     * The edit-map-inputs range's landing point.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 2200-EDIT-MAP-INPUTS-EXIT.} at line 643,
     * body {@code EXIT} at {@code :644}.
     */
    private void editMapInputsExit2200() {
        // EXIT.
    }

    /**
     * Edits the account filter field.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 2210-EDIT-ACCOUNT.} at line 647.
     *
     * <p>A pessimistic default at {@code :648} followed by two gates, each ending in
     * {@code GO TO 2210-EDIT-ACCOUNT-EXIT}. Both messages are guarded by {@code IF WS-RETURN-MSG-OFF}
     * ({@code :656} and {@code :668}), which together with the same guard in {@code 2220} produces
     * first-error-wins across the pair: a blank account followed by a non-numeric card reports the
     * account, not the card.
     *
     * <p><b>The not-supplied gate has three alternatives</b> ({@code :651-653}): {@code LOW-VALUES},
     * {@code SPACES}, and the numeric redefinition equal to {@code ZEROS}. The third compares a
     * {@code PIC 9(11)} view of storage that may be holding {@code LOW-VALUES}, which is not a comparison
     * a portable implementation can reproduce literally; what is reproduced is its <i>outcome</i>, namely
     * that unset, blank, an asterisk and all-zeros are alike "not supplied". No further guard is added.
     *
     * <p><b>The numeric gate means "exactly eleven digits".</b> {@code CC-ACCT-ID} is {@code PIC X(11)},
     * so a shorter value moved into it is padded on the right with spaces, and spaces are not digits;
     * {@code IF CC-ACCT-ID IS NOT NUMERIC} therefore rejects anything that is not eleven digits exactly,
     * which is what the message at {@code :670} says in as many words. The comment above the test at
     * {@code :663-664} confirms the intent.
     *
     * <p>The message is the inline uppercase literal at {@code :670}, not either of the two mixed-case
     * condition names declared for the purpose at {@code :144-147}. Those are dead; see the class
     * documentation.
     *
     * @param work the request-scoped working storage.
     */
    private void editAccount2210(final ScreenWorkArea work) {
        // :648  SET FLG-ACCTFILTER-NOT-OK TO TRUE - pessimistic until proven otherwise.
        work.accountFilterState = FilterState.NOT_OK;

        // :651-661  Not supplied.
        if (isNotSupplied(work.ccAcctId, ACCOUNT_ID_LOW_VALUES, ACCOUNT_ID_SPACES)) {
            work.inputError = Boolean.TRUE;
            work.accountFilterState = FilterState.BLANK;
            if (isReturnMessageOff(work)) {
                work.returnMessage = ACCOUNT_NOT_PROVIDED_MESSAGE;
                work.failingField = FIELD_ACCOUNT_ID;
                work.failingKind = FilterState.BLANK;
            }
            work.commAreaAccountId = 0L;
            return;
        }

        // :665-678  Not numeric, which here means not eleven digits.
        if (!isAllDigits(work.ccAcctId)) {
            work.inputError = Boolean.TRUE;
            work.accountFilterState = FilterState.NOT_OK;
            if (isReturnMessageOff(work)) {
                work.returnMessage = ACCOUNT_FILTER_NOT_NUMERIC_MESSAGE;
                work.failingField = FIELD_ACCOUNT_ID;
                work.failingKind = FilterState.NOT_OK;
            }
            work.commAreaAccountId = 0L;
            return;
        }
        work.commAreaAccountId = Long.parseLong(work.ccAcctId);
        work.accountFilterState = FilterState.OK;
    }

    /**
     * The account-edit range's landing point, and the target of both of that paragraph's
     * {@code GO TO} statements.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 2210-EDIT-ACCOUNT-EXIT.} at line 681, body
     * {@code EXIT} at {@code :682}. The two branches that jump to it are translated as early returns,
     * which is what a {@code GO TO} the exit of the paragraph one is executing means.
     */
    private void editAccountExit2210() {
        // EXIT.
    }

    /**
     * Edits the card filter field.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 2220-EDIT-CARD.} at line 685.
     *
     * <p>The same shape as {@link #editAccount2210}: a pessimistic default at {@code :688}, a
     * not-supplied gate at {@code :691-702} whose message is guarded at {@code :696}, and a numeric gate
     * at {@code :706-715} whose inline uppercase message is guarded at {@code :709}. The numeric test
     * means "exactly sixteen digits", for the padding reason recorded on the account edit.
     *
     * <p>One difference from the account edit is worth naming: on success {@code :717} moves
     * {@code CC-CARD-NUM-N}, the numeric redefinition, into the commarea rather than the alphanumeric
     * group that {@code 2210} moves at {@code :676}. Both arrive at the same digits here because the gate
     * above has already established that all sixteen characters are digits, but the source's choice is
     * reproduced rather than harmonised.
     *
     * @param work the request-scoped working storage.
     */
    private void editCard2220(final ScreenWorkArea work) {
        // :688  SET FLG-CARDFILTER-NOT-OK TO TRUE.
        work.cardFilterState = FilterState.NOT_OK;

        // :691-702  Not supplied.
        if (isNotSupplied(work.ccCardNum, CARD_NUMBER_LOW_VALUES, CARD_NUMBER_SPACES)) {
            work.inputError = Boolean.TRUE;
            work.cardFilterState = FilterState.BLANK;
            if (isReturnMessageOff(work)) {
                work.returnMessage = CARD_NOT_PROVIDED_MESSAGE;
                work.failingField = FIELD_CARD_NUMBER;
                work.failingKind = FilterState.BLANK;
            }
            work.commAreaCardNumber = 0L;
            return;
        }

        // :706-715  Not numeric, which here means not sixteen digits.
        if (!isAllDigits(work.ccCardNum)) {
            work.inputError = Boolean.TRUE;
            work.cardFilterState = FilterState.NOT_OK;
            if (isReturnMessageOff(work)) {
                work.returnMessage = CARD_FILTER_NOT_NUMERIC_MESSAGE;
                work.failingField = FIELD_CARD_NUMBER;
                work.failingKind = FilterState.NOT_OK;
            }
            work.commAreaCardNumber = 0L;
            return;
        }
        work.commAreaCardNumber = Long.parseLong(work.ccCardNum);
        work.cardFilterState = FilterState.OK;
    }

    /**
     * The card-edit range's landing point, and the target of both of that paragraph's {@code GO TO}
     * statements.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 2220-EDIT-CARD-EXIT.} at line 722, body
     * {@code EXIT} at {@code :723}.
     */
    private void editCardExit2220() {
        // EXIT.
    }


    /**
     * Reads the data the screen needs.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 9000-READ-DATA.} at line 726.
     *
     * <p>Its entire body is one {@code PERFORM 9100-GETCARD-BYACCTCARD THRU
     * 9100-GETCARD-BYACCTCARD-EXIT} at {@code :728-729}. It is kept as its own method that delegates
     * rather than inlined into its caller, both because the one-to-one mandate requires it and because it
     * is the sole documentary evidence that {@code 9150} is never reached: this is the only paragraph that
     * could have performed it, and it does not.
     *
     * @param work the request-scoped working storage.
     */
    private void readData9000(final ScreenWorkArea work) {
        getCardByAcctCard9100(work);
        getCardByAcctCardExit9100();
    }

    /**
     * The read-data range's landing point.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 9000-READ-DATA-EXIT.} at line 732, body
     * {@code EXIT} at {@code :733}.
     */
    private void readDataExit9000() {
        // EXIT.
    }

    /**
     * Reads one card by card number - the program's only live file access.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 9100-GETCARD-BYACCTCARD.} at line 736.
     *
     * <p><b>The account identifier is not part of the read predicate.</b> Despite the paragraph's name,
     * {@code :740} moves only the card number into the record identification field and {@code :742-750}
     * issues {@code EXEC CICS READ FILE(LIT-CARDFILENAME) RIDFLD(WS-CARD-RID-CARDNUM)
     * KEYLENGTH(LENGTH OF WS-CARD-RID-CARDNUM)} - a keyed read on the base cluster with a sixteen-byte
     * key. The account form of the same read is commented out at {@code :739} and is a vestige; it is not
     * resurrected. So the target is {@code findById} on the card number and nothing else, and an account
     * filter that does not match the card's own account is <i>not</i> a reason to report not-found.
     *
     * <p><b>Three responses, three shapes, and the guard is not in the same place in each.</b>
     * {@code :753-754} sets the found state on a normal response. {@code :755-761} sets the error flag and
     * both filter states on not-found and then guards the <i>message</i>. {@code :762-771} sets the error
     * flag on anything else and guards the <i>flag</i> instead, leaving the four diagnostic moves and the
     * message move at {@code :767-771} unguarded. That asymmetry is transcribed as written; harmonising it
     * would change which message an operator sees when a read fails after an edit has already complained.
     *
     * <p>The exception the boundary raises is decided here rather than at the boundary, because the
     * mapping is response-specific: an empty result is a {@code RecordNotFoundException} carrying the
     * source's own wording, and a data-access failure is a {@code FileAccessException} carrying the
     * composed online message. Neither is swallowed and the second always preserves its root cause.
     *
     * <p><b>There is no end-of-file case and no duplicate case.</b> A census of {@code DFHRESP} across the
     * member returns four occurrences - two {@code NORMAL} and two {@code NOTFND} - and no
     * {@code ENDFILE}, {@code DUPREC} or {@code DUPKEY} anywhere. Nor is there any write, rewrite or
     * delete, which is why this service declares no transaction: it is read-only.
     *
     * @param work the request-scoped working storage.
     */
    private void getCardByAcctCard9100(final ScreenWorkArea work) {
        // :740  MOVE CC-CARD-NUM TO WS-CARD-RID-CARDNUM.
        work.cardRecordIdCardNumber = work.ccCardNum;

        // :742-750  EXEC CICS READ FILE(CARDDAT) RIDFLD(WS-CARD-RID-CARDNUM) INTO(CARD-RECORD).
        final Optional<Card> located;
        try {
            located = cardRepository.findById(work.cardRecordIdCardNumber);
        } catch (final DataAccessException | PersistenceException cause) {
            // :762-771  WHEN OTHER.  The guard here wraps the FLAG and not the message: only
            // FLG-ACCTFILTER-NOT-OK is conditional, while the four diagnostic moves and the message move
            // at :767-771 are unconditional.  That is the reverse of the NOTFND arm above it and is
            // transcribed as written.
            work.readOutcome = ReadOutcome.OTHER;
            work.readFailureCause = cause;
            work.inputError = Boolean.TRUE;
            if (isReturnMessageOff(work)) {
                work.accountFilterState = FilterState.NOT_OK;
            }
            work.errorOperationName = READ_OPERATION;
            work.errorFileName = CARD_FILE_NAME;
            work.errorResp = ERROR_RESP_UNAVAILABLE;
            work.errorResp2 = ERROR_RESP_UNAVAILABLE;
            work.returnMessage = composeFileErrorMessage(work);
            LOG.error("CARDDAT read failed under transaction {} for card {}; reporting a file error to the "
                    + "operator", THIS_TRANSACTION_ID, describeCardNumber(work.cardRecordIdCardNumber),
                    cause);
            return;
        }

        if (located.isPresent()) {
            // :753-754  WHEN DFHRESP(NORMAL).  Setting the condition name moves its literal - three
            // leading spaces and all - into WS-INFO-MSG, which is the same field the found test reads.
            work.readOutcome = ReadOutcome.NORMAL;
            work.cardRecord = located.get();
            work.infoMessage = DISPLAYING_DETAILS_MESSAGE;
        } else {
            // :755-761  WHEN DFHRESP(NOTFND).  Here the two filter flags are unconditional and the
            // MESSAGE is guarded - the opposite placement from WHEN OTHER.
            work.readOutcome = ReadOutcome.NOT_FOUND;
            work.inputError = Boolean.TRUE;
            work.accountFilterState = FilterState.NOT_OK;
            work.cardFilterState = FilterState.NOT_OK;
            if (isReturnMessageOff(work)) {
                work.returnMessage = CARD_NOT_FOUND_MESSAGE;
            }
        }
    }

    /**
     * The card-read range's landing point.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 9100-GETCARD-BYACCTCARD-EXIT.} at line 775,
     * body {@code EXIT} at {@code :776}.
     */
    private void getCardByAcctCardExit9100() {
        // EXIT.
    }

    /**
     * Retained unreachable paragraph - would have read the card file through the account alternate index.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 9150-GETCARD-BYACCT.} at line 779.
     *
     * <p><b>INTENTIONAL NO-OP, UNREACHABLE FOR PARITY.</b> The paragraph is declared in the source and
     * never performed. A search of the member for the name {@code 9150-GETCARD-BYACCT} returns exactly two
     * lines - {@code :779} and {@code :810} - which are the two labels themselves; there is no
     * {@code PERFORM}, no {@code GO TO} and no {@code THRU} reference anywhere. The only paragraph that
     * could have reached it, {@code 9000-READ-DATA} at {@code :726}, performs
     * {@code 9100-GETCARD-BYACCTCARD THRU 9100-GETCARD-BYACCTCARD-EXIT} and nothing else. It is preserved
     * so the paragraph map stays mechanically provable for the scope-coverage gate, and it is tracked in
     * {@code DECISION_LOG.md} and {@code TRACEABILITY_MATRIX.md}. Severity <b>Low</b>.
     *
     * <p><b>The body is deliberately empty and must stay empty.</b> A repository call here would
     * manufacture a phantom caller for a finder that nothing else needs - dead code of a worse kind than
     * the no-op it replaced - and would add lines no test can cover. The Javadoc carries the fidelity; the
     * body carries the no-op. No JaCoCo exclusion is added for it.
     *
     * <p><b>What the source would have done</b>, transcribed from {@code :783-807}. It would have issued
     * {@code EXEC CICS READ FILE(LIT-CARDFILENAME-ACCT-PATH) RIDFLD(WS-CARD-RID-ACCT-ID)
     * KEYLENGTH(LENGTH OF WS-CARD-RID-ACCT-ID) INTO(CARD-RECORD)} - that is, against {@code CARDAIX}, the
     * card file's own account <i>path</i>, and not against the cross-reference dataset. Then:
     * {@code WHEN DFHRESP(NORMAL)} sets {@code FOUND-CARDS-FOR-ACCOUNT};
     * {@code WHEN DFHRESP(NOTFND)} sets {@code INPUT-ERROR}, {@code FLG-ACCTFILTER-NOT-OK} and
     * {@code DID-NOT-FIND-ACCT-IN-CARDXREF}, all three <b>unguarded</b>, and notably does <i>not</i> set
     * the card filter flag, unlike {@code 9100}; {@code WHEN OTHER} sets {@code INPUT-ERROR} and
     * {@code FLG-ACCTFILTER-NOT-OK}, then moves {@code 'READ'} to {@code ERROR-OPNAME},
     * {@code LIT-CARDFILENAME-ACCT-PATH} to {@code ERROR-FILE}, the two response codes to
     * {@code ERROR-RESP} and {@code ERROR-RESP2}, and {@code WS-FILE-ERROR-MESSAGE} to
     * {@code WS-RETURN-MSG}.
     *
     * <p>Two data items follow this paragraph into disuse and are therefore <b>not</b> declared as Java
     * constants: {@code DID-NOT-FIND-ACCT-IN-CARDXREF} at {@code :151-152}, whose text is
     * {@code 'Did not find this account in cards database'} and which is set only here; and
     * {@code LIT-CARDFILENAME-ACCT-PATH} at {@code :189-190}, whose value {@code 'CARDAIX '} is read only
     * here. Declaring either would be untracked dead code under Rule 1 clause B, whereas recording them
     * in this prose is exactly the tracking the clause asks for. The flag's name is also a warning in its
     * own right - it says cross-reference where the file operand says alternate index - and it is one
     * reason no second repository is injected into this class.
     */
    private void getCardByAcct9150() {
        // INTENTIONAL NO-OP - declared but never performed in the source; retained for control-flow
        // parity and paragraph-map provability.  See this method's documentation.
    }

    /**
     * Retained unreachable paragraph - the landing point of the equally unreachable {@code 9150} range.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code 9150-GETCARD-BYACCT-EXIT.} at line 810.
     *
     * <p><b>INTENTIONAL NO-OP, UNREACHABLE FOR PARITY.</b> Its body in the source is the single statement
     * {@code EXIT} at {@code :811}, so it is a genuine no-operation there too - unreachable and empty on
     * both sides of the migration. Tracked in {@code DECISION_LOG.md} and
     * {@code TRACEABILITY_MATRIX.md} alongside its partner; severity <b>Low</b>. It is kept as a separate
     * method because the mandate forbids consolidating a label with its {@code -EXIT} partner.
     */
    private void getCardByAcctExit9150() {
        // INTENTIONAL NO-OP - unreachable, and EXIT in the source.
    }

    /**
     * Retained unreachable paragraph - would have sent a five-hundred-byte diagnostic text and returned.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code SEND-LONG-TEXT.} at line 820.
     *
     * <p><b>INTENTIONAL NO-OP, UNREACHABLE FOR PARITY.</b> The name occurs at {@code :820} and
     * {@code :831} only, as its own two labels, with no {@code PERFORM}, {@code GO TO} or {@code THRU}
     * reference anywhere in the member. The field it would have sent, {@code WS-LONG-MSG PIC X(500)}
     * declared at {@code :125}, is referenced only inside this dead body, at {@code :822} and
     * {@code :823}, and nowhere else. Contrast {@code SEND-PLAIN-TEXT} at {@code :838}, which <i>is</i>
     * performed - from {@code :379-380} - and is therefore implemented for real. Tracked in
     * {@code DECISION_LOG.md} and {@code TRACEABILITY_MATRIX.md}; severity <b>Low</b>.
     *
     * <p><b>What the source would have done</b>, from {@code :821-829}: {@code EXEC CICS SEND TEXT
     * FROM(WS-LONG-MSG) LENGTH(LENGTH OF WS-LONG-MSG) ERASE FREEKB} followed by {@code EXEC CICS RETURN}.
     * Its own comment block at {@code :816-818} records the intent - it is for debugging and is not to be
     * used in the regular course - which is corroborating evidence that the absence of a caller is
     * deliberate rather than an oversight.
     */
    private void sendLongText() {
        // INTENTIONAL NO-OP - declared but never performed in the source; retained for control-flow
        // parity and paragraph-map provability.  See this method's documentation.
    }

    /**
     * Retained unreachable paragraph - the landing point of the unreachable long-text range.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code SEND-LONG-TEXT-EXIT.} at line 831, body
     * {@code EXIT} at {@code :832}.
     *
     * <p><b>INTENTIONAL NO-OP, UNREACHABLE FOR PARITY.</b> Tracked in {@code DECISION_LOG.md} and
     * {@code TRACEABILITY_MATRIX.md} with its partner; severity <b>Low</b>. Kept separate for the same
     * reason: a label and its {@code -EXIT} are two labels.
     */
    private void sendLongTextExit() {
        // INTENTIONAL NO-OP - unreachable, and EXIT in the source.
    }

    /**
     * Sends the accumulated message as plain text and ends the task.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code SEND-PLAIN-TEXT.} at line 838.
     *
     * <p>This paragraph <b>is</b> reachable - {@code :379-380} performs it from the mainline's
     * unexpected-data arm - so it is implemented rather than retained as a no-op. {@code :839-844} sends
     * {@code WS-RETURN-MSG} with {@code ERASE FREEKB} and {@code :846-847} issues {@code EXEC CICS
     * RETURN}, which ends the task: nothing after the {@code PERFORM} in the mainline can run.
     *
     * <p>Ending the task is what the Java throw models, and it is the reason this method's only observable
     * effect is to raise {@link FatalProcessingException}. The payload is the one the calling arm built at
     * {@code :374-378}: abend code {@code '0001'}, culprit {@code COCRDSLC}, reason spaces, and the
     * message {@code UNEXPECTED DATA SCENARIO}. Note that the arm raises no CICS abend of its own - the
     * distinct {@code ABCODE('9999')} belongs to {@link #abendRoutine} and the value {@code 999} belongs
     * to the batch corpus - so three abend values coexist in this migration and only the two online ones
     * appear in this class.
     *
     * <p>The method returns {@code void} and throws unconditionally rather than returning the exception for
     * the caller to throw, because that is what the source does: control does not come back. A
     * consequence worth naming is that the compiler cannot see the throw through the call, which is what
     * keeps the redundant gate at {@code :386-391} compilable and therefore preservable.
     *
     * @param work the request-scoped working storage, carrying the abend payload and the message.
     * @throws FatalProcessingException always.
     */
    private void sendPlainText(final ScreenWorkArea work) {
        LOG.error("Transaction {} reached the unexpected-data arm of its dispatch; abend payload code {} "
                + "culprit {}", THIS_TRANSACTION_ID, work.abendCode, work.abendCulprit);
        throw new FatalProcessingException(work.abendCode, work.abendCulprit, work.abendReason,
                work.returnMessage);
    }

    /**
     * The plain-text range's landing point.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code SEND-PLAIN-TEXT-EXIT.} at line 849, body
     * {@code EXIT} at {@code :850}.
     *
     * <p>It is unreachable at run time for a different reason from the four retained no-ops: it <i>is</i>
     * named by the {@code PERFORM ... THRU} at {@code :379-380}, but the range's first paragraph ends the
     * task before control can fall into it. The method is therefore genuinely called from the mainline and
     * genuinely never entered, exactly as in the source.
     */
    private void sendPlainTextExit() {
        // EXIT.
    }

    /**
     * Maps the terminal's attention identifier onto the shared five-character action code.
     *
     * <p>Source: {@code app/cpy/CSSTRPFY.cpy} paragraph {@code YYYY-STORE-PFKEY.} at line 17, copied into
     * this program's procedure division by {@code COPY 'CSSTRPFY'} at
     * {@code app/cbl/COCRDSLC.cbl:855}.
     *
     * <p>The copybook is one {@code EVALUATE TRUE} over {@code EIBAID}: {@code DFHENTER} and
     * {@code DFHCLEAR} map to their own codes, {@code DFHPA1} and {@code DFHPA2} to theirs, and
     * {@code DFHPF1} through {@code DFHPF12} to {@code CCARD-AID-PFK01} through {@code CCARD-AID-PFK12}.
     *
     * <p><b>Keys thirteen to twenty-four fold onto one to twelve.</b> {@code DFHPF13} maps to
     * {@code CCARD-AID-PFK01}, {@code DFHPF14} to {@code PFK02}, and so on to {@code DFHPF24} mapping to
     * {@code PFK12}. The two halves of the keyboard are therefore indistinguishable downstream, which on
     * this screen means {@code PF15} exits just as {@code PF3} does. The fold is expressed here as paired
     * cases so it is visible rather than merely arithmetic.
     *
     * <p><b>There is no {@code WHEN OTHER}.</b> An attention identifier the copybook does not enumerate
     * leaves {@code CCARD-AID} exactly as it was - which after the mainline's {@code INITIALIZE} is
     * {@code LOW-VALUES} - and the gate at {@code app/cbl/COCRDSLC.cbl:291-299} then coerces it to
     * {@code ENTER}. The {@link AttentionIdentifier#UNMAPPED} case reproduces that by assigning the field
     * its own current value, which is what "no branch taken" means for an {@code EVALUATE} whose every
     * branch is an assignment. Written as an assignment rather than as an empty branch so that no
     * statement in this method is empty.
     *
     * @param work the request-scoped working storage.
     * @param attentionIdentifier the identifier the terminal raised.
     */
    private static void storePfKeyYyyy(final ScreenWorkArea work,
            final AttentionIdentifier attentionIdentifier) {
        work.ccardAid = switch (attentionIdentifier) {
            case ENTER -> CCARD_AID_ENTER;
            case CLEAR -> CCARD_AID_CLEAR;
            case PA1 -> CCARD_AID_PA1;
            case PA2 -> CCARD_AID_PA2;
            case PF1, PF13 -> mappedFunctionKey(1);
            case PF2, PF14 -> mappedFunctionKey(2);
            case PF3, PF15 -> mappedFunctionKey(3);
            case PF4, PF16 -> mappedFunctionKey(4);
            case PF5, PF17 -> mappedFunctionKey(5);
            case PF6, PF18 -> mappedFunctionKey(6);
            case PF7, PF19 -> mappedFunctionKey(7);
            case PF8, PF20 -> mappedFunctionKey(8);
            case PF9, PF21 -> mappedFunctionKey(9);
            case PF10, PF22 -> mappedFunctionKey(10);
            case PF11, PF23 -> mappedFunctionKey(11);
            case PF12, PF24 -> mappedFunctionKey(MAPPED_FUNCTION_KEY_COUNT);
            case UNMAPPED -> work.ccardAid;
        };
    }

    /**
     * The store-key range's landing point.
     *
     * <p>Source: {@code app/cpy/CSSTRPFY.cpy} paragraph {@code YYYY-STORE-PFKEY-EXIT.} at line 80, copied
     * in at {@code app/cbl/COCRDSLC.cbl:855} and named by the {@code PERFORM ... THRU} at {@code :284-285}.
     * Its body in the copybook is {@code EXIT}.
     */
    private static void storePfKeyExitYyyy() {
        // EXIT.
    }

    /**
     * Handles an abend.
     *
     * <p>Source: {@code app/cbl/COCRDSLC.cbl} paragraph {@code ABEND-ROUTINE.} at line 857.
     *
     * <p>This paragraph <b>is</b> reachable: {@code EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)} at
     * {@code :250-252} names it as the handler for the whole task, so it is implemented for real rather
     * than retained as a no-op. It has <b>no</b> {@code -EXIT} label, because it terminates with
     * {@code EXEC CICS ABEND} at {@code :875-877} and there is nothing to fall into; none is invented.
     *
     * <p>The body sets the culprit at {@code :863}, sends the payload at {@code :865-869}, cancels the
     * handler at {@code :871-873} so a failure inside the handler cannot loop, and abends with
     * {@code ABCODE('9999')}. In the target the payload becomes the four fields of
     * {@code app/cpy/CSMSG02Y.cpy} - internally titled {@code CABENDD.CPY} - namely {@code ABEND-CODE
     * PIC X(4)}, {@code ABEND-CULPRIT PIC X(8)}, {@code ABEND-REASON PIC X(50)} and {@code ABEND-MSG
     * PIC X(72)}, 134 bytes in all, carried on the exception. Cancelling the handler corresponds to the
     * exception propagating rather than re-entering this method.
     *
     * <p><b>Use {@code '9999'} for this site.</b> Three abend values coexist in the migration and they are
     * not interchangeable: the batch corpus abends with {@code 999} and return code 12
     * ({@code app/cbl/CBTRN02C.cbl:707-711}); this online handler abends with {@code ABCODE('9999')}; and
     * the online <i>payload</i> value {@code '0001'} is what the mainline's unexpected-data arm moves into
     * {@code ABEND-CODE} at {@code :375}. Only the latter two occur in this class.
     *
     * <p><b>PARITY TRAP - the default message is never substituted at run time.</b> The guard at
     * {@code :859} tests {@code IF ABEND-MSG EQUAL LOW-VALUES}, but all four fields are declared
     * {@code VALUE SPACES} in {@code app/cpy/CSMSG02Y.cpy}, and the mainline's {@code INITIALIZE} at
     * {@code :254-256} names {@code CC-WORK-AREA}, {@code WS-MISC-STORAGE} and {@code WS-COMMAREA} but
     * <i>not</i> {@code ABEND-DATA}. The field therefore holds spaces, not low values, the test is false,
     * and {@code 'UNEXPECTED ABEND OCCURRED.'} is never moved. The Java equivalent must substitute the
     * default only when the message is {@code null} and never when it is blank or empty - which is exactly
     * what {@code FatalProcessingException} already does, so the behaviour is inherited rather than
     * reimplemented and cannot drift. Severity <b>Medium</b>, tracked; remediation, were parity not the
     * contract, would be to change the guard to test {@code SPACES}.
     *
     * @param cause the unmodelled failure that reached the handler; never {@code null} at any call site.
     * @return the exception to throw, so the caller's {@code throw} keeps the control flow visible.
     */
    private FatalProcessingException abendRoutine(final RuntimeException cause) {
        // :859-861  The guard that never fires.  Passing the message through unchanged - null included -
        // reproduces both halves: a null message takes the shared default, and a blank one does not.
        // :863  MOVE LIT-THISPGM TO ABEND-CULPRIT.
        LOG.error("Transaction {} abended; handler {} raising abend code {}", THIS_TRANSACTION_ID,
                THIS_PROGRAM, HANDLER_ABEND_CODE, cause);
        return new FatalProcessingException(HANDLER_ABEND_CODE, THIS_PROGRAM, ABEND_REASON_SPACES,
                cause.getMessage(), cause);
    }


    /**
     * Hands the assembled screen to the caller, or raises the typed exception the recorded outcome calls
     * for.
     *
     * <p>This is not a paragraph. It is the REST boundary, and it exists because the source and the target
     * report failure by different mechanisms: the source builds an error screen, sends it and returns,
     * whereas the migration mandates a typed exception per outcome. Both happen here, in that order - the
     * caller has already run the full {@code 1000-SEND-MAP} chain and {@code COMMON-RETURN} before
     * reaching this point, so every paragraph the source would have executed has executed, with its
     * messages and its {@code '*'} markers, and only then is the outcome converted.
     *
     * <p>Concentrating the conversion in one place rather than throwing from inside the paragraphs keeps
     * two things true at once: the paragraphs stay faithful transcriptions that only ever set flags and
     * messages, exactly as the COBOL does, and the mapping table below has a single site to be verified
     * against.
     *
     * <table border="1">
     *   <caption>Recorded outcome to raised exception</caption>
     *   <tr><th>Recorded state</th><th>Source</th><th>Raised</th></tr>
     *   <tr><td>read reported not-found</td><td>{@code :755-761}</td>
     *       <td>{@code RecordNotFoundException}, message {@code Did not find cards for this search
     *       condition}, no record key</td></tr>
     *   <tr><td>read failed otherwise</td><td>{@code :762-771}</td>
     *       <td>{@code FileAccessException}, message the composed {@code File Error: ...} truncated to 75,
     *       cause preserved</td></tr>
     *   <tr><td>input error, no field attributed</td><td>{@code :637-640}</td>
     *       <td>{@code ValidationException}, message {@code No input received}</td></tr>
     *   <tr><td>input error, field blank</td><td>{@code :651-661}, {@code :691-702}</td>
     *       <td>{@code ValidationException} of kind {@code BLANK} carrying the field <i>name</i></td></tr>
     *   <tr><td>input error, field invalid</td><td>{@code :665-678}, {@code :706-715}</td>
     *       <td>{@code ValidationException} of kind {@code INVALID} carrying the field
     *       <i>name</i></td></tr>
     *   <tr><td>nothing recorded</td><td>{@code :753-754}, {@code :349-356}, {@code :305-336}</td>
     *       <td>nothing; the screen is returned</td></tr>
     * </table>
     *
     * <p>The read outcome is tested before the input-error flag because the not-found and failed arms both
     * set that flag themselves at {@code :756} and {@code :763}; they are read failures, not edit
     * failures, and reporting them as validation problems would misattribute them. The two cannot collide,
     * because {@code 9000-READ-DATA} only runs when the edits passed.
     *
     * <p>No card number, account identifier or field value reaches any of these exceptions. The validation
     * failures carry a field <i>name</i>; the not-found failure uses the single-argument form precisely so
     * that no record key can be attached; and the composed file-error message names an operation and a
     * file, never a key.
     *
     * @param work the request-scoped working storage carrying the recorded outcome.
     * @param screen the screen the source would have sent.
     * @return {@code screen}, when nothing was recorded.
     * @throws RecordNotFoundException if the read found no card.
     * @throws FileAccessException if the read failed for any other reason.
     * @throws ValidationException if an edit rejected the input.
     */
    private CardDetailScreen deliver(final ScreenWorkArea work, final CardDetailScreen screen) {
        if (work.readOutcome == ReadOutcome.NOT_FOUND) {
            throw new RecordNotFoundException(work.returnMessage);
        }
        if (work.readOutcome == ReadOutcome.OTHER) {
            throw new FileAccessException(work.returnMessage, null, CARD_FILE_NAME, READ_OPERATION,
                    work.readFailureCause);
        }
        if (Boolean.TRUE.equals(work.inputError)) {
            if (work.failingField == null) {
                throw new ValidationException(work.returnMessage);
            }
            if (work.failingKind == FilterState.BLANK) {
                throw ValidationException.missingField(work.failingField, work.returnMessage);
            }
            throw ValidationException.invalidField(work.failingField, work.returnMessage);
        }
        return screen;
    }

    /**
     * Builds one of the twelve mapped function-key codes.
     *
     * <p>The copybook forms each value as the literal {@code PFK} followed by a two-digit ordinal -
     * {@code app/cpy/CVCRD01Y.cpy:8-19} - so forming it the same way is what lets the
     * {@code DFHPF13}-onwards fold be written as paired cases instead of as twelve more constants.
     *
     * @param ordinal the mapped key number, one through {@value #MAPPED_FUNCTION_KEY_COUNT}.
     * @return the five-character code, for example {@code PFK03}.
     */
    private static String mappedFunctionKey(final int ordinal) {
        return CCARD_AID_PFK_PREFIX + (ordinal < 10 ? "0" + ordinal : Integer.toString(ordinal));
    }

    /**
     * Renders the header date field.
     *
     * <p>Reproduces {@code app/cbl/COCRDSLC.cbl:439-443}, which assembles {@code WS-CURDATE-MONTH},
     * {@code WS-CURDATE-DAY} and the last two digits of {@code WS-CURDATE-YEAR} into the
     * {@code MM/DD/YY} layout of {@code app/cpy/CSDAT01Y.cpy:30-35}. The pattern's {@code yy} is exactly
     * the {@code WS-CURDATE-YEAR(3:2)} reference substring, and the formatter is bound to
     * {@code Locale.ROOT} so the digits and separators cannot vary with the host locale.
     *
     * @param moment the value the clock produced.
     * @return eight characters, matching {@code CURDATEO PIC X(8)}.
     */
    private static String renderHeaderDate(final LocalDateTime moment) {
        return HEADER_DATE_FORMAT.format(moment);
    }

    /**
     * Renders the header time field.
     *
     * <p>Reproduces {@code app/cbl/COCRDSLC.cbl:445-449} and the {@code HH:MM:SS} layout of
     * {@code app/cpy/CSDAT01Y.cpy:36-41}, on a twenty-four hour clock and under {@code Locale.ROOT} so no
     * locale can substitute a twelve-hour rendering.
     *
     * @param moment the value the clock produced.
     * @return eight characters, matching {@code CURTIMEO PIC X(8)}.
     */
    private static String renderHeaderTime(final LocalDateTime moment) {
        return HEADER_TIME_FORMAT.format(moment);
    }

    /**
     * Renders an unsigned display field of the given width.
     *
     * <p>This is a COBOL {@code MOVE} of a numeric value into a {@code PIC 9(n)} field, and both of its
     * edge behaviours are the move's rather than inventions. A value with more digits than the field is
     * truncated on the <b>left</b>, because a numeric move aligns on the decimal point and discards
     * high-order digits. A negative value loses its sign, because an unsigned {@code PIC 9(n)} field has
     * no sign position to hold one; {@code CDEMO-ACCT-ID PIC 9(11)} and {@code CDEMO-CARD-NUM PIC 9(16)}
     * are both unsigned, so no signed value can arise from the source in the first place.
     *
     * <p>The sign is stripped from the rendered digits rather than by negating the value, so
     * {@code Long.MIN_VALUE} is handled like any other input instead of overflowing.
     *
     * @param value the value to render.
     * @param width the receiving field's width.
     * @return exactly {@code width} characters, all of them digits.
     */
    private static String renderZeroPadded(final long value, final int width) {
        final String rendered = Long.toString(value);
        final String digits = rendered.startsWith("-") ? rendered.substring(1) : rendered;
        if (digits.length() >= width) {
            return digits.substring(digits.length() - width);
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Reads the commarea card number as the numeric value {@code CDEMO-CARD-NUM PIC 9(16)} holds.
     *
     * <p>Anything that is not a run of digits cannot be held by that field at all, and an unset numeric
     * field holds zero, so zero is what is returned for it - which is also the value the echo test at
     * {@code app/cbl/COCRDSLC.cbl:468} treats as "nothing to display".
     *
     * @param cardNumber the commarea value, possibly {@code null}.
     * @return the numeric value, or zero when there is none.
     */
    private static long parseCommAreaCardNumber(final String cardNumber) {
        if (!isAllDigits(cardNumber) || cardNumber.length() > CARD_NUMBER_WIDTH) {
            return 0L;
        }
        return Long.parseLong(cardNumber);
    }

    /**
     * Pads a value on the right to a fixed width, the way a COBOL {@code MOVE} into a
     * {@code PIC X(n)} field pads it.
     *
     * <p>An absent value yields a field of spaces rather than {@code null}, because that is what a move
     * from an all-spaces or uninitialised sending field leaves in the receiver, and because the
     * fixed-width compositions that depend on this must not lose bytes. A value already at or above the
     * width is truncated on the right, again as the move truncates.
     *
     * @param value the sending value, possibly {@code null}.
     * @param width the receiving field's width.
     * @return exactly {@code width} characters.
     */
    private static String padRight(final String value, final int width) {
        final String sender = value == null ? "" : value;
        if (sender.length() >= width) {
            return sender.substring(0, width);
        }
        return sender + " ".repeat(width - sender.length());
    }

    /**
     * Truncates a value on the right to a fixed width without padding it.
     *
     * <p>Used where the source's move can only shorten - the two inbound map fields, whose widths the
     * terminal enforces, and the eighty-byte file-error message moved into a seventy-five-byte field. An
     * absent value stays absent, because {@code LOW-VALUES} and spaces are distinct states here and
     * collapsing them would lose the three-state marker.
     *
     * @param value the sending value, possibly {@code null}.
     * @param width the receiving field's width.
     * @return the value shortened to {@code width} if it was longer, otherwise unchanged.
     */
    private static String truncate(final String value, final int width) {
        if (value == null || value.length() <= width) {
            return value;
        }
        return value.substring(0, width);
    }

    /**
     * Whether a field is unset or holds only spaces.
     *
     * <p>Covers both shapes the source tests: the bare {@code = SPACES} of
     * {@code app/cbl/COCRDSLC.cbl:616} and {@code :623}, and the {@code EQUAL LOW-VALUES OR EQUAL SPACES}
     * pair of {@code :309-310} and {@code :317-318}. They are one predicate here because {@code null}
     * models {@code LOW-VALUES} and neither test distinguishes an unset field from a blank one.
     *
     * @param value the field, possibly {@code null}.
     * @return {@code true} when the field carries nothing an operator typed.
     */
    private static boolean isUnsetOrSpaces(final String value) {
        return value == null || value.isBlank();
    }

    /**
     * Whether a filter is "not supplied" in the sense the two edit paragraphs mean.
     *
     * <p>{@code app/cbl/COCRDSLC.cbl:651-653} and {@code :691-693} each offer three alternatives:
     * {@code EQUAL LOW-VALUES}, {@code EQUAL SPACES}, and the numeric redefinition {@code EQUAL ZEROS}.
     * The third compares a {@code PIC 9(n)} view of storage that may be holding {@code LOW-VALUES}, which
     * is not something a portable implementation can reproduce literally; what is reproduced is the
     * outcome, namely that an unset field, an explicit run of null bytes, a run of spaces and a run of
     * zeros are all "not supplied". The asterisk reaches this predicate as {@code null}, having been
     * turned into {@code LOW-VALUES} by {@code :615-627}.
     *
     * <p>No further guard is added. A mixed value such as ten zeros followed by a space is not treated as
     * "not supplied" here and is instead rejected by the numeric gate that follows, which is where the
     * source rejects it too.
     *
     * @param value the work-area filter, possibly {@code null}.
     * @param lowValues the field's width in null bytes.
     * @param spaces the field's width in spaces.
     * @return {@code true} when the filter carries no search criterion.
     */
    private static boolean isNotSupplied(final String value, final String lowValues, final String spaces) {
        return value == null || lowValues.equals(value) || spaces.equals(value) || isAllZeros(value);
    }

    /**
     * Whether every character is the digit zero - the reproducible half of
     * {@code IF CC-ACCT-ID-N EQUAL ZEROS} at {@code app/cbl/COCRDSLC.cbl:653} and {@code :693}.
     *
     * @param value the field; never {@code null} at the one call site.
     * @return {@code true} when the field is all zeros.
     */
    private static boolean isAllZeros(final String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '0') {
                return false;
            }
        }
        return !value.isEmpty();
    }

    /**
     * Whether every character is an ASCII digit - COBOL's {@code IS NUMERIC} class test on a
     * {@code PIC X(n)} field, as used at {@code app/cbl/COCRDSLC.cbl:665} and {@code :706}.
     *
     * <p>The test is deliberately restricted to {@code U+0030} through {@code U+0039}. A locale-aware or
     * Unicode-aware digit test would accept other decimal digit characters, which the COBOL class test
     * does not, and accepting them would let a value through the gate that the source rejects.
     *
     * <p>Because the caller has already padded the field to its declared width, this is also what makes
     * the gate mean "exactly eleven digits" or "exactly sixteen digits": a shorter value carries trailing
     * spaces by then, and a space is not a digit.
     *
     * @param value the field, possibly {@code null}.
     * @return {@code true} when the field is a non-empty run of ASCII digits.
     */
    private static boolean isAllDigits(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} still holds - the guard at
     * {@code app/cbl/COCRDSLC.cbl:656}, {@code :668}, {@code :696}, {@code :709}, {@code :759} and
     * {@code :764}.
     *
     * <p>This one predicate is what makes the program first-error-wins. Every message write in the member
     * except the cross-field one is behind it, so once a message has landed the later failures record
     * their flags and leave the text alone.
     *
     * @param work the request-scoped working storage.
     * @return {@code true} when no message has been recorded yet.
     */
    private static boolean isReturnMessageOff(final ScreenWorkArea work) {
        return isUnsetOrSpaces(work.returnMessage);
    }

    /**
     * Composes the online file-error message and applies the move that shortens it.
     *
     * <p>Assembles the nine segments of {@code WS-FILE-ERROR-MESSAGE} at
     * {@code app/cbl/COCRDSLC.cbl:102-121} in declaration order and at their declared widths - 12, 8, 4,
     * 9, 15, 10, 7, 10 and 5 - which totals eighty characters, and then reproduces
     * {@code MOVE WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG} at {@code :771} by shortening the result to the
     * seventy-five of {@code WS-RETURN-MSG PIC X(75)}.
     *
     * <p>Composing to eighty first and shortening afterwards is not a detour. The five discarded bytes are
     * only the trailing filler when every preceding segment is exactly full; shorten earlier and the loss
     * lands somewhere else.
     *
     * <p><b>This is the online format and it is not the batch one.</b> The batch corpus renders
     * {@code FILE STATUS IS: NNNN}, which {@code FileStatus} and {@code FileStatusMapper} own and which
     * must be emitted verbatim wherever it is used. Nothing here reformats, wraps, prefixes or truncates
     * that literal, because nothing here goes near it.
     *
     * <p>It is also not shareable with {@code app/cbl/COCRDLIC.cbl}, whose own copy of this structure
     * differs in two places: its leading literal is {@code 'File Error:'} with no trailing space, and its
     * final filler carries no {@code VALUE} clause. A helper common to both programs would have to pick
     * one, and would then be wrong in the other.
     *
     * @param work the request-scoped working storage carrying the four variable segments.
     * @return the message, at most seventy-five characters.
     */
    private static String composeFileErrorMessage(final ScreenWorkArea work) {
        final String assembled = FILE_ERROR_PREFIX
                + padRight(work.errorOperationName, ERROR_OPNAME_WIDTH)
                + FILE_ERROR_ON_SEGMENT
                + padRight(work.errorFileName, ERROR_FILE_WIDTH)
                + FILE_ERROR_RESP_SEGMENT
                + padRight(work.errorResp, ERROR_RESP_WIDTH)
                + FILE_ERROR_RESP2_SEGMENT
                + padRight(work.errorResp2, ERROR_RESP_WIDTH)
                + FILE_ERROR_TRAILING_FILLER;
        return truncate(padRight(assembled, FILE_ERROR_MESSAGE_WIDTH), RETURN_MESSAGE_WIDTH);
    }

    /**
     * Extracts the expiry month from the redefined date field.
     *
     * <p>{@code CARD-EXPIRY-MONTH} occupies {@code (6:2)} of {@code CARD-EXPIRAION-DATE-X} -
     * {@code app/cbl/COCRDSLC.cbl:88} - which is the two characters after the first separator of a
     * dash-separated {@code yyyy-MM-dd} value. The field is handled as text throughout: there is no
     * temporal type, no format validation and no parsing, exactly as in the source and in the entity.
     *
     * @param expiryDate the date field, already padded to {@value #EXPIRY_DATE_WIDTH} characters.
     * @return two characters, matching {@code EXPMONO PIC X(2)}.
     */
    private static String extractExpiryMonth(final String expiryDate) {
        return expiryDate.substring(EXPIRY_MONTH_OFFSET, EXPIRY_MONTH_OFFSET + EXPIRY_MONTH_WIDTH);
    }

    /**
     * Extracts the expiry year from the redefined date field.
     *
     * <p>{@code CARD-EXPIRY-YEAR} occupies {@code (1:4)} - {@code app/cbl/COCRDSLC.cbl:85} - the four
     * characters before the first separator. The sibling {@code CARD-EXPIRY-DAY} at {@code (9:2)} is
     * extracted by the source but never moved to the screen, so no counterpart to it exists here and none
     * is added.
     *
     * @param expiryDate the date field, already padded to {@value #EXPIRY_DATE_WIDTH} characters.
     * @return four characters, matching {@code EXPYEARO PIC X(4)}.
     */
    private static String extractExpiryYear(final String expiryDate) {
        return expiryDate.substring(EXPIRY_YEAR_OFFSET, EXPIRY_YEAR_OFFSET + EXPIRY_YEAR_WIDTH);
    }

    /**
     * Describes a card number for a log event without disclosing any part of it.
     *
     * <p>Rule 1 clause D admits no partial disclosure, so the value is not masked to its last four digits
     * and not hashed - it is replaced outright. The absent case keeps its own marker so that "there was no
     * card number" stays distinguishable from "there was one and it is withheld", which is the only
     * distinction a diagnostic legitimately needs.
     *
     * <p>Masking happens here, at the call site, because the entity offers nothing to do it with:
     * {@code Card.toString()} excludes the card number, the verification value and the embossed name
     * precisely so that no accidental interpolation can leak them, and it exposes no masking helper of its
     * own because presentation belongs to the projection layer.
     *
     * @param cardNumber the value that must not be logged.
     * @return a fixed marker, never any part of the argument.
     */
    private static String describeCardNumber(final String cardNumber) {
        return cardNumber == null ? CARD_NUMBER_ABSENT : CARD_NUMBER_REDACTED;
    }

    /**
     * The three values of {@code WS-RESP-CD PIC S9(09) COMP} that
     * {@code app/cbl/COCRDSLC.cbl:752-772} tests, and nothing more.
     *
     * <p>{@code null} means the read has not been attempted, which is the state on the two paths that send
     * a screen without reading - the prompt at {@code :349-356} and the exit at {@code :305-336}.
     */
    private enum ReadOutcome {

        /** {@code WHEN DFHRESP(NORMAL)} - {@code app/cbl/COCRDSLC.cbl:753}. */
        NORMAL,

        /** {@code WHEN DFHRESP(NOTFND)} - {@code app/cbl/COCRDSLC.cbl:755}. */
        NOT_FOUND,

        /**
         * {@code WHEN OTHER} - {@code app/cbl/COCRDSLC.cbl:762}. Any response the two named arms did not
         * match. There is deliberately no end-of-file or duplicate-key member: a census of
         * {@code DFHRESP} across the member finds only {@code NORMAL} and {@code NOTFND}, and inventing
         * either would create a state nothing can reach.
         */
        OTHER
    }

    /**
     * The transaction's working storage, for the duration of exactly one request.
     *
     * <p>Every field below stands for a named item in {@code app/cbl/COCRDSLC.cbl}'s {@code WORKING-STORAGE
     * SECTION} or in one of the copybooks it includes, and the class exists so that they can be
     * <b>method-local</b>. Rule 1 clause B forbids global mutable state; a COBOL program's working storage
     * is exactly that, being static for the life of the run unit, so the flags, filters, messages and
     * record images are gathered into one short-lived object instead. One instance is created per call to
     * {@link CardDetailService#main0000} and is unreachable when the call returns, which is what makes the
     * service itself stateless and safe to share across threads without synchronisation.
     *
     * <p>{@code null} consistently means {@code LOW-VALUES}, the state {@code INITIALIZE} at
     * {@code app/cbl/COCRDSLC.cbl:254-256} leaves behind and the state {@code MOVE LOW-VALUES TO CCRDSLAO}
     * at {@code :428} restores. It is never a synonym for spaces or for an empty string: the three-state
     * filter marker depends on telling unset from blank, so the two are not interchanged anywhere.
     *
     * <p>The class is {@code private static final} - it holds no reference to the service, and being final
     * it cannot leak {@code this} through an overridable call from its own initialisation.
     */
    private static final class ScreenWorkArea {

        /**
         * Creates the working storage in the state {@code INITIALIZE CC-WORK-AREA WS-MISC-STORAGE
         * WS-COMMAREA} leaves it in at {@code app/cbl/COCRDSLC.cbl:254-256}.
         *
         * <p>Every field is left at its Java default, which is the point: a reference field defaults to
         * {@code null}, and {@code null} is {@code LOW-VALUES} throughout this class. Declared explicitly
         * rather than relying on the implicit constructor so that the correspondence is documented.
         */
        private ScreenWorkArea() {
            // Field defaults are the INITIALIZE state; see this constructor's documentation.
        }

        /** {@code WS-TRANID} - {@code app/cbl/COCRDSLC.cbl:39}, set from the literal at {@code :260}. */
        private String transactionId;

        /**
         * {@code WS-CURDATE-DATA} of {@code app/cpy/CSDAT01Y.cpy}, the target of
         * {@code MOVE FUNCTION CURRENT-DATE} at {@code app/cbl/COCRDSLC.cbl:430} and again at
         * {@code :437}.
         *
         * <p>It is written twice per screen and read only after the second write, which is the redundancy
         * recorded on {@link CardDetailService#screenInit1100}. Both writes are kept, so this field
         * genuinely changes value twice, exactly as the source's does.
         */
        private LocalDateTime currentDateData;

        /**
         * {@code CCARD-AID PIC X(5)} - {@code app/cpy/CVCRD01Y.cpy:3}, written by the copied-in key
         * mapping and read by the gate at {@code app/cbl/COCRDSLC.cbl:291-299}.
         */
        private String ccardAid;

        /**
         * {@code WS-PFK-FLAG} - {@code app/cbl/COCRDSLC.cbl:66-68}. {@code true} is
         * {@code 88 PFK-INVALID VALUE '1'}; {@code false} is {@code 88 PFK-VALID VALUE '0'}.
         */
        private boolean pfkInvalid;

        /**
         * {@code WS-INPUT-FLAG} - {@code app/cbl/COCRDSLC.cbl:51-54}, which has three states and is
         * therefore boxed. {@code null} is {@code 88 INPUT-PENDING VALUE LOW-VALUES},
         * {@code Boolean.FALSE} is {@code 88 INPUT-OK VALUE '0'} and {@code Boolean.TRUE} is
         * {@code 88 INPUT-ERROR VALUE '1'}. A primitive would collapse pending into one of the other two.
         */
        private Boolean inputError;

        /**
         * {@code WS-EDIT-ACCT-FLAG} - {@code app/cbl/COCRDSLC.cbl:55-58}. {@code null} is the
         * {@code LOW-VALUES} the field starts at, which satisfies none of its three condition names.
         */
        private FilterState accountFilterState;

        /** {@code WS-EDIT-CARD-FLAG} - {@code app/cbl/COCRDSLC.cbl:59-62}, on identical terms. */
        private FilterState cardFilterState;

        /**
         * {@code WS-RETURN-MSG PIC X(75)} - {@code app/cbl/COCRDSLC.cbl:134}. {@code null} or blank is
         * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES}, the state every message guard tests.
         */
        private String returnMessage;

        /**
         * {@code WS-INFO-MSG PIC X(40)} - {@code app/cbl/COCRDSLC.cbl:126}.
         *
         * <p>It is <b>also</b> the found-cards flag: {@code 88 FOUND-CARDS-FOR-ACCOUNT} at
         * {@code :129-130} and {@code 88 WS-PROMPT-FOR-INPUT} at {@code :131-132} are condition names
         * declared on this one field, so setting either writes its literal here and testing either compares
         * against it. There is no separate boolean anywhere in this class for the same reason.
         */
        private String infoMessage;

        /** {@code CCARD-ERROR-MSG PIC X(75)} - {@code app/cpy/CVCRD01Y.cpy:28}. */
        private String errorMessage;

        /** {@code CC-ACCT-ID PIC X(11)} - {@code app/cpy/CVCRD01Y.cpy:34}. */
        private String ccAcctId;

        /** {@code CC-CARD-NUM PIC X(16)} - {@code app/cpy/CVCRD01Y.cpy:37}. */
        private String ccCardNum;

        /** {@code CDEMO-ACCT-ID PIC 9(11)} of {@code app/cpy/COCOM01Y.cpy}, zero when unset. */
        private long commAreaAccountId;

        /** {@code CDEMO-CARD-NUM PIC 9(16)} of {@code app/cpy/COCOM01Y.cpy}, zero when unset. */
        private long commAreaCardNumber;

        /** {@code EIBCALEN IS NOT EQUAL TO 0} - {@code app/cbl/COCRDSLC.cbl:268} and {@code :459}. */
        private boolean commAreaPresent;

        /** {@code CDEMO-PGM-CONTEXT} of {@code app/cpy/COCOM01Y.cpy}, mutated by {@code :567}. */
        private EntryMode entryMode;

        /** {@code CDEMO-FROM-PROGRAM} of {@code app/cpy/COCOM01Y.cpy}. */
        private String fromProgram;

        /** {@code CDEMO-FROM-TRANID} of {@code app/cpy/COCOM01Y.cpy}. */
        private String fromTransactionId;

        /** {@code CDEMO-LAST-MAPSET} of {@code app/cpy/COCOM01Y.cpy}, read at {@code :505} and {@code :527}. */
        private String lastMapset;

        /** {@code CDEMO-TO-PROGRAM} of {@code app/cpy/COCOM01Y.cpy}, set at {@code :318} or {@code :320}. */
        private String toProgram;

        /** {@code CDEMO-TO-TRANID} of {@code app/cpy/COCOM01Y.cpy}, set at {@code :311} or {@code :313}. */
        private String toTransactionId;

        /** {@code CCARD-NEXT-PROG PIC X(8)} - {@code app/cpy/CVCRD01Y.cpy:21}, set at {@code :588}. */
        private String nextProgram;

        /** {@code CCARD-NEXT-MAPSET PIC X(7)} - {@code app/cpy/CVCRD01Y.cpy:23}. */
        private String nextMapset;

        /** {@code CCARD-NEXT-MAP PIC X(7)} - {@code app/cpy/CVCRD01Y.cpy:24}. */
        private String nextMap;

        /**
         * The terminal input buffer {@code EXEC CICS RECEIVE MAP} reads at
         * {@code app/cbl/COCRDSLC.cbl:597-602}, holding the account filter as the operator supplied it and
         * before any move has shortened it.
         */
        private String rawAccountFilter;

        /** The same buffer's card filter. */
        private String rawCardFilter;

        /** {@code ACCTSIDI PIC X(11)} of {@code app/cpy-bms/COCRDSL.CPY:60}. */
        private String accountFilterIn;

        /** {@code CARDSIDI PIC X(16)} of {@code app/cpy-bms/COCRDSL.CPY:66}. */
        private String cardFilterIn;

        /** {@code TRNNAMEO} - the four-character transaction name every CardDemo header carries. */
        private String transactionNameOut;

        /** {@code TITLE01O} - forty characters from {@code CCDA-TITLE01}. */
        private String title01Out;

        /** {@code CURDATEO} - eight characters, {@code MM/DD/YY}. */
        private String currentDateOut;

        /** {@code PGMNAMEO} - the eight-character program name. */
        private String programNameOut;

        /** {@code TITLE02O} - forty characters from {@code CCDA-TITLE02}. */
        private String title02Out;

        /** {@code CURTIMEO} - eight characters, {@code HH:MM:SS}. */
        private String currentTimeOut;

        /** {@code ACCTSIDO} - the echoed account filter, or the {@code '*'} marker. */
        private String accountFilterOut;

        /** {@code CARDSIDO} - the echoed card filter, or the {@code '*'} marker. */
        private String cardFilterOut;

        /** {@code CRDNAMEO PIC X(50)} - the embossed name, moved at {@code :475-476}. */
        private String cardholderNameOut;

        /** {@code CRDSTCDO PIC X(1)} - the active status, moved at {@code :484}. */
        private String cardStatusOut;

        /** {@code EXPMONO PIC X(2)} - the expiry month, moved at {@code :480}. */
        private String expiryMonthOut;

        /** {@code EXPYEARO PIC X(4)} - the expiry year, moved at {@code :482}. */
        private String expiryYearOut;

        /** {@code INFOMSGO PIC X(40)} - the information message, moved at {@code :496}. */
        private String infoMessageOut;

        /** {@code ERRMSGO PIC X(80)} - the error message, moved at {@code :494}. */
        private String errorMessageOut;

        /**
         * {@code FKEYSO PIC X(75)} of {@code app/cpy-bms/COCRDSL.CPY:108}.
         *
         * <p>It stays unset for the whole run: a search of {@code app/cbl/COCRDSLC.cbl} for
         * {@code FKEYS} returns no occurrence at all, so the program never moves anything into it and
         * {@code MOVE LOW-VALUES TO CCRDSLAO} at {@code :428} is the last thing to touch it. Carrying it as
         * unset is the faithful state, and populating it would invent output the source does not produce.
         */
        private String functionKeysOut;

        /**
         * {@code CARD-EXPIRAION-DATE-X} - the redefinition at {@code app/cbl/COCRDSLC.cbl:84-92} that the
         * ten-byte date is moved into at {@code :477-478} before its components are taken.
         */
        private String cardExpirationDateX;

        /**
         * {@code CARD-RECORD} - the {@code app/cpy/CVACT02Y.cpy} area the read fills at {@code :746}.
         *
         * <p>The verification value the record carries is never read from it, never projected and never
         * logged. The expiry day is not read either, even though the redefinition above extracts it.
         */
        private Card cardRecord;

        /** {@code WS-CARD-RID-CARDNUM PIC X(16)} - {@code app/cbl/COCRDSLC.cbl:98}, the read's key. */
        private String cardRecordIdCardNumber;

        /** {@code WS-RESP-CD} as far as this program tests it - see {@link ReadOutcome}. */
        private ReadOutcome readOutcome;

        /**
         * The failure behind {@link ReadOutcome#OTHER}, carried so the boundary can preserve it as the
         * cause. There is no COBOL counterpart: a CICS response code is a number, not an object, and
         * clause B requires the root cause to survive.
         */
        private RuntimeException readFailureCause;

        /** {@code ERROR-OPNAME PIC X(8)} - {@code app/cbl/COCRDSLC.cbl:105}, set at {@code :767}. */
        private String errorOperationName;

        /** {@code ERROR-FILE PIC X(9)} - {@code app/cbl/COCRDSLC.cbl:109}, set at {@code :768}. */
        private String errorFileName;

        /** {@code ERROR-RESP PIC X(10)} - {@code app/cbl/COCRDSLC.cbl:114}, set at {@code :769}. */
        private String errorResp;

        /** {@code ERROR-RESP2 PIC X(10)} - {@code app/cbl/COCRDSLC.cbl:118}, set at {@code :770}. */
        private String errorResp2;

        /** {@code ABEND-CODE PIC X(4)} - {@code app/cpy/CSMSG02Y.cpy}, set at {@code :375}. */
        private String abendCode;

        /** {@code ABEND-CULPRIT PIC X(8)} - {@code app/cpy/CSMSG02Y.cpy}, set at {@code :374}. */
        private String abendCulprit;

        /** {@code ABEND-REASON PIC X(50)} - {@code app/cpy/CSMSG02Y.cpy}, set at {@code :376}. */
        private String abendReason;

        /**
         * The name of the field the cursor would have been positioned on -
         * {@code app/cbl/COCRDSLC.cbl:515-524}. A name only; the value is never carried.
         */
        private String cursorField;

        /** Whether the two filter fields would have been protected - {@code app/cbl/COCRDSLC.cbl:505-512}. */
        private boolean filtersProtected;

        /**
         * The projection assembled where {@code EXEC CICS SEND MAP} would have transmitted the map -
         * {@code app/cbl/COCRDSLC.cbl:569-576}.
         */
        private CardDto detail;

        /**
         * Which field the recorded message is attributable to, or {@code null} when it is not attributable
         * to one. It has no COBOL counterpart: the source encodes the same information in the cursor
         * position and the {@code '*'} marker, neither of which survives into a stateless response, so the
         * field name is tracked explicitly for the boundary to report. The <i>name</i> only.
         */
        private String failingField;

        /**
         * Whether the attributed field was blank or invalid, so the boundary can raise the matching kind.
         * Only {@link FilterState#BLANK} and {@link FilterState#NOT_OK} occur here.
         */
        private FilterState failingKind;

        /**
         * Whether {@code 88 FOUND-CARDS-FOR-ACCOUNT} holds - {@code app/cbl/COCRDSLC.cbl:129-130}.
         *
         * <p>A comparison of {@link #infoMessage} against the condition name's own literal, because that is
         * literally what the COBOL test is. Deriving it rather than storing a parallel boolean is what
         * guarantees the two cannot disagree - and they genuinely share a field, so setting the prompt
         * message really does clear the found state.
         *
         * @return {@code true} when a card was read successfully.
         */
        private boolean foundCardsForAccount() {
            return DISPLAYING_DETAILS_MESSAGE.equals(infoMessage);
        }

        /**
         * Whether {@code 88 WS-NO-INFO-MESSAGE VALUES SPACES LOW-VALUES} holds -
         * {@code app/cbl/COCRDSLC.cbl:127-128}, tested at {@code :490} and {@code :553}.
         *
         * @return {@code true} when no information message has been set.
         */
        private boolean noInfoMessage() {
            return isUnsetOrSpaces(infoMessage);
        }

        /**
         * Reproduces {@code MOVE LOW-VALUES TO CCRDSLAO} at {@code app/cbl/COCRDSLC.cbl:428}.
         *
         * <p>Every output field returns to unset rather than to blank, because that is what
         * {@code LOW-VALUES} means to BMS - transmit nothing for this field - and because the difference is
         * exactly what the {@code '*'} marker relies on.
         */
        private void clearOutputMap() {
            transactionNameOut = null;
            title01Out = null;
            currentDateOut = null;
            programNameOut = null;
            title02Out = null;
            currentTimeOut = null;
            accountFilterOut = null;
            cardFilterOut = null;
            cardholderNameOut = null;
            cardStatusOut = null;
            expiryMonthOut = null;
            expiryYearOut = null;
            infoMessageOut = null;
            errorMessageOut = null;
            functionKeysOut = null;
        }
    }
}

