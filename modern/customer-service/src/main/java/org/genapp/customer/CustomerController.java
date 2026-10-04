package org.genapp.customer;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST entry point replacing the LGICUS01 CICS transaction. */
@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerInquiryService service;

    public CustomerController(CustomerInquiryService service) {
        this.service = service;
    }

    @GetMapping("/{customerNumber}")
    public ResponseEntity<?> getCustomer(@PathVariable String customerNumber) {
        InquiryResult result = service.inquire(customerNumber);
        ReturnCode rc = result.returnCode();
        if (rc == ReturnCode.OK) {
            return ResponseEntity.ok(result.customer().orElseThrow());
        }
        return ResponseEntity.status(rc.httpStatus()).body(new ErrorResponse(rc.code(), message(rc)));
    }

    private static String message(ReturnCode rc) {
        return switch (rc) {
            case NOT_FOUND -> "Customer not found";
            case BAD_REQUEST -> "Customer number must be 1 to 10 digits";
            case DB2_ERROR -> "Database error";
            case OK -> "";
        };
    }
}
