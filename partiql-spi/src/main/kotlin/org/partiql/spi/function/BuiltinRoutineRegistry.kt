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

import org.partiql.spi.catalog.Identifier
import org.partiql.spi.catalog.Name
import org.partiql.spi.catalog.RoutineBinding
import org.partiql.spi.catalog.RoutineInventory
import java.util.Collections

/**
 * Immutable exact-name bindings for routines owned by the system catalog.
 */
internal class BuiltinRoutineRegistry(
    functions: Map<String, Collection<FnOverload>>,
    aggregations: Map<String, Collection<AggOverload>>,
) {
    val inventory: RoutineInventory = RoutineInventory(
        functions = bindings(functions) { it.signature.name },
        aggregations = bindings(aggregations) { it.signature.name },
    )

    fun resolveFunctions(identifier: Identifier): Collection<RoutineBinding<FnOverload>> =
        resolve(identifier, inventory.functions)

    fun resolveAggregations(identifier: Identifier): Collection<RoutineBinding<AggOverload>> =
        resolve(identifier, inventory.aggregations)

    private fun <T> bindings(
        routines: Map<String, Collection<T>>,
        overloadName: (T) -> String,
    ): Collection<RoutineBinding<T>> = routines.map { (name, overloads) ->
        check(overloads.isNotEmpty()) { "Built-in routine must have at least one overload: $name" }
        check(overloads.all { overloadName(it) == name }) {
            "Built-in routine overload names must match their binding: $name"
        }
        RoutineBinding(
            canonicalName = Name.of(name),
            overloads = overloads,
        )
    }

    private fun <T> resolve(
        identifier: Identifier,
        bindings: List<RoutineBinding<T>>,
    ): Collection<RoutineBinding<T>> {
        if (identifier.hasQualifier()) {
            return emptyList()
        }
        val name = identifier.getIdentifier()
        val matches = bindings.filter { name.matches(it.canonicalName.getName()) }
        return Collections.unmodifiableList(matches)
    }
}
