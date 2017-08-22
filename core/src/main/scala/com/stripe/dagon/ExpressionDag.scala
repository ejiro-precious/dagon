/*
 Copyright 2014 Twitter, Inc.
 Copyright 2017 Stripe, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.stripe.dagon

sealed trait ExpressionDag[N[_]] { self =>
  // Once we fix N above, we can make E[T] = Expr[T, N]
  type E[t] = Expr[t, N]
  type Lit[t] = Literal[t, N]

  /**
   * These have package visibility to test
   * the law that for all Expr, the node they
   * evaluate to is unique
   */
  protected[dagon] def idToExp: HMap[Id, E]
  protected def nodeToLiteral: GenFunction[N, Lit]
  protected def roots: Set[Id[_]]
  protected def nextId: Int

  private def copy(id2Exp: HMap[Id, E] = self.idToExp,
    node2Literal: GenFunction[N, Lit] = self.nodeToLiteral,
    gcroots: Set[Id[_]] = self.roots,
    id: Int = self.nextId): ExpressionDag[N] = new ExpressionDag[N] {
    def idToExp = id2Exp
    def roots = gcroots
    def nodeToLiteral = node2Literal
    def nextId = id
  }

  override def toString: String =
    "ExpressionDag(idToExp = %s)".format(idToExp)

  // This is a cache of Id[T] => Option[N[T]]
  private val idToN =
    new HCache[Id, ({ type ON[T] = Option[N[T]] })#ON]()
  private val nodeToId =
    new HCache[N, ({ type OID[T] = Option[Id[T]] })#OID]()

  /**
   * Add a GC root, or tail in the DAG, that can never be deleted
   * currently, we only support a single root
   */
  private def addRoot[_](id: Id[_]) = copy(gcroots = roots + id)

  /**
   * Which ids are reachable from the roots
   */
  private def reachableIds: Set[Id[_]] = {
    // We actually don't care about the return type of the Set
    // This is a constant function at the type level
    type IdSet[t] = Set[Id[_]]
    def expand(s: Set[Id[_]]): Set[Id[_]] = {
      val partial = new GenPartial[HMap[Id, E]#Pair, IdSet] {
        def apply[T] = {
          case (id, Const(_)) if s(id) => s
          case (id, Var(v)) if s(id) => s + v
          case (id, Unary(id0, _)) if s(id) => s + id0
          case (id, Binary(id0, id1, _)) if s(id) => (s + id0) + id1
        }
      }
      // Note this Stream must always be non-empty as long as roots are
      // TODO: we don't need to use collect here, just .get on each id in s
      idToExp.collect[IdSet](partial)
        .reduce(_ ++ _)
    }
    // call expand while we are still growing
    def go(s: Set[Id[_]]): Set[Id[_]] = {
      val step = expand(s)
      if (step == s) s
      else go(step)
    }
    go(roots)
  }

  private def gc: ExpressionDag[N] = {
    val goodIds = reachableIds
    type BoolT[t] = Boolean
    val toKeepI2E = idToExp.filter(new GenFunction[HMap[Id, E]#Pair, BoolT] {
      def apply[T] = { idExp => goodIds(idExp._1) }
    })
    copy(id2Exp = toKeepI2E)
  }

  /**
   * Apply the given rule to the given dag until
   * the graph no longer changes.
   */
  def apply(rule: Rule[N]): ExpressionDag[N] = {
    // for some reason, scala can't optimize this with tailrec
    var prev: ExpressionDag[N] = null
    var curr: ExpressionDag[N] = this
    while (!(curr eq prev)) {
      prev = curr
      curr = curr.applyOnce(rule)
    }
    curr
  }

  /**
   * Convert a N[T] to a Literal[T, N]
   */
  def toLiteral[T](n: N[T]): Literal[T, N] = nodeToLiteral.apply[T](n)

  /**
   * apply the rule at the first place that satisfies
   * it, and return from there.
   */
  def applyOnce(rule: Rule[N]): ExpressionDag[N] = {
    val getN = new GenPartial[HMap[Id, E]#Pair, HMap[Id, N]#Pair] {
      def apply[U] = {
        val fn = rule.apply[U](self)

        def ruleApplies(id: Id[U]): Boolean = {
          val n = evaluate(id)
          fn(n) match {
            case Some(n1) => n != n1
            case None => false
          }
        }


        {
          case (id, _) if ruleApplies(id) =>
            // Sucks to have to call fn, twice, but oh well
            (id, fn(evaluate(id)).get)
        }
      }
    }
    idToExp.collect[HMap[Id, N]#Pair](getN).headOption match {
      case None => this
      case Some(tup) =>
        // some type hand holding
        def act[T](in: HMap[Id, N]#Pair[T]): ExpressionDag[N] = {
          /*
           * We can't delete Ids which may have been shared
           * publicly, and the ids may be embedded in many
           * nodes. Instead we remap this i to be a pointer
           * to the newid.
           */
          val (i, n) = in
          val (dag, newId) = ensure(n)
          dag.copy(id2Exp = dag.idToExp + (i -> Var[T, N](newId)))
        }
        // This cast should not be needed
        act(tup.asInstanceOf[HMap[Id, N]#Pair[Any]]).gc
    }
  }

  /**
   * This is only called by ensure
   *
   * Note, Expr must never be a Var
   */
  private def addExp[T](node: N[T], exp: Expr[T, N]): (ExpressionDag[N], Id[T]) = {
    require(!exp.isInstanceOf[Var[T, N]])

    find(node) match {
      case None =>
        val nodeId = Id[T](nextId)
        (copy(id2Exp = idToExp + (nodeId -> exp), id = nextId + 1), nodeId)
      case Some(id) =>
        (this, id)
    }
  }

  /**
   * This finds the Id[T] in the current graph that is equivalent
   * to the given N[T]
   */
  def find[T](node: N[T]): Option[Id[T]] = nodeToId.getOrElseUpdate(node, {
    val partial = new GenPartial[HMap[Id, E]#Pair, Id] {
      def apply[T1] = {
        // Make sure to return the original Id, not a Id -> Var -> Expr
        case (thisId, expr) if !expr.isInstanceOf[Var[_, N]] && node == expr.evaluate(idToExp) => thisId
      }
    }
    idToExp.collect(partial).toList match {
      case Nil => None
      case id :: Nil =>
        // this cast is safe if node == expr.evaluate(idToExp) implies types match
        Some(id).asInstanceOf[Option[Id[T]]]
      case others => None//sys.error(s"logic error, should only be one mapping: $node -> $others")
    }
  })

  /**
   * This throws if the node is missing, use find if this is not
   * a logic error in your programming. With dependent types we could
   * possibly get this to not compile if it could throw.
   */
  def idOf[T](node: N[T]): Id[T] =
    find(node)
      .getOrElse(sys.error("could not get node: %s\n from %s".format(node, this)))

  /**
   * ensure the given literal node is present in the Dag
   * Note: it is important that at each moment, each node has
   * at most one id in the graph. Put another way, for all
   * Id[T] in the graph evaluate(id) is distinct.
   */
  protected def ensure[T](node: N[T]): (ExpressionDag[N], Id[T]) =
    find(node) match {
      case Some(id) => (this, id)
      case None => {
        val lit: Lit[T] = toLiteral(node)
        lit match {
          case ConstLit(n) =>
            /**
             * Since the code is not performance critical, but correctness critical, and we can't
             * check this property with the typesystem easily, check it here
             */
            require(n == node,
              "Equality or nodeToLiteral is incorrect: nodeToLit(%s) = ConstLit(%s)".format(node, n))
            addExp(node, Const(n))
          case UnaryLit(prev, fn) =>
            val (exp1, idprev) = ensure(prev.evaluate)
            exp1.addExp(node, Unary(idprev, fn))
          case BinaryLit(n1, n2, fn) =>
            val (exp1, id1) = ensure(n1.evaluate)
            val (exp2, id2) = exp1.ensure(n2.evaluate)
            exp2.addExp(node, Binary(id1, id2, fn))
        }
      }
    }

  /**
   * After applying rules to your Dag, use this method
   * to get the original node type.
   * Only call this on an Id[T] that was generated by
   * this dag or a parent.
   */
  def evaluate[T](id: Id[T]): N[T] =
    evaluateOption(id).getOrElse(sys.error("Could not evaluate: %s\nin %s".format(id, this)))

  def evaluateOption[T](id: Id[T]): Option[N[T]] =
    idToN.getOrElseUpdate(id, {
      idToExp.get(id).map(_.evaluate(idToExp))
    })

  /**
   * Return the number of nodes that depend on the
   * given Id, TODO we might want to cache these.
   * We need to garbage collect nodes that are
   * no longer reachable from the root
   */
  def fanOut(id: Id[_]): Int =
    evaluateOption(id)
      .map(fanOut)
      .getOrElse(0)

  @annotation.tailrec
  private def dependsOn(expr: Expr[_, N], node: N[_]): Boolean = expr match {
    case Const(_) => false
    case Var(id) => dependsOn(idToExp(id), node)
    case Unary(id, _) => evaluate(id) == node
    case Binary(id0, id1, _) => evaluate(id0) == node || evaluate(id1) == node
  }

  /**
   * Returns 0 if the node is absent, which is true
   * use .contains(n) to check for containment
   */
  def fanOut(node: N[_]): Int = {
    val pointsToNode = new GenPartial[HMap[Id, E]#Pair, N] {
      def apply[T] = {
        case (id, expr) if dependsOn(expr, node) => evaluate(id)
      }
    }
    idToExp.collect[N](pointsToNode).toSet.size
  }
  def contains(node: N[_]): Boolean = find(node).isDefined
}

object ExpressionDag {
  private def empty[N[_]](n2l: GenFunction[N, ({ type L[t] = Literal[t, N] })#L]): ExpressionDag[N] =
    new ExpressionDag[N] {
      val idToExp = HMap.empty[Id, ({ type E[t] = Expr[t, N] })#E]
      val nodeToLiteral = n2l
      val roots = Set.empty[Id[_]]
      val nextId = 0
    }

  /**
   * This creates a new ExpressionDag rooted at the given tail node
   */
  def apply[T, N[_]](n: N[T],
    nodeToLit: GenFunction[N, ({ type L[t] = Literal[t, N] })#L]): (ExpressionDag[N], Id[T]) = {
    val (dag, id) = empty(nodeToLit).ensure(n)
    (dag.addRoot(id), id)
  }

  /**
   * This is the most useful function. Given a N[T] and a way to convert to Literal[T, N],
   * apply the given rule until it no longer applies, and return the N[T] which is
   * equivalent under the given rule
   */
  def applyRule[T, N[_]](n: N[T],
    nodeToLit: GenFunction[N, ({ type L[t] = Literal[t, N] })#L],
    rule: Rule[N]): N[T] = {
    val (dag, id) = apply(n, nodeToLit)
    dag(rule).evaluate(id)
  }
}

/**
 * This implements a simplification rule on ExpressionDags
 */
trait Rule[N[_]] { self =>
  /**
   * If the given Id can be replaced with a simpler expression,
   * return Some(expr) else None.
   *
   * If it is convenient, you might write a partial function
   * and then call .lift to get the correct Function type
   */
  def apply[T](on: ExpressionDag[N]): (N[T] => Option[N[T]])

  // If the current rule cannot apply, then try the argument here
  def orElse(that: Rule[N]): Rule[N] = new Rule[N] {
    def apply[T](on: ExpressionDag[N]) = { n =>
      self.apply(on)(n).orElse(that.apply(on)(n))
    }

    override def toString: String =
      s"$self.orElse($that)"
  }
}

/**
 * Often a partial function is an easier way to express rules
 */
trait PartialRule[N[_]] extends Rule[N] {
  final def apply[T](on: ExpressionDag[N]) = applyWhere[T](on).lift
  def applyWhere[T](on: ExpressionDag[N]): PartialFunction[N[T], N[T]]
}

