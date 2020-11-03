version development

struct Name {
  String first
  String last
}

task struct_literal {
  input {
    String first
    String last
  }

  command {}

  output {
    Name name = Name { first: first, last: last }
  }

  runtime {
    container: "ubuntu"
  }
}
