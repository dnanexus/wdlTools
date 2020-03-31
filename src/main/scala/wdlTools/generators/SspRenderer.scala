package wdlTools.generators

import org.fusesource.scalate.TemplateEngine

case class SspRenderer(engine: TemplateEngine = new TemplateEngine) extends Renderer {
  override def render(templateName: String, attrs: Map[String, Any]): String = {
    engine.layout(templateName, attrs)
  }
}
