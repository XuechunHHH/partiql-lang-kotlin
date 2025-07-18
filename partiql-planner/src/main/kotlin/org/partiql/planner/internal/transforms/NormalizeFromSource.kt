/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates.  All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at:
 *
 *      http://aws.amazon.com/apache2.0/
 *
 *  or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
 *  language governing permissions and limitations under the License.
 */

package org.partiql.planner.internal.transforms

import org.partiql.ast.Ast.fromExpr
import org.partiql.ast.Ast.fromJoin
import org.partiql.ast.AstNode
import org.partiql.ast.AstRewriter
import org.partiql.ast.FromExpr
import org.partiql.ast.FromJoin
import org.partiql.ast.FromTableRef
import org.partiql.ast.FromType
import org.partiql.ast.QueryBody
import org.partiql.ast.Statement
import org.partiql.ast.expr.Expr
import org.partiql.planner.internal.util.BinderUtils.toBinder

/**
 * Assign aliases to any FROM source which does not have one.
 */
internal object NormalizeFromSource : AstPass {
    override fun apply(statement: Statement): Statement = statement.accept(Visitor, IntCounter(0)) as Statement

    internal class IntCounter(var counter: Int = 0) {
        fun next(): Int = counter++
    }

    private object Visitor : AstRewriter<IntCounter>() {
        // Each SFW starts the ctx count again.
        override fun visitQueryBodySFW(node: QueryBody.SFW, ctx: IntCounter): AstNode =
            super.visitQueryBodySFW(node, IntCounter(0))

        override fun visitFromJoin(node: FromJoin, ctx: IntCounter): FromJoin {
            val lhs = node.lhs.accept(this, ctx) as FromTableRef
            val rhs = node.rhs.accept(this, ctx) as FromTableRef
            val condition = node.condition?.accept(this, ctx) as Expr?
            return if (lhs !== node.lhs || rhs !== node.rhs || condition !== node.condition) {
                fromJoin(lhs, rhs, node.joinType, condition)
            } else {
                node
            }
        }

        override fun visitFromExpr(node: FromExpr, ctx: IntCounter): FromExpr {
            val expr = node.expr.accept(this, ctx) as Expr
            var asAlias = node.asAlias
            var atAlias = node.atAlias
            // derive AS alias
            if (asAlias == null) {
                asAlias = expr.toBinder(ctx.next())
            }
            // derive AT binder
            if (atAlias == null && node.fromType == FromType.UNPIVOT()) {
                atAlias = expr.toBinder(ctx.next())
            }
            return if (expr !== node.expr || asAlias !== node.asAlias || atAlias !== node.atAlias) {
                fromExpr(expr = expr, fromType = node.fromType, asAlias = asAlias, atAlias = atAlias)
            } else {
                node
            }
        }
    }
}
