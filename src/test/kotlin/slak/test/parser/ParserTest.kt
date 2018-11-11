package slak.test.parser

import slak.ckompiler.Lexer
import slak.ckompiler.Parser
import slak.ckompiler.SourceFileName
import slak.test.assertNoDiagnostics

internal fun prepareCode(s: String, source: SourceFileName): Parser {
  val lexer = Lexer(s, source)
  lexer.assertNoDiagnostics()
  return Parser(lexer.tokens, source, s, lexer.tokStartIdxes)
}
