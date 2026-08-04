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
import org.partiql.spi.function.FnOverload;
import org.partiql.spi.types.PType;

import java.util.List;

/**
 * Logical operator for a dynamic dispatch.
 * <p>
 * Function overloads ({@link org.partiql.spi.function.FnOverload}) are assumed thread-safe
 * and are embedded directly in the plan.
 */
public abstract class RexDispatch extends RexBase {

    /**
     * Creates a new RexDispatch instance.
     * @param name dynamic function name
     * @param functions functions to dispatch to
     * @param args function arguments
     * @return new RexDispatch instance
     */
    @NotNull
    public static RexDispatch create(String name, List<FnOverload> functions, List<Rex> args) {
        return new Impl(name, functions, args, null);
    }

    /**
     * Creates a new RexDispatch instance with resolved routine identity.
     * @param name dynamic function name
     * @param functions functions to dispatch to
     * @param args function arguments
     * @param routineRef the resolved routine identity
     * @return new RexDispatch instance
     */
    @NotNull
    public static RexDispatch create(
            String name,
            List<FnOverload> functions,
            List<Rex> args,
            @NotNull RoutineRef routineRef
    ) {
        return new Impl(name, functions, args, routineRef);
    }

    /**
     * Dynamic function name.
     * @return dynamic function name
     */
    public abstract String getName();

    /**
     * Returns the functions to dispatch to.
     * @return functions to dispatch to
     */
    public abstract List<FnOverload> getFunctions();

    /**
     * Returns the list of function arguments.
     * @return function arguments
     */
    public abstract List<Rex> getArgs();

    /**
     * Returns the resolved routine identity, or {@code null} for legacy and manually constructed plans.
     */
    @Nullable
    public RoutineRef getRoutineRef() {
        return null;
    }

    @NotNull
    @Override
    protected final RexType type() {
        return RexType.of(PType.dynamic());
    }

    @NotNull
    @Override
    protected final List<Operand> operands() {
        Operand c0 = Operand.vararg(getArgs());
        return List.of(c0);
    }

    @Override
    public <R, C> R accept(OperatorVisitor<R, C> visitor, C ctx) {
        return visitor.visitDispatch(this, ctx);
    }

    private static class Impl extends RexDispatch {

        private final String name;
        private final List<FnOverload> functions;
        private final List<Rex> args;
        private final RoutineRef routineRef;

        private Impl(String name, List<FnOverload> functions, List<Rex> args, RoutineRef routineRef) {
            this.name = name;
            this.functions = functions;
            this.args = args;
            this.routineRef = routineRef;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public List<FnOverload> getFunctions() {
            return functions;
        }

        @Override
        public List<Rex> getArgs() {
            return args;
        }

        @Nullable
        @Override
        public RoutineRef getRoutineRef() {
            return routineRef;
        }
    }
}
