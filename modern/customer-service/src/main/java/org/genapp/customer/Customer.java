package org.genapp.customer;

import java.time.LocalDate;

/**
 * Customer section of the GenApp COMMAREA ({@code CA-CUSTOMER-REQUEST} in {@code base/src/lgcmarea.cpy}).
 *
 * <p>Text fields carry the COBOL value with trailing CHAR padding removed.
 *
 * @param customerNumber   {@code CA-CUSTOMER-NUM PIC 9(10)}, zero-padded to 10 digits
 * @param firstName        {@code CA-FIRST-NAME PIC X(10)}
 * @param lastName         {@code CA-LAST-NAME PIC X(20)}
 * @param dateOfBirth      {@code CA-DOB PIC X(10)}, ISO {@code yyyy-MM-dd}
 * @param houseName        {@code CA-HOUSE-NAME PIC X(20)}
 * @param houseNumber      {@code CA-HOUSE-NUM PIC X(4)}
 * @param postcode         {@code CA-POSTCODE PIC X(8)}
 * @param numberOfPolicies {@code CA-NUM-POLICIES PIC 9(3)}; LGICUS01 always returns 0
 * @param phoneMobile      {@code CA-PHONE-MOBILE PIC X(20)}
 * @param phoneHome        {@code CA-PHONE-HOME PIC X(20)}
 * @param emailAddress     {@code CA-EMAIL-ADDRESS PIC X(100)}
 */
public record Customer(
        String customerNumber,
        String firstName,
        String lastName,
        String dateOfBirth,
        String houseName,
        String houseNumber,
        String postcode,
        int numberOfPolicies,
        String phoneMobile,
        String phoneHome,
        String emailAddress) {

    static String formatCustomerNumber(long customerNumber) {
        return String.format("%010d", customerNumber);
    }

    static String formatDate(LocalDate date) {
        return date == null ? "" : date.toString();
    }
}
