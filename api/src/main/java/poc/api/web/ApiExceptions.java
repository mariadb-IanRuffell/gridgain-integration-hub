package poc.api.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Exceptions the controllers throw; ApiExceptionHandler maps them to RFC 7807 problem responses. */
public final class ApiExceptions {
    private ApiExceptions() {}

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class NotFound extends RuntimeException {
        public NotFound(String what, Object id) {
            super(what + " " + id + " not found");
        }
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    public static class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class BadRequest extends RuntimeException {
        public BadRequest(String message) {
            super(message);
        }
    }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public static class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
