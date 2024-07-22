package com.igot.cb.demand.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.igot.cb.pores.dto.CustomResponse;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;

public interface DemandService {
  CustomResponse createDemand(JsonNode demandDetails,String token, String rootOrgId);

  CustomResponse readDemand(String id);

  CustomResponse searchDemand(SearchCriteria searchCriteria);

  String delete(String id);

  CustomResponse updateDemandStatus(JsonNode updateDetails, String token, String rootOrgId);

  boolean isSpvRequest(String userId, String requiredRole);

}
