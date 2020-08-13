int main() {
  int x = 22;
  int y = 44;
  int r = 0;
  if (x == 23) {
    r = 2;
  }
  return x + y + r;
  // This code can be completely constant-folded; it should fold to "return 66;"
}
