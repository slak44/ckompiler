package slak.ckompiler.parser

import slak.ckompiler.DebugHandler
import slak.ckompiler.DiagnosticId
import slak.ckompiler.IDebugHandler
import slak.ckompiler.SourcedRange
import slak.ckompiler.lexer.Keywords
import kotlin.js.JsExport

/**
 * Stores the data of a scoped `typedef`.
 * @param declarator the declarator used for this typedef. The [IdentifierNode] in this declarator
 * is the one used for scoping; actual declarations that use this typedef will have their own
 * identifiers
 */
data class TypedefName(
    val declSpec: DeclarationSpecifier,
    val declarator: NamedDeclarator,
) : OrdinaryIdentifier,
    // Only highlight the typedef's name in diagnostics, not the entire thing:
    SourcedRange by declarator.name {
  override val name = declarator.name.name
  override val type = typeNameOf(declSpec, declarator)
  override val kindName = "typedef"

  fun typedefedToString(): String {
    return type.toString()
  }
}

/**
 * Implementors of this interface belong in the "ordinary identifier" namespace.
 *
 * C standard: 6.2.3.0.1
 * @see LexicalScope
 */
@JsExport
interface OrdinaryIdentifier : SourcedRange {
  val name: String

  /**
   * A string that identifies what kind of identifier this is. For example: 'typedef', 'variable'.
   * Is used by diagnostic messages.
   */
  val kindName: String

  /**
   * [TypeName] of this identifier. What this property means depends on the kind of identifier.
   */
  val type: TypeName
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
class LexicalScope(
    val parentScope: LexicalScope? = null,
    val tagNames: MutableMap<IdentifierNode, TagSpecifier> = mutableMapOf(),
    val idents: MutableList<OrdinaryIdentifier> = mutableListOf(),
    val labels: MutableList<IdentifierNode> = mutableListOf(),
) {
  override fun toString(): String {
    val identStr = idents.filter { it !is TypedefName }.joinToString(", ") { it.name }
    val labelStr = labels.joinToString(", ") { it.name }
    val typedefStr = idents.mapNotNull { it as? TypedefName }
        .joinToString(", ") { "typedef ${it.typedefedToString()}" }
    val tags = tagNames.entries
        .joinToString(", ") { "${it.value.kind} ${it.key}" }
    return "LexicalScope(" +
        "idents=[$identStr], labels=[$labelStr], typedefs=[$typedefStr], tags=[$tags])"
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is LexicalScope) return false

    if (tagNames != other.tagNames) return false
    if (idents != other.idents) return false
    if (labels != other.labels) return false

    return true
  }

  override fun hashCode(): Int {
    var result = tagNames.hashCode()
    result = 31 * result + idents.hashCode()
    result = 31 * result + labels.hashCode()
    return result
  }
}

/**
 * Implementors must have a way to search for [OrdinaryIdentifier]s.
 */
interface IdentSearchable {
  /**
   * Searches all the scopes for a given identifier.
   * @return null if no such identifier exists, or the previous [OrdinaryIdentifier] otherwise
   */
  fun searchIdent(target: String): OrdinaryIdentifier?
}

interface IScopeHandler : IdentSearchable {
  fun newScope(): LexicalScope

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
  fun <R> scoped(block: LexicalScope.() -> R): R

  /**
   * Adds a new [OrdinaryIdentifier] to the current scope. If the identifier name already was in the
   * current scope, it is not added again, and diagnostics are printed.
   */
  fun newIdentifier(id: OrdinaryIdentifier)

  /**
   * Strictly in the current scope (not in parents), update an existing name to refer to the updated identifier with the same name.
   *
   * Only works for [TypedIdentifier]s.
   *
   * Useful for declarations whose type is influenced by their initializers.
   */
  fun overwriteTypeInCurrentScope(name: String, type: TypeName)

  /**
   * [newIdentifier] for labels.
   * @see newIdentifier
   */
  fun newLabel(labelIdent: IdentifierNode)

  /**
   * If [TagSpecifier.name] is null (ie the tag is anonymous), [tag] is immediately returned.
   *
   * If not, this function adds a tag to the current scope. If one already exists, and:
   * 1. The tag type differs, a diagnostic is printed.
   * 2. The new type is incomplete, nothing happens.
   * 3. The existing type is incomplete and the new type is complete, the old type is replaced.
   * 4. Both types are complete, a diagnostic is printed.
   *
   * At (2), for enum tags, an incomplete type is disallowed without a previous complete one.
   *
   * Enum tags also add their enumerators to the current scope.
   *
   * Returns the [TagSpecifier] of a previous definition of this specifier. Returns [tag] otherwise,
   * even if diagnostics were printed.
   */
  fun createTag(tag: TagSpecifier): TagSpecifier

  /**
   * Searches all the scopes for the tag with the given name.
   * @return null if no such tag exists, or the previous [TagSpecifier] otherwise
   */
  fun searchTag(target: IdentifierNode): TagSpecifier?

  val rootScope: LexicalScope
}

/** @see IScopeHandler */
class ScopeHandler(debugHandler: DebugHandler) : IScopeHandler, IDebugHandler by debugHandler {
  private val scopeStack = mutableListOf<LexicalScope>()
  override val rootScope: LexicalScope get() = scopeStack.first()

  init {
    scopeStack += LexicalScope()
  }

  override fun newScope() = LexicalScope(scopeStack.last())

  override fun <R> LexicalScope.withScope(block: LexicalScope.() -> R): R {
    scopeStack += this
    val ret = this.block()
    scopeStack.removeLast()
    return ret
  }

  override fun <R> scoped(block: LexicalScope.() -> R): R =
      LexicalScope(scopeStack.last()).withScope(block)

  override fun createTag(tag: TagSpecifier): TagSpecifier {
    val name = tag.name ?: return tag
    val names = scopeStack.last().tagNames
    val foundTag = names[name]
    val previousDef = searchTag(name)
    // Add enum constants to scope, if required
    for (enumerator in (tag as? EnumSpecifier)?.enumerators ?: emptyList()) {
      newIdentifier(enumerator)
    }
    when {
      (tag is TagNameSpecifier && tag.kind.value == Keywords.ENUM) && previousDef == null -> diagnostic {
        id = DiagnosticId.USE_ENUM_UNDEFINED
        formatArgs(name.name)
        errorOn(name)
      }
      // This is the case where we already have a definition somewhere, and we encounter stuff like
      // "struct person p;"
      foundTag == null && previousDef != null -> return previousDef
      foundTag == null -> names[name] = tag
      tag.kind != foundTag.kind -> {
        diagnostic {
          id = DiagnosticId.TAG_MISMATCH
          formatArgs(name.name)
          errorOn(tag.kind)
        }
        diagnostic {
          id = DiagnosticId.TAG_MISMATCH_PREVIOUS
          errorOn(foundTag.name!!)
        }
      }
      tag is TagNameSpecifier -> {
        // Definition found in scope even
        return foundTag
      }
      foundTag is TagNameSpecifier -> {
        names[name] = tag
      }
      else -> {
        diagnostic {
          id = DiagnosticId.REDEFINITION
          formatArgs(name.name)
          errorOn(name)
        }
        diagnostic {
          id = DiagnosticId.REDEFINITION_PREVIOUS
          errorOn(foundTag.name!!)
        }
      }
    }
    return tag
  }

  override fun overwriteTypeInCurrentScope(name: String, type: TypeName) {
    val idents = scopeStack.last().idents
    val idx = idents.indexOfFirst { it.name == name }
    check(idx >= 0) { "Cannot overwrite non-existent name ${name}, check your usage of this function" }
    val old = idents[idx]
    check(old is TypedIdentifier) { "This function only operates on TypedIdentifiers" }
    idents[idx] = old.forceTypeCast(type)
  }

  override fun newIdentifier(id: OrdinaryIdentifier) {
    val idents = scopeStack.last().idents
    val found = idents.firstOrNull { it.name == id.name }
    // FIXME: this can be used to maybe give a diagnostic about name shadowing
    // val isShadowed = scopeStack.peek().idents.any { it.name == id.name }
    if (found == null) {
      if (id !is TypedefName && !id.type.isCompleteObjectType()) diagnostic {
        this.id = DiagnosticId.VARIABLE_TYPE_INCOMPLETE
        formatArgs(id.type.toString())
        errorOn(id)
      }
      idents += id
      return
    }
    // Can't redefine name as a different kind of symbol
    if (id::class != found::class) {
      diagnostic {
        this.id = DiagnosticId.REDEFINITION_OTHER_SYM
        formatArgs(found.name, found.kindName, id.kindName)
        errorOn(id)
      }
      diagnostic {
        this.id = DiagnosticId.REDEFINITION_PREVIOUS
        errorOn(found)
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
        errorOn(id)
      }
      diagnostic {
        this.id = DiagnosticId.REDEFINITION_PREVIOUS
        errorOn(found)
      }
      return
    }
    diagnostic {
      this.id = DiagnosticId.REDEFINITION
      formatArgs(id.name)
      errorOn(id)
    }
    diagnostic {
      this.id = DiagnosticId.REDEFINITION_PREVIOUS
      errorOn(found)
    }
  }

  override fun newLabel(labelIdent: IdentifierNode) {
    val labels = scopeStack.last().labels
    // FIXME: we really should know more data about these idents
    // FIXME: in particular, a reference to a [LabeledStatement] might be required to be stored
    val foundId = labels.firstOrNull { it.name == labelIdent.name }
    if (foundId != null) {
      diagnostic {
        id = DiagnosticId.REDEFINITION_LABEL
        formatArgs(labelIdent.name)
        errorOn(labelIdent)
      }
      diagnostic {
        id = DiagnosticId.REDEFINITION_PREVIOUS
        errorOn(foundId)
      }
      return
    }
    labels += labelIdent
  }

  // FIXME: add spell-checker: "https://en.wikipedia.org/wiki/Wagner%E2%80%93Fischer_algorithm"
  override fun searchIdent(target: String): OrdinaryIdentifier? {
    scopeStack.forEach {
      val idx = it.idents.indexOfFirst { id -> id.name == target }
      if (idx != -1) return it.idents[idx]
    }
    return null
  }

  override fun searchTag(target: IdentifierNode): TagSpecifier? {
    scopeStack.forEach {
      val tag = it.tagNames[target]
      if (tag != null) return@searchTag tag
    }
    return null
  }
}

