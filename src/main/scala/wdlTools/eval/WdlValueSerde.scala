package wdlTools.eval

import java.io.{BufferedInputStream, FileInputStream, FileOutputStream}
import java.nio.charset.Charset
import java.nio.file.{Files, Path, Paths}

import spray.json._
import wdlTools.eval.WdlValues._
import wdlTools.types.WdlTypeSerde
import wdlTools.types.WdlTypes._
import dx.util.{Bindings, FileUtils}
import org.apache.commons.compress.archivers.tar.{
  TarArchiveEntry,
  TarArchiveInputStream,
  TarArchiveOutputStream
}

/**
  * An Archive is a TAR file that contains 1) a JSON file (called manifest.json)
  * with the serialized representation of a complex value, along with its serialized
  * type information, and 2) the files referenced by all of the V_File values nested
  * within the complex value.
  * TODO: add option to support compressing the TAR
  */
trait Archive {
  val path: Path
  val wdlType: T
  val wdlValue: V
  val localized: Boolean
}

object Archive {
  val ManifestFile: String = "manifest.json"
  val ManifestTypeKey: String = "type"
  val ManifestValueKey: String = "value"

  /**
    * Transforms the paths of File-typed values, which may be contained in
    * a (possibly nested) collection.
    * @param wdlValue the WdlValue to transform
    * @param wdlType the WdlType of the value
    * @param transformer function to transform one Path to another
    * @return (updatedValue, pathMap), where the updatedValue is identical
    *         to `wdlValue` except with all paths of file-typed values updated,
    *         and pathMap is a mapping from old to new paths
    */
  def transformPaths(wdlValue: V, wdlType: T, transformer: Path => Path): (V, Map[Path, Path]) = {
    def inner(innerValue: V, innerType: T): (V, Map[Path, Path]) = {
      (innerType, innerValue) match {
        case (T_File, V_File(s)) =>
          val oldPath = Paths.get(s)
          val newPath = transformer(oldPath)
          (V_File(newPath.toString), Map(oldPath -> newPath))
        case (T_File, V_String(s)) =>
          val oldPath = Paths.get(s)
          val newPath = transformer(oldPath)
          (V_File(newPath.toString), Map(oldPath -> newPath))
        case (T_Optional(t), V_Optional(value)) =>
          val (v, paths) = inner(value, t)
          (V_Optional(v), paths)
        case (T_Array(itemType, _), V_Array(value)) =>
          val (items, paths) = value.map(inner(_, itemType)).unzip
          (V_Array(items), paths.flatten.toMap)
        case (T_Map(keyType, valueType), V_Map(m)) =>
          val (members, paths) = m.map {
            case (k, v) =>
              val (key, keyPaths) = inner(k, keyType)
              val (value, valuePaths) = inner(v, valueType)
              (key -> value, keyPaths ++ valuePaths)
          }.unzip
          (V_Map(members.toMap), paths.flatten.toMap)
        case (T_Pair(leftType, rightType), V_Pair(l, r)) =>
          val (leftValue, leftPaths) = inner(l, leftType)
          val (rightValue, rightPaths) = inner(r, rightType)
          (V_Pair(leftValue, rightValue), leftPaths ++ rightPaths)
        case (T_Struct(name, memberTypes), _) =>
          def localizeStruct(m: Map[String, V]): (Map[String, V], Map[Path, Path]) = {
            val (members, paths) = m.map {
              case (k, v) =>
                val valueType = memberTypes.getOrElse(
                    k,
                    throw new RuntimeException(s"${k} is not a member of struct ${name}")
                )
                val (value, paths) = inner(v, valueType)
                (k -> value, paths)
            }.unzip
            (members.toMap, paths.flatten.toMap)
          }
          innerValue match {
            case V_Object(m) =>
              val (members, paths) = localizeStruct(m)
              (V_Object(members), paths)
            case V_Struct(name, m) =>
              val (members, paths) = localizeStruct(m)
              (V_Struct(name, members), paths)
            case _ =>
              throw new RuntimeException(s"invalid struct value ${innerValue}")
          }
        case (_, v) =>
          (v, Map.empty)
      }
    }
    inner(wdlValue, wdlType)
  }
}

case class PackedArchive(path: Path, encoding: Charset = FileUtils.DefaultEncoding)(
    typeAliases: Option[Map[String, T]] = None,
    packedTypeAndValue: Option[(T, V)] = None
) extends Archive {
  assert(typeAliases.isDefined || packedTypeAndValue.isDefined)
  if (!Files.exists(path)) {
    throw new Exception(s"${path} does not exist")
  } else if (Files.isDirectory(path)) {
    throw new Exception(s"${path} is not a file")
  }

  override val localized: Boolean = false
  private var isOpen = false

  private lazy val tarStream: TarArchiveInputStream =
    try {
      val inputStream = new FileInputStream(path.toFile)
      val tarStream = new TarArchiveInputStream(if (inputStream.markSupported()) {
        inputStream
      } else {
        new BufferedInputStream(inputStream)
      })
      isOpen = true
      tarStream
    } catch {
      case ex: Throwable =>
        throw new Exception(s"invalid WDL value archive ${path}", ex)
    }

  private object iterator extends Iterator[TarArchiveEntry] {
    private var currentEntry: TarArchiveEntry = _

    override def hasNext: Boolean = {
      currentEntry = tarStream.getNextTarEntry
      currentEntry != null
    }

    override def next(): TarArchiveEntry = currentEntry
  }

  private def readManifest: (T, V) = {
    if (isOpen) {
      throw new RuntimeException("manifest has already been read")
    }
    if (!iterator.hasNext) {
      throw new RuntimeException(s"invalid archive file: missing ${Archive.ManifestFile}")
    }
    val manifestEntry = iterator.next()
    if (manifestEntry.isFile && manifestEntry.getName == Archive.ManifestFile) {
      val contents = new String(tarStream.readAllBytes(), encoding)
      val fields = contents.parseJson.asJsObject.fields
      val wdlType = WdlTypeSerde.deserializeType(fields("type"), typeAliases.get)
      val wdlValue = WdlValueSerde.deserialize(fields("value"), wdlType)
      (wdlType, wdlValue)
    } else {
      throw new RuntimeException(
          s"invalid archive file: expected first entry to be ${Archive.ManifestFile}, not ${manifestEntry}"
      )
    }
  }

  override lazy val (wdlType: T, wdlValue: V) = {
    packedTypeAndValue.getOrElse(readManifest)
  }

  /**
    * Unpacks the files in the archive relative to the given parent dir, and updates
    * the paths within `wdlValue` and returns a new Archive object.
    * @param parentDir the directory in which to localize files
    * @return the updated Archive object and a Vector of localized paths
    */
  def localize(parentDir: Path, name: Option[String] = None): (LocalizedArchive, Vector[Path]) = {
    def transformer(relPath: Path): Path = {
      parentDir.resolve(relPath)
    }

    // make sure these lazy vals have been instantiated
    val (t, v) = (wdlType, wdlValue)
    val (localizedValue, filePaths) = Archive.transformPaths(v, t, transformer)
    val unpackedArchive = LocalizedArchive(t, localizedValue)(Some(path, v), Some(parentDir), name)
    (unpackedArchive, filePaths.values.toVector)
  }

  def close(): Unit = {
    if (isOpen) {
      tarStream.close()
    }
  }
}

/**
  * Represents an archive file that has been localized, i.e. the files referenced
  * in its `wdlValue` are localized on disk. It could be a previously packed
  * archive or a new archive that has not yet been packed.
  *
  * Either `packedPathAndValue` or `parentDir` must be specified.
  * @param wdlType WdlType
  * @param wdlValue WdlValue
  * @param encoding character encoding of contained files
  * @param packedPathAndValue optional path and delocalized value of a PackedArchive
  *                           from which this LocalizedArchive was created
  * @param parentDir optional Path to which files in the archive are relativeized
  *                  when packing.
  * @param name an optional name that will be used to prefix the randomly-generated
  *             archive name, if `originalPath` is `None`
  */
case class LocalizedArchive(
    wdlType: T,
    wdlValue: V,
    encoding: Charset = FileUtils.DefaultEncoding
)(packedPathAndValue: Option[(Path, V)] = None,
  parentDir: Option[Path] = None,
  name: Option[String] = None)
    extends Archive {
  assert(packedPathAndValue.isDefined || parentDir.isDefined)
  override val localized: Boolean = true

  override lazy val path: Path = {
    packedPathAndValue
      .map(_._1)
      .getOrElse(Files.createTempFile(name.getOrElse("archive"), ".tar"))
  }

  private def createArchive(path: Path, t: T, v: V, filePaths: Map[Path, Path]): Unit = {
    val manifest = JsObject(Archive.ManifestTypeKey -> WdlTypeSerde.serializeType(t),
                            Archive.ManifestValueKey -> WdlValueSerde.serialize(v))
    val manifestBytes = manifest.prettyPrint.getBytes(encoding)
    val tarStream = new TarArchiveOutputStream(new FileOutputStream(path.toFile))
    try {
      // write the manifest
      val manifestEntry = new TarArchiveEntry(Archive.ManifestFile)
      manifestEntry.setSize(manifestBytes.size)
      tarStream.putArchiveEntry(manifestEntry)
      tarStream.write(manifestBytes)
      tarStream.closeArchiveEntry()
      // write each file
      filePaths.foreach {
        case (absPath, relPath) =>
          val dirEntry = new TarArchiveEntry(absPath.toFile, relPath.toString)
          tarStream.putArchiveEntry(dirEntry)
          Files.copy(absPath, tarStream)
          tarStream.closeArchiveEntry()
      }
    } finally {
      tarStream.close()
    }
  }

  lazy val pack: PackedArchive = {
    val delocalizedValue = packedPathAndValue.map(_._2).getOrElse {
      def transformer(absPath: Path): Path = {
        parentDir.get.relativize(absPath)
      }
      val (delocalizedValue, filePaths) = Archive.transformPaths(wdlValue, wdlType, transformer)
      createArchive(path, wdlType, delocalizedValue, filePaths)
      delocalizedValue
    }
    PackedArchive(path, encoding)(packedTypeAndValue = Some(wdlType, delocalizedValue))
  }
}

// an error that occurs during (de)serialization of JSON
final class WdlValueSerializationException(message: String) extends Exception(message)

object WdlValueSerde {
  def serialize(value: V, handler: Option[V => Option[JsValue]] = None): JsValue = {
    def inner(innerValue: V): JsValue = {
      val v = handler.flatMap(_(innerValue))
      if (v.isDefined) {
        return v.get
      }
      innerValue match {
        case V_Null             => JsNull
        case V_Boolean(value)   => JsBoolean(value)
        case V_Int(value)       => JsNumber(value)
        case V_Float(value)     => JsNumber(value)
        case V_String(value)    => JsString(value)
        case V_File(value)      => JsString(value)
        case V_Directory(value) => JsString(value)
        case V_Archive(path)    => JsString(path)

        // compound values
        case V_Optional(v) =>
          inner(v)
        case V_Array(vec) =>
          JsArray(vec.map(inner))
        case V_Pair(l, r) =>
          JsObject(Map("left" -> inner(l), "right" -> inner(r)))
        case V_Map(members) =>
          JsObject(members.map {
            case (k, v) =>
              val key = inner(k) match {
                case JsString(value) => value
                case other =>
                  throw new WdlValueSerializationException(
                      s"Cannot serialize non-string map key ${other}"
                  )
              }
              key -> inner(v)
          })
        case V_Object(members) =>
          JsObject(members.map { case (k, v) => k -> inner(v) })
        case V_Struct(_, members) =>
          JsObject(members.map { case (k, v) => k -> inner(v) })

        case other => throw new WdlValueSerializationException(s"value ${other} not supported")
      }
    }
    inner(value)
  }

  def serializeMap(wdlValues: Map[String, V],
                   handler: Option[V => Option[JsValue]] = None): Map[String, JsValue] = {
    wdlValues.view.mapValues(v => serialize(v, handler)).toMap
  }

  def serializeBindings(bindings: Bindings[String, V],
                        handler: Option[V => Option[JsValue]] = None): Map[String, JsValue] = {
    serializeMap(bindings.toMap, handler)
  }

  def deserialize(jsValue: JsValue): V = {
    jsValue match {
      case JsNull                               => V_Null
      case JsBoolean(value)                     => V_Boolean(value)
      case JsNumber(value) if value.isValidLong => V_Int(value.toLongExact)
      case JsNumber(value)                      => V_Float(value.toDouble)
      case JsString(value)                      => V_String(value)
      // compound values
      case JsArray(vec) =>
        V_Array(vec.map(deserialize))
      case JsObject(fields) =>
        V_Object(fields.map { case (k, v) => k -> deserialize(v) })
    }
  }

  /**
    * Deserializes a JSON input value with a type to a `V`. Only handles supported types
    * with constant values. Input formats that support alternative representations (e.g. object
    * value for File) must pre-process those values first. Does *not* perform file localization.
    * @param name: the variable name (for error reporting)
    * @param wdlType: the destination type
    * @param jsValue: the JSON value
    * @return the WDL value
    */
  def deserialize(
      jsValue: JsValue,
      wdlType: T,
      name: String = ""
  ): V = {
    def inner(innerValue: JsValue, innerType: T, innerName: String): V = {
      (innerType, innerValue) match {
        // primitive types
        case (T_Boolean, JsBoolean(b))  => V_Boolean(b.booleanValue)
        case (T_Int, JsNumber(i))       => V_Int(i.longValue)
        case (T_Float, JsNumber(f))     => V_Float(f.doubleValue)
        case (T_String, JsString(s))    => V_String(s)
        case (T_File, JsString(s))      => V_File(s)
        case (T_Directory, JsString(s)) => V_Directory(s)

        // maps
        case (T_Map(keyType, valueType), JsObject(fields)) =>
          val m = fields.map {
            case (k: String, v: JsValue) =>
              val kWdl = inner(JsString(k), keyType, s"${innerName}.${k}")
              val vWdl = inner(v, valueType, s"${innerName}.${k}")
              kWdl -> vWdl
          }
          V_Map(m)

        // two ways of writing a pair: an object, or an array
        case (T_Pair(lType, rType), JsObject(fields))
            if Vector("left", "right").forall(fields.contains) =>
          val left = inner(fields("left"), lType, s"${innerName}.left")
          val right = inner(fields("right"), rType, s"${innerName}.right")
          V_Pair(left, right)
        case (T_Pair(lType, rType), JsArray(Vector(l, r))) =>
          val left = inner(l, lType, s"${innerName}.left")
          val right = inner(r, rType, s"${innerName}.right")
          V_Pair(left, right)

        // empty array
        case (T_Array(_, _), JsNull) =>
          V_Array(Vector.empty)

        // array
        case (T_Array(t, _), JsArray(vec)) =>
          val wVec: Vector[V] = vec.zipWithIndex.map {
            case (elem: JsValue, index) =>
              inner(elem, t, s"${innerName}[${index}]")
          }
          V_Array(wVec)

        // optionals
        case (T_Optional(_), JsNull) =>
          V_Null
        case (T_Optional(t), jsv) =>
          val value = inner(jsv, t, innerName)
          V_Optional(value)

        // structs
        case (T_Struct(structName, typeMap), JsObject(fields)) =>
          // convert each field
          val m = fields
            .map {
              case (key, value) =>
                val t: T = typeMap(key)
                val elem: V = inner(value, t, s"${innerName}.${key}")
                key -> elem
            }
          V_Struct(structName, m)

        case (_: T_Collection, JsString(path)) =>
          // a complex value may be stored in Archive format, which is serialized
          // as a path to the archive file
          V_Archive(path)

        case _ =>
          throw new WdlValueSerializationException(
              s"Unsupported value ${innerValue.prettyPrint} for input ${innerName} with type ${innerType}"
          )
      }
    }
    inner(jsValue, wdlType, name)
  }
}
