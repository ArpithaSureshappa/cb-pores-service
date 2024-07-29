package com.igot.cb.cios.service.impl;


import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.cios.dto.ObjectDto;
import com.igot.cb.cios.entity.CiosContentEntity;
import com.igot.cb.cios.entity.CornellContentEntity;
import com.igot.cb.cios.repository.CiosRepository;
import com.igot.cb.cios.repository.CornellContentRepository;
import com.igot.cb.cios.service.CiosContentService;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.exceptions.CustomException;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


@Service
@Slf4j
public class CiosContentServiceImpl implements CiosContentService {
    private static long environmentId = 10000000;
    private static String shardId = "1";
    private static AtomicInteger aInteger = new AtomicInteger(1);

    @Autowired
    private CornellContentRepository cornellContentRepository;

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

    public String generateId() {
        long env = environmentId / 10000000;
        long uid = System.currentTimeMillis();
        uid = uid << 13;
        return Constants.ID_PREFIX+env + "" + uid + "" + shardId + "" + aInteger.getAndIncrement();
    }

    @Override
    public Object fetchDataByContentId(String contentId){
        log.info("getting content by id: " + contentId);
        if (StringUtils.isEmpty(contentId)) {
            log.error("CiosContentServiceImpl::read:Id not found");
            throw new CustomException(Constants.ERROR,"contentId is mandatory",HttpStatus.BAD_REQUEST);
        }
        String cachedJson = cacheService.getCache(contentId);
        Object response = null;
        if (StringUtils.isNotEmpty(cachedJson)) {
            log.info("CiosContentServiceImpl::read:Record coming from redis cache");
            try {
                response=objectMapper.readValue(cachedJson, new TypeReference<Object>() {
                       });
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }else{
            Optional<CiosContentEntity> optionalJsonNodeEntity = ciosRepository.findByContentIdAndIsActive(contentId, true);
            if (optionalJsonNodeEntity.isPresent()) {
                CiosContentEntity ciosContentEntity = optionalJsonNodeEntity.get();
                cacheService.putCache(contentId, ciosContentEntity.getCiosData());
                log.info("CiosContentServiceImpl::read:Record coming from postgres db");
                response=objectMapper.convertValue(ciosContentEntity.getCiosData(), new TypeReference<Object>() {
                                        });
            } else {
                log.error("Invalid Id: {}", contentId);
                throw new CustomException(Constants.ERROR,"No data found for given Id",HttpStatus.BAD_REQUEST);
            }
        }
        return response;
    }

    @Override
    public Object deleteContent(String contentId) {
        log.info("CiosContentServiceImpl::read:inside the method");
        Optional<CiosContentEntity> ciosContentEntity = ciosRepository.findByContentIdAndIsActive(
                contentId,true);
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        if (ciosContentEntity.isPresent()) {
            CiosContentEntity fetchedEntity = ciosContentEntity.get();
            JsonNode fetchedJsonData = fetchedEntity.getCiosData();
            ((ObjectNode) fetchedJsonData.path("content")).put(Constants.UPDATED_ON, String.valueOf(currentTime));
            ((ObjectNode) fetchedJsonData.path("content")).put(Constants.IS_ACTIVE,Constants.ACTIVE_STATUS_FALSE);
            fetchedEntity.setCiosData(fetchedJsonData);
            fetchedEntity.setLastUpdatedOn(currentTime);
            fetchedEntity.setIsActive(false);
            ciosRepository.save(fetchedEntity);
            Map<String, Object> map = objectMapper.convertValue(fetchedEntity.getCiosData().get("content"), Map.class);
            esUtilService.addDocument(Constants.CIOS_INDEX_NAME, Constants.INDEX_TYPE,fetchedEntity.getContentId(), map, cbServerProperties.getElasticCiosJsonPath());
            cacheService.deleteCache(fetchedEntity.getContentId());
            log.info("deleted content");
            return "Content with id : " + contentId + " is deleted";
        } else {
            log.error("no data found");
            throw new CustomException(Constants.ERROR, Constants.NO_DATA_FOUND, HttpStatus.NOT_FOUND);
        }

    }

    @Override
    public Object fetchDataByExternalId(String externalid) {
        log.info("getting content by id: " + externalid);
        if (StringUtils.isEmpty(externalid)) {
            log.error("CiosContentServiceImpl::read:Id not found");
            throw new CustomException(Constants.ERROR,"externalid is mandatory",HttpStatus.BAD_REQUEST);
        }
        String cachedJson = cacheService.getCache(externalid);
        Object response = null;
        if (StringUtils.isNotEmpty(cachedJson)) {
            log.info("CiosContentServiceImpl::read:Record coming from redis cache");
            try {
                response=objectMapper.readValue(cachedJson, new TypeReference<Object>() {
                });
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }else{
            Optional<CiosContentEntity> optionalJsonNodeEntity = ciosRepository.findByExternalId(externalid);
            if (optionalJsonNodeEntity.isPresent()) {
                CiosContentEntity ciosContentEntity = optionalJsonNodeEntity.get();
                cacheService.putCache(externalid, ciosContentEntity.getCiosData());
                log.info("CiosContentServiceImpl::read:Record coming from postgres db");
                response=objectMapper.convertValue(ciosContentEntity.getCiosData(), new TypeReference<Object>() {
                });
            } else {
                log.error("Invalid Id: {}", externalid);
                throw new CustomException(Constants.ERROR,"No data found for given Id",HttpStatus.BAD_REQUEST);
            }
        }
        return response;
    }


    @Override
    public Object onboardCornellContent(List<ObjectDto> data) {
        try {
            log.info("CiosContentServiceImpl::createOrUpdateContent");
            for (ObjectDto dto : data) {
                CiosContentEntity ciosContentEntity = null;
                Optional<CornellContentEntity> dataFetched =
                        cornellContentRepository.findByExternalId(dto.getContentData().get("content").get("externalId").asText());
                if (dataFetched.isPresent()) {
                    log.info("data present in external table");
                    ciosContentEntity = createNewContent(dto);
                    CornellContentEntity externalContentEntity = dataFetched.get();
                    externalContentEntity.setIsActive(true);
                    cornellContentRepository.save(externalContentEntity);
                }
                ciosRepository.save(ciosContentEntity);
                log.info("Id of content created: {}",ciosContentEntity.getContentId());
                Map<String, Object> map = objectMapper.convertValue(ciosContentEntity.getCiosData().get("content"), Map.class);
                log.debug("map value for elastic search {}",map);
                cacheService.putCache(ciosContentEntity.getContentId(), ciosContentEntity.getCiosData());
                esUtilService.addDocument(Constants.CIOS_INDEX_NAME,Constants.INDEX_TYPE,ciosContentEntity.getContentId(), map, cbServerProperties.getElasticCiosJsonPath());
            }
            return "Success";
        } catch (Exception e) {
            throw new CustomException("ERROR", e.getMessage(),HttpStatus.BAD_REQUEST);
        }
    }

    private CiosContentEntity createNewContent(ObjectDto dto) {
        log.info("SidJobServiceImpl::createOrUpdateContent:updating the content");
        try {
            JsonNode jsonNode=dto.getContentData();
            payloadValidation.validatePayload(Constants.CIOS_CONTENT_VALIDATION_FILE_JSON,dto.getContentData());
            payloadValidation.validatePayload(Constants.COMPETENCY_AREA_VALIDATION_FILE_JSON, dto.getCompetencies_v5());
            payloadValidation.validatePayload(Constants.PAYLOAD_VALIDATION_FILE_CONTENT_PROVIDER, dto.getContentPartner());
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            CiosContentEntity igotContent = new CiosContentEntity();
            String externalId = jsonNode.path("content").path("externalId").asText();
            Optional<CiosContentEntity> ciosContentEntity = ciosRepository.findByExternalId(externalId);
            if (!ciosContentEntity.isPresent()) {
                igotContent.setContentId(generateId());
                igotContent.setExternalId(externalId);
                igotContent.setCreatedOn(currentTime);
                igotContent.setLastUpdatedOn(currentTime);
                igotContent.setIsActive(Constants.ACTIVE_STATUS);
                igotContent.setPartnerId(dto.getContentPartner().get("id").asText());
                ((ObjectNode) jsonNode.path("content")).put("contentId", igotContent.getContentId());
                ((ObjectNode) jsonNode.path("content")).put(Constants.CREATED_ON, String.valueOf(currentTime));
                ((ObjectNode) jsonNode.path("content")).put(Constants.LAST_UPDATED_ON, String.valueOf(currentTime));
                ((ObjectNode) jsonNode.path("content")).put(Constants.IS_ACTIVE, Constants.ACTIVE_STATUS);
                ((ObjectNode) jsonNode.path("content")).put(Constants.COMPETENCIES_V5,dto.getCompetencies_v5());
                ((ObjectNode) jsonNode.path("content")).put(Constants.CONTENT_PARTNER,dto.getContentPartner());
                addSearchTags(jsonNode);
                igotContent.setCiosData(jsonNode);
            } else {
                igotContent.setContentId(ciosContentEntity.get().getContentId());
                igotContent.setExternalId(ciosContentEntity.get().getExternalId());
                igotContent.setCreatedOn(ciosContentEntity.get().getCreatedOn());
                igotContent.setLastUpdatedOn(currentTime);
                igotContent.setIsActive(Constants.ACTIVE_STATUS);
                igotContent.setPartnerId(dto.getContentPartner().get("id").asText());
                ((ObjectNode) jsonNode.path("content")).put("contentId", ciosContentEntity.get().getContentId());
                ((ObjectNode) jsonNode.path("content")).put(Constants.CREATED_ON, String.valueOf(igotContent.getCreatedOn()));
                ((ObjectNode) jsonNode.path("content")).put(Constants.LAST_UPDATED_ON, String.valueOf(currentTime));
                ((ObjectNode) jsonNode.path("content")).put(Constants.COMPETENCIES_V5,dto.getCompetencies_v5());
                ((ObjectNode) jsonNode.path("content")).put(Constants.CONTENT_PARTNER,dto.getContentPartner());
                addSearchTags(jsonNode);
                igotContent.setCiosData(jsonNode);
            }
            return igotContent;
        }catch (Exception e){
            throw new CustomException(Constants.ERROR,e.getMessage(),HttpStatus.BAD_REQUEST);
        }
    }
    private JsonNode addSearchTags(JsonNode formattedData) {
        List<String> searchTags = new ArrayList<>();
        searchTags.add(formattedData.path("content").get("topic").textValue().toLowerCase());
        searchTags.add(formattedData.path("content").get("name").textValue().toLowerCase());
        searchTags.add(formattedData.path("content").path("contentPartner").get("contentPartnerName").asText().toLowerCase());
        ArrayNode searchTagsArray = objectMapper.valueToTree(searchTags);
        ((ObjectNode) formattedData.path("content")).set("searchTags", searchTagsArray);
        return formattedData;
    }

    @Override
    public SearchResult searchCotent(SearchCriteria searchCriteria) {
        log.info("CiosContentServiceImpl::searchCotent");
        SearchResult searchResult = redisTemplate.opsForValue()
                .get(generateRedisJwtTokenKey(searchCriteria));
        if (searchResult != null) {
            log.info("CiosContentServiceImpl::searchCotent:  search result fetched from redis");
            return searchResult;
        }
        try {
            searchResult= esUtilService.searchDocuments(Constants.CIOS_INDEX_NAME, searchCriteria);
            redisTemplate.opsForValue()
                    .set(generateRedisJwtTokenKey(searchCriteria), searchResult, searchResultRedisTtl,
                            TimeUnit.SECONDS);
            return searchResult;
        } catch (Exception e) {
            throw new CustomException("ERROR", e.getMessage(),HttpStatus.BAD_REQUEST);
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
                throw new CustomException(Constants.ERROR, errorMessage.toString(),HttpStatus.BAD_REQUEST);
            }
        } catch (Exception e) {
            throw new CustomException(Constants.ERROR, "Failed to validate payload: " + e.getMessage(),HttpStatus.BAD_REQUEST);
        }
    }
}
