package slak.ckompiler

actual class ThreadLocal<T> {
  var value: T? = null

  actual fun get(): T {
    return value!!
  }

  actual fun set(value: T) {
    this.value = value
  }
}

actual fun <T> threadLocalWithInitial(supplier: () -> T): ThreadLocal<T> {
  val threadLocal = ThreadLocal<T>()
  threadLocal.value = supplier()
  return threadLocal
}
