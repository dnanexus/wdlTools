package wdlTools.generators

trait Renderer {
  def render(templateName: String, attrs: Map[String, Any]): String
}
