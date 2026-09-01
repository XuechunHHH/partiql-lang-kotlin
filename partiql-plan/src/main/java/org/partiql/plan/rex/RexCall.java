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

package org.partiql.plan.rex;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.partiql.plan.Operand;
import org.partiql.plan.OperatorVisitor;
import org.partiql.plan.RoutineRef;
import org.partiql.spi.function.Fn;

import java.util.List;

/**
 * Logical scalar function expression abstract base class.
 * <p>
 * Functions ({@link org.partiql.spi.function.Fn}) are assumed thread-safe (stateless invoke)
 * and are embedded directly in the plan.
 */
public abstract class RexCall extends RexBase {

    private RoutineRef routineRef;

    /**
     * Creates a new scalar function expression.
     * @param function the function instance backing the call
     * @param args the arguments to the function
     * @return a new scalar function expression
     */
    @NotNull
    public static RexCall create(@NotNull Fn function, @NotNull List<Rex> args) {
        return new Impl(function, args);
    }

    /**
     * Creates a new scalar function expression with resolved routine identity.
     * @param function the function instance backing the call
     * @param args the arguments to the function
     * @param routineRef the resolved routine identity
     * @return a new scalar function expression
     */
    @NotNull
    public static RexCall create(
            @NotNull Fn function,
            @NotNull List<Rex> args,
            @NotNull RoutineRef routineRef
    ) {
        RexCall call = new Impl(function, args);
        call.setRoutineRef(routineRef);
        return call;
    }

    /**
     * Returns the function to invoke.
     *
     * @return the function to invoke
     */
    @NotNull
    public abstract Fn getFunction();

    /**
     * Returns the list of function arguments.
     * @return the list of function arguments
     */
    @NotNull
    public abstract List<Rex> getArgs();

    /**
     * Returns the resolved routine identity, or {@code null} for legacy and manually constructed plans.
     */
    @Nullable
    public RoutineRef getRoutineRef() {
        return routineRef;
    }

    /**
     * Sets the resolved routine identity.
     *
     * @param routineRef the resolved routine identity
     */
    void setRoutineRef(@NotNull RoutineRef routineRef) {
        this.routineRef = routineRef;
    }

    @NotNull
    @Override
    protected RexType type() {
        return RexType.of(getFunction().getSignature().getReturns());
    }

    @NotNull
    @Override
    protected List<Operand> operands() {
        Operand c0 = Operand.vararg(getArgs());
        return List.of(c0);
    }

    @Override
    public <R, C> R accept(OperatorVisitor<R, C> visitor, C ctx) {
        return visitor.visitCall(this, ctx);
    }

    private static class Impl extends RexCall {

        private final Fn function;
        private final List<Rex> args;

        private Impl(Fn function, List<Rex> args) {
            this.function = function;
            this.args = args;
        }

        @NotNull
        @Override
        public Fn getFunction() {
            return function;
        }

        @NotNull
        @Override
        public List<Rex> getArgs() {
            return args;
        }
    }
}
