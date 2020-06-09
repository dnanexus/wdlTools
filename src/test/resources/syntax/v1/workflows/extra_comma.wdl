version 1.0

workflow foo {
  Array[Int] a = [1, 2, 3, ]
  Map[String, Int] m = {
    "hello": 1,
  }
  Object obj = object {
    foo: 2,
  }

  call baz {
    input: s = "hi",
  }

  meta {
    x: "'"
    y: '"'
    foo: {
      bar: 1,
    }
    baz: [1, 2, 3, ]
    blorf: []
  }
}

task baz {
  input {
    String s
  }

  command {}
}
