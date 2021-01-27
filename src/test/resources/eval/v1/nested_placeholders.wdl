version 1.0

workflow nested_placeholders {
  input {
    Array[String]? s
  }

  String result = "~{if defined(s) then "~{sep=" " select_first([s])}" else "null"}"
}