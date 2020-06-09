# Automatic coercions considered harmful

I spent four years working on WDL applications and tools, including a type checker, evaluator, and cloud executor. This page describes a line of thought regarding type checking for WDL programs.

# Coercions

WDL makes wide ranging use of coercions to convert one type to another. This is, in general, a well accepted idea used in a wide variety of programming languages. At DNAnexus we have run into several instances where WDL workflows failed because static type checking was too lenient. This resulted in failures in long running expensive customer pipelines.

For example, under the WDL type system, a `File` is auto-coercible into `File?`. However, this is not the case in the DNAnexus simpler type-system. If you have a task that takes an `Array[File?]` and call it with `Array[File]`  this will fail. For example:

```wdl
version 1.0

task foo {
  input {
    Array[File?] bams
  }
  command {}
}


workflow bar {
  input {
    Array[File] my_files
  }

  call long_expensive_callculation {}

  call foo { input : bams = my_files }

  output {}
}
```

Workflow `bar` will fail when it calls `foo`, after it has spent a long time running an expensive calculation. This is frustrating for the users. We could prevent this, as well as many other errors, by performing stricter type checking. For motivation, please examine the list below, it was compiling when typechecking GATK pipelines on the web.

Converting between `T` and `T?` occurs automatically
```
  # convert an optional file-array to a file-array
  Array[File] files = ["a", "b"]
  Array[File]? files2 = files

  # Convert an optional int to an int
  # This will fail at runtime if a is null
  Int a = 3
  Int? b = a

  # An array with a mixture of Int and Int?
  Int c = select_first([1, b])
```

Converting between strings, integer, and floats "just works".
```
  # Convert an integer to a string
  String s = 3

  # https://github.com/gatk-workflows/gatk4-germline-snps-indels/blob/master/haplotypecaller-gvcf-gatk4.wdl#L211
  Int z = 1.3

  # https://github.com/gatk-workflows/gatk4-germline-snps-indels/blob/master/JointGenotyping-terra.wdl#L475
  # converting Array[String] to Array[Int]
  Int d = "3"
  Array[Int] numbers = ["1", "2"]
```

The branches of a conditional may not resolve to the same type. Here, one side is `Array[String]`, the other is `Array[String?]`.
```
  # https://github.com/gatk-workflows/gatk4-germline-snps-indels/blob/master/JointGenotyping-terra.wdl#L556
  #
  Array[String] birds = ["finch", "hawk"]
  Array[String?] mammals = []
  Array[String?] zoo = if (true) then birds else mammals
  ```

Since `T` and `T?` are interchangable, we can perform operations like `T` + `T?` + `T`.
```
  # https://github.com/gatk-workflows/broad-prod-wgs-germline-snps-indels/blob/master/JointGenotypingWf.wdl#L570
  #
  # Adding a String and String?
  String? here = "here"
  String buf = "not " + "invented " + here

  # https://github.com/gatk-workflows/broad-prod-wgs-germline-snps-indels/blob/master/PairedEndSingleSampleWf.wdl#L1207
  #
  # convert an optional int to string.
  Int? max_output = 3
  String s1 = "MAX_OUTPUT=" + max_output
  ```

The following non-intuitive code type checks in WDL (womtool-51.jar) :

```
Array[String] strings = [1, 1.2, "boo"]
Array[Int] numbers = [1, 1.2, "3.0"]
```

And then `aai` type checks too:
```
Array[Array[Int]] aai = [[1, 2], ["foo"], [1.4, 4.9, 9.3]]
```

# Proposal

While explicit coercions make sense, such, for example:
```
File? x = y
Array[File] af = ["a.txt", "b.txt"]
```

I feel that auto-coercions are harmful. I suggest disallowing automatic coercions, allow explicit coercions, and adding a few necessary stdlib function for type conversion. The new functions are:

Some(X) : X -> X?
toInt()
toString()
toFile()
