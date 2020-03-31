package wdlTools.generator

trait Generator {
  def generate(templateName: String, attrs: Map[String, Any]): String
}
