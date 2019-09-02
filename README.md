# CKompiler

A C11 compiler written in Kotlin.

This project is just a compiler; the assembler and linker are not implemented,
and are assumed to exist in the environment (eg `nasm` and `ld`).

Run `./gradlew distZip` for the compiler's CLI (will be created in
`build/distributions/ckompiler-$version.zip`).

JUnit tests can be found in the `slak.test` package, in `src/test/kotlin`.

Also see what was chosen for some
[implementation defined/undefined behaviours][impl_defs].

## Overview of implementation details

The compiler is mostly implemented as a processing pipeline of immutable data
structures.

### Errors

A compiler has to deal with two classes of "errors"; those that come from the
code being compiled ("diagnostics"), and those that signal issues in the
compiler itself.

All of the relevant code can be found in the [Diagnostics.kt][diags] file.

###### Diagnostics

Diagnostics are handled by the `DebugHandler` class (and its corresponding
interface, `IDebugHandler`, read the [parser](#Parser) section for details).

We generate `Diagnostic` instances using a simple DSL, but the error messages,
the source extracts and the caret/tilde markers are created lazily, internally.
They are only computed when (or if) the diagnostic gets printed. This is useful,
because it makes creating a `Diagnostic` instance relatively cheap. As a result,
we can create diagnostics even if they might be discarded later, with little
cost. One such place where diagnostics are sometimes discarded is
`ConstantExprParser#evaluateExpr`.

An example of the `IDebugHandler.diagnostic` DSL, used in the parser:
```kotlin
// ...
diagnostic {
  id = DiagnosticId.INVALID_ARGUMENT_UNARY
  formatArgs(expr.type, op.op.s)
  errorOn(c..expr)
}
// ...
```
The range passed to `errorOn` in the example above, is an implementor of the
`SourcedRange` interface. `LexicalToken` and `ASTNode` are such implementations.
The `rangeTo` operator is overloaded on `SourcedRange`s to easily combine
multiple ranges:
```kotlin
sizeOf..tokenAt(rParenIdx)
```
The `sizeOf` token and the token returned by `tokenAt(rParenIdx)` are not
adjacent (think of __`sizeof`__`(1 + 1`__`)`__), but this overload allows the
parser to trivially create a compound `SourcedRange` that covers the entire
sizeof expression.

Another example, used in `sequentialize`:
```kotlin
// ...
diagnostic {
  id = DiagnosticId.UNSEQUENCED_MODS
  formatArgs(variable.name)
  for (mod in modList) when (mod) {
    is BinaryExpression -> errorOn(mod.lhs)
    is IncDecOperation -> errorOn(mod)
    else -> logger.throwICE("Modification doesn't modify anything") { mod }
  }
}
// ...
```
This illustrates the utility provided by using a lambda + builder DSL. Arbitrary
code can run in the construction of the diagnostic, which makes it easy to
tailor the same diagnostic to different situations.

Finally, an example from the preprocessor:
```kotlin
// ...
diagnostic {
  id = if (ignoreTrigraphs) DiagnosticId.TRIGRAPH_IGNORED else DiagnosticId.TRIGRAPH_PROCESSED
  if (!ignoreTrigraphs) formatArgs(replacement)
  columns(matchResult.start() until matchResult.end())
}
// ...
```
Even the kind of diagnostic can be dynamically selected based on arbitrary
logic, which makes it easy to support feature flags like `-fno-trigraphs` in
diagnostics.

###### Compiler Errors

For actual issues in the compiler, we use logging (via [Log4j 2][log4j2]), the
`InternalCompilerError` class, and the `throwICE` extension methods on logger
instances. An instance of ICE being thrown means invariants were violated,
"impossible" situations occurred, or misuse of an API was encountered. As a
result, these exceptions should not be caught anywhere: it is desirable for the
application to crash if someone threw an ICE. Every ICE is an unfixed bug in the
compiler.

Since the compiler is still a work in progress, there are many features/code
paths that are not yet implemented. They generally do not throw ICEs, rather
they use `kotlin.NotImplementedError` created by the `TODO` function.

### Target-Specific Information

Certain things depend on the target ISA and/or the machine (the size of an
`int`, for example). `MachineTargetData` instances contain all this information.

`MachineTargetData` also generates various macros used in stdlib headers (such
as `__PTRDIFF_T_TYPE` for `stddef.h`).

For now, only x64 Linux is supported, but the infrastructure for x86 and other
platforms exists.

### Lexer

The [lexer][lexer] operates on the source code string, and produces a list of
[LexicalToken][tokens]s and its subclasses. It is integrated in the
[Preprocessor][pp]. They deal with translation phases 1-6.

### Parser

The [parser][parser] operates on the token list, and produces an
[abstract syntax tree][ast] of `ASTNode` instances. It also performs type
checks, resolves scope, identifier names and other such semantic requirements.

It is mostly a hand-written recursive descent parser, with a
[precedence climbing][prec_climb] parser integrated for expressions, which can
be found in the [ExpressionParser][expr_parser] class.

Much of the parser is built out of loosely coupled classes that each handle
parts of parsing the grammar (eg [ParenMatcher][paren_matcher] handles
everything about matching parenthesis, [StatementParser][st_parser] parses
function block statements, etc).  
Each of these has an associated interface (`IExpressionParser` for the
`ExpressionParser` class), that is used for delegation.  
For example, when the `DeclarationParser` needs to parse an initializer
expression, it implements the `IExpressionParser` interface, and delegates that
job to a concrete `ExpressionParser` instance received through the constructor
(or via `lateinit` properties, in some cases, to resolve cyclic dependencies).

As a code example, the largest of the components, `StatementParser`, is declared
like this:
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
As the code shows, `StatementParser` doesn't receive 7 separate components, even
though it delegates the implementation of 7 different interfaces.
Since the `DeclarationParser` implements some of those interfaces itself,
`StatementParser` only needs an instance of that for all of them.

### Analysis

The [analysis][analysis] package takes the syntax tree, and turns each function
into a graph, with nodes that contain a simpler intermediate representation.

###### Control flow

Control flow is resolved by converting the AST to a graph (see
[ASTGraphing.kt][ast_graphing]), where each node (a so-called [BasicBlock][bb])
represents a _linear_ piece of code, with no jumps, conditional or otherwise.
Basically, all `ASTNode` subclasses are removed by the conversion to a graph,
except for `Expression` subclasses (with certain exceptions that are also
removed, namely `TernaryConditional`).

Those linear pieces of code are represented as a `List<Expression>`, and are
transformed to the simple IR (`List<IRExpression>`) while creating the graph
(see [sequentialize][seq] and [IRLoweringContext][ir]).

Constant folding is also applied to the expressions during this process (see
[ConstantFolding.kt][const_fold]).

While creating the graph, various variable declarations are encountered. These
do not show up in the IR; instead, they are tracked in a separate data structure
(see `CFG.definitions`). A "definition" is either a declaration in the C sense,
or assignment to that variable.

Everything is stored in the [CFG (control flow graph)][cfg] class.

###### Graphical representation for CFGs

The CFG can be viewed graphically, via [Graphviz][graphviz]'s `dot`: the
[createGraphviz][cfg_graphviz_create] function can output the `dot` source for a
graph.  
This functionality is also available via the CLI option `--cfg-mode`.

For example, the following code

```C
int main() {
  int x = 1;
  if (x > 22) {
    x += 2;
  } else {
    x += 3;
  }
  return x + 4;
}
```

produces the following graph  
<p align="center"><img src="readme-resources/graph-example.png" alt="Graph Example"/></p>

###### SSA form

The `CFG` class is also responsible for converting the code in its nodes to a
(pruned) [SSA form][ssa]. However, the process has a few prerequisites:

1. The CFG must be cleaned of certain artifacts created during AST graphing. In
   particular, empty blocks (blocks with no code, no condition and no return)
   are removed from the graph.
   
   Here is an example of a CFG that has not been cleaned:  
   <p align="center"><img src="readme-resources/empty-blocks.png" alt="Empty Blocks"/></p>
   
   All the blocks labeled `<EMPTY>` are redundant.
   
   The edges are collapsed such that the control flow the graph represents is
   unchanged. This is done in a loop until no more collapsing can be done;
   removing an empty block may also allow us to remove a previously
   un-removeable block (this case is in the example above, with the two blocks
   that come after the return).
   
   See `collapseEmptyBlocks` in [ControlFlowGraph.kt][cfg], and
   `BasicBlock.collapseEmptyPreds` in [BasicBlock.kt][bb].
2. Dead code elimination is performed, by finding subgraphs disconnected from
   the start node. See `filterReachable` in [ControlFlowGraph.kt][cfg]. 
   
   Note that in this case, impossible edges aren't considered for connectivity.
   For example, a basic block that contains a function return has an impossible
   edge to another basic block, which contains the code after the return.
   Strictly speaking, the second block is connected. For DCE purposes, however,
   such blocks are considered disconnected. Such a graph looks like this:  
   <p align="center"><img src="readme-resources/disconnected-fake-edge.png" alt="Disconnected Fake Edge"/></p>
3. Unterminated `BasicBlock`s are identified. Warnings are reported for non-void
   functions (it means control flow reached the end of the function and didn't
   find a return). The blocks are terminated with a `ImpossibleJump`.
4. We precompute a list of the CFG's nodes in [post-order][post_order]
   (basically DFS, for the kinds of graphs encountered here). See
   `postOrderNodes` in [ControlFlowGraph.kt][cfg].
5. An auxiliary data structure, `DominatorList`, is created. This list stores 
   each node's _immediate dominator_. The list is then used to compute every
   node's _dominance frontier_, which is stored as a member of `BasicBlock`.

Once all these steps are complete, SSA conversion can begin. It works in 2
phases: φ-function insertion, and variable renaming.

The first phase is responsible for taking every single variable definition in
the function, and creating `PhiFunction` instances for every control flow
intersection (this is what the dominance frontier is used for). This is a
relatively uncomplicated process if dominance is precomputed. See
`insertPhiFunctions` in [ControlFlowGraph.kt][cfg].

The second phase does the bulk of the work: renaming every use and definition of
every variable. A lot of state is tracked to enable this process (see the
`ReachingDef` and `VariableRenamer` classes in [ControlFlowGraph.kt][cfg]). The
"renaming" is done by simply annotating variables with a "version" property.
"Newer" versions' definitions are strictly dominated by "older" versions'
definitions.

Once this work is completed, the code is now in SSA form.

If the `ControlFlowVariableRenames` marker in [log4j2.xml][log4j2_xml] is
enabled (it is denied logging by default), the variable renamer will print a
table outlining some of the steps done during renaming for the variable `x`:
```text
[TRACE] in ControlFlow: BB| x mention   | x.reachingDef
[TRACE] in ControlFlow: -------------------------------
[TRACE] in ControlFlow: 1 |     x0 φuse | ⊥ updated into ⊥
[TRACE] in ControlFlow: 1 | def x1      | ⊥ then x1
[TRACE] in ControlFlow: 1 |     x1 use  | x1 updated into ⊥
[TRACE] in ControlFlow: 3 | def x2      | x1 then x2
[TRACE] in ControlFlow: 3 |     x2 use  | x2 updated into x1
[TRACE] in ControlFlow: 9 |     x2 φuse | x2 updated into x1
[TRACE] in ControlFlow: 4 |     x1 use  | x2 updated into ⊥
[TRACE] in ControlFlow: 4 | def x3      | x1 then x3
[TRACE] in ControlFlow: 4 |     x3 use  | x3 updated into x1
[TRACE] in ControlFlow: 4 |     x3 use  | x3 updated into x1
[TRACE] in ControlFlow: 2 |     x3 φuse | x3 updated into x1
[TRACE] in ControlFlow: 9 |     x3 φuse | x3 updated into x1
[TRACE] in ControlFlow: 9 | def x4      | x3 then x4
[TRACE] in ControlFlow: 9 |     x4 use  | x4 updated into x3
[TRACE] in ControlFlow: 9 | def x5      | x4 then x5
[TRACE] in ControlFlow: 9 |     x5 use  | x5 updated into x4
[TRACE] in ControlFlow: 9 |     x5 use  | x5 updated into x4
[TRACE] in ControlFlow: 1 |     x5 φuse | x5 updated into x4
[TRACE] in ControlFlow: 2 |     x5 φuse | x5 updated into x4
[TRACE] in ControlFlow: 2 | def x6      | x5 then x6
[TRACE] in ControlFlow: 2 |     x6 use  | x6 updated into x5
```

(timestamps omitted for brevity)

### Codegen

Code generation takes the graphs for the functions in the translation unit, and
generates code for a target. It currently targets x86_64 NASM, and, in the
future, might target LLVM IR.

See [NasmGenerator.kt][nasm_gen].

### References

The documentation in the code makes references to some of these documents,
especially to the C standard.

- [C11 draft standard][std_draft]
- [Fast dominance algorithm][dom_algo]
- [SSA book][ssa_book]
- [System V ABI][sysVabi]
- [NASM documentation][nasm]
- [x86_64 calling conventions][x64calling]
- [Intel 64 ISA reference][intel64isa]

[impl_defs]: ./ListOfBehaviours.md
[log4j2_xml]: ./src/main/resources/log4j2.xml
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
[const_fold]: ./src/main/kotlin/slak/ckompiler/analysis/ConstantFolding.kt
[nasm_gen]:
./src/main/kotlin/slak/ckompiler/backend/nasm_x86_64/NasmGenerator.kt
[diags]: ./src/main/kotlin/slak/ckompiler/Diagnostics.kt
[cfg_graphviz_create]: ./src/main/kotlin/slak/ckompiler/analysis/CFGGraphviz.kt
[ssa]: https://en.wikipedia.org/wiki/Static_single_assignment_form
[graphviz]: https://www.graphviz.org/
[intel64isa]:
https://www.intel.com/content/dam/www/public/us/en/documents/manuals/64-ia-32-architectures-software-developer-instruction-set-reference-manual-325383.pdf
[x64calling]:
https://en.wikipedia.org/wiki/X86_calling_conventions#x86-64_calling_conventions
[sysVabi]: https://www.uclibc.org/docs/psABI-x86_64.pdf
[dom_algo]: https://www.cs.rice.edu/~keith/EMBED/dom.pdf
[nasm]: https://nasm.us/doc/nasmdoc0.html
[ssa_book]: http://ssabook.gforge.inria.fr/latest/book.pdf
[log4j2]: https://logging.apache.org/log4j/2.x/
[post_order]: https://en.wikipedia.org/wiki/Tree_traversal#Post-order_(LRN)
[prec_climb]:
https://en.wikipedia.org/wiki/Operator-precedence_parser#Precedence_climbing_method
[std_draft]: http://www.open-std.org/jtc1/sc22/wg14/www/docs/n1570.pdf
