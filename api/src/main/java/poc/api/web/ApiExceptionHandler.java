package poc.api.web;

import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import poc.api.web.ApiExceptions.BadRequest;
import poc.api.web.ApiExceptions.Conflict;
import poc.api.web.ApiExceptions.NotFound;
import poc.api.web.ApiExceptions.Unavailable;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFound.class)
    ProblemDetail notFound(NotFound e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(Conflict.class)
    ProblemDetail conflict(Conflict e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(BadRequest.class)
    ProblemDetail badRequest(BadRequest e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidBody(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + " " + f.getDefaultMessage()).sorted().collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail invalidParam(ConstraintViolationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail badParameter(MethodArgumentTypeMismatchException e) {
        Class<?> type = e.getRequiredType();
        String allowed = type != null && type.isEnum()
            ? ". Allowed values: " + java.util.Arrays.stream(type.getEnumConstants()).map(Object::toString).collect(Collectors.joining(", "))
            : "";
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
            "Invalid value '" + e.getValue() + "' for parameter '" + e.getName() + "'" + allowed);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request body: " + e.getMessage());
    }

    @ExceptionHandler(Unavailable.class)
    ProblemDetail unavailable(Unavailable e) {
        log.warn("GridGain unavailable: {}", e.getMessage(), e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail database(DataAccessException e) {
        log.error("GridGain SQL error", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "GridGain SQL error: " + e.getMostSpecificCause().getMessage());
    }
}
