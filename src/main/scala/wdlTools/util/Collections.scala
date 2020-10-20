package wdlTools.util

object Collections {
  implicit class IterableExtensions[A](iterable: Iterable[A]) {
    def asOption: Option[IterableOnce[A]] = {
      if (iterable.nonEmpty) {
        Some(iterable)
      } else {
        None
      }
    }
  }

  implicit class IterableOnceExtensions[A](iterableOnce: IterableOnce[A]) {
    def foldLeftWhile[B](init: B)(where: B => Boolean)(op: (B, A) => B): B = {
      iterableOnce.iterator.foldLeft(init)((acc, next) => if (where(acc)) op(acc, next) else acc)
    }
  }
}
