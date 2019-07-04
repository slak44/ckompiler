package slak.ckompiler.analysis

import org.apache.logging.log4j.LogManager
import slak.ckompiler.parser.*
import slak.ckompiler.throwICE

private val logger = LogManager.getLogger("ASTGraphing")

fun graph(cfg: CFG) {
  for (p in cfg.f.parameters) cfg.definitions[p.toUniqueId()] = mutableSetOf()
  GraphingContext(root = cfg).graphCompound(cfg.startBlock, cfg.f.block)
  for (block in cfg.allNodes) {
    val definitions = block.irContext.ir.mapNotNull { it as? Store }.filterNot { it.isSynthetic }
    // FIXME: replace TypedIdentifier everywhere
    for (def in definitions) cfg.addDefinition(block, def.target.id)
  }
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
  val k = ident.toUniqueId()
  if (!definitions.containsKey(k)) definitions[k] = mutableSetOf(current)
  else definitions[k]!! += current
}

private fun CFG.addDeclaration(parent: LexicalScope, current: BasicBlock, d: Declaration) {
  for ((ident, init) in d.idents(parent).zip(d.declaratorList.map { it.second })) {
    if (definitions.containsKey(ident.toUniqueId())) {
      logger.throwICE("Redefinition of declaration") { ident }
    }
    definitions[ident.toUniqueId()] = mutableSetOf(current)
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
    current.irContext.buildIR(BinaryExpression(BinaryOperators.ASSIGN, ident, init.expr)
        .withRange(ident..init.expr))
  }
//  else -> TODO("only expression initializers are implemented; see SyntaxTreeModel")
}

/**
 * Create separate lowering context for conditions/returns in the same block.
 */
private fun GraphingContext.processExtraExpressions(current: BasicBlock,
                                                    extra: Expression): IRLoweringContext {
  val extraCtx = IRLoweringContext(current.irContext)
  extraCtx.buildIR(root.sequentialize(extra).toList())
  return extraCtx
}

private fun GraphingContext.graphStatement(scope: LexicalScope,
                                           current: BasicBlock,
                                           s: Statement): BasicBlock = when (s) {
  is ErrorStatement,
  is ErrorExpression -> logger.throwICE("ErrorNode in CFG creation") { "$current/$s" }
  is Expression -> {
    current.irContext.buildIR(root.sequentialize(s).toList())
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
      CondJump(processExtraExpressions(current, s.cond), ifBlock, falseBlock)
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
        CondJump(processExtraExpressions(loopHeader, s.cond), loopBlock, afterLoopBlock)
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
    loopNext.terminator =
        CondJump(processExtraExpressions(loopNext, s.cond), loopBlock, afterLoopBlock)
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
          CondJump(processExtraExpressions(loopHeader, s.cond), loopBlock, afterLoopBlock)
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
    val returnContext = s.expr?.let { processExtraExpressions(current, it) }
    current.terminator = ImpossibleJump(deadCodeBlock, returnContext)
    deadCodeBlock
  }
}
