#include <stdio.h>

int main() {
  int i;
  int res0 = 0;
  int res1 = 1;
  int res2 = 2;
  int res3 = 3;
  for (i = 0; i < 1; i = i + 1) {
    res2 = 1 + 1;
    res3 = 2 + res0;
    if (70 >= 0) {
      printf("%d %d ", 1, 2);
      printf("%d %d ", 3, 4);
      printf("%d %d", 5, res1);
    } else {
      printf("%d %d", 1, 1);
    }
  }

  return 0;
}
