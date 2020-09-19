int main() {
  int x = 1;
  // live-out: x1
  while (x > 0) {
    // header:
    // live-in: x1, x5, x2
    // live-out: x2
    // before if
    // live-in: x2
    // live-out: x2
    if (2 > 1) {
      // live-in:
      x = -222;
      // live-out: x3
    }
    // live-in: x2, x3, x4
    x = x + 3;
    // live-out: x5
  }
  // live-in: x2
  return x;
}
