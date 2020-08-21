#include <stdio.h>
int main() {
  int r = 111;
  int u = 222;
  for (int i = 0; i < 2; i++) {
    printf("%d", i);
    if (i >= 1) {
      r = 4;
    }
    if (i < 1) {
      u = 5;
    }
  }
  if (r * u == 20) {
    r = 8;
  }
  return r + u;
}
