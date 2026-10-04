package org.genapp.policy;

import java.util.Optional;

/**
 * One LGIPDB01 paragraph for one policy type (for example {@code GET-ENDOW-DB2-INFO}). Implementations are
 * Spring beans; {@link PolicyInquiryService} picks the one whose {@link #policyType()} matches the policy's
 * {@code POLICY.POLICYTYPE}.
 */
public interface PolicyTypeInquiry {

    /** {@code POLICY.POLICYTYPE} value handled: {@code "E"}, {@code "H"}, {@code "M"} or {@code "C"}. */
    String policyType();

    /** COBOL {@code CA-REQUEST-ID} that selected this paragraph, for example {@code "01IEND"}. */
    String requestId();

    /**
     * Runs the paragraph's SELECT (POLICY joined with the type table) for one customer and policy number.
     * An empty result is SQLCODE 100 ({@code '01'}). {@link org.springframework.dao.DataAccessException}s
     * propagate and become {@code '90'}.
     */
    Optional<Policy> inquire(long customerNumber, long policyNumber);
}
