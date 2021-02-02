int thing(int a, int b) {
  return a + b;
}

int main() {
  int x0 = 0, x1 = 0, x2 = 0, x3 = 0, x4 = 0, x5 = 0, x6 = 0, x7 = 0, x8 = 0, x9 = 0;
  int i = 0;
  if (i == 0) {
    x0 = thing(1, -2);
    if (i == 0) {
      x1 = thing(2, -3);
      if (i == 0) {
        x2 = thing(3, -4);
        if (i == 0) {
          x3 = thing(4, -5);
          if (i == 0) {
            x4 = thing(5, -6);
            if (i == 0) {
              x5 = thing(6, -7);
              if (i == 0) {
                x6 = thing(7, -8);
                if (i == 0) {
                  x7 = thing(8, -9);
                  if (i == 0) {
                    x8 = thing(9, -10);
                    if (i == 0) {
                      x9 = thing(10, 0);
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
  return x0 + x1 + x2 + x3 + x4 + x5 + x6 + x7 + x8 + x9;
}
