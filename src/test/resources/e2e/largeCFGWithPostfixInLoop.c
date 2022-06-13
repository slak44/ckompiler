// This file contains nested loops with "i++"
// These can interact poorly with the register allocator in a sufficiently large CFG, which this is
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
  if (-143 >= 0) {
    for (i = 0; i < 8; i++) {
      for (i = 0; i < 12; i++) {
        if (res0 >= 0) {
          if (-23 >= 0) {
            printf("%d %d", 172, res0);
            printf("%d %d", -92, res0);
            // this is a random comment!
            printf("%d %d", -47, 196);
            // this is a random comment!
            printf("%d %d", res0, 56);
            // this is a random comment!
          } else {
            if (-15 >= 0) {
              res0 = res0 + res0;
              printf("%d %d", -50, -120);
              // this is a random comment!
            } else {
              for (i = 0; i < 18; i++) {
                if (res0 >= 0) {
                  if (163 >= 0) {
                    if (-146 >= 0) {
                      printf("%d %d", -95, -179);
                      // this is a random comment!
                      printf("%d %d", 28, res0);
                      // this is a random comment!
                    }
                  } else {
                    for (i = 0; i < 11; i++) {
                      for (i = 0; i < 9; i++) {
                        for (i = 0; i < 7; i++) {
                          if (19 >= 0) {
                            printf("%d %d", res0, res0);
                            // this is a random comment!
                            printf("%d %d", 21, -99);
                            // this is a random comment!
                            res1 = -1 - 189;
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        } else {
          if (res0 >= 0) {
            if (res1 >= 0) {
              if (18 >= 0) {
                if (res1 >= 0) {
                  if (res0 >= 0) {
                    res2 = 163 * -159;
                    res3 = res0 < 18;
                    // this is a random comment!
                    res4 = res2 < 149;
                    // this is a random comment!
                  } else {
                    for (i = 0; i < 13; i++) {
                      printf("%d %d", 67, res2);
                      // this is a random comment!
                      printf("%d %d", res1, res3);
                      // this is a random comment!
                      res5 = res3 - -43;
                      // this is a random comment!
                      printf("%d %d", -129, res5);
                      // this is a random comment!
                    }
                  }
                }
              }
            }
          } else {
            for (i = 0; i < 9; i++) {
              if (res0 >= 0) {
                for (i = 0; i < 2; i++) {
                  printf("%d %d", res2, -147);
                  // this is a random comment!
                  res6 = 194 + 115;
                  res7 = -98 - res3;
                  res8 = res4 + res2;
                  // this is a random comment!
                }
              }
            }
          }
        }
      }
    }
  }

  return 0;
}
