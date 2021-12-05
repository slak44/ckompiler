package slak.ckompiler

import kotlinx.cli.*

fun CommandLineInterface.helpGroup(description: String) {
  addHelpEntry(object : HelpEntry {
    override fun printHelp(helpPrinter: HelpPrinter) {
      helpPrinter.printSeparator()
      helpPrinter.printText(description)
    }
  })
}

private data class SimpleHelpEntry(val name: String, val help: String) : HelpEntry {
  override fun printHelp(helpPrinter: HelpPrinter) {
    helpPrinter.printEntry(name, help)
  }
}

private class FlagArgumentList<T>(
    flags: List<String>,
    valueSyntax: String,
    help: String,
    val mapping: (String) -> T
) : FlagValueActionBase(flags, valueSyntax, help), ArgumentValue<List<T>> {
  val args = mutableListOf<T>()
  override fun getValue(thisRef: Any?, prop: Any?): List<T> = args
  override fun invoke(argument: String) {
    args += mapping(argument)
  }
}

private class FlagArgumentMap<K, V>(
    flags: List<String>,
    valueSyntax: String,
    help: String,
    val mapping: (String) -> Pair<K, V>
) : FlagValueActionBase(flags, valueSyntax, help), ArgumentValue<Map<K, V>> {
  val map = mutableMapOf<K, V>()
  override fun getValue(thisRef: Any?, prop: Any?): Map<K, V> = map
  override fun invoke(argument: String) {
    map += mapping(argument)
  }
}

/**
 * Like [CommandLineInterface.flagValueArgument], but collates the flag values into a list, and
 * returns that list via delegate.
 */
fun <T> CommandLineInterface.flagValueArgumentList(
    flags: List<String>,
    valueSyntax: String,
    help: String,
    mapping: (String) -> T
): ArgumentValue<List<T>> = registerAction(FlagArgumentList(flags, valueSyntax, help, mapping))

/**
 * @see CommandLineInterface.flagValueArgumentList
 */
fun <T> CommandLineInterface.flagValueArgumentList(
    flag: String,
    valueSyntax: String,
    help: String,
    mapping: (String) -> T
): ArgumentValue<List<T>> = flagValueArgumentList(listOf(flag), valueSyntax, help, mapping)

/**
 * Like [CommandLineInterface.flagValueArgumentList], but requires that the mapping returns a
 * [Pair], and returns the created map via delegate.
 */
fun <K, V> CommandLineInterface.flagValueArgumentMap(
    flags: List<String>,
    valueSyntax: String,
    help: String,
    mapping: (String) -> Pair<K, V>
): ArgumentValue<Map<K, V>> = registerAction(FlagArgumentMap(flags, valueSyntax, help, mapping))

/**
 * @see CommandLineInterface.flagValueArgumentMap
 */
fun <K, V> CommandLineInterface.flagValueArgumentMap(
    flag: String,
    valueSyntax: String,
    help: String,
    mapping: (String) -> Pair<K, V>
): ArgumentValue<Map<K, V>> = flagValueArgumentMap(listOf(flag), valueSyntax, help, mapping)

/**
 * A boolean value that is toggled on by [flagsPositive], and toggled off by [flagsNegative].
 */
fun CommandLineInterface.toggleableFlagArgument(
    flagsPositive: List<String>,
    flagsNegative: List<String>,
    help: String,
    initialValue: Boolean = false
): ArgumentValue<Boolean> {
  val argValue = object : ArgumentValue<Boolean> {
    var value = initialValue
    override fun getValue(thisRef: Any?, prop: Any?): Boolean = value
  }
  addHelpEntry(SimpleHelpEntry((flagsPositive + flagsNegative).joinToString(", "), help))
  for (flag in flagsPositive) {
    setFlagAction(flag, object : FlagAction {
      override fun invoke() {
        argValue.value = true
      }
    })
  }
  for (flag in flagsNegative) {
    setFlagAction(flag, object : FlagAction {
      override fun invoke() {
        argValue.value = false
      }
    })
  }
  return argValue
}

/**
 * @see CommandLineInterface.toggleableFlagArgument
 */
fun CommandLineInterface.toggleableFlagArgument(
    flagPositive: String,
    flagNegative: String,
    help: String,
    initialValue: Boolean = false
): ArgumentValue<Boolean> =
    toggleableFlagArgument(listOf(flagPositive), listOf(flagNegative), help, initialValue)

interface BlackHoleBuilder : CommandLineBuilder {
  val blackHole: PositionalBlackHole
}

class PositionalBlackHole : PositionalArgument {
  override val maxArgs = Int.MAX_VALUE
  override val minArgs = 0
  override val name: String
    get() = throw IllegalStateException("Should never call this")

  data class PosAction(val priority: Int, val mapping: (String) -> Boolean)

  val actions = mutableListOf<PosAction>()
  private val leftoverArguments = mutableListOf<String>()

  override val action = object : ArgumentAction {
    private var wasSorted = false
    override fun invoke(argument: String) {
      if (!wasSorted) {
        actions.sortBy { it.priority }
        actions.reverse()
        wasSorted = true
      }
      val wasConsumed = actions.any { it.mapping(argument) }
      if (!wasConsumed) leftoverArguments += argument
    }
  }
}

fun <T> BlackHoleBuilder.flagOrPositionalArgumentList(
    flags: List<String>,
    valueSyntax: String,
    help: String,
    priority: Int = Int.MAX_VALUE,
    positionalPredicate: (String) -> Boolean,
    mapping: (String) -> T
): ArgumentValue<List<T>> {
  val argList = FlagArgumentList(flags, valueSyntax, help, mapping)
  registerAction(argList)
  blackHole.actions += PositionalBlackHole.PosAction(priority) {
    if (!positionalPredicate(it)) return@PosAction false
    argList.args += mapping(it)
    return@PosAction true
  }
  return argList
}

fun <T> BlackHoleBuilder.positionalList(
    name: String,
    help: String,
    priority: Int = Int.MAX_VALUE,
    mapping: (String) -> T?
): ArgumentValue<List<T>> {
  addUsageEntry(name)
  addHelpEntry(SimpleHelpEntry(name, help))
  val argDelegate = object : ArgumentValue<List<T>> {
    val args = mutableListOf<T>()
    override fun getValue(thisRef: Any?, prop: Any?): List<T> = args
  }
  blackHole.actions += PositionalBlackHole.PosAction(priority) {
    val possibleMatch = mapping(it) ?: return@PosAction false
    argDelegate.args += possibleMatch
    return@PosAction true
  }
  return argDelegate
}

