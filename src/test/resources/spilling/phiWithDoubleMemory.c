int main() {
  int rax = 1, rbx = 1, rcx = 1, rdx = 1, rsi = 1, rdi = 1,
  r8 = 1, r9 = 1, r10 = 1, r11 = 1, r12 = 1, r13 = 1, r14 = 1;

  int spilled;

  // r15 used for if condition
  // Register pressure at max, nothing spilled yet
  if (1) {
    spilled = 10;
    // r13/r14 is spilled here, and this causes a redefinition here
    // So a φ is needed in the block below for them, each has a vreg and this memory version as its operands
    rax / r12;
  } else {
    spilled = 20;
    // r13/r14 is spilled here, and this causes another redefinition here
    // Now the φ will should have both versions be memory
    rax / r12;
  }

  int sum = rax + rbx + rcx + rdx + rsi + rdi + r8 + r9 + r10 + r11 + r12 + r13 + r14 + spilled;
  return sum;
}
