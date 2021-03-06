/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.wicket.request.handler.render;

import java.util.List;

import org.apache.wicket.Application;
import org.apache.wicket.Session;
import org.apache.wicket.core.request.handler.RenderPageRequestHandler;
import org.apache.wicket.core.request.handler.RenderPageRequestHandler.RedirectPolicy;
import org.apache.wicket.feedback.FeedbackCollector;
import org.apache.wicket.feedback.FeedbackMessage;
import org.apache.wicket.protocol.http.BufferedWebResponse;
import org.apache.wicket.protocol.http.WebApplication;
import org.apache.wicket.request.IRequestHandler;
import org.apache.wicket.request.Request;
import org.apache.wicket.request.Url;
import org.apache.wicket.request.component.IRequestablePage;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.request.http.WebRequest;
import org.apache.wicket.request.http.WebResponse;
import org.apache.wicket.util.lang.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PageRenderer} for web applications.
 * 
 * @author Matej Knopp
 */
public class WebPageRenderer extends PageRenderer
{
	private static final Logger logger = LoggerFactory.getLogger(WebPageRenderer.class);

	/**
	 * Construct.
	 * 
	 * @param renderPageRequestHandler
	 */
	public WebPageRenderer(RenderPageRequestHandler renderPageRequestHandler)
	{
		super(renderPageRequestHandler);
	}

	private boolean isAjax(RequestCycle requestCycle)
	{
		boolean isAjax = false;

		Request request = requestCycle.getRequest();
		if (request instanceof WebRequest)
		{
			WebRequest webRequest = (WebRequest)request;
			isAjax = webRequest.isAjax();
		}

		return isAjax;
	}

	/**
	 * Store the buffered response at application level. If current session is
	 * temporary, a permanent one is created.
	 * 
	 * @param url
	 * @param response
	 */
	protected void storeBufferedResponse(Url url, BufferedWebResponse response)
	{		
		if (isSessionTemporary()) 
		{
			Session.get().bind();
		}
		
		WebApplication.get().storeBufferedResponse(getSessionId(), url, response);
	}

	/**
	 * Retrieves a stored buffered response for a given url.
	 *
	 * @param url
	 *          The url used as a key
	 * @return the stored buffered response. {@code null} if there is no stored response for the given url
	 * @see org.apache.wicket.settings.IRequestCycleSettings.RenderStrategy#REDIRECT_TO_BUFFER
	 * @deprecated Will be removed in Wicket 7.0. Use {@link org.apache.wicket.protocol.http.WebApplication#getAndRemoveBufferedResponse(String, org.apache.wicket.request.Url)} instead
	 */
	@Deprecated
	protected BufferedWebResponse getAndRemoveBufferedResponse(Url url)
	{
		return WebApplication.get().getAndRemoveBufferedResponse(getSessionId(), url);
	}

	/**
	 * Renders page to a {@link BufferedWebResponse}. All URLs in page will be rendered relative to
	 * <code>targetUrl</code>
	 * 
	 * @param targetUrl
	 * @param requestCycle
	 * @return BufferedWebResponse containing page body
	 */
	protected BufferedWebResponse renderPage(Url targetUrl, RequestCycle requestCycle)
	{
		// get the page before checking for a scheduled request handler because
		// the page may call setResponsePage in its constructor
		IRequestablePage requestablePage = getPage();

		IRequestHandler scheduled = requestCycle.getRequestHandlerScheduledAfterCurrent();

		if (scheduled != null)
		{
			// no need to render
			return null;
		}

		// keep the original response
		final WebResponse originalResponse = (WebResponse)requestCycle.getResponse();

		// buffered web response for page
		BufferedWebResponse response = new BufferedWebResponse(originalResponse);

		// keep the original base URL
		Url originalBaseUrl = requestCycle.getUrlRenderer().setBaseUrl(targetUrl);

		try
		{
			requestCycle.setResponse(response);
			requestablePage.renderPage();

			if (scheduled == null && requestCycle.getRequestHandlerScheduledAfterCurrent() != null)
			{
				// This is a special case.
				// During page render another request handler got scheduled and will want to
				// overwrite the response, so we need to let it.
				// Just preserve the meta data headers. Clear the initial actions because they are
				// already copied into the new response's actions
				originalResponse.reset();
				response.writeMetaData(originalResponse);
				return null;
			}
			else
			{
				return response;
			}
		}
		finally
		{
			// restore original response and base URL
			requestCycle.setResponse(originalResponse);
			requestCycle.getUrlRenderer().setBaseUrl(originalBaseUrl);
		}
	}

	/**
	 * 
	 * @param url
	 * @param requestCycle
	 */
	protected void redirectTo(Url url, RequestCycle requestCycle)
	{
		bindSessionIfNeeded();

		WebResponse response = (WebResponse)requestCycle.getResponse();
		String relativeUrl = requestCycle.getUrlRenderer().renderUrl(url);
		response.sendRedirect(relativeUrl);
	}

	/**
	 * Bind the session if there are feedback messages pending.
	 * https://issues.apache.org/jira/browse/WICKET-5165
	 */
	private void bindSessionIfNeeded()
	{
		// check for session feedback messages only
		FeedbackCollector collector = new FeedbackCollector();
		List<FeedbackMessage> feedbackMessages = collector.collect();
		if (feedbackMessages.size() > 0)
		{
			Session.get().bind();
		}
	}

	/*
	 * TODO: simplify the code below. See WICKET-3347
	 */
	@Override
	public void respond(RequestCycle requestCycle)
	{
		Url currentUrl = requestCycle.getUrlRenderer().getBaseUrl();
		Url targetUrl = requestCycle.mapUrlFor(getRenderPageRequestHandler());

		//
		// the code below is little hairy but we have to handle 3 redirect policies,
		// 3 rendering strategies and two kind of requests (ajax and normal)
		//

		boolean isAjax = isAjax(requestCycle);

		boolean shouldPreserveClientUrl = ((WebRequest)requestCycle.getRequest()).shouldPreserveClientUrl();

		if ((isAjax == false)
					&& (compatibleProtocols(currentUrl.getProtocol(), targetUrl.getProtocol())) && (//
							getRedirectPolicy() == RedirectPolicy.NEVER_REDIRECT //
						|| (isOnePassRender() && getRedirectPolicy() != RedirectPolicy.ALWAYS_REDIRECT) //
						|| ((targetUrl.equals(currentUrl) && !getPageProvider().isNewPageInstance() && !getPage()
								.isPageStateless()) //
						|| (targetUrl.equals(currentUrl) && isRedirectToRender()) //
						) //
				|| (shouldPreserveClientUrl && getRedirectPolicy() != RedirectPolicy.ALWAYS_REDIRECT)) //
		) //
		{
			// if the policy is never to redirect
			// or one pass render mode is on
			// or the targetUrl matches current url and the page is not stateless
			// or the targetUrl matches current url, page is stateless but it's redirect-to-render
			// or the request determines that the current url should be preserved
			// just render the page
			BufferedWebResponse response = renderPage(currentUrl, requestCycle);
			if (response != null)
			{
				response.writeTo((WebResponse)requestCycle.getResponse());
			}
		}
		else if (getRedirectPolicy() == RedirectPolicy.ALWAYS_REDIRECT //
			||
			isRedirectToRender() //
			|| (isAjax && targetUrl.equals(currentUrl)))
		{
			// if target URL is different
			// and render policy is always-redirect or it's redirect-to-render
			redirectTo(targetUrl, requestCycle);
		}
		else if (!targetUrl.equals(currentUrl) //
			&&
			(getPageProvider().isNewPageInstance() || (isSessionTemporary() && getPage().isPageStateless())))
		{
			// if target URL is different and session is temporary and page is stateless
			// this is special case when page is stateless but there is no session so we can't
			// render it to buffer

			// alternatively if URLs are different and we have a page class and not an instance we
			// can redirect to the url which will instantiate the instance of us

			// note: if we had session here we would render the page to buffer and then redirect to
			// URL generated *after* page has been rendered (the statelessness may change during
			// render). this would save one redirect because now we have to render to URL generated
			// *before* page is rendered, render the page, get URL after render and if the URL is
			// different (meaning page is not stateless), save the buffer and redirect again (which
			// is pretty much what the next step does)
			redirectTo(targetUrl, requestCycle);
		}
		else
		{
			if (isRedirectToBuffer() == false && logger.isDebugEnabled())
			{
				String details = String.format(
					"redirect strategy: '%s', isAjax: '%s', redirect policy: '%s', "
						+ "current url: '%s', target url: '%s', is new: '%s', is stateless: '%s', is temporary: '%s'",
					Application.get().getRequestCycleSettings().getRenderStrategy(), isAjax,
					getRedirectPolicy(), currentUrl, targetUrl,
					getPageProvider().isNewPageInstance(), getPage().isPageStateless(),
					isSessionTemporary());
				logger.debug("Falling back to Redirect_To_Buffer render strategy because none of the conditions " +
					"matched. Details: " + details);
			}

			// force creation of possible stateful page to get the final target url
			getPage();

			Url beforeRenderUrl = requestCycle.mapUrlFor(getRenderPageRequestHandler());

			// redirect to buffer
			BufferedWebResponse response = renderPage(beforeRenderUrl, requestCycle);

			if (response == null)
			{
				return;
			}

			// the url might have changed after page has been rendered (e.g. the
			// stateless flag might have changed because stateful components
			// were added)
			final Url afterRenderUrl = requestCycle.mapUrlFor(getRenderPageRequestHandler());

			if (beforeRenderUrl.getSegments().equals(afterRenderUrl.getSegments()) == false)
			{
				// the amount of segments is different - generated relative URLs
				// will not work, we need to rerender the page. This can happen
				// with IRequestHandlers that produce different URLs with
				// different amount of segments for stateless and stateful pages
				response = renderPage(afterRenderUrl, requestCycle);
			}

			if (currentUrl.equals(afterRenderUrl))
			{
				// no need to redirect when both urls are exactly the same
				response.writeTo((WebResponse)requestCycle.getResponse());
			}
			// if page is still stateless after render
			else if (getPage().isPageStateless() && !enableRedirectForStatelessPage())
			{
				// we don't want the redirect to happen for stateless page
				// example:
				// when a normal mounted stateful page is hit at /mount/point
				// wicket renders the page to buffer and redirects to /mount/point?12
				// but for stateless page the redirect is not necessary
				// also for listener interface on stateful page we want to redirect
				// after the listener is invoked, but on stateless page the user
				// must ask for redirect explicitly
				response.writeTo((WebResponse)requestCycle.getResponse());
			}
			else
			{
				storeBufferedResponse(afterRenderUrl, response);

				redirectTo(afterRenderUrl, requestCycle);
			}
		}
	}

	/**
	 * Compares the protocols of two {@link Url}s
	 *
	 * @param p1
	 *      the first protocol
	 * @param p2
	 *      the second protocol
	 * @return {@code false} if the protocols are both non-null and not equal,
	 *          {@code true} - otherwise
	 */
	protected boolean compatibleProtocols(String p1, String p2)
	{
		if (p1 != null && p2 != null)
		{
			return Objects.equal(p1, p2);
		}

		return true;
	}
}
