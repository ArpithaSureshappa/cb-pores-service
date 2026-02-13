package com.igot.cb.orgbookmark.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.orgbookmark.entity.OrgBookmarkEntity;
import com.igot.cb.orgbookmark.repository.OrgBookmarkRepository;
import com.igot.cb.pores.Service.OutboundRequestHandlerServiceImpl;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.dto.CustomResponse;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

import java.sql.Timestamp;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgBookmarkServiceImplTest {

    @Spy
    @InjectMocks
    private OrgBookmarkServiceImpl orgBookmarkService;

    @Mock
    private EsUtilService esUtilService;
    @Mock private OrgBookmarkRepository orgBookmarkRepository;
    @Mock private CacheService cacheService;
    @Mock private ObjectMapper objectMapper;
    @Mock private RedisTemplate<String, SearchResult> redisTemplate;
    @Mock private CbServerProperties cbServerProperties;
    @Mock private AccessTokenValidator accessTokenValidator;
    @Mock private OutboundRequestHandlerServiceImpl outboundRequestHandlerService;

    @Mock private ValueOperations<String, SearchResult> valueOperations;

    private JsonNode validPayload;

    private static final String AUTH_TOKEN = "authToken";
    private static final String USER_ID = "user123";
    private static final String BOOKMARK_ID = "bookmark123";

    @BeforeEach
    void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        ObjectMapper mapper = new ObjectMapper();
        String json = """
        {
          "orgId": "org123",
          "category": "testCategory",
          "orgList": ["org1", "org2"]
        }
        """;
        validPayload = mapper.readTree(json);


       // when(cbServerProperties.getOrgSearchPath()).thenReturn("/search");
    }

    @Test
    void testCreateOrgBookmark_success() throws Exception {
        String authToken = "Bearer valid.token";
        String userId = "user123";

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(orgBookmarkRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // Mocking searchOrg internal call
        Map<String, Object> response = new HashMap<>();
        response.put("responseCode", "OK");
        response.put("result", Map.of("response", Map.of("content", List.of(Map.of("name", "org1")))));
        when(outboundRequestHandlerService.fetchResultUsingPost(anyString(), any(), any()))
                .thenReturn(response);

        ApiResponse responseObj = orgBookmarkService.createOrgBookmark(validPayload.deepCopy(), authToken);

        assertEquals(HttpStatus.OK, responseObj.getResponseCode());
        assertEquals("success", responseObj.getParams().getStatus());
        assertNotNull(responseObj.getResult().get("orgBookmarkId"));
    }

    @Test
    void testCreateOrgBookmark_duplicateBookmarkFailure() {
        when(cbServerProperties.getBookmarkDuplicateNotAllowedCategory())
                .thenReturn(List.of("testCategory"));
        when(cbServerProperties.getElasticBookmarkJsonPath()).thenReturn("dummyPath");
        when(cbServerProperties.getSearchResultRedisTtl()).thenReturn(100L);
        when(cbServerProperties.getSbApiKey()).thenReturn("dummy");
        when(cbServerProperties.getLearnerServiceUrl()).thenReturn("http://mock");
        when(accessTokenValidator.fetchUserIdFromAccessToken(anyString()))
                .thenReturn("user123");

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Mock duplicate found
        CustomResponse customResponse = new CustomResponse();
        customResponse.getResult().put("totalCount", 1L);
        SearchCriteria searchCriteria = new SearchCriteria();
        Map<String, Object> filter = new HashMap<>();
        filter.put(Constants.CATEGORY, "category");
        filter.put(Constants.ORG_ID, "orgId");
        filter.put(Constants.IS_ACTIVE, Constants.ACTIVE_STATUS);
        searchCriteria.setFilterCriteriaMap((HashMap<String, Object>) filter);
        when(orgBookmarkService.search(searchCriteria)).thenReturn(customResponse);


        assertThrows(CustomException.class, () ->
                orgBookmarkService.createOrgBookmark(validPayload.deepCopy(), "token"));
    }

    @Test
    void testCreateOrgBookmark_userIdBlank() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(anyString())).thenReturn("");

        ApiResponse response = orgBookmarkService.createOrgBookmark(validPayload.deepCopy(), "token");

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Failed", response.getParams().getStatus());
    }

    @Test
    void testCreateOrgBookmark_exceptionThrown() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(anyString()))
                .thenThrow(new RuntimeException("Unexpected"));

        CustomException ex = assertThrows(CustomException.class, () ->
                orgBookmarkService.createOrgBookmark(validPayload.deepCopy(), "token"));

        assertEquals("Unexpected", ex.getMessage());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getHttpStatusCode());
    }

    @Test
    void testSearch_resultFromRedis() {
        SearchCriteria criteria = new SearchCriteria();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(new SearchResult());

        CustomResponse response = orgBookmarkService.search(criteria);

        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    @Test
    void testSearch_shortSearchString() {
        SearchCriteria criteria = new SearchCriteria();
        criteria.setSearchString("a");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        CustomResponse response = orgBookmarkService.search(criteria);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("FAILED", response.getParams().getStatus());
    }

    @Test
    void testSearch_elasticsearchThrowsException() throws Exception {
        SearchCriteria criteria = new SearchCriteria();
        criteria.setSearchString("valid");

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(esUtilService.searchDocuments(any(), any())).thenThrow(new RuntimeException("ES error"));

        CustomResponse response = orgBookmarkService.search(criteria);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    @Test
    void testUpdateOrgBookmark_success() {
        ObjectMapper mapper = new ObjectMapper();

        // Build test payload
        Map<String, Object> data = new HashMap<>();
        data.put("category", "someCategoryValue");
        data.put("orgId", "someOrgIdValue");
        data.put("orgBookmarkId", "someBookmarkIdValue");  // Must match mocked repository ID
        data.put("orgList", List.of("org1", "org2"));

        JsonNode payload = mapper.valueToTree(data);

        // Build existing entity
        JsonNode orgDetails = buildOrgDetailsNode();
        OrgBookmarkEntity existingEntity = new OrgBookmarkEntity();
        existingEntity.setOrgBookmarkId("someBookmarkIdValue");
        existingEntity.setData(orgDetails);
        existingEntity.setCreatedOn(new Timestamp(System.currentTimeMillis() - 10000));
        existingEntity.setUpdatedOn(new Timestamp(System.currentTimeMillis()));

        // Mock dependencies
        when(accessTokenValidator.verifyUserToken(AUTH_TOKEN)).thenReturn(USER_ID);
        when(orgBookmarkRepository.findById("someBookmarkIdValue")).thenReturn(Optional.of(existingEntity));
        when(orgBookmarkRepository.save(any())).thenReturn(existingEntity);
        when(cbServerProperties.getElasticBookmarkJsonPath()).thenReturn("path");
        when(cbServerProperties.getSbApiKey()).thenReturn("apiKey");
        when(cbServerProperties.getLearnerServiceUrl()).thenReturn("http://dummy-url");
        when(cbServerProperties.getOrgSearchPath()).thenReturn("/org/search");

        // Mock response for outbound request
        Map<String, Object> mockResponse = new HashMap<>();
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> innerResp = new HashMap<>();
        List<Map<String, Object>> contentList = new ArrayList<>();
        contentList.add(Map.of("id", "org1"));
        innerResp.put(Constants.CONTENT, contentList);
        result.put("response", innerResp);
        mockResponse.put("result", result);
        mockResponse.put("responseCode", "OK");

        when(outboundRequestHandlerService.fetchResultUsingPost(anyString(), any(), any()))
                .thenReturn(mockResponse);

        // Execute
        ApiResponse response = orgBookmarkService.updateOrgBookmark(payload, AUTH_TOKEN);

        // Verify response
        assertEquals(Constants.SUCCESS, response.get(Constants.RESPONSE));
        assertEquals(Constants.SUCCESSFULLY_UPDATED, response.getResult().get(Constants.STATUS));
        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    @Test
    void testUpdateOrgBookmark_blankUserId() {
        ObjectMapper mapper = new ObjectMapper();

        Map<String, Object> data = new HashMap<>();
        data.put("category", "someCategoryValue");
        data.put("orgId", "someOrgIdValue");

// Add missing required fields:
        data.put("orgBookmarkId", "someBookmarkIdValue");

// For orgList, since it's likely a list, provide a List or ArrayNode
        List<String> orgList = List.of("org1", "org2"); // example org IDs or details
        data.put("orgList", orgList);

        JsonNode payload = mapper.valueToTree(data);



        when(accessTokenValidator.verifyUserToken(AUTH_TOKEN)).thenReturn("");

        ApiResponse response = orgBookmarkService.updateOrgBookmark(payload, AUTH_TOKEN);

        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }


    @Test
    void testUpdateOrgBookmark_validationException() {
        JsonNode invalidNode = buildOrgDetailsNode();

        // simulate failure inside validatePayload
        doThrow(new CustomException("Validation Error", "schema fail", HttpStatus.BAD_REQUEST))
                .when(orgBookmarkService).validatePayload(anyString(), eq(invalidNode));

        Assertions.assertThrows(CustomException.class, () ->
                orgBookmarkService.updateOrgBookmark(invalidNode, AUTH_TOKEN));
    }

    @Test
    void testUpdateOrgBookmark_exceptionInProcessing() {
        JsonNode orgDetails = buildOrgDetailsNode();
        ObjectMapper mapper = new ObjectMapper();

        Map<String, Object> data = new HashMap<>();
        data.put("category", "someCategoryValue");
        data.put("orgId", "someOrgIdValue");

// Add missing required fields:
        data.put("orgBookmarkId", "someBookmarkIdValue");

// For orgList, since it's likely a list, provide a List or ArrayNode
        List<String> orgList = List.of("org1", "org2"); // example org IDs or details
        data.put("orgList", orgList);

        JsonNode payload = mapper.valueToTree(data);

        OrgBookmarkEntity entity = new OrgBookmarkEntity();
        entity.setOrgBookmarkId(BOOKMARK_ID);
        entity.setData(orgDetails);
        entity.setCreatedOn(new Timestamp(System.currentTimeMillis()));
        entity.setUpdatedOn(new Timestamp(System.currentTimeMillis()));

        when(accessTokenValidator.verifyUserToken(AUTH_TOKEN)).thenReturn(USER_ID);
        when(orgBookmarkRepository.findById(BOOKMARK_ID)).thenReturn(Optional.of(entity));
        when(orgBookmarkRepository.save(any())).thenReturn(entity);
        when(cbServerProperties.getElasticBookmarkJsonPath()).thenReturn("path");

        ApiResponse response = orgBookmarkService.updateOrgBookmark(payload, AUTH_TOKEN);
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals("Org bookmark not found", response.getParams().getErrMsg());
    }

    @Test
    void testReadOrgBookmarkById_idIsEmpty() {
        ApiResponse response = orgBookmarkService.readOrgBookmarkById("");
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals(Constants.ID_NOT_FOUND, response.getParams().getErrMsg());
    }

    @Test
    void testReadOrgBookmarkById_fromRedisCache() throws Exception {
        String json = "{\"orgList\": [\"org1\"]}";
        when(cacheService.getCache(Constants.REDIS_ORG_BOOKMARK_KEY + "_" + BOOKMARK_ID)).thenReturn(json);
        when(objectMapper.readValue(eq(json), ArgumentMatchers.<TypeReference<Object>>any()))
                .thenReturn(Map.of("orgList", List.of("org1")));

        ApiResponse response = orgBookmarkService.readOrgBookmarkById(BOOKMARK_ID);

        assertEquals(Constants.SUCCESSFULLY_READING, response.getParams().getErrMsg());
        assertNotNull(response.getResult().get(Constants.DATA));
    }

    @Test
    void testReadOrgBookmarkById_fromPostgres() throws Exception {
        // Setup entity with orgList
        ObjectNode dataNode = new ObjectMapper().createObjectNode();
        ArrayNode arrayNode = new ObjectMapper().createArrayNode();
        arrayNode.add("org1");
        dataNode.set(Constants.ORG_LIST, arrayNode);

        OrgBookmarkEntity entity = new OrgBookmarkEntity();
        entity.setOrgBookmarkId(BOOKMARK_ID);
        entity.setData(dataNode);

        when(cacheService.getCache(Constants.REDIS_ORG_BOOKMARK_KEY + "_" + BOOKMARK_ID)).thenReturn(null);
        when(orgBookmarkRepository.findById(BOOKMARK_ID)).thenReturn(Optional.of(entity));

        // Outbound search result
        Map<String, Object> orgMap = Map.of("id", "org1");
        List<Map<String, Object>> orgList = List.of(orgMap);
        Map<String, Object> innerResponse = Map.of(Constants.CONTENT, orgList);
        Map<String, Object> result = Map.of("response", innerResponse);
        Map<String, Object> responseMap = Map.of("result", result, "responseCode", "OK");

        when(cbServerProperties.getLearnerServiceUrl()).thenReturn("http://dummy-url");
        when(cbServerProperties.getOrgSearchPath()).thenReturn("/org/search");
        when(cbServerProperties.getSbApiKey()).thenReturn("dummy-key");

        when(outboundRequestHandlerService.fetchResultUsingPost(anyString(), any(), any()))
                .thenReturn(responseMap);
        when(objectMapper.convertValue(any(), eq(List.class))).thenReturn(List.of("org1"));
        when(objectMapper.convertValue(any(), eq(JsonNode.class))).thenReturn(arrayNode);
        when(objectMapper.convertValue(any(), ArgumentMatchers.<TypeReference<Object>>any()))
                .thenReturn(Map.of("orgList", orgList));

        ApiResponse response = orgBookmarkService.readOrgBookmarkById(BOOKMARK_ID);

        assertEquals(Constants.SUCCESSFULLY_READING, response.getParams().getErrMsg());
        assertNotNull(response.getResult().get(Constants.DATA));
    }

    @Test
    void testReadOrgBookmarkById_invalidIdNotFoundInDB() {
        when(cacheService.getCache(Constants.REDIS_ORG_BOOKMARK_KEY + "_" + BOOKMARK_ID)).thenReturn(null);
        when(orgBookmarkRepository.findById(BOOKMARK_ID)).thenReturn(Optional.empty());

        ApiResponse response = orgBookmarkService.readOrgBookmarkById(BOOKMARK_ID);

        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals(Constants.INVALID_ID, response.getParams().getErrMsg());
    }

    @Test
    void testReadOrgBookmarkById_jsonMappingException() throws Exception {
        String badJson = "invalid_json";
        when(cacheService.getCache(Constants.REDIS_ORG_BOOKMARK_KEY + "_" + BOOKMARK_ID)).thenReturn(badJson);
        when(objectMapper.readValue(eq(badJson), ArgumentMatchers.<TypeReference<Object>>any()))
                .thenThrow(new RuntimeException("JSON parse error"));

        CustomException ex = assertThrows(CustomException.class, () ->
                orgBookmarkService.readOrgBookmarkById(BOOKMARK_ID));

        assertEquals("error while processing", ex.getMessage());
        assertEquals(Constants.ERROR, ex.getCode());
    }

    @Test
    void testDeleteOrgBookmark_success() {
        ObjectNode data = new ObjectMapper().createObjectNode();
        data.put(Constants.IS_ACTIVE, true);

        OrgBookmarkEntity entity = new OrgBookmarkEntity();
        entity.setOrgBookmarkId(BOOKMARK_ID);
        entity.setData(data);

        when(orgBookmarkRepository.findById(BOOKMARK_ID)).thenReturn(Optional.of(entity));
        when(orgBookmarkRepository.save(any())).thenReturn(entity);
        when(cbServerProperties.getElasticBookmarkJsonPath()).thenReturn("dummy-path");
        when(objectMapper.convertValue(data, Map.class)).thenReturn(Map.of("isActive", false));

        ApiResponse response = orgBookmarkService.deleteOrgBookmarkById(BOOKMARK_ID);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.get(Constants.RESPONSE));
        assertEquals(Constants.DELETED_SUCCESSFULLY, response.getResult().get(Constants.STATUS));
        assertEquals(BOOKMARK_ID, response.getResult().get(Constants.ORG_BOOKMARK_ID));
    }

    @Test
    void testDeleteOrgBookmark_alreadyInactive() {
        ObjectNode data = new ObjectMapper().createObjectNode();
        data.put(Constants.IS_ACTIVE, false);

        OrgBookmarkEntity entity = new OrgBookmarkEntity();
        entity.setOrgBookmarkId(BOOKMARK_ID);
        entity.setData(data);

        when(orgBookmarkRepository.findById(BOOKMARK_ID)).thenReturn(Optional.of(entity));

        ApiResponse response = orgBookmarkService.deleteOrgBookmarkById(BOOKMARK_ID);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.get(Constants.RESPONSE));
        assertEquals(Constants.ALREADY_INACTIVE, response.getParams().getErrMsg());
        assertEquals(BOOKMARK_ID, response.getResult().get(Constants.ORG_BOOKMARK_ID));
    }

    @Test
    void testDeleteOrgBookmark_idNotFound() {
        when(orgBookmarkRepository.findById(BOOKMARK_ID)).thenReturn(Optional.empty());

        ApiResponse response = orgBookmarkService.deleteOrgBookmarkById(BOOKMARK_ID);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.get(Constants.RESPONSE));
        assertEquals(Constants.INVALID_ID, response.getParams().getErrMsg());
        assertEquals(BOOKMARK_ID, response.getResult().get(Constants.ORG_BOOKMARK_ID));
    }

    @Test
    void testDeleteOrgBookmark_emptyId() {
        ApiResponse response = orgBookmarkService.deleteOrgBookmarkById("");

        // Only default response expected, no further processing
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertNull(response.get(Constants.RESPONSE)); // nothing added to response
    }

    @Test
    void testDeleteOrgBookmark_exceptionThrown() {
        ObjectNode data = new ObjectMapper().createObjectNode();
        data.put(Constants.IS_ACTIVE, true);

        OrgBookmarkEntity entity = new OrgBookmarkEntity();
        entity.setOrgBookmarkId(BOOKMARK_ID);
        entity.setData(data);

        when(orgBookmarkRepository.findById(BOOKMARK_ID)).thenReturn(Optional.of(entity));
        when(orgBookmarkRepository.save(any())).thenThrow(new RuntimeException("DB error"));

        ApiResponse response = orgBookmarkService.deleteOrgBookmarkById(BOOKMARK_ID);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals("DB error", response.getParams().getErrMsg());
    }

    private JsonNode buildOrgDetailsNode() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put(Constants.ORG_BOOKMARK_ID, BOOKMARK_ID);
        node.put("title", "Bookmark Title");

        ArrayNode orgList = mapper.createArrayNode();
        orgList.add("org1");
        orgList.add("org2");
        node.set(Constants.ORG_LIST, orgList);

        return node;
    }
}

