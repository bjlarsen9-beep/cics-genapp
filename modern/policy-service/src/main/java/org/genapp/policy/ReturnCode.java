package org.genapp.policy;

import org.springframework.http.HttpStatus;

/** GenApp {@code CA-RETURN-CODE} values produced by LGIPOL01/LGIPDB01 and their HTTP equivalents. */
public enum ReturnCode {
    OK("00", HttpStatus.OK),
    NOT_FOUND("01", HttpStatus.NOT_FOUND),
    DB2_ERROR("90", HttpStatus.INTERNAL_SERVER_ERROR),
    BAD_REQUEST("98", HttpStatus.BAD_REQUEST),
    UNKNOWN_REQUEST("99", HttpStatus.BAD_REQUEST);

    private final String code;
    private final HttpStatus httpStatus;

    ReturnCode(String code, HttpStatus httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
