package slak.ckompiler

actual typealias ThreadLocal<T> = java.lang.ThreadLocal<T>

actual fun <T> threadLocalWithInitial(supplier: () -> T): ThreadLocal<T> {
  return java.lang.ThreadLocal.withInitial(supplier)
}
