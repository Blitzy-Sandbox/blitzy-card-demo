package com.carddemo.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link Card} entity (COBOL {@code CARD-RECORD} / copybook
 * {@code CVACT02Y}): accessor round-trips, the {@link String} natural key, and
 * the {@code @Version} optimistic-lock accessor.
 *
 * <p>{@code Card} is the field-by-field migration of the 150-byte legacy
 * {@code CARD-RECORD} (source commit {@code 27d6c6f}, read-only reference). The
 * record has <em>no</em> monetary fields, so these tests deliberately exercise
 * only {@link String}, wrapper ({@link Long}/{@link Integer}) and
 * {@link LocalDate} accessors &mdash; only text, whole-number and date
 * accessors are involved, consistent with the numeric-fidelity constraints of
 * the migration.</p>
 *
 * <p>The {@code version} property is covered explicitly: {@code Card} carries a
 * JPA {@code @Version} column so that the Card Update transaction
 * ({@code COCRDUPC} / CCUP) can detect a concurrent modification through
 * optimistic locking. Verifying its accessor guards against an accidental
 * regression of that property.</p>
 *
 * <p>This is a plain-POJO test: no Spring context, no persistence, no
 * container fixtures and no stubbed collaborators &mdash; JUnit 5 with AssertJ
 * only.</p>
 */
class CardTest {

    /** Canonical 16-character card number ({@code CARD-NUM PIC X(16)}, natural key). */
    private static final String SAMPLE_CARD_NUM = "4111111111111111";

    /** Canonical 11-digit owning account id ({@code CARD-ACCT-ID PIC 9(11)}). */
    private static final Long SAMPLE_ACCT_ID = 12345678901L;

    /** Canonical 3-digit CVV code ({@code CARD-CVV-CD PIC 9(03)}). */
    private static final Integer SAMPLE_CVV_CD = 123;

    /** Canonical embossed cardholder name ({@code CARD-EMBOSSED-NAME PIC X(50)}). */
    private static final String SAMPLE_EMBOSSED_NAME = "JOHN Q PUBLIC";

    /** Canonical active-status indicator ({@code CARD-ACTIVE-STATUS PIC X(01)}). */
    private static final String SAMPLE_ACTIVE_STATUS = "Y";

    /** Canonical expiration date ({@code CARD-EXPIRAION-DATE}, stored as a {@link LocalDate}). */
    private static final LocalDate SAMPLE_EXPIRATION_DATE = LocalDate.of(2027, 12, 31);

    /**
     * The natural key {@code cardNum} must round-trip: the exact 16-character
     * value written through the setter is returned verbatim by the getter and
     * preserves the copybook's {@code PIC X(16)} width.
     */
    @Test
    @DisplayName("cardNum natural key round-trips through setter/getter")
    void naturalKeyRoundTrip() {
        Card card = new Card();

        card.setCardNum(SAMPLE_CARD_NUM);

        assertThat(card.getCardNum()).isEqualTo(SAMPLE_CARD_NUM).hasSize(16);
    }

    /**
     * Every non-date scalar field round-trips with wrapper-type fidelity: the
     * {@link Long} account id, the {@link Integer} CVV code and the two
     * {@link String} fields are returned exactly as set. Numeric assertions use
     * {@link Long#valueOf(long)} / {@link Integer#valueOf(int)} so equality is
     * evaluated on the wrapper values (not on primitives).
     */
    @Test
    @DisplayName("scalar fields (Long/Integer/String) round-trip with wrapper equality")
    void scalarFieldsRoundTrip() {
        Card card = new Card();

        card.setCardAcctId(SAMPLE_ACCT_ID);
        card.setCardCvvCd(SAMPLE_CVV_CD);
        card.setCardEmbossedName(SAMPLE_EMBOSSED_NAME);
        card.setCardActiveStatus(SAMPLE_ACTIVE_STATUS);

        assertThat(card.getCardAcctId()).isEqualTo(Long.valueOf(12345678901L));
        assertThat(card.getCardCvvCd()).isEqualTo(Integer.valueOf(123));
        assertThat(card.getCardEmbossedName()).isEqualTo(SAMPLE_EMBOSSED_NAME);
        assertThat(card.getCardActiveStatus()).isEqualTo(SAMPLE_ACTIVE_STATUS);
    }

    /**
     * The {@link LocalDate} expiration date round-trips unchanged, confirming
     * the type-safe date mapping of the legacy {@code CARD-EXPIRAION-DATE}
     * (sic) 10-character field.
     */
    @Test
    @DisplayName("cardExpirationDate LocalDate round-trips")
    void expirationDateRoundTrip() {
        Card card = new Card();

        card.setCardExpirationDate(SAMPLE_EXPIRATION_DATE);

        assertThat(card.getCardExpirationDate()).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    /**
     * The {@code @Version} optimistic-lock property must be readable and
     * writable through its accessor. Two successive values are exercised to
     * prove the counter is a plain, mutable {@link Long} property. The
     * persistence provider manages it at runtime, but a setter is required for
     * detached-entity merge scenarios and test fixtures; a working accessor is
     * a precondition for the {@code COCRDUPC} (CCUP) read-then-rewrite
     * concurrency semantics.
     */
    @Test
    @DisplayName("version optimistic-lock accessor round-trips (0L then 3L)")
    void versionAccessorRoundTrip() {
        Card card = new Card();

        card.setVersion(0L);
        assertThat(card.getVersion()).isEqualTo(Long.valueOf(0L));

        card.setVersion(3L);
        assertThat(card.getVersion()).isEqualTo(Long.valueOf(3L));
    }

    /**
     * A freshly constructed {@link Card} must leave every object-typed property
     * {@code null}. Because all mapped fields (and {@code version}) are wrapper
     * or reference types, there are no accidental primitive defaults such as
     * {@code 0} that could be mistaken for real data.
     */
    @Test
    @DisplayName("new Card() leaves all object-typed properties null")
    void newInstanceDefaultsAreNull() {
        Card card = new Card();

        assertThat(card.getCardNum()).isNull();
        assertThat(card.getCardAcctId()).isNull();
        assertThat(card.getCardCvvCd()).isNull();
        assertThat(card.getCardEmbossedName()).isNull();
        assertThat(card.getCardExpirationDate()).isNull();
        assertThat(card.getCardActiveStatus()).isNull();
        assertThat(card.getVersion()).isNull();
    }
}
