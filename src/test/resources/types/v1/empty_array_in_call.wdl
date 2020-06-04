version 1.0

task EmptyArray {
    input {
        Array[Int] fooAr
    }
    command {
    }
    output {
        Array[Int] result=fooAr
    }
}

workflow foo {
    # Handling of empty arrays as input/output
    call EmptyArray { input: fooAr=[] }
}
