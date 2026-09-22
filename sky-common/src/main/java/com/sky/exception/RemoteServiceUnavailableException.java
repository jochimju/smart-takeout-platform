package com.sky.exception;

/**
 * A safe, user-visible failure for a required synchronous service dependency.
 * Callers must not manufacture a successful result when this exception occurs.
 */
public class RemoteServiceUnavailableException extends BaseException {
    public RemoteServiceUnavailableException(String message) {
        super(message);
    }
}
