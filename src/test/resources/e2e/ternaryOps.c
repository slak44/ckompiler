int main() {
  int a = 1 ? 11 : 0;
  int b = 1;
  if ((a ? 0 : 1) == 0) {
    b += 2;
  }
  int c = 3;
  for (int i = (b > 2 ? 0 : 4); i < 10; i++) {
    c++;
  }
  return c;
  // This code can be completely constant-folded; it should fold to "return 13;"
}
