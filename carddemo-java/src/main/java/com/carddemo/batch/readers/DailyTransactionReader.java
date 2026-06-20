package com.carddemo.batch.readers;

import com.carddemo.exception.FileAccessException;
import com.carddemo.model.entity.DailyTransaction;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.mapping.FieldSetMapper;
import org.springframework.batch.item.file.transform.FieldSet;
import org.springframework.batch.item.file.transform.FixedLengthTokenizer;
import org.springframework.batch.item.file.transform.Range;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Spring Batch reader for the daily-transaction staging file, replacing the sequential
 * DALYTRAN read of COBOL {@code CBTRN01C} and the {@code DALYTRAN} DD of {@code POSTTRAN.jcl}
 * (source commit {@code 27d6c6f}; REFERENCE ONLY, COBOL is not copied). Parses the 350-byte
 * fixed-width {@code CVTRA06Y} layout from an S3-backed {@code Resource} and emits one
 * {@link DailyTransaction} per record, decoding the zoned-decimal trailing-sign overpunch
 * amount to a scale-2 {@link BigDecimal}.
 *
 * <p>The record is read with ISO-8859-1 so every byte maps 1:1 to one character, preserving
 * the exact fixed-width offsets. A non-success open or a malformed record surfaces as
 * {@link FileAccessException}, the idiomatic replacement for the COBOL abend path
 * ({@code Z-ABEND-PROGRAM}).
 */
@Component
@StepScope
public class DailyTransactionReader extends FlatFileItemReader<DailyTransaction> {

    /** Resource loader (S3 protocol resolver supplied by {@code AwsConfig}) used to resolve the input. */
    private final ResourceLoader resourceLoader;

    /** Late-bound {@code s3://} location of the daily-transaction staging object. */
    private final String inputLocation;

    /**
     * Creates a step-scoped reader bound to the daily-transaction staging object. The input
     * location is late-bound from the {@code inputLocation} job parameter when present,
     * otherwise from the {@code carddemo.batch.daily-transaction.input-location} property,
     * otherwise the default S3 object {@code s3://carddemo-batch-input/dailytran.txt}. The
     * resource and tokenizer are configured in {@link #afterPropertiesSet()} (not the
     * constructor) so no partially-constructed instance escapes through an overridable setter.
     *
     * @param resourceLoader the resource loader (S3 protocol resolver supplied by
     *                       {@code com.carddemo.config.AwsConfig}) used to resolve the location
     * @param inputLocation  the resolved {@code s3://} URL of the staging object
     */
    public DailyTransactionReader(
            ResourceLoader resourceLoader,
            @Value("#{jobParameters['inputLocation'] ?: "
                    + "'${carddemo.batch.daily-transaction.input-location:s3://carddemo-batch-input/dailytran.txt}'}")
            String inputLocation) {
        this.resourceLoader = resourceLoader;
        this.inputLocation = inputLocation;
    }

    /**
     * Configures the resource, ISO-8859-1 encoding (1:1 byte-to-character mapping preserving the
     * 350-byte offsets), strict open semantics, and the byte-exact fixed-width line mapper, then
     * delegates to the superclass for its own initialization. Invoked by the Spring
     * {@code InitializingBean} lifecycle for the step-scoped instance before {@link #open}.
     *
     * @throws Exception if superclass initialization fails (e.g. the line mapper is missing)
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        setName("dailyTransactionReader");
        setResource(resourceLoader.getResource(inputLocation));
        setEncoding(StandardCharsets.ISO_8859_1.name());
        setLinesToSkip(0);
        setStrict(true);
        setLineMapper(buildLineMapper());
        super.afterPropertiesSet();
    }

    /**
     * Opens the underlying resource, translating a non-success open ({@link ItemStreamException},
     * e.g. a missing object under {@code setStrict(true)}) into {@link FileAccessException} — the
     * COBOL {@code OPEN}-failure abend equivalent.
     *
     * @param executionContext the step execution context
     */
    @Override
    public void open(ExecutionContext executionContext) {
        try {
            super.open(executionContext);
        } catch (ItemStreamException ex) {
            throw new FileAccessException("Error opening daily transaction file", ex);
        }
    }

    /**
     * Reads the next record, translating a malformed record ({@link FlatFileParseException} —
     * wrong length, bad overpunch, or non-numeric field) into {@link FileAccessException}. Returns
     * {@code null} when the resource is exhausted (the COBOL {@code FILE STATUS '10'} EOF), which
     * signals Spring Batch to stop the step.
     *
     * @return the next {@link DailyTransaction}, or {@code null} at end of file
     * @throws Exception propagated by the superclass for non-parse read failures
     */
    @Override
    public DailyTransaction read() throws Exception {
        try {
            return super.read();
        } catch (FlatFileParseException ex) {
            throw new FileAccessException(
                    "Error reading daily transaction record at line " + ex.getLineNumber(), ex);
        }
    }

    /**
     * Builds the byte-exact line mapper for the 350-byte {@code CVTRA06Y} layout. The 14th
     * {@code filler} range completes the strict 350-character width validation but is not mapped.
     *
     * @return a parameterized {@link DefaultLineMapper} for {@link DailyTransaction}
     */
    private static DefaultLineMapper<DailyTransaction> buildLineMapper() {
        FixedLengthTokenizer tokenizer = new FixedLengthTokenizer();
        tokenizer.setStrict(true);
        tokenizer.setNames(
                "dalytranId", "dalytranTypeCd", "dalytranCatCd", "dalytranSource",
                "dalytranDesc", "dalytranAmt", "dalytranMerchantId", "dalytranMerchantName",
                "dalytranMerchantCity", "dalytranMerchantZip", "dalytranCardNum",
                "dalytranOrigTs", "dalytranProcTs", "filler");
        tokenizer.setColumns(
                new Range(1, 16), new Range(17, 18), new Range(19, 22), new Range(23, 32),
                new Range(33, 132), new Range(133, 143), new Range(144, 152), new Range(153, 202),
                new Range(203, 252), new Range(253, 262), new Range(263, 278), new Range(279, 304),
                new Range(305, 330), new Range(331, 350));
        DefaultLineMapper<DailyTransaction> lineMapper = new DefaultLineMapper<>();
        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(new DailyTransactionFieldSetMapper());
        return lineMapper;
    }

    /**
     * Decodes an 11-character zoned-decimal {@code S9(09)V99} amount with a trailing-sign
     * overpunch on the last byte into a scale-2 {@link BigDecimal} (AAP D-001 / §0.8.2).
     *
     * <p>Overpunch decode of the final character: {@code '{'} = digit 0 positive; {@code 'A'..'I'} =
     * digits 1..9 positive; {@code '}'} = digit 0 negative; {@code 'J'..'R'} = digits 1..9 negative;
     * {@code '0'..'9'} = that digit positive (no overpunch). The leading characters are plain
     * digits; the decoded final digit is appended to form an 11-digit integer scaled down by two.
     * A blank field yields {@code 0.00}; an invalid sign or non-digit head propagates as a read
     * failure (mapped to {@link FileAccessException}). {@link BigDecimal} is used exclusively — never
     * {@code float}/{@code double}.
     *
     * @param field the raw 11-character amount token
     * @return the decoded scale-2 amount
     */
    private static BigDecimal decodeSignedAmount(String field) {
        String s = field == null ? "" : field.trim();
        if (s.isEmpty()) {
            return BigDecimal.ZERO.movePointLeft(2);
        }
        char sign = s.charAt(s.length() - 1);
        String head = s.substring(0, s.length() - 1);
        int lastDigit;
        boolean negative;
        switch (sign) {
            case '{' -> {
                lastDigit = 0;
                negative = false;
            }
            case 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I' -> {
                lastDigit = sign - 'A' + 1;
                negative = false;
            }
            case '}' -> {
                lastDigit = 0;
                negative = true;
            }
            case 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R' -> {
                lastDigit = sign - 'J' + 1;
                negative = true;
            }
            case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                lastDigit = sign - '0';
                negative = false;
            }
            default -> throw new IllegalArgumentException(
                    "Invalid overpunch sign '" + sign + "' in amount '" + field + "'");
        }
        BigDecimal value = new BigDecimal(head + lastDigit).movePointLeft(2);
        return negative ? value.negate() : value;
    }

    /**
     * Maps the tokenized {@link FieldSet} to a {@link DailyTransaction}, aligning each token to the
     * entity's declared type: alphanumeric fields via {@code readString} (already trimmed), the
     * numeric category code via {@code readInt}, the numeric merchant id via {@code readLong}, and
     * the amount via the overpunch decoder. The {@code filler} token is intentionally not read.
     */
    private static final class DailyTransactionFieldSetMapper implements FieldSetMapper<DailyTransaction> {

        @Override
        public DailyTransaction mapFieldSet(FieldSet fieldSet) {
            DailyTransaction tran = new DailyTransaction();
            tran.setDalytranId(fieldSet.readString("dalytranId"));
            tran.setDalytranTypeCd(fieldSet.readString("dalytranTypeCd"));
            tran.setDalytranCatCd(fieldSet.readInt("dalytranCatCd"));
            tran.setDalytranSource(fieldSet.readString("dalytranSource"));
            tran.setDalytranDesc(fieldSet.readString("dalytranDesc"));
            tran.setDalytranAmt(decodeSignedAmount(fieldSet.readString("dalytranAmt")));
            tran.setDalytranMerchantId(fieldSet.readLong("dalytranMerchantId"));
            tran.setDalytranMerchantName(fieldSet.readString("dalytranMerchantName"));
            tran.setDalytranMerchantCity(fieldSet.readString("dalytranMerchantCity"));
            tran.setDalytranMerchantZip(fieldSet.readString("dalytranMerchantZip"));
            tran.setDalytranCardNum(fieldSet.readString("dalytranCardNum"));
            tran.setDalytranOrigTs(fieldSet.readString("dalytranOrigTs"));
            tran.setDalytranProcTs(fieldSet.readString("dalytranProcTs"));
            return tran;
        }
    }
}
