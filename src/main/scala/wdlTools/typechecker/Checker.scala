package wdlTools.typechecker

import wdlTools.util.Util.Conf
import wdlTools.syntax.AbstractSyntax

case class Checker(conf : Conf) {
  // check if the WDL document is correct in terms of typing.
  def apply(doc : AbstractSyntax.Document) : Boolean = ???
}
