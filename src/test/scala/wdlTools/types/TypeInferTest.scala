package wdlTools.types

import java.nio.file.Paths

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import wdlTools.Edge

import wdlTools.syntax.Parsers
import dx.util.{FileSourceResolver, Logger}

class TypeInferTest extends AnyFlatSpec with Matchers {
  private val logger = Logger.Normal
  //private val v1Dir = Paths.get(getClass.getResource("/types/v1").getPath)
  private val v1StructsDir =
    Paths.get(getClass.getResource("/types/v1/structs").getPath)
  private val v2StructsDir =
    Paths.get(getClass.getResource("/types/v2/structs").getPath)

  it should "handle several struct definitions" taggedAs Edge in {
    val structsFileResolver = FileSourceResolver.create(Vector(v1StructsDir))
    val checker = TypeInfer(fileResolver = structsFileResolver, logger = logger)
    val sourceFile = structsFileResolver.fromPath(v1StructsDir.resolve("file3.wdl"))
    val doc = Parsers(followImports = true, fileResolver = structsFileResolver, logger = logger)
      .parseDocument(sourceFile)
    checker.apply(doc)
  }

  it should "handle struct aliases" in {
    val structsFileResolver = FileSourceResolver.create(Vector(v2StructsDir))
    val checker = TypeInfer(fileResolver = structsFileResolver, logger = logger)
    val sourceFile = structsFileResolver.fromPath(v2StructsDir.resolve("parent_workflow.wdl"))
    val doc = Parsers(followImports = true, fileResolver = structsFileResolver, logger = logger)
      .parseDocument(sourceFile)
    checker.apply(doc)
  }
}
