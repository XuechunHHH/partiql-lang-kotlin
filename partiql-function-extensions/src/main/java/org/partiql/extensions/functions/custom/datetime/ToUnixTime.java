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

package org.partiql.extensions.functions.custom.datetime;

import org.partiql.spi.function.FnOverload;
import org.partiql.spi.types.PType;
import org.partiql.spi.value.Datum;

import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

/**
 * {@code to_unixtime} overloads that interpret timestamps as UTC and return epoch seconds.
 */
public final class ToUnixTime {
    private static final List<FnOverload> OVERLOADS = Collections.singletonList(
        new FnOverload.Builder("to_unixtime")
            .addParameters(PType.timestamp())
            .returns(PType.bigint())
            .isNullCall(true)
            .body(args -> Datum.bigint(
                args[0].getLocalDateTime().toEpochSecond(ZoneOffset.UTC)
            ))
            .build()
    );

    private ToUnixTime() {
    }

    /**
     * Returns the immutable {@code to_unixtime} overloads.
     *
     * @return {@code to_unixtime} overloads
     */
    public static List<FnOverload> overloads() {
        return OVERLOADS;
    }
}
