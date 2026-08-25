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
import org.partiql.extensions.functions.compat.datetime.UtcNow;
import org.partiql.spi.function.Fn;
import org.partiql.spi.types.PType;
import org.partiql.spi.value.Datum;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UtcNowTest {
    private static final Fn FUNCTION = FunctionTestSupport.function(UtcNow.overloads().get(0));

    @Test
    public void returnsCurrentUtcTimestampWithoutTimezone() {
        Instant lowerBound = Instant.now().minusSeconds(1);
        Datum result = FUNCTION.invoke(new Datum[0]);
        Instant upperBound = Instant.now().plusSeconds(1);
        Instant actual = result.getLocalDateTime().toInstant(ZoneOffset.UTC);

        assertEquals(PType.timestamp(6), result.getType());
        assertFalse(actual.isBefore(lowerBound));
        assertFalse(actual.isAfter(upperBound));
        assertEquals(0, result.getLocalDateTime().getNano() % 1_000);
        assertTrue(FUNCTION.getSignature().isNullCall());
    }
}
