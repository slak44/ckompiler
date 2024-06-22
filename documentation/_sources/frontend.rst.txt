.. include:: _config.rst

Front-end
=========

Lexer
-----

The `lexer`_ operates on the source code string, and produces a list of `LexicalTokens`_ and
its subclasses.
It is integrated in the `Preprocessor`_.
Together, they deal with translation phases 1-6.

.. _lexer: https://github.com/slak44/ckompiler/tree/master/src/main/kotlin/slak/ckompiler/lexer/LexicalElements.kt
.. _LexicalTokens: https://github.com/slak44/ckompiler/tree/master/src/main/kotlin/slak/ckompiler/lexer/TokenModel.kt
.. _Preprocessor: https://github.com/slak44/ckompiler/tree/master/src/main/kotlin/slak/ckompiler/lexer/Preprocessor.kt

Parser
------

The `parser`_ operates on the token list, and produces an `abstract syntax tree`_ of `ASTNode` instances.
It also performs type checks, resolves scope, identifier names and other such semantic requirements.

It is mostly a hand-written recursive descent parser, with a `precedence climbing`_ parser integrated for expressions,
which can be found in the `ExpressionParser`_ class.

Much of the parser is built out of loosely coupled classes that each handle parts of parsing the grammar
(eg `ParenMatcher`_ handles everything about matching parenthesis, `StatementParser`_ parses function block
statements, etc).
Each of these has an associated interface (`IExpressionParser` for the `ExpressionParser` class), that is used for
delegation.

.. _parser: https://github.com/slak44/ckompiler/tree/master/src/main/kotlin/slak/ckompiler/parser/
.. _abstract syntax tree:
   https://github.com/slak44/ckompiler/tree/master/src/main/kotlin/slak/ckompiler/parser/SyntaxTreeModel.kt
.. _precedence climbing: https://en.wikipedia.org/wiki/Operator-precedence_parser#Precedence_climbing_method
.. _ExpressionParser:
   https://github.com/slak44/ckompiler/tree/master/src/main/kotlin/slak/ckompiler/parser/ExpressionParser.kt
.. _ParenMatcher: https://github.com/slak44/ckompiler/tree/master/src/main/kotlin/slak/ckompiler/parser/ParenMatcher.kt
.. _StatementParser:
   https://github.com/slak44/ckompiler/tree/master/src/main/kotlin/slak/ckompiler/parser/StatementParser.kt

Interfaces and Delegation
^^^^^^^^^^^^^^^^^^^^^^^^^

Many components in the compiler are decoupled: they exist as concrete classes
that implement an associated interface. Users of the components are given an
instance of the concrete classes, and they use it to immediately delegate the
associated interfaces.

For example, when the `DeclarationParser` needs to parse an initializer
expression, it implements the `IExpressionParser` interface, and delegates that
job to a concrete `ExpressionParser` instance received through the constructor
(or, in some cases, via `lateinit` properties, to resolve cyclic dependencies).

As a code example, the largest of the components, `StatementParser`, is declared like this:

.. code-block:: kotlin

    class StatementParser(
        declarationParser: DeclarationParser,
        controlKeywordParser: ControlKeywordParser,
        constExprParser: ConstantExprParser
    ) : IStatementParser,
        ITokenHandler by declarationParser,
        IScopeHandler by declarationParser,
        IParenMatcher by declarationParser,
        IExpressionParser by controlKeywordParser,
        IConstantExprParser by constExprParser,
        IDeclarationParser by declarationParser,
        IControlKeywordParser by controlKeywordParser { /* ... */ }

As the code shows, `StatementParser` doesn't receive 7 separate components, even
though it delegates the implementation of 7 different interfaces.
Since the `DeclarationParser` implements some of those interfaces itself,
`StatementParser` only needs an instance of that for all of them.

This approach has several advantages:

1. Components are forced to be written against the exposed interfaces, allowing implementation details to be hidden in
   the concrete classes.
2. The individual interfaces are simple compared to a monolith approach, where every component would have access to
   every other component.
3. The dependencies between components are made explicit; for example, a `ScopeHandler` has no business using a
   `ControlKeywordParser`. Requiring manual delegation helps prevent accidental coupling.
4. The delegate syntax is clean: there is usually no need to write
   `component.doThing()`, rather `doThing()` can be called directly. This is
   most obvious in parser components using `ITokenHandler`, since they have
   lots (hundreds) of calls to functions like `current`, `eat`, `isEaten` or
   `tokenContext`. Without delegation, they'd end up polluting the source with
   `tokenHandler.current()` everywhere, which is not great for readability.


The `ITokenHandler` Interface
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

This interface allows a user to interact with a list of `LexicalToken`. It
provides functions to process a token, move past it to the next one, search for
the first token to meet a condition, or easily get debug data.

The terminology used in this interface's methods relates to "eating" tokens.
Eating a token means it was processed, consumed, or dealt with in some way, and
further calls to the `current` function will return the next available token. As
expected, `isEaten` returns true if there are no more tokens left.

It is used both in the parser, and in the preprocessor.

By far the most interesting feature, however, is the `tokenContext` function.
One of the most common operations when parsing is having to create a
"sub-parser" for certain nested data: the contents of a set of parenthesis in
expressions, statement blocks for functions, argument lists for function calls
or function prototypes, and many more.
The `tokenContext` function takes an end index, and a lambda. A sublist is
created, including the tokens from the current one, to the one specified by the
end index. This is just a view into the larger list of tokens, so no array
copies are made. The lambda is then executed.
This is how context nesting works in an expression:

.. code-block:: text

  2 + (6 - 3 * (2 + 2) - ((7 * 7) / 2)) * 5
  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ Root context
  2 + (6 - 3 * (2 + 2) - ((7 * 7) / 2)) * 5
       ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^      Outer paren's context (1st tier)
  2 + (6 - 3 * (2 + 2) - ((7 * 7) / 2)) * 5
                ^^^^^     ^^^^^^^^^^^       Each inner paren has its own context,
                                            but they're at the same "tier", not
                                            nested (2nd tier)
  2 + (6 - 3 * (2 + 2) - ((7 * 7) / 2)) * 5
                           ^^^^^            Finally, the inner-most paren has yet
                                            another context, nested 3 tiers deep

Eating tokens in nested contexts advances the parent contexts through them. On
the diagram, eating the `7 * 7` tokens in the inner-most context will advance
all the 3 levels above beyond these tokens (but no further!).

The context's magic lies in the fact that the behaviour of the `ITokenHandler`'s
functions is dynamic based on the token context. For example, if we're parsing
an expression such as `1 + (2 * 3 - 4) - 7`, the parens will get their own
context. Eating tokens (via `eat` or `eatUntil`) in this context will never eat
tokens beyond the contents of the parens. In context, the `isEaten` function
will return true after `4` was eaten, even if there are other tokens afterwards.

Most functions of the interface react to the context. As a result, parsing can
be reasoned about in discrete chunks: each context deals with its own contents,
and does not care what is being parsed in the parent contexts. Let's say an
error is encountered inside a context: `1 + (2 * 3 - ) - 7`. There is a missing
primary expression inside the paren. The expression parser notices, and consumes
all the tokens in the context. However, this does not affect the outer
expression's: the interface provides no way to eat tokens beyond the ones
allocated to the context, accidentally or otherwise.

Nested Declarator Parsing
^^^^^^^^^^^^^^^^^^^^^^^^^

Parsing declarators gets very complicated, very fast when nested declarators come
into play. A typical declaration with nested declarators looks like:

.. code-block:: text

  int * (*f(int x))(double y)
  ^^^                         declaration specifiers
  int * (*f(int x))(double y)
        ^^^^^^^^^^^           nested declarator
  int * (*f(int x))(double y)
                   ^^^^^^^^^^ declarator suffix for non-nested declarator
  int * (*f(int x))(double y)
           ^^^^^^^            declarator suffix for nested declarator
  int * (*f(int x))(double y)
      ^                       indirection that "belongs" to the declaration specifiers
                              (from "int" to "pointer to int")
  int * (*f(int x))(double y)
         ^                    indirection that "belongs" to the declarator suffix
                              (from "function" type to "pointer to function" type)
  int * (*f(int x))(double y)
          ^                   designator for the resulting declaration
                              (ie the name of the function)

The example declaration declares a function called `f`, that takes one int
parameter called `x`, and returns a pointer to a function that also takes one
parameter, a double `y`, and returns a pointer to an int.

Indirection binds in reverse order of suffixes: the first indirection binds to
the last suffix, and the last indirection binds to the first suffix. This
reflects the declarator nesting.

Dereferencing the int pointer returned by calling the returned function pointer,
in one expression, looks like this:

.. code-block:: text

  int result = *(f(1)(2.0));

Yes, this is why typedefs exist.

Errors
------

A compiler has to deal with two classes of "errors"; those that come from the
code being compiled ("diagnostics"), and those that signal issues in the compiler itself.

All the relevant code can be found in the `Diagnostics.kt`_ file.

.. _Diagnostics.kt: https://github.com/slak44/ckompiler/tree/master/src/main/kotlin/slak/ckompiler/Diagnostics.kt

Diagnostics
^^^^^^^^^^^

Diagnostics are handled by the `DebugHandler` class (and its corresponding interface, `IDebugHandler`).

Printed diagnostics look like this:

.. raw:: html

  <div class="highlight">
    <pre>
    someFile.c:1:27: <span style="color: red">error:</span> Expression is not assignable [Parser|EXPRESSION_NOT_ASSIGNABLE]
    int main() {int x; (x + 2) = 5;}
                        <span style="color: green">~~~~~  ^</span>
    </pre>
  </div>

We generate `Diagnostic` instances using a simple DSL, but the error messages,
the source extracts, and the caret/tilde markers are created lazily, internally.
They are only computed when (or if) the diagnostic gets printed. This is useful,
because it makes creating a `Diagnostic` instance relatively cheap. As a result,
we can create diagnostics even if they might be discarded later, with little
cost. One such place where diagnostics are sometimes discarded is
`ConstantExprParser#evaluateExpr`.

An example of the `IDebugHandler.diagnostic` DSL, used in the parser:

.. code-block:: kotlin

  // ...
  diagnostic {
    id = DiagnosticId.INVALID_ARGUMENT_UNARY
    formatArgs(expr.type, op.op.s)
    errorOn(c..expr)
  }
  // ...

The range passed to `errorOn` in the example above, is an implementor of the `SourcedRange` interface.

The `rangeTo` operator is overloaded on `SourcedRange` to easily combine multiple ranges into one, like in this example
from the parser:

.. code-block:: kotlin

  sizeOf..tokenAt(rParenIdx)

The `sizeOf` token and the token returned by `tokenAt(rParenIdx)` are not
adjacent (think of `sizeof(int)`), but this overload allows the parser to
trivially create a compound `SourcedRange` to cover the entire sizeof
expression.

Since `LexicalToken` and `ASTNode` are implementations of `SourcedRange`,
compound ranges can be created by mixing and matching tokens and AST pieces
(`tok..node` and `node..tok` are both valid syntax).

Another example, used in `sequentialize`:

.. code-block:: kotlin

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

This illustrates the utility provided by using a lambda + builder DSL. Arbitrary
code can run in the construction of the diagnostic, so the same diagnostic can
be tailored to different situations.

Finally, an example from the preprocessor:

.. code-block:: kotlin

  // ...
  diagnostic {
    id = if (ignoreTrigraphs) DiagnosticId.TRIGRAPH_IGNORED else DiagnosticId.TRIGRAPH_PROCESSED
    if (!ignoreTrigraphs) formatArgs(replacement)
    columns(matchResult.start() until matchResult.end())
  }
  // ...

Even the kind of diagnostic can be dynamically selected based on arbitrary
logic, which makes it easy to support feature flags like `-fno-trigraphs` in
diagnostics.

Compiler Errors
^^^^^^^^^^^^^^^

For actual issues in the compiler, we use logging, the
`InternalCompilerError` class, the `throwICE` extension methods on logger
instances, and Kotlin's stdlib functions from `Preconditions.kt`.

We use `a Kotlin wrapper <https://github.com/MicroUtils/kotlin-logging>`_ for slf4j's API in common code.
For the JVM, `Log4j 2 <https://logging.apache.org/log4j/2.x/>`_ is the logging backend.

Instances of ICE, `IllegalArgumentException` or `IllegalStateException` being thrown means
invariants were violated, "impossible" situations occurred, or misuse of an API
was encountered. As a result, these exceptions should not be caught anywhere: it
is desirable for the application to crash if someone threw an ICE. Any one of
these exceptions being thrown is an unfixed bug in the compiler.

Since the compiler is still a work in progress, there are many features/code
paths that are not yet implemented. They generally do not throw ICEs, rather
they use `NotImplementedError` created by the `TODO` function.
