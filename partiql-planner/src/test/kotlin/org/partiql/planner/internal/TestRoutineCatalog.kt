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

package org.partiql.planner.internal

import org.partiql.spi.catalog.Identifier
import org.partiql.spi.catalog.Name
import org.partiql.spi.catalog.RoutineBinding
import org.partiql.spi.catalog.RoutineCatalog
import org.partiql.spi.catalog.RoutineInventory
import org.partiql.spi.catalog.Session
import org.partiql.spi.catalog.Table
import org.partiql.spi.function.AggOverload
import org.partiql.spi.function.FnOverload

internal class TestRoutineCatalog private constructor(
    private val name: String,
    private val functions: List<RoutineBinding<FnOverload>>,
    private val aggregations: List<RoutineBinding<AggOverload>>,
    private val tables: List<Table>,
) : RoutineCatalog {

    private val inventory = RoutineInventory(functions, aggregations)

    internal var functionResolutionCount: Int = 0
        private set

    internal var aggregationResolutionCount: Int = 0
        private set

    override fun getName(): String = name

    override fun getTable(session: Session, name: Name): Table? =
        tables.singleOrNull { it.getName() == name }

    override fun resolveTable(session: Session, identifier: Identifier): Name? =
        tables.map { it.getName() }.singleOrNull { it.matches(identifier) }

    override fun resolveFunctions(
        session: Session,
        identifier: Identifier,
    ): Collection<RoutineBinding<FnOverload>> {
        functionResolutionCount++
        return functions.filter { it.canonicalName.matches(identifier) }
    }

    override fun resolveAggregations(
        session: Session,
        identifier: Identifier,
    ): Collection<RoutineBinding<AggOverload>> {
        aggregationResolutionCount++
        return aggregations.filter { it.canonicalName.matches(identifier) }
    }

    override fun getRoutineInventory(): RoutineInventory = inventory

    private fun Name.matches(identifier: Identifier): Boolean {
        val names = toList()
        val parts = identifier.getParts()
        return names.size == parts.size && parts.zip(names).all { (part, name) -> part.matches(name) }
    }

    internal companion object {
        internal fun builder(name: String): Builder = Builder(name)
    }

    internal class Builder(private val name: String) {
        private val functions = mutableListOf<RoutineBinding<FnOverload>>()
        private val aggregations = mutableListOf<RoutineBinding<AggOverload>>()
        private val tables = mutableListOf<Table>()

        internal fun function(
            canonicalName: Name,
            vararg overloads: FnOverload,
        ): Builder = apply {
            functions += RoutineBinding(canonicalName, overloads.toList())
        }

        internal fun aggregation(
            canonicalName: Name,
            vararg overloads: AggOverload,
        ): Builder = apply {
            aggregations += RoutineBinding(canonicalName, overloads.toList())
        }

        internal fun table(table: Table): Builder = apply {
            tables += table
        }

        internal fun build(): TestRoutineCatalog = TestRoutineCatalog(name, functions, aggregations, tables)
    }
}
