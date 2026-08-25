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
import org.partiql.extensions.functions.custom.datetime.ToUnixTime;
import org.partiql.spi.function.Fn;
import org.partiql.spi.types.PType;
import org.partiql.spi.value.Datum;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ToUnixTimeTest {
    private static final Fn FUNCTION = FunctionTestSupport.function(ToUnixTime.overloads().get(0));

    @Test
    public void treatsTimestampAsUtcAndReturnsEpochSeconds() {
        assertEpochSeconds(LocalDateTime.of(1970, 1, 1, 0, 0), 0L);
        assertEpochSeconds(LocalDateTime.of(2000, 1, 1, 0, 0), 946_684_800L);
        assertEpochSeconds(LocalDateTime.of(1969, 12, 31, 23, 59, 59), -1L);
    }

    @Test
    public void ignoresFractionalSeconds() {
        assertEpochSeconds(
            LocalDateTime.of(1970, 1, 1, 0, 0, 0, 999_999_000),
            0L
        );
    }

    private static void assertEpochSeconds(LocalDateTime timestamp, long expected) {
        Datum result = FUNCTION.invoke(new Datum[] {Datum.timestamp(timestamp, 6)});

        assertEquals(PType.bigint(), result.getType());
        assertEquals(expected, result.getLong());
    }
}
