/*
 * ******************************************************************
 * Program     : CardUpdateWriteContractTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Pins the two write-path contracts of COCRDUPC's
 *               9200-WRITE-PROCESSING that no other test asserts
 *               directly. First, that no card verification value is
 *               retained anywhere - the two MOVEs at :1464-1465 wrote
 *               the never-assigned CCUP-NEW-CVV-CD onto the record and
 *               destroyed the stored value on every update, and this
 *               system stores no such value to destroy, so the absence
 *               is asserted structurally across the schema, the entity,
 *               the request group and the sealed snapshot. Second, that
 *               the row is read through the account-scoped pessimistic
 *               finder, so lock acquisition happens at the read of
 *               :1427-1436 and a card the requesting account does not
 *               own is never locked or rewritten.
 * Source      : app/cbl/COCRDUPC.cbl:306       (CCUP-NEW-CVV-CD X(3))
 *               app/cbl/COCRDUPC.cbl:586       (INITIALIZE -> SPACES)
 *               app/cbl/COCRDUPC.cbl:1424      (the account half of the key)
 *               app/cbl/COCRDUPC.cbl:1427-1436 (READ ... UPDATE)
 *               app/cbl/COCRDUPC.cbl:1464-1465 (the two MOVEs, absent here)
 *               app/cbl/COCRDUPC.cbl:1478      (the REWRITE)
 *               app/cbl/COCRDUPC.cbl:1503      (9300 clause 1, inert)
 *               V1__create_schema.sql          (card_cvv_cd - declared, unreadable)
 *               AAP 0.3.2 / 0.8.4              (least privilege) @ 7756d89
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.model.dto.CardUpdateRequest;
import com.cardemo.model.entity.Card;
import com.cardemo.repository.CardRepository;
import com.cardemo.service.card.CardUpdateService;
import jakarta.persistence.LockModeType;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.jpa.repository.Lock;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("CardUpdateService: 9200-WRITE-PROCESSING - the stored CVV is left untouched, and an account-scoped lock")
final class CardUpdateWriteContractTest {

    /** Frozen instant; no decision under test reads the clock. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-04-15T14:30:05Z");

    /** The save key {@code 2000-DECIDE-ACTION} gates the write on, {@code PF05}. */
    private static final String AID_PF05 = "DFHPF5";

    /** Account filter, {@code ACCTSIDI PIC X(11)}. */
    private static final String ACCOUNT_ID = "00000000001";

    /** Card filter, {@code CARDSIDI PIC X(16)}. Synthetic test data, never a credential. */
    private static final String CARD_NUMBER = "0000000000000011";

    /** Stored embossed name, {@code CARD-EMBOSSED-NAME PIC X(50)}. */
    private static final String STORED_NAME = "JOHN SMITH";

    /** A submitted name differing from the stored one, so the group test sees a change. */
    private static final String SUBMITTED_NAME = "JANE SMITH";

    /** Stored expiry date, {@code CARD-EXPIRAION-DATE PIC X(10)} (misspelling intentional). */
    private static final String STORED_EXPIRY = "2026-05-17";

    /** Component of {@link #STORED_EXPIRY} at offset {@code (1:4)}. */
    private static final String STORED_YEAR = "2026";

    /** Component of {@link #STORED_EXPIRY} at offset {@code (6:2)}. */
    private static final String STORED_MONTH = "05";

    /** Component of {@link #STORED_EXPIRY} at offset {@code (9:2)}. */
    private static final String STORED_DAY = "17";

    /** Stored status, {@code CARD-ACTIVE-STATUS PIC X(01)}. */
    private static final String STORED_STATUS = "Y";

    /** {@code CC-ACCT-ID-N}, the numeric view of {@link #ACCOUNT_ID} that scopes the record read. */
    private static final Long ACCOUNT_ID_NUMERIC = Long.valueOf(1L);

    /**
     * The name of the column, field and component that must exist nowhere. Matched case-insensitively as a
     * substring, so {@code cvvCode}, {@code CARD_CVV_CD} and {@code card_cvv_cd} are all caught.
     */
    private static final String VERIFICATION_VALUE_TOKEN = "cvv";

    /**
     * The three characters the stored row holds in {@code CARD-CVV-CD PIC 9(03)}. Synthetic, and chosen with
     * a leading zero because that is the shape a numeric column would corrupt.
     */
    private static final String STORED_VERIFICATION_VALUE = "007";

    /** The store collaborator, mocked because this tier reaches no database. */
    @Mock
    private CardRepository cardRepository;

    /** The system under test. */
    private CardUpdateService service;

    @BeforeEach
    void createServiceUnderTest() {
        final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        this.service = new CardUpdateService(this.cardRepository, clock);
    }

    /**
     * Builds the stored {@code CARD-RECORD} as the repository returns it under the update lock.
     *
     * @return the entity
     */
    private static Card storedCard() {
        return new Card(CARD_NUMBER, ACCOUNT_ID_NUMERIC, STORED_VERIFICATION_VALUE, STORED_NAME,
                STORED_EXPIRY, STORED_STATUS);
    }

    /**
     * Builds the snapshot the caller replays, agreeing with the stored record on every field.
     *
     * @return the snapshot
     */
    private static CardUpdateRequest.CardDetails agreeingSnapshot() {
        return snapshotOf(STORED_NAME);
    }

    /**
     * Builds a {@code CCUP-OLD-DETAILS} snapshot, mirroring {@code COCRDUPC.cbl:291-301} leaf for leaf -
     * less {@code CCUP-OLD-CVV-CD} at {@code :294}, which the group does not declare.
     *
     * @param cardholderName snapshot counterpart of {@code CARD-EMBOSSED-NAME}
     * @return a populated snapshot group
     */
    private static CardUpdateRequest.CardDetails snapshotOf(final String cardholderName) {
        return new CardUpdateRequest.CardDetails(ACCOUNT_ID, CARD_NUMBER,
                new CardUpdateRequest.CardData(cardholderName,
                        new CardUpdateRequest.ExpiraionDate(STORED_YEAR, STORED_MONTH, STORED_DAY),
                        STORED_STATUS));
    }

    /**
     * Builds a request whose submitted name differs from the stored name, so the write proceeds.
     *
     * @param snapshot the snapshot to carry
     * @return the request
     */
    private static CardUpdateRequest changedNameRequest(final CardUpdateRequest.CardDetails snapshot) {
        return new CardUpdateRequest(null, null, null, null, null, null,
                ACCOUNT_ID, CARD_NUMBER, SUBMITTED_NAME, STORED_STATUS,
                STORED_MONTH, STORED_YEAR, STORED_DAY,
                null, null, null, null, snapshot, null);
    }

    /**
     * Drives the confirmation leg, the only route that reaches {@code 9200-WRITE-PROCESSING}.
     *
     * @param request the request to submit
     */
    private void confirmSave(final CardUpdateRequest request) {
        this.service.processRequest(request, AID_PF05, CardUpdateService.EntryMode.REENTER);
    }

    /**
     * Captures the entity handed to {@code save}.
     *
     * @return the persisted entity
     */
    private Card capturePersisted() {
        final ArgumentCaptor<Card> persisted = ArgumentCaptor.forClass(Card.class);
        verify(this.cardRepository).save(persisted.capture());
        return persisted.getValue();
    }

    @Nested
    @DisplayName("F13 - the verification value is stored, never accepted or sealed, and never overwritten")
    class VerificationValueIsStoredButNeverAccepted {

        @Test
        @DisplayName("the schema declares the verification column once, on the card table, as CHAR(3)")
        void theSchemaDeclaresTheVerificationColumnOnceOnTheCardTable() throws IOException {
            final String schema;
            try (InputStream migration = CardUpdateWriteContractTest.class.getClassLoader()
                    .getResourceAsStream("db/migration/V1__create_schema.sql")) {
                assertThat(migration).as("the baseline migration must be on the test classpath").isNotNull();
                schema = new String(migration.readAllBytes(), StandardCharsets.UTF_8);
            }

            // COMMENTS ARE STRIPPED BEFORE THE SEARCH. The migration documents this column's confinement at
            // length, so a search over the raw text would count the prose as well as the declaration. What is
            // being counted is DECLARATIONS, so the assertion is made against the DDL alone.
            final List<String> declarations = schema.lines()
                    .map(line -> line.replaceFirst("--.*$", ""))
                    .filter(line -> line.toLowerCase(Locale.ROOT).contains(VERIFICATION_VALUE_TOKEN))
                    .toList();

            // The whole file is searched rather than the card table alone: the field belongs to CARDDATA, so a
            // verification column under any other table would be an invented one.
            assertThat(declarations)
                    .as("app/cpy/CVACT02Y.cpy:L7 declares CARD-CVV-CD PIC 9(03) inside the authoritative "
                            + "150-byte record, so exactly one column declaration names it and it is on the "
                            + "card table. CHAR(3) rather than numeric because 8 of the 50 fixture rows lead "
                            + "with a zero")
                    .singleElement()
                    .satisfies(line -> {
                        assertThat(line.toLowerCase(Locale.ROOT)).contains("card_cvv_cd");
                        assertThat(line).contains("CHAR(3)");
                        assertThat(line).contains("NOT NULL");
                    });

            // And the confinement must be DOCUMENTED, not merely implemented. An undocumented column of this
            // kind reads as an oversight and invites a later contributor to project it into a DTO.
            assertThat(schema.toLowerCase(Locale.ROOT))
                    .as("the migration must explain that the read path, not the column, is what is withheld")
                    .contains("card_cvv_cd is declared, and the read path is withheld");
        }

        @Test
        @DisplayName("the entity persists the value privately and declares no accessor that returns it")
        void theEntityPersistsTheValueWithoutAnAccessor() {
            assertThat(Card.class.getDeclaredFields())
                    .as("exactly one instance field carries it")
                    .filteredOn(field -> namesVerificationValue(field.getName())
                            && !Modifier.isStatic(field.getModifiers()))
                    .singleElement()
                    .satisfies(field -> {
                        assertThat(field.getType()).isEqualTo(String.class);
                        assertThat(Modifier.isPrivate(field.getModifiers())).isTrue();
                    });
            assertThat(Card.class.getMethods())
                    .as("and every method that names it answers a boolean question rather than returning it")
                    .filteredOn(method -> namesVerificationValue(method.getName()))
                    .allSatisfy(method -> assertThat(method.getReturnType()).isEqualTo(boolean.class));
        }

        @Test
        @DisplayName("the request group declares no verification component, so none can be accepted")
        void theRequestGroupDeclaresNoVerificationComponent() {
            assertThat(CardUpdateRequest.CardDetails.class.getRecordComponents())
                    .noneMatch(component -> namesVerificationValue(component.getName()));
        }

        @Test
        @DisplayName("the read response declares no verification component, so none can be returned")
        void theReadResponseDeclaresNoVerificationComponent() {
            // The as-displayed group travels in the response body and back in the request body, so the two
            // wire shapes are where a verification value would escape. Neither declares one, which is what
            // keeps the stored value confined to the entity - where it has no read path at all.
            assertThat(com.cardemo.model.dto.CardResponse.class.getRecordComponents())
                    .noneMatch(component -> namesVerificationValue(component.getName()));
            assertThat(com.cardemo.model.dto.CardDto.class.getDeclaredMethods())
                    .noneMatch(method -> namesVerificationValue(method.getName()));
        }

        @Test
        @DisplayName("a successful update writes, and the two MOVEs of :1464-1465 cannot blank the value")
        void aSuccessfulUpdatePreservesTheStoredVerificationValue() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard()));

            confirmSave(changedNameRequest(agreeingSnapshot()));

            // The legacy rewrite destroyed the stored verification value on every successful update, because
            // :1464-1465 moved the never-assigned CCUP-NEW-CVV-CD onto the record. Nothing here can: the
            // service mutates the loaded row and there is no setter for the field, so the stored three
            // characters travel through the write untouched.
            final Card written = capturePersisted();
            assertThat(written.getEmbossedName()).startsWith(SUBMITTED_NAME);
            assertThat(written.matchesVerificationValue(STORED_VERIFICATION_VALUE))
                    .as("the stored value survives the rewrite, which is the labelled improvement on the "
                            + "legacy blanking defect")
                    .isTrue();
            assertThat(written.toString())
                    .as("and no rendering may name or contain a verification value")
                    .doesNotContainIgnoringCase(VERIFICATION_VALUE_TOKEN)
                    .doesNotContain(STORED_VERIFICATION_VALUE);
        }

        @Test
        @DisplayName("a detected change abandons the write, so nothing is written at all")
        void aDetectedChangeAbandonsTheWrite() {
            // 9300 detects a changed embossed name and :1455-1457 branches to the exit before :1461 begins
            // building the update image, so nothing is written.
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard()));

            confirmSave(changedNameRequest(snapshotOf("SOMEONE ELSE")));

            verify(cardRepository, never()).save(any(Card.class));
        }

        /**
         * Reports whether a declared name refers to a card verification value under any spelling.
         *
         * @param name the declared field, method or component name
         * @return {@code true} when the name refers to a verification value
         */
        private boolean namesVerificationValue(final String name) {
            return name.toLowerCase(Locale.ROOT).contains(VERIFICATION_VALUE_TOKEN);
        }
    }

    @Nested
    @DisplayName("M1 and F10 - the row is read under a write lock AND scoped to the owning account")
    class ReadForUpdateLocking {

        @Test
        @DisplayName("the write path reads through the account-scoped locking finder and no other")
        void writePathUsesTheAccountScopedLockingFinder() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard()));

            confirmSave(changedNameRequest(agreeingSnapshot()));

            // :1427-1436 is EXEC CICS READ ... UPDATE, so the lock is acquired at the READ; the restored
            // :1424 adds the account half of the key. Both belong to the SAME read: locking first and
            // checking ownership afterwards would still have locked another account's row, and checking
            // first would have read it unlocked.
            verify(cardRepository).findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC);
            verify(cardRepository, never()).findById(CARD_NUMBER);
            verify(cardRepository, never()).findByIdForUpdate(CARD_NUMBER);
            verify(cardRepository, never()).findByCardNumberAndAccountId(CARD_NUMBER, ACCOUNT_ID_NUMERIC);
        }

        @Test
        @DisplayName("the finder receives the sixteen-digit record key and the numeric account identifier")
        void lockingFinderReceivesBothKeyHalves() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.of(storedCard()));

            confirmSave(changedNameRequest(agreeingSnapshot()));

            final ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<Long> account = ArgumentCaptor.forClass(Long.class);
            verify(cardRepository).findByIdAndAccountIdForUpdate(key.capture(), account.capture());
            // :1425 MOVE CC-CARD-NUM TO WS-CARD-RID-CARDNUM - the RIDFLD is the full PIC X(16) field.
            assertThat(key.getValue()).hasSize(16).isEqualTo(CARD_NUMBER);
            // :1424 MOVE CC-ACCT-ID-N TO WS-CARD-RID-ACCT-ID - the numeric REDEFINES view.
            assertThat(account.getValue()).isEqualTo(ACCOUNT_ID_NUMERIC);
        }

        @Test
        @DisplayName("an absent row is the :1441-1449 could-not-lock outcome, not a not-found outcome")
        void absentRowIsTheCouldNotLockOutcome() {
            when(cardRepository.findByIdAndAccountIdForUpdate(CARD_NUMBER, ACCOUNT_ID_NUMERIC))
                    .thenReturn(Optional.empty());

            confirmSave(changedNameRequest(agreeingSnapshot()));

            // :1441-1449 treats anything other than DFHRESP(NORMAL) as "could not lock", a missing row
            // included - and a card owned by another account arrives as exactly the same empty result, so
            // ownership cannot be probed through the difference.
            verify(cardRepository, never()).save(any(Card.class));
        }

        @Test
        @DisplayName("the declared finder carries PESSIMISTIC_WRITE and both key halves")
        void declaredFinderCarriesPessimisticWrite() throws NoSuchMethodException {
            final Method finder = CardRepository.class.getDeclaredMethod("findByIdAndAccountIdForUpdate",
                    String.class, Long.class);
            final Lock lock = finder.getAnnotation(Lock.class);

            assertThat(lock)
                    .as("without the annotation the finder is an unlocked read and M1 is not closed")
                    .isNotNull();
            assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
            assertThat(finder.getReturnType()).isEqualTo(Optional.class);
            assertThat(finder.getParameterCount())
                    .as("a single-key finder would not close F10")
                    .isEqualTo(2);
        }
    }
}
