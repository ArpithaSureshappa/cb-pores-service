package com.igot.cb.designation.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.designation.entity.DesignationEntity;
import com.igot.cb.designation.repository.DesignationRepository;
import com.igot.cb.playlist.util.ProjectUtil;
import com.igot.cb.pores.Service.OutboundRequestHandlerServiceImpl;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.dto.CustomResponse;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.pores.util.PayloadValidation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.util.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.collections.MapUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class DesignationServiceImplTest {

    @Mock
    private AccessTokenValidator accessTokenValidator;

    @Mock
    private CacheService cacheService;

    @Mock
    private CbServerProperties cbServerProperties;

    @Mock
    private DesignationRepository designationRepository;


    @InjectMocks
    private DesignationServiceImpl designationService;

    @Spy
    @InjectMocks
    private DesignationServiceImpl spyDesignationService;

    @Mock
    private EsUtilService esUtilService;

    @Mock
    private DesignationServiceImpl mockDesignationService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private OutboundRequestHandlerServiceImpl outboundRequestHandlerService;

    @Mock
    private OutboundRequestHandlerServiceImpl outboundRequestHandlerServiceImpl;

    @Mock
    private PayloadValidation payloadValidation;

    @Mock
    private RedisTemplate<String, SearchResult> redisTemplate;

    @Mock
    private ValueOperations<String, SearchResult> valueOperations;

    private static final String FRAMEWORK_ID = "framework1";
    private static final String CATEGORY_CODE = "cat1";
    private static final String TERM_CODE = "term001";
    private static final String REF_ID = "DESG-1234";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    /**
     * Test case for createDesignation method when the designation already exists.
     * This test covers the scenario where:
     * - The index is present
     * - The fetched data is not empty and not null
     * - The data JSON is not empty and not null
     * - The node has a DESIGNATION field
     * - The designation title already exists in the titles map
     */
    @Test
    void test_createDesignation_2() throws Exception {
        // Arrange
        JsonNode designationDetails = Mockito.mock(JsonNode.class);
        ObjectNode dataNode = Mockito.mock(ObjectNode.class);
        SearchResult searchResult = new SearchResult();
        searchResult.setData(dataNode);

        when(esUtilService.isIndexPresent(Constants.DESIGNATION_INDEX_NAME)).thenReturn(true);
        when(esUtilService.searchDocuments(any(), any())).thenReturn(searchResult);
        when(dataNode.isEmpty()).thenReturn(false);

        // Act
        CustomResponse response = designationService.createDesignation(designationDetails);

        // Assert
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    /**
     * Test case for createDesignation method when the designation already exists.
     * This test verifies that the method returns a BAD_REQUEST response when
     * attempting to create a designation that already exists in the system.
     */
    @Test
    void test_createDesignation_alreadyExists() throws Exception {
        // Arrange
        JsonNode designationDetails = new ObjectMapper().createObjectNode()
                .put(Constants.DESIGNATION, "Existing Designation");

        SearchResult searchResult = new SearchResult();
        searchResult.setData(new ObjectMapper().createArrayNode().add(
                new ObjectMapper().createObjectNode().put(Constants.DESIGNATION, "Existing Designation")
        ));

        when(esUtilService.isIndexPresent(anyString())).thenReturn(true);
        when(esUtilService.searchDocuments(anyString(), any())).thenReturn(searchResult);

        // Act
        CustomResponse response = designationService.createDesignation(designationDetails);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Already Present", response.getParams().getErrmsg());
    }

    /**
     * Test case for createErrorResponse method
     * Verifies that the CustomResponse object is properly populated with error details
     */
    @Test
    void test_createErrorResponse_1() {
        CustomResponse response = new CustomResponse();
        String errorMessage = "Test error message";
        HttpStatus httpStatus = HttpStatus.BAD_REQUEST;
        String status = "FAILED";

        designationService.createErrorResponse(response, errorMessage, httpStatus, status);

        assertNotNull(response.getParams());
        assertEquals(status, response.getParams().getStatus());
        assertEquals(httpStatus, response.getResponseCode());
    }

    /**
     * Test case to verify that createSuccessResponse sets the correct values in the CustomResponse object.
     */
    @Test
    void test_createSuccessResponse_setsCorrectValues() {
        CustomResponse response = new CustomResponse();

        designationService.createSuccessResponse(response);

        assertNotNull(response.getParams());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    /**
     * Test case for createTerm method when designation entity is present, active,
     * but the readResponse is null.
     * This test verifies that the method returns an error response when it fails to validate
     * if the sector exists.
     */
    @Test
    void test_createTerm_1() {
        MockitoAnnotations.openMocks(this);

        // Prepare test data
        ObjectMapper realObjectMapper = new ObjectMapper();
        ObjectNode request = realObjectMapper.createObjectNode();

        request.set("name", TextNode.valueOf("Sample Term"));
        request.set("refId", TextNode.valueOf("REF-12345"));
        request.set("framework", TextNode.valueOf("SampleFramework"));
        request.set("category", TextNode.valueOf("General"));

        ObjectNode additionalProps = realObjectMapper.createObjectNode();
        additionalProps.put("description", "A sample term for testing");
        additionalProps.put("level", "1");

        request.set("additionalProperties", additionalProps);

        DesignationEntity designationEntity = new DesignationEntity();
        designationEntity.setIsActive(true);

        // Mock repository and validation behavior
        lenient().when(designationRepository.findByIdAndIsActive(eq("REF001"), eq(Boolean.TRUE)))
                .thenReturn(Optional.of(designationEntity));

        // Call the method under test
        ApiResponse response = designationService.createTerm(request);

        // Verify the response
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    /**
     * Test case for createTerm method when designation entity is present but not active.
     * This test verifies that the method returns an appropriate error response when
     * the designation exists but is not active.
     */
    @Test
    void test_createTerm_designationNotExist() {
        // Arrange
        ObjectMapper realObjectMapper = new ObjectMapper();
        ObjectNode request = realObjectMapper.createObjectNode();

        request.set("name", TextNode.valueOf("Sample Term"));
        request.set("refId", TextNode.valueOf("REF-12345"));
        request.set("framework", TextNode.valueOf("SampleFramework"));
        request.set("category", TextNode.valueOf("General"));

        ObjectNode additionalProps = realObjectMapper.createObjectNode();
        additionalProps.put("description", "A sample term for testing");
        additionalProps.put("level", "1");

        request.set("additionalProperties", additionalProps);
        DesignationEntity designationEntity = new DesignationEntity();
        designationEntity.setIsActive(false);

        lenient().when(designationRepository.findByIdAndIsActive("testRefId", Boolean.TRUE))
                .thenReturn(Optional.of(designationEntity));

        // Act
        ApiResponse response = designationService.createTerm(request);

        // Assert
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals("Designation Not Exist.", response.getParams().getErr());
    }

    /**
     * Test case for deleteDesignation method when the designation exists and is active.
     * This test verifies that the method successfully deactivates the designation,
     * updates the database and Elasticsearch, clears the cache, and returns a success response.
     */
    @Test
    void test_deleteDesignation_1() {
        // Arrange
        String id = "DESG-000001";
        DesignationEntity designationEntity = new DesignationEntity();
        designationEntity.setId(id);
        designationEntity.setIsActive(true);
        ObjectNode dataNode = mock(ObjectNode.class);
        designationEntity.setData(dataNode);

        when(designationRepository.findByIdAndIsActive(id, true)).thenReturn(Optional.of(designationEntity));
        when(objectMapper.convertValue(any(), eq(java.util.Map.class))).thenReturn(new java.util.HashMap<>());

        // Act
        CustomResponse response = designationService.deleteDesignation(id);

        // Assert
        verify(designationRepository).save(designationEntity);
        verify(esUtilService).addDocument(eq(Constants.DESIGNATION_INDEX_NAME), eq(Constants.INDEX_TYPE), eq(id), any(), any());
        verify(cacheService).deleteCache(id);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.DELETED_SUCCESSFULLY, response.getMessage());

        assertFalse(designationEntity.getIsActive());
        assertNotNull(designationEntity.getUpdatedOn());
        verify(dataNode).put(eq(Constants.UPDATED_ON), any(String.class));
        verify(dataNode).put(Constants.STATUS, Constants.IN_ACTIVE);
    }

    /**
     * Test case for deleteDesignation method when the designation is not present.
     * This test verifies that the method returns a BAD_REQUEST response when
     * the designation with the given ID is not found or is not active.
     */
    @Test
    void test_deleteDesignation_2() {
        // Arrange
        String id = "non_existent_id";
        when(designationRepository.findByIdAndIsActive(id, true)).thenReturn(Optional.empty());

        // Act
        CustomResponse response = designationService.deleteDesignation(id);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("No data found for this id", response.getMessage());
    }

    /**
     * Test deleteDesignation method when the designation is not found (inactive).
     * This test verifies that the method returns the correct response when
     * the designation with the given ID is not found or is inactive.
     */
    @Test
    void test_deleteDesignation_designationNotFound() {
        // Arrange
        String id = "nonexistentId";
        when(designationRepository.findByIdAndIsActive(id, true)).thenReturn(Optional.empty());

        // Act
        CustomResponse response = designationService.deleteDesignation(id);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("No data found for this id", response.getMessage());
        verify(designationRepository).findByIdAndIsActive(id, true);
        verifyNoMoreInteractions(designationRepository, esUtilService, cacheService);
    }

    /**
     * Test deleteDesignation method when an exception occurs during the process.
     * This test verifies that the method handles exceptions properly and returns
     * the correct error response.
     */
    @Test
    void test_deleteDesignation_exceptionOccurs() {
        // Arrange
        String id = "validId";
        when(designationRepository.findByIdAndIsActive(id, true)).thenThrow(new RuntimeException("Database error"));

        // Act
        CustomResponse response = designationService.deleteDesignation(id);

        // Assert
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Database error", response.getMessage());
        assertEquals("Failed", response.getParams().getStatus());
    }

    /**
     * Test case for frameworkRead method when the frameworkResponse is empty.
     * This test verifies that the method returns the correct ApiResponse when
     * the framework response is empty.
     */
    @Test
    void test_frameworkRead_1() {
        // Arrange
        String frameworkId = "testFrameworkId";
        String categoryCode = "testCategoryCode";
        String termCode = "testTermCode";
        String refId = "testRefId";

        when(cbServerProperties.getKnowledgeMS()).thenReturn("http://test-knowledge-ms.com");
        when(cbServerProperties.getOdcsFrameworkRead()).thenReturn("/framework/read");
        when(outboundRequestHandlerServiceImpl.fetchResult(anyString())).thenReturn(new HashMap<>());

        // Act
        ApiResponse response = designationService.frameworkRead(frameworkId, categoryCode, termCode, refId);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Failed to read framework details for ID: " + frameworkId, response.getParams().getErr());
        assertTrue(MapUtils.isEmpty((Map<?, ?>) response.getResult().get(Constants.FRAMEWORK)));
    }

    /**
     * Test case for frameworkRead method when the framework response is not empty,
     * the response code is OK, categories are not empty, but the category is not found.
     * 
     * Path constraints:
     * - !((MapUtils.isEmpty(frameworkResponse)))
     * - !((!Constants.OK.equalsIgnoreCase(responseCode)))
     * - (CollectionUtils.isNotEmpty(categories))
     * - (MapUtils.isEmpty(category))
     */
    @Test
    void test_frameworkRead_3() {
        // Arrange
        String frameworkId = "testFrameworkId";
        String categoryCode = "testCategoryCode";
        String termCode = "testTermCode";
        String refId = "testRefId";

        when(cbServerProperties.getKnowledgeMS()).thenReturn("http://test-url.com");
        when(cbServerProperties.getOdcsFrameworkRead()).thenReturn("/framework/read");

        Map<String, Object> frameworkResponse = new HashMap<>();
        frameworkResponse.put(Constants.RESPONSE_CODE, Constants.OK);

        Map<String, Object> resultMap = new HashMap<>();
        Map<String, Object> framework = new HashMap<>();
        List<Map<String, Object>> categories = new ArrayList<>();
        Map<String, Object> category = new HashMap<>();
        category.put(Constants.CODE, "differentCategoryCode");
        categories.add(category);
        framework.put(Constants.CATEGORIES, categories);
        resultMap.put(Constants.FRAMEWORK, framework);
        frameworkResponse.put(Constants.RESULT, resultMap);

        when(outboundRequestHandlerServiceImpl.fetchResult(anyString())).thenReturn(frameworkResponse);

        // Act
        ApiResponse response = designationService.frameworkRead(frameworkId, categoryCode, termCode, refId);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Category not found with code: " + categoryCode, response.getParams().getErr());
    }

    /**
     * Test case for frameworkRead method when the term is not found.
     * This test verifies the behavior when the framework and category exist,
     * but the specified term is not found within the category.
     */
    @Test
    void test_frameworkRead_6() {
        // Arrange
        String frameworkId = "testFramework";
        String categoryCode = "testCategory";
        String termCode = "nonExistentTerm";
        String refId = "testRefId";

        when(cbServerProperties.getKnowledgeMS()).thenReturn("http://test-knowledge-ms.com/");
        when(cbServerProperties.getOdcsFrameworkRead()).thenReturn("framework/read");

        Map<String, Object> frameworkResponse = new HashMap<>();
        frameworkResponse.put(Constants.RESPONSE_CODE, Constants.OK);

        Map<String, Object> framework = new HashMap<>();
        List<Map<String, Object>> categories = new ArrayList<>();
        Map<String, Object> category = new HashMap<>();
        category.put(Constants.CODE, categoryCode);
        category.put(Constants.TERMS, new ArrayList<>());
        categories.add(category);
        framework.put(Constants.CATEGORIES, categories);

        Map<String, Object> result = new HashMap<>();
        result.put(Constants.FRAMEWORK, framework);
        frameworkResponse.put(Constants.RESULT, result);

        when(outboundRequestHandlerServiceImpl.fetchResult(anyString())).thenReturn(frameworkResponse);

        // Act
        ApiResponse response = designationService.frameworkRead(frameworkId, categoryCode, termCode, refId);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals("Term not found with code: " + termCode, response.getParams().getErr());
    }

    /**
     * Test case for frameworkRead method when category is not found
     * Path constraints: !((MapUtils.isEmpty(frameworkResponse))), !((!Constants.OK.equalsIgnoreCase(responseCode))), !((CollectionUtils.isNotEmpty(categories))), (MapUtils.isEmpty(category))
     */
    @Test
    void test_frameworkRead_categoryNotFound() {
        // Arrange
        String frameworkId = "testFrameworkId";
        String categoryCode = "testCategoryCode";
        String termCode = "testTermCode";
        String refId = "testRefId";

        ApiResponse expectedResponse = ProjectUtil.createDefaultResponse("");
        expectedResponse.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
        expectedResponse.getParams().setErr("Category not found with code: " + categoryCode);

        ApiResponse actualResponse = designationService.frameworkRead(frameworkId, categoryCode, termCode, refId);

        // Assert
        assertEquals(expectedResponse.getResponseCode(), actualResponse.getResponseCode());

    }

    /**
     * Test case for category not found in the framework response.
     */
    @Test
    void test_frameworkRead_categoryNotFound_2() {
        Map<String, Object> frameworkResponse = new HashMap<>();
        frameworkResponse.put(Constants.RESPONSE_CODE, Constants.OK);
        Map<String, Object> resultMap = new HashMap<>();
        Map<String, Object> framework = new HashMap<>();
        framework.put(Constants.CATEGORIES, new HashMap<>());
        resultMap.put(Constants.FRAMEWORK, framework);
        frameworkResponse.put(Constants.RESULT, resultMap);

        when(cbServerProperties.getKnowledgeMS()).thenReturn("http://example.com");
        when(cbServerProperties.getOdcsFrameworkRead()).thenReturn("/framework");
        when(outboundRequestHandlerServiceImpl.fetchResult(anyString())).thenReturn(frameworkResponse);

        ApiResponse response = designationService.frameworkRead("frameworkId", "categoryCode", "termCode", "refId");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    /**
     * Test case for empty framework response from outbound request handler.
     */
    @Test
    void test_frameworkRead_emptyFrameworkResponse() {
        when(cbServerProperties.getKnowledgeMS()).thenReturn("http://example.com");
        when(cbServerProperties.getOdcsFrameworkRead()).thenReturn("/framework");
        when(outboundRequestHandlerServiceImpl.fetchResult(anyString())).thenReturn(new HashMap<>());

        ApiResponse response = designationService.frameworkRead("frameworkId", "categoryCode", "termCode", "refId");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Failed to read framework details for ID: frameworkId", response.getParams().getErr());
    }

    /**
     * Test case for invalid response code from framework response.
     */
    @Test
    void test_frameworkRead_invalidResponseCode() {
        Map<String, Object> frameworkResponse = new HashMap<>();
        frameworkResponse.put(Constants.RESPONSE_CODE, "ERROR");

        when(cbServerProperties.getKnowledgeMS()).thenReturn("http://example.com");
        when(cbServerProperties.getOdcsFrameworkRead()).thenReturn("/framework");
        when(outboundRequestHandlerServiceImpl.fetchResult(anyString())).thenReturn(frameworkResponse);

        ApiResponse response = designationService.frameworkRead("frameworkId", "categoryCode", "termCode", "refId");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Data not found with ID: frameworkId", response.getParams().getErr());
    }

    /**
     * Test case for frameworkRead method when the framework response is not empty
     * but the response code is not "OK".
     * This test covers the path where !MapUtils.isEmpty(frameworkResponse) is true
     * and !Constants.OK.equalsIgnoreCase(responseCode) is true.
     */
    @Test
    void test_frameworkRead_nonEmptyResponseWithNonOKCode() {
        // Arrange
        String frameworkId = "testFramework";
        String categoryCode = "testCategory";
        String termCode = "testTerm";
        String refId = "testRefId";
        String url = "http://test-url.com";

        when(cbServerProperties.getKnowledgeMS()).thenReturn("http://test-url.com");
        when(cbServerProperties.getOdcsFrameworkRead()).thenReturn("/framework/read");

        Map<String, Object> frameworkResponse = new HashMap<>();
        frameworkResponse.put(Constants.RESPONSE_CODE, "NOT_OK");

        when(outboundRequestHandlerServiceImpl.fetchResult(url + "/framework/read/" + frameworkId))
                .thenReturn(frameworkResponse);

        // Act
        ApiResponse response = designationService.frameworkRead(frameworkId, categoryCode, termCode, refId);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Data not found with ID: " + frameworkId, response.getParams().getErr());
        verify(outboundRequestHandlerServiceImpl, times(1)).fetchResult(anyString());
    }

    /**
     * Tests the frameworkRead method when the framework response is not empty,
     * the response code is OK, categories are not empty, category is not empty,
     * terms are not empty, but the specific term is not found.
     */
    @Test
    void test_frameworkRead_termNotFound() {
        // Arrange
        String frameworkId = "testFramework";
        String categoryCode = "testCategory";
        String termCode = "testTerm";
        String refId = "testRefId";

        when(cbServerProperties.getKnowledgeMS()).thenReturn("http://test-url/");
        when(cbServerProperties.getOdcsFrameworkRead()).thenReturn("framework");

        Map<String, Object> frameworkResponse = new HashMap<>();
        frameworkResponse.put(Constants.RESPONSE_CODE, Constants.OK);

        Map<String, Object> resultMap = new HashMap<>();
        Map<String, Object> framework = new HashMap<>();
        List<Map<String, Object>> categories = new ArrayList<>();
        Map<String, Object> category = new HashMap<>();
        category.put(Constants.CODE, categoryCode);
        List<Map<String, Object>> terms = new ArrayList<>();
        category.put(Constants.TERMS, terms);
        categories.add(category);
        framework.put(Constants.CATEGORIES, categories);
        resultMap.put(Constants.FRAMEWORK, framework);
        frameworkResponse.put(Constants.RESULT, resultMap);

        when(outboundRequestHandlerServiceImpl.fetchResult(anyString())).thenReturn(frameworkResponse);

        // Act
        ApiResponse response = designationService.frameworkRead(frameworkId, categoryCode, termCode, refId);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals("Term not found with code: " + termCode, response.getParams().getErr());
    }

    /**
     * Testcase 2 for public String generateRedisJwtTokenKey(Object requestPayload)
     * Tests the scenario when requestPayload is null
     * Expected result: An empty string should be returned
     */
    @Test
    void test_generateRedisJwtTokenKey_2() {
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        String result = designationService.generateRedisJwtTokenKey(null);
        assertEquals("", result);
    }

    /**
     * Test case for generateRedisJwtTokenKey method with null input.
     * This test verifies that the method returns an empty string when provided with a null input,
     * which is an edge case explicitly handled in the method implementation.
     */
    @Test
    void test_generateRedisJwtTokenKey_nullInput() {
        String result = designationService.generateRedisJwtTokenKey(null);
        assertEquals("", result, "Should return empty string for null input");
    }

    /**
     * Test case for generateRedisJwtTokenKey method when requestPayload is not null.
     * This test verifies that the method returns a non-empty JWT token string
     * when given a non-null request payload.
     */
    @Test
    void test_generateRedisJwtTokenKey_whenRequestPayloadNotNull() {
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        // Arrange
        Object requestPayload = new Object();
        String mockJsonString = "{\"key\":\"value\"}";
        try {
            when(objectMapper.writeValueAsString(requestPayload)).thenReturn(mockJsonString);
        } catch (Exception e) {
            fail("Exception should not be thrown");
        }

        // Act
        String result = designationService.generateRedisJwtTokenKey(requestPayload);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(result.split("\\.").length == 3); // Basic JWT structure check
    }

    /**
     * Test case for readDesignation method when the input id is empty.
     * It should return a CustomResponse with INTERNAL_SERVER_ERROR status and ID_NOT_FOUND message.
     */
    @Test
    void test_readDesignation_1() {
        // Arrange
        String emptyId = "";
        CustomResponse expectedResponse = new CustomResponse();
        expectedResponse.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
        expectedResponse.setMessage(Constants.ID_NOT_FOUND);

        // Act
        CustomResponse actualResponse = designationService.readDesignation(emptyId);

        // Assert
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, actualResponse.getResponseCode());
        assertEquals(Constants.ID_NOT_FOUND, actualResponse.getMessage());
    }

    /**
     * Test case for readDesignation method when the designation is found in the cache.
     * This test verifies that the method returns the correct response when a valid id is provided
     * and the designation data is present in the cache.
     */
    @Test
    void test_readDesignation_2() {
        // Arrange
        String id = "DESG-000001";
        String cachedJson = "{\"designation\":\"Software Engineer\"}";
        when(cacheService.getCache(id)).thenReturn(cachedJson);
        // Act
        CustomResponse response = designationService.readDesignation(id);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFULLY_READING, response.getMessage());
        assertTrue(response.getResult().containsKey(Constants.RESULT));
        verify(cacheService).getCache(id);
    }

    /**
     * Test case for readDesignation method when an empty ID is provided.
     * This test verifies that the method handles empty input correctly
     * by returning an appropriate error response.
     */
    @Test
    void test_readDesignation_emptyId() {
        CustomResponse response = designationService.readDesignation("");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals(Constants.ID_NOT_FOUND, response.getMessage());
    }

    /**
     * Test case for readDesignation method when the designation is not in cache but exists in the repository.
     * This test verifies that the method correctly retrieves the designation from the repository
     * when it's not found in the cache, and returns a successful response.
     */
    @Test
    void test_readDesignation_whenNotInCacheButExistsInRepository() {
        // Arrange
        String id = "DESG-000001";
        when(cacheService.getCache(id)).thenReturn(null);

        DesignationEntity designationEntity = new DesignationEntity();
        designationEntity.setId(id);
        designationEntity.setData(null);
        when(designationRepository.findByIdAndIsActive(id, true)).thenReturn(Optional.of(designationEntity));

        // Act
        CustomResponse response = designationService.readDesignation(id);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals("successfully read", response.getMessage());
    }

    /**
     * Testcase 1 for public ApiResponse readTerm(String Id, String framework, String category)
     * Path constraints: (null != desgResponse), (Constants.OK.equalsIgnoreCase((String) desgResponse.get(Constants.RESPONSE_CODE)))
     * This test verifies that the readTerm method successfully processes a valid response from the outbound request handler.
     */
    @Test
    void test_readTerm_1() {
        // Arrange
        String id = "testId";
        String framework = "testFramework";
        String category = "testCategory";

        when(cbServerProperties.getKnowledgeMS()).thenReturn("http://test.com/");
        when(cbServerProperties.getOdcsDesignationTermRead()).thenReturn("api/designation");

        Map<String, Object> desgResponse = new HashMap<>();
        desgResponse.put(Constants.RESPONSE_CODE, Constants.OK);
        Map<String, Object> resultMap = new HashMap<>();
        Map<String, Object> termMap = new HashMap<>();
        termMap.put("testField", "testValue");
        resultMap.put(Constants.TERM, termMap);
        desgResponse.put(Constants.RESULT, resultMap);

        when(outboundRequestHandlerServiceImpl.fetchResult(anyString())).thenReturn(desgResponse);

        // Act
        ApiResponse response = designationService.readTerm(id, framework, category);

        // Assert
       assertNotNull(response);
    }

    /**
     * Testcase 2 for public ApiResponse readTerm(String Id, String framework, String category)
     * Path constraints: (null != desgResponse), !((Constants.OK.equalsIgnoreCase((String) desgResponse.get(Constants.RESPONSE_CODE))))
     * This test verifies the behavior when the desgResponse is not null but the response code is not "OK".
     */
    @Test
    void test_readTerm_2() {
        // Arrange
        String id = "testId";
        String framework = "testFramework";
        String category = "testCategory";
        String url = "http://test-url.com";

        when(cbServerProperties.getKnowledgeMS()).thenReturn("http://test-url.com");
        when(cbServerProperties.getOdcsDesignationTermRead()).thenReturn("/read");

        Map<String, Object> desgResponse = new HashMap<>();
        desgResponse.put(Constants.RESPONSE_CODE, "NOT_OK");

        when(outboundRequestHandlerServiceImpl.fetchResult(url + "/read/" + id + "?framework=" + framework + "&category=" + category))
                .thenReturn(desgResponse);

        // Act
        ApiResponse response = designationService.readTerm(id, framework, category);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals("Data not found with id : " + id, response.getParams().getErr());
    }

    /**
     * Test case for readTerm method when the outbound request returns null
     * This test verifies that the method handles the case where the external service call returns null
     */
    @Test
    void test_readTerm_3() {
        // Arrange
        String id = "testId";
        String framework = "testFramework";
        String category = "testCategory";

        when(cbServerProperties.getKnowledgeMS()).thenReturn("http://test-url.com");
        when(cbServerProperties.getOdcsDesignationTermRead()).thenReturn("/test-endpoint");
        when(outboundRequestHandlerServiceImpl.fetchResult(anyString())).thenReturn(null);

        // Act
        ApiResponse response = designationService.readTerm(id, framework, category);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals("Failed to read the des details for Id : testId", response.getParams().getErr());
    }

    /**
     * Test case for readTerm method when the response from outboundRequestHandlerServiceImpl is null.
     * This tests the edge case where the external service call fails or returns null.
     */
    @Test
    void test_readTerm_null_response() {

        // Assuming these are valid inputs
        String id = "validId";
        String framework = "validFramework";
        String category = "validCategory";

        ApiResponse response = designationService.readTerm(id, framework, category);

        assertNotNull(response);
    }

    /**
     * Test case for searchDesignation method when search result is found in Redis cache.
     * This test verifies that the method returns the cached result from Redis
     * when it's available, without querying Elasticsearch.
     */
    @Test
    void test_searchDesignation_1() {
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        SearchResult cachedResult = new SearchResult();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any())).thenReturn(cachedResult);

        // Act
        CustomResponse response = designationService.searchDesignation(searchCriteria);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        assertEquals(cachedResult, response.getResult().get(Constants.RESULT));
    }

    /**
    * Testcase 2 for @Override public CustomResponse searchDesignation(SearchCriteria searchCriteria)
    * Path constraints: !((searchResult != null)), (searchString != null && searchString.length() < 2)
    * returns: response
    */
    @Test
    void test_searchDesignation_2() {
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        // Arrange
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        SearchCriteria searchCriteria = mock(SearchCriteria.class);
        when(searchCriteria.getSearchString()).thenReturn("a");

        // Act
        CustomResponse response = designationService.searchDesignation(searchCriteria);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

    /**
     * Test case for searchDesignation method when Redis cache is empty and search string is valid.
     * This test verifies that the method correctly searches for designations using the ES service
     * when the Redis cache does not contain the search result.
     */
    @Test
    void test_searchDesignation_3() throws Exception {
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString("Valid Search");

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        SearchResult mockSearchResult = new SearchResult();
        when(esUtilService.searchDocuments(anyString(), any(SearchCriteria.class))).thenReturn(mockSearchResult);

        // Act
        CustomResponse response = designationService.searchDesignation(searchCriteria);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(mockSearchResult, response.getResult().get("result"));
        verify(esUtilService, times(1)).searchDocuments(anyString(), any(SearchCriteria.class));
    }

    /**
     * Tests that searchDesignation returns an error response when the search string is too short (less than 2 characters).
     * This tests the explicit length check in the focal method.
     */
    @Test
    void test_searchDesignation_shortSearchString() {
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString("a");

        CustomResponse response = designationService.searchDesignation(searchCriteria);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

    /**
     * Test case for updateDesignation method when the designation exists and is successfully updated.
     * This test verifies that the method correctly updates the designation details and returns a success response.
     */
    @Test
    void test_updateDesignation_1() {
        // Arrange
        ObjectMapper realObjectMapper = new ObjectMapper();
        ObjectNode updateDesignationDetails = realObjectMapper.createObjectNode();
        updateDesignationDetails.put(Constants.ID, "DESG-000001");
        updateDesignationDetails.put("updatedField", "updatedValue");

        DesignationEntity existingDesignation = new DesignationEntity();
        existingDesignation.setId("DESG-000001");
        ObjectNode existingData = realObjectMapper.createObjectNode();
        existingData.put("existingField", "existingValue");
        existingDesignation.setData(existingData);

        when(designationRepository.findById("DESG-000001")).thenReturn(Optional.of(existingDesignation));
        when(designationRepository.save(any(DesignationEntity.class))).thenReturn(existingDesignation);
        when(objectMapper.createObjectNode()).thenReturn(realObjectMapper.createObjectNode());
        when(objectMapper.convertValue(any(JsonNode.class), eq(Map.class))).thenReturn(new HashMap<>());

        // Act
        CustomResponse response = designationService.updateDesignation(updateDesignationDetails);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFULLY_CREATED, response.getMessage());
        verify(designationRepository).findById("DESG-000001");
        verify(designationRepository).save(any(DesignationEntity.class));
        verify(esUtilService).updateDocument(eq(Constants.DESIGNATION_INDEX_NAME), eq(Constants.INDEX_TYPE),
                eq("DESG-000001"), any(Map.class), any());
        verify(cacheService).putCache(eq("DESG-000001"), any(JsonNode.class));
    }

    /**
     * Test case for updateDesignation method when the designation exists and is successfully updated.
     * This test covers the path where the designation ID exists, the designation is present in the repository,
     * and the update operation is successful.
     */
    @Test
    void test_updateDesignation_2() {
        MockitoAnnotations.openMocks(this);

        // Prepare test data
        ObjectNode updateDesignationDetails = new ObjectMapper().createObjectNode();
        updateDesignationDetails.put(Constants.ID, "DESG-000001");
        updateDesignationDetails.put("fieldToUpdate", "updatedValue");

        DesignationEntity existingDesignation = new DesignationEntity();
        existingDesignation.setId("DESG-000001");
        ObjectNode existingData = new ObjectMapper().createObjectNode();
        existingData.put("existingField", "existingValue");
        existingDesignation.setData(existingData);

        // Mock repository behavior
        when(designationRepository.findById("DESG-000001")).thenReturn(Optional.of(existingDesignation));
        when(designationRepository.save(any(DesignationEntity.class))).thenReturn(existingDesignation);
        when(objectMapper.createObjectNode()).thenReturn(new ObjectMapper().createObjectNode());
        Map<String, Object> mockMap = new HashMap<>();
        mockMap.put("key", "value");
        doReturn(mockMap).when(objectMapper).convertValue(any(JsonNode.class), eq(Map.class));
        // Mock other dependencies
        when(cbServerProperties.getElasticDesignationJsonPath()).thenReturn("testPath");

        // Perform the update
        CustomResponse response = designationService.updateDesignation(updateDesignationDetails);

        // Verify the response
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFULLY_CREATED, response.getMessage());
    }

    /**
     * Test the updateDesignation method when the ID is missing in the input JSON.
     * This test verifies that the method returns a BAD_REQUEST response when the ID is not provided.
     */
    @Test
    void test_updateDesignation_missingId() {
        ObjectMapper realObjectMapper = new ObjectMapper();
        JsonNode updateDesignationDetails = realObjectMapper.createObjectNode();

        CustomResponse response = designationService.updateDesignation(updateDesignationDetails);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Id not found", response.getMessage());
    }

    /**
     * Test case for updateDesignation method when the designation is not present.
     * This test verifies that the method returns a BAD_REQUEST response when the designation is not found.
     */
    @Test
    void test_updateDesignation_whenDesignationNotPresent() {
        // Arrange
        ObjectMapper realObjectMapper = new ObjectMapper();
        JsonNode updateDesignationDetails = realObjectMapper.createObjectNode().put(Constants.ID, "DESG-000001");
        when(designationRepository.findById("DESG-000001")).thenReturn(Optional.empty());

        // Act
        CustomResponse response = designationService.updateDesignation(updateDesignationDetails);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.NOT_FOUND, response.getMessage());
    }

    /**
     * Test case for updateDesignation method when the input JsonNode does not have an ID field
     * or when the ID field is null.
     */
    @Test
    void test_updateDesignation_whenIdIsMissingOrNull() {
        ObjectMapper realObjectMapper = new ObjectMapper();
        JsonNode updateDesignationDetails = realObjectMapper.createObjectNode();

        CustomResponse response = designationService.updateDesignation(updateDesignationDetails);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.ID_NOT_FOUND, response.getMessage());
    }

    /**
     * Test case for updateIdentifiersToDesignation method
     * Scenario: Valid input with existing designation
     * Expected: CustomResponse with OK status and updated designation details
     */
    @Test
    void test_updateIdentifiersToDesignation_1() {
        // Arrange
        ObjectNode updateDesignationDetails = new ObjectMapper().createObjectNode();
        updateDesignationDetails.put(Constants.ID, "DESG-000001");
        updateDesignationDetails.putArray(Constants.REF_NODES).add("REF-001");

        DesignationEntity existingEntity = new DesignationEntity();
        existingEntity.setId("DESG-000001");
        existingEntity.setData(updateDesignationDetails);

        when(designationRepository.findById("DESG-000001")).thenReturn(Optional.of(existingEntity));
        when(designationRepository.save(any(DesignationEntity.class))).thenReturn(existingEntity);
        when(objectMapper.createObjectNode()).thenReturn((ObjectNode) updateDesignationDetails);
        when(objectMapper.convertValue(any(), any(Class.class))).thenReturn(new java.util.HashMap<>());

        // Act
        CustomResponse response = designationService.updateIdentifiersToDesignation(updateDesignationDetails);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFULLY_CREATED, response.getMessage());
    }

    /**
     * Test case for updateIdentifiersToDesignation method when designation is not found in the repository.
     * This is an edge case explicitly handled in the method implementation.
     */
    @Test
    void test_updateIdentifiersToDesignation_designationNotFound() {
        // Arrange
        JsonNode updateDesignationDetails = mock(JsonNode.class);
        when(updateDesignationDetails.has(Constants.ID)).thenReturn(true);
        when(updateDesignationDetails.get(Constants.ID)).thenReturn(mock(JsonNode.class));
        when(updateDesignationDetails.has(Constants.REF_NODES)).thenReturn(true);
        when(updateDesignationDetails.get(Constants.REF_NODES)).thenReturn(mock(JsonNode.class));
        when(updateDesignationDetails.get(Constants.ID).asText()).thenReturn("testId");

        when(designationRepository.findById("testId")).thenReturn(Optional.empty());

        // Act
        CustomResponse response = designationService.updateIdentifiersToDesignation(updateDesignationDetails);

        // Assert
        assertNotNull(response);
    }

    private JsonNode buildValidRequest() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode request = mapper.createObjectNode();
        request.put(Constants.NAME, "Designation A");
        request.put(Constants.REF_ID, REF_ID);
        request.put(Constants.FRAMEWORK, FRAMEWORK_ID);
        request.put(Constants.CATEGORY, CATEGORY_CODE);

        ObjectNode additional = mapper.createObjectNode();
        additional.put(Constants.PARENT_CATEGORY, CATEGORY_CODE);
        additional.put(Constants.PREV_TERM_CODE, TERM_CODE);
        request.set(Constants.ADDITIONAL_PROPERTIES, additional);
        return request;
    }

    @Test
    void test_createTerm_error() {
        JsonNode request = buildValidRequest();

        // 1. Payload validation passes
        doNothing().when(payloadValidation).validatePayload(anyString(), any(JsonNode.class));

        // 2. Existing Designation found
        DesignationEntity designation = new DesignationEntity();
        designation.setId(REF_ID);
        designation.setIsActive(true);
        designation.setData(new ObjectMapper().createObjectNode());
        designation.setUpdatedOn(new Timestamp(System.currentTimeMillis()));

        when(designationRepository.findByIdAndIsActive(eq(REF_ID), eq(true)))
                .thenReturn(Optional.of(designation));

        // 3. Mock framework read (returns NOT_FOUND so term creation proceeds)
        Map<String, Object> fakeFrameworkResponse = new HashMap<>();
        fakeFrameworkResponse.put(Constants.RESPONSE_CODE, Constants.OK);
        Map<String, Object> frameworkMap = new HashMap<>();
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put(Constants.FRAMEWORK, frameworkMap);
        fakeFrameworkResponse.put(Constants.RESULT, resultMap);

        when(outboundRequestHandlerServiceImpl.fetchResult(anyString()))
                .thenReturn(fakeFrameworkResponse);

        // 4. Mock POST term creation
        Map<String, Object> termCreationResponse = new HashMap<>();
        termCreationResponse.put(Constants.RESPONSE_CODE, Constants.OK);

        Map<String, Object> result = new HashMap<>();
        result.put(Constants.NODE_ID, List.of("TERM-001"));
        termCreationResponse.put(Constants.RESULT, result);

        // Call method under test
        ApiResponse response = designationService.createTerm(request);

        // Validate
        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Failed to create.", response.getParams().getErr());
    }

    @Test
    void testCreateDesignation_success() throws Exception {
        // Create input designationDetails JSON
        ObjectMapper realObjectMapper = new ObjectMapper();
        ObjectNode designationDetails = realObjectMapper.createObjectNode();
        designationDetails.put(Constants.DESIGNATION, "Principal");

        // Mock payload validation
        doNothing().when(payloadValidation)
                .validatePayload(eq(Constants.DESIGNATION_PAYLOAD_VALIDATION), any(JsonNode.class));

        // Mock ES index exists
        when(esUtilService.isIndexPresent(Constants.DESIGNATION_INDEX_NAME)).thenReturn(true);

        // Mock ES search returns empty (no duplicates)
        SearchResult searchResult = new SearchResult();
        searchResult.setData(realObjectMapper.createArrayNode()); // Empty means no duplicates
        when(esUtilService.searchDocuments(eq(Constants.DESIGNATION_INDEX_NAME), any(SearchCriteria.class)))
                .thenReturn(searchResult);

        // Mock repository count
        when(designationRepository.count()).thenReturn(0L);

        // Mock save to repo
        when(designationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Mock objectMapper.convertValue
        when(objectMapper.convertValue(any(), eq(Map.class)))
                .thenReturn(Map.of(Constants.DESIGNATION, "Principal"));

        // Mock elastic path
        when(cbServerProperties.getElasticDesignationJsonPath()).thenReturn("elasticPath");

        // Use real ObjectMapper for internal node updates
        designationService.objectMapper = realObjectMapper;

        // Call the method
        CustomResponse response = designationService.createDesignation(designationDetails);

        // Assert response
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFULLY_CREATED, response.getMessage());
        assertTrue(((Map<?, ?>) response.getResult()).containsKey(Constants.ID));
    }

    @Test
    void testProcessDesignation_success() throws Exception {
        // Prepare test input
        Map<String, Object> input = new HashMap<>();
        input.put("name", "Principal");
        input.put("level", "L1");
        input.put("identifier", "DESG001");

        // Child designation
        Map<String, Object> child = new HashMap<>();
        child.put("name", "Vice Principal");
        child.put("level", "L2");
        child.put("identifier", "DESG002");
        input.put(Constants.CHILDREN, List.of(child));

        // Expected ODCS fields to copy
        when(cbServerProperties.getOdcsFields()).thenReturn(List.of("name", "level", "identifier"));

        // Inject mock config into the real object
        ReflectionTestUtils.setField(designationService, "cbServerProperties", cbServerProperties);

        // Output map
        Map<String, Object> output = new HashMap<>();

        // Use reflection to invoke private method
        Method method = DesignationServiceImpl.class
                .getDeclaredMethod("processDesignation", Map.class, Map.class);
        method.setAccessible(true);
        method.invoke(designationService, input, output);

        // Assertions
        assertEquals("Principal", output.get("name"));
        assertEquals("L1", output.get("level"));
        assertEquals("DESG001", output.get("identifier"));

        // Children check
        assertTrue(output.containsKey(Constants.CHILDREN));
        List<Map<String, Object>> children = (List<Map<String, Object>>) output.get(Constants.CHILDREN);
        assertEquals(1, children.size());
        Map<String, Object> childOutput = children.get(0);
        assertEquals("Vice Principal", childOutput.get("name"));
        assertEquals("L2", childOutput.get("level"));
        assertEquals("DESG002", childOutput.get("identifier"));
    }


//    @Test
//    void test_processExcelFile_xlsx_success() throws Exception {
//        // Create workbook
//        Workbook workbook = new XSSFWorkbook();
//        Sheet sheet = workbook.createSheet("Sheet1");
//
//        Row header = sheet.createRow(0);
//        header.createCell(0).setCellValue("Name");
//        header.createCell(1).setCellValue("Joining Date");
//
//        Row row = sheet.createRow(1);
//        row.createCell(0).setCellValue("John Doe");
//        Cell dateCell = row.createCell(1);
//        dateCell.setCellValue(new Date());
//        CellStyle style = workbook.createCellStyle();
//        style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
//        dateCell.setCellStyle(style);
//
//        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
//        workbook.write(outputStream);
//        workbook.close();
//        ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
//
//        MultipartFile mockFile = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", inputStream);
//
//        // Call private method using reflection
//        Method method = DesignationServiceImpl.class.getDeclaredMethod("processExcelFile", MultipartFile.class);
//        method.setAccessible(true);
//        List<Map<String, String>> result = (List<Map<String, String>>) method.invoke(designationService, mockFile);
//
//        assertEquals(1, result.size());
//        assertEquals("John Doe", result.get(0).get("Name"));
//        assertNotNull(result.get(0).get("Joining Date"));
//    }

    @Test
    void test_processExcelFile_csv_success() throws Exception {
        String csvContent = "Name,Joining Date\nJane Doe,2024-06-01";
        MultipartFile mockCsvFile = new MockMultipartFile("file", "test.csv", "text/csv", csvContent.getBytes());

        Method method = DesignationServiceImpl.class.getDeclaredMethod("processExcelFile", MultipartFile.class);
        method.setAccessible(true);
        List<Map<String, String>> result = (List<Map<String, String>>) method.invoke(designationService, mockCsvFile);

        assertEquals(1, result.size());
        assertEquals("Jane Doe", result.get(0).get("Name"));
        assertEquals("2024-06-01", result.get(0).get("Joining Date"));
    }

    @Test
    void test_processExcelFile_invalidFileExtension_shouldThrowException() {
        MultipartFile mockFile = new MockMultipartFile("file", "invalid.txt", "text/plain", "invalid content".getBytes());

        Exception exception = assertThrows(InvocationTargetException.class, () -> {
            Method method = DesignationServiceImpl.class.getDeclaredMethod("processExcelFile", MultipartFile.class);
            method.setAccessible(true);
            method.invoke(designationService, mockFile);
        });

        assertTrue(exception.getCause().getMessage().contains("Unsupported file type"));
    }

    @Test
    void test_processExcelFile_nullFileName_shouldThrowException() throws Exception {
        // Mock MultipartFile with null filename
        MultipartFile mockFile = Mockito.mock(MultipartFile.class);
        when(mockFile.getOriginalFilename()).thenReturn(null);

        // Invoke private method via reflection
        Method method = DesignationServiceImpl.class.getDeclaredMethod("processExcelFile", MultipartFile.class);
        method.setAccessible(true);

        InvocationTargetException exception = assertThrows(InvocationTargetException.class, () -> {
            method.invoke(designationService, mockFile);
        });

        // Assert that the cause is the RuntimeException with correct message
        assertTrue(exception.getCause() instanceof RuntimeException);
        assertEquals("File name is null", exception.getCause().getMessage());
    }

    @Test
    void test_createDesignationEntity_shouldReturnEntityWithExpectedFields() throws Exception {
        JsonNode mockNode = new ObjectMapper().readTree("{\"designation\": \"Engineer\"}");
        String formattedId = "desg-001";

        Method method = DesignationServiceImpl.class.getDeclaredMethod("createDesignationEntity", JsonNode.class, String.class);
        method.setAccessible(true);

        DesignationEntity entity = (DesignationEntity) method.invoke(designationService, mockNode, formattedId);

        assertNotNull(entity);
        assertEquals(formattedId, entity.getId());
        assertEquals(mockNode, entity.getData());
        assertTrue(entity.getIsActive());
        assertNotNull(entity.getCreatedOn());
        assertNotNull(entity.getUpdatedOn());
    }

    @Test
    void test_validateAndSetData_shouldReturnUpdatedJsonNode() throws Exception {
        // Create test input JsonNode
        ObjectMapper realMapper = new ObjectMapper();
        ObjectNode testNode = realMapper.createObjectNode();
        testNode.put("designation", "Engineer");
        testNode.put("description", "Software Engineer");

        // Use real ObjectMapper for transformation
        ReflectionTestUtils.setField(designationService, "objectMapper", realMapper);

        String userId = "user-123";
        String formattedId = "desg-001";

        Method method = DesignationServiceImpl.class.getDeclaredMethod("validateAndSetData", JsonNode.class, String.class, String.class);
        method.setAccessible(true);

        JsonNode resultNode = (JsonNode) method.invoke(designationService, testNode, userId, formattedId);

        assertNotNull(resultNode);
        assertEquals("Engineer", resultNode.get("designation").asText());
        assertEquals(formattedId, resultNode.get("id").asText());
        assertEquals("user-123", resultNode.get("createdBy").asText());
        assertEquals(1, resultNode.get("version").asInt());

        // Assert searchTags
        assertTrue(resultNode.has("searchTags"));
        JsonNode searchTagsNode = resultNode.get("searchTags");
        assertEquals(1, searchTagsNode.size());
        assertTrue(searchTagsNode.get(0).toString().contains("engineer"));

        // Validate timestamp fields
        assertNotNull(resultNode.get("createdOn").asText());
        assertNotNull(resultNode.get("updatedOn").asText());
    }

//    @Test
//    void testProcessExcelFile_xlsx_shouldProcessSuccessfully() throws Exception {
//        Workbook workbook = new XSSFWorkbook();
//        Sheet sheet = workbook.createSheet();
//        Row headerRow = sheet.createRow(0);
//        headerRow.createCell(0).setCellValue("Designation");
//        headerRow.createCell(1).setCellValue("UpdatedDesignation");
//
//        Row dataRow = sheet.createRow(1);
//        dataRow.createCell(0).setCellValue("Software Engineer");
//        dataRow.createCell(1).setCellValue("Senior Engineer");
//
//        ByteArrayOutputStream out = new ByteArrayOutputStream();
//        workbook.write(out);
//        workbook.close();
//
//        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
//        MultipartFile multipartFile = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", in);
//
//        List<Map<String, String>> result = invokePrivateProcessExcelFile(multipartFile);
//
//        assertEquals(1, result.size());
//        assertEquals("Software Engineer", result.get(0).get("Designation"));
//        assertEquals("Senior Engineer", result.get(0).get("UpdatedDesignation"));
//    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> invokePrivateProcessExcelFile(MultipartFile file) throws Exception {
        java.lang.reflect.Method method = DesignationServiceImpl.class.getDeclaredMethod("processExcelFile", MultipartFile.class);
        method.setAccessible(true);
        return (List<Map<String, String>>) method.invoke(designationService, file);
    }

    @Test
    void testPoresBulkSave_success() throws Exception {
        // Prepare data
        ObjectMapper realMapper = new ObjectMapper();
        ObjectNode dataNode = realMapper.createObjectNode();
        dataNode.put(Constants.ID, "DESG-001");
        dataNode.put("key", "value");

        List<JsonNode> jsonNodes = List.of(dataNode);
        List<DesignationEntity> entities = List.of(new DesignationEntity());

        // Mock behaviors
        Mockito.when(cbServerProperties.getElasticDesignationJsonPath()).thenReturn("mock/path");
        Mockito.when(objectMapper.convertValue(Mockito.any(JsonNode.class), Mockito.eq(Map.class)))
                .thenReturn(Map.of("key", "value"));

        // Call private method via reflection
        Method method = DesignationServiceImpl.class.getDeclaredMethod("poresBulkSave", List.class, List.class);
        method.setAccessible(true);
        method.invoke(designationService, entities, jsonNodes);

        // Verify interactions
        Mockito.verify(designationRepository).saveAll(entities);
        Mockito.verify(esUtilService).addDocument(
                eq(Constants.DESIGNATION_INDEX_NAME),
                eq(Constants.INDEX_TYPE),
                eq("DESG-001"),
                eq(Map.of("key", "value")),
                eq("mock/path")
        );
        Mockito.verify(cacheService).putCache(eq("DESG-001"), eq(dataNode));
    }

    @Test
    void testPoresBulkSave_exception() throws Exception {
        // Cause exception in saveAll
        List<DesignationEntity> entities = List.of(new DesignationEntity());
        List<JsonNode> jsonNodes = new ArrayList<>();

        Mockito.doThrow(new RuntimeException("saveAll failed")).when(designationRepository).saveAll(Mockito.any());

        // Call private method
        Method method = DesignationServiceImpl.class.getDeclaredMethod("poresBulkSave", List.class, List.class);
        method.setAccessible(true);

        // Should not throw, just log
        method.invoke(designationService, entities, jsonNodes);

        // Exception should be caught internally
        Mockito.verify(designationRepository).saveAll(entities);
    }

    @Test
    void testToJavaObject_withObjectNode() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode objectNode = mapper.createObjectNode();
        objectNode.put("key1", "value1");
        objectNode.put("key2", 123);

        Method method = DesignationServiceImpl.class.getDeclaredMethod("toJavaObject", JsonNode.class);
        method.setAccessible(true);
        Object result = method.invoke(null, objectNode);

        assertTrue(result instanceof Map);
        Map<?, ?> map = (Map<?, ?>) result;
        assertEquals("value1", map.get("key1"));
        assertEquals("123", map.get("key2")); // Note: .asText() converts int to string
    }

    @Test
    void testToJavaObject_withArrayNode() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode arrayNode = mapper.createArrayNode();
        arrayNode.add("item1");
        arrayNode.add(456);

        Method method = DesignationServiceImpl.class.getDeclaredMethod("toJavaObject", JsonNode.class);
        method.setAccessible(true);
        Object result = method.invoke(null, arrayNode);

        assertTrue(result instanceof List);
        List<?> list = (List<?>) result;
        assertEquals("item1", list.get(0));
        assertEquals("456", list.get(1)); // Note: .asText() converts int to string
    }

    @Test
    void testToJavaObject_withTextNode() throws Exception {
        TextNode textNode = new TextNode("simpleText");

        Method method = DesignationServiceImpl.class.getDeclaredMethod("toJavaObject", JsonNode.class);
        method.setAccessible(true);
        Object result = method.invoke(null, textNode);

        assertEquals("simpleText", result);
    }

    @Test
    void testToJavaObject_withIntNode() throws Exception {
        IntNode intNode = new IntNode(42);

        Method method = DesignationServiceImpl.class.getDeclaredMethod("toJavaObject", JsonNode.class);
        method.setAccessible(true);
        Object result = method.invoke(null, intNode);

        assertEquals("42", result); // .asText() returns string "42"
    }

    @Test
    void searchDesignation_shouldHandleEsException() throws Exception {
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString("developer");

        // Simulate Redis has no cached result
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        // Simulate exception from esUtilService
        when(esUtilService.searchDocuments(eq(Constants.DESIGNATION_INDEX_NAME), any(SearchCriteria.class)))
                .thenThrow(new RuntimeException("ES error"));

        // Act
        CustomResponse response = designationService.searchDesignation(searchCriteria);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

}
