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
import org.junit.jupiter.api.Test
import org.partiql.spi.function.AggOverload
import org.partiql.spi.function.FnOverload
import org.partiql.spi.types.PType

class RoutinePathsTest {

    @Test
    fun derivesUniqueDirectlyPopulatedNamespacesInLexicalOrder() {
        val catalog = routineCatalog(
            name = "example",
            functions = listOf(
                functionBinding(Name.of("contains")),
                functionBinding(Name.of("datetime", "utcnow")),
                functionBinding(Name.of("math", "extend", "pow")),
            ),
            aggregations = listOf(
                aggregationBinding(Name.of("datetime", "total")),
            ),
        )

        val path = RoutinePaths.fromInventory(catalog)

        assertEquals(
            listOf(
                Namespace.of("example"),
                Namespace.of("example", "datetime"),
                Namespace.of("example", "math", "extend"),
                Namespace.of("\$system"),
            ),
            path.toList(),
        )
    }

    @Test
    fun excludesCatalogRootWhenInventoryHasNoRootRoutine() {
        val catalog = routineCatalog(
            name = "example",
            functions = listOf(
                functionBinding(Name.of("lower", "value")),
                functionBinding(Name.of("Upper", "value")),
            ),
        )
        val customSystem = Catalog.builder().name("builtins").build()

        val path = RoutinePaths.fromInventory(catalog, customSystem)

        assertEquals(
            listOf(
                Namespace.of("example", "Upper"),
                Namespace.of("example", "lower"),
                Namespace.of("builtins"),
            ),
            path.toList(),
        )
    }

    @Test
    fun emptyInventoryProducesOnlyConfiguredSystemRoot() {
        val catalog = routineCatalog(name = "example")

        assertEquals(
            listOf(Namespace.of("\$system")),
            RoutinePaths.fromInventory(catalog).toList(),
        )
    }

    @Test
    fun systemRootIsAppendedOnlyOnce() {
        val catalog = routineCatalog(
            name = "\$system",
            functions = listOf(
                functionBinding(Name.of("lower")),
                functionBinding(Name.of("nested", "value")),
            ),
        )

        assertEquals(
            listOf(
                Namespace.of("\$system", "nested"),
                Namespace.of("\$system"),
            ),
            RoutinePaths.fromInventory(catalog).toList(),
        )
    }

    private fun routineCatalog(
        name: String,
        functions: Collection<RoutineBinding<FnOverload>> = emptyList(),
        aggregations: Collection<RoutineBinding<AggOverload>> = emptyList(),
    ): RoutineCatalog =
        object : RoutineCatalog {
            private val inventory = RoutineInventory(functions, aggregations)

            override fun getName(): String = name

            override fun resolveFunctions(
                session: Session,
                identifier: Identifier,
            ): Collection<RoutineBinding<FnOverload>> = emptyList()

            override fun resolveAggregations(
                session: Session,
                identifier: Identifier,
            ): Collection<RoutineBinding<AggOverload>> = emptyList()

            override fun getRoutineInventory(): RoutineInventory = inventory
        }

    private fun functionBinding(name: Name): RoutineBinding<FnOverload> =
        RoutineBinding(
            canonicalName = name,
            overloads = listOf(
                FnOverload.Builder(name.getName())
                    .returns(PType.dynamic())
                    .build(),
            ),
        )

    private fun aggregationBinding(name: Name): RoutineBinding<AggOverload> =
        RoutineBinding(
            canonicalName = name,
            overloads = listOf(
                AggOverload.Builder(name.getName())
                    .returns(PType.dynamic())
                    .build(),
            ),
        )
}
