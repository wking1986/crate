/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.analyze.relations;

import com.google.common.base.Optional;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import io.crate.analyze.*;
import io.crate.analyze.symbol.*;
import io.crate.metadata.*;
import io.crate.operation.operator.AndOperator;
import io.crate.planner.Limits;
import io.crate.sql.tree.QualifiedName;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The RelationNormalizer tries to merge the tree of relations in a QueriedSelectRelation into a single QueriedRelation.
 * The merge occurs from the top level to the deepest one. For each level, it verifies if the query is mergeable with
 * the next relation and proceed with the merge if positive. When it is not, the partially merged tree is returned.
 */
final class RelationNormalizer {

    private RelationNormalizer() {
    }

    public static AnalyzedRelation normalize(AnalyzedRelation relation,
                                             Functions functions,
                                             TransactionContext transactionContext) {
        Context context = new Context(functions, relation.fields(), transactionContext);
        return NormalizerVisitor.normalize(SubselectRewriter.rewrite(relation, context), context);
    }

    private static Map<QualifiedName, AnalyzedRelation> mapSourceRelations(MultiSourceSelect multiSourceSelect) {
        return Maps.transformValues(multiSourceSelect.sources(), new com.google.common.base.Function<RelationSource, AnalyzedRelation>() {
            @Override
            public AnalyzedRelation apply(RelationSource input) {
                return input.relation();
            }
        });
    }

    private static QuerySpec mergeQuerySpec(QuerySpec childQSpec, @Nullable QuerySpec parentQSpec) {
        if (parentQSpec == null) {
            return childQSpec;
        }

        return new QuerySpec()
            .outputs(parentQSpec.outputs())
            .where(mergeWhere(childQSpec.where(), parentQSpec.where()))
            .orderBy(tryReplace(childQSpec.orderBy(), parentQSpec.orderBy()))
            .offset(Limits.mergeAdd(childQSpec.offset(), parentQSpec.offset()))
            .limit(Limits.mergeMin(childQSpec.limit(), parentQSpec.limit()))
            .groupBy(pushGroupBy(childQSpec.groupBy(), parentQSpec.groupBy()))
            .having(pushHaving(childQSpec.having(), parentQSpec.having()))
            .hasAggregates(childQSpec.hasAggregates() || parentQSpec.hasAggregates());
    }

    private static WhereClause mergeWhere(WhereClause where1, WhereClause where2) {
        if (!where1.hasQuery() || where1 == WhereClause.MATCH_ALL) {
            return where2;
        } else if (!where2.hasQuery() || where2 == WhereClause.MATCH_ALL) {
            return where1;
        }

        return new WhereClause(AndOperator.join(ImmutableList.of(where2.query(), where1.query())));
    }

    /**
     * "Merge" OrderBy of child & parent relations.
     * <p/>
     * examples:
     * <pre>
     *      childOrderBy: col1, col2
     *      parentOrderBy: col2, col3, col4
     *
     *      merged OrderBy returned: col2, col3, col4
     * </pre>
     * <p/>
     * <pre>
     *      childOrderBy: col1, col2
     *      parentOrderBy:
     *
     *      merged OrderBy returned: col1, col2
     * </pre>
     *
     * @param childOrderBy  The OrderBy of the relation being processed
     * @param parentOrderBy The OrderBy of the parent relation (outer select,  union, etc.)
     * @return The merged orderBy
     */
    @Nullable
    private static OrderBy tryReplace(Optional<OrderBy> childOrderBy, Optional<OrderBy> parentOrderBy) {
        if (parentOrderBy.isPresent()) {
            return parentOrderBy.get();
        }
        return childOrderBy.orNull();
    }

    @Nullable
    private static List<Symbol> pushGroupBy(Optional<List<Symbol>> childGroupBy, Optional<List<Symbol>> parentGroupBy) {
        assert !(childGroupBy.isPresent() && parentGroupBy.isPresent()) :
            "Cannot merge 'group by' if exists in both parent and child relations";
        return childGroupBy.or(parentGroupBy).orNull();
    }

    @Nullable
    private static HavingClause pushHaving(Optional<HavingClause> childHaving, Optional<HavingClause> parentHaving) {
        assert !(childHaving.isPresent() && parentHaving.isPresent()) :
            "Cannot merge 'having' if exists in both parent and child relations";
        return childHaving.or(parentHaving).orNull();
    }

    private static boolean canBeMerged(QuerySpec childQuerySpec, QuerySpec parentQuerySpec) {
        if (parentQuerySpec == null) {
            return true;
        }

        boolean hasAggregations = (parentQuerySpec.hasAggregates() || parentQuerySpec.groupBy().isPresent()) &&
                                  (childQuerySpec.hasAggregates() || childQuerySpec.groupBy().isPresent() ||
                                   childQuerySpec.orderBy().isPresent());

        boolean notMergeableOrderBy = childQuerySpec.orderBy().isPresent() && parentQuerySpec.orderBy().isPresent()
                                      && !childQuerySpec.orderBy().equals(parentQuerySpec.orderBy())
                                      && (childQuerySpec.limit().isPresent() || childQuerySpec.offset().isPresent());

        return !hasAggregations && !notMergeableOrderBy &&
               (!parentQuerySpec.where().hasQuery() || parentQuerySpec.where() == WhereClause.MATCH_ALL ||
                !Aggregations.containsAggregation(parentQuerySpec.where().query()));
    }

    private static class Context {

        private final List<Field> fields;
        private final TransactionContext transactionContext;
        private final EvaluatingNormalizer normalizer;
        private final Functions functions;

        private QuerySpec currentParentQSpec;

        public Context(Functions functions,
                       List<Field> fields,
                       TransactionContext transactionContext) {
            this.functions = functions;
            this.normalizer = EvaluatingNormalizer.functionOnlyNormalizer(functions, ReplaceMode.COPY);
            this.fields = fields;
            this.transactionContext = transactionContext;
        }

        public Collection<? extends Path> paths() {
            return Collections2.transform(fields, Field::path);
        }
    }

    /**
     * Function to replace Fields with the Reference from the output of the relation the Field is pointing to.
     * E.g.
     *
     * <pre>
     * select t.x from (select x from t1) t
     *         |         |
     *       Field       \                  ____ Reference that is used as replacement.
     *          relation: t                /
     *                    +-- QS.outputs: [x]
     *                                     ^
     *                                     |
     *          index: 0 ------------------+
     *
     * </pre>
     */
    private static class FieldReferenceResolver extends ReplacingSymbolVisitor<Void>
        implements com.google.common.base.Function<Symbol, Symbol>{

        public static final FieldReferenceResolver INSTANCE = new FieldReferenceResolver(ReplaceMode.MUTATE);
        private static final FieldRelationVisitor<Symbol> FIELD_RELATION_VISITOR = new FieldRelationVisitor<>(INSTANCE);

        private FieldReferenceResolver(ReplaceMode mode) {
            super(mode);
        }

        @Override
        public Symbol visitField(Field field, Void context) {
            Symbol output = FIELD_RELATION_VISITOR.process(field.relation(), field);
            return output != null ? output : field;
        }

        @Nullable
        @Override
        public Symbol apply(@Nullable Symbol input) {
            if (input == null) {
                return null;
            }
            return process(input, null);
        }
    }

    /**
     * Visits an output symbol in a queried relation using the provided field index.
     */
    private static class FieldRelationVisitor<R> extends AnalyzedRelationVisitor<Field, R> {

        private final SymbolVisitor<?, R> symbolVisitor;

        FieldRelationVisitor(SymbolVisitor<?, R> symbolVisitor) {
            this.symbolVisitor = symbolVisitor;
        }

        @Override
        protected R visitAnalyzedRelation(AnalyzedRelation relation, Field context) {
            return null;
        }

        @Override
        public R visitQueriedTable(QueriedTable relation, Field field) {
            return visitQueriedRelation(relation, field);
        }

        @Override
        public R visitQueriedDocTable(QueriedDocTable relation, Field field) {
            return visitQueriedRelation(relation, field);
        }

        @Override
        public R visitMultiSourceSelect(MultiSourceSelect relation, Field field) {
            return visitQueriedRelation(relation, field);
        }

        @Override
        public R visitQueriedSelectRelation(QueriedSelectRelation relation, Field field) {
            return visitQueriedRelation(relation, field);
        }

        private R visitQueriedRelation(QueriedRelation relation, Field field) {
            Symbol output = relation.querySpec().outputs().get(field.index());
            return symbolVisitor.process(output, null);
        }
    }

    private static class SubselectRewriter extends AnalyzedRelationVisitor<RelationNormalizer.Context, AnalyzedRelation> {

        private static final SubselectRewriter SUBSELECT_REWRITER = new SubselectRewriter();

        private SubselectRewriter() {
        }

        public static AnalyzedRelation rewrite(AnalyzedRelation relation, Context context) {
            return SUBSELECT_REWRITER.process(relation, context);
        }

        @Override
        protected AnalyzedRelation visitAnalyzedRelation(AnalyzedRelation relation, Context context) {
            return relation;
        }

        @Override
        public AnalyzedRelation visitQueriedSelectRelation(QueriedSelectRelation relation, Context context) {
            QuerySpec querySpec = relation.querySpec();
            // Try to merge with parent query spec
            if (canBeMerged(querySpec, context.currentParentQSpec)) {
                querySpec = mergeQuerySpec(querySpec, context.currentParentQSpec);
            }

            // Try to push down to the child
            context.currentParentQSpec = querySpec;
            AnalyzedRelation processedChildRelation = process(relation.subRelation(), context);

            // If cannot be pushed down replace qSpec with possibly merged qSpec from context
            if (processedChildRelation == null) {
                relation.querySpec(querySpec);
                return relation;
            } else { // If can be pushed down eliminate relation by return the processed child
                return processedChildRelation;
            }
        }

        @Override
        public AnalyzedRelation visitQueriedTable(QueriedTable table, Context context) {
            if (context.currentParentQSpec == null) {
                return table;
            }
            QuerySpec querySpec = table.querySpec();
            context.currentParentQSpec.replace(FieldReferenceResolver.INSTANCE);
            if (!canBeMerged(querySpec, context.currentParentQSpec)) {
                return null;
            }

            querySpec = mergeQuerySpec(querySpec, context.currentParentQSpec);
            return new QueriedTable(table.tableRelation(), context.paths(), querySpec);
        }

        @Override
        public AnalyzedRelation visitQueriedDocTable(QueriedDocTable table, Context context) {
            if (context.currentParentQSpec == null) {
                return table;
            }
            QuerySpec querySpec = table.querySpec();
            context.currentParentQSpec.replace(FieldReferenceResolver.INSTANCE);
            if (!canBeMerged(querySpec, context.currentParentQSpec)) {
                return null;
            }

            querySpec = mergeQuerySpec(querySpec, context.currentParentQSpec);
            return new QueriedDocTable(table.tableRelation(), context.paths(), querySpec);
        }

        @Override
        public AnalyzedRelation visitMultiSourceSelect(MultiSourceSelect multiSourceSelect, Context context) {
            if (context.currentParentQSpec == null) {
                return multiSourceSelect;
            }
            context.currentParentQSpec.replace(FieldReferenceResolver.INSTANCE);
            QuerySpec querySpec = multiSourceSelect.querySpec();
            if (!canBeMerged(querySpec, context.currentParentQSpec)) {
                return null;
            }

            querySpec = mergeQuerySpec(querySpec, context.currentParentQSpec);
            // must create a new MultiSourceSelect because paths and query spec changed
            return new MultiSourceSelect(mapSourceRelations(multiSourceSelect),
                multiSourceSelect.outputSymbols(),
                context.paths(),
                querySpec,
                multiSourceSelect.joinPairs());
        }


        @Override
        public AnalyzedRelation visitTwoRelationsUnion(TwoRelationsUnion twoTableUnion, Context context) {
            if (context.currentParentQSpec != null) {
                throw new UnsupportedOperationException("UNION as a sub query is not supported");
            }

            QuerySpec querySpec = twoTableUnion.querySpec();
            querySpec.replace(FieldReferenceResolver.INSTANCE);

            // Build PushDown limit to the relations of the union since as the union tree is built
            // by the parser the limit exists only in the top level TwoRelationsUnion
            Optional<Symbol> pushDownLimit = Limits.mergeAdd(querySpec.limit(), querySpec.offset());

            // Build PushDown orderBy to the relations of the union for performance optimization
            Optional<OrderBy> pushDownOrderBy = querySpec.orderBy();
            // Convert orderBy symbols referring to the 1st relation of the union to InputColumns
            // as rootOrderBy must apply to corresponding columns of all relations
            if (pushDownOrderBy.isPresent()) {
                final List<Symbol> outputs = querySpec.outputs();
                pushDownOrderBy.get().replace(new com.google.common.base.Function<Symbol, Symbol>() {
                    @Nullable
                    @Override
                    public Symbol apply(@Nullable Symbol symbol) {
                        return InputColumn.fromSymbol(symbol, outputs);
                    }
                });
            }

            // Push Down orderBy and limit
            UnionPushDownContext unionPushDownContext = new UnionPushDownContext(pushDownLimit, pushDownOrderBy);
            UnionPushDownVisitor.INSTANCE.process(twoTableUnion.first(), unionPushDownContext);
            UnionPushDownVisitor.INSTANCE.process(twoTableUnion.second(), unionPushDownContext);

            twoTableUnion.first((QueriedRelation) process(twoTableUnion.first(), context));
            context.currentParentQSpec = null; // Reset the querySpec of the context to avoid mixing it with the second relation
            twoTableUnion.second((QueriedRelation) process(twoTableUnion.second(), context));
            return twoTableUnion;
        }

        private static class UnionPushDownContext {

            private Optional<Symbol> limit;
            private Optional<OrderBy> orderBy;

            private UnionPushDownContext(Optional<Symbol> limit, Optional<OrderBy> orderBy) {
                this.limit = limit;
                this.orderBy = orderBy;
            }
        }

        private static class UnionPushDownVisitor extends AnalyzedRelationVisitor<UnionPushDownContext, Void> {

            private static final UnionPushDownVisitor INSTANCE = new UnionPushDownVisitor();

            private UnionPushDownVisitor() {
            }

            @Override
            protected Void visitAnalyzedRelation(AnalyzedRelation relation, UnionPushDownContext context) {
                return null;
            }

            @Override
            public Void visitQueriedSelectRelation(QueriedSelectRelation relation, UnionPushDownContext context) {
                return process(relation.subRelation(), context);
            }

            @Override
            public Void visitQueriedTable(QueriedTable table, UnionPushDownContext context) {
                QuerySpec querySpec = table.querySpec();
                pushDownLimit(querySpec, context.limit);
                pushDownOrderBy(querySpec, context.orderBy);
                return null;
            }

            @Override
            public Void visitQueriedDocTable(QueriedDocTable table, UnionPushDownContext context) {
                QuerySpec querySpec = table.querySpec();
                pushDownLimit(querySpec, context.limit);
                pushDownOrderBy(querySpec, context.orderBy);
                return null;
            }

            @Override
            public Void visitMultiSourceSelect(MultiSourceSelect multiSourceSelect, UnionPushDownContext context) {
                // Doesn't make sense to push down the orderBy to joins
                pushDownLimit(multiSourceSelect.querySpec(), context.limit);
                return null;
            }

            @Override
            public Void visitTwoRelationsUnion(TwoRelationsUnion twoRelationsUnion, UnionPushDownContext context) {
                process(twoRelationsUnion.first(), context);
                process(twoRelationsUnion.second(), context);
                return null;
            }

            private void pushDownOrderBy(QuerySpec querySpec, Optional<OrderBy> orderBy) {
                querySpec.orderBy(tryReplace(querySpec.orderBy(),
                    // The pushedDown OrderBy is passed as InputColumn and must
                    // be rewritten to their output symbol counterparts
                    rewritePushedDownOrderBy(orderBy, querySpec.outputs())));
            }

            private void pushDownLimit(QuerySpec querySpec, Optional<Symbol> limit) {
                querySpec.limit(Limits.mergeMin(querySpec.limit(), limit));
            }

            private static Optional<OrderBy> rewritePushedDownOrderBy(Optional<OrderBy> orderBy,
                                                                      final List<Symbol> outputs) {
                if (!orderBy.isPresent()) {
                    return orderBy;
                }

                return Optional.of(orderBy.get().copyAndReplace(new com.google.common.base.Function<Symbol, Symbol>() {
                    @Nullable
                    @Override
                    public Symbol apply(@Nullable Symbol symbol) {
                        if (symbol instanceof InputColumn) {
                            InputColumn inputColumn = (InputColumn) symbol;
                            return outputs.get(inputColumn.index());
                        }
                        return symbol;
                    }
                }));
            }
        }
    }

    private static class NormalizerVisitor extends AnalyzedRelationVisitor<RelationNormalizer.Context, AnalyzedRelation> {

        private static final NormalizerVisitor NORMALIZER = new NormalizerVisitor();

        private NormalizerVisitor() {
        }

        public static AnalyzedRelation normalize(AnalyzedRelation relation, Context context) {
            return NORMALIZER.process(relation, context);
        }

        @Override
        protected AnalyzedRelation visitAnalyzedRelation(AnalyzedRelation relation, Context context) {
            return relation;
        }

        @Override
        public AnalyzedRelation visitQueriedSelectRelation(QueriedSelectRelation relation, Context context) {
            relation.subRelation((QueriedRelation) process(relation.subRelation(), context));
            return relation;
        }

        @Override
        public AnalyzedRelation visitQueriedTable(QueriedTable table, Context context) {
            table.normalize(context.functions, context.transactionContext);
            return table;
        }

        @Override
        public AnalyzedRelation visitQueriedDocTable(QueriedDocTable table, Context context) {
            table.normalize(context.functions, context.transactionContext);
            table.analyzeWhereClause(context.functions, context.transactionContext);
            return table;
        }

        @Override
        public AnalyzedRelation visitMultiSourceSelect(MultiSourceSelect multiSourceSelect, Context context) {
            QuerySpec querySpec = multiSourceSelect.querySpec();
            querySpec.normalize(context.normalizer, context.transactionContext);
            // must create a new MultiSourceSelect because paths and query spec changed
            multiSourceSelect = new MultiSourceSelect(mapSourceRelations(multiSourceSelect),
                multiSourceSelect.outputSymbols(), context.paths(), querySpec,
                multiSourceSelect.joinPairs());
            multiSourceSelect.pushDownQuerySpecs();
            return multiSourceSelect;
        }

        @Override
        public AnalyzedRelation visitTwoRelationsUnion(TwoRelationsUnion twoTableUnion, Context context) {
            twoTableUnion.first((QueriedRelation) process(twoTableUnion.first(), context));
            twoTableUnion.second((QueriedRelation) process(twoTableUnion.second(), context));
            return twoTableUnion;
        }
    }
}
