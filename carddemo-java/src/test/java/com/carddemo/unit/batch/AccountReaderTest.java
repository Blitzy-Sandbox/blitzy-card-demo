package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.readers.AccountReader;
import com.carddemo.model.entity.Account;
import com.carddemo.repository.AccountRepository;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Unit tests for {@link AccountReader} — the Spring Batch reader that replaces the sequential
 * {@code ACCTFILE} scan of COBOL batch program {@code CBACT01C} over the 300-byte {@code CVACT01Y}
 * {@code ACCOUNT-RECORD} layout (REFERENCE-ONLY, COBOL is not copied; source commit {@code 27d6c6f}).
 *
 * <p>These are pure-JVM tests: {@link AccountRepository} is a Mockito mock, so no Spring context,
 * Testcontainers, or LocalStack is involved. {@link AccountReader} extends
 * {@link org.springframework.batch.item.data.RepositoryItemReader} and configures its sorted, paged
 * scan in {@code afterPropertiesSet()} (the {@code InitializingBean} hook), so each fixture invokes
 * {@code afterPropertiesSet()} and then {@code open(ExecutionContext)} exactly as the Spring Batch
 * lifecycle would before the first {@code read()}.
 *
 * <p>The reader re-platforms the VSAM {@code ACCTFILE} KSDS read ({@code ORGANIZATION INDEXED},
 * {@code ACCESS MODE SEQUENTIAL}, {@code RECORD KEY FD-ACCT-ID}); the contracts proven here mirror
 * that sequential primary-key read:
 * <ul>
 *   <li>rows are fetched through {@code AccountRepository.findAll(Pageable)} sorted <em>ascending by
 *       {@code acctId}</em> with a fixed page size of {@code 100} (the KSDS primary-key order);</li>
 *   <li>every row is emitted exactly once, in order, until the scan is exhausted, after which
 *       {@code read()} returns {@code null} — the COBOL end-of-file ({@code FILE STATUS '10'}) that
 *       stops the step;</li>
 *   <li>an empty account file yields {@code null} on the very first {@code read()}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountReader — ascending acctId paging (size 100), emit-once, end-of-file null")
class AccountReaderTest {

    /** Account master repository (re-platforms the VSAM ACCTDAT KSDS); stubbed to return pages. */
    @Mock
    private AccountRepository accountRepository;

    /**
     * Builds a lightweight {@link Account} carrying only its primary key. The reader is exercised
     * against a mocked repository, so no row is persisted or bean-validated; the emission assertions
     * check object identity rather than field content, so populating {@code acctId} alone keeps the
     * fixture minimal while remaining faithful to the {@code @Id ACCT-ID} of {@code CVACT01Y}.
     *
     * @param id the account id ({@code ACCT-ID}, {@code PIC 9(11)})
     * @return an {@link Account} whose {@code acctId} is {@code id}
     */
    private static Account account(Long id) {
        Account account = new Account();
        account.setAcctId(id);
        return account;
    }

    @Test
    @DisplayName("pages findAll ascending by acctId at size 100 and emits each row exactly once until exhaustion")
    void pagesAscendingByAcctId_pageSize100_emitsEachRowOnce() throws Exception {
        Account a1 = account(1L);
        Account a2 = account(2L);

        // RepositoryItemReader drains the current page, then fetches the next; it stops (read()
        // returns null) only when a fetched page is empty. Page 0 holds the rows, page 1 is empty.
        Page<Account> firstPage = new PageImpl<>(List.of(a1, a2));
        Page<Account> emptyPage = new PageImpl<>(Collections.emptyList());
        // Chained single-argument thenReturn calls (not the thenReturn(T, T...) varargs overload),
        // so no generic varargs array is created — keeping the build warning-free under -Werror.
        when(accountRepository.findAll(any(Pageable.class))).thenReturn(firstPage).thenReturn(emptyPage);

        AccountReader reader = new AccountReader(accountRepository);
        reader.afterPropertiesSet();
        reader.open(new ExecutionContext());

        // Each seeded row is emitted once, in ascending key order; then end-of-file (null).
        assertThat(reader.read()).isSameAs(a1);
        assertThat(reader.read()).isSameAs(a2);
        assertThat(reader.read()).isNull();
        reader.close();

        // The first page request must ask for page 0, size 100, sorted ascending by acctId.
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(accountRepository, atLeastOnce()).findAll(captor.capture());
        Pageable first = captor.getAllValues().get(0);
        assertThat(first.getPageNumber()).isEqualTo(0);
        assertThat(first.getPageSize()).isEqualTo(100);
        Sort.Order acctIdOrder = first.getSort().getOrderFor("acctId");
        assertThat(acctIdOrder).isNotNull();
        assertThat(acctIdOrder.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("an empty account file yields null on the first read (COBOL FILE STATUS '10')")
    void read_returnsNull_whenAccountFileIsEmpty() throws Exception {
        Page<Account> emptyPage = new PageImpl<>(Collections.emptyList());
        when(accountRepository.findAll(any(Pageable.class))).thenReturn(emptyPage);

        AccountReader reader = new AccountReader(accountRepository);
        reader.afterPropertiesSet();
        reader.open(new ExecutionContext());

        assertThat(reader.read()).isNull();
        reader.close();
    }
}
