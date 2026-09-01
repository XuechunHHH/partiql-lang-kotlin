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

package org.partiql.extensions.functions.custom.math;

import org.partiql.spi.function.FnOverload;
import org.partiql.spi.types.PType;
import org.partiql.spi.value.Datum;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Numeric {@code pow} overloads that raise a base to an exponent and return a double.
 */
public final class Pow {
    private static final List<FnOverload> OVERLOADS = Collections.unmodifiableList(Arrays.asList(
        overload(PType.tinyint(), Datum::getByte),
        overload(PType.smallint(), Datum::getShort),
        overload(PType.integer(), Datum::getInt),
        overload(PType.bigint(), Datum::getLong),
        overload(PType.real(), Datum::getFloat),
        overload(PType.doublePrecision(), Datum::getDouble)
    ));

    private Pow() {
    }

    /**
     * Returns the immutable numeric overloads.
     *
     * @return {@code pow} overloads
     */
    public static List<FnOverload> overloads() {
        return OVERLOADS;
    }

    private static FnOverload overload(PType argumentType, ToDoubleFunction<Datum> toDouble) {
        return new FnOverload.Builder("pow")
            .addParameters(argumentType, argumentType)
            .returns(PType.doublePrecision())
            .isNullCall(true)
            .body(args -> Datum.doublePrecision(
                Math.pow(
                    toDouble.applyAsDouble(args[0]),
                    toDouble.applyAsDouble(args[1])
                )
            ))
            .build();
    }
}
