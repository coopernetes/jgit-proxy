package com.rbc.jgitproxy.api;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.rbc.jgitproxy.servlet.filter.GitProxyFilter.ERROR_ATTRIBUTE;

@RestController
@Slf4j
public class GenericErrorController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<String> error(HttpServletRequest request) {
        Throwable throwable = (Throwable) request.getAttribute("jakarta.servlet.error.exception");
        Integer statusCode = (Integer) request.getAttribute("jakarta.servlet.error.status_code");
        String errorMessage = "An error occurred";

        if (statusCode != null && statusCode == 404) {
            errorMessage = "Resource not found";
        } else if (request.getAttribute(ERROR_ATTRIBUTE) != null) {
            errorMessage = (String) request.getAttribute(ERROR_ATTRIBUTE);
        } else if (throwable != null) {
            log.error("Unexpected error occurred", throwable);
        } else {
            log.error("Unexpected error occurred and no exception thrown");
        }
        return ResponseEntity.status(statusCode != null ? statusCode : 500).body(errorMessage);
    }
}
