package com.igot.cb.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.demand.service.DemandServiceImpl;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.transactional.cassandrautils.CassandraOperation;
import com.igot.cb.transactional.model.Config;
import com.igot.cb.transactional.model.NotificationAsyncRequest;
import com.igot.cb.transactional.model.Template;
import com.igot.cb.transactional.service.RequestHandlerServiceImpl;
import org.apache.commons.lang.StringUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Component
public class NotificationConsumer {
    private ObjectMapper mapper = new ObjectMapper();

    private Logger logger = LoggerFactory.getLogger(DemandServiceImpl.class);

    @Autowired
    private RequestHandlerServiceImpl requestHandlerService;

    @Autowired
    private CassandraOperation cassandraOperation;

    @Autowired
    private CbServerProperties configuration;

    @KafkaListener(groupId = "${kafka.topic.demand.content.group}", topics = "${kafka.topic.demand.request}")
    public void demandContentConsumer(ConsumerRecord<String, String> data) {
        try {
            Map<String, Object> demandRequest = mapper.readValue(data.value(), HashMap.class);
            CompletableFuture.runAsync(() -> {
                processNotification(demandRequest);
            });
        } catch (Exception e) {
            logger.error("Failed to read demand request. Message received : " + data.value(), e);
        }
    }

    @KafkaListener(groupId = "${kafka.topic.content.partner.registration.group}", topics = "${kafka.topic.content.partner.registration}")
    public void contentPartnerRegistrationConsumer(ConsumerRecord<String, String> data) {
        try {
            Map<String, Object> event = mapper.readValue(data.value(), HashMap.class);
            CompletableFuture.runAsync(() -> {
                processContentPartnerNotification(event);
            });
        } catch (Exception e) {
            logger.error(
                    "Failed to read content partner registration event. Message: " + data.value(),
                    e
            );
        }
    }

    public void processContentPartnerNotification(Map<String, Object> event) {
        try {
            if (event == null || event.isEmpty()) {
                logger.warn("Content partner notification event is null or empty");
                return;
            }
            Object statusObj = event.get(Constants.EVENT_STATUS);
            Object emailObj = event.get(Constants.EVENT_EMAIL);
            Object partnerNameObj = event.get(Constants.EVENT_PARTNER_NAME);
            Object registrationIdObj = event.get(Constants.EVENT_REGISTRATION_ID);

            if (!(statusObj instanceof String)
                    || !(emailObj instanceof String)
                    || !(partnerNameObj instanceof String)
                    || !(registrationIdObj instanceof String)) {

                logger.error("Invalid content partner registration event payload: {}", event);
                return;
            }

            String status = (String) statusObj;
            String email = (String) emailObj;
            String partnerName = (String) partnerNameObj;
            String registrationId = (String) registrationIdObj;

            String subject;
            String statusMessage;
            if (Constants.PENDING.equals(status)) {
                subject = Constants.CP_REG_SUCCESS_SUBJECT;
                statusMessage = Constants.CP_REG_SUCCESS_MESSAGE;

            } else if (Constants.APPROVED.equals(status)) {
                subject = Constants.CP_REG_APPROVED_SUBJECT;
                statusMessage = Constants.CP_REG_APPROVED_MESSAGE;

            } else if (Constants.REJECTED.equals(status)) {
                subject = Constants.CP_REG_REJECTED_SUBJECT;
                statusMessage = Constants.CP_REG_REJECTED_MESSAGE;

            } else {
                logger.warn("Unsupported content partner registration status: {}", status);
                return;
            }
            Map<String, Object> mailNotificationDetails = new HashMap<>();
            // Recipients
            mailNotificationDetails.put(Constants.EMAIL_ID_LIST, Collections.singletonList(email));
            mailNotificationDetails.put(Constants.SUB, subject);
            mailNotificationDetails.put(Constants.CREATED_BY, partnerName);
            mailNotificationDetails.put(Constants.TEMPLATE, Constants.CONTENT_PARTNER_REG_TEMPLATE);

            mailNotificationDetails.put(Constants.PARTNER_NAME, partnerName);
            mailNotificationDetails.put(Constants.EVENT_REGISTRATION_ID, registrationId);
            mailNotificationDetails.put(Constants.STATUS_MESSAGE, statusMessage);
            mailNotificationDetails.put(Constants.ORG, partnerName);
            mailNotificationDetails.put(Constants.ORG_NAME, partnerName);
            sendContentPartnerNotificationAsync(mailNotificationDetails);

        } catch (Exception e) {
            logger.error("Failed to process content partner registration notification", e);
        }
    }

    private void sendContentPartnerNotificationAsync(Map<String, Object> mailNotificationDetails) {

        Map<String, Object> params = new HashMap<>();
        params.put("name", mailNotificationDetails.get("name"));
        params.put("registrationId", mailNotificationDetails.get("registrationId"));
        params.put("statusMessage", mailNotificationDetails.get("statusMessage"));
        params.put(Constants.ORG_NAME, mailNotificationDetails.get(Constants.ORG_NAME));
        params.put(Constants.FROM_EMAIL, configuration.getSupportEmail());
        String templateName = (String) mailNotificationDetails.get(Constants.TEMPLATE);

        Template template = new Template(
                constructEmailTemplate(templateName, params),
                templateName,
                params
        );
        Config config = new Config();
        config.setSubject((String) mailNotificationDetails.get(Constants.SUB));
        config.setSender(configuration.getSupportEmail());

        Map<String, Object> templateMap = new HashMap<>();
        templateMap.put(Constants.CONFIG, config);
        templateMap.put(Constants.TYPE, Constants.EMAIL);
        templateMap.put(Constants.DATA, template.getData());
        templateMap.put(Constants.ID, templateName);
        templateMap.put(Constants.PARAMS, params);

        Map<String, Object> action = new HashMap<>();
        action.put(Constants.TEMPLATE, templateMap);
        action.put(Constants.TYPE, Constants.EMAIL);
        action.put(Constants.CATEGORY, Constants.EMAIL);

        Map<String, Object> createdBy = new HashMap<>();
        createdBy.put(Constants.ID, "system");
        createdBy.put(Constants.TYPE, "system");
        action.put(Constants.CREATED_BY, createdBy);

        NotificationAsyncRequest notificationAsyncRequest = new NotificationAsyncRequest();
        notificationAsyncRequest.setPriority(1);
        notificationAsyncRequest.setType(Constants.EMAIL);
        notificationAsyncRequest.setIds((List<String>) mailNotificationDetails.get(Constants.EMAIL_ID_LIST));
        notificationAsyncRequest.setAction(action);

        Map<String, Object> req = new HashMap<>();
        req.put(Constants.REQUEST,
                Collections.singletonMap(Constants.NOTIFICATIONS,
                        Collections.singletonList(notificationAsyncRequest)));
        sendNotification(req, configuration.getNotificationAsyncPath());
    }

    public void processNotification(Map<String, Object> demandRequest) {
        try {
            logger.info("notification process started");
            long startTime = System.currentTimeMillis();

            // Extract request and status
            Map<String, Object> request = (Map<String, Object>) demandRequest.get(Constants.DATA);
            String status = (String) request.get(Constants.STATUS);
            boolean isSpvRequest = (boolean) demandRequest.get(Constants.IS_SPV_REQUEST);

            // Fetch organization details
            Map<String, Object> propertyMap = new HashMap<>();
            propertyMap.put(Constants.ID, (String) request.get(Constants.ROOT_ORG_ID));
            List<Map<String, Object>> orgDetails = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                    Constants.KEYSPACE_SUNBIRD, Constants.ORG_TABLE, propertyMap, null, 1);
            String mdoName = (String) orgDetails.get(0).get(Constants.USER_ROOT_ORG_NAME);

            // Initialize subject line and body
            String subjectLine = "";
            String body = "";
            Map<String, Object> mailNotificationDetails = new HashMap<>();

            // Set subject line and body based on status
            if (status.equals(Constants.UNASSIGNED)) {
                subjectLine = Constants.REQUEST_CONTENT_SUBJECT.replace(Constants.DEMAND_TAG, (String) request.get(Constants.DEMAND_ID));
                body = Constants.PREFERRED_MAIL_BODY.replace(Constants.DEMAND_TAG, (String) request.get(Constants.DEMAND_ID)).replace(Constants.MDO_NAME_TAG, mdoName);
            } else if (status.equals(Constants.ASSIGNED)) {
                subjectLine = Constants.DEMAND_ASSIGNED_SUB.replace(Constants.DEMAND_TAG, (String) request.get(Constants.DEMAND_ID));
                body = Constants.ASSIGNED_MAIL_BODY_TO_CBP.replace(Constants.DEMAND_TAG, (String) request.get(Constants.DEMAND_ID)).replace(Constants.MDO_NAME_TAG, mdoName);
            }

            // Determine recipients
            List<String> emails = new ArrayList<>();
            if (status.equals(Constants.UNASSIGNED)) {
                List<Map<String, Object>> recipientsList = (List<Map<String, Object>>) request.get(Constants.PREFERRED_PROVIDER);
                Set<String> providerIds = new HashSet<>();
                for (Map<String, Object> recipient : recipientsList) {
                    providerIds.add((String) recipient.get(Constants.PROVIDER_ID));
                }
                emails = getCBPAdminDetails(providerIds);
            } else if (status.equals(Constants.ASSIGNED)) {
                Map<String, Object> assignedProvider = (Map<String, Object>) request.get(Constants.ASSIGNED_PROVIDER);
                Set<String> providerSet = new HashSet<>();
                providerSet.add((String) assignedProvider.get(Constants.PROVIDER_ID));
                emails = getCBPAdminDetails((providerSet));
            }

            // Process competencies
            String allArea = "";
            String allThemes = "";
            String allSubThemes = "";
            if (request.containsKey(Constants.COMPETENCIES)) {
                List<Map<String, String>> competencies = (List<Map<String, String>>) request.get(Constants.COMPETENCIES);
                allArea = extractAndFormatCompetencies(competencies, Constants.AREA);
                allThemes = extractAndFormatCompetencies(competencies, Constants.THEME);
                allSubThemes = extractAndFormatCompetencies(competencies, Constants.SUB_THEME);
            }
            // Prepare mail notification details
            mailNotificationDetails.put(Constants.EMAIL_ID_LIST, emails);
            mailNotificationDetails.put(Constants.MDO_NAME, mdoName);
            mailNotificationDetails.put(Constants.ORG, mdoName);
            mailNotificationDetails.put(Constants.ORG_NAME, mdoName);
            mailNotificationDetails.put(Constants.COMPETENCY_AREA, allArea);
            mailNotificationDetails.put(Constants.COMPETENCY_THEMES, allThemes);
            mailNotificationDetails.put(Constants.COMPETENCY_SUB_THEMES, allSubThemes);
            mailNotificationDetails.put(Constants.DESCRIPTION, request.get(Constants.OBJECTIVE));
            mailNotificationDetails.put(Constants.CREATED_BY, mdoName);
            mailNotificationDetails.put(Constants.DEMAND_ID, request.get(Constants.DEMAND_ID));
            mailNotificationDetails.put(Constants.SUB, subjectLine);
            mailNotificationDetails.put(Constants.BODY, body);
            logger.info("mailNotificationDetails mapped");

            // Send notifications
            if (status.equals(Constants.ASSIGNED) || status.equals(Constants.UNASSIGNED)) {
                if (isSpvRequest) {
                    mailNotificationDetails.put(Constants.ORG, Constants.SPV_ORG_NAME);
                    mailNotificationDetails.put(Constants.ORG_NAME, Constants.SPV_ORG_NAME);
                }
                sendNotificationToProvidersAsync(mailNotificationDetails);
            }

            // Additional notifications for SPV requests
            if (isSpvRequest) {
                handleSpvRequest(status, request, mdoName, mailNotificationDetails);
            }
            logger.info(String.format("Completed request for content. Time taken: %d ms", System.currentTimeMillis() - startTime));
        } catch (Exception e) {
            logger.error("Exception occurred while sending email: " + e.getMessage(), e);
        }
    }

    private String constructEmailTemplate(String templateName, Map<String, Object> params) {
        String replacedHTML = new String();
        try {
            Map<String, Object> propertyMap = new HashMap<>();
            propertyMap.put(Constants.NAME, templateName);
            List<Map<String, Object>> templateMap = cassandraOperation.getRecordsByPropertiesByKey(Constants.KEYSPACE_SUNBIRD, Constants.TABLE_EMAIL_TEMPLATE, propertyMap, Collections.singletonList(Constants.TEMPLATE), null);
            String htmlTemplate = templateMap.stream()
                    .findFirst()
                    .map(template -> (String) template.get(Constants.TEMPLATE))
                    .orElse(null);
            VelocityEngine velocityEngine = new VelocityEngine();
            velocityEngine.init();
            VelocityContext context = new VelocityContext();
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                context.put(entry.getKey(), entry.getValue());
            }
            StringWriter writer = new StringWriter();
            velocityEngine.evaluate(context, writer, "HTMLTemplate", htmlTemplate);
            replacedHTML = writer.toString();
        } catch (Exception e) {
            logger.error("Unable to create template ", e);
        }
        return replacedHTML;
    }

    private void sendNotificationToProvidersAsync(Map<String, Object> mailNotificationDetails) {
        Map<String, Object> params = new HashMap<>();
        NotificationAsyncRequest notificationAsyncRequest = new NotificationAsyncRequest();
        notificationAsyncRequest.setPriority(1);
        notificationAsyncRequest.setType(Constants.EMAIL);
        notificationAsyncRequest.setIds((List<String>) mailNotificationDetails.get(Constants.EMAIL_ID_LIST));

        params.put(Constants.MDO_NAME_PARAM, (String) mailNotificationDetails.get(Constants.MDO_NAME));
        params.put(Constants.NAME, (String) mailNotificationDetails.get(Constants.MDO_NAME));
        params.put(Constants.ORG, mailNotificationDetails.get(Constants.ORG));
        params.put(Constants.COMPETENCY_AREA_PARAM, mailNotificationDetails.get(Constants.COMPETENCY_AREA));
        params.put(Constants.COMPETENCY_THEME_PARAM, mailNotificationDetails.get(Constants.COMPETENCY_THEMES));
        params.put(Constants.COMPETENCY_SUB_THEME_PARAM, mailNotificationDetails.get(Constants.COMPETENCY_SUB_THEMES));
        params.put(Constants.DESCRIPTION, mailNotificationDetails.get(Constants.DESCRIPTION));
        params.put(Constants.FROM_EMAIL, configuration.getSupportEmail());
        params.put(Constants.ORG_NAME, (String) mailNotificationDetails.get(Constants.ORG_NAME));
        params.put(Constants.BODY, mailNotificationDetails.get(Constants.BODY));
        Template template = new Template(constructEmailTemplate(configuration.getDemandRequestTemplate(), params), configuration.getDemandRequestTemplate(), params);

        Config config = new Config();
        config.setSubject((String) mailNotificationDetails.get(Constants.SUB));
        config.setSender(configuration.getSupportEmail());

        Map<String, Object> templateMap = new HashMap<>();
        templateMap.put(Constants.CONFIG, config);
        templateMap.put(Constants.TYPE, Constants.EMAIL);
        templateMap.put(Constants.DATA, template.getData());
        templateMap.put(Constants.ID, configuration.getDemandRequestTemplate());
        templateMap.put(Constants.PARAMS, params);

        Map<String, Object> action = new HashMap<>();
        action.put(Constants.TEMPLATE, templateMap);
        action.put(Constants.TYPE, Constants.EMAIL);
        action.put(Constants.CATEGORY, Constants.EMAIL);

        Map<String, Object> createdBy = new HashMap<>();
        createdBy.put(Constants.ID, mailNotificationDetails.get(Constants.CREATED_BY));
        createdBy.put(Constants.TYPE, Constants.MDO);
        action.put(Constants.CREATED_BY, createdBy);
        notificationAsyncRequest.setAction(action);

        Map<String, Object> req = new HashMap<>();
        Map<String, List<NotificationAsyncRequest>> notificationMap = new HashMap<>();
        notificationMap.put(Constants.NOTIFICATIONS, Collections.singletonList(notificationAsyncRequest));
        req.put(Constants.REQUEST, notificationMap);
        sendNotification(req, configuration.getNotificationAsyncPath());
    }

    private void sendNotification(Map<String, Object> request, String urlPath) {
        StringBuilder builder = new StringBuilder();
        builder.append(configuration.getNotifyServiceHost()).append(urlPath);
        try {
            logger.info(mapper.writeValueAsString(request));
            Map<String, Object> response = requestHandlerService.fetchResultUsingPost(builder.toString(), request, null);
            logger.debug("The email notification is successfully sent, response is: " + response);
        } catch (Exception e) {
            logger.error("Exception while posting the data in notification service: ", e);
        }
    }

    private Map<String, Object> getSearchObject(Set<String> rootOrgIds) {
        Map<String, Object> requestObject = new HashMap<>();
        Map<String, Object> request = new HashMap<>();
        Map<String, Object> filters = new HashMap<>();
        filters.put(Constants.ROOT_ORG_ID, rootOrgIds);
        filters.put(Constants.ORGANIZATIONS_ROLES, Collections.singletonList(Constants.CBP_ADMIN));
        request.put(Constants.FILTERS, filters);
        request.put(Constants.FIELDS_CONSTANT, Arrays.asList("profileDetails.personalDetails.primaryEmail", Constants.ROOT_ORG_ID));
        requestObject.put(Constants.REQUEST, request);
        return requestObject;
    }

    public List<String> getCBPAdminDetails(Set<String> rootOrgIds) throws Exception {
        Map<String, Object> request = getSearchObject(rootOrgIds);
        HashMap<String, String> headersValue = new HashMap<>();
        headersValue.put("Content-Type", "application/json");
        List<String> providerIdEmails = new ArrayList<>();
        StringBuilder url = new StringBuilder(configuration.getSbUrl())
                .append(configuration.getUserSearchEndPoint());
        Map<String, Object> searchProfileApiResp = requestHandlerService.fetchResultUsingPost(url.toString(), request,
                headersValue);
        if (searchProfileApiResp != null
                && "OK".equalsIgnoreCase((String) searchProfileApiResp.get(Constants.RESPONSE_CODE))) {
            Map<String, Object> map = (Map<String, Object>) searchProfileApiResp.get(Constants.RESULT);
            Map<String, Object> response = (Map<String, Object>) map.get(Constants.RESPONSE);
            List<Map<String, Object>> contents = (List<Map<String, Object>>) response.get(Constants.CONTENT);
            if (!org.springframework.util.CollectionUtils.isEmpty(contents)) {
                for (Map<String, Object> content : contents) {
                    String rootOrgId = (String) content.get(Constants.ROOT_ORG_ID);
                    HashMap<String, Object> profileDetails = (HashMap<String, Object>) content
                            .get(Constants.PROFILE_DETAILS);
                    if (!org.springframework.util.CollectionUtils.isEmpty(profileDetails)) {
                        HashMap<String, Object> personalDetails = (HashMap<String, Object>) profileDetails
                                .get(Constants.PERSONAL_DETAILS);
                        if (!org.springframework.util.CollectionUtils.isEmpty(personalDetails)
                                && personalDetails.get(Constants.PRIMARY_EMAIL) != null) {
                            if (rootOrgIds.contains(rootOrgId))
                                providerIdEmails.add((String) personalDetails.get(Constants.PRIMARY_EMAIL));
                        }
                    }
                }
            }
        }
        if (org.springframework.util.CollectionUtils.isEmpty(providerIdEmails)) {
            throw new Exception("Failed to find CBP Admin for OrgIds: " + rootOrgIds);
        }
        logger.info("CBP Admin emails fetched successfully: " + providerIdEmails);
        return providerIdEmails;
    }

    public List<String> fetchEmailFromUserId(List<String> userIds) {
        Map<String, Object> requestObject = new HashMap<>();
        Map<String, Object> req = new HashMap<>();
        Map<String, Object> filters = new HashMap<>();
        filters.put(Constants.USER_ID_RQST, userIds);
        List<String> userFields = Arrays.asList(Constants.PROFILE_DETAILS_PRIMARY_EMAIL);
        req.put(Constants.FILTERS, filters);
        req.put(Constants.FIELDS, userFields);
        requestObject.put(Constants.REQUEST, req);
        HashMap<String, String> headersValue = new HashMap<>();
        headersValue.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
        StringBuilder url = new StringBuilder(configuration.getSbUrl()).append(configuration.getUserSearchEndPoint());
        Map<String, Object> searchProfileApiResp = requestHandlerService.fetchResultUsingPost(url.toString(), requestObject, headersValue);
        List<String> emailResponseList = new ArrayList<>();
        if (searchProfileApiResp != null
                && Constants.OK.equalsIgnoreCase((String) searchProfileApiResp.get(Constants.RESPONSE_CODE))) {
            Map<String, Object> map = (Map<String, Object>) searchProfileApiResp.get(Constants.RESULT);
            Map<String, Object> resp = (Map<String, Object>) map.get(Constants.RESPONSE);
            List<Map<String, Object>> contents = (List<Map<String, Object>>) resp.get(Constants.CONTENT);
            for (Map<String, Object> content : contents) {
                Map<String, Object> profileDetails = (Map<String, Object>) content.get(Constants.PROFILE_DETAILS);
                Map<String, Object> personalDetails = (Map<String, Object>) profileDetails.get(Constants.PERSONAL_DETAILS);
                String email = (String) personalDetails.get(Constants.PRIMARY_EMAIL);
                emailResponseList.add(email);
            }
        }
        return emailResponseList;
    }

    private void handleSpvRequest(String status, Map<String, Object> request, String mdoName, Map<String, Object> mailNotificationDetails) {
        logger.info("handling spvRequest");
        List<String> emails;
        String subjectLine;
        String body;
        if (status.equals(Constants.ASSIGNED)) {
            Map<String, Object> assignedProvider = (Map<String, Object>) request.get(Constants.ASSIGNED_PROVIDER);
            emails = fetchEmailFromUserId(Collections.singletonList((String) request.get(Constants.OWNER)));
            subjectLine = Constants.REQUEST_CONTENT_SUBJECT.replace(Constants.DEMAND_TAG, (String) request.get(Constants.DEMAND_ID));
            body = Constants.ASSIGNED_MAIL_BODY_TO_MDO.replace(Constants.DEMAND_TAG, (String) request.get(Constants.DEMAND_ID))
                    .replace(Constants.CONTENT_PROVIDER_NAME_TAG, (String) assignedProvider.get(Constants.PROVIDER_NAME));
        } else if (status.equals(Constants.INVALID)) {
            emails = fetchEmailFromUserId(Collections.singletonList((String) request.get(Constants.OWNER)));
            subjectLine = Constants.DEMAND_INVALID_SUB.replace(Constants.DEMAND_TAG, (String) request.get(Constants.DEMAND_ID));
            body = Constants.INVALID_DEMAND_MAIL_BODY_MDO.replace(Constants.DEMAND_TAG, (String) request.get(Constants.DEMAND_ID));
        } else {
            return;
        }
        mailNotificationDetails.put(Constants.EMAIL_ID_LIST, emails);
        mailNotificationDetails.put(Constants.SUB, subjectLine);
        mailNotificationDetails.put(Constants.BODY, body);
        mailNotificationDetails.put(Constants.ORG, Constants.SPV_ORG_NAME);
        mailNotificationDetails.put(Constants.ORG_NAME, Constants.SPV_ORG_NAME);
        sendNotificationToProvidersAsync(mailNotificationDetails);
    }

    private String extractAndFormatCompetencies(List<Map<String, String>> competencies, String key) {
        StringBuilder result = new StringBuilder();
        for (Map<String, String> competency : competencies) {
            result.append(competency.get(key)).append(", ");
        }
        if (result.length() > 0) {
            result.setLength(result.length() - 2);
            result.append(".");
        }
        return result.toString();
    }
}