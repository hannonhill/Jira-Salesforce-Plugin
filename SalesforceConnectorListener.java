package com.hannonhill.jira.plugins;

import com.atlassian.jira.event.issue.AbstractIssueEventListener;
import com.atlassian.jira.event.issue.IssueEventListener;
import java.util.Map;

/* Classes Generated from the Sforce enterprise.wsdl file */
import com.sforce.soap.enterprise.*;
import com.sforce.soap.enterprise.fault.ExceptionCode;
import com.sforce.soap.enterprise.fault.ApiFault;
import com.sforce.soap.enterprise.fault.LoginFault;
import com.sforce.soap.enterprise.sobject.*;

/* These classes are standard with the Jira installation */
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.ModifiedValue;
import com.atlassian.jira.issue.util.IssueChangeHolder;
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder;
import com.atlassian.jira.event.issue.IssueEvent;
import com.opensymphony.user.User;
import com.atlassian.jira.ComponentManager;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.fields.CustomField;

public class SalesforceConnectorListener extends AbstractIssueEventListener
		implements IssueEventListener {
	
	private String _uName, _password, _token;

	public void init(Map params){		
		this._uName = (String) params.get("Salesforce Username");
		this._password = (String) params.get("Salesforce Password");
		this._token = (String) params.get("Salesforce Security Token");
		System.out.println(this._password);
	}
	
	public String[] getAcceptedParams(){
		/* These are the parameters for administrators to configure in Jira */
		String[] params = {"Salesforce Username", "Salesforce Password", "Salesforce Security Token"};
		return params;
	}
	
	public static SoapBindingStub login(String uName, String password)
	{
		SoapBindingStub binding;
		LoginResult lr;
		try{
			binding = (SoapBindingStub) new SforceServiceLocator().getSoap();
			lr = binding.login(uName, password);
			
			if( lr.isPasswordExpired() )
			{
				System.out.println("The supplied password has expired!");
			}
			
			binding._setProperty(SoapBindingStub.ENDPOINT_ADDRESS_PROPERTY, lr.getServerUrl());
			
			SessionHeader sh = new SessionHeader();
			sh.setSessionId(lr.getSessionId());
			binding.setHeader(new SforceServiceLocator().getServiceName().getNamespaceURI(), "SessionHeader", sh);
			System.out.println("Salesforce Login Successful");
			return binding;
			
		}catch(LoginFault ex){
			 ExceptionCode exCode = ex.getExceptionCode();
	            if (exCode == ExceptionCode.FUNCTIONALITY_NOT_ENABLED ||
	                exCode == ExceptionCode.INVALID_CLIENT ||
	                exCode == ExceptionCode.INVALID_LOGIN ||
	                exCode == ExceptionCode.LOGIN_DURING_RESTRICTED_DOMAIN ||
	                exCode == ExceptionCode.LOGIN_DURING_RESTRICTED_TIME ||
	                exCode == ExceptionCode.ORG_LOCKED ||
	                exCode == ExceptionCode.PASSWORD_LOCKOUT ||
	                exCode == ExceptionCode.SERVER_UNAVAILABLE ||
	                exCode == ExceptionCode.TRIAL_EXPIRED ||
	                exCode == ExceptionCode.UNSUPPORTED_CLIENT) {
	            	System.out.println("Please Be Sure the Sforce Username and Password are valid " + exCode.toString() + " " + ex.toString());
	            }else{
	            	System.out.println("An unexpected error has occured: " + ex.getExceptionCode() + " - " + ex.getExceptionMessage());
	            }
		}catch(Exception ex){
			System.out.println("An unexpected error has occured:" + ex.getMessage());
		}		
		
		return null;
	}
	
	public static String createCase(String ownerId, String contactId, String accountId, String type, String subject, String description, String bugNumber, SoapBindingStub binding)
	{
		_case c = new _case();
		c.setOwnerId(ownerId);
		c.setAccountId(accountId);
		c.setContactId(contactId);
		c.setStatus("New");
		c.setType(type);
		c.setSubject(subject);
		c.setDescription(description);
		c.setOrigin("Web");
		c.setJira_id__c(bugNumber);
		_case[] ca = {c};
		try{
			SaveResult[] sr  = binding.create(ca);
			System.out.println("Case Created");
			return sr[0].getId();
		}catch(Exception ex)
		{
			System.out.println("Error Creating Case:\n " + ex.getLocalizedMessage());
			return "";
		}	
		
	}
	
	public static void closeCase(String caseId, SoapBindingStub binding)
	{
		QueryResult qr = runQuery("select Id, Status from Case where id = '" + caseId + "'", binding);
		if(qr.getSize() == 1)
		{
			_case c = (_case)qr.getRecords(0);
			c.setStatus("Closed");
			_case[] ca = {c};
			try{
				binding.update(ca);
				System.out.println("Case Closed");
			}catch(Exception ex)
			{
				System.out.println("Error Closing Case:\n " + ex.getLocalizedMessage());
			}
		}
	}
	
	public static void updateCaseContact(String caseId, String contactId, String accountId, SoapBindingStub binding)
	{
		QueryResult qr = runQuery("select Id, ContactId from Case where id = '" + caseId + "'", binding);
		if(qr.getSize() == 1)
		{
			_case c = (_case)qr.getRecords(0);
			c.setContactId(contactId);
			c.setAccountId(accountId);
			_case[] ca = {c};
			try{
				binding.update(ca);
				System.out.println("Case Contact Updated");
			}catch(Exception ex)
			{
				System.out.println("Error Updating Case Contact:\n " + ex.getLocalizedMessage());
			}
		}
	}
	
	public static String getContactIdByCase(String caseId, SoapBindingStub binding)
	{
		QueryResult qr = runQuery("select Id, ContactId from Case where id = '" + caseId + "'", binding);
		if(qr.getSize() == 1)
		{
			_case c = (_case)qr.getRecords(0);			
			return c.getContactId();
		}
		return "";
	}
	
	public static String getContactEmailById(String contactId, SoapBindingStub binding)
	{
		QueryResult qr = runQuery("Select Email from Contact where id = '" + contactId + "'", binding);
		if(qr.getSize() == 1)
		{
			Contact con = (Contact)qr.getRecords(0);
			return con.getEmail();
		}
		return "";
	}
	
	public static QueryResult runQuery(String query, SoapBindingStub binding)
	{
		QueryOptions qo = new QueryOptions();
		qo.setBatchSize(Integer.valueOf(200));
		binding.setHeader(new SforceServiceLocator().getServiceName().getNamespaceURI(), "QueryOptions", qo);
		QueryResult qr = new QueryResult();
		try{
			qr = binding.query(query);
		}catch (ApiFault ex)
		{	
			System.out.println("\nFailed to execute query succesfully, error message was API Fault:\n" + ex.getMessage() + "\n Query: " + query);
			

		}catch(Exception ex)
		{
			System.out.println("\nFailed to execute query succesfully, error message was:\n" + ex.getMessage()+ "\n Query: " + query);
		}
		return qr;
	}
	
	public static String[] getUserInfoByEmail(String email, SoapBindingStub binding)
	{
		QueryResult qr = runQuery("select Id, Name from User where email = '" + email + "'", binding);
		if(qr.getSize() >= 1)
		{
			com.sforce.soap.enterprise.sobject.User u = (com.sforce.soap.enterprise.sobject.User)qr.getRecords(0);
			return new String[] {u.getId(), u.getName()};
		}
		return null;
	}
	
	public static String[] getContactInfoByEmail(String email, SoapBindingStub binding)
	{
		//return an array 0. Contact ID 1. Account Id 2. Name 3.Phone 4.Email 
		String[] ret = new String[5];
		QueryResult qr = runQuery("select Id, AccountId, Name, Phone, Email from Contact where email = '" + email + "'", binding);
		if(qr.getSize() >= 1)
		{
			Contact c = (Contact)qr.getRecords(0);
			ret[0] = c.getId();
			ret[1] = c.getAccountId();
			ret[2] = c.getName();
			ret[3] = c.getPhone();
			ret[4] = c.getEmail();
			return ret;
		}
		return null;
	}
	
	public static String[] getAccountInfoById(String id, SoapBindingStub binding)
	{
		//returns an array 0. Account Name 1. Account Owner ID
		String[] ret = new String[2];
		QueryResult qr = runQuery("select Name, OwnerId from Account where id = '" + id + "'", binding);
		if(qr.getSize() == 1)
		{
			Account a = (Account)qr.getRecords(0);
			ret[0] = a.getName();
			ret[1] = a.getOwnerId();
			return ret;
		}
		return null;
	}
	
	public static String getUserNameById(String id, SoapBindingStub binding)
	{
		QueryResult qr = runQuery("select Name from User where id = '" + id + "'", binding);
		if(qr.getSize() == 1)
		{
			com.sforce.soap.enterprise.sobject.User u = (com.sforce.soap.enterprise.sobject.User)qr.getRecords(0);
			return u.getName();			
		}
		return null;
	}
	
	public void issueCreated(IssueEvent event)
	{
		//The custom field manager is used to create Ojbects to govern the cusom fields in Jira
		CustomFieldManager customFieldManager = ComponentManager.getInstance().getCustomFieldManager();
		
		//System.out.println("Starting to send case to Salesforce");
		SoapBindingStub binding = login(this._uName, this._password + this._token);
		
		Issue i = event.getIssue();
		
		
		IssueChangeHolder changeHolder = new DefaultIssueChangeHolder();
		
		if(i.getReporter() != null && i.getAssignee() != null)
		{
			String conEmail = ((com.opensymphony.user.User)(i.getReporter())).getEmail();
			String uEmail = ((com.opensymphony.user.User)(i.getAssignee())).getEmail();
			
			String[] contactInfo = getContactInfoByEmail(conEmail, binding);
			String[] uInfo = getUserInfoByEmail(uEmail, binding);
			if(uInfo == null || contactInfo == null)
			{
				System.out.println("Id's returned as Blank " + conEmail + " " + uEmail);
				return;
				
			}		
			String caseId = createCase(uInfo[0], contactInfo[0], contactInfo[1], i.getIssueType().get("name").toString(), i.getSummary(), i.getDescription(), i.getKey(), binding );
			
			String[] accountInfo = getAccountInfoById(contactInfo[1], binding);
			
			//create the custom field objects 
			CustomField cfAccountName = customFieldManager.getCustomFieldObjectByName("Salesforce Account");
			CustomField cfAccountUrl = customFieldManager.getCustomFieldObjectByName("Salesforce Address");
			CustomField cfAccountOwner = customFieldManager.getCustomFieldObjectByName("Salesforce Account Owner");
			CustomField cfContactName = customFieldManager.getCustomFieldObjectByName("Salesforce Contact Name");
			CustomField cfContactEmail = customFieldManager.getCustomFieldObjectByName("Salesforce Contact Email");
			CustomField cfContactPhone = customFieldManager.getCustomFieldObjectByName("Salesforce Contact Phone");
			CustomField cfCaseId = customFieldManager.getCustomFieldObjectByName("Salesforce Case Id");
			
			//set the values of the custom fields, see http://confluence.atlassian.com/pages/viewpage.action?pageId=160835
			cfAccountName.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfAccountName), accountInfo[0]), changeHolder);
			cfAccountUrl.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfAccountUrl), "https://na2.salesforce.com/"+ contactInfo[1]), changeHolder);
			
			String ownerName = getUserNameById(accountInfo[1], binding);
			cfAccountOwner.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfAccountOwner), ownerName), changeHolder);
			
			cfContactName.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfContactName), contactInfo[2]), changeHolder);
			cfContactEmail.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfContactEmail), contactInfo[4]), changeHolder);
			cfContactPhone.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfContactPhone), contactInfo[3]), changeHolder);
			cfCaseId.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfCaseId), caseId), changeHolder);
		}
	}
	
	public void issueUpdated(IssueEvent event)
	{
		CustomFieldManager customFieldManager = ComponentManager.getInstance().getCustomFieldManager();
		CustomField cfCaseId = customFieldManager.getCustomFieldObjectByName("Salesforce Case Id");
		Issue i = event.getIssue();
		
		if(i.getCustomFieldValue(cfCaseId) == null || i.getCustomFieldValue(cfCaseId) == "")
		{
			System.out.println("Starting to send case to Salesforce");
			SoapBindingStub binding = login(this._uName, this._password + this._token);
						
			CustomField cfAccountName = customFieldManager.getCustomFieldObjectByName("Salesforce Account");
			CustomField cfAccountUrl = customFieldManager.getCustomFieldObjectByName("Salesforce Address");
			CustomField cfAccountOwner = customFieldManager.getCustomFieldObjectByName("Salesforce Account Owner");
			CustomField cfContactName = customFieldManager.getCustomFieldObjectByName("Salesforce Contact Name");
			CustomField cfContactEmail = customFieldManager.getCustomFieldObjectByName("Salesforce Contact Email");
			CustomField cfContactPhone = customFieldManager.getCustomFieldObjectByName("Salesforce Contact Phone");
			
			IssueChangeHolder changeHolder = new DefaultIssueChangeHolder();
			
			String conEmail = ((com.opensymphony.user.User)(i.getReporter())).getEmail();
			String uEmail = ((com.opensymphony.user.User)(i.getAssignee())).getEmail();
			
			String[] contactInfo = getContactInfoByEmail(conEmail, binding);
			String[] uInfo = getUserInfoByEmail(uEmail, binding);
			if(uInfo == null || contactInfo == null)
			{
				System.out.println("Id's returned as Blank");
				return;
				
			}		
			String caseId = createCase(uInfo[0], contactInfo[0], contactInfo[1], i.getIssueType().get("name").toString(), i.getSummary(), i.getDescription(), i.getKey(), binding );
			
			String[] accountInfo = getAccountInfoById(contactInfo[1], binding);
			
			cfAccountName.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfAccountName), accountInfo[0]), changeHolder);
			cfAccountUrl.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfAccountUrl), "https://na2.salesforce.com/"+ contactInfo[1]), changeHolder);
			
			String ownerName = getUserNameById(accountInfo[1], binding);
			cfAccountOwner.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfAccountOwner), ownerName), changeHolder);
			
			cfContactName.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfContactName), contactInfo[2]), changeHolder);
			cfContactEmail.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfContactEmail), contactInfo[4]), changeHolder);
			cfContactPhone.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfContactPhone), contactInfo[3]), changeHolder);
			cfCaseId.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfCaseId), caseId), changeHolder);
		}else{
			System.out.println("Starting to send case to Salesforce");
			SoapBindingStub binding = login(this._uName, this._password + this._token);
			
			String conEmail = ((com.opensymphony.user.User)(i.getReporter())).getEmail();
			//System.out.println(conEmail + ", " + getContactEmailById(getContactIdByCase((String)i.getCustomFieldValue(cfCaseId), binding),binding));
			if(conEmail != getContactEmailById(getContactIdByCase((String)i.getCustomFieldValue(cfCaseId), binding),binding))
			{
				String[] contactInfo = getContactInfoByEmail(conEmail, binding);
				updateCaseContact((String)i.getCustomFieldValue(cfCaseId), contactInfo[0], contactInfo[1], binding);
				
				CustomField cfContactName = customFieldManager.getCustomFieldObjectByName("Salesforce Contact Name");
				CustomField cfContactEmail = customFieldManager.getCustomFieldObjectByName("Salesforce Contact Email");
				CustomField cfContactPhone = customFieldManager.getCustomFieldObjectByName("Salesforce Contact Phone");
				CustomField cfAccountName = customFieldManager.getCustomFieldObjectByName("Salesforce Account");
				CustomField cfAccountUrl = customFieldManager.getCustomFieldObjectByName("Salesforce Address");
				CustomField cfAccountOwner = customFieldManager.getCustomFieldObjectByName("Salesforce Account Owner");
				
				IssueChangeHolder changeHolder = new DefaultIssueChangeHolder();
				
				cfContactName.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfContactName), contactInfo[2]), changeHolder);
				cfContactEmail.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfContactEmail), contactInfo[4]), changeHolder);
				cfContactPhone.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfContactPhone), contactInfo[3]), changeHolder);
				
				String[] accountInfo = getAccountInfoById(contactInfo[1], binding);
				
				cfAccountName.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfAccountName), accountInfo[0]), changeHolder);
				cfAccountUrl.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfAccountUrl), "https://na2.salesforce.com/"+ contactInfo[1]), changeHolder);
				String ownerName = getUserNameById(accountInfo[1], binding);
				cfAccountOwner.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfAccountOwner), ownerName), changeHolder);
			}
		}
	}
	
	public void issueClosed(IssueEvent event)
	{
		CustomFieldManager customFieldManager = ComponentManager.getInstance().getCustomFieldManager();
		CustomField cfCaseId = customFieldManager.getCustomFieldObjectByName("Salesforce Case Id");
		Issue i = event.getIssue();
		String caseId = (String)i.getCustomFieldValue(cfCaseId);
		if(caseId != null && caseId != "")
		{
			System.out.println("Starting to send case to Salesforce");
			SoapBindingStub binding = login(this._uName, this._password + this._token);
			
			closeCase(caseId, binding);
		}
	}
	
	public void issueResolved(IssueEvent event)
	{
		issueClosed(event);
	}
	
	public void issueAssigned(IssueEvent event)
	{
		issueUpdated(event);
	}
	
	public void issueStarted(IssueEvent event)
	{
		issueUpdated(event);
	}
}
