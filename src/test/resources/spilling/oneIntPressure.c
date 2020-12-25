int main() {
  int rax = 1, rbx = 1, rcx = 1, rdx = 1, rsi = 1, rdi = 1,
  r8 = 1, r9 = 1, r10 = 1, r11 = 1, r12 = 1, r13 = 1, r14 = 1, r15 = 1;
  int spilled = 2;
  int sum = rax + rbx + rcx + rdx + rsi + rdi + r8 + r9 + r10 + r11 + r12 + r13 + r14 + r15 + spilled;
  return sum;
}
