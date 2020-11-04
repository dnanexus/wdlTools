version development

task multiline_string {
  input {
    String a = """This is a
                  multiline string"""
  }

  String b = '''This is a
               |multiline string with a margin'''

  String c = '''This is a
               >multiline string with an indent'''

  String d = sub(b, "\n", " ")
  String e = sub(c, "\n", " ")
  String f = sub(c, "[:space:]+", " ")

  command {}

  output {
    String eq1 = if a == b then "equal" else "unequal"
    String eq2 = if a == c then "equal" else "unequal"
    String eq3 = if b == c then "equal" else "unequal"
    String eq4 = if d == e then "equal" else "unequal"
    String eq5 = if d == f then "equal" else "unequal"
    String eq6 = if e == f then "equal" else "unequal"
  }

  meta {
    a: """This is a
          multiline string"""
    b: '''This is a
         |multiline string with a margin'''
    c: '''This is a
         >multiline string with an indent'''
  }
}