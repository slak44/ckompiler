int main() {
  int y = 0;
  int x = 1;
  if (y > 0) {
    x += 2;
  }
  y = x;
  if (x > 0) {
    x += 3;
  }
  return x + y;
}
