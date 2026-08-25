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

package org.partiql.extensions.functions.custom.conversion;

import org.partiql.spi.function.FnOverload;
import org.partiql.spi.types.PType;
import org.partiql.spi.value.Datum;

import java.util.Collections;
import java.util.List;

/**
 * Converts a hexadecimal string to a signed 64-bit integer.
 */
public final class HexToBigInt {
    private static final int RADIX = 16;
    private static final List<FnOverload> OVERLOADS = Collections.singletonList(
        new FnOverload.Builder("hex_to_bigint")
            .addParameters(PType.string())
            .returns(PType.bigint())
            .isNullCall(true)
            .body(args -> Datum.bigint(Long.parseLong(args[0].getString(), RADIX)))
            .build()
    );

    private HexToBigInt() {
    }

    /**
     * Returns the immutable {@code hex_to_bigint} overloads.
     *
     * @return {@code hex_to_bigint} overloads
     */
    public static List<FnOverload> overloads() {
        return OVERLOADS;
    }
}
