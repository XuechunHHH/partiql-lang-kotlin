/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.partiql.spi.function

/**
 * An opaque identity assigned to a routine by its provider.
 *
 * The value is compared exactly and case-sensitively. PartiQL does not parse or normalize it.
 */
public class RoutineId(public val value: String) {

    init {
        require(value.isNotEmpty()) { "Routine ID cannot be empty" }
    }

    override fun equals(other: Any?): Boolean {
        return this === other || (other is RoutineId && value == other.value)
    }

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}
