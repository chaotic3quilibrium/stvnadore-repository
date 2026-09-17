# STVN Architectural Specification: Zero-Trust Ingress Verification, Byte 4 Wire Governance, and Enum Subset CAS Invariants

**Document ID**: STVN-SPEC-REPO-02  
**Status**: Canonical Specification  
**Version**: 1.3.0-SNAPSHOT  
**Compliance**: Mandatory for all STVN ecosystem server and repository implementations.  

---

**Table of Contents**

<!-- TOC -->
* [STVN Architectural Specification: Zero-Trust Ingress Verification, Byte 4 Wire Governance, and Enum Subset CAS Invariants](#stvn-architectural-specification-zero-trust-ingress-verification-byte-4-wire-governance-and-enum-subset-cas-invariants)
  * [1. Architectural Purpose & Scope](#1-architectural-purpose--scope)
  * [2. Double-Gate Ingress Boundary for Flat Schemas (.stvn_inclf)](#2-double-gate-ingress-boundary-for-flat-schemas-stvn_inclf)
    * [2.1 Perimeter Capacity Bound](#21-perimeter-capacity-bound)
    * [2.2 Gate 1: Filename Hygiene](#22-gate-1-filename-hygiene)
    * [2.3 Headless Semantic Compilation & Zero-Tab Invariant](#23-headless-semantic-compilation--zero-tab-invariant)
    * [2.4 Gate 2: AST Structure Invariants](#24-gate-2-ast-structure-invariants)
    * [2.5 Canonical Flattening & Exported Interface Retention](#25-canonical-flattening--exported-interface-retention)
  * [3. Zero-Trust Ingress Verification & Byte 4 Wire Governance](#3-zero-trust-ingress-verification--byte-4-wire-governance)
    * [3.1 Wire Framing Specification](#31-wire-framing-specification)
    * [3.2 Byte 4 Control Byte Bitfield Layout](#32-byte-4-control-byte-bitfield-layout)
      * [1. Trailer Flag (Bit 7, Mask `0x80`): `HAS_TRAILER_CRC32C`](#1-trailer-flag-bit-7-mask-0x80-has_trailer_crc32c)
      * [2. Wire Strategy (Bits 6..4, Mask `0x70`): `BinaryEncodingStrategy`](#2-wire-strategy-bits-64-mask-0x70-binaryencodingstrategy)
      * [3. Schema Identity Strategy (Bits 3..0, Mask `0x0F`): `SchemaIdentityStrategy`](#3-schema-identity-strategy-bits-30-mask-0x0f-schemaidentitystrategy)
    * [3.3 Hardware-Accelerated CRC-32C Validation Algorithm](#33-hardware-accelerated-crc-32c-validation-algorithm)
  * [4. Enum Subset CAS Hashing Invariants & Non-Collision Guarantees](#4-enum-subset-cas-hashing-invariants--non-collision-guarantees)
    * [4.1 Mathematical Model & Non-Collision Property](#41-mathematical-model--non-collision-property)
    * [4.2 Invariants Enforced](#42-invariants-enforced)
  * [5. REST Ingress Boundary & Dual-Mode Routing](#5-rest-ingress-boundary--dual-mode-routing)
    * [5.1 Endpoint Signatures](#51-endpoint-signatures)
  * [6. HTTP Error Mapping Taxonomy Matrix](#6-http-error-mapping-taxonomy-matrix)
  * [7. Concurrency & Background Sweeper Invariants](#7-concurrency--background-sweeper-invariants)
<!-- TOC -->

---

## 1. Architectural Purpose & Scope

This document specifies the ingress boundary security invariants, wire governance, and Content-Addressable Storage (CAS) non-collision guarantees for `stvnadore-repository` baseline `1.3.0-SNAPSHOT`.

The server enforces four core perimeter invariants:
1. **Perimeter Payload Capacity Bound:** Enforces strict 16 MiB limits (`DEFAULT_UNBOUNDED_STRING_CAPACITY`) on both text schemas and binary streams before parsing.
2. **Double-Gate Textual Ingress Boundary:** Enforces filename hygiene (`.stvn_inclf`), semantic compilation with zero raw tabs (`ERR_TAB_CHARACTER_FORBIDDEN`), and AST structural invariants (strictly `:defs`, zero `:include`, zero `:type`, zero `:body`) prior to CAS commitment.
3. **Zero-Trust Binary Ingress Boundary:** Corrupted frames, tampered checksums, truncated buffers, and unauthorized strategy extensions are intercepted at the network edge and rejected prior to disk persistence.
4. **Enum Subset Cryptographic Isolation:** Nominal enum subsets (`#filterIncl`, `#filterExcl`) derive distinct SHA-256 CAS addresses from canonical flattened shape signatures, preventing storage overwrites and catalog collisions.

---

## 2. Double-Gate Ingress Boundary for Flat Schemas (.stvn_inclf)

### 2.1 Perimeter Capacity Bound
Every inbound schema payload (text or binary) must not exceed the centralized capacity bound:
* The engine asserts payload size $\le$ `StvnStringCapacityUtils.DEFAULT_UNBOUNDED_STRING_CAPACITY` (16 MiB = 16,777,216 bytes).
* Overflows reject immediately with HTTP 422 (`ERR_CAPACITY_OVERFLOW`).

### 2.2 Gate 1: Filename Hygiene
Every text schema submitted to `/api/v1/schemas/{name}` must carry the `.stvn_inclf` extension:
* The engine asserts `schemaName.endsWith(".stvn_inclf")`.
* Filenames violating this rule reject immediately with HTTP 422 (`ERR_MALFORMED_SCHEMA_IN_ENVELOPE`).

### 2.3 Headless Semantic Compilation & Zero-Tab Invariant
The engine compiles incoming source text before executing manual AST structure checks:
* The engine calls `StvnCompiler.compileToResult(sourceText, schemaName, StvnParserConfig.STRICT)`.
* Raw horizontal tab characters (`\t`, `U+0009`) fail compilation and produce diagnostic code `ERR_TAB_CHARACTER_FORBIDDEN`.
* This ordering prevents AST recovery from masking tab and syntax diagnostics.

### 2.4 Gate 2: AST Structure Invariants
Flat schema leaf modules stored in CAS must maintain strict structural cleanliness:
1. The root document context must contain strictly a `:defs` section.
2. Top-level `:type` sections are prohibited (`ERR_MALFORMED_SCHEMA_IN_ENVELOPE`).
3. Top-level `:body` sections are prohibited (`ERR_MALFORMED_SCHEMA_IN_ENVELOPE`).
4. Embedded `:include` directives are strictly prohibited in flat schemas (`ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT`).

### 2.5 Canonical Flattening & Exported Interface Retention
* **Canonical Flattening:** `StvnSchemaFlattener.flatten(Map.of(schemaName, sourceText), schemaName)` unpacks `:package` enclosures into Fully Qualified Nominal Identifiers (FQNIs), applies unary `#strip`, and canonicalizes definitions.
* **Exported Interface Retention:** Flat include schemas (`.stvn_inclf`) lack a root `:type` seed; therefore, `StvnCanonicalDefinitionsResolver` retains all exported interface definitions without dead-code elimination.
* **Deterministic CAS Addressing:** The 64-character CAS hash is computed by applying SHA-256 to the UTF-8 bytes of the flattened shape signature:
  $$\text{CAS Hash} = \text{SHA-256}(\text{shapeSignature.getBytes(StandardCharsets.UTF\_8)})$$

---

## 3. Zero-Trust Ingress Verification & Byte 4 Wire Governance

### 3.1 Wire Framing Specification
Every incoming `.stvn_bin` binary frame follows a strict binary layout:

```
+-------------------+---------------+-----------------------+---------------+--------------------+--------------------+
| Bytes 0-3 (4B)    | Byte 4 (1B)   | Bytes 5..N (0..Var B) | Byte N+1 (1B) | Bytes N+2.. (1..8B)| Trailing (0 or 4B) |
| "STVN" Magic      | Control Byte  | Schema Identity Data  | Flags (Offset)| Root Node Pointer  | [CRC-32C Trailer]  |
+-------------------+---------------+-----------------------+---------------+--------------------+--------------------+
```

### 3.2 Byte 4 Control Byte Bitfield Layout
Byte 4 governs stream decoding and integrity verification:

```
Bit 7           Bit 6   Bit 5   Bit 4   Bit 3   Bit 2   Bit 1   Bit 0
+---------------+-----------------------+-------------------------------+
| HAS_TRAILER   |   WIRE STRATEGY       |   SCHEMA IDENTITY STRATEGY    |
| (0x80)        |   (0x70)              |   (0x0F)                      |
+---------------+-----------------------+-------------------------------+
```

#### 1. Trailer Flag (Bit 7, Mask `0x80`): `HAS_TRAILER_CRC32C`
* When `1`, a 4-byte Little-Endian CRC-32C checksum is appended to the stream at `limit - 4`.
* The buffer must contain at least 9 bytes (5 header bytes + 4 trailer bytes).
* If the buffer contains fewer than 9 bytes, the parser raises `MalformedPayloadException`.

#### 2. Wire Strategy (Bits 6..4, Mask `0x70`): `BinaryEncodingStrategy`
* Code `0x0`: `ZERO_COPY_POST_ORDER`
* Codes `0x1` through `0x6`: Reserved
* Code `0x7`: Reserved sentinel for multi-byte header extension. Encountering `0x7` raises `UnsupportedEncodingStrategyException`.

#### 3. Schema Identity Strategy (Bits 3..0, Mask `0x0F`): `SchemaIdentityStrategy`
* Code `0x0`: `UniversalDefault`
* Code `0x1`: `ExplicitSha256`
* Code `0x2`: `SelfDescribingSchema`

### 3.3 Hardware-Accelerated CRC-32C Validation Algorithm
The server executes the integrity verification algorithm within `SchemaPublishHandler`:

```java
ByteBuffer buffer = ByteBuffer.wrap(binaryBytes);
if (buffer.remaining() < 5) {
    throw new IllegalArgumentException("Buffer smaller than STVN 5-byte header");
}

// 1. Magic byte verification
int magic = buffer.getInt(0);
if (magic != 0x5354564E) { // ASCII "STVN"
    throw new IllegalArgumentException("Invalid STVN magic bytes");
}

// 2. Inspect Byte 4 Control Byte
int controlByte = buffer.get(4) & 0xFF;
boolean hasTrailer = (controlByte & 0x80) != 0;
int strategyCode = (controlByte & 0x70) >>> 4;

if (strategyCode == 0x7) {
    throw new UnsupportedEncodingStrategyException("Strategy 0x7 is reserved for multi-byte header extension");
}

if (hasTrailer) {
    if (buffer.remaining() < 9) {
        throw new MalformedPayloadException("Buffer too small for STVN binary with CRC-32C trailer: requires at least 9 bytes");
    }
    int limit = buffer.limit();
    CRC32C crc = new CRC32C();
    buffer.position(0);
    buffer.limit(limit - 4);
    crc.update(buffer);
    
    buffer.limit(limit);
    buffer.position(limit - 4);
    int expectedCrc = buffer.order(ByteOrder.LITTLE_ENDIAN).getInt();
    
    if ((int) crc.getValue() != expectedCrc) {
        throw new MalformedPayloadException("CRC-32C trailer mismatch: expected 0x" 
            + Integer.toHexString(expectedCrc) + ", computed 0x" + Long.toHexString(crc.getValue()));
    }
}
```

---

## 4. Enum Subset CAS Hashing Invariants & Non-Collision Guarantees

### 4.1 Mathematical Model & Non-Collision Property
In STVN Specification §8 and v1.2.0, an enum subset derives a constrained variant view from a parent enum definition.

`StvnSchemaFlattener` flattens definitions into canonical AST shape signatures, and the CAS address is derived via:
$$\text{CAS Hash} = \text{SHA-256}(\text{shapeSignature.getBytes(StandardCharsets.UTF\_8)})$$

Because the subset nominal name, parent reference, filter mode, and variant list feed into the canonical shape signature:
$$\text{CAS}(:\text{Status}) \ne \text{CAS}(:\text{ActiveStatus}) \ne \text{CAS}(:\text{NonDeleted})$$

### 4.2 Invariants Enforced
1. **Root-Subset Isolation:** A root enum and any derived subset produce divergent CAS addresses.
2. **Sibling Isolation:** Sibling subsets with different variant selections produce divergent CAS addresses.
3. **Transitive Lineage Isolation:** In transitive chains (`:ExecutionStatus` $\to$ `:WorkableStatus` $\to$ `:ActiveStatus` $\to$ `:TaskStatus`), each tier produces a unique canonical shape and distinct CAS address.
4. **Storage Invariant:** Distinct addresses map to distinct files under the `2/62` filesystem CAS layout (`<prefix_2>/<suffix_62>.stvn_cas`), preventing file overwrite.

---

## 5. REST Ingress Boundary & Dual-Mode Routing

### 5.1 Endpoint Signatures

| Route Path                            | HTTP Method | Supported Content-Type                               | Pipeline Executed                                              |
|:--------------------------------------|:-----------:|:-----------------------------------------------------|:---------------------------------------------------------------|
| `/api/v1/schemas/{name}`              |   `POST`    | `application/stvn`                                   | Double-Gate ingress verification, headless compile & flatten.  |
| `/api/v1/schemas/{name}`              |   `POST`    | `application/stvn-bin`<br>`application/octet-stream` | Zero-trust binary verification via `StvnBinaryDecoder.open()`. |
| `/api/v1/artifacts/binary/{name}`     |   `POST`    | Any binary stream                                    | Direct binary verification via `StvnBinaryDecoder.open()`.     |
| `/api/v1/schemas/{name}/shapes/{sig}` |    `GET`    | N/A                                                  | Queries catalog metadata by nominal name and shape signature.  |
| `/api/v1/schemas/cas/{hash}`          |    `GET`    | N/A                                                  | Returns raw unpacked schema text by 64-character CAS hash.     |

---

## 6. HTTP Error Mapping Taxonomy Matrix

The server maps boundary and compilation events to HTTP status codes:

| Status Code | Status Name            | Trigger Condition                                                                                              | Source Exception                                                                         | Response Body Schema                                                                    |
|:------------|:-----------------------|:---------------------------------------------------------------------------------------------------------------|:-----------------------------------------------------------------------------------------|:----------------------------------------------------------------------------------------|
| `400`       | Bad Request            | Binary body is empty (`0 bytes`), magic bytes invalid, or CAS hash length `!= 64`.                             | `IllegalArgumentException`                                                               | `{"error": "Bad Request", "message": "<reason>"}`                                       |
| `404`       | Not Found              | Schema shape signature or CAS file hash does not exist.                                                        | Query miss                                                                               | Empty body                                                                              |
| `409`       | Conflict               | Schema name exists with a different cryptographic hash. Mutations are prohibited.                              | `PublishResult.SchemaConflict`                                                           | `{"error": "Conflict", "message": "Schema name '...' already exists..."}`               |
| `415`       | Unsupported Media Type | Request `Content-Type` is null or not supported.                                                               | Media type guard                                                                         | `{"error": "Unsupported Media Type", "message": "..."}`                                 |
| `422`       | Unprocessable Entity   | Payload > 16 MiB (`ERR_CAPACITY_OVERFLOW`), Gate 1/2 violation, compiler error (or raw tabs), CRC-32C mismatch, buffer < 9 bytes, or strategy sentinel `0x7`. | `CompileDiagnostic`, `MalformedPayloadException`, `UnsupportedEncodingStrategyException` | List of `CompileDiagnostic` records or `{"error": "<Category>", "message": "<reason>"}` |
| `200`       | OK                     | Idempotent publication. Schema name and hash match existing registration.                                      | `PublishResult.IdempotentCollision`                                                      | `SchemaMetadata` JSON object                                                            |
| `201`       | Created                | New schema verified, persisted to CAS, and cataloged.                                                          | `PublishResult.Success`                                                                  | `SchemaMetadata` JSON object                                                            |
| `202`       | Accepted               | CAS write succeeded; catalog indexing deferred to background sweeper.                                          | `PublishResult.IndexingDeferred`                                                         | `SchemaMetadata` JSON object                                                            |

---

## 7. Concurrency & Background Sweeper Invariants

1. **Virtual Thread Execution:** Every HTTP request executes on an unpinned Java 21 Virtual Thread. Calculations for `CRC32C` and `MessageDigest` allocate state on the stack, preventing thread pinning.
2. **Zero-Trust Disk Isolation:** A payload that exceeds 16 MiB (`ERR_CAPACITY_OVERFLOW`), fails CRC-32C validation, carries strategy `0x7`, or violates Gate 1 or Gate 2 never touches the filesystem. The handler rejects the request before invoking `FileSystemCasStorage`.
3. **Relational Sweeper Quarantine:** If a physical file in the CAS directory suffers bit corruption, invalid filenames, illegal includes, or forbidden raw tabs, the `RelationalProjectionSweeper` atomically relocates it to:
   `data/cas/.quarantine/<filename>.<timestamp>.<REASON>.quarantine`
   The sweeper executes `StvnCompiler.compileToResult(STRICT)` before inspecting AST structure. This guarantees that raw horizontal tabs and upstream compilation failures quarantine under `INVALID_INNER_AST`, while illegal includes quarantine under `ILLEGAL_INCLUDES_IN_FLAT_SCHEMA`.
   Supported reasons include: `EMPTY_PAYLOAD`, `CORRUPT_ENVELOPE`, `INVALID_FILENAME_EXTENSION`, `MALFORMED_INNER_STRUCTURE`, `ILLEGAL_INCLUDES_IN_FLAT_SCHEMA`, `INVALID_INNER_AST`, `FLATTENING_ERROR`, and `HASH_MISMATCH`.
