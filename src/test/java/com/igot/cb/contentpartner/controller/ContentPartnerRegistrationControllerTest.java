package com.igot.cb.contentpartner.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.contentpartner.service.ContentPartnerRegistrationService;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.Constants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@ExtendWith(MockitoExtension.class)
class ContentPartnerRegistrationControllerTest {

    @Mock
    private ContentPartnerRegistrationService partnerService;

    @InjectMocks
    private ContentPartnerRegistrationController controller;

    private final ObjectMapper mapper = new ObjectMapper();
    private final String token = "dummy-token";

    @Test
    void testCreate_Success() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        JsonNode requestJson = mapper.readTree(
                "{\"contentPartnerName\":\"Org1\",\"email\":\"org@gmail.com\"}"
        );

        ApiResponse mockResponse = new ApiResponse();
        mockResponse.setResponseCode(HttpStatus.OK);
        Map<String, Object> result = new HashMap<>();
        result.put("id", "generated-id-123");
        result.put("status", Constants.PENDING);
        mockResponse.setResult(result);

        when(partnerService.insert(any(JsonNode.class))).thenReturn(mockResponse);

        mockMvc.perform(post("/contentpartner/register/v1/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("OK"));

        verify(partnerService, times(1)).insert(any(JsonNode.class));
    }

    @Test
    void testCreate_Failure() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        JsonNode requestJson = mapper.readTree("{\"contentPartnerName\":\"Org1\"}");

        ApiResponse mockResponse = new ApiResponse();
        mockResponse.setResponseCode(HttpStatus.BAD_REQUEST);
        mockResponse.getParams().setErrMsg("Email is required");

        when(partnerService.insert(any(JsonNode.class))).thenReturn(mockResponse);

        mockMvc.perform(post("/contentpartner/register/v1/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson.toString()))
                .andExpect(status().isBadRequest());

        verify(partnerService, times(1)).insert(any(JsonNode.class));
    }

    @Test
    void testCreate_OrgNameAlreadyExists() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        JsonNode requestJson = mapper.readTree(
                "{\"contentPartnerName\":\"ExistingOrg\",\"email\":\"new@gmail.com\"}"
        );

        ApiResponse mockResponse = new ApiResponse();
        mockResponse.setResponseCode(HttpStatus.BAD_REQUEST);
        mockResponse.getParams().setErrMsg("Organization Name already registered");

        when(partnerService.insert(any(JsonNode.class))).thenReturn(mockResponse);

        mockMvc.perform(post("/contentpartner/register/v1/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.params.errMsg").value("Organization Name already registered"));

        verify(partnerService, times(1)).insert(any(JsonNode.class));
    }

    @Test
    void testCreate_EmailAlreadyExists() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        JsonNode requestJson = mapper.readTree(
                "{\"contentPartnerName\":\"NewOrg\",\"email\":\"existing@gmail.com\"}"
        );

        ApiResponse mockResponse = new ApiResponse();
        mockResponse.setResponseCode(HttpStatus.BAD_REQUEST);
        mockResponse.getParams().setErrMsg("Email already registered");

        when(partnerService.insert(any(JsonNode.class))).thenReturn(mockResponse);

        mockMvc.perform(post("/contentpartner/register/v1/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.params.errMsg").value("Email already registered"));

        verify(partnerService, times(1)).insert(any(JsonNode.class));
    }

    @Test
    void testUpdate_Success() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        JsonNode requestJson = mapper.readTree("{\"id\":\"123\",\"status\":\"APPROVED\"}");

        ApiResponse mockResponse = new ApiResponse();
        mockResponse.setResponseCode(HttpStatus.OK);
        Map<String, Object> result = new HashMap<>();
        result.put("id", "123");
        result.put("status", Constants.APPROVED);
        mockResponse.setResult(result);

        when(partnerService.update(any(JsonNode.class), eq(token))).thenReturn(mockResponse);

        mockMvc.perform(post("/contentpartner/register/v1/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(Constants.X_AUTH_TOKEN, token)
                        .content(requestJson.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("OK"));

        verify(partnerService, times(1)).update(any(JsonNode.class), eq(token));
    }

    @Test
    void testUpdate_Rejected() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        JsonNode requestJson = mapper.readTree("{\"id\":\"456\",\"status\":\"REJECTED\"}");

        ApiResponse mockResponse = new ApiResponse();
        mockResponse.setResponseCode(HttpStatus.OK);
        Map<String, Object> result = new HashMap<>();
        result.put("id", "456");
        result.put("status", Constants.REJECTED);
        mockResponse.setResult(result);

        when(partnerService.update(any(JsonNode.class), eq(token))).thenReturn(mockResponse);

        mockMvc.perform(post("/contentpartner/register/v1/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(Constants.X_AUTH_TOKEN, token)
                        .content(requestJson.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("OK"));

        verify(partnerService, times(1)).update(any(JsonNode.class), eq(token));
    }

    @Test
    void testUpdate_InvalidStatus() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        JsonNode requestJson = mapper.readTree("{\"id\":\"123\",\"status\":\"INVALID\"}");

        ApiResponse mockResponse = new ApiResponse();
        mockResponse.setResponseCode(HttpStatus.BAD_REQUEST);
        mockResponse.getParams().setErrMsg("Invalid status. Allowed values: APPROVED, REJECTED");

        when(partnerService.update(any(JsonNode.class), eq(token))).thenReturn(mockResponse);

        mockMvc.perform(post("/contentpartner/register/v1/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(Constants.X_AUTH_TOKEN, token)
                        .content(requestJson.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.params.errMsg").value("Invalid status. Allowed values: APPROVED, REJECTED"));

        verify(partnerService, times(1)).update(any(JsonNode.class), eq(token));
    }

    @Test
    void testUpdate_MissingToken() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        JsonNode requestJson = mapper.readTree("{\"id\":\"123\",\"status\":\"APPROVED\"}");

        mockMvc.perform(post("/contentpartner/register/v1/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson.toString()))
                .andExpect(status().isBadRequest());

        verify(partnerService, never()).update(any(JsonNode.class), anyString());
    }

    @Test
    void testUpdate_Unauthorized() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        JsonNode requestJson = mapper.readTree("{\"id\":\"123\",\"status\":\"APPROVED\"}");

        ApiResponse mockResponse = new ApiResponse();
        mockResponse.setResponseCode(HttpStatus.UNAUTHORIZED);
        mockResponse.getParams().setErrMsg(Constants.UNAUTHORIZED);

        when(partnerService.update(any(JsonNode.class), eq(token))).thenReturn(mockResponse);

        mockMvc.perform(post("/contentpartner/register/v1/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(Constants.X_AUTH_TOKEN, token)
                        .content(requestJson.toString()))
                .andExpect(status().isUnauthorized());

        verify(partnerService, times(1)).update(any(JsonNode.class), eq(token));
    }

    @Test
    void testUpdate_NotFound() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        JsonNode requestJson = mapper.readTree("{\"id\":\"non-existent\",\"status\":\"APPROVED\"}");

        ApiResponse mockResponse = new ApiResponse();
        mockResponse.setResponseCode(HttpStatus.NOT_FOUND);
        mockResponse.getParams().setErrMsg("Content Partner Registration not found");

        when(partnerService.update(any(JsonNode.class), eq(token))).thenReturn(mockResponse);

        mockMvc.perform(post("/contentpartner/register/v1/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(Constants.X_AUTH_TOKEN, token)
                        .content(requestJson.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.params.errMsg").value("Content Partner Registration not found"));

        verify(partnerService, times(1)).update(any(JsonNode.class), eq(token));
    }

    @Test
    void testReadById_Success() throws Exception {

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        String id = "test-id-123";
        ApiResponse mockResponse = new ApiResponse();
        mockResponse.setResponseCode(HttpStatus.OK);
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("contentPartnerName", "Org1");
        result.put("email", "org1@gmail.com");
        result.put("status", Constants.APPROVED);
        mockResponse.setResult(result);
        when(partnerService.read(eq(id), isNull())).thenReturn(mockResponse);
        mockMvc.perform(get("/contentpartner/register/v1/read").param("id", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(id))
                .andExpect(jsonPath("$.result.contentPartnerName").value("Org1"))
                .andExpect(jsonPath("$.responseCode").value("OK"));
        verify(partnerService, times(1)).read(eq(id), isNull());
    }

    @Test
    void testReadById_NotFound() throws Exception {

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        String id = "non-existent-id";
        ApiResponse mockResponse = new ApiResponse();
        mockResponse.setResponseCode(HttpStatus.BAD_REQUEST);
        mockResponse.getParams().setErrMsg(Constants.INVALID_ID);
        when(partnerService.read(eq(id), isNull())).thenReturn(mockResponse);
        mockMvc.perform(get("/contentpartner/register/v1/read").param("id", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.params.errMsg").value(Constants.INVALID_ID));
        verify(partnerService, times(1)).read(eq(id), isNull());
    }
    @Test
    void testReadByEmail_Success() throws Exception {

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        String email = "org1@gmail.com";
        ApiResponse mockResponse = new ApiResponse();
        mockResponse.setResponseCode(HttpStatus.OK);
        Map<String, Object> result = new HashMap<>();
        result.put("email", email);
        result.put("contentPartnerName", "Org1");
        mockResponse.setResult(result);
        when(partnerService.read(isNull(), eq(email))).thenReturn(mockResponse);
        mockMvc.perform(get("/contentpartner/register/v1/read").param("email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.email").value(email))
                .andExpect(jsonPath("$.responseCode").value("OK"));

        verify(partnerService, times(1)).read(isNull(), eq(email));
    }

    @Test
    void testRead_NoParams_BadRequest() throws Exception {

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        ApiResponse mockResponse = new ApiResponse();
        mockResponse.setResponseCode(HttpStatus.BAD_REQUEST);
        mockResponse.getParams().setErrMsg("Either id or email must be provided");
        when(partnerService.read(isNull(), isNull())).thenReturn(mockResponse);
        mockMvc.perform(get("/contentpartner/register/v1/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("BAD_REQUEST"));

        verify(partnerService, times(1)).read(isNull(), isNull());
    }

    @Test
    void testSearch_Success() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        SearchCriteria criteria = new SearchCriteria();
        criteria.setSearchString("Org");

        ApiResponse mockResponse = new ApiResponse();
        mockResponse.setResponseCode(HttpStatus.OK);
        Map<String, Object> result = new HashMap<>();
        result.put("total", 5);
        mockResponse.setResult(result);

        when(partnerService.searchEntity(any(SearchCriteria.class), eq(token))).thenReturn(mockResponse);

        mockMvc.perform(post("/contentpartner/register/v1/search")
                        .header(Constants.X_AUTH_TOKEN, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(criteria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("OK"));

        verify(partnerService, times(1)).searchEntity(any(SearchCriteria.class), eq(token));
    }

    @Test
    void testSearch_MinCharactersValidation() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        SearchCriteria criteria = new SearchCriteria();
        criteria.setSearchString("ab");

        ApiResponse mockResponse = new ApiResponse();
        mockResponse.setResponseCode(HttpStatus.BAD_REQUEST);
        mockResponse.getParams().setErrMsg("Minimum 3 characters are required to search");

        when(partnerService.searchEntity(any(SearchCriteria.class), eq(token))).thenReturn(mockResponse);

        mockMvc.perform(post("/contentpartner/register/v1/search")
                        .header(Constants.X_AUTH_TOKEN, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(criteria)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.params.errMsg").value("Minimum 3 characters are required to search"));

        verify(partnerService, times(1)).searchEntity(any(SearchCriteria.class), eq(token));
    }

    @Test
    void testSearch_Unauthorized() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        SearchCriteria criteria = new SearchCriteria();
        criteria.setSearchString("Org");

        ApiResponse mockResponse = new ApiResponse();
        mockResponse.setResponseCode(HttpStatus.UNAUTHORIZED);
        mockResponse.getParams().setErrMsg(Constants.UNAUTHORIZED);

        when(partnerService.searchEntity(any(SearchCriteria.class), eq(token))).thenReturn(mockResponse);

        mockMvc.perform(post("/contentpartner/register/v1/search")
                        .header(Constants.X_AUTH_TOKEN, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(criteria)))
                .andExpect(status().isUnauthorized());

        verify(partnerService, times(1)).searchEntity(any(SearchCriteria.class), eq(token));
    }

    @Test
    void testSearch_MissingToken() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        SearchCriteria criteria = new SearchCriteria();
        criteria.setSearchString("Org");

        mockMvc.perform(post("/contentpartner/register/v1/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(criteria)))
                .andExpect(status().isBadRequest());

        verify(partnerService, never()).searchEntity(any(SearchCriteria.class), anyString());
    }
}