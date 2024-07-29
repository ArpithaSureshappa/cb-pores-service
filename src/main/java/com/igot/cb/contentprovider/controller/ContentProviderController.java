package com.igot.cb.contentprovider.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.igot.cb.contentprovider.service.ContentPartnerService;
import com.igot.cb.pores.dto.CustomResponse;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.util.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/contentpartner")
public class ContentProviderController {
    @Autowired
    private ContentPartnerService partnerService;

    @PostMapping("/v1/create")
    public ResponseEntity<ApiResponse> create(@RequestBody JsonNode contentPartnerDetails) {
        ApiResponse response = partnerService.createOrUpdate(contentPartnerDetails);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @PostMapping("/v1/update")
    public ResponseEntity<?> update(@RequestBody JsonNode contentPartnerDetails) {
        ApiResponse response = partnerService.createOrUpdate(contentPartnerDetails);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @GetMapping("/v1/read/{id}")
    public ResponseEntity<?> read(@PathVariable String id) {
        ApiResponse response = partnerService.read(id);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
    @PostMapping("/v1/search")
    public ResponseEntity<?> search(@RequestBody SearchCriteria searchCriteria) {
        ApiResponse response = partnerService.searchEntity(searchCriteria);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @DeleteMapping("/v1/delete/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        ApiResponse response = partnerService.delete(id);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

}
