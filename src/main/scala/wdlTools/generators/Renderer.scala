package wdlTools.generators

import org.fusesource.scalate.TemplateEngine

case class Renderer(engine: TemplateEngine = new TemplateEngine) {
  def render(templateName: String, attrs: Map[String, Any]): String = {
    engine.layout(templateName, attrs)
  }
}
