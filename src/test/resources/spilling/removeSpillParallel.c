#include <stdio.h>

int main() {
  int n = 1;

  float spilled = 1;

  for (int i = 0; i < n; ++i) {
    float p = 0.0f;
    spilled *= p;
  }
  printf("haha");
  printf("haha");
  // The two calls here split the live range twice
  if (1) {
    // Then there will be a use in a later block
    printf("%.4f\n", spilled);
  }
  return 0;
}
