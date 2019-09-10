int main() {
  int x = 22;
  int y = 44;
  if (x == 23 && y == 44) {
    x += 55;
    y -= 2;
  }
  if (x < 23 || y == 9999) {
    x -= 2;
    y -= 9;
  }
  return x + y;
  // This code can be completely constant-folded; it should fold to "return 55;"
}
