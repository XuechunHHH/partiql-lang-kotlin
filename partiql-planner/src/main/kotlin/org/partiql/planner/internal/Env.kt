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

package org.partiql.planner.internal

import org.partiql.planner.internal.casts.CastTable
import org.partiql.planner.internal.ir.Ref
import org.partiql.planner.internal.ir.Rel
import org.partiql.planner.internal.ir.Rex
import org.partiql.planner.internal.ir.SetQuantifier
import org.partiql.planner.internal.ir.refAgg
import org.partiql.planner.internal.ir.refFn
import org.partiql.planner.internal.ir.relOpAggregateCallResolved
import org.partiql.planner.internal.ir.relOpWindowWindowFunction
import org.partiql.planner.internal.ir.rex
import org.partiql.planner.internal.ir.rexOpCallDynamicCandidate
import org.partiql.planner.internal.ir.rexOpCastResolved
import org.partiql.planner.internal.ir.rexOpVarGlobal
import org.partiql.planner.internal.typer.CompilerType
import org.partiql.planner.internal.typer.PlanTyper.Companion.toCType
import org.partiql.planner.internal.typer.Scope.Companion.toPath
import org.partiql.planner.internal.window.WindowFunctionSignatureProvider
import org.partiql.spi.catalog.Catalog
import org.partiql.spi.catalog.Catalogs
import org.partiql.spi.catalog.Identifier
import org.partiql.spi.catalog.Name
import org.partiql.spi.catalog.Session
import org.partiql.spi.errors.PErrorListener
import org.partiql.spi.function.Agg
import org.partiql.spi.function.AggOverload
import org.partiql.spi.types.PType

/**
 * [Env] is similar to the database type environment from the PartiQL Specification. This includes resolution of
 * database binding values and scoped functions.
 *
 * See TypeEnv for the variables type environment.
 *
 * TODO: function resolution between scalar functions and aggregations.
 *
 * @property session
 */
internal class Env(private val session: Session, internal val listener: PErrorListener) {

    /**
     * Catalogs provider.
     */
    private val catalogs: Catalogs = session.getCatalogs()

    /**
     * Current [Catalog] implementation; error if missing from the [Catalogs] provider.
     */
    private val default: Catalog = catalogs.getCatalog(session.getCatalog()) ?: error("Default catalog does not exist")

    private val routines = RoutineResolver(session)

    fun selectRoutine(identifier: Identifier, shape: RoutineCallShape): RoutineSelection =
        routines.select(identifier, shape)

    fun selectRoutine(identifier: Identifier, arity: Int): RoutineSelection =
        selectRoutine(identifier, RoutineCallShape.from(identifier, arity))

    fun resolveWindowFn(name: String, args: List<Rex>, isIgnoreNulls: Boolean = false): Rel.Op.Window.WindowFunction? {
        val sig = WindowFunctionSignatureProvider.get(name, args, isIgnoreNulls) ?: return null
        val paramTypes = sig.parameterTypes.map { it.toCType() }
        return relOpWindowWindowFunction(sig.name, args, sig.isIgnoreNulls, paramTypes, sig.returnType.toCType())
    }

    fun resolveFn(selection: RoutineSelection.Scalar, args: List<Rex>): Rex? {
        val match = selection.match
        val resolved = FnResolver.resolve(match.overloads, args.map { it.type }) ?: return null
        return when (resolved) {
            is FnMatch.Dynamic -> {
                val candidates = resolved.candidates.map {
                    rexOpCallDynamicCandidate(
                        fn = refFn(
                            catalog = match.catalog,
                            name = match.canonicalName,
                            signature = it,
                            routine = match.routine,
                        ),
                        coercions = emptyList(),
                    )
                }
                Rex(CompilerType(PType.dynamic()), Rex.Op.Call.Dynamic(args, candidates))
            }

            is FnMatch.Static -> {
                val coercions = args.mapIndexed { i, arg ->
                    when (val cast = resolved.mapping[i]) {
                        null -> arg
                        else -> Rex(cast.target, Rex.Op.Cast.Resolved(cast, arg))
                    }
                }
                Rex(
                    CompilerType(PType.dynamic()),
                    Rex.Op.Call.Static(resolved.function, coercions, match.routine),
                )
            }
        }
    }

    fun resolveAgg(
        selection: RoutineSelection.Aggregate,
        setQuantifier: SetQuantifier,
        args: List<Rex>,
    ): Rel.Op.Aggregate.Call.Resolved? {
        val selected = selection.match
        val match = match(selected.overloads, args.map { it.type }) ?: return null
        val agg = match.first
        val mapping = match.second
        val ref = refAgg(
            catalog = selected.catalog,
            name = selected.canonicalName,
            signature = agg,
            routine = selected.routine,
        )
        val coercions = args.mapIndexed { i, arg ->
            when (val cast = mapping[i]) {
                null -> arg
                else -> rex(cast.target, rexOpCastResolved(cast, arg))
            }
        }
        return relOpAggregateCallResolved(ref, setQuantifier, coercions)
    }

    /**
     * Catalog lookup needs to search (3x) to table schema-qualified and catalog-qualified use-cases.
     *
     *  1. Lookup in current catalog and namespace.
     *  2. Lookup as a schema-qualified identifier.
     *  3. Lookup as a catalog-qualified identifier.
     */
    fun resolveTable(identifier: Identifier): Rex? {

        // 1. Search in current catalog and namespace
        var catalog = default
        var path = resolve(identifier)
        var table = catalog.resolveTable(session, path)?.let { catalog.getTable(session, it) }

        // 2. Lookup as a schema-qualified identifier.
        if (table == null && identifier.hasQualifier()) {
            path = identifier
            table = catalog.resolveTable(session, path)?.let { catalog.getTable(session, it) }
        }

        // 3. Lookup as a catalog-qualified identifier
        if (table == null && identifier.hasQualifier()) {
            val parts = identifier.getParts()
            val head = parts.first()
            val tail = parts.drop(1)
            catalog = catalogs.getCatalog(head.getText(), ignoreCase = head.isRegular()) ?: return null
            path = Identifier.of(tail)
            table = catalog.resolveTable(session, path)?.let { catalog.getTable(session, it) }
        }

        // !! NOT FOUND !!
        if (table == null) {
            return null
        }

        // Make a reference and return a global variable expression.
        val refCatalog = catalog.getName()
        val refName = table.getName()
        val refType = CompilerType(table.getSchema())
        val ref = Ref.Obj(refCatalog, refName, refType, table)

        // Convert any remaining identifier parts to a path expression
        val root = Rex(ref.type, rexOpVarGlobal(ref))
        val tail = calculateMatched(path, refName)
        return if (tail.isEmpty()) root else root.toPath(tail)
    }

    fun resolveCast(input: Rex, target: CompilerType): Rex.Op.Cast.Resolved? {
        val operand = input.type
        val cast = CastTable.partiql.get(operand, target) ?: return null
        return rexOpCastResolved(cast, input)
    }

    // -----------------------
    //  Helpers
    // -----------------------

    // Helpers

    /**
     * Prepends the current session namespace to the identifier; named like Path.resolve() from java io.
     */
    private fun resolve(identifier: Identifier): Identifier {
        val namespace = session.getNamespace()
        return if (namespace.isEmpty()) {
            // no need to create another object
            identifier
        } else {
            // prepend the namespace
            namespace.asIdentifier().append(identifier)
        }
    }

    /**
     * Returns a list of the unmatched parts of the identifier given the matched name.
     */
    private fun calculateMatched(path: Identifier, name: Name): List<Identifier.Simple> {
        val lhs = name.toList()
        val rhs = path.toList()
        return rhs.takeLast(rhs.size - lhs.size)
    }

    private fun match(candidates: List<AggOverload>, args: List<PType>): Pair<Agg, Array<Ref.Cast?>>? {
        // 1. Check for an exact match
        for (candidate in candidates) {
            val instance = candidate.getInstance(args.toTypedArray()) ?: continue
            if (instance.matches(args)) {
                return instance to arrayOfNulls(args.size)
            }
        }
        // 2. Look for best match.
        var match: Pair<Agg, Array<Ref.Cast?>>? = null
        for (candidate in candidates) {
            val instance = candidate.getInstance(args.toTypedArray()) ?: continue
            val m = instance.match(args) ?: continue
            // TODO AggMatch comparison
            // if (match != null && m.exact < match.exact) {
            //     // already had a better match.
            //     continue
            // }
            match = m
        }
        // 3. Return best match or null
        return match
    }

    /**
     * Check if this function accepts the exact input argument types. Assume same arity.
     */
    private fun Agg.matches(args: List<PType>): Boolean {
        val instance = this
        val parameters = instance.signature.parameters
        for (i in args.indices) {
            val a = args[i]
            val p = parameters[i]
            if (p.type.code() != a.code()) return false
        }
        return true
    }

    /**
     * Attempt to match arguments to the parameters; return the implicit casts if necessary.
     *
     * @param args
     * @return
     */
    private fun Agg.match(args: List<PType>): Pair<Agg, Array<Ref.Cast?>>? {
        val instance = this
        val parameters = instance.signature.parameters
        val mapping = arrayOfNulls<Ref.Cast?>(args.size)
        for (i in args.indices) {
            val a = args[i]
            val p = parameters[i]
            when {
                // Exact match!
                p.type.code() == a.code() -> continue
                // If the argument is dynamic, we still need to cast the argument at runtime to the aggregate function's expected type
                a.code() == PType.DYNAMIC -> mapping[i] =
                    Ref.Cast(a.toCType(), p.type.toCType(), Ref.Cast.Safety.COERCION, true)
                // If the parameter allows all types, continue.
                p.type.code() == PType.DYNAMIC -> continue
                // Check the Type Families (of argument and param) and coerce argument if possible; if not, this fails.
                else -> mapping[i] = coercion(a, p.type) ?: return null
            }
        }
        return instance to mapping
    }

    private fun coercion(arg: PType, target: PType): Ref.Cast? {
        return when (CoercionFamily.canCoerce(arg, target)) {
            true -> Ref.Cast(arg.toCType(), target.toCType(), Ref.Cast.Safety.COERCION, true)
            false -> return null
        }
    }
}
