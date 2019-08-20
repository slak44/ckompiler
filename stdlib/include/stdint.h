// C standard: 7.20.1.1, 7.20.1.3, 7.20.2.1, 7.20.2.3
#ifdef __INT8_T_TYPE
typedef __INT8_T_TYPE int8_t;
typedef unsigned __INT8_T_TYPE uint8_t;

typedef __INT8_T_TYPE int_fast8_t;
typedef unsigned __INT8_T_TYPE uint_fast8_t;

#define UINT8_MAX (128)
#define INT8_MIN (-128)
#define INT8_MAX (127)

#define UINT_FAST8_MAX UINT8_MAX
#define INT_FAST8_MIN INT8_MIN
#define INT_FAST8_MAX INT8_MAX
#endif

#ifdef __INT16_T_TYPE
typedef __INT16_T_TYPE int16_t;
typedef unsigned __INT16_T_TYPE uint16_t;

typedef __INT16_T_TYPE int_fast16_t;
typedef unsigned __INT16_T_TYPE uint_fast16_t;

#define UINT16_MAX (32768)
#define INT16_MIN (-32768)
#define INT16_MAX (32767)

#define UINT_FAST16_MAX UINT16_MAX
#define INT_FAST16_MIN INT16_MIN
#define INT_FAST16_MAX INT16_MAX
#endif

#ifdef __INT32_T_TYPE
typedef __INT32_T_TYPE int32_t;
typedef unsigned __INT32_T_TYPE uint32_t;

typedef __INT32_T_TYPE int_fast32_t;
typedef unsigned __INT32_T_TYPE uint_fast32_t;

#define UINT32_MAX (2147483648)
#define INT32_MIN (-2147483648)
#define INT32_MAX (2147483647)

#define UINT_FAST32_MAX UINT32_MAX
#define INT_FAST32_MIN INT32_MIN
#define INT_FAST32_MAX INT32_MAX
#endif

#ifdef __INT64_T_TYPE
typedef __INT64_T_TYPE int64_t;
typedef unsigned __INT64_T_TYPE uint64_t;

typedef __INT64_T_TYPE int_fast64_t;
typedef unsigned __INT64_T_TYPE uint_fast64_t;

#define UINT64_MAX (9223372036854776000)
#define INT64_MIN (-9223372036854776000)
#define INT64_MAX (9223372036854776000)

#define UINT_FAST64_MAX UINT64_MAX
#define INT_FAST64_MIN INT64_MIN
#define INT_FAST64_MAX INT64_MAX
#endif

// C standard: 7.20.1.2, 7.20.2.2
#ifdef __INT_LEAST8_T_TYPE
typedef __INT_LEAST8_T_TYPE int_least8_t;
typedef unsigned __INT_LEAST8_T_TYPE uint_least8_t;

#define UINT_LEAST8_MAX ((1 << __INT_LEAST8_T_SIZE) - 1)
#define INT_LEAST8_MIN (-((1 << (__INT_LEAST8_T_SIZE - 1)) - 1))
#define INT_LEAST8_MAX ((1 << (__INT_LEAST8_T_SIZE - 1)) - 1)
#endif

#ifdef __INT_LEAST16_T_TYPE
typedef __INT_LEAST16_T_TYPE int_least16_t;
typedef unsigned __INT_LEAST16_T_TYPE uint_least16_t;

#define UINT_LEAST16_MAX ((1 << __INT_LEAST16_T_SIZE) - 1)
#define INT_LEAST16_MIN (-((1 << (__INT_LEAST16_T_SIZE - 1)) - 1))
#define INT_LEAST16_MAX ((1 << (__INT_LEAST16_T_SIZE - 1)) - 1)
#endif

#ifdef __INT_LEAST32_T_TYPE
typedef __INT_LEAST32_T_TYPE int_least32_t;
typedef unsigned __INT_LEAST32_T_TYPE uint_least32_t;

#define UINT_LEAST32_MAX ((1 << __INT_LEAST32_T_SIZE) - 1)
#define INT_LEAST32_MIN (-((1 << (__INT_LEAST32_T_SIZE - 1)) - 1))
#define INT_LEAST32_MAX ((1 << (__INT_LEAST32_T_SIZE - 1)) - 1)
#endif

#ifdef __INT_LEAST64_T_TYPE
typedef __INT_LEAST64_T_TYPE int_least64_t;
typedef unsigned __INT_LEAST64_T_TYPE uint_least64_t;

#define UINT_LEAST64_MAX ((1 << __INT_LEAST64_T_SIZE) - 1)
#define INT_LEAST64_MIN (-((1 << (__INT_LEAST64_T_SIZE - 1)) - 1))
#define INT_LEAST64_MAX ((1 << (__INT_LEAST64_T_SIZE - 1)) - 1)
#endif

// C standard: 7.20.1.4, 7.20.2.4
#ifdef __INTPTR_T_TYPE
typedef __INTPTR_T_TYPE intptr_t;
typedef unsigned __INTPTR_T_TYPE uintptr_t;

#define UINTPTR_MAX ((1 << __INTPTR_T_SIZE) - 1)
#define INTPTR_MIN (-((1 << (__INTPTR_T_SIZE - 1)) - 1))
#define INTPTR_MAX ((1 << (__INTPTR_T_SIZE - 1)) - 1)
#endif

// C standard: 7.20.1.5
#ifdef __INTMAX_T_TYPE
typedef __INTMAX_T_TYPE intmax_t;
typedef unsigned __INTMAX_T_TYPE uintmax_t;

#define UINTMAX_MAX ((1 << __INTMAX_T_SIZE) - 1)
#define INTMAX_MIN (-((1 << (__INTMAX_T_SIZE - 1)) - 1))
#define INTMAX_MAX ((1 << (__INTMAX_T_SIZE - 1)) - 1)
#endif

// C standard: 7.20.3
#define PTRDIFF_MIN (-(1 << (__PTRDIFF_T_SIZE - 1)) - 1)
#define PTRDIFF_MAX ((1 << (__PTRDIFF_T_SIZE - 1)) - 1)
#define SIZE_MAX ((1 << __SIZE_T_SIZE) - 1)
#define WCHAR_MIN (-(1 << (__WCHAR_T_SIZE - 1)) - 1)
#define WCHAR_MAX ((1 << (__WCHAR_T_SIZE - 1)) - 1)
