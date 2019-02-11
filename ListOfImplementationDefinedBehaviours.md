# List of Implementation Defined Behaviours

(some unspecified/undefined behaviours are also included)

- VLAs are unsupported
- `_Complex` is unsupported
- Empty character constants `''` have the value `0`
- (J.3.4): Multi-byte character constants are truncated to the first char
- (J.3.3): The number of significant initial characters in an identifier is unlimited
- (J.3.5): There are no extended integer types
- (J.3.4/6.2.5.0.15): `char` behaves like `signed char`
- (J.3.13): The value of the result of `sizeof` and `_Alignof` is of type `unsigned int`
