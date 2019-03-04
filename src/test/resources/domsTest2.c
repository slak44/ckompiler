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
    if (x < 2) {
      label2: {
        x += 2;
        if (x > 222) goto label1;
        else goto label3;
      }
    } else {
      label3: {
        x += 3;
        goto label2;
      }
    }
  }
}
