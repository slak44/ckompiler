package slak.ckompiler.parser

import slak.ckompiler.DebugHandler
import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler
import slak.ckompiler.throwICE
import java.util.*

/**
 * Stores the data of a scoped `typedef`.
 * @param typedefIdent this is the [IdentifierNode] found at the place where the typedef was
 * declared; actual declarations that use this typedef will have their own identifiers
 */
data class TypedefName(val declSpec: DeclarationSpecifier,
                       val indirection: List<TypeQualifierList>,
                       val typedefIdent: IdentifierNode) : OrdinaryIdentifier by typedefIdent {
  override val kindName = "typedef"

  fun typedefedToString(): String {
    val ind = indirection.stringify()
    val indStr = if (ind.isBlank()) "" else " $ind"
    // The storage class is "typedef", and we don't want to print it
    val dsNoStorage = DeclarationSpecifier(
        storageClass = null,
        typeSpec = declSpec.typeSpec,
        functionSpecs = declSpec.functionSpecs,
        typeQualifiers = declSpec.typeQualifiers,
        threadLocal = declSpec.threadLocal)
    return "$dsNoStorage$indStr"
  }
}

/**
 * Implementors of this interface belong in the "ordinary identifier" namespace.
 *
 * C standard: 6.2.3.0.1
 * @see LexicalScope
 */
interface OrdinaryIdentifier {
  val name: String
  /** @see ASTNode.tokenRange */
  val tokenRange: IntRange
  /**
   * A string that identifies what kind of identifier this is. For example: 'typedef', 'variable'.
   * Is used by diagnostic messages.
   */
  val kindName: String
}

/**
 * This class stores lexically-scoped identifiers.
 *
 * The standard specifies multiple namespaces:
 * 1. Label namespace ([labels])
 * 2. Tag namespace ([tagNames])
 * 3. "Ordinary identifiers", AKA everything else, including typedef names ([idents])
 *
 * C standard: 6.2.1, 6.2.3.0.1
 */
data class LexicalScope(val tagNames: MutableList<TagSpecifier> = mutableListOf(),
                        val idents: MutableList<OrdinaryIdentifier> = mutableListOf(),
                        val labels: MutableList<IdentifierNode> = mutableListOf()) {

  override fun toString(): String {
    val identStr = idents.filter { it !is TypedefName }.joinToString(", ") { it.name }
    val labelStr = labels.joinToString(", ") { it.name }
    val typedefStr = idents.mapNotNull { it as? TypedefName }
        .joinToString(", ") { "${it.typedefedToString()} ${it.typedefIdent.name}" }
    val tags = tagNames
        .joinToString(", ") { "${it.tagKindKeyword.value.keyword} ${it.tagIdent.name}" }
    return "LexicalScope(" +
        "idents=[$identStr], labels=[$labelStr], typedefs=[$typedefStr], tags=[$tags])"
  }
}

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
   * Adds a new [OrdinaryIdentifier] to the current scope. If the identifier name already was in the
   * current scope, it is not added again, and diagnostics are printed.
   */
  fun newIdentifier(id: OrdinaryIdentifier)

  /**
   * [newIdentifier] for labels.
   * @see newIdentifier
   */
  fun newLabel(labelIdent: IdentifierNode)

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
   * @return null if no such identifier exists, or the previous [OrdinaryIdentifier] otherwise
   */
  fun searchIdent(target: IdentifierNode): OrdinaryIdentifier?

  /**
   * Searches all the scopes for the tag with the given name.
   * @return null if no such tag exists, or the previous [TagSpecifier] otherwise
   */
  fun searchTag(target: IdentifierNode): TagSpecifier?

  val rootScope: LexicalScope
}

/** @see IScopeHandler */
class ScopeHandler(debugHandler: DebugHandler) : IScopeHandler, IDebugHandler by debugHandler {
  private val scopeStack = Stack<LexicalScope>()
  override val rootScope: LexicalScope get() = scopeStack.first()

  init {
    scopeStack.push(LexicalScope())
  }

  override fun <R> LexicalScope.withScope(block: LexicalScope.() -> R): R {
    scopeStack.push(this)
    val ret = this.block()
    scopeStack.pop()
    return ret
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
        diagnostic {
          id = DiagnosticId.TAG_MISMATCH
          formatArgs(tag.tagIdent.name)
          errorOn(tag.tagKindKeyword)
        }
        diagnostic {
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
        diagnostic {
          id = DiagnosticId.REDEFINITION
          formatArgs(tag.tagIdent.name)
          columns(tag.tagIdent.tokenRange)
        }
        diagnostic {
          id = DiagnosticId.REDEFINITION_PREVIOUS
          columns(foundTag.tagIdent.tokenRange)
        }
      }
    }
  }

  override fun newIdentifier(id: OrdinaryIdentifier) {
    val idents = scopeStack.peek().idents
    // FIXME: we really should know more data about these idents, like symbol type (func/var)
    val found = idents.firstOrNull { it.name == id.name }
    // FIXME: this can be used to maybe give a diagnostic about name shadowing
    // val isShadowed = scopeStack.peek().idents.any { it.name == id.name }
    if (found == null) {
      idents += id
      return
    }
    // Can't redefine name as a different kind of symbol
    if (id.javaClass != found.javaClass) {
      diagnostic {
        this.id = DiagnosticId.REDEFINITION_OTHER_SYM
        formatArgs(found.name, found.kindName, id.kindName)
        columns(id.tokenRange)
      }
      diagnostic {
        this.id = DiagnosticId.REDEFINITION_PREVIOUS
        columns(found.tokenRange)
      }
      return
    }
    // Repeated typedefs are only allowed if they define the same thing
    // So complain only if they're different
    if (id is TypedefName && id == found) return
    if (id is TypedefName && id != found) {
      found as TypedefName // We already know id and found have the same type
      diagnostic {
        this.id = DiagnosticId.REDEFINITION_TYPEDEF
        formatArgs(id.typedefedToString(), found.typedefedToString())
        columns(id.tokenRange)
      }
      diagnostic {
        this.id = DiagnosticId.REDEFINITION_PREVIOUS
        columns(found.tokenRange)
      }
      return
    }
    diagnostic {
      this.id = DiagnosticId.REDEFINITION
      formatArgs(id.name)
      columns(id.tokenRange)
    }
    diagnostic {
      this.id = DiagnosticId.REDEFINITION_PREVIOUS
      columns(found.tokenRange)
    }
  }

  override fun newLabel(labelIdent: IdentifierNode) {
    val labels = scopeStack.peek().labels
    // FIXME: we really should know more data about these idents
    // FIXME: in particular, a reference to a [LabeledStatement] might be required to be stored
    val foundId = labels.firstOrNull { it.name == labelIdent.name }
    if (foundId != null) {
      diagnostic {
        id = DiagnosticId.REDEFINITION_LABEL
        formatArgs(labelIdent.name)
        columns(labelIdent.tokenRange)
      }
      diagnostic {
        id = DiagnosticId.REDEFINITION_PREVIOUS
        columns(foundId.tokenRange)
      }
      return
    }
    labels += labelIdent
  }

  // FIXME: add spell-checker: "https://en.wikipedia.org/wiki/Wagner%E2%80%93Fischer_algorithm"
  override fun searchIdent(target: IdentifierNode): OrdinaryIdentifier? {
    scopeStack.forEach {
      val idx = it.idents.indexOfFirst { id -> id.name == target.name }
      if (idx != -1) return it.idents[idx]
    }
    return null
  }

  override fun searchTag(target: IdentifierNode): TagSpecifier? {
    scopeStack.forEach {
      val idx = it.tagNames.indexOfFirst { tag -> tag.tagIdent.name == target.name }
      if (idx != -1) return it.tagNames[idx]
    }
    return null
  }
}

