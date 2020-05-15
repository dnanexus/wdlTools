version	 1.0  # use of tab

  task foo{  # indented top-level
  input {
  String i
  }


           # multiple blank lines
   command {  # three spaces for indent
   ${i}  # deprecated command syntax
  }
  # no output section
  # no runtime section
}