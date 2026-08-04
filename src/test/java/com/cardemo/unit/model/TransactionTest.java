/*
 * ******************************************************************
 * Program     : TransactionTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that the Transaction entity reproduces the
 *               CVTRA05Y 350-byte record contract byte for byte: the
 *               thirteen modelled fields at their catalogued offsets, the
 *               unmodelled 20-byte FILLER, the 16-byte primary key, the
 *               NUMERIC(11,2) amount tier, the three text timestamp
 *               renderings, the free-text source column, the separation
 *               from the DailyTransaction staging twin, and the
 *               redaction of the card number from toString.
 * Source      : app/cpy/CVTRA05Y.cpy:L4-L18 (01 TRAN-RECORD, 350 B) @ 7756d89
 * Source      : app/cpy/CVTRA06Y.cpy:L4-L18 (01 DALYTRAN-RECORD, the twin) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L3591,L3593 (TRANSACT key 16, reclen 350) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L3672-L3678 (TRANSACT.VSAM.AIX, AXRKP 304, NONUNIQKEY) @ 7756d89
 * Source      : app/proc/TRANREPT.prc:L39-L40 (DFSORT SYMNAMES 263 and 305) @ 7756d89
 * Source      : app/jcl/CREASTMT.JCL:L53-L54 (SORT and OUTREC offsets) @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L149,L159-L174 (DB2 timestamp geometry) @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L547-L552 (the cycle-debit sign branch) @ 7756d89
 * Source      : app/cbl/COBIL00C.cbl:L249-L265 (online timestamp, space at byte 11) @ 7756d89
 * Source      : app/cbl/COTRN02C.cbl:L464-L465 (timestamp pass-through move) @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L484 (MOVE 'System' TO TRAN-SOURCE) @ 7756d89
 * Source      : app/data/ASCII/dailytran.txt (300 rows x 350 bytes) @ 7756d89
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.cardemo.model.entity.Transaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for the {@link Transaction} entity, which replaces the {@code TRANSACT} VSAM KSDS cluster.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>{@code app/cpy/CVTRA05Y.cpy:L4} declares {@code 01 TRAN-RECORD} - the record name is
 * {@code TRAN-RECORD}, not {@code TRANSACTION-RECORD} - and {@code :L5-L18} lay out thirteen data fields
 * plus a trailing filler whose widths sum to exactly
 * {@code 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 = 330} populated bytes and
 * {@code 330 + 20 = 350} in total, the record length catalogued at {@code app/catlg/LISTCAT.txt:L3593}
 * beside {@code KEYLEN 16}. This class restates that offset map as an explicit table and asserts it
 * against the entity's own mapping, because three mutually independent legacy artefacts address the record
 * by byte position and a one-byte drift would break all three at once.
 *
 * <p>Six contracts are asserted that a plausible-looking implementation gets wrong:
 *
 * <ul>
 *   <li><strong>The amount is {@code NUMERIC(11,2)}, never {@code NUMERIC(12,2)}.</strong>
 *       {@code TRAN-AMT PIC S9(09)V99} is the nine-integer-digit tier, one digit narrower than the
 *       {@code S9(10)V99} money fields of {@code Account}. Severity High if collapsed.</li>
 *   <li><strong>Both timestamps are text.</strong> {@code PIC X(26)} to {@code CHAR(26)} and
 *       {@code String}, because three incompatible producers write them. Severity High if a temporal type
 *       is introduced.</li>
 *   <li><strong>The source column is free text, never an enum.</strong> Severity Blocker if narrowed -
 *       fifty of the three hundred reference rows would be rejected outright.</li>
 *   <li><strong>The alternate index is non-unique.</strong> Severity High if a unique constraint is added,
 *       since all three hundred reference rows share one processing-timestamp value.</li>
 *   <li><strong>The staging twin shares no ancestry.</strong> Severity High if unified, because it would
 *       hand the staging table a version column it must not have.</li>
 *   <li><strong>The card number never reaches {@code toString}.</strong></li>
 *   </ul>
 *
 * <p><strong>Two corrections to the specification this class was written against are recorded here,
 * because Rule 1 clause F requires evidence rather than assertion.</strong> First, the entity's timestamp
 * properties are named {@code origTs} and {@code procTs}, not {@code originTimestamp} and
 * {@code processTimestamp}; this class binds to the names the entity actually publishes. Second, the
 * timestamp pass-through move was cited at {@code app/cbl/COTRN02C.cbl:L454-L455}, which is off by ten:
 * {@code :L454-L455} move {@code TRNSRCI} into {@code TRAN-SOURCE} and {@code TDESCI} into
 * {@code TRAN-DESC}, while the two timestamp moves are at {@code :L464-L465}. Every locator in this file
 * was re-read at commit {@code 7756d89} before being cited, and {@code :L464-L465} is what is cited.
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>{@code
 * ./mvnw -B -ntp -o test -Dtest=TransactionTest
 * ./mvnw -B -ntp -Ddependency-check.skip=true test
 * }</pre>
 *
 * <p>Surefire 3.5.4 collects this class because it lives under {@code src/test/java} and its name ends in
 * {@code Test}; the plugin includes {@code **}{@code /*Test.java} and excludes only the
 * {@code integration} and {@code e2e} trees. A class moved out of {@code src/test/java} is compiled by
 * neither Surefire nor Failsafe and silently never runs - a green build that proves nothing - so this file
 * must not be renamed or relocated. Where no local toolchain is present the pinned image reproduces the
 * build exactly:
 * {@code docker run --rm -v "$PWD":/w -w /w maven:3.9.11-eclipse-temurin-25 ./mvnw -q test}.
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>None is external. Time comes exclusively from {@link FixedClockProvider}; this class never calls
 * {@code Instant.now()}, {@code LocalDate.now()} or {@code System.currentTimeMillis()}, never consults the
 * default locale and never consults the default zone, so every assertion is reproducible on any host.
 * Record data comes exclusively from {@link FixtureLoader}, which reads the frozen fixture from the test
 * classpath. The copybook widths are restated here as this class's own literals rather than read from the
 * entity's private constants, so that a silent change to the entity fails a test instead of moving with
 * it. There is no mutable static state: the offset table is an immutable {@code List.of(...)} and every
 * subject is built fresh by {@link #postedTransaction()}. No mock is used, so no Mockito strictness
 * setting applies.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The build fails on something trivial-looking.</strong> Compilation runs with
 *       {@code -Xlint:all}, {@code -Werror} and {@code failOnWarning}, and that reaches test compilation,
 *       so a raw type or a deprecation is an error rather than a warning. Reproduce with
 *       {@code ./mvnw -q test-compile}.</li>
 *   <li><strong>A fixture stream is null at run time.</strong> The fixture is {@code dailytran.txt},
 *       spelled in full. The mainframe data definition name is {@code DALYTRAN}, so {@code dalytran.txt}
 *       is the natural guess, compiles cleanly and fails only when the resource is opened.</li>
 *   <li><strong>A timestamp assertion reports 29 characters instead of 26.</strong> The batch rendering
 *       was formatted to nanoseconds. It is hundredths of a second followed by four literal zeros, per
 *       {@code DB2-MIL PIC 9(002)} and {@code MOVE '0000' TO DB2-REST}.</li>
 *   <li><strong>An amount assertion fails on scale rather than value.</strong> {@code BigDecimal.equals}
 *       is scale-sensitive, so {@code 100.0} is not {@code equals} to {@code 100.00} even though they are
 *       the same money. Compare with {@code compareTo}.</li>
 *   <li><strong>The precision assertion fails.</strong> The amount was widened to the account tier.
 *       {@code S9(09)V99} is {@code NUMERIC(11,2)}.</li>
 *   <li><strong>A width rejection stops firing.</strong> A guard was removed, so the failure moves from
 *       construction time to database flush time and loses the caller's stack frame.</li>
 *   </ul>
 *
 * <p><strong>Scope, and what deliberately lives elsewhere.</strong> This class owns the
 * {@link Transaction} entity's own contract. The cross-entity twin comparison and the two-byte truncation
 * performed by the {@code CREASTMT} projection are owned by {@code TransactionTwinLayoutTest} and the
 * batch tier - here only the offsets that projection presupposes are asserted. Data definition language
 * text is owned by {@code SchemaStructureTest}. Beyond the copybook and the catalogue, no schema detail is
 * asserted here and none is invented: any further data definition detail is <em>Not available</em> from
 * the frozen corpus, and this class contains no structured query language of any kind.
 */
class TransactionTest {

    /** {@code TRAN-ID PIC X(16)} - CVTRA05Y:L5, and the catalogued key length for TRANSACT. */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /** {@code TRAN-TYPE-CD PIC X(02)} - CVTRA05Y:L6. */
    private static final int TYPE_CODE_WIDTH = 2;

    /** {@code TRAN-CAT-CD PIC 9(04)} - CVTRA05Y:L7. */
    private static final int CATEGORY_CODE_WIDTH = 4;

    /** {@code TRAN-SOURCE PIC X(10)} - CVTRA05Y:L8. */
    private static final int SOURCE_WIDTH = 10;

    /** {@code TRAN-DESC PIC X(100)} - CVTRA05Y:L9. */
    private static final int DESCRIPTION_WIDTH = 100;

    /** {@code TRAN-AMT PIC S9(09)V99} - CVTRA05Y:L10: nine integer digits plus two decimals. */
    private static final int AMOUNT_WIDTH = 11;

    /** {@code TRAN-MERCHANT-ID PIC 9(09)} - CVTRA05Y:L11. */
    private static final int MERCHANT_ID_WIDTH = 9;

    /** {@code TRAN-MERCHANT-NAME PIC X(50)} - CVTRA05Y:L12. */
    private static final int MERCHANT_NAME_WIDTH = 50;

    /** {@code TRAN-MERCHANT-CITY PIC X(50)} - CVTRA05Y:L13. */
    private static final int MERCHANT_CITY_WIDTH = 50;

    /** {@code TRAN-MERCHANT-ZIP PIC X(10)} - CVTRA05Y:L14. */
    private static final int MERCHANT_ZIP_WIDTH = 10;

    /** {@code TRAN-CARD-NUM PIC X(16)} - CVTRA05Y:L15, the DFSORT field at offset 263. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** {@code TRAN-ORIG-TS PIC X(26)} - CVTRA05Y:L16. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** {@code FILLER PIC X(20)} - CVTRA05Y:L18, deliberately not modelled by the entity. */
    private static final int FILLER_WIDTH = 20;

    /** Populated bytes, {@code TRAN-ID} through {@code TRAN-PROC-TS}. */
    private static final int POPULATED_LENGTH = 330;

    /** The catalogued record length for TRANSACT - {@code AVGLRECL 350} at LISTCAT:L3593. */
    private static final int RECORD_LENGTH = 350;

    /** {@code KEYLEN 16} at LISTCAT:L3593, with {@code RKP 0} at {@code :L3594}. */
    private static final int PRIMARY_KEY_LENGTH = 16;

    /** {@code AXRKP 304} at LISTCAT:L3676 - a zero-based displacement. */
    private static final int ALTERNATE_KEY_DISPLACEMENT = 304;

    /** {@code KEYLEN 26} at LISTCAT:L3674 for TRANSACT.VSAM.AIX. */
    private static final int ALTERNATE_KEY_LENGTH = 26;

    /** The first byte of {@code TRAN-CARD-NUM}, per {@code TRAN-CARD-NUM,263,16,ZD} at TRANREPT.prc:L39. */
    private static final int CARD_NUMBER_START_BYTE = 263;

    /** The first byte of {@code TRAN-PROC-TS}, per {@code TRAN-PROC-DT,305,10,CH} at TRANREPT.prc:L40. */
    private static final int PROC_TS_START_BYTE = 305;

    /** The first byte of {@code TRAN-ORIG-TS}, the origin of the {@code CREASTMT} projection's third element. */
    private static final int ORIG_TS_START_BYTE = 279;

    /** The first byte of {@code TRAN-AMT}. */
    private static final int AMOUNT_START_BYTE = 133;

    /** The first byte of {@code TRAN-SOURCE}. */
    private static final int SOURCE_START_BYTE = 23;

    /** Bytes the {@code CREASTMT} projection copies from offset 279 - {@code 279:279,50} at CREASTMT.JCL:L54. */
    private static final int PROJECTION_LENGTH = 50;

    /** The ten-character prefix the report filter compares - {@code TRAN-PROC-DT,305,10,CH}. */
    private static final int DATE_PREFIX_LENGTH = 10;

    /** Rows in {@code app/data/ASCII/dailytran.txt}. */
    private static final int FIXTURE_ROW_COUNT = 300;

    /** Rows of the fixture carrying a positive zoned-decimal overpunch sign. */
    private static final int FIXTURE_POSITIVE_AMOUNTS = 250;

    /** Rows of the fixture carrying a negative zoned-decimal overpunch sign. */
    private static final int FIXTURE_NEGATIVE_AMOUNTS = 50;

    /** {@code AIX 3} at LISTCAT:L3939 - TRANSACT.VSAM.AIX is the third. */
    private static final int CATALOGUE_AIX_COUNT = 3;

    /** {@code CLUSTER 10} at LISTCAT:L3941. */
    private static final int CATALOGUE_CLUSTER_COUNT = 10;

    /** {@code GDG 7} at LISTCAT:L3943. */
    private static final int CATALOGUE_GDG_COUNT = 7;

    /** {@code PATH 3} at LISTCAT:L3946. */
    private static final int CATALOGUE_PATH_COUNT = 3;

    /** The widest {@code S9(09)V99} value, and the widest a {@code NUMERIC(11,2)} column holds. */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");

    /** The narrowest {@code S9(09)V99} value: the picture clause carries an {@code S}. */
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("-999999999.99");

    /** The precision of {@code tran_amt}: nine integer digits plus two decimals. */
    private static final int AMOUNT_PRECISION = 11;

    /** The scale of {@code tran_amt}, from the two decimal positions of {@code V99}. */
    private static final int AMOUNT_SCALE = 2;

    /** The precision the {@code Account} money fields use, and which {@code tran_amt} must never take. */
    private static final int ACCOUNT_MONEY_PRECISION = 12;

    /** {@code MOVE 'System' TO TRAN-SOURCE} at CBACT04C:L484, padded to the ten-byte field. */
    private static final String SOURCE_SYSTEM = "System    ";

    /** {@code MOVE 'POS TERM' TO TRAN-SOURCE} at COBIL00C:L222, padded to the ten-byte field. */
    private static final String SOURCE_POS_TERM = "POS TERM  ";

    /** The fixture's other source value, which has no {@code MOVE} literal anywhere in the corpus. */
    private static final String SOURCE_OPERATOR = "OPERATOR  ";

    /** A blank timestamp: {@code TRAN-PROC-TS} on every one of the three hundred reference rows. */
    private static final String BLANK_TIMESTAMP = " ".repeat(TIMESTAMP_WIDTH);

    /** The identifier every subject in this class carries, and the only value used to name a failure. */
    private static final String SUBJECT_ID = "0000000000683580";

    /**
     * A card-number placeholder used only to prove it is <em>absent</em> from {@code toString}.
     *
     * <p>Deliberately alphabetic and exactly sixteen characters. {@code TRAN-CARD-NUM} is
     * {@code PIC X(16)} free text, so the column admits it, and choosing a value that no card-number
     * pattern and no checksum can accept keeps a payment-card-shaped literal out of the repository
     * altogether - a digits-only test value would be indistinguishable from a real primary account
     * number to a secret scanner. It also makes the redaction assertions unambiguous, because an
     * alphabetic sentinel cannot coincidentally appear inside a numeric rendering. It is never
     * interpolated into an assertion message.
     */
    private static final String SENTINEL_CARD_NUMBER = "ZZSENTINELCARDNO";

    /** A merchant name used only to prove it is absent from {@code toString}. */
    private static final String SENTINEL_MERCHANT_NAME = "ZZSENTINELMERCHANTNAME";

    /** A merchant city used only to prove it is absent from {@code toString}. */
    private static final String SENTINEL_MERCHANT_CITY = "ZZSENTINELMERCHANTCITY";

    /** A merchant postal code used only to prove it is absent from {@code toString}. */
    private static final String SENTINEL_MERCHANT_ZIP = "ZZ99999999";

    /** Packages a model entity must not reach into, asserted field type by field type and annotation by annotation. */
    private static final List<String> FORBIDDEN_PACKAGES = List.of(
            "com.cardemo.exception",
            "com.cardemo.repository",
            "com.cardemo.service",
            "com.cardemo.controller",
            "com.cardemo.batch",
            "com.cardemo.security",
            "com.cardemo.config",
            "com.cardemo.observability",
            "com.cardemo.model.key",
            "com.cardemo.model.enums");

    /**
     * The proven 350-byte offset map of {@code app/cpy/CVTRA05Y.cpy:L5-L18}, stated explicitly so that the
     * arithmetic is asserted rather than assumed.
     *
     * <p>Immutable, so it is shared state but not mutable state. Byte positions are one-based and
     * inclusive, exactly as the DFSORT symbol definitions express them.
     *
     * @param cobolField   the COBOL field name as the copybook spells it
     * @param copybookLine the one-based line of {@code app/cpy/CVTRA05Y.cpy} declaring it
     * @param picture      the picture clause, verbatim
     * @param startByte    the one-based first byte of the field within the record
     * @param width        the field width in bytes, which the picture clause fixes
     * @param javaProperty the entity property that carries it, or {@code null} when unmodelled
     * @param columnName   the mapped column, or {@code null} when unmodelled
     * @param javaType     the entity property's declared type, or {@code null} when unmodelled
     */
    private record FieldContract(String cobolField,
                                 int copybookLine,
                                 String picture,
                                 int startByte,
                                 int width,
                                 String javaProperty,
                                 String columnName,
                                 Class<?> javaType) {

        /** @return the one-based last byte of the field. */
        int endByte() {
            return startByte + width - 1;
        }

        /** @return {@code true} when the copybook field has an entity property. */
        boolean isModelled() {
            return javaProperty != null;
        }
    }

    /**
     * The fourteen copybook fields of {@code TRAN-RECORD} in declaration order, thirteen of them modelled.
     *
     * <p>Every number here comes from {@code app/cpy/CVTRA05Y.cpy} and nowhere else. The two offsets that
     * the legacy sort decks state independently - 263 and 305 - are asserted against those decks in
     * {@link OffsetCorroboration}.
     */
    private static final List<FieldContract> OFFSET_MAP = List.of(
            new FieldContract("TRAN-ID", 5, "X(16)", 1, TRANSACTION_ID_WIDTH,
                    "transactionId", "tran_id", String.class),
            new FieldContract("TRAN-TYPE-CD", 6, "X(02)", 17, TYPE_CODE_WIDTH,
                    "typeCode", "tran_type_cd", String.class),
            new FieldContract("TRAN-CAT-CD", 7, "9(04)", 19, CATEGORY_CODE_WIDTH,
                    "categoryCode", "tran_cat_cd", Integer.class),
            new FieldContract("TRAN-SOURCE", 8, "X(10)", SOURCE_START_BYTE, SOURCE_WIDTH,
                    "transactionSource", "tran_source", String.class),
            new FieldContract("TRAN-DESC", 9, "X(100)", 33, DESCRIPTION_WIDTH,
                    "description", "tran_desc", String.class),
            new FieldContract("TRAN-AMT", 10, "S9(09)V99", AMOUNT_START_BYTE, AMOUNT_WIDTH,
                    "amount", "tran_amt", BigDecimal.class),
            new FieldContract("TRAN-MERCHANT-ID", 11, "9(09)", 144, MERCHANT_ID_WIDTH,
                    "merchantId", "tran_merchant_id", Long.class),
            new FieldContract("TRAN-MERCHANT-NAME", 12, "X(50)", 153, MERCHANT_NAME_WIDTH,
                    "merchantName", "tran_merchant_name", String.class),
            new FieldContract("TRAN-MERCHANT-CITY", 13, "X(50)", 203, MERCHANT_CITY_WIDTH,
                    "merchantCity", "tran_merchant_city", String.class),
            new FieldContract("TRAN-MERCHANT-ZIP", 14, "X(10)", 253, MERCHANT_ZIP_WIDTH,
                    "merchantZip", "tran_merchant_zip", String.class),
            new FieldContract("TRAN-CARD-NUM", 15, "X(16)", CARD_NUMBER_START_BYTE, CARD_NUMBER_WIDTH,
                    "cardNumber", "tran_card_num", String.class),
            new FieldContract("TRAN-ORIG-TS", 16, "X(26)", ORIG_TS_START_BYTE, TIMESTAMP_WIDTH,
                    "origTs", "tran_orig_ts", String.class),
            new FieldContract("TRAN-PROC-TS", 17, "X(26)", PROC_TS_START_BYTE, TIMESTAMP_WIDTH,
                    "procTs", "tran_proc_ts", String.class),
            new FieldContract("FILLER", 18, "X(20)", 331, FILLER_WIDTH,
                    null, null, null));

    /**
     * Builds a fully populated transaction whose every field is a value the copybook admits.
     *
     * <p>Returns a new instance on each call, so no test can perturb another's subject. The originating
     * timestamp is the online rendering the fixture carries and the processing timestamp is blank, which
     * is exactly the staged state of all three hundred reference rows.
     *
     * @return a fresh, valid {@link Transaction}
     */
    private static Transaction postedTransaction() {
        return new Transaction(SUBJECT_ID,
                "01",
                1,
                SOURCE_POS_TERM,
                "PURCHASE OF GOODS",
                new BigDecimal("123.45"),
                123456789L,
                SENTINEL_MERCHANT_NAME,
                SENTINEL_MERCHANT_CITY,
                SENTINEL_MERCHANT_ZIP,
                SENTINEL_CARD_NUMBER,
                FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP,
                BLANK_TIMESTAMP);
    }

    /**
     * Returns the entity's declared field for a property name.
     *
     * @param property the entity property name
     * @return the reflected field, never {@code null}
     */
    private static Field declaredField(final String property) {
        try {
            return Transaction.class.getDeclaredField(property);
        } catch (final NoSuchFieldException cause) {
            throw new AssertionError("Transaction must declare a field named '" + property
                    + "', because app/cpy/CVTRA05Y.cpy declares the corresponding COBOL field", cause);
        }
    }

    /**
     * Returns the {@code @Column} mapping of a property, failing with the copybook rationale when absent.
     *
     * @param property the entity property name
     * @return the reflected column annotation, never {@code null}
     */
    private static Column columnOf(final String property) {
        final Column column = declaredField(property).getAnnotation(Column.class);
        assertThat(column)
                .as("every modelled field of app/cpy/CVTRA05Y.cpy must carry an explicit @Column so that "
                        + "the width contract is machine-checkable, but '%s' carries none", property)
                .isNotNull();
        return column;
    }

    /**
     * Returns the entity's persistent fields, excluding the synthetic and static members the compiler and
     * the coverage agent add.
     *
     * @return the declared instance fields of {@link Transaction}
     */
    private static List<Field> instanceFields() {
        final List<Field> fields = new ArrayList<>();
        for (final Field field : Transaction.class.getDeclaredFields()) {
            if (!field.isSynthetic() && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                fields.add(field);
            }
        }
        return fields;
    }

    /**
     * Returns the package name of a type, unwrapping array types and tolerating the primitive and
     * unnamed-package cases that carry no package at all.
     *
     * @param type the type to inspect
     * @return its package name, or the empty string when it has none
     */
    private static String packageNameOf(final Class<?> type) {
        Class<?> element = type;
        while (element.isArray()) {
            element = element.getComponentType();
        }
        return element.isPrimitive() || element.getPackage() == null ? "" : element.getPackage().getName();
    }

    @Nested
    @DisplayName("Phase 1 - the record geometry: 350 bytes, a 16-byte key and an unmodelled 20-byte filler")
    class RecordGeometry {

        @Test
        @DisplayName("the thirteen modelled widths sum to 330 and the filler carries the record to 350")
        void theWidthsSumToTheCataloguedRecordLength() {
            int populated = 0;
            for (final FieldContract contract : OFFSET_MAP) {
                if (contract.isModelled()) {
                    populated += contract.width();
                }
            }

            assertThat(populated)
                    .as("16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 = 330 populated "
                            + "bytes from app/cpy/CVTRA05Y.cpy:L5-L17")
                    .isEqualTo(POPULATED_LENGTH);
            assertThat(populated + FILLER_WIDTH)
                    .as("330 populated plus the 20-byte FILLER at app/cpy/CVTRA05Y.cpy:L18 is exactly the "
                            + "AVGLRECL 350 catalogued for TRANSACT at app/catlg/LISTCAT.txt:L3593")
                    .isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the offset map is gapless and contiguous: every field starts one byte after its predecessor")
        void theOffsetMapIsGaplessAndContiguous() {
            int expectedStart = 1;
            for (final FieldContract contract : OFFSET_MAP) {
                assertThat(contract.startByte())
                        .as("%s (%s) at app/cpy/CVTRA05Y.cpy:L%d must begin at byte %d - a COBOL group "
                                        + "item has no padding between elementary items, so any gap or "
                                        + "overlap here would shift every later field",
                                contract.cobolField(), contract.picture(), contract.copybookLine(),
                                expectedStart)
                        .isEqualTo(expectedStart);
                expectedStart = contract.endByte() + 1;
            }

            assertThat(expectedStart - 1)
                    .as("the last byte of the last field, FILLER at app/cpy/CVTRA05Y.cpy:L18, must be "
                            + "byte 350 exactly")
                    .isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the four independently attested offsets are 23, 133, 263, 279 and 305")
        void theIndependentlyAttestedOffsetsHold() {
            assertThat(byteOf("TRAN-SOURCE"))
                    .as("TRAN-SOURCE begins at byte 23, which is where the fixture census reads "
                            + "'POS TERM  ' and 'OPERATOR  '")
                    .isEqualTo(SOURCE_START_BYTE);
            assertThat(byteOf("TRAN-AMT"))
                    .as("TRAN-AMT begins at byte 133 and is 11 bytes wide, ending at 143")
                    .isEqualTo(AMOUNT_START_BYTE);
            assertThat(byteOf("TRAN-CARD-NUM"))
                    .as("TRAN-CARD-NUM begins at byte 263, stated independently by "
                            + "app/proc/TRANREPT.prc:L39 and app/jcl/CREASTMT.JCL:L53")
                    .isEqualTo(CARD_NUMBER_START_BYTE);
            assertThat(byteOf("TRAN-ORIG-TS"))
                    .as("TRAN-ORIG-TS begins at byte 279, the origin of the CREASTMT projection's third "
                            + "element at app/jcl/CREASTMT.JCL:L54")
                    .isEqualTo(ORIG_TS_START_BYTE);
            assertThat(byteOf("TRAN-PROC-TS"))
                    .as("TRAN-PROC-TS begins at byte 305, stated independently by "
                            + "app/proc/TRANREPT.prc:L40 and by AXRKP 304 at app/catlg/LISTCAT.txt:L3676")
                    .isEqualTo(PROC_TS_START_BYTE);
        }

        @Test
        @DisplayName("the entity maps thirteen data columns plus a version column, and nothing else")
        void theEntityMapsThirteenDataColumnsPlusVersion() {
            int modelled = 0;
            for (final FieldContract contract : OFFSET_MAP) {
                if (contract.isModelled()) {
                    modelled++;
                }
            }

            assertThat(modelled)
                    .as("thirteen of the fourteen copybook fields are modelled; only FILLER is not")
                    .isEqualTo(13);
            assertThat(instanceFields())
                    .as("the entity carries the thirteen data properties plus the version counter and no "
                            + "further state - a fifteenth field would be a column the copybook does not "
                            + "declare")
                    .hasSize(14);
        }

        @Test
        @DisplayName("every mapped column is NOT NULL and carries the copybook width")
        void everyColumnIsNotNullAndCarriesItsCopybookWidth() {
            for (final FieldContract contract : OFFSET_MAP) {
                if (!contract.isModelled()) {
                    continue;
                }
                final Column column = columnOf(contract.javaProperty());

                assertThat(column.name())
                        .as("%s at app/cpy/CVTRA05Y.cpy:L%d must map to column %s",
                                contract.cobolField(), contract.copybookLine(), contract.columnName())
                        .isEqualTo(contract.columnName());
                assertThat(column.nullable())
                        .as("a fixed-width COBOL field always holds a value, so %s must be NOT NULL; a "
                                        + "nullable column would let a row exist that the record layout "
                                        + "cannot represent",
                                contract.columnName())
                        .isFalse();
                assertThat(declaredField(contract.javaProperty()).getType())
                        .as("%s (%s) must be carried by a %s", contract.cobolField(), contract.picture(),
                                contract.javaType().getSimpleName())
                        .isEqualTo(contract.javaType());
            }
        }

        @ParameterizedTest
        @CsvSource({
            "transactionId,16",
            "typeCode,2",
            "transactionSource,10",
            "description,100",
            "merchantName,50",
            "merchantCity,50",
            "merchantZip,10",
            "cardNumber,16",
            "origTs,26",
            "procTs,26",
        })
        @DisplayName("each character column declares exactly its picture-clause width")
        void eachCharacterColumnDeclaresItsPictureWidth(final String property, final int width) {
            assertThat(columnOf(property).length())
                    .as("the declared length of '%s' is the width app/cpy/CVTRA05Y.cpy fixes; a narrower "
                            + "column truncates and a wider one accepts a value the record cannot hold",
                            property)
                    .isEqualTo(width);
        }

        @Test
        @DisplayName("the 20-byte FILLER at CVTRA05Y:L18 is mapped by no column at all")
        void theFillerIsNotModelled() {
            final FieldContract filler = OFFSET_MAP.get(OFFSET_MAP.size() - 1);

            assertThat(filler.cobolField())
                    .as("the last field of app/cpy/CVTRA05Y.cpy is FILLER")
                    .isEqualTo("FILLER");
            assertThat(filler.isModelled())
                    .as("FILLER exists only to pad the record to 350 bytes and holds no data - the "
                            + "census of app/data/ASCII/dailytran.txt finds bytes 331-350 blank on every "
                            + "one of the 300 rows - so modelling it would add a column with no meaning")
                    .isFalse();
            assertThat(filler.startByte())
                    .as("FILLER occupies bytes 331 to 350")
                    .isEqualTo(POPULATED_LENGTH + 1);
            assertThat(filler.endByte()).isEqualTo(RECORD_LENGTH);

            for (final Field field : instanceFields()) {
                assertThat(field.getName())
                        .as("no entity field may be named after the unmodelled filler")
                        .doesNotContainIgnoringCase("filler");
            }
        }

        @Test
        @DisplayName("every one of the 300 fixture rows is exactly 350 bytes - never 349, never 351")
        void everyFixtureRowIsExactlyTheRecordLength() {
            final FixtureLoader.FixtureData fixture =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

            assertThat(fixture.recordCount())
                    .as("app/data/ASCII/dailytran.txt holds 300 records")
                    .isEqualTo(FIXTURE_ROW_COUNT);
            assertThat(fixture.recordWidth())
                    .as("each record is the catalogued 350 bytes")
                    .isEqualTo(RECORD_LENGTH);

            for (int index = 0; index < fixture.recordCount(); index++) {
                assertThat(fixture.recordAt(index).length())
                        .as("record %d must be exactly %d bytes: a 349-byte record would leave the last "
                                        + "field short and a 351-byte record would push a byte past the "
                                        + "record boundary, and in both cases every field from the "
                                        + "truncation point on would decode as garbage",
                                index, RECORD_LENGTH)
                        .isEqualTo(RECORD_LENGTH);
            }
        }

        /**
         * Returns the one-based start byte the offset table records for a copybook field.
         *
         * @param cobolField the COBOL field name
         * @return its one-based start byte
         */
        private int byteOf(final String cobolField) {
            for (final FieldContract contract : OFFSET_MAP) {
                if (contract.cobolField().equals(cobolField)) {
                    return contract.startByte();
                }
            }
            throw new AssertionError("the offset table must describe " + cobolField
                    + ", which app/cpy/CVTRA05Y.cpy declares");
        }
    }

    @Nested
    @DisplayName("Phase 1 - identity: a text primary key with no generator, and one version column")
    class IdentityAndVersioning {

        @Test
        @DisplayName("the entity is mapped to the unquoted table name 'transaction'")
        void theEntityIsMappedToTheTransactionTable() {
            assertThat(Transaction.class.getAnnotation(Entity.class))
                    .as("Transaction replaces the TRANSACT VSAM KSDS cluster and must be a mapped entity")
                    .isNotNull();
            assertThat(Transaction.class.getAnnotation(Table.class))
                    .isNotNull();
            assertThat(Transaction.class.getAnnotation(Table.class).name())
                    .as("the table name is the unquoted identifier 'transaction'; quoting it would make "
                            + "schema validation compare a quoted identifier against an unquoted one")
                    .isEqualTo("transaction");
        }

        @Test
        @DisplayName("transactionId is a String @Id of the catalogued 16-byte key length")
        void theIdentifierIsASixteenByteTextKey() {
            final Field id = declaredField("transactionId");

            assertThat(id.getAnnotation(Id.class))
                    .as("RKP 0 with KEYLEN 16 at app/catlg/LISTCAT.txt:L3593-L3594 places the whole key at "
                            + "the start of the record, which is TRAN-ID and only TRAN-ID")
                    .isNotNull();
            assertThat(id.getType())
                    .as("TRAN-ID is PIC X(16) - an identifier, not a quantity. A numeric type would drop "
                            + "the leading zeros of a value such as '0000000000683580'")
                    .isEqualTo(String.class);
            assertThat(columnOf("transactionId").length())
                    .as("the key length catalogued for TRANSACT is 16")
                    .isEqualTo(PRIMARY_KEY_LENGTH);
            assertThat(postedTransaction().getTransactionId())
                    .as("the subject's identifier occupies the full 16-byte key")
                    .hasSize(PRIMARY_KEY_LENGTH);
        }

        @Test
        @DisplayName("the identifier carries no @GeneratedValue, because the service tier generates it")
        void theIdentifierHasNoDatabaseGenerator() {
            assertThat(declaredField("transactionId").getAnnotation(GeneratedValue.class))
                    .as("identifiers come from the descending-browse maximum-key idiom at "
                            + "app/cbl/COTRN02C.cbl:L444-L451 - MOVE HIGH-VALUES, STARTBR, READPREV, "
                            + "ENDBR, then add one - which is inherently racy and retained deliberately "
                            + "for parity. A sequence would change the generated values and break "
                            + "comparison against the legacy baseline, and it would hide the collision "
                            + "that the primary key is meant to surface as a duplicate-key violation")
                    .isNull();
        }

        @Test
        @DisplayName("exactly one field carries @Version, and it has no COBOL counterpart")
        void exactlyOneFieldCarriesVersion() {
            final List<String> versioned = new ArrayList<>();
            for (final Field field : instanceFields()) {
                if (field.getAnnotation(Version.class) != null) {
                    versioned.add(field.getName());
                }
            }

            assertThat(versioned)
                    .as("Transaction is one of exactly four versioned entities - Account, Card, Customer "
                            + "and Transaction - and the counter is the store-level optimistic guard. It "
                            + "detects that some concurrent write happened and nothing more; the "
                            + "business-level field-by-field snapshot comparison is a separate and "
                            + "non-substitutable layer")
                    .containsExactly("version");
            assertThat(declaredField("version").getType())
                    .as("the counter is a Long against a BIGINT column")
                    .isEqualTo(Long.class);

            for (final FieldContract contract : OFFSET_MAP) {
                assertThat(contract.javaProperty())
                        .as("no field of app/cpy/CVTRA05Y.cpy maps to the version column - it is added by "
                                + "the migration, not translated from the copybook")
                        .isNotEqualTo("version");
            }
        }

        @Test
        @DisplayName("the entity does not implement Serializable")
        void theEntityIsNotSerializable() {
            assertThat(Serializable.class.isAssignableFrom(Transaction.class))
                    .as("Java serialization is a deserialization-gadget surface with no use here: the "
                            + "entity crosses a process boundary as JSON through a data transfer object, "
                            + "never as a serialized object graph. Implementing Serializable would also "
                            + "oblige a serialVersionUID, which -Xlint:all reports as the 'serial' "
                            + "warning and -Werror turns into a build failure")
                    .isFalse();
            assertThat(Transaction.class.getInterfaces())
                    .as("the entity implements no interface at all")
                    .isEmpty();
        }

        @Test
        @DisplayName("equality is by primary key alone, so a differing amount does not split identity")
        void equalityIsByPrimaryKeyAlone() {
            final Transaction first = postedTransaction();
            final Transaction second = postedTransaction();
            second.setAmount(new BigDecimal("999.99"));

            assertThat(first)
                    .as("two readings of the row identified by %s are the same entity even when a "
                            + "non-key field differs; comparing the amount here would also drag "
                            + "BigDecimal's scale-sensitive equals into identity", SUBJECT_ID)
                    .isEqualTo(second)
                    .hasSameHashCodeAs(second);

            second.setTransactionId("0000000000683581");
            assertThat(first)
                    .as("a different identifier is a different row")
                    .isNotEqualTo(second);
        }
    }

    @Nested
    @DisplayName("Phase 2 - the amount: NUMERIC(11,2) from S9(09)V99, signed, and never floating point")
    class AmountPrecisionAndSign {

        @Test
        @DisplayName("the amount declares precision 11 and scale 2, one digit narrower than Account's 12")
        void theAmountDeclaresTheNineDigitTier() {
            final Column column = columnOf("amount");

            assertThat(column.precision())
                    .as("TRAN-AMT is PIC S9(09)V99 at app/cpy/CVTRA05Y.cpy:L10 - nine integer digits plus "
                            + "two decimals is eleven characters, matching the 133-143 byte span - so the "
                            + "column is NUMERIC(11,2)")
                    .isEqualTo(AMOUNT_PRECISION);
            assertThat(column.scale())
                    .as("V99 declares exactly two decimal positions")
                    .isEqualTo(AMOUNT_SCALE);
            assertThat(column.precision())
                    .as("Severity High: NUMERIC(12,2) is the S9(10)V99 tier the Account money fields use. "
                            + "Three precision tiers exist and collapsing them either fails schema "
                            + "validation outright or, worse, diverges silently in money: S9(10)V99 to "
                            + "NUMERIC(12,2), S9(09)V99 to NUMERIC(11,2), S9(04)V99 to NUMERIC(6,2)")
                    .isNotEqualTo(ACCOUNT_MONEY_PRECISION);
            assertThat(AMOUNT_PRECISION)
                    .as("the precision is the picture clause's integer digits plus its decimal digits")
                    .isEqualTo(MERCHANT_ID_WIDTH + AMOUNT_SCALE)
                    .isEqualTo(AMOUNT_WIDTH);
        }

        @Test
        @DisplayName("no field of the entity is a float or a double, in any form")
        void noFieldIsFloatingPoint() {
            for (final Field field : instanceFields()) {
                assertThat(field.getType())
                        .as("the security-audit gate asserts zero floating point in any financial field, "
                                + "because binary floating point cannot represent a decimal cent exactly; "
                                + "field '%s' must not be one", field.getName())
                        .isNotIn(float.class, double.class, Float.class, Double.class);
            }

            assertThat(declaredField("amount").getType())
                    .as("money is BigDecimal, and any rounding applied to a value read from here uses "
                            + "RoundingMode.HALF_EVEN")
                    .isEqualTo(BigDecimal.class);
        }

        @Test
        @DisplayName("money compares by compareTo, not by the scale-sensitive equals")
        void moneyComparesByCompareToNotEquals() {
            final BigDecimal twoDecimals = new BigDecimal("100.00");
            final BigDecimal oneDecimal = new BigDecimal("100.0");

            assertThat(twoDecimals.scale())
                    .as("the two operands must genuinely differ in scale, or the comparison below proves "
                            + "nothing")
                    .isNotEqualTo(oneDecimal.scale());
            assertThat(twoDecimals.equals(oneDecimal))
                    .as("BigDecimal.equals compares unscaled value AND scale, so the same amount of money "
                            + "written at two scales is equals-unequal. Using equals on money is a "
                            + "correctness bug that passes review because it looks idiomatic")
                    .isFalse();
            assertThat(twoDecimals.compareTo(oneDecimal))
                    .as("compareTo compares numeric value alone, which is what 'the same amount of money' "
                            + "means")
                    .isZero();

            final Transaction subject = postedTransaction();
            subject.setAmount(new BigDecimal("100.0"));

            assertThat(subject.getAmount())
                    .as("a value supplied at scale 1 is stored verbatim and is not rescaled by the "
                            + "entity, so it must be read back with compareTo")
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(subject.getAmount().scale())
                    .as("the entity applies no rescaling of its own; scale normalisation belongs to the "
                            + "layer that writes the fixed-width record, not to the field guard")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a negative amount keeps its sign and is never normalised to an absolute value")
        void aNegativeAmountKeepsItsSign() {
            final Transaction subject = postedTransaction();
            subject.setAmount(new BigDecimal("-123.45"));

            assertThat(subject.getAmount())
                    .as("negative amounts are legitimate and load-bearing. The sign test at "
                            + "app/cbl/CBTRN02C.cbl:L548 routes a non-negative amount to the cycle credit "
                            + "and the ELSE at :L551 adds a negative amount to the cycle DEBIT "
                            + "accumulator, so that accumulator legitimately holds negative values - "
                            + "which is precisely why the over-limit formula subtracts it. Any abs() on "
                            + "this path corrupts that arithmetic")
                    .isEqualByComparingTo(new BigDecimal("-123.45"));
            assertThat(subject.getAmount().signum())
                    .as("the stored sign is negative, not folded to positive")
                    .isEqualTo(-1);
            assertThat(subject.getAmount())
                    .as("the value is not the absolute value of itself")
                    .isNotEqualByComparingTo(new BigDecimal("123.45"));
        }

        @Test
        @DisplayName("the fixture's 50 negative and 250 non-negative amounts are all accepted verbatim")
        void theWholeFixtureAmountDomainIsAccepted() {
            final FixtureLoader.FixtureData fixture =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            int negative = 0;
            int nonNegative = 0;

            for (int index = 0; index < fixture.recordCount(); index++) {
                final BigDecimal decoded =
                        fixture.signedDecimal(index, AMOUNT_START_BYTE, FixtureLoader.AMOUNT_FIELD_WIDTH);
                if (decoded.signum() < 0) {
                    negative++;
                } else {
                    nonNegative++;
                }

                final Transaction subject = postedTransaction();
                subject.setAmount(decoded);
                assertThat(subject.getAmount())
                        .as("every amount the system of record actually carries must be storable "
                                + "verbatim; row %d was rejected or altered", index)
                        .isEqualByComparingTo(decoded);
            }

            assertThat(negative)
                    .as("the trailing zoned-decimal overpunch of bytes 133-143 is '}' or 'J' through 'R' "
                            + "on exactly 50 of the 300 rows of app/data/ASCII/dailytran.txt, so the "
                            + "fixture genuinely exercises the cycle-debit branch and must not be "
                            + "normalised")
                    .isEqualTo(FIXTURE_NEGATIVE_AMOUNTS);
            assertThat(nonNegative)
                    .as("the remaining 250 rows carry '{' or 'A' through 'I'")
                    .isEqualTo(FIXTURE_POSITIVE_AMOUNTS);
            assertThat(negative + nonNegative).isEqualTo(FIXTURE_ROW_COUNT);
        }

        @ParameterizedTest
        @ValueSource(strings = {"999999999.99", "-999999999.99", "0.00", "0", "5", "-0.01", "1000000.00"})
        @DisplayName("every value the nine-digit signed picture clause admits is accepted")
        void theRepresentableDomainIsAccepted(final String candidate) {
            assertThatNoException()
                    .as("S9(09)V99 spans -999999999.99 to 999999999.99 inclusive and is symmetric about "
                            + "zero; a scale below two denotes the same money and is not rescaled")
                    .isThrownBy(() -> postedTransaction().setAmount(new BigDecimal(candidate)));
        }

        @Test
        @DisplayName("the two magnitude boundaries are inclusive and the first value beyond each is rejected")
        void theMagnitudeBoundariesAreInclusive() {
            assertThatNoException()
                    .as("999999999.99 is the widest S9(09)V99 value and must load")
                    .isThrownBy(() -> postedTransaction().setAmount(MAX_AMOUNT));
            assertThatNoException()
                    .as("-999999999.99 is the narrowest and must load")
                    .isThrownBy(() -> postedTransaction().setAmount(MIN_AMOUNT));

            assertThatIllegalArgumentException()
                    .as("a tenth integer digit does not fit nine, and NUMERIC(11,2) would refuse it at "
                            + "flush time with no caller frame")
                    .isThrownBy(() -> postedTransaction().setAmount(new BigDecimal("1000000000.00")))
                    .withMessageContaining("TRAN-AMT")
                    .withMessageContaining("S9(09)V99")
                    .withNoCause();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> postedTransaction().setAmount(new BigDecimal("-1000000000.00")))
                    .withMessageContaining("TRAN-AMT")
                    .withNoCause();
        }

        @Test
        @DisplayName("a third decimal digit is rejected rather than silently rounded")
        void aThirdDecimalDigitIsRejected() {
            assertThatIllegalArgumentException()
                    .as("V99 declares two decimal positions. PostgreSQL rounds an over-scaled NUMERIC "
                            + "insert half away from zero rather than refusing it - a silent alteration, "
                            + "and by a rounding mode that is not the HALF_EVEN the batch layer uses - so "
                            + "the value is refused here where the caller is still on the stack")
                    .isThrownBy(() -> postedTransaction().setAmount(new BigDecimal("1.234")))
                    .withMessageContaining("at most 2 decimal digits")
                    .withMessageContaining("HALF_EVEN")
                    .withNoCause();
        }
    }

    @Nested
    @DisplayName("Phase 3 - the timestamps: three renderings, both columns CHAR(26) text")
    class TimestampText {

        @Test
        @DisplayName("both timestamp fields are String, 26 wide, and carry no temporal mapping")
        void bothTimestampFieldsAreText() {
            for (final String property : List.of("origTs", "procTs")) {
                final Field field = declaredField(property);

                assertThat(field.getType())
                        .as("Severity High: %s is PIC X(26) at app/cpy/CVTRA05Y.cpy:L16-L17 - a character "
                                        + "field. A LocalDateTime, Instant or java.sql.Timestamp cannot "
                                        + "carry the separator geometry, cannot hold the 26-space blank "
                                        + "the fixture supplies, and cannot represent all three producer "
                                        + "formats at once", property)
                        .isEqualTo(String.class);
                assertThat(field.getAnnotation(Temporal.class))
                        .as("%s must carry no @Temporal: there is no temporal value to convert", property)
                        .isNull();
                assertThat(columnOf(property).length())
                        .as("%s maps to CHAR(26)", property)
                        .isEqualTo(TIMESTAMP_WIDTH);
            }

            assertThat(FixedClockProvider.TIMESTAMP_LENGTH)
                    .as("the shared timestamp width agrees with the copybook")
                    .isEqualTo(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("rendering 1, online: yyyy-MM-dd HH:mm:ss.000000 with a SPACE at byte 11")
        void theOnlineRenderingHasASpaceAtByteEleven() {
            final String rendered = FixedClockProvider.onlineTimestamp(FixedClockProvider.canonicalClock());

            assertThat(rendered)
                    .as("app/cbl/COBIL00C.cbl:L262-L265 initialises WS-TIMESTAMP, moves the ten-character "
                            + "date into bytes 1-10 and the eight-character time into bytes 12-19, then "
                            + "zeroes the six microsecond digits")
                    .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP)
                    .hasSize(TIMESTAMP_WIDTH);
            assertThat(rendered.charAt(10))
                    .as("byte 11 is a SPACE. The move at :L263 fills bytes 1-10 and the move at :L264 "
                            + "starts at byte 12, so byte 11 keeps the blank that INITIALIZE left and that "
                            + "the FILLER PIC X(01) VALUE ' ' of the layout declares")
                    .isEqualTo(' ');
            assertThat(rendered.charAt(13))
                    .as("TIMESEP(':') at app/cbl/COBIL00C.cbl:L259 makes the time separator a colon")
                    .isEqualTo(':');
            assertThat(rendered.charAt(19))
                    .as("byte 20 is the period the layout declares before the microsecond digits")
                    .isEqualTo('.');
            assertThat(rendered.substring(20))
                    .as("MOVE ZEROS TO WS-TIMESTAMP-TM-MS6 at :L265 makes bytes 21-26 six literal zeros; "
                            + "sub-second precision is overwritten, not rounded")
                    .isEqualTo("000000");
        }

        @Test
        @DisplayName("rendering 2, batch: yyyy-MM-dd-HH.mm.ss.SS0000 with a DASH at byte 11")
        void theBatchRenderingHasADashAtByteEleven() {
            final String rendered = FixedClockProvider.batchTimestamp(FixedClockProvider.canonicalClock());

            assertThat(rendered)
                    .as("Z-GET-DB2-FORMAT-TIMESTAMP is character-identical at app/cbl/CBTRN02C.cbl:L692 "
                            + "and app/cbl/CBACT04C.cbl:L613, and the comment at CBTRN02C:L149 states the "
                            + "shape as EEEE-MM-DD-UU.MM.SS.HH0000")
                    .isEqualTo(FixedClockProvider.CANONICAL_BATCH_TIMESTAMP)
                    .hasSize(TIMESTAMP_WIDTH);
            assertThat(rendered.charAt(10))
                    .as("MOVE '-' TO DB2-STREEP-1 DB2-STREEP-2 DB2-STREEP-3 at app/cbl/CBTRN02C.cbl:L702 "
                            + "fills THREE separator bytes with a dash, so byte 11 - between DD and HH - "
                            + "is a DASH and not the space the online rendering has")
                    .isEqualTo('-');
            assertThat(rendered.charAt(13))
                    .as("MOVE '.' TO DB2-DOT-1 DB2-DOT-2 DB2-DOT-3 at :L703 makes the time separators "
                            + "periods, where the online rendering uses colons")
                    .isEqualTo('.');
            assertThat(rendered.substring(22))
                    .as("DB2-MIL PIC 9(002) at :L173 holds HUNDREDTHS and MOVE '0000' TO DB2-REST at :L701 "
                            + "appends four literal zeros. Format to hundredths then append the zeros - "
                            + "never to nanoseconds, which would need nine fractional digits and yield a "
                            + "29-character string that the CHAR(26) column cannot hold")
                    .isEqualTo("0000");
            assertThat(rendered.length())
                    .as("the REDEFINES at :L161-L174 sums to 4+1+2+1+2+1+2+1+2+1+2+1+2+4 = 26")
                    .isEqualTo(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("the two generated renderings differ only in their separators, never in length")
        void theTwoRenderingsDifferOnlyInSeparators() {
            final String online = FixedClockProvider.onlineTimestamp(FixedClockProvider.canonicalClock());
            final String batch = FixedClockProvider.batchTimestamp(FixedClockProvider.canonicalClock());

            assertThat(online).hasSameSizeAs(batch);
            assertThat(online)
                    .as("both renderings coexist in one record: app/cbl/CBTRN02C.cbl:L436 passes the "
                            + "originating timestamp through unchanged while :L438 generates the "
                            + "processing timestamp in the batch shape, so a test that assumes one "
                            + "rendering for both fields asserts a shape the source never produces")
                    .isNotEqualTo(batch);
            assertThat(online.substring(0, 10))
                    .as("the date portion is identical - the divergence begins at byte 11")
                    .isEqualTo(batch.substring(0, 10));
        }

        @Test
        @DisplayName("rendering 3, pass-through: raw screen text is left-justified and space-filled, unaltered")
        void thePassThroughRenderingIsUnaltered() {
            final String screenField = "2022-06-10";
            final String moved = FixedClockProvider.passThroughTimestamp(screenField);

            assertThat(moved)
                    .as("app/cbl/COTRN02C.cbl:L464-L465 move TORIGDTI and TPROCDTI, declared PIC X(10), "
                            + "into TRAN-ORIG-TS and TRAN-PROC-TS, declared PIC X(26). A COBOL "
                            + "alphanumeric move left-justifies and space-fills to the receiving width, so "
                            + "this is a width-changing operation and not an identity. (The cited locator "
                            + "is :L464-L465; :L454-L455 move TRNSRCI and TDESCI instead.)")
                    .startsWith(screenField)
                    .hasSize(TIMESTAMP_WIDTH);
            assertThat(moved.substring(screenField.length()))
                    .as("bytes 11-26 become sixteen spaces")
                    .isEqualTo(" ".repeat(TIMESTAMP_WIDTH - screenField.length()));

            final Transaction subject = postedTransaction();
            subject.setOrigTs(moved);
            subject.setProcTs(moved);
            assertThat(subject.getOrigTs())
                    .as("no formatting, validation or parsing is applied on the way in")
                    .isEqualTo(moved);
            assertThat(subject.getProcTs()).isEqualTo(moved);
        }

        @Test
        @DisplayName("all three renderings round-trip through both columns, and none is rejected")
        void allThreeRenderingsRoundTripThroughBothColumns() {
            final List<String> renderings = List.of(
                    FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP,
                    FixedClockProvider.CANONICAL_BATCH_TIMESTAMP,
                    FixedClockProvider.passThroughTimestamp("2022-06-10"),
                    BLANK_TIMESTAMP);

            for (final String rendering : renderings) {
                final Transaction subject = postedTransaction();
                subject.setOrigTs(rendering);
                subject.setProcTs(rendering);

                assertThat(subject.getOrigTs())
                        .as("the column is text precisely so that every producer's shape survives; a "
                                + "format guard here would reject one of the three")
                        .isEqualTo(rendering)
                        .hasSize(TIMESTAMP_WIDTH);
                assertThat(subject.getProcTs()).isEqualTo(rendering);
            }
        }

        @Test
        @DisplayName("a 26-space timestamp round-trips, being the normal staged state of every fixture row")
        void aBlankTimestampRoundTrips() {
            final Transaction subject = postedTransaction();
            subject.setProcTs(BLANK_TIMESTAMP);

            assertThat(subject.getProcTs())
                    .as("all 300 rows of app/data/ASCII/dailytran.txt carry 26 spaces at bytes 305-330, "
                            + "because the posting job is what fills the processing timestamp in. A "
                            + "not-blank guard, a trim to null, or a temporal type would reject the entire "
                            + "reference fixture")
                    .isEqualTo(BLANK_TIMESTAMP)
                    .isBlank()
                    .hasSize(TIMESTAMP_WIDTH);
        }

        @Test
        @DisplayName("the fixture proves the split: online text at 279-304, 26 spaces at 305-330, on all 300 rows")
        void theFixtureProvesTheTwoColumnSplit() {
            final FixtureLoader.FixtureData fixture =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final Set<String> origValues = new LinkedHashSet<>();
            final Set<String> procValues = new LinkedHashSet<>();

            for (int index = 0; index < fixture.recordCount(); index++) {
                origValues.add(fixture.field(index, ORIG_TS_START_BYTE, TIMESTAMP_WIDTH));
                procValues.add(fixture.field(index, PROC_TS_START_BYTE, TIMESTAMP_WIDTH));
            }

            assertThat(origValues)
                    .as("bytes 279-304 hold exactly ONE distinct value across all 300 rows, and it is the "
                            + "ONLINE rendering - space separated, six-digit fraction - not the "
                            + "dash-separated batch one")
                    .containsExactly(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
            assertThat(procValues)
                    .as("bytes 305-330 are blank on every row")
                    .containsExactly(BLANK_TIMESTAMP);
        }

        @Test
        @DisplayName("the report filter's ten-character date prefix is a lexical string comparison")
        void theReportFilterComparesATextPrefix() {
            final String prefix =
                    FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP.substring(0, DATE_PREFIX_LENGTH);

            assertThat(prefix)
                    .as("TRAN-PROC-DT,305,10,CH at app/proc/TRANREPT.prc:L40 declares ten CHARACTER bytes "
                            + "at offset 305, so the report's inclusive date range is a lexical comparison "
                            + "on the timestamp's first ten characters and not a date comparison")
                    .isEqualTo("2022-06-10")
                    .hasSize(DATE_PREFIX_LENGTH);
            assertThat(prefix.compareTo("2022-01-01") >= 0 && prefix.compareTo("2022-07-06") <= 0)
                    .as("the ISO-ordered text sorts identically to the dates it denotes, which is why the "
                            + "PARM-START-DATE and PARM-END-DATE literals at app/proc/TRANREPT.prc:L41-L42 "
                            + "work as a string range at all")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Phase 4 - the source column is free text, never the TransactionSource enum")
    class TransactionSourceIsFreeText {

        @Test
        @DisplayName("transactionSource is a String with no enum mapping and no converter")
        void theSourceColumnIsAString() {
            final Field field = declaredField("transactionSource");

            assertThat(field.getType())
                    .as("Severity Blocker: TRAN-SOURCE is PIC X(10) free text at "
                            + "app/cpy/CVTRA05Y.cpy:L8. A TransactionSource enum exists in the sibling "
                            + "com.cardemo.model.enums package but is deliberately not the persisted type "
                            + "here")
                    .isEqualTo(String.class);
            assertThat(field.getAnnotation(Enumerated.class))
                    .as("no @Enumerated: the column is not a closed set")
                    .isNull();
            assertThat(columnOf("transactionSource").length())
                    .as("the column is CHAR(10), the picture-clause width")
                    .isEqualTo(SOURCE_WIDTH);
        }

        @Test
        @DisplayName("the entity references nothing from com.cardemo.model.enums, on any field or annotation")
        void theEntityReferencesNoEnumType() {
            for (final Field field : instanceFields()) {
                assertThat(packageNameOf(field.getType()))
                        .as("field '%s' must not be typed from the enums package", field.getName())
                        .isNotEqualTo("com.cardemo.model.enums");

                for (final Annotation annotation : field.getAnnotations()) {
                    assertThat(packageNameOf(annotation.annotationType()))
                            .as("no annotation on '%s' may come from the enums package either - an "
                                    + "@Enumerated or a converter would narrow the column just as "
                                    + "effectively as changing its type", field.getName())
                            .isNotEqualTo("com.cardemo.model.enums");
                }
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {SOURCE_SYSTEM, SOURCE_POS_TERM, SOURCE_OPERATOR})
        @DisplayName("all three real source values round-trip byte-exactly with their ten-byte padding")
        void theRealSourceValuesRoundTrip(final String source) {
            final Transaction subject = postedTransaction();
            subject.setTransactionSource(source);

            assertThat(subject.getTransactionSource())
                    .as("the value is stored verbatim - never trimmed, never case-folded, never mapped")
                    .isEqualTo(source)
                    .hasSize(SOURCE_WIDTH);
        }

        @Test
        @DisplayName("the two literals that do have a MOVE site pad to exactly ten bytes")
        void theProgramLiteralsPadToTenBytes() {
            assertThat(SOURCE_SYSTEM)
                    .as("MOVE 'System' TO TRAN-SOURCE at app/cbl/CBACT04C.cbl:L484 writes a six-character "
                            + "literal into a ten-byte field, so the stored value carries four trailing "
                            + "spaces. Note the mixed case: the literal is 'System', not 'SYSTEM'")
                    .isEqualTo("System" + " ".repeat(4))
                    .hasSize(SOURCE_WIDTH);
            assertThat(SOURCE_POS_TERM)
                    .as("MOVE 'POS TERM' TO TRAN-SOURCE at app/cbl/COBIL00C.cbl:L222 writes an "
                            + "eight-character literal, so two trailing spaces")
                    .isEqualTo("POS TERM" + " ".repeat(2))
                    .hasSize(SOURCE_WIDTH);
        }

        @Test
        @DisplayName("the fixture's two source values are POS TERM on 250 rows and OPERATOR on 50")
        void theFixtureCarriesTwoSourceValues() {
            final FixtureLoader.FixtureData fixture =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final Set<String> distinct = new LinkedHashSet<>();
            int operatorRows = 0;
            int posTermRows = 0;

            for (int index = 0; index < fixture.recordCount(); index++) {
                final String source = fixture.field(index, SOURCE_START_BYTE, SOURCE_WIDTH);
                distinct.add(source);
                if (SOURCE_OPERATOR.equals(source)) {
                    operatorRows++;
                } else if (SOURCE_POS_TERM.equals(source)) {
                    posTermRows++;
                }

                assertThat(postedTransaction().getTransactionSource())
                        .as("the subject's own source value is one the corpus writes")
                        .isEqualTo(SOURCE_POS_TERM);
            }

            assertThat(distinct)
                    .as("bytes 23-32 of app/data/ASCII/dailytran.txt yield exactly two values")
                    .containsExactlyInAnyOrder(SOURCE_POS_TERM, SOURCE_OPERATOR);
            assertThat(posTermRows).isEqualTo(FIXTURE_POSITIVE_AMOUNTS);
            assertThat(operatorRows)
                    .as("Severity Blocker: 'OPERATOR' has NO literal MOVE site anywhere in the corpus - it "
                            + "reaches the column purely through the pass-through at "
                            + "app/cbl/CBTRN02C.cbl:L428, which moves DALYTRAN-SOURCE into TRAN-SOURCE "
                            + "without examining it. An enum-typed column would reject these 50 rows "
                            + "outright, which is one fifth of the reference fixture")
                    .isEqualTo(FIXTURE_NEGATIVE_AMOUNTS);
        }

        @Test
        @DisplayName("every source value the fixture carries is storable, including the one with no literal")
        void everyFixtureSourceValueIsStorable() {
            final FixtureLoader.FixtureData fixture =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

            for (int index = 0; index < fixture.recordCount(); index++) {
                final String source = fixture.field(index, SOURCE_START_BYTE, SOURCE_WIDTH);
                final Transaction subject = postedTransaction();

                assertThatNoException()
                        .as("row %d carries a source value the seed migration must be able to load", index)
                        .isThrownBy(() -> subject.setTransactionSource(source));
                assertThat(subject.getTransactionSource()).isEqualTo(source);
            }
        }
    }

    @Nested
    @DisplayName("Phase 5 - the offsets three independent legacy artefacts agree on")
    class OffsetCorroboration {

        @Test
        @DisplayName("TRAN-CARD-NUM,263,16 and TRAN-PROC-DT,305,10 match the copybook arithmetic exactly")
        void theDfsortSymbolDefinitionsMatchTheCopybook() {
            final FieldContract cardNumber = contractFor("TRAN-CARD-NUM");
            final FieldContract procTs = contractFor("TRAN-PROC-TS");

            assertThat(cardNumber.startByte())
                    .as("app/proc/TRANREPT.prc:L39 declares TRAN-CARD-NUM,263,16,ZD under the //SYMNAMES "
                            + "DD * at :L38. The offset and the width both agree with accumulating the "
                            + "picture widths of app/cpy/CVTRA05Y.cpy:L5-L14")
                    .isEqualTo(CARD_NUMBER_START_BYTE);
            assertThat(cardNumber.width()).isEqualTo(CARD_NUMBER_WIDTH);
            assertThat(procTs.startByte())
                    .as("app/proc/TRANREPT.prc:L40 declares TRAN-PROC-DT,305,10,CH - offset 305 is where "
                            + "the copybook arithmetic puts TRAN-PROC-TS, and the ten bytes are its date "
                            + "prefix rather than the whole 26-byte field")
                    .isEqualTo(PROC_TS_START_BYTE);
            assertThat(DATE_PREFIX_LENGTH)
                    .as("the filter reads ten of the field's twenty-six bytes")
                    .isLessThan(procTs.width());
        }

        @Test
        @DisplayName("the CREASTMT sort keys 263,16 and 1,16 are the card number and the transaction id")
        void theStatementSortKeysAreTheCardNumberAndTheIdentifier() {
            assertThat(contractFor("TRAN-CARD-NUM").startByte())
                    .as("app/jcl/CREASTMT.JCL:L53 sorts FIELDS=(263,16,CH,A,1,16,CH,A) - the major key at "
                            + "263 for 16 bytes is TRAN-CARD-NUM. This member is the sole uppercase .JCL "
                            + "file in app/jcl and carries CRLF line endings, so the carriage returns must "
                            + "be stripped before the cited line numbers line up")
                    .isEqualTo(CARD_NUMBER_START_BYTE);
            assertThat(contractFor("TRAN-ID").startByte())
                    .as("the minor key at 1 for 16 bytes is TRAN-ID, which is also the primary key")
                    .isEqualTo(1);
            assertThat(contractFor("TRAN-ID").width()).isEqualTo(TRANSACTION_ID_WIDTH);
        }

        @Test
        @DisplayName("the OUTREC projection's 279:279,50 presupposes origTs at 279 and procTs at 305")
        void theProjectionPresupposesTheTimestampOffsets() {
            final FieldContract origTs = contractFor("TRAN-ORIG-TS");
            final FieldContract procTs = contractFor("TRAN-PROC-TS");

            assertThat(origTs.startByte())
                    .as("app/jcl/CREASTMT.JCL:L54 projects OUTREC FIELDS=(1:263,16,17:1,262,279:279,50), "
                            + "whose third element copies 50 bytes starting at byte 279 - exactly where "
                            + "TRAN-ORIG-TS begins")
                    .isEqualTo(ORIG_TS_START_BYTE);
            assertThat(origTs.endByte() + 1)
                    .as("the 26 bytes of TRAN-ORIG-TS are followed immediately by TRAN-PROC-TS at 305")
                    .isEqualTo(procTs.startByte());
            assertThat(PROJECTION_LENGTH)
                    .as("50 projected bytes cover the whole 26-byte originating timestamp and only 24 of "
                            + "the 26 processing-timestamp bytes, so the projection silently truncates two "
                            + "bytes and drops the 20-byte filler entirely. That truncation is asserted by "
                            + "the batch tier; what is asserted here is the offset arithmetic it "
                            + "presupposes")
                    .isEqualTo(TIMESTAMP_WIDTH + (TIMESTAMP_WIDTH - 2));
            assertThat(origTs.startByte() + PROJECTION_LENGTH - 1)
                    .as("the projection's last copied byte is 328, two short of the record's 330th "
                            + "populated byte")
                    .isEqualTo(POPULATED_LENGTH - 2);
        }

        @Test
        @DisplayName("AXRKP 304 is zero-based, so the 26-byte alternate key is procTs at 305-330")
        void theAlternateKeyIsTheProcessingTimestamp() {
            final FieldContract procTs = contractFor("TRAN-PROC-TS");

            assertThat(ALTERNATE_KEY_DISPLACEMENT + 1)
                    .as("app/catlg/LISTCAT.txt:L3676 reports AXRKP 304 for TRANSACT.VSAM.AIX. A relative "
                            + "key position is a zero-based displacement, so the key begins at one-based "
                            + "byte 305")
                    .isEqualTo(procTs.startByte());
            assertThat(ALTERNATE_KEY_LENGTH)
                    .as("app/catlg/LISTCAT.txt:L3674 reports KEYLEN 26. Only two fields are 26 bytes wide, "
                            + "and the displacement settles which of them it is")
                    .isEqualTo(procTs.width());
            assertThat(procTs.javaProperty())
                    .as("the alternate index therefore becomes a finder derived from procTs plus a B-tree "
                            + "index on tran_proc_ts; the index is owned by the migration, not by this "
                            + "entity")
                    .isEqualTo("procTs");
        }

        @Test
        @DisplayName("the alternate index is non-unique, so no unique constraint may appear on procTs")
        void theAlternateIndexIsNonUnique() {
            assertThat(columnOf("procTs").unique())
                    .as("Severity High: app/catlg/LISTCAT.txt:L3678 declares the alternate index "
                            + "NONUNIQKEY in so many words, and the fixture bears it out - all 300 rows "
                            + "share one processing-timestamp value, namely 26 spaces. A unique constraint "
                            + "would reject 299 of the 300 legitimate legacy rows")
                    .isFalse();
            assertThat(Transaction.class.getAnnotation(Table.class).uniqueConstraints())
                    .as("no table-level unique constraint may cover tran_proc_ts either; the primary key "
                            + "is the only uniqueness the cluster declares")
                    .isEmpty();
            assertThat(columnOf("cardNumber").unique())
                    .as("the card number is not unique either - one card has many transactions")
                    .isFalse();
        }

        @Test
        @DisplayName("the catalogue tallies corroborate that this is the third of three alternate indexes")
        void theCatalogueTalliesCorroborate() {
            assertThat(CATALOGUE_AIX_COUNT)
                    .as("app/catlg/LISTCAT.txt:L3939 reports AIX 3 - CARDDATA.VSAM.AIX, "
                            + "CARDXREF.VSAM.AIX and TRANSACT.VSAM.AIX - and :L3946 reports PATH 3, one "
                            + "path per alternate index")
                    .isEqualTo(CATALOGUE_PATH_COUNT)
                    .isEqualTo(3);
            assertThat(CATALOGUE_CLUSTER_COUNT)
                    .as("app/catlg/LISTCAT.txt:L3941 reports CLUSTER 10, of which TRANSACT is one; each "
                            + "becomes exactly one entity")
                    .isEqualTo(10);
            assertThat(CATALOGUE_GDG_COUNT)
                    .as("app/catlg/LISTCAT.txt:L3943 reports GDG 7, none of which is this cluster - a "
                            + "generation data group becomes an object-storage key prefix, not a table")
                    .isEqualTo(7);
        }

        /**
         * Returns the offset-table entry for a copybook field name.
         *
         * @param cobolField the COBOL field name
         * @return its contract
         */
        private FieldContract contractFor(final String cobolField) {
            for (final FieldContract contract : OFFSET_MAP) {
                if (contract.cobolField().equals(cobolField)) {
                    return contract;
                }
            }
            throw new AssertionError("the offset table must describe " + cobolField
                    + ", which app/cpy/CVTRA05Y.cpy declares");
        }
    }

    @Nested
    @DisplayName("Phase 6 - the DailyTransaction staging twin shares no ancestry with this entity")
    class StagingTwinSeparation {

        @Test
        @DisplayName("Transaction extends Object directly and carries no inheritance mapping")
        void transactionHasNoSuperclassAndNoInheritanceMapping() {
            assertThat(Transaction.class.getSuperclass())
                    .as("there is no base entity and no auditable superclass; the entity extends Object")
                    .isEqualTo(Object.class);
            assertThat(Transaction.class.getAnnotation(MappedSuperclass.class))
                    .as("no @MappedSuperclass")
                    .isNull();
            assertThat(Transaction.class.getAnnotation(Inheritance.class))
                    .as("no @Inheritance strategy")
                    .isNull();
        }

        @Test
        @DisplayName("the twin declares the same 350-byte shape yet neither type is assignable to the other")
        void theTwinIsNotInterchangeable() {
            final Class<?> twin = stagingTwin();

            assertThat(twin.getSuperclass())
                    .as("app/cpy/CVTRA06Y.cpy:L4 declares 01 DALYTRAN-RECORD with the same 350-byte shape "
                            + "under DALYTRAN- prefixes, but the two are separate objects with different "
                            + "lifecycles, so the twin extends Object too")
                    .isEqualTo(Object.class);
            assertThat(twin.getAnnotation(MappedSuperclass.class)).isNull();
            assertThat(twin.getAnnotation(Inheritance.class)).isNull();

            assertThat(Transaction.class.isAssignableFrom(twin))
                    .as("Severity High: a shared superclass would let a change made for staging reasons "
                            + "silently alter the posted-master contract, and it would hand the staging "
                            + "table a version column it must not have")
                    .isFalse();
            assertThat(twin.isAssignableFrom(Transaction.class))
                    .as("the relationship is symmetric: neither is a subtype of the other")
                    .isFalse();
        }

        @Test
        @DisplayName("the two share no interface, because neither implements one")
        void theTwoShareNoInterface() {
            final Set<Class<?>> shared = new LinkedHashSet<>(List.of(Transaction.class.getInterfaces()));
            shared.retainAll(List.of(stagingTwin().getInterfaces()));

            assertThat(Transaction.class.getInterfaces())
                    .as("the posted master implements nothing")
                    .isEmpty();
            assertThat(shared)
                    .as("with neither implementing an interface there is no shared abstraction through "
                            + "which one could be substituted for the other")
                    .isEmpty();
        }

        @Test
        @DisplayName("only the posted master is versioned: the staging twin has no version column at all")
        void onlyThePostedMasterIsVersioned() {
            assertThat(declaredField("version").getAnnotation(Version.class))
                    .as("TRANSACT is a keyed cluster that the online tier updates in place, so it needs "
                            + "the store-level optimistic guard")
                    .isNotNull();

            int twinVersionFields = 0;
            for (final Field field : stagingTwin().getDeclaredFields()) {
                if (field.getAnnotation(Version.class) != null) {
                    twinVersionFields++;
                }
            }

            assertThat(twinVersionFields)
                    .as("Severity High: the staging twin is a sequential dataset image that is loaded and "
                            + "consumed, never updated in place, so it carries no version column, no "
                            + "foreign key and no catalogue entry. Unifying the two would give it one")
                    .isZero();
        }

        @Test
        @DisplayName("the twin's constructor takes fourteen arguments to this entity's thirteen")
        void theConstructorArityDiffers() {
            assertThat(publicConstructorParameterCount(Transaction.class))
                    .as("the posted master is built from the thirteen copybook fields of "
                            + "app/cpy/CVTRA05Y.cpy:L5-L17; the version counter is the provider's to "
                            + "maintain and the filler is not modelled")
                    .isEqualTo(13);
            assertThat(publicConstructorParameterCount(stagingTwin()))
                    .as("the staging twin takes a fourteenth leading argument, a Java-side surrogate "
                            + "sequence, because the sequential dataset it images has no key of its own. "
                            + "The arities alone make the two structurally non-substitutable")
                    .isEqualTo(14);
        }

        /**
         * Loads the staging twin reflectively rather than importing it.
         *
         * <p>Resolving the class by name keeps this test's compile-time dependency surface to the entity
         * under test, which is the point of the assertion: a file that imported both types would be
         * asserting their separation while itself coupling them.
         *
         * @return the {@code DailyTransaction} class
         */
        private Class<?> stagingTwin() {
            try {
                return Class.forName("com.cardemo.model.entity.DailyTransaction");
            } catch (final ClassNotFoundException cause) {
                throw new AssertionError("com.cardemo.model.entity.DailyTransaction must exist: "
                        + "app/cpy/CVTRA06Y.cpy declares the staging layout and the posting job stages "
                        + "through it", cause);
            }
        }

        /**
         * Returns the parameter count of the widest public constructor of a type.
         *
         * @param type the type to inspect
         * @return the greatest public constructor arity
         */
        private int publicConstructorParameterCount(final Class<?> type) {
            int widest = 0;
            for (final var constructor : type.getConstructors()) {
                widest = Math.max(widest, constructor.getParameterCount());
            }
            return widest;
        }
    }

    @Nested
    @DisplayName("Phase 7 - toString redacts the card number and the merchant detail")
    class Redaction {

        @Test
        @DisplayName("the card number never appears in toString")
        void theCardNumberIsNeverRendered() {
            final Transaction subject = postedTransaction();

            assertThat(subject.toString())
                    .as("a primary account number in a log line is a data-protection incident, and "
                            + "toString is the single most likely route for one to get there because "
                            + "logging frameworks, exception messages, collection renderings and debuggers "
                            + "all call it implicitly. Log masking is the backstop; not emitting the value "
                            + "is the primary defence. Failure is identified by transaction %s and the "
                            + "offending value is deliberately not quoted into this message", SUBJECT_ID)
                    .doesNotContain(SENTINEL_CARD_NUMBER);
        }

        @ParameterizedTest
        @ValueSource(strings = {SENTINEL_MERCHANT_NAME, SENTINEL_MERCHANT_CITY, SENTINEL_MERCHANT_ZIP})
        @DisplayName("the merchant name, city and postal code never appear in toString either")
        void theMerchantDetailIsNeverRendered(final String sentinel) {
            assertThat(postedTransaction().toString())
                    .as("the merchant fields are not secret, but they add no diagnostic value and would "
                            + "inflate every rendered line, so they are omitted under least privilege. "
                            + "Failure is identified by transaction %s", SUBJECT_ID)
                    .doesNotContain(sentinel);
        }

        @Test
        @DisplayName("toString still identifies which row it refers to")
        void toStringStillIdentifiesTheRow()  {
            final Transaction subject = postedTransaction();

            assertThat(subject.toString())
                    .as("a rendering that identifies nothing is useless for diagnosis, so the identifier "
                            + "is included; it is the minimum that says which row a log line is about")
                    .contains(SUBJECT_ID)
                    .startsWith("Transaction{");
        }

        @Test
        @DisplayName("no companion method renders the full record")
        void noCompanionMethodRendersTheFullRecord() {
            final List<String> renderingMethods = new ArrayList<>();
            for (final var method : Transaction.class.getDeclaredMethods()) {
                final String name = method.getName();
                if (!method.isSynthetic() && method.getParameterCount() == 0
                        && method.getReturnType() == String.class
                        && !name.startsWith("get") && !"toString".equals(name)) {
                    renderingMethods.add(name);
                }
            }

            assertThat(renderingMethods)
                    .as("a toDebugString, toFullString or masking helper is an invitation to route the "
                            + "card number into a log by accident, so none exists")
                    .isEmpty();
        }

        @Test
        @DisplayName("the sentinel values really are present on the subject, so the omissions mean something")
        void theSentinelsArePresentOnTheSubject() {
            final Transaction subject = postedTransaction();

            assertThat(subject.getCardNumber())
                    .as("if the subject carried a blank card number the redaction assertions would pass "
                            + "vacuously, so the value is asserted present on the entity itself")
                    .isNotBlank()
                    .hasSize(CARD_NUMBER_WIDTH);
            assertThat(subject.getMerchantName()).isEqualTo(SENTINEL_MERCHANT_NAME);
            assertThat(subject.getMerchantCity()).isEqualTo(SENTINEL_MERCHANT_CITY);
            assertThat(subject.getMerchantZip()).isEqualTo(SENTINEL_MERCHANT_ZIP);
        }
    }

    @Nested
    @DisplayName("Phase 8 - scope: no associations and no reach outside the model layer")
    class ScopeBoundaries {

        @Test
        @DisplayName("no field carries a JPA association of any kind")
        void noFieldCarriesAnAssociation() {
            for (final Field field : instanceFields()) {
                assertThat(field.getAnnotation(ManyToOne.class))
                        .as("field '%s' must carry no @ManyToOne. The legacy corpus performs explicit "
                                + "keyed reads, so an association would add a lazy-loading proxy and an "
                                + "N+1 hazard while buying nothing; zero associations means zero N+1 by "
                                + "construction", field.getName())
                        .isNull();
                assertThat(field.getAnnotation(OneToMany.class)).isNull();
                assertThat(field.getAnnotation(OneToOne.class)).isNull();
                assertThat(field.getAnnotation(JoinColumn.class))
                        .as("field '%s' must carry no @JoinColumn: cardNumber, typeCode and categoryCode "
                                + "are plain scalar columns even though the schema declares foreign keys "
                                + "over them", field.getName())
                        .isNull();
            }
        }

        @Test
        @DisplayName("the card number is a plain scalar column, not a mapped relationship")
        void theCardNumberIsAPlainScalarColumn() {
            final Field cardNumber = declaredField("cardNumber");

            assertThat(cardNumber.getType())
                    .as("TRAN-CARD-NUM is PIC X(16) text, so the property is a String and not a Card")
                    .isEqualTo(String.class);
            assertThat(columnOf("cardNumber").name()).isEqualTo("tran_card_num");
            assertThat(cardNumber.getAnnotation(JoinColumn.class)).isNull();
        }

        @Test
        @DisplayName("no field type and no annotation reaches outside the model layer")
        void nothingReachesOutsideTheModelLayer() {
            for (final Field field : instanceFields()) {
                final String fieldPackage = packageNameOf(field.getType());

                assertThat(FORBIDDEN_PACKAGES)
                        .as("a model class must depend on nothing, so that every other layer may depend on "
                                + "it. Field '%s' is typed from '%s'", field.getName(), fieldPackage)
                        .doesNotContain(fieldPackage);

                for (final Annotation annotation : field.getAnnotations()) {
                    assertThat(FORBIDDEN_PACKAGES)
                            .as("annotation '%s' on field '%s' reaches outside the model layer",
                                    annotation.annotationType().getSimpleName(), field.getName())
                            .doesNotContain(packageNameOf(annotation.annotationType()));
                }
            }

            for (final Annotation annotation : Transaction.class.getAnnotations()) {
                assertThat(FORBIDDEN_PACKAGES)
                        .as("class-level annotation '%s' reaches outside the model layer",
                                annotation.annotationType().getSimpleName())
                        .doesNotContain(packageNameOf(annotation.annotationType()));
            }
        }

        @Test
        @DisplayName("the entity's own package is com.cardemo.model.entity, spelled with one d")
        void theEntityLivesInTheModelEntityPackage() {
            assertThat(Transaction.class.getName())
                    .as("the package root is com.cardemo, never com.carddemo - a second d would put the "
                            + "type outside the scanned entity package and outside every import in the "
                            + "tree")
                    .isEqualTo("com.cardemo.model.entity.Transaction");
            assertThat(TransactionTest.class.getPackageName())
                    .as("this test sits in com.cardemo.unit.model, under src/test/java, which is what "
                            + "makes Surefire collect it")
                    .isEqualTo("com.cardemo.unit.model");
        }
    }

    @Nested
    @DisplayName("Rule 1 clause A - untrusted input: hostile and awkward values the corpus really produces")
    class HostileInput {

        @ParameterizedTest
        @ValueSource(strings = {"12345", "12345-6789", "1234567890", " ", ""})
        @DisplayName("a five-digit, a ZIP+4 and a blank postal code are all accepted, none reformatted")
        void awkwardPostalCodesAreAcceptedVerbatim(final String candidate) {
            final Transaction subject = postedTransaction();
            subject.setMerchantZip(candidate);

            assertThat(subject.getMerchantZip())
                    .as("TRAN-MERCHANT-ZIP is PIC X(10) free text, so a five-digit code, a ten-character "
                            + "ZIP+4 with its hyphen, and a blank are all legitimate. The width is a "
                            + "MAXIMUM, not an exact length: a shorter value is a value the CHAR(10) "
                            + "column blank-pads, and the entity neither pads nor trims it")
                    .isEqualTo(candidate);
        }

        @Test
        @DisplayName("an eleven-character postal code is rejected, one character past the picture clause")
        void anOverWidePostalCodeIsRejected() {
            assertThatIllegalArgumentException()
                    .as("eleven characters do not fit PIC X(10); the CHAR(10) column would refuse or "
                            + "truncate the value and the resulting diagnostic would name only a column")
                    .isThrownBy(() -> postedTransaction().setMerchantZip("12345-67890"))
                    .withMessageContaining("merchantZip")
                    .withMessageContaining("TRAN-MERCHANT-ZIP")
                    .withMessageContaining("at most 10 characters")
                    .withNoCause();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "ABCDEFGHI JKLMNOPQR",
            "{}ABC}IJR{",
            "MERCHANT J WITH R AND }",
            "0000000000{",
        })
        @DisplayName("overpunch characters embedded in text fields are stored as the text they are")
        void overpunchLookalikesInsideTextAreLeftAlone(final String candidate) {
            final Transaction subject = postedTransaction();
            subject.setDescription(candidate);
            subject.setMerchantName(candidate);
            subject.setMerchantCity(candidate);

            assertThat(subject.getDescription())
                    .as("the letters A through R and the braces are the zoned-decimal overpunch alphabet, "
                            + "and they occur legitimately inside merchant names and descriptions. This is "
                            + "exactly why overpunch decoding must be position-aware from the picture "
                            + "clauses alone and must never scan for sign characters: a decoder that "
                            + "searched for them would corrupt this text")
                    .isEqualTo(candidate);
            assertThat(subject.getMerchantName()).isEqualTo(candidate);
            assertThat(subject.getMerchantCity()).isEqualTo(candidate);
        }

        @Test
        @DisplayName("a text field filled to its exact picture width is accepted at the boundary")
        void aTextFieldFilledToItsExactWidthIsAccepted() {
            final Transaction subject = postedTransaction();
            final String hundredCharacters = "D".repeat(DESCRIPTION_WIDTH);

            subject.setDescription(hundredCharacters);

            assertThat(subject.getDescription())
                    .as("PIC X(100) holds exactly one hundred characters, so the boundary itself must load")
                    .isEqualTo(hundredCharacters)
                    .hasSize(DESCRIPTION_WIDTH);
            assertThatIllegalArgumentException()
                    .as("the hundred-and-first character does not fit")
                    .isThrownBy(() -> postedTransaction().setDescription("D".repeat(DESCRIPTION_WIDTH + 1)))
                    .withMessageContaining("TRAN-DESC")
                    .withNoCause();
        }

        @Test
        @DisplayName("the amount is accepted at both scale extremes the picture clause admits")
        void theAmountIsAcceptedAtBothScaleExtremes() {
            final Transaction wholeUnits = postedTransaction();
            final Transaction twoDecimals = postedTransaction();

            wholeUnits.setAmount(new BigDecimal("7"));
            twoDecimals.setAmount(new BigDecimal("7.00"));

            assertThat(wholeUnits.getAmount().scale())
                    .as("a scale-zero value is stored as supplied, because V99 fixes the column's decimal "
                            + "positions and not the caller's representation")
                    .isZero();
            assertThat(twoDecimals.getAmount().scale()).isEqualTo(AMOUNT_SCALE);
            assertThat(wholeUnits.getAmount())
                    .as("the two denote the same money, which only compareTo can express")
                    .isEqualByComparingTo(twoDecimals.getAmount());
        }

        @Test
        @DisplayName("a width failure names the field and its picture clause but never the value")
        void aWidthFailureNeverQuotesTheValue() {
            final String overWideCardNumber = SENTINEL_CARD_NUMBER + "9999";

            assertThatIllegalArgumentException()
                    .as("a validation message is exactly the kind of string that ends up in a log, and two "
                            + "of the ten character fields guarded here are sensitive or identifying, so "
                            + "the message reports the length and not the content")
                    .isThrownBy(() -> postedTransaction().setCardNumber(overWideCardNumber))
                    .withMessageContaining("cardNumber")
                    .withMessageContaining("TRAN-CARD-NUM PIC X(16)")
                    .withMessageContaining("at most 16 characters")
                    .withMessageNotContaining(overWideCardNumber)
                    .withNoCause();
        }
    }

    @Nested
    @DisplayName("Rule 1 clause B - every field rejects null, and the two numeric domains are bounded")
    class NullAndBoundaryGuards {

        @Test
        @DisplayName("all ten character fields reject null, naming the property and the NOT NULL column")
        void everyCharacterFieldRejectsNull() {
            assertNullRejected("transactionId", "TRAN-ID", t -> t.setTransactionId(null));
            assertNullRejected("typeCode", "TRAN-TYPE-CD", t -> t.setTypeCode(null));
            assertNullRejected("transactionSource", "TRAN-SOURCE", t -> t.setTransactionSource(null));
            assertNullRejected("description", "TRAN-DESC", t -> t.setDescription(null));
            assertNullRejected("merchantName", "TRAN-MERCHANT-NAME", t -> t.setMerchantName(null));
            assertNullRejected("merchantCity", "TRAN-MERCHANT-CITY", t -> t.setMerchantCity(null));
            assertNullRejected("merchantZip", "TRAN-MERCHANT-ZIP", t -> t.setMerchantZip(null));
            assertNullRejected("cardNumber", "TRAN-CARD-NUM", t -> t.setCardNumber(null));
            assertNullRejected("origTs", "TRAN-ORIG-TS", t -> t.setOrigTs(null));
            assertNullRejected("procTs", "TRAN-PROC-TS", t -> t.setProcTs(null));
        }

        @Test
        @DisplayName("the three non-character fields reject null too, covering all thirteen data columns")
        void everyNonCharacterFieldRejectsNull() {
            assertNullRejected("categoryCode", "TRAN-CAT-CD", t -> t.setCategoryCode(null));
            assertNullRejected("amount", "TRAN-AMT", t -> t.setAmount(null));
            assertNullRejected("merchantId", "TRAN-MERCHANT-ID", t -> t.setMerchantId(null));
        }

        @Test
        @DisplayName("the constructor rejects a null as firmly as the setter does")
        void theConstructorRejectsNullToo() {
            assertThatIllegalArgumentException()
                    .as("the constructor and the setters guard identically, so an entity can never exist "
                            + "in a state the record layout cannot represent")
                    .isThrownBy(() -> new Transaction(null, "01", 1, SOURCE_POS_TERM, "D",
                            BigDecimal.ONE, 1L, "N", "C", "Z", SENTINEL_CARD_NUMBER,
                            BLANK_TIMESTAMP, BLANK_TIMESTAMP))
                    .withMessageContaining("transactionId")
                    .withMessageContaining("must not be null")
                    .withNoCause();
        }

        @Test
        @DisplayName("a blank but non-null value is accepted on every character field")
        void aBlankValueIsAcceptedEverywhere() {
            final Transaction subject = postedTransaction();

            assertThatNoException()
                    .as("null is what is rejected, not blankness. There is no @NotBlank and no @NotEmpty "
                            + "anywhere on this entity, because the 26-space processing timestamp of the "
                            + "reference fixture is a legitimate value and a blank guard would reject the "
                            + "entire seed load")
                    .isThrownBy(() -> {
                        subject.setTypeCode("  ");
                        subject.setTransactionSource(" ".repeat(SOURCE_WIDTH));
                        subject.setDescription("");
                        subject.setMerchantName(" ");
                        subject.setMerchantCity("");
                        subject.setMerchantZip(" ".repeat(MERCHANT_ZIP_WIDTH));
                        subject.setProcTs(BLANK_TIMESTAMP);
                    });
            assertThat(subject.getDescription()).isEmpty();
            assertThat(subject.getProcTs()).isEqualTo(BLANK_TIMESTAMP);
        }

        @ParameterizedTest
        @CsvSource({"0", "1", "9999"})
        @DisplayName("the category code accepts its whole unsigned four-digit domain, zero included")
        void theCategoryCodeAcceptsItsWholeDomain(final int candidate) {
            final Transaction subject = postedTransaction();
            subject.setCategoryCode(candidate);

            assertThat(subject.getCategoryCode())
                    .as("TRAN-CAT-CD is PIC 9(04) with no S, so the domain is unsigned and starts at zero")
                    .isEqualTo(candidate);
        }

        @ParameterizedTest
        @CsvSource({"-1", "10000"})
        @DisplayName("the category code rejects the first value beyond each boundary")
        void theCategoryCodeRejectsOutOfRange(final int candidate) {
            assertThatIllegalArgumentException()
                    .as("four unsigned display digits, and equally a NUMERIC(4) column, hold 0 through "
                            + "9999 inclusive and nothing else")
                    .isThrownBy(() -> postedTransaction().setCategoryCode(candidate))
                    .withMessageContaining("TRAN-CAT-CD")
                    .withMessageContaining("between 0 and 9999")
                    .withNoCause();
        }

        @ParameterizedTest
        @CsvSource({"0", "1", "999999999"})
        @DisplayName("the merchant identifier accepts zero, which the interest job really writes")
        void theMerchantIdentifierAcceptsZero(final long candidate) {
            final Transaction subject = postedTransaction();
            subject.setMerchantId(candidate);

            assertThat(subject.getMerchantId())
                    .as("zero is not a sentinel to reject: app/cbl/CBACT04C.cbl builds its synthetic "
                            + "interest transactions with a merchant identifier of zero, so rejecting it "
                            + "would fail the interest job outright")
                    .isEqualTo(candidate);
        }

        @ParameterizedTest
        @CsvSource({"-1", "1000000000"})
        @DisplayName("the merchant identifier rejects the first value beyond each boundary")
        void theMerchantIdentifierRejectsOutOfRange(final long candidate) {
            assertThatIllegalArgumentException()
                    .as("nine unsigned display digits, and equally a NUMERIC(9) column, hold 0 through "
                            + "999999999 inclusive")
                    .isThrownBy(() -> postedTransaction().setMerchantId(candidate))
                    .withMessageContaining("TRAN-MERCHANT-ID")
                    .withNoCause();
        }

        @Test
        @DisplayName("the version counter accepts null, because a transient instance has no version yet")
        void theVersionCounterAcceptsNull() {
            final Transaction subject = postedTransaction();

            assertThat(subject.getVersion())
                    .as("the constructor does not assign the counter: it is the persistence provider's to "
                            + "maintain, so a freshly built instance has none")
                    .isNull();
            assertThatNoException()
                    .as("unlike the thirteen copybook fields, the counter is guarded by the provider "
                            + "rather than by the copybook, so it is not null-checked here")
                    .isThrownBy(() -> subject.setVersion(null));
        }

        /**
         * Asserts that a mutation rejects {@code null} with a message naming both the Java property and
         * the COBOL field, and with no wrapped cause.
         *
         * <p>A field guard is itself the root cause, so there is nothing to wrap: the requirement to
         * preserve a root cause applies where an exception is translated, and asserting
         * {@code withNoCause} states positively that no cause was invented or swallowed here.
         *
         * @param property   the entity property expected in the message
         * @param cobolField the COBOL field name expected in the message
         * @param mutation   the mutation that supplies {@code null}
         */
        private void assertNullRejected(final String property,
                                        final String cobolField,
                                        final java.util.function.Consumer<Transaction> mutation) {
            final Transaction subject = postedTransaction();

            assertThatIllegalArgumentException()
                    .as("every one of the thirteen data columns is NOT NULL, and a fixed-width COBOL field "
                            + "cannot be null in the first place, so '%s' must refuse one at the point of "
                            + "assignment where the caller is still on the stack", property)
                    .isThrownBy(() -> mutation.accept(subject))
                    .withMessageContaining(property)
                    .withMessageContaining(cobolField)
                    .withMessageContaining("must not be null")
                    .withNoCause();
        }
    }
}
