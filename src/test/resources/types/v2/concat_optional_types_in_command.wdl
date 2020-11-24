version development

task cutadapt {
  input {
    File fastq1
    File? fastq2
    File? nextseq_adapters
  }

  Boolean paired = defined(fastq2)

  # ~{ if paired then "-a 'file:${nextseq_adapters}' -A 'file:${nextseq_adapters}'" else "-a 'file:${nextseq_adapters}'"} \
  command <<<
    cutadapt \
      ~{ if paired then "-a 'file:" + nextseq_adapters + "' -A 'file:" + nextseq_adapters + "'" else "-a 'file:" + nextseq_adapters + "'"}
      ~{fastq1} ~{fastq2}
  >>>
}