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
// type of every BMS input-field component on this record — and to all java.lang primitive
// wrappers that may be referenced by the compact constructor's null-coalescing logic.
import module java.base;

// Module-import declarations may not import application-defined types; the COBOL traceability
// annotation lives in carddemo-domain and must be brought in by a conventional import.
import com.blitzy.carddemo.domain.annotation.CobolProgram;

/**
 * BMS input DTO record carrying all fields received from the 3270 terminal for the
 * <strong>COACTUP</strong> (Account Update) screen.
 *
 * <p>Source artifacts:
 * <ul>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COACTUP.CPY},
 *       structure {@code CACTUPAI} (lines 17-342).</li>
 *   <li>BMS map definition: {@code app/bms/COACTUP.bms},
 *       map {@code CACTUPA} (size 24x80, FREEKB).</li>
 *   <li>Translated COBOL program: {@code COACTUPC}
 *       ({@code app/cbl/COACTUPC.cbl}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO</h2>
 * <p>Per AAP &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into <em>entry-contract DTO
 * records</em> on the corresponding application class. This record is the Java analog of
 * the input view ({@code CACTUPAI}) of the BMS symbolic structure: it carries the field
 * values returned from {@code EXEC CICS RECEIVE MAP} to the application code. There is no
 * web framework, no Spring binding, no Jakarta Bean Validation; the record is a plain Java
 * carrier with strictly final components and a single normalizing constructor.
 *
 * <h2>Account-update screen semantics &mdash; difference from COACTVW</h2>
 * <p>Unlike the view-only COACTVW screen ({@link CoActVwInput}), COACTUP is the
 * <strong>update</strong> screen for accounts. Nearly every account and customer field is
 * <em>editable</em> by the operator, so this DTO carries inputs for the full account record
 * (status, credit limits, dates, balances) <em>and</em> the full customer demographic record
 * (name, address, SSN, DOB, phone, FICO score, government id, EFT account id, primary flag).
 *
 * <p>Notable shape differences relative to {@link CoActVwInput}:
 * <ul>
 *   <li><strong>Dates are split year/month/day triples.</strong> COACTUP exposes the
 *       account-open, card-expiration, card-reissue, and customer date-of-birth as three
 *       separate BMS input fields each (e.g., {@code OPNYEARI/OPNMONI/OPNDAYI}). This matches
 *       the on-screen layout, where the operator types the year, month, and day in three
 *       distinct positions on the 3270 panel. Translation to {@link java.time.LocalDate}
 *       happens later inside {@code CoActUpC} (1200-EDIT-MAP-INPUTS), not here.</li>
 *   <li><strong>SSN is split.</strong> {@code ACTSSN1I/ACTSSN2I/ACTSSN3I} carry the 3-2-4
 *       digit groups separately. The dashes are rendered on the panel as
 *       {@code DFHMDF} constants, not stored.</li>
 *   <li><strong>Phone numbers are split.</strong> Two phones are present, each with
 *       area code (3), middle (3), and last (4) components.</li>
 *   <li><strong>Function-key panel labels are present.</strong> {@code FKEYSI},
 *       {@code FKEY05I}, and {@code FKEY12I} are echo-back text lines used by the BMS map
 *       to show "PF05=Save  PF12=Cancel" on the bottom border. They are received on the
 *       way back but never validated.</li>
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
 * <h2>Where validation happens</h2>
 * <p>This compact constructor performs <strong>no semantic validation</strong>: it does not
 * check that {@code openYear} is numeric, that {@code state} is a US state abbreviation,
 * that {@code zip} is 5 digits, or that {@code creditLimit} parses as a {@link
 * java.math.BigDecimal}. All such validation is the responsibility of {@code
 * CoActUpC.editMapInputs(...)} (the Java translation of the {@code 1200-EDIT-MAP-INPUTS}
 * paragraph). Putting validation here would diverge from the COBOL flow, where the receive
 * itself is unconditional and the dedicated edit paragraph subsequently inspects each field
 * and accumulates per-field error indicators. We preserve that separation of concerns.
 *
 * <h2>Immutability</h2>
 * <p>Because this is a record, all components are {@code final} and accessors are
 * automatically generated; there are no setters, no Lombok, no Spring annotations, no
 * Jakarta validation annotations. The instance is safely shareable across virtual threads
 * (per AAP &sect;0.6.6) without synchronization.
 *
 * <h2>Field-by-field mapping</h2>
 * <table>
 *   <caption>Mapping from BMS symbolic copybook {@code CACTUPAI} (input view, lines
 *            17-342) to Java record components. PIC column shows the COBOL PICTURE
 *            clause; lengths are fixed and preserved by the runtime that converts the
 *            3270 data stream into this Java record.</caption>
 *   <tr><th>Symbolic BMS field</th><th>COBOL PIC</th><th>Java component</th></tr>
 *   <tr><td>TRNNAMEI</td><td>X(4)</td><td>{@link #trnName()}</td></tr>
 *   <tr><td>TITLE01I</td><td>X(40)</td><td>{@link #title01()}</td></tr>
 *   <tr><td>CURDATEI</td><td>X(8)</td><td>{@link #curDate()}</td></tr>
 *   <tr><td>PGMNAMEI</td><td>X(8)</td><td>{@link #pgmName()}</td></tr>
 *   <tr><td>TITLE02I</td><td>X(40)</td><td>{@link #title02()}</td></tr>
 *   <tr><td>CURTIMEI</td><td>X(8)</td><td>{@link #curTime()}</td></tr>
 *   <tr><td>ACCTSIDI</td><td>X(11)</td><td>{@link #acctSid()}</td></tr>
 *   <tr><td>ACSTTUSI</td><td>X(1)</td><td>{@link #acStatus()}</td></tr>
 *   <tr><td>OPNYEARI</td><td>X(4)</td><td>{@link #openYear()}</td></tr>
 *   <tr><td>OPNMONI</td><td>X(2)</td><td>{@link #openMonth()}</td></tr>
 *   <tr><td>OPNDAYI</td><td>X(2)</td><td>{@link #openDay()}</td></tr>
 *   <tr><td>ACRDLIMI</td><td>X(15)</td><td>{@link #creditLimit()}</td></tr>
 *   <tr><td>EXPYEARI</td><td>X(4)</td><td>{@link #expirationYear()}</td></tr>
 *   <tr><td>EXPMONI</td><td>X(2)</td><td>{@link #expirationMonth()}</td></tr>
 *   <tr><td>EXPDAYI</td><td>X(2)</td><td>{@link #expirationDay()}</td></tr>
 *   <tr><td>ACSHLIMI</td><td>X(15)</td><td>{@link #cashCreditLimit()}</td></tr>
 *   <tr><td>RISYEARI</td><td>X(4)</td><td>{@link #reissueYear()}</td></tr>
 *   <tr><td>RISMONI</td><td>X(2)</td><td>{@link #reissueMonth()}</td></tr>
 *   <tr><td>RISDAYI</td><td>X(2)</td><td>{@link #reissueDay()}</td></tr>
 *   <tr><td>ACURBALI</td><td>X(15)</td><td>{@link #currentBalance()}</td></tr>
 *   <tr><td>ACRCYCRI</td><td>X(15)</td><td>{@link #currCycCredit()}</td></tr>
 *   <tr><td>AADDGRPI</td><td>X(10)</td><td>{@link #accountGroup()}</td></tr>
 *   <tr><td>ACRCYDBI</td><td>X(15)</td><td>{@link #currCycDebit()}</td></tr>
 *   <tr><td>ACSTNUMI</td><td>X(9)</td><td>{@link #custNumber()}</td></tr>
 *   <tr><td>ACTSSN1I</td><td>X(3)</td><td>{@link #ssn1()}</td></tr>
 *   <tr><td>ACTSSN2I</td><td>X(2)</td><td>{@link #ssn2()}</td></tr>
 *   <tr><td>ACTSSN3I</td><td>X(4)</td><td>{@link #ssn3()}</td></tr>
 *   <tr><td>DOBYEARI</td><td>X(4)</td><td>{@link #dobYear()}</td></tr>
 *   <tr><td>DOBMONI</td><td>X(2)</td><td>{@link #dobMonth()}</td></tr>
 *   <tr><td>DOBDAYI</td><td>X(2)</td><td>{@link #dobDay()}</td></tr>
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
 *   <tr><td>ACSPH1AI</td><td>X(3)</td><td>{@link #phone1Area()}</td></tr>
 *   <tr><td>ACSPH1BI</td><td>X(3)</td><td>{@link #phone1Mid()}</td></tr>
 *   <tr><td>ACSPH1CI</td><td>X(4)</td><td>{@link #phone1End()}</td></tr>
 *   <tr><td>ACSGOVTI</td><td>X(20)</td><td>{@link #govtIssuedId()}</td></tr>
 *   <tr><td>ACSPH2AI</td><td>X(3)</td><td>{@link #phone2Area()}</td></tr>
 *   <tr><td>ACSPH2BI</td><td>X(3)</td><td>{@link #phone2Mid()}</td></tr>
 *   <tr><td>ACSPH2CI</td><td>X(4)</td><td>{@link #phone2End()}</td></tr>
 *   <tr><td>ACSEFTCI</td><td>X(10)</td><td>{@link #eftAccountId()}</td></tr>
 *   <tr><td>ACSPFLGI</td><td>X(1)</td><td>{@link #primaryFlag()}</td></tr>
 *   <tr><td>INFOMSGI</td><td>X(45)</td><td>{@link #infoMsg()}</td></tr>
 *   <tr><td>ERRMSGI</td><td>X(78)</td><td>{@link #errMsg()}</td></tr>
 *   <tr><td>FKEYSI</td><td>X(21)</td><td>{@link #fKeys()}</td></tr>
 *   <tr><td>FKEY05I</td><td>X(7)</td><td>{@link #fKey05()}</td></tr>
 *   <tr><td>FKEY12I</td><td>X(10)</td><td>{@link #fKey12()}</td></tr>
 * </table>
 *
 * <p>Plus {@link #aidKey()} &mdash; the AID-key state captured at RECEIVE-MAP time
 * (decoded by the {@code CSSTRPFY} translation). For COACTUP the operator can submit with
 * ENTER (no-op refresh), PF03 (back to caller), PF04 (clear and re-prompt), PF05 (save the
 * edited account), or PF12 (cancel and discard pending edits); all other AID values map
 * to {@link AidKey#OTHER}.
 *
 * <h2>Forbidden idioms (per AAP)</h2>
 * <ul>
 *   <li>No Spring annotations &mdash; this record is plain Java.</li>
 *   <li>No Lombok &mdash; record components and accessors are explicit.</li>
 *   <li>No Jakarta Bean Validation &mdash; validation is delegated to
 *       {@code CoActUpC.editMapInputs(...)}.</li>
 *   <li>No setters &mdash; records are immutable.</li>
 *   <li>No {@code java.util.Date} &mdash; date/time strings remain as raw BMS bytes
 *       on this DTO and are translated into {@code java.time} types only inside the
 *       application logic.</li>
 * </ul>
 *
 * @param trnName          CICS transaction id displayed in row 1 ({@code TRNNAMEI PIC X(4)})
 * @param title01          line-1 screen title (e.g., "CardDemo") ({@code TITLE01I PIC X(40)})
 * @param curDate          current date in {@code MM/DD/YY} format ({@code CURDATEI PIC X(8)})
 * @param pgmName          current program id (e.g., "COACTUPC") ({@code PGMNAMEI PIC X(8)})
 * @param title02          line-2 screen title (e.g., "Update Account")
 *                         ({@code TITLE02I PIC X(40)})
 * @param curTime          current time in {@code HH:MM:SS} format
 *                         ({@code CURTIMEI PIC X(8)})
 * @param acctSid          11-digit account id search key ({@code ACCTSIDI PIC X(11)}). Although
 *                         the on-screen field is numeric, BMS {@code RECEIVE MAP} delivers the
 *                         on-screen bytes as a character string; numeric parsing happens in
 *                         the downstream {@code CoActUpC} application class.
 * @param acStatus         account active status, expected {@code Y} or {@code N}
 *                         ({@code ACSTTUSI PIC X(1)})
 * @param openYear         account open-date year (4 digits) ({@code OPNYEARI PIC X(4)})
 * @param openMonth        account open-date month (2 digits) ({@code OPNMONI PIC X(2)})
 * @param openDay          account open-date day (2 digits) ({@code OPNDAYI PIC X(2)})
 * @param creditLimit      formatted credit limit (signed-decimal text)
 *                         ({@code ACRDLIMI PIC X(15)})
 * @param expirationYear   card expiration year (4 digits) ({@code EXPYEARI PIC X(4)})
 * @param expirationMonth  card expiration month (2 digits) ({@code EXPMONI PIC X(2)})
 * @param expirationDay    card expiration day (2 digits) ({@code EXPDAYI PIC X(2)})
 * @param cashCreditLimit  formatted cash credit limit ({@code ACSHLIMI PIC X(15)})
 * @param reissueYear      card reissue year (4 digits) ({@code RISYEARI PIC X(4)})
 * @param reissueMonth     card reissue month (2 digits) ({@code RISMONI PIC X(2)})
 * @param reissueDay       card reissue day (2 digits) ({@code RISDAYI PIC X(2)})
 * @param currentBalance   formatted current balance ({@code ACURBALI PIC X(15)})
 * @param currCycCredit    formatted current-cycle credit total
 *                         ({@code ACRCYCRI PIC X(15)})
 * @param accountGroup     account group id (used for discount lookups)
 *                         ({@code AADDGRPI PIC X(10)})
 * @param currCycDebit     formatted current-cycle debit total
 *                         ({@code ACRCYDBI PIC X(15)})
 * @param custNumber       customer number associated with this account
 *                         ({@code ACSTNUMI PIC X(9)})
 * @param ssn1             customer SSN first group, 3 digits ({@code ACTSSN1I PIC X(3)})
 * @param ssn2             customer SSN second group, 2 digits ({@code ACTSSN2I PIC X(2)})
 * @param ssn3             customer SSN third group, 4 digits ({@code ACTSSN3I PIC X(4)})
 * @param dobYear          customer date-of-birth year (4 digits)
 *                         ({@code DOBYEARI PIC X(4)})
 * @param dobMonth         customer date-of-birth month (2 digits)
 *                         ({@code DOBMONI PIC X(2)})
 * @param dobDay           customer date-of-birth day (2 digits)
 *                         ({@code DOBDAYI PIC X(2)})
 * @param ficoScore        customer FICO score, 3 digits ({@code ACSTFCOI PIC X(3)})
 * @param firstName        customer first name ({@code ACSFNAMI PIC X(25)})
 * @param middleName       customer middle name ({@code ACSMNAMI PIC X(25)})
 * @param lastName         customer last name ({@code ACSLNAMI PIC X(25)})
 * @param addressLine1     customer address line 1 ({@code ACSADL1I PIC X(50)})
 * @param state            customer state abbreviation ({@code ACSSTTEI PIC X(2)})
 * @param addressLine2     customer address line 2 ({@code ACSADL2I PIC X(50)})
 * @param zip              customer ZIP code ({@code ACSZIPCI PIC X(5)})
 * @param city             customer city ({@code ACSCITYI PIC X(50)})
 * @param country          customer country code ({@code ACSCTRYI PIC X(3)})
 * @param phone1Area       customer phone 1, area code (3 digits)
 *                         ({@code ACSPH1AI PIC X(3)})
 * @param phone1Mid        customer phone 1, exchange (3 digits)
 *                         ({@code ACSPH1BI PIC X(3)})
 * @param phone1End        customer phone 1, subscriber (4 digits)
 *                         ({@code ACSPH1CI PIC X(4)})
 * @param govtIssuedId     customer government-issued id ({@code ACSGOVTI PIC X(20)})
 * @param phone2Area       customer phone 2, area code (3 digits)
 *                         ({@code ACSPH2AI PIC X(3)})
 * @param phone2Mid        customer phone 2, exchange (3 digits)
 *                         ({@code ACSPH2BI PIC X(3)})
 * @param phone2End        customer phone 2, subscriber (4 digits)
 *                         ({@code ACSPH2CI PIC X(4)})
 * @param eftAccountId     customer EFT account id ({@code ACSEFTCI PIC X(10)})
 * @param primaryFlag      primary cardholder flag, expected {@code Y} or {@code N}
 *                         ({@code ACSPFLGI PIC X(1)})
 * @param infoMsg          informational message line ({@code INFOMSGI PIC X(45)})
 * @param errMsg           error message line ({@code ERRMSGI PIC X(78)})
 * @param fKeys            full function-key legend text rendered along the bottom of the
 *                         BMS panel ({@code FKEYSI PIC X(21)})
 * @param fKey05           PF05 label text ({@code FKEY05I PIC X(7)})
 * @param fKey12           PF12 label text ({@code FKEY12I PIC X(10)})
 * @param aidKey           AID key captured at RECEIVE-MAP time, decoded from {@code EIBAID} by
 *                         the {@code CSSTRPFY} copybook translation. Never {@code null}: a
 *                         {@code null} argument is normalized to {@link AidKey#ENTER} by the
 *                         compact constructor.
 * @see CoActUpInput.AidKey
 * @see CoActVwInput
 * @since 1.0.0
 */
@CobolProgram(
        value = "COACTUP",
        sourcePath = "app/bms/COACTUP.bms",
        translationDate = "2025-10-15",
        notes = "BMS entry-contract DTO (input side); symbolic copybook CACTUPAI "
                + "in app/cpy-bms/COACTUP.CPY lines 17-342. Field-for-field translation "
                + "of all 54 PIC X leaves; date/SSN/phone groups are kept as separate "
                + "components matching the on-screen layout."
)
public record CoActUpInput(

        // ============================================================================
        // Header fields (rows 1-2 of the 24x80 BMS map). The application class populates
        // these on SEND-MAP; they are echoed back on RECEIVE-MAP. The user does not edit
        // any of these fields directly, but they are returned in the receive image.
        // ============================================================================
        String trnName,         // TRNNAMEI  PIC X(4)
        String title01,         // TITLE01I  PIC X(40)
        String curDate,         // CURDATEI  PIC X(8)   — MM/DD/YY
        String pgmName,         // PGMNAMEI  PIC X(8)
        String title02,         // TITLE02I  PIC X(40)
        String curTime,         // CURTIMEI  PIC X(8)   — HH:MM:SS

        // ============================================================================
        // Account search key — used to locate the account record on initial open.
        // ============================================================================
        String acctSid,         // ACCTSIDI  PIC X(11)

        // ============================================================================
        // Editable account fields (rows 4-13). The operator updates these to change
        // the underlying ACCOUNT-RECORD.
        // ============================================================================
        String acStatus,        // ACSTTUSI  PIC X(1)   — Y/N
        String openYear,        // OPNYEARI  PIC X(4)
        String openMonth,       // OPNMONI   PIC X(2)
        String openDay,         // OPNDAYI   PIC X(2)
        String creditLimit,     // ACRDLIMI  PIC X(15)  — formatted S9(10)V99
        String expirationYear,  // EXPYEARI  PIC X(4)
        String expirationMonth, // EXPMONI   PIC X(2)
        String expirationDay,   // EXPDAYI   PIC X(2)
        String cashCreditLimit, // ACSHLIMI  PIC X(15)  — formatted S9(10)V99
        String reissueYear,     // RISYEARI  PIC X(4)
        String reissueMonth,    // RISMONI   PIC X(2)
        String reissueDay,      // RISDAYI   PIC X(2)
        String currentBalance,  // ACURBALI  PIC X(15)  — formatted S9(10)V99
        String currCycCredit,   // ACRCYCRI  PIC X(15)  — formatted S9(10)V99
        String accountGroup,    // AADDGRPI  PIC X(10)
        String currCycDebit,    // ACRCYDBI  PIC X(15)  — formatted S9(10)V99

        // ============================================================================
        // Customer key + identity (rows 14-18). Editable demographic fields that update
        // the customer record associated with this account.
        // ============================================================================
        String custNumber,      // ACSTNUMI  PIC X(9)
        String ssn1,            // ACTSSN1I  PIC X(3)   — SSN  area
        String ssn2,            // ACTSSN2I  PIC X(2)   — SSN  group
        String ssn3,            // ACTSSN3I  PIC X(4)   — SSN  serial
        String dobYear,         // DOBYEARI  PIC X(4)
        String dobMonth,        // DOBMONI   PIC X(2)
        String dobDay,          // DOBDAYI   PIC X(2)
        String ficoScore,       // ACSTFCOI  PIC X(3)
        String firstName,       // ACSFNAMI  PIC X(25)
        String middleName,      // ACSMNAMI  PIC X(25)
        String lastName,        // ACSLNAMI  PIC X(25)

        // ============================================================================
        // Address (rows 19-20).
        // ============================================================================
        String addressLine1,    // ACSADL1I  PIC X(50)
        String state,           // ACSSTTEI  PIC X(2)
        String addressLine2,    // ACSADL2I  PIC X(50)
        String zip,             // ACSZIPCI  PIC X(5)
        String city,            // ACSCITYI  PIC X(50)
        String country,         // ACSCTRYI  PIC X(3)

        // ============================================================================
        // Phone 1 (row 21) — area/middle/end triple.
        // ============================================================================
        String phone1Area,      // ACSPH1AI  PIC X(3)
        String phone1Mid,       // ACSPH1BI  PIC X(3)
        String phone1End,       // ACSPH1CI  PIC X(4)

        // ============================================================================
        // Government-issued id (row 21).
        // ============================================================================
        String govtIssuedId,    // ACSGOVTI  PIC X(20)

        // ============================================================================
        // Phone 2 (row 22) — area/middle/end triple.
        // ============================================================================
        String phone2Area,      // ACSPH2AI  PIC X(3)
        String phone2Mid,       // ACSPH2BI  PIC X(3)
        String phone2End,       // ACSPH2CI  PIC X(4)

        // ============================================================================
        // EFT account + primary flag (row 22).
        // ============================================================================
        String eftAccountId,    // ACSEFTCI  PIC X(10)
        String primaryFlag,     // ACSPFLGI  PIC X(1)   — Y/N

        // ============================================================================
        // Status messages (rows 23-24) — SEND-MAP outputs; arrive as SPACES on
        // RECEIVE-MAP. Retained for symbolic-copybook symmetry with CACTUPAI.
        // ============================================================================
        String infoMsg,         // INFOMSGI  PIC X(45)
        String errMsg,          // ERRMSGI   PIC X(78)

        // ============================================================================
        // Function-key labels (row 24, bottom border). Echoed back on RECEIVE-MAP but
        // never edited by the operator. Retained verbatim.
        // ============================================================================
        String fKeys,           // FKEYSI    PIC X(21)
        String fKey05,          // FKEY05I   PIC X(7)   — e.g., "PF05="
        String fKey12,          // FKEY12I   PIC X(10)  — e.g., "PF12="

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
     * <p>The constructor never throws. Semantic validation (numeric checks, range
     * checks, calendar validity) is the responsibility of
     * {@code CoActUpC.editMapInputs(...)} (the Java translation of the
     * {@code 1200-EDIT-MAP-INPUTS} paragraph). Putting validation here would diverge
     * from the COBOL flow.
     */
    public CoActUpInput {
        trnName          = orEmpty(trnName);
        title01          = orEmpty(title01);
        curDate          = orEmpty(curDate);
        pgmName          = orEmpty(pgmName);
        title02          = orEmpty(title02);
        curTime          = orEmpty(curTime);
        acctSid          = orEmpty(acctSid);
        acStatus         = orEmpty(acStatus);
        openYear         = orEmpty(openYear);
        openMonth        = orEmpty(openMonth);
        openDay          = orEmpty(openDay);
        creditLimit      = orEmpty(creditLimit);
        expirationYear   = orEmpty(expirationYear);
        expirationMonth  = orEmpty(expirationMonth);
        expirationDay    = orEmpty(expirationDay);
        cashCreditLimit  = orEmpty(cashCreditLimit);
        reissueYear      = orEmpty(reissueYear);
        reissueMonth     = orEmpty(reissueMonth);
        reissueDay       = orEmpty(reissueDay);
        currentBalance   = orEmpty(currentBalance);
        currCycCredit    = orEmpty(currCycCredit);
        accountGroup     = orEmpty(accountGroup);
        currCycDebit     = orEmpty(currCycDebit);
        custNumber       = orEmpty(custNumber);
        ssn1             = orEmpty(ssn1);
        ssn2             = orEmpty(ssn2);
        ssn3             = orEmpty(ssn3);
        dobYear          = orEmpty(dobYear);
        dobMonth         = orEmpty(dobMonth);
        dobDay           = orEmpty(dobDay);
        ficoScore        = orEmpty(ficoScore);
        firstName        = orEmpty(firstName);
        middleName       = orEmpty(middleName);
        lastName         = orEmpty(lastName);
        addressLine1     = orEmpty(addressLine1);
        state            = orEmpty(state);
        addressLine2     = orEmpty(addressLine2);
        zip              = orEmpty(zip);
        city             = orEmpty(city);
        country          = orEmpty(country);
        phone1Area       = orEmpty(phone1Area);
        phone1Mid        = orEmpty(phone1Mid);
        phone1End        = orEmpty(phone1End);
        govtIssuedId     = orEmpty(govtIssuedId);
        phone2Area       = orEmpty(phone2Area);
        phone2Mid        = orEmpty(phone2Mid);
        phone2End        = orEmpty(phone2End);
        eftAccountId     = orEmpty(eftAccountId);
        primaryFlag      = orEmpty(primaryFlag);
        infoMsg          = orEmpty(infoMsg);
        errMsg           = orEmpty(errMsg);
        fKeys            = orEmpty(fKeys);
        fKey05           = orEmpty(fKey05);
        fKey12           = orEmpty(fKey12);
        aidKey           = (aidKey == null) ? AidKey.ENTER : aidKey;

        // PIC X(n) fixed-length validation per app/cpy-bms/COACTUP.CPY lines 17-342.
        // BMS RECEIVE-MAP at the CICS boundary hardware-truncates input strings at the
        // declared PIC X(n) width, so any value longer than that width indicates an
        // upstream adapter defect (CWE-20). Shorter values are accepted unchanged
        // (representing an unfilled BMS field, which arrives as SPACES). The check runs
        // AFTER the null→empty normalization above so that "" is treated as zero-length
        // (always valid).
        checkPicLength("trnName",         trnName,          4);  // TRNNAMEI  PIC X(4)
        checkPicLength("title01",         title01,         40);  // TITLE01I  PIC X(40)
        checkPicLength("curDate",         curDate,          8);  // CURDATEI  PIC X(8)
        checkPicLength("pgmName",         pgmName,          8);  // PGMNAMEI  PIC X(8)
        checkPicLength("title02",         title02,         40);  // TITLE02I  PIC X(40)
        checkPicLength("curTime",         curTime,          8);  // CURTIMEI  PIC X(8)
        checkPicLength("acctSid",         acctSid,         11);  // ACCTSIDI  PIC X(11)
        checkPicLength("acStatus",        acStatus,         1);  // ACSTTUSI  PIC X(1)
        checkPicLength("openYear",        openYear,         4);  // OPNYEARI  PIC X(4)
        checkPicLength("openMonth",       openMonth,        2);  // OPNMONI   PIC X(2)
        checkPicLength("openDay",         openDay,          2);  // OPNDAYI   PIC X(2)
        checkPicLength("creditLimit",     creditLimit,     15);  // ACRDLIMI  PIC X(15)
        checkPicLength("expirationYear",  expirationYear,   4);  // EXPYEARI  PIC X(4)
        checkPicLength("expirationMonth", expirationMonth,  2);  // EXPMONI   PIC X(2)
        checkPicLength("expirationDay",   expirationDay,    2);  // EXPDAYI   PIC X(2)
        checkPicLength("cashCreditLimit", cashCreditLimit, 15);  // ACSHLIMI  PIC X(15)
        checkPicLength("reissueYear",     reissueYear,      4);  // RISYEARI  PIC X(4)
        checkPicLength("reissueMonth",    reissueMonth,     2);  // RISMONI   PIC X(2)
        checkPicLength("reissueDay",      reissueDay,       2);  // RISDAYI   PIC X(2)
        checkPicLength("currentBalance",  currentBalance,  15);  // ACURBALI  PIC X(15)
        checkPicLength("currCycCredit",   currCycCredit,   15);  // ACRCYCRI  PIC X(15)
        checkPicLength("accountGroup",    accountGroup,    10);  // AADDGRPI  PIC X(10)
        checkPicLength("currCycDebit",    currCycDebit,    15);  // ACRCYDBI  PIC X(15)
        checkPicLength("custNumber",      custNumber,       9);  // ACSTNUMI  PIC X(9)
        checkPicLength("ssn1",            ssn1,             3);  // ACTSSN1I  PIC X(3)
        checkPicLength("ssn2",            ssn2,             2);  // ACTSSN2I  PIC X(2)
        checkPicLength("ssn3",            ssn3,             4);  // ACTSSN3I  PIC X(4)
        checkPicLength("dobYear",         dobYear,          4);  // DOBYEARI  PIC X(4)
        checkPicLength("dobMonth",        dobMonth,         2);  // DOBMONI   PIC X(2)
        checkPicLength("dobDay",          dobDay,           2);  // DOBDAYI   PIC X(2)
        checkPicLength("ficoScore",       ficoScore,        3);  // ACSTFCOI  PIC X(3)
        checkPicLength("firstName",       firstName,       25);  // ACSFNAMI  PIC X(25)
        checkPicLength("middleName",      middleName,      25);  // ACSMNAMI  PIC X(25)
        checkPicLength("lastName",        lastName,        25);  // ACSLNAMI  PIC X(25)
        checkPicLength("addressLine1",    addressLine1,    50);  // ACSADL1I  PIC X(50)
        checkPicLength("state",           state,            2);  // ACSSTTEI  PIC X(2)
        checkPicLength("addressLine2",    addressLine2,    50);  // ACSADL2I  PIC X(50)
        checkPicLength("zip",             zip,              5);  // ACSZIPCI  PIC X(5)
        checkPicLength("city",            city,            50);  // ACSCITYI  PIC X(50)
        checkPicLength("country",         country,          3);  // ACSCTRYI  PIC X(3)
        checkPicLength("phone1Area",      phone1Area,       3);  // ACSPH1AI  PIC X(3)
        checkPicLength("phone1Mid",       phone1Mid,        3);  // ACSPH1BI  PIC X(3)
        checkPicLength("phone1End",       phone1End,        4);  // ACSPH1CI  PIC X(4)
        checkPicLength("govtIssuedId",    govtIssuedId,    20);  // ACSGOVTI  PIC X(20)
        checkPicLength("phone2Area",      phone2Area,       3);  // ACSPH2AI  PIC X(3)
        checkPicLength("phone2Mid",       phone2Mid,        3);  // ACSPH2BI  PIC X(3)
        checkPicLength("phone2End",       phone2End,        4);  // ACSPH2CI  PIC X(4)
        checkPicLength("eftAccountId",    eftAccountId,    10);  // ACSEFTCI  PIC X(10)
        checkPicLength("primaryFlag",     primaryFlag,      1);  // ACSPFLGI  PIC X(1)
        checkPicLength("infoMsg",         infoMsg,         45);  // INFOMSGI  PIC X(45)
        checkPicLength("errMsg",          errMsg,          78);  // ERRMSGI   PIC X(78)
        checkPicLength("fKeys",           fKeys,           21);  // FKEYSI    PIC X(21)
        checkPicLength("fKey05",          fKey05,           7);  // FKEY05I   PIC X(7)
        checkPicLength("fKey12",          fKey12,          10);  // FKEY12I   PIC X(10)
    }

    /**
     * Validates that a {@link String} component does not exceed its declared BMS
     * {@code PIC X(n)} on-screen width.
     *
     * <p>Enforces the AAP &sect;0.7.1 Preserve-As-Is contract at the DTO boundary
     * (CWE-20 input validation): values longer than the declared BMS width would
     * cause silent hardware truncation in the COBOL RECEIVE-MAP layer; the Java
     * adapter must reject them at construction time so the screen contract cannot
     * be corrupted by an upstream defect. Shorter values are accepted unchanged
     * (BMS pads with SPACES when filling the panel); empty strings are accepted
     * as the COBOL SPACES idiom. Only over-length strings raise an exception.
     *
     * @param name      the component name (used in the exception message)
     * @param value     the component value (never {@code null}: the caller
     *                  guarantees normalization via {@link #orEmpty(String)})
     * @param maxLength the declared BMS {@code PIC X(n)} width
     * @throws IllegalArgumentException if {@code value.length() > maxLength}
     */
    private static void checkPicLength(String name, String value, int maxLength) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    name + " exceeds BMS PIC X(" + maxLength
                            + ") declared length; received length="
                            + value.length() + " value=\"" + value + "\"");
        }
    }

    /**
     * Returns the argument if non-null, or the empty string {@code ""} otherwise.
     *
     * <p>Centralizing this single trivial helper keeps the compact constructor
     * uncluttered and removes any risk of inconsistent null-handling between
     * components. Marked {@code private static} so it is not part of the public
     * surface area of the record.
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
     * <p>This matches the initial state on the first dispatch into COACTUP: the
     * operator has not yet typed anything, all SEND-MAP outputs are still blank,
     * and the default action is ENTER.
     *
     * <p>The 54-string-plus-AidKey component count is intentional and matches the
     * symbolic copybook {@code CACTUPAI} (lines 17-342 of
     * {@code app/cpy-bms/COACTUP.CPY}) field count exactly.
     *
     * @return a fully-blank input record (never {@code null})
     */
    public static CoActUpInput blank() {
        return new CoActUpInput(
                // header (6)
                "", "", "", "", "", "",
                // acctSid (1)
                "",
                // editable account fields (16)
                //   acStatus(1) + open(3) + creditLimit(1) + expiration(3) +
                //   cashCreditLimit(1) + reissue(3) + currentBalance(1) + currCycCredit(1)
                //   + accountGroup(1) + currCycDebit(1) = 16
                "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
                // customer identity (11)
                //   custNumber(1) + ssn(3) + dob(3) + ficoScore(1) + names(3) = 11
                "", "", "", "", "", "", "", "", "", "", "",
                // address (6)
                "", "", "", "", "", "",
                // phone 1 (3)
                "", "", "",
                // govt id (1)
                "",
                // phone 2 (3)
                "", "", "",
                // EFT + primary flag (2)
                "", "",
                // status messages (2)
                "", "",
                // function-key labels (3)
                "", "", "",
                // AID key (1)
                AidKey.ENTER
        );
    }

    /**
     * AID-key state captured at BMS RECEIVE-MAP time for the COACTUP screen.
     *
     * <p>The COBOL {@code CSSTRPFY} copybook decodes the {@code EIBAID} byte
     * returned by CICS into a set of 88-level conditions. Per AAP &sect;0.6.10 the
     * <em>full</em> AID-key hierarchy (ENTER, CLEAR, PA1, PA2, PFK01-PFK12) is
     * defined as a sealed type in {@code carddemo-domain.text.CcWorkAreas.AidKey};
     * that hierarchy supports exhaustive pattern matching for screens that handle
     * many function keys.
     *
     * <p>COACTUP handles only five AID keys (per the {@code 0000-MAIN} paragraph
     * {@code EVALUATE} on {@code EIBAID} in {@code app/cbl/COACTUPC.cbl}):
     * <ul>
     *   <li>{@link #ENTER} &mdash; submit; re-render the screen with fresh values.</li>
     *   <li>{@link #PF03_BACK} &mdash; return to the calling menu (XCTL).</li>
     *   <li>{@link #PF04_CLEAR} &mdash; clear all fields and re-prompt.</li>
     *   <li>{@link #PF05_SAVE} &mdash; commit the edited account/customer record.</li>
     *   <li>{@link #PF12_CANCEL} &mdash; discard pending edits and return.</li>
     * </ul>
     * Any other AID value is captured as {@link #OTHER} and yields the standard
     * "PF key not active" error message. To avoid pulling in the full sealed
     * hierarchy for just five meaningful states, this nested enum models only
     * what COACTUP actually consumes.
     *
     * <p><strong>Why an enum rather than a sealed interface here?</strong>
     * Sealed interfaces are AAP-mandated only for COBOL constructs that
     * partition a value space &mdash; in particular {@code REDEFINES} or
     * 88-level taxonomies on data fields. The AID key on COACTUP is a closed
     * 6-state dispatch (ENTER / PF03 / PF04 / PF05 / PF12 / fallback) with no
     * associated payload, which is exactly the case for which a plain
     * {@code enum} is idiomatic and sufficient. The agent prompt for this
     * file explicitly mandates {@code enum} here.
     */
    public enum AidKey {

        /** The user pressed the ENTER key to submit the screen. */
        ENTER,

        /** The user pressed PF03 to navigate back to the calling screen. */
        PF03_BACK,

        /** The user pressed PF04 to clear the screen and re-prompt. */
        PF04_CLEAR,

        /**
         * The user pressed PF05 to commit the edited account/customer record. This
         * is the action that triggers VSAM I-O writes and (on success) the COBOL
         * {@code SYNCPOINT} / on failure {@code SYNCPOINT ROLLBACK}.
         */
        PF05_SAVE,

        /**
         * The user pressed PF12 to cancel pending edits and discard the screen
         * state without writing to any underlying file or table.
         */
        PF12_CANCEL,

        /**
         * Any AID key not specifically recognized by COACTUP. The application
         * class responds with the standard "PF key not active" error message.
         */
        OTHER
    }
}
