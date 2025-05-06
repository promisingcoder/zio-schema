# XML Codec Implementation Plan for ZIO Schema

## Current State

ZIO Schema is a library for modeling the schema of data structures as first-class values. It provides a way to describe the structure of data types and use these descriptions to automatically handle serialization, deserialization, diffing, patching, and migration.

Currently, ZIO Schema supports several codecs:
- JSON (zio-schema-json)
- Protobuf (zio-schema-protobuf)
- Avro (zio-schema-avro)
- BSON (zio-schema-bson)
- Thrift (zio-schema-thrift)
- MessagePack (zio-schema-msg-pack)

However, it lacks an XML codec, which has been requested in issue #754.

## Core Codec Architecture

ZIO Schema defines these key interfaces for codecs:

1. `Codec[Whole, Element, A]` - The base trait that combines encoding and decoding capabilities
2. `Encoder[Whole, Element, -A]` - Handles encoding values of type A to Whole or streams of Element
3. `Decoder[Whole, Element, +A]` - Handles decoding Whole or streams of Element to values of type A

For binary codecs specifically:
- `BinaryCodec[A]` extends `Codec[Chunk[Byte], Byte, A]`

Each codec follows this pattern and provides functionality to convert between a schema-described type and its serialized form.

## Implementation Requirements for XML Codec

To implement an XML codec for ZIO Schema, we need to create a new module called `zio-schema-xml` with the following components:

### 1. Project Structure

```
zio-schema-xml/
├── shared/
│   └── src/
│       ├── main/
│       │   └── scala/
│       │       └── zio/
│       │           └── schema/
│       │               └── codec/
│       │                   ├── XmlCodec.scala
│       │                   └── package.scala
│       └── test/
│           └── scala/
│               └── zio/
│                   └── schema/
│                       └── codec/
│                           └── XmlCodecSpec.scala
```

### 2. Dependencies

The module should have minimal dependencies. Options for XML parsing/generation:
- `scala-xml` for XML handling (standard library in Scala 2.x, separate dependency in Scala 3.x)
- Or potentially another XML library if needed

### 3. Core Implementation Components

1. **XmlCodec Class**:
   - Similar to other codecs like JsonCodec or ProtobufCodec
   - Should handle all Schema types (primitives, records, enums, sequences, etc.)
   - Should support both encoding and decoding
   - Should handle streaming of XML data

2. **Configuration Options**:
   - XML-specific configuration such as:
     - Handling of attributes vs. elements
     - Namespaces support
     - Pretty printing options
     - Custom element/attribute naming conventions

3. **Schema-to-XML Mapping Rules**:
   - Rules for mapping Scala types to XML structures
   - Handling of complex types (case classes, sealed traits)
   - Support for collections (lists, sets, maps)
   - Special handling for options and nullable values

### 4. Implementation Challenges

1. **XML Representation Choices**:
   - Representing case classes as elements with nested elements or attributes
   - Handling collections (whether to use repeated elements or specialized collection elements)
   - Dealing with namespaces

2. **Streaming Support**:
   - Implementing efficient streaming for large XML documents

3. **Error Handling**:
   - Providing detailed error messages for XML parsing failures

### 5. Build Configuration

Update `build.sbt` to include the new module:

```scala
lazy val zioSchemaXml = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("zio-schema-xml"))
  .dependsOn(zioSchema, zioSchema % "test->test")
  .settings(stdSettings("zio-schema-xml"))
  .settings(crossProjectSettings)
  .settings(buildInfoSettings("zio.schema"))
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio" % zioVersion,
      "org.scala-lang.modules" %%% "scala-xml" % scalaXmlVersion
    )
  )
  // Platform-specific settings would go here
```

## Implementation Steps

1. **Set up the project structure** - Create the necessary directories and files
2. **Implement the core XmlCodec** - Start with basic functionality for primitive types
3. **Add support for complex types** - Implement encoding/decoding for records, enums, etc.
4. **Add configuration options** - Implement customization features
5. **Add streaming support** - Implement efficient streaming encoding/decoding
6. **Write tests** - Create comprehensive test cases
7. **Documentation** - Add documentation and examples

## References

- Look at existing codecs (especially JsonCodec) for implementation patterns
- Consider XML-specific features that might need special handling (attributes, namespaces, etc.)
- Review XML libraries' capabilities to choose the most suitable one

## Dependencies

- zio-schema (core)
- scala-xml (or alternative XML library)
- zio-test (for testing)

## Implementation Progress

### 2024-06-28: Initial Setup

- [x] Created directory structure for the module
- [x] Added basic scala files:
  - Created `XmlCodec.scala` with skeleton implementation:
    - Defined `Configuration` class with options for XML formatting
    - Implemented basic BinaryCodec interface with stub methods
    - Added encoder/decoder class outlines
  - Created `package.scala` with extension methods for Schema
- [x] Created test specification class `XmlCodecSpec.scala` with basic structure
- [x] Updated build.sbt:
  - Added scala-xml dependency (version 2.2.0)
  - Added zio-schema-xml module definition
  - Modified test commands to include the new module

### 2024-06-28: Primitive Type Implementation

- [x] Implemented primitive type encoding in XmlEncoder:
  - Added support for all standard types (String, Boolean, numeric types, etc.)
  - Added special handling for date/time types with appropriate format
  - Added base64 encoding for binary data
- [x] Implemented primitive type decoding in XmlDecoder:
  - Added parsing for all standard types
  - Added error handling for parsing failures
- [x] Added tests for primitive types:
  - Tests for String, Int, Boolean, LocalDate, UUID, Option[String]
  - Verified round-trip encoding/decoding works correctly

### 2024-06-28: Complex Type Implementation

- [x] Implemented case class (record) encoding/decoding:
  - Added support for nested fields
  - Added option to encode fields as attributes or elements
  - Added builder-based decoding with proper error handling for missing fields
- [x] Implemented enum (sealed trait) encoding/decoding:
  - Added support for case objects and case classes in sealed traits
  - Used type attribute to track enum case type
- [x] Implemented collection type encoding/decoding:
  - Added support for List/Seq (using repeated "item" elements)
  - Added support for Set (similar to List)
  - Added support for Map (using "entry" elements with "key" and "value" children)
- [x] Added tests for complex types:
  - Tests for simple and nested case classes
  - Tests for attribute-based encoding
  - Tests for collections (List, Set, Map)
  - Tests for enums with and without fields

### 2024-06-28: Streaming Support Implementation

- [x] Enhanced the Configuration class with streaming options:
  - Added chunkSize parameter for buffer control
- [x] Implemented improved stream encoder:
  - Added parallel processing for better performance with large collections
  - Added newline separators between elements for better readability
  - Used ZIO effects for safer error handling
- [x] Implemented improved stream decoder:
  - Added better handling of byte streams
  - Improved error handling during streaming operations
- [x] Added tests for streaming functionality:
  - Tests for streaming simple values (strings)
  - Tests for streaming case classes
  - Tests for streaming large collections (1000 elements)

### 2024-06-29: Fixed Compatibility Issues

- [x] Fixed Record (case class) decoding implementation:
  - Replaced placeholder implementation with proper decoding logic
  - Added handling for missing fields with default values
  - Used Schema.construct to create record instances
  - Added proper error handling for required fields
- [x] Fixed Enum decoding implementation:
  - Added support for enum type attribute to identify enum cases
  - Implemented handling for case objects (empty elements with type attribute)
  - Added basic support for case classes in enums
  - Added error handling for missing or unknown enum cases
- [x] Fixed XML element creation and parsing compatibility issues:
  - Made proper use of Scala XML library's Elem and Node types
  - Fixed attribute handling for enum types
  - Improved error messages for XML parsing failures

### Debugging Notes

### Issues Encountered

1. **XML Element Labels**: Initially, elements weren't getting the correct labels. Fixed by using the `copy(label = elementName)` pattern to set the correct element names.

2. **Attribute Handling**: When implementing the attribute-based encoding, had to carefully handle the MetaData construction to properly build XML attributes.

3. **Enum Handling**: Needed to find the right approach for representing sealed traits in XML. Settled on using a "type" attribute to indicate which case is being encoded, similar to how JSON discriminators work.

4. **Streaming Performance**: The initial implementation of streaming was inefficient for large collections. Fixed by:
   - Processing elements in parallel using ZIO's concurrent capabilities
   - Adding proper chunking and separation between elements
   - Using ZIO effects for safer error handling and resource management

5. **SchemaBasedChunkBuilder Issues**: Initially attempted to use SchemaBasedChunkBuilder for record decoding, but ran into compatibility issues with current ZIO Schema. Switched to using Schema.construct with decoded field values instead.

6. **Enum Case Identification**: Initially struggled with identifying enum cases properly. Fixed by using the type attribute to identify cases and matching on case names.

7. **Compatibility with Schema API**: Found differences between current Schema API and assumptions in our initial implementation. Updated the code to match the current API patterns as seen in other codecs like Avro and JSON.

## Current Implementation Details

The current implementation supports:

1. **Primitive Types**: All standard types supported by ZIO Schema
2. **Case Classes**: Supports both element-based and attribute-based encoding
3. **Collections**: List, Set, and Map with appropriate XML structure
4. **Enums**: Sealed traits with case objects and case classes
5. **Configuration Options**:
   - `useAttributes`: Controls whether case class fields are encoded as attributes
   - `prettyPrint`: Controls XML formatting
   - `fieldNameFormat`: Controls field/element name formatting
   - `rootElementName`: Controls the name of the root element
   - `chunkSize`: Controls the buffer size for streaming operations
6. **Streaming Support**: Efficient encoding and decoding of streams of data

## Remaining Work

1. **XML Namespaces**: Add support for XML namespaces for more complex XML documents
2. **XSD Validation**: Add support for validating XML against XSD schemas
3. **Documentation**: Comprehensive documentation and examples for users
4. **Error Handling**: More detailed and helpful error messages for failed parse operations
5. **Enhanced Enum Support**: Better support for enum cases with fields
6. **Performance Optimization**: Optimize encoding and decoding operations for large documents
7. **Attribute vs Element Mode**: Complete support for attribute-based encoding/decoding for records 