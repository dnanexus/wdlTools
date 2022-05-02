version 1.1

workflow override {

    # Default
    call default {}

}

task default {
    input {
        String? mock_input = "asdl"
    }

    command <<< >>>

    output {
        String mock_out = ""
    }

    runtime {
        dx_app: object {
                    type: "app",
                    id: "app-G6G0jX80g1FZX1Z57z3zbG6v",
                    name: "mock_app_sciprodbuild/0.0.6"
                }
    }
}