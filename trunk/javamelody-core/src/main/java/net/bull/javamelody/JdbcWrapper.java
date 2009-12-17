/*
 * Copyright 2008-2009 by Emeric Vernat, Bull
 *
 *     This file is part of Java Melody.
 *
 * Java Melody is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Java Melody is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Java Melody.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.bull.javamelody;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.Referenceable;
import javax.servlet.ServletContext;
import javax.sql.DataSource;

import org.apache.tomcat.dbcp.dbcp.BasicDataSource;

/**
 * Cette classe rebind dans l'annuaire JNDI la dataSource jdbc en la remplaçant
 * par un proxy de monitoring.
 * @author Emeric Vernat
 */
final class JdbcWrapper {
	// au lieu d'utiliser int avec des synchronized partout, on utilise AtomicInteger
	static final AtomicInteger ACTIVE_CONNECTION_COUNT = new AtomicInteger();
	static final AtomicInteger USED_CONNECTION_COUNT = new AtomicInteger();
	static final AtomicInteger ACTIVE_THREAD_COUNT = new AtomicInteger();

	// instance de JdbcWrapper (ici on ne connaît pas le ServletContext)
	static final JdbcWrapper SINGLETON = new JdbcWrapper(new Counter("sql", "db.png"), null);

	private static final Map<String, Map<String, Object>> TOMCAT_BASIC_DATASOURCES_PROPERTIES = new LinkedHashMap<String, Map<String, Object>>();

	// Cette variable sqlCounter conserve un état qui est global au filtre et à l'application (donc thread-safe).
	private final Counter sqlCounter;
	private ServletContext servletContext;
	private final Map<String, DataSource> dataSourcesBackup = new LinkedHashMap<String, DataSource>(
			2);
	private boolean jboss;
	private boolean glassfish;
	private boolean weblogic;

	/**
	 * Handler de proxy d'un statement jdbc.
	 */
	private class StatementInvocationHandler implements InvocationHandler {
		// Rq : dans les proxy de DataSource, Connection et Statement,
		// si la méthode appelée est java.sql.Wrapper.unwrap
		// on invoque toujours unwrap sur l'objet initial pour qu'il retourne lui-même
		// ou son objet wrappé. Par exemple, l'appel de unwrap sur le proxy d'un Statement
		// retournera le Statement initial du serveur ou même du driver bdd (OracleStatement...)
		// sans notre proxy pour pouvoir appeler les méthodes non standard du driver par ex.
		private String requestName;
		private final Statement statement;

		StatementInvocationHandler(String query, Statement statement) {
			super();
			assert statement != null;

			this.requestName = query;
			this.statement = statement;
		}

		/** {@inheritDoc} */
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			// performance : on évite method.invoke pour equals & hashCode
			final String methodName = method.getName();
			if (isEqualsMethod(methodName, args)) {
				return statement.equals(args[0]);
			} else if (isHashCodeMethod(methodName, args)) {
				return statement.hashCode();
			} else if (methodName.startsWith("execute")) {
				if (isFirstArgAString(args)) {
					// la méthode est du type executeQuery(String), executeUpdate(String),
					// executeUpdate(String, ...) ou execute(String sql),
					// alors la requête sql est le premier argument (et pas query)
					requestName = (String) args[0];
				}

				// si on n'a pas trouvé la requête, on prend "null"
				requestName = String.valueOf(requestName);

				return doExecute(requestName, statement, method, args);
			} else if ("addBatch".equals(methodName) && isFirstArgAString(args)) {
				// Bien que déconseillée la méthode est addBatch(String),
				// la requête sql est alors le premier argument
				// (elle sera utilisée lors de l'appel à executeBatch())

				// Rq : on ne conserve que la dernière requête de addBatch.
				// Rq : si addBatch(String) est appelée, puis que executeUpdate(String)
				// la requête du batch est correctement ignorée ci-dessus.
				// Rq : si connection.prepareStatement(String).addBatch(String) puis executeUpdate()
				// sont appelées (et pas executeBatch()) alors la requête conservée est
				// faussement celle du batch mais l'application cloche grave.
				requestName = (String) args[0];
			}

			// ce n'est pas une méthode executeXxx du Statement
			return method.invoke(statement, args);
		}

		private boolean isFirstArgAString(Object[] args) {
			return args != null && args.length > 0 && args[0] instanceof String;
		}
	}

	/**
	 * Handler de proxy d'une connexion jdbc.
	 */
	private class ConnectionInvocationHandler implements InvocationHandler {
		private final Connection connection;

		ConnectionInvocationHandler(Connection connection) {
			super();
			assert connection != null;
			this.connection = connection;
			USED_CONNECTION_COUNT.incrementAndGet();
		}

		/** {@inheritDoc} */
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			// performance : on évite method.invoke pour equals & hashCode
			final String methodName = method.getName();
			if (isEqualsMethod(methodName, args)) {
				return connection.equals(args[0]);
			} else if (isHashCodeMethod(methodName, args)) {
				return connection.hashCode();
			}
			Object result = method.invoke(connection, args);
			if (result instanceof Statement) {
				final String requestName;
				if ("prepareStatement".equals(methodName) || "prepareCall".equals(methodName)) {
					// la méthode est du type prepareStatement(String) ou prepareCall(String),
					// alors la requête sql est le premier argument
					requestName = (String) args[0];
				} else {
					requestName = null;
				}
				result = createStatementProxy(requestName, (Statement) result);
			} else if ("close".equals(method.getName())) {
				USED_CONNECTION_COUNT.decrementAndGet();
			}
			return result;
		}
	}

	// ce handler désencapsule les InvocationTargetException des 3 proxy
	private static class DelegatingInvocationHandler implements InvocationHandler, Serializable {
		// classe sérialisable pour MonitoringProxy
		private static final long serialVersionUID = 7515240588169084785L;
		@SuppressWarnings("all")
		private final InvocationHandler delegate;

		DelegatingInvocationHandler(InvocationHandler delegate) {
			super();
			this.delegate = delegate;
		}

		/** {@inheritDoc} */
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			try {
				return delegate.invoke(proxy, method, args);
			} catch (final InvocationTargetException e) {
				if (e.getTargetException() != null) {
					throw e.getTargetException();
				}
				throw e;
			}
		}
	}

	private JdbcWrapper(Counter sqlCounter, ServletContext servletContext) {
		super();
		assert sqlCounter != null;
		this.sqlCounter = sqlCounter;
		// servletContext est null pour un simple JdbcDriver
		this.servletContext = servletContext;
		if (servletContext != null) {
			final String serverInfo = servletContext.getServerInfo();
			jboss = serverInfo.contains("JBoss");
			glassfish = serverInfo.contains("GlassFish");
			weblogic = serverInfo.contains("WebLogic");
		}
	}

	void initServletContext(ServletContext context) {
		assert context != null;
		this.servletContext = context;
		final String serverInfo = servletContext.getServerInfo();
		jboss = serverInfo.contains("JBoss");
		glassfish = serverInfo.contains("GlassFish");
		weblogic = serverInfo.contains("WebLogic");
	}

	static int getUsedConnectionCount() {
		return USED_CONNECTION_COUNT.get();
	}

	static int getActiveConnectionCount() {
		return ACTIVE_CONNECTION_COUNT.get();
	}

	static int getActiveThreadCount() {
		return ACTIVE_THREAD_COUNT.get();
	}

	static int getMaxConnectionCount() {
		if (TOMCAT_BASIC_DATASOURCES_PROPERTIES.isEmpty()) {
			return -1;
		}
		int result = 0;
		for (final Map<String, Object> dataSourceProperties : TOMCAT_BASIC_DATASOURCES_PROPERTIES
				.values()) {
			final Integer maxActive = (Integer) dataSourceProperties.get("maxActive");
			if (maxActive == null) {
				return -1;
			}
			result += maxActive;
		}
		return result;
	}

	static Map<String, Map<String, Object>> getTomcatBasicDataSourceProperties() {
		final Map<String, Map<String, Object>> result = new LinkedHashMap<String, Map<String, Object>>();
		for (final Map.Entry<String, Map<String, Object>> entry : TOMCAT_BASIC_DATASOURCES_PROPERTIES
				.entrySet()) {
			result.put(entry.getKey(), Collections.unmodifiableMap(entry.getValue()));
		}
		return Collections.unmodifiableMap(result);
	}

	Counter getSqlCounter() {
		return sqlCounter;
	}

	Object doExecute(String requestName, Statement statement, Method method, Object[] args)
			throws IllegalAccessException, InvocationTargetException {
		assert requestName != null;
		assert statement != null;
		assert method != null;

		if (!sqlCounter.isDisplayed()) {
			return method.invoke(statement, args);
		}

		final long start = System.currentTimeMillis();
		boolean systemError = true;
		try {
			ACTIVE_CONNECTION_COUNT.incrementAndGet();

			// note perf: selon un paramètre current-sql(/requests)-disabled,
			// on pourrait ici ne pas binder un nouveau contexte à chaque requête sql
			sqlCounter.bindContext(requestName, requestName);

			final Object result = method.invoke(statement, args);
			systemError = false;
			return result;
		} catch (final InvocationTargetException e) {
			if (e.getCause() instanceof SQLException) {
				final int errorCode = ((SQLException) e.getCause()).getErrorCode();
				if (errorCode >= 20000 && errorCode < 30000) {
					// Dans Oracle par exemple, les erreurs 20000 à 30000 sont standardisées
					// comme étant des erreurs lancées par l'application dans des procédures stockées
					// pour être traitées comme des erreurs de saisies ou comme des règles de gestion.
					// Ce ne sont donc pas des erreurs systèmes.
					systemError = false;
				}
			}
			throw e;
		} finally {
			// Rq : on n'utilise pas la création du statement et l'appel à la méthode close du statement
			// comme début et fin d'une connexion active, car en fonction de l'application
			// la méthode close du statement peut ne jamais être appelée
			// (par exemple, seule la méthode close de la connection peut être appelée ce qui ferme aussi le statement)
			// Rq : pas de temps cpu pour les requêtes sql car c'est 0 ou quasiment 0
			ACTIVE_CONNECTION_COUNT.decrementAndGet();
			final long duration = Math.max(System.currentTimeMillis() - start, 0);
			sqlCounter.addRequest(requestName, duration, -1, systemError, -1);
		}
	}

	boolean rebindDataSources() {
		boolean ok;
		// on cherche une datasource avec InitialContext pour afficher nom et version bdd + nom et version driver jdbc
		// (le nom de la dataSource recherchée dans JNDI est du genre jdbc/Xxx qui est le nom standard d'une DataSource)
		try {
			final InitialContext initialContext = new InitialContext();
			for (final Map.Entry<String, DataSource> entry : JdbcWrapperHelper.getDataSources()
					.entrySet()) {
				final String jndiName = entry.getKey();
				final DataSource dataSource = entry.getValue();
				if (glassfish || jboss || weblogic) {
					rewrapDataSource(dataSource);
				} else if (!isProxyAlready(dataSource)) {
					// si dataSource est déjà un proxy, il ne faut pas faire un proxy d'un proxy ni un rebinding
					final DataSource dataSourceProxy = createDataSourceProxy(jndiName, dataSource);
					final Object securityToken = JdbcWrapperHelper.changeContextWritable(
							servletContext, null);
					initialContext.rebind(jndiName, dataSourceProxy);
					dataSourcesBackup.put(jndiName, dataSource);
					JdbcWrapperHelper.changeContextWritable(servletContext, securityToken);
				}
			}
			initialContext.close();
			ok = true;
		} catch (final Throwable t) { // NOPMD
			// ça n'a pas marché, tant pis
			ok = false;
		}
		return ok;
	}

	private void rewrapDataSource(DataSource dataSource) throws IllegalAccessException {
		final String dataSourceClassName = dataSource.getClass().getName();
		if (jboss
				&& "org.jboss.resource.adapter.jdbc.WrapperDataSource".equals(dataSourceClassName)
				|| glassfish && "com.sun.gjc.spi.jdbc40.DataSource40".equals(dataSourceClassName)) {
			// JBOSS: le rebind de la datasource dans le JNDI JBoss est possible mais ne
			// fonctionne pas (car tous les lookup renverraient alors une instance de
			// MarshalledValuePair ou une instance javax.naming.Reference selon comment cela
			// est fait), donc on modifie directement l'instance de WrapperDataSource déjà
			// présente dans le JNDI.
			// GLASSFISH: le contexte JNDI commençant par "java:" est en lecture seule
			// dans glassfish (comme dit dans la spec et comme implémenté dans
			// http://kickjava.com/src/com/sun/enterprise/naming/java/javaURLContext.java.htm),
			// donc on modifie directement l'instance de DataSource40 déjà présente dans le
			// JNDI.
			// Par "chance", la classe org.jboss.resource.adapter.jdbc.WrapperDataSource et
			// la super-classe de com.sun.gjc.spi.jdbc40.DataSource40 contiennent toutes les
			// deux un attribut de nom "cm" et de type javax.resource.spi.ConnectionManager
			// dont on veut faire un proxy.
			final Field connectionManagerField = JdbcWrapperHelper.getAccessibleField(dataSource,
					"cm");
			Object javaxConnectionManager = connectionManagerField.get(dataSource);
			javaxConnectionManager = createJavaxConnectionManagerProxy(javaxConnectionManager);
			connectionManagerField.set(dataSource, javaxConnectionManager);
		} else if (weblogic
				&& "weblogic.jdbc.common.internal.RmiDataSource".equals(dataSourceClassName)) {
			// WEBLOGIC: le contexte JNDI est en lecture seule donc on modifie directement
			// l'instance de RmiDataSource déjà présente dans le JNDI.
			final Field jdbcCtxField = JdbcWrapperHelper.getAccessibleField(dataSource, "jdbcCtx");
			Object jdbcCtx = jdbcCtxField.get(dataSource);
			if (jdbcCtx != null) {
				jdbcCtx = createContextProxy((Context) jdbcCtx);
				jdbcCtxField.set(dataSource, jdbcCtx);
			}
			final Field driverInstanceField = JdbcWrapperHelper.getAccessibleField(dataSource,
					"driverInstance");
			Object driverInstance = driverInstanceField.get(dataSource);
			if (driverInstance != null) {
				driverInstance = createDriverProxy((Driver) driverInstance);
				driverInstanceField.set(dataSource, driverInstance);
			}
		}
	}

	boolean stop() {
		boolean ok;
		try {
			final InitialContext initialContext = new InitialContext();
			for (final Map.Entry<String, DataSource> entry : dataSourcesBackup.entrySet()) {
				final String jndiName = entry.getKey();
				final DataSource dataSource = entry.getValue();
				final Object securityToken = JdbcWrapperHelper.changeContextWritable(
						servletContext, null);
				initialContext.rebind(jndiName, dataSource);
				JdbcWrapperHelper.changeContextWritable(servletContext, securityToken);
			}
			initialContext.close();
			// TODO si jboss, glassfish ou weblogic avec datasource, il faudrait aussi désencapsuler
			ok = true;
		} catch (final Throwable t) { // NOPMD
			// ça n'a pas marché, tant pis
			ok = false;
		}
		dataSourcesBackup.clear();
		return ok;
	}

	Context createContextProxy(final Context context) {
		assert context != null;
		final InvocationHandler invocationHandler = new InvocationHandler() {
			/** {@inheritDoc} */
			public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
				Object result = method.invoke(context, args);
				if (result instanceof DataSource) {
					result = createDataSourceProxy((DataSource) result);
				}
				return result;
			}
		};
		return createProxy(context, invocationHandler);
	}

	// pour weblogic
	private Driver createDriverProxy(final Driver driver) {
		assert driver != null;
		final InvocationHandler invocationHandler = new InvocationHandler() {
			/** {@inheritDoc} */
			public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
				Object result = method.invoke(driver, args);
				if (result instanceof Connection) {
					result = createConnectionProxy((Connection) result);
				}
				return result;
			}

		};
		return createProxy(driver, invocationHandler);
	}

	// pour jboss, glassfish
	private Object createJavaxConnectionManagerProxy(final Object javaxConnectionManager) {
		assert javaxConnectionManager != null;
		final InvocationHandler invocationHandler = new InvocationHandler() {
			/** {@inheritDoc} */
			public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
				final Object result = method.invoke(javaxConnectionManager, args);
				if (result instanceof Connection) {
					rewrapConnection((Connection) result);
				}
				return result;
			}

		};
		return createProxy(javaxConnectionManager, invocationHandler);
	}

	void rewrapConnection(Connection connection) throws IllegalAccessException {
		assert connection != null;
		if (jboss && connection.getClass().getSimpleName().startsWith("WrappedConnection")) {
			// pour jboss,
			// result instance de WrappedConnectionJDK6 ou WrappedConnectionJDK5
			// (attribut "mc" sur classe parente)
			final Field baseWrapperManagedConnectionField = JdbcWrapperHelper.getAccessibleField(
					connection, "mc");
			final Object baseWrapperManagedConnection = baseWrapperManagedConnectionField
					.get(connection);
			final Field connectionField = JdbcWrapperHelper.getAccessibleField(
					baseWrapperManagedConnection, "con");
			Connection con = (Connection) connectionField.get(baseWrapperManagedConnection);
			// on teste isProxyAlready ici pour raison de perf
			if (!isProxyAlready(con)) {
				con = createConnectionProxy(con);
				connectionField.set(baseWrapperManagedConnection, con);
			}
		} else if (glassfish
				&& "com.sun.gjc.spi.jdbc40.ConnectionHolder40".equals(connection.getClass()
						.getName())) {
			// pour glassfish,
			// result instance de com.sun.gjc.spi.jdbc40.ConnectionHolder40
			// (attribut "con" sur classe parente)
			final Field connectionHolderField = JdbcWrapperHelper.getAccessibleField(connection,
					"con");
			Connection con = (Connection) connectionHolderField.get(connection);
			// on teste isProxyAlready ici pour raison de perf
			if (!isProxyAlready(con)) {
				con = createConnectionProxy(con);
				connectionHolderField.set(connection, con);
			}
		}
	}

	DataSource createDataSourceProxy(DataSource dataSource) {
		return createDataSourceProxy(null, dataSource);
	}

	private DataSource createDataSourceProxy(String name, final DataSource dataSource) {
		assert dataSource != null;
		if ("org.apache.tomcat.dbcp.dbcp.BasicDataSource".equals(dataSource.getClass().getName())
				&& dataSource instanceof BasicDataSource) {
			pullTomcatDataSourceProperties(name, dataSource);
		}
		final InvocationHandler invocationHandler = new InvocationHandler() {
			/** {@inheritDoc} */
			public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
				Object result = method.invoke(dataSource, args);
				if (result instanceof Connection) {
					result = createConnectionProxy((Connection) result);
				}
				return result;
			}
		};
		return createProxy(dataSource, invocationHandler);
	}

	private void pullTomcatDataSourceProperties(String name, DataSource dataSource) {
		// si tomcat et si dataSource standard, alors on récupère des infos
		final BasicDataSource basicDataSource = (BasicDataSource) dataSource;
		// basicDataSource.getNumActive() est en théorie égale à USED_CONNECTION_COUNT à un instant t,
		// numIdle + numActive est le nombre de connexions ouvertes dans la bdd pour ce serveur à un instant t

		// les propriétés généralement importantes en premier (se méfier aussi de testOnBorrow)
		putTomcatDataSourceProperty(name, "maxActive", basicDataSource.getMaxActive());
		putTomcatDataSourceProperty(name, "poolPreparedStatements", basicDataSource
				.isPoolPreparedStatements());

		putTomcatDataSourceProperty(name, "defaultCatalog", basicDataSource.getDefaultCatalog());
		putTomcatDataSourceProperty(name, "defaultAutoCommit", basicDataSource
				.getDefaultAutoCommit());
		putTomcatDataSourceProperty(name, "defaultReadOnly", basicDataSource.getDefaultReadOnly());
		putTomcatDataSourceProperty(name, "defaultTransactionIsolation", basicDataSource
				.getDefaultTransactionIsolation());
		putTomcatDataSourceProperty(name, "driverClassName", basicDataSource.getDriverClassName());
		putTomcatDataSourceProperty(name, "initialSize", basicDataSource.getInitialSize());
		putTomcatDataSourceProperty(name, "maxIdle", basicDataSource.getMaxIdle());
		putTomcatDataSourceProperty(name, "maxOpenPreparedStatements", basicDataSource
				.getMaxOpenPreparedStatements());
		putTomcatDataSourceProperty(name, "maxWait", basicDataSource.getMaxWait());
		putTomcatDataSourceProperty(name, "minEvictableIdleTimeMillis", basicDataSource
				.getMinEvictableIdleTimeMillis());
		putTomcatDataSourceProperty(name, "minIdle", basicDataSource.getMinIdle());
		putTomcatDataSourceProperty(name, "numTestsPerEvictionRun", basicDataSource
				.getNumTestsPerEvictionRun());
		putTomcatDataSourceProperty(name, "testOnBorrow", basicDataSource.getTestOnBorrow());
		putTomcatDataSourceProperty(name, "testOnReturn", basicDataSource.getTestOnReturn());
		putTomcatDataSourceProperty(name, "testWhileIdle", basicDataSource.getTestWhileIdle());
		putTomcatDataSourceProperty(name, "timeBetweenEvictionRunsMillis", basicDataSource
				.getTimeBetweenEvictionRunsMillis());
		putTomcatDataSourceProperty(name, "validationQuery", basicDataSource.getValidationQuery());
	}

	private static void putTomcatDataSourceProperty(String dataSourceName, String key, Object value) {
		Map<String, Object> dataSourceProperties = TOMCAT_BASIC_DATASOURCES_PROPERTIES
				.get(dataSourceName);
		if (dataSourceProperties == null) {
			dataSourceProperties = new LinkedHashMap<String, Object>();
			TOMCAT_BASIC_DATASOURCES_PROPERTIES.put(dataSourceName, dataSourceProperties);
		}
		dataSourceProperties.put(key, value);
	}

	Connection createConnectionProxy(Connection connection) {
		assert connection != null;
		if (isMonitoringDisabled() || !sqlCounter.isDisplayed()) {
			return connection;
		}
		final InvocationHandler invocationHandler = new ConnectionInvocationHandler(connection);
		return createProxy(connection, invocationHandler);
	}

	private static boolean isMonitoringDisabled() {
		// on doit réévaluer ici le paramètre, car au départ le servletContext
		// n'est pas forcément défini si c'est un driver jdbc sans dataSource
		return Boolean.parseBoolean(Parameters.getParameter(Parameter.DISABLED));
	}

	Statement createStatementProxy(String query, Statement statement) {
		assert statement != null;
		// Si un proxy de connexion a été créé dans un driver jdbc et que par la suite le
		// servletContext a un paramètre désactivant le monitoring, alors ce n'est pas grave
		// les requêtes sql seront simplement agrégées dans le counter pour cette connexion
		// jusqu'à sa fermeture (pour éviter ce détail, il suffirait simplement d'utiliser une
		// dataSource jdbc et pas un driver).
		// Rq : on ne réévalue pas le paramètre ici pour raison de performances sur la recherche
		// dans les paramètres du système, du contexte et du filtre alors que dans 99.999999999%
		// des exécutions il n'y a pas le paramètre.
		final InvocationHandler invocationHandler = new StatementInvocationHandler(query, statement);
		return createProxy(statement, invocationHandler);
	}

	static boolean isEqualsMethod(Object methodName, Object[] args) {
		// == for perf (strings interned: == is ok)
		return "equals" == methodName && args != null && args.length == 1;
	}

	static boolean isHashCodeMethod(Object methodName, Object[] args) {
		// == for perf (strings interned: == is ok)
		return "hashCode" == methodName && (args == null || args.length == 0);
	}

	@SuppressWarnings("unchecked")
	static <T> T createProxy(T object, InvocationHandler invocationHandler) {
		if (isProxyAlready(object)) {
			// si l'objet est déjà un proxy créé pas nous, initialisé par exemple
			// depuis SessionListener ou MonitoringInitialContextFactory,
			// alors il ne faut pas faire un proxy du proxy
			return object;
		}
		final ClassLoader classLoader = object.getClass().getClassLoader(); // NOPMD
		// Rq: object.get.Class().getInterfaces() ne suffit pas pour Connection dans Tomcat
		// car la connection est une instance de PoolGuardConnectionWrapper
		// et connection.getClass().getInterfaces() est vide dans ce cas
		final List<Class> interfaces = new ArrayList(Arrays.asList(object.getClass()
				.getInterfaces()));
		Class classe = object.getClass().getSuperclass();
		while (classe != null) {
			final List<Class> superInterfaces = Arrays.asList(classe.getInterfaces());
			// removeAll d'abord car il ne faut pas de doublon dans la liste
			interfaces.removeAll(superInterfaces);
			interfaces.addAll(superInterfaces);
			classe = classe.getSuperclass();
		}
		// on ignore l'interface javax.naming.Referenceable car sinon le rebind sous jetty appelle
		// referenceable.getReference() et devient inutile
		interfaces.remove(Referenceable.class);
		final Class[] interfacesArray = interfaces.toArray(new Class[interfaces.size()]);

		// ce handler désencapsule les InvocationTargetException des 3 proxy
		final InvocationHandler ih = new DelegatingInvocationHandler(invocationHandler);
		return (T) Proxy.newProxyInstance(classLoader, interfacesArray, ih);
	}

	private static boolean isProxyAlready(Object object) {
		return Proxy.isProxyClass(object.getClass())
				&& Proxy.getInvocationHandler(object) instanceof DelegatingInvocationHandler;
	}
}
