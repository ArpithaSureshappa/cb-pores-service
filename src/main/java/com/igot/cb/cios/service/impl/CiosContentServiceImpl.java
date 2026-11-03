package com.igot.cb.cios.service.impl;


import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.cios.dto.ObjectDto;
import com.igot.cb.cios.entity.CiosContentEntity;
import com.igot.cb.cios.repository.CiosRepository;
import com.igot.cb.cios.service.CiosContentService;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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
@RequiredArgsConstructor
public class CiosContentServiceImpl implements CiosContentService {
    private static long environmentId = 10000000;
    private static String shardId = "1";
    private static AtomicInteger aInteger = new AtomicInteger(1);

    @Value("${search.result.redis.ttl}")
    private long searchResultRedisTtl;

    private final CiosRepository ciosRepository;

    private final ObjectMapper objectMapper;

    private final EsUtilService esUtilService;

    private final PayloadValidation payloadValidation;

    private final RedisTemplate<String, SearchResult> redisTemplate;

    private final CbServerProperties cbServerProperties;

    private final CacheService cacheService;

    private final RestTemplate restTemplate;

    private final ContentPartnerService contentPartnerService;

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


    private boolean isPayloadValid(JsonNode jsonNode, ObjectNode contentNode) {
        try {
            payloadValidation.validatePayload(Constants.CIOS_CONTENT_VALIDATION_FILE_JSON, jsonNode);
            return true;
        } catch (Exception e) {
            log.warn("Payload validation failed for contentId: {}, reason: {}", contentNode.path(Constants.CONTENT_ID).asText(), e.getMessage());
            return false;
        }
    }

    @Override
    public ApiResponse onboardContent(List<ObjectDto> data) {
        log.info("CiosContentServiceImpl::createOrUpdateContent");
        ApiResponse apiResponse = ProjectUtil.createDefaultResponse(Constants.API_CIOS_CURATION_CREATE);
        try {
            Timestamp timestamp = new Timestamp(System.currentTimeMillis());
            String partnerCode = null;
            Integer successCount = 0;
            for (ObjectDto eachData : data) {
                partnerCode = eachData.getContentPartner().get("partnerCode").asText();
                JsonNode jsonNode = eachData.getContentData();
                ObjectNode contentNode = (ObjectNode) jsonNode.path(Constants.CONTENT);
                updateContentWithRequiredFields(contentNode, timestamp, eachData);
                if (Constants.DRAFT.equalsIgnoreCase(eachData.getStatus())) {
                    log.info("Status of the data {}", eachData.getStatus());
                    contentNode.put(Constants.IS_ACTIVE, Constants.ACTIVE_STATUS_FALSE);
                    contentNode.put(Constants.PUBLISHED_ON, "0000-00-00 00:00:00.000");
                    contentNode.put(Constants.CREATED_DATE, timestamp.toString());
                    apiCallToCiosSecondaryDbForUpdateData(jsonNode);
                } else if (eachData.getStatus().equals("live")) {
                    if (!processLiveStatusContent(jsonNode, contentNode, partnerCode, timestamp)) {
                        continue;
                    }
                    successCount++;
                } else {
                    apiResponse.getParams().setErrMsg(Constants.STATUS_NOT_VALID);
                    apiResponse.getParams().setStatus(Constants.FAILED);
                    apiResponse.setResponseCode(HttpStatus.BAD_REQUEST);
                    return apiResponse;
                }
            }
            fetchAndUpdateContentCountsInPartnerDb(partnerCode);
            Map<String, Object> result = new HashMap<>();
            String message = String.format(Constants.COURSES_PUBLISHED_MESSAGE, successCount, data.size());
            result.put("ApiResponse", message);
            apiResponse.setResult(result);
            return apiResponse;
        } catch (CustomException e) {
            apiResponse.getParams().setErrMsg(e.getMessage());
            apiResponse.getParams().setStatus(Constants.FAILED);
            apiResponse.setResponseCode(e.getHttpStatusCode() != null ? e.getHttpStatusCode() : HttpStatus.BAD_REQUEST);
            return apiResponse;
        } catch (Exception e) {
            apiResponse.getParams().setErrMsg(e.getMessage());
            apiResponse.getParams().setStatus(Constants.FAILED);
            apiResponse.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return apiResponse;
        }
    }

    /**
     * Applies the provider-licence-driven rules for courseType, courseEnrolLimit and
     * requiredKarmaPoints at publish ("live") time.
     *
     * Rules:
     * - If the provider has no karma points configured (addKarmaPointEnabled is false, or
     *   karmaPoints is 0/absent), every course's requiredKarmaPoints is forced to 0, regardless
     *   of licenceType or courseType.
     * - licenceType == "User": every course is forced to courseType "paid" with courseEnrolLimit
     *   0 (per-course caps don't apply under a user-wise licence), and requiredKarmaPoints is
     *   forced to exactly match the provider's karmaPoints.
     * - licenceType == "Course", courseType "free": courseEnrolLimit is always set to the
     *   partner's overAllLimit, regardless of any value supplied on this request.
     * - licenceType == "Course", courseType "paid": courseEnrolLimit uses the supplied value when
     *   it is within the partner's overAllLimit; otherwise (not supplied, or supplied but above
     *   the overAllLimit) it falls back to the overAllLimit itself.
     *   requiredKarmaPoints is forced to 0 for a "free" course, and to exactly match the
     *   provider's karmaPoints for a "paid" course (same outcome as the User-licence case).
     * - If the provider hasn't configured a licenceType yet, this is a no-op beyond the
     *   provider-karma-points gate above, preserving prior default-only behaviour.
     *
     * Returns false (with apiResponse already populated as a BAD_REQUEST validation failure) when
     * the supplied courseEnrolLimit/requiredKarmaPoints is invalid against the partner's
     * overAllLimit/karmaPoints; the caller must return apiResponse immediately in that case rather
     * than continuing to persist the content. This is a direct response, not an exception, so it
     * doesn't touch onboardContent's generic catch block or the status code of any other error.
     * Returns true otherwise.
     */
    private boolean applyPublishTimeLicenceRules(ObjectNode contentNode, String partnerCode) {
        ApiResponse partnerResponse = contentPartnerService.getContentDetailsByPartnerCode(partnerCode);
        if (partnerResponse == null || partnerResponse.getResult() == null
                || partnerResponse.getResult().get(Constants.DATA) == null) {
            log.error("Failed to retrieve partner details for code: {}", partnerCode);
            return false;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> partnerData = (Map<String, Object>) partnerResponse.getResult().get(Constants.DATA);
        String partnerLicenceType = (String) partnerData.get(Constants.LICENCE_TYPE);
        Number partnerKarmaPointsNum = (Number) partnerData.get(Constants.KARMA_POINTS);
        int partnerKarmaPoints = partnerKarmaPointsNum != null ? partnerKarmaPointsNum.intValue() : 0;


        Number overAllLimitNum = (Number) partnerData.get(Constants.OVER_ALL_LIMIT);
        int overAllLimit = overAllLimitNum != null ? overAllLimitNum.intValue() : 0;


        if (Constants.LICENCE_TYPE_USER.equalsIgnoreCase(partnerLicenceType)) {
            //remove requiredKarmaPoints and courseType for user license typ
            contentNode.remove(Constants.COURSE_TYPE);// courseType not required for user license type, neither free nor paid
            contentNode.put(Constants.REQUIRED_KARMA_POINTS, 0);//karmapoints always 0 for user license type
            if(contentNode.path(Constants.COURSE_ENROL_LIMIT).asInt(0) > overAllLimit) {
                return false;
            }
        } else if (Constants.LICENCE_TYPE_COURSE.equalsIgnoreCase(partnerLicenceType)) {
            boolean isPaid = Constants.COURSE_TYPE_PAID.equalsIgnoreCase(
                    contentNode.path(Constants.COURSE_TYPE).asText());
            if (isContentAlreadyPublished(contentNode)) {
                log.warn("Cannot change courseType for already published contentId: {}",
                        contentNode.path(Constants.CONTENT_ID).asText());
                return false;
            }
            if (isPaid) {
                // partnerData is a Map, not JsonNode - convert, don't cast.
                JsonNode providerJson = objectMapper.convertValue(partnerData, JsonNode.class);
                Double karmaCoinMultiplier = providerJson.path(Constants.KARMA_COIN_MULTIPLIER).asDouble(1);
                int karmaCoin = (int) Math.round(parseKarmaCoinModifier(karmaCoinMultiplier, contentNode.path(Constants.REQUIRED_KARMA_POINTS).asInt(0)));
                contentNode.put(Constants.REQUIRED_KARMA_COINS, karmaCoin);
                int compatibilityLevel = karmaCoin > 0 ? cbServerProperties.getExtCourseCompatibilityLevel() : 0;
                contentNode.set(Constants.COMPATIBILITY_LEVEL, IntNode.valueOf(compatibilityLevel));
                return validateKarmapointsAndCourseEnrolLimit(contentNode, partnerKarmaPoints, overAllLimit);
            }
        }
        return true;
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

    private CiosContentEntity createNewContent(JsonNode ciosRequestInput, boolean isActive, String status) {
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
                igotContent.setIsActive(isActive);
                igotContent.setPartnerId(partnerId);
                ((ObjectNode) ciosRequestInput.path(Constants.CONTENT)).put("contentId", igotContent.getContentId());
                ((ObjectNode) ciosRequestInput.path(Constants.CONTENT)).put(Constants.CREATED_ON, String.valueOf(currentTime));
                ((ObjectNode) ciosRequestInput.path(Constants.CONTENT)).put(Constants.LAST_UPDATED_ON, String.valueOf(currentTime));
                ((ObjectNode) ciosRequestInput.path(Constants.CONTENT)).put(Constants.STATUS, status);
                igotContent.setCiosData(ciosRequestInput);
            } else {
                igotContent.setContentId(ciosContentEntity.get().getContentId());
                igotContent.setExternalId(ciosContentEntity.get().getExternalId());
                igotContent.setCreatedOn(ciosContentEntity.get().getCreatedOn());
                igotContent.setLastUpdatedOn(currentTime);
                igotContent.setIsActive(isActive);
                igotContent.setPartnerId(partnerId);
                ((ObjectNode) ciosRequestInput.path(Constants.CONTENT)).put("contentId", ciosContentEntity.get().getContentId());
                ((ObjectNode) ciosRequestInput.path(Constants.CONTENT)).put(Constants.CREATED_ON, String.valueOf(igotContent.getCreatedOn()));
                ((ObjectNode) ciosRequestInput.path(Constants.CONTENT)).put(Constants.LAST_UPDATED_ON, String.valueOf(currentTime));
                ((ObjectNode) ciosRequestInput.path(Constants.CONTENT)).put(Constants.STATUS, status);
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
                .toList());
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
                        .sign(Algorithm.HMAC256(Constants.JWT_SECRET_KEY));
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
        if (eachData.getBadgeDetails_v1() != null) {
            contentNode.set(Constants.BADGE_DETAILS_V1, eachData.getBadgeDetails_v1());
        }
        contentNode.set(Constants.ACCESS_SETTINGS_ENABLED, BooleanNode.valueOf(eachData.isAccessSettingsEnabled()));
        String difficultyLevel = eachData.getDifficultyLevel();
        if (StringUtils.isNotBlank(difficultyLevel)) {
            contentNode.put(Constants.DIFFICULTY_LEVEL, difficultyLevel);
        }
        // courseType is set at onboarding; default to "paid" unless already present
        // (either supplied on this request, or already set on the content from a prior onboarding call).
        String courseType = eachData.getCourseType();
        if (StringUtils.isNotBlank(courseType)) {
            contentNode.put(Constants.COURSE_TYPE, courseType);
        } else if (StringUtils.isBlank(contentNode.path(Constants.COURSE_TYPE).asText(null))) {
            contentNode.put(Constants.COURSE_TYPE, Constants.COURSE_TYPE_PAID);
        }
        if(eachData.getCourseEnrolLimit() != null) {
            contentNode.put(Constants.COURSE_ENROL_LIMIT, eachData.getCourseEnrolLimit());
        }
        if(eachData.getRequiredKarmaPoints() != null) {
            contentNode.put(Constants.REQUIRED_KARMA_POINTS, eachData.getRequiredKarmaPoints());
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

    @Override
    public Object fetchDataByInputFields(String contentId, String inputFields) {
        log.info(
                "CiosContentServiceImpl::fetchDataByInputFields: contentId: {}, inputFields: {}",
                contentId,
                inputFields
        );
        if (StringUtils.isBlank(contentId)) {
            log.error("CiosContentServiceImpl::fetchDataByInputFields: contentId not found");
            throw new CustomException(
                    Constants.ERROR,
                    "contentId is mandatory",
                    HttpStatus.BAD_REQUEST
            );
        }
        if (StringUtils.isBlank(inputFields)) {
            log.error("CiosContentServiceImpl::fetchDataByInputFields: inputFields not found");
            throw new CustomException(
                    Constants.ERROR,
                    "inputFields is mandatory",
                    HttpStatus.BAD_REQUEST
            );
        }
        Object fullContent = fetchDataByContentId(contentId);
        if (!(fullContent instanceof Map)) {
            log.info(
                    "CiosContentServiceImpl::fetchDataByInputFields: content for id {} is not a map, returning as-is",
                    contentId
            );
            return fullContent;
        }
        Map<?, ?> fullContentMap = (Map<?, ?>) fullContent;
        Object innerContent = fullContentMap.get(Constants.CONTENT);
        if (!(innerContent instanceof Map)) {
            log.info(
                    "CiosContentServiceImpl::fetchDataByInputFields: no '{}' node found for id {}, returning as-is",
                    Constants.CONTENT,
                    contentId
            );
            return fullContent;
        }
        Map<?, ?> innerContentMap = (Map<?, ?>) innerContent;

        Set<String> requestedFields = Arrays.stream(inputFields.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Object> filteredInnerContent = new LinkedHashMap<>();
        requestedFields.stream()
                .filter(innerContentMap::containsKey)
                .forEach(field -> filteredInnerContent.put(field, innerContentMap.get(field)));

        Map<String, Object> filteredContent = new LinkedHashMap<>();
        filteredContent.put(Constants.CONTENT, filteredInnerContent);
        return filteredContent;
    }

    private Double parseKarmaCoinModifier(Double multiplier, int defaultKarmaPoints) {
        return multiplier * defaultKarmaPoints;
    }

    private boolean validateKarmapointsAndCourseEnrolLimit(ObjectNode contentNode, int partnerKarmaPoints, int overAllLimit) {
        if(contentNode.path(Constants.COURSE_ENROL_LIMIT).asInt(0) > overAllLimit) {
            return false;
        }
        if(contentNode.path(Constants.REQUIRED_KARMA_POINTS).asInt(0) < partnerKarmaPoints) {
            return false;
        }
        return true;
    }

    private boolean isContentAlreadyPublished(ObjectNode contentNode) {
        String contentId = contentNode.path(Constants.CONTENT_ID).asText(null);

        if (StringUtils.isEmpty(contentId)) {
            return false;
        }

        try {
            Object existingContent = fetchDataByContentId(contentId);

            if (existingContent == null) {
                return false;
            }
            JsonNode existingContentNode = objectMapper.convertValue(existingContent, JsonNode.class);
            String existingCourseType = existingContentNode
                    .path(Constants.CONTENT)
                    .path(Constants.COURSE_TYPE)
                    .asText("");
            String requestedCourseType = contentNode
                    .path(Constants.COURSE_TYPE)
                    .asText("");
            return StringUtils.isNotBlank(existingCourseType)
                    && !existingCourseType.equalsIgnoreCase(requestedCourseType);

        } catch (CustomException e) {
            return false;
        }
    }

    /**
     * Handles a single "live" status content item: validates the payload and the publish-time
     * licence rules, and when both pass, persists the content and indexes it. Returns true when
     * the content was successfully published, false when it was skipped due to a validation
     * failure (in which case the caller should skip to the next item).
     */
    private boolean processLiveStatusContent(JsonNode jsonNode, ObjectNode contentNode, String partnerCode, Timestamp timestamp) {
        log.info("Status of the data {}", "live");
        if (!isPayloadValid(jsonNode, contentNode)) {
            return false;
        }
        contentNode.put(Constants.IS_ACTIVE, Constants.ACTIVE_STATUS);
        contentNode.put(Constants.PUBLISHED_ON, timestamp.toString());
        contentNode.put(Constants.UPDATED_DATE, timestamp.toString());
        if (!applyPublishTimeLicenceRules(contentNode, partnerCode)) {
            log.warn("Content validation failed for contentId: {}", contentNode.path(Constants.CONTENT_ID).asText());
            return false;
        }
        //apiCallToCiosSecondaryDbForUpdateData(jsonNode);
        CiosContentEntity ciosContentEntity = createNewContent(jsonNode, Constants.ACTIVE_STATUS, Constants.LIVE);
        ciosRepository.save(ciosContentEntity);
        log.info("Id of content created: {}", ciosContentEntity.getContentId());
        Map<String, Object> map = objectMapper.convertValue(ciosContentEntity.getCiosData().get(Constants.CONTENT), Map.class);
        log.debug("map value for elastic search {}", map);
        cacheService.putCache(ciosContentEntity.getContentId(), ciosContentEntity.getCiosData());
        cacheService.putCache(ciosContentEntity.getExternalId() + "_" + ciosContentEntity.getPartnerId(), ciosContentEntity.getCiosData());
        esUtilService.addDocument(Constants.CIOS_INDEX_NAME, Constants.INDEX_TYPE, ciosContentEntity.getContentId(), map, cbServerProperties.getElasticCiosJsonPath());
        return true;
    }
}
