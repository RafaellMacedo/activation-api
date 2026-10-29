package br.com.rafaelmacedo.activation_api.request.exception;

public class ActivationRequestConflictException extends RuntimeException {

    public ActivationRequestConflictException() {
        super("External ID is already associated with another request");
    }
}