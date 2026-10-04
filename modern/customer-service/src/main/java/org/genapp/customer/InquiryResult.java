package org.genapp.customer;

import java.util.Optional;

/** Outcome of a customer inquiry: the {@code CA-RETURN-CODE} plus the customer section when found. */
public record InquiryResult(ReturnCode returnCode, Optional<Customer> customer) {

    static InquiryResult found(Customer customer) {
        return new InquiryResult(ReturnCode.OK, Optional.of(customer));
    }

    static InquiryResult of(ReturnCode returnCode) {
        return new InquiryResult(returnCode, Optional.empty());
    }
}
