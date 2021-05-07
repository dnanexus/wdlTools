version 1.1

task t1 {
    input {
        Boolean bool_flag = true
        String if_true = "x"
        String if_false = "y"
    }

    command <<<
    echo ~{true="~{if_true}" false="~{if_false}" bool_flag}
    >>>

    output {
        String out = read_lines(stdout())[0]
    }
}

workflow w1 {
    call t1

    output {
        String out = t1.out
    }
}
