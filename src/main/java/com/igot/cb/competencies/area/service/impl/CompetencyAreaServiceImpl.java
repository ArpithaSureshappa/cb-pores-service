package com.igot.cb.competencies.area.service.impl;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.competencies.area.entity.CompetencyAreaEntity;
import com.igot.cb.competencies.area.repository.CompetencyAreaRepository;
import com.igot.cb.competencies.area.service.CompetencyAreaService;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.dto.CustomResponse;
import com.igot.cb.pores.dto.RespParam;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.pores.util.FileProcessService;
import com.igot.cb.pores.util.PayloadValidation;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@Slf4j
public class CompetencyAreaServiceImpl implements CompetencyAreaService {

  @Autowired
  ObjectMapper objectMapper;

  @Autowired
  private PayloadValidation payloadValidation;

  @Autowired
  private EsUtilService esUtilService;

  @Autowired
  private CacheService cacheService;

  @Autowired
  private CbServerProperties cbServerProperties;

  @Autowired
  private CompetencyAreaRepository competencyAreaRepository;

  @Autowired
  private FileProcessService fileProcessService;

  @Autowired
  private AccessTokenValidator accessTokenValidator;

  @Value("${search.result.redis.ttl}")
  private long searchResultRedisTtl;

  @Autowired
  private RedisTemplate<String, SearchResult> redisTemplate;


  @Override
  public void loadCompetencyArea(MultipartFile file, String token) {
    log.info("CompetencyAreaService::loadCompetencyArea");
    String userId = accessTokenValidator.verifyUserToken(token);
    SearchCriteria searchCriteria = new SearchCriteria();
    searchCriteria.setPageNumber(0);
    searchCriteria.setPageSize(1000);
    searchCriteria.setRequestedFields(Collections.singletonList(Constants.TITLE));
    JsonNode dataJson = objectMapper.createObjectNode();
    try {
      if (esUtilService.isIndexPresent(Constants.COMP_AREA_INDEX_NAME)){
        SearchResult dataFetched = esUtilService.searchDocuments(Constants.COMP_AREA_INDEX_NAME, searchCriteria);
        if (!dataFetched.getData().isEmpty() && !dataFetched.getData().isNull()){
          dataJson = dataFetched.getData();
        }
      }
    } catch (Exception e) {
      log.error("Error occurred while creating compArea", e);
      throw new CustomException("error while processing", e.getMessage(),
          HttpStatus.INTERNAL_SERVER_ERROR);
    }
    if (!StringUtils.isBlank(userId)){
      List<Map<String, String>> processedData = fileProcessService.processExcelFile(file);
      log.info("No.of processedData from excel: " + processedData.size());
      JsonNode jsonNode = objectMapper.valueToTree(processedData);
      AtomicLong startingId = new AtomicLong(competencyAreaRepository.count());
      Map<String, Boolean> titles = new HashMap<>();
      List<CompetencyAreaEntity> competencyAreaEntityList = new ArrayList<>();
      List<JsonNode> competencydataNodeList = new ArrayList<>();
      dataJson.forEach(node -> {
        if (node.has(Constants.TITLE)) {
          titles.put(node.get(Constants.TITLE).asText().toLowerCase(), true);
        }
      });
      jsonNode.forEach(
          eachCompArea -> {
            if (!eachCompArea.isNull() && eachCompArea.has(Constants.COMPETENCY_AREA_TYPE)){
             if (!eachCompArea.get(
                 Constants.COMPETENCY_AREA_TYPE).asText().isEmpty()){
               if (!titles.containsKey(eachCompArea.get(Constants.COMPETENCY_AREA_TYPE).asText().toLowerCase())) {
                 String formattedId = String.format("COMAREA-%06d", startingId.incrementAndGet());
                 JsonNode dataNode = validateAndSetData(eachCompArea, userId, formattedId);
                 CompetencyAreaEntity competencyAreaEntity = createCompetencyArea(dataNode, formattedId);
                 competencyAreaEntityList.add(competencyAreaEntity);
                 competencydataNodeList.add(dataNode);
                 titles.put(dataNode.get(Constants.TITLE).asText().toLowerCase(), true);
               }

             }
            }

          });
      poresBulkSave(competencyAreaEntityList, competencydataNodeList);
    }
  }

  private void poresBulkSave(List<CompetencyAreaEntity> competencyAreaEntityList,
      List<JsonNode> competencydataNodeList) {
    log.info("CompetencyAreaService::poresBulkSave");
    try {
      competencyAreaRepository.saveAll(competencyAreaEntityList);
      esUtilService.saveAll(Constants.COMP_AREA_INDEX_NAME, Constants.INDEX_TYPE,
          competencydataNodeList);
      competencydataNodeList.forEach(dataNode -> {
        String formattedId = dataNode.get(Constants.ID).asText();
        cacheService.putCache(formattedId, dataNode);
      });
    } catch (Exception e) {
      log.error(e.getMessage());
    }

  }

  private CompetencyAreaEntity createCompetencyArea(JsonNode dataNode, String formattedId) {
    Timestamp currentTime = new Timestamp(System.currentTimeMillis());
    CompetencyAreaEntity competencyAreaEntity =new CompetencyAreaEntity();
    competencyAreaEntity.setId(formattedId);
    competencyAreaEntity.setData(dataNode);
    competencyAreaEntity.setIsActive(true);
    competencyAreaEntity.setCreatedOn(currentTime);
    competencyAreaEntity.setUpdatedOn(currentTime);
    return competencyAreaEntity;
  }

  private JsonNode validateAndSetData(JsonNode eachCompArea, String userId, String formattedId) {
    JsonNode dataNode = objectMapper.createObjectNode();
    ((ObjectNode) dataNode).put(Constants.ID, formattedId);
    ((ObjectNode) dataNode).put(Constants.TITLE, eachCompArea.get(Constants.COMPETENCY_AREA_TYPE).asText());
    String descriptionValue =
        (eachCompArea.has(Constants.DESCRIPTION_PAYLOAD) && !eachCompArea.get(
            Constants.DESCRIPTION_PAYLOAD).isNull())
            ? eachCompArea.get(Constants.DESCRIPTION).asText()
            : "";
    ((ObjectNode) dataNode).put(Constants.DESCRIPTION, descriptionValue);
    ((ObjectNode) dataNode).put(Constants.STATUS, Constants.LIVE);
    Timestamp currentTime = new Timestamp(System.currentTimeMillis());
    ((ObjectNode) dataNode).put(Constants.CREATED_ON, String.valueOf(currentTime));
    ((ObjectNode) dataNode).put(Constants.UPDATED_ON, String.valueOf(currentTime));
    ((ObjectNode) dataNode).put(Constants.CREATED_BY, userId);
    ((ObjectNode) dataNode).put(Constants.UPDATED_BY, userId);
    ((ObjectNode) dataNode).put(Constants.VERSION, 1);
    payloadValidation.validatePayload(Constants.COMP_AREA_PAYLOAD_VALIDATION,
        dataNode);
    List<String> searchTags = new ArrayList<>();
    searchTags.add(dataNode.get(Constants.TITLE).textValue().toLowerCase());
    ArrayNode searchTagsArray = objectMapper.valueToTree(searchTags);
    ((ObjectNode) dataNode).putArray(Constants.SEARCHTAGS).add(searchTagsArray);
    dataNode = addExtraFields(dataNode);
    return dataNode;
  }

  @Override
  public CustomResponse createCompArea(JsonNode competencyArea, String token) {
    log.info("CompetencyAreaService::createCompArea");
    payloadValidation.validatePayload(Constants.COMP_AREA_PAYLOAD_VALIDATION,
        competencyArea);
    CustomResponse response = new CustomResponse();
    SearchCriteria searchCriteria = new SearchCriteria();
    searchCriteria.setPageNumber(0);
    searchCriteria.setPageSize(5000);
    searchCriteria.setRequestedFields(Collections.singletonList(Constants.DESIGNATION));
    JsonNode dataJson = objectMapper.createObjectNode();
    try {
      if(esUtilService.isIndexPresent(Constants.COMP_AREA_INDEX_NAME)){
        SearchResult dataFetched = esUtilService.searchDocuments(Constants.COMP_AREA_INDEX_NAME, searchCriteria);
        if (!dataFetched.getData().isEmpty() && !dataFetched.getData().isNull()){
          dataJson = dataFetched.getData();
        }
      }
    } catch (Exception e) {
      log.error("Error occurred while fetching data from Es for duplicate check creating Designation", e);
      throw new CustomException("error while fetching data from Es for validation", e.getMessage(),
          HttpStatus.INTERNAL_SERVER_ERROR);
    }
    String userId = accessTokenValidator.verifyUserToken(token);
    if (StringUtils.isBlank(userId) || userId.equalsIgnoreCase(Constants.UNAUTHORIZED)) {
      response.getParams().setErrmsg(Constants.USER_ID_DOESNT_EXIST);
      response.setResponseCode(HttpStatus.BAD_REQUEST);
      return response;
    }
    try {
      Map<String, Boolean> titles = new HashMap<>();
      if (!dataJson.isEmpty() && !dataJson.isNull()){
        dataJson.forEach(node -> {
          if (node.has(Constants.TITLE)) {
            titles.put(node.get(Constants.TITLE).asText().toLowerCase(), true);
          }
        });
      }
      if (!titles.containsKey(competencyArea.get(Constants.TITLE).asText().toLowerCase())) {
        AtomicLong count = new AtomicLong(competencyAreaRepository.count());
        CompetencyAreaEntity competencyAreaEntity = new CompetencyAreaEntity();
        String formattedId = String.format("COMAREA-%06d", count.incrementAndGet());
        ((ObjectNode) competencyArea).put(Constants.STATUS, Constants.LIVE);
        ((ObjectNode) competencyArea).put(Constants.ID, formattedId);
        ((ObjectNode) competencyArea).put(Constants.IS_ACTIVE, true);
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        ((ObjectNode) competencyArea).put(Constants.CREATED_ON, String.valueOf(currentTime));
        ((ObjectNode) competencyArea).put(Constants.UPDATED_ON, String.valueOf(currentTime));
        ((ObjectNode) competencyArea).put(Constants.CREATED_BY, userId);
        ((ObjectNode) competencyArea).put(Constants.UPDATED_BY, userId);
        List<String> searchTags = new ArrayList<>();
        searchTags.add(competencyArea.get(Constants.TITLE).textValue().toLowerCase());
        ArrayNode searchTagsArray = objectMapper.valueToTree(searchTags);
        ((ObjectNode) competencyArea).putArray(Constants.SEARCHTAGS).add(searchTagsArray);
        ((ObjectNode) competencyArea).put(Constants.TYPE, Constants.COMPETENCY_AREA_TYPE);
        ((ObjectNode) competencyArea).put(Constants.VERSION, 1);
        competencyAreaEntity.setId(formattedId);
        competencyAreaEntity.setData(competencyArea);
        competencyAreaEntity.setIsActive(true);
        competencyAreaEntity.setCreatedOn(currentTime);
        competencyAreaEntity.setUpdatedOn(currentTime);
        competencyAreaRepository.save(competencyAreaEntity);
        log.info(
            "CompetencyAreaService::createCompArea::persited comArea in postgres with id: "
                + formattedId);
        Map<String, Object> map = objectMapper.convertValue(competencyArea, Map.class);
        esUtilService.addDocument(Constants.COMP_AREA_INDEX_NAME, Constants.INDEX_TYPE,
            formattedId, map, cbServerProperties.getElasticCompJsonPath());
        cacheService.putCache(formattedId, competencyArea);
        log.info(
            "CompetencyAreaService::createCompArea::created the compArea with: "
                + formattedId);
        response.setMessage(Constants.SUCCESSFULLY_CREATED);
        map.put(Constants.ID, competencyAreaEntity.getId());
        response.setResult(map);
        response.setResponseCode(HttpStatus.OK);
        return response;

      }else {
        response.getParams().setErrmsg("Already Present");
        response.setResponseCode(HttpStatus.BAD_REQUEST);
        return response;
      }

    }catch (Exception e){
      log.error("Error occurred while creating compArea", e);
      throw new CustomException("error while processing", e.getMessage(),
          HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  private JsonNode addExtraFields(JsonNode competencyArea) {
    log.info("CompetencyAreaService::addExtraFields");
    ((ObjectNode) competencyArea).put(Constants.TYPE, Constants.COMPETENCY_AREA_TYPE);
    ((ObjectNode) competencyArea).put(Constants.VERSION, 1);
    ((ObjectNode) competencyArea).put(Constants.SOURCE, (JsonNode) null);
    ((ObjectNode) competencyArea).putArray(Constants.ADDITIONAL_PROPERTIES);
    ((ObjectNode) competencyArea).put(Constants.LEVEL, Constants.INITIATIVE);
    ((ObjectNode) competencyArea).put(Constants.IS_ACTIVE, true);
    ((ObjectNode) competencyArea).put(Constants.REVIEWED_BY, (JsonNode) null);
    ((ObjectNode) competencyArea).put(Constants.REVIEWED_DATE, (JsonNode) null);
    ((ObjectNode) competencyArea).put(Constants.ADDITIONAL_PROPERTIES, (JsonNode) null);
    return competencyArea;
  }

  @Override
  public CustomResponse updateCompArea(JsonNode updatedCompArea) {
    log.info("CompetencyAreaService::updateCompArea");
    payloadValidation.validatePayload(Constants.COMP_AREA_PAYLOAD_VALIDATION,
        updatedCompArea);
    CustomResponse response = new CustomResponse();
    try {
      if (updatedCompArea.has(Constants.ID) && !updatedCompArea.get(Constants.ID)
          .isNull()) {
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        Optional<CompetencyAreaEntity> compArea = competencyAreaRepository.findById(
            updatedCompArea.get(Constants.ID).asText());
        CompetencyAreaEntity competencyAreaEntityUpdated = null;
        if (compArea.isPresent()) {
          JsonNode dataNode = compArea.get().getData();
          Iterator<Entry<String, JsonNode>> fields = updatedCompArea.fields();
          while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldName = field.getKey();
            // Check if the field is present in the update JsonNode
            if (dataNode.has(fieldName)) {
              // Update the main JsonNode with the value from the update JsonNode
              ((ObjectNode) dataNode).set(fieldName, updatedCompArea.get(fieldName));
            } else {
              ((ObjectNode) dataNode).put(fieldName, updatedCompArea.get(fieldName));
            }
          }
          compArea.get().setUpdatedOn(currentTime);
          ((ObjectNode) dataNode).put(Constants.UPDATED_ON, new TextNode(
              convertTimeStampToDate(compArea.get().getUpdatedOn().getTime())));
          competencyAreaEntityUpdated = competencyAreaRepository.save(compArea.get());
          ObjectNode jsonNode = objectMapper.createObjectNode();
          jsonNode.set(Constants.ID,
              new TextNode(updatedCompArea.get(Constants.ID).asText()));
          jsonNode.setAll((ObjectNode) competencyAreaEntityUpdated.getData());
          Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
          esUtilService.updateDocument(Constants.COMP_AREA_INDEX_NAME, Constants.INDEX_TYPE,
              competencyAreaEntityUpdated.getId(), map,
              cbServerProperties.getElasticCompJsonPath());
          cacheService.putCache(competencyAreaEntityUpdated.getId(),
              competencyAreaEntityUpdated.getData());
          log.info("updated the CompArea");
          response.setMessage(Constants.SUCCESSFULLY_UPDATED);
          map.put(Constants.ID, competencyAreaEntityUpdated.getId());
          response.setResult(map);
          response.setResponseCode(HttpStatus.OK);
          log.info("CompetencyAreaService::updateCompArea::persited in Pores");
          return response;
        }else {
          response.setMessage("No data found for this id");
          response.setResponseCode(HttpStatus.BAD_REQUEST);
          return response;
        }
      }else {
        response.setMessage("Id is missing");
        response.setResponseCode(HttpStatus.BAD_REQUEST);
        return response;
      }
    }catch (Exception e){
      log.error("Error while processing file: {}", e.getMessage());
      throw new RuntimeException(e.getMessage());
    }
  }

  @Override
  public CustomResponse searchCompArea(SearchCriteria searchCriteria) {
    log.info("CompetencyAreaService::updateCompArea::persited interest in Pores");
    CustomResponse response = new CustomResponse();
    SearchResult searchResult = redisTemplate.opsForValue()
        .get(generateRedisJwtTokenKey(searchCriteria));
    if (searchResult != null) {
      log.info("searchCompetencyArea:search result fetched from redis");
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
          esUtilService.searchDocuments(Constants.COMP_AREA_INDEX_NAME, searchCriteria);
      response.getResult().put(Constants.RESULT, searchResult);
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

  @Override
  public CustomResponse readCompArea(String id) {
    log.info("CompetencyAreaServiceImpl::readCompArea");
    CustomResponse response = new CustomResponse();
    if (StringUtils.isEmpty(id)) {
      log.error("CompetencyAreaServiceImpl::read:Id not found");
      response.setResponseCode(HttpStatus.BAD_REQUEST);
      response.setMessage(Constants.ID_NOT_FOUND);
      return response;
    }
    try {
      String cachedJson = cacheService.getCache(id);
      if (StringUtils.isNotEmpty(cachedJson)) {
        log.info("CompetencyAreaServiceImpl::read:Record coming from redis cache");
        response.setMessage(Constants.SUCCESSFULLY_READING);
        response
            .getResult()
            .put(Constants.RESULT, objectMapper.readValue(cachedJson, new TypeReference<Object>() {
            }));
        response.setResponseCode(HttpStatus.OK);
      } else {
        Optional<CompetencyAreaEntity> entityOptional = competencyAreaRepository.findByIdAndIsActive(id, true);
        if (entityOptional.isPresent()) {
          CompetencyAreaEntity competencyAreaEntity = entityOptional.get();
          cacheService.putCache(id, competencyAreaEntity.getData());
          log.info("CompetencyAreaServiceImpl::readCompArea:Record coming from postgres db");
          response.setMessage(Constants.SUCCESSFULLY_READING);
          response
              .getResult()
              .put(Constants.RESULT,
                  objectMapper.convertValue(
                      competencyAreaEntity.getData(), new TypeReference<Object>() {
                      }));
          response.setResponseCode(HttpStatus.OK);
        } else {
          log.error("Invalid Id: {}", id);
          response.setResponseCode(HttpStatus.NOT_FOUND);
          response.setMessage(Constants.INVALID_ID);
        }
      }
    } catch (Exception e) {
      log.error("Error while Fetching the data", id, e.getMessage(), e);
      throw new CustomException(Constants.ERROR, "error while processing",
          HttpStatus.INTERNAL_SERVER_ERROR);
    }
    return response;
  }

  @Override
  public CustomResponse deleteCompetencyArea(String id) {
    log.info("CompetencyAreaServiceImpl::deleteCompetencyArea");
    CustomResponse response = new CustomResponse();
    try {
      Optional<CompetencyAreaEntity> optionalEntity = competencyAreaRepository.findByIdAndIsActive(id, true);
      if (optionalEntity.isPresent()){
        CompetencyAreaEntity competencyAreaEntity = optionalEntity.get();
        competencyAreaEntity.setIsActive(false);
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        competencyAreaEntity.setUpdatedOn(currentTime);
        ((ObjectNode) competencyAreaEntity.getData()).put(Constants.UPDATED_ON, String.valueOf(currentTime));
        ((ObjectNode) competencyAreaEntity.getData()).put(Constants.STATUS, Constants.IN_ACTIVE);
        ((ObjectNode) competencyAreaEntity.getData()).put(Constants.IS_ACTIVE, false);
        competencyAreaRepository.save(competencyAreaEntity);
        Map<String, Object> map = objectMapper.convertValue(competencyAreaEntity.getData(), Map.class);
        esUtilService.addDocument(Constants.COMP_AREA_INDEX_NAME, Constants.INDEX_TYPE,
            competencyAreaEntity.getId(), map, cbServerProperties.getElasticCompJsonPath());
        cacheService.deleteCache(id);
        response.setResponseCode(HttpStatus.OK);
        response.setMessage(Constants.DELETED_SUCCESSFULLY);
        return response;
      }else {
        response.setMessage("CompetencyAreaServiceImpl::deleteCompetencyArea:No data found for this id");
        response.setResponseCode(HttpStatus.BAD_REQUEST);
        return response;
      }
    } catch (Exception e) {
      log.error(e.getMessage());
      response.getParams().setStatus(Constants.FAILED);
      response.setMessage(e.getMessage());
      response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
      return response;
    }
  }

  private String convertTimeStampToDate(long timeStamp) {
    Instant instant = Instant.ofEpochMilli(timeStamp);
    OffsetDateTime dateTime = instant.atOffset(ZoneOffset.UTC);
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy'T'HH:mm:ss.SSS'Z'");
    return dateTime.format(formatter);
  }

  public String generateRedisJwtTokenKey(Object requestPayload) {
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

  private boolean existsInDataJson(JsonNode dateJson, String competencyAreaType) {
    for (JsonNode node : dateJson) {
      if (node.has("title") && node.get("title").asText().equals(competencyAreaType)) {
        return true;
      }
    }
    return false;
  }
}
