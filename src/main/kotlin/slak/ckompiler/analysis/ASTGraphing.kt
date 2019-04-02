package slak.ckompiler.analysis

import mu.KotlinLogging
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

private val logger = KotlinLogging.logger("ASTGraphing")

fun graph(cfg: CFG) {
  GraphingContext(root = cfg).graphCompound(cfg.startBlock, cfg.f.block)
}

/**
 * The current context in which the CFG is being built. Tracks relevant loop blocks, and possible
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
      block = graphStatement(block, item.statement)
    } else {
      item as DeclarationItem
      block.addDeclaration(item.declaration)
    }
  }
  return block
}

private fun BasicBlock.addDeclaration(d: Declaration) {
  val idents = d.identifiers().map(::Definition)
  val exprs = idents
      .zip(d.declaratorList.map { it.second })
      .flatMap { transformInitializer(it.first, it.second) }
  definitions += idents
  data += exprs
}

/** Reduce an [Initializer] to a series of synthetic [Expression]s. */
private fun transformInitializer(def: Definition,
                                 init: Initializer?): List<Expression> = when (init) {
  null -> {
    // No initializer, nothing to output
    emptyList()
  }
  is ExpressionInitializer -> {
    // Transform this into an assignment expression
    listOf(BinaryExpression(BinaryOperators.ASSIGN, def.ident, init.expr)
        .withRange(def.ident.tokenRange..init.expr.tokenRange))
  }
//  else -> TODO("only expression initializers are implemented; see SyntaxTreeModel")
}

private fun GraphingContext.graphStatement(current: BasicBlock,
                                           s: Statement): BasicBlock = when (s) {
  is ErrorStatement,
  is ErrorExpression -> logger.throwICE("ErrorNode in CFG creation") { "$current/$s" }
  is Expression -> {
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
    val nextBlock = graphStatement(blockWithLabel, s.statement)
    nextBlock
  }
  is CompoundStatement -> graphCompound(current, s)
  is IfStatement -> {
    val ifBlock = root.newBlock()
    val elseBlock = root.newBlock()
    val ifNext = graphStatement(ifBlock, s.success)
    val elseNext = s.failure?.let { graphStatement(elseBlock, it) }
    val afterIfBlock = root.newBlock()
    current.terminator = run {
      val falseBlock = if (elseNext != null) elseBlock else afterIfBlock
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
    val loopNext = loopContext.graphStatement(loopBlock, s.loopable)
    current.terminator = CondJump(s.cond, loopBlock, afterLoopBlock)
    loopNext.terminator = UncondJump(current)
    afterLoopBlock
  }
  is DoWhileStatement -> {
    val loopBlock = root.newBlock()
    val afterLoopBlock = root.newBlock()
    val loopContext = copy(currentLoopBlock = loopBlock, loopAfterBlock = afterLoopBlock)
    val loopNext = loopContext.graphStatement(loopBlock, s.loopable)
    current.terminator = UncondJump(loopBlock)
    loopNext.terminator = CondJump(s.cond, loopBlock, afterLoopBlock)
    afterLoopBlock
  }
  is ForStatement -> {
    when (s.init) {
      is EmptyInitializer -> {
        // Intentionally left empty
      }
      is ErrorInitializer -> logger.throwICE("ErrorNode in CFG creation") { "$current/$s" }
      is ForExpressionInitializer -> current.data += s.init.value
      is DeclarationInitializer -> current.addDeclaration(s.init.value)
    }
    val loopBlock = root.newBlock()
    val afterLoopBlock = root.newBlock()
    val loopContext = copy(currentLoopBlock = loopBlock, loopAfterBlock = afterLoopBlock)
    val loopNext = loopContext.graphStatement(loopBlock, s.loopable)
    s.loopEnd?.let { graphStatement(loopNext, it) }
    if (s.cond == null) {
      // No for condition means unconditional jump to loop block
      current.terminator = UncondJump(loopBlock)
    } else {
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
