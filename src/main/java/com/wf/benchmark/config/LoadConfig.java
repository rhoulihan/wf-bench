package com.wf.benchmark.config;

import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public class LoadConfig {

    public enum Scale {
        SMALL(10_000),
        MEDIUM(100_000),
        LARGE(1_000_000),
        XLARGE(10_000_000);

        private final long identityCount;

        Scale(long identityCount) {
            this.identityCount = identityCount;
        }

        public long getIdentityCount() {
            return identityCount;
        }
    }

    // Connection settings
    private ConnectionConfig connection = new ConnectionConfig();

    // Data volume
    private Scale scale = Scale.MEDIUM;
    private long identityCount = -1; // -1 means use scale
    private double addressRatio = 1.0;
    private double phoneRatio = 2.5;
    private double accountRatio = 1.5; // 1.5 accounts per identity

    // Performance tuning
    private int threads = 4;
    private int batchSize = 1000;
    private boolean dropExisting = false;
    private String collectionPrefix = "";

    // Write concern
    private String writeConcern = "acknowledged";
    private boolean ordered = false;

    // Data generation options
    private double individualRatio = 0.7;
    private int minAddressesPerCustomer = 1;
    private int maxAddressesPerCustomer = 4;
    private int minPhonesPerCustomer = 1;
    private int maxPhonesPerCustomer = 5;

    // Progress reporting
    private int progressInterval = 5000;
    private boolean quiet = false;

    public LoadConfig() {
    }

    public static LoadConfig fromYaml(String filePath) throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream input = new FileInputStream(filePath)) {
            Map<String, Object> data = yaml.load(input);
            return fromMap(data);
        }
    }

    @SuppressWarnings("unchecked")
    private static LoadConfig fromMap(Map<String, Object> data) {
        LoadConfig config = new LoadConfig();

        if (data.containsKey("connection")) {
            Map<String, Object> conn = (Map<String, Object>) data.get("connection");
            if (conn.containsKey("connectionString")) {
                config.connection.setConnectionString((String) conn.get("connectionString"));
            }
            if (conn.containsKey("database")) {
                config.connection.setDatabase((String) conn.get("database"));
            }
            if (conn.containsKey("connectionPoolSize")) {
                config.connection.setConnectionPoolSize((Integer) conn.get("connectionPoolSize"));
            }
        }

        if (data.containsKey("load")) {
            Map<String, Object> load = (Map<String, Object>) data.get("load");

            if (load.containsKey("scale")) {
                config.scale = Scale.valueOf(((String) load.get("scale")).toUpperCase());
            }
            if (load.containsKey("identityCount")) {
                config.identityCount = ((Number) load.get("identityCount")).longValue();
            }
            if (load.containsKey("addressRatio")) {
                config.addressRatio = ((Number) load.get("addressRatio")).doubleValue();
            }
            if (load.containsKey("phoneRatio")) {
                config.phoneRatio = ((Number) load.get("phoneRatio")).doubleValue();
            }
            if (load.containsKey("accountRatio")) {
                config.accountRatio = ((Number) load.get("accountRatio")).doubleValue();
            }
            if (load.containsKey("threads")) {
                config.threads = (Integer) load.get("threads");
            }
            if (load.containsKey("batchSize")) {
                config.batchSize = (Integer) load.get("batchSize");
            }
            if (load.containsKey("dropExisting")) {
                config.dropExisting = (Boolean) load.get("dropExisting");
            }
            if (load.containsKey("collectionPrefix")) {
                config.collectionPrefix = (String) load.get("collectionPrefix");
            }
            if (load.containsKey("writeConcern")) {
                config.writeConcern = (String) load.get("writeConcern");
            }
            if (load.containsKey("ordered")) {
                config.ordered = (Boolean) load.get("ordered");
            }
            if (load.containsKey("progressInterval")) {
                config.progressInterval = (Integer) load.get("progressInterval");
            }
            if (load.containsKey("quiet")) {
                config.quiet = (Boolean) load.get("quiet");
            }

            if (load.containsKey("dataGeneration")) {
                Map<String, Object> gen = (Map<String, Object>) load.get("dataGeneration");
                if (gen.containsKey("individualRatio")) {
                    config.individualRatio = ((Number) gen.get("individualRatio")).doubleValue();
                }
                if (gen.containsKey("addressesPerCustomer")) {
                    Map<String, Object> addr = (Map<String, Object>) gen.get("addressesPerCustomer");
                    if (addr.containsKey("min")) {
                        config.minAddressesPerCustomer = (Integer) addr.get("min");
                    }
                    if (addr.containsKey("max")) {
                        config.maxAddressesPerCustomer = (Integer) addr.get("max");
                    }
                }
                if (gen.containsKey("phonesPerCustomer")) {
                    Map<String, Object> phone = (Map<String, Object>) gen.get("phonesPerCustomer");
                    if (phone.containsKey("min")) {
                        config.minPhonesPerCustomer = (Integer) phone.get("min");
                    }
                    if (phone.containsKey("max")) {
                        config.maxPhonesPerCustomer = (Integer) phone.get("max");
                    }
                }
            }
        }

        return config;
    }

    public long getEffectiveIdentityCount() {
        return identityCount > 0 ? identityCount : scale.getIdentityCount();
    }

    public long getAddressCount() {
        return (long) (getEffectiveIdentityCount() * addressRatio);
    }

    public long getPhoneCount() {
        return (long) (getEffectiveIdentityCount() * phoneRatio);
    }

    public long getAccountCount() {
        return (long) (getEffectiveIdentityCount() * accountRatio);
    }

    // Getters and setters
    public ConnectionConfig getConnection() {
        return connection;
    }

    public void setConnection(ConnectionConfig connection) {
        this.connection = connection;
    }

    public Scale getScale() {
        return scale;
    }

    public void setScale(Scale scale) {
        this.scale = scale;
    }

    public long getIdentityCount() {
        return identityCount;
    }

    public void setIdentityCount(long identityCount) {
        this.identityCount = identityCount;
    }

    public double getAddressRatio() {
        return addressRatio;
    }

    public void setAddressRatio(double addressRatio) {
        this.addressRatio = addressRatio;
    }

    public double getPhoneRatio() {
        return phoneRatio;
    }

    public void setPhoneRatio(double phoneRatio) {
        this.phoneRatio = phoneRatio;
    }

    public double getAccountRatio() {
        return accountRatio;
    }

    public void setAccountRatio(double accountRatio) {
        this.accountRatio = accountRatio;
    }

    public int getThreads() {
        return threads;
    }

    public void setThreads(int threads) {
        this.threads = threads;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public boolean isDropExisting() {
        return dropExisting;
    }

    public void setDropExisting(boolean dropExisting) {
        this.dropExisting = dropExisting;
    }

    public String getCollectionPrefix() {
        return collectionPrefix;
    }

    public void setCollectionPrefix(String collectionPrefix) {
        this.collectionPrefix = collectionPrefix;
    }

    public String getWriteConcern() {
        return writeConcern;
    }

    public void setWriteConcern(String writeConcern) {
        this.writeConcern = writeConcern;
    }

    public boolean isOrdered() {
        return ordered;
    }

    public void setOrdered(boolean ordered) {
        this.ordered = ordered;
    }

    public double getIndividualRatio() {
        return individualRatio;
    }

    public void setIndividualRatio(double individualRatio) {
        this.individualRatio = individualRatio;
    }

    public int getMinAddressesPerCustomer() {
        return minAddressesPerCustomer;
    }

    public void setMinAddressesPerCustomer(int minAddressesPerCustomer) {
        this.minAddressesPerCustomer = minAddressesPerCustomer;
    }

    public int getMaxAddressesPerCustomer() {
        return maxAddressesPerCustomer;
    }

    public void setMaxAddressesPerCustomer(int maxAddressesPerCustomer) {
        this.maxAddressesPerCustomer = maxAddressesPerCustomer;
    }

    public int getMinPhonesPerCustomer() {
        return minPhonesPerCustomer;
    }

    public void setMinPhonesPerCustomer(int minPhonesPerCustomer) {
        this.minPhonesPerCustomer = minPhonesPerCustomer;
    }

    public int getMaxPhonesPerCustomer() {
        return maxPhonesPerCustomer;
    }

    public void setMaxPhonesPerCustomer(int maxPhonesPerCustomer) {
        this.maxPhonesPerCustomer = maxPhonesPerCustomer;
    }

    public int getProgressInterval() {
        return progressInterval;
    }

    public void setProgressInterval(int progressInterval) {
        this.progressInterval = progressInterval;
    }

    public boolean isQuiet() {
        return quiet;
    }

    public void setQuiet(boolean quiet) {
        this.quiet = quiet;
    }
}
