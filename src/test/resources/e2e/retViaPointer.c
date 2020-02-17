void f(int* result) {
  *result = 42;
}
int main() {
  int data;
  f(&data);
  return data;
}
