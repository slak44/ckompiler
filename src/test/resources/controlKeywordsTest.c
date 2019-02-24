int main() {
  int a = 2;
  while (1) {
    a += 23;
    if (a > 345) break;
    if (a % 2 == 0) continue;
    a += 78;
  }
  return a;
}
