/**
 * Copyright (c) 2003 The Apereo Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http:// Opensource.org/licenses/ecl2
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sakaiproject.contentreview.turnitin.oc;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.SortedSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.sakaiproject.assignment.api.AssignmentService;
import org.sakaiproject.assignment.api.model.Assignment;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.content.api.ContentHostingService;
import org.sakaiproject.content.api.ContentResource;
import org.sakaiproject.contentreview.dao.ContentReviewConstants;
import org.sakaiproject.contentreview.dao.ContentReviewItem;
import org.sakaiproject.contentreview.exception.QueueException;
import org.sakaiproject.contentreview.exception.ReportException;
import org.sakaiproject.contentreview.exception.SubmissionException;
import org.sakaiproject.contentreview.exception.TransientSubmissionException;
import org.sakaiproject.contentreview.service.BaseContentReviewService;
import org.sakaiproject.contentreview.service.ContentReviewQueueService;
import org.sakaiproject.entity.api.EntityManager;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.exception.TypeException;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.user.api.PreferencesService;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserDirectoryService;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.sf.json.JSONObject;

@Slf4j
public class ContentReviewServiceTurnitinOC extends BaseContentReviewService {
	@Setter
	private ServerConfigurationService serverConfigurationService;

	@Setter
	private UserDirectoryService userDirectoryService;

	@Setter
	private EntityManager entityManager;

	@Setter
	private SecurityService securityService;
	
	@Setter
	private PreferencesService preferencesService;
	
	@Setter
	private AssignmentService assignmentService;

	@Setter
	private SiteService siteService;

	@Setter
	private ContentReviewQueueService crqs;

	@Setter
	private ContentHostingService contentHostingService;

	private static final String SERVICE_NAME = "TurnitinOC";
	private static final String TURNITIN_OC_API_VERSION = "v1";
	private static final int TURNITIN_OC_MAX_RETRY_MINUTES = 240; // 4 hours
	private static final int TURNITIN_MAX_RETRY = 16;
	private static final String INTEGRATION_VERSION = "1.0";
	private static final String INTEGRATION_FAMILY = "sakai";
	private static final String CONTENT_TYPE_JSON = "application/json";
	private static final String CONTENT_TYPE_BINARY = "application/octet-stream";
	private static final String HEADER_NAME = "X-Turnitin-Integration-Name";
	private static final String HEADER_VERSION = "X-Turnitin-Integration-Version";
	private static final String HEADER_AUTH = "Authorization";
	private static final String HEADER_CONTENT = "Content-Type";
	private static final String HEADER_DISP = "Content-Disposition";

	private static final String STATUS_CREATED = "CREATED";
	private static final String STATUS_COMPLETE = "COMPLETE";
	private static final String STATUS_PROCESSING = "PROCESSING";

	private String serviceUrl;
	private String apiKey;

	private HashMap<String, String> BASE_HEADERS = new HashMap<String, String>();
	private HashMap<String, String> SUBMISSION_REQUEST_HEADERS = new HashMap<String, String>();
	private HashMap<String, String> SIMILARITY_REPORT_HEADERS = new HashMap<String, String>();
	private HashMap<String, String> CONTENT_UPLOAD_HEADERS = new HashMap<String, String>();

	public void init() {
		// Retrieve Service URL and API key
		serviceUrl = serverConfigurationService.getString("turnitin.oc.serviceUrl", "");
		apiKey = serverConfigurationService.getString("turnitin.oc.apiKey", "");

		// Populate base headers that are needed for all calls to TCA
		BASE_HEADERS.put(HEADER_NAME, INTEGRATION_FAMILY);
		BASE_HEADERS.put(HEADER_VERSION, INTEGRATION_VERSION);
		BASE_HEADERS.put(HEADER_AUTH, "Bearer " + apiKey);

		// Populate submission request headers used in getSubmissionId
		SUBMISSION_REQUEST_HEADERS.putAll(BASE_HEADERS);
		SUBMISSION_REQUEST_HEADERS.put(HEADER_CONTENT, CONTENT_TYPE_JSON);

		// Populate similarity report headers used in generateSimilarityReport
		SIMILARITY_REPORT_HEADERS.putAll(BASE_HEADERS);
		SIMILARITY_REPORT_HEADERS.put(HEADER_CONTENT, CONTENT_TYPE_JSON);

		// Populate content upload headers used in uploadExternalContent
		CONTENT_UPLOAD_HEADERS.putAll(BASE_HEADERS);
		CONTENT_UPLOAD_HEADERS.put(HEADER_CONTENT, CONTENT_TYPE_BINARY);
	}

	public boolean allowResubmission() {
		return true;
	}

	public void checkForReports() {

	}

	public void createAssignment(final String contextId, final String assignmentRef, final Map opts)
			throws SubmissionException, TransientSubmissionException {

	}

	public List<ContentReviewItem> getAllContentReviewItems(String siteId, String taskId)
			throws QueueException, SubmissionException, ReportException {
		return crqs.getContentReviewItems(getProviderId(), siteId, taskId);
	}

	public Map getAssignment(String arg0, String arg1) throws SubmissionException, TransientSubmissionException {
		return null;
	}

	public Date getDateQueued(String contextId) throws QueueException {
		return crqs.getDateQueued(getProviderId(), contextId);
	}

	public Date getDateSubmitted(String contextId) throws QueueException, SubmissionException {
		return crqs.getDateSubmitted(getProviderId(), contextId);
	}

	public String getIconCssClassforScore(int score, String contentId) {
		String cssClass;
		if (score < 0) {
			cssClass = "contentReviewIconThreshold-6";
		} else if (score == 0) {
			cssClass = "contentReviewIconThreshold-5";
		} else if (score < 25) {
			cssClass = "contentReviewIconThreshold-4";
		} else if (score < 50) {
			cssClass = "contentReviewIconThreshold-3";
		} else if (score < 75) {
			cssClass = "contentReviewIconThreshold-2";
		} else {
			cssClass = "contentReviewIconThreshold-1";
		}

		return cssClass;
	}

	public String getLocalizedStatusMessage(String arg0) {
		// TODO Auto-generated method stub
		return null;
	}

	public String getLocalizedStatusMessage(String arg0, String arg1) {
		// TODO Auto-generated method stub
		return null;
	}

	public String getLocalizedStatusMessage(String arg0, Locale arg1) {
		// TODO Auto-generated method stub
		return null;
	}

	public List<ContentReviewItem> getReportList(String siteId)
			throws QueueException, SubmissionException, ReportException {
		// TODO Auto-generated method stub
		return null;
	}

	public List<ContentReviewItem> getReportList(String siteId, String taskId)
			throws QueueException, SubmissionException, ReportException {
		return null;
	}

	public String getReviewReport(String contentId, String assignmentRef, String userId)
			throws QueueException, ReportException {
		return getViewerUrl(contentId, userId);
	}

	public String getReviewReportInstructor(String contentId, String assignmentRef, String userId)
			throws QueueException, ReportException {
		/**
		 * contentId:
		 * /attachment/04bad844-493c-45a1-95b4-af70129d54d1/Assignments/b9872422-fb24-4f85-abf5-2fe0e069b251/plag.docx
		 */
		return getViewerUrl(contentId, userId);
	}

	public String getReviewReportStudent(String contentId, String assignmentRef, String userId)
			throws QueueException, ReportException {
		return getViewerUrl(contentId, userId);
	}
	
	private String getViewerUrl(String userId, String contentId) {
		
		// Set variables
		String viewerUrl = null;
		
		try {
			// Get current user
			User user = userDirectoryService.getUser(userId);
			Map<String, Object> data = new HashMap<String, Object>();

			// Set user name 
			//TODO: hard code parameter names
			data.put("given_name", user.getFirstName());
			data.put("family_name", user.getLastName());

			// Check user preference for locale			
			// If user has no preference set - get the system default
			Locale locale = Optional.ofNullable(preferencesService.getLocale(userId))
					.orElse(Locale.getDefault());

			// Set locale, getLanguage removes locale region
			data.put("locale", locale.getLanguage());

			HashMap<String, Object> response = makeHttpCall("GET",
					getNormalizedServiceUrl() + "submissions/" + contentId + "/viewer-url",
					SUBMISSION_REQUEST_HEADERS,
					data,
					null);

			// Get response:
			//TODO: hard code responseCode, responseMessage, responseBody
			//TODO: NPE check on (int) and (String) casts
			int responseCode = (int) response.get("responseCode");
			String responseMessage = (String) response.get("responseMessage");
			String responseBody = (String) response.get("responseBody");
	
			// create JSONObject from responseBody
			//TODO: NPE check on resopnse body, also, move inside if(>200){}
			JSONObject responseJSON = JSONObject.fromObject(responseBody);
	
			if ((responseCode >= 200) && (responseCode < 300)) {
	
				if (responseJSON.containsKey("viewer_url")) {
					viewerUrl = responseJSON.getString("viewer_url");
					log.info("Successfully retrieved viewer url: " + viewerUrl);
					return viewerUrl;
				} else {
					throw new Error("Viewer URL not found. Response: " + responseMessage);
				}
			} else {
				throw new Error(responseMessage);
			}
		} catch (Exception e) {
			log.error(e.getLocalizedMessage(), e);
		}
	
		return viewerUrl;
	}


	public int getReviewScore(String contentId, String assignmentRef, String userId)
			throws QueueException, ReportException, Exception {
		return crqs.getReviewScore(getProviderId(), contentId);
	}

	public Long getReviewStatus(String contentId) throws QueueException {
		return crqs.getReviewStatus(getProviderId(), contentId);
	}

	public String getServiceName() {
		return SERVICE_NAME;
	}

	public boolean isAcceptableContent(ContentResource arg0) {
		// TODO: what does TII accept?
		return true;
	}

	public boolean isSiteAcceptable(Site arg0) {
		return true;
	}
	
	private HashMap<String, Object> makeHttpCall(String method, String urlStr, Map<String, String> headers,  Map<String, Object> data, byte[] dataBytes) 
		throws IOException {
		// Set variables
		HttpURLConnection connection = null;
		DataOutputStream wr = null;
		URL url = null;

		// Construct URL
		url = new URL(urlStr);

		// Open connection and set HTTP method
		connection = (HttpURLConnection) url.openConnection();
		//TODO: Do Output should only happen if there is output to write
		connection.setDoOutput(true);
		connection.setRequestMethod(method);

		// Set headers
		//TODO: NPE check got headers
		for (Entry<String, String> entry : headers.entrySet()) {
			connection.setRequestProperty(entry.getKey(), entry.getValue());
		}

		// Set Post body:
		if (data != null) {
			// Convert data to string:
			wr = new DataOutputStream(connection.getOutputStream());
			ObjectMapper objectMapper = new ObjectMapper();
			String dataStr = objectMapper.writeValueAsString(data);
			wr.writeBytes(dataStr);
			wr.flush();
			wr.close();
		} else if (dataBytes != null) {
			wr = new DataOutputStream(connection.getOutputStream());
			wr.write(dataBytes);
			wr.close();
		}

		// Send request:
		int responseCode = connection.getResponseCode();
		String responseMessage = connection.getResponseMessage();
		String responseBody = IOUtils.toString(connection.getInputStream(), StandardCharsets.UTF_8);
		
		HashMap<String, Object> response = new HashMap<String, Object>();
		//TODO: hard code responseCode, responseMessage, responseBody
		response.put("responseCode", responseCode);
		response.put("responseMessage", responseMessage);
		response.put("responseBody", responseBody);
		
		return response;
	}

	private void generateSimilarityReport(String reportId, String assignmentId) throws Exception {
		
		Assignment assignment = assignmentService.getAssignment(assignmentId);
		Map<String, String> assignmentSettings = assignment.getProperties();
		
		List<String> repositories = new ArrayList<>();
		if ("true".equals(assignmentSettings.get("internet_check"))) {
			repositories.add("INTERNET");
		}
		if ("true".equals(assignmentSettings.get("institution_check"))) {
			repositories.add("PRIVATE");
		}
		if ("true".equals(assignmentSettings.get("journal_check"))) {
			repositories.add("JOURNAL");
		}
		
		if (repositories.size() == 0) {
			throw new Error("Cannot generate similarity report - at least one search repo must be selected");
		}

		// Build header maps
		Map<String, Object> reportData = new HashMap<String, Object>();
		Map<String, Object> generationSearchSettings = new HashMap<String, Object>();
		generationSearchSettings.put("search_repositories", repositories);
		reportData.put("generation_settings", generationSearchSettings);

		Map<String, Object> viewSettings = new HashMap<String, Object>();
		viewSettings.put("exclude_quotes", "true".equals(assignmentSettings.get("exclude_quoted")));
		viewSettings.put("exclude_bibliography", "true".equals(assignmentSettings.get("exclude_biblio")));
		reportData.put("view_settings", viewSettings);
		
		HashMap<String, Object> response = makeHttpCall("PUT",
			getNormalizedServiceUrl() + "submissions/" + reportId + "/similarity",
			SIMILARITY_REPORT_HEADERS,
			reportData,
			null);
		
		// Get response:
		//TODO: hard code responseCode, responseMessage, responseBody
		//TODO: NPE check on (int) and (String) casts
		int responseCode = (int) response.get("responseCode");
		String responseMessage = (String) response.get("responseMessage");
		String responseBody = (String) response.get("responseBody");
		
		if ((responseCode >= 200) && (responseCode < 300)) {
			log.debug("Successfully initiated Similarity Report generation.");
		} else if ((responseCode == 409)) {
			log.debug("A Similarity Report is already generating for this submission");
		} else {
			throw new Error(
					"Submission failed to initiate: " + responseCode + ", " + responseMessage + ", " + responseBody);
		}
	}

	private String getSubmissionStatus(String reportId) throws Exception {
		String status = null;
		HashMap<String, Object> response = makeHttpCall("GET",
				getNormalizedServiceUrl() + "submissions/" + reportId,
				BASE_HEADERS,
				null,
				null);
		// Get response data:
		//TODO: hard code responseCode, responseMessage, responseBody
		//TODO: NPE check on (int) and (String) casts
		int responseCode = (int) response.get("responseCode");
		String responseMessage = (String) response.get("responseMessage");
		String responseBody = (String) response.get("responseBody");

		// Create JSONObject from response
		JSONObject responseJSON = JSONObject.fromObject(responseBody);
		if ((responseCode >= 200) && (responseCode < 300)) {
			// Get submission status value
			if (responseJSON.containsKey("status")) {
				status = responseJSON.getString("status");
			}
		} else {
			throw new Exception("getSubmissionStatus invalid request: " + responseCode + ", " + responseMessage + ", "
					+ responseBody);
		}
		return status;
	}

	private int getSimilarityReportStatus(String reportId) throws Exception {

		HashMap<String, Object> response = makeHttpCall("GET",
				getNormalizedServiceUrl() + "submissions/" + reportId + "/similarity",
				BASE_HEADERS,
				null,
				null);

		// Get response:
		//TODO: hard code responseCode, responseMessage, responseBody
		//TODO: NPE check on (int) and (String) casts
		int responseCode = (int) response.get("responseCode");
		String responseMessage = (String) response.get("responseMessage");
		String responseBody = (String) response.get("responseBody");

		// create JSONObject from response
		JSONObject responseJSON = JSONObject.fromObject(responseBody);

		if ((responseCode >= 200) && (responseCode < 300)) {
			// See if report is complete or pending. If pending, ignore, if complete, get
			// score and viewer URL
			if (responseJSON.containsKey("status") && responseJSON.getString("status").equals(STATUS_COMPLETE)) {
				log.debug("Submission successful");
				if (responseJSON.containsKey("overall_match_percentage")) {
					return responseJSON.getInt("overall_match_percentage");
				} else {
					log.warn("Report complete but no overall match percentage, defaulting to 0");
					//TODO: 0 is not a good response here, a normal score could be 0
					return 0;
				}

			} else if (responseJSON.containsKey("status")
					&& responseJSON.getString("status").equals(STATUS_PROCESSING)) {
				log.debug("report is processing...");
				return -1;
			} else {
				//TODO: check if there is any known "failed" states, where retry doesn't make sense... if so, stop retrying
				
				//TODO: do not throw Error, return status instead
				throw new Error("Something went wrong in the similarity report process: reportId " + reportId);
			}
		} else {
			//TODO: do not throw Error, return status instead
			throw new Error(responseMessage);
		}
	}

	private String getSubmissionId(String userID, String fileName) {

		String submissionId = null;
		try {

			// Build header maps
			Map<String, Object> data = new HashMap<String, Object>();
			data.put("owner", userID);
			data.put("title", fileName);

			HashMap<String, Object> response = makeHttpCall("POST",
					getNormalizedServiceUrl() + "submissions",
					SUBMISSION_REQUEST_HEADERS,
					data,
					null);

			// Get response:
			//TODO: hard code responseCode, responseMessage, responseBody
			//TODO: NPE check on (int) and (String) casts
			int responseCode = (int) response.get("responseCode");
			String responseMessage = (String) response.get("responseMessage");
			String responseBody = (String) response.get("responseBody");

			// create JSONObject from responseBody
			JSONObject responseJSON = JSONObject.fromObject(responseBody);

			if ((responseCode >= 200) && (responseCode < 300)) {
				if (responseJSON.containsKey("status") && responseJSON.getString("status").equals(STATUS_CREATED)
						&& responseJSON.containsKey("id")) {
					submissionId = responseJSON.getString("id");
				} else {
					log.error("getSubmissionId response: " + responseMessage);
				}
			} else {
				log.error("getSubmissionId response code: " + responseCode + ", " + responseMessage + ", "
						+ responseJSON);
			}
		} catch (Exception e) {
			log.error(e.getMessage(), e);
		}
		return submissionId;
	}

	// Queue service for processing student submissions
	// Stage one creates a submission, uploads submission contents to TCA and sets item externalId
	// Stage two starts similarity report process
	// Stage three checks status of similarity reports and retrieves report score
	// Loop 1 contains stage one and two, Loop 2 contains stage three
	public void processQueue() {
		log.info("Processing Turnitin OC submission queue");
		
		ExecutorService executor = Executors.newFixedThreadPool(2);
		executor.execute(new Runnable() {
			@Override
			public void run() {
				//TODO: put first loop here, but call it in a function
			}
		});
		executor.execute(new Runnable() {
			@Override
			public void run() {
				//TODO: put second loop here, but call it in a function
			}
		});
		executor.shutdown();
		// wait:
		try {
			if(!executor.awaitTermination(30, TimeUnit.MINUTES)){
				log.error("ContentReviewServiceTurnitinOC.processQueue: time out waiting for executor to complete");
			}
		} catch (InterruptedException e) {
			log.error(e.getMessage(), e);
		}
		
		
		
		
		
		
		int errors = 0;
		int success = 0;
		Optional<ContentReviewItem> nextItem = null;

		// LOOP 1
		while ((nextItem = crqs.getNextItemInQueueToSubmit(getProviderId())).isPresent()) {
			ContentReviewItem item = nextItem.get();
			
			// Create new Calendar instance used for adding delay time to current time
			Calendar cal = Calendar.getInstance();
			// If retry count is null set to 0
			if (item.getRetryCount() == null) {
				item.setRetryCount(Long.valueOf(0));
				item.setNextRetryTime(cal.getTime());
				crqs.update(item);
				// If retry count is above maximum increment error count, set status to nine and stop retrying
			} else if (item.getRetryCount().intValue() > TURNITIN_MAX_RETRY) {
				item.setStatus(ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_EXCEEDED_CODE);
				crqs.update(item);
				errors++;
				continue;
				// Increment retry count, adjust delay time, schedule next retry attempt
			} else {
				long retryCount = item.getRetryCount().longValue();
				retryCount++;
				item.setRetryCount(Long.valueOf(retryCount));
				cal.add(Calendar.MINUTE, getDelayTime(retryCount));
				item.setNextRetryTime(cal.getTime());
				crqs.update(item);
			}

			ContentResource resource = null;
			try {
				// Get resource with current item's content Id
				resource = contentHostingService.getResource(item.getContentId());
			} catch (IdUnusedException e4) {
				log.error("IdUnusedException: no resource with id " + item.getContentId(), e4);
				item.setLastError("IdUnusedException: no resource with id " + item.getContentId());
				item.setStatus(ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_NO_RETRY_CODE);
				crqs.update(item);
				errors++;
				continue;
			} catch (PermissionException e) {
				log.error("PermissionException: no resource with id " + item.getContentId(), e);
				item.setLastError("PermissionException: no resource with id " + item.getContentId());
				item.setStatus(ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_NO_RETRY_CODE);
				crqs.update(item);
				errors++;
				continue;
			} catch (TypeException e) {
				log.error("TypeException: no resource with id " + item.getContentId(), e);
				item.setLastError("TypeException: no resource with id " + item.getContentId());
				item.setStatus(ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_NO_RETRY_CODE);
				crqs.update(item);
				errors++;
				continue;
			}
			// Get filename of submission
			String fileName = resource.getProperties().getProperty(ResourceProperties.PROP_DISPLAY_NAME);
			if (StringUtils.isEmpty(fileName)) {
				// Set default file name:
				fileName = "submission_" + item.getUserId() + "_" + item.getSiteId();
				log.info("Using Default Filename " + fileName);
			}
			// EXTERNAL ID DOES NOT EXIST, CREATE SUBMISSION AND UPLOAD CONTENTS TO TCA
			// (STAGE 1)
			if (StringUtils.isEmpty(item.getExternalId())) {
				try {
					log.info("Submission starting...");
					// Retrieve submissionId from TCA and set to externalId
					String externalId = getSubmissionId(item.getUserId(), fileName);
					if (StringUtils.isEmpty(externalId)) {
						throw new Error("submission id is missing");

					} else {
						// Add filename to content upload headers
						CONTENT_UPLOAD_HEADERS.put(HEADER_DISP, "inline; filename=\"" + fileName + "\"");
						// Upload submission contents of to TCA
						uploadExternalContent(externalId, resource.getContent());
						// Set item externalId to externalId
						item.setExternalId(externalId);
						// Reset retry count
						item.setRetryCount(new Long(0));
						// Reset cal to current time
						cal.setTime(new Date());
						// Reset delay time
						cal.add(Calendar.MINUTE, getDelayTime(item.getRetryCount()));
						// Schedule next retry time
						item.setNextRetryTime(cal.getTime());
						item.setDateSubmitted(new Date());
						crqs.update(item);
						success++;
					}
				} catch (Exception e) {
					log.error(e.getMessage(), e);
					item.setLastError(e.getMessage());
					item.setStatus(ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_CODE);
					crqs.update(item);
					errors++;
				}
			} else {
				// EXTERNAL ID EXISTS, START SIMILARITY REPORT GENERATION PROCESS (STAGE 2)
				try {
					// Get submission status, returns the state of the submission as string
					String submissionStatus = getSubmissionStatus(item.getExternalId());
					// Handle possible error status
					String errorStr = null;
					switch (submissionStatus) {
					case "COMPLETE":
						// If submission status is complete, start similarity report process
						generateSimilarityReport(item.getExternalId(), item.getTaskId().split("/")[4]);
						// Update item status for loop 2
						item.setStatus(ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_AWAITING_REPORT_CODE);
						// Reset retry count
						item.setRetryCount(new Long(0));
						// Reset cal to current time
						cal.setTime(new Date());
						// Reset delay time
						cal.add(Calendar.MINUTE, getDelayTime(item.getRetryCount()));
						// Schedule next retry time
						item.setNextRetryTime(cal.getTime());
						crqs.update(item);
						success++;
					case "PROCESSING":
						// do nothing... try again
						continue;
					case "CREATED":
						// do nothing... try again
						continue;
					case "UNSUPPORTED_FILETYPE":
						errorStr = "The uploaded filetype is not supported";
					case "PROCESSING_ERROR":
						errorStr = "An unspecified error occurred while processing the submissions";
					case "TOO_LITTLE_TEXT":
						errorStr = "The submission does not have enough text to generate a Similarity Report (a submission must contain at least 20 words)";
					case "TOO_MUCH_TEXT":
						errorStr = "The submission has too much text to generate a Similarity Report (after extracted text is converted to UTF-8, the submission must contain less than 2MB of text)";
					case "TOO_MANY_PAGES":
						errorStr = "The submission has too many pages to generate a Similarity Report (a submission cannot contain more than 400 pages)";
					case "FILE_LOCKED":
						errorStr = "The uploaded file requires a password in order to be opened";
					case "CORRUPT_FILE":
						errorStr = "The uploaded file appears to be corrupt";
					case "ERROR":				
						errorStr = "Submission returned with ERROR status";
					default:
						log.info("Unknown submission status, will retry: " + submissionStatus);
					}
					if(StringUtils.isNotEmpty(errorStr)) {
						item.setLastError(errorStr);
						item.setStatus(ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_NO_RETRY_CODE);
						crqs.update(item);
						errors++;
					}
				} catch (Exception e) {
					log.error(e.getMessage(), e);
					item.setLastError(e.getMessage());
					item.setStatus(ContentReviewConstants.CONTENT_REVIEW_SUBMISSION_ERROR_RETRY_CODE);
					crqs.update(item);
					errors++;
				}
			}
		}

		// LOOP 2
		// UPLOADED CONTENTS, AWAITING SIMILAIRTY REPORT (STAGE 3)
		for (ContentReviewItem item : crqs.getAwaitingReports(getProviderId())) {
			// Make sure it's after the next retry time
			if (item.getNextRetryTime().getTime() > new Date().getTime()) {
				continue;
			}
			try {
				// Get status of similarity report
				// Returns -1 if report is still processing
				// Returns -2 if an error occurs
				// Else returns reports score as integer
				int status = getSimilarityReportStatus(item.getExternalId());
				if (status > -1) {					
					log.info("Report complete! Score: " + status);
					// Status value is report score
					item.setReviewScore(status);		
					item.setStatus(ContentReviewConstants.CONTENT_REVIEW_SUBMITTED_REPORT_AVAILABLE_CODE);
					item.setDateReportReceived(new Date());
					item.setRetryCount(Long.valueOf(0));
					item.setLastError(null);
					item.setErrorCode(null);
					crqs.update(item);
				} else if (status == -1) {
					// Similarity report is still generating, will try again
					log.info("Processing report " + item.getExternalId() + "...");
				} else if(status == -2){
					throw new Error("Unknown error during report status call");
				}
			} catch (Exception e) {
				log.error(e.getLocalizedMessage(), e);
				item.setLastError(e.getMessage());
				item.setStatus(ContentReviewConstants.CONTENT_REVIEW_REPORT_ERROR_RETRY_CODE);
				crqs.update(item);
				errors++;
			}
		}

		log.info("Submission Turnitin queue run completed: " + success + " items submitted, " + errors + " errors.");
	}

	public int getDelayTime(long retries) {
		// exponential retry algorithm that caps the retries off at 36 hours (checking once every 4 hours max)
		int minutes = (int) Math.pow(2, retries < TURNITIN_MAX_RETRY ? retries : 1); // built in check for max retries
																						// to fail quicker
		return minutes > TURNITIN_OC_MAX_RETRY_MINUTES ? TURNITIN_OC_MAX_RETRY_MINUTES : minutes;
	}

	public void queueContent(String userId, String siteId, String assignmentReference, List<ContentResource> content)
			throws QueueException {
		crqs.queueContent(getProviderId(), userId, siteId, assignmentReference, content);
	}

	public void removeFromQueue(String contentId) {
		crqs.removeFromQueue(getProviderId(), contentId);
	}

	public void resetUserDetailsLockedItems(String arg0) {
		// TODO Auto-generated method stub
	}

	public String getReviewError(String contentId) {
		return null;
	}

	public boolean allowAllContent() {
		return true;
	}

	public Map<String, SortedSet<String>> getAcceptableExtensionsToMimeTypes() {
		return new HashMap<String, SortedSet<String>>();
	}

	public Map<String, SortedSet<String>> getAcceptableFileTypesToExtensions() {
		return new HashMap<String, SortedSet<String>>();
	}

	private String getNormalizedServiceUrl() {
		return serviceUrl + ((StringUtils.isNotEmpty(serviceUrl) && serviceUrl.endsWith("/")) ? "" : "/")
				+ TURNITIN_OC_API_VERSION + "/";
	}

	private void uploadExternalContent(String reportId, byte[] data) throws Exception {
		
		HashMap<String, Object> response = makeHttpCall("PUT",
				getNormalizedServiceUrl() + "submissions/" + reportId + "/original/",
				CONTENT_UPLOAD_HEADERS,
				null,
				data);

		// Get response:
		//TODO: hard code responseCode, responseMessage, responseBody
		//TODO: NPE check on (int) and (String) casts
		int responseCode = (int) response.get("responseCode");
		String responseMessage = (String) response.get("responseMessage");

		if (responseCode < 200 || responseCode >= 300) {
			throw new Error(responseCode + ": " + responseMessage);
		}
	}

	@Override
	public ContentReviewItem getContentReviewItemByContentId(String contentId) {
		Optional<ContentReviewItem> cri = crqs.getQueuedItem(getProviderId(), contentId);
		if (cri.isPresent()) {
			ContentReviewItem item = cri.get();

			// Turnitin specific work
			try {
				int score = getReviewScore(contentId, item.getTaskId(), null);
				log.debug(" getReviewScore returned a score of: {} ", score);
			} catch (Exception e) {
				log.error("Turnitin - getReviewScore error called from getContentReviewItemByContentId with content " + contentId + ", " + e.getMessage(), e);
			}
			return item;
		}
		return null;
	}
	
	@Override
	public String getEndUserLicenseAgreementLink() {
		return "https://www.vericite.com";
	}

	@Override
	public Instant getEndUserLicenseAgreementTimestamp() {
		return Instant.MIN;
	}
}
