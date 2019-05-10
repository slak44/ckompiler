int main() {
  labelOne:;
  int x = 123;
  if (x > 23) goto labelTwo;
  else goto labelThree;
  int y = 321;
  labelTwo:
  y += x;
  labelThree:
  return x * y;
}
