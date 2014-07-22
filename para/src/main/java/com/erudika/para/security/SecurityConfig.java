/*
 * Copyright 2013-2014 Erudika. http://erudika.com
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
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para.security;

import com.erudika.para.Para;
import com.erudika.para.rest.Signer;
import com.erudika.para.utils.Config;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.annotation.security.DeclareRoles;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.RememberMeAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.openid.OpenID4JavaConsumer;
import org.springframework.security.openid.OpenIDAuthenticationProvider;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Programmatic configuration for Spring Security.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
@Configuration
@EnableWebSecurity
@DeclareRoles({ "ROLE_USER", "ROLE_MOD", "ROLE_ADMIN", "ROLE_APP" })
public class SecurityConfig extends WebSecurityConfigurerAdapter {

	private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

	/**
	 * Configuers the authentication providers
	 *
	 * @param auth a builder
	 * @throws Exception ex
	 */
	@Override
	protected void configure(AuthenticationManagerBuilder auth) throws Exception {
		OpenIDAuthenticationProvider openidProvider = new OpenIDAuthenticationProvider();
		openidProvider.setAuthenticationUserDetailsService(new SimpleUserService());
		auth.authenticationProvider(openidProvider);

		RememberMeAuthenticationProvider rmeProvider = new RememberMeAuthenticationProvider(Config.APP_SECRET_KEY);
		auth.authenticationProvider(rmeProvider);
	}

	/**
	 * Configures the unsecured public resources
	 *
	 * @param web web sec object
	 * @throws Exception ex
	 */
	@Override
	public void configure(WebSecurity web) throws Exception {
		ConfigList c = Config.getConfig().getList("security.ignored");
		for (ConfigValue configValue : c) {
			web.ignoring().antMatchers((String) configValue.unwrapped());
		}
		//web.debug(true);
	}

	/**
	 * Configures the protected private resources
	 *
	 * @param http HTTP sec object
	 * @throws Exception ex
	 */
	@Override
	protected void configure(HttpSecurity http) throws Exception {
		String[] defroles = {"USER", "MOD", "ADMIN"};
		Map<String, String> confMap = Config.getConfigMap();
		ConfigObject c = Config.getConfig().getObject("security.protected");
		boolean enableRestFilter = c.containsKey("api");

		for (ConfigValue cv : c.values()) {
			ArrayList<String> patterns = new ArrayList<String>();
			ArrayList<String> roles = new ArrayList<String>();

			for (ConfigValue configValue : (ConfigList) cv) {
				if (configValue instanceof List) {
					for (ConfigValue role : (ConfigList) configValue) {
						roles.add(((String) role.unwrapped()).toUpperCase());
					}
				} else {
					patterns.add((String) configValue.unwrapped());
				}
			}

			String[] rolz = (roles.isEmpty()) ? defroles : roles.toArray(new String[0]);
			http.authorizeRequests().antMatchers(patterns.toArray(new String[0])).hasAnyRole(rolz);
		}

		CachedCsrfTokenRepository str = new CachedCsrfTokenRepository();
		Para.injectInto(str);

		http.csrf().requireCsrfProtectionMatcher(new RequestMatcher() {
			private Pattern allowedMethods = Pattern.compile("^(GET|HEAD|TRACE|OPTIONS)$");

			public boolean matches(HttpServletRequest request) {
				return !RestRequestMatcher.INSTANCE.matches(request)
						&& !allowedMethods.matcher(request.getMethod()).matches();
			}
		}).csrfTokenRepository(str);

		http.sessionManagement().enableSessionUrlRewriting(false);
		http.sessionManagement().sessionCreationPolicy(SessionCreationPolicy.NEVER);
		http.sessionManagement().sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy());
		http.securityContext().securityContextRepository(new NullSecurityContextRepository());
		http.exceptionHandling().authenticationEntryPoint(new SimpleEntryPoint(confMap.get("security.signin")));
		http.exceptionHandling().accessDeniedHandler(new SimpleAccessDeniedHandler(confMap.get("security.access_denied")));
		http.requestCache().requestCache(new SimpleRequestCache());

		SimpleAuthenticationSuccessHandler successHandler = new SimpleAuthenticationSuccessHandler();
		successHandler.setDefaultTargetUrl(confMap.get("security.signin_success"));
		successHandler.setTargetUrlParameter(confMap.get("security.returnto"));
		successHandler.setUseReferer(true);

		SimpleUrlAuthenticationFailureHandler failureHandler = new SimpleUrlAuthenticationFailureHandler();
		failureHandler.setDefaultFailureUrl(confMap.get("security.signin_failure"));

		TokenBasedRememberMeServices tbrms = new TokenBasedRememberMeServices(Config.APP_SECRET_KEY, new SimpleUserService());
		tbrms.setAlwaysRemember(true);
		tbrms.setTokenValiditySeconds(Config.SESSION_TIMEOUT_SEC.intValue());
		tbrms.setCookieName(Config.AUTH_COOKIE);
		tbrms.setParameter(Config.AUTH_COOKIE.concat("-remember-me"));
		http.rememberMe().rememberMeServices(tbrms);

		PasswordAuthFilter passwordFilter = new PasswordAuthFilter("/" + PasswordAuthFilter.PASSWORD_ACTION);
		passwordFilter.setAuthenticationManager(authenticationManager());
		passwordFilter.setAuthenticationSuccessHandler(successHandler);
		passwordFilter.setAuthenticationFailureHandler(failureHandler);
		passwordFilter.setRememberMeServices(tbrms);

		OpenIDAuthFilter openidFilter = new OpenIDAuthFilter("/" + OpenIDAuthFilter.OPENID_ACTION);
		openidFilter.setAuthenticationManager(authenticationManager());
		openidFilter.setConsumer(new OpenID4JavaConsumer(new SimpleAxFetchListFactory()));
		openidFilter.setReturnToUrlParameters(Collections.singleton(confMap.get("security.returnto")));
		openidFilter.setAuthenticationSuccessHandler(successHandler);
		openidFilter.setAuthenticationFailureHandler(failureHandler);
		openidFilter.setRememberMeServices(tbrms);

		FacebookAuthFilter facebookFilter = new FacebookAuthFilter("/" + FacebookAuthFilter.FACEBOOK_ACTION);
		facebookFilter.setAuthenticationManager(authenticationManager());
		facebookFilter.setAuthenticationSuccessHandler(successHandler);
		facebookFilter.setAuthenticationFailureHandler(failureHandler);
		facebookFilter.setRememberMeServices(tbrms);

		http.addFilterAfter(passwordFilter, BasicAuthenticationFilter.class);
		http.addFilterAfter(openidFilter, BasicAuthenticationFilter.class);
		http.addFilterAfter(facebookFilter, BasicAuthenticationFilter.class);

		if (enableRestFilter) {
			RestAuthFilter restFilter = new RestAuthFilter(new Signer());
			http.addFilterAfter(restFilter, BasicAuthenticationFilter.class);
		}
	}

}
