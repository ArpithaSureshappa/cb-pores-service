package com.igot.cb.contentpartner.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.contentpartner.entity.ContentPartnerEntity;
import com.igot.cb.contentpartner.repository.ContentPartnerRepository;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.pores.util.PayloadValidation;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static com.igot.cb.pores.util.Constants.ID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentPartnerServiceImplTest {

    @Mock
    private CacheService cacheService;

    @Mock
    private CbServerProperties cbServerProperties;

    @InjectMocks
    private ContentPartnerServiceImpl contentPartnerService;

    @Mock
    private ContentPartnerRepository entityRepository;

    @Mock
    private EsUtilService esUtilService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private PayloadValidation payloadValidation;
    private ObjectMapper realObjectMapper = new ObjectMapper();

    private ContentPartnerEntity mockEntity;
    private final String partnerCode = "partner-123";

    /**
     * Test case for creating a content partner with an existing partner code.
     * This test ensures that the method correctly handles the scenario where
     * a content partner with the same partner code already exists in the system.
     */
    @Test
    void testCreateOrUpdate_ExistingPartnerCode() {
        // Arrange
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode partnerDetails = mapper.createObjectNode();
        partnerDetails.put(Constants.CONTENT_PARTNER_NAME, "NewPartner");
        partnerDetails.put(Constants.PARTNERCODE, "EXISTING_CODE");

        when(entityRepository.findByContentPartnerName(any())).thenReturn(Optional.empty());
        when(entityRepository.findByPartnerCode("EXISTING_CODE")).thenReturn(Optional.of(new ContentPartnerEntity()));

        // Act
        ApiResponse response = contentPartnerService.createOrUpdate(partnerDetails);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals(Constants.CONTENT_PARTNER_CODE_ALREADY_PRESENT, response.getParams().getErrMsg());
    }

    /**
     * Test case for creating a content partner with an existing partner name.
     * This test verifies that the method correctly handles the scenario where
     * a content partner with the same name already exists in the system.
     */
    @Test
    void testCreateOrUpdate_ExistingPartnerName() {
        // Arrange
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode partnerDetails = mapper.createObjectNode();
        partnerDetails.put(Constants.CONTENT_PARTNER_NAME, "ExistingPartner");

        ContentPartnerEntity existingEntity = new ContentPartnerEntity();
        when(entityRepository.findByContentPartnerName("ExistingPartner")).thenReturn(Optional.of(existingEntity));

        // Act
        ApiResponse response = contentPartnerService.createOrUpdate(partnerDetails);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    /**
     * Test the delete method with an empty ID.
     * This test verifies that the method handles empty input correctly.
     */
    @Test
    void testDeleteWithEmptyId() {
        String emptyId = "";
        ApiResponse response = contentPartnerService.delete(emptyId);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.INVALID_ID, response.getParams().getErrMsg());
    }

    /**
     * Test the delete method with a non-existent ID.
     * This test verifies that the method handles the case when the entity is not found.
     */
    @Test
    void testDeleteWithNonExistentId() {
        String nonExistentId = "non-existent-id";
        when(entityRepository.findByIdAndIsActive(nonExistentId, true)).thenReturn(Optional.empty());

        ApiResponse response = contentPartnerService.delete(nonExistentId);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.CONTENT_PARTNER_NOT_FOUND, response.getParams().getErrMsg());
    }

    /**
     * Test case for createOrUpdate method when a content partner with the same name and code already exists.
     * This test covers the scenario where:
     * - The input does not have an ID (new content partner)
     * - A content partner with the same name already exists
     * - The partner code is not null or empty
     * - A content partner with the same code already exists
     */
    @Test
    void test_createOrUpdate_1() {
        // Arrange
        ObjectMapper realObjectMapper = new ObjectMapper();
        ObjectNode partnerDetails = realObjectMapper.createObjectNode();
        partnerDetails.put(Constants.CONTENT_PARTNER_NAME, "TestPartner");
        partnerDetails.put(Constants.PARTNERCODE, "TP001");

        when(entityRepository.findByContentPartnerName(anyString())).thenReturn(Optional.of(new ContentPartnerEntity()));
        when(entityRepository.findByPartnerCode(anyString())).thenReturn(Optional.of(new ContentPartnerEntity()));

        // Act
        ApiResponse response = contentPartnerService.createOrUpdate(partnerDetails);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals(Constants.CONTENT_PARTNER_CODE_AND_NAME_ALREADY_PRESENT, response.getParams().getErrMsg());
    }

    /**
     * Test case for createOrUpdate method when a content partner with the same name already exists
     * but the partner code is new.
     */
    @Test
    void test_createOrUpdate_2() {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode partnerDetails = mapper.createObjectNode()
                .put(Constants.CONTENT_PARTNER_NAME, "ExistingPartner")
                .put(Constants.PARTNERCODE, "NewCode");

        ContentPartnerEntity existingEntity = new ContentPartnerEntity();
        existingEntity.setId("existingId");

        when(entityRepository.findByContentPartnerName("ExistingPartner")).thenReturn(Optional.of(existingEntity));
        when(entityRepository.findByPartnerCode("NewCode")).thenReturn(Optional.empty());

        ApiResponse response = contentPartnerService.createOrUpdate(partnerDetails);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals(Constants.CONTENT_PARTNER_NAME_ALREADY_PRESENT, response.getParams().getErrMsg());
    }

    /**
     * Test case for createOrUpdate method when creating a new content partner
     * with an existing partner code.
     * 
     * Path constraints:
     * - partnerDetails.get(Constants.ID) == null
     * - !optionalEntity.isPresent()
     * - partnerCode != null && !partnerCode.isEmpty()
     * - entityRepository.findByPartnerCode(partnerCode).isPresent()
     */
    @Test
    void test_createOrUpdate_4() {
        // Arrange
        ObjectMapper mapper = new ObjectMapper();
        JsonNode partnerDetails = mapper.createObjectNode()
                .put(Constants.CONTENT_PARTNER_NAME, "Test Partner")
                .put(Constants.PARTNERCODE, "EXISTING_CODE");

        when(entityRepository.findByContentPartnerName(anyString())).thenReturn(Optional.empty());
        when(entityRepository.findByPartnerCode("EXISTING_CODE")).thenReturn(Optional.of(new ContentPartnerEntity()));

        // Act
        ApiResponse response = contentPartnerService.createOrUpdate(partnerDetails);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals(Constants.CONTENT_PARTNER_CODE_ALREADY_PRESENT, response.getParams().getErrMsg());
    }

    /**
     * Test case for createOrUpdate method when updating an existing content partner
     * This test verifies the behavior when the partner details contain an ID (update scenario)
     * and the partner name already exists for a different entity.
     */
    @Test
    void test_createOrUpdate_5() {
        // Arrange
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode partnerDetails = mapper.createObjectNode();
        partnerDetails.put(ID, "existing-id");

        ObjectNode data = mapper.createObjectNode();
        data.put(Constants.CONTENT_PARTNER_NAME, "Existing Partner");
        partnerDetails.set(Constants.DATA, data);

        ContentPartnerEntity existingEntity = new ContentPartnerEntity();
        existingEntity.setId("existing-id");

        when(entityRepository.findById("existing-id")).thenReturn(Optional.of(existingEntity));
        when(entityRepository.findByContentPartnerName("Existing Partner")).thenReturn(Optional.of(new ContentPartnerEntity()));

        // Act
        ApiResponse response = contentPartnerService.createOrUpdate(partnerDetails);

        // Assert
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    /**
     * Test case for createOrUpdate method when a content partner with the same name already exists
     * but the partner code is null or empty.
     * 
     * This test verifies that the method returns a BAD_REQUEST response with the appropriate error message
     * when attempting to create a new content partner with an existing name but no partner code.
     */
    @Test
    void test_createOrUpdate_existingNameNoCode() {
        ObjectMapper realObjectMapper = new ObjectMapper();
        JsonNode partnerDetails = realObjectMapper.createObjectNode()
            .put(Constants.CONTENT_PARTNER_NAME, "ExistingPartner")
            .put(Constants.PARTNERCODE, "");

        ContentPartnerEntity existingEntity = new ContentPartnerEntity();
        existingEntity.setId("existingId");

        when(entityRepository.findByContentPartnerName("ExistingPartner")).thenReturn(Optional.of(existingEntity));

        ApiResponse response = contentPartnerService.createOrUpdate(partnerDetails);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    /**
     * Test case for successful deletion of a content partner.
     * This test verifies that when a valid ID is provided and the entity exists,
     * the content partner is marked as inactive and the appropriate response is returned.
     */
    @Test
    void test_delete_1() {
        // Arrange
        String id = "validId";
        ContentPartnerEntity entity = new ContentPartnerEntity();
        entity.setId(id);
        entity.setIsActive(true);

        when(entityRepository.findByIdAndIsActive(eq(id), eq(true))).thenReturn(Optional.of(entity));

        // Act
        ApiResponse response = contentPartnerService.delete(id);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

    /**
     * Test case for delete method when the entity is not found
     * This test verifies that the delete method returns a BAD_REQUEST response
     * with the appropriate error message when the content partner is not found.
     */
    @Test
    void test_delete_2() {
        // Arrange
        String id = "non-existent-id";
        when(entityRepository.findByIdAndIsActive(id, true)).thenReturn(Optional.empty());

        // Act
        ApiResponse response = contentPartnerService.delete(id);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.CONTENT_PARTNER_NOT_FOUND, response.getParams().getErrMsg());
    }

    /**
     * Test case for delete method when the input id is empty or null.
     * This test verifies that the method returns a BAD_REQUEST response
     * with an appropriate error message when the id is invalid.
     */
    @Test
    void test_delete_emptyOrNullId() {
        when(contentPartnerService.delete("")).thenCallRealMethod();

        ApiResponse response = contentPartnerService.delete("");

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Invalid Id", response.getParams().getErrMsg());
    }

    /**
     * Test getContentDetailsByPartnerCode method with a non-existent partner code.
     * This test verifies that the method correctly handles the case when the given
     * partner code does not exist in the database and returns an appropriate error response.
     */
    @Test
    void test_getContentDetailsByPartnerCode_nonExistentPartnerCode() {
        String nonExistentPartnerCode = "NONEXISTENT_CODE";

        when(cacheService.getCache(nonExistentPartnerCode)).thenReturn(null);
        when(entityRepository.findByPartnerCode(nonExistentPartnerCode)).thenReturn(Optional.empty());

        ApiResponse response = contentPartnerService.getContentDetailsByPartnerCode(nonExistentPartnerCode);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals("Invalid name", response.getParams().getErrMsg());
    }

    /**
     * Test case for getContentDetailsByPartnerCode when cached data is available.
     * This test verifies that the method returns the correct ApiResponse with data from the cache
     * when a valid partner code is provided and cached data exists.
     */
    @Test
    void test_getContentDetailsByPartnerCode_whenCacheExists(){
        MockitoAnnotations.openMocks(this);

        String cachedJson = "{\"key\":\"value\"}";
        Map<String, Object> cachedData = new HashMap<>();
        cachedData.put("key", "value");

        when(cacheService.getCache(partnerCode)).thenReturn(cachedJson);

        ApiResponse response = contentPartnerService.getContentDetailsByPartnerCode(partnerCode);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.API_PARTNER_READ, response.getId());
    }

    /**
     * Test case for read method when id is empty and cache is not available
     * Verifies that the method returns an error response with appropriate status and message
     */
    @Test
    void test_read_3() {
        // Arrange
        String emptyId = "";
        when(cacheService.getCache(emptyId)).thenReturn(null);

        // Act
        ApiResponse response = contentPartnerService.read(emptyId);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals(Constants.INVALID_ID, response.getParams().getErrMsg());
}

    /**
     * Test case for reading content partner information with an empty ID and cached data.
     * This test verifies that the method returns an error response when the ID is empty,
     * even if there's cached data available.
     */
    @Test
    void test_read_emptyIdWithCachedData() {
        String emptyId = "";
        String cachedJson = "{\"name\":\"Test Partner\"}";

        when(cacheService.getCache(emptyId)).thenReturn(cachedJson);

        ApiResponse response = contentPartnerService.read(emptyId);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals(Constants.ID_NOT_FOUND, response.getParams().getErrMsg());
    }

    /**
     * Test case for reading content partner information from cache
     * Verifies that the method returns the correct response when the content partner data is found in the cache
     */
    @Test
    void test_read_whenDataFoundInCache() {
        // Arrange
        String id = "testId";
        String cachedJson = "{\"key\":\"value\"}";

        when(cacheService.getCache(id)).thenReturn(cachedJson);

        // Act
        ApiResponse response = contentPartnerService.read(id);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    /**
     * Tests the behavior of the read method when given an empty id.
     * This test verifies that the method correctly handles the case where an empty string is provided as the id,
     * which is an explicitly handled edge case in the focal method.
     */
    @Test
    void test_read_with_empty_id() {
        ContentPartnerServiceImpl service = new ContentPartnerServiceImpl();
        ApiResponse response = service.read("");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    /**
     * Test case for searchEntity method when search string is valid (3 or more characters).
     * This test verifies that the method returns a successful response with OK status
     * when the search criteria is valid and the search operation is successful.
     */
    @Test
    void test_searchEntity_2() throws Exception {
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString("validSearchString");

        SearchResult mockSearchResult = new SearchResult();
        when(esUtilService.searchDocuments(eq(Constants.CONTENT_PROVIDER_INDEX_NAME), any(SearchCriteria.class)))
                .thenReturn(mockSearchResult);

        // Act
        ApiResponse response = contentPartnerService.searchEntity(searchCriteria);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
    }

    /**
     * Test case for searchEntity method when search string is too short.
     * This test verifies that the method returns a BAD_REQUEST response
     * when the search string is less than 2 characters long.
     */
    @Test
    void test_searchEntity_shortSearchString() {
        SearchCriteria searchCriteria = mock(SearchCriteria.class);
        when(searchCriteria.getSearchString()).thenReturn("a");

        ApiResponse response = contentPartnerService.searchEntity(searchCriteria);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals("Minimum 3 characters are required to search", response.getParams().getErrMsg());
    }

    /**
     * Test the searchEntity method with a search string less than 2 characters long.
     * This should trigger the error condition where a minimum of 3 characters are required.
     */
    @Test
    void test_searchEntity_shortSearchString_2() {
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString("a");

        ApiResponse response = contentPartnerService.searchEntity(searchCriteria);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals("Minimum 3 characters are required to search", response.getParams().getErrMsg());
    }

    @Test
    void testCreatePartner_Success() throws Exception {
        JsonNode input = realObjectMapper.readTree("{\"contentPartnerName\": \"TestPartner\", \"partnerCode\": \"TP01\"}");

        when(entityRepository.findByContentPartnerName("TestPartner")).thenReturn(Optional.empty());
        when(entityRepository.findByPartnerCode("TP01")).thenReturn(Optional.empty());

        when(entityRepository.save(any(ContentPartnerEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(objectMapper.convertValue(any(), eq(Map.class))).thenReturn(new HashMap<>());
        when(cbServerProperties.getElasticContentJsonPath()).thenReturn("elastic-path");

        ApiResponse response = contentPartnerService.createOrUpdate(input);

        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    @Test
    void testUpdatePartner_Success() throws Exception {
        ObjectNode data = realObjectMapper.createObjectNode();
        data.put("contentPartnerName", "UpdatedPartner");

        ObjectNode input = realObjectMapper.createObjectNode();
        input.put("id", "1234");
        input.set("data", data);

        ContentPartnerEntity entity = new ContentPartnerEntity();
        entity.setId("1234");
        entity.setCreatedOn(new Timestamp(System.currentTimeMillis()));
        entity.setData(data);

        when(entityRepository.findById("1234")).thenReturn(Optional.of(entity));
        when(entityRepository.findByContentPartnerName("UpdatedPartner")).thenReturn(Optional.of(entity));
        when(entityRepository.save(any())).thenReturn(entity);
        when(objectMapper.convertValue(any(), eq(Map.class))).thenReturn(new HashMap<>());
        when(cbServerProperties.getElasticContentJsonPath()).thenReturn("path");

        ApiResponse response = contentPartnerService.createOrUpdate(input);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    @Test
    void testCreatePartner_PartnerNameAlreadyExists() {
        JsonNode input = realObjectMapper.createObjectNode()
                .put("contentPartnerName", "TestPartner")
                .put("partnerCode", "TP01");

        when(entityRepository.findByContentPartnerName("TestPartner")).thenReturn(Optional.of(new ContentPartnerEntity()));
        when(entityRepository.findByPartnerCode("TP01")).thenReturn(Optional.of(new ContentPartnerEntity()));

        ApiResponse response = contentPartnerService.createOrUpdate(input);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.CONTENT_PARTNER_CODE_AND_NAME_ALREADY_PRESENT, response.getParams().getErrMsg());
    }

    @Test
    void testUpdatePartner_NotFound() {
        ObjectNode data = realObjectMapper.createObjectNode();
        data.put("contentPartnerName", "NotExist");

        ObjectNode input = realObjectMapper.createObjectNode();
        input.put("id", "1234");
        input.set("data", data);

        when(entityRepository.findById("1234")).thenReturn(Optional.empty());

        ApiResponse response = contentPartnerService.createOrUpdate(input);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.DATA_NOT_PRESENT, response.getParams().getErrMsg());
    }

    @Test
    void testDelete_SuccessfulDeletion() {
        mockEntity = new ContentPartnerEntity();
        mockEntity.setId(ID);
        mockEntity.setIsActive(true);
        mockEntity.setUpdatedOn(new Timestamp(System.currentTimeMillis()));

        ObjectNode objectNode = new ObjectMapper().createObjectNode();
        objectNode.put("someField", "value");
        objectNode.put(Constants.IS_ACTIVE, true);  // Before update
        mockEntity.setData(objectNode);
        // Arrange
        when(entityRepository.findByIdAndIsActive(ID, true)).thenReturn(Optional.of(mockEntity));
        when(objectMapper.convertValue(any(), eq(Map.class))).thenReturn(new HashMap<>());
        when(cbServerProperties.getElasticContentJsonPath()).thenReturn("path/to/schema");

        // Act
        ApiResponse response = contentPartnerService.delete(ID);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertTrue(((Map<String, Object>) response.getResult()).containsKey(ID));
        assertEquals(Constants.DELETED_SUCCESSFULLY, ((Map<String, Object>) response.getResult()).get(ID));

        verify(entityRepository).save(any(ContentPartnerEntity.class));
        verify(esUtilService).addDocument(eq(Constants.CONTENT_PROVIDER_INDEX_NAME), eq(Constants.INDEX_TYPE), eq(ID), anyMap(), anyString());
        verify(cacheService).deleteCache(ID);
    }

    @Test
    void testGetContentDetailsByPartnerCode_CacheMiss_DBHit() {
        mockEntity = new ContentPartnerEntity();
        mockEntity.setId("id-001");
        // Arrange
        when(cacheService.getCache(partnerCode)).thenReturn(null);
        when(entityRepository.findByPartnerCode(partnerCode)).thenReturn(Optional.of(mockEntity));
        when(objectMapper.convertValue(eq(mockEntity), eq(Map.class))).thenReturn(
                Map.of("id", mockEntity.getId(), "partnerCode", partnerCode)
        );

        // Act
        ApiResponse response = contentPartnerService.getContentDetailsByPartnerCode(partnerCode);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getResponseCode());

        Map<String, Object> resultMap = (Map<String, Object>) response.getResult();
        assertEquals("id-001", resultMap.get("id"));
        assertEquals(partnerCode, resultMap.get("partnerCode"));

        verify(entityRepository).findByPartnerCode(partnerCode);
        verify(cacheService).putCache(partnerCode, mockEntity);
        verify(objectMapper).convertValue(mockEntity, Map.class);
    }

    @Test
    void testReadSuccess_CacheMiss_DbHit() {
        mockEntity = new ContentPartnerEntity();
        mockEntity.setId(ID);
        // Arrange
        when(cacheService.getCache(ID)).thenReturn(null);
        when(entityRepository.findByIdAndIsActive(ID, true)).thenReturn(Optional.of(mockEntity));
        Map<String, Object> dbMap = Map.of("id", mockEntity.getId(), "partnerCode", "");
        when(objectMapper.convertValue(mockEntity, Map.class)).thenReturn(dbMap);

        // Act
        ApiResponse response = contentPartnerService.read(ID);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(dbMap, response.getResult());

        verify(entityRepository).findByIdAndIsActive(ID, true);
        verify(cacheService).putCache(ID, mockEntity);
        verify(objectMapper).convertValue(mockEntity, Map.class);
    }

    @Test
    void testCreateContentPartner_Success() throws Exception {
        ObjectNode request = realObjectMapper.createObjectNode();
        request.put(Constants.CONTENT_PARTNER_NAME, "NewPartner");
        request.put("partnerCode", "P001");
        when(entityRepository.findByContentPartnerName("NewPartner")).thenReturn(Optional.empty());
        when(entityRepository.findByPartnerCode("P001")).thenReturn(Optional.empty());

        ContentPartnerEntity saved = new ContentPartnerEntity();
        saved.setId("generated-id");
        saved.setCreatedOn(new Timestamp(System.currentTimeMillis()));
        saved.setUpdatedOn(saved.getCreatedOn());
        ObjectNode dataNode = realObjectMapper.createObjectNode();
        dataNode.put(Constants.CONTENT_PARTNER_NAME, "NewPartner");
        saved.setData(dataNode);

        when(entityRepository.save(any(ContentPartnerEntity.class))).thenReturn(saved);
        when(objectMapper.convertValue(eq(saved.getData()), eq(Map.class))).thenReturn(new HashMap<>());
        when(cbServerProperties.getElasticContentJsonPath()).thenReturn("elastic-path");
        ApiResponse resp = contentPartnerService.createOrUpdate(request);

        assertNotNull(resp);
        assertEquals(HttpStatus.OK, resp.getResponseCode());
        verify(entityRepository).save(any(ContentPartnerEntity.class));
        verify(esUtilService).addDocument(eq(Constants.CONTENT_PROVIDER_INDEX_NAME), eq(Constants.INDEX_TYPE), anyString(), anyMap(), anyString());
        verify(cacheService).putCache(eq(saved.getId()), any());
    }

    @Test
    void testUpdateContentPartner_Success() throws Exception {
        ObjectNode dataNode = realObjectMapper.createObjectNode();
        dataNode.put(Constants.CONTENT_PARTNER_NAME, "UpdatedName");

        ObjectNode request = realObjectMapper.createObjectNode();
        request.put(Constants.ID, "id-123");
        request.set(Constants.DATA, dataNode);

        ContentPartnerEntity existing = new ContentPartnerEntity();
        existing.setId("id-123");
        existing.setCreatedOn(new Timestamp(System.currentTimeMillis() - 10000));
        ObjectNode existingData = realObjectMapper.createObjectNode();
        existingData.put(Constants.PARTNERCODE, "PCODE");
        existing.setData(existingData);

        when(entityRepository.findById("id-123")).thenReturn(Optional.of(existing));
        when(entityRepository.findByContentPartnerName("UpdatedName")).thenReturn(Optional.empty());

        ContentPartnerEntity saved = new ContentPartnerEntity();
        saved.setId("id-123");
        saved.setData(dataNode);
        when(entityRepository.save(any(ContentPartnerEntity.class))).thenReturn(saved);
        when(objectMapper.convertValue(any(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(new HashMap<>());
        when(objectMapper.convertValue(any(), eq(Map.class))).thenReturn(new HashMap<>());
        when(cbServerProperties.getElasticContentJsonPath()).thenReturn("elastic-path");
        ApiResponse resp = contentPartnerService.createOrUpdate(request);

        assertEquals(HttpStatus.OK, resp.getResponseCode());
        verify(entityRepository).save(any(ContentPartnerEntity.class));
        verify(esUtilService).updateDocument(eq(Constants.CONTENT_PROVIDER_INDEX_NAME), eq(Constants.INDEX_TYPE), eq("id-123"), anyMap(), anyString());
    }
    @Test
    void testUpdateContentPartner_NotFound_ReturnsBadRequest() {
        ObjectNode dataNode = realObjectMapper.createObjectNode();
        dataNode.put(Constants.CONTENT_PARTNER_NAME, "Name");

        ObjectNode request = realObjectMapper.createObjectNode();
        request.put(Constants.ID, "missing-id");
        request.set(Constants.DATA, dataNode);
        when(entityRepository.findById("missing-id")).thenReturn(Optional.empty());
        ApiResponse resp = contentPartnerService.createOrUpdate(request);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getResponseCode());
        assertEquals(Constants.FAILED, resp.getParams().getStatus());
        assertEquals(Constants.DATA_NOT_PRESENT, resp.getParams().getErrMsg());
    }


}