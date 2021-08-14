int main() {
  int rax = 1, rbx = 1, rcx = 1, rdx = 1, rsi = 1, rdi = 1,
  r8 = 1, r9 = 1, r10 = 1, r11 = 1, r12 = 1, r13 = 1, r14 = 1;

  // r15 used for if condition
  // Register pressure at max, nothing spilled yet
  if (0) {
    // Nothing here, use rax
    rax - rbx;
  } else {
    // Fill registers again
    int spilled = 2;
    // This constrained instruction triggers the transfer graph through its live range split
    // All registers are full here, there will also be an extra copy inserted for rax, because it's live-out here
    // And since certain registers are constrained, it will make the φ shuffle registers
    rbx += rax / spilled;
  }

  int sum = rax + rbx + rcx + rdx + rsi + rdi + r8 + r9 + r10 + r11 + r12 + r13 + r14;
  return sum;
}
