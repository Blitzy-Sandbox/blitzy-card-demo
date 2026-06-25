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

import com.carddemo.exception.RecordNotFoundException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure JUnit 5 unit tests for {@link RecordNotFoundException}.
 *
 * <p>{@code RecordNotFoundException} is the typed exception that replaces the
 * legacy COBOL VSAM {@code FILE STATUS '23'} / CICS {@code DFHRESP(NOTFND)}
 * "record not found" condition exercised by the parity references
 * {@code COACTVWC}, {@code COTRN01C}, and {@code CBTRN02C}; the application's
 * {@code GlobalExceptionHandler} maps it to HTTP&nbsp;404. These tests verify
 * <em>only</em> the exception POJO's constructors, getters, and the
 * {@code serialVersionUID} declaration — the HTTP-status translation is covered
 * separately by the global exception-handler tests.
 *
 * <p>The suite is deliberately self-contained: it uses no Mockito, no Spring
 * context, and no external resources, so every assertion runs in-memory in
 * milliseconds.
 */
class RecordNotFoundExceptionTest {

    @Test
    @DisplayName("Message-only constructor sets the detail message and leaves entity/key context null")
    void messageOnlyConstructorSetsMessageAndNullContext() {
        var ex = new RecordNotFoundException("not here");

        assertThat(ex.getMessage()).isEqualTo("not here");
        assertThat(ex.getCause()).isNull();
        assertThat(ex.getEntityType()).isNull();
        assertThat(ex.getKey()).isNull();
    }

    @Test
    @DisplayName("Message-and-cause constructor preserves the message and the exact cause instance")
    void messageAndCauseConstructorSetsBoth() {
        Throwable cause = new IllegalStateException("io");

        var ex = new RecordNotFoundException("wrapped", cause);

        assertThat(ex.getMessage()).isEqualTo("wrapped");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getEntityType()).isNull();
        assertThat(ex.getKey()).isNull();
    }

    @Test
    @DisplayName("Entity-type and key constructor builds a deterministic 'not found' message and exposes context")
    void entityTypeAndKeyConstructorBuildsDeterministicMessage() {
        var ex = new RecordNotFoundException("Account", "00000000011");

        assertThat(ex.getMessage()).isEqualTo("Account not found: 00000000011");
        assertThat(ex.getEntityType()).isEqualTo("Account");
        assertThat(ex.getKey()).isEqualTo("00000000011");
    }

    @Test
    @DisplayName("Entity-type and key constructor stringifies a non-String key via String.valueOf(Object)")
    void entityTypeAndKeyConstructorStringifiesNonStringKey() {
        var ex = new RecordNotFoundException("Customer", 11L);

        assertThat(ex.getKey()).isEqualTo("11");
        assertThat(ex.getMessage()).isEqualTo("Customer not found: 11");
    }

    @Test
    @DisplayName("RecordNotFoundException is an unchecked RuntimeException subtype")
    void isRuntimeExceptionSubtype() {
        assertThat(new RecordNotFoundException("x")).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("serialVersionUID is declared as a private static final long with value 1L")
    void serialVersionUidIsDeclaredAsPrivateStaticFinalLongOne() throws Exception {
        Field f = RecordNotFoundException.class.getDeclaredField("serialVersionUID");
        f.setAccessible(true);

        assertThat(f.getType()).isEqualTo(long.class);
        assertThat(Modifier.isPrivate(f.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(f.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(f.getModifiers())).isTrue();
        assertThat(f.getLong(null)).isEqualTo(1L);
    }
}
