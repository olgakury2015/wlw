package com.iot.platform.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalStateException e) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ok", Boolean.FALSE);
        body.put("error", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }
}
