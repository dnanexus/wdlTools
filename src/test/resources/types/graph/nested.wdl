version 1.0

workflow nested {
  input {
    String name
    Int i
  }

  Array[String] strings = s
  Array[Int?] jarray = j

  scatter (x in r) {
    String s = "${greeting} ${name} ${x}"
    Boolean b = x > 1

    if (b) {
      Int j = x + 1

      call foo as bar {
        input: j = j
      }
    }
  }

  String greeting = "hello"
  Array[Int] r = range(i)
  Array[Int] jdef = select_all(jarray)

  output {
    String out = "${sep=' ' strings}"
  }
}

task foo {
  input {
    Int j
  }
  command {}
  output {
    Int out = j + 1
  }
}
