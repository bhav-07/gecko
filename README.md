# Gecko

Gecko is a persistent NoSQL key-value storage engine built in Java and Spring Boot. It uses a Log-Structured Merge (LSM) tree architecture designed for high write throughput and crash recovery.

![System Architecture](sys%20arch.png)

## Current Features

* **Thread-Safe Memtable**: Uses `ConcurrentSkipListMap` to handle concurrent reads and writes safely.
* **Write-Ahead Log (WAL)**: All writes are fsynced to a WAL segment before being acknowledged. The system provides zero data loss across crashes and restarts.
* **SSTables**: Immutable data files on disk that contain a sparse index and a bloom filter to optimize read paths and minimize physical disk seeks.
* **Crash Recovery**: Automatically replays un-flushed WAL segments on startup, flushes recovered data to a fresh SSTable, and cleans up old log segments.
* **Manifest**: Tracks all active SSTables across server restarts to prevent data amnesia.
* **Blind Deletes**: Deletes are implemented as fast tombstone writes rather than expensive read-then-modify operations.

*Note: Compaction is currently in development.*

## Tech Stack

* **Java 21**
* **Spring Boot 3.5.4**
* **Apache Commons Logging & SLF4J**
* **Google Guava** (Bloom Filters)

## Getting Started

### Prerequisites

* Java 21+
* Maven

### Installation & Running

Clone the repository and build the project:

```bash
git clone <repository-url>
cd gecko
./mvnw clean install
```

Start the server:

```bash
./mvnw spring-boot:run
```

The server runs on port 8080 by default. The storage engine will automatically create `./sst` and `./wal_data` directories in the project root to store its data.

## Configuration

Configuration is managed in `src/main/resources/application.properties`:

| Property | Description | Default |
|----------|-------------|---------|
| `wal.directory` | Directory for Write-Ahead Logs | `./wal_data` |
| `sst.directory` | Directory for SSTables and Manifest | `./sst` |
| `memtable.flush.threshold` | Size threshold in bytes to trigger a flush | `3000` |

## API Reference

### Store Value
**POST** `/api/memtable/put`

```json
{
  "key": "user:123",
  "value": "John Doe"
}
```

### Get Value
**GET** `/api/memtable/get/{key}`

```json
{
  "key": "user:123",
  "value": "John Doe",
  "timestamp": 1709234567890,
  "deleted": false
}
```

### Delete Value
**DELETE** `/api/memtable/delete/{key}`

Writes a tombstone record to mark the key as deleted.

### Bulk Insert
**POST** `/api/memtable/bulk`

```json
{
  "data": {
    "key1": "value1",
    "key2": "value2"
  }
}
```

### Get Memtable Stats
**GET** `/api/memtable/stats`

Returns the current active memtable statistics (size in bytes, entry count).

## Internal Structure

* `memtable/`: In-memory thread-safe skip list.
* `wal/`: Write-Ahead Log management and segment rotation.
* `sstable/`: Sorted String Table writers and read iterators, including bloom filters and sparse indexing.
* `manifest/`: Crash-safe ledger of active SSTable files.
* `diskstore/`: The coordinator layer managing the flow of data from memtable to flush tasks to disk.
