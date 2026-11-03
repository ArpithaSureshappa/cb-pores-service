package com.igot.cb.cios.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.cios.dto.ObjectDto;
import com.igot.cb.cios.entity.CiosContentEntity;
import com.igot.cb.cios.repository.CiosRepository;
import com.igot.cb.cios.util.CiosRequestPayloadValidation;
import com.igot.cb.contentpartner.repository.ContentPartnerRepository;
import com.igot.cb.contentpartner.service.ContentPartnerService;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Stream;

import com.igot.cb.pores.util.PayloadValidation;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.AssertionsForClassTypes.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class CiosContentServiceImplTest {

    @Mock
    private CacheService cacheService;

    @Mock
    private CbServerProperties cbServerProperties;

    @InjectMocks
    private CiosContentServiceImpl ciosContentService;

    @Mock
    private CiosRepository ciosRepository;

    @Mock
    private EsUtilService esUtilService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private RedisTemplate<String, SearchResult> redisTemplate;

    @Mock
    private ValueOperations<String, SearchResult> valueOperations;

    @Mock
    private PayloadValidation payloadValidation;
    @Mock
    private CiosRequestPayloadValidation ciosRequestPayloadValidation;

    @Mock
    private ContentPartnerService contentPartnerService;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private ResponseEntity<JsonNode> responseEntity;

    @Mock
    private ContentPartnerRepository contentPartnerRepository;

    private ObjectMapper realObjectMapper = new ObjectMapper();


    private JsonNode createMockContentData() {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.createObjectNode().set("content", mapper.createObjectNode());
    }

    // Helper methods to create mock JsonNodes
    private JsonNode createMockJsonNode() {
        return new ObjectMapper().createObjectNode();
    }

    /**
     * Test case for deleteContent method when the content is not found
     * This test verifies that a CustomException is thrown with the correct error message and HTTP status
     * when attempting to delete a content that does not exist or is not active
     */
    @Test
    void test_deleteContent_2() {
        // Arrange
        String contentId = "non_existent_id";
        when(ciosRepository.findByContentIdAndIsActive(contentId, true)).thenReturn(Optional.empty());

        // Act & Assert
        CustomException exception = assertThrows(CustomException.class, () -> {
            ciosContentService.deleteContent(contentId);
        });

        assertEquals(Constants.ERROR, exception.getCode());
        assertEquals(Constants.NO_DATA_FOUND, exception.getMessage());
        assertEquals(HttpStatus.NOT_FOUND, exception.getHttpStatusCode());
    }

    /**
     * Test deleteContent method when the content is not found in the repository.
     * This test verifies that a CustomException is thrown with the appropriate error message and HTTP status.
     */
    @Test
    void test_deleteContent_contentNotFound() {
        String contentId = "non_existent_id";
        when(ciosRepository.findByContentIdAndIsActive(contentId, true)).thenReturn(Optional.empty());

        CustomException exception = assertThrows(CustomException.class, () -> {
            ciosContentService.deleteContent(contentId);
        });

        assertEquals(Constants.ERROR, exception.getCode());
        assertEquals(Constants.NO_DATA_FOUND, exception.getMessage());
        assertEquals(HttpStatus.NOT_FOUND, exception.getHttpStatusCode());
    }

    /**
     * Test case for fetchDataByContentId method when contentId is empty and cached JSON is not empty.
     * This test verifies that the method throws a CustomException when the contentId is empty,
     * and does not proceed to check the cache or database.
     */
    @Test
    void test_fetchDataByContentId_1() throws JsonProcessingException {
        // Arrange
        String contentId = "";
        // Act & Assert
        CustomException exception = assertThrows(CustomException.class, () -> {
            ciosContentService.fetchDataByContentId(contentId);
        });

        assertEquals(Constants.ERROR, exception.getCode());
        assertEquals("contentId is mandatory", exception.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatusCode());

        // Verify that the cache was not accessed
        verify(cacheService, never()).getCache(anyString());
        verify(objectMapper, never()).readValue(anyString(), any(TypeReference.class));
    }

    /**
     * Test case for fetchDataByContentId method when the contentId is not empty,
     * but the cache is empty and the content is not found in the repository.
     * This should throw a CustomException with the appropriate error message.
     */
    @Test
    void test_fetchDataByContentId_3() {
        String contentId = "validContentId";

        when(cacheService.getCache(contentId)).thenReturn(null);
        when(ciosRepository.findByContentIdAndIsActive(contentId, true)).thenReturn(Optional.empty());

        CustomException exception = assertThrows(CustomException.class, () -> {
            ciosContentService.fetchDataByContentId(contentId);
        });

        assertEquals("ERROR", exception.getCode());
        assertEquals("No data found for given Id", exception.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatusCode());

        verify(cacheService, times(1)).getCache(contentId);
        verify(ciosRepository, times(1)).findByContentId(contentId);
    }

    /**
     * Test that fetchDataByContentId throws CustomException when contentId is empty
     */
    @Test
    void test_fetchDataByContentId_emptyContentId() {
        CustomException exception = assertThrows(CustomException.class, () -> {
            ciosContentService.fetchDataByContentId("");
        });

        assertEquals(Constants.ERROR, exception.getCode());
        assertEquals("contentId is mandatory", exception.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatusCode());
    }

    /**
     * Test case for fetchDataByExternalIdAndPartnerId method
     * Scenario: Empty externalId is provided
     * Expected: CustomException is thrown with appropriate error message and HTTP status
     */
    @Test
    void test_fetchDataByExternalIdAndPartnerId_3() {
        // Arrange
        String externalId = "";
        String partnerId = "partner123";

        // Act & Assert
        CustomException exception = assertThrows(CustomException.class, () -> {
            ciosContentService.fetchDataByExternalIdAndPartnerId(externalId, partnerId);
        });

        assertEquals(Constants.ERROR, exception.getCode());
        assertEquals("externalid is mandatory", exception.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatusCode());
    }

    /**
     * Test case for fetchDataByExternalIdAndPartnerId method
     * Scenario: External ID is empty
     * Expected: Throws CustomException with "externalid is mandatory" message
     */
    @Test
    void test_fetchDataByExternalIdAndPartnerId_emptyExternalId() {
        String externalId = "";
        String partnerId = "partner123";

        assertThrows(CustomException.class, () -> {
            ciosContentService.fetchDataByExternalIdAndPartnerId(externalId, partnerId);
        }, "externalid is mandatory");
    }

    /**
     * Test that an exception is thrown when externalId is empty
     */
    @Test
    void test_fetchDataByExternalIdAndPartnerId_emptyExternalId_2() {
        CustomException exception = assertThrows(CustomException.class, () -> {
            ciosContentService.fetchDataByExternalIdAndPartnerId("", "partnerId");
        });

        assertEquals(Constants.ERROR, exception.getCode());
        assertEquals("externalid is mandatory", exception.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatusCode());
    }

    /**
     * Test case for fetchDataByExternalIdAndPartnerId method
     * Scenario: Valid external ID is provided and cached data exists
     * Expected: Method should return the cached response
     */
    @Test
    void test_fetchDataByExternalIdAndPartnerId_returnsCachedResponse(){
        String externalId = "test_external_id";
        String partnerId = "test_partner_id";
        String cacheKey = externalId + "_" + partnerId;
        String cachedJson = "{\"key\":\"value\"}";

        when(cacheService.getCache(cacheKey)).thenReturn(cachedJson);

        ciosContentService.fetchDataByExternalIdAndPartnerId(externalId, partnerId);

        verify(cacheService).getCache(cacheKey);
    }

    /**
     * Test case for generateId method
     * Verifies that the generated ID has the correct format and prefix
     */
    @Test
    void test_generateId_1() {
        String generatedId = ciosContentService.generateId();

        assertNotNull(generatedId);
        assertTrue(generatedId.startsWith(Constants.ID_PREFIX));

        String[] parts = generatedId.substring(Constants.ID_PREFIX.length()).split("");
        assertEquals(20, parts.length);

    }

    /**
     * Tests that the generateId method produces a unique ID even when called in 
     * rapid succession, verifying that the timestamp and atomic integer components
     * ensure uniqueness.
     */
    @Test
    void test_generateId_ensureUniqueness() {
        String id1 = ciosContentService.generateId();
        String id2 = ciosContentService.generateId();
        assertNotEquals("Generated IDs should be unique", id1, id2);
    }

    /**
     * Test case for onboardContent method when status is "draft" and all optional fields are present.
     * This test verifies that the method correctly processes content with draft status
     * and handles competencies, content partner, and tags data.
     */
    @Test
    void test_onboardContent_2() {
        List<ObjectDto> dataList = new ArrayList<>();
        ObjectDto objectDto = new ObjectDto();
        objectDto.setStatus("draft");

        JsonNode competenciesV5 = mock(JsonNode.class);
        JsonNode competenciesV6 = mock(JsonNode.class);
        JsonNode contentPartner = mock(JsonNode.class);
        List<String> tags = new ArrayList<>();

        objectDto.setCompetencies_v5(competenciesV5);
        objectDto.setCompetencies_v6(competenciesV6);
        objectDto.setContentPartner(contentPartner);
        objectDto.setTags(tags);

        JsonNode contentData = mock(JsonNode.class);
        objectDto.setContentData(contentData);

        dataList.add(objectDto);

        ApiResponse result = ciosContentService.onboardContent(dataList);

        assertNotNull(result);
        assertEquals(Constants.FAILED, result.getParams().getStatus());
    }

    /**
     * Test case for onboardContent method when status is "live" and all optional fields are present.
     * This test verifies that the method correctly processes a live content with competencies, content partner, and tags.
     */
    @Test
    void test_onboardContent_7() {
        // Arrange
        List<ObjectDto> dataList = new ArrayList<>();
        ObjectDto objectDto = new ObjectDto();
        objectDto.setStatus("live");

        // Mock competencies
        JsonNode competenciesV5 = mock(JsonNode.class);
        JsonNode competenciesV6 = mock(JsonNode.class);
        objectDto.setCompetencies_v5(competenciesV5);
        objectDto.setCompetencies_v6(competenciesV6);

        // Mock content partner
        JsonNode contentPartner = mock(JsonNode.class);
        when(contentPartner.get("partnerCode")).thenReturn(mock(JsonNode.class));
        when(contentPartner.get("partnerCode").asText()).thenReturn("testPartner");
        objectDto.setContentPartner(contentPartner);

        // Mock tags
        List<String> tags = new ArrayList<>();
        tags.add("tag1");
        tags.add("tag2");
        objectDto.setTags(tags);

        // Mock content data
        JsonNode contentData = mock(JsonNode.class);
        when(contentData.path("content")).thenReturn(mock(JsonNode.class));
        objectDto.setContentData(contentData);

        dataList.add(objectDto);

        // Act
        ApiResponse apiResponse = ciosContentService.onboardContent(dataList);

        // Assert
        assertEquals(Constants.FAILED, apiResponse.getParams().getStatus());
    }

    /**
     * Test case for onboardContent method when the status is "draft" and competencies and content partner are present,
     * but tags are not present.
     */
    @Test
    void testOnboardContent_successForDraftAndLive() {
        ObjectMapper draftObjectMapper = new ObjectMapper();
        // Prepare input DTO list
        List<ObjectDto> dataList = new ArrayList<>();
        ObjectDto objectDto = new ObjectDto();
        objectDto.setStatus("draft");

        JsonNode competenciesV5 = mock(JsonNode.class);
        JsonNode competenciesV6 = mock(JsonNode.class);
        JsonNode contentPartner = mock(JsonNode.class);
        JsonNode contentData = mock(JsonNode.class);

        objectDto.setCompetencies_v5(competenciesV5);
        objectDto.setCompetencies_v6(competenciesV6);
        objectDto.setContentPartner(contentPartner);
        objectDto.setContentData(contentData);

        dataList.add(objectDto);

        // Mock validations
        doNothing().when(payloadValidation).validatePayload(anyString(), any());
        doNothing().when(ciosRequestPayloadValidation).validateModel(any());

        // Mock content partner API response
        ApiResponse contentPartnerApiResp = new ApiResponse();
        Map<String, Object> partnerResult = new HashMap<>();
        Map<String, Object> partnerData = new HashMap<>();
        partnerData.put("someKey", "someValue");
        partnerResult.put("data", partnerData);
        contentPartnerApiResp.setResult(partnerResult);
        when(contentPartnerService.getContentDetailsByPartnerCode(any())).thenReturn(contentPartnerApiResp);

        // Mock repository save and lookup
        CiosContentEntity mockEntity = new CiosContentEntity();
        mockEntity.setContentId("CONTENT_ID");
        mockEntity.setCiosData(objectMapper.createObjectNode());
        mockEntity.setExternalId("extId");
        when(ciosRepository.findByExternalIdAndPartnerId(any(), any())).thenReturn(Optional.empty());
        when(ciosRepository.save(any())).thenReturn(mockEntity);
        when(objectMapper.convertValue(any(), eq(Map.class))).thenReturn(new HashMap<>());

        // Mock REST call for secondary DB update
        ObjectNode mockApiResponse = objectMapper.createObjectNode();
        when(restTemplate.exchange(
                not(contains("search")).toString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
        )).thenReturn(new ResponseEntity<>(mockApiResponse, HttpStatus.OK));

        // Mock REST call for search (status counts)
        ObjectNode searchResponse = draftObjectMapper.createObjectNode();
        searchResponse.put(Constants.TOTAL_COUNT, 5);

        ArrayNode statusFacets = draftObjectMapper.createArrayNode();
        ObjectNode draftNode = draftObjectMapper.createObjectNode();
        draftNode.put(Constants.VALUE, "draft");
        draftNode.put(Constants.COUNT, 2);
        ObjectNode liveNode = draftObjectMapper.createObjectNode();
        liveNode.put(Constants.VALUE, "live");
        liveNode.put(Constants.COUNT, 3);
        statusFacets.add(draftNode);
        statusFacets.add(liveNode);

        ObjectNode facetNode = draftObjectMapper.createObjectNode();
        facetNode.set(Constants.STATUS, statusFacets);
        searchResponse.set(Constants.FACETS, facetNode);

        when(restTemplate.exchange(
                contains("search"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
        )).thenReturn(new ResponseEntity<>(searchResponse, HttpStatus.OK));

        // Act
        ApiResponse response = ciosContentService.onboardContent(dataList);

        // Assert
        assertNotNull(response);
    }


    @Test
    void test_onboardContent_return500WithCompetenciesAndContentPartner() {
        // Arrange
        List<ObjectDto> dataList = new ArrayList<>();
        ObjectDto objectDto = new ObjectDto();
        objectDto.setStatus("draft");

        JsonNode competenciesV5 = mock(JsonNode.class);
        JsonNode competenciesV6 = mock(JsonNode.class);
        JsonNode contentPartner = mock(JsonNode.class);
        JsonNode contentData = mock(JsonNode.class);
        JsonNode contentNode = mock(ObjectNode.class);

        objectDto.setCompetencies_v5(competenciesV5);
        objectDto.setCompetencies_v6(competenciesV6);
        objectDto.setContentPartner(contentPartner);
        objectDto.setContentData(contentData);

        dataList.add(objectDto);

        JsonNode partnerCodeNode = mock(JsonNode.class);
        when(partnerCodeNode.asText()).thenReturn("testPartnerCode");
        when(contentPartner.get("partnerCode")).thenReturn(partnerCodeNode);

        when(contentData.path("content")).thenReturn(contentNode);
        when(((ObjectNode) contentNode).put(anyString(), anyString())).thenReturn((ObjectNode) contentNode);
        when(((ObjectNode) contentNode).put(anyString(), anyBoolean())).thenReturn((ObjectNode) contentNode);

        doNothing().when(payloadValidation).validatePayload(anyString(), any(JsonNode.class));

        // Act
        ApiResponse response = ciosContentService.onboardContent(dataList);

        // Assert
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    /**
     * Test case for onboardContent method with draft status, competencies_v5, contentPartner, and tags
     * This test verifies the behavior of onboardContent method when processing a draft content
     * with competencies_v5, contentPartner, and tags, but without competencies_v6.
     */
    @Test
    void test_onboardContent_draftWithCompetenciesV5AndTags() {
        // Arrange
        List<ObjectDto> data = new ArrayList<>();
        ObjectDto objectDto = new ObjectDto();
        objectDto.setStatus("draft");

        JsonNode competenciesV5 = objectMapper.createObjectNode();
        objectDto.setCompetencies_v5(competenciesV5);

        JsonNode contentPartner = objectMapper.createObjectNode();
        objectDto.setContentPartner(contentPartner);

        List<String> tags = new ArrayList<>();
        tags.add("test-tag");
        objectDto.setTags(tags);

        JsonNode contentData = objectMapper.createObjectNode();
        objectDto.setContentData(contentData);

        data.add(objectDto);

        // Act
        ApiResponse response = ciosContentService.onboardContent(data);

        // Assert
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    /**
     * Test case for onboardContent method with draft status and specific field conditions
     * This test verifies the behavior when processing a draft content with competencies_v5,
     * competencies_v6, and tags, but without content partner information.
     */
    @Test
    void test_onboardContent_draftWithSpecificFields() {
        // Arrange
        List<ObjectDto> dataList = new ArrayList<>();
        ObjectDto objectDto = new ObjectDto();
        objectDto.setStatus("draft");
        objectDto.setCompetencies_v5(createMockJsonNode());
        objectDto.setCompetencies_v6(createMockJsonNode());
        objectDto.setTags(List.of("tag1", "tag2"));
        objectDto.setContentData(createMockContentData());
        dataList.add(objectDto);

        // Act
        ApiResponse response = ciosContentService.onboardContent(dataList);

        // Assert
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }


    /**
     * Test case for searchCotent method when search result is found in Redis cache.
     * This test verifies that the method returns the cached search result from Redis
     * without performing a new search operation.
     */
    @Test
    void test_searchCotent_1() {
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        SearchResult expectedResult = new SearchResult();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(expectedResult);

        // Act
        SearchResult actualResult = ciosContentService.searchCotent(searchCriteria);

        // Assert
        assertNotNull(actualResult);
        assertEquals(expectedResult, actualResult);
        verify(redisTemplate).opsForValue();
        verify(valueOperations).get(anyString());
        verifyNoMoreInteractions(redisTemplate, valueOperations);
    }

    @Test
    void test_searchCotent_ShouldThrowException_WhenSearchCriteriaIsNull() {
        // Arrange
        SearchCriteria searchCriteria = null;

        // Act & Assert
        CustomException exception = assertThrows(CustomException.class, () -> {
            ciosContentService.searchCotent(searchCriteria);
        });

        // Verify exception details
        assertEquals("Search criteria must not be null", exception.getMessage());
        assertEquals("ERROR", exception.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatusCode());
    }

    /**
     * Test case for searchCotent method when the search result is not in Redis cache,
     * filterCriteriaMap is null, and isActive is not set in the filterCriteriaMap.
     * This test verifies that the method correctly handles these conditions and
     * performs the search using EsUtilService.
     */
    @Test
    void test_searchCotent_2() throws Exception {
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setFilterCriteriaMap(null);

        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        SearchResult expectedSearchResult = new SearchResult();
        when(esUtilService.searchDocuments(eq(Constants.CIOS_INDEX_NAME), any(SearchCriteria.class)))
                .thenReturn(expectedSearchResult);

        // Act
        SearchResult result = ciosContentService.searchCotent(searchCriteria);

        // Assert
        assertNotNull(result);
        assertEquals(expectedSearchResult, result);

        ArgumentCaptor<SearchCriteria> criteriaCaptor = ArgumentCaptor.forClass(SearchCriteria.class);
        verify(esUtilService).searchDocuments(eq(Constants.CIOS_INDEX_NAME), criteriaCaptor.capture());
        SearchCriteria passedCriteria = criteriaCaptor.getValue();
        HashMap<String, Object> filterMap = passedCriteria.getFilterCriteriaMap();
        assertNotNull(filterMap);
        assertEquals(true, filterMap.get("isActive"));

        verify(valueOperations).set(anyString(), eq(expectedSearchResult), anyLong(), any());
    }

    /**
     * Test case for searchCotent method when the result is not in Redis cache,
     * filterCriteriaMap is not null, and isActive is not present in the map.
     * It verifies that the method correctly adds isActive to the map and
     * performs the search using EsUtilService.
     */
    @Test
    void test_searchCotent_3() throws Exception {
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        HashMap<String, Object> filterCriteriaMap = new HashMap<>();
        searchCriteria.setFilterCriteriaMap(filterCriteriaMap);

        SearchResult expectedResult = new SearchResult();

        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        when(esUtilService.searchDocuments(eq(Constants.CIOS_INDEX_NAME), any(SearchCriteria.class))).thenReturn(expectedResult);

        // Act
        SearchResult result = ciosContentService.searchCotent(searchCriteria);

        // Assert
        assertNotNull(result);
        assertEquals(expectedResult, result);
        assertNotNull(searchCriteria.getFilterCriteriaMap());
        assertEquals(1, searchCriteria.getFilterCriteriaMap().size());
        assertEquals(true, searchCriteria.getFilterCriteriaMap().get("isActive"));

        verify(esUtilService).searchDocuments(eq(Constants.CIOS_INDEX_NAME), any(SearchCriteria.class));
        verify(valueOperations).set(anyString(), eq(expectedResult), anyLong(), any());
    }

    /**
     * Test case for searchCotent method when Redis cache is empty, filterCriteriaMap is null,
     * and isActive is not explicitly set.
     * This test verifies that the method correctly handles these conditions and returns
     * the search result from ElasticSearch with isActive set to true by default.
     */
    @Test
    void test_searchCotent_4() throws Exception {
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        SearchResult expectedResult = new SearchResult();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any())).thenReturn(null);
        when(esUtilService.searchDocuments(eq(Constants.CIOS_INDEX_NAME), any())).thenReturn(expectedResult);

        // Act
        SearchResult result = ciosContentService.searchCotent(searchCriteria);

        // Assert
        assertEquals(expectedResult, result);
    }

    /**
     * Test validatePayload method with invalid payload
     * This test ensures that the method throws a CustomException when the payload doesn't match the schema
     */
    @Test
    void test_validatePayload_invalidPayload() {
        JsonNode invalidPayload = objectMapper.createObjectNode();
        String schemaFile = "/schemas/valid_schema.json";
        CustomException exception = assertThrows(CustomException.class, 
            () -> ciosContentService.validatePayload(schemaFile, invalidPayload));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatusCode());
    }

    /**
     * Test validatePayload method with non-existent schema file
     * This test verifies that the method throws a CustomException when the schema file doesn't exist
     */
    @Test
    void test_validatePayload_nonExistentSchemaFile() {
        JsonNode payload = objectMapper.createObjectNode();
        String nonExistentFile = "non_existent_schema.json";
        CustomException exception = assertThrows(CustomException.class, 
            () -> ciosContentService.validatePayload(nonExistentFile, payload));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatusCode());
        assertTrue(exception.getMessage().contains("Failed to validate payload"));
    }

    /**
     * Test validatePayload method with null fileName
     * This test checks if the method throws a CustomException when fileName is null
     */
    @Test
    void test_validatePayload_nullFileName() {
        JsonNode payload = objectMapper.createObjectNode();
        assertThrows(CustomException.class, () -> ciosContentService.validatePayload(null, payload));
    }

    /**
     * Test case for validatePayload method when validation messages are not empty.
     * This test verifies that a CustomException is thrown with the correct error message
     * and HTTP status when the payload fails validation.
     */
    @Test
    void test_validatePayload_validationMessageNotEmpty() {
        JsonNode invalidPayload = objectMapper.createObjectNode();

        CustomException exception = assertThrows(CustomException.class, () -> {
            ciosContentService.validatePayload("invalid_schema.json", invalidPayload);
        });

        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatusCode());
        assertEquals("ERROR", exception.getCode());
        assertEquals(true, exception.getMessage().startsWith("Failed to validate payload:"));
    }


    @Test
    void test_fetchDataByContentId_success_fromRedis() throws Exception {
        // Arrange
        String contentId = "test-content-id";
        String cachedJson = "{\"key\":\"value\"}";
        Map<String, Object> expectedData = new HashMap<>();
        expectedData.put("key", "value");

        when(cacheService.getCache(contentId)).thenReturn(cachedJson);
        when(objectMapper.readValue(eq(cachedJson), ArgumentMatchers.<TypeReference<Object>>any()))
                .thenReturn(expectedData);

        // Act
        Object result = ciosContentService.fetchDataByContentId(contentId);

        // Assert
        assertNotNull(result);
        assertTrue(result instanceof Map);
        assertEquals("value", ((Map<?, ?>) result).get("key"));

        verify(cacheService).getCache(contentId);
        verify(objectMapper).readValue(eq(cachedJson), ArgumentMatchers.<TypeReference<Object>>any());
        verifyNoInteractions(ciosRepository); // Redis hit; DB should not be called
    }

    @Test
    void test_fetchDataByContentId_success_fromDB() {
        // Arrange
        String contentId = "test-content-id";
        when(cacheService.getCache(contentId)).thenReturn(null);

        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("key", "value");

        CiosContentEntity entity = new CiosContentEntity();
        entity.setCiosData(node);
        when(ciosRepository.findByContentId(contentId))
                .thenReturn(Optional.of(entity));
        when(objectMapper.convertValue(eq(node), ArgumentMatchers.<TypeReference<Object>>any()))
                .thenReturn(Map.of("key", "value"));

        // Act
        Object result = ciosContentService.fetchDataByContentId(contentId);

        // Assert
        assertNotNull(result);
        assertTrue(result instanceof Map);
        assertEquals("value", ((Map<?, ?>) result).get("key"));

        verify(cacheService).getCache(contentId);
        verify(ciosRepository).findByContentId(contentId);
        verify(cacheService).putCache(contentId, node);
        verify(objectMapper).convertValue(eq(node), ArgumentMatchers.<TypeReference<Object>>any());
    }

    /**
     * contentId is blank -> should fail fast with the "contentId is mandatory" error
     * and never touch cache/repository at all.
     */
    @Test
    void test_fetchDataByInputFields_blankContentId() {
        CustomException exception = assertThrows(CustomException.class, () ->
                ciosContentService.fetchDataByInputFields("  ", "name")
        );

        assertEquals(Constants.ERROR, exception.getCode());
        assertEquals("contentId is mandatory", exception.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatusCode());

        verifyNoInteractions(cacheService);
        verifyNoInteractions(ciosRepository);
    }

    /**
     * inputFields is blank -> should fail fast with the "inputFields is mandatory" error
     * before ever attempting to fetch the content.
     */
    @Test
    void test_fetchDataByInputFields_blankInputFields() {
        CustomException exception = assertThrows(CustomException.class, () ->
                ciosContentService.fetchDataByInputFields("test-content-id", "   ")
        );

        assertEquals(Constants.ERROR, exception.getCode());
        assertEquals("inputFields is mandatory", exception.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatusCode());

        verifyNoInteractions(cacheService);
        verifyNoInteractions(ciosRepository);
    }

    /**
     * inputFields is non-blank but parses down to zero usable field names
     * (only commas/whitespace) -> no field names match anything, so the method
     * returns an empty "content" object rather than throwing.
     */
    @Test
    void test_fetchDataByInputFields_onlyCommasAndWhitespace() {
        String contentId = "test-content-id";
        when(cacheService.getCache(contentId)).thenReturn(null);

        ObjectNode node = new ObjectMapper().createObjectNode();
        node.putObject(Constants.CONTENT).put("name", "value");

        CiosContentEntity entity = new CiosContentEntity();
        entity.setCiosData(node);
        when(ciosRepository.findByContentId(contentId)).thenReturn(Optional.of(entity));

        Map<String, Object> innerContent = new LinkedHashMap<>();
        innerContent.put("name", "value");
        Map<String, Object> fullContent = new LinkedHashMap<>();
        fullContent.put(Constants.CONTENT, innerContent);
        when(objectMapper.convertValue(eq(node), ArgumentMatchers.<TypeReference<Object>>any()))
                .thenReturn(fullContent);

        Object result = ciosContentService.fetchDataByInputFields(contentId, " , ,  ,");

        assertTrue(result instanceof Map);
        Object innerResult = ((Map<?, ?>) result).get(Constants.CONTENT);
        assertTrue(innerResult instanceof Map);
        assertTrue(((Map<?, ?>) innerResult).isEmpty());

        verify(cacheService).getCache(contentId);
        verify(ciosRepository).findByContentId(contentId);
    }

    /**
     * fetchDataByContentId returns a non-Map value (e.g. a plain String cached in redis) ->
     * fetchDataByInputFields should just return that value as-is instead of failing.
     */
    @Test
    void test_fetchDataByInputFields_fullContentNotAMap_returnsAsIs() throws Exception {
        String contentId = "test-content-id";
        String cachedJson = "\"plain string value\"";

        when(cacheService.getCache(contentId)).thenReturn(cachedJson);
        when(objectMapper.readValue(eq(cachedJson), ArgumentMatchers.<TypeReference<Object>>any()))
                .thenReturn("plain string value");

        Object result = ciosContentService.fetchDataByInputFields(contentId, "name");

        assertEquals("plain string value", result);
        verifyNoInteractions(ciosRepository);
    }

    /**
     * fetchDataByContentId returns a Map, but it has no "content" node ->
     * fetchDataByInputFields should return the full map as-is instead of failing.
     */
    @Test
    void test_fetchDataByInputFields_noContentNode_returnsAsIs() {
        String contentId = "test-content-id";
        when(cacheService.getCache(contentId)).thenReturn(null);

        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("name", "value");

        CiosContentEntity entity = new CiosContentEntity();
        entity.setCiosData(node);
        when(ciosRepository.findByContentId(contentId)).thenReturn(Optional.of(entity));

        Map<String, Object> fullContent = new LinkedHashMap<>();
        fullContent.put("name", "value");
        when(objectMapper.convertValue(eq(node), ArgumentMatchers.<TypeReference<Object>>any()))
                .thenReturn(fullContent);

        Object result = ciosContentService.fetchDataByInputFields(contentId, "name");

        assertSame(fullContent, result);
    }

    /**
     * Happy path: only the requested fields are returned, in the order requested,
     * duplicates are collapsed, unknown fields are silently dropped, and a field
     * whose value is JSON null is preserved rather than causing an NPE.
     */
    @Test
    void test_fetchDataByInputFields_filtersAndPreservesOrder() {
        String contentId = "test-content-id";
        when(cacheService.getCache(contentId)).thenReturn(null);

        ObjectNode node = new ObjectMapper().createObjectNode();
        ObjectNode contentNode = node.putObject(Constants.CONTENT);
        contentNode.put("name", "Foundations of Ethical Reasoning");
        contentNode.put("topic", "Environmental, Social, Governance, Law");
        contentNode.putNull("description");

        CiosContentEntity entity = new CiosContentEntity();
        entity.setCiosData(node);
        when(ciosRepository.findByContentId(contentId)).thenReturn(Optional.of(entity));

        Map<String, Object> innerContent = new LinkedHashMap<>();
        innerContent.put("name", "Foundations of Ethical Reasoning");
        innerContent.put("topic", "Environmental, Social, Governance, Law");
        innerContent.put("description", null);
        innerContent.put("status", "Live");
        Map<String, Object> fullContent = new LinkedHashMap<>();
        fullContent.put(Constants.CONTENT, innerContent);
        when(objectMapper.convertValue(eq(node), ArgumentMatchers.<TypeReference<Object>>any()))
                .thenReturn(fullContent);

        Object result = ciosContentService.fetchDataByInputFields(
                contentId, "description, name, name, missingField"
        );

        assertTrue(result instanceof Map);
        Object innerResult = ((Map<?, ?>) result).get(Constants.CONTENT);
        assertTrue(innerResult instanceof Map);
        Map<?, ?> filtered = (Map<?, ?>) innerResult;

        // Only the two known, requested fields should be present ("status" and
        // "topic" were not requested; "missingField" doesn't exist).
        assertEquals(2, filtered.size());
        assertTrue(filtered.containsKey("description"));
        assertNull(filtered.get("description"));
        assertEquals("Foundations of Ethical Reasoning", filtered.get("name"));

        // Order should follow the caller's requested order: description, then name.
        Iterator<?> keyIterator = filtered.keySet().iterator();
        assertEquals("description", keyIterator.next());
        assertEquals("name", keyIterator.next());
    }

    @Test
    void test_createNewContent_NewEntity() throws Exception {
        // Prepare input JSON with necessary structure
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode contentNode = mapper.createObjectNode();
        contentNode.put("externalId", "external-123");

        ObjectNode partnerNode = mapper.createObjectNode();
        partnerNode.put("id", "partner-abc");
        contentNode.set("contentPartner", partnerNode);

        ObjectNode rootNode = mapper.createObjectNode();
        rootNode.set("content", contentNode);

        // Mock repository to return empty, so new entity is created
        when(ciosRepository.findByExternalIdAndPartnerId("external-123", "partner-abc"))
                .thenReturn(Optional.empty());

        // Use reflection to invoke private method
        Method method = CiosContentServiceImpl.class.getDeclaredMethod("createNewContent", JsonNode.class, boolean.class, String.class);
        method.setAccessible(true);

        // Invoke method
        Object result = method.invoke(ciosContentService, rootNode, true, Constants.LIVE);

        // Assertions
        assertNotNull(result);
        assertTrue(result instanceof CiosContentEntity);

        CiosContentEntity entity = (CiosContentEntity) result;

        // Check values set properly
        assertEquals("external-123", entity.getExternalId());
        assertEquals("partner-abc", entity.getPartnerId());
        assertNotNull(entity.getContentId());
        assertNotNull(entity.getCreatedOn());
        assertNotNull(entity.getLastUpdatedOn());
        assertTrue(entity.getIsActive());

        // Also verify the JSON was updated with contentId and timestamps
        JsonNode updatedContent = rootNode.path("content");
        assertEquals(entity.getContentId(), updatedContent.path("contentId").asText());
        assertEquals(String.valueOf(entity.getCreatedOn()), updatedContent.path("createdOn").asText());
        assertEquals(String.valueOf(entity.getLastUpdatedOn()), updatedContent.path("lastUpdatedOn").asText());
        assertEquals("Live", updatedContent.path("status").asText());
    }

    @Test
    void test_createNewContent_ExistingEntity() throws Exception {
        // Prepare input JSON
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode contentNode = mapper.createObjectNode();
        contentNode.put("externalId", "external-123");

        ObjectNode partnerNode = mapper.createObjectNode();
        partnerNode.put("id", "partner-abc");
        contentNode.set("contentPartner", partnerNode);

        ObjectNode rootNode = mapper.createObjectNode();
        rootNode.set("content", contentNode);

        // Mock existing entity from repository
        CiosContentEntity existingEntity = new CiosContentEntity();
        existingEntity.setContentId("content-456");
        existingEntity.setExternalId("external-123");
        existingEntity.setPartnerId("partner-abc");
        existingEntity.setCreatedOn(new Timestamp(System.currentTimeMillis() - 10000));
        existingEntity.setIsActive(true);

        when(ciosRepository.findByExternalIdAndPartnerId("external-123", "partner-abc"))
                .thenReturn(Optional.of(existingEntity));

        // Use reflection
        Method method = CiosContentServiceImpl.class.getDeclaredMethod("createNewContent", JsonNode.class, boolean.class, String.class);
        method.setAccessible(true);

        Object result = method.invoke(ciosContentService, rootNode, true, Constants.LIVE);

        assertNotNull(result);
        assertTrue(result instanceof CiosContentEntity);

        CiosContentEntity entity = (CiosContentEntity) result;

        // Existing entity's contentId should be preserved
        assertEquals("content-456", entity.getContentId());
        assertEquals("external-123", entity.getExternalId());
        assertEquals("partner-abc", entity.getPartnerId());

        // CreatedOn should be from existing entity
        assertEquals(existingEntity.getCreatedOn(), entity.getCreatedOn());

        // LastUpdatedOn should be recent (after CreatedOn)
        assertTrue(entity.getLastUpdatedOn().after(entity.getCreatedOn()));

        // JSON content should be updated with these values
        JsonNode updatedContent = rootNode.path("content");
        assertEquals(entity.getContentId(), updatedContent.path("contentId").asText());
        assertEquals(String.valueOf(entity.getCreatedOn()), updatedContent.path("createdOn").asText());
        assertEquals(String.valueOf(entity.getLastUpdatedOn()), updatedContent.path("lastUpdatedOn").asText());
        assertEquals("Live", updatedContent.path("status").asText());
    }

    @Test
    void test_addSearchTags_contentNameNotInTags() throws Exception {
        ReflectionTestUtils.setField(ciosContentService, "objectMapper", realObjectMapper);
        // Prepare input tags
        List<String> inputTags = Arrays.asList("tag1", "tag2");

        // Prepare input JSON with content.name = "MyContent"
        ObjectNode contentNode = realObjectMapper.createObjectNode();
        contentNode.put("name", "MyContent");

        ObjectNode rootNode = realObjectMapper.createObjectNode();
        rootNode.set("content", contentNode);

        // Use reflection to access private method
        Method method = CiosContentServiceImpl.class.getDeclaredMethod("addSearchTags", List.class, JsonNode.class);
        method.setAccessible(true);

        // Invoke method
        Object result = method.invoke(ciosContentService, inputTags, rootNode);

        assertNotNull(result);
        assertTrue(result instanceof ArrayNode);

        ArrayNode arrayNode = (ArrayNode) result;

        // The content name should be added in lowercase (mycontent), plus all tags in lowercase
        // Expected: ["mycontent", "tag1", "tag2"]
        assertEquals(3, arrayNode.size());
        assertEquals("mycontent", arrayNode.get(0).asText());
        assertEquals("tag1", arrayNode.get(1).asText());
        assertEquals("tag2", arrayNode.get(2).asText());
    }

    @Test
    void test_addSearchTags_contentNameAlreadyInTags() throws Exception {
        ReflectionTestUtils.setField(ciosContentService, "objectMapper", realObjectMapper);
        // content.name = "tag1" (already present in input tags, case insensitive)
        List<String> inputTags = Arrays.asList("tag1", "tag2");

        ObjectNode contentNode = realObjectMapper.createObjectNode();
        contentNode.put("name", "Tag1");  // different case but same text

        ObjectNode rootNode = realObjectMapper.createObjectNode();
        rootNode.set("content", contentNode);

        Method method = CiosContentServiceImpl.class.getDeclaredMethod("addSearchTags", List.class, JsonNode.class);
        method.setAccessible(true);

        Object result = method.invoke(ciosContentService, inputTags, rootNode);

        assertNotNull(result);
        assertTrue(result instanceof ArrayNode);

        ArrayNode arrayNode = (ArrayNode) result;

        // content name is NOT added again, only lowercase tags included
        assertEquals(2, arrayNode.size());
        assertEquals("tag1", arrayNode.get(0).asText());
        assertEquals("tag2", arrayNode.get(1).asText());
    }

    @Test
    void test_addSearchTags_contentNameNull() throws Exception {
        // No content node or no content.name present
        ReflectionTestUtils.setField(ciosContentService, "objectMapper", realObjectMapper);
        List<String> inputTags = Arrays.asList("tag1", "tag2");

        ObjectNode rootNode = realObjectMapper.createObjectNode();
        // no content set in rootNode

        Method method = CiosContentServiceImpl.class.getDeclaredMethod("addSearchTags", List.class, JsonNode.class);
        method.setAccessible(true);

        Object result = method.invoke(ciosContentService, inputTags, rootNode);

        assertNotNull(result);
        assertTrue(result instanceof ArrayNode);

        ArrayNode arrayNode = (ArrayNode) result;

        // Since contentName is null, only lowercase tags are added
        assertEquals(2, arrayNode.size());
        assertEquals("tag1", arrayNode.get(0).asText());
        assertEquals("tag2", arrayNode.get(1).asText());
    }

    @Test
    void testFetchData_RecordFromPostgresDb() throws Exception {
        String externalId = "ext123";
        String partnerId = "partner1";
        String cacheKey = externalId + "_" + partnerId;

        // Simulate cache miss
        when(cacheService.getCache(cacheKey)).thenReturn(null);

        // Prepare CiosContentEntity mock
        CiosContentEntity entity = mock(CiosContentEntity.class);
        Object ciosData = new Object();  // could be a Map or JsonNode - here just generic

        // Simulate DB returns entity
        when(ciosRepository.findByExternalIdAndPartnerId(externalId, partnerId))
                .thenReturn(Optional.of(entity));

        // Simulate ObjectMapper conversion of ciosData -> Object
        when(objectMapper.convertValue(eq(ciosData), any(TypeReference.class)))
                .thenReturn(ciosData);

        // Call the method
        Object result = ciosContentService.fetchDataByExternalIdAndPartnerId(externalId, partnerId);

        // Verify the result is what we mocked
        assertEquals(null, result);
    }

    // 2. Test to cover throwing CustomException when no data found for given Id
    @Test
    void testFetchData_NoDataFound_ThrowsCustomException() {
        String externalId = "ext123";
        String partnerId = "partner1";
        String cacheKey = externalId + "_" + partnerId;

        // Cache miss
        when(cacheService.getCache(cacheKey)).thenReturn(null);

        // DB returns empty
        when(ciosRepository.findByExternalIdAndPartnerId(externalId, partnerId))
                .thenReturn(Optional.empty());

        // Assert exception thrown
        CustomException exception = assertThrows(CustomException.class,
                () -> ciosContentService.fetchDataByExternalIdAndPartnerId(externalId, partnerId));

        assertEquals("No data found for given Id", exception.getMessage());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatusCode());
    }

    @Test
    void test_validatePayload_shouldThrowCustomException_whenValidationFails(){
        // Arrange
        String fileName = "/dummy-schema.json";
        JsonNode mockPayload = mock(JsonNode.class);

        // Create a mock ValidationMessage with custom message
        ValidationMessage validationMessage = mock(ValidationMessage.class);
        when(validationMessage.getMessage()).thenReturn("$.field is missing");

        // Return a non-empty set of validation messages
        Set<ValidationMessage> validationMessages = new HashSet<>();
        validationMessages.add(validationMessage);

        // Create a mock schema that returns the above validation messages
        JsonSchema mockSchema = mock(JsonSchema.class);
        when(mockSchema.validate(mockPayload)).thenReturn(validationMessages);

        // Use reflection to inject the mocked schema behavior into the service
        // Trick: Replace `getSchema` behavior by mocking InputStream reading
        InputStream dummySchemaStream = new ByteArrayInputStream("{\"type\": \"object\"}".getBytes());

        // Use reflection to mock JsonSchemaFactory.getInstance().getSchema(...)
        JsonSchemaFactory factory = mock(JsonSchemaFactory.class);
        when(factory.getSchema(any(InputStream.class))).thenReturn(mockSchema);

        // Use reflection to mock the getInstance() call (for testing only)
        JsonSchemaFactory staticFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V201909);
        JsonSchemaFactory spyFactory = spy(staticFactory);
        doReturn(mockSchema).when(spyFactory).getSchema(any(InputStream.class));

        // Force the getResourceAsStream to return dummy schema input
        ClassLoader classLoader = mock(ClassLoader.class);
        when(classLoader.getResourceAsStream(fileName)).thenReturn(dummySchemaStream);
        Thread.currentThread().setContextClassLoader(classLoader);

        // Act + Assert
        CustomException exception = assertThrows(CustomException.class, () ->
                ciosContentService.validatePayload(fileName, mockPayload));

        // Print and validate
        System.out.println("Exception Message: " + exception.getMessage());

        assertEquals(Constants.ERROR, exception.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatusCode());
    }

    @Test
    void test_fetchAndUpdateContentCountsInPartnerDb_whenFacetsMissing_logsWarning() throws Exception {
        ObjectNode mockNode = mock(ObjectNode.class);
        when(mockNode.hasNonNull(Constants.TOTAL_COUNT)).thenReturn(true);
        when(mockNode.has(Constants.FACETS)).thenReturn(false);

        CiosContentServiceImpl service = prepareServiceWithMocks(mockNode);

        Method method = CiosContentServiceImpl.class.getDeclaredMethod("fetchAndUpdateContentCountsInPartnerDb", String.class);
        method.setAccessible(true);
        method.invoke(service, "PARTNER001");
        assertDoesNotThrow(() -> method.invoke(service, "PARTNER001"));
    }

    @SuppressWarnings("unchecked")
    private CiosContentServiceImpl prepareServiceWithMocks(JsonNode mockedNode) throws Exception {
        RestTemplate mockRestTemplate = mock(RestTemplate.class);
        cbServerProperties = mock(CbServerProperties.class);
        when(cbServerProperties.getCiosContentServiceHost()).thenReturn("http://mock-host");
        when(cbServerProperties.getCiosContentServiceSearchApiUrl()).thenReturn("/mock-api");
        contentPartnerService = mock(ContentPartnerService.class);

        // The class-level objectMapper field is a mock (createObjectNode()/createArrayNode()
        // are unstubbed there); this method needs a real one to build actual JSON payloads.
        ObjectMapper realMapper = new ObjectMapper();

        // CiosContentServiceImpl is constructor-injected (@RequiredArgsConstructor over final
        // fields) - there is no no-arg constructor anymore, so build it directly. The fields
        // this test doesn't exercise (ciosRepository, esUtilService, payloadValidation,
        // redisTemplate, cacheService) just get harmless mocks.
        CiosContentServiceImpl service = new CiosContentServiceImpl(
                mock(CiosRepository.class),
                realMapper,
                mock(EsUtilService.class),
                mock(PayloadValidation.class),
                mock(RedisTemplate.class),
                cbServerProperties,
                mock(CacheService.class),
                mockRestTemplate,
                contentPartnerService
        );

        ResponseEntity<JsonNode> mockResponse = mock(ResponseEntity.class);
        when(mockResponse.getBody()).thenReturn(mockedNode);

        when(mockRestTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(JsonNode.class)
        )).thenReturn(mockResponse);

        return service;
    }

    @Test
    void test_fetchAndUpdateContentCountsInPartnerDb_whenTotalCountMissing_logsWarning() throws Exception {
        ObjectNode mockNode = mock(ObjectNode.class);
        when(mockNode.hasNonNull(Constants.TOTAL_COUNT)).thenReturn(false);
        when(mockNode.has(Constants.FACETS)).thenReturn(true);
        when(mockNode.get(Constants.FACETS)).thenReturn(mock(JsonNode.class));

        CiosContentServiceImpl service = prepareServiceWithMocks(mockNode);

        Method method = CiosContentServiceImpl.class.getDeclaredMethod("fetchAndUpdateContentCountsInPartnerDb", String.class);
        method.setAccessible(true);
        method.invoke(service, "PARTNER001");
        verify(contentPartnerRepository, never()).save(any());

    }

    @Test
    void test_fetchAndUpdateContentCountsInPartnerDb_whenStatusMissing_logsWarning() throws Exception {
        ObjectNode mockNode = mock(ObjectNode.class);
        when(mockNode.hasNonNull(Constants.TOTAL_COUNT)).thenReturn(true);
        when(mockNode.has(Constants.FACETS)).thenReturn(true);

        JsonNode mockFacetsNode = mock(JsonNode.class);
        when(mockFacetsNode.has(Constants.STATUS)).thenReturn(false);
        when(mockNode.get(Constants.FACETS)).thenReturn(mockFacetsNode);

        CiosContentServiceImpl service = prepareServiceWithMocks(mockNode);

        Method method = CiosContentServiceImpl.class.getDeclaredMethod("fetchAndUpdateContentCountsInPartnerDb", String.class);
        method.setAccessible(true);
        method.invoke(service, "PARTNER001");
        verify(contentPartnerRepository, never()).save(any());
    }

    @Test
    void test_fetchAndUpdateContentCountsInPartnerDb_whenNodeIsNull_logsWarning() throws Exception {
        CiosContentServiceImpl service = prepareServiceWithMocks(null);
        Method method = CiosContentServiceImpl.class.getDeclaredMethod("fetchAndUpdateContentCountsInPartnerDb", String.class);
        method.setAccessible(true);
        method.invoke(service, "PARTNER001");
        verify(contentPartnerRepository, never()).save(any());
    }

    @Test
    void test_deleteContent_success() {
        // Arrange - CiosContentServiceImpl is constructor-injected now, so drive this through
        // the shared @InjectMocks instance (ciosContentService) rather than a bespoke
        // new CiosContentServiceImpl() + ReflectionTestUtils wiring (contentPartnerRepository in
        // particular is no longer even a field on the service). deleteContent() ->
        // fetchAndUpdateContentCountsInPartnerDb() builds real JsonNode payloads via
        // objectMapper.createObjectNode()/createArrayNode(), so the class-level mock objectMapper
        // (which returns null for those, unstubbed) needs swapping for a real one here.
        ObjectMapper realMapper = new ObjectMapper();
        ReflectionTestUtils.setField(ciosContentService, "objectMapper", realMapper);

        when(cbServerProperties.getElasticCiosJsonPath()).thenReturn("mock/path");

        // build valid JSON structure
        ObjectNode rootNode = realMapper.createObjectNode();
        ObjectNode contentNode = realMapper.createObjectNode();
        ObjectNode partnerNode = realMapper.createObjectNode();
        partnerNode.put("partnerCode", "PARTNER001");
        contentNode.set("contentPartner", partnerNode);
        rootNode.set("content", contentNode);

        CiosContentEntity entity = new CiosContentEntity();
        entity.setCiosData(rootNode);
        entity.setContentId("CID001");

        when(ciosRepository.findByContentIdAndIsActive("CID001", true))
                .thenReturn(Optional.of(entity));

        // mock body for search response (also reused for the secondary-DB update call - both
        // go through the same mocked restTemplate.exchange(anyString(), ...) stub below)
        ObjectNode searchResponseBody = realMapper.createObjectNode();
        searchResponseBody.put(Constants.TOTAL_COUNT, 10);

        ObjectNode facetNode = realMapper.createObjectNode();
        ArrayNode statusArray = realMapper.createArrayNode();
        ObjectNode liveFacet = realMapper.createObjectNode();
        liveFacet.put(Constants.VALUE, "live");
        liveFacet.put(Constants.COUNT, 5);
        statusArray.add(liveFacet);
        facetNode.set(Constants.STATUS, statusArray);
        searchResponseBody.set(Constants.FACETS, facetNode);

        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(JsonNode.class))
        ).thenReturn(new ResponseEntity<>(searchResponseBody, HttpStatus.OK));

        // mock contentPartnerService
        Map<String, Object> responseMap = new HashMap<>();
        Map<String, Object> dataMap = new HashMap<>();
        responseMap.put(Constants.DATA, dataMap);
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setResult(responseMap);
        when(contentPartnerService.getContentDetailsByPartnerCode("PARTNER001")).thenReturn(apiResponse);

        // Act
        Object result = ciosContentService.deleteContent("CID001");

        // Assert
        assertEquals("Content with id : CID001 is deleted", result);
    }

    @Test
    void testOnboardContent_DraftContent_Success() throws Exception {
        ObjectDto dto = new ObjectDto();
        dto.setStatus("draft");

        ObjectNode content = JsonNodeFactory.instance.objectNode();
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set("content", content);

        dto.setContentData(root);

        ObjectNode partner = JsonNodeFactory.instance.objectNode();
        partner.put("partnerCode", "PARTNER_1");
        dto.setContentPartner(partner);

        dto.setTags(Arrays.asList("Skill", "Java"));

        List<ObjectDto> data = List.of(dto);

        // Mock payload validation
        doNothing().when(payloadValidation).validatePayload(any(), any());

        // Mock REST call to secondary DB
        when(objectMapper.createObjectNode()).thenReturn(JsonNodeFactory.instance.objectNode());
        when(objectMapper.createArrayNode()).thenAnswer(invocation -> realObjectMapper.createArrayNode());
        when(cbServerProperties.getCiosContentServiceHost()).thenReturn("http://localhost/");
        when(cbServerProperties.getCiosContentServiceUpdateApiUrl()).thenReturn("update");
        when(restTemplate.exchange(anyString(), any(), any(), eq(JsonNode.class)))
                .thenReturn(new ResponseEntity<>(JsonNodeFactory.instance.objectNode(), HttpStatus.OK));

        // Mock search API call
        when(cbServerProperties.getCiosContentServiceSearchApiUrl()).thenReturn("search");
        JsonNode searchResult = JsonNodeFactory.instance.objectNode()
                .put(Constants.TOTAL_COUNT, 10);
        ObjectNode statusFacet = JsonNodeFactory.instance.objectNode()
                .put(Constants.VALUE, "draft")
                .put(Constants.COUNT, 5);
        ArrayNode statusArray = JsonNodeFactory.instance.arrayNode().add(statusFacet);
        ObjectNode facets = JsonNodeFactory.instance.objectNode().set(Constants.STATUS, statusArray);
        ((ObjectNode) searchResult).set(Constants.FACETS, facets);

        when(restTemplate.exchange(contains("search"), any(), any(), eq(JsonNode.class)))
                .thenReturn(new ResponseEntity<>(searchResult, HttpStatus.OK));

        Map<String, Object> contentPartnerData = new HashMap<>();
        contentPartnerData.put("data", new HashMap<>());
        ApiResponse apiResp = new ApiResponse();
        apiResp.setResult(contentPartnerData);
        when(contentPartnerService.getContentDetailsByPartnerCode(any())).thenReturn(apiResp);
        when(contentPartnerService.createOrUpdate(any())).thenReturn(apiResp);

        ApiResponse response = ciosContentService.onboardContent(data);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
    }

    @Test
    void test_onboardContent_logsContentId() {
        ObjectDto dto = new ObjectDto();
        dto.setStatus("draft");

        ObjectNode contentNode = realObjectMapper.createObjectNode();
        contentNode.put("externalId", "ext123");

        ObjectNode contentPartnerNode = realObjectMapper.createObjectNode();
        contentPartnerNode.put("id", "partner123");
        contentPartnerNode.put("partnerCode", "PARTNER_1"); // crucial to avoid NPE

        contentNode.set("contentPartner", contentPartnerNode);

        ObjectNode contentWrapperNode = realObjectMapper.createObjectNode();
        contentWrapperNode.set("content", contentNode);

        dto.setContentData(contentWrapperNode);
        dto.setContentPartner(contentPartnerNode);
        dto.setTags(Arrays.asList("Skill", "Java"));
        dto.setStatus("live");

        List<ObjectDto> data = List.of(dto);

        when(objectMapper.createObjectNode()).thenReturn(JsonNodeFactory.instance.objectNode());
        when(objectMapper.createArrayNode()).thenReturn(JsonNodeFactory.instance.arrayNode());

        doNothing().when(ciosRequestPayloadValidation).validateModel(any());
        doNothing().when(payloadValidation).validatePayload(anyString(), any());

        when(cbServerProperties.getCiosContentServiceHost()).thenReturn("http://mock-host");
        when(cbServerProperties.getCiosContentServiceUpdateApiUrl()).thenReturn("/update");
        when(cbServerProperties.getElasticCiosJsonPath()).thenReturn("/es/path");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(realObjectMapper.createObjectNode()));

        when(ciosRepository.findByExternalIdAndPartnerId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        ArgumentCaptor<CiosContentEntity> captor = ArgumentCaptor.forClass(CiosContentEntity.class);
        when(ciosRepository.save(captor.capture())).thenAnswer(invocation -> {
            CiosContentEntity entity = captor.getValue();
            entity.setContentId("generated-content-id");
            return entity;
        });

        ApiResponse mockApiResponse = new ApiResponse();
        Map<String, Object> mockData = new HashMap<>();
        mockData.put("data", new HashMap<>());
        mockApiResponse.setResult(mockData);
        when(contentPartnerService.getContentDetailsByPartnerCode(anyString()))
                .thenReturn(mockApiResponse);

        // Act
        ApiResponse response = ciosContentService.onboardContent(data);

        // Assert
        assertEquals("success", response.getParams().getStatus());
        assertEquals(HttpStatus.OK, response.getResponseCode());

    }

    @Test
    void test_readContent_cachedResult() {
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        SearchResult expectedResult = new SearchResult();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(expectedResult);

        // Act
        SearchResult actualResult = ciosContentService.readContent(searchCriteria);

        // Assert
        assertNotNull(actualResult);
        assertEquals(expectedResult, actualResult);
        verify(redisTemplate).opsForValue();
        verify(valueOperations).get(anyString());
        verifyNoMoreInteractions(redisTemplate, valueOperations);
    }

    @Test
    void test_readContent_shouldThrowException_WhenSearchCriteriaIsNull() {
        // Arrange
        SearchCriteria searchCriteria = null;

        // Act & Assert
        CustomException exception = assertThrows(CustomException.class, () -> {
            ciosContentService.readContent(searchCriteria);
        });

        // Verify exception details
        assertEquals("Search criteria must not be null", exception.getMessage());
        assertEquals("ERROR", exception.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatusCode());
    }

    @Test
    void test_readContent_cacheMiss_callsEsAndCachesResult() throws Exception {
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        SearchResult expectedResult = new SearchResult();
        when(esUtilService.searchDocuments(eq(Constants.CIOS_INDEX_NAME), any(SearchCriteria.class)))
                .thenReturn(expectedResult);

        // Act
        SearchResult result = ciosContentService.readContent(searchCriteria);

        // Assert
        assertNotNull(result);
        assertEquals(expectedResult, result);

        verify(esUtilService).searchDocuments(eq(Constants.CIOS_INDEX_NAME), any(SearchCriteria.class));
        verify(valueOperations).set(anyString(), eq(expectedResult), anyLong(), any());
    }

    // ---- parseKarmaCoinModifier ----

    static Stream<Arguments> validKarmaCoinModifiers() {
        return Stream.of(
                Arguments.of(2.0, 100, 200.0),
                Arguments.of(1.5, 40, 60.0),
                Arguments.of(3.0, 10, 30.0),
                Arguments.of(2.0, 0, 0.0)
        );
    }

    @ParameterizedTest(name = "modifier={0}, defaultKarmaPoints={1} -> {2}")
    @MethodSource("validKarmaCoinModifiers")
    void test_parseKarmaCoinModifier_validModifier_multipliesKarmaPoints(
            Double modifier, int defaultKarmaPoints, double expected) throws Exception {
        Method method = CiosContentServiceImpl.class.getDeclaredMethod(
                "parseKarmaCoinModifier", Double.class, int.class);
        method.setAccessible(true);

        Double result = (Double) method.invoke(ciosContentService, modifier, defaultKarmaPoints);

        assertEquals(expected, result);
    }

    @Test
    void test_parseKarmaCoinModifier_zeroModifier_fallsBackToKarmaPointsAsIs() throws Exception {
        Method method = CiosContentServiceImpl.class.getDeclaredMethod(
                "parseKarmaCoinModifier", Double.class, int.class);
        method.setAccessible(true);

        Double result = (Double) method.invoke(ciosContentService, 0.0, 75);

        assertEquals(0.0, result);
    }

    @Test
    void test_parseKarmaCoinModifier_nullModifier_throwsNullPointerException() throws Exception {
        Method method = CiosContentServiceImpl.class.getDeclaredMethod(
                "parseKarmaCoinModifier", Double.class, int.class);
        method.setAccessible(true);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> method.invoke(ciosContentService, (Object) null, 50));

        assertInstanceOf(NullPointerException.class, thrown.getCause());
    }

    // ---- isPayloadValid ----

    private Method isPayloadValidMethod() throws NoSuchMethodException {
        Method method = CiosContentServiceImpl.class.getDeclaredMethod("isPayloadValid", JsonNode.class, ObjectNode.class);
        method.setAccessible(true);
        return method;
    }

    @Test
    void test_isPayloadValid_validPayload_returnsTrue() throws Exception {
        doNothing().when(payloadValidation).validatePayload(anyString(), any());

        boolean result = (boolean) isPayloadValidMethod()
                .invoke(ciosContentService, realObjectMapper.createObjectNode(), realObjectMapper.createObjectNode());

        assertTrue(result);
    }

    @Test
    void test_isPayloadValid_invalidPayload_returnsFalse() throws Exception {
        doThrow(new CustomException(Constants.ERROR, "schema mismatch", HttpStatus.BAD_REQUEST))
                .when(payloadValidation).validatePayload(anyString(), any());

        ObjectNode contentNode = realObjectMapper.createObjectNode();
        contentNode.put(Constants.CONTENT_ID, "content-1");

        boolean result = (boolean) isPayloadValidMethod()
                .invoke(ciosContentService, realObjectMapper.createObjectNode(), contentNode);

        assertFalse(result);
    }

    // ---- processLiveStatusContent ----

    private Method processLiveStatusContentMethod() throws NoSuchMethodException {
        Method method = CiosContentServiceImpl.class.getDeclaredMethod(
                "processLiveStatusContent", JsonNode.class, ObjectNode.class, String.class, Timestamp.class);
        method.setAccessible(true);
        return method;
    }

    @Test
    void test_processLiveStatusContent_invalidPayload_returnsFalseWithoutPersisting() throws Exception {
        ObjectNode contentNode = realObjectMapper.createObjectNode();
        ObjectNode rootNode = realObjectMapper.createObjectNode();
        rootNode.set(Constants.CONTENT, contentNode);

        doThrow(new RuntimeException("bad payload")).when(payloadValidation).validatePayload(anyString(), any());

        boolean result = (boolean) processLiveStatusContentMethod()
                .invoke(ciosContentService, rootNode, contentNode, "PARTNER_1", new Timestamp(System.currentTimeMillis()));

        assertFalse(result);
        verifyNoInteractions(contentPartnerService);
        verify(ciosRepository, never()).save(any());
    }

    @Test
    void test_processLiveStatusContent_licenceRulesFail_returnsFalseWithoutPersisting() throws Exception {
        ObjectNode contentNode = realObjectMapper.createObjectNode();
        ObjectNode rootNode = realObjectMapper.createObjectNode();
        rootNode.set(Constants.CONTENT, contentNode);

        doNothing().when(payloadValidation).validatePayload(anyString(), any());
        when(contentPartnerService.getContentDetailsByPartnerCode("PARTNER_1")).thenReturn(null);

        boolean result = (boolean) processLiveStatusContentMethod()
                .invoke(ciosContentService, rootNode, contentNode, "PARTNER_1", new Timestamp(System.currentTimeMillis()));

        assertFalse(result);
        verify(ciosRepository, never()).save(any());
    }

    @Test
    void test_processLiveStatusContent_success_persistsAndIndexesContent() throws Exception {
        // createNewContent() reads externalId/contentPartner.id/contentId straight off this
        // node (ciosRepository.save()'s return value is discarded by production code, so
        // stubbing it doesn't affect what's used downstream).
        ObjectNode contentNode = realObjectMapper.createObjectNode();
        contentNode.put(Constants.CONTENT_ID, "CONTENT_1");
        contentNode.put("externalId", "EXT_1");
        ObjectNode partnerNode = realObjectMapper.createObjectNode();
        partnerNode.put("id", "PARTNER_ID_1");
        contentNode.set(Constants.CONTENT_PARTNER, partnerNode);

        ObjectNode rootNode = realObjectMapper.createObjectNode();
        rootNode.set(Constants.CONTENT, contentNode);

        doNothing().when(payloadValidation).validatePayload(anyString(), any());

        Map<String, Object> partnerResult = new HashMap<>();
        partnerResult.put(Constants.DATA, new HashMap<>());
        ApiResponse partnerResponse = new ApiResponse();
        partnerResponse.setResult(partnerResult);
        when(contentPartnerService.getContentDetailsByPartnerCode("PARTNER_1")).thenReturn(partnerResponse);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(realObjectMapper.createObjectNode()));
        when(ciosRepository.findByExternalIdAndPartnerId(any(), any())).thenReturn(Optional.empty());

        boolean result = (boolean) processLiveStatusContentMethod()
                .invoke(ciosContentService, rootNode, contentNode, "PARTNER_1", new Timestamp(System.currentTimeMillis()));

        assertTrue(result);
        assertTrue(contentNode.path(Constants.IS_ACTIVE).asBoolean());
        verify(ciosRepository).save(any());
        verify(cacheService, times(2)).putCache(any(), any());
        verify(esUtilService).addDocument(eq(Constants.CIOS_INDEX_NAME), eq(Constants.INDEX_TYPE), eq("CONTENT_1"), any(), any());
    }

    // ---- applyPublishTimeLicenceRules: paid course under a Course licence (karma coins) ----

    private Method applyPublishTimeLicenceRulesMethod() throws NoSuchMethodException {
        Method method = CiosContentServiceImpl.class.getDeclaredMethod(
                "applyPublishTimeLicenceRules", ObjectNode.class, String.class);
        method.setAccessible(true);
        return method;
    }

    @Test
    void test_applyPublishTimeLicenceRules_paidCourseUnderCourseLicence_computesKarmaCoins() throws Exception {
        Method method = applyPublishTimeLicenceRulesMethod();

        ObjectNode contentNode = realObjectMapper.createObjectNode();
        contentNode.put(Constants.COURSE_TYPE, Constants.COURSE_TYPE_PAID);
        contentNode.put(Constants.REQUIRED_KARMA_POINTS, 40);
        contentNode.put(Constants.COURSE_ENROL_LIMIT, 10);

        Map<String, Object> partnerData = new HashMap<>();
        partnerData.put(Constants.LICENCE_TYPE, Constants.LICENCE_TYPE_COURSE);
        partnerData.put(Constants.KARMA_COIN_MULTIPLIER, 1.5);
        partnerData.put(Constants.KARMA_POINTS, 20);
        partnerData.put(Constants.OVER_ALL_LIMIT, 50);
        Map<String, Object> partnerResult = new HashMap<>();
        partnerResult.put(Constants.DATA, partnerData);
        ApiResponse partnerResponse = new ApiResponse();
        partnerResponse.setResult(partnerResult);
        when(contentPartnerService.getContentDetailsByPartnerCode("PARTNER_1")).thenReturn(partnerResponse);
        when(objectMapper.convertValue(partnerData, JsonNode.class))
                .thenReturn(realObjectMapper.valueToTree(partnerData));
        when(cbServerProperties.getExtCourseCompatibilityLevel()).thenReturn(7);

        boolean result = (boolean) method.invoke(ciosContentService, contentNode, "PARTNER_1");

        assertTrue(result);
        assertEquals(60, contentNode.path(Constants.REQUIRED_KARMA_COINS).asInt());
        assertEquals(7, contentNode.path(Constants.COMPATIBILITY_LEVEL).asInt());
    }

    @Test
    void test_applyPublishTimeLicenceRules_paidCourseWithZeroKarmaCoin_setsCompatibilityLevelToZero() throws Exception {
        Method method = applyPublishTimeLicenceRulesMethod();

        ObjectNode contentNode = realObjectMapper.createObjectNode();
        contentNode.put(Constants.COURSE_TYPE, Constants.COURSE_TYPE_PAID);
        contentNode.put(Constants.REQUIRED_KARMA_POINTS, 0);
        contentNode.put(Constants.COURSE_ENROL_LIMIT, 10);

        Map<String, Object> partnerData = new HashMap<>();
        partnerData.put(Constants.LICENCE_TYPE, Constants.LICENCE_TYPE_COURSE);
        partnerData.put(Constants.KARMA_COIN_MULTIPLIER, 2.0);
        partnerData.put(Constants.KARMA_POINTS, 0);
        partnerData.put(Constants.OVER_ALL_LIMIT, 50);
        Map<String, Object> partnerResult = new HashMap<>();
        partnerResult.put(Constants.DATA, partnerData);
        ApiResponse partnerResponse = new ApiResponse();
        partnerResponse.setResult(partnerResult);
        when(contentPartnerService.getContentDetailsByPartnerCode("PARTNER_1")).thenReturn(partnerResponse);
        when(objectMapper.convertValue(partnerData, JsonNode.class))
                .thenReturn(realObjectMapper.valueToTree(partnerData));
        // Configured level is non-zero to prove it's only applied when karmaCoin > 0, not used here.
        when(cbServerProperties.getExtCourseCompatibilityLevel()).thenReturn(7);

        boolean result = (boolean) method.invoke(ciosContentService, contentNode, "PARTNER_1");

        assertTrue(result);
        assertEquals(0, contentNode.path(Constants.REQUIRED_KARMA_COINS).asInt());
        assertEquals(0, contentNode.path(Constants.COMPATIBILITY_LEVEL).asInt());
    }

    @Test
    void test_applyPublishTimeLicenceRules_paidCourseWithPositiveKarmaCoin_usesConfiguredCompatibilityLevel() throws Exception {
        Method method = applyPublishTimeLicenceRulesMethod();

        ObjectNode contentNode = realObjectMapper.createObjectNode();
        contentNode.put(Constants.COURSE_TYPE, Constants.COURSE_TYPE_PAID);
        contentNode.put(Constants.REQUIRED_KARMA_POINTS, 1);
        contentNode.put(Constants.COURSE_ENROL_LIMIT, 10);

        Map<String, Object> partnerData = new HashMap<>();
        partnerData.put(Constants.LICENCE_TYPE, Constants.LICENCE_TYPE_COURSE);
        partnerData.put(Constants.KARMA_COIN_MULTIPLIER, 1.0);
        partnerData.put(Constants.KARMA_POINTS, 0);
        partnerData.put(Constants.OVER_ALL_LIMIT, 50);
        Map<String, Object> partnerResult = new HashMap<>();
        partnerResult.put(Constants.DATA, partnerData);
        ApiResponse partnerResponse = new ApiResponse();
        partnerResponse.setResult(partnerResult);
        when(contentPartnerService.getContentDetailsByPartnerCode("PARTNER_1")).thenReturn(partnerResponse);
        when(objectMapper.convertValue(partnerData, JsonNode.class))
                .thenReturn(realObjectMapper.valueToTree(partnerData));
        when(cbServerProperties.getExtCourseCompatibilityLevel()).thenReturn(1);

        boolean result = (boolean) method.invoke(ciosContentService, contentNode, "PARTNER_1");

        assertTrue(result);
        assertEquals(1, contentNode.path(Constants.REQUIRED_KARMA_COINS).asInt());
        assertEquals(1, contentNode.path(Constants.COMPATIBILITY_LEVEL).asInt());
    }

    // ---- updateContentWithRequiredFields: compatibilityLevel is no longer stamped here; it is set only
    // for paid courses under course licence in applyPublishTimeLicenceRules ----

    @Test
    void test_updateContentWithRequiredFields_doesNotSetCompatibilityLevel() throws Exception {
        ObjectNode contentNode = realObjectMapper.createObjectNode();
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        ObjectDto objectDto = new ObjectDto();
        objectDto.setStatus(Constants.LIVE);

        Method method = CiosContentServiceImpl.class.getDeclaredMethod(
                "updateContentWithRequiredFields", ObjectNode.class, Timestamp.class, ObjectDto.class);
        method.setAccessible(true);

        method.invoke(ciosContentService, contentNode, timestamp, objectDto);

        assertFalse(contentNode.has(Constants.COMPATIBILITY_LEVEL));
    }

    @Test
    void test_updateContentWithRequiredFields_leavesExistingCompatibilityLevelUnchanged() throws Exception {
        ObjectNode contentNode = realObjectMapper.createObjectNode();
        contentNode.put(Constants.COMPATIBILITY_LEVEL, 1);
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        ObjectDto objectDto = new ObjectDto();
        objectDto.setStatus(Constants.LIVE);

        Method method = CiosContentServiceImpl.class.getDeclaredMethod(
                "updateContentWithRequiredFields", ObjectNode.class, Timestamp.class, ObjectDto.class);
        method.setAccessible(true);

        method.invoke(ciosContentService, contentNode, timestamp, objectDto);

        assertEquals(1, contentNode.path(Constants.COMPATIBILITY_LEVEL).asInt());
    }

}
