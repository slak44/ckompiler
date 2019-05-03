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

fun getAssignmentTarget(e: BinaryExpression): TypedIdentifier? {
  if (e.op in assignmentOps) {
    if (e.lhs is TypedIdentifier) {
      return e.lhs
    } else {
      // FIXME: a bunch of other things can be on the left side of an =
      logger.error { "Unimplemented branch" }
    }
  }
  return null
}

private fun CFG.findAssignmentTargets(current: BasicBlock, e: Expression): Unit = when (e) {
  is ErrorExpression -> logger.throwICE("ErrorExpression was removed") {}
  is BinaryExpression -> {
    getAssignmentTarget(e)?.let { addDefinition(current, it) }
    findAssignmentTargets(current, e.lhs)
    findAssignmentTargets(current, e.rhs)
  }
  is FunctionCall -> {
    findAssignmentTargets(current, e.calledExpr)
    for (arg in e.args) findAssignmentTargets(current, arg)
  }
  is UnaryExpression -> findAssignmentTargets(current, e.operand)
  is SizeofExpression -> findAssignmentTargets(current, e.sizeExpr)
  is PrefixIncrement -> findAssignmentTargets(current, e.expr)
  is PrefixDecrement -> findAssignmentTargets(current, e.expr)
  is PostfixIncrement -> findAssignmentTargets(current, e.expr)
  is PostfixDecrement -> findAssignmentTargets(current, e.expr)
  is TypedIdentifier, is IntegerConstantNode, is FloatingConstantNode,
  is CharacterConstantNode, is StringLiteralNode, is SizeofTypeName -> Unit
}

private fun GraphingContext.graphStatement(scope: LexicalScope,
                                           current: BasicBlock,
                                           s: Statement): BasicBlock = when (s) {
  is ErrorStatement,
  is ErrorExpression -> logger.throwICE("ErrorNode in CFG creation") { "$current/$s" }
  is Expression -> {
    root.findAssignmentTargets(current, s)
    current.data += s
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
      root.findAssignmentTargets(current, s.cond)
      CondJump(s.cond, ifBlock, falseBlock)
    }
    ifNext.terminator = UncondJump(afterIfBlock)
    elseNext?.terminator = UncondJump(afterIfBlock)
    afterIfBlock
  }
  is SwitchStatement -> TODO("implement switches")
  is WhileStatement -> {
    val loopBlock = root.newBlock()
    val afterLoopBlock = root.newBlock()
    val loopContext = copy(currentLoopBlock = loopBlock, loopAfterBlock = afterLoopBlock)
    val loopNext = loopContext.graphStatement(scope, loopBlock, s.loopable)
    root.findAssignmentTargets(current, s.cond)
    current.terminator = CondJump(s.cond, loopBlock, afterLoopBlock)
    loopNext.terminator = UncondJump(current)
    afterLoopBlock
  }
  is DoWhileStatement -> {
    val loopBlock = root.newBlock()
    val afterLoopBlock = root.newBlock()
    val loopContext = copy(currentLoopBlock = loopBlock, loopAfterBlock = afterLoopBlock)
    val loopNext = loopContext.graphStatement(scope, loopBlock, s.loopable)
    current.terminator = UncondJump(loopBlock)
    root.findAssignmentTargets(current, s.cond)
    loopNext.terminator = CondJump(s.cond, loopBlock, afterLoopBlock)
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
    val loopBlock = root.newBlock()
    val afterLoopBlock = root.newBlock()
    val loopContext = copy(currentLoopBlock = loopBlock, loopAfterBlock = afterLoopBlock)
    val loopNext = loopContext.graphStatement(scope, loopBlock, s.loopable)
    s.loopEnd?.let { graphStatement(scope, loopNext, it) }
    if (s.cond == null) {
      // No for condition means unconditional jump to loop block
      current.terminator = UncondJump(loopBlock)
    } else {
      root.findAssignmentTargets(current, s.cond)
      current.terminator = CondJump(s.cond, loopBlock, afterLoopBlock)
    }
    loopNext.terminator = current.terminator
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
