package org.genapp.policy;

/**
 * Error body for non-200 responses.
 *
 * @param returnCode the GenApp {@code CA-RETURN-CODE} ("01", "90", "98" or "99")
 * @param message    human-readable reason; never contains customer or policy data
 */
public record ErrorResponse(String returnCode, String message) {
}
