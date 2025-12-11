package com.wf.benchmark.search;

/**
 * Result from SQL join queries for UC scenarios.
 * Contains fields from joined collections (phone, identity, account).
 */
public class SqlJoinSearchResult {

    private final String customerNumber;
    private final String phoneNumber;
    private final String fullName;
    private final String ssnLast4;
    private final String accountNumber;
    private final String accountNumberLast4;
    private final String email;

    private SqlJoinSearchResult(Builder builder) {
        this.customerNumber = builder.customerNumber;
        this.phoneNumber = builder.phoneNumber;
        this.fullName = builder.fullName;
        this.ssnLast4 = builder.ssnLast4;
        this.accountNumber = builder.accountNumber;
        this.accountNumberLast4 = builder.accountNumberLast4;
        this.email = builder.email;
    }

    public String getCustomerNumber() {
        return customerNumber;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getFullName() {
        return fullName;
    }

    public String getSsnLast4() {
        return ssnLast4;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getAccountNumberLast4() {
        return accountNumberLast4;
    }

    public String getEmail() {
        return email;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String customerNumber;
        private String phoneNumber;
        private String fullName;
        private String ssnLast4;
        private String accountNumber;
        private String accountNumberLast4;
        private String email;

        public Builder customerNumber(String customerNumber) {
            this.customerNumber = customerNumber;
            return this;
        }

        public Builder phoneNumber(String phoneNumber) {
            this.phoneNumber = phoneNumber;
            return this;
        }

        public Builder fullName(String fullName) {
            this.fullName = fullName;
            return this;
        }

        public Builder ssnLast4(String ssnLast4) {
            this.ssnLast4 = ssnLast4;
            return this;
        }

        public Builder accountNumber(String accountNumber) {
            this.accountNumber = accountNumber;
            return this;
        }

        public Builder accountNumberLast4(String accountNumberLast4) {
            this.accountNumberLast4 = accountNumberLast4;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        public SqlJoinSearchResult build() {
            return new SqlJoinSearchResult(this);
        }
    }

    @Override
    public String toString() {
        return "SqlJoinSearchResult{" +
                "customerNumber='" + customerNumber + '\'' +
                ", phoneNumber='" + phoneNumber + '\'' +
                ", fullName='" + fullName + '\'' +
                ", ssnLast4='" + ssnLast4 + '\'' +
                ", accountNumber='" + accountNumber + '\'' +
                ", accountNumberLast4='" + accountNumberLast4 + '\'' +
                ", email='" + email + '\'' +
                '}';
    }
}
