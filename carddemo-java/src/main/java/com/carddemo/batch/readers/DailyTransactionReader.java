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
 * {@code DALYTRAN} read of COBOL {@code CBTRN01C} and the {@code DALYTRAN} DD of
 * {@code POSTTRAN.jcl} (source commit {@code 27d6c6f}). Parses the 350-byte fixed-width
 * {@code CVTRA06Y} layout from an S3 object and emits {@link DailyTransaction}, decoding
 * the zoned-decimal trailing-sign overpunch amount to {@link BigDecimal} (scale 2).
 *
 * <p>Wired into a Spring Batch step by {@code com.carddemo.batch.jobs}; it defines no job
 * or step itself. A failed open or a malformed record is surfaced as
 * {@link FileAccessException}, the Java equivalent of the COBOL abend path; end-of-file is
 * the native {@code null} return of {@link FlatFileItemReader#read()}.</p>
 */
@Component
@StepScope
public class DailyTransactionReader extends FlatFileItemReader<DailyTransaction> {

    /** Loader that resolves the {@code s3://} location to an S3-backed {@link org.springframework.core.io.Resource}. */
    private final ResourceLoader resourceLoader;

    /** Step-scoped {@code s3://} location of the daily-transaction file. */
    private final String inputLocation;

    /**
     * Constructs the step-scoped reader, late-binding the S3 input location so a job
     * parameter may override the configured property (otherwise the shown default). The
     * inherited reader is configured in {@link #afterPropertiesSet()} rather than here so
     * no overridable method is invoked during construction.
     *
     * @param resourceLoader the loader resolving the {@code s3://} URL to an S3 resource
     * @param inputLocation  the {@code s3://} location of the daily-transaction file
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
     * Configures the inherited {@link FlatFileItemReader} once the bean is fully
     * constructed: a strict 350-byte fixed-width line mapper over the ISO-8859-1 encoded
     * S3 resource (one byte per char to preserve the fixed-width offsets), no header lines,
     * and strict resource resolution. Runs on each step-scoped instantiation so the
     * late-bound {@code inputLocation} is honoured per job execution.
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
     * Opens the underlying resource, translating the Spring Batch open failure into a
     * {@link FileAccessException} (the abend-equivalent of a non-{@code '00'} OPEN status).
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
     * Reads the next record, translating a parse failure (malformed record, wrong length,
     * or bad overpunch) into a {@link FileAccessException}. Returns {@code null} at
     * end-of-file so Spring Batch stops the step.
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
     * Builds the line mapper with a strict 350-byte fixed-length tokenizer whose 14 named
     * columns mirror the {@code CVTRA06Y} byte layout exactly (the trailing {@code filler}
     * column completes the 350-byte width and is not read by the mapper).
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
     * Decodes an 11-character zoned-decimal amount with a trailing-sign overpunch on the
     * last byte (9 integer digits + 2 implied decimals) into a {@link BigDecimal} of scale
     * 2. A blank field yields {@code 0.00}; an invalid sign or non-digit head throws,
     * which propagates as a read failure mapped to {@link FileAccessException}.
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
     * Maps the tokenized {@link FieldSet} to a {@link DailyTransaction}. Alphanumeric fields
     * are read (and trimmed) as strings, the category code and merchant id as numerics, and
     * the amount via the overpunch decoder; the trailing {@code filler} column is ignored.
     */
    private static final class DailyTransactionFieldSetMapper
            implements FieldSetMapper<DailyTransaction> {

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
