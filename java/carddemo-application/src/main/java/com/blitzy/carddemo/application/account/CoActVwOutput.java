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
 * BMS output DTO record carrying all fields sent to the 3270 terminal for the
 * <strong>COACTVW</strong> (Account View) screen.
 *
 * <p>Source artifacts:
 * <ul>
 *   <li>Symbolic map copybook: {@code app/cpy-bms/COACTVW.CPY},
 *       structure {@code CACTVWAO} (lines 241-465).</li>
 *   <li>BMS map definition: {@code app/bms/COACTVW.bms},
 *       map {@code CACTVWA} (size 24x80).</li>
 *   <li>Translated COBOL program: {@code COACTVWC}
 *       ({@code app/cbl/COACTVWC.cbl}).</li>
 * </ul>
 *
 * <h2>Role &mdash; entry-contract DTO (output side)</h2>
 * <p>Per AAP &sect;0.3.5 and &sect;0.4.1, BMS maps are translated into <em>entry-contract DTO
 * records</em> on the corresponding application class. This record is the Java analog of the
 * output view of the BMS symbolic structure (the {@code CACTVWAO REDEFINES CACTVWAI}
 * overlay in {@code COACTVW.CPY}): it carries the field values supplied by application
 * logic to {@code EXEC CICS SEND MAP}, where they are rendered onto the 3270 terminal.
 * There is no web framework, no Spring binding, no Jakarta Bean Validation, no view
 * templating engine; the record is a plain Java carrier.
 *
 * <h2>View-only screen semantics</h2>
 * <p>COACTVW is a <strong>view-only</strong> account inspection screen. All fields except
 * the account search key are statically protected at the BMS map level
 * ({@code app/bms/COACTVW.bms} {@code DFHMDF} declarations omit the {@code UNPROT}
 * attribute on every field except {@code ACCTSID}, which is the only {@code FSET,IC,NORM,UNPROT}
 * field). Because protection is baked into the BMS map definition, the runtime has no need
 * to manage per-field attribute bytes (the {@code C}/{@code P}/{@code H}/{@code V} suffixes
 * in the symbolic copybook); the {@code 3300-SETUP-SCREEN-ATTRS} paragraph in
 * {@code COACTVWC.cbl} is effectively a no-op. Consequently this Java DTO has no nested
 * {@code FieldAttributes} record &mdash; unlike its update-screen counterpart
 * {@code CoActUpOutput}.
 *
 * <h2>Differences from {@code CoActUpOutput}</h2>
 * <ul>
 *   <li><strong>No field-attribute control structure.</strong> All fields are statically
 *       protected by the BMS map; no per-field {@code attrib} bytes are managed at
 *       runtime.</li>
 *   <li><strong>Consolidated date fields.</strong> {@code openDate},
 *       {@code expirationDate}, {@code reissueDate}, and {@code dob} are single
 *       10-character strings in {@code CCYY-MM-DD} format. COACTUP exposes them as
 *       split {@code year}/{@code month}/{@code day} triples to enable per-component
 *       error highlighting; COACTVW has no edit surface, so a consolidated form is
 *       sufficient.</li>
 *   <li><strong>Consolidated SSN.</strong> {@code ssn} is a single 12-character string
 *       in {@code NNN-NN-NNNN} format. COACTUP splits SSN into three components.</li>
 *   <li><strong>Consolidated phones.</strong> {@code phone1} and {@code phone2} are
 *       single 13-character strings in {@code (NNN)NNN-NNNN} format.</li>
 *   <li><strong>No function-key bar fields.</strong> The COACTVW symbolic copybook has
 *       no {@code FKEYS}, {@code FKEY05}, or {@code FKEY12} symbolic fields (compare
 *       with the COACTUP layout). The screen footer is a static
 *       {@code DFHMDF INITIAL='  F3=Exit '} at line 24.</li>
 * </ul>
 *
 * <h2>Field formatting expectations</h2>
 * <p>Numeric/monetary fields arrive on this DTO <em>pre-formatted as {@link String}</em>.
 * The application class (CoActVwC) is responsible for converting the underlying
 * {@code BigDecimal} balance and limit values into the {@code +ZZZ,ZZZ,ZZZ.99} edited
 * picture that the BMS map declares ({@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} on
 * {@code ACRDLIMO}, {@code ACSHLIMO}, {@code ACURBALO}, {@code ACRCYCRO}, and
 * {@code ACRCYDBO}). Likewise dates are formatted into {@code CCYY-MM-DD} strings before
 * being placed on this DTO. This DTO does not perform numeric or date formatting itself;
 * it is a pure carrier of pre-formatted display strings.
 *
 * <h2>Null and emptiness semantics &mdash; COBOL parity</h2>
 * <p>In COBOL, an unset BMS {@code PIC X(n)} output field is SPACES on SEND-MAP, never
 * null (no null pointer exists in COBOL). The {@code COACTVWC} initialization paragraph
 * {@code 3100-SCREEN-INIT} performs {@code MOVE LOW-VALUES TO CACTVWAO} followed by
 * {@code INITIALIZE CACTVWAO}, which sets every PIC X field to SPACES. To preserve that
 * behavior precisely, the compact constructor below replaces every {@code null}
 * {@link String} component with the empty {@link String} <code>""</code>. This means
 * downstream consumers &mdash; and the BMS-emitting layer that ultimately serializes
 * this record to the 3270 wire format &mdash; can safely treat every component as a
 * non-null {@link String} without first checking for null.
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
 *   <caption>Mapping from BMS symbolic copybook {@code CACTVWAO} (output view) to Java
 *            record components</caption>
 *   <tr><th>Symbolic BMS field</th><th>COBOL PIC</th><th>Java component</th></tr>
 *   <tr><td>TRNNAMEO</td><td>X(4)</td><td>{@link #trnName()}</td></tr>
 *   <tr><td>TITLE01O</td><td>X(40)</td><td>{@link #title01()}</td></tr>
 *   <tr><td>CURDATEO</td><td>X(8)</td><td>{@link #curDate()}</td></tr>
 *   <tr><td>PGMNAMEO</td><td>X(8)</td><td>{@link #pgmName()}</td></tr>
 *   <tr><td>TITLE02O</td><td>X(40)</td><td>{@link #title02()}</td></tr>
 *   <tr><td>CURTIMEO</td><td>X(8)</td><td>{@link #curTime()}</td></tr>
 *   <tr><td>ACCTSIDO</td><td>X(11)</td><td>{@link #acctSid()}</td></tr>
 *   <tr><td>ACSTTUSO</td><td>X(1)</td><td>{@link #acStatus()}</td></tr>
 *   <tr><td>ADTOPENO</td><td>X(10)</td><td>{@link #openDate()}</td></tr>
 *   <tr><td>ACRDLIMO</td><td>+ZZZ,ZZZ,ZZZ.99</td><td>{@link #creditLimit()}</td></tr>
 *   <tr><td>AEXPDTO</td><td>X(10)</td><td>{@link #expirationDate()}</td></tr>
 *   <tr><td>ACSHLIMO</td><td>+ZZZ,ZZZ,ZZZ.99</td><td>{@link #cashCreditLimit()}</td></tr>
 *   <tr><td>AREISDTO</td><td>X(10)</td><td>{@link #reissueDate()}</td></tr>
 *   <tr><td>ACURBALO</td><td>+ZZZ,ZZZ,ZZZ.99</td><td>{@link #currentBalance()}</td></tr>
 *   <tr><td>ACRCYCRO</td><td>+ZZZ,ZZZ,ZZZ.99</td><td>{@link #currCycCredit()}</td></tr>
 *   <tr><td>AADDGRPO</td><td>X(10)</td><td>{@link #accountGroup()}</td></tr>
 *   <tr><td>ACRCYDBO</td><td>+ZZZ,ZZZ,ZZZ.99</td><td>{@link #currCycDebit()}</td></tr>
 *   <tr><td>ACSTNUMO</td><td>X(9)</td><td>{@link #custNumber()}</td></tr>
 *   <tr><td>ACSTSSNO</td><td>X(12)</td><td>{@link #ssn()}</td></tr>
 *   <tr><td>ACSTDOBO</td><td>X(10)</td><td>{@link #dob()}</td></tr>
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
 *   <tr><td>ACSPHN1O</td><td>X(13)</td><td>{@link #phone1()}</td></tr>
 *   <tr><td>ACSGOVTO</td><td>X(20)</td><td>{@link #govtIssuedId()}</td></tr>
 *   <tr><td>ACSPHN2O</td><td>X(13)</td><td>{@link #phone2()}</td></tr>
 *   <tr><td>ACSEFTCO</td><td>X(10)</td><td>{@link #eftAccountId()}</td></tr>
 *   <tr><td>ACSPFLGO</td><td>X(1)</td><td>{@link #primaryFlag()}</td></tr>
 *   <tr><td>INFOMSGO</td><td>X(45)</td><td>{@link #infoMsg()}</td></tr>
 *   <tr><td>ERRMSGO</td><td>X(78)</td><td>{@link #errMsg()}</td></tr>
 * </table>
 *
 * <h2>Forbidden idioms (per AAP)</h2>
 * <ul>
 *   <li>No Spring annotations &mdash; this record is plain Java.</li>
 *   <li>No Lombok &mdash; record components and accessors are explicit.</li>
 *   <li>No Jakarta Bean Validation &mdash; validation is hand-written in the compact
 *       constructor.</li>
 *   <li>No setters &mdash; records are immutable.</li>
 *   <li>No {@code java.util.Date} &mdash; date strings stay as raw display bytes on
 *       this DTO and are formatted upstream by the application class from
 *       {@code java.time.LocalDate} values.</li>
 * </ul>
 *
 * @param trnName         CICS transaction id displayed in row 1 (e.g., {@code "CAVW"})
 *                        ({@code TRNNAMEO PIC X(4)})
 * @param title01         line-1 screen title (e.g., {@code "CardDemo"})
 *                        ({@code TITLE01O PIC X(40)})
 * @param curDate         current date in {@code MM/DD/YY} format ({@code CURDATEO PIC X(8)})
 * @param pgmName         current program id (e.g., {@code "COACTVWC"})
 *                        ({@code PGMNAMEO PIC X(8)})
 * @param title02         line-2 screen title (e.g., {@code "View Account"})
 *                        ({@code TITLE02O PIC X(40)})
 * @param curTime         current time in {@code HH:MM:SS} format ({@code CURTIMEO PIC X(8)})
 * @param acctSid         11-digit account id displayed in row 5
 *                        ({@code ACCTSIDO PIC X(11)})
 * @param acStatus        account active status (Y/N) ({@code ACSTTUSO PIC X(1)})
 * @param openDate        account open date in {@code CCYY-MM-DD} format
 *                        ({@code ADTOPENO PIC X(10)})
 * @param creditLimit     credit limit pre-formatted via picture {@code +ZZZ,ZZZ,ZZZ.99}
 *                        ({@code ACRDLIMO}, 15 display characters)
 * @param expirationDate  card expiration date in {@code CCYY-MM-DD} format
 *                        ({@code AEXPDTO PIC X(10)})
 * @param cashCreditLimit cash credit limit pre-formatted via {@code +ZZZ,ZZZ,ZZZ.99}
 *                        ({@code ACSHLIMO}, 15 display characters)
 * @param reissueDate     card reissue date in {@code CCYY-MM-DD} format
 *                        ({@code AREISDTO PIC X(10)})
 * @param currentBalance  current balance pre-formatted via {@code +ZZZ,ZZZ,ZZZ.99}
 *                        ({@code ACURBALO}, 15 display characters)
 * @param currCycCredit   current-cycle credit total pre-formatted via {@code +ZZZ,ZZZ,ZZZ.99}
 *                        ({@code ACRCYCRO}, 15 display characters)
 * @param accountGroup    account group id used for discount lookups
 *                        ({@code AADDGRPO PIC X(10)})
 * @param currCycDebit    current-cycle debit total pre-formatted via {@code +ZZZ,ZZZ,ZZZ.99}
 *                        ({@code ACRCYDBO}, 15 display characters)
 * @param custNumber      customer number associated with this account
 *                        ({@code ACSTNUMO PIC X(9)})
 * @param ssn             customer SSN in {@code NNN-NN-NNNN} format, single field
 *                        ({@code ACSTSSNO PIC X(12)})
 * @param dob             customer date of birth in {@code CCYY-MM-DD} format
 *                        ({@code ACSTDOBO PIC X(10)})
 * @param ficoScore       customer FICO score, 3 digits ({@code ACSTFCOO PIC X(3)})
 * @param firstName       customer first name ({@code ACSFNAMO PIC X(25)})
 * @param middleName      customer middle name ({@code ACSMNAMO PIC X(25)})
 * @param lastName        customer last name ({@code ACSLNAMO PIC X(25)})
 * @param addressLine1    customer address line 1 ({@code ACSADL1O PIC X(50)})
 * @param state           customer state abbreviation ({@code ACSSTTEO PIC X(2)})
 * @param addressLine2    customer address line 2 ({@code ACSADL2O PIC X(50)})
 * @param zip             customer ZIP code ({@code ACSZIPCO PIC X(5)})
 * @param city            customer city ({@code ACSCITYO PIC X(50)})
 * @param country         customer country code ({@code ACSCTRYO PIC X(3)})
 * @param phone1          customer phone 1 in {@code (NNN)NNN-NNNN} format, single field
 *                        ({@code ACSPHN1O PIC X(13)})
 * @param govtIssuedId    customer government-issued id ({@code ACSGOVTO PIC X(20)})
 * @param phone2          customer phone 2 in {@code (NNN)NNN-NNNN} format, single field
 *                        ({@code ACSPHN2O PIC X(13)})
 * @param eftAccountId    customer EFT account id ({@code ACSEFTCO PIC X(10)})
 * @param primaryFlag     primary cardholder flag (Y/N) ({@code ACSPFLGO PIC X(1)})
 * @param infoMsg         informational message line ({@code INFOMSGO PIC X(45)})
 * @param errMsg          error message line ({@code ERRMSGO PIC X(78)})
 * @see CoActVwInput
 * @since 1.0.0
 */
public record CoActVwOutput(

        // ============================================================================
        // Header fields (rows 1-2 of the 24x80 BMS map). Populated by the application
        // class on every SEND-MAP so the operator always sees the current transaction id,
        // program id, date, and time.
        // ============================================================================
        String trnName,         // TRNNAMEO  PIC X(4)
        String title01,         // TITLE01O  PIC X(40)
        String curDate,         // CURDATEO  PIC X(8)   — MM/DD/YY
        String pgmName,         // PGMNAMEO  PIC X(8)
        String title02,         // TITLE02O  PIC X(40)  — typically "View Account"
        String curTime,         // CURTIMEO  PIC X(8)   — HH:MM:SS

        // ============================================================================
        // Account key (row 5). On a typical SEND-MAP this echoes back the account id the
        // operator typed; on the initial SEND-MAP it is empty.
        // ============================================================================
        String acctSid,         // ACCTSIDO  PIC X(11)

        // ============================================================================
        // Account fields (rows 5-10). All view-only; the BMS map omits the UNPROT
        // attribute, so the operator cannot edit any of these even if a typo were to
        // place the cursor here. Monetary fields arrive pre-formatted via the BMS
        // PICOUT=+ZZZ,ZZZ,ZZZ.99 picture (15 display characters including sign and
        // separators); date fields arrive as consolidated CCYY-MM-DD strings.
        // ============================================================================
        String acStatus,        // ACSTTUSO  PIC X(1)   — Y/N
        String openDate,        // ADTOPENO  PIC X(10)  — CCYY-MM-DD
        String creditLimit,     // ACRDLIMO  +ZZZ,ZZZ,ZZZ.99 (15 chars)
        String expirationDate,  // AEXPDTO   PIC X(10)  — CCYY-MM-DD
        String cashCreditLimit, // ACSHLIMO  +ZZZ,ZZZ,ZZZ.99 (15 chars)
        String reissueDate,     // AREISDTO  PIC X(10)  — CCYY-MM-DD
        String currentBalance,  // ACURBALO  +ZZZ,ZZZ,ZZZ.99 (15 chars)
        String currCycCredit,   // ACRCYCRO  +ZZZ,ZZZ,ZZZ.99 (15 chars)
        String accountGroup,    // AADDGRPO  PIC X(10)
        String currCycDebit,    // ACRCYDBO  +ZZZ,ZZZ,ZZZ.99 (15 chars)

        // ============================================================================
        // Customer fields (rows 12-20). Joined onto the account record via the
        // CARD-XREF lookup performed by COACTVWC.
        // ============================================================================
        String custNumber,      // ACSTNUMO  PIC X(9)
        String ssn,             // ACSTSSNO  PIC X(12)  — NNN-NN-NNNN
        String dob,             // ACSTDOBO  PIC X(10)  — CCYY-MM-DD
        String ficoScore,       // ACSTFCOO  PIC X(3)
        String firstName,       // ACSFNAMO  PIC X(25)
        String middleName,      // ACSMNAMO  PIC X(25)
        String lastName,        // ACSLNAMO  PIC X(25)
        String addressLine1,    // ACSADL1O  PIC X(50)
        String state,           // ACSSTTEO  PIC X(2)
        String addressLine2,    // ACSADL2O  PIC X(50)
        String zip,             // ACSZIPCO  PIC X(5)
        String city,            // ACSCITYO  PIC X(50)
        String country,         // ACSCTRYO  PIC X(3)
        String phone1,          // ACSPHN1O  PIC X(13)  — (NNN)NNN-NNNN
        String govtIssuedId,    // ACSGOVTO  PIC X(20)
        String phone2,          // ACSPHN2O  PIC X(13)  — (NNN)NNN-NNNN
        String eftAccountId,    // ACSEFTCO  PIC X(10)
        String primaryFlag,     // ACSPFLGO  PIC X(1)   — Y/N

        // ============================================================================
        // Status messages (rows 22-23). Populated by the application class to surface
        // success/error feedback back to the operator.
        // ============================================================================
        String infoMsg,         // INFOMSGO  PIC X(45)
        String errMsg           // ERRMSGO   PIC X(78)

) {

    /**
     * Compact (canonical) constructor.
     *
     * <p>Normalizes every {@link String} component so that a {@code null} reference is
     * converted to the empty {@link String} <code>""</code>. This mirrors COBOL
     * SEND-MAP semantics where unfilled BMS {@code PIC X(n)} output fields are SPACES,
     * never undefined. In particular it matches the {@code 3100-SCREEN-INIT} paragraph
     * of {@code COACTVWC.cbl}, which performs
     * {@code MOVE LOW-VALUES TO CACTVWAO} followed by {@code INITIALIZE CACTVWAO}
     * to blank every output field before the application logic populates the ones it
     * actually uses.
     *
     * <p>Uses <strong>JEP 513 Flexible Constructor Bodies</strong> (finalized in Java
     * 25): the normalization statements run before the implicit canonical field
     * assignment, which is the appropriate location for COBOL-style "default to
     * SPACES" output initialization.
     *
     * <p>The constructor never throws. The downstream BMS-emitting layer (translated
     * from {@code EXEC CICS SEND MAP}) never expects null fields; this constructor
     * defensively absorbs any null reference that a Java caller might inadvertently
     * pass and converts it to the empty string before assignment.
     *
     * <p>No length validation is performed here. The application layer ({@code CoActVwC})
     * is responsible for ensuring that each component does not exceed the on-screen
     * PIC length declared in the BMS map; truncation, if any, is enforced downstream by
     * the BMS-emitting layer when it serializes this record into the 3270 wire format.
     */
    public CoActVwOutput {
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
     * Factory: an empty output record with every string component blank.
     *
     * <p>Matches the {@code 3100-SCREEN-INIT} paragraph of {@code COACTVWC.cbl},
     * which performs {@code MOVE LOW-VALUES TO CACTVWAO} followed by
     * {@code INITIALIZE CACTVWAO}. The result is an output DTO suitable for use as
     * a SEND-MAP payload on the first dispatch into COACTVW, before any application
     * logic has had a chance to populate fields. Downstream code can then build up
     * the displayed screen by constructing a new record with selected fields supplied.
     *
     * <p>Since records are immutable and the canonical constructor performs null
     * coalescing, this factory returns the canonical "all SPACES" output instance.
     *
     * @return a fully-blank output record (never {@code null}); all 37 string
     *         components are the empty string {@code ""}
     */
    public static CoActVwOutput blank() {
        return new CoActVwOutput(
                // header (6) — trnName, title01, curDate, pgmName, title02, curTime
                "", "", "", "", "", "",
                // acctSid (1)
                "",
                // account fields (10) — acStatus, openDate, creditLimit, expirationDate,
                // cashCreditLimit, reissueDate, currentBalance, currCycCredit, accountGroup,
                // currCycDebit
                "", "", "", "", "", "", "", "", "", "",
                // customer identity (7) — custNumber, ssn, dob, ficoScore, firstName,
                // middleName, lastName
                "", "", "", "", "", "", "",
                // customer detail (10) — addressLine1, state, addressLine2, zip, city,
                // country, phone1, govtIssuedId, phone2, eftAccountId
                "", "", "", "", "", "", "", "", "", "",
                // primaryFlag + status messages (3) — primaryFlag, infoMsg, errMsg
                "", "", ""
        );
    }
}
