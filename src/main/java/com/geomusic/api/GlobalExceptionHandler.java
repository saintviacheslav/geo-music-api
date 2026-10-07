package com.geomusic.api;

import com.geomusic.domain.Region;
import com.geomusic.error.InvalidRegionException;
import com.geomusic.error.ResourceNotFoundException;
import com.geomusic.routing.AccessMode;
import com.geomusic.routing.NodeUnavailableException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

/**
 * Maps exceptions to RFC 7807 {@link ProblemDetail} responses. Every problem carries {@code timestamp} and,
 * when tracing is active, {@code traceId} to find the request in Grafana Tempo.
 *
 * <ul>
 *   <li>{@link NodeUnavailableException} anywhere in the cause chain → 503 with {@code Retry-After};</li>
 *   <li>{@link ResourceNotFoundException} → 404;</li>
 *   <li>{@link InvalidRegionException} → 400;</li>
 *   <li>{@link DataIntegrityViolationException} → 409;</li>
 *   <li>validation and other Spring MVC errors → handled by {@link ResponseEntityExceptionHandler}.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String PROBLEM_BASE = "https://geomusic.dev/problems/";
    private static final URI TYPE_NODE_UNAVAILABLE = URI.create(PROBLEM_BASE + "database-node-unavailable");
    private static final URI TYPE_NOT_FOUND = URI.create(PROBLEM_BASE + "not-found");
    private static final URI TYPE_INVALID_REGION = URI.create(PROBLEM_BASE + "invalid-region");
    private static final URI TYPE_CONFLICT = URI.create(PROBLEM_BASE + "conflict");
    private static final URI TYPE_INTERNAL = URI.create(PROBLEM_BASE + "internal-error");
    private static final String RETRY_AFTER_SECONDS = "5";

    private final ObjectProvider<Tracer> tracer;

    public GlobalExceptionHandler(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Object> handleNotFound(ResourceNotFoundException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(TYPE_NOT_FOUND);
        problem.setTitle("Resource not found");
        problem.setProperty("resource", ex.getResource());
        problem.setProperty("id", String.valueOf(ex.getId()));
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
    }

    @ExceptionHandler(InvalidRegionException.class)
    ResponseEntity<Object> handleInvalidRegion(InvalidRegionException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(TYPE_INVALID_REGION);
        problem.setTitle("Invalid region");
        problem.setProperty("allowedValues", Arrays.stream(Region.values()).map(Enum::name).toList());
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setTitle("Validation failed");
        problem.setDetail("Request body has %d invalid field(s)".formatted(ex.getErrorCount()));
        problem.setProperty("errors", ex.getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList());
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Object> handleDataAccess(RuntimeException ex, WebRequest request) {
        NodeUnavailableException unavailable = findCause(ex, NodeUnavailableException.class);
        if (unavailable != null) {
            return handleNodeUnavailable(unavailable, ex, request);
        }
        if (ex instanceof DataIntegrityViolationException) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "The request conflicts with existing data");
            problem.setType(TYPE_CONFLICT);
            problem.setTitle("Data conflict");
            return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.CONFLICT, request);
        }
        return handleUnexpected(ex, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        NodeUnavailableException unavailable = findCause(ex, NodeUnavailableException.class);
        if (unavailable != null) {
            return handleNodeUnavailable(unavailable, ex, request);
        }
        log.error("Unhandled error", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected server error");
        problem.setType(TYPE_INTERNAL);
        problem.setTitle("Internal server error");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    private ResponseEntity<Object> handleNodeUnavailable(NodeUnavailableException unavailable, Exception ex,
                                                         WebRequest request) {
        log.warn("Request failed, database unavailable: {}", unavailable.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                unavailable.getMessage());
        problem.setType(TYPE_NODE_UNAVAILABLE);
        problem.setTitle(unavailable.getMode() == AccessMode.WRITE
                ? "Regional master unavailable"
                : "Regional database unavailable");
        problem.setProperty("region", unavailable.getRegion().name());
        problem.setProperty("operation", unavailable.getMode().name());
        problem.setProperty("attemptedNodes", unavailable.getAttemptedNodes());
        if (unavailable.getCause() != null) {
            problem.setProperty("cause", unavailable.getCause().getMessage());
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.SERVICE_UNAVAILABLE, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            problem.setProperty("timestamp", Instant.now());
            String traceId = currentTraceId();
            if (traceId != null) {
                problem.setProperty("traceId", traceId);
            }
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private String currentTraceId() {
        Tracer current = tracer.getIfAvailable();
        Span span = current == null ? null : current.currentSpan();
        return span == null ? null : span.context().traceId();
    }

    private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
