package com.igot.cb.org.service;

import com.igot.cb.pores.util.ApiResponse;

public interface OrgService {
    public ApiResponse readFramework(String frameworkName, String orgId, String termName, String userAuthToken);
}
