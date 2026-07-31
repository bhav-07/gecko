# Gecko

Gecko is a persistent, ACID-compliant key-value storage engine built in Java and Spring Boot. It uses a Log-Structured Merge (LSM) tree architecture, the same architecture that powers Cassandra, RocksDB, and ScyllaDB, designed for high write throughput and crash recovery.

Benchmarked at **80,000+ writes/second** on consumer hardware under concurrent load.

![System Architecture](sys%20arch.png)

---

## Features

### Core Storage

- **Thread-Safe Memtable**: An in-memory `ConcurrentSkipListMap` that keeps keys sorted and handles concurrent reads and writes without coarse-grained locking.
- **Write-Ahead Log (WAL)**: Every write is appended to a WAL segment before the operation is acknowledged. On startup, any un-flushed WAL entries are replayed automatically to guarantee zero data loss across crashes.
- **SSTables**: Immutable, sorted data files written to disk during a memtable flush. Each SSTable is accompanied by a Bloom Filter to skip unnecessary disk reads and a Sparse Index for binary search without scanning the full file.
- **Manifest**: A crash-safe ledger that tracks which SSTables are currently active. Survives restarts and prevents the engine from loading stale or partially-written files.
- **Blind Deletes**: Delete operations write a tombstone record directly rather than performing a read-then-modify, keeping the delete path as fast as any write.
- **Compaction**: A background compaction engine merges overlapping SSTables using K-way merge with a priority queue, automatically discarding stale and deleted records. Implemented behind a `CompactionStrategy` interface, with `MergeAllStrategy` as the current implementation.

### Concurrency

- **WAL Rotation Safety**: A `ReentrantReadWriteLock` ensures that concurrent write threads can append to the WAL freely, while the flush thread acquires an exclusive write lock only for the brief moment it swaps the active WAL pointer. This eliminates the race condition where a write thread holds a reference to a WAL file the flush thread has already closed.
- **Synchronized WAL Appends**: WAL writes are `synchronized` to prevent two concurrent requests from interleaving their bytes into the same log entry, which would corrupt the WAL on recovery.
- **Compaction Snapshot Isolation**: Compaction tasks take a snapshot of the SSTable list inside the executor thread, preventing stale snapshots from causing incorrect merges when multiple compaction tasks queue up.

### Durability & Performance

- **Asynchronous WAL Sync**: Rather than forcing a physical disk flush (`fsync`) on every single write, a background scheduled thread syncs the WAL to disk every `wal.sync.interval.ms` milliseconds (default: 100ms). This is the same strategy used by Redis and MongoDB. In the event of a complete power failure, at most the last 100ms of writes are at risk.
- **LRU Read Cache**: A configurable LRU cache sits in front of the SSTable read path to serve hot keys from memory and avoid repeated disk seeks.
- **Checksum Validation**: Every record carries a CRC32 checksum that is verified on read. Corrupted records are caught and surfaced rather than silently returned.
- **Observability**: Prometheus metrics and Grafana dashboards are integrated via Spring Actuator, exposing WAL append latency, memtable size, and flush frequency.

### ACID Compliance

- **Atomicity**: A write is logged to the WAL before it touches the Memtable. If the process dies mid-write, the incomplete entry is detected and discarded during WAL replay on restart.
- **Consistency**: The Manifest is the commit point for every flush and compaction. Old SSTables are never removed until the new file is safely recorded in the Manifest first.
- **Isolation**: Concurrent writes operate on a `ConcurrentSkipListMap` and go through synchronized WAL appends, so no two requests can see each other's partial state.
- **Durability**: Writes are flushed to the OS page cache immediately, and a background thread calls `fsync` every 100ms to commit them to physical disk. The WAL is also synced one final time on graceful shutdown.

---

## Tech Stack

| Component     | Technology                      |
| ------------- | ------------------------------- |
| Language      | Java 21                         |
| Framework     | Spring Boot 3.5.4               |
| Bloom Filters | Google Guava                    |
| Logging       | Apache Commons Logging & SLF4J  |
| Metrics       | Micrometer, Prometheus, Grafana |

---

## Getting Started

### Prerequisites

- Java 21+
- Maven

### Installation & Running

```bash
git clone <repository-url>
cd gecko
./mvnw clean install
./mvnw spring-boot:run
```

The server starts on port `8080`. The engine automatically creates `./sst` and `./wal_data` directories in the project root.

---

## Configuration

All knobs are managed in `src/main/resources/application.properties`:

| Property                   | Description                                                     | Default           |
| -------------------------- | --------------------------------------------------------------- | ----------------- |
| `wal.directory`            | Directory for WAL segments                                      | `./wal_data`      |
| `sst.directory`            | Directory for SSTables and Manifest                             | `./sst`           |
| `memtable.flsuh.threshold` | Memtable size in bytes before a flush is triggered              | `67108864` (64MB) |
| `wal.sync.interval.ms`     | How often the background thread fsyncs the WAL to physical disk | `100`             |
| `read.cache.enabled`       | Enable or disable the LRU read cache                            | `true`            |
| `read.cache.capacity`      | Maximum number of entries the LRU cache holds                   | `1000`            |
| `compaction.threshold`     | Number of SSTables that triggers a compaction                   | `8`               |

---

## API Reference

### Store a value

**POST** `/api/memtable/put`

```json
{
  "key": "user:123",
  "value": "John Doe"
}
```

### Get a value

**GET** `/api/memtable/get/{key}`

```json
{
  "key": "user:123",
  "value": "John Doe",
  "timestamp": 1709234567890,
  "deleted": false
}
```

### Delete a value

**DELETE** `/api/memtable/delete/{key}`

Writes a tombstone record. The key will be absent from all future reads.

### Bulk insert

**POST** `/api/memtable/bulk`

```json
{
  "data": {
    "key1": "value1",
    "key2": "value2"
  }
}
```

### Memtable stats

**GET** `/api/memtable/stats`

Returns current memtable size in bytes and entry count.

---

## Internal Structure

```
src/main/java/com/bhav/gecko/store/
├── memtable/     In-memory ConcurrentSkipListMap with sorted key ordering
├── wal/          WAL segment creation, append, recovery, and background sync
├── sstable/      SSTable writers, iterators, Bloom Filters, and Sparse Indexes
├── manifest/     Crash-safe ledger of active SSTable files
├── diskstore/    Coordinator: routes writes through WAL → Memtable → Flush → Compaction
├── compaction/   Strategy-pattern compaction engine with K-way merge
└── cache/        LRU read cache with Null Object pattern to avoid null checks
```
