/*
 * Copyright 2020-2022 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.schema.codec

import java.math.{ BigDecimal => JBigDecimal, BigInteger => JBigInteger }
import java.nio.charset.StandardCharsets
import java.time._
import java.time.format.DateTimeFormatter
import java.util.{ Currency, UUID }

import scala.util.control.NonFatal
import scala.xml.{ Elem, MetaData, Node, Null, Text, TopScope, XML }

import zio.schema._
import zio.stream.ZPipeline
import zio.{ Cause, Chunk, ZIO }

// fs2-data imports
import _root_.fs2.Stream
import _root_.fs2.data.xml // To access members like events()
import _root_.fs2.data.xml.scala.{ eventsToDom => fs2EventsToDom }
import _root_.fs2.data.text // To access members like string.chars
import _root_.cats.effect.IO // fs2-data pipes often require an effect type like IO
import _root_.cats.effect.unsafe.implicits.global // For unsafeRunSync, be cautious

object XmlCodec {

  /**
   * Configuration for the XML codec.
   *
   * @param useAttributes whether to encode case class fields as XML attributes when possible
   * @param prettyPrint whether to format the XML output with indentation
   * @param fieldNameFormat format for XML element and attribute names
   * @param rootElementName the name to use for the root element when encoding
   * @param chunkSize buffer size to use when streaming XML content
   */
  final case class Configuration(
    useAttributes: Boolean = false,
    prettyPrint: Boolean = false,
    fieldNameFormat: NameFormat = NameFormat.Identity,
    rootElementName: String = "root",
    chunkSize: Int = 8192
  )

  object Configuration {
    val default: Configuration = Configuration()
  }

  implicit def xmlCodec[A](implicit schema: Schema[A]): BinaryCodec[A] =
    xmlCodec(Configuration.default)

  def xmlCodec[A](config: Configuration)(implicit schema: Schema[A]): BinaryCodec[A] =
    new BinaryCodec[A] {
      // Helper function to parse XML string to scala.xml.Node using fs2-data
      // This is made synchronous for the decode method using unsafeRunSync.
      private def parseXmlStringToScalaXml(xmlString: String): Either[Throwable, scala.xml.Node] =
        try {
          Stream.emit(xmlString)
            .through(_root_.fs2.data.text.string.chars[IO])      // Stream[IO, Char]
            .through(_root_.fs2.data.xml.events[IO]())           // Pipe[IO, Char, XmlEvent]
            .through(fs2EventsToDom[IO])                         // Pipe[IO, XmlEvent, scala.xml.Node]
            .compile
            .toList                              // IO[List[scala.xml.Node]]
            .map(_.headOption)                   // IO[Option[scala.xml.Node]]
            .unsafeRunSync()                     // Option[scala.xml.Node] - This blocks
            .toRight(new RuntimeException("XML parsing failed to produce a root node or XML was empty."))
        } catch {
          case NonFatal(e) => Left(e)
        }
        
      override def decode(whole: Chunk[Byte]): Either[DecodeError, A] =
        try {
          val xmlString = new String(whole.toArray, StandardCharsets.UTF_8)
          // val xml       = XML.loadString(xmlString) // Old, problematic for Scala.js
          // decodeXml(schema, xml)

          parseXmlStringToScalaXml(xmlString) match {
            case Right(xml) => decodeXml(schema, xml)
            case Left(err)  => Left(DecodeError.ReadError(Cause.fail(err), Option(err.getMessage).getOrElse("XML parsing failed via fs2-data")))
          }
        } catch {
          case NonFatal(e) => // Catch errors from xmlString conversion or other unexpected ones
            Left(DecodeError.ReadError(Cause.fail(e), e.getMessage))
        }

      override def streamDecoder: ZPipeline[Any, DecodeError, Byte, A] =
        // FIXME: This still uses XML.loadString and needs to be updated to use fs2-data pipes with ZIO streams.
        // This will require careful integration of fs2.Pipe with ZPipeline, possibly via zio-interop-cats.
        ZPipeline.rechunk(Int.MaxValue).mapChunksZIO { (allBytesCollected: Chunk[Byte]) =>
          try {
            val xmlString = new String(allBytesCollected.toArray, StandardCharsets.UTF_8)
            val xml       = XML.loadString(xmlString) // Problematic for Scala.js

            // Check if root is <items> for stream decoding
            if (xml.label == "items") {
              val itemNodes = xml \ "item"
              val decodedItemsZIO = ZIO.foreach(itemNodes.toList) { itemNode =>
                ZIO.fromEither(decodeXml(schema, itemNode)) // Decode each item
              }
              decodedItemsZIO.map(Chunk.fromIterable)
            } else {
              // Fallback: Assume single item if not wrapped in <items>
              ZIO.fromEither(decodeXml(schema, xml)).map(Chunk.single)
            }
          } catch {
            case NonFatal(e) => ZIO.fail(DecodeError.ReadError(Cause.fail(e), e.getMessage))
          }
        }

      override def encode(value: A): Chunk[Byte] =
        try {
          val xml = encodeXml(schema, value, config.rootElementName, config)
          val xmlString = if (config.prettyPrint) {
            val printer = new scala.xml.PrettyPrinter(80, 2)
            printer.format(xml)
          } else {
            xml.toString
          }
          Chunk.fromArray(xmlString.getBytes(StandardCharsets.UTF_8))
        } catch {
          case NonFatal(e) =>
            throw e
        }

      override def streamEncoder: ZPipeline[Any, Nothing, A, Byte] =
        ZPipeline.mapChunksZIO { chunk =>
          if (chunk.isEmpty) {
            ZIO.succeed(Chunk.empty)
          } else {
            // Encode each item and wrap in a root element
            val itemsXml = chunk.map(item => encodeXml(schema, item, "item", config))
            val rootElem = Elem(null, "items", Null, TopScope, false, itemsXml.toSeq: _*)

            val xmlString = if (config.prettyPrint) {
              val printer = new scala.xml.PrettyPrinter(80, 2)
              printer.format(rootElem)
            } else {
              rootElem.toString
            }
            ZIO.succeed(Chunk.fromArray(xmlString.getBytes(StandardCharsets.UTF_8)))
          }
        }
    }

  private def encodeXml[A](schema: Schema[A], value: A, elementName: String, config: Configuration): Elem =
    schema match {
      case Schema.Primitive(standardType, _) =>
        encodePrimitive(standardType.asInstanceOf[StandardType[Any]], value.asInstanceOf[Any], elementName)

      case Schema.Optional(schema, _) =>
        value.asInstanceOf[Option[_]] match {
          case Some(v) => encodeXml(schema.asInstanceOf[Schema[Any]], v, elementName, config)
          case None    => Elem(null, elementName, scala.xml.Null, scala.xml.TopScope, true)
        }

      case Schema.Tuple2(left, right, _) =>
        val (l, r)    = value.asInstanceOf[(Any, Any)]
        val leftElem  = encodeXml(left.asInstanceOf[Schema[Any]], l, "item1", config)
        val rightElem = encodeXml(right.asInstanceOf[Schema[Any]], r, "item2", config)

        Elem(null, elementName, scala.xml.Null, scala.xml.TopScope, false, leftElem, rightElem)

      case Schema.Transform(codec, _, g, _, _) =>
        val sourceValue = g(value)
        encodeXml(codec.asInstanceOf[Schema[Any]], sourceValue, elementName, config)

      case Schema.Lazy(schema0) =>
        encodeXml(schema0().asInstanceOf[Schema[Any]], value, elementName, config)

      case record: Schema.Record[_] =>
        encodeRecord(record, value.asInstanceOf[AnyRef], elementName, config)

      case enum: Schema.Enum[_] =>
        encodeEnum(enum, value, elementName)

      case Schema.Sequence(schema, _, _, _, _) =>
        val seq = value.asInstanceOf[Seq[_]]
        val itemElems =
          seq.map(item => encodeXml(schema.asInstanceOf[Schema[Any]], item.asInstanceOf[Any], "item", config))

        Elem(null, elementName, scala.xml.Null, scala.xml.TopScope, false, itemElems.toSeq: _*)

      case Schema.Set(schema, _) =>
        val set = value.asInstanceOf[Set[_]]
        val itemElems =
          set.map(item => encodeXml(schema.asInstanceOf[Schema[Any]], item.asInstanceOf[Any], "item", config))

        Elem(null, elementName, scala.xml.Null, scala.xml.TopScope, false, itemElems.toSeq: _*)

      case Schema.Map(keySchema, valueSchema, _) =>
        val map = value.asInstanceOf[Map[_, _]]
        val entryElems = map.toSeq.map {
          case (k, v) =>
            val keyElem   = encodeXml(keySchema.asInstanceOf[Schema[Any]], k.asInstanceOf[Any], "key", config)
            val valueElem = encodeXml(valueSchema.asInstanceOf[Schema[Any]], v.asInstanceOf[Any], "value", config)

            Elem(null, "entry", scala.xml.Null, scala.xml.TopScope, false, keyElem, valueElem)
        }

        Elem(null, elementName, scala.xml.Null, scala.xml.TopScope, false, entryElems: _*)

      case _ =>
        // For any other types
        Elem(
          null,
          elementName,
          scala.xml.Null,
          scala.xml.TopScope,
          true,
          Text(s"Not implemented yet for schema type: ${schema.getClass.getSimpleName}")
        )
    }

  private def encodeRecord[A <: AnyRef](
    record: Schema.Record[_],
    value: A,
    elementName: String,
    config: Configuration
  ): Elem = {
    // Extract fields from record
    val fields = record.fields

    var attributes: MetaData = Null
    val childElements        = scala.collection.mutable.ListBuffer[Node]()

    fields.foreach { field =>
      val rawFieldValue = field.get(value) // Get field value using schema; Corrected: no .asInstanceOf[field.Parent]

      var isAttributeCandidate = false
      var attributeStringValue: Option[String] = None

      if (config.useAttributes) {
        field.schema match {
          case prim: Schema.Primitive[ptype] => // Capture primitive's type
            isAttributeCandidate = true
            if (rawFieldValue != null) {
              // rawFieldValue should be of type ptype.
              // prim.standardType is StandardType[ptype].
              attributeStringValue = Some(prim.standardType.format(rawFieldValue.asInstanceOf[ptype]))
            }
          case Schema.Optional(prim: Schema.Primitive[optype], _) => // Capture optional primitive's type
            isAttributeCandidate = true
            // rawFieldValue is Option[optype]
            rawFieldValue.asInstanceOf[Option[optype]].foreach { innerValue =>
              // innerValue is optype. prim.standardType is StandardType[optype].
              // format expects optype, not Any.
              attributeStringValue = Some(prim.standardType.format(innerValue))
            }
          case _ => // Not a primitive or Option[Primitive], so not an attribute
        }
      }

      if (isAttributeCandidate && attributeStringValue.isDefined) {
        attributes = new scala.xml.UnprefixedAttribute(field.name, Text(attributeStringValue.get), attributes)
      } else {
        // Not an attribute, or is None for optional primitive attribute candidates, or Some(null) which format might handle.
        // Encode as a child element. encodeXml will handle Option wrappers correctly for elements.
        // The cast to Schema[Any] and .asInstanceOf[Any] is because encodeXml is generic,
        // but we know field.schema and rawFieldValue are of compatible types.
        childElements += encodeXml(field.schema.asInstanceOf[Schema[Any]], rawFieldValue.asInstanceOf[Any], field.name, config)
      }
    }

    Elem(null, elementName, attributes, TopScope, childElements.isEmpty, childElements.toSeq: _*)
  }

  private def encodeEnum[A](enumSchema: Schema.Enum[_], value: A, elementName: String): Elem = {
    val caseClass = value.asInstanceOf[AnyRef]
    // Get the simple name of the case class, remove trailing $ if it's an object
    var simpleName = caseClass.getClass.getSimpleName
    if (simpleName.endsWith("$")) {
      simpleName = simpleName.dropRight(1)
    }

    // Add the simple name as a 'type' attribute for discrimination
    val attr = new scala.xml.UnprefixedAttribute("type", simpleName, scala.xml.Null)
    Elem(null, elementName, attr, TopScope, true) // Use true for empty elements
  }

  private def encodePrimitive[A](standardType: StandardType[A], value: A, elementName: String): Elem = {
    val content = standardType match {
      case StandardType.UnitType =>
        ""
      case StandardType.StringType =>
        value.toString
      case StandardType.BoolType =>
        value.toString
      case StandardType.ByteType =>
        value.toString
      case StandardType.ShortType =>
        value.toString
      case StandardType.IntType =>
        value.toString
      case StandardType.LongType =>
        value.toString
      case StandardType.FloatType =>
        value.toString
      case StandardType.DoubleType =>
        value.toString
      case StandardType.BinaryType =>
        java.util.Base64.getEncoder.encodeToString(value.asInstanceOf[Array[Byte]])
      case StandardType.CharType =>
        value.toString
      case StandardType.BigIntegerType =>
        value.toString
      case StandardType.BigDecimalType =>
        value.toString
      case StandardType.UUIDType =>
        value.toString

      // Date/time types
      case StandardType.DayOfWeekType =>
        value.toString
      case StandardType.MonthType =>
        value.toString
      case StandardType.MonthDayType =>
        value.toString
      case StandardType.PeriodType =>
        value.toString
      case StandardType.YearType =>
        value.toString
      case StandardType.YearMonthType =>
        value.toString
      case StandardType.ZoneIdType =>
        value.toString
      case StandardType.ZoneOffsetType =>
        value.toString
      case StandardType.DurationType =>
        value.toString
      case StandardType.InstantType =>
        DateTimeFormatter.ISO_INSTANT.format(value.asInstanceOf[Instant])
      case StandardType.LocalDateType =>
        DateTimeFormatter.ISO_LOCAL_DATE.format(value.asInstanceOf[LocalDate])
      case StandardType.LocalTimeType =>
        DateTimeFormatter.ISO_LOCAL_TIME.format(value.asInstanceOf[LocalTime])
      case StandardType.LocalDateTimeType =>
        DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value.asInstanceOf[LocalDateTime])
      case StandardType.OffsetTimeType =>
        DateTimeFormatter.ISO_OFFSET_TIME.format(value.asInstanceOf[OffsetTime])
      case StandardType.OffsetDateTimeType =>
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.asInstanceOf[OffsetDateTime])
      case StandardType.ZonedDateTimeType =>
        DateTimeFormatter.ISO_ZONED_DATE_TIME.format(value.asInstanceOf[ZonedDateTime])
      case StandardType.CurrencyType =>
        value.toString
    }

    Elem(null, elementName, scala.xml.Null, scala.xml.TopScope, true, Text(content))
  }

  private def decodeXml[A](schema: Schema[A], xml: Node): Either[DecodeError, A] =
    try {
      // FIXME: XML.loadString (used by callers of decodeXml) relies on javax.xml.parsers.SAXParserFactory,
      // which is not available in Scala.js, leading to linking errors.
      // A Scala.js-compatible XML parsing mechanism is needed, e.g., from fs2-data-xml-scala or other alternatives.
      schema match {
        case Schema.Primitive(standardType, _) =>
          decodePrimitive(standardType.asInstanceOf[StandardType[Any]], xml).map(_.asInstanceOf[A])

        case Schema.Optional(schema, _) =>
          if (xml.text.trim.isEmpty) {
            Right(None.asInstanceOf[A])
          } else {
            decodeXml(schema, xml).map(Some(_)).map(_.asInstanceOf[A])
          }

        case Schema.Tuple2(left, right, _) =>
          val leftNode  = (xml \ "item1").headOption.getOrElse(xml)
          val rightNode = (xml \ "item2").headOption.getOrElse(xml)

          for {
            l <- decodeXml(left, leftNode)
            r <- decodeXml(right, rightNode)
          } yield (l, r).asInstanceOf[A]

        case Schema.Transform(codec, f, _, _, _) =>
          decodeXml(codec, xml).map(f).asInstanceOf[Either[DecodeError, A]]

        case Schema.Lazy(schema0) =>
          decodeXml(schema0(), xml)

        case record: Schema.Record[_] =>
          decodeRecord(record.asInstanceOf[Schema.Record[A]], xml)

        case enum: Schema.Enum[_] =>
          decodeEnum(enum.asInstanceOf[Schema.Enum[A]], xml)

        case Schema.Sequence(schema, _, _, _, _) =>
          val nodeItems = xml \ "item"
          val decodedItems = nodeItems.map { itemNode =>
            decodeXml(schema, itemNode) match {
              case Right(value) => value
              case Left(error)  => return Left(error)
            }
          }
          Right(decodedItems.toSeq.asInstanceOf[A])

        case Schema.Set(schema, _) =>
          val nodeItems = xml \ "item"
          val decodedItems = nodeItems.map { itemNode =>
            decodeXml(schema, itemNode) match {
              case Right(value) => value
              case Left(error)  => return Left(error)
            }
          }
          Right(decodedItems.toSet.asInstanceOf[A])

        case Schema.Map(keySchema, valueSchema, _) =>
          val entries = xml \ "entry"
          val decodedPairs = entries.map { entryNode =>
            val keyNode   = (entryNode \ "key").head
            val valueNode = (entryNode \ "value").head

            for {
              k <- decodeXml(keySchema, keyNode)
              v <- decodeXml(valueSchema, valueNode)
            } yield (k, v)
          }

          val resultMap = decodedPairs.foldLeft[Either[DecodeError, Map[Any, Any]]](Right(Map.empty)) {
            case (Right(map), Right(pair)) => Right(map + pair)
            case (Left(err), _)            => Left(err)
            case (_, Left(err))            => Left(err)
          }

          resultMap.asInstanceOf[Either[DecodeError, A]]

        case _ =>
          // For complex types (to be implemented)
          Left(
            DecodeError
              .ReadError(Cause.empty, s"Decoding not implemented yet for schema type: ${schema.getClass.getSimpleName}")
          )
      }
    } catch {
      case NonFatal(e) =>
        Left(DecodeError.ReadError(Cause.fail(e), s"Error decoding XML: ${e.getMessage}"))
    }

  private def decodeRecord[A](record: Schema.Record[A], xml: Node): Either[DecodeError, A] =
    try {
      // Define unsafe context before it's potentially needed by construct
      implicit val unsafe: zio.Unsafe = zio.Unsafe.unsafe

      val constructorFn = record.construct _
      val fields        = record.fields
      val values        = scala.collection.mutable.ArrayBuffer[Any]()

      for (field <- fields) {
        val fieldName   = field.name
        val fieldSchema = field.schema

        // Try to get the field from child elements
        val fieldNode = (xml \ fieldName).headOption

        val fieldValue = fieldNode match {
          case Some(node) =>
            decodeXml(fieldSchema, node) match {
              case Right(value) => Some(value)
              case Left(error)  => return Left(error)
            }
          case None =>
            // If field not found, use default if available or None for optional
            if (fieldSchema.isInstanceOf[Schema.Optional[_]]) {
              Some(None)
            } else {
              return Left(DecodeError.ReadError(Cause.empty, s"Required field '$fieldName' is missing"))
            }
        }

        // Use fieldValue directly and handle by type instead of pattern matching on None
        if (fieldValue.isDefined) {
          values += fieldValue.get
        } else if (fieldSchema.isInstanceOf[Schema.Optional[_]]) {
          values += None
        } else {
          return Left(DecodeError.ReadError(Cause.empty, s"Required field '$fieldName' is missing"))
        }
      }

      // Using unsafe construct to build the record instance
      constructorFn(Chunk.fromIterable(values)).fold(
        error => Left(DecodeError.ReadError(Cause.empty, error)),
        value => Right(value)
      )
    } catch {
      case NonFatal(e) =>
        Left(DecodeError.ReadError(Cause.fail(e), s"Error decoding record: ${e.getMessage}"))
    }

  private def decodeEnum[A](schema: Schema.Enum[A], xml: Node): Either[DecodeError, A] =
    try {
      xml match {
        case elem: Elem =>
          elem.attribute("type") match {
            case Some(typeAttrNodes) =>
              val typeName = typeAttrNodes.text

              val matchingCaseOpt = schema.cases.find { c =>
                val caseSimpleName = c.id.split('.').lastOption.getOrElse("")
                // The encoder now strips trailing '$', so direct match should work
                caseSimpleName == typeName
              }

              matchingCaseOpt match {
                case Some(matchingCase) =>
                  // Check if it's a case object (schema is Schema[Unit])
                  if (matchingCase.schema == Schema.primitive[Unit]) {
                    // For case objects (Schema[Unit]), construct with empty args
                    matchingCase.asInstanceOf[Schema.Case[A, Unit]].construct(()) match {
                      case Right(value) => Right(value.asInstanceOf[A])
                      case Left(error) =>
                        Left(
                          DecodeError.ReadError(Cause.empty, s"Failed to construct enum case object $typeName: $error")
                        )
                    }
                  } else {
                    // Explicitly fail for non-case-object enums for now
                    Left(
                      DecodeError.ReadError(Cause.empty, s"Decoding enum case class '$typeName' is not supported yet")
                    )
                  }
                case None =>
                  Left(DecodeError.ReadError(Cause.empty, s"Unknown enum case type: $typeName from attribute"))
              }
            case None =>
              Left(DecodeError.ReadError(Cause.empty, "Missing 'type' attribute for enum value"))
          }
        case _ =>
          Left(DecodeError.ReadError(Cause.empty, "Enum value must be an XML element"))
      }
    } catch {
      case NonFatal(e) => Left(DecodeError.ReadError(Cause.fail(e), e.getMessage))
    }

  private def decodePrimitive[A](standardType: StandardType[A], xml: Node): Either[DecodeError, A] = {
    val content = xml.text

    try {
      val result = standardType match {
        case StandardType.UnitType =>
          ()
        case StandardType.StringType =>
          content
        case StandardType.BoolType =>
          content.toLowerCase match {
            case "true" | "1" | "yes" => true
            case "false" | "0" | "no" => false
            case _                    => throw new IllegalArgumentException(s"Invalid boolean value: $content")
          }
        case StandardType.ByteType =>
          content.toByte
        case StandardType.ShortType =>
          content.toShort
        case StandardType.IntType =>
          content.toInt
        case StandardType.LongType =>
          content.toLong
        case StandardType.FloatType =>
          content.toFloat
        case StandardType.DoubleType =>
          content.toDouble
        case StandardType.BinaryType =>
          java.util.Base64.getDecoder.decode(content)
        case StandardType.CharType =>
          if (content.length != 1) throw new IllegalArgumentException(s"Expected a single character but got: $content")
          content.charAt(0)
        case StandardType.BigIntegerType =>
          new JBigInteger(content)
        case StandardType.BigDecimalType =>
          new JBigDecimal(content)
        case StandardType.UUIDType =>
          UUID.fromString(content)

        // Date/time types
        case StandardType.DayOfWeekType =>
          DayOfWeek.valueOf(content)
        case StandardType.MonthType =>
          Month.valueOf(content)
        case StandardType.MonthDayType =>
          MonthDay.parse(content)
        case StandardType.PeriodType =>
          Period.parse(content)
        case StandardType.YearType =>
          Year.parse(content)
        case StandardType.YearMonthType =>
          YearMonth.parse(content)
        case StandardType.ZoneIdType =>
          ZoneId.of(content)
        case StandardType.ZoneOffsetType =>
          ZoneOffset.of(content)
        case StandardType.DurationType =>
          Duration.parse(content)
        case StandardType.InstantType =>
          Instant.parse(content)
        case StandardType.LocalDateType =>
          LocalDate.parse(content)
        case StandardType.LocalTimeType =>
          LocalTime.parse(content)
        case StandardType.LocalDateTimeType =>
          LocalDateTime.parse(content)
        case StandardType.OffsetTimeType =>
          OffsetTime.parse(content)
        case StandardType.OffsetDateTimeType =>
          OffsetDateTime.parse(content)
        case StandardType.ZonedDateTimeType =>
          ZonedDateTime.parse(content)
        case StandardType.CurrencyType =>
          Currency.getInstance(content)
      }

      Right(result.asInstanceOf[A])
    } catch {
      case NonFatal(e) =>
        Left(DecodeError.ReadError(Cause.fail(e), s"Error decoding primitive type ${standardType}: ${e.getMessage}"))
    }
  }
}
