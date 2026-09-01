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

import org.junit.jupiter.api.Test
import org.partiql.spi.catalog.Identifier
import org.partiql.spi.catalog.Name
import org.partiql.spi.catalog.RoutineBinding
import org.partiql.spi.catalog.RoutineCatalog
import org.partiql.spi.catalog.Session
import org.partiql.spi.function.AggOverload
import org.partiql.spi.function.FnOverload
import org.partiql.spi.types.PType
import org.partiql.spi.value.Datum
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RoutineResolverValidationTest {

    @Test
    fun `null exact lookup collection is invalid`() {
        assertInvalid(
            MalformedRoutineFixtures.catalogWithNullFunctions("demo"),
            "lookup returned null",
        )
    }

    @Test
    fun `null aggregate lookup collection is invalid`() {
        assertInvalid(
            MalformedRoutineFixtures.catalogWithNullAggregations("demo"),
            "invalid aggregate result",
        )
    }

    @Test
    fun `null binding is invalid`() {
        assertInvalid(
            catalog(MalformedRoutineFixtures.nullBindingResults()),
            "binding 0 is null",
        )
    }

    @Test
    fun `null overload is invalid`() {
        assertInvalid(
            catalog(
                listOf(MalformedRoutineFixtures.bindingWithNullOverload(Name.of("echo"))),
            ),
            "overload 0 is null",
        )
    }

    @Test
    fun `null overload signature is invalid`() {
        val binding = RoutineBinding(
            Name.of("echo"),
            listOf(MalformedRoutineFixtures.overloadWithNullSignature()),
        )

        assertInvalid(catalog(listOf(binding)), "has a null signature")
    }

    @Test
    fun `null signature name is invalid`() {
        val signature = MalformedRoutineFixtures.signatureWithNullName(listOf(PType.integer()))
        val binding = binding(MalformedRoutineFixtures.overloadWithSignature(signature))

        assertInvalid(catalog(listOf(binding)), "name null does not match canonical leaf echo")
    }

    @Test
    fun `null parameter list is invalid`() {
        val signature = MalformedRoutineFixtures.signatureWithNullParameters("echo")
        val binding = binding(MalformedRoutineFixtures.overloadWithSignature(signature))

        assertInvalid(catalog(listOf(binding)), "has null parameter types")
    }

    @Test
    fun `null parameter type is invalid`() {
        val signature = MalformedRoutineFixtures.signatureWithNullParameter("echo")
        val binding = binding(MalformedRoutineFixtures.overloadWithSignature(signature))

        assertInvalid(catalog(listOf(binding)), "parameter 0 is null")
    }

    @Test
    fun `empty canonical name part is invalid`() {
        val binding = MalformedRoutineFixtures.bindingWithCanonicalParts(
            listOf(""),
            scalar("echo", PType.integer()),
        )

        assertInvalid(catalog(listOf(binding)), "canonical name contains an empty part")
    }

    @Test
    fun `empty overload collection is invalid`() {
        val binding = MalformedRoutineFixtures.bindingWithEmptyOverloads(
            Name.of("echo"),
            scalar("echo", PType.integer()),
        )

        assertInvalid(catalog(listOf(binding)), "has no overloads")
    }

    @Test
    fun `canonical name depth must match the exact request`() {
        val binding = RoutineBinding(
            Name.of("namespace", "echo"),
            listOf(scalar("echo", PType.integer())),
        )

        assertInvalid(catalog(listOf(binding)), "does not match the requested identifier")
    }

    @Test
    fun `canonical name must match each request part using its case mode`() {
        val binding = RoutineBinding(
            Name.of("ECHO"),
            listOf(scalar("ECHO", PType.integer())),
        )
        val identifier = Identifier.of(
            Identifier.Simple.regular("demo"),
            Identifier.Simple.delimited("echo"),
        )

        assertInvalid(catalog(listOf(binding)), "does not match the requested identifier", identifier)
    }

    @Test
    fun `overload name must exactly match the canonical leaf before arity filtering`() {
        val binding = RoutineBinding(
            Name.of("echo"),
            listOf(scalar("ECHO", PType.integer(), PType.integer())),
        )

        assertInvalid(catalog(listOf(binding)), "does not match canonical leaf echo")
    }

    @Test
    fun `duplicate parameter sequences in one binding are invalid`() {
        val binding = RoutineBinding(
            Name.of("echo"),
            listOf(
                scalar("echo", PType.integer()),
                scalar("echo", PType.integer()),
            ),
        )

        assertInvalid(catalog(listOf(binding)), "contains duplicate parameter types")
    }

    @Test
    fun `lookup method exceptions propagate unchanged`() {
        val expected = ProviderFailure()
        val catalog = object : RoutineCatalog {
            override fun getName(): String = "demo"

            override fun resolveFunctions(
                session: Session,
                identifier: Identifier,
            ): Collection<RoutineBinding<FnOverload>> = throw expected

            override fun resolveAggregations(
                session: Session,
                identifier: Identifier,
            ): Collection<RoutineBinding<AggOverload>> = emptyList()
        }

        val actual = assertFailsWith<ProviderFailure> {
            search(catalog)
        }

        assertSame(expected, actual)
    }

    @Test
    fun `metadata traversal failures are wrapped with their cause`() {
        val expected = IllegalArgumentException("broken collection")
        val catalog = catalog(MalformedRoutineFixtures.throwingResults(expected))

        val actual = assertFailsWith<IllegalStateException> {
            search(catalog)
        }

        assertSame(expected, actual.cause)
        assertContains(actual.message.orEmpty(), "returned invalid scalar results")
    }

    @Test
    fun `fatal JVM errors during metadata traversal propagate unchanged`() {
        val expected = TestVirtualMachineError()
        val binding = binding(MalformedRoutineFixtures.overloadThrowingSignature(expected))

        val actual = assertFailsWith<TestVirtualMachineError> {
            search(catalog(listOf(binding)))
        }

        assertSame(expected, actual)
    }

    private fun assertInvalid(
        catalog: RoutineCatalog,
        detail: String,
        identifier: Identifier = Identifier.regular("demo", "echo"),
    ) {
        val error = assertFailsWith<IllegalStateException> {
            search(catalog, identifier)
        }
        assertContains(error.message.orEmpty(), detail)
    }

    private fun search(
        catalog: RoutineCatalog,
        identifier: Identifier = Identifier.regular("demo", "echo"),
    ): RoutineSearch {
        val session = Session.builder()
            .catalog("demo")
            .catalogs(catalog)
            .build()
        return RoutineResolver(session).search(identifier, RoutineCallShape(1, 1))
    }

    private fun catalog(
        functions: Collection<RoutineBinding<FnOverload>>,
    ): RoutineCatalog = MalformedRoutineFixtures.catalog("demo", functions, emptyList())

    private fun binding(overload: FnOverload): RoutineBinding<FnOverload> =
        RoutineBinding(Name.of("echo"), listOf(overload))

    private fun scalar(name: String, vararg parameters: PType): FnOverload {
        val builder = FnOverload.Builder(name)
            .returns(PType.integer())
            .body { args -> args.firstOrNull() ?: Datum.integer(0) }
        parameters.forEach(builder::addParameter)
        return builder.build()
    }

    private class ProviderFailure : RuntimeException()

    private class TestVirtualMachineError : VirtualMachineError()
}
