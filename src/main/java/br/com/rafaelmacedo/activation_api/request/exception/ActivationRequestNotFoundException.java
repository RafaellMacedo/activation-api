package br.com.rafaelmacedo.activation_api.request.exception;

public class ActivationRequestNotFoundException extends RuntimeException {

    public ActivationRequestNotFoundException() {
        super("Activation request not found");
    }
}