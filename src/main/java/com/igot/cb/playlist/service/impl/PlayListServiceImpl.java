package com.igot.cb.playlist.service.impl;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.igot.cb.playlist.dto.SearchDto;
import com.igot.cb.playlist.entity.PlayListEntity;
import com.igot.cb.playlist.repository.PlayListRepository;
import com.igot.cb.playlist.service.ContentService;
import com.igot.cb.playlist.service.PlayListSerive;
import com.igot.cb.playlist.util.ProjectUtil;
import com.igot.cb.playlist.util.RedisCacheMngr;
import com.igot.cb.pores.Service.OutboundRequestHandlerServiceImpl;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.util.ApiRespParam;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.pores.util.PayloadValidation;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class PlayListServiceImpl implements PlayListSerive {

  @Autowired
  private PlayListRepository playListRepository;

  @Value("${playlist.redis.ttl}")
  private long playListRedisTtl;

  @Autowired
  private RedisTemplate<String, PlayListEntity> playListEntityRedisTemplate;

  @Autowired
  ObjectMapper objectMapper;

  @Autowired
  private RedisCacheMngr redisCacheMngr;

  @Value("${redis.insights.index}")
  private int redisInsightIndex;

  @Autowired
  private ContentService contentService;

  @Autowired
  private CbServerProperties cbServerProperties;

  @Autowired
  private PayloadValidation payloadValidation;

  private Logger logger = LoggerFactory.getLogger(getClass().getName());

  @Autowired
  private EsUtilService esUtilService;

  @Autowired
  private RedisTemplate<String, SearchResult> redisTemplate;

  @Autowired
  private CacheService cacheService;

  @Autowired
  private OutboundRequestHandlerServiceImpl outboundRequestHandlerService;

  @Value("${search.result.redis.ttl}")
  private long searchResultRedisTtl;

  private String requiredJsonFilePath = "/EsFieldsmapping/playListEsMap.json";


  @Override
  public ApiResponse createPlayList(JsonNode playListDetails) {
    log.info("PlayListService::createPlayList:inside the method");
    payloadValidation.validatePayload(Constants.PLAY_LIST_VALIDATION_FILE_JSON, playListDetails);
    log.debug("PlayListService::createPlayList:validated the payload");
    ApiResponse response = new ApiResponse();
    try {
      List<PlayListEntity> optionalJsonNodeEntity = playListRepository.findByOrgIdAndRequestTypeAndIsActive(
          playListDetails.get(Constants.ORG_ID).asText(),
          playListDetails.get(Constants.RQST_CONTENT_TYPE).asText(), true);
      if (!optionalJsonNodeEntity.isEmpty()) {
        response.getParams().setStatus(Constants.FAILED);
        response.getParams().setErrMsg(
            "For the type " + playListDetails.get(Constants.RQST_CONTENT_TYPE).asText()
                + "this  orgId is present so update");
        response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
        response.getResult().put(Constants.ID, optionalJsonNodeEntity.get(0).getId());
        return response;
      }
      //create playlIst method
      log.info("PlayListService::createPlayList:creating playList");
      PlayListEntity jsonNodeEntity = new PlayListEntity();
      UUID playListId = Uuids.timeBased();
      jsonNodeEntity.setId(String.valueOf(playListId));
      jsonNodeEntity.setOrgId(playListDetails.get(Constants.ORG_ID).asText());
      Timestamp currentTime = new Timestamp(System.currentTimeMillis());
      ((ObjectNode) playListDetails).put(Constants.CREATED_ON, String.valueOf(currentTime));
      ((ObjectNode) playListDetails).put(Constants.UPDATED_ON, String.valueOf(currentTime));
      jsonNodeEntity.setData(playListDetails);
      jsonNodeEntity.setCreatedOn(currentTime);
      jsonNodeEntity.setUpdatedOn(currentTime);
      jsonNodeEntity.setRequestType(playListDetails.get(Constants.RQST_CONTENT_TYPE).asText());
      ((ObjectNode) playListDetails).put(Constants.KEY_PLAYLIST,
          jsonNodeEntity.getOrgId() + jsonNodeEntity.getRequestType());
      jsonNodeEntity.setIsActive(true);
      playListRepository.save(jsonNodeEntity);
      JsonNode childrenNode = playListDetails.get(Constants.CHILDREN);
      Map<String, Object> enrichContentMaps = new HashMap<>();
      enrichContentMaps = fetchContentDetails(childrenNode);
      ObjectNode enrichedContentJson = objectMapper.createObjectNode();
      enrichedContentJson.put(Constants.CHILDREN, objectMapper.valueToTree(enrichContentMaps));
      enrichedContentJson.put(Constants.ID, jsonNodeEntity.getOrgId());
      persistInRedis(enrichedContentJson, jsonNodeEntity,
          jsonNodeEntity.getOrgId() + jsonNodeEntity.getRequestType());
      JsonNode playListJson = jsonNodeEntity.getData();
      if (playListJson.has(Constants.TITLE) && !playListJson.get(Constants.TITLE).asText()
          .isEmpty()) {
        List<String> searchTags = new ArrayList<>();
        searchTags.add(playListJson.get(Constants.TITLE).textValue().toLowerCase());
        ArrayNode searchTagsArray = objectMapper.valueToTree(searchTags);
        ((ObjectNode) playListJson).putArray(Constants.SEARCHTAGS).add(searchTagsArray);
      }
      ((ObjectNode) playListJson).put(Constants.ID, String.valueOf(playListId));
      Map<String, Object> map = objectMapper.convertValue(playListJson, Map.class);
      //put it in es jsonNodeEntiy along with enrichedContentMap
      esUtilService.addDocument(Constants.PLAYLIST_INDEX_NAME, Constants.INDEX_TYPE,
          String.valueOf(playListId), map, requiredJsonFilePath);
      response = ProjectUtil.createDefaultResponse(Constants.API_PLAYLIST_CREATE);
      response.put(Constants.RESPONSE, Constants.SUCCESS);
      response.setResponseCode(HttpStatus.OK);
      response.getResult().put(Constants.STATUS, Constants.CREATED);
      response.getResult().put(Constants.ID, playListId);
      log.info("PlayListService::createPlayList:created playList");
      return response;
    } catch (Exception errMsg) {
      logger.error("Failed to Create PlayList: " + playListDetails.get(Constants.ORG_ID), errMsg);
      response.getParams().setStatus(Constants.FAILED);
      response.getParams().setErrMsg("Not found");
      response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
      return response;
    }
  }

  private void persistInRedis(ObjectNode enrichedContentJson, PlayListEntity jsonNodeEntity,
      String key) {
    log.info("PlayListService::persistInRedis");
    try {
      Map<String, Object> map = objectMapper.convertValue(enrichedContentJson,
          new TypeReference<Map<String, Object>>() {
          });
      Map<String, String> fieldValues = new HashMap<>();
      for (Entry<String, Object> entry : map.entrySet()) {
        if (entry.getValue() instanceof List) {
          List<String> list = (List<String>) entry.getValue();
          String valueAsString = String.join(",", list);
          fieldValues.put(entry.getKey(), valueAsString);
        } else {
          String valueAsString = null;
          valueAsString = objectMapper.writeValueAsString(entry.getValue());
          fieldValues.put(entry.getKey(), valueAsString);
        }
      }
      Map<String, String> hsetValues = new HashMap<>();
      hsetValues.put(jsonNodeEntity.getOrgId(), objectMapper.writeValueAsString(fieldValues));
      redisCacheMngr.hset(
          key,
          redisInsightIndex, hsetValues);
      log.info("persisted in redis");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

  }

  private Map<String, Object> fetchContentDetails(JsonNode childrenNode) {
    log.info("PlayListService::fetchContentDetails");
    Map<String, Object> compositeSearchRes = new HashMap<>();
    if (childrenNode != null && childrenNode.isArray()) {
      Map<String, Object> reqBody = new HashMap<>();
      Map<String, Object> req = new HashMap<>();
      req.put(Constants.FACETS, Arrays.asList(cbServerProperties.getCourseCategoryFacet()));
      Map<String, Object> filters = new HashMap<>();
      filters.put(Constants.IDENTIFIER,
          objectMapper.convertValue(childrenNode, new TypeReference<List<String>>() {
          }));
      filters.put(Constants.STATUS, Arrays.asList(Constants.LIVE));
      req.put(Constants.FILTERS, filters);
      reqBody.put(Constants.REQUEST, req);

      compositeSearchRes = outboundRequestHandlerService.fetchResultUsingPost(
          cbServerProperties.getSbSearchServiceHost() + cbServerProperties.getSbCompositeV4Search(),
          reqBody,
          null);
      return compositeSearchRes;
    }
    log.info("PlayListService::fetchContentDetails:fetchedContent");
    return compositeSearchRes;
  }

  @Override
  public ApiResponse searchPlayListForOrg(SearchDto searchDto) {
    log.info("PlayListService::searchPlayListForOrg: reading the playList");
    ApiResponse response = new ApiResponse();
    try {
      validatePayload(searchDto);
      log.info("PlayListService::validatePayload:validate");
    } catch (Exception e) {
      response.getParams().setStatus(Constants.FAILED);
      response.setResponseCode(HttpStatus.BAD_REQUEST);
      return response;
    }
    HashMap<String, Object> request;
    request = searchDto.getRequest();
    // Get the "filters" HashMap
    HashMap<String, Object> filters = (HashMap<String, Object>) request.get(Constants.FILTERS);
    String orgId = (String) filters.get(Constants.ORGANISATION);
    List<String> contextTypes = (List<String>) filters.get(Constants.REQUEST_TYPE);
    String playListStringFromRedis = "";
    try {
      for (String contextType : contextTypes) {
        playListStringFromRedis =
            redisCacheMngr.hget(orgId + contextType,
                redisInsightIndex, orgId).toString();
        log.info("Cached PlayList for orgId: " + orgId);

      }
      if (playListStringFromRedis == null || "[null]".equals(playListStringFromRedis)
          || playListStringFromRedis.isEmpty()) {
        // Fetch from postgres and add fetched playlist into redis
        Optional<PlayListEntity> optionalJsonNodeEntity = Optional.ofNullable(
            playListRepository.findByOrgIdAndIsActive(orgId, true));
        if (optionalJsonNodeEntity.isPresent()) {
          PlayListEntity playListEntity = optionalJsonNodeEntity.orElse(null);
          log.info("PlayListService::searchPlayListForOrg::fetched playList from postgres");
          playListEntity.getData().get(Constants.CHILDREN);
          Map<String, Object> enrichContentMaps = new HashMap<>();
          enrichContentMaps = fetchContentDetails(playListEntity.getData().get(Constants.CHILDREN));
          ObjectNode enrichedContentJson = objectMapper.createObjectNode();
          enrichedContentJson.put(Constants.CHILDREN, objectMapper.valueToTree(enrichContentMaps));
          enrichedContentJson.put(Constants.ID, playListEntity.getOrgId());
          persistInRedis(enrichedContentJson, playListEntity,
              playListEntity.getOrgId() + playListEntity.getRequestType());
          for (String contextType : contextTypes) {
            playListStringFromRedis =
                redisCacheMngr.hget(orgId + contextType, redisInsightIndex, orgId).toString();
            log.info("Cached PlayList: " + playListStringFromRedis);

          }
        } else {
          logger.error("Failed to Fetch PalyList: ");
          response.getParams().setStatus(Constants.FAILED);
          response.getParams().setErrMsg(Constants.ORG_COURSE_NOT_FOUND);
          response.setResponseCode(HttpStatus.BAD_REQUEST);
          return response;
        }

      }

      JsonNode rootNode = null;
      rootNode = objectMapper.readTree(playListStringFromRedis);
      JsonNode firstElement = rootNode.get(0);
      String childrenJsonString = firstElement.get(Constants.CHILDREN).asText();
      JsonNode childrenNode = objectMapper.readTree(childrenJsonString);
      Map<String, JsonNode> childMap = new HashMap<>();
      List<JsonNode> dataList = new ArrayList<>();
      childrenNode.fields().forEachRemaining(entry -> {
        String childId = entry.getKey();
        JsonNode childData = entry.getValue();
        dataList.add(childData);
      });

      response.setResponseCode(HttpStatus.OK);
      if (!dataList.isEmpty()) {
        response.getResult().put(Constants.CONTENT, dataList);
      }
      if (childrenNode.has(Constants.RESULT) && childrenNode.get(Constants.RESULT)
          .has(Constants.CONTENT) && !childrenNode.get(Constants.RESULT)
          .get(Constants.CONTENT).isNull()) {
        response.getResult()
            .put(Constants.CONTENT, childrenNode.get(Constants.RESULT).get(Constants.CONTENT));
        response.getResult()
            .put(Constants.COUNT, childrenNode.get(Constants.RESULT).get(Constants.COUNT));
        response.getResult()
            .put(Constants.FACETS, childrenNode.get(Constants.RESULT).get(Constants.FACETS));
      }
      return response;
    } catch (Exception e) {
      logger.error("Failed to Create PalyList: ", e);
      response.getParams().setStatus(Constants.FAILED);
      response.getParams().setErrMsg(e.getMessage());
      response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
      return response;
    }
  }


  @Override
  public ApiResponse updatePlayList(JsonNode playListDetails) {

    ApiResponse response = new ApiResponse();
    try {
      log.info("PlayListService::updatePlayList");
      payloadValidation.validatePayload(Constants.PLAY_LIST_VALIDATION_FILE_JSON, playListDetails);
      log.debug("PlayListService::updatePlayList:validated the payload");
      List<PlayListEntity> optionalJsonNodeEntity = playListRepository.findByOrgIdAndRequestTypeAndIsActive(
          playListDetails.get(Constants.ORG_ID).asText(),
          playListDetails.get(Constants.RQST_CONTENT_TYPE).asText(), true
      );
      if (!optionalJsonNodeEntity.isEmpty()) {
        PlayListEntity playListEntity = optionalJsonNodeEntity.get(0);
        JsonNode fetchedData = playListEntity.getData();
        log.info("PlayListService::updatePlayList::fetched playList from postgres");
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        playListEntity.setUpdatedOn(currentTime);
        ((ObjectNode) fetchedData).put(Constants.UPDATED_ON, String.valueOf(currentTime));
        ((ObjectNode) fetchedData).put(Constants.PLAYLIST_KEY_REDIS, playListEntity.getOrgId() + playListEntity.getRequestType());
        Iterator<Map.Entry<String, JsonNode>> fields = playListDetails.fields();
        while (fields.hasNext()) {
          Map.Entry<String, JsonNode> field = fields.next();
          String fieldName = field.getKey();
          // Check if the field is present in the update JsonNode
          if (fetchedData.has(fieldName)) {
            // Update the main JsonNode with the value from the update JsonNode
            ((ObjectNode) fetchedData).set(fieldName, playListDetails.get(fieldName));
          } else {
            ((ObjectNode) fetchedData).put(fieldName, playListDetails.get(fieldName));
          }
        }
        optionalJsonNodeEntity.get(0).setUpdatedOn(currentTime);
        ((ObjectNode) playListDetails).put(Constants.KEY_PLAYLIST,
            playListEntity.getOrgId() + playListEntity.getRequestType());
        playListEntity.setData(fetchedData);
        PlayListEntity saveJsonEntity = playListRepository.save(playListEntity);
        Map<String, Object> enrichContentMaps = new HashMap<>();
        enrichContentMaps = fetchContentDetails(playListDetails.get(Constants.CHILDREN));
        ObjectNode enrichedContentJson = objectMapper.createObjectNode();
        enrichedContentJson.put(Constants.CHILDREN, objectMapper.valueToTree(enrichContentMaps));
        enrichedContentJson.put(Constants.ID, playListEntity.getOrgId());
        persistInRedis(enrichedContentJson, playListEntity,
            playListEntity.getOrgId() + playListEntity.getRequestType());
        JsonNode playListJson = playListEntity.getData();
        if (playListJson.has(Constants.TITLE) && !playListJson.get(Constants.TITLE).asText()
            .isEmpty()) {
          List<String> searchTags = new ArrayList<>();
          searchTags.add(playListJson.get(Constants.TITLE).textValue().toLowerCase());
          ArrayNode searchTagsArray = objectMapper.valueToTree(searchTags);
          ((ObjectNode) playListJson).putArray(Constants.SEARCHTAGS).add(searchTagsArray);
        }
        ((ObjectNode) playListJson).put(Constants.ID, playListEntity.getId());
        Map<String, Object> map = objectMapper.convertValue(playListJson, Map.class);
        //put it in es jsonNodeEntiy along with enrichedContentMap
        esUtilService.addDocument(Constants.PLAYLIST_INDEX_NAME, Constants.INDEX_TYPE,
            String.valueOf(playListEntity.getId()), map, requiredJsonFilePath);
        response = ProjectUtil.createDefaultResponse(Constants.API_PLAYLIST_UPDATED);
        response.put(Constants.RESPONSE, Constants.SUCCESS);
        response.setResponseCode(HttpStatus.OK);
        response.getResult().put(Constants.STATUS, Constants.UPDATED);
        response.getResult().put(Constants.ID, optionalJsonNodeEntity.get(0).getId());
        return response;
      }
    } catch (Exception e) {
      logger.error("Failed to Create PalyList: " + playListDetails.get(Constants.ORG_ID), e);
      response.getParams().setStatus(Constants.FAILED);
      response.getParams().setErrMsg(Constants.NOT_FOUND);
      response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
      return response;
    }
    log.info("PlayListService::updatePlayList:updated");
    response.getParams().setStatus(Constants.FAILED);
    response.getParams().setErrMsg(Constants.NOT_FOUND);
    response.setResponseCode(HttpStatus.NOT_FOUND);
    return response;
  }

  @Override
  public ApiResponse delete(String id) {
    ApiResponse response = new ApiResponse();
    try {
      log.info("PlayListService::delete");
      Optional<PlayListEntity> optionalJsonNodeEntity =
          Optional.ofNullable(playListRepository.findByIdAndIsActive(id, true));
      PlayListEntity playListEntity = optionalJsonNodeEntity.orElse(null);
      if (optionalJsonNodeEntity.isPresent()) {
        log.info("PlayListService::delete::deleting");
        playListEntity.setIsActive(false);
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        playListEntity.setUpdatedOn(currentTime);
        playListRepository.save(playListEntity);
        if (!playListEntity.getData().isNull() && playListEntity.getData()
            .has(Constants.PLAYLIST_KEY_REDIS) && !playListEntity.getData()
            .get(Constants.PLAYLIST_KEY_REDIS).isNull()) {
          redisCacheMngr.hdel(playListEntity.getData().get(Constants.PLAYLIST_KEY_REDIS).asText(),
              playListEntity.getOrgId(),
              redisInsightIndex);
        }
        esUtilService.deleteDocument(id, Constants.PLAYLIST_INDEX_NAME);
        log.info("PlayListService::delete::deleted");
        response = ProjectUtil.createDefaultResponse(Constants.API_PLAYLIST_CREATE);
        response.put(Constants.RESPONSE, Constants.SUCCESS);
        response.setResponseCode(HttpStatus.OK);
        response.getResult().put(Constants.STATUS, Constants.DELETED_SUCCESSFULLY);
        response.getResult().put(Constants.ID, optionalJsonNodeEntity.get().getId());
        return response;
      } else {
        response.getParams().setStatus(Constants.FAILED);
        response.getParams().setErrMsg(Constants.NOT_FOUND);
        response.setResponseCode(HttpStatus.NOT_FOUND);
        return response;
      }
    } catch (Exception e) {
      logger.error("Failed to delete PalyList: " + id, e);
      response.getParams().setStatus(Constants.FAILED);
      response.getParams().setErrMsg(e.getMessage());
      response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
      return response;
    }
  }

  @Override
  public ApiResponse searchPlayList(SearchCriteria searchCriteria) {
    log.info("PlayListService::searchPlayList:inside method");
    ApiResponse response = new ApiResponse();
    SearchResult searchResult = redisTemplate.opsForValue()
        .get(generateRedisJwtTokenKey(searchCriteria));
    if (searchResult != null) {
      log.info("PlayListService::searchPlayList: search result fetched from redis");
      response.getResult().putAll(objectMapper.convertValue(searchResult, Map.class));
      createSuccessResponse(response);
      return response;
    }
    String searchString = searchCriteria.getSearchString();
    if (searchString != null && searchString.length() > 2) {
      searchCriteria.setSearchString(searchString.toLowerCase());
    }
    try {
      searchResult =
          esUtilService.searchDocuments(Constants.PLAYLIST_INDEX_NAME, searchCriteria);
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

  @Override
  public ApiResponse readPlaylist(String id, String orgId) {
    log.info("PlayListService::readPlaylist:inside method");
    ApiResponse response = new ApiResponse();
    String playListStringFromRedis = "";
    try {
      playListStringFromRedis =
          redisCacheMngr.hget(id,
              redisInsightIndex, orgId).toString();
      log.info("Cached PlayList for orgId: " + orgId);
      if (playListStringFromRedis == null || "[null]".equals(playListStringFromRedis)
          || playListStringFromRedis.isEmpty()) {
        String requestType = "";
        if (id.startsWith(orgId)) {
          requestType = extractRequestType(id.substring(orgId.length()));
        }
        // Fetch from postgres and add fetched playlist into redis
        List<PlayListEntity> optionalJsonNodeEntity =
            playListRepository.findByOrgIdAndRequestTypeAndIsActive(orgId, requestType, true);
        if (!optionalJsonNodeEntity.isEmpty()) {
          PlayListEntity playListEntity = optionalJsonNodeEntity.get(0);
          log.info("PlayListService::readPlayList::fetched playList from postgres");
          playListEntity.getData().get(Constants.CHILDREN);
          Map<String, Object> enrichContentMaps = new HashMap<>();
          enrichContentMaps = fetchContentDetails(playListEntity.getData().get(Constants.CHILDREN));
          ObjectNode enrichedContentJson = objectMapper.createObjectNode();
          enrichedContentJson.put(Constants.CHILDREN, objectMapper.valueToTree(enrichContentMaps));
          enrichedContentJson.put(Constants.ID, playListEntity.getOrgId());
          persistInRedis(enrichedContentJson, playListEntity,
              id);

          playListStringFromRedis =
              redisCacheMngr.hget(id, redisInsightIndex, orgId).toString();
          log.info("Cached PlayList: " + playListStringFromRedis);

        } else {
          logger.error("Failed to Fetch PlayList: ");
          response.getParams().setStatus(Constants.FAILED);
          response.getParams().setErrMsg(Constants.ORG_COURSE_NOT_FOUND);
          response.setResponseCode(HttpStatus.BAD_REQUEST);
          return response;
        }

      }

      JsonNode rootNode = null;
      rootNode = objectMapper.readTree(playListStringFromRedis);
      JsonNode firstElement = rootNode.get(0);
      String childrenJsonString = firstElement.get(Constants.CHILDREN).asText();
      JsonNode childrenNode = objectMapper.readTree(childrenJsonString);
      Map<String, JsonNode> childMap = new HashMap<>();
      List<JsonNode> dataList = new ArrayList<>();
      childrenNode.fields().forEachRemaining(entry -> {
        String childId = entry.getKey();
        JsonNode childData = entry.getValue();
        dataList.add(childData);
      });
      response.setResponseCode(HttpStatus.OK);
      if (!dataList.isEmpty()) {
        response.getResult().put(Constants.CONTENT, dataList);
      }
      if (childrenNode.has(Constants.RESULT) && childrenNode.get(Constants.RESULT)
          .has(Constants.CONTENT) && !childrenNode.get(Constants.RESULT)
          .get(Constants.CONTENT).isNull()) {
        response.getResult()
            .put(Constants.CONTENT, childrenNode.get(Constants.RESULT).get(Constants.CONTENT));
        response.getResult()
            .put(Constants.COUNT, childrenNode.get(Constants.RESULT).get(Constants.COUNT));
        response.getResult()
            .put(Constants.FACETS, childrenNode.get(Constants.RESULT).get(Constants.FACETS));
      }
      return response;
    } catch (Exception e) {
      logger.error("Failed to Create PalyList: ", e);
      response.getParams().setStatus(Constants.FAILED);
      response.getParams().setErrMsg(e.getMessage());
      response.setResponseCode(HttpStatus.NOT_FOUND);
      return response;
    }
  }

  private String extractRequestType(String id) {
    // UUID regex (version-agnostic)
    String uuidRegex = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    // Find where the UUID starts and get the prefix
    Pattern pattern = Pattern.compile(uuidRegex);
    Matcher matcher = pattern.matcher(id);

    if (matcher.find()) {
      return id.substring(0, matcher.start()).trim(); // Request type is before UUID
    }

    // If no UUID is found, assume whole string is requestType
    return id.trim();
  }

  @Override
  public ApiResponse updateV2PlayList(JsonNode playListDetails) {
    log.info("PlayListService::updateV2PlayList:inside method");
    ApiResponse response = new ApiResponse();
    try {
      if (playListDetails.has(Constants.ID) && !playListDetails.get(Constants.ID).asText()
          .isEmpty()) {
        String id = playListDetails.get(Constants.ID).asText();
        Optional<PlayListEntity> optPlayList = Optional.ofNullable(
            playListRepository.findByIdAndIsActive(
                playListDetails.get(Constants.ID).asText(), true));
        PlayListEntity playListEntityUpdated = null;
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        if (optPlayList.isPresent()) {
          playListEntityUpdated = optPlayList.get();
          JsonNode dataNode = optPlayList.get().getData();
          ((ObjectNode) dataNode).put(Constants.UPDATED_ON, String.valueOf(currentTime));
          ((ObjectNode) dataNode).put(Constants.PLAYLIST_KEY_REDIS, playListEntityUpdated.getOrgId() + playListEntityUpdated.getRequestType()
              + playListEntityUpdated.getId());
          Iterator<Map.Entry<String, JsonNode>> fields = playListDetails.fields();
          while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldName = field.getKey();
            // Check if the field is present in the update JsonNode
            if (dataNode.has(fieldName)) {
              // Update the main JsonNode with the value from the update JsonNode
              ((ObjectNode) dataNode).set(fieldName, playListDetails.get(fieldName));
            } else {
              ((ObjectNode) dataNode).put(fieldName, playListDetails.get(fieldName));
            }
          }
          optPlayList.get().setUpdatedOn(currentTime);
          PlayListEntity saveJsonEntity = playListRepository.save(playListEntityUpdated);
          if (playListDetails.has(Constants.CHILDREN) && !playListDetails.get(Constants.CHILDREN)
              .isEmpty()) {
            JsonNode childrenNode = playListDetails.get(Constants.CHILDREN);
            Map<String, Object> enrichContentMaps = new HashMap<>();
            enrichContentMaps = fetchContentDetails(childrenNode);
            ObjectNode enrichedContentJson = objectMapper.createObjectNode();
            enrichedContentJson.put(Constants.CHILDREN,
                objectMapper.valueToTree(enrichContentMaps));
            enrichedContentJson.put(Constants.ID, playListEntityUpdated.getOrgId());
            persistInRedis(enrichedContentJson, optPlayList.get(),
                playListEntityUpdated.getOrgId() + playListEntityUpdated.getRequestType()
                    + playListEntityUpdated.getId());
          }
          JsonNode jsonNode = playListEntityUpdated.getData();
          if (jsonNode.has(Constants.TITLE) && !jsonNode.get(Constants.TITLE).asText().isEmpty()) {
            List<String> searchTags = new ArrayList<>();
            searchTags.add(jsonNode.get(Constants.TITLE).textValue().toLowerCase());
            ArrayNode searchTagsArray = objectMapper.valueToTree(searchTags);
            ((ObjectNode) jsonNode).putArray(Constants.SEARCHTAGS).add(searchTagsArray);
          }
          ((ObjectNode) jsonNode).put(Constants.ID, playListEntityUpdated.getId());
          Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
          esUtilService.updateDocument(Constants.PLAYLIST_INDEX_NAME, Constants.INDEX_TYPE, id, map,
              requiredJsonFilePath);
          log.info("playList updated");
          response.setResponseCode(HttpStatus.OK);
          response.put(Constants.RESPONSE, Constants.SUCCESS);
          response.setResponseCode(HttpStatus.OK);
          response.getResult().put(Constants.STATUS, Constants.SUCCESSFULLY_UPDATED);
          response.getResult().put(Constants.ID, id);
          return response;
        } else {
          response.getParams().setStatus(Constants.ID_NOT_FOUND);
          response.setResponseCode(HttpStatus.NOT_FOUND);
          return response;
        }
      } else {
        response.getParams().setStatus(Constants.ID_NOT_FOUND);
        response.setResponseCode(HttpStatus.NOT_FOUND);
        return response;
      }
    } catch (Exception e) {
      logger.error("Failed to Update PalyList: ", e);
      response.getParams().setStatus(Constants.FAILED);
      response.getParams().setErrMsg(e.getMessage());
      response.setResponseCode(HttpStatus.NOT_FOUND);
      return response;
    }
  }

  @Override
  public ApiResponse createV2PlayList(JsonNode playListDetails) {
    log.info("PlayListService::createV2PlayList:inside the method");
    payloadValidation.validatePayload(Constants.PLAY_LIST_VALIDATION_FILE_JSON, playListDetails);
    log.debug("PlayListService::createV2PlayList:validated the payload");
    ApiResponse response = new ApiResponse();
    try {
      //create playlIst method
      log.info("PlayListService::createPlayList:creating playList");
      PlayListEntity jsonNodeEntity = new PlayListEntity();
      UUID playListId = Uuids.timeBased();
      String id = String.valueOf(playListId);
      jsonNodeEntity.setId(String.valueOf(playListId));
      jsonNodeEntity.setOrgId(playListDetails.get(Constants.ORG_ID).asText());
      Timestamp currentTime = new Timestamp(System.currentTimeMillis());
      ((ObjectNode) playListDetails).put(Constants.CREATED_ON, String.valueOf(currentTime));
      ((ObjectNode) playListDetails).put(Constants.UPDATED_ON, String.valueOf(currentTime));
      jsonNodeEntity.setData(playListDetails);
      jsonNodeEntity.setCreatedOn(currentTime);
      jsonNodeEntity.setUpdatedOn(currentTime);
      jsonNodeEntity.setRequestType(playListDetails.get(Constants.RQST_CONTENT_TYPE).asText());
      ((ObjectNode) playListDetails).put(Constants.KEY_PLAYLIST,
          jsonNodeEntity.getOrgId() + jsonNodeEntity.getRequestType() + id);
      jsonNodeEntity.setIsActive(true);
      playListRepository.save(jsonNodeEntity);
      if (playListDetails.has(Constants.CHILDREN) && !playListDetails.get(Constants.CHILDREN)
          .isEmpty()) {
        JsonNode childrenNode = playListDetails.get(Constants.CHILDREN);
        Map<String, Object> enrichContentMaps = new HashMap<>();
        enrichContentMaps = fetchContentDetails(childrenNode);
        ObjectNode enrichedContentJson = objectMapper.createObjectNode();
        enrichedContentJson.put(Constants.CHILDREN, objectMapper.valueToTree(enrichContentMaps));
        enrichedContentJson.put(Constants.ID, jsonNodeEntity.getOrgId());
        persistInRedis(enrichedContentJson, jsonNodeEntity,
            jsonNodeEntity.getOrgId() + jsonNodeEntity.getRequestType() + playListId);

      }
      JsonNode playListJson = jsonNodeEntity.getData();
      if (playListJson.has(Constants.TITLE) && !playListJson.get(Constants.TITLE).asText()
          .isEmpty()) {
        List<String> searchTags = new ArrayList<>();
        searchTags.add(playListJson.get(Constants.TITLE).textValue().toLowerCase());
        ArrayNode searchTagsArray = objectMapper.valueToTree(searchTags);
        ((ObjectNode) playListDetails).putArray(Constants.SEARCHTAGS).add(searchTagsArray);
      }
      ((ObjectNode) playListJson).put(Constants.ID, String.valueOf(playListId));
      Map<String, Object> map = objectMapper.convertValue(playListJson, Map.class);
      //put it in es jsonNodeEntiy along with enrichedContentMap
      esUtilService.addDocument(Constants.PLAYLIST_INDEX_NAME, Constants.INDEX_TYPE,
          String.valueOf(playListId), map, requiredJsonFilePath);
      response = ProjectUtil.createDefaultResponse(Constants.API_PLAYLIST_CREATE);
      response.put(Constants.RESPONSE, Constants.SUCCESS);
      response.setResponseCode(HttpStatus.OK);
      response.getResult().put(Constants.STATUS, Constants.CREATED);
      response.getResult().put(Constants.ID, playListId);
      log.info("PlayListService::createPlayList:created playList");
      return response;
    } catch (Exception errMsg) {
      logger.error("Failed to Create PlayList: " + playListDetails.get(Constants.ORG_ID), errMsg);
      response.getParams().setStatus(Constants.FAILED);
      response.getParams().setErrMsg("Not found");
      response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
      return response;
    }
  }

  @Override
  public ApiResponse readV2Playlist(String id, String playListId, String orgId) {
    log.info("PlayListService::readV2Playlist:inside method");
    ApiResponse response = new ApiResponse();
    String playListStringFromRedis = "";
    try {
      playListStringFromRedis =
          redisCacheMngr.hget(id,
              redisInsightIndex, orgId).toString();
      log.info("Cached PlayList for id: " + playListId);
      if (playListStringFromRedis == null || "[null]".equals(playListStringFromRedis)
          || playListStringFromRedis.isEmpty()) {
        // Fetch from postgres and add fetched playlist into redis
        Optional<PlayListEntity> optionalJsonNodeEntity =
            Optional.ofNullable(playListRepository.findByIdAndIsActive(playListId, true));
        if (optionalJsonNodeEntity.isPresent()) {
          PlayListEntity playListEntity = optionalJsonNodeEntity.get();
          log.info("PlayListService::readPlayList::fetched playList from postgres");
          playListEntity.getData().get(Constants.CHILDREN);
          Map<String, Object> enrichContentMaps = new HashMap<>();
          enrichContentMaps = fetchContentDetails(playListEntity.getData().get(Constants.CHILDREN));
          ObjectNode enrichedContentJson = objectMapper.createObjectNode();
          enrichedContentJson.put(Constants.CHILDREN, objectMapper.valueToTree(enrichContentMaps));
          enrichedContentJson.put(Constants.ID, playListEntity.getOrgId());
          persistInRedis(enrichedContentJson, playListEntity, id);

          playListStringFromRedis =
              redisCacheMngr.hget(id, redisInsightIndex, orgId).toString();
          log.info("Cached PlayList: " + playListStringFromRedis);

        } else {
          logger.error("Failed to Fetch PlayList: ");
          response.getParams().setStatus(Constants.FAILED);
          response.getParams().setErrMsg(Constants.ORG_COURSE_NOT_FOUND);
          response.setResponseCode(HttpStatus.BAD_REQUEST);
          return response;
        }

      }

      JsonNode rootNode = null;
      rootNode = objectMapper.readTree(playListStringFromRedis);
      JsonNode firstElement = rootNode.get(0);
      String childrenJsonString = firstElement.get(Constants.CHILDREN).asText();
      JsonNode childrenNode = objectMapper.readTree(childrenJsonString);
      Map<String, JsonNode> childMap = new HashMap<>();
      List<JsonNode> dataList = new ArrayList<>();
      childrenNode.fields().forEachRemaining(entry -> {
        String childId = entry.getKey();
        JsonNode childData = entry.getValue();
        dataList.add(childData);
      });

      response.setResponseCode(HttpStatus.OK);
      if (!dataList.isEmpty()) {
        response.getResult().put(Constants.CONTENT, dataList);
      }
      if (childrenNode.has(Constants.RESULT) && childrenNode.get(Constants.RESULT)
          .has(Constants.CONTENT) && !childrenNode.get(Constants.RESULT)
          .get(Constants.CONTENT).isNull()) {
        response.getResult()
            .put(Constants.CONTENT, childrenNode.get(Constants.RESULT).get(Constants.CONTENT));
        response.getResult()
            .put(Constants.COUNT, childrenNode.get(Constants.RESULT).get(Constants.COUNT));
        response.getResult()
            .put(Constants.FACETS, childrenNode.get(Constants.RESULT).get(Constants.FACETS));
      }
      return response;
    } catch (Exception e) {
      logger.error("Failed to Create PalyList: ", e);
      response.getParams().setStatus(Constants.FAILED);
      response.getParams().setErrMsg(e.getMessage());
      response.setResponseCode(HttpStatus.NOT_FOUND);
      return response;
    }
  }

  @Override
  public ApiResponse searchPlayListWithoutCaching(SearchCriteria searchCriteria) {
    log.info("PlayListService::searchPlayListWithoutCaching:inside method");
    ApiResponse response = new ApiResponse();
    SearchResult searchResult;
    String searchString = searchCriteria.getSearchString();
    if (searchString != null && searchString.length() < 2) {
      createErrorResponse(response, "Minimum 3 characters are required to search",
          HttpStatus.BAD_REQUEST, Constants.FAILED_CONST);
      return response;
    }
    if (searchString != null && searchString.length() > 2) {
      searchCriteria.setSearchString(searchString.toLowerCase());
    }
    try {
      searchResult =
          esUtilService.searchDocuments(Constants.PLAYLIST_INDEX_NAME, searchCriteria);
      response.getResult().putAll(objectMapper.convertValue(searchResult, Map.class));
      createSuccessResponse(response);
      return response;
    } catch (Exception e) {
      createErrorResponse(response, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR,
          Constants.FAILED_CONST);
      return response;
    }
  }

  private void validatePayload(SearchDto searchDto) {
    log.info("PlayListService::validatePayload:inside method");
    if (searchDto == null || searchDto.getRequest() == null) {
      throw new IllegalArgumentException("Request structure is missing or invalid");
    }
    Map<String, Object> request = searchDto.getRequest();
    if (!request.containsKey(Constants.FILTERS) || !(request.get(
        Constants.FILTERS) instanceof Map)) {
      throw new IllegalArgumentException("filters field is missing or invalid");
    }
    Map<String, Object> filters = (HashMap<String, Object>) request.get(Constants.FILTERS);
    if (!filters.containsKey(Constants.REQUEST_TYPE) || !(filters.get(
        Constants.REQUEST_TYPE) instanceof List) || ((List<?>) filters.get(
        Constants.REQUEST_TYPE)).isEmpty()) {
      throw new IllegalArgumentException("contextType field is missing or empty");
    }
    if (!filters.containsKey(Constants.ORGANISATION) || !(filters.get(
        Constants.ORGANISATION) instanceof String) || ((String) filters.get(
        Constants.ORGANISATION)).isEmpty()) {
      throw new IllegalArgumentException("organisation field is missing or empty");
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

  public void createSuccessResponse(ApiResponse response) {
    response.setParams(new ApiRespParam());
    response.getParams().setStatus(Constants.SUCCESS);
    response.setResponseCode(HttpStatus.OK);
  }

  public void createErrorResponse(
      ApiResponse response, String errorMessage, HttpStatus httpStatus, String status) {
    response.setParams(new ApiRespParam());
    response.getParams().setStatus(status);
    response.setResponseCode(httpStatus);
    response.getParams().setErrMsg(errorMessage);
  }

  private String convertTimeStampToDate(long timeStamp) {
    Instant instant = Instant.ofEpochMilli(timeStamp);
    OffsetDateTime dateTime = instant.atOffset(ZoneOffset.UTC);
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy'T'HH:mm:ss.SSS'Z'");
    return dateTime.format(formatter);
  }
}
