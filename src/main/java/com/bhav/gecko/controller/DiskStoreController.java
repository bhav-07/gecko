package com.bhav.gecko.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.core.annotation.Timed;

import com.bhav.gecko.dto.BulkInsertRequest;
import com.bhav.gecko.dto.GetResponse;
import com.bhav.gecko.dto.MemtableStats;
import com.bhav.gecko.dto.PutRequest;
import com.bhav.gecko.exception.KeyNotFoundException;
import com.bhav.gecko.store.diskstore.DiskStoreServiceImpl;
import com.bhav.gecko.store.memtable.MemTableRecord;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/memtable")
public class DiskStoreController {

    private final DiskStoreServiceImpl diskStoreService;

    DiskStoreController(DiskStoreServiceImpl diskStoreService) {
        this.diskStoreService = diskStoreService;
    }

    @PostMapping("/put")
    @Timed(value = "gecko.write.latency", description = "Time taken to put a key")
    public ResponseEntity<String> put(@RequestBody PutRequest request) {
        try {
            diskStoreService.put(request.getKey(), request.getValue());
            return ResponseEntity.ok("Key-value pair stored successfully");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error storing key-value pair: " + e.getMessage());
        }
    }

    @GetMapping("/get/{key}")
    @Timed(value = "gecko.query.latency", description = "Time taken to get a key")
    public ResponseEntity<?> get(@PathVariable String key) {
        try {
            MemTableRecord record = diskStoreService.get(key);
            return ResponseEntity.ok(new GetResponse(record.getKey(), record.getValue(),
                    record.getTimestamp(), record.isDeleted()));
        } catch (KeyNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Key not found: " + key);
        }
    }

    @DeleteMapping("/delete/{key}")
    @Timed(value = "gecko.write.latency", description = "Time taken to delete a key")
    public ResponseEntity<String> delete(@PathVariable String key) {
        try {
            diskStoreService.delete(key);
            return ResponseEntity.ok("Key deleted successfully");
        } catch (KeyNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Key not found: " + key);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error deleting key: " + e.getMessage());
        }
    }

    @GetMapping("/all")
    public ResponseEntity<Map<String, MemTableRecord>> getAllKVPairs() {
        Map<String, MemTableRecord> allPairs = diskStoreService.getAllKVPairs();
        return ResponseEntity.ok(allPairs);
    }

    @GetMapping("/keys")
    public ResponseEntity<Set<String>> getAllKeys() {
        Set<String> keys = diskStoreService.getAllKeys();
        return ResponseEntity.ok(keys);
    }

    @GetMapping("/stats")
    public ResponseEntity<MemtableStats> getStats() {
        MemtableStats stats = diskStoreService.getMemtableStats();
        return ResponseEntity.ok(stats);
    }

    // @DeleteMapping("/clear")
    // public ResponseEntity<String> clear() {
    // diskStoreService.();
    // return ResponseEntity.ok("Memtable cleared successfully");
    // }

    @PostMapping("/bulk")
    public ResponseEntity<String> bulkInsert(@RequestBody BulkInsertRequest request) {
        try {
            diskStoreService.bulkInsert(request.getData());
            return ResponseEntity.ok("Bulk insert completed successfully");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error during bulk insert: " + e.getMessage());
        }
    }
}