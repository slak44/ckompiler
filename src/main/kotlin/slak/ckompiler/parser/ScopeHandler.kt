package slak.ckompiler.parser

import slak.ckompiler.DiagnosticId
import java.util.*

interface IScopeHandler {
  /**
   * Run the given [block] within the receiver scope.
   * @param block called with its receiver parameter as the original scope
   * @return the value returned by [block]
   */
  fun <R> LexicalScope.withScope(block: LexicalScope.() -> R): R

  /**
   * Create a [LexicalScope], and run the given [block] within that scope.
   * @see LexicalScope.withScope
   * @param block called with the receiver parameter as the created scope
   * @return the value returned by [block]
   */
  fun <R> scoped(block: LexicalScope.() -> R): R = LexicalScope().withScope(block)

  /**
   * Adds a new identifier to the current scope. If the identifier already was in the current scope,
   * it is not added again, and diagnostics are printed.
   */
  fun newIdentifier(id: IdentifierNode, isLabel: Boolean = false)

  /**
   * Searches all the scopes for a given identifier.
   * @return null if no such identifier exists, or the previous [IdentifierNode] otherwise
   */
  fun searchInScope(target: IdentifierNode): IdentifierNode?
}

/** @see IScopeHandler */
class ScopeHandler(debugHandler: DebugHandler) : IScopeHandler, IDebugHandler by debugHandler {
  private val scopeStack = Stack<LexicalScope>()

  init {
    scopeStack.push(LexicalScope())
  }

  override fun <R> LexicalScope.withScope(block: LexicalScope.() -> R): R {
    scopeStack.push(this)
    val ret = this.block()
    scopeStack.pop()
    return ret
  }

  override fun newIdentifier(id: IdentifierNode, isLabel: Boolean) {
    val listRef = if (isLabel) scopeStack.peek().labels else scopeStack.peek().idents
    // FIXME: we really should know more data about these idents, like symbol type (func/var)
    val foundId = listRef.firstOrNull { it.name == id.name }
    // FIXME: this can be used to maybe give a diagnostic about name shadowing
    // val isShadowed = scopeStack.peek().idents.any { it.name == id.name }
    if (foundId != null) {
      parserDiagnostic {
        this.id = if (isLabel) DiagnosticId.REDEFINITION_LABEL else DiagnosticId.REDEFINITION
        formatArgs(id.name)
        columns(id.tokenRange)
      }
      parserDiagnostic {
        this.id = DiagnosticId.REDEFINITION_PREVIOUS
        columns(foundId.tokenRange)
      }
      return
    }
    listRef += id
  }

  override fun searchInScope(target: IdentifierNode): IdentifierNode? {
    scopeStack.forEach {
      val idx = it.idents.indexOfFirst { (name) -> name == target.name }
      if (idx != -1) return it.idents[idx]
    }
    return null
  }
}

