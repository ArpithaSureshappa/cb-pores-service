package com.igot.cb.designation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.igot.cb.designation.service.DesignationService;
import com.igot.cb.pores.util.ApiResponse;

import com.igot.cb.pores.dto.CustomResponse;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;

import com.igot.cb.pores.util.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/designation")
@Slf4j
public class DesignationController {

  @Autowired
  private DesignationService designationService;

  @PostMapping(value = "/upload", consumes = "multipart/form-data")
  public ResponseEntity<String> loadDesignation(@RequestParam("file") MultipartFile file, @RequestHeader(Constants.X_AUTH_TOKEN) String token) {
    try {
      designationService.loadDesignation(file, token);
      return ResponseEntity.ok("Loading of designations from excel is successful.");
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Error during loading of designation from excel: " + e.getMessage());
    }
  }


  @PostMapping("/term/create")
  public ResponseEntity<ApiResponse> createTerm(@RequestBody JsonNode request) {
    ApiResponse response = designationService.createTerm(request);
    return new ResponseEntity<>(response, response.getResponseCode());
  }

  @GetMapping("/read/{id}")
  public ResponseEntity<?> playListRead(@PathVariable String id) {
    CustomResponse response = designationService.readDesignation(id);
    return new ResponseEntity<>(response, response.getResponseCode());
  }

  //update API to store the refNodes

  @PutMapping(value = "/update", produces = "application/json")
  public ResponseEntity<CustomResponse> update(@RequestBody JsonNode updateDesignationDetails) {
    CustomResponse response = designationService.updateDesignation(updateDesignationDetails);
    return new ResponseEntity<>(response, response.getResponseCode());
  }

  @PostMapping("/create")
  public ResponseEntity<CustomResponse> createDesignation(@RequestBody JsonNode designationDetails) {
    CustomResponse response = designationService.createDesignation(designationDetails);
    return new ResponseEntity<>(response, response.getResponseCode());
  }

  @DeleteMapping("/delete/{id}")
  public ResponseEntity<CustomResponse> deleteDesignation(@PathVariable String id) {
    CustomResponse response = designationService.deleteDesignation(id);
    return new ResponseEntity<>(response, response.getResponseCode());
  }

  @PostMapping("/search")
  public ResponseEntity<?> search(@RequestBody SearchCriteria searchCriteria) {
    CustomResponse response = designationService.searchDesignation(searchCriteria);
    return new ResponseEntity<>(response, response.getResponseCode());
  }

}
