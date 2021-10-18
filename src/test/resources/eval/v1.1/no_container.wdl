version 1.1

task countTo {
    input{
        Int value
    }
    command {
        seq 0 1 ${value}
    }
    runtime {
    }
    output {
        File range = stdout()
    }
}

task filterEvens {
    input{
        File numbers
    }
    command {
        grep '[02468]$' ${numbers} > evens
        grep '[13579]$' ${numbers} > odds
    }
    runtime {
    }
    output {
     Map[String,File] counter_output = {"odds":"odds","evens":"evens"}
    }
}

workflow countEvens {
    input{
        Int max
        String sample
    }
    call countTo { input: value = max }
    call filterEvens { input: numbers = countTo.range }
    output {
        Pair[String,Map[String,File]] counter_wf_output = (sample,filterEvens.counter_output)
    }
}