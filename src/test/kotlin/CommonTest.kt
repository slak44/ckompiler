import kotlin.test.assertEquals

internal fun Lexer.assertNoDiagnostics() = assertEquals(emptyList<Diagnostic>(), inspections)
internal val <T : Any> T.source get() = "<test/${javaClass.simpleName}>"
