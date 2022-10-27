package org.springframework.core.convert.converter;

import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.support.GenericConversionService;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

class GenericConversionServiceTests {

	private final GenericConversionService genericConversionService = new GenericConversionService();

	@Test
	void testAddConvert() {
		genericConversionService.addConverter(new ObjectListToStringListConvert());
		List<Object> source = new ArrayList<>();
		source.add(2);
		source.add(new BigDecimal("1.11"));
		genericConversionService.convert(source, TypeDescriptor.forObject(source),
				new TypeDescriptor(ResolvableType.forType(new TypeToken<List<String>>(){}.getType()),null, null));
	}

	static class TypeToken<T> {

		Type getType() {
			Type genericSuperclass = getClass().getGenericSuperclass();
			ParameterizedType parameterizedType = (ParameterizedType) genericSuperclass;
			return parameterizedType.getActualTypeArguments()[0];
		}
	}

	static class TestConvert implements Converter<String, Integer> {

		@Override
		public Integer convert(String source) {
			return Integer.parseInt(source);
		}
	}

	static class ObjectListToStringListConvert implements Converter<List<Object>, List<String>> {

		@Override
		public List<String> convert(List<Object> source) {
			List<String> result = new ArrayList<>();
			for (Object sourceElement : source) {
				result.add(sourceElement.toString());
			}
			return result;
		}
	}
}
