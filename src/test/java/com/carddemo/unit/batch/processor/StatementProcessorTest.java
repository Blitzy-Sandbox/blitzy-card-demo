/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.unit.batch.processor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.carddemo.batch.processor.StatementProcessor;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CustomerRepository;
import com.carddemo.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StatementProcessor} verifying the CP4 stored-XSS fix:
 * dynamic customer and transaction values are HTML-escaped before being placed
 * into the generated HTML statement, while clean fixture data remains
 * byte-equivalent (no over-escaping).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatementProcessor — HTML statement escapes dynamic values")
class StatementProcessorTest {

    private static final Long CUST_ID = 1L;
    private static final Long ACCT_ID = 11L;
    private static final String CARD_NUM = "4111111111111111";

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private StatementProcessor processor;

    private Account account;

    @BeforeEach
    void setUp() {
        account = new Account();
        account.setAcctId(ACCT_ID);
        account.setCurrBal(new BigDecimal("100.00"));
    }

    private Customer customer() {
        Customer customer = new Customer();
        customer.setFicoCreditScore(700);
        return customer;
    }

    private Transaction transaction(String tranId, String description) {
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        transaction.setCardNum(CARD_NUM);
        transaction.setTranDesc(description);
        transaction.setTranAmt(new BigDecimal("10.00"));
        transaction.setTranCatCd(1);
        transaction.setMerchantId(123456789L);
        return transaction;
    }

    private void wire(Customer customer, Transaction transaction) {
        when(customerRepository.findById(CUST_ID)).thenReturn(Optional.of(customer));
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.findByCardNumOrderByTranIdAsc(CARD_NUM))
                .thenReturn(List.of(transaction));
    }

    @Test
    @DisplayName("malicious customer and transaction values are HTML-escaped, no raw markup survives")
    void escapesMaliciousValues() {
        Customer customer = customer();
        // No spaces in the name payload: buildName cuts at the first space.
        customer.setFirstName("<script>alert('xss')</script>");
        customer.setAddrLine1("<img&\"bad\">");
        Transaction transaction = transaction("<x>", "<b>Bad</b> & \"q\" 'p'");
        wire(customer, transaction);

        String html = processor.process(new CardXref(CARD_NUM, CUST_ID, ACCT_ID)).html();

        // Escaped entities are present...
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("&lt;x&gt;");
        assertThat(html).contains("&lt;b&gt;Bad&lt;/b&gt;");
        assertThat(html).contains("&amp;");
        assertThat(html).contains("&quot;");
        assertThat(html).contains("&#39;");
        // ...and no raw, injectable markup from the dynamic values remains.
        assertThat(html).doesNotContain("<script>");
        assertThat(html).doesNotContain("</script>");
        assertThat(html).doesNotContain("<img");
        assertThat(html).doesNotContain("<b>Bad");
    }

    @Test
    @DisplayName("clean data is not over-escaped (byte-equivalent statement output)")
    void cleanDataIsNotEscaped() {
        Customer customer = customer();
        customer.setFirstName("JOHN");
        customer.setLastName("DOE");
        customer.setAddrLine1("123 MAIN ST");
        Transaction transaction = transaction("0000000000000001", "PURCHASE AT POS");
        wire(customer, transaction);

        String html = processor.process(new CardXref(CARD_NUM, CUST_ID, ACCT_ID)).html();

        // Clean dynamic values appear verbatim.
        assertThat(html).contains("PURCHASE AT POS");
        assertThat(html).contains("0000000000000001");
        assertThat(html).contains("JOHN");
        // No entity encoding is introduced when there are no metacharacters
        // (the static markup and clean data contain none of these entities).
        assertThat(html).doesNotContain("&amp;");
        assertThat(html).doesNotContain("&lt;");
        assertThat(html).doesNotContain("&gt;");
        assertThat(html).doesNotContain("&quot;");
        assertThat(html).doesNotContain("&#39;");
    }
}
