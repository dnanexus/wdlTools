package wdlTools.generator

import org.fusesource.scalate.TemplateEngine

case class SspGenerator(engine: TemplateEngine = new TemplateEngine) extends Generator {
  override def generate(templateName: String, attrs: Map[String, Any]): String = {
    engine.layout(templateName, attrs)
  }
}
