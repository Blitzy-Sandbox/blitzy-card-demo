package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
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
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * A raw backend failure reaches no log line and no value a caller can render.
 *
 * <h2>What went wrong, and why a per-file fix was not enough</h2>
 * Every dataset access in this module already composed a sanitized summary of a refusal - the
 * {@code SQLSTATE}, the vendor code, the exception type - and then handed the logger the original
 * {@link Throwable} beside it. Sanitising a summary and attaching the raw exception to the same call
 * sanitises nothing: the logger emits the exception's message and its whole cause chain verbatim.
 *
 * <p>That message is not incidental text. It is prose the backend composed <em>around the values it
 * refused</em>, so {@code value '4444333322221111' rejected} is an ordinary thing for a driver to say
 * about a card operation. Two weaknesses follow from emitting it: the record's content reaches a file
 * that is read by more people, kept for longer and guarded less than the dataset it describes
 * (CWE-532), and a carriage return anywhere in that text splits the entry in two, letting whatever
 * composed the message choose the second entry's contents (CWE-117).
 *
 * <p>Seven call sites did this, across six classes, and one of them additionally published the
 * exception itself through a public accessor. Fixing seven sites leaves the eighth to be written next
 * week, so this suite asserts the property structurally: <strong>no source file in the module passes a
 * throwable to a logger, and {@link BackendDiagnostic} has nowhere to hold a message.</strong>
 */
@DisplayName("No raw backend failure reaches a log line or a renderable value")
class NoRawBackendDiagnosticTest {

    /** Repository-relative root of the module's main sources. */
    private static final String MAIN_SOURCE_ROOT = "app/java/src/main/java/com/vsergeychik/carddemo";

    /** Repository-relative path of the module descriptor, used as the checkout marker. */
    private static final String POM_PATH = "app/java/pom.xml";

    /** The start of a logging call: the field, the level, and the opening parenthesis. */
    private static final Pattern LOGGING_CALL = Pattern.compile(
            "(?:LOG|log|logger|LOGGER)\\s*\\.\\s*(error|warn|info|debug|trace|fatal)\\s*\\(");

    // =============================================================================================
    // The structural guard.
    // =============================================================================================

    @Nested
    @DisplayName("Structurally, across every main source file")
    class Structural {

        @Test
        @DisplayName("no logging call in the module passes a throwable")
        void noLoggingCallPassesAThrowable() {
            // Commons Logging publishes exactly two shapes per level: (Object message) and
            // (Object message, Throwable t). A call with a second top-level argument is therefore a call
            // that passes a throwable, by the API's own definition - so counting arguments IS the check,
            // and it cannot be evaded by naming the variable something else.
            List<String> offenders = new ArrayList<>();
            for (Path source : mainSources()) {
                String text = read(source);
                Matcher matcher = LOGGING_CALL.matcher(text);
                while (matcher.find()) {
                    List<String> arguments = topLevelArguments(text, matcher.end() - 1);
                    if (arguments.size() > 1) {
                        offenders.add(source.getFileName() + " -> LOG." + matcher.group(1)
                                + "(..) passes " + arguments.size() + " arguments; the second is '"
                                + arguments.get(1).strip() + "'");
                    }
                }
            }

            assertThat(offenders)
                    .as("a throwable handed to a logger emits its message and cause chain verbatim, and "
                            + "a driver's message is composed around the record it refused")
                    .isEmpty();
        }

        @Test
        @DisplayName("the argument scanner really does find a two-argument logging call")
        void theScannerFindsATwoArgumentCall() {
            // The guard above passes trivially if the scanner never matches anything, so the scanner is
            // pointed at the shape the defect actually had - a multi-line, concatenated message whose
            // text contains both a semicolon and parentheses, which is what defeated a simpler check.
            String defectiveCall = "        LOG.error(\"Could not \" + attempt + \" - \"\n"
                    + "                + diagnostic.describe() + \"; reporting file status \"\n"
                    + "                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)\n"
                    + "                + \" to the caller\", refusal);\n";

            Matcher matcher = LOGGING_CALL.matcher(defectiveCall);
            assertThat(matcher.find()).isTrue();
            List<String> arguments = topLevelArguments(defectiveCall, matcher.end() - 1);

            assertThat(arguments).hasSize(2);
            assertThat(arguments.get(1).strip()).isEqualTo("refusal");
        }

        @Test
        @DisplayName("the argument scanner does not mistake a one-argument call for two")
        void theScannerAcceptsASanitizedCall() {
            String sanitizedCall = "        LOG.error(\"Could not \" + attempt + \" - \"\n"
                    + "                + diagnostic.describe() + \"; reporting file status \"\n"
                    + "                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)\n"
                    + "                + \" to the caller\");\n";

            Matcher matcher = LOGGING_CALL.matcher(sanitizedCall);
            assertThat(matcher.find()).isTrue();

            assertThat(topLevelArguments(sanitizedCall, matcher.end() - 1)).hasSize(1);
        }

        @Test
        @DisplayName("the seven classes that logged a refusal are all present and all still log one")
        void theSevenClassesStillReportTheirRefusals() {
            // The guard above would also pass if somebody deleted the logging altogether, which would
            // trade one defect for another - a production abend with no diagnosis is not an improvement.
            // So each class that reported a refusal must still report one, through describe().
            List<String> withoutADiagnosticLine = new ArrayList<>();
            for (String fileName : List.of(
                    "account/AccountRepository.java",
                    "card/CardRepository.java",
                    "card/CardXrefRepository.java",
                    "statement/StatementTextWriter.java",
                    "statement/StatementHtmlWriter.java",
                    "transaction/DateParmReader.java")) {
                String text = read(repositoryFile(MAIN_SOURCE_ROOT + "/" + fileName));
                if (!text.contains(".describe()")) {
                    withoutADiagnosticLine.add(fileName);
                }
            }

            assertThat(withoutADiagnosticLine)
                    .as("each dataset access still says what the backend reported, in sanitized form")
                    .isEmpty();
        }

        @Test
        @DisplayName("BackendDiagnostic has no component and no accessor for a driver's message")
        void theDiagnosticHasNowhereToHoldAMessage() {
            assertThat(BackendDiagnostic.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactly("sqlState", "vendorCode", "exceptionType");

            List<String> accessors = Stream.of(BackendDiagnostic.class.getDeclaredMethods())
                    .map(Method::getName)
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).contains("message")
                            || name.toLowerCase(java.util.Locale.ROOT).contains("detail"))
                    .toList();
            assertThat(accessors)
                    .as("an accessor is all a future call site needs to re-introduce the leak")
                    .isEmpty();
        }
    }

    // =============================================================================================
    // Behaviourally: what a refusal actually renders as.
    // =============================================================================================

    @Nested
    @DisplayName("Behaviourally, for the kinds of message a driver actually composes")
    class Behavioural {

        /**
         * Driver messages of the shape a backend composes them, each carrying something a log must not.
         *
         * @return the message and the substring that must not survive
         */
        static Stream<org.junit.jupiter.params.provider.Arguments> driverMessages() {
            return Stream.of(
                    org.junit.jupiter.params.provider.Arguments.of(
                            "value '4444333322221111' rejected", "4444333322221111"),
                    org.junit.jupiter.params.provider.Arguments.of(
                            "column CARD_CVV_CD: 123 out of range", "123 out of range"),
                    org.junit.jupiter.params.provider.Arguments.of(
                            "row (00000000001, MARGARET, GOLD, PASSWORD) violates constraint",
                            "PASSWORD"),
                    org.junit.jupiter.params.provider.Arguments.of(
                            "SSN 123-45-6789 is not numeric", "123-45-6789"));
        }

        @ParameterizedTest(name = "{1} does not survive the diagnostic")
        @MethodSource("driverMessages")
        @DisplayName("nothing the driver said about the record reaches the rendering")
        void recordContentDoesNotSurvive(String driverMessage, String mustNotSurvive) {
            BackendDiagnostic diagnostic = BackendDiagnostic.of(
                    new DataAccessResourceFailureException("wrapped",
                            new SQLException(driverMessage, "22001", 1_400)));

            assertThat(diagnostic.describe()).doesNotContain(mustNotSurvive);
            assertThat(diagnostic.toString()).doesNotContain(mustNotSurvive);
            // And the codes that distinguish one refusal from another are all still there, because a
            // diagnostic that said nothing would be its own defect.
            assertThat(diagnostic.sqlState()).isEqualTo("22001");
            assertThat(diagnostic.vendorCode()).isEqualTo(1_400);
            assertThat(diagnostic.exceptionType())
                    .isEqualTo(DataAccessResourceFailureException.class.getName());
        }

        @Test
        @DisplayName("a control character in a driver's message cannot forge a second log entry")
        void aControlCharacterCannotForgeALogEntry() {
            BackendDiagnostic diagnostic = BackendDiagnostic.of(new SQLException(
                    "rejected\r\n2026-01-01 INFO  every dataset verified", "22001", 1));

            assertThat(diagnostic.describe()).doesNotContain("\n").doesNotContain("\r")
                    .doesNotContain("every dataset verified");
            assertThat(diagnostic.toString()).doesNotContain("\n").doesNotContain("\r");
        }

        @Test
        @DisplayName("the rendering is one line, so it cannot be split by anything it carries")
        void theRenderingIsOneLine() {
            BackendDiagnostic diagnostic =
                    BackendDiagnostic.of(new SQLException("anything at all", "08001", 17_002));

            assertThat(diagnostic.describe().lines()).hasSize(1);
            assertThat(diagnostic.describe())
                    .isEqualTo("backend refusal: SQLSTATE 08001, vendor code 17002, raised as "
                            + SQLException.class.getName());
        }
    }

    /**
     * The top-level arguments of a parenthesised argument list, split on commas at depth one.
     *
     * <p>Hand-written rather than regex-driven because the text being scanned defeats a regex: the
     * original defective call spanned four lines, and its message literal contained both a semicolon and
     * a parenthesised sub-expression. String and character literals are skipped, escapes inside them are
     * honoured, and nesting is tracked, so a comma inside a literal or inside a nested call does not
     * split an argument.
     *
     * @param text            the source text
     * @param openParenIndex  the index of the argument list's opening parenthesis
     * @return the top-level arguments, in order; a single empty entry for an empty list
     */
    private static List<String> topLevelArguments(String text, int openParenIndex) {
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        for (int index = openParenIndex; index < text.length(); index++) {
            char character = text.charAt(index);
            if (inString || inChar) {
                current.append(character);
                if (character == '\\') {
                    if (index + 1 < text.length()) {
                        current.append(text.charAt(++index));
                    }
                } else if (inString && character == '"') {
                    inString = false;
                } else if (inChar && character == '\'') {
                    inChar = false;
                }
                continue;
            }
            switch (character) {
                case '"' -> {
                    inString = true;
                    current.append(character);
                }
                case '\'' -> {
                    inChar = true;
                    current.append(character);
                }
                case '(' -> {
                    depth++;
                    if (depth > 1) {
                        current.append(character);
                    }
                }
                case ')' -> {
                    depth--;
                    if (depth == 0) {
                        arguments.add(current.toString());
                        return arguments;
                    }
                    current.append(character);
                }
                case ',' -> {
                    if (depth == 1) {
                        arguments.add(current.toString());
                        current.setLength(0);
                    } else {
                        current.append(character);
                    }
                }
                default -> current.append(character);
            }
        }
        throw new IllegalStateException("Unbalanced argument list from index " + openParenIndex);
    }

    /**
     * Every {@code .java} file under the module's main source root.
     *
     * @return the source files, in a stable order
     */
    private static List<Path> mainSources() {
        Path root = repositoryFile(MAIN_SOURCE_ROOT);
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> sources = walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
            assertThat(sources).as("the module must have main sources to scan").isNotEmpty();
            return sources;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not walk " + root, unreadable);
        }
    }

    /**
     * Reads one source file.
     *
     * @param source the file
     * @return its text
     */
    private static String read(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read " + source, unreadable);
        }
    }

    /**
     * Locates a repository-relative path by walking upwards from the working directory.
     *
     * <p>The same approach {@code CardDemoApplicationTest} uses, so a suite that reads the checkout does
     * it one way.
     *
     * @param relativePath the repository-relative path
     * @return the resolved path
     */
    private static Path repositoryFile(String relativePath) {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path resolved = candidate.resolve(relativePath);
            if (Files.exists(resolved)) {
                return resolved;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not find " + relativePath + " at or above "
                + Path.of("").toAbsolutePath() + "; the checkout marker is " + POM_PATH);
    }
}
