package com.igot.cb.contentpartner.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.contentpartner.entity.ContentPartnerEntity;
import com.igot.cb.contentpartner.repository.ContentPartnerRepository;
import com.igot.cb.contentpartner.service.ContentPartnerService;
import com.igot.cb.playlist.util.ProjectUtil;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.pores.util.PayloadValidation;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.sql.Timestamp;
import java.util.*;

@Service
@Slf4j
public class ContentPartnerServiceImpl implements ContentPartnerService {

    @Autowired
    private EsUtilService esUtilService;

    @Autowired
    private ContentPartnerRepository entityRepository;
    @Autowired
    private CacheService cacheService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private CbServerProperties cbServerProperties;

    @Autowired
    private PayloadValidation payloadValidation;

    private Logger logger = LoggerFactory.getLogger(ContentPartnerServiceImpl.class);

    @Override
    public ApiResponse createOrUpdate(JsonNode partnerDetails) {
        log.info("ContentPartnerServiceImpl::createOrUpdate:inside");
        ApiResponse response = new ApiResponse();
        try {
            if (partnerDetails.get(Constants.ID) == null) {
                response = createContentPartner(partnerDetails);
            } else {
                response = updateContentPartner(partnerDetails);
            }
            return response;
        } catch (Exception e) {
            response.getParams().setErrMsg(e.getMessage());
            response.getParams().setStatus(Constants.FAILED);
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return response;
        }
    }

    private ApiResponse updateContentPartner(JsonNode partnerDetails) {
        log.info("ContentPartnerServiceImpl::createContentPartner");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_PARTNER_UPDATE);
        JsonNode data = partnerDetails.get(Constants.DATA);
        payloadValidation.validatePayload(Constants.PAYLOAD_VALIDATION_FILE_CONTENT_PROVIDER, data);
        String partnerName = partnerDetails.path(Constants.DATA).get(Constants.CONTENT_PARTNER_NAME).asText();
        String existingId = partnerDetails.get("id").asText();
        Optional<ContentPartnerEntity> content = entityRepository.findById(existingId);
        if (content.isPresent()) {
            if (entityRepository.findByContentPartnerName(partnerName).filter(entity -> !entity.getId().equals(existingId)).isPresent()) {
                response.getParams().setErrMsg(Constants.CONTENT_PARTNER_NAME_ALREADY_PRESENT);
                response.getParams().setStatus(Constants.FAILED);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }
            ContentPartnerEntity jsonEntity = content.get();
            createContentPartnerEntity(jsonEntity,partnerDetails);
            ContentPartnerEntity updateJsonEntity = entityRepository.save(jsonEntity);
            if (!ObjectUtils.isEmpty(updateJsonEntity)) {
                Map<String, Object> jsonMap =
                        objectMapper.convertValue(updateJsonEntity.getData(), new TypeReference<Map<String, Object>>() {
                        });
                esUtilService.updateDocument(Constants.CONTENT_PROVIDER_INDEX_NAME, Constants.INDEX_TYPE, existingId, jsonMap, cbServerProperties.getElasticContentJsonPath());
                Map<String, Object> result = objectMapper.convertValue(updateJsonEntity, Map.class);

                if (jsonMap != null && StringUtils.isNotBlank((String) jsonMap.get(Constants.PARTNERCODE))) {
                    log.info(Constants.CONTENT_PARTNER_UPDATE_CACHE_DELETE, partnerDetails.path(Constants.PARTNERCODE).asText());
                    cacheService.deleteCache((String) jsonMap.get(Constants.PARTNERCODE));
                    cacheService.deleteCache(updateJsonEntity.getId());
                }
                log.info(Constants.UPDATED_CONTENT_PARTNER);
                response.setResult(result);
                response.setResponseCode(HttpStatus.OK);
            }
        } else {
            response.getParams().setErrMsg(Constants.DATA_NOT_PRESENT);
            response.getParams().setStatus(Constants.FAILED);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
        }
        return response;
    }

    private void createContentPartnerEntity(ContentPartnerEntity jsonEntity,JsonNode partnerDetails) {
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        jsonEntity.setUpdatedOn(currentTime);
        jsonEntity.setIsActive(Constants.ACTIVE_STATUS);
        jsonEntity.setTrasformContentJson(partnerDetails.get(Constants.TRANSFORM_CONTENT_JSON));
        jsonEntity.setTransformProgressJson(partnerDetails.get(Constants.TRANSFORM_PROGRESS_JSON));
        jsonEntity.setCertificateTemplateUrl(partnerDetails.path(Constants.CERTIFICATE_TEMPLATE_URL).asText(" "));
        jsonEntity.setServiceRegistryDetails(partnerDetails.get(Constants.SERVICE_REGISTRY_DETAILS));
        jsonEntity.setContentFileValidation(partnerDetails.get(Constants.CONTENT_FILE_VALIDATION));
        jsonEntity.setTransformContentViaApi(partnerDetails.get(Constants.TRANSFORM_CONTENT_VIA_API));
        jsonEntity.setTransformProgressViaApi(partnerDetails.get(Constants.TRANSFORM_PROGRESS_VIA_API));
        ObjectNode objectNode = (ObjectNode) partnerDetails;
        objectNode.remove(Constants.TRANSFORM_CONTENT_JSON);
        objectNode.remove(Constants.TRANSFORM_PROGRESS_JSON);
        objectNode.remove(Constants.CERTIFICATE_TEMPLATE_URL);
        objectNode.remove(Constants.ID);
        objectNode.remove(Constants.CONTENT_FILE_VALIDATION);
        objectNode.remove(Constants.TRANSFORM_CONTENT_VIA_API);
        objectNode.remove(Constants.TRANSFORM_PROGRESS_VIA_API);
        ObjectNode dataNode = (ObjectNode) objectNode.remove(Constants.DATA);
        dataNode.put(Constants.CREATED_ON, String.valueOf(jsonEntity.getCreatedOn()));
        dataNode.put(Constants.UPDATED_ON, String.valueOf(currentTime));
        ((ObjectNode) partnerDetails).put(Constants.DOCUMENT_UPLOADED_DATE, partnerDetails.path(Constants.DOCUMENT_UPLOADED_DATE).asText(""));
        dataNode.put(Constants.IS_ACTIVE, Constants.ACTIVE_STATUS);
        updateOtherDetailsWithDefaultValue(dataNode,jsonEntity);
        addSearchTags(dataNode);
        jsonEntity.setData(dataNode);
    }

    private void updateOtherDetailsWithDefaultValue(ObjectNode dataNode, ContentPartnerEntity content) {
        ObjectNode existingData = (ObjectNode) content.getData();

        existingData.fieldNames().forEachRemaining(field -> {
            if (dataNode.path(field).isMissingNode()) {
                dataNode.set(field, existingData.get(field));
            }
        });
    }

    @Override
    public ApiResponse createContentPartner(JsonNode partnerDetails) {
        log.info("ContentPartnerServiceImpl::createContentPartner");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_PARTNER_CREATE);
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        log.info("Payload for validation: {}", partnerDetails);
        String id = null;
        if (partnerDetails != null && partnerDetails.hasNonNull(Constants.ID)) {
            id = partnerDetails.path(Constants.ID).asText();
            payloadValidation.validatePayload(Constants.CONTENT_PARTNER_FILE_JSON, partnerDetails);
        }else {
            id = UUID.randomUUID().toString();
            payloadValidation.validatePayload(Constants.PAYLOAD_VALIDATION_FILE_CONTENT_PROVIDER, partnerDetails);
        }
        String partnerName = partnerDetails.path(Constants.CONTENT_PARTNER_NAME).asText();
        String partnerCode = partnerDetails.path(Constants.PARTNERCODE).asText("");
        boolean hasPartnerCode = partnerCode != null && !partnerCode.isEmpty();
        Optional<ContentPartnerEntity> existingByName = entityRepository.findByContentPartnerName(partnerName);
        if (existingByName.isPresent()) {
            if (hasPartnerCode && entityRepository.findByPartnerCode(partnerCode).isPresent()) {
                response.getParams().setErrMsg(Constants.CONTENT_PARTNER_CODE_AND_NAME_ALREADY_PRESENT);
            } else {
                response.getParams().setErrMsg(Constants.CONTENT_PARTNER_NAME_ALREADY_PRESENT);
            }
            response.getParams().setStatus(Constants.FAILED);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }
        if (hasPartnerCode && entityRepository.findByPartnerCode(partnerCode).isPresent()) {
            response.getParams().setErrMsg(Constants.CONTENT_PARTNER_CODE_ALREADY_PRESENT);
            response.getParams().setStatus(Constants.FAILED);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }
        ((ObjectNode) partnerDetails).set(Constants.PARTNERCODE, partnerDetails.get("partnerCode"));
        ((ObjectNode) partnerDetails).put(Constants.ID, id);
        ((ObjectNode) partnerDetails).put(Constants.IS_ACTIVE, Constants.ACTIVE_STATUS);
        ((ObjectNode) partnerDetails).put(Constants.TOTAL_COURSES_COUNT, 0);
        ((ObjectNode) partnerDetails).put(Constants.DRAFT_COURSES_COUNT, 0);
        ((ObjectNode) partnerDetails).put(Constants.LIVE_COURSES_COUNT, 0);
        ((ObjectNode) partnerDetails).put(Constants.OVER_ALL_LIMIT, 0);
        ((ObjectNode) partnerDetails).put(Constants.USER_WISE_LIMIT_ENABLED, Constants.IN_ACTIVE_STATUS);
        ((ObjectNode) partnerDetails).put(Constants.CONCURRENT_LIMIT_ENABLED, Constants.IN_ACTIVE_STATUS);
        ((ObjectNode) partnerDetails).put(Constants.ADD_KARMA_POINT_ENABLED, Constants.IN_ACTIVE_STATUS);
        ((ObjectNode) partnerDetails).put(Constants.IS_AUTHENTICATE, Constants.IN_ACTIVE_STATUS);
        ((ObjectNode) partnerDetails).set(Constants.PROVIDER_TIPS, ((ObjectNode) partnerDetails).arrayNode());
        ((ObjectNode) partnerDetails).put(Constants.CREATED_ON, String.valueOf(currentTime));
        ((ObjectNode) partnerDetails).put(Constants.UPDATED_ON, String.valueOf(currentTime));
        ((ObjectNode) partnerDetails).put(Constants.DOCUMENT_UPLOADED_DATE, partnerDetails.path(Constants.DOCUMENT_UPLOADED_DATE).asText(""));
        ContentPartnerEntity contentPartnerEntity = new ContentPartnerEntity();
        contentPartnerEntity.setId(id);
        contentPartnerEntity.setCreatedOn(currentTime);
        contentPartnerEntity.setUpdatedOn(currentTime);
        contentPartnerEntity.setIsActive(Constants.ACTIVE_STATUS);
        contentPartnerEntity.setCertificateTemplateUrl(partnerDetails.path(Constants.CERTIFICATE_TEMPLATE_URL).asText(" "));
        addSearchTags(partnerDetails);
        contentPartnerEntity.setData(partnerDetails);
        ContentPartnerEntity saveJsonEntity = entityRepository.save(contentPartnerEntity);
        Map<String, Object> map = objectMapper.convertValue(saveJsonEntity.getData(), Map.class);
        esUtilService.addDocument(Constants.CONTENT_PROVIDER_INDEX_NAME, Constants.INDEX_TYPE, id, map, cbServerProperties.getElasticContentJsonPath());
        Map<String, Object> result = objectMapper.convertValue(saveJsonEntity, Map.class);
        cacheService.putCache(saveJsonEntity.getId(), result);
        if (!partnerDetails.path(Constants.PARTNERCODE).isMissingNode()) {
            log.info(Constants.CONTENT_PARTNER_CACHE_DELETE, partnerDetails.path(Constants.PARTNERCODE).asText());
            cacheService.deleteCache(partnerDetails.get(Constants.PARTNERCODE).asText());
        }
        log.info(Constants.CONTENT_PARTNER_CREATED);
        response.setResult(result);
        response.setResponseCode(HttpStatus.OK);
        return response;
    }

    private JsonNode addSearchTags(JsonNode formattedData) {
        List<String> searchTags = new ArrayList<>();

        // Preserve existing searchTags if present
        if (formattedData.has("searchTags") && formattedData.get("searchTags").isArray()) {
            ArrayNode existingSearchTags = (ArrayNode) formattedData.get("searchTags");
            existingSearchTags.forEach(tag -> {
                if (tag.isTextual() && !tag.asText().isEmpty()) {
                    searchTags.add(tag.asText());
                }
            });
        }

        // Add contentPartnerName to searchTags
        if (formattedData.has("contentPartnerName")) {
            String partnerName = formattedData.get("contentPartnerName").textValue();
            if (StringUtils.isNotBlank(partnerName)) {
                if (!searchTags.contains(partnerName.toLowerCase())) {
                    searchTags.add(partnerName.toLowerCase());
                }
            }
        }

        ArrayNode searchTagsArray = objectMapper.valueToTree(searchTags);
        ((ObjectNode) formattedData).put("searchTags", searchTagsArray);
        return formattedData;
    }


    @Override
    public ApiResponse read(String id) {
        log.info("ContentPartnerServiceImpl::read:reading information about the content partner");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_PARTNER_READ);
        if (StringUtils.isEmpty(id)) {
            response.getParams().setErrMsg(Constants.ID_NOT_FOUND);
            response.getParams().setStatus(Constants.FAILED);
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        try {
            String cachedJson = cacheService.getCache(id);
            if (StringUtils.isNotEmpty(cachedJson)) {
                log.info("Record coming from redis cache");
                response.setResponseCode(HttpStatus.OK);
                response.setResult(objectMapper.readValue(cachedJson, new TypeReference<Map>() {
                }));
            } else {
                Optional<ContentPartnerEntity> entityOptional = entityRepository.findByIdAndIsActive(id, true);
                if (entityOptional.isPresent()) {
                    ContentPartnerEntity entity = entityOptional.get();
                    cacheService.putCache(id, entity);
                    log.info("Record coming from postgres db");
                    response.setResponseCode(HttpStatus.OK);
                    response.setResult(objectMapper.convertValue(entity, Map.class));
                } else {
                    response.getParams().setErrMsg(Constants.INVALID_ID);
                    response.getParams().setStatus(Constants.FAILED);
                    response.setResponseCode(HttpStatus.BAD_REQUEST);
                }
            }
        } catch (Exception e) {
            log.error("error while processing", e);
            response.getParams().setErrMsg(e.getMessage());
            response.getParams().setStatus(Constants.FAILED);
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return response;
    }

    @Override
    public ApiResponse searchEntity(SearchCriteria searchCriteria) {
        log.info("ContentPartnerServiceImpl::searchEntity:searching the content partner");
        String searchString = searchCriteria.getSearchString();
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_PARTNER_SEARCH);
        if (searchString != null && searchString.length() < 2) {
            response.getParams().setErrMsg("Minimum 3 characters are required to search");
            response.getParams().setStatus(Constants.FAILED);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
        }
        try {
            SearchResult searchResult =
                    esUtilService.searchDocuments(Constants.CONTENT_PROVIDER_INDEX_NAME, searchCriteria);
            Map<String, Object> jsonMap =
                    objectMapper.convertValue(searchResult, new TypeReference<Map<String, Object>>() {
                    });
            response.setResult(jsonMap);
            response.setResponseCode(HttpStatus.OK);
        } catch (Exception e) {
            logger.error("Error while processing to search", e);
            response.getParams().setErrMsg(e.getMessage());
            response.getParams().setStatus(Constants.FAILED);
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return response;
    }

    @Override
    public ApiResponse delete(String id) {
        log.info("ContentPartnerServiceImpl::delete:deleting the content partner");
        ApiResponse response=ProjectUtil.createDefaultResponse(Constants.API_PARTNER_DELETE);
        try {
            if (StringUtils.isNotEmpty(id)) {
                Optional<ContentPartnerEntity> entityOptional = entityRepository.findByIdAndIsActive(id,true);
                if (entityOptional.isPresent()) {
                    ContentPartnerEntity josnEntity = entityOptional.get();
                    Timestamp currentTime = new Timestamp(System.currentTimeMillis());
                    josnEntity.setUpdatedOn(currentTime);
                    josnEntity.setIsActive(Constants.ACTIVE_STATUS_FALSE);
                    ((ObjectNode) josnEntity.getData()).put(Constants.IS_ACTIVE, Constants.ACTIVE_STATUS_FALSE);
                    entityRepository.save(josnEntity);
                    Map<String, Object> map = objectMapper.convertValue(josnEntity.getData(), Map.class);
                    esUtilService.addDocument(Constants.CONTENT_PROVIDER_INDEX_NAME, Constants.INDEX_TYPE, id, map, cbServerProperties.getElasticContentJsonPath());
                    cacheService.deleteCache(id);
                    cacheService.deleteCache((String) map.get(Constants.PARTNERCODE));
                    Map<String,Object> map1=new HashMap<>();
                    map1.put(id,Constants.DELETED_SUCCESSFULLY);
                    response.setResponseCode(HttpStatus.OK);
                    response.setResult(map1);
                } else {
                    response.setResponseCode(HttpStatus.BAD_REQUEST);
                    response.getParams().setErrMsg(Constants.CONTENT_PARTNER_NOT_FOUND);
                }
            } else {
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                response.getParams().setErrMsg(Constants.INVALID_ID);
            }
        } catch (Exception e) {
            log.error("Error deleting Entity with ID " + id + " " + e.getMessage());
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.getParams().setErrMsg("Error deleting Entity with ID " + id + " " + e.getMessage());
        }
        return response;
    }

    @Override
    public ApiResponse getContentDetailsByPartnerCode(String partnercode) {
        log.info("CiosContentService:: ContentPartnerEntity: getContentDetailsByPartnerName {}",partnercode);
        try {
            ApiResponse response=ProjectUtil.createDefaultResponse(Constants.API_PARTNER_READ);
            ContentPartnerEntity entity=null;
            String cachedJson = cacheService.getCache(partnercode);
            if (StringUtils.isNotEmpty(cachedJson)) {
                log.info("Record coming from redis cache");
                response.setResponseCode(HttpStatus.OK);
                response.setResult(objectMapper.readValue(cachedJson, new TypeReference<Map>() {}));
            } else {
                Optional<ContentPartnerEntity> entityOptional = entityRepository.findByPartnerCode(partnercode);
                if (entityOptional.isPresent()) {
                    log.info("Record coming from postgres db");
                    entity = entityOptional.get();
                    cacheService.putCache(partnercode,entity);
                    response.setResponseCode(HttpStatus.OK);
                    response.setResult(objectMapper.convertValue(entity, Map.class));
                } else {
                    response.getParams().setErrMsg("Invalid name");
                    response.getParams().setStatus(Constants.FAILED);
                    response.setResponseCode(HttpStatus.BAD_REQUEST);
                }
            }
            return response;
        } catch (Exception e) {
            log.error("error while processing", e);
        }
        return null;
    }
}