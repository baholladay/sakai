package org.sakaiproject.contentreview.jobs;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.StatefulJob;

import org.sakaiproject.contentreview.service.ContentReviewService;

public class ContentReviewReports implements StatefulJob {

	private ContentReviewService contentReviewService;
	public void setContentReviewService(ContentReviewService sd){
		contentReviewService = sd;
		

	}
	
	public void execute(JobExecutionContext arg0) throws JobExecutionException {
		contentReviewService.checkForReports();
	}

}
