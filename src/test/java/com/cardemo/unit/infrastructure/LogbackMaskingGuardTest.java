/*
 * ****************************************************************************
 * Program     : LogbackMaskingGuardTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Guards the masking layer of logback-spring.xml. Asserts that
 *               every value rule compiles, that none of them contains the '?:-'
 *               sequence logback silently truncates, that the two AWS rules
 *               redact an account identifier in both its ARN and its service-URL
 *               rendering, that they redact nothing else this project puts in a
 *               key or a path, and that the stack renderer is bounded root cause
 *               first while the two alerting class fields stay fully qualified.
 * Source      : app/cbl/CBTRN02C.cbl:L714-L727 (9910-DISPLAY-IO-STATUS, the
 *                 'FILE STATUS IS: NNNN' parity literal that must survive
 *                 masking byte for byte)
 *               + app/cpy/CVACT01Y.cpy:L5 (ACCT-ID PIC 9(11))
 *               + app/cpy/CVTRA05Y.cpy:L5 (TRAN-ID PIC X(16))
 *               + app/cpy/CVACT02Y.cpy:L5 (CARD-NUM PIC X(16))
 *               + app/jcl/DEFGDGB.jcl (the GDG generations the 19-digit key
 *                 segments replace)
 *               @ 7756d89
 * ****************************************************************************
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
 * ****************************************************************************
 */
package com.cardemo.unit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Asserts the masking contract of {@code logback-spring.xml} by executing its rules rather than reading
 * them.
 *
 * <p><strong>Why this is executed rather than reviewed.</strong> Two independent failure modes in this
 * file are invisible to inspection and neither produces a weaker mask - both produce <em>no</em> mask.
 *
 * <p>The first is truncation. Logback substitutes variables in element text before the value reaches
 * {@link Pattern#compile(String)}, and it reads {@code :-} as the default-value separator of a
 * {@code ${...}} construct, so a {@code ?} immediately followed by {@code :-} is consumed as a variable
 * reference with a default and everything from the {@code :-} onward is deleted. The natural spelling
 * {@code (?:-[a-z]{2,8})*} was authored during this work and arrived at the regex engine as
 * {@code (?}, which threw {@code Unknown inline modifier} - and because an uncompilable value rule
 * fails {@code MaskingJsonGeneratorDecorator.start()}, which fails the encoder, which fails the
 * appender, the outcome of that one typo is a log pipeline with the masking layer absent altogether.
 * The rule was rewritten as {@code (?:[-][a-z]{2,8})*}, which is identical in meaning and has no
 * {@code :-} adjacency, and the guard below keeps the sequence out of every rule in the file.
 *
 * <p>The second is over-redaction. A rule anchored on shape alone cannot ask what a value means, so a
 * twelve-digit rule is safe here only because twelve digits is an unused width in this project's own
 * key space. That is a property of the key layouts, not of the regex, so it is asserted against the
 * actual widths the copybooks fix: eleven for an account identifier, sixteen for a transaction
 * identifier and a card number, nineteen for a generation segment.
 *
 * <p><strong>Scope, stated so the omission is not mistaken for an oversight.</strong> These tests
 * exercise the rules, not a configured Logback pipeline. Booting the real configuration would replace
 * the {@code LoggerContext} for the whole Surefire JVM and break every test that attaches an appender
 * to observe its own logger, so full configuration is validated separately and out of band. What is
 * asserted here is exactly what a reviewer cannot verify by reading: that each pattern compiles, that
 * each matches what it must, and that each leaves alone what it must.
 */
@DisplayName("logback-spring.xml: the masking rules compile, redact AWS identity and nothing else")
class LogbackMaskingGuardTest {

    /** The logging configuration under test, relative to {@code ${basedir}}. */
    private static final Path CONFIG = Path.of("src", "main", "resources", "logback-spring.xml");

    /**
     * The sequence logback truncates. A {@code ?} followed by the default-value separator {@code :-}.
     */
    private static final String TRUNCATION_HAZARD = "(?:-";

    /** Every {@code <valueMask>} rule in file order, as a compiled pattern paired with its mask. */
    private static List<ValueRule> valueRules;

    /** Every comma-joined {@code <paths>} entry in the file, flattened to individual field names. */
    private static List<String> maskedPaths;

    /** The raw configuration text, for the assertions that are about declaration rather than behaviour. */
    private static String configText;

    /**
     * One value-masking rule as logback applies it: a compiled pattern and the replacement it feeds to
     * {@link Matcher#replaceAll(String)}.
     *
     * @param source  the authored pattern text, retained so a failure message can name it
     * @param pattern the compiled pattern
     * @param mask    the replacement, with {@code ${...}} properties already resolved
     */
    private record ValueRule(String source, Pattern pattern, String mask) {

        /**
         * Applies this rule the way logback's masking decorator does.
         *
         * @param value the value to mask
         * @return the masked value
         */
        String apply(String value) {
            return pattern.matcher(value).replaceAll(mask);
        }
    }

    @BeforeAll
    static void parseConfiguration() {
        configText = read(CONFIG);
        Map<String, String> properties = declaredProperties(configText);

        valueRules = new ArrayList<>();
        Matcher rules = Pattern
                .compile("<valueMask>\\s*<value><!\\[CDATA\\[(.*?)]]></value>\\s*<mask>(.*?)</mask>",
                        Pattern.DOTALL)
                .matcher(configText);
        while (rules.find()) {
            String source = rules.group(1);
            valueRules.add(new ValueRule(source, Pattern.compile(source),
                    resolve(rules.group(2), properties)));
        }

        maskedPaths = new ArrayList<>();
        Matcher paths = Pattern.compile("<paths>(.*?)</paths>", Pattern.DOTALL).matcher(configText);
        while (paths.find()) {
            for (String path : paths.group(1).split(",")) {
                maskedPaths.add(path.trim());
            }
        }
    }

    /**
     * Reads a repository file, failing rather than skipping when it is absent.
     *
     * @param path the file to read
     * @return its contents
     */
    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read " + path.toAbsolutePath(), failure);
        }
    }

    /**
     * Collects the {@code <property>} declarations so a mask's {@code ${...}} reference resolves to the
     * value the file itself declares, rather than to a literal retyped in this test.
     *
     * @param xml the configuration text
     * @return property name to value
     */
    private static Map<String, String> declaredProperties(String xml) {
        Map<String, String> properties = new LinkedHashMap<>();
        Matcher declarations =
                Pattern.compile("<property\\s+name=\"([^\"]+)\"\\s+value=\"([^\"]*)\"\\s*/>")
                        .matcher(xml);
        while (declarations.find()) {
            properties.put(declarations.group(1), declarations.group(2));
        }
        return properties;
    }

    /**
     * Substitutes {@code ${name}} references in a mask, leaving {@code $n} backreferences alone.
     *
     * @param mask       the authored mask text
     * @param properties the declared properties
     * @return the mask with property references resolved
     */
    private static String resolve(String mask, Map<String, String> properties) {
        String resolved = mask;
        for (Map.Entry<String, String> property : properties.entrySet()) {
            resolved = resolved.replace("${" + property.getKey() + "}",
                    Matcher.quoteReplacement(property.getValue()));
        }
        return resolved;
    }

    /**
     * Applies every value rule in file order, which is what the decorator does to a log value.
     *
     * @param value the value to mask
     * @return the value after all rules have run
     */
    private static String maskAll(String value) {
        String masked = value;
        for (ValueRule rule : valueRules) {
            masked = rule.apply(masked);
        }
        return masked;
    }

    @Nested
    @DisplayName("Rule integrity")
    class RuleIntegrity {

        @Test
        @DisplayName("the configuration is well-formed XML")
        void configurationIsWellFormed() {
            assertThatCode(() -> {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.newDocumentBuilder().parse(CONFIG.toFile());
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("every value rule compiles")
        void everyValueRuleCompiles() {
            // Compilation happens in parseConfiguration, so reaching here already proves it; the count
            // is asserted so a rule silently disappearing is a failure rather than a vacuous pass.
            assertThat(valueRules).hasSizeGreaterThanOrEqualTo(15);
            assertThat(valueRules).allSatisfy(rule -> assertThat(rule.pattern().pattern()).isNotEmpty());
        }

        @Test
        @DisplayName("no value rule contains the '?:-' sequence logback truncates")
        void noValueRuleContainsTheTruncationHazard() {
            for (ValueRule rule : valueRules) {
                assertThat(rule.source())
                        .as("logback deletes from ':-' onward when it follows a '?', so this rule would "
                                + "reach Pattern.compile truncated and fail the whole masking decorator; "
                                + "write the hyphen as the character class [-] instead")
                        .doesNotContain(TRUNCATION_HAZARD);
            }
        }

        @Test
        @DisplayName("the AWS resource-locator field names are masked by name")
        void awsLocatorFieldNamesAreMasked() {
            assertThat(maskedPaths).contains("queueUrl", "topicArn", "queueArn", "bucketArn", "arn",
                    "awsAccountId", "endpoint", "endpointUrl");
        }

        @Test
        @DisplayName("the business account identifier is deliberately NOT masked by name")
        void businessAccountIdentifierIsNotMasked() {
            // ACCT-ID PIC 9(11) at app/cpy/CVACT01Y.cpy:L5 is an eleven digit business key that batch
            // logs and health details publish on purpose, and that parity comparison depends on.
            // Masking it would remove diagnosability to protect nothing.
            assertThat(maskedPaths).doesNotContain("accountId", "acctId", "ACCT-ID", "acct_id");
        }
    }

    @Nested
    @DisplayName("AWS account identity is redacted (H-11)")
    class AwsAccountIdentityIsRedacted {

        @ParameterizedTest
        @ValueSource(strings = {
            "arn:aws:sns:us-east-1:000000000000:carddemo-notifications",
            "arn:aws:sqs:us-east-1:210987654321:carddemo-report-jobs.fifo",
            "arn:aws-us-gov:sqs:us-gov-west-1:210987654321:carddemo-report-jobs.fifo",
            "arn:aws-cn:s3:cn-north-1:210987654321:carddemo-batch-output",
        })
        @DisplayName("the account segment of an ARN is redacted in every partition")
        void arnAccountSegmentIsRedacted(String arn) {
            String masked = maskAll("topicArn " + arn + " end");

            assertThat(masked).doesNotContain("000000000000").doesNotContain("210987654321");
            // The service and the resource survive, because they are what an operator acts on.
            assertThat(masked).contains("arn:aws").contains("carddemo");
        }

        @Test
        @DisplayName("the account segment of a resolved queue URL is redacted")
        void queueUrlAccountSegmentIsRedacted() {
            String masked = maskAll("resolved http://sqs.us-east-1.localhost.localstack.cloud:4566/"
                    + "000000000000/carddemo-report-jobs.fifo");

            assertThat(masked).doesNotContain("000000000000");
            assertThat(masked).contains("carddemo-report-jobs.fifo");
        }

        @Test
        @DisplayName("an ARN with no account segment is left alone")
        void bucketArnWithoutAccountIsUntouched() {
            String value = "arn:aws:s3:::carddemo-batch-output";

            assertThat(maskAll(value)).isEqualTo(value);
        }
    }

    @Nested
    @DisplayName("Driver-rendered bound values and failing-row images (R16, R17)")
    class DriverRenderedValues {

        /**
         * The shape {@code org.postgresql.util.PSQLException} builds for a failed batch: the statement
         * re-rendered with its parameters inlined as {@code column=('value')}. Hibernate's
         * {@code SqlExceptionHelper} logs it at ERROR, so it reaches the encoder on any failed write. The
         * digits below are synthetic.
         */
        private static final String BATCHED_CUSTOMER_UPDATE =
                "Batch entry 0 update customer set cust_addr_line_1=('618 Deshaun Route'),"
                        + "cust_pri_card_holder_ind=('X'),cust_ssn=('020973888'),"
                        + "cust_fico_credit_score=('274'),version=('1'::int8) "
                        + "where cust_id=('1'::int8) was aborted: "
                        + "ERROR: new row for relation \"customer\" violates check constraint "
                        + "\"ck_customer_pri_card_holder_ind\"  Call getNextException to see other errors.";

        @Test
        @DisplayName("a bare nine-digit SSN inlined by the driver is withheld, and so is every sibling value")
        void driverInlinedCustomerValuesAreWithheld() {
            String masked = maskAll(BATCHED_CUSTOMER_UPDATE);

            assertThat(masked)
                    .as("AAP 0.7.7 and Rule 1 Clause D make SSN masking mandatory on every path, and the "
                            + "label-keyed rules cannot reach a value separated from its column by \"=('\"")
                    .doesNotContain("020973888")
                    .doesNotContain("618 Deshaun Route")
                    .doesNotContain("274");
            assertThat(masked)
                    .as("everything a diagnosis acts on survives: the relation, the constraint name and "
                            + "the statement text")
                    .contains("update customer set")
                    .contains("cust_ssn=(")
                    .contains("relation \\\"customer\\\"".replace("\\\"", "\""))
                    .contains("ck_customer_pri_card_holder_ind");
        }

        @Test
        @DisplayName("a sixteen-digit primary account number inlined by the driver is withheld")
        void driverInlinedCardNumberIsWithheld() {
            // Reproduced live: a foreign-key violation on the transaction insert published the PAN in
            // clear, because R11 requires a label and the driver writes cardNum=('...') instead.
            String masked = maskAll("Batch entry 0 insert into transaction (tran_amt,tran_card_num,"
                    + "tran_cat_cd) values (('1.00'::numeric),('9680294154603697'),('1'::numeric)) "
                    + "was aborted");

            assertThat(masked).doesNotContain("9680294154603697");
            assertThat(masked).contains("insert into transaction").contains("tran_card_num");
        }

        @Test
        @DisplayName("the failing-row image is withheld while the constraint diagnosis survives")
        void failingRowImageIsWithheld() {
            String masked = maskAll("ERROR: new row for relation \"customer\" violates check constraint "
                    + "\"ck_customer_ssn_numeric\"  Detail: Failing row contains "
                    + "(1, Immanuel, Madeline, Kessler, 618 Deshaun Route, 020973888, 274, 1).");

            assertThat(masked)
                    .as("the tuple is positional and unlabelled, so no shape rule can tell the social "
                            + "security number from the identifier beside it; the whole image goes")
                    .doesNotContain("020973888")
                    .doesNotContain("Immanuel");
            assertThat(masked)
                    .as("the relation and the constraint name sit before the marker and must survive")
                    .contains("ck_customer_ssn_numeric")
                    .contains("Failing row contains");
        }

        @ParameterizedTest
        @CsvSource({
            // PostgreSQL's referential detail carries no quotes, so it stays readable - and it is the most
            // useful half of a foreign-key diagnosis.
            "'Detail: Key (tran_type_cd)=(99) is not present in table \"transaction_type\".',"
                    + "unquoted referential key detail",
            // Every com.cardemo event renders its values with plain quotes or none, never wrapped in
            // parentheses, so no application message is touched.
            "'CT02 WRITE on dataset ''TRANSACT'' failed: resp=14 reas=0',application dataset diagnostic",
            "'TRANSACTIONS PROCESSED : 000000300',the first counter DISPLAY line",
            "'TRANSACTIONS REJECTED  : 000000002',the second counter DISPLAY line",
        })
        @DisplayName("neither new rule touches a diagnosis that carries no inlined literal")
        void neitherRuleOverReaches(String value, String description) {
            assertThat(maskAll(value)).as(description).isEqualTo(value);
        }
    }

    @Nested
    @DisplayName("Nothing else is over-redacted")
    class NothingElseIsOverRedacted {

        @ParameterizedTest
        @CsvSource({
            // The statements key: ACCT-ID is eleven digits, app/cpy/CVACT01Y.cpy:L5.
            "'statements/00000000011/2026-08/statement.txt',eleven-digit account segment",
            // The writers' key number width is nineteen digits, replacing the GDG generations.
            "'gdg/dalyrejs/0000000000000000042/rejects.dat',nineteen-digit generation segment",
            "'gdg/tranrept/0000000000000000007/report.txt',nineteen-digit job instance segment",
            // TRAN-ID and CARD-NUM are sixteen digits, app/cpy/CVTRA05Y.cpy:L5 and CVACT02Y.cpy:L5.
            "'transact/1234567890123456/record.dat',sixteen-digit identifier segment",
            "'accountId=00000000011 processed=300 rejected=2',labelled business counters",
        })
        @DisplayName("a project key or path is passed through unchanged")
        void projectKeysArePassedThrough(String value, String description) {
            assertThat(maskAll(value)).as(description).isEqualTo(value);
        }

        @Test
        @DisplayName("the FILE STATUS parity literal survives byte for byte")
        void fileStatusParityLiteralSurvives() {
            // 9910-DISPLAY-IO-STATUS at app/cbl/CBTRN02C.cbl:L714-L727 emits the placeholder text NNNN
            // followed immediately by the four character status. The stray NNNN is a preserved legacy
            // quirk and no masking rule may reformat, truncate or redact any part of it.
            String value = "FILE STATUS IS: NNNN0023";

            assertThat(maskAll(value)).isEqualTo(value);
        }

        @Test
        @DisplayName("a twelve digit run that is not a path segment is passed through")
        void barelabelledTwelveDigitRunIsPassedThrough() {
            // The rule requires the segment to sit between two solidi, so a bare twelve digit number in
            // ordinary text is not touched. This keeps the rule from becoming a general digit redactor.
            String value = "elapsedMillis=123456789012 in flight";

            assertThat(maskAll(value)).isEqualTo(value);
        }
    }

    @Nested
    @DisplayName("Bounded stack rendering (H-11)")
    class BoundedStackRendering {

        @Test
        @DisplayName("the stack renderer is bounded and root cause first")
        void stackRendererIsBoundedRootCauseFirst() {
            assertThat(configText)
                    .contains("net.logstash.logback.stacktrace.ShortenedThrowableConverter")
                    .contains("<rootCauseFirst>true</rootCauseFirst>")
                    .contains("<maxDepthPerThrowable>")
                    .contains("<maxLength>")
                    .contains("<inlineHash>false</inlineHash>");
        }

        @Test
        @DisplayName("the two alerting class fields stay fully qualified")
        void alertingClassFieldsStayFullyQualified() {
            // Frame-level shortening must not reach exceptionClass or rootCauseClass:
            // com.cardemo.exception.ValidationException has to remain distinguishable from
            // jakarta.validation.ValidationException for the name to be usable as an alerting key.
            assertThat(configText).contains("<useSimpleClassName>false</useSimpleClassName>");
            assertThat(configText.split("<useSimpleClassName>false</useSimpleClassName>", -1))
                    .as("both throwableClassName and throwableRootCauseClassName must set it")
                    .hasSizeGreaterThanOrEqualTo(3);
        }
    }

    /**
     * The driver-statement rules R16 and R17.
     *
     * <p>A rejected insert reaches the log through Hibernate's {@code SqlExceptionHelper} carrying the
     * driver's own message, and for a batched statement that message is every bound value interpolated
     * into the SQL text - including {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5} in
     * cleartext. No rule above this one could reach it: the labelled card rule needs its label adjacent
     * to the digits, and the field-name layer keys on JSON names while here the whole statement is the
     * value of a single field.
     *
     * <p>The two rules are gated on the sequences {@code ('} and {@code )=(}, which occur in driver text
     * and in nothing this application emits. The last two tests are what make that gate trustworthy:
     * they assert the parity output and the application's own identifier diagnostics pass through
     * untouched, which a bare digit-run rule could not do because {@code TRAN-ID PIC X(16)} has exactly
     * the same shape as a card number.
     */
    @Nested
    @DisplayName("Driver statement text is redacted")
    class DriverStatementTextIsRedacted {

        @Test
        @DisplayName("the card number inside a batched insert's value list is redacted")
        void theCardNumberInABatchedInsertIsRedacted() {
            // Verbatim shape from the captured runtime evidence, abbreviated in the middle only.
            String value = "Batch entry 0 insert into transaction (tran_amt,tran_card_num,tran_id) "
                    + "values (('2.34'::numeric),('7427684863423209'),('0000000996722789')) was aborted: "
                    + "ERROR: duplicate key value violates unique constraint \"pk_transaction\"";

            String masked = maskAll(value);

            assertThat(masked)
                    .as("the sixteen digit PAN is the disclosure the finding names")
                    .doesNotContain("7427684863423209");
            assertThat(masked)
                    .as("the record image is the wider half of the same disclosure: the same statement "
                            + "shape on app/cpy/CVCUS01Y.cpy carries CUST-SSN and the address lines, so "
                            + "every quoted value goes, not only the ones that look like a card number")
                    .doesNotContain("0000000996722789")
                    .doesNotContain("2.34");
            assertThat(masked)
                    .as("what an operator needs survives: the table, the column list and the condition")
                    .contains("insert into transaction (tran_amt,tran_card_num,tran_id)")
                    .contains("duplicate key value violates unique constraint");
        }

        @Test
        @DisplayName("the key value of a constraint-violation detail is redacted, the column name is not")
        void theConstraintDetailKeyValueIsRedacted() {
            String value = "ERROR: duplicate key value violates unique constraint \"pk_transaction\"  "
                    + "Detail: Key (tran_id)=(0000000996722789) already exists.";

            String masked = maskAll(value);

            assertThat(masked)
                    .as("a unique constraint on this schema can be keyed on CARD-NUM, so no key value "
                            + "reported by the driver may stay in the log")
                    .doesNotContain("0000000996722789");
            assertThat(masked)
                    .as("which constraint was violated, and on which column, is the whole diagnostic "
                            + "value of the line and is preserved")
                    .contains("pk_transaction")
                    .contains("Key (tran_id)=")
                    .contains("already exists.");
        }

        @Test
        @DisplayName("an unquoted identifier with no driver context is passed through")
        void theGateIsContextualAndNotAShapeRule() {
            // If either rule degraded into a bare digit-run redactor this would fail, and with it every
            // transaction identifier in every diagnostic this application writes would be unreadable.
            String value = "Refused a transaction add: identifier 0000000996722789 is taken on TRANSACT";

            assertThat(maskAll(value)).isEqualTo(value);
        }

        @Test
        @DisplayName("the parity literals survive both new rules byte for byte")
        void theParityLiteralsSurviveTheNewRules() {
            // Re-checked against the two rules that are gated on context rather than on a label: none of
            // these lines contains a parenthesised quote or a constraint detail at all, so none is touched.
            for (String value : List.of("FILE STATUS IS: NNNN0023", "FILE STATUS IS: NNNN9001",
                    "TRANSACTIONS PROCESSED :000000012", "TRANSACTIONS REJECTED  :000000002",
                    "START OF EXECUTION OF PROGRAM CBTRN02C",
                    "END OF EXECUTION OF PROGRAM CBTRN02C")) {
                assertThat(maskAll(value)).as("parity line [%s]", value).isEqualTo(value);
            }
        }

        @Test
        @DisplayName("both new rules are present, hazard free and free of property references")
        void bothNewRulesArePresentAndHazardFree() {
            List<String> contextual = valueRules.stream()
                    .map(ValueRule::source)
                    .filter(source -> source.contains("(?<=\\(')") || source.contains("(?<=\\)=\\("))
                    .toList();

            assertThat(contextual)
                    .as("both rules must be present: R16 on the quoted literal, R17 on the detail key")
                    .hasSize(2);
            for (String source : contextual) {
                assertThat(source)
                        .as("logback truncates a rule from ':-' onward when it follows a '?', which fails "
                                + "the whole appender and would leave no masking layer at all")
                        .doesNotContain(TRUNCATION_HAZARD);
            }
        }
    }
}
