# Gecko

Gecko is a persistent, crash-recoverable key-value storage engine built in Java and Spring Boot. It uses a Log-Structured Merge (LSM) tree architecture, the same architecture that powers Cassandra, RocksDB, and ScyllaDB, and is designed for high write throughput with single-key atomic writes and WAL-based crash recovery.

Benchmarked at **80,000+ writes/second** on consumer hardware under concurrent load.

![System Architecture](sys%20arch.png)

---

## Features

### Core Storage

- **Thread-Safe Memtable**: An in-memory `ConcurrentSkipListMap` that keeps keys sorted and handles concurrent reads and writes without coarse-grained locking.
- **Write-Ahead Log (WAL)**: Every write is appended to a WAL segment before the operation is acknowledged. On startup, any un-flushed WAL entries are replayed automatically, so a process crash loses no acknowledged writes.
- **SSTables**: Immutable, sorted data files written to disk during a memtable flush. Each SSTable is accompanied by a Bloom Filter to skip unnecessary disk reads and a Sparse Index for binary search without scanning the full file.
- **Manifest**: A crash-safe ledger that tracks which SSTables are currently active. Survives restarts and prevents the engine from loading stale or partially-written files.
- **Blind Deletes**: Delete operations write a tombstone record directly rather than performing a read-then-modify, keeping the delete path as fast as any write.
- **Compaction**: A background compaction engine merges overlapping SSTables using K-way merge with a priority queue, automatically discarding stale and deleted records. Implemented behind a `CompactionStrategy` interface, with `MergeAllStrategy` as the current implementation.

### Concurrency

- **WAL Rotation Safety**: A `ReentrantReadWriteLock` ensures that concurrent write threads can append to the WAL freely, while the flush thread acquires an exclusive write lock only for the brief moment it swaps the active WAL pointer. This eliminates the race condition where a write thread holds a reference to a WAL file the flush thread has already closed.
- **Synchronized WAL Appends**: WAL writes are `synchronized` to prevent two concurrent requests from interleaving their bytes into the same log entry, which would corrupt the WAL on recovery.
- **Flush Hand-off**: The frozen memtable is published to the read path, newest first, inside the same write lock that swaps in the new memtable, so a read never misses a key that is in the middle of being flushed.
- **Serialized SSTable Reads**: Lookups and the compaction iterator share each SSTable's file handle under a lock, and the iterator tracks its own read position, so concurrent reads can't move each other's file pointer.
- **Compaction Snapshot Isolation**: Compaction tasks take a snapshot of the SSTable list inside the executor thread, preventing stale snapshots from causing incorrect merges when multiple compaction tasks queue up.

### Durability & Performance

- **Asynchronous WAL Sync**: Rather than forcing a physical disk flush (`fsync`) on every single write, a background scheduled thread syncs the WAL to disk every `wal.sync.interval.ms` milliseconds (200ms in the shipped config). This is the same strategy as Redis's `appendfsync everysec`. In the event of a complete power failure, writes acknowledged within the last sync interval are at risk.
- **LRU Read Cache**: A configurable LRU cache sits in front of the SSTable read path to serve hot keys from memory and avoid repeated disk seeks.
- **Checksum Validation**: Every record carries a CRC32 checksum that is verified on read. A corrupted record is reported as an error instead of being treated as a miss, so a read never falls back to an older value and compaction never commits a merge that silently dropped records.
- **Observability**: Prometheus metrics and Grafana dashboards are integrated via Spring Actuator, exposing WAL append latency, memtable size, and flush frequency.

### Guarantees and Limitations

Gecko is not a transactional (ACID) database. Every operation touches a single key, and these are the guarantees it makes today:

| Guarantee | Status |
| --- | --- |
| A single put or delete is atomic. A torn WAL record is detected by its length and CRC32 and discarded on replay. | ✅ |
| Acknowledged writes survive a process crash (`kill -9`, OOM, JVM crash). | ✅ |
| Flush and compaction are crash-consistent. SSTable files and their directory are fsynced before the fsynced manifest commits them, and only then are the WAL segment or the old SSTables deleted. | ✅ |
| The engine refuses to start if an SSTable listed in the manifest is missing or empty, instead of silently serving older data. | ✅ |
| Acknowledged writes survive a power loss. | ⚠️ Up to `wal.sync.interval.ms` (200ms by default) of acknowledged writes can be lost, as with Redis's `appendfsync everysec`. |
| Multi-key writes are all-or-nothing. | ❌ There are no transactions. `/bulk` writes keys one at a time and reports any keys that failed. |

Known issues still open:

- A compaction that overlaps a flush can order the merged SSTable ahead of newer data, so an older value can win.
- Two concurrent writes to the same key can reach the WAL and the memtable in different orders, so the value can change after a restart.
- Compaction can close an SSTable while a read is still using it, which returns an error for that read.

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
| `memtable.flsuh.threshold` | Memtable size in bytes before a flush is triggered              | `10485760` (10MB) |
| `wal.sync.interval.ms`     | How often the background thread fsyncs the WAL to physical disk | `200`             |
| `read.cache.enabled`       | Enable or disable the LRU read cache                            | `true`            |
| `read.cache.capacity`      | Maximum number of entries the LRU cache holds                   | `1000`            |
| `compaction.threshold`     | Number of SSTables that triggers a compaction                   | `4`               |

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
