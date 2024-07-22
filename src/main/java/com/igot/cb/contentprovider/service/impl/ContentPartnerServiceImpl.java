package com.igot.cb.contentprovider.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.igot.cb.contentprovider.entity.ContentPartnerEntity;
import com.igot.cb.contentprovider.repository.ContentPartnerRepository;
import com.igot.cb.contentprovider.service.ContentPartnerService;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.dto.CustomResponse;
import com.igot.cb.pores.dto.RespParam;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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

    private Logger logger = LoggerFactory.getLogger(ContentPartnerServiceImpl.class);

    @Override
    public CustomResponse createOrUpdate(JsonNode partnerDetails) {
        CustomResponse response = new CustomResponse();
        validatePayload(Constants.PAYLOAD_VALIDATION_FILE_CONTENT_PROVIDER, partnerDetails);
        try {
            if (partnerDetails.get(Constants.ID) == null) {
                log.info("ContentPartnerServiceImpl::createOrUpdate:creating content partner provider");
                String id = String.valueOf(UUID.randomUUID());
                ((ObjectNode) partnerDetails).put(Constants.IS_ACTIVE, Constants.ACTIVE_STATUS);
                Timestamp currentTime = new Timestamp(System.currentTimeMillis());
                ContentPartnerEntity jsonNodeEntity = new ContentPartnerEntity();
                jsonNodeEntity.setId(id);
                jsonNodeEntity.setData(partnerDetails);
                jsonNodeEntity.setCreatedOn(currentTime);
                jsonNodeEntity.setUpdatedOn(currentTime);
                ContentPartnerEntity saveJsonEntity = entityRepository.save(jsonNodeEntity);
                ObjectMapper objectMapper = new ObjectMapper();
                ObjectNode jsonNode = objectMapper.createObjectNode();
                jsonNode.set(Constants.CONTENT_PROVIDER_ID, new TextNode(saveJsonEntity.getId()));
                jsonNode.setAll((ObjectNode) saveJsonEntity.getData());
                Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
                esUtilService.addDocument(Constants.CONTENT_PROVIDER_INDEX_NAME, Constants.INDEX_TYPE, id, map, cbServerProperties.getElasticContentJsonPath());
                cacheService.putCache(jsonNodeEntity.getId(), jsonNode);
                log.info("Content partner created");
                map.put("id",jsonNodeEntity.getId());
                response.setMessage(Constants.SUCCESSFULLY_CREATED);
                response.setResult(map);
            } else {
                log.info("Updating content partner entity");
                String exitingId = partnerDetails.get("id").asText();
                Optional<ContentPartnerEntity> content = entityRepository.findById(exitingId);
                Timestamp currentTime = new Timestamp(System.currentTimeMillis());
                if (content.isPresent()) {
                    ContentPartnerEntity josnEntity = content.get();
                    josnEntity.setData(partnerDetails);
                    josnEntity.setUpdatedOn(currentTime);
                    ContentPartnerEntity updateJsonEntity = entityRepository.save(josnEntity);
                    if (!org.springframework.util.ObjectUtils.isEmpty(updateJsonEntity)) {
                        Map<String, Object> jsonMap =
                                objectMapper.convertValue(updateJsonEntity.getData(), new TypeReference<Map<String, Object>>() {
                                });
                        updateJsonEntity.setId(exitingId);
                        esUtilService.updateDocument(Constants.CONTENT_PROVIDER_INDEX_NAME, Constants.INDEX_TYPE, exitingId, jsonMap, cbServerProperties.getElasticContentJsonPath());
                        cacheService.putCache(exitingId, updateJsonEntity);
                        log.info("updated the content partner");
                        response.setMessage(Constants.SUCCESSFULLY_UPDATED);
                        jsonMap.put("id",exitingId);
                        response.setResult(jsonMap);
                    }
                }
            }
            response.setResponseCode(HttpStatus.OK);
            return response;
        } catch (Exception e) {
            logger.error("error while processing", e);
            throw new RuntimeException(e);
        }
    }


    @Override
    public CustomResponse read(String id) {
        log.info("ContentPartnerServiceImpl::read:reading information about the content partner");
        CustomResponse response = new CustomResponse();
        if (StringUtils.isEmpty(id)) {
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }
        try {
            String cachedJson = cacheService.getCache(id);
            if (StringUtils.isNotEmpty(cachedJson)) {
                log.info("Record coming from redis cache");
                response
                        .getResult()
                        .put(Constants.RESULT, objectMapper.readValue(cachedJson, new TypeReference<Object>() {
                        }));
            } else {
                Optional<ContentPartnerEntity> entityOptional = entityRepository.findById(id);
                if (entityOptional.isPresent()) {
                    ContentPartnerEntity entity = entityOptional.get();
                    cacheService.putCache(id, entity.getData());
                    log.info("Record coming from postgres db");
                    response
                            .getResult()
                            .put(Constants.RESULT,
                                    objectMapper.convertValue(
                                            entity.getData(), new TypeReference<Object>() {
                                            }));
                } else {
                    response.setResponseCode(HttpStatus.BAD_REQUEST);
                }
            }
        } catch (Exception e) {
            logger.error("error while processing", e);
            throw new RuntimeException(e);
        }
        return response;
    }

    @Override
    public CustomResponse searchEntity(SearchCriteria searchCriteria) {
        log.info("ContentPartnerServiceImpl::searchEntity:searching the content partner");
        String searchString = searchCriteria.getSearchString();
        CustomResponse response = new CustomResponse();
        if (searchString != null && searchString.length() < 2) {
            createErrorResponse(
                    response,
                    "Minimum 3 characters are required to search",
                    HttpStatus.BAD_REQUEST,
                    Constants.FAILED_CONST);
            return response;
        }
        try {
            SearchResult searchResult =
                    esUtilService.searchDocuments(Constants.CONTENT_PROVIDER_INDEX_NAME, searchCriteria);
            Map<String, Object> jsonMap =
                    objectMapper.convertValue(searchResult, new TypeReference<Map<String, Object>>() {
                    });
            response.setResult(jsonMap);
            createSuccessResponse(response);
            return response;
        } catch (Exception e) {
            logger.error("Error while processing to search", e);
            createErrorResponse(response, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR, Constants.FAILED_CONST);
            return response;
        }
    }

    @Override
    public String delete(String id) {
        log.info("ContentPartnerServiceImpl::delete:deleting the content partner");
        try {
            if (StringUtils.isNotEmpty(id)) {
                Optional<ContentPartnerEntity> entityOptional = entityRepository.findById(id);
                if (entityOptional.isPresent()) {
                    ContentPartnerEntity josnEntity = entityOptional.get();
                    JsonNode data = josnEntity.getData();
                    Timestamp currentTime = new Timestamp(System.currentTimeMillis());
                    if (data.get(Constants.IS_ACTIVE).asBoolean()) {
                        ((ObjectNode) data).put(Constants.IS_ACTIVE, false);
                        josnEntity.setData(data);
                        josnEntity.setId(id);
                        josnEntity.setUpdatedOn(currentTime);
                        ContentPartnerEntity updateJsonEntity = entityRepository.save(josnEntity);
                        Map<String, Object> map = objectMapper.convertValue(data, Map.class);
                        esUtilService.addDocument(Constants.CONTENT_PROVIDER_INDEX_NAME, Constants.INDEX_TYPE, id, map, cbServerProperties.getElasticContentJsonPath());
                        cacheService.putCache(id, data);
                        return Constants.DELETED_SUCCESSFULLY;
                    } else
                        return Constants.ALREADY_INACTIVE;
                } else return Constants.CONTENT_PARTNER_NOT_FOUND;
            } else return Constants.INVALID_ID;
        } catch (Exception e) {
            logger.error("Error deleting Entity with ID " + id + " " + e.getMessage());
            return "Error deleting Entity with ID " + id + " " + e.getMessage();
        }
    }

    public void validatePayload(String fileName, JsonNode payload) {
        try {
            JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance();
            InputStream schemaStream = schemaFactory.getClass().getResourceAsStream(fileName);
            JsonSchema schema = schemaFactory.getSchema(schemaStream);

            Set<ValidationMessage> validationMessages = schema.validate(payload);
            if (!validationMessages.isEmpty()) {
                StringBuilder errorMessage = new StringBuilder("Validation error(s): ");
                for (ValidationMessage message : validationMessages) {
                    errorMessage.append(message.getMessage());
                }
                throw new CustomException(Constants.ERROR, errorMessage.toString(), HttpStatus.BAD_REQUEST);
            }
        } catch (CustomException e) {
            logger.error("Failed to validate payload",e);
            throw new CustomException(Constants.ERROR, "Failed to validate payload: " + e.getMessage(), HttpStatus.BAD_REQUEST);
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
        response.getParams().setStatus(status);
        response.setResponseCode(httpStatus);
    }
}
