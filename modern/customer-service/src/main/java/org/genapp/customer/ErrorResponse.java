package org.genapp.customer;

/**
 * Error body for non-200 responses.
 *
 * @param returnCode the GenApp {@code CA-RETURN-CODE} ("01", "90" or "98")
 * @param message    human-readable reason; never contains customer data
 */
public record ErrorResponse(String returnCode, String message) {
}
