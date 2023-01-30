/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.aggregation.fieldtype;

import io.activej.aggregation.measure.HyperLogLog;
import io.activej.aggregation.util.JsonCodec;
import io.activej.codegen.expression.Expression;
import io.activej.codegen.expression.Expressions;
import io.activej.serializer.SerializerDef;
import io.activej.serializer.StringFormat;
import io.activej.serializer.impl.*;
import io.activej.types.Primitives;
import io.activej.types.Types;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static io.activej.aggregation.fieldtype.JsonCodecs.*;
import static io.activej.codegen.expression.Expressions.*;
import static io.activej.serializer.StringFormat.UTF8;
import static java.time.temporal.ChronoUnit.DAYS;

public final class FieldTypes {

	public static FieldType<Byte> ofByte() {
		return new FieldType<>(byte.class, new SerializerDef_Byte(false), BYTE_CODEC) {
			@Override
			public Expression toStringValue(Expression value) {
				return Expressions.staticCall(Byte.class, "toString", value);
			}
		};
	}

	public static FieldType<Short> ofShort() {
		return new FieldType<>(short.class, new SerializerDef_Short(false), SHORT_CODEC) {
			@Override
			public Expression toStringValue(Expression value) {
				return Expressions.staticCall(Short.class, "toString", value);
			}
		};
	}

	public static FieldType<Integer> ofInt() {
		return new FieldType<>(int.class, new SerializerDef_Int(false, true), INTEGER_CODEC);
	}

	public static FieldType<Long> ofLong() {
		return new FieldType<>(long.class, new SerializerDef_Long(false, true), LONG_CODEC);
	}

	public static FieldType<Float> ofFloat() {
		return new FieldType<>(float.class, new SerializerDef_Float(false), FLOAT_CODEC);
	}

	public static FieldType<Double> ofDouble() {
		return new FieldType<>(double.class, new SerializerDef_Double(false), DOUBLE_CODEC);
	}

	public static FieldType<Character> ofChar() {
		return new FieldType<>(char.class, new SerializerDef_Char(false), CHARACTER_CODEC);
	}

	public static FieldType<Boolean> ofBoolean() {
		return new FieldType<>(boolean.class, new SerializerDef_Boolean(false), BOOLEAN_CODEC);
	}

	public static FieldType<Integer> ofHyperLogLog() {
		return new FieldType<>(HyperLogLog.class, int.class, serializerDefHyperLogLog(), INTEGER_CODEC, null);
	}

	private static SerializerDef serializerDefHyperLogLog() {
		SerializerDef_Class serializer = SerializerDef_Class.create(HyperLogLog.class);
		try {
			serializer.addGetter(HyperLogLog.class.getMethod("getRegisters"),
					new SerializerDef_Array(new SerializerDef_Byte(false), byte[].class), -1, -1);
			serializer.setConstructor(HyperLogLog.class.getConstructor(byte[].class),
					List.of("registers"));
		} catch (NoSuchMethodException ignored) {
			throw new RuntimeException("Unable to construct SerializerDef for HyperLogLog");
		}
		return serializer;
	}

	public static <T> FieldType<Set<T>> ofSet(FieldType<T> fieldType) {
		SerializerDef itemSerializer = fieldType.getSerializer();
		if (itemSerializer instanceof SerializerDef_Primitive) {
			itemSerializer = ((SerializerDef_Primitive) itemSerializer).ensureWrapped();
		}
		SerializerDef_Set serializer = new SerializerDef_Set(itemSerializer);
		Type wrappedNestedType = fieldType.getDataType() instanceof Class ?
				Primitives.wrap((Class<?>) fieldType.getDataType()) :
				fieldType.getDataType();
		Type dataType = Types.parameterizedType(Set.class, wrappedNestedType);
		JsonCodec<Set<T>> codec = JsonCodecs.ofSet(fieldType.getCodec());
		return new FieldType<>(Set.class, dataType, serializer, codec, codec);
	}

	public static <E extends Enum<E>> FieldType<E> ofEnum(Class<E> enumClass) {
		return new FieldType<>(enumClass, new SerializerDef_Enum(enumClass), JsonCodecs.ofEnum(enumClass));
	}

	public static FieldType<String> ofString() {
		return ofString(UTF8);
	}

	public static FieldType<String> ofString(StringFormat format) {
		return new FieldType<>(String.class, new SerializerDef_String(format), STRING_CODEC) {
			@Override
			public Expression toStringValue(Expression value) {
				return value;
			}
		};
	}

	public static FieldType<LocalDate> ofLocalDate() {
		return new FieldTypeDate();
	}

	public static FieldType<LocalDate> ofLocalDate(LocalDate startDate) {
		return new FieldTypeDate(startDate);
	}

	public static final class FieldTypeDate extends FieldType<LocalDate> {
		private final LocalDate startDate;

		FieldTypeDate() {
			this(LocalDate.parse("1970-01-01"));
		}

		FieldTypeDate(LocalDate startDate) {
			super(int.class, LocalDate.class, new SerializerDef_Int(false, true), LOCAL_DATE_CODEC, INTEGER_CODEC);
			this.startDate = startDate;
		}

		@Override
		public Expression toValue(Expression internalValue) {
			return call(value(startDate), "plusDays", cast(internalValue, long.class));
		}

		@Override
		public LocalDate toInitialValue(Object internalValue) {
			return startDate.plusDays((int) internalValue);
		}

		@Override
		public Object toInternalValue(LocalDate value) {
			return (int) DAYS.between(startDate, value);
		}

	}

}
