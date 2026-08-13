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
 */
@DisplayName("No raw backend failure reaches a log line or a renderable value")
class NoRawBackendDiagnosticTest {
    private static final String MAIN_SOURCE_ROOT = "app/java/src/main/java/com/vsergeychik/carddemo";

    private static final String POM_PATH = "app/java/pom.xml";

    private static final Pattern LOGGING_CALL = Pattern.compile(
            "(?:LOG|log|logger|LOGGER)\\s*\\.\\s*(error|warn|info|debug|trace|fatal)\\s*\\(");

    @Nested
    @DisplayName("Structurally, across every main source file")
    class Structural {
        @Test
        @DisplayName("no logging call in the module passes a throwable")
        void noLoggingCallPassesAThrowable() {
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

    @Nested
    @DisplayName("Behaviourally, for the kinds of message a driver actually composes")
    class Behavioural {
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

    private static String read(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read " + source, unreadable);
        }
    }

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
