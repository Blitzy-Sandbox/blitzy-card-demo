package com.vsergeychik.carddemo.customer;

import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.customer.CustomerRepository.CustomerFile;
import com.vsergeychik.carddemo.customer.CustomerRepository.ReadResult;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The whole of {@code app/cbl/CBCUS01C.cbl} - {@code Type: BATCH COBOL Program},
 * {@code Function : Read and print customer data file.} - as one Java service. 178 lines of COBOL, invoked
 * as {@code STEP05 EXEC PGM=CBCUS01C} by {@code app/jcl/READCUST.jcl:L6}, which binds {@code CUSTFILE} to
 * the customer master KSDS at {@code L9-L10} and routes {@code SYSOUT} and {@code SYSPRINT} to the spool
 * at.
 *
 * <p>The application log is not a COBOL-observable artefact: {@code SYSOUT} is, and {@code SYSOUT} is
 * reproduced byte for byte in the returned list, which is what {@code CustomerFileReaderJob} writes to the
 * real spool and what every parity case is judged against.
 */
@Service
public class CustomerService {
    private static final Log LOG = LogFactory.getLog(CustomerService.class);

    /**
     * The COBOL {@code PROGRAM-ID}: {@value}.
     */
    public static final String PROGRAM_ID = "CBCUS01C";

    /**
     * The JCL step that invokes it: {@value}.
     */
    public static final String STEP_NAME = "STEP05";

    /**
     * The DD name of the input dataset: {@code CUSTFILE}.
     */
    public static final String DD_NAME = CustomerRepository.BATCH_DD_NAME;

    /**
     * {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'} - {@code app/cbl/CBCUS01C.cbl:L71}.
     */
    public static final String START_OF_EXECUTION = "START OF EXECUTION OF PROGRAM CBCUS01C";

    /**
     * {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'} - {@code app/cbl/CBCUS01C.cbl:L85}.
     */
    public static final String END_OF_EXECUTION = "END OF EXECUTION OF PROGRAM CBCUS01C";

    /**
     * {@code DISPLAY 'ERROR OPENING CUSTFILE'} - {@code app/cbl/CBCUS01C.cbl:L129}.
     */
    public static final String ERROR_OPENING_CUSTFILE = "ERROR OPENING CUSTFILE";

    /**
     * {@code DISPLAY 'ERROR READING CUSTOMER FILE'} - {@code app/cbl/CBCUS01C.cbl:L110}.
     */
    public static final String ERROR_READING_CUSTOMER_FILE = "ERROR READING CUSTOMER FILE";

    /**
     * {@code DISPLAY 'ERROR CLOSING CUSTOMER FILE'} - {@code app/cbl/CBCUS01C.cbl:L147}.
     */
    public static final String ERROR_CLOSING_CUSTOMER_FILE = "ERROR CLOSING CUSTOMER FILE";

    /**
     * {@code DISPLAY 'ABENDING PROGRAM'} - {@code app/cbl/CBCUS01C.cbl:L155}, the first statement of
     * {@code Z-ABEND-PROGRAM}.
     */
    public static final String ABENDING_PROGRAM = AbendException.ABEND_DISPLAY_TEXT;

    public static final List<String> ERROR_TEXTS = List.of(
            ERROR_READING_CUSTOMER_FILE,
            ERROR_OPENING_CUSTFILE,
            ERROR_CLOSING_CUSTOMER_FILE);

    /**
     * {@code 88 APPL-AOK VALUE 0} - {@code app/cbl/CBCUS01C.cbl:L62}.
     */
    public static final int APPL_AOK = FileStatus.APPL_AOK;

    /**
     * {@code 88 APPL-EOF VALUE 16} - {@code app/cbl/CBCUS01C.cbl:L63}.
     */
    public static final int APPL_EOF = FileStatus.APPL_EOF;

    public static final int APPL_RESULT_ASSUMED_FAILURE = AbendException.RETURN_CODE_ASSUMED_FAILURE;

    public static final int APPL_RESULT_FATAL = CustomerRepository.APPL_RESULT_FATAL;

    /**
     * The process return code of a normal end: {@value}.
     *
     * <p>{@code GOBACK} at {@code L87} leaves {@code RETURN-CODE} untouched, and the program never moves
     * anything into it, so a run that reaches {@code L87} ends with zero.
     */
    public static final int RETURN_CODE_NORMAL_END = AbendException.RETURN_CODE_OK;

    /**
     * {@code END-OF-FILE PIC X(01) VALUE 'N'} - the declared initial value,
     * {@code app/cbl/CBCUS01C.cbl:L65}.
     */
    public static final String END_OF_FILE_NO = "N";

    /**
     * {@code MOVE 'Y' TO END-OF-FILE} - the value the end-of-file arm moves,
     * {@code app/cbl/CBCUS01C.cbl:L108}.
     */
    public static final String END_OF_FILE_YES = "Y";

    public static final int DISPLAYS_PER_RECORD = 2;

    public static final int BANNER_LINE_COUNT = 2;

    /**
     * The declared width of every record line: {@value} bytes.
     *
     * <p>{@code app/cpy/CVCUS01Y.cpy} sums to exactly this, {@code FILLER X(168)} at bytes 333 to 500
     * included.
     */
    public static final int RECORD_LENGTH = CustomerRecord.RECORD_LENGTH;

    private final CustomerRepository customerRepository;

    private final FixedWidthCodec codec;

    /**
     * Constructs the service over the customer master.
     *
     * @param customerRepository the customer master's reader; must not be {@code null}
     * @throws NullPointerException if {@code customerRepository} is {@code null}
     */
    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = Objects.requireNonNull(customerRepository, "A CustomerRepository is "
                + "required: CBCUS01C reads the customer master and has nothing to do without it");
        this.codec = new FixedWidthCodec(customerRepository.datasetCharset());
    }

    /**
     * Runs {@code CBCUS01C} end to end and returns everything it emitted.
     *
     * <p>{@code Z-ABEND-PROGRAM} calls {@code CEE3ABD}, which terminates the run, so the Java equivalent
     * throws and the caller never sees an {@link Execution}.
     *
     * @return the emitted line sequence, the return code and the number of records read; never {@code null}
     * @throws AbendException if the open, any read, or the close reports a status the program treats as
     *     fatal - the Java form of {@code CALL 'CEE3ABD'} at {@code L158}
     */
    public Execution readAndPrintCustomerFile() {
        return readAndPrintCustomerFile(new Sysout());
    }

    /**
     * Runs {@code CBCUS01C} end to end, accumulating {@code SYSOUT} into a sink the caller owns.
     *
     * @param sysout the sink to accumulate into; must not be {@code null} and should be empty
     * @return the emitted line sequence, the return code and the number of records read; never {@code null}
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws AbendException if the open, any read, or the close reports a status the program treats as
     *     fatal
     */
    public Execution readAndPrintCustomerFile(Sysout sysout) {
        Objects.requireNonNull(sysout, "A SYSOUT sink is required: the displayed line sequence is this "
                + "program's entire observable output, so there is nothing to run without somewhere to "
                + "put it");
        if (!sysout.retains()) {
            throw new IllegalArgumentException("This overload returns an " + Execution.class
                    .getSimpleName() + ", which carries the emitted line sequence, but the supplied sink "
                    + "streams to a destination and keeps nothing. Run a streaming sink through "
                    + "readAndPrintCustomerFileTo(SysoutSink) - which returns the record count and no "
                    + "line sequence, because none is kept - or supply a capturing sink here.");
        }

        int recordsRead = runProgram(sysout);

        // GOBACK - RETURN-CODE is never moved into, so a normal end is zero. L87.
        return new Execution(sysout.lines(), RETURN_CODE_NORMAL_END, recordsRead);
    }

    /**
     * Runs {@code CBCUS01C} end to end, streaming every {@code DISPLAY} straight to {@code sysout} and
     * retaining none of it.
     *
     * @param sysout where every emitted line goes, one call per {@code DISPLAY}; must not be {@code null}
     * @return how many customer records the browse returned and the program displayed; never negative
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws AbendException if the open, any read, or the close reports a status the program treats as
     *     fatal - the Java form of {@code CALL 'CEE3ABD'} at {@code L158}
     */
    public int readAndPrintCustomerFileTo(SysoutSink sysout) {
        Objects.requireNonNull(sysout, "A SYSOUT destination is required: the displayed line sequence is "
                + "this program's entire observable output, so there is nothing to run without somewhere "
                + "to put it");

        return runProgram(new Sysout(sysout));
    }

    private int runProgram(Sysout sysout) {
        WorkingStorage workingStorage = new WorkingStorage();

        sysout.display(START_OF_EXECUTION);

        CustomerFile custFile = custfileOpen(sysout, workingStorage);

        try {
            int recordsRead = custfileDisplayLoop(sysout, workingStorage, custFile);

            custfileClose(sysout, workingStorage, custFile);

            sysout.display(END_OF_EXECUTION);

            return recordsRead;
        } finally {
            releaseHandle(custFile);
        }
    }

    private static void releaseHandle(CustomerFile custFile) {
        if (custFile.isClosed()) {
            return;
        }
        try {
            custFile.closeFile();
        } catch (RuntimeException cleanupFailure) {
            // The throwable is deliberately not handed to the logger, and neither is its message: a driver
            // composes its message around the value it refused, and a customer master row carries CUST-SSN,
            // CUST-DOB-YYYY-MM-DD and the customer's names (CWE-532), with a newline in that text able to
            // forge a second entry (CWE-117).
            LOG.warn("Releasing the " + DD_NAME + " browse of " + PROGRAM_ID + " after an incomplete run "
                    + "failed - " + BackendDiagnostic.of(cleanupFailure).describe()
                    + ". The run's own outcome is reported to the caller unchanged, because the run's own "
                    + "failure is the one that matters.");
        }
    }

    private int custfileDisplayLoop(Sysout sysout, WorkingStorage workingStorage,
            CustomerFile custFile) {
        int recordsRead = 0;
        while (!workingStorage.endOfFileIsYes()) {
            recordsRead += custfileDisplayIteration(sysout, workingStorage, custFile);
        }
        return recordsRead;
    }

    int custfileDisplayIteration(Sysout sysout, WorkingStorage workingStorage,
            CustomerFile custFile) {
        if (!workingStorage.endOfFileIsNo()) {
            return 0;
        }

        ReadResult result = custfileGetNext(sysout, workingStorage, custFile);

        if (!workingStorage.endOfFileIsNo()) {
            return 0;
        }

        sysout.displayCustomerRecord(recordImageOf(result, workingStorage));
        return 1;
    }

    private CustomerFile custfileOpen(Sysout sysout, WorkingStorage workingStorage) {
        workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);

        CustomerFile custFile = customerRepository.openInput();
        String status = custFile.openStatus();

        if (FileStatus.isOk(status)) {
            workingStorage.moveToApplResult(APPL_AOK);
        } else {
            workingStorage.moveToApplResult(APPL_RESULT_FATAL);
        }

        if (!workingStorage.applAok()) {
            throw reportAndAbend(sysout, workingStorage, ERROR_OPENING_CUSTFILE, status);
        }
        return custFile;
    }

    private ReadResult custfileGetNext(Sysout sysout, WorkingStorage workingStorage,
            CustomerFile custFile) {
        ReadResult result = custFile.readNext();
        String status = result.status();

        if (FileStatus.isOk(status)) {
            workingStorage.moveToApplResult(APPL_AOK);

            sysout.displayCustomerRecord(recordImageOf(result, workingStorage));
        } else if (FileStatus.isEndOfFile(status)) {
            workingStorage.moveToApplResult(APPL_EOF);
        } else {
            workingStorage.moveToApplResult(APPL_RESULT_FATAL);
        }

        if (!workingStorage.applAok()) {
            if (workingStorage.applEof()) {
                workingStorage.moveEndOfFile(END_OF_FILE_YES);
            } else {
                throw reportAndAbend(sysout, workingStorage, ERROR_READING_CUSTOMER_FILE, status);
            }
        }
        return result;
    }

    private void custfileClose(Sysout sysout, WorkingStorage workingStorage, CustomerFile custFile) {
        workingStorage.addToZeroGivingApplResult(APPL_RESULT_ASSUMED_FAILURE);

        String status = custFile.closeFile();

        if (FileStatus.isOk(status)) {
            workingStorage.subtractApplResultFromApplResult();
        } else {
            workingStorage.addToZeroGivingApplResult(APPL_RESULT_FATAL);
        }

        if (!workingStorage.applAok()) {
            throw reportAndAbend(sysout, workingStorage, ERROR_CLOSING_CUSTOMER_FILE, status);
        }
    }

    private String recordImageOf(ReadResult result, WorkingStorage workingStorage) {
        if (result.customer().isEmpty()) {
            throw new IllegalStateException(
                    "A read of the customer master reported file status '" + result.status() + "' and left "
                            + "END-OF-FILE = '" + workingStorage.endOfFileFlag() + "', so DISPLAY "
                            + "CUSTOMER-RECORD was reached with no record to display. Only the '"
                            + FileStatus.OK + "' arm displays, and that arm always carries the decoded "
                            + "record and the bytes it was decoded from.");
        }
        return result.requireStoredImage();
    }

    private AbendException reportAndAbend(Sysout sysout, WorkingStorage workingStorage,
            String errorText, String status) {
        sysout.display(errorText);

        workingStorage.moveToIoStatus(status);
        String statusLine = displayIoStatus(sysout, workingStorage);

        return abendProgram(sysout, workingStorage, errorText + " - " + statusLine);
    }

    private String displayIoStatus(Sysout sysout, WorkingStorage workingStorage) {
        String statusLine = FileStatus.toDisplayLine(workingStorage.ioStatus());
        sysout.display(statusLine);
        return statusLine;
    }

    private AbendException abendProgram(Sysout sysout, WorkingStorage workingStorage, String reason) {
        sysout.display(ABENDING_PROGRAM);

        return AbendException.standard(PROGRAM_ID, workingStorage.applResult(), reason);
    }

    /**
     * {@code 88 APPL-AOK VALUE 0} - {@code app/cbl/CBCUS01C.cbl:L62}.
     *
     * @param applResult the value {@code APPL-RESULT} holds
     * @return {@code true} when the register holds {@link #APPL_AOK}
     */
    public static boolean isApplAok(int applResult) {
        return applResult == APPL_AOK;
    }

    /**
     * {@code 88 APPL-EOF VALUE 16} - {@code app/cbl/CBCUS01C.cbl:L63}.
     *
     * @param applResult the value {@code APPL-RESULT} holds
     * @return {@code true} when the register holds {@link #APPL_EOF}
     */
    public static boolean isApplEof(int applResult) {
        return applResult == APPL_EOF;
    }

    /**
     * How many lines a normal end emits for a given record count: {@link #BANNER_LINE_COUNT} +
     * {@link #DISPLAYS_PER_RECORD} × {@code recordsRead}.
     *
     * @param recordsRead how many records the browse returned; must not be negative
     * @return the exact number of lines a normal end emits
     * @throws IllegalArgumentException if {@code recordsRead} is negative
     */
    public static int expectedSysoutLineCount(int recordsRead) {
        if (recordsRead < 0) {
            throw new IllegalArgumentException("A record count cannot be negative: " + recordsRead
                    + ". A browse returns zero records for an empty dataset and never fewer.");
        }
        return BANNER_LINE_COUNT + DISPLAYS_PER_RECORD * recordsRead;
    }

    /**
     * Everything one run of {@code CBCUS01C} produced: the {@code SYSOUT} line sequence, the process return
     * code, and how many records the browse returned.
     *
     * @param sysout every emitted line, in emission order: the opening banner, then two identical
     *     500-character images per record, then the closing banner
     * @param returnCode the process return code, always {@link #RETURN_CODE_NORMAL_END} for a returned
     *     execution - {@code GOBACK} at {@code L87} leaves {@code RETURN-CODE} untouched
     * @param recordsRead how many records the browse returned, which is {@code (sysout.size() - 2) / 2} on
     *     a normal end
     */
    public record Execution(List<String> sysout, int returnCode, int recordsRead) {
        public Execution {
            Objects.requireNonNull(sysout, "An execution carries its emitted lines; an empty list is the "
                    + "way to say nothing was emitted, and null is not");
            if (recordsRead < 0) {
                throw new IllegalArgumentException("An execution cannot have read " + recordsRead
                        + " records. A browse returns zero for an empty dataset and never fewer.");
            }
            int expected = expectedSysoutLineCount(recordsRead);
            if (sysout.size() != expected) {
                throw new IllegalArgumentException("An execution that read " + recordsRead
                        + " records emits exactly " + expected + " lines - " + BANNER_LINE_COUNT
                        + " banners plus " + DISPLAYS_PER_RECORD + " identical images per record, because "
                        + "CBCUS01C displays every record twice (L96 and L78) - but this one carries "
                        + sysout.size() + ". Either a display was dropped or one was added.");
            }
            sysout = List.copyOf(sysout);
        }

        public int lineCount() {
            return sysout.size();
        }

        /**
         * The record images alone, with the two banners removed.
         *
         * @return the record lines; immutable and never {@code null}
         */
        public List<String> recordLines() {
            return sysout.subList(1, sysout.size() - 1);
        }
    }

    /**
     * The {@code SYSOUT} of one execution: an ordered, append-only line sequence that also reaches the
     * module's logger.
     *
     * <p>Parity is untouched by that choice, because the application log is not an artefact the COBOL
     * produces - {@code SYSOUT} is, and {@code SYSOUT} is what {@link #lines()} returns, byte for byte.
     */
    public static final class Sysout {
        private final List<String> lines;

        private final SysoutSink destination;

        private int recordImageCount;

        private int lineCount;

        public Sysout() {
            List<String> captured = new ArrayList<>();
            this.lines = captured;
            this.destination = captured::add;
        }

        /**
         * Creates a sink that streams every line to {@code destination} and keeps none of them.
         *
         * @param destination where each emitted line goes, one call per {@code DISPLAY}; must not be
         *     {@code null}
         * @throws NullPointerException if {@code destination} is {@code null}
         */
        public Sysout(SysoutSink destination) {
            this.lines = null;
            this.destination = Objects.requireNonNull(destination, "A streaming SYSOUT sink needs "
                    + "somewhere to stream to");
        }

        public boolean retains() {
            return lines != null;
        }

        void display(String line) {
            Objects.requireNonNull(line, "A DISPLAY writes a line, and a COBOL literal is never null");
            emit(line);
            LOG.info(line);
        }

        void displayCustomerRecord(String recordImage) {
            Objects.requireNonNull(recordImage, "DISPLAY CUSTOMER-RECORD writes the record's group "
                    + "image, and a rendered group image is never null");
            if (recordImage.length() != RECORD_LENGTH) {
                throw new IllegalArgumentException("A CUSTOMER-RECORD image is exactly " + RECORD_LENGTH
                        + " characters - CVCUS01Y's fields plus its trailing FILLER X(168) - and this one "
                        + "is " + recordImage.length() + ". A short image means the FILLER was omitted, "
                        + "which breaks every offset downstream of it.");
            }
            emit(recordImage);
            recordImageCount++;
            if (LOG.isDebugEnabled()) {
                // Deliberately NOT the image: it holds CUST-SSN, CUST-DOB-YYYY-MM-DD, CUST-GOVT-ISSUED-ID
                // and the customer's names (CWE-532). The ordinal is enough to correlate a log entry with a
                // position in the browse.
                LOG.debug("Displayed CUSTOMER-RECORD image " + recordImageCount + " of the " + DD_NAME
                        + " browse (" + RECORD_LENGTH + " bytes, withheld from this log)");
            }
        }

        /**
         * How many record images have been emitted so far.
         *
         * @return the count
         */
        public int recordImageCount() {
            return recordImageCount;
        }

        public List<String> lines() {
            if (lines == null) {
                throw new IllegalStateException("This sink streamed its " + lineCount + " lines to a "
                        + "destination and kept none of them, so there is no sequence to hand back. A "
                        + "caller that needs the sequence - a parity case, or a test - constructs a "
                        + "capturing sink with the no-argument constructor.");
            }
            return Collections.unmodifiableList(new ArrayList<>(lines));
        }

        public int lineCount() {
            return lineCount;
        }

        private void emit(String line) {
            destination.write(line);
            lineCount++;
        }
    }

    @FunctionalInterface
    public interface SysoutSink {
        void write(String line);
    }

    public record PrintStreamSysoutSink(PrintStream stream) implements SysoutSink {
        public PrintStreamSysoutSink {
            Objects.requireNonNull(stream, "A print stream is required to emit SYSOUT lines");
        }

        /**
         * Writes the line and terminates it, which is what one {@code DISPLAY} produces.
         *
         * @param line the line; never {@code null}
         * @throws NullPointerException if {@code line} is {@code null}
         */
        @Override
        public void write(String line) {
            Objects.requireNonNull(line, "A DISPLAY never writes an absent line");
            stream.println(line);
        }
    }

    /**
     * The {@code //SYSOUT DD SYSOUT=*} equivalent for this service: a sink over the standard output stream,
     * encoding each line in the code page the customer master is read in.
     *
     * @return a sink over the standard output stream; never {@code null}
     */
    public SysoutSink standardOutputSysoutSink() {
        return standardOutput(datasetCharset());
    }

    /**
     * The code page this service renders records in - the one its repository reads them in.
     *
     * @return the active dataset code page; never {@code null}
     */
    public Charset datasetCharset() {
        return codec.charset();
    }

    /**
     * The {@code //SYSOUT DD SYSOUT=*} equivalent: a sink over the standard output stream, encoding each
     * line in the code page given.
     *
     * <p>On the mainframe the record's bytes pass from the KSDS to the spool unchanged; encoding them in
     * whatever {@code file.encoding} happens to be would corrupt every byte outside the invariant ASCII
     * range and would make the emitted line depend on the JVM rather than on the program.
     *
     * @param charset the code page to encode each line in - the active dataset code page; must not be
     *     {@code null}
     * @return a sink over the standard output stream; never {@code null}
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public static SysoutSink standardOutput(Charset charset) {
        Objects.requireNonNull(charset, "A code page is required for SYSOUT: a displayed record is the "
                + "dataset's own bytes, and the platform default is never assumed");
        return new PrintStreamSysoutSink(
                new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset));
    }

    /**
     * The program's {@code WORKING-STORAGE}, for exactly one execution.
     */
    static final class WorkingStorage {
        private int applResult;

        private String endOfFile = END_OF_FILE_NO;

        private String ioStatus;

        WorkingStorage() {
        }

        boolean applAok() {
            return isApplAok(applResult);
        }

        boolean applEof() {
            return isApplEof(applResult);
        }

        void moveToApplResult(int value) {
            this.applResult = value;
        }

        void addToZeroGivingApplResult(int addend) {
            this.applResult = 0 + addend;
        }

        void subtractApplResultFromApplResult() {
            this.applResult = this.applResult - this.applResult;
        }

        int applResult() {
            return applResult;
        }

        boolean endOfFileIsYes() {
            return END_OF_FILE_YES.equals(endOfFile);
        }

        boolean endOfFileIsNo() {
            return END_OF_FILE_NO.equals(endOfFile);
        }

        void moveEndOfFile(String value) {
            this.endOfFile = Objects.requireNonNull(value, "END-OF-FILE is PIC X(01): it always holds a "
                    + "character, and a COBOL alphanumeric item has no null state");
        }

        String endOfFileFlag() {
            return endOfFile;
        }

        void moveToIoStatus(String status) {
            this.ioStatus = Objects.requireNonNull(status, "A file status is two characters and is never "
                    + "null; the repository reports one for every operation");
        }

        String ioStatus() {
            if (ioStatus == null) {
                throw new IllegalStateException("Z-DISPLAY-IO-STATUS was reached with nothing in "
                        + "IO-STATUS. All three fatal arms MOVE CUSTFILE-STATUS TO IO-STATUS first - "
                        + "CBCUS01C.cbl:L111, L130 and L148 - so reaching the renderer without one is a "
                        + "defect in the translation, not a file status.");
            }
            return ioStatus;
        }
    }
}
