version 1.0

workflow nested_optional {
  input {
    Boolean b
    File? f
  }

  Array[File?] opt_files = [f]

  # make sure `file` is not a nested optional
  if (b) {
    File? file = select_first(opt_files)
  }

  output {
    File? outfile = file
  }
}