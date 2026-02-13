package com.igot.cb.cios.service.impl;


import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.cios.dto.ObjectDto;
import com.igot.cb.cios.entity.CiosContentEntity;
import com.igot.cb.cios.repository.CiosRepository;
import com.igot.cb.cios.service.CiosContentService;
import com.igot.cb.cios.util.CiosRequestPayloadValidation;
import com.igot.cb.contentpartner.repository.ContentPartnerRepository;
import com.igot.cb.contentpartner.service.ContentPartnerService;
import com.igot.cb.playlist.util.ProjectUtil;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.pores.util.PayloadValidation;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.util.CollectionUtils;


@Service
@Slf4j
public class CiosContentServiceImpl implements CiosContentService {
    private static long environmentId = 10000000;
    private static String shardId = "1";
    private static AtomicInteger aInteger = new AtomicInteger(1);

    @Autowired
    private CiosRepository ciosRepository;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    EsUtilService esUtilService;

    @Autowired
    private PayloadValidation payloadValidation;

    @Autowired
    private RedisTemplate<String, SearchResult> redisTemplate;

    @Value("${search.result.redis.ttl}")
    private long searchResultRedisTtl;

    @Autowired
    private CbServerProperties cbServerProperties;

    @Autowired
    private CacheService cacheService;

    @Autowired
    private CiosRequestPayloadValidation ciosRequestPayloadValidation;

    @Autowired
    private ContentPartnerRepository contentPartnerRepository;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ContentPartnerService contentPartnerService;

    public String generateId() {
        long env = environmentId / 10000000;
        long uid = System.currentTimeMillis();
        uid = uid << 13;
        return Constants.ID_PREFIX + env + "" + uid + "" + shardId + "" + aInteger.getAndIncrement();
    }

    @Override
    public Object fetchDataByContentId(String contentId) {
        log.info("getting content by id: " + contentId);
        if (StringUtils.isEmpty(contentId)) {
            log.error("CiosContentServiceImpl::read:Id not found");
            throw new CustomException(Constants.ERROR, "contentId is mandatory", HttpStatus.BAD_REQUEST);
        }
        String cachedJson = cacheService.getCache(contentId);
        Object response = null;
        if (StringUtils.isNotEmpty(cachedJson)) {
            log.info("CiosContentServiceImpl::read:Record coming from redis cache");
            try {
                response = objectMapper.readValue(cachedJson, new TypeReference<Object>() {
                });
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        } else {
            Optional<CiosContentEntity> optionalJsonNodeEntity = ciosRepository.findByContentId(contentId);
            if (optionalJsonNodeEntity.isPresent()) {
                CiosContentEntity ciosContentEntity = optionalJsonNodeEntity.get();
                cacheService.putCache(contentId, ciosContentEntity.getCiosData());
                log.info("CiosContentServiceImpl::read:Record coming from postgres db");
                response = objectMapper.convertValue(ciosContentEntity.getCiosData(), new TypeReference<Object>() {
                });
            } else {
                log.error("Invalid Id: {}", contentId);
                throw new CustomException(Constants.ERROR, "No data found for given Id", HttpStatus.BAD_REQUEST);
            }
        }
        return response;
    }

    @Override
    public Object deleteContent(String contentId) {
        log.info("CiosContentServiceImpl::read:inside the method");
        Optional<CiosContentEntity> ciosContentEntity = ciosRepository.findByContentIdAndIsActive(
                contentId, true);
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        if (ciosContentEntity.isPresent()) {
            CiosContentEntity fetchedEntity = ciosContentEntity.get();
            JsonNode fetchedJsonData = fetchedEntity.getCiosData();
            String partnerCode = fetchedJsonData.path(Constants.CONTENT).path("contentPartner").get("partnerCode").asText();
            ((ObjectNode) fetchedJsonData.path(Constants.CONTENT)).put(Constants.UPDATED_ON, String.valueOf(currentTime));
            ((ObjectNode) fetchedJsonData.path(Constants.CONTENT)).put(Constants.IS_ACTIVE, Constants.ACTIVE_STATUS_FALSE);
            ((ObjectNode) fetchedJsonData.path(Constants.CONTENT)).put(Constants.STATUS, Constants.DRAFT);
            fetchedEntity.setCiosData(fetchedJsonData);
            fetchedEntity.setLastUpdatedOn(currentTime);
            fetchedEntity.setIsActive(false);
            ciosRepository.save(fetchedEntity);
            apiCallToCiosSecondaryDbForUpdateData(fetchedJsonData);
            fetchAndUpdateContentCountsInPartnerDb(partnerCode);
            Map<String, Object> map = objectMapper.convertValue(fetchedEntity.getCiosData().get(Constants.CONTENT), Map.class);
            esUtilService.addDocument(Constants.CIOS_INDEX_NAME, Constants.INDEX_TYPE, fetchedEntity.getContentId(), map, cbServerProperties.getElasticCiosJsonPath());
            cacheService.deleteCache(fetchedEntity.getContentId());
            log.info("deleted content");
            return "Content with id : " + contentId + " is deleted";
        } else {
            log.error("no data found");
            throw new CustomException(Constants.ERROR, Constants.NO_DATA_FOUND, HttpStatus.NOT_FOUND);
        }

    }

    @Override
    public Object fetchDataByExternalIdAndPartnerId(String externalid,String partnerid) {
        log.info("getting content by extid: {} and parterid: {} ",externalid,partnerid);
        if (StringUtils.isEmpty(externalid)) {
            log.error("CiosContentServiceImpl::read:Id not found");
            throw new CustomException(Constants.ERROR, "externalid is mandatory", HttpStatus.BAD_REQUEST);
        }
        String id=externalid+"_"+partnerid;
        String cachedJson = cacheService.getCache(id);
        Object response = null;
        if (StringUtils.isNotEmpty(cachedJson)) {
            log.info("CiosContentServiceImpl::read:Record coming from redis cache");
            try {
                response = objectMapper.readValue(cachedJson, new TypeReference<Object>() {
                });
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        } else {
            Optional<CiosContentEntity> optionalJsonNodeEntity = ciosRepository.findByExternalIdAndPartnerId(externalid,partnerid);
            if (optionalJsonNodeEntity.isPresent()) {
                CiosContentEntity ciosContentEntity = optionalJsonNodeEntity.get();
                cacheService.putCache(id, ciosContentEntity.getCiosData());
                log.info("CiosContentServiceImpl::read:Record coming from postgres db");
                response = objectMapper.convertValue(ciosContentEntity.getCiosData(), new TypeReference<Object>() {
                });
            } else {
                log.error("Invalid Id: {}", externalid);
                throw new CustomException(Constants.ERROR, "No data found for given Id", HttpStatus.BAD_REQUEST);
            }
        }
        return response;
    }


    @Override
    public ApiResponse onboardContent(List<ObjectDto> data) {
        log.info("CiosContentServiceImpl::createOrUpdateContent");
        ApiResponse apiResponse = ProjectUtil.createDefaultResponse(Constants.API_CIOS_CURATION_CREATE);
        try {
            Timestamp timestamp = new Timestamp(System.currentTimeMillis());
            String partnerCode = null;
            for (ObjectDto eachData : data) {
                partnerCode = eachData.getContentPartner().get("partnerCode").asText();
                JsonNode jsonNode = eachData.getContentData();
                payloadValidation.validatePayload(Constants.CIOS_CONTENT_VALIDATION_FILE_JSON, jsonNode);
                ObjectNode contentNode = (ObjectNode) jsonNode.path(Constants.CONTENT);
                updateContentWithRequiredFields(contentNode, timestamp, eachData);
                if (Constants.DRAFT.equalsIgnoreCase(eachData.getStatus())) {
                    log.info("Status of the data {}", eachData.getStatus());
                    contentNode.put(Constants.IS_ACTIVE, Constants.ACTIVE_STATUS_FALSE);
                    contentNode.put(Constants.PUBLISHED_ON, "0000-00-00 00:00:00.000");
                    contentNode.put(Constants.CREATED_DATE, timestamp.toString());
                    apiCallToCiosSecondaryDbForUpdateData(jsonNode);
                } else if (eachData.getStatus().equals("live")) {
                    log.info("Status of the data {}", eachData.getStatus());
                    contentNode.put(Constants.IS_ACTIVE, Constants.ACTIVE_STATUS);
                    contentNode.put(Constants.PUBLISHED_ON, timestamp.toString());
                    contentNode.put(Constants.UPDATED_DATE, timestamp.toString());
                    apiCallToCiosSecondaryDbForUpdateData(jsonNode);
                    CiosContentEntity ciosContentEntity = createNewContent(jsonNode);
                    ciosRepository.save(ciosContentEntity);
                    log.info("Id of content created: {}", ciosContentEntity.getContentId());
                    Map<String, Object> map = objectMapper.convertValue(ciosContentEntity.getCiosData().get(Constants.CONTENT), Map.class);
                    log.debug("map value for elastic search {}", map);
                    cacheService.putCache(ciosContentEntity.getContentId(), ciosContentEntity.getCiosData());
                    cacheService.putCache(ciosContentEntity.getExternalId() + "_" + ciosContentEntity.getPartnerId(), ciosContentEntity.getCiosData());
                    esUtilService.addDocument(Constants.CIOS_INDEX_NAME, Constants.INDEX_TYPE, ciosContentEntity.getContentId(), map, cbServerProperties.getElasticCiosJsonPath());
                } else {
                    apiResponse.getParams().setErrMsg(Constants.STATUS_NOT_VALID);
                    apiResponse.getParams().setStatus(Constants.FAILED);
                    apiResponse.setResponseCode(HttpStatus.BAD_REQUEST);
                    return apiResponse;
                }
            }
            fetchAndUpdateContentCountsInPartnerDb(partnerCode);
            Map<String, Object> result = new HashMap<>();
            result.put("ApiResponse", "All data curated successfully");
            apiResponse.setResult(result);
            return apiResponse;
        } catch (Exception e) {
            apiResponse.getParams().setErrMsg(e.getMessage());
            apiResponse.getParams().setStatus(Constants.FAILED);
            apiResponse.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return apiResponse;
        }
    }

    private void fetchAndUpdateContentCountsInPartnerDb(String partnerCode) {
        log.info("CiosContentServiceImpl::fetchAndUpdateContentCountsInPartnerDb:inside");
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode filterCriteriaMap = objectMapper.createObjectNode();
        filterCriteriaMap.put(Constants.PARTNERCODE, partnerCode);
        ArrayNode requestedFields = objectMapper.createArrayNode();
        requestedFields.add(Constants.EXTERNAL_ID);
        ArrayNode facets = objectMapper.createArrayNode();
        facets.add(Constants.STATUS);
        payload.set(Constants.FILTER_CRITERIA_MAP, filterCriteriaMap);
        payload.set(Constants.REQUESTED_FIELDS, requestedFields);
        payload.set(Constants.FACETS, facets);
        payload.put(Constants.PAGE_NUMBER, 0);
        payload.put(Constants.PAGE_SIZE, 1);
        JsonNode node = callCiosSearchApiToGetStatusCount(payload);
        if (node == null || !node.hasNonNull(Constants.TOTAL_COUNT) || !node.has(Constants.FACETS) ||
                !node.get(Constants.FACETS).has(Constants.STATUS)) {
            log.warn("Search API returned null or invalid structure for partnerCode: {}", partnerCode);
            return;
        }
        Long totalCount = node.get(Constants.TOTAL_COUNT).asLong();
        Long draftCount = 0L;
        Long liveCount = 0L;
        JsonNode facetsResult = node.get(Constants.FACETS).get(Constants.STATUS);
        for (JsonNode facet : facetsResult) {
            String value = facet.get(Constants.VALUE).asText();
            Long count = facet.get(Constants.COUNT).asLong();
            if (Constants.DRAFT.equalsIgnoreCase(value)) {
                draftCount = count;
            } else if ("live".equalsIgnoreCase(value)) {
                liveCount = count;
            }
        }
        log.info("Total count: {}, Draft count: {}, Live count: {}", totalCount, draftCount, liveCount);
        ApiResponse response = contentPartnerService.getContentDetailsByPartnerCode(partnerCode);
        Map<String, Object> contentPartnerResponse = response.getResult();
        if (contentPartnerResponse != null && contentPartnerResponse.containsKey(Constants.DATA)) {
            Map<String, Object> contentPartnerResponseData = (Map<String, Object>) contentPartnerResponse.get("data");
            contentPartnerResponseData.put(Constants.TOTAL_COURSES_COUNT, totalCount);
            contentPartnerResponseData.put(Constants.DRAFT_COURSES_COUNT, draftCount);
            contentPartnerResponseData.put(Constants.LIVE_COURSES_COUNT, liveCount);
            JsonNode contentPartnerRequestData = objectMapper.convertValue(contentPartnerResponse, JsonNode.class);
            contentPartnerService.createOrUpdate(contentPartnerRequestData);
        } else {
            log.error("No data found in the response.");
        }
    }

    private JsonNode callCiosSearchApiToGetStatusCount(JsonNode jsonNode) {
        String apiUrl = cbServerProperties.getCiosContentServiceHost()+cbServerProperties.getCiosContentServiceSearchApiUrl();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        HttpEntity<JsonNode> entity = new HttpEntity<>(jsonNode, headers);
        ResponseEntity<JsonNode> response = restTemplate.exchange(apiUrl, HttpMethod.POST, entity, JsonNode.class);
        return response.getBody();
    }

    private JsonNode apiCallToCiosSecondaryDbForUpdateData(JsonNode jsonNode) {
        log.info("CiosContentServiceImpl::apiCallToCiosSecondaryDbForUpdateData:inside");
        String apiUrl = cbServerProperties.getCiosContentServiceHost()+cbServerProperties.getCiosContentServiceUpdateApiUrl();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        HttpEntity<JsonNode> entity = new HttpEntity<>(jsonNode, headers);
        ResponseEntity<JsonNode> response = restTemplate.exchange(apiUrl, HttpMethod.POST, entity, JsonNode.class);
        return response.getBody();
    }

    private CiosContentEntity createNewContent(JsonNode ciosRequestInput) {
        log.info("SidJobServiceImpl::createOrUpdateContent:updating the content");
        try {
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            CiosContentEntity igotContent = new CiosContentEntity();
            String externalId = ciosRequestInput.path(Constants.CONTENT).path("externalId").asText();
            String partnerId = ciosRequestInput.path(Constants.CONTENT).path("contentPartner").get("id").asText();
            Optional<CiosContentEntity> ciosContentEntity = ciosRepository.findByExternalIdAndPartnerId(externalId, partnerId);
            if (!ciosContentEntity.isPresent()) {
                igotContent.setContentId(ciosRequestInput.path(Constants.CONTENT).path(Constants.CONTENT_ID).asText());
                igotContent.setExternalId(externalId);
                igotContent.setCreatedOn(currentTime);
                igotContent.setLastUpdatedOn(currentTime);
                igotContent.setIsActive(Constants.ACTIVE_STATUS);
                igotContent.setPartnerId(partnerId);
                ((ObjectNode) ciosRequestInput.path(Constants.CONTENT)).put("contentId", igotContent.getContentId());
                ((ObjectNode) ciosRequestInput.path(Constants.CONTENT)).put(Constants.CREATED_ON, String.valueOf(currentTime));
                ((ObjectNode) ciosRequestInput.path(Constants.CONTENT)).put(Constants.LAST_UPDATED_ON, String.valueOf(currentTime));
                ((ObjectNode) ciosRequestInput.path(Constants.CONTENT)).put(Constants.STATUS, Constants.LIVE);
                igotContent.setCiosData(ciosRequestInput);
            } else {
                igotContent.setContentId(ciosContentEntity.get().getContentId());
                igotContent.setExternalId(ciosContentEntity.get().getExternalId());
                igotContent.setCreatedOn(ciosContentEntity.get().getCreatedOn());
                igotContent.setLastUpdatedOn(currentTime);
                igotContent.setIsActive(Constants.ACTIVE_STATUS);
                igotContent.setPartnerId(partnerId);
                ((ObjectNode) ciosRequestInput.path(Constants.CONTENT)).put("contentId", ciosContentEntity.get().getContentId());
                ((ObjectNode) ciosRequestInput.path(Constants.CONTENT)).put(Constants.CREATED_ON, String.valueOf(igotContent.getCreatedOn()));
                ((ObjectNode) ciosRequestInput.path(Constants.CONTENT)).put(Constants.LAST_UPDATED_ON, String.valueOf(currentTime));
                ((ObjectNode) ciosRequestInput.path(Constants.CONTENT)).put(Constants.STATUS, Constants.LIVE);
                igotContent.setCiosData(ciosRequestInput);
            }
            return igotContent;
        } catch (Exception e) {
            throw new CustomException(Constants.ERROR, e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    private JsonNode addSearchTags(List<String> tags, JsonNode jsonNode) {
        List<String> lowercaseTags = new ArrayList<>();
        String contentName = null;
        if (jsonNode.path(Constants.CONTENT) != null
                && jsonNode.path(Constants.CONTENT).get(Constants.NAME) != null) {
            contentName = jsonNode.path(Constants.CONTENT).get(Constants.NAME).textValue().toLowerCase();
        }
        if (contentName != null && !tags.contains(contentName)) {
            lowercaseTags.add(contentName);
        }
        lowercaseTags.addAll(tags.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toList()));
        ArrayNode searchTagsArray = objectMapper.valueToTree(lowercaseTags);
        return searchTagsArray;
    }

    @Override
    public SearchResult searchCotent(SearchCriteria searchCriteria) {
        log.info("CiosContentServiceImpl::searchCotent");
        if (searchCriteria == null) {
            log.error("searchCriteria is null");
            throw new CustomException("ERROR", "Search criteria must not be null", HttpStatus.BAD_REQUEST);
        }
        SearchResult searchResult = redisTemplate.opsForValue()
                .get(generateRedisJwtTokenKey(searchCriteria));
        if (searchResult != null) {
            log.info("CiosContentServiceImpl::searchCotent:  search result fetched from redis");
            return searchResult;
        }
        try {
            HashMap<String, Object> filterCriteriaMap = searchCriteria.getFilterCriteriaMap();
            if (filterCriteriaMap == null) {
                filterCriteriaMap = new HashMap<>();
            }
            if (filterCriteriaMap.get(Constants.IS_ACTIVE) == null) {
                filterCriteriaMap.put(Constants.IS_ACTIVE, true);
            }
            searchCriteria.setFilterCriteriaMap(filterCriteriaMap);
            searchResult = esUtilService.searchDocuments(Constants.CIOS_INDEX_NAME, searchCriteria);
            redisTemplate.opsForValue()
                    .set(generateRedisJwtTokenKey(searchCriteria), searchResult, searchResultRedisTtl,
                            TimeUnit.SECONDS);
            return searchResult;
        } catch (Exception e) {
            throw new CustomException("ERROR", e.getMessage(), HttpStatus.BAD_REQUEST);
        }

    }

    private String generateRedisJwtTokenKey(Object requestPayload) {
        if (requestPayload != null) {
            try {
                String reqJsonString = objectMapper.writeValueAsString(requestPayload);
                return JWT.create()
                        .withClaim(Constants.REQUEST_PAYLOAD, reqJsonString)
                        .sign(Algorithm.HMAC256(cbServerProperties.getJwtSecretKey()));
            } catch (JsonProcessingException e) {
                log.error("Error occurred while converting json object to json string", e);
            }
        }
        return "";
    }

    public void validatePayload(String fileName, JsonNode payload) {
        log.info("CiosContentServiceImpl::validatePayload");
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
                throw new CustomException(Constants.ERROR, errorMessage.toString(), HttpStatus.BAD_REQUEST);
            }
        } catch (Exception e) {
            throw new CustomException(Constants.ERROR, "Failed to validate payload: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    private void updateContentWithRequiredFields(ObjectNode contentNode,Timestamp timestamp,ObjectDto eachData) {
        String contentId = contentNode.path(Constants.CONTENT_ID).asText(null);
        if (StringUtils.isBlank(contentId)) {
            contentId = generateId();
        }
        contentNode.put(Constants.STATUS, eachData.getStatus());
        contentNode.put(Constants.UPDATED_DATE, timestamp.toString());
        contentNode.put(Constants.CONTENT_ID, contentId);
        if (eachData.getCompetencies_v5() != null) {
            contentNode.set(Constants.COMPETENCIES_V5, eachData.getCompetencies_v5());
        }
        if (eachData.getCompetencies_v6() != null) {
            contentNode.set(Constants.COMPETENCIES_V6, eachData.getCompetencies_v6());
        }
        if (eachData.getContentPartner() != null) {
            contentNode.set(Constants.CONTENT_PARTNER, eachData.getContentPartner());
        }
        if (eachData.getTags() != null) {
            JsonNode searchTags = addSearchTags(eachData.getTags(),eachData.getContentData());
            contentNode.set(Constants.SEARCHTAGS, searchTags);
        }
        contentNode.set(Constants.ACCESS_SETTINGS_ENABLED, BooleanNode.valueOf(eachData.isAccessSettingsEnabled()));
        String difficultyLevel = eachData.getDifficultyLevel();
        if (StringUtils.isNotBlank(difficultyLevel)) {
            contentNode.put(Constants.DIFFICULTY_LEVEL, difficultyLevel);
        }
    }

    @Override
    public void updatePartnerIsActiveInEs(JsonNode contents, String partnerId, boolean targetIsActive) {
        List<String> successContentIds = new ArrayList<>();
        List<String> failedContentIds = new ArrayList<>();
        for (JsonNode contentNode : contents) {
            JsonNode partnerNode = contentNode.path(Constants.CONTENT_PARTNER);
            if (!partnerNode.isObject()) {
                continue;
            }
            boolean currentState = partnerNode.path(Constants.IS_ACTIVE).asBoolean(true);
            if (currentState == targetIsActive) {
                continue;
            }
            ((ObjectNode) partnerNode).put(Constants.IS_ACTIVE, targetIsActive);
            String contentId = contentNode.path(Constants.CONTENT_ID).asText(null);
            if (contentId == null) {
                continue;
            }
            try {
                Map<String, Object> updatedDoc = objectMapper.convertValue(contentNode, new TypeReference<Map<String, Object>>() {
                });
                esUtilService.updateDocument(Constants.CIOS_INDEX_NAME, Constants.INDEX_TYPE, contentId, updatedDoc, cbServerProperties.getElasticCiosJsonPath());
                log.info(Constants.LOG_ES_UPDATE_SUCCESS, contentId);
                successContentIds.add(contentId);
            } catch (Exception ex) {
                log.error(Constants.LOG_ES_UPDATE_FAILURE, contentId, ex);
                failedContentIds.add(contentId);
            }
        }
        if (!CollectionUtils.isEmpty(successContentIds)) {
            try {
                int updatedCount = ciosRepository.bulkUpdateJsonIsActive(successContentIds, targetIsActive);
                log.info(Constants.LOG_DB_BULK_UPDATE_SUCCESS, successContentIds.size(), updatedCount);
            } catch (Exception ex) {
                log.error(Constants.LOG_DB_BULK_UPDATE_FAILURE, successContentIds, ex);
            }
        }
    }

    public SearchResult readContent(SearchCriteria searchCriteria) {
        log.info("CiosContentServiceImpl::readContent");
        if (searchCriteria == null) {
            log.error("searchCriteria is null");
            throw new CustomException("ERROR", "Search criteria must not be null", HttpStatus.BAD_REQUEST);
        }
        try {
            SearchResult searchResult = redisTemplate.opsForValue()
                    .get(generateRedisJwtTokenKey(searchCriteria));
            if (searchResult != null) {
                log.info("CiosContentServiceImpl::readContent: search result fetched from redis cache");
                return searchResult;
            }
            searchResult = esUtilService.searchDocuments(Constants.CIOS_INDEX_NAME, searchCriteria);
            redisTemplate.opsForValue()
                    .set(generateRedisJwtTokenKey(searchCriteria), searchResult, searchResultRedisTtl,
                            TimeUnit.SECONDS);
            return searchResult;
        } catch (Exception e) {
            throw new CustomException("ERROR", e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
}
