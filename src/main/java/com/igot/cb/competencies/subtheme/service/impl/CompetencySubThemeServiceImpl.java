package com.igot.cb.competencies.subtheme.service.impl;

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
import com.igot.cb.competencies.subtheme.entity.CompetencySubThemeEntity;
import com.igot.cb.competencies.subtheme.repository.CompetencySubThemeRepository;
import com.igot.cb.competencies.subtheme.service.CompetencySubThemeService;
import com.igot.cb.competencies.theme.enity.CompetencyThemeEntity;
import com.igot.cb.designation.service.DesignationService;
import com.igot.cb.playlist.util.ProjectUtil;
import com.igot.cb.pores.Service.OutboundRequestHandlerServiceImpl;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.dto.CustomResponse;
import com.igot.cb.pores.dto.RespParam;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Map.Entry;
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
public class CompetencySubThemeServiceImpl implements CompetencySubThemeService {

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
  private FileProcessService fileProcessService;

  @Autowired
  private AccessTokenValidator accessTokenValidator;

  @Value("${search.result.redis.ttl}")
  private long searchResultRedisTtl;

  @Autowired
  private RedisTemplate<String, SearchResult> redisTemplate;

  @Autowired
  private CompetencySubThemeRepository competencySubThemeRepository;

  private @Autowired OutboundRequestHandlerServiceImpl outboundRequestHandlerServiceImpl;

  @Autowired
  private DesignationService designationService;

  @Override
  public void loadCompetencySubTheme(MultipartFile file, String token) {
    log.info("CompetencySubThemeService::loadCompetencySubTheme");
    String userId = accessTokenValidator.verifyUserToken(token);
    SearchCriteria searchCriteria = new SearchCriteria();
    searchCriteria.setPageNumber(0);
    searchCriteria.setPageSize(5000);
    searchCriteria.setRequestedFields(Collections.singletonList(Constants.DESIGNATION));
    JsonNode dataJson = objectMapper.createObjectNode();
    try {
      if(esUtilService.isIndexPresent(Constants.COMP_SUB_THEME_INDEX_NAME)){
        SearchResult dataFetched = esUtilService.searchDocuments(Constants.COMP_SUB_THEME_INDEX_NAME, searchCriteria);
        if (!dataFetched.getData().isEmpty() && !dataFetched.getData().isNull()){
          dataJson = dataFetched.getData();
        }
      }
    } catch (Exception e) {
      log.error("Error occurred while fetching data from Es for duplicate check creating Designation", e);
      throw new CustomException("error while fetching data from Es for validation", e.getMessage(),
          HttpStatus.INTERNAL_SERVER_ERROR);
    }
    if (!StringUtils.isBlank(userId)){
      List<Map<String, String>> processedData = fileProcessService.processExcelFile(file);
      log.info("No.of processedData from excel: " + processedData.size());
      JsonNode jsonNode = objectMapper.valueToTree(processedData);
      AtomicLong startingId = new AtomicLong(competencySubThemeRepository.count());
      List<CompetencySubThemeEntity> competencySubThemeEntityList = new ArrayList<>();
      List<JsonNode> competencySubThemeDataNodesList = new ArrayList<>();
      Map<String, Boolean> titles = new HashMap<>();
      if (!dataJson.isEmpty() && !dataJson.isNull()){
        dataJson.forEach(node -> {
          if (node.has(Constants.TITLE)) {
            titles.put(node.get(Constants.TITLE).asText().toLowerCase(), true);
          }
        });
      }
      jsonNode.forEach(
          eachCompSubTheme -> {
            if (!eachCompSubTheme.isNull() &&  eachCompSubTheme.has(Constants.COMPETENCY_SUB_THEME_TYPE) && !eachCompSubTheme.get(Constants.COMPETENCY_SUB_THEME_TYPE).isNull()){
              if (!titles.containsKey(eachCompSubTheme.get(Constants.COMPETENCY_SUB_THEME_TYPE).asText().toLowerCase())) {
                if (!eachCompSubTheme.get(
                    Constants.COMPETENCY_SUB_THEME_TYPE).asText().isEmpty()){
                  String formattedId = String.format("COMSUBTHEME-%06d", startingId.incrementAndGet());
                  JsonNode dataNode = validateAndSetData(eachCompSubTheme, userId, formattedId);
                  CompetencySubThemeEntity competencySubThemeEntity = createCompetenecySubThemeEntity(dataNode,formattedId);
                  competencySubThemeEntityList.add(competencySubThemeEntity);
                  competencySubThemeDataNodesList.add(dataNode);
                  titles.put(dataNode.get(Constants.TITLE).asText().toLowerCase(), true);
                }
              }
            }
          });
      poresBulkSave(competencySubThemeEntityList, competencySubThemeDataNodesList);
    }
  }

  private void poresBulkSave(List<CompetencySubThemeEntity> competencySubThemeEntityList, List<JsonNode> competencySubThemeDataNodesList){
    log.info("CompetencySubThemeService::poresBulkSave");
    try {
      competencySubThemeRepository.saveAll(competencySubThemeEntityList);
      esUtilService.saveAll(Constants.COMP_SUB_THEME_INDEX_NAME, Constants.INDEX_TYPE,
          competencySubThemeDataNodesList);
      competencySubThemeDataNodesList.forEach(dataNode -> {
        String formattedId = dataNode.get(Constants.ID).asText();
        cacheService.putCache(formattedId, dataNode);
      });
    } catch (Exception e) {
      log.error(e.getMessage());
    }
  }

  private JsonNode validateAndSetData(JsonNode eachCompSubTheme, String userId, String formattedId){
    JsonNode dataNode = objectMapper.createObjectNode();
    ((ObjectNode) dataNode).put(Constants.ID, formattedId);
    ((ObjectNode) dataNode).put(Constants.TITLE, eachCompSubTheme.get(Constants.COMPETENCY_SUB_THEME_TYPE).asText());
    String descriptionValue =
        (eachCompSubTheme.has(Constants.DESCRIPTION_PAYLOAD) && !eachCompSubTheme.get(
            Constants.DESCRIPTION_PAYLOAD).isNull())
            ? eachCompSubTheme.get(Constants.DESCRIPTION).asText()
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
    if(eachCompSubTheme.has(Constants.COMPETENCY_TYPE) && !eachCompSubTheme.get(
        Constants.COMPETENCY_TYPE).asText().isEmpty()){
      JsonNode addtionalProperty = objectMapper.createObjectNode();
      ((ObjectNode) addtionalProperty).put(Constants.THEME_TYPE, eachCompSubTheme.get(
          Constants.COMPETENCY_TYPE).asText());
      ((ObjectNode) dataNode).put(Constants.ADDITIONAL_PROPERTIES, addtionalProperty);
    }
    return dataNode;
  }


  private CompetencySubThemeEntity createCompetenecySubThemeEntity(JsonNode dataNode, String formattedId){
    CompetencySubThemeEntity competencySubThemeEntity = new CompetencySubThemeEntity();
    competencySubThemeEntity.setId(formattedId);
    competencySubThemeEntity.setData(dataNode);
    competencySubThemeEntity.setIsActive(true);
    Timestamp currentTime = new Timestamp(System.currentTimeMillis());
    competencySubThemeEntity.setCreatedOn(currentTime);
    competencySubThemeEntity.setUpdatedOn(currentTime);
    return competencySubThemeEntity;
  }

  private JsonNode addExtraFields(JsonNode jsonNode) {
    log.info("CompetencySubThemeService::addExtraFields");
    ((ObjectNode) jsonNode).put(Constants.TYPE, Constants.COMPETENCY_SUB_THEME_TYPE);
    ((ObjectNode) jsonNode).put(Constants.VERSION, 1);
    ((ObjectNode) jsonNode).put(Constants.SOURCE, (JsonNode) null);
    ((ObjectNode) jsonNode).putArray(Constants.ADDITIONAL_PROPERTIES);
    ((ObjectNode) jsonNode).put(Constants.LEVEL, Constants.INITIATIVE);
    ((ObjectNode) jsonNode).put(Constants.IS_ACTIVE, true);
    ((ObjectNode) jsonNode).put(Constants.REVIEWED_BY, (JsonNode) null);
    ((ObjectNode) jsonNode).put(Constants.REVIEWED_DATE, (JsonNode) null);
    ((ObjectNode) jsonNode).put(Constants.ADDITIONAL_PROPERTIES, (JsonNode) null);
    return jsonNode;
  }

  @Override
  public CustomResponse searchCompSubTheme(SearchCriteria searchCriteria) {
    log.info("CompetencySubThemeService::searchCompSubTheme");
    CustomResponse response = new CustomResponse();
    SearchResult searchResult = redisTemplate.opsForValue()
        .get(generateRedisJwtTokenKey(searchCriteria));
    if (searchResult != null) {
      log.info("CompetencySubThemeService::searchCompSubTheme:search result fetched from redis");
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
          esUtilService.searchDocuments(Constants.COMP_SUB_THEME_INDEX_NAME, searchCriteria);
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
  public CustomResponse createCompSubTheme(JsonNode competencySubTheme, String token) {
    log.info("CompetencySubThemeServiceImpl::createCompSubTheme");
    payloadValidation.validatePayload(Constants.COMP_AREA_PAYLOAD_VALIDATION,
        competencySubTheme);
    CustomResponse response = new CustomResponse();
    String userId = accessTokenValidator.verifyUserToken(token);
    SearchCriteria searchCriteria = new SearchCriteria();
    searchCriteria.setPageNumber(0);
    searchCriteria.setPageSize(5000);
    searchCriteria.setRequestedFields(Collections.singletonList(Constants.DESIGNATION));
    JsonNode dataJson = objectMapper.createObjectNode();
    try {
      if(esUtilService.isIndexPresent(Constants.COMP_SUB_THEME_INDEX_NAME)){
        SearchResult dataFetched = esUtilService.searchDocuments(Constants.COMP_SUB_THEME_INDEX_NAME, searchCriteria);
        if (!dataFetched.getData().isEmpty() && !dataFetched.getData().isNull()){
          dataJson = dataFetched.getData();
        }
      }
    } catch (Exception e) {
      log.error("Error occurred while fetching data from Es for duplicate check creating Designation", e);
      throw new CustomException("error while fetching data from Es for validation", e.getMessage(),
          HttpStatus.INTERNAL_SERVER_ERROR);
    }
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
      if (!titles.containsKey(competencySubTheme.get(Constants.TITLE).asText().toLowerCase())) {
        AtomicLong count = new AtomicLong(competencySubThemeRepository.count());
        CompetencySubThemeEntity competencySubThemeEntity = new CompetencySubThemeEntity();
        String formattedId = String.format("COMSUBTHEME-%06d", count.incrementAndGet());
        ((ObjectNode) competencySubTheme).put(Constants.STATUS, Constants.LIVE);
        ((ObjectNode) competencySubTheme).put(Constants.ID, formattedId);
        ((ObjectNode) competencySubTheme).put(Constants.IS_ACTIVE, true);
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        ((ObjectNode) competencySubTheme).put(Constants.CREATED_ON, String.valueOf(currentTime));
        ((ObjectNode) competencySubTheme).put(Constants.UPDATED_ON, String.valueOf(currentTime));
        ((ObjectNode) competencySubTheme).put(Constants.CREATED_BY, userId);
        ((ObjectNode) competencySubTheme).put(Constants.UPDATED_BY, userId);
        List<String> searchTags = new ArrayList<>();
        searchTags.add(competencySubTheme.get(Constants.TITLE).textValue().toLowerCase());
        ArrayNode searchTagsArray = objectMapper.valueToTree(searchTags);
        ((ObjectNode) competencySubTheme).putArray(Constants.SEARCHTAGS).add(searchTagsArray);
        ((ObjectNode) competencySubTheme).put(Constants.TYPE, Constants.COMPETENCY_SUB_THEME_TYPE);
        ((ObjectNode) competencySubTheme).put(Constants.VERSION, 1);
        competencySubThemeEntity.setId(formattedId);
        competencySubThemeEntity.setData(competencySubTheme);
        competencySubThemeEntity.setIsActive(true);
        competencySubThemeEntity.setCreatedOn(currentTime);
        competencySubThemeEntity.setUpdatedOn(currentTime);
        competencySubThemeRepository.save(competencySubThemeEntity);
        log.info(
            "CompetencySubThemeServiceImpl::createCompSubTheme::persited data in postgres with id: "
                + formattedId);
        Map<String, Object> map = objectMapper.convertValue(competencySubTheme, Map.class);
        esUtilService.addDocument(Constants.COMP_SUB_THEME_INDEX_NAME, Constants.INDEX_TYPE,
            formattedId, map, cbServerProperties.getElasticCompJsonPath());
        cacheService.putCache(formattedId, competencySubTheme);
        log.info(
            "CompetencySubThemeServiceImpl::createCompSubTheme::created the compArea with: "
                + formattedId);
        response.setMessage(Constants.SUCCESSFULLY_CREATED);
        map.put(Constants.ID, competencySubThemeEntity.getId());
        response.setResult(map);
        response.setResponseCode(HttpStatus.OK);
        return response;
      }else {
        response.getParams().setErrmsg("Already Present");
        response.setResponseCode(HttpStatus.BAD_REQUEST);
        return response;
      }
    }catch (Exception e){
      log.error("Error occurred while creating compSubTheme", e);
      throw new CustomException("error while creating", e.getMessage(),
          HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  @Override
  public CustomResponse updateCompSubTheme(JsonNode updatedCompSubTheme) {
    log.info("CompetencyThemeServiceImpl::updateCompSubTheme");
    payloadValidation.validatePayload(Constants.COMP_AREA_PAYLOAD_VALIDATION,
        updatedCompSubTheme);
    CustomResponse response = new CustomResponse();
    try {
      if (updatedCompSubTheme.has(Constants.ID) && !updatedCompSubTheme.get(Constants.ID)
          .isNull()) {
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        Optional<CompetencySubThemeEntity> compTheme = competencySubThemeRepository.findById(
            updatedCompSubTheme.get(Constants.ID).asText());
        CompetencySubThemeEntity competencySubThemeEntity = null;
        if (compTheme.isPresent()) {
          JsonNode dataNode = compTheme.get().getData();
          Iterator<Entry<String, JsonNode>> fields = updatedCompSubTheme.fields();
          while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldName = field.getKey();
            // Check if the field is present in the update JsonNode
            if (dataNode.has(fieldName)) {
              // Update the main JsonNode with the value from the update JsonNode
              ((ObjectNode) dataNode).set(fieldName, updatedCompSubTheme.get(fieldName));
            } else {
              ((ObjectNode) dataNode).put(fieldName, updatedCompSubTheme.get(fieldName));
            }
          }
          compTheme.get().setUpdatedOn(currentTime);
          ((ObjectNode) dataNode).put(Constants.UPDATED_ON, new TextNode(
              convertTimeStampToDate(compTheme.get().getUpdatedOn().getTime())));
          competencySubThemeEntity = competencySubThemeRepository.save(compTheme.get());
          ObjectNode jsonNode = objectMapper.createObjectNode();
          jsonNode.set(Constants.ID,
              new TextNode(updatedCompSubTheme.get(Constants.ID).asText()));
          jsonNode.setAll((ObjectNode) competencySubThemeEntity.getData());
          Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
          esUtilService.updateDocument(Constants.COMP_SUB_THEME_INDEX_NAME, Constants.INDEX_TYPE,
              competencySubThemeEntity.getId(), map,
              cbServerProperties.getElasticCompJsonPath());
          cacheService.putCache(competencySubThemeEntity.getId(),
              competencySubThemeEntity.getData());
          log.info("updated the CompSubTheme");
          response.setMessage(Constants.SUCCESSFULLY_UPDATED);
          map.put(Constants.ID, competencySubThemeEntity.getId());
          response.setResult(map);
          response.setResponseCode(HttpStatus.OK);
          log.info("CompetencyThemeServiceImpl::updateCompSubTheme::persited in Pores");
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
  public CustomResponse readCompSubTheme(String id) {
    log.info("CompetencySubThemeServiceImpl::readCompSubTheme");
    CustomResponse response = new CustomResponse();
    if (StringUtils.isEmpty(id)) {
      log.error("CompetencySubThemeServiceImpl::readCompSubTheme:Id not found");
      response.setResponseCode(HttpStatus.BAD_REQUEST);
      response.setMessage(Constants.ID_NOT_FOUND);
      return response;
    }
    try {
      String cachedJson = cacheService.getCache(id);
      if (StringUtils.isNotEmpty(cachedJson)) {
        log.info("CompetencySubThemeServiceImpl::readCompSubTheme:Record coming from redis cache");
        response.setMessage(Constants.SUCCESSFULLY_READING);
        response
            .getResult()
            .put(Constants.RESULT, objectMapper.readValue(cachedJson, new TypeReference<Object>() {
            }));
        response.setResponseCode(HttpStatus.OK);
      } else {
        Optional<CompetencySubThemeEntity> entityOptional = competencySubThemeRepository.findByIdAndIsActive(id, true);
        if (entityOptional.isPresent()) {
          CompetencySubThemeEntity competencySubThemeEntity = entityOptional.get();
          cacheService.putCache(id, competencySubThemeEntity.getData());
          log.info("CompetencySubThemeServiceImpl::readCompSubTheme:Record coming from postgres db");
          response.setMessage(Constants.SUCCESSFULLY_READING);
          response
              .getResult()
              .put(Constants.RESULT,
                  objectMapper.convertValue(
                      competencySubThemeEntity.getData(), new TypeReference<Object>() {
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
  public CustomResponse deleteCompetencySubTheme(String id) {
    log.info("CompetencySubThemeServiceImpl::deleteCompetencySubTheme");
    CustomResponse response = new CustomResponse();
    try {
      Optional<CompetencySubThemeEntity> optionalEntity = competencySubThemeRepository.findByIdAndIsActive(id, true);
      if (optionalEntity.isPresent()){
        CompetencySubThemeEntity competencySubThemeEntity = optionalEntity.get();
        competencySubThemeEntity.setIsActive(false);
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        competencySubThemeEntity.setUpdatedOn(currentTime);
        ((ObjectNode) competencySubThemeEntity.getData()).put(Constants.UPDATED_ON, String.valueOf(currentTime));
        ((ObjectNode) competencySubThemeEntity.getData()).put(Constants.STATUS, Constants.IN_ACTIVE);
        ((ObjectNode) competencySubThemeEntity.getData()).put(Constants.IS_ACTIVE, false);
        competencySubThemeRepository.save(competencySubThemeEntity);
        Map<String, Object> map = objectMapper.convertValue(competencySubThemeEntity.getData(), Map.class);
        esUtilService.updateDocument(Constants.COMP_SUB_THEME_INDEX_NAME, Constants.INDEX_TYPE,
            competencySubThemeEntity.getId(), map, cbServerProperties.getElasticCompJsonPath());
        cacheService.deleteCache(id);
        response.setResponseCode(HttpStatus.OK);
        response.setMessage(Constants.DELETED_SUCCESSFULLY);
        return response;
      }else {
        response.setMessage("CompetencySubThemeServiceImpl::deleteCompetencySubTheme:No data found for this id");
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

  @Override
  public ApiResponse createTerm(JsonNode request) {
    ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_COMPETENCY_SUB_THEME_CREATE);
    try {
      payloadValidation.validatePayload(Constants.TERM_CREATE_PAYLOAD_VALIDATION, request);
      String name = request.get(Constants.NAME).asText();
      String ref_Id = request.get(Constants.REF_ID).asText();
      String framework = request.get(Constants.FRAMEWORK).asText();
      String category = request.get(Constants.CATEGORY).asText();
      JsonNode additionalProperties = request.get(Constants.ADDITIONAL_PROPERTIES);
      String parentCategory = additionalProperties.path(Constants.PARENT_CATEGORY).asText();
      String termCode = additionalProperties.path(Constants.PREV_TERM_CODE).asText();
      Optional<CompetencySubThemeEntity> designationEntity = competencySubThemeRepository.findByIdAndIsActive(ref_Id, Boolean.TRUE);
      if (designationEntity.isPresent()) {
        CompetencySubThemeEntity designation = designationEntity.get();
        if (designation.getIsActive()) {
          ApiResponse readResponse = designationService.frameworkRead(framework,parentCategory,termCode,ref_Id);
          if (readResponse == null) {
            response.getParams().setErr("Failed to validate term exists or not.");
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            response.getParams().setStatus(Constants.FAILED);
          } else if (HttpStatus.NOT_FOUND.equals(readResponse.getResponseCode())) {
            Map<String, Object> reqBody = new HashMap<>();
            request.fields().forEachRemaining(entry -> reqBody.put(entry.getKey(), toJavaObject(entry.getValue())));
            Map<String, Object> termReq = new HashMap<String, Object>();
            termReq.put(Constants.TERM, reqBody);
            Map<String, Object> createReq = new HashMap<String, Object>();
            createReq.put(Constants.REQUEST, termReq);
            StringBuilder strUrl = new StringBuilder(cbServerProperties.getKnowledgeMS());
            strUrl.append(cbServerProperties.getOdcsTermCrete()).append("?framework=")
                    .append(framework).append("&category=")
                    .append(category);
            Map<String, Object> termResponse = (Map<String, Object>) outboundRequestHandlerServiceImpl.fetchResultUsingPost(strUrl.toString(),
                    createReq);
            if (termResponse != null
                    && Constants.OK.equalsIgnoreCase((String) termResponse.get(Constants.RESPONSE_CODE))) {
              Map<String, Object> resultMap = (Map<String, Object>) termResponse.get(Constants.RESULT);
              List<String> termIdentifier = (List<String>) resultMap.getOrDefault(Constants.NODE_ID, "");
              log.info("Created term successfully with name: " + ref_Id);
              log.info("termIdentifier : " + termIdentifier);
              Map<String, Object> reqBodyMap = new HashMap<>();
              reqBodyMap.put(Constants.ID, ref_Id);
              reqBodyMap.put(Constants.TITLE, name);
              reqBodyMap.put(Constants.REF_NODES, termIdentifier);
              CustomResponse desgResponse = updateCompSubTheme(objectMapper.valueToTree(reqBodyMap));
              if (desgResponse.getResponseCode() != HttpStatus.OK) {
                log.error("Failed to update term: " + response.getParams().getErr());
                response.getParams().setErr("Failed to update term.");
                response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
                response.getParams().setStatus(Constants.FAILED);
              } else {
                response.getResult().put(Constants.NODE_ID, termIdentifier);
              }
            } else {
              log.error("Failed to create the term with name: " + ref_Id);
              response.getParams().setErr("Failed to create the term");
              response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
              response.getParams().setStatus(Constants.FAILED);
            }
          } else if (HttpStatus.OK.equals(readResponse.getResponseCode())) {
            String errMsg = "Term already exists with name: " + ref_Id;
            log.error(errMsg);
            response.getParams().setErr(errMsg);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.getParams().setStatus(Constants.FAILED);
          } else {
            log.error("Failed to create the term with name: " + ref_Id);
            response.getParams().setErr("Failed to create.");
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            response.getParams().setStatus(Constants.FAILED);
          }
        } else {
          //if desg. is not active.
          log.error("Failed to create term exists with name: " + ref_Id);
          response.getParams().setErr("Failed to create term.");
          response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
          response.getParams().setStatus(Constants.FAILED);
        }
      } else {
        log.error("Failed to validate Term exists with name: " + ref_Id);
        response.getParams().setErr("Term Not Exist.");
        response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
        response.getParams().setStatus(Constants.FAILED);
      }
    } catch (CustomException e) {
      response.getParams().setErr(e.getMessage());
      response.setResponseCode(HttpStatus.BAD_REQUEST);
      response.getParams().setStatus(Constants.FAILED);
      log.error("Payload validation failed: " + e.getMessage());
    }
    return response;
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

  private String convertTimeStampToDate(long timeStamp) {
    Instant instant = Instant.ofEpochMilli(timeStamp);
    OffsetDateTime dateTime = instant.atOffset(ZoneOffset.UTC);
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy'T'HH:mm:ss.SSS'Z'");
    return dateTime.format(formatter);
  }

  public ApiResponse readTerm(String Id, String framework, String category) {
    ApiResponse response = new ApiResponse();
    try {
      StringBuilder strUrl = new StringBuilder(cbServerProperties.getKnowledgeMS());
      strUrl.append(cbServerProperties.getOdcsDesignationTermRead()).append("/").append(Id).append("?framework=")
              .append(framework).append("&category=")
              .append(category);

      Map<String, Object> map = new HashMap<String, Object>();
      Map<String, Object> desgResponse = (Map<String, Object>) outboundRequestHandlerServiceImpl.fetchResult(strUrl.toString());
      if (null != desgResponse) {
        if (Constants.OK.equalsIgnoreCase((String) desgResponse.get(Constants.RESPONSE_CODE))) {
          Map<String, Object> resultMap = (Map<String, Object>) desgResponse.get(Constants.RESULT);
          Map<String, Object> input = (Map<String, Object>) resultMap.get(Constants.TERM);
          processDesignation(input, map);
          response.getResult().put(Constants.DESIGNATION, map);
        } else {
          response.setResponseCode(HttpStatus.NOT_FOUND);
          response.getParams().setErr("Data not found with id : " + Id);
        }
      } else {
        response.setResponseCode(HttpStatus.NOT_FOUND);
        response.getParams().setErr("Failed to read the term details for Id : " + Id);
      }
    } catch (Exception e) {
      log.error("Failed to read term with Id: " + Id, e);
      response.getParams().setErr("Failed to read term: " + e.getMessage());
      response.getParams().setStatus(Constants.FAILED);
      response.setResponseCode(HttpStatus.NOT_FOUND);
    }
    return response;
  }

  private void processDesignation(Map<String, Object> designationInput, Map<String, Object> designationMap) {
    for (String field : cbServerProperties.getOdcsFields()) {
      if (designationInput.containsKey(field)) {
        designationMap.put(field, designationInput.get(field));
      }
    }
    if (designationInput.containsKey(Constants.CHILDREN)) {
      designationMap.put(Constants.CHILDREN, new ArrayList<Map<String, Object>>());
      processSubDesignation(designationInput, designationMap);
    }
  }

  private void processSubDesignation(Map<String, Object> designation, Map<String, Object> newDesignation) {
    List<Map<String, Object>> designationList = (List<Map<String, Object>>) designation.get(Constants.CHILDREN);
    Set<String> uniqueDesg = new HashSet<String>();
    for (Map<String, Object> desig : designationList) {
      if (uniqueDesg.contains((String) desig.get(Constants.IDENTIFIER))) {
        continue;
      } else {
        uniqueDesg.add((String) desig.get(Constants.IDENTIFIER));
      }
      Map<String, Object> newSubDesignation = new HashMap<String, Object>();
      for (String field : cbServerProperties.getOdcsFields()) {
        if (desig.containsKey(field)) {
          newSubDesignation.put(field, desig.get(field));
        }
      }
      ((List) newDesignation.get(Constants.CHILDREN)).add(newSubDesignation);
    }
  }

  private static Object toJavaObject(JsonNode jsonNode) {
    if (jsonNode.isObject()) {
      Map<String, Object> map = new HashMap<>();
      jsonNode.fields().forEachRemaining(entry -> map.put(entry.getKey(), toJavaObject(entry.getValue())));
      return map;
    } else {
      return jsonNode.asText();
    }
  }

}
