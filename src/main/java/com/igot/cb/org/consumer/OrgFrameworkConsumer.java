package com.igot.cb.org.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.demand.service.DemandServiceImpl;
import com.igot.cb.playlist.util.ProjectUtil;
import com.igot.cb.pores.Service.OutboundRequestHandlerServiceImpl;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.transactional.cassandrautils.CassandraOperation;
import com.igot.cb.transactional.service.RequestHandlerServiceImpl;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.elasticsearch.common.recycler.Recycler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
public class OrgFrameworkConsumer {

    private ObjectMapper mapper = new ObjectMapper();

    private Logger logger = LoggerFactory.getLogger(OrgFrameworkConsumer.class);

    @Autowired
    private RequestHandlerServiceImpl requestHandlerService;

    @Autowired
    private CassandraOperation cassandraOperation;

    @Autowired
    private CbServerProperties configuration;

    @Autowired
    OutboundRequestHandlerServiceImpl outboundRequestHandlerServiceImpl;

    @KafkaListener(groupId = "${kafka.topic.framework.create.group}", topics = "${kafka.topic.framework.create}")
    public void OrgFrameworkCreateConsumer(ConsumerRecord<String, String> data) {
        try {
            Map<String, Object> request = mapper.readValue(data.value(), HashMap.class);
            CompletableFuture.runAsync(() -> {
                processFrameworkCreate(request);
            });
        } catch (Exception e) {
            logger.error("Failed to read request. Message received : " + data.value(), e);
        }
    }

    public void processFrameworkCreate(Map<String,Object> dataMap) {
        try {
            String orgId = (String) dataMap.get("orgId");
            String frameworkName = (String) dataMap.get("frameworkName");
            String termName = (String) dataMap.get("termName");
            Map<String, Object> createReq = createFrameworkRequest(orgId, frameworkName);
            Map<String, Object> request = new HashMap<>();
            request.put(Constants.REQUEST, createReq);
            Map<String, String> headers = new HashMap<>();
            headers.put(Constants.X_CHANNEL_ID, orgId);
            StringBuilder strUrl = new StringBuilder(configuration.getKnowledgeMS());
            strUrl.append(configuration.getFrameworkCopy()).append("/").append(frameworkName);
            logger.info("Printing URL for copy: {}", strUrl);
            logger.info("Printing request: {}", request);
            Map<String, Object> frameworkResponse = (Map<String, Object>) outboundRequestHandlerServiceImpl.fetchResultUsingPost(strUrl.toString(),
                    request, headers);
            String responseCode = (String) frameworkResponse.get(Constants.RESPONSE_CODE);
            if (responseCode.equals(Constants.OK)) {
                Map<String, Object> result = (Map<String, Object>) frameworkResponse.get(Constants.RESULT);
                String fwName = (String) result.getOrDefault(Constants.NODE_ID, "");
                Map<String, Object> map = new HashMap<>();
                map.put(Constants.FRAMEWORKID, fwName);
                map.put(Constants.ID, orgId);
                map.put(Constants.FRAMEWORK_STATUS, Constants.COMPLETED);
                Map<String, Object> updateOrgDetails = cassandraOperation.updateRecord(Constants.KEYSPACE_SUNBIRD, Constants.ORG_TABLE, map);
                String updateResponse = (String) updateOrgDetails.get(Constants.RESPONSE);
                if (!StringUtils.isBlank(updateResponse) && updateResponse.equalsIgnoreCase(Constants.SUCCESS)) {
                    logger.info("Updated framework_id in organization table successfully with name: {}", fwName);
                    createOrgTerm(termName, fwName, frameworkName);
                    publishFramework(fwName, orgId);
                } else {
                    logger.error("Failed to update organization details with the new framework ID");
                }
            } else {
                logger.error("Failed to copy the framework: {}", frameworkResponse.get(Constants.RESPONSE_CODE));
            }
        } catch (Exception e) {
            logger.error("Unexpected error occurred in processFrameworkCreate", e);
        }
    }


    private String frameworkRead(String frameworkId) {
        String code = null;
        try {
            StringBuilder strUrl = new StringBuilder(configuration.getKnowledgeMS());
            strUrl.append(configuration.getOdcsFrameworkRead()).append("/").append(frameworkId);
            logger.info("prinitng framework read url "+strUrl.toString());
            Map<String, Object> framworkResponse = (Map<String, Object>) outboundRequestHandlerServiceImpl.fetchResult(strUrl.toString());
            if (null != framworkResponse) {
                if (Constants.OK.equalsIgnoreCase((String) framworkResponse.get(Constants.RESPONSE_CODE))) {
                    Map<String, Object> resultMap = (Map<String, Object>) framworkResponse.get(Constants.RESULT);
                    Map<String, Object> framework = (Map<String, Object>) resultMap.get(Constants.FRAMEWORK);
                    List categoriesList = (List<Map<String,Object>>) framework.get("categories");
                    Map<String,Object> map = (Map<String,Object>) categoriesList.get(0);
                    code = (String) map.get(Constants.CODE);
                    return code;
                } else {
                    logger.info("Data not found with id : " + frameworkId);
                }
            } else {
                logger.info("Data not found with ID: {}", frameworkId);
            }
        } catch (Exception e) {
            logger.error("Failed to read framework with ID: {}", frameworkId, e);
        }
        return code;
    }

    private void createOrgTerm(String termName, String framework, String masterFramework) {
        try {
            String category = frameworkRead(masterFramework);
            logger.info("category first "+category);
            if (StringUtils.isNotEmpty(category)) {
                Map<String, Object> termMap = createTermMap(termName, category);
                Map<String, Object> requestMap = createRequestMap(termMap);
                Map<String, Object> outerMap = createOuterMap(requestMap);
                StringBuilder strUrl = new StringBuilder(configuration.getKnowledgeMS());
                strUrl.append(configuration.getOdcsTermCrete()).append("?framework=")
                        .append(framework).append("&category=")
                        .append(category);
                Map<String, Object> termResponse = (Map<String, Object>) outboundRequestHandlerServiceImpl.fetchResultUsingPost(strUrl.toString(),
                        outerMap);
                if (termResponse != null
                        && Constants.OK.equalsIgnoreCase((String) termResponse.get(Constants.RESPONSE_CODE))) {
                    Map<String, Object> resultMap = (Map<String, Object>) termResponse.get(Constants.RESULT);
                    List<String> termIdentifier = (List<String>) resultMap.getOrDefault(Constants.NODE_ID, "");
                    logger.info("Created term successfully with name: {}", termName);
                    logger.info("Term identifier: {}", termIdentifier);
                } else {
                    logger.info("Unable to create term with name: {}", termName);
                }
            }
        } catch (Exception e) {
            logger.error("Unexpected error occurred while creating term", e);
        }
    }

    private void publishFramework(String fwName, String orgId) {
        try {
            StringBuilder strUrl = new StringBuilder(configuration.getKnowledgeMS());
            strUrl.append(configuration.getFrameworkPublish()).append("/").append(fwName);
            Map<String, String> headers = new HashMap<>();
            headers.put(Constants.X_CHANNEL_ID, orgId);
            Map<String, Object> response = outboundRequestHandlerServiceImpl.fetchResultUsingPost(strUrl.toString(), "", headers);
            if (response != null
                    && Constants.OK.equalsIgnoreCase((String) response.get(Constants.RESPONSE_CODE))) {
                logger.info("Published the framework: {}", fwName);
            } else {
                logger.info("Unable to publish the framework with name: {}", fwName);
            }
        }catch (Exception e) {
            logger.error("Unexpected error occurred while publishing the framework", e);
        }
    }


    public static Map<String, Object> createFrameworkRequest(String channelId, String frameworkName) {
        Map<String, Object> framework = createFramework(channelId, frameworkName);
        Map<String, Object> request = new HashMap<>();
        request.put("framework", framework);
        return request;
    }

    private static Map<String, Object> createFramework(String channelId, String frameworkName) {
        Map<String, Object> framework = new HashMap<>();
        StringBuilder name = new StringBuilder(channelId).append("_").append(frameworkName).append(Constants.MASTER);
        framework.put(Constants.NAME, name);
        framework.put(Constants.DESCRIPTION, "Master Framework Copy");
        framework.put(Constants.CODE, name);
        framework.put(Constants.OWNER, channelId);
        return framework;
    }

    public static Map<String, Object> createRequestMap(Map<String, Object> termMap) {
        Map<String, Object> requestMap = new HashMap<>();
        requestMap.put("term", termMap);
        return requestMap;
    }

    public static Map<String, Object> createOuterMap(Map<String, Object> requestMap) {
        Map<String, Object> outerMap = new HashMap<>();
        outerMap.put("request", requestMap);
        return outerMap;
    }

    public static Map<String, Object> createTermMap(String termName, String category) {
        Map<String, Object> termMap = new HashMap<>();
        termMap.put(Constants.NAME, termName);
        termMap.put(Constants.DESCRIPTION, termName);
        termMap.put(Constants.CODE, UUID.randomUUID());
        termMap.put(Constants.REF_TYPE, "");
        termMap.put(Constants.REF_ID, "");
        termMap.put(Constants.CATEGORY, category);
        return termMap;
    }


}
