import dx.util.StringFileNode
import wdlTools.generators.code.WdlGenerator
import wdlTools.syntax.{CommentMap, SourceLocation, WdlVersion}
import wdlTools.types.{
  ExprState,
  Section,
  Stdlib,
  TypeCheckingRegime,
  WdlTypes,
  TypedAbstractSyntax => TAT
}

import scala.collection.immutable.TreeSeqMap

object GenerateCode {

  /**
    * This example generates a WDL v1.0 file containing a single task.
    */
  def generateCode(): String = {
    // source locations are only necessary if you want to programmatically add
    // comments to the code
    val loc = SourceLocation.empty
    val stdlib = Stdlib(TypeCheckingRegime.Moderate, WdlVersion.V1, Vector.empty)
    val taskName = "test"
    val nameInput = TAT.RequiredInputParameter("name", WdlTypes.T_String)(loc)
    val ageInput = TAT.OptionalInputParameter("age", WdlTypes.T_Optional(WdlTypes.T_Int))(loc)
    val inputs = Vector(nameInput, ageInput)
    val (readStringType, readStringSig) =
      stdlib("read_string", Vector(WdlTypes.T_String), ExprState.Start, Section.Output)
    val (stdoutType, stdoutSig) = stdlib("stdout", Vector(), ExprState.Start, Section.Output)
    val outputs = Vector(
        TAT.OutputParameter(
            "greeting",
            WdlTypes.T_String,
            TAT.ExprCompoundString(
                Vector(
                    TAT.ValueString("hello ", WdlTypes.T_String)(loc),
                    TAT.ExprApply(
                        "read_string",
                        readStringSig,
                        Vector(TAT.ExprApply("stdout", stdoutSig, Vector.empty, stdoutType)(loc)),
                        readStringType
                    )(
                        loc
                    )
                ),
                WdlTypes.T_String
            )(
                loc
            )
        )(
            loc
        )
    )
    val task = TAT.Task(
        taskName,
        WdlTypes.T_Task(
            taskName,
            inputs
              .map {
                case TAT.RequiredInputParameter(name, wdlType) => name -> (wdlType, true)
                case param: TAT.InputParameter                 => param.name -> (param.wdlType, false)
              }
              .to(TreeSeqMap),
            outputs.map(out => out.name -> out.wdlType).to(TreeSeqMap),
            None
        ),
        inputs,
        outputs,
        TAT.CommandSection(
            Vector(
                TAT.ValueString("echo ", WdlTypes.T_String)(loc),
                TAT.ExprIdentifier("name", nameInput.wdlType)(loc),
                TAT.ExprIdentifier("age", ageInput.wdlType)(loc)
            )
        )(
            loc
        ),
        Vector.empty,
        None,
        None,
        None,
        None
    )(
        loc
    )
    val doc = TAT.Document(StringFileNode.empty,
                           TAT.Version(WdlVersion.V1)(loc),
                           Vector(task),
                           None,
                           CommentMap.empty)(loc)
    val codeGenerator = WdlGenerator(Some(WdlVersion.V1))
    val lines = codeGenerator.generateDocument(doc)
    lines.mkString("\n")
  }

  println(generateCode())
}
