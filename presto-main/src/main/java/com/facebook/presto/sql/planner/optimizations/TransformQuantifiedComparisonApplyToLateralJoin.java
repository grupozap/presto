/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner.optimizations;

import com.facebook.presto.Session;
import com.facebook.presto.execution.warnings.WarningCollector;
import com.facebook.presto.metadata.FunctionManager;
import com.facebook.presto.spi.function.StandardFunctionResolution;
import com.facebook.presto.spi.plan.PlanNodeIdAllocator;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.spi.type.BigintType;
import com.facebook.presto.spi.type.BooleanType;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.ExpressionUtils;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.SymbolAllocator;
import com.facebook.presto.sql.planner.TypeProvider;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.AggregationNode.Aggregation;
import com.facebook.presto.sql.planner.plan.ApplyNode;
import com.facebook.presto.sql.planner.plan.Assignments;
import com.facebook.presto.sql.planner.plan.LateralJoinNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.planner.plan.SimplePlanRewriter;
import com.facebook.presto.sql.relational.FunctionResolution;
import com.facebook.presto.sql.tree.BooleanLiteral;
import com.facebook.presto.sql.tree.Cast;
import com.facebook.presto.sql.tree.ComparisonExpression;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.GenericLiteral;
import com.facebook.presto.sql.tree.NullLiteral;
import com.facebook.presto.sql.tree.QuantifiedComparisonExpression;
import com.facebook.presto.sql.tree.SearchedCaseExpression;
import com.facebook.presto.sql.tree.SimpleCaseExpression;
import com.facebook.presto.sql.tree.SymbolReference;
import com.facebook.presto.sql.tree.WhenClause;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static com.facebook.presto.sql.ExpressionUtils.combineConjuncts;
import static com.facebook.presto.sql.planner.plan.AggregationNode.globalAggregation;
import static com.facebook.presto.sql.planner.plan.SimplePlanRewriter.rewriteWith;
import static com.facebook.presto.sql.tree.BooleanLiteral.FALSE_LITERAL;
import static com.facebook.presto.sql.tree.BooleanLiteral.TRUE_LITERAL;
import static com.facebook.presto.sql.tree.ComparisonExpression.Operator.EQUAL;
import static com.facebook.presto.sql.tree.ComparisonExpression.Operator.GREATER_THAN;
import static com.facebook.presto.sql.tree.ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL;
import static com.facebook.presto.sql.tree.ComparisonExpression.Operator.LESS_THAN;
import static com.facebook.presto.sql.tree.ComparisonExpression.Operator.LESS_THAN_OR_EQUAL;
import static com.facebook.presto.sql.tree.ComparisonExpression.Operator.NOT_EQUAL;
import static com.facebook.presto.sql.tree.QuantifiedComparisonExpression.Quantifier.ALL;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

public class TransformQuantifiedComparisonApplyToLateralJoin
        implements PlanOptimizer
{
    private final StandardFunctionResolution functionResolution;

    public TransformQuantifiedComparisonApplyToLateralJoin(FunctionManager functionManager)
    {
        requireNonNull(functionManager, "functionManager is null");
        this.functionResolution = new FunctionResolution(functionManager);
    }

    @Override
    public PlanNode optimize(PlanNode plan, Session session, TypeProvider types, SymbolAllocator symbolAllocator, PlanNodeIdAllocator idAllocator, WarningCollector warningCollector)
    {
        return rewriteWith(new Rewriter(functionResolution, idAllocator, symbolAllocator), plan, null);
    }

    private static class Rewriter
            extends SimplePlanRewriter<PlanNode>
    {
        private final StandardFunctionResolution functionResolution;
        private final PlanNodeIdAllocator idAllocator;
        private final SymbolAllocator symbolAllocator;

        public Rewriter(StandardFunctionResolution functionResolution, PlanNodeIdAllocator idAllocator, SymbolAllocator symbolAllocator)
        {
            this.functionResolution = requireNonNull(functionResolution, "functionResolution is null");
            this.idAllocator = requireNonNull(idAllocator, "idAllocator is null");
            this.symbolAllocator = requireNonNull(symbolAllocator, "symbolAllocator is null");
        }

        @Override
        public PlanNode visitApply(ApplyNode node, RewriteContext<PlanNode> context)
        {
            if (node.getSubqueryAssignments().size() != 1) {
                return context.defaultRewrite(node);
            }

            Expression expression = getOnlyElement(node.getSubqueryAssignments().getExpressions());
            if (!(expression instanceof QuantifiedComparisonExpression)) {
                return context.defaultRewrite(node);
            }

            QuantifiedComparisonExpression quantifiedComparison = (QuantifiedComparisonExpression) expression;

            return rewriteQuantifiedApplyNode(node, quantifiedComparison, context);
        }

        private PlanNode rewriteQuantifiedApplyNode(ApplyNode node, QuantifiedComparisonExpression quantifiedComparison, RewriteContext<PlanNode> context)
        {
            PlanNode subqueryPlan = context.rewrite(node.getSubquery());

            VariableReferenceExpression outputColumn = getOnlyElement(subqueryPlan.getOutputVariables());
            Type outputColumnType = outputColumn.getType();
            checkState(outputColumnType.isOrderable(), "Subquery result type must be orderable");

            VariableReferenceExpression minValue = symbolAllocator.newVariable("min", outputColumnType);
            VariableReferenceExpression maxValue = symbolAllocator.newVariable("max", outputColumnType);
            VariableReferenceExpression countAllValue = symbolAllocator.newVariable("count_all", BigintType.BIGINT);
            VariableReferenceExpression countNonNullValue = symbolAllocator.newVariable("count_non_null", BigintType.BIGINT);

            List<Expression> outputColumnReferences = ImmutableList.of(new SymbolReference(outputColumn.getName()));

            subqueryPlan = new AggregationNode(
                    idAllocator.getNextId(),
                    subqueryPlan,
                    ImmutableMap.of(
                            minValue, new Aggregation(
                                    functionResolution.minFunction(outputColumnType),
                                    outputColumnReferences,
                                    Optional.empty(),
                                    Optional.empty(),
                                    false,
                                    Optional.empty()),
                            maxValue, new Aggregation(
                                    functionResolution.maxFunction(outputColumnType),
                                    outputColumnReferences,
                                    Optional.empty(),
                                    Optional.empty(),
                                    false,
                                    Optional.empty()),
                            countAllValue, new Aggregation(
                                    functionResolution.countFunction(),
                                    emptyList(),
                                    Optional.empty(),
                                    Optional.empty(),
                                    false,
                                    Optional.empty()),
                            countNonNullValue, new Aggregation(
                                    functionResolution.countFunction(outputColumnType),
                                    outputColumnReferences,
                                    Optional.empty(),
                                    Optional.empty(),
                                    false,
                                    Optional.empty())),
                    globalAggregation(),
                    ImmutableList.of(),
                    AggregationNode.Step.SINGLE,
                    Optional.empty(),
                    Optional.empty());

            PlanNode lateralJoinNode = new LateralJoinNode(
                    node.getId(),
                    context.rewrite(node.getInput()),
                    subqueryPlan,
                    node.getCorrelation(),
                    LateralJoinNode.Type.INNER,
                    node.getOriginSubqueryError());

            Expression valueComparedToSubquery = rewriteUsingBounds(
                    quantifiedComparison,
                    new Symbol(minValue.getName()),
                    new Symbol(maxValue.getName()),
                    new Symbol(countAllValue.getName()),
                    new Symbol(countNonNullValue.getName()));

            VariableReferenceExpression quantifiedComparisonVariable = getOnlyElement(node.getSubqueryAssignments().getVariables());

            return projectExpressions(lateralJoinNode, Assignments.of(quantifiedComparisonVariable, valueComparedToSubquery));
        }

        public Expression rewriteUsingBounds(QuantifiedComparisonExpression quantifiedComparison, Symbol minValue, Symbol maxValue, Symbol countAllValue, Symbol countNonNullValue)
        {
            BooleanLiteral emptySetResult = quantifiedComparison.getQuantifier().equals(ALL) ? TRUE_LITERAL : FALSE_LITERAL;
            Function<List<Expression>, Expression> quantifier = quantifiedComparison.getQuantifier().equals(ALL) ?
                    ExpressionUtils::combineConjuncts : ExpressionUtils::combineDisjuncts;
            Expression comparisonWithExtremeValue = getBoundComparisons(quantifiedComparison, minValue, maxValue);

            return new SimpleCaseExpression(
                    countAllValue.toSymbolReference(),
                    ImmutableList.of(new WhenClause(
                            new GenericLiteral("bigint", "0"),
                            emptySetResult)),
                    Optional.of(quantifier.apply(ImmutableList.of(
                            comparisonWithExtremeValue,
                            new SearchedCaseExpression(
                                    ImmutableList.of(
                                            new WhenClause(
                                                    new ComparisonExpression(NOT_EQUAL, countAllValue.toSymbolReference(), countNonNullValue.toSymbolReference()),
                                                    new Cast(new NullLiteral(), BooleanType.BOOLEAN.toString()))),
                                    Optional.of(emptySetResult))))));
        }

        private Expression getBoundComparisons(QuantifiedComparisonExpression quantifiedComparison, Symbol minValue, Symbol maxValue)
        {
            if (quantifiedComparison.getOperator() == EQUAL && quantifiedComparison.getQuantifier() == ALL) {
                // A = ALL B <=> min B = max B && A = min B
                return combineConjuncts(
                        new ComparisonExpression(EQUAL, minValue.toSymbolReference(), maxValue.toSymbolReference()),
                        new ComparisonExpression(EQUAL, quantifiedComparison.getValue(), maxValue.toSymbolReference()));
            }

            if (EnumSet.of(LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL).contains(quantifiedComparison.getOperator())) {
                // A < ALL B <=> A < min B
                // A > ALL B <=> A > max B
                // A < ANY B <=> A < max B
                // A > ANY B <=> A > min B
                Symbol boundValue = shouldCompareValueWithLowerBound(quantifiedComparison) ? minValue : maxValue;
                return new ComparisonExpression(quantifiedComparison.getOperator(), quantifiedComparison.getValue(), boundValue.toSymbolReference());
            }
            throw new IllegalArgumentException("Unsupported quantified comparison: " + quantifiedComparison);
        }

        private static boolean shouldCompareValueWithLowerBound(QuantifiedComparisonExpression quantifiedComparison)
        {
            switch (quantifiedComparison.getQuantifier()) {
                case ALL:
                    switch (quantifiedComparison.getOperator()) {
                        case LESS_THAN:
                        case LESS_THAN_OR_EQUAL:
                            return true;
                        case GREATER_THAN:
                        case GREATER_THAN_OR_EQUAL:
                            return false;
                    }
                    break;
                case ANY:
                case SOME:
                    switch (quantifiedComparison.getOperator()) {
                        case LESS_THAN:
                        case LESS_THAN_OR_EQUAL:
                            return false;
                        case GREATER_THAN:
                        case GREATER_THAN_OR_EQUAL:
                            return true;
                    }
                    break;
            }
            throw new IllegalArgumentException("Unexpected quantifier: " + quantifiedComparison.getQuantifier());
        }

        private ProjectNode projectExpressions(PlanNode input, Assignments subqueryAssignments)
        {
            Assignments assignments = Assignments.builder()
                    .putIdentities(input.getOutputVariables())
                    .putAll(subqueryAssignments)
                    .build();
            return new ProjectNode(
                    idAllocator.getNextId(),
                    input,
                    assignments);
        }
    }
}
