package com.gsb.web;

import com.gsb.service.NotFoundException;
import com.gsb.service.SampleService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.TreeMap;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(SampleService.BatchRejectedException.class)
    public ResponseEntity<Map<String, Object>> batchRejected(
            SampleService.BatchRejectedException ex) {
        return ResponseEntity.badRequest().body(body("BATCH_REJECTED", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(body("BAD_REQUEST", ex.getMessage()));
    }

    private static Map<String, Object> body(String error, String message) {
        Map<String, Object> map = new TreeMap<>();
        map.put("error", error);
        map.put("message", message == null ? "" : message);
        return map;
    }
}
