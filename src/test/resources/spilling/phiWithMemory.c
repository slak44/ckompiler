int main() {
  int rax = 1, rbx = 1, rcx = 1, rdx = 1, rsi = 1, rdi = 1,
  r8 = 1, r9 = 1, r10 = 1, r11 = 1, r12 = 1, r13 = 1, r14 = 1;
  int spilled;

  // r15 used for if condition
  // Register pressure at max, nothing spilled yet
  if (0) {
    // Nothing here, use rax
    rax - rbx;
  } else {
    spilled = 2;
    // spilled/r14 are spilled, and this causes a redefinition here
    // So a φ is needed in the block below for them, each has a vreg and this memory version as its operands
    rax / r12;
  }

  int sum = rax + rbx + rcx + rdx + rsi + rdi + r8 + r9 + r10 + r11 + r12 + r13 + r14 + spilled;
  return sum;
}
