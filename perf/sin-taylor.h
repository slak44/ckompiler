#ifndef SIN_TAYLOR
#define SIN_TAYLOR

#include <math.h>

double sin_taylor(double x, int terms) {
  double result = x;
  terms--;
  long factorial = 1;
  int sign = 1;
  double power = x;

  for (int i = 1; i < terms; i++) {
    sign *= -1;
    power *= x * x;
    factorial *= (i * 2) * (i * 2 + 1);
    if (factorial < 0) {
      break;
    }
    double term = sign * power / factorial;
    result += term;
  }

  return result;
}

#endif
