package org.sakaiproject.contentreview.service;

import java.time.Instant;

import org.apache.commons.lang.StringUtils;
import org.sakaiproject.entity.api.EntityPropertyNotDefinedException;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.entity.api.ResourcePropertiesEdit;
import org.sakaiproject.user.api.PreferencesEdit;
import org.sakaiproject.user.api.PreferencesService;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BaseContentReviewService implements ContentReviewService{
	
	@Setter
	protected PreferencesService preferencesService;
	
	private static final String PROP_KEY_EULA = "contentReviewEULA";
	private static final String PROP_KEY_EULA_TIMESTAMP = "contentReviewEULATimestamp";
	private static final String PROP_KEY_EULA_VERSION = "contentReviewEULAVersion";
	
	@Override
	public Instant getUserEULATimestamp(String userId) {
		Instant timestamp = null;
		try {
			ResourceProperties pref = preferencesService.getPreferences(userId).getProperties(PROP_KEY_EULA + getProviderId());
			if(pref != null) {
				timestamp = Instant.ofEpochMilli(pref.getLongProperty(PROP_KEY_EULA_TIMESTAMP));
			}
		}catch(EntityPropertyNotDefinedException e) {
			//nothing to do, prop is just not set
		}catch(Exception e) {
			log.error(e.getMessage(), e);
		}
		return timestamp;
	}
	
	@Override
	public String getUserEULAVersion(String userId) {
		String version = null;
		try {
			ResourceProperties pref = preferencesService.getPreferences(userId).getProperties(PROP_KEY_EULA + getProviderId());
			if(pref != null) {
				version = pref.getProperty(PROP_KEY_EULA_VERSION);
			}
		}catch(Exception e) {
			log.error(e.getMessage(), e);
		}
		return version;
	}
	
	@Override
	public void updateUserEULATimestamp(String userId) {
		try {
			PreferencesEdit pref = preferencesService.edit(userId);
			try {
				if(pref != null) {
					ResourcePropertiesEdit resourcePropEdit = pref.getPropertiesEdit(PROP_KEY_EULA + getProviderId());
					if(resourcePropEdit != null) {
						resourcePropEdit.addProperty(PROP_KEY_EULA_TIMESTAMP, "" + Instant.now().toEpochMilli());
						String EULAVersion = getEndUserLicenseAgreementVersion();
						if(StringUtils.isNotEmpty(EULAVersion)) {
							resourcePropEdit.addProperty(PROP_KEY_EULA_VERSION, EULAVersion);
						}
						preferencesService.commit(pref);
					}else {
						preferencesService.cancel(pref);
					}
				}
			}catch(Exception e) {
				log.error(e.getMessage(), e);
				preferencesService.cancel(pref);
			}
		}catch(Exception e) {
			log.error(e.getMessage(), e);
		}	
	}
}
