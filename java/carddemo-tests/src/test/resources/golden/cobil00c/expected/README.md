# COBIL00C Golden-Record Expected Fixture Contract

Authoritative contract for the COBIL00C (Bill Payment online; CICS transaction CB00) golden-record harness. Documents the verbatim message catalog, BMS map layout, multi-file mutation invariants, test scenario inventory, and DO-NOT-modify rules.

Status: PLACEHOLDER ARTIFACTS pending COBOL capture per AAP Sec 0.6.11. Test class `com.blitzy.carddemo.tests.golden.CoBil00CGoldenTest` MUST be annotated `@Disabled` until captures are produced via the procedure in `java/MIGRATION_NOTES.md` Sec 1.6.

## Phase 0 -- Header and Authority Cascade

### Overview

COBIL00C is the CICS COBOL program implementing the online bill payment screen and payment posting flow for transaction CB00. It allows a user to pay an account balance in full and creates the corresponding online bill payment transaction record. The program is uniquely characterized among CardDemo online programs by performing TWO file mutations atomically within a single successful execution: `EXEC CICS WRITE TRANSACT` (new 350-byte TRAN-RECORD) and `EXEC CICS REWRITE ACCTDAT` (in-place update of ACCT-CURR-BAL).

### Authority

| AAP Section | Topic |
|-------------|-------|
| Sec 0.1.1 | `app/` tree preservation; immutable reference implementation |
| Sec 0.2.1 | In-scope: `java/carddemo-tests/src/test/resources/golden/**` wildcard |
| Sec 0.3.1 | Harness structure: `<program>/input/` + `<program>/expected/` |
| Sec 0.4.1 | BMS map -> DTO records; ASCII fixtures NOT copied; one Java class per COBOL PROGRAM-ID |
| Sec 0.6.1 | CRITICAL: BigDecimal scale=2; MathContext.DECIMAL128; no `double`/`float` for money |
| Sec 0.6.4 | `java.time` mandate; no `java.util.Date`/`Calendar` |
| Sec 0.6.5 | `java.nio.file` mandate; no `java.io.File` |
| Sec 0.6.6 | `ScopedValue<Clock>` for deterministic CURDATE/CURTIME/TRAN-ORIG-TS/TRAN-PROC-TS |
| Sec 0.6.11 | Golden-record harness as PR gate; `@Disabled` until captured |
| Sec 0.6.12 | No Spring/Hibernate/PostgreSQL/Docker/AWS |
| Sec 0.7.1 | Minimal Change Clause; verbatim messages BYTE-PERFECT including DOUBLE-SPACE |
| Sec 0.7.2 | CRITICAL: PAN masking last-4 in logs; BMS screen exempt |
| Sec 0.7.4 | No JEP preview features; no `default` branches in sealed switch |
| Sec 0.7.5 | Capture procedure cross-reference to `java/MIGRATION_NOTES.md` Sec 1.6 |

### Sibling Pattern Reference

This document follows the 13-phase canonical pattern established by `golden/cotrn02c/expected/README.md`. Comparable structurally to COTRN02C (Transaction Add) because both online programs mutate the TRANSACT file via WRITE; uniquely COBIL00C also REWRITEs ACCTDAT in the same logical unit of work.

### @Disabled Mandate

Until byte-perfect COBOL captures replace the placeholder fixtures in this directory, the test class `com.blitzy.carddemo.tests.golden.CoBil00CGoldenTest` MUST be annotated with `@org.junit.jupiter.api.Disabled("Golden capture pending per MIGRATION_NOTES.md Sec 1.6")`.

## Phase 1 -- COBOL Source: PROGRAM-ID, WS-VARIABLES, COPY Directives

### PROGRAM-ID

```cobol
IDENTIFICATION DIVISION.
PROGRAM-ID. COBIL00C.
AUTHOR.     AWS.
```

Source: `app/cbl/COBIL00C.cbl:L23-L25`; 572 total lines.

### WS-VARIABLES (L36-L61)

| Field | PIC | Initial | Notes |
|-------|-----|---------|-------|
| WS-PGMNAME | X(08) | `'COBIL00C'` | L37 |
| WS-TRANID | X(04) | `'CB00'` | L38 |
| WS-MESSAGE | X(80) | SPACES | L39 -- ERRMSG buffer |
| WS-TRANSACT-FILE | X(08) | `'TRANSACT'` | L40 |
| WS-ACCTDAT-FILE | X(08) | `'ACCTDAT '` | L41 -- 7 chars + 1 trailing space |
| WS-CXACAIX-FILE | X(08) | `'CXACAIX '` | L42 -- 7 chars + 1 trailing space |
| WS-ERR-FLG | X(01) | `'N'` | L43, 88: ERR-FLG-ON='Y', ERR-FLG-OFF='N' |
| WS-RESP-CD / WS-REAS-CD | S9(09) COMP | 0 | L46-L47 -- CICS RESP / RESP2 |
| WS-USR-MODIFIED | X(01) | `'N'` | L48, 88: USR-MODIFIED-YES/NO |
| WS-CONF-PAY-FLG | X(01) | `'N'` | L51, 88: CONF-PAY-YES='Y', CONF-PAY-NO='N' |
| WS-TRAN-AMT | +99999999.99 | -- | L55 -- BigDecimal scale=2 |
| WS-CURR-BAL | +9999999999.99 | -- | L56 -- BigDecimal scale=2 |
| WS-TRAN-ID-NUM | 9(16) | ZEROS | L57 -- TRAN-ID auto-increment |
| WS-TRAN-DATE | X(08) | `'00/00/00'` | L58 |
| WS-ABS-TIME | S9(15) COMP-3 | 0 | L59 -- ASKTIME timestamp |
| WS-CUR-DATE-X10 / WS-CUR-TIME-X08 | X(10) / X(08) | SPACES | L60-L61 -- FORMATTIME outputs |

### COPY Directives (L63-L85)

| Copybook | Purpose |
|----------|---------|
| COCOM01Y (L63) | CARDDEMO-COMMAREA |
| COBIL00 (L74) | BMS symbolic map COBIL0AI/COBIL0AO |
| COTTL01Y (L76) | Screen titles CCDA-TITLE01/CCDA-TITLE02 |
| CSDAT01Y (L77) | Date/time working structures + WS-TIMESTAMP |
| CSMSG01Y (L78) | CCDA-MSG-INVALID-KEY and CCDA-MSG-THANK-YOU |
| CVACT01Y (L80) | ACCOUNT-RECORD (300 bytes) |
| CVACT03Y (L81) | CARD-XREF-RECORD (50 bytes) |
| CVTRA05Y (L82) | TRAN-RECORD (350 bytes) |
| DFHAID (L84) | CICS AID-key constants (DFHENTER, DFHPF3, DFHPF4, etc.) |
| DFHBMSCA (L85) | BMS attribute constants (DFHGREEN, DFHRED, etc.) |

## Phase 2 -- Commarea Layout: CDEMO-CB00-INFO

```cobol
COPY COCOM01Y.
   05 CDEMO-CB00-INFO.
      10 CDEMO-CB00-TRNID-FIRST     PIC X(16).
      10 CDEMO-CB00-TRNID-LAST      PIC X(16).
      10 CDEMO-CB00-PAGE-NUM        PIC 9(08).
      10 CDEMO-CB00-NEXT-PAGE-FLG   PIC X(01) VALUE 'N'.
         88 NEXT-PAGE-YES                     VALUE 'Y'.
         88 NEXT-PAGE-NO                      VALUE 'N'.
      10 CDEMO-CB00-TRN-SEL-FLG     PIC X(01).
      10 CDEMO-CB00-TRN-SELECTED    PIC X(16).
```

Source: `app/cbl/COBIL00C.cbl:L63-L72`.

### Auto-Trigger Field (CRITICAL)

`CDEMO-CB00-TRN-SELECTED PIC X(16)` is the auto-trigger field. At L116-L121, the first-pass entry path checks if this field is populated (NOT spaces, NOT low-values); if so, it MOVEs the value to `ACTIDINI OF COBIL0AI` and immediately PERFORMs `PROCESS-ENTER-KEY`. This means a calling program (for example COCRDLIC card-list selection) can populate this commarea field to drive a direct payment workflow without an explicit user keystroke.

Java translation: the `CardDemoCommarea` record exposes `CdemoCb00Info` as a nested record. The auto-trigger semantics are realized in the dispatch logic of `CoBil00C.handle(...)`.

## Phase 3 -- MAIN-PARA Dispatch (L99-L149)

### EIBCALEN = 0 Branch (L107-L109)

```cobol
IF EIBCALEN = 0
    MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
    PERFORM RETURN-TO-PREV-SCREEN
```

Direct entry without commarea -> XCTL to COSGN00C signon screen.

### First-Pass with Commarea (L112-L122)

```cobol
IF NOT CDEMO-PGM-REENTER
    SET CDEMO-PGM-REENTER    TO TRUE
    MOVE LOW-VALUES          TO COBIL0AO
    MOVE -1       TO ACTIDINL OF COBIL0AI
    IF CDEMO-CB00-TRN-SELECTED NOT = SPACES AND LOW-VALUES
        MOVE CDEMO-CB00-TRN-SELECTED TO ACTIDINI OF COBIL0AI
        PERFORM PROCESS-ENTER-KEY
    END-IF
    PERFORM SEND-BILLPAY-SCREEN
```

### Re-Entry EIBAID Dispatch (L123-L142)

| EIBAID | Action | Line |
|--------|--------|------|
| DFHENTER | PERFORM PROCESS-ENTER-KEY | L126-L127 |
| DFHPF3 | XCTL to CDEMO-FROM-PROGRAM (or COMEN01C if blank); via RETURN-TO-PREV-SCREEN | L128-L135 |
| DFHPF4 | PERFORM CLEAR-CURRENT-SCREEN | L136-L137 |
| WHEN OTHER | Set ERR-FLG-ON; MOVE CCDA-MSG-INVALID-KEY to WS-MESSAGE; SEND-BILLPAY-SCREEN | L138-L141 |

The CCDA-MSG-INVALID-KEY constant is `'Invalid key pressed. Please see below...         '` (PIC X(50); 41 text chars + 9 trailing spaces). See `app/cpy/CSMSG01Y.cpy:L20-L21`. Java translation MUST preserve all 50 chars BYTE-PERFECT.

### Java Translation Rule (AAP Sec 0.7.4)

The EVALUATE EIBAID at L125-L142 translates to a Java `switch` on a sealed `AidKey` hierarchy. NO `default` branch -- the sealed permits enumerate ENTER, PF3, PF4, plus all other PF-key permits; the `WHEN OTHER` COBOL branch translates to explicitly listed permits for invalid keys (PA1, PA2, PFK01, PFK02, PFK05-PFK12, etc.) that all map to the invalid-key message handler.

### Exit (L146-L149)

```cobol
EXEC CICS RETURN
          TRANSID (WS-TRANID)
          COMMAREA (CARDDEMO-COMMAREA)
END-EXEC.
```

TRANSID is always `'CB00'`.

## Phase 4 -- PROCESS-ENTER-KEY (L154-L244)

### Outer EVALUATE on ACTIDIN Empty (L158-L167)

If `ACTIDINI = SPACES OR LOW-VALUES`, set ERR-FLG-ON, set WS-MESSAGE to `'Acct ID can NOT be empty...'`, move -1 to ACTIDINL (cursor), PERFORM SEND-BILLPAY-SCREEN. Otherwise CONTINUE to the inner EVALUATE.

### Inner EVALUATE on CONFIRMI (L169-L195)

After ACTIDIN is moved to ACCT-ID and XREF-ACCT-ID:

| CONFIRMI Value | Action | Line |
|----------------|--------|------|
| `'Y'` or `'y'` | Set CONF-PAY-YES; PERFORM READ-ACCTDAT-FILE | L174-L177 |
| `'N'` or `'n'` | PERFORM CLEAR-CURRENT-SCREEN; set ERR-FLG-ON | L178-L181 |
| SPACES or LOW-VALUES | PERFORM READ-ACCTDAT-FILE (to display balance) | L182-L184 |
| WHEN OTHER | `'Invalid value. Valid values are (Y/N)...'`; cursor on CONFIRM | L185-L190 |

### Balance Display and Zero-Balance Guard (L193-L206)

```cobol
MOVE ACCT-CURR-BAL TO WS-CURR-BAL
MOVE WS-CURR-BAL   TO CURBALI    OF COBIL0AI

IF NOT ERR-FLG-ON
    IF ACCT-CURR-BAL <= ZEROS AND
       ACTIDINI OF COBIL0AI NOT = SPACES AND LOW-VALUES
        MOVE 'Y'     TO WS-ERR-FLG
        MOVE 'You have nothing to pay...' TO WS-MESSAGE
        MOVE -1       TO ACTIDINL OF COBIL0AI
        PERFORM SEND-BILLPAY-SCREEN
    END-IF
END-IF
```

The `ACCT-CURR-BAL <= ZEROS` guard rejects accounts with zero or negative balances. The asymmetry with the happy path (which then computes the balance to exactly zero post-payment) means the guard fires only on the FIRST request for a zero/negative-balance account; after a successful payment the account itself acquires balance == 0.00 and subsequent re-entry would hit this guard.

### Happy Path (CONF-PAY-YES, L210-L240)

```cobol
IF CONF-PAY-YES
    PERFORM READ-CXACAIX-FILE
    MOVE HIGH-VALUES TO TRAN-ID
    PERFORM STARTBR-TRANSACT-FILE
    PERFORM READPREV-TRANSACT-FILE
    PERFORM ENDBR-TRANSACT-FILE
    MOVE TRAN-ID  TO WS-TRAN-ID-NUM
    ADD 1 TO WS-TRAN-ID-NUM
    INITIALIZE TRAN-RECORD
    MOVE WS-TRAN-ID-NUM  TO TRAN-ID
    *> ... 8 MOVEs populating fixed TRAN-RECORD metadata (see signature below) ...
    MOVE ACCT-CURR-BAL   TO TRAN-AMT
    MOVE XREF-CARD-NUM   TO TRAN-CARD-NUM
    PERFORM GET-CURRENT-TIMESTAMP
    MOVE WS-TIMESTAMP    TO TRAN-ORIG-TS TRAN-PROC-TS
    PERFORM WRITE-TRANSACT-FILE
    COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT
    PERFORM UPDATE-ACCTDAT-FILE
ELSE
    MOVE 'Confirm to make a bill payment...' TO WS-MESSAGE
    MOVE -1  TO CONFIRML OF COBIL0AI
END-IF
```

### Fixed TRAN-RECORD Metadata (CRITICAL Identifying Signature)

Every TRAN-RECORD produced by COBIL00C has this fixed signature:

- TRAN-TYPE-CD = `'02'`
- TRAN-CAT-CD = `2`
- TRAN-SOURCE = `'POS TERM'`
- TRAN-DESC = `'BILL PAYMENT - ONLINE'`
- TRAN-MERCHANT-ID = `999999999`
- TRAN-MERCHANT-NAME = `'BILL PAYMENT'`
- TRAN-MERCHANT-CITY = `'N/A'`
- TRAN-MERCHANT-ZIP = `'N/A'`

### Full-Balance Payment Invariant (CRITICAL)

L224 sets TRAN-AMT to ACCT-CURR-BAL (the full current balance). L234 computes ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT. Substituting: ACCT-CURR-BAL_after = 0.00 for any successful bill payment. BigDecimal scale=2 preserved per AAP Sec 0.6.1 -- `0.00` MUST NOT normalize to `0` or `0.0`.

## Phase 5 -- READ-ACCTDAT-FILE (L343-L372) + UPDATE-ACCTDAT-FILE (L377-L403) + READ-CXACAIX-FILE (L408-L436)

### READ-ACCTDAT-FILE (L343-L372)

`EXEC CICS READ` on `WS-ACCTDAT-FILE` (`'ACCTDAT '`) INTO ACCOUNT-RECORD with UPDATE intent (key: ACCT-ID).

| DFHRESP | Action | Line | Message |
|---------|--------|------|---------|
| NORMAL | CONTINUE | L358 | -- |
| NOTFND | ERR-FLG-ON; cursor on ACTIDIN; SEND-BILLPAY-SCREEN | L359-L364 | `Account ID NOT found...` (L361) |
| OTHER | DISPLAY RESP+REAS; ERR-FLG-ON; cursor on ACTIDIN | L365-L371 | `Unable to lookup Account...` (L368) |

### UPDATE-ACCTDAT-FILE (L377-L403)

`EXEC CICS REWRITE` on `WS-ACCTDAT-FILE` FROM ACCOUNT-RECORD (the record previously read with UPDATE intent).

| DFHRESP | Action | Line | Message |
|---------|--------|------|---------|
| NORMAL | CONTINUE | L389 | -- |
| NOTFND | ERR-FLG-ON; cursor on ACTIDIN | L390-L395 | `Account ID NOT found...` (L392) |
| OTHER | DISPLAY RESP+REAS; ERR-FLG-ON | L396-L402 | `Unable to Update Account...` (L399) |

### READ-CXACAIX-FILE (L408-L436)

`EXEC CICS READ` on `WS-CXACAIX-FILE` (`'CXACAIX '`) INTO CARD-XREF-RECORD (key: XREF-ACCT-ID). No UPDATE intent.

| DFHRESP | Action | Line | Message |
|---------|--------|------|---------|
| NORMAL | CONTINUE | L422 | -- |
| NOTFND | ERR-FLG-ON; cursor on ACTIDIN | L423-L428 | `Account ID NOT found...` (L425) |
| OTHER | DISPLAY RESP+REAS; ERR-FLG-ON | L429-L435 | `Unable to lookup XREF AIX file...` (L432) |

CARD-XREF-RECORD is read to extract XREF-CARD-NUM (PIC X(16)) so the TRAN-RECORD can be populated with the correct TRAN-CARD-NUM at L225. The READ key is XREF-ACCT-ID, which was previously populated from ACTIDIN at L170-L171.

## Phase 6 -- TRANSACT File Operations (L441-L547)

### STARTBR-TRANSACT-FILE (L441-L467)

`EXEC CICS STARTBR` on WS-TRANSACT-FILE positioned at HIGH-VALUES (TRAN-ID). This positions the browse cursor at the high end of the file so the subsequent READPREV returns the maximum existing TRAN-ID.

| DFHRESP | Action | Line | Message |
|---------|--------|------|---------|
| NORMAL | CONTINUE | L453 | -- |
| NOTFND | ERR-FLG-ON; cursor on ACTIDIN | L454-L459 | `Transaction ID NOT found...` (L456) |
| OTHER | DISPLAY RESP+REAS; ERR-FLG-ON | L460-L466 | `Unable to lookup Transaction...` (L463) |

### READPREV-TRANSACT-FILE (L472-L496)

`EXEC CICS READPREV` to find the maximum existing TRAN-ID.

| DFHRESP | Action | Line | Message |
|---------|--------|------|---------|
| NORMAL | CONTINUE | L486 | -- |
| ENDFILE | MOVE ZEROS TO TRAN-ID (empty-file fallback) | L487-L488 | -- |
| OTHER | DISPLAY RESP+REAS; ERR-FLG-ON | L489-L495 | `Unable to lookup Transaction...` (L492) |

Empty-file fallback (L487-L488) is CRITICAL: when TRANSACT is initially empty, READPREV returns ENDFILE, TRAN-ID becomes zeros, then `ADD 1 TO WS-TRAN-ID-NUM` at L217 yields `0000000000000001` as the first record's TRAN-ID.

### ENDBR-TRANSACT-FILE (L501-L505)

`EXEC CICS ENDBR` -- no error handling; assumed success.

### WRITE-TRANSACT-FILE (L510-L547) -- CRITICAL DOUBLE-SPACE PRESERVATION

```cobol
EVALUATE WS-RESP-CD
    WHEN DFHRESP(NORMAL)
        PERFORM INITIALIZE-ALL-FIELDS
        MOVE SPACES   TO WS-MESSAGE
        MOVE DFHGREEN TO ERRMSGC OF COBIL0AO
        STRING 'Payment successful. '     DELIMITED BY SIZE
               ' Your Transaction ID is ' DELIMITED BY SIZE
               TRAN-ID                    DELIMITED BY SPACE
               '.'                        DELIMITED BY SIZE
          INTO WS-MESSAGE
        PERFORM SEND-BILLPAY-SCREEN
    WHEN DFHRESP(DUPKEY)
    WHEN DFHRESP(DUPREC)
        MOVE 'Y' TO WS-ERR-FLG
        MOVE 'Tran ID already exist...' TO WS-MESSAGE
    WHEN OTHER
        DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
        MOVE 'Y' TO WS-ERR-FLG
        MOVE 'Unable to Add Bill pay Transaction...' TO WS-MESSAGE
END-EVALUATE.
```

DOUBLE-SPACE INVARIANT: The STRING statement at L527-L531 concatenates:

1. `'Payment successful. '` (20 chars, with trailing space) DELIMITED BY SIZE
2. `' Your Transaction ID is '` (24 chars, with leading and trailing space) DELIMITED BY SIZE
3. TRAN-ID (16 chars) DELIMITED BY SPACE
4. `'.'` DELIMITED BY SIZE

Joined: `'Payment successful.  Your Transaction ID is 0000000000000016.'`

The TWO consecutive spaces between `.` and `Your` MUST be preserved BYTE-PERFECT per AAP Sec 0.7.1. Any whitespace normalization MUST be disabled in test assertions.

The success message is rendered in GREEN (DFHGREEN attribute on ERRMSGC at L526), whereas all error messages default to RED (BMS map ERRMSG default attribute).

## Phase 7 -- Screen I/O Paragraphs

### SEND-BILLPAY-SCREEN (L289-L301)

`EXEC CICS SEND MAP('COBIL0A') MAPSET('COBIL00') FROM(COBIL0AO) ERASE CURSOR`. The `ERASE` option clears the 3270 screen prior to writing the new map. The `CURSOR` option places the cursor wherever a length-field `-1` marker has been set (typically ACTIDIN or CONFIRM). Always preceded by `PERFORM POPULATE-HEADER-INFO` and `MOVE WS-MESSAGE TO ERRMSGO OF COBIL0AO`.

### RECEIVE-BILLPAY-SCREEN (L306-L314)

`EXEC CICS RECEIVE MAP('COBIL0A') MAPSET('COBIL00') INTO(COBIL0AI) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)`.

### POPULATE-HEADER-INFO (L319-L338)

Populates TRNNAME='CB00', PGMNAME='COBIL00C', TITLE01 (`'      AWS Mainframe Modernization       '` per COTTL01Y), TITLE02 (`'              CardDemo                  '` per COTTL01Y), CURDATE (formatted mm/dd/yy), CURTIME (formatted hh:mm:ss).

CRITICAL: Uses `FUNCTION CURRENT-DATE` at L321 -- this is non-deterministic. AAP Sec 0.6.6 requires the Java translation to inject a fixed `Clock` via `ScopedValue<Clock>` so test runs produce reproducible output.

### CLEAR-CURRENT-SCREEN (L552-L555) and INITIALIZE-ALL-FIELDS (L560-L566)

CLEAR-CURRENT-SCREEN performs INITIALIZE-ALL-FIELDS then SEND-BILLPAY-SCREEN. INITIALIZE-ALL-FIELDS resets the cursor (`MOVE -1 TO ACTIDINL`) and clears ACTIDINI / CURBALI / CONFIRMI / WS-MESSAGE to SPACES. The -1 marker on ACTIDINL signals `place cursor here` to BMS.

## Phase 8 -- GET-CURRENT-TIMESTAMP (L249-L267)

```cobol
EXEC CICS ASKTIME
  ABSTIME(WS-ABS-TIME)
END-EXEC

EXEC CICS FORMATTIME
  ABSTIME(WS-ABS-TIME)
  YYYYMMDD(WS-CUR-DATE-X10)
  DATESEP('-')
  TIME(WS-CUR-TIME-X08)
  TIMESEP(':')
END-EXEC

INITIALIZE WS-TIMESTAMP
MOVE WS-CUR-DATE-X10 TO WS-TIMESTAMP(01:10)
MOVE WS-CUR-TIME-X08 TO WS-TIMESTAMP(12:08)
MOVE ZEROS           TO WS-TIMESTAMP-TM-MS6
.
```

Output: WS-TIMESTAMP is a 26-byte string `YYYY-MM-DD HH:MM:SS.000000` (date `YYYY-MM-DD` at positions 1-10, space at position 11, time `HH:MM:SS` at positions 12-19, dot at position 20, microseconds `000000` at positions 21-26).

The WS-TIMESTAMP structure is defined in `app/cpy/CSDAT01Y.cpy:L42-L55`. INITIALIZE clears the structure to all zeros / dashes / colons per the FILLER VALUE clauses, then the two MOVE statements overlay the date and time substrings, and the final MOVE ZEROS sets the microseconds field to `'000000'` (six zeros).

### Java Translation (AAP Sec 0.6.4 + Sec 0.6.6)

```java
public record CurrentTimestamp(LocalDateTime value) {
    public static CurrentTimestamp now(Clock clock) {
        return new CurrentTimestamp(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
    }
    public String formatted() {
        return value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + ".000000";
    }
}
```

The `Clock` is bound via `ScopedValue<Clock>` per AAP Sec 0.6.6 -- never via `Clock.systemDefaultZone()` directly in production. Tests inject a fixed `Clock.fixed(Instant.parse("2024-01-15T10:30:45Z"), ZoneOffset.UTC)` so the captured `bms_output.txt` and `transact.txt` artifacts can be compared byte-for-byte across runs.

## Phase 9 -- RETURN-TO-PREV-SCREEN (L273-L284)

If CDEMO-TO-PROGRAM is blank, defaults to COSGN00C. Then sets CDEMO-FROM-TRANID = WS-TRANID, CDEMO-FROM-PROGRAM = WS-PGMNAME, CDEMO-PGM-CONTEXT = ZEROS, and `EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)`.

Default fallback program is COSGN00C (when CDEMO-TO-PROGRAM is blank, e.g. on EIBCALEN=0 path L108). For PF3 (BACK) at L128-L135, CDEMO-TO-PROGRAM is set to CDEMO-FROM-PROGRAM (if populated) or to COMEN01C as the secondary fallback. CDEMO-PGM-CONTEXT is reset to ZEROS so the destination program treats this XCTL as a first-pass entry (CDEMO-PGM-ENTER), not as a re-entry.

## Phase 10 -- BMS Map COBIL0A (`app/bms/COBIL00.bms`, 141 lines)

### Mapset Definition (L19-L25)

```text
COBIL00 DFHMSD CTRL=(ALARM,FREEKB),
               EXTATT=YES,
               LANG=COBOL,
               MODE=INOUT,
               STORAGE=AUTO,
               TIOAPFX=YES
COBIL0A DFHMDI COLUMN=1, LINE=1, SIZE=(24,80)
```

### Field Position Table (1920-byte 24-row x 80-col layout)

| Row | Col | Length | Field | Attributes | Color | Initial / Notes |
|-----|-----|--------|-------|------------|-------|------------------|
| 1 | 1 | 5 | (static) | ASKIP,NORM | BLUE | `'Tran:'` |
| 1 | 7 | 4 | TRNNAME | ASKIP,FSET,NORM | BLUE | `'CB00'` runtime-populated |
| 1 | 21 | 40 | TITLE01 | ASKIP,FSET,NORM | YELLOW | from CCDA-TITLE01 |
| 1 | 65 | 5 | (static) | ASKIP,NORM | BLUE | `'Date:'` |
| 1 | 71 | 8 | CURDATE | ASKIP,FSET,NORM | BLUE | `'mm/dd/yy'` formatted |
| 2 | 1 | 5 | (static) | ASKIP,NORM | BLUE | `'Prog:'` |
| 2 | 7 | 8 | PGMNAME | ASKIP,FSET,NORM | BLUE | `'COBIL00C'` runtime-populated |
| 2 | 21 | 40 | TITLE02 | ASKIP,FSET,NORM | YELLOW | from CCDA-TITLE02 |
| 2 | 65 | 5 | (static) | ASKIP,NORM | BLUE | `'Time:'` |
| 2 | 71 | 8 | CURTIME | ASKIP,FSET,NORM | BLUE | `'hh:mm:ss'` formatted |
| 4 | 35 | 12 | (static) | ASKIP,BRT | NEUTRAL | `'Bill Payment'` |
| 6 | 6 | 14 | (static) | ASKIP,NORM | GREEN | `'Enter Acct ID:'` |
| 6 | 21 | 11 | ACTIDIN | FSET,IC,NORM,UNPROT | GREEN UNDERLINE | initial cursor field |
| 8 | 6 | 70 | (static) | -- | YELLOW | dashes separator |
| 11 | 6 | 25 | (static) | ASKIP,NORM | TURQUOISE | `'Your current balance is: '` |
| 11 | 32 | 14 | CURBAL | ASKIP,FSET,NORM | BLUE | display-only |
| 15 | 6 | 53 | (static) | ASKIP,NORM | TURQUOISE | `'Do you want to pay your balance now. Please confirm: '` |
| 15 | 60 | 1 | CONFIRM | FSET,NORM,UNPROT | GREEN UNDERLINE | unprotected input |
| 15 | 63 | 5 | (static) | ASKIP,NORM | NEUTRAL | `'(Y/N)'` |
| 23 | 1 | 78 | ERRMSG | ASKIP,BRT,FSET | RED | message field; can be set to GREEN at runtime |
| 24 | 1 | 33 | (static) | ASKIP,NORM | YELLOW | `'ENTER=Continue  F3=Back  F4=Clear'` |

### Footer Note

NO PF12 in the footer instructions. NO PF5 either. ONLY three named keys: ENTER, F3, F4. The footer literal at L135 of `COBIL00.bms` is exactly `'ENTER=Continue  F3=Back  F4=Clear'` (33 chars, with two spaces between each pair).

### Symbolic Map Layout

The symbolic map copybook `app/cpy-bms/COBIL00.CPY` (140 lines) defines two 01-level groups over the same storage:

- COBIL0AI (input view): 12-byte FILLER prefix, then per-field length (L), flag (F), alias (A REDEFINES F), 4-byte filler, input (I) -- for TRNNAME, TITLE01, CURDATE, PGMNAME, TITLE02, CURTIME, ACTIDIN, CURBAL, CONFIRM, ERRMSG
- COBIL0AO REDEFINES COBIL0AI (output view): C (color), P (PS-set), H (highlight), V (validation), O (output value) subfields per field

The two unprotected input fields are ACTIDIN (length 11) and CONFIRM (length 1). CURBAL is read-only display.

## Phase 11 -- Test Scenarios

The harness drives 14 scenarios exercising every dispatch branch, every error message, the multi-file mutation happy path, and the auto-trigger flow.

| # | Scenario | EIBCALEN | EIBAID | Expected Outcome |
|---|----------|----------|--------|------------------|
| 1 | EIBCALEN_ZERO_XCTL_TO_COSGN00C | 0 | DFHENTER | XCTL COSGN00C |
| 2 | INITIAL_DISPLAY_FIRST_PASS | > 0 | DFHENTER | blank map; cursor ACTIDIN |
| 3 | PF3_BACK_XCTL_TO_FROM_PROGRAM | > 0 | DFHPF3 | XCTL CDEMO-FROM-PROGRAM (or COMEN01C) |
| 4 | PF4_CLEAR_CURRENT_SCREEN | > 0 | DFHPF4 | INITIALIZE-ALL-FIELDS; redisplay |
| 5 | INVALID_AID_PF12 | > 0 | DFHPF12 | `Invalid key pressed. Please see below...         ` (50 chars) |
| 6 | ACTIDIN_EMPTY | > 0 | DFHENTER | `Acct ID can NOT be empty...` |
| 7 | ACCTDAT_NOTFND | > 0 | DFHENTER | `Account ID NOT found...` (L361) |
| 8 | ACCT_CURR_BAL_ZERO | > 0 | DFHENTER | `You have nothing to pay...` |
| 9 | CONFIRM_BLANK_PROMPT | > 0 | DFHENTER | `Confirm to make a bill payment...` |
| 10 | CONFIRM_INVALID_X | > 0 | DFHENTER | `Invalid value. Valid values are (Y/N)...` |
| 11 | CONFIRM_NO_DECLINE | > 0 | DFHENTER | CLEAR-CURRENT-SCREEN; no payment |
| 12 | CONFIRM_YES_HAPPY_PATH | > 0 | DFHENTER | WRITE TRANSACT + REWRITE ACCTDAT; `Payment successful.  Your Transaction ID is <id>.` (DOUBLE-SPACE; GREEN) |
| 13 | CXACAIX_NOTFND_ON_YPATH | > 0 | DFHENTER | `Account ID NOT found...` (L425) |
| 14 | AUTO_TRIGGER_CDEMO_CB00_TRN_SELECTED | > 0 | DFHENTER | Auto-PERFORM PROCESS-ENTER-KEY |

Detailed scenario definitions (including pre-conditions, baseline VSAM state, input field overrides, and per-scenario expected message strings) are encoded in `input_scenario.txt` in this directory.

## Phase 12 -- Files in This Directory

| File | Status | Purpose |
|------|--------|---------|
| `README.md` | This document | Authoritative 13-phase contract |
| `input_scenario.txt` | TEST-OWNED | Synthesized 14-scenario CICS pseudo-conversation driver |
| `bms_output.txt` | CAPTURE PLACEHOLDER | Per-scenario 1920-byte BMS SEND MAP frame dumps |
| `stdout.txt` | CAPTURE PLACEHOLDER | Captured COBOL DISPLAY output (6 statements in error paths) |
| `transact.txt` | CAPTURE PLACEHOLDER | Captured post-WRITE TRANSACT state (+350 bytes on success) |
| `acctdata.txt` | CAPTURE PLACEHOLDER | Captured post-REWRITE ACCTDAT state (in-place; byte length unchanged) |

All capture-placeholder files are comment-only until COBOL captures are produced per `java/MIGRATION_NOTES.md` Sec 1.6. The test class MUST be `@Disabled` until then per AAP Sec 0.6.11.

The two cross-cutting input files (`acctdata.txt` baseline source, `cardxref.txt` baseline source) live in `app/data/ASCII/` and are referenced via classpath relative path per AAP Sec 0.4.1 -- they are NOT copied into this directory.

## Phase 13 -- Structural Invariants

| # | Invariant | Source |
|---|-----------|--------|
| 1 | Byte equality: 350-byte TRAN-RECORD + 300-byte ACCOUNT-RECORD | AAP Sec 0.1.3 |
| 2 | BMS determinism: fixed `Clock` via `ScopedValue<Clock>` for CURDATE/CURTIME/timestamps | AAP Sec 0.6.6 |
| 3 | PAN masking in logs as `'************<last4>'`; BMS screen exempt (screen != log) | AAP Sec 0.7.2 |
| 4 | Verbatim messages (14 distinct strings) preserved BYTE-PERFECT | AAP Sec 0.7.1 |
| 5 | DOUBLE-SPACE in success message preserved BYTE-PERFECT | COBIL00C.cbl L527-L531 |
| 6 | TRAILING 9 SPACES in CCDA-MSG-INVALID-KEY (50 chars total) preserved | CSMSG01Y.cpy L20-L21 |
| 7 | Multi-file atomic mutation: WRITE TRANSACT + REWRITE ACCTDAT in same transaction | COBIL00C.cbl L233-L235 |
| 8 | BigDecimal scale=2 preserved -- `1.20` NEVER normalized to `1.2` | AAP Sec 0.6.1 |
| 9 | Full-balance payment semantics: `ACCT-CURR-BAL_after = 0.00` (BigDecimal scale=2) | COBIL00C.cbl L224, L234 |
| 10 | Empty-file fallback: first TRAN-ID = `0000000000000001` when TRANSACT initially empty | COBIL00C.cbl L487-L488 |
| 11 | Sort orders preserved (ACCT-ID asc; TRAN-ID asc) | VSAM KSDS semantics |
| 12 | TRAN-ID auto-increment = max(existing TRAN-ID) + 1 | COBIL00C.cbl L212-L217 |
| 13 | Fixed TRAN-RECORD metadata signature (TRAN-TYPE-CD='02', etc.) | COBIL00C.cbl L220-L229 |

## Supplementary -- Verbatim Message Catalog

Total of 14 distinct verbatim message strings (plus the shared invalid-key constant). EVERY one MUST be preserved BYTE-PERFECT.

| # | Source Line | Verbatim Text | Color (BMS) |
|---|-------------|----------------|-------------|
| 1 | COBIL00C.cbl:L161 | `Acct ID can NOT be empty...` | RED (default) |
| 2 | COBIL00C.cbl:L187 | `Invalid value. Valid values are (Y/N)...` | RED |
| 3 | COBIL00C.cbl:L201 | `You have nothing to pay...` | RED |
| 4 | COBIL00C.cbl:L237 | `Confirm to make a bill payment...` | RED |
| 5 | COBIL00C.cbl:L361 | `Account ID NOT found...` (READ-ACCTDAT) | RED |
| 6 | COBIL00C.cbl:L368 | `Unable to lookup Account...` | RED |
| 7 | COBIL00C.cbl:L392 | `Account ID NOT found...` (UPDATE-ACCTDAT) | RED |
| 8 | COBIL00C.cbl:L399 | `Unable to Update Account...` | RED |
| 9 | COBIL00C.cbl:L425 | `Account ID NOT found...` (READ-CXACAIX) | RED |
| 10 | COBIL00C.cbl:L432 | `Unable to lookup XREF AIX file...` | RED |
| 11 | COBIL00C.cbl:L456 | `Transaction ID NOT found...` | RED |
| 12 | COBIL00C.cbl:L463 | `Unable to lookup Transaction...` (STARTBR) | RED |
| 13 | COBIL00C.cbl:L492 | `Unable to lookup Transaction...` (READPREV) | RED |
| 14 | COBIL00C.cbl:L536 | `Tran ID already exist...` | RED |
| 15 | COBIL00C.cbl:L543 | `Unable to Add Bill pay Transaction...` | RED |
| 16 | COBIL00C.cbl:L527-L531 | `Payment successful.  Your Transaction ID is <id>.` (DOUBLE-SPACE) | GREEN |
| 17 | CSMSG01Y.cpy:L20-L21 | `Invalid key pressed. Please see below...         ` (50 chars; 9 trailing spaces) | RED |

Note: messages 5, 7, 9 share identical text (`Account ID NOT found...`); messages 12, 13 share identical text (`Unable to lookup Transaction...`). They appear at 3 and 2 sites respectively. The success message (16) is the only one rendered in GREEN; all others are RED.

## Supplementary -- Capture Procedure Cross-Reference

Per AAP Sec 0.7.5, capture instructions for byte-perfect COBOL outputs are documented in `java/MIGRATION_NOTES.md` Sec 1.6. Until captures replace placeholder files in this directory, the test class is `@Disabled`.

Capture procedure summary (illustrative; authoritative version lives in MIGRATION_NOTES.md):

1. Compile `app/cbl/COBIL00C.cbl` and `app/bms/COBIL00.bms` on z/OS or Hercules
2. Define VSAM KSDS files from `app/data/ASCII/acctdata.txt`, `app/data/ASCII/cardxref.txt`, plus an empty TRANSACT KSDS
3. For each scenario in `input_scenario.txt`:
   - Restore baseline VSAM state from a snapshot
   - Send the BMS input as defined by the scenario
   - Capture the resulting BMS SEND MAP frame; append to `bms_output.txt`
   - Capture any DISPLAY output; append to `stdout.txt`
4. After all 14 scenarios complete, snapshot the mutated TRANSACT to `transact.txt`
5. Snapshot the mutated ACCTDAT to `acctdata.txt`
6. Verify all 14 scenarios produced expected message strings BYTE-PERFECT before committing

Until then, placeholder content suffices for harness scaffolding (test class loads, fixture-routing resolves) but NOT for byte-level parity.

## Supplementary -- Source Lineage

| Source File | Lines | Purpose |
|-------------|-------|---------|
| `app/cbl/COBIL00C.cbl` | 572 | COBOL PROGRAM-ID COBIL00C |
| `app/bms/COBIL00.bms` | 141 | BMS map definition COBIL0A |
| `app/cpy-bms/COBIL00.CPY` | 140 | Symbolic map copybook COBIL0AI/COBIL0AO |
| `app/cpy/CVACT01Y.cpy` | 20 | 300-byte ACCOUNT-RECORD |
| `app/cpy/CVACT03Y.cpy` | 11 | 50-byte CARD-XREF-RECORD |
| `app/cpy/CVTRA05Y.cpy` | 21 | 350-byte TRAN-RECORD |
| `app/cpy/COCOM01Y.cpy` | 47 | CARDDEMO-COMMAREA + CDEMO-CB00-INFO |
| `app/cpy/CSMSG01Y.cpy` | 24 | CCDA-MSG-INVALID-KEY |
| `app/cpy/COTTL01Y.cpy` | 27 | CCDA-TITLE01/CCDA-TITLE02 |
| `app/cpy/CSDAT01Y.cpy` | 58 | WS-CURDATE/WS-CURTIME/WS-TIMESTAMP |
| `app/data/ASCII/acctdata.txt` | -- | REFERENCE (read via classpath; NOT copied per AAP Sec 0.4.1) |
| `app/data/ASCII/cardxref.txt` | -- | REFERENCE (read via classpath; NOT copied per AAP Sec 0.4.1) |

## Supplementary -- Cross-References to Java Code

| Java Artifact | Fully Qualified Name |
|---------------|----------------------|
| Test class | `com.blitzy.carddemo.tests.golden.CoBil00CGoldenTest` |
| Class under test | `com.blitzy.carddemo.application.billpay.CoBil00C` |
| Input DTO | `com.blitzy.carddemo.application.billpay.CoBil00Input` |
| Output DTO | `com.blitzy.carddemo.application.billpay.CoBil00Output` |
| Account record | `com.blitzy.carddemo.domain.record.AccountRecord` |
| Card xref record | `com.blitzy.carddemo.domain.record.CardXrefRecord` |
| Transaction record | `com.blitzy.carddemo.domain.record.TranRecord` |
| Commarea | `com.blitzy.carddemo.domain.commarea.CardDemoCommarea` |
| Account port | `com.blitzy.carddemo.domain.port.AccountRepository` |
| Card xref port | `com.blitzy.carddemo.domain.port.CardXrefRepository` |
| Transaction port | `com.blitzy.carddemo.domain.port.TransactionRepository` |
| Account adapter | `com.blitzy.carddemo.adapter.file.FileAccountRepository` |
| Card xref adapter | `com.blitzy.carddemo.adapter.file.FileCardXrefRepository` |
| Transaction adapter | `com.blitzy.carddemo.adapter.file.FileTransactionRepository` |
| Decimals utility | `com.blitzy.carddemo.domain.util.Decimals` |

## Supplementary -- Authority References (AAP)

See the Phase 0 Authority Cascade table at the top of this document for the canonical AAP section mapping. The 14 sections cited there (Sec 0.1.1, 0.2.1, 0.3.1, 0.4.1, 0.6.1, 0.6.4, 0.6.5, 0.6.6, 0.6.11, 0.6.12, 0.7.1, 0.7.2, 0.7.4, 0.7.5) constitute the complete authority surface for this fixture contract. No additional AAP sections are normative for COBIL00C; any future cross-reference must be added to the Phase 0 table first.

## Supplementary -- Contrast Matrix

| Aspect | COBIL00C | COTRN02C | COCRDLIC | COCRDUPC |
|--------|----------|----------|----------|----------|
| Transaction ID | CB00 | CT02 | CCLI | CCUP |
| Source lines (cbl) | 572 | 783 | -- | 1560 |
| BMS unprotected fields | 2 (ACTIDIN, CONFIRM) | several | 1 (pagination) | many |
| Display-only fields | 1 (CURBAL) | several | many | many |
| AID keys (named) | 3 (ENTER, PF3, PF4) | several | several | several |
| Distinct messages | 14 + invalid-key = 15 | many | several | many |
| Auto-trigger field | YES (CDEMO-CB00-TRN-SELECTED) | YES (CDEMO-CT02-TRN-SELECTED) | NO | YES |
| File reads | ACCTDAT (UPDATE), CXACAIX, TRANSACT (BR) | ACCTDAT, CARD, etc. | CARDDAT (LIST) | ACCTDAT, CARDDAT |
| File mutations | WRITE TRANSACT + REWRITE ACCTDAT | WRITE TRANSACT | none | REWRITE CARDDAT |
| Multi-file atomic mutation | YES (unique to COBIL00C) | NO | NO | NO |
| CSUTLDTC date validation | NO | YES | NO | YES (DOB) |
| Money handling | YES (CRITICAL) | YES | NO | NO |
| BigDecimal scale=2 | CRITICAL | CRITICAL | NA | NA |
| Fixed TRAN metadata signature | YES (BILL PAYMENT) | partial | NA | NA |
| TRAN-AMT source | full ACCT-CURR-BAL | user-entered | NA | NA |
| ScopedValue<Clock> required | YES | YES | YES (CURDATE/CURTIME) | YES |

## Supplementary -- DO NOT Modify

The following constraints are BINDING. Any change to these would silently break the golden-record harness and the COBOL parity guarantee.

- DO NOT collapse the DOUBLE-SPACE between `.` and `Your` in the success message at COBIL00C.cbl L527-L531
- DO NOT trim trailing 9 spaces in CCDA-MSG-INVALID-KEY (50 chars total preserved); see CSMSG01Y.cpy L20-L21
- DO NOT remove trailing periods/ellipses from the 14 verbatim messages
- DO NOT change sort orders (ACCT-ID asc; TRAN-ID asc)
- DO NOT use `default` branches in Java `switch` over sealed `AidKey` or sealed response codes
- DO NOT use `double` or `float` for ACCT-CURR-BAL, TRAN-AMT, or CURBAL
- DO NOT use `java.util.Date`, `java.util.Calendar`, or `java.text.SimpleDateFormat` (AAP Sec 0.6.4)
- DO NOT use `java.io.File`, `FileInputStream`, or `FileOutputStream` (AAP Sec 0.6.5)
- DO NOT use `ThreadLocal` in production code (AAP Sec 0.6.6)
- DO NOT introduce Spring, Spring Boot, Hibernate, JPA, PostgreSQL, Docker, AWS, or Spring Cloud (AAP Sec 0.6.12)
- DO NOT enable `--enable-preview` (AAP Sec 0.7.4)
- DO NOT log a full card PAN; always mask as `'************<last4>'` in stdout/log surfaces (AAP Sec 0.7.2)
- DO NOT normalize BigDecimal scale; `0.00` MUST remain `0.00`, never `0` or `0.0` (AAP Sec 0.6.1)
- DO NOT reorder the EVALUATE branches; preserve COBOL precedence
- DO NOT copy `app/data/ASCII/*.txt` fixtures into this folder; read via classpath relative path
- DO NOT modify `app/` source; it is the immutable reference implementation (AAP Sec 0.1.1)
- DO NOT remove the `@Disabled` annotation on the test class until COBOL captures replace placeholder fixtures (AAP Sec 0.6.11)
