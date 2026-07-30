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

import org.partiql.planner.internal.ir.Ref
import org.partiql.spi.catalog.Catalog
import org.partiql.spi.catalog.CatalogNameAmbiguousException
import org.partiql.spi.catalog.Identifier
import org.partiql.spi.catalog.Name
import org.partiql.spi.catalog.RoutineBinding
import org.partiql.spi.catalog.RoutineCatalog
import org.partiql.spi.catalog.Session
import org.partiql.spi.function.AggOverload
import org.partiql.spi.function.FnOverload
import java.util.Locale

internal data class RoutineCallShape(
    val scalarArity: Int,
    val aggregateArity: Int,
) {
    init {
        require(scalarArity >= 0) { "Scalar arity cannot be negative" }
        require(aggregateArity >= 0) { "Aggregate arity cannot be negative" }
    }

    internal companion object {
        internal fun from(identifier: Identifier, arity: Int): RoutineCallShape {
            val aggregateArity = when {
                arity == 0 && identifier.getIdentifier().matches("count") -> 1
                else -> arity
            }
            return RoutineCallShape(arity, aggregateArity)
        }
    }
}

internal data class RoutineMatch<T>(
    val catalog: String,
    val canonicalName: Name,
    val overloads: List<T>,
    val routine: Ref.Routine?,
)

internal sealed class RoutineSelection {
    data object NotFound : RoutineSelection()

    data class Scalar(val match: RoutineMatch<FnOverload>) : RoutineSelection()

    data class Aggregate(val match: RoutineMatch<AggOverload>) : RoutineSelection()

    data class Ambiguous(val candidates: List<String>) : RoutineSelection()
}

/**
 * Selects one routine binding and location before overload type resolution.
 */
internal class RoutineResolver(private val session: Session) {

    private val catalogs = session.getCatalogs()
    private val cache = mutableMapOf<CacheKey, RoutineSelection>()

    internal fun select(identifier: Identifier, shape: RoutineCallShape): RoutineSelection {
        val key = CacheKey(
            identifier.getParts().map { IdentifierPart(it.getText(), it.isRegular()) },
            shape,
        )
        return cache.getOrPut(key) {
            when (identifier.hasQualifier()) {
                true -> selectQualified(identifier, shape)
                false -> selectUnqualified(identifier, shape)
            }
        }
    }

    private fun selectQualified(identifier: Identifier, shape: RoutineCallShape): RoutineSelection {
        val parts = identifier.getParts()
        val catalogPart = parts.first()
        val catalog = try {
            catalogs.getCatalog(catalogPart.getText(), ignoreCase = catalogPart.isRegular())
        } catch (e: CatalogNameAmbiguousException) {
            return RoutineSelection.Ambiguous(e.catalogNames)
        } ?: return RoutineSelection.NotFound

        if (catalog !is RoutineCatalog) {
            return RoutineSelection.NotFound
        }
        return selectAt(catalog, Identifier.of(parts.drop(1)), shape)
    }

    private fun selectUnqualified(identifier: Identifier, shape: RoutineCallShape): RoutineSelection {
        val routinePart = identifier.getIdentifier()
        val visited = mutableSetOf<List<String>>()
        for (pathEntry in session.getPath()) {
            val levels = pathEntry.getLevels().toList()
            if (levels.isEmpty() || !visited.add(levels)) {
                continue
            }
            val catalog = catalogs.getCatalog(levels.first()) ?: continue
            val namespace = levels.drop(1).map { Identifier.Simple.delimited(it) }
            val catalogLocalIdentifier = Identifier.of(namespace + routinePart)
            val selection = when (catalog) {
                is RoutineCatalog -> selectAt(catalog, catalogLocalIdentifier, shape)
                else -> selectLegacy(catalog, routinePart.getText(), shape)
            }
            if (selection !is RoutineSelection.NotFound) {
                return selection
            }
        }
        return RoutineSelection.NotFound
    }

    private fun selectAt(
        catalog: RoutineCatalog,
        identifier: Identifier,
        shape: RoutineCallShape,
    ): RoutineSelection {
        val scalars = catalog.resolveFunctions(session, identifier)
            .matching(shape.scalarArity) { it.signature.arity }
            .map { (binding, overloads) -> match(catalog, binding, overloads) }
        val aggregates = catalog.resolveAggregations(session, identifier)
            .matching(shape.aggregateArity) { it.signature.arity }
            .map { (binding, overloads) -> match(catalog, binding, overloads) }

        return when {
            scalars.isEmpty() && aggregates.isEmpty() -> RoutineSelection.NotFound
            scalars.size == 1 && aggregates.isEmpty() -> RoutineSelection.Scalar(scalars.single())
            scalars.isEmpty() && aggregates.size == 1 -> RoutineSelection.Aggregate(aggregates.single())
            else -> RoutineSelection.Ambiguous(
                scalars.map { describe("scalar", it) } + aggregates.map { describe("aggregate", it) }
            )
        }
    }

    private fun selectLegacy(
        catalog: Catalog,
        queryName: String,
        shape: RoutineCallShape,
    ): RoutineSelection {
        val name = queryName.lowercase(Locale.ROOT)
        val scalars = catalog.getFunctions(session, name).filter { it.signature.arity == shape.scalarArity }
        val aggregates = catalog.getAggregations(session, name).filter { it.signature.arity == shape.aggregateArity }
        val canonicalName = Name.of(name)
        return when {
            scalars.isEmpty() && aggregates.isEmpty() -> RoutineSelection.NotFound
            scalars.isNotEmpty() && aggregates.isEmpty() -> RoutineSelection.Scalar(
                RoutineMatch(catalog.getName(), canonicalName, scalars, null)
            )
            scalars.isEmpty() && aggregates.isNotEmpty() -> RoutineSelection.Aggregate(
                RoutineMatch(catalog.getName(), canonicalName, aggregates, null)
            )
            else -> RoutineSelection.Ambiguous(
                listOf(
                    "scalar ${catalog.getName()}.$canonicalName",
                    "aggregate ${catalog.getName()}.$canonicalName",
                )
            )
        }
    }

    private fun <T> match(
        catalog: Catalog,
        binding: RoutineBinding<T>,
        overloads: List<T>,
    ): RoutineMatch<T> {
        val routine = Ref.Routine(binding.providerId, catalog.getName(), binding.canonicalName)
        return RoutineMatch(catalog.getName(), binding.canonicalName, overloads, routine)
    }

    private fun describe(kind: String, match: RoutineMatch<*>): String =
        "$kind ${match.catalog}.${match.canonicalName}"

    private fun <T> Collection<RoutineBinding<T>>.matching(
        arity: Int,
        getArity: (T) -> Int,
    ): List<Pair<RoutineBinding<T>, List<T>>> = mapNotNull { binding ->
        val overloads = binding.overloads.filter { getArity(it) == arity }
        when (overloads.isEmpty()) {
            true -> null
            false -> binding to overloads
        }
    }

    private data class IdentifierPart(val text: String, val regular: Boolean)

    private data class CacheKey(
        val identifier: List<IdentifierPart>,
        val shape: RoutineCallShape,
    )
}
