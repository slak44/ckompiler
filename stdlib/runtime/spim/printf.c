#include <stdbool.h>
#include <stdlib.h>

extern void __builtin_print_char(char c);
extern void __builtin_print_int(int c);
extern void __builtin_print_string(const char* str);
extern void __builtin_print_float(float f);

int __builtin_printf_no_va(const char* format, void** args) {
  char c;
  bool was_last_format = false;
  int current_arg = 0;

  while ((c = *(format++)) != '\0') {
    bool is_format = c == '%';
    if (is_format) {
      if (was_last_format) {
        // Escaped %%
        __builtin_print_char('%');
        was_last_format = false;
      } else {
        was_last_format = true;
      }
      continue;
    }

    if (!was_last_format) {
      __builtin_print_char(c);
    } else {
      if (c == 'd') {
        void* i = args[current_arg++];
        __builtin_print_int((int) i);
      } else if (c == 'c') {
        void* i = args[current_arg++];
        __builtin_print_char((int) i);
      } else if (c == 's') {
        const char* str = args[current_arg++];
        __builtin_print_string(str);
      } else if (c == 'f') {
        void* f = args[current_arg++];
        __builtin_print_float((float) (int) f);
      } else {
        exit(1);
      }
    }
    was_last_format = false;
  }

  return 0;
}
