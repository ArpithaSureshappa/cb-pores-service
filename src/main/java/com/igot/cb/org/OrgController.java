package com.igot.cb.org;

import com.igot.cb.org.service.OrgService;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/org")
@Slf4j
public class OrgController {

    @Autowired
    OrgService orgService;

    @GetMapping("/framework/read")
    public ResponseEntity<Object> readFramework(@RequestParam String frameworkName, @RequestParam String orgId, @RequestHeader(Constants.X_AUTH_TOKEN) String userAuthToken) {
        ApiResponse response = orgService.readFramework(frameworkName,orgId,userAuthToken);
        return new ResponseEntity<>(response,response.getResponseCode());
    }

}
