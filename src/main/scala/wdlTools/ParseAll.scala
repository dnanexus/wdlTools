package wdlTools

//import ConcreteSyntax.{Document, URL, ImportDoc, ImportDocElaborated}

import java.nio.file.Path
import scala.collection.mutable.Map

// parse and follow imports
case class ParseAll(antlr4Trace: Boolean = false,
                    verbose: Boolean = false,
                    quiet: Boolean = false,
                    localDirectories: Vector[Path] = Vector.empty) {

  // cache of documents that have already been fetched and parsed.
  private val docCache: Map[URL, AbstractSyntax.Document] = Map.empty

  private def followImport(url: URL): AbstractSyntax.Document = {
    docCache.get(url) match {
      case None =>
        val docText = FetchURL(verbose, localDirectories).apply(url)
        val cDoc: ConcreteSyntax.Document =
          ParseDocument.apply(docText, verbose, quiet, antlr4Trace)
        val aDoc = dfs(cDoc)
        docCache(url) = aDoc
        aDoc
      case Some(aDoc) =>
        aDoc
    }
  }

  private def translateType(t: ConcreteSyntax.Type): AbstractSyntax.Type = {
    t match {
      case ConcreteSyntax.TypeOptional(t) => AbstractSyntax.TypeOptional(translateType(t))
      case ConcreteSyntax.TypeArray(t, nonEmpty) =>
        AbstractSyntax.TypeArray(translateType(t), nonEmpty)
      case ConcreteSyntax.TypeMap(k, v) =>
        AbstractSyntax.TypeMap(translateType(k), translateType(v))
      case ConcreteSyntax.TypePair(l, r) =>
        AbstractSyntax.TypePair(translateType(l), translateType(r))
      case ConcreteSyntax.TypeString                => AbstractSyntax.TypeString
      case ConcreteSyntax.TypeFile                  => AbstractSyntax.TypeFile
      case ConcreteSyntax.TypeBoolean               => AbstractSyntax.TypeBoolean
      case ConcreteSyntax.TypeInt                   => AbstractSyntax.TypeInt
      case ConcreteSyntax.TypeFloat                 => AbstractSyntax.TypeFloat
      case ConcreteSyntax.TypeIdentifier(id)        => AbstractSyntax.TypeIdentifier(id)
      case ConcreteSyntax.TypeObject                => AbstractSyntax.TypeObject
      case ConcreteSyntax.TypeStruct(name, members) => AbstractSyntax.TypeStruct(name, members)
    }
  }

  private def translateExpr(e: ConcreteSyntax.Expr): AbstractSyntax.Expr = {
    e match {
      // values
      case ConcreteSyntax.ExprString(value)  => AbstractSyntax.ValueString(value)
      case ConcreteSyntax.ExprFile(value)    => AbstractSyntax.ValueFile(value)
      case ConcreteSyntax.ExprBoolean(value) => AbstractSyntax.ValueBoolean(value)
      case ConcreteSyntax.ExprInt(value)     => AbstractSyntax.ValueInt(value)
      case ConcreteSyntax.ExprFloat(value)   => AbstractSyntax.ValueFloat(value)

      // compound values
      case ConcreteSyntax.ExprIdentifier(id) => AbstractSyntax.ExprIdentifier(id)
      case ConcreteSyntax.ExprCompoundString(vec) =>
        AbstractSyntax.ExprCompoundString(vec.map(translateExpr).toVector)
      case ConcreteSyntax.ExprPair(l, r) => AbstractSyntax.ExprPair(l, r)
      case ConcreteSyntax.ExprArray(vec) =>
        AbstractSyntax.ExprArray(vec.map(translateExpr).toVector)
      case ConcreteSyntax.ExprMap(m) =>
        AbstractSyntax.ExprMap(m.map {
          case (k, v) => translateExpr(k) -> translateExpr(v)
        }.toMap)
      case ConcreteSyntax.ExprObject(m) =>
        AbstractSyntax.ExprObject(m.map {
          case (fieldName, v) => fieldName -> translateExpr(v)
        }.toMap)

      // string place holders
      case ConcreteSyntax.ExprPlaceholderEqual(t, f, value) =>
        AbstractSyntax.ExprPlaceholderEqual(translateExpr(t),
                                            translateExpr(f),
                                            translateExpr(value))
      case ConcreteSyntax.ExprPlaceholderDefault(default, value) =>
        AbstractSyntax.ExprPlaceholderDefault(translateExpr(default), translateExpr(value))
      case ConcreteSyntax.ExprPlaceholderSep(sep, value) =>
        AbstractSyntax.ExprPlaceholderSep(translateExpr(sep), translateExpr(value))

      // operators on one argument
      case ConcreteSyntax.ExprUniraryPlus(value) =>
        AbstractSyntax.ExprUniraryPlus(translateExpr(value))
      case ConcreteSyntax.ExprUniraryMinus(value) =>
        AbstractSyntax.ExprUniraryMinus(translateExpr(value))

      // operators on two arguments
      case ConcreteSyntax.ExprLor(a, b) =>
        AbstractSyntax.ExprLor(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprLand(a, b) =>
        AbstractSyntax.ExprLand(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprNegate(a, b) =>
        AbstractSyntax.ExprNegate(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprEqeq(a, b) =>
        AbstractSyntax.ExprEqeq(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprLt(a, b) => AbstractSyntax.ExprLt(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprGte(a, b) =>
        AbstractSyntax.ExprGte(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprNeq(a, b) =>
        AbstractSyntax.ExprNeq(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprLte(a, b) =>
        AbstractSyntax.ExprLte(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprGt(a, b) => AbstractSyntax.ExprGt(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprAdd(a, b) =>
        AbstractSyntax.ExprAdd(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprSub(a, b) =>
        AbstractSyntax.ExprSub(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprMod(a, b) =>
        AbstractSyntax.ExprMod(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprMul(a, b) =>
        AbstractSyntax.ExprMul(translateExpr(a), translateExpr(b))
      case ConcreteSyntax.ExprDivide(a, b) =>
        AbstractSyntax.ExprDivide(translateExpr(a), translateExpr(b))

      // Access an array element at [index]
      case ConcreteSyntax.ExprAt(array, index) =>
        AbstractSyntax.ExprAt(translateExpr(array), translateExpr(index))

      case ConcreteSyntax.ExprIfThenElse(cond, tBranch, fBranch) =>
        AbstractSyntax.ExprIfThenElse(translateExpr(cond),
                                      translateExpr(tBranch),
                                      translateExpr(fBranch))
      case ConcreteSyntax.ExprApply(funcName, elements) =>
        AbstractSyntax.ExprApply(funcName, elements.map(translateExpr).toVector)
      case ConcreteSyntax.ExprGetName(e: Expr, id: String) =>
        AbstractSyntax.ExprGetName(translateExpr(expr), id)

      case other =>
        throw new Exception(s"invalid concrete syntax element ${other}")
    }
  }

  // start from a document [doc], and recursively dive into all the imported
  // documents. Replace all the raw import statements with fully elaborated ones.
  private def dfs(doc: ConcreteSyntax.Document): AbstractSyntax.Document = {
    // scan for import statements and follow them
    val elems = doc.elements.map {
      case t: Type       => translateType(t)
      case e: Expression => translateExpression(e)

      case ImportDoc(name, aliases, url) =>
        val importedDoc = followImport(url)

        // Replace the original statement with a new one
        ImportDocElaborated(name, aliases, importedDoc)

    }.toVector
    Document(doc.version, elems, doc.workflow)
  }

  // [dirs] : the directories where to search for imported documents
  //
  def apply(sourceCode: String): AbstractSyntax.Document = {
    val topDoc: Document = ParseDocument.apply(sourceCode, verbose, quiet, antlr4Trace)
    dfs(topDoc)
  }
}
