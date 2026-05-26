# COACTUPC Golden-Record Fixtures — expected/

This folder holds the **expected outputs** and the **synthesized scenario contract** for the COACTUPC (Account Update; CICS transaction `CAUP`) golden-record parity test. The test class FQCN is `com.blitzy.carddemo.tests.golden.CoActUpCGoldenTest`. The test class MUST carry `@Disabled("Pending COBOL baseline capture — see java/MIGRATION_NOTES.md §1.6")` until ALL five data files in this folder are committed with real captured COBOL bytes, per AAP §0.6.11.

COACTUPC is an online CICS pseudo-conversational program executing a **multi-pass UPDATE flow across TWO files**. It is the only program in the entire COBOL source tree that issues `EXEC CICS SYNCPOINT ROLLBACK`. The three observable passes are:

- **Pass 1** — ENTER with `ACCTSID` → `9000-READ-ACCT` → 3-step READ chain (CXACAIX → ACCTDAT → CUSTDAT) → `9500-STORE-FETCHED-DATA` captures the `OLD-*` snapshot → state transitions to `ACUP-SHOW-DETAILS`; `INFOMSG` = `'Details of selected account shown above'`.
- **Pass 2** — ENTER with edited account+customer fields → `1200-EDIT-MAP-INPUTS` runs ~25 validations across all editable fields → `1205-COMPARE-OLD-NEW` detects any field differences → if all pass and at least one field differs, state transitions to `ACUP-CHANGES-OK-NOT-CONFIRMED`; `INFOMSG` = `'Changes validated.Press F5 to save'`.
- **Pass 3** — PF5 → `9600-WRITE-PROCESSING` → CICS READ-UPDATE on ACCTDAT + CICS READ-UPDATE on CUSTDAT (acquires both locks) → `9700-CHECK-CHANGE-IN-REC` compares 15 account fields + 18 customer fields against the snapshot (optimistic concurrency) → if unchanged, EXEC CICS REWRITE on ACCTDAT (L4066) + EXEC CICS REWRITE on CUSTDAT (L4086) → state transitions to `ACUP-CHANGES-OKAYED-AND-DONE`; `INFOMSG` = `'Changes committed to database'`.

**CRITICAL UNIQUE BEHAVIOR.** COACTUPC is the **ONLY** COBOL program in the entire source tree (verified across all 28 programs in `app/cbl/`) that issues `EXEC CICS SYNCPOINT ROLLBACK`. If ACCTDAT REWRITE succeeds at `app/cbl/COACTUPC.cbl:L4066` but CUSTDAT REWRITE fails at `app/cbl/COACTUPC.cbl:L4086`, the program executes `EXEC CICS SYNCPOINT ROLLBACK` at `app/cbl/COACTUPC.cbl:L4100` to undo the in-flight ACCTDAT REWRITE. The Java translation MUST implement this via try/finally with compensating writes per AAP §0.4.1 and flag the choice as an IMPLEMENTATION DECISION in `java/MIGRATION_NOTES.md` §1.6.

The conventional `<program>/input/` and `<program>/expected/` split is followed. The sibling `../input/` folder contains only a documentation-marker README per the established convention; the input bytes the harness consumes are read directly from `app/data/ASCII/acctdata.txt`, `app/data/ASCII/custdata.txt`, and `app/data/ASCII/cardxref.txt` (loaded via classpath path; NOT copied) per AAP §0.4.1.

## COBOL Source identification

The COBOL source `app/cbl/COACTUPC.cbl` is 4,236 lines total. `IDENTIFICATION DIVISION` begins at L21; the PROGRAM-ID declaration is at L22-L23. The `WS-LITERALS` group at L532-L582 declares the program's self-reference identifiers plus the XCTL / dataset literal constants.

```cobol
PROGRAM-ID.
   COACTUPC.

01 WS-LITERALS.
   05 LIT-THISPGM                       PIC X(8)  VALUE 'COACTUPC'.
   05 LIT-THISTRANID                    PIC X(4)  VALUE 'CAUP'.
   05 LIT-THISMAPSET                    PIC X(8)  VALUE 'COACTUP '.
   05 LIT-THISMAP                       PIC X(7)  VALUE 'CACTUPA'.
   05 LIT-CARDUPDATE-PGM                PIC X(8)  VALUE 'COCRDUPC'.
   05 LIT-CARDUPDATE-TRANID             PIC X(4)  VALUE 'CCUP'.
   05 LIT-CARDUPDATE-MAPSET             PIC X(8)  VALUE 'COCRDUP '.
   05 LIT-CARDUPDATE-MAP                PIC X(7)  VALUE 'CCRDUPA'.
   05 LIT-CCLISTPGM                     PIC X(8)  VALUE 'COCRDLIC'.
   05 LIT-CCLISTTRANID                  PIC X(4)  VALUE 'CCLI'.
   05 LIT-CCLISTMAPSET                  PIC X(7)  VALUE 'COCRDLI'.
   05 LIT-CCLISTMAP                     PIC X(7)  VALUE 'CCRDSLA'.
   05 LIT-MENUPGM                       PIC X(8)  VALUE 'COMEN01C'.
   05 LIT-MENUTRANID                    PIC X(4)  VALUE 'CM00'.
   05 LIT-MENUMAPSET                    PIC X(7)  VALUE 'COMEN01'.
   05 LIT-MENUMAP                       PIC X(7)  VALUE 'COMEN1A'.
   05 LIT-CARDDTLPGM                    PIC X(8)  VALUE 'COCRDSLC'.
   05 LIT-CARDDTLTRANID                 PIC X(4)  VALUE 'CCDL'.
   05 LIT-CARDDTLMAPSET                 PIC X(7)  VALUE 'COCRDSL'.
   05 LIT-CARDDTLMAP                    PIC X(7)  VALUE 'CCRDSLA'.
   05 LIT-ACCTFILENAME                  PIC X(8)  VALUE 'ACCTDAT '.
   05 LIT-CUSTFILENAME                  PIC X(8)  VALUE 'CUSTDAT '.
   05 LIT-CARDFILENAME                  PIC X(8)  VALUE 'CARDDAT '.
   05 LIT-CARDFILENAME-ACCT-PATH        PIC X(8)  VALUE 'CARDAIX '.
   05 LIT-CARDXREFNAME-ACCT-PATH        PIC X(8)  VALUE 'CXACAIX '.
```

The PROGRAM-ID is `COACTUPC`. The CICS transaction ID is `CAUP`. The mapset is `'COACTUP '` (8 chars, trailing space significant). The map is `'CACTUPA'` (7 chars). The 13 declared `LIT-*` literals cover five logical groupings: this-program (COACTUPC / CAUP / COACTUP / CACTUPA); card-update target (COCRDUPC / CCUP / COCRDUP / CCRDUPA — destination if XCTL'd to card update); card-list target (COCRDLIC / CCLI / COCRDLI / CCRDSLA); menu (COMEN01C / CM00 / COMEN01 / COMEN1A); card-detail (COCRDSLC / CCDL / COCRDSL / CCRDSLA); and 5 file/dataset literals (ACCTDAT, CUSTDAT, CARDDAT, CARDAIX, CXACAIX). All 5 file/dataset literals are 8 chars with a trailing space.

The `ACUP-CHANGE-ACTION` 7-state machine governs the multi-pass flow (`app/cbl/COACTUPC.cbl:L652-L668`):

```cobol
01 WS-THIS-PROGCOMMAREA.
   05 ACCT-UPDATE-SCREEN-DATA.
      10 ACUP-CHANGE-ACTION              PIC X(1) VALUE LOW-VALUES.
         88 ACUP-DETAILS-NOT-FETCHED        VALUES LOW-VALUES, SPACES.
         88 ACUP-SHOW-DETAILS               VALUE 'S'.
         88 ACUP-CHANGES-MADE               VALUES 'E', 'N', 'C', 'L', 'F'.
         88 ACUP-CHANGES-NOT-OK             VALUE 'E'.
         88 ACUP-CHANGES-OK-NOT-CONFIRMED   VALUE 'N'.
         88 ACUP-CHANGES-OKAYED-AND-DONE    VALUE 'C'.
         88 ACUP-CHANGES-FAILED             VALUES 'L', 'F'.
         88 ACUP-CHANGES-OKAYED-LOCK-ERROR  VALUE 'L'.
         88 ACUP-CHANGES-OKAYED-BUT-FAILED  VALUE 'F'.
```

The Java translation models this as a sealed hierarchy of seven discriminator constants on `CoActUpC` per AAP §0.6.2 (sealed-type pattern). No `default` branch is permitted in any pattern-matching switch on `ACUP-CHANGE-ACTION` per AAP §0.7.4.

## Commarea contract (CARDDEMO-COMMAREA + WS-THIS-PROGCOMMAREA)

COACTUPC uses a dual-buffer commarea pattern. The LINKAGE SECTION declares an opaque `DFHCOMMAREA` whose actual length (`EIBCALEN`) carries two concatenated structures:

- `CARDDEMO-COMMAREA` (defined in `app/cpy/COCOM01Y.cpy`) — shared inter-program state. It contains `CDEMO-GENERAL-INFO` with `CDEMO-FROM-TRANID PIC X(4)`, `CDEMO-FROM-PROGRAM PIC X(8)`, `CDEMO-USER-TYPE PIC X(1)` (with 88-levels `CDEMO-USRTYP-ADMIN VALUE 'A'` and `CDEMO-USRTYP-USER VALUE 'U'`), and `CDEMO-PGM-CONTEXT PIC 9(1)` (with 88-levels `CDEMO-PGM-ENTER VALUE 0` and `CDEMO-PGM-REENTER VALUE 1`); plus `CDEMO-CUSTOMER-INFO`, `CDEMO-ACCOUNT-INFO`, `CDEMO-CARD-INFO`, `CDEMO-MORE-INFO`.
- `WS-THIS-PROGCOMMAREA` — program-private extension carrying `ACCT-UPDATE-SCREEN-DATA` (the `ACUP-CHANGE-ACTION` state machine, the `ACUP-OLD-DETAILS` snapshot with `ACUP-OLD-ACCT-DATA` and `ACUP-OLD-CUST-DATA` sub-records, and `ACUP-NEW-DETAILS` for edited values).

Reentry detection logic at `app/cbl/COACTUPC.cbl:L880-L893`: if `EIBCALEN = 0` OR (`CDEMO-FROM-PROGRAM = LIT-MENUPGM` AND NOT `CDEMO-PGM-REENTER`) → INITIALIZE both buffers and SET `CDEMO-PGM-ENTER` + `ACUP-DETAILS-NOT-FETCHED`. Otherwise split `DFHCOMMAREA` into the two slices.

Commarea length on RETURN equals `LENGTH OF CARDDEMO-COMMAREA + LENGTH OF WS-THIS-PROGCOMMAREA` (the exact byte length varies with the copybook definitions in COCOM01Y.cpy and the WS-THIS-PROGCOMMAREA group). `EXEC CICS RETURN` at `app/cbl/COACTUPC.cbl:L1015-L1019` carries the concatenated buffer with TRANSID `CAUP`.

The Java translation models the two buffers as immutable records: `CardDemoCommarea` (in `com.blitzy.carddemo.domain.commarea`) and a `CoActUpCommarea` extension record (private to `com.blitzy.carddemo.application.account`). The records are propagated explicitly through method parameters and return values; there is no shared mutable state. `ThreadLocal` is forbidden per AAP §0.6.6; the per-batch context (run id, processing date, fixed clock) flows through `ScopedValue<BatchRunContext>` instead.

## 0000-MAIN paragraph dispatch (entry / re-entry / first-time states)

The main paragraph at `app/cbl/COACTUPC.cbl:L859-L1023` dispatches on a 4-branch `EVALUATE TRUE`:

| # | Condition | Source Lines | Action |
|---|---|---|---|
| 1 | `CCARD-AID-PFK03` | L927-L959 | `EXEC CICS SYNCPOINT` (L952-L954, normal commit); `EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)` — default `CDEMO-TO-PROGRAM = COMEN01C` |
| 2 | (`ACUP-DETAILS-NOT-FETCHED` AND `CDEMO-PGM-ENTER`) OR (`CDEMO-FROM-PROGRAM = LIT-MENUPGM` AND NOT `CDEMO-PGM-REENTER`) | L964-L973 | Fresh entry: `INITIALIZE WS-THIS-PROGCOMMAREA`; `PERFORM 3000-SEND-MAP`; `SET CDEMO-PGM-REENTER, ACUP-DETAILS-NOT-FETCHED`; `GO TO COMMON-RETURN` |
| 3 | `ACUP-CHANGES-OKAYED-AND-DONE` OR `ACUP-CHANGES-FAILED` | L979-L989 | Post-save reset: `INITIALIZE WS-THIS-PROGCOMMAREA WS-MISC-STORAGE CDEMO-ACCT-ID`; `SET CDEMO-PGM-ENTER`; `PERFORM 3000-SEND-MAP`; `SET CDEMO-PGM-REENTER, ACUP-DETAILS-NOT-FETCHED`; `GO TO COMMON-RETURN` |
| 4 | `WHEN OTHER` | L996-L1003 | `PERFORM 1000-PROCESS-INPUTS` → `PERFORM 2000-DECIDE-ACTION` → `PERFORM 3000-SEND-MAP`; `GO TO COMMON-RETURN` |

Branch 1 (PFK03) is the EXIT path. It performs `EXEC CICS SYNCPOINT` first to commit any in-flight unit of work, then XCTLs to `CDEMO-TO-PROGRAM` (default `COMEN01C`). This SYNCPOINT at L953 is a normal commit; it is NOT the SYNCPOINT ROLLBACK at L4100.

Branch 2 (fresh entry) initializes the program-private buffer, displays an empty form, sets the next-entry state, and returns. This branch fires on first-time entry from the menu.

Branch 3 (post-save reset) fires when state is `ACUP-CHANGES-OKAYED-AND-DONE` (`'C'`) or `ACUP-CHANGES-FAILED` (`'L'` or `'F'`). It clears the program-private buffer plus `CDEMO-ACCT-ID`, displays a freshly-zeroed search-key screen, and returns. The user sees a clean form ready for the next account lookup.

Branch 4 (`WHEN OTHER`) is normal in-flight processing: `PERFORM 1000-PROCESS-INPUTS` → `PERFORM 2000-DECIDE-ACTION` → `PERFORM 3000-SEND-MAP`. This branch fires on every ENTER, PF5, or PF12 submission after the initial entry.

`COMMON-RETURN` at `app/cbl/COACTUPC.cbl:L1007-L1020`: MOVE `WS-RETURN-MSG` TO `CCARD-ERROR-MSG`; concatenate the two commareas into `WS-COMMAREA`; `EXEC CICS RETURN` with `TRANSID('CAUP')` and the concatenated `COMMAREA`.

The Java translation models this as a pattern-matching switch on the current `ACUP-CHANGE-ACTION` discriminator plus the AID-key sealed type. Each branch is implemented as a private method on `CoActUpC` returning an updated `CoActUpCommarea` plus a `CoActUpOutput` record. Exhaustiveness is compiler-enforced; no `default` branch.

## 1000-PROCESS-INPUTS — cursor + AID handling (4 valid AIDs: ENTER, PF3, PF5, PF12)

`1000-PROCESS-INPUTS` at `app/cbl/COACTUPC.cbl:L1025-L1037` performs only one nested paragraph: `1100-RECEIVE-MAP`. `1100-RECEIVE-MAP` at `app/cbl/COACTUPC.cbl:L1039-L1426` (~387 lines) handles: `EXEC CICS RECEIVE MAP(LIT-THISMAP) MAPSET(LIT-THISMAPSET)`; star-wildcard normalization across all ~38 editable fields (`MOVE LOW-VALUES` if `'*'` or SPACES, else `MOVE` through); then `PERFORM` each validation paragraph in turn.

The AID-key validation at `app/cbl/COACTUPC.cbl:L905-L916`:

```cobol
SET PFK-INVALID TO TRUE
IF CCARD-AID-ENTER OR
   CCARD-AID-PFK03 OR
   (CCARD-AID-PFK05 AND ACUP-CHANGES-OK-NOT-CONFIRMED)
                   OR
   (CCARD-AID-PFK12 AND NOT ACUP-DETAILS-NOT-FETCHED)
   SET PFK-VALID TO TRUE
END-IF

IF PFK-INVALID
   SET CCARD-AID-ENTER TO TRUE
END-IF
```

Permitted AIDs are exactly four:

- **ENTER** (always valid) — submits the form for processing.
- **PF03** (always valid) — exits to caller via XCTL.
- **PF05** (valid only when `ACUP-CHANGES-OK-NOT-CONFIRMED`) — saves changes; triggers `9600-WRITE-PROCESSING`.
- **PF12** (valid only when NOT `ACUP-DETAILS-NOT-FETCHED`) — cancels edits and reloads original data via a fresh `9000-READ-ACCT`.

All other AID keys (CLEAR, PA1, PA2, PF1, PF2, PF4, PF6 through PF11) are silently coerced to ENTER. The coercion is **observable**: the same `WS-RETURN-MSG` and `INFOMSG` that ENTER would produce in the current state appear on the screen. The Java translation models AID keys as a sealed type `AidKey permits Enter, Clear, Pa1, Pa2, PfKey` (from `app/cpy/CVCRD01Y.cpy`); the coercion at L914-L916 maps to a private `coerceToEnterIfInvalid(...)` method on `CoActUpC` that returns the canonical ENTER discriminator when the AID is not in the permitted set.

The `2000-DECIDE-ACTION` inner `EVALUATE TRUE` at `app/cbl/COACTUPC.cbl:L2562-L2641` selects the next state transition. Note the duplicate `WHEN ACUP-CHANGES-OK-NOT-CONFIRMED` at L2620 (a second occurrence preserved verbatim per AAP §0.7.1):

```cobol
2000-DECIDE-ACTION.
    EVALUATE TRUE
        WHEN ACUP-DETAILS-NOT-FETCHED
        WHEN CCARD-AID-PFK12
            IF  FLG-ACCTFILTER-ISVALID
                SET WS-RETURN-MSG-OFF       TO TRUE
                PERFORM 9000-READ-ACCT
                   THRU 9000-READ-ACCT-EXIT
                IF FOUND-CUST-IN-MASTER
                   SET ACUP-SHOW-DETAILS    TO TRUE
                END-IF
            END-IF
        WHEN ACUP-SHOW-DETAILS
            IF INPUT-ERROR
            OR NO-CHANGES-DETECTED
               CONTINUE
            ELSE
               SET ACUP-CHANGES-OK-NOT-CONFIRMED TO TRUE
            END-IF
        WHEN ACUP-CHANGES-NOT-OK
            CONTINUE
        WHEN ACUP-CHANGES-OK-NOT-CONFIRMED
         AND CCARD-AID-PFK05
            PERFORM 9600-WRITE-PROCESSING
               THRU 9600-WRITE-PROCESSING-EXIT
            EVALUATE TRUE
                WHEN COULD-NOT-LOCK-ACCT-FOR-UPDATE
                     SET ACUP-CHANGES-OKAYED-LOCK-ERROR TO TRUE
                WHEN LOCKED-BUT-UPDATE-FAILED
                     SET ACUP-CHANGES-OKAYED-BUT-FAILED TO TRUE
                WHEN DATA-WAS-CHANGED-BEFORE-UPDATE
                     SET ACUP-SHOW-DETAILS            TO TRUE
                WHEN OTHER
                     SET ACUP-CHANGES-OKAYED-AND-DONE TO TRUE
            END-EVALUATE
        WHEN ACUP-CHANGES-OK-NOT-CONFIRMED
            CONTINUE
        WHEN ACUP-CHANGES-OKAYED-AND-DONE
            SET ACUP-SHOW-DETAILS TO TRUE
            IF CDEMO-FROM-TRANID    EQUAL LOW-VALUES
            OR CDEMO-FROM-TRANID    EQUAL SPACES
               MOVE ZEROES       TO CDEMO-ACCT-ID
                                    CDEMO-CARD-NUM
               MOVE LOW-VALUES   TO CDEMO-ACCT-STATUS
            END-IF
        WHEN OTHER
            MOVE LIT-THISPGM    TO ABEND-CULPRIT
            MOVE '0001'         TO ABEND-CODE
            MOVE SPACES         TO ABEND-REASON
            MOVE 'UNEXPECTED DATA SCENARIO'
                                TO ABEND-MSG
            PERFORM ABEND-ROUTINE
               THRU ABEND-ROUTINE-EXIT
    END-EVALUATE.
```

The Java translation models this as a nested pattern-matching switch on `ACUP-CHANGE-ACTION` combined with the AID-key sealed type. The inner nested `EVALUATE TRUE` (under `ACUP-CHANGES-OK-NOT-CONFIRMED AND CCARD-AID-PFK05`) maps to a nested switch that observes the post-`9600-WRITE-PROCESSING` flags (`COULD-NOT-LOCK-ACCT-FOR-UPDATE`, `LOCKED-BUT-UPDATE-FAILED`, `DATA-WAS-CHANGED-BEFORE-UPDATE`, or success). The `WHEN OTHER` at the outer level is the only place where the program raises a `CardDemoAbendException` with abcode `'0001'` and message `'UNEXPECTED DATA SCENARIO'` per AAP §0.7.1.

## 9000-READ-ACCT — CXACAIX → ACCTDAT → CUSTDAT cross-reference chain (READ-only snapshot)

The 3-step READ chain at `app/cbl/COACTUPC.cbl:L3608-L3797`:

```cobol
9000-READ-ACCT.                          *> L3608-L3647
    PERFORM 9200-GETCARDXREF-BYACCT
       THRU 9200-GETCARDXREF-BYACCT-EXIT
    IF FOUND-ACCT-IN-XREF
       PERFORM 9300-GETACCTDATA-BYACCT
          THRU 9300-GETACCTDATA-BYACCT-EXIT
    END-IF
    IF FOUND-ACCT-IN-MASTER
       PERFORM 9400-GETCUSTDATA-BYCUST
          THRU 9400-GETCUSTDATA-BYCUST-EXIT
    END-IF
    IF FOUND-CUST-IN-MASTER
       PERFORM 9500-STORE-FETCHED-DATA
          THRU 9500-STORE-FETCHED-DATA-EXIT
    END-IF.

9200-GETCARDXREF-BYACCT.                 *> L3650-L3698
    MOVE CC-ACCT-ID              TO WS-CARD-RID-ACCT-ID
    EXEC CICS READ
         DATASET   (LIT-CARDXREFNAME-ACCT-PATH)
         RIDFLD    (WS-CARD-RID-ACCT-ID-X)
         INTO      (CARD-XREF-RECORD)
         RESP      (WS-RESP-CD)
         RESP2     (WS-REAS-CD)
    END-EXEC.
    *> NORMAL: SET FOUND-ACCT-IN-XREF; MOVE XREF-CUST-ID TO CDEMO-CUST-ID; MOVE XREF-CARD-NUM TO CDEMO-CARD-NUM

9300-GETACCTDATA-BYACCT.                 *> L3701-L3748
    MOVE CC-ACCT-ID              TO WS-CARD-RID-ACCT-ID
    EXEC CICS READ
         DATASET   (LIT-ACCTFILENAME)
         RIDFLD    (WS-CARD-RID-ACCT-ID-X)
         INTO      (ACCOUNT-RECORD)
         RESP      (WS-RESP-CD)
         RESP2     (WS-REAS-CD)
    END-EXEC.
    *> NORMAL: SET FOUND-ACCT-IN-MASTER

9400-GETCUSTDATA-BYCUST.                 *> L3752-L3797
    MOVE CDEMO-CUST-ID           TO WS-CARD-RID-CUST-ID
    EXEC CICS READ
         DATASET   (LIT-CUSTFILENAME)
         RIDFLD    (WS-CARD-RID-CUST-ID-X)
         INTO      (CUSTOMER-RECORD)
         RESP      (WS-RESP-CD)
         RESP2     (WS-REAS-CD)
    END-EXEC.
    *> NORMAL: SET FOUND-CUST-IN-MASTER
```

The 3-step READ in `9000-READ-ACCT` is **READ-only** (no UPDATE clause). This snapshot of (XREF, ACCOUNT, CUSTOMER) becomes the `OLD-*` snapshot stored in `9500-STORE-FETCHED-DATA` (`app/cbl/COACTUPC.cbl:L3801-L3885`; ~85 fields copied) for later optimistic-concurrency comparison.

Error paths:

- If 9200 fails NOTFND → set `DID-NOT-FIND-ACCT-IN-CARDXREF`; build a STRING template error message embedding the failing `ACCT-ID` plus the `RESP` and `RESP2` codes; `GO TO 9000-READ-ACCT-EXIT` (subsequent steps skipped).
- If 9300 fails NOTFND → set `DID-NOT-FIND-ACCT-IN-ACCTDAT`; STRING template error built; skip 9400.
- If 9400 fails NOTFND → set `DID-NOT-FIND-CUST-IN-CUSTDAT`; STRING template error built.
- If all 3 succeed → `9500-STORE-FETCHED-DATA` copies fields into `ACUP-OLD-DETAILS`.

The Java translation uses three port interfaces (`CardXrefRepository.findByAcctId`, `AccountRepository.findById`, `CustomerRepository.findById`) returning `Optional<...>`. The `9000-READ-ACCT` orchestrator chains the three calls and populates the `ACUP-OLD-*` snapshot record. The file adapter implementations (in `carddemo-adapter-file`) use `java.nio.file` per AAP §0.6.5; `java.io.File` is forbidden.

## Screen build paragraphs

`3000-SEND-MAP` at `app/cbl/COACTUPC.cbl:L2649-L2663` is the orchestrator that performs six sub-paragraphs in order: `3100-SCREEN-INIT` (L2668-L2694; init map area to spaces/LOW-VALUES) → `3200-SETUP-SCREEN-VARS` (L2698-L2727; header fields TRNNAME=CAUP, PGMNAME=COACTUPC, TITLE01/02, CURDATE, CURTIME via CSDAT01Y) → `3250-SETUP-INFOMSG` (L2955-L2983; `MOVE WS-INFO-MSG TO INFOMSGO`; `MOVE WS-RETURN-MSG TO ERRMSGO`) → `3300-SETUP-SCREEN-ATTRS` (L2986-L3437; ~450-line attribute setup — colors, protection, FSET, HILIGHT, cursor; per-field error highlighting RED+BRT for `*-NOT-OK`/`*-BLANK`) → `3390-SETUP-INFOMSG-ATTRS` (L3566-L3584; INFOMSGA = DRK when `WS-NO-INFO-MESSAGE`, ASB otherwise) → `3400-SEND-SCREEN` (L3589-L3603; `EXEC CICS SEND MAP(CCARD-NEXT-MAP) MAPSET(CCARD-NEXT-MAPSET) CURSOR`).

`3200-SETUP-SCREEN-VARS` dispatches to one of three state-dependent sub-paragraphs: `3201-SHOW-INITIAL-VALUES` (L2731-L2783; empty form for `ACUP-DETAILS-NOT-FETCHED`); `3202-SHOW-ORIGINAL-VALUES` (L2787-L2867; populated from `ACUP-OLD-DETAILS` after successful `9000-READ-ACCT`); `3203-SHOW-UPDATED-VALUES` (L2870-L2951; populated with `ACUP-NEW-*` values during edit/confirm/save passes). Two attribute helpers (`3310-PROTECT-ALL-ATTRS` L3441-L3496 and `3320-UNPROTECT-FEW-ATTRS` L3500-L3562) handle state-based protection toggling. The dynamic `CCARD-NEXT-MAPSET`/`CCARD-NEXT-MAP` defaults to `COACTUP`/`CACTUPA` but can be remapped (e.g., to `CCRDUPA` when XCTL'd from `COCRDLIC` for chained card update — see `CDEMO-LAST-MAPSET` handling at L3171).

## COMMON-RETURN / SYNCPOINT / EXEC CICS RETURN / ABEND-ROUTINE

`COMMON-RETURN` at `app/cbl/COACTUPC.cbl:L1007-L1020` is the normal exit. It builds `WS-COMMAREA` from concatenation of `CARDDEMO-COMMAREA` and `WS-THIS-PROGCOMMAREA`, then issues `EXEC CICS RETURN` with `TRANSID('CAUP')` and the concatenated `COMMAREA`. The Java translation returns a `CoActUpOutput` record (a JEP 513 flexible-constructor-validated record) containing both the rendered screen state and the updated commarea, which the harness asserts against the captured `bms_output.txt`.

`3400-SEND-SCREEN` is the BMS send wrapper. After `SEND MAP`, control passes to CICS for terminal write. The Java translation renders the screen state into a 1920-byte buffer (24 rows × 80 cols) that the harness serializes into `bms_output.txt` for byte-for-byte parity.

`ABEND-ROUTINE` at `app/cbl/COACTUPC.cbl:L4203-L4226` writes `'UNEXPECTED ABEND OCCURRED.'` to `ABEND-MSG`; performs `ABEND-ROUTINE-EXIT`; then issues `EXEC CICS ABEND ABCODE('9999')`. The Java translation maps this to a `CardDemoAbendException` with `abcode='9999'` and the verbatim message. Per AAP §0.7.1, the abend message string is preserved character-for-character including the trailing period.

## BMS Map Layout — CACTUPA (24×80, FREEKB, 42 named DFHMDF fields)

The BMS map `app/bms/COACTUP.bms` is 513 lines defining MAPSET `COACTUP` and MAP `CACTUPA`. Layout attributes: `CTRL=(FREEKB)`, `DSATTS=(COLOR,HILIGHT,PS,VALIDN)`, `MAPATTS=(COLOR,HILIGHT,PS,VALIDN)`. There are 42 named DFHMDF fields plus literal labels (128 DFHMDF entries total counting literals).

Header area (rows 1-2). All header literals are BLUE ASKIP,NORM at length 5; all named header fields are ASKIP,NORM.

| Row,Col | Field | Length | Color | Notes |
|---|---|---|---|---|
| 1,1 / 1,65 / 2,1 / 2,65 | literals `'Tran:'`, `'Date:'`, `'Prog:'`, `'Time:'` | 5 | BLUE | L29-L33, L42-L46, L52-L56, L65-L69 |
| 1,7 | `TRNNAME` | 4 | BLUE | FSET; filled with `'CAUP'`; L34-L37 |
| 1,21 | `TITLE01` | 40 | YELLOW | filled from `CCDA-TITLE01`; L38-L41 |
| 1,71 | `CURDATE` | 8 | BLUE | INITIAL `'mm/dd/yy'`; L47-L51 |
| 2,7 | `PGMNAME` | 8 | BLUE | filled with `'COACTUPC'`; L57-L60 |
| 2,21 | `TITLE02` | 40 | YELLOW | filled from `CCDA-TITLE02`; L61-L64 |
| 2,71 | `CURTIME` | 8 | BLUE | INITIAL `'hh:mm:ss'`; L70-L74 |

Screen title (row 4): `'Update Account'` (length 14, NEUTRAL color, at row 4 col 33; L75-L78).

Account block (rows 5-11). All fields are UNPROT,UNDERLINE on DEFAULT color; date subfields (`*MON`, `*DAY`) share their parent year field's row.

| Row,Col | Field | Length | Attribute | Notes |
|---|---|---|---|---|
| 5,38 | `ACCTSID` | 11 | IC,UNPROT,UNDERLINE | search key; IC = initial cursor; L84-L87 |
| 5,70 | `ACSTTUS` | 1 | UNPROT,UNDERLINE | Account Active Y/N; L94-L97 |
| 6,46 | `OPNYEAR` (+`OPNMON`@6,51 +`OPNDAY`@6,54) | 4+2+2 | FSET,UNPROT,UNDERLINE | Open date (YYYY-MM-DD); L104-L107 |
| 7,49 | `ACRDLIM` | 15 | FSET,UNPROT,UNDERLINE | Credit Limit signed S9(10)V99; L132-L141 |
| 7,72 | `EXPYEAR` (+`EXPMON`@7,77 +`EXPDAY`@7,80) | 4+2+2 | UNPROT,UNDERLINE | Expiry date; L142-L146 |
| 8,49 | `ACSHLIM` | 15 | FSET,UNPROT,UNDERLINE | Cash Credit Limit; L170-L179 |
| 8,72 | `RISYEAR` (+`RISMON`@8,77 +`RISDAY`@8,80) | 4+2+2 | UNPROT,UNDERLINE | Reissue date; L180-L184 |
| 9,49 | `ACURBAL` | 15 | FSET,UNPROT,UNDERLINE | Current Balance; L208-L218 |
| 10,49 | `ACRCYCR` | 15 | FSET,UNPROT,UNDERLINE | Current Cycle Credit; L219-L228 |
| 10,72 | `AADDGRP` | 10 | UNPROT,UNDERLINE | Account Group; L229-L239 |
| 11,49 | `ACRCYDB` | 15 | FSET,UNPROT,UNDERLINE | Current Cycle Debit; L240-L253 |

Customer block (rows 12-21). All fields are UNPROT,UNDERLINE on DEFAULT color; composite multi-part fields are grouped on a single row.

| Row,Col | Field | Length | Notes |
|---|---|---|---|
| 12,38 | `ACSTNUM` | 9 | Customer Number; L254-L263 |
| 13,38..49 | `ACTSSN1`+`ACTSSN2`+`ACTSSN3` | 3+2+4 | SSN parts at cols 38/44/49; L264-L290 |
| 13,65..73 | `DOBYEAR`+`DOBMON`+`DOBDAY` | 4+2+2 | DOB (YYYY-MM-DD) at cols 65/70/73; L291-L317 |
| 14,65 | `ACSTFCO` | 3 | FICO Score (300-850); L318-L335 |
| 15,38 | `ACSFNAM` / `ACSMNAM` / `ACSLNAM` | 25 each | First / Middle / Last Name; L336-L355 |
| 16,38 | `ACSADL1` | 50 | Address Line 1; L356-L365 |
| 16,38 | `ACSSTTE` | 2 | State Code; L366-L371 |
| 17,38 | `ACSADL2` | 50 | Address Line 2 (City stored here); L372-L381 |
| 17,38 | `ACSZIPC` | 10 | ZIP Code; L382-L391 |
| 18,38 | `ACSCITY` | 25 | City; L392-L401 |
| 18,38 | `ACSCTRY` | 3 | Country Code; L402-L411 |
| 19,38..46 | `ACSPH1A`+`ACSPH1B`+`ACSPH1C` | 3+3+4 | Phone 1 (Area/Prefix/Line) at cols 38/42/46; L412-L432 |
| 19,55 | `ACSGOVT` | 20 | Govt-Issued ID; L433-L442 |
| 20,38..46 | `ACSPH2A`+`ACSPH2B`+`ACSPH2C` | 3+3+4 | Phone 2 at cols 38/42/46; L443-L463 |
| 21,38 | `ACSEFTC` | 10 | EFT Account Id; L464-L473 |
| 21,55 | `ACSPFLG` | 1 | Primary Card Holder Y/N; L474-L479 |

Status/key area (rows 22-24):

| Row,Col | Field | Length | Color | Attribute | Notes |
|---|---|---|---|---|---|
| 22,23 | `INFOMSG` | 45 | NEUTRAL | ASKIP,HILIGHT=OFF | dynamic info message; L480-L484 |
| 23,1 | `ERRMSG` | 78 | RED | ASKIP,BRT,FSET | dynamic error message; L489-L492 |
| 24,1 | `FKEYS` | 21 | YELLOW | ASKIP,NORM | INITIAL `'ENTER=Process F3=Exit'`; L493-L497 |
| 24,23 | `FKEY05` | 7 | YELLOW | ASKIP,DRK | INITIAL `'F5=Save'`; brightened to BRT when CHANGES-OK-NOT-CONFIRMED; L498-L502 |
| 24,31 | `FKEY12` | 10 | YELLOW | ASKIP,DRK | INITIAL `'F12=Cancel'`; brightened to BRT when NOT DETAILS-NOT-FETCHED; L503-L507 |

Notes on the BMS map:

- IC (Initial Cursor) is on `ACCTSID` at row 5 col 38 (L84). ~38 editable input fields total (search key `ACCTSID` plus ~37 update fields).
- `FKEY05` and `FKEY12` brightness is driven by state: DRK by default; BRT when applicable (`F5=Save` BRT only in `ACUP-CHANGES-OK-NOT-CONFIRMED`; `F12=Cancel` BRT only when NOT `ACUP-DETAILS-NOT-FETCHED`).
- `TRNNAME` always carries `'CAUP'` (set at `app/cbl/COACTUPC.cbl:L2675`); `PGMNAME` always carries `'COACTUPC'` (L2676).
- `CURDATE` `'mm/dd/yy'` and `CURTIME` `'hh:mm:ss'` are populated from `FUNCTION CURRENT-DATE` via CSDAT01Y. The Java side uses `ScopedValue<Clock>` (per AAP §0.6.6) to inject a fixed clock; the fixed clock value is documented in `java/MIGRATION_NOTES.md` §1.6.

## 9600-WRITE-PROCESSING + 9700-CHECK-CHANGE-IN-REC — Multi-file UPDATE with optimistic concurrency

**Naming note.** The actual paragraph names in `app/cbl/COACTUPC.cbl` are `9600-WRITE-PROCESSING` (L3888) and `9700-CHECK-CHANGE-IN-REC` (L4109). Per AAP §0.7.1 (Minimal Change Clause; preserve verbatim names), this README uses those actual names. COACTUPC reserves 9200/9300/9400 for the 3-step READ chain documented above; 9600/9700 are the UPDATE counterparts.

`9600-WRITE-PROCESSING` body at `app/cbl/COACTUPC.cbl:L3888-L4105`:

```cobol
9600-WRITE-PROCESSING.
    *  Read the account file for update
    MOVE CC-ACCT-ID              TO WS-CARD-RID-ACCT-ID
    EXEC CICS READ
         FILE      (LIT-ACCTFILENAME)
         UPDATE
         RIDFLD    (WS-CARD-RID-ACCT-ID-X)
         KEYLENGTH (LENGTH OF WS-CARD-RID-ACCT-ID-X)
         INTO      (ACCOUNT-RECORD)
         LENGTH    (LENGTH OF ACCOUNT-RECORD)
         RESP      (WS-RESP-CD)
         RESP2     (WS-REAS-CD)
    END-EXEC

    IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)
       CONTINUE
    ELSE
       SET INPUT-ERROR                    TO TRUE
       IF  WS-RETURN-MSG-OFF
           SET COULD-NOT-LOCK-ACCT-FOR-UPDATE  TO TRUE
       END-IF
       GO TO 9600-WRITE-PROCESSING-EXIT
    END-IF

    *  Read the customer file for update
    MOVE CDEMO-CUST-ID                   TO WS-CARD-RID-CUST-ID
    EXEC CICS READ
         FILE      (LIT-CUSTFILENAME)
         UPDATE
         RIDFLD    (WS-CARD-RID-CUST-ID-X)
         KEYLENGTH (LENGTH OF WS-CARD-RID-CUST-ID-X)
         INTO      (CUSTOMER-RECORD)
         LENGTH    (LENGTH OF CUSTOMER-RECORD)
         RESP      (WS-RESP-CD)
         RESP2     (WS-REAS-CD)
    END-EXEC

    IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)
       CONTINUE
    ELSE
       SET INPUT-ERROR                    TO TRUE
       IF  WS-RETURN-MSG-OFF
           SET COULD-NOT-LOCK-CUST-FOR-UPDATE  TO TRUE
       END-IF
       GO TO 9600-WRITE-PROCESSING-EXIT
    END-IF

    *  Did someone change the record while we were out ?
    PERFORM 9700-CHECK-CHANGE-IN-REC
       THRU 9700-CHECK-CHANGE-IN-REC-EXIT

    IF DATA-WAS-CHANGED-BEFORE-UPDATE
       GO TO 9600-WRITE-PROCESSING-EXIT
    END-IF

    *  Prepare ACCT-UPDATE-RECORD and CUST-UPDATE-RECORD
    INITIALIZE ACCT-UPDATE-RECORD
    MOVE ACUP-NEW-ACCT-ID         TO ACCT-UPDATE-ID
    *  ... (10 field MOVEs and 3 STRING constructions for OPEN-DATE,
    *   EXPIRAION-DATE, REISSUE-DATE)

    *  Update account
    EXEC CICS
         REWRITE FILE(LIT-ACCTFILENAME)        *> L4066
                 FROM(ACCT-UPDATE-RECORD)
                 LENGTH(LENGTH OF ACCT-UPDATE-RECORD)
                 RESP      (WS-RESP-CD)
                 RESP2     (WS-REAS-CD)
    END-EXEC.

    IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)
      CONTINUE
    ELSE
      SET LOCKED-BUT-UPDATE-FAILED    TO TRUE
      GO TO 9600-WRITE-PROCESSING-EXIT
    END-IF

    *  Update customer
    EXEC CICS
              REWRITE FILE(LIT-CUSTFILENAME)   *> L4086
              FROM(CUST-UPDATE-RECORD)
              LENGTH(LENGTH OF CUST-UPDATE-RECORD)
              RESP      (WS-RESP-CD)
              RESP2     (WS-REAS-CD)
    END-EXEC.

    IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)
      CONTINUE
    ELSE
      SET LOCKED-BUT-UPDATE-FAILED    TO TRUE    *> L4099
      EXEC CICS
         SYNCPOINT ROLLBACK                       *> L4100  <-- SOLE ROLLBACK
      END-EXEC
      GO TO 9600-WRITE-PROCESSING-EXIT
    END-IF
    .
9600-WRITE-PROCESSING-EXIT.
    EXIT
    .
```

`9700-CHECK-CHANGE-IN-REC` body at `app/cbl/COACTUPC.cbl:L4109-L4192`:

```cobol
9700-CHECK-CHANGE-IN-REC.
    *  Account Master data
    IF  ACCT-ACTIVE-STATUS      EQUAL ACUP-OLD-ACTIVE-STATUS
    AND ACCT-CURR-BAL           EQUAL ACUP-OLD-CURR-BAL-N
    AND ACCT-CREDIT-LIMIT       EQUAL ACUP-OLD-CREDIT-LIMIT-N
    AND ACCT-CASH-CREDIT-LIMIT EQUAL ACUP-OLD-CASH-CREDIT-LIMIT-N
    AND ACCT-CURR-CYC-CREDIT    EQUAL ACUP-OLD-CURR-CYC-CREDIT-N
    AND ACCT-CURR-CYC-DEBIT     EQUAL ACUP-OLD-CURR-CYC-DEBIT-N
    AND ACCT-OPEN-DATE(1:4)     EQUAL ACUP-OLD-OPEN-YEAR
    AND ACCT-OPEN-DATE(6:2)     EQUAL ACUP-OLD-OPEN-MON
    AND ACCT-OPEN-DATE(9:2)     EQUAL ACUP-OLD-OPEN-DAY
    AND ACCT-EXPIRAION-DATE(1:4)EQUAL ACUP-OLD-EXP-YEAR
    AND ACCT-EXPIRAION-DATE(6:2)EQUAL ACUP-OLD-EXP-MON
    AND ACCT-EXPIRAION-DATE(9:2)EQUAL ACUP-OLD-EXP-DAY
    AND ACCT-REISSUE-DATE(1:4)  EQUAL ACUP-OLD-REISSUE-YEAR
    AND ACCT-REISSUE-DATE(6:2)  EQUAL ACUP-OLD-REISSUE-MON
    AND ACCT-REISSUE-DATE(9:2)  EQUAL ACUP-OLD-REISSUE-DAY
    AND FUNCTION LOWER-CASE (ACCT-GROUP-ID)           EQUAL
        FUNCTION LOWER-CASE (ACUP-OLD-GROUP-ID)
        CONTINUE
    ELSE
       SET DATA-WAS-CHANGED-BEFORE-UPDATE TO TRUE
       GO TO 9600-WRITE-PROCESSING-EXIT
    END-IF

    *  Customer data
    IF  FUNCTION UPPER-CASE (CUST-FIRST-NAME    ) EQUAL
        FUNCTION UPPER-CASE (ACUP-OLD-CUST-FIRST-NAME)
    AND FUNCTION UPPER-CASE (CUST-MIDDLE-NAME   ) EQUAL
        FUNCTION UPPER-CASE (ACUP-OLD-CUST-MIDDLE-NAME)
    AND FUNCTION UPPER-CASE (CUST-LAST-NAME     ) EQUAL
        FUNCTION UPPER-CASE (ACUP-OLD-CUST-LAST-NAME)
    AND FUNCTION UPPER-CASE (CUST-ADDR-LINE-1   ) EQUAL
        FUNCTION UPPER-CASE (ACUP-OLD-CUST-ADDR-LINE-1)
    AND FUNCTION UPPER-CASE (CUST-ADDR-LINE-2   ) EQUAL
        FUNCTION UPPER-CASE (ACUP-OLD-CUST-ADDR-LINE-2)
    AND FUNCTION UPPER-CASE (CUST-ADDR-LINE-3   ) EQUAL
        FUNCTION UPPER-CASE (ACUP-OLD-CUST-ADDR-LINE-3)
    AND FUNCTION UPPER-CASE (CUST-ADDR-STATE-CD ) EQUAL
        FUNCTION UPPER-CASE (ACUP-OLD-CUST-ADDR-STATE-CD)
    AND FUNCTION UPPER-CASE (CUST-ADDR-COUNTRY-CD) EQUAL
        FUNCTION UPPER-CASE (ACUP-OLD-CUST-ADDR-COUNTRY-CD)
    AND CUST-ADDR-ZIP           EQUAL ACUP-OLD-CUST-ADDR-ZIP
    AND CUST-PHONE-NUM-1        EQUAL ACUP-OLD-CUST-PHONE-NUM-1
    AND CUST-PHONE-NUM-2        EQUAL ACUP-OLD-CUST-PHONE-NUM-2
    AND CUST-SSN                EQUAL ACUP-OLD-CUST-SSN
    AND FUNCTION UPPER-CASE (CUST-GOVT-ISSUED-ID) EQUAL
        FUNCTION UPPER-CASE (ACUP-OLD-CUST-GOVT-ISSUED-ID)
    AND CUST-DOB-YYYY-MM-DD(1:4)EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD(1:4)
    AND CUST-DOB-YYYY-MM-DD(6:2)EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD(5:2)
    AND CUST-DOB-YYYY-MM-DD(9:2)EQUAL ACUP-OLD-CUST-DOB-YYYY-MM-DD(7:2)
    AND CUST-EFT-ACCOUNT-ID     EQUAL ACUP-OLD-CUST-EFT-ACCOUNT-ID
    AND CUST-PRI-CARD-HOLDER-IND EQUAL ACUP-OLD-CUST-PRI-HOLDER-IND
    AND CUST-FICO-CREDIT-SCORE  EQUAL ACUP-OLD-CUST-FICO-SCORE
        CONTINUE
    ELSE
       SET DATA-WAS-CHANGED-BEFORE-UPDATE TO TRUE
       GO TO 9600-WRITE-PROCESSING-EXIT
    END-IF.
```

Flow of 9600 (~217 lines): READ-UPDATE on ACCTDAT (L3894-L3915) → READ-UPDATE on CUSTDAT (L3921-L3942) → 9700-CHECK-CHANGE-IN-REC (L3947-L3952) → INITIALIZE `ACCT-UPDATE-RECORD` → MOVE fields → REWRITE ACCTDAT → REWRITE CUSTDAT → SYNCPOINT ROLLBACK on CUSTDAT failure.

Lock-failure paths: lines L3909-L3915 (ACCTDAT lock) and L3936-L3942 (CUSTDAT lock) set `INPUT-ERROR` and either `COULD-NOT-LOCK-ACCT-FOR-UPDATE` or `COULD-NOT-LOCK-CUST-FOR-UPDATE`.

9700 (~85 lines) compares 15 ACCOUNT fields and 18 CUSTOMER fields against the `ACUP-OLD-*` snapshot:

- **15 ACCOUNT fields**: `ACCT-ACTIVE-STATUS`, `ACCT-CURR-BAL`, `ACCT-CREDIT-LIMIT`, `ACCT-CASH-CREDIT-LIMIT`, `ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT`, plus 9 date-component subfields (3 date fields × 3 part subfields: year, month, day for OPEN, EXPIRAION, REISSUE), plus `ACCT-GROUP-ID` with `LOWER-CASE` normalization.
- **18 CUSTOMER fields**: 3 name fields with `UPPER-CASE` (FIRST, MIDDLE, LAST), 3 address-line fields with `UPPER-CASE`, 1 state with `UPPER-CASE`, 1 country with `UPPER-CASE`, ZIP (exact), 2 phones (exact), SSN (exact), govt-id with `UPPER-CASE`, DOB year/month/day (3 subfields), EFT account id, primary card holder indicator, FICO score.

**DOB slicing anomaly.** `ACUP-OLD-CUST-DOB-YYYY-MM-DD` positions (1:4), (5:2), (7:2) are compared against `CUST-DOB-YYYY-MM-DD` positions (1:4), (6:2), (9:2). The OLD record stores DOB **without** the hyphens at positions 5 and 7; the live record stores DOB **with** hyphens at positions 6 and 9 (format `YYYY-MM-DD`). The Java translation must preserve this comparison rule verbatim per AAP §0.7.1; do NOT silently align the offsets.

Uppercase / lowercase normalization rules:

- `ACCT-GROUP-ID` comparison uses `LOWER-CASE` on both sides.
- CUSTOMER name and address fields (first / middle / last / addr-line-1 / addr-line-2 / addr-line-3 / state / country / govt-id) use `UPPER-CASE` on both sides.
- Numeric fields and exact-byte fields (ZIP, phones, SSN, EFT, indicator, FICO) use direct `EQUAL` comparison.

## REWRITE flow + SYNCPOINT ROLLBACK semantics (SOLE rollback in entire COBOL source tree)

This section documents the SINGLE most distinctive characteristic of COACTUPC: it is the **only** COBOL program in the entire source tree that uses `EXEC CICS SYNCPOINT ROLLBACK`. Every COBOL→Java translation decision in this section is binding. **The COBOL behavior**: `9600-WRITE-PROCESSING` performs both REWRITEs in sequence — ACCTDAT first at `app/cbl/COACTUPC.cbl:L4066`, CUSTDAT second at `app/cbl/COACTUPC.cbl:L4086`. Four outcome combinations:

| ACCTDAT REWRITE | CUSTDAT REWRITE | Action | Resulting state | Visible INFOMSG |
|---|---|---|---|---|
| Fail | (not attempted) | Set `LOCKED-BUT-UPDATE-FAILED`; no compensation needed | `ACUP-CHANGES-OKAYED-BUT-FAILED` (`'F'`) | `'Changes unsuccessful. Please try again'` |
| Succeed | Fail | Set `LOCKED-BUT-UPDATE-FAILED` at L4099; `EXEC CICS SYNCPOINT ROLLBACK` at L4100 | `ACUP-CHANGES-OKAYED-BUT-FAILED` (`'F'`) | `'Changes unsuccessful. Please try again'` |
| Succeed | Succeed | Fall through to end of paragraph | `ACUP-CHANGES-OKAYED-AND-DONE` (`'C'`) | `'Changes committed to database'` |
| (CHECK-CHANGE detects external mutation) | (not attempted) | Set `DATA-WAS-CHANGED-BEFORE-UPDATE`; no compensation needed | `ACUP-SHOW-DETAILS` (`'S'`) | `'Record changed by some one else. Please review'` |

**Why it is unique.** `grep -rn "SYNCPOINT ROLLBACK" app/cbl/*.cbl` yields exactly ONE hit: `app/cbl/COACTUPC.cbl:L4100`. No other program in the entire 28-program source tree contains SYNCPOINT ROLLBACK. CICS SYNCPOINT (without ROLLBACK) appears at `app/cbl/COACTUPC.cbl:L953` as a normal commit on the PF03 exit path, and in other programs as ordinary commits.

**The Java translation strategy** per AAP §0.4.1: try/finally with compensating writes. CICS-managed unit-of-work transactions are NOT available in plain Java; JTA is forbidden by AAP §0.6.12 (no container). The compensation must be programmed explicitly with an in-memory snapshot of the pre-REWRITE ACCOUNT bytes:

```java
// Pseudocode (not the implementation; placed here for clarity).
public final class CoActUpC {
    public CoActUpOutput executeSave(CoActUpInput in, CoActUpCommarea commarea) {
        AccountRecord acctSnapshot = accountRepo.findById(in.acctId())   // pre-update bytes
            .orElseThrow();
        try {
            AccountRecord updatedAcct = applyAccountEdits(acctSnapshot, in);
            accountRepo.rewriteAccount(updatedAcct);                     // L4066 equivalent
            try {
                CustomerRecord updatedCust = applyCustomerEdits(in);
                customerRepo.rewriteCustomer(updatedCust);               // L4086 equivalent
            } catch (RuntimeException custFailure) {
                // SYNCPOINT ROLLBACK compensation (L4100 equivalent).
                accountRepo.rewriteAccount(acctSnapshot);                // restore pre-update bytes
                throw custFailure;                                       // re-throw to set LOCKED-BUT-UPDATE-FAILED
            }
        } catch (RuntimeException acctFailure) {
            // ACCTDAT REWRITE itself failed; no compensation needed.
            throw acctFailure;
        }
        // Success path: both REWRITEs succeeded.
        return CoActUpOutput.success(commarea);
    }
}
```

The compensating REWRITE at the `catch (RuntimeException custFailure)` site MUST restore the original 300 bytes of the ACCOUNT slot exactly — mirroring what `EXEC CICS SYNCPOINT ROLLBACK` does in CICS-managed VSAM (discard the in-flight unit of work; on-disk record reverts to its pre-REWRITE state).

**Byte-for-byte invariant.** After a rollback scenario, `acctdata.txt` MUST be byte-identical to `app/data/ASCII/acctdata.txt` (compensating REWRITE restored the bytes) and `custdata.txt` MUST be byte-identical to `app/data/ASCII/custdata.txt` (CUSTDAT REWRITE never succeeded). The harness scenario `pf05_save_rollback_on_custdat_fail` exercises this exact path with directive `EXPECT_SYNCPOINT_ROLLBACK`. **Implementation flag** per AAP §0.4.1: "flag as IMPLEMENTATION DECISION in `MIGRATION_NOTES.md`". The chosen Java strategy is documented in `java/MIGRATION_NOTES.md` §1.6: try/finally with explicit snapshot; not JTA; not container-managed; not any third-party transaction library. The compensating REWRITE is the responsibility of `CoActUpC` itself.

## Required test scenarios

`input_scenario.txt` MUST exercise — at minimum — the following 14 scenarios. The harness iterates each scenario and asserts byte-for-byte parity against the captured outputs in this folder. Each scenario name corresponds to a directive in `input_scenario.txt`.

1. **`first_time_entry_no_commarea`** — `EIBCALEN=0` → INITIALIZE both commareas; `PERFORM 3000-SEND-MAP`; `SET CDEMO-PGM-REENTER + ACUP-DETAILS-NOT-FETCHED`; display empty form. **EXPECT_ACCTDATA_UNCHANGED**: `acctdata_after.txt` byte-identical to `app/data/ASCII/acctdata.txt`. **EXPECT_CUSTDATA_UNCHANGED**: `custdata_after.txt` byte-identical to `app/data/ASCII/custdata.txt`.
2. **`enter_with_blank_acctsid`** — ENTER with blank `ACCTSID`; SET `WS-PROMPT-FOR-ACCT`; ERRMSG = `'Account number not provided'`. **EXPECT_ACCTDATA_UNCHANGED**, **EXPECT_CUSTDATA_UNCHANGED**.
3. **`enter_with_nonnumeric_acctsid`** — ENTER with non-numeric `ACCTSID` (e.g., `'ABC12345678'`); SET `SEARCHED-ACCT-NOT-NUMERIC`; ERRMSG = `'Account number must be a non zero 11 digit number'`. **EXPECT_ACCTDATA_UNCHANGED**, **EXPECT_CUSTDATA_UNCHANGED**.
4. **`enter_valid_full_chain_success`** — `9000-READ-ACCT` succeeds at all 3 hops (CXACAIX + ACCTDAT + CUSTDAT all found); SET `ACUP-SHOW-DETAILS`; INFOMSG = `'Details of selected account shown above'`; ERRMSG cleared. **EXPECT_ACCTDATA_UNCHANGED** (READ-only Pass 1), **EXPECT_CUSTDATA_UNCHANGED**.
5. **`enter_valid_notfnd_in_cxacaix`** — 9200 returns NOTFND; SET `DID-NOT-FIND-ACCT-IN-CARDXREF`; STRING template error message built embedding ACCT-ID + RESP + RESP2; subsequent reads skipped. **EXPECT_ACCTDATA_UNCHANGED**, **EXPECT_CUSTDATA_UNCHANGED**.
6. **`enter_valid_notfnd_in_acctdat`** — 9200 succeeds, 9300 returns NOTFND; SET `DID-NOT-FIND-ACCT-IN-ACCTDAT`; STRING template error built. **EXPECT_ACCTDATA_UNCHANGED**, **EXPECT_CUSTDATA_UNCHANGED**.
7. **`pf03_exit_to_menu`** — `CCARD-AID-PFK03` branch at L927-L959; `EXEC CICS SYNCPOINT` (L952-L954, **a normal commit**, NOT a ROLLBACK); `EXEC CICS XCTL` TO `LIT-MENUPGM` (COMEN01C). **EXPECT_ACCTDATA_UNCHANGED**, **EXPECT_CUSTDATA_UNCHANGED**; XCTL command emitted to `stdout.txt` (or to a control directive in `bms_output.txt`).
8. **`pf05_save_success_rewrite_both`** — multi-pass: Pass 1 (ENTER+ACCTSID → READ chain success); Pass 2 (ENTER+edited fields → VALIDATE success → `ACUP-CHANGES-OK-NOT-CONFIRMED`); Pass 3 (PF5 → `9600-WRITE-PROCESSING` → both REWRITEs succeed → `ACUP-CHANGES-OKAYED-AND-DONE` → INFOMSG = `'Changes committed to database'`). **EXPECT_ACCTDATA_CHANGED**: `acctdata_after.txt` differs from input ONLY at the affected 300-byte slot; total file length unchanged. **EXPECT_CUSTDATA_CHANGED**: `custdata_after.txt` differs ONLY at the affected 500-byte slot; total file length unchanged.
9. **`pf05_save_rollback_on_custdat_fail`** **(CRITICAL — exercises SOLE COBOL SYNCPOINT ROLLBACK at L4100)** — Pass 3 with ACCTDAT REWRITE success at L4066 and CUSTDAT REWRITE failure at L4086 (simulated via mock injection at the customer repository); SET `LOCKED-BUT-UPDATE-FAILED` at L4099; `EXEC CICS SYNCPOINT ROLLBACK` at L4100; state transitions to `ACUP-CHANGES-OKAYED-BUT-FAILED` (`'F'`); INFOMSG = `'Changes unsuccessful. Please try again'`. **EXPECT_ACCTDATA_UNCHANGED**: `acctdata_after.txt` byte-identical to `app/data/ASCII/acctdata.txt` (the compensating REWRITE restored the original bytes). **EXPECT_CUSTDATA_UNCHANGED**: `custdata_after.txt` byte-identical to `app/data/ASCII/custdata.txt` (CUSTDAT REWRITE never succeeded). **EXPECT_SYNCPOINT_ROLLBACK** directive in `input_scenario.txt` verifies the compensation occurred.
10. **`pf12_cancel_after_changes_ok_not_confirmed`** — Pass 2 reaches `ACUP-CHANGES-OK-NOT-CONFIRMED`; user presses PF12; `2000-DECIDE-ACTION`'s `WHEN CCARD-AID-PFK12` branch reloads original data via `9000-READ-ACCT`; state returns to `ACUP-SHOW-DETAILS`; no REWRITE issued. **EXPECT_ACCTDATA_UNCHANGED**, **EXPECT_CUSTDATA_UNCHANGED**.
11. **`invalid_aid_pfk04`** — PF04 (or any unmapped AID such as PF1, PF2, PF6 through PF11, CLEAR, PA1, PA2); silently coerced to ENTER at L914-L916; subsequent flow as if ENTER was pressed. **EXPECT**: behavior identical to whatever ENTER would have done in the current state (matches scenario 2 if `ACCTSID` was blank, scenario 4 if `ACCTSID` was a valid existing account, etc.).
12. **`invalid_input_subscenarios`** (4 sub-cases that share the same parent scenario):
    - Invalid `ACSTTUS` (`'X'` instead of `'Y'`/`'N'`) → `ACCT-STATUS-MUST-BE-YES-NO`.
    - Invalid expiry month (`'13'`) → `THIS-MONTH-NOT-VALID`.
    - Invalid FICO score (`'200'`, out of 300-850 range) → suffix `': should be between 300 and 850'`.
    - Non-alphabetic last name (e.g., `'Smith42'`) → `WS-NAME-MUST-BE-ALPHA`.
    **EXPECT_ACCTDATA_UNCHANGED**, **EXPECT_CUSTDATA_UNCHANGED** for ALL sub-scenarios (no REWRITE).
13. **`no_change_detected`** — Pass 2 with all edited fields equal to current values → `1205-COMPARE-OLD-NEW` returns NO-CHANGES-FOUND → INFOMSG = `'No change detected with respect to values fetched.'`. **EXPECT_ACCTDATA_UNCHANGED**, **EXPECT_CUSTDATA_UNCHANGED**.
14. **`pan_masking_invariant`** (AAP §0.7.2) — `stdout.txt` MUST NOT contain any 16-digit numeric substring matching any `CARD-NUM` from the cross-reference. Mask format: `'************<4digits>'`. Note `bms_output.txt` MAY contain the full PAN at any field that legitimately displays card numbers (screen ≠ log); the COACTUP map itself does NOT display a card-number field, so this scenario primarily polices any incidental DISPLAY emissions from error STRING templates that embed card numbers.

## Files in this folder

The harness consumes six files from this folder. Five of them are placeholder/capture artifacts produced by sibling create_file calls; this README is the authoritative contract that explains them.

- **`README.md`** — this file. The single source of truth for what the COACTUPC golden-record fixture contains, what byte-level outputs it expects, and what Java semantics the `CoActUpC` translation must honor.
- **`input_scenario.txt`** — synthesized CICS pseudo-conversation script (test-owned). One submission per line with whitespace-separated directives documented in its own comment header. Lines beginning with `#` are comments; blank lines are ignored. Each scenario directive line includes an `EXPECT_*` clause that the harness verifies against the captured output files in this folder.
- **`acctdata.txt`** — expected `ACCTDAT` state after the scenario's REWRITE (or pre-REWRITE state for cancel / error / rollback scenarios). In-place REWRITE semantics: same total byte length as `app/data/ASCII/acctdata.txt` (50 records × 300 bytes = 15,000 bytes data plus any line-terminator convenience bytes); only the affected slot(s) differ.
- **`custdata.txt`** — expected `CUSTDAT` state after the scenario's REWRITE (or pre-REWRITE state for cancel / error / rollback scenarios). In-place REWRITE semantics: same total byte length as `app/data/ASCII/custdata.txt` (50 records × 500 bytes = 25,000 bytes data plus any line-terminator convenience bytes); only the affected slot(s) differ.
- **`stdout.txt`** — captured Java SLF4J logging output (and any COBOL DISPLAY output during the capture). COACTUPC has LIMITED DISPLAY usage — most output goes through BMS SEND MAP. The placeholder documents the expected empty / minimal output. **PAN MASKING INVARIANT** (AAP §0.7.2): any log emission that includes `XREF-CARD-NUM` MUST mask all but the last 4 digits. Mask format: `'************<4digits>'`.
- **`bms_output.txt`** — serialized BMS SEND MAP output. Per-scenario block format documenting each `CACTUPAO` buffer (1920 bytes per scenario; 24 rows × 80 cols). The COACTUP map does NOT contain a card-number field, so the PAN does not appear on this screen; however, ZIP, SSN, and phone numbers ARE displayed. Screen buffer ≠ log per AAP §0.7.2.

## Record layout reference (CVACT01Y, CVCUS01Y, CVACT03Y)

### 300-byte ACCOUNT-RECORD layout (from `app/cpy/CVACT01Y.cpy:L4-L17`)

| Offset (1-based) | Length | Field | PIC | Notes |
|---|---|---|---|---|
| 1 | 11 | `ACCT-ID` | 9(11) | Primary key |
| 12 | 1 | `ACCT-ACTIVE-STATUS` | X(01) | `Y` or `N` |
| 13 | 12 | `ACCT-CURR-BAL` | S9(10)V99 | signed implied 2-decimal; trailing-overpunch ASCII encoding |
| 25 | 12 | `ACCT-CREDIT-LIMIT` | S9(10)V99 | signed implied 2-decimal |
| 37 | 12 | `ACCT-CASH-CREDIT-LIMIT` | S9(10)V99 | signed implied 2-decimal |
| 49 | 10 | `ACCT-OPEN-DATE` | X(10) | format `YYYY-MM-DD` |
| 59 | 10 | `ACCT-EXPIRAION-DATE` | X(10) | format `YYYY-MM-DD`; **COBOL misspelling preserved** per AAP §0.7.1 (Java field `acctExpiraionDate`) |
| 69 | 10 | `ACCT-REISSUE-DATE` | X(10) | format `YYYY-MM-DD` |
| 79 | 12 | `ACCT-CURR-CYC-CREDIT` | S9(10)V99 | signed implied 2-decimal |
| 91 | 12 | `ACCT-CURR-CYC-DEBIT` | S9(10)V99 | signed implied 2-decimal |
| 103 | 10 | `ACCT-ADDR-ZIP` | X(10) | space-padded |
| 113 | 10 | `ACCT-GROUP-ID` | X(10) | space-padded |
| 123 | 178 | `FILLER` | X(178) | spaces |
| **300** | | | | **Total** |

Arithmetic verification: 11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12 + 12 + 10 + 10 + 178 = 300. The five `S9(10)V99` monetary fields (`ACCT-CURR-BAL`, `ACCT-CREDIT-LIMIT`, `ACCT-CASH-CREDIT-LIMIT`, `ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT`) translate to `java.math.BigDecimal` with scale 2 per AAP §0.7.4. The Java translation forbids `double` or `float` for any monetary value.

### 500-byte CUSTOMER-RECORD layout (from `app/cpy/CVCUS01Y.cpy:L4-L23`)

| Offset (1-based) | Length | Field | PIC | Notes |
|---|---|---|---|---|
| 1 | 9 | `CUST-ID` | 9(09) | Primary key |
| 10 | 25 | `CUST-FIRST-NAME` | X(25) | |
| 35 | 25 | `CUST-MIDDLE-NAME` | X(25) | |
| 60 | 25 | `CUST-LAST-NAME` | X(25) | |
| 85 | 50 | `CUST-ADDR-LINE-1` | X(50) | |
| 135 | 50 | `CUST-ADDR-LINE-2` | X(50) | |
| 185 | 50 | `CUST-ADDR-LINE-3` | X(50) | City stored here |
| 235 | 2 | `CUST-ADDR-STATE-CD` | X(02) | |
| 237 | 3 | `CUST-ADDR-COUNTRY-CD` | X(03) | |
| 240 | 10 | `CUST-ADDR-ZIP` | X(10) | |
| 250 | 15 | `CUST-PHONE-NUM-1` | X(15) | `(NNN)NNN-NNNN` + 4 trailing spaces |
| 265 | 15 | `CUST-PHONE-NUM-2` | X(15) | `(NNN)NNN-NNNN` + 4 trailing spaces |
| 280 | 9 | `CUST-SSN` | 9(09) | |
| 289 | 20 | `CUST-GOVT-ISSUED-ID` | X(20) | |
| 309 | 10 | `CUST-DOB-YYYY-MM-DD` | X(10) | format `YYYY-MM-DD` |
| 319 | 10 | `CUST-EFT-ACCOUNT-ID` | X(10) | |
| 329 | 1 | `CUST-PRI-CARD-HOLDER-IND` | X(01) | `Y` or `N` |
| 330 | 3 | `CUST-FICO-CREDIT-SCORE` | 9(03) | 300-850 range |
| 333 | 168 | `FILLER` | X(168) | spaces |
| **500** | | | | **Total** |

Arithmetic verification: 9 + 25 + 25 + 25 + 50 + 50 + 50 + 2 + 3 + 10 + 15 + 15 + 9 + 20 + 10 + 10 + 1 + 3 + 168 = 500.

### 50-byte CARD-XREF-RECORD layout (from `app/cpy/CVACT03Y.cpy:L4-L9`)

| Offset (1-based) | Length | Field | PIC | Notes |
|---|---|---|---|---|
| 1 | 16 | `XREF-CARD-NUM` | X(16) | |
| 17 | 9 | `XREF-CUST-ID` | 9(09) | |
| 26 | 11 | `XREF-ACCT-ID` | 9(11) | |
| 37 | 14 | `FILLER` | X(14) | spaces |
| **50** | | | | **Total** |

Arithmetic verification: 16 + 9 + 11 + 14 = 50. `XREF-CARD-NUM` is the 16-digit PAN; per AAP §0.7.2 it must be masked in all log outputs to `'************<4digits>'`.

## Structural Invariants

- **In-place REWRITE preserves slot length**: 300-byte ACCT-RECORD slot and 500-byte CUST-RECORD slot are each rewritten in place. Total `acctdata_after.txt` length always equals `app/data/ASCII/acctdata.txt` length; total `custdata_after.txt` length always equals `app/data/ASCII/custdata.txt` length.
- **`acctExpiraionDate` typo preservation** (AAP §0.7.1; `app/cpy/CVACT01Y.cpy:L11`): the COBOL field `ACCT-EXPIRAION-DATE` (sic — missing T) MUST translate to Java field name `acctExpiraionDate` verbatim. Do NOT auto-correct to `acctExpirationDate`. Flag in `java/MIGRATION_NOTES.md` §1.6.
- **Duplicate 88-level name `DID-NOT-FIND-ACCT-IN-CARDXREF`** (AAP §0.7.1; `app/cbl/COACTUPC.cbl:L497-L498` AND L513-L514 — same name, different message text): preserved as two separate constants in the Java translation with disambiguating suffixes (e.g., `DID_NOT_FIND_ACCT_IN_CARDXREF_PRIMARY` for the L497 occurrence with text `'Did not find this account in account card xref file'` and `DID_NOT_FIND_ACCT_IN_CARDXREF_CARDS` for the L513 occurrence with text `'Did not find this account in cards database'`). The chosen disambiguation approach is documented in `java/MIGRATION_NOTES.md` §1.6.
- **Sort orders preserved**: REWRITE does NOT change `ACCT-ID` (primary key of ACCTDAT) or `CUST-ID` (primary key of CUSTDAT). The in-place semantics keep the file in original primary-key order. The Java translation MUST NOT re-sort or re-order any record on REWRITE.
- **Byte-for-byte parity rule** per AAP §0.6.11. The harness asserts `Files.readAllBytes(actualAcctdata)` equals `Files.readAllBytes(expectedAcctdata)`.
- **Verbatim 30+ COBOL messages** (AAP §0.7.1): preserved character-for-character including trailing periods, double-quotes vs. single-quotes, ASCII ellipses (`....`) and NEVER Unicode replacement. The `WS-EXIT-MESSAGE` `'PF03 pressed.Exiting              '` MUST retain its 14 trailing spaces verbatim in `stdout.txt` if logged (`app/cbl/COACTUPC.cbl:L481-L482`). The `CODING-TO-BE-DONE` dead-code 88-level `'Looks Good.... so far'` with four ASCII periods (`app/cbl/COACTUPC.cbl:L527-L528`; never SET by any paragraph) translates faithfully per AAP §0.7.1 and is flagged in `java/MIGRATION_NOTES.md` §1.6.
- **Forbidden Java idioms** (AAP §0.6.4, §0.6.5, §0.6.6, §0.6.12, §0.7.4): NO Spring / Spring Boot / Spring Batch / Spring Security / Hibernate / Flyway / PostgreSQL (plain factories and constructor injection only); NO `java.util.Date` / `java.util.Calendar` / `java.text.SimpleDateFormat` (use `java.time`: `LocalDate`, `LocalDateTime`, `LocalTime`, `Period`, `Duration`); NO `java.io.File` (use `java.nio.file`: `Files`, `Path`, `SeekableByteChannel`); NO `double` / `float` for monetary values (the five `S9(10)V99` fields all use `java.math.BigDecimal` with scale 2); NO `ThreadLocal` (use `ScopedValue`); NO `default` branches in pattern-matching switches (exhaustiveness is the safety guarantee).
- **Mandatory Java idioms**: `@CobolProgram("COACTUPC")` traceability annotation on the Java `CoActUpC` class per AAP §0.3.1; fixed-clock injection via `ScopedValue<Clock>` so `CURDATE` and `CURTIME` render reproducibly in `bms_output.txt` (the fixed clock value is documented in `java/MIGRATION_NOTES.md` §1.6).
- **PAN masking in logs** (AAP §0.7.2): the harness scans `stdout.txt` for any 16-digit numeric substring matching any `XREF-CARD-NUM` from `app/data/ASCII/cardxref.txt` and FAILS the parity test if an unmasked PAN appears. The screen buffer in `bms_output.txt` is exempt (the COACTUP map does not display the PAN anyway).
- **In-place REWRITE semantics**: a REWRITE replaces the slot at the same primary-key position; the total file byte length is unchanged; sibling records are not touched. The Java adapter `FileAccountRepository.rewriteAccount(...)` and `FileCustomerRepository.rewriteCustomer(...)` MUST implement this with `SeekableByteChannel.position(slotOffset).write(...)` semantics, not a whole-file rewrite.

## Verbatim COBOL Message Catalog

The following catalog reproduces every distinct COACTUPC message literal byte-for-byte. Preserve trailing spaces, periods, commas, and ASCII ellipses (NOT Unicode) exactly. The Java translation references each message as a `public static final String` constant in `com.blitzy.carddemo.application.account.CoActUpC.Messages` with names matching the COBOL 88-level identifier.

INFO messages (40-byte `WS-INFO-MSG` 88-levels at `app/cbl/COACTUPC.cbl:L463-L477`):

```text
Details of selected account shown above    FOUND-ACCOUNT-DATA           L466-L467
Enter or update id of account to update    PROMPT-FOR-SEARCH-KEYS       L468-L469
Update account details presented above.    PROMPT-FOR-CHANGES           L470-L471
Changes validated.Press F5 to save         PROMPT-FOR-CONFIRMATION      L472-L473 (no space after period)
Changes committed to database              CONFIRM-UPDATE-SUCCESS       L474-L475
Changes unsuccessful. Please try again     INFORM-FAILURE               L476-L477
```

RETURN/ERROR messages (75-byte `WS-RETURN-MSG` 88-levels at `app/cbl/COACTUPC.cbl:L479-L528`):

```text
PF03 pressed.Exiting                       WS-EXIT-MESSAGE              L481-L482 (14 trailing spaces)
Account number not provided                WS-PROMPT-FOR-ACCT           L483-L484
Last name not provided                     WS-PROMPT-FOR-LASTNAME       L485-L486
Name can only contain alphabets and spaces WS-NAME-MUST-BE-ALPHA        L487-L488
No input received                          NO-SEARCH-CRITERIA-RECEIVED  L489-L490
No change detected with respect to values fetched.   NO-CHANGES-DETECTED        L491-L492
Account number must be a non zero 11 digit number    SEARCHED-ACCT-ZEROES       L493-L494
Account number must be a non zero 11 digit number    SEARCHED-ACCT-NOT-NUMERIC  L495-L496 (duplicate text intentional)
Did not find this account in account card xref file  DID-NOT-FIND-ACCT-IN-CARDXREF  L497-L498 (FIRST occurrence)
Did not find this account in account master file     DID-NOT-FIND-ACCT-IN-ACCTDAT   L499-L500
Did not find associated customer in master file      DID-NOT-FIND-CUST-IN-CUSTDAT   L501-L502
Account Active Status must be Y or N       ACCT-STATUS-MUST-BE-YES-NO   L503-L504
Credit Limit must be supplied              CRED-LIMIT-IS-BLANK          L505-L506
Credit Limit is not valid                  CRED-LIMIT-IS-NOT-VALID      L507-L508
Card expiry month must be between 1 and 12 THIS-MONTH-NOT-VALID         L509-L510
Invalid card expiry year                   THIS-YEAR-NOT-VALID          L511-L512
Did not find this account in cards database          DID-NOT-FIND-ACCT-IN-CARDXREF  L513-L514 (DUPLICATE 88-name; preserve)
Did not find cards for this search condition         DID-NOT-FIND-ACCTCARD-COMBO    L515-L516
Could not lock account record for update   COULD-NOT-LOCK-ACCT-FOR-UPDATE  L517-L518
Could not lock customer record for update  COULD-NOT-LOCK-CUST-FOR-UPDATE  L519-L520
Record changed by some one else. Please review       DATA-WAS-CHANGED-BEFORE-UPDATE L521-L522
Update of record failed                    LOCKED-BUT-UPDATE-FAILED     L523-L524
Error reading Card Data File               XREF-READ-ERROR              L525-L526
Looks Good.... so far                      CODING-TO-BE-DONE            L527-L528 (DEAD CODE; 4 ASCII periods)
```

Inline error / abend literals:

```text
UNEXPECTED DATA SCENARIO                   ABEND-MSG default            L2637
UNEXPECTED ABEND OCCURRED.                 ABEND-ROUTINE default        L4206
```

STRING templates in `9200`/`9300`/`9400` file-error paths (`app/cbl/COACTUPC.cbl:L3673-L3697`, L3722-L3743, L3772-L3792) emit dynamic messages embedding the failing `ACCT-ID` / `CUST-ID` + `RESP` + `RESP2` codes. The harness reproduces these via parameterized error formatting; the exact template strings are captured per-scenario in `stdout.txt`.

Edit-variable name labels (32 short labels used in dynamic ` must be supplied.`, ` can have alphabets only.`, ` must be all numeric.`, etc. messages; from `app/cbl/COACTUPC.cbl:L1472-L2550`):

```text
Account Status                Open Date                  Credit Limit
Expiry Date                   Cash Credit Limit          Reissue Date
Current Balance               Current Cycle Credit Limit Current Cycle Debit Limit
SSN                           Date of Birth              FICO Score
First Name                    Middle Name                Last Name
Address Line 1                State                      Zip
City                          Country                    Phone Number 1
Phone Number 2                EFT Account Id             Primary Card Holder
SSN: First 3 chars            SSN 4th & 5th chars        SSN Last 4 chars
Address Line 2 (commented out at L1614)
```

Dynamic phone-error suffixes (`app/cbl/COACTUPC.cbl:L2254-L2410`):

```text
: Area code must be supplied.
: Area code must be A 3 digit number.
: Area code cannot be zero
: Not valid North America general purpose area code
: Prefix code must be supplied.
: Prefix code must be A 3 digit number.
: Prefix code cannot be zero
: Line number code must be supplied.
: Line number code must be A 4 digit number.
: Line number code cannot be zero
```

Other dynamic error suffixes (each preserved verbatim with leading colon and space where shown):

```text
: should not be 000, 666, or between 900 and 999   SSN-error suffix       L2457
: is not a valid state code                         State-error suffix     L2503
: should be between 300 and 850                     FICO-error suffix      L2523
Invalid zip code for state                          State+ZIP error        L2550
```

## Capture procedure cross-reference

See `java/MIGRATION_NOTES.md` §1.6 (Golden-record fixture capture procedure for COACTUPC) per AAP §0.7.5 for: the COBOL CICS build/run path; the harness that iterates `input_scenario.txt` and produces `acctdata.txt`, `custdata.txt`, `stdout.txt`, and `bms_output.txt`; the encoding (ASCII-mode capture by default; codepage IBM-1047 fallback documented for EBCDIC sources); the fixed-clock injection technique for `CURDATE` / `CURTIME` determinism; the SYNCPOINT ROLLBACK simulation harness (CUSTDAT lock-injection at the repository boundary); and the exact `bms_output.txt` serialization format (per-scenario 1920-byte block with header and trailer markers). Until capture is performed, the five data files in this folder are placeholders and `CoActUpCGoldenTest` remains `@Disabled` per AAP §0.6.11.

## Source lineage

- `app/cbl/COACTUPC.cbl` (4,236 lines) — COBOL source. PROGRAM-ID at L22; transaction `CAUP` at L535-L536; SYNCPOINT ROLLBACK at L4100. Key paragraphs: `0000-MAIN` L859-L1023 (4-branch `EVALUATE TRUE`); `1000-PROCESS-INPUTS` L1025-L1037; `1100-RECEIVE-MAP` L1039-L1426 (~387 lines); `1200-EDIT-MAP-INPUTS` L1429-L1678; `1205-COMPARE-OLD-NEW` L1681-L1777; `1210`-`1280` edit paragraphs (account, mandatory, yes-no, alpha, alphanum, numeric, signed-9V2, phone, SSN, state, FICO, state-zip); `2000-DECIDE-ACTION` L2562-L2643 (inner `EVALUATE TRUE`); `3000-SEND-MAP` L2649-L2663 (orchestrator); `3100`-`3400` screen build paragraphs; `9000-READ-ACCT` L3608-L3647 (3-step chain orchestrator); `9200-GETCARDXREF-BYACCT` L3650-L3698; `9300-GETACCTDATA-BYACCT` L3701-L3748; `9400-GETCUSTDATA-BYCUST` L3752-L3797; `9500-STORE-FETCHED-DATA` L3801-L3885; `9600-WRITE-PROCESSING` L3888-L4105 (**contains SYNCPOINT ROLLBACK at L4100**); `9700-CHECK-CHANGE-IN-REC` L4109-L4192 (optimistic concurrency); `ABEND-ROUTINE` L4203-L4226.
- `app/bms/COACTUP.bms` (513 lines) — BMS map. MAPSET `COACTUP`, MAP `CACTUPA`, 24×80 with `CTRL=(FREEKB)`, `DSATTS=(COLOR,HILIGHT,PS,VALIDN)`, `MAPATTS=(COLOR,HILIGHT,PS,VALIDN)`; 42 named DFHMDF fields plus literals.
- `app/cpy-bms/COACTUP.CPY` — symbolic map copybook. Defines `CACTUPAI` (input record) and `CACTUPAO REDEFINES CACTUPAI` (output record); 42 fields each in the standard symbolic-map suffix pattern (L/F/A/I for input; C/P/H/V/O for output).
- `app/cpy/CVACT01Y.cpy` (20 lines) — 300-byte ACCOUNT-RECORD layout (12 fields plus 178-byte FILLER). **`ACCT-EXPIRAION-DATE` misspelling preserved per AAP §0.7.1**.
- `app/cpy/CVCUS01Y.cpy` (26 lines) — 500-byte CUSTOMER-RECORD layout (18 fields plus 168-byte FILLER).
- `app/cpy/CVACT03Y.cpy` (11 lines) — 50-byte CARD-XREF-RECORD layout (3 fields plus 14-byte FILLER).
- `app/cpy/COCOM01Y.cpy` — `CARDDEMO-COMMAREA` structure (general / customer / account / card / more sub-records; 88-level state machines).
- `app/cpy/CVCRD01Y.cpy` — `CC-WORK-AREAS` (AID-key 88-conditions: ENTER, CLEAR, PA1, PA2, PFK01-PFK12; REDEFINES on `CC-ACCT-ID`, `CC-CARD-NUM`, `CC-CUST-ID`).
- `app/cpy/CSUTLDPY.cpy`, `app/cpy/CSUTLDWY.cpy` — date validation working storage. `app/cpy/CSLKPCDY.cpy` — North America area codes, US state codes, ZIP prefixes. `app/cpy/CSSTRPFY.cpy`, `app/cpy/CSSETATY.cpy` — PF-key store + BMS attribute setter procedure templates.
- `app/data/ASCII/acctdata.txt` — 50 × 300-byte ACCTDAT initial-state fixture (REFERENCE ONLY; loaded via classpath; NOT copied here per AAP §0.4.1).
- `app/data/ASCII/custdata.txt` — 50 × 500-byte CUSTDAT initial-state fixture (REFERENCE ONLY; classpath).
- `app/data/ASCII/cardxref.txt` — CARDXREF initial-state fixture (REFERENCE ONLY; classpath; supplies `XREF-CARD-NUM` values used by the PAN-masking invariant).

## Cross-references

- `java/carddemo-tests/src/test/resources/golden/coactupc/input/` — sibling input folder (documentation-marker README only).
- `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/CoActUpCGoldenTest.java` — JUnit 5 test class (`@Disabled` until captures committed); `java/carddemo-tests/src/test/java/com/blitzy/carddemo/tests/golden/GoldenRecordTest.java` — abstract base class.
- `java/carddemo-application/src/main/java/com/blitzy/carddemo/application/account/CoActUpC.java` — class under test (in `account` subpackage, NOT `card`); `CoActUpInput.java` and `CoActUpOutput.java` — entry-contract DTO records (translated from CACTUPAI / CACTUPAO symbolic maps).
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/record/{AccountRecord,CustomerRecord,CardXrefRecord}.java` — 300/500/50-byte record translations of CVACT01Y / CVCUS01Y / CVACT03Y.
- `java/carddemo-domain/src/main/java/com/blitzy/carddemo/domain/port/{AccountRepository,CustomerRepository,CardXrefRepository}.java` — port interfaces (`rewriteAccount`, `rewriteCustomer` methods for in-place REWRITE).
- `java/carddemo-adapter-file/src/main/java/com/blitzy/carddemo/adapter/file/{FileAccountRepository,FileCustomerRepository}.java` — file adapter implementations.
- `java/MIGRATION_NOTES.md` §1.6 — capture procedure plus SYNCPOINT ROLLBACK IMPLEMENTATION DECISION.
- Sibling fixtures: `golden/coactvwc/expected/README.md` (View pattern — same 3-file READ chain but no UPDATE) and `golden/cocrdupc/expected/README.md` (single-file UPDATE/REWRITE pattern — analogous but on CARDDAT, no SYNCPOINT ROLLBACK).

## Authority references

- **Scope and parity**: AAP §0.1.1 (byte-for-byte parity); §0.2.1 (in-scope: golden-record fixtures via the `golden/**/*` wildcard); §0.2.2 (out-of-scope: `app/` tree NOT modified); §0.6.11 (non-negotiable PR gate; `@Disabled` until captures committed).
- **Architecture and translation**: AAP §0.3.1 (harness directory convention; `@CobolProgram` annotation); §0.4.1 (COACTUPC → `CoActUpC` in `application.account`; SYNCPOINT ROLLBACK IMPLEMENTATION DECISION; `acctExpiraionDate` typo preservation); §0.6.12 (architectural override: no Spring / Spring Boot / Spring Batch / Hibernate / Flyway / PostgreSQL / Spring Security).
- **Java idiom mandates**: AAP §0.6.4 (`java.time`; no `java.util.Date` / `Calendar` / `SimpleDateFormat`); §0.6.5 (`java.nio.file`; no `java.io.File`); §0.6.6 (`ScopedValue` replaces `ThreadLocal`; fixed-clock injection); §0.7.4 (no JEP preview features 502, 505, 507; no `--enable-preview`; JEP 512 utilities-only; no `default` branches; no `double` / `float` for monetary values).
- **Minimal Change Clause and security**: AAP §0.7.1 (verbatim messages; preserve `acctExpiraionDate` typo; preserve duplicate `DID-NOT-FIND-ACCT-IN-CARDXREF`); §0.7.2 (PAN masking — last 4 digits only); §0.7.5 (capture procedure in `java/MIGRATION_NOTES.md` §1.6).

## Contrast matrix (vs COACTVWC and COCRDUPC)

The three closely-related online programs share the same hexagonal layout but differ sharply in file operations and transactional semantics. This matrix exists to prevent conflation during translation review:

| Aspect | COACTVWC (View) | COCRDUPC (Card Update) | COACTUPC (Account Update — THIS) |
|---|---|---|---|
| Transaction ID | `CAVW` | `CCUP` | `CAUP` |
| Mapset / Map | `COACTVW` / `CACTVWA` | `COCRDUP` / `CCRDUPA` | `COACTUP` / `CACTUPA` |
| File operations | READ-only ×3 (ACCTDAT + CUSTDAT + CXACAIX) | READ + READ-UPDATE + REWRITE ×1 (CARDDAT) | READ-only ×3 + READ-UPDATE ×2 + REWRITE ×2 |
| Files touched | 3 | 1 | 3 (READ) + 2 (UPDATE / REWRITE) |
| SYNCPOINT ROLLBACK | NO | NO | **YES — SOLE OCCURRENCE in codebase** |
| Editable BMS fields | 1 (ACCTSID) | 4 (CRDNAME, CRDSTCD, EXPMON, EXPYEAR) + 1 hidden (EXPDAY) + 2 search (ACCTSID, CARDSID) | 1 search (ACCTSID) + ~37 update fields |
| State machine | None (single READ pass) | CCUP 7-state | ACUP 7-state |
| Multi-pass flow | NO (single pass) | YES (3 passes: ENTER→READ; ENTER→VALIDATE; PF5→REWRITE) | YES (same 3-pass pattern) |
| Optimistic concurrency | NO | YES (9300 compares 6 fields) | YES (9700 compares 15 ACCT + 18 CUST fields) |
| AID keys handled | ENTER, PF3 | ENTER, PF3, PF5 (conditional), PF12 (conditional) | ENTER, PF3, PF5 (conditional), PF12 (conditional) |
| Verbatim error messages | 12 + inline anomalies | 29 | 30+ (largest set in codebase) |
| Validation paragraphs | 1 (1210-EDIT-ACCOUNT) | 6 (1210/1220/1230/1240/1250/1260) | ~13 (1210-1280) |
| Java class FQCN | `com.blitzy.carddemo.application.account.CoActVwC` | `com.blitzy.carddemo.application.card.CoCrdUpC` | `com.blitzy.carddemo.application.account.CoActUpC` |

## DO NOT modify the fixture data without re-capture

- The five data files in this folder (`input_scenario.txt`, `acctdata.txt`, `custdata.txt`, `stdout.txt`, `bms_output.txt`) plus this README are a **coupled set**. If `input_scenario.txt` is modified, ALL other expected outputs MUST be re-captured per `java/MIGRATION_NOTES.md` §1.6. Ad-hoc edits without re-capture WILL break byte-for-byte parity and FAIL the PR gate.
- Do NOT "fix" odd formatting in expected files (the odd formatting is CICS / BMS observable behavior per AAP §0.7.1). Do NOT remove trailing spaces inside `ERRMSG` / `INFOMSG` fields (e.g., the L482 14-trailing-spaces in `WS-EXIT-MESSAGE`). Do NOT normalize trailing newlines in `acctdata.txt` or `custdata.txt` (records are concatenated in production VSAM semantics; the ASCII fixture's LF terminators are an inspection convenience but the capture process MUST preserve whatever line-terminator convention the capture harness emits).
- DO ensure no unmasked PAN appears in `stdout.txt` per AAP §0.7.2. DO NOT auto-correct the COBOL field name `ACCT-EXPIRAION-DATE` (sic) — preserve as Java field name `acctExpiraionDate` per AAP §0.7.1. DO NOT consolidate the duplicate `DID-NOT-FIND-ACCT-IN-CARDXREF` 88-levels at L497 and L513 — preserve both with disambiguating Java constant names per AAP §0.7.1.
