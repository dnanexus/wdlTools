package wdlTools.types

import java.nio.file.Paths

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import wdlTools.syntax.Parsers
import dx.util.{FileSourceResolver, Logger}

class GatkTest extends AnyWordSpec with Matchers {
  private val fileResolver = FileSourceResolver.create(
      Vector(Paths.get(getClass.getResource("/types/v1").getPath))
  )
  private val logger = Logger.Normal
  private val parser = Parsers(followImports = true, fileResolver = fileResolver, logger = logger)
  private val checker =
    TypeInfer(regime = TypeCheckingRegime.Lenient, fileResolver = fileResolver, logger = logger)
  private val sources = Vector(
      // broad removed the terra version - the main version doesn't work because it's import statement uses
      // a relative local path path instead of a URL
      //"https://raw.githubusercontent.com/gatk-workflows/gatk4-germline-snps-indels/master/JointGenotyping.wdl",
      "https://raw.githubusercontent.com/gatk-workflows/gatk4-germline-snps-indels/master/haplotypecaller-gvcf-gatk4.wdl",
      // Uses the keyword "version "
      //"https://raw.githubusercontent.com/gatk-workflows/gatk4-data-processing/master/processing-for-variant-discovery-gatk4.wdl"
      "https://raw.githubusercontent.com/gatk-workflows/broad-prod-wgs-germline-snps-indels/master/JointGenotypingWf.wdl"
      // Non standard usage of place holders
      //"https://github.com/gatk-workflows/broad-prod-wgs-germline-snps-indels/blob/master/PairedEndSingleSampleWf.wdl"
      //
      // https://github.com/gatk-workflows/broad-prod-wgs-germline-snps-indels/blob/master/PairedEndSingleSampleWf.wdl#L1208
      //  Array[String]? ignore
      //  String s2 = {default="null" sep=" IGNORE=" ignore}
      //
      //  # syntax error in place-holder
      //  # https://github.com/gatk-workflows/broad-prod-wgs-germline-snps-indels/blob/master/PairedEndSingleSampleWf.wdl#L1210
      //  Boolean? is_outlier_data
      //  String s3 = ${default='SKIP_MATE_VALIDATION=false' true='SKIP_MATE_VALIDATION=true' false='SKIP_MATE_VALIDATION=false' is_outlier_data}
      //
  )

  "GATK test" should {
    sources.foreach { uri =>
      s"type check WDL at ${uri}" in {
        val docSource = fileResolver.resolve(uri)
        val doc = parser.parseDocument(docSource)
        checker.apply(doc)
      }
    }
  }
}
