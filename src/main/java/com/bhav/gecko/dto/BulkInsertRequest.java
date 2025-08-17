package com.bhav.gecko.dto;

import java.util.Map;

public class BulkInsertRequest {
    private Map<String, String> data;

    public BulkInsertRequest() {
    }

    public BulkInsertRequest(Map<String, String> data) {
        this.data = data;
    }

    public Map<String, String> getData() {
        return data;
    }

    public void setData(Map<String, String> data) {
        this.data = data;
    }
}