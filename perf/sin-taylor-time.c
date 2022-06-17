#include <stdio.h>
#include <time.h>

#include "./sin-taylor.h"

int main() {
  struct timespec now;
  timespec_get(&now, TIME_UTC);
  long start = ((long) now.tv_sec * 1000) + now.tv_nsec / 1000000;

  int terms = 10;
  double pi = 3.14159265358979323846;
  double step = 0.0001;

  for (double rads = -2 * pi; rads < 2 * pi; rads += step) {
    printf("%.05lf\n", sin_taylor(rads, terms));
  }

  timespec_get(&now, TIME_UTC);
  long end = ((long) now.tv_sec * 1000) + now.tv_nsec / 1000000;

  printf("time: %ld ms\n", end - start);
}
