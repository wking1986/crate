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

package io.crate.planner;

import io.crate.analyze.*;
import io.crate.analyze.relations.AnalyzedRelation;
import io.crate.analyze.relations.AnalyzedRelationVisitor;
import io.crate.analyze.relations.QueriedDocTable;
import io.crate.analyze.relations.QueriedRelation;
import io.crate.analyze.symbol.SelectSymbol;
import io.crate.exceptions.ValidationException;
import io.crate.exceptions.VersionInvalidException;
import io.crate.planner.consumer.ConsumerContext;
import io.crate.planner.consumer.ConsumingPlanner;
import io.crate.planner.consumer.ESGetStatementPlanner;
import io.crate.planner.fetch.FetchPushDown;
import io.crate.planner.node.ExecutionPhases;
import io.crate.planner.node.dql.QueryThenFetch;
import io.crate.sql.tree.QualifiedName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class SelectStatementPlanner {

    private final Visitor visitor;

    SelectStatementPlanner(ConsumingPlanner consumingPlanner) {
        visitor = new Visitor(consumingPlanner);
    }

    public Plan plan(SelectAnalyzedStatement statement, Planner.Context context) {
        return visitor.process(statement.relation(), context);
    }

    private static Plan subPlan(AnalyzedRelation rel, Planner.Context context) {
        ConsumerContext consumerContext = new ConsumerContext(rel, context);
        Plan subPlan = context.planSubRelation(rel, consumerContext);
        assert subPlan != null : "plan must not be null";
        ValidationException validationException = consumerContext.validationException();
        if (validationException != null) {
            throw validationException;
        }
        return subPlan;
    }

    private static class Visitor extends AnalyzedRelationVisitor<Planner.Context, Plan> {

        private final ConsumingPlanner consumingPlanner;

        public Visitor(ConsumingPlanner consumingPlanner) {
            this.consumingPlanner = consumingPlanner;
        }

        private Plan invokeConsumingPlanner(AnalyzedRelation relation, Planner.Context context) {
            return Merge.mergeToHandler(consumingPlanner.plan(relation, context), context);
        }

        @Override
        protected Plan visitAnalyzedRelation(AnalyzedRelation relation, Planner.Context context) {
            return invokeConsumingPlanner(relation, context);
        }

        @Override
        public Plan visitQueriedTable(QueriedTable table, Planner.Context context) {
            SubqueryPlanner subqueryPlanner = new SubqueryPlanner(context);
            QuerySpec querySpec = table.querySpec();
            context.applySoftLimit(querySpec);
            Map<Plan, SelectSymbol> subQueries = subqueryPlanner.planSubQueries(querySpec);
            Plan plan = super.visitQueriedTable(table, context);
            return MultiPhasePlan.createIfNeeded(plan, subQueries);
        }

        @Override
        public Plan visitQueriedDocTable(QueriedDocTable table, Planner.Context context) {
            QuerySpec querySpec = table.querySpec();
            context.applySoftLimit(querySpec);
            SubqueryPlanner subqueryPlanner = new SubqueryPlanner(context);
            Map<Plan, SelectSymbol> subQueries = subqueryPlanner.planSubQueries(querySpec);
            if (querySpec.hasAggregates() || querySpec.groupBy().isPresent()) {
                Plan subPlan = invokeConsumingPlanner(table, context);
                return MultiPhasePlan.createIfNeeded(subPlan, subQueries);
            }
            if (querySpec.where().docKeys().isPresent() && !table.tableRelation().tableInfo().isAlias()) {
                return MultiPhasePlan.createIfNeeded(ESGetStatementPlanner.convert(table, context), subQueries);
            }
            if (querySpec.where().hasVersions()) {
                throw new VersionInvalidException();
            }
            Limits limits = context.getLimits(querySpec);
            if (querySpec.where().noMatch() || (querySpec.limit().isPresent() && limits.finalLimit() == 0)) {
                return new NoopPlan(context.jobId());
            }
            table.tableRelation().validateOrderBy(querySpec.orderBy());

            FetchPushDown.Builder fetchPhaseBuilder = FetchPushDown.pushDown(table);
            if (fetchPhaseBuilder == null) {
                // no fetch required
                return MultiPhasePlan.createIfNeeded(invokeConsumingPlanner(table, context), subQueries);
            }
            AnalyzedRelation subRelation = fetchPhaseBuilder.replacedRelation();
            Plan plannedSubQuery = subPlan(subRelation, context);
            assert plannedSubQuery != null : "consumingPlanner should have created a subPlan";
            plannedSubQuery = Merge.mergeToHandler(plannedSubQuery, context);

            // fetch phase and projection can only be build after the sub-plan was processed (shards/readers allocated)
            FetchPushDown.PhaseAndProjection fetchPhaseAndProjection = fetchPhaseBuilder.build(context);
            plannedSubQuery.addProjection(
                fetchPhaseAndProjection.projection,
                null,
                null,
                fetchPhaseAndProjection.projection.outputs().size(),
                null);

            return MultiPhasePlan.createIfNeeded(
                new QueryThenFetch(plannedSubQuery, fetchPhaseAndProjection.phase),
                subQueries);
        }

        @Override
        public Plan visitMultiSourceSelect(MultiSourceSelect mss, Planner.Context context) {
            QuerySpec querySpec = mss.querySpec();
            context.applySoftLimit(querySpec);
            if (querySpec.where().noMatch() && !querySpec.hasAggregates()) {
                return new NoopPlan(context.jobId());
            }
            SubqueryPlanner subqueryPlanner = new SubqueryPlanner(context);
            Map<Plan, SelectSymbol> subQueries = subqueryPlanner.planSubQueries(querySpec);
            for (Map.Entry<QualifiedName, RelationSource> entry : mss.sources().entrySet()) {
                subQueries.putAll(subqueryPlanner.planSubQueries(entry.getValue().querySpec()));
            }
            if (mss.canBeFetched().isEmpty()) {
                return MultiPhasePlan.createIfNeeded(invokeConsumingPlanner(mss, context), subQueries);
            }
            FetchPushDown.Builder fetchPhaseBuilder = FetchPushDown.pushDown(mss);
            assert fetchPhaseBuilder != null : "expecting fetchPhaseBuilder not to be null";

            // plan sub relation as if root so that it adds a mergePhase
            Plan plannedSubQuery = invokeConsumingPlanner(mss, context);
            assert plannedSubQuery != null : "consumingPlanner should have created a subPlan";
            // NestedLoopConsumer can return NoopPlannedAnalyzedRelation if its left or right plan
            // is noop. E.g. it is the case with creating NestedLoopConsumer for empty partitioned tables.
            if (plannedSubQuery instanceof NoopPlan) {
                return plannedSubQuery;
            }

            FetchPushDown.PhaseAndProjection fetchPhaseAndProjection = fetchPhaseBuilder.build(context);
            plannedSubQuery.addProjection(
                fetchPhaseAndProjection.projection,
                null,
                null,
                fetchPhaseAndProjection.projection.outputs().size(),
                null);
            QueryThenFetch queryThenFetch = new QueryThenFetch(plannedSubQuery, fetchPhaseAndProjection.phase);
            return MultiPhasePlan.createIfNeeded(Merge.mergeToHandler(queryThenFetch, context), subQueries);
        }

        @Override
        public Plan visitTwoRelationsUnion(TwoRelationsUnion twoRelationsUnion, Planner.Context context) {
            // Currently we only support UNION ALL so it's ok to flatten the union pairs
            UnionFlatteningVisitorContext visitorContext = new UnionFlatteningVisitorContext();
            UnionFlatteningVisitor.INSTANCE.process(twoRelationsUnion, visitorContext);
            UnionSelect unionSelect = new UnionSelect(visitorContext.relations, twoRelationsUnion.querySpec());

            FetchPushDown.Builder fetchPhaseBuilder = FetchPushDown.pushDown(unionSelect);
            ConsumerContext consumerContext = new ConsumerContext(unionSelect, context);
            Plan plannedSubQuery = consumingPlanner.plan(unionSelect, consumerContext);
            if (plannedSubQuery instanceof NoopPlan) {
                return plannedSubQuery;
            }
            assert plannedSubQuery != null : "consumingPlanner should have created a subPlan";
            assert ExecutionPhases.executesOnHandler(context.handlerNode(), plannedSubQuery.resultDescription().nodeIds())
                : "subQuery must not have a distributed result";

            if (fetchPhaseBuilder == null) {
                // no fetch required
                return plannedSubQuery;
            }

            FetchPushDown.PhaseAndProjection fetchPhaseAndProjection = fetchPhaseBuilder.build(context);
            plannedSubQuery.addProjection(fetchPhaseAndProjection.projection, null, null, null, null);
            return new QueryThenFetch(plannedSubQuery, fetchPhaseAndProjection.phase);
        }

        @Override
        public Plan visitQueriedSelectRelation(QueriedSelectRelation relation, Planner.Context context) {
            throw new UnsupportedOperationException("complex sub selects are not supported");
        }
    }

    private static class UnionFlatteningVisitor extends AnalyzedRelationVisitor<UnionFlatteningVisitorContext, Void> {

        private static final UnionFlatteningVisitor INSTANCE = new UnionFlatteningVisitor();

        @Override
        public Void visitQueriedTable(QueriedTable relation, UnionFlatteningVisitorContext context) {
            context.relations.add(0, relation);
            return null;
        }

        @Override
        public Void visitQueriedDocTable(QueriedDocTable relation, UnionFlatteningVisitorContext context) {
            context.relations.add(0, relation);
            return null;
        }

        @Override
        public Void visitMultiSourceSelect(MultiSourceSelect relation, UnionFlatteningVisitorContext context) {
            context.relations.add(0, relation);
            return null;
        }

        public Void visitQueriedSelectRelation(QueriedSelectRelation relation, UnionFlatteningVisitorContext context) {
            context.relations.add(0, relation);
            return null;
        }

        public Void visitTwoRelationsUnion(TwoRelationsUnion twoRelationsUnion, UnionFlatteningVisitorContext context) {
            process(twoRelationsUnion.first(), context);
            process(twoRelationsUnion.second(), context);
            return null;
        }
    }

    private static class UnionFlatteningVisitorContext {
        private final List<QueriedRelation> relations = new ArrayList<>();
    }
}
