version 1.0

workflow nested_optional {
  input {
    File? f
  }

  Array[File?] opt_files = [f]

  # make sure `file` is not a nested optional
  if (defined(f)) {
    File? file = select_first(opt_files)
  }

  output {
    File? outfile = file
  }
}