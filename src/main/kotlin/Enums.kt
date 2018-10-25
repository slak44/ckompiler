enum class Keywords(val keyword: String) {
  AUTO("auto"),
  BREAK("break"),
  CASE("case"),
  CHAR("char"),
  CONST("const"),
  CONTINUE("continue"),
  DEFAULT("default"),
  DOUBLE("double"),
  DO("do"),
  ELSE("else"),
  ENUM("enum"),
  EXTERN("extern"),
  FLOAT("float"),
  FOR("for"),
  GOTO("goto"),
  IF("if"),
  INLINE("inline"),
  INT("int"),
  LONG("long"),
  REGISTER("register"),
  RESTRICT("restrict"),
  RETURN("return"),
  SHORT("short"),
  SIGNED("signed"),
  SIZEOF("sizeof"),
  STATIC("static"),
  STRUCT("struct"),
  SWITCH("switch"),
  TYPEDEF("typedef"),
  UNION("union"),
  UNSIGNED("unsigned"),
  VOID("void"),
  VOLATILE("volatile"),
  WHILE("while"),
  ALIGNAS("_Alignas"),
  ALIGNOF("_Alignof"),
  ATOMIC("_Atomic"),
  BOOL("_Bool"),
  COMPLEX("_Complex"),
  GENERIC("_Generic"),
  IMAGINARY("_Imaginary"),
  NORETURN("_Noreturn"),
  STATIC_ASSERT("_Static_assert"),
  THREAD_LOCAL("_Thread_local")
}

enum class Punctuators(val punct: String) {
  LSQPAREN("["), RSQPAREN("]"), LPAREN("("), RPAREN(")"),
  LBRACKET("{"), RBRACKET("}"), DOTS("..."), DOT("."), ARROW("->"),
  MUL_ASSIGN("*="), DIV_ASSIGN("/="), MOD_ASSIGN("%="), PLUS_ASSIGN("+="),
  SUB_ASSIGN("-="), LSH_ASSIGN("<<="), RSH_ASSIGN(">>="),
  AND_ASSIGN("&="), XOR_ASSIGN("^="), OR_ASSIGN("|="),
  AND("&&"), OR("||"),
  INC("++"), DEC("--"), AMP("&"), STAR("*"), PLUS("+"), MINUS("-"),
  TILDE("~"),
  LESS_COLON("<:"), LESS_PERCENT("<%"),
  SLASH("/"), LSH("<<"), RSH(">>"),
  LEQ("<="), GEQ(">="), LT("<"), GT(">"),
  EQUALS("=="), NEQUALS("!="), CARET("^"), PIPE("|"),
  QMARK("?"), SEMICOLON(";"),
  NOT("!"),
  ASSIGN("="),
  COMMA(","), DOUBLE_HASH("##"), HASH("#"),
  COLON_MORE(":>"), COLON(":"), PERCENT_MORE("%>"),
  PERCENT_COLON_PERCENT_COLON("%:%:"), PERCENT_COLON("%:"), PERCENT("%");

  fun toOperator(): Optional<Operators> = Operators.values().find { it.op == this }.opt()
}

enum class Associativity { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

enum class Arity { UNARY, BINARY, TERNARY }

enum class Operators(val op: Punctuators,
                     val precedence: Int,
                     val arity: Arity,
                     val assoc: Associativity) {
  // Postfix
  // FIXME [], func calls
  ACCESS(Punctuators.DOT, 124, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  PTR_ACCESS(Punctuators.ARROW, 123, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  // FIXME punctuator conflict
//  POSTFIX_INC(Punctuators.INC, 122, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
//  POSTFIX_DEC(Punctuators.DEC, 121, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
  // FIXME initializer list stuff
  // Prefix
  // FIXME punctuator conflict
//  PREFIX_INC(Punctuators.INC, 115, Arity.UNARY, Associativity.RIGHT_TO_LEFT),
//  PREFIX_DEC(Punctuators.DEC, 110, Arity.UNARY, Associativity.RIGHT_TO_LEFT),
  // Unary
  REF(Punctuators.AMP, 100, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
  // FIXME punctuator conflict
//  DEREF(Punctuators.STAR, 100, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
//  PLUS(Punctuators.PLUS, 100, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
//  MINUS(Punctuators.MINUS, 100, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
  BIT_NOT(Punctuators.TILDE, 100, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
  NOT(Punctuators.NOT, 100, Arity.UNARY, Associativity.LEFT_TO_RIGHT),
  // FIXME here should be casts, alignof and sizeof
  // Arithmetic
  MUL(Punctuators.STAR, 95, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  DIV(Punctuators.SLASH, 95, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  MOD(Punctuators.PERCENT, 95, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  ADD(Punctuators.PLUS, 90, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  SUB(Punctuators.MINUS, 90, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  // Bit-shift
  LSH(Punctuators.LSH, 80, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  RSH(Punctuators.RSH, 80, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  // Relational
  LT(Punctuators.LT, 70, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  GT(Punctuators.GT, 70, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  LEQ(Punctuators.LEQ, 70, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  GEQ(Punctuators.GEQ, 70, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  // Equality
  EQ(Punctuators.EQUALS, 60, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  NEQ(Punctuators.NEQUALS, 60, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  // Bitwise
  BIT_AND(Punctuators.AMP, 58, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  BIT_XOR(Punctuators.CARET, 54, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  BIT_OR(Punctuators.PIPE, 50, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  // Logical
  AND(Punctuators.AND, 45, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  OR(Punctuators.OR, 40, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
  // Ternary
  COND(Punctuators.QMARK, 30, Arity.TERNARY, Associativity.RIGHT_TO_LEFT),
  // Assignment
  ASSIGN(Punctuators.ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  MUL_ASSIGN(Punctuators.MUL_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  DIV_ASSIGN(Punctuators.DIV_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  MOD_ASSIGN(Punctuators.MOD_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  PLUS_ASSIGN(Punctuators.PLUS_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  SUB_ASSIGN(Punctuators.SUB_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  LSH_ASSIGN(Punctuators.LSH_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  RSH_ASSIGN(Punctuators.RSH_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  AND_ASSIGN(Punctuators.AND_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  XOR_ASSIGN(Punctuators.XOR_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  OR_ASSIGN(Punctuators.OR_ASSIGN, 20, Arity.BINARY, Associativity.RIGHT_TO_LEFT),
  // Comma
  COMMA(Punctuators.COMMA, 10, Arity.BINARY, Associativity.LEFT_TO_RIGHT),
}

enum class IntegralSuffix(val length: Int) {
  UNSIGNED(1), LONG(1), LONG_LONG(2),
  UNSIGNED_LONG(2), UNSIGNED_LONG_LONG(3),
  NONE(0)
}

enum class FloatingSuffix(val length: Int) {
  FLOAT(1), LONG_DOUBLE(1), NONE(0)
}

enum class Radix(val prefixLength: Int) {
  DECIMAL(0), OCTAL(0), HEXADECIMAL(2);

  fun toInt(): Int = when (this) {
    DECIMAL -> 10
    OCTAL -> 8
    HEXADECIMAL -> 16
  }
}

enum class StringEncoding(val prefixLength: Int) {
  CHAR(0), UTF8(2), WCHAR_T(1), CHAR16_T(1), CHAR32_T(1)
}

enum class CharEncoding(val prefixLength: Int) {
  UNSIGNED_CHAR(0), WCHAR_T(1), CHAR16_T(1), CHAR32_T(1)
}
