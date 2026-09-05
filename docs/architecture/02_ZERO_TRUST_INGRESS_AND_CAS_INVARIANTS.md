# STVN Architectural Specification: Zero-Trust Ingress Verification, Byte 4 Wire Governance, and Enum Subset CAS Invariants

**Document ID**: STVN-SPEC-REPO-02  
**Status**: Canonical Specification  
**Version**: 1.1.0-SNAPSHOT  
**Compliance**: Mandatory for all STVN ecosystem server and repository implementations.  

---

## 1. Architectural Purpose & Scope

This document specifies the ingress boundary security invariants and Content-Addressable Storage (CAS) non-collision guarantees for `stvnadore-repository` baseline `1.1.0-SNAPSHOT`.

The server enforces two core design invariants:
1. **Zero-Trust Binary Ingress Boundary:** Corrupted frames, tampered checksums, truncated buffers, and unauthorized strategy extensions are intercepted at the network edge and rejected prior to disk persistence.
2. **Enum Subset Cryptographic Isolation:** Nominal enum subsets (`#filterIncl`, `#filterExcl`) derive distinct SHA-256 CAS addresses, preventing storage overwrites and catalog collisions.

---

## 2. Zero-Trust Ingress Verification & Byte 4 Wire Governance

### 2.1 Wire Framing Specification
Every incoming `.stvn_bin` binary frame follows a strict binary layout:

```
+-------------------+---------------+-----------------------+---------------+--------------------+--------------------+
| Bytes 0-3 (4B)    | Byte 4 (1B)   | Bytes 5..N (0..Var B) | Byte N+1 (1B) | Bytes N+2.. (1..8B)| Trailing (0 or 4B) |
| "STVN" Magic      | Control Byte  | Schema Identity Data  | Flags (Offset)| Root Node Pointer  | [CRC-32C Trailer]  |
+-------------------+---------------+-----------------------+---------------+--------------------+--------------------+
```

### 2.2 Byte 4 Control Byte Bitfield Layout
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

### 2.3 Hardware-Accelerated CRC-32C Validation Algorithm
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

## 3. Enum Subset CAS Hashing Invariants & Non-Collision Guarantees

### 3.1 Mathematical Model & Non-Collision Property
In STVN Specification §8 and v1.1.0-SNAPSHOT, an enum subset derives a constrained variant view from a parent enum definition.

`StvnSchemaHasher` digests all subset attributes in strict sequential order:

```
Digest = SHA-256(
    "subsetName:" + subset.name() ||
    "subsetParent:" + subset.parentType() ||
    "subsetRoot:" + subset.rootEnum() ||
    "subsetFilterType:" + (subset.isInclusive() ? "incl" : "excl") ||
    ("subsetVariant:" + variant_i)*
)
```

Because the subset nominal name, parent reference, root reference, filter mode, and sorted allowed variants feed into the SHA-256 digest:
$$\text{CAS}(:\text{Status}) \ne \text{CAS}(:\text{ActiveStatus}) \ne \text{CAS}(:\text{NonDeleted})$$

### 3.2 Invariants Enforced
1. **Root-Subset Isolation:** A root enum and any derived subset produce divergent CAS addresses.
2. **Sibling Isolation:** Sibling subsets with different variant selections produce divergent CAS addresses.
3. **Transitive Lineage Isolation:** In transitive chains (`:ExecutionStatus` $\to$ `:WorkableStatus` $\to$ `:ActiveStatus` $\to$ `:TaskStatus`), each level reflects parent and root links, producing unique addresses at each tier.
4. **Storage Invariant:** Distinct addresses map to distinct files under the `2/62` filesystem CAS layout (`<prefix_2>/<suffix_62>.stvn_cas`), preventing file overwrite.

---

## 4. REST Ingress Boundary & Dual-Mode Routing

### 4.1 Endpoint Signatures

| Route Path | HTTP Method | Supported Content-Type | Pipeline Executed |
|:---|:---:|:---|:---|
| `/api/v1/schemas/{name}` | `POST` | `application/stvn` | Textual compilation via `StvnCompiler.analyze()`. |
| `/api/v1/schemas/{name}` | `POST` | `application/stvn-bin`<br>`application/octet-stream` | Zero-trust binary verification via `StvnBinaryDecoder.open()`. |
| `/api/v1/artifacts/binary/{name}` | `POST` | Any binary stream | Direct binary verification via `StvnBinaryDecoder.open()`. |
| `/api/v1/schemas/{name}/shapes/{sig}` | `GET` | N/A | Queries catalog metadata by nominal name and shape signature. |
| `/api/v1/schemas/cas/{hash}` | `GET` | N/A | Returns raw unpacked schema text by 64-character CAS hash. |

---

## 5. HTTP Error Mapping Taxonomy Matrix

The server maps boundary and compilation events to HTTP status codes:

| Status Code | Status Name | Trigger Condition | Source Exception | Response Body Schema |
|:---|:---|:---|:---|:---|
| `400` | Bad Request | Binary body is empty (`0 bytes`), magic bytes invalid, or CAS hash length `!= 64`. | `IllegalArgumentException` | `{"error": "Bad Request", "message": "<reason>"}` |
| `404` | Not Found | Schema shape signature or CAS file hash does not exist. | Query miss | Empty body |
| `409` | Conflict | Schema name exists with a different cryptographic hash. Mutations are prohibited. | `PublishResult.SchemaConflict` | `{"error": "Conflict", "message": "Schema name '...' already exists..."}` |
| `415` | Unsupported Media Type | Request `Content-Type` is null or not supported. | Media type guard | `{"error": "Unsupported Media Type", "message": "..."}` |
| `422` | Unprocessable Entity | AST compiler diagnostics, CRC-32C trailer mismatch, buffer under 9 bytes, or strategy sentinel `0x7` detected. | `CompileDiagnostic`, `MalformedPayloadException`, `UnsupportedEncodingStrategyException` | List of `CompileDiagnostic` records or `{"error": "<Category>", "message": "<reason>"}` |
| `200` | OK | Idempotent publication. Schema name and hash match existing registration. | `PublishResult.IdempotentCollision` | `SchemaMetadata` JSON object |
| `201` | Created | New schema verified, persisted to CAS, and cataloged. | `PublishResult.Success` | `SchemaMetadata` JSON object |
| `202` | Accepted | CAS write succeeded; catalog indexing deferred to background sweeper. | `PublishResult.IndexingDeferred` | `SchemaMetadata` JSON object |

---

## 6. Concurrency & Background Sweeper Invariants

1. **Virtual Thread Execution:** Every HTTP request executes on an unpinned Java 21 Virtual Thread. Calculations for `CRC32C` and `MessageDigest` allocate state on the stack, preventing thread pinning.
2. **Zero-Trust Disk Isolation:** A payload that fails CRC-32C validation or carries strategy `0x7` never touches the filesystem. The handler rejects the request before invoking `FileSystemCasStorage`.
3. **Relational Sweeper Quarantine:** If a physical file in the CAS root directory suffers bit corruption, the `RelationalProjectionSweeper` detects the hash mismatch and renames the file to `data/cas/.quarantine/<filename>.<timestamp>.HASH_MISMATCH.quarantine`.
