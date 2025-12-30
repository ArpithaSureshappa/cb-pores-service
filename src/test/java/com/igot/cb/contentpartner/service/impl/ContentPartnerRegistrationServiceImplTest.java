package com.igot.cb.contentpartner.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.contentpartner.entity.ContentPartnerRegistrationEntity;
import com.igot.cb.contentpartner.repository.ContentPartnerRegistrationRepository;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.pores.util.PayloadValidation;
import com.igot.cb.producer.Producer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.util.*;

@ExtendWith(MockitoExtension.class)
class ContentPartnerRegistrationServiceImplTest {

    @Mock
    private PayloadValidation payloadValidation;
    @Mock
    private ContentPartnerRegistrationRepository registrationRepository;
    @Mock
    private CacheService cacheService;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private CbServerProperties cbServerProperties;
    @Mock
    private EsUtilService esUtilService;
    @Mock
    private AccessTokenValidator accessTokenValidator;
    @Mock
    private Producer kafkaProducer;

    @InjectMocks
    private ContentPartnerRegistrationServiceImpl service;

    private final ObjectMapper realMapper = new ObjectMapper();
    private final String token = "dummy-token";

    @BeforeEach
    void setUp() {
        // Manually inject the @Autowired kafkaProducer field
        ReflectionTestUtils.setField(service, "kafkaProducer", kafkaProducer);
    }

    @Test
    void testCreate_Success() {
        ObjectNode request = realMapper.createObjectNode();
        request.put("contentPartnerName", "Org1");
        request.put("email", "org1@gmail.com");

        when(registrationRepository.findByContentPartnerOrganizationName("Org1"))
                .thenReturn(Optional.empty());
        when(registrationRepository.findByContentPartnerEmail("org1@gmail.com"))
                .thenReturn(Optional.empty());

        ContentPartnerRegistrationEntity saved = new ContentPartnerRegistrationEntity();
        String generatedId = UUID.randomUUID().toString();
        saved.setId(generatedId);
        saved.setCreatedOn(new Timestamp(System.currentTimeMillis()));
        saved.setUpdatedOn(saved.getCreatedOn());
        saved.setData(request);

        when(registrationRepository.save(any(ContentPartnerRegistrationEntity.class)))
                .thenReturn(saved);

        when(objectMapper.convertValue(any(), eq(Map.class)))
                .thenReturn(new HashMap<>());
        when(cbServerProperties.getElasticContentPartnerJsonPath())
                .thenReturn("elastic-path");
        when(cbServerProperties.getContentPartnerRegistrationTopic())
                .thenReturn("content-partner-topic");

        ApiResponse response = service.insert(request);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(registrationRepository).save(any());
        verify(esUtilService).addDocument(anyString(), anyString(), anyString(), anyMap(), anyString());
        verify(cacheService).putCache(anyString(), any());
        // Capture and verify the Kafka event
        ArgumentCaptor<Map<String, Object>> eventCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kafkaProducer).push(eq("content-partner-topic"), eventCaptor.capture());

        Map<String, Object> capturedEvent = eventCaptor.getValue();
        assertEquals(Constants.PENDING, capturedEvent.get("status"));
        assertEquals("org1@gmail.com", capturedEvent.get("email"));
        assertEquals("Org1", capturedEvent.get("partnerName"));
        assertNotNull(capturedEvent.get("registrationId"));
    }


    @Test
    void testCreate_OrgNameExists() {
        ObjectNode req = realMapper.createObjectNode();
        req.put("contentPartnerName", "ExistingOrg");
        req.put("email", "new@gmail.com");

        when(registrationRepository.findByContentPartnerOrganizationName("ExistingOrg"))
                .thenReturn(Optional.of(new ContentPartnerRegistrationEntity()));

        ApiResponse response = service.insert(req);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Organization Name already registered",
                response.getParams().getErrMsg());

        // Verify no Kafka message was sent
        verify(kafkaProducer, never()).push(anyString(), any());
    }


    @Test
    void testCreate_EmailExists() {
        ObjectNode req = realMapper.createObjectNode();
        req.put("contentPartnerName", "Org2");
        req.put("email", "existing@gmail.com");

        when(registrationRepository.findByContentPartnerOrganizationName("Org2"))
                .thenReturn(Optional.empty());
        when(registrationRepository.findByContentPartnerEmail("existing@gmail.com"))
                .thenReturn(Optional.of(new ContentPartnerRegistrationEntity()));

        ApiResponse response = service.insert(req);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Email already registered",
                response.getParams().getErrMsg());

        // Verify no Kafka message was sent
        verify(kafkaProducer, never()).push(anyString(), any());
    }


    @Test
    void testUpdate_Success() {
        when(accessTokenValidator.verifyUserToken(token)).thenReturn("user-1");

        ObjectNode req = realMapper.createObjectNode();
        req.put("id", "123");
        req.put("status", Constants.APPROVED);

        ContentPartnerRegistrationEntity existing = new ContentPartnerRegistrationEntity();
        existing.setId("123");

        ObjectNode data = realMapper.createObjectNode();
        data.put("status", Constants.PENDING);
        data.put("email", "partner@example.com");
        data.put("contentPartnerName", "Test Partner");
        existing.setData(data);

        when(registrationRepository.findById("123")).thenReturn(Optional.of(existing));
        when(registrationRepository.save(any())).thenReturn(existing);
        when(objectMapper.convertValue(any(), eq(Map.class)))
                .thenReturn(new HashMap<>());
        when(cbServerProperties.getElasticContentPartnerJsonPath()).thenReturn("path");
        when(cbServerProperties.getContentPartnerRegistrationTopic()).thenReturn("content-partner-topic");

        ApiResponse resp = service.update(req, token);

        assertEquals(HttpStatus.OK, resp.getResponseCode());
        verify(esUtilService).updateDocument(anyString(), anyString(), anyString(), anyMap(), anyString());
        verify(cacheService).putCache(eq("123"), any());

        // Capture and verify the Kafka event
        ArgumentCaptor<Map<String, Object>> eventCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kafkaProducer).push(eq("content-partner-topic"), eventCaptor.capture());

        Map<String, Object> capturedEvent = eventCaptor.getValue();
        assertEquals(Constants.APPROVED, capturedEvent.get("status"));
        assertEquals("partner@example.com", capturedEvent.get("email"));
        assertEquals("Test Partner", capturedEvent.get("partnerName"));
        assertEquals("123", capturedEvent.get("registrationId"));
    }

    @Test
    void testUpdate_Success_Rejected() {
        when(accessTokenValidator.verifyUserToken(token)).thenReturn("user-1");

        ObjectNode req = realMapper.createObjectNode();
        req.put("id", "456");
        req.put("status", Constants.REJECTED);

        ContentPartnerRegistrationEntity existing = new ContentPartnerRegistrationEntity();
        existing.setId("456");

        ObjectNode data = realMapper.createObjectNode();
        data.put("status", Constants.PENDING);
        data.put("email", "rejected@example.com");
        data.put("contentPartnerName", "Rejected Partner");
        existing.setData(data);

        when(registrationRepository.findById("456")).thenReturn(Optional.of(existing));
        when(registrationRepository.save(any())).thenReturn(existing);
        when(objectMapper.convertValue(any(), eq(Map.class)))
                .thenReturn(new HashMap<>());
        when(cbServerProperties.getElasticContentPartnerJsonPath()).thenReturn("path");
        when(cbServerProperties.getContentPartnerRegistrationTopic()).thenReturn("content-partner-topic");

        ApiResponse resp = service.update(req, token);

        assertEquals(HttpStatus.OK, resp.getResponseCode());

        // Capture and verify the Kafka event
        ArgumentCaptor<Map<String, Object>> eventCaptor = ArgumentCaptor.forClass(Map.class);
        verify(kafkaProducer).push(eq("content-partner-topic"), eventCaptor.capture());

        Map<String, Object> capturedEvent = eventCaptor.getValue();
        assertEquals(Constants.REJECTED, capturedEvent.get("status"));
        assertEquals("rejected@example.com", capturedEvent.get("email"));
        assertEquals("Rejected Partner", capturedEvent.get("partnerName"));
        assertEquals("456", capturedEvent.get("registrationId"));
    }

    @Test
    void testUpdate_InvalidStatus() {
        when(accessTokenValidator.verifyUserToken(token)).thenReturn("user-1");

        ObjectNode req = realMapper.createObjectNode();
        req.put("id", "123");
        req.put("status", "INVALID_VALUE");

        ApiResponse resp = service.update(req, token);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getResponseCode());
        assertEquals("Invalid status. Allowed values: APPROVED, REJECTED", resp.getParams().getErrMsg());

        // Verify no Kafka message was sent
        verify(kafkaProducer, never()).push(anyString(), any());
    }

    @Test
    void testUpdate_MissingFields() {
        when(accessTokenValidator.verifyUserToken(token)).thenReturn("user-1");

        ObjectNode req = realMapper.createObjectNode();
        req.put("id", "123");

        ApiResponse resp = service.update(req, token);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getResponseCode());
        assertEquals("id and status are required", resp.getParams().getErrMsg());

        // Verify no Kafka message was sent
        verify(kafkaProducer, never()).push(anyString(), any());
    }

    @Test
    void testUpdate_NotFound() {
        when(accessTokenValidator.verifyUserToken(token)).thenReturn("user-1");

        ObjectNode req = realMapper.createObjectNode();
        req.put("id", "missing-id");
        req.put("status", Constants.APPROVED);

        when(registrationRepository.findById("missing-id")).thenReturn(Optional.empty());

        ApiResponse resp = service.update(req, token);

        assertEquals(HttpStatus.NOT_FOUND, resp.getResponseCode());
        assertEquals("Content Partner Registration not found", resp.getParams().getErrMsg());

        // Verify no Kafka message was sent
        verify(kafkaProducer, never()).push(anyString(), any());
    }

    @Test
    void testUpdate_Unauthorized() {
        when(accessTokenValidator.verifyUserToken(token)).thenReturn(Constants.UNAUTHORIZED);

        ObjectNode req = realMapper.createObjectNode();
        req.put("id", "123");
        req.put("status", Constants.APPROVED);

        ApiResponse resp = service.update(req, token);

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getResponseCode());

        // Verify no Kafka message was sent
        verify(kafkaProducer, never()).push(anyString(), any());
    }

    // READ TEST CASES
    @Test
    void testRead_Success_FromDatabase() {

        String id = "test-id-123";

        ContentPartnerRegistrationEntity entity =
                new ContentPartnerRegistrationEntity();
        entity.setId(id);

        when(registrationRepository.findById(id))
                .thenReturn(Optional.of(entity));

        when(objectMapper.convertValue(entity, Map.class))
                .thenReturn(Map.of("id", id));

        ApiResponse response = service.read(id, null);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(id, response.getResult().get("id"));
    }


    @Test
    void testRead_NotFound() {

        String id = "unknown";
        when(registrationRepository.findById(id)).thenReturn(Optional.empty());
        ApiResponse response = service.read(id, null);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.INVALID_ID, response.getParams().getErrMsg());
    }

    @Test
    void testRead_EmptyId() {
        ApiResponse response = service.read("", null);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.ERR_ID_OR_EMAIL_REQUIRED, response.getParams().getErrMsg());
    }
    @Test
    void testRead_CacheException() throws Exception {
        String id = "test-id";
        when(registrationRepository.findById(id)).thenThrow(new RuntimeException("DB error"));
        ApiResponse response = service.read(id, null);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertTrue(response.getParams().getErrMsg().contains("DB error")
        );
    }
    @Test
    void testRead_ByEmail_Success() throws Exception {
        String email = "org1@gmail.com";
        String id = "id-1";
        ContentPartnerRegistrationEntity entity = new ContentPartnerRegistrationEntity();
        entity.setId(id);
        SearchResult searchResult = new SearchResult();
        searchResult.setData(realMapper.valueToTree(List.of(Map.of(Constants.ID, id))));
        when(esUtilService.searchDocuments(eq(Constants.CONTENT_PARTNER_REGISTRATION_INDEX_NAME), any(SearchCriteria.class))).thenReturn(searchResult);
        when(registrationRepository.findById(id))
                .thenReturn(Optional.of(entity));
        when(objectMapper.convertValue(entity, Map.class))
                .thenReturn(Map.of("email", email));
        ApiResponse response = service.read(null, email);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(email, response.getResult().get("email"));
    }

    @Test
    void testRead_ByEmail_NotFoundInES() throws Exception {
        SearchResult searchResult = new SearchResult();
        searchResult.setData(realMapper.createArrayNode());
        when(esUtilService.searchDocuments(
                eq(Constants.CONTENT_PARTNER_REGISTRATION_INDEX_NAME),
                any(SearchCriteria.class)
        )).thenReturn(searchResult);
        ApiResponse response = service.read(null, "noone@gmail.com");
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.INVALID_EMAIL, response.getParams().getErrMsg());
    }

    @Test
    void testRead_ByIdAndEmail_NotFound() throws Exception {
        String email = "a@b.com";
        String providedId = "1";
        String esId = "2";
        SearchResult searchResult = new SearchResult();
        searchResult.setData(realMapper.valueToTree(List.of(Map.of(Constants.ID, esId))));
        when(esUtilService.searchDocuments(
                eq(Constants.CONTENT_PARTNER_REGISTRATION_INDEX_NAME),
                any(SearchCriteria.class)
        )).thenReturn(searchResult);
        ApiResponse response = service.read(providedId, email);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.INVALID_ID_OR_EMAIL, response.getParams().getErrMsg());
    }

    // SEARCH TEST CASES
    @Test
    void testSearch_Success() throws Exception {
        when(accessTokenValidator.verifyUserToken(token)).thenReturn("user-1");

        SearchCriteria criteria = new SearchCriteria();
        criteria.setSearchString("Org");

        SearchResult mockResult = new SearchResult();
        when(esUtilService.searchDocuments(anyString(), eq(criteria)))
                .thenReturn(mockResult);

        Map<String,Object> convertedResult = Map.of("total", 5);
        when(objectMapper.convertValue(eq(mockResult), any(TypeReference.class)))
                .thenReturn(convertedResult);

        ApiResponse response = service.searchEntity(criteria, token);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(convertedResult, response.getResult());
    }


    @Test
    void testSearch_MinCharactersValidation() {
        when(accessTokenValidator.verifyUserToken(token)).thenReturn("user-1");

        SearchCriteria criteria = new SearchCriteria();
        criteria.setSearchString("ab");   // < 3 chars = invalid

        ApiResponse response = service.searchEntity(criteria, token);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Minimum 3 characters are required to search",
                response.getParams().getErrMsg());
    }

    @Test
    void testSearch_Exception() throws Exception {
        when(accessTokenValidator.verifyUserToken(token)).thenReturn("user-1");

        SearchCriteria criteria = new SearchCriteria();
        criteria.setSearchString("Org");

        when(esUtilService.searchDocuments(anyString(), eq(criteria)))
                .thenThrow(new RuntimeException("ES lookup failed"));

        ApiResponse response = service.searchEntity(criteria, token);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertTrue(response.getParams().getErrMsg().contains("ES lookup failed"));
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    @Test
    void testSearch_Unauthorized() {
        when(accessTokenValidator.verifyUserToken(token)).thenReturn(Constants.UNAUTHORIZED);

        SearchCriteria criteria = new SearchCriteria();
        criteria.setSearchString("Org");

        ApiResponse response = service.searchEntity(criteria, token);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getResponseCode());
    }
}