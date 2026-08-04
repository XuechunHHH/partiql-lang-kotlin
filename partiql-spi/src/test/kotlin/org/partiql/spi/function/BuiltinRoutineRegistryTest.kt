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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BuiltinRoutineRegistryTest {

    @Test
    fun rejectsEmptyOverloadCollection() {
        val error = assertThrows<IllegalStateException> {
            BuiltinRoutineRegistry(
                functions = mapOf("empty" to emptyList()),
                aggregations = emptyMap(),
            )
        }

        assertEquals("Built-in routine must have at least one overload: empty", error.message)
    }

    @Test
    fun rejectsSignatureNameMismatch() {
        val overload = FnOverload.Builder("actual").build()

        val error = assertThrows<IllegalStateException> {
            BuiltinRoutineRegistry(
                functions = mapOf("registered" to listOf(overload)),
                aggregations = emptyMap(),
            )
        }

        assertEquals("Built-in routine overload names must match their binding: registered", error.message)
    }
}
