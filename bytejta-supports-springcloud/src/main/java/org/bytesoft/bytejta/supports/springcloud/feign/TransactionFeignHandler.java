/**
 * Copyright 2014-2017 yangming.liu<bytefox@126.com>.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 */
package org.bytesoft.bytejta.supports.springcloud.feign;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bytesoft.bytejta.TransactionImpl;
import org.bytesoft.bytejta.supports.rpc.TransactionRequestImpl;
import org.bytesoft.bytejta.supports.springcloud.SpringCloudBeanRegistry;
import org.bytesoft.bytejta.supports.springcloud.loadbalancer.TransactionLoadBalancerInterceptor;
import org.bytesoft.bytejta.supports.springcloud.rpc.TransactionResponseImpl;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.transaction.TransactionBeanFactory;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.loadbalancer.Server;
import com.netflix.loadbalancer.Server.MetaInfo;
import com.netflix.niws.loadbalancer.DiscoveryEnabledServer;

public class TransactionFeignHandler implements InvocationHandler {
	static final Logger logger = LoggerFactory.getLogger(TransactionFeignHandler.class);

	private InvocationHandler delegate;

	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (Object.class.equals(method.getDeclaringClass())) {
			return method.invoke(this, args);
		} else {
			final SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
			TransactionBeanFactory beanFactory = beanRegistry.getBeanFactory();
			TransactionManager transactionManager = beanFactory.getTransactionManager();
			final TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();

			TransactionImpl transaction = //
					(TransactionImpl) transactionManager.getTransactionQuietly();
			if (transaction == null) {
				return this.delegate.invoke(proxy, method, args);
			}

			final TransactionContext transactionContext = transaction.getTransactionContext();

			final TransactionRequestImpl request = new TransactionRequestImpl();
			final TransactionResponseImpl response = new TransactionResponseImpl();

			final Map<String, XAResourceArchive> participants = transaction.getApplicationMap(); // .getParticipantMap();
			beanRegistry.setLoadBalancerInterceptor(new TransactionLoadBalancerInterceptor() {
				public List<Server> beforeCompletion(List<Server> servers) {
					final List<Server> readyServerList = new ArrayList<Server>();
					final List<Server> unReadyServerList = new ArrayList<Server>();

					for (int i = 0; servers != null && i < servers.size(); i++) {
						Server server = servers.get(i);

						// String instanceId = metaInfo.getInstanceId();
						String instanceId = null;
						if (DiscoveryEnabledServer.class.isInstance(server)) {
							DiscoveryEnabledServer discoveryEnabledServer = (DiscoveryEnabledServer) server;
							InstanceInfo instanceInfo = discoveryEnabledServer.getInstanceInfo();
							String addr = instanceInfo.getIPAddr();
							String appName = instanceInfo.getAppName();
							int port = instanceInfo.getPort();

							instanceId = String.format("%s:%s:%s", addr, appName, port);
						} else {
							MetaInfo metaInfo = server.getMetaInfo();

							String host = server.getHost();
							String addr = host.matches("\\d+(\\.\\d+){3}") ? host : CommonUtils.getInetAddress(host);
							String appName = metaInfo.getAppName();
							int port = server.getPort();
							instanceId = String.format("%s:%s:%s", addr, appName, port);
						}

						if (participants.containsKey(instanceId)) {
							List<Server> serverList = new ArrayList<Server>();
							serverList.add(server);
							return serverList;
						} // end-if (participants.containsKey(instanceId))

						if (server.isReadyToServe()) {
							readyServerList.add(server);
						} else {
							unReadyServerList.add(server);
						}

					}

					logger.warn("There is no suitable server: expect= {}, actual= {}!", participants.keySet(), servers);
					return readyServerList.isEmpty() ? unReadyServerList : readyServerList;
				}

				public void afterCompletion(Server server) {
					beanRegistry.removeLoadBalancerInterceptor();

					if (server == null) {
						logger.warn(
								"There is no suitable server, the TransactionInterceptor.beforeSendRequest() operation is not executed!");
						return;
					} // end-if (server == null)

					// TransactionRequestImpl request = new TransactionRequestImpl();
					request.setTransactionContext(transactionContext);

					// String instanceId = metaInfo.getInstanceId();
					String instanceId = null;

					if (DiscoveryEnabledServer.class.isInstance(server)) {
						DiscoveryEnabledServer discoveryEnabledServer = (DiscoveryEnabledServer) server;
						InstanceInfo instanceInfo = discoveryEnabledServer.getInstanceInfo();
						String addr = instanceInfo.getIPAddr();
						String appName = instanceInfo.getAppName();
						int port = instanceInfo.getPort();

						instanceId = String.format("%s:%s:%s", addr, appName, port);
					} else {
						MetaInfo metaInfo = server.getMetaInfo();

						String host = server.getHost();
						String addr = host.matches("\\d+(\\.\\d+){3}") ? host : CommonUtils.getInetAddress(host);
						String appName = metaInfo.getAppName();
						int port = server.getPort();
						instanceId = String.format("%s:%s:%s", addr, appName, port);
					}

					RemoteCoordinator coordinator = beanRegistry.getConsumeCoordinator(instanceId);
					request.setTargetTransactionCoordinator(coordinator);

					transactionInterceptor.beforeSendRequest(request);
				}
			});

			try {
				return this.delegate.invoke(proxy, method, args);
			} finally {
				if (response.isIntercepted() == false) {
					response.setTransactionContext(transactionContext);

					RemoteCoordinator coordinator = request.getTargetTransactionCoordinator();
					response.setSourceTransactionCoordinator(coordinator);
					response.setParticipantEnlistFlag(request.isParticipantEnlistFlag());

					transactionInterceptor.afterReceiveResponse(response);
				} // end-if (response.isIntercepted() == false)
			}

		}
	}

	public InvocationHandler getDelegate() {
		return delegate;
	}

	public void setDelegate(InvocationHandler delegate) {
		this.delegate = delegate;
	}

}
