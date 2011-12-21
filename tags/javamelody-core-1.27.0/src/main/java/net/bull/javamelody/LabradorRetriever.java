/*
 * Copyright 2008-2010 by Emeric Vernat
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

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.GZIPInputStream;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;

/**
 * Classe permettant d'ouvrir une connexion http et de récupérer les objets java sérialisés dans la réponse.
 * Utilisée dans le serveur de collecte.
 * @author Emeric Vernat
 */
class LabradorRetriever {
	@SuppressWarnings("all")
	private static final Logger LOGGER = Logger.getLogger("javamelody");

	/** Timeout des connections serveur en millisecondes (0 : pas de timeout). */
	private static final int CONNECTION_TIMEOUT = 20000;

	/** Timeout de lecture des connections serveur en millisecondes (0 : pas de timeout). */
	private static final int READ_TIMEOUT = 60000;

	private final URL url;

	// Rq: les configurations suivantes sont celles par défaut, on ne les change pas
	//	    static { HttpURLConnection.setFollowRedirects(true);
	//	    URLConnection.setDefaultAllowUserInteraction(true); }

	LabradorRetriever(URL url) {
		super();
		assert url != null;
		this.url = url;
	}

	<T> T call() throws IOException {
		if (shouldMock()) {
			return this.<T> createMockResultOfCall();
		}
		final long start = System.currentTimeMillis();
		try {
			final URLConnection connection = openConnection(url);
			// pour traductions (si on vient de CollectorServlet.forwardActionAndUpdateData,
			// cela permet d'avoir les messages dans la bonne langue)
			connection.setRequestProperty("Accept-Language", I18N.getCurrentLocale().toString());
			// Rq: on ne gère pas pour l'instant les éventuels cookie de session http,
			// puisque le filtre de monitoring n'est pas censé créer des sessions
			//		if (cookie != null) { connection.setRequestProperty("Cookie", cookie); }

			connection.connect();

			//		final String setCookie = connection.getHeaderField("Set-Cookie");
			//		if (setCookie != null) { cookie = setCookie; }
			@SuppressWarnings("unchecked")
			final T result = (T) read(connection);
			if (LOGGER.isDebugEnabled()) {
				LOGGER.debug("read on " + url + " : " + result);
			}

			if (result instanceof RuntimeException) {
				throw (RuntimeException) result;
			} else if (result instanceof Error) {
				throw (Error) result;
			} else if (result instanceof IOException) {
				throw (IOException) result;
			} else if (result instanceof Exception) {
				throw createIOException((Exception) result);
			}
			return result;
		} catch (final ClassNotFoundException e) {
			throw createIOException(e);
		} finally {
			LOGGER.info("http call done in " + (System.currentTimeMillis() - start) + " ms for "
					+ url);
		}
	}

	private static IOException createIOException(Exception e) {
		// Rq: le constructeur de IOException avec message et cause n'existe qu'en jdk 1.6
		final IOException ex = new IOException(e.getMessage());
		ex.initCause(e);
		return ex;
	}

	void copyTo(HttpServletRequest httpRequest, HttpServletResponse httpResponse)
			throws IOException {
		if (shouldMock()) {
			return;
		}
		assert httpRequest != null;
		assert httpResponse != null;
		final long start = System.currentTimeMillis();
		try {
			final URLConnection connection = openConnection(url);
			// pour traductions
			connection.setRequestProperty("Accept-Language",
					httpRequest.getHeader("Accept-Language"));
			// Rq: on ne gère pas pour l'instant les éventuels cookie de session http,
			// puisque le filtre de monitoring n'est pas censé créer des sessions
			//		if (cookie != null) { connection.setRequestProperty("Cookie", cookie); }

			connection.connect();

			try {
				InputStream input = connection.getInputStream();
				if ("gzip".equals(connection.getContentEncoding())) {
					input = new GZIPInputStream(input);
				}
				httpResponse.setContentType(connection.getContentType());
				TransportFormat.pump(input, httpResponse.getOutputStream());
			} finally {
				close(connection);
			}
		} finally {
			LOGGER.info("http call done in " + (System.currentTimeMillis() - start) + " ms for "
					+ url);
		}
	}

	/**
	 * Ouvre la connection http.
	 * @param url URL
	 * @return Object
	 * @throws IOException   Exception de communication
	 */
	private static URLConnection openConnection(URL url) throws IOException {
		final URLConnection connection = url.openConnection();
		connection.setUseCaches(false);
		if (CONNECTION_TIMEOUT > 0) {
			connection.setConnectTimeout(CONNECTION_TIMEOUT);
		}
		if (READ_TIMEOUT > 0) {
			connection.setReadTimeout(READ_TIMEOUT);
		}
		// grâce à cette propriété, l'application retournera un flux compressé si la taille
		// dépasse x Ko
		connection.setRequestProperty("Accept-Encoding", "gzip");
		return connection;
	}

	/**
	 * Lit l'objet renvoyé dans le flux de réponse.
	 * @return Object
	 * @param connection URLConnection
	 * @throws IOException   Exception de communication
	 * @throws ClassNotFoundException   Une classe transmise par le serveur n'a pas été trouvée
	 */
	private static Serializable read(URLConnection connection) throws IOException,
			ClassNotFoundException {
		InputStream input = connection.getInputStream();
		try {
			if ("gzip".equals(connection.getContentEncoding())) {
				// si la taille du flux dépasse x Ko et que l'application a retourné un flux compressé
				// alors on le décompresse
				input = new GZIPInputStream(input);
			}
			final String contentType = connection.getContentType();
			final TransportFormat transportFormat;
			if (contentType != null && contentType.startsWith("text/xml")) {
				transportFormat = TransportFormat.XML;
			} else {
				transportFormat = TransportFormat.SERIALIZED;
			}
			return transportFormat.readSerializableFrom(input);
		} finally {
			close(connection);
		}
	}

	private static void close(URLConnection connection) throws IOException {
		// ce close doit être fait en finally
		// (http://java.sun.com/j2se/1.5.0/docs/guide/net/http-keepalive.html)
		connection.getInputStream().close();

		if (connection instanceof HttpURLConnection) {
			final InputStream error = ((HttpURLConnection) connection).getErrorStream();
			if (error != null) {
				error.close();
			}
		}
	}

	private static boolean shouldMock() {
		return Boolean.parseBoolean(System.getProperty(Parameters.PARAMETER_SYSTEM_PREFIX
				+ "mockLabradorRetriever"));
	}

	// bouchon pour tests unitaires
	@SuppressWarnings("unchecked")
	private <T> T createMockResultOfCall() throws IOException {
		final Object result;
		final String request = url.toString();
		if (!request.contains(HttpParameters.PART_PARAMETER + '=')) {
			final String message = request.contains("/test2") ? null
					: "ceci est message pour le rapport";
			result = Arrays.asList(new Counter(Counter.HTTP_COUNTER_NAME, null), new Counter(
					Counter.ERROR_COUNTER_NAME, null), new JavaInformations(null, true), message);
		} else {
			result = createMockResultOfPartCall(request);
		}
		return (T) result;
	}

	private Object createMockResultOfPartCall(String request) throws IOException {
		final Object result;
		if (request.contains(HttpParameters.SESSIONS_PART)
				&& request.contains(HttpParameters.SESSION_ID_PARAMETER)) {
			result = null;
		} else if (request.contains(HttpParameters.SESSIONS_PART)
				|| request.contains(HttpParameters.PROCESSES_PART)) {
			result = Collections.emptyList();
		} else if (request.contains(HttpParameters.DATABASE_PART)) {
			try {
				result = new DatabaseInformations(0);
			} catch (final Exception e) {
				throw new IllegalStateException(e);
			}
		} else if (request.contains(HttpParameters.HEAP_HISTO_PART)) {
			final InputStream input = getClass().getResourceAsStream("/heaphisto.txt");
			try {
				result = new HeapHistogram(input, false);
			} finally {
				input.close();
			}
		} else {
			result = null;
		}
		return result;
	}
}