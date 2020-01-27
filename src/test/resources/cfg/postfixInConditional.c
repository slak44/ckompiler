int main() {
  int a = 5;
  if (6 > a++) {
    a = 1;
  } else {
    a = 2;
  }
  return a;
}
