package wdlTools.generators

import org.fusesource.scalate.TemplateEngine
//import de.zalando.beard.filter.{DefaultFilterResolver, Filter}
//import de.zalando.beard.renderer.{
//  BeardTemplateRenderer,
//  ClasspathTemplateLoader,
//  CustomizableTemplateCompiler,
//  StringWriterRenderResult,
//  TemplateName
//}

case class Renderer(engine: TemplateEngine = new TemplateEngine) {
  def render(templateName: String, attrs: Map[String, Any] = Map.empty): String = {
    engine.layout(templateName, attrs)
  }
}

// TODO: switch to using Beard - the only problem is that the official
//  binary is compiled with JDK 14 - we either need the maintainer to
//  provide a JDK 8 binary or we need to vender the code and compile it
//  as part of wdlTools.
///**
//  * Filter that adds an indent to each line of a terminator-delimited string.
//  */
//class IndentFilter extends Filter {
//  override def name: String = "indent"
//
//  override def apply(value: String, parameters: Map[String, Any]): String = {
//    val length = parameters.get("length") match {
//      case Some(n: Int)    => n
//      case Some(s: String) => s.toInt
//      case None            => 0
//      case other           => throw new Exception(s"invalid indent length ${other}")
//    }
//    val indent = " " * length
//    value.linesIterator.map(line => s"${indent}${line}").toArray.mkString("\n")
//  }
//}
//
//case class BeardRenderer(templatePrefix: String = "/templates",
//                         templateSuffix: String = ".beard",
//                         userFilters: Seq[Filter] = Seq.empty) {
//  private val defaultFilters = Seq(
//      new IndentFilter()
//  )
//  private val loader = new ClasspathTemplateLoader(
//      templatePrefix = templatePrefix,
//      templateSuffix = templateSuffix
//  )
//  private val templateCompiler = new CustomizableTemplateCompiler(templateLoader = loader)
//  private val filters = defaultFilters ++ userFilters
//  private val filterResolver = DefaultFilterResolver(filters)
//  private val renderer =
//    new BeardTemplateRenderer(templateCompiler, filterResolver = filterResolver)
//
//  def render(templateName: String, context: Map[String, Any] = Map.empty): String = {
//    val template = templateCompiler.compile(TemplateName(templateName)).get
//    renderer.render(template, StringWriterRenderResult(), context).toString
//  }
//}
