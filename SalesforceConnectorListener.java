package com.hannonhill.jira.plugins;

import java.util.Map;

import com.atlassian.jira.ComponentManager;
import com.atlassian.jira.ManagerFactory;
import com.atlassian.jira.config.properties.APKeys;
import com.atlassian.jira.event.issue.AbstractIssueEventListener;
import com.atlassian.jira.event.issue.IssueEvent;
import com.atlassian.jira.event.issue.IssueEventListener;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.ModifiedValue;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder;
import com.atlassian.jira.issue.util.IssueChangeHolder;
import com.atlassian.mail.MailException;
import com.atlassian.mail.MailFactory;
import com.atlassian.mail.server.MailServerManager;
import com.atlassian.mail.server.SMTPMailServer;
import com.sforce.soap.enterprise.LoginResult;
import com.sforce.soap.enterprise.QueryOptions;
import com.sforce.soap.enterprise.QueryResult;
import com.sforce.soap.enterprise.SaveResult;
import com.sforce.soap.enterprise.SessionHeader;
import com.sforce.soap.enterprise.SforceServiceLocator;
import com.sforce.soap.enterprise.SoapBindingStub;
import com.sforce.soap.enterprise.fault.ApiFault;
import com.sforce.soap.enterprise.fault.ExceptionCode;
import com.sforce.soap.enterprise.fault.LoginFault;
import com.sforce.soap.enterprise.sobject.Account;
import com.sforce.soap.enterprise.sobject.Contact;
import com.sforce.soap.enterprise.sobject._case;

public class SalesforceConnectorListener extends AbstractIssueEventListener
		implements IssueEventListener {
	
	private String _uName, _password, _token, _emails;
	private String[] _projects;
	
	/**
	 * Populate the parameters set in Jira
	 * @see com.atlassian.jira.event.issue.AbstractIssueEventListener#init(java.util.Map)
	 */
	public void init(Map params){		
		this._uName = (String) params.get("Salesforce Username");//Salesforce login username
		this._password = (String) params.get("Salesforce Password");//Salesforce login password
		this._token = (String) params.get("Salesforce Security Token");//Salesforce Security Token
		this._emails = (String) params.get("Notification Emails");//Email Addresses to send notifications
		this._projects = ((String) params.get("Jira Project Keys")).replace(" ", "").split("'");//Projects to create cases for
	}
	
	/**
	 * @see com.atlassian.jira.event.issue.AbstractIssueEventListener#getAcceptedParams()
	 */
	public String[] getAcceptedParams(){
		/* These are the parameters for administrators to configure in Jira */
		String[] params = {"Salesforce Username", "Salesforce Password", "Salesforce Security Token", "Jira Project Keys", "Notification Emails"};
		return params;
	}
	
	/**
	 * Log in to Salesforce
	 * @param uName
	 * @param password password and security token concatenated
	 * @return Soap Binding for salesforce
	 * @throws Exception
	 */
	public static SoapBindingStub login(String uName, String password) throws Exception
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
			
			binding._setProperty(SoapBindingStub.ENDPOINT_ADDRESS_PROPERTY, lr.getServerUrl());//Once logged in you have to set what Salesforce Instance your organization is assigned to
			
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
	            throw ex;
		}catch(Exception ex){
			System.out.println("An unexpected error has occured:" + ex.getMessage());
			throw ex;
		}
	}
	
	/**
	 * Creates a new case object in Salesforce 
	 * @param ownerId - Salesforce Id of User who the issue is assigned to
	 * @param contactId - Salesforce Id of the Reporting user on the issue
	 * @param accountId - Salesforce Id of the Account the Reporting user is associated with
	 * @param type      - The type of issue this is
	 * @param subject   - The Jira Issue Summary
	 * @param description - The Jira Issue Description
	 * @param bugNumber   - The Jira Key of the issue
	 * @param binding     - The Salesforce Soap Binding from the login() method
	 * @return The Salesforce Id of the Case Object
	 * @throws Exception
	 */
	public static String createCase(String ownerId, String contactId, String accountId, String type, String subject, String description, String bugNumber, SoapBindingStub binding) throws Exception
	{
		_case c = new _case();
		c.setOwnerId(ownerId);
		c.setAccountId(accountId);
		c.setContactId(contactId);
		c.setStatus("New");
		c.setType(type);
		c.setSubject(subject);
		c.setDescription(description);
		c.setOrigin("Web");//Origin is required so I just set it to web
		c.setJira_id__c(bugNumber);
		_case[] ca = {c};//wrap object in an array for the create method
		try{
			SaveResult[] sr  = binding.create(ca);
			System.out.println("Case Created");
			return sr[0].getId();
		}catch(Exception ex)
		{
			System.out.println("Error Creating Case:\n " + ex.getLocalizedMessage());
			throw ex;
		}		
	}
	
	/**
	 * Close Case in Salesforce
	 * @param caseId Salesforce Id of Case
	 * @param binding Salesforce Soap Binding from login() method
	 * @throws Exception
	 */
	public static void closeCase(String caseId, SoapBindingStub binding) throws Exception
	{
		QueryResult qr = runQuery("select Id, Status from Case where id = '" + caseId + "'", binding);
		if(qr.getSize() == 1)
		{
			_case c = (_case)qr.getRecords(0);
			c.setStatus("Closed");
			_case[] ca = {c};//wrap object in an array for the update method
			try{
				binding.update(ca);
				System.out.println("Case Closed");
			}catch(Exception ex)
			{
				System.out.println("Error Closing Case:\n " + ex.getLocalizedMessage());
				throw ex;
			}
		}
	}
	
	/**
	 * Update a Salesforce Case with information from a Jira Issue
	 * @param caseId Salesforce Id of the Case Object
	 * @param contactId Salesforce Id of the Contact associated with the Case (from Reporting User in Jira)
	 * @param accountId Salesforce Id of the Account associated with the Contact
	 * @param summary Jira Summary of the Issue
	 * @param description Jira Description of the Issue
	 * @param binding Salesforce Soap Binding from login() function
	 * @throws Exception
	 */
	public static void updateCase(String caseId, String contactId, String accountId, String summary, String description, SoapBindingStub binding) throws Exception
	{
		QueryResult qr = runQuery("select Id, ContactId from Case where id = '" + caseId + "'", binding);
		if(qr.getSize() == 1)
		{
			_case c = (_case)qr.getRecords(0);
			c.setContactId(contactId);
			c.setAccountId(accountId);
			c.setSubject(summary);
			c.setDescription(description);
			_case[] ca = {c};//wrap object in an array for the update method
			try{
				binding.update(ca);
				System.out.println("Case Contact Updated");
			}catch(Exception ex)
			{
				System.out.println("Error Updating Case Contact:\n " + ex.getLocalizedMessage());
				throw ex;
			}
		}
	}
	
	/**	 * 
	 * @param caseId Salesforce Id of Case Object
	 * @param binding Salesforce SOAP Binding from login() method
	 * @return Id of Contact associated with given case
	 * @throws Exception
	 */
	public static String getContactIdByCase(String caseId, SoapBindingStub binding) throws Exception
	{
		QueryResult qr = runQuery("select Id, ContactId from Case where id = '" + caseId + "'", binding);
		if(qr.getSize() == 1)
		{
			_case c = (_case)qr.getRecords(0);			
			return c.getContactId();
		}
		return "";//if query fails
	}
	
	/**
	 * @param contactId Salesforce Id of a Contact
	 * @param binding Salesforce SOAP Binding from login() method
	 * @return Email address of given contact
	 * @throws Exception
	 */
	public static String getContactEmailById(String contactId, SoapBindingStub binding) throws Exception
	{
		QueryResult qr = runQuery("Select Email from Contact where id = '" + contactId + "'", binding);
		if(qr.getSize() == 1)
		{
			Contact con = (Contact)qr.getRecords(0);
			return con.getEmail();
		}
		return "";//if query fails
	}
	
	/**
	 * Executes a SOQL query
	 * @param query query to execute
	 * @param binding Salesforce SOAP Binding from login() method
	 * @return Results of the Query
	 * @throws Exception
	 */
	public static QueryResult runQuery(String query, SoapBindingStub binding) throws Exception
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
			throw ex;
		}catch(Exception ex)
		{
			System.out.println("\nFailed to execute query succesfully, error message was:\n" + ex.getMessage()+ "\n Query: " + query);
			throw ex;
		}
		return qr;
	}
	
	/**
	 * Gets information about a Salesforce user from their email address
	 * @param email Email address of User
	 * @param binding Salesforce SOAP Binding from login() method
	 * @return [0] - User Id, [1] - User's full name
	 * @throws Exception
	 */
	public static String[] getUserInfoByEmail(String email, SoapBindingStub binding) throws Exception
	{
		QueryResult qr = runQuery("select Id, Name from User where email = '" + email + "'", binding);
		if(qr.getSize() >= 1)// there is an off chance that the email address will be associated with multiple users, we figured it would be better for it to assign the case to the wrong user, than do nothing at all for no apparent reason
		{
			com.sforce.soap.enterprise.sobject.User u = (com.sforce.soap.enterprise.sobject.User)qr.getRecords(0);
			return new String[] {u.getId(), u.getName()};
		}
		return null;//if query fails
	}
	
	/**
	 * Gets information about a Salesforce Contact from their email address and the associated Account
	 * @param email Email address of the Salesforce Contact
	 * @param binding Salesforce SOAP Binding from login() method
	 * @return [0] - Contact Id, [1] - Account Id, [2] - Contact's Full Name, [3] - Contact's Phone Number, [4] - Contact's Email address
	 * @throws Exception
	 */
	public static String[] getContactInfoByEmail(String email, SoapBindingStub binding) throws Exception
	{
		//return an array 0. Contact ID 1. Account Id 2. Name 3.Phone 4.Email 
		String[] ret = new String[5];
		QueryResult qr = runQuery("select Id, AccountId, Name, Phone, Email from Contact where email = '" + email + "'", binding);
		if(qr.getSize() >= 1)// there is an off chance that the email address will be associated with multiple users, we figured it would be better for it to assign the case to the wrong user, than do nothing at all for no apparent reason
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
	
	/**
	 * Gets information about a Salesforce account by it's id
	 * @param id Salesforce id of the Account to retrieve information from
	 * @param binding Salesforce SOAP Binding from login() method
	 * @return [0] - Account Name, [1] - Account Owner Id
	 * @throws Exception 
	 */
	public static String[] getAccountInfoById(String id, SoapBindingStub binding) throws Exception
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
	
	/**
	 * Get the Name of a Salesforce User by the User Id
	 * @param id Salesforce Id of the User
	 * @param binding Salesforce SOAP Binding from login() method
	 * @return Users full name
	 * @throws Exception
	 */
	public static String getUserNameById(String id, SoapBindingStub binding) throws Exception
	{
		QueryResult qr = runQuery("select Name from User where id = '" + id + "'", binding);
		if(qr.getSize() == 1)
		{
			com.sforce.soap.enterprise.sobject.User u = (com.sforce.soap.enterprise.sobject.User)qr.getRecords(0);
			return u.getName();			
		}
		return null;
	}
	
	/**
	 * Searches for a String entry in a String[] array
	 * @param haystack Array to search for the String in
	 * @param needle String to search for
	 * @return true if the string is found
	 */
	public static boolean searchArrayForString(String[] haystack, String needle)
	{
		for(int i=haystack.length-1; i >= 0; i--)
		{
			if(haystack[i].equalsIgnoreCase(needle)){
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Sends a Jira email notification about a missing salesforce contact
	 * @param issue The Jira issue with the contact missing in Salesforce
	 */
	public void sendMissingContactNotification(Issue issue) 
	{	
		String baseUrl = ManagerFactory.getApplicationProperties().getString(APKeys.JIRA_BASEURL); //ie. http://support.hannonhill.com
		com.atlassian.mail.Email email = new com.atlassian.mail.Email (this._emails);//send the email to the addresses in the "Notification Emails" parameter
		email.setSubject("Missing Jira Contact in Salesforce: " + issue.getReporter().getFullName());
		email.setBody("Please create a corresponding contact in Salesforce for the appropriate account with the name " + issue.getReporter().getFullName() + "and the email address " + issue.getReporter().getEmail()+" then update the issue " + baseUrl + "/browse/" +issue.getKey());		
		try{
			sendEmail(email);
		}catch(MailException nex){
			System.out.println(nex);
		}
	}
	
	/**
	 * Sends a Jira email notification about any exceptions this extension throws that would cause the case to not be synced
	 * @param issue Jira issue that could not be synced
	 * @param ex Exception that was thrown
	 */
	public void sendFailedSyncNotification(Issue issue, Exception ex)
	{
		String baseUrl = ManagerFactory.getApplicationProperties().getString(APKeys.JIRA_BASEURL);
		com.atlassian.mail.Email email = new com.atlassian.mail.Email (this._emails);
		email.setSubject("Jira-Salesforce Sync Failed: " + issue.getKey());
		email.setBody(baseUrl + "/browse/" +issue.getKey() + " " + ex.toString());
		try{
			sendEmail(email);
		}catch(MailException nex){
			System.out.println(nex);
		}
	}
	
	/**
	 * Sends a Jira Email
	 * @param email Email message to send
	 * @throws MailException
	 */
	public void sendEmail(com.atlassian.mail.Email email)throws MailException
	{
		MailServerManager mailManager = MailFactory.getServerManager();
		SMTPMailServer mailServer = null;
		try{
			mailServer = mailManager.getDefaultSMTPMailServer();
			mailServer.send(email);	
		}catch(Exception ex)
		{
			System.out.println(ex);
		}		
	}
	
	/**
	 * Show in Jira that an issue is not synced with Salesforce
	 * @param issue Jira issue that is out of sync
	 */
	public void unsyncIssue(Issue issue)
	{
		CustomFieldManager customFieldManager = ComponentManager.getInstance().getCustomFieldManager();
		IssueChangeHolder changeHolder = new DefaultIssueChangeHolder();
		CustomField cfSync = customFieldManager.getCustomFieldObjectByName("Salesforce Synced");
		cfSync.updateValue(null, issue, new ModifiedValue(issue.getCustomFieldValue(cfSync), "Unsynced"), changeHolder);
	}
	
	/** Event fired when event is created
	 * @see com.atlassian.jira.event.issue.AbstractIssueEventListener#issueCreated(com.atlassian.jira.event.issue.IssueEvent)
	 */
	public void issueCreated(IssueEvent event)
	{
		Issue i = event.getIssue();//the issue that was created
		
		if(searchArrayForString(this._projects, i.getProjectObject().getKey()))//make sure that this issue is in a project that is set to be synced with Salesforce
		{
		
			//The custom field manager is used to create Ojbects to govern the cusom fields in Jira
			CustomFieldManager customFieldManager = ComponentManager.getInstance().getCustomFieldManager();
			
			//System.out.println("Starting to send case to Salesforce");
			SoapBindingStub binding = null;
			try
			{
				binding = login(this._uName, this._password + this._token);	
			}catch(Exception ex){
				sendFailedSyncNotification(i, ex);
				unsyncIssue(i);
				return;
			}
			
			//used to set values in custom Jira fields
			IssueChangeHolder changeHolder = new DefaultIssueChangeHolder();
			
			if(i.getReporter() != null && i.getAssignee() != null)
			{
				String conEmail = ((com.opensymphony.user.User)(i.getReporter())).getEmail();
				String uEmail = ((com.opensymphony.user.User)(i.getAssignee())).getEmail();
				
				try
				{
					String[] contactInfo = getContactInfoByEmail(conEmail, binding);
					String[] uInfo = getUserInfoByEmail(uEmail, binding);
					if(uInfo == null || contactInfo == null)
					{
						//if the contact or user is missing in Salesforce
						System.out.println("Id's returned as Blank " + conEmail + " " + uEmail);
						System.out.println("Id's returned as Blank");
						sendMissingContactNotification(i);									
						return;					
					}		
					String caseId = createCase(uInfo[0], contactInfo[0], contactInfo[1], i.getIssueType().get("name").toString(), i.getSummary(), i.getDescription(), i.getKey(), binding );
					
					String[] accountInfo = getAccountInfoById(contactInfo[1], binding);
					String ownerName = getUserNameById(accountInfo[1], binding);
				
				
					//create the custom field objects 
					CustomField cfAccountName = customFieldManager.getCustomFieldObjectByName("Salesforce Account");
					CustomField cfAccountUrl = customFieldManager.getCustomFieldObjectByName("Salesforce Address");
					CustomField cfAccountOwner = customFieldManager.getCustomFieldObjectByName("Salesforce Account Owner");
					CustomField cfContactName = customFieldManager.getCustomFieldObjectByName("Salesforce Contact Name");
					CustomField cfContactEmail = customFieldManager.getCustomFieldObjectByName("Salesforce Contact Email");
					CustomField cfContactPhone = customFieldManager.getCustomFieldObjectByName("Salesforce Contact Phone");
					CustomField cfCaseId = customFieldManager.getCustomFieldObjectByName("Salesforce Case Id");
					CustomField cfSync = customFieldManager.getCustomFieldObjectByName("Salesforce Synced");
					
					//set the values of the custom fields, see http://confluence.atlassian.com/pages/viewpage.action?pageId=160835
					cfAccountName.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfAccountName), accountInfo[0]), changeHolder);
					cfAccountUrl.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfAccountUrl), "https://na2.salesforce.com/"+ contactInfo[1]), changeHolder);
					cfAccountOwner.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfAccountOwner), ownerName), changeHolder);
					cfContactName.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfContactName), contactInfo[2]), changeHolder);
					cfContactEmail.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfContactEmail), contactInfo[4]), changeHolder);
					cfContactPhone.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfContactPhone), contactInfo[3]), changeHolder);
					cfCaseId.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfCaseId), caseId), changeHolder);
					cfSync.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfSync), "Synced"), changeHolder);
				}catch(Exception ex)
				{
					sendFailedSyncNotification(i, ex);
					unsyncIssue(i);
					return;
				}
			}
		}
	}
	
	/** Event fired when Issue is updated
	 * @see com.atlassian.jira.event.issue.AbstractIssueEventListener#issueUpdated(com.atlassian.jira.event.issue.IssueEvent)
	 */
	public void issueUpdated(IssueEvent event)
	{
		Issue i = event.getIssue();
		
		if(searchArrayForString(this._projects, i.getProjectObject().getKey()))
		{
		
			CustomFieldManager customFieldManager = ComponentManager.getInstance().getCustomFieldManager();
			CustomField cfCaseId = customFieldManager.getCustomFieldObjectByName("Salesforce Case Id");
			
			
			if(i.getCustomFieldValue(cfCaseId) == null || i.getCustomFieldValue(cfCaseId) == "")//if a case hasn't been created yet for this issue, create a new case
			{
				System.out.println("Starting to send case to Salesforce");
				SoapBindingStub binding = null;
				try{
					binding = login(this._uName, this._password + this._token);
				}catch(Exception ex)
				{
					sendFailedSyncNotification(i, ex);
					unsyncIssue(i);
				}
							
				CustomField cfAccountName = customFieldManager.getCustomFieldObjectByName("Salesforce Account");
				CustomField cfAccountUrl = customFieldManager.getCustomFieldObjectByName("Salesforce Address");
				CustomField cfAccountOwner = customFieldManager.getCustomFieldObjectByName("Salesforce Account Owner");
				CustomField cfContactName = customFieldManager.getCustomFieldObjectByName("Salesforce Contact Name");
				CustomField cfContactEmail = customFieldManager.getCustomFieldObjectByName("Salesforce Contact Email");
				CustomField cfContactPhone = customFieldManager.getCustomFieldObjectByName("Salesforce Contact Phone");
				CustomField cfSync = customFieldManager.getCustomFieldObjectByName("Salesforce Synced");
				
				IssueChangeHolder changeHolder = new DefaultIssueChangeHolder();
				
				if(i.getReporter() != null && i.getAssignee() != null)
				{
				
					String conEmail = ((com.opensymphony.user.User)(i.getReporter())).getEmail();
					String uEmail = ((com.opensymphony.user.User)(i.getAssignee())).getEmail();
					
					try
					{
						String[] contactInfo = getContactInfoByEmail(conEmail, binding);
						String[] uInfo = getUserInfoByEmail(uEmail, binding);
						if(uInfo == null || contactInfo == null)
						{
							System.out.println("Id's returned as Blank");
							sendMissingContactNotification(i);
							return;
							
						}		
						String caseId = createCase(uInfo[0], contactInfo[0], contactInfo[1], i.getIssueType().get("name").toString(), i.getSummary(), i.getDescription(), i.getKey(), binding );				
						String[] accountInfo = getAccountInfoById(contactInfo[1], binding);
						String ownerName = getUserNameById(accountInfo[1], binding);
					
					
						cfAccountName.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfAccountName), accountInfo[0]), changeHolder);
						cfAccountUrl.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfAccountUrl), "https://na2.salesforce.com/"+ contactInfo[1]), changeHolder);	
						cfAccountOwner.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfAccountOwner), ownerName), changeHolder);
						cfContactName.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfContactName), contactInfo[2]), changeHolder);
						cfContactEmail.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfContactEmail), contactInfo[4]), changeHolder);
						cfContactPhone.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfContactPhone), contactInfo[3]), changeHolder);
						cfCaseId.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfCaseId), caseId), changeHolder);
						cfSync.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfSync), "Synced"), changeHolder);
					}catch(Exception ex){
						sendFailedSyncNotification(i, ex);
						unsyncIssue(i);
					}
				}
			}else{//if a case has already been created, update the information in salesforce
				System.out.println("Starting to send case to Salesforce");
				SoapBindingStub binding = null;
				try
				{
					binding = login(this._uName, this._password + this._token);
				}catch(Exception ex){
					sendFailedSyncNotification(i, ex);
					unsyncIssue(i);
					return;
				}
				
				try{
					String conEmail = ((com.opensymphony.user.User)(i.getReporter())).getEmail();
					//System.out.println(conEmail + ", " + getContactEmailById(getContactIdByCase((String)i.getCustomFieldValue(cfCaseId), binding),binding));
										
					String[] contactInfo = getContactInfoByEmail(conEmail, binding);
					updateCase((String)i.getCustomFieldValue(cfCaseId), contactInfo[0], contactInfo[1], i.getSummary(), i.getDescription(), binding);
					String[] accountInfo = getAccountInfoById(contactInfo[1], binding);
					
					CustomField cfContactName = customFieldManager.getCustomFieldObjectByName("Salesforce Contact Name");
					CustomField cfContactEmail = customFieldManager.getCustomFieldObjectByName("Salesforce Contact Email");
					CustomField cfContactPhone = customFieldManager.getCustomFieldObjectByName("Salesforce Contact Phone");
					CustomField cfAccountName = customFieldManager.getCustomFieldObjectByName("Salesforce Account");
					CustomField cfAccountUrl = customFieldManager.getCustomFieldObjectByName("Salesforce Address");
					CustomField cfAccountOwner = customFieldManager.getCustomFieldObjectByName("Salesforce Account Owner");
					CustomField cfSync = customFieldManager.getCustomFieldObjectByName("Salesforce Synced");
					
					IssueChangeHolder changeHolder = new DefaultIssueChangeHolder();
					
					cfContactName.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfContactName), contactInfo[2]), changeHolder);
					cfContactEmail.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfContactEmail), contactInfo[4]), changeHolder);
					cfContactPhone.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfContactPhone), contactInfo[3]), changeHolder);
					cfAccountName.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfAccountName), accountInfo[0]), changeHolder);
					cfAccountUrl.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfAccountUrl), "https://na2.salesforce.com/"+ contactInfo[1]), changeHolder);
					String ownerName = getUserNameById(accountInfo[1], binding);
					cfAccountOwner.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfAccountOwner), ownerName), changeHolder);
					cfSync.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfSync), "Synced"), changeHolder);
					
				}catch(Exception ex){
					sendFailedSyncNotification(i, ex);
					unsyncIssue(i);
				}
			}
		}
	}
	
	/**
	 * Event fired when an Issue is closed in Jira, closes the even in Salesforce
	 * @see com.atlassian.jira.event.issue.AbstractIssueEventListener#issueClosed(com.atlassian.jira.event.issue.IssueEvent)
	 */
	public void issueClosed(IssueEvent event)
	{
		Issue i = event.getIssue();
		if(searchArrayForString(this._projects, i.getProjectObject().getKey()))
		{
			CustomFieldManager customFieldManager = ComponentManager.getInstance().getCustomFieldManager();
			CustomField cfCaseId = customFieldManager.getCustomFieldObjectByName("Salesforce Case Id");
			CustomField cfSync = customFieldManager.getCustomFieldObjectByName("Salesforce Synced");
			IssueChangeHolder changeHolder = new DefaultIssueChangeHolder();
			String caseId = (String)i.getCustomFieldValue(cfCaseId);
			if(caseId != null && caseId != "")
			{
				System.out.println("Starting to send case to Salesforce");
				try{
					SoapBindingStub binding = login(this._uName, this._password + this._token);
					closeCase(caseId, binding);
					cfSync.updateValue(null, i, new ModifiedValue(i.getCustomFieldValue(cfSync), "Synced"), changeHolder);
					
				}catch(Exception ex){
					sendFailedSyncNotification(i, ex);
					unsyncIssue(i);
				}	
				
			}
		}
	}
	
	/**
	 * Event fired in Jira when an issue is resolved, calls issueClosed
	 * @see com.atlassian.jira.event.issue.AbstractIssueEventListener#issueResolved(com.atlassian.jira.event.issue.IssueEvent)
	 */
	public void issueResolved(IssueEvent event)
	{
		issueClosed(event);
	}
	
	/**
	 * Event fired when an issue is assigned in Jira, calls issueUpdated
	 * @see com.atlassian.jira.event.issue.AbstractIssueEventListener#issueAssigned(com.atlassian.jira.event.issue.IssueEvent)
	 */
	public void issueAssigned(IssueEvent event)
	{
		issueUpdated(event);
	}
	
	public void issueStarted(IssueEvent event)
	{
		issueUpdated(event);
	}
}
