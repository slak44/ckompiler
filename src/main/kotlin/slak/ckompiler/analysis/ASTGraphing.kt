package slak.ckompiler.analysis

import io.github.oshai.kotlinlogging.KotlinLogging
import slak.ckompiler.parser.*
import slak.ckompiler.rangeTo
import slak.ckompiler.throwICE

private val logger = KotlinLogging.logger {}

fun graph(cfg: CFGFactory) {
  for ((idx, p) in cfg.f.parameters.withIndex()) {
    cfg.exprDefinitions[Variable(p)] = mutableSetOf(cfg.startBlock)
    cfg.startBlock.ir += MoveInstr(Variable(p), ParameterReference(idx, p.type))
  }
  GraphingContext(root = cfg).graphCompound(cfg.startBlock, cfg.f.block)
  for (block in cfg.allNodes) {
    for (instr in block.instructions) {
      if (instr !is MoveInstr || instr.result !is Variable) continue
      cfg.addDefinition(block, instr.result as Variable)
    }
  }
}

/**
 * The current context in which the [CFG] is being built, using [CFGFactory].
 * Tracks relevant jumpable blocks, and possible labels to jump to using goto.
 *
 * [loopHeader] is the target of "continue", and is used by loops.
 * [afterBlock] is the target of "break", and is used by both loops and switches.
 * [cases] and [currentDefaultBlock] are used by the current switch.
 */
private data class GraphingContext(
    val root: CFGFactory,
    val loopHeader: BasicBlock? = null,
    val afterBlock: BasicBlock? = null,
    val cases: MutableMap<ExprConstantNode, BasicBlock> = mutableMapOf(),
    var currentDefaultBlock: BasicBlock? = null,
    val labels: MutableMap<String, BasicBlock> = mutableMapOf(),
) {
  fun labelBlockFor(labelName: String): BasicBlock {
    return labels.getOrPut(labelName) { root.newBlock() }
  }

  fun caseBlockFor(caseExpr: ExprConstantNode): BasicBlock {
    return cases.getOrPut(caseExpr) { root.newBlock() }
  }
}

private fun GraphingContext.graphCompound(
    current: BasicBlock,
    compoundStatement: CompoundStatement,
): BasicBlock {
  var block = current
  for ((name) in compoundStatement.scope.labels) {
    labelBlockFor(name)
  }
  for (item in compoundStatement.items) {
    block = when (item) {
      is DeclarationItem -> addDeclaration(compoundStatement.scope, block, item.declaration)
      is StatementItem -> graphStatement(compoundStatement.scope, block, item.statement)
    }
  }
  return block
}

private fun CFGFactory.addDefinition(current: BasicBlock, ident: Variable) {
  if (!exprDefinitions.containsKey(ident)) exprDefinitions[ident] = mutableSetOf(current)
  else exprDefinitions[ident]!! += current
}

private fun GraphingContext.addDeclaration(
    parent: LexicalScope,
    current: BasicBlock,
    d: Declaration,
): BasicBlock {
  val refs = d.idents(parent).map(::Variable)
  val inits = d.declaratorList.map { it.second }
  var latestBlock = current
  for ((ident, init) in refs.zip(inits)) {
    if (root.exprDefinitions.containsKey(ident)) {
      logger.throwICE("Redefinition of declaration") { ident }
    }
    root.exprDefinitions[ident] = mutableSetOf(current)
    latestBlock = transformInitializer(latestBlock, ident, init)
  }
  return latestBlock
}

/** Generate IR for an [Initializer]. */
private fun GraphingContext.transformInitializer(
    current: BasicBlock,
    ident: Variable,
    init: Initializer?,
): BasicBlock = when (init) {
  null -> {
    // No initializer, nothing to output
    current
  }
  is ExpressionInitializer -> {
    // Transform this into an assignment expression
    val (exprType, commonType) = root.binaryDiags(init.assignTok, ident.tid, init.expr)
    val convertedInit = convertToCommon(commonType, init.expr)
    val initAssign = BinaryExpression(BinaryOperators.ASSIGN, ident.tid, convertedInit, exprType)
        .withRange(ident.tid..init.expr)
    graphExprRegular(root, current, initAssign)
  }
  else -> TODO("only expression initializers are implemented; see SyntaxTreeModel")
}

private fun processExpression(
    root: CFGFactory,
    current: BasicBlock,
    expr: Expression,
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

private fun graphExprRegular(
    root: CFGFactory,
    current: BasicBlock,
    expr: Expression,
): BasicBlock {
  val (nextBlock, exprs) = processExpression(root, current, expr)
  val instrs = createInstructions(
      exprs.filterNot { it is Terminal },
      root.targetData,
      root.registerIds,
      root.stackVariableIds,
      root.irValueFactory
  )
  nextBlock.ir += instrs
  nextBlock.src += exprs
  return nextBlock
}

private fun graphExprTerm(
    root: CFGFactory,
    current: BasicBlock,
    cond: Expression,
    compareWithZero: Boolean = true,
): Pair<BasicBlock, List<IRInstruction>> {
  val (nextBlock, exprs) = processExpression(root, current, cond)
  val instrs = createInstructions(exprs, root.targetData, root.registerIds, root.stackVariableIds, root.irValueFactory)
  val l = instrs.last()
  return if (compareWithZero && l !is IntCmp && l !is FltCmp) {
    val res = VirtualRegister(root.registerIds(), SignedIntType)
    val zeroCmp = IntCmp(res, l.result, root.irValueFactory.getIntConstant(0, l.result.type), Comparisons.NOT_EQUAL)
    nextBlock to (instrs + zeroCmp)
  } else {
    nextBlock to instrs
  }
}

private fun graphTernary(
    root: CFGFactory,
    current: BasicBlock,
    target: TypedIdentifier,
    ternary: TernaryConditional,
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
  currentNext.terminator = CondJump(condIr, ternary.cond, ifBlock, elseBlock)

  ifNext.terminator = UncondJump(afterIfBlock)
  elseNext.terminator = UncondJump(afterIfBlock)
  return afterIfBlock
}

private fun GraphingContext.graphStatement(
    scope: LexicalScope,
    current: BasicBlock,
    s: Statement,
): BasicBlock = when (s) {
  is ErrorStatement,
  is ErrorExpression,
  -> logger.throwICE("ErrorNode in CFG creation") { "$current/$s" }
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
        CondJump(condIr, s.cond, ifBlock, if (elseNext != null) elseBlock else afterIfBlock)
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
      currentNext.terminator =
          SelectJump(condIr, s.controllingExpr, cases, currentDefaultBlock ?: switchAfterBlock)
    }
    switchAfterBlock
  }
  is WhileStatement -> {
    val loopHeader = root.newBlock()
    val loopBlock = root.newBlock()
    val afterLoopBlock = root.newBlock()
    val loopContext = copy(loopHeader = loopHeader, afterBlock = afterLoopBlock)
    val loopNext = loopContext.graphStatement(scope, loopBlock, s.loopable)
    val (loopHeaderNext, condIr) = graphExprTerm(root, loopHeader, s.cond)
    loopHeaderNext.terminator = CondJump(condIr, s.cond, loopBlock, afterLoopBlock)
    current.terminator = UncondJump(loopHeader)
    loopNext.terminator = UncondJump(loopHeader)
    afterLoopBlock
  }
  is DoWhileStatement -> {
    val loopBlock = root.newBlock()
    val afterLoopBlock = root.newBlock()
    val loopContext = copy(loopHeader = loopHeader, afterBlock = afterLoopBlock)
    val loopNext = loopContext.graphStatement(scope, loopBlock, s.loopable)
    current.terminator = UncondJump(loopBlock)
    val (loopNextNext, condIr) = graphExprTerm(root, loopNext, s.cond)
    loopNextNext.terminator = CondJump(condIr, s.cond, loopBlock, afterLoopBlock)
    afterLoopBlock
  }
  is ForStatement -> {
    val actualCurrent = when (s.init) {
      is EmptyInitializer -> current
      is ErrorInitializer -> logger.throwICE("ErrorNode in CFG creation") { "$current/$s" }
      is ForExpressionInitializer -> graphStatement(s.scope, current, s.init.value)
      is DeclarationInitializer -> addDeclaration(s.scope, current, s.init.value)
    }
    val loopHeader = root.newBlock()
    val loopBlock = root.newBlock()
    val afterLoopBlock = root.newBlock()
    val loopContext = copy(loopHeader = loopHeader, afterBlock = afterLoopBlock)
    val loopNext = loopContext.graphStatement(s.scope, loopBlock, s.loopable)
    s.loopEnd?.let { graphStatement(s.scope, loopNext, it) }
    actualCurrent.terminator = UncondJump(loopHeader)
    if (s.cond == null) {
      // No for condition means unconditional jump to loop block
      loopHeader.terminator = UncondJump(loopBlock)
    } else {
      val (loopHeaderNext, condIr) = graphExprTerm(root, loopHeader, s.cond)
      loopHeaderNext.terminator = CondJump(condIr, s.cond, loopBlock, afterLoopBlock)
    }
    loopNext.terminator = UncondJump(loopHeader)
    afterLoopBlock
  }
  is ContinueStatement -> {
    val afterContinue = root.newBlock()
    current.terminator = ConstantJump(loopHeader!!, afterContinue)
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
      current.terminator = ImpossibleJump(deadCodeBlock, null, null)
    } else {
      val (currentNext, returnIr) = graphExprTerm(root, current, s.expr, compareWithZero = false)
      currentNext.terminator = ImpossibleJump(deadCodeBlock, returnIr, s.expr)
    }
    deadCodeBlock
  }
}
