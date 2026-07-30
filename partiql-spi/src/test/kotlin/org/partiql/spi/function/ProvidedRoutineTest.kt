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
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.partiql.spi.catalog.Name

class ProvidedRoutineTest {

    @Test
    fun copiesSourceNameAndOverloads() {
        val sourceName = Name.of("example", "functions", "tokenize")
        val overloads = mutableListOf("first")
        val routine = ProvidedRoutine(RoutineId("provider.tokenize"), sourceName, overloads)

        overloads += "second"

        assertEquals(sourceName, routine.sourceName)
        assertNotSame(sourceName, routine.sourceName)
        assertEquals(listOf("first"), routine.overloads)
    }

    @Test
    fun overloadsAreJavaUnmodifiable() {
        val routine = ProvidedRoutine(
            RoutineId("provider.tokenize"),
            Name.of("example", "functions", "tokenize"),
            listOf("first"),
        )

        assertThrows<UnsupportedOperationException> {
            (routine.overloads as MutableList<String>).add("second")
        }
    }
}
