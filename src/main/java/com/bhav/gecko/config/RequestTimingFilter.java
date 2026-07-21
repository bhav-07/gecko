package com.bhav.gecko.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class RequestTimingFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(RequestTimingFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        long startTime = System.currentTimeMillis();

        try {
            chain.doFilter(request, response);
        } finally {
            if (request instanceof HttpServletRequest httpRequest) {
                long duration = System.currentTimeMillis() - startTime;
                String method = httpRequest.getMethod();
                String uri = httpRequest.getRequestURI();
                
                // Only log API endpoints to avoid spamming actuator calls
                if (uri.startsWith("/api/")) {
                    logger.info("[{} {}] completed in {}ms", method, uri, duration);
                }
            }
        }
    }
}
