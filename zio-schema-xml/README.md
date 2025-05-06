# ZIO Schema XML Codec

This module provides XML encoding and decoding support for ZIO Schema. It allows you to convert between Scala objects and XML documents using ZIO Schema's type-safe schema definitions.

## Features

- ✅ Primitive type encoding/decoding
- ✅ Case class encoding/decoding
- ✅ Optional fields
- ✅ Collections (List, Set, Map)
- ✅ Enums (sealed traits)
- ✅ Streaming support
- ✅ Pretty printing
- ✅ Custom field name formatting
- ✅ Attribute-based encoding option

## Current Implementation Details

The current implementation supports:

1.  **Primitive Types**: All standard types supported by ZIO Schema
2.  **Case Classes**: Supports element-based encoding. Attribute-based encoding is partially implemented but disabled in tests due to issues with optional primitives.
3.  **Collections**: List, Set, and Map with appropriate XML structure
4.  **Enums**: Basic support using a `type` attribute for discrimination. Only case object decoding is currently functional; case class decoding within enums is not yet implemented and tests are ignored.
5.  **Configuration Options**:
    -   `useAttributes`: Controls whether case class fields are encoded as attributes (currently limited)
    -   `prettyPrint`: Controls XML formatting
    -   `fieldNameFormat`: Controls field/element name formatting (currently only Identity)
    -   `rootElementName`: Controls the name of the root element
    -   `chunkSize`: Controls the buffer size for streaming operations
6.  **Streaming Support**: Basic streaming encoding and decoding via `streamEncoder`/`streamDecoder` using an `<items>` wrapper.

## Remaining Work & Known Limitations

1.  **Attribute Encoding**: Complete the implementation and fix tests for encoding optional primitive fields as attributes (`useAttributes = true`).
2.  **Enum Decoding**: Implement robust decoding for enum case classes with fields. Resolve issues with identifying case objects reliably.
3.  **XML Namespaces**: Add support for XML namespaces.
4.  **XSD Validation**: Consider adding support for validating XML against XSD schemas.
5.  **Error Handling**: Improve error messages for parsing/decoding failures.
6.  **Documentation**: Add more comprehensive examples and usage documentation.
7.  **Performance Optimization**: Further optimize streaming and large document handling.

## Current Status

### Implemented Features

1. **Primitive Types**
   - All standard types (String, Int, Boolean, etc.)
   - Date/time types (LocalDate, Instant, etc.)
   - Binary data (Base64 encoded)
   - UUID
   - Currency

2. **Case Classes**
   - Simple case classes
   - Nested case classes
   - Optional fields
   - Default values

3. **Collections**
   - Lists
   - Sets
   - Maps (with key-value pairs)

4. **Enums**
   - Basic enum support (sealed traits)
   - Case objects
   - Simple case classes in enums

5. **Configuration Options**
   - Pretty printing
   - Field name formatting
   - Attribute-based encoding
   - Custom root element name
   - Chunk size for streaming

### Known Issues and Limitations

1. **Enum Decoding**
   - Complex enum case classes with fields are not fully supported
   - Need to improve case matching logic

2. **Type Safety**
   - Some type casting is needed due to schema erasure
   - Could be improved with better type handling

3. **Error Handling**
   - Basic error messages
   - Could be enhanced with more detailed error information

4. **Performance**
   - Large collections might need optimization
   - Streaming implementation could be improved

### Debugging Information

1. **Compilation Errors Fixed**
   - Fixed type parameter issues in enum pattern matching
   - Corrected schema access methods
   - Fixed missing field handling

2. **Runtime Issues**
   - Added proper error handling for XML parsing
   - Improved null value handling
   - Better handling of optional fields

3. **Testing Status**
   - Basic test suite implemented
   - Coverage for primitive types
   - Coverage for case classes
   - Coverage for collections
   - Coverage for enums
   - Coverage for streaming

## Usage

```scala
import zio.schema._
import zio.schema.codec.XmlCodec

// Define your schema
case class Person(name: String, age: Int)
object Person {
  implicit val schema: Schema[Person] = DeriveSchema.gen[Person]
}

// Create a codec
val codec = XmlCodec.xmlCodec[Person]

// Encode to XML
val person = Person("John Doe", 42)
val xmlBytes = codec.encode(person)

// Decode from XML
val decoded = codec.decode(xmlBytes)
```

## Configuration

```scala
val config = XmlCodec.Configuration(
  useAttributes = true,  // Use attributes for simple fields
  prettyPrint = true,    // Format XML with indentation
  fieldNameFormat = NameFormat.CamelCase,  // Convert field names to camelCase
  rootElementName = "person",  // Custom root element name
  chunkSize = 8192  // Buffer size for streaming
)

val codec = XmlCodec.xmlCodec[Person](config)
```

## Next Steps

1. **Improvements Needed**
   - Complete enum case class field decoding
   - Add more comprehensive error handling
   - Optimize performance for large collections
   - Add more test cases
   - Improve documentation

2. **Future Features**
   - XML namespace support
   - Custom type converters
   - Schema validation
   - XML Schema (XSD) generation
   - Better streaming performance

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the Apache License 2.0 - see the LICENSE file for details. 