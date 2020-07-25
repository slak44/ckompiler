int f(int p1, int p2) {
  // Use enough registers to overwrite rcx
  int x1 = 11, x2 = 12, x3 = 13, x4 = 14;
  return p1 + p2 + x1 + x2 + x3 + x4;
}

int main() {
  // Should put c in rcx, which is caller-saved
  int a = 5, b = 3, c = 7;
  f(a, b);
  return c;
}
