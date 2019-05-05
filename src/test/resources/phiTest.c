// http://ssabook.gforge.inria.fr/latest/book.pdf
// This code should produce the graph from figure 3.1/3.2
int main() {
  int x, y, tmp;
  A:
  // x = φ(x, x)
  if (x < 0) {
    y = 0;
    x = 0;
  } else {
    tmp = x;
    x = y;
    y = tmp;
    if (x == 42) goto exit;
  }
  // x = φ(x, x)
  x = x + y;
  if (x == 99) goto A;
  exit:
  // x = φ(x, x)
  return x;
}
