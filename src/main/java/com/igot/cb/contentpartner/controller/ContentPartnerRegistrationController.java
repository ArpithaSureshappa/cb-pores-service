package com.igot.cb.contentpartner.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.igot.cb.contentpartner.service.ContentPartnerRegistrationService;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.Constants;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/contentpartner/register")
public class ContentPartnerRegistrationController {

    private final ContentPartnerRegistrationService partnerService;

    public ContentPartnerRegistrationController(ContentPartnerRegistrationService partnerService) {
        this.partnerService = partnerService;
    }

    @PostMapping("/v1/create")
    public ResponseEntity<ApiResponse> create(@RequestBody JsonNode contentPartnerDetails) {
        ApiResponse response = partnerService.insert(contentPartnerDetails);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @PostMapping("/v1/update")
    public ResponseEntity<ApiResponse> update(@RequestBody JsonNode contentPartnerDetails, @RequestHeader(Constants.X_AUTH_TOKEN) String token) {
        ApiResponse response = partnerService.update(contentPartnerDetails, token);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @GetMapping("/v1/read")
    public ResponseEntity<ApiResponse> read(
            @RequestParam(required = false) String id,
            @RequestParam(required = false) String email) {

        ApiResponse response = partnerService.read(id, email);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PostMapping("/v1/search")
    public ResponseEntity<ApiResponse> search(@RequestBody SearchCriteria searchCriteria,@RequestHeader(Constants.X_AUTH_TOKEN) String token) {
        ApiResponse response = partnerService.searchEntity(searchCriteria,token);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

}