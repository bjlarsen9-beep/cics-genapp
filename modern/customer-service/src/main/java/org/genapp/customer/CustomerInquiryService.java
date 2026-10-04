package org.genapp.customer;

import java.sql.SQLException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Business logic of LGICUS01 plus the return-code handling of LGICDB01. */
@Service
public class CustomerInquiryService {

    private static final Logger log = LoggerFactory.getLogger(CustomerInquiryService.class);

    /** {@code CA-CUSTOMER-NUM PIC 9(10)}: up to 10 decimal digits. */
    private static final Pattern CUSTOMER_NUM = Pattern.compile("[0-9]{1,10}");

    /** Db2 SQLCODE -913 (deadlock or timeout); LGICDB01 reports it as "01" not found. */
    static final int SQLCODE_DEADLOCK_OR_TIMEOUT = -913;

    private final CustomerRepository repository;

    public CustomerInquiryService(CustomerRepository repository) {
        this.repository = repository;
    }

    /** LGICUS01/LGICDB01 {@code MAINLINE}: validate the request, then {@code GET-CUSTOMER-INFO}. */
    public InquiryResult inquire(String customerNumber) {
        if (customerNumber == null || !CUSTOMER_NUM.matcher(customerNumber).matches()) {
            return InquiryResult.of(ReturnCode.BAD_REQUEST);
        }
        return getCustomerInfo(Long.parseLong(customerNumber));
    }

    /** LGICDB01 {@code GET-CUSTOMER-INFO}: run the SELECT and {@code EVALUATE SQLCODE}. */
    private InquiryResult getCustomerInfo(long customerNumber) {
        try {
            return repository.findByCustomerNumber(customerNumber)
                    .map(InquiryResult::found)
                    .orElseGet(() -> InquiryResult.of(ReturnCode.NOT_FOUND));
        } catch (DataAccessException e) {
            if (sqlCode(e) == SQLCODE_DEADLOCK_OR_TIMEOUT) {
                return InquiryResult.of(ReturnCode.NOT_FOUND);
            }
            writeErrorMessage(e);
            return InquiryResult.of(ReturnCode.DB2_ERROR);
        }
    }

    /** LGICDB01 {@code WRITE-ERROR-MESSAGE}: log the SQL failure without customer data. */
    private void writeErrorMessage(DataAccessException e) {
        log.error("LGICDB01 customer inquiry failed: {} SQLCODE={}", e.getClass().getSimpleName(), sqlCode(e));
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
