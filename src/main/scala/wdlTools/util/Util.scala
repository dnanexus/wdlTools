package wdlTools.util

import java.nio.file.{Path}

object Util {

  case class Conf(antlr4Trace: Boolean = false,
                  verbose: Boolean = false,
                  quiet: Boolean = false,
                  localDirectories: Vector[Path] = Vector.empty)
}
