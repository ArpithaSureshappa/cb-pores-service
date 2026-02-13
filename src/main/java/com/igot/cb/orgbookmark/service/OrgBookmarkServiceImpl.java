package com.igot.cb.orgbookmark.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.orgbookmark.entity.OrgBookmarkEntity;
import com.igot.cb.orgbookmark.repository.OrgBookmarkRepository;
import com.igot.cb.playlist.util.ProjectUtil;
import com.igot.cb.pores.Service.OutboundRequestHandlerServiceImpl;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.dto.CustomResponse;
import com.igot.cb.pores.dto.RespParam;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.mail.Message;
import java.io.InputStream;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class OrgBookmarkServiceImpl implements OrgBookmarkService {
    @Autowired
    private EsUtilService esUtilService;
    @Autowired
    private OrgBookmarkRepository orgBookmarkRepository;
    @Autowired
    private CacheService cacheService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RedisTemplate<String, SearchResult> redisTemplate;
    @Autowired
    private CbServerProperties cbServerProperties;
    @Autowired
    private AccessTokenValidator accessTokenValidator;
    @Autowired
    private OutboundRequestHandlerServiceImpl outboundRequestHandlerService;

    private Logger logger = LoggerFactory.getLogger(OrgBookmarkServiceImpl.class);

    @Override
    public ApiResponse createOrgBookmark(JsonNode orgDetails, String userAuthToken) {
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_ORG_BOOKMARK_CREATE);
        validatePayload(Constants.PAYLOAD_VALIDATION_FILE_ORG_BOOKMARK_LIST, orgDetails);
        try {
            String userId = accessTokenValidator.fetchUserIdFromAccessToken(userAuthToken);
            if (StringUtils.isBlank(userId)) {
                response.getParams().setStatus(Constants.FAILED);
                response.getParams().setErrMsg(Constants.USER_ID_DOESNT_EXIST);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }
            log.info("OrgBookmarkService::createOrgList:creating orgList");
            String id = String.valueOf(Uuids.timeBased());
            ((ObjectNode) orgDetails).put(Constants.IS_ACTIVE, Constants.ACTIVE_STATUS);
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            ((ObjectNode) orgDetails).put(Constants.CREATED_BY, userId);

            OrgBookmarkEntity jsonNodeEntity = new OrgBookmarkEntity();
            jsonNodeEntity.setOrgBookmarkId(id);
            jsonNodeEntity.setData(orgDetails);
            jsonNodeEntity.setCreatedOn(currentTime);
            jsonNodeEntity.setUpdatedOn(currentTime);

            String category = orgDetails.get(Constants.CATEGORY).asText();
            if (cbServerProperties.getBookmarkDuplicateNotAllowedCategory().contains(category)) {
                if (isActiveBookMarkAvailableForOrg(orgDetails)) {
                    response.getParams().setStatus(Constants.FAILED);
                    response.getParams().setErrMsg(MessageFormat.format(Constants.BOOKMARK_ALREADY_AVAILABLE, orgDetails.get(Constants.ORG_ID).asText(), category));
                    response.setResponseCode(HttpStatus.BAD_REQUEST);
                    return response;
                }
            }
            OrgBookmarkEntity saveJsonEntity = orgBookmarkRepository.save(jsonNodeEntity);

            ObjectMapper objectMapper = new ObjectMapper();
            ObjectNode jsonNode = objectMapper.createObjectNode();
            jsonNode.set(Constants.ORG_BOOKMARK_ID, new TextNode(saveJsonEntity.getOrgBookmarkId()));
            jsonNode.setAll((ObjectNode) saveJsonEntity.getData());
            jsonNode.set(Constants.CREATED_ON, new TextNode(convertTimeStampToDate(jsonNodeEntity.getCreatedOn().getTime())));
            jsonNode.set(Constants.UPDATED_ON, new TextNode(convertTimeStampToDate(jsonNodeEntity.getUpdatedOn().getTime())));


            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.addDocument(Constants.INDEX_NAME_FOR_ORG_BOOKMARK, Constants.INDEX_TYPE, id, map, cbServerProperties.getElasticBookmarkJsonPath());

            JsonNode orgListNode = orgDetails.get(Constants.ORG_LIST);
            List<String> orgList = objectMapper.convertValue(orgListNode, List.class);
            List<Map<String, Object>> orgSearchList = searchOrg(Constants.IDENTIFIER, orgList);
            ((ObjectNode) saveJsonEntity.getData()).put(Constants.ORG_LIST, objectMapper.convertValue(orgSearchList, JsonNode.class));

            cacheService.putCache(Constants.REDIS_ORG_BOOKMARK_KEY + Constants.UNDER_SCORE  + jsonNodeEntity.getOrgBookmarkId(), saveJsonEntity.getData());
            log.info("org List created");
            response.setResponseCode(HttpStatus.OK);
            response.put(Constants.RESPONSE, Constants.SUCCESS);
            response.setResponseCode(HttpStatus.OK);
            response.getResult().put(Constants.STATUS, Constants.SUCCESSFULLY_CREATED);
            response.getResult().put(Constants.ORG_BOOKMARK_ID, id);
            return response;
        } catch (Exception e) {
            logger.error("Error occurred while creating orgList", e);
            throw new CustomException("error while processing", e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public ApiResponse updateOrgBookmark(JsonNode orgDetails, String userAuthToken) {
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_ORG_BOOKMARK_UPDATE);
        validatePayload(Constants.PAYLOAD_VALIDATION_UPDATE_FILE_ORG_BOOKMARK_LIST, orgDetails);
        try {
            String userId = accessTokenValidator.verifyUserToken(userAuthToken);
            if (StringUtils.isBlank(userId)) {
                response.getParams().setStatus(Constants.FAILED);
                response.getParams().setErrMsg(Constants.USER_ID_DOESNT_EXIST);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            log.info("OrgBookmarkService::createOrgList:creating orgList");
            String orgBookmarkId = orgDetails.get(Constants.ORG_BOOKMARK_ID).asText();
            Optional<OrgBookmarkEntity> orgBookmarkEntity = orgBookmarkRepository.findById(orgBookmarkId);
            OrgBookmarkEntity orgBookmarkEntityUpdated = null;
            if (orgBookmarkEntity.isPresent()) {
                JsonNode dataNode = orgBookmarkEntity.get().getData();
                Iterator<Map.Entry<String, JsonNode>> fields = orgDetails.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String fieldName = field.getKey();
                    // Check if the field is present in the update JsonNode
                    if (dataNode.has(fieldName)) {
                        // Update the main JsonNode with the value from the update JsonNode
                        ((ObjectNode) dataNode).set(fieldName, orgDetails.get(fieldName));
                    } else {
                        ((ObjectNode) dataNode).put(fieldName, orgDetails.get(fieldName));
                    }
                }
                ((ObjectNode) dataNode).remove(Constants.ORG_BOOKMARK_ID);
                orgBookmarkEntity.get().setUpdatedOn(currentTime);
                orgBookmarkEntityUpdated = orgBookmarkRepository.save(orgBookmarkEntity.get());
            } else {
                log.error("OrgBookmarkEntity not found for ID: {}", orgBookmarkId);
                response.getParams().setStatus(Constants.FAILED);
                response.getParams().setErrMsg("Org bookmark not found");
                response.setResponseCode(HttpStatus.NOT_FOUND);
                return response;
            }

            ObjectMapper objectMapper = new ObjectMapper();
            ObjectNode jsonNode = objectMapper.createObjectNode();
            jsonNode.set(Constants.ORG_BOOKMARK_ID, new TextNode(orgBookmarkId));
            jsonNode.setAll((ObjectNode) orgBookmarkEntityUpdated.getData());
            jsonNode.set(Constants.CREATED_ON, new TextNode(convertTimeStampToDate(orgBookmarkEntityUpdated.getCreatedOn().getTime())));
            jsonNode.set(Constants.UPDATED_ON, new TextNode(convertTimeStampToDate(orgBookmarkEntityUpdated.getUpdatedOn().getTime())));

            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.INDEX_NAME_FOR_ORG_BOOKMARK, Constants.INDEX_TYPE, orgBookmarkId, map, cbServerProperties.getElasticBookmarkJsonPath());

            JsonNode orgListNode = orgDetails.get(Constants.ORG_LIST);
            List<String> orgList = objectMapper.convertValue(orgListNode, List.class);
            List<Map<String, Object>> orgSearchList = searchOrg(Constants.IDENTIFIER, orgList);
            ((ObjectNode) orgBookmarkEntityUpdated.getData()).put(Constants.ORG_LIST, objectMapper.convertValue(orgSearchList, JsonNode.class));

            cacheService.putCache(Constants.REDIS_ORG_BOOKMARK_KEY + Constants.UNDER_SCORE  + orgBookmarkEntityUpdated.getOrgBookmarkId(), orgBookmarkEntityUpdated.getData());
            log.info("org List created");
            response.setResponseCode(HttpStatus.OK);
            response.put(Constants.RESPONSE, Constants.SUCCESS);
            response.setResponseCode(HttpStatus.OK);
            response.getResult().put(Constants.STATUS, Constants.SUCCESSFULLY_UPDATED);
            response.getResult().put(Constants.ORG_BOOKMARK_ID, orgBookmarkId);
            return response;
        } catch (Exception e) {
            logger.error("Error occurred while creating orgList", e);
            throw new CustomException("error while processing", e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public ApiResponse readOrgBookmarkById(String id) {
        log.info("reading orgList");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_ORG_BOOKMARK_READ);
        if (StringUtils.isEmpty(id)) {
            logger.error("Id not found");
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            response.getParams().setErrMsg(Constants.ID_NOT_FOUND);
            return response;
        }
        try {
            String cachedJson = cacheService.getCache(Constants.REDIS_ORG_BOOKMARK_KEY + Constants.UNDER_SCORE  + id);
            if (StringUtils.isNotEmpty(cachedJson)) {
                log.info("Record coming from redis cache");
                response.getParams().setErrMsg(Constants.SUCCESSFULLY_READING);
                response
                        .getResult()
                        .put(Constants.DATA, objectMapper.readValue(cachedJson, new TypeReference<Object>() {
                        }));
            } else {
                Optional<OrgBookmarkEntity> entityOptional = orgBookmarkRepository.findById(id);
                if (entityOptional.isPresent()) {
                    OrgBookmarkEntity orgBookmarkEntity = entityOptional.get();
                    JsonNode orgListNode = ((ObjectNode) orgBookmarkEntity.getData()).get(Constants.ORG_LIST);
                    List<String> orgList = objectMapper.convertValue(orgListNode, List.class);
                    List<Map<String, Object>> orgSearchList = searchOrg(Constants.IDENTIFIER, orgList);
                    ((ObjectNode) orgBookmarkEntity.getData()).put(Constants.ORG_LIST, objectMapper.convertValue(orgSearchList, JsonNode.class));
                    cacheService.putCache(Constants.REDIS_ORG_BOOKMARK_KEY + Constants.UNDER_SCORE  + orgBookmarkEntity.getOrgBookmarkId(), orgBookmarkEntity.getData());
                    log.info("Record coming from postgres db");
                    response.getParams().setErrMsg(Constants.SUCCESSFULLY_READING);
                    response
                            .getResult()
                            .put(Constants.DATA,
                                    objectMapper.convertValue(
                                            orgBookmarkEntity.getData(), new TypeReference<Object>() {
                                            }));
                } else {
                    logger.error("Invalid Id: {}", id);
                    response.setResponseCode(HttpStatus.NOT_FOUND);
                    response.getParams().setErrMsg(Constants.INVALID_ID);
                }
            }
        } catch (Exception e) {
            logger.error("Error while mapping JSON for id {}: {}", id, e.getMessage(), e);
            throw new CustomException(Constants.ERROR, "error while processing", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return response;
    }

    @Override
    public CustomResponse search(SearchCriteria searchCriteria) {
        log.info("OrgBookmarkServiceImpl::searchOrg");
        CustomResponse response = new CustomResponse();
        SearchResult searchResult = redisTemplate.opsForValue().get(generateRedisJwtTokenKey(searchCriteria));
        if (searchResult != null) {
            log.info("SidJobServiceImpl::searchJobs: job search result fetched from redis");
            response.getResult().put(Constants.RESULT, searchResult);
            createSuccessResponse(response);
            return response;
        }
        String searchString = searchCriteria.getSearchString();
        if (searchString != null && searchString.length() < 2) {
            createErrorResponse(response, "Minimum 3 characters are required to search",
                    HttpStatus.BAD_REQUEST,
                    Constants.FAILED_CONST);
            return response;
        }
        try {
            searchResult =
                    esUtilService.searchDocuments(Constants.INDEX_NAME_FOR_ORG_BOOKMARK, searchCriteria);
            response.getResult().putAll(objectMapper.convertValue(searchResult, Map.class));
            createSuccessResponse(response);
            return response;
        } catch (Exception e) {
            createErrorResponse(response, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR, Constants.FAILED_CONST);
            redisTemplate.opsForValue()
                    .set(generateRedisJwtTokenKey(searchCriteria), searchResult, cbServerProperties.getSearchResultRedisTtl(),
                            TimeUnit.SECONDS);
            return response;
        }
    }

    public String generateRedisJwtTokenKey(Object requestPayload) {
        if (requestPayload != null) {
            try {
                String reqJsonString = objectMapper.writeValueAsString(requestPayload);
                return JWT.create()
                        .withClaim(Constants.REQUEST_PAYLOAD, reqJsonString)
                        .sign(Algorithm.HMAC256(cbServerProperties.getJwtSecretKey()));
            } catch (JsonProcessingException e) {
                logger.error("Error occurred while converting json object to json string", e);
            }
        }
        return "";
    }

    @Override
    public ApiResponse deleteOrgBookmarkById(String id) {
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_ORG_BOOKMARK_DELETE);
        log.info("OrgBookmarkServiceImpl::delete");
        try {
            if (StringUtils.isNotEmpty(id)) {
                Optional<OrgBookmarkEntity> entityOptional = orgBookmarkRepository.findById(id);
                if (entityOptional.isPresent()) {
                    OrgBookmarkEntity josnEntity = entityOptional.get();
                    JsonNode data = josnEntity.getData();
                    Timestamp currentTime = new Timestamp(System.currentTimeMillis());
                    if (data.get(Constants.IS_ACTIVE).asBoolean()) {
                        ((ObjectNode) data).put(Constants.IS_ACTIVE, false);
                        josnEntity.setData(data);
                        josnEntity.setOrgBookmarkId(id);
                        josnEntity.setUpdatedOn(currentTime);
                        OrgBookmarkEntity updateJsonEntity = orgBookmarkRepository.save(josnEntity);
                        Map<String, Object> map = objectMapper.convertValue(data, Map.class);
                        esUtilService.updateDocument(Constants.INDEX_NAME_FOR_ORG_BOOKMARK, Constants.INDEX_TYPE, id, map, cbServerProperties.getElasticBookmarkJsonPath());
                        cacheService.putCache(id, data);

                        logger.debug("orgList details deleted successfully");
                        response.setResponseCode(HttpStatus.OK);
                        response.put(Constants.RESPONSE, Constants.SUCCESS);
                        response.setResponseCode(HttpStatus.OK);
                        response.getResult().put(Constants.STATUS, Constants.DELETED_SUCCESSFULLY);
                        response.getResult().put(Constants.ORG_BOOKMARK_ID, id);
                    } else {
                        log.error("OrgList is already inactive.");
                        response.put(Constants.RESPONSE, Constants.FAILED);
                        response.setResponseCode(HttpStatus.BAD_REQUEST);
                        response.getParams().setErrMsg(Constants.ALREADY_INACTIVE);
                        response.getResult().put(Constants.ORG_BOOKMARK_ID, id);
                    }
                } else {
                    log.error("No able to find bookmark {}", id);
                    response.put(Constants.RESPONSE, Constants.FAILED);
                    response.setResponseCode(HttpStatus.BAD_REQUEST);
                    response.getParams().setErrMsg(Constants.INVALID_ID);
                    response.getResult().put(Constants.ORG_BOOKMARK_ID, id);
                }
            }
        } catch (Exception e) {
            logger.error("Error while deleting org with ID: {}. Exception: {}", id, e.getMessage(), e);
            response.getParams().setStatus(Constants.FAILED);
            response.getParams().setErrMsg(e.getMessage());
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return response;
    }

    public void validatePayload(String fileName, JsonNode payload) {
        try {
            JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance();
            InputStream schemaStream = schemaFactory.getClass().getResourceAsStream(fileName);
            JsonSchema schema = schemaFactory.getSchema(schemaStream);

            Set<ValidationMessage> validationMessages = schema.validate(payload);
            if (!validationMessages.isEmpty()) {
                StringBuilder errorMessage = new StringBuilder("Validation error(s): \n");
                for (ValidationMessage message : validationMessages) {
                    errorMessage.append(message.getMessage()).append("\n");
                }
                logger.error("Validation Error", errorMessage.toString());
                throw new CustomException("Validation Error", errorMessage.toString(), HttpStatus.BAD_REQUEST);
            }
        } catch (Exception e) {
            logger.error("Failed to validate payload", e);
            throw new CustomException("Failed to validate payload", e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    public void createSuccessResponse(CustomResponse response) {
        response.setParams(new RespParam());
        response.getParams().setStatus(Constants.SUCCESS);
        response.setResponseCode(HttpStatus.OK);
    }

    public void createErrorResponse(
            CustomResponse response, String errorMessage, HttpStatus httpStatus, String status) {
        response.setParams(new RespParam());
        //response.getParams().setErrorMsg(errorMessage);
        response.getParams().setStatus(status);
        response.setResponseCode(httpStatus);
    }

    private List<Map<String, Object>> searchOrg(String key, List<String> value) {
        // request body
        Map<String, Object> requestObj = new HashMap<>();
        Map<String, Object> reqMap = new HashMap<>();
        Map<String, Object> filtersMap = new HashMap<>();
        filtersMap.put(key, value);

        reqMap.put(Constants.FILTERS, filtersMap);
        requestObj.put(Constants.REQUEST, reqMap);

        HashMap<String, String> headersValue = new HashMap<>();
        headersValue.put(Constants.CONTENT_TYPE_KEY, "application/json");
        headersValue.put(Constants.AUTHORIZATION, cbServerProperties.getSbApiKey());

        try {
            String url = cbServerProperties.getLearnerServiceUrl() + cbServerProperties.getOrgSearchPath();

            Map<String, Object> response = outboundRequestHandlerService.fetchResultUsingPost(url, requestObj,
                    headersValue);
            if (response != null && "OK".equalsIgnoreCase((String) response.get("responseCode"))) {
                Map<String, Object> map = (Map<String, Object>) response.get("result");
                if (map.get("response") != null) {
                    Map<String, Object> responseObj = (Map<String, Object>) map.get("response");
                    if (MapUtils.isNotEmpty(responseObj)) {
                        List<Map<String, Object>> responseContent = (List<Map<String, Object>>) responseObj.get(Constants.CONTENT);
                        if (CollectionUtils.isNotEmpty(responseContent)) {
                            return responseContent;
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new CustomException("Failed to validate payload", e.getMessage(), HttpStatus.BAD_REQUEST);
        }
        return new ArrayList<>();
    }

    private String convertTimeStampToDate(long timeStamp) {
        Instant instant = Instant.ofEpochMilli(timeStamp);
        OffsetDateTime dateTime = instant.atOffset(ZoneOffset.UTC);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy'T'HH:mm:ss.SSS'Z'");
        return dateTime.format(formatter);
    }

    private boolean isActiveBookMarkAvailableForOrg(JsonNode jsonNode) {
        String category = jsonNode.get(Constants.CATEGORY).asText();
        String orgId = jsonNode.get(Constants.ORG_ID).asText();
        SearchCriteria searchCriteria = new SearchCriteria();
        HashMap<String, Object> search = new HashMap<>();
        search.put(Constants.CATEGORY, category);
        search.put(Constants.ORG_ID, orgId);
        search.put(Constants.IS_ACTIVE, Constants.ACTIVE_STATUS);

        searchCriteria.setFilterCriteriaMap(search);
        CustomResponse response = search(searchCriteria);
        if (ObjectUtils.isNotEmpty(response)) {
            Long responseCount = (Long) response.getResult().get(Constants.TOTAL_COUNT);
            if (responseCount != null && responseCount.intValue() != 0) {
                return true;
            }
        }
        return false;
    }
}
