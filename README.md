# CKompiler

A C compiler written in Kotlin.

Parser is hand-written recursive descent + [precedence climbing][prec_climb] for expressions.
Code generation targets x86_64 NASM, and (maybe) LLVM IR in the future.

JUnit tests are in `src/test/kotlin`.

Tries to follow this [C11 draft standard][std_draft].

Also see [what was chosen for some implementation defined/undefined behaviours][impl_defs].

[prec_climb]: https://en.wikipedia.org/wiki/Operator-precedence_parser#Precedence_climbing_method
[std_draft]: http://www.open-std.org/jtc1/sc22/wg14/www/docs/n1570.pdf
[impl_defs]: ./ListOfBehaviours.md
