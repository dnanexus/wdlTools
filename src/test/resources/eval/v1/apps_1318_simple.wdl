version 1.0
workflow apps_1318_simple {
  input {
    Array[Int] foo = [1, 2, 3]
  }

  # Artifically construct an array of optional integers
  if (true) {
    String i1 = "Hello"
  }
  if (false) {
    String i2 = "Cruel"
  }
  if (true) {
    String i3 = "World!"
  }

  Array[String?] lol = [i1, i2, i3]

  scatter (nested_pair in zip(foo, lol)) {
    call apps_1318_task1 {
      input: foo_t1 = nested_pair.left, lol_t1 = nested_pair.right
    }
  }

  output {
    Array[Int] blah = apps_1318_task1.out
  }
}

task apps_1318_task1 {
  input {
    Int foo_t1
    String? lol_t1
  }
  command <<<
    echo ~{foo_t1}~{lol_t1}"_blah"
  >>>
  output {
    Int out = 2
  }
}