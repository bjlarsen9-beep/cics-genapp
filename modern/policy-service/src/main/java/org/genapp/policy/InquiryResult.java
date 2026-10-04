package org.genapp.policy;

import java.util.Optional;

/** Outcome of a policy inquiry: the {@code CA-RETURN-CODE} plus the policy section when found. */
public record InquiryResult(ReturnCode returnCode, Optional<Policy> policy) {

    static InquiryResult found(Policy policy) {
        return new InquiryResult(ReturnCode.OK, Optional.of(policy));
    }

    static InquiryResult of(ReturnCode returnCode) {
        return new InquiryResult(returnCode, Optional.empty());
    }
}
