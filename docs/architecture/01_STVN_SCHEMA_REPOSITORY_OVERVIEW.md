# STVN Architectural Specification: Schema Repository Server Overview

**Document ID**: STVN-SPEC-REPO-01  
**Status**: Canonical Specification  
**Version**: 1.0.0  
**Compliance**: Mandatory for all STVN ecosystem server implementations.

---

## 1. Purpose & Core Responsibilities

The STVN Schema Repository is a high-throughput, non-blocking Content-Addressable Storage (CAS) and Relational Schema Catalog service. It provides:

1. **Content-Addressable Storage (CAS)**: Cryptographically deterministic, immutable storage for .stvn_cas schema envelopes sharded across the filesystem using a 2/62 prefix/suffix partitioning layout.
2. **Relational Version Catalog**: Fast query indexing mapping nominal schema names and flattened structural shape signatures to cryptographic CAS content hashes.
3. **Strict Immutability Invariant**: Schema mutations are strictly prohibited. Publishing an existing schema name with a different cryptographic hash produces an HTTP 409 Conflict.
4. **Self-Healing Background Projection Sweeper**: A background virtual thread asynchronously scans the physical CAS directory, validates AST hashes, reconciles missing index entries, and quarantines corrupted files.

`mermaid
flowchart TD
    Client["Client / IDE Plugin"] -->|POST /api/v1/schemas/{name}| Handler["SchemaPublishHandler\n(Virtual Threads)"]
    Handler -->|1. Parse & Validate| Core["STVN Core SDK\n(StvnCompiler)"]
    Handler -->|2. Compute SHA-256| Hasher["StvnSchemaHasher"]
    Handler -->|3. Write Envelope| CAS["FileSystemCasStorage\n(2/62 Sharding: aa/bb...stvn_cas)"]
    Handler -->|4. Index Metadata| DB[(PostgreSQL / H2\nversion_catalog)]
    
    subgraph Background ["Background Virtual Thread"]
        Sweeper["RelationalProjectionSweeper"] -->|Scan Files| CAS
        Sweeper -->|Recompute Hashes| Hasher
        Sweeper -->|Reconcile| DB
        Sweeper -->|Invalid AST / Hash Mismatch| Quarantine[".quarantine/"]
    end
`

---

## 2. Content-Addressable Storage (CAS) Specification

### 2/62 Filesystem Sharding Layout
All schemas are written to disk using their 64-character lowercase hexadecimal SHA-256 AST hash:
* **Directory Prefix (2 hex characters)**: data/cas/<hash[0..2]>/
* **Filename (62 hex characters + extension)**: <hash[2..64]>.stvn_cas
* **Example Path**: data/cas/ba/7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad.stvn_cas

### CAS Envelope Document Format
Raw schema sources are wrapped in a canonical STVN tuple envelope:
`stvn
{
  :defs {
    :SchemaName :String
    :StvnInclf {#preserveIndent #T} :String
  }
  :type :Tuple(:SchemaName :StvnInclf)
  :body (
    "UserProfile"
    \"\"\"->[SHA256-ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad]
    :defs {
      :UserId :Uint64
      :UserName :StringNonEmpty
    }
    [SHA256-ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad]\"\"\"
  )
}
`

---

## 3. Relational Schema Catalog (PostgreSQL & H2)

### Table: ersion_catalog
| Column | Type | Constraints | Description |
|:---|:---|:---|:---|
| schema_name | VARCHAR(256) | PRIMARY KEY | Nominal schema identifier |
| shape_signature | TEXT | NOT NULL | Flattened AST structural shape signature |
| cas_hash | CHAR(64) | NOT NULL, UNIQUE | Cryptographic SHA-256 CAS address |
| created_at | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | Initial registration timestamp |

### Table: schema_source_audit
| Column | Type | Constraints | Description |
|:---|:---|:---|:---|
| id | BIGSERIAL | PRIMARY KEY | Monotonic audit sequence number |
| schema_name | VARCHAR(256) | NOT NULL | Schema name |
| cas_hash | CHAR(64) | NOT NULL | Content hash |
| source_text | TEXT | NOT NULL | Author-submitted source code |
| published_at | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | Publication timestamp |

---

## 4. REST API Specification

### 1. Publish Schema
* **Method**: POST
* **Path**: /api/v1/schemas/{name}
* **Header**: Content-Type: application/stvn
* **Body**: Raw STVN schema source code.
* **Status Codes**:
  * 201 Created: Schema successfully published and indexed.
  * 200 OK: Idempotent publication (exact name and hash match).
  * 202 Accepted: CAS write succeeded; relational indexing queued for background sweeper.
  * 409 Conflict: Mutation rejected (name exists with different hash).
  * 415 Unsupported Media Type: Request Content-Type is not pplication/stvn.
  * 422 Unprocessable Entity: STVN compiler diagnostics detected syntax/semantic errors.

### 2. Lookup Schema by Shape Signature
* **Method**: GET
* **Path**: /api/v1/schemas/{name}/shapes/{signature}
* **Status Codes**:
  * 200 OK: Returns JSON {"schemaName": "...", "shapeSignature": "...", "casHash": "..."}.
  * 404 Not Found: No schema registered with given name and shape.

### 3. Retrieve Raw CAS Schema Payload
* **Method**: GET
* **Path**: /api/v1/schemas/cas/{hash}
* **Header**: Accept: application/stvn
* **Status Codes**:
  * 200 OK: Returns raw unwrapped schema source code (Content-Type: application/stvn).
  * 400 Bad Request: Hash length is not 64 hexadecimal characters.
  * 404 Not Found: CAS file not found on disk.

---

## 5. Background Projection Sweeper & Quarantine

The RelationalProjectionSweeper executes every 60 seconds on a Java 21 Virtual Thread:
1. Deep-walks the data/cas/ directory, extracting all .stvn_cas filenames.
2. Checks if each CAS hash exists in ersion_catalog.
3. If missing, compiles the envelope text, recomputes the SHA-256 AST hash via StvnSchemaHasher, derives the shape signature via StvnSchemaFlattener, and inserts the missing index entry.
4. If an envelope is malformed, unparseable, or its computed AST hash does not match the filename hash, the file is atomically relocated into data/cas/.quarantine/<filename>.<timestamp>.<REASON>.quarantine.