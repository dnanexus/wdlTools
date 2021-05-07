version 1.0

workflow bwa_mem_wf {
  input {
    String sample_name
    File fastq1_gz
    File fastq2_gz
    File genome_index_tgz
    String? read_group
  }

  call bwa_mem {
    input:
      sample_name = sample_name,
      fastq1_gz = fastq1_gz,
      fastq2_gz = fastq2_gz,
      genome_index_tgz = genome_index_tgz,
      read_group = read_group,
      min_seed_length = 19
  }

  output {
    File sam = bwa_mem.sam
  }
}

task bwa_mem {
  input {
    String sample_name
    File fastq1_gz
    File fastq2_gz
    File genome_index_tgz
    String? read_group
    String docker_image = "broadinstitute/genomes-in-the-cloud:2.3.1-1512499786"
    Int min_seed_length
  }

  String genome_index_basename = basename(genome_index_tgz, ".tar.gz")
  String actual_read_group = select_first([
    read_group,
    "@RG\\tID:${sample_name}\\tSM:${sample_name}\\tLB:${sample_name}\\tPL:ILLUMINA"
  ])

  command <<<
    set -uexo pipefail
    tar xzvf ~{genome_index_tgz}
    /usr/gitc/bwa mem \
    -M \
    -t 4 \
    -R "~{actual_read_group}" \
    -k ~{min_seed_length} \
    ~{genome_index_basename}.fa \
    ~{fastq1_gz} ~{fastq2_gz} > ~{sample_name}.sam
  >>>

  output {
    File sam = "${sample_name}.sam"
  }

  runtime {
    docker: docker_image
  }
}
