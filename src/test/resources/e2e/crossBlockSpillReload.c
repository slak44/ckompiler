int main() {
  int x0 = 1, x1 = -1, x2 = 1, x3 = -1, x4 = 1, x5 = -1, x6 = 1, x7 = -1;
  int x8 = 1, x9 = -1, x10 = 1, x11 = -1, x12 = 1, x13;
  int spilled;
  // 14 ints + the temporary used in the if, to force a spill

  if (1) {
    spilled = 1;
    x13 = spilled;
  } else {
    spilled = 2;
    x13 = spilled;
  }

  return x0 + x1 + x2 + x3 + x4 + x5 + x6 + x7 + x8 + x9 + x10 + x11 + x12 + x13 + spilled;
}
