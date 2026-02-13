package com.igot.cb.competencies.area.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.competencies.area.entity.CompetencyAreaEntity;
import com.igot.cb.competencies.area.repository.CompetencyAreaRepository;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.dto.CustomResponse;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.pores.util.FileProcessService;
import com.igot.cb.pores.util.PayloadValidation;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompetencyAreaServiceImplTest {

    @Mock
    private AccessTokenValidator accessTokenValidator;

    @Mock
    private CacheService cacheService;

    @Mock
    private CbServerProperties cbServerProperties;

    @Mock
    private CompetencyAreaRepository competencyAreaRepository;

    @InjectMocks
    private CompetencyAreaServiceImpl competencyAreaService;

    @Mock
    private EsUtilService esUtilService;

    @Mock
    private FileProcessService fileProcessService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private PayloadValidation payloadValidation;

    @Mock
    private RedisTemplate<String, SearchResult> redisTemplate;

    @Mock
    private ValueOperations<String, SearchResult> valueOperations;

    @Mock
    private MultipartFile multipartFile;
    private static final String TEST_ID = "COMAREA-000001";

    /**
     * Test case for createCompArea method when index is present, data is not empty, and user is unauthorized.
     * Path constraints:
     * - esUtilService.isIndexPresent(Constants.COMP_AREA_INDEX_NAME) returns true
     * - !dataFetched.getData().isEmpty() && !dataFetched.getData().isNull() is true
     * - StringUtils.isBlank(userId) || userId.equalsIgnoreCase(Constants.UNAUTHORIZED) is true
     */
    @Test
    void test_createCompArea_1() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper(); // Initialize ObjectMapper
        MockitoAnnotations.openMocks(this);

        // Arrange
        JsonNode competencyArea = objectMapper.createObjectNode();
        String token = "invalid_token";

        when(esUtilService.isIndexPresent(Constants.COMP_AREA_INDEX_NAME)).thenReturn(true);

        SearchResult mockSearchResult = new SearchResult();
        mockSearchResult.setData(objectMapper.createArrayNode().add(objectMapper.createObjectNode()));
        when(esUtilService.searchDocuments(eq(Constants.COMP_AREA_INDEX_NAME), any())).thenReturn(mockSearchResult);

        when(accessTokenValidator.verifyUserToken(token)).thenReturn(Constants.UNAUTHORIZED);

        // Act
        CustomResponse response = competencyAreaService.createCompArea(competencyArea, token);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.USER_ID_DOESNT_EXIST, response.getParams().getErrmsg());
    }

    /**
     * Test case for createCompArea method when user token is invalid.
     * This test verifies that the method returns a BAD_REQUEST response
     * when the user token is invalid or blank.
     */
    @Test
    void test_createCompArea_invalidUserToken() {
        JsonNode competencyArea = objectMapper.createObjectNode();
        String invalidToken = "invalid_token";

        when(accessTokenValidator.verifyUserToken(invalidToken)).thenReturn(Constants.UNAUTHORIZED);

        CustomResponse response = competencyAreaService.createCompArea(competencyArea, invalidToken);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.USER_ID_DOESNT_EXIST, response.getParams().getErrmsg());
    }

    /**
     * Test case for createErrorResponse method
     * Verifies that the method correctly sets the error response parameters
     */
    @Test
    void test_createErrorResponse_1() {
        CompetencyAreaServiceImpl service = new CompetencyAreaServiceImpl();
        CustomResponse response = new CustomResponse();
        String errorMessage = "Test error message";
        HttpStatus httpStatus = HttpStatus.BAD_REQUEST;
        String status = "FAILED";

        service.createErrorResponse(response, errorMessage, httpStatus, status);

        assertEquals(status, response.getParams().getStatus());
        assertEquals(httpStatus, response.getResponseCode());
    }

    /**
     * Tests that createErrorResponse sets all fields correctly when given valid inputs.
     */
    @Test
    void test_createErrorResponse_setsAllFieldsCorrectly() {
        CompetencyAreaServiceImpl service = new CompetencyAreaServiceImpl();
        CustomResponse response = new CustomResponse();
        String errorMessage = "Test error";
        HttpStatus httpStatus = HttpStatus.BAD_REQUEST;
        String status = "FAILED";

        service.createErrorResponse(response, errorMessage, httpStatus, status);

        assertNotNull(response.getParams());
        assertEquals(status, response.getParams().getStatus());
        assertEquals(httpStatus, response.getResponseCode());
    }

    /**
     * Test case for createSuccessResponse method
     * This test verifies that the method correctly sets the success status, response code, and initializes RespParam
     */
    @Test
    void test_createSuccessResponse_1() {
        CompetencyAreaServiceImpl service = new CompetencyAreaServiceImpl();
        CustomResponse response = new CustomResponse();

        service.createSuccessResponse(response);

        assertNotNull(response.getParams());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    /**
     * Test case for deleteCompetencyArea method when the competency area exists and is active.
     * It verifies that the method successfully deactivates the competency area, updates it in the repository,
     * updates the Elasticsearch index, and removes it from the cache.
     */
    @Test
    void test_deleteCompetencyArea_1() {
        MockitoAnnotations.openMocks(this);

        String id = "COMAREA-000001";
        CompetencyAreaEntity mockEntity = new CompetencyAreaEntity();
        mockEntity.setId(id);
        mockEntity.setIsActive(true);
        mockEntity.setData(mock(com.fasterxml.jackson.databind.node.ObjectNode.class));

        when(competencyAreaRepository.findByIdAndIsActive(id, true)).thenReturn(Optional.of(mockEntity));
        when(competencyAreaRepository.save(any(CompetencyAreaEntity.class))).thenReturn(mockEntity);

        CustomResponse response = competencyAreaService.deleteCompetencyArea(id);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.DELETED_SUCCESSFULLY, response.getMessage());

        verify(competencyAreaRepository).save(any(CompetencyAreaEntity.class));
        verify(esUtilService).addDocument(eq(Constants.COMP_AREA_INDEX_NAME), eq(Constants.INDEX_TYPE), eq(id), any(), any());
        verify(cacheService).deleteCache(id);
    }

    /**
     * Test case for deleteCompetencyArea when the competency area is not found.
     * This test verifies that the method returns a BAD_REQUEST response when
     * the competency area with the given ID does not exist or is not active.
     */
    @Test
    void test_deleteCompetencyArea_2() {
        // Arrange
        String id = "nonexistent-id";
        when(competencyAreaRepository.findByIdAndIsActive(id, true)).thenReturn(Optional.empty());

        // Act
        CustomResponse response = competencyAreaService.deleteCompetencyArea(id);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("CompetencyAreaServiceImpl::deleteCompetencyArea:No data found for this id", response.getMessage());
        verify(competencyAreaRepository).findByIdAndIsActive(id, true);
    }

    /**
     * Test case for generateRedisJwtTokenKey method when requestPayload is not null.
     * It verifies that the method returns a non-null JWT token.
     */
    @Test
    void test_generateRedisJwtTokenKey_1() {
        MockitoAnnotations.openMocks(this);

        Object requestPayload = new Object();

        try {
            when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            String result = competencyAreaService.generateRedisJwtTokenKey(requestPayload);

            assertNotNull(result);
        } catch (Exception e) {
            // Handle potential exceptions
        }
    }

    /**
     * Testcase 2 for public String generateRedisJwtTokenKey(Object requestPayload)
     * Tests the scenario where the requestPayload is null
     * Expected result: An empty string should be returned
     */
    @Test
    void test_generateRedisJwtTokenKey_2() {
        CompetencyAreaServiceImpl competencyAreaService = new CompetencyAreaServiceImpl();
        String result = competencyAreaService.generateRedisJwtTokenKey(null);
        assertEquals("", result);
    }

    /**
     * Tests that the generateRedisJwtTokenKey method returns an empty string when given a null input.
     * This tests the explicit null check in the method implementation.
     */
    @Test
    void test_generateRedisJwtTokenKey_nullInput() {
        String result = competencyAreaService.generateRedisJwtTokenKey(null);
        assertEquals("", result);
    }

    /**
    * Testcase 1 for @Override void loadCompetencyArea(MultipartFile file, String token)
    * Path constraints: (esUtilService.isIndexPresent(Constants.COMP_AREA_INDEX_NAME)), (!dataFetched.getData().isEmpty() && !dataFetched.getData().isNull()), (!StringUtils.isBlank(userId)), (node.has(Constants.TITLE)), (!eachCompArea.isNull() && eachCompArea.has(Constants.COMPETENCY_AREA_TYPE)), (!eachCompArea.get(
                 Constants.COMPETENCY_AREA_TYPE).asText().isEmpty()), (!titles.containsKey(eachCompArea.get(Constants.COMPETENCY_AREA_TYPE).asText().toLowerCase()))
    */
    @Test
    void test_loadCompetencyArea_1() throws Exception {
        // Arrange
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "test data".getBytes());
        String token = "validToken";
        String userId = "testUser";

        when(accessTokenValidator.verifyUserToken(token)).thenReturn(userId);
        when(esUtilService.isIndexPresent(Constants.COMP_AREA_INDEX_NAME)).thenReturn(true);

        SearchResult searchResult = new SearchResult();
        JsonNode dataNode = Mockito.mock(JsonNode.class);
        when(dataNode.isEmpty()).thenReturn(false);
        when(dataNode.isNull()).thenReturn(false);
        searchResult.setData(dataNode);
        when(esUtilService.searchDocuments(eq(Constants.COMP_AREA_INDEX_NAME), any(SearchCriteria.class))).thenReturn(searchResult);

        List<Map<String, String>> processedData = new ArrayList<>();
        Map<String, String> dataMap = new HashMap<>();
        dataMap.put(Constants.COMPETENCY_AREA_TYPE, "New Competency Area");
        processedData.add(dataMap);
        when(fileProcessService.processExcelFile(file)).thenReturn(processedData);

        JsonNode jsonNode = Mockito.mock(JsonNode.class);
        when(objectMapper.valueToTree(processedData)).thenReturn(jsonNode);
        when(competencyAreaRepository.count()).thenReturn(0L);

        // Act
        competencyAreaService.loadCompetencyArea(file, token);

        // Assert
        // Add assertions here to verify the expected behavior
        // For example, you can verify that certain methods were called with expected arguments
        Mockito.verify(esUtilService).isIndexPresent(Constants.COMP_AREA_INDEX_NAME);
        Mockito.verify(esUtilService).searchDocuments(eq(Constants.COMP_AREA_INDEX_NAME), any(SearchCriteria.class));
        Mockito.verify(fileProcessService).processExcelFile(file);
        Mockito.verify(competencyAreaRepository).count();
    }

    /**
     * Test case for loadCompetencyArea method when the index is not present,
     * userId is not blank, and a new competency area is added.
     *
     * This test verifies that the method correctly processes the input file,
     * validates the data, and saves new competency areas when the conditions are met.
     */
    @Test
    void test_loadCompetencyArea_3() {
        // Arrange
        MultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]);
        String token = "testToken";
        String userId = "testUser";

        when(accessTokenValidator.verifyUserToken(token)).thenReturn(userId);
        when(esUtilService.isIndexPresent(Constants.COMP_AREA_INDEX_NAME)).thenReturn(false);
        when(competencyAreaRepository.count()).thenReturn(0L);

        List<Map<String, String>> processedData = new ArrayList<>();
        Map<String, String> dataMap = new HashMap<>();
        dataMap.put(Constants.COMPETENCY_AREA_TYPE, "New Competency Area");
        processedData.add(dataMap);
        when(fileProcessService.processExcelFile(file)).thenReturn(processedData);

        JsonNode jsonNode = mock(JsonNode.class);
        when(objectMapper.valueToTree(processedData)).thenReturn(jsonNode);

        when(objectMapper.createObjectNode()).thenReturn(mock(ObjectNode.class));

        // Act
        competencyAreaService.loadCompetencyArea(file, token);

        // Assert
        verify(esUtilService).isIndexPresent(Constants.COMP_AREA_INDEX_NAME);
        verify(fileProcessService).processExcelFile(file);
        verify(competencyAreaRepository).count();
        verify(competencyAreaRepository).saveAll(any());
    }

    /**
     * Test case for loadCompetencyArea method when an exception occurs while fetching data from Elasticsearch.
     * This test verifies that the method throws a CustomException with the correct status code when there's an error in ES.
     */
    @Test
    void test_loadCompetencyArea_esUtilServiceException() throws Exception {
        when(esUtilService.isIndexPresent(any())).thenReturn(true);
        when(esUtilService.searchDocuments(any(), any())).thenThrow(new RuntimeException("ES error"));

        assertThrows(CustomException.class, () -> {
            competencyAreaService.loadCompetencyArea(any(MultipartFile.class), "valid_token");
        }, "Expected CustomException to be thrown");

        try {
            competencyAreaService.loadCompetencyArea(any(MultipartFile.class), "valid_token");
        } catch (CustomException e) {
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getHttpStatusCode());
            assertEquals("ES error", e.getMessage());
        }
    }

    /**
     * Test case for readCompArea method when cache is present
     * This test verifies that the method returns the correct response when the cached data is available
     */
    @Test
    void test_readCompArea_2() {
        // Arrange
        String id = "testId";
        String cachedJson = "{\"key\":\"value\"}";
        when(cacheService.getCache(id)).thenReturn(cachedJson);

        // Act
        CustomResponse response = competencyAreaService.readCompArea(id);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals("successfully read", response.getMessage());
        verify(cacheService).getCache(id);
    }

    /**
     * Test case for readCompArea method when the id is valid and the data is found in the database.
     * This test verifies that the method returns the correct response when the competency area
     * is not in the cache but is present in the database.
     */
    @Test
    void test_readCompArea_3() {
        // Arrange
        String id = "COMAREA-000001";
        CompetencyAreaEntity entity = new CompetencyAreaEntity();
        entity.setId(id);
        entity.setData(objectMapper.createObjectNode());

        when(cacheService.getCache(id)).thenReturn(null);
        when(competencyAreaRepository.findByIdAndIsActive(id, true)).thenReturn(Optional.of(entity));

        // Act
        CustomResponse response = competencyAreaService.readCompArea(id);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFULLY_READING, response.getMessage());
        assertTrue(response.getResult().containsKey(Constants.RESULT));

        // Verify
        verify(cacheService).getCache(id);
        verify(competencyAreaRepository).findByIdAndIsActive(id, true);
        verify(cacheService).putCache(eq(id), any());
    }

    /**
     * Test case for readCompArea method when the input id is empty
     * This test verifies that the method returns a CustomResponse with BAD_REQUEST status
     * and an appropriate error message when the input id is empty.
     */
    @Test
    void test_readCompArea_emptyId() {
        CompetencyAreaServiceImpl service = new CompetencyAreaServiceImpl();
        String emptyId = "";

        CustomResponse response = service.readCompArea(emptyId);

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Id not found", response.getMessage());
    }

    /**
     * Tests the behavior of readCompArea method when an empty ID is provided.
     * This scenario is explicitly handled in the method, returning a BAD_REQUEST response.
     */
    @Test
    void test_readCompArea_emptyId_2() {
        CompetencyAreaServiceImpl service = new CompetencyAreaServiceImpl();
        CustomResponse response = service.readCompArea("");

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Id not found", response.getMessage());
    }

    /**
     * Test case for searchCompArea method when the search result is not in Redis cache
     * and the search string is valid (length >= 2).
     * It verifies that the method correctly searches documents using EsUtilService
     * and returns a successful response.
     */
    @Test
    void test_searchCompArea_3() throws Exception {
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString("validSearchString");

        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        ValueOperations<String, SearchResult> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any())).thenReturn(null);

        SearchResult mockSearchResult = new SearchResult();
        when(esUtilService.searchDocuments(Constants.COMP_AREA_INDEX_NAME, searchCriteria)).thenReturn(mockSearchResult);

        // Act
        CustomResponse response = competencyAreaService.searchCompArea(searchCriteria);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        assertEquals(mockSearchResult, response.getResult().get(Constants.RESULT));
    }

    /**
     * Test case for searchCompArea method when search result is found in Redis cache.
     * This test verifies that when a cached search result is available in Redis,
     * the method returns it without querying Elasticsearch.
     */
    @Test
    void test_searchCompArea_whenResultInCache() {
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        SearchResult cachedResult = new SearchResult();
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(cachedResult);

        // Act
        CustomResponse response = competencyAreaService.searchCompArea(searchCriteria);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(cachedResult, response.getResult().get(Constants.RESULT));
        verify(redisTemplate.opsForValue(), times(1)).get(anyString());
        verifyNoMoreInteractions(redisTemplate);
    }

    /**
     * Test case for updateCompArea method when the competency area exists and is successfully updated.
     * Path constraints:
     * - updatedCompArea.has(Constants.ID) && !updatedCompArea.get(Constants.ID).isNull()
     * - compArea.isPresent()
     */
    @Test
    void test_updateCompArea_1() {
        // Arrange
        ObjectMapper realObjectMapper = new ObjectMapper();
        ObjectNode updatedCompArea = realObjectMapper.createObjectNode();
        updatedCompArea.put(Constants.ID, "COMAREA-000001");
        updatedCompArea.put("title", "Updated Competency Area");

        CompetencyAreaEntity existingEntity = new CompetencyAreaEntity();
        existingEntity.setId("COMAREA-000001");
        existingEntity.setData(realObjectMapper.createObjectNode());
        existingEntity.setIsActive(true);

        when(competencyAreaRepository.findById("COMAREA-000001")).thenReturn(Optional.of(existingEntity));
        when(competencyAreaRepository.save(any(CompetencyAreaEntity.class))).thenReturn(existingEntity);
        when(objectMapper.createObjectNode()).thenReturn(realObjectMapper.createObjectNode());
        when(objectMapper.convertValue(any(), any(Class.class))).thenReturn(new java.util.HashMap<>());

        // Act
        CustomResponse response = competencyAreaService.updateCompArea(updatedCompArea);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFULLY_UPDATED, response.getMessage());
    }

    @Test
    void testDeleteCompetencyArea_InternalServerError() {
        // given
        String testId = "area123";
        when(competencyAreaRepository.findByIdAndIsActive(testId, true))
                .thenThrow(new RuntimeException("DB access failed"));

        // when
        CustomResponse response = competencyAreaService.deleteCompetencyArea(testId);

        // then
        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals("DB access failed", response.getMessage());
    }

    @Test
    void testUpdateCompArea_NoDataFoundForId() {
        // given
        String testId = "comp123";
        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put(Constants.ID, testId);

        when(competencyAreaRepository.findById(testId)).thenReturn(Optional.empty());

        // when
        CustomResponse response = competencyAreaService.updateCompArea(input);

        // then
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("No data found for this id", response.getMessage());
    }

    @Test
    void testUpdateCompArea_IdIsMissing() {
        // given
        ObjectNode input = new ObjectMapper().createObjectNode(); // no ID

        // when
        CustomResponse response = competencyAreaService.updateCompArea(input);

        // then
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Id is missing", response.getMessage());
    }

    @Test
    void testUpdateCompArea_ThrowsException() {
        // given
        String testId = "comp123";
        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put(Constants.ID, testId);

        ObjectNode existingData = new ObjectMapper().createObjectNode();
        existingData.put("field1", "value1");

        CompetencyAreaEntity entity = new CompetencyAreaEntity();
        entity.setId(testId);
        entity.setData(existingData);
        entity.setUpdatedOn(new Timestamp(System.currentTimeMillis()));

        when(competencyAreaRepository.findById(testId)).thenReturn(Optional.of(entity));
        when(competencyAreaRepository.save(any())).thenThrow(new RuntimeException("DB write failed"));

        // when + then
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            competencyAreaService.updateCompArea(input);
        });

        assertEquals("DB write failed", exception.getMessage());
    }

    @Test
    void testCreateCompArea_ErrorWhileProcessing() throws Exception {
        // Arrange
        ObjectMapper objectMapper1 = new ObjectMapper();
        ObjectNode input = objectMapper1.createObjectNode();
        input.put(Constants.TITLE, "Innovation");
        String token = "valid-token";

        SearchResult mockSearchResult = new SearchResult();
        mockSearchResult.setData(objectMapper1.createArrayNode());

        when(accessTokenValidator.verifyUserToken(token)).thenReturn("user-123");
        when(esUtilService.isIndexPresent(Constants.COMP_AREA_INDEX_NAME)).thenReturn(true);
        when(esUtilService.searchDocuments(any(), any())).thenReturn(mockSearchResult);
        // Act & Assert
        CustomException exception = assertThrows(CustomException.class,
                () -> competencyAreaService.createCompArea(input, token));

        assertEquals("error while processing", exception.getCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getHttpStatusCode());
    }

    @Test
    void testCreateCompArea_AlreadyPresent() throws Exception {
        // Arrange
        ObjectMapper objectMapper1 = new ObjectMapper();
        JsonNode input = objectMapper1.createObjectNode().put(Constants.TITLE, "Leadership");
        String token = "valid-token";

        SearchResult mockSearchResult = new SearchResult();
        ArrayNode existingData = objectMapper1.createArrayNode();
        ObjectNode existingItem = objectMapper1.createObjectNode();
        existingItem.put(Constants.TITLE, "Leadership");
        existingData.add(existingItem);
        mockSearchResult.setData(existingData);

        when(accessTokenValidator.verifyUserToken(token)).thenReturn("user-123");
        when(esUtilService.isIndexPresent(Constants.COMP_AREA_INDEX_NAME)).thenReturn(true);
        when(esUtilService.searchDocuments(any(), any())).thenReturn(mockSearchResult);

        // Act
        CustomResponse response = competencyAreaService.createCompArea(input, token);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Already Present", response.getParams().getErrmsg());
    }

    @Test
    void testReadCompArea_InvalidId() {
        // Arrange
        when(cacheService.getCache(TEST_ID)).thenReturn(null);
        when(competencyAreaRepository.findByIdAndIsActive(TEST_ID, true)).thenReturn(Optional.empty());

        // Act
        CustomResponse response = competencyAreaService.readCompArea(TEST_ID);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals(Constants.INVALID_ID, response.getMessage());
    }

    @Test
    void testReadCompArea_ExceptionWhileProcessing() throws Exception {
        // Arrange
        String invalidJson = "{ invalid_json }";
        when(cacheService.getCache(TEST_ID)).thenReturn(invalidJson);
        // Simulate ObjectMapper throwing an exception
        when(objectMapper.readValue(eq(invalidJson), any(TypeReference.class)))
                .thenThrow(new RuntimeException("JSON parsing failed"));

        // Act & Assert
        CustomException exception = assertThrows(CustomException.class, () -> {
            competencyAreaService.readCompArea(TEST_ID);
        });

        assertEquals("error while processing", exception.getMessage());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getHttpStatusCode());
    }

}
