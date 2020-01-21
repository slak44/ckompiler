                 // live ranges:
int main() {     // | a | b | c |
                 // -------------
  int a = 1;     // | x |   |   |
  int b = 2;     // | x | x |   |
  int c = a + b; // | x | x | x | requires 3 registers
  c += a;        // | x | x | x |
  c += b;        // |   | x | x |
  return c;      // |   |   | x |
}
