int main() {
  int x = 1;
  // live-out: x1
  if (x > 0) {
    // live-in: x1
    // live-out: x1
    if (2 > 1) {
      // live-in:
      x = 4;
      // live-out: x2
    }
    // live-in: x1, x2, x3
    x = x + 3;
    // live-out: x4
  }
  // live-in: x1, x4, x5
  return x;
}
