int main() {
  int x = 1;
  int y = 32;
  // live-out: x1, y1
  if (x > 0) {
    // live-in: x1
    y = 2;
    // live-out x1, y2
  } else {
    // live-in: y1
    x = 0;
    // live-out: x2, y1
  }
  // live-in: x1, x2, y1, y2, x3, y3
  return x + y;
}
