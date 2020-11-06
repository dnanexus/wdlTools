package wdlTools.eval

import java.nio.file.{Files, Path}

import spray.json._
import wdlTools.eval.WdlValues._
import wdlTools.types.{WdlTypeSerde, WdlTypes}
import dx.util.{Bindings, SysUtils}

object Archive {
  val ManifestFile: String = "manifest.json"
  val OutputsDir: String = "outputs"
}

/**
  * An Archive is a TAR file (possibly compressed) that contains
  * 1) a JSON file (called manifest.json) with the serialized
  * representation of a complex value, along with its serialized
  * type information, and 2) the files referenced by all of the
  * V_File values nested within the complex value.
  *
  * TODO: currently this is implemented using system calls, but we
  *  could also use a Java library (e.g. Apache commons).
  */
case class Archive(path: Path, typeAliases: Map[String, WdlTypes.T]) {
  lazy val (wdlType: WdlTypes.T, wdlValue: WdlValues.V) = {
    val options = if (!Files.exists(path)) {
      throw new Exception(s"${path} does not exist")
    } else if (Files.isDirectory(path)) {
      throw new Exception(s"${path} is not a file")
    } else if (path.getFileName.endsWith(".tar.gz") || path.getFileName.endsWith(".tgz")) {
      "-z"
    } else if (path.getFileName.endsWith(".tar")) {
      ""
    } else {
      throw new Exception(s"${path} does not look like tar file")
    }
    try {
      val (_, stdout, _) =
        SysUtils.execCommand(s"tar -xO ${options} -f ${path.toString} ${Archive.ManifestFile}")
      val fields = stdout.parseJson.asJsObject.fields
      val wdlType = WdlTypeSerde.deserializeType(fields("type"), typeAliases)
      val wdlValue = WdlValueSerde.deserialize(fields("value"), wdlType)
      (wdlType, wdlValue)
    } catch {
      case ex: Throwable =>
        throw new Exception(s"invalid WDL value archive ${path}", ex)
    }
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
      wdlType: WdlTypes.T,
      name: String = ""
  ): V = {
    def inner(innerValue: JsValue, innerType: WdlTypes.T, innerName: String): V = {
      (innerType, innerValue) match {
        // primitive types
        case (WdlTypes.T_Boolean, JsBoolean(b))  => V_Boolean(b.booleanValue)
        case (WdlTypes.T_Int, JsNumber(i))       => V_Int(i.longValue)
        case (WdlTypes.T_Float, JsNumber(f))     => V_Float(f.doubleValue)
        case (WdlTypes.T_String, JsString(s))    => V_String(s)
        case (WdlTypes.T_File, JsString(s))      => V_File(s)
        case (WdlTypes.T_Directory, JsString(s)) => V_Directory(s)

        // maps
        case (WdlTypes.T_Map(keyType, valueType), JsObject(fields)) =>
          val m = fields.map {
            case (k: String, v: JsValue) =>
              val kWdl = inner(JsString(k), keyType, s"${innerName}.${k}")
              val vWdl = inner(v, valueType, s"${innerName}.${k}")
              kWdl -> vWdl
          }
          V_Map(m)

        // two ways of writing a pair: an object, or an array
        case (WdlTypes.T_Pair(lType, rType), JsObject(fields))
            if Vector("left", "right").forall(fields.contains) =>
          val left = inner(fields("left"), lType, s"${innerName}.left")
          val right = inner(fields("right"), rType, s"${innerName}.right")
          V_Pair(left, right)
        case (WdlTypes.T_Pair(lType, rType), JsArray(Vector(l, r))) =>
          val left = inner(l, lType, s"${innerName}.left")
          val right = inner(r, rType, s"${innerName}.right")
          V_Pair(left, right)

        // empty array
        case (WdlTypes.T_Array(_, _), JsNull) =>
          V_Array(Vector.empty)

        // array
        case (WdlTypes.T_Array(t, _), JsArray(vec)) =>
          val wVec: Vector[V] = vec.zipWithIndex.map {
            case (elem: JsValue, index) =>
              inner(elem, t, s"${innerName}[${index}]")
          }
          V_Array(wVec)

        // optionals
        case (WdlTypes.T_Optional(_), JsNull) =>
          V_Null
        case (WdlTypes.T_Optional(t), jsv) =>
          val value = inner(jsv, t, innerName)
          V_Optional(value)

        // structs
        case (WdlTypes.T_Struct(structName, typeMap), JsObject(fields)) =>
          // convert each field
          val m = fields
            .map {
              case (key, value) =>
                val t: WdlTypes.T = typeMap(key)
                val elem: V = inner(value, t, s"${innerName}.${key}")
                key -> elem
            }
          V_Struct(structName, m)

        case (_: WdlTypes.T_Collection, JsString(path)) =>
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
