/*
 * Copyright 2002-2013 the original author or authors.
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

package org.springframework.integration.support.json;

import java.io.File;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.Collection;
import java.util.Map;

import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.JavaType;

import org.springframework.integration.json.JsonHeaders;
import org.springframework.util.Assert;

/**
 * Jackson JSON-processor (@link http://jackson.codehaus.org) {@linkplain JsonObjectMapper} implementation.
 * Delegates <code>toJson</code> and <code>fromJson</code>
 * to the {@linkplain org.codehaus.jackson.map.ObjectMapper}
 *
 * @author Artem Bilan
 * @since 3.0
 */
public class JacksonJsonObjectMapper extends AbstractJacksonJsonObjectMapper<JsonParser, JavaType> {

	private final ObjectMapper objectMapper;

	public JacksonJsonObjectMapper() {
		this.objectMapper = new ObjectMapper();
	}

	public JacksonJsonObjectMapper(ObjectMapper objectMapper) {
		Assert.notNull(objectMapper, "objectMapper must not be null");
		this.objectMapper = objectMapper;
	}

	@Override
	public String toJson(Object value) throws Exception {
		return this.objectMapper.writeValueAsString(value);
	}

	@Override
	public void toJson(Object value, Writer writer) throws Exception {
		this.objectMapper.writeValue(writer, value);
	}

	@Override
	public <T> T fromJson(JsonParser parser, Type valueType) throws Exception {
		return this.objectMapper.readValue(parser, this.constructType(valueType));
	}

	@Override
	protected <T> T fromJson(Object json, JavaType type) throws Exception {
		if (json instanceof String) {
			return this.objectMapper.readValue((String) json, type);
		}
		else if(json instanceof byte[]) {
			return this.objectMapper.readValue((byte[]) json, type);
		}
		else if (json instanceof File) {
			return this.objectMapper.readValue((File) json, type);
		}
		else if (json instanceof URL) {
			return this.objectMapper.readValue((URL) json, type);
		}
		else if (json instanceof InputStream) {
			return this.objectMapper.readValue((InputStream) json, type);
		}
		else if (json instanceof Reader) {
			return this.objectMapper.readValue((Reader) json, type);
		}
		else {
			throw new IllegalArgumentException("'json' argument must be an instance of: " + supportedJsonTypes);
		}
	}

	@Override
	public void populateJavaTypes(Map<String, Object> map, Class<?> sourceClass) {
		JavaType javaType = this.constructType(sourceClass);
		map.put(JsonHeaders.TYPE_ID, javaType.getRawClass());

		if (javaType.isContainerType() && !javaType.isArrayType()) {
			map.put(JsonHeaders.CONTENT_TYPE_ID, javaType.getContentType().getRawClass());
		}

		if (javaType.getKeyType() != null) {
			map.put(JsonHeaders.KEY_TYPE_ID, javaType.getKeyType().getRawClass());
		}
	}

	@Override
	protected JavaType constructType(Type type) {
		return this.objectMapper.constructType(type);
	}

	@Override
	@SuppressWarnings({ "unchecked" })
	protected JavaType extractJavaType(Map<String, Object> javaTypes) throws Exception {
		JavaType classType = this.createJavaType(javaTypes, JsonHeaders.TYPE_ID);
		if (!classType.isContainerType() || classType.isArrayType()) {
			return classType;
		}

		JavaType contentClassType = this.createJavaType(javaTypes, JsonHeaders.CONTENT_TYPE_ID);
		if (classType.getKeyType() == null) {
			return this.objectMapper.getTypeFactory()
					.constructCollectionType((Class<? extends Collection<?>>) classType.getRawClass(), contentClassType);
		}

		JavaType keyClassType = this.createJavaType(javaTypes, JsonHeaders.KEY_TYPE_ID);
		return this.objectMapper.getTypeFactory()
				.constructMapType((Class<? extends Map<?, ?>>) classType.getRawClass(), keyClassType, contentClassType);
	}

}
