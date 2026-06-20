package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.readers.CustomerReader;
import com.carddemo.model.entity.Customer;
import com.carddemo.repository.CustomerRepository;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Unit tests for {@link CustomerReader} — the Spring Batch {@link org.springframework.batch.item.data.RepositoryItemReader}
 * that replaces the sequential {@code CUSTFILE} read of COBOL {@code CBCUS01C} over the
 * {@code CVCUS01Y} customer record layout (REFERENCE-ONLY, COBOL not copied; source commit
 * {@code 27d6c6f}).
 *
 * <p>These are pure-JVM tests: {@link CustomerRepository} is a Mockito mock, so no Spring context,
 * Testcontainers, or LocalStack is involved. {@code CustomerReader} is an {@code InitializingBean},
 * so each fixture calls {@code afterPropertiesSet()} — which applies the repository, the
 * {@code findAll} method, the ascending-{@code custId} sort, the page size of 100, and the stream
 * name — before {@code open(ExecutionContext)}, exactly as the Spring step lifecycle would.
 *
 * <p>The verified contracts mirror the VSAM KSDS primary-key scan of {@code CBCUS01C}
 * ({@code ORGANIZATION INDEXED}, {@code ACCESS MODE SEQUENTIAL}, {@code RECORD KEY FD-CUST-ID}):
 * <ul>
 *   <li>rows are paged through {@code findAll(Pageable)} ascending by {@code custId} at page
 *       size 100, and each row is emitted exactly once until exhaustion yields {@code null} (the
 *       COBOL FILE STATUS {@code '10'} end-of-file that stops the step);</li>
 *   <li>the reader is configured with the Spring Batch stream name {@code customerReader}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerReader — ascending custId paging, page size 100, emit-each-row-once (CBCUS01C)")
class CustomerReaderTest {

    /** Customer master repository (re-platforms the VSAM CUSTDAT KSDS); supplies {@code findAll(Pageable)}. */
    @Mock
    private CustomerRepository customerRepository;

    /**
     * Builds a {@link Customer} carrying the given primary key plus short, distinct name fields.
     * Only {@code custId} is material to these tests (emission is asserted by object identity);
     * the name fields simply make each row a recognizable, fully formed instance.
     *
     * @param id the customer id ({@code CUST-ID}) to assign
     * @return a new {@link Customer} with {@code custId} and representative name fields set
     */
    private static Customer customer(Long id) {
        Customer c = new Customer();
        c.setCustId(id);
        c.setCustFirstName("F" + id);
        c.setCustLastName("L" + id);
        return c;
    }

    @Test
    @DisplayName("pages findAll ascending by custId at page size 100 and emits each row once until exhaustion")
    void pagesAscendingByCustId_pageSize100_emitsEachRowOnce() throws Exception {
        Customer c1 = customer(1L);
        Customer c2 = customer(2L);

        // First fetch returns the populated page; the second fetch is empty so read() returns null
        // after the seeded rows. Chained single-argument stubs avoid a generic-varargs return.
        PageImpl<Customer> firstPage = new PageImpl<>(List.of(c1, c2));
        PageImpl<Customer> emptyPage = new PageImpl<>(Collections.emptyList());
        when(customerRepository.findAll(any(Pageable.class)))
                .thenReturn(firstPage)
                .thenReturn(emptyPage);

        CustomerReader reader = new CustomerReader(customerRepository);
        reader.afterPropertiesSet();
        reader.open(new ExecutionContext());

        assertThat(reader.read()).isSameAs(c1);
        assertThat(reader.read()).isSameAs(c2);
        assertThat(reader.read()).isNull();
        reader.close();

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(customerRepository, atLeastOnce()).findAll(captor.capture());

        Pageable first = captor.getAllValues().get(0);
        assertThat(first.getPageNumber()).isEqualTo(0);
        assertThat(first.getPageSize()).isEqualTo(100);
        assertThat(first.getSort().getOrderFor("custId")).isNotNull();
        assertThat(first.getSort().getOrderFor("custId").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("applies the configured Spring Batch stream name 'customerReader'")
    void appliesConfiguredStreamName_customerReader() throws Exception {
        CustomerReader reader = new CustomerReader(customerRepository);
        reader.afterPropertiesSet();

        assertThat(reader.getName()).isEqualTo("customerReader");
    }
}
