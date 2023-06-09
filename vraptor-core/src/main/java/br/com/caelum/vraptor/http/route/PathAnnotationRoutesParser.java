/***
 * Copyright (c) 2009 Caelum - www.caelum.com.br/opensource
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package br.com.caelum.vraptor.http.route;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Predicates.instanceOf;
import static com.google.common.base.Predicates.or;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Arrays.asList;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import br.com.caelum.vraptor.Delete;
import br.com.caelum.vraptor.Get;
import br.com.caelum.vraptor.Options;
import br.com.caelum.vraptor.Patch;
import br.com.caelum.vraptor.Path;
import br.com.caelum.vraptor.Post;
import br.com.caelum.vraptor.Put;
import br.com.caelum.vraptor.controller.BeanClass;
import br.com.caelum.vraptor.controller.HttpMethod;
import br.com.caelum.vraptor.core.ReflectionProvider;
import br.com.caelum.vraptor.util.StringUtils;

import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;

/**
 * The default parser routes creator uses the path annotation to create rules.
 * Note that methods are only registered to be public accessible if the type is
 * annotated with @Controller.
 *
 * If you want to override the convention for default URI, you can create a
 * class like:
 *
 * public class MyRoutesParser extends PathAnnotationRoutesParser { //delegate
 * constructor protected String extractControllerNameFrom(Class&lt;?&gt; type) {
 * return //your convention here }
 *
 * protected String defaultUriFor(String controllerName, String methodName) {
 * return //your convention here } }
 *
 * @author Guilherme Silveira
 * @author Lucas Cavalcanti
 */
@ApplicationScoped
public class PathAnnotationRoutesParser implements RoutesParser {

	private final Router router;
	private ReflectionProvider reflectionProvider;

	/** 
	 * @deprecated CDI eyes only
	 */

	protected PathAnnotationRoutesParser() {
		this(null, null);
	}

	@Inject
	public PathAnnotationRoutesParser(Router router, ReflectionProvider reflectionProvider) {
		this.router = router;
		this.reflectionProvider = reflectionProvider;
	}

	@Override
	public List<Route> rulesFor(BeanClass controller) {
		Class<?> baseType = controller.getType();
		return registerRulesFor(baseType);
	}

	protected List<Route> registerRulesFor(Class<?> baseType) {
		EnumSet<HttpMethod> typeMethods = getHttpMethods(baseType);

		List<Route> routes = new ArrayList<>();
		for (Method javaMethod : baseType.getMethods()) {
			if (isEligible(javaMethod)) {
				String[] uris = getURIsFor(javaMethod, baseType);

				for (String uri : uris) {
					RouteBuilder rule = router.builderFor(uri);

					EnumSet<HttpMethod> methods = getHttpMethods(javaMethod);

					rule.with(methods.isEmpty() ? typeMethods : methods);

					if(javaMethod.isAnnotationPresent(Path.class)){
						rule.withPriority(javaMethod.getAnnotation(Path.class).priority());
					}

					if (getUris(javaMethod).length > 0) {
						rule.withPriority(Path.DEFAULT);
					}

					rule.is(baseType, javaMethod);
					routes.add(rule.build());
				}
			}
		}

		return routes;
	}

	private EnumSet<HttpMethod> getHttpMethods(AnnotatedElement annotated) {
		EnumSet<HttpMethod> methods = EnumSet.noneOf(HttpMethod.class);
		for (HttpMethod method : HttpMethod.values()) {
			if (annotated.isAnnotationPresent(method.getAnnotation())) {
				methods.add(method);
			}
		}
		return methods;
	}

	protected boolean isEligible(Method javaMethod) {
		return Modifier.isPublic(javaMethod.getModifiers())
			&& !Modifier.isStatic(javaMethod.getModifiers())
			&& !javaMethod.isBridge()
			&& !javaMethod.getDeclaringClass().equals(Object.class);
	}

	protected String[] getURIsFor(Method javaMethod, Class<?> type) {

		if (javaMethod.isAnnotationPresent(Path.class)) {
			String[] uris = javaMethod.getAnnotation(Path.class).value();

			checkArgument(uris.length > 0, "You must specify at least one path on @Path at %s", javaMethod);
			checkArgument(getUris(javaMethod).length == 0,
					"You should specify paths either in @Path(\"/path\") or @Get(\"/path\") (or @Post, @Put, @Delete), not both at %s", javaMethod);

			fixURIs(type, uris);
			return uris;
		}
		String[] uris = getUris(javaMethod);

		if(uris.length > 0){
			fixURIs(type, uris);
			return uris;
		}

		return new String[] { defaultUriFor(extractControllerNameFrom(type), javaMethod.getName()) };
	}

	protected String[] getUris(Method javaMethod){
		Annotation method = FluentIterable.from(asList(javaMethod.getAnnotations()))
				.filter(instanceOfMethodAnnotation())
				.first().orNull();

		if (method == null) {
			return new String[0];
		}
		return (String[]) reflectionProvider.invoke(method, "value");
	}

	protected void fixURIs(Class<?> type, String[] uris) {
		String prefix = extractPrefix(type);
		for (int i = 0; i < uris.length; i++) {
			if (isNullOrEmpty(prefix)) {
				uris[i] = fixLeadingSlash(uris[i]);
			} else if (isNullOrEmpty(uris[i])) {
				uris[i] = prefix;
			} else {
				uris[i] = removeTrailingSlash(prefix) + fixLeadingSlash(uris[i]);
			}
		}
	}

	protected String removeTrailingSlash(String prefix) {
		return prefix.replaceFirst("/$", "");
	}

	protected String extractPrefix(Class<?> type) {
		if (type.isAnnotationPresent(Path.class)) {
			String[] uris = type.getAnnotation(Path.class).value();
			checkArgument(uris.length == 1, "You must specify exactly one path on @Path at %s", type);
			return fixLeadingSlash(uris[0]);
		} else {
			return "";
		}
	}

	private static String fixLeadingSlash(String uri) {
		if (!uri.startsWith("/")) {
			return  "/" + uri;
		}
		return uri;
	}

	/**
	 * You can override this method for use a different convention for your
	 * controller name, given a type
	 */
	protected String extractControllerNameFrom(Class<?> type) {
		String prefix = extractPrefix(type);
		if (isNullOrEmpty(prefix)) {
			String baseName = StringUtils.lowercaseFirst(type.getSimpleName());
			if (baseName.endsWith("Controller")) {
				return "/" + baseName.substring(0, baseName.lastIndexOf("Controller"));
			}
			return "/" + baseName;
		} else {
			return prefix;
		}
	}

	/**
	 * You can override this method for use a different convention for your
	 * default URI, given a controller name and a method name
	 */
	protected String defaultUriFor(String controllerName, String methodName) {
		return controllerName + "/" + methodName;
	}


	private Predicate<Annotation> instanceOfMethodAnnotation() {
		return or(instanceOf(Get.class), instanceOf(Post.class), instanceOf(Put.class), instanceOf(Delete.class), instanceOf(Options.class), instanceOf(Patch.class));
	}

}
