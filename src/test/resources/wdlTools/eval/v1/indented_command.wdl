version 1.0

task foo {
  String s = "Steve"

  command <<<
    echo 'hello ~{default="Bill" s}'
   echo 'how are you, ~{default="Bill" s}?'
      echo 'goodbye ~{default="Bill" s}'
  >>>
}
