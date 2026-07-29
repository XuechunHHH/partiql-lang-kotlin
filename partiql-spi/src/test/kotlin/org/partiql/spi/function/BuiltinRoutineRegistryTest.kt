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

package org.partiql.spi.function

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.partiql.spi.types.PType
import org.partiql.spi.utils.FunctionUtils
import org.partiql.spi.value.Datum

class BuiltinRoutineRegistryTest {

    @Test
    fun rejectsUnmappedHiddenRoutine() {
        val hiddenName = FunctionUtils.hide("unmapped")
        val overload = FnOverload.Builder(hiddenName)
            .returns(PType.dynamic())
            .body { Datum.missing() }
            .build()

        assertThrows<IllegalStateException> {
            BuiltinRoutineRegistry(
                functions = mapOf(hiddenName to listOf(overload)),
                aggregations = emptyMap(),
                hiddenRoutineNames = emptyMap(),
            )
        }
    }

    @Test
    fun enforcesLowercaseOnlyForBuiltinIds() {
        val overload = FnOverload.Builder("MixedCase")
            .returns(PType.dynamic())
            .body { Datum.missing() }
            .build()

        assertThrows<IllegalStateException> {
            BuiltinRoutineRegistry(
                functions = mapOf("MixedCase" to listOf(overload)),
                aggregations = emptyMap(),
                hiddenRoutineNames = emptyMap(),
            )
        }
    }
}
