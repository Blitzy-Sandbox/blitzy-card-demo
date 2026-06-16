package com.carddemo.batch.readers;

import com.carddemo.model.entity.Customer;
import com.carddemo.repository.CustomerRepository;
import java.util.Map;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Spring Batch reader for the customer master, replacing COBOL batch reader CBCUS01C
 * (source commit 27d6c6f). Streams {@link Customer} rows in ascending CUST-ID order via a
 * sorted, paged repository scan, reproducing the VSAM KSDS sequential primary-key read.
 *
 * <p>Declared as a step-scoped {@link Component} and wired into a Spring Batch step by
 * {@code com.carddemo.batch.jobs}; this class defines no job or step. The inherited
 * {@link RepositoryItemReader} (its repository, ascending {@code custId} sort, and page
 * size) is configured in {@link #afterPropertiesSet()} so it is fully bound before the step
 * opens the reader.</p>
 */
@Component
@StepScope
public class CustomerReader extends RepositoryItemReader<Customer> {

    private final CustomerRepository customerRepository;

    public CustomerReader(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        setRepository(customerRepository);
        setMethodName("findAll");
        setSort(Map.of("custId", Sort.Direction.ASC));
        setPageSize(100);
        setName("customerReader");
        super.afterPropertiesSet();
    }
}
