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
package com.carddemo.unit.exception;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.carddemo.exception.BusinessRuleException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure JUnit 5 unit tests for {@link BusinessRuleException}.
 *
 * <p>{@code BusinessRuleException} is the Java realization of the COBOL
 * business-rule rejections: the transaction-posting reject codes emitted by
 * {@code CBTRN02C} (reason {@code 102 'OVERLIMIT TRANSACTION'} and reason
 * {@code 103 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'}) and the bill-pay
 * rule checks in {@code COBIL00C}. The exception carries an optional
 * {@code ruleCode} that {@code GlobalExceptionHandler} maps to HTTP 422
 * (Unprocessable Entity); that HTTP mapping is asserted separately in
 * {@code GlobalExceptionHandlerTest} and is intentionally out of scope here.
 *
 * <p>These tests deliberately use only JUnit 5 and AssertJ — no Mockito, no
 * Spring context, and no database, AWS, or network access — because the
 * subject under test is a plain serializable POJO. The two highest-value
 * assertions are the constructor argument order ({@code ruleCode} first,
 * {@code message} second) and the {@code serialVersionUID} declaration, both
 * of which guard against silent regressions that would otherwise only surface
 * at runtime.
 */
@DisplayName("BusinessRuleException")
class BusinessRuleExceptionTest {

    @Test
    @DisplayName("message-only constructor sets the detail message and leaves cause and ruleCode null")
    void messageOnlyConstructor() {
        BusinessRuleException exception = new BusinessRuleException("OVERLIMIT TRANSACTION");

        assertThat(exception.getMessage()).isEqualTo("OVERLIMIT TRANSACTION");
        assertThat(exception.getCause()).isNull();
        assertThat(exception.getRuleCode()).isNull();
    }

    @Test
    @DisplayName("message-and-cause constructor preserves the message, wraps the cause, and leaves ruleCode null")
    void messageAndCauseConstructor() {
        IllegalStateException cause = new IllegalStateException("c");

        BusinessRuleException exception = new BusinessRuleException("OVERLIMIT TRANSACTION", cause);

        assertThat(exception.getMessage()).isEqualTo("OVERLIMIT TRANSACTION");
        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.getRuleCode()).isNull();
    }

    @Test
    @DisplayName("ruleCode-and-message constructor stores ruleCode first and message second (CBTRN02C reject 102/103 parity)")
    void ruleCodeAndMessageConstructor() {
        // Reason code 102 — over-limit posting rejection (CBTRN02C parity text).
        BusinessRuleException overLimit = new BusinessRuleException("102", "OVERLIMIT TRANSACTION");

        assertThat(overLimit.getRuleCode()).isEqualTo("102");
        assertThat(overLimit.getMessage()).isEqualTo("OVERLIMIT TRANSACTION");
        assertThat(overLimit.getCause()).isNull();

        // Reason code 103 — post-expiration posting rejection (CBTRN02C parity text).
        BusinessRuleException afterExpiration =
                new BusinessRuleException("103", "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");

        assertThat(afterExpiration.getRuleCode()).isEqualTo("103");
        assertThat(afterExpiration.getMessage()).isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
        assertThat(afterExpiration.getCause()).isNull();
    }

    @Test
    @DisplayName("serialVersionUID is a private static final long equal to 1L")
    void serialVersionUidIsPrivateStaticFinalLongOne() throws Exception {
        Field field = BusinessRuleException.class.getDeclaredField("serialVersionUID");
        field.setAccessible(true);

        int modifiers = field.getModifiers();

        assertThat(field.getType()).isEqualTo(long.class);
        assertThat(Modifier.isPrivate(modifiers)).isTrue();
        assertThat(Modifier.isStatic(modifiers)).isTrue();
        assertThat(Modifier.isFinal(modifiers)).isTrue();
        assertThat(field.getLong(null)).isEqualTo(1L);
    }

    @Test
    @DisplayName("is an unchecked RuntimeException subtype")
    void isRuntimeExceptionSubtype() {
        BusinessRuleException exception = new BusinessRuleException("x");

        assertThat(exception).isInstanceOf(RuntimeException.class);
    }
}
