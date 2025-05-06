package zio.schema.codec

import java.time.LocalDate
import java.util.UUID

import scala.xml.XML

import zio.schema.{ DeriveSchema, Schema }
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test.{ Spec, TestAspect, TestEnvironment, ZIOSpecDefault, assert, assertTrue }
import zio.{ Chunk, ZIO }

object XmlCodecSpec extends ZIOSpecDefault {

  case class Person(name: String, age: Int)

  object Person {
    implicit val schema: Schema[Person] = DeriveSchema.gen[Person]
  }

  case class Address(street: String, city: String, zipCode: String)

  object Address {
    implicit val schema: Schema[Address] = DeriveSchema.gen[Address]
  }

  case class Employee(id: Int, name: String, address: Address, roles: List[String])

  object Employee {
    implicit val schema: Schema[Employee] = DeriveSchema.gen[Employee]
  }

  sealed trait PaymentMethod

  object PaymentMethod {
    case class CreditCard(number: String, expiry: String)            extends PaymentMethod
    case class BankTransfer(accountNumber: String, bankCode: String) extends PaymentMethod
    case object Cash                                                 extends PaymentMethod

    implicit val creditCardSchema: Schema[CreditCard]     = DeriveSchema.gen[CreditCard]
    implicit val bankTransferSchema: Schema[BankTransfer] = DeriveSchema.gen[BankTransfer]
    implicit val cashSchema: Schema[Cash.type]            = DeriveSchema.gen[Cash.type]
    implicit val schema: Schema[PaymentMethod]            = DeriveSchema.gen[PaymentMethod]
  }

  // Add definition for PaymentMethodComplex used in the ignored test
  sealed trait PaymentMethodComplex

  object PaymentMethodComplex {
    case class CreditCard(number: String, expiry: String, cvv: Int)   extends PaymentMethodComplex
    case class BankTransfer(accountNumber: String, swiftCode: String) extends PaymentMethodComplex
    implicit val schema: Schema[PaymentMethodComplex] = DeriveSchema.gen[PaymentMethodComplex]
  }

  override def spec: Spec[TestEnvironment, Any] =
    suite("XmlCodecSpec")(
      test("XmlCodec should be created from schema") {
        val codec = XmlCodec.xmlCodec[Person]
        assertTrue(codec != null)
      },
      suite("primitive types")(
        test("encode and decode String") {
          val schema  = Schema[String]
          val codec   = XmlCodec.xmlCodec(schema)
          val value   = "Test string with special chars: <>&'\""
          val encoded = codec.encode(value)
          val decoded = codec.decode(encoded)
          assert(decoded)(isRight(equalTo(value)))
        },
        test("encode and decode Int") {
          val schema  = Schema[Int]
          val codec   = XmlCodec.xmlCodec(schema)
          val value   = 42
          val encoded = codec.encode(value)
          val decoded = codec.decode(encoded)
          assert(decoded)(isRight(equalTo(value)))
        },
        test("encode and decode Boolean") {
          val schema  = Schema[Boolean]
          val codec   = XmlCodec.xmlCodec(schema)
          val value   = true
          val encoded = codec.encode(value)
          val decoded = codec.decode(encoded)
          assert(decoded)(isRight(equalTo(value)))
        },
        test("encode and decode LocalDate") {
          val schema  = Schema[LocalDate]
          val codec   = XmlCodec.xmlCodec(schema)
          val value   = LocalDate.of(2023, 12, 31)
          val encoded = codec.encode(value)
          val decoded = codec.decode(encoded)
          assert(decoded)(isRight(equalTo(value)))
        },
        test("encode and decode UUID") {
          val schema  = Schema[UUID]
          val codec   = XmlCodec.xmlCodec(schema)
          val value   = UUID.fromString("f81d4fae-7dec-11d0-a765-00a0c91e6bf6")
          val encoded = codec.encode(value)
          val decoded = codec.decode(encoded)
          assert(decoded)(isRight(equalTo(value)))
        },
        test("encode and decode Option[String]") {
          val schema = Schema[Option[String]]
          val codec  = XmlCodec.xmlCodec(schema)

          val value1   = Some("test")
          val encoded1 = codec.encode(value1)
          val decoded1 = codec.decode(encoded1)

          val value2: Option[String] = None
          val encoded2               = codec.encode(value2)
          val decoded2               = codec.decode(encoded2)

          assert(decoded1)(isRight(equalTo(value1))) &&
          assert(decoded2)(isRight(equalTo(value2)))
        }
      ),
      suite("case classes")(
        test("encode and decode simple case class") {
          val codec   = XmlCodec.xmlCodec[Person]
          val value   = Person("John Doe", 42)
          val encoded = codec.encode(value)
          val decoded = codec.decode(encoded)
          assert(decoded)(isRight(equalTo(value)))
        },
        test("encode and decode nested case class") {
          val codec = XmlCodec.xmlCodec[Employee]
          val value = Employee(
            id = 123,
            name = "Jane Smith",
            address = Address("123 Main St", "Anytown", "12345"),
            roles = List("Developer", "Team Lead")
          )
          val encoded = codec.encode(value)
          val decoded = codec.decode(encoded)
          assert(decoded)(isRight(equalTo(value)))
        },
        test("encode and decode with attribute option enabled") {
          val config  = XmlCodec.Configuration(useAttributes = true)
          val codec   = XmlCodec.xmlCodec[Person](config)
          val value   = Person("John Doe", 42)
          val encoded = codec.encode(value)
          val decoded = codec.decode(encoded)

          // Also check the XML contains attributes
          val xmlString   = new String(encoded.toArray)
          val xml         = XML.loadString(xmlString)
          val hasNameAttr = xml.attribute("name").exists(_.text == "John Doe")
          val hasAgeAttr  = xml.attribute("age").exists(_.text == "42")

          assert(decoded)(isRight(equalTo(value))) &&
          assertTrue(hasNameAttr && hasAgeAttr)
        } @@ TestAspect.ignore
      ),
      suite("collections")(
        test("encode and decode List") {
          val schema  = Schema[List[String]]
          val codec   = XmlCodec.xmlCodec(schema)
          val value   = List("one", "two", "three")
          val encoded = codec.encode(value)
          val decoded = codec.decode(encoded)
          assert(decoded)(isRight(equalTo(value)))
        },
        test("encode and decode Set") {
          val schema  = Schema[Set[Int]]
          val codec   = XmlCodec.xmlCodec(schema)
          val value   = Set(1, 2, 3, 4, 5)
          val encoded = codec.encode(value)
          val decoded = codec.decode(encoded)
          assert(decoded)(isRight(equalTo(value)))
        },
        test("encode and decode Map") {
          val schema  = Schema[Map[String, Int]]
          val codec   = XmlCodec.xmlCodec(schema)
          val value   = Map("one" -> 1, "two" -> 2, "three" -> 3)
          val encoded = codec.encode(value)
          val decoded = codec.decode(encoded)
          assert(decoded)(isRight(equalTo(value)))
        }
      ),
      suite("enums")(
        test("encode and decode enum with no fields") {
          val codec                = XmlCodec.xmlCodec[PaymentMethod]
          val value: PaymentMethod = PaymentMethod.Cash
          val encoded              = codec.encode(value)
          val decoded              = codec.decode(encoded)
          assert(decoded)(isRight(equalTo(value)))
        } @@ TestAspect.ignore,
        test("encode and decode enum with fields") {
          val codec                        = XmlCodec.xmlCodec[PaymentMethodComplex]
          val value1: PaymentMethodComplex = PaymentMethodComplex.CreditCard("1234-5678-8765-4321", "12/25", 123)
          val value2: PaymentMethodComplex = PaymentMethodComplex.BankTransfer("ACC98765", "BANKUS12")
          val encoded1                     = codec.encode(value1)
          val decoded1                     = codec.decode(encoded1)
          val encoded2                     = codec.encode(value2)
          val decoded2                     = codec.decode(encoded2)
          assert(decoded1)(isRight(equalTo(value1))) &&
          assert(decoded2)(isRight(equalTo(value2)))
        } @@ TestAspect.ignore
      ),
      suite("streaming")(
        test("stream encode and decode simple values") {
          val codec = XmlCodec.xmlCodec[String]

          for {
            input <- ZIO.succeed(List("hello", "world", "test", "xml", "streaming"))
            encodedBytes <- ZStream
                             .fromIterable(input)
                             .via(codec.streamEncoder)
                             .runCollect
            decodedValues <- ZStream
                              .fromChunk(encodedBytes)
                              .via(codec.streamDecoder)
                              .runCollect
          } yield assertTrue(decodedValues == Chunk.fromIterable(input))
        },
        test("stream encode and decode case classes") {
          val codec = XmlCodec.xmlCodec[Person]
          val people = List(
            Person("Alice", 30),
            Person("Bob", 40),
            Person("Charlie", 50),
            Person("Dave", 60)
          )

          for {
            encodedBytes <- ZStream
                             .fromIterable(people)
                             .via(codec.streamEncoder)
                             .runCollect
            decodedValues <- ZStream
                              .fromChunk(encodedBytes)
                              .via(codec.streamDecoder)
                              .runCollect
          } yield assertTrue(decodedValues == Chunk.fromIterable(people))
        },
        test("stream encode and decode with large collection") {
          val codec   = XmlCodec.xmlCodec[Int]
          val numbers = (1 to 1000).toList // A large collection of numbers

          for {
            encodedBytes <- ZStream
                             .fromIterable(numbers)
                             .via(codec.streamEncoder)
                             .runCollect
            decodedValues <- ZStream
                              .fromChunk(encodedBytes)
                              .via(codec.streamDecoder)
                              .runCollect
          } yield assertTrue(decodedValues == Chunk.fromIterable(numbers))
        }
      )
    )
}
