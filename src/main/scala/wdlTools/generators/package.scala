package wdlTools.generators

trait Renderer {
  def generate(templateName: String, attrs: Map[String, Any]): String
}
