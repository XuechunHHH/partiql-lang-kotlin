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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.partiql.spi.catalog.Name
import org.partiql.spi.catalog.RoutineBinding

class RoutineIdTest {

    @Test
    fun rejectsEmptyValue() {
        assertThrows<IllegalArgumentException> {
            RoutineId("")
        }
    }

    @Test
    fun preservesOpaqueCaseSensitiveValue() {
        val id = RoutineId("com.example.provider.mixedCaseRoutine")

        assertEquals("com.example.provider.mixedCaseRoutine", id.value)
        assertEquals(id, RoutineId("com.example.provider.mixedCaseRoutine"))
        assertNotEquals(id, RoutineId("com.example.provider.mixedcaseroutine"))
    }

    @Test
    fun routineBindingCopiesOverloads() {
        val overloads = mutableListOf("first")
        val binding = RoutineBinding(RoutineId("provider.routine"), Name.of("routine"), overloads)

        overloads += "second"

        assertEquals(listOf("first"), binding.overloads)
    }
}
