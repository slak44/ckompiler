int f() {
  // Should not overwrite rbx here
  int rax = 1, rbx = 2, rcx = 3, rdx = 4;
  return rax + rbx + rcx + rdx;
}

int main() {
  // Use rbx in caller
  int rax = 13, rbx = 17;
  return f() + rax + rbx;
}
