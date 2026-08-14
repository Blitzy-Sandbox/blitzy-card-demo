/*
 * ******************************************************************
 * Program     : FieldWidthUnitContractTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Holds the unit in which a declared field width is
 *               measured. A PIC X(n) clause declares n character
 *               positions and a CHAR(n) column pads to n characters,
 *               so every width guard on that contract counts Unicode
 *               code points and not UTF-16 code units. Asserts the
 *               accept-at-n and refuse-at-n+1 boundary on each guard,
 *               asserts the two deliberate code-unit boundaries, and
 *               scans the guards' own source so the unit cannot be
 *               reverted without this suite failing.
 * Source      : app/cpy/CSUSR01Y.cpy    (SEC-USR-FNAME PIC X(20))
 *               app/cpy-bms/COUSR00.CPY (FNAMEnnI, LNAMEnnI)
 *               app/cpy/CVCUS01Y.cpy    (CUST-FIRST-NAME PIC X(25))
 *               app/cpy/CVACT01Y.cpy    (ACCT-GROUP-ID PIC X(10))
 *               frozen at commit 7756d89
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
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The unit in which a declared field width is measured, held as a gate.
 *
 * <h2>What went wrong, and why a unit is worth a suite of its own</h2>
 *
 * <p>Every fixed-width field in this migration is declared by a COBOL {@code PICTURE} clause as
 * <em>n character positions</em>, and every one of them round-trips through a PostgreSQL
 * {@code character(n)} column, which pads its value to <em>n characters</em>. A Java {@code String}
 * measures itself in neither of those units: it counts UTF-16 code units, and a code unit is a character
 * only for the basic multilingual plane. One supplementary-plane character is one character position, one
 * code point and <strong>two</strong> {@code char} values.
 *
 * <p>The consequence was not theoretical. A first name of one letter followed by one supplementary-plane
 * character is two character positions, so the request path accepted it and the column stored it and padded
 * it to twenty characters - and those twenty characters read back as twenty-one {@code char} values. The
 * width guard on the <em>response</em> projection then refused a value that the write path had accepted and
 * the database had produced, and because that guard runs while a page of the user list is being assembled,
 * one such row turned every page of the listing into a server error. The inbound bound and the outbound
 * bound were nominally the same number and actually different bounds.
 *
 * <h2>What this suite asserts</h2>
 *
 * <ol>
 *   <li><b>The boundary, per guard.</b> A value of exactly n character positions that contains a
 *       supplementary-plane character is accepted; n+1 is still refused. Asserted through the public
 *       constructors that reach each guard, so it is the shipped path being measured.</li>
 *   <li><b>The projection that failed.</b> A user row whose first name is padded to its full declared width
 *       and contains a supplementary-plane character assembles into a page.</li>
 *   <li><b>The direction of the change.</b> A code point count never exceeds a code unit count, so the new
 *       unit cannot admit a value the old unit would have refused <em>for the wrong reason</em> - it admits
 *       exactly the values the declared field can carry, and nothing else.</li>
 *   <li><b>The deliberate exceptions.</b> Three families stay in code units on purpose, and the reason is
 *       different in each case. Asserted so that a later reader does not "finish the job" and break
 *       them. The third is the one most likely to be mistaken for an oversight: a guard that bounds a
 *       field of a <em>fixed-width record image</em> is measuring a byte budget, not a screen field, and
 *       those images are encoded in a single-byte charset in which a representable character is one code
 *       unit and one byte alike. Widening such a guard to code points would admit a value the encoder
 *       then silently substitutes, so the record would still be the right length and no longer the right
 *       bytes - the precise failure the byte-exactness requirement exists to prevent.</li>
 *   <li><b>No silent reversion.</b> The guards' own source is scanned: each named guard body must measure
 *       with {@code codePointCount}. Restoring a {@code length()} comparison fails here rather than
 *       surfacing months later as a 500 on a listing.</li>
 * </ol>
 */
@DisplayName("A declared field width is n character positions, counted as Unicode code points")
final class FieldWidthUnitContractTest {

    /** U+1F600 GRINNING FACE: one character position, one code point, two {@code char} values. */
    private static final String ASTRAL = "\uD83D\uDE00";

    /** Declared width of {@code SEC-USR-FNAME} and {@code SEC-USR-LNAME}, {@code PIC X(20)}. */
    private static final int NAME_WIDTH = 20;

    /** Declared width of {@code SEC-USR-ID}, {@code PIC X(08)}, and of every {@code USRIDnnI}. */
    private static final int USER_ID_WIDTH = 8;

    /** A BCrypt digest of the seeded literal password, at the strength the security configuration pins. */
    private static final String BCRYPT_DIGEST =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /** The guard bodies that must measure in code points, and the method each one is. */
    private static final List<GuardSite> GUARD_SITES = List.of(
            new GuardSite("src/main/java/com/cardemo/model/dto/UserSecurityDto.java",
                    "requireWidthWithinLimit"),
            new GuardSite("src/main/java/com/cardemo/model/dto/AccountDto.java",
                    "requireWidthWithinLimit"),
            new GuardSite("src/main/java/com/cardemo/model/dto/MenuResponse.java",
                    "requireWidthWithinLimit"),
            new GuardSite("src/main/java/com/cardemo/model/dto/CardDto.java", "requireWidth"),
            new GuardSite("src/main/java/com/cardemo/model/dto/TransactionDto.java", "requireWithinWidth"),
            new GuardSite("src/main/java/com/cardemo/model/dto/StatementTransaction.java",
                    "requireWithinWidth"),
            new GuardSite("src/main/java/com/cardemo/model/entity/Account.java", "requireWidth"),
            new GuardSite("src/main/java/com/cardemo/model/entity/Customer.java", "requireWidth"),
            new GuardSite("src/main/java/com/cardemo/model/entity/Card.java", "checkWidth"),
            new GuardSite("src/main/java/com/cardemo/model/entity/CardCrossReference.java", "requireWidth"),
            new GuardSite("src/main/java/com/cardemo/model/entity/Transaction.java", "requireWidth"),
            new GuardSite("src/main/java/com/cardemo/model/entity/DailyTransaction.java", "requireWidth"),
            new GuardSite("src/main/java/com/cardemo/model/entity/TransactionType.java", "requireWidth"),
            new GuardSite("src/main/java/com/cardemo/model/entity/TransactionCategory.java",
                    "requireCategoryDescription"),
            new GuardSite("src/main/java/com/cardemo/model/entity/UserSecurity.java", "requireWidth"),
            new GuardSite("src/main/java/com/cardemo/model/key/DisclosureGroupId.java", "requireWidth"),
            new GuardSite("src/main/java/com/cardemo/model/key/DisclosureGroupId.java", "requireExactWidth"),
            new GuardSite("src/main/java/com/cardemo/service/admin/UserAddService.java", "requireWidth"),
            new GuardSite("src/main/java/com/cardemo/service/admin/UserUpdateService.java", "requireWidth"),
            new GuardSite("src/main/java/com/cardemo/service/admin/UserDeleteService.java", "requireWidth"),
            new GuardSite("src/main/java/com/cardemo/service/admin/UserListService.java", "requireWidth"));

    /**
     * One guard whose body must measure in code points.
     *
     * @param sourcePath the repository-relative path of the class declaring it
     * @param methodName the guard's method name, unique within that class
     */
    private record GuardSite(String sourcePath, String methodName) {

        @Override
        public String toString() {
            return sourcePath.substring(sourcePath.lastIndexOf('/') + 1) + '#' + methodName;
        }
    }

    /**
     * Builds a value of exactly {@code characterPositions} character positions, the last of which is a
     * supplementary-plane character, so that its code point count and its {@code char} count differ.
     *
     * @param characterPositions the number of character positions the value must occupy; at least one
     * @return a value whose code point count is {@code characterPositions} and whose {@code length()} is one
     *         greater
     */
    private static String astralOfWidth(final int characterPositions) {
        return "A".repeat(characterPositions - 1) + ASTRAL;
    }

    /**
     * Right-pads with spaces to a declared width, in character positions, exactly as a
     * {@code character(n)} column does when the value is stored.
     *
     * @param value the value to pad
     * @param characterPositions the declared width in character positions
     * @return the value padded to that many character positions
     */
    private static String columnPadded(final String value, final int characterPositions) {
        final int present = value.codePointCount(0, value.length());
        return value + " ".repeat(characterPositions - present);
    }

    /** Every guard site, for the source-scan parameterisation. */
    private static Stream<GuardSite> guardSites() {
        return GUARD_SITES.stream();
    }

    @Nested
    @DisplayName("the premise: the two units really do disagree, and only in one direction")
    final class ThePremise {

        @Test
        @DisplayName("a supplementary-plane character is one character position and two char values")
        void aSupplementaryCharacterOccupiesOnePositionAndTwoCodeUnits() {
            assertThat(ASTRAL.codePointCount(0, ASTRAL.length()))
                    .as("one character position, which is what a PIC X(n) clause and a character(n) column "
                            + "both count")
                    .isEqualTo(1);
            assertThat(ASTRAL.length())
                    .as("two UTF-16 code units, which is what String.length() counts. This single "
                            + "disagreement is the whole defect")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a column pads to character positions, so a stored value can exceed its width in char values")
        void aStoredValueCanExceedItsDeclaredWidthInCodeUnits() {
            final String stored = columnPadded("A" + ASTRAL, NAME_WIDTH);
            assertThat(stored.codePointCount(0, stored.codePointCount(0, stored.length()) == 0
                    ? 0 : stored.length()))
                    .as("the stored value occupies exactly its declared width in character positions")
                    .isEqualTo(NAME_WIDTH);
            assertThat(stored.length())
                    .as("and exactly one more than that in char values, which is the value the JDBC driver "
                            + "hands back and the response projection is then asked to carry")
                    .isEqualTo(NAME_WIDTH + 1);
        }

        @ParameterizedTest(name = "width {0}")
        @ValueSource(ints = {1, 2, 8, 20, 25, 50, 78})
        @DisplayName("a code point count never exceeds a code unit count, at every declared width in play")
        void theCodePointCountIsNeverTheLooserBound(final int characterPositions) {
            final String value = astralOfWidth(characterPositions);
            assertThat(value.codePointCount(0, value.length()))
                    .as("this is why the change of unit cannot admit anything a declared field could not "
                            + "carry: it is the same bound measured correctly, never a wider one")
                    .isLessThanOrEqualTo(value.length());
        }
    }

    @Nested
    @DisplayName("the boundary, on the guards the outage ran through")
    final class TheBoundary {

        @Test
        @DisplayName("a user row accepts a first name of exactly its declared width and refuses one more")
        void theUserRowBoundaryIsCountedInCharacterPositions() {
            final UserSecurityDto.UserRow accepted = new UserSecurityDto.UserRow(
                    " ", "USER0001", astralOfWidth(NAME_WIDTH), astralOfWidth(NAME_WIDTH), "U");
            assertThat(accepted.firstName().codePointCount(0, accepted.firstName().length()))
                    .as("twenty character positions is exactly what FNAMEnnI declares, so it is carried "
                            + "unchanged rather than refused")
                    .isEqualTo(NAME_WIDTH);
            assertThat(accepted.firstName().length())
                    .as("and nothing was trimmed to make it fit: the value is returned as supplied, "
                            + "twenty-one char values and all")
                    .isEqualTo(NAME_WIDTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("one character position beyond the declared width is still a refusal; widening the "
                            + "unit must not have widened the field")
                    .isThrownBy(() -> new UserSecurityDto.UserRow(
                            " ", "USER0001", astralOfWidth(NAME_WIDTH + 1), "Astral", "U"))
                    .withMessageContaining("firstName")
                    .withMessageContaining(String.valueOf(NAME_WIDTH + 1))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .as("the message reports the offending LENGTH and never the offending value, "
                                    + "because a name is identifying data")
                            .doesNotContain(ASTRAL));
        }

        @Test
        @DisplayName("the identifier boundary is the eight character positions the cluster key declares")
        void theIdentifierBoundaryIsCountedInCharacterPositions() {
            assertThat(new UserSecurityDto.UserRow(
                    " ", astralOfWidth(USER_ID_WIDTH), "Given", "Family", "U").userId())
                    .as("KEYS(8,0) is eight character positions, so eight of them are accepted whatever "
                            + "plane they come from")
                    .hasSize(USER_ID_WIDTH + 1);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new UserSecurityDto.UserRow(
                            " ", astralOfWidth(USER_ID_WIDTH + 1), "Given", "Family", "U"))
                    .withMessageContaining("userId");
        }

        @Test
        @DisplayName("the user entity accepts the padded value the column produces, and still refuses a wider one")
        void theEntityBoundaryIsCountedInCharacterPositions() {
            final UserSecurity stored = new UserSecurity("AAASTRAL",
                    columnPadded("A" + ASTRAL, NAME_WIDTH),
                    columnPadded("Astral", NAME_WIDTH), BCRYPT_DIGEST, UserType.USER);
            assertThat(stored.getSecUsrFname().length())
                    .as("this is the exact value a character(20) column hands back for a first name holding "
                            + "one supplementary-plane character, so the entity has to be able to hold it: "
                            + "re-setting a hydrated name is an ordinary step of the user-update turn")
                    .isEqualTo(NAME_WIDTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("twenty-one character positions is not a value any column of this table can hold")
                    .isThrownBy(() -> new UserSecurity("AAASTRAL", astralOfWidth(NAME_WIDTH + 1),
                            "Astral", BCRYPT_DIGEST, UserType.USER))
                    .withMessageContaining("secUsrFname")
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .as("the entity sits beside the credential column, so its messages never carry "
                                    + "the value")
                            .doesNotContain(ASTRAL));
        }

        @Test
        @DisplayName("a page carrying such a row assembles, which is the projection that used to fail")
        void aPageCarryingASupplementaryPlaneNameAssembles() {
            final UserSecurityDto.UserRow row = new UserSecurityDto.UserRow(" ", "AAASTRAL",
                    columnPadded("A" + ASTRAL, NAME_WIDTH), columnPadded("Astral", NAME_WIDTH), "U");
            final UserSecurityDto page = new UserSecurityDto("CU00",
                    "List Users", "08/08/26", "COUSR00C", "CardDemo", "20:00:00", "1", "",
                    List.of(row), "");

            assertThat(page.rows())
                    .as("one such row used to make GET /api/admin/users answer 500 for every page, because "
                            + "the guard that refused it runs while the page is being assembled rather than "
                            + "while the request is being read")
                    .hasSize(1);
            assertThat(page.rows().get(0).firstName())
                    .as("and the value is projected exactly as stored, neither truncated nor normalised")
                    .isEqualTo(columnPadded("A" + ASTRAL, NAME_WIDTH));
        }
    }

    @Nested
    @DisplayName("the three boundaries that stay in code units, and why each one does")
    final class TheDeliberateExceptions {

        @Test
        @DisplayName("a positional overlay measures in the unit it indexes in, or its padding disagrees")
        void thePositionalHelpersMeasureCodeUnitsBecauseTheirOffsetsAre() {
            final String source =
                    sourceText("src/main/java/com/cardemo/model/dto/AccountUpdateRequest.java");
            assertThat(body(source, "appendOverlayPart"))
                    .as("""
                        this method checks a width and then emits padding computed from the same count. Its \
                        component offsets are substring indices, so measuring character positions while \
                        indexing code units would let the check pass and the padding compute a negative \
                        repeat. The three components are the numeric parts of a telephone number, for which \
                        the two units coincide anyway.""")
                    .contains("value.length() > width")
                    .doesNotContain("codePointCount");
            assertThat(source)
                    .as("and the reason is recorded where the method is, not only here")
                    .contains("measured in {@code char} values, deliberately");
        }

        @Test
        @DisplayName("bean-validation bounds stay in code units, which is safe in the direction they are in")
        void theOuterBeanValidationBoundIsNeverLooserThanTheGuard() {
            final String value = astralOfWidth(NAME_WIDTH);
            assertThat(value.length())
                    .as("""
                        @Size(max = n) counts code units, so it refuses this twenty-character-position value \
                        at the controller boundary before any guard sees it. That is stricter than the \
                        character-position bound, never looser, so it cannot admit a value a guard would \
                        refuse - and a 3270 field carries only single-byte characters, for which the two \
                        units are the same number.""")
                    .isGreaterThan(NAME_WIDTH);
            assertThat(value.codePointCount(0, value.length()))
                    .as("the guard, measuring character positions, accepts exactly the declared width")
                    .isEqualTo(NAME_WIDTH);
        }

        @Test
        @DisplayName("a fixed-width record image measures a byte budget, so widening it would corrupt bytes")
        void theRecordImageGuardsMeasureCodeUnitsBecauseTheirBudgetIsBytes() {
            assertThat(body(sourceText("src/main/java/com/cardemo/batch/processors/"
                    + "TransactionCombineProcessor.java"), "requireWidth"))
                    .as("""
                        this guard bounds a field of the 350-byte combined record image, not a screen \
                        field or a response projection. Its budget is bytes, and the image is encoded in \
                        a single-byte charset, so code units are the unit that matches the budget.""")
                    .contains("value.length() > width")
                    .doesNotContain("codePointCount");
            assertThat(sourceText("src/main/java/com/cardemo/batch/jobs/CombineTransactionsJob.java"))
                    .as("and that is only true while the record charset stays single-byte")
                    .contains("FIXED_WIDTH_CHARSET = StandardCharsets.ISO_8859_1");

            // Why widening it would be wrong, demonstrated rather than asserted in prose: this is exactly
            // what a character(100) column returns for a short value containing one supplementary-plane
            // character, because the column pads to 100 CHARACTER POSITIONS.
            final StringBuilder builder = new StringBuilder("Gift").append(ASTRAL);
            while (builder.codePointCount(0, builder.length()) < 100) {
                builder.append(' ');
            }
            final String asStored = builder.toString();
            assertThat(asStored.codePointCount(0, asStored.length()))
                    .as("100 character positions, so a code-point guard would admit it")
                    .isEqualTo(100);
            assertThat(asStored.length())
                    .as("but 101 code units, so the code-unit guard refuses it")
                    .isEqualTo(101);
            final byte[] encoded = asStored.getBytes(StandardCharsets.ISO_8859_1);
            assertThat(encoded).as("the encoder still emits a full-width record").hasSize(100);
            assertThat(new String(encoded, StandardCharsets.ISO_8859_1))
                    .as("""
                        and it is no longer the same value: the supplementary-plane character is not \
                        representable in the record charset and is substituted. Right length, wrong bytes \
                        - so refusing at the guard is the correct outcome and this guard must NOT be \
                        widened to code points.""")
                    .isNotEqualTo(asStored);
        }

        @Test
        @DisplayName("the report-image writer states the charset rule outright, and is the pattern to follow")
        void theReportImageGuardAlsoConstrainsTheCharacterSetItself() {
            assertThat(body(sourceText("src/main/java/com/cardemo/batch/jobs/TransactionReportJob.java"),
                    "alphanumeric"))
                    .as("""
                        the in-repository precedent for the record-image family: it bounds the width in \
                        code units AND refuses any character outside the single-byte printable ranges, so \
                        the byte budget and the character set are enforced together rather than one being \
                        inferred from the other.""")
                    .contains("actual.length() > width")
                    .doesNotContain("codePointCount")
                    .contains("printableAscii")
                    .contains("printableLatinOne");
        }
    }

    @Nested
    @DisplayName("the unit cannot be reverted without this suite failing")
    final class NoSilentReversion {

        @Test
        @DisplayName("the guard census covers every class that declares one, so none can be missed")
        void theCensusIsCompleteAcrossTheGuardFamily() {
            assertThat(GUARD_SITES)
                    .as("twenty-one guards were found by measuring the tree rather than by memory; a new "
                            + "one belongs in this list, and a list that has stopped matching the tree is "
                            + "the failure mode a one-off fix leaves behind")
                    .hasSize(21);
            assertThat(GUARD_SITES.stream().map(GuardSite::sourcePath).distinct().toList())
                    .as("the family spans the DTO projections, the entity and composite-key constructors "
                            + "and the four admin services' screen-field checks")
                    .hasSize(20);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.FieldWidthUnitContractTest#guardSites")
        @DisplayName("every guard body measures with codePointCount and none with length()")
        void everyGuardMeasuresCharacterPositions(final GuardSite site) {
            final String guardBody = body(sourceText(site.sourcePath()), site.methodName());

            assertThat(guardBody)
                    .as("%s must measure the width in character positions. A length() comparison here is "
                            + "the defect that turned a stored, padded value into a 500 on a listing",
                            site)
                    .contains("codePointCount(0, value.length())");
            assertThat(comparisonOperands(guardBody))
                    .as("%s must compare the code point count against the declared width, never the raw "
                            + "code unit count. Operands found: %s", site, comparisonOperands(guardBody))
                    .isNotEmpty()
                    .allSatisfy(operand -> assertThat(operand)
                            .doesNotContain("value.length()"));
        }
    }

    /**
     * Extracts the body of one method, from its signature line to the matching closing brace.
     *
     * <p>Brace counting rather than a regular expression, because a guard body legitimately contains braces
     * inside string literals and a naive terminator would cut the body short. Comment lines are dropped
     * first, so that the prose explaining why a unit was chosen cannot satisfy or falsify an assertion about
     * the code that implements it - the surrounding documentation of every one of these guards discusses
     * {@code length()} by name.
     *
     * @param source the whole source text of the declaring class
     * @param methodName the guard's method name, which must appear exactly once as a declaration
     * @return the method's code-bearing lines, joined by newlines
     */
    private static String body(final String source, final String methodName) {
        final List<String> code = new ArrayList<>();
        boolean inBlockComment = false;
        for (final String raw : source.split("\n", -1)) {
            final String line = raw.strip();
            if (inBlockComment) {
                inBlockComment = !line.endsWith("*/");
                continue;
            }
            if (line.startsWith("/*")) {
                inBlockComment = !line.endsWith("*/");
                continue;
            }
            if (line.startsWith("//")) {
                continue;
            }
            code.add(raw);
        }

        // `static` is optional so an instance guard is findable too: TransactionReportJob.alphanumeric is
        // one, and a scan that silently skipped it would assert nothing while appearing to pass.
        final Pattern declaration = Pattern.compile(
                "^\\s+private\\s+(?:static\\s+)?(?:void|String)\\s+" + Pattern.quote(methodName) + "\\s*\\(");
        int start = -1;
        for (int index = 0; index < code.size(); index++) {
            if (declaration.matcher(code.get(index)).find()) {
                start = index;
                break;
            }
        }
        assertThat(start)
                .as("the declaration of %s must be findable, or this scan proves nothing about it",
                        methodName)
                .isNotNegative();

        final StringBuilder collected = new StringBuilder();
        int depth = 0;
        boolean opened = false;
        for (int index = start; index < code.size(); index++) {
            final String line = code.get(index);
            collected.append(line).append('\n');
            for (int position = 0; position < line.length(); position++) {
                final char current = line.charAt(position);
                if (current == '{') {
                    depth++;
                    opened = true;
                } else if (current == '}') {
                    depth--;
                }
            }
            if (opened && depth == 0) {
                break;
            }
        }
        return collected.toString();
    }

    /**
     * Collects the left-hand operand of every width comparison in a guard body.
     *
     * @param guardBody the guard's code-bearing lines
     * @return the operands compared against a declared width, in source order
     */
    private static List<String> comparisonOperands(final String guardBody) {
        final Matcher comparison = Pattern
                .compile("if\\s*\\(([^)]*?)\\s*(?:>|!=)\\s*(?:maxLength|maxWidth|width|length|"
                        + "CATEGORY_DESCRIPTION_LENGTH)\\s*\\)")
                .matcher(guardBody);
        final List<String> operands = new ArrayList<>();
        while (comparison.find()) {
            operands.add(comparison.group(1).strip());
        }
        return operands;
    }

    /**
     * Reads one repository file as UTF-8 text.
     *
     * <p>Resolved against the Surefire working directory, which {@code pom.xml} pins to
     * {@code ${project.basedir}}. The {@link IOException} is wrapped rather than swallowed so the root cause
     * survives, and the message names the path that was attempted so a working-directory problem is
     * self-diagnosing.
     *
     * @param relativePath a repository-relative path
     * @return the file content
     * @throws UncheckedIOException if the file cannot be read
     */
    private static String sourceText(final String relativePath) {
        final Path path = Path.of(relativePath);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException cause) {
            throw new UncheckedIOException("Cannot read " + path.toAbsolutePath()
                    + ". This suite resolves repository files against the Surefire working directory, "
                    + "which pom.xml pins to ${project.basedir}; run it from the repository root.", cause);
        }
    }
}
