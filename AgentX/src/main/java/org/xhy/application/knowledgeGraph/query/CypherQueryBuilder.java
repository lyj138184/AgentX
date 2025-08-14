package org.xhy.application.knowledgeGraph.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xhy.application.knowledgeGraph.dto.GraphQueryRequest;
import org.xhy.infrastructure.exception.BusinessException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Cypher动态查询构建器
 * 负责将GraphQueryRequest转换为安全的、参数化的Cypher查询
 * 
 * @author zang
 */
@Component
public class CypherQueryBuilder {

    private static final Logger logger = LoggerFactory.getLogger(CypherQueryBuilder.class);

    private StringBuilder cypherBuilder;
    private Map<String, Object> parameters;
    private int aliasCounter;

    /**
     * 构建完整的Cypher查询
     * 
     * @param request 图查询请求
     * @return 查询结果包装
     */
    public QueryResult buildQuery(GraphQueryRequest request) {
        try {
            // 初始化构建器状态
            reset();
            
            validateRequest(request);
            
            // 1. 构建MATCH子句 - 起始节点
            buildStartNodeMatch(request.getStartNodes());
            
            // 2. 构建遍历路径
            buildTraversalPath(request.getTraversals());
            
            // 3. 构建WHERE子句 - 过滤条件
            buildWhereClause(request.getFilters());
            
            // 4. 构建RETURN子句
            buildReturnClause(request.getReturnDefinition());
            
            // 5. 添加LIMIT子句
            buildLimitClause(request.getLimit());
            
            String finalQuery = cypherBuilder.toString();
            
            logger.debug("构建的Cypher查询: {}", finalQuery);
            logger.debug("查询参数: {}", parameters);
            
            return new QueryResult(finalQuery, parameters);
            
        } catch (Exception e) {
            logger.error("构建Cypher查询失败: {}", e.getMessage(), e);
            throw new BusinessException("查询构建失败: " + e.getMessage(), e);
        }
    }

    /**
     * 重置构建器状态
     */
    private void reset() {
        cypherBuilder = new StringBuilder();
        parameters = new HashMap<>();
        aliasCounter = 0;
    }

    /**
     * 验证请求参数
     */
    private void validateRequest(GraphQueryRequest request) {
        if (request == null) {
            throw new BusinessException("查询请求不能为空");
        }
        
        if (request.getStartNodes() == null || request.getStartNodes().isEmpty()) {
            throw new BusinessException("起始节点不能为空");
        }
    }

    /**
     * 构建起始节点匹配子句
     */
    private void buildStartNodeMatch(List<GraphQueryRequest.NodeFilter> startNodes) {
        cypherBuilder.append("MATCH ");
        
        List<String> matchPatterns = new ArrayList<>();
        
        for (int i = 0; i < startNodes.size(); i++) {
            GraphQueryRequest.NodeFilter nodeFilter = startNodes.get(i);
            String nodeAlias = "n" + i;
            
            StringBuilder nodePattern = new StringBuilder();
            nodePattern.append("(").append(nodeAlias);
            
            // 添加标签
            if (nodeFilter.getLabel() != null && !nodeFilter.getLabel().trim().isEmpty()) {
                nodePattern.append(":").append(sanitizeLabel(nodeFilter.getLabel()));
            }
            
            nodePattern.append(")");
            matchPatterns.add(nodePattern.toString());
            
            // 如果有属性过滤条件，添加到WHERE子句中
            if (nodeFilter.getProperty() != null && nodeFilter.getValue() != null) {
                CypherSpecification spec = CypherSpecifications.fromNodeFilter(nodeFilter);
                addWhereCondition(spec, nodeAlias);
            }
        }
        
        cypherBuilder.append(String.join(", ", matchPatterns));
    }

    /**
     * 构建遍历路径
     */
    private void buildTraversalPath(List<GraphQueryRequest.TraversalStep> traversals) {
        if (traversals == null || traversals.isEmpty()) {
            return;
        }
        
        for (GraphQueryRequest.TraversalStep traversal : traversals) {
            cypherBuilder.append("\n");
            buildTraversalStep(traversal);
        }
    }

    /**
     * 构建单个遍历步骤
     */
    private void buildTraversalStep(GraphQueryRequest.TraversalStep traversal) {
        String relationshipAlias = "r" + (++aliasCounter);
        String targetNodeAlias = "m" + aliasCounter;
        
        cypherBuilder.append("OPTIONAL MATCH ");
        
        // 构建遍历模式
        String relationshipType = traversal.getRelationshipType();
        String direction = traversal.getDirection();
        Integer minHops = traversal.getMinHops();
        Integer maxHops = traversal.getMaxHops();
        
        StringBuilder pattern = new StringBuilder();
        
        switch (direction.toUpperCase()) {
            case "OUTGOING":
                pattern.append("(n0)-[").append(relationshipAlias);
                if (relationshipType != null && !relationshipType.equals("*")) {
                    pattern.append(":").append(sanitizeRelationshipType(relationshipType));
                }
                if (minHops != null && maxHops != null && (minHops != 1 || maxHops != 1)) {
                    pattern.append("*").append(minHops).append("..").append(maxHops);
                }
                pattern.append("]->(").append(targetNodeAlias).append(")");
                break;
                
            case "INCOMING":
                pattern.append("(n0)<-[").append(relationshipAlias);
                if (relationshipType != null && !relationshipType.equals("*")) {
                    pattern.append(":").append(sanitizeRelationshipType(relationshipType));
                }
                if (minHops != null && maxHops != null && (minHops != 1 || maxHops != 1)) {
                    pattern.append("*").append(minHops).append("..").append(maxHops);
                }
                pattern.append("]-(").append(targetNodeAlias).append(")");
                break;
                
            case "BOTH":
                pattern.append("(n0)-[").append(relationshipAlias);
                if (relationshipType != null && !relationshipType.equals("*")) {
                    pattern.append(":").append(sanitizeRelationshipType(relationshipType));
                }
                if (minHops != null && maxHops != null && (minHops != 1 || maxHops != 1)) {
                    pattern.append("*").append(minHops).append("..").append(maxHops);
                }
                pattern.append("]-(").append(targetNodeAlias).append(")");
                break;
                
            default:
                throw new BusinessException("不支持的遍历方向: " + direction);
        }
        
        cypherBuilder.append(pattern);
    }

    /**
     * 构建WHERE子句
     */
    private void buildWhereClause(List<GraphQueryRequest.QueryFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return;
        }
        
        List<CypherSpecification> specifications = new ArrayList<>();
        
        for (GraphQueryRequest.QueryFilter filter : filters) {
            CypherSpecification spec = CypherSpecifications.fromQueryFilter(filter);
            specifications.add(spec);
        }
        
        if (!specifications.isEmpty()) {
            cypherBuilder.append("\nWHERE ");
            
            // 组合所有规约
            CypherSpecification combinedSpec = specifications.get(0);
            for (int i = 1; i < specifications.size(); i++) {
                combinedSpec = combinedSpec.and(specifications.get(i));
            }
            
            String whereClause = combinedSpec.toCypher("n0");
            cypherBuilder.append(whereClause);
            
            // 添加参数
            parameters.putAll(combinedSpec.getParameters());
        }
    }

    /**
     * 添加WHERE条件
     */
    private void addWhereCondition(CypherSpecification spec, String alias) {
        if (cypherBuilder.toString().contains("WHERE")) {
            cypherBuilder.append(" AND ");
        } else {
            cypherBuilder.append("\nWHERE ");
        }
        
        cypherBuilder.append(spec.toCypher(alias));
        parameters.putAll(spec.getParameters());
    }

    /**
     * 构建RETURN子句
     */
    private void buildReturnClause(GraphQueryRequest.ReturnDefinition returnDef) {
        cypherBuilder.append("\nRETURN ");
        
        List<String> returnItems = new ArrayList<>();
        
        if (returnDef == null || returnDef.isIncludeNodes()) {
            returnItems.add("n0");
            
            // 如果有遍历路径，也返回目标节点
            if (aliasCounter > 0) {
                IntStream.rangeClosed(1, aliasCounter)
                        .forEach(i -> returnItems.add("m" + i));
            }
        }
        
        if (returnDef == null || returnDef.isIncludeRelationships()) {
            if (aliasCounter > 0) {
                IntStream.rangeClosed(1, aliasCounter)
                        .forEach(i -> returnItems.add("r" + i));
            }
        }
        
        if (returnItems.isEmpty()) {
            returnItems.add("n0"); // 至少返回起始节点
        }
        
        cypherBuilder.append(String.join(", ", returnItems));
    }

    /**
     * 构建LIMIT子句
     */
    private void buildLimitClause(Integer limit) {
        if (limit != null && limit > 0) {
            cypherBuilder.append("\nLIMIT ").append(limit);
        }
    }

    /**
     * 清理标签名称，防止注入
     */
    private String sanitizeLabel(String label) {
        if (label == null) {
            return "";
        }
        // 移除特殊字符，只允许字母、数字、下划线和中文
        return label.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]", "");
    }

    /**
     * 清理关系类型名称，防止注入
     */
    private String sanitizeRelationshipType(String relationshipType) {
        if (relationshipType == null) {
            return "";
        }
        // 移除特殊字符，只允许字母、数字、下划线和中文
        return relationshipType.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]", "");
    }

    /**
     * 查询结果包装类
     */
    public static class QueryResult {
        private final String cypher;
        private final Map<String, Object> parameters;

        public QueryResult(String cypher, Map<String, Object> parameters) {
            this.cypher = cypher;
            this.parameters = parameters;
        }

        public String getCypher() {
            return cypher;
        }

        public Map<String, Object> getParameters() {
            return parameters;
        }

        @Override
        public String toString() {
            return "QueryResult{" +
                    "cypher='" + cypher + '\'' +
                    ", parameters=" + parameters +
                    '}';
        }
    }
}