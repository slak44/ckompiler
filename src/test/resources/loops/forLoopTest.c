int main() {
  int x = 0;
  for (int i = 23; i < 66; i++) {
    x += 2;
  }
  return x;
  // This code can be completely constant-folded; it should fold to "return 86;"
}
