package com.carddemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Integration tests for {@link TransactionRepository} against a real PostgreSQL 16 Testcontainer.
 * The transaction table is unseeded (zero rows), so each test inserts its own deterministic data to
 * verify the COTRN02C auto-id query (findMaxTranId), the CBTRN03C inclusive date-range query
 * (findByProcessingDateRange), the COTRN00C 10-rows/page browse (inherited findAll(Pageable)), and
 * the COTRN00C "start-at" (GTEQ) filtered browse (findByTranIdGreaterThanEqual).
 */
class TransactionRepositoryIT extends AbstractRepositoryIT {

    @Autowired
    private TransactionRepository transactionRepository;

    private Transaction newTransaction(String tranId, String procDate) {
        Transaction tx = new Transaction();
        tx.setTranId(tranId);
        tx.setTranTypeCd("01");
        tx.setTranCatCd(1);
        tx.setTranSource("POS TERM");
        tx.setTranDesc("TEST TRANSACTION");
        tx.setTranAmt(new BigDecimal("100.00"));
        tx.setTranMerchantId(123456789L);
        tx.setTranMerchantName("TEST MERCHANT");
        tx.setTranMerchantCity("TEST CITY");
        tx.setTranMerchantZip("12345");
        tx.setTranCardNum("0000000000000001");
        tx.setTranOrigTs("2023-01-05-10.00.00.000000");
        tx.setTranProcTs(procDate + "-10.00.00.000000");
        return tx;
    }

    private void seedTwelveTransactions() {
        List<Transaction> txns = List.of(
                newTransaction("0000000000000001", "2023-01-01"),
                newTransaction("0000000000000002", "2023-01-10"),
                newTransaction("0000000000000003", "2023-01-20"),
                newTransaction("0000000000000004", "2023-01-31"),
                newTransaction("0000000000000005", "2023-02-01"),
                newTransaction("0000000000000006", "2023-02-10"),
                newTransaction("0000000000000007", "2023-02-20"),
                newTransaction("0000000000000008", "2023-02-28"),
                newTransaction("0000000000000009", "2023-03-01"),
                newTransaction("0000000000000010", "2023-03-10"),
                newTransaction("0000000000000011", "2023-03-20"),
                newTransaction("0000000000000012", "2023-03-31"));
        transactionRepository.saveAll(txns);
        transactionRepository.flush();
    }

    @Test
    void transactionTableIsInitiallyEmpty() {
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    void findMaxTranIdReturnsNullWhenEmpty() {
        assertThat(transactionRepository.findMaxTranId()).isNull();
    }

    @Test
    void findMaxTranIdReturnsHighestIdAfterInsert() {
        seedTwelveTransactions();

        assertThat(transactionRepository.findMaxTranId()).isEqualTo("0000000000000012");
    }

    @Test
    void savedTransactionPreservesAmountScaleAndProcessingTimestamp() {
        seedTwelveTransactions();

        Transaction tx = transactionRepository.findById("0000000000000001").orElseThrow();
        assertThat(tx.getTranAmt()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(tx.getTranAmt().scale()).isEqualTo(2);
        assertThat(tx.getTranProcTs()).startsWith("2023-01-01");
    }

    @Test
    void findByProcessingDateRangeReturnsJanuaryTransactions() {
        seedTwelveTransactions();

        List<Transaction> result =
                transactionRepository.findByProcessingDateRange("2023-01-01", "2023-01-31");

        assertThat(result).extracting(Transaction::getTranId)
                .containsExactly("0000000000000001", "0000000000000002",
                        "0000000000000003", "0000000000000004");
    }

    @Test
    void findByProcessingDateRangeIsInclusiveOfBoundaries() {
        seedTwelveTransactions();

        List<Transaction> february =
                transactionRepository.findByProcessingDateRange("2023-02-01", "2023-02-28");
        assertThat(february).extracting(Transaction::getTranId)
                .containsExactly("0000000000000005", "0000000000000006",
                        "0000000000000007", "0000000000000008");

        List<Transaction> innerFebruary =
                transactionRepository.findByProcessingDateRange("2023-02-02", "2023-02-27");
        assertThat(innerFebruary).extracting(Transaction::getTranId)
                .containsExactly("0000000000000006", "0000000000000007");
    }

    @Test
    void findByProcessingDateRangeSpansMultipleMonths() {
        seedTwelveTransactions();

        List<Transaction> result =
                transactionRepository.findByProcessingDateRange("2023-01-01", "2023-02-28");

        assertThat(result).extracting(Transaction::getTranId)
                .containsExactly("0000000000000001", "0000000000000002", "0000000000000003",
                        "0000000000000004", "0000000000000005", "0000000000000006",
                        "0000000000000007", "0000000000000008");
    }

    @Test
    void findAllBrowsesTenRowsPerPage() {
        seedTwelveTransactions();

        Page<Transaction> firstPage = transactionRepository.findAll(PageRequest.of(0, 10));
        assertThat(firstPage.getTotalElements()).isEqualTo(12L);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent()).hasSize(10);

        Page<Transaction> secondPage = transactionRepository.findAll(PageRequest.of(1, 10));
        assertThat(secondPage.getContent()).hasSize(2);
    }

    @Test
    void findByTranIdGreaterThanEqualStartsAtKeyAndReadsForward() {
        // COTRN00C STARTBR (GTEQ): the browse begins at the supplied id and reads forward in
        // ascending id order. Starting at id 5 yields ids 5..12 (eight rows) on the first page.
        seedTwelveTransactions();

        Page<Transaction> page = transactionRepository.findByTranIdGreaterThanEqual(
                "0000000000000005", PageRequest.of(0, 10, Sort.by("tranId").ascending()));

        assertThat(page.getTotalElements()).isEqualTo(8L);
        assertThat(page.getContent()).extracting(Transaction::getTranId)
                .containsExactly("0000000000000005", "0000000000000006", "0000000000000007",
                        "0000000000000008", "0000000000000009", "0000000000000010",
                        "0000000000000011", "0000000000000012");
    }

    @Test
    void findByTranIdGreaterThanEqualReturnsEmptyWhenStartIsPastLastId() {
        seedTwelveTransactions();

        Page<Transaction> page = transactionRepository.findByTranIdGreaterThanEqual(
                "0000000000000099", PageRequest.of(0, 10, Sort.by("tranId").ascending()));

        assertThat(page.getTotalElements()).isZero();
        assertThat(page.getContent()).isEmpty();
    }
}
