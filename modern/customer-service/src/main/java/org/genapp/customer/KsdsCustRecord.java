package org.genapp.customer;

/**
 * One fixed-width record of {@code base/data/ksdscust.txt} (LRECL 225).
 *
 * <p>The record is {@code CA-CUSTOMER-NUM} followed by the {@code CA-CUSTOMER-REQUEST} fields up to
 * {@code CA-EMAIL-ADDRESS}, as written by LGACVS01 ({@code WRITE FILE('KSDSCUST') FROM(CA-CUSTOMER-NUM)}).
 */
record KsdsCustRecord(
        long customerNumber,
        String firstName,
        String lastName,
        String dateOfBirth,
        String houseName,
        String houseNumber,
        String postcode,
        String phoneMobile,
        String phoneHome,
        String emailAddress) {

    static final int LRECL = 225;

    static KsdsCustRecord parse(String line) {
        String r = String.format("%-" + LRECL + "s", line);
        return new KsdsCustRecord(
                Long.parseLong(r.substring(0, 10)),   // CA-CUSTOMER-NUM  9(10)
                field(r, 10, 10),                     // CA-FIRST-NAME    X(10)
                field(r, 20, 20),                     // CA-LAST-NAME     X(20)
                field(r, 40, 10),                     // CA-DOB           X(10)
                field(r, 50, 20),                     // CA-HOUSE-NAME    X(20)
                field(r, 70, 4),                      // CA-HOUSE-NUM     X(4)
                field(r, 74, 8),                      // CA-POSTCODE      X(8)
                                                      // CA-NUM-POLICIES  9(3) at 82, not stored in Db2
                field(r, 85, 20),                     // CA-PHONE-MOBILE  X(20)
                field(r, 105, 20),                    // CA-PHONE-HOME    X(20)
                field(r, 125, 100));                  // CA-EMAIL-ADDRESS X(100)
    }

    private static String field(String record, int offset, int length) {
        return record.substring(offset, offset + length).stripTrailing();
    }
}
