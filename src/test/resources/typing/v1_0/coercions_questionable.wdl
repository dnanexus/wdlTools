version 1.0

workflow foo {
  # convert an optional file-array to a file-array
  Array[File] files = ["a", "b"]
  Array[File]? files2 = files

  # Convert an optional int to an int
  # This will fail at runtime if a is null
  Int a = 3
  Int? b = a

  # An array with a mixture of Int and Int?
  Int c = select_first([1, b])

  # Convert an integer to a string
  String s = 3

  # https://github.com/gatk-workflows/gatk4-germline-snps-indels/blob/master/JointGenotyping-terra.wdl#L475
  # converting Array[String] to Array[Int]
  Int d = "3"
  Array[Int] numbers = ["1", "2"]

  # https://github.com/gatk-workflows/gatk4-germline-snps-indels/blob/master/JointGenotyping-terra.wdl#L556
  #
  # Branches of a conditional may require unification
  Array[String] birds = ["finch", "hawk"]
  Array[String?] mammals = []
  Array[String?] zoo = if (true) then birds else mammals

  # https://github.com/gatk-workflows/gatk4-germline-snps-indels/blob/master/haplotypecaller-gvcf-gatk4.wdl#L211
  Int z = 1.3
}
