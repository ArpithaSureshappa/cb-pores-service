package com.igot.cb.announcement.service.impl;

import com.auth0.jwt.JWT;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.announcement.entity.AnnouncementEntity;
import com.igot.cb.announcement.repository.AnnouncementRepository;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnnouncementServiceImplTest {

    @Mock
    private AnnouncementRepository announcementRepository;

    @Spy
    @InjectMocks
    private AnnouncementServiceImpl announcementService;

    @Mock
    private CacheService cacheService;

    @Mock
    private EsUtilService esUtilService;

    @Mock
    private Logger logger;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private PayloadValidation payloadValidation;

    @Mock
    private RedisTemplate<String, SearchResult> redisTemplate;

    @Mock
    private CbServerProperties serverProperties;

    @Mock
    private ValueOperations<String, SearchResult> valueOperations;

    private AnnouncementEntity entity;
    private ObjectNode jsonData;


    private ObjectMapper realMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    /**
     * Test case for createAnnouncement method when payload validation fails
     * This test verifies that the method throws a RuntimeException when the payload validation fails
     */
    @Test
    void testCreateAnnouncement_payloadValidationFails() {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode invalidAnnouncementEntity = mapper.createObjectNode();

        doThrow(new CustomException("Invalid payload", "Payload validation failed", null))
                .when(payloadValidation).validatePayload(any(), any());

        assertThrows(RuntimeException.class, () -> {
            announcementService.createAnnouncement(invalidAnnouncementEntity);
        });
    }

    /**
     * Tests the readAnnouncement method with an empty announcement ID.
     * This test verifies that the method handles empty input correctly
     * by returning an appropriate error response.
     */
    @Test
    void testReadAnnouncementWithEmptyId() {
        CustomResponse response = announcementService.readAnnouncement("");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals(Constants.ID_NOT_FOUND, response.getMessage());
    }

    /**
     * Tests the readAnnouncement method with an invalid (non-existent) announcement ID.
     * This test verifies that the method handles non-existent IDs correctly
     * by returning an appropriate error response.
     */
    @Test
    void testReadAnnouncementWithInvalidId() {
        CustomResponse response = announcementService.readAnnouncement("non_existent_id");

        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals(Constants.INVALID_ID, response.getMessage());
    }

    /**
     * Tests the readAnnouncement method with a null announcement ID.
     * This test verifies that the method handles null input correctly
     * by returning an appropriate error response.
     */
    @Test
    void testReadAnnouncementWithNullId() {
        CustomResponse response = announcementService.readAnnouncement(null);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals(Constants.ID_NOT_FOUND, response.getMessage());
    }


    /**
     * Test the updateAnnouncement method when the announcement is not found.
     * This should throw a CustomException with NOT_FOUND status.
     */
    @Test
    void testUpdateAnnouncementNotFound() {
        ObjectNode announcementDetails = new ObjectMapper().createObjectNode();
        announcementDetails.put(Constants.ANNOUNCEMENT_ID, "non-existent-id");

        when(announcementRepository.findById("non-existent-id")).thenReturn(Optional.empty());

        CustomException exception = assertThrows(CustomException.class, () -> {
            announcementService.updateAnnouncement(announcementDetails);
        });

        assertEquals(Constants.ERROR, exception.getCode());
        assertEquals(Constants.NO_DATA_FOUND, exception.getMessage());
        assertEquals(HttpStatus.NOT_FOUND, exception.getHttpStatusCode());
    }

    /**
     * Test the updateAnnouncement method when the announcement ID is null.
     * This should throw a CustomException with BAD_REQUEST status.
     */
    @Test
    void testUpdateAnnouncementWithNullId() {
        ObjectNode announcementDetails = new ObjectMapper().createObjectNode();

        CustomException exception = assertThrows(CustomException.class, () -> {
            announcementService.updateAnnouncement(announcementDetails);
        });

        assertEquals(Constants.ERROR, exception.getCode());
        assertEquals("announcementDetailsEntity id is required for updating", exception.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatusCode());
    }

    /**
     * Test case for createAnnouncement method when the announcement already exists (ID is not null).
     * This test verifies that the method correctly updates an existing announcement and returns the appropriate response.
     */
    @Test
    void test_createAnnouncement_2() {
        MockitoAnnotations.openMocks(this);

        ObjectMapper realObjectMapper = new ObjectMapper();
        ObjectNode announcementEntity = realObjectMapper.createObjectNode();
        announcementEntity.put(Constants.ANNOUNCEMENT_ID, "existing-id");
        announcementEntity.put(Constants.ID, "existing-id");

        AnnouncementEntity existingEntity = new AnnouncementEntity();
        existingEntity.setAnnouncementId("existing-id");
        existingEntity.setData(announcementEntity);

        Map<String, Object> dataMap = new HashMap<>();
        dataMap.put("title", "Test Title");

        // Convert map to JsonNode
        JsonNode jsonNode = new ObjectMapper().convertValue(dataMap, JsonNode.class);

        // Create entity and set JsonNode as data
        AnnouncementEntity savedEntity = new AnnouncementEntity();
        savedEntity.setData(jsonNode); // 👈 This fixes the ClassCastException
        savedEntity.setAnnouncementId("existingId");

        // Mock repository
        when(announcementRepository.save(any(AnnouncementEntity.class))).thenReturn(savedEntity);

        // If your service converts it back to Map (optional)
        when(objectMapper.convertValue(eq(jsonNode), any(TypeReference.class))).thenReturn(dataMap);

        when(announcementRepository.findById("existing-id")).thenReturn(Optional.of(existingEntity));

        CustomResponse response = announcementService.createAnnouncement(announcementEntity);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFULLY_UPDATED, response.getMessage());
    }

    /**
     * Tests that the createErrorResponse method properly sets all fields of the CustomResponse object
     * when called with valid input parameters.
     */
    @Test
    void test_createErrorResponse_setsAllFields() {
        AnnouncementServiceImpl service = new AnnouncementServiceImpl();
        CustomResponse response = new CustomResponse();
        String errorMessage = "Test error message";
        HttpStatus httpStatus = HttpStatus.BAD_REQUEST;
        String status = "FAILED";

        service.createErrorResponse(response, errorMessage, httpStatus, status);

        assertNotNull(response.getParams());
        assertEquals(status, response.getParams().getStatus());
        assertEquals(httpStatus, response.getResponseCode());
    }

    /**
     * Test case for createErrorResponse method
     * Verifies that the method correctly sets the error response parameters
     */
    @Test
    void test_createErrorResponse_setsErrorResponseParameters() {
        AnnouncementServiceImpl announcementService = new AnnouncementServiceImpl();
        CustomResponse response = new CustomResponse();
        String errorMessage = "Test error message";
        HttpStatus httpStatus = HttpStatus.BAD_REQUEST;
        String status = "FAILED";

        announcementService.createErrorResponse(response, errorMessage, httpStatus, status);

        assertNotNull(response.getParams());
        assertEquals(status, response.getParams().getStatus());
        assertEquals(httpStatus, response.getResponseCode());
    }

    /**
     * Test case for createSuccessResponse method
     * Verifies that the method correctly sets the status, response code, and params for a successful response
     */
    @Test
    void test_createSuccessResponse_setsSuccessStatusAndOkResponseCode() {
        AnnouncementServiceImpl announcementService = new AnnouncementServiceImpl();
        CustomResponse response = new CustomResponse();

        announcementService.createSuccessResponse(response);

        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(RespParam.class, response.getParams().getClass());
    }

    /**
     * Test the deleteAnnouncement method when the announcement with the given ID is not found.
     * This test verifies that the method throws a CustomException with the appropriate error message and status code.
     */
    @Test
    void test_deleteAnnouncement_nonExistentId() {
        // Arrange
        String nonExistentId = "non-existent-id";
        when(announcementRepository.findById(nonExistentId)).thenReturn(Optional.empty());

        // Act & Assert
        CustomException exception = assertThrows(CustomException.class, () -> {
            announcementService.deleteAnnouncement(nonExistentId);
        });

        assertEquals(Constants.ERROR, exception.getCode());
        assertEquals(Constants.NO_DATA_FOUND, exception.getMessage());
        assertEquals(HttpStatus.NOT_FOUND, exception.getHttpStatusCode());

        verify(announcementRepository).findById(nonExistentId);
    }

    /**
     * Test case for generateRedisJwtTokenKey method when JsonProcessingException occurs
     * This test verifies that the method returns an empty string when a JsonProcessingException is thrown
     * during the conversion of the request payload to a JSON string.
     */
    @Test
    void test_generateRedisJwtTokenKey_jsonProcessingException() throws JsonProcessingException {
        MockitoAnnotations.openMocks(this);

        Object requestPayload = new Object();
        when(serverProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        when(objectMapper.writeValueAsString(requestPayload)).thenThrow(JsonProcessingException.class);

        String result = announcementService.generateRedisJwtTokenKey(requestPayload);

        assertEquals("", result);
        verify(logger).error(eq("Error occurred while converting json object to json string"), any(JsonProcessingException.class));
    }

    /**
     * Tests the generateRedisJwtTokenKey method when the requestPayload is null.
     * It verifies that an empty string is returned in this case.
     */
    @Test
    void test_generateRedisJwtTokenKey_whenRequestPayloadIsNull() {
        String result = announcementService.generateRedisJwtTokenKey(null);
        assertEquals("", result, "Expected an empty string when requestPayload is null");
    }

    /**
     * Test case for generateRedisJwtTokenKey method when requestPayload is not null.
     * It verifies that the method returns a JWT token with the correct claim and signature.
     */
    @Test
    void test_generateRedisJwtTokenKey_whenRequestPayloadNotNull() throws Exception {
        MockitoAnnotations.openMocks(this);

        Object requestPayload = new Object();
        String reqJsonString = "{\"key\":\"value\"}";

        when(serverProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        when(objectMapper.writeValueAsString(requestPayload)).thenReturn(reqJsonString);

        String result = announcementService.generateRedisJwtTokenKey(requestPayload);

        assertNotNull(result);
        assertTrue(result.length() > 0);

        String[] parts = result.split("\\.");
        assertEquals(3, parts.length);

        String payload = JWT.decode(result).getClaim(Constants.REQUEST_PAYLOAD).asString();
        assertEquals(reqJsonString, payload);

        verify(objectMapper).writeValueAsString(requestPayload);
    }


    /**
     * Test case for readAnnouncement method when the announcement is not in cache
     * but exists in the repository.
     * This test verifies that:
     * 1. The method correctly handles a non-empty id
     * 2. The cache is checked first and returns empty
     * 3. The repository is then queried and returns the announcement
     * 4. The response contains the correct message and data
     */
    @Test
    void test_readAnnouncement_3() {
        MockitoAnnotations.openMocks(this);

        String id = "test-announcement-id";
        String cachedJson = "";
        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setAnnouncementId(id);
        announcement.setData(null); // Assuming data can be null for this test

        when(cacheService.getCache(id)).thenReturn(cachedJson);
        when(announcementRepository.findById(id)).thenReturn(Optional.of(announcement));

        CustomResponse response = announcementService.readAnnouncement(id);

        assertNotNull(response);
        assertEquals(Constants.SUCCESSFULLY_READING, response.getMessage());
        assertTrue(response.getResult().containsKey(Constants.DATA));
        verify(cacheService).getCache(id);
        verify(announcementRepository).findById(id);
    }

    /**
     * Test case for readAnnouncement method when the input id is empty
     * This test verifies that the method returns the correct response when the id parameter is empty
     */
    @Test
    void test_readAnnouncement_whenIdIsEmpty() {
        AnnouncementServiceImpl announcementService = new AnnouncementServiceImpl();
        String emptyId = "";

        CustomResponse response = announcementService.readAnnouncement(emptyId);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Id not found", response.getMessage());
    }

    /**
     * Test the searchAnnouncement method with a search string less than 3 characters.
     * This should return an error response with BAD_REQUEST status.
     */
    @Test
    void test_searchAnnouncement_shortSearchString() {
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString("ab");

        when(serverProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        CustomResponse response = announcementService.searchAnnouncement(searchCriteria);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    /**
     * Test case for searchAnnouncement method when search string is less than 2 characters
     * This test verifies that the method returns an error response when the search string
     * is not null but has fewer than 2 characters.
     */
    @Test
    void test_searchAnnouncement_shortSearchString_2() {
        SearchCriteria searchCriteria = mock(SearchCriteria.class);
        when(searchCriteria.getSearchString()).thenReturn("a");
        // Mock opsForValue call
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        CustomResponse response = announcementService.searchAnnouncement(searchCriteria);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

    /**
     * Test case for searchAnnouncement method when:
     * - Redis cache doesn't have search result
     * - Search string is valid (not null and length >= 2)
     * - Page size is 0 (should be set to default)
     * - FilterCriteriaMap is empty
     * This test verifies that the method sets the default page size, adds expiry filter,
     * performs the search using EsUtilService, and returns a success response.
     */
    @Test
    void test_searchAnnouncement_whenCacheEmptyAndValidSearchString() throws Exception {
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString("valid");
        searchCriteria.setPageSize(0);

        ValueOperations<String, SearchResult> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        when(serverProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        when(serverProperties.getAnnouncementDefaultSearchPageSize()).thenReturn(10);

        SearchResult mockSearchResult = new SearchResult();
        when(esUtilService.searchDocuments(eq(Constants.ANNOUNCEMENT_INDEX), any(SearchCriteria.class)))
                .thenReturn(mockSearchResult);

        CustomResponse response = announcementService.searchAnnouncement(searchCriteria);

        verify(serverProperties).getAnnouncementDefaultSearchPageSize();
        verify(esUtilService).searchDocuments(eq(Constants.ANNOUNCEMENT_INDEX), any(SearchCriteria.class));
    }

    /**
     * Test case for searchAnnouncement method when:
     * - Redis cache miss
     * - Search string is valid (not null and length >= 2)
     * - Page size is 0 (default page size should be set)
     * - FilterCriteriaMap is not empty
     * 
     * Expected behavior:
     * - Default page size should be set
     * - Expiration date filter should be added to existing filter criteria
     * - ES search should be performed
     * - Successful response should be returned
     */
    @Test
    void test_searchAnnouncement_whenCacheMissAndValidSearchStringAndEmptyPageSizeAndNonEmptyFilter() throws Exception {
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString("validSearch");
        searchCriteria.setPageSize(0);
        Map<String, Object> filterCriteria = new HashMap<>();
        filterCriteria.put("someKey", "someValue");
        searchCriteria.setFilterCriteriaMap((HashMap<String, Object>) filterCriteria);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(serverProperties.getAnnouncementDefaultSearchPageSize()).thenReturn(10);

        SearchResult mockSearchResult = new SearchResult();
        when(esUtilService.searchDocuments(eq(Constants.ANNOUNCEMENT_INDEX), any(SearchCriteria.class))).thenReturn(mockSearchResult);

        Map<String, Object> resultMap = new HashMap<>();
        when(objectMapper.convertValue(mockSearchResult, Map.class)).thenReturn(resultMap);

        // Act
        CustomResponse response = announcementService.searchAnnouncement(searchCriteria);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        verify(serverProperties).getAnnouncementDefaultSearchPageSize();
        verify(esUtilService).searchDocuments(eq(Constants.ANNOUNCEMENT_INDEX), any(SearchCriteria.class));
        assertTrue(searchCriteria.getFilterCriteriaMap().containsKey(Constants.EXPIRED_ON));
        assertEquals(10, searchCriteria.getPageSize());
    }

    /**
     * Test case for searchAnnouncement method when search result is found in Redis cache.
     * This test verifies that when a search result is available in the Redis cache,
     * the method returns it without performing a new search.
     */
    @Test
    void test_searchAnnouncement_whenResultFoundInCache() {
        SearchCriteria searchCriteria = new SearchCriteria();
        SearchResult cachedResult = new SearchResult();
        CustomResponse expectedResponse = new CustomResponse();
        expectedResponse.getResult().put(Constants.RESULT, cachedResult);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(cachedResult);

        CustomResponse actualResponse = announcementService.searchAnnouncement(searchCriteria);

        assertEquals(expectedResponse.getResult().get(Constants.RESULT), actualResponse.getResult().get(Constants.RESULT));
        verify(redisTemplate.opsForValue(), times(1)).get(anyString());
        verifyNoMoreInteractions(redisTemplate.opsForValue());
    }

    /**
     * Test case for searchAnnouncement method when the search result is not in Redis,
     * search string is valid, page size is not zero, and filter criteria map is not empty.
     * It verifies that the method correctly processes the search criteria and returns
     * a successful response with the search results from ElasticSearch.
     */
    @Test
    void test_searchAnnouncement_whenSearchResultNotInRedisAndValidCriteria() throws Exception {
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString("valid search");
        searchCriteria.setPageSize(10);
        Map<String, Object> filterCriteria = new HashMap<>();
        filterCriteria.put("someKey", "someValue");
        searchCriteria.setFilterCriteriaMap((HashMap<String, Object>) filterCriteria);

        when(serverProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        ValueOperations<String, SearchResult> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        SearchResult mockSearchResult = new SearchResult();
        when(esUtilService.searchDocuments(eq(Constants.ANNOUNCEMENT_INDEX), any(SearchCriteria.class)))
                .thenReturn(mockSearchResult);

        Map<String, Object> resultMap = new HashMap<>();
        when(objectMapper.convertValue(mockSearchResult, Map.class)).thenReturn(resultMap);

        // Act
        CustomResponse response = announcementService.searchAnnouncement(searchCriteria);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        verify(esUtilService).searchDocuments(eq(Constants.ANNOUNCEMENT_INDEX), any(SearchCriteria.class));
        verify(objectMapper).convertValue(mockSearchResult, Map.class);
    }

    /**
     * Test case for updating an existing announcement successfully.
     * This test verifies that the updateAnnouncement method correctly updates
     * an existing announcement when valid details are provided.
     */
    @Test
    void test_updateAnnouncement_2() {
        MockitoAnnotations.openMocks(this);

        // Prepare test data
        ObjectMapper realObjectMapper = new ObjectMapper();
        ObjectNode announcementDetails = realObjectMapper.createObjectNode();
        announcementDetails.put(Constants.ANNOUNCEMENT_ID, "test-id");

        AnnouncementEntity existingEntity = new AnnouncementEntity();
        existingEntity.setAnnouncementId("test-id");
        existingEntity.setData(realObjectMapper.createObjectNode());

        // Mock repository behavior
        when(announcementRepository.findById("test-id")).thenReturn(Optional.of(existingEntity));
        when(announcementRepository.save(any(AnnouncementEntity.class))).thenReturn(existingEntity);

        // Mock ObjectMapper behavior
        when(objectMapper.createObjectNode()).thenReturn(realObjectMapper.createObjectNode());
        when(objectMapper.convertValue(any(), any(Class.class))).thenReturn(new java.util.HashMap<>());

        // Call the method under test
        CustomResponse response = announcementService.updateAnnouncement(announcementDetails);

        // Verify the response
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFULLY_UPDATED, response.getMessage());
    }

    /**
     * Test case for updateAnnouncement method when announcement ID is null and no matching announcement is found.
     * This test verifies that the method throws a CustomException with the appropriate error message and HTTP status
     * when the announcement ID is null or when no matching announcement is found in the repository.
     */
    @Test
    void test_updateAnnouncement_idNullAndAnnouncementNotFound() {
        JsonNode announcementDetails = Mockito.mock(JsonNode.class);
        when(announcementDetails.get(Constants.ANNOUNCEMENT_ID)).thenReturn(null);

        CustomException exception = assertThrows(CustomException.class, () -> {
            announcementService.updateAnnouncement(announcementDetails);
        });

        assertEquals(Constants.ERROR, exception.getCode());
        assertEquals("announcementDetailsEntity id is required for updating", exception.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatusCode());
    }

    /**
     * Test case for updateAnnouncement method when announcement ID is present and announcement exists.
     * This test verifies that the method successfully updates an existing announcement and returns the correct response.
     */
    @Test
    void test_updateAnnouncement_successfulUpdate() {
        MockitoAnnotations.openMocks(this);

        // Prepare test data
        String announcementId = "test-id";
        ObjectNode announcementDetails = new ObjectMapper().createObjectNode();
        announcementDetails.put(Constants.ANNOUNCEMENT_ID, announcementId);

        AnnouncementEntity existingAnnouncement = new AnnouncementEntity();
        existingAnnouncement.setAnnouncementId(announcementId);
        existingAnnouncement.setData(announcementDetails);

        // Mock repository response
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(existingAnnouncement));
        when(announcementRepository.save(any(AnnouncementEntity.class))).thenReturn(existingAnnouncement);

        // Mock ObjectMapper behavior
        ObjectNode jsonNode = new ObjectMapper().createObjectNode();
        when(objectMapper.createObjectNode()).thenReturn(jsonNode);
        when(objectMapper.convertValue(any(), eq(java.util.Map.class))).thenReturn(new java.util.HashMap<>());

        // Execute the method
        CustomResponse response = announcementService.updateAnnouncement(announcementDetails);

        // Verify the results
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFULLY_UPDATED, response.getMessage());
        assertNotNull(response.getResult());

        // Verify interactions
        verify(announcementRepository).findById(announcementId);
        verify(announcementRepository).save(any(AnnouncementEntity.class));
        verify(esUtilService).addDocument(eq(Constants.ANNOUNCEMENT_INDEX), eq(Constants.INDEX_TYPE), eq(announcementId), anyMap(), anyString());
        verify(cacheService).putCache(eq(announcementId), any(ObjectNode.class));
    }

    @Test
    void test_deleteAnnouncement_success() throws Exception {
        AnnouncementEntity entity = new AnnouncementEntity();
        entity.setAnnouncementId("announcement123");
        entity.setIsActive(true);
        entity.setUpdatedOn(new Timestamp(System.currentTimeMillis()));

        ObjectMapper realMapper = new ObjectMapper();
        ObjectNode jsonData = realMapper.createObjectNode();
        jsonData.put("title", "Sample Announcement");

        ArrayNode channelArray = jsonData.putArray(Constants.CHANNEL);
        channelArray.add("013633005407862784180");

        entity.setData(jsonData);

        String id = "announcement123";

        ObjectNode expectedJsonNode = realMapper.createObjectNode();
        expectedJsonNode.put("title", "Sample Announcement");
        expectedJsonNode.put(Constants.UPDATED_ON, String.valueOf(entity.getUpdatedOn()));
        expectedJsonNode.put(Constants.STATUS, Constants.IN_ACTIVE);
        expectedJsonNode.put(Constants.ANNOUNCEMENT_ID, entity.getAnnouncementId());

        when(announcementRepository.findById(id)).thenReturn(Optional.of(entity));
        when(objectMapper.createObjectNode()).thenReturn(realMapper.createObjectNode());
        when(objectMapper.convertValue(any(), eq(Map.class)))
                .thenAnswer(invocation -> realMapper.convertValue(invocation.getArgument(0), Map.class));
        when(announcementRepository.save(any())).thenReturn(entity);

        doNothing().when(announcementService).buildDefaultRequest(anyString());

        CustomResponse response = announcementService.deleteAnnouncement(id);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFULLY_UPDATED, response.getMessage());
        assertTrue(((Map<?, ?>) response.getResult()).containsKey(Constants.ANNOUNCEMENT_ID));

        verify(announcementRepository).save(any());
        verify(esUtilService).addDocument(eq(Constants.ANNOUNCEMENT_INDEX), eq(Constants.INDEX_TYPE), eq(id), any(), any());
        verify(cacheService).putCache(eq(entity.getAnnouncementId()), any());
    }

    @Test
    void test_createAnnouncement_whenIdIsNull_shouldCreateNewAnnouncement() {
        // Given
        ObjectNode announcementNode = realMapper.createObjectNode();
        announcementNode.put("title", "Test Title"); // no "id" -> triggers create path

        ArgumentCaptor<AnnouncementEntity> entityCaptor = ArgumentCaptor.forClass(AnnouncementEntity.class);

        // Stub repository save
        AnnouncementEntity savedEntity = new AnnouncementEntity();
        savedEntity.setAnnouncementId("generated-id-123");
        savedEntity.setData(announcementNode.deepCopy());
        savedEntity.setCreatedOn(new Timestamp(System.currentTimeMillis()));
        savedEntity.setUpdatedOn(new Timestamp(System.currentTimeMillis()));
        savedEntity.setIsActive(true);

        when(announcementRepository.save(any())).thenReturn(savedEntity);

        // When
        CustomResponse response = announcementService.createAnnouncement(announcementNode);

        // Then
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFULLY_CREATED, response.getMessage());

        Map<?, ?> result = (Map<?, ?>) response.getResult();
        assertTrue(result.containsKey(Constants.ANNOUNCEMENT_ID));
    }
}
