package com.igot.cb.interest.service.impl;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.datastax.driver.core.utils.UUIDs;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.demand.entity.DemandEntity;
import com.igot.cb.demand.repository.DemandRepository;
import com.igot.cb.demand.service.DemandServiceImpl;
import com.igot.cb.interest.entity.Interests;
import com.igot.cb.interest.repository.InterestRepository;
import com.igot.cb.interest.service.InterestService;
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
import com.igot.cb.producer.Producer;
import com.igot.cb.transactional.cassandrautils.CassandraOperation;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class InterestServiceImpl implements InterestService {

  @Autowired
  private PayloadValidation payloadValidation;

  @Autowired
  private InterestRepository interestRepository;

  @Autowired
  private DemandRepository demandRepository;

  @Autowired
  private EsUtilService esUtilService;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private CacheService cacheService;

  @Autowired
  private RedisTemplate<String, SearchResult> redisTemplate;
  @Autowired
  private CbServerProperties cbServerProperties;

  private Logger logger = LoggerFactory.getLogger(InterestServiceImpl.class);

  @Value("${search.result.redis.ttl}")
  private long searchResultRedisTtl;

  @Autowired
  private CassandraOperation cassandraOperation;

  @Autowired
  private AccessTokenValidator accessTokenValidator;

  @Autowired
  private DemandServiceImpl demandService;

  @Autowired
  private Producer kafkaProducer;
  @Override
  public CustomResponse createInterest(JsonNode interestDetails) {
    log.info("InterestServiceImpl::createInterest:entered the method: " + interestDetails);
    CustomResponse response = new CustomResponse();
    payloadValidation.validatePayload(Constants.INTEREST_VALIDATION_FILE_JSON, interestDetails);
    Map<String, Object> propertyMap = new HashMap<>();
    String orgId = interestDetails.get(Constants.ORG_ID).asText();
    propertyMap.put(Constants.ID, interestDetails.get(Constants.ORG_ID).asText());
    List<Map<String, Object>> orgDetails = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
        Constants.KEYSPACE_SUNBIRD, Constants.ORG_TABLE, propertyMap, null, 1);
    if (CollectionUtils.isEmpty(orgDetails)) {
      response.setMessage("OrgDetails are not fetched for given orgId");
      response.setResponseCode(HttpStatus.NOT_FOUND);
      return response;
    }
    String orgName = (String) orgDetails.get(0).get(Constants.USER_ROOT_ORG_NAME);
    log.debug("InterestServiceImpl::createInterest:validated the payload");
    try {
      log.info("InterestServiceImpl::createInterest:creating interest");
      Interests interest = new Interests();
      UUID interestIdUuid = UUIDs.timeBased();
      String interestId = String.valueOf(interestIdUuid);
      interest.setInterestId(interestId);
      ((ObjectNode) interestDetails).put(Constants.INTEREST_ID_RQST, String.valueOf(interestId));
      ((ObjectNode) interestDetails).put(Constants.ORG_NAME, orgName);
      ((ObjectNode) interestDetails).put(Constants.STATUS, Constants.REQUESTED);
      Timestamp currentTime = new Timestamp(System.currentTimeMillis());
      Optional<DemandEntity> demandEntity = demandRepository.findById(
          interestDetails.get(Constants.DEMAND_ID_RQST).asText());
      if (demandEntity.isPresent()) {
        JsonNode fetchedDemandJson = null;
        if (!demandEntity.get().getData().isEmpty()) {
          fetchedDemandJson = demandEntity.get().getData();
        } else {
          response.setMessage("data is not present for demand");
          response.setResponseCode(HttpStatus.BAD_REQUEST);
          return response;
        }
        ((ObjectNode) fetchedDemandJson).put(Constants.INTEREST_COUNT,
            fetchedDemandJson.get(Constants.INTEREST_COUNT).asInt() + 1);
        Set<String> interestOrgSet = new HashSet<>();
        if (fetchedDemandJson.has(Constants.INTEREST_ORG_SET) && !ObjectUtils.isEmpty(
            fetchedDemandJson.get(Constants.INTEREST_ORG_SET))) {
          for (JsonNode node : fetchedDemandJson.get(Constants.INTEREST_ORG_SET)) {
            interestOrgSet.add(node.asText());
          }
        }
        if (interestOrgSet.contains(orgId)) {
          response.setResponseCode(HttpStatus.BAD_REQUEST);
          response.setMessage("Interest already generated by same organisation");
          return response;
        } else {
          interestOrgSet.add(orgId);
          // Update interestOrgSet
          ((ObjectNode) fetchedDemandJson).putPOJO(Constants.INTEREST_ORG_SET, interestOrgSet);
        }
        updateCountAndStatusOfDemand(demandEntity.get(), currentTime, fetchedDemandJson);
        log.info("InterestServiceImpl::createInterest:updated the interestCount in demand");
        ((ObjectNode) interestDetails).put(Constants.CREATED_ON, String.valueOf(currentTime));
        ((ObjectNode) interestDetails).put(Constants.UPDATED_ON, String.valueOf(currentTime));
        interest.setData(interestDetails);
        interest.setCreatedOn(currentTime);
        interest.setUpdatedOn(currentTime);
        interestRepository.save(interest);
        log.info("InterestServiceImpl::createInterest::persited interest in postgres");
        ObjectNode jsonNode = objectMapper.createObjectNode();
        jsonNode.set(Constants.INTEREST_ID_RQST,
            new TextNode(interestDetails.get(Constants.INTEREST_ID_RQST).asText()));
        jsonNode.setAll((ObjectNode) interestDetails);
        Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
        esUtilService.addDocument(Constants.INTEREST_INDEX_NAME, Constants.INDEX_TYPE,
            String.valueOf(interestId), map, cbServerProperties.getElasticDemandJsonPath());
        cacheService.putCache(interestId, jsonNode);
        response.setMessage(Constants.SUCCESSFULLY_CREATED);
        map.put(Constants.INTEREST_ID_RQST, interestId);
        response.setResult(map);
        response.setResponseCode(HttpStatus.OK);
        log.info("InterestServiceImpl::createInterest::persited interest in Pores");
        return response;
      } else {
        response.setMessage("Data for the provided demandId is not present to show interest");
        response.setResponseCode(HttpStatus.NOT_FOUND);
        return response;
      }
    } catch (Exception e) {
      logger.error("Error occurred while creating Interest", e);
      throw new CustomException("error while processing", e.getMessage(),
          HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  @Override
  public CustomResponse searchDemand(SearchCriteria searchCriteria) {
    log.info("InterestServiceImpl::searchDemand");
    CustomResponse response = new CustomResponse();
    SearchResult searchResult = redisTemplate.opsForValue()
        .get(generateRedisJwtTokenKey(searchCriteria));
    if (searchResult != null) {
      log.info("InterestServiceImpl::searchInterest: interest search result fetched from redis");
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
          esUtilService.searchDocuments(Constants.INTEREST_INDEX_NAME, searchCriteria);
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
  public CustomResponse assignInterestToDemand(JsonNode interestDetails, String token) {
    log.info("InterestServiceImpl::assignInterestToDemand:inside the method");
    CustomResponse response = new CustomResponse();
    if (interestDetails.get(Constants.INTEREST_ID_RQST) == null || interestDetails.get(Constants.INTEREST_ID_RQST).asText().isEmpty()) {
      response.getParams().setErrmsg(Constants.INTEREST_ID_MISSING);
      response.setResponseCode(HttpStatus.BAD_REQUEST);
      return response;
    }
    if (interestDetails.get(Constants.DEMAND_ID_RQST) == null || interestDetails.get(Constants.DEMAND_ID_RQST).asText().isEmpty()) {
      response.getParams().setErrmsg(Constants.DEMAND_ID_MISSING);
      response.setResponseCode(HttpStatus.BAD_REQUEST);
      return response;
    }
    String userId = accessTokenValidator.verifyUserToken(token);
    if (StringUtils.isBlank(userId) || userId.equalsIgnoreCase(Constants.UNAUTHORIZED)) {
      response.getParams().setErrmsg(Constants.USER_ID_DOESNT_EXIST);
      response.setResponseCode(HttpStatus.BAD_REQUEST);
      return response;
    }else {
      ((ObjectNode) interestDetails).put(Constants.ASSIGNED_BY, userId);
    }

    Optional<Interests> optSchemeDetails = interestRepository.findById(
        interestDetails.get(Constants.INTEREST_ID_RQST).asText());
    Timestamp currentTime = new Timestamp(System.currentTimeMillis());
    if (optSchemeDetails.isPresent()) {
      String demandIdPayload = interestDetails.get(Constants.DEMAND_ID_RQST).asText();
      interestDetails = optSchemeDetails.get().getData();
      String fetchedDemandID = interestDetails.get(Constants.DEMAND_ID_RQST).asText();
      if (!fetchedDemandID.equalsIgnoreCase(demandIdPayload)) {
        log.info("InterestServiceImpl::assignInterestToDemand:demandId passed and fetched are not matching");
        response.getParams().setErrmsg("DemandId passed is not matching with the fetched demandId");
        response.setResponseCode(HttpStatus.BAD_REQUEST);
        return response;
      }
      log.info("InterestServiceImpl::assignInterestToDemand:fetched interest for the given interestId");
      Optional<DemandEntity> demandEntity = demandRepository.findById(
          interestDetails.get(Constants.DEMAND_ID_RQST).asText());
      Map<String, Object> demandMap = new HashMap<>();
      if (demandEntity.isPresent()) {
        log.info("InterestServiceImpl::assignInterestToDemand:fetched interest for the given demandId");
        JsonNode fetchedDemandJson = demandEntity.get().getData();
        if (!fetchedDemandJson.isEmpty()) {
          if (fetchedDemandJson.get(Constants.STATUS).asText()
              .equalsIgnoreCase(Constants.UNASSIGNED)) {
            ((ObjectNode) fetchedDemandJson).put(Constants.STATUS, Constants.ASSIGNED);
            log.info("InterestServiceImpl::assignInterestToDemand:changed the status of demand ot assigned");
          } else {
            if (!fetchedDemandJson.get(Constants.ASSIGNED_PROVIDER).isEmpty()) {
              JsonNode fetchedAssignedProvider = fetchedDemandJson.get(Constants.ASSIGNED_PROVIDER);
              JsonNode orgIdNode = fetchedAssignedProvider.get(Constants.PROVIDER_ID);
              String fetchedOrgId = orgIdNode.asText();
              if (!fetchedOrgId.equalsIgnoreCase(interestDetails.get(Constants.ORG_ID).asText())) {
                ((ObjectNode) fetchedDemandJson).put(Constants.PREV_ASSIGNED_PROVIDER,
                    fetchedDemandJson.get(Constants.ASSIGNED_PROVIDER));
              } else {
                response.setMessage("Assigning to the same org please reassign");
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
              }

            }
          }
          JsonNode assignedProvider = objectMapper.createObjectNode();
          ((ObjectNode) assignedProvider).put(Constants.PROVIDER_ID,
              interestDetails.get(Constants.ORG_ID));
          ((ObjectNode) assignedProvider).put(Constants.PROVIDER_NAME,
              interestDetails.get(Constants.ORG_NAME));
          ((ObjectNode) assignedProvider).put(Constants.INTEREST_ID_RQST,
              interestDetails.get(Constants.INTEREST_ID_RQST));
          ((ObjectNode) assignedProvider).put(Constants.ASSIGNED_BY,
              interestDetails.get(Constants.ASSIGNED_BY));
          ((ObjectNode) fetchedDemandJson).put(Constants.ASSIGNED_PROVIDER, assignedProvider);
           demandMap = updateCountAndStatusOfDemand(demandEntity.get(), currentTime, fetchedDemandJson);
          log.info(
              "InterestServiceImpl::assignInterestToDemand:updated the status and assigned provider in demand");
        }

      }else {
        response.getParams().setErrmsg("No data found for this demand");
        response.setResponseCode(HttpStatus.NOT_FOUND);
        return response;
      }
      Interests fetchedEntity = optSchemeDetails.get();
      ((ObjectNode) interestDetails).put(Constants.STATUS, Constants.GRANTED);
      JsonNode persistUpdatedInterest = fetchedEntity.getData();
      ((ObjectNode) persistUpdatedInterest).put(Constants.STATUS, Constants.GRANTED);
      ((ObjectNode) persistUpdatedInterest).put(Constants.ASSIGNED_BY,
          userId);
      ((ObjectNode) persistUpdatedInterest).put(Constants.UPDATED_ON, String.valueOf(currentTime));
      fetchedEntity.setData(persistUpdatedInterest);
      fetchedEntity.setUpdatedOn(currentTime);
      interestRepository.save(fetchedEntity);
      ObjectNode jsonNode = objectMapper.createObjectNode();
      jsonNode.set(Constants.INTEREST_ID_RQST, new TextNode(fetchedEntity.getInterestId()));
      jsonNode.setAll((ObjectNode) persistUpdatedInterest);

      Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
      esUtilService.addDocument(Constants.INTEREST_INDEX_NAME, Constants.INDEX_TYPE,
          interestDetails.get(Constants.INTEREST_ID_RQST).asText(), map, cbServerProperties.getElasticInterestJsonPath());

      cacheService.putCache(fetchedEntity.getInterestId(), jsonNode);
      log.info("assigned interest");
      Map<String, Object> dataMap = new HashMap<>();
      dataMap.put(Constants.DATA,demandMap);
      dataMap.put(Constants.IS_SPV_REQUEST,false);
      dataMap.put(Constants.USER_ID_RQST,userId);
      if(demandService.isSpvRequest(userId,Constants.SPV_ADMIN)){
        dataMap.put(Constants.IS_SPV_REQUEST,true);
      }
        kafkaProducer.push(cbServerProperties.getDemandRequestKafkaTopic(), dataMap);
      log.info("kafka message pushed");
      map.put(Constants.INTEREST_ID_RQST, fetchedEntity.getInterestId());
      response.setResult(map);
      response.setMessage(Constants.SUCCESSFULLY_ASSIGNED);
      response.setResponseCode(HttpStatus.OK);
      return response;
    } else {
      logger.error("no data found");
      throw new CustomException(Constants.ERROR, Constants.NO_DATA_FOUND, HttpStatus.NOT_FOUND);
    }
  }

  @Override
  public CustomResponse read(String id) {
    log.info("InterestServiceImpl::read:inside the method");
    CustomResponse response = new CustomResponse();
    if (StringUtils.isEmpty(id)) {
      logger.error("InterestServiceImpl::read:Id not found");
      response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
      response.setMessage(Constants.ID_NOT_FOUND);
      return response;
    }
    try {
      String cachedJson = cacheService.getCache(id);
      if (StringUtils.isNotEmpty(cachedJson)) {
        log.info("InterestServiceImpl::read:Record coming from redis cache");
        response.setMessage(Constants.SUCCESSFULLY_READING);
        response
            .getResult()
            .put(Constants.RESULT, objectMapper.readValue(cachedJson, new TypeReference<Object>() {
            }));
      } else {
        Optional<Interests> entityOptional = interestRepository.findById(id);
        if (entityOptional.isPresent()) {
          Interests interests = entityOptional.get();
          cacheService.putCache(id, interests.getData());
          log.info("InterestServiceImpl::read:Record coming from postgres db");
          response.setMessage(Constants.SUCCESSFULLY_READING);
          response
              .getResult()
              .put(Constants.RESULT,
                  objectMapper.convertValue(
                      interests.getData(), new TypeReference<Object>() {
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

  private Map<String, Object> updateCountAndStatusOfDemand(DemandEntity demand, Timestamp currentTime,
      JsonNode fetchedDemandDetails) {
    log.info("InterestServiceImpl::updateCountAndStatusOfDemand:inside the method");
    ((ObjectNode) fetchedDemandDetails).put(Constants.UPDATED_ON, String.valueOf(currentTime));
    ((ObjectNode) fetchedDemandDetails).put(Constants.DEMAND_ID, demand.getDemandId());
    demand.setData(fetchedDemandDetails);
    demand.setUpdatedOn(currentTime);
    demandRepository.save(demand);
    Map<String, Object> esMap = objectMapper.convertValue(fetchedDemandDetails, Map.class);
    esUtilService.addDocument(Constants.INDEX_NAME, Constants.INDEX_TYPE, demand.getDemandId(),
        esMap, cbServerProperties.getElasticDemandJsonPath());
    cacheService.putCache(demand.getDemandId(), fetchedDemandDetails);
    log.info("InterestServiceImpl::updateCountAndStatusOfDemand:updated the demand");
    return esMap;
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
            .sign(Algorithm.HMAC256(Constants.JWT_SECRET_KEY));
      } catch (JsonProcessingException e) {
        logger.error("Error occurred while converting json object to json string", e);
      }
    }
    return "";
  }

  public void createErrorResponse(
      CustomResponse response, String errorMessage, HttpStatus httpStatus, String status) {
    response.setParams(new RespParam());
    response.getParams().setStatus(status);
    response.setResponseCode(httpStatus);
  }
}
