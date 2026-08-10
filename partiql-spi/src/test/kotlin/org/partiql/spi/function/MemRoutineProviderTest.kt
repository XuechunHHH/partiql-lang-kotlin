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
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.partiql.spi.catalog.Identifier
import org.partiql.spi.catalog.Name
import org.partiql.spi.catalog.Namespace
import org.partiql.spi.catalog.RoutineBinding
import org.partiql.spi.types.PType
import org.partiql.spi.types.PTypeField

class MemRoutineProviderTest {

    @Test
    fun providerDefaultsAreEmpty() {
        val provider = object : RoutineProvider {}

        assertTrue(provider.getFunctions(Identifier.regular("missing")).isEmpty())
        assertTrue(provider.getAggregations(Identifier.regular("missing")).isEmpty())
    }

    @Test
    fun registersAtRootNamespaceAndExactName() {
        val root = function("root")
        val namespaced = function("namespaced")
        val exact = function("exact")
        val aggregate = aggregation("total")
        val provider = MemRoutineProvider.builder()
            .register(root)
            .register(namespaced, Namespace.of("analytics"))
            .register(exact, Name.of("custom", "exact"))
            .register(aggregate, Namespace.of("analytics"))
            .build()

        assertSame(root, provider.getFunctions(Identifier.regular("root")).single().overloads.single())
        assertSame(
            namespaced,
            provider.getFunctions(Identifier.regular("analytics", "namespaced")).single().overloads.single(),
        )
        assertSame(
            exact,
            provider.getFunctions(Identifier.regular("custom", "exact")).single().overloads.single(),
        )
        assertSame(
            aggregate,
            provider.getAggregations(Identifier.regular("analytics", "total")).single().overloads.single(),
        )
    }

    @Test
    fun composesDistinctSignaturesAtOneName() {
        val integer = function("pow", PType.integer(), PType.integer())
        val double = function("pow", PType.doublePrecision(), PType.doublePrecision())
        val provider = MemRoutineProvider.builder()
            .register(integer, Namespace.of("math"))
            .register(double, Namespace.of("math"))
            .build()

        val binding = provider.getFunctions(Identifier.regular("math", "pow")).single()

        assertEquals(Name.of("math", "pow"), binding.canonicalName)
        assertEquals(listOf(integer, double), binding.overloads)
    }

    @Test
    fun matchesCompleteIdentifierWithSqlCaseRules() {
        val function = function("Pow")
        val provider = MemRoutineProvider.builder()
            .register(function, Name.of("Math", "Pow"))
            .build()

        assertEquals(
            Name.of("Math", "Pow"),
            provider.getFunctions(Identifier.regular("math", "pow")).single().canonicalName,
        )
        assertEquals(
            Name.of("Math", "Pow"),
            provider.getFunctions(Identifier.delimited("Math", "Pow")).single().canonicalName,
        )
        assertTrue(provider.getFunctions(Identifier.delimited("math", "pow")).isEmpty())
        assertTrue(provider.getFunctions(Identifier.regular("pow")).isEmpty())
        assertTrue(provider.getFunctions(Identifier.regular("other", "math", "pow")).isEmpty())
    }

    @Test
    fun scalarAndAggregateBindingsRemainSeparate() {
        val function = function("shared", PType.integer())
        val aggregation = aggregation("shared", PType.integer())
        val provider = MemRoutineProvider.builder()
            .register(function, Namespace.of("stats"))
            .register(aggregation, Namespace.of("stats"))
            .build()

        assertSame(
            function,
            provider.getFunctions(Identifier.regular("stats", "shared")).single().overloads.single(),
        )
        assertSame(
            aggregation,
            provider.getAggregations(Identifier.regular("stats", "shared")).single().overloads.single(),
        )
    }

    @Test
    fun rejectsLeafRenaming() {
        val error = assertThrows<IllegalArgumentException> {
            MemRoutineProvider.builder()
                .register(function("pow"), Name.of("math", "power"))
                .build()
        }

        assertTrue(error.message!!.contains("does not match overload name"))
    }

    @Test
    fun rejectsEmptyNameSegments() {
        val error = assertThrows<IllegalArgumentException> {
            MemRoutineProvider.builder()
                .register(function("pow"), Name.of("math", "", "pow"))
                .build()
        }

        assertTrue(error.message!!.contains("empty segment"))
    }

    @Test
    fun rejectsCaseInsensitiveCanonicalNameCollisions() {
        val error = assertThrows<IllegalArgumentException> {
            MemRoutineProvider.builder()
                .register(function("pow"), Name.of("math", "pow"))
                .register(function("Pow"), Name.of("Math", "Pow"))
                .build()
        }

        assertTrue(error.message!!.contains("conflict under regular SQL matching"))
    }

    @Test
    fun rejectsDuplicateSignaturesIncludingEqualRowsWithDifferentHashes() {
        val firstRow = PType.row(PTypeField.of("value", PType.string()))
        val secondRow = PType.row(PTypeField.of("value", PType.string()))
        val error = assertThrows<IllegalArgumentException> {
            MemRoutineProvider.builder()
                .register(function("inspect", firstRow))
                .register(function("inspect", secondRow))
                .build()
        }

        assertTrue(error.message!!.contains("Duplicate scalar routine signature"))
    }

    @Test
    fun snapshotsBuilderStateAndReturnsJavaUnmodifiableMatches() {
        val builder = MemRoutineProvider.builder().register(function("first"))
        val provider = builder.build()
        builder.register(function("second"))
        val matches = provider.getFunctions(Identifier.regular("first"))

        assertEquals(1, matches.size)
        assertTrue(provider.getFunctions(Identifier.regular("second")).isEmpty())
        assertThrows<UnsupportedOperationException> {
            (matches as MutableCollection<RoutineBinding<FnOverload>>).clear()
        }
    }

    private fun function(
        name: String,
        vararg parameters: PType,
    ): FnOverload =
        FnOverload.Builder(name)
            .addParameters(*parameters)
            .build()

    private fun aggregation(
        name: String,
        vararg parameters: PType,
    ): AggOverload =
        AggOverload.Builder(name)
            .addParameters(*parameters)
            .build()
}
