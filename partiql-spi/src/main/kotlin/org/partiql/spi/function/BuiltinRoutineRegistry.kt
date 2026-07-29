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
import java.util.Locale

/**
 * Validated identities and exact-name lookup for routines in the system catalog.
 */
internal class BuiltinRoutineRegistry(
    functions: Map<String, Collection<FnOverload>>,
    aggregations: Map<String, Collection<AggOverload>>,
    private val hiddenRoutineNames: Map<String, String>,
) {

    private val functionBindings: Collection<RoutineBinding<FnOverload>>
    private val aggregationBindings: Collection<RoutineBinding<AggOverload>>

    init {
        val routineNames = functions.keys + aggregations.keys
        validateHiddenMappings(routineNames)

        val ids = routineNames.associateWith(::routineId)
        check(ids.values.toSet().size == ids.size) { "Built-in routine IDs must be unique" }

        functionBindings = bindings(functions) { it.signature.name }
        aggregationBindings = bindings(aggregations) { it.signature.name }
    }

    internal fun resolveFunctions(identifier: Identifier): Collection<RoutineBinding<FnOverload>> =
        resolve(identifier, functionBindings)

    internal fun resolveAggregations(identifier: Identifier): Collection<RoutineBinding<AggOverload>> =
        resolve(identifier, aggregationBindings)

    private fun validateHiddenMappings(routineNames: Set<String>) {
        val hiddenNames = routineNames.filterTo(mutableSetOf()) { it.startsWith(HIDDEN_PREFIX) }
        check(hiddenRoutineNames.keys == hiddenNames) {
            val missing = hiddenNames - hiddenRoutineNames.keys
            val unused = hiddenRoutineNames.keys - hiddenNames
            "Hidden built-in routine mapping mismatch; missing=$missing, unused=$unused"
        }
        check(hiddenRoutineNames.values.all { it.isNotEmpty() }) {
            "Hidden built-in routine logical names cannot be empty"
        }
        check(hiddenRoutineNames.values.toSet().size == hiddenRoutineNames.size) {
            "Hidden built-in routine logical names must be unique"
        }
    }

    private fun routineId(name: String): RoutineId {
        val value = if (name.startsWith(HIDDEN_PREFIX)) {
            "$INTERNAL_ID_PREFIX.${hiddenRoutineNames.getValue(name)}"
        } else {
            "$VISIBLE_ID_PREFIX.$name"
        }
        check(value == value.lowercase(Locale.ROOT)) { "Built-in routine ID must be lowercase: $value" }
        check(!value.contains(HIDDEN_PREFIX)) { "Built-in routine ID must not contain private-use characters" }
        return RoutineId(value)
    }

    private fun <T> bindings(
        routines: Map<String, Collection<T>>,
        overloadName: (T) -> String,
    ): Collection<RoutineBinding<T>> = routines.map { (name, overloads) ->
        check(overloads.isNotEmpty()) { "Built-in routine must have at least one overload: $name" }
        check(overloads.all { overloadName(it) == name }) {
            "Built-in routine overload names must match their binding: $name"
        }
        RoutineBinding(
            providerId = routineId(name),
            canonicalName = Name.of(name),
            overloads = overloads,
        )
    }

    private fun <T> resolve(
        identifier: Identifier,
        bindings: Collection<RoutineBinding<T>>,
    ): Collection<RoutineBinding<T>> {
        if (identifier.hasQualifier()) {
            return emptyList()
        }
        val name = identifier.getIdentifier()
        return bindings.filter { name.matches(it.canonicalName.getName()) }
    }

    private companion object {
        private const val HIDDEN_PREFIX = "\uFDEF"
        private const val VISIBLE_ID_PREFIX = "org.partiql.builtin"
        private const val INTERNAL_ID_PREFIX = "$VISIBLE_ID_PREFIX.internal"
    }
}
