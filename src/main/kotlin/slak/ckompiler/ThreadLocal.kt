package slak.ckompiler

expect class ThreadLocal<T> {
  fun get(): T
}

expect fun <T> threadLocalWithInitial(supplier: () -> T): ThreadLocal<T>
