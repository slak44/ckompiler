# CKompiler

A C compiler in Kotlin. Written for fun, currently does very little.

Parser is hand-written recursive descent + [precedence climbing](https://en.wikipedia.org/wiki/Operator-precedence_parser#Precedence_climbing_method) for expressions.
Code generation will (eventually) target x86_64 assembly and maybe LLVM IR.

Tests are in `src/test/kotlin`.

Loosely follows the [C11 draft standard](http://www.open-std.org/jtc1/sc22/wg14/www/docs/n1570.pdf).
