#include <stdio.h>

#include "./sin-taylor.h"

int main() {
  int terms = 10;
  double pi = 3.14159265358979323846;
  double step = 0.000001;

  for (double rads = -2 * pi; rads < 2 * pi; rads += step) {
    printf("%.05lf\n", sin_taylor(rads, terms));
  }
}
