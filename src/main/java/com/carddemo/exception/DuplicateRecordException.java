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
package com.carddemo.exception;

public class DuplicateRecordException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String entityType;

    private final String key;

    public DuplicateRecordException(String message) {
        super(message);
        this.entityType = null;
        this.key = null;
    }

    public DuplicateRecordException(String message, Throwable cause) {
        super(message, cause);
        this.entityType = null;
        this.key = null;
    }

    public DuplicateRecordException(String entityType, Object key) {
        super(entityType + " already exists: " + key);
        this.entityType = entityType;
        this.key = String.valueOf(key);
    }

    public String getEntityType() {
        return entityType;
    }

    public String getKey() {
        return key;
    }
}
