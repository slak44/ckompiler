// This CFG contains lots of dead phi instructions in the exit block
#include <stdio.h>

int main() {
  int i;
  int res0 = 0;
  int res1 = 1;
  int res2 = 2;
  int res3 = 3;
  int res4 = 4;
  int res5 = 5;
  int res6 = 6;
  int res7 = 7;
  int res8 = 8;
  int res9 = 9;
  int res10 = 10;
  int res11 = 11;
  if (115 >= 0) {
    if (21 >= 0) {
      for (i = 0; i < 1; i = i + 1) {
        printf("%d %d", 100, res1);
        res2 = res1 * 119;
        res3 = 36 == res1;
      }
    } else {
      printf("%d %d", res1, 29);
      res4 = res3 < res3;
      res5 = res4 + res3;
    }
  } else {
    if (-105 >= 0) {
      for (i = 0; i < 1; i = i + 1) {
        for (i = 0; i < 16; i = i + 1) {
          if (9 >= 0) {
            if (res1 >= 0) {
              if (res0 >= 0) {
                if (178 >= 0) {
                  res6 = res3 - -180;
                  printf("%d %d", 10, res2);
                  res7 = res4 - res2;
                  printf("%d %d", res4, res6);
                }
              } else {
                res8 = res5 == res7;
                res9 = 149 == res0;
                res10 = 190 - 54;
              }
            } else {
              printf("%d %d", 114, res7);
              res11 = res4 * res6;
            }
          }
        }
      }
    }
  }
  return 0;
}
