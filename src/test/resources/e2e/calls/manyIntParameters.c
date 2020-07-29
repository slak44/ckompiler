#include <stdio.h>
void many(int p1, int p2, int p3, int p4, int p5,
          int p6, int p7, int p8, int p9, int p10, int p11) {
  printf("%d ", p1);
  printf("%d ", p2);
  printf("%d ", p3);
  printf("%d ", p4);
  printf("%d ", p5);
  printf("%d ", p6);
  printf("%d ", p7);
  printf("%d ", p8);
  printf("%d ", p9);
  printf("%d ", p10);
  printf("%d", p11);
}

int main() {
  int p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11;
  scanf("%d", &p1);
  scanf("%d", &p2);
  scanf("%d", &p3);
  scanf("%d", &p4);
  scanf("%d", &p5);
  scanf("%d", &p6);
  scanf("%d", &p7);
  scanf("%d", &p8);
  scanf("%d", &p9);
  scanf("%d", &p10);
  scanf("%d", &p11);
  many(p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11);
  return 0;
}
