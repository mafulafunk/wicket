/**
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.wicket.protocol.ws.jee;

import org.apache.wicket.Component;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.head.OnLoadHeaderItem;

/**
 * Websocket behavior that invokes its callback method (onMessage) when a JavaScript event is fired.
 * 
 * @author andrea
 *
 */
public abstract class WebsocketEventBehavior extends WebsocketBehavior {
	private final String event;

	public WebsocketEventBehavior(String event) {
		super();
		this.event = event;
	}
	
	@Override
	public void renderHead(Component component, IHeaderResponse response) {
		super.renderHead(component, response);
		
		OnLoadHeaderItem headerItem = OnLoadHeaderItem.forScript("Wicket.Event.add('" + component.getMarkupId() + "', '" + event + "', function(event) {\n" +
				"websocket" + component.getMarkupId() + ".send('" + event + "');}\n);");
		response.render(headerItem);
	}
}
