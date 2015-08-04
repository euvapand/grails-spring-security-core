/* Copyright 2006-2015 the original author or authors.
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
package grails.plugin.springsecurity.web.access.intercept

import java.util.concurrent.CopyOnWriteArrayList

import javax.servlet.http.HttpServletRequest

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.support.MessageSourceAccessor
import org.springframework.http.HttpMethod
import org.springframework.security.access.ConfigAttribute
import org.springframework.security.access.SecurityConfig
import org.springframework.security.access.vote.AuthenticatedVoter
import org.springframework.security.access.vote.RoleVoter
import org.springframework.security.core.SpringSecurityMessageSource
import org.springframework.security.web.FilterInvocation
import org.springframework.security.web.access.intercept.FilterInvocationSecurityMetadataSource
import org.springframework.util.AntPathMatcher
import org.springframework.util.StringUtils

import grails.plugin.springsecurity.InterceptedUrl
import grails.util.GrailsUtil
import groovy.transform.CompileStatic

/**
 * @author <a href='mailto:burt@burtbeckwith.com'>Burt Beckwith</a>
 */
@CompileStatic
abstract class AbstractFilterInvocationDefinition implements FilterInvocationSecurityMetadataSource {

	protected static final Collection<ConfigAttribute> DENY = Collections.singletonList((ConfigAttribute)new SecurityConfig('_DENY_'))

	protected RoleVoter roleVoter
	protected AuthenticatedVoter authenticatedVoter
	protected final List<InterceptedUrl> compiled = new CopyOnWriteArrayList<InterceptedUrl>()
	protected MessageSourceAccessor messages = SpringSecurityMessageSource.accessor
	protected AntPathMatcher urlMatcher = new AntPathMatcher()
	protected boolean initialized

	protected final Logger log = LoggerFactory.getLogger(getClass())

	/** Dependency injection for whether to reject if there's no matching rule. */
	boolean rejectIfNoRule

	/**
	 * Allows subclasses to be externally reset.
	 */
	void reset() {
		// override if necessary
	}

	Collection<ConfigAttribute> getAttributes(object) throws IllegalArgumentException {
		assert object, 'Object must be a FilterInvocation'
		assert supports(object.getClass()), 'Object must be a FilterInvocation'

		FilterInvocation filterInvocation = (FilterInvocation)object

		String url = determineUrl(filterInvocation)

		Collection<ConfigAttribute> configAttributes = findConfigAttributes(url, filterInvocation.request.method)

		if (rejectIfNoRule && !configAttributes) {
			// return something that cannot be valid this will cause the voters to abstain or deny
			return DENY
		}

		configAttributes
	}

	protected String determineUrl(FilterInvocation filterInvocation) {
		lowercaseAndStripQuerystring calculateUri(filterInvocation.httpRequest)
	}

	protected boolean stopAtFirstMatch() {
		false
	}

	// for testing
	InterceptedUrl getInterceptedUrl(String url, HttpMethod httpMethod) {

		initialize()

		for (InterceptedUrl iu in compiled) {
			if (iu.httpMethod == httpMethod && iu.pattern == url) {
				return iu
			}
		}
	}

	protected Collection<ConfigAttribute> findConfigAttributes(String url, String requestMethod) {

		initialize()

		Collection<ConfigAttribute> configAttributes
		String configAttributePattern

		boolean stopAtFirstMatch = stopAtFirstMatch()
		for (InterceptedUrl iu in compiled) {

			if (iu.httpMethod && requestMethod && iu.httpMethod != HttpMethod.valueOf(requestMethod)) {
				if (log.debugEnabled) {
					log.debug "Request '{} {}' doesn't match '{} {}'", [requestMethod, url, iu.httpMethod, iu.pattern] as Object[]
				}
				continue
			}

			if (urlMatcher.match(iu.pattern, url)) {
				if (configAttributes == null || urlMatcher.match(configAttributePattern, iu.pattern)) {
					configAttributes = iu.configAttributes
					configAttributePattern = iu.pattern
					if (log.traceEnabled) {
						log.trace "new candidate for '{}': '{}':{}", [url, iu.pattern, configAttributes] as Object[]
					}
					if (stopAtFirstMatch) {
						break
					}
				}
			}
		}

		if (log.traceEnabled) {
			if (configAttributes == null) {
				log.trace "no config for '{}'", url
			}
			else {
				log.trace "config for '{}' is '{}':{}", [url, configAttributePattern, configAttributes] as Object[]
			}
		}

		configAttributes
	}

	protected void initialize() {
		// override if necessary
	}

	boolean supports(Class<?> clazz) {
		FilterInvocation.isAssignableFrom clazz
	}

	Collection<ConfigAttribute> getAllConfigAttributes() {
		try {
			initialize()
		}
		catch (e) {
			log.error e.message, GrailsUtil.deepSanitize(e)
		}

		Collection<ConfigAttribute> all = new LinkedHashSet<ConfigAttribute>()
		for (InterceptedUrl iu in compiled) {
			all.addAll iu.configAttributes
		}
		Collections.unmodifiableCollection all
	}

	protected String calculateUri(HttpServletRequest request) {
		String url = request.requestURI.substring(request.contextPath.length())
		int semicolonIndex = url.indexOf(';')
		semicolonIndex == -1 ? url : url.substring(0, semicolonIndex)
	}

	protected String lowercaseAndStripQuerystring(String url) {

		String fixed = url.toLowerCase()

		int firstQuestionMarkIndex = fixed.indexOf('?')
		if (firstQuestionMarkIndex != -1) {
			fixed = fixed.substring(0, firstQuestionMarkIndex)
		}

		fixed
	}

	/**
	 * For debugging.
	 * @return an unmodifiable map of {@link AnnotationFilterInvocationDefinition}ConfigAttributeDefinition
	 * keyed by compiled patterns
	 */
	List<InterceptedUrl> getConfigAttributeMap() {
		Collections.unmodifiableList compiled
	}

	// fixes extra spaces, trailing commas, etc.
	protected List<String> split(String value) {
		if (!value.startsWith('ROLE_') && !value.startsWith('IS_')) {
			// an expression
			return Collections.singletonList(value)
		}

		String[] parts = StringUtils.commaDelimitedListToStringArray(value)
		List<String> cleaned = []
		for (String part in parts) {
			part = part.trim()
			if (part) {
				cleaned << part
			}
		}
		cleaned
	}

	protected void compileAndStoreMapping(InterceptedUrl iu) {
		String pattern = iu.pattern
		HttpMethod method = iu.httpMethod

		String key = pattern.toLowerCase()

		Collection<ConfigAttribute> configAttributes = iu.configAttributes

		InterceptedUrl replaced = storeMapping(key, method, Collections.unmodifiableCollection(configAttributes))
		if (replaced) {
			log.warn "replaced rule for '{}' with roles {} with roles {}", [key, replaced.configAttributes, configAttributes] as Object[]
		}
	}

	protected InterceptedUrl storeMapping(String pattern, HttpMethod method, Collection<ConfigAttribute> configAttributes) {

		InterceptedUrl existing
		for (InterceptedUrl iu : compiled) {
			if (iu.pattern == pattern && iu.httpMethod == method) {
				existing = iu
				break
			}
		}

		if (existing) {
			compiled.remove existing
		}

		compiled << new InterceptedUrl(pattern, method, configAttributes)

		existing
	}

	protected void resetConfigs() {
		compiled.clear()
	}

	/**
	 * For admin/debugging - find all config attributes that apply to the specified URL (doesn't consider request method restrictions).
	 * @param url the URL
	 * @return matching attributes
	 */
	Collection<ConfigAttribute> findMatchingAttributes(String url) {
		for (InterceptedUrl iu in compiled) {
			if (urlMatcher.match(iu.pattern, url)) {
				return iu.configAttributes
			}
		}
		Collections.emptyList()
	}
}
