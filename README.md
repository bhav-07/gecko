# Gecko 🦎

Gecko is a distributed, persistent NoSQL key-value store built with Java and Spring Boot. It implements a Log-Structured Merge (LSM) tree architecture, featuring in-memory Memtables, Write-Ahead Logs (WAL) for durability, and Bloom Filters for efficient lookups.

## 🚀 Features

- **LSM Tree Architecture**: Efficient write handling using in-memory Memtables.
- **Persistence**: Data durability is ensured through a Write-Ahead Log (WAL).
- **Crash Recovery**: Automatic recovery from WAL on startup.
- **Bulk Operations**: Optimized bulk insert capabilities.
- **Bloom Filters**: (In Development) Probabilistic data structure to reduce disk seeks for non-existent keys.
- **REST API**: Simple HTTP interface for interacting with the store.

## 🛠 Tech Stack

- **Java 21**
- **Spring Boot 3.5.4**
- **Apache Commons Logging**
- **Google Guava** (Bloom Filters)

## 📦 Getting Started

### Prerequisites

- Java 21 or higher
- Maven

### Installation

1. Clone the repository:
   ```bash
   git clone <repository-url>
   cd gecko/gecko
   ```

2. Build the project:
   ```bash
   ./mvnw clean install
   ```

### Running the Application

Start the Spring Boot application:

```bash
./mvnw spring-boot:run
```

The server will start on port `8080` (default).

## ⚙️ Configuration

Key configuration settings in `src/main/resources/application.properties`:

| Property | Description | Default |
|----------|-------------|---------|
| `wal.directory` | Directory where Write-Ahead Logs are stored | `./wal_data` |
| `memtable.flush.threshold` | Size threshold (in bytes) to flush Memtable (Dev mode) | `3000` |

## 🔌 API Reference

### Store Value
**POST** `/api/memtable/put`

Request Body:
```json
{
  "key": "user:123",
  "value": "John Doe"
}
```

### Get Value
**GET** `/api/memtable/get/{key}`

Response:
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

Marks the key as deleted (tombstone) in Memtable and WAL.

### Bulk Insert
**POST** `/api/memtable/bulk`

Request Body:
```json
{
  "data": {
    "key1": "value1",
    "key2": "value2"
  }
}
```

### Get All (Debug)
**GET** `/api/memtable/all`

Returns all key-value pairs currently in the Memtable.

### Get Stats
**GET** `/api/memtable/stats`

Returns current Memtable statistics (size, entry count, etc.).

## 📂 Project Structure

- `src/main/java/com/bhav/gecko/store`: Core storage logic.
  - `memtable`: In-memory data structure implementation.
  - `wal`: Write-Ahead Log management.
  - `sstable`: Sorted String Table and Bloom Filter components.
  - `diskstore`: Disk-based storage coordination.
- `src/main/java/com/bhav/gecko/controller`: REST API endpoints.
