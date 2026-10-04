package org.genapp.policy;

/**
 * Policy section of the GenApp COMMAREA ({@code CA-POLICY-REQUEST} in {@code base/src/lgcmarea.cpy}).
 *
 * @param customerNumber {@code CA-CUSTOMER-NUM PIC 9(10)}, zero-padded to 10 digits
 * @param policyNumber   {@code CA-POLICY-NUM PIC 9(10)}, zero-padded to 10 digits
 * @param policyType     {@code POLICY.POLICYTYPE CHAR(1)}: {@code E}, {@code H}, {@code M} or {@code C}
 *                       (in COBOL the caller chose the type via {@code CA-REQUEST-ID})
 * @param common         {@code CA-POLICY-COMMON}
 * @param details        {@code CA-POLICY-SPECIFIC} for the policy type
 */
public record Policy(
        String customerNumber,
        String policyNumber,
        String policyType,
        PolicyCommon common,
        PolicyDetails details) {
}
