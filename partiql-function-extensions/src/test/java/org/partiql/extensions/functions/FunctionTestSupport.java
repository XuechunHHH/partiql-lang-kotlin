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

package org.partiql.extensions.functions;

import org.partiql.spi.function.Fn;
import org.partiql.spi.function.FnOverload;
import org.partiql.spi.types.PType;

import java.util.Arrays;
import java.util.Objects;

final class FunctionTestSupport {
    private FunctionTestSupport() {
    }

    static Fn function(FnOverload overload, PType... parameterTypes) {
        return Objects.requireNonNull(overload.getInstance(parameterTypes));
    }

    static Fn function(FnOverload overload) {
        PType[] parameterTypes = overload.getSignature().getParameterTypes().toArray(new PType[0]);
        return function(overload, parameterTypes);
    }

    static FnOverload overloadFor(Iterable<FnOverload> overloads, PType... parameterTypes) {
        for (FnOverload overload : overloads) {
            if (overload.getSignature().getParameterTypes().equals(Arrays.asList(parameterTypes))) {
                return overload;
            }
        }
        throw new AssertionError("No overload for " + Arrays.toString(parameterTypes));
    }
}
