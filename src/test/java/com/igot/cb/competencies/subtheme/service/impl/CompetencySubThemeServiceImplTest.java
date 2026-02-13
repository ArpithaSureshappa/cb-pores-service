package com.igot.cb.competencies.subtheme.service.impl;

import com.auth0.jwt.JWT;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.competencies.subtheme.entity.CompetencySubThemeEntity;
import com.igot.cb.competencies.subtheme.repository.CompetencySubThemeRepository;
import com.igot.cb.designation.service.DesignationService;
import com.igot.cb.pores.Service.OutboundRequestHandlerServiceImpl;
import com.igot.cb.pores.cache.CacheService;

import com.igot.cb.pores.dto.CustomResponse;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.sql.Timestamp;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompetencySubThemeServiceImplTest {

    @Mock
    private AccessTokenValidator accessTokenValidator;

    @Mock
    private CacheService cacheService;

    @Mock
    private CbServerProperties cbServerProperties;

    @Mock
    private CompetencySubThemeRepository competencySubThemeRepository;

    @InjectMocks
    private CompetencySubThemeServiceImpl competencySubThemeService;

    @Mock
    private DesignationService designationService;

    @Mock
    private EsUtilService esUtilService;

    @Mock
    private MultipartFile file;

    @Mock
    private FileProcessService fileProcessService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private OutboundRequestHandlerServiceImpl outboundRequestHandlerServiceImpl;

    @Mock
    private PayloadValidation payloadValidation;

    @Mock
    private RedisTemplate<String, SearchResult> redisTemplate;

    @Mock
    private ValueOperations<String, SearchResult> valueOperations;

    private static final String TEST_ID = "test-id";
    private static final String FRAMEWORK = "cbse";
    private static final String CATEGORY = "designation";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
    }

    /**
     * Test case for searchCompSubTheme method with search string less than 2 characters.
     * This test verifies that the method returns an error response when the search string is too short.
     */
    @Test
    void testSearchCompSubThemeWithShortSearchString() {
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString("a");

        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        CustomResponse response = competencySubThemeService.searchCompSubTheme(searchCriteria);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

    /**
     * Test case for createErrorResponse method
     * This test verifies that the createErrorResponse method correctly sets the error response parameters
     */
    @Test
    void test_createErrorResponse_1() {
        CompetencySubThemeServiceImpl service = new CompetencySubThemeServiceImpl();
        CustomResponse response = new CustomResponse();
        String errorMessage = "Test error message";
        HttpStatus httpStatus = HttpStatus.BAD_REQUEST;
        String status = "FAILED";

        service.createErrorResponse(response, errorMessage, httpStatus, status);

        assertEquals(status, response.getParams().getStatus());
        assertEquals(httpStatus, response.getResponseCode());
    }

    /**
     * Tests that createErrorResponse properly sets error message, HTTP status, and status
     * when all parameters are provided.
     */
    @Test
    void test_createErrorResponse_setsAllFields() {
        CompetencySubThemeServiceImpl service = new CompetencySubThemeServiceImpl();
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
     * Verifies that the method correctly sets the success response parameters
     */
    @Test
    void test_createSuccessResponse_1() {
        CompetencySubThemeServiceImpl service = new CompetencySubThemeServiceImpl();
        CustomResponse response = new CustomResponse();

        service.createSuccessResponse(response);

        assertNotNull(response.getParams());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        assertEquals(HttpStatus.OK, response.getResponseCode());
    }


    /**
    * Testcase 1 for @Override public CustomResponse deleteCompetencySubTheme(String id)
    * Path constraints: (optionalEntity.isPresent())
    * returns: response
    */
    @Test
    void test_deleteCompetencySubTheme_1() {
        // Arrange
        String id = "COMSUBTHEME-000001";
        CompetencySubThemeEntity competencySubThemeEntity = new CompetencySubThemeEntity();
        competencySubThemeEntity.setId(id);
        competencySubThemeEntity.setIsActive(true);
        ObjectNode dataNode = mock(ObjectNode.class);
        competencySubThemeEntity.setData(dataNode);

        when(competencySubThemeRepository.findByIdAndIsActive(id, true)).thenReturn(Optional.of(competencySubThemeEntity));
        //when(objectMapper.convertValue(any(), eq(JsonNode.class))).thenReturn(dataNode);
        when(cbServerProperties.getElasticCompJsonPath()).thenReturn("some/path");

        // Act
        CustomResponse response = competencySubThemeService.deleteCompetencySubTheme(id);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.DELETED_SUCCESSFULLY, response.getMessage());
    }

    /**
     * Test case for deleteCompetencySubTheme method when the entity is not present
     * This test verifies that the method returns a BAD_REQUEST response when the
     * competency sub-theme with the given ID is not found or is not active.
     */
    @Test
    void test_deleteCompetencySubTheme_2() {
        // Arrange
        String id = "non_existent_id";
        when(competencySubThemeRepository.findByIdAndIsActive(id, true)).thenReturn(Optional.empty());

        // Act
        CustomResponse response = competencySubThemeService.deleteCompetencySubTheme(id);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("CompetencySubThemeServiceImpl::deleteCompetencySubTheme:No data found for this id", response.getMessage());
    }

    @Test
    void test_deleteCompetencySubTheme_nonExistentId() {
        MockitoAnnotations.openMocks(this);

        String nonExistentId = "NON_EXISTENT_ID";
        when(competencySubThemeRepository.findByIdAndIsActive(nonExistentId, true)).thenReturn(Optional.empty());

        CustomResponse response = competencySubThemeService.deleteCompetencySubTheme(nonExistentId);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("CompetencySubThemeServiceImpl::deleteCompetencySubTheme:No data found for this id", response.getMessage());
    }

    /**
     * Test case for generating Redis JWT token key with a valid request payload.
     * This test verifies that the method returns a non-null JWT token string
     * when provided with a non-null request payload.
     */
    @Test
    void test_generateRedisJwtTokenKey_1() {
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");

        // Arrange
        Object requestPayload = new Object();
        String mockJsonString = "{\"key\":\"value\"}";

        try {
            when(objectMapper.writeValueAsString(any())).thenReturn(mockJsonString);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Act
        String result = competencySubThemeService.generateRedisJwtTokenKey(requestPayload);

        // Assert
        assertNotNull(result);
    }

    /**
     * Testcase 2 for public String generateRedisJwtTokenKey(Object requestPayload)
     * Tests the scenario when requestPayload is null
     */
    @Test
    void test_generateRedisJwtTokenKey_2() {
        CompetencySubThemeServiceImpl service = new CompetencySubThemeServiceImpl();
        Object requestPayload = null;
        String result = service.generateRedisJwtTokenKey(requestPayload);
        assertEquals("", result, "Should return an empty string when requestPayload is null");
    }

    /**
     * Test case for generateRedisJwtTokenKey method when input is null
     * This test verifies that the method returns an empty string when the input is null,
     * as explicitly handled in the method implementation.
     */
    @Test
    void test_generateRedisJwtTokenKey_nullInput() {
        CompetencySubThemeServiceImpl service = new CompetencySubThemeServiceImpl();
        service.objectMapper = new ObjectMapper();

        String result = service.generateRedisJwtTokenKey(null);

        assertEquals("", result);
    }

    /**
     * Testcase 1 for @Override public CustomResponse readCompSubTheme(String id)
     * Path constraints: (StringUtils.isEmpty(id))
     * This test verifies that when an empty id is provided, the method returns a BAD_REQUEST response
     * with the appropriate error message.
     */
    @Test
    void test_readCompSubTheme_1() {
        // Arrange
        String emptyId = "";

        // Act
        CustomResponse response = competencySubThemeService.readCompSubTheme(emptyId);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.ID_NOT_FOUND, response.getMessage());
    }

    /**
     * Testcase 2 for @Override public CustomResponse readCompSubTheme(String id)
     * Path constraints: !((StringUtils.isEmpty(id))), (StringUtils.isNotEmpty(cachedJson))
     * This test verifies that when a valid id is provided and the data is found in the cache,
     * the method returns a successful response with the cached data.
     */
    @Test
    void test_readCompSubTheme_2() throws JsonProcessingException {
        // Arrange
        String id = "validId";
        String cachedJson = "{\"key\":\"value\"}";
        Object mockObject = new Object();

        when(cacheService.getCache(id)).thenReturn(cachedJson);
        //when(objectMapper.readValue((String) eq(cachedJson), (Class<Object>) any())).thenReturn(mockObject);

        // Act
        CustomResponse response = competencySubThemeService.readCompSubTheme(id);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals("successfully read", response.getMessage());
        assertTrue(response.getResult().containsKey("result"));


        verify(cacheService).getCache(id);
    }

    /**
     * Test case for readCompSubTheme method when the id is not empty and the cache is empty.
     * This test verifies that the method correctly retrieves data from the repository
     * when it's not found in the cache.
     */
    @Test
    void test_readCompSubTheme_3() {
        MockitoAnnotations.openMocks(this);

        String id = "testId";
        CompetencySubThemeEntity entity = new CompetencySubThemeEntity();
        entity.setId(id);
        entity.setData(null);

        when(cacheService.getCache(id)).thenReturn(null);
        when(competencySubThemeRepository.findByIdAndIsActive(id, true)).thenReturn(Optional.of(entity));

        CustomResponse response = competencySubThemeService.readCompSubTheme(id);

        verify(cacheService).getCache(id);
        verify(competencySubThemeRepository).findByIdAndIsActive(id, true);
        verify(cacheService).putCache(eq(id), any());

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFULLY_READING, response.getMessage());
    }

    /**
     * Tests the readCompSubTheme method with an empty id input.
     * This negative test case verifies that the method handles empty id
     * by returning a BAD_REQUEST response with an appropriate error message.
     */
    @Test
    void test_readCompSubTheme_emptyId() {
        CompetencySubThemeServiceImpl service = new CompetencySubThemeServiceImpl();
        CustomResponse response = service.readCompSubTheme("");

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.ID_NOT_FOUND, response.getMessage());
    }

    /**
    * Testcase 1 for public ApiResponse readTerm(String Id, String framework, String category)
    * Path constraints: (null != desgResponse), (Constants.OK.equalsIgnoreCase((String) desgResponse.get(Constants.RESPONSE_CODE)))
    * returns: response
    */
    @Test
    void test_readTerm_1() {
        // Arrange
        String id = "testId";
        String framework = "testFramework";
        String category = "testCategory";

        when(cbServerProperties.getKnowledgeMS()).thenReturn("http://test-url.com");
        when(cbServerProperties.getOdcsDesignationTermRead()).thenReturn("/read");
        when(cbServerProperties.getOdcsFields()).thenReturn(List.of(new String[]{"field1", "field2"}));

        Map<String, Object> termData = new HashMap<>();
        termData.put("field1", "value1");
        termData.put("field2", "value2");

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put(Constants.TERM, termData);

        Map<String, Object> desgResponse = new HashMap<>();
        desgResponse.put(Constants.RESPONSE_CODE, Constants.OK);
        desgResponse.put(Constants.RESULT, resultMap);

        when(outboundRequestHandlerServiceImpl.fetchResult(anyString())).thenReturn(desgResponse);

        // Act
        ApiResponse response = competencySubThemeService.readTerm(id, framework, category);

        // Assert
        assertNotNull(response);
        assertNotNull(response.getResult().get(Constants.DESIGNATION));
        Map<String, Object> designation = (Map<String, Object>) response.getResult().get(Constants.DESIGNATION);
        assertEquals("value1", designation.get("field1"));
        assertEquals("value2", designation.get("field2"));
    }

    /**
     * Testcase 2 for public ApiResponse readTerm(String Id, String framework, String category)
     * Path constraints: (null != desgResponse), !((Constants.OK.equalsIgnoreCase((String) desgResponse.get(Constants.RESPONSE_CODE))))
     * This test case verifies that the readTerm method returns a NOT_FOUND response when the desgResponse is not null
     * but does not have an "OK" response code.
     */
    @Test
    void test_readTerm_2() {
        // Arrange
        String id = "testId";
        String framework = "testFramework";
        String category = "testCategory";
        String url = "http://test.url";

        when(cbServerProperties.getKnowledgeMS()).thenReturn("http://test.url");
        when(cbServerProperties.getOdcsDesignationTermRead()).thenReturn("/read");

        Map<String, Object> desgResponse = new HashMap<>();
        desgResponse.put(Constants.RESPONSE_CODE, "NOT_OK");

        when(outboundRequestHandlerServiceImpl.fetchResult(url + "/read/" + id + "?framework=" + framework + "&category=" + category))
                .thenReturn(desgResponse);

        // Act
        ApiResponse response = competencySubThemeService.readTerm(id, framework, category);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals("Data not found with id : " + id, response.getParams().getErr());
    }

    /**
     * Test case for readTerm method when the outboundRequestHandlerServiceImpl.fetchResult returns null.
     * This test verifies that the method handles the case where no data is returned from the external service.
     */
    @Test
    void test_readTerm_3() {
        // Arrange
        String id = "testId";
        String framework = "testFramework";
        String category = "testCategory";
        String url = "http://test.url";

        when(cbServerProperties.getKnowledgeMS()).thenReturn(url);
        when(cbServerProperties.getOdcsDesignationTermRead()).thenReturn("/read");
        when(outboundRequestHandlerServiceImpl.fetchResult(anyString())).thenReturn(null);

        // Act
        ApiResponse response = competencySubThemeService.readTerm(id, framework, category);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals("Failed to read the term details for Id : testId", response.getParams().getErr());
    }

    /**
     * Test case for readTerm method when the outbound request returns null.
     * This tests the scenario where the external service call fails or returns unexpected data.
     */
    @Test
    void test_readTerm_outboundRequestReturnsNull() {
        // Arrange
        when(cbServerProperties.getKnowledgeMS()).thenReturn("http://example.com");
        when(cbServerProperties.getOdcsDesignationTermRead()).thenReturn("/read");
        when(outboundRequestHandlerServiceImpl.fetchResult(anyString())).thenReturn(null);

        // Act
        ApiResponse response = competencySubThemeService.readTerm("testId", "testFramework", "testCategory");

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals("Failed to read the term details for Id : testId", response.getParams().getErr());
    }

    /**
     * Test case for readTerm method when the response code is not OK.
     * This tests the scenario where the external service returns an error response.
     */
    @Test
    void test_readTerm_responseCodeNotOK() {
        // Arrange
        when(cbServerProperties.getKnowledgeMS()).thenReturn("http://example.com");
        when(cbServerProperties.getOdcsDesignationTermRead()).thenReturn("/read");
        when(outboundRequestHandlerServiceImpl.fetchResult(anyString())).thenReturn(java.util.Collections.singletonMap("responseCode", "ERROR"));

        // Act
        ApiResponse response = competencySubThemeService.readTerm("testId", "testFramework", "testCategory");

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals("Data not found with id : testId", response.getParams().getErr());
    }

    /**
     * Test case for searchCompSubTheme method when search result is found in Redis cache.
     * This test verifies that when a cached search result is available in Redis,
     * the method returns a successful response with the cached result.
     */
    @Test
    void test_searchCompSubTheme_1() {
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        SearchResult mockSearchResult = new SearchResult();
        String mockRedisKey = "mockRedisKey";

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(competencySubThemeService.generateRedisJwtTokenKey(searchCriteria)).thenReturn(mockRedisKey);
        lenient().when(valueOperations.get(mockRedisKey)).thenReturn(mockSearchResult);

        // Act
        CustomResponse response = competencySubThemeService.searchCompSubTheme(searchCriteria);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
    }

    /**
     * Test case for searchCompSubTheme method when Redis cache is empty and search string is valid.
     * This test verifies that the method correctly searches for competency sub-themes using ElasticSearch
     * when the Redis cache does not contain the search result and the search string is valid.
     */
    @Test
    void test_searchCompSubTheme_3() throws Exception {
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString("valid search");

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        SearchResult mockSearchResult = new SearchResult();
        when(esUtilService.searchDocuments(anyString(), any(SearchCriteria.class))).thenReturn(mockSearchResult);

        // Act
        CustomResponse response = competencySubThemeService.searchCompSubTheme(searchCriteria);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        assertEquals(mockSearchResult, response.getResult().get(Constants.RESULT));
    }

    /**
     * Testcase 2 for searchCompSubTheme method
     * Tests the scenario where the search string is too short (less than 2 characters)
     */
    @Test
    void test_searchCompSubTheme_shortSearchString() {
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
//        when(valueOperations.get(anyString())).thenReturn(null);
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString("a");

        // Act
        CustomResponse response = competencySubThemeService.searchCompSubTheme(searchCriteria);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

    /**
     * Test case for updating a CompetencySubTheme when the ID exists and the entity is present.
     * This test verifies that the updateCompSubTheme method correctly updates an existing
     * CompetencySubTheme entity and returns a successful response.
     */
    @Test
    void test_updateCompSubTheme_1() {
        // Arrange
        ObjectMapper realObjectMapper = new ObjectMapper();

        ObjectNode updatedCompSubTheme = realObjectMapper.createObjectNode();
        updatedCompSubTheme.put(Constants.ID, "TEST_ID");
        updatedCompSubTheme.put("title", "Updated Title");

        CompetencySubThemeEntity existingEntity = new CompetencySubThemeEntity();
        existingEntity.setId("TEST_ID");

        ObjectNode existingData = realObjectMapper.createObjectNode();
        existingData.put("existingKey", "existingValue");
        existingEntity.setData(existingData);

        existingEntity.setUpdatedOn(new Timestamp(System.currentTimeMillis()));

        // Inject real ObjectMapper
        ReflectionTestUtils.setField(competencySubThemeService, "objectMapper", realObjectMapper);

        // Mock other dependencies
        when(competencySubThemeRepository.findById("TEST_ID")).thenReturn(Optional.of(existingEntity));
        when(competencySubThemeRepository.save(any(CompetencySubThemeEntity.class))).thenReturn(existingEntity);
        when(cbServerProperties.getElasticCompJsonPath()).thenReturn("testPath");
        doNothing().when(payloadValidation).validatePayload(anyString(), eq(updatedCompSubTheme));

        // Act
        CustomResponse response = competencySubThemeService.updateCompSubTheme(updatedCompSubTheme);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFULLY_UPDATED, response.getMessage());

        // Optional verification
        ArgumentCaptor<CompetencySubThemeEntity> captor = ArgumentCaptor.forClass(CompetencySubThemeEntity.class);
        verify(competencySubThemeRepository).save(captor.capture());

    }


    /**
     * Test case for updating a CompetencySubTheme when the ID exists and the update is successful.
     * This test covers the path where the ID exists, the CompetencySubTheme is found,
     * and the update operation is performed successfully.
     */
    @Test
    void test_updateCompSubTheme_2() {
        // Arrange
        ObjectMapper realObjectMapper = new ObjectMapper();

        ObjectNode updatedCompSubTheme = realObjectMapper.createObjectNode();
        updatedCompSubTheme.put(Constants.ID, "testId");
        updatedCompSubTheme.put("fieldToUpdate", "updatedValue");

        ObjectNode existingData = realObjectMapper.createObjectNode();
        existingData.put("existingField", "existingValue");

        CompetencySubThemeEntity existingEntity = new CompetencySubThemeEntity();
        existingEntity.setId("testId");
        existingEntity.setData(existingData);

        when(competencySubThemeRepository.findById("testId")).thenReturn(Optional.of(existingEntity));
        when(competencySubThemeRepository.save(any(CompetencySubThemeEntity.class))).thenReturn(existingEntity);

        // We use a spy to avoid mocking createObjectNode (final method)
        ObjectMapper objectMapperSpy = spy(realObjectMapper);

        // Inject real objectMapper into service (assuming setter or constructor injection available)
        ReflectionTestUtils.setField(competencySubThemeService, "objectMapper", objectMapperSpy);

        // Also mock payload validation
        doNothing().when(payloadValidation).validatePayload(
                eq(Constants.COMP_AREA_PAYLOAD_VALIDATION),
                eq(updatedCompSubTheme)
        );

        // Act
        CustomResponse response = competencySubThemeService.updateCompSubTheme(updatedCompSubTheme);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFULLY_UPDATED, response.getMessage());

        // Optional: verify merged result
        ArgumentCaptor<CompetencySubThemeEntity> captor = ArgumentCaptor.forClass(CompetencySubThemeEntity.class);
        verify(competencySubThemeRepository).save(captor.capture());
    }

    /**
     * Test case for updateCompSubTheme when the competency sub-theme with the given ID is not found.
     * This test verifies that the method returns the appropriate error response when the
     * competency sub-theme to be updated does not exist in the repository.
     */
    @Test
    void test_updateCompSubTheme_4() {
        // Arrange
        ObjectNode updatedCompSubTheme = mock(ObjectNode.class);
        when(updatedCompSubTheme.has(Constants.ID)).thenReturn(true);
        when(updatedCompSubTheme.get(Constants.ID)).thenReturn(mock(JsonNode.class));
        when(updatedCompSubTheme.get(Constants.ID).isNull()).thenReturn(false);
        when(updatedCompSubTheme.get(Constants.ID).asText()).thenReturn("non-existent-id");

        when(competencySubThemeRepository.findById("non-existent-id")).thenReturn(Optional.empty());

        // Act
        CustomResponse response = competencySubThemeService.updateCompSubTheme(updatedCompSubTheme);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("No data found for this id", response.getMessage());
    }

    /**
     * Test case for updateCompSubTheme method when the ID is missing in the input.
     * This test verifies that the method returns a BAD_REQUEST response when the input JSON does not contain an ID.
     */
    @Test
    void test_updateCompSubTheme_missingId() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode updatedCompSubTheme = mapper.createObjectNode();
        updatedCompSubTheme.put("key", "value");
        Mockito.lenient().doNothing().when(payloadValidation)
                .validatePayload(anyString(), any(JsonNode.class));

        CustomResponse response = competencySubThemeService.updateCompSubTheme(updatedCompSubTheme);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Id is missing", response.getMessage());
    }

    /**
     * Test case for updateCompSubTheme method when the updatedCompSubTheme JsonNode
     * does not have an ID or the ID is null.
     * 
     * This test verifies that the method returns a CustomResponse with BAD_REQUEST
     * status and appropriate error message when the input lacks a valid ID.
     */
    @Test
    void test_updateCompSubTheme_missingOrNullId() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode updatedCompSubTheme = mapper.createObjectNode();
        updatedCompSubTheme.put("key", "value");
        Mockito.lenient().doNothing().when(payloadValidation)
                .validatePayload(anyString(), any(JsonNode.class));

        CustomResponse response = competencySubThemeService.updateCompSubTheme(updatedCompSubTheme);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Id is missing", response.getMessage());
    }
    
    @Test
    void testLoadCompetencySubTheme_WithEmptyUserId() {
        MultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/vnd.ms-excel", new byte[0]);
        when(accessTokenValidator.verifyUserToken(anyString())).thenReturn(""); // blank userId

        // Should do nothing if userId is blank, no exception
        assertDoesNotThrow(() -> competencySubThemeService.loadCompetencySubTheme(file, "token"));

        verifyNoInteractions(fileProcessService);
        verifyNoInteractions(competencySubThemeRepository);
    }

    @Test
    void test_readCompSubTheme_whenExceptionThrown_logsErrorAndThrowsCustomException() {
        // Arrange
        when(cacheService.getCache(TEST_ID)).thenThrow(new RuntimeException("Cache failure"));

        // Act & Assert
        CustomException exception = assertThrows(CustomException.class, () -> {
            competencySubThemeService.readCompSubTheme(TEST_ID);
        });

        assertEquals("error while processing", exception.getMessage());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getHttpStatusCode());
    }

    @Test
    void test_readTerm_logsError_whenFetchResultThrowsException() {
        // Arrange
        when(cbServerProperties.getKnowledgeMS()).thenReturn("http://mock-knowledge-ms");
        when(cbServerProperties.getOdcsDesignationTermRead()).thenReturn("/term/read");

        // Simulate exception on fetchResult
        when(outboundRequestHandlerServiceImpl.fetchResult(anyString()))
                .thenThrow(new RuntimeException("Service failure"));

        // Act
        ApiResponse response = competencySubThemeService.readTerm(TEST_ID, FRAMEWORK, CATEGORY);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals("Failed", response.getParams().getStatus());
        assertTrue(response.getParams().getErr().contains("Failed to read term"));
    }
}
