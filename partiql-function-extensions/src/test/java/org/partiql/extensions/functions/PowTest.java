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

import org.junit.jupiter.api.Test;
import org.partiql.extensions.functions.custom.math.Pow;
import org.partiql.spi.function.Fn;
import org.partiql.spi.function.FnOverload;
import org.partiql.spi.types.PType;
import org.partiql.spi.value.Datum;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PowTest {

    @Test
    public void evaluatesAllOverloads() {
        assertPow(PType.tinyint(), Datum.tinyint((byte) 2), Datum.tinyint((byte) 3), 8.0);
        assertPow(PType.smallint(), Datum.smallint((short) 3), Datum.smallint((short) 4), 81.0);
        assertPow(PType.integer(), Datum.integer(5), Datum.integer(3), 125.0);
        assertPow(PType.bigint(), Datum.bigint(2L), Datum.bigint(10L), 1024.0);
        assertPow(PType.real(), Datum.real(9.0F), Datum.real(0.5F), 3.0);
        assertPow(
            PType.doublePrecision(),
            Datum.doublePrecision(2.5),
            Datum.doublePrecision(2.0),
            6.25
        );
    }

    private static void assertPow(
        PType argumentType,
        Datum base,
        Datum exponent,
        double expected
    ) {
        FnOverload overload = FunctionTestSupport.overloadFor(
            Pow.overloads(),
            argumentType,
            argumentType
        );
        Fn function = FunctionTestSupport.function(overload, argumentType, argumentType);

        Datum result = function.invoke(new Datum[] {base, exponent});

        assertEquals(PType.doublePrecision(), result.getType());
        assertEquals(expected, result.getDouble());
    }
}
