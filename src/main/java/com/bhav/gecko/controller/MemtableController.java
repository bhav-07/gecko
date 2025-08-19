package com.bhav.gecko.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bhav.gecko.dto.BulkInsertRequest;
import com.bhav.gecko.dto.GetResponse;
import com.bhav.gecko.dto.MemtableStats;
import com.bhav.gecko.dto.PutRequest;
import com.bhav.gecko.exception.KeyNotFoundException;
import com.bhav.gecko.service.MemtableService;
import com.bhav.gecko.store.memtable.Record;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/memtable")
public class MemtableController {

    @Autowired
    private MemtableService memtableService;

    @PostMapping("/put")
    public ResponseEntity<String> put(@RequestBody PutRequest request) {
        try {
            memtableService.put(request.getKey(), request.getValue());
            return ResponseEntity.ok("Key-value pair stored successfully");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error storing key-value pair: " + e.getMessage());
        }
    }

    @GetMapping("/get/{key}")
    public ResponseEntity<?> get(@PathVariable String key) {
        try {
            Record record = memtableService.get(key);
            return ResponseEntity.ok(new GetResponse(record.getKey(), record.getValue(),
                    record.getTimestamp(), record.isDeleted()));
        } catch (KeyNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Key not found: " + key);
        }
    }

    @DeleteMapping("/delete/{key}")
    public ResponseEntity<String> delete(@PathVariable String key) {
        try {
            memtableService.delete(key);
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
    public ResponseEntity<Map<String, Record>> getAllKVPairs() {
        Map<String, Record> allPairs = memtableService.getAllKVPairs();
        return ResponseEntity.ok(allPairs);
    }

    @GetMapping("/keys")
    public ResponseEntity<Set<String>> getAllKeys() {
        Set<String> keys = memtableService.getAllKeys();
        return ResponseEntity.ok(keys);
    }

    @GetMapping("/stats")
    public ResponseEntity<MemtableStats> getStats() {
        MemtableStats stats = memtableService.getStats();
        return ResponseEntity.ok(stats);
    }

    @DeleteMapping("/clear")
    public ResponseEntity<String> clear() {
        memtableService.clear();
        return ResponseEntity.ok("Memtable cleared successfully");
    }

    @PostMapping("/bulk")
    public ResponseEntity<String> bulkInsert(@RequestBody BulkInsertRequest request) {
        try {
            memtableService.bulkInsert(request.getData());
            return ResponseEntity.ok("Bulk insert completed successfully");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error during bulk insert: " + e.getMessage());
        }
    }
}