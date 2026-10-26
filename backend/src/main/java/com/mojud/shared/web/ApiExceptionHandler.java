package com.mojud.shared.web;

import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Turns every error into an RFC 9457 problem response ({@code application/problem+json}) with a
 * {@code code} property from {@link ErrorCode} and a {@code detail} in the request's language.
 * Validation errors add {@code errors}: a list of {@code {field, message}}.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final MessageSource messages;

    public ApiExceptionHandler(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        ProblemDetail problem = problem(ex.code(), ex.messageArgs());
        ex.details().forEach((name, value) -> problem.setProperty(name, translateFieldProblems(value)));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.getStatus());
        Object retryAfter = ex.details().get(ApiException.RETRY_AFTER_SECONDS);
        if (retryAfter != null) {
            response.header(HttpHeaders.RETRY_AFTER, retryAfter.toString());
        }
        return response.body(problem);
    }

    // The three security handlers below also receive errors raised by the security filter chain,
    // which forwards them here (SecurityConfig) so that every error uses the same format.

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleUnauthenticated(AuthenticationException ex) {
        return respond(problem(ErrorCode.UNAUTHENTICATED));
    }

    @ExceptionHandler(CsrfException.class)
    ResponseEntity<ProblemDetail> handleCsrf(CsrfException ex) {
        return respond(problem(ErrorCode.CSRF_TOKEN_INVALID));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return respond(problem(ErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return respond(problem(ErrorCode.CONCURRENT_MODIFICATION));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return respond(problem(ErrorCode.SERVER_ERROR));
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(ErrorCode.FILE_TOO_LARGE);
        return ResponseEntity.status(problem.getStatus()).headers(headers).body(problem);
    }

    /** A body that fails its annotations, such as {@code @NotBlank} on a field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> fieldError(error.getField(), error))
                .toList();
        return validationFailed(errors, headers);
    }

    /** A query or path parameter that fails its annotations, such as {@code @Max(100) int limit}. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> result.getResolvableErrors().forEach(error ->
                errors.add(fieldError(String.valueOf(result.getMethodParameter().getParameterName()), error))));
        return validationFailed(errors, headers);
    }

    /** A query or path parameter of the wrong type, such as a malformed id. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String field = ex instanceof MethodArgumentTypeMismatchException argument ? argument.getName() : ex.getPropertyName();
        return validationFailed(List.of(invalidValue(String.valueOf(field))), headers);
    }

    /**
     * A body field of the wrong type, an unknown enum value or a missing number, reported like an
     * annotation error with the field's path (for example {@code items[0].qty}). JSON that cannot
     * be parsed at all is {@link ErrorCode#MALFORMED_REQUEST}.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (!(ex.getCause() instanceof MismatchedInputException mismatch) || mismatch.getPath().isEmpty()) {
            return super.handleHttpMessageNotReadable(ex, headers, status, request);
        }
        StringBuilder field = new StringBuilder();
        for (JacksonException.Reference reference : mismatch.getPath()) {
            if (reference.getPropertyName() != null) {
                field.append(field.isEmpty() ? "" : ".").append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                field.append('[').append(reference.getIndex()).append(']');
            }
        }
        return validationFailed(List.of(invalidValue(field.toString())), headers);
    }

    /**
     * Every other framework error (unknown route, wrong method, unreadable body, …) keeps its
     * status but gets our code and a translated detail, so clients handle one format only. This is
     * the last step every framework error passes, after Spring has built its problem body.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            ErrorCode code = frameworkCode(statusCode);
            problem.setProperty("code", code.code());
            problem.setDetail(message(code.messageKey()));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ErrorCode frameworkCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> ErrorCode.ROUTE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError() ? ErrorCode.SERVER_ERROR : ErrorCode.MALFORMED_REQUEST;
        };
    }

    private ResponseEntity<Object> validationFailed(List<Map<String, String>> errors, HttpHeaders headers) {
        ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED);
        problem.setProperty("errors", errors);
        return ResponseEntity.status(problem.getStatus()).headers(headers).body(problem);
    }

    private Map<String, String> fieldError(String field, MessageSourceResolvable error) {
        return Map.of("field", field, "message", messages.getMessage(error, locale()));
    }

    private Map<String, String> invalidValue(String field) {
        return Map.of("field", field, "message", message("field.invalid_value"));
    }

    private Object translateFieldProblems(Object detail) {
        if (!(detail instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof ApiException.FieldProblem)) {
            return detail;
        }
        return list.stream()
                .map(item -> (ApiException.FieldProblem) item)
                .map(item -> Map.of("field", item.field(), "message", message(item.messageKey())))
                .toList();
    }

    private ProblemDetail problem(ErrorCode code, Object... args) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), message(code.messageKey(), args));
        problem.setProperty("code", code.code());
        return problem;
    }

    private String message(String key, Object... args) {
        return messages.getMessage(key, args, key, locale());
    }

    private static Locale locale() {
        return LocaleContextHolder.getLocale();
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
