# List of Implementation Defined Behaviours

- VLAs are unsupported
- `_Complex` is unsupported
- (J.3.4): Multi-byte character constants are truncated to the first
char
- (J.3.3): The number of significant initial characters in an identifier
is unlimited
- (J.3.5): There are no extended integer types
- (J.3.4/6.2.5.0.15): `char` behaves like `signed char`
- (J.3.13): The value of the result of `sizeof` and `_Alignof` is of
type `unsigned int`
- (J.3.1/5.1.1.2.0.1.3): Sequences of whitespace are retained in
translation phase 3
- (J.3.4/5.2.2.0.3): Escape sequences produce the relevant ASCII codes
- (6.10.2.0.4): Macro replaced `#include` directives are resolved using
diagnostic information for the given pp-tokens

# List of Undefined Behaviours

- (6.5.7.0.3) `<<` and `>>` pass all values as they are to the assembler
- Empty character constants `''` have the value `0`
- (6.4.5.0.7) String literals are stored in the .data section, so what happens
  is up to the assembler/linker/etc
- (6.5.2.2) The evaluation order of function call arguments is the reverse order
  of declaration (by default, certain things are able to influence it)
- (6.10.1.0.4) When the `defined` unary operator is used incorrectly, we print
  an error diagnostic, and the entire expression has value `0`
- (6.3.2.1.0.3) Lvalue conversion on an array object with `register` storage
  class is ignored, address is taken
- (J.2/6.2.4) The value of objects with automatic storage duration when used
  while it is indeterminate is the value in the first register of the correct
  type (for example, on X64 that means `rax` for ints and `xmm0` for floats)

# List of Unspecified Behaviours

- (6.4.5.0.7) The arrays are not distinct if they have the same encoding
and the same contents
