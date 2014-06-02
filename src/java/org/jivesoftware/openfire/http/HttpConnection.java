/**
 * $RCSfile$
 * $Revision: $
 * $Date: $
 *
 * Copyright (C) 2005-2008 Jive Software. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.openfire.http;

import org.jivesoftware.util.JiveConstants;
import org.eclipse.jetty.continuation.Continuation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.cert.X509Certificate;

/**
 * Represents one HTTP connection with a client using the HTTP Binding service. The client will wait
 * on {@link #getResponse()} until the server forwards a message to it or the wait time on the
 * session timeout.
 *
 * @author Alexander Wenckus
 */
public class HttpConnection {

    private static final Logger Log = LoggerFactory.getLogger(HttpConnection.class);
    private static final String RESPONSE_BODY = "response-body";
    private static final String CONNECTION_CLOSED = "connection closed";

    private final long requestId;
    private final X509Certificate[] sslCertificates;
    private final boolean isSecure;
    
    private String body;
    private HttpSession session;
    private Continuation continuation;
    private boolean isClosed;

    /**
     * Constructs an HTTP Connection.
     *
     * @param requestId the ID which uniquely identifies this request.
     * @param isSecure true if this connection is using HTTPS
     * @param sslCertificates list of certificates presented by the client.
     */
    public HttpConnection(long requestId, boolean isSecure, X509Certificate[] sslCertificates) {
        this.requestId = requestId;
        this.isSecure = isSecure;
        this.sslCertificates = sslCertificates;
    }

    /**
     * The connection should be closed without delivering a stanza to the requestor.
     */
    public void close() {
        if (isClosed) {
            return;
        }

        try {
            deliverBody(CONNECTION_CLOSED);
        }
        catch (HttpConnectionClosedException e) {
            Log.warn("Unexpected exception occurred while trying to close an HttpException.", e);
        }
    }

    /**
     * Returns true if this connection has been closed, either a response was delivered to the
     * client or the server closed the connection abruptly.
     *
     * @return true if this connection has been closed.
     */
    public boolean isClosed() {
        return isClosed;
    }

    /**
     * Returns true if this connection is using HTTPS.
     *
     * @return true if this connection is using HTTPS.
     */
    public boolean isSecure() {
        return isSecure;
    }

    /**
     * Delivers content to the client. The content should be valid XMPP wrapped inside of a body.
     * A <i>null</i> value for body indicates that the connection should be closed and the client
     * sent an empty body.
     *
     * @param body the XMPP content to be forwarded to the client inside of a body tag.
     *
     * @throws HttpConnectionClosedException when this connection to the client has already received
     * a deliverable to forward to the client
     */
    public void deliverBody(String body) throws HttpConnectionClosedException {
        // We only want to use this function once so we will close it when the body is delivered.
    	synchronized (this) {
	        if (isClosed) {
	            throw new HttpConnectionClosedException("The http connection is no longer " +
	                    "available to deliver content");
	        }
	        else {
	            isClosed = true;
	        }
    	}
        if (body == null) {
            body = CONNECTION_CLOSED;
        }
        if (isSuspended()) {
            continuation.setAttribute(RESPONSE_BODY, body);
            continuation.resume();
            session.incrementServerPacketCount();
        }
        else {
            this.body = body;
        }
    }

    /**
     * A call that will suspend the request if there is no deliverable currently available.
     * Once the response becomes available, it is returned.
     *
     * @return the deliverable to send to the client
     * @throws HttpBindTimeoutException to indicate that the maximum wait time requested by the
     * client has been surpassed and an empty response should be returned.
     */
    public String getResponse() throws HttpBindTimeoutException {
        if (body == null && continuation != null) {
            try {
                body = waitForResponse();
            }
            catch (HttpBindTimeoutException e) {
                this.isClosed = true;
                throw e;
            }
        }
        else if (body == null) {
            throw new IllegalStateException("Continuation not set, cannot wait for deliverable.");
        }
        else if(CONNECTION_CLOSED.equals(body)) {
        	return null;
        }
        return body;
    }

    /**
     * Returns the ID which uniquely identifies this connection.
     *
     * @return the ID which uniquely identifies this connection.
     */
    public long getRequestId() {
        return requestId;
    }

    /**
     * Set the session that this connection belongs to.
     *
     * @param session the session that this connection belongs to.
     */
    void setSession(HttpSession session) {
        this.session = session;
    }

    /**
     * Returns the session that this connection belongs to.
     *
     * @return the session that this connection belongs to.
     */
    public HttpSession getSession() {
        return session;
    }
    
    /**
     * Returns the peer certificates for this connection. 
     * 
     * @return the peer certificates for this connection or null.
     */
    public X509Certificate[] getPeerCertificates() {
        return sslCertificates;
    }

    void setContinuation(Continuation continuation) {
        this.continuation = continuation;
    }
    
    public boolean isSuspended() {
    	return continuation != null && continuation.isSuspended();
    }
    
    public boolean isExpired() {
    	return continuation != null && continuation.isExpired();
    }

    private String waitForResponse() throws HttpBindTimeoutException {
        // we enter this method when we have no messages pending delivery
		// when we resume a suspended continuation, or when we time out
		if (continuation.isInitial()) {
		    continuation.setTimeout(session.getWait() * JiveConstants.SECOND);
            continuation.suspend();
            continuation.undispatch();
        } else if (continuation.isResumed()) {
            // This will occur when the hold attribute of a session has been exceeded.
            String deliverable = (String) continuation.getAttribute(RESPONSE_BODY);
            if (deliverable == null) {
                throw new HttpBindTimeoutException();
            }
            else if(CONNECTION_CLOSED.equals(deliverable)) {
                return null;
            }
            return deliverable;
        }

        throw new HttpBindTimeoutException("Request " + requestId + " exceeded response time from " +
                "server of " + session.getWait() + " seconds.");
    }

	@Override
	public String toString() {
		return (session != null ? session.toString() : "[Anonymous]")
				+ " rid: " + this.getRequestId();
	}
}
