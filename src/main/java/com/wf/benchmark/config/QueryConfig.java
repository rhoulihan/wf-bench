package com.wf.benchmark.config;

import org.bson.Document;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class QueryConfig {

    private ConnectionConfig connection = new ConnectionConfig();
    private QueryExecution queryExecution = new QueryExecution();
    private List<IndexDefinition> indexes = new ArrayList<>();
    private List<QueryDefinition> queries = new ArrayList<>();

    public static class QueryExecution {
        private int iterations = 10;
        private int warmupIterations = 3;
        private int threads = 1;
        private boolean includeExplainPlan = false;

        public int getIterations() {
            return iterations;
        }

        public void setIterations(int iterations) {
            this.iterations = iterations;
        }

        public int getWarmupIterations() {
            return warmupIterations;
        }

        public void setWarmupIterations(int warmupIterations) {
            this.warmupIterations = warmupIterations;
        }

        public int getThreads() {
            return threads;
        }

        public void setThreads(int threads) {
            this.threads = threads;
        }

        public boolean isIncludeExplainPlan() {
            return includeExplainPlan;
        }

        public void setIncludeExplainPlan(boolean includeExplainPlan) {
            this.includeExplainPlan = includeExplainPlan;
        }
    }

    public static class IndexDefinition {
        private String collection;
        private String name;
        private Document keys;
        private Document options;

        public String getCollection() {
            return collection;
        }

        public void setCollection(String collection) {
            this.collection = collection;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Document getKeys() {
            return keys;
        }

        public void setKeys(Document keys) {
            this.keys = keys;
        }

        public Document getOptions() {
            return options;
        }

        public void setOptions(Document options) {
            this.options = options;
        }
    }

    public static class QueryDefinition {
        private String name;
        private String description;
        private String collection;
        private String type = "find"; // find, aggregate, count
        private Document filter;
        private Document projection;
        private List<Document> pipeline;
        private Integer limit;
        private Document sort;
        private Map<String, ParameterDefinition> parameters;
        private String requiresIndex;
        private Integer expectedResults;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getCollection() {
            return collection;
        }

        public void setCollection(String collection) {
            this.collection = collection;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Document getFilter() {
            return filter;
        }

        public void setFilter(Document filter) {
            this.filter = filter;
        }

        public Document getProjection() {
            return projection;
        }

        public void setProjection(Document projection) {
            this.projection = projection;
        }

        public List<Document> getPipeline() {
            return pipeline;
        }

        public void setPipeline(List<Document> pipeline) {
            this.pipeline = pipeline;
        }

        public Integer getLimit() {
            return limit;
        }

        public void setLimit(Integer limit) {
            this.limit = limit;
        }

        public Document getSort() {
            return sort;
        }

        public void setSort(Document sort) {
            this.sort = sort;
        }

        public Map<String, ParameterDefinition> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, ParameterDefinition> parameters) {
            this.parameters = parameters;
        }

        public String getRequiresIndex() {
            return requiresIndex;
        }

        public void setRequiresIndex(String requiresIndex) {
            this.requiresIndex = requiresIndex;
        }

        public Integer getExpectedResults() {
            return expectedResults;
        }

        public void setExpectedResults(Integer expectedResults) {
            this.expectedResults = expectedResults;
        }
    }

    public static class ParameterDefinition {
        private String type; // random_range, random_choice, sequential, fixed
        private Long min;
        private Long max;
        private List<Object> values;
        private Object fixedValue;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Long getMin() {
            return min;
        }

        public void setMin(Long min) {
            this.min = min;
        }

        public Long getMax() {
            return max;
        }

        public void setMax(Long max) {
            this.max = max;
        }

        public List<Object> getValues() {
            return values;
        }

        public void setValues(List<Object> values) {
            this.values = values;
        }

        public Object getFixedValue() {
            return fixedValue;
        }

        public void setFixedValue(Object fixedValue) {
            this.fixedValue = fixedValue;
        }
    }

    public static QueryConfig fromYaml(String filePath) throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream input = new FileInputStream(filePath)) {
            Map<String, Object> data = yaml.load(input);
            return fromMap(data);
        }
    }

    @SuppressWarnings("unchecked")
    private static QueryConfig fromMap(Map<String, Object> data) {
        QueryConfig config = new QueryConfig();

        // Parse connection
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

        // Parse query execution settings
        if (data.containsKey("queryExecution")) {
            Map<String, Object> exec = (Map<String, Object>) data.get("queryExecution");
            if (exec.containsKey("iterations")) {
                config.queryExecution.setIterations((Integer) exec.get("iterations"));
            }
            if (exec.containsKey("warmupIterations")) {
                config.queryExecution.setWarmupIterations((Integer) exec.get("warmupIterations"));
            }
            if (exec.containsKey("threads")) {
                config.queryExecution.setThreads((Integer) exec.get("threads"));
            }
            if (exec.containsKey("includeExplainPlan")) {
                config.queryExecution.setIncludeExplainPlan((Boolean) exec.get("includeExplainPlan"));
            }
        }

        // Parse indexes
        if (data.containsKey("indexes")) {
            List<Map<String, Object>> indexList = (List<Map<String, Object>>) data.get("indexes");
            for (Map<String, Object> idx : indexList) {
                IndexDefinition indexDef = new IndexDefinition();
                indexDef.setCollection((String) idx.get("collection"));
                indexDef.setName((String) idx.get("name"));
                if (idx.containsKey("keys")) {
                    indexDef.setKeys(new Document((Map<String, Object>) idx.get("keys")));
                }
                if (idx.containsKey("options")) {
                    indexDef.setOptions(new Document((Map<String, Object>) idx.get("options")));
                }
                config.indexes.add(indexDef);
            }
        }

        // Parse queries
        if (data.containsKey("queries")) {
            List<Map<String, Object>> queryList = (List<Map<String, Object>>) data.get("queries");
            for (Map<String, Object> q : queryList) {
                QueryDefinition queryDef = new QueryDefinition();
                queryDef.setName((String) q.get("name"));
                queryDef.setDescription((String) q.get("description"));
                queryDef.setCollection((String) q.get("collection"));
                if (q.containsKey("type")) {
                    queryDef.setType((String) q.get("type"));
                }
                if (q.containsKey("filter")) {
                    queryDef.setFilter(new Document((Map<String, Object>) q.get("filter")));
                }
                if (q.containsKey("projection")) {
                    queryDef.setProjection(new Document((Map<String, Object>) q.get("projection")));
                }
                if (q.containsKey("pipeline")) {
                    List<Map<String, Object>> pipelineList = (List<Map<String, Object>>) q.get("pipeline");
                    List<Document> pipeline = new ArrayList<>();
                    for (Map<String, Object> stage : pipelineList) {
                        pipeline.add(new Document(stage));
                    }
                    queryDef.setPipeline(pipeline);
                }
                if (q.containsKey("limit")) {
                    queryDef.setLimit((Integer) q.get("limit"));
                }
                if (q.containsKey("sort")) {
                    queryDef.setSort(new Document((Map<String, Object>) q.get("sort")));
                }
                if (q.containsKey("parameters")) {
                    Map<String, Map<String, Object>> params = (Map<String, Map<String, Object>>) q.get("parameters");
                    Map<String, ParameterDefinition> paramDefs = new java.util.HashMap<>();
                    for (Map.Entry<String, Map<String, Object>> entry : params.entrySet()) {
                        ParameterDefinition paramDef = new ParameterDefinition();
                        Map<String, Object> p = entry.getValue();
                        paramDef.setType((String) p.get("type"));
                        if (p.containsKey("min")) {
                            paramDef.setMin(((Number) p.get("min")).longValue());
                        }
                        if (p.containsKey("max")) {
                            paramDef.setMax(((Number) p.get("max")).longValue());
                        }
                        if (p.containsKey("values")) {
                            paramDef.setValues((List<Object>) p.get("values"));
                        }
                        if (p.containsKey("value")) {
                            paramDef.setFixedValue(p.get("value"));
                        }
                        paramDefs.put(entry.getKey(), paramDef);
                    }
                    queryDef.setParameters(paramDefs);
                }
                if (q.containsKey("requiresIndex")) {
                    queryDef.setRequiresIndex((String) q.get("requiresIndex"));
                }
                if (q.containsKey("expectedResults")) {
                    queryDef.setExpectedResults((Integer) q.get("expectedResults"));
                }
                config.queries.add(queryDef);
            }
        }

        return config;
    }

    // Getters and setters
    public ConnectionConfig getConnection() {
        return connection;
    }

    public void setConnection(ConnectionConfig connection) {
        this.connection = connection;
    }

    public QueryExecution getQueryExecution() {
        return queryExecution;
    }

    public void setQueryExecution(QueryExecution queryExecution) {
        this.queryExecution = queryExecution;
    }

    public List<IndexDefinition> getIndexes() {
        return indexes;
    }

    public void setIndexes(List<IndexDefinition> indexes) {
        this.indexes = indexes;
    }

    public List<QueryDefinition> getQueries() {
        return queries;
    }

    public void setQueries(List<QueryDefinition> queries) {
        this.queries = queries;
    }
}
