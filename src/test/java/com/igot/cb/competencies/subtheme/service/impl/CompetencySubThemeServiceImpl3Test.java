package com.igot.cb.competencies.subtheme.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompetencySubThemeServiceImpl3Test {
    @InjectMocks
    private CompetencySubThemeServiceImpl service;

    @Mock
    private ObjectMapper objectMapper;
    @Mock private PayloadValidation payloadValidation;
    @Mock private EsUtilService esUtilService;
    @Mock private CacheService cacheService;
    @Mock private CbServerProperties cbServerProperties;
    @Mock private FileProcessService fileProcessService;
    @Mock private AccessTokenValidator accessTokenValidator;
    @Mock private RedisTemplate<String, SearchResult> redisTemplate;
    @Mock private CompetencySubThemeRepository repository;
    @Mock private OutboundRequestHandlerServiceImpl outboundRequestHandlerServiceImpl;
    @Mock private DesignationService designationService;

    @Mock
    private ValueOperations<String, SearchResult> valueOperations;

    @Spy
    @InjectMocks
    CompetencySubThemeServiceImpl spyService;


    @Mock private MultipartFile mockFile;

    @BeforeEach
    void setUp() {
        service = new CompetencySubThemeServiceImpl();
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "searchResultRedisTtl", 3600L);
        // Set other mocks
        ReflectionTestUtils.setField(service, "payloadValidation", payloadValidation);
        ReflectionTestUtils.setField(service, "esUtilService", esUtilService);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        ReflectionTestUtils.setField(service, "cbServerProperties", cbServerProperties);
        ReflectionTestUtils.setField(service, "fileProcessService", fileProcessService);
        ReflectionTestUtils.setField(service, "accessTokenValidator", accessTokenValidator);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "competencySubThemeRepository", repository);
        ReflectionTestUtils.setField(service, "outboundRequestHandlerServiceImpl", outboundRequestHandlerServiceImpl);
        ReflectionTestUtils.setField(service, "designationService", designationService);
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
    }
    @Test
    void testLoadCompetencySubTheme_success_newEntriesAdded() throws Exception {
        String token = "token123";
        String userId = "user-xyz";
        when(accessTokenValidator.verifyUserToken(token)).thenReturn(userId);
        when(esUtilService.isIndexPresent(Constants.COMP_SUB_THEME_INDEX_NAME)).thenReturn(true);

        SearchResult searchResult = new SearchResult();
        JsonNode existingData = new ObjectMapper().readTree("[]");
        searchResult.setData(existingData);
        when(esUtilService.searchDocuments(any(), any())).thenReturn(searchResult);

        List<Map<String, String>> excelData = List.of(Map.of(Constants.COMPETENCY_SUB_THEME_TYPE, "NewTitle"));
        when(fileProcessService.processExcelFile(mockFile)).thenReturn(excelData);

        when(repository.count()).thenReturn(5L);

        service.loadCompetencySubTheme(mockFile, token);

        verify(repository, times(1)).saveAll(anyList());
        verify(esUtilService, atLeastOnce()).addDocument(any(), any(), any(), any(), any());
        verify(cacheService, atLeastOnce()).putCache(any(), any());
    }

    @Test
    void testLoadCompetencySubTheme_duplicateTitle_skipped() throws Exception {
        String token = "token123";
        String userId = "user-xyz";
        when(accessTokenValidator.verifyUserToken(token)).thenReturn(userId);
        when(esUtilService.isIndexPresent(Constants.COMP_SUB_THEME_INDEX_NAME)).thenReturn(true);

        JsonNode existingData = new ObjectMapper().readTree("[{\"title\": \"existingtitle\"}]");
        SearchResult searchResult = new SearchResult();
        searchResult.setData(existingData);
        when(esUtilService.searchDocuments(any(), any())).thenReturn(searchResult);

        List<Map<String, String>> excelData = List.of(Map.of(Constants.COMPETENCY_SUB_THEME_TYPE, "ExistingTitle"));
        when(fileProcessService.processExcelFile(mockFile)).thenReturn(excelData);

        service.loadCompetencySubTheme(mockFile, token);

        verify(esUtilService, never()).addDocument(any(), any(), any(), any(), any());
    }

    @Test
    void testLoadCompetencySubTheme_esThrowsException() throws Exception {
        String token = "token123";
        when(accessTokenValidator.verifyUserToken(token)).thenReturn("user");

        when(esUtilService.isIndexPresent(Constants.COMP_SUB_THEME_INDEX_NAME)).thenReturn(true);
        when(esUtilService.searchDocuments(any(), any())).thenThrow(new RuntimeException("ES down"));

        CustomException ex = assertThrows(CustomException.class, () ->
                service.loadCompetencySubTheme(mockFile, token)
        );

        assertEquals("error while fetching data from Es for validation", ex.getCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getHttpStatusCode());
    }

    @Test
    void testLoadCompetencySubTheme_nullUserId_noProcessing() {
        when(accessTokenValidator.verifyUserToken(any())).thenReturn(null);
        service.loadCompetencySubTheme(mockFile, "some-token");

        verifyNoInteractions(fileProcessService);
    }

    @Test
    void testLoadCompetencySubTheme_indexNotPresent() throws Exception {
        String token = "token123";
        when(accessTokenValidator.verifyUserToken(token)).thenReturn("user-123");
        when(esUtilService.isIndexPresent(Constants.COMP_SUB_THEME_INDEX_NAME)).thenReturn(false);

        when(fileProcessService.processExcelFile(mockFile)).thenReturn(List.of());

        service.loadCompetencySubTheme(mockFile, token);

    }

    @Test
    void testCreateCompSubTheme_success() throws Exception {
        String token = "valid-token";
        String userId = "user-123";
        long count = 10L;
        String title = "Digital Literacy";

        JsonNode inputNode = JsonNodeFactory.instance.objectNode().put(Constants.TITLE, title);
        ArrayNode emptyArray = JsonNodeFactory.instance.arrayNode();

        SearchResult result = new SearchResult();
        result.setData(emptyArray);

        when(accessTokenValidator.verifyUserToken(token)).thenReturn(userId);
        when(esUtilService.isIndexPresent(Constants.COMP_SUB_THEME_INDEX_NAME)).thenReturn(true);
        when(esUtilService.searchDocuments(any(), any())).thenReturn(result);
        when(repository.count()).thenReturn(count);
        when(cbServerProperties.getElasticCompJsonPath()).thenReturn("some/path");

        ArgumentCaptor<CompetencySubThemeEntity> entityCaptor = ArgumentCaptor.forClass(CompetencySubThemeEntity.class);

        CustomResponse response = service.createCompSubTheme(inputNode.deepCopy(), token);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFULLY_CREATED, response.getMessage());
        verify(repository).save(entityCaptor.capture());
        assertTrue(entityCaptor.getValue().getId().startsWith("COMSUBTHEME-"));
        verify(cacheService).putCache(any(), any());
    }

    @Test
    void testCreateCompSubTheme_duplicateEntry() throws Exception {
        String token = "valid-token";
        String userId = "user-123";
        String title = "Digital Literacy";

        JsonNode existingNode = JsonNodeFactory.instance.objectNode().put(Constants.TITLE, title);
        ArrayNode dataArray = JsonNodeFactory.instance.arrayNode().add(existingNode);

        JsonNode inputNode = JsonNodeFactory.instance.objectNode().put(Constants.TITLE, title);

        SearchResult result = new SearchResult();
        result.setData(dataArray);

        when(accessTokenValidator.verifyUserToken(token)).thenReturn(userId);
        when(esUtilService.isIndexPresent(Constants.COMP_SUB_THEME_INDEX_NAME)).thenReturn(true);
        when(esUtilService.searchDocuments(any(), any())).thenReturn(result);

        CustomResponse response = service.createCompSubTheme(inputNode.deepCopy(), token);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Already Present", response.getParams().getErrmsg());
    }

    @Test
    void testCreateCompSubTheme_unauthorizedUser() {
        String token = "invalid-token";

        when(accessTokenValidator.verifyUserToken(token)).thenReturn(Constants.UNAUTHORIZED);

        JsonNode inputNode = JsonNodeFactory.instance.objectNode().put(Constants.TITLE, "AnyTitle");

        CustomResponse response = service.createCompSubTheme(inputNode.deepCopy(), token);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.USER_ID_DOESNT_EXIST, response.getParams().getErrmsg());
    }

    @Test
    void testCreateCompSubTheme_esException() throws Exception {
        String token = "token";
        String userId = "user";
        when(accessTokenValidator.verifyUserToken(token)).thenReturn(userId);
        when(esUtilService.isIndexPresent(any())).thenReturn(true);
        when(esUtilService.searchDocuments(any(), any())).thenThrow(new RuntimeException("ES error"));

        JsonNode inputNode = JsonNodeFactory.instance.objectNode().put(Constants.TITLE, "SomeTitle");

        CustomException ex = assertThrows(CustomException.class,
                () -> service.createCompSubTheme(inputNode.deepCopy(), token));

        assertEquals("ES error", ex.getMessage());
    }

    @Test
    void testUpdateCompSubTheme_success() {
        JsonNode updateNode = new ObjectMapper().createObjectNode().put(Constants.ID, "COMP001").put("title", "New Title");

        CompetencySubThemeEntity entity = new CompetencySubThemeEntity();
        ObjectNode dataNode = new ObjectMapper().createObjectNode().put("title", "Old Title");
        entity.setData(dataNode);
        entity.setUpdatedOn(new Timestamp(System.currentTimeMillis()));

        when(repository.findById("COMP001")).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenReturn(entity);

        CustomResponse response = service.updateCompSubTheme(updateNode);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFULLY_UPDATED, response.getMessage());
    }

    @Test
    void testUpdateCompSubTheme_missingId() {
        JsonNode updateNode = new ObjectMapper().createObjectNode();

        CustomResponse response = service.updateCompSubTheme(updateNode);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Id is missing", response.getMessage());
    }

    @Test
    void testUpdateCompSubTheme_noDataFound() {
        JsonNode updateNode = new ObjectMapper().createObjectNode().put(Constants.ID, "NOT_FOUND");

        when(repository.findById("NOT_FOUND")).thenReturn(Optional.empty());

        CustomResponse response = service.updateCompSubTheme(updateNode);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("No data found for this id", response.getMessage());
    }

    @Test
    void testUpdateCompSubTheme_exceptionThrown() {
        JsonNode updateNode = new ObjectMapper().createObjectNode().put(Constants.ID, "COMP001");

        when(repository.findById("COMP001")).thenThrow(new RuntimeException("DB error"));

        assertThrows(RuntimeException.class, () -> service.updateCompSubTheme(updateNode));
    }

    @Test
    void testCreateTerm_InternalServerError() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        // Build request JSON with all required non-null strings
        ObjectNode request = mapper.createObjectNode();
        request.put(Constants.NAME, "term-name");
        request.put(Constants.REF_ID, "REF123");
        request.put(Constants.FRAMEWORK, "fw1");
        request.put(Constants.CATEGORY, "cat1");
        request.put("someKeyUsedInCreateTerm", "nonNullValue");

        ObjectNode additionalProps = mapper.createObjectNode();
        additionalProps.put(Constants.PARENT_CATEGORY, "parent1");
        additionalProps.put(Constants.PREV_TERM_CODE, "prevCode");
        additionalProps.put("someOtherKeyUsed", "nonNullValue");
        request.set(Constants.ADDITIONAL_PROPERTIES, additionalProps);

        // Mock repository to return active entity
        CompetencySubThemeEntity entity = new CompetencySubThemeEntity();
        entity.setIsActive(true);
        when(repository.findByIdAndIsActive("REF123", true)).thenReturn(Optional.of(entity));

        // Mock frameworkRead to return NOT_FOUND
        ApiResponse readResponse = new ApiResponse();
        readResponse.setResponseCode(HttpStatus.NOT_FOUND);
        when(designationService.frameworkRead("fw1", "parent1", "prevCode", "REF123")).thenReturn(readResponse);

        // Mock POST call to knowledgeMS
        when(cbServerProperties.getKnowledgeMS()).thenReturn("http://mock-knowledge-ms/");
        when(cbServerProperties.getOdcsTermCrete()).thenReturn("odcs/term/create");

        Map<String, Object> termResult = Map.of(
                Constants.RESPONSE_CODE, "OK",
                Constants.RESULT, Map.of(Constants.NODE_ID, List.of("TERM001"))
        );
        when(outboundRequestHandlerServiceImpl.fetchResultUsingPost(any(), any())).thenReturn(termResult);

        // Setup for updateCompSubTheme (do not mock it)
        JsonNode updateNode = mapper.createObjectNode().put(Constants.ID, "COMP001").put("title", "New Title");

        CompetencySubThemeEntity entityForUpdate = new CompetencySubThemeEntity();
        ObjectNode dataNode = mapper.createObjectNode().put("title", "Old Title");
        entityForUpdate.setData(dataNode);
        entityForUpdate.setUpdatedOn(new Timestamp(System.currentTimeMillis()));

        when(repository.findById("COMP001")).thenReturn(Optional.of(entityForUpdate));
        when(repository.save(any())).thenReturn(entityForUpdate);
        when(objectMapper.valueToTree(anyString())).thenReturn(updateNode);
        CompetencySubThemeEntity entity1 = new CompetencySubThemeEntity();
        ObjectNode dataNode1 = new ObjectMapper().createObjectNode().put("title", "Old Title");
        entity1.setData(dataNode1);
        entity1.setUpdatedOn(new Timestamp(System.currentTimeMillis()));

        when(repository.findById("COMP001")).thenReturn(Optional.of(entity1));
        when(repository.save(any())).thenReturn(entity1);

        // Now call the method under test (no mocking of updateCompSubTheme)
        ApiResponse response = service.createTerm(request);

        // Assertions
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    @Test
    void testCreateTerm_successfulCreation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        // Build request JSON with all required non-null strings
        ObjectNode request = mapper.createObjectNode();
        request.put(Constants.NAME, "term-name");
        request.put(Constants.REF_ID, "REF123");
        request.put(Constants.FRAMEWORK, "fw1");
        request.put(Constants.CATEGORY, "cat1");
        request.put("someKeyUsedInCreateTerm", "nonNullValue");

        ObjectNode additionalProps = mapper.createObjectNode();
        additionalProps.put(Constants.PARENT_CATEGORY, "parent1");
        additionalProps.put(Constants.PREV_TERM_CODE, "prevCode");
        additionalProps.put("someOtherKeyUsed", "nonNullValue");
        request.set(Constants.ADDITIONAL_PROPERTIES, additionalProps);

        // Mock repository to return active entity
        CompetencySubThemeEntity entity = new CompetencySubThemeEntity();
        entity.setIsActive(true);
        when(repository.findByIdAndIsActive("REF123", true)).thenReturn(Optional.of(entity));

        // Mock frameworkRead to return NOT_FOUND
        ApiResponse readResponse = new ApiResponse();
        readResponse.setResponseCode(HttpStatus.NOT_FOUND);
        when(designationService.frameworkRead("fw1", "parent1", "prevCode", "REF123")).thenReturn(readResponse);

        // Mock POST call to knowledgeMS
        when(cbServerProperties.getKnowledgeMS()).thenReturn("http://mock-knowledge-ms/");
        when(cbServerProperties.getOdcsTermCrete()).thenReturn("odcs/term/create");

        Map<String, Object> termResult = Map.of(
                Constants.RESPONSE_CODE, "OK",
                Constants.RESULT, Map.of(Constants.NODE_ID, List.of("TERM001"))
        );
        when(outboundRequestHandlerServiceImpl.fetchResultUsingPost(any(), any())).thenReturn(termResult);

        // Setup for updateCompSubTheme (do not mock it)
        JsonNode updateNode = mapper.createObjectNode().put(Constants.ID, "COMP001").put("title", "New Title");

        CompetencySubThemeEntity entityForUpdate = new CompetencySubThemeEntity();
        ObjectNode dataNode = mapper.createObjectNode().put("title", "Old Title");
        entityForUpdate.setData(dataNode);
        entityForUpdate.setUpdatedOn(new Timestamp(System.currentTimeMillis()));

        when(repository.findById("COMP001")).thenReturn(Optional.of(entityForUpdate));
        when(repository.save(any())).thenReturn(entityForUpdate);
        when(objectMapper.valueToTree(anyString())).thenReturn(updateNode);

        // Now call the method under test (no mocking of updateCompSubTheme)
        ApiResponse response = service.createTerm(request);

        // Assertions
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }




    @Test
    void testCreateTerm_termAlreadyExists() {
        ObjectNode request = new ObjectMapper().createObjectNode()
                .put(Constants.NAME, "term")
                .put(Constants.REF_ID, "REF123")
                .put(Constants.FRAMEWORK, "fw")
                .put(Constants.CATEGORY, "cat");
        ObjectNode addProps = new ObjectMapper().createObjectNode()
                .put(Constants.PARENT_CATEGORY, "p")
                .put(Constants.PREV_TERM_CODE, "prev");
        request.set(Constants.ADDITIONAL_PROPERTIES, addProps);

        CompetencySubThemeEntity entity = new CompetencySubThemeEntity();
        entity.setIsActive(true);
        when(repository.findByIdAndIsActive("REF123", true)).thenReturn(Optional.of(entity));

        ApiResponse readResponse = new ApiResponse();
        readResponse.setResponseCode(HttpStatus.CONFLICT);
        when(designationService.frameworkRead("fw", "p", "prev", "REF123")).thenReturn(readResponse);

        ApiResponse response = service.createTerm(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }
    @Test
    void testCreateTerm_entityNotActive() {
        ObjectNode request = createValidRequest();

        CompetencySubThemeEntity entity = new CompetencySubThemeEntity();
        entity.setIsActive(false);
        when(repository.findByIdAndIsActive("REF123", true)).thenReturn(Optional.of(entity));

        ApiResponse response = service.createTerm(request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    @Test
    void testCreateTerm_termNotExist() {
        ObjectNode request = createValidRequest();

        when(repository.findByIdAndIsActive("REF123", true)).thenReturn(Optional.empty());

        ApiResponse response = service.createTerm(request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Term Not Exist.", response.getParams().getErr());
    }

    @Test
    void testCreateTerm_validationFails() {
        ObjectNode request = createValidRequest();

        doThrow(new CustomException("Invalid payload", "details", HttpStatus.BAD_REQUEST))
                .when(payloadValidation).validatePayload(any(), any());

        ApiResponse response = service.createTerm(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("details", response.getParams().getErr());
    }

    private ObjectNode createValidRequest() {
        ObjectNode request = new ObjectMapper().createObjectNode();
        request.put(Constants.NAME, "term");
        request.put(Constants.REF_ID, "REF123");
        request.put(Constants.FRAMEWORK, "fw");
        request.put(Constants.CATEGORY, "cat");
        ObjectNode additional = new ObjectMapper().createObjectNode();
        additional.put(Constants.PARENT_CATEGORY, "parent");
        additional.put(Constants.PREV_TERM_CODE, "prev");
        request.set(Constants.ADDITIONAL_PROPERTIES, additional);
        return request;
    }

    @Test
    void testProcessSubDesignation_fullCoverage() throws Exception {
        // Prepare input maps
        Map<String, Object> designation = new HashMap<>();
        Map<String, Object> newDesignation = new HashMap<>();

        // Mock fields expected in sub-designation
        List<String> odcsFields = List.of("field1", "field2", Constants.IDENTIFIER);
        when(cbServerProperties.getOdcsFields()).thenReturn(odcsFields);

        // Original designation list
        Map<String, Object> desig1 = new HashMap<>();
        desig1.put(Constants.IDENTIFIER, "desig1");
        desig1.put("field1", "value1");
        desig1.put("field2", "value2");

        Map<String, Object> desig2 = new HashMap<>();
        desig2.put(Constants.IDENTIFIER, "desig2");
        desig2.put("field1", "value3");
        desig2.put("field2", "value4");

        // Duplicate entry to test `continue`
        Map<String, Object> desigDuplicate = new HashMap<>();
        desigDuplicate.put(Constants.IDENTIFIER, "desig1");
        desigDuplicate.put("field1", "value5");

        designation.put(Constants.CHILDREN, List.of(desig1, desig2, desigDuplicate));
        newDesignation.put(Constants.CHILDREN, new ArrayList<>());

        // Use reflection to invoke the private method
        Method method = CompetencySubThemeServiceImpl.class.getDeclaredMethod("processSubDesignation", Map.class, Map.class);
        method.setAccessible(true);
        method.invoke(service, designation, newDesignation);

        // Validate result
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) newDesignation.get(Constants.CHILDREN);
        assertEquals(2, resultList.size());

        // First designation
        assertEquals("value1", resultList.get(0).get("field1"));
        assertEquals("value2", resultList.get(0).get("field2"));

        // Second designation
        assertEquals("value3", resultList.get(1).get("field1"));
        assertEquals("value4", resultList.get(1).get("field2"));
    }

    @Test
    void testSearchCompSubTheme_fetchFromRedis() {
        SearchCriteria criteria = new SearchCriteria();
        criteria.setSearchString("example");

        SearchResult redisResult = new SearchResult();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(redisResult);

        CustomResponse response = service.searchCompSubTheme(criteria);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertTrue(response.getResult().containsKey(Constants.RESULT));
        assertEquals(redisResult, response.getResult().get(Constants.RESULT));
    }

    @Test
    void testSearchCompSubTheme_esThrowsException_redisSetInvoked() throws Exception {
        SearchCriteria criteria = new SearchCriteria();
        criteria.setSearchString("errorTest");

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(esUtilService.searchDocuments(anyString(), any()))
                .thenThrow(new RuntimeException("ES failed"));

        CustomResponse response = service.searchCompSubTheme(criteria);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals(Constants.FAILED_CONST, response.getParams().getStatus());
    }

    @Test
    void testReadCompSubTheme_invalidIdFromDb_shouldLogErrorAndReturnNotFound() {
        String id = "invalidId";

        when(cacheService.getCache(id)).thenReturn(null);
        when(repository.findByIdAndIsActive(id, true)).thenReturn(Optional.empty());

        CustomResponse response = service.readCompSubTheme(id);

        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals(Constants.INVALID_ID, response.getMessage());
    }

    @Test
    void deleteCompetencySubTheme_whenExceptionThrown_shouldReturnInternalServerError() {
        String id = "someId";

        when(repository.findByIdAndIsActive(id, true))
                .thenThrow(new RuntimeException("DB error"));

        CustomResponse response = service.deleteCompetencySubTheme(id);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("DB error", response.getMessage());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

}

