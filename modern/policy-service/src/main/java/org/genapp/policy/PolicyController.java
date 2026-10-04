package org.genapp.policy;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST entry point replacing the LGIPOL01 CICS transaction. */
@RestController
@RequestMapping("/policies")
public class PolicyController {

    private final PolicyInquiryService service;

    public PolicyController(PolicyInquiryService service) {
        this.service = service;
    }

    @GetMapping("/{customerNumber}/{policyNumber}")
    public ResponseEntity<?> getPolicy(@PathVariable String customerNumber, @PathVariable String policyNumber) {
        InquiryResult result = service.inquire(customerNumber, policyNumber);
        ReturnCode rc = result.returnCode();
        if (rc == ReturnCode.OK) {
            return ResponseEntity.ok(result.policy().orElseThrow());
        }
        return ResponseEntity.status(rc.httpStatus()).body(new ErrorResponse(rc.code(), message(rc)));
    }

    private static String message(ReturnCode rc) {
        return switch (rc) {
            case NOT_FOUND -> "Policy not found";
            case BAD_REQUEST -> "Customer and policy numbers must be 1 to 10 digits";
            case UNKNOWN_REQUEST -> "Unsupported policy type";
            case DB2_ERROR -> "Database error";
            case OK -> "";
        };
    }
}
