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
import org.partiql.extensions.functions.custom.collection.Contains;
import org.partiql.spi.function.Fn;
import org.partiql.spi.value.Datum;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContainsTest {
    private static final Fn FUNCTION = FunctionTestSupport.function(Contains.overloads().get(0));

    @Test
    public void returnsTrueWhenStringIsPresent() {
        Datum array = Datum.array(Arrays.asList(
            Datum.string("alpha"),
            Datum.string("beta"),
            Datum.string("beta")
        ));

        assertTrue(invoke(array, "beta"));
    }

    @Test
    public void returnsFalseWhenStringIsAbsent() {
        Datum array = Datum.array(Arrays.asList(
            Datum.string("alpha"),
            Datum.string("beta")
        ));

        assertFalse(invoke(array, "gamma"));
    }

    @Test
    public void returnsFalseForEmptyArray() {
        assertFalse(invoke(Datum.array(Collections.emptyList()), "alpha"));
    }

    @Test
    public void usesPartiqlComparisonForElements() {
        Datum array = Datum.array(Arrays.asList(
            Datum.nullValue(),
            Datum.character("alpha", 5)
        ));

        assertTrue(invoke(array, "alpha"));
    }

    private static boolean invoke(Datum array, String expected) {
        return FUNCTION.invoke(new Datum[] {array, Datum.string(expected)}).getBoolean();
    }
}
