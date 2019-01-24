package slak.ckompiler.parser

import slak.ckompiler.DiagnosticId
import slak.ckompiler.throwICE
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
   * Creates a typedef in the current scope. If one already exists, and the defined type differs,
   * a diagnostic is printed.
   */
  fun createTypedef(td: TypedefName)

  /**
   * Adds a tag to the current scope. If one already exists, and:
   * 1. The tag type differs, a diagnostic is printed.
   * 2. The new type is incomplete, nothing happens.
   * 3. The existing type is incomplete and the new type is complete, the old type is replaced.
   * 4. Both types are complete, a diagnostic is printed.
   * @param tag an error is thrown if [TagSpecifier.isAnonymous] is true
   */
  fun createTag(tag: TagSpecifier)

  /**
   * Searches all the scopes for a given identifier.
   * @return null if no such identifier exists, or the previous [IdentifierNode] otherwise
   */
  fun searchIdent(target: IdentifierNode): IdentifierNode?

  /**
   * Searches all the scopes for a typedef.
   * @return null if no such identifier exists, or the [TypedefName] otherwise
   */
  fun searchTypedef(target: IdentifierNode): TypedefName?
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

  override fun createTypedef(td: TypedefName) {
    val names = scopeStack.peek().typedefNames
    val foundTypedef = names.firstOrNull { it.typedefIdent.name == td.typedefIdent.name }
    // Repeated typedefs are only allowed if they define the same thing
    // So if they're different, complain
    if (foundTypedef != null && td != foundTypedef) {
      parserDiagnostic {
        id = DiagnosticId.REDEFINITION_TYPEDEF
        formatArgs(td.typedefedToString(), foundTypedef.typedefedToString())
        columns(td.typedefIdent.tokenRange)
      }
      parserDiagnostic {
        id = DiagnosticId.REDEFINITION_PREVIOUS
        columns(foundTypedef.typedefIdent.tokenRange)
      }
    } else {
      names += td
    }
  }

  override fun createTag(tag: TagSpecifier) {
    if (tag.isAnonymous) {
      logger.throwICE("Cannot store the tag name of an anonymous tag specifier") { "tag: $tag" }
    }
    val names = scopeStack.peek().tagNames
    val foundTag = names.firstOrNull { it.tagIdent.name == tag.tagIdent.name }
    when {
      foundTag == null -> names += tag
      tag.tagKindKeyword.value != foundTag.tagKindKeyword.value -> {
        parserDiagnostic {
          id = DiagnosticId.TAG_MISMATCH
          formatArgs(tag.tagIdent.name)
          errorOn(tag.tagKindKeyword)
        }
        parserDiagnostic {
          id = DiagnosticId.TAG_MISMATCH_PREVIOUS
          columns(foundTag.tagIdent.tokenRange)
        }
      }
      !tag.isCompleteType -> { /* Do nothing intentionally */ }
      !foundTag.isCompleteType && tag.isCompleteType -> {
        names -= foundTag
        names += tag
      }
      foundTag.isCompleteType && tag.isCompleteType -> {
        parserDiagnostic {
          id = DiagnosticId.REDEFINITION
          formatArgs(tag.tagIdent.name)
          columns(tag.tagIdent.tokenRange)
        }
        parserDiagnostic {
          id = DiagnosticId.REDEFINITION_PREVIOUS
          columns(foundTag.tagIdent.tokenRange)
        }
      }
    }
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

  override fun searchIdent(target: IdentifierNode): IdentifierNode? {
    scopeStack.forEach {
      val idx = it.idents.indexOfFirst { (name) -> name == target.name }
      if (idx != -1) return it.idents[idx]
    }
    return null
  }

  override fun searchTypedef(target: IdentifierNode): TypedefName? {
    scopeStack.forEach {
      val idx = it.typedefNames.indexOfFirst { (_, _, ident) -> ident.name == target.name }
      if (idx != -1) return it.typedefNames[idx]
    }
    return null
  }
}

