package com.shiftrepair.persistence;

/** Storage failure: do not display success; reload authoritative state before retrying. */
public final class PersistenceException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public PersistenceException(String message) { super(message); }
    public PersistenceException(String message, Throwable cause) { super(message, cause); }
}
