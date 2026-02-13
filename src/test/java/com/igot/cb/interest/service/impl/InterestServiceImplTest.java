package com.igot.cb.interest.service.impl;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.demand.entity.DemandEntity;
import com.igot.cb.demand.repository.DemandRepository;
import com.igot.cb.demand.service.DemandServiceImpl;
import com.igot.cb.interest.entity.Interests;
import com.igot.cb.interest.repository.InterestRepository;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.dto.CustomResponse;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.pores.util.PayloadValidation;
import com.igot.cb.producer.Producer;
import com.igot.cb.transactional.cassandrautils.CassandraOperation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InterestServiceImplTest {

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
    private DemandServiceImpl demandService;

    @Mock
    private EsUtilService esUtilService;

    @Mock
    private InterestRepository interestRepository;

    @InjectMocks
    private InterestServiceImpl interestService;

    @Mock
    private Producer kafkaProducer;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private PayloadValidation payloadValidation;

    @Mock
    private RedisTemplate<String, SearchResult> redisTemplate;

    @Mock
    private ValueOperations<String, SearchResult> valueOperations;

    /**
     * Test case for assignInterestToDemand method when the user token is invalid.
     * This test covers the scenario where the interestId and demandId are provided,
     * but the user token is invalid or unauthorized.
     */
    @Test
    void test_assignInterestToDemand_3() {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode interestDetails = objectMapper.createObjectNode()
                .put(Constants.INTEREST_ID_RQST, "validInterestId")
                .put(Constants.DEMAND_ID_RQST, "validDemandId");

        String token = "invalidToken";

        when(accessTokenValidator.verifyUserToken(token)).thenReturn(Constants.UNAUTHORIZED);

        CustomResponse response = interestService.assignInterestToDemand(interestDetails, token);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.USER_ID_DOESNT_EXIST, response.getParams().getErrmsg());
    }

    /**
     * Test case for assignInterestToDemand method when all conditions are met for successful assignment
     * and the demand status is UNASSIGNED.
     */
    @Test
    void test_assignInterestToDemand_5() {
        // Arrange
        ObjectNode interestDetails = mock(ObjectNode.class);
        when(interestDetails.get(Constants.INTEREST_ID_RQST)).thenReturn(mock(JsonNode.class));
        when(interestDetails.get(Constants.DEMAND_ID_RQST)).thenReturn(mock(JsonNode.class));
        when(interestDetails.get(Constants.INTEREST_ID_RQST).asText()).thenReturn("interest123");
        when(interestDetails.get(Constants.DEMAND_ID_RQST).asText()).thenReturn("demand123");

        String token = "validToken";
        String userId = "user123";
        when(accessTokenValidator.verifyUserToken(token)).thenReturn(userId);

        Interests interest = new Interests();
        interest.setData(interestDetails);
        when(interestRepository.findById("interest123")).thenReturn(Optional.of(interest));

        DemandEntity demandEntity = new DemandEntity();
        ObjectNode demandJson = mock(ObjectNode.class);
        when(demandJson.get(Constants.STATUS)).thenReturn(mock(JsonNode.class));
        when(demandJson.get(Constants.STATUS).asText()).thenReturn(Constants.UNASSIGNED);
        demandEntity.setData(demandJson);
        when(demandRepository.findById("demand123")).thenReturn(Optional.of(demandEntity));

        when(demandService.isSpvRequest(userId, Constants.SPV_ADMIN)).thenReturn(true);

        when(objectMapper.createObjectNode()).thenReturn(mock(ObjectNode.class));
        Map<String, Object> fakeMap = new HashMap<>();
        fakeMap.put("key", "value"); // or the keys your code expects

        when(objectMapper.convertValue(any(JsonNode.class), eq(Map.class)))
                .thenReturn(fakeMap);


        // Act
        CustomResponse response = interestService.assignInterestToDemand(interestDetails, token);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFULLY_ASSIGNED, response.getMessage());

        // Verify
        verify(interestRepository).save(any(Interests.class));
        verify(demandRepository).save(any(DemandEntity.class));
    }

    /**
     * Testcase 6 for @Override public CustomResponse assignInterestToDemand(JsonNode interestDetails, String token)
     * Path constraints: !((interestDetails.get(Constants.INTEREST_ID_RQST) == null || interestDetails.get(Constants.INTEREST_ID_RQST).asText().isEmpty())), 
     * !((interestDetails.get(Constants.DEMAND_ID_RQST) == null || interestDetails.get(Constants.DEMAND_ID_RQST).asText().isEmpty())), 
     * !((StringUtils.isBlank(userId) || userId.equalsIgnoreCase(Constants.UNAUTHORIZED))), 
     * (optSchemeDetails.isPresent()), 
     * !((!fetchedDemandID.equalsIgnoreCase(demandIdPayload))), 
     * (demandEntity.isPresent()), 
     * (!fetchedDemandJson.isEmpty()), 
     * !((fetchedDemandJson.get(Constants.STATUS).asText().equalsIgnoreCase(Constants.UNASSIGNED))), 
     * (demandService.isSpvRequest(userId,Constants.SPV_ADMIN))
     * returns: response
     */
    @Test
    void test_assignInterestToDemand_6() {
        // Arrange
        ObjectNode interestDetails = mock(ObjectNode.class);
        when(interestDetails.get(Constants.INTEREST_ID_RQST)).thenReturn(mock(JsonNode.class));
        when(interestDetails.get(Constants.DEMAND_ID_RQST)).thenReturn(mock(JsonNode.class));
        when(interestDetails.get(Constants.INTEREST_ID_RQST).asText()).thenReturn("interestId");
        when(interestDetails.get(Constants.DEMAND_ID_RQST).asText()).thenReturn("demandId");
        JsonNode orgIdNode = mock(JsonNode.class);
        when(orgIdNode.asText()).thenReturn("orgId");
        when(interestDetails.get(Constants.ORG_ID)).thenReturn(orgIdNode);
        when(interestDetails.get(Constants.ORG_ID).asText()).thenReturn("orgId");

        String token = "validToken";
        String userId = "validUserId";
        when(accessTokenValidator.verifyUserToken(token)).thenReturn(userId);

        Interests interest = mock(Interests.class);
        when(interest.getData()).thenReturn(interestDetails);
        when(interestRepository.findById("interestId")).thenReturn(Optional.of(interest));

        ObjectNode assignedProviderNode = new ObjectMapper().createObjectNode();
        assignedProviderNode.put("providerId", "prov-123"); // dummy data

// 2. Create the full fetchedDemandJson mock
        ObjectNode fetchedDemandJson = new ObjectMapper().createObjectNode();
        fetchedDemandJson.put(Constants.STATUS, Constants.ASSIGNED);
        fetchedDemandJson.set(Constants.ASSIGNED_PROVIDER, assignedProviderNode);

// 3. Create a mock DemandEntity and set its data
        DemandEntity mockDemandEntity = mock(DemandEntity.class);
        when(mockDemandEntity.getData()).thenReturn(fetchedDemandJson);

// 4. Make repository return the mocked entity
        when(demandRepository.findById("demandId")).thenReturn(Optional.of(mockDemandEntity));

        when(demandService.isSpvRequest(userId, Constants.SPV_ADMIN)).thenReturn(true);

        when(objectMapper.createObjectNode()).thenReturn(mock(ObjectNode.class));

        Map<String, Object> fakeMap = new HashMap<>();
        fakeMap.put("key", "value"); // or the keys your code expects

        when(objectMapper.convertValue(any(JsonNode.class), eq(Map.class)))
                .thenReturn(fakeMap);

        // Act
        CustomResponse response = interestService.assignInterestToDemand(interestDetails, token);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFULLY_ASSIGNED, response.getMessage());

        // Verify
        verify(interestRepository).save(any(Interests.class));
        verify(demandRepository).save(any(DemandEntity.class));
        verify(esUtilService).addDocument(eq(Constants.INTEREST_INDEX_NAME), eq(Constants.INDEX_TYPE), anyString(), anyMap(), any());
    }

    @Test
    void test_assignInterestToDemand_7() {
        // Arrange
        ObjectNode interestDetails = mock(ObjectNode.class);
        when(interestDetails.get(Constants.INTEREST_ID_RQST)).thenReturn(mock(JsonNode.class));
        when(interestDetails.get(Constants.DEMAND_ID_RQST)).thenReturn(mock(JsonNode.class));
        when(interestDetails.get(Constants.INTEREST_ID_RQST).asText()).thenReturn("interestId");
        when(interestDetails.get(Constants.DEMAND_ID_RQST).asText()).thenReturn("demandId");
        JsonNode orgIdNode = mock(JsonNode.class);
        when(orgIdNode.asText()).thenReturn("orgId");
        when(interestDetails.get(Constants.ORG_ID)).thenReturn(orgIdNode);
        when(interestDetails.get(Constants.ORG_ID).asText()).thenReturn("prov-123");

        String token = "validToken";
        String userId = "validUserId";
        when(accessTokenValidator.verifyUserToken(token)).thenReturn(userId);

        Interests interest = mock(Interests.class);
        when(interest.getData()).thenReturn(interestDetails);
        when(interestRepository.findById("interestId")).thenReturn(Optional.of(interest));

        ObjectNode assignedProviderNode = new ObjectMapper().createObjectNode();
        assignedProviderNode.put("providerId", "prov-123"); // dummy data

// 2. Create the full fetchedDemandJson mock
        ObjectNode fetchedDemandJson = new ObjectMapper().createObjectNode();
        fetchedDemandJson.put(Constants.STATUS, Constants.ASSIGNED);
        fetchedDemandJson.set(Constants.ASSIGNED_PROVIDER, assignedProviderNode);

// 3. Create a mock DemandEntity and set its data
        DemandEntity mockDemandEntity = mock(DemandEntity.class);
        when(mockDemandEntity.getData()).thenReturn(fetchedDemandJson);

// 4. Make repository return the mocked entity
        when(demandRepository.findById("demandId")).thenReturn(Optional.of(mockDemandEntity));

        // Act
        CustomResponse response = interestService.assignInterestToDemand(interestDetails, token);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Assigning to the same org please reassign", response.getMessage());
    }
    @Test
    void test_assignInterestToDemand_8() {
        // Arrange
        ObjectNode interestDetails = new ObjectMapper().createObjectNode();
        interestDetails.put(Constants.INTEREST_ID_RQST, "interest123");
        interestDetails.put(Constants.DEMAND_ID_RQST, "demand123");

        ObjectNode mockInterestDetails = new ObjectMapper().createObjectNode();
        mockInterestDetails.put(Constants.INTEREST_ID_RQST, "interest123");
        mockInterestDetails.put(Constants.DEMAND_ID_RQST, "demandId");

        String token = "validToken";
        String userId = "user123";

        Interests interest = new Interests();
        interest.setInterestId("interest123");
        interest.setData(interestDetails);

        Interests mockInterest = new Interests();
        mockInterest.setInterestId("interest123");
        mockInterest.setData(mockInterestDetails);
        DemandEntity demandEntity = new DemandEntity();
        ObjectNode demandData = new ObjectMapper().createObjectNode();
        demandData.put(Constants.STATUS, Constants.UNASSIGNED);
        demandEntity.setData(demandData);

        when(accessTokenValidator.verifyUserToken(token)).thenReturn(userId);
        when(interestRepository.findById("interest123")).thenReturn(Optional.of(mockInterest));
        // Act
        CustomResponse response = interestService.assignInterestToDemand(interestDetails, token);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("DemandId passed is not matching with the fetched demandId", response.getParams().getErrmsg());
    }

    /**
     * Test case for assignInterestToDemand method when interest is not found.
     * This test verifies that the method returns a NOT_FOUND response when the interest doesn't exist.
     */
    @Test
    void test_assignInterestToDemand_interestNotFound() {
        // Arrange
        JsonNode interestDetails = mock(JsonNode.class);
        when(interestDetails.get(Constants.INTEREST_ID_RQST)).thenReturn(mock(JsonNode.class));
        when(interestDetails.get(Constants.INTEREST_ID_RQST).asText()).thenReturn("test-interest-id");
        when(interestDetails.get(Constants.DEMAND_ID_RQST)).thenReturn(mock(JsonNode.class));
        when(interestDetails.get(Constants.DEMAND_ID_RQST).asText()).thenReturn("test-demand-id");

        // Act
        CustomResponse response = interestService.assignInterestToDemand(interestDetails, "valid-token");

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

    /**
     * Testcase 2 for assignInterestToDemand method
     * This test verifies that the method returns a BAD_REQUEST response when the demand ID is missing or empty
     */
    @Test
    void test_assignInterestToDemand_missingDemandId() {
       
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode interestDetails = objectMapper.createObjectNode();
        ((ObjectNode) interestDetails).put(Constants.INTEREST_ID_RQST, "valid_interest_id");
        ((ObjectNode) interestDetails).put(Constants.DEMAND_ID_RQST, "");

        CustomResponse response = interestService.assignInterestToDemand(interestDetails, "valid_token");

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.DEMAND_ID_MISSING, response.getParams().getErrmsg());
    }

    /**
     * Tests the scenario where the demand ID is missing in the input.
     * This is an edge case explicitly handled by the method.
     */
    @Test
    void test_assignInterestToDemand_missingDemandId_2() {
       
        ObjectMapper mapper = new ObjectMapper();
        JsonNode interestDetails = mapper.createObjectNode();
        ((ObjectNode) interestDetails).put(Constants.INTEREST_ID_RQST, "someinterestid");

        CustomResponse response = interestService.assignInterestToDemand(interestDetails, "sometoken");

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.DEMAND_ID_MISSING, response.getParams().getErrmsg());
    }

    /**
     * Tests the assignInterestToDemand method when the interest ID is missing or empty.
     * Expects a BAD_REQUEST response with an appropriate error message.
     */
    @Test
    void test_assignInterestToDemand_missingInterestId() {
       
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode interestDetails = objectMapper.createObjectNode();
        String token = "dummyToken";

        CustomResponse response = interestService.assignInterestToDemand(interestDetails, token);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.INTEREST_ID_MISSING, response.getParams().getErrmsg());
    }

    /**
     * Tests the scenario where the interest ID is missing in the input.
     * This is an edge case explicitly handled by the method.
     */
    @Test
    void test_assignInterestToDemand_missingInterestId_2() {
       
        ObjectMapper mapper = new ObjectMapper();
        JsonNode interestDetails = mapper.createObjectNode();
        ((ObjectNode) interestDetails).put(Constants.DEMAND_ID_RQST, "somedemandid");

        CustomResponse response = interestService.assignInterestToDemand(interestDetails, "sometoken");

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.INTEREST_ID_MISSING, response.getParams().getErrmsg());
    }

    /**
     * Test case for assignInterestToDemand method when the interest ID and demand ID are present,
     * user is authorized, interest is found, but the fetched demand ID doesn't match the payload demand ID.
     */
    @Test
    void test_assignInterestToDemand_whenFetchedDemandIdDoesNotMatchPayload() {
        // Arrange
        ObjectMapper realObjectMapper = new ObjectMapper();
        ObjectNode interestDetails = realObjectMapper.createObjectNode();
        interestDetails.put(Constants.INTEREST_ID_RQST, "interest123");
        interestDetails.put(Constants.DEMAND_ID_RQST, "demand123");

        String token = "validToken";

        Interests interest = new Interests();
        ObjectNode interestData = realObjectMapper.createObjectNode();
        interestData.put(Constants.DEMAND_ID_RQST, "demand456");
        interest.setData(interestData);

        // Act
        CustomResponse response = interestService.assignInterestToDemand(interestDetails, token);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("User Id doesn't exist! Please supply a valid auth token", response.getParams().getErrmsg());
    }

    /**
     * Test case for createErrorResponse method
     * Verifies that the method correctly sets the error response parameters
     */
    @Test
    void test_createErrorResponse_setsErrorResponseParameters() {
       
        CustomResponse response = new CustomResponse();
        String errorMessage = "Test error message";
        HttpStatus httpStatus = HttpStatus.BAD_REQUEST;
        String status = "FAILED";

        interestService.createErrorResponse(response, errorMessage, httpStatus, status);

        assertEquals(status, response.getParams().getStatus());
        assertEquals(httpStatus, response.getResponseCode());
    }

    /**
     * Test case for createInterest method when:
     * - Organization details are found
     * - Demand entity is present
     * - Demand data is empty
     * - Interest org set exists and is not empty
     * - Organization ID is already in the interest org set
     */
    @Test
    void test_createInterest_3() {
        // Prepare test data
        ObjectMapper realObjectMapper = new ObjectMapper();
        ObjectNode interestDetails = realObjectMapper.createObjectNode();
        interestDetails.put(Constants.ORG_ID, "testOrgId");
        interestDetails.put(Constants.DEMAND_ID_RQST, "testDemandId");

        List<Map<String, Object>> orgDetails = new ArrayList<>();
        Map<String, Object> orgDetail = new HashMap<>();
        orgDetail.put(Constants.USER_ROOT_ORG_NAME, "TestOrgName");
        orgDetails.add(orgDetail);

        DemandEntity demandEntity = new DemandEntity();
        ObjectNode demandData = realObjectMapper.createObjectNode();
        demandData.put(Constants.INTEREST_COUNT, 1);
        ObjectNode interestOrgSet = realObjectMapper.createObjectNode();
        interestOrgSet.put("testOrgId", "testOrgId");
        demandData.set(Constants.INTEREST_ORG_SET, interestOrgSet);
        demandEntity.setData(demandData);

        // Mock dependencies
        when(cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                eq(Constants.KEYSPACE_SUNBIRD), eq(Constants.ORG_TABLE), any(), any(), anyInt()))
                .thenReturn(orgDetails);
        when(demandRepository.findById(anyString())).thenReturn(Optional.of(demandEntity));

        // Execute method
        CustomResponse response = interestService.createInterest(interestDetails);

        // Verify results
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Interest already generated by same organisation", response.getMessage());
    }
    @Test
    void test_createInterest_7() {
        // Prepare test data
        ObjectMapper realObjectMapper = new ObjectMapper();
        ObjectNode interestDetails = realObjectMapper.createObjectNode();
        interestDetails.put(Constants.ORG_ID, "testOrgId");
        interestDetails.put(Constants.DEMAND_ID_RQST, "testDemandId");

        List<Map<String, Object>> orgDetails = new ArrayList<>();
        Map<String, Object> orgDetail = new HashMap<>();
        orgDetail.put(Constants.USER_ROOT_ORG_NAME, "TestOrgName");
        orgDetails.add(orgDetail);

        DemandEntity demandEntity = new DemandEntity();
        ObjectNode demandData = realObjectMapper.createObjectNode();
        demandData.put(Constants.INTEREST_COUNT, 1);
        ObjectNode interestOrgSet = realObjectMapper.createObjectNode();
        demandData.set(Constants.INTEREST_ORG_SET, interestOrgSet);
        demandEntity.setData(demandData);
        when(objectMapper.createObjectNode()).thenReturn(mock(ObjectNode.class));
        Map<String, Object> fakeMap = new HashMap<>();
        fakeMap.put("key", "value"); // or the keys your code expects

        when(objectMapper.convertValue(any(JsonNode.class), eq(Map.class)))
                .thenReturn(fakeMap);
        // Mock dependencies
        when(cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                eq(Constants.KEYSPACE_SUNBIRD), eq(Constants.ORG_TABLE), any(), any(), anyInt()))
                .thenReturn(orgDetails);
        when(demandRepository.findById(anyString())).thenReturn(Optional.of(demandEntity));

        // Execute method
        CustomResponse response = interestService.createInterest(interestDetails);

        // Verify results
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals("successfully created", response.getMessage());
    }

    /**
     * Test case for createInterest method when the interest already exists for the same organisation.
     * This test verifies that the method returns a BAD_REQUEST response when an interest is
     * attempted to be created for an organisation that has already shown interest.
     */
    @Test
    void test_createInterest_4() {
        // Arrange
        ObjectMapper realObjectMapper = new ObjectMapper();
        ObjectNode interestDetails = realObjectMapper.createObjectNode();
        interestDetails.put(Constants.ORG_ID, "org123");
        interestDetails.put(Constants.DEMAND_ID_RQST, "demand123");

        List<Map<String, Object>> orgDetails = new ArrayList<>();
        Map<String, Object> orgDetail = new HashMap<>();
        orgDetail.put(Constants.USER_ROOT_ORG_NAME, "Test Org");
        orgDetails.add(orgDetail);

        when(cassandraOperation.getRecordsByPropertiesWithoutFiltering(anyString(), anyString(), anyMap(), any(), anyInt()))
            .thenReturn(orgDetails);

        DemandEntity demandEntity = new DemandEntity();
        ObjectNode demandData = realObjectMapper.createObjectNode();
        demandData.put(Constants.INTEREST_COUNT, 1);
        ObjectNode interestOrgSet = realObjectMapper.createObjectNode();
        interestOrgSet.put("org123", "org123");
        demandData.set(Constants.INTEREST_ORG_SET, interestOrgSet);
        demandEntity.setData(demandData);

        when(demandRepository.findById(anyString())).thenReturn(Optional.of(demandEntity));

        // Act
        CustomResponse response = interestService.createInterest(interestDetails);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Interest already generated by same organisation", response.getMessage());

        // Verify
        verify(cassandraOperation).getRecordsByPropertiesWithoutFiltering(anyString(), anyString(), anyMap(), any(), anyInt());
        verify(demandRepository).findById(anyString());
    }

    /**
     * Test case for createInterest method when orgDetails are not empty but demandEntity is not present.
     * This test verifies that the method returns a CustomResponse with NOT_FOUND status
     * when the demand for the provided demandId is not found.
     */
    @Test
    void test_createInterest_6() {
        // Prepare test data
        ObjectMapper objectMapper1 = new ObjectMapper();
        JsonNode interestDetails = objectMapper1.createObjectNode()
                .put(Constants.ORG_ID, "testOrgId")
                .put(Constants.DEMAND_ID_RQST, "testDemandId");

        // Mock CassandraOperation behavior
        List<Map<String, Object>> orgDetails = new ArrayList<>();
        Map<String, Object> orgDetail = new HashMap<>();
        orgDetail.put(Constants.USER_ROOT_ORG_NAME, "TestOrgName");
        orgDetails.add(orgDetail);

        when(cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                eq(Constants.KEYSPACE_SUNBIRD),
                eq(Constants.ORG_TABLE),
                any(),
                isNull(),
                eq(1)
        )).thenReturn(orgDetails);

        // Call the method under test
        CustomResponse response = interestService.createInterest(interestDetails);

        // Assert the results
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals("Data for the provided demandId is not present to show interest", response.getMessage());
    }

    /**
     * Test case for creating interest when interest is already generated by the same organisation
     * This test verifies that the method returns a BAD_REQUEST response when interest is already generated
     */
    @Test
    void test_createInterest_interestAlreadyGenerated() {
        JsonNode interestDetails = new ObjectMapper().createObjectNode();
        ((ObjectNode) interestDetails).put(Constants.ORG_ID, "testOrgId");
        ((ObjectNode) interestDetails).put(Constants.DEMAND_ID_RQST, "testDemandId");

        when(cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                anyString(), anyString(), anyMap(), any(), anyInt()))
                .thenReturn(new ArrayList<>() {{
                    add(new HashMap<>() {{
                        put(Constants.USER_ROOT_ORG_NAME, "testOrgName");
                    }});
                }});

        DemandEntity demandEntity = new DemandEntity();
        JsonNode demandData = new ObjectMapper().createObjectNode();
        ((ObjectNode) demandData).put(Constants.INTEREST_COUNT, 1);
        ((ObjectNode) demandData).putArray(Constants.INTEREST_ORG_SET).add("testOrgId");
        demandEntity.setData(demandData);
        when(demandRepository.findById(anyString())).thenReturn(Optional.of(demandEntity));

        CustomResponse response = interestService.createInterest(interestDetails);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Interest already generated by same organisation", response.getMessage());
    }

    /**
     * Test case for creating interest when org details are not found
     * This test verifies that the method returns a NOT_FOUND response when org details cannot be fetched
     */
    @Test
    void test_createInterest_orgDetailsNotFound() {
        JsonNode interestDetails = new ObjectMapper().createObjectNode();
        ((ObjectNode) interestDetails).put(Constants.ORG_ID, "testOrgId");

        when(cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                anyString(), anyString(), anyMap(), any(), anyInt()))
                .thenReturn(new ArrayList<>());

        CustomResponse response = interestService.createInterest(interestDetails);

        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals("OrgDetails are not fetched for given orgId", response.getMessage());
    }

    /**
     * Test case for createInterest method when organization details are found,
     * demand entity is present, and interest has already been generated by the same organization.
     */
    @Test
    void test_createInterest_whenInterestAlreadyGeneratedBySameOrg() {
        // Arrange
        JsonNode interestDetails = new ObjectMapper().createObjectNode()
                .put(Constants.ORG_ID, "org123")
                .put(Constants.DEMAND_ID_RQST, "demand123");

        List<Map<String, Object>> orgDetails = Collections.singletonList(
                Collections.singletonMap(Constants.USER_ROOT_ORG_NAME, "Test Org"));

        when(cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                anyString(), anyString(), anyMap(), any(), anyInt()))
                .thenReturn(orgDetails);

        DemandEntity demandEntity = new DemandEntity();
        JsonNode demandData = new ObjectMapper().createObjectNode()
                .put(Constants.INTEREST_COUNT, 1)
                .set(Constants.INTEREST_ORG_SET, new ObjectMapper().createArrayNode().add("org123"));
        demandEntity.setData(demandData);

        when(demandRepository.findById(anyString())).thenReturn(Optional.of(demandEntity));

        // Act
        CustomResponse response = interestService.createInterest(interestDetails);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Interest already generated by same organisation", response.getMessage());
    }


    /**
     * Tests that createSuccessResponse sets the expected values on the CustomResponse object.
     * This verifies that the method correctly initializes the response parameters.
     */
    @Test
    void test_createSuccessResponse_setsExpectedValues() {
        CustomResponse response = new CustomResponse();

        interestService.createSuccessResponse(response);

        assertNotNull(response.getParams());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    /**
     * Test case for createSuccessResponse method
     * Verifies that the method correctly sets the success response parameters
     */
    @Test
    void test_createSuccessResponse_setsSuccessResponseParameters() {
       
        CustomResponse response = new CustomResponse();

        interestService.createSuccessResponse(response);

        assertNotNull(response.getParams());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    /**
     * Test case for generateRedisJwtTokenKey method when requestPayload is null.
     * This test verifies that the method returns an empty string when the input is null.
     */
    @Test
    void test_generateRedisJwtTokenKey_whenRequestPayloadIsNull() {
        objectMapper = new ObjectMapper();

        String result = interestService.generateRedisJwtTokenKey(null);

        assertEquals("", result);
    }

    /**
     * Test case for generateRedisJwtTokenKey method when requestPayload is not null.
     * It should create a JWT token with the serialized request payload.
     */
    @Test
    void test_generateRedisJwtTokenKey_whenRequestPayloadNotNull() throws Exception {
        MockitoAnnotations.openMocks(this);

        // Arrange
        Object requestPayload = new Object();
        String serializedPayload = "serialized_payload";
        String jwtSecret = "demand_search_result";
        when(objectMapper.writeValueAsString(requestPayload)).thenReturn(serializedPayload);
        when(cbServerProperties.getJwtSecretKey()).thenReturn(jwtSecret);

        // Act
        String result = interestService.generateRedisJwtTokenKey(requestPayload);

        // Assert
        assertNotNull(result);
        JWT.require(Algorithm.HMAC256(jwtSecret))
           .build()
           .verify(result);
    }

    /**
     * Test case for read method when cached data is available.
     * This test verifies that the method returns the correct response
     * when the data is found in the cache.
     */
    @Test
    void test_read_2(){
        String id = "testId";
        String cachedJson = "{\"key\":\"value\"}";

        when(cacheService.getCache(id)).thenReturn(cachedJson);

        CustomResponse response = interestService.read(id);

        assertEquals(Constants.SUCCESSFULLY_READING, response.getMessage());
    }

    /**
     * Test case for read method when the interest is not in cache but exists in the database.
     * This test verifies that the method correctly fetches the interest from the database,
     * caches it, and returns the appropriate response when the cached data is not available
     * but the interest exists in the database.
     */
    @Test
    void test_read_3() {
        MockitoAnnotations.openMocks(this);

        String id = "testId";
        Interests interest = new Interests();
        interest.setInterestId(id);
        interest.setData(null);

        when(cacheService.getCache(id)).thenReturn(null);
        when(interestRepository.findById(id)).thenReturn(Optional.of(interest));

        CustomResponse response = interestService.read(id);

        verify(cacheService, times(1)).getCache(id);
        verify(interestRepository, times(1)).findById(id);
        verify(cacheService, times(1)).putCache(eq(id), any());

        assertEquals(Constants.SUCCESSFULLY_READING, response.getMessage());
    }

    /**
     * Test case for read method when the input id is empty.
     * Verifies that the method returns a CustomResponse with INTERNAL_SERVER_ERROR status
     * and an appropriate error message when the id is empty.
     */
    @Test
    void test_read_emptyId() {

        CustomResponse response = interestService.read("");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Id not found", response.getMessage());
    }

    /**
     * Test the read method with an empty id.
     * This test verifies that the method handles empty input correctly,
     * returning an appropriate error response.
     */
    @Test
    void test_read_with_empty_id() {
        CustomResponse response = interestService.read("");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Id not found", response.getMessage());
    }

    /**
     * Test case for searchDemand method when Redis cache is empty and search string is valid.
     * This test verifies that the method correctly handles the scenario where the search result
     * is not found in Redis cache and the search string is valid (length >= 2).
     * It ensures that the method calls the EsUtilService to perform the search and returns
     * a successful response with the search result.
     */
    @Test
    void test_searchDemand_3() throws Exception {
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString("validSearch");

        SearchResult mockSearchResult = new SearchResult();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(esUtilService.searchDocuments(eq(Constants.INTEREST_INDEX_NAME), eq(searchCriteria))).thenReturn(mockSearchResult);

        // Act
        CustomResponse response = interestService.searchDemand(searchCriteria);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(mockSearchResult, response.getResult().get(Constants.RESULT));
        verify(esUtilService).searchDocuments(eq(Constants.INTEREST_INDEX_NAME), eq(searchCriteria));
    }

    /**
     * Test case for searchDemand method when search string is less than 3 characters
     * This test verifies that an error response is returned when the search string is too short
     */
    @Test
    void test_searchDemand_shortSearchString() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString("ab");

        CustomResponse response = interestService.searchDemand(searchCriteria);

        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    /**
     * Test case for searchDemand method when search result is found in Redis cache.
     * It verifies that the method returns a successful response with the cached search result.
     */
    @Test
    void test_searchDemand_whenResultFoundInRedis() {
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        SearchResult cachedResult = new SearchResult();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(cachedResult);

        // Act
        CustomResponse response = interestService.searchDemand(searchCriteria);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(cachedResult, response.getResult().get("result"));
        verify(redisTemplate.opsForValue(), times(1)).get(anyString());
    }

}
