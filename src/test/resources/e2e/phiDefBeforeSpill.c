int main() {
  int rax = 1, rbx = 1, rcx = 1, rdx = 1, rsi = 1, rdi = 1,
  r8 = 1, r9 = 1, r10 = 1, r11 = 1, r12 = 1, r13 = 1, r14 = 1;

  // This variable is the 15th φ in the BB after the if, which causes the SSA reconstruction code to spill the def
  // The spiller has to correctly deal with this "pre-spilled" value
  int over = 0;

  int spilled;

  // r15 used for if condition
  // Register pressure at max, nothing spilled yet
  if (1) {
    spilled = 10;
    rax / r12;
  } else {
    spilled = 20;
    rax / r12;
  }

  int sum = rax + rbx + rcx + rdx + rsi + rdi + r8 + r9 + r10 + r11 + r12 + r13 + r14 + spilled + over;
  return sum;
}
