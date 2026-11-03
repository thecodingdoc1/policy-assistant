package example.ai.controller;

import java.sql.SQLException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates failures into RFC 7807 problem responses. Extending ResponseEntityExceptionHandler
 * gives Spring MVC's own errors (missing parameter, bad body, wrong media type) the same shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // SQLSTATEs raised by upsert_document()
    static final String INVALID_PARAMETER = "22023";
    static final String ALREADY_EXISTS = "23505";
    static final String NOT_FOUND = "P0002";

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail handleDataAccess(DataAccessException e) {
        String sqlState = sqlStateOf(e);
        if (sqlState != null) {
            switch (sqlState) {
                case INVALID_PARAMETER:
                    return problem(HttpStatus.BAD_REQUEST, "Invalid document", e);
                case ALREADY_EXISTS:
                    return problem(HttpStatus.CONFLICT, "Document conflict", e);
                case NOT_FOUND:
                    return problem(HttpStatus.NOT_FOUND, "Document not found", e);
                default:
                    break;
            }
        }
        return internalError(e);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e) {
        return internalError(e);
    }

    private ProblemDetail internalError(Exception e) {
        // The client gets an opaque id; the details (which may include SQL or internals) stay in the log.
        String correlationId = UUID.randomUUID().toString();
        log.error("Unhandled error [correlationId={}]", correlationId, e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An internal error occurred. Quote the correlation id when reporting it.");
        pd.setProperty("correlationId", correlationId);
        return pd;
    }

    private ProblemDetail problem(HttpStatus status, String title, DataAccessException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, messageOf(e));
        pd.setTitle(title);
        return pd;
    }

    private static String sqlStateOf(Throwable t) {
        for (; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /** The text from our own RAISE EXCEPTION, without the "ERROR:" prefix or the PL/pgSQL "Where:" trace. */
    private static String messageOf(Throwable t) {
        for (; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getMessage() != null) {
                String first = sql.getMessage().lines().findFirst().orElse("");
                return first.startsWith("ERROR: ") ? first.substring("ERROR: ".length()) : first;
            }
        }
        return "Request rejected";
    }
}
