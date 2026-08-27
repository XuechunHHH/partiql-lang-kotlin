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

package org.partiql.plan

import org.junit.jupiter.api.Test
import org.partiql.plan.rel.RelAggregate
import org.partiql.plan.rex.Rex
import org.partiql.plan.rex.RexCall
import org.partiql.plan.rex.RexDispatch
import org.partiql.plan.rex.RexLit
import org.partiql.spi.catalog.Name
import org.partiql.spi.function.Accumulator
import org.partiql.spi.function.Agg
import org.partiql.spi.function.AggOverload
import org.partiql.spi.function.Fn
import org.partiql.spi.function.FnOverload
import org.partiql.spi.types.PType
import org.partiql.spi.value.Datum
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class RoutineRefTest {

    private val routineRef = RoutineRef("Catalog", Name.of("Namespace", "Routine"))
    private val literal = RexLit.create(Datum.integer(1))
    private val function: Fn = Fn.Builder("routine")
        .returns(PType.integer())
        .body { Datum.integer(1) }
        .build()
    private val overload: FnOverload = FnOverload.Builder("routine")
        .returns(PType.integer())
        .body { Datum.integer(1) }
        .build()
    private val aggregation: Agg = AggOverload.Builder("routine")
        .returns(PType.integer())
        .body {
            object : Accumulator {
                override fun next(args: Array<Datum>) = Unit

                override fun value(): Datum = Datum.integer(1)
            }
        }
        .build()
        .getInstance(emptyArray())!!

    @Test
    fun preservesExactValueIdentity() {
        val inputName = Name.of("Namespace", "Routine")
        val same = RoutineRef("Catalog", inputName)

        assertEquals(routineRef, same)
        assertEquals(routineRef.hashCode(), same.hashCode())
        assertEquals("Catalog", routineRef.catalog)
        assertEquals(inputName, routineRef.name)
        assertNotSame(inputName, same.name)
        assertNotEquals(RoutineRef("catalog", inputName), routineRef)
        assertNotEquals(RoutineRef("Catalog", Name.of("namespace", "Routine")), routineRef)
        assertNotEquals(RoutineRef("Catalog", Name.of("Namespace", "routine")), routineRef)
    }

    @Test
    fun copiesInputAndReturnedNames() {
        val inputName = Name.of("Namespace", "Routine")
        val reference = RoutineRef("Catalog", inputName)

        inputName.getNamespace().getLevels()[0] = "ChangedInput"
        reference.name.getNamespace().getLevels()[0] = "ChangedOutput"

        assertEquals(Name.of("Namespace", "Routine"), reference.name)
        assertEquals(RoutineRef("Catalog", Name.of("Namespace", "Routine")), reference)
    }

    @Test
    fun legacyFactoriesHaveNoIdentity() {
        assertNull(RexCall.create(function, listOf(literal)).routineRef)
        assertNull(RexDispatch.create("routine", listOf(overload), listOf(literal)).routineRef)
        assertNull(RelAggregate.measure(aggregation, listOf(literal), false).routineRef)
    }

    @Test
    fun customSubclassesHaveNoIdentityByDefault() {
        val call = object : RexCall() {
            override fun getFunction(): Fn = function

            override fun getArgs(): List<Rex> = listOf(literal)
        }
        val dispatch = object : RexDispatch() {
            override fun getName(): String = "routine"

            override fun getFunctions(): List<FnOverload> = listOf(overload)

            override fun getArgs(): List<Rex> = listOf(literal)
        }

        assertNull(call.routineRef)
        assertNull(dispatch.routineRef)
    }

    @Test
    fun identityAwareFactoriesAttachIdentity() {
        assertSame(routineRef, RexCall.create(function, listOf(literal), routineRef).routineRef)
        assertSame(
            routineRef,
            RexDispatch.create("routine", listOf(overload), listOf(literal), routineRef).routineRef,
        )
        assertSame(routineRef, RelAggregate.measure(aggregation, listOf(literal), false, routineRef).routineRef)
    }

    @Test
    fun callRewritePreservesIdentity() {
        val original = RexCall.create(function, listOf(literal), routineRef)

        val rewritten = LiteralReplacingRewriter().visitRex(original, Unit) as RexCall

        assertNotSame(original, rewritten)
        assertSame(routineRef, rewritten.routineRef)
    }

    @Test
    fun dispatchRewritePreservesIdentity() {
        val original = RexDispatch.create("routine", listOf(overload), listOf(literal), routineRef)

        val rewritten = LiteralReplacingRewriter().visitRex(original, Unit) as RexDispatch

        assertNotSame(original, rewritten)
        assertSame(routineRef, rewritten.routineRef)
    }

    @Test
    fun identityAwareRewriteUsesCustomFactory() {
        val operators = TrackingOperators()
        val rewriter = LiteralReplacingRewriter(operators)
        val call = RexCall.create(function, listOf(literal), routineRef)
        val dispatch = RexDispatch.create("routine", listOf(overload), listOf(literal), routineRef)

        val rewrittenCall = rewriter.visitRex(call, Unit) as RexCall
        val rewrittenDispatch = rewriter.visitRex(dispatch, Unit) as RexDispatch

        assertEquals(1, operators.callCount)
        assertEquals(1, operators.dispatchCount)
        assertSame(routineRef, rewrittenCall.routineRef)
        assertSame(routineRef, rewrittenDispatch.routineRef)
    }

    @Test
    fun measureCopyAndRewritePreserveIdentity() {
        val original = RelAggregate.measure(aggregation, listOf(literal), false, routineRef)

        val copied = original.copy(listOf(RexLit.create(Datum.integer(2))))
        val rewritten = LiteralReplacingRewriter().visitAggregateMeasure(original, Unit)

        assertSame(routineRef, copied.routineRef)
        assertNotSame(original, rewritten)
        assertSame(routineRef, rewritten.routineRef)
    }

    private class LiteralReplacingRewriter(
        operators: Operators = Operators.STANDARD,
    ) : OperatorRewriter<Unit>(operators) {
        override fun visitLit(rex: RexLit, ctx: Unit): Operator = RexLit.create(rex.datum)
    }

    private class TrackingOperators : Operators {
        var callCount: Int = 0
            private set

        var dispatchCount: Int = 0
            private set

        override fun call(function: Fn, args: List<Rex>, routineRef: RoutineRef): RexCall {
            callCount++
            return RexCall.create(function, args, routineRef)
        }

        override fun dispatch(
            name: String,
            functions: List<FnOverload>,
            args: List<Rex>,
            routineRef: RoutineRef,
        ): RexDispatch {
            dispatchCount++
            return RexDispatch.create(name, functions, args, routineRef)
        }
    }
}
