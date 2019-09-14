#include <stdio.h>
int main() {
  int a;
  scanf("%d", &a);
  switch (a) {
    case 10: printf("10\n");
    case 20: {
      printf ("20\n");
      break;
    }
    default: {
      printf("default\n");
    }
  }
}
