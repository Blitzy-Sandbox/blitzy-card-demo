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
// type of every BMS output-field component on this record — and to all java.lang primitive
// wrappers and enum support used by the nested FieldAttributes record and AttributeMode enum.
import module java.base;

/**
 * BMS output DTO record carrying all fields sent to the 3270 terminal for the
 * <strong>COACTUP</strong> (Account Update) screen.
 *
 * <p>Source artifacts:
 * <ul>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COACTUP.CPY},
 *       structure {@code CACTUPAO} (lines 343-668, REDEFINES of {@code CACTUPAI}).</li>
 *   <li>BMS map definition: {@code app/bms/COACTUP.bms},
 *       map {@code CACTUPA} (size 24x80, FREEKB).</li>
 *   <li>Translated COBOL program: {@code COACTUPC}
 *       ({@code app/cbl/COACTUPC.cbl}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (output side)</h2>
 * <p>Per AAP &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into <em>entry-contract DTO
 * records</em> on the corresponding application class. This record is the Java analog of the
 * output view of the BMS symbolic structure (the {@code CACTUPAO REDEFINES CACTUPAI}
 * overlay in {@code COACTUP.CPY}): it carries the field values supplied by application
 * logic to {@code EXEC CICS SEND MAP}, where they are rendered onto the 3270 terminal.
 * There is no web framework, no Spring binding, no Jakarta Bean Validation, no view
 * templating engine; the record is a plain Java carrier.
 *
 * <p>It is constructed by {@code CoActUpC.processOutbound(...)} (the Java translation of the
 * COBOL {@code 3000-SEND-MAP} family of paragraphs) and returned to the caller for terminal
 * rendering by the SEND-MAP equivalent layer.
 *
 * <h2>Update-screen semantics &mdash; difference from COACTVW</h2>
 * <p>Unlike the view-only COACTVW screen ({@link CoActVwOutput}), COACTUP is the
 * <strong>update</strong> screen for accounts. Nearly every account and customer field is
 * <em>editable</em> by the operator, so the screen needs runtime control over which fields
 * are {@code UNPROT} (editable) versus {@code PROT} (display only) on each SEND-MAP. The
 * COBOL paragraph {@code 3300-SETUP-SCREEN-ATTRS} sets per-field attribute bytes
 * (C={@code COLOR}, P={@code PS}, H={@code HILIGHT}, V={@code VALIDN}) to flag fields in
 * error and to lock fields the user cannot change in the current state.
 *
 * <p>This Java DTO captures that runtime attribute state in a single companion record
 * {@link FieldAttributes}, which is one of this record's components. A field's mode is
 * one of {@link AttributeMode#UNPROTECTED}, {@link AttributeMode#PROTECTED},
 * {@link AttributeMode#PROTECTED_HIGHLIGHTED}, or {@link AttributeMode#ERROR}. The four
 * COBOL attribute bytes (C/P/H/V) are consolidated into this single mode because the
 * SEND-MAP equivalent layer is the only consumer that needs to decompose them back into
 * the wire-format BMS attribute bytes; application code reasons about mode rather than
 * individual attribute bytes.
 *
 * <h2>Notable shape differences relative to {@link CoActVwOutput}</h2>
 * <ul>
 *   <li><strong>Dates are split year/month/day triples.</strong> COACTUP exposes the
 *       account-open, card-expiration, card-reissue, and customer date-of-birth as three
 *       separate BMS output fields each (e.g., {@code OPNYEARO/OPNMONO/OPNDAYO}). This
 *       matches the on-screen layout, where the operator sees and edits the year, month,
 *       and day in three distinct positions on the 3270 panel. The application class
 *       converts {@link java.time.LocalDate} values into these split strings at the
 *       SEND-MAP boundary.</li>
 *   <li><strong>SSN is split.</strong> {@code ACTSSN1O/ACTSSN2O/ACTSSN3O} carry the 3-2-4
 *       digit groups separately. The dashes are rendered on the panel as
 *       {@code DFHMDF} constants, not stored.</li>
 *   <li><strong>Phone numbers are split.</strong> Two phones are present, each with
 *       area code (3), middle (3), and last (4) components.</li>
 *   <li><strong>Function-key panel labels are present.</strong> {@code FKEYSO},
 *       {@code FKEY05O}, and {@code FKEY12O} render the function-key legend
 *       (e.g., "PF05=Save  PF12=Cancel") along the bottom border. They are populated by
 *       the application class on every SEND-MAP.</li>
 *   <li><strong>Nested {@link FieldAttributes} record present.</strong> COACTVW omits
 *       this because its fields are statically protected at the BMS map level; COACTUP
 *       needs runtime attribute control.</li>
 * </ul>
 *
 * <h2>Field formatting expectations</h2>
 * <p>Numeric/monetary fields arrive on this DTO <em>pre-formatted as {@link String}</em>.
 * The application class ({@code CoActUpC}) is responsible for converting the underlying
 * {@link java.math.BigDecimal} balance and limit values into the 15-character signed-decimal
 * picture used by the BMS map ({@code -9999999.99} format for {@code ACRDLIMO},
 * {@code ACSHLIMO}, {@code ACURBALO}, {@code ACRCYCRO}, and {@code ACRCYDBO}). Likewise,
 * date components are formatted into the appropriate digit strings before being placed on
 * this DTO. This DTO does not perform numeric or date formatting itself; it is a pure
 * carrier of pre-formatted display strings.
 *
 * <h2>Null and emptiness semantics &mdash; COBOL parity</h2>
 * <p>In COBOL, an unset BMS {@code PIC X(n)} output field is SPACES on SEND-MAP, never
 * null (no null pointer exists in COBOL). The {@code COACTUPC} initialization paragraph
 * {@code 3100-SCREEN-INIT} performs {@code MOVE LOW-VALUES TO CACTUPAO} followed by
 * {@code INITIALIZE CACTUPAO}, which sets every PIC X field to SPACES. To preserve that
 * behavior precisely, the compact constructor below replaces every {@code null}
 * {@link String} component with the empty {@link String} <code>""</code>. This means
 * downstream consumers &mdash; and the BMS-emitting layer that ultimately serializes
 * this record to the 3270 wire format &mdash; can safely treat every component as a
 * non-null {@link String} without first checking for null. A null {@link FieldAttributes}
 * is normalized to {@link FieldAttributes#allProtected()}, matching the COBOL idiom that
 * an uninitialized attribute byte renders as ASKIP/PROT.
 *
 * <p>The compact constructor uses <strong>JEP 513 Flexible Constructor Bodies</strong>
 * (finalized in Java 25). Statements before the canonical field-assignment perform input
 * normalization, which is exactly the place to capture COBOL-style "default to SPACES"
 * semantics on SEND-MAP. This satisfies the file-level mandate to use JEP 513 for record
 * validation and normalization.
 *
 * <h2>Immutability</h2>
 * <p>Because this is a record, all components are {@code final} and accessors are
 * automatically generated; there are no setters, no Lombok, no Spring annotations, no
 * Jakarta validation annotations. The instance is safely shareable across virtual threads
 * (per AAP &sect;0.6.6) without synchronization.
 *
 * <h2>Field-by-field mapping</h2>
 * <table>
 *   <caption>Mapping from BMS symbolic copybook {@code CACTUPAO} (output view, lines
 *            343-668) to Java record components. PIC column shows the COBOL PICTURE
 *            clause; lengths are fixed and preserved by the runtime that converts this
 *            Java record into the 3270 SEND-MAP wire format.</caption>
 *   <tr><th>Symbolic BMS field</th><th>COBOL PIC</th><th>Java component</th></tr>
 *   <tr><td>TRNNAMEO</td><td>X(4)</td><td>{@link #trnName()}</td></tr>
 *   <tr><td>TITLE01O</td><td>X(40)</td><td>{@link #title01()}</td></tr>
 *   <tr><td>CURDATEO</td><td>X(8)</td><td>{@link #curDate()}</td></tr>
 *   <tr><td>PGMNAMEO</td><td>X(8)</td><td>{@link #pgmName()}</td></tr>
 *   <tr><td>TITLE02O</td><td>X(40)</td><td>{@link #title02()}</td></tr>
 *   <tr><td>CURTIMEO</td><td>X(8)</td><td>{@link #curTime()}</td></tr>
 *   <tr><td>ACCTSIDO</td><td>X(11)</td><td>{@link #acctSid()}</td></tr>
 *   <tr><td>ACSTTUSO</td><td>X(1)</td><td>{@link #acStatus()}</td></tr>
 *   <tr><td>OPNYEARO</td><td>X(4)</td><td>{@link #openYear()}</td></tr>
 *   <tr><td>OPNMONO</td><td>X(2)</td><td>{@link #openMonth()}</td></tr>
 *   <tr><td>OPNDAYO</td><td>X(2)</td><td>{@link #openDay()}</td></tr>
 *   <tr><td>ACRDLIMO</td><td>X(15)</td><td>{@link #creditLimit()}</td></tr>
 *   <tr><td>EXPYEARO</td><td>X(4)</td><td>{@link #expirationYear()}</td></tr>
 *   <tr><td>EXPMONO</td><td>X(2)</td><td>{@link #expirationMonth()}</td></tr>
 *   <tr><td>EXPDAYO</td><td>X(2)</td><td>{@link #expirationDay()}</td></tr>
 *   <tr><td>ACSHLIMO</td><td>X(15)</td><td>{@link #cashCreditLimit()}</td></tr>
 *   <tr><td>RISYEARO</td><td>X(4)</td><td>{@link #reissueYear()}</td></tr>
 *   <tr><td>RISMONO</td><td>X(2)</td><td>{@link #reissueMonth()}</td></tr>
 *   <tr><td>RISDAYO</td><td>X(2)</td><td>{@link #reissueDay()}</td></tr>
 *   <tr><td>ACURBALO</td><td>X(15)</td><td>{@link #currentBalance()}</td></tr>
 *   <tr><td>ACRCYCRO</td><td>X(15)</td><td>{@link #currCycCredit()}</td></tr>
 *   <tr><td>AADDGRPO</td><td>X(10)</td><td>{@link #accountGroup()}</td></tr>
 *   <tr><td>ACRCYDBO</td><td>X(15)</td><td>{@link #currCycDebit()}</td></tr>
 *   <tr><td>ACSTNUMO</td><td>X(9)</td><td>{@link #custNumber()}</td></tr>
 *   <tr><td>ACTSSN1O</td><td>X(3)</td><td>{@link #ssn1()}</td></tr>
 *   <tr><td>ACTSSN2O</td><td>X(2)</td><td>{@link #ssn2()}</td></tr>
 *   <tr><td>ACTSSN3O</td><td>X(4)</td><td>{@link #ssn3()}</td></tr>
 *   <tr><td>DOBYEARO</td><td>X(4)</td><td>{@link #dobYear()}</td></tr>
 *   <tr><td>DOBMONO</td><td>X(2)</td><td>{@link #dobMonth()}</td></tr>
 *   <tr><td>DOBDAYO</td><td>X(2)</td><td>{@link #dobDay()}</td></tr>
 *   <tr><td>ACSTFCOO</td><td>X(3)</td><td>{@link #ficoScore()}</td></tr>
 *   <tr><td>ACSFNAMO</td><td>X(25)</td><td>{@link #firstName()}</td></tr>
 *   <tr><td>ACSMNAMO</td><td>X(25)</td><td>{@link #middleName()}</td></tr>
 *   <tr><td>ACSLNAMO</td><td>X(25)</td><td>{@link #lastName()}</td></tr>
 *   <tr><td>ACSADL1O</td><td>X(50)</td><td>{@link #addressLine1()}</td></tr>
 *   <tr><td>ACSSTTEO</td><td>X(2)</td><td>{@link #state()}</td></tr>
 *   <tr><td>ACSADL2O</td><td>X(50)</td><td>{@link #addressLine2()}</td></tr>
 *   <tr><td>ACSZIPCO</td><td>X(5)</td><td>{@link #zip()}</td></tr>
 *   <tr><td>ACSCITYO</td><td>X(50)</td><td>{@link #city()}</td></tr>
 *   <tr><td>ACSCTRYO</td><td>X(3)</td><td>{@link #country()}</td></tr>
 *   <tr><td>ACSPH1AO</td><td>X(3)</td><td>{@link #phone1Area()}</td></tr>
 *   <tr><td>ACSPH1BO</td><td>X(3)</td><td>{@link #phone1Mid()}</td></tr>
 *   <tr><td>ACSPH1CO</td><td>X(4)</td><td>{@link #phone1End()}</td></tr>
 *   <tr><td>ACSGOVTO</td><td>X(20)</td><td>{@link #govtIssuedId()}</td></tr>
 *   <tr><td>ACSPH2AO</td><td>X(3)</td><td>{@link #phone2Area()}</td></tr>
 *   <tr><td>ACSPH2BO</td><td>X(3)</td><td>{@link #phone2Mid()}</td></tr>
 *   <tr><td>ACSPH2CO</td><td>X(4)</td><td>{@link #phone2End()}</td></tr>
 *   <tr><td>ACSEFTCO</td><td>X(10)</td><td>{@link #eftAccountId()}</td></tr>
 *   <tr><td>ACSPFLGO</td><td>X(1)</td><td>{@link #primaryFlag()}</td></tr>
 *   <tr><td>INFOMSGO</td><td>X(45)</td><td>{@link #infoMsg()}</td></tr>
 *   <tr><td>ERRMSGO</td><td>X(78)</td><td>{@link #errMsg()}</td></tr>
 *   <tr><td>FKEYSO</td><td>X(21)</td><td>{@link #fKeys()}</td></tr>
 *   <tr><td>FKEY05O</td><td>X(7)</td><td>{@link #fKey05()}</td></tr>
 *   <tr><td>FKEY12O</td><td>X(10)</td><td>{@link #fKey12()}</td></tr>
 * </table>
 *
 * <p>Plus {@link #attributes()} &mdash; the runtime field-attribute control structure
 * populated by the COBOL {@code 3300-SETUP-SCREEN-ATTRS} translation.
 *
 * <h2>Forbidden idioms (per AAP)</h2>
 * <ul>
 *   <li>No Spring annotations &mdash; this record is plain Java.</li>
 *   <li>No Lombok &mdash; record components and accessors are explicit.</li>
 *   <li>No Jakarta Bean Validation &mdash; null-coalescing is hand-written in the compact
 *       constructor; no semantic validation is performed here.</li>
 *   <li>No setters &mdash; records are immutable.</li>
 *   <li>No {@code java.util.Date} &mdash; date strings stay as raw display bytes on
 *       this DTO and are formatted upstream by the application class from
 *       {@code java.time.LocalDate} values.</li>
 * </ul>
 *
 * @param trnName          CICS transaction id displayed in row 1 (e.g., {@code "CAUP"})
 *                         ({@code TRNNAMEO PIC X(4)})
 * @param title01          line-1 screen title (e.g., {@code "CardDemo"})
 *                         ({@code TITLE01O PIC X(40)})
 * @param curDate          current date in {@code MM/DD/YY} format ({@code CURDATEO PIC X(8)})
 * @param pgmName          current program id (e.g., {@code "COACTUPC"})
 *                         ({@code PGMNAMEO PIC X(8)})
 * @param title02          line-2 screen title (e.g., {@code "Update Account"})
 *                         ({@code TITLE02O PIC X(40)})
 * @param curTime          current time in {@code HH:MM:SS} format ({@code CURTIMEO PIC X(8)})
 * @param acctSid          11-digit account id displayed in row 5
 *                         ({@code ACCTSIDO PIC X(11)})
 * @param acStatus         account active status (Y/N) ({@code ACSTTUSO PIC X(1)})
 * @param openYear         account open-date year (4 digits) ({@code OPNYEARO PIC X(4)})
 * @param openMonth        account open-date month (2 digits) ({@code OPNMONO PIC X(2)})
 * @param openDay          account open-date day (2 digits) ({@code OPNDAYO PIC X(2)})
 * @param creditLimit      formatted credit limit (signed-decimal text, 15 chars)
 *                         ({@code ACRDLIMO PIC X(15)})
 * @param expirationYear   card expiration year (4 digits) ({@code EXPYEARO PIC X(4)})
 * @param expirationMonth  card expiration month (2 digits) ({@code EXPMONO PIC X(2)})
 * @param expirationDay    card expiration day (2 digits) ({@code EXPDAYO PIC X(2)})
 * @param cashCreditLimit  formatted cash credit limit ({@code ACSHLIMO PIC X(15)})
 * @param reissueYear      card reissue year (4 digits) ({@code RISYEARO PIC X(4)})
 * @param reissueMonth     card reissue month (2 digits) ({@code RISMONO PIC X(2)})
 * @param reissueDay       card reissue day (2 digits) ({@code RISDAYO PIC X(2)})
 * @param currentBalance   formatted current balance ({@code ACURBALO PIC X(15)})
 * @param currCycCredit    formatted current-cycle credit total
 *                         ({@code ACRCYCRO PIC X(15)})
 * @param accountGroup     account group id (used for discount lookups)
 *                         ({@code AADDGRPO PIC X(10)})
 * @param currCycDebit     formatted current-cycle debit total
 *                         ({@code ACRCYDBO PIC X(15)})
 * @param custNumber       customer number associated with this account
 *                         ({@code ACSTNUMO PIC X(9)})
 * @param ssn1             customer SSN first group, 3 digits ({@code ACTSSN1O PIC X(3)})
 * @param ssn2             customer SSN second group, 2 digits ({@code ACTSSN2O PIC X(2)})
 * @param ssn3             customer SSN third group, 4 digits ({@code ACTSSN3O PIC X(4)})
 * @param dobYear          customer date-of-birth year (4 digits)
 *                         ({@code DOBYEARO PIC X(4)})
 * @param dobMonth         customer date-of-birth month (2 digits)
 *                         ({@code DOBMONO PIC X(2)})
 * @param dobDay           customer date-of-birth day (2 digits)
 *                         ({@code DOBDAYO PIC X(2)})
 * @param ficoScore        customer FICO score, 3 digits ({@code ACSTFCOO PIC X(3)})
 * @param firstName        customer first name ({@code ACSFNAMO PIC X(25)})
 * @param middleName       customer middle name ({@code ACSMNAMO PIC X(25)})
 * @param lastName         customer last name ({@code ACSLNAMO PIC X(25)})
 * @param addressLine1     customer address line 1 ({@code ACSADL1O PIC X(50)})
 * @param state            customer state abbreviation ({@code ACSSTTEO PIC X(2)})
 * @param addressLine2     customer address line 2 ({@code ACSADL2O PIC X(50)})
 * @param zip              customer ZIP code ({@code ACSZIPCO PIC X(5)})
 * @param city             customer city ({@code ACSCITYO PIC X(50)})
 * @param country          customer country code ({@code ACSCTRYO PIC X(3)})
 * @param phone1Area       customer phone 1, area code (3 digits)
 *                         ({@code ACSPH1AO PIC X(3)})
 * @param phone1Mid        customer phone 1, exchange (3 digits)
 *                         ({@code ACSPH1BO PIC X(3)})
 * @param phone1End        customer phone 1, subscriber (4 digits)
 *                         ({@code ACSPH1CO PIC X(4)})
 * @param govtIssuedId     customer government-issued id ({@code ACSGOVTO PIC X(20)})
 * @param phone2Area       customer phone 2, area code (3 digits)
 *                         ({@code ACSPH2AO PIC X(3)})
 * @param phone2Mid        customer phone 2, exchange (3 digits)
 *                         ({@code ACSPH2BO PIC X(3)})
 * @param phone2End        customer phone 2, subscriber (4 digits)
 *                         ({@code ACSPH2CO PIC X(4)})
 * @param eftAccountId     customer EFT account id ({@code ACSEFTCO PIC X(10)})
 * @param primaryFlag      primary cardholder flag (Y/N) ({@code ACSPFLGO PIC X(1)})
 * @param infoMsg          informational message line ({@code INFOMSGO PIC X(45)})
 * @param errMsg           error message line ({@code ERRMSGO PIC X(78)})
 * @param fKeys            full function-key legend text rendered along the bottom of the
 *                         BMS panel ({@code FKEYSO PIC X(21)})
 * @param fKey05           PF05 label text ({@code FKEY05O PIC X(7)})
 * @param fKey12           PF12 label text ({@code FKEY12O PIC X(10)})
 * @param attributes       per-field attribute control structure populated by the
 *                         {@code 3300-SETUP-SCREEN-ATTRS} translation; never {@code null}
 *                         (a {@code null} argument is normalized to
 *                         {@link FieldAttributes#allProtected()} by the compact
 *                         constructor)
 * @see CoActUpOutput.FieldAttributes
 * @see CoActUpOutput.AttributeMode
 * @see CoActUpInput
 * @see CoActVwOutput
 * @since 1.0.0
 */
public record CoActUpOutput(

        // ============================================================================
        // Header fields (rows 1-2 of the 24x80 BMS map). Populated by the application
        // class on every SEND-MAP so the operator always sees the current transaction id,
        // program id, date, and time.
        // ============================================================================
        String trnName,         // TRNNAMEO  PIC X(4)
        String title01,         // TITLE01O  PIC X(40)
        String curDate,         // CURDATEO  PIC X(8)   — MM/DD/YY
        String pgmName,         // PGMNAMEO  PIC X(8)
        String title02,         // TITLE02O  PIC X(40)  — typically "Update Account"
        String curTime,         // CURTIMEO  PIC X(8)   — HH:MM:SS

        // ============================================================================
        // Account key (row 5). The account id is shown on every SEND-MAP after the
        // operator has typed it in; on the initial SEND-MAP it is empty (operator types
        // it next).
        // ============================================================================
        String acctSid,         // ACCTSIDO  PIC X(11)

        // ============================================================================
        // Editable account fields (rows 4-13). On a typical SEND-MAP, these are populated
        // from the underlying ACCOUNT-RECORD; they are pre-formatted as String here, and
        // 3300-SETUP-SCREEN-ATTRS determines per-field whether they are UNPROT (editable)
        // or PROT (display only).
        // ============================================================================
        String acStatus,        // ACSTTUSO  PIC X(1)   — Y/N
        String openYear,        // OPNYEARO  PIC X(4)
        String openMonth,       // OPNMONO   PIC X(2)
        String openDay,         // OPNDAYO   PIC X(2)
        String creditLimit,     // ACRDLIMO  PIC X(15)  — formatted "-9999999.99"
        String expirationYear,  // EXPYEARO  PIC X(4)
        String expirationMonth, // EXPMONO   PIC X(2)
        String expirationDay,   // EXPDAYO   PIC X(2)
        String cashCreditLimit, // ACSHLIMO  PIC X(15)  — formatted "-9999999.99"
        String reissueYear,     // RISYEARO  PIC X(4)
        String reissueMonth,    // RISMONO   PIC X(2)
        String reissueDay,      // RISDAYO   PIC X(2)
        String currentBalance,  // ACURBALO  PIC X(15)  — formatted "-9999999.99"
        String currCycCredit,   // ACRCYCRO  PIC X(15)  — formatted "-9999999.99"
        String accountGroup,    // AADDGRPO  PIC X(10)  — disclosure group id
        String currCycDebit,    // ACRCYDBO  PIC X(15)  — formatted "-9999999.99"

        // ============================================================================
        // Customer key + identity (rows 14-18). Editable demographic fields populated
        // from the CUSTOMER-RECORD that this account is linked to.
        // ============================================================================
        String custNumber,      // ACSTNUMO  PIC X(9)
        String ssn1,            // ACTSSN1O  PIC X(3)   — SSN area
        String ssn2,            // ACTSSN2O  PIC X(2)   — SSN group
        String ssn3,            // ACTSSN3O  PIC X(4)   — SSN serial
        String dobYear,         // DOBYEARO  PIC X(4)
        String dobMonth,        // DOBMONO   PIC X(2)
        String dobDay,          // DOBDAYO   PIC X(2)
        String ficoScore,       // ACSTFCOO  PIC X(3)
        String firstName,       // ACSFNAMO  PIC X(25)
        String middleName,      // ACSMNAMO  PIC X(25)
        String lastName,        // ACSLNAMO  PIC X(25)

        // ============================================================================
        // Address (rows 19-20).
        // ============================================================================
        String addressLine1,    // ACSADL1O  PIC X(50)
        String state,           // ACSSTTEO  PIC X(2)
        String addressLine2,    // ACSADL2O  PIC X(50)
        String zip,             // ACSZIPCO  PIC X(5)
        String city,            // ACSCITYO  PIC X(50)
        String country,         // ACSCTRYO  PIC X(3)

        // ============================================================================
        // Phone 1 (row 21) — area/middle/end triple.
        // ============================================================================
        String phone1Area,      // ACSPH1AO  PIC X(3)
        String phone1Mid,       // ACSPH1BO  PIC X(3)
        String phone1End,       // ACSPH1CO  PIC X(4)

        // ============================================================================
        // Government-issued id (row 21).
        // ============================================================================
        String govtIssuedId,    // ACSGOVTO  PIC X(20)

        // ============================================================================
        // Phone 2 (row 22) — area/middle/end triple.
        // ============================================================================
        String phone2Area,      // ACSPH2AO  PIC X(3)
        String phone2Mid,       // ACSPH2BO  PIC X(3)
        String phone2End,       // ACSPH2CO  PIC X(4)

        // ============================================================================
        // EFT account + primary flag (row 22).
        // ============================================================================
        String eftAccountId,    // ACSEFTCO  PIC X(10)
        String primaryFlag,     // ACSPFLGO  PIC X(1)   — Y/N

        // ============================================================================
        // Status messages (rows 23-24) — informational and error message lines populated
        // by the application class to communicate validation results back to the operator.
        // ============================================================================
        String infoMsg,         // INFOMSGO  PIC X(45)
        String errMsg,          // ERRMSGO   PIC X(78)

        // ============================================================================
        // Function-key labels (row 24, bottom border). Populated on every SEND-MAP to
        // show the operator which PF keys are active.
        // ============================================================================
        String fKeys,           // FKEYSO    PIC X(21)
        String fKey05,          // FKEY05O   PIC X(7)   — e.g., "PF05=Save"
        String fKey12,          // FKEY12O   PIC X(10)  — e.g., "PF12=Cancel"

        // ============================================================================
        // Runtime field attributes — populated by the 3300-SETUP-SCREEN-ATTRS translation.
        // Controls which fields are UNPROT (editable) vs PROT (display only) and which
        // are flagged in error state on this SEND-MAP.
        // ============================================================================
        FieldAttributes attributes

) {

    /**
     * Compact (canonical) constructor.
     *
     * <p>Normalizes every {@link String} component so that a {@code null} reference is
     * converted to the empty {@link String} <code>""</code>. This mirrors COBOL
     * SEND-MAP semantics where unfilled BMS {@code PIC X(n)} fields are SPACES,
     * never undefined. A null {@link FieldAttributes} is normalized to
     * {@link FieldAttributes#allProtected()} because the legacy COBOL default for
     * an uninitialized attribute byte renders as ASKIP/PROT.
     *
     * <p>Uses <strong>JEP 513 Flexible Constructor Bodies</strong> (finalized in Java
     * 25): the normalization statements run before the implicit canonical field
     * assignment, which is the appropriate location for COBOL-style "default to
     * SPACES" output cleansing.
     *
     * <p>The constructor never throws. No semantic validation (PIC-length clamping,
     * numeric checks, calendar validity, etc.) is performed here. The application
     * class ({@code CoActUpC}) is the single point of responsibility for producing
     * correctly formatted strings before constructing this record; this record is a
     * pure carrier.
     */
    public CoActUpOutput {
        // Header
        trnName          = orEmpty(trnName);
        title01          = orEmpty(title01);
        curDate          = orEmpty(curDate);
        pgmName          = orEmpty(pgmName);
        title02          = orEmpty(title02);
        curTime          = orEmpty(curTime);
        // Account key
        acctSid          = orEmpty(acctSid);
        // Editable account fields
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
        // Customer key + identity
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
        // Address
        addressLine1     = orEmpty(addressLine1);
        state            = orEmpty(state);
        addressLine2     = orEmpty(addressLine2);
        zip              = orEmpty(zip);
        city             = orEmpty(city);
        country          = orEmpty(country);
        // Phone 1
        phone1Area       = orEmpty(phone1Area);
        phone1Mid        = orEmpty(phone1Mid);
        phone1End        = orEmpty(phone1End);
        // Government id
        govtIssuedId     = orEmpty(govtIssuedId);
        // Phone 2
        phone2Area       = orEmpty(phone2Area);
        phone2Mid        = orEmpty(phone2Mid);
        phone2End        = orEmpty(phone2End);
        // EFT + primary
        eftAccountId     = orEmpty(eftAccountId);
        primaryFlag      = orEmpty(primaryFlag);
        // Messages
        infoMsg          = orEmpty(infoMsg);
        errMsg           = orEmpty(errMsg);
        // Function-key labels
        fKeys            = orEmpty(fKeys);
        fKey05           = orEmpty(fKey05);
        fKey12           = orEmpty(fKey12);
        // Attributes
        attributes       = (attributes == null) ? FieldAttributes.allProtected() : attributes;
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
     * Factory: an output record with every string component empty and every field
     * attribute set to {@link AttributeMode#PROTECTED}.
     *
     * <p>This matches the initial state produced by the COBOL paragraph
     * {@code 3100-SCREEN-INIT}, which performs {@code MOVE LOW-VALUES TO CACTUPAO}
     * followed by {@code INITIALIZE CACTUPAO}: every PIC X field is SPACES, and the
     * symbolic-map attribute bytes default to ASKIP/PROT until
     * {@code 3300-SETUP-SCREEN-ATTRS} overrides them.
     *
     * <p>The 54-string-plus-{@link FieldAttributes} component count is intentional and
     * matches the symbolic copybook {@code CACTUPAO} (lines 343-668 of
     * {@code app/cpy-bms/COACTUP.CPY}) field count exactly.
     *
     * @return a fully-blank output record (never {@code null})
     */
    public static CoActUpOutput blank() {
        return new CoActUpOutput(
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
                // government id (1)
                "",
                // phone 2 (3)
                "", "", "",
                // EFT + primary (2)
                "", "",
                // messages (2)
                "", "",
                // function-key labels (3)
                "", "", "",
                // attributes (1)
                FieldAttributes.allProtected()
        );
    }

    // ========================================================================
    // Nested record for runtime field attributes (CSSETATY translation).
    // ========================================================================
    /**
     * Per-field attribute settings modified at runtime by COBOL paragraph
     * {@code 3300-SETUP-SCREEN-ATTRS}.
     *
     * <p>The COBOL {@code CSSETATY} copybook (referenced via {@code COPY REPLACING}
     * in {@code 3300-SETUP-SCREEN-ATTRS}) sets four BMS-attribute bytes per field:
     * C ({@code COLOR}), P ({@code PS}), H ({@code HILIGHT}), V ({@code VALIDN}).
     * In this Java translation those bytes are consolidated to a single
     * {@link AttributeMode} value per editable field.
     *
     * <p><strong>Field coverage.</strong> Only fields whose attributes vary at
     * runtime are listed here (41 in total). Fields with statically defined
     * attributes &mdash; the header rows, the account search key (always {@code IC,UNPROT}
     * on initial entry, {@code ASKIP} on subsequent entries), the function-key labels,
     * and the message lines &mdash; inherit defaults from the BMS map definition in
     * {@code app/bms/COACTUP.bms} and are not represented on this attribute record.
     *
     * <p><strong>Immutability.</strong> Like its parent record, {@link FieldAttributes}
     * is a Java record: its components are {@code final}, accessors are auto-generated,
     * there are no setters, and the instance is safe to share across virtual threads
     * without synchronization.
     *
     * @param acStatusAttr          mode for ACSTTUSO (active status)
     * @param openYearAttr          mode for OPNYEARO (account open-date year)
     * @param openMonthAttr         mode for OPNMONO (account open-date month)
     * @param openDayAttr           mode for OPNDAYO (account open-date day)
     * @param creditLimitAttr       mode for ACRDLIMO (credit limit)
     * @param expirationYearAttr    mode for EXPYEARO (card expiration year)
     * @param expirationMonthAttr   mode for EXPMONO (card expiration month)
     * @param expirationDayAttr     mode for EXPDAYO (card expiration day)
     * @param cashCreditLimitAttr   mode for ACSHLIMO (cash credit limit)
     * @param reissueYearAttr       mode for RISYEARO (card reissue year)
     * @param reissueMonthAttr      mode for RISMONO (card reissue month)
     * @param reissueDayAttr        mode for RISDAYO (card reissue day)
     * @param currentBalanceAttr    mode for ACURBALO (current balance)
     * @param currCycCreditAttr     mode for ACRCYCRO (current-cycle credit)
     * @param accountGroupAttr      mode for AADDGRPO (account group)
     * @param currCycDebitAttr      mode for ACRCYDBO (current-cycle debit)
     * @param ssn1Attr              mode for ACTSSN1O (SSN area)
     * @param ssn2Attr              mode for ACTSSN2O (SSN group)
     * @param ssn3Attr              mode for ACTSSN3O (SSN serial)
     * @param dobYearAttr           mode for DOBYEARO (DOB year)
     * @param dobMonthAttr          mode for DOBMONO (DOB month)
     * @param dobDayAttr            mode for DOBDAYO (DOB day)
     * @param ficoScoreAttr         mode for ACSTFCOO (FICO score)
     * @param firstNameAttr         mode for ACSFNAMO (first name)
     * @param middleNameAttr        mode for ACSMNAMO (middle name)
     * @param lastNameAttr          mode for ACSLNAMO (last name)
     * @param addressLine1Attr      mode for ACSADL1O (address line 1)
     * @param stateAttr             mode for ACSSTTEO (state)
     * @param addressLine2Attr      mode for ACSADL2O (address line 2)
     * @param zipAttr               mode for ACSZIPCO (ZIP code)
     * @param cityAttr              mode for ACSCITYO (city)
     * @param countryAttr           mode for ACSCTRYO (country)
     * @param phone1AreaAttr        mode for ACSPH1AO (phone 1 area)
     * @param phone1MidAttr         mode for ACSPH1BO (phone 1 exchange)
     * @param phone1EndAttr         mode for ACSPH1CO (phone 1 subscriber)
     * @param govtIssuedIdAttr      mode for ACSGOVTO (government id)
     * @param phone2AreaAttr        mode for ACSPH2AO (phone 2 area)
     * @param phone2MidAttr         mode for ACSPH2BO (phone 2 exchange)
     * @param phone2EndAttr         mode for ACSPH2CO (phone 2 subscriber)
     * @param eftAccountIdAttr      mode for ACSEFTCO (EFT account id)
     * @param primaryFlagAttr       mode for ACSPFLGO (primary cardholder flag)
     */
    public record FieldAttributes(
            AttributeMode acStatusAttr,
            AttributeMode openYearAttr,
            AttributeMode openMonthAttr,
            AttributeMode openDayAttr,
            AttributeMode creditLimitAttr,
            AttributeMode expirationYearAttr,
            AttributeMode expirationMonthAttr,
            AttributeMode expirationDayAttr,
            AttributeMode cashCreditLimitAttr,
            AttributeMode reissueYearAttr,
            AttributeMode reissueMonthAttr,
            AttributeMode reissueDayAttr,
            AttributeMode currentBalanceAttr,
            AttributeMode currCycCreditAttr,
            AttributeMode accountGroupAttr,
            AttributeMode currCycDebitAttr,
            AttributeMode ssn1Attr,
            AttributeMode ssn2Attr,
            AttributeMode ssn3Attr,
            AttributeMode dobYearAttr,
            AttributeMode dobMonthAttr,
            AttributeMode dobDayAttr,
            AttributeMode ficoScoreAttr,
            AttributeMode firstNameAttr,
            AttributeMode middleNameAttr,
            AttributeMode lastNameAttr,
            AttributeMode addressLine1Attr,
            AttributeMode stateAttr,
            AttributeMode addressLine2Attr,
            AttributeMode zipAttr,
            AttributeMode cityAttr,
            AttributeMode countryAttr,
            AttributeMode phone1AreaAttr,
            AttributeMode phone1MidAttr,
            AttributeMode phone1EndAttr,
            AttributeMode govtIssuedIdAttr,
            AttributeMode phone2AreaAttr,
            AttributeMode phone2MidAttr,
            AttributeMode phone2EndAttr,
            AttributeMode eftAccountIdAttr,
            AttributeMode primaryFlagAttr
    ) {

        /**
         * Compact (canonical) constructor.
         *
         * <p>Normalizes every {@link AttributeMode} component so that a {@code null}
         * reference is converted to {@link AttributeMode#PROTECTED}. This matches the
         * COBOL idiom that an uninitialized attribute byte renders as ASKIP/PROT.
         *
         * <p>Uses <strong>JEP 513 Flexible Constructor Bodies</strong> (finalized in
         * Java 25). The constructor never throws.
         */
        public FieldAttributes {
            acStatusAttr         = orProtected(acStatusAttr);
            openYearAttr         = orProtected(openYearAttr);
            openMonthAttr        = orProtected(openMonthAttr);
            openDayAttr          = orProtected(openDayAttr);
            creditLimitAttr      = orProtected(creditLimitAttr);
            expirationYearAttr   = orProtected(expirationYearAttr);
            expirationMonthAttr  = orProtected(expirationMonthAttr);
            expirationDayAttr    = orProtected(expirationDayAttr);
            cashCreditLimitAttr  = orProtected(cashCreditLimitAttr);
            reissueYearAttr      = orProtected(reissueYearAttr);
            reissueMonthAttr     = orProtected(reissueMonthAttr);
            reissueDayAttr       = orProtected(reissueDayAttr);
            currentBalanceAttr   = orProtected(currentBalanceAttr);
            currCycCreditAttr    = orProtected(currCycCreditAttr);
            accountGroupAttr     = orProtected(accountGroupAttr);
            currCycDebitAttr     = orProtected(currCycDebitAttr);
            ssn1Attr             = orProtected(ssn1Attr);
            ssn2Attr             = orProtected(ssn2Attr);
            ssn3Attr             = orProtected(ssn3Attr);
            dobYearAttr          = orProtected(dobYearAttr);
            dobMonthAttr         = orProtected(dobMonthAttr);
            dobDayAttr           = orProtected(dobDayAttr);
            ficoScoreAttr        = orProtected(ficoScoreAttr);
            firstNameAttr        = orProtected(firstNameAttr);
            middleNameAttr       = orProtected(middleNameAttr);
            lastNameAttr         = orProtected(lastNameAttr);
            addressLine1Attr     = orProtected(addressLine1Attr);
            stateAttr            = orProtected(stateAttr);
            addressLine2Attr     = orProtected(addressLine2Attr);
            zipAttr              = orProtected(zipAttr);
            cityAttr             = orProtected(cityAttr);
            countryAttr          = orProtected(countryAttr);
            phone1AreaAttr       = orProtected(phone1AreaAttr);
            phone1MidAttr        = orProtected(phone1MidAttr);
            phone1EndAttr        = orProtected(phone1EndAttr);
            govtIssuedIdAttr     = orProtected(govtIssuedIdAttr);
            phone2AreaAttr       = orProtected(phone2AreaAttr);
            phone2MidAttr        = orProtected(phone2MidAttr);
            phone2EndAttr        = orProtected(phone2EndAttr);
            eftAccountIdAttr     = orProtected(eftAccountIdAttr);
            primaryFlagAttr      = orProtected(primaryFlagAttr);
        }

        /**
         * Returns the argument if non-null, or {@link AttributeMode#PROTECTED}
         * otherwise. Private static helper for the compact constructor.
         *
         * @param m the candidate attribute mode (may be {@code null})
         * @return {@code m} if non-null, otherwise {@link AttributeMode#PROTECTED}
         */
        private static AttributeMode orProtected(AttributeMode m) {
            return (m == null) ? AttributeMode.PROTECTED : m;
        }

        /**
         * Factory: a {@link FieldAttributes} instance with every field set to
         * {@link AttributeMode#PROTECTED}.
         *
         * <p>This is the resting state of an output screen before
         * {@code 3300-SETUP-SCREEN-ATTRS} has had a chance to mark fields editable,
         * highlighted, or in error. The COACTUP application class typically starts
         * here and then calls a mutator-equivalent factory that returns a new record
         * with the specific fields it wants to make editable for the current state.
         *
         * @return a {@link FieldAttributes} instance where every component is
         *         {@link AttributeMode#PROTECTED} (never {@code null})
         */
        public static FieldAttributes allProtected() {
            return uniform(AttributeMode.PROTECTED);
        }

        /**
         * Factory: a {@link FieldAttributes} instance with every field set to
         * {@link AttributeMode#UNPROTECTED}.
         *
         * <p>Used as a starting point when the operator is allowed to edit every
         * field on the screen (typical for the initial UPDATE state after the
         * account has been successfully looked up).
         *
         * @return a {@link FieldAttributes} instance where every component is
         *         {@link AttributeMode#UNPROTECTED} (never {@code null})
         */
        public static FieldAttributes allUnprotected() {
            return uniform(AttributeMode.UNPROTECTED);
        }

        /**
         * Internal factory: a {@link FieldAttributes} instance where every component
         * is the given {@link AttributeMode}.
         *
         * <p>Centralizes the 41-argument constructor call so that the public
         * {@link #allProtected()} and {@link #allUnprotected()} factories stay
         * concise. The argument count (41) matches the field count of the enclosing
         * record exactly; if the field set changes, both must change together.
         *
         * @param m the {@link AttributeMode} to apply to every component (must be
         *          non-null; callers always pass an enum literal)
         * @return a {@link FieldAttributes} instance with every component set to
         *         {@code m}
         */
        private static FieldAttributes uniform(AttributeMode m) {
            return new FieldAttributes(
                    m, m, m, m, m, m, m, m, m, m,
                    m, m, m, m, m, m, m, m, m, m,
                    m, m, m, m, m, m, m, m, m, m,
                    m, m, m, m, m, m, m, m, m, m, m
            );
        }
    }

    // ========================================================================
    // Nested enum for BMS attribute modes (3300-SETUP-SCREEN-ATTRS translation).
    // ========================================================================
    /**
     * Enumeration of BMS attribute modes set by the {@code 3300-SETUP-SCREEN-ATTRS}
     * paragraph.
     *
     * <p>The COBOL symbolic-map convention encodes four attribute bytes per field
     * (C={@code COLOR}, P={@code PS}, H={@code HILIGHT}, V={@code VALIDN}). The
     * combinations actually used by {@code COACTUPC} reduce to four conceptual modes,
     * captured here. The BMS-emitting layer (responsible for serializing this record
     * to the 3270 wire format) is the single point that re-decomposes a mode back
     * into the four wire-format bytes.
     *
     * <p>This is a closed taxonomy &mdash; a plain Java {@code enum} provides the
     * exhaustiveness guarantees required by AAP &sect;0.7.3: pattern-matching
     * {@code switch} on this enum is checked for exhaustiveness by the Java compiler
     * with no need for a {@code default} branch, which preserves the AAP mandate
     * that closed taxonomies must never use {@code default}.
     *
     * <p>Modes:
     * <ul>
     *   <li>{@link #UNPROTECTED} &mdash; editable field; cursor accepts input
     *       (BMS {@code UNPROT} attribute).</li>
     *   <li>{@link #PROTECTED} &mdash; display-only field; cursor cannot land
     *       (BMS {@code ASKIP,NORM} attribute).</li>
     *   <li>{@link #PROTECTED_HIGHLIGHTED} &mdash; display-only with high-brightness
     *       highlighting (BMS {@code ASKIP,BRT}); used to draw attention to a key
     *       value the operator should see.</li>
     *   <li>{@link #ERROR} &mdash; editable field flagged in error state
     *       (BMS {@code UNPROT,BRT} with red color); marks a field that failed
     *       validation in the previous edit pass.</li>
     * </ul>
     */
    public enum AttributeMode {
        /** Editable field; cursor accepts input. BMS {@code UNPROT}. */
        UNPROTECTED,
        /** Display-only field; cursor cannot land. BMS {@code ASKIP,NORM}. */
        PROTECTED,
        /** Display-only field with high-brightness highlighting. BMS {@code ASKIP,BRT}. */
        PROTECTED_HIGHLIGHTED,
        /** Editable field flagged in error state. BMS {@code UNPROT,BRT} with red color. */
        ERROR
    }
}
