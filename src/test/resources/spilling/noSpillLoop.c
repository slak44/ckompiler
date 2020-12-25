int main() {
  int sum = 0;
  int prod = 1;
  for (int i = 0; i < 10; i++) {
    sum += i;
    prod *= i;
  }
  return prod - sum;
}
