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
import org.partiql.extensions.functions.compat.datetime.DateAdd;
import org.partiql.spi.function.Fn;
import org.partiql.spi.types.PType;
import org.partiql.spi.value.Datum;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class DateAddTest {
    private static final Fn FUNCTION = FunctionTestSupport.function(DateAdd.overloads().get(0));
    private static final LocalDateTime BASE = LocalDateTime.of(
        2020,
        1,
        15,
        10,
        20,
        30,
        123_000_000
    );

    @Test
    public void supportsAllDateParts() {
        assertDateAdd("year", 2, BASE.plusYears(2));
        assertDateAdd("month", 2, BASE.plusMonths(2));
        assertDateAdd("day", 2, BASE.plusDays(2));
        assertDateAdd("hour", 2, BASE.plusHours(2));
        assertDateAdd("minute", 2, BASE.plusMinutes(2));
        assertDateAdd("second", 2, BASE.plusSeconds(2));
    }

    @Test
    public void acceptsCaseInsensitiveDatePartsAndNegativeQuantities() {
        assertDateAdd("DAY", -30, BASE.minusDays(30));
    }

    @Test
    public void usesCalendarArithmeticAtMonthEnd() {
        LocalDateTime leapYearMonthEnd = LocalDateTime.of(2020, 1, 31, 10, 0);
        assertDateAdd(
            "month",
            1,
            leapYearMonthEnd,
            LocalDateTime.of(2020, 2, 29, 10, 0)
        );
    }

    @Test
    public void rejectsUnsupportedDatePart() {
        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> invoke("millisecond", 1, BASE)
        );

        assertEquals("Invalid datetime part for date_add: millisecond", error.getMessage());
    }

    private static void assertDateAdd(String part, int quantity, LocalDateTime expected) {
        assertDateAdd(part, quantity, BASE, expected);
    }

    private static void assertDateAdd(
        String part,
        int quantity,
        LocalDateTime input,
        LocalDateTime expected
    ) {
        Datum result = invoke(part, quantity, input);

        assertEquals(PType.timestamp(3), result.getType());
        assertEquals(expected, result.getLocalDateTime());
    }

    private static Datum invoke(String part, int quantity, LocalDateTime timestamp) {
        return FUNCTION.invoke(new Datum[] {
            Datum.string(part),
            Datum.integer(quantity),
            Datum.timestamp(timestamp, 3)
        });
    }
}
