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

package org.partiql.spi.function;

import org.jetbrains.annotations.NotNull;
import org.partiql.spi.types.PType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <p>
 * This represents the signature of a routine overload. This is distinct from {@link RoutineSignature}, as it is specific
 * to the overload, and not the instance. The overload signature is used to determine if a routine overload is applicable
 * to a given call site, and if so, which routine overload to use.
 * </p>
 * <p>
 * This differs from {@link RoutineSignature}, as it does not have {@link RoutineSignature#isNullCall()} and
 * {@link RoutineSignature#isMissingCall()}, among others.
 * </p>
 */
public final class RoutineOverloadSignature {
    @NotNull
    private final String name;
    @NotNull
    private final List<PType> paramTypes;

    /**
     * Creates a new {@link RoutineOverloadSignature} with the given name and parameters.
     * @param name the name of the function
     * @param parameterTypes the types of the parameters of the function
     */
    public RoutineOverloadSignature(@NotNull String name, @NotNull List<PType> parameterTypes) {
        this.name = name;
        this.paramTypes = Collections.unmodifiableList(new ArrayList<>(parameterTypes));
    }

    /**
     * Returns the name of the function.
     * @return the name of the function
     */
    @NotNull
    public String getName() {
        return name;
    }

    /**
     * Returns the number of parameters that the function takes.
     * @return the number of parameters that the function takes
     */
    public int getArity() {
        return paramTypes.size();
    }

    /**
     * Returns the preferred types of the parameters of the function. This is used for the sorting of {@link FnOverload}
     * and {@link AggOverload}.
     * @return the preferred types of the parameters of the function
     */
    public List<PType> getParameterTypes() {
        return paramTypes;
    }
}
