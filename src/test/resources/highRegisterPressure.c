int main() {
  int spilled = 0;
  int rax = 0, rbx = 0, rcx = 0, rdx = 0, rsi = 0, rdi = 0;
  int r8 = 0, r9 = 0, r10 = 0, r11 = 0, r12 = 0, r13 = 0, r14 = 0, r15 = 0;
  int result = rax + rbx + rcx + rdx + rsi + rdi + r8 + r9 + r10 + r11 + r12 + r13 + r14 + r15;
  result += spilled;
  return result;
}
