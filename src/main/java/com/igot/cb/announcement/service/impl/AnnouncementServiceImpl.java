package com.igot.cb.announcement.service.impl;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.igot.cb.announcement.entity.AnnouncementEntity;
import com.igot.cb.announcement.repository.AnnouncementRepository;
import com.igot.cb.announcement.service.AnnouncementService;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.dto.CustomResponse;
import com.igot.cb.pores.dto.RespParam;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.pores.util.PayloadValidation;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AnnouncementServiceImpl implements AnnouncementService {

  @Autowired
  private PayloadValidation payloadValidation;

  private Logger logger = LoggerFactory.getLogger(AnnouncementServiceImpl.class);
  
  @Autowired
  private AnnouncementRepository announcementRepository;

  @Autowired
  private EsUtilService esUtilService;

  @Autowired
  private CacheService cacheService;
  @Autowired
  private ObjectMapper objectMapper;

  private String requiredJsonFilePath = "/EsFieldsmapping/announcementEsMapping.json";

  @Autowired
  private RedisTemplate<String, SearchResult> redisTemplate;

  @Autowired
  private CbServerProperties serverProperties;

  @Value("${search.result.redis.ttl}")
  private long searchResultRedisTtl;

  @Value("${search.criteria.default.page.number}")
  private int defaultPageNumber;

  @Value("${search.criteria.default.page.size}")
  private int defaultPageSize;

  @Value("${search.criteria.default.facets}")
  private String facetsConfig;

  @Value("${search.criteria.default.requested.fields}")
  private String requestedFieldsConfig;

  @Override
  public CustomResponse createAnnouncement(JsonNode announcementEntity) {
    log.info("AnnouncementServiceImpl::createAnnouncement:inside");
    CustomResponse response = new CustomResponse();
    payloadValidation.validatePayload(Constants.ANNOUNCEMENT_VALIDATION_FILE_JSON, announcementEntity);
    try {
      if (announcementEntity.get(Constants.ID) == null) {
        log.info("AnnouncementServiceImpl::createAnnouncement:creating announcement");
        String id = String.valueOf(UUID.randomUUID());
        ((ObjectNode) announcementEntity).put(Constants.STATUS, Constants.ACTIVE);
        ((ObjectNode) announcementEntity).put(Constants.ANNOUNCEMENT_ID, id);
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        ((ObjectNode) announcementEntity).put(Constants.UPDATED_ON, String.valueOf(currentTime));
        ((ObjectNode) announcementEntity).put(Constants.CREATED_ON, String.valueOf(currentTime));
        AnnouncementEntity jsonNodeEntity = new AnnouncementEntity();
        jsonNodeEntity.setAnnouncementId(id);
        jsonNodeEntity.setData(announcementEntity);
        jsonNodeEntity.setCreatedOn(currentTime);
        jsonNodeEntity.setUpdatedOn(currentTime);
        jsonNodeEntity.setIsActive(true);
        AnnouncementEntity saveJsonEntity = announcementRepository.save(jsonNodeEntity);
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode jsonNode = objectMapper.createObjectNode();
        jsonNode.set(Constants.ANNOUNCEMENT_ID, new TextNode(saveJsonEntity.getAnnouncementId()));
        jsonNode.setAll((ObjectNode) saveJsonEntity.getData());
        Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
        esUtilService.addDocument(Constants.ANNOUNCEMENT_INDEX, Constants.INDEX_TYPE, id, map, requiredJsonFilePath);
        cacheService.putCache(jsonNodeEntity.getAnnouncementId(), jsonNode);
        map.put(Constants.ANNOUNCEMENT_ID, id);
        response.setResult(map);
        log.info("announcement created");
        response.setMessage(Constants.SUCCESSFULLY_CREATED);
      } else {
        log.info("Updating announcement");
        String exitingId = announcementEntity.get(Constants.ANNOUNCEMENT_ID).asText();
        Optional<AnnouncementEntity> content = announcementRepository.findById(exitingId);
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        if (content.isPresent()) {
          AnnouncementEntity josnEntity = content.get();
          josnEntity.setData(announcementEntity);
          josnEntity.setUpdatedOn(currentTime);
          AnnouncementEntity updateJsonEntity = announcementRepository.save(josnEntity);
          if (!org.springframework.util.ObjectUtils.isEmpty(updateJsonEntity)) {
            Map<String, Object> jsonMap =
                objectMapper.convertValue(updateJsonEntity.getData(), new TypeReference<Map<String, Object>>() {
                });
            updateJsonEntity.setAnnouncementId(exitingId);
            esUtilService.updateDocument(Constants.INDEX_NAME, Constants.INDEX_TYPE, exitingId, jsonMap, requiredJsonFilePath);
            cacheService.putCache(exitingId, updateJsonEntity);
            log.info("updated the announcement");
            jsonMap.put(Constants.ANNOUNCEMENT_ID, exitingId);
            response.setResult(jsonMap);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
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
  public CustomResponse searchAnnouncement(SearchCriteria searchCriteria) {
    log.info("AnnouncementServiceImpl::searchAnnouncement");
    CustomResponse response = new CustomResponse();
    SearchResult searchResult = redisTemplate.opsForValue()
        .get(generateRedisJwtTokenKey(searchCriteria));
    if (searchResult != null) {
      log.info("AnnouncementServiceImpl::searchAnnouncement:  search result fetched from redis");
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
      if (searchCriteria.getPageSize() == 0) {
        searchCriteria.setPageSize(serverProperties.getAnnouncementDefaultSearchPageSize());
      }

      Map<String, Object> expiredOnMap = new HashMap<>();
      SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
      expiredOnMap.put(Constants.SEARCH_OPERATION_GREATER_THAN_EQUALS, dateFormat.format(new Date()));
      if (MapUtils.isNotEmpty(searchCriteria.getFilterCriteriaMap())) {
        searchCriteria.getFilterCriteriaMap().put(Constants.EXPIRED_ON, expiredOnMap);
      } else {
        HashMap<String, Object> filterCriteria = new HashMap<>();
        filterCriteria.put(Constants.EXPIRED_ON, expiredOnMap);
        searchCriteria.setFilterCriteriaMap(filterCriteria);
      }

      searchResult =
          esUtilService.searchDocuments(Constants.ANNOUNCEMENT_INDEX, searchCriteria);
      response.getResult().putAll(objectMapper.convertValue(searchResult, Map.class));
      createSuccessResponse(response);
      return response;
    } catch (Exception e) {
      createErrorResponse(response, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR,
          Constants.FAILED_CONST);
      redisTemplate.opsForValue()
          .set(generateRedisJwtTokenKey(searchCriteria), searchResult, searchResultRedisTtl,
              TimeUnit.SECONDS);
      return response;
    }
  }

  public void createErrorResponse(
      CustomResponse response, String errorMessage, HttpStatus httpStatus, String status) {
    response.setParams(new RespParam());
    response.getParams().setStatus(status);
    response.setResponseCode(httpStatus);
  }
  public void createSuccessResponse(CustomResponse response) {
    response.setParams(new RespParam());
    response.getParams().setStatus(Constants.SUCCESS);
    response.setResponseCode(HttpStatus.OK);
  }

  public String generateRedisJwtTokenKey(Object requestPayload) {
    if (requestPayload != null) {
      try {
        String reqJsonString = objectMapper.writeValueAsString(requestPayload);
        return JWT.create()
            .withClaim(Constants.REQUEST_PAYLOAD, reqJsonString)
            .sign(Algorithm.HMAC256(serverProperties.getJwtSecretKey()));
      } catch (JsonProcessingException e) {
        logger.error("Error occurred while converting json object to json string", e);
      }
    }
    return "";
  }

  @Override
  public CustomResponse updateAnnouncement(JsonNode announcementDetails) {
    log.info("AnnouncementServiceImpl::read:inside the method");
    CustomResponse response = new CustomResponse();
    if (announcementDetails.get(Constants.ANNOUNCEMENT_ID) == null) {
      throw new CustomException(Constants.ERROR,
          "announcementDetailsEntity id is required for updating",
          HttpStatus.BAD_REQUEST);
    }
    Optional<AnnouncementEntity> optSchemeDetails = announcementRepository.findById(
        announcementDetails.get(Constants.ANNOUNCEMENT_ID).asText());
    Timestamp currentTime = new Timestamp(System.currentTimeMillis());
    ((ObjectNode) announcementDetails).put(Constants.STATUS, Constants.ACTIVE);
    if (optSchemeDetails.isPresent()) {
      AnnouncementEntity fetchedEntity = optSchemeDetails.get();
      JsonNode fetchedEntityData = fetchedEntity.getData();
      ((ObjectNode) announcementDetails).put(Constants.UPDATED_ON, String.valueOf(currentTime));
      ((ObjectNode) announcementDetails).put(Constants.CREATED_ON, fetchedEntityData.get(Constants.CREATED_ON));
      fetchedEntity.setData(announcementDetails);
      fetchedEntity.setUpdatedOn(currentTime);
      announcementRepository.save(fetchedEntity);
      ObjectNode jsonNode = objectMapper.createObjectNode();
      jsonNode.set(Constants.ANNOUNCEMENT_ID, new TextNode(fetchedEntity.getAnnouncementId()));
      jsonNode.setAll((ObjectNode) announcementDetails);

      Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
      esUtilService.addDocument(Constants.ANNOUNCEMENT_INDEX, Constants.INDEX_TYPE,
          announcementDetails.get(Constants.ANNOUNCEMENT_ID).asText(), map, requiredJsonFilePath);

      cacheService.putCache(fetchedEntity.getAnnouncementId(), jsonNode);
      log.info("updated announcement");
      map.put(Constants.ANNOUNCEMENT_ID, fetchedEntity.getAnnouncementId());
      response.setResult(map);
      response.setMessage(Constants.SUCCESSFULLY_UPDATED);
      response.setResponseCode(HttpStatus.OK);
      return response;
    } else {
      logger.error("no data found");
      throw new CustomException(Constants.ERROR, Constants.NO_DATA_FOUND, HttpStatus.NOT_FOUND);
    }
  }

  @Override
  public CustomResponse readAnnouncement(String id) {
    log.info("AnnouncementServiceImpl::read:inside the method");
    CustomResponse response = new CustomResponse();
    if (StringUtils.isEmpty(id)) {
      logger.error("AnnouncementServiceImpl::read:Id not found");
      response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
      response.setMessage(Constants.ID_NOT_FOUND);
      return response;
    }
    try {
      String cachedJson = cacheService.getCache(id);
      if (StringUtils.isNotEmpty(cachedJson)) {
        log.info("AnnouncementServiceImpl::read:Record coming from redis cache");
        response.setMessage(Constants.SUCCESSFULLY_READING);
        response
            .getResult()
            .put(Constants.DATA, objectMapper.readValue(cachedJson, new TypeReference<Object>() {
            }));
      } else {
        Optional<AnnouncementEntity> entityOptional = announcementRepository.findById(id);
        if (entityOptional.isPresent()) {
          AnnouncementEntity announcements = entityOptional.get();
          cacheService.putCache(id, announcements.getData());
          log.info("AnnouncementServiceImpl::read:Record coming from postgres db");
          response.setMessage(Constants.SUCCESSFULLY_READING);
          response
              .getResult()
              .put(Constants.DATA,
                  objectMapper.convertValue(
                      announcements.getData(), new TypeReference<Object>() {
                      }));
        } else {
          logger.error("Invalid Id: {}", id);
          response.setResponseCode(HttpStatus.NOT_FOUND);
          response.setMessage(Constants.INVALID_ID);
        }
      }
    } catch (Exception e) {
      logger.error("Error while mapping JSON for id {}: {}", id, e.getMessage(), e);
      throw new CustomException(Constants.ERROR, "error while processing",
          HttpStatus.INTERNAL_SERVER_ERROR);
    }
    return response;
  }

  @Override
  public CustomResponse deleteAnnouncement(String id) {
    log.info("AnnouncementServiceImpl::read:inside the method");
    CustomResponse response = new CustomResponse();
    Optional<AnnouncementEntity> optSchemeDetails = announcementRepository.findById(
        id);
    Timestamp currentTime = new Timestamp(System.currentTimeMillis());
    if (optSchemeDetails.isPresent()) {
      AnnouncementEntity fetchedEntity = optSchemeDetails.get();
      JsonNode fetchedJsonData = fetchedEntity.getData();
      ((ObjectNode) fetchedJsonData).put(Constants.UPDATED_ON, String.valueOf(currentTime));
      ((ObjectNode) fetchedJsonData).put(Constants.STATUS, Constants.IN_ACTIVE);
      fetchedEntity.setData(fetchedJsonData);
      fetchedEntity.setUpdatedOn(currentTime);
      fetchedEntity.setIsActive(false);
      announcementRepository.save(fetchedEntity);
      ObjectNode jsonNode = objectMapper.createObjectNode();
      jsonNode.set(Constants.ANNOUNCEMENT_ID, new TextNode(fetchedEntity.getAnnouncementId()));
      jsonNode.setAll((ObjectNode) fetchedJsonData);
      Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
      esUtilService.addDocument(Constants.ANNOUNCEMENT_INDEX, Constants.INDEX_TYPE,
          id, map, requiredJsonFilePath);
      cacheService.putCache(fetchedEntity.getAnnouncementId(), jsonNode);
      try {
          buildDefaultRequest(fetchedJsonData.get(Constants.CHANNEL).asText());
      } catch (Exception e) {
          throw new CustomException(Constants.ERROR, Constants.NO_DATA_FOUND, HttpStatus.INTERNAL_SERVER_ERROR);
      }
      log.info("deleted announcement");
      map.put(Constants.ANNOUNCEMENT_ID, fetchedEntity.getAnnouncementId());
      response.setResult(map);
      response.setMessage(Constants.SUCCESSFULLY_UPDATED);
      response.setResponseCode(HttpStatus.OK);
      return response;
    } else {
      logger.error("no data found");
      throw new CustomException(Constants.ERROR, Constants.NO_DATA_FOUND, HttpStatus.NOT_FOUND);
    }
  }

  public void buildDefaultRequest(String channelId) throws Exception {
      SearchCriteria criteria = new SearchCriteria();

      HashMap<String, Object> filterCriteriaMap = new HashMap<>();
      filterCriteriaMap.put(Constants.CHANNEL, Collections.singletonList(channelId));
      filterCriteriaMap.put(Constants.STATUS, Constants.ACTIVE);
      criteria.setFilterCriteriaMap(filterCriteriaMap);
      List<String> requestedFields = Arrays.stream(requestedFieldsConfig.split(","))
              .map(String::trim)
              .toList();

      criteria.setRequestedFields(requestedFields);
      criteria.setOrderBy(Constants.CREATED_ON);
      criteria.setOrderDirection(Constants.ASCENDING);
      List<String> defaultFacets = Arrays.stream(facetsConfig.split(","))
              .map(String::trim)
              .toList();
      criteria.setFacets(defaultFacets);
      criteria.setPageNumber(defaultPageNumber);
      criteria.setPageSize(defaultPageSize);
      redisTemplate.delete(generateRedisJwtTokenKey(criteria));
    }
}
