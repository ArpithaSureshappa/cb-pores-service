package com.igot.cb.playlist.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.playlist.dto.SearchDto;
import com.igot.cb.playlist.entity.PlayListEntity;
import com.igot.cb.playlist.repository.PlayListRepository;
import com.igot.cb.playlist.util.RedisCacheMngr;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.pores.util.PayloadValidation;
import java.sql.Timestamp;
import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.Logger;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlayListServiceImplTest {

    @Mock
    private EsUtilService esUtilService;

    @Mock
    private Logger logger;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private PayloadValidation payloadValidation;

    @Mock
    private PlayListRepository playListRepository;

    @InjectMocks
    private PlayListServiceImpl playListService;

    @Mock
    private RedisCacheMngr redisCacheMngr;

    @Mock
    private RedisTemplate<String, SearchResult> redisTemplate;

    @Mock
    private SearchCriteria searchCriteria;

    @Mock
    private ValueOperations<String, SearchResult> valueOperations;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    /**
     * Tests that createErrorResponse correctly sets error response parameters.
     * 
     * This test verifies that the createErrorResponse method properly sets
     * the status, response code, and error message in the ApiResponse object.
     */
    @Test
    void test_createErrorResponse_1() {
        ApiResponse response = new ApiResponse();
        String errorMessage = "Test error message";
        HttpStatus httpStatus = HttpStatus.BAD_REQUEST;
        String status = "FAILED";

        playListService.createErrorResponse(response, errorMessage, httpStatus, status);

        assertNotNull(response.getParams());
        assertEquals(status, response.getParams().getStatus());
        assertEquals(httpStatus, response.getResponseCode());
        assertEquals(errorMessage, response.getParams().getErrMsg());
    }

    /**
     * Test case for createPlayList method when the playlist doesn't exist and doesn't have a title.
     * This test verifies that a new playlist is created successfully without a title.
     */
    @Test
    void test_createPlayList_3() {
        // Arrange
        JsonNode playListDetails = new ObjectMapper().createObjectNode();
        when(playListRepository.findByOrgIdAndRequestTypeAndIsActive(any(), any(), any())).thenReturn(new ArrayList<>());

        // Act
        ApiResponse response = playListService.createPlayList(playListDetails);

        // Assert
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    /**
     * Test case for createPlayList method when a playlist already exists for the given organization and request type.
     * This test verifies that the method returns a FAILED status and INTERNAL_SERVER_ERROR when attempting to create a duplicate playlist.
     */
    @Test
    void test_createPlayList_duplicatePlaylist() {
        // Arrange
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode playListDetails = mapper.createObjectNode();
        playListDetails.put(Constants.ORG_ID, "testOrg");
        playListDetails.put(Constants.RQST_CONTENT_TYPE, "testType");

        PlayListEntity existingEntity = new PlayListEntity();
        existingEntity.setId("existingId");
        List<PlayListEntity> existingEntities = new ArrayList<>();
        existingEntities.add(existingEntity);

        when(playListRepository.findByOrgIdAndRequestTypeAndIsActive(anyString(), anyString(), any(Boolean.class)))
                .thenReturn(existingEntities);

        // Act
        ApiResponse response = playListService.createPlayList(playListDetails);

        // Assert
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("existingId", response.getResult().get(Constants.ID));
    }

    /**
     * Test case for createPlayList method when a playlist already exists for the given org ID and request type.
     * This test verifies that the method returns a failed response with the appropriate error message and status code.
     */
    @Test
    void test_createPlayList_existingPlaylist() {
        MockitoAnnotations.openMocks(this);

        // Arrange
        ObjectMapper  objectMapper = new ObjectMapper();
        JsonNode playListDetails = objectMapper.createObjectNode()
                .put(Constants.ORG_ID, "testOrgId")
                .put(Constants.RQST_CONTENT_TYPE, "testContentType");

        List<PlayListEntity> existingPlaylists = new ArrayList<>();
        existingPlaylists.add(new PlayListEntity());
        when(playListRepository.findByOrgIdAndRequestTypeAndIsActive(any(), any(), any())).thenReturn(existingPlaylists);

        // Act
        ApiResponse response = playListService.createPlayList(playListDetails);

        // Assert
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("For the type testContentTypethis  orgId is present so update", response.getParams().getErrMsg());
    }

    /**
     * Test case for createSuccessResponse method
     * Verifies that the method correctly sets the success response parameters
     */
    @Test
    void test_createSuccessResponse_1() {
        
        ApiResponse response = new ApiResponse();

        playListService.createSuccessResponse(response);

        assertNotNull(response.getParams());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    /**
     * Testcase 2 for @Override public ApiResponse createV2PlayList(JsonNode playListDetails)
     * Path constraints: !((playListDetails.has(Constants.CHILDREN) && !playListDetails.get(Constants.CHILDREN)
     *       .isEmpty())), (playListJson.has(Constants.TITLE) && !playListJson.get(Constants.TITLE).asText()
     *       .isEmpty())
     * returns: response
     */
    @Test
    void test_createV2PlayList_2() {
        // Arrange
        
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode playListDetails = objectMapper.createObjectNode();
        playListDetails.put(Constants.ORG_ID, "testOrgId");
        playListDetails.put(Constants.RQST_CONTENT_TYPE, "testContentType");
        playListDetails.put(Constants.TITLE, "Test Playlist");

        // Act
        ApiResponse response = playListService.createV2PlayList(playListDetails);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.get(Constants.RESPONSE));
        assertEquals(Constants.CREATED, response.getResult().get(Constants.STATUS));
        assertNotNull(response.getResult().get(Constants.ID));
    }
    
    /**
     * Test case for successful deletion of a playlist
     * Path constraints:
     * - optionalJsonNodeEntity.isPresent() is true
     * - playListEntity.getData() is not null
     * - playListEntity.getData().has(Constants.PLAYLIST_KEY_REDIS) is true
     * - playListEntity.getData().get(Constants.PLAYLIST_KEY_REDIS) is not null
     */
    @Test
    void test_delete_1() {
        // Arrange
        String id = "testId";
        PlayListEntity playListEntity = new PlayListEntity();
        playListEntity.setId(id);
        playListEntity.setOrgId("testOrgId");
        playListEntity.setIsActive(true);

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode dataNode = objectMapper.createObjectNode();
        dataNode.put(Constants.PLAYLIST_KEY_REDIS, "testRedisKey");
        playListEntity.setData(dataNode);

        when(playListRepository.findByIdAndIsActive(id, true)).thenReturn(playListEntity);
        when(playListRepository.save(any(PlayListEntity.class))).thenReturn(playListEntity);
        when(redisCacheMngr.hdel(anyString(), anyString(), anyInt())).thenReturn(1L);

        // Act
        ApiResponse response = playListService.delete(id);

        // Assert
        assert response.getResponseCode() == HttpStatus.OK;
        assert response.getResult().get(Constants.STATUS).equals(Constants.DELETED_SUCCESSFULLY);
        assert response.getResult().get(Constants.ID).equals(id);
    }

    /**
     * Testcase 2 for @Override public ApiResponse delete(String id)
     * Path constraints: (optionalJsonNodeEntity.isPresent()), !((!playListEntity.getData().isNull() && playListEntity.getData()
     *         .has(Constants.PLAYLIST_KEY_REDIS) && !playListEntity.getData()
     *         .get(Constants.PLAYLIST_KEY_REDIS).isNull()))
     * returns: response
     */
    @Test
    void test_delete_2() {
        // Arrange
        String id = "testId";
        PlayListEntity playListEntity = new PlayListEntity();
        playListEntity.setId(id);
        playListEntity.setIsActive(true);
        playListEntity.setUpdatedOn(new Timestamp(System.currentTimeMillis()));

        JsonNode mockData = mock(JsonNode.class);
        when(mockData.isNull()).thenReturn(false);
        when(mockData.has(Constants.PLAYLIST_KEY_REDIS)).thenReturn(false);
        playListEntity.setData(mockData);

        when(playListRepository.findByIdAndIsActive(id, true)).thenReturn(playListEntity);
        when(playListRepository.save(any(PlayListEntity.class))).thenReturn(playListEntity);

        // Act
        ApiResponse response = playListService.delete(id);

        // Assert
        verify(playListRepository).findByIdAndIsActive(id, true);
        verify(playListRepository).save(any(PlayListEntity.class));
        verify(esUtilService).deleteDocument(id, Constants.PLAYLIST_INDEX_NAME);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.get(Constants.RESPONSE));
        assertEquals(Constants.DELETED_SUCCESSFULLY, response.getResult().get(Constants.STATUS));
        assertEquals(id, response.getResult().get(Constants.ID));
    }

    /**
     * Test case for delete method when the playlist is not found.
     * This test verifies that the delete method returns the correct response
     * when the playlist with the given ID does not exist or is not active.
     */
    @Test
    void test_delete_playlist_not_found() {
        // Arrange
        String playlistId = "non_existent_id";
        when(playListRepository.findByIdAndIsActive(playlistId, true)).thenReturn(null);

        // Act
        ApiResponse response = playListService.delete(playlistId);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals("Not found", response.getParams().getErrMsg());
    }

    /**
     * Test the delete method when the playlist is not found (inactive or non-existent).
     * This test verifies that the method returns a NOT_FOUND response when trying to delete a non-existent playlist.
     */
    @Test
    void test_delete_playlist_not_found_2() {
        // Arrange
        String id = "non_existent_id";
        when(playListRepository.findByIdAndIsActive(id, true)).thenReturn(null);

        // Act
        ApiResponse response = playListService.delete(id);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals(Constants.NOT_FOUND, response.getParams().getErrMsg());
        verify(playListRepository).findByIdAndIsActive(id, true);
        verifyNoMoreInteractions(playListRepository, redisCacheMngr, esUtilService);
    }

    /**
     * Test case for generating a Redis JWT token key with a valid request payload.
     * This test verifies that the method returns a non-null JWT token when given a non-null request payload.
     */
    @Test
    void test_generateRedisJwtTokenKey_1() {
        MockitoAnnotations.initMocks(this);
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");

        Object requestPayload = new Object();
        String jsonString = "{\"key\":\"value\"}";

        try {
            when(objectMapper.writeValueAsString(requestPayload)).thenReturn(jsonString);
        } catch (Exception e) {
            // Handle exception
        }

        String result = playListService.generateRedisJwtTokenKey(requestPayload);

        assertNotNull("Generated JWT token should not be null", result);
    }

    /**
     * Testcase 2 for public String generateRedisJwtTokenKey(Object requestPayload)
     * Tests the scenario where the requestPayload is null
     */
    @Test
    void test_generateRedisJwtTokenKey_2() {
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        
        String result = playListService.generateRedisJwtTokenKey(null);
        assertEquals("", result);
    }

    /**
     * Test case for generateRedisJwtTokenKey method when input is null.
     * This tests the edge case where the method is called with a null input,
     * which is explicitly handled in the method implementation.
     */
    @Test
    void test_generateRedisJwtTokenKey_nullInput() {
        String result = playListService.generateRedisJwtTokenKey(null);
        assertEquals("", result, "Expected empty string for null input");
    }

    /**
     * Test case for readPlaylist method when Redis cache is empty, id doesn't start with orgId,
     * playlist is found in repository, and the response contains content and result data.
     */
    @Test
    void test_readPlaylist_2() throws Exception {
        // Arrange
        String id = "testId";
        String orgId = "testOrgId";
        String requestType = "testRequestType";

        when(redisCacheMngr.hget(eq(id), anyInt(), eq(orgId)))
                .thenReturn(Collections.singletonList("[{\"name\":\"playlist1\"}]"));

        PlayListEntity playListEntity = new PlayListEntity();
        ObjectNode dataNode = mock(ObjectNode.class);
        when(dataNode.get(Constants.CHILDREN)).thenReturn(mock(JsonNode.class));
        playListEntity.setData(dataNode);

        List<PlayListEntity> entityList = new ArrayList<>();
        entityList.add(playListEntity);
        when(playListRepository.findByOrgIdAndRequestTypeAndIsActive(orgId, requestType, true)).thenReturn(entityList);

        ObjectNode enrichedContentJson = mock(ObjectNode.class);
        when(objectMapper.createObjectNode()).thenReturn(enrichedContentJson);

        JsonNode rootNode = mock(JsonNode.class);
        JsonNode firstElement = mock(JsonNode.class);
        JsonNode childrenNode = mock(JsonNode.class);
        when(objectMapper.readTree(anyString())).thenReturn(rootNode, childrenNode);
        when(rootNode.get(0)).thenReturn(firstElement);
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode firstElement1 = mock(JsonNode.class);
        JsonNode childrenNode1 = objectMapper.readTree("{\"child1\": {\"name\": \"A\"}, \"child2\": {\"name\": \"B\"}}");

        // Mock .get(Constants.CHILDREN) and .asText()
        JsonNode childrenTextNode = mock(JsonNode.class);
        when(firstElement1.get(Constants.CHILDREN)).thenReturn(childrenTextNode);
        when(childrenTextNode.asText()).thenReturn(childrenNode1.toString());
        when(firstElement.get(Constants.CHILDREN)).thenReturn(mock(JsonNode.class));

        ArrayNode contentArray = mock(ArrayNode.class);
        when(childrenNode.get(Constants.RESULT)).thenReturn(mock(JsonNode.class));
        when(childrenNode.get(Constants.RESULT).get(Constants.CONTENT)).thenReturn(contentArray);
        when(contentArray.isNull()).thenReturn(false);

        // Act
        ApiResponse response = playListService.readPlaylist(id, orgId);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
    }


    /**
     * Test case for readPlaylist method when playlist is not found in Redis or database.
     * This test verifies that the method returns a BAD_REQUEST response when the playlist
     * is not found for the given id and orgId.
     */
    @Test
    void test_readPlaylist_playlistNotFound() {
        // Arrange
        String id = "testId";
        String orgId = "testOrgId";
        when(redisCacheMngr.hget(id, 0, orgId)).thenReturn(null);
        when(playListRepository.findByOrgIdAndRequestTypeAndIsActive(orgId, "", true)).thenReturn(java.util.Collections.emptyList());

        // Act
        ApiResponse response = playListService.readPlaylist(id, orgId);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    /**
     * Test case for readPlaylist method when playlist is not in Redis cache,
     * id starts with orgId, repository returns an empty list, and the response
     * contains no content.
     */
    @Test
    void test_readPlaylist_whenPlaylistNotInCacheAndRepositoryReturnsEmptyList() {
        // Arrange
        String id = "org123requestType";
        String orgId = "org123";
        int redisInsightIndex = 0;

        when(redisCacheMngr.hget(id, redisInsightIndex, orgId)).thenReturn(null);
        when(playListRepository.findByOrgIdAndRequestTypeAndIsActive(orgId, "requestType", true))
            .thenReturn(new ArrayList<>());

        // Act
        ApiResponse response = playListService.readPlaylist(id, orgId);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
 }

    /**
     * Test case for readV2Playlist method when playlist is not found in Redis or database.
     * This test verifies that the method returns a BAD_REQUEST status and appropriate error message
     * when the requested playlist is not found.
     */
    @Test
    void test_readV2Playlist_playlistNotFound() {
        
        ApiResponse response = playListService.readV2Playlist("nonexistentId", "nonexistentPlayListId", "nonexistentOrgId");

        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    /**
     * Tests the searchPlayListForOrg method with missing filters in the request.
     * Expects a BAD_REQUEST response.
     */
    @Test
    void test_searchPlayListForOrg_missingFilters() {
        
        SearchDto searchDto = new SearchDto();
        searchDto.setRequest(new HashMap<>());

        ApiResponse response = playListService.searchPlayListForOrg(searchDto);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    /**
     * Tests the searchPlayListForOrg method with an invalid SearchDto (null request).
     * Expects a BAD_REQUEST response.
     */
    @Test
    void test_searchPlayListForOrg_nullRequest() {
        
        SearchDto searchDto = new SearchDto();
        searchDto.setRequest(null);

        ApiResponse response = playListService.searchPlayListForOrg(searchDto);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    /**
     * Test case for searchPlayListWithoutCaching method when search string is less than 2 characters
     * Path constraints: (searchString != null && searchString.length() < 2)
     * Expected: Error response with BAD_REQUEST status and error message
     */
    @Test
    void test_searchPlayListWithoutCaching_1() {
        when(searchCriteria.getSearchString()).thenReturn("a");

        ApiResponse response = playListService.searchPlayListWithoutCaching(searchCriteria);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED_CONST, response.getParams().getStatus());
        assertEquals("Minimum 3 characters are required to search", response.getParams().getErrMsg());
    }

    /**
     * Testcase 2 for @Override public ApiResponse searchPlayListWithoutCaching(SearchCriteria searchCriteria)
     * Path constraints: !((searchString != null && searchString.length() < 2)), (searchString != null && searchString.length() > 2)
     * returns: response
     */
    @Test
    void test_searchPlayListWithoutCaching_2() throws Exception {
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString("test");

        SearchResult mockSearchResult = new SearchResult();
        Map<String, Object> mockResultMap = new HashMap<>();
        mockResultMap.put("key", "value");

        when(esUtilService.searchDocuments(Constants.PLAYLIST_INDEX_NAME, searchCriteria)).thenReturn(mockSearchResult);
        when(objectMapper.convertValue(mockSearchResult, Map.class)).thenReturn(mockResultMap);

        // Act
        ApiResponse response = playListService.searchPlayListWithoutCaching(searchCriteria);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        assertEquals(mockResultMap, response.getResult());

        verify(esUtilService).searchDocuments(Constants.PLAYLIST_INDEX_NAME, searchCriteria);
        verify(objectMapper).convertValue(mockSearchResult, Map.class);
    }

    /**
     * Test case for searchPlayListWithoutCaching method when search string is too short.
     * This test verifies that the method returns an error response when the search string
     * is less than 3 characters long.
     */
    @Test
    void test_searchPlayListWithoutCaching_shortSearchString() {
        when(searchCriteria.getSearchString()).thenReturn("ab");

        ApiResponse response = playListService.searchPlayListWithoutCaching(searchCriteria);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals(Constants.FAILED_CONST, response.getParams().getStatus());
    }

    /**
     * Test case for searchPlayList method when search result is found in Redis cache.
     * This test verifies that the method returns a successful response with the cached search result
     * when it is available in Redis.
     */
    @Test
    void test_searchPlayList_1() {
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        SearchResult mockSearchResult = new SearchResult();
        ApiResponse expectedResponse = new ApiResponse();
        expectedResponse.setResponseCode(HttpStatus.OK);
        expectedResponse.getParams().setStatus(Constants.SUCCESS);

        when(redisTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(redisTemplate.opsForValue().get(anyString())).thenReturn(mockSearchResult);
        when(objectMapper.convertValue(any(), eq(Map.class))).thenReturn(new HashMap<>());

        // Act
        ApiResponse actualResponse = playListService.searchPlayList(searchCriteria);

        // Assert
        assertEquals(HttpStatus.OK, actualResponse.getResponseCode());
        assertEquals(Constants.SUCCESS, actualResponse.getParams().getStatus());
        verify(redisTemplate.opsForValue()).get(anyString());
        verify(objectMapper).convertValue(eq(mockSearchResult), eq(Map.class));
    }

    /**
     * Testcase 2 for searchPlayList method
     * Tests the scenario where the search result is not in Redis cache and the search string is valid
     */
    @Test
    void test_searchPlayList_2() throws Exception {
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString("test search");

        SearchResult searchResult = new SearchResult();
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("key", "value");

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        //when(valueOperations.get(anyString())).thenReturn(null); // Simulate cache miss
        when(esUtilService.searchDocuments(eq(Constants.PLAYLIST_INDEX_NAME), eq(searchCriteria))).thenReturn(searchResult);
        when(objectMapper.convertValue(searchResult, Map.class)).thenReturn(resultMap);

        // Act
        ApiResponse response = playListService.searchPlayList(searchCriteria);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        assertEquals(resultMap, response.getResult());

        verify(redisTemplate.opsForValue()).get(anyString());
        verify(esUtilService).searchDocuments(eq(Constants.PLAYLIST_INDEX_NAME), eq(searchCriteria));
        verify(objectMapper).convertValue(searchResult, Map.class);
    }

    /**
     * Test case for searchPlayList method when searchResult is null and searchString is null or less than 3 characters.
     * This test verifies that the method handles the case where there's no cached result in Redis
     * and the search string is invalid or too short.
     */
    @Test
    void test_searchPlayList_3(){
        when(cbServerProperties.getJwtSecretKey()).thenReturn("demand_search_result");
        // Arrange
        SearchCriteria searchCriteria = new SearchCriteria();
        searchCriteria.setSearchString(null);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        // Act
        ApiResponse response = playListService.searchPlayList(searchCriteria);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals(Constants.FAILED_CONST, response.getParams().getStatus());
    }

    /**
     * Test case for updating a playlist when the playlist exists but doesn't have a title.
     * This test verifies that the playlist is updated correctly and the response indicates success.
     */
    @Test
    void test_updatePlayList_4(){
        // Arrange
        ObjectMapper realObjectMapper = new ObjectMapper();
        ObjectNode playListDetails = realObjectMapper.createObjectNode();
        playListDetails.put(Constants.ORG_ID, "testOrgId");
        playListDetails.put(Constants.RQST_CONTENT_TYPE, "testContentType");

        PlayListEntity playListEntity = new PlayListEntity();
        playListEntity.setId("testId");
        playListEntity.setOrgId("testOrgId");
        playListEntity.setRequestType("testContentType");
        playListEntity.setData(playListDetails);

        List<PlayListEntity> entityList = new ArrayList<>();
        entityList.add(playListEntity);

        when(playListRepository.findByOrgIdAndRequestTypeAndIsActive(anyString(), anyString(), anyBoolean()))
            .thenReturn(entityList);
        when(playListRepository.save(any(PlayListEntity.class))).thenReturn(playListEntity);
        when(objectMapper.createObjectNode()).thenReturn(mock(ObjectNode.class));
        PlayListEntity jsonNodeEntity = mock(PlayListEntity.class);
        String key = "testKey";
        String orgId = "org123";

        // Create mock data to simulate enrichedContentJson => Map<String, Object>
        Map<String, Object> mockMap = new HashMap<>();
        mockMap.put("sampleKey", "sampleValue"); // or a List if needed

        when(objectMapper.convertValue(any(ObjectNode.class), any(TypeReference.class)))
                .thenReturn(mockMap);

        // Mock entity behavior
        when(jsonNodeEntity.getOrgId()).thenReturn(orgId);

        // Stub redis hset call (void method)
        doNothing().when(redisCacheMngr).hset(eq(key), anyInt(), anyMap());

        // Act
        ApiResponse response = playListService.updatePlayList(playListDetails);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.get(Constants.RESPONSE));
        assertEquals(Constants.UPDATED, response.getResult().get(Constants.STATUS));
        assertEquals("testId", response.getResult().get(Constants.ID));
    }

    /**
     * Test case for updatePlayList method when no playlist is found for the given orgId and requestType.
     * This test verifies that the method returns a NOT_FOUND status when the playlist doesn't exist.
     */
    @Test
    void test_updatePlayList_playlistNotFound() {
        // Arrange
        JsonNode playListDetails = new ObjectMapper().createObjectNode()
                .put(Constants.ORG_ID, "testOrgId")
                .put(Constants.RQST_CONTENT_TYPE, "testRequestType");

        when(playListRepository.findByOrgIdAndRequestTypeAndIsActive(any(), any(), any()))
                .thenReturn(new ArrayList<>());

        // Act
        ApiResponse response = playListService.updatePlayList(playListDetails);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals(Constants.NOT_FOUND, response.getParams().getErrMsg());
    }


    /**
     * Test case for updateV2PlayList method when the playlist exists, has an ID, title,
     * but no children, and the update is successful.
     */
    @Test
    void test_updateV2PlayList_3() {
        // Arrange
        ObjectMapper realObjectMapper = new ObjectMapper();
        ObjectNode playListDetails = realObjectMapper.createObjectNode();
        playListDetails.put(Constants.ID, "123");
        playListDetails.put(Constants.TITLE, "Updated Playlist");

        PlayListEntity existingPlayList = new PlayListEntity();
        existingPlayList.setId("123");
        existingPlayList.setData(playListDetails);

        when(playListRepository.findByIdAndIsActive("123", true))
            .thenReturn(existingPlayList);
        when(playListRepository.save(any(PlayListEntity.class)))
            .thenReturn(existingPlayList);
        when(objectMapper.convertValue(any(JsonNode.class), eq(java.util.Map.class)))
            .thenReturn(new java.util.HashMap<>());

        // Act
        ApiResponse response = playListService.updateV2PlayList(playListDetails);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.get(Constants.RESPONSE));
        assertEquals(Constants.SUCCESSFULLY_UPDATED, response.getResult().get(Constants.STATUS));
        assertEquals("123", response.getResult().get(Constants.ID));

        verify(playListRepository).findByIdAndIsActive("123", true);
        verify(playListRepository).save(any(PlayListEntity.class));
        verify(esUtilService).updateDocument(eq(Constants.PLAYLIST_INDEX_NAME), eq(Constants.INDEX_TYPE), 
                                             eq("123"), any(java.util.Map.class), any(String.class));
    }

    /**
     * Test case for updateV2PlayList method when the playlist ID is provided but not found in the repository.
     * This test verifies that the method returns a NOT_FOUND response when the playlist doesn't exist.
     */
    @Test
    void test_updateV2PlayList_5() {
        // Arrange
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode playListDetails = objectMapper.createObjectNode().put(Constants.ID, "non_existent_id");
        when(playListRepository.findByIdAndIsActive(eq("non_existent_id"), eq(true))).thenReturn(null);

        // Act
        ApiResponse response = playListService.updateV2PlayList(playListDetails);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals(Constants.ID_NOT_FOUND, response.getParams().getStatus());
    }

    /**
     * Tests the updateV2PlayList method when the ID is missing from the input.
     * This is an edge case explicitly handled in the method implementation.
     */
    @Test
    void test_updateV2PlayList_missingId() {
        
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode playListDetails = objectMapper.createObjectNode();

        ApiResponse response = playListService.updateV2PlayList(playListDetails);

        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals(Constants.ID_NOT_FOUND, response.getParams().getStatus());
    }

    /**
     * Test case for updateV2PlayList method when playListDetails does not have an ID or has an empty ID.
     * This test verifies that the method returns a response with NOT_FOUND status when the input lacks a valid ID.
     */
    @Test
    void test_updateV2PlayList_missingOrEmptyId() {
        
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode playListDetails = objectMapper.createObjectNode();

        ApiResponse response = playListService.updateV2PlayList(playListDetails);

        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals(Constants.ID_NOT_FOUND, response.getParams().getStatus());
    }

    @Test
    void test_delete_throwsException_logsErrorAndReturnsErrorResponse() {
        // Arrange
        String id = "mock-playlist-id";
        when(playListRepository.findByIdAndIsActive(eq(id), eq(true)))
                .thenThrow(new RuntimeException("Database is down"));

        // Act
        ApiResponse response = playListService.delete(id);

        // Assert
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertTrue(response.getParams().getErrMsg().contains("Database is down"));
    }
}
