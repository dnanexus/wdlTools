version 1.0

task foo {
  String s = Array[String] numbers = ["1", "10", "100"]

  command <<<
    We have lots of numbers here ~{sep=", " numbers}
  >>>
}
