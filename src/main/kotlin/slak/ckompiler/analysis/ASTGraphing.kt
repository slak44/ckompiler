package slak.ckompiler.analysis

import mu.KotlinLogging
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

private val logger = KotlinLogging.logger("ASTGraphing")

fun graph(cfg: CFG) {
  for (p in cfg.f.parameters) cfg.definitions[p to p.id] = mutableSetOf()
  GraphingContext(root = cfg).graphCompound(cfg.startBlock, cfg.f.block)
}

/**
 * The current context in which the [CFG] is being built. Tracks relevant loop blocks, and possible
 * labels to jump to using goto.
 */
private data class GraphingContext(val root: CFG,
                                   val currentLoopBlock: BasicBlock? = null,
                                   val loopAfterBlock: BasicBlock? = null,
                                   val labels: MutableMap<String, BasicBlock> = mutableMapOf()) {
  fun labelBlockFor(labelName: String): BasicBlock {
    val blockOrNull = labels[labelName]
    if (blockOrNull == null) {
      val block = root.newBlock()
      labels[labelName] = block
      return block
    }
    return blockOrNull
  }
}

private fun GraphingContext.graphCompound(current: BasicBlock,
                                          compoundStatement: CompoundStatement): BasicBlock {
  var block = current
  for ((name) in compoundStatement.scope.labels) {
    labelBlockFor(name)
  }
  for (item in compoundStatement.items) {
    if (item is StatementItem) {
      block = graphStatement(compoundStatement.scope, block, item.statement)
    } else {
      item as DeclarationItem
      root.addDeclaration(compoundStatement.scope, block, item.declaration)
    }
  }
  return block
}

private fun CFG.addDefinition(current: BasicBlock, ident: TypedIdentifier) {
  val k = ident to ident.id
  if (!definitions.containsKey(k)) definitions[k] = mutableSetOf(current)
  else definitions[k]!! += current
}

private fun CFG.addDeclaration(parent: LexicalScope, current: BasicBlock, d: Declaration) {
  for ((ident, init) in d.idents(parent).zip(d.declaratorList.map { it.second })) {
    if (definitions.containsKey(ident to ident.id)) {
      logger.throwICE("Redefinition of declaration") { ident }
    }
    definitions[ident to ident.id] = mutableSetOf(current)
    transformInitializer(current, ident, init)
  }
}

/** Reduce an [Initializer] to a series of synthetic [Expression]s. */
private fun transformInitializer(current: BasicBlock,
                                 ident: TypedIdentifier,
                                 init: Initializer?): Unit = when (init) {
  null -> {
    // No initializer, nothing to output
  }
  is ExpressionInitializer -> {
    // Transform this into an assignment expression
    current.data += BinaryExpression(BinaryOperators.ASSIGN, ident, init.expr)
        .withRange(ident..init.expr)
  }
//  else -> TODO("only expression initializers are implemented; see SyntaxTreeModel")
}

/**
 * A bunch of expressions that should be equivalent to the original expression that was
 * sequentialized.
 *
 * What remains of the original expression after sequentialization will be the middle element of the
 * triple. The other lists can be empty.
 *
 * The middle element acts as a sequence point for the other 2 lists.
 */
typealias SequentialExpression = Triple<List<Expression>, Expression, List<Expression>>

/**
 * Resolve sequencing issues within an expression.
 *
 * Assignment expressions have the value of the left operand after the assignment; we are allowed
 * by note 111 to read the stored object to determine the value, which means we can put the
 * assignment first, and replace it with a read of its target.
 *
 * According to 6.5.3.1.0.2, prefix ++ and -- are equivalent to `(E+=1)` and `(E-=1)`, so the same
 * considerations for assignment expressions apply for them too.
 *
 * According to 6.5.2.4.0.2, for postfix ++ and --, updating the value of the operand is sequenced
 * after returning the result, so we are allowed to move the update after the expression.
 *
 * C standard: C.1, 5.1.2.3, 6.5.16.0.3, 6.5.3.1.0.2, 6.5.2.4.0.2
 * @see SequentialExpression
 */
private fun sequentialize(expr: Expression): SequentialExpression {
  val sequencedBefore = mutableListOf<Expression>()
  val sequencedAfter = mutableListOf<Expression>()
  val modifications = mutableMapOf<TypedIdentifier, MutableList<Expression>>()
  fun Expression.seqImpl(): Expression = when (this) {
    is ErrorExpression -> logger.throwICE("ErrorExpression was removed") {}
    is FunctionCall -> {
      // FIXME: definitely has other sequencing issues that aren't handled
      FunctionCall(calledExpr.seqImpl(), args.map(Expression::seqImpl)).withRange(tokenRange)
    }
    is PrefixIncrement, is PrefixDecrement, is PostfixIncrement, is PostfixDecrement -> {
      val incDec = (this as IncDecOperation).expr
      if (incDec is TypedIdentifier) {
        modifications.getOrPut(incDec, ::mutableListOf).add(this)
      }
      if (this is PrefixIncrement || this is PrefixDecrement) sequencedBefore += this
      else sequencedAfter += this
      incDec.seqImpl()
    }
    is BinaryExpression -> {
      /* FIXME: there are sequence points with && || (also short-circuiting)
          so we can't just pull out the assignment in something like a == null && a = 42
       */
      if (op in assignmentOps) {
        if (lhs is TypedIdentifier) {
          modifications.getOrPut(lhs, ::mutableListOf).add(this)
        }
        // Hoist assignments out of expressions
        sequencedBefore += this
        lhs.seqImpl()
      } else {
        BinaryExpression(op, lhs.seqImpl(), rhs.seqImpl()).withRange(tokenRange)
      }
    }
    is UnaryExpression,
    is SizeofExpression, is SizeofTypeName, is TypedIdentifier, is IntegerConstantNode,
    is FloatingConstantNode, is CharacterConstantNode, is StringLiteralNode -> {
      // Do nothing. These do not pose the problem of being sequenced before or after.
      this
    }
  }
  val remaining = expr.seqImpl()
  for ((variable, modList) in modifications) {
    if (modList.size > 1) {
      // FIXME: insert diagnostic about multiple unsequenced modifications to variable
    }
  }
  return SequentialExpression(sequencedBefore, remaining, sequencedAfter)
}

private operator fun SequentialExpression.iterator(): Iterator<Expression> = iterator {
  yieldAll(first.iterator())
  yield(second)
  yieldAll(third.iterator())
}

private fun GraphingContext.processExpr(current: BasicBlock, e: Expression): SequentialExpression {
  val seqExpr = sequentialize(e)
  for (expr in seqExpr) {
    if (expr !is BinaryExpression || expr.op !in assignmentOps) continue
    // FIXME: a bunch of other things can be on the left side of an =
    if (expr.lhs !is TypedIdentifier) logger.throwICE("Unimplemented branch") { expr }
    root.addDefinition(current, expr.lhs)
  }
  return seqExpr
}

/**
 * Add sequenced code to proper locations, and return what's left of the [cond] expression.
 */
private fun GraphingContext.processCondition(current: BasicBlock, cond: Expression): Expression {
  val sequencedCond = processExpr(current, cond)
  // Disgusting black magic ahead
  val newCond = if (sequencedCond.third.isNotEmpty()) {
    // All names starting with __ are reserved, so we should be safe doing this
    // Variables are identified by id from here on anyway
    val syntheticCondVar = TypedIdentifier("__synthetic_cond_${cond.hashCode()}", BooleanType)
    current.data += BinaryExpression(BinaryOperators.ASSIGN, syntheticCondVar, sequencedCond.second)
    current.data += sequencedCond.third
    syntheticCondVar
  } else {
    sequencedCond.second
  }
  current.data += sequencedCond.first
  return newCond
}

private fun GraphingContext.graphStatement(scope: LexicalScope,
                                           current: BasicBlock,
                                           s: Statement): BasicBlock = when (s) {
  is ErrorStatement,
  is ErrorExpression -> logger.throwICE("ErrorNode in CFG creation") { "$current/$s" }
  is Expression -> {
    val sequenced = processExpr(current, s)
    current.data += sequenced.first
    current.data += sequenced.second
    current.data += sequenced.third
    current
  }
  is Noop -> {
    // Intentionally left empty
    current
  }
  is LabeledStatement -> {
    val blockWithLabel = labelBlockFor(s.label.name)
    current.terminator = UncondJump(blockWithLabel)
    val nextBlock = graphStatement(scope, blockWithLabel, s.statement)
    nextBlock
  }
  is CompoundStatement -> graphCompound(current, s)
  is IfStatement -> {
    val ifBlock = root.newBlock()
    val elseBlock = root.newBlock()
    val ifNext = graphStatement(scope, ifBlock, s.success)
    val elseNext = s.failure?.let { graphStatement(scope, elseBlock, it) }
    val afterIfBlock = root.newBlock()
    current.terminator = run {
      val falseBlock = if (elseNext != null) elseBlock else afterIfBlock
      CondJump(processCondition(current, s.cond), ifBlock, falseBlock)
    }
    ifNext.terminator = UncondJump(afterIfBlock)
    elseNext?.terminator = UncondJump(afterIfBlock)
    afterIfBlock
  }
  is SwitchStatement -> TODO("implement switches")
  is WhileStatement -> {
    val loopHeader = root.newBlock()
    val loopBlock = root.newBlock()
    val afterLoopBlock = root.newBlock()
    val loopContext = copy(currentLoopBlock = loopBlock, loopAfterBlock = afterLoopBlock)
    val loopNext = loopContext.graphStatement(scope, loopBlock, s.loopable)
    loopHeader.terminator =
        CondJump(processCondition(loopHeader, s.cond), loopBlock, afterLoopBlock)
    current.terminator = UncondJump(loopHeader)
    loopNext.terminator = UncondJump(loopHeader)
    afterLoopBlock
  }
  is DoWhileStatement -> {
    val loopBlock = root.newBlock()
    val afterLoopBlock = root.newBlock()
    val loopContext = copy(currentLoopBlock = loopBlock, loopAfterBlock = afterLoopBlock)
    val loopNext = loopContext.graphStatement(scope, loopBlock, s.loopable)
    current.terminator = UncondJump(loopBlock)
    loopNext.terminator = CondJump(processCondition(loopNext, s.cond), loopBlock, afterLoopBlock)
    afterLoopBlock
  }
  is ForStatement -> {
    when (s.init) {
      is EmptyInitializer -> {
        // Intentionally left empty
      }
      is ErrorInitializer -> logger.throwICE("ErrorNode in CFG creation") { "$current/$s" }
      is ForExpressionInitializer -> graphStatement(scope, current, s.init.value)
      is DeclarationInitializer -> root.addDeclaration(scope, current, s.init.value)
    }
    val loopHeader = root.newBlock()
    val loopBlock = root.newBlock()
    val afterLoopBlock = root.newBlock()
    val loopContext = copy(currentLoopBlock = loopBlock, loopAfterBlock = afterLoopBlock)
    val loopNext = loopContext.graphStatement(scope, loopBlock, s.loopable)
    s.loopEnd?.let { graphStatement(scope, loopNext, it) }
    current.terminator = UncondJump(loopHeader)
    if (s.cond == null) {
      // No for condition means unconditional jump to loop block
      loopHeader.terminator = UncondJump(loopBlock)
    } else {
      loopHeader.terminator =
          CondJump(processCondition(loopHeader, s.cond), loopBlock, afterLoopBlock)
    }
    loopNext.terminator = UncondJump(loopHeader)
    afterLoopBlock
  }
  is ContinueStatement -> {
    val afterContinue = root.newBlock()
    current.terminator = ConstantJump(currentLoopBlock!!, afterContinue)
    afterContinue
  }
  is BreakStatement -> {
    val afterBreak = root.newBlock()
    current.terminator = ConstantJump(loopAfterBlock!!, afterBreak)
    afterBreak
  }
  is GotoStatement -> {
    val labelBlock = labelBlockFor(s.identifier.name)
    val afterGoto = root.newBlock()
    current.terminator = ConstantJump(labelBlock, afterGoto)
    afterGoto
  }
  is ReturnStatement -> {
    val deadCodeBlock = root.newBlock()
    current.terminator = ImpossibleJump(deadCodeBlock, s.expr)
    deadCodeBlock
  }
}
