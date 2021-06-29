version 1.0

workflow array_coersion {
  input {
    Array[String]? sample_names
    Array[File] fastqs
  }

  # https://github.com/ENCODE-DCC/wgbs-pipeline.git line 73
  # we allow coercing Array[P] -> Array[String] where P is primitive
  Array[String] names = select_first([sample_names, range(length(select_first([fastqs])))])
}