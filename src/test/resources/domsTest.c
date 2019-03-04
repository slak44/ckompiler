int main() {
  int x = 1;
  if (x < 2) {
    x = 5;
    label1: {
      x += 1;
      goto label2;
    }
  } else {
    x = 7;
    label2: {
      x += 2;
      goto label1;
    }
  }
}
