package org.sitenetsoft.quarkus.atproto.oauth.flow;

/**
 * A login that cannot go on: the HTTP status to answer, a short code (the {@code error} parameter of the error page)
 * and a reason for the user and the log. Never carries a token, a code or a key.
 */
public class LoginFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    public LoginFailure(int status, String code, String reason) {
        super(reason);
        this.status = status;
        this.code = code;
    }

    public LoginFailure(int status, String code, String reason, Throwable cause) {
        super(reason, cause);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
