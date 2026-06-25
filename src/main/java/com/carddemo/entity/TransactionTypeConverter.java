/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.entity;

import com.carddemo.enums.TransactionTypeCode;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA {@link AttributeConverter} that converts {@link TransactionTypeCode} to
 * and from the COBOL {@code TRAN-TYPE} code (copybook {@code CVTRA03Y} @
 * {@code 27d6c6f}, {@code PIC X(02)}).
 *
 * <p>Applied explicitly to the {@code Transaction} entity's
 * {@code transactions.tran_type_cd} {@code CHAR(2)} column via
 * {@code @Convert(converter = TransactionTypeConverter.class)}; auto-apply is
 * disabled.
 */
@Converter(autoApply = false)
public class TransactionTypeConverter
        implements AttributeConverter<TransactionTypeCode, String> {

    /**
     * Encodes the enum value to its two-character database code.
     *
     * @param attribute the entity attribute, may be {@code null}
     * @return the two-character {@code TRAN-TYPE} code (for example
     *         {@code "01"}), or {@code null} when {@code attribute} is
     *         {@code null}
     */
    @Override
    public String convertToDatabaseColumn(TransactionTypeCode attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.getCode();
    }

    /**
     * Decodes the persisted two-character code to its enum value.
     *
     * <p>A {@code null} or blank column value resolves to {@code null}. Any
     * other value is trimmed of fixed-width {@code CHAR(2)} padding and
     * delegated to {@link TransactionTypeCode#fromCode(String)}. The null and
     * blank guards run before delegation because {@code fromCode} throws
     * {@link IllegalArgumentException} on {@code null}, blank, or unknown
     * input.
     *
     * @param dbData the raw column value, may be {@code null} or space-padded
     * @return the matching {@link TransactionTypeCode}, or {@code null} when
     *         {@code dbData} is {@code null} or blank
     * @throws IllegalArgumentException if {@code dbData} is non-blank but does
     *                                  not match a known transaction-type code
     */
    @Override
    public TransactionTypeCode convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        String trimmed = dbData.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return TransactionTypeCode.fromCode(trimmed);
    }
}
