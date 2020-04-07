version 1.0

task simple {
  input {
    Int k
    Array[File] files
  }
  command {
    echo "hello ~{files} ~{k}"
  }
}
