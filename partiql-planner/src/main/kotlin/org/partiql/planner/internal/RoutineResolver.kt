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

import org.partiql.plan.RoutineRef
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

internal sealed interface RoutineClassification {
    data object NotFound : RoutineClassification

    data object Scalar : RoutineClassification

    data object Aggregate : RoutineClassification

    data class Ambiguous(
        val candidates: List<String>,
        val aggregateOnly: Boolean,
    ) : RoutineClassification
}

internal data class RoutineMatch<T>(
    val catalog: String,
    val canonicalName: Name,
    val overloads: List<T>,
    val routine: RoutineRef?,
) {
    internal fun describe(kind: String): String = "$kind $catalog.$canonicalName"
}

internal data class RoutineLocation(
    val scalars: List<RoutineMatch<FnOverload>>,
    val aggregates: List<RoutineMatch<AggOverload>>,
)

internal data class RoutineSearch(
    val locations: List<RoutineLocation>,
    val classification: RoutineClassification,
)

/**
 * Caches exact catalog lookups for one plan and classifies scalar and aggregate candidates from the same snapshot.
 */
internal class RoutineResolver(private val session: Session) {

    private val catalogs = session.getCatalogs()
    private val cache = mutableMapOf<CacheKey, RoutineSearch>()

    internal fun search(identifier: Identifier, shape: RoutineCallShape): RoutineSearch {
        val key = CacheKey(
            identifier.getParts().map { IdentifierPart(it.getText(), it.isRegular()) },
            shape,
        )
        return cache.getOrPut(key) {
            when (identifier.hasQualifier()) {
                true -> searchQualified(identifier, shape)
                false -> searchUnqualified(identifier, shape)
            }
        }
    }

    private fun searchQualified(identifier: Identifier, shape: RoutineCallShape): RoutineSearch {
        val parts = identifier.getParts()
        val catalogPart = parts.first()
        val catalog = try {
            catalogs.getCatalog(catalogPart.getText(), ignoreCase = catalogPart.isRegular())
        } catch (e: CatalogNameAmbiguousException) {
            return RoutineSearch(
                locations = emptyList(),
                classification = RoutineClassification.Ambiguous(e.catalogNames, aggregateOnly = false),
            )
        } ?: return notFound()

        if (catalog !is RoutineCatalog) {
            return notFound()
        }
        return classify(listOf(searchExact(catalog, Identifier.of(parts.drop(1)), shape)))
    }

    private fun searchUnqualified(identifier: Identifier, shape: RoutineCallShape): RoutineSearch {
        val routinePart = identifier.getIdentifier()
        val locations = session.getPath().mapNotNull { pathEntry ->
            val levels = pathEntry.getLevels()
            if (levels.isEmpty()) {
                return@mapNotNull null
            }
            val catalog = catalogs.getCatalog(levels.first()) ?: return@mapNotNull null
            when (catalog) {
                is RoutineCatalog -> {
                    val namespace = levels.drop(1).map { Identifier.Simple.delimited(it) }
                    val catalogLocalIdentifier = Identifier.of(namespace + routinePart)
                    searchExact(catalog, catalogLocalIdentifier, shape)
                }
                else -> searchLegacy(catalog, routinePart.getText(), shape)
            }
        }
        return classify(locations)
    }

    private fun searchExact(
        catalog: RoutineCatalog,
        identifier: Identifier,
        shape: RoutineCallShape,
    ): RoutineLocation {
        val scalars = catalog.resolveFunctions(session, identifier)
            .mapNotNull { binding ->
                match(catalog, binding, shape.scalarArity) { it.signature.arity }
            }
        val aggregates = catalog.resolveAggregations(session, identifier)
            .mapNotNull { binding ->
                match(catalog, binding, shape.aggregateArity) { it.signature.arity }
            }
        return RoutineLocation(scalars, aggregates)
    }

    private fun searchLegacy(
        catalog: Catalog,
        queryName: String,
        shape: RoutineCallShape,
    ): RoutineLocation {
        val name = queryName.lowercase(Locale.ROOT)
        val canonicalName = Name.of(name)
        val scalarOverloads = catalog.getFunctions(session, name).toList()
        val scalars = when (scalarOverloads.isEmpty()) {
            true -> emptyList()
            false -> listOf(
                RoutineMatch(
                    catalog = catalog.getName(),
                    canonicalName = canonicalName,
                    overloads = scalarOverloads.filter { it.signature.arity == shape.scalarArity },
                    routine = null,
                ),
            )
        }
        val aggregateOverloads = catalog.getAggregations(session, name)
            .filter { it.signature.arity == shape.aggregateArity }
        val aggregates = when (aggregateOverloads.isEmpty()) {
            true -> emptyList()
            false -> listOf(
                RoutineMatch(
                    catalog = catalog.getName(),
                    canonicalName = canonicalName,
                    overloads = aggregateOverloads,
                    routine = null,
                ),
            )
        }
        return RoutineLocation(scalars, aggregates)
    }

    private fun classify(locations: List<RoutineLocation>): RoutineSearch {
        val scalars = locations.flatMap { it.scalars }
        val aggregates = locations.flatMap { it.aggregates }
        val classification = when {
            scalars.isEmpty() && aggregates.isEmpty() -> RoutineClassification.NotFound
            scalars.isNotEmpty() && aggregates.isNotEmpty() -> {
                val candidates = (
                    scalars.map { it.describe("scalar") } +
                        aggregates.map { it.describe("aggregate") }
                    ).distinct()
                RoutineClassification.Ambiguous(
                    candidates = candidates,
                    aggregateOnly = aggregates.isNotEmpty() && scalars.isEmpty(),
                )
            }
            scalars.isNotEmpty() -> RoutineClassification.Scalar
            else -> RoutineClassification.Aggregate
        }
        return RoutineSearch(locations, classification)
    }

    private fun notFound(): RoutineSearch = RoutineSearch(emptyList(), RoutineClassification.NotFound)

    private fun <T> match(
        catalog: RoutineCatalog,
        binding: RoutineBinding<T>,
        arity: Int,
        getArity: (T) -> Int,
    ): RoutineMatch<T>? {
        val overloads = binding.overloads.filter { getArity(it) == arity }
        if (overloads.isEmpty()) {
            return null
        }
        return RoutineMatch(
            catalog = catalog.getName(),
            canonicalName = binding.canonicalName,
            overloads = overloads,
            routine = RoutineRef(catalog.getName(), binding.canonicalName),
        )
    }

    private data class IdentifierPart(val text: String, val regular: Boolean)

    private data class CacheKey(
        val identifier: List<IdentifierPart>,
        val shape: RoutineCallShape,
    )
}
