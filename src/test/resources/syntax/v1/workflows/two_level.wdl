version 1.0

workflow two_levels {
  input {
  }

  scatter (i in [1,2,3]) {
    call zinc as incA { input: a = i + 5 }
    call zinc as incB { input: a = i+ 5 }
    call zinc as incC { input: a = i +5 }
    call zinc as incD { input: a = +5 }
    call zinc as incE { input: a = -5 }
  }

  output {
    Array[Int] a4 = incB.result
  }
}

task zinc {
  input {
    Int a
  }

  command {}

  output {
    Int result = a+ 1
  }
}
