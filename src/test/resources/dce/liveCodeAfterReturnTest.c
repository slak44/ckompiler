int main() {
  int x = 1;
  if (x == 0) goto label;
  return x;
  label:
  return x + 1;
}
