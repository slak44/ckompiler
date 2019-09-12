package slak.ckompiler.analysis

import org.apache.logging.log4j.LogManager
import slak.ckompiler.parser.*
import slak.ckompiler.rangeTo
import slak.ckompiler.throwICE

private val logger = LogManager.getLogger()

fun graph(cfg: CFG) {
  for (p in cfg.f.parameters) {
    cfg.definitions[ComputeReference(p, isSynthetic = false)] = mutableSetOf()
  }
  GraphingContext(root = cfg).graphCompound(cfg.startBlock, cfg.f.block)
  val synthList = mutableListOf<String>()
  for (block in cfg.allNodes) {
    for (instr in block.instructions) {
      if (instr !is Store) continue
      if (instr.isSynthetic) {
        if (instr.target.tid.name !in synthList) {
          cfg.synthCount++
          synthList += instr.target.tid.name
        }
      } else {
        cfg.addDefinition(block, instr.target)
      }
    }
  }
}

/**
 * The current context in which the [CFG] is being built. Tracks relevant jumpable blocks, and
 * possible labels to jump to using goto.
 *
 * [currentLoopBlock] is the target of "continue", and is used by loops.
 * [afterBlock] is the target of "break", and is used by both loops and switches.
 * [cases] and [currentDefaultBlock] are used by the current switch.
 */
private data class GraphingContext(
    val root: CFG,
    val currentLoopBlock: BasicBlock? = null,
    val afterBlock: BasicBlock? = null,
    val cases: MutableMap<ExprConstantNode, BasicBlock> = mutableMapOf(),
    var currentDefaultBlock: BasicBlock? = null,
    val labels: MutableMap<String, BasicBlock> = mutableMapOf()
) {
  fun labelBlockFor(labelName: String): BasicBlock {
    return labels.getOrPut(labelName) { root.newBlock() }
  }

  fun caseBlockFor(caseExpr: ExprConstantNode): BasicBlock {
    return cases.getOrPut(caseExpr) { root.newBlock() }
  }
}

private fun GraphingContext.graphCompound(current: BasicBlock,
                                          compoundStatement: CompoundStatement): BasicBlock {
  var block = current
  for ((name) in compoundStatement.scope.labels) {
    labelBlockFor(name)
  }
  for (item in compoundStatement.items) {
    block = when (item) {
      is DeclarationItem -> root.addDeclaration(compoundStatement.scope, block, item.declaration)
      is StatementItem -> graphStatement(compoundStatement.scope, block, item.statement)
    }
  }
  return block
}

private fun CFG.addDefinition(current: BasicBlock, ident: ComputeReference) {
  if (!definitions.containsKey(ident)) definitions[ident] = mutableSetOf(current)
  else definitions[ident]!! += current
}

private fun CFG.addDeclaration(
    parent: LexicalScope,
    current: BasicBlock,
    d: Declaration
): BasicBlock {
  val refs = d.idents(parent).map { ComputeReference(it, isSynthetic = false) }
  val inits = d.declaratorList.map { it.second }
  var latestBlock = current
  for ((ident, init) in refs.zip(inits)) {
    if (definitions.containsKey(ident)) {
      logger.throwICE("Redefinition of declaration") { ident }
    }
    definitions[ident] = mutableSetOf(current)
    latestBlock = transformInitializer(latestBlock, ident, init)
  }
  return latestBlock
}

/** Generate IR for an [Initializer]. */
private fun CFG.transformInitializer(
    current: BasicBlock,
    ident: ComputeReference,
    init: Initializer?
): BasicBlock = when (init) {
  null -> {
    // No initializer, nothing to output
    current
  }
  is ExpressionInitializer -> {
    // Transform this into an assignment expression
    val (exprType, commonType) = binaryDiags(init.assignTok, ident.tid, init.expr)
    val convertedInit = convertToCommon(commonType, init.expr)
    val initAssign = BinaryExpression(BinaryOperators.ASSIGN, ident.tid, convertedInit, exprType)
        .withRange(ident.tid..init.expr)
    graphExprRegular(this, current, initAssign)
  }
//  else -> TODO("only expression initializers are implemented; see SyntaxTreeModel")
}

private fun processExpression(
    root: CFG,
    current: BasicBlock,
    expr: Expression
): Pair<BasicBlock, List<Expression>> {
  val sequential = root.sequentialize(expr).toList()
  val ternaries = sequential.filter {
    it is BinaryExpression && it.op == BinaryOperators.ASSIGN && it.rhs is TernaryConditional
  }.map { it as BinaryExpression }
  val resBlock = ternaries.fold(current) { block, (_, lhs, rhs) ->
    graphTernary(root, block, lhs as TypedIdentifier, rhs as TernaryConditional)
  }
  return resBlock to sequential - ternaries
}

private fun graphExprRegular(root: CFG, current: BasicBlock, expr: Expression): BasicBlock {
  val (nextBlock, exprs) = processExpression(root, current, expr)
  nextBlock.irContext.buildIR(exprs)
  return nextBlock
}

private fun graphExprTerm(
    root: CFG,
    current: BasicBlock,
    cond: Expression
): Pair<BasicBlock, IRLoweringContext> {
  val (nextBlock, exprs) = processExpression(root, current, cond)
  val condIr = IRLoweringContext(nextBlock.irContext)
  condIr.buildIR(exprs)
  return nextBlock to condIr
}

private fun graphTernary(
    root: CFG,
    current: BasicBlock,
    target: TypedIdentifier,
    ternary: TernaryConditional
): BasicBlock {
  val ifBlock = root.newBlock()
  val elseBlock = root.newBlock()

  val assignTrue = BinaryExpression(BinaryOperators.ASSIGN, target, ternary.success, target.type)
      .withRange(ternary.success)
  val ifNext = graphExprRegular(root, ifBlock, assignTrue)

  val assignFalse = BinaryExpression(BinaryOperators.ASSIGN, target, ternary.failure, target.type)
      .withRange(ternary.failure)
  val elseNext = graphExprRegular(root, elseBlock, assignFalse)

  val afterIfBlock = root.newBlock()
  val (currentNext, condIr) = graphExprTerm(root, current, ternary.cond)
  currentNext.terminator = CondJump(condIr, ifBlock, elseBlock)

  ifNext.terminator = UncondJump(afterIfBlock)
  elseNext.terminator = UncondJump(afterIfBlock)
  return afterIfBlock
}

private fun GraphingContext.graphStatement(
    scope: LexicalScope,
    current: BasicBlock,
    s: Statement
): BasicBlock = when (s) {
  is ErrorStatement,
  is ErrorExpression -> logger.throwICE("ErrorNode in CFG creation") { "$current/$s" }
  is Expression -> graphExprRegular(root, current, s)
  is Noop -> {
    // Intentionally left empty
    current
  }
  is CompoundStatement -> graphCompound(current, s)
  is IfStatement -> {
    val ifBlock = root.newBlock()
    val elseBlock = root.newBlock()
    val ifNext = graphStatement(scope, ifBlock, s.success)
    val elseNext = s.failure?.let { graphStatement(scope, elseBlock, it) }
    val afterIfBlock = root.newBlock()
    val (currentNext, condIr) = graphExprTerm(root, current, s.cond)
    currentNext.terminator =
        CondJump(condIr, ifBlock, if (elseNext != null) elseBlock else afterIfBlock)
    ifNext.terminator = UncondJump(afterIfBlock)
    elseNext?.terminator = UncondJump(afterIfBlock)
    afterIfBlock
  }
  is StatementWithLabel -> {
    val labeledBlock = when (s) {
      is LabeledStatement -> labelBlockFor(s.label.name)
      is CaseStatement -> caseBlockFor(s.caseExpr)
      is DefaultStatement -> {
        val blockDefault = root.newBlock()
        currentDefaultBlock = blockDefault
        blockDefault
      }
    }
    current.terminator = UncondJump(labeledBlock)
    val nextBlock = graphStatement(scope, labeledBlock, s.statement)
    nextBlock
  }
  is SwitchStatement -> {
    val switchAfterBlock = root.newBlock()
    val switchInnerBlock = root.newBlock()
    val (currentNext, condIr) = graphExprTerm(root, current, s.controllingExpr)
    with(copy(
        afterBlock = switchAfterBlock,
        currentDefaultBlock = switchAfterBlock
    )) {
      val switchInnerLast = graphStatement(scope, switchInnerBlock, s.statement)
      switchInnerLast.terminator = UncondJump(switchAfterBlock)
      currentNext.terminator = SelectJump(condIr, cases, switchAfterBlock)
    }
    switchAfterBlock
  }
  is WhileStatement -> {
    val loopHeader = root.newBlock()
    val loopBlock = root.newBlock()
    val afterLoopBlock = root.newBlock()
    val loopContext = copy(currentLoopBlock = loopBlock, afterBlock = afterLoopBlock)
    val loopNext = loopContext.graphStatement(scope, loopBlock, s.loopable)
    val (loopHeaderNext, condIr) = graphExprTerm(root, loopHeader, s.cond)
    loopHeaderNext.terminator = CondJump(condIr, loopBlock, afterLoopBlock)
    current.terminator = UncondJump(loopHeader)
    loopNext.terminator = UncondJump(loopHeader)
    afterLoopBlock
  }
  is DoWhileStatement -> {
    val loopBlock = root.newBlock()
    val afterLoopBlock = root.newBlock()
    val loopContext = copy(currentLoopBlock = loopBlock, afterBlock = afterLoopBlock)
    val loopNext = loopContext.graphStatement(scope, loopBlock, s.loopable)
    current.terminator = UncondJump(loopBlock)
    val (loopNextNext, condIr) = graphExprTerm(root, loopNext, s.cond)
    loopNextNext.terminator = CondJump(condIr, loopBlock, afterLoopBlock)
    afterLoopBlock
  }
  is ForStatement -> {
    val actualCurrent = when (s.init) {
      is EmptyInitializer -> current
      is ErrorInitializer -> logger.throwICE("ErrorNode in CFG creation") { "$current/$s" }
      is ForExpressionInitializer -> graphStatement(s.scope, current, s.init.value)
      is DeclarationInitializer -> root.addDeclaration(s.scope, current, s.init.value)
    }
    val loopHeader = root.newBlock()
    val loopBlock = root.newBlock()
    val afterLoopBlock = root.newBlock()
    val loopContext = copy(currentLoopBlock = loopBlock, afterBlock = afterLoopBlock)
    val loopNext = loopContext.graphStatement(s.scope, loopBlock, s.loopable)
    s.loopEnd?.let { graphStatement(s.scope, loopNext, it) }
    actualCurrent.terminator = UncondJump(loopHeader)
    if (s.cond == null) {
      // No for condition means unconditional jump to loop block
      loopHeader.terminator = UncondJump(loopBlock)
    } else {
      val (loopHeaderNext, condIr) = graphExprTerm(root, loopHeader, s.cond)
      loopHeaderNext.terminator = CondJump(condIr, loopBlock, afterLoopBlock)
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
    current.terminator = ConstantJump(afterBlock!!, afterBreak)
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
    if (s.expr == null) {
      current.terminator = ImpossibleJump(deadCodeBlock, null)
    } else {
      val (currentNext, returnIr) = graphExprTerm(root, current, s.expr)
      currentNext.terminator = ImpossibleJump(deadCodeBlock, returnIr)
    }
    deadCodeBlock
  }
}
