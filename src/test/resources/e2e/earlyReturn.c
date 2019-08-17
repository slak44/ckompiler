#include <stdio.h>

void f(int a) {
  if (a > 5) return;
  printf("ding");
}

int main() {
  f(6);
  return 0;
}
