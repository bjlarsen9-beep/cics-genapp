package org.genapp.policy;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Business logic of LGIPOL01 plus the request dispatch and return-code handling of LGIPDB01 {@code MAINLINE}. */
@Service
public class PolicyInquiryService {

    private static final Logger log = LoggerFactory.getLogger(PolicyInquiryService.class);

    /** {@code CA-CUSTOMER-NUM PIC 9(10)} and {@code CA-POLICY-NUM PIC 9(10)}: up to 10 decimal digits. */
    private static final Pattern NUM_10 = Pattern.compile("[0-9]{1,10}");

    private final PolicyRepository repository;
    private final Map<String, PolicyTypeInquiry> inquiriesByType;

    public PolicyInquiryService(PolicyRepository repository, List<PolicyTypeInquiry> inquiries) {
        this.repository = repository;
        this.inquiriesByType = inquiries.stream()
                .collect(Collectors.toUnmodifiableMap(PolicyTypeInquiry::policyType, Function.identity()));
    }

    /** LGIPOL01/LGIPDB01 {@code MAINLINE}: validate the request, then run the paragraph for the policy type. */
    public InquiryResult inquire(String customerNumber, String policyNumber) {
        if (!isNum10(customerNumber) || !isNum10(policyNumber)) {
            return InquiryResult.of(ReturnCode.BAD_REQUEST);
        }
        long customer = Long.parseLong(customerNumber);
        long policy = Long.parseLong(policyNumber);
        try {
            Optional<String> type = repository.findPolicyType(customer, policy);
            if (type.isEmpty()) {
                return InquiryResult.of(ReturnCode.NOT_FOUND);
            }
            PolicyTypeInquiry inquiry = inquiriesByType.get(type.get());
            if (inquiry == null) {
                // EVALUATE WS-REQUEST-ID ... WHEN OTHER MOVE '99' TO CA-RETURN-CODE
                return InquiryResult.of(ReturnCode.UNKNOWN_REQUEST);
            }
            return inquiry.inquire(customer, policy)
                    .map(InquiryResult::found)
                    .orElseGet(() -> InquiryResult.of(ReturnCode.NOT_FOUND));
        } catch (DataAccessException e) {
            writeErrorMessage(e);
            return InquiryResult.of(ReturnCode.DB2_ERROR);
        }
    }

    private static boolean isNum10(String value) {
        return value != null && NUM_10.matcher(value).matches();
    }

    /** LGIPDB01 {@code WRITE-ERROR-MESSAGE}: log the SQL failure without customer or policy numbers. */
    private void writeErrorMessage(DataAccessException e) {
        log.error("LGIPDB01 policy inquiry failed: {} SQLCODE={}", e.getClass().getSimpleName(), sqlCode(e));
    }

    static int sqlCode(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getErrorCode();
            }
        }
        return 0;
    }
}
