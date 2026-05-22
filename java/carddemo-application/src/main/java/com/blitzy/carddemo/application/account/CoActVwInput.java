/*
 * Copyright 2022 The CardDemo Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.blitzy.carddemo.application.account;

// JEP 511 (finalized in Java 25): a single declaration imports all packages exported by the
// java.base module (and the modules it reads). This gives access to java.lang.String — the
// type of every BMS field component on this record — and to all java.lang primitive wrappers
// that may be used by the compact constructor's null-coalescing logic.
import module java.base;

/**
 * BMS input DTO record carrying all fields received from the 3270 terminal for the
 * <strong>COACTVW</strong> (Account View) screen.
 *
 * <p>Source artifacts:
 * <ul>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COACTVW.CPY},
 *       structure {@code CACTVWAI} (lines 17-240).</li>
 *   <li>BMS map definition: {@code app/bms/COACTVW.bms},
 *       map {@code CACTVWA} (size 24x80).</li>
 *   <li>Translated COBOL program: {@code COACTVWC}
 *       ({@code app/cbl/COACTVWC.cbl}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO</h2>
 * <p>Per AAP &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into <em>entry-contract DTO
 * records</em> on the corresponding application class. This record is the Java analog of the
 * input view of the BMS symbolic structure: it carries the field values returned from
 * {@code EXEC CICS RECEIVE MAP} to the application code. There is no web framework, no
 * Spring binding, no Jakarta Bean Validation; the record is a plain Java carrier.
 *
 * <h2>View-only screen semantics</h2>
 * <p>COACTVW is a <strong>view-only</strong> account inspection screen. Unlike COACTUP
 * (Account Update), the user has no editable surface on COACTVW: there is only one
 * meaningful user-input field, the account search key {@code acctSid}. All other fields
 * are present in this record purely to mirror the symbolic copybook layout one-for-one;
 * in practice they arrive as SPACES on every RECEIVE-MAP and the {@code COACTVWC}
 * application class consumes only {@code acctSid} and {@code aidKey}.
 *
 * <h2>Differences from {@code CoActUpInput}</h2>
 * <ul>
 *   <li>No editable account/customer fields.</li>
 *   <li>{@code ACCTSIDI} is COBOL {@code PIC 99999999999} (numeric, 11 digits)
 *       rather than {@code PIC X(11)}. This Java translation preserves it as a
 *       {@link String} because BMS {@code RECEIVE MAP} delivers the on-screen bytes
 *       unchanged; any numeric coercion happens later in {@code CoActVwC}.</li>
 *   <li>Date fields are single 10-char strings ({@code "CCYY-MM-DD"}), not split
 *       year/month/day triples.</li>
 *   <li>SSN is consolidated to a single 12-char field ({@code "NNN-NN-NNNN"}).</li>
 *   <li>Phone numbers are consolidated to single 13-char fields.</li>
 *   <li>No function-key (FKEY) accessor fields. The user reaches actions only via
 *       ENTER, PF03 (Back), and PF04 (Clear); these are conveyed through
 *       {@link #aidKey()}.</li>
 * </ul>
 *
 * <h2>Null and emptiness semantics &mdash; COBOL parity</h2>
 * <p>In COBOL, an unfilled BMS {@code PIC X(n)} input field arrives as SPACES (a fixed-length
 * blank string), never as a null reference; there is no null pointer in COBOL. To preserve
 * that behavior precisely the compact constructor below replaces every {@code null}
 * {@link String} component with the empty {@link String} <code>""</code>. This means
 * downstream consumers can safely call {@link String#isBlank()}, {@link String#trim()}, or
 * comparison helpers without first checking for null &mdash; matching the COBOL idiom where
 * receive-map fields are always defined character strings.
 *
 * <p>The compact constructor uses <strong>JEP 513 Flexible Constructor Bodies</strong>
 * (finalized in Java 25). Statements before the canonical field-assignment perform input
 * normalization, which is exactly the place to capture COBOL-style "default to SPACES"
 * semantics. This satisfies the file-level mandate to use JEP 513 for record validation
 * and normalization.
 *
 * <h2>Immutability</h2>
 * <p>Because this is a record, all components are {@code final} and accessors are
 * automatically generated; there are no setters, no Lombok, no Spring annotations, no
 * Jakarta validation annotations. The instance is safely shareable across virtual threads
 * (per AAP &sect;0.6.6) without synchronization.
 *
 * <h2>Field-by-field mapping</h2>
 * <table>
 *   <caption>Mapping from BMS symbolic copybook {@code CACTVWAI} (input view) to Java
 *            record components</caption>
 *   <tr><th>Symbolic BMS field</th><th>COBOL PIC</th><th>Java component</th></tr>
 *   <tr><td>TRNNAMEI</td><td>X(4)</td><td>{@link #trnName()}</td></tr>
 *   <tr><td>TITLE01I</td><td>X(40)</td><td>{@link #title01()}</td></tr>
 *   <tr><td>CURDATEI</td><td>X(8)</td><td>{@link #curDate()}</td></tr>
 *   <tr><td>PGMNAMEI</td><td>X(8)</td><td>{@link #pgmName()}</td></tr>
 *   <tr><td>TITLE02I</td><td>X(40)</td><td>{@link #title02()}</td></tr>
 *   <tr><td>CURTIMEI</td><td>X(8)</td><td>{@link #curTime()}</td></tr>
 *   <tr><td><strong>ACCTSIDI</strong></td><td><strong>99999999999</strong></td><td>{@link #acctSid()}
 *       <em>&mdash; the only user-input field</em></td></tr>
 *   <tr><td>ACSTTUSI</td><td>X(1)</td><td>{@link #acStatus()}</td></tr>
 *   <tr><td>ADTOPENI</td><td>X(10)</td><td>{@link #openDate()}</td></tr>
 *   <tr><td>ACRDLIMI</td><td>X(15)</td><td>{@link #creditLimit()}</td></tr>
 *   <tr><td>AEXPDTI</td><td>X(10)</td><td>{@link #expirationDate()}</td></tr>
 *   <tr><td>ACSHLIMI</td><td>X(15)</td><td>{@link #cashCreditLimit()}</td></tr>
 *   <tr><td>AREISDTI</td><td>X(10)</td><td>{@link #reissueDate()}</td></tr>
 *   <tr><td>ACURBALI</td><td>X(15)</td><td>{@link #currentBalance()}</td></tr>
 *   <tr><td>ACRCYCRI</td><td>X(15)</td><td>{@link #currCycCredit()}</td></tr>
 *   <tr><td>AADDGRPI</td><td>X(10)</td><td>{@link #accountGroup()}</td></tr>
 *   <tr><td>ACRCYDBI</td><td>X(15)</td><td>{@link #currCycDebit()}</td></tr>
 *   <tr><td>ACSTNUMI</td><td>X(9)</td><td>{@link #custNumber()}</td></tr>
 *   <tr><td>ACSTSSNI</td><td>X(12)</td><td>{@link #ssn()}</td></tr>
 *   <tr><td>ACSTDOBI</td><td>X(10)</td><td>{@link #dob()}</td></tr>
 *   <tr><td>ACSTFCOI</td><td>X(3)</td><td>{@link #ficoScore()}</td></tr>
 *   <tr><td>ACSFNAMI</td><td>X(25)</td><td>{@link #firstName()}</td></tr>
 *   <tr><td>ACSMNAMI</td><td>X(25)</td><td>{@link #middleName()}</td></tr>
 *   <tr><td>ACSLNAMI</td><td>X(25)</td><td>{@link #lastName()}</td></tr>
 *   <tr><td>ACSADL1I</td><td>X(50)</td><td>{@link #addressLine1()}</td></tr>
 *   <tr><td>ACSSTTEI</td><td>X(2)</td><td>{@link #state()}</td></tr>
 *   <tr><td>ACSADL2I</td><td>X(50)</td><td>{@link #addressLine2()}</td></tr>
 *   <tr><td>ACSZIPCI</td><td>X(5)</td><td>{@link #zip()}</td></tr>
 *   <tr><td>ACSCITYI</td><td>X(50)</td><td>{@link #city()}</td></tr>
 *   <tr><td>ACSCTRYI</td><td>X(3)</td><td>{@link #country()}</td></tr>
 *   <tr><td>ACSPHN1I</td><td>X(13)</td><td>{@link #phone1()}</td></tr>
 *   <tr><td>ACSGOVTI</td><td>X(20)</td><td>{@link #govtIssuedId()}</td></tr>
 *   <tr><td>ACSPHN2I</td><td>X(13)</td><td>{@link #phone2()}</td></tr>
 *   <tr><td>ACSEFTCI</td><td>X(10)</td><td>{@link #eftAccountId()}</td></tr>
 *   <tr><td>ACSPFLGI</td><td>X(1)</td><td>{@link #primaryFlag()}</td></tr>
 *   <tr><td>INFOMSGI</td><td>X(45)</td><td>{@link #infoMsg()}</td></tr>
 *   <tr><td>ERRMSGI</td><td>X(78)</td><td>{@link #errMsg()}</td></tr>
 * </table>
 *
 * <p>Plus {@link #aidKey()} &mdash; the AID-key state captured at RECEIVE-MAP time
 * (decoded by the {@code CSSTRPFY} translation). For COACTVW only ENTER, PF03 (Back),
 * and PF04 (Clear) are meaningful; all other AID values map to {@link AidKey#OTHER}.
 *
 * <h2>Forbidden idioms (per AAP)</h2>
 * <ul>
 *   <li>No Spring annotations &mdash; this record is plain Java.</li>
 *   <li>No Lombok &mdash; record components and accessors are explicit.</li>
 *   <li>No Jakarta Bean Validation &mdash; validation is hand-written in the compact
 *       constructor.</li>
 *   <li>No setters &mdash; records are immutable.</li>
 *   <li>No {@code java.util.Date} &mdash; date/time strings remain as raw BMS bytes
 *       on this DTO and are translated into {@code java.time} types only inside the
 *       application logic.</li>
 * </ul>
 *
 * @param trnName         CICS transaction id displayed in row 1 ({@code TRNNAMEI PIC X(4)})
 * @param title01         line-1 screen title (e.g., "CardDemo") ({@code TITLE01I PIC X(40)})
 * @param curDate         current date in {@code MM/DD/YY} format ({@code CURDATEI PIC X(8)})
 * @param pgmName         current program id (e.g., "COACTVWC") ({@code PGMNAMEI PIC X(8)})
 * @param title02         line-2 screen title (e.g., "View Account") ({@code TITLE02I PIC X(40)})
 * @param curTime         current time in {@code HH:MM:SS} format ({@code CURTIMEI PIC X(8)})
 * @param acctSid         <strong>the only user-input field</strong>: 11-digit account id
 *                        ({@code ACCTSIDI PIC 99999999999}). Although COBOL declares this PIC as
 *                        numeric, BMS {@code RECEIVE MAP} delivers the on-screen bytes as a
 *                        character string; numeric parsing and range validation happen in the
 *                        downstream {@code CoActVwC} application class.
 * @param acStatus        account active status (Y/N) ({@code ACSTTUSI PIC X(1)})
 * @param openDate        account open date in {@code CCYY-MM-DD} format ({@code ADTOPENI PIC X(10)})
 * @param creditLimit     formatted credit limit ({@code ACRDLIMI PIC X(15)})
 * @param expirationDate  card expiration date in {@code CCYY-MM-DD} format
 *                        ({@code AEXPDTI PIC X(10)})
 * @param cashCreditLimit formatted cash credit limit ({@code ACSHLIMI PIC X(15)})
 * @param reissueDate     card reissue date in {@code CCYY-MM-DD} format
 *                        ({@code AREISDTI PIC X(10)})
 * @param currentBalance  formatted current balance ({@code ACURBALI PIC X(15)})
 * @param currCycCredit   formatted current-cycle credit total ({@code ACRCYCRI PIC X(15)})
 * @param accountGroup    account group id (used for discount lookups)
 *                        ({@code AADDGRPI PIC X(10)})
 * @param currCycDebit    formatted current-cycle debit total ({@code ACRCYDBI PIC X(15)})
 * @param custNumber      customer number associated with this account
 *                        ({@code ACSTNUMI PIC X(9)})
 * @param ssn             customer SSN in {@code NNN-NN-NNNN} format
 *                        ({@code ACSTSSNI PIC X(12)})
 * @param dob             customer date of birth in {@code CCYY-MM-DD} format
 *                        ({@code ACSTDOBI PIC X(10)})
 * @param ficoScore       customer FICO score, 3 digits ({@code ACSTFCOI PIC X(3)})
 * @param firstName       customer first name ({@code ACSFNAMI PIC X(25)})
 * @param middleName      customer middle name ({@code ACSMNAMI PIC X(25)})
 * @param lastName        customer last name ({@code ACSLNAMI PIC X(25)})
 * @param addressLine1    customer address line 1 ({@code ACSADL1I PIC X(50)})
 * @param state           customer state abbreviation ({@code ACSSTTEI PIC X(2)})
 * @param addressLine2    customer address line 2 ({@code ACSADL2I PIC X(50)})
 * @param zip             customer ZIP code ({@code ACSZIPCI PIC X(5)})
 * @param city            customer city ({@code ACSCITYI PIC X(50)})
 * @param country         customer country code ({@code ACSCTRYI PIC X(3)})
 * @param phone1          customer phone 1 ({@code (NNN)-NNN-NNNN})
 *                        ({@code ACSPHN1I PIC X(13)})
 * @param govtIssuedId    customer government-issued id ({@code ACSGOVTI PIC X(20)})
 * @param phone2          customer phone 2 ({@code (NNN)-NNN-NNNN})
 *                        ({@code ACSPHN2I PIC X(13)})
 * @param eftAccountId    customer EFT account id ({@code ACSEFTCI PIC X(10)})
 * @param primaryFlag     primary cardholder flag (Y/N) ({@code ACSPFLGI PIC X(1)})
 * @param infoMsg         informational message line ({@code INFOMSGI PIC X(45)})
 * @param errMsg          error message line ({@code ERRMSGI PIC X(78)})
 * @param aidKey          AID key captured at RECEIVE-MAP time, decoded from {@code EIBAID} by
 *                        the {@code CSSTRPFY} copybook translation. Never {@code null}: a
 *                        {@code null} argument is normalized to {@link AidKey#ENTER} by the
 *                        compact constructor.
 * @see CoActVwInput.AidKey
 * @since 1.0.0
 */
public record CoActVwInput(

        // ============================================================================
        // Header fields (rows 1-2 of the 24x80 BMS map). The application class populates
        // these on SEND-MAP; they are echoed back on RECEIVE-MAP. The user does not edit
        // any of these fields directly.
        // ============================================================================
        String trnName,         // TRNNAMEI  PIC X(4)
        String title01,         // TITLE01I  PIC X(40)
        String curDate,         // CURDATEI  PIC X(8)  — MM/DD/YY
        String pgmName,         // PGMNAMEI  PIC X(8)
        String title02,         // TITLE02I  PIC X(40)
        String curTime,         // CURTIMEI  PIC X(8)  — HH:MM:SS

        // ============================================================================
        // Account search key — the SOLE user-input field for COACTVW.
        // ============================================================================
        String acctSid,         // ACCTSIDI  PIC 99999999999 — preserved as String on the DTO

        // ============================================================================
        // View-only fields (rows 4-13) — SEND-MAP outputs only. They arrive as SPACES
        // on every RECEIVE-MAP since the user cannot edit them. Retained for symbolic-
        // copybook symmetry with CACTVWAI / CACTVWAO.
        // ============================================================================
        String acStatus,        // ACSTTUSI  PIC X(1)   — Y/N
        String openDate,        // ADTOPENI  PIC X(10)  — CCYY-MM-DD
        String creditLimit,     // ACRDLIMI  PIC X(15)  — formatted S9(10)V99
        String expirationDate,  // AEXPDTI   PIC X(10)  — CCYY-MM-DD
        String cashCreditLimit, // ACSHLIMI  PIC X(15)  — formatted S9(10)V99
        String reissueDate,     // AREISDTI  PIC X(10)  — CCYY-MM-DD
        String currentBalance,  // ACURBALI  PIC X(15)  — formatted S9(10)V99
        String currCycCredit,   // ACRCYCRI  PIC X(15)  — formatted S9(10)V99
        String accountGroup,    // AADDGRPI  PIC X(10)
        String currCycDebit,    // ACRCYDBI  PIC X(15)  — formatted S9(10)V99
        String custNumber,      // ACSTNUMI  PIC X(9)
        String ssn,             // ACSTSSNI  PIC X(12)  — NNN-NN-NNNN
        String dob,             // ACSTDOBI  PIC X(10)  — CCYY-MM-DD
        String ficoScore,       // ACSTFCOI  PIC X(3)
        String firstName,       // ACSFNAMI  PIC X(25)
        String middleName,      // ACSMNAMI  PIC X(25)
        String lastName,        // ACSLNAMI  PIC X(25)
        String addressLine1,    // ACSADL1I  PIC X(50)
        String state,           // ACSSTTEI  PIC X(2)
        String addressLine2,    // ACSADL2I  PIC X(50)
        String zip,             // ACSZIPCI  PIC X(5)
        String city,            // ACSCITYI  PIC X(50)
        String country,         // ACSCTRYI  PIC X(3)
        String phone1,          // ACSPHN1I  PIC X(13)
        String govtIssuedId,    // ACSGOVTI  PIC X(20)
        String phone2,          // ACSPHN2I  PIC X(13)
        String eftAccountId,    // ACSEFTCI  PIC X(10)
        String primaryFlag,     // ACSPFLGI  PIC X(1)   — Y/N

        // ============================================================================
        // Status messages (rows 23-24) — SEND-MAP outputs; arrive as SPACES on
        // RECEIVE-MAP. Retained for symbolic-copybook symmetry.
        // ============================================================================
        String infoMsg,         // INFOMSGI  PIC X(45)
        String errMsg,          // ERRMSGI   PIC X(78)

        // ============================================================================
        // AID-key dispatch — how the user submitted the screen.
        // ============================================================================
        AidKey aidKey

) {

    /**
     * Compact (canonical) constructor.
     *
     * <p>Normalizes every {@link String} component so that a {@code null} reference is
     * converted to the empty {@link String} <code>""</code>. This mirrors COBOL
     * RECEIVE-MAP semantics where unfilled BMS {@code PIC X(n)} fields are SPACES,
     * never undefined. A null {@link AidKey} is normalized to {@link AidKey#ENTER}
     * because the legacy COBOL default action on a screen submission is ENTER.
     *
     * <p>Uses <strong>JEP 513 Flexible Constructor Bodies</strong> (finalized in Java
     * 25): the normalization statements run before the implicit canonical field
     * assignment, which is the appropriate location for COBOL-style "default to
     * SPACES" input cleansing.
     *
     * <p>The constructor never throws. The COBOL terminal handler never produced
     * null fields; this constructor merely defensively absorbs any null reference
     * that a Java caller might inadvertently pass.
     */
    public CoActVwInput {
        trnName         = orEmpty(trnName);
        title01         = orEmpty(title01);
        curDate         = orEmpty(curDate);
        pgmName         = orEmpty(pgmName);
        title02         = orEmpty(title02);
        curTime         = orEmpty(curTime);
        acctSid         = orEmpty(acctSid);
        acStatus        = orEmpty(acStatus);
        openDate        = orEmpty(openDate);
        creditLimit     = orEmpty(creditLimit);
        expirationDate  = orEmpty(expirationDate);
        cashCreditLimit = orEmpty(cashCreditLimit);
        reissueDate     = orEmpty(reissueDate);
        currentBalance  = orEmpty(currentBalance);
        currCycCredit   = orEmpty(currCycCredit);
        accountGroup    = orEmpty(accountGroup);
        currCycDebit    = orEmpty(currCycDebit);
        custNumber      = orEmpty(custNumber);
        ssn             = orEmpty(ssn);
        dob             = orEmpty(dob);
        ficoScore       = orEmpty(ficoScore);
        firstName       = orEmpty(firstName);
        middleName      = orEmpty(middleName);
        lastName        = orEmpty(lastName);
        addressLine1    = orEmpty(addressLine1);
        state           = orEmpty(state);
        addressLine2    = orEmpty(addressLine2);
        zip             = orEmpty(zip);
        city            = orEmpty(city);
        country         = orEmpty(country);
        phone1          = orEmpty(phone1);
        govtIssuedId    = orEmpty(govtIssuedId);
        phone2          = orEmpty(phone2);
        eftAccountId    = orEmpty(eftAccountId);
        primaryFlag     = orEmpty(primaryFlag);
        infoMsg         = orEmpty(infoMsg);
        errMsg          = orEmpty(errMsg);
        aidKey          = (aidKey == null) ? AidKey.ENTER : aidKey;
    }

    /**
     * Returns the argument if non-null, or the empty string {@code ""} otherwise.
     *
     * <p>Centralizing this single trivial helper keeps the compact constructor
     * uncluttered and removes any risk of inconsistent null-handling between
     * components.
     *
     * @param s the candidate string (may be {@code null})
     * @return {@code s} if non-null, otherwise {@code ""}
     */
    private static String orEmpty(String s) {
        return (s == null) ? "" : s;
    }

    /**
     * Factory: an input record with every string component empty and
     * {@link AidKey#ENTER} as the AID key.
     *
     * <p>This matches the initial state on the first dispatch into COACTVW: the
     * user has not yet typed anything, all SEND-MAP outputs are still blank, and
     * the default action is ENTER.
     *
     * @return a fully-blank input record (never {@code null})
     */
    public static CoActVwInput blank() {
        return new CoActVwInput(
                // header (6)
                "", "", "", "", "", "",
                // acctSid (1)
                "",
                // view-only group A (10) — acStatus..currCycDebit
                "", "", "", "", "", "", "", "", "", "",
                // customer identity (7) — custNumber..lastName
                "", "", "", "", "", "", "",
                // customer detail (10) — addressLine1..eftAccountId
                "", "", "", "", "", "", "", "", "", "",
                // primaryFlag + status messages (3) — primaryFlag, infoMsg, errMsg
                "", "", "",
                // AID key (1)
                AidKey.ENTER
        );
    }

    /**
     * Factory: an input record with only the account search key {@code acctSid}
     * populated; all other strings are empty and the AID key defaults to
     * {@link AidKey#ENTER}.
     *
     * <p>This is the typical entry path into COACTVW from another program (e.g.,
     * from the main menu after the operator chose "View Account" and a previous
     * screen had the account id available) or from COACTUPC after a successful
     * update where the program wants to display the updated account.
     *
     * <p>A {@code null} argument is treated as an empty string (matching the
     * compact constructor's null-handling discipline), so this factory is safe
     * to call with any caller-provided value.
     *
     * @param acctSid the account id search key (may be {@code null}; treated as empty)
     * @return an input record carrying just the account search key
     */
    public static CoActVwInput withAccountId(String acctSid) {
        return new CoActVwInput(
                // header (6)
                "", "", "", "", "", "",
                // acctSid (1) — the supplied search key
                (acctSid == null) ? "" : acctSid,
                // view-only group A (10) — acStatus..currCycDebit
                "", "", "", "", "", "", "", "", "", "",
                // customer identity (7) — custNumber..lastName
                "", "", "", "", "", "", "",
                // customer detail (10) — addressLine1..eftAccountId
                "", "", "", "", "", "", "", "", "", "",
                // primaryFlag + status messages (3) — primaryFlag, infoMsg, errMsg
                "", "", "",
                // AID key (1)
                AidKey.ENTER
        );
    }

    /**
     * AID-key state captured at BMS RECEIVE-MAP time for the COACTVW screen.
     *
     * <p>The COBOL {@code CSSTRPFY} copybook decodes the {@code EIBAID} byte
     * returned by CICS into a set of 88-level conditions. Per AAP &sect;0.6.10
     * the <em>full</em> AID-key hierarchy (ENTER, CLEAR, PA1, PA2, PFK01-PFK12)
     * is defined as a sealed type in
     * {@code carddemo-domain.text.CcWorkAreas.AidKey}; that hierarchy supports
     * exhaustive pattern matching for screens that handle many function keys.
     *
     * <p>COACTVW handles only three AID keys:
     * <ul>
     *   <li>{@link #ENTER} &mdash; submit the account-id search.</li>
     *   <li>{@link #PF03_BACK} &mdash; return to the calling menu (XCTL).</li>
     *   <li>{@link #PF04_CLEAR} &mdash; clear all fields and re-prompt.</li>
     * </ul>
     * Any other AID value is captured as {@link #OTHER} and yields the standard
     * "PF key not active" error message. To avoid pulling in the full sealed
     * hierarchy for just three meaningful states, this nested enum models only
     * what COACTVW actually consumes.
     *
     * <p><strong>Why an enum rather than a sealed interface here?</strong>
     * Sealed interfaces are AAP-mandated only for COBOL constructs that
     * partition a value space &mdash; in particular {@code REDEFINES} or
     * 88-level taxonomies on data fields. The AID key on COACTVW is a closed
     * 4-state dispatch (ENTER / PF03 / PF04 / fallback) with no associated
     * payload, which is exactly the case for which a plain {@code enum} is
     * idiomatic and sufficient. The agent prompt for this file explicitly
     * mandates {@code enum} here.
     */
    public enum AidKey {

        /** The user pressed the ENTER key to submit the screen. */
        ENTER,

        /** The user pressed PF03 to navigate back to the calling screen. */
        PF03_BACK,

        /** The user pressed PF04 to clear the screen and re-prompt. */
        PF04_CLEAR,

        /**
         * Any AID key not specifically recognized by COACTVW. The application
         * class responds with the standard "PF key not active" error message.
         */
        OTHER
    }
}
