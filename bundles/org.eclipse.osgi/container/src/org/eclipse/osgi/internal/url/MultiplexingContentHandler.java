/*******************************************************************************
 * Copyright (c) 2006, 2012 Cognos Incorporated, IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 *******************************************************************************/
package org.eclipse.osgi.internal.url;

import java.io.IOException;
import java.net.ContentHandler;
import java.net.URLConnection;

public class MultiplexingContentHandler extends ContentHandler {

	private String contentType;
	private ContentHandlerFactoryImpl factory;

	public MultiplexingContentHandler(String contentType, ContentHandlerFactoryImpl factory) {
		this.contentType = contentType;
		this.factory = factory;
	}

	public Object getContent(URLConnection uConn) throws IOException {
		ContentHandler handler = factory.findAuthorizedContentHandler(contentType);
		if (handler != null)
			return handler.getContent(uConn);

		return uConn.getInputStream();
	}

}
