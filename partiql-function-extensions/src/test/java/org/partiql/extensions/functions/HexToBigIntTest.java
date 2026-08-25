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
import org.partiql.extensions.functions.custom.conversion.HexToBigInt;
import org.partiql.spi.function.Fn;
import org.partiql.spi.types.PType;
import org.partiql.spi.value.Datum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class HexToBigIntTest {
    private static final Fn FUNCTION = FunctionTestSupport.function(HexToBigInt.overloads().get(0));

    @Test
    public void convertsHexadecimalStringsToBigints() {
        assertConverts("00C10300", 12_649_216L);
        assertConverts("abcdef", 11_259_375L);
        assertConverts("FFFFFFFF", 4_294_967_295L);
        assertConverts("7fffffffffffffff", Long.MAX_VALUE);
        assertConverts("-8000000000000000", Long.MIN_VALUE);
    }

    @Test
    public void rejectsMalformedOrOutOfRangeInput() {
        assertInvalid("");
        assertInvalid("0x10");
        assertInvalid("not-hex");
        assertInvalid("8000000000000000");
    }

    private static void assertConverts(String hex, long expected) {
        Datum result = FUNCTION.invoke(new Datum[] {Datum.string(hex)});

        assertEquals(PType.bigint(), result.getType());
        assertEquals(expected, result.getLong());
    }

    private static void assertInvalid(String hex) {
        assertThrows(
            NumberFormatException.class,
            () -> FUNCTION.invoke(new Datum[] {Datum.string(hex)})
        );
    }
}
