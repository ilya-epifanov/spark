/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.optimizer

import scala.annotation.tailrec
import scala.collection.immutable.HashSet

import org.apache.spark.sql.catalyst.analysis.{CleanupAliases, DistinctAggregationRewriter, EliminateSubqueryAliases}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.expressions.Literal.{FalseLiteral, TrueLiteral}
import org.apache.spark.sql.catalyst.planning.{ExtractFiltersAndInnerJoins, Unions}
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules._
import org.apache.spark.sql.types._

/**
 * Abstract class all optimizers should inherit of, contains the standard batches (extending
 * Optimizers can override this.
 */
abstract class Optimizer extends RuleExecutor[LogicalPlan] {
  def batches: Seq[Batch] = {
    // Technically some of the rules in Finish Analysis are not optimizer rules and belong more
    // in the analyzer, because they are needed for correctness (e.g. ComputeCurrentTime).
    // However, because we also use the analyzer to canonicalized queries (for view definition),
    // we do not eliminate subqueries or compute current time in the analyzer.
    Batch("Finish Analysis", Once,
      EliminateSubqueryAliases,
      ComputeCurrentTime,
      DistinctAggregationRewriter) ::
    //////////////////////////////////////////////////////////////////////////////////////////
    // Optimizer rules start here
    //////////////////////////////////////////////////////////////////////////////////////////
    // - Do the first call of CombineUnions before starting the major Optimizer rules,
    //   since it can reduce the number of iteration and the other rules could add/move
    //   extra operators between two adjacent Union operators.
    // - Call CombineUnions again in Batch("Operator Optimizations"),
    //   since the other rules might make two separate Unions operators adjacent.
    Batch("Union", Once,
      CombineUnions) ::
    Batch("Replace Operators", FixedPoint(100),
      ReplaceIntersectWithSemiJoin,
      ReplaceDistinctWithAggregate) ::
    Batch("Aggregate", FixedPoint(100),
      RemoveLiteralFromGroupExpressions) ::
    Batch("Operator Optimizations", FixedPoint(100),
      // Operator push down
      SetOperationPushDown,
      SamplePushDown,
      ReorderJoin,
      OuterJoinElimination,
      PushPredicateThroughJoin,
      PushDownPredicate,
      LimitPushDown,
      ColumnPruning,
      InferFiltersFromConstraints,
      // Operator combine
      CollapseRepartition,
      CollapseProject,
      CombineFilters,
      CombineLimits,
      CombineUnions,
      // Constant folding and strength reduction
      NullPropagation,
      OptimizeIn,
      ConstantFolding,
      LikeSimplification,
      BooleanSimplification,
      SimplifyConditionals,
      RemoveDispensableExpressions,
      BinaryComparisonSimplification,
      PruneFilters,
      EliminateSorts,
      SimplifyCasts,
      SimplifyCaseConversionExpressions,
      EliminateSerialization) ::
    Batch("Decimal Optimizations", FixedPoint(100),
      DecimalAggregates) ::
    Batch("Typed Filter Optimization", FixedPoint(100),
      EmbedSerializerInFilter) ::
    Batch("LocalRelation", FixedPoint(100),
      ConvertToLocalRelation) ::
    Batch("Subquery", Once,
      OptimizeSubqueries) :: Nil
  }

  /**
   * Optimize all the subqueries inside expression.
   */
  object OptimizeSubqueries extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan transformAllExpressions {
      case subquery: SubqueryExpression =>
        subquery.withNewPlan(Optimizer.this.execute(subquery.query))
    }
  }
}

/**
 * Non-abstract representation of the standard Spark optimizing strategies
 *
 * To ensure extendability, we leave the standard rules in the abstract optimizer rules, while
 * specific rules go to the subclasses
 */
object DefaultOptimizer extends Optimizer

/**
 * Pushes operations down into a Sample.
 */
object SamplePushDown extends Rule[LogicalPlan] {

  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    // Push down projection into sample
    case Project(projectList, s @ Sample(lb, up, replace, seed, child)) =>
      Sample(lb, up, replace, seed,
        Project(projectList, child))()
  }
}

/**
 * Removes cases where we are unnecessarily going between the object and serialized (InternalRow)
 * representation of data item.  For example back to back map operations.
 */
object EliminateSerialization extends Rule[LogicalPlan] {
  // TODO: find a more general way to do this optimization.
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case m @ MapPartitions(_, deserializer, _, child: ObjectOperator)
        if !deserializer.isInstanceOf[Attribute] &&
          deserializer.dataType == child.outputObject.dataType =>
      val childWithoutSerialization = child.withObjectOutput
      m.copy(
        deserializer = childWithoutSerialization.output.head,
        child = childWithoutSerialization)

    case m @ MapElements(_, deserializer, _, child: ObjectOperator)
        if !deserializer.isInstanceOf[Attribute] &&
          deserializer.dataType == child.outputObject.dataType =>
      val childWithoutSerialization = child.withObjectOutput
      m.copy(
        deserializer = childWithoutSerialization.output.head,
        child = childWithoutSerialization)

    case d @ DeserializeToObject(_, s: SerializeFromObject)
        if d.outputObjectType == s.inputObjectType =>
      // Adds an extra Project here, to preserve the output expr id of `DeserializeToObject`.
      val objAttr = Alias(s.child.output.head, "obj")(exprId = d.output.head.exprId)
      Project(objAttr :: Nil, s.child)
  }
}

/**
 * Pushes down [[LocalLimit]] beneath UNION ALL and beneath the streamed inputs of outer joins.
 */
object LimitPushDown extends Rule[LogicalPlan] {

  private def stripGlobalLimitIfPresent(plan: LogicalPlan): LogicalPlan = {
    plan match {
      case GlobalLimit(expr, child) => child
      case _ => plan
    }
  }

  private def maybePushLimit(limitExp: Expression, plan: LogicalPlan): LogicalPlan = {
    (limitExp, plan.maxRows) match {
      case (IntegerLiteral(maxRow), Some(childMaxRows)) if maxRow < childMaxRows =>
        LocalLimit(limitExp, stripGlobalLimitIfPresent(plan))
      case (_, None) =>
        LocalLimit(limitExp, stripGlobalLimitIfPresent(plan))
      case _ => plan
    }
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    // Adding extra Limits below UNION ALL for children which are not Limit or do not have Limit
    // descendants whose maxRow is larger. This heuristic is valid assuming there does not exist any
    // Limit push-down rule that is unable to infer the value of maxRows.
    // Note: right now Union means UNION ALL, which does not de-duplicate rows, so it is safe to
    // pushdown Limit through it. Once we add UNION DISTINCT, however, we will not be able to
    // pushdown Limit.
    case LocalLimit(exp, Union(children)) =>
      LocalLimit(exp, Union(children.map(maybePushLimit(exp, _))))
    // Add extra limits below OUTER JOIN. For LEFT OUTER and FULL OUTER JOIN we push limits to the
    // left and right sides, respectively. For FULL OUTER JOIN, we can only push limits to one side
    // because we need to ensure that rows from the limited side still have an opportunity to match
    // against all candidates from the non-limited side. We also need to ensure that this limit
    // pushdown rule will not eventually introduce limits on both sides if it is applied multiple
    // times. Therefore:
    //   - If one side is already limited, stack another limit on top if the new limit is smaller.
    //     The redundant limit will be collapsed by the CombineLimits rule.
    //   - If neither side is limited, limit the side that is estimated to be bigger.
    case LocalLimit(exp, join @ Join(left, right, joinType, condition)) =>
      val newJoin = joinType match {
        case RightOuter => join.copy(right = maybePushLimit(exp, right))
        case LeftOuter => join.copy(left = maybePushLimit(exp, left))
        case FullOuter =>
          (left.maxRows, right.maxRows) match {
            case (None, None) =>
              if (left.statistics.sizeInBytes >= right.statistics.sizeInBytes) {
                join.copy(left = maybePushLimit(exp, left))
              } else {
                join.copy(right = maybePushLimit(exp, right))
              }
            case (Some(_), Some(_)) => join
            case (Some(_), None) => join.copy(left = maybePushLimit(exp, left))
            case (None, Some(_)) => join.copy(right = maybePushLimit(exp, right))

          }
        case _ => join
      }
      LocalLimit(exp, newJoin)
  }
}

/**
 * Pushes certain operations to both sides of a Union or Except operator.
 * Operations that are safe to pushdown are listed as follows.
 * Union:
 * Right now, Union means UNION ALL, which does not de-duplicate rows. So, it is
 * safe to pushdown Filters and Projections through it. Once we add UNION DISTINCT,
 * we will not be able to pushdown Projections.
 *
 * Except:
 * It is not safe to pushdown Projections through it because we need to get the
 * intersect of rows by comparing the entire rows. It is fine to pushdown Filters
 * with deterministic condition.
 */
object SetOperationPushDown extends Rule[LogicalPlan] with PredicateHelper {

  /**
   * Maps Attributes from the left side to the corresponding Attribute on the right side.
   */
  private def buildRewrites(left: LogicalPlan, right: LogicalPlan): AttributeMap[Attribute] = {
    assert(left.output.size == right.output.size)
    AttributeMap(left.output.zip(right.output))
  }

  /**
   * Rewrites an expression so that it can be pushed to the right side of a
   * Union or Except operator. This method relies on the fact that the output attributes
   * of a union/intersect/except are always equal to the left child's output.
   */
  private def pushToRight[A <: Expression](e: A, rewrites: AttributeMap[Attribute]) = {
    val result = e transform {
      case a: Attribute => rewrites(a)
    }

    // We must promise the compiler that we did not discard the names in the case of project
    // expressions.  This is safe since the only transformation is from Attribute => Attribute.
    result.asInstanceOf[A]
  }

  /**
   * Splits the condition expression into small conditions by `And`, and partition them by
   * deterministic, and finally recombine them by `And`. It returns an expression containing
   * all deterministic expressions (the first field of the returned Tuple2) and an expression
   * containing all non-deterministic expressions (the second field of the returned Tuple2).
   */
  private def partitionByDeterministic(condition: Expression): (Expression, Expression) = {
    val andConditions = splitConjunctivePredicates(condition)
    andConditions.partition(_.deterministic) match {
      case (deterministic, nondeterministic) =>
        deterministic.reduceOption(And).getOrElse(Literal(true)) ->
        nondeterministic.reduceOption(And).getOrElse(Literal(true))
    }
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan transform {

    // Push down deterministic projection through UNION ALL
    case p @ Project(projectList, Union(children)) =>
      assert(children.nonEmpty)
      if (projectList.forall(_.deterministic)) {
        val newFirstChild = Project(projectList, children.head)
        val newOtherChildren = children.tail.map { child =>
          val rewrites = buildRewrites(children.head, child)
          Project(projectList.map(pushToRight(_, rewrites)), child)
        }
        Union(newFirstChild +: newOtherChildren)
      } else {
        p
      }

    // Push down filter into union
    case Filter(condition, Union(children)) =>
      assert(children.nonEmpty)
      val (deterministic, nondeterministic) = partitionByDeterministic(condition)
      val newFirstChild = Filter(deterministic, children.head)
      val newOtherChildren = children.tail.map {
        child => {
          val rewrites = buildRewrites(children.head, child)
          Filter(pushToRight(deterministic, rewrites), child)
        }
      }
      Filter(nondeterministic, Union(newFirstChild +: newOtherChildren))

    // Push down filter through EXCEPT
    case Filter(condition, Except(left, right)) =>
      val (deterministic, nondeterministic) = partitionByDeterministic(condition)
      val rewrites = buildRewrites(left, right)
      Filter(nondeterministic,
        Except(
          Filter(deterministic, left),
          Filter(pushToRight(deterministic, rewrites), right)
        )
      )
  }
}

/**
 * Attempts to eliminate the reading of unneeded columns from the query plan.
 *
 * Since adding Project before Filter conflicts with PushPredicatesThroughProject, this rule will
 * remove the Project p2 in the following pattern:
 *
 *   p1 @ Project(_, Filter(_, p2 @ Project(_, child))) if p2.outputSet.subsetOf(p2.inputSet)
 *
 * p2 is usually inserted by this rule and useless, p1 could prune the columns anyway.
 */
object ColumnPruning extends Rule[LogicalPlan] {
  private def sameOutput(output1: Seq[Attribute], output2: Seq[Attribute]): Boolean =
    output1.size == output2.size &&
      output1.zip(output2).forall(pair => pair._1.semanticEquals(pair._2))

  def apply(plan: LogicalPlan): LogicalPlan = removeProjectBeforeFilter(plan transform {
    // Prunes the unused columns from project list of Project/Aggregate/Expand
    case p @ Project(_, p2: Project) if (p2.outputSet -- p.references).nonEmpty =>
      p.copy(child = p2.copy(projectList = p2.projectList.filter(p.references.contains)))
    case p @ Project(_, a: Aggregate) if (a.outputSet -- p.references).nonEmpty =>
      p.copy(
        child = a.copy(aggregateExpressions = a.aggregateExpressions.filter(p.references.contains)))
    case a @ Project(_, e @ Expand(_, _, grandChild)) if (e.outputSet -- a.references).nonEmpty =>
      val newOutput = e.output.filter(a.references.contains(_))
      val newProjects = e.projections.map { proj =>
        proj.zip(e.output).filter { case (e, a) =>
          newOutput.contains(a)
        }.unzip._1
      }
      a.copy(child = Expand(newProjects, newOutput, grandChild))

    // Prunes the unused columns from child of MapPartitions
    case mp @ MapPartitions(_, _, _, child) if (child.outputSet -- mp.references).nonEmpty =>
      mp.copy(child = prunedChild(child, mp.references))

    // Prunes the unused columns from child of Aggregate/Expand/Generate
    case a @ Aggregate(_, _, child) if (child.outputSet -- a.references).nonEmpty =>
      a.copy(child = prunedChild(child, a.references))
    case e @ Expand(_, _, child) if (child.outputSet -- e.references).nonEmpty =>
      e.copy(child = prunedChild(child, e.references))
    case g: Generate if !g.join && (g.child.outputSet -- g.references).nonEmpty =>
      g.copy(child = prunedChild(g.child, g.references))

    // Turn off `join` for Generate if no column from it's child is used
    case p @ Project(_, g: Generate) if g.join && p.references.subsetOf(g.generatedSet) =>
      p.copy(child = g.copy(join = false))

    // Eliminate unneeded attributes from right side of a Left Existence Join.
    case j @ Join(left, right, LeftExistence(_), condition) =>
      j.copy(right = prunedChild(right, j.references))

    // all the columns will be used to compare, so we can't prune them
    case p @ Project(_, _: SetOperation) => p
    case p @ Project(_, _: Distinct) => p
    // Eliminate unneeded attributes from children of Union.
    case p @ Project(_, u: Union) =>
      if ((u.outputSet -- p.references).nonEmpty) {
        val firstChild = u.children.head
        val newOutput = prunedChild(firstChild, p.references).output
        // pruning the columns of all children based on the pruned first child.
        val newChildren = u.children.map { p =>
          val selected = p.output.zipWithIndex.filter { case (a, i) =>
            newOutput.contains(firstChild.output(i))
          }.map(_._1)
          Project(selected, p)
        }
        p.copy(child = u.withNewChildren(newChildren))
      } else {
        p
      }

    // Prune unnecessary window expressions
    case p @ Project(_, w: Window) if (w.windowOutputSet -- p.references).nonEmpty =>
      p.copy(child = w.copy(
        windowExpressions = w.windowExpressions.filter(p.references.contains)))

    // Eliminate no-op Window
    case w: Window if w.windowExpressions.isEmpty => w.child

    // Eliminate no-op Projects
    case p @ Project(projectList, child) if sameOutput(child.output, p.output) => child

    // Can't prune the columns on LeafNode
    case p @ Project(_, l: LeafNode) => p

    // for all other logical plans that inherits the output from it's children
    case p @ Project(_, child) =>
      val required = child.references ++ p.references
      if ((child.inputSet -- required).nonEmpty) {
        val newChildren = child.children.map(c => prunedChild(c, required))
        p.copy(child = child.withNewChildren(newChildren))
      } else {
        p
      }
  })

  /** Applies a projection only when the child is producing unnecessary attributes */
  private def prunedChild(c: LogicalPlan, allReferences: AttributeSet) =
    if ((c.outputSet -- allReferences.filter(c.outputSet.contains)).nonEmpty) {
      Project(c.output.filter(allReferences.contains), c)
    } else {
      c
    }

  /**
   * The Project before Filter is not necessary but conflict with PushPredicatesThroughProject,
   * so remove it.
   */
  private def removeProjectBeforeFilter(plan: LogicalPlan): LogicalPlan = plan transform {
    case p1 @ Project(_, f @ Filter(_, p2 @ Project(_, child)))
      if p2.outputSet.subsetOf(child.outputSet) =>
      p1.copy(child = f.copy(child = child))
  }
}

/**
 * Combines two adjacent [[Project]] operators into one and perform alias substitution,
 * merging the expressions into one single expression.
 */
object CollapseProject extends Rule[LogicalPlan] {

  def apply(plan: LogicalPlan): LogicalPlan = plan transformUp {
    case p1 @ Project(_, p2: Project) =>
      if (haveCommonNonDeterministicOutput(p1.projectList, p2.projectList)) {
        p1
      } else {
        p2.copy(projectList = buildCleanedProjectList(p1.projectList, p2.projectList))
      }
    case p @ Project(_, agg: Aggregate) =>
      if (haveCommonNonDeterministicOutput(p.projectList, agg.aggregateExpressions)) {
        p
      } else {
        agg.copy(aggregateExpressions = buildCleanedProjectList(
          p.projectList, agg.aggregateExpressions))
      }
  }

  private def collectAliases(projectList: Seq[NamedExpression]): AttributeMap[Alias] = {
    AttributeMap(projectList.collect {
      case a: Alias => a.toAttribute -> a
    })
  }

  private def haveCommonNonDeterministicOutput(
      upper: Seq[NamedExpression], lower: Seq[NamedExpression]): Boolean = {
    // Create a map of Aliases to their values from the lower projection.
    // e.g., 'SELECT ... FROM (SELECT a + b AS c, d ...)' produces Map(c -> Alias(a + b, c)).
    val aliases = collectAliases(lower)

    // Collapse upper and lower Projects if and only if their overlapped expressions are all
    // deterministic.
    upper.exists(_.collect {
      case a: Attribute if aliases.contains(a) => aliases(a).child
    }.exists(!_.deterministic))
  }

  private def buildCleanedProjectList(
      upper: Seq[NamedExpression],
      lower: Seq[NamedExpression]): Seq[NamedExpression] = {
    // Create a map of Aliases to their values from the lower projection.
    // e.g., 'SELECT ... FROM (SELECT a + b AS c, d ...)' produces Map(c -> Alias(a + b, c)).
    val aliases = collectAliases(lower)

    // Substitute any attributes that are produced by the lower projection, so that we safely
    // eliminate it.
    // e.g., 'SELECT c + 1 FROM (SELECT a + b AS C ...' produces 'SELECT a + b + 1 ...'
    val rewrittenUpper = upper.map(_.transform {
      case a: Attribute => aliases.getOrElse(a, a)
    })
    // collapse upper and lower Projects may introduce unnecessary Aliases, trim them here.
    rewrittenUpper.map { p =>
      CleanupAliases.trimNonTopLevelAliases(p).asInstanceOf[NamedExpression]
    }
  }
}

/**
 * Combines adjacent [[Repartition]] operators by keeping only the last one.
 */
object CollapseRepartition extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transformUp {
    case r @ Repartition(numPartitions, shuffle, Repartition(_, _, child)) =>
      Repartition(numPartitions, shuffle, child)
  }
}

/**
 * Simplifies LIKE expressions that do not need full regular expressions to evaluate the condition.
 * For example, when the expression is just checking to see if a string starts with a given
 * pattern.
 */
object LikeSimplification extends Rule[LogicalPlan] {
  // if guards below protect from escapes on trailing %.
  // Cases like "something\%" are not optimized, but this does not affect correctness.
  private val startsWith = "([^_%]+)%".r
  private val endsWith = "%([^_%]+)".r
  private val startsAndEndsWith = "([^_%]+)%([^_%]+)".r
  private val contains = "%([^_%]+)%".r
  private val equalTo = "([^_%]*)".r

  def apply(plan: LogicalPlan): LogicalPlan = plan transformAllExpressions {
    case Like(input, Literal(pattern, StringType)) =>
      pattern.toString match {
        case startsWith(prefix) if !prefix.endsWith("\\") =>
          StartsWith(input, Literal(prefix))
        case endsWith(postfix) =>
          EndsWith(input, Literal(postfix))
        // 'a%a' pattern is basically same with 'a%' && '%a'.
        // However, the additional `Length` condition is required to prevent 'a' match 'a%a'.
        case startsAndEndsWith(prefix, postfix) if !prefix.endsWith("\\") =>
          And(GreaterThanOrEqual(Length(input), Literal(prefix.size + postfix.size)),
            And(StartsWith(input, Literal(prefix)), EndsWith(input, Literal(postfix))))
        case contains(infix) if !infix.endsWith("\\") =>
          Contains(input, Literal(infix))
        case equalTo(str) =>
          EqualTo(input, Literal(str))
        case _ =>
          Like(input, Literal.create(pattern, StringType))
      }
  }
}

/**
 * Replaces [[Expression Expressions]] that can be statically evaluated with
 * equivalent [[Literal]] values. This rule is more specific with
 * Null value propagation from bottom to top of the expression tree.
 */
object NullPropagation extends Rule[LogicalPlan] {
  private def nonNullLiteral(e: Expression): Boolean = e match {
    case Literal(null, _) => false
    case _ => true
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case q: LogicalPlan => q transformExpressionsUp {
      case e @ AggregateExpression(Count(exprs), _, _, _) if !exprs.exists(nonNullLiteral) =>
        Cast(Literal(0L), e.dataType)
      case e @ IsNull(c) if !c.nullable => Literal.create(false, BooleanType)
      case e @ IsNotNull(c) if !c.nullable => Literal.create(true, BooleanType)
      case e @ GetArrayItem(Literal(null, _), _) => Literal.create(null, e.dataType)
      case e @ GetArrayItem(_, Literal(null, _)) => Literal.create(null, e.dataType)
      case e @ GetMapValue(Literal(null, _), _) => Literal.create(null, e.dataType)
      case e @ GetMapValue(_, Literal(null, _)) => Literal.create(null, e.dataType)
      case e @ GetStructField(Literal(null, _), _, _) => Literal.create(null, e.dataType)
      case e @ GetArrayStructFields(Literal(null, _), _, _, _, _) =>
        Literal.create(null, e.dataType)
      case e @ EqualNullSafe(Literal(null, _), r) => IsNull(r)
      case e @ EqualNullSafe(l, Literal(null, _)) => IsNull(l)
      case ae @ AggregateExpression(Count(exprs), _, false, _) if !exprs.exists(_.nullable) =>
        // This rule should be only triggered when isDistinct field is false.
        ae.copy(aggregateFunction = Count(Literal(1)))

      // For Coalesce, remove null literals.
      case e @ Coalesce(children) =>
        val newChildren = children.filter(nonNullLiteral)
        if (newChildren.length == 0) {
          Literal.create(null, e.dataType)
        } else if (newChildren.length == 1) {
          newChildren.head
        } else {
          Coalesce(newChildren)
        }

      case e @ Substring(Literal(null, _), _, _) => Literal.create(null, e.dataType)
      case e @ Substring(_, Literal(null, _), _) => Literal.create(null, e.dataType)
      case e @ Substring(_, _, Literal(null, _)) => Literal.create(null, e.dataType)

      // MaxOf and MinOf can't do null propagation
      case e: MaxOf => e
      case e: MinOf => e

      // Put exceptional cases above if any
      case e @ BinaryArithmetic(Literal(null, _), _) => Literal.create(null, e.dataType)
      case e @ BinaryArithmetic(_, Literal(null, _)) => Literal.create(null, e.dataType)

      case e @ BinaryComparison(Literal(null, _), _) => Literal.create(null, e.dataType)
      case e @ BinaryComparison(_, Literal(null, _)) => Literal.create(null, e.dataType)

      case e: StringRegexExpression => e.children match {
        case Literal(null, _) :: right :: Nil => Literal.create(null, e.dataType)
        case left :: Literal(null, _) :: Nil => Literal.create(null, e.dataType)
        case _ => e
      }

      case e: StringPredicate => e.children match {
        case Literal(null, _) :: right :: Nil => Literal.create(null, e.dataType)
        case left :: Literal(null, _) :: Nil => Literal.create(null, e.dataType)
        case _ => e
      }

      // If the value expression is NULL then transform the In expression to
      // Literal(null)
      case In(Literal(null, _), list) => Literal.create(null, BooleanType)

    }
  }
}

/**
 * Generate a list of additional filters from an operator's existing constraint but remove those
 * that are either already part of the operator's condition or are part of the operator's child
 * constraints. These filters are currently inserted to the existing conditions in the Filter
 * operators and on either side of Join operators.
 *
 * Note: While this optimization is applicable to all types of join, it primarily benefits Inner and
 * LeftSemi joins.
 */
object InferFiltersFromConstraints extends Rule[LogicalPlan] with PredicateHelper {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case filter @ Filter(condition, child) =>
      val newFilters = filter.constraints --
        (child.constraints ++ splitConjunctivePredicates(condition))
      if (newFilters.nonEmpty) {
        Filter(And(newFilters.reduce(And), condition), child)
      } else {
        filter
      }

    case join @ Join(left, right, joinType, conditionOpt) =>
      // Only consider constraints that can be pushed down completely to either the left or the
      // right child
      val constraints = join.constraints.filter { c =>
        c.references.subsetOf(left.outputSet) || c.references.subsetOf(right.outputSet)}
      // Remove those constraints that are already enforced by either the left or the right child
      val additionalConstraints = constraints -- (left.constraints ++ right.constraints)
      val newConditionOpt = conditionOpt match {
        case Some(condition) =>
          val newFilters = additionalConstraints -- splitConjunctivePredicates(condition)
          if (newFilters.nonEmpty) Option(And(newFilters.reduce(And), condition)) else None
        case None =>
          additionalConstraints.reduceOption(And)
      }
      if (newConditionOpt.isDefined) Join(left, right, joinType, newConditionOpt) else join
  }
}

/**
 * Replaces [[Expression Expressions]] that can be statically evaluated with
 * equivalent [[Literal]] values.
 */
object ConstantFolding extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case q: LogicalPlan => q transformExpressionsDown {
      // Skip redundant folding of literals. This rule is technically not necessary. Placing this
      // here avoids running the next rule for Literal values, which would create a new Literal
      // object and running eval unnecessarily.
      case l: Literal => l

      // Fold expressions that are foldable.
      case e if e.foldable => Literal.create(e.eval(EmptyRow), e.dataType)
    }
  }
}

/**
 * Replaces [[In (value, seq[Literal])]] with optimized version[[InSet (value, HashSet[Literal])]]
 * which is much faster
 */
object OptimizeIn extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case q: LogicalPlan => q transformExpressionsDown {
      case In(v, list) if !list.exists(!_.isInstanceOf[Literal]) && list.size > 10 =>
        val hSet = list.map(e => e.eval(EmptyRow))
        InSet(v, HashSet() ++ hSet)
    }
  }
}

/**
 * Simplifies boolean expressions:
 * 1. Simplifies expressions whose answer can be determined without evaluating both sides.
 * 2. Eliminates / extracts common factors.
 * 3. Merge same expressions
 * 4. Removes `Not` operator.
 */
object BooleanSimplification extends Rule[LogicalPlan] with PredicateHelper {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case q: LogicalPlan => q transformExpressionsUp {
      case TrueLiteral And e => e
      case e And TrueLiteral => e
      case FalseLiteral Or e => e
      case e Or FalseLiteral => e

      case FalseLiteral And _ => FalseLiteral
      case _ And FalseLiteral => FalseLiteral
      case TrueLiteral Or _ => TrueLiteral
      case _ Or TrueLiteral => TrueLiteral

      case a And b if a.semanticEquals(b) => a
      case a Or b if a.semanticEquals(b) => a

      case a And (b Or c) if Not(a).semanticEquals(b) => And(a, c)
      case a And (b Or c) if Not(a).semanticEquals(c) => And(a, b)
      case (a Or b) And c if a.semanticEquals(Not(c)) => And(b, c)
      case (a Or b) And c if b.semanticEquals(Not(c)) => And(a, c)

      case a Or (b And c) if Not(a).semanticEquals(b) => Or(a, c)
      case a Or (b And c) if Not(a).semanticEquals(c) => Or(a, b)
      case (a And b) Or c if a.semanticEquals(Not(c)) => Or(b, c)
      case (a And b) Or c if b.semanticEquals(Not(c)) => Or(a, c)

      // Common factor elimination for conjunction
      case and @ (left And right) =>
        // 1. Split left and right to get the disjunctive predicates,
        //   i.e. lhs = (a, b), rhs = (a, c)
        // 2. Find the common predict between lhsSet and rhsSet, i.e. common = (a)
        // 3. Remove common predict from lhsSet and rhsSet, i.e. ldiff = (b), rdiff = (c)
        // 4. Apply the formula, get the optimized predicate: common || (ldiff && rdiff)
        val lhs = splitDisjunctivePredicates(left)
        val rhs = splitDisjunctivePredicates(right)
        val common = lhs.filter(e => rhs.exists(e.semanticEquals))
        if (common.isEmpty) {
          // No common factors, return the original predicate
          and
        } else {
          val ldiff = lhs.filterNot(e => common.exists(e.semanticEquals))
          val rdiff = rhs.filterNot(e => common.exists(e.semanticEquals))
          if (ldiff.isEmpty || rdiff.isEmpty) {
            // (a || b || c || ...) && (a || b) => (a || b)
            common.reduce(Or)
          } else {
            // (a || b || c || ...) && (a || b || d || ...) =>
            // ((c || ...) && (d || ...)) || a || b
            (common :+ And(ldiff.reduce(Or), rdiff.reduce(Or))).reduce(Or)
          }
        }

      // Common factor elimination for disjunction
      case or @ (left Or right) =>
        // 1. Split left and right to get the conjunctive predicates,
        //   i.e.  lhs = (a, b), rhs = (a, c)
        // 2. Find the common predict between lhsSet and rhsSet, i.e. common = (a)
        // 3. Remove common predict from lhsSet and rhsSet, i.e. ldiff = (b), rdiff = (c)
        // 4. Apply the formula, get the optimized predicate: common && (ldiff || rdiff)
        val lhs = splitConjunctivePredicates(left)
        val rhs = splitConjunctivePredicates(right)
        val common = lhs.filter(e => rhs.exists(e.semanticEquals))
        if (common.isEmpty) {
          // No common factors, return the original predicate
          or
        } else {
          val ldiff = lhs.filterNot(e => common.exists(e.semanticEquals))
          val rdiff = rhs.filterNot(e => common.exists(e.semanticEquals))
          if (ldiff.isEmpty || rdiff.isEmpty) {
            // (a && b) || (a && b && c && ...) => a && b
            common.reduce(And)
          } else {
            // (a && b && c && ...) || (a && b && d && ...) =>
            // ((c && ...) || (d && ...)) && a && b
            (common :+ Or(ldiff.reduce(And), rdiff.reduce(And))).reduce(And)
          }
        }

      case Not(TrueLiteral) => FalseLiteral
      case Not(FalseLiteral) => TrueLiteral

      case Not(a GreaterThan b) => LessThanOrEqual(a, b)
      case Not(a GreaterThanOrEqual b) => LessThan(a, b)

      case Not(a LessThan b) => GreaterThanOrEqual(a, b)
      case Not(a LessThanOrEqual b) => GreaterThan(a, b)

      case Not(a Or b) => And(Not(a), Not(b))
      case Not(a And b) => Or(Not(a), Not(b))

      case Not(Not(e)) => e
    }
  }
}

/**
 * Simplifies binary comparisons with semantically-equal expressions:
 * 1) Replace '<=>' with 'true' literal.
 * 2) Replace '=', '<=', and '>=' with 'true' literal if both operands are non-nullable.
 * 3) Replace '<' and '>' with 'false' literal if both operands are non-nullable.
 */
object BinaryComparisonSimplification extends Rule[LogicalPlan] with PredicateHelper {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case q: LogicalPlan => q transformExpressionsUp {
      // True with equality
      case a EqualNullSafe b if a.semanticEquals(b) => TrueLiteral
      case a EqualTo b if !a.nullable && !b.nullable && a.semanticEquals(b) => TrueLiteral
      case a GreaterThanOrEqual b if !a.nullable && !b.nullable && a.semanticEquals(b) =>
        TrueLiteral
      case a LessThanOrEqual b if !a.nullable && !b.nullable && a.semanticEquals(b) => TrueLiteral

      // False with inequality
      case a GreaterThan b if !a.nullable && !b.nullable && a.semanticEquals(b) => FalseLiteral
      case a LessThan b if !a.nullable && !b.nullable && a.semanticEquals(b) => FalseLiteral
    }
  }
}

/**
 * Simplifies conditional expressions (if / case).
 */
object SimplifyConditionals extends Rule[LogicalPlan] with PredicateHelper {
  private def falseOrNullLiteral(e: Expression): Boolean = e match {
    case FalseLiteral => true
    case Literal(null, _) => true
    case _ => false
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case q: LogicalPlan => q transformExpressionsUp {
      case If(TrueLiteral, trueValue, _) => trueValue
      case If(FalseLiteral, _, falseValue) => falseValue
      case If(Literal(null, _), _, falseValue) => falseValue

      case e @ CaseWhen(branches, elseValue) if branches.exists(x => falseOrNullLiteral(x._1)) =>
        // If there are branches that are always false, remove them.
        // If there are no more branches left, just use the else value.
        // Note that these two are handled together here in a single case statement because
        // otherwise we cannot determine the data type for the elseValue if it is None (i.e. null).
        val newBranches = branches.filter(x => !falseOrNullLiteral(x._1))
        if (newBranches.isEmpty) {
          elseValue.getOrElse(Literal.create(null, e.dataType))
        } else {
          e.copy(branches = newBranches)
        }

      case e @ CaseWhen(branches, _) if branches.headOption.map(_._1) == Some(TrueLiteral) =>
        // If the first branch is a true literal, remove the entire CaseWhen and use the value
        // from that. Note that CaseWhen.branches should never be empty, and as a result the
        // headOption (rather than head) added above is just a extra (and unnecessary) safeguard.
        branches.head._2
    }
  }
}

/**
 * Combines all adjacent [[Union]] operators into a single [[Union]].
 */
object CombineUnions extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case Unions(children) => Union(children)
  }
}

/**
 * Combines two adjacent [[Filter]] operators into one, merging the non-redundant conditions into
 * one conjunctive predicate.
 */
object CombineFilters extends Rule[LogicalPlan] with PredicateHelper {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case ff @ Filter(fc, nf @ Filter(nc, grandChild)) =>
      (ExpressionSet(splitConjunctivePredicates(fc)) --
        ExpressionSet(splitConjunctivePredicates(nc))).reduceOption(And) match {
        case Some(ac) =>
          Filter(And(ac, nc), grandChild)
        case None =>
          nf
      }
  }
}

/**
 * Removes no-op SortOrder from Sort
 */
object EliminateSorts  extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case s @ Sort(orders, _, child) if orders.isEmpty || orders.exists(_.child.foldable) =>
      val newOrders = orders.filterNot(_.child.foldable)
      if (newOrders.isEmpty) child else s.copy(order = newOrders)
  }
}

/**
 * Removes filters that can be evaluated trivially.  This can be done through the following ways:
 * 1) by eliding the filter for cases where it will always evaluate to `true`.
 * 2) by substituting a dummy empty relation when the filter will always evaluate to `false`.
 * 3) by eliminating the always-true conditions given the constraints on the child's output.
 */
object PruneFilters extends Rule[LogicalPlan] with PredicateHelper {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    // If the filter condition always evaluate to true, remove the filter.
    case Filter(Literal(true, BooleanType), child) => child
    // If the filter condition always evaluate to null or false,
    // replace the input with an empty relation.
    case Filter(Literal(null, _), child) => LocalRelation(child.output, data = Seq.empty)
    case Filter(Literal(false, BooleanType), child) => LocalRelation(child.output, data = Seq.empty)
    // If any deterministic condition is guaranteed to be true given the constraints on the child's
    // output, remove the condition
    case f @ Filter(fc, p: LogicalPlan) =>
      val (prunedPredicates, remainingPredicates) =
        splitConjunctivePredicates(fc).partition { cond =>
          cond.deterministic && p.constraints.contains(cond)
        }
      if (prunedPredicates.isEmpty) {
        f
      } else if (remainingPredicates.isEmpty) {
        p
      } else {
        val newCond = remainingPredicates.reduce(And)
        Filter(newCond, p)
      }
  }
}

/**
 * Pushes [[Filter]] operators through many operators iff:
 * 1) the operator is deterministic
 * 2) the predicate is deterministic and the operator will not change any of rows.
 *
 * This heuristic is valid assuming the expression evaluation cost is minimal.
 */
object PushDownPredicate extends Rule[LogicalPlan] with PredicateHelper {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    // SPARK-13473: We can't push the predicate down when the underlying projection output non-
    // deterministic field(s).  Non-deterministic expressions are essentially stateful. This
    // implies that, for a given input row, the output are determined by the expression's initial
    // state and all the input rows processed before. In another word, the order of input rows
    // matters for non-deterministic expressions, while pushing down predicates changes the order.
    case filter @ Filter(condition, project @ Project(fields, grandChild))
      if fields.forall(_.deterministic) =>

      // Create a map of Aliases to their values from the child projection.
      // e.g., 'SELECT a + b AS c, d ...' produces Map(c -> a + b).
      val aliasMap = AttributeMap(fields.collect {
        case a: Alias => (a.toAttribute, a.child)
      })

      project.copy(child = Filter(replaceAlias(condition, aliasMap), grandChild))

    case filter @ Filter(condition, aggregate: Aggregate) =>
      // Find all the aliased expressions in the aggregate list that don't include any actual
      // AggregateExpression, and create a map from the alias to the expression
      val aliasMap = AttributeMap(aggregate.aggregateExpressions.collect {
        case a: Alias if a.child.find(_.isInstanceOf[AggregateExpression]).isEmpty =>
          (a.toAttribute, a.child)
      })

      // For each filter, expand the alias and check if the filter can be evaluated using
      // attributes produced by the aggregate operator's child operator.
      val (pushDown, stayUp) = splitConjunctivePredicates(condition).partition { cond =>
        val replaced = replaceAlias(cond, aliasMap)
        replaced.references.subsetOf(aggregate.child.outputSet) && replaced.deterministic
      }

      if (pushDown.nonEmpty) {
        val pushDownPredicate = pushDown.reduce(And)
        val replaced = replaceAlias(pushDownPredicate, aliasMap)
        val newAggregate = aggregate.copy(child = Filter(replaced, aggregate.child))
        // If there is no more filter to stay up, just eliminate the filter.
        // Otherwise, create "Filter(stayUp) <- Aggregate <- Filter(pushDownPredicate)".
        if (stayUp.isEmpty) newAggregate else Filter(stayUp.reduce(And), newAggregate)
      } else {
        filter
      }

    case filter @ Filter(condition, child)
      if child.isInstanceOf[Union] || child.isInstanceOf[Intersect] =>
      // Union/Intersect could change the rows, so non-deterministic predicate can't be pushed down
      val (pushDown, stayUp) = splitConjunctivePredicates(condition).partition { cond =>
        cond.deterministic
      }
      if (pushDown.nonEmpty) {
        val pushDownCond = pushDown.reduceLeft(And)
        val output = child.output
        val newGrandChildren = child.children.map { grandchild =>
          val newCond = pushDownCond transform {
            case e if output.exists(_.semanticEquals(e)) =>
              grandchild.output(output.indexWhere(_.semanticEquals(e)))
          }
          assert(newCond.references.subsetOf(grandchild.outputSet))
          Filter(newCond, grandchild)
        }
        val newChild = child.withNewChildren(newGrandChildren)
        if (stayUp.nonEmpty) {
          Filter(stayUp.reduceLeft(And), newChild)
        } else {
          newChild
        }
      } else {
        filter
      }

    case filter @ Filter(condition, e @ Except(left, _)) =>
      pushDownPredicate(filter, e.left) { predicate =>
        e.copy(left = Filter(predicate, left))
      }

    // two filters should be combine together by other rules
    case filter @ Filter(_, f: Filter) => filter
    // should not push predicates through sample, or will generate different results.
    case filter @ Filter(_, s: Sample) => filter
    // TODO: push predicates through expand
    case filter @ Filter(_, e: Expand) => filter

    case filter @ Filter(condition, u: UnaryNode) if u.expressions.forall(_.deterministic) =>
      pushDownPredicate(filter, u.child) { predicate =>
        u.withNewChildren(Seq(Filter(predicate, u.child)))
      }
  }

  private def pushDownPredicate(
      filter: Filter,
      grandchild: LogicalPlan)(insertFilter: Expression => LogicalPlan): LogicalPlan = {
    // Only push down the predicates that is deterministic and all the referenced attributes
    // come from grandchild.
    // TODO: non-deterministic predicates could be pushed through some operators that do not change
    // the rows.
    val (pushDown, stayUp) = splitConjunctivePredicates(filter.condition).partition { cond =>
      cond.deterministic && cond.references.subsetOf(grandchild.outputSet)
    }
    if (pushDown.nonEmpty) {
      val newChild = insertFilter(pushDown.reduceLeft(And))
      if (stayUp.nonEmpty) {
        Filter(stayUp.reduceLeft(And), newChild)
      } else {
        newChild
      }
    } else {
      filter
    }
  }
}

/**
 * Reorder the joins and push all the conditions into join, so that the bottom ones have at least
 * one condition.
 *
 * The order of joins will not be changed if all of them already have at least one condition.
 */
object ReorderJoin extends Rule[LogicalPlan] with PredicateHelper {

  /**
   * Join a list of plans together and push down the conditions into them.
   *
   * The joined plan are picked from left to right, prefer those has at least one join condition.
   *
   * @param input a list of LogicalPlans to join.
   * @param conditions a list of condition for join.
   */
  @tailrec
  def createOrderedJoin(input: Seq[LogicalPlan], conditions: Seq[Expression]): LogicalPlan = {
    assert(input.size >= 2)
    if (input.size == 2) {
      Join(input(0), input(1), Inner, conditions.reduceLeftOption(And))
    } else {
      val left :: rest = input.toList
      // find out the first join that have at least one join condition
      val conditionalJoin = rest.find { plan =>
        val refs = left.outputSet ++ plan.outputSet
        conditions.filterNot(canEvaluate(_, left)).filterNot(canEvaluate(_, plan))
          .exists(_.references.subsetOf(refs))
      }
      // pick the next one if no condition left
      val right = conditionalJoin.getOrElse(rest.head)

      val joinedRefs = left.outputSet ++ right.outputSet
      val (joinConditions, others) = conditions.partition(_.references.subsetOf(joinedRefs))
      val joined = Join(left, right, Inner, joinConditions.reduceLeftOption(And))

      // should not have reference to same logical plan
      createOrderedJoin(Seq(joined) ++ rest.filterNot(_ eq right), others)
    }
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case j @ ExtractFiltersAndInnerJoins(input, conditions)
        if input.size > 2 && conditions.nonEmpty =>
      createOrderedJoin(input, conditions)
  }
}

/**
 * Elimination of outer joins, if the predicates can restrict the result sets so that
 * all null-supplying rows are eliminated
 *
 * - full outer -> inner if both sides have such predicates
 * - left outer -> inner if the right side has such predicates
 * - right outer -> inner if the left side has such predicates
 * - full outer -> left outer if only the left side has such predicates
 * - full outer -> right outer if only the right side has such predicates
 *
 * This rule should be executed before pushing down the Filter
 */
object OuterJoinElimination extends Rule[LogicalPlan] with PredicateHelper {

  /**
   * Returns whether the expression returns null or false when all inputs are nulls.
   */
  private def canFilterOutNull(e: Expression): Boolean = {
    if (!e.deterministic) return false
    val attributes = e.references.toSeq
    val emptyRow = new GenericInternalRow(attributes.length)
    val v = BindReferences.bindReference(e, attributes).eval(emptyRow)
    v == null || v == false
  }

  private def buildNewJoinType(filter: Filter, join: Join): JoinType = {
    val splitConjunctiveConditions: Seq[Expression] = splitConjunctivePredicates(filter.condition)
    val leftConditions = splitConjunctiveConditions
      .filter(_.references.subsetOf(join.left.outputSet))
    val rightConditions = splitConjunctiveConditions
      .filter(_.references.subsetOf(join.right.outputSet))

    val leftHasNonNullPredicate = leftConditions.exists(canFilterOutNull) ||
      filter.constraints.filter(_.isInstanceOf[IsNotNull])
        .exists(expr => join.left.outputSet.intersect(expr.references).nonEmpty)
    val rightHasNonNullPredicate = rightConditions.exists(canFilterOutNull) ||
      filter.constraints.filter(_.isInstanceOf[IsNotNull])
        .exists(expr => join.right.outputSet.intersect(expr.references).nonEmpty)

    join.joinType match {
      case RightOuter if leftHasNonNullPredicate => Inner
      case LeftOuter if rightHasNonNullPredicate => Inner
      case FullOuter if leftHasNonNullPredicate && rightHasNonNullPredicate => Inner
      case FullOuter if leftHasNonNullPredicate => LeftOuter
      case FullOuter if rightHasNonNullPredicate => RightOuter
      case o => o
    }
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case f @ Filter(condition, j @ Join(_, _, RightOuter | LeftOuter | FullOuter, _)) =>
      val newJoinType = buildNewJoinType(f, j)
      if (j.joinType == newJoinType) f else Filter(condition, j.copy(joinType = newJoinType))
  }
}

/**
 * Pushes down [[Filter]] operators where the `condition` can be
 * evaluated using only the attributes of the left or right side of a join.  Other
 * [[Filter]] conditions are moved into the `condition` of the [[Join]].
 *
 * And also pushes down the join filter, where the `condition` can be evaluated using only the
 * attributes of the left or right side of sub query when applicable.
 *
 * Check https://cwiki.apache.org/confluence/display/Hive/OuterJoinBehavior for more details
 */
object PushPredicateThroughJoin extends Rule[LogicalPlan] with PredicateHelper {
  /**
   * Splits join condition expressions into three categories based on the attributes required
   * to evaluate them.
   *
   * @return (canEvaluateInLeft, canEvaluateInRight, haveToEvaluateInBoth)
   */
  private def split(condition: Seq[Expression], left: LogicalPlan, right: LogicalPlan) = {
    val (leftEvaluateCondition, rest) =
        condition.partition(_.references subsetOf left.outputSet)
    val (rightEvaluateCondition, commonCondition) =
        rest.partition(_.references subsetOf right.outputSet)

    (leftEvaluateCondition, rightEvaluateCondition, commonCondition)
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    // push the where condition down into join filter
    case f @ Filter(filterCondition, Join(left, right, joinType, joinCondition)) =>
      val (leftFilterConditions, rightFilterConditions, commonFilterCondition) =
        split(splitConjunctivePredicates(filterCondition), left, right)

      joinType match {
        case Inner =>
          // push down the single side `where` condition into respective sides
          val newLeft = leftFilterConditions.
            reduceLeftOption(And).map(Filter(_, left)).getOrElse(left)
          val newRight = rightFilterConditions.
            reduceLeftOption(And).map(Filter(_, right)).getOrElse(right)
          val newJoinCond = (commonFilterCondition ++ joinCondition).reduceLeftOption(And)

          Join(newLeft, newRight, Inner, newJoinCond)
        case RightOuter =>
          // push down the right side only `where` condition
          val newLeft = left
          val newRight = rightFilterConditions.
            reduceLeftOption(And).map(Filter(_, right)).getOrElse(right)
          val newJoinCond = joinCondition
          val newJoin = Join(newLeft, newRight, RightOuter, newJoinCond)

          (leftFilterConditions ++ commonFilterCondition).
            reduceLeftOption(And).map(Filter(_, newJoin)).getOrElse(newJoin)
        case LeftOuter | LeftExistence(_) =>
          // push down the left side only `where` condition
          val newLeft = leftFilterConditions.
            reduceLeftOption(And).map(Filter(_, left)).getOrElse(left)
          val newRight = right
          val newJoinCond = joinCondition
          val newJoin = Join(newLeft, newRight, joinType, newJoinCond)

          (rightFilterConditions ++ commonFilterCondition).
            reduceLeftOption(And).map(Filter(_, newJoin)).getOrElse(newJoin)
        case FullOuter => f // DO Nothing for Full Outer Join
        case NaturalJoin(_) => sys.error("Untransformed NaturalJoin node")
        case UsingJoin(_, _) => sys.error("Untransformed Using join node")
      }

    // push down the join filter into sub query scanning if applicable
    case f @ Join(left, right, joinType, joinCondition) =>
      val (leftJoinConditions, rightJoinConditions, commonJoinCondition) =
        split(joinCondition.map(splitConjunctivePredicates).getOrElse(Nil), left, right)

      joinType match {
        case Inner | LeftExistence(_) =>
          // push down the single side only join filter for both sides sub queries
          val newLeft = leftJoinConditions.
            reduceLeftOption(And).map(Filter(_, left)).getOrElse(left)
          val newRight = rightJoinConditions.
            reduceLeftOption(And).map(Filter(_, right)).getOrElse(right)
          val newJoinCond = commonJoinCondition.reduceLeftOption(And)

          Join(newLeft, newRight, joinType, newJoinCond)
        case RightOuter =>
          // push down the left side only join filter for left side sub query
          val newLeft = leftJoinConditions.
            reduceLeftOption(And).map(Filter(_, left)).getOrElse(left)
          val newRight = right
          val newJoinCond = (rightJoinConditions ++ commonJoinCondition).reduceLeftOption(And)

          Join(newLeft, newRight, RightOuter, newJoinCond)
        case LeftOuter =>
          // push down the right side only join filter for right sub query
          val newLeft = left
          val newRight = rightJoinConditions.
            reduceLeftOption(And).map(Filter(_, right)).getOrElse(right)
          val newJoinCond = (leftJoinConditions ++ commonJoinCondition).reduceLeftOption(And)

          Join(newLeft, newRight, LeftOuter, newJoinCond)
        case FullOuter => f
        case NaturalJoin(_) => sys.error("Untransformed NaturalJoin node")
        case UsingJoin(_, _) => sys.error("Untransformed Using join node")
      }
  }
}

/**
 * Removes [[Cast Casts]] that are unnecessary because the input is already the correct type.
 */
object SimplifyCasts extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transformAllExpressions {
    case Cast(e, dataType) if e.dataType == dataType => e
  }
}

/**
 * Removes nodes that are not necessary.
 */
object RemoveDispensableExpressions extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transformAllExpressions {
    case UnaryPositive(child) => child
    case PromotePrecision(child) => child
  }
}

/**
 * Combines two adjacent [[Limit]] operators into one, merging the
 * expressions into one single expression.
 */
object CombineLimits extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case ll @ GlobalLimit(le, nl @ GlobalLimit(ne, grandChild)) =>
      GlobalLimit(Least(Seq(ne, le)), grandChild)
    case ll @ LocalLimit(le, nl @ LocalLimit(ne, grandChild)) =>
      LocalLimit(Least(Seq(ne, le)), grandChild)
    case ll @ Limit(le, nl @ Limit(ne, grandChild)) =>
      Limit(Least(Seq(ne, le)), grandChild)
  }
}

/**
 * Removes the inner case conversion expressions that are unnecessary because
 * the inner conversion is overwritten by the outer one.
 */
object SimplifyCaseConversionExpressions extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case q: LogicalPlan => q transformExpressionsUp {
      case Upper(Upper(child)) => Upper(child)
      case Upper(Lower(child)) => Upper(child)
      case Lower(Upper(child)) => Lower(child)
      case Lower(Lower(child)) => Lower(child)
    }
  }
}

/**
 * Speeds up aggregates on fixed-precision decimals by executing them on unscaled Long values.
 *
 * This uses the same rules for increasing the precision and scale of the output as
 * [[org.apache.spark.sql.catalyst.analysis.DecimalPrecision]].
 */
object DecimalAggregates extends Rule[LogicalPlan] {
  import Decimal.MAX_LONG_DIGITS

  /** Maximum number of decimal digits representable precisely in a Double */
  private val MAX_DOUBLE_DIGITS = 15

  def apply(plan: LogicalPlan): LogicalPlan = plan transformAllExpressions {
    case ae @ AggregateExpression(Sum(e @ DecimalType.Expression(prec, scale)), _, _, _)
      if prec + 10 <= MAX_LONG_DIGITS =>
      MakeDecimal(ae.copy(aggregateFunction = Sum(UnscaledValue(e))), prec + 10, scale)

    case ae @ AggregateExpression(Average(e @ DecimalType.Expression(prec, scale)), _, _, _)
      if prec + 4 <= MAX_DOUBLE_DIGITS =>
      val newAggExpr = ae.copy(aggregateFunction = Average(UnscaledValue(e)))
      Cast(
        Divide(newAggExpr, Literal.create(math.pow(10.0, scale), DoubleType)),
        DecimalType(prec + 4, scale + 4))
  }
}

/**
 * Converts local operations (i.e. ones that don't require data exchange) on LocalRelation to
 * another LocalRelation.
 *
 * This is relatively simple as it currently handles only a single case: Project.
 */
object ConvertToLocalRelation extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case Project(projectList, LocalRelation(output, data)) =>
      val projection = new InterpretedProjection(projectList, output)
      LocalRelation(projectList.map(_.toAttribute), data.map(projection))
  }
}

/**
 * Replaces logical [[Distinct]] operator with an [[Aggregate]] operator.
 * {{{
 *   SELECT DISTINCT f1, f2 FROM t  ==>  SELECT f1, f2 FROM t GROUP BY f1, f2
 * }}}
 */
object ReplaceDistinctWithAggregate extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case Distinct(child) => Aggregate(child.output, child.output, child)
  }
}

/**
 * Replaces logical [[Intersect]] operator with a left-semi [[Join]] operator.
 * {{{
 *   SELECT a1, a2 FROM Tab1 INTERSECT SELECT b1, b2 FROM Tab2
 *   ==>  SELECT DISTINCT a1, a2 FROM Tab1 LEFT SEMI JOIN Tab2 ON a1<=>b1 AND a2<=>b2
 * }}}
 *
 * Note:
 * 1. This rule is only applicable to INTERSECT DISTINCT. Do not use it for INTERSECT ALL.
 * 2. This rule has to be done after de-duplicating the attributes; otherwise, the generated
 *    join conditions will be incorrect.
 */
object ReplaceIntersectWithSemiJoin extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case Intersect(left, right) =>
      assert(left.output.size == right.output.size)
      val joinCond = left.output.zip(right.output).map { case (l, r) => EqualNullSafe(l, r) }
      Distinct(Join(left, right, LeftSemi, joinCond.reduceLeftOption(And)))
  }
}

/**
 * Removes literals from group expressions in [[Aggregate]], as they have no effect to the result
 * but only makes the grouping key bigger.
 */
object RemoveLiteralFromGroupExpressions extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case a @ Aggregate(grouping, _, _) =>
      val newGrouping = grouping.filter(!_.foldable)
      a.copy(groupingExpressions = newGrouping)
  }
}

/**
 * Computes the current date and time to make sure we return the same result in a single query.
 */
object ComputeCurrentTime extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = {
    val dateExpr = CurrentDate()
    val timeExpr = CurrentTimestamp()
    val currentDate = Literal.create(dateExpr.eval(EmptyRow), dateExpr.dataType)
    val currentTime = Literal.create(timeExpr.eval(EmptyRow), timeExpr.dataType)

    plan transformAllExpressions {
      case CurrentDate() => currentDate
      case CurrentTimestamp() => currentTime
    }
  }
}

/**
 * Typed [[Filter]] is by default surrounded by a [[DeserializeToObject]] beneath it and a
 * [[SerializeFromObject]] above it.  If these serializations can't be eliminated, we should embed
 * the deserializer in filter condition to save the extra serialization at last.
 */
object EmbedSerializerInFilter extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case s @ SerializeFromObject(_, Filter(condition, d: DeserializeToObject)) =>
      val numObjects = condition.collect {
        case a: Attribute if a == d.output.head => a
      }.length

      if (numObjects > 1) {
        // If the filter condition references the object more than one times, we should not embed
        // deserializer in it as the deserialization will happen many times and slow down the
        // execution.
        // TODO: we can still embed it if we can make sure subexpression elimination works here.
        s
      } else {
        val newCondition = condition transform {
          case a: Attribute if a == d.output.head => d.deserializer.child
        }
        Filter(newCondition, d.child)
      }
  }
}
