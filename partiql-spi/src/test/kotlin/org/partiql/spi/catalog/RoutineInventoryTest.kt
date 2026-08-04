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

package org.partiql.spi.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.partiql.spi.function.AggOverload
import org.partiql.spi.function.FnOverload
import org.partiql.spi.types.PType

class RoutineInventoryTest {

    @Test
    fun copiesBindingsAndInputCollections() {
        val original = functionBinding(Name.of("math", "pow"))
        val functions = mutableListOf(original)
        val inventory = RoutineInventory(functions, emptyList())

        functions.clear()

        val copied = inventory.functions.single()
        assertEquals(original.canonicalName, copied.canonicalName)
        assertEquals(original.overloads, copied.overloads)
        assertNotSame(original, copied)
        assertNotSame(original.canonicalName, copied.canonicalName)
        assertNotSame(original.overloads, copied.overloads)
    }

    @Test
    fun collectionsAndNestedOverloadsAreJavaUnmodifiable() {
        val inventory = RoutineInventory(
            functions = listOf(functionBinding(Name.of("math", "pow"))),
            aggregations = listOf(aggregationBinding(Name.of("stats", "total"))),
        )

        assertThrows<UnsupportedOperationException> {
            (inventory.functions as MutableList<RoutineBinding<FnOverload>>).clear()
        }
        assertThrows<UnsupportedOperationException> {
            (inventory.aggregations as MutableList<RoutineBinding<AggOverload>>).clear()
        }
        assertThrows<UnsupportedOperationException> {
            (inventory.functions.single().overloads as MutableList<FnOverload>).clear()
        }
    }

    @Test
    fun sortsEachRoutineKindByExactCanonicalName() {
        val inventory = RoutineInventory(
            functions = listOf(
                functionBinding(Name.of("z")),
                functionBinding(Name.of("a", "b")),
                functionBinding(Name.of("a")),
                functionBinding(Name.of("A")),
                functionBinding(Name.of("a", "A")),
            ),
            aggregations = listOf(
                aggregationBinding(Name.of("stats", "z")),
                aggregationBinding(Name.of("stats", "a")),
            ),
        )

        assertEquals(
            listOf(Name.of("A"), Name.of("a"), Name.of("a", "A"), Name.of("a", "b"), Name.of("z")),
            inventory.functions.map { it.canonicalName },
        )
        assertEquals(
            listOf(Name.of("stats", "a"), Name.of("stats", "z")),
            inventory.aggregations.map { it.canonicalName },
        )
    }

    @Test
    fun allowsCaseDistinctAndCrossKindNames() {
        val inventory = RoutineInventory(
            functions = listOf(
                functionBinding(Name.of("value")),
                functionBinding(Name.of("VALUE")),
            ),
            aggregations = listOf(aggregationBinding(Name.of("value"))),
        )

        assertEquals(listOf(Name.of("VALUE"), Name.of("value")), inventory.functions.map { it.canonicalName })
        assertEquals(listOf(Name.of("value")), inventory.aggregations.map { it.canonicalName })
    }

    @Test
    fun rejectsExactDuplicateNamesWithinOneKind() {
        val error = assertThrows<IllegalArgumentException> {
            RoutineInventory(
                functions = listOf(
                    functionBinding(Name.of("math", "pow")),
                    functionBinding(Name.of("math", "pow")),
                ),
                aggregations = emptyList(),
            )
        }

        assertEquals(
            "Routine inventory contains duplicate scalar binding: \"math\".\"pow\"",
            error.message,
        )
    }

    private fun functionBinding(name: Name): RoutineBinding<FnOverload> =
        RoutineBinding(
            canonicalName = name,
            overloads = listOf(
                FnOverload.Builder(name.getName())
                    .addParameter(PType.integer())
                    .returns(PType.integer())
                    .build(),
            ),
        )

    private fun aggregationBinding(name: Name): RoutineBinding<AggOverload> =
        RoutineBinding(
            canonicalName = name,
            overloads = listOf(
                AggOverload.Builder(name.getName())
                    .addParameter(PType.integer())
                    .returns(PType.integer())
                    .build(),
            ),
        )
}
