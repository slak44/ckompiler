#include <stdio.h>

// stdout = "Hello World!"
// exit code = 62 (162 - 100, avoid a large exit code)
int main() {
  printf("Hello World!");

  int first = 123;
  int second = first / 2;
  if (first > 5346) {
    first += 56;
  } else {
    second -= 22;
  }

  while (234 + first) {
    first--;
  }

  for (int i = 23; i < 66; i++) {
    second = first + second / i;
  }

  double d = 32.23;
  do {
    d += 2;
  } while (d < 123.1234);

  return first * second - 100;
}
