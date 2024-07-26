package com.igot.cb.designation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.igot.cb.pores.dto.CustomResponse;
import com.igot.cb.pores.util.ApiResponse;

import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;

import org.springframework.web.multipart.MultipartFile;

public interface DesignationService {

 public ApiResponse createTerm(JsonNode request);

 public   CustomResponse updateIdentifiersToDesignation(JsonNode updateDesignationDetails);

  public void loadDesignation(MultipartFile file, String token);

  public CustomResponse readDesignation(String id);

  public CustomResponse updateDesignation(JsonNode updateDesignationDetails);

  public CustomResponse createDesignation(JsonNode designationDetails);

  public CustomResponse deleteDesignation(
      String id);

  public CustomResponse searchDesignation(SearchCriteria searchCriteria);

  public ApiResponse frameworkRead(String frameworkId, String categoryCode, String termCode, String refId);
}
