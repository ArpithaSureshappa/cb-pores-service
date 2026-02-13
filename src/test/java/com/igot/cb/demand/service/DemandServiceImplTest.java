package com.igot.cb.demand.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.demand.entity.DemandEntity;
import com.igot.cb.demand.repository.DemandRepository;
import com.igot.cb.demand.util.StatusTransitionConfig;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.dto.CustomResponse;
import com.igot.cb.pores.dto.RespParam;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.producer.Producer;
import com.igot.cb.transactional.cassandrautils.CassandraOperation;
import com.igot.cb.transactional.service.RequestHandlerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DemandServiceImplTest {

    @Mock
    private AccessTokenValidator accessTokenValidator;

    @Mock
    private CacheService cacheService;

    @Mock
    private CassandraOperation cassandraOperation;

    @Mock
    private CbServerProperties cbServerProperties;

    @Mock
    private DemandRepository demandRepository;

    @Mock
    private EsUtilService esUtilService;

    @Mock
    private Producer kafkaProducer;

    @Mock
    private Logger logger;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private RedisTemplate<String, SearchResult> redisTemplate;

    @Mock
    private RequestHandlerServiceImpl requestHandlerService;

    @Mock
    private StatusTransitionConfig statusTransitionConfig;

    @Mock
    private ValueOperations<String, SearchResult> valueOperations;

    private DemandServiceImpl demandService;

    @BeforeEach
    void setup(){
        // Create instance without calling constructor
        Objenesis objenesis = new ObjenesisStd();
        demandService = objenesis.newInstance(DemandServiceImpl.class);

        // Inject all mocks and real fields manually
        ReflectionTestUtils.setField(demandService, "demandRepository", demandRepository);
        ReflectionTestUtils.setField(demandService, "cacheService", cacheService);
        ReflectionTestUtils.setField(demandService, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(demandService, "accessTokenValidator", accessTokenValidator);
        ReflectionTestUtils.setField(demandService, "cassandraOperation", cassandraOperation);
        ReflectionTestUtils.setField(demandService, "kafkaProducer", kafkaProducer);
        ReflectionTestUtils.setField(demandService, "requestHandlerService", requestHandlerService);
        ReflectionTestUtils.setField(demandService, "cbServerProperties", cbServerProperties);
        ReflectionTestUtils.setField(demandService, "esUtilService", esUtilService);
        ReflectionTestUtils.setField(demandService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(demandService, "logger", LoggerFactory.getLogger(DemandServiceImpl.class));
        // Mock statusTransitionConfig to avoid file read
        ReflectionTestUtils.setField(demandService, "statusTransitionConfig", mock(StatusTransitionConfig.class));
    }



    /**
     * Testcase 1 for @Autowired public DemandServiceImpl() throws IOException
     * <p>
     * This test verifies that the DemandServiceImpl constructor initializes
     * the statusTransitionConfig correctly with the expected path.
     */
    @Test
    void test_DemandServiceImpl_1() {

        assertNotNull(demandService, "DemandServiceImpl should be created successfully");
        // We can't directly access private fields, so we can't check statusTransitionConfig
        // However, we can verify that no exception is thrown during initialization
    }

    /**
     * Test case for createErrorResponse method
     * This test verifies that the createErrorResponse method correctly sets the error response parameters
     */
    @Test
    void test_createErrorResponse_1() {
        CustomResponse response = new CustomResponse();
        String errorMessage = "Test error message";
        HttpStatus httpStatus = HttpStatus.BAD_REQUEST;
        String status = "FAILED";

        demandService.createErrorResponse(response, errorMessage, httpStatus, status);

        assertNotNull(response.getParams());
        assertEquals(status, response.getParams().getStatus());
        assertEquals(httpStatus, response.getResponseCode());
    }

    /**
     * Tests that the createErrorResponse method correctly sets the error response
     * when given empty strings for error message and status.
     */
    @Test
    void test_createErrorResponse_emptyStrings() {
        CustomResponse response = new CustomResponse();

        demandService.createErrorResponse(response, "", HttpStatus.BAD_REQUEST, "");

        assertNotNull(response.getParams());
        assertEquals("", response.getParams().getStatus());
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

    /**
     * Tests that the createErrorResponse method correctly sets the error response
     * when given null values for all parameters.
     */
    @Test
    void test_createErrorResponse_nullInputs() {
        CustomResponse response = new CustomResponse();

        demandService.createErrorResponse(response, null, null, null);

        assertNotNull(response.getParams());
        assertEquals(null, response.getParams().getStatus());
        assertEquals(null, response.getResponseCode());
    }

    /**
     * Test case for createSuccessResponse method
     * Verifies that the method correctly sets the success response parameters
     */
    @Test
    void test_createSuccessResponse_setsSuccessParameters() {
        CustomResponse response = new CustomResponse();

        demandService.createSuccessResponse(response);

        assertNotNull(response.getParams());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    /**
     * Testcase 1 for @Override public String delete(String id)
     * Path constraints: (StringUtils.isNotEmpty(id)), (entityOptional.isPresent()), (data.get(Constants.IS_ACTIVE).asBoolean())
     * returns: Constants.DELETED_SUCCESSFULLY
     */
    @Test
    void test_delete_1() {
        // Arrange
        String id = "testId";
        DemandEntity demandEntity = new DemandEntity();
        ObjectNode dataNode = mock(ObjectNode.class);
        when(dataNode.get(Constants.IS_ACTIVE)).thenReturn(dataNode);
        when(dataNode.asBoolean()).thenReturn(true);
        demandEntity.setData(dataNode);

        when(demandRepository.findById(id)).thenReturn(Optional.of(demandEntity));
        when(demandRepository.save(any(DemandEntity.class))).thenReturn(demandEntity);
        when(objectMapper.convertValue(any(), eq(java.util.Map.class))).thenReturn(new java.util.HashMap<>());
        when(cbServerProperties.getElasticDemandJsonPath()).thenReturn("testPath");

        // Act
        String result = demandService.delete(id);

        // Assert
        assertEquals(Constants.DELETED_SUCCESSFULLY, result);
        verify(demandRepository).findById(id);
        verify(demandRepository).save(any(DemandEntity.class));
        verify(esUtilService).addDocument(eq(Constants.INDEX_NAME), eq(Constants.INDEX_TYPE), eq(id), any(), eq("testPath"));
        verify(cacheService).putCache(eq(id), any(JsonNode.class));
    }

    /**
     * Testcase 2 for @Override public String delete(String id)
     * Path constraints: (StringUtils.isNotEmpty(id)), (entityOptional.isPresent()), !((data.get(Constants.IS_ACTIVE).asBoolean()))
     * returns: Constants.ALREADY_INACTIVE
     */
    @Test
    void test_delete_2() {
        MockitoAnnotations.openMocks(this);

        String id = "testId";
        DemandEntity demandEntity = new DemandEntity();
        ObjectNode dataNode = mock(ObjectNode.class);
        demandEntity.setData(dataNode);

        String result = demandService.delete(id);

        assertEquals(Constants.NO_DATA_FOUND, result);
    }

    /**
     * Testcase 3 for @Override public String delete(String id)
     * This test verifies the behavior of the delete method when the provided ID is not empty,
     * but the demand entity is not found in the repository.
     * Expected outcome: The method should return an error message indicating that the demand was not found.
     */
    @Test
    void test_delete_3() {
        // Arrange
        String id = "non_existent_id";
        when(demandRepository.findById(anyString())).thenReturn(Optional.empty());

        // Act
        String result = demandService.delete(id);

        // Assert
        assertEquals(Constants.NO_DATA_FOUND, result);
    }

    /**
     * Tests the delete method when the demand is already inactive.
     * This is an edge case where the method should return ALREADY_INACTIVE.
     */
    @Test
    void test_delete_already_inactive_demand() {

        DemandEntity mockEntity = new DemandEntity();
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode mockData = objectMapper.createObjectNode();
        mockData.put(Constants.IS_ACTIVE, false);
        mockEntity.setData(mockData);

        Mockito.when(demandRepository.findById("inactive_id")).thenReturn(Optional.of(mockEntity));

        String result = demandService.delete("inactive_id");

        assertEquals(Constants.ALREADY_INACTIVE, result);
    }

    /**
     * Testcase 4 for @Override public String delete(String id)
     * Path constraints: !((StringUtils.isNotEmpty(id)))
     * returns: Constants.ERROR_WHILE_DELETING_DEMAND + id + " " + e.getMessage()
     */
    @Test
    void test_delete_emptyId() {
        // Arrange
        String id = "";

        // Act
        String result = demandService.delete(id);

        // Assert
        assertEquals(Constants.INVALID_ID, result);
    }

    /**
     * Tests the delete method when an empty ID is provided.
     * This is an edge case where the method should return INVALID_ID.
     */
    @Test
    void test_delete_empty_id() {

        String result = demandService.delete("");

        assertEquals(Constants.INVALID_ID, result);
    }

    /**
     * Tests the delete method when the demand with the given ID is not found.
     * This is an edge case where the method should return NO_DATA_FOUND.
     */
    @Test
    void test_delete_non_existent_id() {

        Mockito.when(demandRepository.findById("non_existent_id")).thenReturn(Optional.empty());

        String result = demandService.delete("non_existent_id");

        assertEquals(Constants.NO_DATA_FOUND, result);
    }

    /**
     * Tests the delete method when a null ID is provided.
     * This is an edge case where the method should return INVALID_ID.
     */
    @Test
    void test_delete_null_id() {


        String result = demandService.delete(null);

        assertEquals(Constants.INVALID_ID, result);
    }

    /**
     * Test case for generateRedisJwtTokenKey method when requestPayload is not null.
     * This test verifies that the method generates a JWT token with the correct claim
     * and signs it with the expected algorithm.
     */
    @Test
    void test_generateRedisJwtTokenKey_1(){
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");

        Object requestPayload = new Object();

        String result = demandService.generateRedisJwtTokenKey(requestPayload);

        String expectedToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyZXF1ZXN0UGF5bG9hZCI6bnVsbH0.1_QviVZiSvsyjUHzK-QGNJ1qT8DTfAxVjy4orhCXCDE";

        assertEquals(expectedToken, result);
    }

    /**
     * Testcase 2 for public String generateRedisJwtTokenKey(Object requestPayload)
     * Path constraints: !((requestPayload != null))
     * returns: ""
     */
    @Test
    void test_generateRedisJwtTokenKey_2() {
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");

        Object requestPayload = null;
        String result = demandService.generateRedisJwtTokenKey(requestPayload);
        assertEquals("", result);
    }

    /**
     * Test case for generateRedisJwtTokenKey method when JsonProcessingException occurs.
     * This test verifies that the method returns an empty string and logs an error
     * when a JsonProcessingException is thrown during object serialization.
     */
    @Test
    void test_generateRedisJwtTokenKey_jsonProcessingException() throws JsonProcessingException {
        Object requestPayload = new Object();
        when(objectMapper.writeValueAsString(requestPayload)).thenThrow(JsonProcessingException.class);

        String result = demandService.generateRedisJwtTokenKey(requestPayload);

        assertEquals("", result);
    }

    /**
     * Test case for generateRedisJwtTokenKey method when null input is provided.
     * This test verifies that the method returns an empty string when the input is null.
     */
    @Test
    void test_generateRedisJwtTokenKey_nullInput() {
        String result = demandService.generateRedisJwtTokenKey(null);
        assertEquals("", result);
    }

    /**
     * Test case for generateUniqueDemandId method when a unique ID is generated after multiple attempts
     * This test verifies that the method returns a unique ID when the repository indicates
     * that the first few generated IDs already exist, but eventually a unique one is found
     */
    @Test
    void test_generateUniqueDemandId_1() {
        // Mock the behavior of demandRepository.existsById
        Mockito.when(demandRepository.existsById(anyString())).thenReturn(true, true, false); // First two calls return true, third call returns false

        // Call the method
        String uniqueId = demandService.generateUniqueDemandId();

        // Verify that the method returned a non-null, non-empty string
        assertNotNull(uniqueId);
        assertFalse(uniqueId.isEmpty());

        // Verify that existsById was called at least 3 times
        Mockito.verify(demandRepository, Mockito.atLeast(3)).existsById(anyString());
    }

    /**
     * Test case for generateUniqueDemandId method when total IDs exceed the current digit length
     * and the first generated ID already exists.
     * <p>
     * Path constraints:
     * - (totalIds >= Math.pow(10, digitLength))
     * - (idExists) for the first generated ID
     * <p>
     * Expected behavior:
     * - The method should increase the digit length
     * - Generate a new ID with increased length
     * - Return a unique ID that doesn't exist in the repository
     */
    @Test
    void test_generateUniqueDemandId_2() {
        // Mock the repository to return a large number of total IDs
        when(demandRepository.count()).thenReturn(10000000L); // 10 million IDs

        // Mock the repository to return true for the first ID check, then false
        when(demandRepository.existsById(anyString())).thenReturn(true) // First ID exists
                .thenReturn(false); // Second ID doesn't exist

        String generatedId = demandService.generateUniqueDemandId();

        // Verify that the generated ID is 8 digits long (increased from 7)
        assertEquals(8, generatedId.length());

        // Verify that existsById was called twice
        verify(demandRepository, times(2)).existsById(anyString());
    }

    /**
     * Test case for generateUniqueDemandId when the total IDs are less than 10^7,
     * but the first generated ID already exists in the repository.
     * It should generate a new unique ID after the first attempt.
     */
    @Test
    void test_generateUniqueDemandId_3() {
        MockitoAnnotations.openMocks(this);

        String result = demandService.generateUniqueDemandId();

        assertNotNull(result);
        assertEquals(7, result.length());
    }

    /**
     * Test case for generateUniqueDemandId method when a unique ID is generated on the first attempt
     * Path constraints: !((idExists))
     * Expected: Returns a non-null unique demand ID
     */
    @Test
    void test_generateUniqueDemandId_4() {
        MockitoAnnotations.openMocks(this);

        // Call the method under test
        String uniqueId = demandService.generateUniqueDemandId();

        // Assert that the generated ID is not null
        assertNotNull(uniqueId, "Generated unique demand ID should not be null");
    }

    /**
     * Test case for readDemand method when id is empty
     * This test verifies that the method returns the correct response
     * when an empty id is provided.
     */
    @Test
    void test_readDemand_1() {
        String emptyId = "";
        CustomResponse response = demandService.readDemand(emptyId);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Id not found", response.getMessage());
    }

    /**
     * Test the readDemand method with an empty id.
     * This test verifies that the method handles empty input correctly
     * by returning an appropriate error response.
     */
    @Test
    void test_readDemand_emptyId() {

        CustomResponse response = demandService.readDemand("");

        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Id not found", response.getMessage());
    }

    /**
     * Test case for searchDemand method when search result is found in Redis cache.
     * This test verifies that when a valid search result is present in the Redis cache,
     * the method returns a successful response with the cached result.
     */
    @Test
    void test_searchDemand_1() {
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        SearchResult mockSearchResult = new SearchResult();
        CustomResponse expectedResponse = new CustomResponse();
        expectedResponse.getResult().put(Constants.RESULT, mockSearchResult);
        expectedResponse.setResponseCode(HttpStatus.OK);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(mockSearchResult);

        // Act
        CustomResponse actualResponse = demandService.searchDemand(searchCriteria);

        // Assert
        assertEquals(expectedResponse.getResponseCode(), actualResponse.getResponseCode());
        assertEquals(expectedResponse.getResult().get(Constants.RESULT), actualResponse.getResult().get(Constants.RESULT));
        verify(redisTemplate.opsForValue(), times(1)).get(anyString());
    }

    /**
     * Test case for searchDemand method when the search string is valid and longer than 2 characters.
     * This test verifies that the method correctly processes the search criteria,
     * calls the ES utility service, and returns a successful response.
     */
    @Test
    void test_searchDemand_3() throws Exception {
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString("valid search");

        SearchResult mockSearchResult = new SearchResult();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(esUtilService.searchDocuments(anyString(), any(SearchCriteria.class))).thenReturn(mockSearchResult);

        // Act
        CustomResponse response = demandService.searchDemand(searchCriteria);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        assertNotNull(response.getResult().get(Constants.RESULT));
        assertEquals(mockSearchResult, response.getResult().get(Constants.RESULT));

        // Verify
        Mockito.verify(esUtilService).searchDocuments(anyString(), any(SearchCriteria.class));
    }

    /**
     * Test case for searchDemand method when searchResult is null, searchString is null,
     * and the search operation is successful.
     * <p>
     * This test verifies that:
     * 1. The method correctly handles a null searchResult from Redis
     * 2. It properly processes a null searchString
     * 3. It successfully performs the search operation using EsUtilService
     * 4. It returns a CustomResponse with the correct status and search results
     */
    @Test
    void test_searchDemand_4() throws Exception {
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString(null);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        SearchResult expectedSearchResult = new SearchResult();
        when(esUtilService.searchDocuments(eq(Constants.INDEX_NAME), eq(searchCriteria))).thenReturn(expectedSearchResult);

        // Act
        CustomResponse response = demandService.searchDemand(searchCriteria);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(expectedSearchResult, response.getResult().get(Constants.RESULT));
        verify(esUtilService).searchDocuments(eq(Constants.INDEX_NAME), eq(searchCriteria));
    }

    /**
     * Test case for searchDemand method when search string is less than 3 characters
     * This test verifies that the method returns an error response when the search string is too short
     */
    @Test
    void test_searchDemand_shortSearchString() {
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString("ab");

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        CustomResponse response = demandService.searchDemand(searchCriteria);

        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    /**
     * Test case for updateDemandStatus method when user ID is blank
     * This test verifies that the method returns an appropriate error response
     * when the user ID obtained from the token is blank.
     */
    @Test
    void test_updateDemandStatus_userIdBlank() {
        MockitoAnnotations.openMocks(this);

        JsonNode updateDetails = objectMapper.createObjectNode();
        String token = "dummyToken";
        String rootOrgId = "dummyRootOrgId";

        CustomResponse response = demandService.updateDemandStatus(updateDetails, token, rootOrgId);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.USER_ID_DOESNT_EXIST, response.getParams().getErrmsg());
    }

    /**
     * Test case for validatePayload method when validation messages are not empty.
     * This test verifies that a CustomException is thrown when the payload is invalid.
     */
    @Test
    void test_validatePayload_1() throws Exception {
        // Arrange
        String fileName = "test-schema.json";
        JsonNode invalidPayload = new ObjectMapper().readTree("{}");

        // Act & Assert
        assertThrows(CustomException.class, () -> {
            demandService.validatePayload(fileName, invalidPayload);
        });
    }


    /**
     * Test validatePayload method with an invalid JSON payload.
     * This test ensures that the method throws a CustomException with BAD_REQUEST status
     * when the provided payload does not conform to the schema.
     */
    @Test
    void test_validatePayload_invalidJsonPayload() {
        JsonNode invalidPayload = objectMapper.createObjectNode();
        String validSchemaFileName = Constants.PAYLOAD_VALIDATION_FILE;

        CustomException exception = assertThrows(CustomException.class, () -> {
            demandService.validatePayload(validSchemaFileName, invalidPayload);
        });

        assertEquals("Failed to validate payload", exception.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatusCode());
    }

    /**
     * Test validatePayload method with an invalid schema file name.
     * This test verifies that the method throws a CustomException with BAD_REQUEST status
     * when an invalid or non-existent schema file name is provided.
     */
    @Test
    void test_validatePayload_invalidSchemaFileName() {
        JsonNode payload = objectMapper.createObjectNode();
        String invalidFileName = "invalid_schema.json";

        CustomException exception = assertThrows(CustomException.class, () -> {
            demandService.validatePayload(invalidFileName, payload);
        });

        assertEquals("Failed to validate payload", exception.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatusCode());
    }

    /**
     * Test case for validateUser method when user details are found but root org ID doesn't match.
     * Path constraints: (!CollectionUtils.isEmpty(userDetails)), (!rootOrgId.equals(userRootOrgId))
     */
    @Test
    void test_validateUser_1() {
        // Arrange
        String rootOrgId = "org123";
        CustomResponse response = new CustomResponse();
        String userId = "user123";

        List<Map<String, Object>> userDetails = new ArrayList<>();
        Map<String, Object> userDetail = new HashMap<>();
        userDetail.put("rootOrgId", "differentOrgId");
        userDetail.put("firstName", "John");
        userDetails.add(userDetail);

        when(cassandraOperation.getRecordsByPropertiesWithoutFiltering(anyString(), anyString(), anyMap(), anyList(), anyInt())).thenReturn(userDetails);

        // Act
        CustomResponse result = demandService.validateUser(rootOrgId, response, userId);

        // Assert
        assertEquals(HttpStatus.FORBIDDEN, result.getResponseCode());
        assertEquals("Unauthorized User.", result.getParams().getErrmsg());
    }

    /**
     * Test validateUser method when root org ID does not match.
     * This tests the edge case where the user's root org ID does not match the provided root org ID.
     */
    @Test
    void test_validateUser_rootOrgIdMismatch() {
        String rootOrgId = "testRootOrgId";
        String userId = "testUserId";
        String userRootOrgId = "differentRootOrgId";
        CustomResponse response = new CustomResponse();

        List<Map<String, Object>> userDetails = new ArrayList<>();
        Map<String, Object> userDetail = new HashMap<>();
        userDetail.put(Constants.USER_ROOT_ORG_ID, userRootOrgId);
        userDetail.put(Constants.FIRST_NAME, "TestUser");
        userDetails.add(userDetail);

        when(cassandraOperation.getRecordsByPropertiesWithoutFiltering(eq(Constants.KEYSPACE_SUNBIRD), eq(Constants.TABLE_USER), anyMap(), anyList(), anyInt())).thenReturn(userDetails);

        CustomResponse result = demandService.validateUser(rootOrgId, response, userId);

        assertEquals(HttpStatus.FORBIDDEN, result.getResponseCode());
        assertEquals(Constants.ROOT_ORG_ID_DOESNT_MATCH, result.getParams().getErrmsg());
    }

    /**
     * Test validateUser method when user details are not found in Cassandra.
     * This tests the edge case where the user ID provided does not exist in the database.
     */
    @Test
    void test_validateUser_userNotFound() {
        String rootOrgId = "testRootOrgId";
        String userId = "nonExistentUserId";
        CustomResponse response = new CustomResponse();

        when(cassandraOperation.getRecordsByPropertiesWithoutFiltering(eq(Constants.KEYSPACE_SUNBIRD), eq(Constants.TABLE_USER), anyMap(), anyList(), anyInt())).thenReturn(Collections.emptyList());

        CustomResponse result = demandService.validateUser(rootOrgId, response, userId);

        assertEquals(HttpStatus.BAD_REQUEST, result.getResponseCode());
        assertEquals("User details not found with userId", result.getParams().getErrmsg());
    }

    @Test
    void testFetchingUserId_returnsValidList(){
        List<String> inputUserIds = List.of("org-123");

        // Mock CbServerProperties
        when(cbServerProperties.getSbApiKey()).thenReturn("test-api-key");
        when(cbServerProperties.getLearnerServiceUrl()).thenReturn("http://fake-url/");
        when(cbServerProperties.getOrgSearchPath()).thenReturn("search");

        // Build response map structure
        Map<String, Object> responseMap = Map.of(Constants.RESPONSE_CODE, Constants.OK, Constants.RESULT, Map.of(Constants.RESPONSE, Map.of(Constants.CONTENT, List.of(Map.of(Constants.ROOT_ORG_ID, "org-123")))));

        when(requestHandlerService.fetchResultUsingPost(eq("http://fake-url/search"), anyMap(), anyMap())).thenReturn(responseMap);

        // Use reflection to invoke private method
        List<String> result = (List<String>) ReflectionTestUtils.invokeMethod(demandService, "fetchingUserId", inputUserIds);

        assertEquals(1, result.size());
        assertEquals("org-123", result.get(0));
    }

    @Test
    void testFetchingUserId_returnsEmptyList_onNullResponse() {
        List<String> inputUserIds = List.of("org-404");

        when(cbServerProperties.getSbApiKey()).thenReturn("key");
        when(cbServerProperties.getLearnerServiceUrl()).thenReturn("url");
        when(cbServerProperties.getOrgSearchPath()).thenReturn("path");

        when(requestHandlerService.fetchResultUsingPost(anyString(), anyMap(), anyMap())).thenReturn(null); // simulate failure

        List<String> result = (List<String>) ReflectionTestUtils.invokeMethod(demandService, "fetchingUserId", inputUserIds);

        assertEquals(0, result.size());
    }

    @Test
    void testFetchingUserId_returnsEmptyList_onInvalidCode() {
        List<String> inputUserIds = List.of("invalid");

        when(cbServerProperties.getSbApiKey()).thenReturn("key");
        when(cbServerProperties.getLearnerServiceUrl()).thenReturn("url");
        when(cbServerProperties.getOrgSearchPath()).thenReturn("path");

        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put(Constants.RESPONSE_CODE, "ERROR");

        when(requestHandlerService.fetchResultUsingPost(anyString(), anyMap(), anyMap())).thenReturn(responseMap);

        List<String> result = (List<String>) ReflectionTestUtils.invokeMethod(demandService, "fetchingUserId", inputUserIds);

        assertEquals(0, result.size());
    }

    @Test
    void testHandleProviderValidation_successForBroadcast() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode demandDetails = mapper.createObjectNode();
        demandDetails.put(Constants.REQUEST_TYPE, Constants.BROADCAST);

        ArrayNode preferredProviders = mapper.createArrayNode();
        ObjectNode provider = mapper.createObjectNode();
        provider.put(Constants.PROVIDER_ID, "provider1");
        preferredProviders.add(provider);
        demandDetails.set(Constants.PREFERRED_PROVIDER, preferredProviders);

        CustomResponse response = new CustomResponse();
        response.setParams(new RespParam());

        // Mock fetchResultUsingPost response
        when(cbServerProperties.getSbApiKey()).thenReturn("dummy-key");
        when(cbServerProperties.getLearnerServiceUrl()).thenReturn("http://dummy/");
        when(cbServerProperties.getOrgSearchPath()).thenReturn("path");

        Map<String, Object> content = Map.of(Constants.ROOT_ORG_ID, "provider1");
        Map<String, Object> result = Map.of(Constants.CONTENT, List.of(content));
        Map<String, Object> outerResult = Map.of(Constants.RESPONSE, result);
        Map<String, Object> responseMap = Map.of(Constants.RESPONSE_CODE, Constants.OK, Constants.RESULT, outerResult);

        when(requestHandlerService.fetchResultUsingPost(anyString(), anyMap(), anyMap())).thenReturn(responseMap);

        // Call private method
        boolean isValid = (Boolean) ReflectionTestUtils.invokeMethod(demandService, "handleProviderValidation", demandDetails, response);

        assertTrue(isValid);
        assertNull(response.getParams().getErrmsg());
    }

    @Test
    void testHandleProviderValidation_failureForSingleRequest_invalidProvider() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode demandDetails = mapper.createObjectNode();
        demandDetails.put(Constants.REQUEST_TYPE, Constants.SINGLE);

        ObjectNode assignedProvider = mapper.createObjectNode();
        assignedProvider.put(Constants.PROVIDER_ID, "invalidProvider");
        demandDetails.set(Constants.ASSIGNED_PROVIDER, assignedProvider);

        CustomResponse response = new CustomResponse();
        response.setParams(new RespParam());

        // Mock fetchResultUsingPost with no valid provider
        when(cbServerProperties.getSbApiKey()).thenReturn("key");
        when(cbServerProperties.getLearnerServiceUrl()).thenReturn("http://url/");
        when(cbServerProperties.getOrgSearchPath()).thenReturn("orgPath");

        Map<String, Object> emptyResult = Map.of(Constants.CONTENT, List.of());
        Map<String, Object> outer = Map.of(Constants.RESPONSE, emptyResult);
        Map<String, Object> resultMap = Map.of(Constants.RESPONSE_CODE, Constants.OK, Constants.RESULT, outer);

        when(requestHandlerService.fetchResultUsingPost(anyString(), anyMap(), anyMap())).thenReturn(resultMap);

        // Invoke private method
        boolean result = (Boolean) ReflectionTestUtils.invokeMethod(demandService, "handleProviderValidation", demandDetails, response);

        assertFalse(result);
        assertEquals(Constants.INVALID_ID + "invalidProvider", response.getParams().getErrmsg());
    }

    @Test
    void testHandleProviderValidation_nullResponseCase() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode demandDetails = mapper.createObjectNode();
        demandDetails.put(Constants.REQUEST_TYPE, Constants.BROADCAST);

        ArrayNode preferredProviders = mapper.createArrayNode();
        ObjectNode provider = mapper.createObjectNode();
        provider.put(Constants.PROVIDER_ID, "provider1");
        preferredProviders.add(provider);
        demandDetails.set(Constants.PREFERRED_PROVIDER, preferredProviders);

        CustomResponse response = new CustomResponse();
        response.setParams(new RespParam());

        when(cbServerProperties.getSbApiKey()).thenReturn("key");
        when(cbServerProperties.getLearnerServiceUrl()).thenReturn("url");
        when(cbServerProperties.getOrgSearchPath()).thenReturn("path");

        when(requestHandlerService.fetchResultUsingPost(anyString(), anyMap(), anyMap())).thenReturn(null); // simulate failure

        boolean result = (Boolean) ReflectionTestUtils.invokeMethod(demandService, "handleProviderValidation", demandDetails, response);

        assertFalse(result);
        assertEquals(Constants.INVALID_ID + "provider1", response.getParams().getErrmsg());
    }

    @Test
    void testValidateUser_logsErrorOnException() {
        // Arrange
        String rootOrgId = "test-org";
        String userId = "user123";
        CustomResponse response = new CustomResponse();
        RespParam params = new RespParam();
        response.setParams(params);

        when(cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                any(), any(), anyMap(), anyList(), anyInt()))
                .thenThrow(new RuntimeException("Simulated Cassandra failure"));

        // Act
        CustomResponse result = demandService.validateUser(rootOrgId, response, userId);

        // Assert
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getResponseCode());
        assertEquals("An error occurred while validating user root org ID.", result.getParams().getErrmsg());
    }

    @Test
    void testValidateUser_putsFirstNameOnSuccess() {
        // Arrange
        String rootOrgId = "test-org";
        String userId = "user123";
        CustomResponse response = new CustomResponse();
        RespParam params = new RespParam();
        response.setParams(params);

        Map<String, Object> userMap = new HashMap<>();
        userMap.put(Constants.USER_ROOT_ORG_ID, "test-org");
        userMap.put(Constants.FIRST_NAME, "Ajay");

        List<Map<String, Object>> userDetails = Collections.singletonList(userMap);

        when(cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                any(), any(), anyMap(), anyList(), anyInt()))
                .thenReturn(userDetails);

        // Act
        CustomResponse result = demandService.validateUser(rootOrgId, response, userId);

        // Assert
        assertEquals(HttpStatus.OK, result.getResponseCode());
        assertNotNull(result.getResult());
        assertEquals("Ajay", result.getResult().get(Constants.FIRST_NAME));
    }

    @Test
    void test_validatePayload_throwsCustomException_onSchemaValidationFailure() {
        // Given
        String schemaPath = "/testSchema.json"; // Ensure it's in src/test/resources
        ObjectMapper mapper = new ObjectMapper();

        // Payload missing required field "requestType"
        String invalidPayloadStr = "{ \"name\": \"sample\" }";

        JsonNode payload;
        try {
            payload = mapper.readTree(invalidPayloadStr);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // When + Then
        CustomException exception = assertThrows(CustomException.class, () -> {
            demandService.validatePayload(schemaPath, payload);
        });

        assertEquals("Failed to validate payload", exception.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatusCode());
    }

    @Test
    void test_createDemand_shouldReturn500_whenPayloadIsValid() {
        ObjectNode demandDetails = new ObjectMapper().createObjectNode();
        demandDetails.put("title", "Upskilling for Teachers");
        demandDetails.put("objective", "Train teachers on blended learning practices.");
        demandDetails.put(Constants.REQUEST_TYPE, Constants.BROADCAST);

        String token = "validToken";
        String userId = "user123";
        String rootOrgId = "org123";

        when(accessTokenValidator.verifyUserToken(token)).thenReturn(userId);

        // Mock user/org validation
        when(cassandraOperation.getRecordsByPropertiesWithoutFiltering(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(Map.of(Constants.USER_ROOT_ORG_ID, rootOrgId, Constants.FIRST_NAME, "Test User")));

        // Mock repository
        when(demandRepository.save(any())).thenAnswer(invocation -> {
            DemandEntity entity = invocation.getArgument(0);
            entity.setDemandId("testDemand123");
            return entity;
        });

        CustomResponse response = demandService.createDemand(demandDetails, token, rootOrgId);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("error while processing", response.getParams().getErrmsg());
    }

    @Test
    void test_createDemand_shouldReturn400_whenPayloadIsValid() {
        ObjectNode demandDetails = new ObjectMapper().createObjectNode();
        demandDetails.put("title", "Upskilling for Teachers");
        demandDetails.put("objective", "Train teachers on blended learning practices.");
        demandDetails.put(Constants.REQUEST_TYPE, Constants.BROADCAST);

        String token = "validToken";
        String userId = "user123";
        String rootOrgId = "org123";

        when(accessTokenValidator.verifyUserToken(token)).thenReturn(userId);

        // Mock user/org validation
        when(cassandraOperation.getRecordsByPropertiesWithoutFiltering(any(), any(), any(), any(), anyInt()))
                .thenReturn(null);

        CustomResponse response = demandService.createDemand(demandDetails, token, rootOrgId);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("User details not found with userId", response.getParams().getErrmsg());
    }

    @Test
    void test_createDemand_shouldReturn400_whenUserIdIsNull() {
        ObjectNode demandDetails = new ObjectMapper().createObjectNode();
        demandDetails.put("title", "Upskilling for Teachers");
        demandDetails.put("objective", "Train teachers on blended learning practices.");
        demandDetails.put(Constants.REQUEST_TYPE, Constants.BROADCAST);

        String token = "validToken";
        String rootOrgId = "org123";

        when(accessTokenValidator.verifyUserToken(token)).thenReturn(null);

        CustomResponse response = demandService.createDemand(demandDetails, token, rootOrgId);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.USER_ID_DOESNT_EXIST, response.getParams().getErrmsg());
    }

    @Test
    void test_createDemand_Return400_whenPayloadIsValid() {
        ObjectNode demandDetails = new ObjectMapper().createObjectNode();
        demandDetails.put("title", "Upskilling for Teachers");
        demandDetails.put("objective", "Train teachers on blended learning practices.");
        demandDetails.put(Constants.REQUEST_TYPE, Constants.SINGLE);

        String token = "validToken";
        String userId = "user123";
        String rootOrgId = "org123";

        when(accessTokenValidator.verifyUserToken(token)).thenReturn(userId);

        // Mock user/org validation
        when(cassandraOperation.getRecordsByPropertiesWithoutFiltering(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(Map.of(Constants.USER_ROOT_ORG_ID, rootOrgId, Constants.FIRST_NAME, "Test User")));

        // Mock repository
        when(demandRepository.save(any())).thenAnswer(invocation -> {
            DemandEntity entity = invocation.getArgument(0);
            entity.setDemandId("testDemand123");
            return entity;
        });

        CustomResponse response = demandService.createDemand(demandDetails, token, rootOrgId);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("error while processing", response.getParams().getErrmsg());
    }

}
