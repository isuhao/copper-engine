/*
 * Copyright 2002-2012 SCOOP Software GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.scoopgmbh.copper.audit;

import java.sql.Connection;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import de.scoopgmbh.copper.spring.SpringTransaction;


public class SpringTxnAuditTrail extends BatchingAuditTrail {
	
	private PlatformTransactionManager transactionManager;
	
	public void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionManager = transactionManager;
	}

	@Override
	public void synchLog(final AuditTrailEvent e) {
		new SpringTransaction() {
			@Override
			protected void execute(Connection con) throws Exception {
				doSyncLog(e, con);
			}
		}.run(transactionManager, getDataSource(), createTransactionDefinition());
	}

	protected TransactionDefinition createTransactionDefinition() {
		return new DefaultTransactionDefinition();
	}
	

}
