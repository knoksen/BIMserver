package org.bimserver.longaction;

/******************************************************************************
 * Copyright (C) 2009-2019  BIMserver.org
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see {@literal<http://www.gnu.org/licenses/>}.
 *****************************************************************************/

import javax.activation.DataHandler;

import org.bimserver.BimServer;
import org.bimserver.BimserverDatabaseException;
import org.bimserver.database.DatabaseSession;
import org.bimserver.database.OldQuery;
import org.bimserver.database.OperationType;
import org.bimserver.database.actions.BimDatabaseAction;
import org.bimserver.emf.IfcModelInterface;
import org.bimserver.interfaces.objects.SCheckoutResult;
import org.bimserver.models.log.AccessMethod;
import org.bimserver.models.store.*;
import org.bimserver.plugins.Reporter;
import org.bimserver.plugins.renderengine.RenderEnginePlugin;
import org.bimserver.plugins.serializers.CacheStoringEmfSerializerDataSource;
import org.bimserver.plugins.serializers.EmfSerializerDataSource;
import org.bimserver.plugins.serializers.MessagingSerializer;
import org.bimserver.plugins.serializers.Serializer;
import org.bimserver.plugins.serializers.SerializerException;
import org.bimserver.shared.exceptions.ServerException;
import org.bimserver.shared.exceptions.UserException;
import org.bimserver.webservices.authorization.Authorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class LongDownloadOrCheckoutAction extends LongAction implements Reporter {
	protected static final Logger LOGGER = LoggerFactory.getLogger(LongDownloadAction.class);
	protected final AccessMethod accessMethod;
	protected final DownloadParameters downloadParameters;
	protected SCheckoutResult checkoutResult;
	protected MessagingSerializer messagingSerializer;

	protected LongDownloadOrCheckoutAction(BimServer bimServer, String username, String userUsername, DownloadParameters downloadParameters, AccessMethod accessMethod,
			Authorization authorization) {
		super(bimServer, username, userUsername, authorization);
		this.accessMethod = accessMethod;
		this.downloadParameters = downloadParameters;
	}

	public SCheckoutResult getCheckoutResult() {
		return checkoutResult;
	}

	protected SCheckoutResult convertModelToCheckoutResult(Revision revision, String username, IfcModelInterface model, RenderEnginePlugin renderEnginePlugin, DownloadParameters downloadParameters)
			throws UserException {
		SCheckoutResult checkoutResult = new SCheckoutResult();
		checkoutResult.setSerializerOid(downloadParameters.getSerializerOid());
		if (model.isValid()) {
			checkoutResult.setProjectName(revision.getProject().getName());
			checkoutResult.setRevisionNr(model.getModelMetaData().getRevisionId());
			try {
				Serializer serializer = getBimServer().getSerializerFactory().create(revision, username, model, renderEnginePlugin, downloadParameters);
				if (serializer == null) {
					throw new UserException("Error, serializer " + downloadParameters.getSerializerOid() + " not found or failed to initialize.");
				}
				if (getBimServer().getServerSettingsCache().getServerSettings().getCacheOutputFiles() && serializer.allowCaching()) {
					if (getBimServer().getDiskCacheManager().contains(downloadParameters)) {
						checkoutResult.setFile(new CachingDataHandler(getBimServer().getDiskCacheManager(), downloadParameters, () -> changeActionState(ActionState.FINISHED, "Done", 100)));
					} else {
						checkoutResult.setFile(new DataHandler(new CacheStoringEmfSerializerDataSource(serializer, model.getModelMetaData().getName(), () -> changeActionState(ActionState.FINISHED, "Done", 100), getBimServer().getDiskCacheManager().startCaching(downloadParameters))));
					}
				} else {
					checkoutResult.setFile(new DataHandler(new EmfSerializerDataSource(serializer, model.getModelMetaData().getName(), () -> changeActionState(ActionState.FINISHED, "Done", 100))));
				}
			} catch (SerializerException e) {
				LOGGER.error("", e);
			}
		}
		return checkoutResult;
	}
	
	public MessagingSerializer getMessagingSerializer() {
		return messagingSerializer;
	}

	protected void executeAction(BimDatabaseAction<? extends IfcModelInterface> action, DownloadParameters downloadParameters, DatabaseSession session, boolean commit)
			throws BimserverDatabaseException, UserException, ServerException {
		try {
			if (action == null) {
				checkoutResult = new SCheckoutResult();
				checkoutResult.setFile(new CachingDataHandler(getBimServer().getDiskCacheManager(), downloadParameters, () -> changeActionState(ActionState.FINISHED, "Done", 100)));
				checkoutResult.setSerializerOid(downloadParameters.getSerializerOid());
			} else {
				Revision revision = session.get(StorePackage.eINSTANCE.getRevision(), downloadParameters.getRoid(), OldQuery.getDefault());
				if (revision == null) {
					throw new UserException("Revision with roid " + downloadParameters.getRoid() + " not found");
				}
				revision.getProject().getGeoTag().load(); // Little hack to make
															// sure this is
															// lazily loaded,
															// because after the
															// executeAndCommitAction
															// the session won't
															// be usable
				IfcModelInterface ifcModel = session.executeAndCommitAction(action);
				// Session is closed after this

				DatabaseSession newSession = getBimServer().getDatabase().createSession(OperationType.READ_ONLY);
				RenderEnginePlugin renderEnginePlugin = null;
				try {
					PluginConfiguration serializerPluginConfiguration = newSession.get(StorePackage.eINSTANCE.getPluginConfiguration(), downloadParameters.getSerializerOid(), OldQuery.getDefault());
					if (serializerPluginConfiguration != null) {
						if (serializerPluginConfiguration instanceof MessagingSerializerPluginConfiguration) {
							try {
								messagingSerializer = getBimServer().getSerializerFactory().createMessagingSerializer(getUserName(), ifcModel, downloadParameters);
								checkoutResult = new SCheckoutResult();
								checkoutResult.setSerializerOid(downloadParameters.getSerializerOid());
								checkoutResult.setFile(new DataHandler(new MessagingStreamingDataSource(messagingSerializer)));
							} catch (SerializerException e) {
								e.printStackTrace();
							}
						} else if (serializerPluginConfiguration instanceof SerializerPluginConfiguration) {
							RenderEnginePluginConfiguration renderEngine = ((SerializerPluginConfiguration)serializerPluginConfiguration).getRenderEngine();
							if (renderEngine != null) {
								renderEnginePlugin = getBimServer().getPluginManager().getRenderEnginePlugin(renderEngine.getPluginDescriptor().getPluginClassName(), true);
							}
							checkoutResult = convertModelToCheckoutResult(revision, getUserName(), ifcModel, renderEnginePlugin, downloadParameters);
						}
					}
				} catch (BimserverDatabaseException e) {
					LOGGER.error("", e);
				} finally {
					newSession.close();
				}
			}
		} finally {
			done();
		}
	}

	public long getSerializerOid() {
		return downloadParameters.getSerializerOid();
	}
}