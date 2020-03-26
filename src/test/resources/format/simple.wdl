version 1.0

task foo {
    input {
        String s
    }

    String x = "${s}.txt"

    command <<<
    echo ~{x}
    >>>

    output {
        String sout = x
    }

    runtime {
        docker: "debian:stretch-slim"
    }
}