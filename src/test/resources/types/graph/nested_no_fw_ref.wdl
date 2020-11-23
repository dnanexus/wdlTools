version 1.0

workflow nested {
  input {
    String name
    Int i
  }

  Array[Int] r = range(i)
  String greeting = "hello"


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

  Array[String] strings = s
  Array[Int?] jarray = j
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
