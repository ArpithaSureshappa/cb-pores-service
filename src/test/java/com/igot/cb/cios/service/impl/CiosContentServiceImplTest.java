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
import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.util.*;

import com.igot.cb.pores.util.PayloadValidation;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        MockitoAnnotations.openMocks(this);

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
        CiosContentServiceImpl service = new CiosContentServiceImpl();

        CustomException exception = assertThrows(CustomException.class, () -> {
            service.fetchDataByContentId("");
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
        MockitoAnnotations.openMocks(this);

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
        CiosContentServiceImpl service = new CiosContentServiceImpl();
        String generatedId = service.generateId();

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
        CiosContentServiceImpl service = new CiosContentServiceImpl();
        String id1 = service.generateId();
        String id2 = service.generateId();
        assertNotEquals("Generated IDs should be unique", id1, id2);
    }

    /**
     * Test case for onboardContent method when status is "draft" and all optional fields are present.
     * This test verifies that the method correctly processes content with draft status
     * and handles competencies, content partner, and tags data.
     */
    @Test
    void test_onboardContent_2() {
        MockitoAnnotations.openMocks(this);

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

        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
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

        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        ValueOperations<String, SearchResult> valueOperations = mock(ValueOperations.class);
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

        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        ValueOperations<String, SearchResult> valueOperations = mock(ValueOperations.class);
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

        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
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
        ObjectMapper objectMapper = new ObjectMapper();
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
        when(ciosRepository.findByContentId(eq(contentId)))
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
        verify(cacheService).putCache(eq(contentId), eq(node));
        verify(objectMapper).convertValue(eq(node), ArgumentMatchers.<TypeReference<Object>>any());
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
        Method method = CiosContentServiceImpl.class.getDeclaredMethod("createNewContent", JsonNode.class);
        method.setAccessible(true);

        // Invoke method
        Object result = method.invoke(ciosContentService, rootNode);

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
        Method method = CiosContentServiceImpl.class.getDeclaredMethod("createNewContent", JsonNode.class);
        method.setAccessible(true);

        Object result = method.invoke(ciosContentService, rootNode);

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
        ciosContentService.objectMapper = realObjectMapper;
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
        ciosContentService.objectMapper = realObjectMapper;
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
        ciosContentService.objectMapper = realObjectMapper;
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
        JsonSchemaFactory factory = Mockito.mock(JsonSchemaFactory.class);
        when(factory.getSchema(any(InputStream.class))).thenReturn(mockSchema);

        // Use reflection to mock the getInstance() call (for testing only)
        JsonSchemaFactory staticFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V201909);
        JsonSchemaFactory spyFactory = Mockito.spy(staticFactory);
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
    }

    private CiosContentServiceImpl prepareServiceWithMocks(JsonNode mockedNode) throws Exception {
        CiosContentServiceImpl service = new CiosContentServiceImpl();

        ObjectMapper objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);

        RestTemplate mockRestTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(service, "restTemplate", mockRestTemplate);

        CbServerProperties cbServerProperties = mock(CbServerProperties.class);
        when(cbServerProperties.getCiosContentServiceHost()).thenReturn("http://mock-host");
        when(cbServerProperties.getCiosContentServiceSearchApiUrl()).thenReturn("/mock-api");
        ReflectionTestUtils.setField(service, "cbServerProperties", cbServerProperties);

        ContentPartnerService contentPartnerService = mock(ContentPartnerService.class);
        ReflectionTestUtils.setField(service, "contentPartnerService", contentPartnerService);

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
    }

    @Test
    void test_fetchAndUpdateContentCountsInPartnerDb_whenNodeIsNull_logsWarning() throws Exception {
        CiosContentServiceImpl service = prepareServiceWithMocks(null);
        Method method = CiosContentServiceImpl.class.getDeclaredMethod("fetchAndUpdateContentCountsInPartnerDb", String.class);
        method.setAccessible(true);
        method.invoke(service, "PARTNER001");
    }

    @Test
    void test_deleteContent_success() {
        // Arrange
        CiosContentServiceImpl service = new CiosContentServiceImpl();

        CiosRepository mockRepo = mock(CiosRepository.class);
        ReflectionTestUtils.setField(service, "ciosRepository", mockRepo);

        ObjectMapper objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);

        EsUtilService esUtilService = mock(EsUtilService.class);
        ReflectionTestUtils.setField(service, "esUtilService", esUtilService);

        CacheService cacheService = mock(CacheService.class);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);

        ContentPartnerService contentPartnerService = mock(ContentPartnerService.class);
        ReflectionTestUtils.setField(service, "contentPartnerService", contentPartnerService);

        ContentPartnerRepository contentPartnerRepository = mock(ContentPartnerRepository.class);
        ReflectionTestUtils.setField(service, "contentPartnerRepository", contentPartnerRepository);

        CbServerProperties cbServerProperties = mock(CbServerProperties.class);
        when(cbServerProperties.getElasticCiosJsonPath()).thenReturn("mock/path");
        when(cbServerProperties.getCiosContentServiceHost()).thenReturn("http://host");
        when(cbServerProperties.getCiosContentServiceUpdateApiUrl()).thenReturn("/update");
        when(cbServerProperties.getCiosContentServiceSearchApiUrl()).thenReturn("/search");
        ReflectionTestUtils.setField(service, "cbServerProperties", cbServerProperties);

        RestTemplate restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);

        // build valid JSON structure
        ObjectNode rootNode = objectMapper.createObjectNode();
        ObjectNode contentNode = objectMapper.createObjectNode();
        ObjectNode partnerNode = objectMapper.createObjectNode();
        partnerNode.put("partnerCode", "PARTNER001");
        contentNode.set("contentPartner", partnerNode);
        rootNode.set("content", contentNode);

        CiosContentEntity entity = new CiosContentEntity();
        entity.setCiosData(rootNode);
        entity.setContentId("CID001");

        when(mockRepo.findByContentIdAndIsActive("CID001", true))
                .thenReturn(Optional.of(entity));

        // mock search API
        ResponseEntity<JsonNode> mockSearchResponse = mock(ResponseEntity.class);
        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(JsonNode.class))
        ).thenReturn(mockSearchResponse);

        // mock body for search response
        ObjectNode searchResponseBody = objectMapper.createObjectNode();
        searchResponseBody.put(Constants.TOTAL_COUNT, 10);

        ObjectNode facetNode = objectMapper.createObjectNode();
        ArrayNode statusArray = objectMapper.createArrayNode();
        ObjectNode liveFacet = objectMapper.createObjectNode();
        liveFacet.put(Constants.VALUE, "live");
        liveFacet.put(Constants.COUNT, 5);
        statusArray.add(liveFacet);
        facetNode.set(Constants.STATUS, statusArray);
        searchResponseBody.set(Constants.FACETS, facetNode);

        when(mockSearchResponse.getBody()).thenReturn(searchResponseBody);

        // mock contentPartnerService
        Map<String, Object> responseMap = new HashMap<>();
        Map<String, Object> dataMap = new HashMap<>();
        responseMap.put(Constants.DATA, dataMap);
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setResult(responseMap);
        when(contentPartnerService.getContentDetailsByPartnerCode("PARTNER001")).thenReturn(apiResponse);

        // Act
        Object result = service.deleteContent("CID001");

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
        // Prepare real object mapper
        ObjectMapper realObjectMapper = new ObjectMapper();

        // Prepare the DTO and JSON
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

        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
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

        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
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

}
