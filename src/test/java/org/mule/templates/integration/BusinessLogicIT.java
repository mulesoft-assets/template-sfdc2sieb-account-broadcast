/**
 * Mule Anypoint Template
 * Copyright (c) MuleSoft, Inc.
 * All rights reserved.  http://www.mulesoft.com
 */

package org.mule.templates.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mule.MessageExchangePattern;
import org.mule.api.MuleEvent;
import org.mule.modules.salesforce.bulk.EnrichedUpsertResult;
import org.mule.processor.chain.SubflowInterceptingChainLifecycleWrapper;
import org.mule.templates.builders.SfdcObjectBuilder;

import com.mulesoft.module.batch.BatchTestHelper;

/**
 * The objective of this class is to validate the correct behavior of the flows
 * for this Anypoint Template that make calls to external systems.
 * 
 */
public class BusinessLogicIT extends AbstractTemplateTestCase {

	protected static final int TIMEOUT = 60;
	private static final Logger log = LogManager.getLogger(BusinessLogicIT.class);
	private static final String POLL_FLOW_NAME = "triggerFlow";
	private static final String ACCOUNT_NAME = "Account Broadcast Siebel Test Name";
	
	private BatchTestHelper helper;
	private Map<String, Object> account;
	private SubflowInterceptingChainLifecycleWrapper selectAccountFromSiebelFlow;
	private SubflowInterceptingChainLifecycleWrapper deleteAccountFromSalesforceFlow;
	private SubflowInterceptingChainLifecycleWrapper deleteAccountsFromSiebelFlow;
	
	@BeforeClass
	public static void init() {
		System.setProperty("page.size", "1000");
		System.setProperty("poll.frequencyMillis", "45000");
		System.setProperty("poll.startDelayMillis", "100");
		System.setProperty("watermark.default.expression",
				"#[groovy: new Date(System.currentTimeMillis() - 30000).format(\"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'\", TimeZone.getTimeZone('BDST'))]");
	}

	@Before
	public void setUp() throws Exception {
		stopFlowSchedulers(POLL_FLOW_NAME);
		registerListeners();
		helper = new BatchTestHelper(muleContext);

		selectAccountFromSiebelFlow = getSubFlow("selectAccountFromSiebelFlow");
		selectAccountFromSiebelFlow.initialise();
		
		deleteAccountFromSalesforceFlow = getSubFlow("deleteAccountFromSalesforceFlow");
		deleteAccountFromSalesforceFlow.initialise();

		deleteAccountsFromSiebelFlow = getSubFlow("deleteAccountsFromSiebelFlow");
		deleteAccountsFromSiebelFlow.initialise();

		// prepare test data
		account = createSalesforceAccount();
		insertSalesforceAccount(account);
	}

	@After
	public void tearDown() throws Exception {
		stopFlowSchedulers(POLL_FLOW_NAME);

		// delete previously created account from Siebel and Salesforce by matching ID
		deleteAccountFromSalesforce(account);
		deleteAccountsFromSiebel(account);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testMainFlow() throws Exception {
		// Run poll and wait for it to run
		runSchedulersOnce(POLL_FLOW_NAME);
		waitForPollToRun();

		// Wait for the batch job executed by the poll flow to finish
		helper.awaitJobTermination(TIMEOUT * 1000, 500);
		helper.assertJobWasSuccessful();

		// Execute selectAccountFromSiebel subflow
		final MuleEvent event = selectAccountFromSiebelFlow.process(getTestEvent(account, MessageExchangePattern.REQUEST_RESPONSE));
		final List<Map<String, Object>> payload = (List<Map<String, Object>>) event.getMessage().getPayload();

		// fill SiebelId
		for (Map<String, Object> acc : payload){
			account.put("SiebelId", acc.get("Id"));
		}

		// Account previously created in Salesforce should be present in Siebel
		Assert.assertEquals("The account should have been sync", 1, payload.size());
		Assert.assertEquals("The account name should match", account.get("Name"), payload.get(0).get("Name"));
	}

	@SuppressWarnings("unchecked")
	private void insertSalesforceAccount(final Map<String, Object> account) throws Exception {
		final SubflowInterceptingChainLifecycleWrapper flow = getSubFlow("insertSalesforceAccountSubFlow");
		flow.initialise();

		final MuleEvent event = flow.process(getTestEvent(account, MessageExchangePattern.REQUEST_RESPONSE));
		final List<EnrichedUpsertResult> result = (List<EnrichedUpsertResult>) event.getMessage().getPayload();

		// store Id into our account
		for (EnrichedUpsertResult item : result) {
			log.info("response from insertSalesforceAccountSubFlow: " + item);
			account.put("Id", item.getId());
			account.put("LastModifiedDate", item.getPayload().getField("LastModifiedDate"));
		}
	}

	private void deleteAccountFromSalesforce(final Map<String, Object> acc) throws Exception {

		List<Object> idList = new ArrayList<Object>();
		idList.add(acc.get("Id"));

		deleteAccountFromSalesforceFlow.process(getTestEvent(idList, MessageExchangePattern.REQUEST_RESPONSE));
	}

	private void deleteAccountsFromSiebel(final Map<String, Object> acc) throws Exception {

		List<String> idList = new ArrayList<String>();
		idList.add(acc.get("SiebelId").toString());
		
		deleteAccountsFromSiebelFlow.process(getTestEvent(idList, MessageExchangePattern.REQUEST_RESPONSE));
	}

	private Map<String, Object> createSalesforceAccount() {
		final SfdcObjectBuilder builder = new SfdcObjectBuilder();
		final Map<String, Object> account = builder
				.with("Name", ACCOUNT_NAME + System.currentTimeMillis()).build();
		
		return account;
	}
}
