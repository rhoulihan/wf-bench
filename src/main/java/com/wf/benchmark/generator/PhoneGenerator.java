package com.wf.benchmark.generator;

import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.Date;
import java.util.List;

/**
 * Generates phone documents matching the sample schema.
 * Uses ObjectId for primary key with embedded phoneKey for customer reference.
 */
public class PhoneGenerator implements DataGenerator {

    private static final long BASE_CUSTOMER_NUMBER = 1_000_000_001L;

    private static final List<String> PHONE_TYPE_CODES = List.of(
        "MOBILE", "HOME", "BUSINESS", "FAX"
    );

    private static final List<String> VERIFICATION_STATUS_CODES = List.of(
        "TWO_FACTOR_AUTH", "VERIFIED", "PENDING"
    );

    private static final List<String> VERIFICATION_SOURCES = List.of(
        "WELLS_FARGO_ONLINE", "MOBILE_APP", "BRANCH", "CALL_CENTER"
    );

    private static final List<String> VENDOR_SOURCES = List.of(
        "LEXISNEXIS", "PROVE", "NEUSTAR", "PAYFONE"
    );

    private static final List<String> DISCONNECT_STATUS_CODES = List.of(
        "NOT_DISCONNECTED", "DISCONNECTED", "PORTED"
    );

    private final RandomDataProvider random;
    private final double phonesPerIdentity;
    private final String collectionName;

    public PhoneGenerator(RandomDataProvider random, double phonesPerIdentity, String collectionPrefix) {
        this.random = random;
        this.phonesPerIdentity = phonesPerIdentity;
        this.collectionName = collectionPrefix + "phone";
    }

    @Override
    public String getCollectionName() {
        return collectionName;
    }

    @Override
    public Document generate(long sequenceNumber) {
        // Map sequence to customer number based on ratio
        // If ratio is 2.5, sequences 0,1 map to customer 0, sequences 2,3 map to customer 1, etc.
        long customerOffset = (long) (sequenceNumber / phonesPerIdentity);
        long customerNumber = BASE_CUSTOMER_NUMBER + customerOffset;
        int customerCompanyNumber = random.randomInt(1, 3);

        // Determine phone type based on position within customer's phones
        int phoneIndex = (int) (sequenceNumber % (long) Math.ceil(phonesPerIdentity));
        String phoneTypeCode = determinePhoneType(phoneIndex);

        return generatePhoneDocument(customerNumber, customerCompanyNumber, phoneTypeCode, phoneIndex);
    }

    private String determinePhoneType(int phoneIndex) {
        // First phone is usually MOBILE, then vary
        return switch (phoneIndex) {
            case 0 -> "MOBILE";
            case 1 -> random.randomBoolean(0.7) ? "HOME" : "BUSINESS";
            default -> random.randomChoice(PHONE_TYPE_CODES);
        };
    }

    private Document generatePhoneDocument(long customerNumber, int customerCompanyNumber,
                                           String phoneTypeCode, int occurrenceNumber) {
        String phoneNumber = random.phoneNumber();
        String lineTypeCode = random.lineTypeCode(phoneTypeCode);
        String countryCode = random.randomBoolean(0.95) ? "US" : "MX";
        String providerName = random.phoneProvider(lineTypeCode);
        String extension = "BUSINESS".equals(phoneTypeCode) ? random.extension() : null;

        Date createdTs = random.timestamp();
        Date updatedTs = random.recentTimestamp();
        Date verificationTs = random.recentTimestamp();
        Date vendorVerificationTs = random.recentTimestamp();
        Date providerConfirmTs = random.recentTimestamp();

        // Determine verification statuses
        boolean hasCustomerVerification = "MOBILE".equals(phoneTypeCode) && random.randomBoolean(0.7);
        String customerVerificationStatus = hasCustomerVerification ?
            random.randomChoice(VERIFICATION_STATUS_CODES.subList(0, 2)) : null;
        String customerVerificationSource = hasCustomerVerification ?
            random.randomChoice(VERIFICATION_SOURCES) : null;

        boolean hasVendorVerification = random.randomBoolean(0.8);
        String vendorVerificationStatus = hasVendorVerification ? "VERIFIED" : null;
        String vendorVerificationSource = hasVendorVerification ?
            random.randomChoice(VENDOR_SOURCES) : null;

        String disconnectStatus = "WIRELESS".equals(lineTypeCode) ?
            "NOT_DISCONNECTED" : null;

        int cdcSeq = random.randomInt(20, 50);

        // Build systemOfRecord for phone
        Document phoneSystemOfRecord = new Document()
            .append("M_CIF_CUS_TO_COM_PURPOSE_CDE", mapPhoneTypeToPurpose(phoneTypeCode))
            .append("M_CIF_CUS_TO_COM_USE_OCC_NO", occurrenceNumber + 1)
            .append("M_CIF_CUS_TO_COM_EFF_DATE", generateEffectiveDate());

        // Build main systemOfRecord
        Document mainSystemOfRecord = new Document()
            .append("M_XCM_DEVICE_TYPE", "WIRELESS".equals(lineTypeCode) ? "MOBIL" : "LAND")
            .append("M_XCMPRV_MSISDN_TYPE", lineTypeCode.equals("WIRELESS") ? "MOBILE" : "WIRELINE")
            .append("X53011_COM_FEAT_STAT_CUST", hasCustomerVerification ? "2F" : null)
            .append("X53011_COM_FEAT_STAT_RSN_CUST", hasCustomerVerification ?
                customerVerificationSource.replace("_", "").substring(0, Math.min(4, customerVerificationSource.length())).toUpperCase() : null)
            .append("X53011_COM_FEAT_STAT_TS_CUST", hasCustomerVerification ?
                formatTimestamp(verificationTs) : null)
            .append("X53011_COM_FEAT_STAT_TS_VEND", hasVendorVerification ?
                formatTimestamp(vendorVerificationTs) : null)
            .append("X53011_COM_FEAT_STAT_RSN_VEND", vendorVerificationSource)
            .append("M_XCMPRV_LST_CONFIRM_DT_TM", formatTimestamp(providerConfirmTs))
            .append("X53011_COM_FEAT_STAT_VEND", hasVendorVerification ? "VF" : null)
            .append("X53011_COM_VRFCN_SOURCE", vendorVerificationSource)
            .append("X53011_COM_VRFCN_TS_DT_AND_TM", formatShortTimestamp(providerConfirmTs));

        Document doc = new Document()
            .append("_id", new ObjectId())
            .append("countryCode", countryCode)
            .append("customerVerificationStatusCode", customerVerificationStatus)
            .append("mobileDisconnectStatusTimestamp", null)
            .append("customerVerificationStatusTimestamp", hasCustomerVerification ? verificationTs : null)
            .append("metaData", new Document()
                .append("createdByProcessName", "24201 - IL")
                .append("createdByIdentifier", "VT_INITIAL_LOAD")
                .append("createdTimestamp", createdTs)
                .append("updatedByProcessName", "24201 - CDC")
                .append("updatedByIdentifier", "VT_CDC_" + String.format("%03d", random.randomInt(1, 999)))
                .append("updatedTimestamp", updatedTs)
                .append("cdcDeletedFlag", "N")
                .append("PHONE", new Document()
                    .append("createdCdcIdentifier", "CDC_PH" + customerNumber + "_" + String.format("%03d", 1))
                    .append("createdCdcTimestamp", createdTs.getTime())
                    .append("updatedCdcIdentifier", "CDC_PH" + customerNumber + "_" + String.format("%03d", cdcSeq))
                    .append("updatedCdcTimestamp", updatedTs.getTime())))
            .append("isPreferred", occurrenceNumber == 0)
            .append("customerVerificationStatusSourceCode", customerVerificationSource)
            .append("lineTypeCode", lineTypeCode)
            .append("mobileProviderLastConfirmationTimestamp", providerConfirmTs)
            .append("phoneKey", new Document()
                .append("customerNumber", customerNumber)
                .append("customerCompanyNumber", customerCompanyNumber)
                .append("phoneNumber", phoneNumber)
                .append("phoneNumberTypeCode", phoneTypeCode)
                .append("systemOfRecord", phoneSystemOfRecord))
            .append("providerName", providerName)
            .append("extension", extension)
            .append("vendorVerificationStatusSourceCode", vendorVerificationSource)
            .append("systemOfRecord", mainSystemOfRecord)
            .append("mobileDisconnectStatusCode", disconnectStatus)
            .append("vendorVerificationStatusTimestamp", hasVendorVerification ? vendorVerificationTs : null)
            .append("lineTypeSourceCode", lineTypeCode.equals("WIRELESS") ? "MOBILE" : "WIRELINE")
            .append("vendorVerificationStatusCode", vendorVerificationStatus);

        return doc;
    }

    private String mapPhoneTypeToPurpose(String phoneType) {
        return switch (phoneType) {
            case "MOBILE" -> "MOBILE";
            case "HOME" -> "HOME";
            case "BUSINESS" -> "BUSNS";
            case "FAX" -> "FAX";
            default -> "OTHER";
        };
    }

    private String generateEffectiveDate() {
        int year = random.randomInt(19, 24);
        int month = random.randomInt(1, 12);
        int day = random.randomInt(1, 28);
        return String.format("%02d%02d%02d1", year, month, day);
    }

    private String formatTimestamp(Date date) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMddHHmmss");
        return sdf.format(date) + "000000";
    }

    private String formatShortTimestamp(Date date) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMddHHmmss");
        return sdf.format(date);
    }
}
