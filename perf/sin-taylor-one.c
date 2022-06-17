#include <stdio.h>

#include "./sin-taylor.h"

int main() {
  double x;
  int terms;
  scanf("%lf %d", &x, &terms);
  printf("%.05lf\n", sin_taylor(x, terms));
}
