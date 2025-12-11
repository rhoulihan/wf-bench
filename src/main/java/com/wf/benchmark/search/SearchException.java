package com.wf.benchmark.search;

/**
 * Exception thrown when a search operation fails.
 * Wraps underlying SQL or database exceptions.
 */
public class SearchException extends RuntimeException {

    public SearchException(String message) {
        super(message);
    }

    public SearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
