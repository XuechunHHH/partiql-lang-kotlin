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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.partiql.spi.catalog.Name
import org.partiql.spi.types.PType
import org.partiql.spi.value.Datum

class LoadedRoutineProviderTest {

    @Test
    fun loadsScalarAggregateAndMixedInventories() {
        val scalar = providedFunction("provider.scalar", "root.scalar", function("scalar"))
        val aggregate = providedAggregation("provider.aggregate", "root.aggregate", aggregation("aggregate"))

        val scalarOnly = LoadedRoutineProvider.load(provider(functions = listOf(scalar)))
        val aggregateOnly = LoadedRoutineProvider.load(provider(aggregations = listOf(aggregate)))
        val mixed = LoadedRoutineProvider.load(provider(listOf(scalar), listOf(aggregate)))

        assertEquals(listOf(scalar.id), scalarOnly.functions.map { it.id })
        assertEquals(emptyList<RoutineId>(), scalarOnly.aggregations.map { it.id })
        assertEquals(emptyList<RoutineId>(), aggregateOnly.functions.map { it.id })
        assertEquals(listOf(aggregate.id), aggregateOnly.aggregations.map { it.id })
        assertEquals(listOf(scalar.id), mixed.functions.map { it.id })
        assertEquals(listOf(aggregate.id), mixed.aggregations.map { it.id })
    }

    @Test
    fun snapshotsProviderCollectionsAndScalarSignatures() {
        val mutableOverload = MutableFnOverload(
            RoutineOverloadSignature("tokenize", listOf(PType.string())),
        )
        val routines = mutableListOf(
            providedFunction("provider.tokenize", "root.tokenize", mutableOverload),
        )
        val loaded = LoadedRoutineProvider.load(provider(functions = routines))

        routines.clear()
        mutableOverload.currentSignature =
            RoutineOverloadSignature("changed", listOf(PType.dynamic()))

        val loadedOverload = loaded.functions.single().overloads.single()
        assertEquals("tokenize", loadedOverload.signature.name)
        assertEquals(listOf(PType.string()), loadedOverload.signature.parameterTypes)
        assertNotSame(mutableOverload, loadedOverload)
    }

    @Test
    fun snapshotsAggregateSignatures() {
        val mutableOverload = MutableAggOverload(
            RoutineOverloadSignature("total", listOf(PType.integer())),
        )
        val loaded = LoadedRoutineProvider.load(
            provider(
                aggregations = listOf(
                    providedAggregation("provider.total", "root.total", mutableOverload),
                ),
            ),
        )

        mutableOverload.currentSignature =
            RoutineOverloadSignature("changed", listOf(PType.dynamic()))

        val loadedOverload = loaded.aggregations.single().overloads.single()
        assertEquals("total", loadedOverload.signature.name)
        assertEquals(listOf(PType.integer()), loadedOverload.signature.parameterTypes)
        assertNotSame(mutableOverload, loadedOverload)
    }

    @Test
    fun wrappersDelegateInstanceCreation() {
        val scalar = function("scalar")
        val aggregate = aggregation("aggregate")
        val loaded = LoadedRoutineProvider.load(
            provider(
                functions = listOf(providedFunction("provider.scalar", "root.scalar", scalar)),
                aggregations = listOf(providedAggregation("provider.aggregate", "root.aggregate", aggregate)),
            ),
        )

        assertSame(
            scalar.getInstance(emptyArray()),
            loaded.functions.single().overloads.single().getInstance(emptyArray()),
        )
        assertSame(
            aggregate.getInstance(emptyArray()),
            loaded.aggregations.single().overloads.single().getInstance(emptyArray()),
        )
    }

    @Test
    fun invokesCallbacksOnceInScalarThenAggregateOrder() {
        val callbacks = mutableListOf<String>()
        val loaded = LoadedRoutineProvider.load(
            object : RoutineProvider {
                override fun getFunctions(): Collection<ProvidedRoutine<FnOverload>> {
                    callbacks += RoutineProviderValidationIssue.GET_FUNCTIONS
                    return emptyList()
                }

                override fun getAggregations(): Collection<ProvidedRoutine<AggOverload>> {
                    callbacks += RoutineProviderValidationIssue.GET_AGGREGATIONS
                    return emptyList()
                }
            },
        )

        assertEquals(
            listOf(
                RoutineProviderValidationIssue.GET_FUNCTIONS,
                RoutineProviderValidationIssue.GET_AGGREGATIONS,
            ),
            callbacks,
        )
        assertEquals(emptyList<ProvidedRoutine<FnOverload>>(), loaded.functions)
        assertEquals(emptyList<ProvidedRoutine<AggOverload>>(), loaded.aggregations)
    }

    @Test
    fun firstCallbackFailureSkipsAggregateCallback() {
        val cause = IllegalStateException("functions unavailable")
        var aggregateInvoked = false
        val error = assertThrows<RoutineProviderValidationException> {
            LoadedRoutineProvider.load(
                object : RoutineProvider {
                    override fun getFunctions(): Collection<ProvidedRoutine<FnOverload>> = throw cause

                    override fun getAggregations(): Collection<ProvidedRoutine<AggOverload>> {
                        aggregateInvoked = true
                        return emptyList()
                    }
                },
            )
        }

        val issue = error.issues.single()
        assertEquals(false, aggregateInvoked)
        assertSame(cause, error.cause)
        assertEquals("Routine provider validation failed with 1 issue(s).", error.message)
        assertEquals(RoutineProviderValidationReason.PROVIDER_ACCESS_FAILED, issue.reason)
        assertEquals(RoutineProviderValidationIssue.GET_FUNCTIONS, issue.callback)
        assertEquals("Provider callback getFunctions failed.", issue.message)
        assertNull(issue.routineId)
        assertNull(issue.sourceName)
    }

    @Test
    fun secondCallbackFailurePreservesCause() {
        val cause = IllegalStateException("aggregations unavailable")
        val callbacks = mutableListOf<String>()
        val error = assertThrows<RoutineProviderValidationException> {
            LoadedRoutineProvider.load(
                object : RoutineProvider {
                    override fun getFunctions(): Collection<ProvidedRoutine<FnOverload>> {
                        callbacks += RoutineProviderValidationIssue.GET_FUNCTIONS
                        return emptyList()
                    }

                    override fun getAggregations(): Collection<ProvidedRoutine<AggOverload>> {
                        callbacks += RoutineProviderValidationIssue.GET_AGGREGATIONS
                        throw cause
                    }
                },
            )
        }

        assertEquals(
            listOf(
                RoutineProviderValidationIssue.GET_FUNCTIONS,
                RoutineProviderValidationIssue.GET_AGGREGATIONS,
            ),
            callbacks,
        )
        assertSame(cause, error.cause)
        assertEquals(
            RoutineProviderValidationIssue.GET_AGGREGATIONS,
            error.issues.single().callback,
        )
    }

    @Test
    fun rejectsEmptySourceSegment() {
        val sourceName = Name.of("root", "")
        val error = validationError(
            provider(
                functions = listOf(
                    ProvidedRoutine(RoutineId("provider.empty"), sourceName, listOf(function(""))),
                ),
            ),
        )

        val issue = error.issues.single()
        assertEquals(RoutineProviderValidationReason.EMPTY_SOURCE_SEGMENT, issue.reason)
        assertEquals(RoutineId("provider.empty"), issue.routineId)
        assertEquals(sourceName, issue.sourceName)
        assertNotSame(sourceName, issue.sourceName)
        assertEquals("Source name \"root\".\"\" contains an empty segment.", issue.message)
    }

    @Test
    fun rejectsEmptyOverloads() {
        val error = validationError(
            provider(
                functions = listOf(
                    ProvidedRoutine(RoutineId("provider.empty"), Name.of("root", "empty"), emptyList()),
                ),
            ),
        )

        val issue = error.issues.single()
        assertEquals(RoutineProviderValidationReason.EMPTY_OVERLOADS, issue.reason)
        assertEquals("Routine provider.empty has no overloads.", issue.message)
    }

    @Test
    fun rejectsDuplicateIdsAcrossRoutineKinds() {
        val id = "provider.duplicate"
        val firstSource = Name.of("root", "scalar")
        val secondSource = Name.of("root", "aggregate")
        val error = validationError(
            provider(
                functions = listOf(
                    ProvidedRoutine(RoutineId(id), firstSource, listOf(function("scalar"))),
                ),
                aggregations = listOf(
                    ProvidedRoutine(RoutineId(id), secondSource, listOf(aggregation("aggregate"))),
                ),
            ),
        )

        val issue = error.issues.single()
        assertEquals(RoutineProviderValidationReason.DUPLICATE_ROUTINE_ID, issue.reason)
        assertEquals(RoutineId(id), issue.routineId)
        assertEquals(secondSource, issue.sourceName)
        assertEquals(RoutineId(id), issue.conflictingRoutineId)
        assertEquals(firstSource, issue.conflictingSourceName)
        assertEquals(
            "Routine ID provider.duplicate is declared more than once at \"root\".\"scalar\" and \"root\".\"aggregate\".",
            issue.message,
        )
    }

    @Test
    fun rejectsDuplicateSourceNamesAcrossRoutineKinds() {
        val sourceName = Name.of("root", "routine")
        val error = validationError(
            provider(
                functions = listOf(
                    ProvidedRoutine(RoutineId("provider.scalar"), sourceName, listOf(function("routine"))),
                ),
                aggregations = listOf(
                    ProvidedRoutine(RoutineId("provider.aggregate"), sourceName, listOf(aggregation("routine"))),
                ),
            ),
        )

        val issue = error.issues.single()
        assertEquals(RoutineProviderValidationReason.DUPLICATE_SOURCE_NAME, issue.reason)
        assertEquals(RoutineId("provider.aggregate"), issue.routineId)
        assertEquals(RoutineId("provider.scalar"), issue.conflictingRoutineId)
        assertEquals(
            "Source name \"root\".\"routine\" is declared by routine IDs provider.scalar and provider.aggregate.",
            issue.message,
        )
    }

    @Test
    fun rejectsSignatureNameMismatch() {
        val error = validationError(
            provider(
                functions = listOf(
                    providedFunction("provider.tokenize", "root.tokenize", function("other")),
                ),
            ),
        )

        val issue = error.issues.single()
        assertEquals(RoutineProviderValidationReason.SIGNATURE_NAME_MISMATCH, issue.reason)
        assertEquals("other", issue.signatureName)
        assertEquals(
            "Routine provider.tokenize overload name other does not equal source leaf tokenize.",
            issue.message,
        )
    }

    @Test
    fun rejectsDuplicateOverloadSignatures() {
        val error = validationError(
            provider(
                functions = listOf(
                    providedFunction(
                        "provider.tokenize",
                        "root.tokenize",
                        function("tokenize", PType.string()),
                        function("tokenize", PType.string()),
                    ),
                ),
            ),
        )

        val issue = error.issues.single()
        assertEquals(RoutineProviderValidationReason.DUPLICATE_OVERLOAD_SIGNATURE, issue.reason)
        assertEquals(listOf(PType.string()), issue.parameterTypes)
        assertEquals(
            "Routine provider.tokenize contains a duplicate overload signature.",
            issue.message,
        )
        assertThrows<UnsupportedOperationException> {
            (issue.parameterTypes as MutableList<PType>).add(PType.dynamic())
        }
    }

    @Test
    fun collectsAllDiscoverableValidationIssues() {
        val duplicateSource = Name.of("root", "duplicate")
        val error = validationError(
            provider(
                functions = listOf(
                    ProvidedRoutine(
                        RoutineId("provider.duplicate"),
                        duplicateSource,
                        listOf(function("wrong"), function("wrong")),
                    ),
                    ProvidedRoutine(
                        RoutineId("provider.duplicate"),
                        Name.of("root", "second"),
                        emptyList(),
                    ),
                    ProvidedRoutine(
                        RoutineId("provider.third"),
                        duplicateSource,
                        listOf(function("duplicate")),
                    ),
                ),
            ),
        )

        assertEquals(
            setOf(
                RoutineProviderValidationReason.SIGNATURE_NAME_MISMATCH,
                RoutineProviderValidationReason.DUPLICATE_OVERLOAD_SIGNATURE,
                RoutineProviderValidationReason.EMPTY_OVERLOADS,
                RoutineProviderValidationReason.DUPLICATE_ROUTINE_ID,
                RoutineProviderValidationReason.DUPLICATE_SOURCE_NAME,
            ),
            error.issues.mapTo(mutableSetOf()) { it.reason },
        )
        assertEquals(6, error.issues.size)
    }

    @Test
    fun exceptionIssuesAreJavaUnmodifiable() {
        val error = validationError(
            provider(
                functions = listOf(
                    ProvidedRoutine(
                        RoutineId("provider.empty"),
                        Name.of("root", "empty"),
                        emptyList(),
                    ),
                ),
            ),
        )

        assertThrows<UnsupportedOperationException> {
            (error.issues as MutableList<RoutineProviderValidationIssue>).add(error.issues.single())
        }
    }

    private fun validationError(provider: RoutineProvider): RoutineProviderValidationException =
        assertThrows {
            LoadedRoutineProvider.load(provider)
        }

    private fun provider(
        functions: Collection<ProvidedRoutine<FnOverload>> = emptyList(),
        aggregations: Collection<ProvidedRoutine<AggOverload>> = emptyList(),
    ): RoutineProvider =
        object : RoutineProvider {
            override fun getFunctions(): Collection<ProvidedRoutine<FnOverload>> = functions

            override fun getAggregations(): Collection<ProvidedRoutine<AggOverload>> = aggregations
        }

    private fun providedFunction(
        id: String,
        sourceName: String,
        vararg overloads: FnOverload,
    ): ProvidedRoutine<FnOverload> =
        ProvidedRoutine(RoutineId(id), Name.of(sourceName.split(".")), overloads.toList())

    private fun providedAggregation(
        id: String,
        sourceName: String,
        vararg overloads: AggOverload,
    ): ProvidedRoutine<AggOverload> =
        ProvidedRoutine(RoutineId(id), Name.of(sourceName.split(".")), overloads.toList())

    private fun function(name: String, vararg parameters: PType): FnOverload {
        val builder = FnOverload.Builder(name)
            .returns(PType.dynamic())
            .body { Datum.missing() }
        parameters.forEach(builder::addParameter)
        return builder.build()
    }

    private fun aggregation(name: String, vararg parameters: PType): AggOverload {
        val builder = AggOverload.Builder(name).returns(PType.dynamic())
        parameters.forEach(builder::addParameter)
        return builder.build()
    }

    private class MutableFnOverload(
        var currentSignature: RoutineOverloadSignature,
    ) : FnOverload() {
        override fun getSignature(): RoutineOverloadSignature = currentSignature

        override fun getInstance(args: Array<PType>): Fn? = null
    }

    private class MutableAggOverload(
        var currentSignature: RoutineOverloadSignature,
    ) : AggOverload() {
        override fun getSignature(): RoutineOverloadSignature = currentSignature

        override fun getInstance(args: Array<PType>): Agg? = null
    }
}
