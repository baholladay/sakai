/******************************************************************************
 * Copyright 2015 sakaiproject.org Licensed under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/ECL-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package org.sakaiproject.contentreview.tool;

import org.apache.commons.lang.StringUtils;
import org.sakaiproject.assignment.api.AssignmentService;
import org.sakaiproject.contentreview.dao.ContentReviewItem;
import org.sakaiproject.contentreview.service.ContentReviewService;
import org.sakaiproject.tool.api.SessionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import lombok.extern.slf4j.Slf4j;

/**
 * MainController
 *
 * This is the controller used by Spring MVC to handle requests
 *
 * @author Bryan Holladay
 *
 *
 */
@Slf4j
@Controller
public class MainController {

	@Autowired
	private SessionManager sessionManager;
	
	@Autowired
	@Qualifier("org.sakaiproject.contentreview.service.ContentReviewService")
	private ContentReviewService contentReviewService;
	
	@Autowired
	private AssignmentService assignmentService;


	@RequestMapping(value = "/viewreport", params={"contentId", "assignmentRef"}, method = RequestMethod.GET)
	public String viewReport(Model model, @RequestParam String contentId, @RequestParam String assignmentRef) {
		//TODO: does this work for federated course properties override?
		log.info("viewReport(): contentId: " + contentId + ", assignmentRef: " + assignmentRef);
		if(sessionManager != null && sessionManager.getCurrentSession() != null
				&& StringUtils.isNotEmpty(sessionManager.getCurrentSessionUserId())) {
			boolean isInstructor = hasInstructorPermissions(assignmentRef);
			if(!isInstructor && !hasStudentPermission(assignmentRef, contentId)) {
				//this user doesn't have access to view this item
				throw new SecurityException("A valid session ID with access to the content item is required");
			}
			return "redirect:" + contentReviewService.getReviewReportRedirectUrl(contentId, assignmentRef, sessionManager.getCurrentSessionUserId(), isInstructor);
		}
		throw new SecurityException("A valid session ID with access to the content item is required");			
	}
	
	/**
	 * Depending on the assignmentRef, a check will be used to determine whether the user should have instructor access 
	 * @param assignmentRef
	 * @return
	 */
	private boolean hasInstructorPermissions(String assignmentRef) {
		if(StringUtils.isNotEmpty(assignmentRef)) {
			if(assignmentRef.startsWith("/assignment/a/")) {
				//ASSIGNMENT1 instructor permission check
				return assignmentService.allowGradeSubmission(assignmentRef);
			}
		}
		return false;
	}

	/**
	 * Depending on the assignmentRef, a check will be used to determine whether the user should have student access
	 * @param assignmentRef
	 * @param contentId
	 * @return
	 */
	private boolean hasStudentPermission(String assignmentRef, String contentId) {
		ContentReviewItem item = contentReviewService.getContentReviewItemByContentId(contentId);
		if(item != null && sessionManager.getCurrentSessionUserId().equals(item.getUserId())) {
			return true;
		}else {
			if(assignmentRef.startsWith("/assignment/a/")) {
				//TODO: ASSIGNMENT service group permission check
			}
		}
		return false;
	}
}