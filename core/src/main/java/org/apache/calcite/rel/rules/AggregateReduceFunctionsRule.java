/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.rel.rules;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.fun.SqlAvgAggFunction;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.fun.SqlSumAggFunction;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.util.CompositeList;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Planner rule that reduces aggregate functions in
 * {@link org.apache.calcite.rel.core.Aggregate}s to simpler forms.
 *
 * <p>Rewrites:
 * <ul>
 *
 * <li>AVG(x) &rarr; SUM(x) / COUNT(x)
 *
 * <li>STDDEV_POP(x) &rarr; SQRT(
 *     (SUM(x * x) - SUM(x) * SUM(x) / COUNT(x))
 *    / COUNT(x))
 *
 * <li>STDDEV_SAMP(x) &rarr; SQRT(
 *     (SUM(x * x) - SUM(x) * SUM(x) / COUNT(x))
 *     / CASE COUNT(x) WHEN 1 THEN NULL ELSE COUNT(x) - 1 END)
 *
 * <li>VAR_POP(x) &rarr; (SUM(x * x) - SUM(x) * SUM(x) / COUNT(x))
 *     / COUNT(x)
 *
 * <li>VAR_SAMP(x) &rarr; (SUM(x * x) - SUM(x) * SUM(x) / COUNT(x))
 *        / CASE COUNT(x) WHEN 1 THEN NULL ELSE COUNT(x) - 1 END
 * </ul>
 *
 * <p>Since many of these rewrites introduce multiple occurrences of simpler
 * forms like {@code COUNT(x)}, the rule gathers common sub-expressions as it
 * goes.
 */
public class AggregateReduceFunctionsRule extends RelOptRule {
  //~ Static fields/initializers ---------------------------------------------

  /** The singleton. */
  public static final AggregateReduceFunctionsRule INSTANCE =
      new AggregateReduceFunctionsRule(operand(LogicalAggregate.class, any()));

  //~ Constructors -----------------------------------------------------------

  protected AggregateReduceFunctionsRule(RelOptRuleOperand operand) {
    super(operand);
  }

  //~ Methods ----------------------------------------------------------------

  @Override public boolean matches(RelOptRuleCall call) {
    if (!super.matches(call)) {
      return false;
    }
    Aggregate oldAggRel = (Aggregate) call.rels[0];
    return containsAvgStddevVarCall(oldAggRel.getAggCallList());
  }

  public void onMatch(RelOptRuleCall ruleCall) {
    Aggregate oldAggRel = (Aggregate) ruleCall.rels[0];
    reduceAggs(ruleCall, oldAggRel);
  }

  /**
   * Returns whether any of the aggregates are calls to AVG, STDDEV_*, VAR_*.
   *
   * @param aggCallList List of aggregate calls
   */
  private boolean containsAvgStddevVarCall(List<AggregateCall> aggCallList) {
    for (AggregateCall call : aggCallList) {
      if (call.getAggregation() instanceof SqlAvgAggFunction
          || call.getAggregation() instanceof SqlSumAggFunction) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reduces all calls to AVG, STDDEV_POP, STDDEV_SAMP, VAR_POP, VAR_SAMP in
   * the aggregates list to.
   *
   * <p>It handles newly generated common subexpressions since this was done
   * at the sql2rel stage.
   */
  private void reduceAggs(
      RelOptRuleCall ruleCall,
      Aggregate oldAggRel) {
    RexBuilder rexBuilder = oldAggRel.getCluster().getRexBuilder();

    List<AggregateCall> oldCalls = oldAggRel.getAggCallList();
    final int nGroups = oldAggRel.getGroupCount();

    List<AggregateCall> newCalls = new ArrayList<AggregateCall>();
    Map<AggregateCall, RexNode> aggCallMapping =
        new HashMap<AggregateCall, RexNode>();

    List<RexNode> projList = new ArrayList<RexNode>();

    // pass through group key
    for (int i = 0; i < nGroups; ++i) {
      projList.add(
          rexBuilder.makeInputRef(
              getFieldType(oldAggRel, i),
              i));
    }

    // List of input expressions. If a particular aggregate needs more, it
    // will add an expression to the end, and we will create an extra
    // project.
    RelNode input = oldAggRel.getInput();
    List<RexNode> inputExprs = new ArrayList<RexNode>();
    for (RelDataTypeField field : input.getRowType().getFieldList()) {
      inputExprs.add(
          rexBuilder.makeInputRef(
              field.getType(), inputExprs.size()));
    }

    // create new agg function calls and rest of project list together
    for (AggregateCall oldCall : oldCalls) {
      projList.add(
          reduceAgg(
              oldAggRel, oldCall, newCalls, aggCallMapping, inputExprs));
    }

    final int extraArgCount =
        inputExprs.size() - input.getRowType().getFieldCount();
    if (extraArgCount > 0) {
      input =
          RelOptUtil.createProject(
              input,
              inputExprs,
              CompositeList.of(
                  input.getRowType().getFieldNames(),
                  Collections.<String>nCopies(
                      extraArgCount,
                      null)));
    }
    Aggregate newAggRel =
        newAggregateRel(
            oldAggRel, input, newCalls);

    RelNode projectRel =
        RelOptUtil.createProject(
            newAggRel,
            projList,
            oldAggRel.getRowType().getFieldNames());

    ruleCall.transformTo(projectRel);
    // If we old AggRel(SUM(0)) transforms to new AggRel($SUM0($0)) both will
    // have the same cost, but we prefer new. Before we set the importance of
    // old to 0, we were getting different results between JDK 1.7 and 1.8
    // because of some arbitrary orderings of rels within an equivalence set.
    ruleCall.getPlanner().setImportance(oldAggRel, 0d);
  }

  private RexNode reduceAgg(
      Aggregate oldAggRel,
      AggregateCall oldCall,
      List<AggregateCall> newCalls,
      Map<AggregateCall, RexNode> aggCallMapping,
      List<RexNode> inputExprs) {
    if (oldCall.getAggregation() instanceof SqlSumAggFunction) {
      // replace original SUM(x) with
      // case COUNT(x) when 0 then null else SUM0(x) end
      return reduceSum(oldAggRel, oldCall, newCalls, aggCallMapping);
    }
    if (oldCall.getAggregation() instanceof SqlAvgAggFunction) {
      final SqlAvgAggFunction.Subtype subtype =
          ((SqlAvgAggFunction) oldCall.getAggregation()).getSubtype();
      switch (subtype) {
      case AVG:
        // replace original AVG(x) with SUM(x) / COUNT(x)
        return reduceAvg(
            oldAggRel, oldCall, newCalls, aggCallMapping);
      case STDDEV_POP:
        // replace original STDDEV_POP(x) with
        //   SQRT(
        //     (SUM(x * x) - SUM(x) * SUM(x) / COUNT(x))
        //     / COUNT(x))
        return reduceStddev(
            oldAggRel, oldCall, true, true, newCalls, aggCallMapping,
            inputExprs);
      case STDDEV_SAMP:
        // replace original STDDEV_POP(x) with
        //   SQRT(
        //     (SUM(x * x) - SUM(x) * SUM(x) / COUNT(x))
        //     / CASE COUNT(x) WHEN 1 THEN NULL ELSE COUNT(x) - 1 END)
        return reduceStddev(
            oldAggRel, oldCall, false, true, newCalls, aggCallMapping,
            inputExprs);
      case VAR_POP:
        // replace original VAR_POP(x) with
        //     (SUM(x * x) - SUM(x) * SUM(x) / COUNT(x))
        //     / COUNT(x)
        return reduceStddev(
            oldAggRel, oldCall, true, false, newCalls, aggCallMapping,
            inputExprs);
      case VAR_SAMP:
        // replace original VAR_POP(x) with
        //     (SUM(x * x) - SUM(x) * SUM(x) / COUNT(x))
        //     / CASE COUNT(x) WHEN 1 THEN NULL ELSE COUNT(x) - 1 END
        return reduceStddev(
            oldAggRel, oldCall, false, false, newCalls, aggCallMapping,
            inputExprs);
      default:
        throw Util.unexpected(subtype);
      }
    } else {
      // anything else:  preserve original call
      RexBuilder rexBuilder = oldAggRel.getCluster().getRexBuilder();
      final int nGroups = oldAggRel.getGroupCount();
      List<RelDataType> oldArgTypes = SqlTypeUtil
          .projectTypes(oldAggRel.getRowType(), oldCall.getArgList());
      return rexBuilder.addAggCall(
          oldCall,
          nGroups,
          newCalls,
          aggCallMapping,
          oldArgTypes);
    }
  }

  private RexNode reduceAvg(
      Aggregate oldAggRel,
      AggregateCall oldCall,
      List<AggregateCall> newCalls,
      Map<AggregateCall, RexNode> aggCallMapping) {
    final int nGroups = oldAggRel.getGroupCount();
    RelDataTypeFactory typeFactory =
        oldAggRel.getCluster().getTypeFactory();
    RexBuilder rexBuilder = oldAggRel.getCluster().getRexBuilder();
    int iAvgInput = oldCall.getArgList().get(0);
    RelDataType avgInputType =
        getFieldType(
            oldAggRel.getInput(),
            iAvgInput);
    RelDataType sumType =
        typeFactory.createTypeWithNullability(
            avgInputType,
            avgInputType.isNullable() || nGroups == 0);
    SqlAggFunction sumAgg = new SqlSumAggFunction(sumType);
    AggregateCall sumCall =
        new AggregateCall(
            sumAgg,
            oldCall.isDistinct(),
            oldCall.getArgList(),
            sumType,
            null);
    AggregateCall countCall =
        AggregateCall.create(
            SqlStdOperatorTable.COUNT,
            oldCall.isDistinct(),
            oldCall.getArgList(),
            oldAggRel.getGroupCount(),
            oldAggRel.getInput(),
            null,
            null);

    // NOTE:  these references are with respect to the output
    // of newAggRel
    RexNode numeratorRef =
        rexBuilder.addAggCall(
            sumCall,
            nGroups,
            newCalls,
            aggCallMapping,
            ImmutableList.of(avgInputType));
    RexNode denominatorRef =
        rexBuilder.addAggCall(
            countCall,
            nGroups,
            newCalls,
            aggCallMapping,
            ImmutableList.of(avgInputType));
    final RexNode divideRef =
        rexBuilder.makeCall(
            SqlStdOperatorTable.DIVIDE,
            numeratorRef,
            denominatorRef);
    return rexBuilder.makeCast(
        oldCall.getType(), divideRef);
  }

  private RexNode reduceSum(
      Aggregate oldAggRel,
      AggregateCall oldCall,
      List<AggregateCall> newCalls,
      Map<AggregateCall, RexNode> aggCallMapping) {
    final int nGroups = oldAggRel.getGroupCount();
    RelDataTypeFactory typeFactory =
        oldAggRel.getCluster().getTypeFactory();
    RexBuilder rexBuilder = oldAggRel.getCluster().getRexBuilder();
    int arg = oldCall.getArgList().get(0);
    RelDataType argType =
        getFieldType(
            oldAggRel.getInput(),
            arg);
    final RelDataType sumType =
        typeFactory.createTypeWithNullability(
            argType, argType.isNullable());
    final AggregateCall sumZeroCall =
        new AggregateCall(
            SqlStdOperatorTable.SUM0,
            oldCall.isDistinct(),
            oldCall.getArgList(),
            sumType,
            null);
    final AggregateCall countCall =
        AggregateCall.create(
            SqlStdOperatorTable.COUNT,
            oldCall.isDistinct(),
            oldCall.getArgList(),
            oldAggRel.getGroupCount(),
            oldAggRel,
            null,
            null);

    // NOTE:  these references are with respect to the output
    // of newAggRel
    RexNode sumZeroRef =
        rexBuilder.addAggCall(
            sumZeroCall,
            nGroups,
            newCalls,
            aggCallMapping,
            ImmutableList.of(argType));
    if (!oldCall.getType().isNullable()) {
      // If SUM(x) is not nullable, the validator must have determined that
      // nulls are impossible (because the group is never empty and x is never
      // null). Therefore we translate to SUM0(x).
      return sumZeroRef;
    }
    RexNode countRef =
        rexBuilder.addAggCall(
            countCall,
            nGroups,
            newCalls,
            aggCallMapping,
            ImmutableList.of(argType));
    return rexBuilder.makeCall(SqlStdOperatorTable.CASE,
        rexBuilder.makeCall(SqlStdOperatorTable.EQUALS,
            countRef, rexBuilder.makeExactLiteral(BigDecimal.ZERO)),
        rexBuilder.constantNull(),
        sumZeroRef);
  }

  private RexNode reduceStddev(
      Aggregate oldAggRel,
      AggregateCall oldCall,
      boolean biased,
      boolean sqrt,
      List<AggregateCall> newCalls,
      Map<AggregateCall, RexNode> aggCallMapping,
      List<RexNode> inputExprs) {
    // stddev_pop(x) ==>
    //   power(
    //     (sum(x * x) - sum(x) * sum(x) / count(x))
    //     / count(x),
    //     .5)
    //
    // stddev_samp(x) ==>
    //   power(
    //     (sum(x * x) - sum(x) * sum(x) / count(x))
    //     / nullif(count(x) - 1, 0),
    //     .5)
    final int nGroups = oldAggRel.getGroupCount();
    RelDataTypeFactory typeFactory =
        oldAggRel.getCluster().getTypeFactory();
    final RexBuilder rexBuilder = oldAggRel.getCluster().getRexBuilder();

    assert oldCall.getArgList().size() == 1 : oldCall.getArgList();
    final int argOrdinal = oldCall.getArgList().get(0);
    final RelDataType argType =
        getFieldType(
            oldAggRel.getInput(),
            argOrdinal);

    final RexNode argRef = inputExprs.get(argOrdinal);
    final RexNode argSquared =
        rexBuilder.makeCall(
            SqlStdOperatorTable.MULTIPLY, argRef, argRef);
    final int argSquaredOrdinal = lookupOrAdd(inputExprs, argSquared);

    final RelDataType sumType =
        typeFactory.createTypeWithNullability(
            argType,
            true);
    final AggregateCall sumArgSquaredAggCall =
        new AggregateCall(
            new SqlSumAggFunction(sumType),
            oldCall.isDistinct(),
            ImmutableIntList.of(argSquaredOrdinal),
            sumType,
            null);
    final RexNode sumArgSquared =
        rexBuilder.addAggCall(
            sumArgSquaredAggCall,
            nGroups,
            newCalls,
            aggCallMapping,
            ImmutableList.of(argType));

    final AggregateCall sumArgAggCall =
        new AggregateCall(
            new SqlSumAggFunction(sumType),
            oldCall.isDistinct(),
            ImmutableIntList.of(argOrdinal),
            sumType,
            null);
    final RexNode sumArg =
        rexBuilder.addAggCall(
            sumArgAggCall,
            nGroups,
            newCalls,
            aggCallMapping,
            ImmutableList.of(argType));

    final RexNode sumSquaredArg =
        rexBuilder.makeCall(
            SqlStdOperatorTable.MULTIPLY, sumArg, sumArg);

    final AggregateCall countArgAggCall =
        AggregateCall.create(
            SqlStdOperatorTable.COUNT,
            oldCall.isDistinct(),
            oldCall.getArgList(),
            oldAggRel.getGroupCount(),
            oldAggRel.getInput(),
            null,
            null);
    final RexNode countArg =
        rexBuilder.addAggCall(
            countArgAggCall,
            nGroups,
            newCalls,
            aggCallMapping,
            ImmutableList.of(argType));

    final RexNode avgSumSquaredArg =
        rexBuilder.makeCall(
            SqlStdOperatorTable.DIVIDE,
            sumSquaredArg, countArg);

    final RexNode diff =
        rexBuilder.makeCall(
            SqlStdOperatorTable.MINUS,
            sumArgSquared, avgSumSquaredArg);

    final RexNode denominator;
    if (biased) {
      denominator = countArg;
    } else {
      final RexLiteral one =
          rexBuilder.makeExactLiteral(BigDecimal.ONE);
      final RexNode nul =
          rexBuilder.makeNullLiteral(countArg.getType().getSqlTypeName());
      final RexNode countMinusOne =
          rexBuilder.makeCall(
              SqlStdOperatorTable.MINUS, countArg, one);
      final RexNode countEqOne =
          rexBuilder.makeCall(
              SqlStdOperatorTable.EQUALS, countArg, one);
      denominator =
          rexBuilder.makeCall(
              SqlStdOperatorTable.CASE,
              countEqOne, nul, countMinusOne);
    }

    final RexNode div =
        rexBuilder.makeCall(
            SqlStdOperatorTable.DIVIDE, diff, denominator);

    RexNode result = div;
    if (sqrt) {
      final RexNode half =
          rexBuilder.makeExactLiteral(new BigDecimal("0.5"));
      result =
          rexBuilder.makeCall(
              SqlStdOperatorTable.POWER, div, half);
    }

    return rexBuilder.makeCast(
        oldCall.getType(), result);
  }

  /**
   * Finds the ordinal of an element in a list, or adds it.
   *
   * @param list    List
   * @param element Element to lookup or add
   * @param <T>     Element type
   * @return Ordinal of element in list
   */
  private static <T> int lookupOrAdd(List<T> list, T element) {
    int ordinal = list.indexOf(element);
    if (ordinal == -1) {
      ordinal = list.size();
      list.add(element);
    }
    return ordinal;
  }

  /**
   * Do a shallow clone of oldAggRel and update aggCalls. Could be refactored
   * into Aggregate and subclasses - but it's only needed for some
   * subclasses.
   *
   * @param oldAggRel LogicalAggregate to clone.
   * @param inputRel  Input relational expression
   * @param newCalls  New list of AggregateCalls
   * @return shallow clone with new list of AggregateCalls.
   */
  protected Aggregate newAggregateRel(
      Aggregate oldAggRel,
      RelNode inputRel,
      List<AggregateCall> newCalls) {
    return new LogicalAggregate(oldAggRel.getCluster(), inputRel,
        oldAggRel.indicator, oldAggRel.getGroupSet(), oldAggRel.getGroupSets(),
        newCalls);
  }

  private RelDataType getFieldType(RelNode relNode, int i) {
    final RelDataTypeField inputField =
        relNode.getRowType().getFieldList().get(i);
    return inputField.getType();
  }
}

// End AggregateReduceFunctionsRule.java