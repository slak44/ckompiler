int thing(int a, int b) {
  return a + b;
}

int main() {
  int x = 0, y = 0;
  int i = 0;
  if (i == 0) {
    x = thing(1, -2);
    if (i == 0) {
      y = thing(0, 3);
    }
  }
  return x + y;
}
