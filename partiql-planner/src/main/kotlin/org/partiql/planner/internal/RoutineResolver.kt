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
import org.partiql.spi.function.RoutineOverloadSignature
import org.partiql.spi.types.PType
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
    ) : RoutineClassification
}

internal class RoutineMatch<T>(
    catalogLookup: () -> String,
    val canonicalName: Name,
    val overloads: List<T>,
    val routine: RoutineRef?,
) {
    internal val catalog: String by lazy(LazyThreadSafetyMode.NONE, catalogLookup)

    internal fun describe(kind: String): String = "$kind $catalog.$canonicalName"
}

internal class RoutineLocation(
    scalarLookup: () -> List<RoutineMatch<FnOverload>>,
    aggregateLookup: () -> List<RoutineMatch<AggOverload>>,
) {
    internal val scalars: List<RoutineMatch<FnOverload>> by lazy(LazyThreadSafetyMode.NONE, scalarLookup)
    internal val aggregates: List<RoutineMatch<AggOverload>> by lazy(LazyThreadSafetyMode.NONE, aggregateLookup)
}

internal data class RoutineSearch(
    val locations: List<RoutineLocation>,
    val classification: RoutineClassification,
)

private data class ValidatedRoutineBinding<T : Any>(
    val canonicalName: Name,
    val overloads: List<ValidatedRoutineOverload<T>>,
)

private data class ValidatedRoutineOverload<T : Any>(
    val overload: T,
    val parameterTypes: List<PType>,
)

/**
 * Caches exact catalog lookups for one plan and classifies scalar and aggregate candidates from the same snapshot.
 */
internal class RoutineResolver(private val session: Session) {

    private val catalogs = session.getCatalogs()
    private val cache = mutableMapOf<CacheKey, RoutineSearch>()
    private var propagatingFailure: Throwable? = null

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

    internal fun shouldPropagate(failure: Throwable): Boolean = propagatingFailure === failure

    private fun searchQualified(identifier: Identifier, shape: RoutineCallShape): RoutineSearch {
        val parts = identifier.getParts()
        val catalogPart = parts.first()
        val catalog = try {
            catalogs.getCatalog(catalogPart.getText(), ignoreCase = catalogPart.isRegular())
        } catch (e: CatalogNameAmbiguousException) {
            return RoutineSearch(
                locations = emptyList(),
                classification = RoutineClassification.Ambiguous(e.catalogNames),
            )
        } catch (e: Throwable) {
            propagate(e)
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
            val location = lazy(LazyThreadSafetyMode.NONE) {
                val catalog = catalogs.getCatalog(levels.first()) ?: return@lazy null
                when (catalog) {
                    is RoutineCatalog -> {
                        val namespace = levels.drop(1).map { Identifier.Simple.delimited(it) }
                        val catalogLocalIdentifier = Identifier.of(namespace + routinePart)
                        searchExact(catalog, catalogLocalIdentifier, shape)
                    }
                    else -> searchLegacy(catalog, routinePart.getText(), shape)
                }
            }
            RoutineLocation(
                scalarLookup = { location.value?.scalars ?: emptyList() },
                aggregateLookup = { location.value?.aggregates ?: emptyList() },
            )
        }
        return classify(locations)
    }

    private fun searchExact(
        catalog: RoutineCatalog,
        identifier: Identifier,
        shape: RoutineCallShape,
    ): RoutineLocation {
        val catalogName = lazy(LazyThreadSafetyMode.NONE) {
            catalogCall { catalog.getName() }
        }
        return RoutineLocation(
            scalarLookup = {
                searchExact(
                    catalogName = catalogName.value,
                    kind = "scalar",
                    identifier = identifier,
                    arity = shape.scalarArity,
                    bindings = { catalog.resolveFunctions(session, identifier) },
                    signature = { it.signature },
                )
            },
            aggregateLookup = {
                searchExact(
                    catalogName = catalogName.value,
                    kind = "aggregate",
                    identifier = identifier,
                    arity = shape.aggregateArity,
                    bindings = { catalog.resolveAggregations(session, identifier) },
                    signature = { it.signature },
                )
            },
        )
    }

    private fun <T : Any> searchExact(
        catalogName: String,
        kind: String,
        identifier: Identifier,
        arity: Int,
        bindings: () -> Collection<RoutineBinding<T>>?,
        signature: (T) -> RoutineOverloadSignature?,
    ): List<RoutineMatch<T>> =
        validateBindings(
            catalogName = catalogName,
            kind = kind,
            identifier = identifier,
            bindings = catalogCall(bindings),
            signature = signature,
        ).mapNotNull { binding ->
            match(catalogName, binding, arity)
        }

    private fun searchLegacy(
        catalog: Catalog,
        queryName: String,
        shape: RoutineCallShape,
    ): RoutineLocation {
        val name = queryName.lowercase(Locale.ROOT)
        val canonicalName = Name.of(name)
        return RoutineLocation(
            scalarLookup = {
                val overloads = catalog.getFunctions(session, name)
                    .filter { it.signature.arity == shape.scalarArity }
                when (overloads.isEmpty()) {
                    true -> emptyList()
                    false -> listOf(
                        RoutineMatch(
                            catalogLookup = { catalog.getName() },
                            canonicalName = canonicalName,
                            overloads = overloads,
                            routine = null,
                        ),
                    )
                }
            },
            aggregateLookup = {
                val overloads = catalog.getAggregations(session, name)
                    .filter { it.signature.arity == shape.aggregateArity }
                when (overloads.isEmpty()) {
                    true -> emptyList()
                    false -> listOf(
                        RoutineMatch(
                            catalogLookup = { catalog.getName() },
                            canonicalName = canonicalName,
                            overloads = overloads,
                            routine = null,
                        ),
                    )
                }
            },
        )
    }

    private fun classify(locations: List<RoutineLocation>): RoutineSearch {
        var scalars: List<RoutineMatch<FnOverload>> = emptyList()
        var aggregates: List<RoutineMatch<AggOverload>> = emptyList()
        for (location in locations) {
            if (scalars.isEmpty()) {
                scalars = location.scalars
            }
            if (aggregates.isEmpty()) {
                aggregates = location.aggregates
            }
            if (scalars.isNotEmpty() && aggregates.isNotEmpty()) {
                break
            }
        }
        val classification = when {
            scalars.isEmpty() && aggregates.isEmpty() -> RoutineClassification.NotFound
            scalars.isNotEmpty() && aggregates.isNotEmpty() -> {
                val candidates = (
                    scalars.map { it.describe("scalar") } +
                        aggregates.map { it.describe("aggregate") }
                    ).distinct()
                RoutineClassification.Ambiguous(
                    candidates = candidates,
                )
            }
            scalars.isNotEmpty() -> RoutineClassification.Scalar
            else -> RoutineClassification.Aggregate
        }
        return RoutineSearch(locations, classification)
    }

    private fun notFound(): RoutineSearch = RoutineSearch(emptyList(), RoutineClassification.NotFound)

    private fun <T : Any> match(
        catalogName: String,
        binding: ValidatedRoutineBinding<T>,
        arity: Int,
    ): RoutineMatch<T>? {
        val overloads = binding.overloads
            .filter { it.parameterTypes.size == arity }
            .map { it.overload }
        if (overloads.isEmpty()) {
            return null
        }
        return RoutineMatch(
            catalogLookup = { catalogName },
            canonicalName = binding.canonicalName,
            overloads = overloads,
            routine = RoutineRef(catalogName, binding.canonicalName),
        )
    }

    private fun <T : Any> validateBindings(
        catalogName: String,
        kind: String,
        identifier: Identifier,
        bindings: Collection<RoutineBinding<T>>?,
        signature: (T) -> RoutineOverloadSignature?,
    ): List<ValidatedRoutineBinding<T>> {
        try {
            val returned = bindings ?: invalidResult(
                catalogName,
                kind,
                identifier,
                "lookup returned null",
            )
            @Suppress("UNCHECKED_CAST")
            val snapshot = ArrayList(returned as Collection<RoutineBinding<T>?>)
            return snapshot.mapIndexed { bindingIndex, candidate ->
                val binding = candidate ?: invalidResult(
                    catalogName,
                    kind,
                    identifier,
                    "binding $bindingIndex is null",
                )
                validateBinding(catalogName, kind, identifier, bindingIndex, binding, signature)
            }
        } catch (e: InvalidRoutineResultException) {
            propagate(e)
        } catch (e: VirtualMachineError) {
            propagate(e)
        } catch (e: ThreadDeath) {
            propagate(e)
        } catch (e: LinkageError) {
            propagate(e)
        } catch (e: Throwable) {
            propagate(
                InvalidRoutineResultException(
                    "Routine catalog $catalogName returned invalid $kind results for $identifier",
                    e,
                ),
            )
        }
    }

    private fun <T : Any> validateBinding(
        catalogName: String,
        kind: String,
        identifier: Identifier,
        bindingIndex: Int,
        binding: RoutineBinding<T>,
        signature: (T) -> RoutineOverloadSignature?,
    ): ValidatedRoutineBinding<T> {
        val canonicalParts = binding.canonicalName.toList()
        if (canonicalParts.isEmpty()) {
            invalidResult(catalogName, kind, identifier, "binding $bindingIndex has an empty canonical name")
        }
        if (canonicalParts.any(String::isEmpty)) {
            invalidResult(
                catalogName,
                kind,
                identifier,
                "binding $bindingIndex canonical name contains an empty part",
            )
        }

        val identifierParts = identifier.getParts()
        if (identifierParts.size != canonicalParts.size ||
            identifierParts.indices.any { !identifierParts[it].matches(canonicalParts[it]) }
        ) {
            invalidResult(
                catalogName,
                kind,
                identifier,
                "binding $bindingIndex canonical name ${binding.canonicalName} does not match the requested identifier",
            )
        }

        @Suppress("UNCHECKED_CAST")
        val overloadSnapshot = ArrayList(binding.overloads as Collection<T?>)
        if (overloadSnapshot.isEmpty()) {
            invalidResult(catalogName, kind, identifier, "binding $bindingIndex has no overloads")
        }

        val leaf = canonicalParts.last()
        val parameterSignatures = mutableListOf<List<PType>>()
        val overloads = overloadSnapshot.mapIndexed { overloadIndex, candidate ->
            val overload = candidate ?: invalidResult(
                catalogName,
                kind,
                identifier,
                "binding $bindingIndex overload $overloadIndex is null",
            )
            val checkedSignature = signature(overload) ?: invalidResult(
                catalogName,
                kind,
                identifier,
                "binding $bindingIndex overload $overloadIndex has a null signature",
            )
            val signatureName: String? = checkedSignature.name
            if (signatureName != leaf) {
                invalidResult(
                    catalogName,
                    kind,
                    identifier,
                    "binding $bindingIndex overload $overloadIndex name $signatureName " +
                        "does not match canonical leaf $leaf",
                )
            }

            val rawParameterTypes: List<PType?>? = checkedSignature.parameterTypes
            val parameters = rawParameterTypes?.mapIndexed { parameterIndex, type ->
                type ?: invalidResult(
                    catalogName,
                    kind,
                    identifier,
                    "binding $bindingIndex overload $overloadIndex parameter $parameterIndex is null",
                )
            } ?: invalidResult(
                catalogName,
                kind,
                identifier,
                "binding $bindingIndex overload $overloadIndex has null parameter types",
            )
            if (parameters in parameterSignatures) {
                invalidResult(
                    catalogName,
                    kind,
                    identifier,
                    "binding $bindingIndex contains duplicate parameter types $parameters",
                )
            }
            parameterSignatures += parameters
            ValidatedRoutineOverload(overload, parameters)
        }
        return ValidatedRoutineBinding(
            canonicalName = Name.of(canonicalParts),
            overloads = overloads,
        )
    }

    private fun invalidResult(
        catalogName: String,
        kind: String,
        identifier: Identifier,
        detail: String,
    ): Nothing {
        propagate(
            InvalidRoutineResultException(
                "Routine catalog $catalogName returned an invalid $kind result for $identifier: $detail",
            ),
        )
    }

    private inline fun <T> catalogCall(block: () -> T): T =
        try {
            block()
        } catch (e: Throwable) {
            propagate(e)
        }

    private fun propagate(failure: Throwable): Nothing {
        propagatingFailure = failure
        throw failure
    }

    private data class IdentifierPart(val text: String, val regular: Boolean)

    private data class CacheKey(
        val identifier: List<IdentifierPart>,
        val shape: RoutineCallShape,
    )

    private class InvalidRoutineResultException(
        message: String,
        cause: Throwable? = null,
    ) : IllegalStateException(message, cause)
}
