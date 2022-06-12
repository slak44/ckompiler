int fun(int x1, int x2, int x3, int x4, int x5, int x6, int x7, int x8, int x9, int x10) {
}

int main() {
  int x1 = 1, x2 = -2, x3 = 3, x4 = -4, x5 = 5, x6 = -6, x7 = 7,
  x8 = -8, x9 = 9, x10 = -10, x11 = 11, x12 = -12, x13 = 13, x14 = -14, x15 = 15;

  int res = fun(x1, x2, x3, x4, x5, x6, x7, x8, x9, x10);

  int sum = x1 + x2 + x3 + x4 + x5 + x6 + x7 + x8 + x9 + x10 + x11 + x12 + x13 + x14 + x15;

  return sum + res; // 8 + 8
}
