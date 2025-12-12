package com.wf.benchmark.search;

/**
 * Result model for UC 1-7 search queries matching the PDF response format.
 * Contains all fields required for the Wells Fargo RFP search results.
 *
 * Fields match the expected response document format from PDF pages 12-18:
 * - rankingScore: Oracle Text SCORE() relevance ranking
 * - ecn: Enterprise Customer Number (customerNumber as string)
 * - companyId: Customer Company Number
 * - entityType: INDIVIDUAL or NON_INDIVIDUAL
 * - name: Full name of customer
 * - alternateName: First name (individual) or business description (non-individual)
 * - taxIdNumber: Full SSN/EIN
 * - taxIdType: SSN, EIN, or ITIN
 * - birthDate: Date of birth (individuals only)
 * - addressLine: Street address
 * - cityName: City
 * - state: State code
 * - postalCode: ZIP code
 * - countryCode: Country code
 * - customerType: Customer, Prospect, or Youth Banking
 */
public class UcSearchResult {

    private final int rankingScore;
    private final String ecn;
    private final int companyId;
    private final String entityType;
    private final String name;
    private final String alternateName;
    private final String taxIdNumber;
    private final String taxIdType;
    private final String birthDate;
    private final String addressLine;
    private final String cityName;
    private final String state;
    private final String postalCode;
    private final String countryCode;
    private final String customerType;

    private UcSearchResult(Builder builder) {
        this.rankingScore = builder.rankingScore;
        this.ecn = builder.ecn;
        this.companyId = builder.companyId;
        this.entityType = builder.entityType;
        this.name = builder.name;
        this.alternateName = builder.alternateName;
        this.taxIdNumber = builder.taxIdNumber;
        this.taxIdType = builder.taxIdType;
        this.birthDate = builder.birthDate;
        this.addressLine = builder.addressLine;
        this.cityName = builder.cityName;
        this.state = builder.state;
        this.postalCode = builder.postalCode;
        this.countryCode = builder.countryCode;
        this.customerType = builder.customerType;
    }

    public int getRankingScore() {
        return rankingScore;
    }

    public String getEcn() {
        return ecn;
    }

    public int getCompanyId() {
        return companyId;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getName() {
        return name;
    }

    public String getAlternateName() {
        return alternateName;
    }

    public String getTaxIdNumber() {
        return taxIdNumber;
    }

    public String getTaxIdType() {
        return taxIdType;
    }

    public String getBirthDate() {
        return birthDate;
    }

    public String getAddressLine() {
        return addressLine;
    }

    public String getCityName() {
        return cityName;
    }

    public String getState() {
        return state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public String getCustomerType() {
        return customerType;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int rankingScore;
        private String ecn;
        private int companyId;
        private String entityType;
        private String name;
        private String alternateName;
        private String taxIdNumber;
        private String taxIdType;
        private String birthDate;
        private String addressLine;
        private String cityName;
        private String state;
        private String postalCode;
        private String countryCode;
        private String customerType;

        public Builder rankingScore(int rankingScore) {
            this.rankingScore = rankingScore;
            return this;
        }

        public Builder ecn(String ecn) {
            this.ecn = ecn;
            return this;
        }

        public Builder companyId(int companyId) {
            this.companyId = companyId;
            return this;
        }

        public Builder entityType(String entityType) {
            this.entityType = entityType;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder alternateName(String alternateName) {
            this.alternateName = alternateName;
            return this;
        }

        public Builder taxIdNumber(String taxIdNumber) {
            this.taxIdNumber = taxIdNumber;
            return this;
        }

        public Builder taxIdType(String taxIdType) {
            this.taxIdType = taxIdType;
            return this;
        }

        public Builder birthDate(String birthDate) {
            this.birthDate = birthDate;
            return this;
        }

        public Builder addressLine(String addressLine) {
            this.addressLine = addressLine;
            return this;
        }

        public Builder cityName(String cityName) {
            this.cityName = cityName;
            return this;
        }

        public Builder state(String state) {
            this.state = state;
            return this;
        }

        public Builder postalCode(String postalCode) {
            this.postalCode = postalCode;
            return this;
        }

        public Builder countryCode(String countryCode) {
            this.countryCode = countryCode;
            return this;
        }

        public Builder customerType(String customerType) {
            this.customerType = customerType;
            return this;
        }

        public UcSearchResult build() {
            return new UcSearchResult(this);
        }
    }

    @Override
    public String toString() {
        return "UcSearchResult{" +
                "rankingScore=" + rankingScore +
                ", ecn='" + ecn + '\'' +
                ", companyId=" + companyId +
                ", entityType='" + entityType + '\'' +
                ", name='" + name + '\'' +
                ", alternateName='" + alternateName + '\'' +
                ", taxIdNumber='" + maskTaxId(taxIdNumber) + '\'' +
                ", taxIdType='" + taxIdType + '\'' +
                ", birthDate='" + birthDate + '\'' +
                ", addressLine='" + addressLine + '\'' +
                ", cityName='" + cityName + '\'' +
                ", state='" + state + '\'' +
                ", postalCode='" + postalCode + '\'' +
                ", countryCode='" + countryCode + '\'' +
                ", customerType='" + customerType + '\'' +
                '}';
    }

    /**
     * Mask the tax ID for display purposes (show only last 4 digits).
     */
    private String maskTaxId(String taxId) {
        if (taxId == null || taxId.length() < 4) {
            return taxId;
        }
        return "***-**-" + taxId.substring(taxId.length() - 4);
    }

    /**
     * Returns a formatted string representation for display in benchmark results.
     */
    public String toDisplayString() {
        return String.format(
            "Score: %d | ECN: %s | Name: %s | Type: %s | TaxID: %s | City: %s, %s %s | CustomerType: %s",
            rankingScore, ecn, name, entityType, maskTaxId(taxIdNumber),
            cityName, state, postalCode, customerType
        );
    }
}
