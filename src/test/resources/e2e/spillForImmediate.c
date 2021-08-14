int main() {
  int rax = 1, rbx = 1, rcx = 1, rdx = 1, rsi = 1, rdi = 1,
  r8 = 1, r9 = 1, r10 = 1, r11 = 1, r12 = 1, r13 = 1, r14 = 1;

  // r15 used for if condition
  // Register pressure at max, nothing spilled yet
  if (1) {
    // Nothing here, use rax
    rax - rbx;
  } else {
    // There has to be a register for the immediate 4
    // As the register file is full, the compiler must spill here
    rax / 4;
  }

  int sum = rax + rbx + rcx + rdx + rsi + rdi + r8 + r9 + r10 + r11 + r12 + r13 + r14;
  return sum;
}
