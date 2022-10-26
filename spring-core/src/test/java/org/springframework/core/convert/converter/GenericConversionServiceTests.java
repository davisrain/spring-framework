package org.springframework.core.convert.converter;

import org.junit.jupiter.api.Test;
import org.springframework.core.convert.support.GenericConversionService;

class GenericConversionServiceTests {

	private GenericConversionService genericConversionService = new GenericConversionService();

	@Test
	void testAddConvert() {
		genericConversionService.addConverter(new TestConvert());
	}

	static class TestConvert implements Converter<String, Integer> {

		@Override
		public Integer convert(String source) {
			return Integer.parseInt(source);
		}
	}
}
