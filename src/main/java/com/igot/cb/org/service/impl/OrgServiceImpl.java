package com.igot.cb.org.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.demand.service.DemandService;
import com.igot.cb.demand.service.DemandServiceImpl;
import com.igot.cb.org.service.OrgService;
import com.igot.cb.playlist.util.ProjectUtil;
import com.igot.cb.pores.Service.OutboundRequestHandlerServiceImpl;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.*;
import com.igot.cb.producer.Producer;
import com.igot.cb.transactional.cassandrautils.CassandraOperation;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
public class OrgServiceImpl implements OrgService {

    @Autowired
    PayloadValidation payloadValidation;

    @Autowired
    CassandraOperation cassandraOperation;

    @Autowired
    OutboundRequestHandlerServiceImpl outboundRequestHandlerServiceImpl;

    @Autowired
    CbServerProperties cbServerProperties;

    @Autowired
    AccessTokenValidator accessTokenValidator;

    @Autowired
    DemandService demandService;

    @Autowired
    Producer kafkaProducer;

    @Override
    public ApiResponse readFramework(String frameworkName, String orgId, String termName, String userAuthToken) {
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_ORG_CREATE);
        try {
            if (StringUtils.isBlank(frameworkName) || StringUtils.isBlank(orgId)) {
                response.getParams().setStatus(Constants.FAILED);
                response.getParams().setErrMsg("OrgID and FrameworkId is Missing");
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }
            String userId = accessTokenValidator.verifyUserToken(userAuthToken);
            if (StringUtils.isBlank(userId)) {
                response.getParams().setStatus(Constants.FAILED);
                response.getParams().setErrMsg(Constants.USER_ID_DOESNT_EXIST);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }
            if (!demandService.isSpvRequest(userId,Constants.MDO_ADMIN)) {
                response.getParams().setStatus(Constants.FAILED);
                response.getParams().setErrMsg("User does not have the required role:");
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }
            Map<String, Object> propertyMap = new HashMap<>();
            propertyMap.put(Constants.ID, orgId);
            List<Map<String, Object>> orgDetails = cassandraOperation.getRecordsByPropertiesWithoutFiltering(Constants.KEYSPACE_SUNBIRD, Constants.ORG_TABLE, propertyMap, null, 1);
            if (CollectionUtils.isEmpty(orgDetails)) {
                response.getParams().setStatus(Constants.FAILED);
                response.getParams().setErrMsg("Organization not found");
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }
            String fwName = (String) orgDetails.get(0).get(Constants.FRAMEWORKID);
            if (StringUtils.isBlank(fwName)) {
                Map<String,Object> dataMap = new HashMap<>();
                dataMap.put("orgId",orgId);
                dataMap.put("frameworkName",frameworkName);
                dataMap.put("termName",termName);
                log.info("printing createReq {}",dataMap);
                kafkaProducer.push(cbServerProperties.getTopicFrameworkCreate(),dataMap);
                log.info("kafka message pushed for broadcast type");
                response.getResult().put(Constants.FRAMEWORK, "Framework creation request has been published Awaiting processing.");
                response.setResponseCode(HttpStatus.OK);
            } else {
                response.getResult().put(Constants.FRAMEWORK, fwName);
                response.setResponseCode(HttpStatus.OK);
            }
        } catch (CustomException e) {
            response.getParams().setErr(e.getMessage());
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.getParams().setStatus(Constants.FAILED);
            log.error("Payload validation failed: " + e.getMessage());
        } catch(Exception e) {
            response.getParams().setErr("Failed to read framework: " + e.getMessage());
            response.getParams().setStatus(Constants.FAILED);
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return response;
    }

    private ApiResponse frameworkRead(String frameworkId) {
        ApiResponse response = ProjectUtil.createDefaultResponse("");
        try {
            StringBuilder strUrl = new StringBuilder(cbServerProperties.getKnowledgeMS());
            strUrl.append(cbServerProperties.getOdcsFrameworkRead()).append("/").append(frameworkId);
            Map<String, Object> framworkResponse = (Map<String, Object>) outboundRequestHandlerServiceImpl.fetchResult(strUrl.toString());
            if (null != framworkResponse) {
                if (Constants.OK.equalsIgnoreCase((String) framworkResponse.get(Constants.RESPONSE_CODE))) {
                    Map<String, Object> resultMap = (Map<String, Object>) framworkResponse.get(Constants.RESULT);
                    Map<String, Object> framework = (Map<String, Object>) resultMap.get(Constants.FRAMEWORK);
                    response.getResult().put(Constants.FRAMEWORK, framework);
                } else {
                    response.setResponseCode(HttpStatus.NOT_FOUND);
                    response.getParams().setErr("Data not found with id : " + frameworkId);
                }
            } else {
                response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
                response.getParams().setErr("Failed to read the framework details for Id : " + frameworkId);
            }
        } catch (Exception e) {
            log.error("Failed to read framework with Id: " + frameworkId, e);
            response.getParams().setErr("Failed to read framework: " + e.getMessage());
            response.getParams().setStatus(Constants.FAILED);
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return response;
    }

    private static List<Map<String, String>> createChannels(String channelId) {
        Map<String, String> channel = createChannel(channelId);
        List<Map<String, String>> channels = new ArrayList<>();
        channels.add(channel);
        return channels;
    }

    private static Map<String, String> createChannel(String channelId) {
        Map<String, String> channel = new HashMap<>();
        channel.put(Constants.IDENTIFIER, channelId);
        return channel;
    }
}