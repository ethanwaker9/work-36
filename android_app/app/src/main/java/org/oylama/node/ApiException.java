package org.oylama.node;

public final class ApiException extends Exception {
    public final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }
}
