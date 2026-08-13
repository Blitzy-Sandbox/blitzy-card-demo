package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.AccountViewController;
import com.vsergeychik.carddemo.account.dto.AccountViewRequest;
import com.vsergeychik.carddemo.account.dto.AccountViewResponse;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.RepositoryOperation;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * The behavioural-parity gate for {@code COACTVWC} - the account view screen, CSD transaction {@code CAVW},
 * projected as {@code GET /api/accounts/&#123;acctId&#125;} onto
 * {@link com.vsergeychik.carddemo.account.AccountViewController}.
 */
class COACTVWCParityTest {
    private static final String PROGRAM = AccountViewResponse.THIS_PROGRAM;

    private static final String ACCTDAT = AccountRepository.CICS_FILE_NAME;

    private static final String CCXREF = CardXrefRepository.BASE_DD_NAME;

    private static final String CUSTDAT = CustomerRepository.CICS_FILE_NAME;

    private static final Map<String, String> READ_SITES = Map.of(
            "READ-" + CCXREF, CCXREF,
            "READ-" + ACCTDAT, ACCTDAT,
            "READ-" + CUSTDAT, CUSTDAT);

    private static final int REQUIRED_CASES = ParityHarness.CASES_PER_PROGRAM;

    private static final Charset FIXTURE_CHARSET = StandardCharsets.US_ASCII;

    private static final String LENGTH_ITEM_SUFFIX = "L";

    private static final String CURSOR_ITEM =
            AccountViewResponse.ScreenField.ACCTSID.label() + LENGTH_ITEM_SUFFIX;

    private static final String HIGHLIGHT_ITEM_SUFFIX = "H";

    private static final Map<Byte, String> AID_MNEMONICS = Map.copyOf(CicsAid.mnemonicsByAid());

    private static List<ParityCase> cases() {
        List<ParityCase> loaded = ParityHarness.casesOf(PROGRAM);
        if (loaded.size() != REQUIRED_CASES) {
            throw new IllegalStateException("Parity gate for " + PROGRAM + " requires exactly "
                    + REQUIRED_CASES + " cases under src/test/resources/parity/" + PROGRAM
                    + "/, named case01 through case" + REQUIRED_CASES + ", but " + loaded.size()
                    + " loaded. A missing case does not weaken the gate - it removes it, because an "
                    + "assertion nobody runs cannot fail.");
        }
        for (int ordinal = 1; ordinal <= REQUIRED_CASES; ordinal++) {
            String expected = ParityHarness.caseId(ordinal);
            String actual = loaded.get(ordinal - 1).caseId();
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Parity case " + ordinal + " for " + PROGRAM
                        + " is \"" + actual + "\" where the gate requires \"" + expected
                        + "\". The identifiers are the file-name stems and they are ordered, so a "
                        + "mis-numbered file would silently take another case's place.");
            }
        }
        return loaded;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("COACTVWC: every case diffs to zero against the COBOL-derived baseline")
    void theTranslationMatchesTheCobolFieldForField(ParityCase parityCase) {
        DiffResult result = ParityHarness.usAscii()
                .judge(parityCase, UnitKind.CONTROLLER_POJO, COACTVWCParityTest::invoke);

        assertThat(result.count())
                .describedAs("%s/%s must diff to zero. %s", PROGRAM, parityCase.caseId(),
                        result.render())
                .isZero();
    }

    private static UnitOutcome invoke(Invocation invocation) {
        SeededDataset crossReference = invocation.dataset(CCXREF);
        SeededDataset accounts = invocation.dataset(ACCTDAT);
        SeededDataset customers = invocation.dataset(CUSTDAT);
        FileStatus.Outcome forced = invocation.hasForcedOutcome(RepositoryOperation.READ)
                ? invocation.forcedOutcome(RepositoryOperation.READ).outcome()
                : null;
        String forcedSite = forcedSite(invocation.caseId(), forced, invocation.stimulus(),
                invocation.datasets());

        AccountViewResponse painted = paint(invocation.caseId(), invocation.datasets(), forcedSite,
                invocation.mapFields(), invocation.commarea(), invocation.eibcalen(),
                invocation.aid(), invocation.clock());

        UnitOutcome.Builder recorder = invocation.recorder();
        recorder.finalStateUnchanged(crossReference, CardXrefRecord.LAYOUT);
        recorder.finalStateUnchanged(accounts, AccountRecord.LAYOUT);
        recorder.finalStateUnchanged(customers, CustomerRecord.LAYOUT);
        recorder.response(observed(painted));
        emitPlainText(painted, recorder);
        recorder.returnCode(0);
        return recorder.build();
    }

    private static AccountViewResponse paint(String caseId, Map<String, SeededDataset> seeded,
            String forcedSite, Map<String, String> mapFields, Map<String, String> commarea,
            int eibcalen, String aid, Clock clock) {
        AccountViewController controller = new AccountViewController(
                seededAccountRepository(seeded.get(ACCTDAT), forcedSite),
                seededCardXrefRepository(seeded.get(CCXREF), forcedSite),
                seededCustomerRepository(seeded.get(CUSTDAT), forcedSite),
                clock, FIXTURE_CHARSET);
        AccountViewRequest request = requestOf(caseId, mapFields, commarea, eibcalen);
        ScreenResponse<AccountViewResponse> envelope = Objects.requireNonNull(
                controller.viewAccount(pathAccountIdOf(mapFields), request, null, eibcalen,
                        Byte.toUnsignedInt(aidByteOf(caseId, aid))).getBody(),
                "COACTVWC leaves either by EXEC CICS XCTL at :349 or by EXEC CICS RETURN at :402 and "
                        + ":885, so viewAccount always answers with a body");
        assertMetadataProjection(caseId, envelope);
        return envelope.screen();
    }

    private static void assertMetadataProjection(String caseId,
            ScreenResponse<AccountViewResponse> envelope) {
        AccountViewResponse painted = envelope.screen();
        ScreenMetadata metadata = envelope.screenMetadata();

        assertThat(metadata.fields())
                .describedAs("%s: every one of the 37 COACTVW fields declares a four-item attribute "
                        + "quad in app/cpy-bms/COACTVW.CPY, and the client needs all of them to render "
                        + "what the terminal showed", caseId)
                .hasSize(AccountViewResponse.FIELD_COUNT);

        for (AccountViewResponse.ScreenField field : AccountViewResponse.ScreenField.values()) {
            AccountViewResponse.FieldAttributes quad = painted.attributes(field);
            ScreenMetadata.FieldMetadata published = metadata.fields().get(field.label());
            assertThat(published)
                    .describedAs("%s: %s has no published quad", caseId, field.label())
                    .isNotNull();
            assertThat(published.colour())
                    .describedAs("%s: %sC is X'%02X', and it must reach the client unsigned", caseId,
                            field.label(), quad.getColour())
                    .isEqualTo(Byte.toUnsignedInt(quad.getColour()));
            assertThat(published.protection())
                    .describedAs("%s: %sP is the programmed-symbol plane", caseId, field.label())
                    .isEqualTo(Byte.toUnsignedInt(quad.getPs()));
            assertThat(published.highlight()).isEqualTo(Byte.toUnsignedInt(quad.getHilight()));
            assertThat(published.validation()).isEqualTo(Byte.toUnsignedInt(quad.getValidn()));
        }

        assertThat(metadata.cursorField())
                .describedAs("%s: ACCTSID is this program's entire cursor vocabulary - all three arms of "
                        + ":546-552 move -1 to ACCTSIDL and no other xxxL item is ever written - and null "
                        + "is the transfer path at :349, which sends no map and so places no cursor",
                        caseId)
                .isIn(null, AccountViewResponse.ScreenField.ACCTSID.label());
        assertThat(metadata.messageColour())
                .describedAs("%s: the message colour is ERRMSG's own xxxC item", caseId)
                .isEqualTo(Byte.toUnsignedInt(
                        painted.attributes(AccountViewResponse.ScreenField.ERRMSG).getColour()));
        assertThat(metadata.resetAllOutputFields())
                .describedAs("%s: COACTVWC has no MOVE LOW-VALUES TO CACTVWAO, so there is no "
                        + "group-clear for the client to reproduce", caseId)
                .isFalse();
    }

    private static String forcedSite(String caseId, FileStatus.Outcome forced,
            ParityCase.UnitStimulus stimulus, Map<String, SeededDataset> seeded) {
        Map<String, ParityCase.CallSiteOutcome> declaredSites = stimulus.callSiteOutcomes();
        if (forced == null) {
            if (!declaredSites.isEmpty()) {
                throw new IllegalStateException(PROGRAM + "/" + caseId + " declares call sites "
                        + declaredSites.keySet() + " but forces no read outcome. Naming a site without an "
                        + "outcome to report there would be a control that does nothing.");
            }
            return null;
        }
        if (forced != FileStatus.Outcome.OTHER) {
            throw new IllegalStateException(PROGRAM + "/" + caseId + " forces outcome "
                    + forced + " on a read. Only " + FileStatus.Outcome.OTHER + " needs forcing here: "
                    + FileStatus.Outcome.OK + " and " + FileStatus.Outcome.DUPLICATE
                    + " require a record an empty dataset has none of, "
                    + FileStatus.Outcome.NOT_FOUND + " is what an empty dataset answers anyway, and "
                    + FileStatus.Outcome.END_OF_FILE + " is a browse outcome and COACTVWC opens no "
                    + "browse.");
        }
        if (declaredSites.size() != 1) {
            throw new IllegalStateException(PROGRAM + "/" + caseId + " forces a read outcome but "
                    + "declares " + declaredSites.size() + " call sites " + declaredSites.keySet()
                    + ". Exactly one is required, because all three of this program's reads are READ and "
                    + "the declared site is what names which of them is driven: none would leave the "
                    + "outcome applying to nothing, and two would leave it ambiguous.");
        }

        String site = declaredSites.keySet().iterator().next();
        String dataset = READ_SITES.get(site);
        if (dataset == null) {
            throw new IllegalStateException(PROGRAM + "/" + caseId + " declares an outcome at '" + site
                    + "', which is not one of " + READ_SITES.keySet() + ". Those three are the program's "
                    + "only file verbs - the EXEC CICS READs at :843, :787 and :864 - so a name matching "
                    + "none of them would leave every read untouched while the case read as though one "
                    + "were driven.");
        }
        ParityCase.CallSiteOutcome outcome = declaredSites.get(site);
        if (outcome.status() != null || outcome.resp() != null || outcome.afterRecords() != null) {
            throw new IllegalStateException(PROGRAM + "/" + caseId + " declares a status, a RESP or a "
                    + "record count at " + site + ". The outcome itself is carried by the case's "
                    + "forcedOutcomes member, which is the shape ParityCase gives an online read; the "
                    + "call-site entry exists to name WHICH read, so a second spelling of the outcome "
                    + "here could contradict it.");
        }

        List<String> declaredEmpty = new ArrayList<>(1);
        for (Map.Entry<String, String> readSite : READ_SITES.entrySet()) {
            if (seeded.get(readSite.getValue()).isEmpty()) {
                declaredEmpty.add(readSite.getValue());
            }
        }
        if (!List.of(dataset).equals(declaredEmpty)) {
            throw new IllegalStateException(PROGRAM + "/" + caseId + " declares its outcome at " + site
                    + " but its empty datasets are " + declaredEmpty + ". The driven read must be the "
                    + "case's one empty dataset: an empty dataset is one whose rows cannot answer any "
                    + "key, which is exactly why the outcome has to be supplied rather than seeded, and "
                    + "because the other two still hold their rows the reads before it still succeed and "
                    + "the flow still reaches the site being driven. Two declarations that disagree are "
                    + "refused rather than one being quietly honoured.");
        }
        return dataset;
    }

    private static AccountViewResponse paintFrom(ParityCase parityCase) {
        Map<String, SeededDataset> seeded = ParityHarness.usAscii().seed(parityCase);
        ParityCase.ForcedOutcome declared =
                parityCase.screenRequest().forcedOutcomes().get(RepositoryOperation.READ);
        String forcedSite = forcedSite(parityCase.caseId(),
                declared == null ? null : declared.outcome(), parityCase.unitStimulus(), seeded);
        return paint(parityCase.caseId(), seeded, forcedSite,
                parityCase.screenRequest().mapFields(), parityCase.screenRequest().commarea(),
                parityCase.screenRequest().eibcalen(), parityCase.screenRequest().aid(),
                ParityHarness.usAscii().clockFor(parityCase));
    }

    private static AccountRepository seededAccountRepository(SeededDataset seeded,
            String forcedSite) {
        AccountRepository repository = Mockito.mock(AccountRepository.class, unstubbed -> {
            throw new UnsupportedOperationException("COACTVWC called AccountRepository."
                    + unstubbed.getMethod().getName() + ", which it has no statement for. Its only "
                    + "account verb is the EXEC CICS READ at app/cbl/COACTVWC.cbl:776-784 - there is "
                    + "no REWRITE, no browse and no read-for-update anywhere in the program - so a "
                    + "call to anything else is a translation reaching for a verb the source does not "
                    + "contain.");
        });
        Mockito.doAnswer(read -> readAccount(seeded, forcedSite, read.getArgument(0)))
                .when(repository).readByKey(ArgumentMatchers.anyLong());
        return repository;
    }

    private static AccountRepository.ReadResult readAccount(SeededDataset seeded, String forcedSite,
            Long acctId) {
        if (ACCTDAT.equals(forcedSite)) {
            return AccountRepository.ReadResult.of(AccountRepository.PERMANENT_ERROR_STATUS);
        }
        String key = keyImage(acctId, AccountRecord.ACCT_ID_LENGTH);
        for (String image : seeded.rows()) {
            if (image.startsWith(key)) {
                return AccountRepository.ReadResult.found(
                        AccountRecord.decode(image, FIXTURE_CHARSET));
            }
        }
        return AccountRepository.ReadResult.notFound();
    }

    private static CardXrefRepository seededCardXrefRepository(SeededDataset seeded,
            String forcedSite) {
        CardXrefRepository repository = Mockito.mock(CardXrefRepository.class, unstubbed -> {
            throw new UnsupportedOperationException("COACTVWC called CardXrefRepository."
                    + unstubbed.getMethod().getName() + ", which it has no statement for. Its only "
                    + "cross-reference verb is the EXEC CICS READ against the CXACAIX path at "
                    + "app/cbl/COACTVWC.cbl:727-735.");
        });
        Mockito.doAnswer(read -> readXrefByAccount(seeded, forcedSite, read.getArgument(0)))
                .when(repository).readByAccountIdViaAltIndex(ArgumentMatchers.anyString());
        Mockito.doAnswer(read -> readXrefByCardNumber(seeded, read.getArgument(0)))
                .when(repository).readByCardNumber(ArgumentMatchers.anyString());
        return repository;
    }

    private static CardXrefRepository.ReadResult readXrefByAccount(SeededDataset seeded,
            String forcedSite, String accountIdDigits) {
        if (CCXREF.equals(forcedSite)) {
            return CardXrefRepository.ReadResult.other(CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
                    CardXrefRepository.PERMANENT_ERROR_STATUS);
        }
        for (String image : seeded.rows()) {
            CardXrefRecord candidate = CardXrefRecord.decode(
                    image.getBytes(FIXTURE_CHARSET), FIXTURE_CHARSET);
            if (keyImage(candidate.xrefAcctId(), CardXrefRepository.ACCOUNT_ID_KEY_LENGTH)
                    .equals(accountIdDigits)) {
                return CardXrefRepository.ReadResult.found(
                        CardXrefRepository.ALTERNATE_INDEX_DD_NAME, candidate, image);
            }
        }
        return CardXrefRepository.ReadResult.notFound(
                CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
    }

    private static CardXrefRepository.ReadResult readXrefByCardNumber(SeededDataset seeded,
            String cardNumber) {
        for (String image : seeded.rows()) {
            CardXrefRecord candidate = CardXrefRecord.decode(
                    image.getBytes(FIXTURE_CHARSET), FIXTURE_CHARSET);
            if (candidate.xrefCardNum().equals(cardNumber)) {
                return CardXrefRepository.ReadResult.found(
                        CardXrefRepository.BASE_DD_NAME, candidate, image);
            }
        }
        return CardXrefRepository.ReadResult.notFound(CardXrefRepository.BASE_DD_NAME);
    }

    private static CustomerRepository seededCustomerRepository(SeededDataset seeded,
            String forcedSite) {
        CustomerRepository repository = Mockito.mock(CustomerRepository.class, unstubbed -> {
            throw new UnsupportedOperationException("COACTVWC called CustomerRepository."
                    + unstubbed.getMethod().getName() + ", which it has no statement for. Its only "
                    + "customer verb is the EXEC CICS READ at app/cbl/COACTVWC.cbl:826-834.");
        });
        Mockito.doAnswer(read -> readCustomer(seeded, forcedSite, read.getArgument(0)))
                .when(repository).readByKey(ArgumentMatchers.anyString());
        return repository;
    }

    private static CustomerRepository.ReadResult readCustomer(SeededDataset seeded, String forcedSite,
            String custId) {
        if (CUSTDAT.equals(forcedSite)) {
            return CustomerRepository.ReadResult.of(CustomerRepository.PERMANENT_ERROR_STATUS);
        }
        for (String image : seeded.rows()) {
            if (image.startsWith(custId)) {
                return CustomerRepository.ReadResult.found(
                        CustomerRecord.decode(image, FIXTURE_CHARSET), image);
            }
        }
        return CustomerRepository.ReadResult.notFound();
    }

    private static String keyImage(long value, int width) {
        return new FixedWidthCodec(FIXTURE_CHARSET).movePic9(value, width);
    }

    private static final int NO_COMMAREA_LENGTH = 0;

    private static AccountViewRequest requestOf(String caseId, Map<String, String> mapFields,
            Map<String, String> commarea, int eibcalen) {
        AccountViewRequest request = new AccountViewRequest();
        request.initializeMapArea();
        for (Map.Entry<String, String> field : mapFields.entrySet()) {
            request.setValue(screenFieldOf(caseId, field.getKey()), field.getValue());
        }
        if (eibcalen != NO_COMMAREA_LENGTH) {
            request.setNavigationContext(NavigationImage.from(commarea));
        }
        return request;
    }

    private static AccountViewRequest.ScreenField screenFieldOf(String caseId, String itemName) {
        for (AccountViewRequest.ScreenField field : AccountViewRequest.ScreenField.values()) {
            if (field.symbolicItemName().equals(itemName)) {
                return field;
            }
        }
        throw new IllegalStateException(PROGRAM + "/" + caseId + " declares the map field "
                + itemName + ", which is not one of the " + AccountViewResponse.FIELD_COUNT
                + " xxxI items of app/cpy-bms/COACTVW.CPY. Only those items carry payload; the xxxL, "
                + "xxxF and xxxA items are length, flag and attribute metadata.");
    }

    private static String pathAccountIdOf(Map<String, String> mapFields) {
        String declared = mapFields.get(AccountViewRequest.ScreenField.ACCTSID.symbolicItemName());
        return declared == null
                ? AccountViewRequest.spaces(AccountViewRequest.ACCTSID_LENGTH)
                : declared;
    }

    private static byte aidByteOf(String caseId, String mnemonic) {
        if (mnemonic == null) {
            return CicsAid.DFHENTER;
        }
        for (Map.Entry<Byte, String> candidate : AID_MNEMONICS.entrySet()) {
            if (candidate.getValue().equals(mnemonic)) {
                return candidate.getKey();
            }
        }
        throw new IllegalStateException(PROGRAM + "/" + caseId + " names the AID "
                + mnemonic + ", which common.CicsAid does not publish. DFHAID is IBM-supplied and "
                + "absent from this repository, so CicsAid is the single reproduction of it and the "
                + "only place a mnemonic can come from.");
    }

    private static ObservedResponse observed(AccountViewResponse painted) {
        String nextProgram = tokenOrAbsent(painted.getNextProgram());
        String nextMapset = tokenOrAbsent(painted.getNextMapset());
        String nextMap = tokenOrAbsent(painted.getNextMap());

        List<ObservedSend> sends = new ArrayList<>(1);
        if (nextMap != null) {
            sends.add(new ObservedSend(painted.fieldImages(), attributeMnemonics(painted)));
        }

        String cursorField = sends.isEmpty() ? null : CURSOR_ITEM;
        Termination termination;
        if (nextProgram != null && sends.isEmpty()) {
            termination = Termination.XCTL;
        } else if (sentPlainText(painted)) {
            termination = Termination.RETURN_NO_TRANSID;
        } else {
            termination = Termination.RETURN_TRANSID;
        }

        return new ObservedResponse(nextProgram, nextMapset, nextMap, navigation(painted), sends,
                cursorField, termination);
    }

    private static Map<String, String> navigation(AccountViewResponse painted) {
        return NavigationImage.of(painted.getNavigationContext());
    }

    private static Map<String, String> attributeMnemonics(AccountViewResponse painted) {
        Map<String, String> items = new LinkedHashMap<>();
        for (AccountViewResponse.ScreenField field : AccountViewResponse.ScreenField.values()) {
            items.put(field.colourItemName(),
                    colourMnemonic(painted.attributes(field).getColour()));
        }
        for (AccountViewResponse.ScreenField field : AccountViewResponse.ScreenField.values()) {
            items.put(field.label() + HIGHLIGHT_ITEM_SUFFIX,
                    highlightMnemonic(painted.attributes(field).getHilight()));
        }
        return items;
    }

    private static String colourMnemonic(byte colour) {
        String mnemonic = BmsAttributes.COLOUR_MNEMONICS.get(colour);
        if (mnemonic == null) {
            throw new IllegalStateException(String.format(
                    "COACTVWC moved 0x%02X into an extended-colour item, which is not one of the "
                            + "DFHBMSCA colours. A colour a case cannot name is a colour nobody can "
                            + "assert, so this is reported rather than rendered as a raw byte.",
                    Byte.toUnsignedInt(colour)));
        }
        return mnemonic;
    }

    private static String highlightMnemonic(byte highlight) {
        String mnemonic = BmsAttributes.HIGHLIGHT_MNEMONICS.get(highlight);
        if (mnemonic == null) {
            throw new IllegalStateException(String.format(
                    "COACTVWC left 0x%02X in an extended-highlight item, which is not one of the "
                            + "DFHBMSCA highlights. The program writes no highlight at all, so anything "
                            + "other than DFHDFHI is itself the finding.",
                    Byte.toUnsignedInt(highlight)));
        }
        return mnemonic;
    }

    private static String tokenOrAbsent(String token) {
        return token == null || token.isBlank() ? null : token.trim();
    }

    private static void emitPlainText(AccountViewResponse painted, UnitOutcome.Builder recorder) {
        if (!sentPlainText(painted)) {
            return;
        }
        recorder.message(new EmittedMessage(MessageChannel.DISPLAY_LINE,
                painted.getErrmsg().substring(0, CardScreenState.CCARD_RETURN_MSG_LENGTH)));
    }

    private static boolean sentPlainText(AccountViewResponse painted) {
        boolean sentMap = tokenOrAbsent(painted.getNextMap()) != null;
        boolean transferred = tokenOrAbsent(painted.getNextProgram()) != null;
        boolean errorLineUntouched = painted.getErrmsg().equals(
                CardScreenState.lowValues(AccountViewResponse.ERRMSG_LENGTH));
        return !sentMap && !transferred && !errorLineUntouched;
    }

    private static final int THIS_PROG_COMMAREA_LENGTH = 12;

    private static final int PASSED_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + THIS_PROG_COMMAREA_LENGTH;

    private static final String CROSS_REFERENCED_ACCOUNT = "00000000050";

    private static final String CROSS_REFERENCED_CUSTOMER = "000000050";

    private static final int SUCCESS_PATH_CASE = 3;

    private static ParityCase caseNumbered(int ordinal) {
        return cases().get(ordinal - 1);
    }

    private static Map<String, SeededDataset> seededFor(int ordinal) {
        return ParityHarness.usAscii().seed(caseNumbered(ordinal));
    }

    private static Clock pinnedClock() {
        return ParityHarness.fixedClockAt(ParityHarness.DEFAULT_PINNED_CLOCK);
    }

    private static NavigationContext reenteringFromCardList() {
        return NavigationContext.empty()
                .withFromTranid("CCLI")
                .withFromProgram("COCRDLIC")
                .withUserId("ADMIN001")
                .withUserTypeAdmin()
                .withPgmReenter()
                .withLastMap("CCRDLIA")
                .withLastMapset("COCRDLI");
    }

    private static AccountViewResponse interact(Map<String, SeededDataset> seeded, String forcedSite,
            NavigationContext context, String acctsid, byte aid) {
        Clock clock = pinnedClock();
        Map<String, String> mapFields = Map.of(
                AccountViewRequest.ScreenField.ACCTSID.symbolicItemName(), acctsid);
        Map<String, String> commarea =
                context == null ? Map.of() : NavigationImage.of(context);
        int eibcalen = context == null ? NO_COMMAREA_LENGTH : PASSED_COMMAREA_LENGTH;
        return paint("direct", seeded, forcedSite, mapFields, commarea, eibcalen,
                AID_MNEMONICS.get(aid), clock);
    }

    /**
     * The pairing between {@code app/cpy/COCOM01Y.cpy}'s sixteen fields and {@link NavigationContext}'s
     * components.
     */
    private static final class NavigationImage {
        private NavigationImage() {
            throw new AssertionError("NavigationImage is a holder for two conversions");
        }

        private static Map<String, String> of(NavigationContext context) {
            Map<String, String> image = new LinkedHashMap<>();
            image.put(NavigationContext.FROM_TRANID_FIELD, context.fromTranid());
            image.put(NavigationContext.FROM_PROGRAM_FIELD, context.fromProgram());
            image.put(NavigationContext.TO_TRANID_FIELD, context.toTranid());
            image.put(NavigationContext.TO_PROGRAM_FIELD, context.toProgram());
            image.put(NavigationContext.USER_ID_FIELD, context.userId());
            image.put(NavigationContext.USER_TYPE_FIELD, context.userType());
            image.put(NavigationContext.PGM_CONTEXT_FIELD,
                    digits(context.pgmContext(), NavigationContext.PGM_CONTEXT_LENGTH));
            image.put(NavigationContext.CUST_ID_FIELD,
                    digits(context.custId(), NavigationContext.CUST_ID_LENGTH));
            image.put(NavigationContext.CUST_FNAME_FIELD, context.custFname());
            image.put(NavigationContext.CUST_MNAME_FIELD, context.custMname());
            image.put(NavigationContext.CUST_LNAME_FIELD, context.custLname());
            image.put(NavigationContext.ACCT_ID_FIELD,
                    digits(context.acctId(), NavigationContext.ACCT_ID_LENGTH));
            image.put(NavigationContext.ACCT_STATUS_FIELD, context.acctStatus());
            image.put(NavigationContext.CARD_NUM_FIELD,
                    digits(context.cardNum(), NavigationContext.CARD_NUM_LENGTH));
            image.put(NavigationContext.LAST_MAP_FIELD, context.lastMap());
            image.put(NavigationContext.LAST_MAPSET_FIELD, context.lastMapset());
            return image;
        }

        private static NavigationContext from(Map<String, String> declared) {
            NavigationContext empty = NavigationContext.empty();
            return new NavigationContext(
                    text(declared, NavigationContext.FROM_TRANID_FIELD, empty.fromTranid()),
                    text(declared, NavigationContext.FROM_PROGRAM_FIELD, empty.fromProgram()),
                    text(declared, NavigationContext.TO_TRANID_FIELD, empty.toTranid()),
                    text(declared, NavigationContext.TO_PROGRAM_FIELD, empty.toProgram()),
                    text(declared, NavigationContext.USER_ID_FIELD, empty.userId()),
                    text(declared, NavigationContext.USER_TYPE_FIELD, empty.userType()),
                    (int) number(declared, NavigationContext.PGM_CONTEXT_FIELD, empty.pgmContext()),
                    (int) number(declared, NavigationContext.CUST_ID_FIELD, empty.custId()),
                    text(declared, NavigationContext.CUST_FNAME_FIELD, empty.custFname()),
                    text(declared, NavigationContext.CUST_MNAME_FIELD, empty.custMname()),
                    text(declared, NavigationContext.CUST_LNAME_FIELD, empty.custLname()),
                    number(declared, NavigationContext.ACCT_ID_FIELD, empty.acctId()),
                    text(declared, NavigationContext.ACCT_STATUS_FIELD, empty.acctStatus()),
                    number(declared, NavigationContext.CARD_NUM_FIELD, empty.cardNum()),
                    text(declared, NavigationContext.LAST_MAP_FIELD, empty.lastMap()),
                    text(declared, NavigationContext.LAST_MAPSET_FIELD, empty.lastMapset()));
        }

        private static String text(Map<String, String> declared, String field, String initialised) {
            String stated = declared.get(field);
            return stated == null ? initialised : stated;
        }

        private static long number(Map<String, String> declared, String field, long initialised) {
            String stated = declared.get(field);
            if (stated == null || stated.isBlank()) {
                return initialised;
            }
            return Long.parseLong(stated.trim());
        }

        private static String digits(long value, int width) {
            return new FixedWidthCodec(FIXTURE_CHARSET).movePic9(value, width);
        }
    }

    @Nested
    @DisplayName("COACTVWC: the twenty case files")
    class TheCaseSet {
        @Test
        @DisplayName("are exactly twenty, in order, and every one is a CONTROLLER_POJO case")
        void areExactlyTwentyOrderedOnlineCases() {
            List<ParityCase> loaded = cases();
            assertThat(loaded).hasSize(REQUIRED_CASES);
            for (int ordinal = 1; ordinal <= REQUIRED_CASES; ordinal++) {
                ParityCase parityCase = loaded.get(ordinal - 1);
                assertThat(parityCase.caseId())
                        .describedAs("case %d is the file whose stem is case%02d", ordinal, ordinal)
                        .isEqualTo(ParityHarness.caseId(ordinal));
                assertThat(parityCase.program()).isEqualTo(PROGRAM);
                assertThat(parityCase.unitKind()).isEqualTo(UnitKind.CONTROLLER_POJO);
                assertThat(parityCase.expectedReturnCode()).isZero();
                assertThat(parityCase.expectedWrites())
                        .describedAs("COACTVWC issues no WRITE, REWRITE or DELETE, so no case may "
                                + "expect one")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("each seed all three access paths and expect every row unchanged")
        void eachSeedAllThreeAccessPathsAndExpectThemUnchanged() {
            for (ParityCase parityCase : cases()) {
                assertThat(parityCase.inputs().keySet())
                        .describedAs("%s seeds the three files COACTVWC reads", parityCase.caseId())
                        .containsExactlyInAnyOrder(CCXREF, ACCTDAT, CUSTDAT);
                Map<String, SeededDataset> seeded = ParityHarness.usAscii().seed(parityCase);
                int seededRows = seeded.values().stream().mapToInt(SeededDataset::rowCount).sum();
                assertThat(parityCase.expectedFinalState())
                        .describedAs("%s pins every seeded row on the final-state channel",
                                parityCase.caseId())
                        .hasSize(seededRows);
            }
        }

        @Test
        @DisplayName("normalise cardxref from 36 bytes to the 50 CVACT03Y declares")
        void normaliseTheCrossReferenceFixtureToItsDeclaredWidth() {
            for (ParityCase parityCase : cases()) {
                SeededDataset crossReference = ParityHarness.usAscii().seed(parityCase).get(CCXREF);
                if (crossReference.isEmpty()) {
                    assertThat(crossReference.recordLength()).isEqualTo(CardXrefRecord.RECORD_LENGTH);
                    continue;
                }
                assertThat(parityCase.normalisations())
                        .describedAs("%s seeds real cardxref rows, so it must declare the pad",
                                parityCase.caseId())
                        .isNotEmpty();
                for (String row : crossReference.rows()) {
                    assertThat(row).hasSize(CardXrefRecord.RECORD_LENGTH);
                }
            }
        }

        @Test
        @DisplayName("each cite the COBOL paragraph or line they drive")
        void eachCiteTheSourceTheyWereDerivedFrom() {
            for (ParityCase parityCase : cases()) {
                assertThat(parityCase.description())
                        .describedAs("%s must explain itself", parityCase.caseId())
                        .isNotBlank()
                        .contains(":");
            }
        }
    }

    @Nested
    @DisplayName("COACTVWC: the COACTVW screen contract")
    class TheScreenContract {
        @Test
        @DisplayName("project 37 fields whose widths are the symbolic map's xxxI items")
        void projectThirtySevenFieldsAtTheirDeclaredWidths() {
            Map<String, Integer> declared = new LinkedHashMap<>();
            declared.put("TRNNAME", 4);
            declared.put("TITLE01", 40);
            declared.put("CURDATE", 8);
            declared.put("PGMNAME", 8);
            declared.put("TITLE02", 40);
            declared.put("CURTIME", 8);
            declared.put("ACCTSID", 11);
            declared.put("ACSTTUS", 1);
            declared.put("ADTOPEN", 10);
            declared.put("ACRDLIM", 15);
            declared.put("AEXPDT", 10);
            declared.put("ACSHLIM", 15);
            declared.put("AREISDT", 10);
            declared.put("ACURBAL", 15);
            declared.put("ACRCYCR", 15);
            declared.put("AADDGRP", 10);
            declared.put("ACRCYDB", 15);
            declared.put("ACSTNUM", 9);
            declared.put("ACSTSSN", 12);
            declared.put("ACSTDOB", 10);
            declared.put("ACSTFCO", 3);
            declared.put("ACSFNAM", 25);
            declared.put("ACSMNAM", 25);
            declared.put("ACSLNAM", 25);
            declared.put("ACSADL1", 50);
            declared.put("ACSSTTE", 2);
            declared.put("ACSADL2", 50);
            declared.put("ACSZIPC", 5);
            declared.put("ACSCITY", 50);
            declared.put("ACSCTRY", 3);
            declared.put("ACSPHN1", 13);
            declared.put("ACSGOVT", 20);
            declared.put("ACSPHN2", 13);
            declared.put("ACSEFTC", 10);
            declared.put("ACSPFLG", 1);
            declared.put("INFOMSG", 45);
            declared.put("ERRMSG", 78);

            assertThat(declared).hasSize(AccountViewResponse.FIELD_COUNT);
            assertThat(AccountViewResponse.ScreenField.values())
                    .hasSize(AccountViewResponse.FIELD_COUNT);
            for (AccountViewResponse.ScreenField field : AccountViewResponse.ScreenField.values()) {
                assertThat(field.length())
                        .describedAs("%s is %sI in app/cpy-bms/COACTVW.CPY", field.label(),
                                field.label())
                        .isEqualTo(declared.get(field.label()));
                assertThat(field.symbolicItemName())
                        .isEqualTo(field.label() + FieldAttributeSetter.OUTPUT_ITEM_SUFFIX);
                assertThat(field.colourItemName())
                        .isEqualTo(field.label() + FieldAttributeSetter.COLOUR_ITEM_SUFFIX);
            }
        }

        @Test
        @DisplayName("keep xxxL, xxxF and xxxA as metadata and never as payload")
        void keepLengthFlagAndAttributeItemsAsMetadata() {
            AccountViewRequest request = new AccountViewRequest();
            request.initializeMapArea();
            assertThat(request.metadata()).hasSize(AccountViewResponse.FIELD_COUNT);
            for (AccountViewRequest.ScreenField field : AccountViewRequest.ScreenField.values()) {
                AccountViewRequest.ScreenFieldMetadata metadata = request.metadata(field);
                assertThat(metadata.isLengthUnset())
                        .describedAs("%sL starts unset, because no MOVE has reached it", field.label())
                        .isTrue();
                assertThat(metadata.isCursorHere()).isFalse();
                assertThat(metadata.isAttributeUnset()).isTrue();
                assertThat(request.value(field)).hasSize(field.length());
            }
        }

        @Test
        @DisplayName("never write the programmed-symbol or validation attribute planes")
        void theProgrammedSymbolAndValidationPlanesAreNeverWritten() {
            for (ParityCase parityCase : cases()) {
                AccountViewResponse painted = paintFrom(parityCase);
                for (AccountViewResponse.ScreenField field
                        : AccountViewResponse.ScreenField.values()) {
                    AccountViewResponse.FieldAttributes quad = painted.attributes(field);
                    assertThat(quad.getPs())
                            .describedAs("%s/%s wrote %sP", PROGRAM, parityCase.caseId(),
                                    field.label())
                            .isEqualTo(AccountViewResponse.FieldAttributes.UNSET);
                    assertThat(quad.getValidn())
                            .describedAs("%s/%s wrote %sV", PROGRAM, parityCase.caseId(),
                                    field.label())
                            .isEqualTo(AccountViewResponse.FieldAttributes.UNSET);
                }
            }
        }
    }

    @Nested
    @DisplayName("COACTVWC: the five named files and the three it reads")
    class TheDatasetInventory {
        @Test
        @DisplayName("name five files, read three, and leave CARDDAT and CARDAIX unread")
        void nameFiveFilesAndReadThree() {
            assertThat(AccountRepository.CICS_FILE_NAME).isEqualTo(ACCTDAT);
            assertThat(CustomerRepository.CICS_FILE_NAME).isEqualTo(CUSTDAT);
            assertThat(CardXrefRepository.BASE_DD_NAME).isEqualTo(CCXREF);
            assertThat(CardXrefRepository.ALTERNATE_INDEX_DD_NAME).isEqualTo("CXACAIX");

            assertThat(CardRepository.BASE_CICS_FILE_NAME).isEqualTo("CARDDAT ");
            assertThat(CardRepository.ALTERNATE_INDEX_CICS_FILE_NAME).isEqualTo("CARDAIX ");
            assertThat(CardRecord.RECORD_LENGTH)
                    .describedAs("CVACT02Y is 150 bytes; COACTVWC never reads one, which is precisely "
                            + "why no CardRepository is injected")
                    .isEqualTo(150);
        }

        @Test
        @DisplayName("answer both the base key and the CXACAIX path from one set of rows")
        void theAlternateIndexIsASecondFinderOnTheSameRepository() {
            SeededDataset crossReference = seededFor(SUCCESS_PATH_CASE).get(CCXREF);
            CardXrefRepository repository = seededCardXrefRepository(crossReference, null);

            CardXrefRepository.ReadResult viaPath =
                    repository.readByAccountIdViaAltIndex(CROSS_REFERENCED_ACCOUNT);
            assertThat(viaPath.isFound()).isTrue();
            CardXrefRecord found = viaPath.record().orElseThrow();
            assertThat(viaPath.ddName()).isEqualTo(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);

            CardXrefRepository.ReadResult viaBaseKey =
                    repository.readByCardNumber(found.xrefCardNum());
            assertThat(viaBaseKey.isFound()).isTrue();
            assertThat(viaBaseKey.ddName()).isEqualTo(CardXrefRepository.BASE_DD_NAME);
            assertThat(viaBaseKey.record().orElseThrow())
                    .describedAs("one row, two keys - the same record either way")
                    .isEqualTo(found);

            assertThat(keyImage(found.xrefCustId(), NavigationContext.CUST_ID_LENGTH))
                    .isEqualTo(CROSS_REFERENCED_CUSTOMER);
        }

        @Test
        @DisplayName("keep every record at its declared width with FILLER present")
        void theRecordWidthsIncludeTheirFiller() {
            Map<String, SeededDataset> seeded = seededFor(SUCCESS_PATH_CASE);

            String accountRow = seeded.get(ACCTDAT).row(0);
            assertThat(accountRow).hasSize(AccountRecord.RECORD_LENGTH);
            AccountRecord account = AccountRecord.decode(accountRow, FIXTURE_CHARSET);
            assertThat(account.getFiller()).hasSize(AccountRecord.FILLER_LENGTH);
            assertThat(account.toFixedWidthString())
                    .describedAs("300 bytes in, 300 bytes out, byte for byte")
                    .isEqualTo(accountRow);
            assertThat(account.getAcctExpiraionDate())
                    .describedAs("CVACT01Y misspells EXPIRATION and the misspelling is preserved, "
                            + "because a corrected field name would stop matching the copybook the "
                            + "differ keys on")
                    .isEqualTo("2023-03-09");
            assertThat(AccountRecord.ACCT_EXPIRAION_DATE_NAME).isEqualTo("ACCT-EXPIRAION-DATE");

            String customerRow = seeded.get(CUSTDAT).row(0);
            assertThat(customerRow).hasSize(CustomerRecord.RECORD_LENGTH);
            CustomerRecord customer = CustomerRecord.decode(customerRow, FIXTURE_CHARSET);
            assertThat(customer.recordImage(FIXTURE_CHARSET)).isEqualTo(customerRow);
            assertThat(customer.getCustDobYyyyMmDd())
                    .describedAs("CVCUS01Y spells it CUST-DOB-YYYY-MM-DD, which CUSTREC spells "
                            + "CUST-DOB-YYYYMMDD - the reason the two records stay separate types")
                    .isEqualTo("1960-12-01");

            String crossReferenceRow = seeded.get(CCXREF).row(0);
            assertThat(crossReferenceRow).hasSize(CardXrefRecord.RECORD_LENGTH);
            CardXrefRecord xref = CardXrefRecord.decode(
                    crossReferenceRow.getBytes(FIXTURE_CHARSET), FIXTURE_CHARSET);
            assertThat(new String(xref.encode(FIXTURE_CHARSET), FIXTURE_CHARSET))
                    .isEqualTo(crossReferenceRow);
        }
    }

    @Nested
    @DisplayName("COACTVWC: the pseudo-conversational turn")
    class TheConversation {
        @Test
        @DisplayName("carry every scrap of conversation state in the payload and none on the server")
        void theConversationKeepsNoServerSideState() {
            Map<String, SeededDataset> seeded = seededFor(SUCCESS_PATH_CASE);

            AccountViewResponse first = interact(seeded, null, reenteringFromCardList(),
                    CROSS_REFERENCED_ACCOUNT, CicsAid.DFHENTER);
            AccountViewResponse blank = interact(seeded, null, reenteringFromCardList(),
                    FieldAttributeSetter.ASTERISK
                            + AccountViewRequest.spaces(AccountViewResponse.ACCTSID_LENGTH - 1),
                    CicsAid.DFHENTER);
            AccountViewResponse third = interact(seeded, null, reenteringFromCardList(),
                    CROSS_REFERENCED_ACCOUNT, CicsAid.DFHENTER);

            assertThat(third.fieldImages())
                    .describedAs("the third turn repeats the first exactly, so nothing carried over")
                    .isEqualTo(first.fieldImages());
            assertThat(third.getNavigationContext()).isEqualTo(first.getNavigationContext());
            assertThat(blank.fieldImages())
                    .describedAs("the rejected turn shows no account, so it saw nothing of the first")
                    .isNotEqualTo(first.fieldImages());
            assertThat(blank.getAcstnum())
                    .describedAs("a rejected filter reads no file, so the customer number is untouched "
                            + "LOW-VALUES rather than the previous turn's value")
                    .isEqualTo(CardScreenState.lowValues(AccountViewResponse.ACSTNUM_LENGTH));
        }

        @Test
        @DisplayName("mark a blank filter with an asterisk and DFHRED only on re-entry")
        void theBlankFieldHighlightAppliesOnlyOnReentry() {
            Map<String, SeededDataset> seeded = seededFor(SUCCESS_PATH_CASE);
            String blankFilter = AccountViewRequest.spaces(AccountViewResponse.ACCTSID_LENGTH);

            AccountViewResponse onEntry = interact(seeded, null,
                    NavigationContext.empty().withFromTranid("CCLI").withFromProgram("COCRDLIC"),
                    blankFilter, CicsAid.DFHENTER);
            assertThat(onEntry.getAcctsid())
                    .describedAs("first entry paints LOW-VALUES, not a marker")
                    .isEqualTo(CardScreenState.lowValues(AccountViewResponse.ACCTSID_LENGTH));
            assertThat(onEntry.attributes(AccountViewResponse.ScreenField.ACCTSID).getColour())
                    .isEqualTo(BmsAttributes.DFHDFCOL);

            AccountViewResponse onReentry = interact(seeded, null, reenteringFromCardList(),
                    blankFilter, CicsAid.DFHENTER);
            assertThat(onReentry.getAcctsid())
                    .describedAs("re-entry marks the offending field with CSSETATY's asterisk")
                    .isEqualTo(new FixedWidthCodec(FIXTURE_CHARSET).movePicX(
                            FieldAttributeSetter.ASTERISK, AccountViewResponse.ACCTSID_LENGTH));
            assertThat(onReentry.attributes(AccountViewResponse.ScreenField.ACCTSID).getColour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(onReentry.attributes(AccountViewResponse.ScreenField.ACCTSID)
                    .isRedHighlighted()).isTrue();
        }

        @ParameterizedTest(name = "{0} behaves as DFHENTER")
        @ValueSource(strings = {"DFHCLEAR", "DFHPA1", "DFHPA2", "DFHPA3", "DFHPF1", "DFHPF2",
                "DFHPF4", "DFHPF7", "DFHPF12", "DFHPF13", "DFHPF14", "DFHPF24"})
        @DisplayName("rewrite every key but ENTER, PF3 and PF15 to ENTER")
        void everyKeyButEnterAndPf3IsRewrittenToEnter(String mnemonic) {
            Map<String, SeededDataset> seeded = seededFor(SUCCESS_PATH_CASE);
            AccountViewResponse onEnter = interact(seeded, null, reenteringFromCardList(),
                    CROSS_REFERENCED_ACCOUNT, CicsAid.DFHENTER);
            AccountViewResponse onOtherKey = interact(seeded, null, reenteringFromCardList(),
                    CROSS_REFERENCED_ACCOUNT, aidByteOf("direct", mnemonic));

            assertThat(onOtherKey.fieldImages())
                    .describedAs("%s is neither ENTER nor PF3, so :312-314 rewrites it to ENTER",
                            mnemonic)
                    .isEqualTo(onEnter.fieldImages());
            assertThat(onOtherKey.getNextMap()).isEqualTo(AccountViewResponse.MAP_NAME);
        }

        @Test
        @DisplayName("fold PF13-PF24 onto PFK01-PFK12 and report PA3 as absent")
        void csstrpfyFoldsTheSecondTwelveFunctionKeysOntoTheFirst() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER))
                    .contains(PfKeyResolver.AidKey.ENTER);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHCLEAR))
                    .contains(PfKeyResolver.AidKey.CLEAR);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF3))
                    .contains(PfKeyResolver.AidKey.PFK03);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF12))
                    .contains(PfKeyResolver.AidKey.PFK12);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF13))
                    .describedAs("app/cpy/CSSTRPFY.cpy:54-55 folds PF13 onto PFK01")
                    .contains(PfKeyResolver.AidKey.PFK01);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15))
                    .describedAs("app/cpy/CSSTRPFY.cpy:58-59 folds PF15 onto PFK03, which is why PF15 "
                            + "transfers off this screen")
                    .contains(PfKeyResolver.AidKey.PFK03);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF24))
                    .describedAs("app/cpy/CSSTRPFY.cpy:76-77 folds PF24 onto PFK12")
                    .contains(PfKeyResolver.AidKey.PFK12);

            Optional<PfKeyResolver.AidKey> outsideTheTable = PfKeyResolver.resolve(CicsAid.DFHPA3);
            assertThat(outsideTheTable)
                    .describedAs("CSSTRPFY has no arm for PA3, though DFHAID publishes it, and no "
                            + "WHEN OTHER to fall into")
                    .isEmpty();
            assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPA3,
                    Optional.of(PfKeyResolver.AidKey.PFK03)))
                    .describedAs("an unrecognised key leaves CCARD-AID holding what it already held")
                    .contains(PfKeyResolver.AidKey.PFK03);
        }

        @Test
        @DisplayName("treat PF15 exactly as PF3, because CSSTRPFY folds it onto PFK03")
        void pf15TransfersBecauseTheCopybookFoldsItOntoPfk03() {
            Map<String, SeededDataset> seeded = seededFor(SUCCESS_PATH_CASE);
            AccountViewResponse onPf3 = interact(seeded, null, reenteringFromCardList(),
                    CROSS_REFERENCED_ACCOUNT, CicsAid.DFHPF3);
            AccountViewResponse onPf15 = interact(seeded, null, reenteringFromCardList(),
                    CROSS_REFERENCED_ACCOUNT, CicsAid.DFHPF15);

            assertThat(onPf15.getNextProgram()).isEqualTo(onPf3.getNextProgram());
            assertThat(onPf15.getNavigationContext()).isEqualTo(onPf3.getNavigationContext());
            assertThat(onPf15.fieldImages()).isEqualTo(onPf3.fieldImages());
            assertThat(onPf15.getNextMap())
                    .describedAs("a transfer paints no map")
                    .isBlank();
        }

        @Test
        @DisplayName("transfer by naming the next program, painting no map at all")
        void theTransferNamesItsTargetAndPaintsNothing() {
            Map<String, SeededDataset> seeded = seededFor(SUCCESS_PATH_CASE);

            AccountViewResponse toCaller = interact(seeded, null, reenteringFromCardList(),
                    CROSS_REFERENCED_ACCOUNT, CicsAid.DFHPF3);
            assertThat(toCaller.getNextProgram().trim()).isEqualTo("COCRDLIC");
            assertThat(toCaller.getNavigationContext().toTranid()).isEqualTo("CCLI");
            assertThat(toCaller.getNextMapset())
                    .describedAs("XCTL never reaches 1400-SEND-SCREEN, so no mapset is named")
                    .isBlank();
            assertThat(toCaller.getNextMap()).isBlank();
            assertThat(toCaller.getAcctsid())
                    .describedAs("no map is painted, so all 37 items stay as MOVE LOW-VALUES left them")
                    .isEqualTo(CardScreenState.lowValues(AccountViewResponse.ACCTSID_LENGTH));
            assertThat(toCaller.getNavigationContext().userType())
                    .describedAs("SET CDEMO-USRTYP-USER at :344 is unconditional, so an administrator "
                            + "leaves this screen recorded as a plain user")
                    .isEqualTo(NavigationContext.USER_TYPE_USER);
            assertThat(toCaller.getNavigationContext().isEnter())
                    .describedAs("the target is entered, not re-entered")
                    .isTrue();
            assertThat(toCaller.getNavigationContext().lastMapset())
                    .describedAs("LIT-THISMAPSET is PIC X(8) and CDEMO-LAST-MAPSET is PIC X(7), so the "
                            + "move at :346 discards the literal's trailing space")
                    .isEqualTo(AccountViewResponse.THIS_MAPSET.trim());

            AccountViewResponse toMenu = interact(seeded, null,
                    reenteringFromCardList()
                            .withFromTranid(AccountViewRequest.spaces(
                                    NavigationContext.FROM_TRANID_LENGTH))
                            .withFromProgram(AccountViewRequest.spaces(
                                    NavigationContext.FROM_PROGRAM_LENGTH)),
                    CROSS_REFERENCED_ACCOUNT, CicsAid.DFHPF3);
            assertThat(toMenu.getNextProgram().trim())
                    .describedAs("blank from-fields fall back to the main menu, not to nowhere")
                    .isEqualTo("COMEN01C");
            assertThat(toMenu.getNavigationContext().toTranid()).isEqualTo("CM00");
        }

        @Test
        @DisplayName("drive NORMAL, NOTFND and OTHER at each of the three read sites")
        void theThreeReadSitesEachDriveNormalNotFoundAndOther() {
            assertThat(paintFrom(caseNumbered(3)).getErrmsg())
                    .describedAs("three successful reads compose no message")
                    .isBlank();

            Map<String, SeededDataset> withoutTheAccount =
                    new LinkedHashMap<>(seededFor(SUCCESS_PATH_CASE));
            List<String> remaining = withoutTheAccount.get(CCXREF).rows().stream()
                    .filter(row -> !CROSS_REFERENCED_ACCOUNT.equals(row.substring(
                            CardXrefRecord.XREF_ACCT_ID_OFFSET,
                            CardXrefRecord.XREF_ACCT_ID_OFFSET + CardXrefRecord.XREF_ACCT_ID_LENGTH)))
                    .toList();
            assertThat(remaining)
                    .describedAs("the cross reference must still hold rows, just not this account")
                    .isNotEmpty()
                    .hasSizeLessThan(withoutTheAccount.get(CCXREF).rowCount());
            withoutTheAccount.put(CCXREF, SeededDataset.of(CCXREF, CardXrefRecord.RECORD_LENGTH,
                    remaining, FIXTURE_CHARSET));
            assertThat(interact(withoutTheAccount, null, reenteringFromCardList(),
                    CROSS_REFERENCED_ACCOUNT, CicsAid.DFHENTER).getErrmsg())
                    .describedAs("9200 WHEN DFHRESP(NOTFND) at :741-758")
                    .startsWith("Account:" + CROSS_REFERENCED_ACCOUNT + " not found in Cross ref file.");
            assertThat(paintFrom(caseNumbered(10)).getErrmsg())
                    .describedAs("9300 WHEN DFHRESP(NOTFND) at :789-807")
                    .startsWith("Account:" + CROSS_REFERENCED_ACCOUNT
                            + " not found in Acct Master file.");
            assertThat(paintFrom(caseNumbered(12)).getErrmsg())
                    .describedAs("9400 WHEN DFHRESP(NOTFND) at :839-857 - CustId, not Account")
                    .startsWith("CustId:" + CROSS_REFERENCED_CUSTOMER + " not found in customer master.");

            assertThat(paintFrom(caseNumbered(9)).getErrmsg())
                    .describedAs("9200 WHEN OTHER at :759-766, with a reported RESP")
                    .startsWith("File Error: READ     on CXACAIX   returned RESP 0000000");
            assertThat(paintFrom(caseNumbered(11)).getErrmsg())
                    .describedAs("9300 WHEN OTHER at :809-816, with no RESP to report")
                    .startsWith("File Error: READ     on ACCTDAT   returned RESP *********");
            assertThat(paintFrom(caseNumbered(13)).getErrmsg())
                    .describedAs("9400 WHEN OTHER at :858-865, with no RESP to report")
                    .startsWith("File Error: READ     on CUSTDAT   returned RESP *********");

            assertThat(paintFrom(caseNumbered(12))
                    .attributes(AccountViewResponse.ScreenField.ACCTSID).getColour())
                    .describedAs("a customer failure sets FLG-CUSTFILTER-NOT-OK at :841, not the "
                            + "account flag, so the account number is not coloured red")
                    .isEqualTo(BmsAttributes.DFHDFCOL);
        }

        @Test
        @DisplayName("hold money as BigDecimal at scale two and truncate rather than round")
        void theMoneyFieldsAreScaleTwoAndTruncated() {
            assertThat(CobolDecimal.COBOL_ROUNDING)
                    .describedAs("ROUNDED appears zero times in the 28 programs, so a store truncates")
                    .isEqualTo(RoundingMode.DOWN);
            assertThat(CobolDecimal.MONETARY_SCALE).isEqualTo(2);
            assertThat(CobolDecimal.store(new BigDecimal("492.999"), CobolDecimal.MONETARY_SCALE))
                    .describedAs("truncation, not rounding: .999 stores as .99 and never as 493.00")
                    .isEqualByComparingTo(new BigDecimal("492.99"));

            String accountRow = seededFor(SUCCESS_PATH_CASE).get(ACCTDAT).row(0);
            AccountRecord account = AccountRecord.decode(accountRow, FIXTURE_CHARSET);
            BigDecimal balance = account.getAcctCurrBal();
            assertThat(balance.scale()).isEqualTo(CobolDecimal.MONETARY_SCALE);
            assertThat(balance).isEqualByComparingTo(new BigDecimal("492.00"));

            String storedImage = accountRow.substring(AccountRecord.ACCT_CURR_BAL_OFFSET,
                    AccountRecord.ACCT_CURR_BAL_OFFSET + AccountRecord.ACCT_CURR_BAL_LENGTH);
            assertThat(new FixedWidthCodec(FIXTURE_CHARSET)
                    .decodeSignedScaled(storedImage, CobolDecimal.MONETARY_SCALE))
                    .describedAs("the trailing overpunch of %s is a digit and a sign in one byte",
                            storedImage)
                    .isEqualByComparingTo(balance);

            assertThat(AccountViewResponse.editAmount(balance))
                    .describedAs("PIC %s at LENGTH=%d", AccountViewResponse.AMOUNT_PICTURE,
                            AccountViewResponse.ACURBAL_LENGTH)
                    .isEqualTo("+        492.00")
                    .hasSize(AccountViewResponse.ACURBAL_LENGTH);
            assertThat(AccountViewResponse.editAmount(CobolDecimal.monetaryZero()))
                    .describedAs("every Z suppressed, and the two decimal digits still present")
                    .isEqualTo("+           .00");
        }
    }
}
