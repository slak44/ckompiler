# CKompiler

A C11 compiler written in Kotlin.

This project is just a compiler; the assembler and linker are not implemented, and are assumed to
exist in the environment (eg `nasm` and `ld`).

Run `./gradlew build` for the compiler's CLI.

The ~300 JUnit tests can be found in the `slak.test` package, in `src/test/kotlin`.

Also see [what was chosen for some implementation defined/undefined behaviours][impl_defs].

### Overview of implementation details

The compiler is mostly implemented as a processing pipeline of immutable data structures.

#### The `DebugHandler` class

A compiler has to deal with two classes of "errors"; those that come from the code being compiled,
and those that signal issues in the compiler itself.

For the former, we generate `Diagnostic` instances. These are created using a simple DSL, and the
messages themselves are created internally in the `Diagnostic` class.

For the latter, we use logging (via [Log4j 2][log4j2]), the `InternalCompilerError` class, and the
`throwICE` extension methods on logger instances.

All of this code can be found in the [Diagnostics.kt][diags] file.

#### Lexer

The [lexer][lexer] operates on the source code string, and produces a list of
[LexicalToken][tokens]s and its subclasses. It is integrated in the [Preprocessor][pp].
They deal with translation phases 1-6.

#### Parser

The [parser][parser] operates on the token list, and produces an [abstract syntax tree][ast] of
`ASTNode` instances. It also performs type checks, resolves scope, identifier names and other such
semantic requirements.

It is mostly a hand-written recursive descent parser, with a [precedence climbing][prec_climb]
parser integrated for expressions, which can be found in the [ExpressionParser][expr_parser] class.

Much of the parser is built out of loosely coupled classes that each handle parts of parsing the
grammar (eg [ParenMatcher][paren_matcher] handles everything about parenthesis,
[StatementParser][st_parser] parses function block statements, etc).
Each of these has an associated interface (`IExpressionParser` for the `ExpressionParser` class),
that is used for delegation.
For example, when the `DeclarationParser` needs to parse an initializer
expression, it implements the `IExpressionParser` interface, and delegates that job to a concrete
`ExpressionParser` instance received through the constructor (or via `lateinit` properties, in some
cases, to resolve cyclic dependencies).

As a code example, the largest of the components, `StatementParser`, is declared like this:
```kotlin
class StatementParser(declarationParser: DeclarationParser,
                      controlKeywordParser: ControlKeywordParser) :
    IStatementParser,
    IDebugHandler by declarationParser,
    ITokenHandler by declarationParser,
    IScopeHandler by declarationParser,
    IParenMatcher by declarationParser,
    IExpressionParser by controlKeywordParser,
    IDeclarationParser by declarationParser,
    IControlKeywordParser by controlKeywordParser { /* ... */ }
```
As the code shows, `StatementParser` doesn't receive 7 separate components, even though it delegates
the implementation of 7 different interfaces.
Since the `DeclarationParser` implements some of those interfaces itself, `StatementParser`
only needs an instance of that for all of them.

#### Analysis

The [analysis][analysis] package takes the syntax tree (for now, one function of the syntax tree)
and it turns it into a graph with nodes that contain a simpler intermediate representation.

Control flow is resolved by converting the AST to a graph (see [ASTGraphing.kt][ast_graphing]),
where each node (a so-called [BasicBlock][bb]) represents a _linear_ piece of code, with no jumps,
conditional or otherwise.

Those linear pieces of code are transformed to the simple IR while creating the graph (see
[sequentialize][seq] and [IRLoweringContext][ir]).

All of this is stored in the [CFG (control flow graph)][cfg] class, which is responsible for
converting the code in its nodes to a (pruned) [SSA form][ssa]. It also eliminates dead code, by
finding nodes not connected to the rest of the graph.

The CFG can be viewed graphically, via [Graphviz][graphviz]'s `dot`: the
[createGraphviz][cfg_graphviz_create] function can output the `dot` source for a graph.
This functionality is also available via the CLI option `--print-cfg-graphviz`.

#### Codegen

Code generation takes the graph, and generates code for a target.
It currently targets x86_64 NASM, and, in the future, might target LLVM IR.

### References

The documentation in the code makes references to some of these documents, especially to the C
standard.

- [C11 draft standard][std_draft]
- [Fast dominance algorithm][dom_algo]
- [SSA book][ssa_book]
- [System V ABI][sysVabi]
- [NASM documentation][nasm]
- [x86_64 calling conventions][x64calling]
- [Intel 64 ISA reference][intel64isa]

[prec_climb]: https://en.wikipedia.org/wiki/Operator-precedence_parser#Precedence_climbing_method
[std_draft]: http://www.open-std.org/jtc1/sc22/wg14/www/docs/n1570.pdf
[impl_defs]: ./ListOfBehaviours.md
[lexer]: ./src/main/kotlin/slak/ckompiler/lexer/LexicalElements.kt
[pp]: ./src/main/kotlin/slak/ckompiler/lexer/Preprocessor.kt
[tokens]: ./src/main/kotlin/slak/ckompiler/lexer/TokenModel.kt
[parser]: ./src/main/kotlin/slak/ckompiler/parser/
[ast]: ./src/main/kotlin/slak/ckompiler/parser/SyntaxTreeModel.kt
[expr_parser]: ./src/main/kotlin/slak/ckompiler/parser/ExpressionParser.kt
[paren_matcher]: ./src/main/kotlin/slak/ckompiler/parser/ParenMatcher.kt
[st_parser]: ./src/main/kotlin/slak/ckompiler/parser/StatementParser.kt
[analysis]: ./src/main/kotlin/slak/ckompiler/analysis/
[ast_graphing]: ./src/main/kotlin/slak/ckompiler/analysis/ASTGraphing.kt
[bb]: ./src/main/kotlin/slak/ckompiler/analysis/BasicBlock.kt
[seq]: ./src/main/kotlin/slak/ckompiler/analysis/Sequentialization.kt
[ir]: ./src/main/kotlin/slak/ckompiler/analysis/IRLowering.kt
[cfg]: ./src/main/kotlin/slak/ckompiler/analysis/ControlFlowGraph.kt
[ssa]: https://en.wikipedia.org/wiki/Static_single_assignment_form
[graphviz]: https://www.graphviz.org/
[cfg_graphviz_create]: ./src/main/kotlin/slak/ckompiler/analysis/CFGGraphviz.kt
[intel64isa]: https://www.intel.com/content/dam/www/public/us/en/documents/manuals/64-ia-32-architectures-software-developer-instruction-set-reference-manual-325383.pdf
[x64calling]: https://en.wikipedia.org/wiki/X86_calling_conventions#x86-64_calling_conventions
[sysVabi]: https://www.uclibc.org/docs/psABI-x86_64.pdf
[dom_algo]: https://www.cs.rice.edu/~keith/EMBED/dom.pdf
[nasm]: https://nasm.us/doc/nasmdoc0.html
[ssa_book]: http://ssabook.gforge.inria.fr/latest/book.pdf
[log4j2]: https://logging.apache.org/log4j/2.x/
[diags]: ./src/main/kotlin/slak/ckompiler/Diagnostics.kt
